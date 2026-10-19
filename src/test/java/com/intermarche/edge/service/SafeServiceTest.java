package com.intermarche.edge.service;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.safe.AccountingPeriod;
import com.intermarche.edge.domain.safe.BankDeposit;
import com.intermarche.edge.domain.safe.CashPouch;
import com.intermarche.edge.domain.safe.CoinOrder;
import com.intermarche.edge.domain.safe.CoinOrderLine;
import com.intermarche.edge.domain.safe.SafeDenomination;
import com.intermarche.edge.domain.safe.SafeInventory;
import com.intermarche.edge.domain.safe.SafeInventoryLine;
import com.intermarche.edge.domain.safe.SafeMovement;
import com.intermarche.edge.domain.safe.SafeMovementType;
import com.intermarche.edge.domain.safe.SafeVatLine;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link SafeService}.
 *
 * <p>The one rule the class exists to enforce is that the balance of the safe is
 * the sum of its movements and that nothing moves it outside the open period.
 * Every gesture is therefore asserted twice — once with a period open, once
 * without — and every guard on both arms: a closure refused for pending
 * movements, a correction refused on a frozen row, a delivery refused on an
 * order that no longer awaits one, a bank deposit refused with no pouch picked.
 *
 * <p>Panache statics are neutralized on {@code PanacheEntityBase} and the rows
 * the service writes are intercepted with {@code mockConstruction}; nothing here
 * touches a database.
 */
class SafeServiceTest {

    /**
     * Builds an open period.
     *
     * @param opening the opening balance, as a plain decimal string
     * @return the period
     */
    private AccountingPeriod openPeriod(String opening) {
        AccountingPeriod period = mock(AccountingPeriod.class);
        period.id = 7L;
        period.label = "2026-09";
        period.startDate = LocalDate.of(2026, 9, 1);
        period.openingBalance = new BigDecimal(opening);
        period.status = AccountingPeriod.PeriodStatus.OPEN;
        return period;
    }

    /**
     * Builds a movement of a kind and an amount.
     *
     * @param type the kind
     * @param amount the amount, as a plain decimal string
     * @return the movement
     */
    private SafeMovement movement(SafeMovementType type, String amount) {
        SafeMovement movement = new SafeMovement();
        movement.type = type;
        movement.amount = new BigDecimal(amount);
        movement.movementDate = LocalDate.of(2026, 9, 15);
        movement.label = "Mouvement";
        return movement;
    }

    /**
     * Stubs the open-period finder.
     *
     * @param panache the active PanacheEntityBase mock
     * @param period the period to resolve, null when none is open
     */
    @SuppressWarnings("unchecked")
    private void stubOpenPeriod(MockedStatic<PanacheEntityBase> panache, AccountingPeriod period) {
        PanacheQuery<AccountingPeriod> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(period);
        panache.when(() -> AccountingPeriod.find("status = ?1 order by startDate desc",
                AccountingPeriod.PeriodStatus.OPEN)).thenReturn(query);
    }

    /**
     * Stubs the whole-ledger listing.
     *
     * @param panache the active PanacheEntityBase mock
     * @param movements the movements to resolve
     */
    @SuppressWarnings("unchecked")
    private void stubLedger(MockedStatic<PanacheEntityBase> panache, List<SafeMovement> movements) {
        PanacheQuery<SafeMovement> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(movements);
        panache.when(() -> SafeMovement.find("order by id")).thenReturn(query);
    }

    /**
     * Stubs the listing of one period's movements.
     *
     * @param panache the active PanacheEntityBase mock
     * @param periodId the period identifier
     * @param movements the movements to resolve
     */
    @SuppressWarnings("unchecked")
    private void stubPeriodMovements(MockedStatic<PanacheEntityBase> panache, Long periodId,
            List<SafeMovement> movements) {
        PanacheQuery<SafeMovement> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(movements);
        panache.when(() -> SafeMovement.find("period.id = ?1 order by movementDate, id", periodId))
                .thenReturn(query);
    }

