package com.intermarche.pos.ui;

import com.intermarche.pos.service.PosSettingsService;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LockCheckFilter}.
 * <p>
 * The filter is a plain JAX-RS request filter: it reads the path off a mocked
 * {@link ContainerRequestContext}, consults a real {@link PosState} for the
 * lock state and the idle clock, a mocked {@link PosSettingsService} for the
 * idle-lockout delay, and either lets the request through or aborts it toward
 * {@code /lock}. Time is the real wall clock; expiry is exercised by pushing
 * the last-activity stamp into the past. No database and no Quarkus context is
 * booted.
 * <p>
 * Branch enumeration (every arm exercised — 100%): the path normalization
 * (leading-slash present / absent), the idle-expiry compound guard (delay-zero
 * arm, no-prior-activity arm, not-expired arm, all-true expiry arm), the
 * post-check activity clock (cashier-path reset arm / allowlisted no-reset
 * arm) and the locked dispatch (allowlisted pass arm / redirect arm).
 */
class LockCheckFilterTest {

    /**
     * Builds a filter over a real state and a mocked settings service.
     *
     * @param state the register state
     * @param idleSeconds the idle-lockout delay the settings service returns
     * @return the wired filter
     */
    private LockCheckFilter newFilter(PosState state, long idleSeconds) {
        LockCheckFilter filter = new LockCheckFilter();
        filter.state = state;
        filter.posSettingsService = mock(PosSettingsService.class);
        when(filter.posSettingsService.idleLockoutSeconds()).thenReturn(idleSeconds);
        filter.technicalEventService = mock(com.intermarche.pos.service.TechnicalEventService.class);
        return filter;
    }

