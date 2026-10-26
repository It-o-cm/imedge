package com.intermarche.edge.service.sync;

import com.intermarche.edge.domain.barcode.AlertLevel;
import com.intermarche.edge.domain.barcode.CouponControl;
import com.intermarche.edge.domain.barcode.CouponField;
import com.intermarche.edge.domain.barcode.CouponType;
import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.setting.EchelonLevel;
import com.intermarche.edge.domain.setting.EchelonSetting;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.people.EmployeeProfile;
import com.intermarche.edge.domain.people.Profile;
import com.intermarche.edge.domain.people.ProfileGrant;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductType;
import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.sync.RefApplyAudit;
import com.intermarche.edge.domain.sync.RefState;
import com.intermarche.edge.domain.setting.TouchGroupSetting;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    com.intermarche.edge.service.DocumentTemplateService documentTemplateService;

    /**
     * The role of this node, the same property the pull loop reads: a
     * {@code register} applies what its store node sends, a {@code store}
     * applies what the head office sends.
     */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /**
     * Whether a column the upstream snapshot leaves NULL keeps the value this
     * node already holds instead of being cleared.
     *
     * <p>The two links of the chain do not share one answer. A register
     * applies its store node verbatim: the store is the authority on what the
     * lane holds, and a value it emptied must disappear at the register too. A
     * store node receiving from the head office keeps what it chose locally:
     * a head office snapshot that does not carry a column says nothing about
     * it, and must not erase the store's own choice behind its back.
     *
     * @return true on a store node, false on a register
     */
    boolean keepsLocalOnSilence() {
        return "store".equalsIgnoreCase(role);
    }

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
            // BO-03-01-21/-22/-23: the scope of the group. A snapshot naming
            // nothing leaves it drawn everywhere, which is what every group did
            // before the columns existed.
            setting.islandCodes = dto.islandCodes;
            setting.terminalIds = dto.terminalIds;
            setting.tpvTypes = dto.tpvTypes;
            // BO-03-01-19: the translated wordings descend with the row; on a
            // store node, a wording the store typed for its own customers is
            // not the head office's to erase by staying silent about it.
            if (dto.labels != null || !keepsLocalOnSilence()) {
                setting.labels = dto.labels;
            }
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
            // BO-02-03-03: the additional sale codes replace the register's own.
            product.saleCodes = dto.saleCodes != null
                    ? new java.util.TreeSet<>(dto.saleCodes) : new java.util.TreeSet<>();
            // BO-03-01-13: the sales volume is the store node's own computation
            // over its registers' sales; a head office snapshot never overwrites
            // it, a register takes what its store computed.
            if (!keepsLocalOnSilence()) {
                product.salesVolume = dto.salesVolume;
            }
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
        // An UPSERT by number, never a wipe: the prices point at the rows, and a
        // table deleted and re-created detached every one of them — the price
        // file, unchanged, was not re-applied, so one regime corrected in the
        // back office left the whole catalogue of the till on the default rate
        // (BO-02-02-01). Only the regimes the snapshot no longer carries go, and
        // only their prices are detached.
        java.util.Set<Integer> kept = new java.util.HashSet<>();
        for (RefPayloads.VatRateDto dto : dtos) {
            kept.add(dto.number);
            VatRate rate = VatRate.findByNumber(dto.number);
            boolean created = rate == null;
            if (created) {
                rate = new VatRate();
                rate.number = dto.number;
            }
            rate.rate = dto.rate;
            rate.label = dto.label;
            rate.country = dto.country;
            rate.legalMention = dto.legalMention;
            rate.exempt = dto.exempt;
            rate.exemptionCode = dto.exemptionCode;
            if (created) {
                rate.persist();
            }
        }
        for (VatRate stale : VatRate.<VatRate>listAll()) {
            if (!kept.contains(stale.number)) {
                Price.update("set vat = null where vat = ?1", stale);
                stale.delete();
            }
        }
        LOGGER.infof("Référentiel TVA remplacé: %d régime(s)", dtos.size());
        LOGGER.info("Exiting method applyVatRates");
    }

    /** Imports the PRODUCTS file the way the store node imports it. */
    @Inject
    com.intermarche.edge.imports.ProductCsvResource productImporter;

    /** Imports the FAMILIES file the way the store node imports it. */
    @Inject
    com.intermarche.edge.imports.ProductFamilyCsvResource familyImporter;

    /** Imports the PRICES file the way the store node imports it. */
    @Inject
    com.intermarche.edge.imports.PriceCsvResource priceImporter;

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
        com.intermarche.edge.domain.sync.EngineFeed feed =
                com.intermarche.edge.domain.sync.EngineFeed.<com.intermarche.edge.domain.sync.EngineFeed>
                        find("code", domain).firstResult();
        LOGGER.info("Exiting method feedContent");
        return feed == null ? null : feed.content;
    }

    /**
     * The state of the store's referential a feed file was integrated over,
     * and the state it left (BO-02-03-33).
     *
     * @param base the fingerprint before, empty for an empty referential, or null
     * @param result the fingerprint after, or null
     */
    public record FeedStamp(String base, String result) {

        /**
         * Whether the file applies over a register at the given state: the
         * register must stand exactly where the store stood when it integrated
         * the file, a register that never applied the domain where the store
         * held nothing.
         *
         * @param applied the fingerprint the register last applied, or null
         * @return true when applying the file reproduces the store's result
         */
        public boolean startsFrom(String applied) {
            if (base == null || result == null) {
                return false;
            }
            return base.isEmpty() ? applied == null : base.equals(applied);
        }
    }

    /**
     * Reads the stamps of one engine feed this register holds (BO-02-03-33),
     * in a transaction of its own for the pull thread, as {@link #feedContent}.
     *
     * @param domain the feed code
     * @return the stamps, or null when this register holds no such feed
     */
    @Transactional
    public FeedStamp feedStamp(String domain) {
        LOGGER.info("Entering method feedStamp with domain: " + domain);
        com.intermarche.edge.domain.sync.EngineFeed feed =
                com.intermarche.edge.domain.sync.EngineFeed.<com.intermarche.edge.domain.sync.EngineFeed>
                        find("code", domain).firstResult();
        LOGGER.info("Exiting method feedStamp");
        return feed == null ? null : new FeedStamp(feed.baseFingerprint, feed.resultFingerprint);
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
            // BO-03-06-51: a node that does not know the column printed no card.
            employee.supervisorCard = Boolean.TRUE.equals(dto.supervisorCard);
            // BO-04-02-01: the profiles the holder endorses by.
            employee.profileNames = dto.profiles;
            // BO-01-01-04 / BO-01-02-12: the operator's language and the age of
            // their secret come from the node like the secret itself.
            employee.language = dto.language;
            employee.passwordChangedAt = dto.passwordChangedAt == null
                    ? null : java.time.LocalDateTime.parse(dto.passwordChangedAt);
            // BO-01-02-07: the key that opens the register without a PIN.
            employee.dallasKey = dallasKeyFor(employee, dto.loginName, dto.dallasKey);
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
            // BO-03-06-08 and -30 to -38: the tender the range is settled as, and
            // the instruments this register issues under it. Chosen on the store
            // node: a head office snapshot silent about them must not erase them.
            if (dto.tenderCode != null || !keepsLocalOnSilence()) {
                type.tenderCode = dto.tenderCode;
            }
            if (dto.issuedKind != null || !keepsLocalOnSilence()) {
                type.issuedKind = dto.issuedKind;
            }
            // BO-03-02-37: the protocol of a prepaid card range, carried verbatim.
            type.cardProtocol = dto.cardProtocol;
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
            com.intermarche.edge.domain.setting.ProviderMessage message =
                    com.intermarche.edge.domain.setting.ProviderMessage
                            .find("providerCode = ?1 and messageCode = ?2",
                                    dto.providerCode, dto.messageCode)
                            .firstResult();
            if (message == null) {
                message = new com.intermarche.edge.domain.setting.ProviderMessage();
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
        for (com.intermarche.edge.domain.setting.ProviderMessage message
                : com.intermarche.edge.domain.setting.ProviderMessage
                        .<com.intermarche.edge.domain.setting.ProviderMessage>listAll()) {
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
            com.intermarche.edge.domain.setting.DocumentTemplate template =
                    com.intermarche.edge.domain.setting.DocumentTemplate
                            .find("code", dto.code).firstResult();
            if (template == null) {
                template = new com.intermarche.edge.domain.setting.DocumentTemplate();
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
            template.echelonScope = dto.echelonScope;
            template.persist();
            seen.add(dto.code);
        }
        long deactivated = 0;
        for (com.intermarche.edge.domain.setting.DocumentTemplate template
                : com.intermarche.edge.domain.setting.DocumentTemplate
                        .<com.intermarche.edge.domain.setting.DocumentTemplate>listAll()) {
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
     * Applies the PRINTABLE DOCUMENTS snapshot (BO-03-04-06, -09, -10 and
     * -14): upsert by code, and a row the snapshot no longer carries is DELETED.
     *
     * <p>Deleted and not deactivated, because deleting a document is precisely
     * what the back office asked for (BO-03-04-10): a register that kept it
     * inactive would still carry a name nobody administers. The papers already
     * printed under it are not touched — they carry their own name and number.
     *
     * <p>A row whose nature this version does not know is read as an invoice:
     * the document stays printable rather than disappearing from the till.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyPrintableDocuments(List<RefPayloads.PrintableDocumentDto> dtos) {
        LOGGER.info("Entering method applyPrintableDocuments with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.PrintableDocumentDto dto : dtos) {
            com.intermarche.edge.domain.setting.PrintableDocument document =
                    com.intermarche.edge.domain.setting.PrintableDocument.findByCode(dto.code);
            if (document == null) {
                document = new com.intermarche.edge.domain.setting.PrintableDocument();
                document.code = dto.code;
            }
            com.intermarche.edge.domain.sale.DocumentType kind =
                    com.intermarche.edge.domain.sale.DocumentType.byName(dto.kind);
            document.label = dto.label;
            document.kind = kind == null ? com.intermarche.edge.domain.sale.DocumentType.FACTURE : kind;
            document.output = com.intermarche.edge.domain.setting.PrintableDocument.outputOf(dto.output);
            document.templateCode = dto.templateCode;
            document.active = dto.active;
            document.priority = dto.priority;
            document.persist();
            seen.add(dto.code);
        }
        long removed = 0;
        for (com.intermarche.edge.domain.setting.PrintableDocument document
                : com.intermarche.edge.domain.setting.PrintableDocument
                        .<com.intermarche.edge.domain.setting.PrintableDocument>listAll()) {
            if (!seen.contains(document.code)) {
                document.delete();
                removed++;
            }
        }
        LOGGER.infof("Référentiel documents imprimables appliqué: %d ligne(s), %d retirée(s)",
                dtos.size(), removed);
        LOGGER.info("Exiting method applyPrintableDocuments");
    }

    /**
     * Reads a document type off a snapshot row, an absent or unknown one leaving
     * the template attached to no document.
     *
     * @param raw the type name as it travelled, or null
     * @return the type, or null when unrecognised
     */
    private com.intermarche.edge.domain.setting.DocumentTemplate.DocumentType documentType(
            String raw) {
        for (com.intermarche.edge.domain.setting.DocumentTemplate.DocumentType type
                : com.intermarche.edge.domain.setting.DocumentTemplate.DocumentType.values()) {
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
            com.intermarche.edge.domain.payment.TenderDefinition tender =
                    com.intermarche.edge.domain.payment.TenderDefinition
                            .find("code", dto.code).firstResult();
            if (tender == null) {
                tender = new com.intermarche.edge.domain.payment.TenderDefinition();
                tender.code = dto.code;
            }
            tender.functionalId = dto.functionalId;
            tender.label = dto.label;
            tender.groupCode = dto.groupCode;
            tender.groupLabel = dto.groupLabel;
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
            // BO-03-09-30: a snapshot from a node that does not know the column
            // names no product code, which is what every tender named before it;
            // on a store node, a head office snapshot that does not carry the
            // column must not take the store's product codes back to none.
            if (dto.vicProductCode != null || !keepsLocalOnSilence()) {
                tender.vicProductCode = dto.vicProductCode;
            }
            // BO-03-02-27/-29: what a self-service lane may settle with, and
            // what it calls the hostess for — both carried verbatim: a head
            // office that describes the self-service lanes describes them for
            // every store.
            tender.selfServiceAllowed = dto.selfServiceAllowed;
            tender.selfServiceSupervision = dto.selfServiceSupervision;
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
            // BO-03-02-01/-24/-29/-36/-44: carried verbatim, like the other
            // rules of the tender — the snapshot is the truth.
            tender.nature = dto.nature;
            tender.supervisionIslands = dto.supervisionIslands;
            tender.moneticCodes = dto.moneticCodes;
            tender.coinMachine = dto.coinMachine;
            tender.micrReading = dto.micrReading;
            tender.chequeAuthorization = dto.chequeAuthorization;
            tender.accountNumberEntry = dto.accountNumberEntry;
            tender.chequeAutoPrint = dto.chequeAutoPrint;
            tender.persist();
            seen.add(dto.code);
        }
        long deactivated = 0;
        for (com.intermarche.edge.domain.payment.TenderDefinition tender
                : com.intermarche.edge.domain.payment.TenderDefinition
                        .<com.intermarche.edge.domain.payment.TenderDefinition>listAll()) {
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
    private com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel control(String raw) {
        for (com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel level
                : com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.values()) {
            if (level.name().equals(raw)) {
                return level;
            }
        }
        return com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.NONE;
    }

    /**
     * Reads a drawer-opening moment off a snapshot row, an absent or unknown one
     * meaning that the drawer never opens.
     *
     * @param raw the moment name as it travelled, or null
     * @return the moment, never null
     */
    private com.intermarche.edge.domain.payment.TenderDefinition.DrawerOpening drawer(String raw) {
        for (com.intermarche.edge.domain.payment.TenderDefinition.DrawerOpening moment
                : com.intermarche.edge.domain.payment.TenderDefinition.DrawerOpening.values()) {
            if (moment.name().equals(raw)) {
                return moment;
            }
        }
        return com.intermarche.edge.domain.payment.TenderDefinition.DrawerOpening.NEVER;
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
            com.intermarche.edge.domain.store.CheckoutIsland island =
                    com.intermarche.edge.domain.store.CheckoutIsland
                            .find("code", dto.code).firstResult();
            if (island == null) {
                island = new com.intermarche.edge.domain.store.CheckoutIsland();
                island.code = dto.code;
            }
            island.label = dto.label;
            island.active = dto.active;
            island.terminalIds = effectiveTerminalIds(dto);
            island.persist();
            reconstructPoints(dto.code, island.terminalIds);
            seen.add(dto.code);
        }
        long deactivated = 0;
        for (com.intermarche.edge.domain.store.CheckoutIsland island
                : com.intermarche.edge.domain.store.CheckoutIsland
                        .<com.intermarche.edge.domain.store.CheckoutIsland>listAll()) {
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
            com.intermarche.edge.domain.store.CheckoutPoint point =
                    com.intermarche.edge.domain.store.CheckoutPoint.findByTerminal(dto.terminalId);
            if (point == null) {
                point = new com.intermarche.edge.domain.store.CheckoutPoint();
                point.terminalId = dto.terminalId.trim();
            }
            point.pdvNumber = dto.pdvNumber;
            point.islandCode = dto.islandCode;
            point.label = dto.label;
            point.active = dto.active;
            point.tpvType = com.intermarche.edge.domain.store.TpvType.fromName(dto.tpvType);
            point.technicalHostessBadge = dto.technicalHostessBadge;
            point.cashierDisplayType =
                    com.intermarche.edge.domain.store.DisplayType.fromName(dto.cashierDisplayType);
            point.cashierDisplayResolution = dto.cashierDisplayResolution;
            point.customerDisplayType =
                    com.intermarche.edge.domain.store.DisplayType.fromName(dto.customerDisplayType);
            point.customerDisplayResolution = dto.customerDisplayResolution;
            // BO-03-09-15/-24/-25/-26: the peripherals of this register, as the
            // store administered them. A snapshot naming no protocol leaves the
            // register with NONE, which is what it had before the column existed.
            point.scaleProtocol = com.intermarche.edge.domain.store.ScaleProtocol
                    .fromName(dto.scaleProtocol);
            if (point.scaleProtocol == null) {
                point.scaleProtocol = com.intermarche.edge.domain.store.ScaleProtocol.NONE;
            }
            point.scaleAddress = dto.scaleAddress;
            point.scalePort = dto.scalePort;
            point.coinMachineProtocol = com.intermarche.edge.domain.store.CoinMachineProtocol
                    .fromName(dto.coinMachineProtocol);
            if (point.coinMachineProtocol == null) {
                point.coinMachineProtocol =
                        com.intermarche.edge.domain.store.CoinMachineProtocol.NONE;
            }
            point.coinMachineAddress = dto.coinMachineAddress;
            point.coinMachinePort = dto.coinMachinePort;
            point.menuTemplateCode = dto.menuTemplateCode;
            point.persist();
            seen.add(point.terminalId);
        }
        long deactivated = 0;
        for (com.intermarche.edge.domain.store.CheckoutPoint point
                : com.intermarche.edge.domain.store.CheckoutPoint
                        .<com.intermarche.edge.domain.store.CheckoutPoint>listAll()) {
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
     * Rebuilds the island&rarr;register relation on the receiving side from the
     * backward-compatible {@code terminalIds} string: each listed terminal gets
     * an active {@link com.intermarche.edge.domain.store.CheckoutPoint} pointing at
     * the island, and a point of this island no longer listed is deactivated. The
     * relation the store node holds as first-class checkout points travels the
     * wire as the historical semicolon list, so an un-updated register still
     * reads it, and an updated register rebuilds the objects.
     *
     * @param islandCode the island the terminals belong to
     * @param terminalIds the semicolon list of terminal identifiers, or null
     */
    void reconstructPoints(String islandCode, String terminalIds) {
        Set<String> wanted = new java.util.LinkedHashSet<>();
        if (terminalIds != null) {
            for (String raw : terminalIds.split(";")) {
                String trimmed = raw.trim();
                if (!trimmed.isEmpty()) {
                    wanted.add(trimmed);
                }
            }
        }
        for (String terminal : wanted) {
            com.intermarche.edge.domain.store.CheckoutPoint point =
                    com.intermarche.edge.domain.store.CheckoutPoint.findByTerminal(terminal);
            if (point == null) {
                point = new com.intermarche.edge.domain.store.CheckoutPoint();
                point.terminalId = terminal;
            }
            point.islandCode = islandCode;
            point.active = true;
            point.persist();
        }
        for (com.intermarche.edge.domain.store.CheckoutPoint point
                : com.intermarche.edge.domain.store.CheckoutPoint
                        .<com.intermarche.edge.domain.store.CheckoutPoint>list("islandCode = ?1", islandCode)) {
            if (!wanted.contains(point.terminalId) && point.active) {
                point.active = false;
                point.persist();
            }
        }
    }

    /**
     * Derives the effective attached terminals of an island from whichever
     * payload form the node sent (BO-03-09/BO-03-10), without preference or
     * warning: when the contact-point list is present and non-empty the
     * terminals are read from it (the new form), otherwise the semicolon list is
     * kept as is (the old form). Both are normalized to the semicolon string the
     * {@link com.intermarche.edge.domain.store.CheckoutIsland} entity and every
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
            com.intermarche.edge.domain.barcode.ArticleBarcodeRange range =
                    com.intermarche.edge.domain.barcode.ArticleBarcodeRange
                            .find("code", dto.code).firstResult();
            if (range == null) {
                range = new com.intermarche.edge.domain.barcode.ArticleBarcodeRange();
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
                    ? com.intermarche.edge.domain.barcode.ArticleBarcodeRange.ValueSource.PRICE
                    : com.intermarche.edge.domain.barcode.ArticleBarcodeRange.ValueSource
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
            // all were before it existed. On a store node the destination is
            // chosen locally: a head office snapshot that does not carry the
            // field must not take it back to ARTICLE behind the store's back.
            if (dto.purpose != null || !keepsLocalOnSilence()) {
                range.purpose = dto.purpose == null
                        ? com.intermarche.edge.domain.barcode.ArticleBarcodeRange.Purpose.ARTICLE
                        : com.intermarche.edge.domain.barcode.ArticleBarcodeRange.Purpose
                                .valueOf(dto.purpose);
            }
            // BO-03-06-43: a snapshot from a node that does not know the column
            // names no promotion, which is what every range named before it;
            // same store-node reasoning as the destination above.
            if (dto.promotionCode != null || !keepsLocalOnSilence()) {
                range.promotionCode = dto.promotionCode;
            }
            // BO-03-06-41: likewise, a node that does not know the column asks
            // nobody, which is what every range did before it existed.
            if (dto.lookupUrl != null || !keepsLocalOnSilence()) {
                range.lookupUrl = dto.lookupUrl;
            }
            // BO-03-06-59: the register function a FUNCTION range calls.
            if (dto.functionCode != null || !keepsLocalOnSilence()) {
                range.functionCode = dto.functionCode;
            }
            // BO-03-06-43 / -41: the definition of a range's promotion and the
            // mapping of its partner's answer, same store-node reasoning.
            if (dto.promotionType != null || !keepsLocalOnSilence()) {
                range.promotionType = dto.promotionType;
            }
            if (dto.lookupField != null || !keepsLocalOnSilence()) {
                range.lookupField = dto.lookupField;
            }
            range.persist();
            seen.add(dto.code);
        }
        long deactivated = 0;
        for (com.intermarche.edge.domain.barcode.ArticleBarcodeRange range
                : com.intermarche.edge.domain.barcode.ArticleBarcodeRange
                        .<com.intermarche.edge.domain.barcode.ArticleBarcodeRange>listAll()) {
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
     * Applies the units of measure (BO-02-03-17): upsert by code, and a unit
     * absent from the snapshot is DEACTIVATED rather than deleted, an article
     * may still name it.
     *
     * @param dtos the full unit snapshot
     */
    @Transactional
    public void applyMeasureUnits(List<RefPayloads.MeasureUnitDto> dtos) {
        LOGGER.info("Entering method applyMeasureUnits with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.MeasureUnitDto dto : dtos) {
            com.intermarche.edge.domain.catalog.MeasureUnit unit =
                    com.intermarche.edge.domain.catalog.MeasureUnit.findByCode(dto.code);
            if (unit == null) {
                unit = new com.intermarche.edge.domain.catalog.MeasureUnit();
                unit.code = dto.code;
            }
            unit.label = dto.label;
            unit.symbol = dto.symbol;
            unit.decimalAllowed = dto.decimalAllowed;
            unit.active = dto.active;
            unit.persist();
            seen.add(unit.code);
        }
        for (com.intermarche.edge.domain.catalog.MeasureUnit unit
                : com.intermarche.edge.domain.catalog.MeasureUnit.listAllOrdered()) {
            if (!seen.contains(unit.code) && unit.active) {
                unit.active = false;
                unit.persist();
            }
        }
        LOGGER.info("Exiting method applyMeasureUnits");
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
            com.intermarche.edge.domain.payment.Currency currency =
                    com.intermarche.edge.domain.payment.Currency.find("code", dto.code).firstResult();
            if (currency == null) {
                currency = new com.intermarche.edge.domain.payment.Currency();
                currency.code = dto.code;
            }
            currency.label = dto.label;
            currency.symbol = dto.symbol;
            java.math.BigDecimal rate = amount(dto.euroPerUnit);
            currency.euroPerUnit = rate == null ? java.math.BigDecimal.ONE : rate;
            currency.active = dto.active;
            currency.displayOrder = dto.displayOrder;
            // BO-03-02-39: the change tender descends verbatim to a register. On
            // a store node it is chosen locally: a head office snapshot that does
            // not carry the field must not wipe that choice.
            if (dto.changeTenderCode != null || !keepsLocalOnSilence()) {
                currency.changeTenderCode = dto.changeTenderCode;
            }
            currency.persist();
            seen.add(dto.code);
        }
        long deactivated = 0;
        for (com.intermarche.edge.domain.payment.Currency currency
                : com.intermarche.edge.domain.payment.Currency.<com.intermarche.edge.domain.payment.Currency>listAll()) {
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
            com.intermarche.edge.domain.store.PostalCode row =
                    com.intermarche.edge.domain.store.PostalCode.findPair(dto.code, dto.place);
            if (row == null) {
                row = new com.intermarche.edge.domain.store.PostalCode();
                row.code = dto.code;
                row.place = dto.place;
            }
            row.country = dto.country;
            row.persist();
            seen.add(pairOf(dto.code, dto.place));
        }
        for (com.intermarche.edge.domain.store.PostalCode row
                : com.intermarche.edge.domain.store.PostalCode
                        .<com.intermarche.edge.domain.store.PostalCode>listAll()) {
            if (!seen.contains(pairOf(row.code, row.place))) {
                row.delete();
            }
        }
        LOGGER.infof("Référentiel codes postaux appliqué: %d ligne(s)", dtos.size());
        LOGGER.info("Exiting method applyPostalCodes");
    }

    /**
     * Applies the administered-list snapshot ({@code BO-04-02-07},
     * {@code BO-04-03-12}): upsert by list code, the entries replaced whole.
     *
     * <p>THE ENTRIES ARE REPLACED, NOT MERGED. An entry carries no history —
     * it is a key the cashier taps — so the shortest correct apply is to drop
     * what the list held and write what the node sent. Merging would have
     * needed an upsert key inside the list and would have kept, at the first
     * renaming, a key nobody administers any more.
     *
     * <p>A list the snapshot no longer names is deleted, like every other
     * referential domain: the back office is the authority on which lists
     * exist.
     *
     * @param dtos the full administered-list snapshot
     */
    @Transactional
    public void applyPosLists(List<RefPayloads.PosListDto> dtos) {
        LOGGER.info("Entering method applyPosLists with dtos: " + dtos);
        Set<String> seen = new HashSet<>();
        for (RefPayloads.PosListDto dto : dtos) {
            com.intermarche.edge.domain.setting.PosList row =
                    com.intermarche.edge.domain.setting.PosList.findByCode(dto.code);
            if (row == null) {
                row = new com.intermarche.edge.domain.setting.PosList();
                row.code = dto.code;
            }
            row.label = dto.label;
            row.kind = kindOf(dto.kind);
            row.functions = dto.functions;
            row.active = dto.active;
            row.items.clear();
            if (dto.items != null) {
                for (RefPayloads.PosListItemDto itemDto : dto.items) {
                    com.intermarche.edge.domain.setting.PosListItem item =
                            new com.intermarche.edge.domain.setting.PosListItem();
                    item.posList = row;
                    item.code = itemDto.code;
                    item.label = itemDto.label;
                    item.displayOrder = itemDto.displayOrder;
                    item.severity = itemDto.severity;
                    item.numericValue = weightOf(itemDto.numericValue);
                    item.options = itemDto.options;
                    item.active = itemDto.active;
                    row.items.add(item);
                }
            }
            row.persist();
            seen.add(dto.code);
        }
        for (com.intermarche.edge.domain.setting.PosList row
                : com.intermarche.edge.domain.setting.PosList
                        .<com.intermarche.edge.domain.setting.PosList>listAll()) {
            if (!seen.contains(row.code)) {
                row.delete();
            }
        }
        LOGGER.infof("Référentiel listes administrées appliqué: %d liste(s)", dtos.size());
        LOGGER.info("Exiting method applyPosLists");
    }

    /**
     * Applies the return-type snapshot ({@code BO-03-13-01} to {@code -04},
     * {@code -11}, {@code -12}): upsert by code, a type the snapshot no longer
     * names deleted — the back office decides which types exist, and a type it
     * deactivated travels deactivated rather than missing.
     *
     * @param dtos the full return-type snapshot
     */
    @Transactional
    public void applyReturnTypes(List<RefPayloads.ReturnTypeDto> dtos) {
        LOGGER.info("Entering method applyReturnTypes with " + dtos.size() + " type(s)");
        Set<String> seen = new HashSet<>();
        for (RefPayloads.ReturnTypeDto dto : dtos) {
            com.intermarche.edge.domain.setting.ReturnTypeDefinition row =
                    com.intermarche.edge.domain.setting.ReturnTypeDefinition.findByCode(dto.code);
            if (row == null) {
                row = new com.intermarche.edge.domain.setting.ReturnTypeDefinition();
                row.code = dto.code;
            }
            row.label = dto.label;
            row.ticketMode = dto.ticketMode;
            row.endorsementExempt = dto.endorsementExempt;
            row.originOptional = dto.originOptional;
            row.priceConfirmed = dto.priceConfirmed;
            row.depositReturn = dto.depositReturn;
            row.rangeCode = dto.rangeCode;
            row.displayOrder = dto.displayOrder;
            row.active = dto.active;
            row.persist();
            seen.add(dto.code);
        }
        for (com.intermarche.edge.domain.setting.ReturnTypeDefinition row
                : com.intermarche.edge.domain.setting.ReturnTypeDefinition
                        .<com.intermarche.edge.domain.setting.ReturnTypeDefinition>listAll()) {
            if (!seen.contains(row.code)) {
                row.delete();
            }
        }
        LOGGER.infof("Référentiel types de retour appliqué: %d type(s)", dtos.size());
        LOGGER.info("Exiting method applyReturnTypes");
    }

    /**
     * Applies the prepaid-card-type snapshot ({@code BO-03-02-37}): upsert by
     * code, a type the snapshot no longer names deleted.
     *
     * @param dtos the full prepaid-card-type snapshot
     */
    @Transactional
    public void applyPrepaidCardTypes(List<RefPayloads.PrepaidCardTypeDto> dtos) {
        LOGGER.info("Entering method applyPrepaidCardTypes with " + dtos.size() + " type(s)");
        Set<String> seen = new HashSet<>();
        for (RefPayloads.PrepaidCardTypeDto dto : dtos) {
            com.intermarche.edge.domain.payment.PrepaidCardType row =
                    com.intermarche.edge.domain.payment.PrepaidCardType.findByCode(dto.code);
            if (row == null) {
                row = new com.intermarche.edge.domain.payment.PrepaidCardType();
                row.code = dto.code;
            }
            row.label = dto.label;
            row.protocol = dto.protocol == null || dto.protocol.isBlank()
                    ? com.intermarche.edge.domain.payment.PrepaidCardType.PROTOCOL_NEXO : dto.protocol;
            row.provider = dto.provider;
            row.ranges = dto.ranges;
            row.paymentAllowed = dto.paymentAllowed;
            row.balanceAllowed = dto.balanceAllowed;
            row.activationAllowed = dto.activationAllowed;
            row.displayOrder = dto.displayOrder;
            row.active = dto.active;
            row.persist();
            seen.add(dto.code);
        }
        for (com.intermarche.edge.domain.payment.PrepaidCardType row
                : com.intermarche.edge.domain.payment.PrepaidCardType
                        .<com.intermarche.edge.domain.payment.PrepaidCardType>listAll()) {
            if (!seen.contains(row.code)) {
                row.delete();
            }
        }
        LOGGER.infof("Référentiel types de cartes prépayées appliqué: %d type(s)", dtos.size());
        LOGGER.info("Exiting method applyPrepaidCardTypes");
    }

    /**
     * Applies the image snapshot ({@code BO-03-03-15}, {@code BO-10-03-09}):
     * upsert by image code, an image the snapshot no longer names deleted.
     *
     * <p>What arrives is ALREADY RESOLVED: the node sent the logo its echelon
     * chain answers and one visual per advantage, so there is nothing to
     * choose here. An image whose picture is empty is still applied — the
     * back office may be in the middle of replacing it, and the printer
     * simply falls back on what it printed before.
     *
     * @param dtos the full image snapshot
     */
    @Transactional
    public void applyStoreImages(List<RefPayloads.StoreImageDto> dtos) {
        LOGGER.info("Entering method applyStoreImages with " + dtos.size() + " image(s)");
        Set<String> seen = new HashSet<>();
        for (RefPayloads.StoreImageDto dto : dtos) {
            com.intermarche.edge.domain.setting.StoreImage row =
                    com.intermarche.edge.domain.setting.StoreImage.findByCode(dto.code);
            if (row == null) {
                row = new com.intermarche.edge.domain.setting.StoreImage();
                row.code = dto.code;
            }
            row.label = dto.label;
            row.usage = usageOf(dto.usage);
            row.advantageCode = dto.advantageCode;
            row.imageData = dto.imageData;
            row.active = dto.active;
            row.persist();
            seen.add(dto.code);
        }
        for (com.intermarche.edge.domain.setting.StoreImage row
                : com.intermarche.edge.domain.setting.StoreImage
                        .<com.intermarche.edge.domain.setting.StoreImage>listAll()) {
            if (!seen.contains(row.code)) {
                row.delete();
            }
        }
        LOGGER.infof("Référentiel images appliqué: %d image(s)", dtos.size());
        LOGGER.info("Exiting method applyStoreImages");
    }

    /**
     * Reads the usage of an image off the wire, an unknown or absent one
     * answering the loyalty visual — the usage that illustrates and never
     * replaces, so an image this register does not understand cannot take
     * the place of its logo.
     *
     * @param raw the transported name, possibly null
     * @return the usage, never null
     */
    private static com.intermarche.edge.domain.setting.StoreImage.Usage usageOf(String raw) {
        if (raw != null && !raw.isBlank()) {
            for (com.intermarche.edge.domain.setting.StoreImage.Usage candidate
                    : com.intermarche.edge.domain.setting.StoreImage.Usage.values()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
        }
        return com.intermarche.edge.domain.setting.StoreImage.Usage.LOYALTY_ADVANTAGE;
    }

    /**
     * Reads the kind of an administered list off the wire, an unknown or
     * absent one answering JUSTIFICATION — the kind that drives nothing by
     * itself, so a list the register does not understand offers no key rather
     * than refusing the whole pull.
     *
     * @param raw the transported name, possibly null
     * @return the kind, never null
     */
    private static com.intermarche.edge.domain.setting.PosList.Kind kindOf(String raw) {
        if (raw != null && !raw.isBlank()) {
            for (com.intermarche.edge.domain.setting.PosList.Kind candidate
                    : com.intermarche.edge.domain.setting.PosList.Kind.values()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
        }
        return com.intermarche.edge.domain.setting.PosList.Kind.JUSTIFICATION;
    }

    /**
     * Reads the weight of a tare container off the wire; an absent or
     * unreadable one answers null, the entry then carrying no weight rather
     * than a wrong one.
     *
     * @param raw the transported weight, possibly null
     * @return the weight, or null
     */
    private static java.math.BigDecimal weightOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(raw.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
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
            com.intermarche.edge.domain.payment.AccountCustomer customer =
                    com.intermarche.edge.domain.payment.AccountCustomer
                            .find("accountNumber", dto.accountNumber).firstResult();
            if (customer == null) {
                customer = new com.intermarche.edge.domain.payment.AccountCustomer();
                customer.accountNumber = dto.accountNumber;
            }
            customer.companyName = dto.companyName;
            customer.origin = origin(dto.origin);
            customer.civility = dto.civility;
            customer.lastName = dto.lastName;
            customer.firstName = dto.firstName;
            if (customer.address == null) {
                customer.address = new com.intermarche.edge.domain.store.Address();
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
                            new com.intermarche.edge.domain.payment.AccountCustomer.FreeField(
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
    private static com.intermarche.edge.domain.payment.AccountCustomer.Origin origin(String text) {
        if (text == null || text.isBlank()) {
            return com.intermarche.edge.domain.payment.AccountCustomer.Origin.REGISTER;
        }
        try {
            return com.intermarche.edge.domain.payment.AccountCustomer.Origin.valueOf(text.trim());
        } catch (IllegalArgumentException unknown) {
            return com.intermarche.edge.domain.payment.AccountCustomer.Origin.REGISTER;
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
    com.intermarche.edge.service.PosSettingsService posSettingsService;

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
            com.intermarche.edge.domain.setting.PosSetting row =
                    com.intermarche.edge.domain.setting.PosSetting.findByKey(dto.key);
            if (row == null) {
                row = new com.intermarche.edge.domain.setting.PosSetting();
                row.settingKey = dto.key;
            }
            row.settingValue = dto.value;
            row.persist();
            seen.add(dto.key);
        }
        long removed = com.intermarche.edge.domain.setting.PosSetting.delete(
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
            com.intermarche.edge.domain.sync.EngineFeed row =
                    com.intermarche.edge.domain.sync.EngineFeed.findByCode(dto.code);
            if (row == null) {
                row = new com.intermarche.edge.domain.sync.EngineFeed();
                row.code = dto.code;
            } else if (dto.version != null && dto.version.equals(row.version)) {
                // BO-02-03-33: the stamps of a file arrive after the file itself.
                row.baseFingerprint = dto.baseFingerprint;
                row.resultFingerprint = dto.resultFingerprint;
                seen.add(dto.code);
                continue;
            }
            row.content = dto.content;
            row.version = dto.version;
            row.baseFingerprint = dto.baseFingerprint;
            row.resultFingerprint = dto.resultFingerprint;
            row.receivedAt = java.time.LocalDateTime.now();
            row.persist();
            seen.add(dto.code);
        }
        long removed = com.intermarche.edge.domain.sync.EngineFeed.delete(
                "code not in ?1", seen.isEmpty() ? java.util.List.of("") : seen);
        LOGGER.infof("Flux moteur appliqués: %d ligne(s), %d supprimé(s)", dtos.size(), removed);
        LOGGER.info("Exiting method applyEngineFeeds");
    }

    /**
     * Applies a country snapshot (store node, route A): the echelon tree is
     * fully owned by the central node, and nothing on the store references a
     * country by foreign key, so the table is replaced as a whole — the same
     * wholesale strategy as prices.
     *
     * @param dtos the full countries snapshot
     */
    @Transactional
    public void applyCountries(List<RefPayloads.CountryDto> dtos) {
        LOGGER.info("Entering method applyCountries with dtos: " + dtos);
        Country.deleteAll();
        for (RefPayloads.CountryDto dto : dtos) {
            Country row = new Country();
            row.code = dto.code;
            row.name = dto.name;
            row.defaultLanguage = dto.defaultLanguage;
            row.persist();
        }
        LOGGER.infof("Référentiel pays appliqué: %d ligne(s)", dtos.size());
        LOGGER.info("Exiting method applyCountries");
    }

    /**
     * Applies an enseigne snapshot (store node, route A): wholesale replacement,
     * like the countries — no foreign key on the store points at an enseigne.
     *
     * @param dtos the full enseignes snapshot
     */
    @Transactional
    public void applyEnseignes(List<RefPayloads.EnseigneDto> dtos) {
        LOGGER.info("Entering method applyEnseignes with dtos: " + dtos);
        Enseigne.deleteAll();
        for (RefPayloads.EnseigneDto dto : dtos) {
            Enseigne row = new Enseigne();
            row.code = dto.code;
            row.name = dto.name;
            row.countryCode = dto.countryCode;
            row.defaultLanguage = dto.defaultLanguage;
            row.persist();
        }
        LOGGER.infof("Référentiel enseignes appliqué: %d ligne(s)", dtos.size());
        LOGGER.info("Exiting method applyEnseignes");
    }

    /**
     * Applies a PDV snapshot (store node, route A): wholesale replacement. The
     * enseigne link and the adhérent grouping travel by code, so a re-parenting
     * done centrally lands as a plain field change here.
     *
     * @param dtos the full PDVs snapshot
     */
    @Transactional
    public void applyPdvs(List<RefPayloads.PdvDto> dtos) {
        LOGGER.info("Entering method applyPdvs with dtos: " + dtos);
        Pdv.deleteAll();
        for (RefPayloads.PdvDto dto : dtos) {
            Pdv row = new Pdv();
            row.pdvNumber = dto.pdvNumber;
            row.name = dto.name;
            row.enseigneCode = dto.enseigneCode;
            row.adherentCode = dto.adherentCode;
            row.active = dto.active;
            row.persist();
        }
        LOGGER.infof("Référentiel PDV appliqué: %d ligne(s)", dtos.size());
        LOGGER.info("Exiting method applyPdvs");
    }

    /**
     * Applies the profiles the central node administers (BO-01-03-04): upsert
     * by name, every one marked central, the grants rewritten with their
     * validating profile resolved by name; a central profile the centre no
     * longer sends is removed with its assignments. The store's own profiles
     * are untouched, unless the centre sends one of the same name, which it
     * then takes over.
     *
     * @param dtos the full profile snapshot of the centre
     */
    @Transactional
    public void applyProfiles(List<RefPayloads.ProfileDto> dtos) {
        LOGGER.info("Entering method applyProfiles with " + dtos.size() + " profile(s)");
        Map<String, Profile> byName = new HashMap<>();
        for (RefPayloads.ProfileDto dto : dtos) {
            Profile profile = Profile.findByName(dto.name);
            if (profile == null) {
                profile = new Profile();
                profile.name = dto.name;
            }
            profile.description = dto.description;
            profile.hierarchyLevel = dto.hierarchyLevel;
            profile.centralManaged = true;
            profile.persist();
            byName.put(dto.name, profile);
        }
        for (RefPayloads.ProfileDto dto : dtos) {
            Profile profile = byName.get(dto.name);
            List<ProfileGrant> grants = new java.util.ArrayList<>();
            for (RefPayloads.ProfileGrantDto g : dto.grants) {
                CrudOption option = optionOf(g.option);
                if (g.featureKey == null || option == null) {
                    continue;
                }
                Profile validator = g.validatingProfileName == null ? null : byName.get(g.validatingProfileName);
                if (validator == null && g.validatingProfileName != null) {
                    validator = Profile.findByName(g.validatingProfileName);
                }
                grants.add(new ProfileGrant(g.featureKey, option, g.requiresValidation,
                        validator == null ? null : validator.id));
            }
            profile.grants = grants;
        }
        long removed = 0;
        for (Profile profile : Profile.<Profile>list("centralManaged", true)) {
            if (!byName.containsKey(profile.name)) {
                EmployeeProfile.delete("profileId", profile.id);
                profile.delete();
                removed++;
            }
        }
        LOGGER.infof("Profils centraux appliqués: %d ligne(s), %d retiré(s)", dtos.size(), removed);
        LOGGER.info("Exiting method applyProfiles");
    }

    /**
     * Applies the accounts the central node administers for this point of
     * sale (BO-01-01-03): upsert by login, both secret hashes included, every
     * one marked central; a central account the centre no longer sends is
     * deactivated. The local state of an account — failed attempts, lockout,
     * theme — is kept, and the store's own accounts are untouched, unless the
     * centre sends one of the same login, which it then takes over. An
     * account whose address or badge another local account already holds is
     * skipped: two holders of one badge would be one person at the register.
     *
     * @param dtos the accounts the centre hands to this point of sale
     */
    @Transactional
    public void applyAccounts(List<RefPayloads.AccountDto> dtos) {
        LOGGER.info("Entering method applyAccounts with " + dtos.size() + " account(s)");
        Set<String> seen = new HashSet<>();
        for (RefPayloads.AccountDto dto : dtos) {
            Employee account = Employee.find("loginName", dto.loginName).firstResult();
            Employee emailHolder = dto.email == null ? null : Employee.find("email", dto.email).firstResult();
            if (emailHolder != null && (account == null || !emailHolder.id.equals(account.id))) {
                LOGGER.warnf("Compte central %s ignoré : adresse %s déjà portée par %s",
                        dto.loginName, dto.email, emailHolder.loginName);
                continue;
            }
            if (account == null) {
                account = new Employee();
                account.loginName = dto.loginName;
            }
            Employee badgeHolder = dto.badgeId == null ? null : Employee.find("badgeId", dto.badgeId).firstResult();
            if (badgeHolder != null && (account.id == null || !badgeHolder.id.equals(account.id))) {
                LOGGER.warnf("Compte central %s ignoré : badge %s déjà porté par %s",
                        dto.loginName, dto.badgeId, badgeHolder.loginName);
                continue;
            }
            account.firstName = dto.firstName == null ? "" : dto.firstName;
            account.lastName = dto.lastName;
            account.email = dto.email;
            account.password = dto.password;
            account.role = Employee.EmployeeRole.valueOf(dto.role);
            account.badgeId = dto.badgeId;
            account.active = dto.active;
            account.phone = dto.phone;
            account.startDate = dto.startDate == null ? null : LocalDate.parse(dto.startDate);
            account.department = dto.department;
            account.hierarchyLevel = dto.hierarchyLevel;
            account.language = dto.language;
            account.echelonScopes = dto.echelonScopes;
            account.authMode = Employee.AuthMode.from(dto.authMode);
            account.mustChangePassword = dto.mustChangePassword;
            account.passwordChangedAt = dto.passwordChangedAt == null
                    ? null : LocalDateTime.parse(dto.passwordChangedAt);
            account.supervisorCard = dto.supervisorCard;
            account.dallasKey = dallasKeyFor(account, dto.loginName, dto.dallasKey);
            account.centralManaged = true;
            account.persist();
            Employee.flush();
            Employee.adoptBackOfficePasswordHash(account.id, dto.backOfficePassword);
            seen.add(dto.loginName);
        }
        long deactivated = 0;
        for (Employee account : Employee.<Employee>list("centralManaged", true)) {
            if (!seen.contains(account.loginName) && account.active) {
                account.active = false;
                account.persist();
                deactivated++;
            }
        }
        LOGGER.infof("Comptes centraux appliqués: %d ligne(s), %d désactivé(s)", seen.size(), deactivated);
        LOGGER.info("Exiting method applyAccounts");
    }

    /**
     * Applies the profile assignments of the central accounts (BO-01-03-04):
     * the central assignments are replaced as a whole, account by login and
     * profile by name; an assignment naming an account or a profile this node
     * does not hold is skipped. The store's own assignments are untouched.
     *
     * @param dtos the assignments the centre hands to this point of sale
     */
    @Transactional
    public void applyAccountProfiles(List<RefPayloads.AccountProfileDto> dtos) {
        LOGGER.info("Entering method applyAccountProfiles with " + dtos.size() + " assignment(s)");
        EmployeeProfile.delete("centralManaged", true);
        int applied = 0;
        for (RefPayloads.AccountProfileDto dto : dtos) {
            Employee account = Employee.find("loginName", dto.loginName).firstResult();
            Profile profile = Profile.findByName(dto.profileName);
            if (account == null || profile == null) {
                continue;
            }
            EmployeeProfile binding = new EmployeeProfile();
            binding.employeeId = account.id;
            binding.profileId = profile.id;
            binding.echelonScope = dto.echelonScope == null ? EmployeeProfile.GLOBAL : dto.echelonScope;
            binding.centralManaged = true;
            binding.persist();
            applied++;
        }
        LOGGER.infof("Affectations centrales appliquées: %d ligne(s)", applied);
        LOGGER.info("Exiting method applyAccountProfiles");
    }

    /**
     * Resolves an option from its distributed name.
     *
     * @param name the name, possibly null
     * @return the option, or null when unknown
     */
    static CrudOption optionOf(String name) {
        for (CrudOption option : CrudOption.values()) {
            if (option.name().equals(name)) {
                return option;
            }
        }
        return null;
    }

    /**
     * Applies an echelon-parameters snapshot (store node, route A): wholesale
     * replacement, then the settings cache is dropped so this store re-resolves
     * its own PDV's effective values on the next read — this is what carries a
     * value posed at the enseigne down to the registers, and what makes a
     * future-dated value apply on its own once its day comes (BO-03-12-03/04).
     * The store's OWN local overrides live in a different table ({@code
     * pos_settings}) and are untouched, so they survive this apply.
     *
     * @param dtos the full echelon-settings snapshot
     */
    @Transactional
    public void applyEchelonSettings(List<RefPayloads.EchelonSettingDto> dtos) {
        LOGGER.info("Entering method applyEchelonSettings with dtos: " + dtos);
        // Wholesale replacement of the CENTRAL levels only. The two levels below
        // the PDV — ISLAND and CHECKOUT_POINT — are administered locally on this
        // store node and are NOT part of the central snapshot, so a central pull
        // must not wipe them (a plain deleteAll() would silently destroy the
        // island and per-terminal settings on every pull).
        EchelonSetting.delete("level in ?1",
                List.of(EchelonLevel.COUNTRY, EchelonLevel.ENSEIGNE, EchelonLevel.PDV));
        for (RefPayloads.EchelonSettingDto dto : dtos) {
            EchelonSetting row = new EchelonSetting();
            row.level = EchelonLevel.valueOf(dto.level);
            row.echelonCode = dto.echelonCode;
            row.settingKey = dto.settingKey;
            row.settingValue = dto.settingValue;
            row.pendingValue = dto.pendingValue;
            row.effectiveDate = dto.effectiveDate != null ? LocalDate.parse(dto.effectiveDate) : null;
            row.persist();
        }
        posSettingsService.invalidate();
        LOGGER.infof("Paramètres d'échelon appliqués: %d ligne(s)", dtos.size());
        LOGGER.info("Exiting method applyEchelonSettings");
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
     * Records one INTEGRATION RESULT, where the shop traces them
     * (BO-10-08-23).
     *
     * <p>Beside {@link #recordApplied}, which keeps the state, and never
     * instead of it: the state says what the node holds, this says what
     * happened. Its own transaction, like every other write here, because the
     * pull loop runs on a scheduler thread with none.
     *
     * <p>A failure to TRACE must never fail an integration, and a failure to
     * integrate must still be traced: the caller therefore records the outcome
     * after the fact, and this method swallows nothing but its own trouble.
     *
     * @param domain the referential domain
     * @param fingerprint the fingerprint the node was reaching, or null
     * @param outcome how the attempt ended
     * @param detail what went wrong, or null for a success
     */
    @Transactional
    public void recordAudit(String domain, String fingerprint,
            RefApplyAudit.Outcome outcome, String detail) {
        LOGGER.info("Entering method recordAudit with domain: " + domain);
        if (!posSettingsService.referentialUpdateAuditEnabled()) {
            LOGGER.info("Exiting method recordAudit");
            return;
        }
        RefApplyAudit audit = new RefApplyAudit();
        audit.domain = domain;
        audit.appliedAt = LocalDateTime.now();
        audit.fingerprint = fingerprint;
        audit.outcome = outcome;
        audit.detail = detail == null || detail.length() <= 500
                ? detail : detail.substring(0, 500);
        audit.persist();
        LOGGER.info("Exiting method recordAudit");
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

    /**
     * Returns the Dallas key a pulled employee or a distributed account may
     * carry here (BO-01-02-07): its own, unless another account of this node
     * already holds it — the key is then left off with a warning rather than
     * the whole referential failing on the unique column.
     *
     * @param account the employee or account being written
     * @param loginName its login, for the warning
     * @param key the distributed key, possibly null
     * @return the key to store, or null
     */
    String dallasKeyFor(Employee account, String loginName, String key) {
        if (key == null) {
            return null;
        }
        Employee holder = Employee.find("dallasKey", key).firstResult();
        if (holder != null && (account.id == null || !holder.id.equals(account.id))) {
            LOGGER.warnf("Clé Dallas de %s ignorée : déjà portée par %s", loginName, holder.loginName);
            return null;
        }
        return key;
    }

    /**
     * Applies the cash-menu snapshot (BO-03-10-16/17): upsert by code, a model
     * the snapshot no longer names deactivated rather than deleted, so a
     * register whose parameter still names it falls back on every key instead
     * of failing on a missing row.
     *
     * @param dtos the full snapshot
     */
    @Transactional
    public void applyCashMenus(List<RefPayloads.CashMenuDto> dtos) {
        LOGGER.info("Entering method applyCashMenus with " + dtos.size() + " model(s)");
        Set<String> seen = new HashSet<>();
        for (RefPayloads.CashMenuDto dto : dtos) {
            if (dto.code == null || dto.code.isBlank()) {
                continue;
            }
            com.intermarche.edge.domain.store.CashMenu menu =
                    com.intermarche.edge.domain.store.CashMenu.findByCode(dto.code);
            if (menu == null) {
                menu = new com.intermarche.edge.domain.store.CashMenu();
                menu.code = dto.code.trim();
            }
            menu.label = dto.label == null || dto.label.isBlank() ? menu.code : dto.label;
            menu.active = dto.active;
            menu.menuKeys = dto.menuKeys;
            menu.hiddenFunctions = dto.hiddenFunctions;
            menu.keyColumns = dto.keyColumns;
            menu.cashierScreen = dto.cashierScreen;
            menu.customerScreen = dto.customerScreen;
            menu.ticketModel = dto.ticketModel;
            menu.persist();
            seen.add(menu.code);
        }
        for (com.intermarche.edge.domain.store.CashMenu menu
                : com.intermarche.edge.domain.store.CashMenu
                        .<com.intermarche.edge.domain.store.CashMenu>listAll()) {
            if (!seen.contains(menu.code) && menu.active) {
                menu.active = false;
                menu.persist();
            }
        }
        LOGGER.info("Exiting method applyCashMenus");
    }

    /**
     * Applies the store record (BO-03-10-19/20, BO-03-12-01): the descriptive
     * fields of the node's store are copied onto this node's single store row,
     * created when it has none, so the document headers print what the back
     * office states. The node-local theme and the GPS coordinates are kept.
     *
     * @param dtos the snapshot, one row at most
     */
    @Transactional
    public void applyStore(List<RefPayloads.StoreDto> dtos) {
        LOGGER.info("Entering method applyStore with " + dtos.size() + " row(s)");
        if (dtos.isEmpty() || dtos.get(0).name == null || dtos.get(0).name.isBlank()) {
            LOGGER.info("Exiting method applyStore");
            return;
        }
        RefPayloads.StoreDto dto = dtos.get(0);
        com.intermarche.edge.domain.store.Store store =
                com.intermarche.edge.domain.store.Store.<com.intermarche.edge.domain.store.Store>findAll()
                        .firstResult();
        if (store == null) {
            store = new com.intermarche.edge.domain.store.Store();
            store.code = dto.code;
        }
        store.name = dto.name;
        if (store.address == null) {
            store.address = new com.intermarche.edge.domain.store.Address();
        }
        store.address.streetLine1 = dto.streetLine1;
        store.address.streetLine2 = dto.streetLine2;
        store.address.postalCode = dto.postalCode;
        store.address.city = dto.city;
        store.address.country = dto.country;
        store.vatNumber = dto.vatNumber;
        store.siret = dto.siret;
        store.phone = dto.phone;
        store.bankAccountNumber = dto.bankAccountNumber;
        store.legalName = dto.legalName;
        store.rcs = dto.rcs;
        store.shareCapital = dto.shareCapital == null ? null : new java.math.BigDecimal(dto.shareCapital);
        store.fax = dto.fax;
        store.persist();
        LOGGER.info("Exiting method applyStore");
    }
}
