package com.intermarche.pos.ui.auth;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;

/**
 * Authentication of operators at the register.
 * <p>
 * Phase 2: the register unlock and the manager endorsement both go through
 * the shared {@link #checkCredentials(String, String)} with PIN lockout —
 * after {@code pos.auth.max-attempts} consecutive failures the account is
 * locked for {@code pos.auth.lockout-minutes} minutes and the lockout is
 * journaled. The two flows therefore share ONE failure counter: a manager
 * burning attempts on an endorsement locks their register login too, which
 * is deliberate (one credential, one lockout). Exception: the PIN change
 * verifies the current PIN directly, outside the lockout.
 * <p>
 * Lockout mechanics worth knowing: locking RESETS the failure counter, so
 * the window after a lockout expires starts clean; a successful PIN resets
 * both counter and lock; and the pair (failedAttempts, lockedUntil) is
 * LOCAL operational state — the phase 6 referential pull never touches it,
 * a lock neither spreads to other registers nor gets reset by a sync.
 * <p>
 * {@code logout} clears the in-memory cart but NOT the persisted draft: a
 * lock in mid-sale abandons the sale (empty screen at next login), and the
 * stale OPEN draft is cancelled by the recovery of the next register
 * restart — the single-draft invariant absorbs it as a documented sequence
 * gap.
 */
