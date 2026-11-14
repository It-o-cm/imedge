package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mockStatic;

/** Unit tests for {@link NodeInventoryItem}. */
class NodeInventoryItemTest {

    /** BO-08-04-20: the inventory is read whole and per node, and replaced per node. */
    @Test
    void findersAndReplacement() {
        List<Object> rows = List.of(new NodeInventoryItem());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("order by pdvNumber, nodeId, category, name")).thenReturn(rows);
            panache.when(() -> PanacheEntityBase.list("nodeKey = ?1 order by category, name", "K")).thenReturn(rows);
            panache.when(() -> PanacheEntityBase.delete("nodeKey", "K")).thenReturn(2L);
            assertSame(rows, NodeInventoryItem.listOrdered());
            assertSame(rows, NodeInventoryItem.listByNode("K"));
            assertEquals(2L, NodeInventoryItem.deleteByNode("K"));
        }
    }
}
