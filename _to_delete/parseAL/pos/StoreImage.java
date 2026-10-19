package com.intermarche.pos.domain.setting;

import com.intermarche.pos.domain.BaseEntity;
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
 * An image the store node distributed to this register: the receipt logo
 * ({@code BO-03-03-15}) and the visuals of the loyalty advantages
 * ({@code BO-10-03-09}).
 *
 * <p>NO ECHELON HERE, and that is the whole point. The node resolves the
 * chain — PDV, enseigne, country, group default — and sends the winner, as it
 * does for the parameters: a lane prints the paper it was given and holds no
 * organisation chart to work out which one it is.
 *
 * <p>The image travels and is stored as a PNG {@code data:} URI. The printer
 * rasterises it to dots the first time a document asks for it, and a document
 * rendered as a page uses the very same string.
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
     * What an image is FOR.
     */
    public enum Usage {

        /** The logo printed at the head of the documents (BO-03-03-15). */
        RECEIPT_LOGO,

        /** The visual of a loyalty advantage (BO-10-03-09). */
        LOYALTY_ADVANTAGE
    }

    /** The image's code, unique, the key the distribution upserts on. */
    @Column(name = "code", nullable = false, unique = true, length = 40)
    @NotBlank
    public String code;

    /** What the back office calls it. */
    @Column(name = "label", nullable = false, length = 80)
    @NotBlank
    public String label;

    /** What the image is for, never null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "usage", nullable = false, length = 24)
    public Usage usage;

    /**
     * The loyalty advantage this visual illustrates: the RULE code of the
     * advantage, or its TYPE when one visual serves a whole family. Null for
     * a receipt logo.
     */
    @Column(name = "advantage_code", length = 40)
    public String advantageCode;

    /** The image itself, a PNG {@code data:} URI. */
    @Column(name = "image_data", columnDefinition = "TEXT")
    public String imageData;

    /** Whether the register uses it at all. */
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
     * Hashes the business fields, for the change detection of the base
     * entity; the image itself is hashed too, so replacing the picture under
     * an unchanged name is seen as the change it is.
     *
     * @return the checksum of this row
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, label, usage, advantageCode, imageData, active);
    }
}
