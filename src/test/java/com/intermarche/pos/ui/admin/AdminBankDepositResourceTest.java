package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.safe.BankDeposit;
import com.intermarche.pos.domain.safe.CashPouch;
import com.intermarche.pos.security.CurrentUser;
import com.intermarche.pos.service.SafeService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AdminBankDepositResource}.
 *
 * <p>What is proved here is that the pouches PICKED are the ones that go — a
 * pouch no longer in the safe is dropped rather than banked twice — and that the
 * two accounting pieces state the bag, its pouches and its total. The refusals
 * are asserted on each of their legs: no bag number, a bag already used, no
 * pouch at all, and a safe with no period open.
 */
class AdminBankDepositResourceTest {

    /**
     * Builds a resource with mocked template, safe and identity.
     *
     * @return the wired resource
     */
    private AdminBankDepositResource newResource() {
        AdminBankDepositResource resource = new AdminBankDepositResource();
        resource.adminBankDeposits = mock(Template.class);
        resource.safeService = mock(SafeService.class);
        resource.currentUser = mock(CurrentUser.class);
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
     * Builds a pouch held in the safe.
     *
     * @param number the pouch number
     * @param amount the amount, as a plain decimal string, null for none
     * @return the pouch
     */
    private CashPouch pouch(String number, String amount) {
        CashPouch pouch = new CashPouch();
        pouch.pouchNumber = number;
        pouch.accountingDay = LocalDate.of(2026, 9, 15);
        pouch.amount = amount == null ? null : new BigDecimal(amount);
        return pouch;
    }

    /**
     * Stubs the bag-number lookup.
     *
     * @param panache the active PanacheEntityBase mock
     * @param bag the bag number
     * @param found the deposit to resolve, null when the number is free
     */
    @SuppressWarnings("unchecked")
    private void stubBag(MockedStatic<PanacheEntityBase> panache, String bag, BankDeposit found) {
        PanacheQuery<BankDeposit> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(found);
        panache.when(() -> BankDeposit.find("bagNumber", bag)).thenReturn(query);
    }

    /**
     * The page carries the deposits and what is still available to send.
     */
    @Test
    void thepageCarriesWhatIsAvailableToSend() {
        AdminBankDepositResource resource = newResource();
        TemplateInstance view = stub(resource.adminBankDeposits);
        CashPouch held = pouch("POCH-1", "1250.00");
        BankDeposit deposit = new BankDeposit();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<CashPouch> available = mock(PanacheQuery.class);
            when(available.list()).thenReturn(List.of(held));
            panache.when(() -> CashPouch.find("status = ?1 order by accountingDay, id",
                    CashPouch.PouchStatus.IN_SAFE)).thenReturn(available);
            PanacheQuery<BankDeposit> deposits = mock(PanacheQuery.class);
            when(deposits.list()).thenReturn(List.of(deposit));
            panache.when(() -> BankDeposit.find("order by depositDay desc, id desc"))
                    .thenReturn(deposits);
            assertSame(view, resource.list(null, true));
        }
        verify(resource.adminBankDeposits).data("deposits", List.of(deposit));
        verify(view).data("available", List.of(held));
        verify(view).data("availableTotal", "1250.00");
    }

