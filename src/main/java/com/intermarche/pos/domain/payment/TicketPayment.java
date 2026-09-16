package com.intermarche.pos.domain.payment;

import com.intermarche.pos.domain.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.hibernate.Hibernate;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A payment registered on a ticket, specialized per method through
 * single-table inheritance (discriminator column payment_method).
 * <p>
 * {@link #paymentIndex} is the 1-based registration order: restart recovery
 * and the store-node ingestion rebuild the in-memory list in that order, so
 * the cashier sees the payments as they were taken. The {@link Factory}
 * instances are discovered by CDI and indexed by key in two places — the
 * draft persistence (saving in-memory entries) and the sync ingestion
 * (rebuilding pushed tickets); adding a payment method is therefore one new
 * subclass with its factory, nothing else. {@code getMethodKey()} resolves
 * the key from the discriminator annotation, unwrapping Hibernate proxies —
 * the same key strings flow through the session reports (totals per method)
 * and the sync payloads.
 */
@Entity
@Table(name = "ticket_payments")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "payment_method", discriminatorType = DiscriminatorType.STRING, length = 20)
public abstract class TicketPayment extends BaseEntity {

    /** The 1-based registration order of the payment on its ticket. */
    @Column(name = "payment_index", nullable = false)
    public int paymentIndex;

    /** The amount applied to the ticket. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal amount;

    /**
     * Default constructor for JPA.
     */
    protected TicketPayment() {}

    /**
     * Creates a payment with its applied amount.
     *
     * @param amount the amount applied to the ticket
     */
    public TicketPayment(BigDecimal amount) {
        this.amount = amount;
    }

    /**
     * Returns the payment method key of this payment, resolved from the JPA
     * discriminator value of its concrete class (e.g. "CASH", "CARD"),
     * unwrapping any Hibernate proxy.
     *
     * @return the method key, or "UNKNOWN" when unresolvable
     */
    public String getMethodKey() {
        Class<?> entityClass = Hibernate.getClass(this);
        DiscriminatorValue discriminator = entityClass.getAnnotation(DiscriminatorValue.class);
        return discriminator != null ? discriminator.value() : "UNKNOWN";
    }

    /**
     * Returns the audit checksum of the payment.
     *
     * @return a hash of the index and amount
     */
    @Override
    public int getChecksum() {
        return Objects.hash(paymentIndex, amount);
    }

    // --- Nested factory interface ---

    /**
     * Factory discovered by CDI, creating the JPA entity of one payment method.
     */
    public interface Factory {
        /**
         * Returns the unique key identifying this payment method (e.g. "CARD").
         *
         * @return the method key
         */
        String getKey();

        /**
         * Creates the corresponding JPA entity.
         *
         * @param amount the amount applied to the ticket
         * @param tendered the tendered amount for cash payments, or null
         * @return the created payment entity
         */
        TicketPayment create(BigDecimal amount, BigDecimal tendered);
    }
}
