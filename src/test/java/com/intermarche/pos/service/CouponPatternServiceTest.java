package com.intermarche.pos.service;

import com.intermarche.pos.domain.barcode.ArticleBarcodeRange;
import com.intermarche.pos.domain.barcode.CouponField;
import com.intermarche.pos.domain.barcode.CouponType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CouponPatternService}, targeting 100% branch coverage.
 * <p>
 * The service is pure: it reads an administered {@link CouponType} and writes
 * strings, so every test runs on plain instances with no database and no
 * Quarkus boot.
 * <p>
 * Branch enumeration (every leg exercised): {@code regenerate} covers the null
 * arm, the not-administered arm and the administered arm; {@code
 * buildMatchPattern} and {@code buildAmountPattern} cover their null arm, their
 * not-administered arm and, for the amount, each of the four legs of the guard
 * that refuses to place a capturing group; {@code validate} covers every
 * problem it can report and the sound case; {@code slots} covers both arms of
 * its kind fallback and each leg of the field skip; {@code appendRuns} covers
 * the no-group path, the group-at-the-start path and the group-at-the-end
 * path; {@code escape} covers the meta and non-meta arms.
 * <p>
 * The last test is the fidelity proof: the descriptions seeded by the data
 * initializer must regenerate the very patterns written there by hand.
 */
class CouponPatternServiceTest {

    /** The service under test. */
    private final CouponPatternService service = new CouponPatternService();

    /**
     * Builds an administered range.
     *
     * @param prefix the literal head, or null
     * @param codeLength the total code length, or null for a range left unadministered
     * @param source where the amount comes from
     * @return the configured range
     */
    private CouponType range(String prefix, Integer codeLength, CouponType.AmountSource source) {
        CouponType type = new CouponType();
        type.code = "RANGE";
        type.prefix = prefix;
        type.codeLength = codeLength;
        type.codeKind = CouponField.Kind.NUMERIC;
        type.amountSource = source;
        type.fields = new ArrayList<>();
        return type;
    }

    /**
     * Adds an administered position to a range.
     *
     * @param type the range
     * @param role the role the field carries
     * @param offset the zero-based start position
     * @param length the number of characters
     * @return the added field, so the caller can refine it
     */
    private CouponField add(CouponType type, CouponField.Role role, int offset, int length) {
        CouponField f = new CouponField();
        f.couponType = type;
        f.role = role;
        f.offsetPosition = offset;
        f.fieldLength = length;
        f.kind = CouponField.Kind.NUMERIC;
        type.fields.add(f);
        return f;
    }

    /**
     * regenerate tolerates a null range (first leg of the guard).
     */
    @Test
    void regenerateNullRangeDoesNothing() {
        Assertions.assertDoesNotThrow(() -> service.regenerate(null));
    }

    /**
     * regenerate leaves a hand-written range untouched (second leg of the guard).
     */
    @Test
    void regenerateLeavesAnUnadministeredRangeUntouched() {
        CouponType type = range("50", null, CouponType.AmountSource.ENCODED);
        type.matchPattern = "^50\\d{12}$";
        type.amountPattern = "\\d{10}(\\d{4})$";
        service.regenerate(type);
        Assertions.assertEquals("^50\\d{12}$", type.matchPattern);
        Assertions.assertEquals("\\d{10}(\\d{4})$", type.amountPattern);
    }

    /**
     * regenerate rewrites both patterns of an administered range.
     */
    @Test
    void regenerateRewritesBothPatterns() {
        CouponType type = range("298", 13, CouponType.AmountSource.ENCODED);
        add(type, CouponField.Role.PRICE, 9, 4);
        type.matchPattern = "obsolete";
        type.amountPattern = "obsolete";
        service.regenerate(type);
        Assertions.assertEquals("^298\\d{10}$", type.matchPattern);
        Assertions.assertEquals("^298\\d{6}(\\d{4})$", type.amountPattern);
    }

