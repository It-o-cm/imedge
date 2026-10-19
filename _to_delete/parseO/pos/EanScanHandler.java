package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;

/**
 * Scan handler adding a product to the ticket from a scanned EAN code.
 * <p>
 * Phase 0: the price is handed to the ticket as {@link BigDecimal} without any
 * double round-trip. Phase 1: the real VAT rate of the Price is captured on
 * the line (default rate when no price is found).
 * <p>
 * This is THE SNAPSHOT MOMENT of a sale line: price and VAT are resolved
 * here (current-price window + priority) and frozen onto the line — a later
 * price change never rewrites what was sold. Forbidden-to-sale products are
 * refused here with the cashier error, and the resulting unit line is the
 * only line kind eligible for merging and quantity edition.
 */
@ApplicationScoped
@Priority(2)
public class EanScanHandler implements ScanContext.ScanHandler {

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.Messages i18n;

    @jakarta.inject.Inject
    com.intermarche.pos.ui.ticket.TicketService ticketService;

    /** The back-office parameters (EAN13 check-digit control — BO-10-02-21). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;


    /** Default VAT rate applied when no catalog price is found (e.g. 0.20). */
    @ConfigProperty(name = "pos.vat.default-rate", defaultValue = "0.20")
    BigDecimal defaultVatRate;

    /**
     * Handles a scanned EAN code by adding the matching product to the ticket.
     * <p>
     * A product flagged as forbidden to sell is refused: no line is created and an
     * error is shown to the cashier.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;

        if (ctx.code.matches("\\d{8,13}")) {
            // BO-10-02-21: when the check-digit control is active, a 13-digit
            // code whose EAN13 key is wrong is refused before any lookup.
            if (posSettingsService.ean13CheckDigitEnabled() && ctx.code.length() == 13
                    && !hasValidChecksum(ctx.code)) {
                ctx.state.ticket.setError(i18n.get("op.codeEanInvalide"));
                ctx.handled = true;
                return;
            }
            Product p = Product.find("ean = ?1 and active = true", ctx.code).firstResult();
            if (p != null) {
                if (p.forbiddenToSale) {
                    ctx.state.ticket.setError(i18n.get("op.produitInterditVente"));
                    ctx.handled = true;
                    return;
                }

                // BO-02-03-11: a recalled article is refused at scan, no line.
                if (ProductAttributes.recall(p)) {
                    ctx.state.ticket.setError(i18n.get("op.articleRetraitRappel"));
                    ctx.handled = true;
                    return;
                }

                // Age gate: a restricted product parks the scan behind the
                // ID-check prompt; the confirmation replays this very code
                // through the chain (phase: age control).
                if (ticketService.suspendForAgeCheck(ctx.state, p, "SCAN", ctx.code, null)) {
                    ctx.handled = true;
                    return;
                }

                // LC-02-03-01/03: an article whose price or whose quantity the
                // referential does not carry parks the add behind the entry prompt.
                if (ticketService.suspendForEntry(ctx.state, p, null)) {
                    ctx.handled = true;
                    return;
                }

                Price price = Price.findCurrentPrice(p.id);
                BigDecimal finalPrice = (price != null) ? price.priceIncludingTax : BigDecimal.ZERO;
                BigDecimal vatRate = (price != null && price.vatRate() != null)
                        ? price.vatRate() : defaultVatRate;
                // BO-02-03-26/27: VAT-exempt article ventilated at rate 0; the
                // amount due is unchanged, only its VAT breakdown.
                if (ProductAttributes.vatExempt(p)) {
                    vatRate = BigDecimal.ZERO;
                }

                // Line label is the checkout label when set, else the name.
                ctx.state.ticket.addItem(ctx.code, null, p.saleLabel().toUpperCase(), finalPrice, BigDecimal.ONE, vatRate);
                // Only reach into the line when there is a snapshot to carry —
                // a plain product leaves the freshly added line untouched.
                com.intermarche.pos.ui.ticket.TicketState.TicketItem line =
                        ctx.state.ticket.items.get(ctx.state.ticket.items.size() - 1);
                if (p.giftCardAmount != null) {
                    line.moneyProduct = true;
                }
                if (ProductAttributes.discountForbidden(p)) {
                    line.discountForbidden = true;
                }
                // LC-09-01-11 to -18: what this article may be paid with, snapshotted
                // on the line so the eligible bases can be totalled without a lookup.
                line.restrictedTenders = com.intermarche.pos.domain.catalog.attribute
                        .RestrictedTender.snapshot(p);
                // LC-02-03-13: a plain EAN names no lot, so the register cannot tell
                // whether this pack is one of the recalled ones. It lists them and lets
                // the sale go on — the cashier reads the lot printed on the pack.
                String recalledLots = ProductAttributes.recalledLotsMessage(p);
                if (recalledLots != null) {
                    ctx.state.ticket.setNotice(recalledLots);
                }

                // BO-03-06-44: a DEMATERIALISED article is sold by its code and
                // not by its packet. The line is rung first — the customer is
                // paying for it — and the register then asks for the activation
                // code, which the next scan answers. The provider is
                // snapshotted on the line beside the code it owes.
                if (ProductAttributes.activationRequired(p)) {
                    line.activationProvider = ProductAttributes.activationProvider(p);
                    ctx.state.ticket.pendingActivationUid = line.uid;
                    ctx.state.ticket.setWarning(i18n.get("op.codeActivationAttendu"));
                }

                ctx.handled = true;
            }
        }
    }

    /**
     * Verifies the EAN13 check digit of a 13-digit code (same algorithm as the
     * in-store weighted-label handler): the twelve data digits are weighted
     * 1-3-1-3..., the complement to the next ten is the expected key.
     *
     * @param code the 13-digit code
     * @return true when the check digit matches
     */
    private boolean hasValidChecksum(String code) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = code.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        int expected = (10 - (sum % 10)) % 10;
        return expected == (code.charAt(12) - '0');
    }
}
