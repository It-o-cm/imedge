package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.people.Menu;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.store.CheckoutIsland;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.DisplayType;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.TpvType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminCheckoutPointResource}.
 * <p>
 * Plain JUnit 5 + Mockito, no Quarkus and no database. Every guard of the save
 * path (unknown island, PDV or hostess) and both the insert and the delete arms
 * are exercised (BO-03-09).
 */
class AdminCheckoutPointResourceTest {

    /**
     * Builds a resource with a mocked template.
     *
     * @return the resource
     */
    private AdminCheckoutPointResource newResource() {
        AdminCheckoutPointResource resource = new AdminCheckoutPointResource();
        resource.adminCheckoutPoints = mock(Template.class);
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the template to one self-returning
     * instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked instance the chain returns
     */
    private TemplateInstance wireTemplate(AdminCheckoutPointResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminCheckoutPoints.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds a posted form from key/value pairs.
     *
     * @param pairs the alternating keys and values
     * @return the form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> posted = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            posted.putSingle(pairs[i], pairs[i + 1]);
        }
        return posted;
    }

    /**
     * Wraps a first-result in a Panache query mock.
     *
     * @param row the row the query resolves, or null
     * @param <T> the row type
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> query(T row) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /**
     * The screen is declared in the parametrage menu (BO-03-09).
     */
    @Test
    void theScreenIsDeclaredInTheMenu() {
        Feature feature = Feature.CHECKOUT_POINT;
        assertEquals("checkout_point", feature.getKey());
        assertEquals("/admin/checkout-points", feature.getPath());
        assertEquals(Menu.PARAMETRAGE, feature.getMenu());
    }

