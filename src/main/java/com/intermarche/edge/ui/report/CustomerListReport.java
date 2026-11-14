package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.payment.AccountCustomer;
import com.intermarche.edge.domain.store.Address;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * LISTE DES CLIENTS (BO-06-03-04): the customers in account — the ones the
 * commercial management sends and the ones the registers open — and the
 * PASSING customers who asked for an invoice, with the account number, the
 * country code, the last and first names, the address and its type (main or
 * invoicing), the telephone, the VAT code, the SIRET, the billing type and the
 * profession.
 *
 * <p>A customer whose invoicing address differs from the main one is listed
 * twice, once per address, the type saying which. The type option keeps the
 * customers in account, the passing ones, or both; the range keeps a range of
 * account NUMBERS when its bounds start with a digit, a range of NAMES
 * otherwise — « une plage de numéros de client ou une plage de noms de client ».
 */
@ApplicationScoped
public class CustomerListReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CustomerListReport.class);

    /** The option value keeping the customers in account. */
    static final String ACCOUNT = "ACCOUNT";

    /** The option value keeping the passing customers. */
    static final String VOLATILE = "VOLATILE";

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "customers";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "Liste des clients";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-03-04";
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
                ReportColumn.text("account", "N° du compte client"),
                ReportColumn.text("type", "Type de client"),
                ReportColumn.text("country", "Code pays"),
                ReportColumn.text("lastName", "Nom du client"),
                ReportColumn.text("firstName", "Prénom du client"),
                ReportColumn.text("address", "Adresse"),
                ReportColumn.text("addressType", "Type d'adresse"),
                ReportColumn.text("phone", "N° de téléphone"),
                ReportColumn.text("vat", "Code TVA"),
                ReportColumn.text("siret", "SIRET"),
                ReportColumn.text("billing", "Type de facturation"),
                ReportColumn.text("profession", "Profession"));
    }

    /**
     * {@inheritDoc}
     *
     * @return what the type option is called on the screen
     */
    @Override
    public String optionLabel() {
        return "Type de client";
    }

    /**
     * {@inheritDoc}
     *
     * @return both, in account, passing
     */
    @Override
    public List<String> optionChoices() {
        return List.of("ALL:Tous les clients", ACCOUNT + ":Clients en compte", VOLATILE + ":Clients volatiles");
    }

    /**
     * {@inheritDoc}
     *
     * @return what the range is called on the screen
     */
    @Override
    public String boundLabel() {
        return "Plage de numéros ou de noms de client";
    }

    /**
     * {@inheritDoc}
     *
     * @return no criterion beyond the report's own: a customer belongs to no register
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.noneOf(ReportFilter.class);
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per address of each customer kept, by account number
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        String option = criteria.optionOr("ALL");
        boolean byNumber = numeric(criteria.boundFrom) || numeric(criteria.boundTo);
        List<List<Object>> rows = new ArrayList<>();
        for (AccountCustomer customer : entityManager.createQuery(
                "select c from AccountCustomer c order by c.accountNumber", AccountCustomer.class).getResultList()) {
            boolean passing = customer.origin == AccountCustomer.Origin.VOLATILE;
            if (ACCOUNT.equals(option) && passing || VOLATILE.equals(option) && !passing) {
                continue;
            }
            String name = customer.lastName == null ? customer.companyName : customer.lastName;
            if (!criteria.withinBounds(byNumber ? customer.accountNumber : name)) {
                continue;
            }
            rows.add(row(customer, passing, name, customer.address, "Principale"));
            if (customer.billingAddress != null && customer.billingAddress.streetLine1 != null) {
                rows.add(row(customer, passing, name, customer.billingAddress, "Facturation"));
            }
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Builds the row of one address of a customer.
     *
     * @param customer the customer
     * @param passing whether the customer is a passing one
     * @param name the name shown
     * @param address the address, possibly null
     * @param type the address type
     * @return the row
     */
    private List<Object> row(AccountCustomer customer, boolean passing, String name, Address address, String type) {
        return Arrays.asList(
                customer.accountNumber,
                passing ? "Client volatile" : "Client en compte",
                address == null || address.country == null ? "" : address.country,
                name == null ? "" : name,
                customer.firstName == null ? "" : customer.firstName,
                address == null ? "" : text(address),
                type,
                customer.phone == null ? "" : customer.phone,
                customer.vatNumber == null ? "" : customer.vatNumber,
                customer.siret == null ? "" : customer.siret,
                billing(customer),
                customer.profession == null ? "" : customer.profession);
    }

    /**
     * Names the billing type of a customer.
     *
     * @param customer the customer
     * @return the French label, the supplement code beside its type, empty when none
     */
    static String billing(AccountCustomer customer) {
        if (customer.billingType == null) {
            return "";
        }
        return switch (customer.billingType) {
            case "AMOUNT" -> "Montant";
            case "PERCENT" -> "Pourcentage";
            case "SUPPLEMENT" -> customer.supplementCode == null ? "Code supplément"
                    : "Code supplément " + customer.supplementCode;
            default -> customer.billingType;
        };
    }

    /**
     * Writes an address on one line.
     *
     * @param address the address
     * @return the street, the postal code and the city
     */
    private static String text(Address address) {
        StringBuilder text = new StringBuilder();
        for (String part : new String[]{address.streetLine1, address.postalCode, address.city}) {
            if (part != null && !part.isBlank()) {
                text.append(text.length() == 0 ? "" : " ").append(part.trim());
            }
        }
        return text.toString();
    }

    /**
     * Whether a bound is a number.
     *
     * @param bound the bound, possibly null
     * @return true when it starts with a digit
     */
    static boolean numeric(String bound) {
        return bound != null && Character.isDigit(bound.charAt(0));
    }
}
