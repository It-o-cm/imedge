package com.intermarche.edge.ui.journal;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AdherentJournalResource}. */
class AdherentJournalResourceTest {

    /**
     * Builds the resource over mocked templates and service.
     *
     * @param role the node's role
     * @return the resource
     */
    private AdherentJournalResource resource(String role) {
        AdherentJournalResource resource = new AdherentJournalResource();
        resource.journalAdherent = mock(Template.class);
        resource.journalAdherentDetail = mock(Template.class);
        resource.adherentJournalService = mock(AdherentJournalService.class);
        resource.role = role;
        return resource;
    }

    /**
     * Wires a self-returning template instance.
     *
     * @param template the template
     * @return the instance the chain returns
     */
    private TemplateInstance wire(Template template) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(template.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds a URI carrying query parameters.
     *
     * @param name the parameter
     * @param value its value
     * @return the mocked URI
     */
    private UriInfo uri(String name, String value) {
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.add(name, value);
        when(uriInfo.getQueryParameters()).thenReturn(params);
        return uriInfo;
    }

    /** BO-02-05-05: the search renders the page with its choices, saying whether this is the central node. */
    @Test
    void theSearchRendersThePage() {
        AdherentJournalResource central = resource("central");
        TemplateInstance instance = wire(central.journalAdherent);
        JournalPage<AdherentJournalRow> page = new JournalPage<>(List.of(), 1, AdherentJournalService.PAGE_SIZE, 0);
        when(central.adherentJournalService.search(any())).thenReturn(page);
        when(central.adherentJournalService.adherents()).thenReturn(List.of("AD1"));
        assertSame(instance, central.search(uri("adherent", "AD1")));
        verify(instance).data("tickets", page);
        verify(instance).data("baseQuery", "adherent=AD1&");
        verify(instance).data("adherents", List.of("AD1"));
        verify(instance).data("kinds", AdherentJournalCriteria.KINDS);
        verify(instance).data("central", true);
        AdherentJournalResource store = resource("store");
        TemplateInstance storeInstance = wire(store.journalAdherent);
        store.search(uri("pdv", "10001"));
        verify(storeInstance).data("central", false);
    }

    /** The detail renders what the service opens. */
    @Test
    void theDetailRendersTheTicket() {
        AdherentJournalResource resource = resource("central");
        TemplateInstance instance = wire(resource.journalAdherentDetail);
        AdherentJournalDetail detail = new AdherentJournalDetail("AD1", "10001", "", "SALE", "", "", null);
        when(resource.adherentJournalService.detail(3L)).thenReturn(detail);
        assertSame(instance, resource.detail(3L));
        verify(resource.journalAdherentDetail).data("detail", detail);
    }
}
