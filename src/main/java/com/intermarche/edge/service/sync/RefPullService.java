package com.intermarche.edge.service.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Register-side pull loop of the centralized referentials (phase 6 lot 3):
 * every {@code pos.referential.pull-seconds}, fetches the per-domain
 * fingerprints from the store node and, for each domain whose fingerprint
 * differs from the last applied one, downloads the full snapshot page by
 * page and applies it. A fresh register bootstraps itself the same way (no
 * fingerprint recorded yet, everything differs).
 * <p>
 * Two-level chain (route A): the SAME loop runs on a store node too, pulling
 * the echelon domains ({@link RefPayloads#ECHELON_DOMAINS}) from the
 * CENTRAL node instead of the register domains from the store — a register
 * pulls its store, a store pulls the central, and neither the mechanism nor
 * the fingerprint form changes, only the upstream URL and the domain set. A
 * register never learns the echelons exist; it receives the resolved SETTINGS.
 * A central node runs no pull: it is the top.
 * <p>
 * Consistency note: the pages of one snapshot are fetched without a shared
 * transaction, so an import running on the store node DURING a pull can
 * yield a torn snapshot. This is accepted by design: the recorded
 * fingerprint then no longer matches the store's next computation, and the
 * following cycle re-pulls a clean one — the fingerprint loop is
 * self-healing against its own race.
 */
@ApplicationScoped
public class RefPullService {

    private static final Logger LOGGER = Logger.getLogger(RefPullService.class);

    /** Snapshot page size. */
    private static final int PAGE_SIZE = 1000;

    /** Seconds between two pull cycles. */
    @ConfigProperty(name = "pos.referential.pull-seconds", defaultValue = "300")
    long pullSeconds;

    /**
     * Seconds between two reads of the upstream CHANGE STAMP (BO-03-12-01),
     * zero or less to read none: a back-office modification moves the stamp,
     * and the cycle then runs at once instead of waiting for its turn.
     */
    @ConfigProperty(name = "pos.referential.watch-seconds", defaultValue = "5")
    long watchSeconds;

    /** The last change stamp read upstream, null before the first read. */
    private volatile String lastStamp;

    /** The role of this node: a register pulls its store, a store pulls the central. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /**
     * Seconds this node waits for one answer from its upstream node.
     *
     * <p>Configurable and not a constant because the wait is not a property of
     * the protocol but of the CATALOGUE: the node answers
     * {@code /api/referential/versions} by hashing every row of every domain,
     * and a real point of sale has a hundred thousand articles where the
     * demonstration set has three hundred. Thirty seconds is right for the
     * latter and far too short for the former, where the whole pull cycle then
     * fails and NO domain is applied — the register silently keeps the
     * referential of its own seed.
     */
    @ConfigProperty(name = "pos.referential.request-timeout-seconds", defaultValue = "30")
    long requestTimeoutSeconds;

    /** Shared token sent to the upstream node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The central node URL a STORE pulls its echelons from; absent = no central chaining. */
    @ConfigProperty(name = "pos.sync.central-url")
    Optional<String> centralUrl;

    /**
     * This register's own identifier ({@code pos.terminal.id}), the same
     * property {@code TicketNumberService} reads. It NAMES the caisse on every
     * pull request (BO-03-09/BO-03-10) so an updated node can resolve the
     * settings by contact point, then by island, then by point of sale before
     * answering. Sent as the {@code X-Pos-Terminal} request header; an older
     * node ignores a header it does not know, so a caisse that names itself
     * keeps working unchanged against a node that has not moved.
     */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    @Inject
    SyncEndpoints syncEndpoints;

    @Inject
    RefApplyService refApplyService;

    /** This node's point of sale, named to the central node (BO-01-01-03). */
    @Inject
    com.intermarche.edge.service.NodeEchelonService nodeEchelonService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Whether a sale is in progress on this node (BO-10-08-25): resolvable on
     * a register only, a node that sells nothing never defers anything.
     */
    @Inject
    jakarta.enterprise.inject.Instance<SaleActivityProbe> saleActivity;

    /**
     * The domains an update of which must not reach a sale in progress
     * (BO-10-08-25): what the basket's articles, prices, VAT, promotions —
     * the engine feeds — and code readings are made of.
     */
    static final List<String> SALE_DOMAINS = List.of("ENGINE_FEEDS", "FAMILIES", "PRODUCTS",
            "VAT_RATES", "PRICES", "COUPON_TYPES", "ARTICLE_RANGES");

    /** Seconds before an update deferred for a sale is tried again (BO-10-08-25). */
    static final long DEFER_RETRY_SECONDS = 15;

    /**
     * The fingerprint each domain was last deferred at, so a deferral is
     * audited once and not at every retry (BO-10-08-25).
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> deferredAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether a retry of the deferred updates is already scheduled. */
    private final java.util.concurrent.atomic.AtomicBoolean retryPending =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * When the last pull CYCLE completed without error, or null while none has.
     *
     * <p>Deliberately the cycle and not the last applied domain: a referential that
     * has not changed for two hours is healthy, whereas {@code RefState.appliedAt}
     * would say two hours old and let a consumer conclude the link is down. In
     * memory only — after a restart the register has, in truth, not pulled anything
     * yet, and a consumer that cares (customer credit does) must be told so.
     */
    private volatile java.time.LocalDateTime lastSuccessfulPull;

    /**
     * The last integration failure of each domain, keyed by domain name; a
     * domain that applied is removed from it. Concurrent because the scheduled
     * cycle writes it while the supervision screen reads it.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> lastErrors =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The pull loop executor, or null when the pull is disabled. */
    private ScheduledExecutorService executor;

    /** The HTTP client used toward the store node. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Starts the pull loop at register startup when a store URL is
     * configured; the first cycle runs shortly after boot so a fresh
     * register fills itself quickly.
     *
     * @param event the Quarkus startup event
     */
    void onStart(@Observes StartupEvent event) {
        boolean registerReady = "register".equalsIgnoreCase(role) && syncEndpoints.hasStoreUrl();
        boolean storeReady = "store".equalsIgnoreCase(role) && hasCentralUrl();
        if (!registerReady && !storeReady) {
            LOGGER.info("Tirage des référentiels désactivé (rôle central, ou URL amont absente)");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "referential-pull");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::pullSafely, 15, pullSeconds, TimeUnit.SECONDS);
        if (watchSeconds > 0) {
            executor.scheduleWithFixedDelay(this::watchSafely, 20, watchSeconds, TimeUnit.SECONDS);
        }
        LOGGER.infof("Tirage des référentiels actif depuis %s (toutes les %ds)",
                upstreamUrl(), pullSeconds);
    }

    /**
     * Whether a non-blank central URL is configured (a store node's upstream).
     *
     * @return true when the central URL is present and non-blank
     */
    private boolean hasCentralUrl() {
        return centralUrl.map(url -> !url.isBlank()).orElse(false);
    }

    /**
     * The domains this node pulls: the echelon domains for a store node, the
     * register domains otherwise.
     *
     * @return the domain list to iterate
     */
    private List<String> pullDomains() {
        return "store".equalsIgnoreCase(role) ? RefPayloads.ECHELON_DOMAINS : RefPayloads.DOMAINS;
    }

    /**
     * The upstream base URL this node pulls from: the central URL for a store
     * node, the store URL otherwise.
     *
     * @return the upstream base URL
     */
    private String upstreamUrl() {
        return "store".equalsIgnoreCase(role) ? centralUrl.orElse("") : syncEndpoints.storeUrl();
    }

    /**
     * Stops the pull loop at shutdown.
     */
    @PreDestroy
    void onStop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Reads the upstream change stamp once, never letting an exception kill
     * the loop: an older node answering no stamp simply leaves the register
     * on its cycle (BO-03-12-01).
     */
    void watchSafely() {
        try {
            watchOnce();
        } catch (Exception e) {
            LOGGER.debugf("Tampon des référentiels illisible: %s", e.getMessage());
        }
    }

    /**
     * Reads the upstream change stamp and runs a pull cycle AT ONCE when it
     * moved since the last read (BO-03-12-01). The first read only records
     * it: the cycle that starts with the node already pulls everything.
     *
     * @throws Exception when the stamp cannot be read
     */
    void watchOnce() throws Exception {
        String stamp = get("/api/referential/stamp").trim();
        String previous = lastStamp;
        lastStamp = stamp;
        if (previous != null && !previous.equals(stamp)) {
            LOGGER.infof("Modification du back-office détectée, tirage immédiat des référentiels");
            pullSafely();
        }
    }

    /**
     * Runs one pull cycle, never letting an exception kill the loop.
     */
    private void pullSafely() {
        try {
            pullOnce();
        } catch (Exception e) {
            LOGGER.errorf("Cycle de tirage des référentiels en erreur: %s", e.getMessage());
        }
    }

    /**
     * Runs one pull cycle: compares the fingerprints and applies every
     * changed domain in dependency order.
     */
    void pullOnce() throws Exception {
        String versions;
        try {
            versions = get("/api/referential/versions");
            nodeReachable = Boolean.TRUE;
            unreachableSince = null;
        } catch (Exception unreachable) {
            // BO-03-06-45: the one call every cycle makes is the register's
            // pulse of its link with the store node.
            nodeReachable = Boolean.FALSE;
            // BO-10-07-19: the FIRST failure dates the outage; the later ones
            // leave it, so the delay counts from when the link was lost.
            if (unreachableSince == null) {
                unreachableSince = java.time.LocalDateTime.now();
            }
            throw unreachable;
        }
        Map<String, String> remote = objectMapper.readValue(
                versions, new TypeReference<Map<String, String>>() {});
        String firstFailure = null;
        boolean deferred = false;
        for (String domain : pullDomains()) {
            String remoteFingerprint = remote.get(domain);
            if (remoteFingerprint == null || remoteFingerprint.equals(refApplyService.lastApplied(domain))) {
                continue;
            }
            // BO-10-08-25: an update of what the basket is made of waits for the
            // sale to end. Its fingerprint is not recorded, so the domain still
            // differs and the next cycle — brought forward — applies it once the
            // register is free.
            if (SALE_DOMAINS.contains(domain) && saleInProgress()) {
                deferred = true;
                if (!remoteFingerprint.equals(deferredAt.put(domain, remoteFingerprint))) {
                    recordAudit(domain, remoteFingerprint,
                            com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.DEFERRED, "Vente en cours");
                }
                LOGGER.infof("Référentiel %s différé : vente en cours", domain);
                continue;
            }
            deferredAt.remove(domain);
            LOGGER.infof("Référentiel %s modifié: tirage du snapshot", domain);
            try {
                applyDomain(domain, remoteFingerprint);
                lastErrors.remove(domain);
                // BO-10-08-23: the dated result of this integration, where the
                // shop traces them. After the apply, never instead of it.
                recordAudit(domain, remoteFingerprint,
                        com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.APPLIED, null);
            } catch (Exception domainFailure) {
                String message = domainFailure.getMessage() == null
                        ? domainFailure.getClass().getSimpleName() : domainFailure.getMessage();
                lastErrors.put(domain, message);
                recordAudit(domain, remoteFingerprint,
                        com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.FAILED, message);
                LOGGER.errorf("Référentiel %s non appliqué: %s", domain, message);
                if (firstFailure == null) {
                    firstFailure = domain + ": " + message;
                }
            }
        }
        if (deferred) {
            scheduleRetry();
        }
        if (firstFailure != null) {
            throw new IllegalStateException(firstFailure);
        }
        lastSuccessfulPull = java.time.LocalDateTime.now();
    }

    /**
     * Whether a sale in progress forbids the sale domains to be applied now
     * (BO-10-08-25).
     *
     * @return true only on a node that sells, while it sells
     */
    private boolean saleInProgress() {
        return saleActivity != null && saleActivity.isResolvable() && saleActivity.get().saleInProgress();
    }

    /**
     * Brings the next cycle forward when an update was deferred for a sale
     * (BO-10-08-25), once: a retry already pending is not doubled.
     */
    private void scheduleRetry() {
        if (executor != null && retryPending.compareAndSet(false, true)) {
            executor.schedule(this::retryDeferred, DEFER_RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Runs the cycle brought forward for the deferred updates (BO-10-08-25).
     */
    void retryDeferred() {
        retryPending.set(false);
        pullSafely();
    }

    /**
     * Whether the last pull cycle reached the store node (BO-03-06-45): true
     * when it answered, false when it could not be reached, null before the
     * first cycle or on a register with no store node.
     */
    private volatile Boolean nodeReachable = null;

    /**
     * Tells whether the register has lost its link with the store node
     * (BO-03-06-45): its last pull cycle could not reach it.
     *
     * @return true only when a cycle ran and failed to reach the node
     */
    public boolean isNodeUnreachable() {
        return Boolean.FALSE.equals(nodeReachable);
    }

    /**
     * Since when the store node has stopped answering (BO-10-07-19), or null
     * while it answers, before the first cycle, or on a register with none.
     */
    private volatile java.time.LocalDateTime unreachableSince = null;

    /**
     * Tells whether the store node has been unreachable for at least a delay
     * (BO-10-07-19): the outage started that long ago and no cycle has reached
     * the node since.
     *
     * @param seconds the delay, in seconds
     * @param now the instant the question is asked
     * @return true when the outage lasts since at least that delay
     */
    public boolean isUnreachableFor(long seconds, java.time.LocalDateTime now) {
        java.time.LocalDateTime since = unreachableSince;
        return since != null && !since.plusSeconds(seconds).isAfter(now);
    }

    /**
     * The last integration failure of each referential domain, keyed by domain.
     *
     * <p>Why this exists. A pull domain that fails to apply left NO trace: a
     * {@code RefState} row is written only on success, so the supervision
     * screen showed a fingerprint and an apply date for the domains that went
     * through and nothing at all for the one that did not. The domains that are
     * also engine feeds carried their error on the feed row; the ten that are
     * pull-only — among them CUSTOMERS, the clients en compte — carried none.
     *
     * <p>Held in memory, like {@link #getLastSuccessfulPull()}: this is the
     * state of THIS node since it started, not a journal. A restart clears it,
     * and the next cycle re-reports whatever is still broken.
     *
     * @return the failures, empty when every domain applied; never null
     */
    public Map<String, String> getLastErrors() {
        LOGGER.info("Entering method getLastErrors");
        LOGGER.info("Exiting method getLastErrors");
        return java.util.Collections.unmodifiableMap(lastErrors);
    }

    /**
     * The last integration failure of one referential domain.
     *
     * @param domain the domain name, as {@code RefPayloads} spells it
     * @return the failure message, or null when this domain has not failed
     *         since this node started
     */
    public String getLastError(String domain) {
        LOGGER.info("Entering method getLastError with domain: " + domain);
        LOGGER.info("Exiting method getLastError");
        return lastErrors.get(domain);
    }

    /**
     * When the last complete pull cycle succeeded.
     *
     * @return the instant of the last successful cycle, or null when this register
     *         has not completed one since it started
     */
    public java.time.LocalDateTime getLastSuccessfulPull() {
        LOGGER.info("Entering method getLastSuccessfulPull");
        LOGGER.info("Exiting method getLastSuccessfulPull");
        return lastSuccessfulPull;
    }

    /**
     * Runs one pull cycle NOW, on the caller's thread, on demand from the sync
     * supervision screen (BO-08-04-09/10). It is the manual counterpart of the
     * scheduled {@link #pullSafely()}: same {@link #pullOnce()}, but the outcome
     * is returned rather than swallowed so the screen can show it. A success
     * refreshes {@link #getLastSuccessfulPull()} exactly like a scheduled cycle.
     *
     * @return null when the cycle completed, or the failure message otherwise
     */
    public String triggerPull() {
        LOGGER.info("Entering method triggerPull");
        try {
            pullOnce();
            LOGGER.info("Exiting method triggerPull");
            return null;
        } catch (Exception e) {
            LOGGER.errorf("Tirage manuel des référentiels en erreur: %s", e.getMessage());
            LOGGER.info("Exiting method triggerPull");
            return e.getMessage();
        }
    }

    /**
     * The configured cadence between two scheduled pull cycles, surfaced
     * read-only on the supervision screen (BO-08-03-08).
     *
     * @return the pull period in seconds
     */
    public long getPullSeconds() {
        LOGGER.info("Entering method getPullSeconds");
        LOGGER.info("Exiting method getPullSeconds");
        return pullSeconds;
    }

    /**
     * The domains the register applies from the raw file rather than from the
     * snapshot pages.
     *
     * <p>Exactly those the engine feed catalog also carries, which is what puts
     * the file on this register in the first place, and exactly the three that
     * are big enough for the difference to matter.
     */
    private static final List<String> FILE_DOMAINS = List.of("PRODUCTS", "FAMILIES", "PRICES");

    /**
     * The raw file each {@link #FILE_DOMAINS} domain was last applied from, by
     * its content's hash and length. A file already applied cannot explain a
     * new fingerprint: the domain then moved by a back-office change the file
     * does not carry (an article deactivated, its sales volume, its
     * attributes), and only the snapshot pages hold it.
     */
    private final Map<String, String> appliedFeedFiles = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Applies one domain from the verbatim file the register holds, when it
     * holds one and stands where the file starts from.
     *
     * @param domain the domain being applied
     * @return true when the file served, false to fall back on the pages
     */
    private boolean applyFromFeedFile(String domain) {
        // The lookup goes through the apply service because THIS thread has
        // neither a transaction nor a CDI request context: a Panache finder
        // called here throws instead of returning the file.
        String content = refApplyService.feedContent(domain);
        if (content == null || content.isBlank()) {
            return false;
        }
        String file = Integer.toHexString(content.hashCode()) + ":" + content.length();
        if (file.equals(appliedFeedFiles.get(domain))) {
            return false;
        }
        // BO-02-03-33: a file is the change from ONE state of the store's
        // referential. Over any other — a file missed while the register was
        // busy, a back-office edit not yet applied — it would leave the register
        // short of what it missed while recording the store's fingerprint as
        // reached: the pages are then the truth.
        RefApplyService.FeedStamp stamp = refApplyService.feedStamp(domain);
        if (stamp == null || !stamp.startsFrom(refApplyService.lastApplied(domain))) {
            return false;
        }
        if (refApplyService.applyFromFile(domain, content) == null) {
            return false;
        }
        // The state the file left, not the one served now: a later change then
        // still differs, and the next cycle takes it from the pages.
        refApplyService.recordApplied(domain, stamp.result());
        appliedFeedFiles.put(domain, file);
        return true;
    }

    /**
     * Downloads and applies the full snapshot of one domain, then records
     * the applied fingerprint.
     *
     * @param domain the referential domain
     * @param fingerprint the remote fingerprint being applied
     */
    private void applyDomain(String domain, String fingerprint) throws Exception {
        // The three big shared referentials are applied from the RAW FILE the
        // register already holds through ENGINE_FEEDS, by the same importer the
        // store node uses: transactions of a thousand rows, nothing accumulated
        // in memory, and an unchanged row costing nothing. The pages are not
        // even fetched. ENGINE_FEEDS is applied first in DOMAINS so the file is
        // there when its domain comes up; should it be missing — a register
        // that has never pulled it — the payload path below still serves.
        if (FILE_DOMAINS.contains(domain) && applyFromFeedFile(domain)) {
            return;
        }
        switch (domain) {
            case "FAMILIES" -> refApplyService.applyFamilies(
                    this.pages(domain, new TypeReference<List<RefPayloads.FamilyDto>>() {}));
            case "TOUCH_GROUPS" -> refApplyService.applyTouchGroups(
                    this.pages(domain, new TypeReference<List<RefPayloads.TouchGroupDto>>() {}));
            case "PRODUCTS" -> refApplyService.applyProducts(
                    this.pages(domain, new TypeReference<List<RefPayloads.ProductDto>>() {}));
            case "VAT_RATES" -> refApplyService.applyVatRates(
                    this.pages(domain, new TypeReference<List<RefPayloads.VatRateDto>>() {}));
            case "PRICES" -> refApplyService.applyPrices(
                    this.pages(domain, new TypeReference<List<RefPayloads.PriceDto>>() {}));
            case "EMPLOYEES" -> refApplyService.applyEmployees(
                    this.pages(domain, new TypeReference<List<RefPayloads.EmployeeDto>>() {}));
            case "COUPON_TYPES" -> refApplyService.applyCouponTypes(
                    this.pages(domain, new TypeReference<List<RefPayloads.CouponTypeDto>>() {}));
            case "ISLANDS" -> refApplyService.applyCheckoutIslands(
                    this.pages(domain, new TypeReference<List<RefPayloads.CheckoutIslandDto>>() {}));
            case "CHECKOUT_POINTS" -> refApplyService.applyCheckoutPoints(
                    this.pages(domain, new TypeReference<List<RefPayloads.CheckoutPointDto>>() {}));
            case "TENDERS" -> refApplyService.applyTenders(
                    this.pages(domain, new TypeReference<List<RefPayloads.TenderDefinitionDto>>() {}));
            case "DOCUMENT_TEMPLATES" -> refApplyService.applyDocumentTemplates(
                    this.pages(domain, new TypeReference<List<RefPayloads.DocumentTemplateDto>>() {}));
            case "PRINTABLE_DOCUMENTS" -> refApplyService.applyPrintableDocuments(
                    this.pages(domain, new TypeReference<List<RefPayloads.PrintableDocumentDto>>() {}));
            case "PROVIDER_MESSAGES" -> refApplyService.applyProviderMessages(
                    this.pages(domain, new TypeReference<List<RefPayloads.ProviderMessageDto>>() {}));
            case "ARTICLE_RANGES" -> refApplyService.applyArticleBarcodeRanges(
                    this.pages(domain, new TypeReference<List<RefPayloads.ArticleBarcodeRangeDto>>() {}));
            case "CUSTOMERS" -> refApplyService.applyCustomers(
                    this.pages(domain, new TypeReference<List<RefPayloads.CustomerDto>>() {}));
            case "POSTAL_CODES" -> refApplyService.applyPostalCodes(
                    this.pages(domain, new TypeReference<List<RefPayloads.PostalCodeDto>>() {}));
            case "CURRENCIES" -> refApplyService.applyCurrencies(
                    this.pages(domain, new TypeReference<List<RefPayloads.CurrencyDto>>() {}));
            case "MEASURE_UNITS" -> refApplyService.applyMeasureUnits(
                    this.pages(domain, new TypeReference<List<RefPayloads.MeasureUnitDto>>() {}));
            case "POS_LISTS" -> refApplyService.applyPosLists(
                    this.pages(domain, new TypeReference<List<RefPayloads.PosListDto>>() {}));
            case "RETURN_TYPES" -> refApplyService.applyReturnTypes(
                    this.pages(domain, new TypeReference<List<RefPayloads.ReturnTypeDto>>() {}));
            case "PREPAID_CARD_TYPES" -> refApplyService.applyPrepaidCardTypes(
                    this.pages(domain, new TypeReference<List<RefPayloads.PrepaidCardTypeDto>>() {}));
            case "STORE_IMAGES" -> refApplyService.applyStoreImages(
                    this.pages(domain, new TypeReference<List<RefPayloads.StoreImageDto>>() {}));
            case "CASH_MENUS" -> refApplyService.applyCashMenus(
                    this.pages(domain, new TypeReference<List<RefPayloads.CashMenuDto>>() {}));
            case "STORE" -> refApplyService.applyStore(
                    this.pages(domain, new TypeReference<List<RefPayloads.StoreDto>>() {}));
            case "SETTINGS" -> refApplyService.applySettings(
                    this.pages(domain, new TypeReference<List<RefPayloads.SettingDto>>() {}));
            case "ENGINE_FEEDS" -> refApplyService.applyEngineFeeds(
                    this.pages(domain, new TypeReference<List<RefPayloads.EngineFeedDto>>() {}));
            case "COUNTRIES" -> refApplyService.applyCountries(
                    this.pages(domain, new TypeReference<List<RefPayloads.CountryDto>>() {}));
            case "ENSEIGNES" -> refApplyService.applyEnseignes(
                    this.pages(domain, new TypeReference<List<RefPayloads.EnseigneDto>>() {}));
            case "PDVS" -> refApplyService.applyPdvs(
                    this.pages(domain, new TypeReference<List<RefPayloads.PdvDto>>() {}));
            case "ECHELON_SETTINGS" -> refApplyService.applyEchelonSettings(
                    this.pages(domain, new TypeReference<List<RefPayloads.EchelonSettingDto>>() {}));
            case "PROFILES" -> refApplyService.applyProfiles(
                    this.pages(domain, new TypeReference<List<RefPayloads.ProfileDto>>() {}));
            case "ACCOUNTS" -> refApplyService.applyAccounts(
                    this.pages(domain, new TypeReference<List<RefPayloads.AccountDto>>() {}));
            case "ACCOUNT_PROFILES" -> refApplyService.applyAccountProfiles(
                    this.pages(domain, new TypeReference<List<RefPayloads.AccountProfileDto>>() {}));
            default -> throw new IllegalArgumentException("Domaine inconnu: " + domain);
        }
        refApplyService.recordApplied(domain, fingerprint);
    }

    /**
     * Traces one integration result, and never lets the tracing cost an
     * integration (BO-10-08-23).
     *
     * <p>A full disk, a locked table, a mapping that changed: none of these is
     * a reason for a node to stop integrating its referential. The trouble is
     * logged and the cycle goes on.
     *
     * @param domain the referential domain
     * @param fingerprint the fingerprint the node was reaching
     * @param outcome how the attempt ended
     * @param detail what went wrong, or null for a success
     */
    private void recordAudit(String domain, String fingerprint,
            com.intermarche.edge.domain.sync.RefApplyAudit.Outcome outcome, String detail) {
        try {
            refApplyService.recordAudit(domain, fingerprint, outcome, detail);
        } catch (Exception auditFailure) {
            LOGGER.warnf("Audit d'intégration non écrit pour %s (%s)",
                    domain, auditFailure.getMessage());
        }
    }

    /**
     * Downloads every page of a domain's snapshot.
     *
     * @param <T> the payload type
     * @param domain the referential domain
     * @param pageType the Jackson type of one page
     * @return all rows of the snapshot
     */
    private <T> List<T> pages(String domain, TypeReference<List<T>> pageType) throws Exception {
        List<T> all = new ArrayList<>();
        int page = 0;
        while (true) {
            List<T> rows = objectMapper.readValue(
                    get("/api/referential/" + domain + "?page=" + page + "&size=" + PAGE_SIZE), pageType);
            if (rows.isEmpty()) break;
            all.addAll(rows);
            page++;
        }
        return all;
    }

    /**
     * Runs an authenticated GET against the upstream node.
     *
     * @param path the path to fetch
     * @return the response body
     * @throws IllegalStateException on a non-2xx status
     */
    private String get(String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(upstreamUrl() + path))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .GET();
        String sharedToken = token.orElse("");
        if (!sharedToken.isBlank()) {
            builder.header("X-Sync-Token", sharedToken);
        }
        // BO-03-09/BO-03-10: a register names the caisse on the request so the
        // node can resolve the settings by contact point, then island, then
        // PDV. Added only when known; an older node simply ignores the header.
        if (!"store".equalsIgnoreCase(role) && terminalId != null && !terminalId.isBlank()) {
            builder.header("X-Pos-Terminal", terminalId.trim());
        }
        String pdv = "store".equalsIgnoreCase(role) && nodeEchelonService != null
                ? nodeEchelonService.currentPdv() : null;
        if (pdv != null && !pdv.isBlank()) {
            // BO-01-01-03: the central node hands each store the accounts of
            // ITS point of sale, so the store says which one it is.
            builder.header("X-Pos-Pdv", pdv.trim());
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " sur " + path);
        }
        return response.body();
    }
}
