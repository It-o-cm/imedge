package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.auth.AuthState;
import com.intermarche.pos.ui.endorsement.EndorsementState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuthScanHandler}.
 * <p>
 * The handler is the {@code @Priority(0)} link of the scan chain: it
 * recognizes employee badges via the {@code scan.pattern.badge} regex and
 * routes a match into the endorsement mailbox (top precedence), the lock
 * mailbox, or — on a register in use — the close request, when and only when
 * the badge is the one of the operator in post. Every leg of that last test is
 * exercised: the own badge, another employee's badge, and a register holding no
 * badge at all. Every collaborator is a Mockito mock: the {@link PosState} whose
 * public {@code endorsement}/{@code auth} sub-states are themselves mocks
 * ({@link EndorsementState}, {@link AuthState}) so their {@code active}
 * field and {@code setScannedBadge}/{@code touch}/{@code isLocked}
 * interactions can be driven and verified. The {@code badgePattern} field
 * is a package-private collaborator set directly. Four decision points,
 * eight branches, are exercised by five isolated cases.
 */
class AuthScanHandlerTest {

    /** Regex recognizing a four-digit badge, injected into the handler. */
    private static final String BADGE_PATTERN = "[0-9]{4}";

    /** A code matching {@link #BADGE_PATTERN}. */
    private static final String BADGE_CODE = "1234";

    /** A code NOT matching {@link #BADGE_PATTERN}. */
    private static final String NON_BADGE_CODE = "ABC";

    /**
     * Builds a handler wired with the four-digit badge pattern.
     *
     * @return a ready-to-test handler
     */
    private AuthScanHandler newHandler() {
        AuthScanHandler handler = new AuthScanHandler();
        handler.badgePattern = BADGE_PATTERN;
        handler.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(handler.posSettingsService.badgeScanEnabled()).thenReturn(true);
        return handler;
    }

    /**
     * Assembles a mock {@link PosState} whose endorsement and auth
     * sub-states are the supplied mocks.
     *
     * @param endorsement the endorsement mailbox mock
     * @param auth the auth mailbox mock
     * @return the wired state mock
     */
    private PosState newState(EndorsementState endorsement, AuthState auth) {
        PosState state = mock(PosState.class);
        state.endorsement = endorsement;
        state.auth = auth;
        return state;
    }

    /**
     * An already-handled context short-circuits: the handler returns before
     * touching the state, its code or either mailbox.
     */
    @Test
    void alreadyHandledShortCircuits() {
        EndorsementState endorsement = mock(EndorsementState.class);
        AuthState auth = mock(AuthState.class);
        PosState state = newState(endorsement, auth);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        ctx.handled = true;
        newHandler().handle(ctx);
        assertTrue(ctx.handled);
        verifyNoInteractions(state);
        verifyNoInteractions(endorsement);
        verifyNoInteractions(auth);
    }

    /**
     * With badge scanning disabled (BO-10-02-29/30), a matching badge is NOT
     * consumed even at an active endorsement or a locked register: the context
     * stays unhandled and walks on, so the operator keys an identifier instead.
     */
    @Test
    void disabledBadgeScanLeavesBadgeUnhandled() {
        AuthScanHandler handler = newHandler();
        when(handler.posSettingsService.badgeScanEnabled()).thenReturn(false);
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = true;
        AuthState auth = mock(AuthState.class);
        PosState state = newState(endorsement, auth);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        handler.handle(ctx);
        assertFalse(ctx.handled);
        verify(endorsement, never()).setScannedBadge(BADGE_CODE);
        verify(auth, never()).setScannedBadge(BADGE_CODE);
    }

    /**
     * A matching badge with an active endorsement lands in the endorsement
     * mailbox, touches the state, marks the context handled and never
     * consults the lock screen.
     */
    @Test
    void matchingBadgeGoesToActiveEndorsement() {
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = true;
        AuthState auth = mock(AuthState.class);
        PosState state = newState(endorsement, auth);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        newHandler().handle(ctx);
        verify(endorsement).setScannedBadge(BADGE_CODE);
        verify(state).touch();
        assertTrue(ctx.handled);
        verify(state, never()).isLocked();
        verify(auth, never()).setScannedBadge(BADGE_CODE);
    }

    /**
     * A matching badge with no active endorsement but a locked register
     * lands in the auth mailbox, touches the state and marks the context
     * handled.
     */
    @Test
    void matchingBadgeGoesToLockScreenWhenLocked() {
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = false;
        AuthState auth = mock(AuthState.class);
        PosState state = newState(endorsement, auth);
        when(state.isLocked()).thenReturn(true);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        newHandler().handle(ctx);
        verify(auth).setScannedBadge(BADGE_CODE);
        verify(state).touch();
        assertTrue(ctx.handled);
        verify(endorsement, never()).setScannedBadge(BADGE_CODE);
    }

