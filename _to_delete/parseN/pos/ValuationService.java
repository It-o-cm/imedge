package com.intermarche.pos.ui.valuation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.intermarche.pos.ui.fidelity.ImfidClient;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the remote valuation of a cart (phase 7 lot 1): builds the
 * basket request from the in-memory ticket, calls the engine, and degrades
 * gracefully — a failure NEVER blocks the sale, the register falls back to
 * its local catalog totals with a visible banner.
 * <p>
 * Line eligibility, by construction of the engine contract
 * ({@code produceEan} required): only EAN-bearing positive lines travel.
 * Excluded and locally valued as today: unknown items, deposit returns,
 * the solidarity round-up (no EAN), and weight-embedded 2x lines carrying
 * only a PLU. Price-embedded 2x labels would need the surcharge trio and a
 * product EAN resolution — lot 2 refinement, excluded for now.
 * <p>
 * Manual gestures travel AS DATA on their line: REMISE →
 * {@code manualDiscountAmount} (euros off the line total), DISCOUNT →
 * {@code manualDiscountPercent}, FORCE_PRICE → {@code manualForcedPrice}
 * (the effective unit price, i.e. the forced line total divided by the
 * quantity — already what the cart holds as unit price). The engine
 * re-applies the gesture on the SAME base price the register resolved,
 * a coherence guaranteed by the centralized referentials plus
 * {@code priceDate}.
 * <p>
 * Package placement: consumed exclusively by the sale and payment screen
 * flows and operating on {@code PosState} — per the register's placement
 * rule the whole valuation package lives under ui.
 * <p>
 * Placement: ui.valuation — consumed exclusively by the sale screens (ui.ticket, ui.payment); pos.service keeps only services serving other services.
 */
@ApplicationScoped
public class ValuationService {

    private static final Logger LOGGER = Logger.getLogger(ValuationService.class);

    @Inject
    ValuationClient valuationClient;

    @Inject
    ValuationReconciler valuationReconciler;

    @Inject
    TicketPersistenceService ticketPersistenceService;

    @Inject
    ObjectMapper objectMapper;

    /** Loyalty orchestration fed by the valuation path (imfid lot 1). */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.fidelity.FidelityService fidelityService;

