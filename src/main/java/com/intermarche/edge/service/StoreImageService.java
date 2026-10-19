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
 * Resolves the administered images for THIS store ({@code BO-03-03-15},
 * {@code BO-10-03-09}): the receipt logo and the loyalty-advantage visuals.
 *
 * <p>ONE RULE: the closest echelon wins. An image posed on the PDV beats one
 * posed on its enseigne, which beats one posed on its country, which beats
 * the group's default — the same walk the parameter resolution makes, and for
 * the same reason: a group states a rule once at the top and a shop
 * contradicts it where it has to.
 *
 * <p>The resolution happens HERE, on the store node, and the registers
 * receive only its result. A lane holds no echelon chain; it holds the paper
 * it prints.
 */
@ApplicationScoped
public class StoreImageService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(StoreImageService.class);

    /** The node's own identity in the organisation tree. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * The receipt logo this store prints, or null when no image resolves.
     *
     * @return the resolved logo, or null
     */
    public StoreImage receiptLogo() {
        LOGGER.info("Entering method receiptLogo");
        StoreImage resolved = closest(StoreImage.findActiveByUsage(StoreImage.Usage.RECEIPT_LOGO));
        LOGGER.info("Exiting method receiptLogo");
        return resolved;
    }

    /**
     * The loyalty-advantage visuals this store prints, one per advantage code
     * ({@code BO-10-03-09}), each resolved on its own echelon chain.
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
        StoreImage logo = receiptLogo();
        if (logo != null) {
            images.add(logo);
        }
        images.addAll(advantageVisuals().values());
        LOGGER.info("Exiting method resolvedImages with " + images.size() + " image(s)");
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
            if (closeness > best || (closeness == best && winner != null
                    && candidate.code != null && candidate.code.compareTo(winner.code) < 0)) {
                winner = candidate;
                best = closeness;
            }
        }
        return winner;
    }
}