    /**
     * A matching badge that is NOT the badge of the operator in post, on an
     * unlocked register with no endorsement, is deliberately ignored: no
     * mailbox write, no close asked for, no touch, context stays unhandled.
     * Changing hands goes through a close, never through a scan.
     */
    @Test
    void anotherEmployeeBadgeIsIgnoredOnARegisterInUse() {
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = false;
        AuthState auth = mock(AuthState.class);
        auth.operatorBadgeId = "9999";
        PosState state = newState(endorsement, auth);
        when(state.isLocked()).thenReturn(false);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        newHandler().handle(ctx);
        verify(endorsement, never()).setScannedBadge(BADGE_CODE);
        verify(auth, never()).setScannedBadge(BADGE_CODE);
        assertFalse(auth.closeRequestedByBadge);
        verify(state, never()).touch();
        assertFalse(ctx.handled);
    }

    /**
     * A register with no operator badge at all — locked out of step, or an
     * operator signed in without a badge — ignores the scan rather than
     * matching null against the code.
     */
    @Test
    void aRegisterWithoutAnOperatorBadgeIgnoresTheScan() {
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = false;
        AuthState auth = mock(AuthState.class);
        PosState state = newState(endorsement, auth);
        when(state.isLocked()).thenReturn(false);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        newHandler().handle(ctx);
        assertFalse(auth.closeRequestedByBadge);
        verify(state, never()).touch();
        assertFalse(ctx.handled);
    }

    /**
     * The operator's OWN badge, scanned on the register they are working at,
     * asks for the close (LC-01-02-03): the request is raised, the state is
     * touched so the sale screen's poll sees it, and the badge is consumed
     * rather than walking on to the catalog.
     */
    @Test
    void theOwnBadgeOfTheOperatorAsksForTheClose() {
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = false;
        AuthState auth = mock(AuthState.class);
        auth.operatorBadgeId = BADGE_CODE;
        PosState state = newState(endorsement, auth);
        when(state.isLocked()).thenReturn(false);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        newHandler().handle(ctx);
        assertTrue(auth.closeRequestedByBadge);
        verify(state).touch();
        assertTrue(ctx.handled);
        verify(auth, never()).setScannedBadge(BADGE_CODE);
        verify(endorsement, never()).setScannedBadge(BADGE_CODE);
    }

    /**
     * A non-badge code is not recognized: no mailbox is consulted, the state
     * is untouched and the context stays unhandled for the next link.
     */
    @Test
    void nonBadgeCodeIsNotRecognized() {
        EndorsementState endorsement = mock(EndorsementState.class);
        AuthState auth = mock(AuthState.class);
        PosState state = newState(endorsement, auth);
        ScanContext ctx = new ScanContext(NON_BADGE_CODE, state);
        newHandler().handle(ctx);
        assertFalse(ctx.handled);
        verifyNoInteractions(endorsement);
        verifyNoInteractions(auth);
        verify(state, never()).touch();
    }

    /**
     * A matching badge, no endorsement, register in use, but a screen ASKING for
     * a supervisor identification: the badge lands in the supervisor mailbox,
     * touches the state and marks the context handled. Presenting a badge and
     * keying an identifier have the same effect wherever an identifier is asked
     * for — this is the leg that used to fall through and be thrown away.
     */
    @Test
    void matchingBadgeGoesToAnArmedSupervisorPanel() {
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = false;
        AuthState auth = mock(AuthState.class);
        when(auth.supervisorBadgeArmed()).thenReturn(true);
        PosState state = newState(endorsement, auth);
        when(state.isLocked()).thenReturn(false);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        newHandler().handle(ctx);
        verify(auth).setSupervisorBadge(BADGE_CODE);
        verify(state).touch();
        assertTrue(ctx.handled);
        verify(endorsement, never()).setScannedBadge(BADGE_CODE);
        verify(auth, never()).setScannedBadge(BADGE_CODE);
    }

    /**
     * On the LOCKED register both the armed panel and the lock screen want the
     * badge, and the armed panel wins: the supervisor panel is the one actually
     * on screen at that moment, while the opening's own identification sits
     * behind it. The lock mailbox is never written.
     */
    @Test
    void anArmedSupervisorPanelOutranksTheLockScreen() {
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = false;
        AuthState auth = mock(AuthState.class);
        when(auth.supervisorBadgeArmed()).thenReturn(true);
        PosState state = newState(endorsement, auth);
        when(state.isLocked()).thenReturn(true);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        newHandler().handle(ctx);
        verify(auth).setSupervisorBadge(BADGE_CODE);
        verify(auth, never()).setScannedBadge(BADGE_CODE);
        assertTrue(ctx.handled);
    }

