package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.payment.AccountCustomer;
import com.intermarche.edge.domain.store.Address;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CustomerListReport}: one row per address of each
 * customer, the principal and billing addresses, the account and passing
 * options, the range read on the number or on the name, the billing types and
 * the metadata.
 */
class CustomerListReportTest {

    /**
     * Builds a customer.
     *
     * @param number the account number
     * @param origin the origin
     * @param lastName the last name, possibly null
     * @return the customer
     */
    private AccountCustomer customer(String number, AccountCustomer.Origin origin, String lastName) {
        AccountCustomer customer = new AccountCustomer();
        customer.accountNumber = number;
        customer.origin = origin;
        customer.lastName = lastName;
        customer.companyName = "Société " + number;
        return customer;
    }

    /**
     * The customers of the referential.
     *
     * @return the customers
     */
    private List<AccountCustomer> customers() {
        AccountCustomer full = customer("100", AccountCustomer.Origin.REGISTER, "Dupont");
        full.firstName = "Jean";
        full.phone = "0102";
        full.vatNumber = "FR1";
        full.siret = "123";
        full.profession = "Boulanger";
        full.billingType = "AMOUNT";
        full.address.streetLine1 = " 1 rue Haute ";
        full.address.postalCode = "";
        full.address.city = "Paris";
        full.address.country = "FR";
        full.billingAddress = new Address();
        full.billingAddress.streetLine1 = "2 rue Basse";
        AccountCustomer passing = customer("200", AccountCustomer.Origin.VOLATILE, null);
        passing.address = null;
        passing.billingAddress = new Address();
        AccountCustomer company = customer("300", AccountCustomer.Origin.INTEGRATED, null);
        return List.of(full, passing, company);
    }

    /**
     * Runs the report.
     *
     * @param criteria the edition parameters
     * @return the rows
     */
    private List<List<Object>> run(ReportCriteria criteria) {
        CustomerListReport report = new CustomerListReport();
        report.entityManager = new QueryStub().answer("from AccountCustomer c", customers()).entityManager;
        return report.rows(criteria);
    }

    /**
     * Every customer, the billing address as a row of its own; a passing
     * customer without address reads blank, a company is named by its name.
     */
    @Test
    void everyCustomerAndAddress() {
        List<List<Object>> rows = run(new ReportCriteria());
        assertEquals(4, rows.size());
        assertEquals(Arrays.asList("100", "Client en compte", "FR", "Dupont", "Jean", "1 rue Haute Paris",
                "Principale", "0102", "FR1", "123", "Montant", "Boulanger"), rows.get(0));
        assertEquals("Facturation", rows.get(1).get(6));
        assertEquals("2 rue Basse", rows.get(1).get(5));
        assertEquals("", rows.get(1).get(2));
        List<Object> passing = rows.get(2);
        assertEquals("Client volatile", passing.get(1));
        assertEquals("", passing.get(2));
        assertEquals("Société 200", passing.get(3));
        assertEquals("", passing.get(4));
        assertEquals("", passing.get(5));
        assertEquals("", passing.get(7));
        assertEquals("", passing.get(10));
        assertEquals("", passing.get(11));
        assertEquals("Société 300", rows.get(3).get(3));
    }

    /**
     * The options keep the customers in account or the passing ones; a range
     * of numbers reads the account number, a range of names the name.
     */
    @Test
    void theOptionsAndRanges() {
        ReportCriteria account = new ReportCriteria();
        account.option = CustomerListReport.ACCOUNT;
        assertEquals(3, run(account).size());
        ReportCriteria passing = new ReportCriteria();
        passing.option = CustomerListReport.VOLATILE;
        assertEquals(1, run(passing).size());
        ReportCriteria numbers = new ReportCriteria();
        numbers.boundTo = "250";
        assertEquals(3, run(numbers).size());
        ReportCriteria fromNumber = new ReportCriteria();
        fromNumber.boundFrom = "250";
        assertEquals(1, run(fromNumber).size());
        ReportCriteria names = new ReportCriteria();
        names.boundFrom = "E";
        List<List<Object>> named = run(names);
        assertEquals(2, named.size());
        assertEquals("200", named.get(0).get(0));
        assertTrue(CustomerListReport.numeric("1"));
        assertFalse(CustomerListReport.numeric("A"));
        assertFalse(CustomerListReport.numeric(null));
    }

    /**
     * A customer without any name reads a blank name.
     */
    @Test
    void aNamelessCustomer() {
        AccountCustomer nameless = customer("400", AccountCustomer.Origin.REGISTER, null);
        nameless.companyName = null;
        CustomerListReport report = new CustomerListReport();
        report.entityManager = new QueryStub().answer("from AccountCustomer c", List.of(nameless)).entityManager;
        assertEquals("", report.rows(new ReportCriteria()).get(0).get(3));
    }

    /**
     * The billing types read in French, the supplement with its code, an
     * unknown type as it stands.
     */
    @Test
    void theBillingTypes() {
        AccountCustomer customer = new AccountCustomer();
        assertEquals("", CustomerListReport.billing(customer));
        customer.billingType = "PERCENT";
        assertEquals("Pourcentage", CustomerListReport.billing(customer));
        customer.billingType = "SUPPLEMENT";
        assertEquals("Code supplément", CustomerListReport.billing(customer));
        customer.supplementCode = "S1";
        assertEquals("Code supplément S1", CustomerListReport.billing(customer));
        customer.billingType = "OTHER";
        assertEquals("OTHER", CustomerListReport.billing(customer));
    }

    /**
     * The metadata: identity, columns, options, range and filters.
     */
    @Test
    void metadataDescribesTheReport() {
        CustomerListReport report = new CustomerListReport();
        assertEquals("customers", report.code());
        assertEquals("Liste des clients", report.label());
        assertEquals("BO-06-03-04", report.requirement());
        assertEquals(ReportFamily.CUSTOMERS, report.family());
        assertEquals(12, report.columns().size());
        assertEquals("Type de client", report.optionLabel());
        assertEquals(3, report.optionChoices().size());
        assertEquals("Plage de numéros ou de noms de client", report.boundLabel());
        assertTrue(report.filters().isEmpty());
    }
}
