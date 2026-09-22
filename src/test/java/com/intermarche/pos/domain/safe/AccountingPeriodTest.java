package com.intermarche.pos.domain.safe;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AccountingPeriod}.
 *
 * <p>The period is what turns the safe into an account. Its two finders are
 * exercised through {@code PanacheEntityBase} — the entities are not enhanced
 * under a plain unit test — and {@code covers} is asserted on every leg of its
 * compound guard: null day, null start, before the start, after the end, and the
 * open-ended case where there is no end at all.
 */
class AccountingPeriodTest {

    /**
     * Builds a period over a stretch of days.
     *
     * @param start the first day
     * @param end the last day, null for an open-ended period
     * @return the period
     */
    private AccountingPeriod period(LocalDate start, LocalDate end) {
        AccountingPeriod period = new AccountingPeriod();
        period.label = "2026-09";
        period.startDate = start;
        period.endDate = end;
        return period;
    }

    /**
     * A period opens OPEN, with no closing balance and nobody having closed it.
     */
    @Test
    void aperiodOpensOpen() {
        AccountingPeriod period = period(LocalDate.of(2026, 9, 1), null);
        assertTrue(period.isOpen());
        assertEquals(AccountingPeriod.PeriodStatus.OPEN, period.status);
        assertEquals(BigDecimal.ZERO, period.openingBalance);
        assertNull(period.closingBalance);
        assertNull(period.closedAt);
        assertNull(period.closedBy);
    }

    /**
     * A closed period is no longer open.
     */
    @Test
    void aclosedPeriodIsNoLongerOpen() {
        AccountingPeriod period = period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        period.status = AccountingPeriod.PeriodStatus.CLOSED;
        assertFalse(period.isOpen());
    }

    /**
     * A day inside the stretch is covered, on both of its boundaries.
     */
    @Test
    void adayInsideTheStretchIsCovered() {
        AccountingPeriod period = period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertTrue(period.covers(LocalDate.of(2026, 9, 1)));
        assertTrue(period.covers(LocalDate.of(2026, 9, 15)));
        assertTrue(period.covers(LocalDate.of(2026, 9, 30)));
    }

    /**
     * A day before the start or after the end is not — the two refusing legs.
     */
    @Test
    void adayOutsideTheStretchIsNotCovered() {
        AccountingPeriod period = period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertFalse(period.covers(LocalDate.of(2026, 8, 31)));
        assertFalse(period.covers(LocalDate.of(2026, 10, 1)));
    }

    /**
     * An OPEN-ENDED period covers everything from its start on — the null arm of
     * the end guard.
     */
    @Test
    void anOpenEndedPeriodCoversEverythingFromItsStart() {
        AccountingPeriod period = period(LocalDate.of(2026, 9, 1), null);
        assertTrue(period.covers(LocalDate.of(2027, 1, 1)));
        assertFalse(period.covers(LocalDate.of(2026, 8, 31)));
    }

    /**
     * A null day and a period with no start cover nothing — the two legs of the
     * first guard, each alone.
     */
    @Test
    void anAbsentDayOrStartCoversNothing() {
        assertFalse(period(LocalDate.of(2026, 9, 1), null).covers(null));
        assertFalse(period(null, LocalDate.of(2026, 9, 30)).covers(LocalDate.of(2026, 9, 15)));
    }

    /**
     * The open finder asks for the OPEN status, newest start first.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theOpenFinderAsksForTheOpenStatus() {
        AccountingPeriod open = period(LocalDate.of(2026, 9, 1), null);
        PanacheQuery<AccountingPeriod> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(open);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountingPeriod.find("status = ?1 order by startDate desc",
                    AccountingPeriod.PeriodStatus.OPEN)).thenReturn(query);
            assertSame(open, AccountingPeriod.open());
        }
    }

    /**
     * The open finder answers null when the safe has never been opened.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theOpenFinderAnswersNullWhenNothingIsOpen() {
        PanacheQuery<AccountingPeriod> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountingPeriod.find("status = ?1 order by startDate desc",
                    AccountingPeriod.PeriodStatus.OPEN)).thenReturn(query);
            assertNull(AccountingPeriod.open());
        }
    }

    /**
     * The history finder asks for every period, newest first.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theHistoryFinderAsksForEveryPeriodNewestFirst() {
        AccountingPeriod first = period(LocalDate.of(2026, 9, 1), null);
        PanacheQuery<AccountingPeriod> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(first));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountingPeriod.find("order by startDate desc")).thenReturn(query);
            assertEquals(List.of(first), AccountingPeriod.listNewestFirst());
        }
    }

    /**
     * The checksum hashes the identifying and financial fields.
     */
    @Test
    void thechecksumHashesTheSalientFields() {
        AccountingPeriod period = period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        period.openingBalance = new BigDecimal("100.00");
        period.closingBalance = new BigDecimal("250.00");
        period.status = AccountingPeriod.PeriodStatus.CLOSED;
        period.closedAt = java.time.LocalDateTime.of(2026, 10, 1, 9, 0);
        int expected = Objects.hash("2026-09", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                AccountingPeriod.PeriodStatus.CLOSED, new BigDecimal("100.00"),
                new BigDecimal("250.00"), java.time.LocalDateTime.of(2026, 10, 1, 9, 0));
        assertEquals(expected, period.getChecksum());
    }
}
