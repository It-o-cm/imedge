package com.intermarche.edge.service.export;

import com.intermarche.edge.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The ACCOUNTING DAY of a sale (BO-11-01-05): which day a transaction is counted
 * on, which is NOT the same question as which day it happened on.
 *
 * <p>A shop that closes at one in the morning posts the sales of that last hour
 * on the evening it belongs to, not on the calendar day the clock had already
 * turned to. The back office administers the hour of the switch, and this class
 * is the one place that applies it — the requirement says it applies JOINTLY to
 * the revenue and cash-book flows, so the two must never answer differently
 * about the same ticket.
 *
 * <p>A cut-over hour of zero makes the accounting day the civil day, which is
 * what the node did before the parameter existed: the default changes nothing.
 */
@ApplicationScoped
public class AccountingDay {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AccountingDay.class);

    /** The administered cut-over hour, resolved through the echelons. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Returns the accounting day a sale belongs to.
     *
     * <p>Before the cut-over hour the sale still belongs to the PREVIOUS day:
     * the hour names the moment the new accounting day opens, so everything
     * timestamped earlier is the tail of the day before. With a cut-over of zero
     * no sale is ever before it, and the answer is the calendar day.
     *
     * @param moment the instant the sale was closed, or null
     * @return the accounting day, or null when the instant is null
     */
    public LocalDate of(LocalDateTime moment) {
        LOGGER.info("Entering method of with moment: " + moment);
        if (moment == null) {
            LOGGER.info("Exiting method of");
            return null;
        }
        int cutover = posSettingsService.accountingCutoverHour();
        LocalDate day = moment.toLocalDate();
        if (moment.getHour() < cutover) {
            day = day.minusDays(1);
        }
        LOGGER.info("Exiting method of");
        return day;
    }

    /**
     * Returns the instant an accounting day OPENS, inclusive.
     *
     * @param day the accounting day
     * @return the first instant counted on that day
     */
    public LocalDateTime startOf(LocalDate day) {
        LOGGER.info("Entering method startOf with day: " + day);
        LocalDateTime start = day.atStartOfDay().plusHours(posSettingsService.accountingCutoverHour());
        LOGGER.info("Exiting method startOf");
        return start;
    }

    /**
     * Returns the instant an accounting day CLOSES, exclusive — which is the
     * instant the next one opens.
     *
     * <p>Exclusive and not inclusive so that the two bounds of consecutive days
     * meet without overlapping: a ticket closed exactly on the cut-over second
     * must be counted once, and on the later day.
     *
     * @param day the accounting day
     * @return the first instant NO LONGER counted on that day
     */
    public LocalDateTime endOf(LocalDate day) {
        LOGGER.info("Entering method endOf with day: " + day);
        LocalDateTime end = startOf(day.plusDays(1));
        LOGGER.info("Exiting method endOf");
        return end;
    }
}
