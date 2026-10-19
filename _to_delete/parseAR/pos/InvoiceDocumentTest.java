package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.domain.payment.AccountCustomer;
import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.domain.payment.CardPayment;
import com.intermarche.pos.domain.payment.CashPayment;
import com.intermarche.pos.domain.payment.ChequePayment;
import com.intermarche.pos.domain.payment.CreditPayment;
import com.intermarche.pos.domain.sale.DocumentType;
import com.intermarche.pos.domain.payment.FidelityPayment;
import com.intermarche.pos.domain.sale.Invoice;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.payment.TicketRestoPayment;
import com.intermarche.pos.domain.payment.VoucherPayment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link InvoiceDocument}.
 * <p>
 * The class is a pure layout function over entities that are built by hand here — no
 * CDI, no database, no Panache call — which is exactly why the whole document can be
 * asserted. Every guard is exercised on both arms and every leg of the compound ones:
 * the three legs of the legal footer, the three of an address, the two of a town
 * line, the three quantity shapes and the cancelled-line exclusion.
 */
class InvoiceDocumentTest {

    /**
     * Builds a ticket line.
     *
     * @param label    the article label
     * @param ean      the article code, null when it has none
     * @param quantity the quantity
     * @param unit     the tax-included unit price
     * @param total    the tax-included line total
     * @param rate     the VAT rate as a fraction, null for none
     * @param plu      the PLU, non-null for a weighed article
     * @param family   the family label, null when the line carries none
     * @return the line
     */
    private TicketLine line(String label, String ean, String quantity, String unit, String total,
            String rate, String plu, String family) {
        TicketLine line = new TicketLine();
        line.productLabel = label;
        line.ean = ean;
        line.plu = plu;
        line.familyLabel = family;
        line.quantity = new BigDecimal(quantity);
        line.unitPrice = new BigDecimal(unit);
        line.totalPrice = new BigDecimal(total);
        line.vatRate = rate == null ? null : new BigDecimal(rate);
        return line;
    }

