package com.intermarche.edge.domain.barcode;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.List;

/**
 * An administered range of ARTICLE barcodes — the in-store codes a scale or a
 * service counter prints, where the article is named by a segment of the code
 * and the rest carries a price or a weight (BO-03-06-02/03/04/05/10).
 *
 * <p>Same doctrine as {@link CouponType}: the back office states the range in
 * plain terms — a literal {@link #prefix}, a total length, a character kind and
 * two positions — and {@code CouponPatternService} GENERATES the recognition
 * pattern from them. The register never carries the layout in its code; it reads
 * it here. A store that changes its scale plan changes rows, not classes.
 *
 * <p>Why a separate entity rather than a {@link CouponType} with a flag: a
 * voucher type answers questions an article range has no business answering —
 * how the amount is settled, whether the paper is a deposit return, which
 * payment panel lists it. Every voucher query would then have to exclude the
 * article ranges, on every call site. Two entities, one shared positional
 * vocabulary ({@link CouponField.Kind}, {@link CouponField.PriceCurrency}).
 */
@Entity
@Table(name = "article_barcode_range")
public class ArticleBarcodeRange extends PanacheEntity {

    /** The smallest total length a barcode range may declare (BO-03-06-10). */
    public static final int MIN_CODE_LENGTH = 1;

    /** The largest total length a barcode range may declare (BO-03-06-10). */
    public static final int MAX_CODE_LENGTH = 38;

    /** The stable technical code of the range (for example, BALANCE_PRIX). */
    @Column(name = "code", unique = true, nullable = false, length = 50)
    public String code;

    /** The human-readable label shown in the back office. */
    @Column(name = "label", nullable = false, length = 100)
    public String label;

    /** Whether the range is considered at scan time; deactivated, never deleted. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /** The order the ranges are tested in; lower runs first. */
    @Column(name = "priority", nullable = false)
    public int priority = 100;

    /** The literal head every code of the range carries (BO-03-06-02). */
    @Column(name = "prefix", nullable = false, length = 40)
    public String prefix;

    /** The total number of characters of a code of the range (BO-03-06-10). */
    @Column(name = "code_length", nullable = false)
    public int codeLength;

    /** The characters the range accepts outside its two positions (BO-03-06-02). */
    @Enumerated(EnumType.STRING)
    @Column(name = "code_kind", nullable = false, length = 16)
    public CouponField.Kind codeKind = CouponField.Kind.NUMERIC;

    /** The 0-based offset of the segment naming the article (BO-03-06-02). */
    @Column(name = "article_position", nullable = false)
    public int articlePosition;

    /** The number of characters of the segment naming the article (BO-03-06-02). */
    @Column(name = "article_length", nullable = false)
    public int articleLength;

    /** What the value segment carries — a price or a weight (BO-03-06-03/04). */
    @Enumerated(EnumType.STRING)
    @Column(name = "value_source", nullable = false, length = 16)
    public ValueSource valueSource = ValueSource.PRICE;

    /** The 0-based offset of the segment carrying the value (BO-03-06-03/04). */
    @Column(name = "value_position", nullable = false)
    public int valuePosition;

    /** The number of characters of the segment carrying the value (BO-03-06-03/04). */
    @Column(name = "value_length", nullable = false)
    public int valueLength;

    /**
     * The number of decimals the value segment carries: 2 for a price in cents,
     * 3 for a weight in grams (BO-03-06-04 — "quantités en décimales").
     */
    @Column(name = "value_decimals", nullable = false)
    public int valueDecimals = 2;

    /**
     * The currency the PRICE segment is expressed in (BO-03-06-05). A range in
     * anything but euros is converted through the {@code Currency} referential
     * at the administered rate; the field is meaningless on a WEIGHT range.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 8)
    public CouponField.PriceCurrency currency = CouponField.PriceCurrency.EUR;

    /**
     * Whether the last character is a check digit the register verifies
     * (EAN13 modulo 10). A code failing it is not this range's code and falls
     * through to the next handler rather than raising an error.
     */
    @Column(name = "check_digit", nullable = false)
    public boolean checkDigit = true;

    /**
     * The generated pattern a code must match to belong to this range; written
     * by {@code CouponPatternService} from the fields above, never by hand.
     */
    @Column(name = "match_pattern", nullable = false, length = 255)
    public String matchPattern;

