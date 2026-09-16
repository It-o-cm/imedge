package com.intermarche.pos.domain.payment;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CreditPayment} and its nested {@link CreditPayment.Factory},
 * targeting 100% branch coverage.
 * <p>
 * The class is straight-line (no ternary, no null guard): the amount constructor,
 * the JPA default constructor, {@link CreditPayment#getChecksum()} and the factory
 * members only need to be reached once each. The tests assert the applied amount is
 * forwarded, that the default constructor leaves inherited and own fields at their
 * declared defaults, that the checksum folds in the account and override flag, that
 * the "CREDIT" discriminator resolves through {@link TicketPayment#getMethodKey()},
 * that the factory key is "CREDIT", and that the factory builds a CreditPayment
 * carrying the amount without setting the account. No static finder or persist is
 * reached, so no Panache mocking is required. Each test is fully isolated and
 * asserts absolute expected values.
 */
class CreditPaymentTest {

    /**
     * The amount constructor forwards its applied-amount argument to the field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("42.0000");
        CreditPayment payment = new CreditPayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The amount constructor leaves the registration index at its default zero.
     */
    @Test
    void amountConstructorLeavesIndexAtZero() {
        CreditPayment payment = new CreditPayment(new BigDecimal("1.0000"));
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * The amount constructor leaves the over-limit flag at its declared default false.
     */
    @Test
    void amountConstructorLeavesOverLimitFalse() {
        CreditPayment payment = new CreditPayment(new BigDecimal("1.0000"));
        Assertions.assertFalse(payment.overLimit);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        CreditPayment payment = new CreditPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * The default constructor leaves the account number unset (null).
     */
    @Test
    void defaultConstructorLeavesAccountNumberNull() {
        CreditPayment payment = new CreditPayment();
        Assertions.assertNull(payment.accountNumber);
    }

    /**
     * The default constructor leaves the account name unset (null).
     */
    @Test
    void defaultConstructorLeavesAccountNameNull() {
        CreditPayment payment = new CreditPayment();
        Assertions.assertNull(payment.accountName);
    }

    /**
     * getChecksum folds the account number into the base checksum: two credit
     * lines of the same amount charged to different accounts hash differently.
     */
    @Test
    void getChecksumDependsOnAccountNumber() {
        CreditPayment townHall = new CreditPayment(new BigDecimal("10.0000"));
        townHall.accountNumber = "ACC-1";
        CreditPayment association = new CreditPayment(new BigDecimal("10.0000"));
        association.accountNumber = "ACC-2";
        Assertions.assertNotEquals(townHall.getChecksum(), association.getChecksum());
    }

    /**
     * getChecksum folds the override flag into the base checksum: a settlement
     * passed on an override and one that was not hash differently.
     */
    @Test
    void getChecksumDependsOnOverLimitFlag() {
        CreditPayment plain = new CreditPayment(new BigDecimal("10.0000"));
        plain.overLimit = false;
        CreditPayment overridden = new CreditPayment(new BigDecimal("10.0000"));
        overridden.overLimit = true;
        Assertions.assertNotEquals(plain.getChecksum(), overridden.getChecksum());
    }

    /**
     * getChecksum is deterministic: identical amount, account and override flag
     * yield the same hash across two independent instances.
     */
    @Test
    void getChecksumIsStableForEqualState() {
        CreditPayment first = new CreditPayment(new BigDecimal("10.0000"));
        first.accountNumber = "ACC-1";
        first.accountName = "Town Hall";
        CreditPayment second = new CreditPayment(new BigDecimal("10.0000"));
        second.accountNumber = "ACC-1";
        second.accountName = "Town Hall";
        Assertions.assertEquals(first.getChecksum(), second.getChecksum());
    }

    /**
     * getMethodKey resolves the "CREDIT" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsCredit() {
        CreditPayment payment = new CreditPayment(new BigDecimal("3.0000"));
        Assertions.assertEquals("CREDIT", payment.getMethodKey());
    }

    /**
     * The factory advertises the "CREDIT" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsCredit() {
        CreditPayment.Factory factory = new CreditPayment.Factory();
        Assertions.assertEquals("CREDIT", factory.getKey());
    }

    /**
     * The factory builds a CreditPayment carrying the applied amount.
     */
    @Test
    void factoryCreateBuildsCreditPaymentWithAmount() {
        BigDecimal amount = new BigDecimal("20.0000");
        CreditPayment.Factory factory = new CreditPayment.Factory();
        TicketPayment payment = factory.create(amount, new BigDecimal("50.0000"));
        Assertions.assertTrue(payment instanceof CreditPayment);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The factory does not set the account: it is the generic amount-and-tendered
     * contract, and the caller that knows the debtor sets the account afterwards.
     */
    @Test
    void factoryCreateLeavesAccountUnset() {
        BigDecimal amount = new BigDecimal("7.5000");
        CreditPayment.Factory factory = new CreditPayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertNull(((CreditPayment) payment).accountNumber);
    }
}
