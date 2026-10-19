package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.store.PostalCode;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
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
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import com.intermarche.edge.ui.journal.JournalPage;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The POSTAL CODES back-office screen ({@code /admin/postal-codes},
 * {@code BO-02-04-16}): creates, corrects and removes the code-and-place pairs
 * the registers use to fill an address.
 *
 * <p>The ONE referential of this project that is created by hand. Everything
 * else — articles, customers, prices — comes from an integration and is only
 * corrected here, because a shop inventing an article is a shop whose catalogue
 * no longer matches the group's. A postal code is the opposite case: the
 * requirement asks for the list to be maintainable by the shop itself, and a
 * missing hamlet is a cashier retyping a town name by hand every time.
 *
 * <p>Removing one is a REAL removal and not a deactivation, unlike the
 * articles: nothing references a postal code — a customer's address carries the
 * text of the code, not a foreign key to this table — so a row deleted here
 * takes nothing with it.
 *
 * <p>Access reserved to ADMIN, like every referential screen.
 * POST&nbsp;→&nbsp;303&nbsp;→&nbsp;notice.
 */
@Path("/admin/postal-codes")
public class AdminPostalCodeResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminPostalCodeResource.class);

    /**
     * The number of rows one page of the list shows.
     *
     * <p>A hundred, like every other paged list of this back office, so a page
     * turn means the same thing wherever the operator is.
     */
    static final int PAGE_SIZE = 100;

    /** The postal-code list template. */
    @Inject
    @Location("admin-postal-codes")
    Template adminPostalCodes;

    /**
     * Shows the postal codes, filtered by a fragment of code or place.
     *
     * @param text a fragment of the code or the place
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the list page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Transactional
    public TemplateInstance list(@QueryParam("text") String text,
                                 @QueryParam("page") @DefaultValue("1") int page,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list with text: " + text + ", page: " + page);
        PanacheQuery<PostalCode> query = search(text);
        long total = query.count();
        int number = clampPage(page, total);
        JournalPage<PostalCode> window = new JournalPage<>(
                query.page(number - 1, PAGE_SIZE).list(), number, PAGE_SIZE, total);
        LOGGER.info("Exiting method list with " + total + " row(s)");
        return adminPostalCodes.data("page", window)
                .data("baseQuery", baseQuery(text))
                .data("text", text == null ? "" : text)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Clamps a requested page number into the pages the result actually has, so
     * a stale link or a hand-typed number lands on the last page rather than on
     * an empty list.
     *
     * @param requested the 1-based page number asked for
     * @param total the number of matching rows
     * @return the page number to read, between 1 and the page count
     */
    private int clampPage(int requested, long total) {
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, JournalPage.pageCount(total, PAGE_SIZE));
    }

    /**
     * Renders what a page link has to carry over: the search fragment, so that
     * turning a page keeps the list the operator was reading.
     *
     * @param text the search fragment, possibly null or blank
     * @return the query string, terminated by an ampersand when non-empty
     */
    private String baseQuery(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return "text=" + URLEncoder.encode(text, StandardCharsets.UTF_8) + "&";
    }

    /**
     * Creates or corrects one pair ({@code BO-02-04-16}).
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save");
        String code = blankToNull(form.getFirst("code"));
        String place = blankToNull(form.getFirst("place"));
        if (code == null || place == null) {
            LOGGER.info("Exiting method save: incomplete pair");
            return redirect("Code postal et localité obligatoires.", false);
        }
        Long id = longValue(form.getFirst("id"));
        PostalCode row = id != null ? PostalCode.<PostalCode>findById(id) : null;
        PostalCode clash = PostalCode.findPair(code, place);
        if (clash != null && (row == null || !clash.id.equals(row.id))) {
            LOGGER.info("Exiting method save: the pair already exists");
            return redirect("Ce couple code / localité existe déjà.", false);
        }
        if (row == null) {
            row = new PostalCode();
        }
        row.code = code;
        row.place = place;
        row.country = blankToNull(form.getFirst("country"));
        row.persist();
        LOGGER.infof("Code postal %s / %s enregistré", code, place);
        LOGGER.info("Exiting method save");
        return redirect("Code postal enregistré.", true);
    }

    /**
     * Removes one pair ({@code BO-02-04-16}).
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete");
        Long id = longValue(form.getFirst("id"));
        PostalCode row = id != null ? PostalCode.<PostalCode>findById(id) : null;
        if (row == null) {
            LOGGER.info("Exiting method delete: unknown row");
            return redirect("Code postal inconnu.", false);
        }
        row.delete();
        LOGGER.infof("Code postal %s / %s supprimé", row.code, row.place);
        LOGGER.info("Exiting method delete");
        return redirect("Code postal supprimé.", true);
    }

    /**
     * Builds the ordered query of the postal codes matching a fragment.
     *
     * <p>The fragment is matched by the DATABASE and no longer by walking the
     * table: the French postal file is tens of thousands of rows, and a screen
     * that hydrates all of them to show twenty is a screen that gets slower
     * every time the file grows.
     *
     * @param text a fragment of the code or the place, possibly null or blank
     * @return the query, ordered by code then place
     */
    PanacheQuery<PostalCode> search(String text) {
        String needle = text == null || text.isBlank() ? null : text.trim().toLowerCase();
        if (needle == null) {
            return PostalCode.find("order by code, place");
        }
        return PostalCode.find(
                "(lower(code) like ?1 or lower(place) like ?1) order by code, place",
                "%" + needle + "%");
    }

    /**
     * Reads a posted text, a blank one counting as absent.
     *
     * @param value the posted value, possibly null or blank
     * @return the trimmed value, or null
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Reads a posted identifier.
     *
     * @param value the posted value, possibly null or unreadable
     * @return the identifier, or null
     */
    private Long longValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Builds the 303 that carries the outcome message.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response redirect(String notice, boolean ok) {
        return Response.seeOther(URI.create("/admin/postal-codes?notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8) + "&noticeOk=" + ok)).build();
    }
}
