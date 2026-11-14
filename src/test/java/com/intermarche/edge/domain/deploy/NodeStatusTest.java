package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Unit tests for {@link NodeStatus}. */
class NodeStatusTest {

    /** BO-08-04-15: the finders read by key, in organisation order, and by kind. */
    @Test
    @SuppressWarnings("unchecked")
    void findersReadTheReports() {
        NodeStatus row = new NodeStatus();
        List<Object> rows = List.of(row);
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("nodeKey", "REGISTER:07039:C04")).thenReturn(query);
            panache.when(() -> PanacheEntityBase.list("order by pdvNumber, kind, nodeId")).thenReturn(rows);
            panache.when(() -> PanacheEntityBase.list("kind = ?1 order by pdvNumber, nodeId", "REGISTER"))
                    .thenReturn(rows);
            assertSame(row, NodeStatus.findByKey("REGISTER:07039:C04"));
            assertSame(rows, NodeStatus.listOrdered());
            assertSame(rows, NodeStatus.listByKind("REGISTER"));
        }
    }

    /** BO-08-04-15: a node is online while its last report is within the tolerance. */
    @Test
    void onlineFollowsTheLastReport() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 24, 12, 0);
        NodeStatus status = new NodeStatus();
        assertFalse(status.online(now, 180));
        status.reportedAt = now.minusSeconds(180);
        assertTrue(status.online(now, 180));
        status.reportedAt = now.minusSeconds(181);
        assertFalse(status.online(now, 180));
        assertTrue(new NodeStatus().appOk && new NodeStatus().peripheralOk && new NodeStatus().syncOk);
    }
}
