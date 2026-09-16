package com.intermarche.pos.domain.setting;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.util.List;

/**
 * An administered DOCUMENT TEMPLATE: the layout of one printed document, stated
 * as a Qute template and edited in the back office (BO-03-03).
 *
 * <p>THE DECISION THIS EMBODIES. The register already ships a Qute engine, so a
 * template engine did not have to be written — the layout of a document becomes
 * a template like every screen of the application, stored in a row instead of a
 * file. Adding a line to the receipt is then a paragraph typed in the back
 * office, not a delivery.
 *
 * <p>WHAT A TEMPLATE CAN SEE, and this is the whole safety of the design: the
 * renderer hands it MAPS, LISTS AND STRINGS — never an entity. Every amount,
 * rate, quantity and date arrives already written, exactly as
 * {@code InvoiceDocument} already does for the two renderers it feeds. A
 * template therefore lays out and nothing else: it cannot call a method on a
 * sale, cannot reach the database, and cannot format a decimal one way while
 * the rest of the register formats it another.
 *
 * <p>A document type no row administers is printed by the register's own code,
 * unchanged. That is what lets the referential be installed on a running shop
 * one document at a time.
 */
@Entity
@Table(name = "document_template")
public class DocumentTemplate extends PanacheEntity {

    /** The narrowest roll a template may declare, in characters. */
    public static final int MIN_WIDTH = 20;

    /** The widest page a template may declare, in characters. */
    public static final int MAX_WIDTH = 120;

    /** The stable technical code of the template (for example, TICKET_VENTE_FR). */
    @Column(name = "code", unique = true, nullable = false, length = 50)
    public String code;

    /** The human-readable label shown in the back office. */
    @Column(name = "label", nullable = false, length = 100)
    public String label;

    /** The document this template lays out. */
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    public DocumentType documentType;

    /** Whether the template is used; deactivated, never deleted. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /** The order competing templates of one document type are tried in. */
    @Column(name = "priority", nullable = false)
    public int priority = 100;

    /** The width the template lays out for, in characters. */
    @Column(name = "width", nullable = false)
    public int width = 42;

    /** How many copies of the document are printed (BO-03-02-33). */
    @Column(name = "copies", nullable = false)
    public int copies = 1;

    /** The Qute source the back office edits. */
    @Lob
    @Column(name = "source", nullable = false)
    public String source;

    /**
     * Tells whether the template carries a source worth rendering.
     *
     * <p>A row saved with an empty source is not an error and not a reason to
     * print nothing: it is a template the paramétreur has not written yet, and
     * the register goes on printing the document its own way.
     *
     * @return true when the template has something to render
     */
    public boolean isRenderable() {
        return active && source != null && !source.isBlank();
    }



    /**
     * Returns the active templates of one document type, in administered order.
     *
     * @param type the document type, or null
     * @return the templates, empty when the type is administered by none
     */
    public static List<DocumentTemplate> listActiveFor(DocumentType type) {
        if (type == null) {
            return List.of();
        }
        return list("active = true and documentType = ?1 order by priority, code", type);
    }

    /**
     * Returns the template that lays out a document type, or null when the
     * register lays it out itself.
     *
     * @param type the document type, or null
     * @return the first renderable active template, or null when there is none
     */
    public static DocumentTemplate findFor(DocumentType type) {
        for (DocumentTemplate template : listActiveFor(type)) {
            if (template != null && template.isRenderable()) {
                return template;
            }
        }
        return null;
    }

    /**
     * Returns every administered template, active or not, in administered order.
     *
     * @return the whole referential, empty when nothing is administered
     */
    public static List<DocumentTemplate> listAllOrdered() {
        return list("order by documentType, priority, code");
    }

    /**
     * Returns the administered template of the given code.
     *
     * @param code the technical code, or null
     * @return the template, or null when no row carries that code
     */
    public static DocumentTemplate findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return find("code", code.trim()).firstResult();
    }

    /**
     * The documents the register prints and a template may lay out (BO-03-03).
     *
     * <p>One constant per document the register actually emits today: the list
     * is deliberately closed, because a template for a document nothing prints
     * would be a row nobody ever reads.
     */
    public enum DocumentType {

        /** The sale receipt. */
        SALE_RECEIPT("Ticket de vente"),

        /** The refund receipt. */
        REFUND_RECEIPT("Ticket de retour"),

        /** The commercial invoice, as the register's own roll prints it. */
        INVOICE("Facture (rouleau)"),

        /** The X report (reading). */
        X_REPORT("Rapport X"),

        /** The Z report (closing). */
        Z_REPORT("Rapport Z"),

        /** The withdrawal ticket. */
        WITHDRAWAL_TICKET("Ticket de prélèvement"),

        /** The transfer ticket between two tenders. */
        TRANSFER_TICKET("Ticket de transfert"),

        /** The voucher printed for a freshly issued gift card. */
        GIFT_CARD_VOUCHER("Bon carte cadeau"),

        /** The voucher printed for a credit note. */
        CREDIT_NOTE_VOUCHER("Bon d'avoir"),

        /** The card receipt handed over with a settlement. */
        CARD_RECEIPT("Justificatif carte"),

        /**
         * The commercial invoice, as the network printer's A4 page states it.
         *
         * <p>A SECOND type for the same document, deliberately: the roll states
         * it in forty-two columns of text and the office printer states it as an
         * HTML page, and one source cannot be both. Two rows so that a shop
         * which restates its invoice restates it on BOTH papers — a document
         * that changed on one and not the other is an accounting defect, not an
         * ergonomic detail.
         */
        INVOICE_A4("Facture (A4 réseau)");

        /** The label shown on the administration screen. */
        private final String label;

        /**
         * Builds a document type with its administration label.
         *
         * @param label the label shown on the administration screen
         */
        DocumentType(String label) {
            this.label = label;
        }

        /**
         * Returns the label shown on the administration screen.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }
    }
}
