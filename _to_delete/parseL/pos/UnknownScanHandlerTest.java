package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link UnknownScanHandler}.
 * <p>
 * The handler is the {@code @Priority(100)} tail of the scan chain — the only
 * link allowed to answer "I don't know". It has two guards and one effect: it
 * bails when the context is already {@code handled}, it bails silently when the
 * register is locked (so no "CODE INCONNU" leaks onto the lock screen), and
 * otherwise it stamps {@code "CODE INCONNU: " + code} onto the ticket through
 * {@code setError} — which is what bumps the polling version, so the test
 * verifies the CALL and not the field — and consumes the context. Both
 * collaborators are Mockito mocks: the {@link PosState} whose
 * {@code isLocked()} decides the second guard and whose public
 * {@code ticket} sub-state is a {@link TicketState} mock. Two
 * decision points, four branches, are exercised by three isolated cases.
 */
class UnknownScanHandlerTest {

    /** An unrecognized code reaching the tail of the chain. */
    private static final String CODE = "ABC999";

    /**
     * Assembles the handler under test with a resolver reading the French
     * bundle, so the assertions keep the literal sentence the register shows
     * (LC-13-06).
     *
     * @return the wired handler
     */
    private UnknownScanHandler newHandler() {
        UnknownScanHandler handler = new UnknownScanHandler();
        handler.i18n = TestMessages.french();
        handler.ticketPrinterService =
                mock(com.intermarche.pos.ui.hardware.TicketPrinterService.class);
        return handler;
    }

    /**
     * Assembles a mock {@link PosState} whose ticket sub-state is the supplied
     * mock and whose lock flag is set as requested.
     *
     * @param ticket the ticket mailbox mock
     * @param locked the value {@code isLocked()} must report
     * @return the wired state mock
     */
    private PosState newState(TicketState ticket, boolean locked) {
        PosState state = mock(PosState.class);
        state.ticket = ticket;
        when(state.isLocked()).thenReturn(locked);
        return state;
    }

    /**
     * An already-handled context short-circuits: the handler returns before
     * consulting the lock flag or touching the ticket, leaving the flag set.
     */
    @Test
    void alreadyHandledShortCircuits() {
        TicketState ticket = mock(TicketState.class);
        PosState state = mock(PosState.class);
        state.ticket = ticket;
        ScanContext ctx = new ScanContext(CODE, state);
        ctx.handled = true;
        newHandler().handle(ctx);
        assertTrue(ctx.handled);
        assertNull(ticket.transientError);
        verifyNoInteractions(ticket);
        verify(state, org.mockito.Mockito.never()).isLocked();
    }

    /**
     * A locked register swallows the unknown code: the handler returns without
     * setting an error and leaves the context unhandled for no further link.
     */
    @Test
    void lockedRegisterStaysSilent() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket, true);
        ScanContext ctx = new ScanContext(CODE, state);
        newHandler().handle(ctx);
        assertFalse(ctx.handled);
        assertNull(ticket.transientError);
    }

    /**
     * An unlocked register with an unrecognized code stamps the "CODE INCONNU"
     * error onto the ticket and consumes the context.
     */
    @Test
    void unlockedRegisterStampsUnknownError() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket, false);
        ScanContext ctx = new ScanContext(CODE, state);
        UnknownScanHandler handler = newHandler();
        handler.handle(ctx);
        assertTrue(ctx.handled);
        verify(ticket).setError("CODE INCONNU: " + CODE);
        // BO-03-10-05: the paper is ASKED FOR on every unknown code; whether
        // one comes out is the island's decision and the printer's answer, not
        // this handler's business.
        verify(handler.ticketPrinterService).printUnknownArticleTicket(CODE);
    }

    /**
     * BO-03-10-05: a locked register asks for no paper either — the slip
     * follows the message, and the lock screen shows neither.
     */
    @Test
    void aLockedRegisterAsksForNoSlip() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket, true);
        UnknownScanHandler handler = newHandler();
        handler.handle(new ScanContext(CODE, state));
        verify(handler.ticketPrinterService, never()).printUnknownArticleTicket(any());
    }

    /**
     * A handler built without CDI carries no printer, and the message still
     * reaches the screen: the slip is an addition, never a condition.
     */
    @Test
    void aHandlerWithoutAPrinterStillStampsTheMessage() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket, false);
        UnknownScanHandler handler = new UnknownScanHandler();
        handler.i18n = TestMessages.french();
        ScanContext ctx = new ScanContext(CODE, state);
        handler.handle(ctx);
        assertTrue(ctx.handled);
        verify(ticket).setError("CODE INCONNU: " + CODE);
    }
}
