package com.intermarche.edge.domain.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * Unit tests for {@link SocialCardPayment} (LC-08-01-06), targeting 100% branch
 * coverage.
 * <p>
 * The class carries no branching logic: the amount constructor, the JPA default
 * constructor and the CDI {@link SocialCardPayment.Factory} members are all
 * straight-line, so branch coverage is complete once every member is exercised.
 * The tests assert the applied amount is forwarded to {@link TicketPayment}, the
 * default constructor leaves it null, the "CAS" discriminator resolves through
 * {@link TicketPayment#getMethodKey()}, and the factory advertises "CAS" and
 * ignores the tendered amount (a card gives no change). No static finder or
 * persist is reached, so no Panache mocking is required. Each test is fully
 * isolated and asserts absolute expected values.
 */
class SocialCardPaymentTest {

    /**
     * The amount constructor forwards its argument to the applied amount field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("12.5000");
        SocialCardPayment payment = new SocialCardPayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        SocialCardPayment payment = new SocialCardPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * getMethodKey resolves the "CAS" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsCas() {
        SocialCardPayment payment = new SocialCardPayment(new BigDecimal("3.0000"));
        Assertions.assertEquals("CAS", payment.getMethodKey());
    }

    /**
     * The factory advertises the "CAS" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsCas() {
        SocialCardPayment.Factory factory = new SocialCardPayment.Factory();
        Assertions.assertEquals("CAS", factory.getKey());
    }

    /**
     * The factory builds a SocialCardPayment carrying the applied amount, ignoring
     * the tendered amount (a card gives no change).
     */
    @Test
    void factoryCreateBuildsSocialCardPaymentIgnoringTendered() {
        BigDecimal amount = new BigDecimal("20.0000");
        SocialCardPayment.Factory factory = new SocialCardPayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertInstanceOf(SocialCardPayment.class, payment);
        Assertions.assertSame(amount, payment.amount);
    }
}
