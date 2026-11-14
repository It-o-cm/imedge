package com.intermarche.edge.domain.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * Unit tests for {@link RestoFlashPayment} (EXP-67), targeting 100% branch
 * coverage.
 * <p>
 * The class carries no branching logic: the amount constructor, the JPA default
 * constructor and the CDI {@link RestoFlashPayment.Factory} members are all
 * straight-line. The tests assert the applied amount is forwarded, the default
 * constructor leaves it null, the "RESTOFLASH" discriminator resolves through
 * {@link TicketPayment#getMethodKey()}, and the factory advertises "RESTOFLASH"
 * and ignores the tendered amount. No Panache mocking is required. Each test is
 * fully isolated and asserts absolute expected values.
 */
class RestoFlashPaymentTest {

    /**
     * The amount constructor forwards its argument to the applied amount field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("9.9000");
        RestoFlashPayment payment = new RestoFlashPayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        RestoFlashPayment payment = new RestoFlashPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * getMethodKey resolves the "RESTOFLASH" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsRestoFlash() {
        RestoFlashPayment payment = new RestoFlashPayment(new BigDecimal("3.0000"));
        Assertions.assertEquals("RESTOFLASH", payment.getMethodKey());
    }

    /**
     * The factory advertises the "RESTOFLASH" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsRestoFlash() {
        RestoFlashPayment.Factory factory = new RestoFlashPayment.Factory();
        Assertions.assertEquals("RESTOFLASH", factory.getKey());
    }

    /**
     * The factory builds a RestoFlashPayment carrying the applied amount,
     * ignoring the tendered amount.
     */
    @Test
    void factoryCreateBuildsRestoFlashPaymentIgnoringTendered() {
        BigDecimal amount = new BigDecimal("15.0000");
        RestoFlashPayment.Factory factory = new RestoFlashPayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertInstanceOf(RestoFlashPayment.class, payment);
        Assertions.assertSame(amount, payment.amount);
    }
}
