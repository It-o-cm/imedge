package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.Nomenclature;
import com.intermarche.edge.domain.catalog.NomenclatureLevel;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link NomenclatureLookup}: the scheme found or absent, the
 * rayon level named or not, the ancestor of a code, the label read once.
 */
class NomenclatureLookupTest {

    /**
     * Over the store's scheme: the rayon is the level named so, an ancestor is
     * the code cut to its level, a shorter or unknown level keeps the code, a
     * null code reads empty, a label is read and an unknown node reads empty.
     */
    @Test
    void overTheStoresScheme() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            NomenclatureFixture.scheme(panache, Map.of("1002", "BOUCHERIE"));
            NomenclatureLookup lookup = NomenclatureLookup.current();
            assertEquals(3, lookup.levels().size());
            assertEquals(1, lookup.rayonRank());
            assertEquals("1002", lookup.ancestor("10020200", 1));
            assertEquals("10", lookup.ancestor("10", 1));
            assertEquals("10020200", lookup.ancestor("10020200", 9));
            assertEquals("", lookup.ancestor(null, 1));
            assertEquals("EPICERIE", lookup.ancestor("EPICERIE", 1));
            assertEquals("BOUCHERIE", lookup.label("1002"));
            assertEquals("BOUCHERIE", lookup.label("1002"));
            assertEquals("", lookup.label("9999"));
            assertEquals("", lookup.label(""));
        }
    }

    /**
     * An inactive scheme is skipped and without scheme the codes are kept;
     * a scheme naming no rayon level takes the second; a node without label
     * reads empty.
     */
    @Test
    void withoutSchemeOrRayon() {
        Nomenclature inactive = new Nomenclature();
        inactive.active = false;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("order by code")).thenReturn(List.of(inactive));
            NomenclatureLookup lookup = NomenclatureLookup.current();
            assertTrue(lookup.levels().isEmpty());
            assertEquals(1, lookup.rayonRank());
            assertEquals("10020200", lookup.ancestor("10020200", 1));
        }
        NomenclatureLevel unnamed = new NomenclatureLevel();
        unnamed.rank = 0;
        NomenclatureLevel other = new NomenclatureLevel();
        other.rank = 1;
        other.label = "Famille";
        Nomenclature scheme = new Nomenclature();
        scheme.id = 2L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("nomenclature.id = ?1 order by rank", 2L))
                    .thenReturn(List.of(unnamed, other));
            assertEquals(1, new NomenclatureLookup(scheme).rayonRank());
            com.intermarche.edge.domain.catalog.ProductFamily bare = new com.intermarche.edge.domain.catalog.ProductFamily();
            @SuppressWarnings("unchecked")
            io.quarkus.hibernate.orm.panache.PanacheQuery<Object> query =
                    org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            org.mockito.Mockito.when(query.firstResult()).thenReturn(bare);
            panache.when(() -> PanacheEntityBase.find("code", "X")).thenReturn(query);
            assertEquals("", new NomenclatureLookup(null).label("X"));
        }
    }
}
