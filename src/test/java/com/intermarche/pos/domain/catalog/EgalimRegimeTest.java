package com.intermarche.pos.domain.catalog;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EgalimRegime}.
 * <p>
 * The entity is an active record over Panache statics: {@code find} and
 * {@code list} resolve to {@link PanacheEntityBase} under plain {@code mvn
 * test} and are intercepted with {@code mockStatic}. No database, no Quarkus
 * boot.
 * <p>
 * Branch enumeration, every arm exercised: {@code findByCode} the null arm and
 * the lookup arm; {@code getLabel} the three legs of its guard — label null,
 * label blank, label set; {@code isCapped} both arms; {@code getCapFormatted}
 * the uncapped arm and the formatting arm. 8 branches, 8 covered.
 */
class EgalimRegimeTest {

    /**
     * Builds a regime carrying its three values.
     *
     * @param code the code
     * @param label the label, possibly null
     * @param capRate the ceiling as a fraction, or null for uncapped
     * @return the regime
     */
    private EgalimRegime regime(String code, String label, String capRate) {
        return new EgalimRegime(code, label, capRate == null ? null : new BigDecimal(capRate));
    }

    /**
     * The default constructor leaves every field unset, as JPA expects.
     */
    @Test
    void defaultConstructorLeavesEveryFieldUnset() {
        EgalimRegime regime = new EgalimRegime();
        assertNull(regime.code);
        assertNull(regime.label);
        assertNull(regime.capRate);
    }

    /**
     * The three-argument constructor carries its values verbatim.
     */
    @Test
    void constructorCarriesItsValues() {
        EgalimRegime regime = regime("FOOD", "Alimentaire", "0.3400");
        assertEquals("FOOD", regime.code);
        assertEquals("Alimentaire", regime.label);
        assertEquals(new BigDecimal("0.3400"), regime.capRate);
    }

    /**
     * A null code finds nothing and never reaches the database — the guard's
     * true arm.
     */
    @Test
    void findByCodeAnswersNullOnANullCode() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertNull(EgalimRegime.findByCode(null));
            panache.verifyNoInteractions();
        }
    }

    /**
     * A code is looked up on the code column — the guard's false arm.
     */
    @Test
    void findByCodeLooksUpTheCodeColumn() {
        EgalimRegime food = regime("FOOD", "Alimentaire", "0.3400");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<EgalimRegime> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(food);
            panache.when(() -> EgalimRegime.find("code", "FOOD")).thenReturn(query);
            assertSame(food, EgalimRegime.findByCode("FOOD"));
        }
    }

    /**
     * The listing is ordered by code, which is what the screen renders.
     */
    @Test
    void listAllOrderedOrdersByCode() {
        EgalimRegime dph = regime("DPH", "Droguerie", "0.4000");
        EgalimRegime food = regime("FOOD", "Alimentaire", "0.3400");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EgalimRegime.list("order by code")).thenReturn(List.of(dph, food));
            List<EgalimRegime> listed = EgalimRegime.listAllOrdered();
            assertEquals(2, listed.size());
            assertSame(dph, listed.get(0));
            assertSame(food, listed.get(1));
        }
    }

    /**
     * A regime with a label reads by its label — the fall-through of the
     * guard.
     */
    @Test
    void labelIsTheLabelWhenOneIsSet() {
        assertEquals("Alimentaire", regime("FOOD", "Alimentaire", "0.3400").getLabel());
    }

    /**
     * A regime without a label falls back on its code — the null leg.
     */
    @Test
    void labelFallsBackOnTheCodeWhenNull() {
        assertEquals("FOOD", regime("FOOD", null, "0.3400").getLabel());
    }

    /**
     * A blank label is no label — the blank leg, which a null check alone
     * would let through and which would render an empty cell.
     */
    @Test
    void labelFallsBackOnTheCodeWhenBlank() {
        assertEquals("FOOD", regime("FOOD", "   ", "0.3400").getLabel());
    }

    /**
     * A regime carrying a ceiling is capped — the true arm.
     */
    @Test
    void aRegimeWithACeilingIsCapped() {
        assertTrue(regime("FOOD", "Alimentaire", "0.3400").isCapped());
    }

    /**
     * A regime without a ceiling is NOT capped, and that is the exempt state —
     * the false arm. Zero would be a ceiling of zero, the exact contrary.
     */
    @Test
    void aRegimeWithoutACeilingIsNotCapped() {
        assertFalse(regime("EXEMPT", "Exempté", null).isCapped());
        assertTrue(regime("NONE", "Aucune générosité", "0.0000").isCapped());
    }

    /**
     * The ceiling is rendered as a percentage, comma-separated, with its
     * needless decimals dropped.
     */
    @Test
    void capIsFormattedAsAPercentage() {
        assertEquals("34", regime("FOOD", null, "0.3400").getCapFormatted());
        assertEquals("40", regime("DPH", null, "0.4000").getCapFormatted());
        assertEquals("33,5", regime("ODD", null, "0.3350").getCapFormatted());
    }

    /**
     * An uncapped regime formats to an empty string rather than to a zero — a
     * screen printing "0 %" would state a rule nobody wrote.
     */
    @Test
    void anUncappedRegimeFormatsToNothing() {
        assertEquals("", regime("EXEMPT", null, null).getCapFormatted());
    }

    /**
     * The fingerprint hashes the three values that make the regime, so a
     * referential export moves when any of them moves.
     */
    @Test
    void checksumHashesCodeLabelAndCap() {
        EgalimRegime regime = regime("FOOD", "Alimentaire", "0.3400");
        assertEquals(Objects.hash("FOOD", "Alimentaire", new BigDecimal("0.3400")),
                regime.getChecksum());
        EgalimRegime moved = regime("FOOD", "Alimentaire", "0.4000");
        assertFalse(regime.getChecksum() == moved.getChecksum());
    }
}
