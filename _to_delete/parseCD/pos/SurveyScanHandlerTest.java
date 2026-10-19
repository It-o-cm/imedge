package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.supervision.SurveyTriggerService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link SurveyScanHandler}, the survey code-barre of
 * LC-03-07-03.
 * <p>
 * The handler is a plain recognizer: it filters on the already-handled flag
 * and the lock state, matches the administered pattern — one capturing group
 * naming the survey id — and delegates the ask to a mocked
 * {@link SurveyTriggerService}. The register state is a real {@link PosState}.
 * Every arm is covered: the already-handled guard, the lock guard (both
 * during and out of a transaction, which the line demands), the non-matching
 * code, the recognized code and the deployment pattern that captures no id.
 */
class SurveyScanHandlerTest {

    /**
     * Builds a handler with the default pattern and a mocked trigger.
     *
     * @param trigger the survey trigger mock to inject
     * @return a ready-to-test handler
     */
    private SurveyScanHandler newHandler(SurveyTriggerService trigger) {
        SurveyScanHandler handler = new SurveyScanHandler();
        handler.surveyPattern = "^SONDAGE-(\\d{1,6})$";
        handler.surveyTriggerService = trigger;
        return handler;
    }

    /**
     * An already-handled context short-circuits: nothing is asked.
     */
    @Test
    void alreadyHandledShortCircuits() {
        SurveyTriggerService trigger = mock(SurveyTriggerService.class);
        SurveyScanHandler handler = newHandler(trigger);
        PosState lockedByDefault = new PosState();
        ScanContext ctx = new ScanContext("SONDAGE-12", lockedByDefault);
        ctx.handled = true;
        handler.handle(ctx);
        verifyNoInteractions(trigger);
    }

    /**
     * A survey code on a LOCKED register is ignored — the lock guard stands
     * whether a transaction runs or not, and the code falls through.
     */
    @Test
    void aLockedRegisterIgnoresTheCode() {
        SurveyTriggerService trigger = mock(SurveyTriggerService.class);
        SurveyScanHandler handler = newHandler(trigger);
        PosState locked = mock(PosState.class);
        org.mockito.Mockito.when(locked.isLocked()).thenReturn(true);
        ScanContext ctx = new ScanContext("SONDAGE-12", locked);
        handler.handle(ctx);
        verifyNoInteractions(trigger);
        assertFalse(ctx.handled);
    }

    /**
     * LC-03-07-03: a matching code on an unlocked register triggers the
     * survey its id names, during a transaction and out of one — the only
     * guard is the lock.
     */
    @Test
    void aMatchingCodeTriggersTheSurveyItNames() {
        SurveyTriggerService trigger = mock(SurveyTriggerService.class);
        SurveyScanHandler handler = newHandler(trigger);
        PosState duringSale = new PosState();
        duringSale.auth.isLocked = false;
        duringSale.ticket.items.add(new com.intermarche.pos.ui.ticket.TicketState.TicketItem(
                "123", null, "L", java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                java.math.BigDecimal.ZERO));
        ScanContext ctx = new ScanContext("SONDAGE-12", duringSale);
        handler.handle(ctx);
        verify(trigger).fireByScan(duringSale, 12L);
        assertTrue(ctx.handled);
        PosState outsideSale = new PosState();
        outsideSale.auth.isLocked = false;
        ScanContext bare = new ScanContext("SONDAGE-12", outsideSale);
        handler.handle(bare);
        verify(trigger).fireByScan(outsideSale, 12L);
        assertTrue(bare.handled);
    }

    /**
     * A code the pattern does not name falls through unhandled — the survey
     * function is a shape, not a prefix.
     */
    @Test
    void aNonMatchingCodeFallsThrough() {
        SurveyTriggerService trigger = mock(SurveyTriggerService.class);
        SurveyScanHandler handler = newHandler(trigger);
        PosState unlocked = new PosState();
        unlocked.auth.isLocked = false;
        ScanContext ctx = new ScanContext("2990000000019", unlocked);
        handler.handle(ctx);
        verifyNoInteractions(trigger);
        assertFalse(ctx.handled);
    }

    /**
     * A deployment pattern that captures NO id is a deployment defect, not a
     * lane error: the code falls through rather than breaking the scan.
     */
    @Test
    void aPatternWithoutACapturingGroupFallsThrough() {
        SurveyTriggerService trigger = mock(SurveyTriggerService.class);
        SurveyScanHandler handler = newHandler(trigger);
        handler.surveyPattern = "^SONDAGE-\\d{1,6}$";
        PosState unlocked = new PosState();
        unlocked.auth.isLocked = false;
        ScanContext ctx = new ScanContext("SONDAGE-12", unlocked);
        handler.handle(ctx);
        verifyNoInteractions(trigger);
        assertFalse(ctx.handled);
    }

}