    /**
     * buildMatchPattern returns null for a null range.
     */
    @Test
    void buildMatchPatternNullRangeIsNull() {
        Assertions.assertNull(service.buildMatchPattern(null));
    }

    /**
     * buildMatchPattern hands back the hand-written pattern of an unadministered range.
     */
    @Test
    void buildMatchPatternHandsBackTheHandWrittenPattern() {
        CouponType type = range("50", null, CouponType.AmountSource.MANUAL);
        type.matchPattern = "^50\\d{12}$";
        Assertions.assertEquals("^50\\d{12}$", service.buildMatchPattern(type));
    }

    /**
     * buildMatchPattern writes the prefix then one quantified run.
     */
    @Test
    void buildMatchPatternWritesPrefixThenOneRun() {
        CouponType type = range("50", 14, CouponType.AmountSource.MANUAL);
        Assertions.assertEquals("^50\\d{12}$", service.buildMatchPattern(type));
    }

    /**
     * buildMatchPattern writes a single run when the range has no prefix.
     */
    @Test
    void buildMatchPatternWithoutPrefixIsOneRun() {
        CouponType type = range(null, 10, CouponType.AmountSource.MANUAL);
        Assertions.assertEquals("^\\d{10}$", service.buildMatchPattern(type));
    }

    /**
     * buildMatchPattern emits a bare token, unquantified, for a one-character run.
     */
    @Test
    void buildMatchPatternEmitsABareTokenForOneCharacter() {
        CouponType type = range("50", 3, CouponType.AmountSource.MANUAL);
        Assertions.assertEquals("^50\\d$", service.buildMatchPattern(type));
    }

    /**
     * buildMatchPattern emits nothing past a prefix that fills the code.
     */
    @Test
    void buildMatchPatternOnAPrefixOnlyRangeIsTheLiteral() {
        CouponType type = range("50", 2, CouponType.AmountSource.MANUAL);
        Assertions.assertEquals("^50$", service.buildMatchPattern(type));
    }

    /**
     * buildMatchPattern switches token inside an alphanumeric field.
     */
    @Test
    void buildMatchPatternSwitchesTokenInsideAnAlphanumericField() {
        CouponType type = range("98", 10, CouponType.AmountSource.MANUAL);
        add(type, CouponField.Role.SEQUENCE_NUMBER, 4, 3).kind = CouponField.Kind.ALPHANUMERIC;
        Assertions.assertEquals("^98\\d{2}[A-Za-z0-9]{3}\\d{3}$", service.buildMatchPattern(type));
    }

    /**
     * buildMatchPattern writes the whole range in the administered character
     * kind, which is what an alphanumeric range means (BO-03-06-09).
     */
    @Test
    void buildMatchPatternHonoursAnAlphanumericRange() {
        CouponType type = range("98", 10, CouponType.AmountSource.MANUAL);
        type.codeKind = CouponField.Kind.ALPHANUMERIC;
        Assertions.assertEquals("^98[A-Za-z0-9]{8}$", service.buildMatchPattern(type));
    }

    /**
     * buildMatchPattern writes an any-character range as such, the third kind
     * of the catalog.
     */
    @Test
    void buildMatchPatternHonoursAnAnyCharacterRange() {
        CouponType type = range(null, 4, CouponType.AmountSource.MANUAL);
        type.codeKind = CouponField.Kind.ANY;
        Assertions.assertEquals("^.{4}$", service.buildMatchPattern(type));
    }

    /**
     * buildMatchPattern falls back to digits when the range declares no kind.
     */
    @Test
    void buildMatchPatternFallsBackToDigitsWithoutAKind() {
        CouponType type = range("50", 6, CouponType.AmountSource.MANUAL);
        type.codeKind = null;
        Assertions.assertEquals("^50\\d{4}$", service.buildMatchPattern(type));
    }

