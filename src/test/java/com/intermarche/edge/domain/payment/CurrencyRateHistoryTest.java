package com.intermarche.edge.domain.payment;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link CurrencyRateHistory} (BO-03-02-40).
 *
 * <p>Plain JUnit 5 + Mockito, no Quarkus and no database: the entity is
 * constructed through {@link org.mockito.Mockito#mockConstruction} so the
 * {@code persist()} of the written row can be verified, and the inherited
 * Panache finder is stubbed on {@link PanacheEntityBase}.
 *
 * <p>Every leg of the guard of {@code record} is exercised — a null next rate,
 * an unchanged rate, an unchanged rate written with another scale, a first
 * record with no previous rate, and a real change.
 */
class CurrencyRateHistoryTest {

    /** The franc rate, the one the questionnaire names. */
    private static final BigDecimal FRANC = new BigDecimal("6.55957");

    /**
     * A null next rate records nothing: there is no change to keep.
     */
    @Test
    void recordIgnoresANullRate() {
        try (MockedConstruction<CurrencyRateHistory> created =
                mockConstruction(CurrencyRateHistory.class)) {
            CurrencyRateHistory.record("FRF", FRANC, null, "DURAND");
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * Saving a currency without touching its rate records nothing.
     */
    @Test
    void recordIgnoresAnUnchangedRate() {
        try (MockedConstruction<CurrencyRateHistory> created =
                mockConstruction(CurrencyRateHistory.class)) {
            CurrencyRateHistory.record("FRF", FRANC, new BigDecimal("6.55957"), "DURAND");
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * The same rate written with another scale is still the same rate: the
     * comparison is on the value, not on the text.
     */
    @Test
    void recordIgnoresTheSameRateWrittenWithAnotherScale() {
        try (MockedConstruction<CurrencyRateHistory> created =
                mockConstruction(CurrencyRateHistory.class)) {
            CurrencyRateHistory.record("CHF", new BigDecimal("0.95"),
                    new BigDecimal("0.9500"), "DURAND");
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * A first rate, with no previous one, is recorded with a null predecessor.
     */
    @Test
    void recordKeepsAFirstRate() {
        try (MockedConstruction<CurrencyRateHistory> created =
                mockConstruction(CurrencyRateHistory.class)) {
            CurrencyRateHistory.record("FRF", null, FRANC, "DURAND");
            assertEquals(1, created.constructed().size());
            CurrencyRateHistory entry = created.constructed().get(0);
            assertEquals("FRF", entry.currencyCode);
            assertNull(entry.previousEuroPerUnit);
            assertEquals(FRANC, entry.euroPerUnit);
            assertEquals("DURAND", entry.changedBy);
            verify(entry, times(1)).persist();
        }
    }

    /**
     * A real change is recorded with both rates, so the row says what it
     * replaced and not only what it is.
     */
    @Test
    void recordKeepsARealChange() {
        try (MockedConstruction<CurrencyRateHistory> created =
                mockConstruction(CurrencyRateHistory.class)) {
            CurrencyRateHistory.record("CHF", new BigDecimal("0.95"),
                    new BigDecimal("0.97"), null);
            assertEquals(1, created.constructed().size());
            CurrencyRateHistory entry = created.constructed().get(0);
            assertEquals("CHF", entry.currencyCode);
            assertEquals(new BigDecimal("0.95"), entry.previousEuroPerUnit);
            assertEquals(new BigDecimal("0.97"), entry.euroPerUnit);
            assertNull(entry.changedBy);
            verify(entry, times(1)).persist();
        }
    }

    /**
     * {@code listFor} runs the newest-first query of one currency and returns
     * its result verbatim.
     */
    @Test
    void listForRunsTheNewestFirstQuery() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            List<CurrencyRateHistory> expected = List.of(new CurrencyRateHistory());
            panache.when(() -> CurrencyRateHistory.list(
                            "currencyCode = ?1 order by changedAt desc", "FRF"))
                    .thenReturn(expected);
            assertSame(expected, CurrencyRateHistory.listFor("FRF"));
        }
    }

    /**
     * A row stamps itself on construction: the moment of the change is not left
     * to the caller.
     */
    @Test
    void aRowStampsItsOwnMoment() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        CurrencyRateHistory entry = new CurrencyRateHistory();
        assertNotNull(entry.changedAt);
        assertTrue(entry.changedAt.isAfter(before));
    }
}
