package com.intermarche.edge.imports;

import com.intermarche.edge.service.ExternalRelayService;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RestoFlashResource}: the gate arms (off-role, bad token)
 * and both relay outcomes (service reached / refused default), EXP-67.
 */
class RestoFlashResourceTest {

    /**
     * Builds a store-role resource with the given token and a mocked relay.
     *
     * @param token the configured shared token
     * @return the resource
     */
    private RestoFlashResource storeResource(Optional<String> token) {
        RestoFlashResource resource = new RestoFlashResource();
        resource.role = "store";
        resource.token = token;
        resource.restoFlashUrl = Optional.of("http://flash.local");
        resource.relay = mock(ExternalRelayService.class);
        return resource;
    }

    /**
     * A node that is not a store refuses the confirmation (403).
     */
    @Test
    void offRoleIsForbidden() {
        RestoFlashResource resource = new RestoFlashResource();
        resource.role = "register";
        resource.token = Optional.empty();
        resource.relay = mock(ExternalRelayService.class);
        assertEquals(403, resource.confirm("{}", null).getStatus());
    }

    /**
     * A configured token the caller does not present is rejected (401).
     */
    @Test
    void badTokenIsUnauthorized() {
        RestoFlashResource resource = storeResource(Optional.of("secret"));
        assertEquals(401, resource.confirm("{}", "wrong").getStatus());
    }

    /**
     * When the service is unreachable, the refused default is returned.
     */
    @Test
    @SuppressWarnings("unchecked")
    void unreachableServiceRefuses() {
        RestoFlashResource resource = storeResource(Optional.empty());
        when(resource.relay.relay(any(), eq("POST"), any(), any(), anyLong()))
                .thenReturn(new ExternalRelayService.RelayResult(false, 0, ""));
        Response response = resource.confirm("{\"token\":\"T\",\"amount\":5}", null);
        assertEquals(200, response.getStatus());
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals(false, body.get("accepted"));
    }

    /**
     * When the service answers, its status and body pass through.
     */
    @Test
    void reachedServicePassesThrough() {
        RestoFlashResource resource = storeResource(Optional.empty());
        when(resource.relay.relay(any(), eq("POST"), any(), any(), anyLong()))
                .thenReturn(new ExternalRelayService.RelayResult(true, 202, "{\"accepted\":true}"));
        Response response = resource.confirm("{\"token\":\"T\",\"amount\":5}", null);
        assertEquals(202, response.getStatus());
        assertEquals("{\"accepted\":true}", response.getEntity());
    }
    /** LC-07/LC-08: a correct configured node token passes the gate and preserves the relay's safe fallback. */
    @Test
    void configuredTokenAcceptsTheAuthenticatedRequest() {
        var resource = storeResource(Optional.of("TOKEN"));
        when(resource.relay.relay(any(), any(), any(), any(), anyLong())).thenReturn(new ExternalRelayService.RelayResult(false, 0, ""));
        assertEquals(200, resource.confirm("{}", "TOKEN").getStatus());
        org.mockito.Mockito.verify(resource.relay).relay(any(), any(), any(), any(), anyLong());
    }

}
