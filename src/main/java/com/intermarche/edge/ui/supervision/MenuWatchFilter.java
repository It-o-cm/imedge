package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.TechnicalEventService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Follows the ACCESSES to the back-office menus the store chose to watch
 * (BO-04-03-04): each opening of a watched screen is journalled with the
 * operator's badge and the screen's code, beside the sign-ins, so the
 * electronic journal answers « who opened what, and when ».
 * <p>
 * Only a SCREEN counts — a GET asking for a page; a poll or a form posted
 * is not an opening. A journal momentarily unwritable never refuses the
 * screen.
 */
@Provider
@Priority(Priorities.USER + 60)
public class MenuWatchFilter implements ContainerRequestFilter {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(MenuWatchFilter.class);

    /** The resource method the request reaches. */
    @Context
    ResourceInfo resourceInfo;

    /** The watched menus. */
    @Inject
    PosSettingsService posSettingsService;

    /** The journal. */
    @Inject
    TechnicalEventService technicalEventService;

    /** Who is signed in. */
    @Inject
    CurrentUser currentUser;

    /**
     * Journals the opening of a watched screen.
     *
     * @param request the request
     */
    @Override
    public void filter(ContainerRequestContext request) {
        Method method = resourceInfo == null ? null : resourceInfo.getResourceMethod();
        FeatureAccess access = method == null ? null : method.getAnnotation(FeatureAccess.class);
        String accept = request.getHeaderString("Accept");
        if (access == null || !HttpMethod.GET.equals(request.getMethod())
                || accept == null || !accept.contains("text/html")) {
            return;
        }
        String path = "/" + request.getUriInfo().getPath().replaceFirst("^/", "");
        try {
            String key = access.value().getKey();
            if (!watched(posSettingsService.watchedMenus(), key, path)) {
                return;
            }
            String login = currentUser.getName();
            QuarkusTransaction.requiringNew().run(() -> {
                Employee employee = Employee.find("loginName", login).firstResult();
                technicalEventService.record(TechnicalEvent.EventType.BACK_OFFICE_MENU_OPENED,
                        login + " : " + access.value().getLabel() + " (" + path + ")",
                        employee == null ? null : employee.badgeId, null, null, key);
            });
        } catch (RuntimeException trouble) {
            LOGGER.warnf("Accès au menu non journalisé (%s)", trouble.getMessage());
        }
    }

    /**
     * Whether a screen is watched: named by its code or by the beginning of
     * its path, or every screen under {@code *}.
     *
     * @param administered the administered list, possibly null
     * @param key the screen's code
     * @param path the screen's path
     * @return true when its opening is journalled
     */
    static boolean watched(String administered, String key, String path) {
        if (administered == null) {
            return false;
        }
        for (String part : administered.split(";")) {
            String wanted = part.trim().toLowerCase(Locale.ROOT);
            if (wanted.isEmpty()) {
                continue;
            }
            if ("*".equals(wanted) || wanted.equals(key) || (wanted.startsWith("/") && path.startsWith(wanted))) {
                return true;
            }
        }
        return false;
    }
}
