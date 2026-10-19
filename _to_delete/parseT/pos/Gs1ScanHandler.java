package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.barcode.AlertLevel;
import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.domain.barcode.gs1.Gs1ApplicationIdentifier;
import com.intermarche.pos.domain.barcode.gs1.Gs1Message;
import com.intermarche.pos.domain.barcode.gs1.Gs1Parser;
import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.VoucherService;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Reads a GS1 code at the till — Databar, DataMatrix or QR code, Element String or
 * Digital Link URI — and does what its identifiers say ({@code LC-11-01-11},
 * {@code LC-11-02-01/03/06}, {@code LC-11-03-02} to {@code -23}).
 *
 * <p>ONE HANDLER FOR THREE SYMBOLS AND TWO SYNTAXES, because by the time a payload
 * reaches this chain the symbol it was printed as no longer exists: the reader has
 * turned it into a string. What differs between a Databar and a QR code is the optics,
 * not the meaning, and building three handlers would be building three chances to
 * disagree about what {@code (17)} means.
 *
 * <p>ITS RECOGNITION DOMAIN IS DISJOINT from the other handlers at this priority
 * (fidelity card, deposit voucher, 2x scale label): a payload is only claimed when it
 * carries a GS1 marker — parentheses, a symbology identifier, an FNC1 separator or a
 * Digital Link address — which none of theirs does, and which an ordinary EAN-13 does
 * not either.
 *
 * <p>IT REFUSES NOTHING IT CANNOT NAME. An identifier this version does not know is
 * journalled and stepped over, never treated as an error ({@code LC-11-03-03}): a
 * supplier adding one to its labels must not be able to stop a lane. And a payload that
 * decodes to nothing usable leaves {@code handled} false, so the chain goes on and the
 * code gets its chance with the handlers that follow.
 */
@ApplicationScoped
@Priority(1)
public class Gs1ScanHandler implements ScanContext.ScanHandler {

    /** Whether an article may be rung on THIS register (BO-03-01-18). */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.ticket.ArticleScopeService articleScopeService;

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** Applies a GS1 gift document or coupon as a settlement. */
    @Inject
    VoucherService voucherService;

    /** The shop's rules: expiry levels, and the settlement types of GS1 coupons. */
    @Inject
    PosSettingsService posSettingsService;

    /** The technical journal, which receives every decoded identifier. */
    @Inject
    TechnicalEventService technicalEventService;

    /** Default VAT rate applied when no catalog price is found. */
    @ConfigProperty(name = "pos.vat.default-rate", defaultValue = "0.20")
    BigDecimal defaultVatRate;

    /**
     * Handles a scan by decoding it as GS1 and acting on what it carries.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) {
            return;
        }
        PosState state = ctx.state;
        if (state.isLocked()) {
            return;
        }
        Gs1Message message = Gs1Parser.parse(ctx.code);
        if (message.isEmpty()) {
            return;
        }
        // A payload that does not SAY it is GS1 — the bare concatenation, all digits —
        // is a plausible reading and not a certainty: a long numeric loyalty card or a
        // badge could be read the same way. It is therefore claimed only if it turns out
        // to name something, and otherwise released to the handlers that follow, in
        // silence. A marked payload is claimed outright and its failures are said out
        // loud, because nothing else in the chain could have wanted it.
        boolean marked = Gs1Parser.hasExplicitMarker(ctx.code);
        // LC-11-03-02: the technical half of the recording, written BEFORE anything is
        // decided. A code refused further down is exactly the code someone will come
        // looking for, and a journal that only holds the successes cannot answer them.
        if (marked) {
            technicalEventService.log(TechnicalEvent.EventType.GS1_DECODED,
                    journalDetail(message));
        }
        if (message.has(Gs1ApplicationIdentifier.GDTI)
                || message.has(Gs1ApplicationIdentifier.GCN)) {
            ctx.handled = marked && handleSettlement(state, message);
            return;
        }
        if (message.getArticleCodes().isEmpty()) {
            // The payload decoded, but names nothing this register sells or takes in
            // payment. The chain goes on.
            return;
        }
        Product product = findArticle(message.getArticleCodes());
        if (product == null && !marked) {
            return;
        }
        if (!marked) {
            technicalEventService.log(TechnicalEvent.EventType.GS1_DECODED,
                    journalDetail(message));
        }
        ctx.handled = handleArticle(state, message, product);
    }

    /**
     * Builds the journal line of a decoded payload, capped to the column that holds it.
     *
     * @param message the decoded payload
     * @return every decoded identifier, in encoding order
     */
    private String journalDetail(Gs1Message message) {
        String detail = message.describe();
        return detail.length() <= 250 ? detail : detail.substring(0, 250);
    }

