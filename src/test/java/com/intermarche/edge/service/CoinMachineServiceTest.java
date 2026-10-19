package com.intermarche.edge.service;

import com.intermarche.edge.domain.payment.CardPayment;
import com.intermarche.edge.domain.payment.CashPayment;
import com.intermarche.edge.domain.safe.CoinMachine;
import com.intermarche.edge.domain.sale.Ticket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link CoinMachineService}.
 *
 * <p>The figure is the SOFTWARE's, which is the requirement's whole point. What
 * is proved here is that it moves by the NET cash of a ticket — tendered minus
 * change — that a re-pushed ticket moves it by the difference and not again by
 * the whole, and that a register with no machine moves nothing. Every null guard
 * is covered on both arms.
 */
class CoinMachineServiceTest {

    /**
     * Builds a machine attached to a register.
     *
     * @param code the machine's code
     * @param declared what the software believes, as a plain decimal string
     * @return the machine, its persist neutralized
     */
    private CoinMachine machine(String code, String declared) {
        CoinMachine machine = mock(CoinMachine.class);
        machine.code = code;
        machine.terminalId = "C01";
        machine.declaredAmount = declared == null ? null : new BigDecimal(declared);
        return machine;
    }

    /**
     * Builds a ticket settled partly in cash.
     *
     * @param cash the cash applied, as a plain decimal string, null for none
     * @return the ticket
     */
    private Ticket ticket(String cash) {
        Ticket ticket = new Ticket();
        ticket.terminalId = "C01";
        ticket.payments.add(new CardPayment(new BigDecimal("10.00")));
        if (cash != null) {
            ticket.payments.add(new CashPayment(new BigDecimal(cash), new BigDecimal("50.00")));
        }
        return ticket;
    }

    /**
     * Stubs the machines of one register.
     *
     * @param panache the active PanacheEntityBase mock
     * @param machines the machines to resolve
     */
    @SuppressWarnings("unchecked")
    private void stubMachines(MockedStatic<PanacheEntityBase> panache, List<CoinMachine> machines) {
        PanacheQuery<CoinMachine> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(machines);
        panache.when(() -> CoinMachine.find("terminalId = ?1 order by code", "C01"))
                .thenReturn(query);
    }

    /**
     * The cash of a ticket is what was APPLIED, the change already deducted: the
     * machine keeps what the customer handed over and gives the rest back.
     */
    @Test
    void thecashOfAticketIsWhatWasApplied() {
        CoinMachineService service = new CoinMachineService();
        assertEquals(new BigDecimal("32.50"), service.cashOf(ticket("32.50")));
    }

    /**
     * A ticket settled without cash, and a ticket carrying no payment list at
     * all, are worth nothing — both arms of the guard.
     */
    @Test
    void aticketWithoutCashIsWorthNothing() {
        CoinMachineService service = new CoinMachineService();
        assertEquals(BigDecimal.ZERO, service.cashOf(ticket(null)));
        Ticket bare = new Ticket();
        bare.payments = null;
        assertEquals(BigDecimal.ZERO, service.cashOf(bare));
        assertEquals(BigDecimal.ZERO, service.cashOf(null));
    }

    /**
     * A cash payment carrying no applied amount is skipped: the false arm of
     * the amount guard, taken while the {@code instanceof} arm is true, which no
     * amount-bearing cash payment reaches.
     */
    @Test
    void acashPaymentWithoutAnAmountIsSkipped() {
        CoinMachineService service = new CoinMachineService();
        Ticket ticket = new Ticket();
        ticket.terminalId = "C01";
        ticket.payments.add(new CashPayment(null, new BigDecimal("50.00")));
        ticket.payments.add(new CashPayment(new BigDecimal("12.00"), new BigDecimal("20.00")));
        assertEquals(new BigDecimal("12.00"), service.cashOf(ticket));
    }

    /**
     * A new ticket moves the machine of its register by its whole cash.
     */
    @Test
    void anewTicketMovesTheMachineByItsWholeCash() {
        CoinMachineService service = new CoinMachineService();
        CoinMachine machine = machine("MON-01", "300.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMachines(panache, List.of(machine));
            service.collect(ticket("32.50"), BigDecimal.ZERO);
            assertEquals(new BigDecimal("332.5000"), machine.declaredAmount);
            verify(machine).persist();
        }
    }

    /**
     * A ticket the node already knew moves it by the DIFFERENCE, so a re-push
     * credits nothing twice.
     */
    @Test
    void arepushedTicketMovesTheMachineByTheDifference() {
        CoinMachineService service = new CoinMachineService();
        CoinMachine machine = machine("MON-01", "300.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMachines(panache, List.of(machine));
            service.collect(ticket("32.50"), new BigDecimal("30.00"));
            assertEquals(new BigDecimal("302.5000"), machine.declaredAmount);
        }
    }

