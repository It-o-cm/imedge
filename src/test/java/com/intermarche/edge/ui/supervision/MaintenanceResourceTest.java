package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.domain.deploy.MaintenanceAction;
import com.intermarche.edge.domain.deploy.NodeStatus;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.deploy.MaintenanceService;
import com.intermarche.edge.service.deploy.NodeStatusService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link MaintenanceResource}. */
class MaintenanceResourceTest {

    /**
     * Builds the resource over mocked collaborators.
     *
     * @param role the node's role
     * @return the resource
     */
    private MaintenanceResource resource(String role) {
        MaintenanceResource resource = new MaintenanceResource();
        resource.maintenancePage = mock(Template.class);
        resource.maintenanceService = mock(MaintenanceService.class);
        resource.nodeStatusService = mock(NodeStatusService.class);
        resource.currentUser = mock(CurrentUser.class);
        when(resource.currentUser.getFullName()).thenReturn("Admin");
        resource.role = role;
        return resource;
    }

    /**
     * Builds a node.
     *
     * @param kind its kind
     * @param pdv its point of sale
     * @param id its identifier
     * @return the node
     */
    private NodeStatus node(String kind, String pdv, String id) {
        NodeStatus node = new NodeStatus();
        node.kind = kind;
        node.pdvNumber = pdv;
        node.nodeId = id;
        return node;
    }

    /** BO-08-04-17: the page lists the nodes, filtered by point of sale, with the orders each accepts. */
    @Test
    @SuppressWarnings("unchecked")
    void thePageListsTheNodes() {
        MaintenanceResource resource = resource("store");
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.maintenancePage.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        NodeStatus mine = node("REGISTER", "07039", "C04");
        NodeStatus other = node("REGISTER", "07040", "C01");
        when(resource.nodeStatusService.all()).thenReturn(List.of(mine, other));
        assertSame(instance, resource.page(" 07039 ", null, true));
        ArgumentCaptor<Object> nodes = ArgumentCaptor.forClass(Object.class);
        verify(resource.maintenancePage).data(eq("nodes"), nodes.capture());
        assertEquals(List.of(mine), nodes.getValue());
        resource.page(null, null, true);
        resource.page(" ", null, true);
        List<MaintenanceResource.Choice> registers = MaintenanceResource.choices("REGISTER");
        assertEquals("TERMINAL_RESET", registers.get(0).value);
        assertEquals("Relance du TPV", registers.get(0).label);
        assertEquals(4, MaintenanceResource.choices("STORE").size());
    }

    /** BO-08-04-17: an order is left for a known node, traced with its issuer and echelon. */
    @Test
    void issueLeavesTheOrder() {
        MaintenanceResource resource = resource("central");
        NodeStatus node = node("REGISTER", "07039", "C04");
        when(resource.nodeStatusService.find("K")).thenReturn(node);
        when(resource.maintenanceService.issue("REGISTER", "07039", "C04", MaintenanceAction.ActionType.TERMINAL_RESET,
                "Admin", "figé", "CENTRAL")).thenReturn(new MaintenanceAction());
        Response response = resource.issue("K", "TERMINAL_RESET", " figé ");
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        MaintenanceResource store = resource("store");
        when(store.nodeStatusService.find("K")).thenReturn(node);
        Response refused = store.issue("K", "TERMINAL_RESET", " ");
        assertTrue(refused.getLocation().toString().contains("noticeOk=false"));
        verify(store.maintenanceService).issue(eq("REGISTER"), eq("07039"), eq("C04"),
                eq(MaintenanceAction.ActionType.TERMINAL_RESET), eq("Admin"), isNull(), eq("STORE"));
        store.issue("K", "TERMINAL_RESET", null);
    }

    /** An unknown node or order type is refused. */
    @Test
    void unknownNodesAndOrdersAreRefused() {
        MaintenanceResource resource = resource("store");
        assertTrue(resource.issue(null, "TERMINAL_RESET", null).getLocation().toString().contains("noticeOk=false"));
        when(resource.nodeStatusService.find("K")).thenReturn(node("STORE", "07039", "07039"));
        assertTrue(resource.issue("K", "REBOOT", null).getLocation().toString().contains("noticeOk=false"));
        assertTrue(resource.issue("Z", "TERMINAL_RESET", null).getLocation().toString().contains("noticeOk=false"));
        assertNull(MaintenanceResource.typeOf("REBOOT"));
        assertEquals(MaintenanceAction.ActionType.UPDATE_CHECK, MaintenanceResource.typeOf("UPDATE_CHECK"));
    }
}
