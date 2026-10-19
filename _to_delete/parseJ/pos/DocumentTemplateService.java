package com.intermarche.pos.service;

import com.intermarche.pos.domain.setting.DocumentTemplate;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Renders a printed document from its ADMINISTERED template (BO-03-03).
 *
 * <p>The register already runs Qute for every screen it draws, so the template
 * engine the questionnaire asks for did not have to be written: a document
 * layout is a Qute template like any other, stored in a row instead of a file
 * and edited in the back office.
 *
 * <p>THE CONTRACT, in one sentence: {@link #render} answers the laid-out
 * document, or NULL when the register must print it its own way. Null is
 * returned for a document type nobody administered, for a template whose source
 * is empty, and for a template that fails to parse or render — the last one
 * being the important case, because a paramétreur's typo must cost a fallback
 * to the built-in layout and never a sale that cannot be printed.
 *
 * <p>WHAT A TEMPLATE SEES: maps, lists and strings, handed over by the caller
 * and already formatted. Never an entity, never a service, never the database.
 * A template therefore lays out and nothing else — it cannot reach past what it
 * was given, and it cannot format an amount one way while the rest of the
 * register formats it another.
 *
 * <p>Parsed templates are cached per code, keyed on the source itself: the back
 * office rewrites a template far less often than the shop prints, and a source
 * that changed invalidates its own entry without anyone having to remember to
 * clear a cache.
 */
@ApplicationScoped
public class DocumentTemplateService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DocumentTemplateService.class);

    /** The Qute engine the whole register already runs on. */
    @Inject
    Engine engine;

    /** Parsed templates, keyed by template code. */
    private final Map<String, Parsed> cache = new HashMap<>();

    /**
     * Default constructor, used by CDI, which injects the engine.
     */
    public DocumentTemplateService() {
    }

    /**
     * Builds the renderer over a given engine.
     *
     * <p>For the callers that build this service by hand — the printers' unit
     * tests, which run a real Qute engine rather than a stand-in so that what
     * they assert is a layout and not a mock.
     *
     * @param engine the engine to render with
     */
    public DocumentTemplateService(Engine engine) {
        this.engine = engine;
    }

    /**
     * Renders the administered layout of a document type.
     *
     * @param type the document to lay out, or null
     * @param data the already-formatted values the template may read
     * @return the laid-out document, or null when the register must print it
     *         its own way
     */
    public String render(DocumentTemplate.DocumentType type, Map<String, Object> data) {
        LOGGER.info("Entering method render with type: " + type);
        // No guard on the lookup: a type nobody administers answers null, and
        // rendering null answers null too — the same fallback, said once.
        String rendered = renderTemplate(DocumentTemplate.findFor(type), data);
        LOGGER.info("Exiting method render");
        return rendered;
    }

    /**
     * Renders ONE template against the given values, whatever the referential
     * says — what the back office's preview pane needs.
     *
     * @param template the template to render, or null
     * @param data the already-formatted values the template may read
     * @return the laid-out document, or null when the template cannot render
     */
    public String renderTemplate(DocumentTemplate template, Map<String, Object> data) {
        if (template == null || !template.isRenderable()) {
            return null;
        }
        Template parsed = parse(template);
        if (parsed == null) {
            return null;
        }
        try {
            // Null values and empty values are the same thing to the engine
            // (both raise on a template that reads something), and the catch
            // below answers for both: a guard here would never be observed.
            return parsed.data(withLayout(template, data)).render();
        } catch (RuntimeException e) {
            // A paramétreur's mistake costs a fallback to the built-in layout,
            // never a sale that cannot be printed.
            LOGGER.errorf(e, "Gabarit %s non rendu, mise en page interne utilisée", template.code);
            return null;
        }
    }

    /**
     * Hands the template its typesetting primitives and its paper width.
     *
     * <p>{@code fmt} carries the alignment, centring and wrapping the register
     * uses for its own layouts, already bound to THIS template's width — so a
     * template writing {@code {fmt.line("TOTAL TTC", totals.includingTax)}}
     * produces the very characters the built-in receipt does, instead of
     * padding with spaces its author counted by hand. {@code width} is exposed
     * beside it for a template that needs the number itself.
     *
     * <p>Until now the administered width decided nothing: it was validated,
     * stored, distributed by the referential pull, and read by no renderer.
     *
     * <p>The caller's map is COPIED, never enriched in place: the values a
     * printer assembles for one document must not grow a key because they were
     * passed through here.
     *
     * @param template the template being rendered, for its width
     * @param data the values the caller assembled, possibly null
     * @return the values plus the layout primitives
     */
    private Map<String, Object> withLayout(DocumentTemplate template, Map<String, Object> data) {
        Map<String, Object> enriched =
                data == null ? new java.util.HashMap<>() : new java.util.HashMap<>(data);
        enriched.put("fmt", new DocumentLayout(template.width));
        enriched.put("width", template.width);
        return enriched;
    }

    /** How deep a zone may include another before the nesting is called a loop. */
    private static final int MAX_ZONE_DEPTH = 5;

    /** The placeholder a document writes to pull a zone in (BO-03-04-03). */
    private static final java.util.regex.Pattern ZONE =
            java.util.regex.Pattern.compile("\\[\\[ZONE\\s+([A-Za-z0-9_.-]+)\\s*\\]\\]");

    /**
     * Replaces every {@code [[ZONE <code>]]} of a source by the zone's own
     * source, before anything is parsed (BO-03-04-03, -04 and -07).
     *
     * <p>Said at PARSE TIME and not at render time, for two reasons: the
     * compilation check the back office runs at save then sees the document the
     * printer will see, and the composed form is cached like any other, so a
     * document made of six zones costs one parse and not seven.
     *
     * <p>A zone may name another zone, to a bounded depth: beyond it the text
     * is left as it stands rather than expanded further, so a zone that names
     * itself prints its placeholder instead of hanging the register.
     *
     * <p>A zone NOBODY ADMINISTERS expands to nothing. A document whose zone was
     * deleted prints without it, which is what a shop removing a zone asked
     * for; printing the placeholder on the customer's paper would be worse.
     *
     * @param source the source as the paramétreur typed it, possibly null
     * @return the source with its zones in place, never null for a non-null input
     */
    String expandZones(String source) {
        String expanded = source;
        for (int depth = 0; depth < MAX_ZONE_DEPTH; depth++) {
            if (expanded == null || !expanded.contains("[[ZONE")) {
                return expanded;
            }
            java.util.regex.Matcher matcher = ZONE.matcher(expanded);
            StringBuilder out = new StringBuilder();
            boolean replaced = false;
            while (matcher.find()) {
                DocumentTemplate zone = DocumentTemplate.findByCode(matcher.group(1));
                String body = zone == null || zone.source == null ? "" : zone.source;
                matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(body));
                replaced = true;
            }
            matcher.appendTail(out);
            if (!replaced) {
                return expanded;
            }
            expanded = out.toString();
        }
        return expanded;
    }

    /**
     * Parses a template, reusing the parsed form while its source is unchanged.
     *
     * @param template the administered template
     * @return the parsed template, or null when the source does not parse
     */
    private Template parse(DocumentTemplate template) {
        synchronized (cache) {
            Parsed cached = cache.get(template.code);
            if (cached != null && cached.source.equals(template.source)) {
                return cached.template;
            }
            try {
                Template compiled = engine.parse(expandZones(template.source));
                cache.put(template.code, new Parsed(template.source, compiled));
                return compiled;
            } catch (RuntimeException e) {
                // The stale entry is left alone on purpose: it is keyed on the
                // source it was parsed from, so the next call reparses whatever
                // the back office has typed since. Removing it would be a line
                // no behaviour could ever tell apart.
                LOGGER.errorf(e, "Gabarit %s illisible, mise en page interne utilisée",
                        template.code);
                return null;
            }
        }
    }

    /**
     * Tells whether a source parses, which is what the back office checks before
     * saving a template (BO-03-03).
     *
     * @param source the Qute source the paramétreur typed, or null
     * @return null when the source parses, the parser's complaint otherwise
     */
    public String parseError(String source) {
        LOGGER.info("Entering method parseError");
        // A blank source parses happily, so only the null one is short-circuited
        // here — and it is, because the engine would raise on it.
        if (source == null) {
            LOGGER.info("Exiting method parseError");
            return null;
        }
        try {
            engine.parse(expandZones(source));
            LOGGER.info("Exiting method parseError");
            return null;
        } catch (RuntimeException e) {
            LOGGER.info("Exiting method parseError");
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    /**
     * Returns a DEMONSTRATION document of the given type, in the exact shape the
     * register hands a template at printing time (BO-03-03).
     *
     * <p>This method IS the contract between the editor and the printers: the
     * preview pane renders against it, and every reader builds the same shape
     * from a real sale. A key that is not here is a key no template may read.
     *
     * @param type the document to describe, or null
     * @return the demonstration values, never null
     */
    public Map<String, Object> sampleData(DocumentTemplate.DocumentType type) {
        LOGGER.info("Entering method sampleData with type: " + type);
        Map<String, Object> data = new HashMap<>();
        data.put("store", Map.of(
                "name", "INTERMARCHE VAUCRESSON",
                "street", "15 RUE DE LA GARE",
                "postalCode", "92420",
                "city", "VAUCRESSON",
                "siret", "12345678900012"));
        data.put("terminal", "C04");
        data.put("operator", "MARIE");
        data.put("date", "15/09/2026");
        data.put("time", "11:24");
        if (type == null) {
            LOGGER.info("Exiting method sampleData");
            return data;
        }
        switch (type) {
            case SALE_RECEIPT, TICKET_HEADER, TICKET_FOOTER, FRAGMENT -> {
                data.put("document", Map.of("number", "C04-000123", "duplicate", false,
                        "duplicateNumber", "0", "digitalPath", "/t/42/3f9a1c"));
                data.put("lines", sampleSaleLines());
                data.put("totals", Map.of("excludingTax", "4,36", "vat", "0,24",
                        "includingTax", "4,60", "discount", "",
                        "immediateDiscount", "0,20"));
                data.put("vatRows", List.of(
                        Map.of("rate", "5,50%", "base", "4,36", "vat", "0,24")));
                data.put("payments", List.of(
                        Map.of("label", "ESPECES", "key", "CASH", "amount", "4,60")));
                data.put("fidelity", sampleFidelity());
            }
            case REFUND_RECEIPT -> {
                // The refund carries NEITHER payments nor a loyalty zone, and
                // its totals hold the one figure a return has: what was given
                // back. Advertising the receipt's keys here would promise a
                // paramétreur values that render empty on the paper.
                data.put("document", Map.of("number", "C04-R000012",
                        "originalNumber", "C04-000123", "method", "ESPECES",
                        "returnReason", "ARTICLE ABIME"));
                data.put("lines", List.of(
                        Map.of("label", "LAIT DEMI-ECREME 1L", "quantity", "1",
                                "unitPrice", "1,15", "total", "1,15", "vatRate", "0.055")));
                data.put("totals", Map.of("includingTax", "1,15"));
                data.put("vatRows", List.of(
                        Map.of("rate", "5,50%", "base", "1,09", "vat", "0,06")));
                data.put("recalculation", Map.of("status", "OK", "withdrawn", "0,28",
                        "keptAdvantages", "SOCLE", "loyaltySent", "1"));
            }
            case INVOICE, INVOICE_A4, INVOICE_HEADER, INVOICE_FOOTER,
                    FS, FT, NC, RC -> {
                // The invoice names a seller and an addressee the receipt never
                // carries, states its own kind of document, and describes its
                // articles with BOTH unit prices. Its store block is the SELLER
                // as a party and not the receipt's postal block, so it is
                // replaced here rather than inherited.
                data.put("store", sampleSeller());
                data.put("seller", sampleSeller());
                data.put("customer", sampleCustomer());
                data.put("date", "15/09/2026");
                data.put("time", "");
                data.put("operator", "");
                data.put("document", sampleInvoiceDocument());
                data.put("lines", sampleInvoiceLines());
                data.put("vatRows", List.of(
                        Map.of("rate", "5,50 %", "base", "4,36", "vat", "0,24",
                                "includingTax", "4,60")));
                data.put("payments", List.of(
                        Map.of("label", "ESPECES", "amount", "4,60")));
                data.put("totals", Map.of("excludingTax", "4,36", "vat", "0,24",
                        "includingTax", "4,60", "methods", "ESPECES"));
            }
            case X_REPORT, Z_REPORT -> {
                data.put("session", Map.of("number", "C04-S00012",
                        "openedAt", "15/09/2026 08:02", "closedAt", "15/09/2026 20:14",
                        "closing", type == DocumentTemplate.DocumentType.Z_REPORT));
                data.put("totals", Map.ofEntries(
                        Map.entry("ticketCount", "128"),
                        Map.entry("includingTax", "3412,55"),
                        Map.entry("refunds", "24,90"),
                        Map.entry("theoreticalCash", "1204,30"),
                        Map.entry("declaredCash", "1204,30"),
                        Map.entry("declaredVariance", "0,00"),
                        Map.entry("openingFloat", "100,00"),
                        Map.entry("countedCash", "1204,30"),
                        Map.entry("variance", "0,00"),
                        Map.entry("withdrawn", "1104,30"),
                        Map.entry("otherTenders", "0,00"),
                        Map.entry("bankDeposit", "2258,25")));
                data.put("tenders", List.of(
                        Map.of("label", "CASH", "amount", "1154,30"),
                        Map.of("label", "CARD", "amount", "2258,25")));
            }
            case WITHDRAWAL_TICKET, TRANSFER_TICKET -> {
                boolean transfer = type == DocumentTemplate.DocumentType.TRANSFER_TICKET;
                data.put("movement", Map.of(
                        "label", transfer ? "TRANSFERT REGLEMENT" : "PRELEVEMENT",
                        "tender", transfer ? "" : "CASH",
                        "from", transfer ? "CASH" : "",
                        "to", transfer ? "CHEQUE" : "",
                        "amount", "500,00"));
                data.put("counts", transfer ? List.of() : List.of(
                        Map.of("label", "Billets 50", "amount", "8"),
                        Map.of("label", "Billets 20", "amount", "5")));
            }
            case GIFT_CARD_VOUCHER, CREDIT_NOTE_VOUCHER -> {
                boolean giftCard = type == DocumentTemplate.DocumentType.GIFT_CARD_VOUCHER;
                String number = giftCard ? "296000000000042" : "297000000000042";
                data.put("instrument", Map.of(
                        "number", number,
                        "amount", "30,00",
                        // BO-03-03-08: the end of validity the registry holds,
                        // and the barcode as the DIRECTIVE the bridge encodes —
                        // the two values the credit note was missing.
                        "validUntil", giftCard ? "" : "15/03/2027",
                        "barcode", "[[BARCODE " + number + "]]"));
            }
            case CARD_RECEIPT -> data.put("card", Map.of(
                    "kind", "CREDIT", "amount", "4,60", "frame", ""));
            case LOYALTY_RECEIPT -> {
                data.put("document", Map.of("number", "C04-000123"));
                data.put("fidelity", sampleFidelity());
                data.put("settlement", Map.of("amount", "3,00"));
            }
            case EXIT_TICKET -> {
                // BO-03-03-36: the exit ticket states a total, a count and the
                // dearest articles — and the barcode as the DIRECTIVE the
                // bridge encodes, like the credit note's.
                data.put("document", Map.of("number", "C04-000123",
                        "barcode", "[[BARCODE C04-000123]]"));
                data.put("totals", Map.of("includingTax", "4,60"));
                data.put("articleCount", "3");
                data.put("topLines", List.of(
                        Map.of("label", "COTE DE BOEUF", "quantity", "1",
                                "unitPrice", "18,90", "total", "18,90"),
                        Map.of("label", "LAIT DEMI-ECREME 1L", "quantity", "6",
                                "unitPrice", "1,15", "total", "6,90")));
            }
            case PARKED_TICKET_RESUME -> {
                // BO-03-03-07: the two numbers, which is what the document is
                // for — on an inter-till resume they differ.
                data.put("document", Map.of("number", "C04-000124",
                        "originalNumber", "C03-000087"));
            }
        }
        LOGGER.info("Exiting method sampleData");
        return data;
    }

    /**
     * The LOYALTY ZONE of the demonstration document (BO-03-03-25, -29, -30,
     * -31, -32).
     *
     * <p>Built once and shared by the receipt and the loyalty slip, because the
     * two show the same zone: a paramétreur who learns it on one knows it on the
     * other, and a key that drifted between them would be a key the preview
     * lies about.
     *
     * @return the demonstration loyalty values
     */
    private Map<String, Object> sampleFidelity() {
        // A LinkedHashMap and no longer Map.of: the grouping of imfid response
        // A and the balances of responses B and C take the zone past the ten
        // pairs the factory accepts.
        Map<String, Object> fidelity = new java.util.LinkedHashMap<>();
        fidelity.put("card", "6045200000123");
        fidelity.put("present", true);
        fidelity.put("earnTotal", "1,03");
        fidelity.put("lines", List.of(
                sampleFidelityLine("SOCLE", "Cagnotte socle", "0,28",
                        "REMISE", "Remises immediates", "10"),
                sampleFidelityLine("F&L", "Fruits & legumes", "0,75",
                        "CAGNOTTE", "Cagnotte", "20")));
        fidelity.put("availableBalance", "42,30");
        fidelity.put("projectedAfterEarn", "43,33");
        fidelity.put("projectedLabel", "SOLDE PREVISIONNEL");
        fidelity.put("balanceAfter", "40,33");
        fidelity.put("anonymousProjection", false);
        fidelity.put("usedAmount", "3,00");
        fidelity.put("unavailable", false);
        fidelity.put("message", "");
        return fidelity;
    }

    /**
     * One advantage line of the demonstration loyalty zone, with the grouping
     * the programme sends with it (imfid response A, BO-03-03-25, BO-03-03-33).
     *
     * @param ruleCode the rule that granted the advantage
     * @param label the wording the programme sent
     * @param amount the advantage, already written
     * @param advantageType the advantage type's code
     * @param advantageTypeLabel the advantage type's wording
     * @param advantageTypeOrder the advantage type's print rank, written out
     * @return the demonstration line
     */
    private Map<String, Object> sampleFidelityLine(String ruleCode, String label,
            String amount, String advantageType, String advantageTypeLabel,
            String advantageTypeOrder) {
        Map<String, Object> line = new java.util.LinkedHashMap<>();
        line.put("ruleCode", ruleCode);
        line.put("label", label);
        line.put("amount", amount);
        line.put("advantageType", advantageType);
        line.put("advantageTypeLabel", advantageTypeLabel);
        line.put("advantageTypeOrder", advantageTypeOrder);
        line.put("advantageCategory", advantageType);
        line.put("advantageCategoryLabel", advantageTypeLabel);
        return line;
    }

    /**
     * The ARTICLE ROWS of the demonstration receipt (BO-03-03-03,
     * BO-03-03-22).
     *
     * <p>Both unit prices and the line's own modification are in them, because
     * the register hands both: a layout can print the tax-excluded price per
     * line and name the rebate under the article.
     *
     * @return the demonstration article rows
     */
    private List<Map<String, Object>> sampleSaleLines() {
        return List.of(
                sampleSaleLine("LAIT DEMI-ECREME 1L", "3250390000013", "CREMERIE",
                        "2", "1,15", "1,09", "2,30", "", ""),
                sampleSaleLine("PAIN COMPLET 500G", "3250390000020", "BOULANGERIE",
                        "1", "2,30", "2,18", "2,10", "Remise -0,20\u20ac", "0,20"));
    }

    /**
     * One article row of the demonstration receipt.
     *
     * @param label the article's wording
     * @param ean the article's code
     * @param family the article's family
     * @param quantity the quantity sold, already written
     * @param unitPrice the tax-included unit price, already written
     * @param unitExcludingTax the tax-excluded unit price, already written
     * @param total the line total, already written
     * @param discountLabel the wording of the line's modification, empty for none
     * @param discount what that modification took off, empty for none
     * @return the demonstration row
     */
    private Map<String, Object> sampleSaleLine(String label, String ean, String family,
            String quantity, String unitPrice, String unitExcludingTax, String total,
            String discountLabel, String discount) {
        Map<String, Object> line = new java.util.LinkedHashMap<>();
        line.put("label", label);
        line.put("ean", ean);
        line.put("family", family);
        line.put("unit", "");
        line.put("quantity", quantity);
        line.put("unitPrice", unitPrice);
        line.put("unitExcludingTax", unitExcludingTax);
        line.put("total", total);
        line.put("vatRate", "0.055");
        line.put("discountLabel", discountLabel);
        line.put("discount", discount);
        return line;
    }

    /**
     * The ARTICLE ROWS of the demonstration invoice (BO-03-03-03).
     *
     * <p>Not the receipt's: an invoice row names the article's attributes and
     * both unit prices, and carries neither family nor unit.
     *
     * @return the demonstration invoice rows
     */
    private List<Map<String, Object>> sampleInvoiceLines() {
        return List.of(
                Map.of("label", "LAIT DEMI-ECREME 1L", "attributes", "Famille : CREMERIE",
                        "ean", "3250390000013", "quantity", "2", "vatRate", "5,50 %",
                        "unitExcludingTax", "1,0900", "unitIncludingTax", "1,15",
                        "total", "2,30"),
                Map.of("label", "PAIN COMPLET 500G", "attributes", "Famille : BOULANGERIE",
                        "ean", "3250390000020", "quantity", "1", "vatRate", "5,50 %",
                        "unitExcludingTax", "2,1800", "unitIncludingTax", "2,30",
                        "total", "2,30"));
    }

    /**
     * The DOCUMENT block of the demonstration invoice (BO-03-03-02).
     *
     * @return the demonstration values, the due date of BO-02-04-08 included
     */
    private Map<String, Object> sampleInvoiceDocument() {
        Map<String, Object> document = new java.util.LinkedHashMap<>();
        document.put("title", "FACTURE");
        document.put("number", "C04-F000042");
        document.put("issueDate", "15/09/2026");
        document.put("saleDate", "15/09/2026");
        document.put("ticketNumber", "C04-000123");
        document.put("duplicate", false);
        document.put("duplicateNumber", "0");
        document.put("dueDate", "15/10/2026");
        document.put("legalFooter", "");
        document.put("legalMentions", "");
        document.put("logo", "");
        return document;
    }

    /**
     * The SELLER of the demonstration invoice, as a party.
     *
     * @return the demonstration seller
     */
    private Map<String, Object> sampleSeller() {
        return Map.of("name", "INTERMARCHE VAUCRESSON",
                "legalName", "ITM VAUCRESSON SAS", "contact", "",
                "addressLines", List.of("15 RUE DE LA GARE", "92420 VAUCRESSON"),
                "phone", "", "fax", "", "siret", "12345678900012",
                "vatNumber", "FR00123456789", "accountNumber", "");
    }

    /**
     * The ADDRESSEE of the demonstration invoice, as a party.
     *
     * @return the demonstration customer, carrying the tax id only an addressee
     *         has (BO-10-04-15/-16)
     */
    private Map<String, Object> sampleCustomer() {
        return Map.of("name", "SARL DUPONT",
                "legalName", "SARL DUPONT", "contact", "",
                "addressLines", List.of("3 RUE DES LILAS", "92100 BOULOGNE"),
                "phone", "", "fax", "", "siret", "98765432100011",
                "vatNumber", "", "accountNumber", "C000123",
                "taxId", "");
    }

    /**
     * One value a template of a given document type may read, as the screen
     * shows it to the paramétreur.
     */
    public static class Reference {

        /** What to type in the layout, braces included. */
        public final String expression;

        /** What that expression yields on the demonstration document. */
        public final String sample;

        /** The loop this expression only reads inside, or null. */
        public final String loop;

        /**
         * Builds a reference.
         *
         * @param expression what to type, braces included
         * @param sample what it yields on the demonstration document
         * @param loop the loop it only reads inside, or null
         */
        public Reference(String expression, String sample, String loop) {
            this.expression = expression;
            this.sample = sample;
            this.loop = loop;
        }

        /**
         * Returns what to type in the layout.
         *
         * @return the expression, braces included
         */
        public String getExpression() {
            return expression;
        }

        /**
         * Returns what the expression yields on the demonstration document.
         *
         * @return the sample value
         */
        public String getSample() {
            return sample;
        }

        /**
         * Returns the loop this expression only reads inside.
         *
         * @return the loop expression, or null outside any loop
         */
        public String getLoop() {
            return loop;
        }

        /**
         * Whether this expression only means something inside a loop.
         *
         * @return true when it belongs to a repeating block
         */
        public boolean isRepeating() {
            return loop != null;
        }
    }

    /**
     * Everything a template of this document type may read, each with what it
     * yields on the demonstration document (BO-03-03).
     * <p>
     * DERIVED FROM {@link #sampleData}, never written beside it. The sample is
     * already the contract — "a key that is not here is a key no template may
     * read" — so listing the keys by hand would create a second list free to
     * drift from the first, and a paramétreur would be told about a value that
     * renders empty. Walking the sample means the screen cannot describe a key
     * the renderer does not serve, nor miss one it does.
     * <p>
     * Order is meaning, not the alphabet: the values that stand alone first,
     * then the blocks, then the repeating rows — which is the order a document
     * is written in, from its header down to its lines.
     *
     * @param type the document to describe, or null for the common values
     * @return the readable expressions, never null
     */
    public List<Reference> references(DocumentTemplate.DocumentType type) {
        LOGGER.info("Entering method references with type: " + type);
        Map<String, Object> sample = sampleData(type);
        List<Reference> scalars = new ArrayList<>();
        List<Reference> blocks = new ArrayList<>();
        List<Reference> rows = new ArrayList<>();
        for (String key : new TreeSet<>(sample.keySet())) {
            Object value = sample.get(key);
            if (value instanceof Map<?, ?> block) {
                for (Object field : new TreeSet<>(stringKeys(block))) {
                    blocks.add(new Reference("{" + key + "." + field + "}",
                            text(block.get(field)), null));
                }
            } else if (value instanceof List<?> list) {
                rows.addAll(listReferences(key, list));
            } else {
                scalars.add(new Reference("{" + key + "}", text(value), null));
            }
        }
        List<Reference> all = new ArrayList<>(scalars);
        all.addAll(blocks);
        all.addAll(rows);
        LOGGER.info("Exiting method references");
        return all;
    }

    /**
     * The expressions of a repeating block: its loop, then one per field of the
     * rows it carries.
     * <p>
     * The first row names the fields, because the sample builds every row of a
     * list the same way — a list whose rows differed would be a sample that
     * lies about what a template can read.
     *
     * @param key the name of the list
     * @param list the demonstration rows
     * @return the loop and its fields, or the loop alone for an empty list
     */
    private List<Reference> listReferences(String key, List<?> list) {
        String item = singular(key);
        String loop = "{#for " + item + " in " + key + "}…{/for}";
        List<Reference> found = new ArrayList<>();
        found.add(new Reference(loop, list.size() + " ligne(s)", null));
        if (list.isEmpty()) {
            return found;
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> row)) {
            found.add(new Reference("{" + item + "}", text(first), loop));
            return found;
        }
        for (Object field : new TreeSet<>(stringKeys(row))) {
            found.add(new Reference("{" + item + "." + field + "}",
                    text(row.get(field)), loop));
        }
        return found;
    }

    /**
     * The loop variable a list is read through: its name without the plural s,
     * which is what a paramétreur would have written anyway.
     *
     * @param key the list name
     * @return the loop variable name
     */
    private String singular(String key) {
        return key.endsWith("s") && key.length() > 1
                ? key.substring(0, key.length() - 1) : key + "Item";
    }

    /**
     * The keys of a demonstration map, as strings.
     *
     * @param map the map
     * @return its keys written out
     */
    private List<String> stringKeys(Map<?, ?> map) {
        List<String> keys = new ArrayList<>();
        for (Object key : map.keySet()) {
            keys.add(String.valueOf(key));
        }
        return keys;
    }

    /**
     * Writes a demonstration value the way the screen shows it.
     *
     * @param value the value, possibly null or a nested list
     * @return the value written out, never null
     */
    private String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            return String.join(" / ", list.stream().map(String::valueOf).toList());
        }
        return String.valueOf(value);
    }

    /**
     * A ready-made STARTER layout for one kind of document.
     * <p>
     * The editor used to open on an empty box, which meant the operator had to
     * already know the shape of the data — that {@code lines} is a list, that a
     * line names {@code label} and {@code total}, that the totals hang under
     * {@code totals}. Nobody knows that from the screen. The example is written
     * against the SAME keys {@link #sampleData} publishes, so the two describe
     * one contract rather than two, and a starter that renders here renders on
     * the register.
     * <p>
     * It is a starting point, not a house layout: the register keeps printing
     * its own way until a row is administered, and the operator is expected to
     * cut this one down.
     *
     * @param type the kind of document, possibly null
     * @return the starter source, never null but empty for an unknown kind
     */
    public String defaultSource(DocumentTemplate.DocumentType type) {
        LOGGER.info("Entering method defaultSource with type: " + type);
        String source = starter(type);
        LOGGER.info("Exiting method defaultSource");
        return source;
    }

    /**
     * The starter layout of each kind, kept apart so the public method stays
     * one statement and the switch stays readable.
     *
     * @param type the kind of document, possibly null
     * @return the starter source, empty for an unknown kind
     */
    private String starter(DocumentTemplate.DocumentType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case SALE_RECEIPT -> """
                    {store.name}
                    {store.street}
                    {store.postalCode} {store.city}
                    ---
                    Caisse {terminal}  {date} {time}
                    Ticket {document.number}
                    ---
                    {#for line in lines}
                    {line.label}
                      {line.quantity} x {line.unitPrice}   {line.total}
                    {/for}
                    ---
                    TOTAL HT   {totals.excludingTax}
                    TVA        {totals.vat}
                    TOTAL TTC  {totals.includingTax}
                    ---
                    {#for payment in payments}
                    {payment.label}   {payment.amount}
                    {/for}
                    ---
                    Merci de votre visite
                    """;
            case REFUND_RECEIPT -> """
                    {store.name}
                    ---
                    TICKET DE REMBOURSEMENT
                    Caisse {terminal}  {date} {time}
                    Avoir {document.number}
                    Ticket d'origine {document.originalNumber}
                    ---
                    {#for line in lines}
                    {line.label}
                      {line.quantity} x {line.unitPrice}   {line.total}
                    {/for}
                    ---
                    TOTAL REMBOURSE  {totals.includingTax}
                    Mode {document.method}
                    """;
            case INVOICE -> """
                    {store.name}
                    ---
                    {document.title} {document.number}
                    Du {document.issueDate}
                    Ticket {document.ticketNumber}
                    ---
                    Client {customer.name}
                    {#for address in customer.addressLines}
                    {address}
                    {/for}
                    Echeance {document.dueDate}
                    ---
                    {#for line in lines}
                    {line.label}
                      {line.quantity} x {line.unitIncludingTax}   {line.total}
                    {/for}
                    ---
                    TOTAL HT   {totals.excludingTax}
                    TVA        {totals.vat}
                    TOTAL TTC  {totals.includingTax}
                    """;
            case INVOICE_A4 -> """
                    <!DOCTYPE html>
                    <html lang="fr"><head><meta charset="UTF-8">
                    <title>{document.title} {document.number}</title>
                    <style>
                      body { font: 12px/1.5 system-ui, sans-serif; margin: 40px; }
                      table { border-collapse: collapse; width: 100%; margin-top: 18px; }
                      th, td { border-bottom: 1px solid #ddd; padding: 6px 8px; text-align: left; }
                      td.num, th.num { text-align: right; }
                      .parties { display: flex; gap: 60px; margin-top: 24px; }
                    </style></head><body>
                    <h1>{document.title} {document.number}</h1>
                    <p>Du {document.issueDate} — ticket {document.ticketNumber}
                       — echeance {document.dueDate}</p>
                    <div class="parties">
                      <div><b>{seller.legalName}</b>
                        {#for address in seller.addressLines}<br>{address}{/for}
                        <br>SIRET {seller.siret}<br>TVA {seller.vatNumber}</div>
                      <div><b>{customer.name}</b>
                        {#for address in customer.addressLines}<br>{address}{/for}</div>
                    </div>
                    <table>
                      <tr><th>Article</th><th class="num">Qte</th>
                          <th class="num">PU</th><th class="num">Total</th></tr>
                      {#for line in lines}
                      <tr><td>{line.label}</td><td class="num">{line.quantity}</td>
                          <td class="num">{line.unitIncludingTax}</td>
                          <td class="num">{line.total}</td></tr>
                      {/for}
                    </table>
                    <p>Total HT {totals.excludingTax} — TVA {totals.vat}
                       — <b>Total TTC {totals.includingTax}</b></p>
                    </body></html>
                    """;
            case X_REPORT, Z_REPORT -> """
                    {store.name}
                    ---
                    RAPPORT DE SESSION
                    Session {session.number}
                    Ouverte {session.openedAt}
                    Caisse {terminal}  {date} {time}
                    ---
                    Tickets          {totals.ticketCount}
                    Chiffre TTC      {totals.includingTax}
                    Remboursements   {totals.refunds}
                    ---
                    {#for tender in tenders}
                    {tender.label}   {tender.amount}
                    {/for}
                    ---
                    Fond de caisse   {totals.openingFloat}
                    Especes theorique {totals.theoreticalCash}
                    Especes comptees  {totals.countedCash}
                    Ecart             {totals.variance}
                    """;
            case WITHDRAWAL_TICKET, TRANSFER_TICKET -> """
                    {store.name}
                    ---
                    {movement.label}
                    Caisse {terminal}  {date} {time}
                    Operateur {operator}
                    ---
                    Montant {movement.amount}
                    ---
                    {#for count in counts}
                    {count.label}   {count.amount}
                    {/for}
                    ---
                    Signature
                    """;
            case GIFT_CARD_VOUCHER, CREDIT_NOTE_VOUCHER -> """
                    {store.name}
                    ---
                    BON D'ACHAT
                    Numero {instrument.number}
                    Montant {instrument.amount}
                    Emis le {date} a {time}
                    ---
                    A presenter en caisse
                    """;
            case CARD_RECEIPT -> """
                    {store.name}
                    ---
                    JUSTIFICATIF CARTE
                    Caisse {terminal}  {date} {time}
                    Type {card.kind}
                    Montant {card.amount}
                    ---
                    A conserver
                    """;
            case FRAGMENT -> """
                    {fmt.rule()}
                    {fmt.line("TOTAL HT", totals.excludingTax)}
                    {fmt.line("TVA", totals.vat)}
                    {fmt.line("TOTAL TTC", totals.includingTax)}
                    """;
            case FS, FT, NC, RC -> """
                    {fmt.centre(store.name)}
                    {fmt.centre(store.city)}
                    {fmt.rule()}
                    {fmt.centre(document.title)}
                    {fmt.centre(document.number)}
                    {fmt.rule()}
                    {#for line in lines}
                    {line.label}
                      {line.quantity} x {line.unitPrice}   {line.total}
                    {/for}
                    {fmt.rule()}
                    {fmt.line("TOTAL", totals.includingTax)}
                    """;
            case TICKET_HEADER -> """
                    {fmt.centre("BIENVENUE")}
                    {fmt.centre(store.name)}
                    {fmt.centre(store.city)}
                    """;
            case TICKET_FOOTER -> """
                    {fmt.centre("MERCI DE VOTRE VISITE")}
                    {fmt.centre("A BIENTOT")}
                    {fmt.right(store.siret)}
                    """;
            case INVOICE_HEADER -> """
                    {fmt.centre(store.name)}
                    {fmt.centre("SERVICE FACTURATION")}
                    """;
            case INVOICE_FOOTER -> """
                    {fmt.centre("Document a conserver")}
                    {fmt.right(store.siret)}
                    """;
            case EXIT_TICKET -> """
                    {fmt.centre("TICKET DE SORTIE")}
                    {fmt.rule()}
                    {fmt.line("TOTAL TTC", totals.includingTax)}
                    {fmt.line("NOMBRE D'ARTICLES", articleCount)}
                    {fmt.rule()}
                    {#for line in topLines}
                    {fmt.line(line.label, line.total)}
                    {/for}
                    {fmt.rule()}
                    {fmt.centre(document.number)}
                    {document.barcode}
                    """;
            case PARKED_TICKET_RESUME -> """
                    {fmt.centre("TICKET DE REPRISE")}
                    {fmt.rule()}
                    {fmt.line("DATE", date)}
                    {fmt.line("TICKET", document.number)}
                    {fmt.line("TICKET D'ORIGINE", document.originalNumber)}
                    {fmt.rule()}
                    """;
            case LOYALTY_RECEIPT -> """
                    {store.name}
                    ---
                    PAIEMENT FIDELITE
                    Caisse {terminal}  {date} {time}
                    Ticket {document.number}
                    ---
                    PAIEMENT TOTAL FID  {settlement.amount}
                    CARTE {fidelity.card}
                    SOLDE AVANT {fidelity.availableBalance}
                    ---
                    Signature du client


                    """;
        };
    }

    /**
     * Forgets every parsed template, so the next print reparses.
     *
     * <p>Needed by the referential pull, which rewrites the rows underneath this
     * service without going through the back-office screen.
     */
    public void clearCache() {
        LOGGER.info("Entering method clearCache");
        synchronized (cache) {
            cache.clear();
        }
        LOGGER.info("Exiting method clearCache");
    }

    /**
     * One parsed template and the source it was parsed from.
     */
    private static final class Parsed {

        /** The source this entry was parsed from. */
        private final String source;

        /** The parsed template. */
        private final Template template;

        /**
         * Builds a cache entry.
         *
         * @param source the source the template was parsed from
         * @param template the parsed template
         */
        private Parsed(String source, Template template) {
            this.source = source;
            this.template = template;
        }
    }
}
