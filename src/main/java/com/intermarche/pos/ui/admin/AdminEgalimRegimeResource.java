package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.catalog.EgalimRegime;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import org.jboss.logging.Logger;

/**
 * The back office's EGAlim page ({@code /admin/egalim-regimes}): the legal
 * categories that cap the promotional generosity of an article.
 *
 * <p>Until this screen the regimes lived in compiled code, with their ceilings
 * written into their own names. The law has already moved one of them — the
 * DPH ceiling went from 34 % to 40 % — and a legal rate that moves by
 * redeployment is a rate nobody can correct the morning it changes. Here a
 * ceiling is a field, a new category is a row, and the valuation engine reads
 * what the shop states.
 *
 * <p>The screen speaks PERCENT and the model speaks FRACTION. A human writes
 * {@code 34} and the row stores {@code 0.3400}, which is the form every
 * computation of the suite consumes. An EMPTY ceiling is not zero: it is the
 * absence of a ceiling, and it is how the exempt category is stated. Zero
 * would mean "no generosity allowed at all", the exact opposite.
 *
 * <p>The CODE is the identity: it is what the article feed states and what the
 * engine matches on. It can be corrected, but never onto a code another regime
 * already carries, and correcting it detaches nothing here while breaking the
 * correspondence with the files that name the old one — so the screen says so.
 *
 * <p>Same admin cycle as the other back-office pages: POST &rarr; 303 &rarr; a
 * one-shot notice, {@code @RolesAllowed("ADMIN")} throughout.
 */
@Path("/admin/egalim-regimes")
public class AdminEgalimRegimeResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminEgalimRegimeResource.class);

    /** Where the screen sends the operator back after a write. */
    private static final String PAGE = "/admin/egalim-regimes";

    /** The highest ceiling the screen accepts, as a percentage. */
    private static final BigDecimal MAX_PERCENT = new BigDecimal("100");

    /** The longest code the column holds. */
    private static final int CODE_MAX_LENGTH = 30;

    /** The EGAlim page template. */
    @Inject
    @Location("admin-egalim-regimes")
    Template adminEgalimRegimes;

    /**
     * Shows the EGAlim page: every regime in code order, plus the creation
     * form.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Transactional
    public TemplateInstance egalimRegimesPage(@QueryParam("notice") String notice,
                                              @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method egalimRegimesPage with notice: " + notice + ", noticeOk: " + noticeOk);
        List<EgalimRegime> rows = EgalimRegime.listAllOrdered();
        LOGGER.info("Exiting method egalimRegimesPage");
        return adminEgalimRegimes.data("rows", rows)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Creates or updates a regime.
     *
     * <p>The row being edited is named by its identifier, not by its code, so
     * a code can be corrected without the write losing track of which row it
     * was correcting. A blank identifier is a creation.
     *
     * @param form the posted form (id, code, label, capPercent)
     * @return a 303 redirect carrying the notice
     */
    @POST
    @Path("/save")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveRegime(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveRegime with form: " + form);
        String code = code(AdminForms.text(form, "code"));
        if (code == null) {
            LOGGER.info("Exiting method saveRegime");
            return AdminForms.redirect(PAGE,
                    "Le code du régime est obligatoire et limité à " + CODE_MAX_LENGTH + " caractères.", false);
        }
        String rawCap = AdminForms.text(form, "capPercent");
        BigDecimal cap = fraction(rawCap);
        if (cap == null && rawCap != null) {
            LOGGER.info("Exiting method saveRegime");
            return AdminForms.redirect(PAGE,
                    "Le plafond doit être un pourcentage entre 0 et 100, ou vide pour un régime non plafonné.",
                    false);
        }
        Long id = AdminForms.id(AdminForms.text(form, "id"));
        EgalimRegime edited = id == null ? null : EgalimRegime.<EgalimRegime>findById(id);
        if (id != null && edited == null) {
            LOGGER.info("Exiting method saveRegime");
            return AdminForms.redirect(PAGE, "Régime EGAlim introuvable.", false);
        }
        EgalimRegime holder = EgalimRegime.findByCode(code);
        if (holder != null && (edited == null || !holder.id.equals(edited.id))) {
            LOGGER.info("Exiting method saveRegime");
            return AdminForms.redirect(PAGE, "Le code " + code + " est déjà porté par un autre régime.", false);
        }
        String label = AdminForms.text(form, "label");
        if (edited == null) {
            EgalimRegime created = new EgalimRegime(code, label, cap);
            created.persist();
            LOGGER.info("Exiting method saveRegime");
            return AdminForms.redirect(PAGE, "Régime EGAlim créé.", true);
        }
        edited.code = code;
        edited.label = label;
        edited.capRate = cap;
        LOGGER.info("Exiting method saveRegime");
        return AdminForms.redirect(PAGE, "Régime EGAlim enregistré.", true);
    }

    /**
     * Removes a regime.
     *
     * <p>Nothing points at a regime inside this node: the classification is
     * consumed by the valuation engine, which reads the code carried by the
     * article feed. Removing a regime the catalog still states therefore does
     * not break a foreign key — it makes the engine treat those articles as
     * unknown, hence UNCAPPED. The screen says it rather than forbidding it:
     * the shop is the one who knows whether the category still exists in law.
     *
     * @param form the posted form (id)
     * @return a 303 redirect carrying the notice
     */
    @POST
    @Path("/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteRegime(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteRegime with form: " + form);
        Long id = AdminForms.id(AdminForms.text(form, "id"));
        EgalimRegime regime = id == null ? null : EgalimRegime.<EgalimRegime>findById(id);
        if (regime == null) {
            LOGGER.info("Exiting method deleteRegime");
            return AdminForms.redirect(PAGE, "Régime EGAlim introuvable.", false);
        }
        String code = regime.code;
        regime.delete();
        LOGGER.info("Exiting method deleteRegime");
        return AdminForms.redirect(PAGE, "Régime " + code
                + " retiré : les articles qui le portent ne sont plus plafonnés.", true);
    }

    /**
     * Reads a posted code: trimmed, upper-cased, bounded by the column.
     *
     * <p>Upper-cased because the code is matched by the engine against what a
     * file states, and a referential that answers differently to {@code food}
     * and {@code FOOD} holds two categories where the law has one.
     *
     * @param raw the posted value, possibly null
     * @return the code, or null when absent, blank or too long
     */
    private String code(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.length() > CODE_MAX_LENGTH) {
            return null;
        }
        return trimmed;
    }

    /**
     * Converts a percentage typed by a human into the fraction the model
     * stores.
     *
     * <p>An absent value is an absent CEILING, not a zero one, and the two
     * legs are distinguished by the caller: this method answers null both for
     * "nothing was typed" and for "what was typed is not a percentage", and
     * the caller tells them apart by looking at the raw value.
     *
     * @param raw the posted percentage, possibly null or blank
     * @return the fraction at scale 4, or null when absent or out of range
     */
    private BigDecimal fraction(String raw) {
        BigDecimal percent = AdminForms.amount(raw);
        if (percent == null) {
            return null;
        }
        if (percent.signum() < 0 || percent.compareTo(MAX_PERCENT) > 0) {
            return null;
        }
        return percent.divide(MAX_PERCENT, 4, RoundingMode.HALF_UP);
    }
}
