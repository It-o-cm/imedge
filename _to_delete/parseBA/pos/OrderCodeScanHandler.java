package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.recall.BackOfficeDocumentClient;
import com.intermarche.pos.ui.recall.RecalledDocument;
import com.intermarche.pos.ui.ticket.TicketService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Recognizes the BARCODE of a deposit slip printed by this register's store
 * ({@code LC-06-03-07}): a code shaped {@code <terminal>-CMD<digits>} names
 * an order created at a till, and the customer hands the slip back to reopen
 * the order — its articles loaded on the lane, the deposit already collected
 * credited as the "Reprise d'acompte" settlement, the balance to settle now.
 *
 * <p>THE SHAPE IS THE HANDLER'S, AND IT IS DISJOINT from every other family
 * of the chain: the {@code -CMD} marker never appears in a ticket number
 * ({@code -<8 digits>}), an EAN, a badge or a voucher. Only a TILL-CREATED
 * order claims it; a quote or a voucher the back office typed freely keeps
 * being recalled by hand on the /recall screen, which is where the back
 * office told the customer to key it.
 *
 * <p>Resolution is the node's, like every recall: the fetch asks the store
 * node for the document behind the code, and a code the node does not know
 * — or a lane with no store configured — falls through UNHANDLED, so the
 * unknown-code link answers exactly as before.
 */
@ApplicationScoped
@Priority(3)
public class OrderCodeScanHandler implements ScanContext.ScanHandler {

    /** The fetch to the store node's order store. */
    @Inject
    BackOfficeDocumentClient documentClient;

    /** The load of a recalled document into the current sale. */
    @Inject
    TicketService ticketService;

    /**
     * Handles a scan by loading the order it names, when the store node
     * holds one behind that code.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;
        if (ctx.state.isLocked()) return;
        String code = ctx.code;
        if (code == null || !code.matches("[A-Za-z0-9]+-CMD[0-9]+")) return;
        RecalledDocument order = documentClient.fetch(code);
        if (order == null) {
            // Not this register's order to claim: the unknown-code link
            // answers below, with the slip the customer can still key.
            return;
        }
        ticketService.loadRecalledDocument(ctx.state, order);
        ctx.state.touch();
        ctx.handled = true;
    }
}
