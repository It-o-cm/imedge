package com.intermarche.edge.ui.admin;

import com.intermarche.edge.service.sync.store.ReferentialReleaseService;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReleaseBanner} (BO-03-12-03, BO-03-12-04): the armed
 * date and the held count for a signed-in administrator, nothing for nobody.
 */
class ReleaseBannerTest {

    /**
     * Builds a banner on a mocked engine.
     *
     * @param identity the identity, possibly null
     * @return the banner
     */
    private ReleaseBanner banner(SecurityIdentity identity) {
        ReleaseBanner banner = new ReleaseBanner();
        banner.releaseService = mock(ReferentialReleaseService.class);
        banner.identity = identity;
        return banner;
    }

    /**
     * Builds an identity.
     *
     * @param anonymous whether it is anonymous
     * @param user the principal's login, or null for no principal
     * @return the identity
     */
    private SecurityIdentity identity(boolean anonymous, String user) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(anonymous);
        if (user != null) {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn(user);
            when(identity.getPrincipal()).thenReturn(principal);
        }
        return identity;
    }

    /** A signed-in administrator sees his armed date, or nothing when he armed none. */
    @Test
    void anAdministratorSeesHisArmedDate() {
        ReleaseBanner banner = banner(identity(false, "admin"));
        when(banner.releaseService.armedAt("admin")).thenReturn(LocalDateTime.of(2026, 11, 1, 6, 0));
        when(banner.releaseService.pendingCount()).thenReturn(3);
        assertEquals("01/11/2026 06:00", banner.getArmedAt());
        assertEquals(3, banner.getPendingCount());
        ReleaseBanner unarmed = banner(identity(false, "jdupont"));
        assertEquals("", unarmed.getArmedAt());
    }

    /** Nobody signed in sees nothing, whatever the missing piece. */
    @Test
    void nobodySeesNothing() {
        assertEquals("", banner(null).getArmedAt());
        assertEquals(0, banner(null).getPendingCount());
        assertEquals("", banner(identity(true, "anonymous")).getArmedAt());
        assertEquals(0, banner(identity(true, null)).getPendingCount());
        assertEquals("", banner(identity(false, null)).getArmedAt());
    }
}
