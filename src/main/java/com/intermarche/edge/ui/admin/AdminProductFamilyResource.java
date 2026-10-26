package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.ui.journal.JournalPage;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The ARTICLE GROUPS administration screen ({@code /admin/article-groups}),
 * Lot 5C — the back-office surface over the existing {@link ProductFamily}
 * tree. It exposes, on a single admin cycle (POST&nbsp;→&nbsp;303&nbsp;→&nbsp;notice),
 * the group-tree operations the questionnaire requires:
 * <ul>
 *   <li>BO-03-01-01 — create a group, optionally under a parent, to any depth
 *       (the tree already supports the requested 0-to-4 nesting and beyond);</li>
 *   <li>BO-03-01-02 — rename, re-parent (attach a group under another existing
 *       group) and delete a group;</li>
 *   <li>BO-03-01-03 — duplicate a group into a new code, carrying its
 *       description, flags, touch configuration and member articles, under the
 *       same parents, its whole sub-tree duplicated with it;</li>
 *   <li>BO-03-01-04 / BO-03-01-16 — add articles to a group, one by one or in
 *       bulk, by exact code (EAN, internal code or PLU), by EAN or internal code
 *       range, by family (sub-families included), by label, by copying another
 *       group's members, or by importing an Excel or CSV list;</li>
 *   <li>BO-03-01-05 — add an article at level 0, i.e. directly onto the
 *       reserved {@value #LEVEL_ZERO_CODE} group so it needs no group touch;</li>
 *   <li>BO-03-01-17 — deactivate (and reactivate) a member article through its
 *       {@code active} flag so it stops appearing in the group listings without
 *       being deleted or its data re-sent.</li>
 * </ul>
 * This is administration only: no sale screen is touched here (the till
 * rendering of groups and keys is Lot 5F). Access is reserved to ADMIN, like
 * every article-area screen. The group tree is the {@link ProductFamily}
 * referential: {@code code} is its unique upsert key, and a family with no
 * parent sits at level 0.
 */
@Path("/admin/article-groups")
public class AdminProductFamilyResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminProductFamilyResource.class);

    /**
     * The reserved code of the level-0 group — the container of articles placed
     * directly on the till without a group touch (BO-03-01-05). Created on
     * demand the first time an article is added at level 0.
     */
    static final String LEVEL_ZERO_CODE = ProductFamily.LEVEL_ZERO_CODE;

    /** The separator between a duplicate's code and its sub-groups' own codes (BO-03-01-03). */
    static final String COPY_SEPARATOR = "-";

    /** The longest code a group may carry, the column's length. */
    static final int MAX_CODE_LENGTH = 50;

    /**
     * The article-list upload of a group (BO-03-01-16): the target group and
     * the file — an Excel workbook (.xlsx) or a CSV/text list, one article code
     * per cell or per token.
     */
    public static class ImportForm {

        /** The id of the group the articles are added to. */
        @jakarta.ws.rs.FormParam("id")
        @org.jboss.resteasy.annotations.providers.multipart.PartType(MediaType.TEXT_PLAIN)
        public String id;

        /** The uploaded list. */
        @jakarta.ws.rs.FormParam("file")
        @org.jboss.resteasy.annotations.providers.multipart.PartType(MediaType.APPLICATION_OCTET_STREAM)
        public java.io.InputStream file;

        /**
         * Builds an empty form, as the multipart reader requires.
         */
        public ImportForm() {
        }
    }

    /** The groups administration template. */
    @Inject
    @Location("admin-article-groups")
    Template adminArticleGroups;

    /**
     * Clamps a requested page number into the pages the result actually has, so
     * a stale link or a hand-typed number lands on the last page rather than on
     * an empty list.
     *
     * @param requested the 1-based page number asked for
     * @param total the number of groups
     * @return the page number to read, between 1 and the page count
     */
    private int clampPage(int requested, long total) {
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, JournalPage.pageCount(total, AdminForms.PAGE_SIZE));
    }

    /**
     * Renders the group tree as a flat, code-ordered list annotated with each
     * group's parent and member count, plus — when an {@code edit} id resolves —
     * the edit panel of the selected group with its member articles.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @param editId the id of the group whose edit panel to open, or null
     * @return the administration page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_GROUP)
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk,
                                 @QueryParam("page") @DefaultValue("1") int page,
                                 @QueryParam("edit") Long editId) {
        LOGGER.info("Entering method list with notice: " + notice + ", noticeOk: " + noticeOk + ", page: " + page + ", editId: " + editId);
        // The page is cut BEFORE the rows are built: each row asks the database
        // for its parent, so rendering used to cost one query per group of the
        // whole tree, not per group on screen.
        PanacheQuery<ProductFamily> query = ProductFamily.find("order by code");
        long total = query.count();
        int number = clampPage(page, total);
        List<GroupRow> rows = new ArrayList<>();
        for (ProductFamily family : query.page(number - 1, AdminForms.PAGE_SIZE).<ProductFamily>list()) {
            ProductFamily parent = parentOf(family.id);
            rows.add(new GroupRow(family.id, family.code, family.description,
                    parent == null ? null : parent.code, family.products.size()));
        }
        ProductFamily selected = editId == null ? null : ProductFamily.<ProductFamily>findById(editId);
        List<Product> members = selected == null ? List.of() : sortedMembers(selected);
        LOGGER.info("Exiting method list");
        return adminArticleGroups.data("page",
                        new JournalPage<>(rows, number, AdminForms.PAGE_SIZE, total))
                // The parent selector of the creation form needs EVERY group,
                // by construction: a dropdown showing one page cannot express a
                // parent that lives on another. Loaded without the per-row
                // parent lookup, which is what the page cut was for.
                .data("groups", ProductFamily.<ProductFamily>find("order by code").list())
                .data("baseQuery", editId == null ? "" : "edit=" + editId + "&")
                .data("selected", selected)
                .data("members", members)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Creates a group when no id is posted, or renames and optionally re-parents
     * an existing one otherwise. The code (the sync key) is set on creation and
     * never changed on update.
     *
     * @param form the posted form (id optional; code required on create;
     *             description and parentId optional)
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_GROUP, option = CrudOption.CREATE, updateKey = "id")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save with form: " + form);
        Long id = parseId(form.getFirst("id"));
        String description = blankToNull(form.getFirst("description"));
        Long parentId = parseId(form.getFirst("parentId"));
        if (id == null) {
            String code = trimmed(form.getFirst("code"));
            if (code.isEmpty()) {
                LOGGER.info("Exiting method save");
                return redirect(null, "Le code du groupe est obligatoire.", false);
            }
            if (ProductFamily.count("code", code) > 0) {
                LOGGER.info("Exiting method save");
                return redirect(null, "Un groupe porte déjà le code « " + code + " ».", false);
            }
            ProductFamily family = new ProductFamily();
            family.code = code;
            family.description = description;
            family.persist();
            if (parentId != null) {
                ProductFamily parent = ProductFamily.findById(parentId);
                if (parent == null) {
                    LOGGER.info("Exiting method save");
                    return redirect(null, "Groupe parent introuvable.", false);
                }
                parent.productFamilies.add(family);
            }
            LOGGER.info("Exiting method save");
            return redirect(family.id, "Groupe « " + code + " » créé.", true);
        }
        ProductFamily family = ProductFamily.findById(id);
        if (family == null) {
            LOGGER.info("Exiting method save");
            return redirect(null, "Groupe introuvable.", false);
        }
        family.description = description;
        if (parentId != null) {
            if (parentId.equals(family.id)) {
                LOGGER.info("Exiting method save");
                return redirect(family.id, "Un groupe ne peut pas être son propre parent.", false);
            }
            ProductFamily parent = ProductFamily.findById(parentId);
            if (parent == null) {
                LOGGER.info("Exiting method save");
                return redirect(family.id, "Groupe parent introuvable.", false);
            }
            ProductFamily current = parentOf(family.id);
            if (current != null) {
                current.productFamilies.remove(family);
            }
            parent.productFamilies.add(family);
        }
        LOGGER.info("Exiting method save");
        return redirect(family.id, "Groupe « " + family.code + " » mis à jour.", true);
    }

    /**
     * Deletes a group by id.
     *
     * @param form the posted form carrying the id
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/delete")
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_GROUP, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete with form: " + form);
        Long id = parseId(form.getFirst("id"));
        ProductFamily family = id == null ? null : ProductFamily.<ProductFamily>findById(id);
        if (family == null) {
            LOGGER.info("Exiting method delete");
            return redirect(null, "Groupe introuvable.", false);
        }
        String code = family.code;
        family.delete();
        LOGGER.info("Exiting method delete");
        return redirect(null, "Groupe « " + code + " » supprimé.", true);
    }

    /**
     * Duplicates a group into a new code (BO-03-01-03), reproducing its place in
     * the tree: the copy hangs under every parent the source hangs under, and
     * the source's whole sub-tree is duplicated with it, each sub-group under
     * the code {@code <new code>-<its own code>}. Every copy carries the
     * description, the flags, the member articles and the touch configuration
     * of its original.
     *
     * <p>Every derived code is checked BEFORE anything is written: a derived
     * code already taken, or longer than the column, refuses the whole
     * duplication rather than leaving half a tree.
     *
     * @param form the posted form (sourceId and newCode required)
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/duplicate")
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_GROUP, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response duplicate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method duplicate with form: " + form);
        Long sourceId = parseId(form.getFirst("sourceId"));
        ProductFamily source = sourceId == null ? null : ProductFamily.<ProductFamily>findById(sourceId);
        if (source == null) {
            LOGGER.info("Exiting method duplicate");
            return redirect(null, "Groupe à dupliquer introuvable.", false);
        }
        String newCode = trimmed(form.getFirst("newCode"));
        if (newCode.isEmpty()) {
            LOGGER.info("Exiting method duplicate");
            return redirect(source.id, "Le code du nouveau groupe est obligatoire.", false);
        }
        java.util.Map<ProductFamily, String> codes = new java.util.LinkedHashMap<>();
        planCopy(source, newCode, codes);
        for (String code : codes.values()) {
            if (code.length() > MAX_CODE_LENGTH) {
                LOGGER.info("Exiting method duplicate");
                return redirect(source.id, "Le code « " + code + " » dépasse 50 caractères.", false);
            }
            if (ProductFamily.count("code", code) > 0) {
                LOGGER.info("Exiting method duplicate");
                return redirect(source.id, "Un groupe porte déjà le code « " + code + " ».", false);
            }
        }
        java.util.Map<ProductFamily, ProductFamily> copies = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<ProductFamily, String> planned : codes.entrySet()) {
            copies.put(planned.getKey(), copyOf(planned.getKey(), planned.getValue()));
        }
        for (java.util.Map.Entry<ProductFamily, ProductFamily> copied : copies.entrySet()) {
            for (ProductFamily child : copied.getKey().productFamilies) {
                copied.getValue().productFamilies.add(copies.get(child));
            }
        }
        ProductFamily copy = copies.get(source);
        for (ProductFamily parent : ProductFamily.<ProductFamily>find(
                "select p from ProductFamily p join p.productFamilies c where c.id = ?1", source.id).list()) {
            parent.productFamilies.add(copy);
        }
        LOGGER.info("Exiting method duplicate");
        return redirect(copy.id, "Groupe « " + source.code + " » dupliqué en « " + newCode + " » avec "
                + (codes.size() - 1) + " sous-groupe(s).", true);
    }

    /**
     * Gives every group of a sub-tree the code its copy will carry: the root
     * the code typed, each descendant the code of its copied parent, a dash and
     * its own code. A group reached twice — the tree is a graph, one group may
     * hang under two others — is copied once.
     *
     * @param group the group to plan
     * @param code the code its copy carries
     * @param codes the plan being built, by original group
     */
    private void planCopy(ProductFamily group, String code, java.util.Map<ProductFamily, String> codes) {
        if (codes.containsKey(group)) {
            return;
        }
        codes.put(group, code);
        for (ProductFamily child : group.productFamilies) {
            planCopy(child, code + COPY_SEPARATOR + child.code, codes);
        }
    }

    /**
     * Creates the copy of one group: its description, flags and members, and
     * its touch configuration when it has one, every mandatory column set
     * before the row is persisted.
     *
     * @param original the group copied
     * @param code the code of the copy
     * @return the persisted copy
     */
    private ProductFamily copyOf(ProductFamily original, String code) {
        ProductFamily copy = new ProductFamily();
        copy.code = code;
        copy.description = original.description;
        copy.flags = original.flags;
        copy.products.addAll(original.products);
        copy.persist();
        com.intermarche.edge.domain.setting.TouchGroupSetting touch =
                com.intermarche.edge.domain.setting.TouchGroupSetting.findByFamilyCode(original.code);
        if (touch != null) {
            com.intermarche.edge.domain.setting.TouchGroupSetting copied =
                    new com.intermarche.edge.domain.setting.TouchGroupSetting();
            copied.familyCode = code;
            copied.pinned = touch.pinned;
            copied.buttonSize = touch.buttonSize;
            copied.displayOrder = touch.displayOrder;
            copied.salesVolume = touch.salesVolume;
            copied.islandCodes = touch.islandCodes;
            copied.terminalIds = touch.terminalIds;
            copied.tpvTypes = touch.tpvTypes;
            copied.labels = touch.labels;
            copied.persist();
        }
        return copy;
    }

    /**
     * Adds articles to a group, selecting them by exact code, EAN range and/or a
     * source group's members (BO-03-01-04 for one article, BO-03-01-16 for a
     * bulk list).
     *
     * @param form the posted form (id of the target group, plus selectors)
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/articles")
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_GROUP, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response addArticles(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method addArticles with form: " + form);
        Long id = parseId(form.getFirst("id"));
        ProductFamily family = id == null ? null : ProductFamily.<ProductFamily>findById(id);
        if (family == null) {
            LOGGER.info("Exiting method addArticles");
            return redirect(null, "Groupe introuvable.", false);
        }
        LOGGER.info("Exiting method addArticles");
        return attach(family, form);
    }

    /**
     * Adds articles at level 0 — directly onto the reserved
     * {@value #LEVEL_ZERO_CODE} group, created on demand — so they appear as a
     * till key without a group touch (BO-03-01-05).
     *
     * @param form the posted form carrying the article selectors
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/level-zero/articles")
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_GROUP, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response addAtLevelZero(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method addAtLevelZero with form: " + form);
        ProductFamily family = ProductFamily.findByCode(LEVEL_ZERO_CODE);
        if (family == null) {
            family = new ProductFamily();
            family.code = LEVEL_ZERO_CODE;
            family.description = "Articles niveau 0 (touches directes)";
            family.persist();
        }
        LOGGER.info("Exiting method addAtLevelZero");
        return attach(family, form);
    }

    /**
     * Adds to a group every article an uploaded list names (BO-03-01-16): an
     * Excel workbook — every cell of its first sheet — or a CSV or text list —
     * every token between separators — each read as an EAN, an internal code
     * or a PLU. A header or a code the catalog does not know is counted, not
     * refused: one unknown line must not cost the rest of the list. The
     * articles reach the registers with the group at their next pull.
     *
     * @param form the posted group id and file
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/import")
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_GROUP, option = CrudOption.UPDATE)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    public Response importArticles(@org.jboss.resteasy.annotations.providers.multipart.MultipartForm ImportForm form) {
        LOGGER.info("Entering method importArticles");
        Long id = form == null ? null : parseId(form.id);
        ProductFamily family = id == null ? null : ProductFamily.<ProductFamily>findById(id);
        if (family == null) {
            LOGGER.info("Exiting method importArticles");
            return redirect(null, "Groupe introuvable.", false);
        }
        List<String> tokens;
        try {
            tokens = ArticleListReader.read(form.file);
        } catch (java.io.IOException e) {
            LOGGER.info("Exiting method importArticles");
            return redirect(family.id, "Fichier illisible.", false);
        }
        Set<Product> found = new LinkedHashSet<>();
        int unknown = 0;
        for (String token : withoutHeading(tokens)) {
            Product product = resolveProduct(token);
            if (product == null) {
                unknown++;
            } else {
                found.add(product);
            }
        }
        if (found.isEmpty()) {
            LOGGER.info("Exiting method importArticles");
            return redirect(family.id, "Aucun article reconnu dans le fichier.", false);
        }
        family.products.addAll(found);
        LOGGER.info("Exiting method importArticles");
        return redirect(family.id, found.size() + " article(s) importé(s) dans le groupe « " + family.code
                + " », " + unknown + " code(s) inconnu(s).", true);
    }

    /**
     * Removes an article from a group's members (the article itself is
     * untouched, only its membership in this group is dropped).
     *
     * @param form the posted form (id of the group, productId of the article)
     * @return a 303 redirect carrying the outcome notice
     */
    /**
     * Drops the heading of an imported list ({@code BO-03-01-16}): the words a
     * spreadsheet or a CSV carries above its codes — « EAN », « Code article »
     * — are column titles, not article codes, and counting them as unknown
     * codes told the user of a code the file never held. Every token up to the
     * first one carrying a digit is the heading.
     *
     * @param tokens the tokens of the file, in order
     * @return the tokens from the first code on
     */
    private static List<String> withoutHeading(List<String> tokens) {
        int first = 0;
        while (first < tokens.size() && !tokens.get(first).matches(".*\\d.*")) {
            first++;
        }
        return tokens.subList(first, tokens.size());
    }

    @POST
    @Path("/articles/remove")
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_GROUP, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response removeArticle(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method removeArticle with form: " + form);
        Long id = parseId(form.getFirst("id"));
        ProductFamily family = id == null ? null : ProductFamily.<ProductFamily>findById(id);
        if (family == null) {
            LOGGER.info("Exiting method removeArticle");
            return redirect(null, "Groupe introuvable.", false);
        }
        Long productId = parseId(form.getFirst("productId"));
        Product product = productId == null ? null : Product.<Product>findById(productId);
        if (product == null) {
            LOGGER.info("Exiting method removeArticle");
            return redirect(family.id, "Article introuvable.", false);
        }
        family.products.remove(product);
        LOGGER.info("Exiting method removeArticle");
        return redirect(family.id, "Article « " + product.name + " » retiré du groupe.", true);
    }

    /**
     * Toggles a member article's {@code active} flag (BO-03-01-17): deactivating
     * hides it from the group listings while keeping its row and image, so a
     * later reactivation needs no data re-send.
     *
     * @param form the posted form (id of the group, productId of the article)
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/articles/toggle")
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_GROUP, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response toggleArticle(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method toggleArticle with form: " + form);
        Long id = parseId(form.getFirst("id"));
        Long productId = parseId(form.getFirst("productId"));
        Product product = productId == null ? null : Product.<Product>findById(productId);
        if (product == null) {
            LOGGER.info("Exiting method toggleArticle");
            return redirect(id, "Article introuvable.", false);
        }
        product.active = !product.active;
        String state = product.active ? "réactivé" : "désactivé";
        LOGGER.info("Exiting method toggleArticle");
        return redirect(id, "Article « " + product.name + " » " + state + ".", true);
    }

    /**
     * Collects the selected articles and adds them to the given group, reporting
     * how many were attached. Shared by the group and the level-0 endpoints.
     *
     * @param family the target group
     * @param form the posted form carrying the selectors
     * @return a 303 redirect carrying the outcome notice
     */
    private Response attach(ProductFamily family, MultivaluedMap<String, String> form) {
        Set<Product> selected = collectProducts(form);
        if (selected.isEmpty()) {
            return redirect(family.id, "Aucun article sélectionné.", false);
        }
        family.products.addAll(selected);
        return redirect(family.id, selected.size() + " article(s) ajouté(s) au groupe « "
                + family.code + " ».", true);
    }

    /**
     * Builds the set of articles designated by the posted selectors, deduplicated
     * and in insertion order: exact codes (EAN, internal code or PLU, one per
     * whitespace/comma/semicolon token), an inclusive EAN range, an inclusive
     * internal-code range, every member of a named source group, every article
     * of a family and of its sub-families, and a case-insensitive label
     * (commercial name) match (BO-03-01-04).
     *
     * @param form the posted form
     * @return the selected articles, never null, possibly empty
     */
    private Set<Product> collectProducts(MultivaluedMap<String, String> form) {
        Set<Product> result = new LinkedHashSet<>();
        String codes = form.getFirst("codes");
        if (codes != null && !codes.isBlank()) {
            for (String token : codes.trim().split("[\\s,;]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                Product product = resolveProduct(token);
                if (product != null) {
                    result.add(product);
                }
            }
        }
        String from = trimmed(form.getFirst("eanFrom"));
        String to = trimmed(form.getFirst("eanTo"));
        if (!from.isEmpty() && !to.isEmpty()) {
            result.addAll(Product.<Product>find("ean >= ?1 and ean <= ?2 order by ean", from, to).list());
        }
        String internalFrom = trimmed(form.getFirst("internalFrom"));
        String internalTo = trimmed(form.getFirst("internalTo"));
        if (!internalFrom.isEmpty() && !internalTo.isEmpty()) {
            result.addAll(Product.<Product>find("internalCode >= ?1 and internalCode <= ?2 order by internalCode",
                    internalFrom, internalTo).list());
        }
        String familyCode = trimmed(form.getFirst("familyCode"));
        if (!familyCode.isEmpty()) {
            ProductFamily family = ProductFamily.findByCode(familyCode);
            if (family != null) {
                result.addAll(familyArticles(family));
            }
        }
        String source = trimmed(form.getFirst("sourceFamily"));
        if (!source.isEmpty()) {
            ProductFamily family = ProductFamily.findByCode(source);
            if (family != null) {
                result.addAll(family.products);
            }
        }
        String label = trimmed(form.getFirst("label"));
        if (!label.isEmpty()) {
            result.addAll(Product.<Product>find("lower(name) like ?1 order by ean",
                    "%" + label.toLowerCase() + "%").list());
        }
        return result;
    }

    /**
     * Every article of a family and of its sub-families (BO-03-01-04).
     *
     * <p>A nomenclature node holds its descendants by CODE — a sous-famille's
     * code starts with its famille's — so they are read in one query over the
     * scheme. A shop's own group holds its sub-groups by edge, walked once each.
     *
     * @param family the family named
     * @return its articles and those of every family under it
     */
    private Set<Product> familyArticles(ProductFamily family) {
        Set<Product> articles = new LinkedHashSet<>();
        if (family.nomenclature != null) {
            articles.addAll(Product.<Product>find("select distinct p from ProductFamily pf join pf.products p"
                    + " where pf.nomenclature.id = ?1 and pf.code like ?2 order by p.ean",
                    family.nomenclature.id, family.code + "%").list());
            return articles;
        }
        java.util.Deque<ProductFamily> pending = new java.util.ArrayDeque<>(List.of(family));
        Set<ProductFamily> walked = new java.util.HashSet<>();
        while (!pending.isEmpty()) {
            ProductFamily current = pending.pop();
            if (walked.add(current)) {
                articles.addAll(current.products);
                pending.addAll(current.productFamilies);
            }
        }
        return articles;
    }

    /**
     * Resolves one code token to an article, trying the EAN, then the internal
     * code, then the PLU.
     *
     * @param token the code token
     * @return the matching article, or null when none matches
     */
    private Product resolveProduct(String token) {
        Product product = Product.findByEan(token);
        if (product != null) {
            return product;
        }
        product = Product.findByInternalCode(token);
        if (product != null) {
            return product;
        }
        return Product.findByPlu(token);
    }

    /**
     * Returns a group's member articles sorted by EAN for a stable rendering.
     *
     * @param family the group
     * @return the members ordered by EAN
     */
    private List<Product> sortedMembers(ProductFamily family) {
        List<Product> members = new ArrayList<>(family.products);
        members.sort(Comparator.comparing(product -> product.ean == null ? "" : product.ean));
        return members;
    }

    /**
     * Finds the single parent of a group — the family whose {@code productFamilies}
     * set contains it — or null when the group sits at level 0.
     *
     * @param childId the group id, or null
     * @return the parent group, or null when there is none
     */
    private ProductFamily parentOf(Long childId) {
        if (childId == null) {
            return null;
        }
        return ProductFamily.<ProductFamily>find(
                "select p from ProductFamily p join p.productFamilies c where c.id = ?1",
                childId).firstResult();
    }

    /**
     * Parses an id, tolerating a null, blank or malformed value.
     *
     * @param raw the raw id, or null
     * @return the parsed id, or null when absent or malformed
     */
    private Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Trims a posted field, mapping a null to an empty string.
     *
     * @param raw the posted value, or null
     * @return the trimmed value, never null
     */
    private String trimmed(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * Trims a posted field, mapping a null or blank value to null.
     *
     * @param raw the posted value, or null
     * @return the trimmed value, or null when absent or blank
     */
    private String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Builds the 303 redirect back to the administration page, re-opening the
     * given group's edit panel when an id is provided.
     *
     * @param editId the group whose edit panel to re-open, or null for the list
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(Long editId, String notice, boolean ok) {
        StringBuilder target = new StringBuilder("/admin/article-groups?");
        if (editId != null) {
            target.append("edit=").append(editId).append('&');
        }
        target.append("noticeOk=").append(ok).append("&notice=")
                .append(URLEncoder.encode(notice, StandardCharsets.UTF_8));
        return Response.seeOther(URI.create(target.toString())).build();
    }

    /**
     * A row of the group table: the group with its parent code and member count,
     * ready for rendering. Fields are public for the Qute template.
     */
    public static class GroupRow {

        /** The group id. */
        public final Long id;

        /** The group's unique code. */
        public final String code;

        /** The group's description, or null. */
        public final String description;

        /** The parent group's code, or null when the group is at level 0. */
        public final String parentCode;

        /** The number of member articles. */
        public final int memberCount;

        /**
         * Builds a group row.
         *
         * @param id the group id
         * @param code the group code
         * @param description the group description, or null
         * @param parentCode the parent code, or null at level 0
         * @param memberCount the member article count
         */
        public GroupRow(Long id, String code, String description, String parentCode, int memberCount) {
            this.id = id;
            this.code = code;
            this.description = description;
            this.parentCode = parentCode;
            this.memberCount = memberCount;
        }
    }
}
