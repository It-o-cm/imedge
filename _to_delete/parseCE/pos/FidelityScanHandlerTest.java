package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.fidelity.FidelityService;
import com.intermarche.pos.ui.fidelity.FidelityState;
import com.intermarche.pos.ui.ticket.TicketState;
import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FidelityScanHandler}.
 * <p>
 * The handler is a {@code @Priority(1)} link of the scan chain: it
 * recognizes fidelity cards via the {@code scan.pattern.fidelity} regex and,
 * only while the register is unlocked, hands the card to
 * {@link FidelityService#validateCard}, marking the context handled. Every
 * collaborator is a Mockito mock: the {@link PosState} whose
 * {@code isLocked()} verdict is driven, and the {@link FidelityService}
 * whose {@code validateCard} interaction is verified. The
 * {@code fidelityPattern} field is a package-private collaborator set
 * directly. Three decision points, six branches, are exercised by four
 * isolated cases.
 */
class FidelityScanHandlerTest {

    /** Regex recognizing a fidelity card, injected into the handler. */
    private static final String FIDELITY_PATTERN = "FID[0-9]{4}";

    /** A code matching {@link #FIDELITY_PATTERN}. */
    private static final String FIDELITY_CODE = "FID1234";

    /** A code NOT matching {@link #FIDELITY_PATTERN}. */
    private static final String NON_FIDELITY_CODE = "1234";

    /**
     * Builds a handler wired with the fidelity pattern and a mock service.
     *
     * @param fidelityService the fidelity service mock to inject
     * @return a ready-to-test handler
     */
    private FidelityScanHandler newHandler(FidelityService fidelityService) {
        return newHandler(fidelityService, true);
    }

    /**
     * Builds a handler wired with the fidelity pattern, a mock service and a
     * back-office parameters mock whose multiple-scan verdict is driven.
     *
     * @param fidelityService the fidelity service mock to inject
     * @param allowMultiple the value returned by {@code fidelityAllowMultipleScan}
     * @return a ready-to-test handler
     */
    private FidelityScanHandler newHandler(FidelityService fidelityService, boolean allowMultiple) {
        FidelityScanHandler handler = new FidelityScanHandler();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        handler.i18n = TestMessages.french();
        handler.fidelityPattern = FIDELITY_PATTERN;
        handler.fidelityService = fidelityService;
        PosSettingsService settings = mock(PosSettingsService.class);
        when(settings.fidelityAllowMultipleScan()).thenReturn(allowMultiple);
        // BO-03-06-54: no card range administered by default, so every case
        // below keeps running against the deployment property.
        when(settings.fidelityCardPattern()).thenReturn("");
        handler.posSettingsService = settings;
        return handler;
    }

    /**
     * An already-handled context short-circuits: the handler returns before
     * touching the state or the fidelity service.
     */
    @Test
    void alreadyHandledShortCircuits() {
        PosState state = mock(PosState.class);
        FidelityService fidelityService = mock(FidelityService.class);
        ScanContext ctx = new ScanContext(FIDELITY_CODE, state);
        ctx.handled = true;
        newHandler(fidelityService).handle(ctx);
        assertTrue(ctx.handled);
        verifyNoInteractions(state);
        verifyNoInteractions(fidelityService);
    }

    /**
     * A matching card on an unlocked register is validated by the fidelity
     * service and the context is marked handled. The state carries an EMPTY
     * loyalty sub-state, the only production reality: {@code PosState} always
     * wires one, and the replacement proposal of LC-03-04-08 reads it.
     */
    @Test
    void matchingCardUnlockedIsValidated() {
        PosState state = mock(PosState.class);
        when(state.isLocked()).thenReturn(false);
        state.reprint = new com.intermarche.pos.ui.reprintticket.ReprintState();
        state.fidelity = new FidelityState();
        FidelityService fidelityService = mock(FidelityService.class);
        ScanContext ctx = new ScanContext(FIDELITY_CODE, state);
        newHandler(fidelityService).handle(ctx);
        verify(fidelityService).validateCard(state, FIDELITY_CODE);
        assertTrue(ctx.handled);
    }

    /**
     * A matching card on a locked register is ignored: the lock guard
     * short-circuits before the regex, no validation happens, the context
     * stays unhandled for the next link.
     */
    @Test
    void matchingCardLockedIsIgnored() {
        PosState state = mock(PosState.class);
        when(state.isLocked()).thenReturn(true);
        state.reprint = new com.intermarche.pos.ui.reprintticket.ReprintState();
        FidelityService fidelityService = mock(FidelityService.class);
        ScanContext ctx = new ScanContext(FIDELITY_CODE, state);
        newHandler(fidelityService).handle(ctx);
        verify(fidelityService, never()).validateCard(state, FIDELITY_CODE);
        assertFalse(ctx.handled);
    }

    /**
     * A non-fidelity code on an unlocked register is not recognized: the
     * regex fails, no validation happens, the context stays unhandled.
     */
    @Test
    void nonFidelityCodeIsNotRecognized() {
        PosState state = mock(PosState.class);
        when(state.isLocked()).thenReturn(false);
        state.reprint = new com.intermarche.pos.ui.reprintticket.ReprintState();
        FidelityService fidelityService = mock(FidelityService.class);
        ScanContext ctx = new ScanContext(NON_FIDELITY_CODE, state);
        newHandler(fidelityService).handle(ctx);
        verify(fidelityService, never()).validateCard(state, NON_FIDELITY_CODE);
        assertFalse(ctx.handled);
    }

    /**
     * BO-10-03-02: with multiple scans DISALLOWED and a card already attached,
     * a further scan is refused — the cashier gets the transient error, the
     * card is not re-validated, and the context is consumed.
     */
    @Test
    void secondCardRefusedWhenMultipleScanDisallowed() {
        PosState state = mock(PosState.class);
        when(state.isLocked()).thenReturn(false);
        state.reprint = new com.intermarche.pos.ui.reprintticket.ReprintState();
        FidelityState fidelity = mock(FidelityState.class);
        fidelity.active = true;
        state.fidelity = fidelity;
        TicketState ticket = mock(TicketState.class);
        state.ticket = ticket;
        FidelityService fidelityService = mock(FidelityService.class);
        ScanContext ctx = new ScanContext(FIDELITY_CODE, state);
        newHandler(fidelityService, false).handle(ctx);
        verify(ticket).setError("CARTE FIDÉLITÉ DÉJÀ SCANNÉE");
        verify(fidelityService, never()).validateCard(any(), any());
        assertTrue(ctx.handled);
    }

    /**
     * BO-10-03-02: with multiple scans DISALLOWED but NO card yet attached,
     * the first card is validated normally — the block only bites once a card
     * is present.
     */
    @Test
    void firstCardValidatedWhenMultipleScanDisallowed() {
        PosState state = mock(PosState.class);
        when(state.isLocked()).thenReturn(false);
        state.reprint = new com.intermarche.pos.ui.reprintticket.ReprintState();
        FidelityState fidelity = mock(FidelityState.class);
        fidelity.active = false;
        state.fidelity = fidelity;
        FidelityService fidelityService = mock(FidelityService.class);
        ScanContext ctx = new ScanContext(FIDELITY_CODE, state);
        newHandler(fidelityService, false).handle(ctx);
        verify(fidelityService).validateCard(state, FIDELITY_CODE);
        assertTrue(ctx.handled);
    }

    /**
     * LC-03-04-08: a DIFFERENT card arriving on a ticket that already carries
     * one is PARKED as a proposal, not substituted — the operator confirms the
     * swap on the screen, and nothing is attached here.
     */
    @Test
    void aDifferentCardIsProposedNeverSubstituted() {
        PosState state = newState(false);
        state.fidelity.assignCard("FID9999");
        FidelityService fidelityService = mock(FidelityService.class);
        ScanContext ctx = new ScanContext(FIDELITY_CODE, state);
        newHandler(fidelityService).handle(ctx);
        assertEquals(FIDELITY_CODE, state.fidelity.pendingReplacementCard);
        assertEquals("FID9999", state.fidelity.label);
        verify(fidelityService, never()).validateCard(any(), any());
        assertTrue(ctx.handled);
    }

    /**
     * LC-03-06-03: while the REASSIGNMENT mask of the reprint screen is open,
     * a scanned card FILLS THE MASK instead of attaching to the live sale —
     * the number is the one the closed ticket is being reassigned to, and
     * the operator validates it there.
     */
    @Test
    void aScannedCardFillsTheOpenReassignmentMask() {
        PosState state = newState(false);
        state.reprint.reassignMode = true;
        state.reprint.reassignMaskOpen = true;
        FidelityService fidelityService = mock(FidelityService.class);
        ScanContext ctx = new ScanContext(FIDELITY_CODE, state);
        newHandler(fidelityService).handle(ctx);
        assertEquals(FIDELITY_CODE, state.reprint.reassignCard);
        assertFalse(state.fidelity.active);
        verify(fidelityService, never()).validateCard(any(), any());
        assertTrue(ctx.handled);
    }

    /**
     * LC-03-06-03, the other arm: outside the reassignment mask the very same
     * scan keeps its ordinary meaning — the mask only claims the card while
     * it is open in the reassignment mode.
     */
    @Test
    void theSameScanKeepsItsOrdinaryMeaningOutsideTheMask() {
        PosState state = newState(false);
        state.reprint.reassignMode = true;
        state.reprint.reassignMaskOpen = false;
        FidelityService fidelityService = mock(FidelityService.class);
        ScanContext ctx = new ScanContext(FIDELITY_CODE, state);
        newHandler(fidelityService).handle(ctx);
        assertEquals("", state.reprint.reassignCard);
        verify(fidelityService).validateCard(state, FIDELITY_CODE);
    }

    /**
     * LC-03-04-08, the other arm: the SAME card scanned again is a refresh,
     * not a substitution — it goes through the ordinary attachment and no
     * proposal is parked.
     */
    @Test
    void theSameCardScannedAgainAttachesDirectly() {
        PosState state = newState(false);
        state.fidelity.assignCard(FIDELITY_CODE);
        FidelityService fidelityService = mock(FidelityService.class);
        ScanContext ctx = new ScanContext(FIDELITY_CODE, state);
        newHandler(fidelityService).handle(ctx);
        assertNull(state.fidelity.pendingReplacementCard);
        verify(fidelityService).validateCard(state, FIDELITY_CODE);
        assertTrue(ctx.handled);
    }

    /**
     * Builds an unlocked register state carrying an empty loyalty sub-state.
     *
     * @param locked whether the register is locked
     * @return the mocked state
     */
    private PosState newState(boolean locked) {
        PosState state = mock(PosState.class);
        when(state.isLocked()).thenReturn(locked);
        state.fidelity = new FidelityState();
        state.reprint = new com.intermarche.pos.ui.reprintticket.ReprintState();
        return state;
    }

    // --- Plage des cartes de fidélité (BO-03-06-54) ---

    /**
     * The ADMINISTERED range wins over the deployment property: a card the
     * property would not recognise is attached, and one the property WOULD
     * recognise is left alone.
     */
    @Test
    void theAdministeredRangeWinsOverTheDeploymentProperty() {
        FidelityService service = mock(FidelityService.class);
        FidelityScanHandler handler = newHandler(service);
        when(handler.posSettingsService.fidelityCardPattern()).thenReturn("^299\\d{10}$");
        assertEquals("^299\\d{10}$", handler.cardPattern());

        PosState state = newState(false);
        ScanContext ctx = new ScanContext("2990000000019", state);
        handler.handle(ctx);
        assertTrue(ctx.handled);
        verify(service).validateCard(state, "2990000000019");

        ScanContext other = new ScanContext(FIDELITY_CODE, newState(false));
        handler.handle(other);
        assertFalse(other.handled);
    }

    /**
     * A SECOND administered range recognises another shape, which is what
     * proves the setting is read rather than a literal returned; padding is
     * trimmed.
     */
    @Test
    void aSecondAdministeredRangeRecognisesAnotherShape() {
        FidelityService service = mock(FidelityService.class);
        FidelityScanHandler handler = newHandler(service);
        when(handler.posSettingsService.fidelityCardPattern()).thenReturn("  ^30\\d{11}$  ");
        assertEquals("^30\\d{11}$", handler.cardPattern());
        ScanContext ctx = new ScanContext("301000000012", newState(false));
        handler.handle(ctx);
        assertFalse(ctx.handled);
        ScanContext twelve = new ScanContext("3010000000123", newState(false));
        handler.handle(twelve);
        assertTrue(twelve.handled);
    }

    /**
     * A BLANK administered range leaves the deployment property in charge —
     * the leg a null check alone would miss — and a NULL one behaves the same.
     */
    @Test
    void anEmptyAdministeredRangeKeepsTheDeploymentProperty() {
        FidelityService service = mock(FidelityService.class);
        FidelityScanHandler blank = newHandler(service);
        when(blank.posSettingsService.fidelityCardPattern()).thenReturn("   ");
        assertEquals(FIDELITY_PATTERN, blank.cardPattern());
        FidelityScanHandler missing = newHandler(service);
        when(missing.posSettingsService.fidelityCardPattern()).thenReturn(null);
        assertEquals(FIDELITY_PATTERN, missing.cardPattern());
        ScanContext ctx = new ScanContext(FIDELITY_CODE, newState(false));
        missing.handle(ctx);
        assertTrue(ctx.handled);
    }
}
