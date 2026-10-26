package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.setting.TouchGroupSetting;
import com.intermarche.edge.service.PosSettingsService;
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
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The CAISSE TOUCHES administration screen ({@code /admin/touches}), Lot 5F —
 * the back-office surface that decides what the cashier sees on the group-touch
 * grid of the SAISIE DIRECTE screen. Every operation runs on one admin cycle
 * (POST&nbsp;→&nbsp;303&nbsp;→&nbsp;notice) and reaches the registers at the
 * next tirage (the touch attributes ride the FAMILIES domain, the picture the
 * PRODUCTS domain):
 * <ul>
 *   <li>BO-03-01-07 — pin or unpin a group so it stays shown whatever the
 *       navigation, bounded to {@value #MAX_PINNED} pinned groups;</li>
 *   <li>BO-03-01-08 — set a group's touch size (SMALL / NORMAL / LARGE);</li>
 *   <li>BO-03-01-11 — set a group's custom rank, honoured by the CUSTOM order;</li>
 *   <li>BO-03-01-13 — set a group's sales volume, honoured by the VOLUME order;</li>
 *   <li>BO-03-01-15 — attach a picture to an article, one by one;</li>
 *   <li>BO-03-01-24 — import article pictures in bulk, each resized on the fly by
 *       {@link ImageResizeService}.</li>
 * </ul>
 * The display ORDER mode (BO-03-01-10/11/13) and the number of touches PER PAGE
 * (BO-03-01-06) are single-valued parameters administered on the generic
 * {@code /admin/settings} page ({@code touch.display-order},
 * {@code touch.groups-per-page}); this screen echoes their current effective
 * value so the administrator sees the mode its ranks feed into. Access is
 * reserved to ADMIN, like every article-area screen.
 */
@Path("/admin/touches")
public class AdminTouchResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminTouchResource.class);

    /** The maximum number of groups that may be pinned at once (BO-03-01-07). */
    static final int MAX_PINNED = 4;

    /** The allowed touch sizes (BO-03-01-08). */
    private static final List<String> SIZES = List.of("SMALL", "NORMAL", "LARGE");

    /** The caisse-touches administration template. */
    @Inject
    @Location("admin-touches")
    Template adminTouches;

    /** The catalog and store of the single-valued touch parameters. */
    @Inject
    PosSettingsService posSettingsService;

    /** The sales volumes counted on the registers' sales (BO-03-01-13). */
    @Inject
    com.intermarche.edge.service.SalesVolumeService salesVolumeService;

    /** The picture resizer for the single and bulk image imports. */
    @Inject
    ImageResizeService imageResizeService;

    /**
     * Renders the group list with its touch configuration, the effective order
     * mode and page size, and the pinned-group count.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the administration page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.TOUCH)
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list with notice: " + notice + ", noticeOk: " + noticeOk);
        List<ProductFamily> families = ProductFamily.<ProductFamily>find("order by code").list();
        Map<String, TouchGroupSetting> settings = TouchGroupSetting.byFamilyCode();
        List<TouchRow> rows = new ArrayList<>();
        int pinnedCount = 0;
        for (ProductFamily family : families) {
            TouchGroupSetting setting = TouchGroupSetting.orDefaults(settings, family.code);
            rows.add(new TouchRow(family.id, family.code, family.description, setting.pinned,
                    setting.buttonSize, setting.displayOrder, setting.salesVolume,
                    setting.islandCodes, setting.terminalIds, setting.tpvTypes,
                    setting.labels));
            if (setting.pinned) {
                pinnedCount++;
            }
        }
        LOGGER.info("Exiting method list");
        return adminTouches.data("groups", rows)
                .data("islands", com.intermarche.edge.domain.store.CheckoutIsland
                        .list("order by code"))
                .data("points", com.intermarche.edge.domain.store.CheckoutPoint.listAllOrdered())
                .data("tpvTypes", com.intermarche.edge.domain.store.TpvType.values())
                .data("orderMode", posSettingsService.touchDisplayOrder())
                .data("perPage", posSettingsService.touchGroupsPerPage())
                .data("articleSortVolume", posSettingsService.touchArticleSortVolume())
                .data("salesVolumeDays", posSettingsService.touchSalesVolumeDays())
                .data("pinnedCount", pinnedCount)
                .data("maxPinned", MAX_PINNED)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Updates a group's touch size, custom rank and sales volume (BO-03-01-08,
     * BO-03-01-11, BO-03-01-13).
     *
     * @param form the posted form (id required; size, displayOrder, salesVolume)
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/config")
    @Authenticated
    @FeatureAccess(value = Feature.TOUCH, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveConfig(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveConfig with form: " + form);
        Long id = parseLong(form.getFirst("id"));
        ProductFamily family = id == null ? null : ProductFamily.<ProductFamily>findById(id);
        if (family == null) {
            LOGGER.info("Exiting method saveConfig");
            return redirect("Groupe introuvable.", false);
        }
        String size = trimmed(form.getFirst("buttonSize")).toUpperCase();
        if (!SIZES.contains(size)) {
            LOGGER.info("Exiting method saveConfig");
            return redirect("Taille de touche invalide pour « " + family.code + " ».", false);
        }
        Integer order = parseNonNegativeInt(form.getFirst("displayOrder"));
        if (order == null) {
            LOGGER.info("Exiting method saveConfig");
            return redirect("Ordre personnalisé invalide — entier positif attendu.", false);
        }
        Long volume = parseNonNegativeLong(form.getFirst("salesVolume"));
        if (volume == null) {
            LOGGER.info("Exiting method saveConfig");
            return redirect("Volume de vente invalide — entier positif attendu.", false);
        }
        TouchGroupSetting setting = settingFor(family.code);
        setting.buttonSize = size;
        setting.displayOrder = order;
        setting.salesVolume = volume;
        // BO-03-01-21/-22/-23: WHERE the group is drawn. Nothing ticked is not
        // "nowhere" but "everywhere", which is what every group did before the
        // scope existed — so a shop that never opens this part keeps its grid.
        setting.islandCodes = joined(form.get("islandCodes"));
        setting.terminalIds = joined(form.get("terminalIds"));
        setting.tpvTypes = joined(form.get("tpvTypes"));
        // BO-03-01-19: the group's wording in the other languages the store
        // serves, as lang=libellé pairs. Blank becomes null, so a group named
        // in one language only carries nothing rather than an empty map.
        String labels = trimmed(form.getFirst("labels"));
        setting.labels = labels.isEmpty() ? null : labels;
        setting.persist();
        LOGGER.info("Exiting method saveConfig");
        return redirect("Touche du groupe « " + family.code + " » mise à jour.", true);
    }

    /**
     * Recomputes now the sales volumes of the articles and of the groups from
     * the sales the registers pushed (BO-03-01-13), rather than waiting for the
     * end of period; the registers take them at their next pull.
     *
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/sales-volume")
    @Authenticated
    @FeatureAccess(value = Feature.TOUCH, option = CrudOption.UPDATE)
    public Response refreshSalesVolume() {
        LOGGER.info("Entering method refreshSalesVolume");
        int sold = salesVolumeService.refresh(com.intermarche.edge.domain.util.DateTimeProvider.now().toLocalDate());
        LOGGER.info("Exiting method refreshSalesVolume");
        return redirect("Volumes de vente recalculés : " + sold + " article(s) vendu(s) sur la période.", true);
    }

    /**
     * Pins or unpins a group (BO-03-01-07): pinning is refused once
     * {@value #MAX_PINNED} groups are already pinned; unpinning is always
     * allowed.
     *
     * @param form the posted form carrying the id
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/pin")
    @Authenticated
    @FeatureAccess(value = Feature.TOUCH, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response togglePin(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method togglePin with form: " + form);
        Long id = parseLong(form.getFirst("id"));
        ProductFamily family = id == null ? null : ProductFamily.<ProductFamily>findById(id);
        if (family == null) {
            LOGGER.info("Exiting method togglePin");
            return redirect("Groupe introuvable.", false);
        }
        TouchGroupSetting setting = settingFor(family.code);
        if (!setting.pinned && TouchGroupSetting.countPinned() >= MAX_PINNED) {
            LOGGER.info("Exiting method togglePin");
            return redirect("Maximum " + MAX_PINNED + " groupes épinglés — désépinglez-en un d'abord.", false);
        }
        setting.pinned = !setting.pinned;
        setting.persist();
        String state = setting.pinned ? "épinglé" : "désépinglé";
        LOGGER.info("Exiting method togglePin");
        return redirect("Groupe « " + family.code + " » " + state + ".", true);
    }

    /**
     * Folds the values ticked for one scope into the administered string
     * (BO-03-01-21/-22/-23).
     *
     * <p>Null when nothing was ticked, because the COLUMN's empty value is what
     * means "every island", and an empty string that is not null would be a
     * scope naming nothing — which would hide the group everywhere.
     *
     * @param values the posted values, possibly null or empty
     * @return the values joined by {@code ;}, or null when none was ticked
     */
    private String joined(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            String trimmed = value == null ? "" : value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(';');
            }
            joined.append(trimmed);
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    /**
     * Returns the touch row of a group, creating an unwritten one carrying the
     * defaults when the group has never been configured.
     * <p>
     * A group is configured the first time someone touches it, not when the
     * nomenclature is imported: a referential of forty thousand families would
     * otherwise carry forty thousand rows of defaults that say nothing.
     *
     * @param familyCode the group code
     * @return the row to write into, persisted or not yet
     */
    private TouchGroupSetting settingFor(String familyCode) {
        TouchGroupSetting setting = TouchGroupSetting.findByFamilyCode(familyCode);
        return setting == null ? TouchGroupSetting.defaults(familyCode) : setting;
    }

    /**
     * Attaches a picture to one or several articles (BO-03-01-15 single,
     * BO-03-01-24 bulk): the parallel {@code imgCode}/{@code imgData} fields are
     * matched by index, each picture resized before storage. Unresolved codes
     * and blank pictures are skipped and counted.
     *
     * @param form the posted form (parallel imgCode and imgData lists)
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/image")
    @Authenticated
    @FeatureAccess(value = Feature.TOUCH, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveImages(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveImages with form: " + form);
        List<String> codes = form.get("imgCode");
        List<String> images = form.get("imgData");
        if (codes == null || images == null || codes.isEmpty()) {
            LOGGER.info("Exiting method saveImages");
            return redirect("Aucune image à importer.", false);
        }
        int attached = 0;
        int skipped = 0;
        for (int i = 0; i < codes.size(); i++) {
            String code = trimmed(codes.get(i));
            String raw = i < images.size() ? images.get(i) : null;
            if (code.isEmpty() || raw == null || raw.isBlank()) {
                skipped++;
                continue;
            }
            Product product = resolveProduct(code);
            if (product == null) {
                skipped++;
                continue;
            }
            String resized;
            try {
                resized = imageResizeService.resizeToDataUri(raw);
            } catch (IllegalArgumentException e) {
                skipped++;
                continue;
            }
            product.imageData = resized;
            attached++;
        }
        LOGGER.info("Exiting method saveImages");
        return redirect(attached + " image(s) importée(s), " + skipped + " ignorée(s).", attached > 0);
    }

    /**
     * Clears the picture of an article (BO-03-01-15), reverting its touch to the
     * emoji icon.
     *
     * @param form the posted form carrying the article code
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/image/clear")
    @Authenticated
    @FeatureAccess(value = Feature.TOUCH, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response clearImage(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method clearImage with form: " + form);
        String code = trimmed(form.getFirst("code"));
        Product product = code.isEmpty() ? null : resolveProduct(code);
        if (product == null) {
            LOGGER.info("Exiting method clearImage");
            return redirect("Article introuvable.", false);
        }
        product.imageData = null;
        LOGGER.info("Exiting method clearImage");
        return redirect("Image de « " + product.name + " » supprimée.", true);
    }

    /**
     * Resolves one code to an article, trying the EAN, then the internal code,
     * then the PLU.
     *
     * @param code the code token
     * @return the matching article, or null when none matches
     */
    private Product resolveProduct(String code) {
        Product product = Product.findByEan(code);
        if (product != null) {
            return product;
        }
        product = Product.findByInternalCode(code);
        if (product != null) {
            return product;
        }
        return Product.findByPlu(code);
    }

    /**
     * Parses an id, tolerating a null, blank or malformed value.
     *
     * @param raw the raw id, or null
     * @return the parsed id, or null when absent or malformed
     */
    private Long parseLong(String raw) {
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
     * Parses a non-negative integer, mapping a null, blank, malformed or
     * negative value to null.
     *
     * @param raw the raw value, or null
     * @return the parsed value, or null when invalid
     */
    private Integer parseNonNegativeInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a non-negative long, mapping a null, blank, malformed or negative
     * value to null.
     *
     * @param raw the raw value, or null
     * @return the parsed value, or null when invalid
     */
    private Long parseNonNegativeLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed < 0 ? null : parsed;
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
     * Builds the 303 redirect back to the administration page with the outcome
     * notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/touches?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }

    /**
     * A row of the touches table: a group with its touch configuration, ready
     * for rendering. Fields are public for the Qute template.
     */
    public static class TouchRow {

        /** The group id. */
        public final Long id;

        /** The group's unique code. */
        public final String code;

        /** The group's description, or null. */
        public final String description;

        /** Whether the group is pinned. */
        public final boolean pinned;

        /** The group's touch size. */
        public final String buttonSize;

        /** The group's custom rank. */
        public final int displayOrder;

        /** The group's sales volume. */
        public final long salesVolume;

        /** The islands it is drawn on, codes split by ';', or null for all. */
        public final String islandCodes;

        /** The registers it is drawn on, ids split by ';', or null for all. */
        public final String terminalIds;

        /** The kinds of point of contact it is drawn on, or null for all. */
        public final String tpvTypes;

        /** The group's wording in other languages, as lang=libellé pairs (BO-03-01-19). */
        public final String labels;

        /**
         * Builds a touches row.
         *
         * @param id the group id
         * @param code the group code
         * @param description the group description, or null
         * @param pinned whether the group is pinned
         * @param buttonSize the touch size
         * @param displayOrder the custom rank
         * @param salesVolume the sales volume
         * @param islandCodes the islands it is drawn on, or null for all
         * @param terminalIds the registers it is drawn on, or null for all
         * @param tpvTypes the kinds of point of contact, or null for all
         * @param labels the wording in other languages, or null
         */
        public TouchRow(Long id, String code, String description, boolean pinned, String buttonSize,
                        int displayOrder, long salesVolume, String islandCodes, String terminalIds,
                        String tpvTypes, String labels) {
            this.id = id;
            this.code = code;
            this.description = description;
            this.pinned = pinned;
            this.buttonSize = buttonSize;
            this.displayOrder = displayOrder;
            this.salesVolume = salesVolume;
            this.islandCodes = islandCodes;
            this.terminalIds = terminalIds;
            this.tpvTypes = tpvTypes;
            this.labels = labels;
        }

        /**
         * Whether a value is one of those ticked for a scope, for the screen's
         * check boxes.
         *
         * @param scope the administered scope, possibly null
         * @param value the value the screen is drawing
         * @return true when the scope names that value
         */
        private static boolean ticked(String scope, String value) {
            if (scope == null || scope.isBlank() || value == null) {
                return false;
            }
            for (String allowed : scope.split(";", -1)) {
                if (allowed.trim().equals(value.trim())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether this group names that island (BO-03-01-21).
         *
         * @param code the island code the screen is drawing
         * @return true when the island is ticked
         */
        public boolean hasIsland(String code) {
            return ticked(islandCodes, code);
        }

        /**
         * Whether this group names that register (BO-03-01-22).
         *
         * @param terminalId the register the screen is drawing
         * @return true when the register is ticked
         */
        public boolean hasTerminal(String terminalId) {
            return ticked(terminalIds, terminalId);
        }

        /**
         * Whether this group names that kind of point of contact (BO-03-01-23).
         *
         * @param tpvType the kind the screen is drawing
         * @return true when the kind is ticked
         */
        public boolean hasTpvType(String tpvType) {
            return ticked(tpvTypes, tpvType);
        }
    }
}
