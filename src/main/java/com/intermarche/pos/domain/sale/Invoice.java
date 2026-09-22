package com.intermarche.pos.domain.sale;

import com.intermarche.pos.domain.payment.AccountCustomer;
import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A commercial document issued on a closed ticket: an invoice, or a delivery note.
 * <p>
 * DERIVED, NEVER RECOMPUTED. The sale happened once, on the ticket, and the ticket
 * is immutable from the moment it closes: the document does not re-price anything,
 * it states what was sold and to whom. What it does keep of its own are the things
 * that could change afterwards and must not — the totals as they were printed, and
 * the customer AS THEY WERE at the moment of issue. A customer's address gets
 * corrected, a business is renamed; a document already handed over does not follow.
 * That is why the addressee is stored twice here: a link to the live customer, for
 * the reports and the searches, and a flat copy of what was printed on the paper.
 * <p>
 * The ticket lines are NOT copied. They belong to a closed ticket, they cannot
 * change, and duplicating them would create a second truth to keep in step with the
 * first. The per-rate VAT table is likewise recomputed from those lines at render
 * time, by the same rule the receipt uses — {@code BO-03-03-04} requires the two to
 * agree, and recomputing from the same source is the only way they cannot drift.
 * <p>
 * {@link #printCount} carries the duplicate rule of {@code LC-08-04-17}: the first
 * print is the original, every later one is marked as a duplicate, and a second
 * request for a ticket that already has a document produces that duplicate rather
 * than a second original.
 */
@Entity
@Table(name = "invoices",
        indexes = {
                @Index(name = "idx_invoice_number", columnList = "document_number", unique = true),
                @Index(name = "idx_invoice_ticket", columnList = "ticket_number"),
                @Index(name = "idx_invoice_date", columnList = "issue_date"),
                @Index(name = "idx_invoice_customer", columnList = "customer_id")
        }
)
public class Invoice extends BaseEntity {

    /** The document number, e.g. {@code C04-F000123}, unique per register database. */
    @Column(name = "document_number", nullable = false, unique = true, length = 30)
    @NotBlank
    public String documentNumber;

    /** What kind of document this is, which decides its sequence and its title. */
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    @NotNull
    public DocumentType documentType = DocumentType.FACTURE;

    /** The identifier of the register that issued it (pos.terminal.id). */
    @Column(name = "terminal_id", nullable = false, length = 20)
    @NotBlank
    public String terminalId;

    /** When it was issued. */
    @Column(name = "issue_date", nullable = false)
    @NotNull
    public LocalDateTime issueDate;

    /** The closed ticket it states. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    public Ticket ticket;

    /**
     * The ticket number, kept beside the link.
     * <p>
     * The same reason the ticket number is what the store node upserts on: database
     * ids are local to a register, the number is the portable identity. A document
     * that travels to a report or to accounting must name its ticket in terms
     * everyone can read.
     */
    @Column(name = "ticket_number", nullable = false, length = 30)
    @NotBlank
    public String ticketNumber;

    /** The live customer, for searches and reports. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    public AccountCustomer customer;

    /** The customer's account number as printed. */
    @Column(name = "customer_account_number", length = 30)
    public String customerAccountNumber;

    /** The addressee as printed: the business name, first line of its block. */
    @Column(name = "customer_name", nullable = false, length = 180)
    @NotBlank
    public String customerName;

    /**
     * The contact as printed, under the business name; blank when the customer is a
     * business alone. Two lines and not one parenthesised string: that is how the
     * addressee's block of a real document reads.
     */
    @Column(name = "customer_contact", length = 120)
    public String customerContact;

    /** The billing address as printed. */
    @Embedded
    @AttributeOverride(name = "streetLine1", column = @Column(name = "customer_street1"))
    @AttributeOverride(name = "streetLine2", column = @Column(name = "customer_street2"))
    @AttributeOverride(name = "postalCode", column = @Column(name = "customer_postal_code"))
    @AttributeOverride(name = "city", column = @Column(name = "customer_city"))
    @AttributeOverride(name = "country", column = @Column(name = "customer_country"))
    @AttributeOverride(name = "latitude", column = @Column(name = "customer_latitude"))
    @AttributeOverride(name = "longitude", column = @Column(name = "customer_longitude"))
    public Address customerAddress = new Address();

    /** The customer's SIRET as printed, blank when they gave none. */
    @Column(name = "customer_siret", length = 20)
    public String customerSiret;

    /** The customer's intra-community VAT number as printed. */
    @Column(name = "customer_vat_number", length = 20)
    public String customerVatNumber;

    /**
     * The customer's fiscal identifier (NIF) as printed ({@code BO-02-04-19}).
     *
     * <p>Copied onto the document like the SIRET beside it, and for the same
     * reason: what the back office extracts two years later must be what was
     * printed that day, not what the customer's file says now.
     */
    @Column(name = "customer_tax_id", length = 30)
    public String customerTaxId;

    /**
     * The date this document falls due ({@code BO-02-04-08}).
     *
     * <p>The requirement asks for it on every invoice and delivery note issued
     * at the register, so it is COPIED onto the document like the rest of the
     * customer block: a customer whose terms change next month does not move
     * the due date of a paper already handed over.
     *
     * <p>Null when the commercial management sent none for that customer, and
     * the document then prints no due-date line at all — an empty « Échéance : »
     * on an invoice is worse than none, because it reads as immediate.
     */
    @Column(name = "customer_due_date")
    public java.time.LocalDate customerDueDate;

    /** The tax-excluded total as printed. */
    @Column(name = "total_ht", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalExcludingTax = BigDecimal.ZERO;

    /** The tax-included total as printed. */
    @Column(name = "total_ttc", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalIncludingTax = BigDecimal.ZERO;

    /** The VAT total as printed. */
    @Column(name = "total_vat", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalVat = BigDecimal.ZERO;

    /**
     * The eco-tax borne by the billed sale ({@code BO-10-04-14}), zero when the
     * articles carry none.
     *
     * <p>Frozen on the document like every other total: the eco-tax of an article
     * is administered and can be revised, and an extraction produced six months
     * later must state what was billed, not what the article costs today.
     */
    @Column(name = "total_eco_tax", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalEcoTax = BigDecimal.ZERO;

    /** How many times it has been printed; the first print is the original. */
    @Column(name = "print_count", nullable = false)
    public int printCount = 0;

    /**
     * Copies onto the document what must survive a later change of the customer.
     *
     * @param source the customer the document is addressed to
     */
    public void addressTo(AccountCustomer source) {
        this.customer = source;
        this.customerAccountNumber = source.accountNumber;
        this.customerName = source.companyName;
        this.customerContact = source.getContactName();
        this.customerSiret = source.siret;
        this.customerVatNumber = source.vatNumber;
        this.customerTaxId = source.taxId;
        this.customerDueDate = source.dueDate;
        this.customerAddress = copyOf(source.address);
    }

    /**
     * Tells whether the next print is a duplicate rather than the original.
     *
     * @return true once the document has been printed at least once
     */
    public boolean isDuplicate() {
        return printCount >= 1;
    }

    /**
     * Copies an address, so the document keeps its own and not a shared reference
     * that a later edit of the customer would move under it.
     *
     * @param source the address to copy, possibly null
     * @return a fresh copy, empty when the source was null
     */
    private static Address copyOf(Address source) {
        Address copy = new Address();
        if (source == null) {
            return copy;
        }
        copy.streetLine1 = source.streetLine1;
        copy.streetLine2 = source.streetLine2;
        copy.postalCode = source.postalCode;
        copy.city = source.city;
        copy.country = source.country;
        return copy;
    }

    /**
     * Hashes the business fields, for the change detection of the base entity.
     *
     * @return the checksum of this document
     */
    @Override
    public int getChecksum() {
        return Objects.hash(documentNumber, documentType, terminalId, issueDate, ticketNumber,
                customerAccountNumber, customerName, customerContact, customerSiret,
                customerVatNumber, customerTaxId, customerDueDate,
                totalExcludingTax, totalIncludingTax, totalVat, totalEcoTax, printCount);
    }
}
