package com.intermarche.pos.imports;

import com.intermarche.pos.domain.payment.AccountCustomer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountCustomerCsvResource}.
 * <p>
 * The importer's staged transaction machinery belongs to
 * {@link ImporterCsvResource} and is tested there; what is exercised here is
 * what this subclass adds — the full/delta decision, the blocking of the
 * accounts a full feed does not carry, and the reading of the free-field
 * reserve out of the file's header. Rows are built as plain {@link
 * ImporterCsvResource.LineData} against a hand-made header, and the one static
 * finder involved is intercepted with {@link org.mockito.Mockito#mockStatic}:
 * no Quarkus context and no database is booted.
 * <p>
 * Branch enumeration (every arm exercised): {@code isFull} — null, another
 * word, {@code full} and {@code FULL} with spaces; {@code blockAbsentAccounts}
 * — the empty-set arm and the populated arm; {@code freeFields} — a column that
 * is not a free field, one whose slot is not a number, one whose slot is out of
 * the 1..99 range, a pair whose two cells are empty, a pair carrying only a
 * label, one carrying only a value, and the ordering by slot.
 */
class AccountCustomerCsvResourceTest {

    /** The resource under test. */
    private final AccountCustomerCsvResource resource = new AccountCustomerCsvResource();

    /**
     * Builds a row from a header and its cells, in the order given.
     *
     * @param columns the header names, in column order
     * @param cells the cells of the row, in the same order
     * @return the parsed row
     */
    private ImporterCsvResource.LineData row(List<String> columns, String... cells) {
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            header.put(columns.get(index), index);
        }
        return new ImporterCsvResource.LineData(1, header, cells,
                AccountCustomerCsvResource.COL_ACCOUNT_NUMBER);
    }

    /**
     * Nothing, or any other word, is the evening's delta.
     */
    @Test
    void anythingButFullIsADelta() {
        assertFalse(resource.isFull(null));
        assertFalse(resource.isFull(""));
        assertFalse(resource.isFull("delta"));
    }

    /**
     * The word {@code full} declares the feed complete, whatever its case and
     * its surrounding spaces.
     */
    @Test
    void theWordFullDeclaresACompleteFeed() {
        assertTrue(resource.isFull("full"));
        assertTrue(resource.isFull("  FULL "));
    }

    /**
     * A full feed BLOCKS the accounts it does not carry — it does not delete
     * them, because the invoices that name them outlive the account.
     */
    @Test
    void afullFeedBlocksTheAccountsItDoesNotCarry() {
        AccountCustomer absent = mock(AccountCustomer.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.list(
                            "blocked = false and accountNumber not in ?1", Set.of("A1")))
                    .thenReturn(List.of(absent));
            assertEquals(1L, resource.blockAbsentAccounts(Set.of("A1")));
        }
        assertTrue(absent.blocked);
        verify(absent).persist();
    }

    /**
     * An EMPTY full feed blocks every account: the {@code not in ()} a bound
     * empty collection would produce is rejected at run time by the database,
     * so the query without the clause is the one that is issued.
     */
    @Test
    void anEmptyFullFeedBlocksEveryAccount() {
        AccountCustomer absent = mock(AccountCustomer.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.list("blocked = false"))
                    .thenReturn(List.of(absent));
            assertEquals(1L, resource.blockAbsentAccounts(Set.of()));
        }
        verify(absent).persist();
    }

    /**
     * A feed where nothing left the population writes nothing.
     */
    @Test
    void afullFeedCarryingEveryAccountWritesNothing() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.list(
                            "blocked = false and accountNumber not in ?1", Set.of("A1")))
                    .thenReturn(List.of());
            assertEquals(0L, resource.blockAbsentAccounts(Set.of("A1")));
        }
    }

    /**
     * The free fields are read out of the header, in slot order, whatever the
     * order the columns are declared in.
     */
    @Test
    void theFreeFieldsAreReadInSlotOrder() {
        List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                "FREE_2_LABEL", "FREE_2_VALUE", "FREE_1_LABEL", "FREE_1_VALUE");
        List<AccountCustomer.FreeField> fields =
                resource.freeFields(row(columns, "A1", "Tournée", "B", "Zone", "Nord"));
        assertEquals(2, fields.size());
        assertEquals(1, fields.get(0).slot);
        assertEquals("Zone", fields.get(0).label);
        assertEquals("Nord", fields.get(0).value);
        assertEquals(2, fields.get(1).slot);
        assertEquals("Tournée", fields.get(1).label);
        assertEquals("B", fields.get(1).value);
    }

    /**
     * A pair whose two cells are empty is NOT a field: a feed declaring the
     * ninety-nine columns for every customer would otherwise give each of them
     * ninety-nine empty rows. A pair carrying only one of the two IS one.
     */
    @Test
    void anEmptyPairIsNotAField() {
        List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                "FREE_1_LABEL", "FREE_1_VALUE", "FREE_2_LABEL", "FREE_2_VALUE",
                "FREE_3_LABEL", "FREE_3_VALUE");
        List<AccountCustomer.FreeField> fields =
                resource.freeFields(row(columns, "A1", "", "", "Zone", "", "", "Nord"));
        assertEquals(2, fields.size());
        assertEquals(2, fields.get(0).slot);
        assertEquals("Zone", fields.get(0).label);
        assertEquals(3, fields.get(1).slot);
        assertEquals("Nord", fields.get(1).value);
    }

    /**
     * A column that is not a free-field pair is left alone, and so is one whose
     * slot is not a number or falls outside the reserve of ninety-nine.
     */
    @Test
    void onlyWellFormedSlotsAreRead() {
        List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                AccountCustomerCsvResource.COL_COMPANY_NAME, "FREE_2_VALUE",
                "FREE_X_LABEL", "FREE_0_LABEL", "FREE_100_LABEL", "FREE_5_LABEL");
        List<AccountCustomer.FreeField> fields =
                resource.freeFields(row(columns, "A1", "Acme", "B", "x", "zero", "cent", "Zone"));
        assertEquals(1, fields.size());
        assertEquals(5, fields.get(0).slot);
        assertEquals("Zone", fields.get(0).label);
    }

    /**
     * A file declaring no free-field column yields none.
     */
    @Test
    void afileWithoutFreeColumnsYieldsNone() {
        List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                AccountCustomerCsvResource.COL_COMPANY_NAME);
        assertTrue(resource.freeFields(row(columns, "A1", "Acme")).isEmpty());
    }

    /**
     * The chunk hook records the account numbers it was handed, which is what
     * lets a full feed know which accounts it did NOT carry.
     */
    @Test
    void theChunkHookRecordsTheNumbersItSees() {
        AccountCustomer known = mock(AccountCustomer.class);
        known.accountNumber = "A1";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.list("accountNumber IN ?1", Set.of("A1")))
                    .thenReturn(List.of(known));
            List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER);
            Map<String, Object> map = resource.processChunkWithFallback(
                    List.of(row(columns, "A1")), Set.of("A1"), new int[]{0, 0}, new java.util.ArrayList<>());
            assertEquals(1, map.size());
            assertEquals(known, map.get("A1"));
        }
    }

    /**
     * An empty chunk touches nothing at all.
     */
    @Test
    void anEmptyChunkTouchesNothing() {
        assertTrue(resource.processChunkWithFallback(List.of(), Set.of(),
                new int[]{0, 0}, new java.util.ArrayList<>()).isEmpty());
    }

    /**
     * The one-by-one fallback looks an account up by its number.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theFallbackFindsOneAccountByItsNumber() {
        AccountCustomer known = mock(AccountCustomer.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            io.quarkus.hibernate.orm.panache.PanacheQuery<AccountCustomer> query =
                    mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            when(query.firstResult()).thenReturn(known);
            panache.when(() -> AccountCustomer.find("accountNumber", "A1")).thenReturn(query);
            List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER);
            assertEquals(known, resource.findEntityForLine(row(columns, "A1")));
        }
    }
}
