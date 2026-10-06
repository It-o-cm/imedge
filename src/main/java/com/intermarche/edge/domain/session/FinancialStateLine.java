package com.intermarche.edge.domain.session;

import com.intermarche.edge.domain.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One line of any section of a {@link FinancialState}.
 *
 * <p>A single table carries the seven sections of a financial state — settlement
 * means, VAT rates, hourly slots, nomenclature nodes, sectors, best sellers and
 * the per-cashier breakdown — because a single Z holds twenty nomenclature lines
 * and four VAT lines, and seven tables would carry the same schema seven times.
 * The {@link #section} discriminates, {@link #rank} orders the print, and the
 * nullable columns ({@link #quantity}, {@link #amountExcludingTax},
 * {@link #vatAmount}, {@link #share}) are served only by the sections that have a
 * meaning for them.
 */
@Entity
@Table(name = "financial_state_lines",
        indexes = {
                @Index(name = "idx_fin_state_line_state", columnList = "state_id"),
                @Index(name = "idx_fin_state_line_section", columnList = "state_id, section")
        }
)
public class FinancialStateLine extends BaseEntity {

    /** The section of a financial state a line belongs to. */
    public enum StateSection {
        /** One line per settlement mean (R11, R12, R20). */
        SETTLEMENT,
        /** One line per VAT rate. */
        VAT,
        /** One line per hourly slot (R17). */
        HOURLY,
        /** One line per nomenclature node of the restitution level (R16). */
        NOMENCLATURE,
        /** One line per sector, with its subtotal (R16). */
        SECTOR,
        /** One line per best-selling article (R19). */
        BEST_SELLER,
        /** One line per cashier on a store-scope state (R14). */
        CASHIER
    }

    /** The owning financial state. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "state_id", nullable = false)
    @NotNull
    public FinancialState state;

    /** The section this line belongs to. */
    @Column(name = "section", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @NotNull
    public StateSection section;

    /** The print order of the line within its section. */
    @Column(name = "line_rank", nullable = false)
    public int rank;

    /** The settlement key, the slot, the IFLS node, the EAN or the login, or null. */
    @Column(name = "code", length = 50)
    public String code;

    /** The printable label of the line, or null. */
    @Column(name = "label", length = 255)
    public String label;

    /** The quantity carried by the line (items, currency amount…), or null. */
    @Column(name = "quantity", precision = 19, scale = 4)
    public BigDecimal quantity;

    /** The amount of the line, tax included. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal amount;

    /** The tax-excluded amount, served by VAT and NOMENCLATURE lines, or null. */
    @Column(name = "amount_excluding_tax", precision = 19, scale = 4)
    public BigDecimal amountExcludingTax;

    /** The VAT amount, served by VAT and NOMENCLATURE lines, or null. */
    @Column(name = "vat_amount", precision = 19, scale = 4)
    public BigDecimal vatAmount;

    /** The share of the state's net turnover this line stands for (percent), or null. */
    @Column(name = "share", precision = 9, scale = 4)
    public BigDecimal share;

    /**
     * Builds an empty line; required by JPA.
     */
    public FinancialStateLine() {
    }

    /**
     * Builds a line of the given section with its order, code, label and amount.
     *
     * @param section the section the line belongs to
     * @param rank the print order within the section
     * @param code the line code, or null
     * @param label the line label, or null
     * @param amount the line amount, tax included
     */
    public FinancialStateLine(StateSection section, int rank, String code, String label, BigDecimal amount) {
        this.section = section;
        this.rank = rank;
        this.code = code;
        this.label = label;
        this.amount = amount;
    }

    /**
     * Returns the audit checksum of the line.
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(section, rank, code, amount);
    }
}
