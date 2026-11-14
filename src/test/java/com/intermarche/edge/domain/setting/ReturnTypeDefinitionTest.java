package com.intermarche.edge.domain.setting;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReturnTypeDefinition}, the return-type referential
 * (BO-03-13-01 to -04, -11, -12).
 *
 * <p>Branch enumeration: {@code findByCode} on null, blank and a real code;
 * the two list finders; the checksum moving with every business field.
 */
class ReturnTypeDefinitionTest {

    /**
     * A code nobody typed is looked up nowhere; a real one is trimmed and
     * looked up by code.
     */
    @Test
    @SuppressWarnings("unchecked")
    void findByCodeLooksUpTheTrimmedCode() {
        assertNull(ReturnTypeDefinition.findByCode(null));
        assertNull(ReturnTypeDefinition.findByCode("  "));
        ReturnTypeDefinition type = new ReturnTypeDefinition();
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(type);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "ECH")).thenReturn(query);
            assertSame(type, ReturnTypeDefinition.findByCode(" ECH "));
        }
    }

    /**
     * The two list finders ask for every type and for the active ones, both
     * in display order.
     */
    @Test
    void theListFindersOrderByRank() {
        ReturnTypeDefinition type = new ReturnTypeDefinition();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("order by displayOrder, code")).thenReturn(List.of(type));
            panache.when(() -> PanacheEntityBase.list("active = true order by displayOrder, code"))
                    .thenReturn(List.of());
            assertEquals(List.of(type), ReturnTypeDefinition.findAllOrdered());
            assertTrue(ReturnTypeDefinition.findActive().isEmpty());
        }
    }

    /**
     * The checksum moves with the business fields.
     */
    @Test
    void theChecksumMovesWithTheFields() {
        ReturnTypeDefinition type = new ReturnTypeDefinition();
        type.code = "ECH";
        type.label = "Échange";
        int before = type.getChecksum();
        type.depositReturn = true;
        assertNotEquals(before, type.getChecksum());
        assertTrue(type.active);
    }
}