    // --------------------------------------------------
    // Settlements (LC-11-03-05 / -06)
    // --------------------------------------------------

    /**
     * Applies a GS1 gift document ({@code AI 253}) or coupon ({@code AI 255}) as a
     * settlement.
     *
     * <p>The identifier says WHAT the paper is; the back office says which settlement
     * type of this store it is ({@code LC-11-03-05/06}). Nothing is hard-coded here on
     * purpose: a store's gift cheques and its coupons are its own referential, and a
     * register that knew an issuer by heart would be a register to redeploy every time
     * a partner changes.
     *
     * @param state   the register state
     * @param message the decoded payload
     * @return true when the payload was consumed, whether it was applied or refused
     */
    private boolean handleSettlement(PosState state, Gs1Message message) {
        boolean gift = message.has(Gs1ApplicationIdentifier.GDTI);
        String number = gift
                ? message.value(Gs1ApplicationIdentifier.GDTI)
                : message.value(Gs1ApplicationIdentifier.GCN);
        CouponType type = gift ? giftTypeOf(number) : couponType();
        if (type == null) {
            state.ticket.setError(i18n.get(gift ? "op.chequeCadeauGs1Non" : "op.couponGs1NonParametre"));
            return true;
        }
        // A payment paper is presented at the payment. Outside it, the cashier is told
        // the moment rather than the code — the code IS known, which is exactly what
        // the generic "unknown code" would deny.
        if (!state.payment.paymentInProgress) {
            state.ticket.setError(i18n.get("op.bonValablePhasePaiement"));
            return true;
        }
        // LC-11-03-13: an expired coupon is refused or announced, as the shop says.
        if (isExpiredCoupon(state, message)) {
            return true;
        }
        // LC-11-03-21: (390n) carries the coupon's own value. Present, it settles the
        // amount the paper states and nothing has to be keyed; absent, the settlement
        // type decides where the amount comes from, exactly as for a scanned voucher.
        BigDecimal encoded = message.decimal(Gs1ApplicationIdentifier.AMOUNT_PAYABLE);
        if (encoded != null && encoded.signum() > 0) {
            voucherService.applyManualVoucher(state, type, number, encoded);
            return true;
        }
        if (type.amountSource == CouponType.AmountSource.REGISTRY) {
            voucherService.applyRegistryVoucher(state, type, number);
            return true;
        }
        if (type.requiresManualAmount()) {
            state.payment.clearPendingVoucher();
            state.payment.voucherPanelOpen = true;
            state.payment.pendingVoucherTypeCode = type.code;
            state.payment.pendingVoucherLabel = type.label;
            state.payment.pendingVoucherNumber = number;
            state.payment.pendingVoucherNeedsAmount = true;
            state.touch();
            return true;
        }
        voucherService.applyEncodedVoucher(state, type, number);
        return true;
    }

    /**
     * Resolves the settlement type of a gift document from its issuer
     * ({@code LC-11-03-05}).
     *
     * <p>THE LONGEST PREFIX WINS. Issuer prefixes are nested by construction — a
     * company prefix is an extension of its country's — so a shop that administers both
     * a general rule and a finer one for one partner must get the finer one.
     *
     * @param number the gift document's number, as encoded
     * @return the settlement type, or null when the store administered none for it
     */
    private CouponType giftTypeOf(String number) {
        if (number == null || number.isBlank()) {
            return null;
        }
        String best = null;
        String code = null;
        for (String entry : posSettingsService.gs1GiftCouponTypes().split(";")) {
            String[] pair = entry.split(":");
            if (pair.length != 2) {
                continue;
            }
            String prefix = pair[0].trim();
            if (prefix.isEmpty() || !number.startsWith(prefix)) {
                continue;
            }
            if (best == null || prefix.length() > best.length()) {
                best = prefix;
                code = pair[1].trim();
            }
        }
        return code == null ? null : CouponType.findActiveByCode(code);
    }

    /**
     * Resolves the settlement type of a GS1 coupon ({@code LC-11-03-06}).
     *
     * @return the settlement type, or null when the store administered none
     */
    private CouponType couponType() {
        String code = posSettingsService.gs1CouponType().trim();
        return code.isEmpty() ? null : CouponType.findActiveByCode(code);
    }

