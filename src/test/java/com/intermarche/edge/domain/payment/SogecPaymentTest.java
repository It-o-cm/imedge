package com.intermarche.edge.domain.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * Unit tests for {@link SogecPayment} (EXP-58), targeting 100% branch coverage.
 * <p>
 * The class carries no branching logic: the amount constructor, the JPA default
 * constructor and the CDI {@link SogecPayment.Factory} members are all
 * straight-line. The tests assert the applied amount is forwarded, the default
 * constructor leaves it null, the "SOGEC" discriminator resolves through
 * {@link TicketPayment#getMethodKey()}, and the factory advertises "SOGEC" and
 * ignores the tendered amount. No Panache mocking is required. Each test is
 * fully isolated and asserts absolute expected values.
 */
class SogecPaymentTest {

    /**
     * The amount constructor forwards its argument to the applied amount field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("2.0000");
        SogecPayment payment = new SogecPayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        SogecPayment payment = new SogecPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * getMethodKey resolves the "SOGEC" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsSogec() {
        SogecPayment payment = new SogecPayment(new BigDecimal("3.0000"));
        Assertions.assertEquals("SOGEC", payment.getMethodKey());
    }

    /**
     * The factory advertises the "SOGEC" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsSogec() {
        SogecPayment.Factory factory = new SogecPayment.Factory();
        Assertions.assertEquals("SOGEC", factory.getKey());
    }

    /**
     * The factory builds a SogecPayment carrying the applied amount, ignoring
     * the tendered amount.
     */
    @Test
    void factoryCreateBuildsSogecPaymentIgnoringTendered() {
        BigDecimal amount = new BigDecimal("4.0000");
        SogecPayment.Factory factory = new SogecPayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertInstanceOf(SogecPayment.class, payment);
        Assertions.assertSame(amount, payment.amount);
    }
}
