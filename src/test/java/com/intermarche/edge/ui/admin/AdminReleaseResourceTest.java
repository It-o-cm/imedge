package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.service.sync.store.ReferentialReleaseService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminReleaseResource} (BO-03-12-03, BO-03-12-04): the
 * page armed and not, and each gesture's refusal and success arms.
 */
class AdminReleaseResourceTest {

    /**
     * Builds a resource on a mocked engine, template and identity.
     *
     * @param user the signed-in login, or null for no principal
     * @return the resource
     */
    private AdminReleaseResource resource(String user) {
        AdminReleaseResource resource = new AdminReleaseResource();
        resource.adminReleases = mock(Template.class);
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminReleases.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        resource.releaseService = mock(ReferentialReleaseService.class);
        resource.identity = mock(SecurityIdentity.class);
        if (user != null) {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn(user);
            when(resource.identity.getPrincipal()).thenReturn(principal);
        }
        return resource;
    }

    /**
     * Builds a form.
     *
     * @param pairs field, value, field, value…
     * @return the form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            form.putSingle(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    /**
     * Reads the decoded notice of a redirect.
     *
     * @param response the redirect
     * @return the decoded location
     */
    private String notice(Response response) {
        return URLDecoder.decode(response.getLocation().toString(), StandardCharsets.UTF_8);
    }

    /** The screen is declared in the parametrage menu. */
    @Test
    void theScreenIsDeclared() {
        assertEquals("/admin/releases", Feature.REFERENTIAL_RELEASE.getPath());
        assertEquals("Déploiements programmés", Feature.REFERENTIAL_RELEASE.getLabel());
    }

    /** The page shows the armed date, or none. */
    @Test
    void thePageShowsTheArmedDateOrNone() {
        AdminReleaseResource resource = resource("admin");
        when(resource.releaseService.armedAt("admin")).thenReturn(LocalDateTime.of(2026, 11, 1, 6, 0));
        when(resource.releaseService.pending()).thenReturn(List.of());
        assertNotNull(resource.page(null, true));
        verify(resource.adminReleases).data("armedAt", "01/11/2026 06:00");
        AdminReleaseResource unarmed = resource(null);
        assertNotNull(unarmed.page("x", false));
        verify(unarmed.adminReleases).data("armedAt", null);
    }

    /** Arming needs a readable date the engine accepts. */
    @Test
    void armingNeedsAnAcceptedDate() {
        AdminReleaseResource resource = resource("admin");
        LocalDateTime at = LocalDateTime.of(2030, 11, 1, 6, 0);
        when(resource.releaseService.arm("admin", at)).thenReturn(true);
        assertTrue(notice(resource.arm(form("at", "2030-11-01T06:00"))).contains("noticeOk=true"));
        assertTrue(notice(resource.arm(form("at", "2030-11-01T06:00"))).contains("le 01/11/2030 06:00."));
        assertTrue(notice(resource.arm(form("at", "demain"))).contains(AdminReleaseResource.INVALID_DATE));
        assertTrue(notice(resource.arm(form())).contains("noticeOk=false"));
    }

    /** Disarming makes the changes immediate again. */
    @Test
    void disarmingMakesTheChangesImmediate() {
        AdminReleaseResource resource = resource("admin");
        assertTrue(notice(resource.disarm()).contains("immédiatement"));
        verify(resource.releaseService).disarm("admin");
    }

    /** A complete deployment needs a date to come. */
    @Test
    void aCompleteDeploymentNeedsADateToCome() {
        AdminReleaseResource resource = resource("admin");
        when(resource.releaseService.programComplete(eq("admin"), any())).thenReturn(List.of("PRICES", "TENDERS"));
        assertTrue(notice(resource.complete(form("at", "2001-01-01T06:00"))).contains("noticeOk=false"));
        assertTrue(notice(resource.complete(form("at", "2030-11-01T06:00")))
                .contains("Déploiement complet programmé le 01/11/2030 06:00 : 2 domaine(s) retenu(s)."));
    }

