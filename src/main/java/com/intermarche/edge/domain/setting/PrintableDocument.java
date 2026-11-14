package com.intermarche.edge.domain.setting;

import com.intermarche.edge.domain.sale.DocumentType;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.List;

/**
 * A DOCUMENT the registers may print over a closed ticket (BO-03-04-06, -09,
 * -10 and -14): an invoice, a delivery note, or any other paper the shop
 * assembles from its templates and names itself.
 *
 * <p>THE ASSEMBLY, NOT THE LAYOUT. The layout of a document is a template of the
 * referential, which itself includes its zones ({@code [[ZONE code]]}); this row
 * is what turns such a layout into something the operator is OFFERED at the
 * till: a name printed on the paper, the nature of the document — which decides
 * its numbering sequence — the printer it comes out of, and whether it is
 * active. Creating a row creates a document, activating it makes it available
 * at every register on the next referential pull, and deleting it removes it
 * from them.
 *
 * <p>While the shop has created no row, the registers keep offering the
 * built-in kinds of the {@code invoice.document-types} parameter: the referential
 * can be installed on a running shop without a day where nothing is printable.
 */
@Entity
@Table(name = "printable_document")
public class PrintableDocument extends PanacheEntity {

    /** The stable technical code of the document (for example, FACTURE_PRO). */
    @Column(name = "code", unique = true, nullable = false, length = 30)
    public String code;

    /** The NAME printed on the document and offered to the operator. */
    @Column(name = "label", nullable = false, length = 60)
    public String label;

    /** What the document is: its nature, and so its numbering sequence. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    public DocumentType kind = DocumentType.FACTURE;

    /** The printers a document may come out of (BO-03-04-14), in the order the form offers them. */
    public static final List<String> OUTPUTS = List.of("TICKET", "FACTURETTE", "A4");

    /**
     * The printer the document comes out of (BO-03-04-14): {@code TICKET} for
     * the register's roll, {@code FACTURETTE} for the slip station,
     * {@code A4} for the network printer; null when the
     * {@code invoice.document-output} parameter decides.
     */
    @Column(name = "output", length = 20)
    public String output;

    /**
     * The code of the template that lays the document out — a gabarit
     * assembling its zones — or null when the register's own layout stands.
     */
    @Column(name = "template_code", length = 50)
    public String templateCode;

    /** Whether the document is offered at the registers (BO-03-04-06). */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /** The order the documents are offered in. */
    @Column(name = "priority", nullable = false)
    public int priority = 100;

    /**
     * Returns the documents the registers offer, in administered order.
     *
     * @return the active documents, empty when the shop created none
     */
    public static List<PrintableDocument> listActiveOrdered() {
        return list("active = true order by priority, code");
    }

    /**
     * Returns every document, active or not, in administered order.
     *
     * @return the whole referential
     */
    public static List<PrintableDocument> listAllOrdered() {
        return list("order by priority, code");
    }

    /**
     * Returns the document of the given code.
     *
     * @param code the technical code, or null
     * @return the document, or null when no row carries that code
     */
    public static PrintableDocument findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return find("code", code.trim()).firstResult();
    }

    /**
     * Reads one of the {@link #OUTPUTS}, tolerating case and padding.
     *
     * @param name the posted or stored name, possibly null
     * @return the printer name, or null when the name is blank or unknown
     */
    public static String outputOf(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.trim().toUpperCase(java.util.Locale.ROOT);
        return OUTPUTS.contains(wanted) ? wanted : null;
    }
}
