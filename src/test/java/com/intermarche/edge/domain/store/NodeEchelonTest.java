package com.intermarche.edge.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NodeEchelon} (BO-01-01-03), targeting 100% branch
 * coverage: a null and a blank number, an unknown point of sale, a point of
 * sale without enseigne, an enseigne unknown to the tree, a complete tree.
 */
class NodeEchelonTest {

    /**
     * Answers a finder with one row, or none.
     *
     * @param row the row, possibly null
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Object> query(Object row) {
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /**
     * Without a number nothing is known.
     */
    @Test
    void noNumberKnowsNothing() {
        NodeEchelon none = NodeEchelon.of(null);
        assertNull(none.pdv);
        assertNull(none.enseigne);
        assertNull(none.country);
        assertNull(NodeEchelon.of("  ").pdv);
    }

    /**
     * An unknown point of sale, or one without enseigne, knows only its number.
     */
    @Test
    void anUnknownPointOfSaleKnowsOnlyItsNumber() {
        Pdv bare = new Pdv();
        bare.pdvNumber = "07040";
        PanacheQuery<Object> none = query(null);
        PanacheQuery<Object> found = query(bare);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(none);
            NodeEchelon unknown = NodeEchelon.of(" 07039 ");
            assertEquals("07039", unknown.pdv);
            assertNull(unknown.enseigne);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(found);
            assertNull(NodeEchelon.of("07040").enseigne);
        }
    }

    /**
     * The enseigne and its country are read from the tree; an enseigne the
     * tree does not hold leaves the country unknown.
     */
    @Test
    void theTreeIsWalkedUp() {
        Pdv pdv = new Pdv();
        pdv.pdvNumber = "07039";
        pdv.enseigneCode = "ITM";
        Enseigne itm = new Enseigne();
        itm.code = "ITM";
        itm.countryCode = "FR";
        PanacheQuery<Object> pdvQuery = query(pdv);
        PanacheQuery<Object> itmQuery = query(itm);
        PanacheQuery<Object> none = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(org.mockito.ArgumentMatchers.eq("pdvNumber"),
                    any(Object[].class))).thenReturn(pdvQuery);
            panache.when(() -> PanacheEntityBase.find(org.mockito.ArgumentMatchers.eq("code"),
                    any(Object[].class))).thenReturn(itmQuery);
            NodeEchelon node = NodeEchelon.of("07039");
            assertEquals("ITM", node.enseigne);
            assertEquals("FR", node.country);
            assertEquals("PDV 07039 / enseigne ITM / pays FR", node.toString());
            panache.when(() -> PanacheEntityBase.find(org.mockito.ArgumentMatchers.eq("code"),
                    any(Object[].class))).thenReturn(none);
            assertNull(NodeEchelon.of("07039").country);
        }
    }
}
