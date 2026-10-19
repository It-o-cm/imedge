package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.payment.TenderDefinition;
import com.intermarche.edge.domain.payment.PaymentTypes;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The back office's TENDERS page ({@code /admin/tenders}): which settlement
 * methods this store accepts, and under what rules
 * (BO-03-02-02/03/04/05/10/11/12/13/14/15/16/17/18/19/21/22/23/25/30).
 *
 * <p>The page states the doctrine in its own shape: the CODE of a tender is
 * CHOSEN FROM A LIST — the settlement natures the register knows how to carry
 * out — and everything else is typed in. Putting the cheque into service with a
 * ceiling of three hundred euros is administration; inventing a settlement
 * conversation nobody wrote is not, and the form offers no way to ask for it.
 *
 * <p>A row is refused rather than half-saved: the posted description is checked
 * before anything is written — a known settlement key, a functional identifier
 * of the required width, and that identifier free — and the stored row is only
 * touched once it holds together.
 *
 * <p>Same admin cycle as the other back-office pages: POST &rarr; 303 &rarr; a
 * one-shot notice, {@code @RolesAllowed("ADMIN")} throughout.
 */
@Path("/admin/tenders")
public class AdminTenderResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminTenderResource.class);

    /** The separator of the exported list (BO-03-02-05). */
    private static final char CSV_SEPARATOR = ';';

    /** The tenders page template. */
    @Inject
    @Location("admin-tenders")
    Template adminTenders;

    /**
     * Shows the tenders page: the administered rows, the settlement keys a new
     * row may claim, and the catalogs the forms offer.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance tendersPage(@QueryParam("notice") String notice,
            @QueryParam("noticeOk") String noticeOk) {
        LOGGER.info("Entering method tendersPage with notice: " + notice);
        List<TenderDefinition> tenders = TenderDefinition.listAllOrdered();
        LOGGER.info("Exiting method tendersPage");
        return adminTenders
                .data("tenders", tenders)
                .data("settlementKeys", PaymentTypes.keys())
                .data("controls", TenderDefinition.ControlLevel.values())
                .data("drawerMoments", TenderDefinition.DrawerOpening.values())
                .data("notice", notice)
                .data("noticeOk", !"false".equals(noticeOk));
    }

    /**
     * Upserts a tender by its settlement key (BO-03-02-03/04/10 to 30).
     *
     * @param form the posted form carrying the whole administered description
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/save")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveTender(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveTender with form: " + form);
        String code = trimmed(form, "code");
        if (!PaymentTypes.keys().contains(code)) {
            LOGGER.info("Exiting method saveTender");
            return redirect("Moyen de règlement inconnu de la caisse : " + code, false);
        }
        String functionalId = trimmed(form, "functionalId");
        if (functionalId.length() < TenderDefinition.MIN_FUNCTIONAL_ID_LENGTH) {
            LOGGER.info("Exiting method saveTender");
            return redirect("L'identifiant doit compter au moins "
                    + TenderDefinition.MIN_FUNCTIONAL_ID_LENGTH + " caractères.", false);
        }
        TenderDefinition clash = TenderDefinition.find("functionalId", functionalId).firstResult();
        if (clash != null && !code.equals(clash.code)) {
            LOGGER.info("Exiting method saveTender");
            return redirect("L'identifiant " + functionalId + " est déjà pris par " + clash.code + ".",
                    false);
        }
        TenderDefinition existing = TenderDefinition.findByCode(code);
        TenderDefinition tender = existing;
        if (tender == null) {
            tender = new TenderDefinition();
            tender.code = code;
            tender.persist();
        }
        apply(tender, form, functionalId);
        LOGGER.info("Exiting method saveTender");
        return redirect(existing == null ? "Moyen de règlement créé." : "Moyen de règlement enregistré.",
                true);
    }

    /**
     * Removes a tender from the referential (BO-03-02-02).
     *
     * <p>Removing the row does NOT remove the settlement nature from the
     * register; it removes the store's administration of it, which then falls
     * back to the register's own defaults. Taking a tender out of service is
     * what the {@code active} flag is for.
     *
     * @param form the posted form (code)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteTender(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteTender with form: " + form);
        TenderDefinition tender = TenderDefinition.findByCode(trimmed(form, "code"));
        if (tender == null) {
            LOGGER.info("Exiting method deleteTender");
            return redirect("Moyen de règlement inconnu.", false);
        }
        tender.delete();
        LOGGER.info("Exiting method deleteTender");
        return redirect("Moyen de règlement supprimé.", true);
    }

    /**
     * Exports the administered list as a CSV attachment (BO-03-02-05).
     *
     * @return the CSV attachment response
     */
    @GET
    @Path("/export")
    @RolesAllowed("ADMIN")
    @Produces("text/csv")
    public Response export() {
        LOGGER.info("Entering method export");
        String csv = buildCsv(TenderDefinition.listAllOrdered());
        LOGGER.info("Exiting method export");
        return Response.ok(csv)
                .header("Content-Disposition", "attachment; filename=\"modes-de-reglement.csv\"")
                .build();
    }

    /**
     * Writes the administered list as CSV, one row per tender.
     *
     * @param tenders the administered tenders, in administered order
     * @return the CSV body, header line included
     */
    String buildCsv(List<TenderDefinition> tenders) {
        StringBuilder csv = new StringBuilder();
        csv.append("Identifiant;Code;Libelle;Actif;Ordre;Montant max;Controle max;"
                + "Second montant max;Controle second max;Montant min;Controle min;"
                + "Nombre max;Controle nombre;Rendu max;Controle rendu;Remboursement;"
                + "Rendu monnaie;Rendu avec;Declaration auto;Prelevement auto;Tiroir;"
                + "Apport/depense;Remise en banque;Fond de caisse;Total par defaut;"
                + "Detail prelevement;Remontee fidelite;Transfert source;"
                + "Transfert destination\n");
        for (TenderDefinition tender : tenders) {
            csv.append(cell(tender.functionalId))
                    .append(cell(tender.code))
                    .append(cell(tender.label))
                    .append(cell(yesNo(tender.active)))
                    .append(cell(String.valueOf(tender.displayOrder)))
                    .append(cell(amount(tender.maxAmount)))
                    .append(cell(control(tender.maxAmountControl)))
                    .append(cell(amount(tender.secondMaxAmount)))
                    .append(cell(control(tender.secondMaxAmountControl)))
                    .append(cell(amount(tender.minAmount)))
                    .append(cell(control(tender.minAmountControl)))
                    .append(cell(tender.maxCount == null ? "" : String.valueOf(tender.maxCount)))
                    .append(cell(control(tender.maxCountControl)))
                    .append(cell(amount(tender.maxChangeAmount)))
                    .append(cell(control(tender.maxChangeControl)))
                    .append(cell(yesNo(tender.refundAllowed)))
                    .append(cell(yesNo(tender.changeAllowed)))
                    .append(cell(tender.changeTender()))
                    .append(cell(yesNo(tender.cashierDeclaration)))
                    .append(cell(yesNo(tender.automaticWithdrawal)))
                    .append(cell(tender.drawerOpening == null ? "" : tender.drawerOpening.getLabel()))
                    .append(cell(yesNo(tender.movementAllowed)))
                    .append(cell(yesNo(tender.bankDeposit)))
                    .append(cell(yesNo(tender.floatAllowed)))
                    .append(cell(yesNo(tender.defaultsToTotal)))
                    .append(cell(yesNo(tender.withdrawalReportDetail)))
                    .append(cell(yesNo(tender.fidelityReported)))
                    .append(cell(yesNo(tender.transferSource)))
                    .append(yesNo(tender.transferDestination))
                    .append('\n');
        }
        return csv.toString();
    }

    /**
     * Copies the posted description onto the row, once it has been accepted.
     *
     * @param tender the row to write
     * @param form the posted form
     * @param functionalId the validated functional identifier
     */
    private void apply(TenderDefinition tender, MultivaluedMap<String, String> form,
            String functionalId) {
        tender.functionalId = functionalId;
        tender.label = trimmed(form, "label");
        tender.active = form.getFirst("active") != null;
        tender.displayOrder = intOr(form.getFirst("displayOrder"), 100);
        tender.maxAmount = amountOrNull(trimmed(form, "maxAmount"));
        tender.maxAmountControl = parseControl(form.getFirst("maxAmountControl"));
        tender.secondMaxAmount = amountOrNull(trimmed(form, "secondMaxAmount"));
        tender.secondMaxAmountControl = parseControl(form.getFirst("secondMaxAmountControl"));
        tender.minAmount = amountOrNull(trimmed(form, "minAmount"));
        tender.minAmountControl = parseControl(form.getFirst("minAmountControl"));
        tender.maxCount = positiveOrNull(form.getFirst("maxCount"));
        tender.maxCountControl = parseControl(form.getFirst("maxCountControl"));
        tender.maxChangeAmount = amountOrNull(trimmed(form, "maxChangeAmount"));
        tender.maxChangeControl = parseControl(form.getFirst("maxChangeControl"));
        tender.refundAllowed = form.getFirst("refundAllowed") != null;
        tender.changeAllowed = form.getFirst("changeAllowed") != null;
        tender.changeTenderCode = blankToNull(trimmed(form, "changeTenderCode"));
        // BO-03-09-30: the VIC product code of the multi-product card, stored
        // as the acquirer wrote it. Blank becomes null, so a tender the card
        // does not carry carries nothing rather than an empty pair.
        tender.vicProductCode = blankToNull(trimmed(form, "vicProductCode"));
        tender.cashierDeclaration = form.getFirst("cashierDeclaration") != null;
        tender.automaticWithdrawal = form.getFirst("automaticWithdrawal") != null;
        tender.drawerOpening = parseDrawer(form.getFirst("drawerOpening"));
        tender.movementAllowed = form.getFirst("movementAllowed") != null;
        tender.bankDeposit = form.getFirst("bankDeposit") != null;
        tender.floatAllowed = form.getFirst("floatAllowed") != null;
        tender.defaultsToTotal = form.getFirst("defaultsToTotal") != null;
        tender.withdrawalReportDetail = form.getFirst("withdrawalReportDetail") != null;
        tender.fidelityReported = form.getFirst("fidelityReported") != null;
        // BO-03-02-31, -32, -33 et -26 : le justificatif du moyen de paiement.
        // Un code de gabarit vide = ce moyen n'imprime rien de propre.
        tender.documentTemplateCode = blankToNull(trimmed(form, "documentTemplateCode"));
        tender.printCount = Math.max(1, intOr(form.getFirst("printCount"), 1));
        tender.printDuplicate = form.getFirst("printDuplicate") != null;
        tender.printForced = form.getFirst("printForced") != null;
        // BO-05-02-17 : les deux bouts du transfert de règlement, administrés
        // séparément — un moyen peut être corrigeable vers un autre sans que
        // l'inverse ait un sens.
        tender.transferSource = form.getFirst("transferSource") != null;
        tender.transferDestination = form.getFirst("transferDestination") != null;
    }

    /**
     * Parses a control level name, an unknown or absent value meaning none.
     *
     * @param raw the posted name
     * @return the level, never null
     */
    private TenderDefinition.ControlLevel parseControl(String raw) {
        for (TenderDefinition.ControlLevel level : TenderDefinition.ControlLevel.values()) {
            if (level.name().equals(raw)) {
                return level;
            }
        }
        return TenderDefinition.ControlLevel.NONE;
    }

    /**
     * Parses a drawer-opening moment name, an unknown or absent value meaning
     * that the drawer never opens.
     *
     * @param raw the posted name
     * @return the moment, never null
     */
    private TenderDefinition.DrawerOpening parseDrawer(String raw) {
        for (TenderDefinition.DrawerOpening moment : TenderDefinition.DrawerOpening.values()) {
            if (moment.name().equals(raw)) {
                return moment;
            }
        }
        return TenderDefinition.DrawerOpening.NEVER;
    }

    /**
     * Reads a posted amount, an absent or malformed one meaning unbounded.
     *
     * @param raw the posted value, trimmed
     * @return the amount, or null when nothing readable was posted
     */
    private BigDecimal amountOrNull(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads a posted whole number, falling back when it is absent or malformed.
     *
     * @param raw the posted value
     * @param fallback the value to use when nothing readable was posted
     * @return the parsed number, or the fallback
     */
    private int intOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Reads a posted strictly positive number, an absent value being legitimate.
     *
     * @param raw the posted value
     * @return the number, or null when absent, malformed or not positive
     */
    private Integer positiveOrNull(String raw) {
        int parsed = intOr(raw, -1);
        return parsed > 0 ? parsed : null;
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
     * Writes one CSV cell, followed by the separator.
     *
     * @param value the cell content, or null
     * @return the cell and its separator
     */
    private String cell(String value) {
        return (value == null ? "" : value) + CSV_SEPARATOR;
    }

    /**
     * Writes a flag the way the exported list reads it.
     *
     * @param flag the flag
     * @return "Oui" or "Non"
     */
    private String yesNo(boolean flag) {
        return flag ? "Oui" : "Non";
    }

    /**
     * Writes an administered bound, an absent one showing as empty.
     *
     * @param value the bound, or null when unbounded
     * @return the plain figure, or the empty string
     */
    private String amount(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    /**
     * Writes a control level, a missing one showing as empty.
     *
     * @param level the level, or null
     * @return the level label, or the empty string
     */
    private String control(TenderDefinition.ControlLevel level) {
        return level == null ? "" : level.getLabel();
    }

    /**
     * Builds the 303 redirect carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/tenders?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }
}
