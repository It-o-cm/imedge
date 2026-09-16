package com.intermarche.pos.domain.barcode;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * One administered field of a barcode range, given by its POSITION rather than
 * by a capturing group (BO-03-06-03/04/11/13/15/16/17/18/19/21/22/23/24/27/28/
 * 29/30/31/32/33/34/35/36/37/38).
 *
 * <p>The back office does not write regular expressions: it states where a
 * value sits in the code and how to read it — offset, length, character kind,
 * decimal count, date or time layout, currency. The recognition pattern of the
 * owning {@link CouponType} is GENERATED from these rows by
 * {@code CouponPatternService}; extraction itself is positional and needs no
 * pattern at all.
 *
 * <p>At most one field per {@link Role} and per type: the role IS the name the
 * till asks for, so two fields claiming the same role would leave the reading
 * ambiguous.
 */
@Entity
@Table(name = "coupon_field")
public class CouponField extends PanacheEntity {

    /** The default number of decimals of a monetary field when none is administered. */
    public static final int DEFAULT_DECIMALS = 2;

    /**
     * The barcode range this field belongs to.
     */
    @ManyToOne(optional = false)
    @JoinColumn(name = "coupon_type_id", nullable = false)
    public CouponType couponType;

    /**
     * What the field holds, which is also the name the till reads it by.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    public Role role;

    /**
     * The zero-based position of the field's first character in the code.
     */
    @Column(name = "offset_position", nullable = false)
    public int offsetPosition;

    /**
     * The number of characters the field spans.
     */
    @Column(name = "field_length", nullable = false)
    public int fieldLength;

    /**
     * The characters the field accepts, which drives the generated pattern.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    public Kind kind = Kind.NUMERIC;

    /**
     * The number of decimals of a monetary or weighed field, or null for the
     * default of two (BO-03-06-13/29).
     */
    @Column(name = "decimals")
    public Integer decimals;

    /**
     * The layout of a date or time field, or null when the field is not one
     * (BO-03-06-18/19/21/22/23/24/25/31/32).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "date_format", length = 16)
    public DateFormat dateFormat;

    /**
     * The currency a price field is expressed in, or null for euros
     * (BO-03-06-03/05).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "currency", length = 8)
    public PriceCurrency currency;

    /**
     * Returns the position just past the field's last character.
     *
     * @return the exclusive end position
     */
    public int endPosition() {
        return offsetPosition + fieldLength;
    }

    /**
     * Indicates whether this field and another one claim a common position.
     *
     * @param other the field to compare with, or null
     * @return true when the two spans intersect
     */
    public boolean overlaps(CouponField other) {
        if (other == null) {
            return false;
        }
        return offsetPosition < other.endPosition() && other.offsetPosition < endPosition();
    }

    /**
     * Returns the number of decimals to apply, defaulting when none is administered.
     *
     * @return the administered decimal count, or two
     */
    public int effectiveDecimals() {
        return decimals == null ? DEFAULT_DECIMALS : decimals;
    }

    /**
     * Reads the field's characters out of a scanned code.
     *
     * @param code the scanned or typed code, or null
     * @return the substring the field spans, or null when the code is too short
     */
    public String raw(String code) {
        if (code == null || offsetPosition < 0 || fieldLength <= 0) {
            return null;
        }
        if (code.length() < endPosition()) {
            return null;
        }
        return code.substring(offsetPosition, endPosition());
    }

