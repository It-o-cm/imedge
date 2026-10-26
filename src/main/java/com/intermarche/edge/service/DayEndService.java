package com.intermarche.edge.service;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.service.sync.RegisterCommandService;
import com.intermarche.edge.service.sync.RegisterPresenceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The END OF DAY as the back office sees it and launches it
 * ({@code BO-09-01-04} to {@code -08}, {@code BO-09-02-01} to {@code -03},
 * {@code BO-09-03-03}).
 *
 * <p>Until here the only end of day was the register's own Z: the node
 * closed the lanes left open at the approach of the period, and said nothing
 * to anybody. The three things the requirements ask the back office to WATCH
 * — a lane still open, a lane gone quiet, a cart left parked — were each
 * knowable and none was shown, and the manual launch did not exist at all.
 *
 * <p>ONE PICTURE, TWO ROADS. The automatic end of period
 * ({@link PeriodCloseService}) and the manual launch of this service issue
 * the SAME order to the SAME registers; what differs is who decided and
 * when. So the picture is computed once, here, and both the screen and the
 * launch read it — the alert a manager acts on and the state the launch acts
 * on can never disagree.
 *
 * <p>Everything is JOURNALLED, as the four requirements each demand in their
 * last line: the launch, the lanes it ordered closed, the lanes it found
 * silent and the carts it found parked.
 */
