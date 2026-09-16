package com.intermarche.pos.domain.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for {@link Enseigne}.
 * <p>
 * The entity carries no branching logic; {@link Enseigne#getChecksum} is the
 * only behaviour to pin, exercised on rows differing by one field each so the
 * hash is proven field-sensitive. Static finders carry no branches.
 */
class EnseigneTest {

    /**
     * {@link Enseigne#getChecksum} is stable for identical fields and changes
     * when any business field changes, including the nullable country and
     * language.
     */
    @Test
    void checksumIsFieldSensitive() {
        Enseigne base = enseigne("IF", "Intermarché France", "FR", "fr");
        assertEquals(base.getChecksum(), enseigne("IF", "Intermarché France", "FR", "fr").getChecksum());
        assertNotEquals(base.getChecksum(), enseigne("NF", "Intermarché France", "FR", "fr").getChecksum());
        assertNotEquals(base.getChecksum(), enseigne("IF", "Netto France", "FR", "fr").getChecksum());
        assertNotEquals(base.getChecksum(), enseigne("IF", "Intermarché France", null, "fr").getChecksum());
        assertNotEquals(base.getChecksum(), enseigne("IF", "Intermarché France", "FR", null).getChecksum());
    }

    /**
     * Builds an enseigne with the given business fields.
     *
     * @param code the enseigne code
     * @param name the display name
     * @param countryCode the country code, possibly null
     * @param language the default language, possibly null
     * @return the built enseigne
     */
    private Enseigne enseigne(String code, String name, String countryCode, String language) {
        Enseigne enseigne = new Enseigne();
        enseigne.code = code;
        enseigne.name = name;
        enseigne.countryCode = countryCode;
        enseigne.defaultLanguage = language;
        return enseigne;
    }
}