    /**
     * buildMatchPattern ignores a field with no kind, no length, or nothing at all.
     */
    @Test
    void buildMatchPatternIgnoresUnusableFields() {
        CouponType type = range("50", 8, CouponType.AmountSource.MANUAL);
        add(type, CouponField.Role.TICKET_NUMBER, 2, 2).kind = null;
        add(type, CouponField.Role.TPV_NUMBER, 4, 0).kind = CouponField.Kind.ANY;
        type.fields.add(null);
        Assertions.assertEquals("^50\\d{6}$", service.buildMatchPattern(type));
    }

    /**
     * buildMatchPattern treats a null field list as no field at all.
     */
    @Test
    void buildMatchPatternToleratesANullFieldList() {
        CouponType type = range("50", 8, CouponType.AmountSource.MANUAL);
        type.fields = null;
        Assertions.assertEquals("^50\\d{6}$", service.buildMatchPattern(type));
    }

    /**
     * buildMatchPattern protects a prefix carrying a regular expression character.
     */
    @Test
    void buildMatchPatternEscapesTheMetaCharactersOfThePrefix() {
        CouponType type = range("5+2", 6, CouponType.AmountSource.MANUAL);
        Assertions.assertEquals("^5\\+2\\d{3}$", service.buildMatchPattern(type));
    }

    /**
     * buildAmountPattern returns null for a null range.
     */
    @Test
    void buildAmountPatternNullRangeIsNull() {
        Assertions.assertNull(service.buildAmountPattern(null));
    }

    /**
     * buildAmountPattern hands back the hand-written pattern of an unadministered range.
     */
    @Test
    void buildAmountPatternHandsBackTheHandWrittenPattern() {
        CouponType type = range("50", null, CouponType.AmountSource.ENCODED);
        type.amountPattern = "\\d{10}(\\d{4})$";
        Assertions.assertEquals("\\d{10}(\\d{4})$", service.buildAmountPattern(type));
    }

    /**
     * buildAmountPattern returns null when the amount is typed in (first leg).
     */
    @Test
    void buildAmountPatternOnAManualRangeIsNull() {
        CouponType type = range("50", 14, CouponType.AmountSource.MANUAL);
        add(type, CouponField.Role.PRICE, 10, 4);
        Assertions.assertNull(service.buildAmountPattern(type));
    }

    /**
     * buildAmountPattern returns null when no price is administered (second leg).
     */
    @Test
    void buildAmountPatternWithoutAPriceFieldIsNull() {
        CouponType type = range("50", 14, CouponType.AmountSource.ENCODED);
        Assertions.assertNull(service.buildAmountPattern(type));
    }

    /**
     * buildAmountPattern returns null when the price starts inside the prefix (third leg).
     */
    @Test
    void buildAmountPatternWithAPriceInsideThePrefixIsNull() {
        CouponType type = range("298", 13, CouponType.AmountSource.ENCODED);
        add(type, CouponField.Role.PRICE, 2, 4);
        Assertions.assertNull(service.buildAmountPattern(type));
    }

    /**
     * buildAmountPattern returns null when the price runs past the code (fourth leg).
     */
    @Test
    void buildAmountPatternWithAPriceBeyondTheCodeIsNull() {
        CouponType type = range("298", 13, CouponType.AmountSource.ENCODED);
        add(type, CouponField.Role.PRICE, 10, 4);
        Assertions.assertNull(service.buildAmountPattern(type));
    }

    /**
     * buildAmountPattern places the group between two runs.
     */
    @Test
    void buildAmountPatternPlacesTheGroupBetweenTwoRuns() {
        CouponType type = range("50", 16, CouponType.AmountSource.ENCODED);
        add(type, CouponField.Role.PRICE, 6, 4);
        Assertions.assertEquals("^50\\d{4}(\\d{4})\\d{6}$", service.buildAmountPattern(type));
    }

    /**
     * buildAmountPattern places the group right after the prefix.
     */
    @Test
    void buildAmountPatternPlacesTheGroupRightAfterThePrefix() {
        CouponType type = range("50", 10, CouponType.AmountSource.ENCODED);
        add(type, CouponField.Role.PRICE, 2, 4);
        Assertions.assertEquals("^50(\\d{4})\\d{4}$", service.buildAmountPattern(type));
    }

