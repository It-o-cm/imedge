package com.intermarche.pos.domain.payment;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChequePayment}, targeting 100% branch coverage.
 * <p>
 * The class carries no branching logic: both constructors and the CDI
 * {@link ChequePayment.Factory} members are straight-line, so branch coverage is
 * complete once every member is exercised. The tests assert the applied amount
 * is forwarded to {@link TicketPayment}, that the default constructor leaves the
 * inherited fields at their declared defaults, that the factory key is "CHEQUE",
 * that {@code create} ignores the tendered amount (the applied amount is the
 * cheque amount), and that the "CHEQUE" discriminator resolves through
 * {@link TicketPayment#getMethodKey()}. No static finder or persist is reached,
 * so no Panache mocking is required. Each test is fully isolated and asserts
 * absolute expected values.
 */
class ChequePaymentTest {

    /**
     * The amount constructor forwards its argument to the applied amount field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("12.5000");
        ChequePayment payment = new ChequePayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The amount constructor leaves the registration index at its default zero.
     */
    @Test
    void amountConstructorLeavesIndexAtZero() {
        ChequePayment payment = new ChequePayment(new BigDecimal("1.0000"));
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        ChequePayment payment = new ChequePayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * The default constructor leaves the registration index at its default zero.
     */
    @Test
    void defaultConstructorLeavesIndexAtZero() {
        ChequePayment payment = new ChequePayment();
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * getMethodKey resolves the "CHEQUE" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsCheque() {
        ChequePayment payment = new ChequePayment(new BigDecimal("3.0000"));
        Assertions.assertEquals("CHEQUE", payment.getMethodKey());
    }

    /**
     * The factory advertises the "CHEQUE" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsCheque() {
        ChequePayment.Factory factory = new ChequePayment.Factory();
        Assertions.assertEquals("CHEQUE", factory.getKey());
    }

    /**
     * The factory builds a ChequePayment carrying the applied amount.
     */
    @Test
    void factoryCreateBuildsChequePaymentWithAmount() {
        BigDecimal amount = new BigDecimal("20.0000");
        ChequePayment.Factory factory = new ChequePayment.Factory();
        TicketPayment payment = factory.create(amount, new BigDecimal("50.0000"));
        Assertions.assertTrue(payment instanceof ChequePayment);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The factory ignores the tendered amount: the applied amount is the cheque
     * amount, so a null tender still yields a valid cheque payment for it.
     */
    @Test
    void factoryCreateIgnoresTendered() {
        BigDecimal amount = new BigDecimal("7.5000");
        ChequePayment.Factory factory = new ChequePayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertSame(amount, payment.amount);
    }
}
