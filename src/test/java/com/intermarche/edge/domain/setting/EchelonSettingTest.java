package com.intermarche.edge.domain.setting;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EchelonSetting} and {@link EchelonLevel}.
 * <p>
 * {@link EchelonSetting#isProgrammed} and {@link EchelonSetting#valueAsOf}
 * are covered on every arm of their date guard; {@link EchelonSetting#getChecksum} is
 * exercised on rows differing by one field each so the hash is proven
 * field-sensitive, and the level ordering is pinned because the inheritance
 * relies on it. Static finders carry no branches.
 */
class EchelonSettingTest {

    /**
     * {@link EchelonSetting#getChecksum} is stable for identical fields and
     * changes when any business field changes.
     */
    @Test
    void checksumIsFieldSensitive() {
        EchelonSetting base = setting(EchelonLevel.ENSEIGNE, "IF", "display.show-ean", "true");
        assertEquals(base.getChecksum(), setting(EchelonLevel.ENSEIGNE, "IF", "display.show-ean", "true").getChecksum());
        assertNotEquals(base.getChecksum(), setting(EchelonLevel.PDV, "IF", "display.show-ean", "true").getChecksum());
        assertNotEquals(base.getChecksum(), setting(EchelonLevel.ENSEIGNE, "NF", "display.show-ean", "true").getChecksum());
        assertNotEquals(base.getChecksum(), setting(EchelonLevel.ENSEIGNE, "IF", "scan.ean13-check-digit", "true").getChecksum());
        assertNotEquals(base.getChecksum(), setting(EchelonLevel.ENSEIGNE, "IF", "display.show-ean", "false").getChecksum());
        EchelonSetting dated = setting(EchelonLevel.ENSEIGNE, "IF", "display.show-ean", "true");
        dated.effectiveDate = java.time.LocalDate.of(2026, 3, 1);
        assertNotEquals(base.getChecksum(), dated.getChecksum());
        EchelonSetting pending = setting(EchelonLevel.ENSEIGNE, "IF", "display.show-ean", "true");
        pending.pendingValue = "false";
        assertNotEquals(base.getChecksum(), pending.getChecksum());
    }

    /**
     * A row without a date programs nothing; a row with a date programs a
     * change (both arms of {@link EchelonSetting#isProgrammed}).
     */
    @Test
    void isProgrammedFollowsTheDate() {
        EchelonSetting row = setting(EchelonLevel.PDV, "12345", "k", "v");
        assertFalse(row.isProgrammed());
        row.effectiveDate = java.time.LocalDate.of(2026, 11, 1);
        assertTrue(row.isProgrammed());
    }

    /**
     * {@link EchelonSetting#valueAsOf} gives the current value when nothing is
     * programmed, the current value before the date, the pending value on the
     * date and after it — every arm of the date guard.
     */
    @Test
    void valueAsOfKeepsTheCurrentValueUntilTheDate() {
        EchelonSetting row = setting(EchelonLevel.PDV, "12345", "k", "current");
        java.time.LocalDate date = java.time.LocalDate.of(2026, 11, 1);
        assertEquals("current", row.valueAsOf(date));
        row.pendingValue = "pending";
        row.effectiveDate = date;
        assertEquals("current", row.valueAsOf(date.minusDays(1)));
        assertEquals("pending", row.valueAsOf(date));
        assertEquals("pending", row.valueAsOf(date.plusDays(1)));
    }

    /**
     * The two date finders delegate to Panache with their query: the due rows
     * up to a day, the programmed rows after it, nearest date first.
     */
    @Test
    void dateFindersDelegateToPanache() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 10, 25);
        List<EchelonSetting> due = List.of(setting(EchelonLevel.PDV, "12345", "k", "v"));
        List<EchelonSetting> programmed = List.of(setting(EchelonLevel.PDV, "12345", "k2", "v"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> EchelonSetting.list("effectiveDate <= ?1", day)).thenReturn(due);
            mocked.when(() -> EchelonSetting.list(
                    "effectiveDate > ?1 order by effectiveDate, echelonCode, settingKey", day)).thenReturn(programmed);
            assertSame(due, EchelonSetting.listDue(day));
            assertSame(programmed, EchelonSetting.listProgrammed(day));
        }
    }

    /**
     * The declaration order encodes the inheritance ranking the resolver
     * relies on: COUNTRY is the least specific, PDV the most specific.
     */
    @Test
    void levelOrderingIsBottomUp() {
        assertEquals(0, EchelonLevel.COUNTRY.ordinal());
        assertEquals(1, EchelonLevel.ENSEIGNE.ordinal());
        assertEquals(2, EchelonLevel.PDV.ordinal());
        assertEquals(EchelonLevel.PDV, EchelonLevel.valueOf("PDV"));
    }

    /**
     * Builds an echelon setting with the given business fields.
     *
     * @param level the echelon level
     * @param code the echelon code
     * @param key the catalog key
     * @param value the value
     * @return the built echelon setting
     */
    private EchelonSetting setting(EchelonLevel level, String code, String key, String value) {
        EchelonSetting setting = new EchelonSetting();
        setting.level = level;
        setting.echelonCode = code;
        setting.settingKey = key;
        setting.settingValue = value;
        return setting;
    }

    /**
     * The three finders delegate to Panache with their query and hand back
     * what the store answers: {@code findValue} the first row of one echelon
     * and key, {@code listForEchelon} the rows of one echelon ordered by key,
     * {@code listForKeyAtLevel} the rows of one key at a level ordered by
     * echelon.
     */
    @Test
    @SuppressWarnings("unchecked")
    void findersDelegateToPanache() {
        EchelonSetting row = setting(EchelonLevel.ENSEIGNE, "ITM", "discount.enabled", "false");
        PanacheQuery<EchelonSetting> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        List<EchelonSetting> ofEchelon = List.of(row);
        List<EchelonSetting> ofKey = List.of(row, row);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> EchelonSetting.find("level = ?1 and echelonCode = ?2 and settingKey = ?3",
                    EchelonLevel.ENSEIGNE, "ITM", "discount.enabled")).thenReturn(query);
            mocked.when(() -> EchelonSetting.list("level = ?1 and echelonCode = ?2 order by settingKey",
                    EchelonLevel.ENSEIGNE, "ITM")).thenReturn(ofEchelon);
            mocked.when(() -> EchelonSetting.list("level = ?1 and settingKey = ?2 order by echelonCode",
                    EchelonLevel.ENSEIGNE, "discount.enabled")).thenReturn(ofKey);
            assertSame(row, EchelonSetting.findValue(EchelonLevel.ENSEIGNE, "ITM", "discount.enabled"));
            assertSame(ofEchelon, EchelonSetting.listForEchelon(EchelonLevel.ENSEIGNE, "ITM"));
            assertSame(ofKey, EchelonSetting.listForKeyAtLevel(EchelonLevel.ENSEIGNE, "discount.enabled"));
        }
    }
}
