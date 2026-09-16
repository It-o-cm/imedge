package com.intermarche.pos.domain.sale;

import com.intermarche.pos.domain.session.CashSession;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Refund}, targeting 100% branch coverage.
 * <p>
 * The class carries no conditional logic: {@link Refund#calculateTotal()} folds
 * the lines through a stream and {@link Refund#getChecksum()} hashes the salient
 * fields, so there are no ternaries or null guards to split. Coverage is
 * therefore complete once the field defaults, both enums, the total computation
 * (with and without lines, so the mapping lambda is exercised) and the checksum
 * are asserted. No static finder or persist is reached, so no Panache mocking is
 * required. Each test is fully isolated and asserts absolute expected values.
 */
class RefundTest {

    /**
     * Builds a refund line carrying the given unit price and quantity.
     *
     * @param price    the unit price including tax
     * @param quantity the refunded quantity
     * @return a populated {@link RefundLine}
     */
    private RefundLine line(String price, String quantity) {
        RefundLine l = new RefundLine();
        l.price = new BigDecimal(price);
        l.quantity = new BigDecimal(quantity);
        return l;
    }

    /**
     * The default constructor sets the lifecycle status to OPEN.
     */
    @Test
    void defaultStatusIsOpen() {
        Refund refund = new Refund();
        Assertions.assertEquals(Refund.RefundStatus.OPEN, refund.status);
    }

    /**
     * The default constructor initializes the lines to an empty, mutable list.
     */
    @Test
    void defaultLinesAreEmpty() {
        Refund refund = new Refund();
        Assertions.assertNotNull(refund.lines);
        Assertions.assertTrue(refund.lines.isEmpty());
    }

    /**
     * The default constructor leaves the refund method unset (null).
     */
    @Test
    void defaultRefundMethodIsNull() {
        Refund refund = new Refund();
        Assertions.assertNull(refund.refundMethod);
    }

    /**
     * All public fields accept and return their assigned values.
     */
    @Test
    void fieldsAreReadWrite() {
        Refund refund = new Refund();
        CashSession session = new CashSession();
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 10, 0);
        refund.refundNumber = "C04-R000012";
        refund.status = Refund.RefundStatus.CLOSED;
        refund.refundMethod = Refund.RefundMethod.VOUCHER;
        refund.originalTicketId = 42L;
        refund.terminalId = "C04";
        refund.session = session;
        refund.creationDate = now;
        refund.totalAmount = new BigDecimal("10.0000");
        refund.totalExcludingTax = new BigDecimal("8.3333");
        refund.totalVat = new BigDecimal("1.6667");
        Assertions.assertEquals("C04-R000012", refund.refundNumber);
        Assertions.assertEquals(Refund.RefundStatus.CLOSED, refund.status);
        Assertions.assertEquals(Refund.RefundMethod.VOUCHER, refund.refundMethod);
        Assertions.assertEquals(42L, refund.originalTicketId);
        Assertions.assertEquals("C04", refund.terminalId);
        Assertions.assertSame(session, refund.session);
        Assertions.assertEquals(now, refund.creationDate);
        Assertions.assertEquals(new BigDecimal("10.0000"), refund.totalAmount);
        Assertions.assertEquals(new BigDecimal("8.3333"), refund.totalExcludingTax);
        Assertions.assertEquals(new BigDecimal("1.6667"), refund.totalVat);
    }

    /**
     * The RefundStatus enum exposes exactly OPEN and CLOSED, round-tripping by name.
     */
    @Test
    void refundStatusEnumValues() {
        Assertions.assertArrayEquals(
                new Refund.RefundStatus[] {Refund.RefundStatus.OPEN, Refund.RefundStatus.CLOSED},
                Refund.RefundStatus.values());
        Assertions.assertEquals(Refund.RefundStatus.CLOSED, Refund.RefundStatus.valueOf("CLOSED"));
    }

    /**
     * The RefundMethod enum exposes exactly CASH, CARD, VOUCHER and LOYALTY, round-tripping by name.
     */
    @Test
    void refundMethodEnumValues() {
        Assertions.assertArrayEquals(
                new Refund.RefundMethod[] {
                        Refund.RefundMethod.CASH,
                        Refund.RefundMethod.CARD,
                        Refund.RefundMethod.VOUCHER,
                        Refund.RefundMethod.LOYALTY},
                Refund.RefundMethod.values());
        Assertions.assertEquals(Refund.RefundMethod.LOYALTY, Refund.RefundMethod.valueOf("LOYALTY"));
    }

    /**
     * calculateTotal over an empty line list yields zero (the reduction identity).
     */
    @Test
    void calculateTotalWithNoLinesIsZero() {
        Refund refund = new Refund();
        refund.calculateTotal();
        Assertions.assertEquals(BigDecimal.ZERO, refund.totalAmount);
    }

    /**
     * calculateTotal sums price times quantity across every line.
     */
    @Test
    void calculateTotalSumsLines() {
        Refund refund = new Refund();
        refund.lines.add(line("2.5000", "3"));
        refund.lines.add(line("1.0000", "4"));
        refund.calculateTotal();
        Assertions.assertEquals(new BigDecimal("11.5000"), refund.totalAmount);
    }

    /**
     * getChecksum hashes the refund number, ticket id, total, status and method.
     */
    @Test
    void checksumHashesSalientFields() {
        Refund refund = new Refund();
        refund.refundNumber = "C04-R000012";
        refund.originalTicketId = 42L;
        refund.totalAmount = new BigDecimal("10.0000");
        refund.status = Refund.RefundStatus.CLOSED;
        refund.refundMethod = Refund.RefundMethod.CASH;
        int expected = Objects.hash("C04-R000012", 42L, new BigDecimal("10.0000"),
                Refund.RefundStatus.CLOSED, Refund.RefundMethod.CASH);
        Assertions.assertEquals(expected, refund.getChecksum());
    }

    /**
     * getChecksum tolerates the null-valued identifying and financial fields.
     */
    @Test
    void checksumToleratesNulls() {
        Refund refund = new Refund();
        int expected = Objects.hash(null, null, null, Refund.RefundStatus.OPEN, null);
        Assertions.assertEquals(expected, refund.getChecksum());
    }

    /**
     * A change in a hashed field changes the checksum (change detection contract).
     */
    @Test
    void checksumChangesWhenFieldChanges() {
        Refund refund = new Refund();
        refund.totalAmount = new BigDecimal("10.0000");
        int before = refund.getChecksum();
        refund.totalAmount = new BigDecimal("20.0000");
        Assertions.assertNotEquals(before, refund.getChecksum());
    }
}
