package com.intermarche.edge.service.sync;

import com.intermarche.edge.domain.payment.CardPayment;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.sale.RefundLine;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.sale.VatBreakdown;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ThirdPartyFormats} (BO-11-01-07, BO-11-02-01,
 * BO-11-02-04, BO-11-04-02), targeting 100% branch coverage.
 *
 * <p>Branch enumeration. {@code catalina}: a cashier or none, a cancelled line
 * left out, a card tender carrying its authorisation and another one without.
 * {@code discount}: each of the four absent operands, a price forced up and a
 * real discount. {@code tfrema}: a cancelled line, a returned line with and
 * without a quantity, a sold line. {@code dsRecords}: a cancelled line, a line
 * with no quantity, a returned line. {@code anaveo}: a ticket with and without
 * a closing instant, with and without a status, a cancelled line, a label
 * carrying the separator and a null one. {@code ticketBreakdown}: no list,
 * a blank list, a listed and an unlisted line, a cancelled one.
 * {@code refundBreakdown}: absent quantity and price. {@code registerInvoice}:
 * a sale and a credit note, the customer absent, blank and given, the issue
 * instant and the type absent, a cancelled document, the totals stated by the
 * document or taken from the breakdown. The helpers: null texts, null rates,
 * null amounts.
 */
class ThirdPartyFormatsTest {

    /** The instant every sale below is closed at. */
    private static final LocalDateTime CLOSED = LocalDateTime.of(2026, 10, 25, 10, 15);

    /**
     * Builds a sold line.
     *
     * @param uid the line uid
     * @param ean the EAN
     * @param quantity the quantity, possibly null
     * @param total the line total, possibly null
     * @return the line
     */
    private TicketLine line(String uid, String ean, String quantity, String total) {
        TicketLine line = new TicketLine();
        line.lineUid = uid;
        line.ean = ean;
        line.productLabel = "CAFE|MOULU";
        line.familyCode = "100";
        line.quantity = quantity == null ? null : new BigDecimal(quantity);
        line.unitPrice = new BigDecimal("3.00");
        line.totalPrice = total == null ? null : new BigDecimal(total);
        line.vatRate = new BigDecimal("0.2000");
        return line;
    }

    /**
     * Builds a closed ticket.
     *
     * @param lines its lines
     * @return the ticket
     */
    private Ticket ticket(TicketLine... lines) {
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C04-000001";
        ticket.terminalId = "C04";
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.creationDate = CLOSED.minusMinutes(5);
        ticket.closingDate = CLOSED;
        ticket.itemCount = 2;
        ticket.totalIncludingTax = new BigDecimal("6.00");
        ticket.totalVat = new BigDecimal("1.00");
        ticket.fidelityCard = "2990000000001";
        ticket.lines.addAll(List.of(lines));
        return ticket;
    }

    /**
     * Builds a settlement of a method.
     *
     * @param method the method key
     * @param amount the amount
     * @return the settlement
     */
    private TicketPayment payment(String method, String amount) {
        TicketPayment payment = mock(TicketPayment.class);
        when(payment.getMethodKey()).thenReturn(method);
        payment.amount = new BigDecimal(amount);
        return payment;
    }

    /**
     * The Catalina transaction carries the store, the lane, the card, the sold
     * items with their discount and the tenders with the card authorisation,
     * every amount to the cent.
     */
    @Test
    void catalinaCarriesTheSoldItemsAndTheTenders() {
        TicketLine sold = line("u1", "3250390000001", "2", "6.00");
        sold.originalUnitPrice = new BigDecimal("3.50");
        TicketLine cancelled = line("u2", "3250390000002", "1", "1.00");
        cancelled.cancelled = true;
        Ticket ticket = ticket(sold, cancelled);
        Employee cashier = new Employee();
        cashier.loginName = "jdupont";
        ticket.cashier = cashier;
        CardPayment card = new CardPayment(new BigDecimal("4.00"));
        card.authorizationNumber = "A123";
        ticket.payments.add(card);
        ticket.payments.add(payment("CASH", "2.0000"));
        ticket.totalIncludingTax = new BigDecimal("6.0000");
        ticket.totalVat = new BigDecimal("1.0000");
        sold.unitPrice = new BigDecimal("3.0000");
        sold.totalPrice = new BigDecimal("6.0000");
        StreamPayloads.CatalinaTransaction transaction = ThirdPartyFormats.catalina(ticket, "10001");
        Assertions.assertEquals("10001", transaction.storeId);
        Assertions.assertEquals("C04", transaction.laneId);
        Assertions.assertEquals("C04-000001", transaction.transactionId);
        Assertions.assertEquals("2026-10-25T10:15:00", transaction.transactionDateTime);
        Assertions.assertEquals("jdupont", transaction.cashierId);
        Assertions.assertEquals("2990000000001", transaction.loyaltyCardNumber);
        Assertions.assertEquals(1, transaction.items.size());
        Assertions.assertEquals("3250390000001", transaction.items.get(0).upc);
        Assertions.assertEquals("100", transaction.items.get(0).departmentCode);
        Assertions.assertEquals(new BigDecimal("1.00"), transaction.items.get(0).discountAmount);
        Assertions.assertEquals(2, transaction.tenders.size());
        Assertions.assertEquals("CARD", transaction.tenders.get(0).tenderType);
        Assertions.assertEquals("A123", transaction.tenders.get(0).authorizationCode);
        Assertions.assertNull(transaction.tenders.get(1).authorizationCode);
        Assertions.assertEquals(new BigDecimal("6.00"), transaction.totalAmount);
        Assertions.assertEquals(new BigDecimal("1.00"), transaction.taxAmount);
        Assertions.assertEquals(new BigDecimal("3.00"), transaction.items.get(0).unitPrice);
        Assertions.assertEquals(new BigDecimal("6.00"), transaction.items.get(0).extendedPrice);
        Assertions.assertEquals(new BigDecimal("2.00"), transaction.tenders.get(1).amount);
        Assertions.assertNull(ThirdPartyFormats.money(null));
    }

