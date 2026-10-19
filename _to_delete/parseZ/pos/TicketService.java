package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.recall.RecalledDocument;
import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.scanner.ScanContext;
import com.intermarche.pos.ui.valuation.ValuationService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * Ticket-building service: scan processing, item additions, price
 * modifications and ticket-level actions.
 * <p>
 * All monetary values are {@link BigDecimal} (phase 0). Weights coming from
 * the scale remain double at the hardware boundary and are converted when a
 * line is created.
 * <p>
 * Phase 0 lot 2: every cart mutation is followed by a draft synchronization
 * ({@link TicketPersistenceService#syncDraft}), so the sale survives a
 * register restart from the first article on.
 * <p>
 * {@code processScan} is the UNIFORM ENTRY of everything that names a
 * product or a code: scanner gun, simulator, manual typing, search-result
 * taps — one gate, one session guard (bypassed in training), one handler
 * chain assembled here from CDI in {@code @Priority} order (missing
 * annotation = 100). Uniformity is the guarantee that no path can invent
 * its own semantics: whatever adds to the cart went through the same
 * chain.
 */
@ApplicationScoped
public class TicketService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TicketService.class);

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** The control points of the sale (BO-10-02-07). */
    @Inject
    com.intermarche.pos.ui.endorsement.EndorsementService endorsementService;

    /** The back-office parameters (discount caps — LC-03-02). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** Internal scale used for intermediate unit-price divisions. */
    private static final int PRICE_SCALE = 4;

    /** Default VAT rate applied to unknown items without a catalog price (e.g. 0.20). */
    @ConfigProperty(name = "pos.vat.default-rate", defaultValue = "0.20")
    BigDecimal defaultVatRate;

    /**
     * Technical EAN of the manual deconsignment line (parameterized).
     * <p>
     * Register-generated lines carry a technical EAN like every other line:
     * the valuation engine prices the WHOLE ticket and must be able to
     * resolve each line it receives — a line without an EAN does not exist.
     * The referential must declare these codes on the engine side too.
     */
    @ConfigProperty(name = "pos.ean.deconsignment")
    String deconsignmentEan;

    /** Technical EAN of a manually typed unknown article (parameterized). */
    @ConfigProperty(name = "pos.ean.unknown-item")
    String unknownItemEan;

    @Inject
    HardwareService hardwareService;

    @Inject
    com.intermarche.pos.ui.balance.TareService tareService;

    @Inject
    TicketPersistenceService ticketPersistenceService;

    @Inject
    ValuationService valuationService;

    /**
     * The single post-mutation funnel (phase 7 lot 4): persists the draft,
     * then revalues the cart against the engine — every cart mutation of
     * this service ends here, so pricing follows the cart in real time
     * (synchronous local call; training, payment phase and open circuit
     * are guarded inside {@code revalue}).
     *
     * @param state the current POS state
     */
    private void syncAndRevalue(PosState state) {
        ticketPersistenceService.syncDraft(state);
        valuationService.revalue(state);
        noticeOnArticleMessage(state);
        warnOnExpensiveEntry(state);
    }

    /**
     * Shows the ARTICLE'S OWN MESSAGE when one has just been rung
     * (BO-03-08-09).
     *
     * <p>An information and never a refusal: "produit fragile", "vérifier la
     * date", "appeler le rayon". The line is registered, the sale goes on, and
     * the cashier reads the sentence the store attached to that article. An
     * article the shop wants to refuse is a control point's business, not a
     * sentence's.
     *
     * <p>Said BEFORE the price warning and not after, because the two share one
     * notice slot and the article's own message is the more specific of the
     * two: a store that wrote a sentence on an article wants that sentence
     * read, and a dear article says it is dear by its price.
     *
     * <p>An error already on the screen outranks both: a line that was refused
     * must not be covered by a notice about the line before it.
     *
     * @param state the current POS state
     */
    private void noticeOnArticleMessage(PosState state) {
        if (!state.lineJustEntered || state.ticket.transientError != null) {
            return;
        }
        // BO-03-10-13: the island may silence the messages tied to an article.
        // The flag is NOT consumed here: a blocked message is a message the
        // register never shows, and the next reader of lineJustEntered — the
        // high-price warning — must decide for itself.
        if (posSettingsService.islandBlockArticlePopups()) {
            return;
        }
        TicketState.TicketItem entered = enteredLine(state);
        if (entered == null || entered.ean == null || entered.ean.isBlank()) {
            return;
        }
        // The line carries the EAN and not the article's key, so the message is
        // read by the EAN the line was rung on — the same lookup the sale made.
        com.intermarche.pos.domain.catalog.Product product =
                com.intermarche.pos.domain.catalog.Product.find("ean", entered.ean).firstResult();
        if (product == null || product.infoMessage == null || product.infoMessage.isBlank()) {
            return;
        }
        state.lineJustEntered = false;
        state.ticket.setWarning(product.infoMessage.trim());
    }

    /**
     * Warns the cashier when the article just entered is dearer than the
     * administered threshold.
     *
     * <p>Placed on the post-mutation funnel rather than on each entry point:
     * a scan, a keyed EAN, a PLU, a group tile and the entry prompt all end
     * here, and all of them raise {@code lineJustEntered} through the single
     * {@code addItem} funnel. The flag is CONSUMED whatever the outcome, so a
     * gesture that follows the entry — a discount, a quantity change — never
     * replays the warning, and a second entry of the same article warns
     * again even though it merged into the line already there.
     *
     * <p>Three things it deliberately does not do. It never speaks over a
     * refusal or a confirmation already in the zone: the message that stopped
     * a gesture matters more than the one that comments on it. It reads the
     * UNIT price and not the line amount, because the rule is about the
     * article being dear, not about the basket growing — three articles at
     * four euros are not a dear article. And a threshold at zero switches it
     * off entirely rather than warning on everything.
     *
     * @param state the current POS state
     */
    private void warnOnExpensiveEntry(PosState state) {
        if (!state.lineJustEntered) {
            return;
        }
        state.lineJustEntered = false;
        if (state.ticket.transientError != null) {
            return;
        }
        // BO-03-10-13: silenced by the island, like the article message — the
        // flag above is still consumed, so the entry is accounted for either
        // way and the next entry starts clean.
        if (posSettingsService.islandBlockArticlePopups()) {
            return;
        }
        BigDecimal threshold = posSettingsService.saleLineWarningThreshold();
        if (threshold == null || threshold.signum() <= 0) {
            return;
        }
        TicketState.TicketItem entered = enteredLine(state);
        if (entered == null || entered.unitPrice == null
                || entered.unitPrice.compareTo(threshold) <= 0) {
            return;
        }
        state.ticket.setWarning(i18n.format("op.prixEleve", entered.label,
                entered.unitPrice.setScale(2, java.math.RoundingMode.HALF_UP)
                        .toPlainString().replace('.', ',')));
    }

    /**
     * Returns the line the last entry landed on, by the uid the funnel
     * recorded.
     *
     * @param state the current POS state
     * @return the entered line, or null when it is no longer in the ticket
     */
    private TicketState.TicketItem enteredLine(PosState state) {
        if (state.lastEnteredItemId == null) {
            return null;
        }
        for (TicketState.TicketItem item : state.ticket.items) {
            if (state.lastEnteredItemId.equals(item.uid)) {
                return item;
            }
        }
        return null;
    }

    @Inject
    CashSessionService cashSessionService;

    @Inject
    Instance<ScanContext.ScanHandler> scanHandlers;

    /** Whether an article may be rung on THIS register (BO-03-01-18). */
    @jakarta.inject.Inject
    ArticleScopeService articleScopeService;

    /**
     * The return flow, for the one scan the sale screen does not own: on the
     * return screen a scanned article designates the ORIGIN line to take back
     * (LC-05-02-11).
     */
    @Inject
    com.intermarche.pos.ui.returnprocess.RefundService refundService;

    /** The sale-screen service, for the cancel-by-scan gesture (EXP-36). */
    @Inject
    com.intermarche.pos.ui.home.HomeService homeService;

    @Inject
    PosState state;

    @Inject
    TicketPrinterService ticketPrinterService;

    /** The abandon rules: printing, and the reason the operator gave (LC-04-04). */
    @jakarta.inject.Inject
    TicketAbandonService ticketAbandonService;

    @Inject
    com.intermarche.pos.service.TechnicalEventService technicalEventService;

    /** The register's identity — which terminal's last sale to recover. */
    @Inject
    com.intermarche.pos.service.TicketNumberService ticketNumberService;

    /**
     * Applies (or clears, value 0) the ticket-level discount after its
     * manager endorsement (phase: global ticket discount). The base is what
     * the customer actually pays — AFTER the engine's offers — and the
     * amount is ventilated onto the lines at every recomputation (see
     * TicketState.allocateGlobalDiscount). Refused during an active payment.
     *
     * @param state the current POS state
     * @param type {@link PriceModType#GLOBAL_REMISE} (euros) or
     *             {@link PriceModType#GLOBAL_DISCOUNT} (percent)
     * @param value the endorsed value
     */
    public void applyGlobalDiscount(PosState state, PriceModType type, BigDecimal value) {
        LOGGER.info("Entering method applyGlobalDiscount with state: " + state + ", type: " + type + ", value: " + value);
        if (state.payment.paymentInProgress) {
            state.ticket.setError(i18n.get("op.terminezAnnulezTicketAbord"));
            LOGGER.info("Exiting method applyGlobalDiscount");
            return;
        }
        // The percentage cap is administered (LC-03-02-13); 100 stays the
        // absolute ceiling whatever the back office says.
        int cap = Math.min(100, posSettingsService.globalMaxDiscountPercent());
        if (value == null || value.signum() < 0
                || (type == PriceModType.GLOBAL_DISCOUNT
                        && value.compareTo(BigDecimal.valueOf(cap)) > 0)) {
            state.ticket.setError(i18n.get("op.valeurInvalide"));
            LOGGER.info("Exiting method applyGlobalDiscount");
            return;
        }
        // The EURO cap is administered separately (BO-03-07-04) and governs the
        // amount gesture only: a percentage is already bounded by its own cap.
        // Zero disables it, so an unadministered register behaves as before.
        BigDecimal amountCap = posSettingsService.globalMaxDiscountAmount();
        if (type == PriceModType.GLOBAL_REMISE && amountCap.signum() > 0
                && value.compareTo(amountCap) > 0) {
            state.ticket.setError(i18n.get("op.remiseSuperieurePlafond"));
            LOGGER.info("Exiting method applyGlobalDiscount");
            return;
        }
        // BO-03-07-12: the store decides whether the ticket-level gesture bears
        // on the lines that already carry a reduction. Read HERE and carried on
        // the ticket, because the allocation runs at every recomputation and has
        // no business reaching for the back office each time.
        state.ticket.globalDiscountSparesReducedLines =
                !posSettingsService.discountGlobalOnDiscounted();
        state.ticket.setGlobalDiscount(
                type == PriceModType.GLOBAL_DISCOUNT ? "PERCENT" : "AMOUNT", value);
        recalculateTotal(state);
        LOGGER.info("Exiting method applyGlobalDiscount");
    }

    /**
     * Suspends the sale on an age-restricted product: the add is PARKED and
     * the server-rendered prompt asks the cashier to check the customer's ID
     * (phase: age control). Returns false — no prompt — when the product is
     * unrestricted or the ticket already carries a verification at this
     * threshold or above (one ID check covers the whole sale).
     *
     * @param state the current POS state
     * @param product the resolved product
     * @param kind the replay kind (SCAN, PLU or EAN_QTY)
     * @param code the parked code to replay on confirmation
     * @param quantity the parked quantity (EAN_QTY kind), or null
     * @return true when the add was suspended behind the prompt
     */
    public boolean suspendForAgeCheck(PosState state, Product product, String kind,
                                      String code, BigDecimal quantity) {
        LOGGER.info("Entering method suspendForAgeCheck with state: " + state + ", product: " + product + ", kind: " + kind + ", code: " + code + ", quantity: " + quantity);
        if (product.ageRestriction == null
                || product.ageRestriction <= state.ticket.ageVerifiedThreshold) {
            LOGGER.info("Exiting method suspendForAgeCheck");
            return false;
        }
        state.ageCheck.active = true;
        state.ageCheck.productLabel = product.name.toUpperCase();
        state.ageCheck.threshold = product.ageRestriction;
        state.ageCheck.kind = kind;
        state.ageCheck.code = code;
        state.ageCheck.quantity = quantity;
        hardwareService.displayMessage(i18n.both("display.controleAgeCours"));
        state.touch();
        LOGGER.info("Exiting method suspendForAgeCheck");
        return true;
    }

    /**
     * Suspends the add on an article whose price or whose quantity the referential
     * does not carry ({@code LC-02-03-01} and {@code -03}).
     *
     * <p>THE PROMPT REPLACES NOTHING AND ADDS NOTHING: the article is the one that
     * was named, the line is the one that would have been created, and the only
     * difference is that a figure comes from the operator instead of the catalog. A
     * "prix requis" article — a gift card, a generic "Divers" line — has no price to
     * read; a decimal-quantity article has a price PER UNIT OF MEASURE and no
     * quantity, so the prompt shows that price and its unit while the operator keys
     * the metres or the litres.
     *
     * <p>The two CHAIN when an article carries both: the quantity is asked first,
     * because it is what the price will multiply, and the operator sees the figure
     * they already gave while giving the second.
     *
     * @param state    the current POS state
     * @param product  the resolved product
     * @param quantity the quantity already resolved, null treated as one
     * @return true when the add was suspended behind the prompt
     */
    public boolean suspendForEntry(PosState state, Product product, BigDecimal quantity) {
        LOGGER.info("Entering method suspendForEntry with state: " + state + ", product: " + product + ", quantity: " + quantity);
        boolean needsQuantity = ProductAttributes.quantityToEnter(product);
        // BO-03-13-10: on a manual return — one taken WITHOUT the original
        // ticket — the administered return type may ask the cashier to confirm
        // or change the price given back. It is the SAME prompt: the figure
        // comes from a person, the catalog price is shown beside it, and the
        // line is created once.
        boolean needsPrice = ProductAttributes.priceToEnter(product)
                || returnPriceConfirmed(state);
        if (!needsQuantity && !needsPrice) {
            LOGGER.info("Exiting method suspendForEntry");
            return false;
        }
        Price price = Price.findCurrentPrice(product.id);
        state.entryPrompt.clear();
        state.entryPrompt.active = true;
        state.entryPrompt.kind = needsQuantity
                ? PosState.EntryPromptState.QUANTITY
                : PosState.EntryPromptState.PRICE;
        state.entryPrompt.ean = product.ean;
        state.entryPrompt.label = translatedLabel(product);
        state.entryPrompt.unitName = product.unitName == null ? "" : product.unitName;
        state.entryPrompt.unitPriceFormatted = price == null || price.priceIncludingTax == null
                ? "" : String.format("%.2f", price.priceIncludingTax).replace('.', ',');
        state.entryPrompt.quantity = quantity == null ? BigDecimal.ONE : quantity;
        state.touch();
        LOGGER.info("Exiting method suspendForEntry");
        return true;
    }

    /**
     * Whether the return in progress asks for its price to be confirmed
     * (BO-03-13-10).
     *
     * <p>Only in RETURN mode, and only for the administered type the operator
     * chose: a sale is never asked to confirm a catalog price, and a return
     * taken from its original ticket reads the price off that ticket.
     *
     * @param state the current POS state
     * @return true when the price of the returned line must be confirmed
     */
    private boolean returnPriceConfirmed(PosState state) {
        if (!state.returnMode) {
            return false;
        }
        com.intermarche.pos.service.PosSettingsService.ReturnType type =
                posSettingsService.returnType(state.returnTypeLabel);
        return type != null && type.priceConfirmed();
    }

    /**
     * Takes the figure the operator keyed and either asks the second one or creates
     * the line ({@code LC-02-03-01/02/03}).
     *
     * @param state the current POS state
     * @param value the figure keyed: a decimal quantity or a price, depending on the
     *              prompt
     */
    public void confirmEntry(PosState state, BigDecimal value) {
        LOGGER.info("Entering method confirmEntry with state: " + state + ", value: " + value);
        if (!state.entryPrompt.active) {
            LOGGER.info("Exiting method confirmEntry");
            return;
        }
        if (value == null || value.signum() <= 0) {
            state.ticket.setError(i18n.get("op.valeurInvalide"));
            state.touch();
            LOGGER.info("Exiting method confirmEntry");
            return;
        }
        Product product = Product.findActiveByEan(state.entryPrompt.ean);
        if (product == null) {
            state.entryPrompt.clear();
            state.ticket.setError(i18n.get("op.produitIntrouvable"));
            state.touch();
            LOGGER.info("Exiting method confirmEntry");
            return;
        }
        if (state.entryPrompt.isPriceKind()) {
            state.entryPrompt.price = value;
        } else {
            state.entryPrompt.quantity = value;
            // The article may want its price too: ask it now rather than ringing a
            // line at a price nobody gave.
            if (ProductAttributes.priceToEnter(product)) {
                state.entryPrompt.kind = PosState.EntryPromptState.PRICE;
                state.touch();
                LOGGER.info("Exiting method confirmEntry");
                return;
            }
        }
        BigDecimal quantity = state.entryPrompt.quantity;
        BigDecimal keyedPrice = state.entryPrompt.price;
        state.entryPrompt.clear();
        addEnteredLine(state, product, quantity, keyedPrice);
        LOGGER.info("Exiting method confirmEntry");
    }

    /**
     * Gives up the suspended add: nothing is registered.
     *
     * @param state the current POS state
     */
    public void cancelEntry(PosState state) {
        LOGGER.info("Entering method cancelEntry with state: " + state);
        state.entryPrompt.clear();
        state.ticket.setError(i18n.get("op.saisieAbandonnee"));
        state.touch();
        LOGGER.info("Exiting method cancelEntry");
    }

    /**
     * Creates the line an entry prompt resolved.
     *
     * @param state      the current POS state
     * @param product    the article
     * @param quantity   the quantity to register
     * @param keyedPrice the price the operator gave, or null to use the catalog's
     */
    private void addEnteredLine(PosState state, Product product, BigDecimal quantity,
            BigDecimal keyedPrice) {
        Price price = Price.findCurrentPrice(product.id);
        BigDecimal unitPrice = keyedPrice != null
                ? keyedPrice
                : (price != null ? price.priceIncludingTax : BigDecimal.ZERO);
        BigDecimal vatRate = price != null && price.vatRate() != null
                ? price.vatRate() : defaultVatRate;
        if (ProductAttributes.vatExempt(product)) {
            vatRate = BigDecimal.ZERO;
        }
        state.ticket.addItem(product.ean, null, translatedLabel(product),
                unitPrice, quantity, vatRate);
        // addItem always leaves at least one line (it merges into an existing one
        // or appends a new one), so the list is never empty here.
        TicketState.TicketItem line = state.ticket.items.get(state.ticket.items.size() - 1);
        // LC-02-03-02: the unit of measure travels WITH the line, so the register
        // display, the customer display and the receipt all state "2,36 x 4,99 €/m"
        // without any of them going back to the referential.
        line.unitName = product.unitName;
        // LC-02-13-02: the DECIMAL-QUANTITY type travels with the line too, so the
        // quantity key knows that decimals are legitimate on this one without
        // reading the referential back.
        line.decimalQuantity = ProductAttributes.quantityToEnter(product);
        if (product.giftCardAmount != null) {
            line.moneyProduct = true;
        }
        if (ProductAttributes.discountForbidden(product)) {
            line.discountForbidden = true;
        }
        // BO-02-03-06: the meal-voucher eligibility travels with the line, so a
        // register the engine never answered still knows what may be settled
        // with a meal ticket.
        line.mealVoucherEligible = ProductAttributes.mealVoucherEligible(product);
        line.restrictedTenders =
                com.intermarche.pos.domain.catalog.attribute.RestrictedTender.snapshot(product);
        displayItem(line);
        syncAndRevalue(state);
    }

    /**
     * Confirms the ID check: the verification is remembered for the whole
     * ticket (highest threshold wins), the decision is journalized, and the
     * parked add REPLAYS through its original path — the sale resumes as if
     * the product had just been presented.
     *
     * @param state the current POS state
     */
    public void confirmAgeCheck(PosState state) {
        LOGGER.info("Entering method confirmAgeCheck with state: " + state);
        PosState.AgeCheckState pending = state.ageCheck;
        if (!pending.active) { LOGGER.info("Exiting method confirmAgeCheck"); return; }
        state.ticket.ageVerifiedThreshold =
                Math.max(state.ticket.ageVerifiedThreshold, pending.threshold);
        technicalEventService.log(
                com.intermarche.pos.domain.session.TechnicalEvent.EventType.AGE_CHECK_CONFIRMED,
                pending.productLabel + " (" + pending.threshold + "+) - ID vérifiée par "
                        + state.auth.operatorName);
        String kind = pending.kind;
        String code = pending.code;
        BigDecimal quantity = pending.quantity;
        pending.clear();
        switch (kind) {
            case "PLU" -> addItemByPlu(state, code);
            case "EAN_QTY" -> addItemByEan(state, code, quantity);
            default -> processScan(code);
        }
        LOGGER.info("Exiting method confirmAgeCheck");
    }

    /**
     * Refuses the sale of the restricted product: nothing is added, the
     * refusal is journalized (the legal trace), and the cashier gets the
     * transient refusal message.
     *
     * @param state the current POS state
     */
    public void refuseAgeCheck(PosState state) {
        LOGGER.info("Entering method refuseAgeCheck with state: " + state);
        PosState.AgeCheckState pending = state.ageCheck;
        if (!pending.active) { LOGGER.info("Exiting method refuseAgeCheck"); return; }
        technicalEventService.log(
                com.intermarche.pos.domain.session.TechnicalEvent.EventType.AGE_CHECK_REFUSED,
                pending.productLabel + " (" + pending.threshold + "+) - vente refusée par "
                        + state.auth.operatorName);
        pending.clear();
        state.ticket.setError(i18n.get("op.venteRefuseeControleAge"));
        state.touch();
        LOGGER.info("Exiting method refuseAgeCheck");
    }

    /**
     * Processes a scanned code by running it through the ordered scan handler chain.
     *
     * @param code the scanned code
     */
    public void processScan(String code) {
        LOGGER.info("Entering method processScan with code: " + code);
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        state.selectedTicketIndex = -1;

        // Phase 2 session gate: sale scans are refused while no session is
        // open (badge scans on the lock screen are unaffected).
        if (!state.isLocked() && !requireOpenSession(state)) {
            LOGGER.info("Exiting method processScan");
            return;
        }

        // EXP-36: the cancel-by-scan mode intercepts BEFORE the add chain — the
        // scanned code designates the line to cancel, it never rings a new one.
        // Handled at the single entry rather than as a chain handler, so it runs
        // ahead of every add-handler without disturbing their disjoint priorities.
        if (!state.isLocked() && state.cancelByScanArmed) {
            homeService.cancelByScan(code);
            if (!state.ticket.items.isEmpty() || state.payment.ticketDbId != null) {
                syncAndRevalue(state);
            }
            LOGGER.info("Exiting method processScan");
            return;
        }

        // LC-05-02-11: on the RETURN screen a scanned article designates the ORIGIN
        // line to take back, never a new sale line. Intercepted at the single
        // entry, like the cancel-by-scan mode above, so it runs ahead of every add
        // handler without disturbing their disjoint priorities. The gate is the
        // ticket selection itself, which the return screen drops when it is left.
        // A PENDING ENDORSEMENT takes precedence (BO-10-02): a refund awaiting a
        // manager's aval shows the endorsement modal over the return screen, and
        // the manager's badge must reach the badge handler to prefill it, not be
        // swallowed as an origin-line scan.
        if (!state.isLocked() && state.refund.isTicketSelected() && !state.endorsement.active) {
            refundService.enterArticleByCode(state, code);
            LOGGER.info("Exiting method processScan");
            return;
        }

        ScanContext ctx = new ScanContext(code, state);
        List<ScanContext.ScanHandler> handlers = StreamSupport.stream(scanHandlers.spliterator(), false)
                .sorted(Comparator.comparing(this::getPriority)).toList();
        handlers.stream().forEach(handler -> handler.handle(ctx));


        // Lot 2: single sync point for every scan-driven mutation (EAN/PLU
        // handlers, fidelity card, vouchers). No-op cost only when locked or
        // before the first article.
        if (!state.isLocked() && (!state.ticket.items.isEmpty() || state.payment.ticketDbId != null)) {
            syncAndRevalue(state);
        }
        LOGGER.info("Exiting method processScan");
    }

    /**
     * Processes a weight reported by the scale.
     *
     * @param weightStr the weight as text (comma or dot separator)
     */
    public void processWeight(String weightStr) {
        LOGGER.info("Entering method processWeight with weightStr: " + weightStr);
        if (state.isLocked()) { LOGGER.info("Exiting method processWeight"); return; }
        try {
            double w = Double.parseDouble(weightStr.replace(',', '.'));
            state.ticket.setWeight(w);
        } catch (NumberFormatException e) { System.err.println("Poids invalide: " + weightStr); }
        LOGGER.info("Exiting method processWeight");
    }

    /**
     * Resolves the {@link Priority} value of a scan handler, unwrapping CDI proxies.
     *
     * @param handler the scan handler
     * @return the priority value, or 100 when the annotation is absent
     */
    private int getPriority(ScanContext.ScanHandler handler) {
        Class<?> effectiveClass = handler.getClass();
        if (effectiveClass.getName().contains("$Proxy") || effectiveClass.getName().contains("_ClientProxy")) {
            effectiveClass = effectiveClass.getSuperclass();
        }
        Priority priority = effectiveClass.getAnnotation(Priority.class);
        return (priority != null) ? priority.value() : 100;
    }

    /**
     * Verifies that a cash session is open on this register before a sale
     * mutation; shows an error to the cashier otherwise.
     *
     * @param state the current POS state
     * @return true when a session is open, false otherwise (mutation refused)
     */
    private boolean requireOpenSession(PosState state) {
        if (state.trainingMode) return true; // training sells without a session
        if (cashSessionService.getOpenSession() != null) return true;
        state.ticket.setError(i18n.get("op.aucuneSessionOuverteMenu"));
        return false;
    }

    // --- CUSTOMER DISPLAY LOGIC ---

    /**
     * Shows a ticket line on the customer display, or the welcome message.
     *
     * @param item the line to show, or null for the welcome message
     */
    private void displayItem(TicketState.TicketItem item) {
        if (item == null) {
            hardwareService.displayMessage(i18n.get("paper.intermarche"));
            return;
        }
        String label = item.label;
        BigDecimal price = item.getTotalPrice();
        String msg = String.format("%s\t%.2fE", label, price);
        hardwareService.displayMessage(msg);
    }

    /**
     * Shows the last ticket line on the customer display, or the welcome
     * message when the ticket is empty.
     *
     * @param state the current POS state
     */
    private void displayLastItemOrWelcome(PosState state) {
        if (state.ticket.items.isEmpty()) {
            hardwareService.displayMessage(i18n.get("paper.intermarche"));
        } else {
            displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
        }
    }

    // --- PRICE MODIFICATIONS ---

    /**
     * Applies an absolute discount (euros) on the line total.
     *
     * @param item the target line
     * @param amount the discount amount in euros (strictly positive)
     */
    public void applyRemise(TicketState.TicketItem item, BigDecimal amount) {
        LOGGER.info("Entering method applyRemise with item: " + item + ", amount: " + amount);
        if (item == null || amount == null || amount.signum() <= 0) { LOGGER.info("Exiting method applyRemise"); return; }
        if (item.moneyProduct) { LOGGER.info("Exiting method applyRemise"); return; } // money products are never discounted
        if (item.discountForbidden) { LOGGER.info("Exiting method applyRemise"); return; } // BO-02-03-09: article bans discounts
        // BO-03-07-11: same rule as the percentage gesture — a second reduction
        // on an already-reduced line only lands where the store allows it.
        if (alreadyReduced(item) && !posSettingsService.discountCumulativeOnDiscounted()) {
            LOGGER.info("Exiting method applyRemise");
            return;
        }
        // P8: the entered amount is a UNIT remise; its effect on the line is that
        // amount times the quantity (10 articles à 10 €, remise 2 € → 80 €, not 98 €),
        // and a same article scanned afterwards rings at full price. The line
        // discount is therefore amount × quantity throughout.
        BigDecimal lineDiscount = amount.multiply(item.quantity);
        // The euro ceiling of a LINE remise is administered (BO-03-07-04);
        // zero disables it. The ceiling caps the LINE discount, at it passes.
        BigDecimal amountCap = posSettingsService.lineMaxDiscountAmount();
        if (amountCap.signum() > 0 && lineDiscount.compareTo(amountCap) > 0) {
            LOGGER.info("Exiting method applyRemise");
            return;
        }
        if (item.originalUnitPrice.signum() == 0 || item.originalUnitPrice.compareTo(item.unitPrice) == 0) {
            item.originalUnitPrice = item.unitPrice;
        }
        BigDecimal currentTotal = item.unitPrice.multiply(item.quantity);
        BigDecimal newTotal = currentTotal.subtract(lineDiscount);
        if (newTotal.signum() < 0) newTotal = BigDecimal.ZERO;
        if (item.quantity.signum() != 0) {
            item.unitPrice = newTotal.divide(item.quantity, PRICE_SCALE, RoundingMode.HALF_UP);
        } else {
            item.unitPrice = newTotal;
        }
        item.modifierLabel = String.format("Remise -%.2f€", amount);
        item.modifierType = PriceModType.REMISE;
        item.modifierValue = amount;
        displayItem(item);
        LOGGER.info("Exiting method applyRemise");
    }

    /**
     * Whether a line already carries a reduction (BO-03-07-11 and -12): a
     * manual gesture, or an automatic promotion the engine valued it below its
     * own price.
     *
     * <p>The two cases the questionnaire names, and the only two the line can
     * answer for: {@code modifierType} is the manual gesture, and a valued
     * total under the local one is the engine's offer.
     *
     * @param item the line, never null
     * @return true when the line is already reduced
     */
    private boolean alreadyReduced(TicketState.TicketItem item) {
        if (item.modifierType != null) {
            return true;
        }
        if (item.valuedTotal == null || item.unitPrice == null || item.quantity == null) {
            return false;
        }
        return item.valuedTotal.compareTo(item.unitPrice.multiply(item.quantity)) < 0;
    }

    /**
     * Applies the ready-made discount key the back office administers
     * (BO-03-07-03 and BO-03-07-06).
     *
     * <p>It decides NOTHING about the arithmetic: a PERCENT key runs
     * {@link #applyDiscount}, an AMOUNT key runs {@link #applyRemise}, so a
     * ready-made key and a keyed figure are capped, journaled and printed
     * exactly the same way. What the key adds is the figure — spared from the
     * numpad — and its own cumulation rule (BO-03-07-11): a key carrying
     * {@code CUMUL} applies on an already-reduced line whatever the default
     * says, and a key that does not carry it is refused there even where the
     * default allows it.
     *
     * @param item the target line, possibly null
     * @param label the label of the key the operator tapped, possibly null
     * @return true when the key was found and applied
     */
    public boolean applyPreset(TicketState.TicketItem item, String label) {
        LOGGER.info("Entering method applyPreset with label: " + label);
        com.intermarche.pos.service.PosSettingsService.DiscountPreset preset =
                posSettingsService.discountPreset(label);
        if (item == null || preset == null) {
            LOGGER.info("Exiting method applyPreset");
            return false;
        }
        if (alreadyReduced(item) && !preset.cumulative()) {
            LOGGER.info("Exiting method applyPreset");
            return false;
        }
        if (preset.percent()) {
            applyDiscount(item, preset.value());
        } else {
            applyRemise(item, preset.value());
        }
        LOGGER.info("Exiting method applyPreset");
        return true;
    }

    /**
     * Applies a percentage discount on the unit price.
     *
     * @param item the target line
     * @param percent the discount percentage (0 exclusive to 100 inclusive)
     */
    public void applyDiscount(TicketState.TicketItem item, BigDecimal percent) {
        LOGGER.info("Entering method applyDiscount with item: " + item + ", percent: " + percent);
        if (item != null && item.moneyProduct) { LOGGER.info("Exiting method applyDiscount"); return; } // money products are never discounted
        if (item != null && item.discountForbidden) { LOGGER.info("Exiting method applyDiscount"); return; } // BO-02-03-09: article bans discounts
        // BO-03-07-11: the line may already carry a reduction — a manual one or
        // an automatic promotion. Whether a second one piles on top is
        // administered, and a ready-made key may override the default (see
        // applyPreset). Refused here rather than silently stacked: a margin
        // eaten twice is the defect the questionnaire names.
        if (item != null && alreadyReduced(item)
                && !posSettingsService.discountCumulativeOnDiscounted()) {
            LOGGER.info("Exiting method applyDiscount");
            return;
        }
        // The line cap is administered (LC-03-02-07); 100 stays absolute.
        int lineCap = Math.min(100, posSettingsService.lineMaxDiscountPercent());
        if (item == null || percent == null || percent.signum() <= 0
                || percent.compareTo(BigDecimal.valueOf(lineCap)) > 0) {
            LOGGER.info("Exiting method applyDiscount");
            return;
        }
        if (item.originalUnitPrice.signum() == 0 || item.originalUnitPrice.compareTo(item.unitPrice) == 0) {
            item.originalUnitPrice = item.unitPrice;
        }
        BigDecimal reduction = item.unitPrice.multiply(percent)
                .divide(BigDecimal.valueOf(100), PRICE_SCALE, RoundingMode.HALF_UP);
        item.unitPrice = item.unitPrice.subtract(reduction);
        item.modifierLabel = String.format("Discount -%.2f%%", percent);
        item.modifierType = PriceModType.DISCOUNT;
        item.modifierValue = percent;
        displayItem(item);
        LOGGER.info("Exiting method applyDiscount");
    }

    /**
     * Forces the line total to a new price.
     *
     * @param item the target line
     * @param newTotalPrice the new line total (zero or positive)
     */
    public void forcePrice(TicketState.TicketItem item, BigDecimal newTotalPrice) {
        LOGGER.info("Entering method forcePrice with item: " + item + ", newTotalPrice: " + newTotalPrice);
        if (item != null && item.moneyProduct) { LOGGER.info("Exiting method forcePrice"); return; } // money products are never discounted
        if (item == null || newTotalPrice == null || newTotalPrice.signum() < 0) { LOGGER.info("Exiting method forcePrice"); return; }
        if (item.originalUnitPrice.signum() == 0 || item.originalUnitPrice.compareTo(item.unitPrice) == 0) {
            item.originalUnitPrice = item.unitPrice;
        }
        BigDecimal oldTotalPrice = item.originalUnitPrice.multiply(item.quantity);
        if (item.quantity.signum() != 0) {
            item.unitPrice = newTotalPrice.divide(item.quantity, PRICE_SCALE, RoundingMode.HALF_UP);
        } else {
            item.unitPrice = newTotalPrice;
        }
        // BO-10-07-12: the original price is recalled on screen only when the
        // back office administers it; masked, the line carries no "Prix initial".
        item.modifierLabel = posSettingsService.priceShowOriginalOnForce()
                ? String.format("Prix initial: %.2f€", oldTotalPrice) : null;
        item.modifierType = PriceModType.FORCE_PRICE;
        item.modifierValue = newTotalPrice;
        displayItem(item);
        LOGGER.info("Exiting method forcePrice");
    }

    /**
     * Recomputes the ticket total (fiscal rule: sum of line totals rounded to
     * the cent) and synchronizes the draft (covers line cancellations and
     * endorsed price modifications).
     *
     * @param state the current POS state
     */
    public void recalculateTotal(PosState state) {
        LOGGER.info("Entering method recalculateTotal with state: " + state);
        state.ticket.recomputeTotal();
        state.ticket.onChange();
        applyTicketTotalControl(state);
        syncAndRevalue(state);
        LOGGER.info("Exiting method recalculateTotal");
    }

    /**
     * Applies the TICKET TOTAL control point (BO-10-02-07).
     *
     * <p>Said after the total is recomputed and not before an article is
     * registered, because the figure the shop administers is a TOTAL: it is
     * only known once the basket is summed. The articles stay in the basket
     * whatever the level — a line already scanned is a fact — and what changes
     * is what the register says about it: nothing, a warning, or a refusal
     * naming the ceiling. The point is not endorsable: see {@link
     * com.intermarche.pos.ui.endorsement.ControlPoint#isEndorsable()}.
     *
     * <p>A shop administering no ceiling never reaches the level at all, which
     * is the default and the register's historical silence.
     *
     * @param state the current POS state
     */
    private void applyTicketTotalControl(PosState state) {
        java.math.BigDecimal ceiling = posSettingsService.controlTicketTotalMax();
        if (ceiling == null || state.ticket.totalAmount == null
                || state.ticket.totalAmount.compareTo(ceiling) <= 0) {
            return;
        }
        com.intermarche.pos.ui.endorsement.ControlLevel level =
                endorsementService.check(state, com.intermarche.pos.ui.endorsement.ControlPoint.TICKET_TOTAL);
        if (level == com.intermarche.pos.ui.endorsement.ControlLevel.WARN) {
            state.ticket.setWarning(endorsementService.controlMessage(
                    com.intermarche.pos.ui.endorsement.ControlPoint.TICKET_TOTAL, level));
        } else if (!level.allows()) {
            state.ticket.setError(endorsementService.controlMessage(
                    com.intermarche.pos.ui.endorsement.ControlPoint.TICKET_TOTAL, level));
        }
    }

    // --- STANDARD ACTIONS ---

    /**
     * Adds a product to the ticket by its EAN code.
     *
     * @param state the current POS state
     * @param ean the EAN code
     * @param quantity the quantity to add
     */
    /**
     * Whether this register REFUSES an article because a settlement is under
     * way, saying so to the cashier (BO-10-02-15).
     *
     * <p>The rule is one and the same wherever an article comes in by — scan,
     * keyed code, PLU — which is why it lives here and not in each handler.
     * The shop that allows the gesture gets it unchanged; the shop that does
     * not gets a refusal and a sentence, because a basket that grows while the
     * total is being settled is a total that no longer holds.
     *
     * @param state the register state
     * @return true when the article must not be rung, the message already set
     */
    public boolean saleRefusedDuringPayment(PosState state) {
        LOGGER.info("Entering method saleRefusedDuringPayment");
        if (posSettingsService.saleAllowedDuringPayment() || !state.payment.paymentInProgress) {
            LOGGER.info("Exiting method saleRefusedDuringPayment");
            return false;
        }
        state.ticket.setError(i18n.get("op.venteInterditePendantReglement"));
        LOGGER.info("Exiting method saleRefusedDuringPayment");
        return true;
    }

    public void addItemByEan(PosState state, String ean, BigDecimal quantity) {
        LOGGER.info("Entering method addItemByEan with state: " + state + ", ean: " + ean + ", quantity: " + quantity);
        if (ean == null || ean.isEmpty()) { LOGGER.info("Exiting method addItemByEan"); return; }
        if (!requireOpenSession(state)) { LOGGER.info("Exiting method addItemByEan"); return; }
        if (saleRefusedDuringPayment(state)) { LOGGER.info("Exiting method addItemByEan"); return; }
        state.selectedTicketIndex = -1;
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        Product p = Product.find("ean = ?1 and active = true", ean).firstResult();
        if (p == null) {
            // BO-02-03-04: keying the internal code reaches the same article as
            // its EAN would.
            p = Product.findActiveByInternalCode(ean);
        }
        if (p != null) {
            if (p.forbiddenToSale) {
                state.ticket.setError(i18n.get("op.produitInterditVente"));
                LOGGER.info("Exiting method addItemByEan");
                return;
            }
            // BO-03-01-18: an article the store took out of THIS island or
            // THIS register is refused here and nowhere else — the same
            // decision whatever road the article came in by.
            if (articleScopeService != null && !articleScopeService.soldHere(p)) {
                state.ticket.setError(i18n.get("op.articleNonVenduSurCetteCaisse"));
                LOGGER.info("Exiting method addItemByEan");
                return;
            }

            if (ProductAttributes.recall(p)) {
                state.ticket.setError(i18n.get("op.articleRetraitRappel"));
                LOGGER.info("Exiting method addItemByEan");
                return;
            }
            if (suspendForAgeCheck(state, p, "EAN_QTY", p.ean, quantity)) {
                LOGGER.info("Exiting method addItemByEan");
                return;
            }
            // LC-02-03-01/03 and LC-02-13-08: the quantity keyed or armed is carried
            // into the prompt, so an article that also wants its price is rung at the
            // quantity the operator gave and not at one.
            if (suspendForEntry(state, p, quantity)) {
                LOGGER.info("Exiting method addItemByEan");
                return;
            }
            Price price = Price.findCurrentPrice(p.id);
            BigDecimal finalPrice = (price != null) ? price.priceIncludingTax : BigDecimal.ZERO;
            BigDecimal vatRate = (price != null && price.vatRate() != null)
                    ? price.vatRate() : defaultVatRate;
            // BO-02-03-26/27: a VAT-exempt article is ventilated at rate 0; the
            // amount due (tax included) is unchanged, only its VAT breakdown.
            if (ProductAttributes.vatExempt(p)) {
                vatRate = BigDecimal.ZERO;
            }
            // PLU is null: unit sale. Line label is the checkout label when set.
            state.ticket.addItem(p.ean, null, translatedLabel(p), finalPrice, quantity, vatRate);
            TicketState.TicketItem line = state.ticket.items.get(state.ticket.items.size() - 1);
            if (p.giftCardAmount != null) {
                line.moneyProduct = true;
            }
            if (ProductAttributes.discountForbidden(p)) {
                line.discountForbidden = true;
            }
            line.mealVoucherEligible = ProductAttributes.mealVoucherEligible(p);
            // LC-09-01-11 to -18: what the article may be paid with.
            line.restrictedTenders =
                    com.intermarche.pos.domain.catalog.attribute.RestrictedTender.snapshot(p);
            displayItem(line);
            syncAndRevalue(state);
            // LC-02-03-13: a keyed EAN or internal code names no lot, so the recalled
            // lots are listed and the cashier reads the one printed on the pack.
            noticeRecalledLots(state, p);
        } else {
            state.ticket.setError(i18n.get("op.produitIntrouvable"));
        }
        LOGGER.info("Exiting method addItemByEan");
    }

    /**
     * Lists the recalled lots of an article whose entry named none ({@code
     * LC-02-03-13}).
     *
     * <p>It is raised AFTER the line is registered, because registering clears the
     * message area — a notice set before would be wiped by the very gesture it is
     * about.
     *
     * @param state   the register state
     * @param product the article that was just registered
     */
    private void noticeRecalledLots(PosState state, Product product) {
        String message = ProductAttributes.recalledLotsMessage(product);
        if (message != null) {
            state.ticket.setNotice(message);
        }
    }

    /**
     * Adds a weighed product to the ticket by its PLU code, requesting a weighing.
     *
     * @param state the current POS state
     * @param pluCode the PLU code
     */
    public void addItemByPlu(PosState state, String pluCode) {
        LOGGER.info("Entering method addItemByPlu with state: " + state + ", pluCode: " + pluCode);
        if (!requireOpenSession(state)) { LOGGER.info("Exiting method addItemByPlu"); return; }
        if (saleRefusedDuringPayment(state)) { LOGGER.info("Exiting method addItemByPlu"); return; }
        state.selectedTicketIndex = -1;
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        Product p = Product.findActiveByPlu(pluCode);
        if (p != null) {
            if (p.forbiddenToSale) {
                state.ticket.setError(i18n.get("op.produitInterditVente"));
                LOGGER.info("Exiting method addItemByPlu");
                return;
            }
            // BO-03-01-18: an article the store took out of THIS island or
            // THIS register is refused here and nowhere else — the same
            // decision whatever road the article came in by.
            if (articleScopeService != null && !articleScopeService.soldHere(p)) {
                state.ticket.setError(i18n.get("op.articleNonVenduSurCetteCaisse"));
                LOGGER.info("Exiting method addItemByPlu");
                return;
            }

            if (ProductAttributes.recall(p)) {
                state.ticket.setError(i18n.get("op.articleRetraitRappel"));
                LOGGER.info("Exiting method addItemByPlu");
                return;
            }
            // Age gate BEFORE weighing: the scale read is consumed on the
            // replay only, so a confirmed check weighs exactly once.
            if (suspendForAgeCheck(state, p, "PLU", pluCode, null)) {
                LOGGER.info("Exiting method addItemByPlu");
                return;
            }
            // LC-14-01-03/04/05/06/07: resolve the software tare (container >
            // article > default) and SEND it to the scale. The scale nets it
            // and returns the net weight, which is recorded as-is below — the
            // register never re-deducts (non-cumulation). The punctual
            // container tare is consumed straight away: it arms one weighing.
            BigDecimal tare = tareService.resolve(p, state);
            // The article is chosen, the plate may still be empty: wait for it to
            // be loaded instead of refusing the weighing outright. Choosing then
            // laying down is the order the screen asks for, and it did not work.
            double weight = hardwareService.awaitWeighing(tare);
            tareService.consume(state);
            if (weight <= 0) { state.ticket.setError(i18n.get("op.poidsInvalide")); LOGGER.info("Exiting method addItemByPlu"); return; }
            if (!Double.isNaN(state.ticket.lastRecordedWeight) && Double.compare(weight, state.ticket.lastRecordedWeight) == 0) { state.ticket.setError(i18n.get("op.erreurPoidsIdentique")); LOGGER.info("Exiting method addItemByPlu"); return; }

            state.ticket.lastRecordedWeight = weight;
            Price price = Price.findCurrentPrice(p.id);
            BigDecimal unitPrice = (price != null) ? price.priceIncludingTax : BigDecimal.ZERO;
            BigDecimal vatRate = (price != null && price.vatRate() != null)
                    ? price.vatRate() : defaultVatRate;
            // BO-02-03-26/27: a VAT-exempt weighed article ventilates at rate 0.
            if (ProductAttributes.vatExempt(p)) {
                vatRate = BigDecimal.ZERO;
            }
            BigDecimal quantityKg = BigDecimal.valueOf(weight).setScale(3, RoundingMode.HALF_UP);

            // Real PLU code carried by the line; EAN left null. Checkout label
            // when set, else the commercial name.
            state.ticket.addItem(p.ean, pluCode, translatedLabel(p), unitPrice, quantityKg, vatRate);
            TicketState.TicketItem line = state.ticket.items.get(state.ticket.items.size() - 1);
            // LC-14-01-07/08: snapshot the article tare so a later MANUAL weight
            // re-entry can deduct it without a fresh catalog lookup.
            line.tareKg = ProductAttributes.tare(p);
            if (ProductAttributes.discountForbidden(p)) {
                line.discountForbidden = true;
            }
            line.mealVoucherEligible = ProductAttributes.mealVoucherEligible(p);
            // LC-09-01-11 to -18: what the article may be paid with.
            line.restrictedTenders =
                    com.intermarche.pos.domain.catalog.attribute.RestrictedTender.snapshot(p);

            displayItem(line);
            syncAndRevalue(state);
            // LC-02-03-13: a PLU names no lot either.
            noticeRecalledLots(state, p);
        } else {
            state.ticket.setError(i18n.get("op.pluIntrouvable"));
        }
        LOGGER.info("Exiting method addItemByPlu");
    }

    /**
     * Adds an unknown (unlisted) item with a typed label and price.
     *
     * @param state the current POS state
     * @param label the label typed by the cashier
     * @param priceStr the price typed by the cashier
     */
    public void addUnknownItem(PosState state, String label, String priceStr) {
        LOGGER.info("Entering method addUnknownItem with state: " + state + ", label: " + label + ", priceStr: " + priceStr);
        if (!requireOpenSession(state)) { LOGGER.info("Exiting method addUnknownItem"); return; }
        state.selectedTicketIndex = -1;
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        try {
            BigDecimal price = new BigDecimal(priceStr.replace(',', '.').replace(" ", ""));
            if (price.signum() >= 0 && label != null && !label.isEmpty()) {
                state.ticket.addItem(unknownItemEan, null, label.toUpperCase(),
                        price, BigDecimal.ONE, defaultVatRate);
                displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
                syncAndRevalue(state);
            }
        } catch (Exception e) {
            state.ticket.setError(i18n.get("op.erreurPrixSaisi"));
        }
        LOGGER.info("Exiting method addUnknownItem");
    }

    /**
     * Adds an article at a free price under a FAMILY CODE (EXP-24): the cashier
     * keys a family code then a price — "80" then "235" rings an "Epicerie 2,35 €"
     * line — the family carrying the LABEL. The family's VAT rate is not modelled
     * on {@link com.intermarche.pos.domain.catalog.ProductFamily} (it has no VAT
     * column), so the register's default VAT applies — a named assumption of
     * EXP-24 recorded here.
     *
     * @param state the current POS state
     * @param familyCode the family code keyed by the cashier
     * @param priceStr the price keyed by the cashier
     */
    public void addFamilyPricedItem(PosState state, String familyCode, String priceStr) {
        LOGGER.info("Entering method addFamilyPricedItem with familyCode: " + familyCode + ", priceStr: " + priceStr);
        if (!requireOpenSession(state)) { LOGGER.info("Exiting method addFamilyPricedItem"); return; }
        state.selectedTicketIndex = -1;
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        com.intermarche.pos.domain.catalog.ProductFamily family =
                (familyCode == null || familyCode.isBlank())
                        ? null : com.intermarche.pos.domain.catalog.ProductFamily.findByCode(familyCode.trim());
        if (family == null) {
            state.ticket.setError(i18n.get("op.familleInconnue"));
            state.touch();
            LOGGER.info("Exiting method addFamilyPricedItem");
            return;
        }
        try {
            BigDecimal price = new BigDecimal(priceStr.replace(',', '.').replace(" ", ""));
            if (price.signum() >= 0) {
                String label = family.description == null ? family.code : family.description;
                state.ticket.addItem(unknownItemEan, null, label.toUpperCase(),
                        price, BigDecimal.ONE, defaultVatRate);
                displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
                syncAndRevalue(state);
            } else {
                state.ticket.setError(i18n.get("op.erreurPrixSaisi"));
            }
        } catch (Exception e) {
            state.ticket.setError(i18n.get("op.erreurPrixSaisi"));
        }
        LOGGER.info("Exiting method addFamilyPricedItem");
    }

    /**
     * Adds a deposit-return line to the ticket (out of VAT scope: zero rate).
     *
     * @param state the current POS state
     */
    public void addDeposit(PosState state) {
        LOGGER.info("Entering method addDeposit with state: " + state);
        if (!requireOpenSession(state)) { LOGGER.info("Exiting method addDeposit"); return; }
        state.selectedTicketIndex = -1;
        state.ticket.addItem(deconsignmentEan, null, "DECONSIGNATION",
                new BigDecimal("-1.00"), BigDecimal.ONE, BigDecimal.ZERO);
        displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
        syncAndRevalue(state);
        LOGGER.info("Exiting method addDeposit");
    }

    /**
     * Loads a recalled back-office document (EXP-90/91/92) into the current sale:
     * a quote, an order, a sale voucher or a click-and-collect basket, whose lines
     * are added through the SAME {@code addItem} funnel as a scan so the valuation,
     * the subtotal marker and the persisted draft all behave. For an order that
     * carries a down-payment already collected (EXP-85), that acompte is registered
     * as a payment so it is DEDUCTED from the amount due — "repris sur commande".
     *
     * <p>Guards, out loud: no open session refuses as any sale scan does; a null
     * document (unknown code) sets "DOCUMENT INTROUVABLE"; a document with no line
     * sets "DOCUMENT VIDE" and loads nothing.
     *
     * @param state the current POS state
     * @param doc the recalled document, or null when the code resolved to nothing
     */
    public void loadRecalledDocument(PosState state, RecalledDocument doc) {
        LOGGER.info("Entering method loadRecalledDocument");
        if (!requireOpenSession(state)) { LOGGER.info("Exiting method loadRecalledDocument"); return; }
        if (doc == null) {
            state.ticket.setError(i18n.get("op.documentIntrouvable"));
            state.touch();
            LOGGER.info("Exiting method loadRecalledDocument");
            return;
        }
        if (doc.lines == null || doc.lines.isEmpty()) {
            state.ticket.setError(i18n.get("op.documentVide"));
            state.touch();
            LOGGER.info("Exiting method loadRecalledDocument");
            return;
        }
        state.selectedTicketIndex = -1;
        for (RecalledDocument.Line line : doc.lines) {
            state.ticket.addItem(line.ean(), null, line.label(),
                    line.unitPrice(), line.quantity(), line.vatRate());
        }
        if (doc.hasAcompte()) {
            // EXP-85: the down-payment already collected on the order is credited
            // as a payment, so the remaining due is the balance to settle now.
            state.payment.addPayment("ACOMPTE", doc.acompteAlreadyPaid);
        }
        recalculateTotal(state);
        displayLastItemOrWelcome(state);
        state.ticket.setNotice(i18n.format("op.documentCharge",
                doc.getKindLabel(), (doc.code == null ? "" : doc.code)));
        syncAndRevalue(state);
        LOGGER.info("Exiting method loadRecalledDocument");
    }

    /**
     * Cancels a ticket line by its uid and refreshes total and display.
     *
     * @param state the current POS state
     * @param uid the uid of the line to cancel
     */
    public void cancelItemById(PosState state, String uid) {
        LOGGER.info("Entering method cancelItemById with state: " + state + ", uid: " + uid);
        // Conserve the cancelled article (lot C4, BO-04-01-16): mark the draft
        // line BEFORE it leaves the cart, so the reconciliation keeps it as a
        // witness. Training and pre-draft carts have no id — the marking is
        // skipped exactly where nothing fiscal is persisted anyway.
        if (state.payment.ticketDbId != null) {
            ticketPersistenceService.markLineCancelled(
                    state.payment.ticketDbId, uid, state.auth.operatorBadgeId);
        }
        // The line LEAVES THE CART AND STAYS ON THE SCREEN, struck through: what
        // was cancelled is a fact of the sale the operator and the customer must
        // still be able to point at.
        state.ticket.cancelLine(uid);
        recalculateTotal(state);
        state.selectedTicketIndex = -1;
        displayLastItemOrWelcome(state);
        LOGGER.info("Exiting method cancelItemById");
    }

    /**
     * Cancels the whole ticket: marks the database draft as cancelled, then
     * clears the in-memory state and resets the customer display.
     *
     * @param state the current POS state
     */
    public void cancelTicket(PosState state) {
        LOGGER.info("Entering method cancelTicket with state: " + state);
        // Cancel the draft before clearTicket() nulls its id
        if (state.payment.ticketDbId != null) {
            // LC-04-04-11/12: the abandon ticket states a sale that is about to stop
            // existing, so it is printed BEFORE the draft is cancelled — after it,
            // there would be nothing left to read.
            if (ticketAbandonService.printsTicket()) {
                ticketPrinterService.printAbandonTicket(state.payment.ticketDbId,
                        state.abandonReason, posSettingsService.abandonPrintDetail(),
                        state.auth.operatorName);
            }
            ticketPersistenceService.cancelDraft(state.payment.ticketDbId);
        }
        // LC-04-04-10: the reason is journalled whether or not it was printed — the
        // back-office reports count abandons by reason, and a paper the shop chose
        // not to print must not take the reason with it.
        technicalEventService.log(
                com.intermarche.pos.domain.session.TechnicalEvent.EventType.TICKET_CANCELLED,
                state.abandonReason == null || state.abandonReason.isBlank()
                        ? "Abandon ticket" : "Abandon ticket : " + state.abandonReason);
        state.clearTicket();
        hardwareService.displayMessage(i18n.get("paper.intermarche"));
        LOGGER.info("Exiting method cancelTicket");
    }

    /**
     * Resolves the ticket the DERNIER keys work on: the last sale this register
     * closed.
     *
     * <p>{@code PosState.lastClosedTicketId} only remembers a sale closed by
     * THIS process. It is the right answer while the register runs, and the
     * wrong one the moment it restarts: the ticket is still in the database, the
     * register still printed it a minute ago, and every DERNIER key answered
     * "AUCUN TICKET" — four keys that looked broken because the memory of the
     * sale, not the sale, had gone. The database is asked once and the answer is
     * kept in the state.
     *
     * @param state the current POS state
     * @return the id of the last closed ticket of this register, or null when it
     *         has closed none
     */
    public Long resolveLastClosedTicketId(PosState state) {
        LOGGER.info("Entering method resolveLastClosedTicketId with state: " + state);
        if (state.lastClosedTicketId == null) {
            Ticket recovered = Ticket.findLastClosedByTerminal(ticketNumberService.getTerminalId());
            if (recovered != null) {
                state.lastClosedTicketId = recovered.id;
            }
        }
        LOGGER.info("Exiting method resolveLastClosedTicketId");
        return state.lastClosedTicketId;
    }

    /**
     * Reprints a closed ticket by its database id.
     *
     * @param ticketId the ticket database id
     */
    public void reprintTicket(Long ticketId) {
        LOGGER.info("Entering method reprintTicket with ticketId: " + ticketId);
        if (ticketId != null) {
            ticketPrinterService.printTicket(ticketId);
        }
        LOGGER.info("Exiting method reprintTicket");
    }

    /**
     * Prints the identification barcode of a closed ticket, alone (LC-08-01-04).
     *
     * @param ticketId the ticket database id
     */
    public void printTicketIdentityBarcode(Long ticketId) {
        LOGGER.info("Entering method printTicketIdentityBarcode with ticketId: " + ticketId);
        if (ticketId != null) {
            ticketPrinterService.printTicketIdentityBarcode(ticketId);
        }
        LOGGER.info("Exiting method printTicketIdentityBarcode");
    }

    /**
     * Prints a duplicate of a ticket's card receipt, carrying the DUPLICATA mention
     * (LC-08-05-09).
     *
     * @param ticketId the ticket database id
     * @return the number of slips printed; zero when the sale carried no card
     */
    public int printCardReceiptDuplicate(Long ticketId) {
        LOGGER.info("Entering method printCardReceiptDuplicate with ticketId: " + ticketId);
        if (ticketId != null) {
            LOGGER.info("Exiting method printCardReceiptDuplicate");
            return ticketPrinterService.printCardReceipt(ticketId, false, "DUPLICATA");
        }
        LOGGER.info("Exiting method printCardReceiptDuplicate");
        return 0;
    }

    /**
     * The article's wording in the language the till is set to, upper-cased as
     * every line label is (BO-03-01-20).
     *
     * <p>The administered translation wins; the catalog's own sale label is the
     * fallback, which is what every line carried before translations existed.
     *
     * @param product the article
     * @return the line label
     */
    private String translatedLabel(com.intermarche.pos.domain.catalog.Product product) {
        String language = i18n == null ? null : i18n.getLang();
        String label = com.intermarche.pos.domain.catalog.attribute.ProductAttributes
                .saleLabel(product, language);
        return label == null ? "" : label.toUpperCase();
    }
}
