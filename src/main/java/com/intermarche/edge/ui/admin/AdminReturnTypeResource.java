package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.barcode.ArticleBarcodeRange;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.setting.PosList;
import com.intermarche.edge.domain.setting.PosListItem;
import com.intermarche.edge.domain.setting.ReturnTypeDefinition;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.PosSettingsService;
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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The RETURN TYPE screen ({@code /admin/return-types}, BO-03-13-01 to -04,
 * -11, -12): the types of return the registers offer, administered as a
 * referential of their own and distributed by the {@code RETURN_TYPES} pull.
 *
 * <ul>
 *   <li>CREATE (BO-03-13-01): a code, a label and the options the register
 *       honours — ticket mode, no endorsement, no original ticket, price
 *       confirmed, déconsigne, attached RETURN barcode range;</li>
 *   <li>DELETE (BO-03-13-02): a type the refunds or the return lines already
 *       name is DEACTIVATED instead, and the administrator is told how many
 *       returns name it;</li>
 *   <li>ACTIVATE (BO-03-13-03): one click switches a type in or out of the
 *       registers, with the next pull.</li>
 * </ul>
 *
 * <p>REPRENDRE LES TYPES EXISTANTS migrates in one gesture what the shop
 * administered before this screen — the entries of the return-type lists and
 * the {@code return.types} setting — creating only the codes that do not
 * exist yet. Until a type exists here, the registers keep reading the list,
 * then the setting.
 */
