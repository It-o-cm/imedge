package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.payment.AccountCustomer;
import com.intermarche.edge.domain.store.Address;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminCustomerResource}.
 * <p>
 * A Qute-backed back-office screen over the {@link AccountCustomer} static
 * finders, intercepted with {@link org.mockito.Mockito#mockStatic}; the two
 * templates and the {@link EntityManager} are mocks. No Quarkus context and no
 * database is booted.
 * <p>
 * Branch enumeration (every arm exercised): {@code search} — each of the four
 * criteria absent and present, the three columns the text matches on, a
 * customer whose fiscal identifier is null against a fiscal criterion, both
 * arms of the blocking filter and its third « all » state, and a null origin;
 * {@code lastMovement} — the null account, the empty result and the found one;
 * {@code edit} — the unknown id, the null id, and a customer whose address is
 * null; {@code save} — the unknown customer, the missing business name, and the
 * full save with its blocking checkbox present and absent; the private readers
 * — a blank, an unreadable and a readable value each.
 */
class AdminCustomerResourceTest {

    /**
     * Builds a resource with mocked templates and entity manager.
     *
     * @return the wired resource
     */
    private AdminCustomerResource newResource() {
        AdminCustomerResource resource = new AdminCustomerResource();
        resource.adminCustomers = mock(Template.class);
        resource.adminCustomer = mock(Template.class);
        resource.entityManager = mock(EntityManager.class);
        // BO-10-04-05: the list page asks the parameters whether an account may
        // be opened at all, so the collaborator belongs to the fixture.
        resource.posSettingsService =
                mock(com.intermarche.edge.service.PosSettingsService.class);
        resource.customerUplinkService =
                mock(com.intermarche.edge.service.sync.CustomerUplinkService.class);
        return resource;
    }

    /**
     * Stubs a template's fluent chain to a recognizable view.
     *
     * @param template the template to stub
     * @return the view every chain call returns
     */
    private TemplateInstance stub(Template template) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(template.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds a customer.
     *
     * @param number the account number
     * @param company the business name
     * @param origin where it came from, possibly null
     * @return the customer
     */
    private AccountCustomer customer(String number, String company, AccountCustomer.Origin origin) {
        AccountCustomer customer = new AccountCustomer();
        customer.accountNumber = number;
        customer.companyName = company;
        customer.origin = origin;
        customer.address = new Address();
        return customer;
    }

    /**
     * Stubs the listing the search walks.
     *
     * @param panache the active PanacheEntityBase mock
     * @param customers the customers to resolve
     */
    @SuppressWarnings("unchecked")
    private void stubAll(MockedStatic<PanacheEntityBase> panache, List<AccountCustomer> customers) {
        PanacheQuery<AccountCustomer> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(customers);
        panache.when(() -> AccountCustomer.find("order by companyName")).thenReturn(query);
    }

    /**
     * With no criterion at all, every customer is listed — both populations
     * together, which is what the requirement asks the screen to show.
     */
    @Test
    void withoutCriteriaEveryCustomerIsListed() {
        AdminCustomerResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(customer("A1", "Acme", AccountCustomer.Origin.INTEGRATED),
                    customer("B2", "Bravo", AccountCustomer.Origin.REGISTER)));
            assertEquals(2, resource.search(null, null, null, null).size());
            assertEquals(2, resource.search("", "", "", "").size());
        }
    }

    /**
     * The text criterion matches the account number, the business name and the
     * contact — its three columns — and ignores case.
     */
    @Test
    void theTextCriterionMatchesItsThreeColumns() {
        AdminCustomerResource resource = newResource();
        AccountCustomer withContact = customer("C3", "Charlie", AccountCustomer.Origin.REGISTER);
        withContact.firstName = "Zoé";
        withContact.lastName = "Durand";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(customer("A1", "Acme", AccountCustomer.Origin.INTEGRATED),
                    withContact));
            assertEquals(1, resource.search("a1", null, null, null).size());
            assertEquals(1, resource.search("CHARL", null, null, null).size());
            assertEquals(1, resource.search("durand", null, null, null).size());
            assertTrue(resource.search("inconnu", null, null, null).isEmpty());
        }
    }

    /**
     * The fiscal criterion keeps only the customers carrying one, which is the
     * extraction the requirement asks for — and a customer with no NIF at all
     * is not matched by a blank-ish criterion either.
     */
    @Test
    void theFiscalCriterionKeepsOnlyTheCustomersCarryingOne() {
        AdminCustomerResource resource = newResource();
        AccountCustomer withNif = customer("A1", "Acme", AccountCustomer.Origin.INTEGRATED);
        withNif.taxId = "PT123456789";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(withNif, customer("B2", "Bravo", AccountCustomer.Origin.REGISTER)));
            List<AccountCustomer> matches = resource.search(null, "pt123", null, null);
            assertEquals(1, matches.size());
            assertEquals("A1", matches.get(0).accountNumber);
            assertTrue(resource.search(null, "FR", null, null).isEmpty());
        }
    }

    /**
     * The origin criterion separates the two populations, and a customer whose
     * origin is null is kept out of both — the null arm of that guard.
     */
    @Test
    void theOriginCriterionSeparatesTheTwoPopulations() {
        AdminCustomerResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(customer("A1", "Acme", AccountCustomer.Origin.INTEGRATED),
                    customer("B2", "Bravo", AccountCustomer.Origin.REGISTER),
                    customer("C3", "Charlie", null)));
            assertEquals(1, resource.search(null, null, "INTEGRATED", null).size());
            assertEquals(1, resource.search(null, null, "REGISTER", null).size());
            assertEquals(3, resource.search(null, null, null, null).size());
        }
    }

    /**
     * The blocking criterion has its two states and its « all » state.
     */
    @Test
    void theBlockingCriterionHasItsThreeStates() {
        AdminCustomerResource resource = newResource();
        AccountCustomer blocked = customer("A1", "Acme", AccountCustomer.Origin.INTEGRATED);
        blocked.blocked = true;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(blocked, customer("B2", "Bravo", AccountCustomer.Origin.REGISTER)));
            assertEquals(1, resource.search(null, null, null, "yes").size());
            assertEquals(1, resource.search(null, null, null, "no").size());
            assertEquals(2, resource.search(null, null, null, "peu importe").size());
        }
    }

    /**
     * The list page hands the template what it filtered and echoes the criteria
     * back, so the form stays filled after a search.
     */
    @Test
    void theListPageEchoesTheCriteria() {
        AdminCustomerResource resource = newResource();
        TemplateInstance view = stub(resource.adminCustomers);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(customer("A1", "Acme", AccountCustomer.Origin.INTEGRATED)));
            assertSame(view, resource.list("a", "n", "REGISTER", "yes", 1, null, true));
        }
        verify(resource.adminCustomers).data(eq("page"), any());
    }

    /**
     * The extraction renders a header and one line per filtered customer, the
     * separator neutralized in the cells.
     */
    @Test
    void theExtractionRendersTheFilteredCustomers() {
        AdminCustomerResource resource = newResource();
        AccountCustomer customer = customer("A1", "Acme; et fils", AccountCustomer.Origin.INTEGRATED);
        customer.taxId = "PT1";
        customer.blocked = true;
        customer.dueDate = LocalDate.of(2026, 10, 31);
        customer.discountPercent = new BigDecimal("2.50");
        customer.creditLimit = new BigDecimal("1000.00");
        customer.address.city = "Paris";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(customer));
            Response response = resource.export(null, null, null, null);
            assertEquals(200, response.getStatus());
            String csv = (String) response.getEntity();
            assertTrue(csv.startsWith("ACCOUNT_NUMBER;ORIGIN;COMPANY_NAME;"));
            assertTrue(csv.contains("A1;INTEGRATED;Acme, et fils;"));
            assertTrue(csv.contains(";true;2026-10-31;2.50;1000.00;0"));
            assertTrue(response.getHeaderString("Content-Disposition").contains("clients-"));
        }
    }

    /**
     * An account that never paid on credit here has no last movement, and so
     * has an account with no number at all.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anAccountWithoutACreditPaymentHasNoLastMovement() {
        AdminCustomerResource resource = newResource();
        assertNull(resource.lastMovement(null));
        TypedQuery<Object[]> query = mock(TypedQuery.class);
        when(resource.entityManager.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.setParameter("account", "A1")).thenReturn(query);
        when(query.setMaxResults(1)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        assertNull(resource.lastMovement("A1"));
    }

    /**
     * The last credit payment is read with its date and its amount, which is
     * what the file shows beside the customer.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theLastCreditPaymentIsReadWithItsDateAndAmount() {
        AdminCustomerResource resource = newResource();
        TypedQuery<Object[]> query = mock(TypedQuery.class);
        when(resource.entityManager.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.setParameter("account", "A1")).thenReturn(query);
        when(query.setMaxResults(1)).thenReturn(query);
        LocalDateTime paid = LocalDateTime.of(2026, 9, 10, 18, 42);
        when(query.getResultList()).thenReturn(List.<Object[]>of(new Object[]{paid, new BigDecimal("120.00")}));
        AdminCustomerResource.LastMovement movement = resource.lastMovement("A1");
        assertEquals(paid, movement.getDate());
        assertEquals(new BigDecimal("120.00"), movement.getAmount());
    }

    /**
     * The file page of an unknown or unnamed id renders with a null customer
     * rather than failing: both arms of the id guard.
     */
    @Test
    void anUnknownFileRendersWithoutACustomer() {
        AdminCustomerResource resource = newResource();
        TemplateInstance view = stub(resource.adminCustomer);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(9L)).thenReturn(null);
            assertSame(view, resource.edit(9L, null, true));
            assertSame(view, resource.edit(null, null, true));
        }
        verify(resource.adminCustomer, org.mockito.Mockito.times(2)).data("customer", null);
    }

    /**
     * A customer whose address is null is given one before rendering, so the
     * page reads its parts without failing — the null arm of that guard.
     */
    @Test
    void afileWithoutAnAddressIsGivenAnEmptyOne() {
        AdminCustomerResource resource = newResource();
        stub(resource.adminCustomer);
        AccountCustomer customer = customer("A1", "Acme", AccountCustomer.Origin.INTEGRATED);
        customer.address = null;
        TypedQuery<Object[]> query = mock(TypedQuery.class);
        when(resource.entityManager.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.setParameter("account", "A1")).thenReturn(query);
        when(query.setMaxResults(1)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(1L)).thenReturn(customer);
            resource.edit(1L, null, true);
        }
        assertEquals(new Address().city, customer.address.city);
    }

    /**
     * Saving against an unknown id goes back to the list with a refusal and
     * writes nothing.
     */
    @Test
    void savingAnUnknownCustomerIsRefused() {
        AdminCustomerResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "9");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(9L)).thenReturn(null);
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().startsWith("/admin/customers?notice="));
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * An unreadable id is an unknown customer too, rather than a failure.
     */
    @Test
    void savingWithAnUnreadableIdIsRefused() {
        AdminCustomerResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "pas un nombre");
        Response response = resource.save(form);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * A file cannot be saved without a business name: it is what the invoice is
     * addressed to.
     */
    @Test
    void savingWithoutABusinessNameIsRefused() {
        AdminCustomerResource resource = newResource();
        AccountCustomer customer = customer("A1", "Acme", AccountCustomer.Origin.INTEGRATED);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "1");
        form.add("companyName", "   ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(1L)).thenReturn(customer);
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
            assertEquals("Acme", customer.companyName);
        }
    }

    /**
     * A full save writes every correctable field, reads the blocking checkbox
     * as present, and leaves the account number and the origin alone — neither
     * is a correction anybody makes by hand.
     */
    @Test
    void afullSaveWritesTheCorrectableFields() {
        AdminCustomerResource resource = newResource();
        AccountCustomer customer = mock(AccountCustomer.class);
        customer.accountNumber = "A1";
        customer.companyName = "Acme";
        customer.origin = AccountCustomer.Origin.INTEGRATED;
        customer.address = null;
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "1");
        form.add("companyName", "Acme SA");
        form.add("civility", "Mme");
        form.add("firstName", "Zoé");
        form.add("lastName", "Durand");
        form.add("street", "10 rue X");
        form.add("postalCode", "75008");
        form.add("city", "Paris");
        form.add("siret", "SIR");
        form.add("vatNumber", "FR1");
        form.add("taxId", "PT1");
        form.add("phone", "0102");
        form.add("email", "a@x.fr");
        form.add("blocked", "on");
        form.add("dueDate", "2026-10-31");
        form.add("discountPercent", "2,50");
        form.add("creditLimit", "1000.00");
        form.add("profession", "Boulanger");
        form.add("billingType", "SUPPLEMENT");
        form.add("supplementCode", "S1");
        form.add("billingStreet", "2 rue Basse");
        form.add("billingPostalCode", "75002");
        form.add("billingCity", "Paris");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(1L)).thenReturn(customer);
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("Acme SA", customer.companyName);
        assertEquals("Mme", customer.civility);
        assertEquals("Paris", customer.address.city);
        assertEquals("PT1", customer.taxId);
        assertTrue(customer.blocked);
        assertEquals(LocalDate.of(2026, 10, 31), customer.dueDate);
        assertEquals(new BigDecimal("2.50"), customer.discountPercent);
        assertEquals(new BigDecimal("1000.00"), customer.creditLimit);
        assertEquals("A1", customer.accountNumber);
        assertEquals(AccountCustomer.Origin.INTEGRATED, customer.origin);
        // BO-06-03-04: what the customer list states beyond the identity.
        assertEquals("Boulanger", customer.profession);
        assertEquals("SUPPLEMENT", customer.billingType);
        assertEquals("S1", customer.supplementCode);
        assertEquals("2 rue Basse", customer.billingAddress.streetLine1);
        assertEquals("75002", customer.billingAddress.postalCode);
        assertEquals("Paris", customer.billingAddress.city);
        // BO-02-04-13: the correction is filed for the commercial management.
        org.mockito.Mockito.verify(resource.customerUplinkService).enqueue(customer,
                com.intermarche.edge.domain.sync.CustomerUplink.Source.BACK_OFFICE);
        verify(customer).persist();
    }

    /**
     * A save with the blocking checkbox ABSENT unblocks the account, and blank
     * or unreadable amounts and dates are cleared rather than kept — the empty
     * and unreadable arms of the three readers.
     */
    @Test
    void anAbsentCheckboxUnblocksAndUnreadableValuesAreCleared() {
        AdminCustomerResource resource = newResource();
        AccountCustomer customer = mock(AccountCustomer.class);
        customer.blocked = true;
        customer.dueDate = LocalDate.of(2026, 1, 1);
        customer.discountPercent = BigDecimal.TEN;
        customer.creditLimit = BigDecimal.TEN;
        customer.address = new Address();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "1");
        form.add("companyName", "Acme");
        form.add("dueDate", "le 31");
        form.add("discountPercent", "");
        form.add("creditLimit", "beaucoup");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(1L)).thenReturn(customer);
            resource.save(form);
        }
        assertFalse(customer.blocked);
        assertNull(customer.dueDate);
        assertNull(customer.discountPercent);
        assertNull(customer.creditLimit);
        verify(customer).persist();
    }

    /**
     * BO-06-03-04: a billing address emptied on the form is cleared, the
     * address kept.
     */
    @Test
    void anEmptiedBillingAddressIsCleared() {
        AdminCustomerResource resource = newResource();
        AccountCustomer customer = mock(AccountCustomer.class);
        customer.address = new Address();
        customer.billingAddress = new Address();
        customer.billingAddress.streetLine1 = "2 rue Basse";
        customer.billingAddress.city = "Paris";
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "1");
        form.add("companyName", "Acme");
        form.add("billingStreet", " ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(1L)).thenReturn(customer);
            resource.save(form);
        }
        assertNull(customer.billingAddress.streetLine1);
        assertNull(customer.billingAddress.city);
        verify(customer).persist();
    }

    /**
     * Nothing on this screen ever creates a customer: the requirement forbids
     * it, so the save of an unknown id must not fall back on an insert.
     */
    @Test
    void nothingIsEverCreatedHere() {
        AdminCustomerResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "9");
        form.add("companyName", "Nouvelle société");
        AccountCustomer never = mock(AccountCustomer.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(9L)).thenReturn(null);
            resource.save(form);
        }
        verify(never, never()).persist();
    }

    /**
     * Stubs a single-result finder onto a field of the customer table.
     *
     * @param panache the active PanacheEntityBase mock
     * @param field the column the finder queries
     * @param value the value looked up
     * @param found the customer to resolve, null when none matches
     */
    @SuppressWarnings("unchecked")
    private void stubFind(MockedStatic<PanacheEntityBase> panache, String field, String value,
            AccountCustomer found) {
        PanacheQuery<AccountCustomer> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(found);
        panache.when(() -> AccountCustomer.find(field, value)).thenReturn(query);
    }

    /**
     * A shop that forbids opening accounts is sent back to the list rather than
     * shown the form ({@code BO-10-04-05}, blocked arm).
     */
    @Test
    void openingAnAccountIsRefusedWhenTheShopForbidsIt() {
        AdminCustomerResource resource = newResource();
        when(resource.posSettingsService.accountCustomerCreationBlocked()).thenReturn(true);
        Response response = resource.create(null, true);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * With the right open, the form is shown over an EMPTY file carrying an
     * address, so the page reads its parts without failing.
     */
    @Test
    void theCreationFormIsShownOverAnEmptyFile() {
        AdminCustomerResource resource = newResource();
        stub(resource.adminCustomer);
        when(resource.posSettingsService.accountCustomerCreationBlocked()).thenReturn(false);
        Response response = resource.create(null, true);
        assertEquals(200, response.getStatus());
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(resource.adminCustomer).data(eq("customer"), captor.capture());
        AccountCustomer blank = (AccountCustomer) captor.getValue();
        assertNull(blank.companyName);
        assertEquals(new Address().city, blank.address.city);
    }

    /**
     * The POST is refused too when the shop forbids it: a form can be hidden, a
     * POST cannot ({@code BO-10-04-05}).
     */
    @Test
    void theCreationPostIsRefusedWhenTheShopForbidsIt() {
        AdminCustomerResource resource = newResource();
        when(resource.posSettingsService.accountCustomerCreationBlocked()).thenReturn(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("accountNumber", "A9");
        form.add("companyName", "Acme");
        Response response = resource.add(form);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/customers?notice="));
    }

    /**
     * An account cannot be opened without a number, nor without a business name —
     * the two mandatory legs.
     */
    @Test
    void openingAnAccountNeedsANumberAndABusinessName() {
        AdminCustomerResource resource = newResource();
        when(resource.posSettingsService.accountCustomerCreationBlocked()).thenReturn(false);
        MultivaluedMap<String, String> noNumber = new MultivaluedHashMap<>();
        noNumber.add("accountNumber", "   ");
        noNumber.add("companyName", "Acme");
        Response first = resource.add(noNumber);
        assertEquals(303, first.getStatus());
        assertTrue(first.getLocation().toString().contains("noticeOk=false"));
        MultivaluedMap<String, String> noName = new MultivaluedHashMap<>();
        noName.add("accountNumber", "A9");
        Response second = resource.add(noName);
        assertEquals(303, second.getStatus());
        assertTrue(second.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * A number already attributed refuses the opening: this is the one road where
     * a human names the number, so it is the one road that must check it.
     */
    @Test
    void anAccountNumberAlreadyAttributedIsRefused() {
        AdminCustomerResource resource = newResource();
        when(resource.posSettingsService.accountCustomerCreationBlocked()).thenReturn(false);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("accountNumber", "A1");
        form.add("companyName", "Acme");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "accountNumber", "A1",
                    customer("A1", "Déjà là", AccountCustomer.Origin.INTEGRATED));
            Response response = resource.add(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * A fiscal identifier already registered refuses the opening too
     * ({@code BO-10-04-18}).
     */
    @Test
    void aFiscalIdentifierAlreadyRegisteredIsRefused() {
        AdminCustomerResource resource = newResource();
        when(resource.posSettingsService.accountCustomerCreationBlocked()).thenReturn(false);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("accountNumber", "A9");
        form.add("companyName", "Acme");
        form.add("taxId", "PT1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "accountNumber", "A9", null);
            stubFind(panache, "taxId", "PT1",
                    customer("A1", "Déjà là", AccountCustomer.Origin.INTEGRATED));
            Response response = resource.add(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * A complete opening writes every field, stamps the origin as opened at the
     * back office, and lands on the new file ({@code BO-10-04-05}).
     */
    @Test
    void acompleteOpeningWritesTheAccount() {
        AdminCustomerResource resource = newResource();
        when(resource.posSettingsService.accountCustomerCreationBlocked()).thenReturn(false);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("accountNumber", "A9");
        form.add("companyName", "Acme SA");
        form.add("civility", "Mme");
        form.add("firstName", "Zoé");
        form.add("lastName", "Durand");
        form.add("street", "10 rue X");
        form.add("postalCode", "75008");
        form.add("city", "Paris");
        form.add("siret", "SIR");
        form.add("vatNumber", "FR1");
        form.add("taxId", "PT2");
        form.add("phone", "0102");
        form.add("email", "a@x.fr");
        form.add("blocked", "on");
        form.add("dueDate", "2026-10-31");
        form.add("discountPercent", "2,50");
        form.add("creditLimit", "1000.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<AccountCustomer> created =
                        mockConstruction(AccountCustomer.class)) {
            stubFind(panache, "accountNumber", "A9", null);
            stubFind(panache, "taxId", "PT2", null);
            Response response = resource.add(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            AccountCustomer written = created.constructed().get(0);
            assertEquals("A9", written.accountNumber);
            assertEquals("Acme SA", written.companyName);
            assertEquals(AccountCustomer.Origin.BACK_OFFICE, written.origin);
            assertEquals("Paris", written.address.city);
            assertEquals("PT2", written.taxId);
            assertTrue(written.blocked);
            assertEquals(LocalDate.of(2026, 10, 31), written.dueDate);
            assertEquals(new BigDecimal("2.50"), written.discountPercent);
            assertEquals(new BigDecimal("1000.00"), written.creditLimit);
            verify(written).persist();
        }
    }

    /**
     * The list page fed only nulls echoes each criterion back as the empty
     * string, exercising the null arm of the four defaulting ternaries.
     */
    @Test
    void theListPageEchoesEmptyStringsForNullCriteria() {
        AdminCustomerResource resource = newResource();
        TemplateInstance view = stub(resource.adminCustomers);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(customer("A1", "Acme", AccountCustomer.Origin.INTEGRATED)));
            assertSame(view, resource.list(null, null, null, null, 1, null, true));
        }
        verify(view).data("text", "");
        verify(view).data("taxId", "");
        verify(view).data("origin", "");
        verify(view).data("blocked", "");
    }

    /**
     * The extraction of a bare customer — no origin, no address, not blocked, no
     * due date, no discount, no credit limit, but a credit balance — walks the
     * opposite arm of every per-cell ternary the fully-populated line did not.
     */
    @Test
    void theExtractionRendersABareCustomer() {
        AdminCustomerResource resource = newResource();
        AccountCustomer bare = new AccountCustomer();
        bare.accountNumber = "Z9";
        bare.companyName = "Zeta";
        bare.origin = null;
        bare.address = null;
        bare.blocked = false;
        bare.dueDate = null;
        bare.discountPercent = null;
        bare.creditLimit = null;
        bare.creditBalance = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(bare));
            Response response = resource.export(null, null, null, null);
            assertEquals(200, response.getStatus());
            String csv = (String) response.getEntity();
            assertTrue(csv.contains("Z9;;Zeta;"));
            assertTrue(csv.contains("false;;;;"));
        }
    }

    /**
     * A file whose address is already set keeps it untouched — the false arm of
     * the address guard the null-address case did not reach.
     */
    @Test
    void afileWithAnAddressKeepsIt() {
        AdminCustomerResource resource = newResource();
        stub(resource.adminCustomer);
        AccountCustomer customer = customer("A1", "Acme", AccountCustomer.Origin.INTEGRATED);
        Address original = customer.address;
        TypedQuery<Object[]> query = mock(TypedQuery.class);
        when(resource.entityManager.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.setParameter("account", "A1")).thenReturn(query);
        when(query.setMaxResults(1)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(1L)).thenReturn(customer);
            resource.edit(1L, null, true);
        }
        assertSame(original, customer.address);
    }

    /**
     * A search over a customer whose account number, business name and contact
     * are all null matches nothing — the null-value arm of the fragment reader.
     */
    @Test
    void aTextSearchOverANamelessCustomerMatchesNothing() {
        AdminCustomerResource resource = newResource();
        AccountCustomer nameless = new AccountCustomer();
        nameless.accountNumber = null;
        nameless.companyName = null;
        nameless.address = new Address();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(nameless));
            assertTrue(resource.search("zzz", null, null, null).isEmpty());
        }
    }

    /**
     * A save whose id is absent or blank is an unknown customer, exercising both
     * the null and the blank arms of the identifier reader.
     */
    @Test
    void savingWithAnAbsentOrBlankIdIsRefused() {
        AdminCustomerResource resource = newResource();
        MultivaluedMap<String, String> absent = new MultivaluedHashMap<>();
        absent.add("companyName", "Acme");
        Response first = resource.save(absent);
        assertEquals(303, first.getStatus());
        assertTrue(first.getLocation().toString().contains("noticeOk=false"));
        MultivaluedMap<String, String> blank = new MultivaluedHashMap<>();
        blank.add("id", "   ");
        Response second = resource.save(blank);
        assertEquals(303, second.getStatus());
        assertTrue(second.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * A save carrying a blank due date clears it — the blank arm of the date
     * reader that the null and unreadable cases did not reach.
     */
    @Test
    void savingWithABlankDueDateClearsIt() {
        AdminCustomerResource resource = newResource();
        AccountCustomer customer = mock(AccountCustomer.class);
        customer.dueDate = LocalDate.of(2026, 1, 1);
        customer.address = new Address();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "1");
        form.add("companyName", "Acme");
        form.add("dueDate", "   ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(1L)).thenReturn(customer);
            resource.save(form);
        }
        assertNull(customer.dueDate);
        verify(customer).persist();
    }

    /**
     * An opening with the blocking checkbox ABSENT and no fiscal identifier leaves
     * the account open and its identifier null — the false arms of both guards.
     */
    @Test
    void anOpeningWithoutBlockingOrIdentifierLeavesThemEmpty() {
        AdminCustomerResource resource = newResource();
        when(resource.posSettingsService.accountCustomerCreationBlocked()).thenReturn(false);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("accountNumber", "A9");
        form.add("companyName", "Acme SA");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<AccountCustomer> created =
                        mockConstruction(AccountCustomer.class)) {
            stubFind(panache, "accountNumber", "A9", null);
            resource.add(form);
            AccountCustomer written = created.constructed().get(0);
            assertNull(written.taxId);
            assertFalse(written.blocked);
        }
    }
    /**
     * BO-02-04-13: replaying the uplink without an administered address says
     * so and comes back to the list when no file is named.
     */
    @Test
    void replayingWithoutAnAddressIsRefused() {
        AdminCustomerResource resource = newResource();
        when(resource.posSettingsService.customerUplinkUrl()).thenReturn("");
        Response response = resource.replayUplink(new MultivaluedHashMap<>());
        assertTrue(response.getLocation().toString().startsWith("/admin/customers?"));
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        org.mockito.Mockito.verify(resource.customerUplinkService, org.mockito.Mockito.never()).deliverPending();
    }

    /**
     * BO-02-04-13: replaying the uplink delivers the pending files at once and
     * comes back to the file it was asked from, with the count acknowledged.
     */
    @Test
    void replayingDeliversThePendingFiles() {
        AdminCustomerResource resource = newResource();
        when(resource.posSettingsService.customerUplinkUrl()).thenReturn("http://gc/clients");
        when(resource.customerUplinkService.deliverPending()).thenReturn(2);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "7");
        Response response = resource.replayUplink(form);
        String location = response.getLocation().toString();
        assertTrue(location.startsWith("/admin/customers/edit?id=7&"));
        assertTrue(location.contains("noticeOk=true"));
        assertTrue(java.net.URLDecoder.decode(location, java.nio.charset.StandardCharsets.UTF_8)
                .contains("2 fiche(s) acquittée(s)"));
    }
}
