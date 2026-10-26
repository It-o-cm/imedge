package com.intermarche.edge.domain.setting;

import com.intermarche.edge.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Objects;

/**
 * An IMAGE administered in the back office and distributed to the registers:
 * the receipt logo of an echelon ({@code BO-03-03-15}) and the visual of a
 * loyalty advantage ({@code BO-10-03-09}).
 *
 * <p>Until here the register printed ONE logo, a single file on its
 * classpath. A group that runs several countries, several enseignes and
 * several concepts could not give them different paper, and the requirement
 * asks for at least ten — so the logo becomes a row, posed at an echelon like
 * a parameter, and the closest echelon wins.
 *
 * <p>THE ECHELON STAYS ON THE NODE. A register never receives the chain, only
 * the image the chain resolved to, exactly as it receives resolved parameters
 * and never the {@code ECHELON_SETTINGS} rows: the store node knows its PDV,
 * its enseigne and its country, and a lane has no business holding the
 * group's organisation to print its own paper.
 *
 * <p>The image travels as a PNG {@code data:} URI, like the touch pictures of
 * the catalogue ({@code BO-03-01-24}), and for the same reason: a snapshot
 * that is pure text stays diffable, fingerprintable and loggable, and the
 * back office already knows how to resize one. The register stores it as
 * received: the printer rasterises it to dots the first time a document asks
 * for it, and a document rendered as a page uses the very same string.
 */
@Entity
@Table(name = "store_images",
        indexes = {
                @Index(name = "idx_store_image_code", columnList = "code"),
                @Index(name = "idx_store_image_usage", columnList = "usage")
        }
)
public class StoreImage extends BaseEntity {

    /**
     * What an image is FOR, which is also what resolves it.
     */
    public enum Usage {

        /** The logo printed at the head of the documents (BO-03-03-15). */
        RECEIPT_LOGO("Logo de ticket"),

        /** The visual of a loyalty advantage (BO-10-03-09). */
        LOYALTY_ADVANTAGE("Visuel d'avantage fidélité"),

        /** The monetique logo printed above every terminal receipt (BO-10-07-13). */
        EFT_LOGO("Logo monétique"),

        /** The contactless logo a contactless payment's receipt carries (BO-10-07-13). */
        CONTACTLESS_LOGO("Logo sans contact"),
        /**
         * The picture of a tare container (BO-02-03-39), shown on the weighing
         * screen beside the container whose code the image carries.
         */
        TARE_CONTAINER("Visuel de contenant de tare");

        /** What the administration screen calls this usage. */
        private final String label;

        /**
         * Builds a usage with its screen label.
         *
         * @param label what the administration screen calls it
         */
        Usage(String label) {
            this.label = label;
        }

        /**
         * What the administration screen calls this usage.
         *
         * @return the screen label, never null
         */
        public String getLabel() {
            return label;
        }
    }

    /** The image's code, unique, the key the distribution upserts on. */
    @Column(name = "code", nullable = false, unique = true, length = 40)
    @NotBlank
    public String code;

    /** What the administrator calls it. */
    @Column(name = "label", nullable = false, length = 80)
    @NotBlank
    public String label;

    /** What the image is for, never null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "usage", nullable = false, length = 24)
    public Usage usage;

    /**
     * The echelon the image is posed at, or null when it is the group's
     * default — the image a shop whose echelons name none still prints.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "level", length = 16)
    public EchelonLevel level;

    /** The code of the echelon at that level, or null for the default. */
    @Column(name = "echelon_code", length = 20)
    public String echelonCode;

    /**
     * The loyalty advantage this visual illustrates ({@code BO-10-03-09}):
     * the RULE code of the advantage, or its TYPE when the shop wants one
     * visual for a whole family. Null for a receipt logo.
     */
    @Column(name = "advantage_code", length = 40)
    public String advantageCode;

    /** The image itself, a PNG {@code data:} URI. */
    @Column(name = "image_data", columnDefinition = "TEXT")
    public String imageData;

    /** Whether the image is distributed at all, and so whether a register uses it. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /**
     * Finds an image by its code.
     *
     * @param code the code looked for, possibly null
     * @return the image, or null when no image carries that code
     */
    public static StoreImage findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return find("code", code.trim()).firstResult();
    }

    /**
     * Lists the ACTIVE images of one usage, in code order.
     *
     * @param usage the usage looked for
     * @return the matching images, possibly empty
     */
    public static List<StoreImage> findActiveByUsage(Usage usage) {
        return list("usage = ?1 and active = true order by code", usage);
    }

    /**
     * How CLOSE this image's echelon is to a shop, the larger winning: 3 for
     * its own PDV, 2 for its enseigne, 1 for its country, 0 for the group's
     * default, and -1 when the image is posed somewhere else entirely.
     *
     * <p>Sorting the candidates on one number is what makes "the closest
     * echelon wins" a single rule rather than three nested conditions, and it
     * is the same order the parameter resolution walks.
     *
     * <p>The two echelons INSIDE the store — the island and the checkout point
     * — are not offered here and answer -1: a logo is the paper of a shop, not
     * of a lane, and letting one register print another banner than the one
     * beside it would be a defect, not a feature.
     *
     * @param pdvNumber the shop's PDV number, possibly null
     * @param enseigneCode the shop's enseigne code, possibly null
     * @param countryCode the shop's country code, possibly null
     * @return the closeness, -1 when the image does not apply to that shop
     */
    public int closenessTo(String pdvNumber, String enseigneCode, String countryCode) {
        if (level == null) {
            return 0;
        }
        return switch (level) {
            case PDV -> matches(pdvNumber) ? 3 : -1;
            case ENSEIGNE -> matches(enseigneCode) ? 2 : -1;
            case COUNTRY -> matches(countryCode) ? 1 : -1;
            case ISLAND, CHECKOUT_POINT -> -1;
        };
    }

    /**
     * Whether this image's echelon code is the one given.
     *
     * @param candidate the shop's code at that level, possibly null
     * @return true when the two name the same echelon
     */
    private boolean matches(String candidate) {
        return echelonCode != null && candidate != null
                && echelonCode.trim().equalsIgnoreCase(candidate.trim());
    }

    /**
     * Hashes the business fields, for the change detection of the base
     * entity; the image itself is hashed too, so replacing the picture under
     * an unchanged name is seen as the change it is.
     *
     * @return the checksum of this row
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, label, usage, level, echelonCode, advantageCode, imageData,
                active);
    }
}