    /**
     * What the register DOES with a code of this range (BO-03-06-56/-60/-61).
     *
     * <p>The positional vocabulary above says how to READ a code; it never said
     * what the code MEANS. Every range meant "an article with its price or its
     * weight", because that was the only thing the chain could do with one. The
     * questionnaire asks for three more meanings on the same vocabulary — a
     * return, a container tare, a counter ticket — and none of them deserves a
     * second table: the same prefix, the same length, the same two segments.
     *
     * <p>Defaults to {@link Purpose#ARTICLE}, so every range administered before
     * this column existed keeps doing exactly what it did.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20)
    public Purpose purpose = Purpose.ARTICLE;

    /**
     * The name of the promotion a {@link Purpose#PROMOTION} range triggers
     * (BO-03-06-43); null, and meaningless, on every other range.
     *
     * <p>A NAME and nothing more, deliberately. Neither this node nor the
     * register knows what a promotion is worth, and neither must decide it: a
     * promotion is a rule the valuation engine owns. The only honest thing a
     * barcode can do is name the promotion it stands for; the scan arms that
     * name on the ticket and the engine resolves it, exactly as it resolves a
     * card-borne one.
     */
    @Column(name = "promotion_code", length = 60)
    public String promotionCode;

    /**
     * The URL of the THIRD-PARTY service asked about a code of this range
     * (BO-03-06-41); null on every range the register interprets by itself.
     *
     * <p>Some codes are not this referential's to read: a partner's voucher, a
     * ticketing stub, a coupon printed by a system nobody here models. The URL
     * names the service that does know, and may carry {@code {code}} where the
     * scanned code belongs; without the placeholder the code is appended.
     */
    @Column(name = "lookup_url", length = 255)
    public String lookupUrl;

    /**
     * Returns the 0-based position just after the article segment.
     *
     * @return the end position of the article segment
     */
    public int articleEndPosition() {
        return articlePosition + articleLength;
    }

    /**
     * Returns the 0-based position just after the value segment.
     *
     * @return the end position of the value segment
     */
    public int valueEndPosition() {
        return valuePosition + valueLength;
    }

    /**
     * Indicates whether the two administered segments overlap, which no valid
     * range may do.
     *
     * @return true when the article and value segments share a position
     */
    public boolean segmentsOverlap() {
        return articlePosition < valueEndPosition() && valuePosition < articleEndPosition();
    }

    /**
     * Tests whether the given code belongs to this range.
     *
     * @param scanned the scanned code, or null
     * @return true when the code matches the generated pattern
     */
    public boolean matches(String scanned) {
        if (scanned == null || matchPattern == null || matchPattern.isBlank()) {
            return false;
        }
        return scanned.matches(matchPattern);
    }

