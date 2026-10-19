package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.VatRate;
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
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The back office's VAT page ({@code /admin/vat-rates}): the VAT referential,
 * regime by regime (BO-02-03-14).
 *
 * <p>Until this screen the table was fed by file alone, like the prices and the
 * products. A file is the right channel for a hundred thousand rows; it is the
 * wrong one for five, which a shop reads, checks and occasionally corrects —
 * and which every price of the catalog points at. So the regimes are listed
 * with what USES them, and a regime nothing uses can be removed here rather
 * than through a re-integration.
 *
 * <p>The screen speaks PERCENT and the model speaks FRACTION. A human writes
 * {@code 5,5} and the row stores {@code 0.0550}, because that is the form every
 * computation of the suite consumes; the CSV feed keeps stating the fraction,
 * being written by a machine for a machine. The conversion lives here, once,
 * and {@link VatRate#getRateFormatted()} performs the way back.
 *
 * <p>The NUMBER is the identity: it is what a price names in the feed and what
 * the documents print. It can be corrected, but never onto a number another
 * regime already carries — the referential would then hold two answers to the
 * same question. Changing it does NOT detach the prices, which point at the row
 * and not at the number; what it does break is the correspondence with the
 * commercial management's own numbering, so the screen says so.
 *
 * <p>Same admin cycle as the other back-office pages: POST &rarr; 303 &rarr; a
 * one-shot notice, {@code @RolesAllowed("ADMIN")} throughout.
 */
@Path("/admin/vat-rates")
public class AdminVatRateResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminVatRateResource.class);

    /** Where the screen sends the operator back after a write. */
    private static final String PAGE = "/admin/vat-rates";

    /** The highest rate the screen accepts, as a percentage. */
    private static final BigDecimal MAX_PERCENT = new BigDecimal("100");

    /** The VAT page template. */
    @Inject
    @Location("admin-vat-rates")
    Template adminVatRates;

    /**
     * One regime as the screen renders it: the row, and how many prices name
     * it.
     *
     * <p>The count is what turns the delete button from a trap into a
     * statement: a regime three thousand prices point at is not removed by
     * accident, and the operator learns why without having to try.
     */
    public static class RateRow {

        /** The regime itself. */
        public final VatRate rate;

        /** How many price rows point at this regime. */
        public final long prices;

        /**
         * Builds a row.
         *
         * @param rate the regime
         * @param prices how many prices name it
         */
        public RateRow(VatRate rate, long prices) {
            this.rate = rate;
            this.prices = prices;
        }

        /**
         * The regime itself.
         *
         * @return the regime
         */
        public VatRate getRate() {
            return rate;
        }

        /**
         * How many price rows point at this regime.
         *
         * @return the count, zero when nothing uses it
         */
        public long getPrices() {
            return prices;
        }

        /**
         * Whether this regime may be removed.
         *
         * @return true when no price names it
         */
        public boolean isRemovable() {
            return prices == 0;
        }
    }

    /**
     * Shows the VAT page: every regime in number order, each with its usage
     * count, plus the creation form.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Transactional
    public TemplateInstance vatRatesPage(@QueryParam("notice") String notice,
                                         @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method vatRatesPage with notice: " + notice + ", noticeOk: " + noticeOk);
        List<RateRow> rows = rows();
        LOGGER.info("Exiting method vatRatesPage");
        return adminVatRates.data("rows", rows)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Builds the rendered rows: every regime, each carrying how many prices
     * name it.
     *
     * @return the rows in number order, empty when the referential holds none
     */
    private List<RateRow> rows() {
        List<RateRow> rows = new ArrayList<>();
        for (VatRate rate : VatRate.listAllOrdered()) {
            rows.add(new RateRow(rate, Price.count("vat.id = ?1", rate.id)));
        }
        return rows;
    }

    /**
     * Creates or updates a regime.
     *
     * <p>The row being edited is named by its identifier, not by its number, so
     * a number can be corrected without the write losing track of which row it
     * was correcting. A blank identifier is a creation.
     *
     * @param form the posted form (id, number, ratePercent, label)
     * @return a 303 redirect carrying the notice
     */
    @POST
    @Path("/save")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveVatRate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveVatRate with form: " + form);
        Integer number = number(AdminForms.text(form, "number"));
        if (number == null) {
            LOGGER.info("Exiting method saveVatRate");
            return AdminForms.redirect(PAGE, "Le numéro de TVA doit être un entier positif.", false);
        }
        BigDecimal rate = fraction(AdminForms.amount(AdminForms.text(form, "ratePercent")));
        if (rate == null) {
            LOGGER.info("Exiting method saveVatRate");
            return AdminForms.redirect(PAGE, "Le taux doit être un pourcentage entre 0 et 100.", false);
        }
        Long id = AdminForms.id(AdminForms.text(form, "id"));
        VatRate edited = id == null ? null : VatRate.<VatRate>findById(id);
        if (id != null && edited == null) {
            LOGGER.info("Exiting method saveVatRate");
            return AdminForms.redirect(PAGE, "Régime de TVA introuvable.", false);
        }
        VatRate holder = VatRate.findByNumber(number);
        if (holder != null && (edited == null || !holder.id.equals(edited.id))) {
            LOGGER.info("Exiting method saveVatRate");
            return AdminForms.redirect(PAGE, "Le numéro " + number + " est déjà porté par un autre régime.", false);
        }
        String label = AdminForms.text(form, "label");
        // BO-02-02-02: the country the regime answers to and the legal mention
        // it obliges the documents to carry. Both optional: a shop running one
        // country and owing no mention administers neither, and the ticket
        // prints what it printed before.
        String country = country(AdminForms.text(form, "country"));
        String legalMention = AdminForms.text(form, "legalMention");
        if (edited == null) {
            VatRate created = new VatRate(number, rate, label);
            created.country = country;
            created.legalMention = legalMention;
            created.persist();
            LOGGER.info("Exiting method saveVatRate");
            return AdminForms.redirect(PAGE, "Régime de TVA créé.", true);
        }
        edited.number = number;
        edited.rate = rate;
        edited.label = label;
        edited.country = country;
        edited.legalMention = legalMention;
        LOGGER.info("Exiting method saveVatRate");
        return AdminForms.redirect(PAGE, "Régime de TVA enregistré.", true);
    }

    /**
     * Normalises the posted country: trimmed and raised to upper case, cut to
     * the column's three characters; nothing typed answers null, the regime
     * then belonging to every country ({@code BO-02-02-02}).
     *
     * @param raw the posted value, possibly null
     * @return the ISO country, or null
     */
    static String country(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String code = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return code.length() > 3 ? code.substring(0, 3) : code;
    }

    /**
     * Removes a regime, provided no price names it.
     *
     * <p>Refused and not cascaded: detaching the prices would silently drop
     * every one of them onto the administered default rate, and a screen that
     * re-taxes a catalog on a single click is a screen nobody can use with
     * confidence. The operator re-points the prices first — by feed — and the
     * regime becomes removable on its own.
     *
     * @param form the posted form (id)
     * @return a 303 redirect carrying the notice
     */
    @POST
    @Path("/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteVatRate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteVatRate with form: " + form);
        Long id = AdminForms.id(AdminForms.text(form, "id"));
        VatRate rate = id == null ? null : VatRate.<VatRate>findById(id);
        if (rate == null) {
            LOGGER.info("Exiting method deleteVatRate");
            return AdminForms.redirect(PAGE, "Régime de TVA introuvable.", false);
        }
        long prices = Price.count("vat.id = ?1", rate.id);
        if (prices > 0) {
            LOGGER.info("Exiting method deleteVatRate");
            return AdminForms.redirect(PAGE, "Régime utilisé par " + prices
                    + " prix : repointez-les avant de le retirer.", false);
        }
        rate.delete();
        LOGGER.info("Exiting method deleteVatRate");
        return AdminForms.redirect(PAGE, "Régime de TVA retiré.", true);
    }

    /**
     * Reads a posted regime number.
     *
     * @param raw the posted value, possibly null
     * @return the number, or null when absent, unreadable or negative
     */
    private Integer number(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(raw);
            return parsed < 0 ? null : Integer.valueOf(parsed);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Converts a percentage typed by a human into the fraction the model
     * stores.
     *
     * @param percent the percentage, possibly null
     * @return the fraction at scale 4, or null when absent or out of range
     */
    private BigDecimal fraction(BigDecimal percent) {
        if (percent == null) {
            return null;
        }
        if (percent.signum() < 0 || percent.compareTo(MAX_PERCENT) > 0) {
            return null;
        }
        return percent.divide(MAX_PERCENT, 4, RoundingMode.HALF_UP);
    }
}
