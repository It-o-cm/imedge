package com.intermarche.pos.domain.sale;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One loyalty advantage line AS THE SALE DISPLAYED IT, frozen on the ticket at
 * closing (BO-03-03-25).
 *
 * <p>The rule label is the only rule datum the loyalty service lets the register
 * print, and it is copied here rather than looked up again: a duplicata issued
 * two years later must state what the customer was shown on the day, not what
 * the rule says today. The rule code travels beside it so a line can still be
 * traced back to the rule that produced it, and nothing else of the rule is
 * kept — the register is not a second engine.
 *
 * <p>The amount is the earn AFTER the caps the loyalty service applied: the
 * register never recomputes it, and a figure it cannot vouch for is a figure it
 * does not print.
 */
@Embeddable
public class TicketFidelityLine {

    /** The stable code of the crediting rule, as the loyalty service names it. */
    @Column(name = "rule_code", length = 50)
    public String ruleCode;

    /** The printable label of the rule, as the loyalty service worded it. */
    @Column(name = "label", length = 120)
    public String label;

    /** The earn amount of this line, after caps. */
    @Column(name = "amount", precision = 19, scale = 4)
    public BigDecimal amount;

    /**
     * Default constructor for JPA.
     */
    public TicketFidelityLine() {
    }

    /**
     * Freezes one displayed advantage line.
     *
     * @param ruleCode the rule code, or null
     * @param label the printable label, or null
     * @param amount the earn amount after caps, or null
     */
    public TicketFidelityLine(String ruleCode, String label, BigDecimal amount) {
        this.ruleCode = ruleCode;
        this.label = label;
        this.amount = amount;
    }

    /**
     * Returns the label a document prints, never null.
     *
     * @return the label, or an empty string when the line carries none
     */
    public String getLabel() {
        return label == null ? "" : label;
    }

    /**
     * Returns the amount formatted for a document (2 decimals, French comma).
     *
     * @return the formatted amount, "0,00" when the line carries none
     */
    public String getAmountFormatted() {
        if (amount == null) {
            return "0,00";
        }
        return String.format("%.2f", amount.setScale(2, RoundingMode.HALF_UP)).replace('.', ',');
    }
}
