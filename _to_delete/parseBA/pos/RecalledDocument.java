package com.intermarche.pos.ui.recall;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A document recalled at the till by a code the back office gave the customer
 * (EXP-90/91/92): a quote, an order, a sale voucher or a click-and-collect
 * basket. It is EMITTED and STORED by the store node (imedge); the register
 * only fetches it by code through {@link BackOfficeDocumentClient} and loads
 * its lines into the current sale.
 *
 * <p>All four kinds share one shape — a typed header, a list of article lines
 * and, for an order, the down-payment already collected — because the campaign
 * builds them on the SAME recall-by-code mechanism; only the {@link Kind} and
 * the label differ.
 *
 * <p>Since LC-06-03 the register CREATES orders too: the shape above doubles as
 * the push payload of a till-created order ({@code BackOfficeDocumentClient#pushOrder}),
 * the node storing it and any register of the store recalling it by its code.
 */
public class RecalledDocument {

    /** The kind of recalled document. */
    public enum Kind {
        /** A quote (devis): articles proposed, not yet ordered. */
        DEVIS,
        /** An order (commande): may carry a down-payment already paid. */
        COMMANDE,
        /** A sale voucher (bon de vente). */
        BON_VENTE,
        /** A click-and-collect prepared basket. */
        CLICK_COLLECT
    }

    /**
     * One article line of a recalled document.
     *
     * @param ean the article EAN (or internal code)
     * @param label the article label
     * @param quantity the quantity to load
     * @param unitPrice the unit price to load
     * @param vatRate the VAT rate of the line
     */
    public record Line(String ean, String label, BigDecimal quantity,
                       BigDecimal unitPrice, BigDecimal vatRate) {
    }

    /** The kind of document. */
    public Kind kind;

    /** The recall code the customer presented. */
    public String code;

    /** The article lines to load into the sale. */
    public List<Line> lines = new ArrayList<>();

    /**
     * The down-payment already collected on an order (EXP-85), deducted from the
     * amount due when the order is recalled; ZERO when none.
     */
    public BigDecimal acompteAlreadyPaid = BigDecimal.ZERO;

    /**
     * The per-rate VAT of the down-payment already collected, in the compact
     * {@code DepositVat} form ({@code rate:amount;...}), or null when the
     * node carried none (LC-06-03-05/-08). The register computes it when the
     * order is CREATED at the till and the node hands it back at recall, so
     * the final ticket restates the ventilation printed on the deposit slip.
     */
    public String depositVatDetail;

    /**
     * The total amount of the order (TTC), or null when the node did not
     * state one. Set by the till when the order is created there
     * (LC-06-03-01) and pushed to the node with the order.
     */
    public BigDecimal totalAmount;

    /**
     * Creates an empty document (fields set by the client after a fetch).
     */
    public RecalledDocument() {
    }

    /**
     * Creates a recalled document.
     *
     * @param kind the document kind
     * @param code the recall code
     * @param lines the article lines
     * @param acompteAlreadyPaid the down-payment already paid, or null for none
     */
    public RecalledDocument(Kind kind, String code, List<Line> lines, BigDecimal acompteAlreadyPaid) {
        this.kind = kind;
        this.code = code;
        this.lines = lines != null ? lines : new ArrayList<>();
        this.acompteAlreadyPaid = acompteAlreadyPaid != null ? acompteAlreadyPaid : BigDecimal.ZERO;
    }

    /**
     * The operator-facing label of the document kind.
     *
     * @return the French label of the kind, or "DOCUMENT" when the kind is unset
     */
    public String getKindLabel() {
        if (kind == null) {
            return "DOCUMENT";
        }
        return switch (kind) {
            case DEVIS -> "DEVIS";
            case COMMANDE -> "COMMANDE";
            case BON_VENTE -> "BON DE VENTE";
            case CLICK_COLLECT -> "CLICK AND COLLECT";
        };
    }

    /**
     * Whether this document carries a down-payment to deduct (EXP-85).
     *
     * @return true when the paid down-payment is strictly positive
     */
    public boolean hasAcompte() {
        return acompteAlreadyPaid != null && acompteAlreadyPaid.signum() > 0;
    }
}