    /**
     * Reads the article segment, leading zeros stripped, as the catalog holds
     * the PLU.
     *
     * @param scanned the scanned code, or null
     * @return the article code, or null when the segment cannot be read
     */
    public String articleCode(String scanned) {
        String raw = articleSegment(scanned);
        if (raw == null) {
            return null;
        }
        String stripped = raw.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

    /**
     * Reads the article segment RAW, leading zeros kept.
     *
     * <p>A counter-ticket reference is a key and not a number: the shop matches
     * it character for character, and stripping its zeros would look up a
     * reference nobody printed (BO-03-06-56).
     *
     * @param scanned the scanned code, or null
     * @return the raw segment, or null when it cannot be read
     */
    public String articleSegment(String scanned) {
        return segment(scanned, articlePosition, articleLength);
    }

    /**
     * Reads the value segment as a decimal, with the administered number of
     * decimals — a price in the range's own currency, or a weight.
     *
     * @param scanned the scanned code, or null
     * @return the value, or null when the segment carries no readable number
     */
    public BigDecimal rawValue(String scanned) {
        String raw = segment(scanned, valuePosition, valueLength);
        if (raw == null) {
            return null;
        }
        try {
            return BigDecimal.valueOf(Long.parseLong(raw.trim()), valueDecimals);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Indicates whether the value segment carries a price rather than a weight.
     *
     * @return true when the range is a price-embedded one
     */
    public boolean isPriceEmbedded() {
        return valueSource == ValueSource.PRICE;
    }

    /**
     * Indicates whether the administered currency is the euro, which is the only
     * case needing no conversion.
     *
     * @return true when the range is expressed in euros
     */
    public boolean isEuro() {
        return currency == null || currency == CouponField.PriceCurrency.EUR;
    }

    /**
     * Reads a segment of a code, refusing a code too short to carry it.
     *
     * @param scanned the scanned code, or null
     * @param offset the 0-based start of the segment
     * @param length the number of characters
     * @return the raw segment, or null when it cannot be read
     */
    private String segment(String scanned, int offset, int length) {
        if (scanned == null || offset < 0 || length <= 0) {
            return null;
        }
        if (scanned.length() < offset + length) {
            return null;
        }
        return scanned.substring(offset, offset + length);
    }

    /**
     * Returns the active ranges in administered order, the order they are
     * tested in at scan time.
     *
     * @return the ordered list of active ranges, empty when none is administered
     */
    public static List<ArticleBarcodeRange> listActiveByPriority() {
        return list("active = true order by priority, code");
    }

    /**
     * Returns the active range recognizing the given code.
     *
     * @param scanned the scanned code, or null
     * @return the first matching active range, or null when none recognizes it
     */
    public static ArticleBarcodeRange findMatching(String scanned) {
        if (scanned == null || scanned.isBlank()) {
            return null;
        }
        for (ArticleBarcodeRange range : listActiveByPriority()) {
            if (range != null && range.matches(scanned)) {
                return range;
            }
        }
        return null;
    }

    /**
     * Names what the value segment of a range carries.
     */
    public enum ValueSource {

        /** The segment carries the line total, in the range's currency (BO-03-06-03). */
        PRICE,

        /** The segment carries the weight, with the administered decimals (BO-03-06-04). */
        WEIGHT
    }

    /**
     * Names what the register does with a code of the range.
     */
    public enum Purpose {

        /** An article line, priced or weighed by the code (BO-03-06-02 to -05). */
        ARTICLE,

        /**
         * A RETURN of the article the code names, at the price the code carries
         * (BO-03-06-61) — rung on the sale in progress, without the original
         * ticket: the paper the code is printed on IS the authorisation.
         */
        REFUND,

        /**
         * The TARE of a container (BO-03-06-60): the value segment carries the
         * container's weight, deducted from the next weighed line.
         */
        TARE,

        /**
         * A counter ticket (BO-03-06-56): the article segment carries the
         * reference of the bag the scales pushed to the shop, picked up whole.
         */
        BALANCE_TICKET,

        /**
         * A named PROMOTION triggered on the sale in progress (BO-03-06-43):
         * the range names the promotion, the scan arms it on the ticket, and
         * the valuation engine is what resolves and applies it. No line is
         * rung — a promotion is not an article.
         */
        PROMOTION,

        /**
         * A code only a THIRD PARTY can read (BO-03-06-41): the range names the
         * service, the register asks it, and acts on what comes back.
         */
        EXTERNAL_LOOKUP,

        /**
         * A customer ORDER, quote or sale voucher recalled by its code
         * (BO-03-06-58): the article segment carries the order's reference,
         * which the scan arms on the ticket.
         */
        CUSTOMER_ORDER
    }

    /**
     * Indicates whether the range is an ordinary article range.
     *
     * @return true when a code of the range rings an article line
     */
    public boolean isArticlePurpose() {
        return purpose == null || purpose == Purpose.ARTICLE;
    }

    /**
     * Indicates whether the range triggers a named promotion (BO-03-06-43).
     *
     * <p>A promotion range that names no promotion triggers nothing: the
     * administered row is incomplete, and arming an empty name on a ticket
     * would send the engine a promotion nobody can resolve.
     *
     * @return true when the range is a promotion range carrying a name
     */
    public boolean triggersPromotion() {
        return purpose == Purpose.PROMOTION
                && promotionCode != null
                && !promotionCode.isBlank();
    }

    /**
     * Indicates whether the range names a third-party service to ask
     * (BO-03-06-41).
     *
     * <p>An EXTERNAL_LOOKUP range naming no URL asks nobody: the administered
     * row is incomplete, and the register says so rather than swallowing the
     * code in silence.
     *
     * @return true when the range is an external-lookup range carrying a URL
     */
    public boolean hasExternalLookup() {
        return purpose == Purpose.EXTERNAL_LOOKUP
                && lookupUrl != null
                && !lookupUrl.isBlank();
    }
}
