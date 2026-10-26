package com.intermarche.edge.service;

import com.intermarche.edge.domain.setting.StoreImage;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The administered images of the shop ({@code BO-03-03-15},
 * {@code BO-10-03-09}): the receipt logo and the loyalty-advantage visuals.
 * It serves the two nodes of the suite, each through its own road.
 *
 * <p>On the STORE NODE it RESOLVES them, and ONE RULE applies: the closest
 * echelon wins. An image posed on the PDV beats one posed on its enseigne,
 * which beats one posed on its country, which beats the group's default — the
 * same walk the parameter resolution makes, and for the same reason: a group
 * states a rule once at the top and a shop contradicts it where it has to
 * ({@link #resolvedReceiptLogo()}, {@link #advantageVisuals()},
 * {@link #resolvedImages()}).
 *
 * <p>The resolution happens on the store node, and the registers receive only
 * its result. A lane holds no echelon chain; it holds the paper it prints. On
 * the REGISTER this service therefore only READS what the node distributed
 * ({@link #receiptLogo()}, {@link #advantageVisual(String, String)}): no
 * resolution happens there, it only answers "which picture, if any", and
 * answers an EMPTY STRING when there is none, because every caller falls back
 * on what it printed before: the logo of the installation, or no visual at
 * all. A register that has never pulled prints exactly what it printed
 * yesterday.
 */
@ApplicationScoped
public class StoreImageService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(StoreImageService.class);

    /** The node's own identity in the organisation tree. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * The administered receipt logo as a {@code data:} URI, or an empty
     * string when the node distributed none ({@code BO-03-03-15}) — the
     * register's road.
     *
     * <p>Several logos would mean the node resolved two, which it does not;
     * should it ever happen, the first in code order answers rather than an
     * error — a receipt must print.
     *
     * @return the logo data URI, never null
     */
    public String receiptLogo() {
        LOGGER.info("Entering method receiptLogo");
        LOGGER.info("Exiting method receiptLogo");
        return firstOfUsage(StoreImage.Usage.RECEIPT_LOGO);
    }

    /**
     * The administered monetique logo printed above the payment terminal's
     * receipts, or an empty string when none was distributed (BO-10-07-13).
     *
     * @return the logo data URI, never null
     */
    public String eftLogo() {
        LOGGER.info("Entering method eftLogo");
        LOGGER.info("Exiting method eftLogo");
        return firstOfUsage(StoreImage.Usage.EFT_LOGO);
    }

    /**
     * The administered contactless logo a contactless payment's receipt
     * carries, or an empty string when none was distributed (BO-10-07-13).
     *
     * @return the logo data URI, never null
     */
    public String contactlessLogo() {
        LOGGER.info("Entering method contactlessLogo");
        LOGGER.info("Exiting method contactlessLogo");
        return firstOfUsage(StoreImage.Usage.CONTACTLESS_LOGO);
    }

    /**
     * The picture of the first active image of a usage that carries one.
     *
     * @param usage the usage looked for
     * @return the data URI, or an empty string
     */
    private String firstOfUsage(StoreImage.Usage usage) {
        for (StoreImage image : StoreImage.findActiveByUsage(usage)) {
            if (image.imageData != null && !image.imageData.isBlank()) {
                return image.imageData;
            }
        }
        return "";
    }

    /**
     * The receipt logo this store prints, resolved on the echelon chain, or
     * null when no image resolves — the store node's road.
     *
     * @return the resolved logo, or null
     */
    public StoreImage resolvedReceiptLogo() {
        LOGGER.info("Entering method resolvedReceiptLogo");
        StoreImage resolved = closest(StoreImage.findActiveByUsage(StoreImage.Usage.RECEIPT_LOGO));
        LOGGER.info("Exiting method resolvedReceiptLogo");
        return resolved;
    }

    /**
     * The visual of a loyalty advantage as a {@code data:} URI, or an empty
     * string when none is administered ({@code BO-10-03-09}) — the register's
     * road.
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
     * The loyalty-advantage visuals this store prints, one per advantage code
     * ({@code BO-10-03-09}), each resolved on its own echelon chain — the store
     * node's road.
     *
     * <p>Keyed by the advantage code and NOT by the image code: two echelons
     * proposing a visual for the same advantage are two candidates for one
     * slot, and the shop prints one of them.
     *
     * @return the resolved visuals, keyed by advantage code, in code order
     */
    public Map<String, StoreImage> advantageVisuals() {
        LOGGER.info("Entering method advantageVisuals");
        Map<String, List<StoreImage>> byAdvantage = new LinkedHashMap<>();
        for (StoreImage image
                : StoreImage.findActiveByUsage(StoreImage.Usage.LOYALTY_ADVANTAGE)) {
            if (image.advantageCode == null || image.advantageCode.isBlank()) {
                continue;
            }
            byAdvantage.computeIfAbsent(image.advantageCode.trim(), key -> new ArrayList<>())
                    .add(image);
        }
        Map<String, StoreImage> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, List<StoreImage>> entry : byAdvantage.entrySet()) {
            StoreImage winner = closest(entry.getValue());
            if (winner != null) {
                resolved.put(entry.getKey(), winner);
            }
        }
        LOGGER.info("Exiting method advantageVisuals with " + resolved.size() + " visual(s)");
        return resolved;
    }

    /**
     * Every image this store resolves to, logo and visuals together — what
     * the distribution hands to the registers.
     *
     * @return the resolved images, the logo first
     */
    public List<StoreImage> resolvedImages() {
        LOGGER.info("Entering method resolvedImages");
        List<StoreImage> images = new ArrayList<>();
        StoreImage logo = resolvedReceiptLogo();
        if (logo != null) {
            images.add(logo);
        }
        images.addAll(advantageVisuals().values());
        // BO-10-07-13: the monetique and contactless logos of the terminal
        // receipts, each resolved on its own echelon chain like the logo.
        for (StoreImage.Usage usage : List.of(StoreImage.Usage.EFT_LOGO, StoreImage.Usage.CONTACTLESS_LOGO)) {
            StoreImage resolved = closest(StoreImage.findActiveByUsage(usage));
            if (resolved != null) {
                images.add(resolved);
            }
        }
        // BO-02-03-39: the pictures of the tare containers, each named by the
        // code of the container it shows (codes are unique, so one per container).
        images.addAll(StoreImage.findActiveByUsage(StoreImage.Usage.TARE_CONTAINER));
        LOGGER.info("Exiting method resolvedImages with " + images.size() + " image(s)");
        return images;
    }

    /**
     * Every image this store resolves to FOR ONE REGISTER (BO-03-09-04): the
     * same set as {@link #resolvedImages()}, except that a receipt logo chosen
     * by code — active, and a receipt logo — replaces the one the echelon chain
     * answers, so the register still receives exactly one logo.
     *
     * @param logoCode the code of the chosen logo, or null for none
     * @return the resolved images, the logo first
     */
    public List<StoreImage> resolvedImages(String logoCode) {
        LOGGER.info("Entering method resolvedImages with logoCode: " + logoCode);
        List<StoreImage> images = resolvedImages();
        StoreImage chosen = StoreImage.findByCode(logoCode);
        if (chosen == null || !chosen.active || chosen.usage != StoreImage.Usage.RECEIPT_LOGO) {
            LOGGER.info("Exiting method resolvedImages");
            return images;
        }
        images.removeIf(image -> image.usage == StoreImage.Usage.RECEIPT_LOGO);
        images.add(0, chosen);
        LOGGER.info("Exiting method resolvedImages");
        return images;
    }

    /**
     * Picks the candidate posed at the echelon closest to this store; a tie —
     * two images at the same echelon — is settled by the code, so the choice
     * is stable from one pull to the next rather than depending on the order
     * the rows came back in.
     *
     * @param candidates the images competing for one slot
     * @return the winner, or null when none applies to this store
     */
    StoreImage closest(List<StoreImage> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String pdvNumber = posSettingsService.pdvNumber();
        String enseigneCode = null;
        String countryCode = null;
        Pdv pdv = pdvNumber == null || pdvNumber.isBlank() ? null : Pdv.findByNumber(pdvNumber);
        if (pdv != null) {
            enseigneCode = pdv.enseigneCode;
            if (enseigneCode != null) {
                Enseigne enseigne = Enseigne.findByCode(enseigneCode);
                if (enseigne != null) {
                    countryCode = enseigne.countryCode;
                }
            }
        }
        StoreImage winner = null;
        int best = -1;
        for (StoreImage candidate : candidates) {
            int closeness = candidate.closenessTo(pdvNumber, enseigneCode, countryCode);
            if (closeness < 0) {
                continue;
            }
            if (closeness > best || (closeness == best
                    && candidate.code != null && candidate.code.compareTo(winner.code) < 0)) {
                winner = candidate;
                best = closeness;
            }
        }
        return winner;
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
