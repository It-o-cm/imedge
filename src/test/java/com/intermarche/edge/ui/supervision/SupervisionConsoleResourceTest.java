package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.ui.admin.BackOfficeMessages;
import com.intermarche.edge.domain.sync.IntegrationDeposit;
import com.intermarche.edge.service.sync.IntegrationRetryService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link SupervisionConsoleResource}. */
class SupervisionConsoleResourceTest {

    /**
     * Builds the resource over mocked templates and services.
     *
     * @return the resource
     */
    private SupervisionConsoleResource resource() {
        SupervisionConsoleResource resource = new SupervisionConsoleResource();
        resource.activityPage = mock(Template.class);
        resource.integrationPage = mock(Template.class);
        resource.inventoryPage = mock(Template.class);
        resource.usagePage = mock(Template.class);
        resource.consoleService = mock(SupervisionConsoleService.class);
        resource.integrationRetryService = mock(IntegrationRetryService.class);
        when(resource.consoleService.activity(any())).thenReturn(new SupervisionConsoleService.ConsoleView());
        when(resource.consoleService.integration(any())).thenReturn(new SupervisionConsoleService.ConsoleView());
        when(resource.consoleService.inventory(any())).thenReturn(new SupervisionConsoleService.ConsoleView());
        when(resource.consoleService.inventory(any(), any())).thenReturn(new SupervisionConsoleService.ConsoleView());
        resource.i18n = mock(BackOfficeMessages.class);
        when(resource.consoleService.usage(any())).thenReturn(new SupervisionConsoleService.ConsoleView());
        return resource;
    }

    /**
     * Builds a request address with an empty query.
     *
     * @return the address
     */
    private UriInfo uri() {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        return uriInfo;
    }

    /**
     * Wires a template's chained data calls.
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

    /** BO-08-04-15/20, BO-08-02-02, BO-08-01-17: each console renders its view. */
    @Test
    void theConsolesRender() {
        SupervisionConsoleResource resource = resource();
        TemplateInstance activity = wire(resource.activityPage);
        TemplateInstance integration = wire(resource.integrationPage);
        TemplateInstance inventory = wire(resource.inventoryPage);
        TemplateInstance usage = wire(resource.usagePage);
        assertSame(activity, resource.activity(uri()));
        assertSame(integration, resource.integration(uri(), "fait", true));
        assertSame(inventory, resource.inventory(uri()));
        assertSame(usage, resource.usage(uri()));
        verify(integration).data("notice", "fait");
        verify(integration).data("noticeOk", true);
    }

    /** BO-08-01-17, BO-08-04-15/20, BO-08-02-02: each console exports its list as a file. */
    @Test
    void theConsolesExport() {
        SupervisionConsoleResource resource = resource();
        assertEquals("attachment; filename=\"activite.csv\"",
                resource.activityExport(uri(), "csv").getHeaderString("Content-Disposition"));
        assertEquals("attachment; filename=\"integration.xlsx\"",
                resource.integrationExport(uri(), "xlsx").getHeaderString("Content-Disposition"));
        assertEquals("attachment; filename=\"inventaire.csv\"",
                resource.inventoryExport(uri(), null).getHeaderString("Content-Disposition"));
        assertEquals("attachment; filename=\"usage-fonctions.csv\"",
                resource.usageExport(uri(), "csv").getHeaderString("Content-Disposition"));
    }

    /** BO-08-04-09 to -13: a deposit is replayed on demand, green when it went in, red otherwise or unknown. */
    @Test
    void replayForcesADeposit() {
        SupervisionConsoleResource resource = resource();
        IntegrationDeposit applied = new IntegrationDeposit();
        applied.code = "PRICES";
        applied.status = IntegrationDeposit.Status.APPLIED;
        IntegrationDeposit partial = new IntegrationDeposit();
        partial.code = "PRICES";
        partial.status = IntegrationDeposit.Status.PARTIAL;
        when(resource.integrationRetryService.replayNow(1L)).thenReturn(applied);
        when(resource.integrationRetryService.replayNow(2L)).thenReturn(partial);
        Response ok = resource.replay(1L);
        assertEquals(303, ok.getStatus());
        assertTrue(ok.getLocation().toString().contains("noticeOk=true"));
        assertTrue(resource.replay(2L).getLocation().toString().contains("noticeOk=false"));
        assertTrue(resource.replay(3L).getLocation().toString().contains("introuvable"));
        when(resource.integrationRetryService.retryAll()).thenReturn(4);
        assertTrue(resource.replayAll().getLocation().toString().contains("4+d%C3%A9p%C3%B4t"));
    }
}