    /**
     * buildAmountPattern closes the pattern on the group when the price ends the code.
     */
    @Test
    void buildAmountPatternClosesOnTheGroupAtTheEndOfTheCode() {
        CouponType type = range(null, 10, CouponType.AmountSource.ENCODED);
        add(type, CouponField.Role.PRICE, 6, 4);
        Assertions.assertEquals("^\\d{6}(\\d{4})$", service.buildAmountPattern(type));
    }

    /**
     * validate reports nothing for a null range (first leg of the guard).
     */
    @Test
    void validateNullRangeHasNoProblem() {
        Assertions.assertTrue(service.validate(null).isEmpty());
    }

    /**
     * validate reports nothing for a hand-written range (second leg of the guard).
     */
    @Test
    void validateUnadministeredRangeHasNoProblem() {
        CouponType type = range("50", null, CouponType.AmountSource.ENCODED);
        Assertions.assertTrue(service.validate(type).isEmpty());
    }

    /**
     * validate accepts a sound range, fields included.
     */
    @Test
    void validateAcceptsASoundRange() {
        CouponType type = range("298", 13, CouponType.AmountSource.ENCODED);
        add(type, CouponField.Role.PRICE, 9, 4);
        CouponField date = add(type, CouponField.Role.DATE_END, 3, 6);
        date.dateFormat = CouponField.DateFormat.DDMMYY;
        Assertions.assertTrue(service.validate(type).isEmpty(), service.validate(type).toString());
    }

    /**
     * validate refuses a prefix longer than the code itself.
     */
    @Test
    void validateRefusesAPrefixLongerThanTheCode() {
        CouponType type = range("12345", 3, CouponType.AmountSource.MANUAL);
        Assertions.assertTrue(service.validate(type).get(0).contains("plus long que le code"));
    }

    /**
     * validate refuses a field carrying no role.
     */
    @Test
    void validateRefusesAFieldWithoutARole() {
        CouponType type = range(null, 10, CouponType.AmountSource.MANUAL);
        add(type, null, 0, 4);
        Assertions.assertTrue(service.validate(type).get(0).contains("n'a pas de rôle"));
    }

    /**
     * validate refuses the same role administered twice.
     */
    @Test
    void validateRefusesADuplicateRole() {
        CouponType type = range(null, 12, CouponType.AmountSource.MANUAL);
        add(type, CouponField.Role.TICKET_NUMBER, 0, 4);
        add(type, CouponField.Role.TICKET_NUMBER, 4, 4);
        Assertions.assertTrue(service.validate(type).get(0).contains("administré deux fois"));
    }

    /**
     * validate refuses a field of zero length.
     */
    @Test
    void validateRefusesAZeroLengthField() {
        CouponType type = range(null, 10, CouponType.AmountSource.MANUAL);
        add(type, CouponField.Role.TPV_NUMBER, 0, 0);
        Assertions.assertTrue(service.validate(type).get(0).contains("longueur nulle"));
    }

    /**
     * validate refuses a field starting before the code.
     */
    @Test
    void validateRefusesANegativeOffset() {
        CouponType type = range(null, 10, CouponType.AmountSource.MANUAL);
        add(type, CouponField.Role.TPV_NUMBER, -1, 3);
        Assertions.assertTrue(service.validate(type).get(0).contains("avant le début"));
    }

    /**
     * validate refuses a field overlapping the literal head.
     */
    @Test
    void validateRefusesAFieldOverThePrefix() {
        CouponType type = range("298", 13, CouponType.AmountSource.MANUAL);
        add(type, CouponField.Role.TPV_NUMBER, 1, 3);
        Assertions.assertTrue(service.validate(type).get(0).contains("empiète sur le préfixe"));
    }

