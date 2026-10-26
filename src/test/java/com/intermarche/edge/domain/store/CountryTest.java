package com.intermarche.edge.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

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

    /**
     * The two finders delegate to Panache with their query and hand back
     * what the store answers: {@code findByCode} the first match of the
     * code, {@code listAllOrdered} every row ordered by code.
     */
    @Test
    @SuppressWarnings("unchecked")
    void findersDelegateToPanache() {
        Country france = country("FR", "France", "fr");
        PanacheQuery<Country> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(france);
        List<Country> all = List.of(france);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Country.find("code", "FR")).thenReturn(query);
            mocked.when(() -> Country.list("order by code")).thenReturn(all);
            assertSame(france, Country.findByCode("FR"));
            assertSame(all, Country.listAllOrdered());
        }
    }
}
