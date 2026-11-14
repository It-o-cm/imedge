package com.intermarche.edge.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.setting.ServiceProvider;
import com.intermarche.edge.service.fiscal.EInvoicingService;
import com.intermarche.edge.service.fiscal.EReportingService;
import com.intermarche.edge.service.fiscal.SaftStreamService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link FiscalApiResource} (BO-05-01-02, -03, -05): the SAF-T
 * data served at the flow behind a SAFT provider's key, and the statuses and
 * acknowledgements the platform pushes behind its own.
 */
class FiscalApiResourceTest {

    /**
     * Builds the resource with its services mocked.
     *
     * @return the resource
     */
    private FiscalApiResource resource() {
        FiscalApiResource resource = new FiscalApiResource();
        resource.saftStreamService = mock(SaftStreamService.class);
        resource.eInvoicingService = mock(EInvoicingService.class);
        resource.eReportingService = mock(EReportingService.class);
        resource.objectMapper = new ObjectMapper();
        when(resource.eInvoicingService.read(anyString())).thenAnswer(call -> tree(call.getArgument(0)));
        return resource;
    }

    /**
     * Reads a body as the service does: unreadable reads as empty.
     *
     * @param body the body
     * @return the tree
     */
    private static JsonNode tree(String body) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readTree(body);
        } catch (Exception unreadable) {
            return mapper.createObjectNode();
        }
    }

    /**
     * The documents since an instant are served with their SAF-T file, the
     * page bounded; the beginning when no instant is given.
     *
     * @throws Exception never, the answer is JSON
     */
    @Test
    void thedocumentsAreServedWithTheirFile() throws Exception {
        FiscalApiResource resource = resource();
        when(resource.saftStreamService.authorized("K1")).thenReturn(true);
        FiscalDocument document = new FiscalDocument();
        document.number = "FS 1";
        document.documentType = FiscalDocument.Type.FS;
        document.issuedAt = LocalDateTime.of(2026, 10, 24, 10, 0);
        document.atcud = "AT-1";
        document.terminalId = "C04";
        when(resource.saftStreamService.documentsSince(LocalDateTime.of(2026, 10, 24, 0, 0), 500))
                .thenReturn(List.<Object[]>of(new Object[] {document, "<AuditFile/>"}));
        Response response = resource.saftDocuments("Bearer K1", "2026-10-24T00:00", 9999);
        assertEquals(200, response.getStatus());
        JsonNode body = new ObjectMapper().readTree((String) response.getEntity());
        assertEquals("FS 1", body.get(0).get("number").asText());
        assertEquals("FS", body.get(0).get("type").asText());
        assertEquals("2026-10-24T10:00", body.get(0).get("issuedAt").asText());
        assertEquals("<AuditFile/>", body.get(0).get("saft").asText());
        resource.saftDocuments("bearer K1", " ", null);
        verify(resource.saftStreamService).documentsSince(LocalDateTime.of(1970, 1, 1, 0, 0), 100);
        resource.saftDocuments("Bearer K1", null, 0);
        verify(resource.saftStreamService, org.mockito.Mockito.times(2))
                .documentsSince(LocalDateTime.of(1970, 1, 1, 0, 0), 100);
        resource.saftDocuments("Bearer K1", null, 7);
        verify(resource.saftStreamService).documentsSince(LocalDateTime.of(1970, 1, 1, 0, 0), 7);
    }

    /**
     * Without a valid key the answer is 401; an unreadable instant is 400.
     */
    @Test
    void akeyAndAReadableInstantAreRequired() {
        FiscalApiResource resource = resource();
        assertEquals(401, resource.saftDocuments(null, null, null).getStatus());
        when(resource.saftStreamService.authorized("K1")).thenReturn(true);
        Response bad = resource.saftDocuments("Bearer K1", "hier", null);
        assertEquals(400, bad.getStatus());
        assertTrue(((String) bad.getEntity()).contains("Instant illisible : hier"));
    }

    /**
     * Builds the platform.
     *
     * @return the provider
     */
    private ServiceProvider platform() {
        ServiceProvider provider = new ServiceProvider();
        provider.authKey = "PK";
        return provider;
    }

    /**
     * A status pushed by the platform is applied by the invoice's identifier
     * or number; an unknown invoice is 404, an unknown status 400, a missing
     * key 401.
     */
    @Test
    void apushedStatusIsApplied() {
        FiscalApiResource resource = resource();
        assertEquals(401, resource.einvoicingStatus("Bearer PK", "{}").getStatus());
        when(resource.eInvoicingService.platform()).thenReturn(platform());
        assertEquals(401, resource.einvoicingStatus("Bearer XX", "{}").getStatus());
        assertEquals(401, resource.einvoicingStatus(null, "{}").getStatus());
        when(resource.eInvoicingService.receive("P-1", "205", "ok")).thenReturn(null);
        Response applied = resource.einvoicingStatus("Bearer PK", "{\"id\":\"P-1\",\"status\":\"205\",\"reason\":\"ok\"}");
        assertEquals(200, applied.getStatus());
        assertEquals("{\"applied\":true}", applied.getEntity());
        when(resource.eInvoicingService.receive(eq("F-1"), eq("X"), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn("Statut inconnu.");
        assertEquals(400, resource.einvoicingStatus("Bearer PK", "{\"number\":\"F-1\",\"status\":\"X\"}").getStatus());
        when(resource.eInvoicingService.receive(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn("Facture électronique inconnue.");
        Response unknown = resource.einvoicingStatus("Bearer PK", "pas du json");
        assertEquals(404, unknown.getStatus());
        assertTrue(((String) unknown.getEntity()).contains("Facture électronique inconnue."));
    }

    /**
     * An acknowledgement pushed by the platform is applied; a missing key is
     * 401.
     */
    @Test
    void apushedAcknowledgementIsApplied() {
        FiscalApiResource resource = resource();
        when(resource.eInvoicingService.platform()).thenReturn(platform());
        assertEquals(401, resource.ereportingAck("Basic PK", "{}").getStatus());
        when(resource.eReportingService.receive("A-1", "ACCEPTED", "ok")).thenReturn(null);
        assertEquals(200, resource.ereportingAck("Bearer PK",
                "{\"id\":\"A-1\",\"status\":\"ACCEPTED\",\"message\":\"ok\"}").getStatus());
    }

    /**
     * A bearer key is read case ignored; another scheme, an empty key or no
     * header give none.
     */
    @Test
    void thebearerKeyIsRead() {
        assertEquals("K", FiscalApiResource.bearer("BEARER  K "));
        assertNull(FiscalApiResource.bearer("Bearer   "));
        assertNull(FiscalApiResource.bearer("Basic K"));
        assertNull(FiscalApiResource.bearer("Bear"));
        assertNull(FiscalApiResource.bearer(null));
        assertFalse(resource().platformKey("PK"));
    }
}
