package com.intermarche.edge.imports;

import com.intermarche.edge.domain.payment.AccountCustomer;
import com.intermarche.edge.domain.store.Address;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Integrates the CUSTOMERS IN ACCOUNT sent by the commercial management
 * ({@code BO-02-04-01} to {@code BO-02-04-11}, {@code BO-02-04-20}).
 *
 * <p>Two feeds, one endpoint. The questionnaire asks for a FULL integration and
 * a DELTA integration every evening after the shop closes; they carry the same
 * rows in the same shape and differ in one thing only — whether the file is the
 * whole population or the evening's changes. That is what {@code mode=full}
 * says, and the only thing it changes: an account the full feed does NOT carry
 * is one the commercial management no longer keeps, so it is BLOCKED rather
 * than deleted. Deleting it would take with it the invoices and the tickets
 * that name it, and a shop audited two years later would find documents
 * addressed to nobody. Blocking says the one thing that is true: no more credit
 * here.
 *
 * <p>Creating a customer in account is NOT possible from the register
 * ({@code BO-02-04-12}): this feed and the back-office correction are the only
 * two ways one gets in or changes.
 *
 * <p>Consumed columns, resolved by header name (unknown columns are ignored):
 * ACCOUNT_NUMBER (key), COMPANY_NAME, CIVILITY, LAST_NAME, FIRST_NAME,
 * STREET_LINE1, POSTAL_CODE, CITY, SIRET, VAT_NUMBER, TAX_ID, PHONE, EMAIL,
 * BLOCKED, DUE_DATE ({@code yyyy-MM-dd}), DISCOUNT_PERCENT, CREDIT_LIMIT,
 * CREDIT_BALANCE — and the free fields of {@code BO-02-04-11}, read from any
 * pair of columns named {@code FREE_nn_LABEL} and {@code FREE_nn_VALUE}. The
 * reserve is read from the HEADER and not from ninety-nine declared constants:
 * a feed that uses three of them declares three columns, and the day a
 * hundredth is asked for nothing here has to change.
 */
