package com.intermarche.edge.service.sync;

import com.intermarche.edge.domain.sync.IntegrationDeposit;
import com.intermarche.edge.imports.EngineFeedRelayResource;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The AUTOMATIC AND MANUAL REPLAY of the deposited integrations that failed
 * (BO-08-04-01 to BO-08-04-13, the central echelon of each).
 *
 * <p>At the central node an integration is a FILE DEPOSIT on the import
 * door, and a failed one used to have no retry but a second deposit. Every
 * deposit is now recorded here with its outcome; a file that FAILED, or went
 * in with REJECTED LINES, keeps its content and is replayed — articles,
 * nomenclature, customers in account, VAT, article attributes, commercial
 * offers alike, since they all enter by the same door:
 * <ul>
 *   <li>AUTOMATICALLY, every minute the loop wakes, for each deposit whose
 *       administered delay since its last attempt has passed, until it goes
 *       in or its administered attempts are spent (then ABANDONED);</li>
 *   <li>MANUALLY, from the integration console, at any time and whatever
 *       its state — the forcing a support team needs once the cause is
 *       fixed.</li>
 * </ul>
 * The typical case is the price file that names articles the article file
 * of the same night has not delivered yet: its lines are rejected, it is kept,
 * and the replay that follows the article file puts it in.
 *
 * <p>The replay runs the SAME delegate as the deposit, outside any
 * transaction of this class: the importers open their own transactions, and
 * a replay nested in one would fail on its first chunk.
 */
