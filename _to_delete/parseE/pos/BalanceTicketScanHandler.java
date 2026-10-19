package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.balance.BalanceTicketService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Scan handler picking up a service-counter ticket at the till (LC-06-01-02).
 *
 * <p>Layout: {@code PP RRRRRRRRRR K} — a 2-digit in-store prefix
 * ({@code pos.scan.balance-ticket-prefixes}, {@code 27} by default), the 10-digit
 * counter reference exactly as the scale pushed it to the shop, and the EAN13 check
 * digit. Leading zeros of the reference are KEPT: the reference is a key, not a
 * number, and the shop matches it character for character.
 *
 * <p>This handler recognizes the same {@code 2\d{12}} family as
 * {@link WeightedEanScanHandler}, so the two share priority 1 and stay disjoint the
 * way the chain contract demands: the price and weight prefixes on one side, the
 * counter-ticket prefixes on the other, and a code whose prefix belongs to neither
 * falls through to the catalog handler untouched. An invalid check digit falls
 * through too — a regular 2-prefixed retail EAN must still reach the catalog.
 *
 * <p>The scan itself carries no price and no article: it is only the RENDEZ-VOUS
 * between a paper printed at the counter and the shop that has been holding its
 * lines. Everything that follows — the pick-up, the arbitration, the refusals —
 * belongs to {@link BalanceTicketService}.
 */
@ApplicationScoped
@Priority(1)
public class BalanceTicketScanHandler implements ScanContext.ScanHandler {

    /** In-store prefixes carrying a service-counter ticket reference. */
    @ConfigProperty(name = "pos.scan.balance-ticket-prefixes", defaultValue = "27")
    List<String> balanceTicketPrefixes;

    /** Picks the paper up from the shop and turns it into ticket lines. */
    @Inject
    BalanceTicketService balanceTicketService;

    /**
     * Handles a counter-ticket barcode by asking the shop for its lines.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;
        String code = ctx.code;
        if (code == null || code.isBlank()) return;
        // BO-03-06-56: the ADMINISTERED range is consulted first. A store whose
        // scales print a reference somewhere else in a code of another length
        // states that range in the back office; until it does, the historical
        // 2x EAN13 layout below is what answers, unchanged.
        String reference = administeredReference(code);
        if (reference == null) {
            if (!code.matches("2\\d{12}")) return;
            if (!balanceTicketPrefixes.contains(code.substring(0, 2))) return;
            if (!hasValidChecksum(code)) return; // let the generic EAN handler try
            reference = code.substring(2, 12);
        }

        balanceTicketService.integrate(ctx.state, reference);
        // Consumed either way: a refused pick-up already told the cashier why, and
        // letting the code walk on would end as "ARTICLE INCONNU" over that message.
        ctx.handled = true;
    }

    /**
     * Reads the counter-ticket reference an administered range carries, if one
     * recognizes the code (BO-03-06-56).
     *
     * <p>The reference is taken RAW, leading zeros kept: the shop matches it
     * character for character against what the scale pushed, and a reference
     * whose zeros were stripped names nothing.
     *
     * @param code the scanned code, non-blank
     * @return the reference, or null when no BALANCE_TICKET range claims the code
     */
    private String administeredReference(String code) {
        com.intermarche.pos.domain.barcode.ArticleBarcodeRange range =
                com.intermarche.pos.domain.barcode.ArticleBarcodeRange.findMatching(code);
        if (range == null
                || range.purpose != com.intermarche.pos.domain.barcode
                        .ArticleBarcodeRange.Purpose.BALANCE_TICKET) {
            return null;
        }
        if (range.checkDigit && code.length() == 13 && !hasValidChecksum(code)) {
            return null;
        }
        String reference = range.articleSegment(code);
        return reference == null || reference.isBlank() ? null : reference;
    }

    /**
     * Verifies the EAN13 check digit of the given 13-digit code.
     *
     * @param code the 13-digit code
     * @return true if the check digit is valid
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