    /**
     * An ACTIVE ENDORSEMENT still outranks an armed supervisor panel: the shared
     * modal is the gesture in progress, and the panel is only a screen waiting.
     */
    @Test
    void anActiveEndorsementOutranksAnArmedSupervisorPanel() {
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = true;
        AuthState auth = mock(AuthState.class);
        when(auth.supervisorBadgeArmed()).thenReturn(true);
        PosState state = newState(endorsement, auth);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        newHandler().handle(ctx);
        verify(endorsement).setScannedBadge(BADGE_CODE);
        verify(auth, never()).setSupervisorBadge(BADGE_CODE);
        assertTrue(ctx.handled);
    }

    /**
     * With badge scanning deactivated at the back office (BO-10-02-29/30), an
     * armed supervisor panel receives nothing either: the badge walks on and the
     * supervisor keys their identifier, which is the whole point of that
     * parameter.
     */
    @Test
    void disabledBadgeScanLeavesAnArmedSupervisorPanelEmpty() {
        AuthScanHandler handler = newHandler();
        when(handler.posSettingsService.badgeScanEnabled()).thenReturn(false);
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = false;
        AuthState auth = mock(AuthState.class);
        when(auth.supervisorBadgeArmed()).thenReturn(true);
        PosState state = newState(endorsement, auth);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        handler.handle(ctx);
        assertFalse(ctx.handled);
        verify(auth, never()).setSupervisorBadge(BADGE_CODE);
    }

    /**
     * A code that is NOT a badge is not routed to an armed panel either: the
     * arming says who may identify themselves, not what counts as a badge.
     */
    @Test
    void aNonBadgeCodeIsNotRoutedToAnArmedSupervisorPanel() {
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = false;
        AuthState auth = mock(AuthState.class);
        when(auth.supervisorBadgeArmed()).thenReturn(true);
        PosState state = newState(endorsement, auth);
        ScanContext ctx = new ScanContext(NON_BADGE_CODE, state);
        newHandler().handle(ctx);
        assertFalse(ctx.handled);
        verify(auth, never()).setSupervisorBadge(NON_BADGE_CODE);
    }

    /**
     * BO-10-02-32: while a prefix is administered, only a PREFIXED code
     * endorses — the bare code, the one anybody could key, no longer does — and
     * the identifier stored is the operator's own, prefix stripped.
     */
    @Test
    void aPrefixedCodeEndorsesAndTheBareOneNoLongerDoes() {
        AuthScanHandler handler = newHandler();
        when(handler.posSettingsService.supervisorBadgePrefix()).thenReturn("SUP");
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = true;
        AuthState auth = mock(AuthState.class);
        PosState state = newState(endorsement, auth);
        ScanContext prefixed = new ScanContext("SUP" + BADGE_CODE, state);
        handler.handle(prefixed);
        assertTrue(prefixed.handled);
        verify(endorsement).setScannedBadge(BADGE_CODE);

        EndorsementState other = mock(EndorsementState.class);
        other.active = true;
        PosState bareState = newState(other, mock(AuthState.class));
        ScanContext bare = new ScanContext(BADGE_CODE, bareState);
        handler.handle(bare);
        assertFalse(bare.handled);
        verify(other, never()).setScannedBadge(anyString());
    }

    /**
     * BO-10-02-32: taking the post is the other way round — the badge takes it,
     * the supervisor barcode does not, so a prefixed code scanned at the lock
     * screen is ignored rather than logging somebody in.
     */
    @Test
    void thePostIsTakenByTheBareBadgeOnly() {
        AuthScanHandler handler = newHandler();
        when(handler.posSettingsService.supervisorBadgePrefix()).thenReturn(" SUP ");
        EndorsementState endorsement = mock(EndorsementState.class);
        AuthState auth = mock(AuthState.class);
        PosState state = newState(endorsement, auth);
        when(state.isLocked()).thenReturn(true);
        ScanContext bare = new ScanContext(BADGE_CODE, state);
        handler.handle(bare);
        assertTrue(bare.handled);
        verify(auth).setScannedBadge(BADGE_CODE);

        AuthState otherAuth = mock(AuthState.class);
        PosState prefixedState = newState(mock(EndorsementState.class), otherAuth);
        when(prefixedState.isLocked()).thenReturn(true);
        ScanContext prefixed = new ScanContext("SUP" + BADGE_CODE, prefixedState);
        handler.handle(prefixed);
        assertFalse(prefixed.handled);
        verify(otherAuth, never()).setScannedBadge(anyString());
    }
}