@ApplicationScoped
public class IntegrationRetryService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(IntegrationRetryService.class);

    /** The longest result the table holds. */
    static final int RESULT_LENGTH = 500;

    /**
     * An importer's answer listing at least one rejected line: an EMPTY
     * {@code "errors":[]} list (the nomenclature importer always writes one)
     * is a full success, not a partial one.
     */
    static final Pattern REJECTED_LINES = Pattern.compile("\"errors\"\\s*:\\s*\\[\\s*[^\\]\\s]");

    /** The administered activation, delay and attempts. */
    @Inject
    PosSettingsService posSettingsService;

    /** The import door whose delegates a replay runs again. */
    @Inject
    EngineFeedRelayResource engineFeedRelayResource;

    /** The replay loop. */
    private ScheduledExecutorService executor;

    /**
     * Starts the replay loop.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "integration-retry");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::retryDueSafely, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * Stops the replay loop.
     */
    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Runs one pass of the automatic replay, swallowing whatever it fails on.
     */
    void retryDueSafely() {
        try {
            retryDue();
        } catch (RuntimeException failure) {
            LOGGER.warnf("Relance des intégrations impossible (%s)", failure.getMessage());
        }
    }

    /**
     * Records a deposit and the outcome of its integration.
     *
     * @param code the feed code
     * @param mode the customer import mode
     * @param scheme the nomenclature scheme
     * @param label the nomenclature scheme label
     * @param enseigne the nomenclature enseigne
     * @param content the file
     * @param response what the integration answered
     * @return the recorded deposit
     */
    public IntegrationDeposit record(String code, String mode, String scheme, String label, String enseigne,
                                     String content, Response response) {
        LOGGER.info("Entering method record with code: " + code);
        IntegrationDeposit deposit = new IntegrationDeposit();
        deposit.code = code;
        deposit.mode = mode;
        deposit.scheme = scheme;
        deposit.schemeLabel = label;
        deposit.enseigne = enseigne;
        deposit.depositedAt = LocalDateTime.now();
        deposit.content = content;
        deposit.contentHash = content == null ? null : EngineFeedService.sha256(content);
        apply(deposit, response);
        QuarkusTransaction.requiringNew().run(deposit::persist);
        return deposit;
    }

    /**
     * Replays every deposit whose delay has passed (the automatic replay).
     *
     * @return how many deposits were replayed
     */
    public int retryDue() {
        if (!posSettingsService.integrationRetryEnabled()) {
            return 0;
        }
        int replayed = 0;
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(posSettingsService.integrationRetryMinutes());
        int max = posSettingsService.integrationRetryMaxAttempts();
        for (IntegrationDeposit deposit : retryable()) {
            if (deposit.attempts > max) {
                deposit.status = IntegrationDeposit.Status.ABANDONED;
                save(deposit);
            } else if (deposit.lastAttemptAt.isBefore(threshold)) {
                replay(deposit);
                replayed++;
            }
        }
        return replayed;
    }

    /**
     * Replays every failed or partial deposit now, whatever its delay (the
     * remote maintenance order).
     *
     * @return how many deposits were replayed
     */
    public int retryAll() {
        int replayed = 0;
        for (IntegrationDeposit deposit : retryable()) {
            replay(deposit);
            replayed++;
        }
        return replayed;
    }

    /**
     * Replays one deposit on demand (the manual replay of the console).
     *
     * @param id the deposit
     * @return the replayed deposit, or null when it is unknown or holds no content any more
     */
    public IntegrationDeposit replayNow(Long id) {
        IntegrationDeposit deposit = QuarkusTransaction.requiringNew()
                .call(() -> IntegrationDeposit.<IntegrationDeposit>findById(id));
        if (deposit == null || deposit.content == null) {
            return null;
        }
        replay(deposit);
        return deposit;
    }

    /**
     * The most recent deposits, for the console.
     *
     * @param limit how many to read
     * @return the deposits, newest first
     */
    public List<IntegrationDeposit> latest(int limit) {
        return QuarkusTransaction.requiringNew().call(() -> IntegrationDeposit.latest(limit));
    }

    /**
     * Integrates a kept deposit again and records the outcome.
     *
     * @param deposit the deposit
     */
    void replay(IntegrationDeposit deposit) {
        LOGGER.infof("Réintégration du dépôt %s (%s)", deposit.id, deposit.code);
        Response response = engineFeedRelayResource.integrate(deposit.code, deposit.mode, deposit.scheme,
                deposit.schemeLabel, deposit.enseigne, deposit.content);
        apply(deposit, response);
        save(deposit);
    }

    /**
     * Reads the integration's answer into the deposit.
     *
     * @param deposit the deposit
     * @param response the integration's answer
     */
    static void apply(IntegrationDeposit deposit, Response response) {
        String body = String.valueOf(response.getEntity());
        deposit.attempts++;
        deposit.lastAttemptAt = LocalDateTime.now();
        deposit.lastResult = body.length() > RESULT_LENGTH ? body.substring(0, RESULT_LENGTH) : body;
        deposit.status = statusOf(response.getStatus(), body);
        if (deposit.status == IntegrationDeposit.Status.APPLIED) {
            deposit.content = null;
        }
    }

    /**
     * Classifies an integration's answer.
     *
     * @param httpStatus the answer's status
     * @param body the answer's body
     * @return APPLIED, PARTIAL (rejected lines: a non-empty errors list), FAILED (server error) or
     *         REJECTED (refused file)
     */
    static IntegrationDeposit.Status statusOf(int httpStatus, String body) {
        if (httpStatus >= 500) {
            return IntegrationDeposit.Status.FAILED;
        }
        if (httpStatus >= 400) {
            return IntegrationDeposit.Status.REJECTED;
        }
        return REJECTED_LINES.matcher(body).find() ? IntegrationDeposit.Status.PARTIAL
                : IntegrationDeposit.Status.APPLIED;
    }

    /**
     * Reads the deposits to replay.
     *
     * @return the failed and partial deposits
     */
    private List<IntegrationDeposit> retryable() {
        return QuarkusTransaction.requiringNew().call(IntegrationDeposit::retryable);
    }

    /**
     * Writes a deposit back.
     *
     * @param deposit the deposit
     */
    private void save(IntegrationDeposit deposit) {
        QuarkusTransaction.requiringNew().run(() -> IntegrationDeposit.getEntityManager().merge(deposit));
    }
}
