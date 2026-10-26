package com.intermarche.edge.ui.admin;

import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.qute.Location;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
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
import org.jboss.logging.Logger;

/**
 * The store back office's PARAMETERS page ({@code /admin/settings}) —
 * imvaluation admin gabarit (light chrome, cards, POST → 303 → notice),
 * deliberately NOT the till's touch theme.
 * <p>
 * Values are stored node-locally ({@code pos_settings}) and reach the
 * registers through the referential pull (domain SETTINGS): administered on
 * the STORE node they apply store-wide at the next pull; edited on a
 * standalone register they apply locally — same screen, same table, the
 * topology decides the reach.
 * <p>
 * Access is reserved to ADMIN, deliberately one notch above the supervision
 * page (ADMIN or MANAGER): these parameters decide, among other things,
 * whether a gesture requires a manager's endorsement — letting a manager
 * lift the requirement that a manager approves would be an escalation. The
 * page sits outside the cashier lock (the lock filter allowlists
 * {@code /admin}) because it is a back-office surface, not a till screen.
 * <p>
 * Also serves the back office's ENTRY POINT ({@code /admin}), which
 * redirects here: the surface has one address to type and one address for
 * the header brand to point at, the mirror of the imvaluation admin root.
 */
@Path("/")
public class AdminSettingsResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminSettingsResource.class);

    /** The parameters page template. */
    @Inject
    @Location("admin-settings")
    Template adminSettings;

    /** The catalog and store of the parameters. */
    @Inject
    PosSettingsService posSettingsService;

    /** The identity of the signed-in operator, used to route the root. */
    @Inject
    SecurityIdentity identity;

    /** The signed-in operator's navigation, whose first screen an operator lands on. */
    @Inject
    BackOfficeMenu backOfficeMenu;

    /**
     * One render-ready catalog entry: the definition flattened for Qute plus
     * the current effective value and the section-break marker.
     */
    public static class Entry {
        /** The storage key. */
        public String key;
        /** The section heading. */
        public String section;
        /** The operator-facing label. */
        public String label;
        /** The operator-facing explanation. */
        public String hint;
        /** The current effective value, as text. */
        public String value;
        /** True for a boolean parameter (select widget). */
        public boolean bool;
        /** True for an integer parameter (number widget). */
        public boolean integer;
        /** The values to pick from, empty for a free text field. */
        public List<String> choices;
        /**
         * True for a SECRET (BO-10-03-08): the field is a masked one, offered
         * empty — the stored value never travels back to the page.
         */
        public boolean secret;
        /** True when a secret holds a stored value, which the empty field keeps. */
        public boolean secretSet;
    }

    /**
     * One SECTION of the catalog: a heading and the parameters under it.
     * <p>
     * The page carries seventy-five parameters over twenty-four headings, and
     * rendering them all open made a screen nobody could read. Grouping them
     * here rather than in the template also retires the {@code firstOfSection}
     * marker and the hand-closed {@code </div></div>} that went with it: the
     * template had to open a card on one iteration and close it on another,
     * which is the kind of arrangement that survives exactly until someone
     * adds a parameter.
     */
    public static class Section {
        /** The section heading. */
        public String label;
        /** The parameters under it, in catalog order. */
        public List<Entry> entries = new ArrayList<>();

        /**
         * Builds a section.
         *
         * @param label the section heading
         */
        public Section(String label) {
            this.label = label;
        }

        /**
         * Returns the section heading.
         *
         * @return the heading
         */
        public String getLabel() {
            return label;
        }

        /**
         * Returns the parameters under this section.
         *
         * @return the entries, never null
         */
        public List<Entry> getEntries() {
            return entries;
        }

        /**
         * How many parameters this section holds — shown on the closed panel,
         * so the operator knows what is behind it without opening it.
         *
         * @return the parameter count
         */
        public int getCount() {
            return entries.size();
        }
    }

    /**
     * Routes the back-office root to the entry point the signed-in operator
     * may actually reach.
     * <p>
     * An administrator lands on the parameters; an operator whose profiles
     * open the supervision — a manager among them — lands on the supervision;
     * any other operator on the first screen of their navigation (their
     * favorites first, BO-01-04-08), and an operator whom no screen is opened
     * to on their favorites, which say so. Without this the single landing
     * page of form authentication would send operators onto a refusal, and
     * the header brand would do the same. Kept in this resource rather than
     * in a class of its own: a redirect does not warrant one.
     *
     * @return a 303 redirect to the operator's entry point
     */
    @GET
    @Path("/admin")
    @Authenticated
    public Response adminRoot() {
        LOGGER.info("Entering method adminRoot");
        boolean admin = identity != null && identity.hasRole(Employee.EmployeeRole.ADMIN.name());
        String target = admin ? "/admin/settings" : "/dashboard";
        if (!admin && backOfficeMenu != null) {
            List<BackOfficeMenu.Group> groups = backOfficeMenu.getGroups();
            boolean supervision = groups.stream().anyMatch(group -> group.getEntries().stream()
                    .anyMatch(entry -> "/dashboard".equals(entry.getPath())));
            if (groups.isEmpty()) {
                target = "/admin/favorites";
            } else if (!supervision) {
                target = groups.get(0).getEntries().get(0).getPath();
            }
        }
        LOGGER.info("Exiting method adminRoot");
        return Response.seeOther(URI.create(target)).build();
    }

    /**
     * Shows the parameters page.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the parameters page
     */
    @GET
    @Path("/admin/settings")
    @Authenticated
    @FeatureAccess(value = Feature.SETTINGS)
    public TemplateInstance settingsPage(@QueryParam("notice") String notice,
                                         @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method settingsPage with notice: " + notice + ", noticeOk: " + noticeOk);
        List<Section> sections = new ArrayList<>();
        Section current = null;
        int total = 0;
        for (PosSettingsService.Def def : PosSettingsService.CATALOG) {
            Entry entry = new Entry();
            entry.key = def.key();
            entry.section = def.section();
            entry.label = def.label();
            entry.hint = def.hint();
            entry.value = posSettingsService.value(def.key());
            entry.bool = def.type() == PosSettingsService.Type.BOOL;
            entry.integer = def.type() == PosSettingsService.Type.INT;
            // A CLOSED SET IS OFFERED, NOT TYPED (PosSettingsService.CLOSED_VALUES).
            // The value already stored joins the list when the list does not
            // carry it, so a synonym or an older value — FLOATING, which the
            // register still reads as FLOATING_WEEK_MONTH — is shown as it is
            // instead of being quietly replaced by the first option the next
            // time someone saves the page.
            List<String> choices = PosSettingsService.closedValues(def.key());
            if (!choices.isEmpty() && entry.value != null && !entry.value.isBlank()
                    && !choices.contains(entry.value)) {
                List<String> widened = new ArrayList<>(choices);
                widened.add(entry.value);
                choices = widened;
            }
            entry.choices = choices;
            // BO-10-03-08: a secret is never sent back to the page; the field
            // only says whether one is stored.
            entry.secret = PosSettingsService.isSecret(def.key());
            if (entry.secret) {
                entry.secretSet = entry.value != null && !entry.value.isEmpty();
                entry.value = "";
            }
            if (current == null || !current.label.equals(def.section())) {
                current = new Section(def.section());
                sections.add(current);
            }
            current.entries.add(entry);
            total++;
        }
        LOGGER.info("Exiting method settingsPage");
        return adminSettings.data("sections", sections)
                .data("settingCount", total)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Saves every posted parameter (upsert by key), validating integers, and
     * redirects back with the outcome notice — the admin cycle.
     *
     * @param form the posted form, one field per catalog key
     * @return a 303 redirect to the page with the notice
     */
    @POST
    @Path("/admin/settings")
    @Authenticated
    @FeatureAccess(value = Feature.SETTINGS, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save with form: " + form);
        int saved = 0;
        for (PosSettingsService.Def def : PosSettingsService.CATALOG) {
            String raw = form.getFirst(def.key());
            if (raw == null) continue;
            String value = raw.trim();
            if (def.type() == PosSettingsService.Type.INT) {
                try {
                    int parsed = Integer.parseInt(value);
                    if (parsed < 0) throw new NumberFormatException();
                    value = String.valueOf(parsed);
                } catch (NumberFormatException e) {
                    LOGGER.info("Exiting method save");
                    return redirect("Valeur invalide pour « " + def.label() + " » — entier positif attendu.", false);
                }
            } else if (def.type() == PosSettingsService.Type.BOOL) {
                value = String.valueOf(Boolean.parseBoolean(value));
            } else {
                // A word outside the closed set is refused rather than stored:
                // the register would read it, fail to recognise it and fall back
                // on the default, which is a control the shop believes it has
                // set. The value ALREADY stored is let through unchanged, so a
                // configuration carrying a synonym stays saveable.
                List<String> choices = PosSettingsService.closedValues(def.key());
                if (!choices.isEmpty() && !choices.contains(value)
                        && !value.equals(posSettingsService.value(def.key()))) {
                    LOGGER.info("Exiting method save");
                    return redirect("Valeur invalide pour « " + def.label()
                            + " » — attendu : " + String.join(", ", choices) + ".", false);
                }
            }
            // BO-10-03-08: a secret field left empty keeps the stored secret —
            // the page never shows it, so empty means "unchanged".
            if (PosSettingsService.isSecret(def.key()) && value.isEmpty()) {
                continue;
            }
            posSettingsService.store(def.key(), value);
            saved++;
        }
        LOGGER.info("Exiting method save");
        return redirect(saved + " paramètre(s) enregistré(s). Application aux caisses au prochain tirage.", true);
    }

    /**
     * Builds the 303 redirect carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/settings?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }
}
