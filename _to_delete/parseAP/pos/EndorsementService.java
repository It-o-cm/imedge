package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.ui.auth.AuthService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * Manager endorsement service: credential check and endorsement request
 * lifecycle.
 * <p>
 * Phase 2: the check verifies the MANAGER or ADMIN role (any active employee
 * used to pass), goes through the shared lockout-aware credential check, and
 * every granted or refused endorsement is journaled with its action code.
 * <p>
 * Consequences of sharing the credential check with the register unlock:
 * ONE failure counter per account (a manager burning attempts on an
 * endorsement locks their own login too — deliberate, one credential = one
 * lockout), and the endorsing manager does NOT need to be the logged-in
 * operator: a cashier stays logged in while a manager validates over their
 * shoulder, which is exactly the four-eyes gesture the pattern models. The
 * journal entry carries the ACTION CODE, so the audit trail says what was
 * endorsed, by whom, granted or refused.
 */
@ApplicationScoped
public class EndorsementService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EndorsementService.class);

    @Inject
    AuthService authService;

    @Inject
    TechnicalEventService technicalEventService;

    /** Tells the node a request was settled at the till (BO-04-02-03). */
    @Inject
    com.intermarche.pos.service.SupervisorSettleService supervisorSettleService;

    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** The operator messages the control points speak with (BO-10-02-01 to -10). */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /**
     * Whether a negative gesture may run WITHOUT opening the endorsement modal
     * (EXP-32/33): true when the gesture's administered flag is off, OR the logged
     * operator is themselves a supervisor (LC-01-05-07), OR a supervisor session is
     * currently open on the ticket (EXP-33). Otherwise the gesture must be endorsed.
     *
     * @param state the current POS state
     * @param gestureKey the gesture suffix (e.g. "cancel-line", "abandon", "refund")
     * @return true when the gesture is already authorized and needs no modal
     */
    public boolean gestureAuthorized(PosState state, String gestureKey) {
        LOGGER.info("Entering method gestureAuthorized with gestureKey: " + gestureKey);
        boolean authorized = !posSettingsService.gestureEndorsementRequired(gestureKey)
                || operatorIsSupervisor(state)
                || state.supervisorSessionBadge != null;
        LOGGER.info("Exiting method gestureAuthorized");
        return authorized;
    }

    /**
     * The single decision point for a gesture that ALWAYS needs an endorsement —
     * one carrying no administered on/off flag (training toggle, operator-badge
     * print): a modal is needed UNLESS the logged operator is themselves a
     * supervisor (P6, LC-01-05-07) or a supervisor session is open (EXP-33). A
     * connected supervisor never gets asked for their own aval.
     *
     * @param state the current POS state
     * @return true when the gesture is already authorized and needs no modal
     */
    public boolean selfAuthorized(PosState state) {
        LOGGER.info("Entering method selfAuthorized");
        boolean authorized = operatorIsSupervisor(state) || state.supervisorSessionBadge != null;
        LOGGER.info("Exiting method selfAuthorized");
        return authorized;
    }

    /**
     * Opens a supervisor session on the ticket when the administered scope is
     * PER_TICKET (EXP-33): the endorsing manager's badge stays attached to the sale
     * so the following guarded gestures proceed without re-asking, until FIN
     * SUPERVISEUR or the end of the ticket. A no-op in PER_OP mode.
     *
     * @param state the current POS state
     * @param endorserBadge the badge (or login) of the manager who just endorsed
     */
    public void openSupervisorSessionIfPerTicket(PosState state, String endorserBadge) {
        LOGGER.info("Entering method openSupervisorSessionIfPerTicket");
        if ("PER_TICKET".equals(posSettingsService.endorsementScope())) {
            state.supervisorSessionBadge = endorserBadge;
        }
        LOGGER.info("Exiting method openSupervisorSessionIfPerTicket");
    }

    /**
     * Ends an open supervisor session (EXP-33): the FIN SUPERVISEUR gesture.
     *
     * @param state the current POS state
     */
    public void endSupervisorSession(PosState state) {
        LOGGER.info("Entering method endSupervisorSession");
        state.supervisorSessionBadge = null;
        LOGGER.info("Exiting method endSupervisorSession");
    }

    /**
     * The administered level of a CONTROL POINT (BO-10-02-01 to -10), as the
     * back office states it and before anyone is looked at.
     *
     * @param point the control point
     * @return its administered level, its own default when the value cannot be
     *         read
     */
    public ControlLevel level(ControlPoint point) {
        LOGGER.info("Entering method level with point: " + point);
        ControlLevel level = ControlLevel.of(
                posSettingsService.controlLevel(point.getSettingKey()),
                point.getDefaultLevel());
        LOGGER.info("Exiting method level");
        return level;
    }

    /**
     * What a control point does HERE AND NOW, the operator included
     * (BO-10-02-01 to -10).
     * <p>
     * The administered level, with ONE resolution applied: a gesture awaiting a
     * supervisor's endorsement needs none when a supervisor is the one
     * operating (LC-01-05-07) or when a supervisor session is open on the
     * ticket (EXP-33) — the same shortcut {@link #selfAuthorized} already gives
     * every other guarded gesture, said once here so that eight call sites do
     * not each say it their own way.
     * <p>
     * A refusal is NOT resolved away: {@link ControlLevel#BLOCK} means the shop
     * decided the gesture does not happen at this register, and a supervisor
     * standing there does not change that.
     *
     * @param state the current POS state
     * @param point the control point
     * @return what the caller must do, never null
     */
    public ControlLevel check(PosState state, ControlPoint point) {
        LOGGER.info("Entering method check with point: " + point);
        ControlLevel level = level(point);
        if (level == ControlLevel.ENDORSE && selfAuthorized(state)) {
            LOGGER.info("Exiting method check");
            return ControlLevel.NONE;
        }
        if (level == ControlLevel.ENDORSE && !point.isEndorsable()) {
            // Said once, here: a point that fires where no ceremony exists
            // refuses rather than promising an aval nobody can give.
            LOGGER.info("Exiting method check");
            return ControlLevel.BLOCK;
        }
        LOGGER.info("Exiting method check");
        return level;
    }

    /**
     * Whether the drawer may open OUTSIDE a sale (BO-10-02-04), and tells the
     * operator when it may not.
     * <p>
     * Two screens open the drawer without settling anything — the register
     * taken over at the lock screen, and the Z count — and both ask this one
     * question rather than each writing the control their own way. The point is
     * not endorsable, so a shop administering an aval gets a refusal here; see
     * {@link ControlPoint#isEndorsable()}.
     *
     * @param state the current POS state
     * @return true when the drawer may open
     */
    public boolean drawerOutOfSaleAllowed(PosState state) {
        LOGGER.info("Entering method drawerOutOfSaleAllowed");
        ControlLevel level = check(state, ControlPoint.DRAWER_OUT_OF_SALE);
        if (level == ControlLevel.WARN) {
            state.ticket.setWarning(controlMessage(ControlPoint.DRAWER_OUT_OF_SALE, level));
        } else if (!level.allows()) {
            state.ticket.setError(controlMessage(ControlPoint.DRAWER_OUT_OF_SALE, level));
        }
        LOGGER.info("Exiting method drawerOutOfSaleAllowed");
        return level.allows();
    }

    /**
     * The sentence the operator reads when a control point speaks
     * (BO-10-02-01 to -10).
     *
     * <p>Two wordings and eight gestures, not sixteen sentences: the gesture
     * names itself and the level says what happened to it. An operator reads
     * the same two sentences wherever a control fires, which is what makes them
     * recognisable at a glance.
     *
     * @param point the control point speaking
     * @param level what it decided
     * @return the sentence, never null
     */
    public String controlMessage(ControlPoint point, ControlLevel level) {
        LOGGER.info("Entering method controlMessage with point: " + point + ", level: " + level);
        String sentence = i18n.format(
                level == ControlLevel.BLOCK ? "op.controleRefus" : "op.controleAlerte",
                i18n.get(point.getLabelKey()));
        LOGGER.info("Exiting method controlMessage");
        return sentence;
    }

    /**
     * Verifies that the presented credentials belong to an active employee
     * holding the MANAGER or ADMIN role, PIN lockout enforced.
     *
     * @param login the badge id or login name
     * @param password the raw PIN
     * @return true if the credentials are valid and the role is sufficient
     */
    public boolean isManager(String login, String password) {
        LOGGER.info("Entering method isManager with login: " + login + ", password: ***");
        AuthService.CredentialStatus status = authService.checkCredentials(login, password);
        LOGGER.info("Exiting method isManager");
        return status.isSuccess()
                && (status.employee.role == Employee.EmployeeRole.MANAGER
                    || status.employee.role == Employee.EmployeeRole.ADMIN);
    }

    /**
     * Whether a role may endorse a guarded gesture ({@code BO-04-02-01}).
     *
     * <p>ADMINISTERED, where it used to be written in this method: a shop
     * that wants its head cashiers to endorse a line cancellation names
     * their role instead of asking for a release. The two roles that
     * endorsed before the list existed remain the default, so a shop that
     * administers nothing behaves exactly as it did.
     *
     * <p>A list naming nothing readable falls back on those two rather than
     * forbidding everything: an administered list is a typo away from
     * locking a shop out of its own endorsements, and the fallback is what
     * makes that typo survivable.
     *
     * @param role the role of the holder presenting their credentials
     * @return true when that role endorses
     */
    boolean endorses(Employee.EmployeeRole role) {
        if (role == null) {
            return false;
        }
        String administered = posSettingsService == null ? null
                : posSettingsService.endorsementRoles();
        if (administered == null || administered.isBlank()) {
            return role == Employee.EmployeeRole.MANAGER || role == Employee.EmployeeRole.ADMIN;
        }
        boolean named = false;
        for (String part : administered.split(";")) {
            String typed = part.trim();
            if (typed.isEmpty()) {
                continue;
            }
            named = true;
            if (typed.equalsIgnoreCase(role.name())) {
                return true;
            }
        }
        // A list that names nothing readable is not a list that forbids
        // everything: it is a typo, and a typo must not lock a shop out of
        // its own supervisor endorsements.
        return !named
                && (role == Employee.EmployeeRole.MANAGER
                    || role == Employee.EmployeeRole.ADMIN);
    }

    /**
     * The back office's AUTHORISATION of the gesture this lane is holding
     * ({@code BO-04-02-02}), journalled the moment it is consumed, or null
     * when nobody authorised anything.
     *
     * <p>Journalled HERE and not when the order landed, because the journal
     * of a register states what the register DID: an authorisation nobody
     * used is a message, not an endorsement. Consuming it leaves the same
     * event a typed code leaves, naming the back office instead of a badge.
     *
     * @param state the current POS state, possibly null
     * @return who granted it, or null when nobody did
     */
    public String remotelyGranted(PosState state) {
        LOGGER.info("Entering method remotelyGranted");
        String granted = state == null || state.endorsement == null
                ? null : state.endorsement.remoteGrant;
        if (granted != null && !granted.isBlank()) {
            technicalEventService.log(TechnicalEvent.EventType.ENDORSEMENT_GRANTED,
                    "Autorisation accordée par le back-office (" + granted.trim() + ")");
            LOGGER.info("Exiting method remotelyGranted");
            return granted.trim();
        }
        LOGGER.info("Exiting method remotelyGranted");
        return null;
    }

    /**
     * Verifies the credentials for an endorsement and journals the outcome
     * with the endorsed action code and the presented login.
     *
     * @param login the badge id or login name
     * @param password the raw PIN
     * @param actionCode the action code requiring authorization
     * @return true if the endorsement is granted
     */
    public boolean authorize(String login, String password, String actionCode) {
        LOGGER.info("Entering method authorize with login: " + login + ", password: ***" + ", actionCode: " + actionCode);
        boolean granted = authorizeOutcome(login, password, actionCode)
                == AuthService.CheckOutcome.GRANTED;
        LOGGER.info("Exiting method authorize");
        return granted;
    }

    /**
     * Verifies the credentials for an endorsement and journals the outcome,
     * telling a REFUSAL from a LOCKOUT.
     *
     * <p>Same check and same journal entry as {@link #authorize}, which delegates
     * here: this form exists for the screens that must name the lockout instead of
     * answering "autorisation refusée" to a supervisor whose account is blocked —
     * the refusal that cannot be acted upon by retyping.
     *
     * @param login the badge id or login name
     * @param password the raw PIN
     * @param actionCode the action code requiring authorization
     * @return which of the three outcomes the presented credentials got
     */
    public AuthService.CheckOutcome authorizeOutcome(String login, String password, String actionCode) {
        LOGGER.info("Entering method authorizeOutcome with login: " + login + ", password: ***" + ", actionCode: " + actionCode);
        AuthService.CredentialStatus status = authService.checkCredentials(login, password);
        boolean granted = status.isSuccess() && endorses(status.employee.role);
        // BO-04-01-39: name the endorsing operator in the first-class badge
        // column, not only inside the free-text detail — otherwise the journal's
        // "N° caissière" range filter (matched on operatorBadgeId) never returns
        // an endorsement event. A badge-based endorsement then filters exactly;
        // one keyed by login name carries that login as its identifier.
        technicalEventService.log(
                granted ? TechnicalEvent.EventType.ENDORSEMENT_GRANTED
                        : TechnicalEvent.EventType.ENDORSEMENT_DENIED,
                actionCode + " par " + (login != null ? login : "?"),
                login);
        if (granted && supervisorSettleService != null) {
            // BO-04-02-03: a supervisor settled it HERE, so the back office
            // marks the demand « déjà traitée en caisse » rather than sending
            // somebody to a lane that no longer needs anyone. Fire and forget:
            // the endorsement is already granted and journalled.
            supervisorSettleService.settledAtTill();
        }
        LOGGER.info("Exiting method authorizeOutcome");
        if (granted) {
            return AuthService.CheckOutcome.GRANTED;
        }
        // A LOCKED account that presents the right PIN is still locked: the
        // lockout wins over the credential, and over the role — an account under
        // lockout is not told whether it would have been manager enough.
        return status.locked
                ? AuthService.CheckOutcome.LOCKED : AuthService.CheckOutcome.DENIED;
    }

    /**
     * Opens an endorsement request for the given action.
     *
     * @param state the current POS state
     * @param actionCode the action code requiring authorization
     */
    public void requestAuthorization(PosState state, String actionCode) {
        LOGGER.info("Entering method requestAuthorization with state: " + state + ", actionCode: " + actionCode);
        state.endorsement.request(actionCode);
        state.touch();
        LOGGER.info("Exiting method requestAuthorization");
    }

    /**
     * Opens an endorsement request for a gesture that carries a form and
     * belongs to a screen of its own.
     *
     * <p>This is what lets the drawer screens use the shared modal instead of
     * their own badge and PIN boxes: the typed form waits here, inert, and the
     * dispatch replays it once a manager credential passes.
     *
     * @param state the current POS state
     * @param actionCode the action code requiring authorization
     * @param form the gesture's fields, copied as parked values
     * @param returnPath where to land once the gesture ran
     */
    public void requestAuthorization(PosState state, String actionCode,
            java.util.Map<String, String> form, String returnPath) {
        LOGGER.info("Entering method requestAuthorization with state: " + state + ", actionCode: " + actionCode
                + ", returnPath: " + returnPath);
        state.endorsement.request(actionCode, form, returnPath);
        state.touch();
        LOGGER.info("Exiting method requestAuthorization");
    }

    /**
     * Returns true when the LOGGED operator already holds a supervising role
     * (MANAGER or ADMIN, the same roles that validate endorsements) — the
     * connected-supervisor shortcut of LC-01-05-07. Looked up live in the
     * referential: a role revoked by a pull is effective immediately.
     *
     * @param state the current POS state
     * @return true when the logged operator can approve without a credential
     */
    public boolean operatorIsSupervisor(PosState state) {
        LOGGER.info("Entering method operatorIsSupervisor with state: " + state);
        Long operatorId = state.auth.getOperatorId();
        if (operatorId == null) {
            LOGGER.info("Exiting method operatorIsSupervisor");
            return false;
        }
        com.intermarche.pos.domain.people.Employee operator =
                com.intermarche.pos.domain.people.Employee.findById(operatorId);
        LOGGER.info("Exiting method operatorIsSupervisor");
        return operator != null && operator.active
                && (operator.role == com.intermarche.pos.domain.people.Employee.EmployeeRole.MANAGER
                    || operator.role == com.intermarche.pos.domain.people.Employee.EmployeeRole.ADMIN);
    }

    /**
     * Opens an endorsement request for a price modification.
     *
     * @param state the current POS state
     * @param type the modification type (REMISE, DISCOUNT, FORCE_PRICE)
     * @param uid the uid of the targeted ticket line
     * @param value the modification value (euros or percent depending on the type)
     */
    public void requestPriceModification(PosState state, PriceModType type, String uid, BigDecimal value) {
        LOGGER.info("Entering method requestPriceModification with state: " + state + ", type: " + type + ", uid: " + uid + ", value: " + value);
        state.endorsement.requestPriceModification(type, uid, value);
        state.touch();
        LOGGER.info("Exiting method requestPriceModification");
    }

    /**
     * Clears the current endorsement request.
     *
     * @param state the current POS state
     */
    public void clearRequest(PosState state) {
        LOGGER.info("Entering method clearRequest with state: " + state);
        state.endorsement.clear();
        state.touch();
        LOGGER.info("Exiting method clearRequest");
    }
}
