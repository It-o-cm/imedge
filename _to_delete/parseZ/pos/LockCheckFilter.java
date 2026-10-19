package com.intermarche.pos.ui;

import com.intermarche.pos.domain.session.TechnicalEvent;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * JAX-RS filter enforcing the lock guard globally: while no cashier is
 * logged in ({@code state.isLocked()}), every cashier-facing request is
 * redirected to the lock page instead of re-rendering the current — more or
 * less invalid — screen under its own URL.
 * <p>
 * Runs at {@link Priorities#AUTHENTICATION}, i.e. BEFORE the drawer guard
 * ({@link DrawerCheckFilter}): a locked register wins over an open drawer.
 * <p>
 * The allowlist below names the surfaces that must keep answering while
 * locked — everything that is NOT a cashier screen:
 * <ul>
 *   <li>the lock flow itself: the lock page and its poll, and the PIN
 *       unlock action (the PIN-change flow requires a logged-in operator
 *       and is therefore NOT allowlisted);</li>
 *   <li>machine and hardware surfaces under {@code /api/} (the scan bus —
 *       which carries the login badge —, the hardware simulator, store
 *       synchronization, referential export, supervisor calls);</li>
 *   <li>public and customer-facing pages: the digital ticket and the
 *       customer display, which never show the cashier lock screen;</li>
 *   <li>the store-node dashboard, which has no cashier login at all;</li>
 *   <li>the polls of open screens ({@code /ticket-fragment},
 *       {@code /endorsement-data}): they must keep answering so the client
 *       can LEARN that the register locked — the fragment carries a
 *       {@code locked} flag and the page navigates to {@code /lock} itself;</li>
 *   <li>the CSV import endpoints (back-office machine surface);</li>
 *   <li>the LANGUAGE selection and its action ({@code LC-13-06-03}): the one
 *       cashier screen that must answer on a LOCKED register, because an
 *       operator who cannot read the till's language has to change it BEFORE
 *       signing in — the lock screen is the only screen they ever see. Letting
 *       the filter bounce it back to {@code /lock} made the key look dead.</li>
 * </ul>
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class LockCheckFilter implements ContainerRequestFilter {

    /** Path prefixes that keep answering while the register is locked. */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "/lock",
            "/action/unlock",
            "/action/force-unlock",
            "/action/force-close",
            "/article-info",
            "/api/",
            "/t/",
            "/customer",
            "/dashboard",
            "/admin",
            "/ticket-fragment",
            "/endorsement-data",
            "/supervisor-badge-data",
            "/products/import",
            "/prices/import",
            "/product-families/import",
            "/stores/import",
            "/employees/import",
            "/feeds/import",
            "/language-select",
            "/action/language");

    @Inject
    PosState state;

    /**
     * The journal emitter. The automatic idle pause (LC-01-03-02) is the
     * "départ en pause" gesture (BO-04-01-27): it is recorded here, naming the
     * operator being paused, through the single event mechanism — the enqueue
     * is asynchronous, so a network-severed register still pauses and sells.
     */
    @Inject
    com.intermarche.pos.service.TechnicalEventService technicalEventService;

    /**
     * The back-office parameters — the idle-lockout delay (LC-01-03-02)
     * now lives there, administered on {@code /admin/settings} and pulled
     * by the registers; the historical configuration key remains its
     * fallback inside the service.
     */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * Aborts the request toward the lock page when no cashier is logged in,
     * unless the requested path is one of the allowlisted non-cashier
     * surfaces.
     *
     * @param requestContext the JAX-RS request context
     * @throws IOException never in practice (filter contract)
     */
    /**
     * The administered idle action, trimmed and never null (BO-10-02-28).
     *
     * @return the action as the shop wrote it, blank when nothing is stored
     */
    private String trimmedAction() {
        String action = posSettingsService.idleAction();
        return action == null ? "" : action.trim();
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String raw = requestContext.getUriInfo().getPath();
        String path = raw.startsWith("/") ? raw : "/" + raw;
        if (!state.isLocked()) {
            // Automatic pause (LC-01-03-02): expire the session BEFORE the
            // lock check so this very request already lands on /lock.
            long last = state.auth.lastActivityAt;
            long idleLockoutSeconds = posSettingsService.idleLockoutSeconds();
            if (idleLockoutSeconds > 0 && last > 0
                    && System.currentTimeMillis() - last > idleLockoutSeconds * 1000L) {
                String pausedBadge = state.auth.operatorBadgeId;
                technicalEventService.log(TechnicalEvent.EventType.REGISTER_LOCKED,
                        null, pausedBadge);
                // VERROUILLAGE or FERMETURE, as the shop administers it
                // (BO-10-02-28). The pause (EXP-12) keeps the operator identity
                // aside and the in-memory ticket, so the same cashier resumes
                // their sale by keying only their PIN; the close drops the
                // identity altogether and the next person takes the post from
                // the beginning. Anything but CLOSE is the pause, which is what
                // every register did before the parameter existed.
                if ("CLOSE".equalsIgnoreCase(trimmedAction())) {
                    state.auth.logout();
                } else {
                    state.auth.pause();
                }
                state.touch();
            }
        }
        if (!state.isLocked()) {
            if (!isAllowedWhileLocked(path)) {
                // A cashier-facing request IS activity; the allowlisted
                // machine surfaces and polls never reset the idle clock.
                state.auth.lastActivityAt = System.currentTimeMillis();
            }
            return;
        }
        if (isAllowedWhileLocked(path)) {
            return;
        }
        requestContext.abortWith(
                Response.seeOther(URI.create("/lock")).build()
        );
    }

    /**
     * Returns true when the path may answer while the register is locked.
     *
     * @param path the normalized request path
     * @return true for allowlisted, non-cashier surfaces
     */
    private boolean isAllowedWhileLocked(String path) {
        for (String prefix : ALLOWED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
