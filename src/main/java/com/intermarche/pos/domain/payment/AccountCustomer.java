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
 * Two populations live here, as the questionnaire ({@code LC-08-04-08}) says they
 * must: the customer SENT BY THE COMMERCIAL MANAGEMENT ({@code BO-02-04-01}) and the
 * one created at the register. {@link #origin} says which, because the back office has
 * to consult both and tell them apart ({@code BO-02-04-22}), and they are not
 * administered the same way — the sent one is written by the nightly integration and
 * only corrected in the back office ({@code BO-02-04-12}), the register's own is the
 * register's.
 * <p>
 * The fields the commercial management administers — civility, blocking, due date,
 * discount, tax identifier, credit ceiling and outstanding balance, and the ninety-nine
 * free fields — are carried here and OVERWRITTEN at each integration. Nothing computed
 * at the register is kept among them: the date of the customer's last payment, which
 * the back office shows beside them ({@code BO-02-04-14}), is read from the tickets,
 * because a figure that can be read must not be a figure that can go stale.
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

    /** Where a customer came from, which is not the same as who may change it. */
    public enum Origin {

        /** Created at a register while an invoice was being issued (LC-08-04-08). */
        REGISTER,

        /** Sent by the commercial management's integration (BO-02-04-01). */
        INTEGRATED,

        /**
         * A PASSING customer opened at the till to carry one document
         * ({@code BO-10-04-01}).
         *
         * <p>Not a customer in account and never one: no credit, no ceiling, no
         * terms. The shop needs a name and an address to print an invoice for
         * someone who will not come back, and calling that an account would put
         * them in the population the commercial management believes it owns.
         */
        VOLATILE,

        /**
         * Opened by hand in the back office ({@code BO-10-04-05}).
         *
         * <p>A customer in account like an integrated one, but one the commercial
         * management has never heard of: the shop that opens it takes on saying so
         * upstream, which is exactly why the shop may be forbidden to open one.
         */
        BACK_OFFICE
    }

    /**
     * Where this customer came from ({@code BO-02-04-22}).
     *
     * <p>Defaults to {@code REGISTER}, which is what every row written by
     * anything other than the integration is: the integration is the one place
     * that states otherwise, and a row it never sent must not be taken for one
     * of its own.
     */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 16)
    public Origin origin = Origin.REGISTER;

    /** The business name, printed as the invoice's addressee. */
    @Column(name = "company_name", nullable = false, length = 120)
    @NotBlank
    public String companyName;

    /**
     * The contact's civility as the commercial management states it — « M. »,
     * « Mme », « Dr »… ({@code BO-02-04-02}).
     *
     * <p>Free text and not an enumeration: the list belongs to the commercial
     * management, differs by country, and a register that refused an unknown
     * civility would refuse a customer over a courtesy title.
     */
    @Column(name = "civility", length = 12)
    public String civility;

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
     * The fiscal identifier (NIF), for the countries that bill on it
     * ({@code BO-02-04-18}, {@code BO-02-04-20}).
     *
     * <p>Held beside the SIRET and the VAT number rather than in their place:
     * a Portuguese customer carries a NIF and no SIRET, a French one the
     * reverse, and an invoice that printed one for the other would be wrong in
     * both countries.
     */
    @Column(name = "tax_id", length = 30)
    public String taxId;

    /**
     * True when the commercial management has BLOCKED this account at the
     * register ({@code BO-02-04-06}).
     *
     * <p>A blocked account is not a deleted one: it still exists, is still
     * shown, still carries its history — it simply may not settle on credit
     * any more. The shop decides that, not the till.
     */
    @Column(name = "blocked", nullable = false)
    public boolean blocked = false;

    /**
     * The commercial SEGMENT the commercial management put this customer in
     * ({@code BO-10-04-07}).
     *
     * <p>What a segment means belongs upstream; the register only asks whether
     * this customer is in the one the shop administered as carrying the
     * discount. Free text for that reason — a closed list here would refuse a
     * segment the group invents next quarter.
     */
    @Column(name = "segment", length = 40)
    public String segment;

    /**
     * The date the account's outstanding balance falls due
     * ({@code BO-02-04-08}), or null when the commercial management sent none.
     *
     * <p>The requirement asks for it on every invoice and delivery note issued
     * at the register, which is why it travels down to the tills rather than
     * staying in the back office.
     */
    @Column(name = "due_date")
    public java.time.LocalDate dueDate;

    /**
     * The discount rate granted to this account, in percent
     * ({@code BO-02-04-09}), or null when none is granted.
     *
     * <p>Null and zero are the same discount and a different statement: null is
     * an account the commercial management never granted a rate to, zero is one
     * whose rate was set back to nothing.
     */
    @Column(name = "discount_percent", precision = 5, scale = 2)
    public BigDecimal discountPercent;

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
     * The reserve of free fields the commercial management fills as it likes
     * ({@code BO-02-04-11}): a slot, a label and an alphanumeric value.
     *
     * <p>Modelled as rows and not as ninety-nine columns. A table ninety-nine
     * columns wide would be empty on almost every customer, would have to be
     * migrated the day a hundredth is asked for, and would give the screens no
     * label to show. The slot is what the commercial management numbers them
     * by; the label is what a human reads.
     */
    @jakarta.persistence.ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @jakarta.persistence.CollectionTable(name = "account_customer_fields",
            joinColumns = @jakarta.persistence.JoinColumn(name = "customer_id"))
    @jakarta.persistence.OrderBy("slot")
    public java.util.List<FreeField> freeFields = new java.util.ArrayList<>();

    /**
     * One of the free fields of {@code BO-02-04-11}.
     *
     * <p>Nested in its owner rather than living on its own: it has no meaning
     * away from the customer that carries it, and no other part of the model
     * ever looks one up.
     */
    @jakarta.persistence.Embeddable
    public static class FreeField {

        /** The slot the commercial management numbers this field by, 1 to 99. */
        @Column(name = "slot", nullable = false)
        public int slot;

        /** What a human reads in front of the value. */
        @Column(name = "label", length = 60)
        public String label;

        /**
         * The value, alphanumeric and interpreted by nobody here.
         *
         * <p>The column is {@code field_value} and not {@code value}: VALUE is
         * a reserved word of the SQL standard, which H2 2.x enforces, so a
         * column named after the Java field would stop the schema creation
         * before the application ever starts. The field keeps its own name —
         * nothing reads this column by its name, neither a query nor an import.
         */
        @Column(name = "field_value", length = 120)
        public String value;

        /**
         * Creates an empty field, as the persistence layer requires.
         */
        public FreeField() {
        }

        /**
         * Creates a filled field.
         *
         * @param slot the slot number
         * @param label the label shown in front of the value
         * @param value the value
         */
        public FreeField(int slot, String label, String value) {
            this.slot = slot;
            this.label = label;
            this.value = value;
        }

        /**
         * Renders the field the way a screen and a fingerprint both read it.
         *
         * @return {@code slot=label=value}, the null parts rendered empty
         */
        @Override
        public String toString() {
            return slot + "=" + (label == null ? "" : label) + "=" + (value == null ? "" : value);
        }
    }

    /**
     * Tells whether this account may settle on credit at all.
     *
     * <p>Two conditions, both required: a ceiling was granted, and the account
     * is not blocked ({@code BO-02-04-06}). A blocked account with a ceiling is
     * exactly the case the blocking exists for.
     *
     * @return true when the back office granted the account a ceiling and has
     *         not blocked it
     */
    public boolean isCreditAllowed() {
        return creditLimit != null && !blocked;
    }

    /**
     * Returns what this account may still spend on credit, floored at zero.
     *
     * <p>A BLOCKED account may spend nothing, whatever its ceiling says: the
     * screens read this figure to tell the cashier what is left, and telling
     * them a blocked account has room would be a lie they would act on
     * ({@code BO-02-04-06}).
     *
     * @return the ceiling minus the outstanding balance, or zero when no credit
     *         is granted, the account is blocked, or the balance already
     *         reaches the ceiling
     */
    public BigDecimal getCreditAvailable() {
        if (!isCreditAllowed()) {
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
        return Objects.hash(accountNumber, companyName, origin, civility, lastName, firstName, siret,
                vatNumber, taxId, segment, phone, email, creditLimit, creditBalance,
                blocked, dueDate, discountPercent, freeFieldsDigest(),
                address == null ? null : address.streetLine1,
                address == null ? null : address.postalCode,
                address == null ? null : address.city);
    }

    /**
     * Renders the free fields in a stable order, so a customer whose ninetieth
     * field changed is seen as changed.
     *
     * @return the joined rendering, empty when the customer carries none
     */
    private String freeFieldsDigest() {
        if (freeFields == null || freeFields.isEmpty()) {
            return "";
        }
        return freeFields.stream()
                .sorted(java.util.Comparator.comparingInt(field -> field.slot))
                .map(FreeField::toString)
                .collect(java.util.stream.Collectors.joining(";"));
    }
}
