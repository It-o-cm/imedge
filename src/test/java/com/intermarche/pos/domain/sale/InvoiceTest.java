package com.intermarche.pos.domain.sale;

import com.intermarche.pos.domain.payment.AccountCustomer;
import com.intermarche.pos.domain.store.Address;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Invoice}, targeting 100% branch coverage.
 * <p>
 * The class carries its conditional logic in two places: the private {@code copyOf}
 * helper reached through {@link Invoice#addressTo(AccountCustomer)} guards on a null
 * source address (both arms exercised), and {@link Invoice#isDuplicate()} tests
 * {@code printCount >= 1} (both arms exercised). The getters, field defaults and
 * {@link Invoice#getChecksum()} carry no branches. No static finder or persist is
 * reached, so no Panache mocking is required. Each test is fully isolated and
 * asserts absolute expected values.
 */
class InvoiceTest {

    /**
     * Builds an account customer carrying the identity and address fields used by
     * {@link Invoice#addressTo(AccountCustomer)}.
     *
     * @param address the billing address, possibly null
     * @return a populated {@link AccountCustomer}
     */
    private AccountCustomer customer(Address address) {
        AccountCustomer source = new AccountCustomer();
        source.accountNumber = "ACC-001";
        source.companyName = "ACME SARL";
        source.firstName = "Jean";
        source.lastName = "Dupont";
        source.siret = "12345678900011";
        source.vatNumber = "FR12345678900";
        source.taxId = "PT123456789";
        source.dueDate = java.time.LocalDate.of(2026, 10, 31);
        source.address = address;
        return source;
    }

    /**
     * The default constructor sets the document type to FACTURE.
     */
    @Test
    void defaultDocumentTypeIsFacture() {
        Invoice invoice = new Invoice();
        Assertions.assertEquals(DocumentType.FACTURE, invoice.documentType);
    }

    /**
     * The default constructor initializes the totals to zero.
     */
    @Test
    void defaultTotalsAreZero() {
        Invoice invoice = new Invoice();
        Assertions.assertEquals(BigDecimal.ZERO, invoice.totalExcludingTax);
        Assertions.assertEquals(BigDecimal.ZERO, invoice.totalIncludingTax);
        Assertions.assertEquals(BigDecimal.ZERO, invoice.totalVat);
    }

    /**
     * The default constructor sets the print count to zero and a non-null address.
     */
    @Test
    void defaultPrintCountAndAddress() {
        Invoice invoice = new Invoice();
        Assertions.assertEquals(0, invoice.printCount);
        Assertions.assertNotNull(invoice.customerAddress);
    }

    /**
     * All public fields accept and return their assigned values.
     */
    @Test
    void fieldsAreReadWrite() {
        Invoice invoice = new Invoice();
        Ticket ticket = new Ticket();
        AccountCustomer source = customer(new Address());
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 10, 0);
        Address address = new Address();
        invoice.documentNumber = "C04-F000123";
        invoice.documentType = DocumentType.BON_LIVRAISON;
        invoice.terminalId = "C04";
        invoice.issueDate = now;
        invoice.ticket = ticket;
        invoice.ticketNumber = "C04-T000045";
        invoice.customer = source;
        invoice.customerAccountNumber = "ACC-001";
        invoice.customerName = "ACME SARL";
        invoice.customerContact = "Jean Dupont";
        invoice.customerAddress = address;
        invoice.customerSiret = "12345678900011";
        invoice.customerVatNumber = "FR12345678900";
        invoice.totalExcludingTax = new BigDecimal("8.3333");
        invoice.totalIncludingTax = new BigDecimal("10.0000");
        invoice.totalVat = new BigDecimal("1.6667");
        invoice.printCount = 2;
        Assertions.assertEquals("C04-F000123", invoice.documentNumber);
        Assertions.assertEquals(DocumentType.BON_LIVRAISON, invoice.documentType);
        Assertions.assertEquals("C04", invoice.terminalId);
        Assertions.assertEquals(now, invoice.issueDate);
        Assertions.assertSame(ticket, invoice.ticket);
        Assertions.assertEquals("C04-T000045", invoice.ticketNumber);
        Assertions.assertSame(source, invoice.customer);
        Assertions.assertEquals("ACC-001", invoice.customerAccountNumber);
        Assertions.assertEquals("ACME SARL", invoice.customerName);
        Assertions.assertEquals("Jean Dupont", invoice.customerContact);
        Assertions.assertSame(address, invoice.customerAddress);
        Assertions.assertEquals("12345678900011", invoice.customerSiret);
        Assertions.assertEquals("FR12345678900", invoice.customerVatNumber);
        Assertions.assertEquals(new BigDecimal("8.3333"), invoice.totalExcludingTax);
        Assertions.assertEquals(new BigDecimal("10.0000"), invoice.totalIncludingTax);
        Assertions.assertEquals(new BigDecimal("1.6667"), invoice.totalVat);
        Assertions.assertEquals(2, invoice.printCount);
    }

    /**
     * addressTo copies the customer identity fields and links the live customer.
     */
    @Test
    void addressToCopiesIdentityFields() {
        Invoice invoice = new Invoice();
        AccountCustomer source = customer(new Address());
        invoice.addressTo(source);
        Assertions.assertSame(source, invoice.customer);
        Assertions.assertEquals("ACC-001", invoice.customerAccountNumber);
        Assertions.assertEquals("ACME SARL", invoice.customerName);
        Assertions.assertEquals("Jean Dupont", invoice.customerContact);
        Assertions.assertEquals("12345678900011", invoice.customerSiret);
        Assertions.assertEquals("FR12345678900", invoice.customerVatNumber);
        // The NIF is copied onto the document like the SIRET beside it: what
        // the back office extracts must be what was printed (BO-02-04-19).
        Assertions.assertEquals("PT123456789", invoice.customerTaxId);
        // BO-02-04-08: the due date is copied onto the document too, because
        // the paper must carry it and the customer's terms may change later.
        Assertions.assertEquals(java.time.LocalDate.of(2026, 10, 31), invoice.customerDueDate);
    }

    /**
     * addressTo with a present address (copyOf non-null arm) deep-copies every
     * address field into a fresh, unshared instance.
     */
    @Test
    void addressToCopiesPresentAddress() {
        Invoice invoice = new Invoice();
        Address address = new Address();
        address.streetLine1 = "10 Rue de la Paix";
        address.streetLine2 = "Batiment A";
        address.postalCode = "75002";
        address.city = "Paris";
        address.country = "France";
        AccountCustomer source = customer(address);
        invoice.addressTo(source);
        Assertions.assertNotSame(address, invoice.customerAddress);
        Assertions.assertEquals("10 Rue de la Paix", invoice.customerAddress.streetLine1);
        Assertions.assertEquals("Batiment A", invoice.customerAddress.streetLine2);
        Assertions.assertEquals("75002", invoice.customerAddress.postalCode);
        Assertions.assertEquals("Paris", invoice.customerAddress.city);
        Assertions.assertEquals("France", invoice.customerAddress.country);
    }

    /**
     * addressTo with a null address (copyOf null arm) yields a fresh, empty address
     * rather than a null reference.
     */
    @Test
    void addressToWithNullAddressYieldsEmpty() {
        Invoice invoice = new Invoice();
        AccountCustomer source = customer(null);
        invoice.addressTo(source);
        Assertions.assertNotNull(invoice.customerAddress);
        Assertions.assertNull(invoice.customerAddress.streetLine1);
        Assertions.assertNull(invoice.customerAddress.streetLine2);
        Assertions.assertNull(invoice.customerAddress.postalCode);
        Assertions.assertNull(invoice.customerAddress.city);
        Assertions.assertNull(invoice.customerAddress.country);
    }

    /**
     * isDuplicate is false before the document has ever been printed (printCount
     * zero, the {@code >= 1} false arm).
     */
    @Test
    void isDuplicateFalseWhenNeverPrinted() {
        Invoice invoice = new Invoice();
        invoice.printCount = 0;
        Assertions.assertFalse(invoice.isDuplicate());
    }

    /**
     * isDuplicate is true once the document has been printed at least once
     * (printCount positive, the {@code >= 1} true arm).
     */
    @Test
    void isDuplicateTrueAfterFirstPrint() {
        Invoice invoice = new Invoice();
        invoice.printCount = 1;
        Assertions.assertTrue(invoice.isDuplicate());
    }

    /**
     * getChecksum hashes the salient business fields in their declared order.
     */
    @Test
    void checksumHashesSalientFields() {
        Invoice invoice = new Invoice();
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 10, 0);
        invoice.documentNumber = "C04-F000123";
        invoice.documentType = DocumentType.FACTURE;
        invoice.terminalId = "C04";
        invoice.issueDate = now;
        invoice.ticketNumber = "C04-T000045";
        invoice.customerAccountNumber = "ACC-001";
        invoice.customerName = "ACME SARL";
        invoice.customerContact = "Jean Dupont";
        invoice.customerSiret = "12345678900011";
        invoice.customerVatNumber = "FR12345678900";
        invoice.customerTaxId = "PT123456789";
        invoice.customerDueDate = java.time.LocalDate.of(2026, 10, 31);
        invoice.totalExcludingTax = new BigDecimal("8.3333");
        invoice.totalIncludingTax = new BigDecimal("10.0000");
        invoice.totalVat = new BigDecimal("1.6667");
        invoice.totalEcoTax = new BigDecimal("0.4000");
        invoice.printCount = 2;
        int expected = Objects.hash("C04-F000123", DocumentType.FACTURE, "C04", now,
                "C04-T000045", "ACC-001", "ACME SARL", "Jean Dupont", "12345678900011",
                "FR12345678900", "PT123456789", java.time.LocalDate.of(2026, 10, 31),
                new BigDecimal("8.3333"), new BigDecimal("10.0000"),
                new BigDecimal("1.6667"), new BigDecimal("0.4000"), 2);
        Assertions.assertEquals(expected, invoice.getChecksum());
    }

    /**
     * A change in a hashed field changes the checksum (change detection contract).
     */
    @Test
    void checksumChangesWhenFieldChanges() {
        Invoice invoice = new Invoice();
        invoice.documentNumber = "C04-F000123";
        int before = invoice.getChecksum();
        invoice.documentNumber = "C04-F000124";
        Assertions.assertNotEquals(before, invoice.getChecksum());
    }
}
