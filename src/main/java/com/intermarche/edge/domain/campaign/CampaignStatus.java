package com.intermarche.edge.domain.campaign;

import java.time.LocalDate;

/**
 * The lifecycle state of a {@link Campaign} as READ from its period against a
 * reference day.
 *
 * <p>It is derived, never stored: a campaign's row carries only its bounds, and
 * whether it is upcoming, running or finished is a pure function of those bounds
 * and the day one looks at them. Keeping it out of the table means a campaign
 * never has to be "flipped" by a scheduled job — it becomes running the moment
 * its {@code validFrom} arrives and finished the moment its {@code validTo}
 * passes, with no write.
 */
public enum CampaignStatus {

    /** The reference day is before the inclusive {@code validFrom} bound. */
    UPCOMING,

    /** The reference day is within the period (or the period is open). */
    RUNNING,

    /** The reference day is on or after the exclusive {@code validTo} bound. */
    FINISHED;

    /**
     * Reads the status of a period on a reference day.
     *
     * <p>{@code validFrom} is inclusive, {@code validTo} is exclusive, and a
     * null bound is an open one: a null {@code validFrom} is running from the
     * beginning of time, a null {@code validTo} never finishes.
     *
     * @param validFrom the inclusive start bound, or null when open
     * @param validTo the exclusive end bound, or null when open
     * @param on the reference day
     * @return the status on that day
     */
    public static CampaignStatus of(LocalDate validFrom, LocalDate validTo, LocalDate on) {
        if (validFrom != null && on.isBefore(validFrom)) {
            return UPCOMING;
        }
        if (validTo != null && !on.isBefore(validTo)) {
            return FINISHED;
        }
        return RUNNING;
    }
}
