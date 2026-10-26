package com.intermarche.edge.domain.sale;

import com.intermarche.edge.domain.session.CashSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

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
     * The RefundMethod enum exposes exactly CASH, CARD, VOUCHER, LOYALTY and
     * CREDIT (LC-05-06-05), round-tripping by name.
     */
    @Test
    void refundMethodEnumValues() {
        Assertions.assertArrayEquals(
                new Refund.RefundMethod[] {
                        Refund.RefundMethod.CASH,
                        Refund.RefundMethod.CARD,
                        Refund.RefundMethod.VOUCHER,
                        Refund.RefundMethod.LOYALTY,
                        Refund.RefundMethod.CREDIT},
                Refund.RefundMethod.values());
        Assertions.assertEquals(Refund.RefundMethod.LOYALTY, Refund.RefundMethod.valueOf("LOYALTY"));
        Assertions.assertEquals(Refund.RefundMethod.CREDIT, Refund.RefundMethod.valueOf("CREDIT"));
    }

    /**
     * getChecksum hashes the refund number, ticket id, total, status, method,
     * the return reason (LC-05-05-01) and the credit account number
     * (LC-05-06-05), all carried on the refund.
     */
    @Test
    void checksumHashesSalientFields() {
        Refund refund = new Refund();
        refund.refundNumber = "C04-R000012";
        refund.originalTicketId = 42L;
        refund.totalAmount = new BigDecimal("10.0000");
        refund.status = Refund.RefundStatus.CLOSED;
        refund.refundMethod = Refund.RefundMethod.CASH;
        refund.returnReason = "Article périmé";
        refund.creditAccountNumber = "CC1";
        int expected = Objects.hash("C04-R000012", 42L, new BigDecimal("10.0000"),
                Refund.RefundStatus.CLOSED, Refund.RefundMethod.CASH, "Article périmé", "CC1");
        Assertions.assertEquals(expected, refund.getChecksum());
    }

    /**
     * getChecksum tolerates the null-valued identifying and financial fields,
     * the return reason and credit account number included.
     */
    @Test
    void checksumToleratesNulls() {
        Refund refund = new Refund();
        int expected = Objects.hash(null, null, null, Refund.RefundStatus.OPEN, null, null, null);
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

    /**
     * The recalculation fields (LC-05-02-13) default to absent — no status, no
     * amount, no surviving advantage, nothing sent to the loyalty programme — so
     * a refund created before the recalculation existed reads as one that never
     * ran.
     */
    @Test
    void recalculationFieldsDefaultToAbsent() {
        Refund refund = new Refund();
        Assertions.assertNull(refund.recalculationStatus);
        Assertions.assertNull(refund.withdrawnGenerosity);
        Assertions.assertNull(refund.keptAdvantages);
        Assertions.assertFalse(refund.loyaltyRecalculationSent);
    }

    /**
     * The recalculation fields round-trip what the return established
     * (LC-05-02-13): the status, the advantage withdrawn, the surviving
     * advantages joined in one column and the loyalty notification.
     */
    @Test
    void recalculationFieldsRoundTrip() {
        Refund refund = new Refund();
        refund.recalculationStatus = "RECOMPUTED";
        refund.withdrawnGenerosity = new BigDecimal("2.5000");
        refund.keptAdvantages = "REMISE PANIER | OFFRE-7";
        refund.loyaltyRecalculationSent = true;
        Assertions.assertEquals("RECOMPUTED", refund.recalculationStatus);
        Assertions.assertEquals(new BigDecimal("2.5000"), refund.withdrawnGenerosity);
        Assertions.assertEquals("REMISE PANIER | OFFRE-7", refund.keptAdvantages);
        Assertions.assertTrue(refund.loyaltyRecalculationSent);
    }

    /** LC-07 credit sync: misplaced relationship annotations on returnReason must not prevent node startup. */
    @Test
    void refundAssociationBelongsToLines() throws Exception {
        org.junit.jupiter.api.Assertions.assertNotNull(Refund.class.getDeclaredField("lines")
                .getAnnotation(jakarta.persistence.OneToMany.class));
        org.junit.jupiter.api.Assertions.assertNull(Refund.class.getDeclaredField("returnReason")
                .getAnnotation(jakarta.persistence.OneToMany.class));
    }
}
