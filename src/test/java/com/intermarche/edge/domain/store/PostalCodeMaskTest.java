package com.intermarche.edge.domain.store;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PostalCodeMask}: a code shaped to the administered
 * mask when its digits fill the mask's slots exactly, kept trimmed and
 * upper-cased otherwise, an empty text for no code.
 */
class PostalCodeMaskTest {

    /**
     * No code reads empty; no mask, or a blank one, keeps the code trimmed and
     * upper-cased.
     */
    @Test
    void withoutCodeOrMask() {
        assertEquals("", PostalCodeMask.normalize(null, "CCCCC"));
        assertEquals("B1000", PostalCodeMask.normalize(" b1000 ", null));
        assertEquals("B1000", PostalCodeMask.normalize("b1000", " "));
    }

    /**
     * Digits filling the slots exactly are shaped to the mask, its literal
     * characters kept.
     */
    @Test
    void theDigitsAreShaped() {
        assertEquals("75001", PostalCodeMask.normalize("75 001", "CCCCC"));
        assertEquals("1000-123", PostalCodeMask.normalize("1000123", "CCCC-CCC"));
    }

    /**
     * Too few or too many digits, or letters among them, keep the code as
     * keyed.
     */
    @Test
    void aCodeNotFittingTheMaskIsKept() {
        assertEquals("7500", PostalCodeMask.normalize("7500", "CCCCC"));
        assertEquals("750012", PostalCodeMask.normalize("750012", "CCCCC"));
        assertEquals("B7500", PostalCodeMask.normalize("b7500", "CCCC"));
    }

    /**
     * The class is not instantiable from outside.
     *
     * @throws Exception never
     */
    @Test
    void theClassIsAHolder() throws Exception {
        Constructor<PostalCodeMask> constructor = PostalCodeMask.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}
