package com.intermarche.edge.ui.fiscal;

import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The TRANSACTIONS EXPORT ({@code BO-05-01-06}): every ticket and invoice the
 * point of sale issued, found by the filters the requirement names, totalled by
 * document and by customer, and exported.
 *
 * <p>ONE VOCABULARY for the documents, the Portuguese one the requirement uses:
 * {@code FS} a simplified invoice — the ticket of a sale —, {@code FT} an
 * invoice, {@code NC} a credit note — a return —, {@code RC} a receipt. Where
 * the registers issue the Portuguese fiscal documents, those are what is listed,
 * with their number, their NIF and their totals; elsewhere the tickets, the
 * invoices and the returns stand for them. A sale is listed once: a ticket
 * stated by fiscal documents is listed through them.
 *
 * <p>Credit notes count NEGATIVELY in the totals, so the total of a customer is
 * what they owe and not what they moved. The tickets stay online for the years
 * administered in {@code fiscal.online-retention-years}: the retention purge
 * never deletes a sale or an invoice younger than that.
 */
@ApplicationScoped
public class TransactionExportService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TransactionExportService.class);

    /** How many documents one search reads at most per kind. */
    static final int MAX_ROWS = 5000;

    /** The document types of the filter. */
    public static final List<String> TYPES = List.of("FS", "FT", "NC", "RC");

    /**
     * The filters of a search.
     *
     * @param from the first day, inclusive
     * @param to the last day, inclusive
     * @param type the document type, or null for all
     * @param nif the customer's tax number, or null
     * @param customerNumber the customer's number, or null
     * @param customerName a fragment of the customer's name, or null
     * @param ticketNumber the ticket number, or null
     * @param htMin the smallest total tax excluded, or null
     * @param htMax the largest total tax excluded, or null
     * @param ttcMin the smallest total tax included, or null
     * @param ttcMax the largest total tax included, or null
     */
    public record Criteria(LocalDate from, LocalDate to, String type, String nif, String customerNumber,
                           String customerName, String ticketNumber, BigDecimal htMin, BigDecimal htMax,
                           BigDecimal ttcMin, BigDecimal ttcMax) {
    }

    /** One document of the result. */
    public static class Row {

        /** The document type: FS, FT, NC or RC. */
        public String type;

        /** The document number. */
        public String number;

        /** When it was issued. */
        public LocalDateTime issuedAt;

        /** The register that issued it. */
        public String terminalId;

        /** The ticket it states, or null. */
        public String ticketNumber;

        /** The customer's number: account or loyalty card, or null. */
        public String customerNumber;

        /** The customer's name, or null. */
        public String customerName;

        /** The customer's tax number, or null. */
        public String nif;

        /** The total tax excluded, signed. */
        public BigDecimal totalExcludingTax = BigDecimal.ZERO;

        /** The VAT, signed. */
        public BigDecimal totalVat = BigDecimal.ZERO;

        /** The total tax included, signed. */
        public BigDecimal totalIncludingTax = BigDecimal.ZERO;

        /**
         * Returns the type, for a template.
         *
         * @return the type
         */
        public String getType() {
            return type;
        }

        /**
         * Returns the number, for a template.
         *
         * @return the number
         */
        public String getNumber() {
            return number;
        }

        /**
         * Returns the day and time of issue, for a template.
         *
         * @return the instant, without seconds' fraction, empty when unknown
         */
        public String getIssued() {
            return issuedAt == null ? "" : issuedAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }

        /**
         * The customer key the totals group on: the number, else the NIF,
         * else the name, else the anonymous customer.
         *
         * @return the key
         */
        public String customerKey() {
            return firstFilled(customerNumber, firstFilled(nif, firstFilled(customerName, "")));
        }
    }

    /** The totals of one customer. */
    public static class CustomerTotal {

        /** The customer's key. */
        public String customer;

        /** The customer's name, when known. */
        public String name;

        /** How many documents. */
        public int count;

        /** The total tax excluded. */
        public BigDecimal totalExcludingTax = BigDecimal.ZERO;

        /** The VAT. */
        public BigDecimal totalVat = BigDecimal.ZERO;

        /** The total tax included. */
        public BigDecimal totalIncludingTax = BigDecimal.ZERO;
    }

    /** The result of a search. */
    public static class Result {

        /** The documents, by date of issue. */
        public final List<Row> rows = new ArrayList<>();

        /** The totals by customer, the largest first. */
        public final List<CustomerTotal> customers = new ArrayList<>();

        /** The grand totals: tax excluded, VAT, tax included. */
        public BigDecimal totalExcludingTax = BigDecimal.ZERO;

        /** The VAT of the result. */
        public BigDecimal totalVat = BigDecimal.ZERO;

        /** The total tax included of the result. */
        public BigDecimal totalIncludingTax = BigDecimal.ZERO;

        /** Whether a kind of document hit the reading ceiling. */
        public boolean truncated;
    }

    /**
     * Leaves out an invoice whose sale the Portuguese fiscal documents already
     * state: the documents are the fiscal record there, and the same sale is
     * never counted twice in the totals.
     */
    static final String STATED_ELSEWHERE = "and ticketNumber not in (select t.ticketNumber from Ticket t "
            + "where t.id in (select f.ticketId from FiscalDocument f where f.ticketId is not null)) ";

    /**
     * Runs a search.
     *
     * @param criteria the filters
     * @return the documents and their totals
     */
    @Transactional
    public Result search(Criteria criteria) {
        LOGGER.info("Entering method search with " + criteria);
        LocalDateTime start = criteria.from().atStartOfDay();
        LocalDateTime end = criteria.to().plusDays(1).atStartOfDay();
        Result result = new Result();
        List<Row> rows = new ArrayList<>();
        List<FiscalDocument> documents = FiscalDocument.<FiscalDocument>find(
                "issuedAt >= ?1 and issuedAt < ?2 order by issuedAt", start, end).page(0, MAX_ROWS).list();
        result.truncated |= documents.size() >= MAX_ROWS;
        Map<Long, String> ticketNumbers = new HashMap<>();
        for (FiscalDocument document : documents) {
            rows.add(fromDocument(document, ticketNumbers));
        }
        List<Ticket> tickets = Ticket.<Ticket>find("status = ?1 and closingDate >= ?2 and closingDate < ?3 "
                + "and id not in (select f.ticketId from FiscalDocument f where f.ticketId is not null) "
                + "and ticketNumber not in (select i.ticketNumber from Invoice i where i.ticketNumber is not null) "
                + "order by closingDate", Ticket.TicketStatus.CLOSED, start, end).page(0, MAX_ROWS).list();
        result.truncated |= tickets.size() >= MAX_ROWS;
        for (Ticket ticket : tickets) {
            rows.add(fromTicket(ticket));
        }
        List<Invoice> invoices = Invoice.<Invoice>find("issueDate >= ?1 and issueDate < ?2 " + STATED_ELSEWHERE
                + "order by issueDate", start, end).page(0, MAX_ROWS).list();
        result.truncated |= invoices.size() >= MAX_ROWS;
        for (Invoice invoice : invoices) {
            rows.add(fromInvoice(invoice));
        }
        List<Refund> refunds = Refund.<Refund>find("creationDate >= ?1 and creationDate < ?2 "
                + "and id not in (select f.refundId from FiscalDocument f where f.refundId is not null) "
                + "order by creationDate", start, end).page(0, MAX_ROWS).list();
        result.truncated |= refunds.size() >= MAX_ROWS;
        for (Refund refund : refunds) {
            rows.add(fromRefund(refund, ticketNumbers));
        }
        Map<String, CustomerTotal> byCustomer = new LinkedHashMap<>();
        for (Row row : rows) {
            if (!matches(row, criteria)) {
                continue;
            }
            result.rows.add(row);
            result.totalExcludingTax = result.totalExcludingTax.add(row.totalExcludingTax);
            result.totalVat = result.totalVat.add(row.totalVat);
            result.totalIncludingTax = result.totalIncludingTax.add(row.totalIncludingTax);
            String key = row.customerKey();
            if (!key.isEmpty()) {
                CustomerTotal total = byCustomer.computeIfAbsent(key, k -> {
                    CustomerTotal created = new CustomerTotal();
                    created.customer = k;
                    return created;
                });
                total.name = firstFilled(total.name, row.customerName);
                total.count++;
                total.totalExcludingTax = total.totalExcludingTax.add(row.totalExcludingTax);
                total.totalVat = total.totalVat.add(row.totalVat);
                total.totalIncludingTax = total.totalIncludingTax.add(row.totalIncludingTax);
            }
        }
        result.rows.sort(Comparator.comparing((Row row) -> row.issuedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        result.customers.addAll(byCustomer.values());
        result.customers.sort(Comparator.comparing((CustomerTotal total) -> total.totalIncludingTax).reversed());
        LOGGER.info("Exiting method search with " + result.rows.size() + " document(s)");
        return result;
    }

    /**
     * A Portuguese fiscal document as a row; a credit note counts negatively.
     *
     * @param document the document
     * @param ticketNumbers the ticket numbers already read, by id
     * @return the row
     */
    Row fromDocument(FiscalDocument document, Map<Long, String> ticketNumbers) {
        Row row = new Row();
        row.type = document.documentType.name();
        row.number = document.number;
        row.issuedAt = document.issuedAt;
        row.terminalId = document.terminalId;
        row.ticketNumber = document.ticketId == null ? null : ticketNumber(document.ticketId, ticketNumbers);
        row.customerName = document.customerName;
        row.nif = FiscalDocument.isFinalConsumer(document.customerTaxId) ? null : document.customerTaxId;
        BigDecimal sign = document.documentType == FiscalDocument.Type.NC ? BigDecimal.ONE.negate() : BigDecimal.ONE;
        BigDecimal gross = zero(document.grossTotal).abs();
        BigDecimal tax = zero(document.taxTotal).abs();
        row.totalIncludingTax = gross.multiply(sign);
        row.totalVat = tax.multiply(sign);
        row.totalExcludingTax = gross.subtract(tax).multiply(sign);
        return row;
    }

    /**
     * A ticket the registers issued without a fiscal document, as a simplified
     * invoice. A ticket an invoice was issued for is stated by that invoice
     * alone, as a Portuguese simplified invoice is cancelled by the invoice that
     * replaces it: the same sale is never counted twice in the totals.
     *
     * @param ticket the ticket
     * @return the row
     */
    Row fromTicket(Ticket ticket) {
        Row row = new Row();
        row.type = "FS";
        row.number = ticket.ticketNumber;
        row.issuedAt = ticket.closingDate;
        row.terminalId = ticket.terminalId;
        row.ticketNumber = ticket.ticketNumber;
        row.customerNumber = ticket.fidelityCard;
        row.nif = ticket.customerTaxId;
        row.totalExcludingTax = zero(ticket.totalExcludingTax);
        row.totalVat = zero(ticket.totalVat);
        row.totalIncludingTax = zero(ticket.totalIncludingTax);
        return row;
    }

    /**
     * An invoice as a row.
     *
     * @param invoice the invoice
     * @return the row
     */
    Row fromInvoice(Invoice invoice) {
        Row row = new Row();
        row.type = "FT";
        row.number = invoice.documentNumber;
        row.issuedAt = invoice.issueDate;
        row.terminalId = invoice.terminalId;
        row.ticketNumber = invoice.ticketNumber;
        row.customerNumber = invoice.customerAccountNumber;
        row.customerName = invoice.customerName;
        row.nif = firstFilled(invoice.customerTaxId, firstFilled(invoice.customerVatNumber, invoice.customerSiret));
        row.totalExcludingTax = zero(invoice.totalExcludingTax);
        row.totalVat = zero(invoice.totalVat);
        row.totalIncludingTax = zero(invoice.totalIncludingTax);
        return row;
    }

    /**
     * A return the registers issued without a credit note, as a credit note.
     *
     * @param refund the return
     * @param ticketNumbers the ticket numbers already read, by id
     * @return the row, its totals negative
     */
    Row fromRefund(Refund refund, Map<Long, String> ticketNumbers) {
        Row row = new Row();
        row.type = "NC";
        row.number = refund.refundNumber;
        row.issuedAt = refund.creationDate;
        row.terminalId = refund.terminalId;
        row.ticketNumber = refund.originalTicketId == null ? null
                : ticketNumber(refund.originalTicketId, ticketNumbers);
        row.customerNumber = refund.creditAccountNumber;
        row.totalExcludingTax = zero(refund.totalExcludingTax).abs().negate();
        row.totalVat = zero(refund.totalVat).abs().negate();
        row.totalIncludingTax = zero(refund.totalAmount).abs().negate();
        return row;
    }

    /**
     * The number of a ticket, read once.
     *
     * @param id the ticket's id
     * @param ticketNumbers the numbers already read
     * @return the number, or null when the node does not hold the ticket
     */
    private static String ticketNumber(Long id, Map<Long, String> ticketNumbers) {
        return ticketNumbers.computeIfAbsent(id, key -> {
            Ticket ticket = Ticket.findById(key);
            return ticket == null ? null : ticket.ticketNumber;
        });
    }

    /**
     * Whether a row passes every filter.
     *
     * @param row the row
     * @param criteria the filters
     * @return true when it is kept
     */
    static boolean matches(Row row, Criteria criteria) {
        return (criteria.type() == null || criteria.type().equals(row.type))
                && same(criteria.nif(), row.nif)
                && same(criteria.customerNumber(), row.customerNumber)
                && contains(row.ticketNumber, criteria.ticketNumber())
                && contains(row.customerName, criteria.customerName())
                && within(row.totalExcludingTax, criteria.htMin(), criteria.htMax())
                && within(row.totalIncludingTax, criteria.ttcMin(), criteria.ttcMax());
    }

    /**
     * Whether a value equals a filter, spaces and case ignored; no filter
     * passes everything.
     *
     * @param filter the filter, possibly null
     * @param value the value, possibly null
     * @return true when kept
     */
    static boolean same(String filter, String value) {
        return filter == null || (value != null && normalize(filter).equals(normalize(value)));
    }

    /**
     * Whether a value contains a fragment, case ignored; no fragment passes
     * everything.
     *
     * @param value the value, possibly null
     * @param fragment the fragment, possibly null
     * @return true when kept
     */
    static boolean contains(String value, String fragment) {
        return fragment == null || (value != null
                && value.toLowerCase(Locale.ROOT).contains(fragment.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Whether an amount lies within bounds.
     *
     * @param amount the amount
     * @param min the smallest, or null
     * @param max the largest, or null
     * @return true when kept
     */
    static boolean within(BigDecimal amount, BigDecimal min, BigDecimal max) {
        return (min == null || amount.compareTo(min) >= 0) && (max == null || amount.compareTo(max) <= 0);
    }

    /**
     * A value without its spaces, upper case.
     *
     * @param value the value
     * @return the normalized value
     */
    private static String normalize(String value) {
        return value.replace(" ", "").toUpperCase(Locale.ROOT);
    }

    /**
     * An amount, zero when absent.
     *
     * @param value the amount, possibly null
     * @return the amount, never null
     */
    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * The first value that is filled.
     *
     * @param value the preferred value, possibly null or blank
     * @param fallback the value used otherwise
     * @return the value, or the fallback
     */
    static String firstFilled(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