    /**
     * A sale with no cashier and no closing instant still makes a transaction,
     * those fields left empty; a line states a discount only when it has every
     * operand and its price went down.
     */
    @Test
    void catalinaSurvivesTheBlanksAndStatesOnlyRealDiscounts() {
        TicketLine noOriginal = line("u1", "A", "1", "3.00");
        TicketLine noPrice = line("u2", "B", "1", "3.00");
        noPrice.originalUnitPrice = new BigDecimal("4.00");
        noPrice.unitPrice = null;
        TicketLine noQuantity = line("u3", "C", null, "3.00");
        noQuantity.originalUnitPrice = new BigDecimal("4.00");
        TicketLine forcedUp = line("u4", "D", "1", "3.00");
        forcedUp.originalUnitPrice = new BigDecimal("2.00");
        Ticket ticket = ticket(noOriginal, noPrice, noQuantity, forcedUp);
        ticket.closingDate = null;
        StreamPayloads.CatalinaTransaction transaction = ThirdPartyFormats.catalina(ticket, "");
        Assertions.assertNull(transaction.cashierId);
        Assertions.assertNull(transaction.transactionDateTime);
        for (StreamPayloads.CatalinaItem item : transaction.items) {
            Assertions.assertNull(item.discountAmount);
        }
    }

    /**
     * The TFREMA event lists the sold articles, a returned one negative, a
     * returned one with no quantity kept without one, a cancelled one left out.
     */
    @Test
    void tfremaListsTheArticlesSold() {
        TicketLine sold = line("u1", "A", "2", "6.00");
        TicketLine returned = line("u2", "B", "1", "-3.00");
        returned.returnLine = true;
        TicketLine returnedBlank = line("u3", "C", null, "-3.00");
        returnedBlank.returnLine = true;
        TicketLine cancelled = line("u4", "D", "1", "3.00");
        cancelled.cancelled = true;
        StreamPayloads.TfremaEvent event = ThirdPartyFormats.tfrema(ticket(sold, returned, returnedBlank, cancelled),
                "10001");
        Assertions.assertEquals("VENTE", event.eventType);
        Assertions.assertEquals("10001", event.pdv);
        Assertions.assertEquals("C04", event.caisse);
        Assertions.assertEquals("2026-10-25T10:15:00", event.dateHeure);
        Assertions.assertEquals(3, event.lignes.size());
        Assertions.assertEquals(new BigDecimal("2"), event.lignes.get(0).quantite);
        Assertions.assertEquals(new BigDecimal("-1"), event.lignes.get(1).quantite);
        Assertions.assertNull(event.lignes.get(2).quantite);
        Assertions.assertEquals(new BigDecimal("6.00"), event.montantTtc);
        Assertions.assertEquals(new BigDecimal("-3.00"), event.lignes.get(1).montantTtc);
    }

