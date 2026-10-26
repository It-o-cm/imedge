package com.intermarche.edge.service;

import com.intermarche.edge.domain.payment.CardPayment;
import com.intermarche.edge.domain.payment.CashPayment;
import com.intermarche.edge.domain.safe.CoinMachine;
import com.intermarche.edge.domain.safe.CoinMachineFlow;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.sale.Ticket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
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
        assertEquals(BigDecimal.ZERO, service.cashOf((com.intermarche.edge.domain.sale.Ticket) null));
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
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachineFlow> flows = mockConstruction(CoinMachineFlow.class)) {
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
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachineFlow> flows = mockConstruction(CoinMachineFlow.class)) {
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
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachineFlow> flows = mockConstruction(CoinMachineFlow.class)) {
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
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachineFlow> flows = mockConstruction(CoinMachineFlow.class)) {
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
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachineFlow> flows = mockConstruction(CoinMachineFlow.class)) {
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
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachineFlow> flows = mockConstruction(CoinMachineFlow.class)) {
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
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachineFlow> flows = mockConstruction(CoinMachineFlow.class)) {
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
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachineFlow> flows = mockConstruction(CoinMachineFlow.class)) {
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
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachineFlow> flows = mockConstruction(CoinMachineFlow.class)) {
            panache.when(() -> CoinMachine.find("code", "MON-01")).thenReturn(found);
            panache.when(() -> CoinMachine.find("code", "MON-99")).thenReturn(missing);
            assertNull(service.load("MON-99", BigDecimal.TEN));
            assertNull(service.load("MON-01", null));
            assertNull(service.load("MON-01", BigDecimal.ZERO));
            verify(machine, never()).persist();
        }
    }
    /**
     * A collected ticket journals its flow with the ticket number, the amount
     * moved and the running total once applied.
     */
    @Test
    void acollectedTicketIsJournalled() {
        CoinMachineService service = new CoinMachineService();
        CoinMachine machine = machine("MON-01", "300.00");
        Ticket ticket = ticket("32.50");
        ticket.ticketNumber = "C01-T1";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachineFlow> flows = mockConstruction(CoinMachineFlow.class)) {
            stubMachines(panache, List.of(machine));
            service.collect(ticket, BigDecimal.ZERO);
            CoinMachineFlow flow = flows.constructed().get(0);
            assertEquals("MON-01", flow.machineCode);
            assertEquals(CoinMachineFlow.Kind.TICKET, flow.kind);
            assertEquals("C01-T1", flow.reference);
            assertEquals(new BigDecimal("32.5000"), flow.amount);
            assertEquals(new BigDecimal("332.5000"), flow.balanceAfter);
            verify(flow).persist();
        }
    }

    /**
     * Builds a refund of a register.
     *
     * @param method how it was paid out, null for none
     * @param amount the amount, as a plain decimal string, null for none
     * @return the refund
     */
    private Refund refund(Refund.RefundMethod method, String amount) {
        Refund refund = new Refund();
        refund.terminalId = "C01";
        refund.refundNumber = "C01-R1";
        refund.refundMethod = method;
        refund.totalAmount = amount == null ? null : new BigDecimal(amount);
        return refund;
    }

    /**
     * Only a refund paid in cash with an amount is worth something, negative
     * amounts read by their magnitude — every leg of the guard.
     */
    @Test
    void therefundCashIsItsAmountWhenPaidInCash() {
        CoinMachineService service = new CoinMachineService();
        assertEquals(BigDecimal.ZERO, service.refundCashOf(null));
        assertEquals(BigDecimal.ZERO, service.refundCashOf(refund(Refund.RefundMethod.CARD, "5.00")));
        assertEquals(BigDecimal.ZERO, service.refundCashOf(refund(Refund.RefundMethod.CASH, null)));
        assertEquals(new BigDecimal("5.00"), service.refundCashOf(refund(Refund.RefundMethod.CASH, "-5.00")));
    }

    /**
     * A cash refund takes its amount OUT of the machine and is journalled as a
     * refund; re-pushed unchanged, it moves nothing.
     */
    @Test
    void acashRefundTakesFromTheMachine() {
        CoinMachineService service = new CoinMachineService();
        CoinMachine machine = machine("MON-01", "300.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachineFlow> flows = mockConstruction(CoinMachineFlow.class)) {
            stubMachines(panache, List.of(machine));
            service.collectRefund(refund(Refund.RefundMethod.CASH, "12.00"), null);
            assertEquals(new BigDecimal("288.0000"), machine.declaredAmount);
            assertEquals(CoinMachineFlow.Kind.REFUND, flows.constructed().get(0).kind);
            assertEquals("C01-R1", flows.constructed().get(0).reference);
            service.collectRefund(refund(Refund.RefundMethod.CASH, "12.00"), new BigDecimal("12.00"));
            assertEquals(1, flows.constructed().size());
        }
    }

    /**
     * A null refund, and a refund naming no register, move nothing.
     */
    @Test
    void anAbsentRefundOrRegisterMovesNothing() {
        CoinMachineService service = new CoinMachineService();
        service.collectRefund(null, BigDecimal.ZERO);
        Refund anonymous = refund(Refund.RefundMethod.CASH, "3.00");
        anonymous.terminalId = null;
        service.collectRefund(anonymous, BigDecimal.ZERO);
        assertEquals(BigDecimal.ZERO, service.refundCashOf(refund(null, "3.00")));
    }

    /**
     * Builds a drawer movement of a register.
     *
     * @param type the movement type
     * @param method the tender, null for cash
     * @param transferTo the transfer's destination, or null
     * @return the movement, ten euros
     */
    private CashMovement movement(CashMovement.MovementType type, String method, String transferTo) {
        CashMovement movement = new CashMovement();
        movement.movementUid = "MV-1";
        movement.terminalId = "C01";
        movement.type = type;
        movement.paymentMethod = method;
        movement.transferTo = transferTo;
        movement.amount = BigDecimal.TEN;
        return movement;
    }

    /**
     * Every type of movement has its effect on the cash: floats and
     * down-payments add, withdrawals and expenses take, a transfer moves only
     * when cash is one end, a declaration and a non-cash movement move nothing.
     */
    @Test
    void everyMovementHasItsCashImpact() {
        CoinMachineService service = new CoinMachineService();
        BigDecimal ten = BigDecimal.TEN;
        assertEquals(ten, service.impactOf(movement(CashMovement.MovementType.DEPOSIT, null, null)));
        assertEquals(ten, service.impactOf(movement(CashMovement.MovementType.CUSTOMER_DEPOSIT, "CASH", null)));
        assertEquals(BigDecimal.ZERO, service.impactOf(movement(CashMovement.MovementType.DEPOSIT, "CARD", null)));
        assertEquals(ten.negate(), service.impactOf(movement(CashMovement.MovementType.WITHDRAWAL, " ", null)));
        assertEquals(ten.negate(), service.impactOf(movement(CashMovement.MovementType.EXPENSE, null, null)));
        assertEquals(BigDecimal.ZERO, service.impactOf(movement(CashMovement.MovementType.EXPENSE, "CHEQUE", null)));
        assertEquals(ten.negate(), service.impactOf(movement(CashMovement.MovementType.TRANSFER, null, "CARD")));
        assertEquals(ten, service.impactOf(movement(CashMovement.MovementType.TRANSFER, "CARD", "CASH")));
        assertEquals(BigDecimal.ZERO, service.impactOf(movement(CashMovement.MovementType.TRANSFER, "CARD", "CHEQUE")));
        assertEquals(BigDecimal.ZERO, service.impactOf(movement(CashMovement.MovementType.TRANSFER, null, null)));
        assertEquals(BigDecimal.ZERO, service.impactOf(movement(CashMovement.MovementType.DECLARATION, null, null)));
    }

    /**
     * A null movement, one without amount or type, and the cash changer's own
     * automatic withdrawal move nothing — every leg of the guard.
     */
    @Test
    void theGuardOfTheImpactHasEveryLeg() {
        CoinMachineService service = new CoinMachineService();
        assertEquals(BigDecimal.ZERO, service.impactOf(null));
        CashMovement noAmount = movement(CashMovement.MovementType.DEPOSIT, null, null);
        noAmount.amount = null;
        assertEquals(BigDecimal.ZERO, service.impactOf(noAmount));
        CashMovement noType = movement(CashMovement.MovementType.DEPOSIT, null, null);
        noType.type = null;
        assertEquals(BigDecimal.ZERO, service.impactOf(noType));
        CashMovement changer = movement(CashMovement.MovementType.WITHDRAWAL, null, null);
        changer.reason = CashMovement.CASH_CHANGER_REASON;
        assertEquals(BigDecimal.ZERO, service.impactOf(changer));
    }

    /**
     * A drawer movement in cash moves the machine by the difference with what
     * it already moved, and journals it under the movement's uid; unchanged, it
     * moves nothing.
     */
    @Test
    void amovementMovesTheMachineByTheDifference() {
        CoinMachineService service = new CoinMachineService();
        CoinMachine machine = machine("MON-01", "300.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachineFlow> flows = mockConstruction(CoinMachineFlow.class)) {
            stubMachines(panache, List.of(machine));
            service.collectMovement(movement(CashMovement.MovementType.WITHDRAWAL, null, null), null);
            assertEquals(new BigDecimal("290.0000"), machine.declaredAmount);
            assertEquals(CoinMachineFlow.Kind.MOVEMENT, flows.constructed().get(0).kind);
            assertEquals("MV-1", flows.constructed().get(0).reference);
            service.collectMovement(movement(CashMovement.MovementType.DEPOSIT, null, null), BigDecimal.ONE);
            assertEquals(new BigDecimal("299.0000"), machine.declaredAmount);
            service.collectMovement(movement(CashMovement.MovementType.DEPOSIT, null, null), BigDecimal.TEN);
            assertEquals(2, flows.constructed().size());
        }
    }

    /**
     * A null movement, and a movement naming no register, move nothing.
     */
    @Test
    void anAbsentMovementOrRegisterMovesNothing() {
        CoinMachineService service = new CoinMachineService();
        service.collectMovement(null, BigDecimal.ZERO);
        CashMovement anonymous = movement(CashMovement.MovementType.DEPOSIT, null, null);
        anonymous.terminalId = null;
        service.collectMovement(anonymous, BigDecimal.ZERO);
        assertEquals(BigDecimal.TEN, service.impactOf(anonymous));
    }

    /**
     * A count is journalled without an amount, a realignment with what it
     * moved; a machine without running total journals a zero balance.
     */
    @Test
    void countsAndRealignmentsAreJournalled() {
        CoinMachineService service = new CoinMachineService();
        CoinMachine machine = machine("MON-01", null);
        try (MockedConstruction<CoinMachineFlow> flows = mockConstruction(CoinMachineFlow.class)) {
            service.journal(machine, CoinMachineFlow.Kind.COUNT, null);
            assertEquals(BigDecimal.ZERO, flows.constructed().get(0).amount);
            assertEquals(BigDecimal.ZERO, flows.constructed().get(0).balanceAfter);
            assertNull(flows.constructed().get(0).reference);
            machine.declaredAmount = new BigDecimal("40.00");
            service.journal(machine, CoinMachineFlow.Kind.REALIGN, new BigDecimal("-2.00"));
            assertEquals(new BigDecimal("-2.00"), flows.constructed().get(1).amount);
            assertEquals(new BigDecimal("40.00"), flows.constructed().get(1).balanceAfter);
            assertEquals(CoinMachineFlow.Kind.REALIGN, flows.constructed().get(1).kind);
        }
    }
}
