package com.intermarche.pos.domain.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for {@link Country}.
 * <p>
 * The entity carries no branching logic; {@link Country#getChecksum} is the
 * only behaviour to pin, exercised on rows differing by one field each so the
 * hash is proven field-sensitive. Static finders carry no branches.
 */
class CountryTest {

    /**
     * {@link Country#getChecksum} is stable for identical fields and changes
     * when any business field changes, including the nullable language.
     */
    @Test
    void checksumIsFieldSensitive() {
        Country base = country("FR", "France", "fr");
        assertEquals(base.getChecksum(), country("FR", "France", "fr").getChecksum());
        assertNotEquals(base.getChecksum(), country("BE", "France", "fr").getChecksum());
        assertNotEquals(base.getChecksum(), country("FR", "Belgique", "fr").getChecksum());
        assertNotEquals(base.getChecksum(), country("FR", "France", null).getChecksum());
    }

    /**
     * Builds a country with the given business fields.
     *
     * @param code the country code
     * @param name the display name
     * @param language the default language, possibly null
     * @return the built country
     */
    private Country country(String code, String name, String language) {
        Country country = new Country();
        country.code = code;
        country.name = name;
        country.defaultLanguage = language;
        return country;
    }
}
