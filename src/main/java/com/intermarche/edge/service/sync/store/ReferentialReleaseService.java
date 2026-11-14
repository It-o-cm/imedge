package com.intermarche.edge.service.sync.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.setting.EchelonLevel;
import com.intermarche.edge.domain.setting.EchelonSetting;
import com.intermarche.edge.domain.setting.PosSetting;
import com.intermarche.edge.domain.setting.StoreImage;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.sync.ReferentialRelease;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.sync.RefApplyService;
import com.intermarche.edge.service.sync.RefPayloads;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The PROGRAMMED DEPLOYMENT of the referential (BO-03-12-03, BO-03-12-04): any
 * back-office change of a distributed domain may be given a date of effect,
 * immediate by default, and reaches the registers only from that date.
 *
 * <p>NO PARALLEL PATH. The registers keep pulling what they always pulled —
 * {@code /api/referential/stamp}, {@code /versions}, the paged snapshots —
 * from {@link RefExportService}; this service only tells the exporter, for a
 * domain whose change is programmed, which rows to answer until the date: the
 * version the registers already hold, captured just before the change. The
 * fingerprint is folded over those rows by the same arithmetic, so it does not
 * move and no register pulls; the stamp is not moved by a programmed change.
 * When the date comes, the release is closed, the stamp moves, and the next
 * look of each register finds the new fingerprint and pulls the pending
 * version from the tables, where the back office kept it.
 *
 * <p>Two ways of programming:
 * <ul>
 *   <li>PARTIAL (BO-03-12-03): an administrator ARMS a date for his own next
 *       changes; every back-office post he makes until he disarms is captured
 *       before and compared after, and each domain it changed is held until
 *       the date — articles, prices, groups, VAT, tenders, templates, lists,
 *       users, islands, checkout points, images, parameters, whatever the
 *       screen;</li>
 *   <li>COMPLETE (BO-03-12-04): every distributed domain is held at once
 *       until the date, so everything the back office changes in between,
 *       whoever changes it, reaches the registers together.</li>
 * </ul>
 * A held domain holds every further change of it, programmed or not: the
 * tables carry one pending version. A pending release can be brought forward,
 * postponed, or CANCELLED — the held version is then put back into the tables
 * by the very apply code a register runs to install a version.
 */
