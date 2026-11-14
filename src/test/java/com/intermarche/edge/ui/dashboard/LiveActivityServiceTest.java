package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.service.PosSettingsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link LiveActivityService} (BO-04-03-07).
 */
class LiveActivityServiceTest {

    /**
     * Builds a service over a query answering the given events.
     *
     * @param types the administered types
     * @param events the events answered
     * @return the service
     */
    @SuppressWarnings("unchecked")
    private LiveActivityService service(List<String> types, List<TechnicalEvent> events) {
        LiveActivityService service = new LiveActivityService();
        service.entityManager = mock(EntityManager.class);
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.liveEventTypes()).thenReturn(types);
        TypedQuery<TechnicalEvent> query = mock(TypedQuery.class);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(events);
        when(service.entityManager.createQuery(anyString(), eq(TechnicalEvent.class))).thenReturn(query);
        return service;
    }

    /**
     * The operations of the administered types after the last one, for one
     * register, formatted; unknown type names are ignored.
     */
    @Test
    void readsTheOperationsAfterTheLastOne() {
        TechnicalEvent sale = new TechnicalEvent();
        sale.id = 12L;
        sale.terminalId = "C04";
        sale.operatorBadgeId = "12341234";
        sale.eventType = TechnicalEvent.EventType.TICKET_CLOSED;
        sale.eventDate = LocalDateTime.of(2026, 10, 25, 10, 5, 9);
        sale.amount = new BigDecimal("12.5");
        sale.ticketNumber = "T1";
        sale.detail = "T1";
        LiveActivityService service = service(List.of("ticket_closed", "NOPE"), List.of(sale));
        List<Map<String, Object>> rows = service.after(10L, " C04 ");
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals(12L, row.get("id"));
        assertEquals("10:05:09", row.get("time"));
        assertEquals("12,50", row.get("amount"));
        assertEquals("T1", row.get("ticket"));
        assertEquals("12341234", row.get("operator"));
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(service.entityManager).createQuery(jpql.capture(), eq(TechnicalEvent.class));
        assertTrue(jpql.getValue().contains("e.eventType in :types"));
        assertTrue(jpql.getValue().contains("e.terminalId = :terminal"));
    }

    /**
     * Without types or register every operation is read from the first one;
     * the missing facts are written empty.
     */
    @Test
    void readsEverythingByDefault() {
        TechnicalEvent bare = new TechnicalEvent();
        bare.id = 1L;
        bare.terminalId = "C05";
        bare.eventType = TechnicalEvent.EventType.DRAWER_OPENED;
        bare.eventDate = LocalDateTime.of(2026, 10, 25, 10, 0);
        LiveActivityService service = service(List.of(), List.of(bare));
        Map<String, Object> row = service.after(null, null).get(0);
        assertEquals("", row.get("amount"));
        assertEquals("", row.get("operator"));
        assertEquals("", row.get("ticket"));
        assertEquals("", row.get("detail"));
        assertEquals(1, service.after(null, "  ").size());
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(service.entityManager, org.mockito.Mockito.times(2)).createQuery(jpql.capture(), eq(TechnicalEvent.class));
        assertFalse(jpql.getValue().contains(":types"));
        assertFalse(jpql.getValue().contains(":terminal"));
    }
}
