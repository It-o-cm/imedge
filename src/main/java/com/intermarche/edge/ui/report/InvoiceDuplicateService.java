package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.session.TechnicalEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * REPRINTS an invoice or a delivery note from the back office, as a DUPLICATE
 * (BO-06-04-03, « réimpression des factures à partir du backoffice
 * encaissement, avec ajout mention duplicata »).
 *
 * <p>The document is rebuilt from what the node holds — the document's own
 * header, the customer it was addressed to, the lines of the ticket it states,
 * its totals — and rendered as a PDF by the report frame, its title carrying
 * the mention « DUPLICATA » and its number: a reprint never passes for the
 * original. The reprint is counted on the document and journaled, so the
 * duplicates of a document can be numbered and traced like the register's.
 *
 * <p>Placement: ui.report, beside the list of invoices it reprints from.
 */
@ApplicationScoped
public class InvoiceDuplicateService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(InvoiceDuplicateService.class);

    /** The day format of the document. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** The PDF renderer of the report frame. */
    @Inject PdfReportWriter pdfReportWriter;

    /** The journal. */
    @Inject com.intermarche.edge.service.TechnicalEventService technicalEventService;

    /**
     * The duplicate as a document the frame renders: the lines of the ticket
     * the invoice states, the header in the preamble. Not a bean: it is built
     * for one reprint and never listed among the reports.
     */
    static final class Document implements ReportDefinition {

        /** The invoice reprinted. */
        private final Invoice invoice;

        /** The duplicate's number. */
        private final int duplicate;

        /**
         * Builds the document of a reprint.
         *
         * @param invoice the invoice
         * @param duplicate the duplicate's number
         */
        Document(Invoice invoice, int duplicate) {
            this.invoice = invoice;
            this.duplicate = duplicate;
        }

        /**
         * {@inheritDoc}
         *
         * @return the document's number as code
         */
        @Override
        public String code() {
            return "duplicata-" + invoice.documentNumber;
        }

        /**
         * {@inheritDoc}
         *
         * @return the printed title, the duplicate mention included
         */
        @Override
        public String label() {
            return invoice.printedTitle() + " N° " + invoice.documentNumber + " — DUPLICATA N° " + duplicate;
        }

        /**
         * {@inheritDoc}
         *
         * @return the requirement this reprint answers
         */
        @Override
        public String requirement() {
            return "BO-06-04-03";
        }

        /**
         * {@inheritDoc}
         *
         * @return the customers family
         */
        @Override
        public ReportFamily family() {
            return ReportFamily.CUSTOMERS;
        }

        /**
         * {@inheritDoc}
         *
         * @return the columns of an invoice line
         */
        @Override
        public List<ReportColumn> columns() {
            return List.of(
                    ReportColumn.text("ean", "Code"),
                    ReportColumn.text("label", "Désignation"),
                    ReportColumn.decimal("quantity", "Quantité"),
                    ReportColumn.average("unit", "Prix unitaire TTC"),
                    ReportColumn.percent("rate", "TVA"),
                    ReportColumn.amount("total", "Montant TTC"));
        }

        /**
         * {@inheritDoc}
         *
         * @param criteria the edition parameters, unused
         * @return the header: the customer, the ticket, the totals
         */
        @Override
        public List<String> preamble(ReportCriteria criteria) {
            List<String> lines = new ArrayList<>();
            lines.add("DUPLICATA — émis le " + DAY.format(invoice.issueDate) + " sur la caisse " + invoice.terminalId
                    + ", ticket " + invoice.ticketNumber);
            lines.add("Client : " + invoice.customerName
                    + (invoice.customerAccountNumber == null ? "" : " (n° " + invoice.customerAccountNumber + ")"));
            String address = invoice.customerAddress == null ? "" : String.join(" ", nonNull(
                    invoice.customerAddress.streetLine1, invoice.customerAddress.postalCode,
                    invoice.customerAddress.city)).trim();
            if (!address.isEmpty()) {
                lines.add("Adresse : " + address);
            }
            lines.add("Total HT " + ReportFormat.cell(ReportColumn.amount("a", "a"), invoice.totalExcludingTax)
                    + " — TVA " + ReportFormat.cell(ReportColumn.amount("a", "a"), invoice.totalVat)
                    + " — TTC " + ReportFormat.cell(ReportColumn.amount("a", "a"), invoice.totalIncludingTax));
            return lines;
        }

        /**
         * {@inheritDoc}
         *
         * @param criteria the edition parameters, unused
         * @return the ticket's lines, the cancelled ones left out
         */
        @Override
        public List<List<Object>> rows(ReportCriteria criteria) {
            List<List<Object>> rows = new ArrayList<>();
            if (invoice.ticket == null) {
                return rows;
            }
            for (TicketLine line : invoice.ticket.lines) {
                if (!line.cancelled) {
                    rows.add(Arrays.asList(line.ean == null ? "" : line.ean, line.productLabel, line.quantity,
                            line.unitPrice, line.vatRate == null ? null : line.vatRate.multiply(BigDecimal.valueOf(100)),
                            line.totalPrice));
                }
            }
            return rows;
        }

        /**
         * Keeps the non-null parts of an address.
         *
         * @param parts the parts
         * @return the parts that are not null
         */
        private static List<String> nonNull(String... parts) {
            List<String> kept = new ArrayList<>();
            for (String part : parts) {
                if (part != null) {
                    kept.add(part);
                }
            }
            return kept;
        }
    }

    /**
     * Renders the duplicate of a document and counts the reprint.
     *
     * @param number the document's number
     * @param language the language its headings and labels are printed in
     * @return the PDF, or null when no document carries the number
     */
    @Transactional
    public byte[] duplicate(String number, String language) {
        LOGGER.info("Entering method duplicate with number: " + number);
        Invoice invoice = number == null ? null : Invoice.find("documentNumber", number).firstResult();
        if (invoice == null) {
            LOGGER.info("Exiting method duplicate: unknown document");
            return null;
        }
        Document document = new Document(invoice, countReprint(invoice));
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.DAY;
        criteria.reference = invoice.issueDate.toLocalDate();
        criteria.language = language;
        byte[] pdf = pdfReportWriter.write(new ReportData(document, document.rows(criteria)), criteria);
        LOGGER.info("Exiting method duplicate");
        return pdf;
    }

    /**
     * Counts one back-office reprint of a document and journals it: the ONE
     * rule both reprint routes share, the printable duplicate of the journal
     * (BO-04-01-53) and the PDF of the report frame (BO-06-04-03). The first
     * print is the original even when the document reached the node unprinted,
     * so the n-th reprint is the duplicate N° n. The event names the document
     * and its ticket, under the register that issued it when it is known, so
     * the journal finds the reprint beside the register's own duplicates.
     *
     * @param invoice the document reprinted, managed by the caller's transaction
     * @return the duplicate's number
     */
    public int countReprint(Invoice invoice) {
        LOGGER.info("Entering method countReprint with number: " + invoice.documentNumber);
        invoice.printCount = Math.max(1, invoice.printCount) + 1;
        int duplicate = invoice.printCount - 1;
        TechnicalEvent event = technicalEventService.record(TechnicalEvent.EventType.DUPLICATA_PRINTED,
                "Duplicata " + duplicate + " du document " + invoice.documentNumber + " réimprimé du back-office",
                null, invoice.ticketNumber, null, invoice.documentNumber);
        if (invoice.terminalId != null) {
            event.terminalId = invoice.terminalId;
        }
        LOGGER.info("Exiting method countReprint");
        return duplicate;
    }
}
