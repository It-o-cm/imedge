package com.intermarche.edge.domain.payment;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PrepaidCardType}, the types of prepaid card
 * (BO-03-02-37).
 *
 * <p>Branch enumeration: {@code findByCode} on null, blank and a real code;
 * {@code findFor} on null, blank, no match and the longest prefix winning;
 * {@code matchLength} on null ranges, an empty prefix, a foreign prefix, a
 * length that matches, one that does not and one that cannot be read; the
 * protocol test; the checksum.
 */
class PrepaidCardTypeTest {

    /**
     * Builds an active type over ranges.
     *
     * @param code the type code
     * @param ranges the written ranges
     * @return the type
     */
    private PrepaidCardType type(String code, String ranges) {
        PrepaidCardType type = new PrepaidCardType();
        type.code = code;
        type.label = code;
        type.ranges = ranges;
        return type;
    }

    /**
     * A code nobody typed is looked up nowhere; a real one is trimmed.
     */
    @Test
    @SuppressWarnings("unchecked")
    void findByCodeLooksUpTheTrimmedCode() {
        assertNull(PrepaidCardType.findByCode(null));
        assertNull(PrepaidCardType.findByCode(" "));
        PrepaidCardType type = type("GIFT", "6299");
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(type);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "GIFT")).thenReturn(query);
            panache.when(() -> PanacheEntityBase.list("order by displayOrder, code")).thenReturn(List.of(type));
            assertSame(type, PrepaidCardType.findByCode(" GIFT "));
            assertEquals(List.of(type), PrepaidCardType.findAllOrdered());
        }
    }

    /**
     * A number belongs to the active type whose matching prefix is the
     * longest; nothing typed and nothing matching name no type.
     */
    @Test
    void findForPrefersTheLongestPrefix() {
        PrepaidCardType wide = type("WIDE", "62");
        PrepaidCardType narrow = type("NARROW", "6299:16");
        PrepaidCardType other = type("OTHER", "296");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("active = true order by displayOrder, code"))
                    .thenReturn(List.of(wide, narrow, other));
            assertSame(narrow, PrepaidCardType.findFor(" 6299000000000017 "));
            assertSame(wide, PrepaidCardType.findFor("6200"));
            assertNull(PrepaidCardType.findFor("1234"));
            assertNull(PrepaidCardType.findFor(null));
            assertNull(PrepaidCardType.findFor("  "));
        }
    }

    /**
     * Every shape of range entry: an empty prefix and a foreign prefix never
     * match, a length must be equal and readable, and no length means any.
     */
    @Test
    void matchLengthReadsEveryEntry() {
        assertEquals(-1, type("A", null).matchLength("6299"));
        assertEquals(-1, type("A", " ;:4").matchLength("6299"));
        assertEquals(-1, type("A", "7").matchLength("6299"));
        assertEquals(2, type("A", "62:4").matchLength("6299"));
        assertEquals(-1, type("A", "62:5").matchLength("6299"));
        assertEquals(-1, type("A", "62:x").matchLength("6299"));
        assertEquals(3, type("A", "6;629").matchLength("6299"));
    }

    /**
     * The protocol test, the defaults and the checksum.
     */
    @Test
    void theProtocolAndTheChecksum() {
        PrepaidCardType type = type("GIFT", "6299");
        assertTrue(type.isNexo());
        assertTrue(type.paymentAllowed && type.balanceAllowed && type.activationAllowed && type.active);
        int before = type.getChecksum();
        type.protocol = PrepaidCardType.PROTOCOL_LOCAL;
        assertFalse(type.isNexo());
        assertNotEquals(before, type.getChecksum());
        assertEquals(List.of("NEXO", "LOCAL"), PrepaidCardType.PROTOCOLS);
    }
}
