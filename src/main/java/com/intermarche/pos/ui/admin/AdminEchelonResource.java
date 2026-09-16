package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.store.Country;
import com.intermarche.pos.domain.setting.EchelonLevel;
import com.intermarche.pos.domain.store.Enseigne;
import com.intermarche.pos.domain.store.Pdv;
import com.intermarche.pos.service.EchelonSettingService;
import com.intermarche.pos.service.PosSettingsService;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * one-shot notice, {@code @RolesAllowed("ADMIN")} throughout.
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

    /**
     * Shows the echelons page: the tree, the parameter catalog and, when a key
     * and an enseigne are selected, the personalisation view.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @param viewEnseigne the enseigne code of the personalisation view, or null
     * @param viewKey the catalog key of the personalisation view, or null
     * @return the page
     */
    @GET
    @RolesAllowed("ADMIN")
    public TemplateInstance echelonsPage(@QueryParam("notice") String notice,
                                         @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk,
                                         @QueryParam("viewEnseigne") String viewEnseigne,
                                         @QueryParam("viewKey") String viewKey) {
        LOGGER.info("Entering method echelonsPage with notice: " + notice + ", noticeOk: " + noticeOk + ", viewEnseigne: " + viewEnseigne + ", viewKey: " + viewKey);
        List<Pdv> personalised;
        if (viewEnseigne != null && !viewEnseigne.isBlank() && viewKey != null && !viewKey.isBlank()) {
            personalised = echelonSettings.personalizedPdvs(viewEnseigne, viewKey);
        } else {
            personalised = List.of();
        }
        LOGGER.info("Exiting method echelonsPage");
        return adminEchelons.data("countries", Country.listAllOrdered())
                .data("enseignes", Enseigne.listAllOrdered())
                .data("pdvs", Pdv.listAllOrdered())
                .data("keys", PosSettingsService.CATALOG)
                .data("viewEnseigne", viewEnseigne)
                .data("viewKey", viewKey)
                .data("personalised", personalised)
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
    @RolesAllowed("ADMIN")
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
        if (country == null) {
            country = new Country();
            country.code = code;
            country.persist();
        }
        country.name = trimmed(form, "name");
        country.defaultLanguage = blankToNull(trimmed(form, "defaultLanguage"));
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
    @RolesAllowed("ADMIN")
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
        if (enseigne == null) {
            enseigne = new Enseigne();
            enseigne.code = code;
            enseigne.persist();
        }
        enseigne.name = trimmed(form, "name");
        enseigne.countryCode = blankToNull(trimmed(form, "countryCode"));
        enseigne.defaultLanguage = blankToNull(trimmed(form, "defaultLanguage"));
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
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response savePdv(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method savePdv with form: " + form);
        String number = trimmed(form, "pdvNumber");
        if (!Pdv.isValidNumber(number)) {
            LOGGER.info("Exiting method savePdv");
            return redirect("Le numéro de PDV doit comporter exactement 5 chiffres.", false);
        }
        String original = trimmed(form, "originalNumber");
        Pdv pdv;
        if (!original.isEmpty() && !original.equals(number)) {
            pdv = Pdv.findByNumber(original);
            if (pdv == null) {
                LOGGER.info("Exiting method savePdv");
                return redirect("PDV à renommer introuvable.", false);
            }
            if (Pdv.findByNumber(number) != null) {
                LOGGER.info("Exiting method savePdv");
                return redirect("Ce numéro de PDV est déjà utilisé.", false);
            }
            pdv.pdvNumber = number;
            // BO-02-05-01: carry the echelon settings posed at this PDV over to
            // its new number, or the rename would orphan them.
            echelonSettings.migratePdv(original, number);
        } else {
            pdv = Pdv.findByNumber(number);
            if (pdv == null) {
                pdv = new Pdv();
                pdv.pdvNumber = number;
                pdv.persist();
            }
        }
        pdv.name = trimmed(form, "name");
        pdv.enseigneCode = blankToNull(trimmed(form, "enseigneCode"));
        pdv.adherentCode = blankToNull(trimmed(form, "adherentCode"));
        pdv.active = form.getFirst("active") != null;
        LOGGER.info("Exiting method savePdv");
        return redirect("Point de vente enregistré.", true);
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
    @RolesAllowed("ADMIN")
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
        echelonSettings.set(level, echelonCode, key, trimmed(form, "value"), effectiveDate);
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
    @RolesAllowed("ADMIN")
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
}
