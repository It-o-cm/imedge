package com.intermarche.pos.domain.payment;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** Accounting identity and factory coverage for LC-07-05-05. */
class DeferredChequePaymentTest {

    /** LC-07-05-05: JPA construction leaves the amount unset. */
    @Test
    void defaultConstruction() {
        assertNull(new DeferredChequePayment().amount);
    }

    /** LC-07-05-05: amount and discriminator survive construction. */
    @Test
    void distinctAccountingIdentity() {
        DeferredChequePayment payment = new DeferredChequePayment(BigDecimal.TEN);
        assertEquals(BigDecimal.TEN, payment.amount);
        assertEquals("CHEQUE_DEFERRED", payment.getMethodKey());
        assertEquals(DeferredChequePayment.class, PaymentTypes.forKey("CHEQUE_DEFERRED"));
    }

    /** LC-07-05-05: the factory ignores tendered cash and keeps the cheque amount. */
    @Test
    void factoryWithTenderedAmount() {
        DeferredChequePayment.Factory factory = new DeferredChequePayment.Factory();
        assertEquals("CHEQUE_DEFERRED", factory.getKey());
        TicketPayment payment = factory.create(BigDecimal.ONE, BigDecimal.TEN);
        assertInstanceOf(DeferredChequePayment.class, payment);
        assertEquals(BigDecimal.ONE, payment.amount);
    }

    /** LC-07-05-05: a null tendered amount also creates a valid cheque. */
    @Test
    void factoryWithNullTenderedAmount() {
        assertEquals(BigDecimal.ONE, new DeferredChequePayment.Factory().create(BigDecimal.ONE, null).amount);
    }
}