    /** Administered settings — carries the card-linked promotion flag (BO-10-03-22). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** Seconds during which the engine is skipped after a failure (circuit breaker). */
    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "pos.valuation.retry-seconds", defaultValue = "10")
    long retrySeconds;

    /** Epoch millis until which engine calls are skipped (0 = circuit closed). */
    private volatile long engineSkipUntil = 0;

    /**
     * Announces the active valuation engine at startup, so a glance at the
     * boot log settles which engine this register will call.
     *
     * @param ev the startup event
     */
    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev) {
        if (valuationClient.isEnabled()) {
            LOGGER.infof("Moteur de valorisation DISTANT actif: %s (repli local en dégradé, disjoncteur %ds)",
                    valuationClient.targetUrl(), retrySeconds);
        } else {
            LOGGER.info("Moteur de valorisation: LOCAL seul (pos.valuation.url absent)");
        }
    }

    /**
     * Outcome of a valuation attempt.
     */
    public static class ValuationOutcome {
        /** Status: LOCAL (engine not configured), ENGINE (valued), DEGRADED (engine failed). */
        public final String status;
        /** The raw request JSON as sent to the engine (imfid couple), or null. */
        public final String requestJson;

        /** The raw response JSON when valued (reparsed by lot 2), or null. */
        public final String responseJson;
        /** The engine's total including tax when valued (log comparison), or null. */
        public final BigDecimal engineTotalInclTax;

        /**
         * Creates an outcome.
         *
         * @param status the outcome status
         * @param responseJson the raw response JSON, or null
         * @param engineTotalInclTax the engine total, or null
         */
        public ValuationOutcome(String status, String responseJson, BigDecimal engineTotalInclTax) {
            this(status, null, responseJson, engineTotalInclTax);
        }

        /**
         * Creates an outcome carrying the verbatim couple (ENGINE case).
         *
         * @param status the outcome status
         * @param requestJson the raw request JSON as sent, or null
         * @param responseJson the raw response JSON, or null
         * @param engineTotalInclTax the engine total, or null
         */
        public ValuationOutcome(String status, String requestJson, String responseJson,
                                BigDecimal engineTotalInclTax) {
            this.status = status;
            this.requestJson = requestJson;
            this.responseJson = responseJson;
            this.engineTotalInclTax = engineTotalInclTax;
        }
    }

    /**
     * Actionable hints extracted from an engine response (phase 7 lot 3):
     * the meal-voucher base and the upsell suggestions.
     */
    public static class EngineHints {
        /** The meal-voucher eligible base (tax included), or null. */
        public BigDecimal mealEligible;
        /** The meal-voucher threshold (legal cap), or null. */
        public BigDecimal mealThreshold;
        /** Human-readable upsell suggestions, product labels resolved locally. */
        public List<String> upsells = new ArrayList<>();
        /** Offer messages to show and print, empty when the engine emitted none. */
        public List<EngineMessage> messages = new ArrayList<>();
    }

    /**
     * One offer message extracted from a valuation response (GB-01-07-05/06):
     * a code, the text to show and print, and where to print it. It carries no
     * money — showing or dropping it never changes a total.
     */
    public static class EngineMessage {
        /** The stable message code, or null. */
        public String code;
        /** The text shown on screen and printed. */
        public String text;
        /** The print placement (FOOTER by default), or null. */
        public String printMode;

        /**
         * Creates an offer message.
         *
         * @param code the message code, or null
         * @param text the message text
         * @param printMode the print placement, or null
         */
        public EngineMessage(String code, String text, String printMode) {
            this.code = code;
            this.text = text;
            this.printMode = printMode;
        }
    }

    /**
     * Extracts the actionable hints of a valuation response: the
     * MEAL_VOUCHER advantage (eligible base and threshold, feeding the
     * meal-ticket cap) and the upsell suggestions, with the product label
     * resolved from the local catalog so the cashier reads a name, not an
     * EAN.
     *
     * @param responseJson the raw engine response
     * @return the hints, empty-but-non-null on any parsing trouble
     */
    public EngineHints extractHints(String responseJson) {
        LOGGER.info("Entering method extractHints with responseJson: " + responseJson);
        EngineHints hints = new EngineHints();
        if (responseJson == null) { LOGGER.info("Exiting method extractHints"); return hints; }
        try {
            ValuationPayloads.ValuationResponseDto response =
                    objectMapper.readValue(responseJson, ValuationPayloads.ValuationResponseDto.class);
            for (ValuationPayloads.AdvantageDto advantage : response.advantages) {
                if ("MEAL_VOUCHER".equals(advantage.type)) {
                    hints.mealEligible = advantage.totalEligibleAmount;
                    hints.mealThreshold = advantage.threshold;
                } else if (advantage.suggestion != null && advantage.suggestion.ean != null) {
                    Product product = Product.find("ean", advantage.suggestion.ean).firstResult();
                    String label = product != null ? product.name : advantage.suggestion.ean;
                    String qty = advantage.suggestion.quantity != null
                            ? advantage.suggestion.quantity.stripTrailingZeros().toPlainString() : "1";
                    String offer = advantage.suggestion.offerCode != null
                            ? " (" + advantage.suggestion.offerCode + ")" : "";
                    hints.upsells.add("+" + qty + " x " + label + offer);
                }
            }
            // GB-01-07-05/06: the offer messages, shown on the seller screen as
            // the basket grows and printed on the ticket. A blank text is
            // dropped — a message says something or it is not a message — and a
            // response without the block leaves the list empty.
            if (response.messages != null) {
                for (ValuationPayloads.MessageDto message : response.messages) {
                    if (message.text == null || message.text.isBlank()) {
                        continue;
                    }
                    hints.messages.add(new EngineMessage(
                            message.code, message.text.trim(), message.printMode));
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Hints de valorisation illisibles (%s)", e.getMessage());
        }
        LOGGER.info("Exiting method extractHints");
        return hints;
    }

    /**
     * Tells whether the remote engine is configured on this register.
     *
     * @return true when remote valuation is active
     */
    public boolean isEnabled() {
        LOGGER.info("Entering method isEnabled");
        LOGGER.info("Exiting method isEnabled");
        return valuationClient.isEnabled();
    }

    /**
     * CONTINUOUS VALUATION HOOK (phase 7 lot 4): revalues the cart after a
     * cart mutation. The engine runs on the same machine and serves only
     * this register, so the call is SYNCHRONOUS inside the scan gesture —
     * no races, no flicker, the versioned poll repaints a settled total.
     * Skipped in training, during an active payment (the payment entry has
     * its own final revaluation) and while the circuit breaker is open: a
     * hung engine must not charge its timeout to every scan, so a failure
     * opens the circuit for {@code pos.valuation.retry-seconds} and the
     * register runs on local totals until the next retry window.
     *
     * @param state the current POS state
     */
    public void revalue(PosState state) {
        LOGGER.info("Entering method revalue with state: " + state);
        if (state.trainingMode || state.payment.paymentInProgress) { LOGGER.info("Exiting method revalue"); return; }
        doRevalue(state);
        LOGGER.info("Exiting method revalue");
    }

    /**
     * Final revaluation at payment entry — same machinery, the
     * payment-in-progress guard lifted: the figure fixed here is the one
     * the customer pays.
     *
     * @param state the current POS state
     */
    public void revalueForPayment(PosState state) {
        LOGGER.info("Entering method revalueForPayment with state: " + state);
        if (state.trainingMode) { LOGGER.info("Exiting method revalueForPayment"); return; }
        doRevalue(state);
        LOGGER.info("Exiting method revalueForPayment");
    }

    /**
     * Revalues the cart: calls the engine (circuit permitting), applies or
     * reverts the line-borne valuation, recomputes the totals, persists the
     * draft and refreshes the payment-state valuation fields and hints.
     *
     * @param state the current POS state
     */
    private void doRevalue(PosState state) {
        if (!isEnabled()) return;
        Long ticketDbId = state.payment.ticketDbId;

        if (System.currentTimeMillis() < engineSkipUntil) {
            degradeInMemory(state, ticketDbId);
            return;
        }

        ValuationOutcome outcome = valuate(state.ticket,
                state.fidelity.active ? state.fidelity.label : null,
                state.fidelity.cardPromotions,
                java.time.LocalDateTime.now());
        state.payment.valuationStatus = outcome.status;
        state.payment.valuationJson = outcome.responseJson;
        state.payment.valuationEngineTotal = outcome.engineTotalInclTax;

        switch (outcome.status) {
            case "ENGINE" -> {
                state.payment.valuationAdjustment =
                        valuationReconciler.apply(state.ticket, ticketDbId, outcome.responseJson);
                state.ticket.recomputeTotal();
                ticketPersistenceService.syncDraft(state);
                EngineHints hints = extractHints(outcome.responseJson);
                state.payment.valuationMealEligible = hints.mealEligible;
                state.payment.valuationMealThreshold = hints.mealThreshold;
                state.payment.valuationUpsells = new java.util.ArrayList<>(hints.upsells);
                state.payment.valuationMessages = new java.util.ArrayList<>(hints.messages);
                if (outcome.engineTotalInclTax != null) {
                    LOGGER.debugf("Revalorisation: caisse=%s moteur=%s",
                            state.ticket.totalAmount, outcome.engineTotalInclTax);
                }
                // Loyalty hook: the verbatim couple feeds the earn projection
                // (phase: imfid integration, lot 1).
                fidelityService.onValuation(state, outcome.requestJson, outcome.responseJson);
            }
            case "DEGRADED" -> {
                engineSkipUntil = System.currentTimeMillis() + retrySeconds * 1000L;
                degradeInMemory(state, ticketDbId);
                valuationReconciler.markDegraded(ticketDbId);
                fidelityService.onValuationUnavailable(state);
            }
            default -> { // LOCAL: no eligible line — make sure nothing stale survives
                revertInMemory(state, ticketDbId);
                fidelityService.onValuationUnavailable(state);
            }
        }
        state.touch();
    }

    /**
     * Reverts any applied valuation and stamps the in-memory state degraded.
     *
     * @param state the current POS state
     * @param ticketDbId the draft database id, or null
     */
    private void degradeInMemory(PosState state, Long ticketDbId) {
        revertInMemory(state, ticketDbId);
        state.payment.valuationStatus = "DEGRADED";
    }

    /**
     * Reverts any applied valuation: local totals restored, hints cleared.
     *
     * @param state the current POS state
     * @param ticketDbId the draft database id, or null
     */
    private void revertInMemory(PosState state, Long ticketDbId) {
        boolean hadValuation = state.payment.valuationAdjustment != null;
        if (hadValuation) {
            valuationReconciler.revert(state.ticket, ticketDbId);
            state.ticket.recomputeTotal();
            ticketPersistenceService.syncDraft(state);
        }
        state.payment.valuationAdjustment = null;
        state.payment.valuationMealEligible = null;
        state.payment.valuationMealThreshold = null;
        state.payment.valuationUpsells = new java.util.ArrayList<>();
        state.payment.valuationMessages = new java.util.ArrayList<>();
    }

    /**
     * Values the cart against the remote engine, degrading on any failure.
     *
     * @param ticket the in-memory cart
     * @param fidelityCard the attached fidelity card, or null
     * @param creationDate the ticket creation date (price lookup alignment)
     * @return the outcome: LOCAL, ENGINE (with the raw response) or DEGRADED
     */
    public ValuationOutcome valuate(TicketState ticket, String fidelityCard, LocalDateTime creationDate) {
        return valuate(ticket, fidelityCard, null, creationDate);
    }

    /**
     * Values the cart against the remote engine, carrying the holder's
     * card-borne promotions, and degrading on any failure.
     *
     * @param ticket the in-memory cart
     * @param fidelityCard the attached fidelity card, or null
     * @param cardPromotions the holder's immediate-discount promotions, or
     *                       null/empty when there is none
     * @param creationDate the ticket creation date (price lookup alignment)
     * @return the outcome: LOCAL, ENGINE (with the raw response) or DEGRADED
     */
    public ValuationOutcome valuate(TicketState ticket, String fidelityCard,
                                    List<ImfidClient.CardPromotion> cardPromotions,
                                    LocalDateTime creationDate) {
        LOGGER.info("Entering method valuate with ticket: " + ticket + ", fidelityCard: " + fidelityCard + ", creationDate: " + creationDate);
        if (!isEnabled()) {
            LOGGER.info("Exiting method valuate");
            return new ValuationOutcome("LOCAL", null, null);
        }
        try {
            ValuationPayloads.BasketDto basket =
                    buildBasket(ticket, fidelityCard, cardPromotions, creationDate);
            if (basket.items.isEmpty()) {
                // An EMPTY CART, nothing else: there is no basket to price.
                // This is not a filtering decision — every line of a non-empty
                // ticket reaches the engine.
                LOGGER.info("Exiting method valuate");
                return new ValuationOutcome("LOCAL", null, null);
            }
            // The couple travels VERBATIM to imfid (spec §1): the request is
            // serialized ONCE, before the call, exactly as the client sends it.
            String requestJson = objectMapper.writeValueAsString(basket);
            ValuationClient.ValuationResult result = valuationClient.valuate(basket);
            ValuationPayloads.ValuationResponseDto response = result.response();
            BigDecimal engineTotal = response.totalPrice != null ? response.totalPrice.amountIncludingTax : null;
            // The BODY, not the object re-serialized: what leaves for the
            // loyalty service must carry everything the engine wrote,
            // including the blocks this register does not model.
            String json = result.rawJson();
            LOGGER.infof("Valorisation moteur: %d offre(s), %d advantage(s), total moteur=%s",
                    response.offers.size(), response.advantages.size(), engineTotal);
            LOGGER.info("Exiting method valuate");
            return new ValuationOutcome("ENGINE", requestJson, json, engineTotal);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Valorisation interrompue: mode dégradé prix catalogue");
            LOGGER.info("Exiting method valuate");
            return new ValuationOutcome("DEGRADED", null, null);
        } catch (Exception e) {
            LOGGER.warnf("Valorisation indisponible (%s: %s): mode dégradé prix catalogue",
                    e.getClass().getSimpleName(), e.getMessage());
            LOGGER.info("Exiting method valuate");
            return new ValuationOutcome("DEGRADED", null, null);
        }
    }

    /**
     * Builds the basket request from the eligible cart lines.
     *
     * @param ticket the in-memory cart
     * @param fidelityCard the attached fidelity card, or null
     * @param cardPromotions the holder's immediate-discount promotions, or
     *                       null/empty when there is none
     * @param creationDate the ticket creation date
     * @return the basket request
     */
    private ValuationPayloads.BasketDto buildBasket(TicketState ticket, String fidelityCard,
                                                    List<ImfidClient.CardPromotion> cardPromotions,
                                                    LocalDateTime creationDate) {
        ValuationPayloads.BasketDto basket = new ValuationPayloads.BasketDto();
        // BO-10-03-22: the card travels to the promotion engine ONLY when the
        // store administers card-linked promotions. Off, the basket is priced
        // anonymously — the engine can no longer read the holder's eligible
        // personal offers, and none can be triggered against the card.
        boolean jvPromotion = posSettingsService.fidelityJvPromotionEnabled();
        basket.customerCode = (jvPromotion && fidelityCard != null && !fidelityCard.isBlank())
                ? fidelityCard : null;
        // The card-borne promotions, and the card they belong to, travel
        // INDEPENDENTLY of the customerCode above: that one is a store
        // setting about store offers, these are personal rights imfid
        // already granted, and switching the setting off must not silently
        // withdraw them. Absent promotions leave BOTH fields null, so the
        // request is the one the engine used to receive, to the byte.
        if (cardPromotions != null && !cardPromotions.isEmpty()) {
            List<ValuationPayloads.CardPromotionDto> promotions = new ArrayList<>();
            for (ImfidClient.CardPromotion promotion : cardPromotions) {
                ValuationPayloads.CardPromotionDto dto = new ValuationPayloads.CardPromotionDto();
                dto.ean = promotion.ean;
                dto.promotionType = promotion.promotionType;
                dto.value = promotion.value;
                dto.benefit = promotion.benefit;
                promotions.add(dto);
            }
            basket.cardPromotions = promotions;
            basket.cardNumber = fidelityCard;
        }
        // BO-03-06-43: the promotions a barcode ARMED on this ticket. The
        // register carries their names and nothing else — what they are worth
        // is the engine's, exactly as a card-borne promotion's is. Absent, the
        // field stays null and the request is unchanged.
        if (ticket.scannedPromotions != null && !ticket.scannedPromotions.isEmpty()) {
            basket.scannedPromotions = new ArrayList<>(ticket.scannedPromotions);
        }
        Store store = Store.findAll().firstResult();
        basket.storeCode = store != null ? store.code : "0000";
        String isoDate = (creationDate != null ? creationDate : LocalDateTime.now())
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        basket.createdAt = isoDate;

        // EVERY line goes to the engine — no exception, no local filtering.
        // The engine is the AUTHORITY on what a basket is worth: it alone
        // decides what a gift card, a deposit voucher or an age-restricted
        // bottle is worth, and the register never second-guesses it. The POS
        // computes a total by itself ONLY when the engine cannot answer
        // (unreachable or in error), which is the degraded path below.
        for (TicketState.TicketItem item : ticket.items) {
            // EXP-38: a RETURN line is not a sale — it earns no offer and must not
            // reach the engine as a promo-eligible basket line. The signed total
            // still carries it; the valuation simply does not see it.
            if (item.returnLine) {
                continue;
            }
            ValuationPayloads.ItemDto dto = new ValuationPayloads.ItemDto();
            dto.lineId = item.uid;
            dto.produceEan = item.ean;
            dto.quantity = item.quantity;
            dto.priceDate = isoDate;
            // LC-11-03-13: a short-dated article may be worth less, and the engine is
            // what decides that. The date travels with the line for the same reason
            // every line travels: the engine is the authority on the basket's value.
            if (item.gs1ExpiryDate != null) {
                dto.bestBeforeDate = item.gs1ExpiryDate.toString();
            }
            if (item.priceEmbedded) {
                // PRICE-EMBEDDED sticker (EAN 2x, prefixes 21-22): the price
                // lives on the paper, not in any catalog. The contract's
                // surcharge trio — all-or-nothing — overrides the engine's
                // catalog price for this line; without it the engine would
                // re-price the EAN per kilogram and clobber the sticker total.
                dto.pricePerUnitInclTax = item.unitPrice;
                dto.vatRate = item.vatRate;
                dto.pricePerUnitExclTax = item.unitPrice.divide(
                        BigDecimal.ONE.add(item.vatRate), 2, RoundingMode.HALF_UP);
            }
            applyGesture(dto, item);
            basket.items.add(dto);
        }
        return basket;
    }

    /**
     * Maps the line's structured manual gesture onto the request fields.
     *
     * @param dto the request line
     * @param item the cart line
     */
    private void applyGesture(ValuationPayloads.ItemDto dto, TicketState.TicketItem item) {
        if (item.modifierType == null || item.modifierValue == null) return;
        switch (item.modifierType) {
            // P8: modifierValue is a UNIT remise; the engine expects the euros
            // removed from the LINE total, i.e. the unit remise times the quantity.
            case REMISE -> dto.manualDiscountAmount = item.modifierValue.multiply(item.quantity);
            case DISCOUNT -> dto.manualDiscountPercent = item.modifierValue;
            case FORCE_PRICE -> dto.manualForcedPrice =
                    item.quantity.signum() != 0
                            ? item.modifierValue.divide(item.quantity, 2, RoundingMode.HALF_UP)
                            : item.modifierValue;
            default -> LOGGER.warnf("Geste inconnu ignoré sur la ligne %s: %s", item.uid, item.modifierType);
        }
    }
}
