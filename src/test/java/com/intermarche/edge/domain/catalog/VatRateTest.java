package com.intermarche.edge.domain.catalog;

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
     * {@code findByRate} answers null on a null rate without querying (true arm
     * of the guard).
     */
    @Test
    void findByRateAnswersNullOnNullRate() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertNull(VatRate.findByRate(null));
            panache.verifyNoInteractions();
        }
    }

    /**
     * {@code findByRate} queries on a non-null rate and returns the first result
     * (false arm of the guard).
     */
    @Test
    void findByRateReturnsTheQueriedRegime() {
        BigDecimal rate = new BigDecimal("0.0550");
        VatRate expected = regime(2, "0.0550", "Taux réduit");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<VatRate> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> VatRate.find("rate", rate)).thenReturn(query);
            assertSame(expected, VatRate.findByRate(rate));
        }
    }

    /**
     * {@code findByRate} answers null when the referential carries no row with
     * that rate.
     */
    @Test
    void findByRateAnswersNullWhenUnknown() {
        BigDecimal rate = new BigDecimal("0.9999");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<VatRate> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> VatRate.find("rate", rate)).thenReturn(query);
            assertNull(VatRate.findByRate(rate));
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
     * {@code getChecksum} hashes every business field — the three the import
     * feed carries and the two it does not, which the referential exports and
     * the register applies, so a change to either shows as one.
     */
    @Test
    void getChecksumHashesEveryBusinessField() {
        VatRate rate = regime(1, "0.2000", "Taux normal");
        rate.exempt = true;
        rate.exemptionCode = "M07";
        assertEquals(Objects.hash(rate.number, rate.rate, rate.label,
                rate.country, rate.legalMention, true, "M07"), rate.getChecksum());
    }

    /**
     * BO-03-03-04: a regime's code is its number in letters — zero is A, 25 is
     * Z, 26 is AA, 27 AB — and a regime without a usable number has none.
     */
    @Test
    void theCodeWritesTheNumberInLetters() {
        VatRate rate = regime(0, "0.2000", "Taux normal");
        for (java.util.Map.Entry<Integer, String> example
                : java.util.Map.of(0, "A", 1, "B", 25, "Z", 26, "AA", 27, "AB").entrySet()) {
            rate.number = example.getKey();
            assertEquals(example.getValue(), rate.code());
        }
        rate.number = null;
        assertNull(rate.code());
        rate.number = -1;
        assertNull(rate.code());
    }

    /**
     * BO-03-03-04: {@code codeOf} names the code of the regime carrying a rate,
     * and nothing for a rate no regime carries.
     */
    @Test
    void codeOfNamesTheRegimeOfARate() {
        BigDecimal rate = new BigDecimal("0.0550");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<VatRate> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(regime(1, "0.0550", "Taux réduit"));
            panache.when(() -> VatRate.find("rate", rate)).thenReturn(query);
            assertEquals("B", VatRate.codeOf(rate));
            assertNull(VatRate.codeOf(null));
        }
    }

    /**
     * Stubs the regime a number names.
     *
     * @param panache the active static mock
     * @param number the number
     * @param regime the regime, or null
     */
    @SuppressWarnings("unchecked")
    private void stubNumber(MockedStatic<PanacheEntityBase> panache, int number, VatRate regime) {
        PanacheQuery<VatRate> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(regime);
        panache.when(() -> VatRate.find("number", number)).thenReturn(query);
    }

    /**
     * Stubs the first regime of a rate.
     *
     * @param panache the active static mock
     * @param rate the rate
     * @param regime the regime, or null
     */
    @SuppressWarnings("unchecked")
    private void stubRate(MockedStatic<PanacheEntityBase> panache, BigDecimal rate, VatRate regime) {
        PanacheQuery<VatRate> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(regime);
        panache.when(() -> VatRate.find("rate", rate)).thenReturn(query);
    }

    /**
     * BO-02-02-01: {@code resolve} trusts the number when its regime carries the
     * rate, or when either side states no rate, and falls back on the rate
     * otherwise — and without a number, or with an unknown one.
     */
    @Test
    void resolveReadsTheNumberBeforeTheRate() {
        BigDecimal zero = new BigDecimal("0.0000");
        VatRate exemptNine = regime(10, "0.0000", "Isento art. 9");
        VatRate exemptFiftyThree = regime(11, "0.0000", "Isento art. 53");
        VatRate unrated = regime(12, "0.0000", "Sans taux");
        unrated.rate = null;
        VatRate rerated = regime(13, "0.0600", "Réduit");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubNumber(panache, 11, exemptFiftyThree);
            stubNumber(panache, 12, unrated);
            stubNumber(panache, 13, rerated);
            stubNumber(panache, 99, null);
            stubRate(panache, zero, exemptNine);
            assertSame(exemptFiftyThree, VatRate.resolve(zero, 11));
            assertSame(exemptFiftyThree, VatRate.resolve(null, 11));
            assertSame(unrated, VatRate.resolve(zero, 12));
            assertSame(exemptNine, VatRate.resolve(zero, 13));
            assertSame(exemptNine, VatRate.resolve(zero, 99));
            assertSame(exemptNine, VatRate.resolve(zero, null));
        }
    }

    /**
     * BO-02-02-01: {@code numberFor} answers the regime of the article's current
     * price when it carries the line's rate, the first regime of the rate
     * otherwise, and null without a rate or a regime.
     */
    @Test
    void numberForReadsTheRegimeOfTheCurrentPrice() {
        BigDecimal zero = new BigDecimal("0");
        VatRate exemptNine = regime(10, "0.0000", "Isento art. 9");
        VatRate exemptFiftyThree = regime(11, "0.0000", "Isento art. 53");
        VatRate unrated = regime(12, "0.0000", "Sans taux");
        unrated.rate = null;
        assertNull(VatRate.numberFor("123", null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRate(panache, zero, exemptNine);
            Product donation = new Product();
            donation.id = 1L;
            Product unpriced = new Product();
            unpriced.id = 2L;
            Product bare = new Product();
            bare.id = 3L;
            Product oddly = new Product();
            oddly.id = 4L;
            Product taxed = new Product();
            taxed.id = 5L;
            Price exemptPrice = new Price();
            exemptPrice.vat = exemptFiftyThree;
            Price noVat = new Price();
            Price unratedPrice = new Price();
            unratedPrice.vat = unrated;
            Price taxedPrice = new Price();
            taxedPrice.vat = regime(1, "0.2000", "Normal");
            stubProduct(panache, "EXEMPT", donation);
            stubProduct(panache, "UNPRICED", unpriced);
            stubProduct(panache, "BARE", bare);
            stubProduct(panache, "ODD", oddly);
            stubProduct(panache, "TAXED", taxed);
            stubProduct(panache, "UNKNOWN", null);
            stubPrice(panache, 1L, exemptPrice);
            stubPrice(panache, 2L, null);
            stubPrice(panache, 3L, noVat);
            stubPrice(panache, 4L, unratedPrice);
            stubPrice(panache, 5L, taxedPrice);
            assertEquals(11, VatRate.numberFor("EXEMPT", zero));
            assertEquals(10, VatRate.numberFor("UNPRICED", zero));
            assertEquals(10, VatRate.numberFor("BARE", zero));
            assertEquals(10, VatRate.numberFor("ODD", zero));
            assertEquals(10, VatRate.numberFor("TAXED", zero));
            assertEquals(10, VatRate.numberFor("UNKNOWN", zero));
            assertEquals(10, VatRate.numberFor(null, zero));
            BigDecimal unknownRate = new BigDecimal("0.3300");
            stubRate(panache, unknownRate, null);
            assertNull(VatRate.numberFor(null, unknownRate));
        }
    }

    /**
     * BO-02-02-01 with BO-02-03-27: {@code numberFor} answers first the regime
     * the article itself names when it carries the line's rate, ahead of the
     * price's regime, and passes over an own regime of another rate.
     */
    @Test
    void numberForPrefersTheRegimeTheArticleNames() {
        BigDecimal zero = new BigDecimal("0");
        VatRate exemptFiftyThree = regime(11, "0.0000", "Isento art. 53");
        VatRate reduced = regime(13, "0.0600", "Réduit");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubNumber(panache, 11, exemptFiftyThree);
            stubNumber(panache, 13, reduced);
            stubRate(panache, zero, regime(10, "0.0000", "Isento art. 9"));
            Product own = new Product();
            own.id = 1L;
            own.attributes.put(com.intermarche.edge.domain.catalog.attribute.ProductAttributeCatalog.VAT_NUMBER, "11");
            Product other = new Product();
            other.id = 2L;
            other.attributes.put(com.intermarche.edge.domain.catalog.attribute.ProductAttributeCatalog.VAT_NUMBER, "13");
            Price taxedPrice = new Price();
            taxedPrice.vat = regime(1, "0.2000", "Normal");
            stubProduct(panache, "OWN", own);
            stubProduct(panache, "OTHER", other);
            stubPrice(panache, 1L, taxedPrice);
            stubPrice(panache, 2L, taxedPrice);
            assertEquals(11, VatRate.numberFor("OWN", zero));
            assertEquals(10, VatRate.numberFor("OTHER", zero));
        }
    }

    /**
     * Stubs the product an EAN names.
     *
     * @param panache the active static mock
     * @param ean the EAN
     * @param product the product, or null
     */
    @SuppressWarnings("unchecked")
    private void stubProduct(MockedStatic<PanacheEntityBase> panache, String ean, Product product) {
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(product);
        panache.when(() -> PanacheEntityBase.find(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(ean))).thenReturn(query);
    }

    /**
     * Stubs the current price of a product, whatever the instant.
     *
     * @param panache the active static mock
     * @param productId the product id
     * @param price the price, or null
     */
    @SuppressWarnings("unchecked")
    private void stubPrice(MockedStatic<PanacheEntityBase> panache, Long productId, Price price) {
        PanacheQuery<Price> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(price);
        panache.when(() -> PanacheEntityBase.find(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(productId),
                org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class))).thenReturn(query);
    }
}
