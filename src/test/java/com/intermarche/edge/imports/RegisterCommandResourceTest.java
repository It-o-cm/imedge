package com.intermarche.edge.imports;

import com.intermarche.edge.domain.sync.RegisterCommand;
import com.intermarche.edge.service.sync.RegisterCommandService;
import com.intermarche.edge.service.sync.RegisterPresenceService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RegisterCommandResource}.
 * <p>
 * The resource is a thin JAX-RS front over {@link RegisterCommandService}: its
 * four members ({@code role}, {@code token}, {@code registerCommandService},
 * {@code registerPresenceService})
 * are package-private, so each test assigns them directly and mocks the
 * service. No database and no Quarkus context is booted, and the JSON is
 * asserted as an exact string since the resource writes it by hand.
 * <p>
 * Branch enumeration (every arm exercised): {@code gate} covers the
 * {@code "register"} role arm — in both spellings, to prove the comparison
 * ignores case — and the three legs of
 * {@code token.isPresent() &amp;&amp; !isBlank() &amp;&amp; !equals(presented)}
 * (absent, present but blank, present and mismatched) plus the matching arm;
 * {@code pending} covers the refused arm, the empty list, the first-element
 * arm and the separator arm of its loop; {@code quoted} covers the null arm
 * and the escaping arm; {@code acknowledge} covers the refused arm and both
 * outcomes of the service.
 */
class RegisterCommandResourceTest {

    /**
     * Builds a resource wired with a role, a token and a mock order book, on a
     * mock presence service.
     *
     * @param role the {@code pos.role} value
     * @param token the optional shared token
     * @param service the mock order book
     * @return the configured resource under test
     */
    private RegisterCommandResource resource(String role, Optional<String> token,
                                             RegisterCommandService service) {
        return resource(role, token, service, mock(RegisterPresenceService.class));
    }

    /**
     * Builds a resource wired with a role, a token, a mock order book and the
     * presence service the resource stamps.
     *
     * @param role the {@code pos.role} value
     * @param token the optional shared token
     * @param service the mock order book
     * @param presence the mock presence service
     * @return the configured resource under test
     */
    private RegisterCommandResource resource(String role, Optional<String> token,
                                             RegisterCommandService service,
                                             RegisterPresenceService presence) {
        RegisterCommandResource resource = new RegisterCommandResource();
        resource.role = role;
        resource.token = token;
        resource.registerCommandService = service;
        resource.registerPresenceService = presence;
        return resource;
    }

    /**
     * Builds an order row.
     *
     * @param uid the order token
     * @param issuedBy who issued it, possibly null
     * @param reason why, possibly null
     * @return the order
     */
    private RegisterCommand command(String uid, String issuedBy, String reason) {
        RegisterCommand order = new RegisterCommand();
        order.commandUid = uid;
        order.terminalId = "POS07";
        order.commandType = RegisterCommand.CommandType.CLOSE_SESSION;
        order.issuedBy = issuedBy;
        order.reason = reason;
        return order;
    }

    /**
     * A node that is itself a register distributes no orders: 403, and the
     * order book is never touched.
     */
    @Test
    void pendingForbiddenOnARegisterNode() {
        RegisterCommandService service = mock(RegisterCommandService.class);
        Response response = resource("register", Optional.empty(), service).pending("POS07", null);
        assertEquals(403, response.getStatus());
        assertEquals("{\"error\":\"Ce nœud ne distribue pas d'ordres\"}", response.getEntity());
        verifyNoInteractions(service);
    }

    /**
     * The role comparison ignores case: a node configured {@code REGISTER} is
     * refused just the same.
     */
    @Test
    void pendingForbiddenWhateverTheSpellingOfTheRole() {
        RegisterCommandService service = mock(RegisterCommandService.class);
        Response response = resource("REGISTER", Optional.empty(), service).pending("POS07", null);
        assertEquals(403, response.getStatus());
        verifyNoInteractions(service);
    }

    /**
     * A configured token that the caller does not present is a 401, and the
     * order book is never touched.
     */
    @Test
    void pendingUnauthorizedOnAWrongToken() {
        RegisterCommandService service = mock(RegisterCommandService.class);
        Response response = resource("store", Optional.of("secret"), service).pending("POS07", "wrong");
        assertEquals(401, response.getStatus());
        assertEquals("{\"error\":\"Jeton de synchronisation invalide\"}", response.getEntity());
        verifyNoInteractions(service);
    }

    /**
     * A token configured as blank is no token at all: the caller passes
     * without presenting one, and a register with nothing waiting gets an
     * empty array.
     */
    @Test
    void pendingServesAnEmptyArrayWhenTheTokenIsBlank() {
        RegisterCommandService service = mock(RegisterCommandService.class);
        when(service.pending("POS07")).thenReturn(List.of());
        Response response = resource("store", Optional.of("  "), service).pending("POS07", null);
        assertEquals(200, response.getStatus());
        assertEquals("[]", response.getEntity());
    }

