package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Unit tests for {@link DeploymentSelection}. */
class DeploymentSelectionTest {

    /** BO-08-03-02: a saved selection is found by name, and the selections are listed by name. */
    @Test
    @SuppressWarnings("unchecked")
    void findersReadTheSelections() {
        DeploymentSelection row = new DeploymentSelection();
        List<Object> rows = List.of(row);
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("name", "pilotes")).thenReturn(query);
            panache.when(() -> PanacheEntityBase.list("order by name")).thenReturn(rows);
            assertSame(row, DeploymentSelection.findByName("pilotes"));
            assertSame(rows, DeploymentSelection.listOrdered());
        }
    }
}