    /**
     * Applies the shop's rule to a coupon's expiry date ({@code LC-11-03-13}).
     *
     * @param state   the register state
     * @param message the decoded payload
     * @return true when the coupon is refused
     */
    private boolean isExpiredCoupon(PosState state, Gs1Message message) {
        LocalDate expiry = message.date(Gs1ApplicationIdentifier.EXPIRY);
        AlertLevel level = AlertLevel.of(posSettingsService.gs1CouponExpiryAlert());
        // A coupon is expired on the day AFTER its date: "atteinte" is still a valid
        // day, which is how a customer reads the date printed on the paper.
        if (expiry == null || !level.speaks() || !expiry.isBefore(LocalDate.now())) {
            return false;
        }
        String message1 = i18n.format("op.bonExpireLe", expiry.format(
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        state.ticket.setError(message1);
        return level.blocks();
    }

    // --------------------------------------------------
    // Articles (LC-11-03-04 and the identifiers riding with it)
    // --------------------------------------------------

    /**
     * Registers the article a GS1 code names, with the quantity, the weight and the
     * price its identifiers carry.
     *
     * @param state   the register state
     * @param message the decoded payload
     * @param product the catalog article the payload names, or null when it names none
     *                this register knows
     * @return true when the payload was consumed, whether a line was created or refused
     */
    private boolean handleArticle(PosState state, Gs1Message message, Product product) {
        if (product == null) {
            state.ticket.setError(i18n.format("op.articleGs1Introuvable", message.getArticleCode()));
            return true;
        }
        // BO-03-01-18: same decision on the GS1 road.
        if (articleScopeService != null && !articleScopeService.soldHere(product)) {
            ctx.state.ticket.setError(i18n.get("op.articleNonVenduSurCetteCaisse"));
            ctx.handled = true;
            return;
        }
        if (product.forbiddenToSale) {
            state.ticket.setError(i18n.get("op.produitInterditVente"));
            return true;
        }
        if (ProductAttributes.recall(product)) {
            state.ticket.setError(i18n.get("op.articleRetraitRappel"));
            return true;
        }
        // LC-02-03-12: the code names the lot, and that lot is one of the recalled
        // ones. THIS IS THE ONE CASE WHERE THE REGISTER KNOWS, so it refuses the line
        // instead of asking the cashier to read the pack — the pack already said so.
        String batch = message.value(Gs1ApplicationIdentifier.BATCH);
        if (ProductAttributes.lotRecalled(product, batch)) {
            state.ticket.setError(i18n.format("op.produitRappelLot", batch));
            return true;
        }
        // LC-02-03-13: the article carries a lot recall but this code does not say
        // which lot is in the basket. The lots are listed and the sale goes on: only
        // the cashier, holding the pack, can read the lot printed on it. The message
        // is raised AFTER the line is registered, because registering clears the
        // message area.
        String recalledLots = (batch == null || batch.isBlank())
                ? ProductAttributes.recalledLotsMessage(product) : null;
        // LC-11-03-16: the code carries a quantity AND the cashier armed the quantity
        // key. Two answers to one question, and the register may not pick one — it says
        // so and registers nothing.
        BigDecimal count = countOf(message);
        if (count != null && state.priceModState.active
                && state.priceModState.type == com.intermarche.pos.ui.PriceModType.QUANTITY) {
            state.ticket.setError(i18n.get("op.quantiteDejaPorteeCode"));
            return true;
        }
        LocalDate expiry = message.date(Gs1ApplicationIdentifier.EXPIRY);
        if (isExpiredArticle(state, expiry)) {
            return true;
        }
        addLine(state, message, product, count, expiry);
        if (recalledLots != null) {
            state.ticket.setNotice(recalledLots);
        }
        return true;
    }

    /**
     * Finds the catalog article a GTIN names, trying each spelling the standard allows.
     *
     * @param candidates the codes the payload may mean, likeliest first
     * @return the article, or null when the catalog knows none of them
     */
    private Product findArticle(List<String> candidates) {
        for (String candidate : candidates) {
            Product found = Product.findActiveByEan(candidate);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Reads the quantity the payload carries ({@code LC-11-03-16}).
     *
     * @param message the decoded payload
     * @return the quantity, or null when the payload carries none usable
     */
    private BigDecimal countOf(Gs1Message message) {
        Integer count = message.get(Gs1ApplicationIdentifier.COUNT) == null
                ? null : message.get(Gs1ApplicationIdentifier.COUNT).asInteger();
        return count == null || count <= 0 ? null : BigDecimal.valueOf(count);
    }

    /**
     * Applies the shop's rule to an article's expiry date ({@code LC-11-03-13}).
     *
     * @param state  the register state
     * @param expiry the date the payload carried, or null
     * @return true when the article is refused
     */
    private boolean isExpiredArticle(PosState state, LocalDate expiry) {
        AlertLevel level = AlertLevel.of(posSettingsService.gs1ExpiryAlert());
        if (expiry == null || !level.speaks()) {
            return false;
        }
        LocalDate today = LocalDate.now();
        int warnDays = Math.max(posSettingsService.gs1ExpiryWarnDays(), 0);
        if (expiry.isBefore(today)) {
            state.ticket.setError(i18n.format("op.articlePerime", formatted(expiry)));
            return level.blocks();
        }
        if (!expiry.isAfter(today.plusDays(warnDays))) {
            // Near or reached. It is a warning even at the blocking level: the article
            // is still within its date, and refusing it would refuse a sale the law and
            // the shop both allow.
            state.ticket.setError(i18n.format("op.dateCourte", formatted(expiry)));
            return false;
        }
        return false;
    }

    /**
     * Formats a date as the cashier reads it.
     *
     * @param date the date
     * @return the date, {@code dd/MM/yyyy}
     */
    private String formatted(LocalDate date) {
        return date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    /**
     * Creates the sale line, resolving what it is priced on.
     *
     * <p>THREE PRICINGS, in the order the standard makes them binding. An amount
     * payable for a variable-measure item ({@code 392n}) or a plain amount payable
     * ({@code 390n}) IS the line's total — the label was printed by a scale that already
     * weighed and priced, exactly like an in-store price-embedded sticker, and the
     * register must not re-price it. A net weight ({@code 310n}) is a quantity, and the
     * catalog's price per kilogram applies to it. Anything else is a plain unit line,
     * with the quantity the code carried or one.
     *
     * @param state   the register state
     * @param message the decoded payload
     * @param product the catalog article
     * @param count   the quantity the payload carried, or null
     * @param expiry  the expiry date the payload carried, or null
     */
    private void addLine(PosState state, Gs1Message message, Product product,
            BigDecimal count, LocalDate expiry) {
        Price price = Price.findCurrentPrice(product.id);
        BigDecimal vatRate = price != null && price.vatRate() != null
                ? price.vatRate() : defaultVatRate;
        if (ProductAttributes.vatExempt(product)) {
            vatRate = BigDecimal.ZERO;
        }
        BigDecimal amount = firstAmount(message);
        BigDecimal weight = message.decimal(Gs1ApplicationIdentifier.NET_WEIGHT_KG);
        BigDecimal unitPrice;
        BigDecimal quantity;
        boolean priceEmbedded = false;
        if (amount != null && amount.signum() > 0) {
            unitPrice = amount.setScale(2, RoundingMode.HALF_UP);
            quantity = BigDecimal.ONE;
            priceEmbedded = true;
        } else if (weight != null && weight.signum() > 0) {
            unitPrice = price != null ? price.priceIncludingTax : BigDecimal.ZERO;
            quantity = weight.setScale(3, RoundingMode.HALF_UP);
        } else {
            unitPrice = price != null ? price.priceIncludingTax : BigDecimal.ZERO;
            quantity = count == null ? BigDecimal.ONE : count;
        }
        state.ticket.addItem(product.ean, product.plu, product.saleLabel().toUpperCase(),
                unitPrice, quantity, vatRate);
        if (state.ticket.items.isEmpty()) {
            return;
        }
        TicketState.TicketItem added = state.ticket.items.get(state.ticket.items.size() - 1);
        // LC-11-03-02: the transactional half of the recording, every identifier
        // included, those with a rule and those without.
        added.gs1Data = message.describe();
        added.gs1ExpiryDate = expiry;
        added.priceEmbedded = priceEmbedded;
        if (ProductAttributes.discountForbidden(product)) {
            added.discountForbidden = true;
        }
        // LC-09-01-11 to -18: what the article may be paid with.
        added.restrictedTenders =
                com.intermarche.pos.domain.catalog.attribute.RestrictedTender.snapshot(product);
    }

    /**
     * Reads the amount the payload states for the line, the variable-measure one first.
     *
     * <p>{@code 392n} is the more specific of the two — it exists precisely for an
     * article sold by measure — so a payload carrying both is stating the line total
     * there, and the plain {@code 390n} beside it is the coupon-and-catch-all form
     * ({@code LC-11-03-21/22}).
     *
     * @param message the decoded payload
     * @return the amount, or null when the payload states none
     */
    private BigDecimal firstAmount(Gs1Message message) {
        BigDecimal variableMeasure =
                message.decimal(Gs1ApplicationIdentifier.AMOUNT_VARIABLE_MEASURE);
        return variableMeasure != null
                ? variableMeasure
                : message.decimal(Gs1ApplicationIdentifier.AMOUNT_PAYABLE);
    }
}
