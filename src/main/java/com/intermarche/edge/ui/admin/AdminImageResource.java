package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.EchelonLevel;
import com.intermarche.edge.domain.setting.StoreImage;
import com.intermarche.edge.service.StoreImageService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The IMAGE administration screen ({@code BO-03-03-15},
 * {@code BO-10-03-09}).
 *
 * <p>The register printed ONE logo, a file on its classpath, so a group
 * running several countries, enseignes and concepts could not give them
 * different paper — and the requirement asks for at least ten. Here a logo
 * is a row posed at an echelon, and the closest echelon wins: a logo on the
 * PDV beats one on its enseigne, which beats one on its country, which beats
 * the group's default.
 *
 * <p>The SAME screen administers the loyalty-advantage visuals, because they
 * are the same object — an imported picture, posed at an echelon, carried to
 * the registers by the same domain. What tells them apart is the usage and,
 * for a visual, the advantage it illustrates.
 *
 * <p>Imports go through {@link ImageResizeService}, which bounds what a
 * picture costs in the snapshot; a logo is allowed a larger side than a
 * touch picture, because it is printed across the paper rather than drawn in
 * a key.
 */
@ApplicationScoped
@Path("/admin/images")
public class AdminImageResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminImageResource.class);

    /** The longest code this screen accepts, in characters. */
    static final int MAX_CODE = 40;

    /** The longest side, in pixels, an imported image is reduced to. */
    static final int MAX_DIMENSION = 512;

    /** The images template. */
    @Inject
    @Location("admin-images")
    Template adminImages;

    /** The picture resizer shared with the touch-picture import. */
    @Inject
    ImageResizeService imageResizeService;

    /** The resolver, so the screen can show WHICH image this store prints. */
    @Inject
    StoreImageService storeImageService;

    /**
     * One image as the screen lists it.
     */
    public static class ImageRow {

        /** The image's database id. */
        public Long id;

        /** The image code. */
        public String code;

        /** What the administrator calls it. */
        public String label;

        /** What it is for, as the enumeration names it. */
        public String usage;

        /** What the usage is called on screen. */
        public String usageLabel;

        /** The echelon it is posed at, or an empty string for the default. */
        public String level;

        /** The echelon code, or an empty string. */
        public String echelonCode;

        /** The advantage it illustrates, or an empty string. */
        public String advantageCode;

        /** The image itself, a data URI, or an empty string. */
        public String imageData;

        /** Whether the image is distributed. */
        public boolean active;

        /** Whether THIS store is the one that prints it. */
        public boolean resolved;
    }

    /**
     * Lists the administered images, saying which ones this store resolves to.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the images page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.STORE_IMAGE)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list");
        List<Long> resolved = new ArrayList<>();
        for (StoreImage image : storeImageService.resolvedImages()) {
            resolved.add(image.id);
        }
        List<ImageRow> rows = new ArrayList<>();
        for (StoreImage image : StoreImage.<StoreImage>find("order by usage, code").list()) {
            rows.add(row(image, resolved.contains(image.id)));
        }
        LOGGER.info("Exiting method list with " + rows.size() + " image(s)");
        return adminImages.data("images", rows)
                .data("usages", StoreImage.Usage.values())
                .data("levels", EchelonLevel.values())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Imports an image or writes one back ({@code BO-03-03-15},
     * {@code BO-10-03-09}).
     *
     * <p>The picture is OPTIONAL on a write-back: an administrator correcting
     * a label or moving a logo from an enseigne to a PDV should not have to
     * find the file again. It is mandatory on a creation — an image row with
     * no image prints nothing.
     *
     * @param form the posted form
     * @return a 303 back to the screen with the outcome
     */
    @POST
    @Path("/save")
    @Authenticated
    @FeatureAccess(value = Feature.STORE_IMAGE, option = CrudOption.CREATE, updateKey = "id")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save");
        Long id = AdminForms.id(form.getFirst("id"));
        String code = code(AdminForms.text(form, "code"));
        String label = AdminForms.text(form, "label");
        if (code == null || label == null) {
            LOGGER.info("Exiting method save: incomplete");
            return back("Le code et le libellé de l'image sont obligatoires.", false);
        }
        StoreImage existing = StoreImage.findByCode(code);
        if (existing != null && (id == null || !existing.id.equals(id))) {
            LOGGER.info("Exiting method save: duplicate code");
            return back("Ce code d'image est déjà pris.", false);
        }
        StoreImage image = id == null ? new StoreImage() : StoreImage.<StoreImage>findById(id);
        if (image == null) {
            LOGGER.info("Exiting method save: unknown image");
            return back("Image inconnue.", false);
        }
        StoreImage.Usage usage = usage(form.getFirst("usage"));
        EchelonLevel level = level(form.getFirst("level"));
        String echelonCode = AdminForms.text(form, "echelonCode");
        if (level != null && echelonCode == null) {
            LOGGER.info("Exiting method save: echelon without code");
            return back("Un échelon demande le code de l'échelon.", false);
        }
        if (usage == StoreImage.Usage.LOYALTY_ADVANTAGE
                && AdminForms.text(form, "advantageCode") == null) {
            LOGGER.info("Exiting method save: visual without advantage");
            return back("Un visuel d'avantage demande le code de l'avantage.", false);
        }
        String picture = resized(AdminForms.text(form, "imgData"));
        if (picture == null && id == null) {
            LOGGER.info("Exiting method save: no picture");
            return back("Une image neuve demande un fichier.", false);
        }
        image.code = code;
        image.label = label;
        image.usage = usage;
        image.level = level;
        image.echelonCode = level == null ? null : echelonCode;
        image.advantageCode = usage == StoreImage.Usage.LOYALTY_ADVANTAGE
                ? AdminForms.text(form, "advantageCode") : null;
        image.active = AdminForms.checkbox(form, "active");
        if (picture != null) {
            image.imageData = picture;
        }
        image.persist();
        LOGGER.infof("Image %s enregistrée", image.code);
        LOGGER.info("Exiting method save");
        return back(id == null ? "Image importée." : "Image enregistrée.", true);
    }

    /**
     * Deletes an image.
     *
     * @param form the posted form
     * @return a 303 back to the screen with the outcome
     */
    @POST
    @Path("/delete")
    @Authenticated
    @FeatureAccess(value = Feature.STORE_IMAGE, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete");
        Long id = AdminForms.id(form.getFirst("id"));
        StoreImage image = id == null ? null : StoreImage.<StoreImage>findById(id);
        if (image == null) {
            LOGGER.info("Exiting method delete: unknown image");
            return back("Image inconnue.", false);
        }
        image.delete();
        LOGGER.info("Exiting method delete");
        return back("Image supprimée.", true);
    }

    /**
     * Projects one image onto its screen row.
     *
     * @param image the image
     * @param resolved whether this store is the one that prints it
     * @return the row
     */
    private ImageRow row(StoreImage image, boolean resolved) {
        ImageRow row = new ImageRow();
        row.id = image.id;
        row.code = image.code;
        row.label = image.label;
        row.usage = image.usage == null ? "" : image.usage.name();
        row.usageLabel = image.usage == null ? "" : image.usage.getLabel();
        row.level = image.level == null ? "" : image.level.name();
        row.echelonCode = image.echelonCode == null ? "" : image.echelonCode;
        row.advantageCode = image.advantageCode == null ? "" : image.advantageCode;
        row.imageData = image.imageData == null ? "" : image.imageData;
        row.active = image.active;
        row.resolved = resolved;
        return row;
    }

    /**
     * Resizes an imported picture, an unreadable file answering null rather
     * than failing the screen.
     *
     * @param raw the posted data URI, possibly null
     * @return the resized PNG data URI, or null when nothing usable was posted
     */
    String resized(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return imageResizeService.resizeToDataUri(raw, MAX_DIMENSION);
        } catch (IllegalArgumentException e) {
            LOGGER.warnf("Fichier image illisible : %s", e.getMessage());
            return null;
        }
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
     * Reads the posted usage, an unknown or absent one answering the receipt
     * logo — the usage this screen was opened for.
     *
     * @param raw the posted value
     * @return the usage, never null
     */
    static StoreImage.Usage usage(String raw) {
        if (raw != null && !raw.isBlank()) {
            for (StoreImage.Usage candidate : StoreImage.Usage.values()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
        }
        return StoreImage.Usage.RECEIPT_LOGO;
    }

    /**
     * Reads the posted echelon level; absent, blank, unknown or one of the two
     * levels INSIDE the store answers null — the group's default. A logo is
     * the paper of a shop and not of a lane, so an island or a checkout point
     * is not an echelon an image is posed at.
     *
     * @param raw the posted value
     * @return the level, or null for the group's default
     */
    static EchelonLevel level(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String typed = raw.trim();
        if (EchelonLevel.COUNTRY.name().equalsIgnoreCase(typed)) {
            return EchelonLevel.COUNTRY;
        }
        if (EchelonLevel.ENSEIGNE.name().equalsIgnoreCase(typed)) {
            return EchelonLevel.ENSEIGNE;
        }
        if (EchelonLevel.PDV.name().equalsIgnoreCase(typed)) {
            return EchelonLevel.PDV;
        }
        return null;
    }

    /**
     * Builds the 303 back to this screen.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String notice, boolean ok) {
        return AdminForms.redirect("/admin/images", notice, ok);
    }
}
