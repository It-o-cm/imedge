package com.intermarche.pos.domain.payment;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TicketRestoPayment}, targeting 100% branch coverage.
 * <p>
 * The class carries no branching logic: both constructors and the CDI
 * {@link TicketRestoPayment.Factory} members are straight-line, so branch
 * coverage is complete once every member is exercised. The tests assert the
 * applied amount is forwarded to {@link TicketPayment}, that the default
 * constructor leaves the inherited fields at their declared defaults, that the
 * factory key is "TR", that {@code create} ignores the tendered amount (no
 * change on meal vouchers by store rule), and that the "TR" discriminator
 * resolves through {@link TicketPayment#getMethodKey()}. No static finder or
 * persist is reached, so no Panache mocking is required. Each test is fully
 * isolated and asserts absolute expected values.
 */
class TicketRestoPaymentTest {

    /**
     * The amount constructor forwards its argument to the applied amount field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("12.5000");
        TicketRestoPayment payment = new TicketRestoPayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The amount constructor leaves the registration index at its default zero.
     */
    @Test
    void amountConstructorLeavesIndexAtZero() {
        TicketRestoPayment payment = new TicketRestoPayment(new BigDecimal("1.0000"));
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        TicketRestoPayment payment = new TicketRestoPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * The default constructor leaves the registration index at its default zero.
     */
    @Test
    void defaultConstructorLeavesIndexAtZero() {
        TicketRestoPayment payment = new TicketRestoPayment();
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * getMethodKey resolves the "TR" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsTr() {
        TicketRestoPayment payment = new TicketRestoPayment(new BigDecimal("3.0000"));
        Assertions.assertEquals("TR", payment.getMethodKey());
    }

    /**
     * The factory advertises the "TR" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsTr() {
        TicketRestoPayment.Factory factory = new TicketRestoPayment.Factory();
        Assertions.assertEquals("TR", factory.getKey());
    }

    /**
     * The factory builds a TicketRestoPayment carrying the applied amount.
     */
    @Test
    void factoryCreateBuildsTicketRestoPaymentWithAmount() {
        BigDecimal amount = new BigDecimal("20.0000");
        TicketRestoPayment.Factory factory = new TicketRestoPayment.Factory();
        TicketPayment payment = factory.create(amount, new BigDecimal("50.0000"));
        Assertions.assertTrue(payment instanceof TicketRestoPayment);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The factory ignores the tendered amount: no change is handled on meal
     * vouchers, so a null tender still yields a valid payment for the amount.
     */
    @Test
    void factoryCreateIgnoresTendered() {
        BigDecimal amount = new BigDecimal("7.5000");
        TicketRestoPayment.Factory factory = new TicketRestoPayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertSame(amount, payment.amount);
    }
}
