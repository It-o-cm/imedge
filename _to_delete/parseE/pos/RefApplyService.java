package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.barcode.AlertLevel;
import com.intermarche.pos.domain.barcode.CouponControl;
import com.intermarche.pos.domain.barcode.CouponField;
import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.domain.catalog.*;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.setting.TouchGroupSetting;
import com.intermarche.pos.domain.sync.RefState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Register-side application of a referential snapshot (phase 6 lot 3), one
 * transaction per domain. Rows are upserted by natural key; rows absent from
 * the snapshot are deactivated, never deleted (ticket lines hold product
 * foreign keys) — except prices, replaced as a whole (nothing references
 * them: ticket lines snapshot their values). Employee lockout counters are
 * local operational state and survive the upsert.
 * <p>
 * Failure semantics: each domain commits alone, and the fingerprint is
 * recorded in a SEPARATE transaction after the apply — a crash between the
 * two merely re-pulls and re-applies the same snapshot on the next cycle
 * (upserts converge, the price replacement is wholesale: harmless). Known
 * edge: two products SWAPPING their PLUs inside one snapshot can trip the
 * unique constraint depending on row order (same exposure as the CSV
 * import). Snapshots are applied from memory one full domain at a time,
 * which is deliberate and bounded by catalog size.
 */
@ApplicationScoped
public class RefApplyService {

    private static final Logger LOGGER = Logger.getLogger(RefApplyService.class);

    /**
     * The document renderer, told to forget its parsed templates when the pull
     * rewrites them underneath (BO-03-03).
     */
    @jakarta.inject.Inject
    com.intermarche.pos.service.DocumentTemplateService documentTemplateService;

    /**
     * Applies a family snapshot: upsert by code (families carry no active
     * flag; absents are left in place), then rebuilds the GROUP TREE and the
     * article memberships the snapshot describes.
     * <p>
     * The edges matter as much as the rows: without them a register receives
     * flat, empty groups, and the touch grid shows buttons with nothing behind
     * them — everything the back office administered would stop at the store
     * node. They are rebuilt in three passes because a snapshot ordered by code
     * puts parents and children in any order, and because clearing a collection
     * while wiring another would erase what an earlier row had just received.
     * Articles are resolved by EAN and are already applied at this point:
     * PRODUCTS comes before FAMILIES in {@code DOMAINS} for that reason.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyFamilies(List<RefPayloads.FamilyDto> dtos) {
        LOGGER.info("Entering method applyFamilies with dtos: " + dtos);
        // FIRST PASS: the rows themselves. The edges cannot be wired here —
        // a parent may appear after its child in a snapshot ordered by code.
        Map<String, ProductFamily> byCode = new HashMap<>();
        for (RefPayloads.FamilyDto dto : dtos) {
            ProductFamily family = ProductFamily.find("code", dto.code).firstResult();
            if (family == null) {
                family = new ProductFamily();
                family.code = dto.code;
            }
            family.description = dto.description;
            family.flags = dto.flags;
            family.persist();
            byCode.put(dto.code, family);
        }
        // SECOND PASS: empty every edge collection BEFORE wiring any of them.
        // Clearing inside the wiring loop would erase the children a family
        // received from a row processed earlier — the snapshot is applied in
        // code order, a parent can come after its child.
        for (RefPayloads.FamilyDto dto : dtos) {
            ProductFamily family = byCode.get(dto.code);
            family.productFamilies.clear();
            family.products.clear();
        }
        // THIRD PASS: the tree and the memberships. Both collections are
        // REPLACED, never merged: the snapshot is the truth, so a family
        // removed from a group upstream leaves it here too.
        for (RefPayloads.FamilyDto dto : dtos) {
            ProductFamily family = byCode.get(dto.code);
            for (String parentCode : dto.parentCodes) {
                ProductFamily parent = byCode.get(parentCode);
                if (parent != null) {
                    parent.productFamilies.add(family);
                }
            }
            for (String ean : dto.productEans) {
                Product product = Product.find("ean", ean).firstResult();
                if (product != null) {
                    family.products.add(product);
                }
            }
            family.persist();
        }
        LOGGER.infof("Référentiel familles appliqué: %d ligne(s)", dtos.size());
        LOGGER.info("Exiting method applyFamilies");
    }

    /**
     * Applies a touch-configuration snapshot: upsert by group code, absents
     * deleted.
     * <p>
     * DELETED and not merely ignored, unlike the article referential where an
     * absent row is deactivated: a group that no longer has a configured touch
     * must fall back to the defaults, and leaving a stale row behind would keep
     * it pinned or oversized long after the back office un-pinned it.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyTouchGroups(List<RefPayloads.TouchGroupDto> dtos) {
        LOGGER.info("Entering method applyTouchGroups with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.TouchGroupDto dto : dtos) {
            TouchGroupSetting setting = TouchGroupSetting.findByFamilyCode(dto.familyCode);
            if (setting == null) {
                setting = new TouchGroupSetting();
                setting.familyCode = dto.familyCode;
            }
            setting.pinned = dto.pinned;
            setting.buttonSize = dto.buttonSize == null
                    ? TouchGroupSetting.DEFAULT_BUTTON_SIZE : dto.buttonSize;
            setting.displayOrder = dto.displayOrder;
            setting.salesVolume = dto.salesVolume;
            setting.persist();
            seen.add(dto.familyCode);
        }
        for (TouchGroupSetting setting : TouchGroupSetting.<TouchGroupSetting>listAll()) {
            if (!seen.contains(setting.familyCode)) {
                setting.delete();
            }
        }
        LOGGER.infof("Référentiel touches appliqué: %d ligne(s)", dtos.size());
        LOGGER.info("Exiting method applyTouchGroups");
    }

    /**
     * Applies a product snapshot: upsert by EAN, absents deactivated.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyProducts(List<RefPayloads.ProductDto> dtos) {
        LOGGER.info("Entering method applyProducts with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.ProductDto dto : dtos) {
            Product product = Product.find("ean", dto.ean).firstResult();
            if (product == null) {
                product = new Product();
                product.ean = dto.ean;
            }
            product.plu = dto.plu;
            product.name = dto.name;
            product.description = dto.description;
            product.icon = dto.icon;
            product.imageData = dto.imageData;
            product.brand = dto.brand;
            product.referenceWeight = dto.referenceWeight;
            product.referenceVolume = dto.referenceVolume;
            product.productType = dto.productType != null ? ProductType.valueOf(dto.productType) : null;
            product.unitName = dto.unitName;
            product.active = dto.active;
            product.forbiddenToSale = dto.forbiddenToSale;
            product.ageRestriction = dto.ageRestriction;
            product.checkoutLabel = dto.checkoutLabel;
            product.infoMessage = dto.infoMessage;
            product.internalCode = dto.internalCode;
            product.variableWeight = dto.variableWeight;
            product.attributes = dto.attributes != null
                    ? new java.util.HashMap<>(dto.attributes) : new java.util.HashMap<>();
            product.persist();
            seen.add(dto.ean);
        }
        int deactivated = deactivateAbsentProducts(seen);
        LOGGER.infof("Référentiel produits appliqué: %d ligne(s), %d désactivé(s)", dtos.size(), deactivated);
        LOGGER.info("Exiting method applyProducts");
    }

    /**
     * Replaces the VAT referential with the snapshot pulled from the node
     * (BO-02-03-14).
     *
     * <p>Applied BEFORE the prices, and that order is the contract: a price
     * names its regime by number, so the table has to be there when the price
     * rows land. A regime a price names and that this snapshot does not carry
     * simply leaves that price without a rate, which the scan paths already
     * know how to survive — they fall back on the administered default.
     *
     * @param dtos the regimes of the snapshot
     */
    @Transactional
    public void applyVatRates(List<RefPayloads.VatRateDto> dtos) {
        LOGGER.info("Entering method applyVatRates with dtos: " + dtos);
        Price.update("set vat = null where vat is not null");
        VatRate.deleteAll();
        for (RefPayloads.VatRateDto dto : dtos) {
            VatRate rate = new VatRate();
            rate.number = dto.number;
            rate.rate = dto.rate;
            rate.label = dto.label;
            rate.persist();
        }
        LOGGER.infof("Référentiel TVA remplacé: %d régime(s)", dtos.size());
        LOGGER.info("Exiting method applyVatRates");
    }