    /**
     * Reads the field as a decimal number, honouring the administered decimals.
     *
     * @param code the scanned or typed code, or null
     * @return the value, or null when the field cannot be read as a number
     */
    public BigDecimal decimalValue(String code) {
        String raw = raw(code);
        if (raw == null) {
            return null;
        }
        try {
            return BigDecimal.valueOf(Long.parseLong(raw.trim()), effectiveDecimals());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads the field as a date, honouring the administered layout.
     *
     * @param code the scanned or typed code, or null
     * @param century the century to complete a two-digit year with (for example 2000)
     * @return the date, or null when the field carries no readable date
     */
    public LocalDate dateValue(String code, int century) {
        String raw = raw(code);
        if (raw == null || dateFormat == null) {
            return null;
        }
        return dateFormat.toDate(raw, century);
    }

    /**
     * Reads the field as a time of day, honouring the administered layout.
     *
     * @param code the scanned or typed code, or null
     * @return the time, or null when the field carries no readable time
     */
    public LocalTime timeValue(String code) {
        String raw = raw(code);
        if (raw == null || dateFormat == null) {
            return null;
        }
        return dateFormat.toTime(raw);
    }


    /**
     * Names what an administered field holds.
     */
    public enum Role {

        /** The price or amount carried by the code (BO-03-06-03/11/13). */
        PRICE,

        /** The quantity or weight carried by the code (BO-03-06-04). */
        QUANTITY,

        /** The point of sale number the code is restricted to (BO-03-06-15). */
        STORE_NUMBER,

        /** The control character of the point of sale number (BO-03-06-27). */
        STORE_CHECK_DIGIT,

        /** The date the code becomes usable (BO-03-06-16). */
        DATE_START,

        /** The date the code expires (BO-03-06-17). */
        DATE_END,

        /** The time of day carried by the code (BO-03-06-30). */
        TIME,

        /** The ticket number carried by the code (BO-03-06-33/34). */
        TICKET_NUMBER,

        /** The issue sequence number, used to spot duplicates (BO-03-06-35/36). */
        SEQUENCE_NUMBER,

        /** The register number carried by the code (BO-03-06-37/38). */
        TPV_NUMBER,

        /** The minimum ticket total the code requires (BO-03-06-28/29). */
        MIN_TICKET_TOTAL,

        /**
         * The part of the loyalty card number the code carries, compared with
         * the card presented in the sale (BO-03-06-40).
         */
        CARD_MATCH
    }

    /**
     * Names the characters a position accepts, which is what the generated
     * pattern spells out.
     */
    public enum Kind {

        /** Digits only. */
        NUMERIC("\\d"),

        /** Latin letters and digits (BO-03-06-09). */
        ALPHANUMERIC("[A-Za-z0-9]"),

        /** Any character. */
        ANY(".");

        /** The regular expression token matching one character of that kind. */
        private final String token;

        /**
         * Builds a kind over the token standing for one of its characters.
         *
         * @param token the single-character regular expression token
         */
        Kind(String token) {
            this.token = token;
        }

        /**
         * Returns the regular expression token matching one character.
         *
         * @return the token
         */
        public String getToken() {
            return token;
        }
    }

    /**
     * Names the layouts a date or time field can be administered in
     * (BO-03-06-18/19/21/22/23/24/25/31/32).
     */
    public enum DateFormat {

        /** Day, month, two-digit year. */
        DDMMYY(6),

        /** Month, day, two-digit year. */
        MMDDYY(6),

        /** Day and month, the year being the current one. */
        DDMM(4),

        /** Month and day, the year being the current one. */
        MMDD(4),

        /** Day, month, four-digit year. */
        DDMMYYYY(8),

        /** Four-digit year, month, day. */
        YYYYMMDD(8),

        /** Day of the year, from 1 to 366, the year being the current one. */
        DDD(3),

        /** Hours and minutes. */
        HHMM(4),

        /** Hours, minutes and seconds. */
        HHMMSS(6);

        /** The number of characters the layout occupies. */
        private final int width;

        /**
         * Builds a layout over the number of characters it occupies.
         *
         * @param width the character count
         */
        DateFormat(int width) {
            this.width = width;
        }

        /**
         * Returns the number of characters the layout occupies.
         *
         * @return the character count
         */
        public int getWidth() {
            return width;
        }

        /**
         * Indicates whether the layout describes a time of day rather than a date.
         *
         * @return true for the time layouts
         */
        public boolean isTime() {
            return this == HHMM || this == HHMMSS;
        }

        /**
         * Reads a date out of the characters the field spans.
         *
         * @param raw the field's characters
         * @param century the century completing a two-digit year (for example 2000)
         * @return the date, or null when the characters do not form one
         */
        public LocalDate toDate(String raw, int century) {
            if (isTime() || raw == null || raw.length() != width) {
                return null;
            }
            try {
                return switch (this) {
                    case DDMMYY -> LocalDate.of(century + part(raw, 4, 2), part(raw, 2, 2), part(raw, 0, 2));
                    case MMDDYY -> LocalDate.of(century + part(raw, 4, 2), part(raw, 0, 2), part(raw, 2, 2));
                    case DDMM -> LocalDate.of(LocalDate.now().getYear(), part(raw, 2, 2), part(raw, 0, 2));
                    case MMDD -> LocalDate.of(LocalDate.now().getYear(), part(raw, 0, 2), part(raw, 2, 2));
                    case DDMMYYYY -> LocalDate.of(part(raw, 4, 4), part(raw, 2, 2), part(raw, 0, 2));
                    case YYYYMMDD -> LocalDate.of(part(raw, 0, 4), part(raw, 4, 2), part(raw, 6, 2));
                    case DDD -> LocalDate.ofYearDay(LocalDate.now().getYear(), part(raw, 0, 3));
                    default -> null;
                };
            } catch (NumberFormatException | java.time.DateTimeException e) {
                return null;
            }
        }

        /**
         * Reads a time of day out of the characters the field spans.
         *
         * @param raw the field's characters
         * @return the time, or null when the characters do not form one
         */
        public LocalTime toTime(String raw) {
            if (!isTime() || raw == null || raw.length() != width) {
                return null;
            }
            try {
                return this == HHMM
                        ? LocalTime.of(part(raw, 0, 2), part(raw, 2, 2))
                        : LocalTime.of(part(raw, 0, 2), part(raw, 2, 2), part(raw, 4, 2));
            } catch (NumberFormatException | java.time.DateTimeException e) {
                return null;
            }
        }

        /**
         * Reads a fixed-width integer out of the field's characters.
         *
         * @param raw the field's characters
         * @param from the zero-based start position
         * @param size the number of characters to read
         * @return the parsed number
         */
        private static int part(String raw, int from, int size) {
            return Integer.parseInt(raw.substring(from, from + size));
        }
    }

    /**
     * Names the currency a price field is expressed in (BO-03-06-03/05).
     */
    public enum PriceCurrency {

        /** Euros. */
        EUR,

        /** French francs, still administered on legacy ranges. */
        FRF
    }
}
