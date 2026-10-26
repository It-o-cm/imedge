package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.setting.EchelonLevel;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.service.EchelonSettingService;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
 * of the level/code/key guard, the closed-value guard (a key with no set, a
 * value of the set, a value outside it) and, for clear, the removed /
 * not-removed arms;
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
        resource.pdvRegistry = mock(com.intermarche.edge.service.PdvRegistryService.class);
        resource.refExportService = mock(com.intermarche.edge.service.sync.store.RefExportService.class);
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
        List<com.intermarche.edge.domain.setting.EchelonSetting> programmed =
                List.of(new com.intermarche.edge.domain.setting.EchelonSetting());
        when(resource.echelonSettings.personalizedPdvs("IF", "k")).thenReturn(personalised);
        when(resource.echelonSettings.programmed()).thenReturn(programmed);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubLists(panache);
            assertSame(instance, resource.echelonsPage(null, true, "IF", "k", null));
            verify(resource.echelonSettings).personalizedPdvs("IF", "k");
            verify(instance).data("personalised", personalised);
            // BO-03-12-03: the values programmed for a later day, current and pending.
            verify(instance).data("programmed", programmed);
            // BO-10-03-08: the page's script masks the value of a secret key.
            verify(instance).data("secretKeys", "[\"fidelity.password\",\"fiscal.at-password\"]");
            org.mockito.ArgumentCaptor<Object> closed = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(instance).data(org.mockito.ArgumentMatchers.eq("closedValues"), closed.capture());
            com.fasterxml.jackson.databind.JsonNode parsed = readJson((String) closed.getValue());
            assertEquals(com.intermarche.edge.service.PosSettingsService.CLOSED_VALUES.size(), parsed.size());
            for (java.util.Map.Entry<String, java.util.List<String>> entry
                    : com.intermarche.edge.service.PosSettingsService.CLOSED_VALUES.entrySet()) {
                assertEquals(entry.getValue().size(), parsed.get(entry.getKey()).size());
            }
        }
    }

    /**
     * Reads a JSON text with a real parser, so a malformed document fails.
     *
     * @param text the JSON text
     * @return the parsed document
     */
    private static com.fasterxml.jackson.databind.JsonNode readJson(String text) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new AssertionError(malformed);
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
            resource.echelonsPage("n", false, viewEnseigne, viewKey, null);
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
        List<String> namedAtPersist = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Country> construction = mockConstruction(Country.class,
                     (entity, context) -> doAnswer(call -> namedAtPersist.add(entity.name)).when(entity).persist())) {
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
            assertEquals(List.of("France"), namedAtPersist);
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
        List<String> namedAtPersist = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Enseigne> construction = mockConstruction(Enseigne.class,
                     (entity, context) -> doAnswer(call -> namedAtPersist.add(entity.name)).when(entity).persist())) {
            PanacheQuery<Enseigne> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> Enseigne.find("code", "IF")).thenReturn(query);
            resource.saveEnseigne(form);
            Enseigne created = construction.constructed().get(0);
            assertEquals("IF", created.code);
            assertEquals("FR", created.countryCode);
            assertEquals("fr", created.defaultLanguage);
            verify(created, times(1)).persist();
            assertEquals(List.of("Intermarché France"), namedAtPersist);
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
     * {@code savePdv} hands the posted point of sale to the registry — blank
     * enseigne and adhérent read as null, the active box as checked — and
     * redirects with the registry's notice and outcome.
     */
    @Test
    void savePdvDelegatesToTheRegistry() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("pdvNumber", " 12345 ");
        form.putSingle("originalNumber", "54321");
        form.putSingle("name", "Lyon");
        form.putSingle("enseigneCode", " ");
        form.putSingle("adherentCode", "AD1");
        form.putSingle("active", "1");
        when(resource.pdvRegistry.save("12345", "54321", "Lyon", null, "AD1", true,
                com.intermarche.edge.domain.store.PdvHistory.Source.SCREEN))
                .thenReturn(new com.intermarche.edge.service.PdvRegistryService.Result(
                        true, "Point de vente enregistré.", new Pdv(), false));
        Response response = resource.savePdv(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
    }

    /**
     * {@code savePdv} reports a refusal of the registry, the active box absent.
     */
    @Test
    void savePdvReportsTheRegistryRefusal() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("pdvNumber", "12A4");
        when(resource.pdvRegistry.save("12A4", "", "", null, null, false,
                com.intermarche.edge.domain.store.PdvHistory.Source.SCREEN))
                .thenReturn(new com.intermarche.edge.service.PdvRegistryService.Result(
                        false, "Le numéro de PDV doit comporter exactement 5 chiffres.", null, false));
        Response response = resource.savePdv(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * The inheritance view of a known point of sale hands the page its
     * history, its inherited parameters, the VAT regimes, the templates and the
     * accounts it receives (BO-02-05-01/02/04).
     */
    @Test
    void pageShowsTheInheritanceOfAKnownPdv() {
        AdminEchelonResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        Pdv viewed = new Pdv();
        viewed.id = 4L;
        viewed.pdvNumber = "07039";
        List<EchelonSettingService.Inherited> inherited = List.of(
                new EchelonSettingService.Inherited("menu.code", "M1", "ENSEIGNE", "IF"));
        when(resource.echelonSettings.inheritedFor("07039")).thenReturn(inherited);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubLists(panache);
            stubPdv(panache, "07039", viewed);
            resource.echelonsPage(null, true, null, null, " 07039 ");
        }
        verify(instance).data("viewPdv", "07039");
        verify(instance).data("viewed", viewed);
        verify(instance).data("inherited", inherited);
        verify(resource.refExportService).vatRatesFor("07039");
        verify(resource.refExportService).templatesFor("07039");
        verify(resource.refExportService).bindingsFor("07039");
    }

    /**
     * The inheritance view keeps only the bindings in force on the point of
     * sale: the whole organisation, a scope left blank and its own enseigne
     * are kept, another enseigne's binding is not.
     */
    @Test
    void inForceKeepsOnlyTheBindingsCoveringThePdv() {
        com.intermarche.edge.service.sync.RefPayloads.AccountProfileDto everywhere = binding("*");
        com.intermarche.edge.service.sync.RefPayloads.AccountProfileDto unscoped = binding(null);
        com.intermarche.edge.service.sync.RefPayloads.AccountProfileDto own = binding("ENSEIGNE:IP");
        com.intermarche.edge.service.sync.RefPayloads.AccountProfileDto other = binding("ENSEIGNE:IF");
        com.intermarche.edge.domain.store.NodeEchelon node =
                new com.intermarche.edge.domain.store.NodeEchelon("10001", "IP", "PT");
        assertEquals(List.of(everywhere, unscoped, own),
                AdminEchelonResource.inForce(List.of(everywhere, unscoped, own, other), node));
    }

    /**
     * Builds a profile binding of jdupont at a scope.
     *
     * @param scope the scope, possibly null
     * @return the binding
     */
    private com.intermarche.edge.service.sync.RefPayloads.AccountProfileDto binding(String scope) {
        com.intermarche.edge.service.sync.RefPayloads.AccountProfileDto dto =
                new com.intermarche.edge.service.sync.RefPayloads.AccountProfileDto();
        dto.loginName = "jdupont";
        dto.profileName = "Caissier";
        dto.echelonScope = scope;
        return dto;
    }

    /**
     * An unknown point of sale, and a blank one, show no inheritance at all.
     */
    @Test
    void pageShowsNoInheritanceForAnUnknownOrBlankPdv() {
        AdminEchelonResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubLists(panache);
            stubPdv(panache, "99999", null);
            resource.echelonsPage(null, true, null, null, "99999");
            resource.echelonsPage(null, true, null, null, "  ");
        }
        verify(instance, times(2)).data("viewed", null);
        verify(instance).data("viewPdv", "");
        verify(resource.echelonSettings, never()).inheritedFor(anyString());
    }

    /**
     * The adhérent view groups the points of sale by adhérent code, in code
     * order, and leaves the points of sale attached to none out
     * (BO-02-05-05).
     */
    @Test
    void adherentsGroupThePointsOfSale() {
        Pdv a1 = new Pdv();
        a1.adherentCode = "B";
        Pdv a2 = new Pdv();
        a2.adherentCode = "A";
        Pdv a3 = new Pdv();
        a3.adherentCode = "B";
        Pdv loose = new Pdv();
        java.util.Map<String, List<Pdv>> grouped = AdminEchelonResource.adherents(List.of(a1, a2, a3, loose));
        assertEquals(List.of("A", "B"), new ArrayList<>(grouped.keySet()));
        assertEquals(List.of(a1, a3), grouped.get("B"));
    }

    // ----------------------------------------------------------- SETTINGS

    /**
     * {@code saveSetting} refuses a value OUTSIDE the closed set of the chosen
     * parameter, naming what was expected: posed at an echelon, a word the
     * register cannot read falls back on the default, and the shop believes it
     * has set a parameter it has not.
     */
    @Test
    void saveSettingRejectsAValueOutsideTheClosedSet() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("level", "ENSEIGNE");
        form.putSingle("echelonCode", "IF");
        form.putSingle("key", "control.cancel-line");
        form.putSingle("value", "BLOK");
        Response response = resource.saveSetting(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        verify(resource.echelonSettings, never())
                .set(any(), anyString(), anyString(), anyString(), any());
    }

    /**
     * {@code saveSetting} poses a value the closed set offers (the allowed arm
     * of the same guard).
     */
    @Test
    void saveSettingPosesAValueOfTheClosedSet() {
        AdminEchelonResource resource = newResource();
        MultivaluedMap<String, String> form = form();
        form.putSingle("level", "ENSEIGNE");
        form.putSingle("echelonCode", "IF");
        form.putSingle("key", "control.cancel-line");
        form.putSingle("value", "BLOCK");
        Response response = resource.saveSetting(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        verify(resource.echelonSettings)
                .set(EchelonLevel.ENSEIGNE, "IF", "control.cancel-line", "BLOCK", null);
    }

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
