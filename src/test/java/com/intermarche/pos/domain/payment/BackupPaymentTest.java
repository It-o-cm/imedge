package com.intermarche.pos.domain.payment;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BackupPayment} and its nested {@link BackupPayment.Factory},
 * targeting 100% branch coverage.
 * <p>
 * The class is straight-line (no ternary, no null guard): the amount constructor,
 * the JPA default constructor, {@link BackupPayment#getChecksum()} and the factory
 * members only need to be reached once each. The tests assert the applied amount is
 * forwarded, that the default constructor leaves inherited fields at their declared
 * defaults, that the checksum folds in the scheme, transaction and manual flag, that
 * the "SECOURS" discriminator resolves through {@link TicketPayment#getMethodKey()},
 * that the factory key is "SECOURS", and that the factory builds a BackupPayment
 * carrying the amount while ignoring the tendered argument. No static finder or
 * persist is reached, so no Panache mocking is required. Each test is fully isolated
 * and asserts absolute expected values.
 */
class BackupPaymentTest {

    /**
     * The amount constructor forwards its applied-amount argument to the field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("42.0000");
        BackupPayment payment = new BackupPayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The amount constructor leaves the registration index at its default zero.
     */
    @Test
    void amountConstructorLeavesIndexAtZero() {
        BackupPayment payment = new BackupPayment(new BigDecimal("1.0000"));
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * The amount constructor leaves the manual flag at its declared default false.
     */
    @Test
    void amountConstructorLeavesManualFalse() {
        BackupPayment payment = new BackupPayment(new BigDecimal("1.0000"));
        Assertions.assertFalse(payment.manual);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        BackupPayment payment = new BackupPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * The default constructor leaves the scheme label unset (null).
     */
    @Test
    void defaultConstructorLeavesMethodLabelNull() {
        BackupPayment payment = new BackupPayment();
        Assertions.assertNull(payment.methodLabel);
    }

    /**
     * The default constructor leaves the transaction number unset (null).
     */
    @Test
    void defaultConstructorLeavesTransactionNumberNull() {
        BackupPayment payment = new BackupPayment();
        Assertions.assertNull(payment.transactionNumber);
    }

    /**
     * getChecksum folds the scheme label into the base checksum: two settlements
     * of the same amount reported under different schemes hash differently.
     */
    @Test
    void getChecksumDependsOnMethodLabel() {
        BackupPayment emv = new BackupPayment(new BigDecimal("10.0000"));
        emv.methodLabel = "CB EMV";
        BackupPayment amex = new BackupPayment(new BigDecimal("10.0000"));
        amex.methodLabel = "AMERICAN EXPRESS";
        Assertions.assertNotEquals(emv.getChecksum(), amex.getChecksum());
    }

    /**
     * getChecksum folds the manual flag into the base checksum: a scanned outcome
     * and a keyed-in outcome of the same amount hash differently.
     */
    @Test
    void getChecksumDependsOnManualFlag() {
        BackupPayment scanned = new BackupPayment(new BigDecimal("10.0000"));
        scanned.manual = false;
        BackupPayment keyed = new BackupPayment(new BigDecimal("10.0000"));
        keyed.manual = true;
        Assertions.assertNotEquals(scanned.getChecksum(), keyed.getChecksum());
    }

    /**
     * getChecksum is deterministic: identical amount, scheme, transaction and
     * manual flag yield the same hash across two independent instances.
     */
    @Test
    void getChecksumIsStableForEqualState() {
        BackupPayment first = new BackupPayment(new BigDecimal("10.0000"));
        first.methodLabel = "CB EMV";
        first.transactionNumber = "TX-1";
        BackupPayment second = new BackupPayment(new BigDecimal("10.0000"));
        second.methodLabel = "CB EMV";
        second.transactionNumber = "TX-1";
        Assertions.assertEquals(first.getChecksum(), second.getChecksum());
    }

    /**
     * getMethodKey resolves the "SECOURS" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsSecours() {
        BackupPayment payment = new BackupPayment(new BigDecimal("3.0000"));
        Assertions.assertEquals("SECOURS", payment.getMethodKey());
    }

    /**
     * The factory advertises the "SECOURS" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsSecours() {
        BackupPayment.Factory factory = new BackupPayment.Factory();
        Assertions.assertEquals("SECOURS", factory.getKey());
    }

    /**
     * The factory builds a BackupPayment carrying the applied amount.
     */
    @Test
    void factoryCreateBuildsBackupPaymentWithAmount() {
        BigDecimal amount = new BigDecimal("20.0000");
        BackupPayment.Factory factory = new BackupPayment.Factory();
        TicketPayment payment = factory.create(amount, new BigDecimal("50.0000"));
        Assertions.assertTrue(payment instanceof BackupPayment);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The factory ignores the tendered argument: nothing is tendered at this till,
     * so a null tendered still yields a well-formed payment carrying the amount.
     */
    @Test
    void factoryCreateIgnoresTendered() {
        BigDecimal amount = new BigDecimal("7.5000");
        BackupPayment.Factory factory = new BackupPayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertSame(amount, payment.amount);
    }
}
