package com.intermarche.edge.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mockStatic;

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

    /**
     * {@code listAllOrdered} delegates to Panache ordered by code and hands
     * back what the store answers.
     */
    @Test
    void listAllOrderedDelegatesToPanache() {
        List<Enseigne> all = List.of(new Enseigne());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Enseigne.list("order by code")).thenReturn(all);
            assertSame(all, Enseigne.listAllOrdered());
        }
    }
}
