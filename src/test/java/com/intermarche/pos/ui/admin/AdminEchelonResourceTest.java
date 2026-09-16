package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.store.Country;
import com.intermarche.pos.domain.setting.EchelonLevel;
import com.intermarche.pos.domain.store.Enseigne;
import com.intermarche.pos.domain.store.Pdv;
import com.intermarche.pos.service.EchelonSettingService;
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

import java.time.LocalDate;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminEchelonResource}.
 * <p>
 * The resource is a Qute-backed back-office page over the Panache static
 * finders of {@link Country}, {@link Enseigne} and {@link Pdv} (resolved to
 * {@link PanacheEntityBase} under plain {@code mvn test}, intercepted with
 * {@link org.mockito.Mockito#mockStatic} and differentiated by query string)
 * plus a mocked {@link EchelonSettingService}. Inserted rows are neutralised
 * with {@link org.mockito.Mockito#mockConstruction}. No database, no Quarkus
 * boot.
 * <p>
 * Branch enumeration (every arm exercised — 100%): {@code echelonsPage} covers
 * each leg of the four-part personalisation guard (viewEnseigne null / blank,
 * viewKey null / blank, all present); {@code saveCountry} and
 * {@code saveEnseigne} cover the empty-code arm, the insert arm and the update
 * arm plus both arms of {@code blankToNull}; {@code savePdv} covers the
 * invalid-number arm, the rename-missing arm, the rename-collision arm, the
 * rename-ok arm, the plain-insert arm, the plain-update arm and both arms of
 * the active flag; {@code saveSetting} and {@code clearSetting} cover each leg
 * of the level/code/key guard and, for clear, the removed / not-removed arms;
 * {@code parseLevel} covers the match and the no-match arms; {@code trimmed}
 * covers the present and absent arms.
 */
class AdminEchelonResourceTest {

    /**
     * Builds a resource over a mocked template and echelon engine.
     *
     * @return the wired resource
     */
    private AdminEchelonResource newResource() {
        AdminEchelonResource resource = new AdminEchelonResource();
        resource.adminEchelons = mock(Template.class);
        resource.echelonSettings = mock(EchelonSettingService.class);
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the page template to a single
     * self-returning instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked template instance the chain returns
     */
    private TemplateInstance wireTemplate(AdminEchelonResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminEchelons.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Stubs the three ordered list finders the page reads so no real query
     * runs.
     *
     * @param panache the active static mock
     */
    private void stubLists(MockedStatic<PanacheEntityBase> panache) {
        panache.when(() -> PanacheEntityBase.list("order by code")).thenReturn(List.of());
        panache.when(() -> PanacheEntityBase.list("order by pdvNumber")).thenReturn(List.of());
    }

    /**
     * Stubs {@code Pdv.findByNumber(number)} to resolve to the given row.
     *
     * @param panache the active static mock
     * @param number the number to match
     * @param row the row to resolve, or null
     */
    @SuppressWarnings("unchecked")
    private void stubPdv(MockedStatic<PanacheEntityBase> panache, String number, Pdv row) {
        PanacheQuery<Pdv> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        panache.when(() -> Pdv.find("pdvNumber", number)).thenReturn(query);
    }

    /**
     * Builds an empty posted form.
     *
     * @return a mutable empty form
     */
    private MultivaluedMap<String, String> form() {
        return new MultivaluedHashMap<>();
    }

    // ---------------------------------------------------------------- GET

    /**
     * The page renders the personalisation view when both the enseigne and the
     * key are present (all-present arm), delegating to the engine.
     */
    @Test
    void pageShowsPersonalisationWhenBothPresent() {
        AdminEchelonResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        List<Pdv> personalised = List.of(new Pdv());
        when(resource.echelonSettings.personalizedPdvs("IF", "k")).thenReturn(personalised);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubLists(panache);
            assertSame(instance, resource.echelonsPage(null, true, "IF", "k"));
            verify(resource.echelonSettings).personalizedPdvs("IF", "k");
            verify(instance).data("personalised", personalised);
        }
    }

    /**
     * The view is skipped when the enseigne is null (first leg false).
     */
    @Test
    void pageSkipsPersonalisationWhenEnseigneNull() {
        assertNoView(null, "k");
    }

    /**
     * The view is skipped when the enseigne is blank (second leg false).
     */
    @Test
    void pageSkipsPersonalisationWhenEnseigneBlank() {
        assertNoView("  ", "k");
    }

    /**
     * The view is skipped when the key is null (third leg false).
     */
    @Test
    void pageSkipsPersonalisationWhenKeyNull() {
        assertNoView("IF", null);
    }

    /**
     * The view is skipped when the key is blank (fourth leg false).
     */
    @Test
    void pageSkipsPersonalisationWhenKeyBlank() {
        assertNoView("IF", "  ");
    }

    /**
     * Renders the page with the given view parameters and asserts the engine
     * is never consulted and an empty personalised list is passed.
     *
     * @param viewEnseigne the enseigne view parameter
     * @param viewKey the key view parameter
     */
    private void assertNoView(String viewEnseigne, String viewKey) {
        AdminEchelonResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubLists(panache);
            resource.echelonsPage("n", false, viewEnseigne, viewKey);
            verify(resource.echelonSettings, never()).personalizedPdvs(anyString(), anyString());
            verify(instance).data("personalised", List.of());
        }
    }

    // ------------------------------------------------------------ COUNTRY

    /**
     * {@code saveCountry} refuses a blank code (empty-code arm).
     */
    @Test
    void saveCountryRejectsBlankCode() {
        AdminEchelonResource resource = newResource();
        Response response = resource.saveCountry(form());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code saveCountry} inserts a new country when none carries the code
     * (insert arm) and maps a blank language to null ({@code blankToNull}
     * empty arm).
     */
    @Test
    void saveCountryInsertsWhenAbsent() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", " FR ");
        form.putSingle("name", " France ");
        form.putSingle("defaultLanguage", "  ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Country> construction = mockConstruction(Country.class)) {
            PanacheQuery<Country> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> Country.find("code", "FR")).thenReturn(query);
            Response response = resource.saveCountry(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            Country created = construction.constructed().get(0);
            assertEquals("FR", created.code);
            assertEquals("France", created.name);
            assertNull(created.defaultLanguage);
            verify(created, times(1)).persist();
        }
    }

    /**
     * {@code saveCountry} updates an existing country (update arm) and keeps a
     * non-blank language ({@code blankToNull} non-empty arm), constructing
     * nothing.
     */
    @Test
    void saveCountryUpdatesWhenPresent() {
        AdminEchelonResource resource = newResource();
        Country existing = new Country();
        existing.code = "FR";
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "FR");
        form.putSingle("name", "France");
        form.putSingle("defaultLanguage", "fr");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Country> construction = mockConstruction(Country.class)) {
            PanacheQuery<Country> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            panache.when(() -> Country.find("code", "FR")).thenReturn(query);
            resource.saveCountry(form);
            assertTrue(construction.constructed().isEmpty());
            assertEquals("France", existing.name);
            assertEquals("fr", existing.defaultLanguage);
        }
    }

    // ----------------------------------------------------------- ENSEIGNE

    /**
     * {@code saveEnseigne} refuses a blank code (empty-code arm).
     */
    @Test
    void saveEnseigneRejectsBlankCode() {
        AdminEchelonResource resource = newResource();
        Response response = resource.saveEnseigne(form());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code saveEnseigne} inserts a new enseigne when none carries the code
     * (insert arm), attaching it to a country.
     */
    @Test
    void saveEnseigneInsertsWhenAbsent() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "IF");
        form.putSingle("name", "Intermarché France");
        form.putSingle("countryCode", "FR");
        form.putSingle("defaultLanguage", "fr");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Enseigne> construction = mockConstruction(Enseigne.class)) {
            PanacheQuery<Enseigne> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> Enseigne.find("code", "IF")).thenReturn(query);
            resource.saveEnseigne(form);
            Enseigne created = construction.constructed().get(0);
            assertEquals("IF", created.code);
            assertEquals("FR", created.countryCode);
            assertEquals("fr", created.defaultLanguage);
            verify(created, times(1)).persist();
        }
    }

    /**
     * {@code saveEnseigne} updates an existing enseigne (update arm) and maps a
     * blank country to null ({@code blankToNull} empty arm), constructing
     * nothing.
     */
    @Test
    void saveEnseigneUpdatesWhenPresent() {
        AdminEchelonResource resource = newResource();
        Enseigne existing = new Enseigne();
        existing.code = "IF";
        MultivaluedMap<String, String> form = form();
        form.putSingle("code", "IF");
        form.putSingle("name", "Renommée");
        form.putSingle("countryCode", "  ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Enseigne> construction = mockConstruction(Enseigne.class)) {
            PanacheQuery<Enseigne> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            panache.when(() -> Enseigne.find("code", "IF")).thenReturn(query);
            resource.saveEnseigne(form);
            assertTrue(construction.constructed().isEmpty());
            assertEquals("Renommée", existing.name);
            assertNull(existing.countryCode);
        }
    }

    // --------------------------------------------------------------- PDV

    /**
     * {@code savePdv} refuses a number that is not exactly five digits
     * (invalid-number arm).
     */
    @Test
    void savePdvRejectsInvalidNumber() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("pdvNumber", "12A4");
        Response response = resource.savePdv(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code savePdv} inserts a new PDV when the number is free (plain-insert
     * arm) and reads the active flag as present (active-true arm).
     */
    @Test
    void savePdvInsertsWhenAbsent() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("pdvNumber", "12345");
        form.putSingle("name", "Lyon");
        form.putSingle("enseigneCode", "IF");
        form.putSingle("adherentCode", "AD1");
        form.putSingle("active", "1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Pdv> construction = mockConstruction(Pdv.class)) {
            stubPdv(panache, "12345", null);
            resource.savePdv(form);
            Pdv created = construction.constructed().get(0);
            assertEquals("12345", created.pdvNumber);
            assertEquals("IF", created.enseigneCode);
            assertEquals("AD1", created.adherentCode);
            assertTrue(created.active);
            verify(created, times(1)).persist();
        }
    }

    /**
     * {@code savePdv} updates an existing PDV (plain-update arm), maps a blank
     * adhérent to null and reads the missing active flag as false
     * (active-false arm), constructing nothing.
     */
    @Test
    void savePdvUpdatesWhenPresent() {
        AdminEchelonResource resource = newResource();
        Pdv existing = new Pdv();
        existing.pdvNumber = "12345";
        existing.active = true;
        MultivaluedMap<String, String> form = form();
        form.putSingle("pdvNumber", "12345");
        form.putSingle("name", "Lyon 2");
        form.putSingle("adherentCode", "  ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Pdv> construction = mockConstruction(Pdv.class)) {
            stubPdv(panache, "12345", existing);
            resource.savePdv(form);
            assertTrue(construction.constructed().isEmpty());
            assertEquals("Lyon 2", existing.name);
            assertNull(existing.adherentCode);
            assertFalse(existing.active);
        }
    }

    /**
     * {@code savePdv} refuses a rename whose original number is unknown
     * (rename-missing arm).
     */
    @Test
    void savePdvRejectsRenameOfMissing() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("pdvNumber", "54321");
        form.putSingle("originalNumber", "12345");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", null);
            Response response = resource.savePdv(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code savePdv} refuses a rename whose target number is already taken
     * (rename-collision arm).
     */
    @Test
    void savePdvRejectsRenameCollision() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("pdvNumber", "54321");
        form.putSingle("originalNumber", "12345");
        Pdv source = new Pdv();
        source.pdvNumber = "12345";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", source);
            stubPdv(panache, "54321", new Pdv());
            Response response = resource.savePdv(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code savePdv} renames a PDV to a free number (rename-ok arm),
     * re-numbering the existing row.
     */
    @Test
    void savePdvRenamesToFreeNumber() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("pdvNumber", "54321");
        form.putSingle("originalNumber", "12345");
        form.putSingle("name", "Renumérotée");
        Pdv source = new Pdv();
        source.pdvNumber = "12345";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", source);
            stubPdv(panache, "54321", null);
            Response response = resource.savePdv(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertEquals("54321", source.pdvNumber);
            assertEquals("Renumérotée", source.name);
            // BO-02-05-01: the rename carries the echelon settings to the new number.
            verify(resource.echelonSettings).migratePdv("12345", "54321");
        }
    }

    /**
     * {@code savePdv} treats an original number equal to the new number as a
     * plain update, not a rename (the {@code !original.equals(number)} false
     * arm): the row is looked up once by its number and updated in place, and no
     * echelon-settings migration is triggered.
     */
    @Test
    void savePdvTreatsEqualOriginalAsUpdate() {
        AdminEchelonResource resource = newResource();
        Pdv existing = new Pdv();
        existing.pdvNumber = "12345";
        MultivaluedMap<String, String> form = form();
        form.putSingle("pdvNumber", "12345");
        form.putSingle("originalNumber", "12345");
        form.putSingle("name", "Sans renommage");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Pdv> construction = mockConstruction(Pdv.class)) {
            stubPdv(panache, "12345", existing);
            Response response = resource.savePdv(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertTrue(construction.constructed().isEmpty());
            assertEquals("Sans renommage", existing.name);
            verify(resource.echelonSettings, never()).migratePdv(anyString(), anyString());
        }
    }

    // ----------------------------------------------------------- SETTINGS

    /**
     * {@code saveSetting} refuses an unrecognised level (level-null leg of the
     * guard, and the no-match arm of {@code parseLevel}).
     */
    @Test
    void saveSettingRejectsUnknownLevel() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("level", "REGION");
        form.putSingle("echelonCode", "IF");
        form.putSingle("key", "k");
        Response response = resource.saveSetting(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        verify(resource.echelonSettings, never())
                .set(any(), anyString(), anyString(), anyString(), any());
    }

    /**
     * {@code saveSetting} refuses a blank echelon code (echelon-empty leg).
     */
    @Test
    void saveSettingRejectsBlankEchelon() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("level", "ENSEIGNE");
        form.putSingle("key", "k");
        Response response = resource.saveSetting(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code saveSetting} refuses a blank key (key-empty leg).
     */
    @Test
    void saveSettingRejectsBlankKey() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("level", "ENSEIGNE");
        form.putSingle("echelonCode", "IF");
        Response response = resource.saveSetting(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code saveSetting} poses the value with no effect date when none is
     * supplied (all-ok arm, the match arm of {@code parseLevel}, and the
     * empty-date arm passing a null effect date for immediate effect).
     */
    @Test
    void saveSettingPosesValue() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("level", "ENSEIGNE");
        form.putSingle("echelonCode", "IF");
        form.putSingle("key", "display.show-ean");
        form.putSingle("value", "true");
        Response response = resource.saveSetting(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        verify(resource.echelonSettings)
                .set(EchelonLevel.ENSEIGNE, "IF", "display.show-ean", "true", null);
    }

    /**
     * {@code saveSetting} parses a supplied effect date and poses the value with
     * it (non-empty-date arm, parse-ok arm) so a scheduled change is stored dated.
     */
    @Test
    void saveSettingParsesEffectiveDate() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("level", "ENSEIGNE");
        form.putSingle("echelonCode", "IF");
        form.putSingle("key", "display.show-ean");
        form.putSingle("value", "true");
        form.putSingle("effectiveDate", "2026-03-01");
        Response response = resource.saveSetting(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        verify(resource.echelonSettings)
                .set(EchelonLevel.ENSEIGNE, "IF", "display.show-ean", "true", LocalDate.of(2026, 3, 1));
    }

    /**
     * {@code saveSetting} refuses a malformed effect date (parse-fail catch arm)
     * without posing anything.
     */
    @Test
    void saveSettingRejectsInvalidEffectiveDate() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("level", "ENSEIGNE");
        form.putSingle("echelonCode", "IF");
        form.putSingle("key", "display.show-ean");
        form.putSingle("value", "true");
        form.putSingle("effectiveDate", "not-a-date");
        Response response = resource.saveSetting(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        verify(resource.echelonSettings, never())
                .set(any(), anyString(), anyString(), anyString(), any());
    }

    /**
     * {@code clearSetting} refuses when the guard fails (echelon-empty leg),
     * without touching the engine.
     */
    @Test
    void clearSettingRejectsInvalidInput() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("level", "PDV");
        form.putSingle("key", "k");
        Response response = resource.clearSetting(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        verify(resource.echelonSettings, never()).clear(any(), anyString(), anyString());
    }

    /**
     * {@code clearSetting} refuses an unrecognised level (level-null leg of its
     * guard).
     */
    @Test
    void clearSettingRejectsUnknownLevel() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("level", "REGION");
        form.putSingle("echelonCode", "12345");
        form.putSingle("key", "k");
        Response response = resource.clearSetting(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        verify(resource.echelonSettings, never()).clear(any(), anyString(), anyString());
    }

    /**
     * {@code clearSetting} refuses a blank key (key-empty leg of its guard).
     */
    @Test
    void clearSettingRejectsBlankKey() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("level", "PDV");
        form.putSingle("echelonCode", "12345");
        Response response = resource.clearSetting(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code clearSetting} reports success when a row was removed (removed arm).
     */
    @Test
    void clearSettingReportsRemoval() {
        AdminEchelonResource resource = newResource();
        when(resource.echelonSettings.clear(EchelonLevel.PDV, "12345", "k")).thenReturn(true);
        MultivaluedMap<String, String> form = form();
        form.putSingle("level", "PDV");
        form.putSingle("echelonCode", "12345");
        form.putSingle("key", "k");
        Response response = resource.clearSetting(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
    }

    /**
     * {@code clearSetting} reports nothing to remove when no row existed
     * (not-removed arm).
     */
    @Test
    void clearSettingReportsNothingToRemove() {
        AdminEchelonResource resource = newResource();
        when(resource.echelonSettings.clear(eq(EchelonLevel.PDV), eq("12345"), eq("k"))).thenReturn(false);
        MultivaluedMap<String, String> form = form();
        form.putSingle("level", "PDV");
        form.putSingle("echelonCode", "12345");
        form.putSingle("key", "k");
        Response response = resource.clearSetting(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }
}
