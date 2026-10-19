package com.intermarche.edge.domain.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * Unit tests for {@link RoundingPayment} and its nested {@link RoundingPayment.Factory},
 * targeting 100% branch coverage.
 * <p>
 * The class is straight-line (no ternary, no null guard) and carries no field of its
 * own: the amount constructor, the JPA default constructor, the inherited
 * {@link TicketPayment#getChecksum()} and the factory members only need to be reached
 * once each. The tests assert the signed amount is forwarded in both directions, that
 * the default constructor leaves inherited fields at their declared defaults, that the
 * inherited checksum folds in the signed amount, that the "ARRONDI" discriminator
 * resolves through {@link TicketPayment#getMethodKey()}, that the factory key is
 * "ARRONDI", and that the factory builds a RoundingPayment carrying the amount while
 * ignoring the tendered argument. No static finder or persist is reached, so no
 * Panache mocking is required. Each test is fully isolated and asserts absolute
 * expected values.
 */
class RoundingPaymentTest {

    /**
     * The amount constructor forwards a positive (rounded-down) signed difference.
     */
    @Test
    void amountConstructorSetsPositiveAmount() {
        BigDecimal amount = new BigDecimal("0.0200");
        RoundingPayment payment = new RoundingPayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The amount constructor forwards a negative (rounded-up) signed difference.
     */
    @Test
    void amountConstructorSetsNegativeAmount() {
        BigDecimal amount = new BigDecimal("-0.0200");
        RoundingPayment payment = new RoundingPayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The amount constructor leaves the registration index at its default zero.
     */
    @Test
    void amountConstructorLeavesIndexAtZero() {
        RoundingPayment payment = new RoundingPayment(new BigDecimal("0.0200"));
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        RoundingPayment payment = new RoundingPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * The default constructor leaves the registration index at its default zero.
     */
    @Test
    void defaultConstructorLeavesIndexAtZero() {
        RoundingPayment payment = new RoundingPayment();
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * The inherited checksum depends on the signed amount: a +0,02 and a −0,02
     * rounding settlement hash differently.
     */
    @Test
    void getChecksumDependsOnSignedAmount() {
        RoundingPayment down = new RoundingPayment(new BigDecimal("0.0200"));
        RoundingPayment up = new RoundingPayment(new BigDecimal("-0.0200"));
        Assertions.assertNotEquals(down.getChecksum(), up.getChecksum());
    }

    /**
     * The inherited checksum is deterministic: equal signed amounts yield the same
     * hash across two independent instances.
     */
    @Test
    void getChecksumIsStableForEqualAmounts() {
        RoundingPayment first = new RoundingPayment(new BigDecimal("0.0200"));
        RoundingPayment second = new RoundingPayment(new BigDecimal("0.0200"));
        Assertions.assertEquals(first.getChecksum(), second.getChecksum());
    }

    /**
     * getMethodKey resolves the "ARRONDI" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsArrondi() {
        RoundingPayment payment = new RoundingPayment(new BigDecimal("0.0200"));
        Assertions.assertEquals("ARRONDI", payment.getMethodKey());
    }

    /**
     * The factory advertises the "ARRONDI" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsArrondi() {
        RoundingPayment.Factory factory = new RoundingPayment.Factory();
        Assertions.assertEquals("ARRONDI", factory.getKey());
    }

    /**
     * The factory builds a RoundingPayment carrying the signed amount.
     */
    @Test
    void factoryCreateBuildsRoundingPaymentWithAmount() {
        BigDecimal amount = new BigDecimal("-0.0300");
        RoundingPayment.Factory factory = new RoundingPayment.Factory();
        TicketPayment payment = factory.create(amount, new BigDecimal("0.0000"));
        Assertions.assertInstanceOf(RoundingPayment.class, payment);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The factory ignores the tendered argument: nothing is tendered for a
     * rounding, so a null tendered still yields a payment carrying the amount.
     */
    @Test
    void factoryCreateIgnoresTendered() {
        BigDecimal amount = new BigDecimal("0.0200");
        RoundingPayment.Factory factory = new RoundingPayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertSame(amount, payment.amount);
    }
}
