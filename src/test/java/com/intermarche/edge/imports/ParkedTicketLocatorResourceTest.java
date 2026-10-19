package com.intermarche.edge.imports;

import com.intermarche.edge.domain.sale.Ticket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ParkedTicketLocatorResource}.
 * <p>
 * Plain JUnit 5 + Mockito: the gate fields are set directly and the parked
 * finder resolves to {@link PanacheEntityBase}, intercepted with mockStatic.
 * Every gate arm and both the found and not-found outcomes are exercised.
 */
class ParkedTicketLocatorResourceTest {

    /** The finder clause the locator uses. */
    private static final String CLAUSE = "ticketNumber = ?1 and status = ?2";

    /**
     * Builds a store-role locator with the given token.
     *
     * @param token the configured shared token
     * @return the resource
     */
    private ParkedTicketLocatorResource storeResource(Optional<String> token) {
        ParkedTicketLocatorResource resource = new ParkedTicketLocatorResource();
        resource.role = "store";
        resource.token = token;
        return resource;
    }

    /**
     * A node that is not a store refuses the lookup (403).
     */
    @Test
    void offRoleIsForbidden() {
        ParkedTicketLocatorResource resource = new ParkedTicketLocatorResource();
        resource.role = "register";
        resource.token = Optional.empty();
        assertEquals(403, resource.locate("C04-1", null).getStatus());
    }

    /**
     * A configured token the caller does not present is rejected (401).
     */
    @Test
    void badTokenIsUnauthorized() {
        ParkedTicketLocatorResource resource = storeResource(Optional.of("secret"));
        assertEquals(401, resource.locate("C04-1", "wrong").getStatus());
    }

    /**
     * A number carrying no parked ticket answers 404 (both the blank and the
     * no-row arm).
     */
    @Test
    void noParkedTicketIsNotFound() {
        ParkedTicketLocatorResource resource = storeResource(Optional.empty());
        assertEquals(404, resource.locate("   ", null).getStatus());
        PanacheQuery<Ticket> absent = mock(PanacheQuery.class);
        when(absent.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Ticket.find(CLAUSE, "C04-1", Ticket.TicketStatus.PARKED)).thenReturn(absent);
            assertEquals(404, resource.locate("C04-1", null).getStatus());
        }
    }

    /**
     * A parked ticket is located: the node answers which register holds it.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aParkedTicketIsLocated() {
        ParkedTicketLocatorResource resource = storeResource(Optional.empty());
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C04-1";
        ticket.terminalId = "C04";
        ticket.status = Ticket.TicketStatus.PARKED;
        ticket.grandTotal = new BigDecimal("12.00");
        PanacheQuery<Ticket> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(ticket);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Ticket.find(CLAUSE, "C04-1", Ticket.TicketStatus.PARKED)).thenReturn(found);
            var response = resource.locate(" C04-1 ", null);
            assertEquals(200, response.getStatus());
            Map<String, Object> json = (Map<String, Object>) response.getEntity();
            assertEquals("C04-1", json.get("ticketNumber"));
            assertEquals("C04", json.get("terminalId"));
            assertEquals("PARKED", json.get("status"));
        }
    }
}
