package com.intermarche.edge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.edge.domain.sale.ThirdPartySystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The STATELESS e-commerce facade of the store's valuation engine for the
 * baskets of the platform (DG-01-01, DG-01-03).
 *
 * <p>The basket belongs to the selling front that builds it — here the
 * e-commerce platform — never to the node nor to the engine: every call
 * carries the platform's whole basket, and its state when the gesture
 * depends on it. The node stores nothing; it completes the basket with what
 * only it knows and has the engine's stateless {@code POST /valuation} value
 * it:
 * <ul>
 *   <li>the point of sale, when the basket names none: its declared
 *       system's, else the node's own;</li>
 *   <li>the card promotions of the basket's loyalty card, read from the
 *       loyalty programme on open, modify, hold and resume (DG-01-01-01, -02)
 *       — tolerantly: a programme that cannot be reached leaves the basket
 *       valued without them and the answer carries a warning;</li>
 *   <li>the {@code closed} fact the engine expects: false while the basket is
 *       in progress, true for the final basket of a close.</li>
 * </ul>
 *
 * <p>The state rules of the design apply to the state the platform states:
 * an OPEN basket is modified, held, closed or deleted; an ON_HOLD one is
 * resumed or deleted; anything else answers 409. Nothing is consumed before
 * the close, which declares the SALE consumption to the engine's
 * {@code /consumptions} (DG-01-01-06).
 */
