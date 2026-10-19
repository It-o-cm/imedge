package com.intermarche.pos.domain.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * Unit tests for {@link DepositPayment}, targeting 100% branch coverage.
 * <p>
 * The class carries no branching logic: both constructors, the CDI
 * {@link DepositPayment.Factory} members and the checksum are straight-line,
 * so branch coverage is complete once every member is exercised. The tests
 * assert the applied amount is forwarded to {@link TicketPayment}, that the
 * deposit's VAT detail and order code are held on the row (LC-06-03-08), that
 * the checksum distinguishes two recoveries of the same amount on two orders,
 * that the factory key is "ACOMPTE" and that {@code create} ignores the
 * tendered amount (nothing is handed over on a recovery). No static finder or
 * persist is reached, so no Panache mocking is required. Each test is fully
 * isolated and asserts absolute expected values.
 */
class DepositPaymentTest {

    /**
     * The amount constructor forwards its argument to the applied amount field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("50.0000");
        DepositPayment payment = new DepositPayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The amount constructor leaves the registration index at its default zero.
     */
    @Test
    void amountConstructorLeavesIndexAtZero() {
        DepositPayment payment = new DepositPayment(new BigDecimal("50.0000"));
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * The amount constructor leaves the VAT detail and the order code unset
     * (null): the persistence fills them from the payment entry.
     */
    @Test
    void amountConstructorLeavesDetailUnset() {
        DepositPayment payment = new DepositPayment(new BigDecimal("50.0000"));
        Assertions.assertNull(payment.vatDetail);
        Assertions.assertNull(payment.orderCode);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        DepositPayment payment = new DepositPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * getMethodKey resolves the "ACOMPTE" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsAcompte() {
        DepositPayment payment = new DepositPayment(new BigDecimal("50.0000"));
        Assertions.assertEquals("ACOMPTE", payment.getMethodKey());
    }

    /**
     * The checksum distinguishes two recoveries of the same amount and index on
     * two orders: the order code and the VAT detail are part of the row.
     */
    @Test
    void checksumDistinguishesOrdersAndDetails() {
        DepositPayment first = new DepositPayment(new BigDecimal("50.00"));
        first.paymentIndex = 1;
        first.vatDetail = "0.2:8.33";
        first.orderCode = "C04-CMD000001";
        DepositPayment second = new DepositPayment(new BigDecimal("50.00"));
        second.paymentIndex = 1;
        second.vatDetail = "0.2:8.33";
        second.orderCode = "C04-CMD000002";
        DepositPayment sameCodeOtherDetail = new DepositPayment(new BigDecimal("50.00"));
        sameCodeOtherDetail.paymentIndex = 1;
        sameCodeOtherDetail.vatDetail = "0.055:2.38";
        sameCodeOtherDetail.orderCode = "C04-CMD000001";
        Assertions.assertNotEquals(first.getChecksum(), second.getChecksum());
        Assertions.assertNotEquals(first.getChecksum(), sameCodeOtherDetail.getChecksum());
        DepositPayment identical = new DepositPayment(new BigDecimal("50.00"));
        identical.paymentIndex = 1;
        identical.vatDetail = "0.2:8.33";
        identical.orderCode = "C04-CMD000001";
        Assertions.assertEquals(first.getChecksum(), identical.getChecksum());
    }

    /**
     * The factory advertises the "ACOMPTE" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsAcompte() {
        DepositPayment.Factory factory = new DepositPayment.Factory();
        Assertions.assertEquals("ACOMPTE", factory.getKey());
    }

    /**
     * The factory builds a DepositPayment carrying the applied amount.
     */
    @Test
    void factoryCreateBuildsDepositPaymentWithAmount() {
        BigDecimal amount = new BigDecimal("50.0000");
        DepositPayment.Factory factory = new DepositPayment.Factory();
        TicketPayment payment = factory.create(amount, new BigDecimal("100.0000"));
        Assertions.assertInstanceOf(DepositPayment.class, payment);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The factory ignores the tendered amount: nothing is handed over on a
     * deposit recovery, so a null tender still yields a valid payment.
     */
    @Test
    void factoryCreateIgnoresTendered() {
        BigDecimal amount = new BigDecimal("50.0000");
        DepositPayment.Factory factory = new DepositPayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertSame(amount, payment.amount);
    }
}
