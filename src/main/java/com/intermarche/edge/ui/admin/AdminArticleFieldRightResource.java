package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.ArticleFieldRight;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Profile;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.FeatureAccess;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
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
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The ARTICLE FIELD RIGHTS screen ({@code /admin/article-field-rights},
 * BO-02-03-33): one row per datum of the article fiche, one column per
 * profile, a tick granting that profile the right to change that datum.
 *
 * <p>A row with no tick leaves the datum open to every account allowed to save
 * the fiche; ticking a profile reserves it to the ticked profiles. Saving
 * replaces the whole grid, which is what the operator sees.
 */
@Path("/admin/article-field-rights")
public class AdminArticleFieldRightResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminArticleFieldRightResource.class);

    /** The screen the gestures return to. */
    static final String PAGE = "/admin/article-field-rights";

    /** The rights template. */
    @Inject
    @Location("admin-article-field-rights")
    Template adminArticleFieldRights;

    /** One rendered row of the grid: a datum and the profiles granted it. */
    public static class Row {
        /** The datum. */
        public ArticleField field;
        /** The ids of the profiles granted the datum. */
        public Set<Long> profileIds = new HashSet<>();
        /** Whether the datum is reserved to the ticked profiles. */
        public boolean restricted;
    }

    /**
     * Shows the grid.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_FIELD_RIGHT)
    @Transactional
    public TemplateInstance page(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method page");
        List<ArticleFieldRight> rights = ArticleFieldRight.listAllOrdered();
        List<Row> rows = new ArrayList<>();
        for (ArticleField field : ArticleField.values()) {
            Row row = new Row();
            row.field = field;
            for (ArticleFieldRight right : rights) {
                if (right.fieldCode.equals(field.name())) {
                    row.profileIds.add(right.profileId);
                    row.restricted = true;
                }
            }
            rows.add(row);
        }
        LOGGER.info("Exiting method page");
        return adminArticleFieldRights.data("rows", rows)
                .data("profiles", Profile.listAllOrdered())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Replaces the grid with the posted ticks.
     *
     * @param form the posted form, one {@code right} value per tick
     * @return a 303 redirect carrying the notice
     */
    @POST
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_FIELD_RIGHT, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save with form: " + form);
        ArticleFieldRight.deleteAll();
        int count = 0;
        List<String> ticks = form.get("right");
        for (String tick : ticks == null ? List.<String>of() : ticks) {
            ArticleFieldRight right = parse(tick);
            if (right != null) {
                right.persist();
                count++;
            }
        }
        LOGGER.info("Exiting method save");
        return AdminForms.redirect(PAGE, count + " droit(s) de la fiche article enregistré(s).", true);
    }

    /**
     * Reads one posted tick, {@code FIELD:profileId}.
     *
     * @param tick the posted value, possibly null or malformed
     * @return the right, or null when the tick names no datum or no profile
     */
    static ArticleFieldRight parse(String tick) {
        int colon = tick == null ? -1 : tick.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        Long profileId = AdminForms.id(tick.substring(colon + 1));
        String code = tick.substring(0, colon);
        boolean known = false;
        for (ArticleField field : ArticleField.values()) {
            known |= field.name().equals(code);
        }
        if (profileId == null || !known) {
            return null;
        }
        ArticleFieldRight right = new ArticleFieldRight();
        right.profileId = profileId;
        right.fieldCode = code;
        return right;
    }
}
