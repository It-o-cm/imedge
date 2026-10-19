package com.intermarche.edge.service.export;

import com.intermarche.edge.service.PosSettingsService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountingDay} (BO-11-01-05), targeting 100% branch
 * coverage.
 *
 * <p>Branch enumeration, leg by leg. {@code of}: the null instant leg, then a
 * non-null instant with the hour BELOW the cut-over (the previous day), EQUAL to
 * it (the new day — the boundary that decides which side a ticket closed exactly
 * on the switch falls), and ABOVE it. A cut-over of zero is tested on its own
 * because it is the default and must leave the civil day untouched, including at
 * midnight. {@code startOf} and {@code endOf}: their bounds must MEET without
 * overlapping, which is what guarantees a ticket is counted once.
 */
class AccountingDayTest {

    /**
     * Builds the service with a mocked settings service answering the given
     * cut-over hour.
     *
     * @param cutover the administered cut-over hour
     * @return the service under test
     */
    private AccountingDay service(int cutover) {
        PosSettingsService settings = mock(PosSettingsService.class);
        when(settings.accountingCutoverHour()).thenReturn(cutover);
        AccountingDay accountingDay = new AccountingDay();
        accountingDay.posSettingsService = settings;
        return accountingDay;
    }

    /** A null instant has no accounting day, and does not throw. */
    @Test
    void ofIsNullForANullInstant() {
        Assertions.assertNull(service(0).of(null));
    }

    /** With no cut-over, the accounting day is the civil day — midnight included. */
    @Test
    void withoutCutoverTheAccountingDayIsTheCivilDay() {
        AccountingDay service = service(0);
        Assertions.assertEquals(LocalDate.of(2026, 3, 17),
                service.of(LocalDateTime.of(2026, 3, 17, 0, 0)));
        Assertions.assertEquals(LocalDate.of(2026, 3, 17),
                service.of(LocalDateTime.of(2026, 3, 17, 23, 59, 59)));
    }

    /** Below the cut-over, the sale belongs to the previous accounting day. */
    @Test
    void belowTheCutoverTheSaleBelongsToThePreviousDay() {
        Assertions.assertEquals(LocalDate.of(2026, 3, 16),
                service(2).of(LocalDateTime.of(2026, 3, 17, 1, 30)));
    }

    /** Exactly ON the cut-over, the sale belongs to the new accounting day. */
    @Test
    void onTheCutoverTheSaleBelongsToTheNewDay() {
        Assertions.assertEquals(LocalDate.of(2026, 3, 17),
                service(2).of(LocalDateTime.of(2026, 3, 17, 2, 0)));
    }

    /** Above the cut-over, the sale belongs to the day it happened on. */
    @Test
    void aboveTheCutoverTheSaleBelongsToTheSameDay() {
        Assertions.assertEquals(LocalDate.of(2026, 3, 17),
                service(2).of(LocalDateTime.of(2026, 3, 17, 10, 0)));
    }

    /** The bounds of an accounting day start at the cut-over hour. */
    @Test
    void theDayOpensOnTheCutoverHour() {
        Assertions.assertEquals(LocalDateTime.of(2026, 3, 17, 2, 0),
                service(2).startOf(LocalDate.of(2026, 3, 17)));
    }

    /** The bounds MEET: a day closes exactly where the next one opens. */
    @Test
    void theDayClosesWhereTheNextOneOpens() {
        AccountingDay service = service(2);
        Assertions.assertEquals(service.startOf(LocalDate.of(2026, 3, 18)),
                service.endOf(LocalDate.of(2026, 3, 17)));
    }

    /** With no cut-over the bounds are midnight to midnight. */
    @Test
    void withoutCutoverTheBoundsAreMidnightToMidnight() {
        AccountingDay service = service(0);
        Assertions.assertEquals(LocalDateTime.of(2026, 3, 17, 0, 0),
                service.startOf(LocalDate.of(2026, 3, 17)));
        Assertions.assertEquals(LocalDateTime.of(2026, 3, 18, 0, 0),
                service.endOf(LocalDate.of(2026, 3, 17)));
    }
}
