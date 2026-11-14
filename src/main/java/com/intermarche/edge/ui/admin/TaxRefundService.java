package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.sale.TaxRefundForm;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.sale.VatBreakdown;
import com.intermarche.edge.ui.report.PdfReportWriter;
import com.intermarche.edge.ui.report.ReportColumn;
import com.intermarche.edge.ui.report.ReportCriteria;
import com.intermarche.edge.ui.report.ReportData;
import com.intermarche.edge.ui.report.ReportDefinition;
import com.intermarche.edge.ui.report.ReportFamily;
import com.intermarche.edge.ui.report.ReportPeriod;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The DETAXE module of the welcome desk (BO-06-07-03): prints the export sales
 * slip of a customer's ticket, and records the refund of its VAT when the
 * customer brings it back stamped by the customs.
 *
 * <p>The slip is built from the ticket the node holds: its lines sold, rate by
 * rate — 2,10 %, 5,5 %, 10 % and 20 % — VAT included and their VAT, the amount
 * to refund being the ticket's VAT. A ticket returned, cancelled or unknown
 * has no slip, and one ticket has one slip: a second request reprints the
 * first rather than refunding the VAT twice.
 *
 * <p>Placement: ui.admin, serving the Détaxe screen.
 */
@ApplicationScoped
public class TaxRefundService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TaxRefundService.class);

    /** The date stamp of a slip's number. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** The PDF renderer of the report frame. */
    @Inject PdfReportWriter pdfReportWriter;

    /** The outcome of a slip request. */
    public record Outcome(TaxRefundForm form, String refusal) {
    }

    /**
     * Prints the slip of a ticket, or finds the one already printed.
     *
     * @param ticketNumber the ticket's number
     * @param customerName the customer's name
     * @param country the customer's country or the boat's registration, possibly null
     * @param document the customer's identity document, possibly null
     * @param by who prints it, possibly null
     * @return the slip, or the French refusal
     */
    @Transactional
    public Outcome issue(String ticketNumber, String customerName, String country, String document, String by) {
        LOGGER.info("Entering method issue with ticketNumber: " + ticketNumber);
        if (ticketNumber == null || customerName == null) {
            return new Outcome(null, "Numéro de ticket et nom du client obligatoires.");
        }
        TaxRefundForm existing = TaxRefundForm.find("ticketNumber", ticketNumber).firstResult();
        if (existing != null) {
            return new Outcome(existing, null);
        }
        Ticket ticket = Ticket.find("ticketNumber", ticketNumber).firstResult();
        if (ticket == null || ticket.status != Ticket.TicketStatus.CLOSED) {
            return new Outcome(null, "Ticket inconnu ou non clôturé.");
        }
        TaxRefundForm form = new TaxRefundForm();
        form.ticketNumber = ticketNumber;
        form.customerName = customerName;
        form.customerCountry = country;
        form.customerDocument = document;
        form.createdAt = LocalDateTime.now();
        form.createdBy = by;
        form.number = "BV" + STAMP.format(form.createdAt) + "-" + ticketNumber;
        fill(form, ticket);
        form.persist();
        LOGGER.infof("Bordereau de détaxe %s imprimé pour le ticket %s", form.number, ticketNumber);
        LOGGER.info("Exiting method issue");
        return new Outcome(form, null);
    }

    /**
     * Fills a slip with the ticket's amounts, rate by rate.
     *
     * @param form the slip
     * @param ticket the ticket
     */
    static void fill(TaxRefundForm form, Ticket ticket) {
        VatBreakdown breakdown = new VatBreakdown();
        for (TicketLine line : ticket.lines) {
            if (!line.cancelled && !line.returnLine) {
                breakdown.add(line.vatRate, line.totalPrice);
            }
        }
        BigDecimal refund = BigDecimal.ZERO;
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            refund = refund.add(bucket.vatAmount);
            switch (bucket.rate.setScale(4, RoundingMode.HALF_UP).toPlainString()) {
                case "0.0210" -> {
                    form.sales210 = form.sales210.add(bucket.totalIncludingTax);
                    form.vat210 = form.vat210.add(bucket.vatAmount);
                }
                case "0.0550" -> {
                    form.sales55 = form.sales55.add(bucket.totalIncludingTax);
                    form.vat55 = form.vat55.add(bucket.vatAmount);
                }
                case "0.1000" -> {
                    form.sales10 = form.sales10.add(bucket.totalIncludingTax);
                    form.vat10 = form.vat10.add(bucket.vatAmount);
                }
                case "0.2000" -> {
                    form.sales20 = form.sales20.add(bucket.totalIncludingTax);
                    form.vat20 = form.vat20.add(bucket.vatAmount);
                }
                default -> {
                }
            }
        }
        form.refundAmount = refund;
    }

    /**
     * Records the refund of a slip's VAT.
     *
     * @param number the slip's number
     * @param by who paid it out, possibly null
     * @return null on success, else the French refusal
     */
    @Transactional
    public String refund(String number, String by) {
        LOGGER.info("Entering method refund with number: " + number);
        TaxRefundForm form = TaxRefundForm.findByNumber(number);
        if (form == null) {
            return "Bordereau inconnu.";
        }
        if (TaxRefundForm.REFUNDED.equals(form.status)) {
            return "Ce bordereau est déjà remboursé.";
        }
        form.status = TaxRefundForm.REFUNDED;
        form.refundedAt = LocalDateTime.now();
        form.refundedBy = by;
        LOGGER.info("Exiting method refund");
        return null;
    }

    /**
     * Renders a slip as a PDF.
     *
     * @param number the slip's number
     * @param language the language its headings and labels are printed in
     * @return the PDF, or null for an unknown slip
     */
    public byte[] slip(String number, String language) {
        LOGGER.info("Entering method slip with number: " + number);
        TaxRefundForm form = TaxRefundForm.findByNumber(number);
        if (form == null) {
            return null;
        }
        Slip slip = new Slip(form);
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.DAY;
        criteria.reference = form.createdAt.toLocalDate();
        criteria.language = language;
        LOGGER.info("Exiting method slip");
        return pdfReportWriter.write(new ReportData(slip, slip.rows(criteria)), criteria);
    }

    /**
     * The slip as a document the frame renders. Not a bean: built for one print.
     */
    static final class Slip implements ReportDefinition {

        /** The slip. */
        private final TaxRefundForm form;

        /**
         * Builds the document of a slip.
         *
         * @param form the slip
         */
        Slip(TaxRefundForm form) {
            this.form = form;
        }

        /**
         * {@inheritDoc}
         *
         * @return the slip's number
         */
        @Override
        public String code() {
            return form.number;
        }

        /**
         * {@inheritDoc}
         *
         * @return the slip's title
         */
        @Override
        public String label() {
            return "Bordereau de vente à l'exportation N° " + form.number;
        }

        /**
         * {@inheritDoc}
         *
         * @return the requirement the slip answers
         */
        @Override
        public String requirement() {
            return "BO-06-07-03";
        }

        /**
         * {@inheritDoc}
         *
         * @return the tax family
         */
        @Override
        public ReportFamily family() {
            return ReportFamily.TAX;
        }

        /**
         * {@inheritDoc}
         *
         * @return the rate, the amount sold and its VAT
         */
        @Override
        public List<ReportColumn> columns() {
            return List.of(ReportColumn.text("rate", "Taux de TVA"),
                    ReportColumn.amount("sales", "Montant des articles TTC"),
                    ReportColumn.amount("vat", "Montant de la TVA"));
        }

        /**
         * {@inheritDoc}
         *
         * @param criteria the edition parameters, unused
         * @return the customer, the ticket and the amount to refund
         */
        @Override
        public List<String> preamble(ReportCriteria criteria) {
            List<String> lines = new ArrayList<>();
            // One fact per line, each a sentence the bundles translate (BO-06-01-02).
            lines.add("Client : " + form.customerName);
            if (form.customerCountry != null) {
                lines.add("Pays de résidence : " + form.customerCountry);
            }
            if (form.customerDocument != null) {
                lines.add("Pièce d'identité : " + form.customerDocument);
            }
            lines.add("Ticket " + form.ticketNumber + " — montant de TVA à rembourser : "
                    + com.intermarche.edge.ui.report.ReportFormat.cell(ReportColumn.amount("a", "a"), form.refundAmount));
            lines.add("Visa des douanes : ____________________");
            return lines;
        }

        /**
         * {@inheritDoc}
         *
         * @param criteria the edition parameters, unused
         * @return one row per rate
         */
        @Override
        public List<List<Object>> rows(ReportCriteria criteria) {
            return List.of(
                    Arrays.asList("2,10 %", form.sales210, form.vat210),
                    Arrays.asList("5,5 %", form.sales55, form.vat55),
                    Arrays.asList("10 %", form.sales10, form.vat10),
                    Arrays.asList("20 %", form.sales20, form.vat20));
        }

        /**
         * {@inheritDoc}
         *
         * @return the customer's signature
         */
        @Override
        public String signature() {
            return "Signature du client";
        }
    }
}
