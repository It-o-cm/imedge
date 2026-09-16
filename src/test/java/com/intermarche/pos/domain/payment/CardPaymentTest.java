package com.intermarche.pos.domain.payment;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CardPayment}, targeting 100% branch coverage.
 * <p>
 * The class carries no branching logic: both constructors and the CDI
 * {@link CardPayment.Factory} members are straight-line, so branch coverage is
 * complete once every member is exercised. The tests assert the applied amount
 * is forwarded to {@link TicketPayment}, that the default constructor leaves the
 * inherited fields at their declared defaults, that the factory key is "CARD",
 * that {@code create} ignores the tendered amount (no change on card), and that
 * the "CARD" discriminator resolves through {@link TicketPayment#getMethodKey()}.
 * No static finder or persist is reached, so no Panache mocking is required.
 * Each test is fully isolated and asserts absolute expected values.
 */
class CardPaymentTest {

    /**
     * The amount constructor forwards its argument to the applied amount field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("12.5000");
        CardPayment payment = new CardPayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The amount constructor leaves the registration index at its default zero.
     */
    @Test
    void amountConstructorLeavesIndexAtZero() {
        CardPayment payment = new CardPayment(new BigDecimal("1.0000"));
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        CardPayment payment = new CardPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * The default constructor leaves the registration index at its default zero.
     */
    @Test
    void defaultConstructorLeavesIndexAtZero() {
        CardPayment payment = new CardPayment();
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * getMethodKey resolves the "CARD" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsCard() {
        CardPayment payment = new CardPayment(new BigDecimal("3.0000"));
        Assertions.assertEquals("CARD", payment.getMethodKey());
    }

    /**
     * A fresh card payment defaults its monetique traces: no authorization
     * number and not degraded (BO-04-01-08/47/49).
     */
    @Test
    void monetiqueTracesDefaultToEmpty() {
        CardPayment payment = new CardPayment(new BigDecimal("3.0000"));
        Assertions.assertNull(payment.authorizationNumber);
        Assertions.assertFalse(payment.degradedMode);
    }

    /**
     * The monetique traces are plain persisted fields the sync ingestion and the
     * register payment path write: they store and return the terminal values.
     */
    @Test
    void monetiqueTracesStoreTheirValues() {
        CardPayment payment = new CardPayment(new BigDecimal("3.0000"));
        payment.authorizationNumber = "987654";
        payment.degradedMode = true;
        Assertions.assertEquals("987654", payment.authorizationNumber);
        Assertions.assertTrue(payment.degradedMode);
    }

    /**
     * The factory advertises the "CARD" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsCard() {
        CardPayment.Factory factory = new CardPayment.Factory();
        Assertions.assertEquals("CARD", factory.getKey());
    }

    /**
     * The factory builds a CardPayment carrying the applied amount.
     */
    @Test
    void factoryCreateBuildsCardPaymentWithAmount() {
        BigDecimal amount = new BigDecimal("20.0000");
        CardPayment.Factory factory = new CardPayment.Factory();
        TicketPayment payment = factory.create(amount, new BigDecimal("50.0000"));
        Assertions.assertTrue(payment instanceof CardPayment);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The factory ignores the tendered amount: no change is handled on card, so
     * a null tender still yields a valid card payment for the applied amount.
     */
    @Test
    void factoryCreateIgnoresTendered() {
        BigDecimal amount = new BigDecimal("7.5000");
        CardPayment.Factory factory = new CardPayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertSame(amount, payment.amount);
    }
}
