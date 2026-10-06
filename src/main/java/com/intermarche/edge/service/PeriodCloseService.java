package com.intermarche.edge.service;

import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.sync.RegisterCommand;
import com.intermarche.edge.domain.util.DateTimeProvider;
import com.intermarche.edge.service.sync.RegisterCommandService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Closes, as the period ends, the registers someone left open (LC-01-02-08,
 * BO-09-01-06/09, BO-09-03-07).
 *
 * <p>The shop's day ends at an administered hour. A register still logged in at
 * that moment is a register nobody is standing at: its operator has gone home,
 * and the session would otherwise be counted on the next day. So a few minutes
 * BEFORE the period ends — the lead of BO-09-03-07 — the node leaves each open
 * register the order to close.
 *
 * <p>An ORDER, and not a closing: the node closes nothing itself. It cannot,
 * and it should not pretend to. A register may be off, unplugged, or halfway
 * through a customer's shopping, and only the register knows which. The order
 * carried is therefore {@code CLOSE_SESSION_IF_IDLE}: a register at rest obeys
 * at once, a register in the middle of a sale leaves the order pending and
 * takes it again when the cashier has finished. That is the whole meaning of
 * « si une caisse est ouverte SANS ACTIVITÉ ».
 *
 * <p>Issuing is idempotent per register and per kind, so a tick every minute
 * over the whole lead window leaves exactly one order per register — and a
 * register that comes back up inside the window still finds its order waiting.
 *
 * <p>Runs on its own daemon thread rather than through a scheduler extension:
 * the node already drives its periodic work this way, and a new extension on
 * the pom would have to be justified by more than one hourly tick.
 */
