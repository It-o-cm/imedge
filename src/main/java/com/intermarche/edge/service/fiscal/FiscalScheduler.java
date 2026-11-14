package com.intermarche.edge.service.fiscal;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The FISCAL SCHEDULER of the store node ({@code BO-05-01}): one loop that, at
 * each tick, produces the monthly fiscal archives, deposits and follows the
 * electronic invoices, extracts and transmits the e-reporting periods and sends
 * the SAF-T at the flow.
 *
 * <p>RESOLVED AT EVERY TICK: the loop caches nothing. Each step reads its own
 * activation and delays from the administered parameters when it runs, inside
 * its own transaction and request context, so a value changed in the back
 * office applies at the next tick and never needs a restart. A step that fails
 * is logged and the others still run: a platform down must not stop the
 * archives.
 *
 * <p>Started on a store or central node only: a register issues its documents,
 * it never transmits them.
 */
@ApplicationScoped
public class FiscalScheduler {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FiscalScheduler.class);

    /** The role of this node. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Seconds between two ticks. */
    @ConfigProperty(name = "edge.fiscal.tick-seconds", defaultValue = "20")
    long tickSeconds;

    /** The article-88 archives. */
    @Inject
    FiscalArchiveService fiscalArchiveService;

    /** The electronic invoices. */
    @Inject
    EInvoicingService eInvoicingService;

    /** The e-reporting. */
    @Inject
    EReportingService eReportingService;

    /** The SAF-T at the flow. */
    @Inject
    SaftStreamService saftStreamService;

    /** The loop, null on a register. */
    ScheduledExecutorService executor;

    /**
     * Starts the loop on a store or central node.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        if ("register".equalsIgnoreCase(role)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fiscal");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, tickSeconds, tickSeconds, TimeUnit.SECONDS);
        LOGGER.info("Ordonnanceur fiscal démarré");
    }

    /**
     * Stops the loop.
     */
    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Runs one tick: every step in turn, each failure logged and passed over.
     *
     * @return how many steps failed
     */
    int tick() {
        LocalDateTime now = LocalDateTime.now();
        int failed = 0;
        failed += step("archives", () -> fiscalArchiveService.autoArchive(now.toLocalDate()));
        failed += step("e-invoicing", () -> eInvoicingService.tick(now));
        failed += step("e-reporting", () -> eReportingService.tick(now));
        failed += step("saf-t", () -> saftStreamService.tick(now));
        return failed;
    }

    /**
     * Runs one step, swallowing whatever it fails on.
     *
     * @param name the step's name, for the log
     * @param work the step
     * @return one when the step failed, zero otherwise
     */
    static int step(String name, Runnable work) {
        try {
            work.run();
            return 0;
        } catch (RuntimeException failure) {
            LOGGER.warnf("Étape fiscale %s impossible (%s)", name, failure.getMessage());
            return 1;
        }
    }
}