@ApplicationScoped
public class DayEndService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DayEndService.class);

    /** The horizon used when the administered one is absent or absurd. */
    static final int DEFAULT_ALERT_MINUTES = 60;

    /** The administered parameters. */
    @Inject
    PosSettingsService posSettingsService;

    /** The order book the close orders are left in. */
    @Inject
    RegisterCommandService registerCommandService;

    /** Who is still speaking to this node. */
    @Inject
    RegisterPresenceService registerPresenceService;

    /** The electronic journal every operation is written to. */
    @Inject
    TechnicalEventService technicalEventService;

    /** The end-of-period clock, shared with the automatic close. */
    @Inject
    PeriodCloseService periodCloseService;

    /** The tickets still on hold (BO-09-01-05, -08). */
    @Inject
    ParkedTicketCloseService parkedTicketCloseService;

    /**
     * One register still open at the moment the picture was taken.
     *
     * @param terminalId the register's identifier
     * @param sessionNumber the open session's number
     * @param cashier who opened it, or an empty string
     * @param session the open session, for the close order it takes
     */
    public record OpenRegister(String terminalId, String sessionNumber, String cashier, CashSession session) {
    }

    /**
     * One cart left parked on a register.
     *
     * @param terminalId the register it waits on
     * @param ticketNumber the cart's number
     * @param cashier who parked it, or an empty string
     * @param amount the cart's total so far, as text
     */
    public record ParkedCart(String terminalId, String ticketNumber, String cashier,
                             String amount) {
    }

    /**
     * What the back office must see before an end of day.
     *
     * @param autoEnabled whether the automatic close is armed
     * @param endOfPeriod the administered end of period, {@code HH:mm}
     * @param countdownMinutes minutes left before it, or -1 when too far off
     * @param alerting whether the countdown has entered the alert horizon
     * @param openRegisters the lanes still open
     * @param offlineRegisters the lanes gone quiet
     * @param parkedCarts the carts left parked
     */
    public record DayEndState(boolean autoEnabled, String endOfPeriod, long countdownMinutes,
                              boolean alerting, List<OpenRegister> openRegisters,
                              List<RegisterPresenceService.Presence> offlineRegisters,
                              List<ParkedCart> parkedCarts) {

        /**
         * Whether anything at all asks for a manager's attention.
         *
         * @return true when a lane is open, silent, or holds a parked cart
         */
        public boolean hasAlert() {
            return !openRegisters.isEmpty() || !offlineRegisters.isEmpty()
                    || !parkedCarts.isEmpty();
        }
    }

    /**
     * Takes the picture: what is open, what is silent, what is parked, and
     * how long is left.
     *
     * @return the state of the day's end
     */
    @Transactional
    public DayEndState state() {
        LOGGER.info("Entering method state");
        List<OpenRegister> open = new ArrayList<>();
        for (CashSession session
                : CashSession.<CashSession>list("status", CashSession.SessionStatus.OPEN)) {
            // BO-03-09-01: a deactivated register is left out of the closure control.
            if (com.intermarche.edge.domain.store.CheckoutPoint.isDeactivated(session.terminalId)) {
                continue;
            }
            open.add(new OpenRegister(
                    session.terminalId == null ? "" : session.terminalId,
                    session.sessionNumber == null ? "" : session.sessionNumber,
                    displayed(session.openingCashier), session));
        }
        // BO-09-01-05, -08: the carts on hold as the node knows them — the
        // parked rows and the tickets whose chain record puts them on hold.
        List<ParkedCart> parked = new ArrayList<>();
        for (ParkedTicketCloseService.Pending ticket : parkedTicketCloseService.pending(null)) {
            parked.add(new ParkedCart(ticket.terminalId(),
                    ticket.ticketNumber() == null ? "" : ticket.ticketNumber(),
                    ticket.cashier(),
                    ticket.amount() == null ? "" : ticket.amount().toPlainString()));
        }
        long countdown = periodCloseService.countdownMinutes();
        DayEndState state = new DayEndState(
                posSettingsService.periodAutoCloseEnabled(),
                periodCloseService.endOfPeriodLabel(),
                countdown,
                countdown >= 0 && countdown <= alertMinutes(),
                open,
                registerPresenceService.offline(),
                parked);
        LOGGER.info("Exiting method state");
        return state;
    }

    /**
     * The name the end-of-day picture shows for a cashier. The picture fills
     * every column it does not know with an empty string (BO-09-01-05/-06),
     * and an employee whose first name is blank is not shown the separator
     * alone: the full name is trimmed of whatever a blank half leaves at its
     * edge.
     *
     * @param cashier the employee who opened the session or parked the cart,
     *                or null
     * @return the trimmed full name, empty when there is none
     */
    private String displayed(Employee cashier) {
        return cashier == null ? "" : cashier.getFullName().strip();
    }

    /**
     * Launches the end of day by hand ({@code BO-09-02-01} to {@code -03}).
     *
     * <p>Leaves the SAME close-if-idle order the automatic end of period
     * leaves, and for the same reason: a lane halfway through a customer's
     * shopping is not closed under them, it takes the order when it is at
     * rest. The manual launch is therefore not a harder close — it is the
     * same close, decided earlier by somebody who was looking at the screen.
     *
     * <p>A register already carrying an order is not ordered twice, which is
     * what makes the button safe to press again.
     *
     * @param operator who pressed the button
     * @return the picture taken at the moment of the launch
     */
    @Transactional
    public DayEndState launch(String operator) {
        LOGGER.info("Entering method launch with operator: " + operator);
        DayEndState state = state();
        String who = operator == null || operator.isBlank() ? "back-office" : operator.trim();
        technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Fin de journée lancée manuellement par " + who + " : "
                        + state.openRegisters().size() + " caisse(s) ouverte(s), "
                        + state.offlineRegisters().size() + " déconnectée(s), "
                        + state.parkedCarts().size() + " ticket(s) en attente");
        for (OpenRegister register : state.openRegisters()) {
            order(register, who);
        }
        for (RegisterPresenceService.Presence silent : state.offlineRegisters()) {
            technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                    "Fin de journée : caisse " + silent.terminalId() + " déconnectée depuis "
                            + silent.silentMinutes() + " min, ordre laissé en attente");
        }
        for (ParkedCart cart : state.parkedCarts()) {
            technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                    "Fin de journée : ticket en attente " + cart.ticketNumber()
                            + " sur la caisse " + cart.terminalId());
        }
        LOGGER.info("Exiting method launch");
        return state;
    }

    /**
     * Leaves the end-of-day close order for one register — the close-if-idle
     * one, or the automatic declaration of a self-service island's host
     * (BO-09-03-01) — unless it already carries a close order.
     *
     * @param register the register still open
     * @param operator who asked for it
     */
    private void order(OpenRegister register, String operator) {
        String terminalId = register.terminalId();
        if (terminalId.isBlank() || periodCloseService.closePending(terminalId)) {
            return;
        }
        registerCommandService.issue(terminalId, periodCloseService.closeOrderType(register.session()),
                operator, "Fin de journée lancée depuis le back-office");
        technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Fin de journée : fermeture demandée à la caisse " + terminalId);
    }

    /**
     * How long before the end of period the alerts start.
     *
     * <p>An absent or absurd horizon answers an hour rather than zero: a
     * horizon of zero would show the alerts for one minute a day, which is
     * the same as not showing them.
     *
     * @return the horizon in minutes, always strictly positive
     */
    int alertMinutes() {
        int minutes = posSettingsService.alertCountdownMinutes();
        return minutes > 0 ? minutes : DEFAULT_ALERT_MINUTES;
    }
}
