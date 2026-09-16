package com.intermarche.pos.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;

/**
 * A customer-credit settlement ({@code LC-07-09-01}): the goods leave, the money
 * does not.
 *
 * <p>Unlike every other payment method, NOTHING IS COLLECTED at the till. What the
 * register does is name a debtor — a professional, an association, a town hall —
 * and the debt is settled monthly in the commercial management. So the account is
 * not a detail of the payment, it IS the payment: a credit line without an account
 * number states that someone owes money without saying who, which is worth nothing
 * to the accountant reading the ticket a month later.
 *
 * <p>The account NAME is carried alongside the number, deliberately duplicated from
 * the customer referential: {@code LC-07-09-06} prints it on the receipt, and the
 * receipt states the sale AS IT WAS — an account renamed or closed six months later
 * must not rewrite the paper the customer already holds.
 */
@Entity
@DiscriminatorValue("CREDIT")
public class CreditPayment extends TicketPayment {

    /**
     * The account number the debt is charged to. Nullable on the shared payment
     * table, so the other payment methods leave the column untouched.
     */
    @Column(name = "credit_account_number", length = 30)
    public String accountNumber;

    /** The account name as it stood at sale time, printed on the receipt. */
    @Column(name = "credit_account_name", length = 120)
    public String accountName;

    /**
     * Whether a supervisor authorized this settlement over the account's ceiling
     * ({@code LC-07-09-04}). Kept on the payment because it is a fact of the sale:
     * the shop must be able to tell, later, which credit lines went through on an
     * override and which did not.
     */
    @Column(name = "credit_over_limit")
    public boolean overLimit = false;

    /**
     * Default constructor for JPA.
     */
    protected CreditPayment() {}

    /**
     * Creates a credit settlement with its applied amount.
     *
     * @param amount the amount charged to the account
     */
    public CreditPayment(BigDecimal amount) { super(amount); }

    /**
     * Returns the audit checksum of the settlement, folding the debtor in: two
     * credit lines of the same amount charged to two different accounts are not
     * the same fact.
     *
     * @return a hash of the index, amount, account and override flag
     */
    @Override
    public int getChecksum() {
        return java.util.Objects.hash(super.getChecksum(), accountNumber, accountName, overLimit);
    }

    /**
     * Factory discovered by CDI under the "CREDIT" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the customer-credit method.
         *
         * @return the method key "CREDIT"
         */
        @Override
        public String getKey() { return "CREDIT"; }

        /**
         * Creates a customer-credit payment entity.
         *
         * <p>The account is NOT set here: this factory is the generic
         * amount-and-tendered contract shared by every method, and the caller that
         * knows the debtor sets it right after. A factory signature widened for one
         * method would have to be widened for the next one too.
         *
         * @param amount the amount charged to the account
         * @param tendered ignored (nothing is collected at the till)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new CreditPayment(amount);
        }
    }
}
