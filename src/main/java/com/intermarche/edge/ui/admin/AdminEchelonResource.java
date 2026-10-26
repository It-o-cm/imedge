package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.setting.EchelonLevel;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.PdvHistory;
import com.intermarche.edge.service.EchelonSettingService;
import com.intermarche.edge.service.PdvRegistryService;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
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
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The central back office's ECHELONS page ({@code /admin/echelons}): the
 * organisation tree (Pays &rarr; Enseigne &rarr; Point De Vente) and the
 * parameters posed at each echelon (BO-02-05-01/02/04/05, BO-03-12-07).
 * <p>
 * It edits the CENTRAL registry — {@link Country}, {@link Enseigne},
 * {@link Pdv} and the echelon parameter values — which the central node serves
 * down the referential pull, unlike the node-local {@code /admin/store} row.
 * A PDV's five-digit number is validated and unique here (BO-02-05-01); its
 * enseigne attachment can be changed to re-parent it (BO-02-05-02); its
 * adhérent groups several PDVs (BO-02-05-05). The personalisation view lists
 * the PDVs of an enseigne that hold their own value for a key, away from the
 * enseigne default (BO-03-12-07).
 * <p>
 * Same admin cycle as the other back-office pages: POST &rarr; 303 &rarr; a
 * one-shot notice, {@code @FeatureAccess} throughout (BO-01-04-01).
 */
