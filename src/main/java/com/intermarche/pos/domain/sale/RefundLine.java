package com.intermarche.pos.domain.sale;

import com.intermarche.pos.domain.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * A refunded line, snapshot of the original ticket line at refund time.
 * <p>
 * Phase 3 lot 4: the VAT rate of the original line is carried so the refund
 * restitutes the VAT it cancels, and {@link #originalLineId} is the anchor of
 * the double-refund guard (the refunded quantities of one original line are
 * summed across refunds and capped at the sold quantity).
 * <p>
 * {@link #originalLineId} is a register-local database id: it never travels.
 * The store synchronization references the original line by its stable
 * {@code lineUid} and the ingestion resolves it back to the store-local id —
 * the reason ticket lines must arrive before the refunds that cite them
 * (drain order). {@link #vatRate} feeds the per-rate ventilation printed on
 * the refund ticket.
 */
@Entity
@Table(name = "refund_lines",
        indexes = @Index(name = "idx_refund_line_original", columnList = "original_line_id")
)
public class RefundLine extends BaseEntity {

    /** The database id of the refunded original ticket line. */
    @Column(name = "original_line_id", nullable = false)
    public Long originalLineId;

    /** The label of the refunded product. */
    @Column(name = "product_label", nullable = false)
    public String productLabel;

    /** The refunded quantity. */
    @Column(name = "quantity", nullable = false, precision = 10, scale = 3)
    public BigDecimal quantity;

    /** The unit price refunded (original unit price including tax). */
    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    public BigDecimal price;

    /** The VAT rate of the original line (null on pre-lot-4 rows). */
    @Column(name = "vat_rate", precision = 5, scale = 4)
    public BigDecimal vatRate;

    /**
     * Returns the audit checksum of the line (was previously a constant 0,
     * defeating the change detection).
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(originalLineId, quantity, price, vatRate);
    }
}
