package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.setting.PosList;
import com.intermarche.edge.domain.setting.PosListItem;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
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
    @RolesAllowed("ADMIN")
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
        row.usesWeight = posList.kind == PosList.Kind.TARE_CONTAINER;
        row.usesOptions = posList.kind == PosList.Kind.RETURN_TYPE;
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
    @RolesAllowed("ADMIN")
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
    @RolesAllowed("ADMIN")
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
    @RolesAllowed("ADMIN")
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
        item.options = AdminForms.text(form, "options");
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
    @RolesAllowed("ADMIN")
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
        if (item.posList != null) {
            item.posList.items.remove(item);
        }
        item.delete();
        LOGGER.info("Exiting method deleteItem");
        return back("Entrée supprimée.", true);
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
    @RolesAllowed("ADMIN")
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