    /**
     * The DS records are one positioned record per article sold: record type,
     * point of sale, register, ticket, instant, EAN, quantity in thousandths,
     * amount in cents — a returned article negative, an absent quantity zero.
     */
    @Test
    void dsRecordsAreOnePositionedRecordPerArticle() {
        TicketLine sold = line("u1", "3250390000001", "1.5", "4.50");
        TicketLine returned = line("u2", "3250390000002", "1", "-3.00");
        returned.returnLine = true;
        TicketLine blank = line("u3", "3250390000003", null, null);
        TicketLine cancelled = line("u4", "3250390000004", "1", "3.00");
        cancelled.cancelled = true;
        String records = ThirdPartyFormats.dsRecords(ticket(sold, returned, blank, cancelled), "10001");
        String[] lines = records.split("\n");
        Assertions.assertEquals(3, lines.length);
        Assertions.assertTrue(lines[0].startsWith("DS10001 C04       C04-000001"));
        Assertions.assertTrue(lines[0].contains("202610251015" + "3250390000001" + "000001500" + "00000000450"));
        Assertions.assertTrue(lines[1].contains("-00001000-0000000300"));
        Assertions.assertTrue(lines[2].endsWith("00000000000000000000"));
    }

    /**
     * The Anaveo journal states every article, the cancelled ones as such, the
     * settlements and the total, the separator removed from the labels.
     */
    @Test
    void anaveoJournalsEveryEventOfTheLane() {
        TicketLine sold = line("u1", "A", "1", "3.00");
        TicketLine cancelled = line("u2", "B", "1", "3.00");
        cancelled.cancelled = true;
        Ticket ticket = ticket(sold, cancelled);
        ticket.payments.add(payment("CASH", "3.00"));
        String journal = ThirdPartyFormats.anaveo(ticket);
        Assertions.assertEquals("C04|2026-10-25T10:15:00|ARTICLE|CAFE MOULU|1.00|3.00\n"
                + "C04|2026-10-25T10:15:00|ANNULATION|CAFE MOULU|1.00|3.00\n"
                + "C04|2026-10-25T10:15:00|REGLEMENT|CASH||3.00\n"
                + "C04|2026-10-25T10:15:00|TOTAL|CLOSED|2|6.00\n", journal);
    }

    /**
     * A cart still open is journalled at its creation instant, with no status,
     * no label and no amounts left as empty columns.
     */
    @Test
    void anaveoJournalsAnOpenCartWithItsBlanks() {
        TicketLine blank = line("u1", "A", null, null);
        blank.productLabel = null;
        Ticket ticket = ticket(blank);
        ticket.closingDate = null;
        ticket.status = null;
        ticket.terminalId = null;
        ticket.totalIncludingTax = null;
        String journal = ThirdPartyFormats.anaveo(ticket);
        Assertions.assertEquals("|2026-10-25T10:10:00|ARTICLE|||\n|2026-10-25T10:10:00|TOTAL||2|\n", journal);
    }

    /**
     * A whole-sale document covers every line but the cancelled ones; a blank
     * list reads as a whole sale; a part covers only its listed lines.
     */
    @Test
    void theTicketBreakdownFollowsTheLinesTheDocumentCovers() {
        TicketLine first = line("u1", "A", "1", "12.00");
        TicketLine second = line("u2", "B", "1", "24.00");
        TicketLine cancelled = line("u3", "C", "1", "6.00");
        cancelled.cancelled = true;
        List<TicketLine> lines = List.of(first, second, cancelled);
        FiscalDocument whole = new FiscalDocument();
        Assertions.assertEquals(new BigDecimal("36.00"),
                ThirdPartyFormats.ticketBreakdown(whole, lines).getTotalIncludingTax());
        whole.lineUids = " ";
        Assertions.assertEquals(new BigDecimal("36.00"),
                ThirdPartyFormats.ticketBreakdown(whole, lines).getTotalIncludingTax());
        FiscalDocument part = new FiscalDocument();
        part.lineUids = "u2,u3";
        Assertions.assertEquals(new BigDecimal("24.00"),
                ThirdPartyFormats.ticketBreakdown(part, lines).getTotalIncludingTax());
    }

    /**
     * A refund ventilates each refunded quantity at the price paid; an absent
     * quantity or price counts nothing.
     */
    @Test
    void theRefundBreakdownWeighsQuantityAndPrice() {
        RefundLine refunded = new RefundLine();
        refunded.quantity = new BigDecimal("2");
        refunded.price = new BigDecimal("6.00");
        refunded.vatRate = new BigDecimal("0.2000");
        RefundLine noQuantity = new RefundLine();
        noQuantity.price = new BigDecimal("5.00");
        RefundLine noPrice = new RefundLine();
        noPrice.quantity = BigDecimal.ONE;
        VatBreakdown breakdown = ThirdPartyFormats.refundBreakdown(List.of(refunded, noQuantity, noPrice));
        Assertions.assertEquals(new BigDecimal("12.00"), breakdown.getTotalIncludingTax());
    }

