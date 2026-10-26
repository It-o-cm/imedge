package com.intermarche.edge.ui.report;

import java.time.format.DateTimeFormatter;

/**
 * Says, in one sentence, WHICH period a report covers.
 *
 * <p>Printed at the top of the screen and of all three exports. A report found
 * on a desk or in a mailbox has to state the interval it measured: « État des
 * règlements » alone is a figure without a question, and two of them side by
 * side are indistinguishable.
 */
public final class ReportHeading {

    /** French day format. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** French day and time format, for the bound that is not a whole day. */
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /**
     * Not instantiable.
     */
    private ReportHeading() {
    }

    /**
     * Renders the covered period.
     *
     * <p>The upper bound is EXCLUSIVE inside the frame, and shown inclusive
     * here: a report covering [1 Sept, 2 Sept[ reads « du 01/09/2025 au
     * 01/09/2025 », which is what the operator asked for. Printing the raw
     * exclusive bound would date every daily report a day late.
     *
     * @param criteria the edition parameters
     * @return the sentence naming the period
     */
    public static String period(ReportCriteria criteria) {
        java.time.LocalDateTime from = criteria.from();
        java.time.LocalDateTime to = criteria.to();
        java.time.LocalDate lastDay = to.toLocalDate().minusDays(1);
        if (to.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                && from.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) {
            if (from.toLocalDate().equals(lastDay)) {
                return criteria.period.getLabel() + " : " + DAY.format(from);
            }
            return criteria.period.getLabel() + " : du " + DAY.format(from) + " au " + DAY.format(lastDay);
        }
        return criteria.period.getLabel() + " : du " + MOMENT.format(from) + " au " + MOMENT.format(to);
    }

    /**
     * Renders the covered period in a language (BO-06-01-02): the period's
     * label and the range words translated, the dates kept as they are written.
     *
     * @param criteria the edition parameters
     * @param messages the messages of the language to write in
     * @return the sentence naming the period, in that language
     */
    public static String period(ReportCriteria criteria, com.intermarche.edge.ui.admin.BackOfficeMessages messages) {
        String label = criteria.period.getLabel();
        String dates = period(criteria).substring(label.length() + " : ".length());
        return messages.text(label) + " : " + messages.text(dates);
    }
}
