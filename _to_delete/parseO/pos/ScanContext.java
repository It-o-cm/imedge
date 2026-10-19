package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;

// Contexte passé le long de la chaîne
/**
 * Token flowing through the scan chain — every code, whatever its source
 * (scanner gun, simulator, manual entry, search-result tap), becomes one
 * ScanContext walked through the handlers in {@code @Priority} order.
 * <p>
 * THE CHAIN CONTRACT, in full: each handler first bails when
 * {@code handled} is already true, recognizes its own family of codes,
 * acts, and sets {@code handled} — first recognizer wins, the rest never
 * run. Current order: the awaited activation code (-1), badge (0), then
 * fidelity / deposit voucher / 2x
 * scale label / GS1 / administered range destination (all at 1 — the tie is
 * safe ONLY because their recognition domains are disjoint: a regex, a 298
 * prefix, a 2x EAN-13, a payload carrying a GS1 marker, and a code an
 * administered range claims for a destination OTHER than an article line;
 * a new handler at a shared priority must keep that disjointness), then
 * payment voucher and catalog EAN (2), and the unknown-code fallback (100)
 * which is the only handler allowed to answer "I don't know".
 * <p>
 * THE LAST OF THOSE FIVE IS DISJOINT BY A DECISION AND NOT BY A SHAPE
 * (BO-03-06-56/-60/-61). An administered barcode range says both how to read
 * a code and what the register does with it;
 * {@code RangePurposeScanHandler} claims the codes whose range means a
 * return, a container tare or a named promotion,
 * {@code BalanceTicketScanHandler} those whose
 * range means a counter ticket, and {@code WeightedEanScanHandler} refuses
 * every range that is not an article range. One decision, read off the
 * range, honoured in three places that agree — which is why two handlers can
 * recognize the very same 2x EAN-13 shape without ever both claiming it. Handlers missing a {@code @Priority} default to
 * 100 in the assembler.
 * <p>
 * THE GS1 HANDLER IS THE ONE EXCEPTION to "first recognizer wins", and it
 * is deliberate. A GS1 Element String has no syntax of its own: written
 * bare it is a run of digits, indistinguishable from a long numeric card
 * or badge. So it claims a MARKED payload (parentheses, symbology
 * identifier, FNC1 separator, Digital Link address) outright, and an
 * unmarked one only once it has found the article — otherwise it leaves
 * {@code handled} false and the code goes on down the chain untouched.
 * <p>
 * THE ACTIVATION HANDLER IS DISJOINT BY STATE AND NOT BY SHAPE
 * (BO-03-06-44). An activation code has no shape of its own — it is whatever
 * the provider printed on the voucher — so {@code ActivationCodeScanHandler}
 * claims a code only while the ticket is WAITING for one, which happens only
 * for the one scan following a dematerialised article. While that wait is open
 * it swallows every code, a badge included, and that is the intended
 * behaviour: the register asked a question and the next scan is the answer.
 * It sits at -1, alone, so the wait is honoured before any shape is tested.
 * <p>
 * NO PLU HANDLER, deliberately: a PLU is not a barcode. It is the four- or
 * five-digit number printed on a produce sticker or a shelf card, and the
 * cashier TYPES it — the sale screen posts it to {@code /action/add/{code}},
 * which weighs the item and carries its EAN. A handler recognizing short
 * digit strings on the scan bus used to exist; it added a unit line with NO
 * EAN, so the engine never saw the item and the total was wrong. Weighed
 * produce reaches this chain only as a 2x scale label, which is a real
 * barcode.
 */
public class ScanContext {
    /** The scanned or typed code. */
    public String code;
    /** The register state the handler acts on. */
    public PosState state;
    /** True once a handler recognized and consumed the code (stops the chain). */
    public boolean handled = false; // Permet d'arrêter la chaîne

    /**
     * Wraps a code and the state for one walk through the chain.
     *
     * @param code the scanned or typed code
     * @param state the register state
     */
    public ScanContext(String code, PosState state) {
        this.code = code;
        this.state = state;
    }

    /**
     * A link of the scan chain, discovered by CDI and ordered by
     * {@code @Priority}.
     */
    public interface ScanHandler {

        /**
         * Examines the context and consumes it when the code belongs to
         * this handler's family.
         *
         * @param context the token walking the chain
         */
        void handle(ScanContext context);
    }
}