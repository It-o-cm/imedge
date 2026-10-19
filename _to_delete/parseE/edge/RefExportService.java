package com.intermarche.edge.service.sync.store;

import com.intermarche.edge.domain.barcode.CouponControl;
import com.intermarche.edge.domain.barcode.CouponField;
import com.intermarche.edge.domain.barcode.CouponType;
import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.setting.EchelonSetting;
import com.intermarche.edge.domain.setting.TouchGroupSetting;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import com.intermarche.edge.service.sync.RefPayloads;

/**
 * Store-side half of the centralized referentials (phase 6 lot 3): maps each
 * referential to its snapshot payloads and computes a SHA-256 fingerprint
 * per domain over the canonical row stream. The fingerprint is cached for a
 * short TTL so the registers' cheap polling never rescans the tables more
 * than once per minute — and no hook in the CSV imports is needed: any
 * change (insert, update, delete) changes the fingerprint by construction.
 * <p>
 * Maintenance invariant: {@code canonical(row)} and the DTO mappings MUST
 * evolve together — a field added to a payload but forgotten in its
 * canonical string would propagate on the next unrelated change only,
 * silently breaking the "any change changes the fingerprint" guarantee.
 * The register never recomputes a fingerprint: it stores the remote value
 * verbatim after applying, so both sides always compare the store node's
 * own arithmetic.
 */