@Path("/admin/echelons")
public class AdminEchelonResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminEchelonResource.class);

    /** The echelons page template. */
    @Inject
    @Location("admin-echelons")
    Template adminEchelons;

    /** The echelon inheritance engine backing the settings editor and the view. */
    @Inject
    EchelonSettingService echelonSettings;

    /** The registry every point-of-sale write goes through (BO-02-05-01/02/05). */
    @Inject
    PdvRegistryService pdvRegistry;

    /** What a point of sale receives down the referential pull (BO-02-05-04). */
    @Inject
    com.intermarche.edge.service.sync.store.RefExportService refExportService;

    /**
     * Shows the echelons page: the tree, the parameter catalog, the values
     * programmed for a later day (current and pending, BO-03-12-03) and, when a
     * key and an enseigne are selected, the personalisation view.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @param viewEnseigne the enseigne code of the personalisation view, or null
     * @param viewKey the catalog key of the personalisation view, or null
     * @param viewPdv the point of sale of the inheritance view, or null
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.ECHELON)
    @Transactional
    public TemplateInstance echelonsPage(@QueryParam("notice") String notice,
                                         @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk,
                                         @QueryParam("viewEnseigne") String viewEnseigne,
                                         @QueryParam("viewKey") String viewKey,
                                         @QueryParam("viewPdv") String viewPdv) {
        LOGGER.info("Entering method echelonsPage with notice: " + notice + ", noticeOk: " + noticeOk + ", viewEnseigne: " + viewEnseigne + ", viewKey: " + viewKey);
        List<Pdv> personalised;
        if (viewEnseigne != null && !viewEnseigne.isBlank() && viewKey != null && !viewKey.isBlank()) {
            personalised = echelonSettings.personalizedPdvs(viewEnseigne, viewKey);
        } else {
            personalised = List.of();
        }
        List<Pdv> pdvs = Pdv.listAllOrdered();
        Pdv viewed = viewPdv == null || viewPdv.isBlank() ? null : Pdv.findByNumber(viewPdv.trim());
        LOGGER.info("Exiting method echelonsPage");
        return adminEchelons.data("countries", Country.listAllOrdered())
                .data("enseignes", Enseigne.listAllOrdered())
                .data("pdvs", pdvs)
                .data("adherents", adherents(pdvs))
                .data("viewPdv", viewPdv == null ? "" : viewPdv.trim())
                .data("viewed", viewed)
                .data("history", viewed == null ? List.of() : PdvHistory.listForPdv(viewed.id))
                .data("inherited", viewed == null ? List.of() : echelonSettings.inheritedFor(viewed.pdvNumber))
                .data("inheritedVat", viewed == null ? List.of() : refExportService.vatRatesFor(viewed.pdvNumber))
                .data("inheritedTemplates", viewed == null ? List.of()
                        : refExportService.templatesFor(viewed.pdvNumber))
                .data("inheritedAccounts", viewed == null ? List.of()
                        : inForce(refExportService.bindingsFor(viewed.pdvNumber),
                                com.intermarche.edge.domain.store.NodeEchelon.of(viewed.pdvNumber)))
                .data("keys", PosSettingsService.CATALOG)
                .data("closedValues", closedValuesJson())
                .data("secretKeys", secretKeysJson())
                .data("viewEnseigne", viewEnseigne)
                .data("viewKey", viewKey)
                .data("personalised", personalised)
                .data("programmed", echelonSettings.programmed())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Upserts a country by its code.
     *
     * @param form the posted form (code, name, defaultLanguage)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/country")
    @Authenticated
    @FeatureAccess(value = Feature.ECHELON, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveCountry(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveCountry with form: " + form);
        String code = trimmed(form, "code");
        if (code.isEmpty()) {
            LOGGER.info("Exiting method saveCountry");
            return redirect("Le code pays est obligatoire.", false);
        }
        Country country = Country.findByCode(code);
        boolean created = country == null;
        if (created) {
            country = new Country();
            country.code = code;
        }
        country.name = trimmed(form, "name");
        country.defaultLanguage = blankToNull(trimmed(form, "defaultLanguage"));
        if (created) {
            // Persisted once filled: the identity insert runs at persist, and
            // the name column refuses null (a new country used to answer 500).
            country.persist();
        }
        LOGGER.info("Exiting method saveCountry");
        return redirect("Pays enregistré.", true);
    }

    /**
     * Upserts an enseigne by its code and attaches it to a country.
     *
     * @param form the posted form (code, name, countryCode, defaultLanguage)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/enseigne")
    @Authenticated
    @FeatureAccess(value = Feature.ECHELON, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveEnseigne(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveEnseigne with form: " + form);
        String code = trimmed(form, "code");
        if (code.isEmpty()) {
            LOGGER.info("Exiting method saveEnseigne");
            return redirect("Le code enseigne est obligatoire.", false);
        }
        Enseigne enseigne = Enseigne.findByCode(code);
        boolean created = enseigne == null;
        if (created) {
            enseigne = new Enseigne();
            enseigne.code = code;
        }
        enseigne.name = trimmed(form, "name");
        enseigne.countryCode = blankToNull(trimmed(form, "countryCode"));
        enseigne.defaultLanguage = blankToNull(trimmed(form, "defaultLanguage"));
        if (created) {
            // Persisted once filled: the name column refuses null at the insert.
            enseigne.persist();
        }
        LOGGER.info("Exiting method saveEnseigne");
        return redirect("Enseigne enregistrée.", true);
    }

    /**
     * Upserts a PDV, validating its five-digit number and its global
     * uniqueness, and re-parenting it under a new number when renamed
     * (BO-02-05-01/02/05).
     *
     * @param form the posted form (pdvNumber, originalNumber, name,
     *        enseigneCode, adherentCode, active)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/pdv")
    @Authenticated
    @FeatureAccess(value = Feature.ECHELON, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response savePdv(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method savePdv with form: " + form);
        PdvRegistryService.Result result = pdvRegistry.save(trimmed(form, "pdvNumber"),
                trimmed(form, "originalNumber"), trimmed(form, "name"),
                blankToNull(trimmed(form, "enseigneCode")), blankToNull(trimmed(form, "adherentCode")),
                form.getFirst("active") != null, PdvHistory.Source.SCREEN);
        LOGGER.info("Exiting method savePdv");
        return redirect(result.message(), result.ok());
    }

    /**
     * Poses a value for a key at an echelon, optionally dated to take effect on
     * a future day (BO-03-12-03/04): a value with an effect date is distributed
     * at once but applies on its own the day it names.
     *
     * @param form the posted form (level, echelonCode, key, value, effectiveDate)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/setting")
    @Authenticated
    @FeatureAccess(value = Feature.ECHELON, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response saveSetting(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveSetting with form: " + form);
        EchelonLevel level = parseLevel(trimmed(form, "level"));
        String echelonCode = trimmed(form, "echelonCode");
        String key = trimmed(form, "key");
        if (level == null || echelonCode.isEmpty() || key.isEmpty()) {
            LOGGER.info("Exiting method saveSetting");
            return redirect("Niveau, échelon et paramètre sont obligatoires.", false);
        }
        String value = trimmed(form, "value");
        // A CLOSED SET IS CLOSED AT EVERY ECHELON. The page offers the values of
        // the chosen key, but the refusal lives here: a stale page, a key changed
        // after the value was picked or a hand-made request would otherwise pose
        // a word the register cannot read — and a word it cannot read falls back
        // on the default, which is a parameter the shop believes it has set.
        List<String> allowed = PosSettingsService.closedValues(key);
        if (!allowed.isEmpty() && !allowed.contains(value)) {
            LOGGER.info("Exiting method saveSetting");
            return redirect("Valeur invalide pour « " + key + " » — attendu : "
                    + String.join(", ", allowed) + ".", false);
        }
        String rawDate = trimmed(form, "effectiveDate");
        java.time.LocalDate effectiveDate;
        if (rawDate.isEmpty()) {
            effectiveDate = null;
        } else {
            try {
                effectiveDate = java.time.LocalDate.parse(rawDate);
            } catch (java.time.format.DateTimeParseException e) {
                LOGGER.info("Exiting method saveSetting");
                return redirect("Date d'effet invalide (attendu AAAA-MM-JJ).", false);
            }
        }
        echelonSettings.set(level, echelonCode, key, value, effectiveDate);
        LOGGER.info("Exiting method saveSetting");
        return redirect("Paramètre d'échelon enregistré.", true);
    }

    /**
     * Clears a value posed at an echelon, so it reverts to inheriting the
     * level above.
     *
     * @param form the posted form (level, echelonCode, key)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/setting/clear")
    @Authenticated
    @FeatureAccess(value = Feature.ECHELON, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response clearSetting(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method clearSetting with form: " + form);
        EchelonLevel level = parseLevel(trimmed(form, "level"));
        String echelonCode = trimmed(form, "echelonCode");
        String key = trimmed(form, "key");
        if (level == null || echelonCode.isEmpty() || key.isEmpty()) {
            LOGGER.info("Exiting method clearSetting");
            return redirect("Niveau, échelon et paramètre sont obligatoires.", false);
        }
        boolean removed = echelonSettings.clear(level, echelonCode, key);
        if (removed) {
            LOGGER.info("Exiting method clearSetting");
            return redirect("Paramètre d'échelon supprimé.", true);
        }
        LOGGER.info("Exiting method clearSetting");
        return redirect("Aucun paramètre à supprimer à cet échelon.", false);
    }

    /**
     * Groups the points of sale by the adhérent operating them (BO-02-05-05):
     * the regrouping view of the organisation by adhérent, the points of sale
     * attached to none left out.
     *
     * @param pdvs every point of sale, by number
     * @return one entry per adhérent, by code, each listing its points of sale
     */
    static Map<String, List<Pdv>> adherents(List<Pdv> pdvs) {
        Map<String, List<Pdv>> grouped = new java.util.TreeMap<>();
        for (Pdv pdv : pdvs) {
            if (pdv.adherentCode != null) {
                grouped.computeIfAbsent(pdv.adherentCode, code -> new java.util.ArrayList<>()).add(pdv);
            }
        }
        return grouped;
    }

    /**
     * Serialises the closed value sets as the JSON the page's script reads to
     * turn the value field into a list when the chosen key is a closed set.
     *
     * <p>Built here rather than assembled in the template, where a comma
     * between two loops is one edit away from a broken page. No escaping: the
     * keys and the values are CONSTANTS of the catalogue — letters, digits,
     * dots, dashes and the empty string — written by the JSON library all the
     * same, so no value can break the page's script.
     *
     * @return the JSON object, key to array of allowed values
     */
    private String closedValuesJson() {
        com.fasterxml.jackson.databind.node.ObjectNode json =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, List<String>> closed : PosSettingsService.CLOSED_VALUES.entrySet()) {
            closed.getValue().forEach(json.putArray(closed.getKey())::add);
        }
        return json.toString();
    }

    /**
     * Serialises the SECRET keys of the catalogue (BO-10-03-08) as the JSON
     * array the page's script reads to mask the value field while one of them
     * is chosen. Constants of the catalogue, written by the JSON library.
     *
     * @return the JSON array of secret keys, sorted
     */
    private String secretKeysJson() {
        com.fasterxml.jackson.databind.node.ArrayNode json =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        PosSettingsService.SECRET_KEYS.stream().sorted().forEach(json::add);
        return json.toString();
    }

    /**
     * Parses an echelon level name, tolerating an unknown or blank value.
     *
     * @param raw the posted level name
     * @return the level, or null when unrecognised
     */
    private EchelonLevel parseLevel(String raw) {
        for (EchelonLevel level : EchelonLevel.values()) {
            if (level.name().equals(raw)) {
                return level;
            }
        }
        return null;
    }

    /**
     * Reads a posted field, trimmed, never null.
     *
     * @param form the posted form
     * @param key the field name
     * @return the trimmed value, or the empty string when absent
     */
    private String trimmed(MultivaluedMap<String, String> form, String key) {
        String raw = form.getFirst(key);
        return raw == null ? "" : raw.trim();
    }

    /**
     * Maps an empty string to null, leaving any other value untouched.
     *
     * @param value the value to normalise
     * @return null when the value is empty, the value otherwise
     */
    private String blankToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    /**
     * Builds the 303 redirect carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/echelons?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }

    /**
     * The profile bindings a point of sale really receives (BO-02-05-02,
     * BO-02-05-04): an account travels with all its bindings, each carrying
     * its scope, and only those whose scope covers the point of sale — the
     * whole organisation, its country, its enseigne or itself — are in force
     * there; a binding scoped to another enseigne is not inherited.
     *
     * @param bindings the bindings of the accounts the point of sale receives
     * @param node the point of sale's place in the organisation
     * @return the bindings in force on that point of sale, in their order
     */
    static List<com.intermarche.edge.service.sync.RefPayloads.AccountProfileDto> inForce(
            List<com.intermarche.edge.service.sync.RefPayloads.AccountProfileDto> bindings,
            com.intermarche.edge.domain.store.NodeEchelon node) {
        return bindings.stream()
                .filter(binding -> com.intermarche.edge.domain.people.EchelonScope.admitsNode(
                        java.util.Arrays.asList(binding.echelonScope), node))
                .toList();
    }
}
