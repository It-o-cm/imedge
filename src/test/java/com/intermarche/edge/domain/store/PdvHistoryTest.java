package com.intermarche.edge.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PdvHistory} (BO-02-05-01/02/05): its finders resolve
 * to {@link PanacheEntityBase} and are intercepted by a static mock.
 */
class PdvHistoryTest {

    /**
     * The changes of a point of sale are listed most recent first.
     */
    @Test
    void theChangesOfAPointOfSaleAreListed() {
        PdvHistory change = new PdvHistory();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PdvHistory.list("pdvId = ?1 order by changedAt desc, id desc", 4L))
                    .thenReturn(List.of(change));
            assertEquals(List.of(change), PdvHistory.listForPdv(4L));
        }
    }

    /**
     * The former holder of a number is the point of sale that left it, none
     * for a null number or a number nobody left.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theFormerHolderOfANumberIsFound() {
        PdvHistory change = new PdvHistory();
        change.pdvId = 7L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertNull(PdvHistory.formerHolderOf(null));
            PanacheQuery<PdvHistory> found = mock(PanacheQuery.class);
            when(found.firstResult()).thenReturn(change);
            PanacheQuery<PdvHistory> none = mock(PanacheQuery.class);
            panache.when(() -> PdvHistory.find("kind = ?1 and oldValue = ?2", PdvHistory.Kind.NUMBER, "01011"))
                    .thenReturn(found);
            panache.when(() -> PdvHistory.find("kind = ?1 and oldValue = ?2", PdvHistory.Kind.NUMBER, "02022"))
                    .thenReturn(none);
            assertEquals(7L, PdvHistory.formerHolderOf("01011"));
            assertNull(PdvHistory.formerHolderOf("02022"));
        }
    }
}
