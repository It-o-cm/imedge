package com.intermarche.pos.domain.barcode;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * One weighed line of a {@link BalanceTicket} — one service at one counter.
 *
 * <p>THE PRICE HERE IS THE TRUTH. It was computed on the scale, in front of
 * the customer, and printed on the paper they hold. The register must state
 * that amount and not re-price the EAN from its own catalog, which is why the
 * integrated line is flagged price-imposed exactly like a 2x price-embedded
 * sticker.
 *
 * <p>The EAN is carried all the same: a line without an EAN does not exist on
 * this register, and the valuation engine — which prices the whole ticket —
 * must see this one too.
 */
@Entity
@Table(name = "balance_ticket_lines")
public class BalanceTicketLine extends PanacheEntity {

    /** The ticket this line belongs to. */
    @ManyToOne
    @JoinColumn(name = "balance_ticket_id")
    public BalanceTicket balanceTicket;

    /** The article's EAN. */
    @Column(name = "ean", length = 20, nullable = false)
    public String ean;

    /** The label as the counter printed it. */
    @Column(name = "label", length = 80, nullable = false)
    public String label;

    /** The weighed quantity — kilograms, three decimals. */
    @Column(name = "quantity", precision = 10, scale = 3, nullable = false)
    public BigDecimal quantity;

    /** The line total including tax, as the scale computed it. */
    @Column(name = "total_including_tax", precision = 10, scale = 2, nullable = false)
    public BigDecimal totalIncludingTax;

    /** The VAT rate applied by the counter, as a fraction (0.0550 for 5.5 %). */
    @Column(name = "vat_rate", precision = 6, scale = 4)
    public BigDecimal vatRate;
}
