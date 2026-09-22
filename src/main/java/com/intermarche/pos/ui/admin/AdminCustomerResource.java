package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.payment.AccountCustomer;
import com.intermarche.pos.domain.store.Address;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The CUSTOMERS IN ACCOUNT back-office screen ({@code /admin/customers}):
 * consults both populations — the ones the commercial management sent and the
 * ones a register created ({@code BO-02-04-22}) — shows one customer's whole
 * file ({@code BO-02-04-23}) with the date and amount of their last credit
 * payment ({@code BO-02-04-14}), corrects the file ({@code BO-02-04-12}) and
 * extracts the filtered list, by fiscal identifier among other things
 * ({@code BO-02-04-18}).
 *
 * <p>NOTHING IS CREATED HERE, and nothing is created at the register either:
 * the requirement is explicit, a customer in account enters by the integration
 * alone ({@code BO-02-04-01}). This screen edits an existing file, like
 * {@code AdminArticleResource} and {@code AdminStoreResource} do for theirs.
 *
 * <p>What it edits, it edits until the NEXT INTEGRATION: the commercial
 * management is the owner of these rows and overwrites them every evening. That
 * is not a defect of this screen, it is what the requirement describes — a
 * correction made here holds the counter until the shop has corrected it
 * upstream too. Sending the correction back up ({@code BO-02-04-13}) needs a
 * road towards the commercial management that this node does not have yet.
 *
 * <p>Access reserved to ADMIN, like every referential screen.
 * POST&nbsp;→&nbsp;303&nbsp;→&nbsp;notice.
 */
