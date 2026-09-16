package com.intermarche.pos.domain.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.Objects;
import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.BaseEntity;

/**
 * A professional customer, the party an invoice is addressed to.
 * <p>
 * "Client en compte" in the questionnaire: a business the store sells to over the
 * counter and bills, as opposed to the anonymous shopper a ticket belongs to. The
 * loyalty programme is NOT this: it carries pseudonymous card holders, not businesses
 * with a SIRET and a credit limit, and nothing of it is reusable here.
 * <p>
 * At this stage the register CREATES these customers and nothing else does. The
 * questionnaire ({@code LC-08-04-08}) distinguishes two populations — the customer
 * sent by the commercial-management system and the one created at the register — and
 * the second is the one that needs no integration. The fields the first population
 * adds (credit limit, blocking flag, due date, outstanding balance, code range) come
 * with that integration, not before.
 * <p>
 * Fields are public to comply with the project's Panache conventions.
 */
@Entity
@Table(name = "account_customers",
        indexes = {
                @Index(name = "idx_account_customer_number", columnList = "account_number", unique = true),
                @Index(name = "idx_account_customer_name", columnList = "company_name")
        }
)
public class AccountCustomer extends BaseEntity {

    /**
     * The account number, unique in this register's database.
     * <p>
     * Issued by the register for a customer created here. The administered code
     * ranges of {@code BO-03-06-53} will constrain how it is built; until then it
     * carries the register's own sequence, which is unique and never collides with
     * a number coming from elsewhere because nothing comes from elsewhere yet.
     */
    @Column(name = "account_number", nullable = false, unique = true, length = 30)
    @NotBlank
    public String accountNumber;

    /** The business name, printed as the invoice's addressee. */
    @Column(name = "company_name", nullable = false, length = 120)
    @NotBlank
    public String companyName;

    /** The contact's last name, blank when the customer is a business alone. */
    @Column(name = "last_name", length = 60)
    public String lastName;

    /** The contact's first name, blank when the customer is a business alone. */
    @Column(name = "first_name", length = 60)
    public String firstName;

    /** The billing address. */
    @Embedded
    public Address address = new Address();

    /** The SIRET, printed on the invoice when the customer gave one. */
    @Column(name = "siret", length = 20)
    public String siret;

    /** The intra-community VAT number, printed when the customer gave one. */
    @Column(name = "vat_number", length = 20)
    public String vatNumber;

    /** The telephone number, kept for the invoice report of {@code BO-06-03-05}. */
    @Column(name = "phone", length = 30)
    public String phone;

    /** The electronic address, kept for the same report. */
    @Column(name = "email", length = 120)
    public String email;

    /**
     * The credit ceiling administered by the commercial-management system
     * ({@code LC-07-09-03}), or null when this account may not settle on credit.
     *
     * <p>NULL IS A REFUSAL, not a zero: an account the back office has never
     * granted credit to must not be payable on credit by default, and a ceiling
     * of zero is a granted-then-suspended account, which is a different thing to
     * say to the cashier.
     */
    @Column(name = "credit_limit", precision = 12, scale = 2)
    public BigDecimal creditLimit;

    /**
     * What this account already owes ({@code LC-07-09-03}), as the commercial
     * management last stated it.
     *
     * <p>The register ADDS its own credit settlements to it so that two sales in
     * a row at the same till cannot both slip under the ceiling, but the figure
     * remains the shop's, not the register's: the next referential pull overwrites
     * it, and that is deliberate — the debt is settled monthly in the commercial
     * management, which is the only place that knows it has been paid.
     */
    @Column(name = "credit_balance", precision = 12, scale = 2, nullable = false)
    public BigDecimal creditBalance = BigDecimal.ZERO;

    /**
     * Tells whether this account may settle on credit at all.
     *
     * @return true when the back office granted the account a ceiling
     */
    public boolean isCreditAllowed() {
        return creditLimit != null;
    }

    /**
     * Returns what this account may still spend on credit, floored at zero.
     *
     * @return the ceiling minus the outstanding balance, or zero when no credit
     *         is granted or the balance already reaches the ceiling
     */
    public BigDecimal getCreditAvailable() {
        if (creditLimit == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal outstanding = creditBalance == null ? BigDecimal.ZERO : creditBalance;
        return creditLimit.subtract(outstanding).max(BigDecimal.ZERO);
    }

    /**
     * Returns the display name of the customer: the business, then the contact
     * when there is one.
     *
     * @return the name shown on screen and printed on the document
     */
    public String getDisplayName() {
        String contact = contactName();
        return contact.isEmpty() ? companyName : companyName + " (" + contact + ")";
    }

    /**
     * Returns the contact's name, empty when the customer is a business alone.
     *
     * @return the contact's first and last name, or an empty string
     */
    public String getContactName() {
        return contactName();
    }

    /**
     * Joins the contact's first and last names, ignoring the blank ones.
     *
     * @return the joined name, empty when both parts are missing
     */
    private String contactName() {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        if (first.isEmpty()) {
            return last;
        }
        if (last.isEmpty()) {
            return first;
        }
        return first + " " + last;
    }

    /**
     * Hashes the business fields, for the change detection of the base entity.
     *
     * @return the checksum of this customer
     */
    @Override
    public int getChecksum() {
        return Objects.hash(accountNumber, companyName, lastName, firstName, siret, vatNumber,
                phone, email, creditLimit, creditBalance,
                address == null ? null : address.streetLine1,
                address == null ? null : address.postalCode,
                address == null ? null : address.city);
    }
}
