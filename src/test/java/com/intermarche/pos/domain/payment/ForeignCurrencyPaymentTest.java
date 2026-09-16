package com.intermarche.pos.domain.payment;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ForeignCurrencyPayment} and its nested
 * {@link ForeignCurrencyPayment.Factory}, targeting 100% branch coverage.
 * <p>
 * The class is straight-line (no ternary, no null guard): the amount constructor,
 * the JPA default constructor, {@link ForeignCurrencyPayment#getChecksum()} and the
 * factory members only need to be reached once each. The tests assert the euro
 * amount is forwarded, that the default constructor leaves inherited and own fields
 * at their declared defaults, that the checksum folds in the currency, foreign
 * amount and frozen rate, that the "DEVISE" discriminator resolves through
 * {@link TicketPayment#getMethodKey()}, that the factory key is "DEVISE", and that
 * the factory builds a ForeignCurrencyPayment carrying the amount without setting
 * the currency fields. No static finder or persist is reached, so no Panache mocking
 * is required. Each test is fully isolated and asserts absolute expected values.
 */
class ForeignCurrencyPaymentTest {

    /**
     * The amount constructor forwards its euro-value argument to the field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("52.6500");
        ForeignCurrencyPayment payment = new ForeignCurrencyPayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The amount constructor leaves the registration index at its default zero.
     */
    @Test
    void amountConstructorLeavesIndexAtZero() {
        ForeignCurrencyPayment payment = new ForeignCurrencyPayment(new BigDecimal("1.0000"));
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * The JPA default constructor leaves the euro amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        ForeignCurrencyPayment payment = new ForeignCurrencyPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * The default constructor leaves the currency code unset (null).
     */
    @Test
    void defaultConstructorLeavesCurrencyCodeNull() {
        ForeignCurrencyPayment payment = new ForeignCurrencyPayment();
        Assertions.assertNull(payment.currencyCode);
    }

    /**
     * The default constructor leaves the foreign amount unset (null).
     */
    @Test
    void defaultConstructorLeavesForeignAmountNull() {
        ForeignCurrencyPayment payment = new ForeignCurrencyPayment();
        Assertions.assertNull(payment.foreignAmount);
    }

    /**
     * The default constructor leaves the exchange rate unset (null).
     */
    @Test
    void defaultConstructorLeavesExchangeRateNull() {
        ForeignCurrencyPayment payment = new ForeignCurrencyPayment();
        Assertions.assertNull(payment.exchangeRate);
    }

    /**
     * getChecksum folds the currency code into the base checksum: the same euro
     * value handed over in two currencies hashes differently.
     */
    @Test
    void getChecksumDependsOnCurrencyCode() {
        ForeignCurrencyPayment chf = new ForeignCurrencyPayment(new BigDecimal("52.6500"));
        chf.currencyCode = "CHF";
        ForeignCurrencyPayment usd = new ForeignCurrencyPayment(new BigDecimal("52.6500"));
        usd.currencyCode = "USD";
        Assertions.assertNotEquals(chf.getChecksum(), usd.getChecksum());
    }

    /**
     * getChecksum folds the frozen rate into the base checksum: the same euro value
     * booked at two rates hashes differently.
     */
    @Test
    void getChecksumDependsOnExchangeRate() {
        ForeignCurrencyPayment low = new ForeignCurrencyPayment(new BigDecimal("52.6500"));
        low.exchangeRate = new BigDecimal("1.053000");
        ForeignCurrencyPayment high = new ForeignCurrencyPayment(new BigDecimal("52.6500"));
        high.exchangeRate = new BigDecimal("1.053100");
        Assertions.assertNotEquals(low.getChecksum(), high.getChecksum());
    }

    /**
     * getChecksum is deterministic: identical euro amount, currency, foreign amount
     * and rate yield the same hash across two independent instances.
     */
    @Test
    void getChecksumIsStableForEqualState() {
        ForeignCurrencyPayment first = new ForeignCurrencyPayment(new BigDecimal("52.6500"));
        first.currencyCode = "CHF";
        first.foreignAmount = new BigDecimal("50.0000");
        first.exchangeRate = new BigDecimal("1.053000");
        ForeignCurrencyPayment second = new ForeignCurrencyPayment(new BigDecimal("52.6500"));
        second.currencyCode = "CHF";
        second.foreignAmount = new BigDecimal("50.0000");
        second.exchangeRate = new BigDecimal("1.053000");
        Assertions.assertEquals(first.getChecksum(), second.getChecksum());
    }

    /**
     * getMethodKey resolves the "DEVISE" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsDevise() {
        ForeignCurrencyPayment payment = new ForeignCurrencyPayment(new BigDecimal("3.0000"));
        Assertions.assertEquals("DEVISE", payment.getMethodKey());
    }

    /**
     * The factory advertises the "DEVISE" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsDevise() {
        ForeignCurrencyPayment.Factory factory = new ForeignCurrencyPayment.Factory();
        Assertions.assertEquals("DEVISE", factory.getKey());
    }

    /**
     * The factory builds a ForeignCurrencyPayment carrying the euro amount.
     */
    @Test
    void factoryCreateBuildsForeignCurrencyPaymentWithAmount() {
        BigDecimal amount = new BigDecimal("52.6500");
        ForeignCurrencyPayment.Factory factory = new ForeignCurrencyPayment.Factory();
        TicketPayment payment = factory.create(amount, new BigDecimal("50.0000"));
        Assertions.assertTrue(payment instanceof ForeignCurrencyPayment);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The factory does not set the currency fields: it is the generic
     * amount-and-tendered contract, and the caller that knows them sets them after.
     */
    @Test
    void factoryCreateLeavesCurrencyUnset() {
        BigDecimal amount = new BigDecimal("52.6500");
        ForeignCurrencyPayment.Factory factory = new ForeignCurrencyPayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertNull(((ForeignCurrencyPayment) payment).currencyCode);
    }
}
