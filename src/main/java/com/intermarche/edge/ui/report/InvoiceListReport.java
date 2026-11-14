package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.payment.AccountCustomer;
import com.intermarche.edge.domain.sale.DocumentType;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.service.NodeEchelonService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * LISTE DES FACTURES IMPRIMÉES EN CAISSE (BO-06-03-05, BO-06-04-03): every
 * invoice and delivery note the registers printed over the period, with the
 * point of sale, the document's number, the customer's first name, last name
 * and number, the amounts including and excluding tax and the tax, the ticket
 * it states, its date, and the customer's telephone and e-mail — the date of
 * the report and the dates of the period stated above the list.
 *
 * <p>Exported like every report, in CSV, in a spreadsheet and in PDF; each row
 * carries the REPRINT of its document as a DUPLICATE (BO-06-04-03), which the
 * back office renders without the register.
 *
 * <p>The customer is read from the account the document was addressed to; a
 * document addressed to a passing customer keeps the name it was printed with,
 * and its e-mail from the ticket when the cashier keyed one.
 */
@ApplicationScoped
public class InvoiceListReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(InvoiceListReport.class);

    /** The moment format of the report's date. */
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /** This node's point of sale. */
    @Inject NodeEchelonService nodeEchelonService;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "invoices";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "Liste des factures imprimées en caisse";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-03-05";
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
     * @return the columns the requirement enumerates
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("pdv", "Point de vente"),
                ReportColumn.text("number", "N° de facture ou de bon de livraison"),
                ReportColumn.text("type", "Type de document"),
                ReportColumn.text("firstName", "Prénom du client"),
                ReportColumn.text("lastName", "Nom du client"),
                ReportColumn.text("customer", "N° client"),
                ReportColumn.amount("including", "Montant de la vente TTC"),
                ReportColumn.amount("excluding", "Montant de la vente HT"),
                ReportColumn.amount("vat", "Montant TVA"),
                ReportColumn.text("ticket", "N° du ticket"),
                ReportColumn.dateTime("issuedAt", "Date de facturation / livraison"),
                ReportColumn.text("phone", "N° de téléphone"),
                ReportColumn.text("email", "Adresse électronique"));
    }

    /**
     * {@inheritDoc}
     *
     * @return what the document-type option is called on the screen
     */
    @Override
    public String optionLabel() {
        return "Documents";
    }

    /**
     * {@inheritDoc}
     *
     * @return both, the invoices, the delivery notes
     */
    @Override
    public List<String> optionChoices() {
        return List.of("ALL:Factures et bons de livraison", "FACTURE:Factures", "BON_LIVRAISON:Bons de livraison");
    }

    /**
     * {@inheritDoc}
     *
     * @return the registers and the island
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND);
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return the date of the report and the point of sale
     */
    @Override
    public List<String> preamble(ReportCriteria criteria) {
        return List.of("Rapport édité le " + MOMENT.format(LocalDateTime.now()) + " — point de vente " + pdv());
    }

    /**
     * {@inheritDoc}
     *
     * @param row the row
     * @return the duplicate of the row's document
     */
    @Override
    public String rowLink(List<Object> row) {
        return "/admin/reports/invoices/duplicate?number=" + URLEncoder.encode(String.valueOf(row.get(1)),
                StandardCharsets.UTF_8);
    }

    /**
     * {@inheritDoc}
     *
     * @return the label of the reprint
     */
    @Override
    public String rowLinkLabel() {
        return "Réimprimer (duplicata)";
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per document, oldest first
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        String option = criteria.optionOr("ALL");
        DocumentType type = "FACTURE".equals(option) ? DocumentType.FACTURE
                : "BON_LIVRAISON".equals(option) ? DocumentType.BON_LIVRAISON : null;
        var query = entityManager.createQuery(
                        "select i from Invoice i where i.issueDate >= :from and i.issueDate < :to"
                                + (type == null ? "" : " and i.documentType = :type")
                                + criteria.terminalFilter("i.terminalId") + " order by i.issueDate", Invoice.class)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        if (type != null) {
            query.setParameter("type", type);
        }
        criteria.bindTerminals(query);
        String pdv = pdv();
        List<List<Object>> rows = new ArrayList<>();
        for (Invoice invoice : query.getResultList()) {
            AccountCustomer customer = invoice.customer;
            String lastName = customer != null && customer.lastName != null ? customer.lastName : invoice.customerName;
            String email = customer != null && customer.email != null ? customer.email
                    : invoice.ticket == null ? null : invoice.ticket.customerEmail;
            rows.add(Arrays.asList(
                    pdv,
                    invoice.documentNumber,
                    invoice.documentType == null ? "" : invoice.documentType.getTitle(),
                    customer == null || customer.firstName == null ? "" : customer.firstName,
                    lastName == null ? "" : lastName,
                    invoice.customerAccountNumber == null ? "" : invoice.customerAccountNumber,
                    invoice.totalIncludingTax,
                    invoice.totalExcludingTax,
                    invoice.totalVat,
                    invoice.ticketNumber,
                    invoice.issueDate,
                    customer == null || customer.phone == null ? "" : customer.phone,
                    email == null ? "" : email));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * The point of sale of this node.
     *
     * @return the PDV code, an empty text when unknown
     */
    private String pdv() {
        String pdv = nodeEchelonService == null ? null : nodeEchelonService.currentPdv();
        return pdv == null ? "" : pdv;
    }
}