    /**
     * validate refuses a field running past the end of the code.
     */
    @Test
    void validateRefusesAFieldBeyondTheCode() {
        CouponType type = range(null, 10, CouponType.AmountSource.MANUAL);
        add(type, CouponField.Role.TPV_NUMBER, 8, 4);
        Assertions.assertTrue(service.validate(type).get(0).contains("dépasse la longueur"));
    }

    /**
     * validate refuses a date field whose length does not fit its layout.
     */
    @Test
    void validateRefusesADateFieldOfTheWrongWidth() {
        CouponType type = range(null, 10, CouponType.AmountSource.MANUAL);
        add(type, CouponField.Role.DATE_END, 0, 4).dateFormat = CouponField.DateFormat.DDMMYY;
        Assertions.assertTrue(service.validate(type).get(0).contains("occupe 6 caractères"));
    }

    /**
     * validate refuses two fields claiming a common position.
     */
    @Test
    void validateRefusesOverlappingFields() {
        CouponType type = range(null, 12, CouponType.AmountSource.MANUAL);
        add(type, CouponField.Role.TICKET_NUMBER, 0, 5);
        add(type, CouponField.Role.TPV_NUMBER, 4, 4);
        Assertions.assertTrue(service.validate(type).get(0).contains("se chevauchent"));
    }

    /**
     * validate ignores a null field and a role-less field when pairing overlaps.
     */
    @Test
    void validateSkipsUnusableFieldsWhenPairingOverlaps() {
        CouponType type = range(null, 12, CouponType.AmountSource.MANUAL);
        type.fields.add(null);
        add(type, null, 0, 4);
        add(type, CouponField.Role.TPV_NUMBER, 4, 4);
        List<String> problems = service.validate(type);
        Assertions.assertEquals(1, problems.size());
        Assertions.assertTrue(problems.get(0).contains("n'a pas de rôle"));
    }

    /**
     * validate refuses an encoded amount with nowhere to read it.
     */
    @Test
    void validateRefusesAnEncodedAmountWithoutAPriceField() {
        CouponType type = range(null, 10, CouponType.AmountSource.ENCODED);
        Assertions.assertTrue(service.validate(type).get(0).contains("aucun champ PRICE"));
    }

    /**
     * validate treats a null field list as no field at all.
     */
    @Test
    void validateToleratesANullFieldList() {
        CouponType type = range("50", 10, CouponType.AmountSource.MANUAL);
        type.fields = null;
        Assertions.assertTrue(service.validate(type).isEmpty());
    }

    /**
     * The posted overload reports nothing without a code length (first leg).
     */
    @Test
    void validatePostedWithoutACodeLengthHasNoProblem() {
        Assertions.assertTrue(service.validate("50", null,
                CouponType.AmountSource.ENCODED, List.of()).isEmpty());
    }

    /**
     * The posted overload reports nothing on a zero code length (second leg, boundary).
     */
    @Test
    void validatePostedOnAZeroCodeLengthHasNoProblem() {
        Assertions.assertTrue(service.validate("50", 0,
                CouponType.AmountSource.ENCODED, List.of()).isEmpty());
    }

    /**
     * The posted overload treats an absent prefix as an empty one.
     */
    @Test
    void validatePostedTreatsANullPrefixAsEmpty() {
        List<CouponField> fields = new ArrayList<>();
        CouponField price = new CouponField();
        price.role = CouponField.Role.PRICE;
        price.offsetPosition = 0;
        price.fieldLength = 4;
        price.kind = CouponField.Kind.NUMERIC;
        fields.add(price);
        Assertions.assertTrue(service.validate(null, 10,
                CouponType.AmountSource.ENCODED, fields).isEmpty());
    }

    /**
     * The posted overload treats a null field list as no field at all.
     */
    @Test
    void validatePostedToleratesANullFieldList() {
        Assertions.assertTrue(service.validate("50", 10,
                CouponType.AmountSource.MANUAL, null).isEmpty());
    }

