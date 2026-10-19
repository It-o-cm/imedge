package com.intermarche.edge.imports;

import com.intermarche.edge.domain.payment.AccountCustomer;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
 * label, one carrying only a value, and the ordering by slot;
 * {@code refuseIncompleteAddress} — the control-off arm (the nominal one,
 * shared by every other test), the control-on arm with a complete address,
 * and the refusal arm naming the missing columns.
 */
class AccountCustomerCsvResourceTest {

    /** The resource under test. */
    private final AccountCustomerCsvResource resource = new AccountCustomerCsvResource();

    /** The back-office parameters, holding the shop's address control. */
    private PosSettingsService posSettingsService;

    /**
     * Mounts the back-office parameters on the nominal behavior: the shop did
     * not ask for the address control (BO-10-08-27), so the feeds integrate
     * exactly what they carried before that control existed.
     */
    @BeforeEach
    void mountTheBackOfficeParameters() {
        posSettingsService = mock(PosSettingsService.class);
        when(posSettingsService.customerIntegrationRequiresAddress()).thenReturn(false);
        resource.posSettingsService = posSettingsService;
    }

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
     * A free-field pair whose two cells are BOTH missing from the row — the
     * label and the value indices both fall beyond the line's cells, so each
     * reads as null — is not a field: this exercises the null arms of the
     * emptiness guard that an empty-string cell never reaches.
     */
    @Test
    void afreePairWithBothCellsMissingIsNotAField() {
        List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                "FREE_1_LABEL", "FREE_1_VALUE");
        List<AccountCustomer.FreeField> fields = resource.freeFields(row(columns, "A1"));
        assertTrue(fields.isEmpty());
    }

    /**
     * A FULL import blocks the accounts the file did not carry once the reading
     * is done: an empty file makes the block query fire over every unblocked
     * account, and the endpoint answers 200.
     */
    @Test
    void afullImportBlocksTheAbsentAccountsAfterReading() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.list("blocked = false")).thenReturn(List.of());
            InputStream stream = new ByteArrayInputStream(
                    "ACCOUNT_NUMBER|COMPANY_NAME\n".getBytes(StandardCharsets.UTF_8));
            Response response = resource.importCustomers(stream, "full");
            assertEquals(200, response.getStatus());
            panache.verify(() -> AccountCustomer.list("blocked = false"));
        }
    }

    /**
     * A DELTA import blocks nothing: the mode is not full, so the block query
     * is never issued and the endpoint still answers 200.
     */
    @Test
    void adeltaImportBlocksNothing() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            InputStream stream = new ByteArrayInputStream(
                    "ACCOUNT_NUMBER|COMPANY_NAME\n".getBytes(StandardCharsets.UTF_8));
            Response response = resource.importCustomers(stream, null);
            assertEquals(200, response.getStatus());
            panache.verifyNoInteractions();
        }
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

    /**
     * The nineteen consumed columns, in the order the wire schema declares
     * them, for the rows that exercise the create and update paths.
     *
     * @return the header names of every column this importer reads
     */
    private List<String> allColumns() {
        return List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                AccountCustomerCsvResource.COL_COMPANY_NAME,
                AccountCustomerCsvResource.COL_CIVILITY,
                AccountCustomerCsvResource.COL_LAST_NAME,
                AccountCustomerCsvResource.COL_FIRST_NAME,
                AccountCustomerCsvResource.COL_STREET_LINE1,
                AccountCustomerCsvResource.COL_POSTAL_CODE,
                AccountCustomerCsvResource.COL_CITY,
                AccountCustomerCsvResource.COL_SIRET,
                AccountCustomerCsvResource.COL_VAT_NUMBER,
                AccountCustomerCsvResource.COL_TAX_ID,
                AccountCustomerCsvResource.COL_SEGMENT,
                AccountCustomerCsvResource.COL_PHONE,
                AccountCustomerCsvResource.COL_EMAIL,
                AccountCustomerCsvResource.COL_BLOCKED,
                AccountCustomerCsvResource.COL_DUE_DATE,
                AccountCustomerCsvResource.COL_DISCOUNT_PERCENT,
                AccountCustomerCsvResource.COL_CREDIT_LIMIT,
                AccountCustomerCsvResource.COL_CREDIT_BALANCE);
    }

    /**
     * An account absent from the pre-fetched map is CREATED: it is stamped as
     * integrated, fed from the row and handed to the entity manager, and the
     * created counter — not the updated one — moves. A row that names none of
     * the optional columns leaves every optional field null, its due date
     * unread and its balance floored at zero, and its address non-null because
     * a fresh account already carries an empty one.
     */
    @Test
    void anUnknownAccountIsCreatedAndPersisted() {
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                    AccountCustomerCsvResource.COL_COMPANY_NAME);
            int[] counters = {0, 0};
            resource.processLineLogic(row(columns, "A1", "Acme"), new HashMap<>(), counters);
            ArgumentCaptor<AccountCustomer> captor = ArgumentCaptor.forClass(AccountCustomer.class);
            verify(em).persist(captor.capture());
            AccountCustomer created = captor.getValue();
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            assertEquals("A1", created.accountNumber);
            assertEquals(AccountCustomer.Origin.INTEGRATED, created.origin);
            assertEquals("Acme", created.companyName);
            assertNull(created.civility);
            assertNull(created.dueDate);
            assertNull(created.creditLimit);
            assertEquals(BigDecimal.ZERO, created.creditBalance);
            assertNotNull(created.address);
            assertTrue(created.freeFields.isEmpty());
        }
    }

    /**
     * A create reads every column the file declares: the contact, the address,
     * the fiscal identifiers, the flags, a readable due date, the two decimals
     * and the balance the row carries rather than the floored zero.
     */
    @Test
    void aCreateReadsEveryColumnItIsGiven() {
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            int[] counters = {0, 0};
            resource.processLineLogic(row(allColumns(), "A1", "Acme", "M.", "Doe", "John",
                    "1 rue", "75001", "Paris", "SIR123", "FR99", "TAX7", "GOLD",
                    "0102030405", "a@b.c", "true", "2026-01-15", "5.00", "1000.00", "250.00"),
                    new HashMap<>(), counters);
            ArgumentCaptor<AccountCustomer> captor = ArgumentCaptor.forClass(AccountCustomer.class);
            verify(em).persist(captor.capture());
            AccountCustomer created = captor.getValue();
            assertEquals(1, counters[0]);
            assertEquals("M.", created.civility);
            assertEquals("Doe", created.lastName);
            assertEquals("John", created.firstName);
            assertEquals("1 rue", created.address.streetLine1);
            assertEquals("75001", created.address.postalCode);
            assertEquals("Paris", created.address.city);
            assertEquals("SIR123", created.siret);
            assertEquals("FR99", created.vatNumber);
            assertEquals("TAX7", created.taxId);
            assertEquals("GOLD", created.segment);
            assertEquals("0102030405", created.phone);
            assertEquals("a@b.c", created.email);
            assertTrue(created.blocked);
            assertEquals(LocalDate.of(2026, 1, 15), created.dueDate);
            assertEquals(new BigDecimal("5.00"), created.discountPercent);
            assertEquals(new BigDecimal("1000.00"), created.creditLimit);
            assertEquals(new BigDecimal("250.00"), created.creditBalance);
        }
    }

    /**
     * A due date the row declares but leaves EMPTY reads as none: the empty
     * cell is told apart from an absent column, and both leave the date null.
     */
    @Test
    void anEmptyDueDateCellReadsAsNoDate() {
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                    AccountCustomerCsvResource.COL_COMPANY_NAME,
                    AccountCustomerCsvResource.COL_DUE_DATE);
            int[] counters = {0, 0};
            resource.processLineLogic(row(columns, "A1", "Acme", ""), new HashMap<>(), counters);
            ArgumentCaptor<AccountCustomer> captor = ArgumentCaptor.forClass(AccountCustomer.class);
            verify(em).persist(captor.capture());
            assertNull(captor.getValue().dueDate);
            assertEquals(1, counters[0]);
        }
    }

    /**
     * An unreadable due date is dropped rather than failing the row: a cell
     * that is not an ISO date leaves the account's due date null.
     */
    @Test
    void anUnreadableDueDateIsIgnored() {
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                    AccountCustomerCsvResource.COL_COMPANY_NAME,
                    AccountCustomerCsvResource.COL_DUE_DATE);
            int[] counters = {0, 0};
            resource.processLineLogic(row(columns, "A1", "Acme", "not-a-date"),
                    new HashMap<>(), counters);
            ArgumentCaptor<AccountCustomer> captor = ArgumentCaptor.forClass(AccountCustomer.class);
            verify(em).persist(captor.capture());
            assertNull(captor.getValue().dueDate);
            assertEquals(1, counters[0]);
        }
    }

    /**
     * An account present in the map but carrying NO stored checksum — the case
     * of a column added by a schema update on an existing table — is rewritten:
     * the row is re-read through {@code findById} on the detached entity, fed
     * onto it, and the updated counter moves. Its missing address is created
     * afresh in the feeding.
     */
    @Test
    void anUpdateWithNoStoredChecksumRewritesTheAccount() {
        AccountCustomer existing = new AccountCustomer();
        existing.id = 7L;
        existing.accountNumber = "A1";
        existing.address = null;
        existing.checksum = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(7L)).thenReturn(existing);
            Map<String, Object> map = new HashMap<>();
            map.put("A1", existing);
            int[] counters = {0, 0};
            List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                    AccountCustomerCsvResource.COL_COMPANY_NAME);
            resource.processLineLogic(row(columns, "A1", "Acme"), map, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Acme", existing.companyName);
            assertEquals(AccountCustomer.Origin.INTEGRATED, existing.origin);
            assertNotNull(existing.address);
        }
    }

    /**
     * An account whose stored checksum no longer matches the incoming row is
     * rewritten: after a first feed leaves it consistent, a checksum bumped by
     * one is seen as stale and the second pass rewrites it again.
     */
    @Test
    void anUpdateWithAStaleChecksumRewritesTheAccount() {
        AccountCustomer existing = new AccountCustomer();
        existing.id = 8L;
        existing.accountNumber = "A1";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(8L)).thenReturn(existing);
            Map<String, Object> map = new HashMap<>();
            map.put("A1", existing);
            int[] counters = {0, 0};
            List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                    AccountCustomerCsvResource.COL_COMPANY_NAME);
            ImporterCsvResource.LineData line = row(columns, "A1", "Acme");
            resource.processLineLogic(line, map, counters);
            existing.checksum = existing.getChecksum() + 1;
            resource.processLineLogic(line, map, counters);
            assertEquals(0, counters[0]);
            assertEquals(2, counters[1]);
        }
    }

    /**
     * An account whose stored checksum already matches the incoming row is
     * SKIPPED: re-importing the same file is a no-op, and the updated counter
     * does not move on the second pass.
     */
    @Test
    void anUpdateWhoseChecksumMatchesIsSkipped() {
        AccountCustomer existing = new AccountCustomer();
        existing.id = 9L;
        existing.accountNumber = "A1";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(9L)).thenReturn(existing);
            Map<String, Object> map = new HashMap<>();
            map.put("A1", existing);
            int[] counters = {0, 0};
            List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                    AccountCustomerCsvResource.COL_COMPANY_NAME);
            ImporterCsvResource.LineData line = row(columns, "A1", "Acme");
            resource.processLineLogic(line, map, counters);
            existing.checksum = existing.getChecksum();
            resource.processLineLogic(line, map, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
        }
    }

    /**
     * Where the shop asks for the address control (BO-10-08-27), a row whose
     * three address lines are complete is integrated as any other: the control
     * is a refusal, not an obstacle.
     */
    @Test
    void aCompleteAddressIsIntegratedWhereTheControlIsOn() {
        when(posSettingsService.customerIntegrationRequiresAddress()).thenReturn(true);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                    AccountCustomerCsvResource.COL_COMPANY_NAME,
                    AccountCustomerCsvResource.COL_STREET_LINE1,
                    AccountCustomerCsvResource.COL_POSTAL_CODE,
                    AccountCustomerCsvResource.COL_CITY);
            int[] counters = {0, 0};
            resource.processLineLogic(row(columns, "A1", "Acme", "1 rue", "75001", "Paris"),
                    new HashMap<>(), counters);
            verify(em).persist(any(AccountCustomer.class));
            assertEquals(1, counters[0]);
        }
    }

    /**
     * Where the shop asks for the address control (BO-10-08-27), a row whose
     * address is incomplete is REJECTED: it throws, so the base importer turns
     * it into one error entry naming its line and its account number, and the
     * missing columns are named in the report.
     */
    @Test
    void anIncompleteAddressIsRefusedWhereTheControlIsOn() {
        when(posSettingsService.customerIntegrationRequiresAddress()).thenReturn(true);
        List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                AccountCustomerCsvResource.COL_COMPANY_NAME,
                AccountCustomerCsvResource.COL_POSTAL_CODE);
        int[] counters = {0, 0};
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(row(columns, "A1", "Acme", "75001"),
                        new HashMap<>(), counters));
        assertTrue(refused.getMessage().contains(AccountCustomerCsvResource.COL_STREET_LINE1));
        assertTrue(refused.getMessage().contains(AccountCustomerCsvResource.COL_CITY));
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }
}
