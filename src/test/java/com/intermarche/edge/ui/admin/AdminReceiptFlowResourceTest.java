package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.safe.ReceiptFlow;
import com.intermarche.edge.imports.SafeReceiptCsvResource;
import com.intermarche.edge.service.ReceiptFlowService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AdminReceiptFlowResource} (BO-05-02-03): the flows
 * listed with their defaults, a flow declared or corrected, and a file
 * integrated by hand through the same importer as the feed door.
 */
class AdminReceiptFlowResourceTest {

    /**
     * Builds the resource with its collaborators mocked.
     *
     * @return the resource
     */
    private AdminReceiptFlowResource newResource() {
        AdminReceiptFlowResource resource = new AdminReceiptFlowResource();
        resource.adminReceiptFlows = mock(Template.class);
        resource.receiptFlowService = mock(ReceiptFlowService.class);
        resource.safeReceiptCsvResource = mock(SafeReceiptCsvResource.class);
        return resource;
    }

    /**
     * Builds a posted form.
     *
     * @param pairs the field names and values, alternately
     * @return the form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            form.add(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    /**
     * Reads the decoded location of a redirect.
     *
     * @param response the redirect
     * @return the location
     */
    private String location(Response response) {
        return URLDecoder.decode(response.getLocation().toString(), StandardCharsets.UTF_8);
    }

    /**
     * The page lists the flows, the defaults declared first.
     */
    @Test
    void thePageListsTheFlows() {
        AdminReceiptFlowResource resource = newResource();
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.adminReceiptFlows.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        ReceiptFlow flow = new ReceiptFlow();
        when(resource.receiptFlowService.ensureDefaults()).thenReturn(List.of(flow));
        assertSame(view, resource.list(null, true));
        verify(resource.adminReceiptFlows).data("flows", List.of(flow));
    }

