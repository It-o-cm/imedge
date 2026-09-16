package com.intermarche.pos.domain.sale;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.store.Store;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import com.intermarche.pos.domain.payment.CashPayment;
import com.intermarche.pos.domain.payment.TicketPayment;

/**
 * Unit tests for {@link Ticket}, targeting 100% branch coverage.
 * <p>
 * The class is a persistence entity whose only conditional logic is the null
 * guard of {@link Ticket#getTotalFormatted()}; both arms are exercised.
 * {@link Ticket#getChecksum()} and the {@code addLine}/{@code addPayment}
 * helpers carry no branch, so coverage is completed by asserting the field
 * defaults, both enums, the read/write of every field, the two collection
 * mutators and the checksum contract (populated, null-tolerant and change
 * detecting). No static finder or persist is reached, so no Panache mocking is
 * required. Each test is fully isolated and asserts absolute expected values.
 */
class TicketTest {

    /**
     * The default constructor sets the lifecycle status to OPEN.
     */
    @Test
    void defaultStatusIsOpen() {
        Ticket ticket = new Ticket();
        Assertions.assertEquals(Ticket.TicketStatus.OPEN, ticket.status);
    }

    /**
     * The default constructor sets the valuation status to NOT_VALUATED.
     */
    @Test
    void defaultValuationStatusIsNotValuated() {
        Ticket ticket = new Ticket();
        Assertions.assertEquals(Ticket.ValuationStatus.NOT_VALUATED, ticket.valuationStatus);
    }

    /**
     * The default constructor leaves the print count at zero.
     */
    @Test
    void defaultPrintCountIsZero() {
        Ticket ticket = new Ticket();
        Assertions.assertEquals(0, ticket.printCount);
    }

    /**
     * The default constructor initializes the lines to an empty, mutable list.
     */
    @Test
    void defaultLinesAreEmpty() {
        Ticket ticket = new Ticket();
        Assertions.assertNotNull(ticket.lines);
        Assertions.assertTrue(ticket.lines.isEmpty());
    }

    /**
     * The default constructor initializes the payments to an empty, mutable list.
     */
    @Test
    void defaultPaymentsAreEmpty() {
        Ticket ticket = new Ticket();
        Assertions.assertNotNull(ticket.payments);
        Assertions.assertTrue(ticket.payments.isEmpty());
    }

    /**
     * All public fields accept and return their assigned values.
     */
    @Test
    void fieldsAreReadWrite() {
        Ticket ticket = new Ticket();
        Store store = new Store();
        Employee cashier = new Employee();
        CashSession session = new CashSession();
        LocalDateTime created = LocalDateTime.of(2026, 8, 3, 10, 0);
        LocalDateTime closed = LocalDateTime.of(2026, 8, 3, 10, 5);
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.ticketNumber = "C04-00000123";
        ticket.terminalId = "C04";
        ticket.creationDate = created;
        ticket.closingDate = closed;
        ticket.signature = "SIG";
        ticket.previousSignature = "GENESIS";
        ticket.grandTotal = new BigDecimal("100.0000");
        ticket.printCount = 2;
        ticket.valuationStatus = Ticket.ValuationStatus.VALUATED;
        ticket.store = store;
        ticket.cashier = cashier;
        ticket.session = session;
        ticket.fidelityCard = "CARD-1";
        ticket.digitalKey = "0123456789abcdef";
        ticket.customerEmail = "buyer@example.com";
        ticket.itemCount = 3;
        ticket.totalExcludingTax = new BigDecimal("8.3333");
        ticket.totalIncludingTax = new BigDecimal("10.0000");
        ticket.totalVat = new BigDecimal("1.6667");
        Assertions.assertEquals(Ticket.TicketStatus.CLOSED, ticket.status);
        Assertions.assertEquals("C04-00000123", ticket.ticketNumber);
        Assertions.assertEquals("C04", ticket.terminalId);
        Assertions.assertEquals(created, ticket.creationDate);
        Assertions.assertEquals(closed, ticket.closingDate);
        Assertions.assertEquals("SIG", ticket.signature);
        Assertions.assertEquals("GENESIS", ticket.previousSignature);
        Assertions.assertEquals(new BigDecimal("100.0000"), ticket.grandTotal);
        Assertions.assertEquals(2, ticket.printCount);
        Assertions.assertEquals(Ticket.ValuationStatus.VALUATED, ticket.valuationStatus);
        Assertions.assertSame(store, ticket.store);
        Assertions.assertSame(cashier, ticket.cashier);
        Assertions.assertSame(session, ticket.session);
        Assertions.assertEquals("CARD-1", ticket.fidelityCard);
        Assertions.assertEquals("0123456789abcdef", ticket.digitalKey);
        Assertions.assertEquals("buyer@example.com", ticket.customerEmail);
        Assertions.assertEquals(3, ticket.itemCount);
        Assertions.assertEquals(new BigDecimal("8.3333"), ticket.totalExcludingTax);
        Assertions.assertEquals(new BigDecimal("10.0000"), ticket.totalIncludingTax);
        Assertions.assertEquals(new BigDecimal("1.6667"), ticket.totalVat);
    }

