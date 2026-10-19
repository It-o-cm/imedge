package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.service.PosSettingsService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExternalAppResource} (BO-03-09-27).
 *
 * <p>Plain JUnit 5 + Mockito: the administered applications come from a mocked
 * {@link PosSettingsService}, which is the only thing this resource reads.
 *
 * <p>Branch enumeration: a rank the referential carries; a negative rank; a
 * rank past the end; and an empty referential.
 */
class ExternalAppResourceTest {

    /**
     * Builds a resource over the given administered applications.
     *
     * @param apps the administered applications
     * @return the wired resource
     */
    private ExternalAppResource newResource(List<PosSettingsService.ExternalApp> apps) {
        ExternalAppResource resource = new ExternalAppResource();
        resource.posSettingsService = mock(PosSettingsService.class);
        when(resource.posSettingsService.externalApps()).thenReturn(apps);
        return resource;
    }

    /**
     * The touch of an administered rank redirects to that application's
     * address, and the address never travels in the register's own URL.
     */
    @Test
    void anAdministeredRankRedirectsToItsApplication() {
        ExternalAppResource resource = newResource(List.of(
                new PosSettingsService.ExternalApp("Fid", "https://fid.magasin/caisse"),
                new PosSettingsService.ExternalApp("Photo", "https://photo.magasin/")));
        Response first = resource.open(0);
        assertEquals(303, first.getStatus());
        assertEquals("https://fid.magasin/caisse", first.getLocation().toString());
        Response second = resource.open(1);
        assertEquals("https://photo.magasin/", second.getLocation().toString());
    }

    /**
     * A rank the referential does not carry opens nothing: a touch pointing at
     * an entry the back office has since removed is simply gone with its row
     * (the three legs of the guard).
     */
    @Test
    void anUnknownRankOpensNothing() {
        ExternalAppResource resource = newResource(List.of(
                new PosSettingsService.ExternalApp("Fid", "https://fid.magasin/caisse")));
        assertEquals(404, resource.open(-1).getStatus());
        assertEquals(404, resource.open(1).getStatus());
        assertEquals(404, newResource(List.of()).open(0).getStatus());
    }
}
