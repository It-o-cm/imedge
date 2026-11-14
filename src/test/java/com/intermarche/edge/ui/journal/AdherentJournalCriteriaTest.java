package com.intermarche.edge.ui.journal;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link AdherentJournalCriteria}. */
class AdherentJournalCriteriaTest {

    /**
     * Builds query parameters from name/value pairs.
     *
     * @param pairs the names and values, alternated
     * @return the parameters
     */
    private MultivaluedMap<String, String> params(String... pairs) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.add(pairs[i], pairs[i + 1]);
        }
        return params;
    }

    /** BO-02-05-05: every criterion is read, trimmed and typed, and reproduced for the pager. */
    @Test
    void everyCriterionIsReadAndReproduced() {
        AdherentJournalCriteria criteria = AdherentJournalCriteria.from(params("adherent", " AD1 ", "country", "FRA",
                "enseigne", "IF", "pdv", "10001", "dateFrom", "2026-10-01", "dateTo", "2026-10-25",
                "terminal", "POS01", "ticket", "0002", "amountMin", "1,5", "amountMax", "40", "kind", "CANCELLED",
                "page", "3"));
        assertEquals("AD1", criteria.adherent);
        assertEquals("FRA", criteria.country);
        assertEquals("IF", criteria.enseigne);
        assertEquals("10001", criteria.pdv);
        assertEquals(LocalDate.of(2026, 10, 1), criteria.dateFrom);
        assertEquals(LocalDate.of(2026, 10, 25), criteria.dateTo);
        assertEquals("POS01", criteria.terminal);
        assertEquals("0002", criteria.ticket);
        assertEquals(new BigDecimal("1.5"), criteria.amountMin);
        assertEquals(new BigDecimal("40"), criteria.amountMax);
        assertEquals("CANCELLED", criteria.kind);
        assertEquals(3, criteria.page);
        assertEquals("adherent=AD1&country=FRA&enseigne=IF&pdv=10001&dateFrom=2026-10-01&dateTo=2026-10-25"
                + "&terminal=POS01&ticket=0002&amountMin=1.5&amountMax=40&kind=CANCELLED&", criteria.query());
    }

    /** A blank, missing or unreadable criterion filters nothing. */
    @Test
    void badCriteriaAreAbsent() {
        AdherentJournalCriteria criteria = AdherentJournalCriteria.from(params("adherent", " ", "dateFrom", "hier",
                "amountMin", "beaucoup", "kind", "OTHER", "page", "x"));
        assertNull(criteria.adherent);
        assertNull(criteria.dateFrom);
        assertNull(criteria.dateTo);
        assertNull(criteria.amountMin);
        assertNull(criteria.amountMax);
        assertNull(criteria.kind);
        assertEquals(1, criteria.page);
        assertEquals("", criteria.query());
        assertNull(AdherentJournalCriteria.from(params()).kind);
        assertEquals(1, AdherentJournalCriteria.from(params()).page);
    }

    /** Each echelon filter alone narrows the points of sale read. */
    @Test
    void eachEchelonFilterNarrows() {
        assertFalse(new AdherentJournalCriteria().narrowsEchelons());
        AdherentJournalCriteria adherent = new AdherentJournalCriteria();
        adherent.adherent = "AD1";
        assertTrue(adherent.narrowsEchelons());
        AdherentJournalCriteria country = new AdherentJournalCriteria();
        country.country = "FRA";
        assertTrue(country.narrowsEchelons());
        AdherentJournalCriteria enseigne = new AdherentJournalCriteria();
        enseigne.enseigne = "IF";
        assertTrue(enseigne.narrowsEchelons());
        AdherentJournalCriteria pdv = new AdherentJournalCriteria();
        pdv.pdv = "10001";
        assertTrue(pdv.narrowsEchelons());
    }
}