    /**
     * A new flow is declared under its upper-case code with its layout; the
     * separator defaults to a semicolon and keeps one character.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anewFlowIsDeclared() {
        AdminReceiptFlowResource resource = newResource();
        PanacheQuery<ReceiptFlow> free = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<ReceiptFlow> created = mockConstruction(ReceiptFlow.class)) {
            panache.when(() -> ReceiptFlow.find("code", "LAVAGE")).thenReturn(free);
            assertTrue(location(resource.save(form("code", "lavage", "label", "Station de lavage",
                    "mapping", "REFERENCE=R;AMOUNT=M", "separator", "|x", "datePattern", "dd/MM/yyyy",
                    "active", "on"))).contains("Flux de recettes enregistré."));
            ReceiptFlow flow = created.constructed().get(0);
            assertEquals("LAVAGE", flow.code);
            assertEquals("Station de lavage", flow.label);
            assertEquals("|", flow.separator);
            assertEquals("dd/MM/yyyy", flow.datePattern);
            assertTrue(flow.active);
            verify(flow).persist();
            resource.save(form("code", "lavage", "label", "L", "mapping", "AMOUNT=M"));
            assertEquals(";", created.constructed().get(1).separator);
            assertFalse(created.constructed().get(1).active);
            resource.save(form("code", "lavage", "label", "L", "mapping", "AMOUNT=M", "separator", ""));
            assertEquals(";", created.constructed().get(2).separator);
        }
    }

    /**
     * An existing flow is corrected in place, its code untouched.
     */
    @Test
    void anExistingFlowIsCorrected() {
        AdminReceiptFlowResource resource = newResource();
        ReceiptFlow flow = mock(ReceiptFlow.class);
        flow.code = "STATION";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ReceiptFlow.findById(4L)).thenReturn(flow);
            resource.save(form("id", "4", "label", "Station", "mapping", "amount=MT", "separator", ";"));
        }
        assertEquals("STATION", flow.code);
        assertEquals("amount=MT", flow.mapping);
        verify(flow).persist();
    }

    /**
     * A flow is refused without code, with a code taken, without label,
     * without mapping, or with a mapping naming no amount.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anIncompleteFlowIsRefused() {
        AdminReceiptFlowResource resource = newResource();
        PanacheQuery<ReceiptFlow> taken = mock(PanacheQuery.class);
        when(taken.firstResult()).thenReturn(new ReceiptFlow());
        PanacheQuery<ReceiptFlow> free = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<ReceiptFlow> created = mockConstruction(ReceiptFlow.class)) {
            assertTrue(location(resource.save(form("id", "9"))).contains("Code du flux obligatoire."));
            panache.when(() -> ReceiptFlow.find("code", "GAZ")).thenReturn(taken);
            panache.when(() -> ReceiptFlow.find("code", "NEW")).thenReturn(free);
            assertTrue(location(resource.save(form("code", "gaz"))).contains("existe déjà : gaz"));
            String incomplete = "Libellé et correspondance des colonnes (au moins AMOUNT=) obligatoires.";
            assertTrue(location(resource.save(form("code", "new", "mapping", "AMOUNT=M"))).contains(incomplete));
            assertTrue(location(resource.save(form("code", "new", "label", "L"))).contains(incomplete));
            assertTrue(location(resource.save(form("code", "new", "label", "L", "mapping", "REFERENCE=R")))
                    .contains(incomplete));
            for (ReceiptFlow flow : created.constructed()) {
                verify(flow, org.mockito.Mockito.never()).persist();
            }
        }
    }

    /**
     * A pasted file goes through the importer under its flow's code, and the
     * notice states in words what the importer answered; an unknown or
     * inactive flow is refused before any integration.
     */
    @Test
    void apastedFileIsIntegrated() {
        AdminReceiptFlowResource resource = newResource();
        ReceiptFlow station = new ReceiptFlow();
        station.code = "STATION";
        station.active = true;
        ReceiptFlow old = new ReceiptFlow();
        old.code = "OLD";
        old.active = false;
        when(resource.safeReceiptCsvResource.importReceipts(any(InputStream.class), eq("STATION")))
                .thenReturn(Response.ok("{\"createdCount\":2,\"updatedCount\":1}").build(),
                        Response.status(400).build());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFlow(panache, "STATION", station);
            stubFlow(panache, "OLD", old);
            stubFlow(panache, "NONE", null);
            String location = location(resource.integrate(form("code", "station", "content", "A;B\n1;2")));
            assertTrue(location.contains("Flux STATION : 2 recette(s) créée(s), 1 mise(s) à jour, 0 erreur(s)."));
            assertTrue(location.contains("noticeOk=true"));
            String refused = location(resource.integrate(form("code", "station", "content", "A;B\n1;2")));
            assertTrue(refused.contains("Flux STATION refusé (statut 400) : fichier non conforme au flux."));
            assertTrue(refused.contains("noticeOk=false"));
            assertTrue(location(resource.integrate(form("code", "old", "content", "x")))
                    .contains("Flux de recettes inconnu ou inactif."));
            assertTrue(location(resource.integrate(form("code", "none", "content", "x")))
                    .contains("Flux de recettes inconnu ou inactif."));
        }
        verify(resource.receiptFlowService, org.mockito.Mockito.times(4)).ensureDefaults();
    }

    /**
     * Stubs the flow a code finds.
     *
     * @param panache the static mock
     * @param code the code, upper case
     * @param flow the flow, or null
     */
    @SuppressWarnings("unchecked")
    private void stubFlow(MockedStatic<PanacheEntityBase> panache, String code, ReceiptFlow flow) {
        PanacheQuery<ReceiptFlow> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(flow);
        panache.when(() -> PanacheEntityBase.find("code", code)).thenReturn(query);
    }

    /**
     * Nothing is integrated without a flow, without content or with a blank one.
     */
    @Test
    void nothingIsIntegratedWithoutFlowOrContent() {
        AdminReceiptFlowResource resource = newResource();
        String refusal = "Flux et contenu du fichier obligatoires.";
        assertTrue(location(resource.integrate(form("content", "x"))).contains(refusal));
        assertTrue(location(resource.integrate(form("code", "GAZ"))).contains(refusal));
        assertTrue(location(resource.integrate(form("code", "GAZ", "content", " "))).contains(refusal));
    }

    /**
     * A value longer than its column is refused before anything is read or
     * written: the code, the label, the mapping and the date format.
     */
    @Test
    void avalueLongerThanItsColumnIsRefused() {
        AdminReceiptFlowResource resource = newResource();
        String refusal = "Valeur trop longue : code 30, libellé 120, correspondance 1000 et format des dates "
                + "30 caractères au plus.";
        assertTrue(location(resource.save(form("code", "x".repeat(31)))).contains(refusal));
        assertTrue(location(resource.save(form("label", "x".repeat(121)))).contains(refusal));
        assertTrue(location(resource.save(form("mapping", "x".repeat(1001)))).contains(refusal));
        assertTrue(location(resource.save(form("datePattern", "x".repeat(31)))).contains(refusal));
        assertFalse(AdminReceiptFlowResource.tooLong(form("code", "x".repeat(30), "label", "x".repeat(120),
                "mapping", "x".repeat(1000), "datePattern", "x".repeat(30))));
    }
}
