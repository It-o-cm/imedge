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
 * Unit tests for {@link GiftCardResource}: the gate arms (off-role, bad token)
 * and both relay outcomes (issuer reached / safe default), EXP-66.
 */
class GiftCardResourceTest {

    /**
     * Builds a store-role resource with the given token and a mocked relay.
     *
     * @param token the configured shared token
     * @return the resource
     */
    private GiftCardResource storeResource(Optional<String> token) {
        GiftCardResource resource = new GiftCardResource();
        resource.role = "store";
        resource.token = token;
        resource.issuerUrl = Optional.of("http://issuer.local");
        resource.relay = mock(ExternalRelayService.class);
        return resource;
    }

    /**
     * A node that is not a store refuses the query (403).
     */
    @Test
    void offRoleIsForbidden() {
        GiftCardResource resource = new GiftCardResource();
        resource.role = "register";
        resource.token = Optional.empty();
        resource.relay = mock(ExternalRelayService.class);
        assertEquals(403, resource.query("CARD1", null).getStatus());
    }

    /**
     * A configured token the caller does not present is rejected (401).
     */
    @Test
    void badTokenIsUnauthorized() {
        GiftCardResource resource = storeResource(Optional.of("secret"));
        assertEquals(401, resource.query("CARD1", "wrong").getStatus());
    }

    /**
     * When the issuer is unreachable, the safe default (invalid, zero) is returned.
     */
    @Test
    @SuppressWarnings("unchecked")
    void unreachableIssuerReturnsSafeDefault() {
        GiftCardResource resource = storeResource(Optional.empty());
        when(resource.relay.relay(any(), eq("GET"), eq("/CARD1"), any(), anyLong()))
                .thenReturn(new ExternalRelayService.RelayResult(false, 0, ""));
        Response response = resource.query("CARD1", null);
        assertEquals(200, response.getStatus());
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals(false, body.get("valid"));
        assertEquals(0, body.get("balance"));
    }

    /**
     * When the issuer answers, its status and body pass through.
     */
    @Test
    void reachedIssuerPassesThrough() {
        GiftCardResource resource = storeResource(Optional.empty());
        when(resource.relay.relay(any(), eq("GET"), eq("/CARD1"), any(), anyLong()))
                .thenReturn(new ExternalRelayService.RelayResult(true, 200, "{\"valid\":true,\"balance\":25}"));
        Response response = resource.query("CARD1", null);
        assertEquals(200, response.getStatus());
        assertEquals("{\"valid\":true,\"balance\":25}", response.getEntity());
    }
}