@Path("/admin/customers")
public class AdminCustomerResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminCustomerResource.class);

    /** The customer list template. */
    @Inject
    @Location("admin-customers")
    Template adminCustomers;

    /** The customer file template. */
    @Inject
    @Location("admin-customer")
    Template adminCustomer;

    /** The reader of the last credit payment. */
    @Inject
    EntityManager entityManager;

    /** The back-office parameters governing whether an account may be opened. */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * The last credit payment an account made, as the file shows it
     * ({@code BO-02-04-14}).
     */
    public static class LastMovement {

        /** When it was paid. */
        public java.time.LocalDateTime date;

        /** How much was paid on credit that day. */
        public BigDecimal amount;

        /**
         * Builds a movement.
         *
         * @param date when it was paid
         * @param amount how much was paid
         */
        public LastMovement(java.time.LocalDateTime date, BigDecimal amount) {
            this.date = date;
            this.amount = amount;
        }

        /**
         * Returns the date, for the template.
         *
         * @return the moment of the payment
         */
        public java.time.LocalDateTime getDate() {
            return date;
        }

        /**
         * Returns the amount, for the template.
         *
         * @return the amount paid
         */
        public BigDecimal getAmount() {
            return amount;
        }
    }

    /**
     * Shows the customer list, filtered ({@code BO-02-04-22}).
     *
     * @param text a fragment of the account number, business name or contact
     * @param taxId a fragment of the fiscal identifier (BO-02-04-18)
     * @param origin {@code REGISTER}, {@code INTEGRATED} or anything else for both
     * @param blocked {@code yes}, {@code no}, or anything else for both
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the list page
     */
    @GET
    @RolesAllowed("ADMIN")
    public TemplateInstance list(@QueryParam("text") String text,
                                 @QueryParam("taxId") String taxId,
                                 @QueryParam("origin") String origin,
                                 @QueryParam("blocked") String blocked,
                                 @QueryParam("page") @DefaultValue("1") int page,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list with text: " + text + ", taxId: " + taxId
                + ", origin: " + origin + ", blocked: " + blocked + ", page: " + page);
        List<AccountCustomer> matches = search(text, taxId, origin, blocked);
        LOGGER.info("Exiting method list with " + matches.size() + " customer(s)");
        return adminCustomers.data("page", AdminForms.page(matches, page))
                .data("baseQuery", AdminForms.pageParam("text", text)
                        + AdminForms.pageParam("taxId", taxId)
                        + AdminForms.pageParam("origin", origin)
                        + AdminForms.pageParam("blocked", blocked))
                // BO-10-04-05 : le magasin peut s'interdire d'ouvrir un compte.
                .data("creationBlocked", posSettingsService.accountCustomerCreationBlocked())
                .data("text", text == null ? "" : text)
                .data("taxId", taxId == null ? "" : taxId)
                .data("origin", origin == null ? "" : origin)
                .data("blocked", blocked == null ? "" : blocked)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Extracts the filtered list as CSV ({@code BO-02-04-18},
     * {@code BO-02-04-22}).
     *
     * @param text a fragment of the account number, business name or contact
     * @param taxId a fragment of the fiscal identifier
     * @param origin the origin filter
     * @param blocked the blocking filter
     * @return the CSV file, named after the day it was drawn
     */
    @GET
    @jakarta.ws.rs.Path("/export")
    @RolesAllowed("ADMIN")
    @Produces("text/csv; charset=UTF-8")
    public Response export(@QueryParam("text") String text,
                           @QueryParam("taxId") String taxId,
                           @QueryParam("origin") String origin,
                           @QueryParam("blocked") String blocked) {
        LOGGER.info("Entering method export");
        StringBuilder csv = new StringBuilder(
                "ACCOUNT_NUMBER;ORIGIN;COMPANY_NAME;CIVILITY;CONTACT;POSTAL_CODE;CITY;"
                        + "SIRET;VAT_NUMBER;TAX_ID;PHONE;EMAIL;BLOCKED;DUE_DATE;DISCOUNT_PERCENT;"
                        + "CREDIT_LIMIT;CREDIT_BALANCE\n");
        for (AccountCustomer customer : search(text, taxId, origin, blocked)) {
            csv.append(cell(customer.accountNumber)).append(';')
                    .append(cell(customer.origin == null ? null : customer.origin.name())).append(';')
                    .append(cell(customer.companyName)).append(';')
                    .append(cell(customer.civility)).append(';')
                    .append(cell(customer.getContactName())).append(';')
                    .append(cell(customer.address == null ? null : customer.address.postalCode)).append(';')
                    .append(cell(customer.address == null ? null : customer.address.city)).append(';')
                    .append(cell(customer.siret)).append(';')
                    .append(cell(customer.vatNumber)).append(';')
                    .append(cell(customer.taxId)).append(';')
                    .append(cell(customer.phone)).append(';')
                    .append(cell(customer.email)).append(';')
                    .append(customer.blocked ? "true" : "false").append(';')
                    .append(cell(customer.dueDate == null ? null : customer.dueDate.toString())).append(';')
                    .append(cell(customer.discountPercent == null ? null : customer.discountPercent.toPlainString())).append(';')
                    .append(cell(customer.creditLimit == null ? null : customer.creditLimit.toPlainString())).append(';')
                    .append(cell(customer.creditBalance == null ? null : customer.creditBalance.toPlainString()))
                    .append('\n');
        }
        LOGGER.info("Exiting method export");
        return Response.ok(csv.toString())
                .header("Content-Disposition",
                        "attachment; filename=\"clients-" + LocalDate.now() + ".csv\"")
                .build();
    }

    /**
     * Shows one customer's whole file ({@code BO-02-04-23}), with their last
     * credit payment ({@code BO-02-04-14}).
     *
     * @param id the customer id
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the file page; {@code customer} is null when the id is unknown
     */
    @GET
    @jakarta.ws.rs.Path("/edit")
    @RolesAllowed("ADMIN")
    public TemplateInstance edit(@QueryParam("id") Long id,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method edit with id: " + id);
        AccountCustomer customer = id != null ? AccountCustomer.<AccountCustomer>findById(id) : null;
        if (customer != null && customer.address == null) {
            customer.address = new Address();
        }
        LOGGER.info("Exiting method edit");
        return adminCustomer.data("customer", customer)
                .data("creating", false)
                .data("lastMovement", customer == null ? null : lastMovement(customer.accountNumber))
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Opens an EMPTY file, to create a customer in account by hand
     * ({@code BO-10-04-05}).
     *
     * <p>Refused outright when the shop has forbidden it: an account opened here
     * is a customer the commercial management has never heard of, and a shop that
     * has said it does not open accounts must not be shown a form that does.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the empty file page, or a 303 back to the list when it is forbidden
     */
    @GET
    @jakarta.ws.rs.Path("/new")
    @RolesAllowed("ADMIN")
    public Response create(@QueryParam("notice") String notice,
                           @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method create");
        if (posSettingsService.accountCustomerCreationBlocked()) {
            LOGGER.info("Exiting method create: forbidden");
            return redirect("/admin/customers",
                    "Création de compte client interdite par le paramétrage.", false);
        }
        AccountCustomer blank = new AccountCustomer();
        blank.address = new Address();
        LOGGER.info("Exiting method create");
        return Response.ok(adminCustomer.data("customer", blank)
                .data("creating", true)
                .data("lastMovement", null)
                .data("notice", notice)
                .data("noticeOk", noticeOk).render(), MediaType.TEXT_HTML).build();
    }

    /**
     * Writes the customer in account opened by hand ({@code BO-10-04-05}).
     *
     * <p>The account NUMBER is typed here and nowhere else: the register issues its
     * own for a volatile customer, the integration brings its own for an integrated
     * one, and this is the one road where a human names it — so this is the one
     * road that has to refuse a number already taken.
     *
     * @param form the posted form
     * @return a 303 to the new file, or back to the form with the refusal
     */
    @POST
    @jakarta.ws.rs.Path("/new")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response add(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method add");
        if (posSettingsService.accountCustomerCreationBlocked()) {
            LOGGER.info("Exiting method add: forbidden");
            return redirect("/admin/customers",
                    "Création de compte client interdite par le paramétrage.", false);
        }
        String accountNumber = blankToNull(form.getFirst("accountNumber"));
        if (accountNumber == null) {
            LOGGER.info("Exiting method add: no account number");
            return redirect("/admin/customers/new", "N° de compte obligatoire.", false);
        }
        String companyName = blankToNull(form.getFirst("companyName"));
        if (companyName == null) {
            LOGGER.info("Exiting method add: no business name");
            return redirect("/admin/customers/new", "Raison sociale obligatoire.", false);
        }
        if (AccountCustomer.find("accountNumber", accountNumber).firstResult() != null) {
            LOGGER.info("Exiting method add: duplicate account number");
            return redirect("/admin/customers/new",
                    "N° de compte déjà attribué : " + accountNumber, false);
        }
        String taxId = blankToNull(form.getFirst("taxId"));
        // BO-10-04-18 : un NIF ne peut désigner qu'un seul compte client.
        if (taxId != null && AccountCustomer.find("taxId", taxId).firstResult() != null) {
            LOGGER.info("Exiting method add: duplicate tax id");
            return redirect("/admin/customers/new", "N° fiscal déjà enregistré : " + taxId, false);
        }
        AccountCustomer customer = new AccountCustomer();
        customer.accountNumber = accountNumber;
        customer.origin = AccountCustomer.Origin.BACK_OFFICE;
        customer.companyName = companyName;
        customer.civility = blankToNull(form.getFirst("civility"));
        customer.lastName = blankToNull(form.getFirst("lastName"));
        customer.firstName = blankToNull(form.getFirst("firstName"));
        customer.address = new Address();
        customer.address.streetLine1 = blankToNull(form.getFirst("street"));
        customer.address.postalCode = blankToNull(form.getFirst("postalCode"));
        customer.address.city = blankToNull(form.getFirst("city"));
        customer.siret = blankToNull(form.getFirst("siret"));
        customer.vatNumber = blankToNull(form.getFirst("vatNumber"));
        customer.taxId = taxId;
        customer.phone = blankToNull(form.getFirst("phone"));
        customer.email = blankToNull(form.getFirst("email"));
        customer.blocked = form.getFirst("blocked") != null;
        customer.dueDate = dateValue(form.getFirst("dueDate"));
        customer.discountPercent = decimalValue(form.getFirst("discountPercent"));
        customer.creditLimit = decimalValue(form.getFirst("creditLimit"));
        customer.persist();
        LOGGER.infof("Compte client %s ouvert depuis le back-office", customer.accountNumber);
        LOGGER.info("Exiting method add");
        return redirect("/admin/customers/edit?id=" + customer.id, "Compte créé.", true);
    }

    /**
     * Saves the corrections made on an existing file ({@code BO-02-04-12}).
     *
     * <p>The account number and the origin are NOT read from the form: one is
     * the key the integration upserts on, the other says where the row came
     * from, and neither is a correction anybody makes by hand.
     *
     * @param form the posted form
     * @return a 303 back to the file with the notice
     */
    @POST
    @jakarta.ws.rs.Path("/edit")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save");
        Long id = longValue(form.getFirst("id"));
        AccountCustomer customer = id != null ? AccountCustomer.<AccountCustomer>findById(id) : null;
        if (customer == null) {
            LOGGER.info("Exiting method save: unknown customer");
            return redirect("/admin/customers", "Client inconnu.", false);
        }
        String companyName = blankToNull(form.getFirst("companyName"));
        if (companyName == null) {
            LOGGER.info("Exiting method save: no business name");
            return redirect("/admin/customers/edit?id=" + id, "Raison sociale obligatoire.", false);
        }
        customer.companyName = companyName;
        customer.civility = blankToNull(form.getFirst("civility"));
        customer.lastName = blankToNull(form.getFirst("lastName"));
        customer.firstName = blankToNull(form.getFirst("firstName"));
        if (customer.address == null) {
            customer.address = new Address();
        }
        customer.address.streetLine1 = blankToNull(form.getFirst("street"));
        customer.address.postalCode = blankToNull(form.getFirst("postalCode"));
        customer.address.city = blankToNull(form.getFirst("city"));
        customer.siret = blankToNull(form.getFirst("siret"));
        customer.vatNumber = blankToNull(form.getFirst("vatNumber"));
        customer.taxId = blankToNull(form.getFirst("taxId"));
        customer.phone = blankToNull(form.getFirst("phone"));
        customer.email = blankToNull(form.getFirst("email"));
        customer.blocked = form.getFirst("blocked") != null;
        customer.dueDate = dateValue(form.getFirst("dueDate"));
        customer.discountPercent = decimalValue(form.getFirst("discountPercent"));
        customer.creditLimit = decimalValue(form.getFirst("creditLimit"));
        customer.persist();
        LOGGER.infof("Fiche client %s corrigée depuis le back-office", customer.accountNumber);
        LOGGER.info("Exiting method save");
        return redirect("/admin/customers/edit?id=" + id, "Fiche enregistrée.", true);
    }

    /**
     * Runs the list search over the customers.
     *
     * <p>Filtered in memory and not in JPQL: four optional criteria, three of
     * them fragment matches on different columns, would make a query built by
     * string concatenation — which is how a referential screen ends up with an
     * injection or with a clause that binds an empty collection. The population
     * is a shop's customers in account, counted in hundreds.
     *
     * @param text a fragment of the account number, business name or contact
     * @param taxId a fragment of the fiscal identifier
     * @param origin the origin filter
     * @param blocked the blocking filter
     * @return the matching customers, ordered by business name
     */
    List<AccountCustomer> search(String text, String taxId, String origin, String blocked) {
        String needle = lower(text);
        String fiscal = lower(taxId);
        List<AccountCustomer> matches = new ArrayList<>();
        for (AccountCustomer customer
                : AccountCustomer.<AccountCustomer>find("order by companyName").list()) {
            if (needle != null && !matchesText(customer, needle)) {
                continue;
            }
            if (fiscal != null && (customer.taxId == null
                    || !customer.taxId.toLowerCase().contains(fiscal))) {
                continue;
            }
            if (origin != null && !origin.isBlank()
                    && (customer.origin == null || !customer.origin.name().equals(origin))) {
                continue;
            }
            if ("yes".equals(blocked) && !customer.blocked) {
                continue;
            }
            if ("no".equals(blocked) && customer.blocked) {
                continue;
            }
            matches.add(customer);
        }
        return matches;
    }

    /**
     * Says whether a customer's number, business name or contact carries a
     * fragment.
     *
     * @param customer the customer examined
     * @param needle the lower-case fragment, never null
     * @return true when one of the three carries it
     */
    private boolean matchesText(AccountCustomer customer, String needle) {
        return contains(customer.accountNumber, needle)
                || contains(customer.companyName, needle)
                || contains(customer.getContactName(), needle);
    }

    /**
     * Null-safe lower-case fragment match.
     *
     * @param value the value examined, possibly null
     * @param needle the lower-case fragment
     * @return true when the value carries the fragment
     */
    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    /**
     * Reads the last credit payment made by an account ({@code BO-02-04-14}).
     *
     * @param accountNumber the account number
     * @return the movement, or null when that account never paid on credit here
     */
    LastMovement lastMovement(String accountNumber) {
        LOGGER.info("Entering method lastMovement with accountNumber: " + accountNumber);
        if (accountNumber == null) {
            LOGGER.info("Exiting method lastMovement with null");
            return null;
        }
        List<Object[]> rows = entityManager.createQuery(
                        "select t.closingDate, p.amount"
                                + " from com.intermarche.pos.domain.payment.CreditPayment p,"
                                + " com.intermarche.pos.domain.sale.Ticket t"
                                + " where p member of t.payments and p.accountNumber = :account"
                                + " and t.closingDate is not null"
                                + " order by t.closingDate desc", Object[].class)
                .setParameter("account", accountNumber)
                .setMaxResults(1)
                .getResultList();
        if (rows.isEmpty()) {
            LOGGER.info("Exiting method lastMovement with null");
            return null;
        }
        Object[] row = rows.get(0);
        LOGGER.info("Exiting method lastMovement");
        return new LastMovement((java.time.LocalDateTime) row[0], (BigDecimal) row[1]);
    }

    /**
     * Renders one CSV cell, the separator neutralized.
     *
     * @param value the value, possibly null
     * @return the cell, empty for null
     */
    private String cell(String value) {
        return value == null ? "" : value.replace(';', ',').replace('\n', ' ');
    }

    /**
     * Lower-cases a criterion, a blank one counting as absent.
     *
     * @param value the criterion, possibly null or blank
     * @return the lower-case fragment, or null when nothing was asked
     */
    private String lower(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase();
    }

    /**
     * Reads a posted text, a blank one counting as absent.
     *
     * @param value the posted value, possibly null or blank
     * @return the trimmed value, or null
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Reads a posted identifier.
     *
     * @param value the posted value, possibly null or unreadable
     * @return the identifier, or null
     */
    private Long longValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Reads a posted decimal, an unreadable one counting as none.
     *
     * @param value the posted value, possibly null or unreadable
     * @return the decimal, or null
     */
    private BigDecimal decimalValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim().replace(',', '.'));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Reads a posted ISO date, an unreadable one counting as none.
     *
     * @param value the posted value, possibly null or unreadable
     * @return the date, or null
     */
    private LocalDate dateValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException notADate) {
            return null;
        }
    }

    /**
     * Builds the 303 that carries the outcome message.
     *
     * @param path where to go back to
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response redirect(String path, String notice, boolean ok) {
        String separator = path.contains("?") ? "&" : "?";
        return Response.seeOther(URI.create(path + separator + "notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8) + "&noticeOk=" + ok)).build();
    }
}
