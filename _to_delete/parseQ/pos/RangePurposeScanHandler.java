package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.barcode.ArticleBarcodeRange;
import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Scan handler for the administered ranges that mean something OTHER than an
 * article line: a RETURN, a container TARE, a named PROMOTION and a code only a
 * third party can read (BO-03-06-60, -61, -43 and -41).
 *
 * <p>Nothing here decodes a code. The positional vocabulary of
 * {@link ArticleBarcodeRange} already did that work for the scale labels, and a
 * store that prints its returns on the same plan states the same four numbers.
 * What this handler adds is the MEANING the range carries, and only the two
 * meanings that need no new table and no new engine:
 *
 * <ul>
 *   <li>{@link ArticleBarcodeRange.Purpose#REFUND} — the code names the article
 *       and carries its price; the line is rung as a return on the sale in
 *       progress. The paper IS the authorisation, so the original ticket is
 *       never asked for; which is exactly why the two administered permissions
 *       on returns are honoured here, word for word as the return key honours
 *       them (LC-05-01-01, LC-05-02-09/14).</li>
 *   <li>{@link ArticleBarcodeRange.Purpose#TARE} — the code carries the weight
 *       of the container, remembered on the ticket and deducted from the next
 *       weighed line by {@link WeightedEanScanHandler}. The deduction belongs
 *       there because that is where a weight becomes a quantity.</li>
 *   <li>{@link ArticleBarcodeRange.Purpose#PROMOTION} — the range NAMES a
 *       promotion and the code arms that name on the ticket, which the
 *       valuation engine then resolves. No line and no amount: the register
 *       never decides what a promotion is worth (BO-03-06-43).</li>
 *   <li>{@link ArticleBarcodeRange.Purpose#EXTERNAL_LOOKUP} — the range names a
 *       THIRD-PARTY service, the register asks it about the code and acts on
 *       the answer: an article the catalog carries is rung, anything else is
 *       shown to the operator as the service wrote it (BO-03-06-41).</li>
 * </ul>
 *
 * <p>Priority 1, beside the other recognizers, and DISJOINT from them the way
 * the chain contract demands: this handler claims a code only when an
 * administered range recognizes it AND that range's purpose is one of the two
 * above, and {@link WeightedEanScanHandler} refuses the very same codes for the
 * same reason. One decision, taken on the range, in two places that agree.
 */
@ApplicationScoped
@Priority(1)
public class RangePurposeScanHandler implements ScanContext.ScanHandler {

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.Messages i18n;

    /** The administered permissions on returns. */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** The call an EXTERNAL_LOOKUP range makes at scan time (BO-03-06-41). */
    @jakarta.inject.Inject
    RangeLookupClient rangeLookupClient;

    /** Default VAT rate applied when no catalog price is found (e.g. 0.20). */
    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "pos.vat.default-rate", defaultValue = "0.20")
    BigDecimal defaultVatRate;

    /**
     * Claims a code belonging to a range administered as a return or a tare.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;
        String code = ctx.code;
        if (code == null || code.isBlank()) return;
        ArticleBarcodeRange range = ArticleBarcodeRange.findMatching(code);
        if (range == null || range.isArticlePurpose()) return;
        if (range.purpose == ArticleBarcodeRange.Purpose.BALANCE_TICKET) {
            // Claimed by BalanceTicketScanHandler, which owns the pick-up.
            return;
        }
        if (range.checkDigit && !hasValidChecksum(code)) return;
        if (range.purpose == ArticleBarcodeRange.Purpose.PROMOTION) {
            armPromotion(ctx, range);
            return;
        }
        if (range.purpose == ArticleBarcodeRange.Purpose.EXTERNAL_LOOKUP) {
            askTheThirdParty(ctx, range, code);
            return;
        }
        if (range.purpose == ArticleBarcodeRange.Purpose.TARE) {
            announceTare(ctx, range, code);
            return;
        }
        ringReturn(ctx, range, code);
    }

    /**
     * Remembers the container weight the code carries (BO-03-06-60).
     *
     * <p>The tare is NOT a line: it changes the next weighed line and nothing
     * else. An unreadable or non-positive weight is refused rather than taken
     * as zero — a tare of zero and a tare nobody could read are not the same
     * thing to a customer paying for a bowl.
     *
     * @param ctx the scan context
     * @param range the administered range the code belongs to
     * @param code the scanned code
     */
    private void announceTare(ScanContext ctx, ArticleBarcodeRange range, String code) {
        BigDecimal value = range.rawValue(code);
        if (value == null || value.signum() <= 0) {
            ctx.state.ticket.setError(i18n.get("op.tareIllisible"));
            ctx.handled = true;
            return;
        }
        BigDecimal tare = value.setScale(3, RoundingMode.HALF_UP);
        ctx.state.ticket.pendingTareKg = tare;
        ctx.state.ticket.setNotice(i18n.format("op.tarePriseEnCompte", tare.toPlainString()));
        ctx.handled = true;
    }

    /**
     * Arms the promotion the range names on the sale in progress
     * (BO-03-06-43).
     *
     * <p>No line, no amount, no decision about what the promotion is worth: the
     * register NAMES it and the valuation engine resolves it. A second scan of
     * the same promotion is a caution and not a refusal — the gesture went
     * through the first time, and the customer is told rather than blocked.
     *
     * <p>A promotion range that names nothing is an incomplete administered
     * row; it is refused loudly, because silently arming an empty name would
     * send the engine a promotion nobody can resolve.
     *
     * @param ctx the scan context
     * @param range the administered range the code belongs to
     */
    private void armPromotion(ScanContext ctx, ArticleBarcodeRange range) {
        if (!range.triggersPromotion()) {
            ctx.state.ticket.setError(i18n.get("op.promotionNonParametree"));
            ctx.handled = true;
            return;
        }
        if (!ctx.state.ticket.armPromotion(range.promotionCode)) {
            ctx.state.ticket.setWarning(
                    i18n.format("op.promotionDejaDeclenchee", range.promotionCode));
            ctx.handled = true;
            return;
        }
        ctx.state.ticket.setNotice(
                i18n.format("op.promotionDeclenchee", range.promotionCode));
        ctx.handled = true;
    }

    /**
     * Asks the third-party service the range names what a code it alone can
     * read means, and acts on the answer (BO-03-06-41).
     *
     * <p>Three outcomes, and the register says which one it got. The answer
     * names an article the catalog carries: the line is rung at the shop's own
     * price, because the third party said WHAT the code is and the shop still
     * says what it costs. The answer names nothing the catalog knows: it is
     * shown as it came — a partner refusing a voucher writes the sentence the
     * customer needs to hear, and the register has no business paraphrasing it.
     * No answer at all, for any reason: the operator is told the service could
     * not be reached, and the code is consumed rather than falling through to
     * "unknown code", which would be a lie about what happened.
     *
     * @param ctx the scan context
     * @param range the administered range the code belongs to
     * @param code the scanned code
     */
    private void askTheThirdParty(ScanContext ctx, ArticleBarcodeRange range, String code) {
        if (!range.hasExternalLookup()) {
            ctx.state.ticket.setError(i18n.get("op.serviceExterneNonParametre"));
            ctx.handled = true;
            return;
        }
        String answer = rangeLookupClient == null
                ? null
                : rangeLookupClient.lookup(range.lookupUrl, code);
        if (answer == null) {
            ctx.state.ticket.setError(i18n.get("op.serviceExterneInjoignable"));
            ctx.handled = true;
            return;
        }
        Product product = Product.find("ean = ?1 and active = true", answer).firstResult();
        if (product == null) {
            product = Product.findActiveByPlu(answer);
        }
        if (product == null) {
            ctx.state.ticket.setNotice(answer);
            ctx.handled = true;
            return;
        }
        Price price = Price.findCurrentPrice(product.id);
        BigDecimal vatRate = (price != null && price.vatRate() != null)
                ? price.vatRate() : defaultVatRate;
        BigDecimal unitPrice = (price != null && price.priceIncludingTax != null)
                ? price.priceIncludingTax : BigDecimal.ZERO;
        ctx.state.ticket.addItem(product.ean, null,
                product.saleLabel().toUpperCase(), unitPrice, BigDecimal.ONE, vatRate);
        ctx.handled = true;
    }

    /**
     * Rings the article the code names as a RETURN (BO-03-06-61).
     *
     * <p>The price comes from the code when the range carries one, and from the
     * catalog when it carries a weight instead — a return of a weighed article
     * is given back at the price the shop charges for it.
     *
     * @param ctx the scan context
     * @param range the administered range the code belongs to
     * @param code the scanned code
     */
    private void ringReturn(ScanContext ctx, ArticleBarcodeRange range, String code) {
        String articleCode = range.articleCode(code);
        BigDecimal value = range.rawValue(code);
        if (articleCode == null || value == null) return;
        // LC-05-02-09/14: taking an article back without its origin ticket can be
        // forbidden store-wide — and this code IS a return without one.
        if (!posSettingsService.returnWithoutOriginAllowed()) {
            ctx.state.ticket.setError(i18n.get("op.retourSansTicketOrigine"));
            ctx.handled = true;
            return;
        }
        // LC-05-01-01: a return inside a ticket that already carries lines is a
        // permission of its own.
        if (!posSettingsService.returnInSaleAllowed() && !ctx.state.ticket.items.isEmpty()) {
            ctx.state.ticket.setError(i18n.get("op.retourTicketVenteInterdit"));
            ctx.handled = true;
            return;
        }
        Product product = Product.findActiveByPlu(articleCode);
        if (product == null) {
            ctx.state.ticket.setError(i18n.format("op.articleBalanceIntrouvable", articleCode));
            ctx.handled = true;
            return;
        }
        Price price = Price.findCurrentPrice(product.id);
        BigDecimal vatRate = (price != null && price.vatRate() != null)
                ? price.vatRate() : defaultVatRate;
        BigDecimal unitPrice = range.isPriceEmbedded()
                ? value
                : (price != null && price.priceIncludingTax != null
                        ? price.priceIncludingTax : BigDecimal.ZERO);
        BigDecimal quantity = range.isPriceEmbedded()
                ? BigDecimal.ONE
                : value.setScale(3, RoundingMode.HALF_UP);
        if (quantity.signum() <= 0) {
            ctx.state.ticket.setError(i18n.get("op.poidsInvalide"));
            ctx.handled = true;
            return;
        }
        // The ONE funnel every entry goes through (TicketState.addItem) is what
        // negates the quantity and marks the line as a return, so a return rung
        // by a barcode and a return rung by the return key are the same line.
        // The mode is armed for this entry only: a code is one gesture, not a
        // change of what the register is doing.
        boolean wasReturnMode = ctx.state.returnMode;
        ctx.state.returnMode = true;
        try {
            ctx.state.ticket.addItem(product.ean, articleCode,
                    product.saleLabel().toUpperCase(), unitPrice, quantity, vatRate);
        } finally {
            ctx.state.returnMode = wasReturnMode;
        }
        ctx.handled = true;
    }

    /**
     * Verifies the EAN13 modulo-10 check digit of a 13-character code.
     *
     * <p>A code of another length carries no EAN13 check digit, so it passes:
     * the administered range said the last character is checked, not that the
     * code is an EAN13.
     *
     * @param code the scanned code
     * @return true when the check digit is right, or not applicable
     */
    private boolean hasValidChecksum(String code) {
        if (code.length() != 13) {
            return true;
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = code.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                return false;
            }
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        int expected = (10 - (sum % 10)) % 10;
        return expected == code.charAt(12) - '0';
    }
}
