package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Unit tests for {@link DeploymentTarget}. */
class DeploymentTargetTest {

    /** BO-08-03-02/03: targets are read per lot, per node, and by lot and node. */
    @Test
    @SuppressWarnings("unchecked")
    void findersReadTheTargets() {
        DeploymentTarget row = new DeploymentTarget();
        assertEquals(DeploymentTarget.State.PENDING, row.state);
        List<Object> rows = List.of(row);
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("lotId = ?1 order by pdvNumber, kind, nodeId", 4L)).thenReturn(rows);
            panache.when(() -> PanacheEntityBase.list("nodeKey", "K")).thenReturn(rows);
            panache.when(() -> PanacheEntityBase.find("lotId = ?1 and nodeKey = ?2", 4L, "K")).thenReturn(query);
            assertSame(rows, DeploymentTarget.listByLot(4L));
            assertSame(rows, DeploymentTarget.listByNode("K"));
            assertSame(row, DeploymentTarget.findKey(4L, "K"));
        }
    }
}
