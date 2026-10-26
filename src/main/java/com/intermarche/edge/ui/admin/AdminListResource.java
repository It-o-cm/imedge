package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.PosList;
import com.intermarche.edge.domain.setting.PosListItem;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * The ADMINISTERED LISTS screen ({@code BO-04-02-07}, {@code BO-04-03-12}).
 *
 * <p>Four things the cashier taps were administered as a SETTING holding a
 * semicolon-separated string: the service-request reasons, the expense
 * justifications, the return types and the tare containers. A string draws
 * four keys and does nothing else — it carries no code for the back office to
 * file a call under, no severity for it to sort them by, and no grouping, so
 * the same motives could not serve two gestures under one heading.
 *
 * <p>This screen administers them as lists: a list has a code, a heading, a
 * kind that says what it drives and, for a justification list, the register
 * functions it serves. Its entries carry a code, a label, a rank and — by
 * kind — a severity, a weight or option markers.
 *
 * <p>REPRENDRE LES VALEURS ACTUELLES does the migration in one gesture: it
 * reads the four settings as they stand and writes the lists they describe,
 * creating only the ones that do not exist yet. Until a list of a kind
 * exists, the registers keep reading the setting, so nothing changes under a
 * shop that never comes here.
 */
@ApplicationScoped
@Path("/admin/lists")
public class AdminListResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminListResource.class);

    /** The longest code this screen accepts, in characters. */
    static final int MAX_CODE = 40;

    /** The lists template. */
    @Inject
    @Location("admin-lists")
    Template adminLists;

    /** The administered parameters, read by the migration gesture. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * One administered list as the screen draws it, entries included.
     */
    public static class ListRow {

        /** The list's database id. */
        public Long id;

        /** The list code. */
        public String code;

        /** The heading. */
        public String label;

        /** What the list drives, as the enumeration names it. */
        public String kind;

        /** What the kind is called on screen. */
        public String kindLabel;

        /** The functions it serves, or an empty string for all of them. */
        public String functions;

        /** Whether the list is offered at all. */
        public boolean active;

        /** Whether the kind reads the severity column. */
        public boolean usesSeverity;

        /** Whether the kind reads the weight column. */
        public boolean usesWeight;

        /** Whether the kind reads the option markers. */
        public boolean usesOptions;

        /** The bundle key of the value column's heading, by kind (BO-03-02-47, BO-03-07-03). */
        public String valueLabelKey;

        /** The bundle key of the option markers' hint, by kind. */
        public String optionsHintKey;

        /** The entries, in display order. */
        public List<ItemRow> items = new java.util.ArrayList<>();
    }

    /**
     * One entry of a list as the screen draws it.
     */
    public static class ItemRow {

        /** The entry's database id. */
        public Long id;

        /** The entry code. */
        public String code;

        /** The label the cashier reads. */
        public String label;

        /** The display rank. */
        public int displayOrder;

        /** The severity, zero when the kind does not use it. */
        public int severity;

        /** The weight in kilograms, or an empty string. */
        public String numericValue;

        /** The option markers, or an empty string. */
        public String options;

        /** Whether the entry is offered. */
        public boolean active;
    }

    /**
     * Lists the administered lists and their entries.
     *
     * <p>Projected onto rows inside the transaction rather than handed to the
     * template as entities: the entries are a lazy collection and the page is
     * rendered once the method has returned.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the lists page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.POS_LIST)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list");
        List<ListRow> rows = new java.util.ArrayList<>();
        for (PosList posList : PosList.<PosList>find("order by kind, code").list()) {
            rows.add(row(posList));
        }
        LOGGER.info("Exiting method list with " + rows.size() + " list(s)");
        return adminLists.data("lists", rows)
                .data("kinds", PosList.Kind.values())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Projects one list and its entries onto their screen rows.
     *
     * @param posList the list
     * @return the row
     */
    private ListRow row(PosList posList) {
        ListRow row = new ListRow();
        row.id = posList.id;
        row.code = posList.code;
        row.label = posList.label;
        row.kind = posList.kind == null ? "" : posList.kind.name();
        row.kindLabel = posList.kind == null ? "" : posList.kind.getLabel();
        row.functions = posList.functions == null ? "" : posList.functions;
        row.active = posList.active;
        row.usesSeverity = posList.kind == PosList.Kind.SERVICE_REQUEST;
        // BO-03-02-45/-47 and BO-03-07-03/-04/-06: an expense carries its
        // maximum and a discount key its figure in the value column, and both
        // state their rules in the option markers, like a return type.
        row.usesWeight = posList.kind == PosList.Kind.TARE_CONTAINER
                || posList.kind == PosList.Kind.JUSTIFICATION
                || posList.kind == PosList.Kind.DISCOUNT;
        row.usesOptions = posList.kind == PosList.Kind.RETURN_TYPE
                || posList.kind == PosList.Kind.JUSTIFICATION
                || posList.kind == PosList.Kind.DISCOUNT;
        row.valueLabelKey = valueLabelKey(posList.kind);
        row.optionsHintKey = optionsHintKey(posList.kind);
        for (PosListItem item : posList.items) {
            ItemRow itemRow = new ItemRow();
            itemRow.id = item.id;
            itemRow.code = item.code;
            itemRow.label = item.label;
            itemRow.displayOrder = item.displayOrder;
            itemRow.severity = item.severity;
            itemRow.numericValue = item.numericValue == null
                    ? "" : item.numericValue.toPlainString();
            itemRow.options = item.options == null ? "" : item.options;
            itemRow.active = item.active;
            row.items.add(itemRow);
        }
        return row;
    }

    /**
     * Creates a list or writes one back ({@code BO-04-03-12}).
     *
     * @param form the posted form
     * @return a 303 back to the screen with the outcome
     */
    @POST
    @Path("/save")
    @Authenticated
    @FeatureAccess(value = Feature.POS_LIST, option = CrudOption.CREATE, updateKey = "id")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save");
        Long id = AdminForms.id(form.getFirst("id"));
        String code = code(AdminForms.text(form, "code"));
        String label = AdminForms.text(form, "label");
        if (code == null || label == null) {
            LOGGER.info("Exiting method save: incomplete");
            return back("Le code et le libellé de la liste sont obligatoires.", false);
        }
        PosList existing = PosList.findByCode(code);
        if (existing != null && (id == null || !existing.id.equals(id))) {
            LOGGER.info("Exiting method save: duplicate code");
            return back("Ce code de liste est déjà pris.", false);
        }
        PosList row = id == null ? new PosList() : PosList.<PosList>findById(id);
        if (row == null) {
            LOGGER.info("Exiting method save: unknown list");
            return back("Liste inconnue.", false);
        }
        row.code = code;
        row.label = label;
        row.kind = kind(form.getFirst("kind"));
        row.functions = AdminForms.text(form, "functions");
        row.active = AdminForms.checkbox(form, "active");
        row.persist();
        LOGGER.infof("Liste %s enregistrée", row.code);
        LOGGER.info("Exiting method save");
        return back(id == null ? "Liste créée." : "Liste enregistrée.", true);
    }

    /**
     * Deletes a list and the entries it holds.
     *
     * @param form the posted form
     * @return a 303 back to the screen with the outcome
     */
    @POST
    @Path("/delete")
    @Authenticated
    @FeatureAccess(value = Feature.POS_LIST, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete");
        Long id = AdminForms.id(form.getFirst("id"));
        PosList row = id == null ? null : PosList.<PosList>findById(id);
        if (row == null) {
            LOGGER.info("Exiting method delete: unknown list");
            return back("Liste inconnue.", false);
        }
        // BO-03-13-02: a list whose return types the refunds already name is
        // not deleted — the states would lose what those refunds were.
        if (row.kind == PosList.Kind.RETURN_TYPE) {
            for (PosListItem item : row.items) {
                if (returnsOfType(item.code) > 0) {
                    LOGGER.info("Exiting method delete: return types in use");
                    return back("Cette liste porte des types de retour déjà utilisés : désactivez-la plutôt.", false);
                }
            }
        }
        row.delete();
        LOGGER.info("Exiting method delete");
        return back("Liste supprimée.", true);
    }

    /**
     * Adds an entry to a list or writes one back.
     *
     * @param form the posted form
     * @return a 303 back to the screen with the outcome
     */
    @POST
    @Path("/item/save")
    @Authenticated
    @FeatureAccess(value = Feature.POS_LIST, option = CrudOption.CREATE, updateKey = "id")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveItem(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveItem");
        Long listId = AdminForms.id(form.getFirst("listId"));
        PosList parent = listId == null ? null : PosList.<PosList>findById(listId);
        if (parent == null) {
            LOGGER.info("Exiting method saveItem: unknown list");
            return back("Liste inconnue.", false);
        }
        String code = code(AdminForms.text(form, "code"));
        String label = AdminForms.text(form, "label");
        if (code == null || label == null) {
            LOGGER.info("Exiting method saveItem: incomplete");
            return back("Le code et le libellé de l'entrée sont obligatoires.", false);
        }
        // BO-03-02-45/-47, BO-03-07-03/-04, BO-03-13-11/-12: the option markers
        // are a closed vocabulary per kind; a typo is refused rather than
        // silently ignored at the register.
        String options = AdminForms.text(form, "options");
        String problem = optionProblem(parent.kind, options);
        if (problem != null) {
            LOGGER.info("Exiting method saveItem: bad option");
            return back("Option inconnue pour ce genre de liste : " + problem + ".", false);
        }
        if (parent.kind == PosList.Kind.DISCOUNT
                && hasMarker(options, "PERCENT") == hasMarker(options, "AMOUNT")) {
            LOGGER.info("Exiting method saveItem: discount nature");
            return back("Une remise porte PERCENT ou AMOUNT, et un seul des deux.", false);
        }
        Long id = AdminForms.id(form.getFirst("id"));
        PosListItem item = id == null ? null : PosListItem.<PosListItem>findById(id);
        if (id != null && item == null) {
            LOGGER.info("Exiting method saveItem: unknown item");
            return back("Entrée inconnue.", false);
        }
        if (item == null) {
            PosListItem duplicate = PosListItem
                    .<PosListItem>find("posList.id = ?1 and code = ?2", parent.id, code)
                    .firstResult();
            if (duplicate != null) {
                LOGGER.info("Exiting method saveItem: duplicate code");
                return back("Ce code d'entrée est déjà pris dans cette liste.", false);
            }
            item = new PosListItem();
            item.posList = parent;
            parent.items.add(item);
        }
        item.code = code;
        item.label = label;
        item.displayOrder = AdminForms.integer(form.getFirst("displayOrder"), 0);
        item.severity = Math.max(AdminForms.integer(form.getFirst("severity"), 0), 0);
        item.numericValue = AdminForms.amount(form.getFirst("numericValue"));
        item.options = options;
        item.active = AdminForms.checkbox(form, "active");
        item.persist();
        LOGGER.infof("Entrée %s de la liste %s enregistrée", item.code, parent.code);
        LOGGER.info("Exiting method saveItem");
        return back(id == null ? "Entrée ajoutée." : "Entrée enregistrée.", true);
    }

    /**
     * Deletes one entry of a list.
     *
     * @param form the posted form
     * @return a 303 back to the screen with the outcome
     */
    @POST
    @Path("/item/delete")
    @Authenticated
    @FeatureAccess(value = Feature.POS_LIST, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteItem(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteItem");
        Long id = AdminForms.id(form.getFirst("id"));
        PosListItem item = id == null ? null : PosListItem.<PosListItem>findById(id);
        if (item == null) {
            LOGGER.info("Exiting method deleteItem: unknown item");
            return back("Entrée inconnue.", false);
        }
        // BO-03-13-02: a return type the refunds already name is DEACTIVATED
        // rather than deleted, and the administrator is told how many refunds
        // name it — the register stops offering it all the same.
        long used = item.posList != null && item.posList.kind == PosList.Kind.RETURN_TYPE
                ? returnsOfType(item.code) : 0;
        if (used > 0) {
            item.active = false;
            item.persist();
            LOGGER.info("Exiting method deleteItem: return type in use, deactivated");
            return back("Le type de retour " + item.code + " est rattaché à " + used
                    + " retour(s) : il a été désactivé et non supprimé.", false);
        }
        if (item.posList != null) {
            item.posList.items.remove(item);
        }
        item.delete();
        LOGGER.info("Exiting method deleteItem");
        return back("Entrée supprimée.", true);
    }

    /**
     * Switches an entry in or out of service in one gesture (BO-03-02-46,
     * BO-03-13-03): the register offers it, or stops offering it, with the
     * next lists it receives, and nothing else of the entry changes.
     *
     * @param form the posted form (id)
     * @return a 303 back to the screen with the outcome
     */
    @POST
    @Path("/item/toggle")
    @Authenticated
    @FeatureAccess(value = Feature.POS_LIST, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response toggleItem(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method toggleItem");
        Long id = AdminForms.id(form.getFirst("id"));
        PosListItem item = id == null ? null : PosListItem.<PosListItem>findById(id);
        if (item == null) {
            LOGGER.info("Exiting method toggleItem: unknown item");
            return back("Entrée inconnue.", false);
        }
        item.active = !item.active;
        item.persist();
        LOGGER.info("Exiting method toggleItem");
        return back(item.active ? "Entrée activée." : "Entrée désactivée.", true);
    }

    /**
     * Counts the refunds that name a return type (BO-03-13-02).
     *
     * @param code the return type code
     * @return how many refunds carry that type code
     */
    long returnsOfType(String code) {
        return com.intermarche.edge.domain.sale.Refund.count("returnTypeCode", code);
    }

    /**
     * The heading of the value column of a kind's entries.
     *
     * @param kind the list kind, possibly null
     * @return the bundle key of the heading
     */
    static String valueLabelKey(PosList.Kind kind) {
        if (kind == PosList.Kind.JUSTIFICATION) {
            return "t.montant-maximum-de-la-depense";
        }
        if (kind == PosList.Kind.DISCOUNT) {
            return "t.valeur-de-la-touche";
        }
        return "t.poids-kg";
    }

    /**
     * The hint of the option markers of a kind's entries.
     *
     * @param kind the list kind, possibly null
     * @return the bundle key of the hint
     */
    static String optionsHintKey(PosList.Kind kind) {
        if (kind == PosList.Kind.JUSTIFICATION) {
            return "t.options-depense";
        }
        if (kind == PosList.Kind.DISCOUNT) {
            return "t.options-remise";
        }
        return "t.options-type-retour";
    }

    /**
     * Finds the first option marker a kind does not know (BO-03-02-45/-47,
     * BO-03-07-03/-04, BO-03-13-11/-12).
     *
     * <p>Only the kinds whose markers mean something are checked: a return
     * type knows {@code TICKET}, {@code NOENDORSE}, {@code NOORIGIN},
     * {@code PRICE}, {@code DECONSIGNE} and {@code RANGE:code}; an expense
     * {@code TENDER:code} and {@code CONTROL:level}; a discount key
     * {@code PERCENT}, {@code AMOUNT}, {@code TICKET}, {@code CUMUL} and
     * {@code MAX:n}.
     *
     * @param kind the list kind, possibly null
     * @param options the posted markers, possibly null
     * @return the first unknown marker, or null when all are known
     */
    static String optionProblem(PosList.Kind kind, String options) {
        if (options == null) {
            return null;
        }
        for (String part : options.split(",")) {
            String marker = part.trim();
            if (!marker.isEmpty() && !knownMarker(kind, marker)) {
                return marker;
            }
        }
        return null;
    }

    /**
     * Tells whether one marker belongs to a kind's vocabulary.
     *
     * @param kind the list kind, possibly null
     * @param marker the trimmed marker, never empty
     * @return true when the kind knows it, or checks nothing
     */
    private static boolean knownMarker(PosList.Kind kind, String marker) {
        String upper = marker.toUpperCase(Locale.ROOT);
        int colon = upper.indexOf(':');
        String name = colon < 0 ? upper : upper.substring(0, colon).trim();
        String value = colon < 0 ? null : upper.substring(colon + 1).trim();
        if (kind == PosList.Kind.RETURN_TYPE) {
            return colon < 0 ? List.of("TICKET", "NOENDORSE", "NOORIGIN", "PRICE", "DECONSIGNE").contains(name)
                    : "RANGE".equals(name) && !value.isEmpty();
        }
        if (kind == PosList.Kind.JUSTIFICATION) {
            return colon > 0 && ("TENDER".equals(name) && !value.isEmpty()
                    || "CONTROL".equals(name) && controlLevel(value));
        }
        if (kind == PosList.Kind.DISCOUNT) {
            return colon < 0 ? List.of("PERCENT", "AMOUNT", "TICKET", "CUMUL").contains(name)
                    : "MAX".equals(name) && AdminForms.amount(value) != null;
        }
        return true;
    }

    /**
     * Tells whether a word names a tender control level.
     *
     * @param value the upper-case word
     * @return true for NONE, INFO, WARNING, SUPERVISOR or BLOCKING
     */
    private static boolean controlLevel(String value) {
        for (com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel level
                : com.intermarche.edge.domain.payment.TenderDefinition.ControlLevel.values()) {
            if (level.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether the posted markers carry a bare marker.
     *
     * @param options the posted markers, possibly null
     * @param name the marker, upper case
     * @return true when it is present
     */
    static boolean hasMarker(String options, String name) {
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
     * Writes the four standard lists from the settings as they stand, the
     * migration gesture ({@code BO-04-02-07}, {@code BO-04-03-12}).
     *
     * <p>A list whose code already exists is left ALONE: the gesture is a
     * starting point, not a reset, and running it twice must not undo what an
     * administrator has since corrected.
     *
     * @return a 303 back to the screen with the outcome
     */
    @POST
    @Path("/bootstrap")
    @Authenticated
    @FeatureAccess(value = Feature.POS_LIST, option = CrudOption.CREATE)
    @Transactional
    public Response bootstrap() {
        LOGGER.info("Entering method bootstrap");
        int created = 0;
        created += bootstrapLabels("SERVICE", "Demandes de service",
                PosList.Kind.SERVICE_REQUEST, null, "supervisor.service-reasons", 1);
        created += bootstrapLabels("JUSTIF-DEPENSE", "Motifs de dépense",
                PosList.Kind.JUSTIFICATION, "EXPENSE", "cash.movement-reasons", 0);
        created += bootstrapReturnTypes();
        created += bootstrapTareContainers();
        LOGGER.info("Exiting method bootstrap with " + created + " list(s)");
        return back(created == 0
                ? "Les listes standard existent déjà : rien n'a été créé."
                : created + " liste(s) reprise(s) des paramètres.", true);
    }

    /**
     * Creates a list of plain labels out of a semicolon-separated setting.
     *
     * @param code the code of the list to create
     * @param label its heading
     * @param kind what it drives
     * @param functions the functions it serves, or null for all of them
     * @param settingKey the setting to read the labels from
     * @param severity the severity every entry is created with
     * @return 1 when a list was created, 0 when it already existed or the
     *         setting named nothing
     */
    private int bootstrapLabels(String code, String label, PosList.Kind kind,
                                String functions, String settingKey, int severity) {
        if (PosList.findByCode(code) != null) {
            return 0;
        }
        String raw = posSettingsService.value(settingKey);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        PosList row = newList(code, label, kind, functions);
        int rank = 0;
        for (String part : raw.split(";")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            PosListItem item = newItem(row, slug(entry), entry, rank);
            item.severity = severity;
            rank += 10;
        }
        row.persist();
        return 1;
    }

    /**
     * Creates the return-type list out of {@code return.types}, keeping the
     * option markers the string carried after its {@code >}.
     *
     * @return 1 when the list was created, 0 otherwise
     */
    private int bootstrapReturnTypes() {
        if (PosList.findByCode("RETOURS") != null) {
            return 0;
        }
        String raw = posSettingsService.value("return.types");
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        PosList row = newList("RETOURS", "Types de retour", PosList.Kind.RETURN_TYPE, null);
        int rank = 0;
        for (String part : raw.split(";")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int marker = entry.indexOf('>');
            String label = marker < 0 ? entry : entry.substring(0, marker).trim();
            if (label.isEmpty()) {
                continue;
            }
            PosListItem item = newItem(row, slug(label), label, rank);
            item.options = marker < 0 ? null : entry.substring(marker + 1).trim();
            rank += 10;
        }
        row.persist();
        return 1;
    }

    /**
     * Creates the tare-container list out of {@code balance.tare-containers},
     * whose entries are written {@code CODE:LIBELLÉ:POIDS}.
     *
     * @return 1 when the list was created, 0 otherwise
     */
    private int bootstrapTareContainers() {
        if (PosList.findByCode("TARES") != null) {
            return 0;
        }
        String raw = posSettingsService.value("balance.tare-containers");
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        PosList row = newList("TARES", "Contenants de tare", PosList.Kind.TARE_CONTAINER, null);
        int rank = 0;
        for (String part : raw.split(";")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String[] fields = entry.split(":");
            if (fields.length != 3) {
                continue;
            }
            String code = fields[0].trim();
            String label = fields[1].trim();
            if (code.isEmpty() || label.isEmpty()) {
                continue;
            }
            BigDecimal weight = weight(fields[2]);
            if (weight == null) {
                continue;
            }
            PosListItem item = newItem(row, code, label, rank);
            item.numericValue = weight;
            rank += 10;
        }
        row.persist();
        return 1;
    }

    /**
     * Builds a list with its header fields set and no entry yet.
     *
     * @param code the code
     * @param label the heading
     * @param kind what it drives
     * @param functions the functions it serves, or null
     * @return the list, not persisted yet
     */
    private PosList newList(String code, String label, PosList.Kind kind, String functions) {
        PosList row = new PosList();
        row.code = code;
        row.label = label;
        row.kind = kind;
        row.functions = functions;
        row.active = true;
        return row;
    }

    /**
     * Builds an entry and attaches it to its list.
     *
     * @param parent the list it belongs to
     * @param code the entry code
     * @param label the entry label
     * @param rank the display rank
     * @return the entry, attached but not persisted yet
     */
    private PosListItem newItem(PosList parent, String code, String label, int rank) {
        PosListItem item = new PosListItem();
        item.posList = parent;
        item.code = code;
        item.label = label;
        item.displayOrder = rank;
        item.active = true;
        parent.items.add(item);
        return item;
    }

    /**
     * Reads a weight off a migrated entry, a comma passing for a point; an
     * unreadable or negative one answers null and drops its entry.
     *
     * @param raw the written weight
     * @return the weight, or null
     */
    static BigDecimal weight(String raw) {
        try {
            BigDecimal value = new BigDecimal(raw.trim().replace(',', '.'));
            return value.signum() < 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Derives an entry code from its label: upper case, the runs of anything
     * else becoming a single dash, cut to the column's length.
     *
     * @param label the label, never blank
     * @return the derived code
     */
    static String slug(String label) {
        StringBuilder code = new StringBuilder();
        for (char character : label.toUpperCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(character)) {
                code.append(character);
            } else if (code.length() > 0 && code.charAt(code.length() - 1) != '-') {
                code.append('-');
            }
        }
        while (code.length() > 0 && code.charAt(code.length() - 1) == '-') {
            code.setLength(code.length() - 1);
        }
        if (code.length() > MAX_CODE) {
            code.setLength(MAX_CODE);
        }
        return code.length() == 0 ? "X" : code.toString();
    }

    /**
     * Normalises a posted code: trimmed, upper case, cut to the column's
     * length; a blank one answers null.
     *
     * @param raw the posted code, possibly null
     * @return the code, or null when nothing was typed
     */
    static String code(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        return code.length() > MAX_CODE ? code.substring(0, MAX_CODE) : code;
    }

    /**
     * Reads the posted kind, an unknown or absent one answering JUSTIFICATION
     * — the kind that drives nothing by itself.
     *
     * @param raw the posted value
     * @return the kind, never null
     */
    static PosList.Kind kind(String raw) {
        if (raw != null && !raw.isBlank()) {
            for (PosList.Kind candidate : PosList.Kind.values()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
        }
        return PosList.Kind.JUSTIFICATION;
    }

    /**
     * Builds the 303 back to this screen.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String notice, boolean ok) {
        return AdminForms.redirect("/admin/lists", notice, ok);
    }
}
