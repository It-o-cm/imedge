package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Unit tests for {@link NodeVersion}. */
class NodeVersionTest {

    /** BO-08-03-07: the row of the node is the first one found, and a fresh row starts idle. */
    @Test
    @SuppressWarnings("unchecked")
    void currentReadsTheSingleRow() {
        NodeVersion row = new NodeVersion();
        assertEquals(NodeVersion.State.IDLE, row.state);
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::findAll).thenReturn(query);
            assertSame(row, NodeVersion.current());
        }
    }
}
