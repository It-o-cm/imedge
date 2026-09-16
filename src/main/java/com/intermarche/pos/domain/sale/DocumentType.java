package com.intermarche.pos.domain.sale;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A kind of commercial document the register issues on a closed ticket.
 * <p>
 * The questionnaire never describes the delivery note on its own: it always writes
 * "Facture/Bon de livraison", and {@code BO-03-03-02} puts the document type INSIDE
 * the printed template ("Type de document : facture/bon de livraison /...., issu du
 * nom du document") next to "N° de séquence : unique, séquentiel et sans rupture par
 * type de document". A document kind is therefore a name, a template and a sequence
 * of its own — nothing more — and the list is open, which is why the register offers
 * what the back office activated rather than knowing any type by heart.
 * <p>
 * The Portuguese documents (FS, FT, NC, RC) are the same shape and will land here
 * when that market enters the perimeter; giving the sequence a per-type prefix from
 * the start is what avoids numbering them a second way.
 */
public enum DocumentType {

    /** An invoice: the sale is billed to a professional customer. */
    FACTURE("F", "FACTURE"),

    /** A delivery note: the goods leave, the billing follows its own course. */
    BON_LIVRAISON("L", "BON DE LIVRAISON");

    /** The letter that separates this type's sequence from the others. */
    private final String prefix;

    /** The title printed at the top of the document. */
    private final String title;

    /**
     * Builds a document type.
     *
     * @param prefix the letter of its number sequence
     * @param title  the title printed on the document
     */
    DocumentType(String prefix, String title) {
        this.prefix = prefix;
        this.title = title;
    }

    /**
     * Returns the letter that separates this type's sequence from the others.
     *
     * @return the sequence prefix
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * Returns the title printed at the top of the document.
     *
     * @return the printed title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Reads the list of document kinds the back office activated for the registers
     * ({@code LC-08-04-04}).
     *
     * <p>The syntax is the register's usual semicolon list of enum names. An unknown
     * name is DROPPED rather than refused: the back office of a later version may
     * activate a kind this register does not know how to draw yet, and one such name
     * must not cost the store every other kind on the list.
     *
     * <p>An empty or unreadable list falls back to the invoice alone. A store that
     * empties the parameter by accident must still be able to bill a customer, and the
     * invoice is the one kind the questionnaire never makes optional.
     *
     * @param administered the administered list, possibly null or blank
     * @return the activated kinds, in administered order, never empty
     */
    public static List<DocumentType> activated(String administered) {
        if (administered == null || administered.isBlank()) {
            return List.of(FACTURE);
        }
        List<DocumentType> activated = new ArrayList<>();
        for (String part : administered.split(";")) {
            DocumentType known = byName(part);
            if (known != null && !activated.contains(known)) {
                activated.add(known);
            }
        }
        return activated.isEmpty() ? List.of(FACTURE) : activated;
    }

    /**
     * Reads the administered mapping of payment methods to the document printed
     * automatically at the end of a sale settled with them ({@code LC-08-04-16}).
     *
     * <p>The syntax is the register's usual semicolon list, each entry pairing a
     * payment method key and a document kind with a colon:
     * {@code CREDIT:FACTURE;CHEQUE:BON_LIVRAISON}. The key is the method's own
     * discriminator, the one {@code TicketPayment.getMethodKey()} answers, so the
     * parameter names methods the register really has rather than labels a store typed.
     *
     * <p>An entry that is not a pair, or names a kind this version does not know, is
     * dropped. The method key is NOT checked here: a register that does not know a
     * method simply never carries a payment of it, and refusing the entry would only
     * hide a parameter written for a lane that does.
     *
     * @param administered the administered list, possibly null or blank
     * @return the document kind to print for each named payment method
     */
    public static Map<String, DocumentType> automatic(String administered) {
        Map<String, DocumentType> automatic = new LinkedHashMap<>();
        if (administered == null || administered.isBlank()) {
            return automatic;
        }
        for (String part : administered.split(";")) {
            String[] pair = part.split(":");
            if (pair.length != 2) {
                continue;
            }
            DocumentType kind = byName(pair[1]);
            String methodKey = pair[0].trim().toUpperCase(Locale.ROOT);
            if (kind != null && !methodKey.isEmpty()) {
                automatic.put(methodKey, kind);
            }
        }
        return automatic;
    }

    /**
     * Returns the document kind carrying that name, tolerating case and padding.
     *
     * @param name the enum name as typed or posted, possibly null
     * @return the kind, or null when no kind carries that name
     */
    public static DocumentType byName(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.trim().toUpperCase(Locale.ROOT);
        for (DocumentType candidate : values()) {
            if (candidate.name().equals(wanted)) {
                return candidate;
            }
        }
        return null;
    }
}
