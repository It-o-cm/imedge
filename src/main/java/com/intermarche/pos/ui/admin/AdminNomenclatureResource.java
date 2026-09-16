package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.catalog.Nomenclature;
import com.intermarche.pos.domain.catalog.NomenclatureLevel;
import com.intermarche.pos.domain.catalog.ProductFamily;
import com.intermarche.pos.imports.NomenclatureCsvResource;
import com.intermarche.pos.service.NomenclatureImportService;
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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.annotations.providers.multipart.MultipartForm;
import org.jboss.resteasy.annotations.providers.multipart.PartType;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The NOMENCLATURE screen ({@code /admin/nomenclatures}).
 * <p>
 * It answers the three things BO-02-01-08 asks for and the register had no way
 * of showing: the whole classification VISIBLE LEVEL BY LEVEL, a way to check
 * what an integration actually loaded, and a way to RUN that integration.
 * <p>
 * The nomenclature is authored in the gestion commerciale (BO-02-01-01), not
 * here, so this screen never invents a node: it INTEGRATES the file that
 * office publishes, and re-integrating it is how a nomenclature is updated.
 * The file used to be reachable only by posting it to
 * {@code /nomenclatures/import} with an HTTP client, which meant a shop could
 * read its classification on this screen and had no way to refresh it. The
 * form below closes that gap, and reads the file through
 * {@link NomenclatureCsvResource#read} — one reading of the published format,
 * so the screen and the machine surface can never disagree on what a line
 * means.
 * <p>
 * Beside the integration the screen EDITS the classification node by node —
 * create, rename, delete — because the finest level is the point of sale's
 * own ({@code custom} on the level table: 1909 of the 2504 lines of the
 * published Intermarché file), and a shop that cannot open a sous-famille
 * without asking the gestion commerciale cannot classify what it sells.
 * <p>
 * Three rules govern that editing, and none of them is a preference:
 * <ul>
 *   <li>A node's CODE places it. Its length is the level, so a code of a
 *       length no level declares is refused rather than filed at a guessed
 *       depth, and the level is never typed — it is read off the code.</li>
 *   <li>A code is IDENTITY, not a field: renaming changes the label alone.
 *       Changing a code would move the node and orphan everything under it,
 *       so it is a delete and a create, said explicitly.</li>
 *   <li>A node with children or with articles filed under it is NOT deleted.
 *       The relation to the articles cascades, so deleting such a node would
 *       take them with it — the one accident this screen must never cause.</li>
 * </ul>
 * <p>
 * What the screen does NOT hide: a node of a level the file owns is
 * overwritten by the next integration, label included. The level table says
 * which ones, the screen shows it, and it refuses nothing on that ground —
 * a shop may have a good reason to correct a label between two integrations.
 * <p>
 * The level LABELS are written here too: the published file does not carry
 * them and every report names them ("rupture par rayon").
 * <p>
 * Navigation is by prefix, not by stored edge: opening a node lists the nodes
 * of the level below whose code starts with its own. That is the same rule the
 * import relies on, so the screen can never show a tree the printers would
 * disagree with.
 */
@Path("/admin/nomenclatures")
public class AdminNomenclatureResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminNomenclatureResource.class);

    /** How many nodes one page of the level listing shows. */
    static final int PAGE_SIZE = 50;

    /** How many refusals the outcome notice names before counting the rest. */
    static final int NOTICED_ERRORS = 3;

    /** How many hits one search answers before it stops looking. */
    static final int SEARCH_LIMIT = 50;

    /** The page template. */
    @Inject
    @Location("admin-nomenclatures.html")
    Template adminNomenclatures;

    /** The reader of the published file, shared with the machine surface. */
    @Inject
    NomenclatureCsvResource nomenclatureCsvResource;

    /** The loader the integration goes through. */
    @Inject
    NomenclatureImportService nomenclatureImportService;

    /**
     * The integration form: the scheme it loads into, and the published file.
     * <p>
     * A multipart form and not a textarea: the group's file runs to a couple
     * of thousand lines, well past what a form attribute carries.
     */
    public static class ImportForm {

        /** The scheme code to load into, its upsert key. */
        @jakarta.ws.rs.FormParam("scheme")
        @PartType(MediaType.TEXT_PLAIN)
        public String scheme;

        /** The scheme label, used only when the scheme is created. */
        @jakarta.ws.rs.FormParam("label")
        @PartType(MediaType.TEXT_PLAIN)
        public String label;

        /** The enseigne the scheme classifies for, or blank. */
        @jakarta.ws.rs.FormParam("enseigne")
        @PartType(MediaType.TEXT_PLAIN)
        public String enseigne;

        /** The published file itself. */
        @jakarta.ws.rs.FormParam("file")
        @PartType(MediaType.APPLICATION_OCTET_STREAM)
        public InputStream file;

        /**
         * Builds an empty form, as the multipart reader requires.
         */
        public ImportForm() {
        }
    }

    /**
     * One node as the screen draws it: what it is, and how much hangs under it.
     */
    public static class NodeRow {

        /** The node code. */
        public final String code;

        /** The node label. */
        public final String label;

        /** The 0-based level. */
        public final int level;

        /** How many nodes of the level below hang under it. */
        public final long children;

        /**
         * Builds a row.
         *
         * @param code the node code
         * @param label the node label
         * @param level the 0-based level
         * @param children how many nodes hang under it
         */
        public NodeRow(String code, String label, int level, long children) {
            this.code = code;
            this.label = label;
            this.level = level;
            this.children = children;
        }

        /**
         * Returns the node code.
         *
         * @return the code
         */
        public String getCode() {
            return code;
        }

        /**
         * Returns the node label.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }

        /**
         * Returns the 0-based level.
         *
         * @return the level
         */
        public int getLevel() {
            return level;
        }

        /**
         * Returns how many nodes hang under this one.
         *
         * @return the child count
         */
        public long getChildren() {
            return children;
        }

        /**
         * Whether anything hangs under this node — what tells the screen to
         * offer the drill-down or not.
         *
         * @return true when the node has children
         */
        public boolean isOpenable() {
            return children > 0;
        }
    }

    /**
     * One step of the breadcrumb: an ancestor the operator may climb back to.
     */
    public static class Crumb {

        /** The ancestor code, or empty for the root of the scheme. */
        public final String code;

        /** What to show. */
        public final String label;

        /**
         * Builds a crumb.
         *
         * @param code the ancestor code, empty for the root
         * @param label the label to show
         */
        public Crumb(String code, String label) {
            this.code = code;
            this.label = label;
        }

        /**
         * Returns the ancestor code.
         *
         * @return the code, empty for the root
         */
        public String getCode() {
            return code;
        }

        /**
         * Returns the label to show.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }
    }

    /**
     * Shows one scheme, one level at a time.
     *
     * @param schemeCode the scheme to show, or null for the first active one
     * @param parentCode the node whose children to list, or null for the top
     * @param page the 1-based page of the listing
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance nomenclaturesPage(@QueryParam("scheme") String schemeCode,
            @QueryParam("parent") String parentCode,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("notice") String notice,
            @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method nomenclaturesPage with scheme: " + schemeCode
                + ", parent: " + parentCode);
        List<Nomenclature> schemes = Nomenclature.listAllOrdered();
        Nomenclature scheme = resolveScheme(schemes, schemeCode);
        List<NomenclatureLevel> levels = scheme == null ? List.of() : scheme.levels();
        String parent = parentCode == null ? "" : parentCode.trim();
        int level = levelOf(scheme, parent);
        List<ProductFamily> nodes = childrenOf(scheme, parent, level);
        int totalPages = Math.max(1, (nodes.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 1), totalPages);
        int from = (current - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, nodes.size());
        List<NodeRow> rows = new ArrayList<>();
        for (ProductFamily node : nodes.subList(from, to)) {
            rows.add(new NodeRow(node.code, node.description, level,
                    countChildren(scheme, node.code, level)));
        }
        LOGGER.info("Exiting method nomenclaturesPage");
        return adminNomenclatures
                .data("schemes", schemes)
                .data("scheme", scheme)
                .data("levels", levels)
                .data("levelLabel", levelLabel(scheme, level))
                .data("levelCustom", isCustomLevel(scheme, level))
                .data("codeLength", codeLengthAt(scheme, level))
                .data("crumbs", crumbs(scheme, parent))
                .data("parent", parent)
                .data("rows", rows)
                .data("total", nodes.size())
                .data("page", current)
                .data("totalPages", totalPages)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Integrates the file published by the gestion commerciale (BO-02-01-08).
     * <p>
     * This is how a nomenclature is UPDATED: the published file is the record,
     * and re-integrating it is idempotent — the same file twice changes
     * nothing, so a retry after a network failure or a doubtful load is free.
     * Nodes the file no longer names are LEFT IN PLACE rather than deleted: an
     * article may still be filed under one, and a classification that empties
     * itself because a partial extract was dropped on it is the one accident
     * this screen must not cause.
     * <p>
     * A refusal is reported and nothing is half-written: the loader stops on a
     * level whose lines disagree on the code length, because the length IS the
     * level and two lengths would place every node below it by chance.
     *
     * @param form the posted scheme identity and the published file
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @jakarta.ws.rs.Path("/import")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response importFile(@MultipartForm ImportForm form) {
        LOGGER.info("Entering method importFile");
        String schemeCode = form == null || form.scheme == null ? "" : form.scheme.trim();
        if (schemeCode.isEmpty()) {
            LOGGER.info("Exiting method importFile");
            return redirect(null, "Le code de la nomenclature est obligatoire.", false);
        }
        List<String> complaints = new ArrayList<>();
        List<NomenclatureImportService.Row> rows =
                nomenclatureCsvResource.read(form.file, complaints);
        if (!complaints.isEmpty()) {
            LOGGER.info("Exiting method importFile");
            return redirect(schemeCode, summarize(complaints), false);
        }
        if (rows.isEmpty()) {
            LOGGER.info("Exiting method importFile");
            return redirect(schemeCode, "Fichier sans aucune ligne exploitable.", false);
        }
        NomenclatureImportService.Outcome outcome = nomenclatureImportService.load(
                schemeCode, trimmed(form.label), trimmed(form.enseigne), rows);
        if (!outcome.isClean()) {
            LOGGER.info("Exiting method importFile");
            return redirect(schemeCode, summarize(outcome.errors), false);
        }
        LOGGER.info("Exiting method importFile");
        return redirect(schemeCode, outcome.created + " nœud(s) créé(s), "
                + outcome.updated + " mis à jour, " + outcome.levels + " niveau(x).", true);
    }

    /**
     * Turns a list of refusals into one notice a redirect can carry.
     * <p>
     * A bad extract refuses thousands of lines and a URL cannot hold them:
     * the first few are named — they are enough to recognize the mistake —
     * and the rest are counted.
     *
     * @param errors what was refused, never empty
     * @return the notice
     */
    private String summarize(List<String> errors) {
        StringBuilder notice = new StringBuilder();
        for (int i = 0; i < Math.min(NOTICED_ERRORS, errors.size()); i++) {
            notice.append(i == 0 ? "" : " ").append(errors.get(i));
        }
        if (errors.size() > NOTICED_ERRORS) {
            notice.append(" (+").append(errors.size() - NOTICED_ERRORS).append(" autre(s))");
        }
        return notice.toString();
    }

    /**
     * Reads an optional posted field, blank becoming absent.
     *
     * @param posted the posted value, possibly null
     * @return the trimmed value, or null when it says nothing
     */
    private String trimmed(String posted) {
        if (posted == null || posted.trim().isEmpty()) {
            return null;
        }
        return posted.trim();
    }

    /**
     * Creates a node, or renames one that already carries the posted code
     * (BO-02-01-08).
     * <p>
     * The level is NOT posted: it is read off the code length, because the
     * length IS the level in this coding and a typed level could contradict
     * it. A code no level declares is refused — filing it at a guessed depth
     * would misplace everything hanging under it.
     * <p>
     * The parent is not posted either, and not stored: it is the code's own
     * prefix. A node whose parent does not exist is refused, so the
     * classification can never grow a branch hanging from nothing.
     *
     * @param form the posted scheme, code and label
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @jakarta.ws.rs.Path("/nodes")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveNode(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveNode");
        String schemeCode = value(form, "scheme");
        Nomenclature scheme = schemeCode.isEmpty() ? null : Nomenclature.findByCode(schemeCode);
        if (scheme == null) {
            LOGGER.info("Exiting method saveNode");
            return redirect(null, "Nomenclature introuvable.", false);
        }
        Verdict verdict = applyNode(scheme, value(form, "code"), value(form, "label"));
        LOGGER.info("Exiting method saveNode");
        return redirect(scheme.code, verdict.message, verdict.ok);
    }

    /**
     * The outcome of one write, said the same way whatever surface asked for
     * it: the form posts and the tree's fetch calls share this verdict, so the
     * screen and its no-JavaScript fallback can never enforce two rules.
     */
    public static class Verdict {

        /** Whether the write went through. */
        public final boolean ok;

        /** What to tell the operator. */
        public final String message;

        /**
         * The code the node ended up carrying, when the write moved it, or
         * null for a write that changed no code.
         * <p>
         * Said rather than deduced: a move may RENUMBER the node, so the
         * screen can no longer compute where its row landed from the parent
         * it aimed at. Guessing it would leave the tree pointing at a code
         * that no longer exists.
         */
        public final String code;

        /**
         * Builds a verdict about a write that changed no code.
         *
         * @param ok whether the write went through
         * @param message what to tell the operator
         */
        public Verdict(boolean ok, String message) {
            this(ok, message, null);
        }

        /**
         * Builds a verdict naming the code the node now carries.
         *
         * @param ok whether the write went through
         * @param message what to tell the operator
         * @param code the code the node ended up on, or null
         */
        public Verdict(boolean ok, String message, String code) {
            this.ok = ok;
            this.message = message;
            this.code = code;
        }
    }

    /**
     * Creates a node, or renames the one already carrying that code.
     *
     * @param scheme the scheme being edited, never null
     * @param code the posted node code
     * @param label the posted label
     * @return what was done, or why it was refused
     */
    private Verdict applyNode(Nomenclature scheme, String code, String label) {
        if (code.isEmpty() || label.isEmpty()) {
            return new Verdict(false, "Le code et le libellé sont obligatoires.");
        }
        NomenclatureLevel placed = scheme.levelOfCode(code);
        if (placed == null) {
            return new Verdict(false, "Code « " + code + " » : aucun niveau de "
                    + scheme.code + " n'a des codes de " + code.length() + " caractères.");
        }
        String parentCode = scheme.parentCodeOf(code);
        if (parentCode != null && ProductFamily.findByCode(parentCode) == null) {
            return new Verdict(false, "Code « " + code + " » : le nœud parent « "
                    + parentCode + " » n'existe pas.");
        }
        ProductFamily node = ProductFamily.findByCode(code);
        boolean creating = node == null;
        if (creating) {
            node = new ProductFamily();
            node.code = code;
        } else if (node.nomenclature != null && !node.nomenclature.code.equals(scheme.code)) {
            return new Verdict(false, "Le code « " + code + " » appartient déjà à la "
                    + "nomenclature " + node.nomenclature.code + ".");
        }
        node.description = label;
        node.nomenclature = scheme;
        node.level = placed.rank;
        node.persist();
        LOGGER.infof("Nœud %s %s dans %s", code, creating ? "créé" : "renommé", scheme.code);
        return new Verdict(true, "Nœud « " + code + " » "
                + (creating ? "créé." : "renommé."));
    }

    /**
     * Deletes a node, when nothing hangs from it (BO-02-01-08).
     * <p>
     * Two refusals, both structural. A node with CHILDREN would leave them
     * hanging from a parent that no longer exists, and the ancestry is read
     * off the code, so nothing would even signal the break. A node with
     * ARTICLES filed under it takes them with it — the relation cascades —
     * and a classification cannot be allowed to delete the catalog.
     *
     * @param form the posted scheme and code
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @jakarta.ws.rs.Path("/nodes/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteNode(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteNode");
        String schemeCode = value(form, "scheme");
        Nomenclature scheme = schemeCode.isEmpty() ? null : Nomenclature.findByCode(schemeCode);
        if (scheme == null) {
            LOGGER.info("Exiting method deleteNode");
            return redirect(null, "Nomenclature introuvable.", false);
        }
        Verdict verdict = removeNode(scheme, value(form, "code"));
        LOGGER.info("Exiting method deleteNode");
        return redirect(scheme.code, verdict.message, verdict.ok);
    }

    /**
     * Deletes a node when nothing hangs from it.
     *
     * @param scheme the scheme being edited, never null
     * @param code the posted node code
     * @return what was done, or why it was refused
     */
    private Verdict removeNode(Nomenclature scheme, String code) {
        ProductFamily node = code.isEmpty() ? null : ProductFamily.findByCode(code);
        if (node == null) {
            return new Verdict(false, "Nœud « " + code + " » introuvable.");
        }
        NomenclatureLevel placed = scheme.levelOfCode(code);
        long children = placed == null ? 0 : countChildren(scheme, code, placed.rank);
        if (children > 0) {
            return new Verdict(false, "Nœud « " + code + " » : " + children
                    + " nœud(s) en dépendent, supprimez-les d'abord.");
        }
        int articles = node.products == null ? 0 : node.products.size();
        if (articles > 0) {
            return new Verdict(false, "Nœud « " + code + " » : " + articles
                    + " article(s) y sont rattachés, reclassez-les d'abord.");
        }
        node.delete();
        LOGGER.infof("Nœud %s supprimé de %s", code, scheme.code);
        return new Verdict(true, "Nœud « " + code + " » supprimé.");
    }

    /**
     * Reads one posted field, absent becoming empty.
     *
     * @param form the posted form
     * @param field the field name
     * @return the trimmed value, never null
     */
    private String value(MultivaluedMap<String, String> form, String field) {
        String posted = form == null ? null : form.getFirst(field);
        return posted == null ? "" : posted.trim();
    }

    // --------------------------------------------------
    // The tree's own surface (BO-02-01-08)
    // --------------------------------------------------
    //
    // Four endpoints answering JSON, so the screen opens a node, searches,
    // renames and deletes WITHOUT reloading: on a classification of two and a
    // half thousand nodes a page reload per gesture is the whole ergonomic
    // problem — it costs the operator its place in the tree every time.
    //
    // They decide nothing of their own: the two writes go through the very
    // same applyNode/removeNode the form posts use, so the tree and its
    // no-JavaScript fallback enforce one set of rules.

    /**
     * The children of a node, or the top level when no node is named.
     * <p>
     * Two queries whatever the width: the level being listed and the one below
     * it, the child counts then computed in memory by prefix. A count per node
     * would be one query per row, which is what makes a tree crawl.
     *
     * @param schemeCode the scheme to read
     * @param parentCode the opened node code, or null for the top
     * @return the nodes as JSON
     */
    @GET
    @jakarta.ws.rs.Path("/api/children")
    @RolesAllowed("ADMIN")
    @Produces(MediaType.APPLICATION_JSON)
    public Response children(@QueryParam("scheme") String schemeCode,
            @QueryParam("parent") String parentCode) {
        LOGGER.info("Entering method children with scheme: " + schemeCode);
        Nomenclature scheme = schemeCode == null ? null : Nomenclature.findByCode(schemeCode);
        if (scheme == null) {
            LOGGER.info("Exiting method children");
            return Response.status(Response.Status.NOT_FOUND).entity("[]").build();
        }
        String parent = parentCode == null ? "" : parentCode.trim();
        int level = levelOf(scheme, parent);
        List<ProductFamily> below = ProductFamily.listAtLevel(scheme, level + 1);
        StringBuilder body = new StringBuilder("[");
        boolean first = true;
        for (ProductFamily node : childrenOf(scheme, parent, level)) {
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append(nodeJson(node, hasPrefix(below, node.code)));
        }
        LOGGER.info("Exiting method children");
        return Response.ok(body.append(']').toString()).build();
    }

    /**
     * The nodes whose code or label carries what was typed, wherever they sit.
     * <p>
     * This is what replaces the descent: four levels deep, reaching a node by
     * opening its three ancestors is the slow path, and a shop knows the label
     * long before it knows the code. Each hit carries its full path, so a
     * result is readable without opening anything.
     *
     * @param schemeCode the scheme to search
     * @param query what was typed
     * @return the hits as JSON, capped
     */
    @GET
    @jakarta.ws.rs.Path("/api/search")
    @RolesAllowed("ADMIN")
    @Produces(MediaType.APPLICATION_JSON)
    public Response search(@QueryParam("scheme") String schemeCode,
            @QueryParam("q") String query) {
        LOGGER.info("Entering method search with scheme: " + schemeCode);
        Nomenclature scheme = schemeCode == null ? null : Nomenclature.findByCode(schemeCode);
        String needle = query == null ? "" : query.trim().toLowerCase();
        if (scheme == null || needle.isEmpty()) {
            LOGGER.info("Exiting method search");
            return Response.ok("[]").build();
        }
        StringBuilder body = new StringBuilder("[");
        int found = 0;
        for (ProductFamily node : ProductFamily.listInNomenclature(scheme)) {
            if (!matches(node, needle)) {
                continue;
            }
            if (found > 0) {
                body.append(',');
            }
            if (++found > SEARCH_LIMIT) {
                break;
            }
            body.append(nodeJson(node, false));
            body.setLength(body.length() - 1);
            body.append(",\"path\":\"").append(escape(pathOf(scheme, node))).append("\"}");
        }
        LOGGER.info("Exiting method search");
        return Response.ok(body.append(']').toString()).build();
    }

    /**
     * Creates or renames a node, answering the tree rather than redirecting.
     *
     * @param form the posted scheme, code and label
     * @return the verdict as JSON
     */
    @POST
    @jakarta.ws.rs.Path("/api/nodes")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response apiSaveNode(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method apiSaveNode");
        LOGGER.info("Exiting method apiSaveNode");
        return verdictJson(form, true);
    }

    /**
     * Deletes a node, answering the tree rather than redirecting.
     *
     * @param form the posted scheme and code
     * @return the verdict as JSON
     */
    @POST
    @jakarta.ws.rs.Path("/api/nodes/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response apiDeleteNode(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method apiDeleteNode");
        LOGGER.info("Exiting method apiDeleteNode");
        return verdictJson(form, false);
    }

    /**
     * Runs one write for the tree and writes its verdict as JSON.
     *
     * @param form the posted form
     * @param saving true to create or rename, false to delete
     * @return the verdict as JSON
     */
    private Response verdictJson(MultivaluedMap<String, String> form, boolean saving) {
        String schemeCode = value(form, "scheme");
        Nomenclature scheme = schemeCode.isEmpty() ? null : Nomenclature.findByCode(schemeCode);
        if (scheme == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"ok\":false,\"message\":\"Nomenclature introuvable.\"}").build();
        }
        Verdict verdict = saving
                ? applyNode(scheme, value(form, "code"), value(form, "label"))
                : removeNode(scheme, value(form, "code"));
        return Response.ok("{\"ok\":" + verdict.ok + ",\"message\":\""
                + escape(verdict.message) + "\"}").build();
    }

    /**
     * Moves a node under another parent, answering the tree.
     *
     * @param form the posted scheme, code and new parent code
     * @return the verdict as JSON
     */
    @POST
    @jakarta.ws.rs.Path("/api/nodes/move")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response apiMoveNode(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method apiMoveNode");
        String schemeCode = value(form, "scheme");
        Nomenclature scheme = schemeCode.isEmpty() ? null : Nomenclature.findByCode(schemeCode);
        if (scheme == null) {
            LOGGER.info("Exiting method apiMoveNode");
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"ok\":false,\"message\":\"Nomenclature introuvable.\"}").build();
        }
        Verdict verdict = moveNode(scheme, value(form, "code"), value(form, "parent"));
        LOGGER.info("Exiting method apiMoveNode");
        return Response.ok(verdictJson(verdict)).build();
    }

    /**
     * Renumbers a node — the free part of its code — answering the tree.
     *
     * @param form the posted scheme, code and new segment
     * @return the verdict as JSON
     */
    @POST
    @jakarta.ws.rs.Path("/api/nodes/recode")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response apiRecodeNode(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method apiRecodeNode");
        String schemeCode = value(form, "scheme");
        Nomenclature scheme = schemeCode.isEmpty() ? null : Nomenclature.findByCode(schemeCode);
        if (scheme == null) {
            LOGGER.info("Exiting method apiRecodeNode");
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"ok\":false,\"message\":\"Nomenclature introuvable.\"}").build();
        }
        Verdict verdict = recodeNode(scheme, value(form, "code"), value(form, "segment"));
        LOGGER.info("Exiting method apiRecodeNode");
        return Response.ok(verdictJson(verdict)).build();
    }

    /**
     * Writes a verdict the tree can read, naming the landing code when there
     * is one.
     *
     * @param verdict the verdict to say
     * @return the JSON body
     */
    private String verdictJson(Verdict verdict) {
        return "{\"ok\":" + verdict.ok + ",\"message\":\"" + escape(verdict.message)
                + "\",\"code\":" + (verdict.code == null ? "null"
                        : "\"" + escape(verdict.code) + "\"") + "}";
    }

    /**
     * Re-parents a node — which in this coding means RE-CODING it and
     * everything under it.
     * <p>
     * The parent is not an edge, it is the code's own prefix. So moving a
     * sous-famille from one rayon to another is not a pointer being repointed:
     * the node's code loses its old parent's prefix and takes the new one, and
     * every descendant follows, because their codes carry that prefix too.
     * Nothing else moves — the level is the code LENGTH, and both parents are
     * of the same level, so every code keeps its length and every node keeps
     * its level. The articles follow without being touched: they are attached
     * to the row, not to the code.
     * <p>
     * Four refusals, all structural:
     * <ul>
     *   <li>A TOP node has no parent to change; its code is its whole
     *       identity, and re-coding it is a delete and a create.</li>
     *   <li>The target must be exactly ONE level above. A rayon dropped on a
     *       sous-famille would need a code of a length its level does not
     *       have.</li>
     *   <li>A node cannot move INTO ITSELF or into its own descendants: the
     *       result would be a branch hanging from a code it also carries.</li>
     *   <li>No code it would take may already exist. The whole subtree is
     *       checked BEFORE anything is written — a move that stopped halfway
     *       would leave the classification in a state no import could
     *       repair.</li>
     * </ul>
     *
     * @param scheme the scheme being edited, never null
     * @param code the moved node's code
     * @param parentCode the code of the node it is dropped on
     * @return what was done, or why it was refused
     */
    private Verdict moveNode(Nomenclature scheme, String code, String parentCode) {
        ProductFamily node = code.isEmpty() ? null : ProductFamily.findByCode(code);
        if (node == null) {
            return new Verdict(false, "Nœud « " + code + " » introuvable.");
        }
        NomenclatureLevel placed = scheme.levelOfCode(code);
        if (placed == null || placed.rank == 0) {
            return new Verdict(false, "Nœud « " + code
                    + " » : un nœud de premier niveau n'a pas de parent à changer.");
        }
        NomenclatureLevel above = scheme.levelAt(placed.rank - 1);
        if (above == null || parentCode.length() != above.codeLength) {
            return new Verdict(false, "Nœud « " + code + " » : il ne peut être déposé que "
                    + "sur un nœud de niveau " + (placed.rank - 1) + ".");
        }
        if (ProductFamily.findByCode(parentCode) == null) {
            return new Verdict(false, "Nœud « " + parentCode + " » introuvable.");
        }
        if (parentCode.startsWith(code)) {
            return new Verdict(false, "Nœud « " + code
                    + " » : on ne peut pas le déposer sous lui-même.");
        }
        if (parentCode.equals(code.substring(0, above.codeLength))) {
            return new Verdict(false, "Nœud « " + code + " » est déjà sous « "
                    + parentCode + " ».");
        }
        List<ProductFamily> all = ProductFamily.listInNomenclature(scheme);
        List<ProductFamily> subtree = subtreeOf(all, code);
        String wanted = code.substring(above.codeLength);
        String segment = freeSegment(all, subtree, code, parentCode, wanted);
        if (segment == null) {
            return new Verdict(false, "Nœud « " + code + " » : aucun numéro libre sous « "
                    + parentCode + " ».");
        }
        String moved = parentCode + segment;
        int recoded = relocate(subtree, code, moved);
        LOGGER.infof("Nœud %s déplacé en %s (%d nœud(s) recodés)", code, moved, recoded);
        String renumbered = segment.equals(wanted) ? ""
                : " — numéro « " + wanted + " » déjà pris, renuméroté en « " + segment + " »";
        return new Verdict(true, "Nœud « " + code + " » déplacé en « " + moved + " »"
                + renumbered + " — " + recoded + " nœud(s) recodé(s).", moved);
    }

    /**
     * Gives a node a new number WITHOUT changing its parent — the free part of
     * its code, the part the prefix does not impose.
     * <p>
     * The counterpart of the renumbering a drop may do on its own: the machine
     * picks a free number so the move is never refused, and the operator
     * corrects it here if that number is not the one the shop wants. What is
     * NOT negotiable is the prefix: it names the parent, so the segment alone
     * is posted and the prefix is put back here.
     * <p>
     * A number ASKED FOR is never silently changed: unlike a drop, this is a
     * precise request, and answering it with a different number would leave
     * the operator correcting a correction.
     *
     * @param scheme the scheme being edited, never null
     * @param code the node's current code
     * @param segment the new free part, without the parent's prefix
     * @return what was done, or why it was refused
     */
    private Verdict recodeNode(Nomenclature scheme, String code, String segment) {
        ProductFamily node = code.isEmpty() ? null : ProductFamily.findByCode(code);
        if (node == null) {
            return new Verdict(false, "Nœud « " + code + " » introuvable.");
        }
        NomenclatureLevel placed = scheme.levelOfCode(code);
        if (placed == null) {
            return new Verdict(false, "Nœud « " + code + " » : aucun niveau de "
                    + scheme.code + " n'a des codes de " + code.length() + " caractères.");
        }
        NomenclatureLevel above = placed.rank == 0 ? null : scheme.levelAt(placed.rank - 1);
        int fixed = above == null ? 0 : above.codeLength;
        int width = placed.codeLength - fixed;
        if (segment.length() != width) {
            return new Verdict(false, "Nœud « " + code + " » : le numéro compte "
                    + width + " caractère(s), pas " + segment.length() + ".");
        }
        String recoded = code.substring(0, fixed) + segment;
        if (recoded.equals(code)) {
            return new Verdict(false, "Nœud « " + code + " » porte déjà ce numéro.");
        }
        List<ProductFamily> all = ProductFamily.listInNomenclature(scheme);
        List<ProductFamily> subtree = subtreeOf(all, code);
        if (!fits(all, subtree, code, recoded)) {
            return new Verdict(false, "Le code « " + recoded + " » est déjà pris.");
        }
        int moved = relocate(subtree, code, recoded);
        LOGGER.infof("Nœud %s renuméroté en %s (%d nœud(s) recodés)", code, recoded, moved);
        return new Verdict(true, "Nœud « " + code + " » renuméroté en « " + recoded
                + " » — " + moved + " nœud(s) recodé(s).", recoded);
    }

    /**
     * A node and everything hanging under it, the node first.
     *
     * @param all every node of the scheme
     * @param code the node's code
     * @return the node and its descent
     */
    private List<ProductFamily> subtreeOf(List<ProductFamily> all, String code) {
        List<ProductFamily> subtree = new ArrayList<>();
        for (ProductFamily candidate : all) {
            if (candidate.code != null && candidate.code.startsWith(code)) {
                subtree.add(candidate);
            }
        }
        return subtree;
    }

    /**
     * The number a node may take under a parent: the one it already carries if
     * that one is free there, otherwise the FIRST free one.
     * <p>
     * A drop that refused because the number was taken asked the operator to
     * solve a problem they did not create and cannot see: the codes of the
     * target branch are not on their screen. The machine has the whole list,
     * so it picks, and says which number it picked.
     * <p>
     * The scan runs in memory over the scheme read once — a query per tried
     * number would be ten thousand queries on a four-character level.
     *
     * @param all every node of the scheme
     * @param subtree the node and its descent, which are moving with it
     * @param code the node's current code
     * @param parentCode the code of the parent it is landing under
     * @param wanted the number it carries today
     * @return the number to take, or null when the level is full
     */
    private String freeSegment(List<ProductFamily> all, List<ProductFamily> subtree,
            String code, String parentCode, String wanted) {
        if (fits(all, subtree, code, parentCode + wanted)) {
            return wanted;
        }
        int width = wanted.length();
        long ceiling = (long) Math.pow(10, width);
        for (long tried = 0; tried < ceiling; tried++) {
            String segment = String.format("%0" + width + "d", tried);
            if (fits(all, subtree, code, parentCode + segment)) {
                return segment;
            }
        }
        return null;
    }

    /**
     * Whether a whole subtree can be re-coded onto a candidate code without
     * landing on a node that is not part of it.
     *
     * @param all every node of the scheme
     * @param subtree the node and its descent, which are moving with it
     * @param code the node's current code
     * @param candidate the code the node would take
     * @return true when nothing standing is in the way
     */
    private boolean fits(List<ProductFamily> all, List<ProductFamily> subtree,
            String code, String candidate) {
        List<String> wanted = new ArrayList<>();
        for (ProductFamily moving : subtree) {
            wanted.add(candidate + moving.code.substring(code.length()));
        }
        for (String asked : wanted) {
            for (ProductFamily standing : all) {
                if (asked.equals(standing.code) && !standing.code.startsWith(code)) {
                    return false;
                }
            }
        }
        // The scheme read in memory says nothing about the OTHER schemes, and
        // a family code is unique across all of them: what survives the cheap
        // scan is confirmed against the base before it is taken. Only what
        // survives — a query per tried number would be ten thousand queries.
        for (String asked : wanted) {
            ProductFamily standing = ProductFamily.findByCode(asked);
            if (standing != null && !standing.code.startsWith(code)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes a subtree onto its new code, the node and its whole descent.
     *
     * @param subtree the node and its descent
     * @param code the node's current code
     * @param moved the code the node now carries
     * @return how many nodes were re-coded
     */
    private int relocate(List<ProductFamily> subtree, String code, String moved) {
        for (ProductFamily candidate : subtree) {
            candidate.code = moved + candidate.code.substring(code.length());
            candidate.persist();
        }
        return subtree.size();
    }

    /**
     * Describes one node the way the tree reads it.
     *
     * @param node the node
     * @param openable whether anything hangs under it
     * @return the JSON object
     */
    private String nodeJson(ProductFamily node, boolean openable) {
        return "{\"code\":\"" + escape(node.code) + "\",\"label\":\""
                + escape(node.description) + "\",\"level\":"
                + (node.level == null ? "null" : node.level)
                + ",\"openable\":" + openable + "}";
    }

    /**
     * Whether any node of a list hangs under a code.
     *
     * @param below the nodes of the level underneath
     * @param code the node code
     * @return true when at least one starts with it
     */
    private boolean hasPrefix(List<ProductFamily> below, String code) {
        for (ProductFamily node : below) {
            if (node.code != null && node.code.startsWith(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a node answers what was typed, by code or by label.
     *
     * @param node the node
     * @param needle the lower-cased search text
     * @return true when either carries it
     */
    private boolean matches(ProductFamily node, String needle) {
        return (node.code != null && node.code.toLowerCase().contains(needle))
                || (node.description != null
                        && node.description.toLowerCase().contains(needle));
    }

    /**
     * The readable path of a node: its ancestors' labels, read off its own
     * code, then its own.
     *
     * @param scheme the scheme the node belongs to
     * @param node the node
     * @return the path, separated by a chevron
     */
    private String pathOf(Nomenclature scheme, ProductFamily node) {
        StringBuilder path = new StringBuilder();
        for (NomenclatureLevel level : scheme.levels()) {
            if (node.code == null || level.codeLength >= node.code.length()) {
                break;
            }
            ProductFamily ancestor = ProductFamily.findByCode(
                    node.code.substring(0, level.codeLength));
            path.append(ancestor == null ? node.code.substring(0, level.codeLength)
                    : ancestor.description).append(" › ");
        }
        return path.append(node.description == null ? "" : node.description).toString();
    }

    /**
     * Escapes a value for the JSON bodies above.
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
     * Renames the levels of a scheme — the one thing the screen writes.
     * <p>
     * The published file does not carry the level names, and every report
     * names them. Renaming never moves a node: a node is placed by its code
     * length, which this form cannot touch.
     *
     * @param form the posted form (scheme, then one label per level rank)
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/levels")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveLevels(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveLevels");
        String schemeCode = form.getFirst("scheme");
        Nomenclature scheme = schemeCode == null ? null : Nomenclature.findByCode(schemeCode);
        if (scheme == null) {
            LOGGER.info("Exiting method saveLevels");
            return redirect(null, "Nomenclature introuvable.", false);
        }
        int renamed = 0;
        for (NomenclatureLevel level : scheme.levels()) {
            String posted = form.getFirst("level-" + level.rank);
            if (posted != null && !posted.trim().isEmpty()) {
                level.label = posted.trim();
                level.persist();
                renamed++;
            }
        }
        LOGGER.info("Exiting method saveLevels");
        return redirect(scheme.code, renamed + " niveau(x) renommé(s).", true);
    }

    /**
     * The scheme to show: the requested one, else the first of the list.
     *
     * @param schemes every scheme
     * @param requested the requested code, possibly null or unknown
     * @return the scheme to show, or null when none exists
     */
    private Nomenclature resolveScheme(List<Nomenclature> schemes, String requested) {
        for (Nomenclature candidate : schemes) {
            if (candidate.code.equals(requested)) {
                return candidate;
            }
        }
        return schemes.isEmpty() ? null : schemes.get(0);
    }

    /**
     * The level being listed: the one below the opened node, or the top.
     *
     * @param scheme the scheme shown, possibly null
     * @param parent the opened node code, empty for the top
     * @return the 0-based level to list
     */
    private int levelOf(Nomenclature scheme, String parent) {
        if (scheme == null || parent.isEmpty()) {
            return 0;
        }
        NomenclatureLevel placed = scheme.levelOfCode(parent);
        return placed == null ? 0 : placed.rank + 1;
    }

    /**
     * The nodes to list: those of the level, kept to the ones hanging under the
     * opened node.
     *
     * @param scheme the scheme shown, possibly null
     * @param parent the opened node code, empty for the top
     * @param level the level to list
     * @return the nodes, in code order
     */
    private List<ProductFamily> childrenOf(Nomenclature scheme, String parent, int level) {
        if (scheme == null) {
            return List.of();
        }
        List<ProductFamily> all = ProductFamily.listAtLevel(scheme, level);
        if (parent.isEmpty()) {
            return all;
        }
        List<ProductFamily> kept = new ArrayList<>();
        for (ProductFamily node : all) {
            if (node.code != null && node.code.startsWith(parent)) {
                kept.add(node);
            }
        }
        return kept;
    }

    /**
     * How many nodes of the level below hang under one node.
     *
     * @param scheme the scheme shown
     * @param code the node code
     * @param level the node's level
     * @return the child count
     */
    private long countChildren(Nomenclature scheme, String code, int level) {
        long count = 0;
        for (ProductFamily node : ProductFamily.listAtLevel(scheme, level + 1)) {
            if (node.code != null && node.code.startsWith(code)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The name of a level, or a generic one when the scheme does not go there.
     *
     * @param scheme the scheme shown, possibly null
     * @param level the 0-based level
     * @return the level name
     */
    private String levelLabel(Nomenclature scheme, int level) {
        NomenclatureLevel declared = scheme == null ? null : scheme.levelAt(level);
        return declared == null || declared.label == null || declared.label.isBlank()
                ? "Niveau " + level : declared.label;
    }

    /**
     * Whether the level being listed is the point of sale's own.
     * <p>
     * It decides what the screen SAYS, never what it refuses: a national level
     * is overwritten by the next integration, label included, and an operator
     * correcting one between two integrations must know that rather than be
     * stopped.
     *
     * @param scheme the scheme shown, possibly null
     * @param level the 0-based level
     * @return true when the point of sale owns that level
     */
    private boolean isCustomLevel(Nomenclature scheme, int level) {
        NomenclatureLevel declared = scheme == null ? null : scheme.levelAt(level);
        return declared != null && declared.custom;
    }

    /**
     * The code length the level being listed expects, so the creation form can
     * state it and the browser can hold the operator to it.
     *
     * @param scheme the scheme shown, possibly null
     * @param level the 0-based level
     * @return the cumulative code length, or 0 when the scheme does not go there
     */
    private int codeLengthAt(Nomenclature scheme, int level) {
        NomenclatureLevel declared = scheme == null ? null : scheme.levelAt(level);
        return declared == null ? 0 : declared.codeLength;
    }

    /**
     * The path back up, built by truncating the opened code at each level.
     *
     * @param scheme the scheme shown, possibly null
     * @param parent the opened node code, empty for the top
     * @return the crumbs, the root first
     */
    private List<Crumb> crumbs(Nomenclature scheme, String parent) {
        List<Crumb> crumbs = new ArrayList<>();
        crumbs.add(new Crumb("", "Racine"));
        if (scheme == null || parent.isEmpty()) {
            return crumbs;
        }
        for (NomenclatureLevel level : scheme.levels()) {
            if (level.codeLength > parent.length()) {
                break;
            }
            String code = parent.substring(0, level.codeLength);
            ProductFamily node = ProductFamily.findByCode(code);
            crumbs.add(new Crumb(code, node == null ? code : code + " — " + node.description));
        }
        return crumbs;
    }

    /**
     * Builds the 303 redirect carrying the one-shot notice.
     *
     * @param schemeCode the scheme to come back to, or null
     * @param notice the message to display
     * @param ok whether the message reports a success
     * @return the redirect response
     */
    private Response redirect(String schemeCode, String notice, boolean ok) {
        StringBuilder target = new StringBuilder("/admin/nomenclatures?notice=");
        target.append(java.net.URLEncoder.encode(notice, java.nio.charset.StandardCharsets.UTF_8));
        target.append("&noticeOk=").append(ok);
        if (schemeCode != null) {
            target.append("&scheme=").append(
                    java.net.URLEncoder.encode(schemeCode, java.nio.charset.StandardCharsets.UTF_8));
        }
        return Response.seeOther(URI.create(target.toString())).build();
    }
}
