package com.intermarche.edge.domain.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * Unit tests for {@link TrdCardPayment} (EXP-60), targeting 100% branch
 * coverage.
 * <p>
 * The class carries no branching logic: the amount constructor, the JPA default
 * constructor and the CDI {@link TrdCardPayment.Factory} members are all
 * straight-line, so branch coverage is complete once every member is exercised.
 * The tests assert the applied amount is forwarded to {@link TicketPayment}, the
 * default constructor leaves it null, the "TRD" discriminator resolves through
 * {@link TicketPayment#getMethodKey()}, and the factory advertises "TRD" and
 * ignores the tendered amount (no change on meal vouchers). No static finder or
 * persist is reached, so no Panache mocking is required. Each test is fully
 * isolated and asserts absolute expected values.
 */
class TrdCardPaymentTest {

    /**
     * The amount constructor forwards its argument to the applied amount field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("12.5000");
        TrdCardPayment payment = new TrdCardPayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        TrdCardPayment payment = new TrdCardPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * getMethodKey resolves the "TRD" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsTrd() {
        TrdCardPayment payment = new TrdCardPayment(new BigDecimal("3.0000"));
        Assertions.assertEquals("TRD", payment.getMethodKey());
    }

    /**
     * The factory advertises the "TRD" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsTrd() {
        TrdCardPayment.Factory factory = new TrdCardPayment.Factory();
        Assertions.assertEquals("TRD", factory.getKey());
    }

    /**
     * The factory builds a TrdCardPayment carrying the applied amount, ignoring
     * the tendered amount (no change on meal vouchers).
     */
    @Test
    void factoryCreateBuildsTrdCardPaymentIgnoringTendered() {
        BigDecimal amount = new BigDecimal("20.0000");
        TrdCardPayment.Factory factory = new TrdCardPayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertInstanceOf(TrdCardPayment.class, payment);
        Assertions.assertSame(amount, payment.amount);
    }
}
