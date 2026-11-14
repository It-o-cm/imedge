package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.sale.DocumentType;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.setting.DocumentTemplate;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.setting.PrintableDocument;
import com.intermarche.edge.security.FeatureAccess;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The back office's PRINTABLE DOCUMENTS page ({@code /admin/printable-documents},
 * BO-03-04-06, -09, -10 and -14): the documents the registers offer over a
 * closed ticket.
 *
 * <p>A document is created with a code, the name it prints, its nature — the
 * invoice or the delivery note, which decides its numbering sequence —, the
 * template assembling its zones and the printer it comes out of. Activating it
 * makes it available at every register on the next referential pull; deleting
 * it removes it from them. A document the registers have ALREADY printed is not
 * deleted but deactivated: the papers carry its name, and the archive of the
 * documents issued must still say what they were.
 *
 * <p>POST, then 303 back to the page with a one-shot notice, and
 * {@code @FeatureAccess} throughout (BO-01-04-01).
 */
@Path("/admin/printable-documents")
public class AdminPrintableDocumentResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminPrintableDocumentResource.class);

    /** The page every action returns to. */
    private static final String PAGE = "/admin/printable-documents";

    /** The page template. */
    @Inject
    @Location("admin-printable-documents")
    Template adminPrintableDocuments;

    /**
     * Shows the documents and what the creation form offers.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.PRINTABLE_DOCUMENT)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance documentsPage(@QueryParam("notice") String notice,
            @QueryParam("noticeOk") String noticeOk) {
        LOGGER.info("Entering method documentsPage");
        List<PrintableDocument> documents = PrintableDocument.listAllOrdered();
        LOGGER.info("Exiting method documentsPage");
        return adminPrintableDocuments
                .data("documents", documents)
                .data("kinds", DocumentType.values())
                .data("templates", layoutTemplates())
                .data("notice", notice)
                .data("noticeOk", !"false".equals(noticeOk));
    }

    /**
     * Creates or updates a document, keyed on its code (BO-03-04-09, -14).
     *
     * <p>Every mandatory column is set before the row is written: a new
     * document is persisted whole or not at all.
     *
     * @param form the posted description
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/save")
    @Authenticated
    @FeatureAccess(value = Feature.PRINTABLE_DOCUMENT, option = CrudOption.CREATE, updateKey = "code",
            entity = PrintableDocument.class)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveDocument(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveDocument");
        String code = AdminForms.text(form, "code");
        String label = AdminForms.text(form, "label");
        if (code == null || label == null) {
            LOGGER.info("Exiting method saveDocument");
            return AdminForms.redirect(PAGE, "Le code et le nom du document sont obligatoires.", false);
        }
        DocumentType kind = DocumentType.byName(AdminForms.text(form, "kind"));
        if (kind == null) {
            LOGGER.info("Exiting method saveDocument");
            return AdminForms.redirect(PAGE, "Nature de document inconnue.", false);
        }
        String templateCode = AdminForms.text(form, "templateCode");
        if (templateCode != null && DocumentTemplate.findByCode(templateCode) == null) {
            LOGGER.info("Exiting method saveDocument");
            return AdminForms.redirect(PAGE, "Gabarit inconnu.", false);
        }
        PrintableDocument existing = PrintableDocument.findByCode(code);
        PrintableDocument document = existing == null ? new PrintableDocument() : existing;
        document.code = code;
        document.label = label;
        document.kind = kind;
        document.output = PrintableDocument.outputOf(AdminForms.text(form, "output"));
        document.templateCode = templateCode;
        document.priority = AdminForms.integer(AdminForms.text(form, "priority"), 100);
        document.active = AdminForms.checkbox(form, "active");
        document.persist();
        LOGGER.infof("Document imprimable %s enregistré (%s, %s).", code, kind, document.output);
        LOGGER.info("Exiting method saveDocument");
        return AdminForms.redirect(PAGE, existing == null
                ? "Document créé." : "Document enregistré.", true);
    }

    /**
     * Activates or deactivates a document at the registers (BO-03-04-06).
     *
     * @param form the posted code
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/toggle")
    @Authenticated
    @FeatureAccess(value = Feature.PRINTABLE_DOCUMENT, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response toggleDocument(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method toggleDocument");
        PrintableDocument document = PrintableDocument.findByCode(AdminForms.text(form, "code"));
        if (document == null) {
            LOGGER.info("Exiting method toggleDocument");
            return AdminForms.redirect(PAGE, "Document inconnu.", false);
        }
        document.active = !document.active;
        document.persist();
        LOGGER.info("Exiting method toggleDocument");
        return AdminForms.redirect(PAGE, document.active
                ? "Document activé : il est proposé en caisse."
                : "Document désactivé : il n'est plus proposé en caisse.", true);
    }

    /**
     * Deletes a document (BO-03-04-10) — or, when the registers have already
     * printed it, deactivates it and says so.
     *
     * @param form the posted code
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/delete")
    @Authenticated
    @FeatureAccess(value = Feature.PRINTABLE_DOCUMENT, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteDocument(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteDocument");
        PrintableDocument document = PrintableDocument.findByCode(AdminForms.text(form, "code"));
        if (document == null) {
            LOGGER.info("Exiting method deleteDocument");
            return AdminForms.redirect(PAGE, "Document inconnu.", false);
        }
        if (Invoice.count("documentCode", document.code) > 0) {
            document.active = false;
            document.persist();
            LOGGER.info("Exiting method deleteDocument");
            return AdminForms.redirect(PAGE,
                    "Document déjà imprimé en caisse : il est désactivé et non supprimé.", false);
        }
        document.delete();
        LOGGER.info("Exiting method deleteDocument");
        return AdminForms.redirect(PAGE, "Document supprimé.", true);
    }

    /**
     * The templates a document may be laid out with: those of an invoice, on
     * the roll or on an A4 page — the ones that assemble the zones of a
     * document printed over a ticket.
     *
     * @return the templates, in administered order
     */
    private List<DocumentTemplate> layoutTemplates() {
        List<DocumentTemplate> layouts = new ArrayList<>();
        for (DocumentTemplate template : DocumentTemplate.listAllOrdered()) {
            if (template.documentType == DocumentTemplate.DocumentType.INVOICE
                    || template.documentType == DocumentTemplate.DocumentType.INVOICE_A4) {
                layouts.add(template);
            }
        }
        return layouts;
    }
}
