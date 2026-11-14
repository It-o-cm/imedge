package com.intermarche.edge.service.deploy;

import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.sync.EngineFeedDeliveryService;
import com.intermarche.edge.service.sync.IntegrationRetryService;
import com.intermarche.edge.service.sync.RefPullService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link EdgeMaintenanceService}. */
class EdgeMaintenanceServiceTest {

    /**
     * Builds the service over mocked collaborators.
     *
     * @param enabled whether the remote maintenance is active
     * @return the service
     */
    private EdgeMaintenanceService service(boolean enabled) {
        EdgeMaintenanceService service = new EdgeMaintenanceService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.refPullService = mock(RefPullService.class);
        service.engineFeedDeliveryService = mock(EngineFeedDeliveryService.class);
        service.integrationRetryService = mock(IntegrationRetryService.class);
        when(service.posSettingsService.remoteMaintenanceEnabled()).thenReturn(enabled);
        return service;
    }

    /**
     * Builds an order.
     *
     * @param type its type
     * @return the order
     */
    private DeployPayloads.Action order(String type) {
        DeployPayloads.Action order = new DeployPayloads.Action();
        order.uid = "u-" + type;
        order.type = type;
        return order;
    }

    /** BO-08-04-17: a node with the remote maintenance off refuses every order, and says so. */
    @Test
    void aDisabledNodeRefuses() {
        EdgeMaintenanceService service = service(false);
        DeployPayloads.ActionResult result = service.execute(order("REFERENTIAL_PULL"));
        assertFalse(result.success);
        assertEquals("Prise en main à distance désactivée sur ce nœud", result.result);
        verify(service.refPullService, never()).triggerPull();
    }

    /** BO-08-04-09/10 from afar: the pull is relaunched, its error reported. */
    @Test
    void thePullIsRelaunched() {
        EdgeMaintenanceService service = service(true);
        DeployPayloads.ActionResult ok = service.execute(order("REFERENTIAL_PULL"));
        assertTrue(ok.success);
        assertEquals("u-REFERENTIAL_PULL", ok.uid);
        assertNotNull(ok.doneAt);
        when(service.refPullService.triggerPull()).thenReturn("injoignable");
        DeployPayloads.ActionResult failed = service.execute(order("REFERENTIAL_PULL"));
        assertFalse(failed.success);
        assertEquals("injoignable", failed.result);
    }

    /** BO-08-04-13 from afar: the engine-feed delivery is relaunched. */
    @Test
    void theDeliveryIsRelaunched() {
        EdgeMaintenanceService service = service(true);
        assertEquals("Livraison des flux moteur relancée", service.execute(order("ENGINE_FEED_DELIVERY")).result);
    }

    /** BO-08-04-02 from afar: the failed deposits are replayed. */
    @Test
    void theDepositsAreReplayed() {
        EdgeMaintenanceService service = service(true);
        when(service.integrationRetryService.retryAll()).thenReturn(2);
        assertEquals("2 dépôt(s) réintégré(s)", service.execute(order("INTEGRATION_RETRY")).result);
    }

    /** A register-only order is refused on a store or central node. */
    @Test
    void aRegisterOrderIsRefused() {
        DeployPayloads.ActionResult result = service(true).execute(order("TERMINAL_RESET"));
        assertFalse(result.success);
        assertEquals("Ordre non applicable à ce nœud", result.result);
    }
}
