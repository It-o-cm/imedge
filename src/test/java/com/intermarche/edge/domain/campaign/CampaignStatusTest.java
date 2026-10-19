package com.intermarche.edge.domain.campaign;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link CampaignStatus}.
 * <p>
 * The status is a pure function of a half-open period and a reference day, so
 * every test states an absolute expected value. Both legs of each guard of
 * {@link CampaignStatus#of} are exercised: the null and non-null arms of both
 * bounds, the inclusive {@code validFrom} boundary and the exclusive
 * {@code validTo} boundary.
 */
class CampaignStatusTest {

    /**
     * A day before the inclusive start is upcoming (validFrom non-null,
     * on-before-validFrom true).
     */
    @Test
    void beforeStartIsUpcoming() {
        assertEquals(CampaignStatus.UPCOMING, CampaignStatus.of(
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 1, 15)));
    }

    /**
     * A day within a bounded period is running (both bounds non-null, neither
     * boundary reached).
     */
    @Test
    void withinBoundedPeriodIsRunning() {
        assertEquals(CampaignStatus.RUNNING, CampaignStatus.of(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 15)));
    }

    /**
     * The inclusive start day is running, not upcoming (on-before-validFrom
     * false with validFrom non-null).
     */
    @Test
    void inclusiveStartDayIsRunning() {
        assertEquals(CampaignStatus.RUNNING, CampaignStatus.of(
                LocalDate.of(2026, 1, 1), null, LocalDate.of(2026, 1, 1)));
    }

    /**
     * The exclusive end day is finished (validTo non-null, on not before
     * validTo true, with validFrom null so the first guard is skipped).
     */
    @Test
    void exclusiveEndDayIsFinished() {
        assertEquals(CampaignStatus.FINISHED, CampaignStatus.of(
                null, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)));
    }

    /**
     * A period open on both sides is always running (both bounds null).
     */
    @Test
    void openPeriodIsAlwaysRunning() {
        assertEquals(CampaignStatus.RUNNING, CampaignStatus.of(
                null, null, LocalDate.of(2026, 1, 15)));
    }
}