@ApplicationScoped
public class RefExportService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RefExportService.class);

    /** Fingerprint cache TTL in milliseconds. */
    private static final long FINGERPRINT_TTL_MS = 60_000;

    /** Cached fingerprint per domain. */
    private final Map<String, String> fingerprintCache = new HashMap<>();

    /** Cache timestamp per domain (epoch millis). */
    private final Map<String, Long> fingerprintCachedAt = new HashMap<>();

    /** Resolves the effective SETTINGS values a store publishes to its registers. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Returns the fingerprint of every REGISTER-facing domain, recomputing the
     * expired ones.
     *
     * @return an ordered map domain to fingerprint
     */
    public synchronized Map<String, String> getFingerprints() {
        LOGGER.info("Entering method getFingerprints");
        LOGGER.info("Exiting method getFingerprints");
        return getFingerprints(RefPayloads.DOMAINS, null);
    }

    /**
     * Returns the fingerprint of every domain in the given set, recomputing the
     * expired ones — the store node passes {@link RefPayloads#DOMAINS}, the
     * central node passes {@link RefPayloads#ECHELON_DOMAINS}.
     *
     * @param domains the domains to fingerprint
     * @return an ordered map domain to fingerprint
     */
    public synchronized Map<String, String> getFingerprints(List<String> domains) {
        LOGGER.info("Entering method getFingerprints with domains: " + domains);
        LOGGER.info("Exiting method getFingerprints");
        return getFingerprints(domains, null);
    }

    /**
     * Returns the fingerprint of every domain in the given set AS SEEN BY ONE
     * TERMINAL, recomputing the expired ones.
     *
     * <p>The terminal matters for exactly one domain, SETTINGS, whose rows are
     * the echelon resolution of the register that asks. Its fingerprint is
     * therefore cached PER TERMINAL: keyed by domain alone, register B would be
     * told "nothing changed" because register A had just warmed the cache with
     * ITS resolution, and would then keep another lane's parameters — a fault
     * whose symptom (a register behaving like its neighbour) says nothing about
     * its cause. Every other domain is global and keeps the bare domain key, so
     * one register's poll still serves the whole lane.
     *
     * @param domains the domains to fingerprint
     * @param terminalId the asking register's identifier, or null when none
     *        named itself
     * @return an ordered map domain to fingerprint
     */
    public synchronized Map<String, String> getFingerprints(List<String> domains, String terminalId) {
        LOGGER.info("Entering method getFingerprints with domains: " + domains
                + ", terminalId: " + terminalId);
        Map<String, String> result = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String domain : domains) {
            String key = cacheKey(domain, terminalId);
            Long cachedAt = fingerprintCachedAt.get(key);
            if (cachedAt == null || now - cachedAt > FINGERPRINT_TTL_MS) {
                fingerprintCache.put(key, computeFingerprint(domain, terminalId));
                fingerprintCachedAt.put(key, now);
            }
            result.put(domain, fingerprintCache.get(key));
        }
        LOGGER.info("Exiting method getFingerprints");
        return result;
    }

    /**
     * Builds the cache key of a domain's fingerprint: the domain alone for a
     * global domain, the domain and the terminal for the one domain resolved
     * per register.
     *
     * @param domain the referential domain
     * @param terminalId the asking register's identifier, or null
     * @return the cache key, never null
     */
    private String cacheKey(String domain, String terminalId) {
        if (!isTerminalScoped(domain) || terminalId == null || terminalId.isBlank()) {
            return domain;
        }
        return domain + "@" + terminalId.trim();
    }

    /**
     * Tells whether a domain's rows depend on which register is asking.
     *
     * <p>Only SETTINGS does: it carries the result of the echelon resolution,
     * which walks down to the terminal. A domain added here must also be paged
     * with the terminal, or its fingerprint and its content would disagree.
     *
     * @param domain the referential domain
     * @return true when the domain is resolved per register
     */
    private boolean isTerminalScoped(String domain) {
        return "SETTINGS".equals(domain);
    }

    /**
     * Returns one page of a domain's snapshot, in canonical order.
     * <p>
     * Transactional because a family payload now reads its lazy article
     * collection: a snapshot page must never depend on whether a session
     * happens to be open around the call.
     *
     * @param domain the referential domain
     * @param page the 0-based page index
     * @param size the page size
     * @return the page payloads, empty past the end
     * @throws IllegalArgumentException on an unknown domain
     */
    @Transactional
    public List<?> getPage(String domain, int page, int size) {
        LOGGER.info("Entering method getPage with domain: " + domain + ", page: " + page + ", size: " + size);
        LOGGER.info("Exiting method getPage");
        return getPage(domain, page, size, null);
    }

    /**
     * Returns one page of a domain's snapshot AS SEEN BY ONE TERMINAL, in
     * canonical order.
     *
     * <p>The terminal changes exactly one domain, SETTINGS, whose rows are the
     * echelon resolution of that register (route A, BO-02-05-04). Every other
     * domain ignores it and answers the same rows to every lane.
     * <p>
     * Transactional because a family payload now reads its lazy article
     * collection: a snapshot page must never depend on whether a session
     * happens to be open around the call.
     *
     * @param domain the referential domain
     * @param page the 0-based page index
     * @param size the page size
     * @param terminalId the asking register's identifier, or null when none
     *        named itself
     * @return the page payloads, empty past the end
     * @throws IllegalArgumentException on an unknown domain
     */
    @Transactional
    public List<?> getPage(String domain, int page, int size, String terminalId) {
        LOGGER.info("Entering method getPage with domain: " + domain + ", page: " + page
                + ", size: " + size + ", terminalId: " + terminalId);
        LOGGER.info("Exiting method getPage");
        return switch (domain) {
            case "FAMILIES" -> ProductFamily.<ProductFamily>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "TOUCH_GROUPS" -> TouchGroupSetting.<TouchGroupSetting>find("order by familyCode")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "PRODUCTS" -> Product.<Product>find("order by ean")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "VAT_RATES" -> VatRate.<VatRate>find("order by number")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "PRICES" -> Price.<Price>find("order by id")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "EMPLOYEES" -> Employee.<Employee>find("order by loginName")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "COUPON_TYPES" -> CouponType.<CouponType>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "ISLANDS" -> com.intermarche.edge.domain.store.CheckoutIsland
                    .<com.intermarche.edge.domain.store.CheckoutIsland>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "CHECKOUT_POINTS" -> com.intermarche.edge.domain.store.CheckoutPoint
                    .<com.intermarche.edge.domain.store.CheckoutPoint>find("order by terminalId")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "ARTICLE_RANGES" -> com.intermarche.edge.domain.barcode.ArticleBarcodeRange
                    .<com.intermarche.edge.domain.barcode.ArticleBarcodeRange>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "TENDERS" -> com.intermarche.edge.domain.payment.TenderDefinition
                    .<com.intermarche.edge.domain.payment.TenderDefinition>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "DOCUMENT_TEMPLATES" -> com.intermarche.edge.domain.setting.DocumentTemplate
                    .<com.intermarche.edge.domain.setting.DocumentTemplate>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "PROVIDER_MESSAGES" -> com.intermarche.edge.domain.setting.ProviderMessage
                    .<com.intermarche.edge.domain.setting.ProviderMessage>find(
                            "order by providerCode, messageCode")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "CUSTOMERS" -> com.intermarche.edge.domain.payment.AccountCustomer
                    .<com.intermarche.edge.domain.payment.AccountCustomer>find("order by accountNumber")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "POSTAL_CODES" -> com.intermarche.edge.domain.store.PostalCode
                    .<com.intermarche.edge.domain.store.PostalCode>find("order by code, place")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "CURRENCIES" -> com.intermarche.edge.domain.payment.Currency
                    .<com.intermarche.edge.domain.payment.Currency>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "SETTINGS" -> settingsPage(page, size, terminalId);
            case "ENGINE_FEEDS" -> com.intermarche.edge.domain.sync.EngineFeed
                    .<com.intermarche.edge.domain.sync.EngineFeed>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "COUNTRIES" -> Country.<Country>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "ENSEIGNES" -> Enseigne.<Enseigne>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "PDVS" -> Pdv.<Pdv>find("order by pdvNumber")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "ECHELON_SETTINGS" -> EchelonSetting
                    .<EchelonSetting>find("order by level, echelonCode, settingKey")
                    .page(page, size).list().stream().map(this::toDto).toList();
            default -> throw new IllegalArgumentException("Domaine inconnu: " + domain);
        };
    }

    /**
     * Builds one page of the SETTINGS snapshot from the RESOLVED effective
     * values (route A, BO-02-05-04): the store node publishes the result of the
     * echelon resolution for its own PDV, not the raw {@code pos_settings} rows,
     * so a register receives a parameter, never the echelon chain. Ordered by
     * key, paged in memory (the administered set is small and bounded).
     *
     * <p>Resolved FOR THE ASKING REGISTER when it named itself: the chain then
     * walks the two echelons of the shop as well — the island, then the checkout
     * point — so a value posed on one lane beats its island's, which beats the
     * PDV's. A null terminal keeps the PDV resolution, which is what an older
     * register that does not name itself receives.
     *
     * @param page the 0-based page index
     * @param size the page size
     * @param terminalId the asking register's identifier, or null
     * @return the page of setting payloads, empty past the end
     */
    private List<RefPayloads.SettingDto> settingsPage(int page, int size, String terminalId) {
        List<RefPayloads.SettingDto> all = new ArrayList<>();
        for (Map.Entry<String, String> entry
                : posSettingsService.administeredValues(terminalId).entrySet()) {
            RefPayloads.SettingDto dto = new RefPayloads.SettingDto();
            dto.key = entry.getKey();
            dto.value = entry.getValue();
            all.add(dto);
        }
        int from = page * size;
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, Math.min(from + size, all.size()));
    }

    /**
     * Maps a foreign currency to its snapshot payload.
     *
     * @param currency the currency entity
     * @return the transport DTO
     */
    private RefPayloads.CurrencyDto toDto(com.intermarche.edge.domain.payment.Currency currency) {
        RefPayloads.CurrencyDto dto = new RefPayloads.CurrencyDto();
        dto.code = currency.code;
        dto.label = currency.label;
        dto.symbol = currency.symbol;
        dto.euroPerUnit = currency.euroPerUnit == null
                ? null : currency.euroPerUnit.toPlainString();
        dto.active = currency.active;
        dto.displayOrder = currency.displayOrder;
        dto.changeTenderCode = currency.changeTenderCode;
        return dto;
    }

    /**
     * Maps an account customer to its snapshot payload.
     *
     * <p>The two credit figures travel as TEXT like every other amount here: the
     * canonical row that feeds the fingerprint is a string, and a decimal formatted
     * twice must read the same twice or the register would re-pull the whole
     * customer base on every cycle.
     *
     * @param customer the account customer entity
     * @return the transport DTO
     */
    private RefPayloads.CustomerDto toDto(com.intermarche.edge.domain.payment.AccountCustomer customer) {
        RefPayloads.CustomerDto dto = new RefPayloads.CustomerDto();
        dto.accountNumber = customer.accountNumber;
        dto.companyName = customer.companyName;
        dto.origin = customer.origin == null ? null : customer.origin.name();
        dto.civility = customer.civility;
        dto.lastName = customer.lastName;
        dto.firstName = customer.firstName;
        dto.street = customer.address == null ? null : customer.address.streetLine1;
        dto.postalCode = customer.address == null ? null : customer.address.postalCode;
        dto.city = customer.address == null ? null : customer.address.city;
        dto.siret = customer.siret;
        dto.vatNumber = customer.vatNumber;
        dto.phone = customer.phone;
        dto.email = customer.email;
        dto.taxId = customer.taxId;
        dto.segment = customer.segment;
        dto.blocked = customer.blocked;
        dto.dueDate = customer.dueDate == null ? null : customer.dueDate.toString();
        dto.discountPercent = customer.discountPercent == null
                ? null : customer.discountPercent.toPlainString();
        dto.creditLimit = customer.creditLimit == null ? null : customer.creditLimit.toPlainString();
        dto.creditBalance = customer.creditBalance == null
                ? null : customer.creditBalance.toPlainString();
        if (customer.freeFields != null) {
            for (com.intermarche.edge.domain.payment.AccountCustomer.FreeField field : customer.freeFields) {
                RefPayloads.FreeFieldDto fieldDto = new RefPayloads.FreeFieldDto();
                fieldDto.slot = field.slot;
                fieldDto.label = field.label;
                fieldDto.value = field.value;
                dto.freeFields.add(fieldDto);
            }
        }
        return dto;
    }

    /**
     * Maps a postal code to its snapshot payload ({@code BO-02-04-15}).
     *
     * @param postalCode the row
     * @return the transport DTO
     */
    private RefPayloads.PostalCodeDto toDto(com.intermarche.edge.domain.store.PostalCode postalCode) {
        RefPayloads.PostalCodeDto dto = new RefPayloads.PostalCodeDto();
        dto.code = postalCode.code;
        dto.place = postalCode.place;
        dto.country = postalCode.country;
        return dto;
    }

    /**
     * Maps a country echelon to its snapshot payload.
     *
     * @param country the country entity
     * @return the transport DTO
     */
    private RefPayloads.CountryDto toDto(Country country) {
        RefPayloads.CountryDto dto = new RefPayloads.CountryDto();
        dto.code = country.code;
        dto.name = country.name;
        dto.defaultLanguage = country.defaultLanguage;
        return dto;
    }

    /**
     * Maps an enseigne echelon to its snapshot payload.
     *
     * @param enseigne the enseigne entity
     * @return the transport DTO
     */
    private RefPayloads.EnseigneDto toDto(Enseigne enseigne) {
        RefPayloads.EnseigneDto dto = new RefPayloads.EnseigneDto();
        dto.code = enseigne.code;
        dto.name = enseigne.name;
        dto.countryCode = enseigne.countryCode;
        dto.defaultLanguage = enseigne.defaultLanguage;
        return dto;
    }

    /**
     * Maps a point de vente to its snapshot payload.
     *
     * @param pdv the PDV entity
     * @return the transport DTO
     */
    private RefPayloads.PdvDto toDto(Pdv pdv) {
        RefPayloads.PdvDto dto = new RefPayloads.PdvDto();
        dto.pdvNumber = pdv.pdvNumber;
        dto.name = pdv.name;
        dto.enseigneCode = pdv.enseigneCode;
        dto.adherentCode = pdv.adherentCode;
        dto.active = pdv.active;
        return dto;
    }

    /**
     * Maps an echelon parameter to its snapshot payload, its effect date as an
     * ISO date string.
     *
     * @param setting the echelon setting entity
     * @return the transport DTO
     */
    private RefPayloads.EchelonSettingDto toDto(EchelonSetting setting) {
        RefPayloads.EchelonSettingDto dto = new RefPayloads.EchelonSettingDto();
        dto.level = setting.level != null ? setting.level.name() : null;
        dto.echelonCode = setting.echelonCode;
        dto.settingKey = setting.settingKey;
        dto.settingValue = setting.settingValue;
        dto.effectiveDate = setting.effectiveDate != null ? setting.effectiveDate.toString() : null;
        return dto;
    }

    /**
     * Maps a verbatim engine feed to its snapshot payload (content shipped
     * unopened).
     *
     * @param feed the stored feed row
     * @return the transport DTO
     */
    private RefPayloads.EngineFeedDto toDto(com.intermarche.edge.domain.sync.EngineFeed feed) {
        RefPayloads.EngineFeedDto dto = new RefPayloads.EngineFeedDto();
        dto.code = feed.code;
        dto.version = feed.version;
        dto.content = feed.content;
        return dto;
    }

    /**
     * Computes the SHA-256 fingerprint of a domain over its canonical row,
     * for the register that asked
     * stream.
     *
     * @param domain the referential domain
     * @param terminalId the asking register's identifier, or null
     * @return the fingerprint, hex encoded
     */
    private String computeFingerprint(String domain, String terminalId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int page = 0;
            List<?> rows;
            do {
                rows = getPage(domain, page++, 1000, terminalId);
                for (Object row : rows) {
                    digest.update(canonical(row).getBytes(StandardCharsets.UTF_8));
                }
            } while (!rows.isEmpty());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Empreinte incalculable pour " + domain, e);
        }
    }

    /**
     * Builds the canonical string of a payload row (field order fixed by the
     * mapping methods).
     *
     * @param row the payload row
     * @return the canonical string
     */
    private String canonical(Object row) {
        if (row instanceof RefPayloads.FamilyDto f) {
            return String.join("|", n(f.code), n(f.description), n(f.flags),
                    String.join(",", f.parentCodes), String.join(",", f.productEans));
        }
        if (row instanceof RefPayloads.TouchGroupDto t) {
            return String.join("|", n(t.familyCode), String.valueOf(t.pinned), n(t.buttonSize),
                    String.valueOf(t.displayOrder), String.valueOf(t.salesVolume));
        }
        if (row instanceof RefPayloads.ProductDto p) {
            return String.join("|", n(p.ean), n(p.plu), n(p.name), n(p.description), n(p.icon),
                    n(p.imageData), n(p.brand), n(p.referenceWeight), n(p.referenceVolume),
                    n(p.productType), n(p.unitName), String.valueOf(p.active),
                    String.valueOf(p.forbiddenToSale), n(p.ageRestriction), n(p.checkoutLabel),
                    n(p.internalCode), String.valueOf(p.variableWeight), n(p.infoMessage),
                    attributes(p.attributes));
        }
        if (row instanceof RefPayloads.VatRateDto v) {
            return String.join("|", n(v.number), n(v.rate), n(v.label));
        }
        if (row instanceof RefPayloads.PriceDto p) {
            return String.join("|", n(p.productEan), n(p.priceExcludingTax), n(p.priceIncludingTax),
                    n(p.vatNumber), n(p.priority), n(p.startDateTime), n(p.endDateTime));
        }
        if (row instanceof RefPayloads.EmployeeDto e) {
            return String.join("|", n(e.loginName), n(e.firstName), n(e.lastName), n(e.password),
                    n(e.email), n(e.role), n(e.badgeId), n(e.theme), String.valueOf(e.active));
        }
        if (row instanceof RefPayloads.SettingDto s) {
            return String.join("|", n(s.key), n(s.value));
        }
        if (row instanceof RefPayloads.CountryDto c) {
            return String.join("|", n(c.code), n(c.name), n(c.defaultLanguage));
        }
        if (row instanceof RefPayloads.EnseigneDto e) {
            return String.join("|", n(e.code), n(e.name), n(e.countryCode), n(e.defaultLanguage));
        }
        if (row instanceof RefPayloads.PdvDto p) {
            return String.join("|", n(p.pdvNumber), n(p.name), n(p.enseigneCode), n(p.adherentCode),
                    String.valueOf(p.active));
        }
        if (row instanceof RefPayloads.EchelonSettingDto s) {
            return String.join("|", n(s.level), n(s.echelonCode), n(s.settingKey), n(s.settingValue),
                    n(s.effectiveDate));
        }
        if (row instanceof RefPayloads.EngineFeedDto f) {
            // The version IS the SHA-256 of the content: hashing code and
            // version covers the whole parcel without streaming its body
            // through the fingerprint a second time.
            return String.join("|", n(f.code), n(f.version));
        }
        if (row instanceof RefPayloads.CouponTypeDto c) {
            return String.join("|", n(c.code), n(c.label), n(c.matchPattern), n(c.amountSource),
                    n(c.amountPattern), String.valueOf(c.priority), String.valueOf(c.active),
                    String.valueOf(c.depositLine), n(c.prefix), n(c.codeLength), n(c.codeKind),
                    String.valueOf(c.manualAmountOnAllNines), n(c.islandCodes),
                    couponFields(c), couponControls(c));
        }
        if (row instanceof RefPayloads.ArticleBarcodeRangeDto r) {
            return String.join("|", n(r.code), n(r.label), String.valueOf(r.active),
                    String.valueOf(r.priority), n(r.prefix), String.valueOf(r.codeLength),
                    n(r.codeKind), String.valueOf(r.articlePosition),
                    String.valueOf(r.articleLength), n(r.valueSource),
                    String.valueOf(r.valuePosition), String.valueOf(r.valueLength),
                    String.valueOf(r.valueDecimals), n(r.currency),
                    String.valueOf(r.checkDigit), n(r.matchPattern), n(r.purpose));
        }
        if (row instanceof RefPayloads.CheckoutIslandDto i) {
            return String.join("|", n(i.code), n(i.label), String.valueOf(i.active),
                    n(i.terminalIds));
        }
        if (row instanceof RefPayloads.CheckoutPointDto c) {
            return String.join("|", n(c.terminalId), n(c.pdvNumber), n(c.islandCode), n(c.label),
                    String.valueOf(c.active), n(c.tpvType), n(c.technicalHostessBadge),
                    n(c.cashierDisplayType), n(c.cashierDisplayResolution),
                    n(c.customerDisplayType), n(c.customerDisplayResolution),
                    n(c.menuTemplateCode));
        }
        if (row instanceof RefPayloads.TenderDefinitionDto t) {
            return String.join("|", n(t.code), n(t.functionalId), n(t.label),
                    String.valueOf(t.active), String.valueOf(t.displayOrder),
                    n(t.maxAmount), n(t.maxAmountControl),
                    n(t.secondMaxAmount), n(t.secondMaxAmountControl),
                    n(t.minAmount), n(t.minAmountControl),
                    t.maxCount == null ? "" : String.valueOf(t.maxCount), n(t.maxCountControl),
                    n(t.maxChangeAmount), n(t.maxChangeControl),
                    String.valueOf(t.refundAllowed), String.valueOf(t.changeAllowed),
                    n(t.changeTenderCode), String.valueOf(t.cashierDeclaration),
                    String.valueOf(t.automaticWithdrawal), n(t.drawerOpening),
                    String.valueOf(t.movementAllowed), String.valueOf(t.bankDeposit),
                    String.valueOf(t.floatAllowed), String.valueOf(t.defaultsToTotal),
                    String.valueOf(t.withdrawalReportDetail),
                    String.valueOf(t.fidelityReported), n(t.documentTemplateCode),
                    String.valueOf(t.printCount), String.valueOf(t.printDuplicate),
                    String.valueOf(t.printForced));
        }
        if (row instanceof RefPayloads.ProviderMessageDto m) {
            return String.join("|", n(m.providerCode), n(m.messageCode), n(m.screenText),
                    n(m.ticketText), String.valueOf(m.active));
        }
        if (row instanceof RefPayloads.DocumentTemplateDto t) {
            return String.join("|", n(t.code), n(t.label), n(t.documentType),
                    String.valueOf(t.active), String.valueOf(t.priority),
                    String.valueOf(t.width), String.valueOf(t.copies), n(t.source),
                    n(t.title), n(t.duplicateTitle), n(t.numberPrefix), n(t.copyTitles));
        }
        if (row instanceof RefPayloads.CurrencyDto c) {
            return String.join("|", n(c.code), n(c.label), n(c.symbol), n(c.euroPerUnit),
                    String.valueOf(c.active), String.valueOf(c.displayOrder),
                    n(c.changeTenderCode));
        }
        if (row instanceof RefPayloads.PostalCodeDto p) {
            return String.join("|", n(p.code), n(p.place), n(p.country));
        }
        if (row instanceof RefPayloads.CustomerDto c) {
            return String.join("|", n(c.accountNumber), n(c.companyName), n(c.origin), n(c.civility),
                    n(c.lastName), n(c.firstName), n(c.street), n(c.postalCode), n(c.city),
                    n(c.siret), n(c.vatNumber), n(c.taxId), n(c.segment), n(c.phone), n(c.email),
                    String.valueOf(c.blocked), n(c.dueDate), n(c.discountPercent),
                    n(c.creditLimit), n(c.creditBalance), freeFields(c.freeFields));
        }
        return String.valueOf(row);
    }

    /**
     * Null-safe canonical rendering of a field.
     *
     * @param value the field value, or null
     * @return the canonical string, empty for null
     */
    private String n(Object value) {
        return value != null ? value.toString() : "";
    }

    /**
     * Canonical rendering of a customer's free fields: sorted by slot and
     * joined, so the fingerprint does not move with the order the rows came
     * back in ({@code BO-02-04-11}).
     *
     * @param fields the free fields, possibly null or empty
     * @return the joined rendering, empty when there is none
     */
    private String freeFields(java.util.List<RefPayloads.FreeFieldDto> fields) {
        if (fields == null || fields.isEmpty()) {
            return "";
        }
        return fields.stream()
                .sorted(java.util.Comparator.comparingInt(field -> field.slot))
                .map(field -> field.slot + "=" + n(field.label) + "=" + n(field.value))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    /**
     * Canonical rendering of a product's declared attributes: entries sorted by
     * code and joined {@code code=value}, so the fingerprint is stable whatever
     * the map's iteration order. An empty or null map renders as the empty
     * string.
     *
     * @param attributes the attribute map, or null
     * @return the ordered canonical rendering
     */
    private String attributes(java.util.Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "";
        }
        return new java.util.TreeMap<>(attributes).entrySet().stream()
                .map(e -> e.getKey() + "=" + n(e.getValue()))
                .collect(java.util.stream.Collectors.joining(","));
    }

    // --------------------------------------------------
    // Entity to DTO mapping
    // --------------------------------------------------

    /**
     * Maps one touch configuration row to its payload.
     *
     * @param setting the administered row
     * @return the payload
     */
    private RefPayloads.TouchGroupDto toDto(TouchGroupSetting setting) {
        RefPayloads.TouchGroupDto dto = new RefPayloads.TouchGroupDto();
        dto.familyCode = setting.familyCode;
        dto.pinned = setting.pinned;
        dto.buttonSize = setting.buttonSize;
        dto.displayOrder = setting.displayOrder;
        dto.salesVolume = setting.salesVolume;
        return dto;
    }

    /**
     * Maps a product family to its payload.
     *
     * @param family the family entity
     * @return the payload
     */
    private RefPayloads.FamilyDto toDto(ProductFamily family) {
        RefPayloads.FamilyDto dto = new RefPayloads.FamilyDto();
        dto.code = family.code;
        dto.description = family.description;
        dto.flags = family.flags;
        dto.parentCodes = ProductFamily.<ProductFamily>find(
                "select p from ProductFamily p join p.productFamilies c where c.code = ?1"
                        + " order by p.code", family.code)
                .list().stream().map(p -> p.code).toList();
        dto.productEans = family.products.stream()
                .map(product -> product.ean).sorted().toList();
        return dto;
    }

    /**
     * Maps a product to its payload.
     *
     * @param product the product entity
     * @return the payload
     */
    private RefPayloads.ProductDto toDto(Product product) {
        RefPayloads.ProductDto dto = new RefPayloads.ProductDto();
        dto.ean = product.ean;
        dto.plu = product.plu;
        dto.name = product.name;
        dto.description = product.description;
        dto.icon = product.icon;
        dto.imageData = product.imageData;
        dto.brand = product.brand;
        dto.referenceWeight = product.referenceWeight;
        dto.referenceVolume = product.referenceVolume;
        dto.productType = product.productType != null ? product.productType.name() : null;
        dto.unitName = product.unitName;
        dto.active = product.active;
        dto.forbiddenToSale = product.forbiddenToSale;
        dto.ageRestriction = product.ageRestriction;
        dto.checkoutLabel = product.checkoutLabel;
        dto.infoMessage = product.infoMessage;
        dto.internalCode = product.internalCode;
        dto.variableWeight = product.variableWeight;
        dto.attributes = product.attributes != null
                ? new java.util.TreeMap<>(product.attributes) : new java.util.TreeMap<>();
        return dto;
    }

    /**
     * Maps a VAT regime to its payload.
     *
     * @param rate the regime entity
     * @return the payload
     */
    private RefPayloads.VatRateDto toDto(VatRate rate) {
        RefPayloads.VatRateDto dto = new RefPayloads.VatRateDto();
        dto.number = rate.number;
        dto.rate = rate.rate;
        dto.label = rate.label;
        return dto;
    }

    /**
     * Maps a price row to its payload.
     *
     * @param price the price entity
     * @return the payload
     */
    private RefPayloads.PriceDto toDto(Price price) {
        RefPayloads.PriceDto dto = new RefPayloads.PriceDto();
        dto.productEan = price.product != null ? price.product.ean : null;
        dto.priceExcludingTax = price.priceExcludingTax;
        dto.priceIncludingTax = price.priceIncludingTax;
        dto.vatNumber = price.vatNumber();
        dto.priority = price.priority;
        dto.startDateTime = iso(price.startDateTime);
        dto.endDateTime = iso(price.endDateTime);
        return dto;
    }

    /**
     * Maps an employee to its payload.
     *
     * @param employee the employee entity
     * @return the payload
     */
    private RefPayloads.EmployeeDto toDto(Employee employee) {
        RefPayloads.EmployeeDto dto = new RefPayloads.EmployeeDto();
        dto.loginName = employee.loginName;
        dto.firstName = employee.firstName;
        dto.lastName = employee.lastName;
        dto.password = employee.password;
        dto.email = employee.email;
        dto.role = employee.role.name();
        dto.badgeId = employee.badgeId;
        dto.theme = employee.theme;
        dto.active = employee.active;
        return dto;
    }

    /**
     * Maps a coupon type to its payload.
     *
     * @param type the coupon type entity
     * @return the payload
     */
    /**
     * Maps an administered ARTICLE barcode range onto its snapshot row
     * (BO-03-06-02/03/04/05/10): the register that receives it reads its scale
     * plan from the referential, exactly as it reads its voucher ranges.
     *
     * @param range the stored range
     * @return the snapshot row
     */
    /**
     * Maps a checkout island onto its snapshot row (BO-03-06-07, BO-03-06-50):
     * a register cannot know which island it stands on unless the island
     * travels to it.
     *
     * @param island the stored island
     * @return the snapshot row
     */
    private RefPayloads.CheckoutIslandDto toDto(
            com.intermarche.edge.domain.store.CheckoutIsland island) {
        RefPayloads.CheckoutIslandDto dto = new RefPayloads.CheckoutIslandDto();
        dto.code = island.code;
        dto.label = island.label;
        dto.active = island.active;
        // terminalIds is no longer stored on the island: it is DERIVED from the
        // CheckoutPoint relation, so the wire stays byte-compatible with a
        // register that has not been updated (it still reads a semicolon list),
        // while the truth of the relation lives on CheckoutPoint.
        java.util.List<String> terminals = island.terminals();
        dto.terminalIds = terminals.isEmpty() ? null : String.join(";", terminals);
        return dto;
    }

    /**
     * Maps an administered CHECKOUT POINT to its snapshot row (BO-03-09,
     * BO-03-10).
     *
     * <p>The two enumerations travel by NAME, and a null one travels as null
     * rather than as a chosen default: the register's own column carries its own
     * default, and inventing one here would hide a point the back office has not
     * finished describing.
     *
     * @param point the stored checkout point
     * @return the snapshot row
     */
    private RefPayloads.CheckoutPointDto toDto(
            com.intermarche.edge.domain.store.CheckoutPoint point) {
        RefPayloads.CheckoutPointDto dto = new RefPayloads.CheckoutPointDto();
        dto.terminalId = point.terminalId;
        dto.pdvNumber = point.pdvNumber;
        dto.islandCode = point.islandCode;
        dto.label = point.label;
        dto.active = point.active;
        dto.tpvType = point.tpvType == null ? null : point.tpvType.name();
        dto.technicalHostessBadge = point.technicalHostessBadge;
        dto.cashierDisplayType = point.cashierDisplayType == null
                ? null : point.cashierDisplayType.name();
        dto.cashierDisplayResolution = point.cashierDisplayResolution;
        dto.customerDisplayType = point.customerDisplayType == null
                ? null : point.customerDisplayType.name();
        dto.customerDisplayResolution = point.customerDisplayResolution;
        dto.menuTemplateCode = point.menuTemplateCode;
        return dto;
    }

    /**
     * Maps an administered TENDER to its snapshot row (BO-03-02-03/04/10 to 30).
     *
     * <p>The bounds travel as plain strings rather than as decimals, for the
     * reason stated on the canonical contract above: a decimal formatted one way
     * here and another way at the other end would move the fingerprint without
     * anything having changed.
     *
     * @param tender the stored tender
     * @return the snapshot row
     */
    private RefPayloads.TenderDefinitionDto toDto(
            com.intermarche.edge.domain.payment.TenderDefinition tender) {
        RefPayloads.TenderDefinitionDto dto = new RefPayloads.TenderDefinitionDto();
        dto.code = tender.code;
        dto.functionalId = tender.functionalId;
        dto.label = tender.label;
        dto.active = tender.active;
        dto.displayOrder = tender.displayOrder;
        dto.maxAmount = plain(tender.maxAmount);
        dto.maxAmountControl = name(tender.maxAmountControl);
        dto.secondMaxAmount = plain(tender.secondMaxAmount);
        dto.secondMaxAmountControl = name(tender.secondMaxAmountControl);
        dto.minAmount = plain(tender.minAmount);
        dto.minAmountControl = name(tender.minAmountControl);
        dto.maxCount = tender.maxCount;
        dto.maxCountControl = name(tender.maxCountControl);
        dto.maxChangeAmount = plain(tender.maxChangeAmount);
        dto.maxChangeControl = name(tender.maxChangeControl);
        dto.refundAllowed = tender.refundAllowed;
        dto.changeAllowed = tender.changeAllowed;
        dto.changeTenderCode = tender.changeTenderCode;
        dto.cashierDeclaration = tender.cashierDeclaration;
        dto.automaticWithdrawal = tender.automaticWithdrawal;
        dto.drawerOpening = name(tender.drawerOpening);
        dto.movementAllowed = tender.movementAllowed;
        dto.bankDeposit = tender.bankDeposit;
        dto.floatAllowed = tender.floatAllowed;
        dto.defaultsToTotal = tender.defaultsToTotal;
        dto.withdrawalReportDetail = tender.withdrawalReportDetail;
        dto.fidelityReported = tender.fidelityReported;
        dto.documentTemplateCode = tender.documentTemplateCode;
        dto.printCount = tender.printCount;
        dto.printDuplicate = tender.printDuplicate;
        dto.printForced = tender.printForced;
        return dto;
    }

    /**
     * Maps an administered DOCUMENT TEMPLATE to its snapshot row (BO-03-03).
     *
     * <p>The source travels verbatim: a layout the central node wrote is the
     * layout the register prints, character for character. Reformatting it here
     * would move the fingerprint without anything having changed.
     *
     * @param template the stored template
     * @return the snapshot row
     */
    /**
     * Renders one provider-message substitution for the snapshot
     * (BO-03-08-06 and -07).
     *
     * @param message the declared substitution
     * @return its travelling form
     */
    private RefPayloads.ProviderMessageDto toDto(
            com.intermarche.edge.domain.setting.ProviderMessage message) {
        RefPayloads.ProviderMessageDto dto = new RefPayloads.ProviderMessageDto();
        dto.providerCode = message.providerCode;
        dto.messageCode = message.messageCode;
        dto.screenText = message.screenText;
        dto.ticketText = message.ticketText;
        dto.active = message.active;
        return dto;
    }

    private RefPayloads.DocumentTemplateDto toDto(
            com.intermarche.edge.domain.setting.DocumentTemplate template) {
        RefPayloads.DocumentTemplateDto dto = new RefPayloads.DocumentTemplateDto();
        dto.code = template.code;
        dto.label = template.label;
        dto.documentType = name(template.documentType);
        dto.active = template.active;
        dto.priority = template.priority;
        dto.width = template.width;
        dto.copies = template.copies;
        dto.source = template.source;
        dto.title = template.title;
        dto.duplicateTitle = template.duplicateTitle;
        dto.numberPrefix = template.numberPrefix;
        dto.copyTitles = template.copyTitles;
        return dto;
    }

    /**
     * Renders an administered bound for the snapshot, an absent one travelling
     * as null.
     *
     * @param value the bound, or null when unbounded
     * @return the plain figure, or null
     */
    private String plain(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /**
     * Renders an administered enumeration for the snapshot, an absent one
     * travelling as null.
     *
     * @param value the enumeration constant, or null
     * @return the constant name, or null
     */
    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * Maps an administered article barcode range to its snapshot payload.
     *
     * @param range the range entity
     * @return the payload row
     */
    private RefPayloads.ArticleBarcodeRangeDto toDto(
            com.intermarche.edge.domain.barcode.ArticleBarcodeRange range) {
        RefPayloads.ArticleBarcodeRangeDto dto = new RefPayloads.ArticleBarcodeRangeDto();
        dto.code = range.code;
        dto.label = range.label;
        dto.active = range.active;
        dto.priority = range.priority;
        dto.prefix = range.prefix;
        dto.codeLength = range.codeLength;
        dto.codeKind = range.codeKind == null ? null : range.codeKind.name();
        dto.articlePosition = range.articlePosition;
        dto.articleLength = range.articleLength;
        dto.valueSource = range.valueSource == null ? null : range.valueSource.name();
        dto.valuePosition = range.valuePosition;
        dto.valueLength = range.valueLength;
        dto.valueDecimals = range.valueDecimals;
        dto.currency = range.currency == null ? null : range.currency.name();
        dto.checkDigit = range.checkDigit;
        dto.matchPattern = range.matchPattern;
        dto.purpose = range.purpose == null ? null : range.purpose.name();
        return dto;
    }

    private RefPayloads.CouponTypeDto toDto(CouponType type) {
        RefPayloads.CouponTypeDto dto = new RefPayloads.CouponTypeDto();
        dto.code = type.code;
        dto.label = type.label;
        dto.matchPattern = type.matchPattern;
        dto.amountSource = type.amountSource.name();
        dto.amountPattern = type.amountPattern;
        dto.priority = type.priority;
        dto.active = type.active;
        dto.depositLine = type.depositLine;
        dto.prefix = type.prefix;
        dto.codeLength = type.codeLength;
        dto.codeKind = type.codeKind == null ? null : type.codeKind.name();
        dto.manualAmountOnAllNines = type.manualAmountOnAllNines;
        dto.islandCodes = type.islandCodes;
        dto.fields = new ArrayList<>();
        if (type.fields != null) {
            for (CouponField field : type.fields) {
                if (field == null || field.role == null) {
                    continue;
                }
                RefPayloads.CouponFieldDto row = new RefPayloads.CouponFieldDto();
                row.role = field.role.name();
                row.offsetPosition = field.offsetPosition;
                row.fieldLength = field.fieldLength;
                row.kind = field.kind == null ? null : field.kind.name();
                row.decimals = field.decimals;
                row.dateFormat = field.dateFormat == null ? null : field.dateFormat.name();
                row.currency = field.currency == null ? null : field.currency.name();
                dto.fields.add(row);
            }
        }
        dto.controls = new ArrayList<>();
        if (type.controls != null) {
            for (CouponControl control : type.controls) {
                if (control == null || control.kind == null) {
                    continue;
                }
                RefPayloads.CouponControlDto row = new RefPayloads.CouponControlDto();
                row.kind = control.kind.name();
                row.level = control.level == null ? null : control.level.name();
                row.message = control.message;
                dto.controls.add(row);
            }
        }
        return dto;
    }

    /**
     * Renders the administered controls of a range as one canonical string, so
     * that adding, retuning or removing a control changes the fingerprint.
     *
     * @param dto the coupon type payload
     * @return the canonical rendering, empty when the range administers none
     */
    private String couponControls(RefPayloads.CouponTypeDto dto) {
        if (dto.controls == null || dto.controls.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (RefPayloads.CouponControlDto row : dto.controls) {
            parts.add(String.join(":", n(row.kind), n(row.level), n(row.message)));
        }
        parts.sort(null);
        return String.join(",", parts);
    }

    /**
     * Renders the administered positions of a range as one canonical string,
     * so that adding, moving or removing a position changes the fingerprint.
     *
     * @param dto the coupon type payload
     * @return the canonical rendering, empty when the range administers none
     */
    private String couponFields(RefPayloads.CouponTypeDto dto) {
        if (dto.fields == null || dto.fields.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (RefPayloads.CouponFieldDto row : dto.fields) {
            parts.add(String.join(":", n(row.role), String.valueOf(row.offsetPosition),
                    String.valueOf(row.fieldLength), n(row.kind), n(row.decimals),
                    n(row.dateFormat), n(row.currency)));
        }
        parts.sort(null);
        return String.join(",", parts);
    }

    /**
     * Formats a timestamp as ISO-8601, tolerating null.
     *
     * @param dateTime the timestamp, or null
     * @return the ISO string, or null
     */
    private String iso(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
    }
}
