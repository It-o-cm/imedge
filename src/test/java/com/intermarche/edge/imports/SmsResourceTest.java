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
 * Unit tests for {@link SmsResource}: the gate arms (off-role, bad token) and
 * both relay outcomes (gateway reached / not-sent default), EXP-48.
 */
class SmsResourceTest {

    /**
     * Builds a store-role resource with the given token and a mocked relay.
     *
     * @param token the configured shared token
     * @return the resource
     */
    private SmsResource storeResource(Optional<String> token) {
        SmsResource resource = new SmsResource();
        resource.role = "store";
        resource.token = token;
        resource.gatewayUrl = Optional.of("http://sms.local");
        resource.relay = mock(ExternalRelayService.class);
        return resource;
    }

    /**
     * A node that is not a store refuses the send (403).
     */
    @Test
    void offRoleIsForbidden() {
        SmsResource resource = new SmsResource();
        resource.role = "register";
        resource.token = Optional.empty();
        resource.relay = mock(ExternalRelayService.class);
        assertEquals(403, resource.send("{}", null).getStatus());
    }

    /**
     * A configured token the caller does not present is rejected (401).
     */
    @Test
    void badTokenIsUnauthorized() {
        SmsResource resource = storeResource(Optional.of("secret"));
        assertEquals(401, resource.send("{}", "wrong").getStatus());
    }

    /**
     * When the gateway is unreachable, the not-sent default is returned.
     */
    @Test
    @SuppressWarnings("unchecked")
    void unreachableGatewayNotSent() {
        SmsResource resource = storeResource(Optional.empty());
        when(resource.relay.relay(any(), eq("POST"), any(), any(), anyLong()))
                .thenReturn(new ExternalRelayService.RelayResult(false, 0, ""));
        Response response = resource.send("{\"ticket\":\"T1\",\"phone\":\"0600\"}", null);
        assertEquals(200, response.getStatus());
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals(false, body.get("sent"));
    }

    /**
     * When the gateway answers, its status and body pass through.
     */
    @Test
    void reachedGatewayPassesThrough() {
        SmsResource resource = storeResource(Optional.empty());
        when(resource.relay.relay(any(), eq("POST"), any(), any(), anyLong()))
                .thenReturn(new ExternalRelayService.RelayResult(true, 200, "{\"sent\":true}"));
        Response response = resource.send("{\"ticket\":\"T1\",\"phone\":\"0600\"}", null);
        assertEquals(200, response.getStatus());
        assertEquals("{\"sent\":true}", response.getEntity());
    }
    /** LC-07/LC-08: a correct configured node token passes the gate and preserves the relay's safe fallback. */
    @Test
    void configuredTokenAcceptsTheAuthenticatedRequest() {
        var resource = storeResource(Optional.of("TOKEN"));
        when(resource.relay.relay(any(), any(), any(), any(), anyLong())).thenReturn(new ExternalRelayService.RelayResult(false, 0, ""));
        assertEquals(200, resource.send("{}", "TOKEN").getStatus());
        org.mockito.Mockito.verify(resource.relay).relay(any(), any(), any(), any(), anyLong());
    }

}
