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

    /** The role of this node: a register pulls its store, a store pulls the central. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token sent to the upstream node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The central node URL a STORE pulls its echelons from; absent = no central chaining. */
    @ConfigProperty(name = "pos.sync.central-url")
    Optional<String> centralUrl;

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

        for (String domain : pullDomains()) {
            String remoteFingerprint = remote.get(domain);
            if (remoteFingerprint == null || remoteFingerprint.equals(refApplyService.lastApplied(domain))) {
                continue;
            }
            LOGGER.infof("Référentiel %s modifié: tirage du snapshot", domain);
            applyDomain(domain, remoteFingerprint);
        }
        lastSuccessfulPull = java.time.LocalDateTime.now();
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
     * Downloads and applies the full snapshot of one domain, then records
     * the applied fingerprint.
     *
     * @param domain the referential domain
     * @param fingerprint the remote fingerprint being applied
     */
    private void applyDomain(String domain, String fingerprint) throws Exception {
        switch (domain) {
            case "FAMILIES" -> refApplyService.applyFamilies(
                    this.<RefPayloads.FamilyDto>pages(domain, new TypeReference<List<RefPayloads.FamilyDto>>() {}));
            case "TOUCH_GROUPS" -> refApplyService.applyTouchGroups(
                    this.<RefPayloads.TouchGroupDto>pages(domain, new TypeReference<List<RefPayloads.TouchGroupDto>>() {}));
            case "PRODUCTS" -> refApplyService.applyProducts(
                    this.<RefPayloads.ProductDto>pages(domain, new TypeReference<List<RefPayloads.ProductDto>>() {}));
            case "VAT_RATES" -> refApplyService.applyVatRates(
                    this.<RefPayloads.VatRateDto>pages(domain, new TypeReference<List<RefPayloads.VatRateDto>>() {}));
            case "PRICES" -> refApplyService.applyPrices(
                    this.<RefPayloads.PriceDto>pages(domain, new TypeReference<List<RefPayloads.PriceDto>>() {}));
            case "EMPLOYEES" -> refApplyService.applyEmployees(
                    this.<RefPayloads.EmployeeDto>pages(domain, new TypeReference<List<RefPayloads.EmployeeDto>>() {}));
            case "COUPON_TYPES" -> refApplyService.applyCouponTypes(
                    this.<RefPayloads.CouponTypeDto>pages(domain, new TypeReference<List<RefPayloads.CouponTypeDto>>() {}));
            case "ISLANDS" -> refApplyService.applyCheckoutIslands(
                    this.<RefPayloads.CheckoutIslandDto>pages(domain, new TypeReference<List<RefPayloads.CheckoutIslandDto>>() {}));
            case "TENDERS" -> refApplyService.applyTenders(
                    this.<RefPayloads.TenderDefinitionDto>pages(domain, new TypeReference<List<RefPayloads.TenderDefinitionDto>>() {}));
            case "DOCUMENT_TEMPLATES" -> refApplyService.applyDocumentTemplates(
                    this.<RefPayloads.DocumentTemplateDto>pages(domain, new TypeReference<List<RefPayloads.DocumentTemplateDto>>() {}));
            case "ARTICLE_RANGES" -> refApplyService.applyArticleBarcodeRanges(
                    this.<RefPayloads.ArticleBarcodeRangeDto>pages(domain, new TypeReference<List<RefPayloads.ArticleBarcodeRangeDto>>() {}));
            case "CUSTOMERS" -> refApplyService.applyCustomers(
                    this.<RefPayloads.CustomerDto>pages(domain, new TypeReference<List<RefPayloads.CustomerDto>>() {}));
            case "POSTAL_CODES" -> refApplyService.applyPostalCodes(
                    this.<RefPayloads.PostalCodeDto>pages(domain, new TypeReference<List<RefPayloads.PostalCodeDto>>() {}));
            case "CURRENCIES" -> refApplyService.applyCurrencies(
                    this.<RefPayloads.CurrencyDto>pages(domain, new TypeReference<List<RefPayloads.CurrencyDto>>() {}));
            case "SETTINGS" -> refApplyService.applySettings(
                    this.<RefPayloads.SettingDto>pages(domain, new TypeReference<List<RefPayloads.SettingDto>>() {}));
            case "ENGINE_FEEDS" -> refApplyService.applyEngineFeeds(
                    this.<RefPayloads.EngineFeedDto>pages(domain, new TypeReference<List<RefPayloads.EngineFeedDto>>() {}));
            case "COUNTRIES" -> refApplyService.applyCountries(
                    this.<RefPayloads.CountryDto>pages(domain, new TypeReference<List<RefPayloads.CountryDto>>() {}));
            case "ENSEIGNES" -> refApplyService.applyEnseignes(
                    this.<RefPayloads.EnseigneDto>pages(domain, new TypeReference<List<RefPayloads.EnseigneDto>>() {}));
            case "PDVS" -> refApplyService.applyPdvs(
                    this.<RefPayloads.PdvDto>pages(domain, new TypeReference<List<RefPayloads.PdvDto>>() {}));
            case "ECHELON_SETTINGS" -> refApplyService.applyEchelonSettings(
                    this.<RefPayloads.EchelonSettingDto>pages(domain, new TypeReference<List<RefPayloads.EchelonSettingDto>>() {}));
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
                .timeout(Duration.ofSeconds(30))
                .GET();
        String sharedToken = token.orElse("");
        if (!sharedToken.isBlank()) {
            builder.header("X-Sync-Token", sharedToken);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " sur " + path);
        }
        return response.body();
    }
}
