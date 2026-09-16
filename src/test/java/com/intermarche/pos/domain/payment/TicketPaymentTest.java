package com.intermarche.pos.domain.payment;

import java.math.BigDecimal;
import java.util.Objects;
import jakarta.persistence.DiscriminatorValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the abstract {@link TicketPayment}, targeting 100% branch
 * coverage of the class itself.
 * <p>
 * The only branching logic lives in {@link TicketPayment#getMethodKey()}: the
 * ternary that returns the discriminator value when the concrete class carries
 * a {@link DiscriminatorValue} annotation, and "UNKNOWN" otherwise. Both arms
 * are exercised through two local concrete subclasses — {@link Discriminated}
 * (annotated) and {@link Undiscriminated} (unannotated) — so no real payment
 * subclass is depended upon. The amount constructor, the protected JPA default
 * constructor and the branch-free {@link TicketPayment#getChecksum()} are also
 * covered. {@code Hibernate.getClass} returns the plain class for a
 * non-proxied instance, so no Panache or Hibernate proxy mocking is required.
 * Each test is fully isolated and asserts absolute expected values.
 */
class TicketPaymentTest {

    /**
     * A concrete payment carrying a JPA discriminator, exercising the non-null
     * arm of {@link TicketPayment#getMethodKey()}.
     */
    @DiscriminatorValue("TEST")
    static class Discriminated extends TicketPayment {

        /**
         * Creates the discriminated payment with its applied amount.
         *
         * @param amount the amount applied to the ticket
         */
        Discriminated(BigDecimal amount) {
            super(amount);
        }

        /**
         * Creates the discriminated payment through the protected default
         * constructor, leaving inherited fields at their declared defaults.
         */
        Discriminated() {
            super();
        }
    }

    /**
     * A concrete payment with no JPA discriminator, exercising the null arm of
     * {@link TicketPayment#getMethodKey()}.
     */
    static class Undiscriminated extends TicketPayment {

        /**
         * Creates the undiscriminated payment with its applied amount.
         *
         * @param amount the amount applied to the ticket
         */
        Undiscriminated(BigDecimal amount) {
            super(amount);
        }
    }

    /**
     * The amount constructor forwards its argument to the applied amount field.
     */
    @Test
    void amountConstructorSetsAmount() {
        BigDecimal amount = new BigDecimal("12.5000");
        Discriminated payment = new Discriminated(amount);
        Assertions.assertSame(amount, payment.amount);
    }

    /**
     * The amount constructor leaves the registration index at its default zero.
     */
    @Test
    void amountConstructorLeavesIndexAtZero() {
        Discriminated payment = new Discriminated(new BigDecimal("1.0000"));
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * The protected default constructor leaves the applied amount unset (null).
     */
    @Test
    void defaultConstructorLeavesAmountNull() {
        Discriminated payment = new Discriminated();
        Assertions.assertNull(payment.amount);
    }

    /**
     * The protected default constructor leaves the index at its default zero.
     */
    @Test
    void defaultConstructorLeavesIndexAtZero() {
        Discriminated payment = new Discriminated();
        Assertions.assertEquals(0, payment.paymentIndex);
    }

    /**
     * getMethodKey returns the declared discriminator value when the concrete
     * class carries a {@link DiscriminatorValue} annotation (non-null arm).
     */
    @Test
    void getMethodKeyReturnsDiscriminatorValue() {
        Discriminated payment = new Discriminated(new BigDecimal("3.0000"));
        Assertions.assertEquals("TEST", payment.getMethodKey());
    }

    /**
     * getMethodKey falls back to "UNKNOWN" when the concrete class carries no
     * {@link DiscriminatorValue} annotation (null arm).
     */
    @Test
    void getMethodKeyReturnsUnknownWhenNoDiscriminator() {
        Undiscriminated payment = new Undiscriminated(new BigDecimal("3.0000"));
        Assertions.assertEquals("UNKNOWN", payment.getMethodKey());
    }

    /**
     * getChecksum hashes the registration index and the applied amount.
     */
    @Test
    void getChecksumHashesIndexAndAmount() {
        BigDecimal amount = new BigDecimal("9.9900");
        Discriminated payment = new Discriminated(amount);
        payment.paymentIndex = 4;
        Assertions.assertEquals(Objects.hash(4, amount), payment.getChecksum());
    }

    /**
     * getChecksum reflects a change of the applied amount, confirming the amount
     * participates in the hash (change detection, not a constant).
     */
    @Test
    void getChecksumReflectsAmountChange() {
        Discriminated first = new Discriminated(new BigDecimal("1.0000"));
        first.paymentIndex = 1;
        Discriminated second = new Discriminated(new BigDecimal("2.0000"));
        second.paymentIndex = 1;
        Assertions.assertNotEquals(first.getChecksum(), second.getChecksum());
    }
}