    /**
     * With no token configured, one waiting order is rendered whole, and its
     * null fields come out as the JSON literal null rather than the word.
     */
    @Test
    void pendingRendersASingleOrderAndItsNullFields() {
        RegisterCommandService service = mock(RegisterCommandService.class);
        when(service.pending("POS07")).thenReturn(List.of(command("u-1", null, null)));
        Response response = resource("store", Optional.empty(), service).pending("POS07", null);
        assertEquals(200, response.getStatus());
        assertEquals("[{\"uid\":\"u-1\",\"type\":\"CLOSE_SESSION\",\"issuedBy\":null,\"reason\":null}]",
                response.getEntity());
    }

    /**
     * Two orders are two elements of the array, and the text fields are
     * escaped by the JSON library: a backslash, a quote and a line break in a
     * free-text reason read back unchanged with a real parser.
     *
     * @throws Exception when the answer is not valid JSON
     */
    @Test
    void pendingSeparatesAndEscapesEveryOrder() throws Exception {
        RegisterCommandService service = mock(RegisterCommandService.class);
        when(service.pending("POS07")).thenReturn(List.of(
                command("u-1", "Marie Curie", "fin de service"),
                command("u-2", "C:\\poste", "dit \"maintenant\"")));
        Response response = resource("store", Optional.of("secret"), service).pending("POS07", "secret");
        assertEquals(200, response.getStatus());
        assertEquals("[{\"uid\":\"u-1\",\"type\":\"CLOSE_SESSION\",\"issuedBy\":\"Marie Curie\","
                        + "\"reason\":\"fin de service\"},"
                        + "{\"uid\":\"u-2\",\"type\":\"CLOSE_SESSION\",\"issuedBy\":\"C:\\\\poste\","
                        + "\"reason\":\"dit \\\"maintenant\\\"\"}]",
                response.getEntity());
        com.fasterxml.jackson.databind.JsonNode orders = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree((String) response.getEntity());
        assertEquals("C:\\poste", orders.get(1).get("issuedBy").asText());
        assertEquals("dit \"maintenant\"", orders.get(1).get("reason").asText());
    }

    /**
     * The acknowledgement is gated exactly like the collection.
     */
    @Test
    void acknowledgeForbiddenOnARegisterNode() {
        RegisterCommandService service = mock(RegisterCommandService.class);
        Response response = resource("register", Optional.empty(), service).acknowledge("u-1", null);
        assertEquals(403, response.getStatus());
        verifyNoInteractions(service);
    }

    /**
     * An acknowledgement the node could not match — unknown token, or an order
     * already stamped — is a 404 saying so.
     */
    @Test
    void acknowledgeAnswers404WhenNothingWasStamped() {
        RegisterCommandService service = mock(RegisterCommandService.class);
        when(service.acknowledge("ghost")).thenReturn(false);
        Response response = resource("central", Optional.empty(), service).acknowledge("ghost", null);
        assertEquals(404, response.getStatus());
        assertEquals("{\"acknowledged\":false}", response.getEntity());
    }

    /**
     * A stamped order answers 200, which is what tells the register it may stop
     * collecting that order.
     */
    @Test
    void acknowledgeAnswers200WhenTheOrderWasStamped() {
        RegisterCommandService service = mock(RegisterCommandService.class);
        when(service.acknowledge("u-1")).thenReturn(true);
        Response response = resource("store", Optional.of("secret"), service).acknowledge("u-1", "secret");
        assertEquals(200, response.getStatus());
        assertEquals("{\"acknowledged\":true}", response.getEntity());
        verify(service).acknowledge("u-1");
    }

    /**
     * A lane cycle that PASSES the gate is the register speaking, so it stamps
     * that terminal alive on the presence service; a cycle the gate refuses
     * (here, the wrong token) is not the register, and stamps nothing.
     */
    @Test
    void aServedCycleStampsTheRegisterAliveAndAGatedOneStampsNothing() {
        RegisterCommandService service = mock(RegisterCommandService.class);
        when(service.pending("POS07")).thenReturn(List.of());
        RegisterPresenceService presence = mock(RegisterPresenceService.class);
        resource("store", Optional.empty(), service, presence).pending("POS07", null);
        verify(presence).touch("POS07");
        RegisterPresenceService gatedPresence = mock(RegisterPresenceService.class);
        resource("store", Optional.of("secret"), service, gatedPresence).pending("POS07", "wrong");
        verifyNoInteractions(gatedPresence);
    }
}