    /**
     * A ticket re-pushed unchanged moves nothing at all, and neither does one
     * whose cash was already counted in full.
     */
    @Test
    void anUnchangedTicketMovesNothing() {
        CoinMachineService service = new CoinMachineService();
        CoinMachine machine = machine("MON-01", "300.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMachines(panache, List.of(machine));
            service.collect(ticket("32.50"), new BigDecimal("32.50"));
            verify(machine, never()).persist();
        }
    }

    /**
     * A previous contribution of null counts as zero — the null arm of that
     * guard.
     */
    @Test
    void anAbsentPreviousContributionCountsAsZero() {
        CoinMachineService service = new CoinMachineService();
        CoinMachine machine = machine("MON-01", "300.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMachines(panache, List.of(machine));
            service.collect(ticket("32.50"), null);
            assertEquals(new BigDecimal("332.5000"), machine.declaredAmount);
        }
    }

    /**
     * A null ticket, and a ticket naming no register, move nothing — the two
     * legs of the first guard.
     */
    @Test
    void anAbsentTicketOrRegisterMovesNothing() {
        CoinMachineService service = new CoinMachineService();
        service.collect(null, BigDecimal.ZERO);
        Ticket anonymous = ticket("32.50");
        anonymous.terminalId = null;
        service.collect(anonymous, BigDecimal.ZERO);
    }

    /**
     * A register with NO machine moves nothing, which is the normal state of a
     * line that owns none.
     */
    @Test
    void aregisterWithoutAmachineMovesNothing() {
        CoinMachineService service = new CoinMachineService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMachines(panache, List.of());
            service.collect(ticket("32.50"), BigDecimal.ZERO);
        }
    }

    /**
     * A register carrying TWO machines splits the amount evenly: nothing in what
     * the register pushes says which unit swallowed which coin.
     */
    @Test
    void aregisterWithTwoMachinesSplitsTheAmount() {
        CoinMachineService service = new CoinMachineService();
        CoinMachine first = machine("MON-01", "100.00");
        CoinMachine second = machine("MON-02", "50.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMachines(panache, List.of(first, second));
            service.collect(ticket("30.00"), BigDecimal.ZERO);
            assertEquals(new BigDecimal("115.0000"), first.declaredAmount);
            assertEquals(new BigDecimal("65.0000"), second.declaredAmount);
        }
    }

    /**
     * A machine whose running total was never written starts from zero — the
     * null arm of that guard.
     */
    @Test
    void amachineWithoutArunningTotalStartsFromZero() {
        CoinMachineService service = new CoinMachineService();
        CoinMachine machine = machine("MON-01", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMachines(panache, List.of(machine));
            service.collect(ticket("20.00"), BigDecimal.ZERO);
            assertEquals(new BigDecimal("20.0000"), machine.declaredAmount);
        }
    }

    /**
     * A loading adds to the machine and a negative one takes from it — the two
     * directions of the same gesture.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aloadingMovesTheMachineBothWays() {
        CoinMachineService service = new CoinMachineService();
        CoinMachine machine = machine("MON-01", "300.00");
        PanacheQuery<CoinMachine> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(machine);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinMachine.find("code", "MON-01")).thenReturn(query);
            assertSame(machine, service.load("MON-01", new BigDecimal("100.00")));
            assertEquals(new BigDecimal("400.00"), machine.declaredAmount);
            service.load("MON-01", new BigDecimal("-50.00"));
            assertEquals(new BigDecimal("350.00"), machine.declaredAmount);
        }
    }

    /**
     * A loading is refused on an unknown machine, on a null amount and on a zero
     * one — the three legs of its guard.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aloadingIsRefusedOnEveryLegOfItsGuard() {
        CoinMachineService service = new CoinMachineService();
        CoinMachine machine = machine("MON-01", "300.00");
        PanacheQuery<CoinMachine> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(machine);
        PanacheQuery<CoinMachine> missing = mock(PanacheQuery.class);
        when(missing.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinMachine.find("code", "MON-01")).thenReturn(found);
            panache.when(() -> CoinMachine.find("code", "MON-99")).thenReturn(missing);
            assertNull(service.load("MON-99", BigDecimal.TEN));
            assertNull(service.load("MON-01", null));
            assertNull(service.load("MON-01", BigDecimal.ZERO));
            verify(machine, never()).persist();
        }
    }
}