@ApplicationScoped
public class PeriodCloseService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PeriodCloseService.class);

    /** How the administered end-of-period hour is written. */
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    /** Minutes of a day, for the countdown that straddles midnight. */
    private static final long DAY_MINUTES = 24L * 60L;

    /**
     * How long before the end of period the back office starts announcing it.
     *
     * <p>An hour: long enough for a manager to go and see what a register is
     * still doing open, short enough that the banner is not permanent furniture
     * nobody reads any more (BO-09-01-06).
     */
    static final long ALERT_HORIZON_MINUTES = 60L;

    /** The administered parameters: armed, hour, lead. */
    @Inject PosSettingsService posSettingsService;

    /** The order book the registers collect from. */
    @Inject RegisterCommandService registerCommandService;

    /** The electronic journal the requirement asks these orders to land in. */
    @Inject TechnicalEventService technicalEventService;

    /** The end-of-period data-retention sweep (BO-10-08-03 to -26). */
    @Inject RetentionPurgeService retentionPurgeService;

    /** The end-of-period financial-state emission (X/Z campaign, §4). */
    @Inject FinancialStateEmissionService financialStateEmissionService;

    /**
     * The day the retention sweep last ran, so it runs at most once a period.
     * <p>In memory rather than persisted: a restart re-runs it the same evening,
     * which is harmless — the sweep is idempotent, deleting nothing on a second
     * pass because the first already took what was stale.
     */
    LocalDate lastPurgeDay;

    /** Seconds between two examinations of the clock. */
    long tickSeconds = 60L;

    /** The examination loop, null until the node has started. */
    ScheduledExecutorService executor;

    /**
     * Starts the loop that watches the clock.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Entering method onStart");
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "period-close");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tickSafely, tickSeconds, tickSeconds, TimeUnit.SECONDS);
        LOGGER.info("Exiting method onStart");
    }

    /**
     * Runs one examination, swallowing whatever it fails on.
     *
     * <p>A database hiccup must not stop the node from trying again a minute
     * later: the window lasts several minutes on purpose.
     */
    void tickSafely() {
        try {
            tick();
        } catch (Exception failure) {
            LOGGER.warnf("Fermeture de fin de période impossible (%s)", failure.getMessage());
        }
    }

    /**
     * Leaves an order for every open register, when the clock has entered the
     * closing window.
     */
    @Transactional
    public void tick() {
        LOGGER.info("Entering method tick");
        LocalTime now = LocalTime.now();
        if (posSettingsService.periodAutoCloseEnabled() && inClosingWindow(now)) {
            List<CashSession> open = CashSession.list("status", CashSession.SessionStatus.OPEN);
            for (CashSession session : open) {
                order(session.terminalId);
            }
            LOGGER.info("Closing window: " + open.size() + " register(s) still open");
        }
        maybeSweep(now, DateTimeProvider.now().toLocalDate());
        LOGGER.info("Exiting method tick");
    }

    /**
     * Runs the data-retention sweep once, when the period has ended.
     *
     * <p>The sweep hooks onto this end-of-period loop rather than a parallel
     * scheduler, but it must not run WHILE the day closes: it waits until the
     * clock has reached the end of period (past the closing window that precedes
     * it), then runs once and stamps the day, so the minute-a-tick loop does not
     * launch it again. The two guards are the whole of it — nothing to sweep is
     * decided by the policies themselves, each inert while its parameter is zero.
     *
     * @param now the moment examined
     * @param today the day examined, from the freezable clock
     */
    void maybeSweep(LocalTime now, LocalDate today) {
        LOGGER.info("Entering method maybeSweep");
        if (today.equals(lastPurgeDay)) {
            LOGGER.info("Exiting method maybeSweep: already swept today");
            return;
        }
        if (now.isBefore(endOfPeriod())) {
            LOGGER.info("Exiting method maybeSweep: end of period not reached");
            return;
        }
        // Financial states are emitted BEFORE the retention sweep (§4): the purge
        // may carry away tickets a Z still needs, so the Z must be frozen first.
        financialStateEmissionService.emitEndOfPeriod(today);
        retentionPurgeService.sweep();
        lastPurgeDay = today;
        LOGGER.info("Exiting method maybeSweep: sweep run");
    }

    /**
     * Leaves the close-if-idle order for one register and journals it, unless
     * that register already carries one.
     *
     * @param terminalId the register still open
     */
    void order(String terminalId) {
        LOGGER.info("Entering method order with terminalId: " + terminalId);
        if (registerCommandService.pendingCount(terminalId) > 0) {
            LOGGER.info("Exiting method order: an order is already waiting");
            return;
        }
        registerCommandService.issue(terminalId, RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE,
                "Fin de période", "Fermeture automatique de fin de période");
        technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Fermeture de fin de période demandée à la caisse " + terminalId);
        LOGGER.info("Exiting method order");
    }

    /**
     * How many minutes are left before the period ends, for the back office's
     * countdown (BO-09-01-06).
     *
     * <p>Answers -1 rather than a figure when there is nothing to announce —
     * the automatic close disarmed, or the end of period still far off — so the
     * screen has one thing to test and the banner cannot appear all day.
     *
     * @return the minutes left, or -1 when the back office must say nothing
     */
    public long countdownMinutes() {
        LOGGER.info("Entering method countdownMinutes");
        if (!posSettingsService.periodAutoCloseEnabled()) {
            LOGGER.info("Exiting method countdownMinutes with -1");
            return -1L;
        }
        long minutes = minutesUntil(LocalTime.now(), endOfPeriod());
        LOGGER.info("Exiting method countdownMinutes with " + minutes);
        return minutes > ALERT_HORIZON_MINUTES ? -1L : minutes;
    }

    /**
     * The administered end of period as the screen shows it.
     *
     * @return the hour, formatted {@code HH:mm}
     */
    public String endOfPeriodLabel() {
        LOGGER.info("Entering method endOfPeriodLabel");
        LOGGER.info("Exiting method endOfPeriodLabel");
        return HOUR.format(endOfPeriod());
    }

    /**
     * Counts the minutes from one moment to another within the same day, the
     * count rolling over midnight rather than going negative.
     *
     * @param now the moment counted from
     * @param end the moment counted to
     * @return the minutes left, always positive or zero
     */
    static long minutesUntil(LocalTime now, LocalTime end) {
        long minutes = java.time.Duration.between(now, end).toMinutes();
        return minutes < 0 ? minutes + DAY_MINUTES : minutes;
    }

    /**
     * Says whether a moment falls in the lead window that precedes the end of
     * the period.
     *
     * <p>The window runs from the end of period MINUS the lead up to the end of
     * period itself. It may straddle midnight — a period ending at 00:15 with a
     * lead of thirty minutes opens at 23:45 the day before — so the wrapped
     * case is read the other way round, as « after the opening OR before the
     * end ».
     *
     * @param now the moment examined
     * @return true when the open registers must be asked to close
     */
    boolean inClosingWindow(LocalTime now) {
        LocalTime end = endOfPeriod();
        int lead = posSettingsService.periodCloseLeadMinutes();
        LocalTime opening = end.minusMinutes(Math.max(lead, 0));
        if (opening.equals(end)) {
            // A lead of zero leaves exactly the minute of the end of period,
            // which a tick-a-minute loop can still catch.
            return now.getHour() == end.getHour() && now.getMinute() == end.getMinute();
        }
        if (opening.isBefore(end)) {
            return !now.isBefore(opening) && now.isBefore(end);
        }
        return !now.isBefore(opening) || now.isBefore(end);
    }

    /**
     * Reads the administered end-of-period hour.
     *
     * <p>An unreadable row falls back on the catalog's own default rather than
     * on nothing: a mistyped hour must not silently disarm a closing the shop
     * believes is armed.
     *
     * @return the hour the period ends
     */
    LocalTime endOfPeriod() {
        String configured = posSettingsService.periodEndTime();
        try {
            return LocalTime.parse(configured.trim(), HOUR);
        } catch (Exception unreadable) {
            LOGGER.warnf("Heure de fin de période illisible (%s), valeur par défaut appliquée", configured);
            return LocalTime.parse(PosSettingsService.CATALOG.stream()
                    .filter(def -> "period.end-time".equals(def.key()))
                    .findFirst().orElseThrow().defaultValue(), HOUR);
        }
    }
}
