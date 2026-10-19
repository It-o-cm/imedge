package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.domain.session.TechnicalEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DashboardIndicator}.
 * <p>
 * The catalog is pure data, so the tests bear on what the rest of the code
 * relies upon: the codes are the ones the parameter documents, they are
 * unique, every accessor answers, the two computed indicators carry NO event
 * type while every other one carries at least one, and {@code byCode} covers
 * its three answers (a match, an unknown code, null) plus the trimming that
 * makes a hand-typed list forgiving.
 */
class DashboardIndicatorTest {

    /** Every declared constant answers its three accessors. */
    @Test
    void everyIndicatorExposesItsMetadata() {
        for (DashboardIndicator indicator : DashboardIndicator.values()) {
            assertTrue(indicator.code() != null && !indicator.code().isBlank(),
                    "code of " + indicator);
            assertTrue(indicator.label() != null && !indicator.label().isBlank(),
                    "label of " + indicator);
            assertTrue(indicator.unit() != null, "unit of " + indicator);
            assertTrue(indicator.eventTypes() != null, "event types of " + indicator);
        }
    }

    /** The catalog holds exactly its eleven declared indicators. */
    @Test
    void catalogHasElevenIndicators() {
        assertEquals(11, DashboardIndicator.values().length);
    }

    /** No two indicators share a code: the parameter must be unambiguous. */
    @Test
    void codesAreUnique() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (DashboardIndicator indicator : DashboardIndicator.values()) {
            assertTrue(seen.add(indicator.code()), "duplicate code " + indicator.code());
        }
    }

    /** The codes are the ones the parameter's help text names, in order. */
    @Test
    void codesAreTheDocumentedOnes() {
        String[] expected = {"endorsements", "endorsements-denied", "age-checks",
                "supervisor-calls", "parked-tickets", "paid-cancelled", "duplicates",
                "price-checks", "training", "cash-movements", "offline-registers"};
        DashboardIndicator[] values = DashboardIndicator.values();
        assertEquals(expected.length, values.length);
        for (int rank = 0; rank < expected.length; rank++) {
            assertEquals(expected[rank], values[rank].code());
        }
    }

    /** The age check counts BOTH outcomes, confirmed and refused. */
    @Test
    void ageCheckCountsBothOutcomes() {
        assertEquals(java.util.List.of(TechnicalEvent.EventType.AGE_CHECK_CONFIRMED,
                        TechnicalEvent.EventType.AGE_CHECK_REFUSED),
                DashboardIndicator.AGE_CHECKS.eventTypes());
    }

    /** The two computed indicators carry no event type at all. */
    @Test
    void computedIndicatorsCarryNoEventType() {
        assertTrue(DashboardIndicator.CASH_MOVEMENTS.eventTypes().isEmpty());
        assertTrue(DashboardIndicator.OFFLINE_REGISTERS.eventTypes().isEmpty());
    }

    /** Every other indicator counts at least one event type. */
    @Test
    void journalIndicatorsCarryAtLeastOneEventType() {
        for (DashboardIndicator indicator : DashboardIndicator.values()) {
            if (indicator == DashboardIndicator.CASH_MOVEMENTS
                    || indicator == DashboardIndicator.OFFLINE_REGISTERS) {
                continue;
            }
            assertTrue(!indicator.eventTypes().isEmpty(), "event types of " + indicator);
        }
    }

    /** The event-type list is unmodifiable: the catalog cannot be rewritten. */
    @Test
    void eventTypesAreUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> DashboardIndicator.ENDORSEMENTS.eventTypes()
                        .add(TechnicalEvent.EventType.TICKET_CLOSED));
    }

    /** Only the amount carries a suffix; a count prints bare. */
    @Test
    void unitsCarryTheirSuffix() {
        assertEquals("", DashboardIndicator.Unit.COUNT.suffix());
        assertEquals("€", DashboardIndicator.Unit.AMOUNT.suffix());
        assertEquals(DashboardIndicator.Unit.AMOUNT, DashboardIndicator.CASH_MOVEMENTS.unit());
        assertEquals(DashboardIndicator.Unit.COUNT, DashboardIndicator.ENDORSEMENTS.unit());
    }

    /** The two declared units are the only ones. */
    @Test
    void unitEnumHasTwoConstants() {
        assertEquals(2, DashboardIndicator.Unit.values().length);
    }

    /** A known code resolves to its indicator. */
    @Test
    void byCodeFindsAKnownCode() {
        assertSame(DashboardIndicator.OFFLINE_REGISTERS,
                DashboardIndicator.byCode("offline-registers"));
    }

    /** Surrounding spaces are tolerated: the list is typed by hand. */
    @Test
    void byCodeTrimsWhatItIsGiven() {
        assertSame(DashboardIndicator.DUPLICATES, DashboardIndicator.byCode("  duplicates  "));
    }

    /** An unknown code resolves to nothing rather than to a default. */
    @Test
    void byCodeReturnsNullOnAnUnknownCode() {
        assertNull(DashboardIndicator.byCode("chiffre-magique"));
    }

    /** A blank code resolves to nothing. */
    @Test
    void byCodeReturnsNullOnABlankCode() {
        assertNull(DashboardIndicator.byCode("   "));
    }

    /** A null code resolves to nothing, before any comparison runs. */
    @Test
    void byCodeReturnsNullOnNull() {
        assertNull(DashboardIndicator.byCode(null));
    }

    /** Every constant round-trips through valueOf under its own name. */
    @Test
    void valueOfRoundTrips() {
        for (DashboardIndicator indicator : DashboardIndicator.values()) {
            assertSame(indicator, DashboardIndicator.valueOf(indicator.name()));
        }
    }
}