    /**
     * Builds a request context whose path is fixed.
     *
     * @param path the value {@code getUriInfo().getPath()} returns
     * @return the mocked context
     */
    private ContainerRequestContext ctxFor(String path) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn(path);
        return ctx;
    }

    /**
     * Builds an unlocked register state.
     *
     * @return the unlocked state
     */
    private PosState unlocked() {
        PosState state = new PosState();
        state.auth.isLocked = false;
        return state;
    }

    /**
     * A locked register redirects a cashier-facing request to {@code /lock}
     * (locked arm, non-allowlisted arm), the path normalized from a
     * leading-slash-less raw value.
     *
     * @throws Exception never in practice
     */
    @Test
    void lockedCashierPathRedirects() throws Exception {
        PosState state = new PosState();
        LockCheckFilter filter = newFilter(state, 0);
        ContainerRequestContext ctx = ctxFor("sale");
        filter.filter(ctx);
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        assertEquals(303, captor.getValue().getStatus());
        assertEquals("/lock", captor.getValue().getLocation().toString());
    }

    /**
     * A locked register lets an allowlisted surface through (allowlisted arm),
     * the raw path already carrying a leading slash (normalization true arm).
     *
     * @throws Exception never in practice
     */
    @Test
    void lockedAllowlistedPathPasses() throws Exception {
        PosState state = new PosState();
        LockCheckFilter filter = newFilter(state, 0);
        ContainerRequestContext ctx = ctxFor("/api/pos/scan");
        filter.filter(ctx);
        verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
    }

    /**
     * Every CSV import surface passes on a locked register. They are machine
     * surfaces fed by the back office, not cashier screens, and a register spends
     * most of its life locked: an import that is not allowlisted is answered with a
     * redirect to the lock page and never runs — silently, since the caller is a
     * script reading a body that never comes.
     *
     * @throws Exception never in practice
     */
    @Test
    void lockedImportSurfacesAllPass() throws Exception {
        PosState state = new PosState();
        LockCheckFilter filter = newFilter(state, 0);
        for (String path : java.util.List.of("/products/import", "/prices/import",
                "/product-families/import", "/stores/import", "/employees/import",
                "/feeds/import/STORE_GROUPS")) {
            ContainerRequestContext ctx = ctxFor(path);
            filter.filter(ctx);
            verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
        }
    }

    /**
     * INFO ARTICLE (EXP-93) answers on a locked register: the read-only lookup is
     * on the allowlist, so a caisse fermée can consult an article without a login.
     *
     * @throws Exception never in practice
     */
    @Test
    void lockedArticleInfoPasses() throws Exception {
        PosState state = new PosState();
        LockCheckFilter filter = newFilter(state, 0);
        ContainerRequestContext ctx = ctxFor("/article-info");
        filter.filter(ctx);
        verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
    }

    /**
     * An unlocked register on a cashier path with the idle pause disabled
     * (delay-zero arm) passes and stamps the activity clock (reset arm).
     *
     * @throws Exception never in practice
     */
    @Test
    void unlockedDisabledIdleStampsClock() throws Exception {
        PosState state = unlocked();
        state.auth.lastActivityAt = 0L;
        LockCheckFilter filter = newFilter(state, 0);
        ContainerRequestContext ctx = ctxFor("sale");
        filter.filter(ctx);
        verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
        assertFalse(state.isLocked());
        assertTrue(state.auth.lastActivityAt > 0L);
    }

    /**
     * An unlocked register on an allowlisted poll does NOT reset the activity
     * clock (no-reset arm), the idle guard skipped for want of prior activity
     * (no-prior-activity arm).
     *
     * @throws Exception never in practice
     */
    @Test
    void unlockedAllowlistedPollLeavesClock() throws Exception {
        PosState state = unlocked();
        state.auth.lastActivityAt = 0L;
        LockCheckFilter filter = newFilter(state, 60);
        ContainerRequestContext ctx = ctxFor("ticket-fragment");
        filter.filter(ctx);
        verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
        assertEquals(0L, state.auth.lastActivityAt);
    }

    /**
     * An unlocked register whose last activity is recent is not expired
     * (not-expired arm): it stays unlocked and passes, the clock refreshed.
     *
     * @throws Exception never in practice
     */
    @Test
    void unlockedRecentActivityNotExpired() throws Exception {
        PosState state = unlocked();
        long recent = System.currentTimeMillis() - 1000L;
        state.auth.lastActivityAt = recent;
        LockCheckFilter filter = newFilter(state, 60);
        ContainerRequestContext ctx = ctxFor("sale");
        filter.filter(ctx);
        verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
        assertFalse(state.isLocked());
        assertTrue(state.auth.lastActivityAt >= recent);
    }

    /**
     * An unlocked register whose last activity is older than the delay expires
     * (all-true expiry arm): the filter logs the operator out and, the register
     * now locked, redirects the cashier path to {@code /lock}.
     *
     * @throws Exception never in practice
     */
    @Test
    void unlockedIdleExpiryLogsOutAndRedirects() throws Exception {
        PosState state = unlocked();
        state.auth.lastActivityAt = System.currentTimeMillis() - 120_000L;
        state.auth.operatorBadgeId = "12341234";
        long beforeVersion = state.version;
        LockCheckFilter filter = newFilter(state, 60);
        ContainerRequestContext ctx = ctxFor("sale");
        filter.filter(ctx);
        assertTrue(state.isLocked());
        assertTrue(state.version > beforeVersion);
        verify(filter.technicalEventService).log(
                com.intermarche.pos.domain.session.TechnicalEvent.EventType.REGISTER_LOCKED,
                null, "12341234");
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        assertEquals("/lock", captor.getValue().getLocation().toString());
    }

    /**
     * A register that is not expired never journals a pause (no-op arm of the
     * idle guard): the emitter is left untouched on a recent-activity request.
     *
     * @throws Exception never in practice
     */
    @Test
    void unlockedNotExpiredNeverLogsPause() throws Exception {
        PosState state = unlocked();
        state.auth.lastActivityAt = System.currentTimeMillis() - 1000L;
        LockCheckFilter filter = newFilter(state, 60);
        ContainerRequestContext ctx = ctxFor("sale");
        filter.filter(ctx);
        verify(filter.technicalEventService, never()).log(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * The idle timeout VERROUILLE rather than logs out (EXP-12): it keeps the
     * operator identity aside so the same cashier resumes with only their PIN.
     * After the pause the register is locked and paused, the active operator is
     * cleared, and the former identity is held in the locked-operator fields.
     *
     * @throws Exception never in practice
     */
    @Test
    void unlockedIdleExpiryPausesKeepingIdentity() throws Exception {
        PosState state = unlocked();
        state.auth.login(5L, "Alice", "12341234");
        state.auth.isLocked = false;
        state.auth.lastActivityAt = System.currentTimeMillis() - 120_000L;
        LockCheckFilter filter = newFilter(state, 60);
        ContainerRequestContext ctx = ctxFor("sale");
        filter.filter(ctx);
        assertTrue(state.isLocked());
        assertTrue(state.auth.isPaused());
        assertNull(state.auth.operatorId);
        assertEquals(5L, state.auth.lockedOperatorId);
        assertEquals("12341234", state.auth.lockedOperatorBadgeId);
    }

    /**
     * The paused supervisor force actions must answer while the register is
     * locked (they are how a paused, abandoned post is recovered): both
     * {@code /action/force-unlock} and {@code /action/force-close} pass the lock
     * guard rather than being redirected to {@code /lock}.
     *
     * @throws Exception never in practice
     */
    @Test
    void lockedForceActionsPass() throws Exception {
        PosState state = new PosState();
        LockCheckFilter filter = newFilter(state, 0);
        for (String path : java.util.List.of("/action/force-unlock", "/action/force-close")) {
            ContainerRequestContext ctx = ctxFor(path);
            filter.filter(ctx);
            verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
        }
    }

    /**
     * The shared supervisor panel's badge poll must answer while the register is
     * LOCKED: the panel lives on the paused lock screen too, and a poll bounced
     * back to {@code /lock} would arm nothing — the supervisor's badge would go
     * on being thrown away on the very screen that fixed it first.
     *
     * @throws Exception never in practice
     */
    @Test
    void lockedSupervisorBadgePollPasses() throws Exception {
        PosState state = new PosState();
        LockCheckFilter filter = newFilter(state, 0);
        ContainerRequestContext ctx = ctxFor("/supervisor-badge-data");
        filter.filter(ctx);
        verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
    }

    /**
     * The LANGUAGE screen and its action answer on a LOCKED register
     * ({@code LC-13-06-03}).
     *
     * <p>The one cashier screen that must: an operator who cannot read the till's
     * language changes it BEFORE signing in, and the lock screen is the only screen
     * they ever see. Bounced back to {@code /lock} by this filter, the key looked
     * dead — which is exactly what happened on the till.
     *
     * @throws Exception never in practice
     */
    @Test
    void lockedLanguageChoicePasses() throws Exception {
        PosState state = new PosState();
        LockCheckFilter filter = newFilter(state, 0);
        for (String path : java.util.List.of("/language-select", "/action/language")) {
            ContainerRequestContext ctx = ctxFor(path);
            filter.filter(ctx);
            verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
        }
    }

    /**
     * BO-10-02-28: the idle delay VERROUILLE by default — the operator is held
     * aside and resumes with their own code — and FERME when the shop
     * administers it, which drops the identity altogether.
     *
     * @throws Exception never in practice
     */
    @Test
    void theIdleDelayLocksOrClosesAsAdministered() throws Exception {
        PosState paused = unlocked();
        paused.auth.operatorId = 7L;
        paused.auth.operatorBadgeId = "1234";
        paused.auth.lastActivityAt = System.currentTimeMillis() - 5000L;
        LockCheckFilter lock = newFilter(paused, 1);
        lock.filter(ctxFor("sale"));
        assertTrue(paused.isLocked());
        assertTrue(paused.auth.isPaused());

        PosState closed = unlocked();
        closed.auth.operatorId = 7L;
        closed.auth.operatorBadgeId = "1234";
        closed.auth.lastActivityAt = System.currentTimeMillis() - 5000L;
        LockCheckFilter close = newFilter(closed, 1);
        when(close.posSettingsService.idleAction()).thenReturn("  close  ");
        close.filter(ctxFor("sale"));
        assertTrue(closed.isLocked());
        assertFalse(closed.auth.isPaused());
    }
}
