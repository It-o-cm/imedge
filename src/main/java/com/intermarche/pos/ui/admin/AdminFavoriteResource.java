package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.CrudOption;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.setting.Feature;
import com.intermarche.pos.domain.people.Menu;
import com.intermarche.pos.domain.people.UserFavorite;
import com.intermarche.pos.service.PermissionService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The FAVORITES self-service screen ({@code /admin/favorites}, BO-01-04-08):
 * within the limits of what their profiles allow, an account hides the
 * features it does not want cluttering its interface.
 * <p>
 * This is a personal convenience, not a right: the screen only offers the
 * features the signed-in account may actually {@link CrudOption#VIEW view}
 * (asked of {@link PermissionService}), and hiding one changes DISPLAY alone —
 * {@link UserFavorite} never widens nor narrows authorization. It therefore
 * needs no special permission of its own: any signed-in back-office account
 * administers its own favorites and no one else's.
 */
@Path("/admin/favorites")
public class AdminFavoriteResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminFavoriteResource.class);

    /** The favorites template. */
    @Inject
    @Location("admin-favorites")
    Template adminFavorites;

    /** The administered authorization layer, consulted for viewable features. */
    @Inject
    PermissionService permissionService;

    /** The signed-in operator. */
    @Inject
    SecurityIdentity identity;

    /** One rendered feature the account may view, with its hidden state. */
    public static class FavoriteRow {
        /** The feature key (posted token). */
        public String key;
        /** The feature label. */
        public String label;
        /** The menu label. */
        public String menu;
        /** Whether this row opens a new menu group. */
        public boolean firstOfMenu;
        /** Whether the account currently hides the feature. */
        public boolean hidden;
    }

    /**
     * Shows the favorites of the signed-in account.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page, or a redirect when the account cannot be resolved
     */
    @GET
    @Authenticated
    public Object favoritesPage(@QueryParam("notice") String notice,
                                @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method favoritesPage with notice: " + notice + ", noticeOk: " + noticeOk);
        Employee employee = currentEmployee();
        if (employee == null) {
            LOGGER.info("Exiting method favoritesPage");
            return redirect("Compte introuvable.", false);
        }
        Set<String> hidden = UserFavorite.hiddenKeys(employee.id);
        List<FavoriteRow> rows = new ArrayList<>();
        String loginName = employee.loginName;
        Menu lastMenu = null;
        for (Feature feature : Feature.values()) {
            if (!permissionService.isAllowed(loginName, feature, CrudOption.VIEW)) {
                continue;
            }
            FavoriteRow row = new FavoriteRow();
            row.key = feature.getKey();
            row.label = feature.getLabel();
            row.menu = feature.getMenu().getLabel();
            row.firstOfMenu = feature.getMenu() != lastMenu;
            row.hidden = hidden.contains(feature.getKey());
            lastMenu = feature.getMenu();
            rows.add(row);
        }
        LOGGER.info("Exiting method favoritesPage");
        return adminFavorites.data("rows", rows)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Rewrites the account's hidden-feature set from the posted selection.
     * <p>
     * Only keys the account may actually view are honoured, so a forged post
     * cannot record a preference on a feature the profiles do not grant.
     *
     * @param form the posted form, one {@code hide} value per feature to hide
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Authenticated
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save with form: " + form);
        Employee employee = currentEmployee();
        if (employee == null) {
            LOGGER.info("Exiting method save");
            return redirect("Compte introuvable.", false);
        }
        UserFavorite.clearFor(employee.id);
        List<String> hide = form.get("hide");
        if (hide != null) {
            String loginName = employee.loginName;
            for (String key : hide) {
                Feature feature = Feature.fromKey(key);
                if (feature != null && permissionService.isAllowed(loginName, feature, CrudOption.VIEW)) {
                    UserFavorite row = new UserFavorite();
                    row.employeeId = employee.id;
                    row.featureKey = feature.getKey();
                    row.persist();
                }
            }
        }
        LOGGER.info("Exiting method save");
        return redirect("Préférences d'affichage enregistrées.", true);
    }

    /**
     * Resolves the signed-in employee from the identity's login name.
     *
     * @return the employee, or null when none matches
     */
    private Employee currentEmployee() {
        return Employee.findActiveLogin(identity.getPrincipal().getName());
    }

    /**
     * Builds the 303 redirect back to the page carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/favorites?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }
}