    /**
     * The page lists the checkout points.
     */
    @Test
    void pointsPageListsThePoints() {
        AdminCheckoutPointResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        CheckoutPoint point = new CheckoutPoint();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutPoint.list("order by terminalId")).thenReturn(List.of(point));
            panache.when(() -> CheckoutIsland.list("order by code")).thenReturn(List.of());
            panache.when(() -> Pdv.list("order by pdvNumber")).thenReturn(List.of());
            assertSame(instance, resource.pointsPage(null, true));
            verify(resource.adminCheckoutPoints).data("points", List.of(point));
        }
    }

    /**
     * A blank terminal identifier is refused (first guard).
     */
    @Test
    void saveRefusesABlankTerminal() {
        Response response = newResource().savePoint(form());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * An island the store does not carry is refused.
     */
    @Test
    void saveRefusesAnUnknownIsland() {
        AdminCheckoutPointResource resource = newResource();
        PanacheQuery<CheckoutIsland> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutIsland.find("code", "GHOST")).thenReturn(absent);
            Response response = resource.savePoint(form("terminalId", "POS01", "islandCode", "GHOST"));
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * A PDV the store does not carry is refused.
     */
    @Test
    void saveRefusesAnUnknownPdv() {
        AdminCheckoutPointResource resource = newResource();
        PanacheQuery<Pdv> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Pdv.find("pdvNumber", "99999")).thenReturn(absent);
            Response response = resource.savePoint(form("terminalId", "POS01", "pdvNumber", "99999"));
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * A technical hostess whose badge is unknown is refused (BO-03-09-08).
     */
    @Test
    void saveRefusesAnUnknownHostess() {
        AdminCheckoutPointResource resource = newResource();
        PanacheQuery<Employee> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.find("badgeId", "BADGE9")).thenReturn(absent);
            Response response = resource.savePoint(
                    form("terminalId", "POS01", "technicalHostessBadge", "BADGE9"));
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * An absent terminal inserts an active point, its type taken from the form
     * and an unknown display type falling back to NONE.
     */
    @Test
    void saveInsertsAnActivePoint() {
        AdminCheckoutPointResource resource = newResource();
        PanacheQuery<CheckoutPoint> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CheckoutPoint> created = mockConstruction(CheckoutPoint.class)) {
            panache.when(() -> CheckoutPoint.find("terminalId", "POS01")).thenReturn(absent);
            Response response = resource.savePoint(form("terminalId", " POS01 ", "label", "Caisse 1",
                    "tpvType", "SELF_CHECKOUT_TGCS", "cashierDisplayType", "GRAPHIC",
                    "customerDisplayType", "NOPE", "active", "on"));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            CheckoutPoint point = created.constructed().get(0);
            assertEquals("POS01", point.terminalId);
            assertEquals("Caisse 1", point.label);
            assertEquals(TpvType.SELF_CHECKOUT_TGCS, point.tpvType);
            assertEquals(DisplayType.GRAPHIC, point.cashierDisplayType);
            assertEquals(DisplayType.NONE, point.customerDisplayType);
            assertTrue(point.active);
            verify(point, times(1)).persist();
        }
    }

    /**
     * BO-03-09-15/-24/-25/-26: the peripherals of a point of contact are
     * administered here — protocol, address and port for the scale and for the
     * coin machine.
     */
    @Test
    void saveKeepsTheAdministeredPeripherals() {
        AdminCheckoutPointResource resource = newResource();
        CheckoutPoint stored = new CheckoutPoint();
        stored.terminalId = "POS01";
        PanacheQuery<CheckoutPoint> present = query(stored);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutPoint.find("terminalId", "POS01")).thenReturn(present);
            assertTrue(resource.savePoint(form("terminalId", "POS01",
                    "scaleProtocol", "DIGI", "scaleAddress", "10.0.0.8", "scalePort", "4001",
                    "coinMachineProtocol", "GLORY", "coinMachineAddress", "10.0.0.9",
                    "coinMachinePort", "5001")).getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals(com.intermarche.edge.domain.store.ScaleProtocol.DIGI, stored.scaleProtocol);
        assertEquals("10.0.0.8", stored.scaleAddress);
        assertEquals(Integer.valueOf(4001), stored.scalePort);
        assertEquals(com.intermarche.edge.domain.store.CoinMachineProtocol.GLORY,
                stored.coinMachineProtocol);
        assertEquals("10.0.0.9", stored.coinMachineAddress);
        assertEquals(Integer.valueOf(5001), stored.coinMachinePort);
    }

    /**
     * An unknown protocol falls back to NONE on both machines, and a port that
     * is blank, not a number, zero or past the last TCP port is NOT stored —
     * null and not zero, because zero is a port the register would try. Every
     * leg of the port guard, and the two bounds that are accepted.
     */
    @Test
    void saveRefusesAProtocolAndAPortItCannotRead() {
        AdminCheckoutPointResource resource = newResource();
        CheckoutPoint stored = new CheckoutPoint();
        stored.terminalId = "POS01";
        PanacheQuery<CheckoutPoint> present = query(stored);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutPoint.find("terminalId", "POS01")).thenReturn(present);
            resource.savePoint(form("terminalId", "POS01",
                    "scaleProtocol", "BIZERBA", "coinMachineProtocol", "SCAN_COIN"));
            assertEquals(com.intermarche.edge.domain.store.ScaleProtocol.NONE,
                    stored.scaleProtocol);
            assertEquals(com.intermarche.edge.domain.store.CoinMachineProtocol.NONE,
                    stored.coinMachineProtocol);
            assertNull(stored.scalePort);

            resource.savePoint(form("terminalId", "POS01", "scalePort", "   "));
            assertNull(stored.scalePort);
            resource.savePoint(form("terminalId", "POS01", "scalePort", "quatre mille"));
            assertNull(stored.scalePort);
            resource.savePoint(form("terminalId", "POS01", "scalePort", "0"));
            assertNull(stored.scalePort);
            resource.savePoint(form("terminalId", "POS01", "scalePort", "65536"));
            assertNull(stored.scalePort);
            resource.savePoint(form("terminalId", "POS01", "scalePort", "1"));
            assertEquals(Integer.valueOf(1), stored.scalePort);
            resource.savePoint(form("terminalId", "POS01", "scalePort", "65535"));
            assertEquals(Integer.valueOf(65535), stored.scalePort);
        }
    }

    /**
     * The page serves the two protocol lists to the form, so a paramétreur
     * picks a protocol rather than typing one.
     */
    @Test
    void thePageServesTheProtocolChoices() {
        AdminCheckoutPointResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutPoint.list("order by terminalId"))
                    .thenReturn(java.util.List.of());
            assertSame(instance, resource.pointsPage(null, true));
            verify(instance).data("scaleProtocols",
                    com.intermarche.edge.domain.store.ScaleProtocol.values());
            verify(instance).data("coinProtocols",
                    com.intermarche.edge.domain.store.CoinMachineProtocol.values());
        }
    }

    /**
     * Valid links are accepted (the second arm of the island, PDV and hostess
     * guards): the point is attached to the island, the PDV and the hostess.
     */
    @Test
    void saveAcceptsValidLinks() {
        AdminCheckoutPointResource resource = newResource();
        CheckoutIsland island = new CheckoutIsland();
        island.code = "AVANT";
        Pdv pdv = new Pdv();
        pdv.pdvNumber = "12345";
        Employee hostess = new Employee();
        PanacheQuery<CheckoutIsland> foundIsland = query(island);
        PanacheQuery<Pdv> foundPdv = query(pdv);
        PanacheQuery<Employee> foundHostess = query(hostess);
        PanacheQuery<CheckoutPoint> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CheckoutPoint> created = mockConstruction(CheckoutPoint.class)) {
            panache.when(() -> CheckoutIsland.find("code", "AVANT")).thenReturn(foundIsland);
            panache.when(() -> Pdv.find("pdvNumber", "12345")).thenReturn(foundPdv);
            panache.when(() -> Employee.find("badgeId", "BADGE1")).thenReturn(foundHostess);
            panache.when(() -> CheckoutPoint.find("terminalId", "POS02")).thenReturn(absent);
            Response response = resource.savePoint(form("terminalId", "POS02", "islandCode", "AVANT",
                    "pdvNumber", "12345", "technicalHostessBadge", "BADGE1"));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            CheckoutPoint point = created.constructed().get(0);
            assertEquals("AVANT", point.islandCode);
            assertEquals("12345", point.pdvNumber);
            assertEquals("BADGE1", point.technicalHostessBadge);
        }
    }

    /**
     * A present terminal updates the stored point in place, an unknown type
     * falling back to the manned till (the {@code fromName} null arm).
     */
    @Test
    void saveUpdatesAnExistingPoint() {
        AdminCheckoutPointResource resource = newResource();
        CheckoutPoint stored = new CheckoutPoint();
        stored.terminalId = "POS01";
        PanacheQuery<CheckoutPoint> present = query(stored);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutPoint.find("terminalId", "POS01")).thenReturn(present);
            Response response = resource.savePoint(form("terminalId", "POS01", "label", "Comptoir",
                    "tpvType", "NOPE", "menuTemplateCode", "MENU9"));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("Comptoir", stored.label);
        assertEquals(TpvType.CASH_REGISTER, stored.tpvType);
        assertEquals("MENU9", stored.menuTemplateCode);
    }

    /**
     * Deleting an unknown point is refused.
     */
    @Test
    void deleteRefusesAnUnknownPoint() {
        AdminCheckoutPointResource resource = newResource();
        PanacheQuery<CheckoutPoint> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutPoint.find("terminalId", "POS01")).thenReturn(absent);
            Response response = resource.deletePoint(form("terminalId", "POS01"));
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * Deleting a known point removes it.
     */
    @Test
    void deleteRemovesAKnownPoint() {
        AdminCheckoutPointResource resource = newResource();
        CheckoutPoint stored = mock(CheckoutPoint.class);
        PanacheQuery<CheckoutPoint> present = query(stored);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutPoint.find("terminalId", "POS01")).thenReturn(present);
            Response response = resource.deletePoint(form("terminalId", "POS01"));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            verify(stored, times(1)).delete();
            verify(stored, never()).persist();
        }
    }
}
