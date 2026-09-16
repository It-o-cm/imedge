package com.intermarche.pos.domain.payment;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VoucherPayment}, targeting 100% branch coverage.
 * <p>
 * The class carries no branching logic: both constructors and the CDI
 * {@link VoucherPayment.Factory} members are straight-line, so branch coverage
 * is complete once every member is exercised. The tests assert the applied
 * amount, label and number are forwarded to the fields, that the default
 * constructor leaves the inherited and declared fields at their defaults, that
 * the factory key is "VOUCHER", that {@code create} ignores the tendered amount
 * and produces a numberless, labelless voucher, and that the "VOUCHER"
 * discriminator resolves through {@link TicketPayment#getMethodKey()}. No static
 * finder or persist is reached, so no Panache mocking is required. Each test is
 * fully isolated and asserts absolute expected values.
 */
class VoucherPaymentTest {

    /**
     * The full constructor forwards its argument to the applied amount field.
     */
    @Test
    void fullConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("12.5000");
        VoucherPayment payment = new VoucherPayment(amount, "Chèque cadeau", "V123");
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The full constructor forwards the voucher type label to its field.
     */
    @Test
    void fullConstructorSetsVoucherLabel() {
        VoucherPayment payment = new VoucherPayment(new BigDecimal("5.0000"), "Chèque cadeau", "V123");
        Assertions.assertEquals("Chèque cadeau", payment.voucherLabel);
    }

    /**
     * The full constructor forwards the voucher number to its field.
     */
    @Test
    void fullConstructorSetsVoucherNumber() {
        VoucherPayment payment = new VoucherPayment(new BigDecimal("5.0000"), "Chèque cadeau", "V123");
        Assertions.assertEquals("V123", payment.voucherNumber);
    }

    /**
     * The full constructor accepts a null number for a numberless voucher.
     */
    @Test
    void fullConstructorAcceptsNullVoucherNumber() {
        VoucherPayment payment = new VoucherPayment(new BigDecimal("5.0000"), "Coupon", null);
        Assertions.assertNull(payment.voucherNumber);
    }

    /**
     * The full constructor leaves the registration index at its default zero.
     */
    @Test
    void fullConstructorLeavesIndexAtZero() {
        VoucherPayment payment = new VoucherPayment(new BigDecimal("1.0000"), "Coupon", "N1");
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        VoucherPayment payment = new VoucherPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * The default constructor leaves the voucher label unset (null).
     */
    @Test
    void defaultConstructorLeavesVoucherLabelNull() {
        VoucherPayment payment = new VoucherPayment();
        Assertions.assertNull(payment.voucherLabel);
    }

    /**
     * The default constructor leaves the voucher number unset (null).
     */
    @Test
    void defaultConstructorLeavesVoucherNumberNull() {
        VoucherPayment payment = new VoucherPayment();
        Assertions.assertNull(payment.voucherNumber);
    }

    /**
     * The default constructor leaves the registration index at its default zero.
     */
    @Test
    void defaultConstructorLeavesIndexAtZero() {
        VoucherPayment payment = new VoucherPayment();
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * getMethodKey resolves the "VOUCHER" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsVoucher() {
        VoucherPayment payment = new VoucherPayment(new BigDecimal("3.0000"), "Coupon", "N1");
        Assertions.assertEquals("VOUCHER", payment.getMethodKey());
    }

    /**
     * The factory advertises the "VOUCHER" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsVoucher() {
        VoucherPayment.Factory factory = new VoucherPayment.Factory();
        Assertions.assertEquals("VOUCHER", factory.getKey());
    }

    /**
     * The factory builds a VoucherPayment carrying the applied amount.
     */
    @Test
    void factoryCreateBuildsVoucherPaymentWithAmount() {
        BigDecimal amount = new BigDecimal("20.0000");
        VoucherPayment.Factory factory = new VoucherPayment.Factory();
        TicketPayment payment = factory.create(amount, new BigDecimal("50.0000"));
        Assertions.assertTrue(payment instanceof VoucherPayment);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The factory leaves the label and number null: it builds a bare voucher
     * payment whose identity fields are populated later from the scan.
     */
    @Test
    void factoryCreateLeavesLabelAndNumberNull() {
        VoucherPayment.Factory factory = new VoucherPayment.Factory();
        VoucherPayment payment = (VoucherPayment) factory.create(new BigDecimal("4.0000"), null);
        Assertions.assertNull(payment.voucherLabel);
        Assertions.assertNull(payment.voucherNumber);
    }

    /**
     * The factory ignores the tendered amount: no change is handled on a
     * voucher, so a non-null tender still yields the applied amount unchanged.
     */
    @Test
    void factoryCreateIgnoresTendered() {
        BigDecimal amount = new BigDecimal("7.5000");
        VoucherPayment.Factory factory = new VoucherPayment.Factory();
        TicketPayment payment = factory.create(amount, new BigDecimal("100.0000"));
        Assertions.assertSame(amount, payment.amount);
    }
}
