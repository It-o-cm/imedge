package com.intermarche.edge.imports;

import com.intermarche.edge.service.sync.store.RefExportService;
import com.intermarche.edge.service.sync.RefPayloads;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RefExportResource}.
 * <p>
 * The resource is a thin JAX-RS front for {@link RefExportService}: two GET
 * endpoints ({@code /versions} and {@code /{domain}}) both funnel through the
 * private {@code gate} guard, which enforces the {@code pos.role} store-or-central
 * rule and an optional shared token, and a role ternary that serves the register
 * domains to a store and the echelon domains to a central. The three injected
 * members ({@code role},
 * {@code token}, {@code refExportService}) are package-private, so each test
 * sets them directly and mocks the service; no database and no Quarkus context
 * is booted. Every branch of the role gate, the token gate, the page-size
 * bounding ternary and the {@code IllegalArgumentException} catch is asserted
 * against absolute expected values.
 */
class RefExportResourceTest {

    /**
     * Builds a resource wired with the given role, token and a mock service.
     *
     * @param role    the {@code pos.role} value
     * @param token   the optional shared token
     * @param service the mock export service
     * @return the configured resource under test
     */
    private RefExportResource resource(String role, Optional<String> token, RefExportService service) {
        RefExportResource resource = new RefExportResource();
        resource.role = role;
        resource.token = token;
        resource.refExportService = service;
        return resource;
    }

    /**
     * {@code versions} refuses a node that is neither store nor central with 403
     * (gate role-check: both operands true) and never touches the service.
     */
    @Test
    void versionsForbiddenWhenRoleNotStore() {
        RefExportService service = mock(RefExportService.class);
        RefExportResource resource = resource("register", Optional.empty(), service);
        Response response = resource.versions(null, null);
        assertEquals(403, response.getStatus());
        assertEquals("Ce nœud n'a pas le rôle store ou central", response.getEntity());
        verifyNoInteractions(service);
    }

    /**
     * {@code versions} returns the REGISTER-facing fingerprints with 200 when the
     * node is a store and no token is configured (gate role-check first operand
     * false, token blank arm, versions {@code gate == null} arm, role ternary
     * non-central arm).
     */
    @Test
    void versionsReturnsFingerprintsWhenAllowed() {
        RefExportService service = mock(RefExportService.class);
        Map<String, String> fingerprints = Map.of("PRODUCTS", "abc");
        when(service.getFingerprints(RefPayloads.DOMAINS, null)).thenReturn(fingerprints);
        RefExportResource resource = resource("store", Optional.empty(), service);
        Response response = resource.versions(null, null);
        assertEquals(200, response.getStatus());
        assertSame(fingerprints, response.getEntity());
    }

    /**
     * {@code versions} on a CENTRAL node returns the ECHELON fingerprints (gate
     * role-check first operand true, second operand false, role ternary central
     * arm), proving a store node's upstream serves the echelon domains.
     */
    @Test
    void versionsReturnsEchelonFingerprintsForCentralRole() {
        RefExportService service = mock(RefExportService.class);
        Map<String, String> fingerprints = Map.of("COUNTRIES", "abc");
        when(service.getFingerprints(RefPayloads.ECHELON_DOMAINS, null)).thenReturn(fingerprints);
        RefExportResource resource = resource("central", Optional.empty(), service);
        Response response = resource.versions(null, null);
        assertEquals(200, response.getStatus());
        assertSame(fingerprints, response.getEntity());
    }

    /**
     * {@code versions} refuses a wrong token with 401 (gate token non-blank arm,
     * mismatch true arm).
     */
    @Test
    void versionsUnauthorizedWhenTokenMismatch() {
        RefExportService service = mock(RefExportService.class);
        RefExportResource resource = resource("store", Optional.of("secret"), service);
        Response response = resource.versions("wrong", null);
        assertEquals(401, response.getStatus());
        assertEquals("Jeton invalide", response.getEntity());
        verifyNoInteractions(service);
    }

    /**
     * {@code versions} accepts a matching token (gate token non-blank arm,
     * mismatch false arm, {@code gate == null} arm) and returns the fingerprints.
     */
    @Test
    void versionsAllowedWhenTokenMatches() {
        RefExportService service = mock(RefExportService.class);
        Map<String, String> fingerprints = Map.of("PRODUCTS", "abc");
        when(service.getFingerprints(RefPayloads.DOMAINS, null)).thenReturn(fingerprints);
        RefExportResource resource = resource("store", Optional.of("secret"), service);
        Response response = resource.versions("secret", null);
        assertEquals(200, response.getStatus());
        assertSame(fingerprints, response.getEntity());
    }

