package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.catalog.EegProvider;
import com.intermarche.edge.service.PosSettingsService;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminEegProviderResource} (BO-02-03-37/38): the page,
 * every refusal of the save, the creation, the update and the deletion.
 */
class AdminEegProviderResourceTest {

    /**
     * Builds the resource over a mocked template and parameters.
     *
     * @return the resource
     */
    private AdminEegProviderResource newResource() {
        AdminEegProviderResource resource = new AdminEegProviderResource();
        resource.adminEegProviders = mock(Template.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        return resource;
    }

    /**
     * Builds a posted form from name/value pairs.
     *
     * @param pairs the pairs
     * @return the form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            form.putSingle(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    /**
     * Builds a single-result query.
     *
     * @param result the first result, possibly null
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<EegProvider> single(EegProvider result) {
        return mock(PanacheQuery.class, invocation ->
                "firstResult".equals(invocation.getMethod().getName()) ? result : null);
    }

    /**
     * Tells whether a redirect reports a success.
     *
     * @param response the redirect
     * @return true for a green notice
     */
    private boolean ok(Response response) {
        return response.getLocation().toString().contains("noticeOk=true");
    }

    /**
     * The page carries the providers, the edited one, the flow's state and the
     * connectors.
     */
    @Test
    void pageRendersTheProviders() {
        AdminEegProviderResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminEegProviders.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        when(resource.posSettingsService.eegEnabled()).thenReturn(true);
        EegProvider provider = new EegProvider();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EegProvider.list("order by code")).thenReturn(List.of(provider));
            panache.when(() -> EegProvider.findById(3L)).thenReturn(provider);
            assertEquals(instance, resource.page(3L, null, true));
            assertEquals(instance, resource.page(null, null, true));
        }
        verify(instance).data("edited", provider);
        verify(instance).data("edited", null);
    }

    /**
     * A missing code or label, an HTTP provider without a valid address, an
     * unknown id and a code clash are refused.
     */
    @Test
    void saveRefusals() {
        AdminEegProviderResource resource = newResource();
        assertFalse(ok(resource.save(form("label", "SES"))));
        assertFalse(ok(resource.save(form("code", "ses"))));
        assertFalse(ok(resource.save(form("code", "ses", "label", "SES", "kind", "HTTP"))));
        assertFalse(ok(resource.save(form("code", "ses", "label", "SES", "kind", "HTTP", "baseUrl", "ftp://x"))));
        EegProvider other = new EegProvider();
        PanacheQuery<EegProvider> clash = single(other);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EegProvider.findById(9L)).thenReturn(null);
            assertFalse(ok(resource.save(form("id", "9", "code", "ses", "label", "SES"))));
            panache.when(() -> EegProvider.find("code", "SES")).thenReturn(clash);
            assertFalse(ok(resource.save(form("code", "ses", "label", "SES"))));
        }
    }

    /**
     * A new provider is created active under its upper-cased code; an
     * unrecognized connector reads as the simulated one.
     */
    @Test
    void saveCreatesAProvider() {
        AdminEegProviderResource resource = newResource();
        PanacheQuery<EegProvider> free = single(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<EegProvider> created = mockConstruction(EegProvider.class)) {
            panache.when(() -> EegProvider.find("code", "SES")).thenReturn(free);
            assertTrue(ok(resource.save(form("code", "ses", "label", "SES", "kind", "OTHER"))));
            EegProvider provider = created.constructed().get(0);
            assertEquals("SES", provider.code);
            assertEquals(EegProvider.KIND_SIMULATED, provider.kind);
            assertTrue(provider.active);
            verify(provider).persist();
        }
    }

    /**
     * An existing provider is updated to an HTTP connector and the posted
     * activity.
     */
    @Test
    void saveUpdatesAProvider() {
        AdminEegProviderResource resource = newResource();
        EegProvider edited = new EegProvider();
        PanacheQuery<EegProvider> self = single(edited);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EegProvider.findById(2L)).thenReturn(edited);
            panache.when(() -> EegProvider.find("code", "HAN")).thenReturn(self);
            assertTrue(ok(resource.save(form("id", "2", "code", "han", "label", "Hanshow", "kind", "HTTP",
                    "baseUrl", "https://eeg.example", "active", "on"))));
        }
        assertEquals(EegProvider.KIND_HTTP, edited.kind);
        assertEquals("https://eeg.example", edited.baseUrl);
        assertTrue(edited.active);
    }

    /**
     * A deletion naming nothing is refused; a known provider is deleted.
     */
    @Test
    void deleteRemovesAProvider() {
        AdminEegProviderResource resource = newResource();
        EegProvider provider = mock(EegProvider.class);
        provider.code = "SES";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertFalse(ok(resource.delete(form())));
            panache.when(() -> EegProvider.findById(4L)).thenReturn(provider);
            assertTrue(ok(resource.delete(form("id", "4"))));
        }
        verify(provider).delete();
    }

    /**
     * An update without the activity deactivates the provider.
     */
    @Test
    void saveDeactivatesAnUpdatedProvider() {
        AdminEegProviderResource resource = newResource();
        EegProvider edited = new EegProvider();
        PanacheQuery<EegProvider> self = single(edited);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EegProvider.findById(2L)).thenReturn(edited);
            panache.when(() -> EegProvider.find("code", "HAN")).thenReturn(self);
            assertTrue(ok(resource.save(form("id", "2", "code", "han", "label", "Hanshow"))));
        }
        assertFalse(edited.active);
    }
}