@ApplicationScoped
public class ReferentialReleaseService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReferentialReleaseService.class);

    /** The scope of the rows every register receives, or of a register the capture did not know. */
    static final String ALL_SCOPE = "";

    /** The origin shown for a complete deployment. */
    static final String COMPLETE_ORIGIN = "Déploiement complet";

    /** How often the store looks for the releases whose date has come, in seconds. */
    static final long LOOP_SECONDS = 15;

    /** How many closed releases the screen shows. */
    static final int HISTORY_SIZE = 20;

    /** The minute format of the screen. */
    static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** The payload type of every distributed domain, for reading a held version back. */
    static final Map<String, Class<?>> DTO_TYPES = Map.ofEntries(
            Map.entry("ENGINE_FEEDS", RefPayloads.EngineFeedDto.class),
            Map.entry("MEASURE_UNITS", RefPayloads.MeasureUnitDto.class),
            Map.entry("PRODUCTS", RefPayloads.ProductDto.class),
            Map.entry("FAMILIES", RefPayloads.FamilyDto.class),
            Map.entry("TOUCH_GROUPS", RefPayloads.TouchGroupDto.class),
            Map.entry("VAT_RATES", RefPayloads.VatRateDto.class),
            Map.entry("PRICES", RefPayloads.PriceDto.class),
            Map.entry("EMPLOYEES", RefPayloads.EmployeeDto.class),
            Map.entry("COUPON_TYPES", RefPayloads.CouponTypeDto.class),
            Map.entry("ARTICLE_RANGES", RefPayloads.ArticleBarcodeRangeDto.class),
            Map.entry("ISLANDS", RefPayloads.CheckoutIslandDto.class),
            Map.entry("CHECKOUT_POINTS", RefPayloads.CheckoutPointDto.class),
            Map.entry("TENDERS", RefPayloads.TenderDefinitionDto.class),
            Map.entry("DOCUMENT_TEMPLATES", RefPayloads.DocumentTemplateDto.class),
            Map.entry("PRINTABLE_DOCUMENTS", RefPayloads.PrintableDocumentDto.class),
            Map.entry("PROVIDER_MESSAGES", RefPayloads.ProviderMessageDto.class),
            Map.entry("SETTINGS", RefPayloads.SettingDto.class),
            Map.entry("CASH_MENUS", RefPayloads.CashMenuDto.class),
            Map.entry("STORE", RefPayloads.StoreDto.class),
            Map.entry("CUSTOMERS", RefPayloads.CustomerDto.class),
            Map.entry("CURRENCIES", RefPayloads.CurrencyDto.class),
            Map.entry("POSTAL_CODES", RefPayloads.PostalCodeDto.class),
            Map.entry("POS_LISTS", RefPayloads.PosListDto.class),
            Map.entry("RETURN_TYPES", RefPayloads.ReturnTypeDto.class),
            Map.entry("PREPAID_CARD_TYPES", RefPayloads.PrepaidCardTypeDto.class),
            Map.entry("STORE_IMAGES", RefPayloads.StoreImageDto.class));

    /** The role of this node: only a store node holds versions for its registers. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** The exporter whose answers a release holds. */
    @Inject
    RefExportService refExportService;

    /** The apply code a register runs, which puts a cancelled release's version back. */
    @Inject
    RefApplyService refApplyService;

    /** Writes and reads the held versions. */
    @Inject
    ObjectMapper objectMapper;

    /** The settings cache, dropped when held parameters are put back. */
    @Inject
    PosSettingsService posSettingsService;

    /** The echelon engine that folds, advances and cancels the dated parameters. */
    @Inject
    com.intermarche.edge.service.EchelonSettingService echelonSettings;

    /** The current transaction, to act again once it is over. */
    @Inject
    TransactionSynchronizationRegistry transactions;

    /** The date each administrator armed for his next changes, by login. */
    private final Map<String, LocalDateTime> armed = new ConcurrentHashMap<>();

    /** The held versions by domain, read from the pending releases at the first need. */
    private volatile Map<String, Held> heldCache;

    /** The day the loop last looked at, to move the stamp when dated parameters come into effect. */
    private volatile LocalDate lastDay = LocalDate.now();

    /** The release loop. */
    private ScheduledExecutorService executor;

    /**
     * A held version in memory.
     *
     * @param effectiveAt the date it stops being held
     * @param scopes the rows by register scope, {@link #ALL_SCOPE} for every register
     */
    record Held(LocalDateTime effectiveAt, Map<String, List<?>> scopes) {
    }

    /**
     * The versions the registers were exposed to just before a programmed
     * change, for each domain that was not held yet.
     */
    public static final class Capture {

        /** The rows by domain then register scope. */
        final Map<String, Map<String, List<?>>> rows = new LinkedHashMap<>();

        /** The fingerprints by domain then register scope. */
        final Map<String, Map<String, String>> fingerprints = new LinkedHashMap<>();

        /** The tables the SETTINGS resolution reads, for a cancellation. */
        String settingsSource;

        /** The image library the STORE_IMAGES resolution reads, for a cancellation. */
        String imagesSource;

        /** The date of effect the change is programmed for. */
        final LocalDateTime effectiveAt;

        /** Who programmed it. */
        final String user;

        /**
         * Builds an empty capture.
         *
         * @param effectiveAt the date of effect
         * @param user who programs
         */
        Capture(LocalDateTime effectiveAt, String user) {
            this.effectiveAt = effectiveAt;
            this.user = user;
        }
    }

    /**
     * One pending release as the screen lists it.
     *
     * @param id the release id
     * @param domain the referential domain
     * @param effective the date of effect, shown
     * @param programmed when it was programmed, shown
     * @param programmedBy who programmed it
     * @param origin the screen whose change opened it
     * @param complete true for a complete deployment
     * @param divergent true when the tables differ from the held version
     */
    public record PendingRow(Long id, String domain, String effective, String programmed,
                             String programmedBy, String origin, boolean complete, boolean divergent) {
    }

    /**
     * One closed release as the screen lists it.
     *
     * @param domain the referential domain
     * @param effective the date of effect, shown
     * @param status RELEASED or CANCELLED
     * @param closed when it was closed, shown
     * @param closedBy who closed it, empty when its date came
     */
    public record HistoryRow(String domain, String effective, String status, String closed, String closedBy) {
    }

    /**
     * Starts the release loop.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "referential-release");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::promoteDueSafely, LOOP_SECONDS, LOOP_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the release loop.
     */
    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Runs one pass of the loop in a transaction of its own, swallowing whatever it fails on.
     */
    void promoteDueSafely() {
        try {
            QuarkusTransaction.requiringNew().call(this::promoteDue);
        } catch (RuntimeException failure) {
            LOGGER.warnf("Libération des déploiements programmés impossible (%s)", failure.getMessage());
        }
    }

    /**
     * Tells whether this node holds versions for registers: a store node.
     *
     * @return true on a store node
     */
    public boolean enabled() {
        return "store".equalsIgnoreCase(role);
    }

    /**
     * Arms a date of effect for an administrator's next changes (BO-03-12-03).
     *
     * @param user the administrator's login
     * @param at the date of effect
     * @return false when there is no login or the date is not in the future
     */
    public boolean arm(String user, LocalDateTime at) {
        LOGGER.info("Entering method arm with user: " + user + ", at: " + at);
        if (user == null || at == null || !at.isAfter(LocalDateTime.now())) {
            LOGGER.info("Exiting method arm");
            return false;
        }
        armed.put(user, at);
        LOGGER.info("Exiting method arm");
        return true;
    }

    /**
     * Disarms an administrator: his next changes are immediate again.
     *
     * @param user the administrator's login, possibly null
     */
    public void disarm(String user) {
        LOGGER.info("Entering method disarm with user: " + user);
        if (user != null) {
            armed.remove(user);
        }
        LOGGER.info("Exiting method disarm");
    }

    /**
     * Returns the date an administrator armed, forgetting it once passed.
     *
     * @param user the administrator's login, possibly null
     * @return the date, or null when his changes are immediate
     */
    public LocalDateTime armedAt(String user) {
        if (user == null) {
            return null;
        }
        LocalDateTime at = armed.get(user);
        if (at == null) {
            return null;
        }
        if (!at.isAfter(LocalDateTime.now())) {
            armed.remove(user);
            return null;
        }
        return at;
    }

    /**
     * Captures the version the registers are exposed to, for every domain not
     * already held, just before a programmed change.
     *
     * @param at the date of effect
     * @param user who programs
     * @return the capture
     */
    @Transactional
    public Capture capture(LocalDateTime at, String user) {
        LOGGER.info("Entering method capture with at: " + at + ", user: " + user);
        Capture capture = new Capture(at, user);
        Map<String, Held> held = heldDomains();
        List<String> terminals = new ArrayList<>();
        for (CheckoutPoint point : CheckoutPoint.listAllOrdered()) {
            terminals.add(point.terminalId);
        }
        for (String domain : RefPayloads.DOMAINS) {
            if (held.containsKey(domain)) {
                continue;
            }
            Map<String, List<?>> rows = new LinkedHashMap<>();
            Map<String, String> fingerprints = new LinkedHashMap<>();
            for (String scope : scopes(domain, terminals)) {
                List<?> scoped = refExportService.liveRows(domain, scope.isEmpty() ? null : scope);
                rows.put(scope, scoped);
                fingerprints.put(scope, refExportService.fingerprintOf(scoped));
            }
            capture.rows.put(domain, rows);
            capture.fingerprints.put(domain, fingerprints);
        }
        if (capture.rows.containsKey("SETTINGS")) {
            capture.settingsSource = settingsSource();
        }
        if (capture.rows.containsKey("STORE_IMAGES")) {
            capture.imagesSource = imagesSource();
        }
        LOGGER.info("Exiting method capture");
        return capture;
    }

    /**
     * Lists the register scopes a domain is captured for.
     *
     * @param domain the referential domain
     * @param terminals the registers of the store
     * @return every register for a domain resolved per register, plus the common scope
     */
    private List<String> scopes(String domain, List<String> terminals) {
        List<String> scopes = new ArrayList<>();
        scopes.add(ALL_SCOPE);
        if (refExportService.isTerminalScoped(domain)) {
            scopes.addAll(terminals);
        }
        return scopes;
    }

    /**
     * Opens a release for every captured domain the change touched.
     *
     * @param capture the capture taken before the change
     * @param origin the back-office path that changed
     * @return the domains now held, in distribution order
     */
    @Transactional
    public List<String> record(Capture capture, String origin) {
        LOGGER.info("Entering method record with origin: " + origin);
        List<String> programmed = open(capture, origin, false);
        LOGGER.info("Exiting method record");
        return programmed;
    }

    /**
     * Programs a COMPLETE deployment (BO-03-12-04): every distributed domain
     * not held yet is held until the date, changed or not.
     *
     * @param user who programs
     * @param at the date of effect
     * @return the domains now held, empty when the date is not in the future
     */
    @Transactional
    public List<String> programComplete(String user, LocalDateTime at) {
        LOGGER.info("Entering method programComplete with user: " + user + ", at: " + at);
        if (at == null || !at.isAfter(LocalDateTime.now())) {
            LOGGER.info("Exiting method programComplete");
            return List.of();
        }
        List<String> programmed = open(capture(at, user), COMPLETE_ORIGIN, true);
        LOGGER.info("Exiting method programComplete");
        return programmed;
    }

    /**
     * Opens the releases of a capture.
     *
     * @param capture the capture
     * @param origin what opened them
     * @param complete true to hold every captured domain, false for the changed ones only
     * @return the domains now held
     */
    private List<String> open(Capture capture, String origin, boolean complete) {
        List<String> programmed = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, Map<String, List<?>>> entry : capture.rows.entrySet()) {
            String domain = entry.getKey();
            if (!complete && !changed(domain, capture.fingerprints.get(domain))) {
                continue;
            }
            if (ReferentialRelease.findPending(domain) != null) {
                continue;
            }
            ReferentialRelease release = new ReferentialRelease();
            release.domain = domain;
            release.status = ReferentialRelease.Status.PENDING;
            release.effectiveAt = capture.effectiveAt;
            release.programmedAt = now;
            release.programmedBy = capture.user;
            release.origin = origin;
            release.complete = complete;
            release.held = write(entry.getValue(), source(domain, capture));
            release.persist();
            programmed.add(domain);
        }
        if (!programmed.isEmpty()) {
            changed(false);
            LOGGER.infof("Déploiement programmé au %s : %s", capture.effectiveAt, programmed);
        }
        return programmed;
    }

    /**
     * Tells whether the tables of a domain moved since its capture.
     *
     * @param domain the referential domain
     * @param before the captured fingerprints by scope
     * @return true when any scope's fingerprint moved
     */
    private boolean changed(String domain, Map<String, String> before) {
        for (Map.Entry<String, String> scope : before.entrySet()) {
            String terminal = scope.getKey().isEmpty() ? null : scope.getKey();
            if (!scope.getValue().equals(refExportService.liveFingerprint(domain, terminal))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the source tables captured for a domain resolved from them.
     *
     * @param domain the referential domain
     * @param capture the capture
     * @return the source JSON, or null for a domain read from its own table
     */
    private String source(String domain, Capture capture) {
        if ("SETTINGS".equals(domain)) {
            return capture.settingsSource;
        }
        return "STORE_IMAGES".equals(domain) ? capture.imagesSource : null;
    }

    /**
     * Writes a held version.
     *
     * @param scopes the rows by scope
     * @param source the source tables, or null
     * @return the JSON
     */
    String write(Map<String, List<?>> scopes, String source) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("scopes", scopes);
        root.put("source", source);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Version détenue illisible", failure);
        }
    }

    /**
     * Reads a held version back into payload rows.
     *
     * @param release the release
     * @return the held version
     */
    Held read(ReferentialRelease release) {
        try {
            JsonNode scopes = objectMapper.readTree(release.held).get("scopes");
            Map<String, List<?>> rows = new HashMap<>();
            Class<?> type = DTO_TYPES.get(release.domain);
            scopes.fields().forEachRemaining(entry -> rows.put(entry.getKey(), objectMapper.convertValue(
                    entry.getValue(), objectMapper.getTypeFactory().constructCollectionType(List.class, type))));
            return new Held(release.effectiveAt, rows);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Version détenue illisible", failure);
        }
    }

    /**
     * Reads the source tables of a held version.
     *
     * @param release the release
     * @return the source, or null when none was captured
     */
    JsonNode readSource(ReferentialRelease release) {
        try {
            JsonNode source = objectMapper.readTree(release.held).get("source");
            return source == null || source.isNull() ? null : objectMapper.readTree(source.asText());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Version détenue illisible", failure);
        }
    }

    /**
     * Returns the held versions by domain, read once from the pending releases.
     *
     * @return the held versions, never null
     */
    Map<String, Held> heldDomains() {
        Map<String, Held> cache = heldCache;
        if (cache == null) {
            cache = new HashMap<>();
            for (ReferentialRelease release : ReferentialRelease.listPending()) {
                cache.put(release.domain, read(release));
            }
            heldCache = cache;
        }
        return cache;
    }

    /**
     * Returns the rows a register must still receive for a domain (BO-03-12-03/04).
     *
     * @param domain the referential domain
     * @param terminalId the asking register, or null
     * @return the held rows until the release date, null when the domain is not held
     */
    public List<?> heldRows(String domain, String terminalId) {
        Held held = heldDomains().get(domain);
        if (held == null || !held.effectiveAt().isAfter(LocalDateTime.now())) {
            return null;
        }
        List<?> rows = terminalId == null ? null : held.scopes().get(terminalId.trim());
        return rows != null ? rows : held.scopes().get(ALL_SCOPE);
    }

    /**
     * Returns how many domains are held.
     *
     * @return the number of pending releases
     */
    public int pendingCount() {
        return heldDomains().size();
    }

    /**
     * Releases every pending release whose date has come and moves the stamp,
     * so the registers pull the pending versions; on the day dated parameters
     * come into effect, folds them into their current value and moves it as well.
     *
     * @return how many releases were released
     */
    @Transactional
    public int promoteDue() {
        LocalDateTime now = LocalDateTime.now();
        List<ReferentialRelease> due = ReferentialRelease.listDue(now);
        for (ReferentialRelease release : due) {
            close(release, ReferentialRelease.Status.RELEASED, null);
        }
        LocalDate today = now.toLocalDate();
        boolean dayTurned = !today.equals(lastDay);
        lastDay = today;
        boolean datedToday = dayTurned && echelonSettings.promoteDue(today) > 0;
        if (datedToday) {
            posSettingsService.invalidate();
        }
        if (!due.isEmpty() || datedToday) {
            changed(true);
            LOGGER.infof("Déploiements programmés libérés : %d", due.size());
        }
        return due.size();
    }

    /**
     * Moves the date of a pending release; a date already come releases it now.
     *
     * @param id the release id
     * @param at the new date of effect
     * @param user who moves it
     * @return false when there is no such pending release
     */
    @Transactional
    public boolean reschedule(Long id, LocalDateTime at, String user) {
        LOGGER.info("Entering method reschedule with id: " + id + ", at: " + at + ", user: " + user);
        ReferentialRelease release = ReferentialRelease.findById(id);
        if (release == null || release.status != ReferentialRelease.Status.PENDING) {
            LOGGER.info("Exiting method reschedule");
            return false;
        }
        release.effectiveAt = at;
        boolean now = !at.isAfter(LocalDateTime.now());
        if (now) {
            close(release, ReferentialRelease.Status.RELEASED, user);
        }
        changed(now);
        LOGGER.info("Exiting method reschedule");
        return true;
    }

    /**
     * Cancels a pending release: the held version is put back into the tables,
     * so the back office shows again what the registers hold.
     *
     * @param id the release id
     * @param user who cancels it
     * @return false when there is no such pending release
     */
    @Transactional
    public boolean cancel(Long id, String user) {
        LOGGER.info("Entering method cancel with id: " + id + ", user: " + user);
        ReferentialRelease release = ReferentialRelease.findById(id);
        if (release == null || release.status != ReferentialRelease.Status.PENDING) {
            LOGGER.info("Exiting method cancel");
            return false;
        }
        restore(release.domain, read(release).scopes().get(ALL_SCOPE), readSource(release));
        close(release, ReferentialRelease.Status.CANCELLED, user);
        changed(true);
        LOGGER.info("Exiting method cancel");
        return true;
    }

    /**
     * Closes a release and drops its held version.
     *
     * @param release the release
     * @param status RELEASED or CANCELLED
     * @param user who closes it, null when its date came
     */
    private void close(ReferentialRelease release, ReferentialRelease.Status status, String user) {
        release.status = status;
        release.closedAt = LocalDateTime.now();
        release.closedBy = user;
        release.held = null;
    }

    /**
     * Puts a held version back into the tables through the register's apply code.
     *
     * @param domain the referential domain
     * @param rows the rows every register received
     * @param source the source tables of a resolved domain, or null
     */
    @SuppressWarnings("unchecked")
    void restore(String domain, List<?> rows, JsonNode source) {
        switch (domain) {
            case "SETTINGS" -> restoreSettings(source);
            case "STORE_IMAGES" -> restoreImages(source);
            case "EMPLOYEES" -> restoreEmployees((List<RefPayloads.EmployeeDto>) rows);
            case "VAT_RATES" -> {
                List<?> prices = refExportService.liveRows("PRICES", null);
                refApplyService.applyVatRates((List<RefPayloads.VatRateDto>) rows);
                refApplyService.applyPrices((List<RefPayloads.PriceDto>) prices);
            }
            case "ENGINE_FEEDS" -> refApplyService.applyEngineFeeds((List<RefPayloads.EngineFeedDto>) rows);
            case "PRODUCTS" -> refApplyService.applyProducts((List<RefPayloads.ProductDto>) rows);
            case "FAMILIES" -> refApplyService.applyFamilies((List<RefPayloads.FamilyDto>) rows);
            case "TOUCH_GROUPS" -> refApplyService.applyTouchGroups((List<RefPayloads.TouchGroupDto>) rows);
            case "PRICES" -> refApplyService.applyPrices((List<RefPayloads.PriceDto>) rows);
            case "COUPON_TYPES" -> refApplyService.applyCouponTypes((List<RefPayloads.CouponTypeDto>) rows);
            case "ARTICLE_RANGES" -> refApplyService.applyArticleBarcodeRanges(
                    (List<RefPayloads.ArticleBarcodeRangeDto>) rows);
            case "ISLANDS" -> refApplyService.applyCheckoutIslands((List<RefPayloads.CheckoutIslandDto>) rows);
            case "CHECKOUT_POINTS" -> refApplyService.applyCheckoutPoints(
                    (List<RefPayloads.CheckoutPointDto>) rows);
            case "TENDERS" -> refApplyService.applyTenders((List<RefPayloads.TenderDefinitionDto>) rows);
            case "DOCUMENT_TEMPLATES" -> refApplyService.applyDocumentTemplates(
                    (List<RefPayloads.DocumentTemplateDto>) rows);
            case "PRINTABLE_DOCUMENTS" -> refApplyService.applyPrintableDocuments(
                    (List<RefPayloads.PrintableDocumentDto>) rows);
            case "PROVIDER_MESSAGES" -> refApplyService.applyProviderMessages(
                    (List<RefPayloads.ProviderMessageDto>) rows);
            case "CASH_MENUS" -> refApplyService.applyCashMenus((List<RefPayloads.CashMenuDto>) rows);
            case "STORE" -> refApplyService.applyStore((List<RefPayloads.StoreDto>) rows);
            case "CUSTOMERS" -> refApplyService.applyCustomers((List<RefPayloads.CustomerDto>) rows);
            case "CURRENCIES" -> refApplyService.applyCurrencies((List<RefPayloads.CurrencyDto>) rows);
            case "MEASURE_UNITS" -> refApplyService.applyMeasureUnits((List<RefPayloads.MeasureUnitDto>) rows);
            case "POSTAL_CODES" -> refApplyService.applyPostalCodes((List<RefPayloads.PostalCodeDto>) rows);
            case "POS_LISTS" -> refApplyService.applyPosLists((List<RefPayloads.PosListDto>) rows);
            case "RETURN_TYPES" -> refApplyService.applyReturnTypes((List<RefPayloads.ReturnTypeDto>) rows);
            case "PREPAID_CARD_TYPES" -> refApplyService.applyPrepaidCardTypes(
                    (List<RefPayloads.PrepaidCardTypeDto>) rows);
            default -> throw new IllegalArgumentException("Domaine inconnu: " + domain);
        }
        LOGGER.infof("Version détenue remise en place : %s", domain);
    }

    /**
     * Puts the held accounts back. The register's apply deactivates an account
     * the version does not carry; an account that is not distributed to the
     * registers at all is not the release's business and stays active.
     *
     * @param rows the held accounts
     */
    private void restoreEmployees(List<RefPayloads.EmployeeDto> rows) {
        Set<String> distributed = new HashSet<>();
        for (RefPayloads.EmployeeDto dto : rows) {
            distributed.add(dto.loginName);
        }
        for (Object live : refExportService.liveRows("EMPLOYEES", null)) {
            distributed.add(((RefPayloads.EmployeeDto) live).loginName);
        }
        List<Employee> outside = new ArrayList<>();
        for (Employee employee : Employee.<Employee>list("active", true)) {
            if (!distributed.contains(employee.loginName)) {
                outside.add(employee);
            }
        }
        refApplyService.applyEmployees(rows);
        for (Employee employee : outside) {
            employee.active = true;
        }
    }

    /**
     * Captures the tables the SETTINGS resolution reads: the echelon values and
     * the node's own overrides.
     *
     * @return the JSON
     */
    String settingsSource() {
        List<Map<String, Object>> echelon = new ArrayList<>();
        for (EchelonSetting row : EchelonSetting.<EchelonSetting>listAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("level", row.level.name());
            item.put("echelonCode", row.echelonCode);
            item.put("settingKey", row.settingKey);
            item.put("settingValue", row.settingValue);
            item.put("pendingValue", row.pendingValue);
            item.put("effectiveDate", row.effectiveDate == null ? null : row.effectiveDate.toString());
            echelon.add(item);
        }
        List<Map<String, Object>> local = new ArrayList<>();
        for (PosSetting row : PosSetting.<PosSetting>listAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("settingKey", row.settingKey);
            item.put("settingValue", row.settingValue);
            local.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("echelon", echelon);
        root.put("local", local);
        return json(root);
    }

    /**
     * Captures the image library the STORE_IMAGES resolution reads.
     *
     * @return the JSON
     */
    String imagesSource() {
        List<Map<String, Object>> images = new ArrayList<>();
        for (StoreImage image : StoreImage.<StoreImage>listAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", image.code);
            item.put("label", image.label);
            item.put("usage", image.usage.name());
            item.put("level", image.level == null ? null : image.level.name());
            item.put("echelonCode", image.echelonCode);
            item.put("advantageCode", image.advantageCode);
            item.put("imageData", image.imageData);
            item.put("active", image.active);
            images.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("images", images);
        return json(root);
    }

    /**
     * Writes a value as JSON.
     *
     * @param value the value
     * @return the JSON
     */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Source illisible", failure);
        }
    }

    /**
     * Puts the parameter tables back as they were captured.
     *
     * @param source the captured tables
     */
    private void restoreSettings(JsonNode source) {
        EchelonSetting.deleteAll();
        PosSetting.deleteAll();
        for (JsonNode item : source.get("echelon")) {
            EchelonSetting row = new EchelonSetting();
            row.level = EchelonLevel.valueOf(item.get("level").asText());
            row.echelonCode = item.get("echelonCode").asText();
            row.settingKey = item.get("settingKey").asText();
            row.settingValue = text(item, "settingValue");
            row.pendingValue = text(item, "pendingValue");
            String day = text(item, "effectiveDate");
            row.effectiveDate = day == null ? null : LocalDate.parse(day);
            row.persist();
        }
        for (JsonNode item : source.get("local")) {
            PosSetting row = new PosSetting();
            row.settingKey = item.get("settingKey").asText();
            row.settingValue = item.get("settingValue").asText();
            row.persist();
        }
        posSettingsService.invalidate();
    }

    /**
     * Puts the image library back as it was captured.
     *
     * @param source the captured library
     */
    private void restoreImages(JsonNode source) {
        StoreImage.deleteAll();
        for (JsonNode item : source.get("images")) {
            StoreImage image = new StoreImage();
            image.code = item.get("code").asText();
            image.label = item.get("label").asText();
            image.usage = StoreImage.Usage.valueOf(item.get("usage").asText());
            String level = text(item, "level");
            image.level = level == null ? null : EchelonLevel.valueOf(level);
            image.echelonCode = text(item, "echelonCode");
            image.advantageCode = text(item, "advantageCode");
            image.imageData = text(item, "imageData");
            image.active = item.get("active").asBoolean();
            image.persist();
        }
    }

    /**
     * Reads an optional text field of a JSON object.
     *
     * @param node the object
     * @param field the field name
     * @return the text, or null when absent or null
     */
    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * Lists the pending releases for the screen, telling for each whether the
     * tables already differ from what the registers hold.
     *
     * @return the rows, the nearest date first
     */
    @Transactional
    public List<PendingRow> pending() {
        List<PendingRow> rows = new ArrayList<>();
        for (ReferentialRelease release : ReferentialRelease.listPending()) {
            List<?> held = read(release).scopes().get(ALL_SCOPE);
            boolean divergent = !refExportService.fingerprintOf(held)
                    .equals(refExportService.liveFingerprint(release.domain, null));
            rows.add(new PendingRow(release.id, release.domain, release.effectiveAt.format(SHOWN),
                    release.programmedAt.format(SHOWN),
                    release.programmedBy, release.origin, release.complete, divergent));
        }
        return rows;
    }

    /**
     * Lists the latest closed releases for the screen.
     *
     * @return the rows, the latest first
     */
    @Transactional
    public List<HistoryRow> history() {
        List<HistoryRow> rows = new ArrayList<>();
        for (ReferentialRelease release : ReferentialRelease.listHistory(HISTORY_SIZE)) {
            rows.add(new HistoryRow(release.domain, release.effectiveAt.format(SHOWN), release.status.name(),
                    release.closedAt == null ? "" : release.closedAt.format(SHOWN),
                    release.closedBy == null ? "" : release.closedBy));
        }
        return rows;
    }

    /**
     * Lists the parameters carrying a change programmed for a day still to come
     * (BO-03-12-03/04, the dated echelon values), each with its current and its
     * pending value.
     *
     * @return the rows, the nearest date first
     */
    @Transactional
    public List<EchelonSetting> datedSettings() {
        return echelonSettings.programmed();
    }

    /**
     * Brings a dated parameter into effect now: its pending value becomes the
     * current one.
     *
     * @param id the echelon value id
     * @return false when there is no such programmed value
     */
    @Transactional
    public boolean advanceSetting(Long id) {
        LOGGER.info("Entering method advanceSetting with id: " + id);
        if (!echelonSettings.advance(id)) {
            LOGGER.info("Exiting method advanceSetting");
            return false;
        }
        settingsMoved();
        LOGGER.info("Exiting method advanceSetting");
        return true;
    }

    /**
     * Cancels a dated parameter: only the pending value is removed, the current
     * value (or the inherited one) keeps applying.
     *
     * @param id the echelon value id
     * @return false when there is no such programmed value
     */
    @Transactional
    public boolean cancelSetting(Long id) {
        LOGGER.info("Entering method cancelSetting with id: " + id);
        if (!echelonSettings.cancel(id)) {
            LOGGER.info("Exiting method cancelSetting");
            return false;
        }
        settingsMoved();
        LOGGER.info("Exiting method cancelSetting");
        return true;
    }

    /**
     * Drops the settings cache and moves the stamp after a dated parameter moved.
     */
    private void settingsMoved() {
        posSettingsService.invalidate();
        changed(true);
    }

    /**
     * Drops the held versions in memory and, when asked, moves the stamp —
     * now, and again once the current transaction is over: a register polling
     * between this call and the commit would otherwise read the base as it
     * was, and keep that answer in the caches after the commit.
     *
     * @param moveStamp true to move the change stamp as well
     */
    void changed(boolean moveStamp) {
        heldCache = null;
        if (transactions == null || transactions.getTransactionKey() == null) {
            if (moveStamp) {
                refExportService.referentialChanged();
            }
            return;
        }
        transactions.registerInterposedSynchronization(new AfterCompletion(moveStamp));
    }

    /** What {@link #changed(boolean)} does again once the transaction is over. */
    final class AfterCompletion implements Synchronization {

        /** Whether to move the stamp on a commit. */
        private final boolean moveStamp;

        /**
         * Builds the callback.
         *
         * @param moveStamp whether to move the stamp on a commit
         */
        AfterCompletion(boolean moveStamp) {
            this.moveStamp = moveStamp;
        }

        /**
         * Does nothing before the completion.
         */
        @Override
        public void beforeCompletion() {
            LOGGER.debug("Referential release transaction completing");
        }

        /**
         * Drops the held versions read meanwhile and moves the stamp on a commit.
         *
         * @param status the JTA status of the completed transaction
         */
        @Override
        public void afterCompletion(int status) {
            heldCache = null;
            if (moveStamp && status == Status.STATUS_COMMITTED) {
                refExportService.referentialChanged();
            }
        }
    }
}
