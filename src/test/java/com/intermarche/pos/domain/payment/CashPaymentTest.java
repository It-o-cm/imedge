package com.intermarche.pos.domain.payment;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CashPayment}, targeting 100% branch coverage.
 * <p>
 * The only branch in the class is the {@code tendered != null ? tendered :
 * amount} ternary of {@link CashPayment.Factory#create}; both arms are
 * exercised below. The remaining members — the two constructors,
 * {@link CashPayment#getChecksum()} and {@link CashPayment.Factory#getKey()} —
 * are straight-line, so they only need to be reached once. The tests assert the
 * applied and tendered amounts are forwarded, that the default constructor
 * leaves inherited fields at their declared defaults, that the checksum folds in
 * the tendered amount (a change of tender changes the hash), that the factory
 * key is "CASH", and that the "CASH" discriminator resolves through
 * {@link TicketPayment#getMethodKey()}. No static finder or persist is reached,
 * so no Panache mocking is required. Each test is fully isolated and asserts
 * absolute expected values.
 */
class CashPaymentTest {

    /**
     * The amount constructor forwards its applied-amount argument to the field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("12.5000");
        CashPayment payment = new CashPayment(amount, new BigDecimal("20.0000"));
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The amount constructor forwards its tendered-amount argument to the field.
     */
    @Test
    void amountConstructorSetsTenderedAmount() {
        BigDecimal tendered = new BigDecimal("20.0000");
        CashPayment payment = new CashPayment(new BigDecimal("12.5000"), tendered);
        Assertions.assertSame(tendered, payment.tenderedAmount);
    }

    /**
     * The amount constructor leaves the registration index at its default zero.
     */
    @Test
    void amountConstructorLeavesIndexAtZero() {
        CashPayment payment = new CashPayment(new BigDecimal("1.0000"), new BigDecimal("1.0000"));
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        CashPayment payment = new CashPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * The JPA default constructor leaves the tendered amount unset (null).
     */
    @Test
    void defaultConstructorLeavesTenderedAmountNull() {
        CashPayment payment = new CashPayment();
        Assertions.assertNull(payment.tenderedAmount);
    }

    /**
     * The default constructor leaves the registration index at its default zero.
     */
    @Test
    void defaultConstructorLeavesIndexAtZero() {
        CashPayment payment = new CashPayment();
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * getChecksum folds the tendered amount into the base checksum: two payments
     * with the same applied amount but different tenders hash differently.
     */
    @Test
    void getChecksumDependsOnTenderedAmount() {
        CashPayment exact = new CashPayment(new BigDecimal("10.0000"), new BigDecimal("10.0000"));
        CashPayment overpaid = new CashPayment(new BigDecimal("10.0000"), new BigDecimal("20.0000"));
        Assertions.assertNotEquals(exact.getChecksum(), overpaid.getChecksum());
    }

    /**
     * getChecksum is deterministic: identical applied and tendered amounts yield
     * the same hash across two independent instances.
     */
    @Test
    void getChecksumIsStableForEqualAmounts() {
        CashPayment first = new CashPayment(new BigDecimal("10.0000"), new BigDecimal("15.0000"));
        CashPayment second = new CashPayment(new BigDecimal("10.0000"), new BigDecimal("15.0000"));
        Assertions.assertEquals(first.getChecksum(), second.getChecksum());
    }

    /**
     * getMethodKey resolves the "CASH" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsCash() {
        CashPayment payment = new CashPayment(new BigDecimal("3.0000"), new BigDecimal("3.0000"));
        Assertions.assertEquals("CASH", payment.getMethodKey());
    }

    /**
     * The factory advertises the "CASH" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsCash() {
        CashPayment.Factory factory = new CashPayment.Factory();
        Assertions.assertEquals("CASH", factory.getKey());
    }

    /**
     * The factory builds a CashPayment carrying the applied amount.
     */
    @Test
    void factoryCreateBuildsCashPaymentWithAmount() {
        BigDecimal amount = new BigDecimal("20.0000");
        CashPayment.Factory factory = new CashPayment.Factory();
        TicketPayment payment = factory.create(amount, new BigDecimal("50.0000"));
        Assertions.assertTrue(payment instanceof CashPayment);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The factory keeps a non-null tendered amount as-is (the customer handed
     * over more than the applied amount): the non-null arm of the ternary.
     */
    @Test
    void factoryCreateKeepsNonNullTendered() {
        BigDecimal amount = new BigDecimal("20.0000");
        BigDecimal tendered = new BigDecimal("50.0000");
        CashPayment.Factory factory = new CashPayment.Factory();
        TicketPayment payment = factory.create(amount, tendered);
        Assertions.assertSame(tendered, ((CashPayment) payment).tenderedAmount);
    }

    /**
     * The factory falls back to the applied amount when the tendered amount is
     * null (the exact sum was given): the null arm of the ternary.
     */
    @Test
    void factoryCreateDefaultsNullTenderedToAmount() {
        BigDecimal amount = new BigDecimal("7.5000");
        CashPayment.Factory factory = new CashPayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertSame(amount, ((CashPayment) payment).tenderedAmount);
    }
}
