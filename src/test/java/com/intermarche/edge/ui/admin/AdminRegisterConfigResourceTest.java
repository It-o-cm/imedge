package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.setting.DocumentTemplate;
import com.intermarche.edge.domain.setting.EchelonLevel;
import com.intermarche.edge.domain.setting.EchelonSetting;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.setting.StoreImage;
import com.intermarche.edge.domain.store.CashMenu;
import com.intermarche.edge.domain.store.CheckoutIsland;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.TpvType;
import com.intermarche.edge.service.EchelonSettingService;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.TrainingDataService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminRegisterConfigResource} (BO-03-09, BO-03-10,
 * BO-03-10-18, BO-03-12-03): the page, the per-level save, the grouped
 * configuration by island, type, list and all, the training data, and every
 * helper — field kinds, choice lists, value checks, target selection.
 */
class AdminRegisterConfigResourceTest {

    /** The query of a value posed at one echelon. */
    private static final String VALUE_QUERY = "level = ?1 and echelonCode = ?2 and settingKey = ?3";

    /**
     * Builds a resource with mocked collaborators and a self-returning template chain.
     *
     * @return the resource
     */
    private AdminRegisterConfigResource newResource() {
        AdminRegisterConfigResource resource = new AdminRegisterConfigResource();
        resource.adminRegisterConfig = mock(Template.class);
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminRegisterConfig.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        resource.echelonSettings = mock(EchelonSettingService.class);
        resource.trainingDataService = mock(TrainingDataService.class);
        return resource;
    }

    /**
     * Builds a query answering one row.
     *
     * @param row the row, or null
     * @param <T> the row type
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> query(T row) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
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
     * Stubs the lookup of an island and of a point.
     *
     * @param panache the static mock
     * @param island the island answered for code AV, or null
     * @param point the point answered for terminal C04, or null
     */
    private void stubPark(MockedStatic<PanacheEntityBase> panache, CheckoutIsland island, CheckoutPoint point) {
        PanacheQuery<CheckoutIsland> islandQuery = query(island);
        PanacheQuery<CheckoutPoint> pointQuery = query(point);
        panache.when(() -> CheckoutIsland.find("code", "AV")).thenReturn(islandQuery);
        panache.when(() -> CheckoutPoint.find("terminalId", "C04")).thenReturn(pointQuery);
    }

    /** The screen is declared in the parametrage menu. */
    @Test
    void theScreenIsDeclared() {
        assertEquals("/admin/register-config", Feature.REGISTER_CONFIG.getPath());
    }

