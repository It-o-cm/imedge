package com.intermarche.edge.domain.fiscal;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link SaftTransmission} (BO-05-01-05): the identity of a row
 * and the finders.
 */
class SaftTransmissionTest {

    /**
     * A row is identified by its endpoint and its document, and found through
     * Panache.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aRowIsIdentifiedAndFound() {
        assertEquals("AT|FS C04/1", SaftTransmission.uidOf("AT", "FS C04/1"));
        SaftTransmission row = new SaftTransmission();
        assertEquals(SaftTransmission.Status.PENDING, row.status);
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 9, 0);
        PanacheQuery<SaftTransmission> one = mock(PanacheQuery.class);
        when(one.firstResult()).thenReturn(row);
        PanacheQuery<SaftTransmission> many = mock(PanacheQuery.class);
        when(many.page(0, 4)).thenReturn(many);
        when(many.list()).thenReturn(List.of(row));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SaftTransmission.find("uid", "AT|1")).thenReturn(one);
            panache.when(() -> SaftTransmission.find("status in ?1 and (nextAttemptAt is null or nextAttemptAt <= ?2) "
                    + "order by id", List.of(SaftTransmission.Status.PENDING, SaftTransmission.Status.FAILED), now))
                    .thenReturn(many);
            panache.when(() -> SaftTransmission.find("order by id desc")).thenReturn(many);
            panache.when(() -> SaftTransmission.count("status", SaftTransmission.Status.FAILED)).thenReturn(7L);
            assertSame(row, SaftTransmission.findByUid("AT|1"));
            assertEquals(List.of(row), SaftTransmission.due(now, 4));
            assertEquals(List.of(row), SaftTransmission.latest(4));
            assertEquals(7L, SaftTransmission.countIn(SaftTransmission.Status.FAILED));
        }
    }
}
