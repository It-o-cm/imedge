package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mockStatic;

/** Unit tests for {@link NodeIntegration}. */
class NodeIntegrationTest {

    /** BO-08-01-17: integrations are read per node and in organisation order, and replaced per node. */
    @Test
    void findersAndReplacement() {
        List<Object> rows = List.of(new NodeIntegration());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("nodeKey = ?1 order by domain", "K")).thenReturn(rows);
            panache.when(() -> PanacheEntityBase.list("order by pdvNumber, kind, nodeId, domain")).thenReturn(rows);
            panache.when(() -> PanacheEntityBase.delete("nodeKey", "K")).thenReturn(3L);
            assertSame(rows, NodeIntegration.listByNode("K"));
            assertSame(rows, NodeIntegration.listOrdered());
            assertEquals(3L, NodeIntegration.deleteByNode("K"));
        }
    }
}
