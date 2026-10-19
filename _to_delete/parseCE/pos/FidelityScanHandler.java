package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.fidelity.FidelityService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Recognizes fidelity cards ({@code scan.pattern.fidelity}) and attaches
 * them to the running ticket — the primary path, the manual page being the
 * fallback. Inert while the register is locked (a card on the lock screen
 * means nothing).
 */
@ApplicationScoped
@Priority(1) // Juste après l'authentification
public class FidelityScanHandler implements ScanContext.ScanHandler {

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /**
     * Recognition regex of fidelity cards, as the deployment states it. The
     * ADMINISTERED range wins over it (BO-03-06-54); this stays the fallback
     * for a node administering none.
     */
    @ConfigProperty(name = "scan.pattern.fidelity")
    String fidelityPattern;

    @Inject
    FidelityService fidelityService;

    /** The back-office parameters (multiple-card-scan rule — BO-10-03-02). */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * Attaches a recognized card to the ticket.
     *
     * @param ctx the token walking the chain
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;

        // On n'applique la fidélité que si la caisse est déverrouillée
        if (!ctx.state.isLocked() && ctx.code.matches(cardPattern())) {
            // LC-03-06-03: while the REASSIGNMENT mask of the reprint screen is
            // open, a scanned card is the card that ticket is being reassigned
            // to — it fills the mask instead of attaching to the live sale,
            // which is on another screen and would silently change hands.
            if (ctx.state.reprint.reassignMode && ctx.state.reprint.reassignMaskOpen) {
                ctx.state.reprint.reassignCard = ctx.code;
                ctx.state.touch();
                ctx.handled = true;
                return;
            }
            // BO-10-03-02: a card already attached blocks any further scan
            // unless the back office allows multiple scans (last one wins).
            if (!posSettingsService.fidelityAllowMultipleScan() && ctx.state.fidelity.active) {
                ctx.state.ticket.setError(i18n.get("op.carteFideliteDejaScannee"));
                ctx.handled = true;
                return;
            }
            // LC-03-04-08: a DIFFERENT card arriving on a ticket that already
            // carries one is PROPOSED, never substituted in silence — the
            // replacement loses the first holder's advantages and earns on
            // the new one's account, and the operator confirms the swap before
            // either happens. The same card scanned again is a refresh, not a
            // substitution, and stays the silent gesture it always was.
            if (ctx.state.fidelity.active
                    && !ctx.code.equals(ctx.state.fidelity.label)) {
                ctx.state.fidelity.pendingReplacementCard = ctx.code;
                ctx.handled = true;
                return;
            }
            fidelityService.validateCard(ctx.state, ctx.code);
            ctx.handled = true;
        }
    }

    /**
     * Resolves the range of loyalty cards this register recognises
     * (BO-03-06-54): the ADMINISTERED pattern wins, so an echelon can widen or
     * narrow its card range without a redeployment, and the deployment
     * property remains the fallback for a node administering none.
     *
     * @return the recognition regex, never null
     */
    String cardPattern() {
        String administered = posSettingsService.fidelityCardPattern();
        if (administered != null && !administered.isBlank()) {
            return administered.trim();
        }
        return fidelityPattern;
    }
}