package com.intermarche.pos.domain.setting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for {@link EchelonSetting} and {@link EchelonLevel}.
 * <p>
 * Neither carries branching logic; {@link EchelonSetting#getChecksum} is
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
}