@ApplicationScoped
public class AuthService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AuthService.class);


    /**
     * The localised operator messages: every sentence this class shows the
     * operator is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** Number of consecutive PIN failures triggering a lockout. */
    @ConfigProperty(name = "pos.auth.max-attempts", defaultValue = "3")
    int maxAttempts;

    /** Lockout duration in minutes after repeated PIN failures. */
    @ConfigProperty(name = "pos.auth.lockout-minutes", defaultValue = "5")
    int lockoutMinutes;

    @Inject
    TechnicalEventService technicalEventService;

    /** The administered parameters governing how the register opens. */
    @Inject
    PosSettingsService posSettingsService;

    /** Outcome of a login attempt. */
    public enum LoginResult {
        /** Credentials valid; the operator is logged in. */
        SUCCESS,
        /** Credentials invalid (unknown account or wrong PIN). */
        INVALID,
        /** Account locked after repeated PIN failures. */
        LOCKED
    }

    /**
     * Outcome of a credential check made OUTSIDE the login — an endorsement, a
     * closing password — where the three cases must be told apart by the screen.
     *
     * <p>THE LOCKOUT IS THE ONE THAT MATTERS. A boolean collapses {@link #DENIED}
     * and {@link #LOCKED} into one refusal, and the screen then tells an operator
     * their code is wrong while the register is in fact refusing a code that is
     * right: they retype it, correctly, until they give up. The lockout has to be
     * NAMED, or it reads as a definitive block.
     */
    public enum CheckOutcome {
        /** Credentials valid and sufficient for what was asked. */
        GRANTED,
        /** Credentials invalid, or valid without the required role. */
        DENIED,
        /** Account locked after repeated PIN failures. */
        LOCKED
    }

    /**
     * Result of a credential check: the authenticated employee on success,
     * and a locked flag when the account is under a PIN lockout.
     */
    public static final class CredentialStatus {
        /** The authenticated employee, or null when the check failed. */
        public final Employee employee;
        /** True when the account is currently locked out. */
        public final boolean locked;

        /**
         * Creates a credential status.
         *
         * @param employee the authenticated employee, or null
         * @param locked true when the account is locked out
         */
        private CredentialStatus(Employee employee, boolean locked) {
            this.employee = employee;
            this.locked = locked;
        }

        /**
         * Indicates whether the check succeeded.
         *
         * @return true when an employee was authenticated
         */
        public boolean isSuccess() {
            return employee != null;
        }
    }

    /**
     * Verifies a login / PIN pair with lockout enforcement: a locked account
     * is refused without checking the PIN; a wrong PIN increments the failure
     * counter and locks the account after the configured threshold (journaled);
     * a correct PIN resets the counter.
     *
     * @param loginInfo the login identifier (badge id or login name)
     * @param rawPassword the raw PIN entered
     * @return the credential status (employee on success, locked flag)
     */
    @Transactional
    public CredentialStatus checkCredentials(String loginInfo, String rawPassword) {
        LOGGER.info("Entering method checkCredentials with loginInfo: " + loginInfo + ", rawPassword: ***");
        if (loginInfo == null || rawPassword == null) {
            LOGGER.info("Exiting method checkCredentials");
            return new CredentialStatus(null, false);
        }
        Employee employee = Employee.findActiveLogin(loginInfo.toLowerCase());
        if (employee == null) {
            LOGGER.info("Exiting method checkCredentials");
            return new CredentialStatus(null, false);
        }
        if (employee.isCurrentlyLocked()) {
            LOGGER.info("Exiting method checkCredentials");
            return new CredentialStatus(null, true);
        }
        if (employee.verifyPassword(rawPassword)) {
            employee.failedAttempts = 0;
            employee.lockedUntil = null;
            employee.persist();
            LOGGER.info("Exiting method checkCredentials");
            return new CredentialStatus(employee, false);
        }
        employee.failedAttempts++;
        technicalEventService.log(TechnicalEvent.EventType.PASSWORD_FAILED, null, employee.badgeId);
        boolean nowLocked = false;
        if (employee.failedAttempts >= maxAttempts) {
            employee.lockedUntil = LocalDateTime.now().plusMinutes(lockoutMinutes);
            employee.failedAttempts = 0;
            nowLocked = true;
            technicalEventService.log(TechnicalEvent.EventType.AUTH_LOCKED,
                    loginInfo + " (" + lockoutMinutes + " min)", employee.badgeId);
        }
        employee.persist();
        LOGGER.info("Exiting method checkCredentials");
        return new CredentialStatus(null, nowLocked);
    }

    /**
     * Authenticates an employee from a login and a raw PIN, updating the
     * session on success.
     *
     * @param state the current POS state
     * @param loginInfo the login identifier (badge id or login name)
     * @param rawPassword the raw PIN entered
     * @return the login outcome (success, invalid credentials, or locked account)
     */
    /** The action code a supervisor-forced register unlock is endorsed under (EXP-14). */
    public static final String FORCE_UNLOCK_ACTION = "REGISTER_FORCE_UNLOCK";

    public LoginResult login(PosState state, String loginInfo, String rawPassword) {
        LOGGER.info("Entering method login with state: " + state + ", loginInfo: " + loginInfo + ", rawPassword: ***");
        if (state.auth.isPaused()) {
            // A paused register RESUMES rather than logs in: only the operator who
            // verrouilla may unlock (EXP-13), and the in-memory ticket survives.
            LOGGER.info("Exiting method login");
            return resume(state, loginInfo, rawPassword);
        }
        if (badgeOpensAlone(state, loginInfo, rawPassword)) {
            Employee employee = Employee.findActiveLogin(loginInfo);
            if (employee != null) {
                state.auth.login(employee.id, employee.getFullName(), employee.badgeId);
                applyPasswordExpiry(state, employee);
                applyDedicatedReturnRegister(state);
                technicalEventService.log(TechnicalEvent.EventType.REGISTER_UNLOCKED,
                        null, employee.badgeId);
                LOGGER.info("Exiting method login");
                return LoginResult.SUCCESS;
            }
            LOGGER.info("Exiting method login");
            return LoginResult.INVALID;
        }
        CredentialStatus status = checkCredentials(loginInfo, rawPassword);
        if (status.isSuccess()) {
            state.auth.login(status.employee.id, status.employee.getFullName(),
                    status.employee.badgeId);
            applyPasswordExpiry(state, status.employee);
            applyDedicatedReturnRegister(state);
            technicalEventService.log(TechnicalEvent.EventType.REGISTER_UNLOCKED,
                    null, status.employee.badgeId);
            LOGGER.info("Exiting method login");
            return LoginResult.SUCCESS;
        }
        LOGGER.info("Exiting method login");
        return status.locked ? LoginResult.LOCKED : LoginResult.INVALID;
    }

    /**
     * Forces MODE RETOUR on a register the back office dedicated to returns
     * (LC-05-01-03): the mode is permanent there, so an operator signing in
     * finds the till already taking articles back rather than pressing a key the
     * register would refuse to un-press.
     *
     * @param state the current POS state
     */
    private void applyDedicatedReturnRegister(PosState state) {
        LOGGER.info("Entering method applyDedicatedReturnRegister");
        if (posSettingsService.returnDedicatedRegister()) {
            state.returnMode = true;
        }
        LOGGER.info("Exiting method applyDedicatedReturnRegister");
    }


    /**
     * Resumes a PAUSED register (déverrouillage, EXP-12/13): only the operator
     * who verrouilla may unlock, and only their password is checked — the login
     * is fixed to their badge on the screen. On success the operator is restored
     * (the in-memory ticket kept) and the reprise is journaled.
     *
     * @param state the current POS state (paused)
     * @param loginInfo the presented identifier, expected to be the locker's badge
     * @param rawPassword the raw PIN entered
     * @return the resume outcome (success, invalid, or locked account)
     */
    LoginResult resume(PosState state, String loginInfo, String rawPassword) {
        LOGGER.info("Entering method resume with loginInfo: " + loginInfo);
        if (loginInfo == null
                || !loginInfo.equalsIgnoreCase(state.auth.lockedOperatorBadgeId)) {
            // Another operator cannot lift a colleague's pause: only a supervisor
            // forcing (forceResume) may, and that is a different gesture.
            LOGGER.info("Exiting method resume");
            return LoginResult.INVALID;
        }
        CredentialStatus status = checkCredentials(loginInfo, rawPassword);
        if (status.isSuccess()) {
            state.auth.login(status.employee.id, status.employee.getFullName(),
                    status.employee.badgeId);
            applyPasswordExpiry(state, status.employee);
            technicalEventService.log(TechnicalEvent.EventType.REGISTER_UNLOCKED,
                    null, status.employee.badgeId);
            LOGGER.info("Exiting method resume");
            return LoginResult.SUCCESS;
        }
        LOGGER.info("Exiting method resume");
        return status.locked ? LoginResult.LOCKED : LoginResult.INVALID;
    }

    /**
     * VERROUILLE (pauses) the register (EXP-12): the operator identity and the
     * in-memory ticket survive, the pause is journaled like the idle one, and the
     * drawer is not touched. Nothing is written when no operator is signed in.
     *
     * @param state the current POS state
     */
    public void pause(PosState state) {
        LOGGER.info("Entering method pause with state: " + state);
        if (state.auth.operatorBadgeId != null) {
            technicalEventService.log(TechnicalEvent.EventType.REGISTER_LOCKED,
                    null, state.auth.operatorBadgeId);
        }
        state.auth.pause();
        LOGGER.info("Exiting method pause");
    }

    /**
     * Demands a new PIN when the operator's secret has passed the administered
     * delay (BO-01-02-12).
     *
     * <p>Called at every way IN — badge, keyed credentials, reprise — and not
     * at the forced reprise, which already demands a change for its own
     * reason. The demand is the SAME one EXP-14 raises, so the cashier meets
     * the screen they already know.
     *
     * <p>A register assembled without a settings service keeps the post open:
     * a parameter that cannot be read must not stand between a cashier and
     * their till.
     *
     * @param state the register state, already logged in
     * @param employee the operator who just took the post
     */
    private void applyPasswordExpiry(PosState state, Employee employee) {
        if (posSettingsService == null || employee == null) {
            return;
        }
        if (employee.secretExpired(posSettingsService.passwordExpiryDays())) {
            state.auth.passwordChangeRequired = true;
        }
    }

    /**
     * Forces the reprise of a paused register whose cashier forgot their password
     * (EXP-14): a supervisor endorses the unlock, the cashier keeps their session
     * and drawer (the in-memory ticket too), and a PIN change is imposed at the
     * reprise ({@code passwordChangeRequired}). No credential of the cashier is
     * checked — the supervisor's endorsement stands in for it.
     *
     * @param state the current POS state (paused)
     * @return true when the register was paused and the reprise ran, false when it
     *         was not paused or the locked operator could not be resolved
     */
    public boolean forceResume(PosState state) {
        LOGGER.info("Entering method forceResume with state: " + state);
        if (!state.auth.isPaused()) {
            LOGGER.info("Exiting method forceResume");
            return false;
        }
        Employee operator = Employee.findById(state.auth.lockedOperatorId);
        if (operator == null) {
            LOGGER.info("Exiting method forceResume");
            return false;
        }
        state.auth.login(operator.id, operator.getFullName(), operator.badgeId);
        state.auth.passwordChangeRequired = true;
        technicalEventService.log(TechnicalEvent.EventType.REGISTER_UNLOCKED,
                "forçage superviseur", operator.badgeId);
        LOGGER.info("Exiting method forceResume");
        return true;
    }

    /**
     * Whether this attempt is a badge opening the register on its own
     * (LC-01-01-03).
     *
     * <p>THREE conditions, all of them: the back office must have turned the
     * opening password off, no password may have been keyed, and the presented
     * identifier must be the badge this register PHYSICALLY READ — the marker
     * {@code AuthState} kept from the scan. Without that third condition the
     * parameter would turn every badge number into a password, and a badge
     * number is printed on the badge.
     *
     * <p>The marker is consumed whatever the outcome: a presented badge opens
     * one attempt, not a window.
     *
     * @param state the current register state
     * @param loginInfo the presented identifier
     * @param rawPassword the keyed password, expected to be empty here
     * @return true when the badge alone may open the register
     */
    boolean badgeOpensAlone(PosState state, String loginInfo, String rawPassword) {
        if (rawPassword != null && !rawPassword.isBlank()) {
            return false;
        }
        if (posSettingsService.passwordRequiredOnOpen()) {
            return false;
        }
        String presented = state.auth.takePresentedBadge();
        return presented != null && presented.equals(loginInfo);
    }
    /**
     * Logs the current operator out and clears the ticket.
     *
     * @param state the current POS state
     */
    public void logout(PosState state) {
        LOGGER.info("Entering method logout with state: " + state);
        // A DELIBERATE lock is journaled exactly like the idle one
        // (BO-04-01-27): the requirement asks for the registers that went on
        // pause, and a cashier locking the till on purpose is one of them —
        // only the automatic timeout was journaled, so the list was silently
        // incomplete. Nothing is written when no operator was signed in:
        // /lock is also the landing page of an already-locked register.
        if (state.auth.operatorBadgeId != null) {
            technicalEventService.log(TechnicalEvent.EventType.REGISTER_LOCKED,
                    null, state.auth.operatorBadgeId);
            // LC-13-06-02: at sign-out the default language applies again — the
            // choice belonged to the OPERATOR who made it, not to the register.
            //
            // INSIDE the "somebody was signed in" guard, and that is the whole
            // point: /lock logs out at EVERY render to reset a free register, so
            // dropping the language unconditionally erased it a millisecond after
            // it was chosen — and the lock screen is precisely where an operator
            // who cannot read the till's language has to choose one
            // (LC-13-06-03). A logout with nobody signed in has nothing to
            // forget.
            state.language = null;
        }
        state.auth.logout();
        state.clearTicket();
        // A PENDING AVAL DIES WITH THE POST. On the lock screen the endorsement
        // mailbox outranks the login one (AuthScanHandler, priority 1 before 3),
        // so an endorsement left active by the operator who just left would
        // swallow the badge of the one who arrives — and the register would
        // refuse every badge until it is restarted. Nobody is there to answer
        // the question any more; the gesture it guarded went with the ticket.
        state.endorsement.clear();
        LOGGER.info("Exiting method logout");
    }

    /**
     * Changes the PIN of the currently logged-in operator.
     * <p>
     * Validates the current PIN, the format of the new PIN (4 digits) and its
     * confirmation before persisting the new hashed PIN.
     *
     * @param state the current POS state holding the logged-in operator id
     * @param currentPin the operator's current PIN
     * @param newPin the desired new PIN
     * @param confirmPin the confirmation of the new PIN
     * @return null on success, or an error message describing why the change failed
     */
    @Transactional
    public String changePin(PosState state, String currentPin, String newPin, String confirmPin) {
        LOGGER.info("Entering method changePin with state: " + state + ", currentPin: ***" + ", newPin: ***" + ", confirmPin: ***");
        Long operatorId = state.auth.operatorId;
        if (operatorId == null) {
            LOGGER.info("Exiting method changePin");
            return i18n.get("op.aucunOperateurConnecte");
        }
        Employee employee = Employee.findById(operatorId);
        if (employee == null) {
            LOGGER.info("Exiting method changePin");
            return i18n.get("op.operateurIntrouvable2");
        }
        if (currentPin == null || !employee.verifyPassword(currentPin)) {
            LOGGER.info("Exiting method changePin");
            return i18n.get("op.codePinActuelIncorrect");
        }
        if (newPin == null || !newPin.matches("\\d{4}")) {
            LOGGER.info("Exiting method changePin");
            return i18n.get("op.nouveauCodeDoitComporter");
        }
        if (!newPin.equals(confirmPin)) {
            LOGGER.info("Exiting method changePin");
            return i18n.get("op.nouveauxCodesNeCorrespondent");
        }
        employee.password = Employee.hashPassword(newPin);
        // BO-01-02-12: the delay counts from the LAST change, so the change is
        // what restarts it.
        employee.passwordChangedAt = java.time.LocalDateTime.now();
        employee.persist();
        // A forced reprise (EXP-14) demanded this change; once done, the demand
        // is satisfied and the register is a plain logged-in post again.
        state.auth.passwordChangeRequired = false;
        technicalEventService.log(TechnicalEvent.EventType.PASSWORD_CHANGED, null, employee.badgeId);
        LOGGER.info("Exiting method changePin");
        return null;
    }
}
