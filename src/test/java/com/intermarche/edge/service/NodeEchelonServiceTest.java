package com.intermarche.edge.service;

import com.intermarche.edge.domain.store.NodeEchelon;
import com.intermarche.edge.domain.store.Store;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NodeEchelonService} (BO-01-01-03), targeting 100%
 * branch coverage: a commissioned number, an absent configuration (null
 * field and empty optional), a blank number, a store record or none.
 */
class NodeEchelonServiceTest {

    /**
     * Makes the store finder answer one record, or none.
     *
     * @param panache the static mock of the Panache base
     * @param store the record, possibly null
     */
    @SuppressWarnings("unchecked")
    private void store(MockedStatic<PanacheEntityBase> panache, Store store) {
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(store);
        panache.when(PanacheEntityBase::findAll).thenReturn(query);
    }

    /**
     * The commissioned number wins, trimmed.
     */
    @Test
    void theCommissionedNumberWins() {
        NodeEchelonService service = new NodeEchelonService();
        service.pdvNumber = Optional.of(" 07039 ");
        assertEquals("07039", service.currentPdv());
    }

    /**
     * Without a commissioned number the store record's code is used, or nothing.
     */
    @Test
    void theStoreCodeIsTheFallback() {
        NodeEchelonService service = new NodeEchelonService();
        Store record = new Store();
        record.code = "0101";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            store(panache, record);
            assertEquals("0101", service.currentPdv());
            service.pdvNumber = Optional.of("  ");
            assertEquals("0101", service.currentPdv());
            service.pdvNumber = Optional.empty();
            store(panache, null);
            assertNull(service.currentPdv());
            NodeEchelon none = service.current();
            assertNull(none.pdv);
        }
    }
}
