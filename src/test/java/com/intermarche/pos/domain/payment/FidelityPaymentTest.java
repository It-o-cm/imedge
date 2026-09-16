package com.intermarche.pos.domain.payment;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FidelityPayment}, targeting 100% branch coverage.
 * <p>
 * The class carries no branching logic: both constructors and the CDI
 * {@link FidelityPayment.Factory} members are straight-line, so branch coverage
 * is complete once every member is exercised. The tests assert the applied
 * amount is forwarded to {@link TicketPayment}, that the default constructor
 * leaves the inherited fields at their declared defaults, that the factory key
 * is "FIDELITY", that {@code create} ignores the tendered amount (no change on
 * loyalty), and that the "FIDELITY" discriminator resolves through
 * {@link TicketPayment#getMethodKey()}. No static finder or persist is reached,
 * so no Panache mocking is required. Each test is fully isolated and asserts
 * absolute expected values.
 */
class FidelityPaymentTest {

    /**
     * The amount constructor forwards its argument to the applied amount field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("12.5000");
        FidelityPayment payment = new FidelityPayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The amount constructor leaves the registration index at its default zero.
     */
    @Test
    void amountConstructorLeavesIndexAtZero() {
        FidelityPayment payment = new FidelityPayment(new BigDecimal("1.0000"));
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        FidelityPayment payment = new FidelityPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * The default constructor leaves the registration index at its default zero.
     */
    @Test
    void defaultConstructorLeavesIndexAtZero() {
        FidelityPayment payment = new FidelityPayment();
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * getMethodKey resolves the "FIDELITY" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsFidelity() {
        FidelityPayment payment = new FidelityPayment(new BigDecimal("3.0000"));
        Assertions.assertEquals("FIDELITY", payment.getMethodKey());
    }

    /**
     * The factory advertises the "FIDELITY" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsFidelity() {
        FidelityPayment.Factory factory = new FidelityPayment.Factory();
        Assertions.assertEquals("FIDELITY", factory.getKey());
    }

    /**
     * The factory builds a FidelityPayment carrying the applied amount.
     */
    @Test
    void factoryCreateBuildsFidelityPaymentWithAmount() {
        BigDecimal amount = new BigDecimal("20.0000");
        FidelityPayment.Factory factory = new FidelityPayment.Factory();
        TicketPayment payment = factory.create(amount, new BigDecimal("50.0000"));
        Assertions.assertTrue(payment instanceof FidelityPayment);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The factory ignores the tendered amount: no change is handled on loyalty,
     * so a null tender still yields a valid fidelity payment for the amount.
     */
    @Test
    void factoryCreateIgnoresTendered() {
        BigDecimal amount = new BigDecimal("7.5000");
        FidelityPayment.Factory factory = new FidelityPayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertSame(amount, payment.amount);
    }
}
