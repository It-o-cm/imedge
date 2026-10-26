package com.intermarche.edge.domain.sale;

import com.intermarche.edge.domain.BaseEntity;
import com.intermarche.edge.domain.catalog.Product;
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

    /** LC-02-03-04: principal line owning an automatic companion. */
    @Column(name = "linked_parent_uid", length = 36)
    public String linkedParentUid;
    /** Configured companion captured when this line was registered. */
    @Column(name = "linked_article_ean", length = 38)
    public String linkedArticleEan;
    /** Last quantity successfully paired with its companion chain. */
    @Column(name = "linked_recorded_quantity", precision = 19, scale = 3)
    public BigDecimal linkedRecordedQuantity;
    /**
     * How many companions accompany one unit of this principal (BO-02-03-15),
     * captured with the companion EAN; null on lines recorded before it
     * existed, which read as one.
     */
    @Column(name = "linked_article_multiplier", precision = 19, scale = 3)
    public BigDecimal linkedArticleMultiplier;

    /**
     * The catalog product, or null for unknown-item and deposit-return lines.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    public Product product;

    /** The EAN code as entered at sale time, or null. */
    @Column(name = "ean", length = 38)
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

    /**
     * The NUMBER of the VAT regime the line was taxed under (BO-02-02-01), or
     * null on a line written before the number was kept or under no regime.
     *
     * <p>Kept beside the rate because two regimes may share a percentage: the
     * rate alone cannot tell a Portuguese exemption under article 9 from one
     * under article 53, and the documents of the sale must name the right one.
     */
    @Column(name = "vat_number")
    public Integer vatNumber;

    /** The price-modification label ("REMISE -10%"...), or null when untouched. */
    @Column(name = "modifier_label", length = 50)
    public String modifierLabel;

    /** Structured type of the applied manual gesture (REMISE, DISCOUNT, FORCE_PRICE), or null. */
    @Column(name = "modifier_type", length = 20)
    @Enumerated(EnumType.STRING)
    public com.intermarche.edge.ui.PriceModType modifierType;

    /**
     * Structured value of the gesture as typed, read by {@link #modifierType}, or
     * null: REMISE = a UNIT discount in euros (its effect on the line is this value
     * times the quantity), DISCOUNT = a percentage, FORCE_PRICE = the new UNIT
     * price. The reliable line total is always {@code unitPrice * quantity}
     * ({@link #totalPrice}), never derived from this value.
     */
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
     * On a price-embedded weighed line (EXP-26, LC-02-05-04), the WEIGHT deduced
     * from the read total and the referential price per kilo, in kilograms — so
     * it prints on the fiscal ticket, not only on the screen. Null on any other
     * line.
     */
    @Column(name = "embedded_weight", precision = 10, scale = 3)
    public BigDecimal embeddedWeight;

    /**
     * On a price-embedded weighed line (EXP-26), the referential price per kilo
     * the weight was deduced with. Null on any other line.
     */
    @Column(name = "embedded_unit_price", precision = 19, scale = 4)
    public BigDecimal embeddedUnitPrice;

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
     * Whether the line is EXEMPT of VAT (BO-02-03-26/27), a statement distinct
     * from a rate of zero: the ticket prints it under its own heading and the
     * ventilations keep it apart. Snapshotted at the sale like the rate, and
     * carried by the store push so the consolidated VAT reads it.
     */
    @Column(name = "vat_exempt")
    public boolean vatExempt;

    /**
     * Whether the article was sold on behalf of a THIRD PARTY, the "TVA vente
     * prestation" (BO-02-03-41 to -43): its VAT is the third party's, printed
     * with its own rate on the ticket, and left out of the store's VAT report
     * and of its turnover. Snapshotted at the sale and carried by the push.
     */
    @Column(name = "third_party_sale")
    public boolean thirdPartySale;

    /**
     * Whether the article sold is a SERVICE (prestation de services) and not
     * goods (BO-05-01-03): its collected amount belongs to the payment data of
     * the e-reporting. Snapshotted at the sale and carried by the push.
     */
    @Column(name = "service_line")
    public boolean service;

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
     * True when the article was rung by a SCAN rather than keyed
     * (BO-04-01-14, event « article scanné »). A line that is not scanned —
     * typed, searched, picked on a screen — is a keyed one: the store sales
     * state counts the scanned and the keyed articles apart (BO-06-04-05).
     */
    @Column(name = "scanned", nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    public boolean scanned;

    /**
     * True when the price of the line was KEYED by the operator — an article
     * whose price is to be entered, an unknown article or a family sale
     * (BO-04-01-14, event « prix saisi »).
     */
    @Column(name = "price_entered", nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    public boolean priceEntered;

    /**
     * True when the article is a bulky one the customer carries out without
     * putting it on the belt (BO-04-01-14, event « article emporté »).
     */
    @Column(name = "carried_out", nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    public boolean carriedOut;

    /**
     * True when a SOUS-TOTAL is drawn AFTER this line (EXP-43). Persisted because
     * the subtotal is part of the fiscal document: the printed ticket, its
     * duplicata and the dematerialized ticket all render it from the CLOSED ticket,
     * and each shopper of a shared basket reads their part on the paper.
     */
    @Column(name = "subtotal_boundary", nullable = false)
    public boolean subtotalBoundary;

    /**
     * True when this line is a RETURN carried inside a mixed ticket (EXP-38): a
     * negative contribution the customer is refunded for. Persisted so a mixed
     * ticket survives a draft recovery and prints its return lines as such.
     */
    @Column(name = "return_line", nullable = false)
    public boolean returnLine;

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
     * The quantity ORDERED on the document the line was recalled from, when the
     * document also stated the delivered quantity the line sells (LC-06-02-02,
     * -12); null for any other line.
     */
    @Column(name = "ordered_quantity", precision = 12, scale = 3)
    public BigDecimal orderedQuantity;

    /**
     * The article's unit of measure ({@code LC-02-03-02}), or null for an article sold
     * by the piece. Snapshotted like the label and the price: the receipt states the
     * unit the article was sold in, whatever the referential says later.
     */
    @Column(name = "unit_name", length = 20)
    public String unitName;

    /**
     * Whether the article was of the DECIMAL-QUANTITY type ({@code LC-02-13-02}),
     * sold by the metre, the square metre or the litre. Snapshotted and read back
     * on recovery for the same reason as {@link #discountForbidden}: a draft
     * recovered after a restart must still let the quantity key accept the
     * decimals that article is sold in, instead of rounding it to whole units.
     */
    @Column(name = "decimal_quantity")
    public boolean decimalQuantity;

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
     * The article's SECOND CODE — its internal code — as the referential
     * carried it the day the line was rung (BO-10-02-20).
     *
     * <p>Frozen here and not read back from the article at print time: a
     * duplicata months later must state the code the customer was handed, not
     * the code the article carries today. Null for an article that has none.
     */
    @Column(name = "second_code", length = 50)
    public String secondCode;

    /**
     * The tare applied to this weighing, in kilograms (BO-10-05-04).
     *
     * <p>Frozen on the sold line like the family and the second code: a
     * duplicata must restate the deduction the customer was shown. Null for a
     * line that was not weighed.
     */
    @Column(name = "applied_tare_kg", precision = 10, scale = 3)
    public java.math.BigDecimal appliedTareKg;

    /**
     * The activation code of a dematerialised article (BO-03-06-44), or null on
     * every ordinary line.
     *
     * <p>Kept on the SOLD line, like the GS1 identifiers above: a customer who
     * comes back three weeks later with a gift card that never worked is
     * answered by the line that carried its code, and by nothing else.
     */
    @Column(name = "activation_code", length = 100)
    public String activationCode;

    /**
     * The provider that owes the activation of the line (BO-03-06-44), as the
     * referential named it when the article was rung, or null.
     */
    @Column(name = "activation_provider", length = 60)
    public String activationProvider;

    /**
     * The payment hub's identifier of the activation of the line (LC-02-09-01),
     * or null on a line the hub never activated.
     */
    @Column(name = "activation_reference", length = 64)
    public String activationReference;

    /** The provider's own code for the activation (LC-02-09-03), or null. */
    @Column(name = "activation_approval", length = 64)
    public String activationApproval;

    /** What the article carried before its activation (LC-02-09-03), or null. */
    @Column(name = "activation_balance_before", precision = 10, scale = 2)
    public java.math.BigDecimal activationBalanceBefore;

    /** What the article carries after its activation (LC-02-09-03), or null. */
    @Column(name = "activation_balance_after", precision = 10, scale = 2)
    public java.math.BigDecimal activationBalanceAfter;

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
     * Whether this line was the LAST of the ticket when it was cancelled.
     *
     * <p>The distinction is the whole point of the fraud indicator. A cashier may
     * cancel the last line alone — a mis-scan corrected on the spot. Cancelling any
     * other line needs a supervisor's endorsement, and it is that cancellation the
     * financial state counts apart: a line pulled out of the middle of a ticket is
     * the gesture a fraud hides behind.
     *
     * <p>False on a line that was never cancelled, where it means nothing: read it
     * only together with {@link #cancelled}.
     */
    @Column(name = "cancelled_last", nullable = false)
    public boolean cancelledLast;

    /**
     * Code of the administered justification the cashier gave for the
     * cancellation ({@code BO-06-06-04}), or null when the shop administers no
     * justification for that gesture.
     *
     * <p>The CODE and the label are stored side by side, and both are frozen:
     * a state edited months later must show the wording the cashier was
     * offered, not the one the referential carries today — the rule the
     * family label and the second code already follow on this line.
     */
    @Column(name = "cancellation_reason_code", length = 20)
    public String cancellationReasonCode;

    /**
     * Label of the administered justification the cashier gave for the
     * cancellation ({@code BO-06-06-04}), or null when none was given.
     */
    @Column(name = "cancellation_reason_label", length = 80)
    public String cancellationReasonLabel;

    /**
     * Code of the administered RETURN TYPE a return line taken back inside the
     * sale was rung under ({@code BO-03-13-12}), or null for a sale line or a
     * return made under no type. Frozen with its label, like the cancellation
     * reason: a report edited later shows the type the cashier chose.
     */
    @Column(name = "return_type_code", length = 40)
    public String returnTypeCode;

    /**
     * Label of the return type of a return line ({@code BO-03-13-12}), or null.
     */
    @Column(name = "return_type_label", length = 80)
    public String returnTypeLabel;

    /**
     * Returns the unit price the customer ACTUALLY PAID on this line, tax
     * included (LC-05-02-12): the line total shared out over the quantity, so
     * the advantages the line carries are part of it — a valuation-engine
     * promotion and the share of a global ticket discount both land on
     * {@link #totalPrice} and never on {@link #unitPrice}.
     * <p>
     * This is the price a RETURN hands back. Refunding {@link #unitPrice} would
     * give the customer more than they paid on any line that carried an
     * advantage, and the difference would come out of the store's till.
     * <p>
     * Scale 4, like every stored unit price, so a division that does not
     * terminate has a defined result and a return of the WHOLE quantity still
     * adds up to the line total at the centime.
     * <p>
     * Falls back to {@link #unitPrice} when the line total is absent (rows
     * written before it was stored) or when the quantity is absent or zero:
     * there is then nothing to share out, and the unit price is the only price
     * the line knows.
     *
     * @return the unit price actually paid including tax, or the plain unit
     *         price when the line total cannot be shared out
     */
    public BigDecimal getEffectiveUnitPrice() {
        if (this.totalPrice == null || this.quantity == null
                || this.quantity.signum() == 0) {
            return this.unitPrice;
        }
        return this.totalPrice.divide(this.quantity, 4, RoundingMode.HALF_UP);
    }

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
            return "x" + this.quantity.stripTrailingZeros().toPlainString().replace('.', ',');
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