    /** Imports the PRODUCTS file the way the store node imports it. */
    @Inject
    com.intermarche.pos.imports.ProductCsvResource productImporter;

    /** Imports the FAMILIES file the way the store node imports it. */
    @Inject
    com.intermarche.pos.imports.ProductFamilyCsvResource familyImporter;

    /** Imports the PRICES file the way the store node imports it. */
    @Inject
    com.intermarche.pos.imports.PriceCsvResource priceImporter;

    /**
     * Reads the verbatim file of one engine feed this register holds.
     *
     * <p>It lives here, and not in the pull loop that needs it, because that
     * loop runs on its own thread where neither a transaction nor a CDI
     * request context is active: a Panache lookup issued from there fails
     * outright. It is also kept APART from
     * {@link #applyFromFile(String, String)} on purpose — that one must not
     * open a transaction of its own, the importer opening one per chunk.
     *
     * @param domain the feed code, which is also the referential domain
     * @return the raw file, or null when this register holds no such feed
     */
    @Transactional
    public String feedContent(String domain) {
        LOGGER.info("Entering method feedContent with domain: " + domain);
        com.intermarche.pos.domain.sync.EngineFeed feed =
                com.intermarche.pos.domain.sync.EngineFeed.<com.intermarche.pos.domain.sync.EngineFeed>
                        find("code", domain).firstResult();
        LOGGER.info("Exiting method feedContent");
        return feed == null ? null : feed.content;
    }