    /**
     * The descriptions seeded by the data initializer regenerate the very
     * patterns written there by hand: same recognition pattern, character for
     * character, and an amount pattern that reads the same cents.
     */
    @Test
    void administeredDescriptionsReproduceTheSeededPatterns() {
        assertFaithful("297", 15, -1, 0, CouponType.AmountSource.REGISTRY,
                "^297\\d{12}$", null, "297000000001234");
        assertFaithful("296", 15, -1, 0, CouponType.AmountSource.REGISTRY,
                "^296\\d{12}$", null, "296000000001234");
        assertFaithful("", 10, 6, 4, CouponType.AmountSource.ENCODED,
                "^\\d{10}$", "\\d{6}(\\d{4})$", "1234561234");
        assertFaithful("50", 14, 10, 4, CouponType.AmountSource.ENCODED,
                "^50\\d{12}$", "\\d{10}(\\d{4})$", "50123456781234");
        assertFaithful("789", 15, 11, 4, CouponType.AmountSource.ENCODED,
                "^789\\d{12}$", "\\d{11}(\\d{4})$", "789123456781234");
        assertFaithful("0482", 14, -1, 0, CouponType.AmountSource.MANUAL,
                "^0482\\d{10}$", null, "04821234567890");
        assertFaithful("298", 13, 9, 4, CouponType.AmountSource.ENCODED,
                "^298\\d{10}$", "^298\\d{6}(\\d{4})$", "2981234561234");
    }

    /**
     * Asserts that one seeded description regenerates its hand-written patterns.
     *
     * @param prefix the literal head, possibly empty
     * @param codeLength the total code length
     * @param priceOffset the price position, or a negative value for none
     * @param priceLength the price length, ignored without a price
     * @param source where the amount comes from
     * @param seededMatch the recognition pattern written by hand
     * @param seededAmount the amount pattern written by hand, or null
     * @param sample a code of the range, used to compare the two amount patterns
     */
    private void assertFaithful(String prefix, int codeLength, int priceOffset, int priceLength,
                                CouponType.AmountSource source, String seededMatch,
                                String seededAmount, String sample) {
        CouponType type = range(prefix.isEmpty() ? null : prefix, codeLength, source);
        if (priceOffset >= 0) {
            add(type, CouponField.Role.PRICE, priceOffset, priceLength).decimals = 2;
        }
        service.regenerate(type);
        Assertions.assertEquals(seededMatch, type.matchPattern, "motif de " + prefix);
        Assertions.assertTrue(sample.matches(seededMatch), "échantillon " + sample);
        Assertions.assertTrue(sample.matches(type.matchPattern), "échantillon " + sample);
        if (seededAmount == null) {
            Assertions.assertNull(type.amountPattern, "montant de " + prefix);
            return;
        }
        Assertions.assertEquals(firstGroup(seededAmount, sample),
                firstGroup(type.amountPattern, sample), "cents de " + prefix);
        Assertions.assertEquals(new BigDecimal("12.34"), type.extractAmount(sample));
    }

    /**
     * Reads the first capturing group of a pattern out of a sample code.
     *
     * @param pattern the amount pattern
     * @param sample the code to read
     * @return the captured characters, or null when nothing matched
     */
    private String firstGroup(String pattern, String sample) {
        Matcher matcher = Pattern.compile(pattern).matcher(sample);
        return matcher.find() ? matcher.group(1) : null;
    }

    // --- Plages ARTICLE (BO-03-06-02/03/04/10) ---

    /**
     * Builds an administered article range laid out as {@code 21 AAAAA VVVVV K}.
     *
     * @return the range
     */
    private ArticleBarcodeRange articleRange() {
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
        return range;
    }

    /**
     * The generated pattern spells out the literal prefix and the remaining
     * positions, and it recognizes a code of the range while refusing one of
     * another prefix or another length.
     */
    @Test
    void theArticleRangePatternIsGeneratedFromTheAdministeredDescription() {
        String pattern = new CouponPatternService().buildRangePattern(articleRange());
        Assertions.assertEquals("^21\\d{11}$", pattern);
        Assertions.assertTrue("2101234001509".matches(pattern));
        Assertions.assertFalse("2301234005006".matches(pattern));
        Assertions.assertFalse("210123400150".matches(pattern));
    }

