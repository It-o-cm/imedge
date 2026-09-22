package com.intermarche.pos.domain.catalog;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VatRate}.
 * <p>
 * The entity is a Panache active record: its two finders resolve to
 * {@link PanacheEntityBase} under plain {@code mvn test} and are intercepted
 * with {@link org.mockito.Mockito#mockStatic} inside a try-with-resources
 * block. No database and no Quarkus context is booted. Every branch is
 * covered with both arms: the null guard of {@code findByNumber}, the three
 * arms of the {@code getLabel} disjunction (null label, blank label, real
 * label) and both arms of the {@code getRateFormatted} null guard.
 */
class VatRateTest {

    /**
     * Builds a regime carrying the three given values.
     *
     * @param number the regime number
     * @param rate the rate as a fraction, possibly null
     * @param label the label, possibly null
     * @return the regime
     */
    private VatRate regime(Integer number, String rate, String label) {
        return new VatRate(number, rate == null ? null : new BigDecimal(rate), label);
    }

    /**
     * The three-argument constructor assigns the three fields.
     */
    @Test
    void constructorAssignsTheThreeFields() {
        VatRate rate = regime(2, "0.0550", "Taux réduit");
        assertEquals(2, rate.number);
        assertEquals(new BigDecimal("0.0550"), rate.rate);
        assertEquals("Taux réduit", rate.label);
    }

    /**
     * The JPA constructor leaves the three fields null.
     */
    @Test
    void defaultConstructorLeavesFieldsNull() {
        VatRate rate = new VatRate();
        assertNull(rate.number);
        assertNull(rate.rate);
        assertNull(rate.label);
    }

    /**
     * {@code findByNumber} answers null on a null number without querying
     * (true arm of the guard).
     */
    @Test
    void findByNumberAnswersNullOnNullNumber() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertNull(VatRate.findByNumber(null));
            panache.verifyNoInteractions();
        }
    }

    /**
     * {@code findByNumber} queries on a non-null number and returns the first
     * result (false arm of the guard).
     */
    @Test
    void findByNumberReturnsTheQueriedRegime() {
        VatRate expected = regime(1, "0.2000", "Taux normal");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<VatRate> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> VatRate.find("number", 1)).thenReturn(query);
            assertSame(expected, VatRate.findByNumber(1));
        }
    }

    /**
     * {@code findByNumber} answers null when the referential carries no row
     * with that number.
     */
    @Test
    void findByNumberAnswersNullWhenUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<VatRate> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> VatRate.find("number", 9)).thenReturn(query);
            assertNull(VatRate.findByNumber(9));
        }
    }

    /**
     * {@code listAllOrdered} delegates to the ordered Panache list.
     */
    @Test
    void listAllOrderedDelegatesToTheOrderedList() {
        VatRate first = regime(1, "0.2000", "Taux normal");
        VatRate second = regime(2, "0.0550", "Taux réduit");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.list("order by number")).thenReturn(List.of(first, second));
            List<VatRate> result = VatRate.listAllOrdered();
            assertEquals(2, result.size());
            assertSame(first, result.get(0));
            assertSame(second, result.get(1));
        }
    }

    /**
     * {@code getLabel} falls back on the number when the label is null (first
     * arm of the disjunction).
     */
    @Test
    void getLabelFallsBackOnNumberWhenLabelIsNull() {
        assertEquals("3", regime(3, "0.1000", null).getLabel());
    }

    /**
     * {@code getLabel} falls back on the number when the label is blank
     * (second arm of the disjunction).
     */
    @Test
    void getLabelFallsBackOnNumberWhenLabelIsBlank() {
        assertEquals("4", regime(4, "0.0210", "   ").getLabel());
    }

    /**
     * {@code getLabel} returns the label when there is one (false arm of the
     * disjunction).
     */
    @Test
    void getLabelReturnsTheLabelWhenPresent() {
        assertEquals("Taux normal", regime(1, "0.2000", "Taux normal").getLabel());
    }

    /**
     * {@code getRateFormatted} answers the empty string on a null rate (true
     * arm of the guard).
     */
    @Test
    void getRateFormattedAnswersEmptyOnNullRate() {
        assertEquals("", regime(5, null, "Exonéré").getRateFormatted());
    }

    /**
     * {@code getRateFormatted} states a fractional rate with a comma and
     * without its needless decimals.
     */
    @Test
    void getRateFormattedStatesAFractionalRate() {
        assertEquals("5,5", regime(2, "0.0550", "Taux réduit").getRateFormatted());
    }

    /**
     * {@code getRateFormatted} states a whole rate without any decimal.
     */
    @Test
    void getRateFormattedStatesAWholeRate() {
        assertEquals("20", regime(1, "0.2000", "Taux normal").getRateFormatted());
    }

    /**
     * {@code getRateFormatted} states a zero rate as a bare zero.
     */
    @Test
    void getRateFormattedStatesAZeroRate() {
        assertEquals("0", regime(5, "0.0000", "Exonéré").getRateFormatted());
    }

    /**
     * {@code getChecksum} hashes the three fields.
     */
    @Test
    void getChecksumHashesTheThreeFields() {
        VatRate rate = regime(1, "0.2000", "Taux normal");
        assertEquals(Objects.hash(rate.number, rate.rate, rate.label), rate.getChecksum());
    }
}