    /**
     * Builds a store carrying everything a document can print of a seller.
     *
     * @return the store
     */
    private Store fullStore() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.legalName = "SA JANSELIN";
        store.rcs = "342 490 562";
        store.shareCapital = new BigDecimal("40000");
        store.siret = "SIRET1";
        store.vatNumber = "FR1";
        store.phone = "0102030405";
        store.fax = "0102030406";
        store.address = new Address();
        store.address.streetLine1 = "12 rue A";
        store.address.streetLine2 = "Bat B";
        store.address.postalCode = "92420";
        store.address.city = "VAUCRESSON";
        return store;
    }

    /**
     * Builds a customer carrying everything a document can print of an addressee.
     *
     * @return the customer
     */
    private AccountCustomer fullCustomer() {
        AccountCustomer customer = new AccountCustomer();
        customer.accountNumber = "C04-CLI000042";
        customer.companyName = "BOULANGERIE";
        customer.firstName = "Marc";
        customer.lastName = "VIDAL";
        customer.siret = "SIRET2";
        customer.vatNumber = "FR2";
        customer.address = new Address();
        customer.address.streetLine1 = "3 place";
        customer.address.postalCode = "92420";
        customer.address.city = "VAUCRESSON";
        return customer;
    }

    /**
     * Builds a document over a ticket and a customer.
     *
     * @param ticket   the ticket
     * @param customer the addressee
     * @param number   the document number
     * @return the transient invoice
     */
    private Invoice invoice(Ticket ticket, AccountCustomer customer, String number) {
        Invoice document = new Invoice();
        document.documentNumber = number;
        document.documentType = DocumentType.FACTURE;
        document.terminalId = "C04";
        document.issueDate = LocalDateTime.of(2026, 9, 9, 16, 42);
        document.ticket = ticket;
        document.ticketNumber = ticket.ticketNumber;
        document.addressTo(customer);
        document.totalExcludingTax = new BigDecimal("50.79");
        document.totalVat = new BigDecimal("5.81");
        document.totalIncludingTax = new BigDecimal("56.60");
        return document;
    }

    /**
     * A ticket with one line of each shape, one cancelled line and two tenders lays
     * out completely: the cancelled line is gone, the quantities take their three
     * shapes and the tenders are grouped in the order they first appear.
     */
    @Test
    void laysOutEveryShapeAndDropsTheCancelledLine() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "C04-00000417";
        ticket.creationDate = LocalDateTime.of(2026, 9, 9, 16, 12);
        ticket.lines.add(line("A", "3178530403022", "2", "12.90", "25.80", "0.055", null, "EPICERIE"));
        ticket.lines.add(line("B", null, "1.50", "2.00", "3.00", "0.20", null, null));
        ticket.lines.add(line("C", null, "1.480", "3.95", "5.85", "0.055", "0100", "FL"));
        TicketLine cancelled = line("ANNULE", null, "1", "9.99", "9.99", "0.20", null, null);
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        ticket.payments.add(new CardPayment(new BigDecimal("40.00")));
        ticket.payments.add(new CashPayment(new BigDecimal("16.60"), new BigDecimal("20.00")));

        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "C04-F000123"),
                ticket, true, TestMessages.french());

        assertEquals(3, document.lines.size());
        assertEquals("A", document.lines.get(0).label());
        assertEquals("2", document.lines.get(0).quantity());
        assertEquals("12,23", document.lines.get(0).unitExcludingTax());
        assertEquals("12,90", document.lines.get(0).unitIncludingTax());
        assertEquals("25,80", document.lines.get(0).totalIncludingTax());
        assertEquals("5,50 %", document.lines.get(0).vatRate());
        assertEquals("Famille : EPICERIE", document.lines.get(0).attributes());
        assertEquals("3178530403022", document.lines.get(0).ean());
        assertEquals("1,50", document.lines.get(1).quantity());
        assertEquals("", document.lines.get(1).attributes());
        assertEquals("", document.lines.get(1).ean());
        assertEquals("1,480 kg", document.lines.get(2).quantity());
        assertEquals(2, document.vatRows.size());
        assertEquals("5,50 %", document.vatRows.get(0).rate());
        assertEquals(2, document.tenders.size());
        assertEquals("Carte bancaire", document.tenders.get(0).label());
        assertEquals("40,00", document.tenders.get(0).amount());
        assertEquals("Espèces", document.tenders.get(1).label());
        assertEquals("Carte bancaire, Espèces", document.paymentMethods);
        assertEquals("SA JANSELIN — RCS 342 490 562 — Capital : 40 000,00 €", document.legalFooter);
        assertEquals("09/09/2026", document.saleDate);
        assertEquals("09/09/2026 16:42", document.issueDate);
        assertEquals("BOULANGERIE", document.customer.name());
        assertEquals("Marc VIDAL", document.customer.contact());
        assertFalse(document.isDuplicate());
    }

    /**
     * The article code is withheld when the back office says not to print it, the
     * false arm of the {@code showEan} guard.
     */
    @Test
    void withholdsTheArticleCodeWhenItIsNotPrinted() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        ticket.lines.add(line("A", "3178530403022", "1", "1.00", "1.00", "0.20", null, null));
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, false, TestMessages.french());
        assertEquals("", document.lines.get(0).ean());
    }

    /**
     * The ADMINISTERED store logo wins over the installation's file
     * ({@code BO-03-03-15}): a bare base64 payload comes back as a data URI,
     * and a payload that already is one comes back untouched.
     */
    @Test
    void carriesTheAdministeredLogo() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        InvoiceDocument bare = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, null, TestMessages.french(), InvoiceDocument.Decimals.standard(),
                "iVBORw0KGgo=");
        assertEquals("data:image/png;base64,iVBORw0KGgo=", bare.getLogo());
        InvoiceDocument uri = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, null, TestMessages.french(), InvoiceDocument.Decimals.standard(),
                "data:image/png;base64,iVBORw0KGgo=");
        assertEquals("data:image/png;base64,iVBORw0KGgo=", uri.getLogo());
        assertEquals("data:image/png;base64,iVBORw0KGgo=", uri.logoImage);
    }

    /**
     * No administered logo leaves the document on the installation's file, the
     * conduct of every call that does not name one — a blank payload falling
     * back exactly like a null one.
     */
    @Test
    void fallsBackOnTheInstallationLogo() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        InvoiceDocument absent = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, TestMessages.french());
        InvoiceDocument blank = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, null, TestMessages.french(), InvoiceDocument.Decimals.standard(), "   ");
        assertNull(absent.logoImage);
        assertEquals(com.intermarche.pos.ui.hardware.ReceiptLogo.dataUri(), absent.getLogo());
        assertEquals(absent.getLogo(), blank.getLogo());
    }

    /**
     * The same tender used twice is summed on one row rather than repeated.
     */
    @Test
    void sumsATenderUsedTwice() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        ticket.payments.add(new CashPayment(new BigDecimal("10.00"), new BigDecimal("10.00")));
        ticket.payments.add(new CashPayment(new BigDecimal("5.50"), new BigDecimal("5.50")));
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, TestMessages.french());
        assertEquals(1, document.tenders.size());
        assertEquals("15,50", document.tenders.get(0).amount());
    }

    /**
     * A line with no VAT rate at all is treated as zero-rated rather than refused,
     * the null leg of the rate guard.
     */
    @Test
    void treatsAMissingRateAsZero() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        ticket.lines.add(line("A", null, "1", "10.00", "10.00", null, null, null));
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, TestMessages.french());
        assertEquals("0,00 %", document.lines.get(0).vatRate());
        assertEquals("10,00", document.lines.get(0).unitExcludingTax());
    }

    /**
     * A store with no legal name, no register entry and no capital prints an empty
     * footer — all three legs false — and a bare address prints one line.
     */
    @Test
    void printsAnEmptyFooterAndABareAddressWhenTheStoreDeclaresNothing() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.address = new Address();
        store.address.city = "VAUCRESSON";
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "T";
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, TestMessages.french());
        assertEquals("", document.legalFooter);
        assertEquals(1, document.seller.addressLines().size());
        assertEquals("VAUCRESSON", document.seller.addressLines().get(0));
        assertEquals("", document.seller.phone());
        assertEquals("", document.seller.legalName());
        assertTrue(document.tenders.isEmpty());
        assertEquals("", document.paymentMethods);
    }

    /**
     * Each leg of the legal footer stands alone: a store declaring only its capital
     * prints only that.
     */
    @Test
    void printsTheCapitalAloneWhenItIsTheOnlyLegalFieldDeclared() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.shareCapital = new BigDecimal("1");
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "T";
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, TestMessages.french());
        assertEquals("Capital : 1,00 €", document.legalFooter);
        assertTrue(document.seller.addressLines().isEmpty());
    }

    /**
     * A store declaring only its register entry prints only that, the middle leg.
     */
    @Test
    void printsTheRegisterEntryAloneWhenItIsTheOnlyLegalFieldDeclared() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.rcs = "123";
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "T";
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, TestMessages.french());
        assertEquals("RCS 123", document.legalFooter);
    }

    /**
     * A town without a postal code prints the town alone, and a postal code without a
     * town prints the code alone — the two legs of the town line.
     */
    @Test
    void printsEitherHalfOfTheTownLineAlone() {
        Store townOnly = new Store();
        townOnly.name = "M";
        townOnly.address = new Address();
        townOnly.address.city = "VAUCRESSON";
        Ticket first = new Ticket();
        first.store = townOnly;
        first.ticketNumber = "T";
        assertEquals("VAUCRESSON", InvoiceDocument.of(invoice(first, fullCustomer(), "N"), first, true, TestMessages.french())
                .seller.addressLines().get(0));

        Store codeOnly = new Store();
        codeOnly.name = "M";
        codeOnly.address = new Address();
        codeOnly.address.postalCode = "92420";
        Ticket second = new Ticket();
        second.store = codeOnly;
        second.ticketNumber = "T";
        assertEquals("92420", InvoiceDocument.of(invoice(second, fullCustomer(), "N"), second, true, TestMessages.french())
                .seller.addressLines().get(0));
    }

    /**
     * A customer with no address at all yields no address lines, the null leg of the
     * address guard — a document is still issued.
     */
    @Test
    void survivesACustomerWithNoAddress() {
        AccountCustomer customer = new AccountCustomer();
        customer.accountNumber = "AC";
        customer.companyName = "CO";
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        Invoice document = invoice(ticket, customer, "N");
        document.customerAddress = null;
        InvoiceDocument laid = InvoiceDocument.of(document, ticket, true, TestMessages.french());
        assertTrue(laid.customer.addressLines().isEmpty());
        assertEquals("", laid.customer.contact());
        assertEquals("", laid.customer.siret());
    }

    /**
     * A printed document reports itself as a duplicate and carries its rank.
     */
    @Test
    void reportsItselfAsADuplicateOnceItHasBeenPrinted() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        Invoice document = invoice(ticket, fullCustomer(), "N");
        document.printCount = 2;
        InvoiceDocument laid = InvoiceDocument.of(document, ticket, true, TestMessages.french());
        assertTrue(laid.isDuplicate());
        assertEquals(2, laid.duplicateNumber);
    }

    /**
     * A ticket with no creation date states no sale date rather than failing, the
     * null leg of that guard.
     */
    @Test
    void statesNoSaleDateWhenTheTicketHasNone() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        ticket.creationDate = null;
        assertEquals("", InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"), ticket, true, TestMessages.french())
                .saleDate);
    }

    /**
     * The mentions a professional invoice must carry are on the document, and they
     * name the two articles they come from.
     */
    @Test
    void carriesTheLatePaymentMentions() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        String mentions = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"), ticket, true, TestMessages.french())
                .getLegalMentions();
        assertTrue(mentions.contains("L441-10"));
        assertTrue(mentions.contains("40 €"));
    }

    /**
     * Each of the first six named tenders is named as the customer reads it. The
     * loyalty tender is settled with no amount, which the document reads as zero,
     * the null arm of the amount guard.
     */
    @Test
    void namesEveryRemainingTenderAndTreatsANullAmountAsZero() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        ticket.payments.add(new ChequePayment(new BigDecimal("10.00")));
        ticket.payments.add(new TicketRestoPayment(new BigDecimal("5.00")));
        ticket.payments.add(new VoucherPayment(new BigDecimal("3.00"), null, null));
        ticket.payments.add(new FidelityPayment(null));
        ticket.payments.add(new CreditPayment(new BigDecimal("2.00")));
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, TestMessages.french());
        assertEquals(5, document.tenders.size());
        assertEquals("Chèque", document.tenders.get(0).label());
        assertEquals("10,00", document.tenders.get(0).amount());
        assertEquals("Titre-restaurant", document.tenders.get(1).label());
        assertEquals("Bon d'achat", document.tenders.get(2).label());
        assertEquals("Cagnotte fidélité", document.tenders.get(3).label());
        assertEquals("0,00", document.tenders.get(3).amount());
        assertEquals("Crédit client", document.tenders.get(4).label());
        assertEquals("Chèque, Titre-restaurant, Bon d'achat, Cagnotte fidélité, Crédit client",
                document.paymentMethods);
    }

    /**
     * THE EIGHT TENDERS THIS DOCUMENT USED TO LEAVE IN ENGLISH — one arm of the
     * switch each, on a single invoice so none is asserted on faith.
     *
     * <p>They all fell through to the default arm, which answers the Java class
     * name: a customer settling on their account read "Credit", a rounding line
     * read "Rounding" and a foreign settlement "ForeignCurrency". The loop below
     * also checks the English word is GONE from the joined
     * {@code paymentMethods}, because a mapping that adds the French label
     * without replacing the derived one would read as passing.
     *
     * <p>SOGEC IS EXCLUDED FROM THAT LOOP, and it is not an oversight: the
     * French label is "Bon Sogec" — the clearing house keeps its name, so the
     * word is legitimately on the page and its absence cannot be tested. The
     * exact label asserted above is what covers that arm.
     */
    @Test
    void namesTheEightTendersThatUsedToPrintInEnglish() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        ticket.payments.add(new CreditPayment(new BigDecimal("1.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.RoundingPayment(new BigDecimal("0.02")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.ForeignCurrencyPayment(new BigDecimal("2.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.BackupPayment(new BigDecimal("3.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.TrdCardPayment(new BigDecimal("4.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.RestoFlashPayment(new BigDecimal("5.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.SupplierGiftCardPayment(new BigDecimal("6.00")));
        ticket.payments.add(new com.intermarche.pos.domain.payment.SogecPayment(new BigDecimal("7.00")));
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, TestMessages.french());
        assertEquals(8, document.tenders.size());
        assertEquals("Crédit client", document.tenders.get(0).label());
        assertEquals("Arrondi", document.tenders.get(1).label());
        assertEquals("Devise", document.tenders.get(2).label());
        assertEquals("Secours monétique", document.tenders.get(3).label());
        assertEquals("Carte TRD", document.tenders.get(4).label());
        assertEquals("Resto Flash", document.tenders.get(5).label());
        assertEquals("Carte cadeau", document.tenders.get(6).label());
        assertEquals("Bon Sogec", document.tenders.get(7).label());
        for (String english : List.of("Credit", "Rounding", "ForeignCurrency", "Backup",
                "TrdCard", "RestoFlash", "SupplierGiftCard")) {
            assertFalse(document.paymentMethods.contains(english),
                    "le nom de classe " + english + " ne doit plus figurer sur la facture");
        }
    }

    /**
     * A settlement class this version has no word for keeps its bare type name —
     * the default arm, deliberately kept: visible on the page beats blank under
     * an amount, and it names what has to be added.
     */
    @Test
    void fallsBackToTheTypeNameForAnUnknownTender() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        ticket.payments.add(new UnknownTenderPayment(new BigDecimal("9.00")));
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, TestMessages.french());
        assertEquals(1, document.tenders.size());
        assertEquals("UnknownTender", document.tenders.get(0).label());
    }

    /**
     * A settlement method this version knows nothing about, used to exercise the
     * default arm of the tender switch.
     */
    private static class UnknownTenderPayment extends com.intermarche.pos.domain.payment.TicketPayment {

        /**
         * Settles the given amount.
         *
         * @param amount the settled amount
         */
        UnknownTenderPayment(BigDecimal amount) {
            super(amount);
        }
    }

    /**
     * A store whose legal name is present but blank is treated as declaring none, the
     * non-null-yet-blank leg of the filled-text guard: the footer keeps only the
     * register entry and the capital.
     */
    @Test
    void treatsABlankLegalNameAsAbsent() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.legalName = "   ";
        store.rcs = "123";
        store.shareCapital = new BigDecimal("1");
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "T";
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, TestMessages.french());
        assertEquals("RCS 123 — Capital : 1,00 €", document.legalFooter);
    }

    /**
     * An address carrying a street but neither postal code nor town prints the street
     * alone and appends no town line, the empty-town arm of the town-line guard.
     */
    @Test
    void printsTheStreetAloneWhenThereIsNoTown() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.address = new Address();
        store.address.streetLine1 = "12 rue A";
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "T";
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, TestMessages.french());
        assertEquals(1, document.seller.addressLines().size());
        assertEquals("12 rue A", document.seller.addressLines().get(0));
    }

    // --------------------------------------------------
    // asDocumentData (BO-03-03)
    // --------------------------------------------------

    /**
     * The document restates itself as maps, lists and strings — every field
     * already written, which is what lets an administered layout read it
     * without reaching a single method of the register.
     */
    @Test
    void theDocumentRestatesItselfAsPlainValues() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.siret = "12345678900012";
        store.address = new Address();
        store.address.streetLine1 = "12 rue A";
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "T-1";
        ticket.terminalId = "C04";
        ticket.creationDate = LocalDateTime.of(2026, 9, 15, 11, 24);
        ticket.lines.add(line("A", "3178530403022", "2", "12.90", "25.80", "0.055", null, "EPICERIE"));
        ticket.payments.add(new CashPayment(new BigDecimal("25.80"), new BigDecimal("30.00")));
        InvoiceDocument document = InvoiceDocument.of(
                invoice(ticket, fullCustomer(), "C04-F000042"), ticket, true, TestMessages.french());

        Map<String, Object> data = document.asDocumentData();

        @SuppressWarnings("unchecked")
        Map<String, Object> head = (Map<String, Object>) data.get("document");
        assertEquals("C04-F000042", head.get("number"));
        assertEquals("T-1", head.get("ticketNumber"));
        assertEquals(Boolean.FALSE, head.get("duplicate"));
        assertEquals("0", head.get("duplicateNumber"));
        // BO-02-04-08: the administered layouts read the due date from the same
        // description as the two built-in ones — empty when there is none.
        assertEquals("", head.get("dueDate"));

        @SuppressWarnings("unchecked")
        Map<String, Object> seller = (Map<String, Object>) data.get("seller");
        assertEquals("MAGASIN", seller.get("name"));
        assertEquals("12345678900012", seller.get("siret"));
        assertEquals(List.of("12 rue A"), seller.get("addressLines"));

        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) data.get("customer");
        assertNotNull(customer.get("name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) data.get("lines");
        assertEquals(1, lines.size());
        assertEquals("A", lines.get(0).get("label"));
        assertEquals("25,80", lines.get(0).get("total"));
        assertEquals("5,50 %", lines.get(0).get("vatRate"));
        assertEquals("3178530403022", lines.get(0).get("ean"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vatRows = (List<Map<String, Object>>) data.get("vatRows");
        assertEquals(1, vatRows.size());
        assertEquals("5,50 %", vatRows.get(0).get("rate"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payments = (List<Map<String, Object>>) data.get("payments");
        assertEquals(1, payments.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) data.get("totals");
        assertEquals(document.totalIncludingTax, totals.get("includingTax"));
        assertEquals(document.totalExcludingTax, totals.get("excludingTax"));
        assertEquals("C04", data.get("terminal"));
    }

    /**
     * The seller's block is also published under {@code store}, so one layout
     * vocabulary serves the invoice and every other document.
     */
    @Test
    void theSellerIsAlsoPublishedAsTheStore() {
        Store store = new Store();
        store.name = "MAGASIN";
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "T";
        Map<String, Object> data = InvoiceDocument
                .of(invoice(ticket, fullCustomer(), "N"), ticket, true, TestMessages.french())
                .asDocumentData();
        assertEquals(data.get("seller"), data.get("store"));
    }

    /**
     * A duplicate says so in its restated form, with its rank — what a layout
     * prints the DUPLICATA banner from.
     */
    @Test
    void aDuplicateSaysSoInItsRestatedForm() {
        Store store = new Store();
        store.name = "MAGASIN";
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "T";
        Invoice invoice = invoice(ticket, fullCustomer(), "N");
        invoice.printCount = 2;
        Map<String, Object> data = InvoiceDocument.of(invoice, ticket, true, TestMessages.french()).asDocumentData();
        @SuppressWarnings("unchecked")
        Map<String, Object> head = (Map<String, Object>) data.get("document");
        assertEquals(Boolean.TRUE, head.get("duplicate"));
        assertEquals("2", head.get("duplicateNumber"));
    }

    /**
     * A customer carrying administered terms puts the due date in the layout
     * description and answers {@code hasDueDate}; one carrying none answers the
     * other way and leaves the field empty — the two arms {@code BO-02-04-08}
     * turns on, which the three renderings all test.
     */
    @Test
    void theDueDateRidesTheDescriptionOnlyWhenThereIsOne() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T-1";
        ticket.lines.add(line("A", "3178530403022", "2", "12.90", "25.80", "0.055", null, "EPICERIE"));
        AccountCustomer customer = fullCustomer();
        customer.dueDate = java.time.LocalDate.of(2026, 10, 31);
        InvoiceDocument dated = InvoiceDocument.of(
                invoice(ticket, customer, "C04-F000042"), ticket, true, TestMessages.french());
        assertTrue(dated.hasDueDate());
        assertEquals("31/10/2026", dated.dueDate);
        @SuppressWarnings("unchecked")
        Map<String, Object> head = (Map<String, Object>) dated.asDocumentData().get("document");
        assertEquals("31/10/2026", head.get("dueDate"));
        customer.dueDate = null;
        InvoiceDocument undated = InvoiceDocument.of(
                invoice(ticket, customer, "C04-F000043"), ticket, true, TestMessages.french());
        assertFalse(undated.hasDueDate());
        assertEquals("", undated.dueDate);
    }

    /**
     * The addressee's fiscal identifier travels onto the document as stored when
     * no wording is named — null and blank wordings both, the two legs of the
     * guard ({@code BO-10-04-15}).
     */
    @Test
    void printsTheFiscalIdentifierAsStoredWhenNoWordingIsNamed() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        AccountCustomer customer = fullCustomer();
        customer.taxId = "PT123456789";
        assertEquals("PT123456789",
                InvoiceDocument.of(invoice(ticket, customer, "N"), ticket, true, null, TestMessages.french())
                        .customerTaxId);
        assertEquals("PT123456789",
                InvoiceDocument.of(invoice(ticket, customer, "N"), ticket, true, "  ", TestMessages.french())
                        .customerTaxId);
        assertEquals("PT123456789",
                InvoiceDocument.of(invoice(ticket, customer, "N"), ticket, true, TestMessages.french()).customerTaxId);
    }

    /**
     * A named wording replaces the digits ({@code BO-10-04-16}).
     */
    @Test
    void printsTheAdministeredWordingInPlaceOfTheDigits() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        AccountCustomer customer = fullCustomer();
        customer.taxId = "999999990";
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, customer, "N"), ticket,
                true, "CONSUMIDOR FINAL", TestMessages.french());
        assertEquals("CONSUMIDOR FINAL", document.customerTaxId);
        assertTrue(document.hasCustomerTaxId());
    }

    /**
     * A customer carrying no fiscal identifier leaves the line off the paper —
     * the false arm of {@code hasCustomerTaxId} — and the document block still
     * carries the key, empty.
     */
    @Test
    void leavesTheFiscalLineOffWhenTheCustomerCarriesNone() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true, TestMessages.french());
        assertEquals("", document.customerTaxId);
        assertFalse(document.hasCustomerTaxId());
        java.util.Map<String, Object> data = document.asDocumentData();
        java.util.Map<?, ?> customerData = (java.util.Map<?, ?>) data.get("customer");
        assertEquals("", customerData.get("taxId"));
    }

    /**
     * The administered layouts read the fiscal identifier from the customer block,
     * where it belongs: the seller never carries one.
     */
    @Test
    void theLayoutsReadTheFiscalIdentifierFromTheCustomerBlock() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        AccountCustomer customer = fullCustomer();
        customer.taxId = "PT1";
        java.util.Map<String, Object> data =
                InvoiceDocument.of(invoice(ticket, customer, "N"), ticket, true, "ANONYME", TestMessages.french())
                        .asDocumentData();
        java.util.Map<?, ?> customerData = (java.util.Map<?, ?>) data.get("customer");
        java.util.Map<?, ?> sellerData = (java.util.Map<?, ?>) data.get("seller");
        assertEquals("ANONYME", customerData.get("taxId"));
        assertNull(sellerData.get("taxId"));
    }

    /**
     * A document whose number was never assigned restates it as the empty
     * string rather than a null — the {@code value == null} arm of the private
     * {@code nullToEmpty} guard that shields every layout from a null field.
     */
    @Test
    void restatesAMissingNumberAsTheEmptyString() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        Invoice invoice = invoice(ticket, fullCustomer(), "N");
        invoice.documentNumber = null;
        Map<String, Object> data = InvoiceDocument.of(invoice, ticket, true, TestMessages.french()).asDocumentData();
        @SuppressWarnings("unchecked")
        Map<String, Object> head = (Map<String, Object>) data.get("document");
        assertEquals("", head.get("number"));
    }

    /**
     * BO-10-02-19: each figure carries the decimals the shop administered for
     * it — three on the unit price excluding tax and on the tax-included total,
     * two on the rest — and the counts are independent of one another.
     */
    @Test
    void laysOutEachFigureOnItsAdministeredDecimals() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "C04-00000417";
        ticket.creationDate = LocalDateTime.of(2026, 9, 9, 16, 12);
        ticket.lines.add(line("A", "3178530403022", "2", "12.90", "25.80", "0.055", null, "EPICERIE"));

        InvoiceDocument document = InvoiceDocument.of(
                invoice(ticket, fullCustomer(), "C04-F000123"), ticket, true,
                null, TestMessages.french(),
                new InvoiceDocument.Decimals(3, 2, 2, 2, 3));

        assertEquals("12,227", document.lines.get(0).unitExcludingTax());
        assertEquals("50,79", document.totalExcludingTax);
        assertEquals("56,600", document.totalIncludingTax);
    }

    /**
     * BO-10-02-19: the standard counts are two everywhere, which is what the
     * shorter factory hands over and what every document stated before the keys
     * existed.
     */
    @Test
    void theStandardDecimalsAreTwoEverywhere() {
        InvoiceDocument.Decimals standard = InvoiceDocument.Decimals.standard();
        assertEquals(2, standard.unitExcludingTax());
        assertEquals(2, standard.vatBase());
        assertEquals(2, standard.vatAmount());
        assertEquals(2, standard.totalExcludingTax());
        assertEquals(2, standard.totalIncludingTax());
    }
}
