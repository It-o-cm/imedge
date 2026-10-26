package com.intermarche.edge.domain.catalog;

import com.intermarche.edge.domain.BaseEntity;
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
 * One VAT regime of the referential: a number, a rate and a label
 * (BO-02-03-14, BO-03-03-04), the country it answers to, the legal mention it
 * obliges and whether it is an exemption (BO-02-02-01, BO-02-02-02).
 *
 * <p>SEVERAL REGIMES MAY SHARE A PERCENTAGE (BO-02-02-01): the number is the
 * identity, never the rate. Two Portuguese exemptions at 0 % with two different
 * legal grounds are two rows, and a sold line keeps the NUMBER of its regime
 * ({@link #numberFor}) so its documents name the right one ({@link #resolve}).
 *
 * <p>A table rather than a decimal on every price, because a regime is NAMED
 * before it is a number of percent. The documents the register emits are asked
 * for the VAT NUMBER — the ticket's VAT table and the invoice both state it —
 * and a rate copied onto a hundred thousand price rows cannot be numbered,
 * cannot be relabelled, and cannot be corrected anywhere but in the feed that
 * wrote it.
 *
 * <p>The number is the identity and the upsert key: the commercial management
 * numbers a regime once and every price points at that number. The rate is a
 * FRACTION — {@code 0.0550} for five and a half percent — which is the form
 * every computation in the suite already expects.
 *
 * <p>What this table is NOT: a history. A rate that changes legally is a new
 * value on the same number, and the sold lines are unaffected because a ticket
 * line snapshots the rate at scan time. A ticket reprinted two years later
 * therefore states the rate of its day, not the rate of today.
 */
@Entity
@Table(name = "vat_rates",
        uniqueConstraints = @UniqueConstraint(columnNames = "vat_number"))
@Cacheable
public class VatRate extends BaseEntity {

    /** The number the commercial management knows this regime by, the upsert key. */
    @Column(name = "vat_number", nullable = false, unique = true)
    @NotNull(message = "VAT number is mandatory")
    public Integer number;

    /** The rate as a fraction: 0.2000 for twenty percent. */
    @Column(name = "rate", nullable = false, precision = 5, scale = 4)
    @NotNull(message = "VAT rate is mandatory")
    @PositiveOrZero(message = "VAT rate must be positive")
    public BigDecimal rate;

    /** What a human reads: "Taux normal", "Taux réduit"… */
    @Column(name = "label", length = 60)
    public String label;

    /**
     * The ISO country this regime belongs to, or null when it belongs to all
     * of them ({@code BO-02-02-02}).
     *
     * <p>A rate is a national decision: five and a half percent means one
     * thing in France and another in Portugal, and a group running both
     * cannot keep one table unless the row says which country it answers.
     */
    @Column(name = "country", length = 3)
    public String country;

    /**
     * The LEGAL MENTION this regime obliges the document to carry
     * ({@code BO-02-02-02}), or null when it obliges none.
     *
     * <p>Printed under the VAT ventilation of the documents that state a tax —
     * the sale receipt, the invoice, the credit note and the refund receipt —
     * once per distinct mention, and never twice because two rates share it.
     * Administered rather than written in the code: the sentence is imposed
     * by a tax administration and changes by decree, not by release.
     */
    @Column(name = "legal_mention", length = 160)
    public String legalMention;

    /**
     * Whether this regime EXEMPTS what it taxes ({@code BO-02-02-01},
     * {@code BO-02-03-26/27}): an exemption is a legal status, not a rate of
     * zero. A French zero-rated export and a donation collected at the till, or
     * a Portuguese article exempted under article 9, all carry no tax, but only
     * the exemption is printed under its own EXO heading, apart from the
     * 0&nbsp;% rows, and justified by its {@link #exemptionCode} and its
     * {@link #legalMention}. An exempt regime carries a rate of zero.
     */
    @Column(name = "exempt", nullable = false)
    public boolean exempt;

    /**
     * The code of the exemption as the tax administration names it
     * ({@code BO-02-02-01}) — the Portuguese {@code M01} to {@code M99} the
     * SAF-T file states — or null when the regime exempts nothing or the
     * country names no code.
     */
    @Column(name = "exemption_code", length = 10)
    public String exemptionCode;

    /**
     * Default constructor for JPA.
     */
    public VatRate() {
    }

    /**
     * Creates a regime with its three values.
     *
     * @param number the number, the upsert key
     * @param rate the rate as a fraction
     * @param label what a human reads, or null
     */
    public VatRate(Integer number, BigDecimal rate, String label) {
        this.number = number;
        this.rate = rate;
        this.label = label;
    }

    /**
     * Finds a regime by its number.
     *
     * @param number the number, possibly null
     * @return the regime, or null when no row carries that number
     */
    public static VatRate findByNumber(Integer number) {
        if (number == null) {
            return null;
        }
        return find("number", number).firstResult();
    }

    /**
     * Finds a regime by its rate.
     *
     * <p>The way a feed that states rates rather than numbers — every
     * historical price file, and the engine feed which computes with the rate
     * — is attached to the referential. Two regimes sharing a rate is a
     * referential anomaly, not a case to arbitrate: the first row wins.
     *
     * @param rate the rate as a fraction, possibly null
     * @return the regime, or null when no row carries that rate
     */
    public static VatRate findByRate(BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        return find("rate", rate).firstResult();
    }

    /**
     * Resolves the regime a sold line or a ventilation bucket was taxed under
     * ({@code BO-02-02-01}): by its NUMBER when it carries one, by its rate
     * otherwise.
     *
     * <p>The number is what keeps two regimes of the same percentage apart — a
     * Portuguese 0 % exempted under article 9 and another under article 53 are
     * two regimes, two codes and two mentions, and a rate alone names whichever
     * the table returns first. A number whose regime no longer carries the rate
     * of the line (the regime was re-rated since the sale) is not trusted, and
     * the rate decides, as it did before the number was kept.
     *
     * @param rate the rate the line or the bucket carries, possibly null
     * @param number the regime number it carries, possibly null
     * @return the regime, or null when neither identifies one
     */
    public static VatRate resolve(BigDecimal rate, Integer number) {
        VatRate numbered = findByNumber(number);
        if (numbered != null && (rate == null || numbered.rate == null
                || numbered.rate.compareTo(rate) == 0)) {
            return numbered;
        }
        return findByRate(rate);
    }

    /**
     * The NUMBER of the regime an article sold at a rate falls under
     * ({@code BO-02-02-01}): the regime the article itself names
     * ({@code VAT_NUMBER}, BO-02-03-27) when that regime carries the rate, the
     * regime its current price names when that one does, the first regime of
     * that rate otherwise — the precedence {@link ArticleVat} rates the line by.
     *
     * <p>Read when the line is written, so the line keeps the regime of its day
     * the way it keeps its rate: a regime re-rated or renumbered later does not
     * rewrite a sale already made.
     *
     * @param ean the article's EAN, possibly null (a line keyed in by amount)
     * @param rate the rate the line snapshotted, possibly null
     * @return the regime number, or null when no regime carries the rate
     */
    public static Integer numberFor(String ean, BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        Product product = ean == null ? null : Product.findByEan(ean);
        VatRate own = product == null ? null
                : findByNumber(com.intermarche.edge.domain.catalog.attribute.ProductAttributes.vatNumber(product));
        if (carries(own, rate)) {
            return own.number;
        }
        Price price = product == null ? null : Price.findCurrentPrice(product.id);
        VatRate priced = price == null ? null : price.vat;
        if (carries(priced, rate)) {
            return priced.number;
        }
        VatRate regime = findByRate(rate);
        return regime == null ? null : regime.number;
    }

    /**
     * Whether a regime exists and carries the given rate.
     *
     * @param regime the regime, possibly null
     * @param rate the rate, never null
     * @return true when the regime carries exactly that rate
     */
    private static boolean carries(VatRate regime, BigDecimal rate) {
        return regime != null && regime.rate != null && regime.rate.compareTo(rate) == 0;
    }

    /**
     * The alphabetic CODE of the regime carrying a rate (LC-02-10-03,
     * BO-03-03-04, BO-03-03-37).
     *
     * <p>One rule for every document: the sale ticket, the invoice's VAT table
     * and the deposit recap all name a regime this way, which is what makes the
     * tables of two papers stating the same sale agree.
     *
     * @param rate the rate a line or a bucket carries, possibly null
     * @return the code, or null when no regime with a usable number carries the
     *         rate
     */
    public static String codeOf(BigDecimal rate) {
        VatRate regime = findByRate(rate);
        return regime == null ? null : regime.code();
    }

    /**
     * This regime's alphabetic CODE: its number written in letters, 0 being A,
     * 25 being Z and 26 being AA (LC-02-10-03, BO-03-03-04).
     *
     * @return the code, or null when the regime carries no usable number
     */
    public String code() {
        if (number == null || number < 0) {
            return null;
        }
        long remaining = number.longValue() + 1;
        StringBuilder code = new StringBuilder();
        do {
            remaining--;
            code.append((char) ('A' + remaining % 26));
            remaining /= 26;
        } while (remaining > 0);
        return code.reverse().toString();
    }

    /**
     * Lists every regime in number order.
     *
     * @return the regimes, empty when the referential holds none
     */
    public static List<VatRate> listAllOrdered() {
        return list("order by number");
    }

    /**
     * Returns what a document prints in front of the rate.
     *
     * @return the label, or the number when the regime carries none
     */
    public String getLabel() {
        return label == null || label.isBlank() ? String.valueOf(number) : label;
    }

    /**
     * Returns the rate as a percentage, the way a document states it.
     *
     * @return the percentage with its needless decimals dropped ("5,5"), or an
     *         empty string when the regime carries no rate
     */
    public String getRateFormatted() {
        if (rate == null) {
            return "";
        }
        BigDecimal percent = rate.multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return percent.toPlainString().replace('.', ',');
    }

    /**
     * The fingerprint input of this regime, over every business field.
     *
     * @return a hash of the number, the rate, the label, the country, the
     *         legal mention and the exemption
     */
    @Override
    public int getChecksum() {
        return checksumOf(number, rate, label, country, legalMention, exempt, exemptionCode);
    }

    /**
     * The ONE formula every VAT checksum is computed by — the entity's own,
     * and the importer's for the lines the commercial feed carries. The two
     * must never diverge: the import skips a line whose checksum equals the
     * row's stored one, so an importer hashing other fields than the entity
     * would rewrite every line of every file, and one hashing fewer would
     * never see the fields the feed does not carry change.
     *
     * @param number the regime number, the upsert key
     * @param rate the rate as a fraction of one
     * @param label what a human reads, or null
     * @param country the ISO country this regime answers, or null for all
     * @param legalMention the mention the documents quote, or null
     * @param exempt whether the regime is an exemption
     * @param exemptionCode the administration's code of the exemption, or null
     * @return the hash of the seven business fields
     */
    public static int checksumOf(Integer number, BigDecimal rate, String label,
                                 String country, String legalMention, boolean exempt,
                                 String exemptionCode) {
        return Objects.hash(number, rate, label, country, legalMention, exempt, exemptionCode);
    }
}
