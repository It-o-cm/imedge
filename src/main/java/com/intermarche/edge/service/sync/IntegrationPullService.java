package com.intermarche.edge.service.sync;

import com.intermarche.edge.domain.setting.ServiceProvider;
import com.intermarche.edge.domain.sync.IntegrationDeposit;
import com.intermarche.edge.imports.EngineFeedRelayResource;
import com.intermarche.edge.service.ExternalRelayService;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The INTEGRATION FLOW, emitter side (BO-11-01-01): the store node GOES AND
 * FETCHES the articles, the nomenclature, the customers and the VAT the
 * commercial service publishes, at the address the back office declares for
 * it, and integrates them exactly as a deposit on the import door.
 *
 * <p>The address is not a property: it is the provider of type
 * {@code Service commercial} of the provider table (BO-03-11), with its
 * credential, so the requirement's "adresse de récupération des données" is
 * administered where every other third-party address is. Each administered
 * feed code is read by {@code GET <address>/<CODE>} and handed to the very
 * delegate a deposit runs; the deposit is recorded, so a file that failed or
 * left rejected lines is replayed by the integration console like any other.
 *
 * <p>A file IDENTICAL to the last one integrated under its code — recognised
 * by the SHA-256 the deposit keeps — is not integrated again. That is what keeps a pull from overriding the back office:
 * a publication the commercial service has not changed must not undo, every
 * hour, the corrections made since on the node.
 *
 * <p>Resolved per tick, never cached at startup: the feeds, the frequency and
 * the provider are read on every pass, so a change made in the back office
 * applies at the next minute. Every database read runs in a transaction of its
 * own, the loop having none.
 */
@ApplicationScoped
public class IntegrationPullService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(IntegrationPullService.class);

    /** How often the loop looks whether a pull is due, in seconds. */
    static final int TICK_SECONDS = 60;

    /** The read timeout of one feed, in milliseconds. */
    static final long TIMEOUT_MILLIS = 60000L;

    /** The administered feeds and frequency. */
    @Inject
    PosSettingsService posSettingsService;

    /** The credentialed reader of the provider's address. */
    @Inject
    ExternalRelayService externalRelayService;

    /** The import door whose delegates integrate a pulled file. */
    @Inject
    EngineFeedRelayResource engineFeedRelayResource;

    /** Records each pulled file as a deposit. */
    @Inject
    IntegrationRetryService integrationRetryService;

    /** The node's role: only a store node pulls its commercial service. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** When the last pull ran, null before the first. */
    LocalDateTime lastPull;

    /** The pull loop, null until the node has started. */
    private ScheduledExecutorService executor;

    /**
     * One pull's outcome, for the console notice.
     *
     * @param integrated how many files were integrated
     * @param unchanged how many were identical to the last integrated
     * @param failed how many could not be read
     */
    public record Outcome(int integrated, int unchanged, int failed) {
    }

    /**
     * Starts the pull loop.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "integration-pull");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tickSafely, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the pull loop.
     */
    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Runs one tick, swallowing whatever it fails on.
     */
    void tickSafely() {
        try {
            tick(LocalDateTime.now());
        } catch (RuntimeException failure) {
            LOGGER.warnf("Récupération des flux d'intégration impossible (%s)", failure.getMessage());
        }
    }

    /**
     * Pulls the feeds when a pull is due: a store node, at least one feed
     * administered, and the administered frequency elapsed since the last pull.
     *
     * @param now the current instant
     * @return the outcome, or null when no pull was due
     */
    Outcome tick(LocalDateTime now) {
        if (!"store".equalsIgnoreCase(role) || posSettingsService.integrationPullFeeds().isEmpty()) {
            return null;
        }
        if (lastPull != null && now.isBefore(lastPull.plusMinutes(posSettingsService.integrationPullMinutes()))) {
            return null;
        }
        return pullNow(now);
    }

    /**
     * Pulls every administered feed from every commercial service in service,
     * now — the loop's gesture and the console's. A file counts as integrated
     * only when its deposit was applied in full: one the import refused, failed
     * on or applied with rejected lines counts as a failure, since it waits for
     * its replay and the console must not announce it as done.
     *
     * @param now the instant of the pull
     * @return the outcome
     */
    public Outcome pullNow(LocalDateTime now) {
        LOGGER.info("Entering method pullNow");
        lastPull = now;
        int integrated = 0;
        int unchanged = 0;
        int failed = 0;
        List<String> feeds = posSettingsService.integrationPullFeeds();
        for (ServiceProvider provider : commercialProviders()) {
            for (String feed : feeds) {
                int equals = feed.indexOf('=');
                String code = (equals < 0 ? feed : feed.substring(0, equals)).trim().toUpperCase(Locale.ROOT);
                String scheme = equals < 0 ? null : feed.substring(equals + 1).trim();
                ExternalRelayService.RelayResult result = externalRelayService.relay(
                        Optional.of(provider.endpoint), "GET", "/" + code, null, TIMEOUT_MILLIS, provider.authKey);
                if (!result.reached() || result.status() < 200 || result.status() >= 300
                        || result.body().isBlank()) {
                    LOGGER.warnf("Flux %s du service commercial %s illisible (%s)", code, provider.code,
                            result.reached() ? "HTTP " + result.status() : "injoignable");
                    failed++;
                    continue;
                }
                if (EngineFeedService.sha256(result.body()).equals(lastFingerprint(code))) {
                    unchanged++;
                    continue;
                }
                Response answer = engineFeedRelayResource.integrate(code, null, scheme, scheme, null, result.body());
                IntegrationDeposit deposit =
                        integrationRetryService.record(code, null, scheme, scheme, null, result.body(), answer);
                if (deposit.status == IntegrationDeposit.Status.APPLIED) {
                    integrated++;
                } else {
                    failed++;
                }
            }
        }
        LOGGER.infof("Flux d'intégration récupérés : %d intégré(s), %d inchangé(s), %d en échec.",
                integrated, unchanged, failed);
        LOGGER.info("Exiting method pullNow");
        return new Outcome(integrated, unchanged, failed);
    }

    /**
     * Reads the commercial services in service that PUBLISH the feeds and can
     * be called: addressed, credentialed, and spoken to in plain HTTP. A
     * commercial service declared with a sales protocol (TFREMA, DS) is a
     * receiver of the sales stream, not a publisher of the referential, and is
     * never asked for a feed.
     *
     * @return the providers, in code order
     */
    List<ServiceProvider> commercialProviders() {
        List<ServiceProvider> reachable = new ArrayList<>();
        for (ServiceProvider provider : QuarkusTransaction.requiringNew().call(
                () -> ServiceProvider.listActiveOfType(ServiceProvider.ProviderType.COMMERCIAL))) {
            if (provider.isReachable() && provider.protocol == ServiceProvider.ProviderProtocol.HTTP_JSON) {
                reachable.add(provider);
            }
        }
        return reachable;
    }

    /**
     * Reads the fingerprint of the last file deposited under a code — its
     * SHA-256, which the deposit keeps even once an integrated file has
     * dropped its content.
     *
     * @param code the feed code
     * @return the fingerprint, or null when nothing was deposited under it
     *         (or before fingerprints were kept)
     */
    String lastFingerprint(String code) {
        IntegrationDeposit last = QuarkusTransaction.requiringNew().call(() -> IntegrationDeposit
                .<IntegrationDeposit>find("code = ?1 order by depositedAt desc, id desc", code).firstResult());
        return last == null ? null : last.contentHash;
    }
}
