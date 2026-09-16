package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
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
 *       description, flags and member articles;</li>
 *   <li>BO-03-01-04 / BO-03-01-16 — add articles to a group, one by one or in
 *       bulk, by exact code (EAN, internal code or PLU), by EAN range or by
 *       copying another group's members;</li>
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
    static final String LEVEL_ZERO_CODE = "NIVEAU-0";

    /** The groups administration template. */
    @Inject
    @Location("admin-article-groups")
    Template adminArticleGroups;

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
    @RolesAllowed("ADMIN")
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk,
                                 @QueryParam("edit") Long editId) {
        LOGGER.info("Entering method list with notice: " + notice + ", noticeOk: " + noticeOk + ", editId: " + editId);
        List<ProductFamily> families = ProductFamily.<ProductFamily>find("order by code").list();
        List<GroupRow> rows = new ArrayList<>();
        for (ProductFamily family : families) {
            ProductFamily parent = parentOf(family.id);
            rows.add(new GroupRow(family.id, family.code, family.description,
                    parent == null ? null : parent.code, family.products.size()));
        }
        ProductFamily selected = editId == null ? null : ProductFamily.<ProductFamily>findById(editId);
        List<Product> members = selected == null ? List.of() : sortedMembers(selected);
        LOGGER.info("Exiting method list");
        return adminArticleGroups.data("groups", rows)
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
    @RolesAllowed("ADMIN")
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
    @RolesAllowed("ADMIN")
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
     * Duplicates a group into a new code, copying its description, flags and
     * member articles (the sub-tree is not copied — the new group is created at
     * root level).
     *
     * @param form the posted form (sourceId and newCode required)
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/duplicate")
    @RolesAllowed("ADMIN")
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
        if (ProductFamily.count("code", newCode) > 0) {
            LOGGER.info("Exiting method duplicate");
            return redirect(source.id, "Un groupe porte déjà le code « " + newCode + " ».", false);
        }
        ProductFamily copy = new ProductFamily();
        copy.code = newCode;
        copy.description = source.description;
        copy.flags = source.flags;
        copy.products.addAll(source.products);
        copy.persist();
        LOGGER.info("Exiting method duplicate");
        return redirect(copy.id, "Groupe « " + source.code + " » dupliqué en « " + newCode + " ».", true);
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
    @RolesAllowed("ADMIN")
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
    @RolesAllowed("ADMIN")
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
     * Removes an article from a group's members (the article itself is
     * untouched, only its membership in this group is dropped).
     *
     * @param form the posted form (id of the group, productId of the article)
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/articles/remove")
    @RolesAllowed("ADMIN")
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
    @RolesAllowed("ADMIN")
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
     * whitespace/comma/semicolon token), an inclusive EAN range, every member of a
     * named source group, and a case-insensitive label (commercial name) match.
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
