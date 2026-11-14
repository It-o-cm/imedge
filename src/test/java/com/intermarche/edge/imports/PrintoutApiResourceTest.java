package com.intermarche.edge.imports;

import com.intermarche.edge.domain.sale.PrintedTicket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PrintoutApiResource}, targeting 100% branch coverage.
 * <p>
 * The printed tickets are read through a static Panache mock. Branch
 * enumeration: off-role, bad token, no token configured, blank token configured,
 * every audience arm (absent, blank, named, unknown), every page-size arm (absent,
 * non-positive, within, above the maximum), the cursor absent or given, and a
 * row with and without time and text.
 */
class PrintoutApiResourceTest {

    /**
     * A store-role resource with the given token.
     *
     * @param token the configured token
     * @return the resource
     */
    private PrintoutApiResource resource(Optional<String> token) {
        PrintoutApiResource resource = new PrintoutApiResource();
        resource.role = "store";
        resource.token = token;
        return resource;
    }

    /**
     * Stubs the page the finder answers.
     *
     * @param panache the static mock
     * @param after the cursor expected
     * @param audience the audience expected
     * @param limit the page size expected
     * @param rows the rows answered
     */
    @SuppressWarnings("unchecked")
    private void page(MockedStatic<PanacheEntityBase> panache, long after, PrintedTicket.Audience audience,
                      int limit, List<PrintedTicket> rows) {
        PanacheQuery<PrintedTicket> query = mock(PanacheQuery.class);
        PanacheQuery<PrintedTicket> paged = mock(PanacheQuery.class);
        when(query.page(0, limit)).thenReturn(paged);
        when(paged.list()).thenReturn(rows);
        panache.when(() -> PrintedTicket.find("id > ?1 and audience = ?2 order by id", after, audience))
                .thenReturn(query);
    }

    /**
     * The machine gates: a node without the store role and a bad token are refused.
     */
    @Test
    void theGates() {
        PrintoutApiResource register = resource(Optional.empty());
        register.role = "register";
        assertEquals(403, register.list(null, null, null, null).getStatus());
        assertEquals(401, resource(Optional.of("secret")).list("wrong", null, null, null).getStatus());
        assertEquals(400, resource(Optional.of(" ")).list(null, null, "NOBODY", null).getStatus());
    }

    /**
     * A first read serves the customer's tickets, one hundred at a time, the text
     * encoded in Base64URL.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aFirstReadServesTheCustomersTickets() {
        PrintedTicket printout = new PrintedTicket();
        printout.id = 3L;
        printout.terminalId = "C04";
        printout.source = "TicketPrinterService.printTicket";
        printout.audience = PrintedTicket.Audience.CUSTOMER;
        printout.issuedAt = LocalDateTime.of(2026, 10, 24, 9, 0);
        printout.content = "TOTAL 3,72 € ?/";
        PrintedTicket bare = new PrintedTicket();
        bare.id = 4L;
        bare.audience = PrintedTicket.Audience.CUSTOMER;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            page(panache, 0L, PrintedTicket.Audience.CUSTOMER, 100, List.of(printout, bare));
            Response response = resource(Optional.of("secret")).list("secret", null, null, null);
            List<Map<String, Object>> body = (List<Map<String, Object>>) response.getEntity();
            assertEquals(2, body.size());
            assertEquals(3L, body.get(0).get("id"));
            assertEquals("C04", body.get(0).get("terminalId"));
            assertEquals("2026-10-24T09:00", body.get(0).get("issuedAt"));
            assertEquals("TicketPrinterService.printTicket", body.get(0).get("source"));
            assertEquals("CUSTOMER", body.get(0).get("audience"));
            assertEquals("base64url", body.get(0).get("encoding"));
            assertEquals("TOTAL 3,72 € ?/", new String(Base64.getUrlDecoder()
                    .decode((String) body.get(0).get("content")), StandardCharsets.UTF_8));
            assertNull(body.get(1).get("issuedAt"));
            assertEquals("", body.get(1).get("content"));
        }
    }

    /**
     * A reader names the audience it reads and its cursor; its page size is
     * bounded, a non-positive one reading as the default.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aReaderChoosesItsAudienceCursorAndPage() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            page(panache, 10L, PrintedTicket.Audience.STORE, 500, List.of());
            page(panache, 10L, PrintedTicket.Audience.TRAINING, 20, List.of());
            page(panache, 10L, PrintedTicket.Audience.CUSTOMER, 100, List.of());
            PrintoutApiResource resource = resource(Optional.empty());
            assertEquals(List.of(), resource.list(null, 10L, " STORE ", 900).getEntity());
            assertEquals(List.of(), resource.list(null, 10L, "TRAINING", 20).getEntity());
            assertEquals(List.of(), resource.list(null, 10L, " ", 0).getEntity());
        }
    }
}
