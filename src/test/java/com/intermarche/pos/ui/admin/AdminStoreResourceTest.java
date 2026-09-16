package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.store.Store;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminStoreResource}.
 * <p>
 * The resource is a Qute-backed back-office page over the {@link Store}
 * static finder, which resolves to {@link PanacheEntityBase} under plain
 * {@code mvn test} and is intercepted with
 * {@link org.mockito.Mockito#mockStatic}. The template is a Mockito mock
 * whose chained {@code data(...)} returns a mocked instance. No database and
 * no Quarkus context is booted.
 * <p>
 * Branch enumeration (every arm exercised — 100%): {@code storePage} covers
 * the compound {@code store != null && store.address == null} guard (store
 * null / store non-null with null address / store non-null with non-null
 * address); {@code save} covers the store-null early-redirect arm and, on
 * the store-present path, both arms of the same address-null guard;
 * {@code trimmed} covers the field-present and field-absent arms.
 */
class AdminStoreResourceTest {

    /**
     * Builds a resource over a mocked template.
     *
     * @return the wired resource
     */
    private AdminStoreResource newResource() {
        AdminStoreResource resource = new AdminStoreResource();
        resource.adminStore = mock(Template.class);
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the page template to a single
     * self-returning instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked template instance the chain returns
     */
    private TemplateInstance wireTemplate(AdminStoreResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminStore.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Stubs {@code Store.findAll().firstResult()} to return the given row
     * (or null) and runs the given action within the static mock's scope.
     *
     * @param row the row {@code findAll} should resolve to, or null
     * @param action the test action to run while the mock is active
     */
    @SuppressWarnings("unchecked")
    private void withStore(Store row, Runnable action) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Store> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(row);
            panache.when(PanacheEntityBase::findAll).thenReturn(query);
            action.run();
        }
    }

    /**
     * {@code storePage} passes a null store through untouched when this node
     * has no row yet (store-null arm of the compound guard).
     */
    @Test
    void storePagePassesNullStoreThrough() {
        AdminStoreResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        withStore(null, () -> {
            assertEquals(instance, resource.storePage(null, true));
            org.mockito.Mockito.verify(resource.adminStore).data("store", null);
        });
    }

    /**
     * {@code storePage} initializes a missing address on an existing row
     * (store-present / address-null arm) so the template never dereferences
     * a null address.
     */
    @Test
    void storePageInitializesMissingAddress() {
        AdminStoreResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        Store store = new Store();
        store.code = "0101";
        store.address = null;
        withStore(store, () -> {
            assertEquals(instance, resource.storePage("hi", false));
            assertNotNull(store.address);
        });
        org.mockito.Mockito.verify(instance).data("notice", "hi");
        org.mockito.Mockito.verify(instance).data("noticeOk", false);
    }

    /**
     * {@code storePage} leaves an existing address untouched (store-present /
     * address-non-null arm).
     */
    @Test
    void storePageKeepsExistingAddress() {
        AdminStoreResource resource = newResource();
        wireTemplate(resource);
        Store store = new Store();
        store.code = "0101";
        Address address = new Address();
        store.address = address;
        withStore(store, () -> {
            resource.storePage(null, true);
            assertEquals(address, store.address);
        });
    }

    /**
     * {@code save} redirects red without touching anything when this node has
     * no store row yet (store-null arm).
     */
    @Test
    void saveRejectsWhenNoStore() {
        AdminStoreResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        withStore(null, () -> {
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        });
    }

    /**
     * {@code save} initializes a missing address before writing the posted
     * fields (address-null arm inside {@code save}) and stores every
     * trimmed field (field-present arm of {@code trimmed}), leaving the code
     * untouched.
     */
    @Test
    void saveInitializesAddressAndStoresTrimmedFields() {
        AdminStoreResource resource = newResource();
        Store store = new Store();
        store.code = "0101";
        store.name = "Old name";
        store.address = null;
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("name", "  Intermarché Lyon  ");
        form.putSingle("streetLine1", " 1 Rue Test ");
        form.putSingle("streetLine2", " Bat A ");
        form.putSingle("postalCode", " 69001 ");
        form.putSingle("city", " Lyon ");
        form.putSingle("country", " France ");
        form.putSingle("vatNumber", " FR123 ");
        form.putSingle("siret", " 12345678900012 ");
        form.putSingle("phone", " 0472000000 ");
        form.putSingle("bankAccountNumber", " FR76001 ");
        withStore(store, () -> {
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        });
        assertEquals("0101", store.code);
        assertEquals("Intermarché Lyon", store.name);
        assertNotNull(store.address);
        assertEquals("1 Rue Test", store.address.streetLine1);
        assertEquals("Bat A", store.address.streetLine2);
        assertEquals("69001", store.address.postalCode);
        assertEquals("Lyon", store.address.city);
        assertEquals("France", store.address.country);
        assertEquals("FR123", store.vatNumber);
        assertEquals("12345678900012", store.siret);
        assertEquals("0472000000", store.phone);
        assertEquals("FR76001", store.bankAccountNumber);
    }

    /**
     * BO-03-12-06: the PDV number is READ-ONLY. A form that posts {@code code}
     * — a stale page, a hand-edited request — leaves the store's number
     * untouched.
     *
     * <p>The success case above also asserts the code, but its form never posts
     * one, so it would stay green if the screen started writing it. This case
     * posts a different number on purpose: it is the one that fails the day
     * someone adds {@code store.code = trimmed(form, "code", store.code)}. The
     * number is the sync key of the node — an editable one silently reattaches
     * the register to another shop.
     */
    @Test
    void saveIgnoresAPostedStoreCode() {
        AdminStoreResource resource = newResource();
        Store store = new Store();
        store.code = "0101";
        store.address = new Address();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("code", "9999");
        form.putSingle("name", "Intermarché Lyon");
        withStore(store, () -> {
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        });
        assertEquals("0101", store.code);
        assertEquals("Intermarché Lyon", store.name);
    }

    /**
     * {@code save} keeps every field's current value when the form omits it
     * (field-absent arm of {@code trimmed}), on an already-addressed row
     * (address-non-null arm inside {@code save}).
     */
    @Test
    void saveKeepsCurrentValuesForMissingFields() {
        AdminStoreResource resource = newResource();
        Store store = new Store();
        store.code = "0101";
        store.name = "Kept name";
        Address address = new Address();
        address.city = "Kept city";
        store.address = address;
        store.vatNumber = "Kept VAT";
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        withStore(store, () -> {
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
        });
        assertEquals("Kept name", store.name);
        assertEquals(address, store.address);
        assertEquals("Kept city", store.address.city);
        assertEquals("Kept VAT", store.vatNumber);
        assertNull(store.siret);
    }
}