    /**
     * A SECOND administered description yields a different pattern, which is
     * what proves the description is read rather than a literal returned.
     */
    @Test
    void aSecondAdministeredDescriptionYieldsAnotherPattern() {
        ArticleBarcodeRange range = articleRange();
        range.prefix = "297";
        range.codeLength = 18;
        String pattern = new CouponPatternService().buildRangePattern(range);
        Assertions.assertEquals("^297\\d{15}$", pattern);
    }

    /**
     * An ALPHANUMERIC range accepts letters where a numeric one would not
     * (BO-03-06-02 — "de type numérique alphanumérique").
     */
    @Test
    void anAlphanumericArticleRangeAcceptsLetters() {
        ArticleBarcodeRange range = articleRange();
        range.codeKind = CouponField.Kind.ALPHANUMERIC;
        String pattern = new CouponPatternService().buildRangePattern(range);
        Assertions.assertEquals("^21[A-Za-z0-9]{11}$", pattern);
        Assertions.assertTrue("21ABCDE001509".matches(pattern));
    }

    /**
     * A range whose kind was never set falls back to digits (null-kind arm).
     */
    @Test
    void anArticleRangeWithoutAKindIsNumeric() {
        ArticleBarcodeRange range = articleRange();
        range.codeKind = null;
        Assertions.assertEquals("^21\\d{11}$", new CouponPatternService().buildRangePattern(range));
    }

    /**
     * A prefix carrying a regular-expression metacharacter is escaped rather
     * than interpreted.
     */
    @Test
    void anArticleRangePrefixIsEscaped() {
        ArticleBarcodeRange range = articleRange();
        range.prefix = "2.";
        Assertions.assertEquals("^2\\.\\d{11}$", new CouponPatternService().buildRangePattern(range));
    }

    /**
     * A prefix as long as the whole code leaves no free position after it
     * (boundary of the run writer).
     */
    @Test
    void aPrefixAsLongAsTheCodeLeavesNoFreePosition() {
        ArticleBarcodeRange range = articleRange();
        range.prefix = "2101234001509";
        Assertions.assertEquals("^2101234001509$", new CouponPatternService().buildRangePattern(range));
    }

    /**
     * A null range and a range without a length generate nothing (the two legs
     * of the first guard).
     */
    @Test
    void anUnusableArticleRangeGeneratesNothing() {
        CouponPatternService service = new CouponPatternService();
        Assertions.assertNull(service.buildRangePattern(null));
        ArticleBarcodeRange range = articleRange();
        range.codeLength = 0;
        Assertions.assertNull(service.buildRangePattern(range));
    }

    /**
     * {@code regenerateRange} writes the generated pattern onto the range, and
     * leaves a null range alone.
     */
    @Test
    void regenerateRangeWritesThePatternOntoTheRange() {
        CouponPatternService service = new CouponPatternService();
        ArticleBarcodeRange range = articleRange();
        service.regenerateRange(range);
        Assertions.assertEquals("^21\\d{11}$", range.matchPattern);
        service.regenerateRange(null);
    }

    /**
     * A sound description raises no problem.
     */
    @Test
    void aSoundArticleRangeRaisesNoProblem() {
        Assertions.assertTrue(new CouponPatternService().validateRange(articleRange()).isEmpty());
    }

    /**
     * A null range raises no problem either — there is nothing to check.
     */
    @Test
    void aNullArticleRangeRaisesNoProblem() {
        Assertions.assertTrue(new CouponPatternService().validateRange(null).isEmpty());
    }

