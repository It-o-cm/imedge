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

    /** The sales volumes of the direct-entry groups (BO-03-01-13). */
    @Inject SalesVolumeService salesVolumeService;

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
                order(session);
            }
            LOGGER.info("Closing window: " + open.size() + " register(s) still open");
        }
        maybeSweep(now, DateTimeProvider.now().toLocalDate());
        LOGGER.info("Exiting method tick");
    }

    /** The consolidated close of the point of sale (BO-03-12-02). */
    @Inject StorePeriodCloseService storePeriodCloseService;

    /** The programmed end-of-period editions (BO-09-01-10). */
    @jakarta.inject.Inject
    ScheduledReportService scheduledReportService;

    /** Which periods a day closes (BO-09-01-07). */
    @jakarta.inject.Inject
    PeriodScheduleService periodScheduleService;

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
        // BO-09-03-03: the treatment starts on its own only where the store
        // activated the automatic launch; otherwise it waits for the button
        // of the end-of-day screen, and the journal says it did not start.
        if (!posSettingsService.periodAutoLaunch()) {
            if (!today.equals(lastSkippedDay)) {
                technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                        "Fin de p\u00e9riode automatique d\u00e9sactiv\u00e9e : traitement du " + today + " non lanc\u00e9");
                lastSkippedDay = today;
            }
            LOGGER.info("Exiting method maybeSweep: automatic launch off");
            return;
        }
        treat(today, AUTOMATIC);
        LOGGER.info("Exiting method maybeSweep: sweep run");
    }

    /**
     * The day the disarmed automatic launch was last journalled, so a day
     * that does not start says so once (BO-09-03-03). The manual launch stays
     * possible that day: the day is not treated, only announced untreated.
     */
    LocalDate lastSkippedDay;

    /** Who the automatic end of period is, in the journal and on the rows. */
    static final String AUTOMATIC = "Fin de p\u00e9riode automatique";

    /** The technical entry points around the treatment (BO-09-02-04). */
    @Inject PeriodHookService periodHookService;

    /** The tickets left on hold at the close (BO-09-01-05, -08, BO-09-02-01). */
    @Inject ParkedTicketCloseService parkedTicketCloseService;

    /** The registers the check of their data is ordered to (BO-09-03-05). */
    @Inject com.intermarche.edge.service.sync.RegisterPresenceService registerPresenceService;

    /**
     * Runs the end-of-period treatment of a day by hand (BO-09-02-01 to -04,
     * BO-09-03-03): the same treatment the automatic end of period runs, once
     * a day — a day already treated, by hand or automatically, is not treated
     * again.
     *
     * @param day the day whose period ends
     * @param launchedBy who pressed the button
     * @return true when the treatment ran, false when the day was already treated
     */
    @Transactional
    public boolean runEndOfPeriod(LocalDate day, String launchedBy) {
        LOGGER.info("Entering method runEndOfPeriod with day: " + day);
        if (day.equals(lastPurgeDay)) {
            LOGGER.info("Exiting method runEndOfPeriod: already treated");
            return false;
        }
        treat(day, launchedBy);
        LOGGER.info("Exiting method runEndOfPeriod");
        return true;
    }

    /**
     * The end-of-period treatment of a day, in its order: the entry point
     * before, the tickets on hold, the registers' check, the financial states,
     * the consolidated close, the editions, the volumes, the retention sweep,
     * the periods closed, the entry point after.
     *
     * @param today the day whose period ends
     * @param launchedBy who launched it
     */
    void treat(LocalDate today, String launchedBy) {
        // BO-09-02-04: the store's own needs before the treatment.
        periodHookService.run(com.intermarche.edge.domain.session.PeriodHookRun.Phase.BEFORE, today, launchedBy);
        // BO-09-01-05, BO-09-02-01: the close goes through WITH the tickets
        // on hold, each written down; BO-09-01-08: reset where administered.
        parkedTicketCloseService.record(today, launchedBy);
        if (AUTOMATIC.equals(launchedBy) && posSettingsService.periodParkedRaz()) {
            parkedTicketCloseService.raz(null, launchedBy, today);
        }
        // BO-09-03-05: each register checks its data against the server's.
        if (posSettingsService.periodRegisterCheck()) {
            verifyRegisters(today);
        }
        // Financial states are emitted BEFORE the retention sweep (§4): the purge
        // may carry away tickets a Z still needs, so the Z must be frozen first.
        financialStateEmissionService.emitEndOfPeriod(today);
        // BO-03-12-02: the consolidated close of the point of sale, above the
        // sessions, chaining the administered actions under the calendar.
        storePeriodCloseService.consolidate(today);
        // BO-09-01-10: the programmed states are edited BEFORE the retention
        // sweep, for the reason the financial states are: the purge carries
        // away the tickets the states are made of, and a state edited after it
        // would state a day that has already been emptied.
        scheduledReportService.runEndOfPeriod(today);
        // BO-03-01-13: the sales volumes are counted before the sweep, which
        // carries away the tickets they are counted on.
        salesVolumeService.refresh(today);
        retentionPurgeService.sweep();
        // BO-09-01-07: which periods this day closes — the day always, the
        // week, the month and the year on the administered days. Journalised
        // and nothing more for now: what a week-end or a year-end adds to a
        // day-end is the consolidation nobody has specified yet.
        technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Fin de p\u00e9riode du " + today + " : " + periodScheduleService.endingOn(today));
        lastPurgeDay = today;
        // BO-09-02-04: the store's own needs after the treatment.
        periodHookService.run(com.intermarche.edge.domain.session.PeriodHookRun.Phase.AFTER, today, launchedBy);
    }

    /**
     * Orders every register the node knows to check its data of the day
     * against the server's (BO-09-03-05): the order carries what the server
     * holds of that register's day — its closed tickets and their total — and
     * a register that differs pushes its sales again and re-pulls its
     * referential. A deactivated register is left out.
     *
     * @param day the day checked
     */
    void verifyRegisters(LocalDate day) {
        for (com.intermarche.edge.service.sync.RegisterPresenceService.Presence presence
                : registerPresenceService.all()) {
            String terminal = presence.terminalId();
            if (deactivated(terminal)) {
                continue;
            }
            java.util.List<com.intermarche.edge.domain.sale.Ticket> tickets = closedTickets(terminal, day);
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            for (com.intermarche.edge.domain.sale.Ticket ticket : tickets) {
                total = total.add(ticket.totalIncludingTax == null ? java.math.BigDecimal.ZERO : ticket.totalIncludingTax);
            }
            registerCommandService.issue(terminal, RegisterCommand.CommandType.VERIFY_SYNC,
                    "Fin de p\u00e9riode", day + ";" + tickets.size() + ";" + total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                    "Contr\u00f4le caisse / serveur demand\u00e9 \u00e0 la caisse " + terminal + " : "
                            + tickets.size() + " ticket(s) re\u00e7u(s) par le serveur");
        }
    }

    /**
     * Leaves the end-of-period close order for one open session's register
     * and journals it, unless that register already carries a close order.
     *
     * @param session the session still open
     */
    void order(CashSession session) {
        String terminalId = session.terminalId;
        LOGGER.info("Entering method order with terminalId: " + terminalId);
        if (closePending(terminalId)) {
            LOGGER.info("Exiting method order: an order is already waiting");
            return;
        }
        RegisterCommand.CommandType type = closeOrderType(session);
        registerCommandService.issue(terminalId, type,
                "Fin de période", "Fermeture automatique de fin de période");
        technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Fermeture de fin de période demandée à la caisse " + terminalId
                        + (type == RegisterCommand.CommandType.CLOSE_SESSION_AUTO_DECLARE
                        ? " (déclaration automatique de l'hôte)" : ""));
        LOGGER.info("Exiting method order");
    }

    /**
     * Whether a register already carries an end-of-period close order.
     *
     * @param terminalId the register
     * @return true when a close order is pending
     */
    public boolean closePending(String terminalId) {
        return registerCommandService.isPending(terminalId, RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE)
                || registerCommandService.isPending(terminalId, RegisterCommand.CommandType.CLOSE_SESSION_AUTO_DECLARE);
    }

    /**
     * The close order an open session takes at the end of period
     * (BO-09-03-01): the automatic declaration of its host where the store
     * declares automatically the hosts of its self-service islands, the
     * session's register stands on such an island — one that declares the end
     * of period on its own and holds self-service registers — and the island
     * attaches the host who opened it; the close-if-idle order otherwise.
     *
     * @param session the session still open
     * @return the kind of close order
     */
    public RegisterCommand.CommandType closeOrderType(CashSession session) {
        if (!posSettingsService.periodScoAutoDeclaration()) {
            return RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE;
        }
        com.intermarche.edge.domain.store.CheckoutIsland island = islandOf(session.terminalId);
        if (island == null || !island.autoPeriodCloseDeclaration || !selfService(island)) {
            return RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE;
        }
        String badge = session.openingCashier == null ? null : session.openingCashier.badgeId;
        return island.attaches(badge)
                ? RegisterCommand.CommandType.CLOSE_SESSION_AUTO_DECLARE
                : RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE;
    }

    /**
     * The island a register stands on.
     *
     * @param terminalId the register
     * @return the island, or null
     */
    com.intermarche.edge.domain.store.CheckoutIsland islandOf(String terminalId) {
        return com.intermarche.edge.domain.store.CheckoutIsland.findByTerminal(terminalId);
    }

    /**
     * Whether the back office deactivated a register (BO-03-09-01).
     *
     * @param terminalId the register
     * @return true when deactivated
     */
    boolean deactivated(String terminalId) {
        return com.intermarche.edge.domain.store.CheckoutPoint.isDeactivated(terminalId);
    }

    /**
     * The tickets of a register the server holds closed on a day.
     *
     * @param terminalId the register
     * @param day the day
     * @return the tickets
     */
    java.util.List<com.intermarche.edge.domain.sale.Ticket> closedTickets(String terminalId, LocalDate day) {
        return com.intermarche.edge.domain.sale.Ticket.list(
                "terminalId = ?1 and status = ?2 and closingDate >= ?3 and closingDate < ?4", terminalId,
                com.intermarche.edge.domain.sale.Ticket.TicketStatus.CLOSED,
                day.atStartOfDay(), day.plusDays(1).atStartOfDay());
    }

    /**
     * The checkout point of a register.
     *
     * @param terminalId the register
     * @return the point, or null when undeclared
     */
    com.intermarche.edge.domain.store.CheckoutPoint pointOf(String terminalId) {
        return com.intermarche.edge.domain.store.CheckoutPoint.findByTerminal(terminalId);
    }

    /**
     * Whether an island is a self-service island: one of its registers is a
     * self-service checkout point.
     *
     * @param island the island
     * @return true when it holds a self-service register
     */
    boolean selfService(com.intermarche.edge.domain.store.CheckoutIsland island) {
        for (String terminal : island.terminals()) {
            com.intermarche.edge.domain.store.CheckoutPoint point = pointOf(terminal);
            if (point != null && point.isSelfService()) {
                return true;
            }
        }
        return false;
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
