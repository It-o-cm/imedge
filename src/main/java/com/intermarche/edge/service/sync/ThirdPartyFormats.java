package com.intermarche.edge.service.sync;

import com.intermarche.edge.domain.payment.CardPayment;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.sale.RefundLine;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.sale.VatBreakdown;
import com.intermarche.edge.service.export.FlatRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The THIRD-PARTY SHAPES of the at-the-flow stream: what a consolidated sale
 * becomes for a receiver that speaks its own protocol rather than the house
 * JSON (BO-11-01-07, BO-11-02-01, BO-11-02-04, BO-11-04-02).
 *
 * <p>Pure functions of a ticket, gathered in one class because they share the
 * one rule that matters: a receiver is told what was SOLD. Cancelled lines are
 * left out of every shape that counts sales and kept, flagged, in the one that
 * watches the lane (the video journal), where a cancellation is precisely what
 * the operator wants to see.
 *
 * <p>The field names follow each protocol's published vocabulary as far as this
 * repository knows it — Catalina's POS web-service transaction, the TFREMA cash
 * event, the DS unit-sale record, the tax administration's RegisterInvoice —
 * and are the house reading of the interface documents, to be checked against
 * them before a real exchange, exactly as the flat files of the outgoing flows.
 */
public final class ThirdPartyFormats {

    /** The wire shape of an instant, to the second. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** The tax type every Portuguese line declares. */
    private static final String VAT_TAX_TYPE = "IVA";

    /** The tax number of a customer who gave none (the final consumer). */
    private static final String FINAL_CONSUMER = "999999990";

    /** The namespace of the tax administration's invoice communication service. */
    static final String AT_NAMESPACE = "http://servicos.portaldasfinancas.gov.pt/faturas/";

    /**
     * Not instantiable: a holder of pure functions.
     */
    private ThirdPartyFormats() {
    }

