package com.intermarche.pos.domain.catalog;

import com.intermarche.pos.domain.BaseEntity;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * One EGAlim regime of the referential: a code, a label and the ceiling it
 * imposes on the promotional generosity of an article.
 *
 * <p>The law (2018, Descrozaille 2023, Travert n° 2025-337) caps the cumulated
 * generosity of a line by the CATEGORY of the article: food and petfood on one
 * side, droguerie-parfumerie-hygiène on the other, everything else uncapped.
 * The regime is that category; the ceiling is what the category is worth in
 * law today.
 *
 * <p>A table rather than an enum, for the reason the law itself has already
 * demonstrated: the DPH ceiling moved from 34 % to 40 % by the loi Travert. A
 * ceiling written in code is a redeployment; a ceiling in a row is a screen.
 * And a category the legislator adds tomorrow — or that another country
 * numbers differently — is then a line, not a release.
 *
 * <p>The CODE is the identity and the upsert key: it is what the article feed
 * states, what the valuation engine matches on, and what the conformity report
 * of a corrected ticket quotes. It carries no value in its name on purpose:
 * naming a regime after its rate — {@code FOOD_34} — freezes in the identifier
 * the one thing that is administered, and the day the rate moves the label
 * lies. {@code FOOD}, {@code DPH}, {@code EXEMPT}: the rate lives in the
 * column, once.
 *
 * <p>A NULL ceiling means uncapped, and it is the deliberate fail-open state:
 * an article whose regime is unknown is not capped, rather than capped by
 * default on a missing datum. Refusing to sell, or silently correcting a
 * ticket on the strength of an absent classification, would both be worse than
 * applying no ceiling at all and saying so in the report.
 *
 * <p>What this table is NOT: a history. A ceiling that changes legally is a
 * new value on the same code, and the tickets already sold are unaffected —
 * the engine's conformity report snapshots what it applied, line by line, at
 * the moment of the sale.
 */
@Entity
@Table(name = "egalim_regimes",
        uniqueConstraints = @UniqueConstraint(columnNames = "code"))
@Cacheable
public class EgalimRegime extends BaseEntity {

    /** The code the article feed states, the upsert key. */
    @Column(name = "code", nullable = false, unique = true, length = 30)
    @NotNull(message = "EGAlim regime code is mandatory")
    public String code;

    /** What a human reads: "Alimentaire et petfood", "Droguerie…". */
    @Column(name = "label", length = 80)
    public String label;

    /**
     * The ceiling as a fraction: {@code 0.3400} for thirty-four percent, or
     * NULL when the regime imposes none.
     */
    @Column(name = "cap_rate", precision = 5, scale = 4)
    @PositiveOrZero(message = "EGAlim cap must be positive")
    public BigDecimal capRate;

    /**
     * Default constructor for JPA.
     */
    public EgalimRegime() {
    }

    /**
     * Builds a regime.
     *
     * @param code the code the article feed states
     * @param label what a human reads, or null
     * @param capRate the ceiling as a fraction, or null for uncapped
     */
    public EgalimRegime(String code, String label, BigDecimal capRate) {
        this.code = code;
        this.label = label;
        this.capRate = capRate;
    }

    /**
     * Finds a regime by its code.
     *
     * @param code the code, possibly null
     * @return the regime, or null when no row carries that code
     */
    public static EgalimRegime findByCode(String code) {
        if (code == null) {
            return null;
        }
        return find("code", code).firstResult();
    }

    /**
     * Lists every regime in code order.
     *
     * @return the regimes, empty when the referential holds none
     */
    public static List<EgalimRegime> listAllOrdered() {
        return list("order by code");
    }

    /**
     * Returns what a screen prints in front of the ceiling.
     *
     * @return the label, or the code when the regime carries none
     */
    public String getLabel() {
        return label == null || label.isBlank() ? code : label;
    }

    /**
     * Tells whether this regime imposes a ceiling.
     *
     * @return true when a ceiling is set
     */
    public boolean isCapped() {
        return capRate != null;
    }

    /**
     * Returns the ceiling as a percentage, the way a screen states it.
     *
     * @return the percentage with its needless decimals dropped ("34"), or an
     *         empty string when the regime imposes none
     */
    public String getCapFormatted() {
        if (capRate == null) {
            return "";
        }
        BigDecimal percent = capRate.multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return percent.toPlainString().replace('.', ',');
    }

    /**
     * The fingerprint input of this regime for the referential export.
     *
     * @return a hash of the code, the label and the ceiling
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, label, capRate);
    }
}
