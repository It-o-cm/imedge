package com.intermarche.edge.domain.sale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TaxIdRules}, the legal construction of a Portuguese
 * NIF (LC-08-06-04). Every leg of every guard has its case: the shape of the
 * entry (null, digits, PT prefix, foreign, empty), the length and digit test,
 * the single first digit and the two-digit prefixes, the two arms of the check
 * digit (remainder below two, and the subtraction) and the store's choice to
 * check or not.
 */
class TaxIdRulesTest {

    /** An entry of digits, prefixed or not by PT, is Portuguese; anything else is not. */
    @Test
    void portugueseDigitsRecognisesThePortugueseShape() {
        assertNull(TaxIdRules.portugueseDigits(null));
        assertEquals("123456789", TaxIdRules.portugueseDigits("123456789"));
        assertEquals("123456789", TaxIdRules.portugueseDigits("PT123456789"));
        assertEquals("12", TaxIdRules.portugueseDigits("12"));
        assertNull(TaxIdRules.portugueseDigits("ESB12345678"));
        assertNull(TaxIdRules.portugueseDigits("PT"));
        assertNull(TaxIdRules.portugueseDigits(""));
        assertNull(TaxIdRules.portugueseDigits("FR 12"));
    }

    /** Valid NIFs: a single first digit, a two-digit prefix, and both arms of the check digit. */
    @Test
    void isValidPortugueseAcceptsLegallyConstructedNifs() {
        assertTrue(TaxIdRules.isValidPortuguese("123456789"));
        assertTrue(TaxIdRules.isValidPortuguese("451234561"));
        assertTrue(TaxIdRules.isValidPortuguese("999999990"));
        assertTrue(TaxIdRules.isValidPortuguese("500000000"));
        assertTrue(TaxIdRules.isValidPortuguese("700000003"));
    }

    /** Invalid NIFs: no value, a wrong length, a letter, an illegal prefix, a wrong check digit. */
    @Test
    void isValidPortugueseRefusesEveryBrokenRule() {
        assertFalse(TaxIdRules.isValidPortuguese(null));
        assertFalse(TaxIdRules.isValidPortuguese("12345678"));
        assertFalse(TaxIdRules.isValidPortuguese("1234567890"));
        assertFalse(TaxIdRules.isValidPortuguese("12345678A"));
        assertFalse(TaxIdRules.isValidPortuguese("412345675"));
        assertFalse(TaxIdRules.isValidPortuguese("123456780"));
    }

    /** Without the store's check everything passes; with it only a malformed Portuguese NIF fails. */
    @Test
    void acceptableFollowsTheStoresChoice() {
        assertTrue(TaxIdRules.acceptable("123", false));
        assertTrue(TaxIdRules.acceptable("ESB12345678", true));
        assertTrue(TaxIdRules.acceptable("", true));
        assertTrue(TaxIdRules.acceptable("PT123456789", true));
        assertFalse(TaxIdRules.acceptable("123", true));
        assertFalse(TaxIdRules.acceptable("PT123456780", true));
    }
}
