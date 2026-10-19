package com.intermarche.pos.service;

import com.intermarche.pos.domain.setting.StoreImage;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Reads the images the store node distributed to this register: the receipt
 * logo ({@code BO-03-03-15}) and the visuals of the loyalty advantages
 * ({@code BO-10-03-09}).
 *
 * <p>No resolution happens here — the node already did it. This service only
 * answers "which picture, if any", and answers an EMPTY STRING when there is
 * none, because every caller falls back on what it printed before: the logo
 * of the installation, or no visual at all. A register that has never pulled
 * prints exactly what it printed yesterday.
 */
@ApplicationScoped
public class StoreImageService {

    /**
     * Technical log of this class.
     */
    private static final Logger LOGGER = Logger.getLogger(StoreImageService.class);

    /**
     * The administered receipt logo as a {@code data:} URI, or an empty
     * string when the node distributed none ({@code BO-03-03-15}).
     *
     * <p>Several logos would mean the node resolved two, which it does not;
     * should it ever happen, the first in code order answers rather than an
     * error — a receipt must print.
     *
     * @return the logo data URI, never null
     */
    public String receiptLogo() {
        LOGGER.info("Entering method receiptLogo");
        for (StoreImage image : StoreImage.findActiveByUsage(StoreImage.Usage.RECEIPT_LOGO)) {
            if (image.imageData != null && !image.imageData.isBlank()) {
                LOGGER.info("Exiting method receiptLogo");
                return image.imageData;
            }
        }
        LOGGER.info("Exiting method receiptLogo");
        return "";
    }

    /**
     * The visual of a loyalty advantage as a {@code data:} URI, or an empty
     * string when none is administered ({@code BO-10-03-09}).
     *
     * <p>Looked up on the advantage's RULE code first and on its TYPE next:
     * a shop illustrates one rule precisely, or a whole family at once, and
     * the precise one wins.
     *
     * @param ruleCode the advantage's rule code, possibly null
     * @param advantageType the advantage's type, possibly null
     * @return the visual data URI, never null
     */
    public String advantageVisual(String ruleCode, String advantageType) {
        LOGGER.info("Entering method advantageVisual with ruleCode: " + ruleCode
                + ", advantageType: " + advantageType);
        List<StoreImage> images =
                StoreImage.findActiveByUsage(StoreImage.Usage.LOYALTY_ADVANTAGE);
        String visual = match(images, ruleCode);
        if (visual.isEmpty()) {
            visual = match(images, advantageType);
        }
        LOGGER.info("Exiting method advantageVisual");
        return visual;
    }

    /**
     * The picture of the image whose advantage code is the one given.
     *
     * @param images the administered visuals
     * @param code the advantage code looked for, possibly null
     * @return the data URI, or an empty string
     */
    private String match(List<StoreImage> images, String code) {
        if (images == null || code == null || code.isBlank()) {
            return "";
        }
        String typed = code.trim();
        for (StoreImage image : images) {
            if (image.advantageCode != null && typed.equalsIgnoreCase(image.advantageCode.trim())
                    && image.imageData != null && !image.imageData.isBlank()) {
                return image.imageData;
            }
        }
        return "";
    }
}
