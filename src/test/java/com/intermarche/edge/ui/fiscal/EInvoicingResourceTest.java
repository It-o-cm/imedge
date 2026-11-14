package com.intermarche.edge.ui.fiscal;

import com.intermarche.edge.domain.fiscal.EInvoice;
import com.intermarche.edge.domain.setting.ServiceProvider;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.fiscal.EInvoicingService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link EInvoicingResource} (BO-05-01-02): the electronic
 * invoices listed by status, queued, deposited, refreshed and downloaded.
 */
class EInvoicingResourceTest {

    /**
     * Builds the resource with its collaborators mocked.
     *
     * @return the resource
     */
    private EInvoicingResource resource() {
        EInvoicingResource resource = new EInvoicingResource();
        resource.adminEInvoicing = mock(Template.class);
        resource.eInvoicingService = mock(EInvoicingService.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        return resource;
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
     * Builds a posted form naming an invoice.
     *
     * @param id the identifier, or null
     * @return the form
     */
    private MultivaluedMap<String, String> form(String id) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        if (id != null) {
            form.add("id", id);
        }
        return form;
    }

    /**
     * The page lists the invoices of a status, or all, with the platform's
     * state.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thepageListsTheInvoicesOfAStatus() {
        EInvoicingResource resource = resource();
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.adminEInvoicing.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        when(resource.posSettingsService.pdpProviderCode()).thenReturn("PDP");
        when(resource.eInvoicingService.platform()).thenReturn(new ServiceProvider(), (ServiceProvider) null);
        PanacheQuery<EInvoice> query = mock(PanacheQuery.class);
        when(query.page(0, EInvoicingResource.SHOWN)).thenReturn(query);
        when(query.list()).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("status = ?1 order by id desc", EInvoice.Status.REJECTED))
                    .thenReturn(query);
            panache.when(() -> PanacheEntityBase.find("order by id desc")).thenReturn(query);
            resource.list("213", null, true);
            resource.list(null, null, true);
        }
        verify(view).data("status", "REJECTED");
        verify(view).data("status", "");
        verify(view).data("platformReady", true);
        verify(view).data("platformReady", false);
        verify(view, org.mockito.Mockito.times(2)).data("platformCode", "PDP");
    }

    /**
     * The scan states how many invoices it queued.
     */
    @Test
    void thescanStatesWhatItQueued() {
        EInvoicingResource resource = resource();
        when(resource.eInvoicingService.scan(any())).thenReturn(3);
        assertTrue(location(resource.scan()).contains("3 facture(s) mise(s) en file."));
    }

    /**
     * A deposit says whether the platform took the invoice, and why not; a
     * refresh says whether the status moved; an unknown invoice is refused.
     */
    @Test
    void adepositAndARefreshSayWhatHappened() {
        EInvoicingResource resource = resource();
        EInvoice invoice = new EInvoice();
        invoice.lastError = "Plateforme de dématérialisation non configurée ou injoignable.";
        when(resource.eInvoicingService.deposit(eq(invoice), any())).thenReturn(true, false);
        when(resource.eInvoicingService.refresh(eq(invoice), any())).thenReturn(true, false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(invoice);
            assertTrue(location(resource.deposit(form("1"))).contains("Facture déposée sur la plateforme."));
            assertTrue(location(resource.deposit(form("1"))).contains("Dépôt en échec : Plateforme"));
            assertTrue(location(resource.refresh(form("1"))).contains("Statut mis à jour."));
            assertTrue(location(resource.refresh(form("1"))).contains("Statut inchangé."));
            assertTrue(location(resource.deposit(form("2"))).contains("Facture électronique inconnue."));
            assertTrue(location(resource.refresh(form("2"))).contains("Facture électronique inconnue."));
        }
        assertTrue(location(resource.deposit(form(null))).contains("Facture électronique inconnue."));
    }

    /**
     * The structured invoice downloads as deposited; an unknown one is not
     * found.
     */
    @Test
    void thestructuredInvoiceDownloads() {
        EInvoicingResource resource = resource();
        EInvoice invoice = new EInvoice();
        invoice.invoiceNumber = "F1";
        invoice.payload = "<x/>";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(invoice);
            Response response = resource.document(1L);
            assertArrayEquals("<x/>".getBytes(StandardCharsets.UTF_8), (byte[]) response.getEntity());
            assertEquals("attachment; filename=\"facture-F1-cii.xml\"", response.getHeaderString("Content-Disposition"));
            assertEquals(404, resource.document(2L).getStatus());
        }
        assertEquals(404, resource.document(null).getStatus());
    }
}
