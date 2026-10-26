package com.intermarche.edge.ui.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Turns a raw report value into the characters a HUMAN reads — on screen, in
 * the CSV and in the PDF.
 *
 * <p>One place, so the three agree to the character: an amount that reads
 * {@code 1 234,50 €} on screen must read the same in the PDF handed to the
 * accountant, or the two documents look like two measurements.
 *
 * <p>The spreadsheet does NOT come through here: it receives the raw number and
 * a cell format, because a recipient who opens it is going to sum a column.
 */
public final class ReportFormat {

    /** French day format. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** French time format, to the minute. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** French day and time format. */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /**
     * Not instantiable.
     */
    private ReportFormat() {
    }

    /**
     * Renders one cell for a human reader.
     *
     * @param column the column the value belongs to
     * @param value the raw value, possibly null
     * @return the rendered text, empty for a null value
     */
    public static String cell(ReportColumn column, Object value) {
        if (value == null) {
            return "";
        }
        return switch (column.kind()) {
            case AMOUNT -> amount(value);
            case DECIMAL -> group(decimal(value).setScale(2, RoundingMode.HALF_UP));
            case PERCENT -> percent(value);
            case INTEGER -> integer(value);
            case DATE -> date(value);
            case TIME -> time(value);
            case DATETIME -> dateTime(value);
            case TEXT -> String.valueOf(value);
        };
    }

    /**
     * Renders an amount with two decimals, a comma and a currency sign.
     *
     * @param value the raw value
     * @return the rendered amount
     */
    private static String amount(Object value) {
        return group(decimal(value).setScale(2, RoundingMode.HALF_UP)) + " €";
    }

    /**
     * Renders a share with one decimal and a percent sign.
     *
     * @param value the raw value
     * @return the rendered share
     */
    private static String percent(Object value) {
        return decimal(value).setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " %";
    }

    /**
     * Renders a count, thousands separated.
     *
     * @param value the raw value
     * @return the rendered count
     */
    private static String integer(Object value) {
        return group(decimal(value).setScale(0, RoundingMode.HALF_UP));
    }

    /**
     * Renders a day.
     *
     * @param value a {@code LocalDate} or a {@code LocalDateTime}
     * @return the rendered day, or the value's own text when it is neither
     */
    private static String date(Object value) {
        if (value instanceof LocalDate day) {
            return DATE.format(day);
        }
        if (value instanceof LocalDateTime moment) {
            return DATE.format(moment);
        }
        return String.valueOf(value);
    }

    /**
     * Renders a time of day.
     *
     * @param value a {@code LocalTime} or a {@code LocalDateTime}
     * @return the rendered time, or the value's own text when it is neither
     */
    private static String time(Object value) {
        if (value instanceof LocalTime moment) {
            return TIME.format(moment);
        }
        if (value instanceof LocalDateTime moment) {
            return TIME.format(moment);
        }
        return String.valueOf(value);
    }

    /**
     * Renders a day and a time of day.
     *
     * @param value a {@code LocalDateTime}
     * @return the rendered moment, or the value's own text when it is not one
     */
    private static String dateTime(Object value) {
        if (value instanceof LocalDateTime moment) {
            return DATE_TIME.format(moment);
        }
        return String.valueOf(value);
    }

    /**
     * Reads any numeric value as a decimal.
     *
     * @param value the raw value
     * @return the decimal, zero when the value is not numeric
     */
    static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte) {
            // Read through longValue and NOT through doubleValue: a count past
            // two to the fifty-third would come back rounded, and a report's
            // figures have to be the figures.
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    /**
     * Groups the integer part in threes with a NARROW no-break space and uses a
     * comma for the decimal separator.
     *
     * <p>A narrow no-break space, not a plain one: a plain space lets a browser
     * break {@code 1 234,50 €} across two lines, which turns one amount into two
     * numbers in a column of figures.
     *
     * @param value the decimal to render
     * @return the grouped text
     */
    private static String group(BigDecimal value) {
        String plain = value.abs().toPlainString();
        String integerPart = plain.contains(".") ? plain.substring(0, plain.indexOf('.')) : plain;
        String decimalPart = plain.contains(".") ? plain.substring(plain.indexOf('.') + 1) : "";
        StringBuilder grouped = new StringBuilder();
        int count = 0;
        for (int i = integerPart.length() - 1; i >= 0; i--) {
            grouped.append(integerPart.charAt(i));
            count++;
            if (count % 3 == 0 && i > 0) {
                grouped.append(' ');
            }
        }
        grouped.reverse();
        if (!decimalPart.isEmpty()) {
            grouped.append(',').append(decimalPart);
        }
        return (value.signum() < 0 ? "-" : "") + grouped;
    }
}
