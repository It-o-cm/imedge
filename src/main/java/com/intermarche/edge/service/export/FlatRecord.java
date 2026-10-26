package com.intermarche.edge.service.export;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * One record of a POSITIONED-FIELD flat file — the shape the accounting .dat
 * flows use, where a field is found by its offset and its width and not by a
 * separator (BO-11-01-12).
 *
 * <p>A separated file forgives a missing field; a flat file does not. Everything
 * here is therefore about never shifting an offset: a text longer than its field
 * is TRUNCATED rather than allowed to push the rest of the record, a text shorter
 * is padded with spaces, an amount is right-aligned and zero-filled, and a
 * separator or a line break found inside a value is replaced by a space — one
 * newline in a customer's name would otherwise split one record into two.
 *
 * <p>Amounts are written in CENTS with no decimal point, which is how such files
 * carry money: the point is implied by the annex, and writing it would consume a
 * character the receiving layout does not reserve. The widths themselves live
 * with the caller, beside the layout they implement.
 *
 * <p>The exact layout of {@code clifact.dat} comes from the receiving side's
 * annex, which this repository does not hold: the writer is the mechanism, the
 * field order and widths used by the invoice flow are the house reading of the
 * requirement and are to be confirmed before any real exchange.
 */
public final class FlatRecord {

    /** The characters accumulated so far, already padded to their widths. */
    private final StringBuilder buffer = new StringBuilder();

    /**
     * Appends a text field, padded with spaces on the right or truncated.
     *
     * @param value the value, possibly null — then an empty field
     * @param width the width of the field, in characters
     * @return this record, to chain the next field
     */
    public FlatRecord text(String value, int width) {
        String safe = value == null ? "" : value.replaceAll("[\\r\\n;]", " ");
        if (safe.length() > width) {
            buffer.append(safe, 0, width);
        } else {
            buffer.append(safe);
            buffer.append(" ".repeat(width - safe.length()));
        }
        return this;
    }

    /**
     * Appends an amount field, in cents, right-aligned and zero-filled.
     *
     * <p>A negative amount keeps its sign in the first character, where the
     * layouts put it; an amount too wide for its field is written as nines rather
     * than truncated, because a truncated amount is a plausible wrong number
     * while a field of nines is visibly impossible.
     *
     * @param value the amount, possibly null — then zero
     * @param width the width of the field, in characters
     * @return this record, to chain the next field
     */
    public FlatRecord amount(BigDecimal value, int width) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        BigDecimal cents = safe.setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2).setScale(0, RoundingMode.HALF_UP);
        boolean negative = cents.signum() < 0;
        String digits = cents.abs().toPlainString();
        int room = negative ? width - 1 : width;
        if (digits.length() > room) {
            buffer.append("9".repeat(width));
            return this;
        }
        if (negative) {
            buffer.append('-');
        }
        buffer.append("0".repeat(room - digits.length()));
        buffer.append(digits);
        return this;
    }

    /**
     * Appends an integer field, right-aligned and zero-filled.
     *
     * @param value the value
     * @param width the width of the field, in characters
     * @return this record, to chain the next field
     */
    public FlatRecord number(long value, int width) {
        String digits = Long.toString(Math.abs(value));
        boolean negative = value < 0;
        int room = negative ? width - 1 : width;
        if (digits.length() > room) {
            buffer.append("9".repeat(width));
            return this;
        }
        if (negative) {
            buffer.append('-');
        }
        buffer.append("0".repeat(room - digits.length()));
        buffer.append(digits);
        return this;
    }

    /**
     * Appends a date field, spelled {@code yyyyMMdd}.
     *
     * @param value the date, possibly null — then eight spaces
     * @return this record, to chain the next field
     */
    public FlatRecord date(LocalDate value) {
        if (value == null) {
            return text(null, 8);
        }
        return text(value.format(DateTimeFormatter.ofPattern("yyyyMMdd")), 8);
    }

    /**
     * Appends an instant as a date and a time, {@code yyyyMMddHHmm}.
     *
     * @param value the instant, possibly null — then twelve spaces
     * @return this record, to chain the next field
     */
    public FlatRecord instant(LocalDateTime value) {
        if (value == null) {
            return text(null, 12);
        }
        return text(value.format(DateTimeFormatter.ofPattern("yyyyMMddHHmm")), 12);
    }

    /**
     * Returns the record as one line, trailing padding included.
     *
     * <p>The padding is kept on purpose: a flat record has a fixed length, and a
     * receiving side that reads the last field by its offset must find it there
     * even when it is empty.
     *
     * @return the assembled record
     */
    public String line() {
        return buffer.toString();
    }
}
