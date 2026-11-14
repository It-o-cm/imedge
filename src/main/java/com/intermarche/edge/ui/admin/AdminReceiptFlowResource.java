package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.safe.ReceiptFlow;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.imports.SafeReceiptCsvResource;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.ReceiptFlowService;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * The RECEIPT FLOWS screen ({@code /admin/safe/receipt-flows},
 * {@code BO-05-02-03}): the existing feeds of takings collected outside the
 * registers — fuel stations, gas suppliers — their layout, and the
 * integration of a file by hand when a feed cannot deposit it itself.
 *
 * <p>The automatic door stays {@code POST /feeds/import/SAFE_RECEIPTS?mode=CODE};
 * this screen declares how each feed's file is read and shows what its last
 * integration answered. A file pasted here goes through exactly the same
 * importer, so a receipt integrated by hand and one deposited by its feed are
 * the same row.
 */
@Path("/admin/safe/receipt-flows")
public class AdminReceiptFlowResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminReceiptFlowResource.class);

    /** The flows template. */
    @Inject
    @Location("admin-receipt-flows")
    Template adminReceiptFlows;

    /** The declared feeds and their defaults. */
    @Inject
    ReceiptFlowService receiptFlowService;

    /** The receipt importer the feeds are integrated through. */
    @Inject
    SafeReceiptCsvResource safeReceiptCsvResource;

    /**
     * Shows the feeds, the three existing ones declared on first use.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_RECEIPT_FLOW)
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list");
        List<ReceiptFlow> flows = receiptFlowService.ensureDefaults();
        LOGGER.info("Exiting method list with " + flows.size() + " flow(s)");
        return adminReceiptFlows.data("flows", flows)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Declares a feed or corrects its layout.
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_RECEIPT_FLOW, option = CrudOption.CREATE, updateKey = "id")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save");
        Long id = AdminForms.id(form.getFirst("id"));
        ReceiptFlow flow = id == null ? null : ReceiptFlow.<ReceiptFlow>findById(id);
        if (tooLong(form)) {
            LOGGER.info("Exiting method save: too long");
            return back("Valeur trop longue : code 30, libellé 120, correspondance 1000 et format des dates "
                    + "30 caractères au plus.", false);
        }
        if (flow == null) {
            String code = AdminForms.text(form, "code");
            if (code == null) {
                LOGGER.info("Exiting method save: no code");
                return back("Code du flux obligatoire.", false);
            }
            if (ReceiptFlow.findByCode(code) != null) {
                LOGGER.info("Exiting method save: duplicate code");
                return back("Ce code de flux existe déjà : " + code, false);
            }
            flow = new ReceiptFlow();
            flow.code = code.toUpperCase(Locale.ROOT);
        }
        String label = AdminForms.text(form, "label");
        String mapping = AdminForms.text(form, "mapping");
        if (label == null || mapping == null || !mapping.toUpperCase(Locale.ROOT).contains("AMOUNT=")) {
            LOGGER.info("Exiting method save: incomplete flow");
            return back("Libellé et correspondance des colonnes (au moins AMOUNT=) obligatoires.", false);
        }
        String separator = form.getFirst("separator");
        flow.label = label;
        flow.mapping = mapping;
        flow.separator = separator == null || separator.isEmpty() ? ";" : separator.substring(0, 1);
        flow.datePattern = AdminForms.text(form, "datePattern");
        flow.active = AdminForms.checkbox(form, "active");
        flow.persist();
        LOGGER.infof("Flux de recettes %s enregistré", flow.code);
        LOGGER.info("Exiting method save");
        return back("Flux de recettes enregistré.", true);
    }

    /**
     * Integrates a file of a feed pasted on the screen.
     *
     * @param form the posted form
     * @return a 303 back to the list with what the importer answered
     */
    @POST
    @Path("/import")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_RECEIPT_FLOW, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response integrate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method integrate");
        String code = AdminForms.text(form, "code");
        String content = form.getFirst("content");
        if (code == null || content == null || content.isBlank()) {
            LOGGER.info("Exiting method integrate: nothing to integrate");
            return back("Flux et contenu du fichier obligatoires.", false);
        }
        receiptFlowService.ensureDefaults();
        ReceiptFlow flow = ReceiptFlow.findByCode(code);
        if (flow == null || !flow.active) {
            LOGGER.info("Exiting method integrate: unknown flow");
            return back("Flux de recettes inconnu ou inactif.", false);
        }
        Response answer = safeReceiptCsvResource.importReceipts(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), flow.code);
        LOGGER.info("Exiting method integrate with status " + answer.getStatus());
        return back(ReceiptFlowService.summary(flow.code, answer.getStatus(), answer.getEntity()),
                answer.getStatus() < 300);
    }

    /**
     * Whether a typed value exceeds what its column holds.
     *
     * @param form the posted form
     * @return true when the code, the label, the mapping or the date format is too long
     */
    static boolean tooLong(MultivaluedMap<String, String> form) {
        return longer(AdminForms.text(form, "code"), 30) || longer(AdminForms.text(form, "label"), 120)
                || longer(AdminForms.text(form, "mapping"), 1000) || longer(AdminForms.text(form, "datePattern"), 30);
    }

    /**
     * Whether a value is longer than a length.
     *
     * @param value the value, possibly null
     * @param length the length
     * @return true when it is longer
     */
    static boolean longer(String value, int length) {
        return value != null && value.length() > length;
    }

    /**
     * Builds the 303 back to this screen.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String notice, boolean ok) {
        return AdminForms.redirect("/admin/safe/receipt-flows", notice, ok);
    }
}
