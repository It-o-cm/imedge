package com.intermarche.pos.service.sync;

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
 * This register pulls the register domains ({@link RefPayloads#DOMAINS}) from
 * its store node (imedge). It never learns the echelons exist; it receives the
 * resolved SETTINGS already computed upstream. impos runs as a register only.
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
     * Seconds this register waits for one answer from its store node.
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

    @Inject
    ObjectMapper objectMapper;

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
        if (!syncEndpoints.hasStoreUrl()) {
            LOGGER.info("Tirage des référentiels désactivé (URL du nœud magasin absente)");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "referential-pull");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::pullSafely, 15, pullSeconds, TimeUnit.SECONDS);
        LOGGER.infof("Tirage des référentiels actif depuis %s (toutes les %ds)",
                upstreamUrl(), pullSeconds);
    }

    /**
     * The domains this register pulls from its store node.
     *
     * @return the register domain list to iterate
     */
    private List<String> pullDomains() {
        return RefPayloads.DOMAINS;
    }

    /**
     * The upstream base URL this register pulls from: its store node.
     *
     * @return the store node base URL
     */
    private String upstreamUrl() {
        return syncEndpoints.storeUrl();
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
        Map<String, String> remote = objectMapper.readValue(
                get("/api/referential/versions"), new TypeReference<Map<String, String>>() {});

        String firstFailure = null;
        for (String domain : pullDomains()) {
            String remoteFingerprint = remote.get(domain);
            if (remoteFingerprint == null || remoteFingerprint.equals(refApplyService.lastApplied(domain))) {
                continue;
            }
            LOGGER.infof("Référentiel %s modifié: tirage du snapshot", domain);
            try {
                applyDomain(domain, remoteFingerprint);
                lastErrors.remove(domain);
            } catch (Exception domainFailure) {
                String message = domainFailure.getMessage() == null
                        ? domainFailure.getClass().getSimpleName() : domainFailure.getMessage();
                lastErrors.put(domain, message);
                LOGGER.errorf("Référentiel %s non appliqué: %s", domain, message);
                if (firstFailure == null) {
                    firstFailure = domain + ": " + message;
                }
            }
        }
        if (firstFailure != null) {
            throw new IllegalStateException(firstFailure);
        }
        lastSuccessfulPull = java.time.LocalDateTime.now();
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
     * Applies one domain from the verbatim file the register holds, when it
     * holds one.
     *
     * @param domain the domain being applied
     * @param fingerprint the remote fingerprint to record on success
     * @return true when the file served, false to fall back on the pages
     */
    private boolean applyFromFeedFile(String domain, String fingerprint) {
        // The lookup goes through the apply service because THIS thread has
        // neither a transaction nor a CDI request context: a Panache finder
        // called here throws instead of returning the file.
        String content = refApplyService.feedContent(domain);
        if (content == null || content.isBlank()) {
            return false;
        }
        if (refApplyService.applyFromFile(domain, content) == null) {
            return false;
        }
        refApplyService.recordApplied(domain, fingerprint);
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
        if (FILE_DOMAINS.contains(domain) && applyFromFeedFile(domain, fingerprint)) {
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
            case "POS_LISTS" -> refApplyService.applyPosLists(
                    this.pages(domain, new TypeReference<List<RefPayloads.PosListDto>>() {}));
            case "SETTINGS" -> refApplyService.applySettings(
                    this.pages(domain, new TypeReference<List<RefPayloads.SettingDto>>() {}));
            case "ENGINE_FEEDS" -> refApplyService.applyEngineFeeds(
                    this.pages(domain, new TypeReference<List<RefPayloads.EngineFeedDto>>() {}));
            default -> throw new IllegalArgumentException("Domaine inconnu: " + domain);
        }
        refApplyService.recordApplied(domain, fingerprint);
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
     * Runs an authenticated GET against the store node.
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
        // BO-03-09/BO-03-10: name the caisse on the request so the node can
        // resolve the settings by contact point, then island, then PDV. Added
        // only when known; an older node simply ignores the header.
        if (terminalId != null && !terminalId.isBlank()) {
            builder.header("X-Pos-Terminal", terminalId.trim());
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " sur " + path);
        }
        return response.body();
    }
}
