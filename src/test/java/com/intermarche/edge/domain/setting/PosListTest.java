package com.intermarche.edge.domain.setting;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PosList} and {@link PosListItem}, the administered
 * lists ({@code BO-04-02-07}, {@code BO-04-03-12}).
 *
 * <p>Only the pure parts are exercised here — the finders talk to the
 * database and are covered where they are consumed. Branch enumeration (both
 * arms): {@code servesFunction} on a null list of functions, a blank one, a
 * null and a blank function, a matching entry whatever its case and spacing,
 * and a non-matching one; {@code findByCode} on null and blank, its two
 * early returns; the kind labels; and the checksums, which must move with
 * the business fields.
 */
class PosListTest {

    /**
     * Builds a list carrying a semicolon-separated function binding.
     *
     * @param functions the binding, possibly null
     * @return the list
     */
    private PosList list(String functions) {
        PosList posList = new PosList();
        posList.code = "L";
        posList.label = "Liste";
        posList.kind = PosList.Kind.JUSTIFICATION;
        posList.functions = functions;
        return posList;
    }

    /**
     * BO-04-03-12: a list naming no function serves them all, including a
     * caller asking about nothing in particular.
     */
    @Test
    void aListWithoutFunctionsServesThemAll() {
        assertTrue(list(null).servesFunction("EXPENSE"));
        assertTrue(list("   ").servesFunction("EXPENSE"));
        assertTrue(list(null).servesFunction(null));
    }

    /**
     * BO-04-03-12: a bound list serves the functions it names, case and
     * spacing aside, and nothing else — a caller naming no function included.
     */
    @Test
    void aBoundListServesWhatItNames() {
        PosList bound = list("EXPENSE; WITHDRAWAL ");
        assertTrue(bound.servesFunction("EXPENSE"));
        assertTrue(bound.servesFunction(" withdrawal "));
        assertFalse(bound.servesFunction("DEPOSIT"));
        assertFalse(bound.servesFunction(null));
        assertFalse(bound.servesFunction("  "));
    }

    /**
     * A code nobody typed is looked up nowhere: the finder answers null
     * without touching the database.
     */
    @Test
    void findByCodeAnswersNullForNothing() {
        assertNull(PosList.findByCode(null));
        assertNull(PosList.findByCode("   "));
    }

    /**
     * Every kind names itself for the administration screen.
     */
    @Test
    void everyKindCarriesAScreenLabel() {
        for (PosList.Kind kind : PosList.Kind.values()) {
            assertFalse(kind.getLabel() == null || kind.getLabel().isBlank());
        }
        assertEquals("Demandes de service", PosList.Kind.SERVICE_REQUEST.getLabel());
        assertEquals("Motifs de retour", PosList.Kind.RETURN_REASON.getLabel());
        assertEquals("Remises et rabais", PosList.Kind.DISCOUNT.getLabel());
        assertEquals(6, PosList.Kind.values().length);
    }

    /**
     * The checksum of a list moves with its business fields — the base
     * entity's change detection is only worth its hash.
     */
    @Test
    void theListChecksumFollowsItsFields() {
        PosList first = list("EXPENSE");
        PosList second = list("EXPENSE");
        assertEquals(first.getChecksum(), second.getChecksum());
        second.active = !second.active;
        assertNotEquals(first.getChecksum(), second.getChecksum());
    }

    /**
     * The checksum of an entry moves with its business fields, the weight and
     * the option markers included.
     */
    @Test
    void theEntryChecksumFollowsItsFields() {
        PosListItem first = new PosListItem();
        first.code = "BQS";
        first.label = "Barquette S";
        first.displayOrder = 10;
        first.severity = 1;
        first.numericValue = new BigDecimal("0.035");
        first.options = "TICKET";
        PosListItem second = new PosListItem();
        second.code = "BQS";
        second.label = "Barquette S";
        second.displayOrder = 10;
        second.severity = 1;
        second.numericValue = new BigDecimal("0.035");
        second.options = "TICKET";
        assertEquals(first.getChecksum(), second.getChecksum());
        second.numericValue = new BigDecimal("0.040");
        assertNotEquals(first.getChecksum(), second.getChecksum());
    }

    /** BO-03: nullable/blank lookup keys never query; a trimmed key returns the matching referential row. */
    @org.junit.jupiter.api.Test
    void coverageNullableBlankAndTrimmedCode() {
        org.junit.jupiter.api.Assertions.assertNull(PosList.findByCode(null));
        org.junit.jupiter.api.Assertions.assertNull(PosList.findByCode(" "));
        PosList row = new PosList();
        var query = org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        org.mockito.Mockito.when(query.firstResult()).thenReturn(row);
        try (var panache = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.find("code", "KEY")).thenReturn(query);
            org.junit.jupiter.api.Assertions.assertSame(row, PosList.findByCode(" KEY "));
        }
    }

    /** BO-04-03: unscoped lists serve all functions; scoped lists require a nonblank matching function. */
    @org.junit.jupiter.api.Test
    void coverageFunctionScopeLegs() {
        PosList list = new PosList();
        list.functions = null;
        org.junit.jupiter.api.Assertions.assertTrue(list.servesFunction(null));
        list.functions = " ";
        org.junit.jupiter.api.Assertions.assertTrue(list.servesFunction("EXPENSE"));
        list.functions = "RETURN; EXPENSE";
        org.junit.jupiter.api.Assertions.assertFalse(list.servesFunction(null));
        org.junit.jupiter.api.Assertions.assertFalse(list.servesFunction(" "));
        org.junit.jupiter.api.Assertions.assertTrue(list.servesFunction(" expense "));
        org.junit.jupiter.api.Assertions.assertFalse(list.servesFunction("OTHER"));
    }

    /** BO-03/08: the finder selects the required business scope and ordering, preserving the returned rows. */
    @org.junit.jupiter.api.Test
    void findActiveByKindUsesItsBusinessScopeAndOrder() {
        var row = new PosList();
        java.util.List<PosList> rows = java.util.List.of(row);
        try (var panache = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list("kind = ?1 and active = true order by code", PosList.Kind.values()[0])).thenReturn(rows);
            org.junit.jupiter.api.Assertions.assertSame(rows, PosList.findActiveByKind(PosList.Kind.values()[0]));
            panache.verify(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list("kind = ?1 and active = true order by code", PosList.Kind.values()[0]));
        }
    }

    /** BO-08-01-04: each synchronized business-field change invalidates the row checksum. */
    @org.junit.jupiter.api.Test
    void everyBusinessFieldChangeInvalidatesTheChecksum() {
        var row = new PosList();
        int previous = row.getChecksum();
        row.code = "CANCEL";
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "code must participate in synchronization");
        previous = row.getChecksum();
        row.label = "Reasons";
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "label must participate in synchronization");
        previous = row.getChecksum();
        row.kind = PosList.Kind.values()[0];
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "kind must participate in synchronization");
        previous = row.getChecksum();
        row.functions = "CANCEL";
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "functions must participate in synchronization");
        previous = row.getChecksum();
        row.active = false;
        org.junit.jupiter.api.Assertions.assertNotEquals(previous, row.getChecksum(), "active must participate in synchronization");
        previous = row.getChecksum();
    }
}
