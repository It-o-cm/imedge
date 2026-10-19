package com.intermarche.pos.ui.scanner;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@Priority(100) // Exécuté en tout dernier
public class UnknownScanHandler implements ScanContext.ScanHandler {

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.Messages i18n;

    /**
     * The printer, for the unknown-article slip the island may ask for
     * (BO-03-10-05).
     */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.hardware.TicketPrinterService ticketPrinterService;

    /**
     * Stamps the "unknown code" error onto the ticket when no earlier link of
     * the chain recognized the scanned code, and prints the slip where the
     * island asks for one (BO-03-10-05).
     *
     * @param ctx the scan context carrying the code and the register state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;

        // Si on arrive ici et que la caisse est vérouillée, on ne fait rien
        // (pas d'erreur "Code Inconnu" sur l'écran de lock)
        if (ctx.state.isLocked()) return;

        // Through setError, never by assigning transientError: only setError
        // calls onChange(), which bumps the polling version. Assigning the
        // field left the version untouched, /ticket-fragment answered
        // "changed: false" and the message never reached the screen.
        ctx.state.ticket.setError(i18n.format("op.codeInconnu", ctx.code));
        // BO-03-10-05: the paper that leaves the till with the code on it. The
        // printer answers zero where the island asks for none, so this handler
        // does not have to know what the island decided — and the message on
        // the screen is stamped either way, before the paper and not instead
        // of it.
        if (ticketPrinterService != null) {
            ticketPrinterService.printUnknownArticleTicket(ctx.code);
        }
        ctx.handled = true;
    }
}