    /**
     * A deposit without a bag number, and one whose number is already used, are
     * refused — the two legs of that guard.
     */
    @Test
    void anUnnamedOrDuplicateDepositIsRefused() {
        AdminBankDepositResource resource = newResource();
        MultivaluedMap<String, String> unnamed = new MultivaluedHashMap<>();
        unnamed.add("bagNumber", "  ");
        assertTrue(resource.deposit(unnamed).getLocation().toString().contains("noticeOk=false"));
        BankDeposit existing = new BankDeposit();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("bagNumber", "SAC-42");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<BankDeposit> created = mockConstruction(BankDeposit.class)) {
            stubBag(panache, "SAC-42", existing);
            assertTrue(resource.deposit(form).getLocation().toString().contains("noticeOk=false"));
            assertTrue(created.constructed().isEmpty());
        }
        verify(resource.safeService, never()).depositToBank(any(), any(), any());
    }

    /**
     * A deposit picking no pouch is refused: the bag would leave empty.
     */
    @Test
    void adepositPickingNoPouchIsRefused() {
        AdminBankDepositResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("bagNumber", "SAC-42");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<BankDeposit> created = mockConstruction(BankDeposit.class)) {
            stubBag(panache, "SAC-42", null);
            assertTrue(resource.deposit(form).getLocation().toString().contains("noticeOk=false"));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * The picked pouches are the ones still in the safe: an unreadable
     * identifier, one that names nothing and one already banked are all dropped
     * — the three refusing legs of that reader.
     */
    @Test
    void onlyThePouchesStillInTheSafeArePicked() {
        AdminBankDepositResource resource = newResource();
        CashPouch held = pouch("POCH-1", "1250.00");
        CashPouch banked = pouch("POCH-2", "800.00");
        banked.status = CashPouch.PouchStatus.BANKED;
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("pouchId", "1");
        form.add("pouchId", "2");
        form.add("pouchId", "9");
        form.add("pouchId", "abc");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashPouch.findById(1L)).thenReturn(held);
            panache.when(() -> CashPouch.findById(2L)).thenReturn(banked);
            panache.when(() -> CashPouch.findById(9L)).thenReturn(null);
            assertEquals(List.of(held), resource.pickedPouches(form));
        }
        assertTrue(resource.pickedPouches(new MultivaluedHashMap<>()).isEmpty());
    }

    /**
     * A complete deposit reaches the safe with its bag, its day and its pouches.
     */
    @Test
    void acompleteDepositReachesTheSafe() {
        AdminBankDepositResource resource = newResource();
        CashPouch held = pouch("POCH-1", "1250.00");
        Employee declarer = new Employee();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("bagNumber", "SAC-42");
        form.add("label", "Remise du lundi");
        form.add("depositDay", "2026-09-16");
        form.add("pouchId", "1");
        when(resource.currentUser.getName()).thenReturn("alice");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<BankDeposit> created = mockConstruction(BankDeposit.class)) {
            stubBag(panache, "SAC-42", null);
            panache.when(() -> CashPouch.findById(1L)).thenReturn(held);
            PanacheQuery<Employee> who = mock(PanacheQuery.class);
            when(who.firstResult()).thenReturn(declarer);
            panache.when(() -> Employee.find("loginName", "alice")).thenReturn(who);
            assertTrue(resource.deposit(form).getLocation().toString().contains("noticeOk=true"));
            BankDeposit written = created.constructed().get(0);
            assertEquals("SAC-42", written.bagNumber);
            assertEquals("Remise du lundi", written.label);
            assertEquals(LocalDate.of(2026, 9, 16), written.depositDay);
            verify(resource.safeService).depositToBank(written, List.of(held), declarer);
        }
    }

    /**
     * A deposit with no day leaves today, and a safe with no period open refuses
     * it — the null arm of the day guard and the refusing arm of the answer.
     */
    @Test
    void adepositWithoutAdayLeavesTodayAndMayStillBeRefused() {
        AdminBankDepositResource resource = newResource();
        CashPouch held = pouch("POCH-1", "1250.00");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("bagNumber", "SAC-43");
        form.add("pouchId", "1");
        when(resource.safeService.depositToBank(any(), any(), any()))
                .thenReturn(SafeService.NO_OPEN_PERIOD);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<BankDeposit> created = mockConstruction(BankDeposit.class)) {
            stubBag(panache, "SAC-43", null);
            panache.when(() -> CashPouch.findById(1L)).thenReturn(held);
            assertTrue(resource.deposit(form).getLocation().toString().contains("noticeOk=false"));
            assertEquals(LocalDate.now(), created.constructed().get(0).depositDay);
        }
    }

    /**
     * The {@code bordereau} states the bag, its pouches and its total; the
     * {@code versement} states the one figure the bank credits.
     */
    @Test
    void thetwoPiecesStateTheBagAndItsPouches() {
        AdminBankDepositResource resource = newResource();
        BankDeposit deposit = new BankDeposit();
        deposit.id = 3L;
        deposit.bagNumber = "SAC-42";
        deposit.label = "Remise du lundi";
        deposit.depositDay = LocalDate.of(2026, 9, 16);
        deposit.amount = new BigDecimal("1250.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> BankDeposit.findById(3L)).thenReturn(deposit);
            PanacheQuery<CashPouch> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of(pouch("POCH-1", "1250.00")));
            panache.when(() -> CashPouch.find("bankDeposit.id = ?1 order by accountingDay, id", 3L))
                    .thenReturn(query);
            Response bordereau = resource.bordereau(3L);
            assertEquals(200, bordereau.getStatus());
            String piece = String.valueOf(bordereau.getEntity());
            assertTrue(piece.startsWith("BORDEREAU ESPECES\n"));
            assertTrue(piece.contains("Sac : SAC-42"));
            assertTrue(piece.contains("Objet : Remise du lundi"));
            assertTrue(piece.contains("POCH-1"));
            assertTrue(piece.contains("TOTAL : 1250.00 EUR"));
            assertTrue(bordereau.getHeaderString("Content-Disposition")
                    .contains("bordereau-especes-SAC-42.txt"));
            Response versement = resource.versement(3L);
            String second = String.valueOf(versement.getEntity());
            assertTrue(second.startsWith("VERSEMENT ESPECES\n"));
            assertTrue(second.contains("Nombre de pochettes : 1"));
            assertTrue(second.contains("Montant verse : 1250.00 EUR"));
        }
    }

    /**
     * A deposit carrying no wording leaves the line off — the blank arm of that
     * guard — and a deposit never written has no pouches to list.
     */
    @Test
    void thepiecesTolerateTheMissingParts() {
        AdminBankDepositResource resource = newResource();
        BankDeposit bare = new BankDeposit();
        bare.bagNumber = "SAC-44";
        bare.depositDay = LocalDate.of(2026, 9, 16);
        bare.label = "   ";
        assertTrue(!resource.renderBordereau(bare).contains("Objet :"));
        assertTrue(resource.pouchesOf(bare).isEmpty());
        assertTrue(resource.pouchesOf(null).isEmpty());
    }

    /**
     * An unknown deposit answers a 404 on both pieces, on both legs of the
     * guard.
     */
    @Test
    void anUnknownDepositAnswersNotFound() {
        AdminBankDepositResource resource = newResource();
        assertEquals(404, resource.bordereau(null).getStatus());
        assertEquals(404, resource.versement(null).getStatus());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> BankDeposit.findById(9L)).thenReturn(null);
            assertEquals(404, resource.bordereau(9L).getStatus());
            assertEquals(404, resource.versement(9L).getStatus());
        }
    }

    /**
     * The total of a list of pouches counts a pouch carrying no amount as zero —
     * the null arm of that guard.
     */
    @Test
    void thetotalCountsApouchWithoutAmountAsZero() {
        AdminBankDepositResource resource = newResource();
        assertEquals(new BigDecimal("1250.00"),
                resource.totalOf(List.of(pouch("POCH-1", "1250.00"), pouch("POCH-2", null))));
        assertEquals(BigDecimal.ZERO, resource.totalOf(List.of()));
    }

    /**
     * The signed-in employee is resolved by login, and is nobody when the
     * session names nobody — the three legs of that guard.
     */
    @Test
    void thesignedInEmployeeIsResolvedByLogin() {
        AdminBankDepositResource resource = newResource();
        Employee declarer = new Employee();
        when(resource.currentUser.getName()).thenReturn("alice", "", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Employee> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(declarer);
            panache.when(() -> Employee.find("loginName", "alice")).thenReturn(query);
            assertSame(declarer, resource.signedIn());
            assertNull(resource.signedIn());
            assertNull(resource.signedIn());
        }
        AdminBankDepositResource anonymous = newResource();
        anonymous.currentUser = null;
        assertNull(anonymous.signedIn());
    }
}
