package com.intermarche.pos.domain.people;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.intermarche.pos.domain.setting.Feature;

/**
 * Unit tests for {@link ProfileGrant}.
 * <p>
 * Branch enumeration (every arm exercised): {@code matches} covers the
 * null-feature arm, the key-mismatch arm, the option-mismatch arm and the
 * full-match arm of its short-circuit chain; {@code equals} covers identity,
 * null, foreign class, and a difference on each of the four business fields
 * plus the all-equal case.
 */
class ProfileGrantTest {

    /**
     * Builds a grant with the four business fields.
     *
     * @param key the feature key
     * @param option the option
     * @param requires whether validation is required
     * @param validatorId the validating profile id, or null
     * @return the grant
     */
    private ProfileGrant grant(String key, CrudOption option, boolean requires, Long validatorId) {
        return new ProfileGrant(key, option, requires, validatorId);
    }

    /**
     * {@code matches} is false when the feature is null (null-feature arm).
     */
    @Test
    void matchesFalseOnNullFeature() {
        assertFalse(grant("settings", CrudOption.VIEW, false, null).matches(null, CrudOption.VIEW));
    }

    /**
     * {@code matches} is false when the key differs (key-mismatch arm).
     */
    @Test
    void matchesFalseOnKeyMismatch() {
        assertFalse(grant("store", CrudOption.VIEW, false, null).matches(Feature.SETTINGS, CrudOption.VIEW));
    }

    /**
     * {@code matches} is false when the option differs (option-mismatch arm).
     */
    @Test
    void matchesFalseOnOptionMismatch() {
        assertFalse(grant("settings", CrudOption.VIEW, false, null).matches(Feature.SETTINGS, CrudOption.UPDATE));
    }

    /**
     * {@code matches} is true on the full match (all-match arm).
     */
    @Test
    void matchesTrueOnFullMatch() {
        assertTrue(grant("settings", CrudOption.VIEW, false, null).matches(Feature.SETTINGS, CrudOption.VIEW));
    }

    /**
     * The default constructor leaves an empty, unvalidated grant.
     */
    @Test
    void defaultConstructorLeavesEmptyGrant() {
        ProfileGrant empty = new ProfileGrant();
        assertFalse(empty.requiresValidation);
    }

    /**
     * {@code equals} is reflexive (identity arm).
     */
    @Test
    void equalsIsReflexive() {
        ProfileGrant one = grant("settings", CrudOption.VIEW, true, 5L);
        assertEquals(one, one);
    }

    /**
     * {@code equals} is false against null (null arm) — invoked on the grant so
     * the guard itself runs, not {@code Objects.equals}.
     */
    @Test
    void equalsFalseAgainstNull() {
        assertFalse(grant("settings", CrudOption.VIEW, false, null).equals(null));
    }

    /**
     * {@code equals} is false against a foreign class (class arm) — invoked on
     * the grant so its class guard runs.
     */
    @Test
    void equalsFalseAgainstForeignClass() {
        assertFalse(grant("settings", CrudOption.VIEW, false, null).equals("settings"));
    }

    /**
     * {@code equals} is true and hashes agree for two identical grants
     * (all-equal arm).
     */
    @Test
    void equalsTrueForIdenticalGrants() {
        ProfileGrant a = grant("settings", CrudOption.VIEW, true, 5L);
        ProfileGrant b = grant("settings", CrudOption.VIEW, true, 5L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    /**
     * {@code equals} distinguishes a differing feature key (key field arm).
     */
    @Test
    void equalsFalseOnDifferentKey() {
        assertNotEquals(grant("settings", CrudOption.VIEW, true, 5L),
                grant("store", CrudOption.VIEW, true, 5L));
    }

    /**
     * {@code equals} distinguishes a differing option (option field arm).
     */
    @Test
    void equalsFalseOnDifferentOption() {
        assertNotEquals(grant("settings", CrudOption.VIEW, true, 5L),
                grant("settings", CrudOption.UPDATE, true, 5L));
    }

    /**
     * {@code equals} distinguishes a differing validation flag (flag arm).
     */
    @Test
    void equalsFalseOnDifferentValidationFlag() {
        assertNotEquals(grant("settings", CrudOption.VIEW, true, 5L),
                grant("settings", CrudOption.VIEW, false, 5L));
    }

    /**
     * {@code equals} distinguishes a differing validator id (validator arm).
     */
    @Test
    void equalsFalseOnDifferentValidator() {
        assertNotEquals(grant("settings", CrudOption.VIEW, true, 5L),
                grant("settings", CrudOption.VIEW, true, 6L));
    }
}
