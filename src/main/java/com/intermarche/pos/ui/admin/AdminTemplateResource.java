package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.setting.DocumentTemplate;
import com.intermarche.pos.service.DocumentTemplateService;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The back office's DOCUMENT TEMPLATES page ({@code /admin/templates}): the
 * layout of every printed document, edited as a Qute source (BO-03-03).
 *
 * <p>The editor is deliberately a TEXT AREA and not a canvas. A receipt is a
 * flow of lines on a 42-column roll, not a page with things to drag sideways,
 * and the register already runs Qute for every screen it draws — so the layout
 * of a document is a template like any other, stored in a row instead of a file.
 *
 * <p>What makes the screen an editor rather than a form is the PREVIEW: the
 * source is rendered against a demonstration sale on every display, so the
 * paramétreur reads the receipt they have just described instead of imagining
 * it. The demonstration values are the service's own, which are the very shape
 * the printers hand a template at printing time.
 *
 * <p>A source that does not compile is REFUSED, with the engine's own complaint
 * shown: a template saved broken would print nothing at the till, and the till
 * is the worst place to discover a typo.
 *
 * <p>Same admin cycle as the other back-office pages: POST &rarr; 303 &rarr; a
 * one-shot notice, {@code @RolesAllowed("ADMIN")} throughout.
 */