    /**
     * A simplified invoice is communicated with the issuer, the number, the
     * date, the type, a normal status, the final consumer, one debit line per
     * rate and the totals the document states.
     */
    @Test
    void registerInvoiceStatesASale() {
        FiscalDocument document = new FiscalDocument();
        document.documentType = FiscalDocument.Type.FS;
        document.number = "FS C04/1";
        document.issuedAt = CLOSED;
        document.grossTotal = new BigDecimal("12.30");
        document.taxTotal = new BigDecimal("2.30");
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(new BigDecimal("0.2300"), new BigDecimal("12.30"));
        String xml = ThirdPartyFormats.registerInvoice(document, breakdown, "501234567");
        Assertions.assertTrue(xml.contains("<ns2:RegisterInvoiceElem xmlns:ns2=\""
                + ThirdPartyFormats.AT_NAMESPACE + "\">"));
        Assertions.assertTrue(xml.contains("<TaxRegistrationNumber>501234567</TaxRegistrationNumber>"));
        Assertions.assertTrue(xml.contains("<ns2:InvoiceNo>FS C04/1</ns2:InvoiceNo>"));
        Assertions.assertTrue(xml.contains("<ns2:InvoiceDate>2026-10-25</ns2:InvoiceDate>"));
        Assertions.assertTrue(xml.contains("<ns2:InvoiceType>FS</ns2:InvoiceType>"));
        Assertions.assertTrue(xml.contains("<ns2:InvoiceStatus>N</ns2:InvoiceStatus>"));
        Assertions.assertTrue(xml.contains("<CustomerTaxID>999999990</CustomerTaxID>"));
        Assertions.assertTrue(xml.contains("<ns2:DebitAmount>10.00</ns2:DebitAmount>"));
        Assertions.assertTrue(xml.contains("<ns2:TaxPercentage>23</ns2:TaxPercentage>"));
        Assertions.assertTrue(xml.contains("<ns2:TaxPayable>2.30</ns2:TaxPayable>"));
        Assertions.assertTrue(xml.contains("<ns2:NetTotal>10.00</ns2:NetTotal>"));
        Assertions.assertTrue(xml.contains("<ns2:GrossTotal>12.30</ns2:GrossTotal>"));
    }

    /**
     * A cancelled credit note states credit lines, its customer, and totals
     * taken from the breakdown when it states none; a blank customer is the
     * final consumer; an undated, untyped document leaves those elements empty.
     */
    @Test
    void registerInvoiceStatesACreditNoteAndTheBlanks() {
        FiscalDocument credit = new FiscalDocument();
        credit.documentType = FiscalDocument.Type.NC;
        credit.number = "NC C04/1";
        credit.cancelled = true;
        credit.customerTaxId = " 501234567 ";
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(null, new BigDecimal("5.00"));
        String xml = ThirdPartyFormats.registerInvoice(credit, breakdown, "5&<>\"'");
        Assertions.assertTrue(xml.contains("<ns2:CreditAmount>5.00</ns2:CreditAmount>"));
        Assertions.assertTrue(xml.contains("<ns2:InvoiceStatus>A</ns2:InvoiceStatus>"));
        Assertions.assertTrue(xml.contains("<CustomerTaxID>501234567</CustomerTaxID>"));
        Assertions.assertTrue(xml.contains("<ns2:InvoiceDate></ns2:InvoiceDate>"));
        Assertions.assertTrue(xml.contains("<ns2:TaxPercentage>0</ns2:TaxPercentage>"));
        Assertions.assertTrue(xml.contains("<ns2:GrossTotal>5.00</ns2:GrossTotal>"));
        Assertions.assertTrue(xml.contains("5&amp;&lt;&gt;&quot;&apos;"));
        FiscalDocument blank = new FiscalDocument();
        blank.customerTaxId = " ";
        String untyped = ThirdPartyFormats.registerInvoice(blank, new VatBreakdown(), null);
        Assertions.assertTrue(untyped.contains("<ns2:InvoiceType></ns2:InvoiceType>"));
        Assertions.assertTrue(untyped.contains("<CustomerTaxID>999999990</CustomerTaxID>"));
        Assertions.assertTrue(untyped.contains("<TaxRegistrationNumber></TaxRegistrationNumber>"));
    }

    /** The helpers write nothing for an absent value. */
    @Test
    void theHelpersTolerateAbsentValues() {
        Assertions.assertEquals("", ThirdPartyFormats.escape(null));
        Assertions.assertEquals("<a></a>", ThirdPartyFormats.element("a", null));
        Assertions.assertEquals("0", ThirdPartyFormats.percent(null));
        Assertions.assertEquals("6", ThirdPartyFormats.percent(new BigDecimal("0.0600")));
        Assertions.assertEquals("", ThirdPartyFormats.plain(null));
        Assertions.assertNull(ThirdPartyFormats.authorisationOf(payment("CASH", "1.00")));
    }
}