    /**
     * Builds the Catalina transaction of a closed sale (BO-11-01-07): the store,
     * the lane, the transaction, the loyalty card, each sold item with its UPC,
     * department and prices, and each tender.
     *
     * @param ticket the consolidated ticket
     * @param pdv the point of sale number of this node
     * @return the Catalina payload
     */
    public static StreamPayloads.CatalinaTransaction catalina(Ticket ticket, String pdv) {
        StreamPayloads.CatalinaTransaction transaction = new StreamPayloads.CatalinaTransaction();
        transaction.storeId = pdv;
        transaction.laneId = ticket.terminalId;
        transaction.transactionId = ticket.ticketNumber;
        transaction.transactionDateTime = stamp(ticket.closingDate);
        transaction.cashierId = ticket.cashier == null ? null : ticket.cashier.loginName;
        transaction.loyaltyCardNumber = ticket.fidelityCard;
        transaction.totalAmount = money(ticket.totalIncludingTax);
        transaction.taxAmount = money(ticket.totalVat);
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) {
                continue;
            }
            StreamPayloads.CatalinaItem item = new StreamPayloads.CatalinaItem();
            item.upc = line.ean;
            item.description = line.productLabel;
            item.departmentCode = line.familyCode;
            item.quantity = line.quantity;
            item.unitPrice = money(line.unitPrice);
            item.extendedPrice = money(line.totalPrice);
            item.discountAmount = discount(line);
            transaction.items.add(item);
        }
        for (TicketPayment payment : ticket.payments) {
            StreamPayloads.CatalinaTender tender = new StreamPayloads.CatalinaTender();
            tender.tenderType = payment.getMethodKey();
            tender.amount = money(payment.amount);
            tender.authorizationCode = authorisationOf(payment);
            transaction.tenders.add(tender);
        }
        return transaction;
    }

    /**
     * Builds the TFREMA cash event of a closed sale (BO-11-02-01): one event per
     * transaction, carrying each article sold with its quantity and the instant
     * of the sale, what the centralised gestion commerciale moves its stock by.
     *
     * @param ticket the consolidated ticket
     * @param pdv the point of sale number of this node
     * @return the TFREMA payload
     */
    public static StreamPayloads.TfremaEvent tfrema(Ticket ticket, String pdv) {
        StreamPayloads.TfremaEvent event = new StreamPayloads.TfremaEvent();
        event.eventType = "VENTE";
        event.pdv = pdv;
        event.caisse = ticket.terminalId;
        event.transaction = ticket.ticketNumber;
        event.dateHeure = stamp(ticket.closingDate);
        event.montantTtc = money(ticket.totalIncludingTax);
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) {
                continue;
            }
            StreamPayloads.TfremaLine sold = new StreamPayloads.TfremaLine();
            sold.article = line.ean;
            sold.libelle = line.productLabel;
            sold.famille = line.familyCode;
            sold.quantite = line.returnLine && line.quantity != null ? line.quantity.negate() : line.quantity;
            sold.montantTtc = money(line.totalPrice);
            event.lignes.add(sold);
        }
        return event;
    }

    /**
     * Builds the DS unit-sale records of a closed sale (BO-11-02-01): one
     * positioned-field record per article sold — record type, point of sale,
     * register, ticket, instant, EAN, signed quantity in thousandths, amount in
     * cents — the {@code .dat} shape a local gestion commerciale loads.
     *
     * @param ticket the consolidated ticket
     * @param pdv the point of sale number of this node
     * @return the records, one per line, each ended by a line break; empty when
     *     nothing was sold
     */
    public static String dsRecords(Ticket ticket, String pdv) {
        StringBuilder records = new StringBuilder();
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) {
                continue;
            }
            BigDecimal quantity = line.quantity == null ? BigDecimal.ZERO : line.quantity;
            if (line.returnLine) {
                quantity = quantity.negate();
            }
            records.append(new FlatRecord()
                    .text("DS", 2)
                    .text(pdv, 6)
                    .text(ticket.terminalId, 10)
                    .text(ticket.ticketNumber, 30)
                    .instant(ticket.closingDate)
                    .text(line.ean, 13)
                    .amount(quantity.movePointRight(1), 9)
                    .amount(line.totalPrice, 11)
                    .line()).append('\n');
        }
        return records.toString();
    }

    /**
     * Builds the Anaveo video journal of a ticket (BO-11-02-04): one text line
     * per event of the lane — each article, each CANCELLED article, each
     * settlement, then the total — {@code register|instant|EVENT|label|quantity|amount},
     * the text a video-surveillance system overlays on the images of the lane.
     *
     * @param ticket the consolidated ticket
     * @return the journal, one event per line
     */
    public static String anaveo(Ticket ticket) {
        StringBuilder journal = new StringBuilder();
        String moment = stamp(ticket.closingDate == null ? ticket.creationDate : ticket.closingDate);
        for (TicketLine line : ticket.lines) {
            journal.append(journalLine(ticket.terminalId, moment, line.cancelled ? "ANNULATION" : "ARTICLE",
                    line.productLabel, plain(line.quantity), plain(line.totalPrice)));
        }
        for (TicketPayment payment : ticket.payments) {
            journal.append(journalLine(ticket.terminalId, moment, "REGLEMENT", payment.getMethodKey(),
                    "", plain(payment.amount)));
        }
        String status = ticket.status == null ? "" : ticket.status.name();
        journal.append(journalLine(ticket.terminalId, moment, "TOTAL", status, String.valueOf(ticket.itemCount),
                plain(ticket.totalIncludingTax)));
        return journal.toString();
    }

    /**
     * Builds one line of the video journal, the separator removed from every
     * value so a label cannot shift the columns.
     *
     * @param terminal the register
     * @param moment the instant
     * @param event the event code
     * @param label the label, possibly null
     * @param quantity the quantity text
     * @param amount the amount text
     * @return the line, ended by a line break
     */
    private static String journalLine(String terminal, String moment, String event, String label,
            String quantity, String amount) {
        return String.join("|", clean(terminal), clean(moment), event, clean(label), quantity, amount) + "\n";
    }

    /**
     * Ventilates by VAT rate the ticket lines a fiscal document covers: all of
     * them for a whole sale, the listed ones for a part, cancelled lines never.
     *
     * @param document the fiscal document
     * @param lines the lines of the sale it states
     * @return the breakdown of the covered lines
     */
    public static VatBreakdown ticketBreakdown(FiscalDocument document, List<TicketLine> lines) {
        Set<String> covered = document.lineUids == null || document.lineUids.isBlank()
                ? null : new HashSet<>(Arrays.asList(document.lineUids.split(",")));
        VatBreakdown breakdown = new VatBreakdown();
        for (TicketLine line : lines) {
            if (line.cancelled || (covered != null && !covered.contains(line.lineUid))) {
                continue;
            }
            breakdown.add(line.vatRate, line.totalPrice);
        }
        return breakdown;
    }

    /**
     * Ventilates by VAT rate the lines of a refund: each refunded quantity at
     * the unit price the customer actually paid.
     *
     * @param lines the refund lines
     * @return the breakdown of the refunded amounts
     */
    public static VatBreakdown refundBreakdown(List<RefundLine> lines) {
        VatBreakdown breakdown = new VatBreakdown();
        for (RefundLine line : lines) {
            BigDecimal quantity = line.quantity == null ? BigDecimal.ZERO : line.quantity;
            BigDecimal price = line.price == null ? BigDecimal.ZERO : line.price;
            breakdown.add(line.vatRate, quantity.multiply(price).setScale(2, RoundingMode.HALF_UP));
        }
        return breakdown;
    }

    /**
     * Builds the RegisterInvoice message of one fiscal document (BO-11-04-02):
     * the issuer's tax number, the document number, date, type and status, the
     * customer's tax number, one line per VAT rate — net amount, debit for a
     * sale and credit for a credit note — and the document totals.
     *
     * <p>The credential of the receiver is NOT in the message: it travels with
     * the call and never rests in a stored payload.
     *
     * @param document the fiscal document
     * @param breakdown the VAT ventilation of what the document states
     * @param issuerTaxId the issuer's tax number
     * @return the SOAP envelope
     */
    public static String registerInvoice(FiscalDocument document, VatBreakdown breakdown, String issuerTaxId) {
        boolean credit = document.documentType == FiscalDocument.Type.NC;
        String customer = document.customerTaxId == null || document.customerTaxId.isBlank()
                ? FINAL_CONSUMER : document.customerTaxId.trim();
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<S:Envelope xmlns:S=\"http://schemas.xmlsoap.org/soap/envelope/\"><S:Body>")
                .append("<ns2:RegisterInvoiceElem xmlns:ns2=\"").append(AT_NAMESPACE).append("\">")
                .append(element("TaxRegistrationNumber", issuerTaxId))
                .append(element("ns2:InvoiceNo", document.number))
                .append(element("ns2:InvoiceDate", document.issuedAt == null ? ""
                        : document.issuedAt.toLocalDate().toString()))
                .append(element("ns2:InvoiceType", document.documentType == null ? ""
                        : document.documentType.name()))
                .append(element("ns2:InvoiceStatus", document.cancelled ? "A" : "N"))
                .append(element("CustomerTaxID", customer))
                .append(element("CustomerTaxIDCountry", "PT"));
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            xml.append("<Line>")
                    .append(element(credit ? "ns2:CreditAmount" : "ns2:DebitAmount", plain(bucket.totalExcludingTax)))
                    .append("<ns2:Tax>")
                    .append(element("ns2:TaxType", VAT_TAX_TYPE))
                    .append(element("ns2:TaxCountryRegion", "PT"))
                    .append(element("ns2:TaxPercentage", percent(bucket.rate)))
                    .append("</ns2:Tax></Line>");
        }
        BigDecimal gross = document.grossTotal == null ? breakdown.getTotalIncludingTax() : document.grossTotal;
        BigDecimal tax = document.taxTotal == null ? breakdown.getTotalVat() : document.taxTotal;
        xml.append("<DocumentTotals>")
                .append(element("ns2:TaxPayable", plain(tax)))
                .append(element("ns2:NetTotal", plain(gross.subtract(tax))))
                .append(element("ns2:GrossTotal", plain(gross)))
                .append("</DocumentTotals>")
                .append("</ns2:RegisterInvoiceElem></S:Body></S:Envelope>");
        return xml.toString();
    }

    /**
     * Writes one XML element, its text escaped.
     *
     * @param name the element name
     * @param text the text, possibly null — then an empty element
     * @return the element
     */
    static String element(String name, String text) {
        return "<" + name + ">" + escape(text) + "</" + name + ">";
    }

    /**
     * Escapes the five XML special characters of a text.
     *
     * @param text the text, possibly null
     * @return the escaped text, empty for null
     */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /**
     * Writes a VAT rate held as a fraction as the percentage the tax
     * administration reads: {@code 0.2300} becomes {@code 23}.
     *
     * @param rate the rate as a fraction, possibly null
     * @return the percentage, without trailing zeros
     */
    static String percent(BigDecimal rate) {
        BigDecimal value = rate == null ? BigDecimal.ZERO : rate.movePointRight(2);
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * Writes an amount with two decimals and a point, or nothing when absent.
     *
     * @param value the amount, possibly null
     * @return the amount text, empty for null
     */
    static String plain(BigDecimal value) {
        BigDecimal amount = money(value);
        return amount == null ? "" : amount.toPlainString();
    }

    /**
     * Rounds an amount to the cent for the wire: the node keeps four decimals,
     * a receiver reads euros and cents.
     *
     * @param value the amount, possibly null
     * @return the amount to two decimals, or null when absent
     */
    static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Replaces the journal separator and the line breaks of a value.
     *
     * @param value the value, possibly null
     * @return the cleaned value, empty for null
     */
    private static String clean(String value) {
        return value == null ? "" : value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * The discount a line carries: the gap between its original and its charged
     * unit price times its quantity, or null when it carries none.
     *
     * @param line the line
     * @return the discount, or null
     */
    private static BigDecimal discount(TicketLine line) {
        if (line.originalUnitPrice == null || line.unitPrice == null || line.quantity == null
                || line.originalUnitPrice.compareTo(line.unitPrice) <= 0) {
            return null;
        }
        return line.originalUnitPrice.subtract(line.unitPrice).multiply(line.quantity)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Renders an instant for the wire, ISO-8601 to the second, or null when
     * absent: always {@code yyyy-MM-ddTHH:mm:ss}, never the minute alone nor
     * the fraction, so every receiver reads one fixed shape.
     *
     * @param value the instant, possibly null
     * @return the rendered instant, or null
     */
    private static String stamp(LocalDateTime value) {
        return value == null ? null : value.format(STAMP);
    }

    /**
     * Returns the card authorisation of a settlement, for the receivers that
     * reconcile it, or null when the settlement is not a card one.
     *
     * @param payment the settlement
     * @return the authorisation number, or null
     */
    static String authorisationOf(TicketPayment payment) {
        return payment instanceof CardPayment card ? card.authorizationNumber : null;
    }
}
