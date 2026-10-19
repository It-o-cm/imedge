package com.intermarche.pos.domain.setting;

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
        assertEquals(5, PosList.Kind.values().length);
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
}