    /**
     * The TicketStatus enum exposes exactly OPEN, PARKED, CLOSED and CANCELLED, round-tripping by name.
     */
    @Test
    void ticketStatusEnumValues() {
        Assertions.assertArrayEquals(
                new Ticket.TicketStatus[] {
                        Ticket.TicketStatus.OPEN,
                        Ticket.TicketStatus.PARKED,
                        Ticket.TicketStatus.CLOSED,
                        Ticket.TicketStatus.CANCELLED},
                Ticket.TicketStatus.values());
        Assertions.assertEquals(Ticket.TicketStatus.CANCELLED, Ticket.TicketStatus.valueOf("CANCELLED"));
    }

    /**
     * The ValuationStatus enum exposes exactly NOT_VALUATED, VALUATED and DEGRADED, round-tripping by name.
     */
    @Test
    void valuationStatusEnumValues() {
        Assertions.assertArrayEquals(
                new Ticket.ValuationStatus[] {
                        Ticket.ValuationStatus.NOT_VALUATED,
                        Ticket.ValuationStatus.VALUATED,
                        Ticket.ValuationStatus.DEGRADED},
                Ticket.ValuationStatus.values());
        Assertions.assertEquals(Ticket.ValuationStatus.DEGRADED, Ticket.ValuationStatus.valueOf("DEGRADED"));
    }

    /**
     * addLine appends the given line to the lines collection.
     */
    @Test
    void addLineAppendsLine() {
        Ticket ticket = new Ticket();
        TicketLine line = new TicketLine();
        ticket.addLine(line);
        Assertions.assertEquals(1, ticket.lines.size());
        Assertions.assertSame(line, ticket.lines.get(0));
    }

    /**
     * addPayment appends the given payment to the payments collection.
     */
    @Test
    void addPaymentAppendsPayment() {
        Ticket ticket = new Ticket();
        TicketPayment payment = new CashPayment(BigDecimal.TEN, BigDecimal.TEN);
        ticket.addPayment(payment);
        Assertions.assertEquals(1, ticket.payments.size());
        Assertions.assertSame(payment, ticket.payments.get(0));
    }

    /**
     * getTotalFormatted returns the zero placeholder when the tax-included total is null.
     */
    @Test
    void totalFormattedNullTotalYieldsZeroPlaceholder() {
        Ticket ticket = new Ticket();
        ticket.totalIncludingTax = null;
        Assertions.assertEquals("0,00", ticket.getTotalFormatted());
    }

    /**
     * getTotalFormatted rounds HALF_UP to two decimals and renders the decimal separator as a comma.
     */
    @Test
    void totalFormattedRoundsHalfUpAndUsesComma() {
        Ticket ticket = new Ticket();
        ticket.totalIncludingTax = new BigDecimal("12.345");
        Assertions.assertEquals("12,35", ticket.getTotalFormatted());
    }

    /**
     * getTotalFormatted pads a whole amount to two decimals with a comma separator.
     */
    @Test
    void totalFormattedPadsWholeAmount() {
        Ticket ticket = new Ticket();
        ticket.totalIncludingTax = new BigDecimal("10");
        Assertions.assertEquals("10,00", ticket.getTotalFormatted());
    }

    /**
     * getChecksum hashes the ticket number, creation date, status, total and signature.
     */
    @Test
    void checksumHashesSalientFields() {
        Ticket ticket = new Ticket();
        LocalDateTime created = LocalDateTime.of(2026, 8, 3, 10, 0);
        ticket.ticketNumber = "C04-00000123";
        ticket.creationDate = created;
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.totalIncludingTax = new BigDecimal("10.0000");
        ticket.signature = "SIG";
        int expected = Objects.hash("C04-00000123", created, Ticket.TicketStatus.CLOSED,
                new BigDecimal("10.0000"), "SIG");
        Assertions.assertEquals(expected, ticket.getChecksum());
    }

    /**
     * getChecksum tolerates the null-valued identifying, financial and chaining fields.
     */
    @Test
    void checksumToleratesNulls() {
        Ticket ticket = new Ticket();
        int expected = Objects.hash(null, null, Ticket.TicketStatus.OPEN, null, null);
        Assertions.assertEquals(expected, ticket.getChecksum());
    }

    /**
     * A change in a hashed field changes the checksum (change detection contract).
     */
    @Test
    void checksumChangesWhenFieldChanges() {
        Ticket ticket = new Ticket();
        ticket.totalIncludingTax = new BigDecimal("10.0000");
        int before = ticket.getChecksum();
        ticket.totalIncludingTax = new BigDecimal("20.0000");
        Assertions.assertNotEquals(before, ticket.getChecksum());
    }
}
