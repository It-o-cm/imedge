package com.intermarche.pos.domain.barcode;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link ArticleBarcodeRange}, targeting 100% branch coverage.
 * <p>
 * Instance methods run on plain instances; the two static finders resolve the
 * Panache {@code list} finder, which under plain {@code mvn test} falls back to
 * {@link PanacheEntityBase}, so they are intercepted with
 * {@link org.mockito.Mockito#mockStatic}.
 * <p>
 * Branch enumeration (every leg exercised): {@code segment} — reached through
 * {@code articleCode} and {@code rawValue} — covers each of the three legs of
 * its first guard separately and both arms of the length guard at the boundary;
 * {@code articleCode} covers the all-zero arm and the ordinary arm;
 * {@code rawValue} covers the unreadable arm, the unparsable arm and the
 * administered decimal counts; {@code matches} covers the null-code arm, the
 * null-pattern arm, the blank-pattern arm and both outcomes of the match;
 * {@code segmentsOverlap} covers the two disjoint arms, both touching
 * boundaries and the intersecting arm; {@code isEuro} covers the null arm, the
 * euro arm and the foreign arm; {@code findMatching} covers the null arm, the
 * blank arm, the empty-list arm, the null-entry arm, the no-match arm and the
 * match arm.
 */
class ArticleBarcodeRangeTest {

    /**
     * Builds a price range laid out as {@code 21 AAAAA VVVVV K} — the historical
     * in-store plan, stated as administered data.
     *
     * @return the administered range
     */
    private ArticleBarcodeRange priceRange() {
        ArticleBarcodeRange range = new ArticleBarcodeRange();
        range.code = "BALANCE_PRIX";
        range.label = "Étiquette prix";
        range.prefix = "21";
        range.codeLength = 13;
        range.codeKind = CouponField.Kind.NUMERIC;
        range.articlePosition = 2;
        range.articleLength = 5;
        range.valueSource = ArticleBarcodeRange.ValueSource.PRICE;
        range.valuePosition = 7;
        range.valueLength = 5;
        range.valueDecimals = 2;
        range.matchPattern = "^21\\d{11}$";
        return range;
    }

    /**
     * {@code articleCode} reads the administered segment and strips the padding
     * zeros the scale prints.
     */
    @Test
    void theArticleSegmentIsReadWhereTheRangeSaysItIs() {
        assertEquals("1234", priceRange().articleCode("2101234001509"));
    }

    /**
     * A SECOND administered layout reads another segment of the very same code,
     * which is what proves the position is read rather than hard-coded.
     */
    @Test
    void aSecondAdministeredLayoutReadsAnotherSegment() {
        ArticleBarcodeRange range = priceRange();
        range.articlePosition = 7;
        range.articleLength = 5;
        range.valuePosition = 2;
        range.valueLength = 5;
        assertEquals("150", range.articleCode("2101234001509"));
    }

    /**
     * An all-zero article segment strips to a single zero rather than to an
     * empty code (all-zero arm).
     */
    @Test
    void anAllZeroArticleSegmentStripsToZero() {
        assertEquals("0", priceRange().articleCode("2100000001507"));
    }

    /**
     * A code too short to carry the article segment reads null rather than
     * throwing (length arm).
     */
    @Test
    void aCodeTooShortCarriesNoArticle() {
        assertNull(priceRange().articleCode("210123"));
    }

    /**
     * A code exactly long enough to carry the article segment reads it — the
     * boundary of the length guard.
     */
    @Test
    void aCodeExactlyLongEnoughCarriesTheArticle() {
        assertEquals("1234", priceRange().articleCode("2101234"));
    }

    /**
     * A null code carries no article (null arm).
     */
    @Test
    void aNullCodeCarriesNoArticle() {
        assertNull(priceRange().articleCode(null));
    }

    /**
     * A negative administered position reads nothing (negative-offset arm).
     */
    @Test
    void aNegativePositionReadsNothing() {
        ArticleBarcodeRange range = priceRange();
        range.articlePosition = -1;
        assertNull(range.articleCode("2101234001509"));
    }

    /**
     * A zero-length administered segment reads nothing (zero-length arm).
     */
    @Test
    void aZeroLengthSegmentReadsNothing() {
        ArticleBarcodeRange range = priceRange();
        range.valueLength = 0;
        assertNull(range.rawValue("2101234001509"));
    }

    /**
     * {@code rawValue} applies the administered number of decimals: two, and the
     * segment is a price in cents.
     */
    @Test
    void theValueSegmentIsScaledByTheAdministeredDecimals() {
        assertEquals(new BigDecimal("1.50"), priceRange().rawValue("2101234001509"));
    }

    /**
     * A SECOND administered decimal count scales the very same digits
     * differently — the weight plan, in grams.
     */
    @Test
    void aSecondAdministeredDecimalCountScalesDifferently() {
        ArticleBarcodeRange range = priceRange();
        range.valueDecimals = 3;
        assertEquals(new BigDecimal("0.150"), range.rawValue("2101234001509"));
    }

    /**
     * A value segment carrying something other than digits reads null rather
     * than throwing (unparsable arm).
     */
    @Test
    void anUnparsableValueSegmentReadsNull() {
        ArticleBarcodeRange range = priceRange();
        range.codeKind = CouponField.Kind.ALPHANUMERIC;
        assertNull(range.rawValue("21012340ABCD9"));
    }

    /**
     * A code too short to carry the value segment reads null (length arm).
     */
    @Test
    void aCodeTooShortCarriesNoValue() {
        assertNull(priceRange().rawValue("2101234"));
    }

    /**
     * {@code matches} recognizes a code of the range and refuses one outside it.
     */
    @Test
    void thePatternDecidesWhatBelongsToTheRange() {
        ArticleBarcodeRange range = priceRange();
        assertTrue(range.matches("2101234001509"));
        assertFalse(range.matches("2301234005006"));
    }

    /**
     * A null code belongs to no range (null-code arm).
     */
    @Test
    void aNullCodeBelongsToNoRange() {
        assertFalse(priceRange().matches(null));
    }

    /**
     * A range whose pattern was never generated recognizes nothing — null and
     * blank alike, the two legs of the same guard.
     */
    @Test
    void anUngeneratedRangeRecognizesNothing() {
        ArticleBarcodeRange nullPattern = priceRange();
        nullPattern.matchPattern = null;
        assertFalse(nullPattern.matches("2101234001509"));
        ArticleBarcodeRange blankPattern = priceRange();
        blankPattern.matchPattern = "   ";
        assertFalse(blankPattern.matches("2101234001509"));
    }

    /**
     * {@code segmentsOverlap} sees disjoint segments in both orders, tolerates
     * the two touching boundaries and catches the real intersection.
     */
    @Test
    void overlappingSegmentsAreDetected() {
        ArticleBarcodeRange range = priceRange();
        assertFalse(range.segmentsOverlap());

        range.articlePosition = 7;
        range.valuePosition = 2;
        assertFalse(range.segmentsOverlap());

        range.articlePosition = 2;
        range.valuePosition = 7;
        assertFalse(range.segmentsOverlap());

        range.valuePosition = 6;
        assertTrue(range.segmentsOverlap());
    }

    /**
     * {@code articleEndPosition} and {@code valueEndPosition} report the
     * position just past each administered segment.
     */
    @Test
    void theEndPositionsFollowTheAdministeredSegments() {
        ArticleBarcodeRange range = priceRange();
        assertEquals(7, range.articleEndPosition());
        assertEquals(12, range.valueEndPosition());
    }

    /**
     * {@code isPriceEmbedded} separates the two families of range.
     */
    @Test
    void thePriceAndWeightFamiliesAreDistinguished() {
        ArticleBarcodeRange range = priceRange();
        assertTrue(range.isPriceEmbedded());
        range.valueSource = ArticleBarcodeRange.ValueSource.WEIGHT;
        assertFalse(range.isPriceEmbedded());
    }

    /**
     * {@code isEuro} covers its three legs: an administered euro range, a range
     * whose currency was never set, and a foreign one.
     */
    @Test
    void theCurrencyDecidesWhetherAConversionIsNeeded() {
        ArticleBarcodeRange range = priceRange();
        assertTrue(range.isEuro());
        range.currency = null;
        assertTrue(range.isEuro());
        range.currency = CouponField.PriceCurrency.FRF;
        assertFalse(range.isEuro());
    }

    /**
     * {@code findMatching} answers the first active range recognizing the code,
     * skipping a null row and a range the code does not belong to.
     */
    @Test
    void findMatchingAnswersTheFirstRecognizingRange() {
        ArticleBarcodeRange other = priceRange();
        other.matchPattern = "^23\\d{11}$";
        ArticleBarcodeRange wanted = priceRange();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> ArticleBarcodeRange.list("active = true order by priority, code"))
                    .thenReturn(java.util.Arrays.asList(null, other, wanted));
            assertSame(wanted, ArticleBarcodeRange.findMatching("2101234001509"));
        }
    }

    /**
     * {@code findMatching} answers null when no active range recognizes the
     * code (no-match arm) and when none is administered at all (empty arm).
     */
    @Test
    void findMatchingAnswersNullWhenNothingRecognizesTheCode() {
        ArticleBarcodeRange other = priceRange();
        other.matchPattern = "^23\\d{11}$";
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> ArticleBarcodeRange.list("active = true order by priority, code"))
                    .thenReturn(List.of(other));
            assertNull(ArticleBarcodeRange.findMatching("2101234001509"));
            ms.when(() -> ArticleBarcodeRange.list("active = true order by priority, code"))
                    .thenReturn(List.of());
            assertNull(ArticleBarcodeRange.findMatching("2101234001509"));
        }
    }

    /**
     * A null or blank code is answered without touching the database, which is
     * what the absence of any static stubbing here asserts.
     */
    @Test
    void findMatchingShortCircuitsOnAnEmptyCode() {
        assertNull(ArticleBarcodeRange.findMatching(null));
        assertNull(ArticleBarcodeRange.findMatching("   "));
    }

    /**
     * {@code listActiveByPriority} forwards the administered order verbatim.
     */
    @Test
    void listActiveByPriorityForwardsTheAdministeredOrder() {
        ArticleBarcodeRange range = priceRange();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> ArticleBarcodeRange.list("active = true order by priority, code"))
                    .thenReturn(List.of(range));
            assertEquals(List.of(range), ArticleBarcodeRange.listActiveByPriority());
        }
    }

    /**
     * The length bounds the questionnaire states are the ones the entity
     * publishes (BO-03-06-10).
     */
    @Test
    void theAdministeredLengthBoundsAreOneAndThirtyEight() {
        assertEquals(1, ArticleBarcodeRange.MIN_CODE_LENGTH);
        assertEquals(38, ArticleBarcodeRange.MAX_CODE_LENGTH);
    }

    /**
     * Exercises the two enum accessors so the enum body is fully covered.
     */
    @Test
    void theValueSourcesAreNamed() {
        assertEquals(ArticleBarcodeRange.ValueSource.PRICE,
                ArticleBarcodeRange.ValueSource.valueOf("PRICE"));
        assertEquals(2, ArticleBarcodeRange.ValueSource.values().length);
    }
}
