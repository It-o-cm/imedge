package com.intermarche.pos.ui.balance;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * Resolves the SOFTWARE TARE (LC-14-01) the register sends to the scale, and
 * arms the punctual container tare selected or scanned by the cashier.
 * <p>
 * It lives in {@code ui.balance} and not among the general services because it
 * reads and writes {@link PosState} (the punctual container tare of one
 * weighing) — the package-placement doctrine of this product.
 * <p>
 * Priority of the tare, highest first (LC-14-01-03/04/05/07):
 * <ol>
 *   <li>the PUNCTUAL container tare armed on {@link PosState#tare} by a
 *       selection or a scan — it overrides everything for one weighing;</li>
 *   <li>the ARTICLE tare declared in the product's {@code TARE} attribute,
 *       which replaces the default (LC-14-01-07);</li>
 *   <li>the administered DEFAULT tare (LC-14-01-03), zero when none.</li>
 * </ol>
 * The resolved tare is SENT TO THE SCALE, which prioritises and returns the NET
 * weight; the register records that net as-is and never re-deducts
 * (LC-14-01-06). The ONE place the register itself deducts is manual weight
 * entry (LC-14-01-08, {@link #resolveForManual}), where no scale is in the loop.
 */
@ApplicationScoped
public class TareService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TareService.class);

    @Inject
    PosSettingsService posSettingsService;

    /** The administered lists: tare containers (BO-04-02-07). */
    @Inject
    com.intermarche.pos.service.PosListService posListService;

    /**
     * Resolves the software tare to send to the scale for a live weighing of
     * the given product: the punctual container tare when one is armed, else
     * the product's article tare when it declares one, else the administered
     * default tare (LC-14-01-03/04/05/07). Never null, never negative.
     *
     * @param product the product being weighed, or null
     * @param state the current POS state carrying the punctual container tare
     * @return the tare in kilograms, zero when none applies
     */
    public BigDecimal resolve(Product product, PosState state) {
        LOGGER.info("Entering method resolve");
        if (state != null && state.tare.hasContainer()) {
            LOGGER.info("Exiting method resolve");
            return state.tare.containerTare;
        }
        BigDecimal articleTare = ProductAttributes.tare(product);
        if (articleTare.signum() > 0) {
            LOGGER.info("Exiting method resolve");
            return articleTare;
        }
        LOGGER.info("Exiting method resolve");
        return posSettingsService.defaultTare();
    }

    /**
     * Resolves the software tare to deduct from a MANUALLY typed weight
     * (LC-14-01-08): the punctual container tare when armed, else the article
     * tare snapshot carried on the line, else the administered default. This is
     * the only path where the register itself deducts the tare, because no
     * scale nets the weight. Never null, never negative.
     *
     * @param item the weighed line whose gross weight was typed, or null
     * @param state the current POS state carrying the punctual container tare
     * @return the tare in kilograms, zero when none applies
     */
    public BigDecimal resolveForManual(TicketState.TicketItem item, PosState state) {
        LOGGER.info("Entering method resolveForManual");
        if (state != null && state.tare.hasContainer()) {
            LOGGER.info("Exiting method resolveForManual");
            return state.tare.containerTare;
        }
        if (item != null && item.tareKg != null && item.tareKg.signum() > 0) {
            LOGGER.info("Exiting method resolveForManual");
            return item.tareKg;
        }
        LOGGER.info("Exiting method resolveForManual");
        return posSettingsService.defaultTare();
    }

    /**
     * Arms the punctual container tare from the administered container list
     * (LC-14-01-04): the container of the given code is looked up and its tare
     * armed for the next weighing; an unknown code arms nothing and answers
     * false.
     *
     * @param state the current POS state
     * @param code the administered container code the cashier tapped
     * @return true when a container was found and armed
     */
    public boolean selectContainer(PosState state, String code) {
        LOGGER.info("Entering method selectContainer with code: " + code);
        boolean armed = arm(state, code);
        LOGGER.info("Exiting method selectContainer");
        return armed;
    }

    /**
     * Arms the punctual container tare from a SCANNED container barcode
     * (LC-14-01-05): the barcode is matched against the administered container
     * codes and, on a hit, its tare is armed for the next weighing.
     *
     * @param state the current POS state
     * @param barcode the scanned container barcode
     * @return true when a container matched the barcode and was armed
     */
    public boolean scanContainer(PosState state, String barcode) {
        LOGGER.info("Entering method scanContainer with barcode: " + barcode);
        boolean armed = arm(state, barcode);
        LOGGER.info("Exiting method scanContainer");
        return armed;
    }

    /**
     * Looks a container up by code in the administered list and arms its tare
     * on the POS state, tolerating a null state or an unknown/blank code.
     *
     * @param state the current POS state, or null
     * @param code the container code to match
     * @return true when a container matched and was armed
     */
    private boolean arm(PosState state, String code) {
        if (state == null || code == null || code.isBlank()) {
            return false;
        }
        for (PosSettingsService.TareContainer container : posListService.tareContainers()) {
            if (container.code().equals(code.trim())) {
                state.tare.containerTare = container.tareKg();
                state.tare.containerLabel = container.label();
                return true;
            }
        }
        return false;
    }

    /**
     * Consumes the punctual container tare after a weighing so it never carries
     * to the next article (LC-14-01-04: re-selected at each weighing).
     *
     * @param state the current POS state, or null
     */
    public void consume(PosState state) {
        LOGGER.info("Entering method consume");
        if (state != null) {
            state.tare.clear();
        }
        LOGGER.info("Exiting method consume");
    }

    /**
     * The administered container list offered to the cashier (LC-14-01-04).
     *
     * @return the administered containers, in order, never null
     */
    public java.util.List<PosSettingsService.TareContainer> containers() {
        LOGGER.info("Entering method containers");
        LOGGER.info("Exiting method containers");
        return posListService.tareContainers();
    }
}