    /**
     * The balance is the signed sum of every movement ever written.
     */
    @Test
    void thebalanceIsTheSignedSumOfTheLedger() {
        SafeService service = new SafeService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubLedger(panache, List.of(movement(SafeMovementType.POUCH, "1000.00"),
                    movement(SafeMovementType.EXPENSE, "40.00"),
                    movement(SafeMovementType.BANK_DEPOSIT, "500.00")));
            assertEquals(new BigDecimal("460.00"), service.balance());
        }
    }

    /**
     * An empty ledger balances at zero.
     */
    @Test
    void anEmptyLedgerBalancesAtZero() {
        SafeService service = new SafeService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubLedger(panache, List.of());
            assertEquals(BigDecimal.ZERO, service.balance());
        }
    }

    /**
     * A period's balance is its opening balance plus its own movements.
     */
    @Test
    void aperiodBalanceIsItsOpeningPlusItsMovements() {
        SafeService service = new SafeService();
        AccountingPeriod period = openPeriod("100.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPeriodMovements(panache, 7L, List.of(movement(SafeMovementType.RECEIPT, "30.00")));
            assertEquals(new BigDecimal("130.00"), service.balanceOf(period));
        }
    }

    /**
     * A null period, and a period whose opening balance was never written,
     * answer without exploding — the null arms of the two guards.
     */
    @Test
    void anAbsentPeriodOrOpeningBalanceDoesNotExplode() {
        SafeService service = new SafeService();
        assertEquals(BigDecimal.ZERO, service.balanceOf(null));
        AccountingPeriod period = openPeriod("0.00");
        period.openingBalance = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPeriodMovements(panache, 7L, List.of(movement(SafeMovementType.RECEIPT, "30.00")));
            assertEquals(new BigDecimal("30.00"), service.balanceOf(period));
        }
    }

    /**
     * A period with no identifier has no movements: the second leg of the guard,
     * which a null check alone would miss.
     */
    @Test
    void aperiodWithoutAnIdentifierHasNoMovements() {
        SafeService service = new SafeService();
        AccountingPeriod transient1 = openPeriod("0.00");
        transient1.id = null;
        assertTrue(service.movementsOf(transient1).isEmpty());
        assertTrue(service.movementsOf(null).isEmpty());
        assertEquals(0, service.countPending(transient1));
        assertEquals(0, service.countPending(null));
    }

    /**
     * Opening a period stamps it with the balance the safe holds and persists it.
     */
    @Test
    void openingAperiodStampsItWithTheCurrentBalance() {
        SafeService service = new SafeService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<AccountingPeriod> created =
                        mockConstruction(AccountingPeriod.class)) {
            stubOpenPeriod(panache, null);
            stubLedger(panache, List.of(movement(SafeMovementType.POUCH, "250.00")));
            AccountingPeriod period = service.open("2026-09", LocalDate.of(2026, 9, 1));
            assertNotNull(period);
            assertEquals("2026-09", period.label);
            assertEquals(LocalDate.of(2026, 9, 1), period.startDate);
            assertEquals(new BigDecimal("250.00"), period.openingBalance);
            assertEquals(AccountingPeriod.PeriodStatus.OPEN, period.status);
            verify(created.constructed().get(0)).persist();
        }
    }

    /**
     * Opening without a first day starts it today — the null arm of that guard.
     */
    @Test
    void openingWithoutAdayStartsItToday() {
        SafeService service = new SafeService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<AccountingPeriod> created =
                        mockConstruction(AccountingPeriod.class)) {
            stubOpenPeriod(panache, null);
            stubLedger(panache, List.of());
            assertEquals(LocalDate.now(), service.open("2026-09", null).startDate);
        }
    }

    /**
     * A second period cannot be opened while one is: the safe has one balance.
     */
    @Test
    void asecondPeriodCannotBeOpened() {
        SafeService service = new SafeService();
        AccountingPeriod already = openPeriod("0.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<AccountingPeriod> created =
                        mockConstruction(AccountingPeriod.class)) {
            stubOpenPeriod(panache, already);
            assertNull(service.open("2026-10", LocalDate.of(2026, 10, 1)));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * Closing with nothing open is refused by name.
     */
    @Test
    void closingWithNothingOpenIsRefused() {
        SafeService service = new SafeService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubOpenPeriod(panache, null);
            assertEquals(SafeService.NO_OPEN_PERIOD, service.close(null));
        }
    }

    /**
     * Closing is refused while a movement of the period is still pending — the
     * requirement's order, point then validate then close.
     */
    @Test
    void closingIsRefusedWhileAmovementIsPending() {
        SafeService service = new SafeService();
        AccountingPeriod period = openPeriod("100.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubOpenPeriod(panache, period);
            panache.when(() -> SafeMovement.count("period.id = ?1 and status = ?2", 7L,
                    SafeMovement.MovementStatus.PENDING)).thenReturn(2L);
            assertEquals(SafeService.PENDING_MOVEMENTS, service.close(null));
            assertEquals(AccountingPeriod.PeriodStatus.OPEN, period.status);
        }
    }

    /**
     * Closing freezes the period on its balance and names who ran it.
     */
    @Test
    void closingFreezesThePeriodOnItsBalance() {
        SafeService service = new SafeService();
        AccountingPeriod period = openPeriod("100.00");
        Employee closer = new Employee();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubOpenPeriod(panache, period);
            panache.when(() -> SafeMovement.count("period.id = ?1 and status = ?2", 7L,
                    SafeMovement.MovementStatus.PENDING)).thenReturn(0L);
            stubPeriodMovements(panache, 7L, List.of(movement(SafeMovementType.RECEIPT, "30.00")));
            assertNull(service.close(closer));
            assertEquals(AccountingPeriod.PeriodStatus.CLOSED, period.status);
            assertEquals(new BigDecimal("130.00"), period.closingBalance);
            assertSame(closer, period.closedBy);
            assertNotNull(period.closedAt);
            assertEquals(LocalDate.now(), period.endDate);
        }
    }

    /**
     * A movement is written into the open period, pending, its amount forced
     * positive and its VAT kept.
     */
    @Test
    void amovementIsWrittenIntoTheOpenPeriod() {
        SafeService service = new SafeService();
        AccountingPeriod period = openPeriod("0.00");
        Employee author = new Employee();
        Employee concerned = new Employee();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeMovement> created = mockConstruction(SafeMovement.class)) {
            stubOpenPeriod(panache, period);
            SafeMovement written = service.record(SafeMovementType.EXPENSE,
                    new BigDecimal("-40.00"), "Fournitures", LocalDate.of(2026, 9, 15), author,
                    concerned, "FACT-12", List.of(new SafeVatLine(new BigDecimal("20.00"),
                            new BigDecimal("33.33"), new BigDecimal("6.67"))));
            assertNotNull(written);
            assertEquals(SafeMovementType.EXPENSE, written.type);
            assertEquals(new BigDecimal("40.00"), written.amount);
            assertEquals("Fournitures", written.label);
            assertEquals(LocalDate.of(2026, 9, 15), written.movementDate);
            assertSame(period, written.period);
            assertSame(author, written.enteredBy);
            assertSame(concerned, written.employee);
            assertEquals("FACT-12", written.reference);
            assertEquals(SafeMovement.MovementStatus.PENDING, written.status);
            assertEquals(1, written.vatLines.size());
            verify(created.constructed().get(0)).persist();
        }
    }

    /**
     * A movement with no day counts for today, and one with no amount for
     * nothing — the null arms of those two guards.
     */
    @Test
    void amovementWithoutAdayOrAmountFallsBack() {
        SafeService service = new SafeService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeMovement> created = mockConstruction(SafeMovement.class)) {
            stubOpenPeriod(panache, openPeriod("0.00"));
            SafeMovement written = service.record(SafeMovementType.RECEIPT, null, "Parking", null,
                    null, null, null, null);
            assertEquals(LocalDate.now(), written.movementDate);
            assertEquals(BigDecimal.ZERO, written.amount);
            assertTrue(written.vatLines.isEmpty());
        }
    }

    /**
     * A VAT line stating nothing is dropped, and a VAT breakdown on a kind that
     * bears none is dropped whole — the two legs of that filter.
     */
    @Test
    void theVatIsKeptOnlyWhereItIsStatedAndAllowed() {
        SafeService service = new SafeService();
        List<SafeVatLine> posted = new ArrayList<>(List.of(
                new SafeVatLine(new BigDecimal("20.00"), null, null),
                new SafeVatLine(new BigDecimal("5.50"), new BigDecimal("10.00"),
                        new BigDecimal("0.55"))));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeMovement> created = mockConstruction(SafeMovement.class)) {
            stubOpenPeriod(panache, openPeriod("0.00"));
            assertEquals(1, service.record(SafeMovementType.EXPENSE, BigDecimal.ONE, "A", null,
                    null, null, null, posted).vatLines.size());
            assertTrue(service.record(SafeMovementType.POUCH, BigDecimal.ONE, "B", null, null,
                    null, null, posted).vatLines.isEmpty());
            assertTrue(service.record(null, BigDecimal.ONE, "C", null, null, null, null, posted)
                    .vatLines.isEmpty());
        }
    }

    /**
     * A null VAT line in the posted breakdown is dropped: the false arm of the
     * per-line null guard, which every non-null case leaves untouched.
     */
    @Test
    void anullVatLineIsDropped() {
        SafeService service = new SafeService();
        List<SafeVatLine> posted = new ArrayList<>();
        posted.add(null);
        posted.add(new SafeVatLine(new BigDecimal("5.50"), new BigDecimal("10.00"),
                new BigDecimal("0.55")));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeMovement> created = mockConstruction(SafeMovement.class)) {
            stubOpenPeriod(panache, openPeriod("0.00"));
            assertEquals(1, service.record(SafeMovementType.EXPENSE, BigDecimal.ONE, "A", null,
                    null, null, null, posted).vatLines.size());
        }
    }

    /**
     * Nothing is written when no period is open: the one door, shut.
     */
    @Test
    void nothingIsWrittenWithoutAnOpenPeriod() {
        SafeService service = new SafeService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeMovement> created = mockConstruction(SafeMovement.class)) {
            stubOpenPeriod(panache, null);
            assertNull(service.record(SafeMovementType.EXPENSE, BigDecimal.TEN, "A", null, null,
                    null, null, null));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * Validating a pending movement stamps it; validating a validated one, or a
     * null one, does nothing — the two refusing legs.
     */
    @Test
    void validatingStampsOnlyApendingMovement() {
        SafeService service = new SafeService();
        SafeMovement movement = mock(SafeMovement.class);
        movement.status = SafeMovement.MovementStatus.PENDING;
        when(movement.isValidated()).thenReturn(false, true);
        Employee validator = new Employee();
        assertTrue(service.validate(movement, validator));
        assertEquals(SafeMovement.MovementStatus.VALIDATED, movement.status);
        assertSame(validator, movement.validatedBy);
        assertNotNull(movement.validatedAt);
        verify(movement).persist();
        assertFalse(service.validate(movement, validator));
        assertFalse(service.validate(null, validator));
    }

    /**
     * A correction writes the three fields it is given and leaves the others.
     */
    @Test
    void acorrectionWritesWhatItIsGiven() {
        SafeService service = new SafeService();
        SafeMovement movement = mock(SafeMovement.class);
        movement.status = SafeMovement.MovementStatus.PENDING;
        movement.amount = new BigDecimal("40.00");
        movement.label = "Fournitures";
        movement.movementDate = LocalDate.of(2026, 9, 15);
        when(movement.isCorrectable()).thenReturn(true);
        assertNull(service.correct(movement, new BigDecimal("-55.00"), "  Papeterie  ",
                LocalDate.of(2026, 9, 16)));
        assertEquals(new BigDecimal("55.00"), movement.amount);
        assertEquals("Papeterie", movement.label);
        assertEquals(LocalDate.of(2026, 9, 16), movement.movementDate);
        verify(movement).persist();
    }

    /**
     * A correction given nothing changes nothing — the null and blank arms of
     * the three guards, each alone.
     */
    @Test
    void acorrectionGivenNothingChangesNothing() {
        SafeService service = new SafeService();
        SafeMovement movement = mock(SafeMovement.class);
        movement.status = SafeMovement.MovementStatus.PENDING;
        movement.amount = new BigDecimal("40.00");
        movement.label = "Fournitures";
        movement.movementDate = LocalDate.of(2026, 9, 15);
        when(movement.isCorrectable()).thenReturn(true);
        assertNull(service.correct(movement, null, null, null));
        assertNull(service.correct(movement, null, "   ", null));
        assertEquals(new BigDecimal("40.00"), movement.amount);
        assertEquals("Fournitures", movement.label);
        assertEquals(LocalDate.of(2026, 9, 15), movement.movementDate);
    }

    /**
     * A correction is refused on an unknown movement and on a frozen one — the
     * two refusing legs.
     */
    @Test
    void acorrectionIsRefusedOnAnUnknownOrFrozenMovement() {
        SafeService service = new SafeService();
        assertEquals(SafeService.FROZEN_MOVEMENT, service.correct(null, null, null, null));
        SafeMovement validated = mock(SafeMovement.class);
        validated.status = SafeMovement.MovementStatus.VALIDATED;
        assertEquals(SafeService.FROZEN_MOVEMENT,
                service.correct(validated, BigDecimal.ONE, "X", null));
        verify(validated, never()).persist();
    }

    /**
     * A pouch credits the safe and is held in it.
     */
    @Test
    void apouchCreditsTheSafeAndIsHeldInIt() {
        SafeService service = new SafeService();
        CashPouch pouch = mock(CashPouch.class);
        pouch.pouchNumber = "POCH-1";
        pouch.amount = new BigDecimal("1250.00");
        pouch.accountingDay = LocalDate.of(2026, 9, 15);
        Employee carrier = new Employee();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeMovement> created = mockConstruction(SafeMovement.class)) {
            stubOpenPeriod(panache, openPeriod("0.00"));
            assertNull(service.depositPouch(pouch, carrier));
            assertNotNull(pouch.movement);
            assertEquals(SafeMovementType.POUCH, pouch.movement.type);
            assertEquals(new BigDecimal("1250.00"), pouch.movement.amount);
            assertEquals("POCH-1", pouch.movement.reference);
            assertSame(carrier, pouch.depositedBy);
            assertEquals(CashPouch.PouchStatus.IN_SAFE, pouch.status);
            verify(pouch).persist();
        }
    }

    /**
     * A pouch is refused with no pouch at all, and with no period open — the two
     * refusing legs.
     */
    @Test
    void apouchIsRefusedWithoutApouchOrAperiod() {
        SafeService service = new SafeService();
        assertEquals(SafeService.NO_OPEN_PERIOD, service.depositPouch(null, null));
        CashPouch pouch = mock(CashPouch.class);
        pouch.amount = new BigDecimal("10.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeMovement> created = mockConstruction(SafeMovement.class)) {
            stubOpenPeriod(panache, null);
            assertEquals(SafeService.NO_OPEN_PERIOD, service.depositPouch(pouch, null));
            verify(pouch, never()).persist();
        }
    }

    /**
     * A bank deposit debits the safe of the SUM of its pouches and banks them.
     */
    @Test
    void abankDepositDebitsTheSumOfItsPouches() {
        SafeService service = new SafeService();
        BankDeposit deposit = mock(BankDeposit.class);
        deposit.bagNumber = "SAC-42";
        deposit.depositDay = LocalDate.of(2026, 9, 16);
        CashPouch first = mock(CashPouch.class);
        first.amount = new BigDecimal("1000.00");
        CashPouch second = mock(CashPouch.class);
        second.amount = null;
        Employee declarer = new Employee();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeMovement> created = mockConstruction(SafeMovement.class)) {
            stubOpenPeriod(panache, openPeriod("0.00"));
            assertNull(service.depositToBank(deposit, List.of(first, second), declarer));
            assertEquals(new BigDecimal("1000.00"), deposit.amount);
            assertSame(declarer, deposit.declaredBy);
            assertEquals(SafeMovementType.BANK_DEPOSIT, deposit.movement.type);
            assertSame(deposit, first.bankDeposit);
            assertEquals(CashPouch.PouchStatus.BANKED, first.status);
            assertEquals(CashPouch.PouchStatus.BANKED, second.status);
            verify(deposit).persist();
            verify(first).persist();
            verify(second).persist();
        }
    }

    /**
     * A bank deposit is refused with no deposit, no list and an empty list — the
     * three legs of its guard — and when no period is open.
     */
    @Test
    void abankDepositIsRefusedWithoutPouchesOrAperiod() {
        SafeService service = new SafeService();
        assertEquals("Aucune pochette sélectionnée.", service.depositToBank(null, List.of(), null));
        BankDeposit deposit = mock(BankDeposit.class);
        deposit.bagNumber = "SAC-42";
        assertEquals("Aucune pochette sélectionnée.", service.depositToBank(deposit, null, null));
        assertEquals("Aucune pochette sélectionnée.",
                service.depositToBank(deposit, List.of(), null));
        CashPouch pouch = mock(CashPouch.class);
        pouch.amount = new BigDecimal("10.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeMovement> created = mockConstruction(SafeMovement.class)) {
            stubOpenPeriod(panache, null);
            assertEquals(SafeService.NO_OPEN_PERIOD,
                    service.depositToBank(deposit, List.of(pouch), null));
            verify(pouch, never()).persist();
        }
    }

    /**
     * A coin delivery credits the safe of what CAME BACK and closes the order.
     */
    @Test
    void acoinDeliveryCreditsWhatCameBack() {
        SafeService service = new SafeService();
        CoinOrder order = mock(CoinOrder.class);
        order.orderNumber = "DM-1";
        order.status = CoinOrder.OrderStatus.REQUESTED;
        CoinOrderLine line = new CoinOrderLine(SafeDenomination.C1, 50);
        line.receivedQuantity = 40;
        order.lines = new ArrayList<>(List.of(line));
        when(order.isAwaitingDelivery()).thenReturn(true);
        when(order.receivedAmount()).thenReturn(new BigDecimal("40.00"));
        Employee receiver = new Employee();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeMovement> created = mockConstruction(SafeMovement.class)) {
            stubOpenPeriod(panache, openPeriod("0.00"));
            assertNull(service.receiveCoinOrder(order, receiver));
            assertEquals(SafeMovementType.COIN_DELIVERY, order.movement.type);
            assertEquals(new BigDecimal("40.00"), order.movement.amount);
            assertEquals(CoinOrder.OrderStatus.RECEIVED, order.status);
            assertSame(receiver, order.receivedBy);
            assertNotNull(order.receivedAt);
            verify(order).persist();
        }
    }

    /**
     * A delivery is refused on a null order, on one that awaits nothing, on an
     * empty delivery and when no period is open — the four refusing legs.
     */
    @Test
    void acoinDeliveryIsRefusedOnEveryLegOfItsGuard() {
        SafeService service = new SafeService();
        assertEquals("Cette demande n'attend plus de livraison.",
                service.receiveCoinOrder(null, null));
        CoinOrder closed = mock(CoinOrder.class);
        when(closed.isAwaitingDelivery()).thenReturn(false);
        assertEquals("Cette demande n'attend plus de livraison.",
                service.receiveCoinOrder(closed, null));
        CoinOrder empty = mock(CoinOrder.class);
        when(empty.isAwaitingDelivery()).thenReturn(true);
        when(empty.receivedAmount()).thenReturn(BigDecimal.ZERO);
        assertEquals("Aucune quantité reçue n'a été saisie.",
                service.receiveCoinOrder(empty, null));
        CoinOrder pending = mock(CoinOrder.class);
        pending.orderNumber = "DM-1";
        when(pending.isAwaitingDelivery()).thenReturn(true);
        when(pending.receivedAmount()).thenReturn(new BigDecimal("40.00"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeMovement> created = mockConstruction(SafeMovement.class)) {
            stubOpenPeriod(panache, null);
            assertEquals(SafeService.NO_OPEN_PERIOD, service.receiveCoinOrder(pending, null));
            verify(pending, never()).persist();
        }
    }

    /**
     * An inventory freezes the theoretical of the moment and writes NOTHING into
     * the ledger: a count observes.
     */
    @Test
    void anInventoryFreezesTheTheoreticalAndWritesNothing() {
        SafeService service = new SafeService();
        SafeInventory inventory = mock(SafeInventory.class);
        inventory.lines = new ArrayList<>(List.of(
                new SafeInventoryLine(SafeDenomination.N50, 10)));
        when(inventory.countedTotal()).thenReturn(new BigDecimal("500.00"));
        Employee counter = new Employee();
        AccountingPeriod period = openPeriod("0.00");
        List<SafeMovement> ledger = List.of(movement(SafeMovementType.POUCH, "515.00"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeMovement> created = mockConstruction(SafeMovement.class)) {
            stubOpenPeriod(panache, period);
            stubLedger(panache, ledger);
            service.takeInventory(inventory, counter);
            assertEquals(new BigDecimal("515.00"), inventory.theoreticalAmount);
            assertEquals(new BigDecimal("500.00"), inventory.countedAmount);
            assertEquals(new BigDecimal("-15.00"), inventory.variance);
            assertSame(counter, inventory.countedBy);
            assertSame(period, inventory.period);
            assertNotNull(inventory.countedAt);
            assertTrue(created.constructed().isEmpty());
            verify(inventory).persist();
        }
    }

    /**
     * A MISSING amount is written off as an expense, an EXCESS as an adjustment
     * — the two arms of the sign.
     */
    @Test
    void thediscrepancyIsWrittenOffBothWays() {
        SafeService service = new SafeService();
        SafeInventory missing = mock(SafeInventory.class);
        missing.id = 3L;
        missing.variance = new BigDecimal("-15.00");
        when(missing.isAdjustable()).thenReturn(true);
        SafeInventory excess = mock(SafeInventory.class);
        excess.id = 4L;
        excess.variance = new BigDecimal("12.00");
        when(excess.isAdjustable()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeMovement> created = mockConstruction(SafeMovement.class)) {
            stubOpenPeriod(panache, openPeriod("0.00"));
            assertNull(service.adjustFromInventory(missing, null));
            assertEquals(SafeMovementType.EXPENSE, missing.adjustment.type);
            assertEquals(new BigDecimal("15.00"), missing.adjustment.amount);
            assertEquals("INVENTAIRE-3", missing.adjustment.reference);
            assertNull(service.adjustFromInventory(excess, null));
            assertEquals(SafeMovementType.INVENTORY_ADJUSTMENT, excess.adjustment.type);
            assertEquals(new BigDecimal("12.00"), excess.adjustment.amount);
        }
    }

    /**
     * Writing off is refused on a null count, on one with nothing to write off,
     * and when no period is open — the three refusing legs.
     */
    @Test
    void writingOffIsRefusedOnEveryLegOfItsGuard() {
        SafeService service = new SafeService();
        assertEquals("Cet inventaire ne présente pas d'écart à régulariser.",
                service.adjustFromInventory(null, null));
        SafeInventory balanced = mock(SafeInventory.class);
        when(balanced.isAdjustable()).thenReturn(false);
        assertEquals("Cet inventaire ne présente pas d'écart à régulariser.",
                service.adjustFromInventory(balanced, null));
        SafeInventory standing = mock(SafeInventory.class);
        standing.id = 5L;
        standing.variance = new BigDecimal("-15.00");
        when(standing.isAdjustable()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeMovement> created = mockConstruction(SafeMovement.class)) {
            stubOpenPeriod(panache, null);
            assertEquals(SafeService.NO_OPEN_PERIOD, service.adjustFromInventory(standing, null));
            verify(standing, never()).persist();
        }
    }

    /**
     * The journal asks for a stretch of days, the two open bounds falling back
     * to the epoch and to today — both arms of each guard.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thejournalAsksForAstretchOfDays() {
        SafeService service = new SafeService();
        SafeMovement movement = movement(SafeMovementType.RECEIPT, "30.00");
        PanacheQuery<SafeMovement> narrow = mock(PanacheQuery.class);
        when(narrow.list()).thenReturn(List.of(movement));
        PanacheQuery<SafeMovement> wide = mock(PanacheQuery.class);
        when(wide.list()).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeMovement.find(
                    "movementDate >= ?1 and movementDate <= ?2 order by movementDate, id",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).thenReturn(narrow);
            panache.when(() -> SafeMovement.find(
                    "movementDate >= ?1 and movementDate <= ?2 order by movementDate, id",
                    LocalDate.of(1970, 1, 1), LocalDate.now())).thenReturn(wide);
            assertEquals(List.of(movement),
                    service.journal(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));
            assertTrue(service.journal(null, null).isEmpty());
        }
    }

    /**
     * The open-period accessor hands back what the finder resolves, on both of
     * its arms.
     */
    @Test
    void theOpenPeriodAccessorHandsBackWhatTheFinderResolves() {
        SafeService service = new SafeService();
        AccountingPeriod period = openPeriod("0.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubOpenPeriod(panache, period);
            assertSame(period, service.openPeriod());
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubOpenPeriod(panache, null);
            assertNull(service.openPeriod());
        }
    }

    /**
     * The pending count asks the ledger for the period's unvalidated rows.
     */
    @Test
    void thependingCountAsksTheLedger() {
        SafeService service = new SafeService();
        AccountingPeriod period = openPeriod("0.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeMovement.count("period.id = ?1 and status = ?2", 7L,
                    SafeMovement.MovementStatus.PENDING)).thenReturn(3L);
            assertEquals(3L, service.countPending(period));
        }
    }
}
