package com.intermarche.pos.domain.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Pdv}.
 * <p>
 * Branch enumeration (every arm exercised — 100%): {@link Pdv#isValidNumber}
 * covers the null arm, the wrong-length arm, the non-digit arm inside the loop
 * and the all-digits success; {@link Pdv#getChecksum} is exercised on two rows
 * differing in one field each so the hash is proven field-sensitive. Static
 * finders carry no branches and are left to the integration surface.
 */
class PdvTest {

    /**
     * A null candidate is not a valid number (null arm).
     */
    @Test
    void isValidNumberRejectsNull() {
        assertFalse(Pdv.isValidNumber(null));
    }

    /**
     * A candidate of the wrong length is rejected (length arm), both shorter
     * and longer than five.
     */
    @Test
    void isValidNumberRejectsWrongLength() {
        assertFalse(Pdv.isValidNumber("1234"));
        assertFalse(Pdv.isValidNumber("123456"));
    }

    /**
     * A five-character candidate carrying a non-digit is rejected (non-digit
     * arm inside the loop).
     */
    @Test
    void isValidNumberRejectsNonDigit() {
        assertFalse(Pdv.isValidNumber("12A45"));
    }

    /**
     * Exactly five digits is valid (loop completes, success arm), including a
     * leading zero.
     */
    @Test
    void isValidNumberAcceptsFiveDigits() {
        assertTrue(Pdv.isValidNumber("01234"));
    }

    /**
     * {@link Pdv#getChecksum} changes when any business field changes and is
     * stable for identical field sets.
     */
    @Test
    void checksumIsFieldSensitive() {
        Pdv base = pdv("12345", "Lyon", "IF", "AD1", true);
        Pdv same = pdv("12345", "Lyon", "IF", "AD1", true);
        assertEquals(base.getChecksum(), same.getChecksum());
        assertNotEquals(base.getChecksum(), pdv("54321", "Lyon", "IF", "AD1", true).getChecksum());
        assertNotEquals(base.getChecksum(), pdv("12345", "Paris", "IF", "AD1", true).getChecksum());
        assertNotEquals(base.getChecksum(), pdv("12345", "Lyon", "NF", "AD1", true).getChecksum());
        assertNotEquals(base.getChecksum(), pdv("12345", "Lyon", "IF", "AD2", true).getChecksum());
        assertNotEquals(base.getChecksum(), pdv("12345", "Lyon", "IF", "AD1", false).getChecksum());
    }

    /**
     * Builds a PDV with the given business fields.
     *
     * @param number the PDV number
     * @param name the display name
     * @param enseigne the enseigne code
     * @param adherent the adhérent code
     * @param active whether the PDV is active
     * @return the built PDV
     */
    private Pdv pdv(String number, String name, String enseigne, String adherent, boolean active) {
        Pdv pdv = new Pdv();
        pdv.pdvNumber = number;
        pdv.name = name;
        pdv.enseigneCode = enseigne;
        pdv.adherentCode = adherent;
        pdv.active = active;
        return pdv;
    }
}
