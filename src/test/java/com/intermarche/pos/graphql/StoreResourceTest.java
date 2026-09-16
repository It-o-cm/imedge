package com.intermarche.pos.graphql;

import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.store.Store;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link StoreResource}.
 * <p>
 * {@code StoreResource} is a GraphQL active-record facade: queries and
 * mutations delegate to Panache static finders on {@link Store}, and the three
 * mutations wrap their body in {@link GraphQLTrait#execute} which re-throws
 * {@link NoSuchElementException} unchanged, wraps {@link AlreadyExistsException}
 * in a {@link GraphQLException} carrying the same message, and wraps any other
 * {@link Exception} in a generic {@link GraphQLException}. Under plain
 * {@code mvn test} the inherited finders ({@code listAll}, {@code findById},
 * {@code count}, {@code deleteById}) resolve to {@link PanacheEntityBase} and are
 * intercepted with {@link org.mockito.Mockito#mockStatic}; the custom static
 * {@code Store.findByCode} is declared on {@link Store} and is intercepted through
 * {@code mockStatic(Store.class)}; the {@code new Store()} performed by
 * {@code createStore} is neutralized with {@link org.mockito.Mockito#mockConstruction}
 * so its {@code persist()} is observable, while the embeddable {@link Address} is a
 * plain POJO constructed for real. Every static mock lives in a try-with-resources
 * block, no database and no Quarkus context is booted, entity fields are set
 * directly, and every branch (both ternary/guard arms, every short-circuit of the
 * compound conditions) is asserted against absolute expected values.
 */
class StoreResourceTest {

    /** The exact JPQL fragment issued by {@code updateStore} for its name conflict check. */
    private static final String UPDATE_COUNT_QUERY = "name = ?1 and id <> ?2";
    /** Store id used across query/update/delete scenarios. */
    private static final Long STORE_ID = 7L;
    /** Store code used across scenarios. */
    private static final String CODE = "0034";
    /** Store name used across scenarios. */
    private static final String NAME = "Intermarche Lyon Centre";

    /** The resource under test; the trait default method is real, no collaborators are injected. */
    private final StoreResource resource = new StoreResource();

    /**
     * Builds a real {@link Store} carrying the supplied code and name.
     *
     * @param code the store code
     * @param name the store name
     * @return the store
     */
    private Store store(String code, String name) {
        Store s = new Store();
        s.code = code;
        s.name = name;
        return s;
    }

    /**
     * Builds a {@link StoreResource.StoreRecord} with the supplied code and name, leaving the
     * address fields null.
     *
     * @param code the code (may be null)
     * @param name the name (may be null)
     * @return the populated record
     */
    private StoreResource.StoreRecord record(String code, String name) {
        StoreResource.StoreRecord input = new StoreResource.StoreRecord();
        input.code = code;
        input.name = name;
        return input;
    }

    /**
     * {@code allStores} returns whatever {@code Store.listAll} yields, unchanged.
     */
    @Test
    void allStoresReturnsListAll() {
        List<Store> all = List.of(new Store(), new Store());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Store::listAll).thenReturn(all);
            List<Store> result = resource.allStores();
            assertSame(all, result);
        }
    }

    /**
     * {@code store} returns the entity found by id when it exists (guard false arm).
     */
    @Test
    void storeReturnsEntityWhenFound() {
        Store found = store(CODE, NAME);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Store.findById(STORE_ID)).thenReturn(found);
            Store result = resource.store(STORE_ID);
            assertSame(found, result);
        }
    }

    /**
     * {@code store} throws {@link NoSuchElementException} when no entity has the id (guard true arm).
     */
    @Test
    void storeThrowsWhenNotFound() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Store.findById(STORE_ID)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.store(STORE_ID));
            assertEquals("Store with id " + STORE_ID + " not found", thrown.getMessage());
        }
    }

    /**
     * {@code storeByCode} returns the entity found by code when it exists (guard false arm).
     */
    @Test
    void storeByCodeReturnsEntityWhenFound() {
        Store found = store(CODE, NAME);
        try (MockedStatic<Store> stores = mockStatic(Store.class)) {
            stores.when(() -> Store.findByCode(CODE)).thenReturn(found);
            Store result = resource.storeByCode(CODE);
            assertSame(found, result);
        }
    }

    /**
     * {@code storeByCode} throws {@link NoSuchElementException} when no entity has the code (guard
     * true arm).
     */
    @Test
    void storeByCodeThrowsWhenNotFound() {
        try (MockedStatic<Store> stores = mockStatic(Store.class)) {
            stores.when(() -> Store.findByCode(CODE)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.storeByCode(CODE));
            assertEquals("Store with code " + CODE + " not found", thrown.getMessage());
        }
    }

    /**
     * {@code createStore} persists a new store with a fully populated address when the code and name
     * are both unique (both guards false).
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void createStorePersistsWhenCodeAndNameUnique() throws GraphQLException {
        StoreResource.StoreRecord input = record(CODE, NAME);
        input.streetLine1 = "10 Rue de la Paix";
        input.streetLine2 = "Batiment A";
        input.postalCode = "69001";
        input.city = "Lyon";
        input.country = "France";
        input.latitude = 45.767;
        input.longitude = 4.833;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Store> stores = mockStatic(Store.class);
             MockedConstruction<Store> created = mockConstruction(Store.class)) {
            stores.when(() -> Store.findByCode(CODE)).thenReturn(null);
            panache.when(() -> Store.count("name", NAME)).thenReturn(0L);
            Store result = resource.createStore(input);
            assertEquals(1, created.constructed().size());
            Store store = created.constructed().get(0);
            assertSame(store, result);
            assertEquals(CODE, store.code);
            assertEquals(NAME, store.name);
            assertNotNull(store.address);
            assertEquals("10 Rue de la Paix", store.address.streetLine1);
            assertEquals("Batiment A", store.address.streetLine2);
            assertEquals("69001", store.address.postalCode);
            assertEquals("Lyon", store.address.city);
            assertEquals("France", store.address.country);
            assertEquals(45.767, store.address.latitude);
            assertEquals(4.833, store.address.longitude);
            verify(store, times(1)).persist();
        }
    }

    /**
     * {@code createStore} wraps the {@link AlreadyExistsException} in a {@link GraphQLException} when
     * the code already exists (first guard true).
     */
    @Test
    void createStoreThrowsWhenCodeExists() {
        StoreResource.StoreRecord input = record(CODE, NAME);
        try (MockedStatic<Store> stores = mockStatic(Store.class)) {
            stores.when(() -> Store.findByCode(CODE)).thenReturn(store(CODE, "Other"));
            GraphQLException thrown = assertThrows(GraphQLException.class,
                    () -> resource.createStore(input));
            assertEquals("Store with code '" + CODE + "' already exists.", thrown.getMessage());
            assertTrue(thrown.getCause() instanceof AlreadyExistsException);
        }
    }

    /**
     * {@code createStore} wraps the {@link AlreadyExistsException} in a {@link GraphQLException} when
     * the code is unique but the name already exists (first guard false, second guard true).
     */
    @Test
    void createStoreThrowsWhenNameExists() {
        StoreResource.StoreRecord input = record(CODE, NAME);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Store> stores = mockStatic(Store.class)) {
            stores.when(() -> Store.findByCode(CODE)).thenReturn(null);
            panache.when(() -> Store.count("name", NAME)).thenReturn(1L);
            GraphQLException thrown = assertThrows(GraphQLException.class,
                    () -> resource.createStore(input));
            assertEquals("Store with name '" + NAME + "' already exists.", thrown.getMessage());
            assertTrue(thrown.getCause() instanceof AlreadyExistsException);
        }
    }

    /**
     * {@code updateStore} re-throws {@link NoSuchElementException} unwrapped when the id resolves to
     * nothing (store guard true arm).
     */
    @Test
    void updateStoreThrowsWhenNotFound() {
        StoreResource.StoreRecord input = record(CODE, NAME);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Store.findById(STORE_ID)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.updateStore(STORE_ID, input));
            assertEquals("Store with id " + STORE_ID + " not found", thrown.getMessage());
        }
    }

    /**
     * {@code updateStore} is a no-op leaving the store untouched when every input field is null (both
     * compound first arms false, existing address kept, every apply guard false).
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void updateStoreNoOpWhenAllNull() throws GraphQLException {
        Store existing = store(CODE, NAME);
        existing.address = new Address("keep1", "keep2", "keep3", "keepCity", "keepCountry", 1.0, 2.0);
        StoreResource.StoreRecord input = record(null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Store.findById(STORE_ID)).thenReturn(existing);
            Store result = resource.updateStore(STORE_ID, input);
            assertSame(existing, result);
            assertEquals(CODE, existing.code);
            assertEquals(NAME, existing.name);
            assertEquals("keep1", existing.address.streetLine1);
            assertEquals("keep2", existing.address.streetLine2);
            assertEquals("keep3", existing.address.postalCode);
            assertEquals("keepCity", existing.address.city);
            assertEquals("keepCountry", existing.address.country);
            assertEquals(1.0, existing.address.latitude);
            assertEquals(2.0, existing.address.longitude);
            panache.verify(() -> Store.count(UPDATE_COUNT_QUERY, NAME, STORE_ID), times(0));
        }
    }

    /**
     * {@code updateStore} skips both conflict checks yet re-applies the code and name when they are
     * provided but equal to the current values (both compound second arms false, both apply guards
     * true, existing address kept).
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void updateStoreUnchangedCodeAndNameSkipConflictChecks() throws GraphQLException {
        Store existing = store(CODE, NAME);
        existing.address = new Address("keep1", null, null, null, null, null, null);
        StoreResource.StoreRecord input = record(CODE, NAME);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Store> stores = mockStatic(Store.class)) {
            panache.when(() -> Store.findById(STORE_ID)).thenReturn(existing);
            Store result = resource.updateStore(STORE_ID, input);
            assertSame(existing, result);
            assertSame(input.code, existing.code);
            assertSame(input.name, existing.name);
            assertEquals("keep1", existing.address.streetLine1);
            stores.verify(() -> Store.findByCode(CODE), times(0));
            panache.verify(() -> Store.count(UPDATE_COUNT_QUERY, NAME, STORE_ID), times(0));
        }
    }

    /**
     * {@code updateStore} wraps an {@link AlreadyExistsException} in a {@link GraphQLException} when
     * the changed code conflicts with another store (code compound both arms true, conflict lookup
     * non-null).
     */
    @Test
    void updateStoreThrowsWhenCodeConflicts() {
        Store existing = store(CODE, NAME);
        StoreResource.StoreRecord input = record("9999", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Store> stores = mockStatic(Store.class)) {
            panache.when(() -> Store.findById(STORE_ID)).thenReturn(existing);
            stores.when(() -> Store.findByCode("9999")).thenReturn(store("9999", "Other"));
            GraphQLException thrown = assertThrows(GraphQLException.class,
                    () -> resource.updateStore(STORE_ID, input));
            assertEquals("Another store with code '9999' already exists.", thrown.getMessage());
            assertTrue(thrown.getCause() instanceof AlreadyExistsException);
        }
    }

    /**
     * {@code updateStore} wraps an {@link AlreadyExistsException} in a {@link GraphQLException} when
     * the changed name conflicts with another store (code guard first arm false, name compound both
     * arms true, conflict count positive).
     */
    @Test
    void updateStoreThrowsWhenNameConflicts() {
        Store existing = store(CODE, NAME);
        StoreResource.StoreRecord input = record(null, "Intermarche Lyon Nord");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Store.findById(STORE_ID)).thenReturn(existing);
            panache.when(() -> Store.count(UPDATE_COUNT_QUERY, "Intermarche Lyon Nord", STORE_ID)).thenReturn(1L);
            GraphQLException thrown = assertThrows(GraphQLException.class,
                    () -> resource.updateStore(STORE_ID, input));
            assertEquals("Another store with name 'Intermarche Lyon Nord' already exists.", thrown.getMessage());
            assertTrue(thrown.getCause() instanceof AlreadyExistsException);
        }
    }

    /**
     * {@code updateStore} applies every field and creates a fresh address when the code and name are
     * changed and free of conflicts (code conflict lookup null, name conflict count zero, null
     * address created, every apply guard true).
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void updateStoreAppliesAllFieldsAndCreatesAddress() throws GraphQLException {
        Store existing = store(CODE, NAME);
        existing.address = null;
        StoreResource.StoreRecord input = record("9999", "Intermarche Lyon Nord");
        input.streetLine1 = "5 Avenue Berthelot";
        input.streetLine2 = "Etage 2";
        input.postalCode = "69007";
        input.city = "Lyon";
        input.country = "France";
        input.latitude = 45.75;
        input.longitude = 4.85;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Store> stores = mockStatic(Store.class)) {
            panache.when(() -> Store.findById(STORE_ID)).thenReturn(existing);
            stores.when(() -> Store.findByCode("9999")).thenReturn(null);
            panache.when(() -> Store.count(UPDATE_COUNT_QUERY, "Intermarche Lyon Nord", STORE_ID)).thenReturn(0L);
            Store result = resource.updateStore(STORE_ID, input);
            assertSame(existing, result);
            assertEquals("9999", existing.code);
            assertEquals("Intermarche Lyon Nord", existing.name);
            assertNotNull(existing.address);
            assertEquals("5 Avenue Berthelot", existing.address.streetLine1);
            assertEquals("Etage 2", existing.address.streetLine2);
            assertEquals("69007", existing.address.postalCode);
            assertEquals("Lyon", existing.address.city);
            assertEquals("France", existing.address.country);
            assertEquals(45.75, existing.address.latitude);
            assertEquals(4.85, existing.address.longitude);
        }
    }

    /**
     * {@code deleteStore} returns true when the underlying delete removed a row.
     *
     * @throws GraphQLException never in this path
     */
    @Test
    void deleteStoreReturnsTrueWhenDeleted() throws GraphQLException {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Store.deleteById(STORE_ID)).thenReturn(true);
            boolean result = resource.deleteStore(STORE_ID);
            assertTrue(result);
        }
    }

    /**
     * {@code deleteStore} returns false when no row matched the id.
     *
     * @throws GraphQLException never in this path
     */
    @Test
    void deleteStoreReturnsFalseWhenNothingDeleted() throws GraphQLException {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Store.deleteById(STORE_ID)).thenReturn(false);
            boolean result = resource.deleteStore(STORE_ID);
            assertFalse(result);
        }
    }

    /**
     * {@code StoreRecord} default constructor leaves every field null.
     */
    @Test
    void storeRecordDefaultConstructorLeavesFieldsNull() {
        StoreResource.StoreRecord input = new StoreResource.StoreRecord();
        assertNull(input.code);
        assertNull(input.name);
        assertNull(input.streetLine1);
        assertNull(input.streetLine2);
        assertNull(input.postalCode);
        assertNull(input.city);
        assertNull(input.country);
        assertNull(input.latitude);
        assertNull(input.longitude);
    }

    /**
     * {@code StoreRecord.toString} renders all fields of the record.
     */
    @Test
    void storeRecordToStringRendersAllFields() {
        StoreResource.StoreRecord input = record(CODE, NAME);
        input.streetLine1 = "s1";
        input.streetLine2 = "s2";
        input.postalCode = "pc";
        input.city = "c";
        input.country = "co";
        input.latitude = 1.5;
        input.longitude = 2.5;
        String expected = "StoreRecord [code=" + CODE + ", name=" + NAME +
                ", streetLine1=s1, streetLine2=s2" +
                ", postalCode=pc, city=c" +
                ", country=co, latitude=1.5" +
                ", longitude=2.5]";
        assertEquals(expected, input.toString());
    }
}