@Path("/admin/templates")
public class AdminTemplateResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminTemplateResource.class);

    /** The document templates page. */
    @Inject
    @Location("admin-templates")
    Template adminTemplates;

    /** The renderer, asked both to check a source and to preview it. */
    @Inject
    DocumentTemplateService documentTemplateService;

    /** Prefix of a picker entry naming a house starter. */
    static final String EXAMPLE_PICK = "example:";

    /** Prefix of a picker entry naming a layout the shop administers. */
    static final String TEMPLATE_PICK = "template:";

    /**
     * Shows the templates page: every administered layout, its preview, and the
     * document types a new one may claim.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance templatesPage(@QueryParam("notice") String notice,
            @QueryParam("noticeOk") String noticeOk) {
        LOGGER.info("Entering method templatesPage with notice: " + notice);
        LOGGER.info("Exiting method templatesPage");
        return page(notice, !"false".equals(noticeOk), null);
    }

    /**
     * Renders the posted source WITHOUT storing it (the "Aperçu" button).
     * <p>
     * A preview that saved would make trying a layout a commitment, and the
     * operator would stop trying. Nothing is written here: the page comes back
     * with the source as typed and what it renders against the demonstration
     * sale, and the row in the database is untouched until Enregistrer.
     *
     * @param form the posted form (code, documentType, source)
     * @return the page carrying the draft and its rendering
     */
    @POST
    @Path("/preview")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance previewTemplate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method previewTemplate");
        LOGGER.info("Exiting method previewTemplate");
        return page(null, true, draftOf(form, trimmed(form, "source")));
    }

    /**
     * Loads an EXISTING layout into the editor — a ready-made example or a
     * layout the shop already administers (the "Partir de" picker).
     * <p>
     * Nobody writes a receipt from an empty box. A shop that has a good
     * A4 invoice and wants a second one starts from the first, and a shop
     * starting out takes the house example; what it must never have to do is
     * retype a header, a VAT ventilation and a settlement loop from memory.
     * <p>
     * Nothing is stored: the chosen source lands in the box, already rendered
     * beside it, and the operator cuts it down and saves — or does not. The
     * CODE is never copied along: it is the identity of the row, and two
     * layouts sharing one would overwrite each other at the next save.
     *
     * @param form the posted form (card, documentType, from)
     * @return the page carrying the copied source and its rendering
     */
    @POST
    @Path("/copy")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance copySource(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method copySource");
        LOGGER.info("Exiting method copySource");
        return page(null, true, draftOf(form, sourceOf(trimmed(form, "from"),
                documentType(form.getFirst("documentType")))));
    }

    /**
     * The source a picked entry carries.
     * <p>
     * Two kinds, told apart by their prefix rather than by a second field: an
     * {@code example:} entry is the house starter of a document type, a
     * {@code template:} entry is a layout the shop administers. Anything else —
     * an empty pick, a code since deleted — falls back on the example of the
     * chosen type rather than emptying the box.
     *
     * @param pick the posted entry key
     * @param type the document type the card is on
     * @return the source to load, never null
     */
    private String sourceOf(String pick, DocumentTemplate.DocumentType type) {
        if (pick.startsWith(TEMPLATE_PICK)) {
            DocumentTemplate held = DocumentTemplate.findByCode(
                    pick.substring(TEMPLATE_PICK.length()));
            if (held != null && held.source != null) {
                return held.source;
            }
        }
        if (pick.startsWith(EXAMPLE_PICK)) {
            return documentTemplateService.defaultSource(
                    documentType(pick.substring(EXAMPLE_PICK.length())));
        }
        return documentTemplateService.defaultSource(type);
    }

    /**
     * What the "Partir de" picker offers on a card of a given type, the
     * layouts of that SAME type first.
     * <p>
     * Privileging the same type is not cosmetic: a starter written for a Z
     * report reads {@code session} and {@code tenders}, keys a sale receipt
     * never carries, so picking across types yields a layout whose every line
     * renders empty. Same-type entries are the ones that will work; the others
     * stay reachable because a shop sometimes wants a header it wrote once.
     *
     * @param type the document type the card is on
     * @param held the layouts the shop administers
     * @return the picker entries, same type first
     */
    private List<Pick> picksFor(DocumentTemplate.DocumentType type,
            List<DocumentTemplate> held) {
        List<Pick> same = new ArrayList<>();
        List<Pick> others = new ArrayList<>();
        for (DocumentTemplate.DocumentType candidate : DocumentTemplate.DocumentType.values()) {
            Pick pick = new Pick(EXAMPLE_PICK + candidate.name(),
                    "Exemple — " + candidate.getLabel(), candidate.name(),
                    candidate == type);
            (pick.sameType ? same : others).add(pick);
        }
        for (DocumentTemplate template : held) {
            String label = template.code
                    + (template.label == null || template.label.isBlank()
                            ? "" : " — " + template.label);
            String typeName = template.documentType == null ? ""
                    : template.documentType.name();
            Pick pick = new Pick(TEMPLATE_PICK + template.code, label, typeName,
                    template.documentType == type);
            (pick.sameType ? same : others).add(pick);
        }
        List<Pick> picks = new ArrayList<>(same);
        picks.addAll(others);
        return picks;
    }

    /**
     * One entry of the "Partir de" picker.
     */
    @io.quarkus.qute.TemplateData
    public static class Pick {

        /** The entry key, carrying its kind as a prefix. */
        public final String key;

        /** What the operator reads. */
        public final String label;

        /** The document type this entry belongs to, for the live re-ordering. */
        public final String type;

        /** Whether it is of the card's own document type. */
        public final boolean sameType;

        /**
         * Builds an entry.
         *
         * @param key the entry key
         * @param label what the operator reads
         * @param type the document type it belongs to
         * @param sameType whether it matches the card's type
         */
        public Pick(String key, String label, String type, boolean sameType) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.sameType = sameType;
        }

        /**
         * Returns the entry key.
         *
         * @return the key
         */
        public String getKey() {
            return key;
        }

        /**
         * Returns what the operator reads.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }

        /**
         * Returns the document type this entry belongs to.
         *
         * @return the type name
         */
        public String getType() {
            return type;
        }

        /**
         * Returns whether the entry matches the card's document type.
         *
         * @return true when it is of the same type
         */
        public boolean isSameType() {
            return sameType;
        }
    }

    /**
     * Builds the page, optionally carrying a draft the operator is trying out.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @param draft the draft being tried, or null
     * @return the page
     */
    private TemplateInstance page(String notice, boolean noticeOk, Draft draft) {
        List<DocumentTemplate> templates = DocumentTemplate.listAllOrdered();
        List<Preview> previews = new ArrayList<>();
        for (DocumentTemplate template : templates) {
            previews.add(preview(template, picksFor(template.documentType, templates)));
        }
        DocumentTemplate.DocumentType newType = draft != null && draft.isNew()
                ? draft.getDocumentType() : DocumentTemplate.DocumentType.SALE_RECEIPT;
        return adminTemplates
                .data("previews", previews)
                .data("documentTypes", DocumentTemplate.DocumentType.values())
                .data("newPicks", picksFor(newType, templates))
                .data("newReferences", documentTemplateService.references(newType))
                .data("draft", draft)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * What a layout of one document type may read, for the context menu
     * ({@code GET /admin/templates/api/references?type=…}).
     * <p>
     * Fetched when the menu opens rather than shipped with the page: the
     * catalogue is wanted for ONE card, the one being typed in, and the
     * creation card changes its type while the page stays up. The first
     * version rendered all eleven catalogues at the top of the page, which
     * asked the paramétreur to find their own among ten they had no use for.
     *
     * @param typeName the document type asked about
     * @return the readable expressions as JSON
     */
    @GET
    @Path("/api/references")
    @RolesAllowed("ADMIN")
    @Produces(MediaType.APPLICATION_JSON)
    public Response apiReferences(@QueryParam("type") String typeName) {
        LOGGER.info("Entering method apiReferences with type: " + typeName);
        StringBuilder body = new StringBuilder("[");
        boolean first = true;
        for (DocumentTemplateService.Reference reference
                : documentTemplateService.references(documentType(typeName))) {
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append("{\"expression\":\"").append(escape(reference.getExpression()))
                    .append("\",\"sample\":\"").append(escape(reference.getSample()))
                    .append("\",\"loop\":").append(reference.getLoop() == null ? "null"
                            : "\"" + escape(reference.getLoop()) + "\"")
                    .append('}');
        }
        LOGGER.info("Exiting method apiReferences");
        return Response.ok(body.append(']').toString()).build();
    }

    /**
     * Escapes a value for the JSON body above.
     *
     * @param value the value, possibly null
     * @return the escaped value, never null
     */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    /**
     * Builds the draft of a posted source: which CARD it belongs to, what it
     * says, and what it renders or why it does not.
     * <p>
     * The card, not the code: on the creation card the operator types the code
     * they are about to use, so keying the draft on it sent the preview to a
     * card that does not exist yet and the box stayed empty. A hidden field
     * names the card instead — {@code new} for the creation card, the template
     * code for an existing one — and that never changes as the form is filled.
     *
     * @param form the posted form (card, documentType)
     * @param source the source to try
     * @return the draft, never null
     */
    private Draft draftOf(MultivaluedMap<String, String> form, String source) {
        String card = trimmed(form, "card");
        String code = trimmed(form, "code");
        DocumentTemplate.DocumentType type = documentType(form.getFirst("documentType"));
        String complaint = documentTemplateService.parseError(source);
        String rendered = null;
        if (complaint == null) {
            DocumentTemplate trial = new DocumentTemplate();
            trial.code = code.isEmpty() ? "APERCU" : code;
            trial.documentType = type;
            trial.active = true;
            trial.width = DocumentTemplate.MIN_WIDTH;
            trial.source = source;
            rendered = documentTemplateService.renderTemplate(
                    trial, documentTemplateService.sampleData(type));
        }
        return new Draft(card, type, source, rendered, complaint);
    }

    /**
     * Resolves a posted document type, falling back on the sale receipt.
     *
     * @param posted the posted type name, possibly null or unknown
     * @return the resolved type, never null
     */
    private DocumentTemplate.DocumentType documentType(String posted) {
        for (DocumentTemplate.DocumentType candidate : DocumentTemplate.DocumentType.values()) {
            if (candidate.name().equals(posted)) {
                return candidate;
            }
        }
        return DocumentTemplate.DocumentType.SALE_RECEIPT;
    }

    /**
     * Upserts a template by its code (BO-03-03).
     *
     * @param form the posted form (code, label, documentType, source, width,
     *             copies, priority, active)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/save")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveTemplate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveTemplate with code: " + form.getFirst("code"));
        String code = trimmed(form, "code");
        if (code.isEmpty()) {
            LOGGER.info("Exiting method saveTemplate");
            return redirect("Le code du gabarit est obligatoire.", false);
        }
        DocumentTemplate.DocumentType type = parseType(form.getFirst("documentType"));
        if (type == null) {
            LOGGER.info("Exiting method saveTemplate");
            return redirect("Type de document inconnu.", false);
        }
        String source = form.getFirst("source") == null ? "" : form.getFirst("source");
        String complaint = documentTemplateService.parseError(source);
        if (complaint != null) {
            LOGGER.info("Exiting method saveTemplate");
            return redirect("Gabarit refusé : " + complaint, false);
        }
        int width = intOr(form.getFirst("width"), 42);
        if (width < DocumentTemplate.MIN_WIDTH || width > DocumentTemplate.MAX_WIDTH) {
            LOGGER.info("Exiting method saveTemplate");
            return redirect("La largeur doit être comprise entre "
                    + DocumentTemplate.MIN_WIDTH + " et " + DocumentTemplate.MAX_WIDTH + ".",
                    false);
        }
        DocumentTemplate existing = DocumentTemplate.findByCode(code);
        DocumentTemplate template = existing == null ? new DocumentTemplate() : existing;
        template.code = code;
        // Every column is set BEFORE the row is written. Persisting first and
        // filling afterwards worked only as long as the flush happened after
        // the assignments; label is NOT NULL, so a creation went to the
        // database with a null label and the transaction rolled back.
        template.label = trimmed(form, "label");
        template.documentType = type;
        template.source = source;
        template.width = width;
        template.copies = intOr(form.getFirst("copies"), 1);
        template.priority = intOr(form.getFirst("priority"), 100);
        template.active = form.getFirst("active") != null;
        if (existing == null) {
            template.persist();
        }
        documentTemplateService.clearCache();
        LOGGER.info("Exiting method saveTemplate");
        return redirect(existing == null ? "Gabarit créé." : "Gabarit enregistré.", true);
    }

    /**
     * Removes a template from the referential, the register then printing that
     * document its own way again (BO-03-03).
     *
     * @param form the posted form (code)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteTemplate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteTemplate with form: " + form);
        DocumentTemplate template = DocumentTemplate.findByCode(trimmed(form, "code"));
        if (template == null) {
            LOGGER.info("Exiting method deleteTemplate");
            return redirect("Gabarit inconnu.", false);
        }
        template.delete();
        documentTemplateService.clearCache();
        LOGGER.info("Exiting method deleteTemplate");
        return redirect("Gabarit supprimé.", true);
    }

    /**
     * Builds the preview of one template against the demonstration document of
     * its type, with the picker entries its card offers.
     *
     * @param template the administered template
     * @param picks what the card's "Partir de" picker offers
     * @return the template, what it renders and what it may start from
     */
    Preview preview(DocumentTemplate template, List<Pick> picks) {
        String rendered = documentTemplateService.renderTemplate(
                template, documentTemplateService.sampleData(template.documentType));
        return new Preview(template, rendered, picks,
                documentTemplateService.references(template.documentType));
    }

    /**
     * Parses a document type name, tolerating an unknown or blank value.
     *
     * @param raw the posted name
     * @return the type, or null when unrecognised
     */
    private DocumentTemplate.DocumentType parseType(String raw) {
        for (DocumentTemplate.DocumentType type : DocumentTemplate.DocumentType.values()) {
            if (type.name().equals(raw)) {
                return type;
            }
        }
        return null;
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
     * Builds the 303 redirect carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/templates?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }

    /**
     * A layout the operator is TRYING OUT: never stored, shown back in the
     * editor with what it renders, or with the parser's complaint.
     */
    public static class Draft {

        /** The name the creation card posts to identify itself. */
        public static final String NEW_CARD = "new";

        /** Which card posted it: {@code new}, or the template's code. */
        public final String card;

        /** The kind of document it was rendered against. */
        public final DocumentTemplate.DocumentType documentType;

        /** The source as typed. */
        public final String source;

        /** What it renders against the demonstration sale, or null. */
        public final String rendered;

        /** Why it renders nothing, or null when it renders. */
        public final String error;

        /**
         * Builds a draft.
         *
         * @param card the card that posted it
         * @param documentType the kind of document
         * @param source the source as typed
         * @param rendered what it renders, or null
         * @param error the parser's complaint, or null
         */
        public Draft(String card, DocumentTemplate.DocumentType documentType, String source,
                     String rendered, String error) {
            this.card = card;
            this.documentType = documentType;
            this.source = source;
            this.rendered = rendered;
            this.error = error;
        }

        /**
         * Returns which card posted this draft.
         *
         * @return the card name
         */
        public String getCard() {
            return card;
        }

        /**
         * Returns the kind of document.
         *
         * @return the document type
         */
        public DocumentTemplate.DocumentType getDocumentType() {
            return documentType;
        }

        /**
         * Returns the source as typed.
         *
         * @return the source
         */
        public String getSource() {
            return source;
        }

        /**
         * Returns what the draft renders.
         *
         * @return the rendered document, or null
         */
        public String getRendered() {
            return rendered;
        }

        /**
         * Returns why the draft renders nothing.
         *
         * @return the complaint, or null when it renders
         */
        public String getError() {
            return error;
        }

        /**
         * Whether this draft is the one of a given template code.
         *
         * @param candidate the code of a card on the page
         * @return true when the draft belongs to that card
         */
        public boolean isFor(String candidate) {
            return card != null && card.equals(candidate);
        }

        /**
         * Whether this draft belongs to the creation card.
         *
         * @return true when the draft came from the new-template card
         */
        public boolean isNew() {
            return NEW_CARD.equals(card);
        }
    }

    /**
     * One administered template and what it renders on the demonstration
     * document — what the page draws, side by side.
     */
    public static class Preview {

        /** The administered template. */
        public final DocumentTemplate template;

        /** What it renders, or null when it renders nothing. */
        public final String rendered;

        /** What this card's "Partir de" picker offers, same type first. */
        public final List<Pick> picks;

        /** What a layout of THIS card's document may read. */
        public final List<DocumentTemplateService.Reference> references;

        /**
         * Builds a preview.
         *
         * @param template the administered template
         * @param rendered what it renders, or null
         * @param picks what the card's picker offers
         * @param references what a layout of this card's document may read
         */
        public Preview(DocumentTemplate template, String rendered, List<Pick> picks,
                List<DocumentTemplateService.Reference> references) {
            this.template = template;
            this.rendered = rendered;
            this.picks = picks;
            this.references = references;
        }

        /**
         * Returns what this card's picker offers.
         *
         * @return the picker entries, same type first
         */
        public List<Pick> getPicks() {
            return picks;
        }

        /**
         * Returns what a layout of this card's document may read.
         *
         * @return the references of the card's own type
         */
        public List<DocumentTemplateService.Reference> getReferences() {
            return references;
        }

        /**
         * Returns the administered template.
         *
         * @return the template
         */
        public DocumentTemplate getTemplate() {
            return template;
        }

        /**
         * Returns what the template renders on the demonstration document.
         *
         * @return the rendered document, or null when it renders nothing
         */
        public String getRendered() {
            return rendered;
        }

        /**
         * Tells whether the preview has something to show.
         *
         * @return true when the template rendered a document
         */
        public boolean isRendered() {
            return rendered != null;
        }
    }
}