// No @Path: the ONE door is POST /feeds/import/{CODE}, which delegates here.
// A second address for the same import is a second thing to secure, to
// document and to keep in step, and the permission policy had to name them
// one by one — an importer forgotten there was a door left open.
@ApplicationScoped
public class AccountCustomerCsvResource extends ImporterCsvResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AccountCustomerCsvResource.class);

    /** The back-office parameters, for the address control (BO-10-08-27). */
    @jakarta.inject.Inject
    com.intermarche.edge.service.PosSettingsService posSettingsService;

    /** Header name of the natural key: the account number. */
    static final String COL_ACCOUNT_NUMBER = "ACCOUNT_NUMBER";
    /** Header name of the business name. */
    static final String COL_COMPANY_NAME = "COMPANY_NAME";
    /** Header name of the contact's civility. */
    static final String COL_CIVILITY = "CIVILITY";
    /** Header name of the contact's last name. */
    static final String COL_LAST_NAME = "LAST_NAME";
    /** Header name of the contact's first name. */
    static final String COL_FIRST_NAME = "FIRST_NAME";
    /** Header name of the street line of the billing address. */
    static final String COL_STREET_LINE1 = "STREET_LINE1";
    /** Header name of the postal code of the billing address. */
    static final String COL_POSTAL_CODE = "POSTAL_CODE";
    /** Header name of the city of the billing address. */
    static final String COL_CITY = "CITY";
    /** Header name of the SIRET. */
    static final String COL_SIRET = "SIRET";
    /** Header name of the intra-community VAT number. */
    static final String COL_VAT_NUMBER = "VAT_NUMBER";
    /** Header name of the fiscal identifier (NIF). */
    static final String COL_TAX_ID = "TAX_ID";
    /** Header name of the commercial segment. */
    static final String COL_SEGMENT = "SEGMENT";
    /** Header name of the telephone number. */
    static final String COL_PHONE = "PHONE";
    /** Header name of the electronic address. */
    static final String COL_EMAIL = "EMAIL";
    /** Header name of the blocking flag. */
    static final String COL_BLOCKED = "BLOCKED";
    /** Header name of the due date, ISO {@code yyyy-MM-dd}. */
    static final String COL_DUE_DATE = "DUE_DATE";
    /** Header name of the granted discount, in percent. */
    static final String COL_DISCOUNT_PERCENT = "DISCOUNT_PERCENT";
    /** Header name of the credit ceiling. */
    static final String COL_CREDIT_LIMIT = "CREDIT_LIMIT";
    /** Header name of the outstanding balance. */
    static final String COL_CREDIT_BALANCE = "CREDIT_BALANCE";

    /** Prefix of the free-field columns of {@code BO-02-04-11}. */
    static final String FREE_PREFIX = "FREE_";
    /** Suffix of the free-field column carrying the label. */
    static final String FREE_LABEL_SUFFIX = "_LABEL";
    /** Suffix of the free-field column carrying the value. */
    static final String FREE_VALUE_SUFFIX = "_VALUE";

    /** The columns this importer cannot work without. */
    private static final List<String> REQUIRED_COLUMNS = List.of(COL_COMPANY_NAME);

    /**
     * The account numbers this import has seen, collected chunk by chunk so a
     * FULL feed can block the accounts it does not carry.
     *
     * <p>A thread-local and not a field: one import runs from end to end on one
     * virtual thread, while the resource itself is application-scoped and two
     * shops could be importing at the same moment.
     */
    private static final ThreadLocal<Set<String>> SEEN = ThreadLocal.withInitial(HashSet::new);

    /**
     * Imports or updates the customers in account from a CSV stream.
     *
     * @param inputStream the stream carrying the CSV
     * @param mode {@code full} for the complete population, anything else — or
     *        nothing — for the evening's delta
     * @return a JSON summary of the created and updated counts and the errors
     */
    public Response importCustomers(InputStream inputStream, String mode) {
        LOGGER.info("Entering method importCustomers with mode: " + mode);
        SEEN.remove();
        try {
            Response response = this.importCsvStream(inputStream, COL_ACCOUNT_NUMBER, REQUIRED_COLUMNS);
            if (isFull(mode)) {
                blockAbsentAccounts(SEEN.get());
            }
            LOGGER.info("Exiting method importCustomers");
            return response;
        } finally {
            SEEN.remove();
        }
    }

    /**
     * Says whether the caller declared the feed complete.
     *
     * @param mode the {@code mode} query parameter, possibly null
     * @return true when the feed carries the whole population
     */
    boolean isFull(String mode) {
        return mode != null && "full".equalsIgnoreCase(mode.trim());
    }

    /**
     * Blocks the accounts the full feed did not carry.
     *
     * <p>Only the ones not already blocked are touched, so an evening where
     * nothing left the population writes nothing.
     *
     * @param seen the account numbers the feed carried
     * @return the number of accounts blocked by this call
     */
    @jakarta.transaction.Transactional
    public long blockAbsentAccounts(Set<String> seen) {
        LOGGER.info("Entering method blockAbsentAccounts with " + seen.size() + " account(s) seen");
        List<AccountCustomer> absent = seen.isEmpty()
                ? AccountCustomer.<AccountCustomer>list("blocked = false")
                : AccountCustomer.<AccountCustomer>list("blocked = false and accountNumber not in ?1", seen);
        for (AccountCustomer customer : absent) {
            customer.blocked = true;
            customer.persist();
        }
        LOGGER.infof("Intégration full : %d compte(s) client bloqué(s), absent(s) du fichier", absent.size());
        LOGGER.info("Exiting method blockAbsentAccounts with " + absent.size());
        return absent.size();
    }

    /**
     * Bulk-fetches the accounts of the chunk and records the numbers seen.
     *
     * @param parsedLines the rows of the current chunk
     * @param targetCodes the account numbers of the current chunk
     * @param counters the created and updated counters
     * @param errors the collected error messages
     * @return the account-number to entity map the generic algorithm works on
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines,
                                                           Set<String> targetCodes,
                                                           int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) {
            return new HashMap<>();
        }
        SEEN.get().addAll(targetCodes);
        Map<String, Object> customers = new HashMap<>();
        for (AccountCustomer customer
                : AccountCustomer.<AccountCustomer>list("accountNumber IN ?1", targetCodes)) {
            customers.put(customer.accountNumber, customer);
        }
        return customers;
    }

    /**
     * Creates or updates one account from its row.
     *
     * @param data the parsed row
     * @param entityMap the accounts already in the database, by number
     * @param counters the created and updated counters
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        refuseIncompleteAddress(data);
        AccountCustomer customer = (AccountCustomer) entityMap.get(data.code);
        if (customer == null) {
            customer = new AccountCustomer();
            customer.accountNumber = data.code;
            feedCustomer(data, customer);
            counters[0]++;
            Panache.getEntityManager().persist(customer);
        } else {
            customer = AccountCustomer.findById(customer.id);
            int incoming = computeIncomingChecksum(data);
            if (customer.checksum == null || customer.checksum != incoming) {
                feedCustomer(data, customer);
                counters[1]++;
            }
        }
    }

    /**
     * Finds one account afresh, for the one-by-one fallback of the base class.
     *
     * @param data the parsed row
     * @return the account, or null when this number is unknown
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return AccountCustomer.find("accountNumber", data.code).firstResult();
    }

    /**
     * REFUSES a row whose billing address is incomplete, where the shop asks
     * for it (BO-10-08-27).
     *
     * <p>Thrown rather than skipped: the base importer turns a row that throws
     * into one error entry naming its line and its account number, and goes on
     * with the rest of the file. A client one cannot invoice is better named in
     * the report than quietly integrated — but the shop that did not ask for
     * the control keeps integrating what the feed carries, which is what every
     * feed did before this parameter existed.
     *
     * @param data the parsed row
     */
    private void refuseIncompleteAddress(LineData data) {
        if (!posSettingsService.customerIntegrationRequiresAddress()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(safeGet(data, COL_STREET_LINE1))) {
            missing.add(COL_STREET_LINE1);
        }
        if (isBlank(safeGet(data, COL_POSTAL_CODE))) {
            missing.add(COL_POSTAL_CODE);
        }
        if (isBlank(safeGet(data, COL_CITY))) {
            missing.add(COL_CITY);
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "adresse incomplète, colonnes manquantes : " + String.join(", ", missing));
        }
    }

    /**
     * Whether a cell carries nothing a person could post a bill to.
     *
     * @param value the cell, possibly null
     * @return true when it is null, empty or blank
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Populates an account from its row.
     *
     * @param data the parsed row
     * @param customer the account to fill
     */
    private void feedCustomer(LineData data, AccountCustomer customer) {
        // The feed IS the commercial management, so every row it carries is one
        // of its own — including a row a register created and the shop has since
        // declared upstream (BO-02-04-22).
        customer.origin = AccountCustomer.Origin.INTEGRATED;
        customer.companyName = safeGet(data, COL_COMPANY_NAME);
        customer.civility = safeGet(data, COL_CIVILITY);
        customer.lastName = safeGet(data, COL_LAST_NAME);
        customer.firstName = safeGet(data, COL_FIRST_NAME);
        if (customer.address == null) {
            customer.address = new Address();
        }
        customer.address.streetLine1 = safeGet(data, COL_STREET_LINE1);
        customer.address.postalCode = safeGet(data, COL_POSTAL_CODE);
        customer.address.city = safeGet(data, COL_CITY);
        customer.siret = safeGet(data, COL_SIRET);
        customer.vatNumber = safeGet(data, COL_VAT_NUMBER);
        customer.taxId = safeGet(data, COL_TAX_ID);
        customer.segment = safeGet(data, COL_SEGMENT);
        customer.phone = safeGet(data, COL_PHONE);
        customer.email = safeGet(data, COL_EMAIL);
        customer.blocked = safeParseBoolean(data, COL_BLOCKED);
        customer.dueDate = parseDate(safeGet(data, COL_DUE_DATE));
        customer.discountPercent = safeParseBigDecimal(data, COL_DISCOUNT_PERCENT);
        customer.creditLimit = safeParseBigDecimal(data, COL_CREDIT_LIMIT);
        BigDecimal balance = safeParseBigDecimal(data, COL_CREDIT_BALANCE);
        customer.creditBalance = balance == null ? BigDecimal.ZERO : balance;
        customer.freeFields.clear();
        customer.freeFields.addAll(freeFields(data));
    }

    /**
     * Reads the free fields the row carries, by walking its HEADER for the
     * {@code FREE_nn_LABEL} / {@code FREE_nn_VALUE} pairs ({@code BO-02-04-11}).
     *
     * <p>A pair whose two cells are both empty is not a field: a feed declaring
     * the ninety-nine columns for every customer would otherwise give each of
     * them ninety-nine empty rows.
     *
     * @param data the parsed row
     * @return the free fields found, ordered by slot
     */
    List<AccountCustomer.FreeField> freeFields(LineData data) {
        List<AccountCustomer.FreeField> fields = new ArrayList<>();
        for (String column : data.header.keySet()) {
            if (!column.startsWith(FREE_PREFIX) || !column.endsWith(FREE_LABEL_SUFFIX)) {
                continue;
            }
            String slotText = column.substring(FREE_PREFIX.length(),
                    column.length() - FREE_LABEL_SUFFIX.length());
            Integer slot = slot(slotText);
            if (slot == null) {
                continue;
            }
            String label = safeGet(data, column);
            String value = safeGet(data, FREE_PREFIX + slotText + FREE_VALUE_SUFFIX);
            if ((label == null || label.isEmpty()) && (value == null || value.isEmpty())) {
                continue;
            }
            fields.add(new AccountCustomer.FreeField(slot, label, value));
        }
        fields.sort(java.util.Comparator.comparingInt(field -> field.slot));
        return fields;
    }

    /**
     * Reads the slot number out of a free-field column name.
     *
     * @param text what stood between the prefix and the suffix
     * @return the slot when it is a number from 1 to 99, null otherwise
     */
    private Integer slot(String text) {
        try {
            int slot = Integer.parseInt(text);
            return slot >= 1 && slot <= 99 ? slot : null;
        } catch (NumberFormatException notASlot) {
            return null;
        }
    }

    /**
     * Reads an ISO date, an unreadable one counting as none.
     *
     * @param text the cell, possibly null or empty
     * @return the date, or null when nothing readable was given
     */
    private LocalDate parseDate(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (java.time.format.DateTimeParseException unreadable) {
            LOGGER.warnf("Date d'échéance illisible ignorée : %s", text);
            return null;
        }
    }

    /**
     * Computes the checksum the incoming row WOULD carry once stored, by
     * feeding a throw-away account and asking it — never by replaying the hash
     * by hand, which goes stale on the next field added.
     *
     * @param data the parsed row
     * @return the checksum the stored row would carry
     */
    private int computeIncomingChecksum(LineData data) {
        AccountCustomer candidate = new AccountCustomer();
        candidate.accountNumber = data.code;
        feedCustomer(data, candidate);
        return candidate.getChecksum();
    }
}
