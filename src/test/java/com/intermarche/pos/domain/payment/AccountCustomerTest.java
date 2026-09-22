package com.intermarche.pos.domain.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AccountCustomer}.
 * <p>
 * The class is a plain Panache domain entity with no static finder, so it is
 * exercised directly: {@code new AccountCustomer()} and public-field
 * assignment, no {@link org.mockito.Mockito#mockStatic} needed. The coverage
 * target is the business logic — the credit ceiling, the display/contact name
 * assembly and the change-detection checksum — where every null guard and
 * ternary has both arms driven, including the null-versus-blank distinction on
 * the contact fields and the null-versus-present address in the checksum.
 */
class AccountCustomerTest {

    /**
     * Builds a customer carrying only the two mandatory business fields, the
     * common starting point of the name and credit tests.
     *
     * @return a minimal account customer
     */
    private AccountCustomer minimal() {
        AccountCustomer customer = new AccountCustomer();
        customer.accountNumber = "A1";
        customer.companyName = "Acme";
        return customer;
    }

    /**
     * Proves {@code isCreditAllowed} returns true when a ceiling is present
     * (the non-null arm of the {@code creditLimit != null} guard).
     */
    @Test
    void isCreditAllowedTrueWhenLimitPresent() {
        AccountCustomer customer = minimal();
        customer.creditLimit = new BigDecimal("100.00");
        assertTrue(customer.isCreditAllowed());
    }

    /**
     * A BLOCKED account is refused credit even with a ceiling: the other leg of
     * the {@code creditLimit != null && !blocked} guard, and the whole point of
     * the blocking (BO-02-04-06).
     */
    @Test
    void isCreditAllowedFalseWhenBlocked() {
        AccountCustomer customer = minimal();
        customer.creditLimit = new BigDecimal("100.00");
        customer.blocked = true;
        assertFalse(customer.isCreditAllowed());
        assertEquals(BigDecimal.ZERO, customer.getCreditAvailable());
    }

    /**
     * A blocked account with NO ceiling is refused too: both legs true at once.
     */
    @Test
    void isCreditAllowedFalseWhenBlockedAndWithoutLimit() {
        AccountCustomer customer = minimal();
        customer.blocked = true;
        assertFalse(customer.isCreditAllowed());
    }

    /**
     * A customer is a REGISTER one until something says otherwise
     * (BO-02-04-22).
     */
    @Test
    void aCustomerIsARegisterOneByDefault() {
        assertEquals(AccountCustomer.Origin.REGISTER, minimal().origin);
    }

    /**
     * The free fields enter the checksum in SLOT ORDER, so a customer whose
     * ninetieth field changed is seen as changed, and a customer whose fields
     * merely came back in another order is not (BO-02-04-11).
     */
    @Test
    void theFreeFieldsEnterTheChecksumInSlotOrder() {
        AccountCustomer one = minimal();
        one.freeFields.add(new AccountCustomer.FreeField(2, "Tournée", "B"));
        one.freeFields.add(new AccountCustomer.FreeField(1, "Zone", "N"));
        AccountCustomer other = minimal();
        other.freeFields.add(new AccountCustomer.FreeField(1, "Zone", "N"));
        other.freeFields.add(new AccountCustomer.FreeField(2, "Tournée", "B"));
        assertEquals(one.getChecksum(), other.getChecksum());
        other.freeFields.get(0).value = "S";
        assertNotEquals(one.getChecksum(), other.getChecksum());
    }

    /**
     * A customer carrying no free field, and one whose list is null, hash the
     * same: both arms of the digest's guard.
     */
    @Test
    void theFreeFieldsDigestToleratesAnEmptyAndANullList() {
        AccountCustomer empty = minimal();
        AccountCustomer none = minimal();
        none.freeFields = null;
        assertEquals(empty.getChecksum(), none.getChecksum());
    }

    /**
     * A free field renders its slot, its label and its value, the null parts
     * rendered empty — both arms of each of its two ternaries.
     */
    @Test
    void aFreeFieldRendersItsThreeParts() {
        assertEquals("7=Zone=Nord", new AccountCustomer.FreeField(7, "Zone", "Nord").toString());
        assertEquals("7==", new AccountCustomer.FreeField(7, null, null).toString());
        AccountCustomer.FreeField empty = new AccountCustomer.FreeField();
        assertEquals("0==", empty.toString());
    }

    /**
     * Proves {@code isCreditAllowed} returns false when no ceiling was granted
     * (the null arm of the {@code creditLimit != null} guard): null is a
     * refusal, not a zero.
     */
    @Test
    void isCreditAllowedFalseWhenLimitNull() {
        AccountCustomer customer = minimal();
        customer.creditLimit = null;
        assertFalse(customer.isCreditAllowed());
    }

    /**
     * Proves {@code getCreditAvailable} returns zero when no credit is granted
     * (the {@code creditLimit == null} true arm), short-circuiting the balance.
     */
    @Test
    void getCreditAvailableZeroWhenNoCredit() {
        AccountCustomer customer = minimal();
        customer.creditLimit = null;
        assertEquals(BigDecimal.ZERO, customer.getCreditAvailable());
    }

    /**
     * Proves the balance null guard uses zero (the true arm of the
     * {@code creditBalance == null} ternary): the full ceiling stays available.
     */
    @Test
    void getCreditAvailableFullCeilingWhenBalanceNull() {
        AccountCustomer customer = minimal();
        customer.creditLimit = new BigDecimal("100.00");
        customer.creditBalance = null;
        assertEquals(new BigDecimal("100.00"), customer.getCreditAvailable());
    }

    /**
     * Proves the available credit is the ceiling minus a present outstanding
     * balance (the {@code creditLimit == null} false arm and the
     * {@code creditBalance == null} false arm, ceiling above balance).
     */
    @Test
    void getCreditAvailableCeilingMinusBalance() {
        AccountCustomer customer = minimal();
        customer.creditLimit = new BigDecimal("100.00");
        customer.creditBalance = new BigDecimal("30.00");
        assertEquals(new BigDecimal("70.00"), customer.getCreditAvailable());
    }

    /**
     * Proves the available credit is floored at zero when the balance exceeds
     * the ceiling (the {@code max(ZERO)} taking the zero side).
     */
    @Test
    void getCreditAvailableFlooredAtZeroWhenOverspent() {
        AccountCustomer customer = minimal();
        customer.creditLimit = new BigDecimal("100.00");
        customer.creditBalance = new BigDecimal("150.00");
        assertEquals(BigDecimal.ZERO, customer.getCreditAvailable());
    }

    /**
     * Proves {@code getDisplayName} returns the bare company name when there is
     * no contact (the {@code contact.isEmpty()} true arm of the ternary).
     */
    @Test
    void getDisplayNameBusinessAloneShowsCompanyOnly() {
        AccountCustomer customer = minimal();
        customer.firstName = null;
        customer.lastName = null;
        assertEquals("Acme", customer.getDisplayName());
    }

    /**
     * Proves {@code getDisplayName} appends the contact in parentheses when one
     * exists (the {@code contact.isEmpty()} false arm of the ternary).
     */
    @Test
    void getDisplayNameWithContactAppendsParenthesised() {
        AccountCustomer customer = minimal();
        customer.firstName = "John";
        customer.lastName = "Doe";
        assertEquals("Acme (John Doe)", customer.getDisplayName());
    }

    /**
     * Proves the contact name is empty when both parts are null (the null true
     * arms of both name ternaries and the {@code first.isEmpty()} true arm,
     * which returns the equally empty last name).
     */
    @Test
    void getContactNameEmptyWhenBothNull() {
        AccountCustomer customer = minimal();
        customer.firstName = null;
        customer.lastName = null;
        assertEquals("", customer.getContactName());
    }

    /**
     * Proves the contact name is the first name alone when the last name is
     * null (the {@code firstName == null} false arm, the
     * {@code lastName == null} true arm, {@code first.isEmpty()} false and
     * {@code last.isEmpty()} true, returning the first name).
     */
    @Test
    void getContactNameFirstOnlyWhenLastNull() {
        AccountCustomer customer = minimal();
        customer.firstName = "John";
        customer.lastName = null;
        assertEquals("John", customer.getContactName());
    }

    /**
     * Proves the contact name is the last name alone when the first name is
     * null but the last is present (the {@code firstName == null} true arm, the
     * {@code lastName == null} false arm and the {@code first.isEmpty()} true
     * arm returning the last name).
     */
    @Test
    void getContactNameLastOnlyWhenFirstNull() {
        AccountCustomer customer = minimal();
        customer.firstName = null;
        customer.lastName = "Doe";
        assertEquals("Doe", customer.getContactName());
    }

    /**
     * Proves both names are joined with a space when both are present (the
     * {@code last.isEmpty()} false arm producing the joined form).
     */
    @Test
    void getContactNameJoinsWhenBothPresent() {
        AccountCustomer customer = minimal();
        customer.firstName = "John";
        customer.lastName = "Doe";
        assertEquals("John Doe", customer.getContactName());
    }

    /**
     * Proves blank (whitespace) names trim to empty and yield an empty contact
     * name — the non-null arms of both ternaries feeding the trim, with the
     * {@code first.isEmpty()} true arm reached on a non-null value.
     */
    @Test
    void getContactNameEmptyWhenBothBlank() {
        AccountCustomer customer = minimal();
        customer.firstName = "  ";
        customer.lastName = "  ";
        assertEquals("", customer.getContactName());
    }

    /**
     * Proves {@code getChecksum} hashes the salient business fields including
     * the address parts when an address is present (the false arm of each of
     * the three {@code address == null} ternaries).
     */
    @Test
    void getChecksumHashesAddressPartsWhenPresent() {
        AccountCustomer customer = minimal();
        customer.lastName = "Doe";
        customer.firstName = "John";
        customer.siret = "S";
        customer.vatNumber = "V";
        customer.phone = "P";
        customer.email = "E";
        customer.creditLimit = BigDecimal.TEN;
        customer.creditBalance = BigDecimal.ONE;
        customer.address.streetLine1 = "street";
        customer.address.postalCode = "75000";
        customer.address.city = "Paris";
        int expected = Objects.hash("A1", "Acme", AccountCustomer.Origin.REGISTER, null,
                "Doe", "John", "S", "V", null, null, "P", "E",
                BigDecimal.TEN, BigDecimal.ONE, false, null, null, "",
                "street", "75000", "Paris");
        assertEquals(expected, customer.getChecksum());
    }

    /**
     * Proves {@code getChecksum} substitutes null for the three address parts
     * when the address itself is null (the true arm of each of the three
     * {@code address == null} ternaries).
     */
    @Test
    void getChecksumUsesNullAddressPartsWhenAddressNull() {
        AccountCustomer customer = minimal();
        customer.lastName = "Doe";
        customer.firstName = "John";
        customer.siret = "S";
        customer.vatNumber = "V";
        customer.phone = "P";
        customer.email = "E";
        customer.creditLimit = BigDecimal.TEN;
        customer.creditBalance = BigDecimal.ONE;
        customer.address = null;
        int expected = Objects.hash("A1", "Acme", AccountCustomer.Origin.REGISTER, null,
                "Doe", "John", "S", "V", null, null, "P", "E",
                BigDecimal.TEN, BigDecimal.ONE, false, null, null, "",
                null, null, null);
        assertEquals(expected, customer.getChecksum());
    }
}