@Path("/admin/return-types")
public class AdminReturnTypeResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminReturnTypeResource.class);

    /** The redirect target, equal to the resource path. */
    static final String PAGE = "/admin/return-types";

    /** The longest code the screen accepts. */
    static final int MAX_CODE = 40;

    /** The return-types template. */
    @Inject
    @Location("admin-return-types")
    Template adminReturnTypes;

    /** The administered parameters, read by the migration gesture. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * One return type as the screen draws it.
     */
    public static class Row {

        /** The type itself. */
        public ReturnTypeDefinition type;

        /** How many returns already name it. */
        public long used;
    }

    /**
     * Shows the return types.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.RETURN_TYPE)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list");
        List<Row> rows = new ArrayList<>();
        for (ReturnTypeDefinition type : ReturnTypeDefinition.findAllOrdered()) {
            Row row = new Row();
            row.type = type;
            row.used = returnsOfType(type.code);
            rows.add(row);
        }
        LOGGER.info("Exiting method list with " + rows.size() + " type(s)");
        return adminReturnTypes.data("rows", rows)
                .data("ranges", refundRangeCodes())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Creates or updates a return type by its code (BO-03-13-01, -04, -11,
     * -12). Every mandatory column is set before the row is persisted.
     *
     * @param form the posted form
     * @return a 303 back to the screen with the outcome
     */
    @POST
    @Path("/save")
    @Authenticated
    @FeatureAccess(value = Feature.RETURN_TYPE, option = CrudOption.CREATE, updateKey = "code",
            entity = ReturnTypeDefinition.class)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save");
        String code = AdminForms.text(form, "code");
        if (code == null || code.length() > MAX_CODE) {
            LOGGER.info("Exiting method save: no code");
            return AdminForms.redirect(PAGE, "Le code du type de retour est obligatoire (40 caractères au plus).", false);
        }
        String label = AdminForms.text(form, "label");
        if (label == null) {
            LOGGER.info("Exiting method save: no label");
            return AdminForms.redirect(PAGE, "Le libellé du type de retour est obligatoire.", false);
        }
        String range = AdminForms.text(form, "rangeCode");
        if (range != null && !refundRangeCodes().contains(range)) {
            LOGGER.info("Exiting method save: unknown range");
            return AdminForms.redirect(PAGE, "Plage de codes-barres retour inconnue : " + range, false);
        }
        String key = code.toUpperCase(Locale.ROOT);
        ReturnTypeDefinition existing = ReturnTypeDefinition.findByCode(key);
        ReturnTypeDefinition type = existing == null ? new ReturnTypeDefinition() : existing;
        type.code = key;
        type.label = label;
        type.ticketMode = AdminForms.checkbox(form, "ticketMode");
        type.endorsementExempt = AdminForms.checkbox(form, "endorsementExempt");
        type.originOptional = AdminForms.checkbox(form, "originOptional");
        type.priceConfirmed = AdminForms.checkbox(form, "priceConfirmed");
        type.depositReturn = AdminForms.checkbox(form, "depositReturn");
        type.rangeCode = range;
        type.displayOrder = AdminForms.integer(AdminForms.text(form, "displayOrder"), 0);
        type.active = AdminForms.checkbox(form, "active");
        type.persist();
        LOGGER.info("Exiting method save");
        return AdminForms.redirect(PAGE, existing == null ? "Type de retour créé." : "Type de retour enregistré.", true);
    }

    /**
     * Deletes a return type (BO-03-13-02), or deactivates it when returns
     * already name it — the refunds and the return lines filed under its code
     * must keep meaning something.
     *
     * @param form the posted form (code)
     * @return a 303 back to the screen with the outcome
     */
    @POST
    @Path("/delete")
    @Authenticated
    @FeatureAccess(value = Feature.RETURN_TYPE, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete");
        ReturnTypeDefinition type = ReturnTypeDefinition.findByCode(AdminForms.text(form, "code"));
        if (type == null) {
            LOGGER.info("Exiting method delete: unknown type");
            return AdminForms.redirect(PAGE, "Type de retour inconnu.", false);
        }
        long used = returnsOfType(type.code);
        if (used > 0) {
            type.active = false;
            type.persist();
            LOGGER.info("Exiting method delete: in use, deactivated");
            return AdminForms.redirect(PAGE, "Le type de retour " + type.code + " est rattaché à " + used
                    + " retour(s) : il a été désactivé et non supprimé.", false);
        }
        type.delete();
        LOGGER.info("Exiting method delete");
        return AdminForms.redirect(PAGE, "Type de retour supprimé.", true);
    }

    /**
     * Switches a return type in or out of the registers in one click
     * (BO-03-13-03).
     *
     * @param form the posted form (code)
     * @return a 303 back to the screen with the outcome
     */
    @POST
    @Path("/toggle")
    @Authenticated
    @FeatureAccess(value = Feature.RETURN_TYPE, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response toggle(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method toggle");
        ReturnTypeDefinition type = ReturnTypeDefinition.findByCode(AdminForms.text(form, "code"));
        if (type == null) {
            LOGGER.info("Exiting method toggle: unknown type");
            return AdminForms.redirect(PAGE, "Type de retour inconnu.", false);
        }
        type.active = !type.active;
        type.persist();
        LOGGER.info("Exiting method toggle");
        return AdminForms.redirect(PAGE, type.active ? "Type de retour activé." : "Type de retour désactivé.", true);
    }

    /**
     * Takes over the return types the shop administered before this screen:
     * the entries of the return-type lists, then the {@code return.types}
     * setting, creating only the codes that do not exist yet.
     *
     * @return a 303 back to the screen with the number of types created
     */
    @POST
    @Path("/import")
    @Authenticated
    @FeatureAccess(value = Feature.RETURN_TYPE, option = CrudOption.CREATE)
    @Transactional
    public Response importExisting() {
        LOGGER.info("Entering method importExisting");
        int created = 0;
        int rank = 0;
        for (PosList list : PosList.<PosList>list("kind = ?1 order by code", PosList.Kind.RETURN_TYPE)) {
            for (PosListItem item : list.items) {
                rank++;
                if (createIfAbsent(item.code, item.label, item.options, rank, item.active)) {
                    created++;
                }
            }
        }
        for (PosSettingsService.ReturnType legacy : posSettingsService.returnTypes()) {
            rank++;
            String options = (legacy.dedicatedTicket() ? "TICKET," : "")
                    + (legacy.endorsementExempt() ? "NOENDORSE," : "")
                    + (legacy.originOptional() ? "NOORIGIN," : "")
                    + (legacy.priceConfirmed() ? "PRICE" : "");
            if (createIfAbsent(legacy.label(), legacy.label(), options, rank, true)) {
                created++;
            }
        }
        LOGGER.info("Exiting method importExisting with " + created + " type(s) created");
        return created == 0
                ? AdminForms.redirect(PAGE, "Aucun type de retour à reprendre.", true)
                : AdminForms.redirect(PAGE, created + " type(s) de retour repris de la liste et du paramètre.", true);
    }

    /**
     * Creates a return type from an entry administered the old way, unless
     * its code already exists.
     *
     * @param rawCode the entry's code, or its label when it had none
     * @param label the entry's label
     * @param options the entry's option markers, possibly null
     * @param rank the display rank to give it
     * @param active whether the entry was offered
     * @return true when a type was created
     */
    private boolean createIfAbsent(String rawCode, String label, String options, int rank, boolean active) {
        String code = rawCode.trim().toUpperCase(Locale.ROOT);
        if (code.length() > MAX_CODE) {
            code = code.substring(0, MAX_CODE);
        }
        if (ReturnTypeDefinition.findByCode(code) != null) {
            return false;
        }
        ReturnTypeDefinition type = new ReturnTypeDefinition();
        type.code = code;
        type.label = label;
        type.ticketMode = marked(options, "TICKET");
        type.endorsementExempt = marked(options, "NOENDORSE");
        type.originOptional = marked(options, "NOORIGIN");
        type.priceConfirmed = marked(options, "PRICE");
        type.depositReturn = marked(options, "DECONSIGNE");
        type.rangeCode = rangeOption(options);
        type.displayOrder = rank;
        type.active = active;
        type.persist();
        return true;
    }

    /**
     * Tells whether option markers carry a bare marker.
     *
     * @param options the comma-separated markers, possibly null
     * @param name the marker, upper case
     * @return true when the marker is present
     */
    static boolean marked(String options, String name) {
        if (options == null) {
            return false;
        }
        for (String part : options.split(",")) {
            if (part.trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the {@code RANGE:code} option of option markers.
     *
     * @param options the comma-separated markers, possibly null
     * @return the range code, or null when the option is absent or empty
     */
    static String rangeOption(String options) {
        if (options == null) {
            return null;
        }
        for (String part : options.split(",")) {
            String marker = part.trim();
            if (marker.toUpperCase(Locale.ROOT).startsWith("RANGE:") && marker.length() > 6) {
                return marker.substring(6).trim();
            }
        }
        return null;
    }

    /**
     * Counts the returns that name a return type (BO-03-13-02): the refunds
     * of a dedicated return ticket and the return lines taken back inside a
     * sale (BO-03-13-12).
     *
     * @param code the return type code
     * @return how many returns carry that type code
     */
    long returnsOfType(String code) {
        return com.intermarche.edge.domain.sale.Refund.count("returnTypeCode", code)
                + com.intermarche.edge.domain.sale.TicketLine.count("returnTypeCode", code);
    }

    /**
     * The codes of the RETURN barcode ranges a type may be attached to
     * (BO-03-13-12).
     *
     * @return the range codes, in code order
     */
    List<String> refundRangeCodes() {
        List<String> codes = new ArrayList<>();
        for (ArticleBarcodeRange range : ArticleBarcodeRange.<ArticleBarcodeRange>list(
                "purpose = ?1 order by code", ArticleBarcodeRange.Purpose.REFUND)) {
            codes.add(range.code);
        }
        return codes;
    }
}