    /** Bringing forward needs a known pending release. */
    @Test
    void bringingForwardNeedsAKnownRelease() {
        AdminReleaseResource resource = resource("admin");
        when(resource.releaseService.reschedule(eq(5L), any(), eq("admin"))).thenReturn(true);
        assertTrue(notice(resource.advance(form())).contains(AdminReleaseResource.UNKNOWN));
        assertTrue(notice(resource.advance(form("id", "6"))).contains("noticeOk=false"));
        assertTrue(notice(resource.advance(form("id", "5"))).contains("Déploiement avancé"));
    }

    /** Moving a release needs a date to come and a known pending release. */
    @Test
    void movingNeedsADateAndAKnownRelease() {
        AdminReleaseResource resource = resource("admin");
        LocalDateTime at = LocalDateTime.of(2030, 11, 3, 6, 0);
        when(resource.releaseService.reschedule(5L, at, "admin")).thenReturn(true);
        assertTrue(notice(resource.reschedule(form("id", "5"))).contains(AdminReleaseResource.INVALID_DATE));
        assertTrue(notice(resource.reschedule(form("at", "2030-11-03T06:00"))).contains(AdminReleaseResource.UNKNOWN));
        assertTrue(notice(resource.reschedule(form("id", "6", "at", "2030-11-03T06:00")))
                .contains("noticeOk=false"));
        assertTrue(notice(resource.reschedule(form("id", "5", "at", "2030-11-03T06:00")))
                .contains("Déploiement reprogrammé le 03/11/2030 06:00."));
    }

    /** Cancelling needs a known pending release. */
    @Test
    void cancellingNeedsAKnownRelease() {
        AdminReleaseResource resource = resource("admin");
        when(resource.releaseService.cancel(5L, "admin")).thenReturn(true);
        assertTrue(notice(resource.cancel(form())).contains("noticeOk=false"));
        assertTrue(notice(resource.cancel(form("id", "6"))).contains("noticeOk=false"));
        assertTrue(notice(resource.cancel(form("id", "5"))).contains("Déploiement annulé"));
    }

    /** A dated parameter is brought forward or cancelled when known. */
    @Test
    void aDatedParameterIsAdvancedOrCancelled() {
        AdminReleaseResource resource = resource("admin");
        when(resource.releaseService.advanceSetting(9L)).thenReturn(true);
        when(resource.releaseService.cancelSetting(9L)).thenReturn(true);
        assertTrue(notice(resource.advanceSetting(form())).contains("noticeOk=false"));
        assertTrue(notice(resource.advanceSetting(form("id", "8"))).contains("noticeOk=false"));
        assertTrue(notice(resource.advanceSetting(form("id", "9"))).contains("Paramètre daté appliqué"));
        assertTrue(notice(resource.cancelSetting(form())).contains("noticeOk=false"));
        assertTrue(notice(resource.cancelSetting(form("id", "8"))).contains("noticeOk=false"));
        assertTrue(notice(resource.cancelSetting(form("id", "9"))).contains("Paramètre daté annulé."));
    }

    /** A date is read from a datetime-local value, and kept only when to come. */
    @Test
    void datesAreReadAndKeptWhenToCome() {
        assertNull(AdminReleaseResource.minute(null));
        assertNull(AdminReleaseResource.minute("bad"));
        assertEquals(LocalDateTime.of(2030, 1, 2, 3, 4), AdminReleaseResource.minute(" 2030-01-02T03:04 "));
        assertNull(AdminReleaseResource.future(null));
        assertNull(AdminReleaseResource.future(LocalDateTime.now().minusMinutes(1)));
        LocalDateTime later = LocalDateTime.now().plusDays(1);
        assertEquals(later, AdminReleaseResource.future(later));
    }

    /** Without an identity the gestures act for nobody. */
    @Test
    void withoutAnIdentityTheGesturesActForNobody() {
        AdminReleaseResource resource = resource(null);
        resource.identity = null;
        resource.disarm();
        verify(resource.releaseService).disarm(null);
        verify(resource.releaseService, never()).disarm("admin");
    }
}
