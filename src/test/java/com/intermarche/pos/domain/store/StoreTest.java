package com.intermarche.pos.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Store}, targeting 100% branch coverage.
 * <p>
 * The static {@code findByCode} finder resolves the Panache {@code find}
 * query, which under plain {@code mvn test} falls back to
 * {@link PanacheEntityBase}, so it is intercepted with
 * {@link org.mockito.Mockito#mockStatic} together with a mock
 * {@link PanacheQuery}. The {@code getChecksum} ternary is exercised on both
 * the null and non-null address arms on plain instances. Each test is fully
 * isolated and asserts absolute expected values.
 */
class StoreTest {

    /**
     * Builds a fully populated address value object for checksum assertions.
     *
     * @return a non-null address with every field set
     */
    private Address sampleAddress() {
        return new Address("10 Rue Centrale", "Bat A", "69001", "Lyon",
                "France", 45.76, 4.83);
    }

    /**
     * findByCode delegates to the code finder and returns its first result.
     */
    @Test
    void findByCodeDelegatesToFinder() {
        Store expected = new Store();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Store> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> Store.find("code", "0034")).thenReturn(query);
            Assertions.assertSame(expected, Store.findByCode("0034"));
        }
    }

    /**
     * findByCode propagates a null first result when no row matches.
     */
    @Test
    void findByCodeReturnsNullWhenNoMatch() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Store> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> Store.find("code", "9999")).thenReturn(query);
            Assertions.assertNull(Store.findByCode("9999"));
        }
    }

    /**
     * getChecksum substitutes zero for a null address (ternary null arm)
     * and matches the reference hash.
     *
     * <p>The reference lists the ELEVEN values the checksum folds in, in the
     * order {@code Store.getChecksum} folds them: a field added to the store's
     * identity and forgotten here would leave the store syncing under an
     * unchanged checksum, which is the one thing this test exists to catch.
     */
    @Test
    void getChecksumWithNullAddressUsesZero() {
        Store store = new Store();
        store.code = "0034";
        store.name = "Intermarche Lyon Centre";
        store.address = null;
        int expected = Objects.hash("0034", "Intermarche Lyon Centre", 0,
                null, null, null, null, null, null, null, null);
        Assertions.assertEquals(expected, store.getChecksum());
    }

    /**
     * getChecksum folds in the address checksum for a non-null address
     * (ternary non-null arm) and matches the reference hash, every one of the
     * eleven values filled in.
     */
    @Test
    void getChecksumWithNonNullAddressUsesAddressChecksum() {
        Store store = new Store();
        store.code = "0034";
        store.name = "Intermarche Lyon Centre";
        store.address = sampleAddress();
        store.vatNumber = "FR123456789";
        store.siret = "12345678900012";
        store.phone = "0472000000";
        store.bankAccountNumber = "FR7612345";
        store.legalName = "SA JANSELIN";
        store.rcs = "RCS LYON 123 456 789";
        store.shareCapital = new java.math.BigDecimal("37000.00");
        store.fax = "0472000001";
        int expected = Objects.hash("0034", "Intermarche Lyon Centre",
                sampleAddress().getChecksum(), "FR123456789", "12345678900012",
                "0472000000", "FR7612345", "SA JANSELIN", "RCS LYON 123 456 789",
                new java.math.BigDecimal("37000.00"), "0472000001");
        Assertions.assertEquals(expected, store.getChecksum());
    }

    /**
     * The legal identity fields carried by an invoice — legal name, RCS, share
     * capital, fax — are part of the checksum: changing one alone must change
     * it, or the store node would never learn the footer of its invoices moved.
     */
    @Test
    void getChecksumChangesWhenALegalFieldChanges() {
        Store before = new Store();
        before.code = "0034";
        before.name = "Intermarche Lyon Centre";
        before.legalName = "SA JANSELIN";
        Store after = new Store();
        after.code = "0034";
        after.name = "Intermarche Lyon Centre";
        after.legalName = "SAS JANSELIN";
        Assertions.assertNotEquals(before.getChecksum(), after.getChecksum());
    }
}
