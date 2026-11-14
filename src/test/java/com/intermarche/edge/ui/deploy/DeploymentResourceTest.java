package com.intermarche.edge.ui.deploy;

import com.intermarche.edge.security.CurrentUser;
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
import static org.mockito.Mockito.when;

/** Unit tests for {@link DeploymentResource}. */
class DeploymentResourceTest {

    /**
     * Builds the resource over mocked templates and service.
     *
     * @return the resource
     */
    private DeploymentResource resource() {
        DeploymentResource resource = new DeploymentResource();
        resource.consolePage = mock(Template.class);
        resource.lotPage = mock(Template.class);
        resource.consoleService = mock(DeploymentConsoleService.class);
        resource.currentUser = mock(CurrentUser.class);
        when(resource.currentUser.getFullName()).thenReturn("Admin");
        when(resource.consoleService.console(any())).thenReturn(new DeploymentConsoleService.ConsoleData());
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

    /**
     * Reads a redirect's location.
     *
     * @param response the redirect
     * @return its location
     */
    private String location(Response response) {
        assertEquals(303, response.getStatus());
        return response.getLocation().toString();
    }

    /** BO-08-03-06: the console renders, and exports its list. */
    @Test
    void theConsoleRendersAndExports() {
        DeploymentResource resource = resource();
        TemplateInstance instance = wire(resource.consolePage);
        assertSame(instance, resource.console(uri(), null, true));
        assertEquals("attachment; filename=\"deploiement.xlsx\"",
                resource.export(uri(), "xlsx").getHeaderString("Content-Disposition"));
    }

    /** A release is published, or its refusal shown. */
    @Test
    void createReleaseRedirects() {
        DeploymentResource resource = resource();
        assertTrue(location(resource.createRelease("IMPOS", "2.0", "MAJOR", null, false)).contains("noticeOk=true"));
        when(resource.consoleService.createRelease("X", "2.0", "MAJOR", null, false)).thenReturn("Produit inconnu.");
        assertTrue(location(resource.createRelease("X", "2.0", "MAJOR", null, false)).contains("noticeOk=false"));
    }

    /** BO-08-03-04: a created lot opens its page; a refused one returns to the console. */
    @Test
    void createLotRedirects() {
        DeploymentResource resource = resource();
        assertTrue(location(resource.createLot(" L1 ", null, "IMPOS", "2.0", true, null, "a", "b"))
                .startsWith("/admin/deployment/lot/L1?noticeOk=true"));
        when(resource.consoleService.createLot("L2", null, "IMPOS", "2.0", false, null, "a", "b", "Admin"))
                .thenReturn("Période illisible.");
        assertTrue(location(resource.createLot("L2", null, "IMPOS", "2.0", false, null, "a", "b"))
                .startsWith("/admin/deployment?noticeOk=false"));
    }

    /** BO-08-03-01: a lot's page renders; an unknown lot returns to the console. */
    @Test
    void lotRendersOrRedirects() {
        DeploymentResource resource = resource();
        TemplateInstance instance = wire(resource.lotPage);
        DeploymentConsoleService.LotView view = new DeploymentConsoleService.LotView();
        when(resource.consoleService.lot("L1")).thenReturn(view);
        Response page = resource.lot("L1", "ok", true);
        assertEquals(200, page.getStatus());
        assertSame(instance, page.getEntity());
        assertTrue(location(resource.lot("L9", null, true)).contains("noticeOk=false"));
    }

    /** BO-08-03-02/03/04: the lot's gestures redirect to its page, green or red. */
    @Test
    void theLotGesturesRedirect() {
        DeploymentResource resource = resource();
        when(resource.consoleService.addTargets("L1", "UNIT", null, "07039", "ALL", null))
                .thenReturn("1 cible(s) ajoutée(s).");
        when(resource.consoleService.addTargets("L1", "RECALL", null, "x", "ALL", null)).thenReturn("Sélection introuvable.");
        assertTrue(location(resource.addTargets("L1", "UNIT", null, "07039", "ALL", null)).contains("noticeOk=true"));
        assertTrue(location(resource.addTargets("L1", "RECALL", null, "x", "ALL", null)).contains("noticeOk=false"));
        assertTrue(location(resource.removeTarget("L1", 3L)).contains("noticeOk=true"));
        when(resource.consoleService.removeTarget("L1", 4L)).thenReturn("Cible introuvable.");
        assertTrue(location(resource.removeTarget("L1", 4L)).contains("noticeOk=false"));
        assertTrue(location(resource.saveSelection("L1", "pilotes")).contains("noticeOk=true"));
        assertTrue(location(resource.updateWindow("L1", "a", "b")).contains("noticeOk=true"));
        assertTrue(location(resource.schedule("L1")).contains("noticeOk=true"));
        assertTrue(location(resource.close("L1")).contains("noticeOk=true"));
    }

    /** BO-08-04-02: the forcings report how many targets were forced; a closed lot is refused. */
    @Test
    void theForcingsRedirect() {
        DeploymentResource resource = resource();
        when(resource.consoleService.force("L1", 3L)).thenReturn(1);
        when(resource.consoleService.force("L1", null)).thenReturn(0);
        when(resource.consoleService.force("L2", null)).thenReturn(-1);
        assertTrue(location(resource.forceTarget("L1", 3L)).contains("noticeOk=true"));
        assertTrue(location(resource.forceFailed("L1")).contains("noticeOk=false"));
        assertTrue(location(resource.forceFailed("L2")).contains("cl%C3%B4tur%C3%A9"));
        assertEquals("/admin/deployment/lot/L+1", DeploymentResource.lotPath("L 1"));
    }
}
