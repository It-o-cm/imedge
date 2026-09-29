package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.safe.CoinMachine;
import com.intermarche.pos.service.CoinMachineService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Unit tests of {@link AdminCoinMachineResource}.
 *
 * <p>The figure the software keeps and the figure somebody counted are two
 * columns, and this screen must never merge them by accident: the count writes
 * only the second, and the realignment is a separate, deliberate gesture. Both
 * are asserted here, along with every refusal of the three POSTs.
 */
class AdminCoinMachineResourceTest {

    /**
     * Builds a resource with mocked template and machine service.
     *
     * @return the wired resource
     */
    private AdminCoinMachineResource newResource() {
        AdminCoinMachineResource resource = new AdminCoinMachineResource();
        resource.adminCoinMachines = mock(Template.class);
        resource.coinMachineService = mock(CoinMachineService.class);
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
     * Builds a machine.
     *
     * @param code the code
     * @param declared what the software believes, as a plain decimal string
     * @return the machine, its persist neutralized
     */
    private CoinMachine machine(String code, String declared) {
        CoinMachine machine = mock(CoinMachine.class);
        machine.code = code;
        machine.terminalId = "C01";
        machine.declaredAmount = declared == null ? null : new BigDecimal(declared);
        return machine;
    }

    /**
     * Stubs the park listing and the code lookup.
     *
     * @param panache the active PanacheEntityBase mock
     * @param machines the machines to resolve
     */
    @SuppressWarnings("unchecked")
    private void stubPark(MockedStatic<PanacheEntityBase> panache, List<CoinMachine> machines) {
        PanacheQuery<CoinMachine> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(machines);
        panache.when(() -> CoinMachine.find("order by code")).thenReturn(query);
    }

    /**
     * Stubs the code lookup.
     *
     * @param panache the active PanacheEntityBase mock
     * @param code the code looked up
     * @param found the machine to resolve, null when the code is free
     */
    @SuppressWarnings("unchecked")
    private void stubCode(MockedStatic<PanacheEntityBase> panache, String code, CoinMachine found) {
        PanacheQuery<CoinMachine> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(found);
        panache.when(() -> CoinMachine.find("code", code)).thenReturn(query);
    }

    /**
     * The page carries the park and what it is believed to hold in total, a
     * machine with no running total counting as zero.
     */
    @Test
    void thepageCarriesTheParkAndItsTotal() {
        AdminCoinMachineResource resource = newResource();
        TemplateInstance view = stub(resource.adminCoinMachines);
        CoinMachine first = machine("MON-01", "300.00");
        CoinMachine second = machine("MON-02", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPark(panache, List.of(first, second));
            assertSame(view, resource.list(null, true));
        }
        verify(resource.adminCoinMachines).data("machines", List.of(first, second));
        verify(view).data("total", "300.00");
        assertEquals(BigDecimal.ZERO, resource.totalOf(List.of()));
    }

    /**
     * A machine without a code is refused, and a code already used is refused
     * on a creation as on a correction of another row — the two legs of that
     * compound guard.
     */
    @Test
    void anUnnamedOrDuplicateMachineIsRefused() {
        AdminCoinMachineResource resource = newResource();
        assertTrue(resource.save(new MultivaluedHashMap<>()).getLocation().toString()
                .contains("noticeOk=false"));
        CoinMachine existing = machine("MON-01", "300.00");
        existing.id = 1L;
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("code", "MON-01");
        form.add("id", "2");
        CoinMachine other = machine("MON-02", "0.00");
        other.id = 2L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachine> created = mockConstruction(CoinMachine.class)) {
            stubCode(panache, "MON-01", existing);
            panache.when(() -> CoinMachine.findById(2L)).thenReturn(other);
            assertTrue(resource.save(form).getLocation().toString().contains("noticeOk=false"));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * A new machine is written, in service, with its register.
     */
    @Test
    void anewMachineIsWritten() {
        AdminCoinMachineResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("code", "MON-03");
        form.add("label", "Monnayeur caisse 3");
        form.add("terminalId", "C03");
        form.add("active", "on");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachine> created = mockConstruction(CoinMachine.class)) {
            stubCode(panache, "MON-03", null);
            assertTrue(resource.save(form).getLocation().toString().contains("noticeOk=true"));
            CoinMachine written = created.constructed().get(0);
            assertEquals("MON-03", written.code);
            assertEquals("Monnayeur caisse 3", written.label);
            assertEquals("C03", written.terminalId);
            assertTrue(written.active);
            verify(written).persist();
        }
    }

    /**
     * An existing machine is corrected in place, and the box left unticked takes
     * it out of service — the false arm of that guard.
     */
    @Test
    void anExistingMachineIsCorrectedInPlace() {
        AdminCoinMachineResource resource = newResource();
        CoinMachine machine = machine("MON-01", "300.00");
        machine.id = 1L;
        machine.active = true;
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "1");
        form.add("code", "MON-01");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachine> created = mockConstruction(CoinMachine.class)) {
            stubCode(panache, "MON-01", machine);
            panache.when(() -> CoinMachine.findById(1L)).thenReturn(machine);
            assertTrue(resource.save(form).getLocation().toString().contains("noticeOk=true"));
            assertTrue(created.constructed().isEmpty());
        }
        assertFalse(machine.active);
        assertNull(machine.label);
        verify(machine).persist();
    }

    /**
     * A count writes only the counted column and leaves the running total alone
     * — the whole point of the two columns.
     */
    @Test
    void acountWritesOnlyTheCountedColumn() {
        AdminCoinMachineResource resource = newResource();
        CoinMachine machine = machine("MON-01", "300.00");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "1");
        form.add("countedAmount", "290,00");
        when(machine.isReconciled()).thenReturn(false);
        when(machine.getVarianceFormatted()).thenReturn("-10.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinMachine.findById(1L)).thenReturn(machine);
            String location = resource.count(form).getLocation().toString();
            assertTrue(location.contains("noticeOk=false"));
            assertTrue(location.contains("-10.00"));
        }
        assertEquals(new BigDecimal("290.00"), machine.countedAmount);
        assertEquals(new BigDecimal("300.00"), machine.declaredAmount);
        assertNotNull(machine.countedOn);
        verify(machine).persist();
    }

    /**
     * A count that agrees with the software says so — the other arm of the
     * outcome message.
     */
    @Test
    void acountThatAgreesSaysSo() {
        AdminCoinMachineResource resource = newResource();
        CoinMachine machine = machine("MON-01", "300.00");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "1");
        form.add("countedAmount", "300,00");
        when(machine.isReconciled()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinMachine.findById(1L)).thenReturn(machine);
            assertTrue(resource.count(form).getLocation().toString().contains("noticeOk=true"));
        }
    }

    /**
     * A count is refused on an unknown machine and on an absent, unreadable or
     * negative amount — the four refusing legs.
     */
    @Test
    void acountIsRefusedOnEveryLegOfItsGuard() {
        AdminCoinMachineResource resource = newResource();
        assertTrue(resource.count(new MultivaluedHashMap<>()).getLocation().toString()
                .contains("noticeOk=false"));
        CoinMachine machine = machine("MON-01", "300.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinMachine.findById(1L)).thenReturn(machine);
            panache.when(() -> CoinMachine.findById(9L)).thenReturn(null);
            MultivaluedMap<String, String> unknown = new MultivaluedHashMap<>();
            unknown.add("id", "9");
            assertTrue(resource.count(unknown).getLocation().toString().contains("noticeOk=false"));
            for (String amount : new String[] {null, "beaucoup", "-1,00"}) {
                MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
                form.add("id", "1");
                if (amount != null) {
                    form.add("countedAmount", amount);
                }
                assertTrue(resource.count(form).getLocation().toString()
                        .contains("noticeOk=false"));
            }
            verify(machine, never()).persist();
        }
    }

    /**
     * The realignment takes the counted figure as the new running total.
     */
    @Test
    void therealignmentTakesTheCountedFigure() {
        AdminCoinMachineResource resource = newResource();
        CoinMachine machine = machine("MON-01", "300.00");
        machine.countedAmount = new BigDecimal("290.00");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinMachine.findById(1L)).thenReturn(machine);
            assertTrue(resource.align(form).getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals(new BigDecimal("290.00"), machine.declaredAmount);
        assertNotNull(machine.updatedOn);
        verify(machine).persist();
    }

    /**
     * The realignment is refused on an unknown machine and on one never counted
     * — the two legs of that guard.
     */
    @Test
    void therealignmentIsRefusedWithoutAcount() {
        AdminCoinMachineResource resource = newResource();
        assertTrue(resource.align(new MultivaluedHashMap<>()).getLocation().toString()
                .contains("noticeOk=false"));
        CoinMachine never = machine("MON-01", "300.00");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinMachine.findById(1L)).thenReturn(never);
            assertTrue(resource.align(form).getLocation().toString().contains("noticeOk=false"));
            verify(never, org.mockito.Mockito.never()).persist();
        }
    }

    /**
     * A loading reaches the machine service, and is refused on an absent, zero
     * or unreadable amount and on an unknown machine — the four legs.
     */
    @Test
    void aloadingReachesTheMachineService() {
        AdminCoinMachineResource resource = newResource();
        CoinMachine machine = machine("MON-01", "300.00");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("code", "MON-01");
        form.add("amount", "100,00");
        when(resource.coinMachineService.load("MON-01", new BigDecimal("100.00")))
                .thenReturn(machine, (CoinMachine) null);
        assertTrue(resource.load(form).getLocation().toString().contains("noticeOk=true"));
        assertTrue(resource.load(form).getLocation().toString().contains("noticeOk=false"));
        for (String amount : new String[] {null, "0", "beaucoup"}) {
            MultivaluedMap<String, String> bad = new MultivaluedHashMap<>();
            bad.add("code", "MON-01");
            if (amount != null) {
                bad.add("amount", amount);
            }
            assertTrue(resource.load(bad).getLocation().toString().contains("noticeOk=false"));
        }
        // The two calls that reached the service are the two usable amounts; the
        // three unusable ones were refused before it was ever asked.
        verify(resource.coinMachineService, org.mockito.Mockito.times(2))
                .load("MON-01", new BigDecimal("100.00"));
    }

    /**
     * The extraction states one row per machine, the separator neutralized and
     * a machine never counted leaving its cell empty.
     */
    @Test
    void theextractionStatesOneRowPerMachine() {
        AdminCoinMachineResource resource = newResource();
        CoinMachine counted = machine("MON;01", "300.00");
        counted.countedAmount = new BigDecimal("290.00");
        counted.active = true;
        when(counted.getDeclaredAmountFormatted()).thenReturn("300.00");
        when(counted.getVarianceFormatted()).thenReturn("-10.00");
        CoinMachine never = machine("MON-02", "0.00");
        never.active = false;
        when(never.getDeclaredAmountFormatted()).thenReturn("0.00");
        when(never.getVarianceFormatted()).thenReturn("0.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPark(panache, List.of(counted, never));
            Response response = resource.export();
            assertEquals(200, response.getStatus());
            String csv = String.valueOf(response.getEntity());
            assertTrue(csv.startsWith("CODE;LIBELLE;CAISSE;ACTIF;TENU;COMPTE;ECART\n"));
            assertTrue(csv.contains("MON,01;;C01;Oui;300.00;290.00;-10.00"));
            assertTrue(csv.contains("MON-02;;C01;Non;0.00;;0.00"));
            assertTrue(response.getHeaderString("Content-Disposition")
                    .contains("monnayeurs.csv"));
        }
    }

    /**
     * A CREATION whose code is already taken is refused — the machine-null arm of
     * the clash guard, distinct from the correction-of-another-row arm.
     */
    @Test
    void acreationWhoseCodeIsAlreadyTakenIsRefused() {
        AdminCoinMachineResource resource = newResource();
        CoinMachine existing = machine("MON-01", "300.00");
        existing.id = 1L;
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("code", "MON-01");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinMachine> created = mockConstruction(CoinMachine.class)) {
            stubCode(panache, "MON-01", existing);
            assertTrue(resource.save(form).getLocation().toString().contains("noticeOk=false"));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * The amount formatter maps a null amount to zero — the null arm of a guard
     * no reachable caller reaches, exercised on the helper directly.
     *
     * @throws Exception when the helper cannot be reached by reflection
     */
    @Test
    void formatMapsAnullAmountToZero() throws Exception {
        AdminCoinMachineResource resource = newResource();
        java.lang.reflect.Method format =
                AdminCoinMachineResource.class.getDeclaredMethod("format", BigDecimal.class);
        format.setAccessible(true);
        assertEquals("0.00", format.invoke(resource, (BigDecimal) null));
    }
}
