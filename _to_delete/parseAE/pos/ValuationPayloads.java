package com.intermarche.pos.ui.valuation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Payloads of the remote valuation engine (phase 7 lot 1).
 * <p>
 * Request side: natural identities only — {@code lineId} carries the line's
 * {@code lineUid} verbatim (the engine echoes it back, closing the identity
 * loop), manual gestures travel AS DATA ({@code manualDiscountAmount} in
 * euros off the LINE total, {@code manualDiscountPercent},
 * {@code manualForcedPrice} as the effective UNIT price tax included), and
 * the surcharge trio remains the vehicle of price-embedded scale labels.
 * <p>
 * Response side: TOLERANT READER — every class ignores unknown fields, every
 * field may be absent, and amounts are read as {@link BigDecimal}
 * (configured on the client's mapper). Known engine quirks absorbed here by
 * doctrine rather than fields: a line may come back in SEVERAL portions
 * (aggregate by lineId), the per-item {@code vatRate} may be a blended
 * decoration (the register derives tax-excluded amounts from its own
 * snapshot rates), and advantages join offers by the {@code offer} type
 * string until a stable {@code offerId} exists.
 * <p>
 * Placement: ui.valuation — the engine wire format, used only by this package (consumer-exclusivity rule).
 */
public final class ValuationPayloads {

    /**
     * Non-instantiable payload container.
     */
    private ValuationPayloads() {}

    // --------------------------------------------------
    // Request
    // --------------------------------------------------

    /**
     * The basket submitted for valuation.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BasketDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The fidelity card of the ticket, or null. */
        public String customerCode;
        /** The store code of this node. */
        public String storeCode;
        /** The ticket creation timestamp, ISO-8601. */
        public String createdAt;
        /** Always IN_STORE for a register sale. */
        public String deliveryMode = "IN_STORE";
        /** The eligible lines of the cart. */
        public List<ItemDto> items = new ArrayList<>();
        /**
         * The loyalty card of the ticket, for the traceability of the
         * card-borne advantages. Distinct from {@link #customerCode}, which
         * travels only when the store administers card-linked store offers:
         * this one names the card the promotions below belong to.
         */
        public String cardNumber;
        /**
         * The holder's immediate-discount promotions, or NULL when there is
         * none. Null and not an empty list: the field is then absent from the
         * request, so a basket without promotions is byte-for-byte the one
         * the engine received before this block existed.
         */
        public List<CardPromotionDto> cardPromotions;
        /**
         * The names of the promotions a scanned barcode armed on this ticket
         * (BO-03-06-43), or NULL when none was armed. Null and not an empty
         * list, for the reason {@link #cardPromotions} is: a ticket where no
         * promotion barcode was scanned sends the request the engine received
         * before this block existed, to the byte.
         */
        public List<String> scannedPromotions;
    }

    /**
     * One basket line.
     */
    /**
     * One card-borne promotion carried to the engine, which synthesizes a
     * transient offer configuration from it. Only IMMEDIATE_DISCOUNT ones
     * are sent: the cagnotte ones are imfid's to serve.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CardPromotionDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The promoted product EAN. */
        public String ean;
        /** How {@link #value} reads: PERCENT (fraction) or AMOUNT (euro per unit). */
        public String promotionType;
        /** The promotion value. */
        public BigDecimal value;
        /** How the benefit is served; always IMMEDIATE_DISCOUNT here. */
        public String benefit;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ItemDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The line's lineUid, echoed back by the engine. */
        public String lineId;
        /** The product EAN. */
        public String produceEan;
        /** Units for a UNIT product, kilograms otherwise. */
        public BigDecimal quantity;
        /** Surcharge trio: unit price excluding tax (price-embedded labels). */
        public BigDecimal pricePerUnitExclTax;
        /** Surcharge trio: unit price including tax (price-embedded labels). */
        public BigDecimal pricePerUnitInclTax;
        /** Surcharge trio: VAT rate (price-embedded labels). */
        public BigDecimal vatRate;
        /** Manual gesture: euros off the LINE total (REMISE). */
        public BigDecimal manualDiscountAmount;
        /** Manual gesture: percentage reduction (DISCOUNT). */
        public BigDecimal manualDiscountPercent;
        /** Manual gesture: effective unit price tax included (FORCE_PRICE). */
        public BigDecimal manualForcedPrice;
        /** Price lookup date, ISO-8601 (the register sends the ticket creation date). */
        public String priceDate;
        /**
         * The expiry date a GS1 code carried on this line (AI 17), ISO-8601, or null
         * (LC-11-03-13). Named {@code bestBeforeDate} because that is the field of the
         * engine's basket contract (its strict schema knows no other spelling): sent
         * under any other name the date would be silently ignored and the anti-waste
         * discount would never trigger from the register.
         * <p>
         * Sent so the engine can trigger a promotion on a short-dated article — that is
         * the requirement in so many words: "le logiciel doit donc envoyer l'AI (17) au
         * moteur de promotions". The register applies no rule of its own to it beyond
         * the shop's alert level; deciding what a date is worth is the engine's job,
         * exactly as deciding what a basket is worth is.
         */
        public String bestBeforeDate;

        /**
         * The code of the article's FAMILY, sent only where the shop asks for
         * it (BO-10-03-19).
         *
         * <p>Null when the shop does not send the nomenclature, and null for an
         * article the referential files under no family: an empty code would
         * make the engine group under a family that does not exist.
         */
        public String familyCode;

        /**
         * The code of the article's SUB-FAMILY — the deepest level the
         * referential files it under — under the same rule (BO-10-03-19).
         */
        public String subFamilyCode;
    }

    // --------------------------------------------------
    // Response (tolerant reader)
    // --------------------------------------------------

    /**
     * An amount triple of the engine.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AmountDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The amount excluding tax. */
        public BigDecimal amountExcludingTax;
        /** The amount including tax — the authoritative figure for the register. */
        public BigDecimal amountIncludingTax;
        /** The rate — possibly blended on bundles, decorative for the register. */
        public BigDecimal vatRate;
    }

    /**
     * One valued portion of a request line inside an offer.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OfferItemDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The echoed lineUid of the originating line. */
        public String lineId;
        /** The product EAN. */
        public String produceEan;
        /** The portion quantity. */
        public BigDecimal quantity;
        /** The valued amounts of the portion. */
        public AmountDto amount;
    }

    /**
     * One offer of the valuation.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OfferDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The stable offer identifier, when the engine emits one. */
        public String offerId;
        /** The human-readable offer label (also the advantages' join key today). */
        public String type;
        /** The valued amounts of the whole offer. */
        public AmountDto amount;
        /** The valued portions, empty for basket-level offers (delivery, deposit). */
        public List<OfferItemDto> items = new ArrayList<>();
    }

    /**
     * An upsell suggestion carried by an advantage.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SuggestionDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The EAN to add. */
        public String ean;
        /** The quantity to add. */
        public BigDecimal quantity;
        /** The offer the addition would complete. */
        public String offerCode;
    }

    /**
     * One advantage of the valuation (heterogeneous by type).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdvantageDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The advantage label/type. */
        public String type;
        /** The joined offer: its offerId when emitted, its type string today. */
        public String offer;
        /** The offer code (MEAL_VOUCHER and upsells). */
        public String offerCode;
        /**
         * Where the register prints this advantage (GB-01-07-10):
         * {@code "LINE"} under the single article line it targets,
         * {@code "FOOTER"} in the ticket footer. Absent — the general case —
         * reads as FOOTER, so an engine that says nothing gets the footer
         * placement and the register never has to guess a line for an
         * advantage that spans several.
         */
        public String printMode;
        /**
         * The accounting ventilation key of this advantage (GB-01-06-05),
         * copied VERBATIM into the ticket's consumption declaration. The
         * register does not interpret it — it is a key for the downstream
         * accounting — and its absence leaves the declaration byte-for-byte
         * the one it was before the field existed.
         */
        public String accountingCode;
        /** The discount amounts, on discount advantages. */
        public AmountDto discountAmount;
        /** The upsell suggestion, on upsell advantages. */
        public SuggestionDto suggestion;
        /** The meal-voucher eligible base, on MEAL_VOUCHER. */
        public BigDecimal totalEligibleAmount;
        /** The meal-voucher threshold, on MEAL_VOUCHER. */
        public BigDecimal threshold;
        /**
         * The per-line detail of a CORRECTION advantage, and what identifies
         * one: an advantage that carries lines and their {@code correction}
         * ADDS to the ticket, where every other advantage deducts from it.
         * <p>
         * The distinction is structural on purpose. Reading it from the
         * {@code type} string would tie the register's arithmetic to a label
         * the engine is free to rename, and a renamed label would silently
         * turn an addition into nothing at all — the worst of the two
         * failures, since the money simply disappears from the ticket.
         */
        public List<AdvantageDetailDto> details;
    }

    /**
     * One line of a correction advantage: which line is corrected, and by how
     * much the ticket goes UP for it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdvantageDetailDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The corrected line, echoed from the basket's lineId. */
        public String lineId;
        /** The corrected article, for the human reading the trace. */
        public String ean;
        /** The correction added to that line, tax included. */
        public BigDecimal correction;
    }

    /**
     * One line of the EGAlim guard report, as the engine explains its
     * arithmetic — read and carried, never recomputed by the register.
     * <p>
     * The register does NOT act on this block: the money it must charge is in
     * the correction advantage. It is kept because it travels verbatim to the
     * loyalty service and because it is what explains a corrected ticket to
     * whoever asks, months later.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EgalimLineDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The basket line this report concerns. */
        public String lineId;
        /** The article. */
        public String ean;
        /** FOOD_34, DPH_40 or EXEMPT. */
        public String regime;
        /** Displayed price times quantity. */
        public BigDecimal nominalPrice;
        /** Net kept after every advantage. */
        public BigDecimal finalPrice;
        /** Counted generosity over nominal, as a fraction. */
        public BigDecimal generosityRate;
        /** The generosity that counts against the cap. */
        public BigDecimal countedGenerosity;
        /** The generosity left out of the cap (anti-waste, manual gestures). */
        public BigDecimal excludedGenerosity;
        /** What is left before the cap bites, in euros; absent on EXEMPT. */
        public BigDecimal remainingBeforeCap;
        /** The correction applied to that line, present only when above zero. */
        public BigDecimal correction;
    }

    /**
     * One offer message of the valuation (GB-01-07-05/06): a text the register
     * shows the seller as the basket grows (the engine's {@code BELOW_TRIGGER}
     * messages are the "plus que X €" nudge) and prints on the ticket.
     * <p>
     * It carries no money: a message ignored — unreadable, or emitted by an
     * engine version this register does not model — leaves every total exactly
     * where it was.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MessageDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The stable message code, for the human reading the trace. */
        public String code;
        /** The text shown on screen and printed. */
        public String text;
        /**
         * Where the register prints the message: {@code "FOOTER"} in the ticket
         * footer, the default when absent. Held for symmetry with the advantage
         * placement; a basket-level message has no line to attach to, so it
         * prints in the footer whatever the value.
         */
        public String printMode;
    }

    /**
     * The whole valuation response.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ValuationResponseDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The offers of the valuation. */
        public List<OfferDto> offers = new ArrayList<>();
        /** The advantages of the valuation. */
        public List<AdvantageDto> advantages = new ArrayList<>();
        /**
         * The offer messages, or null when the engine emitted none — null and
         * not an empty list, so a response without the block reads exactly as it
         * did before the block existed and nothing is shown or printed.
         */
        public List<MessageDto> messages;
        /**
         * The EGAlim guard report, one entry per initial basket line, emitted
         * only when a guard is in force for the store and the basket is
         * closed. Null the rest of the time, which is every basket today.
         */
        public List<EgalimLineDto> egalim;
        /** The engine's own total — compared in logs, never charged as-is (lot 1). */
        public AmountDto totalPrice;
    }
}