    /**
     * The page without selection lists the park; with a known island it builds
     * every field with its posted value; an unknown code or level builds none.
     */
    @Test
    void pageBuildsTheFieldsOfAKnownLevelOnly() {
        AdminRegisterConfigResource resource = newResource();
        EchelonSetting posted = new EchelonSetting();
        posted.settingValue = "SLS";
        PanacheQuery<EchelonSetting> postedQuery = query(posted);
        PanacheQuery<EchelonSetting> none = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPark(panache, new CheckoutIsland(), null);
            for (String key : AdminRegisterConfigResource.KEYS) {
                panache.when(() -> EchelonSetting.find(VALUE_QUERY, EchelonLevel.ISLAND, "AV", key))
                        .thenReturn("menu.code".equals(key) ? postedQuery : none);
            }
            assertNotNull(resource.page(null, null, null, true));
            assertNotNull(resource.page("ISLAND", " AV ", "ok", true));
            assertNotNull(resource.page("CHECKOUT_POINT", "C04", null, false));
            assertNotNull(resource.page("COUNTRY", "FR", null, true));
            verify(resource.adminRegisterConfig, org.mockito.Mockito.times(4)).data(anyString(), any());
            List<AdminRegisterConfigResource.Field> fields = resource.fields(EchelonLevel.ISLAND, "AV");
            assertEquals(AdminRegisterConfigResource.KEYS.size(), fields.size());
            assertEquals("SLS", fields.stream().filter(f -> f.key().equals("menu.code")).findFirst()
                    .orElseThrow().value());
        }
    }

    /** A save on an unknown level, an unknown code or a null one is refused. */
    @Test
    void saveRefusesAnUnknownTarget() {
        AdminRegisterConfigResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPark(panache, null, null);
            assertTrue(resource.save(form("level", "PDV", "code", "AV")).getLocation().toString()
                    .contains("noticeOk=false"));
            assertTrue(resource.save(form("level", "ISLAND", "code", "AV")).getLocation().toString()
                    .contains("noticeOk=false"));
            assertTrue(resource.save(form("level", "ISLAND")).getLocation().toString()
                    .contains("noticeOk=false"));
        }
        verifyNoInteractions(resource.echelonSettings);
    }

    /** An unreadable date of effect, then an unreadable value, refuse the whole save. */
    @Test
    void saveRefusesABadDateOrValue() {
        AdminRegisterConfigResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPark(panache, new CheckoutIsland(), null);
            assertTrue(resource.save(form("level", "ISLAND", "code", "AV", "effectiveDate", "demain"))
                    .getLocation().toString().contains("noticeOk=false"));
            assertTrue(resource.save(form("level", "ISLAND", "code", "AV", "v.queue.single-enabled", "maybe"))
                    .getLocation().toString().contains("noticeOk=false"));
        }
        verifyNoInteractions(resource.echelonSettings);
    }

    /**
     * A save dated for a later day programs the filled values and the removal
     * of the emptied ones at that date, ignores the keys not posted, and leaves
     * the point's menu column alone until the date (BO-03-12-03).
     */
    @Test
    void savePosesClearsAndMirrorsTheMenu() {
        AdminRegisterConfigResource resource = newResource();
        CheckoutPoint point = new CheckoutPoint();
        point.terminalId = "C04";
        CashMenu menu = new CashMenu();
        menu.code = "SLS";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPark(panache, null, point);
            panache.when(() -> CashMenu.list("active = true order by code")).thenReturn(List.of(menu));
            assertTrue(resource.save(form("level", "CHECKOUT_POINT", "code", "C04", "effectiveDate", "2026-12-01",
                    "v.menu.code", "SLS", "v.queue.single-url", "", "v.hardware.printer.speed", "38400"))
                    .getLocation().toString().contains("noticeOk=true"));
        }
        LocalDate day = LocalDate.of(2026, 12, 1);
        verify(resource.echelonSettings).set(EchelonLevel.CHECKOUT_POINT, "C04", "menu.code", "SLS", day);
        verify(resource.echelonSettings).set(EchelonLevel.CHECKOUT_POINT, "C04", "hardware.printer.speed", "38400", day);
        verify(resource.echelonSettings).clear(EchelonLevel.CHECKOUT_POINT, "C04", "queue.single-url", day);
        verify(resource.echelonSettings, never()).clear(EchelonLevel.CHECKOUT_POINT, "C04", "menu.code", day);
        assertNull(point.menuTemplateCode);
    }

    /**
     * An immediate save — without a date, or dated today — mirrors the menu of
     * a point onto its column at once (the two "now" arms of the mirror).
     */
    @Test
    void anImmediateSaveMirrorsTheMenu() {
        AdminRegisterConfigResource resource = newResource();
        CheckoutPoint point = new CheckoutPoint();
        point.terminalId = "C04";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPark(panache, null, point);
            resource.pose(EchelonLevel.CHECKOUT_POINT, "C04", "menu.code", "SLS", null);
            assertEquals("SLS", point.menuTemplateCode);
            resource.pose(EchelonLevel.CHECKOUT_POINT, "C04", "menu.code", "LIGNE", LocalDate.now());
            assertEquals("LIGNE", point.menuTemplateCode);
        }
        verify(resource.echelonSettings).set(EchelonLevel.CHECKOUT_POINT, "C04", "menu.code", "SLS", null);
        verify(resource.echelonSettings).set(EchelonLevel.CHECKOUT_POINT, "C04", "menu.code", "LIGNE", LocalDate.now());
    }

    /** The menu posed on a point that has since disappeared touches no column. */
    @Test
    void poseOnAMissingPointOnlyPosesTheValue() {
        AdminRegisterConfigResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPark(panache, null, null);
            resource.pose(EchelonLevel.CHECKOUT_POINT, "C04", "menu.code", null, null);
            resource.pose(EchelonLevel.ISLAND, "AV", "menu.code", "SLS", null);
            resource.pose(EchelonLevel.CHECKOUT_POINT, "C04", "catalina.enabled", "true", null);
        }
        verify(resource.echelonSettings).clear(EchelonLevel.CHECKOUT_POINT, "C04", "menu.code", null);
        verify(resource.echelonSettings).set(EchelonLevel.ISLAND, "AV", "menu.code", "SLS", null);
        verify(resource.echelonSettings).set(EchelonLevel.CHECKOUT_POINT, "C04", "catalina.enabled", "true", null);
    }

    /** The grouped form refuses an unknown key, a bad value and a bad date. */
    @Test
    void groupRefusesKeyValueAndDate() {
        AdminRegisterConfigResource resource = newResource();
        assertTrue(resource.group(form()).getLocation().toString().contains("noticeOk=false"));
        assertTrue(resource.group(form("key", "fidelity.password")).getLocation().toString().contains("noticeOk=false"));
        assertTrue(resource.group(form("key", "hardware.printer.speed", "value", "fast"))
                .getLocation().toString().contains("noticeOk=false"));
        assertTrue(resource.group(form("key", "catalina.enabled", "value", "true", "effectiveDate", "xx"))
                .getLocation().toString().contains("noticeOk=false"));
        verifyNoInteractions(resource.echelonSettings);
    }

    /** By island: the value is posed once at the island level; an unknown or absent island is refused. */
    @Test
    void groupByIsland() {
        AdminRegisterConfigResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPark(panache, new CheckoutIsland(), null);
            PanacheQuery<CheckoutIsland> ghost = query(null);
            panache.when(() -> CheckoutIsland.find("code", "GHOST")).thenReturn(ghost);
            assertTrue(resource.group(form("key", "terminal.island-label", "value", "ILOT1", "target", "ISLAND",
                    "island", "AV")).getLocation().toString().contains("noticeOk=true"));
            assertTrue(resource.group(form("key", "terminal.island-label", "value", "ILOT1", "target", "ISLAND",
                    "island", "GHOST")).getLocation().toString().contains("noticeOk=false"));
            assertTrue(resource.group(form("key", "terminal.island-label", "value", "ILOT1", "target", "ISLAND"))
                    .getLocation().toString().contains("noticeOk=false"));
            assertTrue(resource.group(form("key", "terminal.island-label", "value", "ILOT2", "target", "ISLAND",
                    "island", "AV", "effectiveDate", "2026-12-01")).getLocation().toString().contains("noticeOk=true"));
        }
        verify(resource.echelonSettings).set(EchelonLevel.ISLAND, "AV", "terminal.island-label", "ILOT1", null);
        verify(resource.echelonSettings).set(EchelonLevel.ISLAND, "AV", "terminal.island-label", "ILOT2",
                java.time.LocalDate.of(2026, 12, 1));
    }

    /** An absent or unknown target is refused. */
    @Test
    void groupRefusesAnUnknownTarget() {
        AdminRegisterConfigResource resource = newResource();
        assertTrue(resource.group(form("key", "catalina.enabled", "value", "true")).getLocation().toString()
                .contains("noticeOk=false"));
        assertTrue(resource.group(form("key", "catalina.enabled", "value", "true", "target", "MOON"))
                .getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * By type, list or all: each chosen point gets the value at its own level;
     * a choice that selects nobody reports a failure.
     */
    @Test
    void groupByTypeListAndAll() {
        AdminRegisterConfigResource resource = newResource();
        CheckoutPoint sls = new CheckoutPoint();
        sls.terminalId = "C01";
        sls.tpvType = TpvType.SELF_CHECKOUT_NCR;
        sls.islandCode = "LS";
        CheckoutPoint till = new CheckoutPoint();
        till.terminalId = "C02";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutPoint.list("order by terminalId")).thenReturn(List.of(sls, till));
            assertTrue(resource.group(form("key", "catalina.enabled", "value", "true", "target", "TYPE",
                    "tpvType", "SELF_CHECKOUT_NCR")).getLocation().toString().contains("noticeOk=true"));
            assertTrue(resource.group(form("key", "catalina.enabled", "value", "true", "target", "LIST",
                    "terminals", "C02, C09")).getLocation().toString().contains("noticeOk=true"));
            assertTrue(resource.group(form("key", "catalina.enabled", "target", "ALL"))
                    .getLocation().toString().contains("noticeOk=true"));
            assertTrue(resource.group(form("key", "catalina.enabled", "value", "true", "target", "LIST"))
                    .getLocation().toString().contains("noticeOk=false"));
        }
        verify(resource.echelonSettings).set(EchelonLevel.CHECKOUT_POINT, "C01", "catalina.enabled", "true", null);
        verify(resource.echelonSettings).set(EchelonLevel.CHECKOUT_POINT, "C02", "catalina.enabled", "true", null);
        verify(resource.echelonSettings).clear(EchelonLevel.CHECKOUT_POINT, "C01", "catalina.enabled", null);
        verify(resource.echelonSettings).clear(EchelonLevel.CHECKOUT_POINT, "C02", "catalina.enabled", null);
    }

    /** A type target is narrowed by an island when one is named, and needs a readable type. */
    @Test
    void targetsByTypeHonourTheIslandAndTheType() {
        AdminRegisterConfigResource resource = newResource();
        CheckoutPoint inIsland = new CheckoutPoint();
        inIsland.terminalId = "C01";
        inIsland.tpvType = TpvType.SELF_CHECKOUT_NCR;
        inIsland.islandCode = "LS";
        CheckoutPoint elsewhere = new CheckoutPoint();
        elsewhere.terminalId = "C02";
        elsewhere.tpvType = TpvType.SELF_CHECKOUT_NCR;
        elsewhere.islandCode = "AV";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CheckoutPoint.list("order by terminalId")).thenReturn(List.of(inIsland, elsewhere));
            assertEquals(List.of(inIsland), resource.targets("TYPE", "LS", "SELF_CHECKOUT_NCR", null));
            assertEquals(2, resource.targets("TYPE", null, "SELF_CHECKOUT_NCR", null).size());
            assertTrue(resource.targets("TYPE", null, "SPACESHIP", null).isEmpty());
            assertTrue(resource.targets("TYPE", null, "CASH_REGISTER", null).isEmpty());
        }
    }

    /** The training data are installed and the count reported. */
    @Test
    void trainingInstallsTheBaseData() {
        AdminRegisterConfigResource resource = newResource();
        when(resource.trainingDataService.install()).thenReturn(5);
        assertTrue(resource.training().getLocation().toString().contains("noticeOk=true"));
        verify(resource.trainingDataService).install();
    }

    /** The typed list splits on semicolons, commas and spaces; null reads as empty. */
    @Test
    void splitReadsTheTypedList() {
        assertEquals(List.of("C01", "C02", "C03"), AdminRegisterConfigResource.split(" C01; C02,C03 "));
        assertTrue(AdminRegisterConfigResource.split(null).isEmpty());
    }

    /** Each value is checked against its parameter: inherit, integers, lists, free text. */
    @Test
    void refusalChecksEachKind() {
        AdminRegisterConfigResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertNull(resource.refusal("hardware.printer.speed", null));
            assertNull(resource.refusal("hardware.printer.speed", "9600"));
            assertNotNull(resource.refusal("hardware.printer.speed", "x"));
            assertNull(resource.refusal("queue.single-url", "http://queue"));
            assertNull(resource.refusal("printer2.format", "TICKET"));
            assertNotNull(resource.refusal("printer2.format", "A3"));
            assertNull(resource.refusal("catalina.enabled", "false"));
        }
    }

    /** The choice lists read the templates, the logos, the menus and the closed values. */
    @Test
    void choicesReadTheirLibraries() {
        AdminRegisterConfigResource resource = newResource();
        DocumentTemplate head = new DocumentTemplate();
        head.code = "H1";
        head.label = "Tête";
        DocumentTemplate foot = new DocumentTemplate();
        foot.code = "F1";
        foot.label = "Pied";
        StoreImage logo = new StoreImage();
        logo.code = "L1";
        logo.label = "Logo";
        CashMenu menu = new CashMenu();
        menu.code = "SLS";
        menu.label = "Libre-service";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list("active = true and documentType = ?1 order by priority, code",
                    DocumentTemplate.DocumentType.TICKET_HEADER)).thenReturn(List.of(head));
            panache.when(() -> DocumentTemplate.list("active = true and documentType = ?1 order by priority, code",
                    DocumentTemplate.DocumentType.TICKET_FOOTER)).thenReturn(List.of(foot));
            panache.when(() -> StoreImage.list("usage = ?1 and active = true order by code",
                    StoreImage.Usage.RECEIPT_LOGO)).thenReturn(List.of(logo));
            panache.when(() -> CashMenu.list("active = true order by code")).thenReturn(List.of(menu));
            assertEquals("H1 — Tête", resource.choices("ticket.header-template", PosSettingsService.Type.TEXT)
                    .get(1).label());
            assertEquals("F1", resource.choices("ticket.footer-template", PosSettingsService.Type.TEXT)
                    .get(1).value());
            assertEquals("L1 — Logo", resource.choices("print.logo-code", PosSettingsService.Type.TEXT)
                    .get(1).label());
            assertEquals("SLS", resource.choices("menu.code", PosSettingsService.Type.TEXT).get(1).value());
            List<AdminRegisterConfigResource.Choice> vat =
                    resource.choices("ticket.vat-table", PosSettingsService.Type.TEXT);
            assertEquals("", vat.get(0).value());
            assertEquals(5, vat.size());
            assertEquals(3, resource.choices("printer2.connection", PosSettingsService.Type.TEXT).size());
            assertTrue(resource.choices("queue.single-url", PosSettingsService.Type.TEXT).isEmpty());
            assertEquals(3, resource.choices("catalina.enabled", PosSettingsService.Type.BOOL).size());
        }
    }

    /** A field is drawn as a list, a number or a text according to its parameter. */
    @Test
    void fieldKinds() {
        AdminRegisterConfigResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertEquals("BOOL", resource.field("catalina.enabled", null, null).kind());
            assertTrue(resource.field("catalina.enabled", null, null).isList());
            assertEquals("CHOICE", resource.field("printer2.format", null, null).kind());
            assertTrue(resource.field("printer2.format", null, null).isList());
            assertEquals("INT", resource.field("hardware.printer.speed", null, null).kind());
            assertFalse(resource.field("hardware.printer.speed", null, null).isList());
            assertEquals("TEXT", resource.field("queue.single-url", null, null).kind());
            EchelonSetting empty = new EchelonSetting();
            PanacheQuery<EchelonSetting> emptyQuery = query(empty);
            panache.when(() -> EchelonSetting.find(VALUE_QUERY, EchelonLevel.ISLAND, "AV", "queue.single-url"))
                    .thenReturn(emptyQuery);
            assertEquals("", resource.field("queue.single-url", EchelonLevel.ISLAND, "AV").value());
            assertEquals("", resource.field("queue.single-url", EchelonLevel.ISLAND, "AV").pending());
            assertEquals("", resource.field("queue.single-url", EchelonLevel.ISLAND, "AV").pendingDate());
        }
    }

    /**
     * A field shows its current value and, when a change is programmed, the
     * programmed value and its day; a programmed removal shows an empty
     * programmed value (both arms of the programmed and of the null guards).
     */
    @Test
    void aFieldShowsTheCurrentAndTheProgrammedValue() {
        AdminRegisterConfigResource resource = newResource();
        EchelonSetting programmed = new EchelonSetting();
        programmed.settingValue = "http://old";
        programmed.pendingValue = "http://new";
        programmed.effectiveDate = LocalDate.of(2026, 12, 1);
        EchelonSetting removal = new EchelonSetting();
        removal.settingValue = "http://old";
        removal.effectiveDate = LocalDate.of(2026, 12, 2);
        PanacheQuery<EchelonSetting> programmedQuery = query(programmed);
        PanacheQuery<EchelonSetting> removalQuery = query(removal);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EchelonSetting.find(VALUE_QUERY, EchelonLevel.ISLAND, "AV", "queue.single-url"))
                    .thenReturn(programmedQuery);
            panache.when(() -> EchelonSetting.find(VALUE_QUERY, EchelonLevel.ISLAND, "AV", "terminal.island-label"))
                    .thenReturn(removalQuery);
            AdminRegisterConfigResource.Field field = resource.field("queue.single-url", EchelonLevel.ISLAND, "AV");
            assertEquals("http://old", field.value());
            assertEquals("http://new", field.pending());
            assertEquals("01/12/2026", field.pendingDate());
            AdminRegisterConfigResource.Field cleared = resource.field("terminal.island-label", EchelonLevel.ISLAND, "AV");
            assertEquals("http://old", cleared.value());
            assertEquals("", cleared.pending());
            assertEquals("02/12/2026", cleared.pendingDate());
        }
    }

    /** Only the two levels of the shop are read; the group keys carry their labels. */
    @Test
    void levelsAndGroupKeys() {
        AdminRegisterConfigResource resource = newResource();
        assertEquals(EchelonLevel.CHECKOUT_POINT, AdminRegisterConfigResource.level("CHECKOUT_POINT"));
        assertEquals(EchelonLevel.ISLAND, AdminRegisterConfigResource.level("ISLAND"));
        assertNull(AdminRegisterConfigResource.level(null));
        assertEquals("Menu caisse", resource.groupKeys().get("menu.code"));
        assertFalse(resource.exists(EchelonLevel.ISLAND, " "));
    }
}