    /**
     * Applies one of the three big shared referentials from the RAW FILE the
     * register already holds, instead of from a snapshot of payload rows.
     *
     * <p>Why the file and not the pages. The register pulls the verbatim feeds
     * anyway — it is what it delivers to the valuation engine — so the catalogue
     * is already on its disk, header line included. Applying it through the
     * SAME importer the store node uses buys three things the payload path did
     * not have: the work is cut into transactions of a thousand rows instead of
     * one transaction for a hundred thousand; nothing is accumulated in memory;
     * and a row whose checksum has not moved costs nothing, where the payload
     * path wiped the table and rewrote it whole.
     *
     * <p>A failure inside a chunk is not a failure of the pull: the importer
     * falls back 1000 → 100 → 10 → 1 and isolates the offending rows, exactly
     * as it does at the node. What comes back is a count, and the caller records
     * the fingerprint on it.
     *
     * <p>The importer's own answer is returned rather than a boolean, because
     * this method now has two callers: the pull loop, which only needs to know
     * that the content served, and the CSV door of a register running without
     * a store node, which hands the counts back to whoever pushed the file.
     *
     * @param domain the domain being applied, which names the importer
     * @param content the raw CSV, verbatim
     * @return the importer's answer, or null when no importer serves that domain
     */
    @jakarta.enterprise.context.control.ActivateRequestContext
    public jakarta.ws.rs.core.Response applyFromFile(String domain, String content) {
        LOGGER.info("Entering method applyFromFile with domain: " + domain);
        java.io.InputStream stream = new java.io.ByteArrayInputStream(
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jakarta.ws.rs.core.Response answer = switch (domain) {
            case "PRODUCTS" -> productImporter.importProducts(stream);
            case "FAMILIES" -> familyImporter.importProductFamilies(stream);
            case "PRICES" -> priceImporter.importPrices(stream);
            default -> null;
        };
        if (answer == null) {
            LOGGER.info("Exiting method applyFromFile: domaine non servi par cette voie");
            return null;
        }
        LOGGER.infof("Référentiel %s appliqué depuis le contenu: %s", domain, answer.getEntity());
        LOGGER.info("Exiting method applyFromFile");
        return answer;
    }

    /**
     * Applies a price snapshot: full replacement (nothing references price
     * rows). Rows pointing to a product unknown on this register are skipped
     * and counted.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyPrices(List<RefPayloads.PriceDto> dtos) {
        LOGGER.info("Entering method applyPrices with dtos: " + dtos);
        Price.deleteAll();
        int skipped = 0;
        for (RefPayloads.PriceDto dto : dtos) {
            Product product = dto.productEan != null
                    ? Product.<Product>find("ean", dto.productEan).firstResult()
                    : null;
            if (product == null) {
                skipped++;
                continue;
            }
            Price price = new Price();
            price.product = product;
            price.priceExcludingTax = dto.priceExcludingTax;
            price.priceIncludingTax = dto.priceIncludingTax;
            price.vat = VatRate.findByNumber(dto.vatNumber);
            price.priority = dto.priority != null ? dto.priority : 0;
            price.startDateTime = parse(dto.startDateTime);
            price.endDateTime = parse(dto.endDateTime);
            price.persist();
        }
        LOGGER.infof("Référentiel prix remplacé: %d ligne(s), %d orpheline(s) ignorée(s)",
                dtos.size() - skipped, skipped);
        LOGGER.info("Exiting method applyPrices");
    }

    /**
     * Applies an employee snapshot: upsert by login, absents deactivated,
     * local lockout counters preserved.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyEmployees(List<RefPayloads.EmployeeDto> dtos) {
        LOGGER.info("Entering method applyEmployees with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.EmployeeDto dto : dtos) {
            Employee employee = Employee.find("loginName", dto.loginName).firstResult();
            if (employee == null) {
                employee = new Employee();
                employee.loginName = dto.loginName;
            }
            employee.firstName = dto.firstName;
            employee.lastName = dto.lastName;
            employee.password = dto.password;
            employee.email = dto.email;
            employee.role = Employee.EmployeeRole.valueOf(dto.role);
            employee.badgeId = dto.badgeId;
            // A theme chosen AT THE REGISTER wins over the pulled one: the
            // register-side selector must not be mysteriously undone by the
            // next referential pull. The store's value only seeds employees
            // who never chose.
            if (employee.theme == null) {
                employee.theme = dto.theme;
            }
            employee.active = dto.active;
            // failedAttempts / lockedUntil are local state: untouched
            employee.persist();
            seen.add(dto.loginName);
        }
        long deactivated = 0;
        for (Employee employee : Employee.<Employee>listAll()) {
            if (!seen.contains(employee.loginName) && employee.active) {
                employee.active = false;
                employee.persist();
                deactivated++;
            }
        }
        LOGGER.infof("Référentiel employés appliqué: %d ligne(s), %d désactivé(s)", dtos.size(), deactivated);
        LOGGER.info("Exiting method applyEmployees");
    }

    /**
     * Applies a coupon-type snapshot: upsert by code, absents deactivated.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyCouponTypes(List<RefPayloads.CouponTypeDto> dtos) {
        LOGGER.info("Entering method applyCouponTypes with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.CouponTypeDto dto : dtos) {
            CouponType type = CouponType.find("code", dto.code).firstResult();
            if (type == null) {
                type = new CouponType();
                type.code = dto.code;
            }
            type.label = dto.label;
            type.matchPattern = dto.matchPattern;
            type.amountSource = CouponType.AmountSource.valueOf(dto.amountSource);
            type.amountPattern = dto.amountPattern;
            type.priority = dto.priority;
            type.active = dto.active;
            type.depositLine = dto.depositLine;
            type.prefix = dto.prefix;
            type.codeLength = dto.codeLength;
            type.codeKind = dto.codeKind == null
                    ? CouponField.Kind.NUMERIC
                    : CouponField.Kind.valueOf(dto.codeKind);
            type.manualAmountOnAllNines = dto.manualAmountOnAllNines;
            type.islandCodes = dto.islandCodes;
            applyCouponFields(type, dto);
            applyCouponControls(type, dto);
            type.persist();
            seen.add(dto.code);
        }
        long deactivated = 0;
        for (CouponType type : CouponType.<CouponType>listAll()) {
            if (!seen.contains(type.code) && type.active) {
                type.active = false;
                type.persist();
                deactivated++;
            }
        }
        LOGGER.infof("Référentiel types de bons appliqué: %d ligne(s), %d désactivé(s)", dtos.size(), deactivated);
        LOGGER.info("Exiting method applyCouponTypes");
    }

    /**
     * Applies the PROVIDER MESSAGE substitutions of the snapshot
     * (BO-03-08-06 and -07).
     *
     * <p>Upsert on the pair provider plus code, and a row the snapshot no
     * longer carries is DELETED rather than deactivated: unlike a document or
     * a tender, a substitution nobody declares any more has no history to
     * keep, and leaving it inactive in the register's base would make the next
     * shop that reuses the code inherit someone else's sentence.
     *
     * @param dtos the substitutions the node published
     */
    @Transactional
    public void applyProviderMessages(List<RefPayloads.ProviderMessageDto> dtos) {
        LOGGER.info("Entering method applyProviderMessages with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.ProviderMessageDto dto : dtos) {
            com.intermarche.pos.domain.setting.ProviderMessage message =
                    com.intermarche.pos.domain.setting.ProviderMessage
                            .find("providerCode = ?1 and messageCode = ?2",
                                    dto.providerCode, dto.messageCode)
                            .firstResult();
            if (message == null) {
                message = new com.intermarche.pos.domain.setting.ProviderMessage();
                message.providerCode = dto.providerCode;
                message.messageCode = dto.messageCode;
            }
            message.screenText = dto.screenText;
            message.ticketText = dto.ticketText;
            message.active = dto.active;
            message.persist();
            seen.add(dto.providerCode + "|" + dto.messageCode);
        }
        long removed = 0;
        for (com.intermarche.pos.domain.setting.ProviderMessage message
                : com.intermarche.pos.domain.setting.ProviderMessage
                        .<com.intermarche.pos.domain.setting.ProviderMessage>listAll()) {
            if (!seen.contains(message.providerCode + "|" + message.messageCode)) {
                message.delete();
                removed++;
            }
        }
        LOGGER.infof("Référentiel messages fournisseur appliqué: %d ligne(s), %d retirée(s)",
                dtos.size(), removed);
        LOGGER.info("Exiting method applyProviderMessages");
    }

    /**
     * Applies a DOCUMENT TEMPLATE snapshot: upsert by code, absents deactivated
     * (BO-03-03).
     *
     * <p>Same contract as every other domain — the snapshot is the truth, a row
     * it no longer names stops laying out its document rather than lingering,
     * and the register then prints that document its own way again.
     *
     * <p>The parsed-template cache is cleared at the end: the rows changed
     * underneath the renderer, which has no other way of knowing.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyDocumentTemplates(List<RefPayloads.DocumentTemplateDto> dtos) {
        LOGGER.info("Entering method applyDocumentTemplates with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.DocumentTemplateDto dto : dtos) {
            com.intermarche.pos.domain.setting.DocumentTemplate template =
                    com.intermarche.pos.domain.setting.DocumentTemplate
                            .find("code", dto.code).firstResult();
            if (template == null) {
                template = new com.intermarche.pos.domain.setting.DocumentTemplate();
                template.code = dto.code;
            }
            template.label = dto.label;
            template.documentType = documentType(dto.documentType);
            template.active = dto.active;
            template.priority = dto.priority;
            template.width = dto.width;
            template.copies = dto.copies;
            template.source = dto.source;
            template.title = dto.title;
            template.duplicateTitle = dto.duplicateTitle;
            template.numberPrefix = dto.numberPrefix;
            template.copyTitles = dto.copyTitles;
            template.persist();
            seen.add(dto.code);
        }
        long deactivated = 0;
        for (com.intermarche.pos.domain.setting.DocumentTemplate template
                : com.intermarche.pos.domain.setting.DocumentTemplate
                        .<com.intermarche.pos.domain.setting.DocumentTemplate>listAll()) {
            if (!seen.contains(template.code) && template.active) {
                template.active = false;
                template.persist();
                deactivated++;
            }
        }
        documentTemplateService.clearCache();
        LOGGER.infof("Référentiel gabarits appliqué: %d ligne(s), %d désactivé(s)",
                dtos.size(), deactivated);
        LOGGER.info("Exiting method applyDocumentTemplates");
    }

    /**
     * Reads a document type off a snapshot row, an absent or unknown one leaving
     * the template attached to no document.
     *
     * @param raw the type name as it travelled, or null
     * @return the type, or null when unrecognised
     */
    private com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType documentType(
            String raw) {
        for (com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType type
                : com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.values()) {
            if (type.name().equals(raw)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Applies a TENDER snapshot: upsert by settlement key, absents deactivated
     * (BO-03-02-03/04/10 to 30).
     *
     * <p>Same contract as every other domain — the snapshot is the truth, a row
     * it no longer names stops being offered rather than lingering. Deactivating
     * rather than deleting matters here more than elsewhere: the settlements
     * already registered on a sale name their tender by this key, and a key the
     * referential forgot would leave those lines unexplained.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyTenders(List<RefPayloads.TenderDefinitionDto> dtos) {
        LOGGER.info("Entering method applyTenders with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.TenderDefinitionDto dto : dtos) {
            com.intermarche.pos.domain.payment.TenderDefinition tender =
                    com.intermarche.pos.domain.payment.TenderDefinition
                            .find("code", dto.code).firstResult();
            if (tender == null) {
                tender = new com.intermarche.pos.domain.payment.TenderDefinition();
                tender.code = dto.code;
            }
            tender.functionalId = dto.functionalId;
            tender.label = dto.label;
            tender.active = dto.active;
            tender.displayOrder = dto.displayOrder;
            tender.maxAmount = decimal(dto.maxAmount);
            tender.maxAmountControl = control(dto.maxAmountControl);
            tender.secondMaxAmount = decimal(dto.secondMaxAmount);
            tender.secondMaxAmountControl = control(dto.secondMaxAmountControl);
            tender.minAmount = decimal(dto.minAmount);
            tender.minAmountControl = control(dto.minAmountControl);
            tender.maxCount = dto.maxCount;
            tender.maxCountControl = control(dto.maxCountControl);
            tender.maxChangeAmount = decimal(dto.maxChangeAmount);
            tender.maxChangeControl = control(dto.maxChangeControl);
            tender.refundAllowed = dto.refundAllowed;
            tender.changeAllowed = dto.changeAllowed;
            tender.changeTenderCode = dto.changeTenderCode;
            tender.cashierDeclaration = dto.cashierDeclaration;
            tender.automaticWithdrawal = dto.automaticWithdrawal;
            tender.drawerOpening = drawer(dto.drawerOpening);
            tender.movementAllowed = dto.movementAllowed;
            tender.bankDeposit = dto.bankDeposit;
            tender.floatAllowed = dto.floatAllowed;
            tender.defaultsToTotal = dto.defaultsToTotal;
            tender.withdrawalReportDetail = dto.withdrawalReportDetail;
            tender.fidelityReported = dto.fidelityReported;
            tender.documentTemplateCode = dto.documentTemplateCode;
            // A count nobody administered is ONE: a tender printing a slip
            // prints it, and a zero arriving from an older node must not make
            // the register stop printing cheque slips.
            tender.printCount = Math.max(1, dto.printCount);
            tender.printDuplicate = dto.printDuplicate;
            tender.printForced = dto.printForced;
            tender.persist();
            seen.add(dto.code);
        }
        long deactivated = 0;
        for (com.intermarche.pos.domain.payment.TenderDefinition tender
                : com.intermarche.pos.domain.payment.TenderDefinition
                        .<com.intermarche.pos.domain.payment.TenderDefinition>listAll()) {
            if (!seen.contains(tender.code) && tender.active) {
                tender.active = false;
                tender.persist();
                deactivated++;
            }
        }
        LOGGER.infof("Référentiel modes de règlement appliqué: %d ligne(s), %d désactivé(s)",
                dtos.size(), deactivated);
        LOGGER.info("Exiting method applyTenders");
    }

    /**
     * Reads a bound off a snapshot row, an absent or malformed one meaning
     * unbounded.
     *
     * @param raw the bound as it travelled, or null
     * @return the bound, or null when unbounded
     */
    private java.math.BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads a control level off a snapshot row, an absent or unknown one meaning
     * no control.
     *
     * @param raw the level name as it travelled, or null
     * @return the level, never null
     */
    private com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel control(String raw) {
        for (com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel level
                : com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.values()) {
            if (level.name().equals(raw)) {
                return level;
            }
        }
        return com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.NONE;
    }

    /**
     * Reads a drawer-opening moment off a snapshot row, an absent or unknown one
     * meaning that the drawer never opens.
     *
     * @param raw the moment name as it travelled, or null
     * @return the moment, never null
     */
    private com.intermarche.pos.domain.payment.TenderDefinition.DrawerOpening drawer(String raw) {
        for (com.intermarche.pos.domain.payment.TenderDefinition.DrawerOpening moment
                : com.intermarche.pos.domain.payment.TenderDefinition.DrawerOpening.values()) {
            if (moment.name().equals(raw)) {
                return moment;
            }
        }
        return com.intermarche.pos.domain.payment.TenderDefinition.DrawerOpening.NEVER;
    }

    /**
     * Applies a checkout-island snapshot: upsert by code, absents deactivated
     * (BO-03-06-07, BO-03-06-50).
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyCheckoutIslands(List<RefPayloads.CheckoutIslandDto> dtos) {
        LOGGER.info("Entering method applyCheckoutIslands with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.CheckoutIslandDto dto : dtos) {
            com.intermarche.pos.domain.store.CheckoutIsland island =
                    com.intermarche.pos.domain.store.CheckoutIsland
                            .find("code", dto.code).firstResult();
            if (island == null) {
                island = new com.intermarche.pos.domain.store.CheckoutIsland();
                island.code = dto.code;
            }
            island.label = dto.label;
            island.active = dto.active;
            island.terminalIds = effectiveTerminalIds(dto);
            island.persist();
            seen.add(dto.code);
        }
        long deactivated = 0;
        for (com.intermarche.pos.domain.store.CheckoutIsland island
                : com.intermarche.pos.domain.store.CheckoutIsland
                        .<com.intermarche.pos.domain.store.CheckoutIsland>listAll()) {
            if (!seen.contains(island.code) && island.active) {
                island.active = false;
                island.persist();
                deactivated++;
            }
        }
        LOGGER.infof("Référentiel îlots appliqué: %d ligne(s), %d désactivé(s)",
                dtos.size(), deactivated);
        LOGGER.info("Exiting method applyCheckoutIslands");
    }

    /**
     * Applies a CHECKOUT-POINT snapshot: upsert by terminal identifier, absents
     * deactivated (BO-03-09, BO-03-10).
     *
     * <p>Deactivated and never deleted, exactly like an island: a point that
     * disappears from the node's snapshot is a point the back office took out of
     * service, and a register that erased it would lose the only record of which
     * island a ticket was taken on. A point that comes back is reactivated by the
     * upsert.
     *
     * <p>Both enumerations are resolved by NAME and fall back to null rather than
     * to a guessed value when the node sends something this register does not
     * know: a column left null says "the node did not describe this", which is
     * the truth, where a default would quietly assert a type nobody administered.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyCheckoutPoints(List<RefPayloads.CheckoutPointDto> dtos) {
        LOGGER.info("Entering method applyCheckoutPoints with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.CheckoutPointDto dto : dtos) {
            if (dto.terminalId == null || dto.terminalId.isBlank()) {
                continue;
            }
            com.intermarche.pos.domain.store.CheckoutPoint point =
                    com.intermarche.pos.domain.store.CheckoutPoint.findByTerminal(dto.terminalId);
            if (point == null) {
                point = new com.intermarche.pos.domain.store.CheckoutPoint();
                point.terminalId = dto.terminalId.trim();
            }
            point.pdvNumber = dto.pdvNumber;
            point.islandCode = dto.islandCode;
            point.label = dto.label;
            point.active = dto.active;
            point.tpvType = com.intermarche.pos.domain.store.TpvType.fromName(dto.tpvType);
            point.technicalHostessBadge = dto.technicalHostessBadge;
            point.cashierDisplayType =
                    com.intermarche.pos.domain.store.DisplayType.fromName(dto.cashierDisplayType);
            point.cashierDisplayResolution = dto.cashierDisplayResolution;
            point.customerDisplayType =
                    com.intermarche.pos.domain.store.DisplayType.fromName(dto.customerDisplayType);
            point.customerDisplayResolution = dto.customerDisplayResolution;
            point.menuTemplateCode = dto.menuTemplateCode;
            point.persist();
            seen.add(point.terminalId);
        }
        long deactivated = 0;
        for (com.intermarche.pos.domain.store.CheckoutPoint point
                : com.intermarche.pos.domain.store.CheckoutPoint
                        .<com.intermarche.pos.domain.store.CheckoutPoint>listAll()) {
            if (!seen.contains(point.terminalId) && point.active) {
                point.active = false;
                point.persist();
                deactivated++;
            }
        }
        LOGGER.infof("Référentiel points de contact appliqué: %d ligne(s), %d désactivé(s)",
                dtos.size(), deactivated);
        LOGGER.info("Exiting method applyCheckoutPoints");
    }

    /**
     * Derives the effective attached terminals of an island from whichever
     * payload form the node sent (BO-03-09/BO-03-10), without preference or
     * warning: when the contact-point list is present and non-empty the
     * terminals are read from it (the new form), otherwise the semicolon list is
     * kept as is (the old form). Both are normalized to the semicolon string the
     * {@link com.intermarche.pos.domain.store.CheckoutIsland} entity and every
     * downstream reader already understand, so a payload that dropped the old
     * field never costs the island its registers.
     *
     * @param dto the island payload, in either form
     * @return the effective semicolon list of terminal identifiers, or null when
     *         the payload carries neither form
     */
    private String effectiveTerminalIds(RefPayloads.CheckoutIslandDto dto) {
        if (dto.contactPoints != null && !dto.contactPoints.isEmpty()) {
            StringBuilder joined = new StringBuilder();
            for (RefPayloads.ContactPointDto contactPoint : dto.contactPoints) {
                if (contactPoint == null || contactPoint.terminalId == null
                        || contactPoint.terminalId.isBlank()) {
                    continue;
                }
                if (joined.length() > 0) {
                    joined.append(';');
                }
                joined.append(contactPoint.terminalId.trim());
            }
            return joined.toString();
        }
        return dto.terminalIds;
    }

    /**
     * Applies an ARTICLE barcode-range snapshot: upsert by code, absents
     * deactivated (BO-03-06-02/03/04/05/10).
     *
     * <p>Same contract as every other domain — the snapshot is the truth, a row
     * it no longer names stops recognizing codes rather than lingering. The
     * pattern travels ALREADY GENERATED: the register applies what the back
     * office produced, it does not regenerate anything of its own.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyArticleBarcodeRanges(List<RefPayloads.ArticleBarcodeRangeDto> dtos) {
        LOGGER.info("Entering method applyArticleBarcodeRanges with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.ArticleBarcodeRangeDto dto : dtos) {
            com.intermarche.pos.domain.barcode.ArticleBarcodeRange range =
                    com.intermarche.pos.domain.barcode.ArticleBarcodeRange
                            .find("code", dto.code).firstResult();
            if (range == null) {
                range = new com.intermarche.pos.domain.barcode.ArticleBarcodeRange();
                range.code = dto.code;
            }
            range.label = dto.label;
            range.active = dto.active;
            range.priority = dto.priority;
            range.prefix = dto.prefix;
            range.codeLength = dto.codeLength;
            range.codeKind = dto.codeKind == null
                    ? CouponField.Kind.NUMERIC
                    : CouponField.Kind.valueOf(dto.codeKind);
            range.articlePosition = dto.articlePosition;
            range.articleLength = dto.articleLength;
            range.valueSource = dto.valueSource == null
                    ? com.intermarche.pos.domain.barcode.ArticleBarcodeRange.ValueSource.PRICE
                    : com.intermarche.pos.domain.barcode.ArticleBarcodeRange.ValueSource
                            .valueOf(dto.valueSource);
            range.valuePosition = dto.valuePosition;
            range.valueLength = dto.valueLength;
            range.valueDecimals = dto.valueDecimals;
            range.currency = dto.currency == null
                    ? CouponField.PriceCurrency.EUR
                    : CouponField.PriceCurrency.valueOf(dto.currency);
            range.checkDigit = dto.checkDigit;
            range.matchPattern = dto.matchPattern;
            // BO-03-06-56/-60/-61: a snapshot from a node that does not know the
            // column yet leaves every range an ARTICLE range, which is what they
            // all were before it existed.
            range.purpose = dto.purpose == null
                    ? com.intermarche.pos.domain.barcode.ArticleBarcodeRange.Purpose.ARTICLE
                    : com.intermarche.pos.domain.barcode.ArticleBarcodeRange.Purpose
                            .valueOf(dto.purpose);
            range.persist();
            seen.add(dto.code);
        }
        long deactivated = 0;
        for (com.intermarche.pos.domain.barcode.ArticleBarcodeRange range
                : com.intermarche.pos.domain.barcode.ArticleBarcodeRange
                        .<com.intermarche.pos.domain.barcode.ArticleBarcodeRange>listAll()) {
            if (!seen.contains(range.code) && range.active) {
                range.active = false;
                range.persist();
                deactivated++;
            }
        }
        LOGGER.infof("Référentiel plages article appliqué: %d ligne(s), %d désactivé(s)",
                dtos.size(), deactivated);
        LOGGER.info("Exiting method applyArticleBarcodeRanges");
    }

    /**
     * Replaces the administered controls of a range with those of the snapshot.
     *
     * <p>Wholesale replacement, like the positions: a control dropped upstream
     * must stop firing here, and the kind is the only key it has.
     *
     * @param type the range being upserted
     * @param dto the snapshot row
     */
    private void applyCouponControls(CouponType type, RefPayloads.CouponTypeDto dto) {
        if (type.controls == null) {
            type.controls = new java.util.ArrayList<>();
        }
        type.controls.clear();
        if (dto.controls == null) {
            return;
        }
        for (RefPayloads.CouponControlDto row : dto.controls) {
            if (row == null || row.kind == null) {
                continue;
            }
            CouponControl control = new CouponControl();
            control.couponType = type;
            control.kind = CouponControl.Kind.valueOf(row.kind);
            control.level = AlertLevel.of(row.level);
            control.message = row.message;
            type.controls.add(control);
        }
    }

    /**
     * Replaces the administered positions of a range with those of the snapshot.
     *
     * <p>Wholesale replacement, not an upsert: a position removed upstream must
     * disappear here, and the fields carry no identity of their own — the role
     * is their only key, and the snapshot is authoritative on the whole set.
     *
     * @param type the range being upserted
     * @param dto the snapshot row
     */
    private void applyCouponFields(CouponType type, RefPayloads.CouponTypeDto dto) {
        if (type.fields == null) {
            type.fields = new java.util.ArrayList<>();
        }
        type.fields.clear();
        if (dto.fields == null) {
            return;
        }
        for (RefPayloads.CouponFieldDto row : dto.fields) {
            if (row == null || row.role == null) {
                continue;
            }
            CouponField field = new CouponField();
            field.couponType = type;
            field.role = CouponField.Role.valueOf(row.role);
            field.offsetPosition = row.offsetPosition;
            field.fieldLength = row.fieldLength;
            field.kind = row.kind == null ? CouponField.Kind.NUMERIC : CouponField.Kind.valueOf(row.kind);
            field.decimals = row.decimals;
            field.dateFormat = row.dateFormat == null ? null : CouponField.DateFormat.valueOf(row.dateFormat);
            field.currency = row.currency == null ? null : CouponField.PriceCurrency.valueOf(row.currency);
            type.fields.add(field);
        }
    }

    /**
     * Applies the foreign-currency snapshot ({@code LC-07-14}): upsert by ISO code,
     * and the currencies absent from the snapshot are DEACTIVATED rather than
     * deleted — a receipt from last month still names one, and a code that comes
     * back keeps its history.
     *
     * @param dtos the full currency snapshot
     */
    @Transactional
    public void applyCurrencies(List<RefPayloads.CurrencyDto> dtos) {
        LOGGER.info("Entering method applyCurrencies with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.CurrencyDto dto : dtos) {
            com.intermarche.pos.domain.payment.Currency currency =
                    com.intermarche.pos.domain.payment.Currency.find("code", dto.code).firstResult();
            if (currency == null) {
                currency = new com.intermarche.pos.domain.payment.Currency();
                currency.code = dto.code;
            }
            currency.label = dto.label;
            currency.symbol = dto.symbol;
            java.math.BigDecimal rate = amount(dto.euroPerUnit);
            currency.euroPerUnit = rate == null ? java.math.BigDecimal.ONE : rate;
            currency.active = dto.active;
            currency.displayOrder = dto.displayOrder;
            currency.changeTenderCode = dto.changeTenderCode;
            currency.persist();
            seen.add(dto.code);
        }
        long deactivated = 0;
        for (com.intermarche.pos.domain.payment.Currency currency
                : com.intermarche.pos.domain.payment.Currency.<com.intermarche.pos.domain.payment.Currency>listAll()) {
            if (!seen.contains(currency.code) && currency.active) {
                currency.active = false;
                currency.persist();
                deactivated++;
            }
        }
        LOGGER.infof("Référentiel devises appliqué: %d ligne(s), %d désactivée(s)",
                dtos.size(), deactivated);
        LOGGER.info("Exiting method applyCurrencies");
    }

    /**
     * Applies a postal-code snapshot: upsert by the PAIR, absents DELETED
     * ({@code BO-02-04-15}).
     *
     * <p>Deleted and not deactivated, unlike the articles: nothing references a
     * postal code — a customer's address carries the text of the code, not a
     * foreign key — so a row the store node dropped must go, or a commune
     * merged away last year would keep being offered at the till forever.
     *
     * @param dtos the full postal-code snapshot
     */
    @Transactional
    public void applyPostalCodes(List<RefPayloads.PostalCodeDto> dtos) {
        LOGGER.info("Entering method applyPostalCodes with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.PostalCodeDto dto : dtos) {
            com.intermarche.pos.domain.store.PostalCode row =
                    com.intermarche.pos.domain.store.PostalCode.findPair(dto.code, dto.place);
            if (row == null) {
                row = new com.intermarche.pos.domain.store.PostalCode();
                row.code = dto.code;
                row.place = dto.place;
            }
            row.country = dto.country;
            row.persist();
            seen.add(pairOf(dto.code, dto.place));
        }
        for (com.intermarche.pos.domain.store.PostalCode row
                : com.intermarche.pos.domain.store.PostalCode
                        .<com.intermarche.pos.domain.store.PostalCode>listAll()) {
            if (!seen.contains(pairOf(row.code, row.place))) {
                row.delete();
            }
        }
        LOGGER.infof("Référentiel codes postaux appliqué: %d ligne(s)", dtos.size());
        LOGGER.info("Exiting method applyPostalCodes");
    }

    /**
     * Composes the key of a postal-code row, the code alone not being unique.
     *
     * @param code the postal code, possibly null
     * @param place the place, possibly null
     * @return the composed key
     */
    private static String pairOf(String code, String place) {
        return (code == null ? "" : code) + '\u0000' + (place == null ? "" : place);
    }

    /**
     * Applies the account-customer snapshot ({@code LC-07-09}): upsert by account
     * number, credit ceiling and outstanding balance included.
     *
     * <p>UNLIKE THE OTHER DOMAINS, nothing is deactivated or deleted for the
     * customers absent from the snapshot. A customer created AT THE TILL — the
     * invoice flow does exactly that — has not reached the commercial management
     * yet at the next pull, and wiping it would destroy, on the way back, the
     * declaration the register just made. The commercial management remains the
     * authority on the credit figures; it is not the authority on the existence
     * of a customer the register itself opened five minutes ago.
     *
     * @param dtos the full customer snapshot
     */
    @Transactional
    public void applyCustomers(List<RefPayloads.CustomerDto> dtos) {
        LOGGER.info("Entering method applyCustomers with dtos: " + dtos);
        for (RefPayloads.CustomerDto dto : dtos) {
            com.intermarche.pos.domain.payment.AccountCustomer customer =
                    com.intermarche.pos.domain.payment.AccountCustomer
                            .find("accountNumber", dto.accountNumber).firstResult();
            if (customer == null) {
                customer = new com.intermarche.pos.domain.payment.AccountCustomer();
                customer.accountNumber = dto.accountNumber;
            }
            customer.companyName = dto.companyName;
            customer.origin = origin(dto.origin);
            customer.civility = dto.civility;
            customer.lastName = dto.lastName;
            customer.firstName = dto.firstName;
            if (customer.address == null) {
                customer.address = new com.intermarche.pos.domain.store.Address();
            }
            customer.address.streetLine1 = dto.street;
            customer.address.postalCode = dto.postalCode;
            customer.address.city = dto.city;
            customer.siret = dto.siret;
            customer.vatNumber = dto.vatNumber;
            customer.phone = dto.phone;
            customer.email = dto.email;
            customer.taxId = dto.taxId;
            customer.segment = dto.segment;
            customer.blocked = dto.blocked;
            customer.dueDate = date(dto.dueDate);
            customer.discountPercent = amount(dto.discountPercent);
            customer.creditLimit = amount(dto.creditLimit);
            java.math.BigDecimal balance = amount(dto.creditBalance);
            customer.creditBalance = balance == null ? java.math.BigDecimal.ZERO : balance;
            // The free fields are REPLACED and not merged: the commercial
            // management sends the whole reserve, so a field it dropped must
            // disappear here rather than survive as a value nobody maintains
            // any more (BO-02-04-11).
            if (customer.freeFields == null) {
                customer.freeFields = new java.util.ArrayList<>();
            }
            customer.freeFields.clear();
            if (dto.freeFields != null) {
                for (RefPayloads.FreeFieldDto field : dto.freeFields) {
                    customer.freeFields.add(
                            new com.intermarche.pos.domain.payment.AccountCustomer.FreeField(
                                    field.slot, field.label, field.value));
                }
            }
            customer.persist();
        }
        LOGGER.infof("Référentiel clients en compte appliqué: %d ligne(s)", dtos.size());
        LOGGER.info("Exiting method applyCustomers");
    }

    /**
     * Reads a decimal amount carried as text by a snapshot row.
     *
     * @param text the amount as exported, possibly null or blank
     * @return the amount, or null when nothing readable was sent
     */
    private static java.math.BigDecimal amount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads where a customer came from ({@code BO-02-04-22}).
     *
     * <p>Anything the node did not state, or stated in a word this version does
     * not know, is read as {@code REGISTER}: claiming a row came from the
     * commercial management is a claim that must be made explicitly, never one
     * that falls out of a blank.
     *
     * @param text the origin as exported, possibly null or blank
     * @return the origin, {@code REGISTER} when nothing readable was sent
     */
    private static com.intermarche.pos.domain.payment.AccountCustomer.Origin origin(String text) {
        if (text == null || text.isBlank()) {
            return com.intermarche.pos.domain.payment.AccountCustomer.Origin.REGISTER;
        }
        try {
            return com.intermarche.pos.domain.payment.AccountCustomer.Origin.valueOf(text.trim());
        } catch (IllegalArgumentException unknown) {
            return com.intermarche.pos.domain.payment.AccountCustomer.Origin.REGISTER;
        }
    }

    /**
     * Reads an ISO date carried as text by a snapshot row.
     *
     * <p>An unreadable date is read as NO date rather than as an incident: a
     * malformed due date on one customer must not stop the whole referential
     * from being applied ({@code BO-02-04-08}).
     *
     * @param text the date as exported, possibly null or blank
     * @return the date, or null when nothing readable was sent
     */
    private static java.time.LocalDate date(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(text.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** The settings cache to drop after an apply. */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * Applies the back-office parameters snapshot: upsert by key, and the
     * keys ABSENT from the store are deleted locally — the register then
     * reverts to the catalog defaults, so "not administered" means the same
     * thing on every node. The settings cache is dropped last.
     *
     * @param dtos the full parameters snapshot
     */
    @jakarta.transaction.Transactional
    public void applySettings(List<RefPayloads.SettingDto> dtos) {
        LOGGER.info("Entering method applySettings with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.SettingDto dto : dtos) {
            com.intermarche.pos.domain.setting.PosSetting row =
                    com.intermarche.pos.domain.setting.PosSetting.findByKey(dto.key);
            if (row == null) {
                row = new com.intermarche.pos.domain.setting.PosSetting();
                row.settingKey = dto.key;
            }
            row.settingValue = dto.value;
            row.persist();
            seen.add(dto.key);
        }
        long removed = com.intermarche.pos.domain.setting.PosSetting.delete(
                "settingKey not in ?1", seen.isEmpty() ? java.util.List.of("") : seen);
        posSettingsService.invalidate();
        LOGGER.infof("Paramètres appliqués: %d ligne(s), %d supprimé(s)", dtos.size(), removed);
        LOGGER.info("Exiting method applySettings");
    }

    /**
     * Applies the verbatim engine feeds snapshot: upsert by code when the
     * version differs — the engine acknowledgement columns are PRESERVED,
     * the local delivery loop owns them — and the codes absent from the
     * store are deleted locally (a withdrawn feed stops being delivered).
     *
     * @param dtos the full engine-feeds snapshot
     */
    @Transactional
    public void applyEngineFeeds(List<RefPayloads.EngineFeedDto> dtos) {
        LOGGER.info("Entering method applyEngineFeeds with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.EngineFeedDto dto : dtos) {
            com.intermarche.pos.domain.sync.EngineFeed row =
                    com.intermarche.pos.domain.sync.EngineFeed.findByCode(dto.code);
            if (row == null) {
                row = new com.intermarche.pos.domain.sync.EngineFeed();
                row.code = dto.code;
            } else if (dto.version != null && dto.version.equals(row.version)) {
                seen.add(dto.code);
                continue;
            }
            row.content = dto.content;
            row.version = dto.version;
            row.receivedAt = java.time.LocalDateTime.now();
            row.persist();
            seen.add(dto.code);
        }
        long removed = com.intermarche.pos.domain.sync.EngineFeed.delete(
                "code not in ?1", seen.isEmpty() ? java.util.List.of("") : seen);
        LOGGER.infof("Flux moteur appliqués: %d ligne(s), %d supprimé(s)", dtos.size(), removed);
        LOGGER.info("Exiting method applyEngineFeeds");
    }

    /**
     * Records the applied fingerprint of a domain.
     *
     * @param domain the referential domain
     * @param fingerprint the remote fingerprint just applied
     */
    @Transactional
    public void recordApplied(String domain, String fingerprint) {
        LOGGER.info("Entering method recordApplied with domain: " + domain + ", fingerprint: " + fingerprint);
        RefState state = RefState.find("domain", domain).firstResult();
        if (state == null) {
            state = new RefState();
            state.domain = domain;
        }
        state.fingerprint = fingerprint;
        state.appliedAt = LocalDateTime.now();
        state.persist();
        LOGGER.info("Exiting method recordApplied");
    }

    /**
     * Returns the last applied fingerprint of a domain.
     * <p>
     * Transactional like every other database method here, and for a reason
     * that is not cosmetic: the pull loop runs on its own scheduler thread,
     * where no request context and no transaction exist. This read is the
     * FIRST database touch of a cycle, so without a transaction of its own the
     * whole cycle dies before pulling anything — silently for the user, once
     * every pull interval, in the log only.
     *
     * @param domain the referential domain
     * @return the fingerprint, or null when never applied
     */
    @Transactional
    public String lastApplied(String domain) {
        LOGGER.info("Entering method lastApplied with domain: " + domain);
        RefState state = RefState.find("domain", domain).firstResult();
        LOGGER.info("Exiting method lastApplied");
        return state != null ? state.fingerprint : null;
    }

    /**
     * Deactivates the products absent from the applied snapshot.
     *
     * @param seenEans the EANs present in the snapshot
     * @return the number of deactivated products
     */
    private int deactivateAbsentProducts(Set<String> seenEans) {
        int deactivated = 0;
        for (Product product : Product.<Product>listAll()) {
            if (!seenEans.contains(product.ean) && product.active) {
                product.active = false;
                product.persist();
                deactivated++;
            }
        }
        return deactivated;
    }

    /**
     * Parses an ISO-8601 timestamp, tolerating null.
     *
     * @param value the ISO string, or null
     * @return the timestamp, or null
     */
    private LocalDateTime parse(String value) {
        return value != null ? LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
    }
}