    /**
     * {@code page} refuses a non-store node with 403 (page {@code gate != null}
     * arm) and never touches the service.
     */
    @Test
    void pageForbiddenWhenRoleNotStore() {
        RefExportService service = mock(RefExportService.class);
        RefExportResource resource = resource("register", Optional.empty(), service);
        Response response = resource.page(null, null, "products", 0, 100);
        assertEquals(403, response.getStatus());
        assertEquals("Ce nœud n'a pas le rôle store ou central", response.getEntity());
        verifyNoInteractions(service);
    }

    /**
     * {@code page} substitutes the default 1000 page size when the requested
     * size is non-positive (bounding ternary {@code size <= 0} true arm),
     * upper-cases the domain and clamps a negative page index to 0.
     */
    @Test
    void pageUsesDefaultSizeWhenSizeNonPositive() {
        RefExportService service = mock(RefExportService.class);
        List<?> payload = List.of("row");
        doReturn(payload).when(service).getPage("PRODUCTS", 0, 1000, null);
        RefExportResource resource = resource("store", Optional.empty(), service);
        Response response = resource.page(null, null, "products", -5, 0);
        assertEquals(200, response.getStatus());
        assertSame(payload, response.getEntity());
        verify(service).getPage("PRODUCTS", 0, 1000, null);
    }

    /**
     * {@code page} substitutes the default 1000 page size when the requested
     * size exceeds the 5000 cap (bounding ternary {@code size <= 0} false arm,
     * {@code size > 5000} true arm).
     */
    @Test
    void pageUsesDefaultSizeWhenSizeTooLarge() {
        RefExportService service = mock(RefExportService.class);
        List<?> payload = List.of("row");
        doReturn(payload).when(service).getPage("PRODUCTS", 1, 1000, null);
        RefExportResource resource = resource("store", Optional.empty(), service);
        Response response = resource.page(null, null, "products", 1, 6000);
        assertEquals(200, response.getStatus());
        assertSame(payload, response.getEntity());
        verify(service).getPage("PRODUCTS", 1, 1000, null);
    }

    /**
     * {@code page} passes an in-bounds size straight through (bounding ternary
     * both arms false) and returns the page payload with 200.
     */
    @Test
    void pageUsesProvidedSizeWhenWithinBounds() {
        RefExportService service = mock(RefExportService.class);
        List<?> payload = List.of("row");
        doReturn(payload).when(service).getPage("PRODUCTS", 2, 200, null);
        RefExportResource resource = resource("store", Optional.empty(), service);
        Response response = resource.page(null, null, "products", 2, 200);
        assertEquals(200, response.getStatus());
        assertSame(payload, response.getEntity());
        verify(service).getPage("PRODUCTS", 2, 200, null);
    }

    /**
     * {@code page} maps an {@link IllegalArgumentException} from the service to a
     * 400 carrying the exception message (catch arm).
     */
    @Test
    void pageReturnsBadRequestWhenServiceRejectsDomain() {
        RefExportService service = mock(RefExportService.class);
        when(service.getPage(eq("UNKNOWN"), eq(0), eq(1000), isNull()))
                .thenThrow(new IllegalArgumentException("Domaine inconnu"));
        RefExportResource resource = resource("store", Optional.empty(), service);
        Response response = resource.page(null, null, "unknown", 0, 0);
        assertEquals(400, response.getStatus());
        assertEquals("Domaine inconnu", response.getEntity());
    }

    /**
     * The {@code X-Pos-Terminal} header is handed to the service as it stands:
     * the fingerprints of a register that names itself are ITS fingerprints,
     * because its SETTINGS are resolved down to its own checkout point.
     */
    @Test
    void versionsPassesTheAskingTerminalThrough() {
        RefExportService service = mock(RefExportService.class);
        Map<String, String> fingerprints = Map.of("SETTINGS", "abc");
        when(service.getFingerprints(RefPayloads.DOMAINS, "POS07")).thenReturn(fingerprints);
        RefExportResource resource = resource("store", Optional.empty(), service);
        Response response = resource.versions(null, "POS07");
        assertEquals(200, response.getStatus());
        assertSame(fingerprints, response.getEntity());
        verify(service).getFingerprints(RefPayloads.DOMAINS, "POS07");
    }

    /**
     * The snapshot page is served for the SAME terminal that asked: the
     * fingerprint and the content must be computed from one identifier, or a
     * register would compare one lane's hash with another lane's rows and re-pull
     * for ever.
     */
    @Test
    void pagePassesTheAskingTerminalThrough() {
        RefExportService service = mock(RefExportService.class);
        List<Object> payload = List.of();
        doReturn(payload).when(service).getPage("SETTINGS", 0, 1000, "POS07");
        RefExportResource resource = resource("store", Optional.empty(), service);
        Response response = resource.page(null, "POS07", "settings", 0, 0);
        assertEquals(200, response.getStatus());
        verify(service).getPage("SETTINGS", 0, 1000, "POS07");
    }
}