    /**
     * A length under the administered floor and one over the ceiling are both
     * refused, and the two boundaries themselves are accepted (BO-03-06-10).
     */
    @Test
    void theLengthBoundsAreEnforcedAtTheirBoundaries() {
        CouponPatternService service = new CouponPatternService();
        ArticleBarcodeRange range = articleRange();

        range.codeLength = 0;
        Assertions.assertTrue(joined(service.validateRange(range)).contains("entre 1 et 38"));

        range.codeLength = 39;
        Assertions.assertTrue(joined(service.validateRange(range)).contains("entre 1 et 38"));

        range.codeLength = 38;
        Assertions.assertFalse(joined(service.validateRange(range)).contains("entre 1 et 38"));

        range.prefix = "2";
        range.codeLength = 1;
        range.articlePosition = 1;
        range.articleLength = 0;
        Assertions.assertFalse(joined(service.validateRange(range)).contains("entre 1 et 38"));
    }

    /**
     * A blank prefix and a prefix longer than the code are each named.
     */
    @Test
    void theArticleRangePrefixIsChecked() {
        CouponPatternService service = new CouponPatternService();
        ArticleBarcodeRange blank = articleRange();
        blank.prefix = "   ";
        Assertions.assertTrue(joined(service.validateRange(blank)).contains("préfixe de la plage est obligatoire"));

        ArticleBarcodeRange missing = articleRange();
        missing.prefix = null;
        Assertions.assertTrue(joined(service.validateRange(missing)).contains("préfixe de la plage est obligatoire"));

        ArticleBarcodeRange tooLong = articleRange();
        tooLong.prefix = "2101234001509XX";
        Assertions.assertTrue(joined(service.validateRange(tooLong)).contains("plus long que le code"));
    }

    /**
     * A segment of no length, one starting inside the prefix and one running
     * past the end of the code are each named, for both segments.
     */
    @Test
    void eachAdministeredSegmentIsCheckedAgainstTheCode() {
        CouponPatternService service = new CouponPatternService();

        ArticleBarcodeRange empty = articleRange();
        empty.articleLength = 0;
        Assertions.assertTrue(joined(service.validateRange(empty)).contains("zone article doit occuper"));

        ArticleBarcodeRange inPrefix = articleRange();
        inPrefix.articlePosition = 1;
        Assertions.assertTrue(joined(service.validateRange(inPrefix)).contains("zone article commence dans le préfixe"));

        ArticleBarcodeRange past = articleRange();
        past.valuePosition = 10;
        past.valueLength = 5;
        Assertions.assertTrue(joined(service.validateRange(past)).contains("zone valeur déborde"));

        ArticleBarcodeRange emptyValue = articleRange();
        emptyValue.valueLength = 0;
        Assertions.assertTrue(joined(service.validateRange(emptyValue)).contains("zone valeur doit occuper"));
    }

    /**
     * Two segments sharing a position are refused.
     */
    @Test
    void overlappingArticleSegmentsAreRefused() {
        ArticleBarcodeRange range = articleRange();
        range.valuePosition = 6;
        Assertions.assertTrue(joined(new CouponPatternService().validateRange(range))
                .contains("se chevauchent"));
    }

    /**
     * A decimal count outside the value segment is refused, at both ends, and
     * the two boundaries are accepted.
     */
    @Test
    void theDecimalCountMustFitTheValueSegment() {
        CouponPatternService service = new CouponPatternService();
        ArticleBarcodeRange range = articleRange();

        range.valueDecimals = -1;
        Assertions.assertTrue(joined(service.validateRange(range)).contains("décimales"));

        range.valueDecimals = 6;
        Assertions.assertTrue(joined(service.validateRange(range)).contains("décimales"));

        range.valueDecimals = 0;
        Assertions.assertFalse(joined(service.validateRange(range)).contains("décimales"));

        range.valueDecimals = 5;
        Assertions.assertFalse(joined(service.validateRange(range)).contains("décimales"));
    }

    /**
     * Joins the reported problems so a case can assert on one of them.
     *
     * @param problems the reported problems
     * @return the problems as one string
     */
    private String joined(List<String> problems) {
        return String.join(" | ", problems);
    }
}