@ApplicationScoped
public class EcommerceBasketService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EcommerceBasketService.class);

    /** The shape a basket identifier must have. */
    public static final String ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    /** The warning header value of a basket valued without its card promotions. */
    public static final String FID_WARNING =
            "199 imedge \"card promotions unavailable: loyalty service unreachable\"";

    /** An open basket. */
    public static final String OPEN = "OPEN";

    /** A basket on hold. */
    public static final String ON_HOLD = "ON_HOLD";

    /** A deleted basket. */
    public static final String DELETED = "DELETED";

    /** A closed basket. */
    public static final String CLOSED = "CLOSED";

    /** The engine relay. */
    @Inject
    EcommerceValuationService valuationService;

    /** Reads the card promotions from the loyalty programme. */
    @Inject
    FidCardPromotionClient fidCardPromotionClient;

    /** The node's own point of sale. */
    @Inject
    PosSettingsService posSettingsService;

    /** Reads and writes the relayed bodies. */
    ObjectMapper objectMapper = new ObjectMapper();

    /**
     * What the node answers the platform.
     *
     * @param status the HTTP status to answer the platform with
     * @param body the answer, JSON, or the reason in words
     * @param warning the warning header value, or null
     */
    public record Outcome(int status, String body, String warning) {
    }

    /**
     * A basket valued by the engine.
     *
     * @param status the engine's status
     * @param basket the basket as the engine valued it (completed by the node)
     * @param evaluation the engine's answer, JSON text
     * @param warning the warning header value, or null
     */
    public record Valued(int status, ObjectNode basket, String evaluation, String warning) {
    }

    /**
     * Opens a basket (DG-01-01-01): valued in progress, nothing stored.
     *
     * @param body the platform's basket, JSON
     * @param systemCode the declared system calling, or null
     * @return the valued basket, OPEN
     */
    public Outcome open(String body, String systemCode) {
        return transition(null, body, systemCode, null, OPEN);
    }

    /**
     * Modifies a basket (DG-01-01-04, DG-01-02-01): the whole basket the
     * platform sends is valued again.
     *
     * @param id the platform's basket identifier
     * @param body the platform's basket, JSON, its {@code status} OPEN or absent
     * @param systemCode the declared system calling, or null
     * @return the valued basket, OPEN; 409 from another state
     */
    public Outcome modify(String id, String body, String systemCode) {
        return transition(id, body, systemCode, OPEN, OPEN);
    }

    /**
     * Puts a basket on hold (DG-01-01-03): valued and answered without any
     * commitment — nothing is consumed, nothing is kept.
     *
     * @param id the platform's basket identifier
     * @param body the platform's basket, JSON, its {@code status} OPEN or absent
     * @param systemCode the declared system calling, or null
     * @return the valued basket, ON_HOLD; 409 from another state
     */
    public Outcome hold(String id, String body, String systemCode) {
        return transition(id, body, systemCode, OPEN, ON_HOLD);
    }

    /**
     * Resumes a basket on hold (DG-01-01-02): valued again at the current
     * prices and offers, the card promotions read again.
     *
     * @param id the platform's basket identifier
     * @param body the platform's basket, JSON, its {@code status} ON_HOLD or absent
     * @param systemCode the declared system calling, or null
     * @return the valued basket, OPEN; 409 from another state
     */
    public Outcome resume(String id, String body, String systemCode) {
        return transition(id, body, systemCode, ON_HOLD, OPEN);
    }

    /**
     * Deletes a basket (DG-01-01-05): nothing was consumed before the close,
     * so there is nothing to release; a closed basket is a return's business.
     *
     * @param id the platform's basket identifier
     * @param status the state the platform states, or null
     * @return DELETED; 409 for a closed or already deleted basket
     */
    public Outcome delete(String id, String status) {
        LOGGER.info("Entering method delete with id: " + id + ", status: " + status);
        if (!validId(id)) {
            LOGGER.info("Exiting method delete: bad id");
            return badId();
        }
        String state = status == null || status.isBlank() ? OPEN : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (CLOSED.equals(state)) {
            LOGGER.info("Exiting method delete: closed");
            return new Outcome(409, "Panier clos : utiliser un retour", null);
        }
        if (!OPEN.equals(state) && !ON_HOLD.equals(state)) {
            LOGGER.info("Exiting method delete: state " + state);
            return new Outcome(409, "Suppression impossible d'un panier " + state, null);
        }
        ObjectNode answer = objectMapper.createObjectNode().put("basketId", id.trim()).put("status", DELETED);
        LOGGER.info("Exiting method delete");
        return new Outcome(200, answer.toString(), null);
    }

    /**
     * Values the FINAL basket of a close (DG-01-01-06), completed like the
     * others and declared closed to the engine.
     *
     * @param basket the platform's final basket
     * @param systemCode the declared system calling, or null
     * @return the engine's valuation
     */
    public Valued valueFinal(ObjectNode basket, String systemCode) {
        return value(basket, systemCode, true);
    }

    /**
     * Declares the SALE consumption of a closed basket to the engine
     * (DG-01-01-06): every advantage of the final evaluation with its offer
     * code, amount and per-line share, in the shape the registers declare
     * them; idempotent at the engine by (store, ticket, direction). The offer
     * code is read as the engine reads it: the advantage's {@code offerCode},
     * else the code its label names ("Ticket Discount: WEB10_07039").
     *
     * @param ticketId the order reference the consumption is declared for
     * @param storeCode the point of sale, or null
     * @param finalizedAt when the order was finalized, ISO-8601
     * @param evaluation the final evaluation, JSON
     * @return the engine's status; 204 when there was nothing to declare
     */
    public int declareSale(String ticketId, String storeCode, String finalizedAt, String evaluation) {
        LOGGER.info("Entering method declareSale with ticketId: " + ticketId);
        JsonNode answer;
        try {
            answer = objectMapper.readTree(evaluation);
        } catch (Exception e) {
            answer = objectMapper.createObjectNode();
        }
        ObjectNode declaration = declaration(ticketId, storeCode, finalizedAt, "SALE");
        ArrayNode advantages = declaration.putArray("advantages");
        for (JsonNode advantage : answer.path("advantages")) {
            BigDecimal amount = EcommerceSnapshot.decimal(advantage.path("discountAmount").path("amountIncludingTax"));
            String offerCode = offerCodeOf(advantage);
            if (offerCode == null || amount == null) {
                continue;
            }
            ObjectNode declared = advantages.addObject().put("offerCode", offerCode).put("amount", amount.abs());
            if (advantage.hasNonNull("funding")) {
                declared.put("funding", advantage.path("funding").asText());
            }
            ArrayNode lines = shares(answer, advantage.path("offer").asText(""), amount.abs());
            if (!lines.isEmpty()) {
                declared.set("lines", lines);
            }
        }
        int status = send(declaration);
        LOGGER.info("Exiting method declareSale with status " + status);
        return status;
    }

    /**
     * Declares the CANCELLATION consumption of a recorded return to the engine
     * (DG-01-03, spec §5.4): the advantages the engine's return valuation took
     * back ({@code advantagesTakenBack}, every advantage of the order for a
     * full return), under the return's reference; idempotent at the engine by
     * (store, ticket, direction), so a replay moves no counter twice.
     *
     * @param returnReference the return reference the cancellation is declared for
     * @param storeCode the point of sale the sale was declared for, or null
     * @param finalizedAt when the refund was recorded, ISO-8601
     * @param takenBack the advantages taken back, {@code [{offerCode, amount}]}
     * @return the engine's status; 204 when there was nothing to declare
     */
    public int declareCancellation(String returnReference, String storeCode, String finalizedAt, JsonNode takenBack) {
        LOGGER.info("Entering method declareCancellation with returnReference: " + returnReference);
        ObjectNode declaration = declaration(returnReference, storeCode, finalizedAt, "CANCELLATION");
        ArrayNode advantages = declaration.putArray("advantages");
        for (JsonNode advantage : takenBack) {
            BigDecimal amount = EcommerceSnapshot.decimal(advantage.path("amount"));
            String offerCode = advantage.path("offerCode").asText("").trim();
            if (offerCode.isEmpty() || amount == null || amount.signum() == 0) {
                continue;
            }
            advantages.addObject().put("offerCode", offerCode).put("amount", amount.abs());
        }
        int status = send(declaration);
        LOGGER.info("Exiting method declareCancellation with status " + status);
        return status;
    }

    /**
     * The head of a consumption declaration.
     *
     * @param ticketId the ticket (order or return reference) declared
     * @param storeCode the point of sale, or null
     * @param finalizedAt the instant declared, ISO-8601
     * @param direction SALE or CANCELLATION
     * @return the declaration, without advantages
     */
    private ObjectNode declaration(String ticketId, String storeCode, String finalizedAt, String direction) {
        return objectMapper.createObjectNode().put("ticketId", ticketId).put("storeCode", storeCode)
                .put("finalizedAt", finalizedAt).put("direction", direction);
    }

    /**
     * Sends a declaration to the engine's {@code /consumptions}, unless it
     * declares no advantage.
     *
     * @param declaration the declaration
     * @return the engine's status; 204 when there was nothing to declare
     */
    private int send(ObjectNode declaration) {
        if (declaration.path("advantages").isEmpty()) {
            return 204;
        }
        return valuationService.relay("POST", "/consumptions", declaration.toString()).status();
    }

    /**
     * The offer code of an engine advantage, read as the engine itself reads
     * it: its {@code offerCode} field when present, else the first word after
     * {@code ": "} in its {@code type} label.
     *
     * @param advantage the advantage node
     * @return the offer code, or null when the advantage names none
     */
    static String offerCodeOf(JsonNode advantage) {
        String explicit = advantage.path("offerCode").asText("").trim();
        if (!explicit.isEmpty()) {
            return explicit;
        }
        String type = advantage.path("type").asText("");
        int colon = type.indexOf(": ");
        if (colon < 0) {
            return null;
        }
        String rest = type.substring(colon + 2).trim();
        int space = rest.indexOf(' ');
        String code = space < 0 ? rest : rest.substring(0, space);
        return code.isEmpty() ? null : code;
    }

    /**
     * The per-line shares of an advantage over the lines of its offer, pro
     * rata to their valued share, the rounding residual on the largest.
     *
     * @param answer the evaluation
     * @param reference the advantage's offer reference
     * @param amount the advantage's amount
     * @return the lines, empty when the offer has none
     */
    private ArrayNode shares(JsonNode answer, String reference, BigDecimal amount) {
        ArrayNode lines = objectMapper.createArrayNode();
        Map<String, BigDecimal> shares = new LinkedHashMap<>();
        Map<String, String> eans = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode offer : answer.path("offers")) {
            if (!reference.isEmpty() && (reference.equals(offer.path("offerId").asText())
                    || reference.equals(offer.path("type").asText()))) {
                for (JsonNode item : offer.path("items")) {
                    BigDecimal share = EcommerceSnapshot.decimal(item.path("amount").path("amountIncludingTax"));
                    String lineId = item.path("lineId").asText("");
                    if (!lineId.isEmpty() && share != null) {
                        shares.merge(lineId, share.abs(), BigDecimal::add);
                        eans.putIfAbsent(lineId, item.path("produceEan").asText(""));
                        total = total.add(share.abs());
                    }
                }
                break;
            }
        }
        if (total.signum() == 0) {
            return lines;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        ObjectNode largest = null;
        BigDecimal largestShare = BigDecimal.valueOf(-1);
        for (Map.Entry<String, BigDecimal> share : shares.entrySet()) {
            BigDecimal part = amount.multiply(share.getValue()).divide(total, 2, RoundingMode.HALF_UP);
            ObjectNode line = lines.addObject().put("ean", eans.get(share.getKey())).put("amount", part);
            allocated = allocated.add(part);
            if (share.getValue().compareTo(largestShare) > 0) {
                largestShare = share.getValue();
                largest = line;
            }
        }
        largest.put("amount", largest.path("amount").decimalValue().add(amount.subtract(allocated)));
        return lines;
    }

    /**
     * Values a return (DG-01-03): the engine prices each returned line from
     * the original order's snapshot the platform sends, or from the price
     * source of the line, the node naming the point of sale when the
     * platform names none.
     *
     * @param body the return valuation request, JSON
     * @param systemCode the declared system calling, or null
     * @return the engine's answer, 400 on an unreadable body
     */
    public Outcome valueReturn(String body, String systemCode) {
        LOGGER.info("Entering method valueReturn");
        ObjectNode request = parse(body);
        if (request == null) {
            LOGGER.info("Exiting method valueReturn: unreadable body");
            return new Outcome(400, "Demande illisible", null);
        }
        completeStore(request, systemCode);
        EcommerceValuationService.Answer answer = valuationService.relay("POST", "/returns/valuation", request.toString());
        LOGGER.info("Exiting method valueReturn");
        return engineAnswer(answer.status(), answer.body(), null);
    }

    /**
     * What the node answers for an engine's answer: as it came, but an
     * engine failure (5xx other than the node's own 503 for an engine out of
     * reach) is answered 502 with its status in words, never with the
     * engine's error page.
     *
     * @param status the engine's status
     * @param body the engine's body
     * @param warning the warning header value, or null
     * @return the outcome
     */
    static Outcome engineAnswer(int status, String body, String warning) {
        if (status >= 500 && status != 503) {
            return new Outcome(502, "Valorisation en échec dans le moteur (HTTP " + status + ")", warning);
        }
        return new Outcome(status, body, warning);
    }

    /**
     * Plays one gesture on the basket the platform sends: its stated state
     * checked, the basket valued in progress, the answer stating the new state.
     *
     * @param id the platform's basket identifier, or null on open
     * @param body the platform's basket, JSON
     * @param systemCode the declared system calling, or null
     * @param from the state the gesture starts from, or null for any (open)
     * @param to the state the gesture leads to
     * @return the answer: the basket identifier, the new state, the hold
     *     reason, the basket as valued and the engine's evaluation
     */
    private Outcome transition(String id, String body, String systemCode, String from, String to) {
        LOGGER.info("Entering method transition with id: " + id + ", to: " + to);
        if (id != null && !validId(id)) {
            LOGGER.info("Exiting method transition: bad id");
            return badId();
        }
        ObjectNode basket = parse(body);
        if (basket == null) {
            LOGGER.info("Exiting method transition: unreadable basket");
            return new Outcome(400, "Panier illisible", null);
        }
        String state = basket.path("status").asText("").trim().toUpperCase(java.util.Locale.ROOT);
        if (from != null && !state.isEmpty() && !from.equals(state)) {
            LOGGER.info("Exiting method transition: refused from " + state);
            return new Outcome(409, "Transition refusée : panier " + state + " → " + to, null);
        }
        String basketId = id != null ? id.trim() : basket.path("basketId").asText(null);
        String reason = basket.path("reason").asText(null);
        Valued valued = value(basket, systemCode, false);
        if (valued.status() != 200) {
            LOGGER.info("Exiting method transition: engine " + valued.status());
            return engineAnswer(valued.status(), valued.evaluation(), valued.warning());
        }
        ObjectNode answer = objectMapper.createObjectNode();
        answer.put("basketId", basketId);
        answer.put("status", to);
        if (ON_HOLD.equals(to)) {
            answer.put("holdReason", reason);
        }
        answer.set("basket", valued.basket());
        answer.set("evaluation", readOrText(valued.evaluation()));
        LOGGER.info("Exiting method transition");
        return new Outcome(200, answer.toString(), valued.warning());
    }

    /**
     * Completes a basket and has the engine value it.
     *
     * @param basket the platform's basket, changed in place
     * @param systemCode the declared system calling, or null
     * @param closed whether the basket is final
     * @return the engine's valuation
     */
    Valued value(ObjectNode basket, String systemCode, boolean closed) {
        basket.remove(java.util.List.of("basketId", "status", "reason"));
        completeStore(basket, systemCode);
        String warning = enrich(basket);
        basket.put("closed", closed);
        EcommerceValuationService.Answer answer = valuationService.relay("POST", "/valuation", basket.toString());
        return new Valued(answer.status(), basket, answer.body(), warning);
    }

    /**
     * Reads the engine's answer as JSON, or keeps it as text.
     *
     * @param body the answer
     * @return the JSON node
     */
    private JsonNode readOrText(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(body);
        }
    }

    /**
     * Names the point of sale of a basket that names none: its declared
     * system's, else the node's own; a node without either leaves it unnamed.
     *
     * @param request the basket or return request, completed in place
     * @param systemCode the declared system calling, or null
     */
    void completeStore(ObjectNode request, String systemCode) {
        if (!request.path("storeCode").asText("").isBlank()) {
            return;
        }
        String pdv = systemPdv(systemCode);
        if (pdv == null || pdv.isBlank()) {
            pdv = posSettingsService.pdvNumber();
        }
        if (!pdv.isBlank()) {
            request.put("storeCode", pdv.trim());
        }
    }

    /**
     * Injects the card promotions of the basket's card, when it names one and
     * a loyalty programme is configured (DG-01-01-01, -02).
     *
     * @param basket the basket, completed in place
     * @return the warning header value when the programme could not be read,
     *     null otherwise
     */
    String enrich(ObjectNode basket) {
        String card = basket.path("cardNumber").asText("").trim();
        if (card.isEmpty() || !fidCardPromotionClient.configured()) {
            return null;
        }
        try {
            basket.set("cardPromotions", fidCardPromotionClient.promotions(card));
            return null;
        } catch (Exception failure) {
            LOGGER.warnf("Promotions carte indisponibles pour un panier e-commerce (%s)", failure.getMessage());
            return FID_WARNING;
        }
    }

    /**
     * The point of sale a declared system is administered for.
     *
     * @param systemCode the system's code, or null
     * @return its point of sale, or null
     */
    String systemPdv(String systemCode) {
        if (systemCode == null) {
            return null;
        }
        ThirdPartySystem system = ThirdPartySystem.findByCode(systemCode);
        return system == null ? null : system.pdvNumber;
    }

    /**
     * Reads a JSON object.
     *
     * @param body the text, possibly null
     * @return the object, or null when the text is not a JSON object
     */
    ObjectNode parse(String body) {
        try {
            JsonNode node = objectMapper.readTree(body == null ? "" : body);
            return node instanceof ObjectNode object ? object : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether a basket identifier is well formed.
     *
     * @param id the identifier, possibly null
     * @return true when well formed
     */
    public static boolean validId(String id) {
        return id != null && id.trim().matches(ID_PATTERN);
    }

    /**
     * The refusal of an unreadable basket identifier.
     *
     * @return the 400 outcome
     */
    private static Outcome badId() {
        return new Outcome(400, "Identifiant de panier illisible", null);
    }
}
