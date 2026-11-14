package com.intermarche.edge.domain.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * Unit tests for {@link SupplierGiftCardPayment} (EXP-66), targeting 100%
 * branch coverage.
 * <p>
 * The class carries no branching logic: the amount constructor, the JPA default
 * constructor and the CDI {@link SupplierGiftCardPayment.Factory} members are
 * all straight-line. The tests assert the applied amount is forwarded, the
 * default constructor leaves it null, the "GIFTCARD" discriminator resolves
 * through {@link TicketPayment#getMethodKey()}, and the factory advertises
 * "GIFTCARD" and ignores the tendered amount. No Panache mocking is required.
 * Each test is fully isolated and asserts absolute expected values.
 */
class SupplierGiftCardPaymentTest {

    /**
     * The amount constructor forwards its argument to the applied amount field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("25.0000");
        SupplierGiftCardPayment payment = new SupplierGiftCardPayment(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The JPA default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        SupplierGiftCardPayment payment = new SupplierGiftCardPayment();
        Assertions.assertNull(payment.amount);
    }

    /**
     * getMethodKey resolves the "GIFTCARD" discriminator declared on the class.
     */
    @Test
    void getMethodKeyReturnsGiftCard() {
        SupplierGiftCardPayment payment = new SupplierGiftCardPayment(new BigDecimal("3.0000"));
        Assertions.assertEquals("GIFTCARD", payment.getMethodKey());
    }

    /**
     * The factory advertises the "GIFTCARD" method key used for CDI lookup.
     */
    @Test
    void factoryKeyIsGiftCard() {
        SupplierGiftCardPayment.Factory factory = new SupplierGiftCardPayment.Factory();
        Assertions.assertEquals("GIFTCARD", factory.getKey());
    }

    /**
     * The factory builds a SupplierGiftCardPayment carrying the applied amount,
     * ignoring the tendered amount.
     */
    @Test
    void factoryCreateBuildsGiftCardPaymentIgnoringTendered() {
        BigDecimal amount = new BigDecimal("30.0000");
        SupplierGiftCardPayment.Factory factory = new SupplierGiftCardPayment.Factory();
        TicketPayment payment = factory.create(amount, null);
        Assertions.assertInstanceOf(SupplierGiftCardPayment.class, payment);
        Assertions.assertSame(amount, payment.amount);
    }
}
