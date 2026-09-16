package com.intermarche.pos.domain.sale;

import com.intermarche.pos.domain.BaseEntity;
import com.intermarche.pos.domain.catalog.Product;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A persisted ticket line.
 * <p>
 * Phase 0 lot 2: each line carries the stable {@code lineUid} of its in-memory
 * counterpart (the future contractual lineId toward the valuation engine) and
 * snapshots of the EAN / PLU as they were entered, so a draft can be restored
 * faithfully after a register restart. The product reference becomes nullable:
 * unknown-item and deposit-return lines have no catalog product.
 * <p>
 * The {@code lineUid} is a single identity with three lives: in-memory cart
 * uid (TicketItem.uid), store-sync key (refund lines reference their original
 * line by uid, since database ids are register-local), and the contractual
 * lineId of the phase 7 valuation exchanges. The draft sync reconciles lines
 * by uid (update in place, orphan removal for the gone ones).
 * <p>
 * Merge policy at cart level (phase 3): only unmodified unit EAN lines at the
 * same price merge; weighed lines (one weighing = one line), price-embedded
 * scale stickers (no code carried), deposit returns and negative lines never
 * do. {@link #modifierLabel} and {@link #originalUnitPrice} persist an
 * endorsed price modification so it survives a restart without being asked
 * again (debt sweep).
 */
@Entity
@Table(name = "ticket_lines")
public class TicketLine extends BaseEntity {

    /** The 1-based position of the line on the ticket. */
    @Column(name = "line_number", nullable = false)
    public int lineNumber;

    /**
     * The stable unique identifier of the line, shared with the in-memory cart
     * (TicketItem.uid) and reused as the contractual lineId in phase 7.
     */
    @Column(name = "line_uid", length = 36)
    public String lineUid;

    /**
     * The catalog product, or null for unknown-item and deposit-return lines.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    public Product product;

    /** The EAN code as entered at sale time, or null. */
    @Column(name = "ean", length = 13)
    public String ean;

    /** The PLU code as entered at sale time (weighed sale), or null. */
    @Column(name = "plu", length = 10)
    public String plu;

    /** The label of the line as shown on the ticket. */
    @Column(name = "product_label", nullable = false)
    @NotNull
    public String productLabel;

    /** The quantity (units, or kg for weighed lines). */
    @Column(name = "quantity", nullable = false, precision = 10, scale = 3)
    @NotNull
    public BigDecimal quantity;

    /** The unit price including tax. */
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal unitPrice;

    /** The VAT rate captured at sale time (e.g. 0.2000 for 20%). */
    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 4)
    @NotNull
    public BigDecimal vatRate;

    /** The price-modification label ("REMISE -10%"...), or null when untouched. */
    @Column(name = "modifier_label", length = 50)
    public String modifierLabel;

    /** Structured type of the applied manual gesture (REMISE, DISCOUNT, FORCE_PRICE), or null. */
    @Column(name = "modifier_type", length = 20)
    @Enumerated(EnumType.STRING)
    public com.intermarche.pos.ui.PriceModType modifierType;

    /** Structured value of the gesture as typed (line euros, percent, or new line total), or null. */
    @Column(name = "modifier_value", precision = 19, scale = 4)
    public java.math.BigDecimal modifierValue;

    /** The catalog unit price before modification, or null when untouched. */
    @Column(name = "original_unit_price", precision = 19, scale = 4)
    public BigDecimal originalUnitPrice;

    /** The line total including tax. */
    @Column(name = "total_price", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalPrice;

    /** True for deposit-return lines. */
    @Column(name = "is_deposit")
    public boolean deposit;

    /**
     * Money instrument sold on this line (gift card): the line carries VALUE,
     * not goods. Persisted rather than re-derived from the catalog at
     * recovery: the DRAFT is the durable truth of what was sold, and a
     * catalog changed mid-sale must not flip the flag of a line already rung
     * up. It drives the refund refusal and the exclusions from discounts,
     * gestures and valuation.
     */
    @Column(name = "money_product")
    public boolean moneyProduct;

    /**
     * True when the line was rung from a price-embedded balance sticker
     * (EAN 2x, prefixes 21-22). Persisted rather than re-derived: the sticker
     * price exists only on the paper, and the flag decides whether the
     * valuation request carries the surcharge trio at recovery time.
     */
    @Column(name = "price_embedded")
    public boolean priceEmbedded;

    /**
     * Whether the article forbids any price reduction (BO-02-03-09).
     * <p>
     * Persisted on the line, and read back on recovery, for the same reason as
     * {@link #moneyProduct}: the draft is the durable truth of what was rung
     * up. Deriving it again from the catalog at recovery would let a ban
     * disappear from a cart the moment the register restarts. Working flag of
     * the draft, deliberately NOT carried by the store push: once the ticket is
     * closed no reduction can be applied to it any more, so the consolidated
     * node has nothing to do with it.
     */
    @Column(name = "discount_forbidden")
    public boolean discountForbidden;

    /**
     * Code of the product's nomenclature (family) as it was at sale time
     * (campaign lot C3, BO-04-01-11). Snapshotted from the product when the
     * persistent line is created, exactly as the label, EAN, PLU and price
     * already are: the referential can be re-parented after the sale, but a
     * consolidated line must keep the family the article was sold under. Null
     * for unknown-item and deposit-return lines (no catalog product) and for a
     * product attached to no family. Register-local at creation, then carried
     * by the transport so the store journal can search by family.
     */
    @Column(name = "family_code", length = 50)
    public String familyCode;

    /**
     * Human-readable label of the product's nomenclature (family) as it was at
     * sale time (campaign lot C3, BO-04-01-11), snapshotted alongside
     * {@link #familyCode}. Null when the line carries no family.
     */
    @Column(name = "family_label", length = 255)
    public String familyLabel;

    /**
     * True when the article on this line is collected at the goods desk after the
     * sale rather than carried out by the customer ({@code LC-02-08}).
     * <p>
     * Persisted because the collection voucher is printed from the CLOSED ticket:
     * the desk hands goods over against a paid sale, and a flag that lived only in
     * the register's memory would leave a reprinted voucher unable to say which
     * lines it covers.
     */
    @Column(name = "to_collect", nullable = false)
    public boolean toCollect;

    /**
     * The restricted-tender eligibilities the article carried when it was rung
     * ({@code LC-09-01-11} to {@code -18}), comma-joined, or null when it carried none.
     * <p>
     * Persisted so a draft recovered after a restart still knows what the basket may
     * be paid with: the running totals are shown "en permanence jusqu'au paiement",
     * and a recovery that lost them would show zero on a basket full of eligible
     * articles.
     */
    @Column(name = "restricted_tenders", length = 200)
    public String restrictedTenders;

    /**
     * The article's unit of measure ({@code LC-02-03-02}), or null for an article sold
     * by the piece. Snapshotted like the label and the price: the receipt states the
     * unit the article was sold in, whatever the referential says later.
     */
    @Column(name = "unit_name", length = 20)
    public String unitName;

    /**
     * Every identifier the GS1 code that rang this line carried, as the technical
     * journal writes them ({@code LC-11-03-02}), or null on a line rung any other way.
     * <p>
     * THIS IS THE TRANSACTIONAL HALF of that requirement, the technical journal being
     * the other. Recording the identifiers only in the journal would make them
     * searchable and not attributable: an investigation on a batch recall needs to know
     * which LINE carried the lot number, and a journal entry beside a ticket number is
     * not that. The identifiers this version has no rule for are kept exactly like the
     * others, which is what the requirement asks in so many words.
     */
    @Column(name = "gs1_data", length = 500)
    public String gs1Data;

    /**
     * The expiry date the GS1 code carried ({@code LC-11-03-12}), or null on a line
     * that carried none. A column of its own because it is the one decoded identifier
     * a rule reads.
     */
    @Column(name = "gs1_expiry_date")
    public java.time.LocalDate gs1ExpiryDate;

    /**
     * True when the line was cancelled by the cashier during the sale and KEPT
     * as a witness rather than dropped (campaign lot C4, BO-04-01-16). The
     * register historically modelled the cart as a state — a cancelled line
     * simply vanished (orphan removal in the draft reconciliation); this lot
     * turns the cancellation into a conserved event so the store journal can
     * look for tickets bearing an article cancellation. A cancelled line is
     * EXCLUDED from everything that counts what was sold — the ticket totals,
     * the printed receipt, the customer screen, the day dashboard, the returns
     * screen, the recovered cart — and INCLUDED only in the consolidated
     * history the journal reads. Its totals never move a centime (the totals
     * are recomputed from the live cart, which no longer holds it).
     */
    @Column(name = "cancelled", nullable = false)
    public boolean cancelled;

    /**
     * Timestamp of the article cancellation (campaign lot C4, BO-04-01-16), or
     * null when the line was never cancelled. Set once, at the moment the
     * cashier cancels the line.
     */
    @Column(name = "cancellation_date")
    public java.time.LocalDateTime cancellationDate;

    /**
     * Badge identifier of the operator who cancelled the line (campaign lot C4,
     * BO-04-01-16, the "auteur" of the cancellation), or null when the line was
     * never cancelled. Like {@link #cancelled} and {@link #cancellationDate},
     * this DESCRIBES THE SALE EVENT — what happened on this ticket — not the
     * register's local authentication state (unlike {@code failedAttempts} /
     * {@code lockedUntil} / {@code bo_password}), so the three fields ARE
     * carried by the store synchronization (see {@code SyncPayloads.LineDto}
     * and the outbox transport), never a hash or a password.
     */
    @Column(name = "cancelled_by", length = 20)
    public String cancelledBy;

    /**
     * Returns the line total formatted for display (2 decimals, French comma).
     *
     * @return the formatted line total
     */
    public String getTotalFormatted() {
        if (this.totalPrice == null) return "0,00";
        BigDecimal rounded = this.totalPrice.setScale(2, RoundingMode.HALF_UP);
        return String.format("%.2f", rounded).replace(".", ",");
    }

    /**
     * Returns the quantity formatted for display: kilograms for weighed lines,
     * a unit count otherwise.
     *
     * @return the formatted quantity
     */
    public String getFormattedQuantity() {
        if (this.quantity == null) return "";

        boolean isWeight = (this.product != null && this.product.plu != null && !this.product.plu.isEmpty());

        if (isWeight) {
            return String.format("%.3f kg", this.quantity).replace(".", ",");
        } else {
            if (this.quantity.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
                return String.format("x%.0f", this.quantity);
            }
            return "x" + this.quantity.stripTrailingZeros().toPlainString();
        }
    }

    /**
     * Returns the audit checksum of the line, tolerant to the absence of a
     * catalog product (unknown items, deposit returns).
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(lineNumber, product != null ? product.id : null, quantity, totalPrice);
    }
}
