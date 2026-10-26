package com.intermarche.edge.service.sync.store;

import com.intermarche.edge.domain.barcode.CouponControl;
import com.intermarche.edge.domain.barcode.CouponField;
import com.intermarche.edge.domain.barcode.CouponType;
import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.setting.EchelonSetting;
import com.intermarche.edge.domain.setting.TouchGroupSetting;
import com.intermarche.edge.domain.people.EchelonScope;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.people.EmployeeProfile;
import com.intermarche.edge.domain.people.Profile;
import com.intermarche.edge.domain.people.ProfileGrant;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.NodeEchelon;
import com.intermarche.edge.service.NodeEchelonService;
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

    /**
     * The change stamp of the referential (BO-03-12-01), seeded with the boot
     * time so a restarted node never repeats a stamp a register already read.
     */
    private volatile long changeStamp = System.currentTimeMillis();

    /**
     * Returns the change stamp of the referential (BO-03-12-01).
     *
     * @return the stamp, moving on every back-office modification
     */
    public long changeStamp() {
        return changeStamp;
    }

    /**
     * Records a back-office modification (BO-03-12-01): the stamp moves and
     * the cached fingerprints are dropped, so the register that reacts to the
     * stamp is answered with fingerprints computed after the change.
     */
    public synchronized void referentialChanged() {
        LOGGER.info("Entering method referentialChanged");
        changeStamp++;
        fingerprintCache.clear();
        fingerprintCachedAt.clear();
        LOGGER.info("Exiting method referentialChanged");
    }

    /** Resolves the effective SETTINGS values a store publishes to its registers. */
    @Inject
    PosSettingsService posSettingsService;

    /** Resolves the images this store publishes: logo and advantage visuals. */
    @Inject
    com.intermarche.edge.service.StoreImageService storeImageService;

    /** Where this node sits, for the accounts it hands to its registers (BO-01-02-03). */
    @Inject
    NodeEchelonService nodeEchelonService;

    /**
     * The programmed releases (BO-03-12-03, BO-03-12-04): a domain whose
     * back-office change is programmed for a later date answers, until that
     * date, the version the registers already hold.
     */
    @Inject
    ReferentialReleaseService releaseService;

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
        LOGGER.info("Exiting method getFingerprints");
        return getFingerprints(domains, terminalId, null);
    }

    /**
     * Returns the fingerprint of every domain in the given set as seen by one
     * terminal of one point of sale: the central node answers the accounts
     * and assignments of the asking store's point of sale (BO-01-01-03).
     *
     * @param domains the domains to fingerprint
     * @param terminalId the asking register's identifier, or null
     * @param pdv the asking store's point of sale, or null
     * @return an ordered map domain to fingerprint
     */
    public synchronized Map<String, String> getFingerprints(List<String> domains, String terminalId,
                                                            String pdv) {
        LOGGER.info("Entering method getFingerprints with domains: " + domains
                + ", terminalId: " + terminalId + ", pdv: " + pdv);
        Map<String, String> result = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String domain : domains) {
            String key = cacheKey(domain, terminalId, pdv);
            Long cachedAt = fingerprintCachedAt.get(key);
            if (cachedAt == null || now - cachedAt > FINGERPRINT_TTL_MS) {
                fingerprintCache.put(key, computeFingerprint(domain, terminalId, pdv));
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
     * @param pdv the asking store's point of sale, or null
     * @return the cache key, never null
     */
    private String cacheKey(String domain, String terminalId, String pdv) {
        if (isPdvScoped(domain) && pdv != null && !pdv.isBlank()) {
            return domain + "#" + pdv.trim();
        }
        if (!isTerminalScoped(domain) || terminalId == null || terminalId.isBlank()) {
            return domain;
        }
        return domain + "@" + terminalId.trim();
    }

    /**
     * Tells whether a domain's rows depend on which point of sale is asking:
     * the accounts the central node distributes and their assignments, the VAT
     * regimes of the country of the point of sale and the document templates
     * of its echelons (BO-02-05-02, BO-02-05-04).
     *
     * @param domain the referential domain
     * @return true for ACCOUNTS, ACCOUNT_PROFILES, VAT_RATES and DOCUMENT_TEMPLATES
     */
    private boolean isPdvScoped(String domain) {
        return "ACCOUNTS".equals(domain) || "ACCOUNT_PROFILES".equals(domain)
                || "VAT_RATES".equals(domain) || "DOCUMENT_TEMPLATES".equals(domain);
    }

    /**
     * Tells whether a domain's rows depend on which register is asking.
     *
     * <p>SETTINGS carries the result of the echelon resolution, which walks
     * down to the terminal; EMPLOYEES carries only the accounts the register
     * admits, an account attached to types of point of contact or to points
     * of contact signing in only there (BO-01-02-03). A domain added here must also be paged
     * with the terminal, or its fingerprint and its content would disagree.
     *
     * @param domain the referential domain
     * @return true when the domain is resolved per register
     */
    public boolean isTerminalScoped(String domain) {
        return "SETTINGS".equals(domain) || "EMPLOYEES".equals(domain)
                || "STORE_IMAGES".equals(domain);
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
        return getPage(domain, page, size, terminalId, null);
    }

    /**
     * Returns one page of a domain's snapshot for one terminal of one point of
     * sale, in canonical order.
     *
     * @param domain the referential domain
     * @param page the 0-based page index
     * @param size the page size
     * @param terminalId the asking register's identifier, or null
     * @param pdv the asking store's point of sale, or null
     * @return the page payloads, empty past the end
     * @throws IllegalArgumentException on an unknown domain
     */
    @Transactional
    public List<?> getPage(String domain, int page, int size, String terminalId, String pdv) {
        LOGGER.info("Entering method getPage with domain: " + domain + ", page: " + page
                + ", size: " + size + ", terminalId: " + terminalId + ", pdv: " + pdv);
        List<?> held = releaseService == null ? null : releaseService.heldRows(domain, terminalId);
        if (held != null) {
            LOGGER.info("Exiting method getPage");
            return slice(held, page, size);
        }
        LOGGER.info("Exiting method getPage");
        return livePage(domain, page, size, terminalId, pdv);
    }

    /**
     * Returns one page of a domain's snapshot READ FROM THE TABLES, whatever a
     * programmed release holds (BO-03-12-03/04): the version the back office
     * already carries, which the registers receive only from the release date.
     *
     * @param domain the referential domain
     * @param page the 0-based page index
     * @param size the page size
     * @param terminalId the asking register's identifier, or null
     * @param pdv the asking store's point of sale, or null
     * @return the page payloads, empty past the end
     * @throws IllegalArgumentException on an unknown domain
     */
    @Transactional
    public List<?> livePage(String domain, int page, int size, String terminalId, String pdv) {
        LOGGER.info("Entering method livePage with domain: " + domain + ", page: " + page
                + ", size: " + size + ", terminalId: " + terminalId);
        LOGGER.info("Exiting method livePage");
        return switch (domain) {
            case "FAMILIES" -> ProductFamily.<ProductFamily>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "TOUCH_GROUPS" -> TouchGroupSetting.<TouchGroupSetting>find("order by familyCode")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "PRODUCTS" -> Product.<Product>find("order by ean")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "VAT_RATES" -> slice(vatRatesFor(pdv), page, size).stream()
                    .map(this::toDto).toList();
            case "PRICES" -> Price.<Price>find("order by id")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "EMPLOYEES" -> slice(registerEmployees(terminalId), page, size).stream()
                    .map(this::toDto).toList();
            case "PROFILES" -> Profile.<Profile>find("order by name")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "ACCOUNTS" -> slice(accountsFor(pdv), page, size).stream().map(this::toAccountDto).toList();
            case "ACCOUNT_PROFILES" -> slice(bindingsFor(pdv), page, size);
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
            case "DOCUMENT_TEMPLATES" -> slice(templatesFor(pdv), page, size).stream()
                    .map(this::toDto).toList();
            case "PRINTABLE_DOCUMENTS" -> com.intermarche.edge.domain.setting.PrintableDocument
                    .<com.intermarche.edge.domain.setting.PrintableDocument>find("order by code")
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
            case "MEASURE_UNITS" -> com.intermarche.edge.domain.catalog.MeasureUnit
                    .<com.intermarche.edge.domain.catalog.MeasureUnit>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "POS_LISTS" -> com.intermarche.edge.domain.setting.PosList
                    .<com.intermarche.edge.domain.setting.PosList>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "RETURN_TYPES" -> com.intermarche.edge.domain.setting.ReturnTypeDefinition
                    .<com.intermarche.edge.domain.setting.ReturnTypeDefinition>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "PREPAID_CARD_TYPES" -> com.intermarche.edge.domain.payment.PrepaidCardType
                    .<com.intermarche.edge.domain.payment.PrepaidCardType>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "SETTINGS" -> settingsPage(page, size, terminalId);
            case "STORE_IMAGES" -> storeImagesPage(page, size, terminalId);
            case "CASH_MENUS" -> com.intermarche.edge.domain.store.CashMenu
                    .<com.intermarche.edge.domain.store.CashMenu>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "STORE" -> com.intermarche.edge.domain.store.Store
                    .<com.intermarche.edge.domain.store.Store>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
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
     * Builds one page of the STORE_IMAGES snapshot from the images RESOLVED
     * for this store ({@code BO-03-03-15}, {@code BO-10-03-09}): the logo its
     * echelon chain answers and one visual per loyalty advantage. Paged in
     * memory, the resolved set being a handful of rows.
     *
     * <p>Resolved FOR THE ASKING REGISTER (BO-03-09-04): a logo chosen for that
     * lane, its island or its PDV through {@code print.logo-code} replaces the
     * logo of the echelon chain, so each register still receives exactly one.
     *
     * @param page the 0-based page index
     * @param size the page size
     * @param terminalId the asking register's identifier, or null
     * @return the page, empty past the end
     */
    private List<RefPayloads.StoreImageDto> storeImagesPage(int page, int size, String terminalId) {
        String logoCode = posSettingsService == null ? null
                : posSettingsService.administeredValues(terminalId).get("print.logo-code");
        List<RefPayloads.StoreImageDto> all = new ArrayList<>();
        for (com.intermarche.edge.domain.setting.StoreImage image
                : storeImageService.resolvedImages(logoCode)) {
            all.add(toDto(image));
        }
        int from = page * size;
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, Math.min(from + size, all.size()));
    }

    /**
     * Maps a resolved image to its snapshot payload ({@code BO-03-03-15},
     * {@code BO-10-03-09}); the echelon it was posed at is deliberately NOT
     * carried, the register receiving the result of the resolution.
     *
     * @param image the resolved image
     * @return the transport DTO
     */
    private RefPayloads.StoreImageDto toDto(com.intermarche.edge.domain.setting.StoreImage image) {
        RefPayloads.StoreImageDto dto = new RefPayloads.StoreImageDto();
        dto.code = image.code;
        dto.label = image.label;
        dto.usage = image.usage == null ? null : image.usage.name();
        dto.advantageCode = image.advantageCode;
        dto.imageData = image.imageData;
        dto.active = image.active;
        return dto;
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
     * Maps a unit of measure to its snapshot payload (BO-02-03-17).
     *
     * @param unit the unit entity
     * @return the transport DTO
     */
    private RefPayloads.MeasureUnitDto toDto(com.intermarche.edge.domain.catalog.MeasureUnit unit) {
        RefPayloads.MeasureUnitDto dto = new RefPayloads.MeasureUnitDto();
        dto.code = unit.code;
        dto.label = unit.label;
        dto.symbol = unit.symbol;
        dto.decimalAllowed = unit.decimalAllowed;
        dto.active = unit.active;
        return dto;
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
    /**
     * Maps an administered list and its entries to their snapshot payload
     * ({@code BO-04-02-07}, {@code BO-04-03-12}).
     *
     * <p>The entries travel INSIDE the list, in display order, so the register
     * applies a list and its contents in one transaction.
     *
     * @param posList the list
     * @return the transport DTO
     */
    private RefPayloads.PosListDto toDto(com.intermarche.edge.domain.setting.PosList posList) {
        RefPayloads.PosListDto dto = new RefPayloads.PosListDto();
        dto.code = posList.code;
        dto.label = posList.label;
        dto.kind = posList.kind == null ? null : posList.kind.name();
        dto.functions = posList.functions;
        dto.active = posList.active;
        dto.items = new java.util.ArrayList<>();
        for (com.intermarche.edge.domain.setting.PosListItem item : posList.items) {
            RefPayloads.PosListItemDto itemDto = new RefPayloads.PosListItemDto();
            itemDto.code = item.code;
            itemDto.label = item.label;
            itemDto.displayOrder = item.displayOrder;
            itemDto.severity = item.severity;
            itemDto.numericValue = item.numericValue == null
                    ? null : item.numericValue.toPlainString();
            itemDto.options = item.options;
            itemDto.active = item.active;
            dto.items.add(itemDto);
        }
        return dto;
    }

    /**
     * Maps an administered return type to its snapshot payload
     * ({@code BO-03-13-01} to {@code -04}, {@code -11}, {@code -12}).
     *
     * @param type the return type
     * @return the transport DTO
     */
    private RefPayloads.ReturnTypeDto toDto(com.intermarche.edge.domain.setting.ReturnTypeDefinition type) {
        RefPayloads.ReturnTypeDto dto = new RefPayloads.ReturnTypeDto();
        dto.code = type.code;
        dto.label = type.label;
        dto.ticketMode = type.ticketMode;
        dto.endorsementExempt = type.endorsementExempt;
        dto.originOptional = type.originOptional;
        dto.priceConfirmed = type.priceConfirmed;
        dto.depositReturn = type.depositReturn;
        dto.rangeCode = type.rangeCode;
        dto.displayOrder = type.displayOrder;
        dto.active = type.active;
        return dto;
    }

    /**
     * Maps an administered type of prepaid card to its snapshot payload
     * ({@code BO-03-02-37}).
     *
     * @param type the prepaid card type
     * @return the transport DTO
     */
    private RefPayloads.PrepaidCardTypeDto toDto(com.intermarche.edge.domain.payment.PrepaidCardType type) {
        RefPayloads.PrepaidCardTypeDto dto = new RefPayloads.PrepaidCardTypeDto();
        dto.code = type.code;
        dto.label = type.label;
        dto.protocol = type.protocol;
        dto.provider = type.provider;
        dto.ranges = type.ranges;
        dto.paymentAllowed = type.paymentAllowed;
        dto.balanceAllowed = type.balanceAllowed;
        dto.activationAllowed = type.activationAllowed;
        dto.displayOrder = type.displayOrder;
        dto.active = type.active;
        return dto;
    }

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
     * Maps an echelon parameter to its snapshot payload — its current value,
     * its pending value and the effect date of the latter as an ISO date string.
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
        dto.pendingValue = setting.pendingValue;
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
        dto.baseFingerprint = feed.baseFingerprint;
        dto.resultFingerprint = feed.resultFingerprint;
        return dto;
    }

    /**
     * Computes the SHA-256 fingerprint of a domain over its canonical row,
     * for the register that asked
     * stream.
     *
     * @param domain the referential domain
     * @param terminalId the asking register's identifier, or null
     * @param pdv the asking store's point of sale, or null
     * @return the fingerprint, hex encoded
     */
    private String computeFingerprint(String domain, String terminalId, String pdv) {
        return digest(domain, terminalId, pdv, false);
    }

    /**
     * Returns every row of a domain READ FROM THE TABLES for one register,
     * whatever a programmed release holds (BO-03-12-03/04): what a release
     * captures before a programmed change and compares after it.
     *
     * @param domain the referential domain
     * @param terminalId the register the rows are resolved for, or null
     * @return the rows in canonical order, never null
     */
    @Transactional
    public List<?> liveRows(String domain, String terminalId) {
        LOGGER.info("Entering method liveRows with domain: " + domain + ", terminalId: " + terminalId);
        List<Object> all = new ArrayList<>();
        int page = 0;
        List<?> rows;
        do {
            rows = livePage(domain, page++, 1000, terminalId, null);
            all.addAll(rows);
        } while (!rows.isEmpty());
        LOGGER.info("Exiting method liveRows");
        return all;
    }

    /**
     * Returns the fingerprint of a domain READ FROM THE TABLES for one
     * register, uncached and whatever a programmed release holds
     * (BO-03-12-03/04).
     *
     * @param domain the referential domain
     * @param terminalId the register the rows are resolved for, or null
     * @return the fingerprint
     */
    public String liveFingerprint(String domain, String terminalId) {
        LOGGER.info("Entering method liveFingerprint with domain: " + domain + ", terminalId: " + terminalId);
        LOGGER.info("Exiting method liveFingerprint");
        return digest(domain, terminalId, null, true);
    }

    /**
     * Returns the fingerprint a register is served for a domain, uncached
     * (BO-02-03-33): what a feed file is stamped with before and after its
     * integration.
     *
     * @param domain the referential domain
     * @return the fingerprint
     */
    public String publishedFingerprint(String domain) {
        LOGGER.info("Entering method publishedFingerprint with domain: " + domain);
        LOGGER.info("Exiting method publishedFingerprint");
        return computeFingerprint(domain, null, null);
    }

    /**
     * Folds the fingerprint of rows already in hand, with the arithmetic of
     * the published fingerprints (BO-03-12-03/04).
     *
     * @param rows the payload rows, in canonical order
     * @return the fingerprint
     */
    public String fingerprintOf(List<?> rows) {
        LOGGER.info("Entering method fingerprintOf");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object row : rows) {
                digest.update(canonical(row).getBytes(StandardCharsets.UTF_8));
            }
            LOGGER.info("Exiting method fingerprintOf");
            return hex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Empreinte incalculable", e);
        }
    }

    /**
     * Encodes a digest in lower-case hexadecimal.
     *
     * @param bytes the digest
     * @return the hexadecimal text
     */
    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Folds the fingerprint of a domain over its exposed or its live rows.
     *
     * @param domain the referential domain
     * @param terminalId the asking register's identifier, or null
     * @param pdv the asking store's point of sale, or null
     * @param live true to read the tables, false to read what the registers are exposed to
     * @return the fingerprint
     */
    private String digest(String domain, String terminalId, String pdv, boolean live) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int page = 0;
            List<?> rows;
            do {
                rows = live ? livePage(domain, page++, 1000, terminalId, pdv)
                        : getPage(domain, page++, 1000, terminalId, pdv);
                for (Object row : rows) {
                    digest.update(canonical(row).getBytes(StandardCharsets.UTF_8));
                }
            } while (!rows.isEmpty());
            return hex(digest.digest());
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
                    String.valueOf(t.displayOrder), String.valueOf(t.salesVolume),
                    n(t.islandCodes), n(t.terminalIds), n(t.tpvTypes), n(t.labels));
        }
        if (row instanceof RefPayloads.ProductDto p) {
            return String.join("|", n(p.ean), n(p.plu), n(p.name), n(p.description), n(p.icon),
                    n(p.imageData), n(p.brand), n(p.referenceWeight), n(p.referenceVolume),
                    n(p.productType), n(p.unitName), String.valueOf(p.active),
                    String.valueOf(p.forbiddenToSale), n(p.ageRestriction), n(p.checkoutLabel),
                    n(p.internalCode), String.valueOf(p.variableWeight), n(p.infoMessage),
                    attributes(p.attributes), String.valueOf(p.salesVolume),
                    String.join(",", p.saleCodes));
        }
        if (row instanceof RefPayloads.VatRateDto v) {
            return String.join("|", n(v.number), n(v.rate), n(v.label), n(v.country),
                    n(v.legalMention), String.valueOf(v.exempt), n(v.exemptionCode));
        }
        if (row instanceof RefPayloads.PriceDto p) {
            return String.join("|", n(p.productEan), n(p.priceExcludingTax), n(p.priceIncludingTax),
                    n(p.vatNumber), n(p.priority), n(p.startDateTime), n(p.endDateTime));
        }
        if (row instanceof RefPayloads.EmployeeDto e) {
            return String.join("|", n(e.loginName), n(e.firstName), n(e.lastName), n(e.password),
                    n(e.email), n(e.role), n(e.badgeId), n(e.theme), n(e.language),
                    n(e.passwordChangedAt), String.valueOf(e.active), n(e.supervisorCard), n(e.dallasKey),
                    n(e.profiles));
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
        if (row instanceof RefPayloads.ProfileDto p) {
            StringBuilder grants = new StringBuilder();
            for (RefPayloads.ProfileGrantDto g : p.grants) {
                grants.append(String.join(":", n(g.featureKey), n(g.option),
                        String.valueOf(g.requiresValidation), n(g.validatingProfileName))).append(',');
            }
            return String.join("|", n(p.name), n(p.description), String.valueOf(p.hierarchyLevel),
                    grants.toString());
        }
        if (row instanceof RefPayloads.AccountDto a) {
            return String.join("|", n(a.loginName), n(a.firstName), n(a.lastName), n(a.email),
                    n(a.password), n(a.backOfficePassword), n(a.role), n(a.badgeId), String.valueOf(a.active),
                    n(a.phone), n(a.startDate), n(a.department), String.valueOf(a.hierarchyLevel),
                    n(a.language), n(a.echelonScopes), n(a.authMode), String.valueOf(a.mustChangePassword),
                    n(a.passwordChangedAt), String.valueOf(a.supervisorCard), n(a.dallasKey));
        }
        if (row instanceof RefPayloads.AccountProfileDto b) {
            return String.join("|", n(b.loginName), n(b.profileName), n(b.echelonScope));
        }
        if (row instanceof RefPayloads.EchelonSettingDto s) {
            return String.join("|", n(s.level), n(s.echelonCode), n(s.settingKey), n(s.settingValue),
                    n(s.pendingValue), n(s.effectiveDate));
        }
        if (row instanceof RefPayloads.EngineFeedDto f) {
            // The version IS the SHA-256 of the content: hashing code and
            // version covers the whole parcel without streaming its body
            // through the fingerprint a second time.
            return String.join("|", n(f.code), n(f.version), n(f.baseFingerprint), n(f.resultFingerprint));
        }
        if (row instanceof RefPayloads.CouponTypeDto c) {
            return String.join("|", n(c.code), n(c.label), n(c.matchPattern), n(c.amountSource),
                    n(c.amountPattern), String.valueOf(c.priority), String.valueOf(c.active),
                    String.valueOf(c.depositLine), n(c.prefix), n(c.codeLength), n(c.codeKind),
                    String.valueOf(c.manualAmountOnAllNines), n(c.islandCodes),
                    n(c.tenderCode), n(c.issuedKind), n(c.cardProtocol),
                    couponFields(c), couponControls(c));
        }
        if (row instanceof RefPayloads.ArticleBarcodeRangeDto r) {
            return String.join("|", n(r.code), n(r.label), String.valueOf(r.active),
                    String.valueOf(r.priority), n(r.prefix), String.valueOf(r.codeLength),
                    n(r.codeKind), String.valueOf(r.articlePosition),
                    String.valueOf(r.articleLength), n(r.valueSource),
                    String.valueOf(r.valuePosition), String.valueOf(r.valueLength),
                    String.valueOf(r.valueDecimals), n(r.currency),
                    String.valueOf(r.checkDigit), n(r.matchPattern), n(r.purpose),
                    n(r.promotionCode), n(r.lookupUrl), n(r.functionCode),
                    n(r.promotionType), n(r.lookupField));
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
                    n(c.scaleProtocol), n(c.scaleAddress), String.valueOf(c.scalePort),
                    n(c.coinMachineProtocol), n(c.coinMachineAddress),
                    String.valueOf(c.coinMachinePort),
                    n(c.menuTemplateCode));
        }
        if (row instanceof RefPayloads.TenderDefinitionDto t) {
            return String.join("|", n(t.code), n(t.functionalId), n(t.label),
                    n(t.groupCode), n(t.groupLabel),
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
                    String.valueOf(t.printForced), n(t.vicProductCode),
                    String.valueOf(t.selfServiceAllowed),
                    String.valueOf(t.selfServiceSupervision), n(t.nature),
                    n(t.supervisionIslands), n(t.moneticCodes), String.valueOf(t.coinMachine),
                    String.valueOf(t.micrReading), String.valueOf(t.chequeAuthorization),
                    String.valueOf(t.accountNumberEntry), String.valueOf(t.chequeAutoPrint));
        }
        if (row instanceof RefPayloads.PrintableDocumentDto d) {
            return String.join("|", n(d.code), n(d.label), n(d.kind), n(d.output),
                    n(d.templateCode), String.valueOf(d.active), String.valueOf(d.priority));
        }
        if (row instanceof RefPayloads.ProviderMessageDto m) {
            return String.join("|", n(m.providerCode), n(m.messageCode), n(m.screenText),
                    n(m.ticketText), String.valueOf(m.active));
        }
        if (row instanceof RefPayloads.DocumentTemplateDto t) {
            return String.join("|", n(t.code), n(t.label), n(t.documentType),
                    String.valueOf(t.active), String.valueOf(t.priority),
                    String.valueOf(t.width), String.valueOf(t.copies), n(t.source),
                    n(t.title), n(t.duplicateTitle), n(t.numberPrefix), n(t.copyTitles),
                    n(t.echelonScope));
        }
        if (row instanceof RefPayloads.MeasureUnitDto u) {
            return String.join("|", n(u.code), n(u.label), n(u.symbol), String.valueOf(u.decimalAllowed),
                    String.valueOf(u.active));
        }
        if (row instanceof RefPayloads.CurrencyDto c) {
            return String.join("|", n(c.code), n(c.label), n(c.symbol), n(c.euroPerUnit),
                    String.valueOf(c.active), String.valueOf(c.displayOrder),
                    n(c.changeTenderCode));
        }
        if (row instanceof RefPayloads.StoreImageDto i) {
            return String.join("|", n(i.code), n(i.label), n(i.usage), n(i.advantageCode),
                    n(i.imageData), String.valueOf(i.active));
        }
        if (row instanceof RefPayloads.PosListDto l) {
            return String.join("|", n(l.code), n(l.label), n(l.kind), n(l.functions),
                    String.valueOf(l.active), listItems(l.items));
        }
        if (row instanceof RefPayloads.ReturnTypeDto r) {
            return String.join("|", n(r.code), n(r.label), String.valueOf(r.ticketMode),
                    String.valueOf(r.endorsementExempt), String.valueOf(r.originOptional),
                    String.valueOf(r.priceConfirmed), String.valueOf(r.depositReturn),
                    n(r.rangeCode), String.valueOf(r.displayOrder), String.valueOf(r.active));
        }
        if (row instanceof RefPayloads.PrepaidCardTypeDto c) {
            return String.join("|", n(c.code), n(c.label), n(c.protocol), n(c.provider),
                    n(c.ranges), String.valueOf(c.paymentAllowed), String.valueOf(c.balanceAllowed),
                    String.valueOf(c.activationAllowed), String.valueOf(c.displayOrder),
                    String.valueOf(c.active));
        }
        if (row instanceof RefPayloads.PostalCodeDto p) {
            return String.join("|", n(p.code), n(p.place), n(p.country));
        }
        if (row instanceof RefPayloads.CashMenuDto m) {
            return String.join("|", n(m.code), n(m.label), String.valueOf(m.active), n(m.menuKeys),
                    n(m.hiddenFunctions), String.valueOf(m.keyColumns), n(m.cashierScreen),
                    n(m.customerScreen), n(m.ticketModel));
        }
        if (row instanceof RefPayloads.StoreDto s) {
            return String.join("|", n(s.code), n(s.name), n(s.streetLine1), n(s.streetLine2),
                    n(s.postalCode), n(s.city), n(s.country), n(s.vatNumber), n(s.siret), n(s.phone),
                    n(s.bankAccountNumber), n(s.legalName), n(s.rcs), n(s.shareCapital), n(s.fax));
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
     * Canonical rendering of the entries of an administered list: joined in
     * the order they travel, each entry field-separated, so the fingerprint
     * moves when an entry does and not otherwise ({@code BO-04-02-07}).
     *
     * @param items the entries, possibly null or empty
     * @return the canonical string, empty when there is no entry
     */
    private String listItems(java.util.List<RefPayloads.PosListItemDto> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder canonical = new StringBuilder();
        for (RefPayloads.PosListItemDto item : items) {
            if (canonical.length() > 0) {
                canonical.append(',');
            }
            canonical.append(n(item.code)).append('~').append(n(item.label))
                    .append('~').append(item.displayOrder)
                    .append('~').append(item.severity)
                    .append('~').append(n(item.numericValue))
                    .append('~').append(n(item.options))
                    .append('~').append(item.active);
        }
        return canonical.toString();
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
        dto.islandCodes = setting.islandCodes;
        dto.terminalIds = setting.terminalIds;
        dto.tpvTypes = setting.tpvTypes;
        dto.labels = setting.labels;
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
        dto.salesVolume = product.salesVolume;
        dto.saleCodes = product.saleCodes == null ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(new java.util.TreeSet<>(product.saleCodes));
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
        dto.country = rate.country;
        dto.legalMention = rate.legalMention;
        dto.exempt = rate.exempt;
        dto.exemptionCode = rate.exemptionCode;
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
     * Lists the accounts a register admits (BO-01-02-03): those this node's
     * point of sale admits and, when the register names itself, whose
     * register-level scopes name it or its type.
     *
     * @param terminalId the asking register's identifier, or null
     * @return the accounts, by login
     */
    List<Employee> registerEmployees(String terminalId) {
        NodeEchelon node = nodeEchelonService == null
                ? new NodeEchelon(null, null, null) : nodeEchelonService.current();
        CheckoutPoint point = terminalId == null || terminalId.isBlank()
                ? null : CheckoutPoint.findByTerminal(terminalId.trim());
        String tpvType = point == null || point.tpvType == null ? null : point.tpvType.name();
        List<Employee> admitted = new ArrayList<>();
        for (Employee employee : Employee.<Employee>find("order by loginName").list()) {
            List<String> scopes = EchelonScope.parseList(employee.echelonScopes);
            boolean admits = terminalId == null || terminalId.isBlank()
                    ? EchelonScope.admitsNode(scopes, node)
                    : EchelonScope.admitsRegister(scopes, node, terminalId.trim(), tpvType);
            if (admits) {
                admitted.add(employee);
            }
        }
        return admitted;
    }

    /**
     * Places the asking point of sale in the organisation: the one the asking
     * store named, else this node's own — a register asking its store names
     * none, and the store answers for itself.
     *
     * @param pdv the asking store's point of sale, or null
     * @return its place, every part null when no point of sale is known
     */
    NodeEchelon askingNode(String pdv) {
        String number = pdv != null && !pdv.isBlank() ? pdv
                : nodeEchelonService == null ? null : nodeEchelonService.currentPdv();
        return NodeEchelon.of(number);
    }

    /**
     * Lists the VAT regimes one point of sale receives (BO-02-02-01,
     * BO-02-05-04): those of the country its enseigne belongs to, and those
     * that name no country.
     *
     * <p>The referential holds the regimes of every country of the group — the
     * French, the Belgian and the Portuguese ones side by side — and a point of
     * sale is taxed by its own: a Portuguese till handed the French 5,5 % would
     * name it on a receipt the moment two regimes share a rate. A point of sale
     * whose country the tree does not know receives them all, as before.
     *
     * @param pdv the asking store's point of sale, or null
     * @return the regimes, in number order
     */
    public List<VatRate> vatRatesFor(String pdv) {
        String country = askingNode(pdv).country;
        List<VatRate> regimes = new ArrayList<>();
        for (VatRate regime : VatRate.listAllOrdered()) {
            if (country == null || regime.country == null
                    || regime.country.equalsIgnoreCase(country)) {
                regimes.add(regime);
            }
        }
        return regimes;
    }

    /**
     * Lists the document templates one point of sale receives (BO-02-05-02,
     * BO-02-05-04): for each document, the templates posed at the MOST SPECIFIC
     * echelon that covers it — its own, else its enseigne's, else its
     * country's, else the ones posed for everyone.
     *
     * <p>That is the inheritance of a document model: a Portuguese layout posed
     * on the country reaches every Portuguese point of sale, an enseigne that
     * lays its own receipt out overrides it for its points of sale, and a point
     * of sale moved to another enseigne receives the new enseigne's at its next
     * pull. A template posed on another branch of the tree never reaches it.
     *
     * @param pdv the asking store's point of sale, or null
     * @return the templates, in code order
     */
    public List<com.intermarche.edge.domain.setting.DocumentTemplate> templatesFor(String pdv) {
        NodeEchelon node = askingNode(pdv);
        List<com.intermarche.edge.domain.setting.DocumentTemplate> covering = new ArrayList<>();
        Map<Object, Integer> deepest = new HashMap<>();
        for (com.intermarche.edge.domain.setting.DocumentTemplate template
                : com.intermarche.edge.domain.setting.DocumentTemplate
                        .<com.intermarche.edge.domain.setting.DocumentTemplate>list("order by code")) {
            String scope = template.echelonScope;
            if (scope != null && !scope.isBlank() && !EchelonScope.coversNode(scope, node)) {
                continue;
            }
            covering.add(template);
            deepest.merge(template.documentType, depth(scope), Math::max);
        }
        List<com.intermarche.edge.domain.setting.DocumentTemplate> kept = new ArrayList<>();
        for (com.intermarche.edge.domain.setting.DocumentTemplate template : covering) {
            if (depth(template.echelonScope) == deepest.get(template.documentType)) {
                kept.add(template);
            }
        }
        return kept;
    }

    /**
     * The depth of an echelon scope in the organisation tree: nothing or the
     * whole organisation 0, a country 1, an enseigne 2, a point of sale 3.
     *
     * @param scope the scope, possibly null
     * @return its depth
     */
    static int depth(String scope) {
        EchelonScope.Kind kind = EchelonScope.kindOf(scope);
        if (kind == EchelonScope.Kind.COUNTRY) {
            return 1;
        }
        if (kind == EchelonScope.Kind.ENSEIGNE) {
            return 2;
        }
        return kind == EchelonScope.Kind.PDV ? 3 : 0;
    }

    /**
     * Lists the accounts the central node hands to one point of sale
     * (BO-01-01-03): those whose node-level scopes cover it.
     *
     * @param pdv the asking store's point of sale, or null
     * @return the accounts, by login
     */
    List<Employee> accountsFor(String pdv) {
        NodeEchelon node = NodeEchelon.of(pdv);
        List<Employee> handed = new ArrayList<>();
        for (Employee employee : Employee.<Employee>find("order by loginName").list()) {
            if (EchelonScope.admitsNode(EchelonScope.parseList(employee.echelonScopes), node)) {
                handed.add(employee);
            }
        }
        return handed;
    }

    /**
     * Lists the profile assignments of the accounts handed to one point of
     * sale (BO-01-03-04).
     *
     * @param pdv the asking store's point of sale, or null
     * @return the assignments, account by account
     */
    public List<RefPayloads.AccountProfileDto> bindingsFor(String pdv) {
        Map<Long, String> profileNames = new HashMap<>();
        for (Profile profile : Profile.<Profile>listAll()) {
            profileNames.put(profile.id, profile.name);
        }
        List<RefPayloads.AccountProfileDto> bindings = new ArrayList<>();
        for (Employee employee : accountsFor(pdv)) {
            for (EmployeeProfile binding : EmployeeProfile.forEmployee(employee.id)) {
                String profileName = profileNames.get(binding.profileId);
                if (profileName == null) {
                    continue;
                }
                RefPayloads.AccountProfileDto dto = new RefPayloads.AccountProfileDto();
                dto.loginName = employee.loginName;
                dto.profileName = profileName;
                dto.echelonScope = binding.echelonScope;
                bindings.add(dto);
            }
        }
        return bindings;
    }

    /**
     * Cuts one page out of a list already in canonical order.
     *
     * @param all the whole list
     * @param page the 0-based page index
     * @param size the page size
     * @param <T> the row type
     * @return the page, empty past the end
     */
    static <T> List<T> slice(List<T> all, int page, int size) {
        int from = page * size;
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, Math.min(from + size, all.size()));
    }

    /**
     * Maps a profile to its distribution payload, the validating profile by name.
     *
     * @param profile the profile
     * @return the payload
     */
    private RefPayloads.ProfileDto toDto(Profile profile) {
        RefPayloads.ProfileDto dto = new RefPayloads.ProfileDto();
        dto.name = profile.name;
        dto.description = profile.description;
        dto.hierarchyLevel = profile.hierarchyLevel;
        for (ProfileGrant grant : profile.grants) {
            RefPayloads.ProfileGrantDto g = new RefPayloads.ProfileGrantDto();
            g.featureKey = grant.featureKey;
            g.option = grant.option == null ? null : grant.option.name();
            g.requiresValidation = grant.requiresValidation;
            Profile validator = grant.validatingProfileId == null
                    ? null : Profile.<Profile>findById(grant.validatingProfileId);
            g.validatingProfileName = validator == null ? null : validator.name;
            dto.grants.add(g);
        }
        dto.grants.sort(java.util.Comparator.comparing((RefPayloads.ProfileGrantDto g) -> n(g.featureKey))
                .thenComparing(g -> n(g.option)));
        return dto;
    }

    /**
     * Maps an account to its distribution payload, both secret hashes included.
     *
     * @param employee the account
     * @return the payload
     */
    private RefPayloads.AccountDto toAccountDto(Employee employee) {
        RefPayloads.AccountDto dto = new RefPayloads.AccountDto();
        dto.loginName = employee.loginName;
        dto.firstName = employee.firstName;
        dto.lastName = employee.lastName;
        dto.email = employee.email;
        dto.password = employee.password;
        dto.backOfficePassword = employee.backOfficePassword;
        dto.role = employee.role == null ? null : employee.role.name();
        dto.badgeId = employee.badgeId;
        dto.active = employee.active;
        dto.phone = employee.phone;
        dto.startDate = employee.startDate == null ? null : employee.startDate.toString();
        dto.department = employee.department;
        dto.hierarchyLevel = employee.hierarchyLevel;
        dto.language = employee.language;
        dto.echelonScopes = employee.echelonScopes;
        dto.authMode = employee.authMode == null ? null : employee.authMode.name();
        dto.mustChangePassword = employee.mustChangePassword;
        dto.passwordChangedAt = employee.passwordChangedAt == null ? null : employee.passwordChangedAt.toString();
        dto.supervisorCard = employee.supervisorCard;
        dto.dallasKey = employee.dallasKey;
        return dto;
    }

    /**
     * The names of the profiles an employee is bound to, joined by semicolons
     * (BO-04-02-01): what a register endorses by besides the role.
     *
     * @param employee the employee entity
     * @return the names, or null when the holder is bound to none
     */
    String profileNames(Employee employee) {
        if (employee.id == null) {
            return null;
        }
        List<EmployeeProfile> bindings = EmployeeProfile.list("employeeId", employee.id);
        if (bindings == null) {
            return null;
        }
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (EmployeeProfile binding : bindings) {
            Profile profile = Profile.findById(binding.profileId);
            if (profile != null && profile.name != null) {
                names.add(profile.name);
            }
        }
        return names.isEmpty() ? null : String.join(";", names);
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
        // BO-03-06-51: the supervisor card the back office printed for the holder.
        dto.supervisorCard = employee.supervisorCard;
        // BO-04-02-01: the profiles a register endorses by.
        dto.profiles = profileNames(employee);
        dto.theme = employee.theme;
        // BO-01-01-04 / BO-01-02-12: the two account columns a REGISTER needs —
        // the operator's language, and the age of their secret. The telephone,
        // the start date, the department and the level stay on the node: a
        // register has no use for them, and a payload carries what it serves.
        dto.language = employee.language;
        dto.passwordChangedAt = employee.passwordChangedAt == null
                ? null : employee.passwordChangedAt.toString();
        // BO-01-02-07: the key that opens the register without a PIN.
        dto.dallasKey = employee.dallasKey;
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
        dto.scaleProtocol = point.scaleProtocol == null ? null : point.scaleProtocol.name();
        dto.scaleAddress = point.scaleAddress;
        dto.scalePort = point.scalePort;
        dto.coinMachineProtocol = point.coinMachineProtocol == null
                ? null : point.coinMachineProtocol.name();
        dto.coinMachineAddress = point.coinMachineAddress;
        dto.coinMachinePort = point.coinMachinePort;
        dto.menuTemplateCode = point.menuTemplateCode;
        return dto;
    }

    /**
     * Maps a cash-menu model to its snapshot row (BO-03-10-16/17).
     *
     * @param menu the stored model
     * @return the snapshot row
     */
    private RefPayloads.CashMenuDto toDto(com.intermarche.edge.domain.store.CashMenu menu) {
        RefPayloads.CashMenuDto dto = new RefPayloads.CashMenuDto();
        dto.code = menu.code;
        dto.label = menu.label;
        dto.active = menu.active;
        dto.menuKeys = menu.menuKeys;
        dto.hiddenFunctions = menu.hiddenFunctions;
        dto.keyColumns = menu.keyColumns;
        dto.cashierScreen = menu.cashierScreen;
        dto.customerScreen = menu.customerScreen;
        dto.ticketModel = menu.ticketModel;
        return dto;
    }

    /**
     * Maps the node's store record to its snapshot row (BO-03-10-19/20,
     * BO-03-12-01); the node-local theme is not carried.
     *
     * @param store the stored record
     * @return the snapshot row
     */
    private RefPayloads.StoreDto toDto(com.intermarche.edge.domain.store.Store store) {
        RefPayloads.StoreDto dto = new RefPayloads.StoreDto();
        dto.code = store.code;
        dto.name = store.name;
        com.intermarche.edge.domain.store.Address address = store.address;
        if (address != null) {
            dto.streetLine1 = address.streetLine1;
            dto.streetLine2 = address.streetLine2;
            dto.postalCode = address.postalCode;
            dto.city = address.city;
            dto.country = address.country;
        }
        dto.vatNumber = store.vatNumber;
        dto.siret = store.siret;
        dto.phone = store.phone;
        dto.bankAccountNumber = store.bankAccountNumber;
        dto.legalName = store.legalName;
        dto.rcs = store.rcs;
        dto.shareCapital = store.shareCapital == null ? null : store.shareCapital.toPlainString();
        dto.fax = store.fax;
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
        dto.groupCode = tender.groupCode;
        dto.groupLabel = tender.groupLabel;
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
        dto.vicProductCode = tender.vicProductCode;
        dto.selfServiceAllowed = tender.selfServiceAllowed;
        dto.selfServiceSupervision = tender.selfServiceSupervision;
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
        dto.nature = tender.nature;
        dto.supervisionIslands = tender.supervisionIslands;
        dto.moneticCodes = tender.moneticCodes;
        dto.coinMachine = tender.coinMachine;
        dto.micrReading = tender.micrReading;
        dto.chequeAuthorization = tender.chequeAuthorization;
        dto.accountNumberEntry = tender.accountNumberEntry;
        dto.chequeAutoPrint = tender.chequeAutoPrint;
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
     * Renders one printable document for the snapshot (BO-03-04-06, -09, -10
     * and -14).
     *
     * @param document the administered document
     * @return its travelling form
     */
    private RefPayloads.PrintableDocumentDto toDto(
            com.intermarche.edge.domain.setting.PrintableDocument document) {
        RefPayloads.PrintableDocumentDto dto = new RefPayloads.PrintableDocumentDto();
        dto.code = document.code;
        dto.label = document.label;
        dto.kind = name(document.kind);
        dto.output = document.output;
        dto.templateCode = document.templateCode;
        dto.active = document.active;
        dto.priority = document.priority;
        return dto;
    }

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
        dto.echelonScope = template.echelonScope;
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
        dto.promotionCode = range.promotionCode;
        dto.lookupUrl = range.lookupUrl;
        dto.functionCode = range.functionCode;
        dto.promotionType = range.promotionType;
        dto.lookupField = range.lookupField;
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
        dto.tenderCode = type.tenderCode;
        dto.issuedKind = type.issuedKind;
        dto.cardProtocol = type.cardProtocol;
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
