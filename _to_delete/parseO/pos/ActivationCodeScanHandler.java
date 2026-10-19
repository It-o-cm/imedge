package com.intermarche.pos.ui.scanner;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Scan handler for the SECOND code a dematerialised article requires
 * (BO-03-06-44): the activation code the provider printed on the voucher, read
 * right after the article's own EAN.
 *
 * <p>Nothing here recognizes a shape, and that is the whole point. An
 * activation code is whatever the provider chose to print — digits, letters, a
 * mix, any length — so no regex can tell one from a badge or a card. What CAN
 * be told is whether the register is WAITING for one: {@code EanScanHandler}
 * opens the wait when it rings an article the referential declared
 * dematerialised, and the very next scan closes it. Outside that wait this
 * handler claims nothing at all.
 *
 * <p>Priority -1, alone at that rank, so the wait is honoured before any shape
 * is tested. While it is open every code is swallowed, a supervisor badge
 * included; the chain contract in {@link ScanContext} states it, because it is
 * a decision and not an accident — the register asked a question and treats the
 * next scan as the answer.
 *
 * <p>The code is STORED and the sale goes on. Transmitting it to the provider
 * is the provider's protocol, which these repositories do not carry: the line
 * keeps the code and the provider name it owes, so the settlement is possible
 * the moment that protocol exists, and nothing is invented in the meantime.
 */
@ApplicationScoped
@Priority(-1)
public class ActivationCodeScanHandler implements ScanContext.ScanHandler {

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal
     * (LC-13-06).
     */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.Messages i18n;

    /**
     * Claims the scan that answers a pending activation request.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;
        if (ctx.state == null || ctx.state.ticket == null) return;
        if (ctx.state.ticket.pendingActivationUid == null) return;
        String code = ctx.code;
        if (code == null || code.isBlank()) {
            // A blank scan answers nothing: the wait stays open rather than
            // being spent on a code the provider never printed.
            return;
        }
        com.intermarche.pos.ui.ticket.TicketState.TicketItem line =
                ctx.state.ticket.storePendingActivation(code);
        if (line == null) {
            // The awaited line is gone (cancelled, or a ticket cleared under
            // the operator's hand). The wait is closed by the store call above,
            // and the operator is told rather than left with a code that went
            // nowhere.
            ctx.state.ticket.setError(i18n.get("op.ligneActivationIntrouvable"));
            ctx.handled = true;
            return;
        }
        ctx.state.ticket.setNotice(line.activationProvider == null
                ? i18n.get("op.codeActivationEnregistre")
                : i18n.format("op.codeActivationEnregistreFournisseur",
                        line.activationProvider));
        ctx.handled = true;
    }
}
