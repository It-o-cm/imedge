package com.intermarche.edge.domain.sale;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * One loyalty advantage a return takes back, line by line (LC-05-07-02): the
 * share of the cagnotte, of a purchase voucher or of a coupon the sale earned and
 * the basket the customer keeps no longer earns.
 *
 * <p>The amount is the loyalty programme's: what the sale earned under the rule,
 * frozen on the ticket, minus what the programme says the kept basket earns under
 * it, minus what earlier returns of the same ticket already took back. The
 * register computes no earn of its own.
 */
@Embeddable
public class RefundFidelityLine {

    /** The stable code of the crediting rule, or null. */
    @Column(name = "rule_code", length = 50)
    public String ruleCode;

    /** The printable label of the rule, as the sale displayed it. */
    @Column(name = "label", length = 120)
    public String label;

    /** The advantage type label (cagnotte, bon d'achat, coupon), or null. */
    @Column(name = "advantage_type_label", length = 120)
    public String advantageTypeLabel;

    /** The amount taken back. */
    @Column(name = "amount", precision = 19, scale = 4)
    public BigDecimal amount;

    /**
     * Default constructor for JPA.
     */
    public RefundFidelityLine() {
    }

    /**
     * Freezes one advantage taken back.
     *
     * @param ruleCode the rule code, or null
     * @param label the rule label
     * @param advantageTypeLabel the advantage type label, or null
     * @param amount the amount taken back
     */
    public RefundFidelityLine(String ruleCode, String label, String advantageTypeLabel, BigDecimal amount) {
        this.ruleCode = ruleCode;
        this.label = label;
        this.advantageTypeLabel = advantageTypeLabel;
        this.amount = amount;
    }
}
