package com.intermarche.edge.service;

import com.intermarche.edge.domain.setting.EchelonLevel;
import com.intermarche.edge.domain.setting.EchelonSetting;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EchelonSettingService}.
 * <p>
 * The service reads through the Panache static finders of {@link Pdv},
 * {@link Enseigne} and {@link EchelonSetting}, which resolve to
 * {@link PanacheEntityBase} under plain {@code mvn test} and are intercepted
 * with {@link org.mockito.Mockito#mockStatic}, differentiated by their query
 * strings. {@code new EchelonSetting()} is neutralised with
 * {@link org.mockito.Mockito#mockConstruction}. No database, no Quarkus boot.
 * <p>
 * Branch enumeration (every arm exercised — 100%): {@code resolveForPdv} covers
 * the null-number arm, the unknown-PDV arm, the enseigne-null arm, the
 * enseigne-present-but-unknown arm, the enseigne-present-country-null arm and
 * the full three-level chain (proving PDV over ENSEIGNE over COUNTRY);
 * {@code resolve} covers the present and absent key; {@code set} covers the
 * insert and the update arms; {@code clear} covers the found and the absent
 * arms; {@code personalizedPdvs} covers the empty-enseigne arm and the
 * mixed-membership filter (both the personalised and the plain arm).
 */
class EchelonSettingServiceTest {

    /** The query string of {@link Pdv#findByNumber}. */
    private static final String PDV_BY_NUMBER = "pdvNumber";
    /** The query string of {@link Enseigne#findByCode}. */
    private static final String ENSEIGNE_BY_CODE = "code";
    /** The query string of {@link EchelonSetting#findValue}. */
    private static final String SETTING_FIND = "level = ?1 and echelonCode = ?2 and settingKey = ?3";
    /** The query string of {@link EchelonSetting#listForEchelon}. */
    private static final String SETTING_FOR_ECHELON = "level = ?1 and echelonCode = ?2 order by settingKey";
    /** The query string of {@link EchelonSetting#listForKeyAtLevel}. */
    private static final String SETTING_FOR_KEY = "level = ?1 and settingKey = ?2 order by echelonCode";
    /** The query string of {@link Pdv#listByEnseigne}. */
    private static final String PDV_BY_ENSEIGNE = "enseigneCode = ?1 order by pdvNumber";

    /**
     * Stubs {@code Pdv.findByNumber(number)} to return the given row.
     *
     * @param panache the active static mock
     * @param number the number to match
     * @param row the row to resolve, or null
     */
    @SuppressWarnings("unchecked")
    private void stubPdv(MockedStatic<PanacheEntityBase> panache, String number, Pdv row) {
        PanacheQuery<Pdv> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        panache.when(() -> Pdv.find(PDV_BY_NUMBER, number)).thenReturn(query);
    }

    /**
     * Stubs {@code Enseigne.findByCode(code)} to return the given row.
     *
     * @param panache the active static mock
     * @param code the code to match
     * @param row the row to resolve, or null
     */
    @SuppressWarnings("unchecked")
    private void stubEnseigne(MockedStatic<PanacheEntityBase> panache, String code, Enseigne row) {
        PanacheQuery<Enseigne> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        panache.when(() -> Enseigne.find(ENSEIGNE_BY_CODE, code)).thenReturn(query);
    }

    /**
     * Builds an echelon-setting row.
     *
     * @param key the catalog key
     * @param value the value
     * @return the built row
     */
    private EchelonSetting row(String key, String value) {
        EchelonSetting setting = new EchelonSetting();
        setting.settingKey = key;
        setting.settingValue = value;
        return setting;
    }

    /**
     * Builds an echelon-setting row carrying an effect date.
     *
     * @param key the catalog key
     * @param value the value
     * @param effectiveDate the day the value takes effect, or null for immediate
     * @return the built row
     */
    private EchelonSetting row(String key, String value, LocalDate effectiveDate) {
        EchelonSetting setting = row(key, value);
        setting.effectiveDate = effectiveDate;
        return setting;
    }

    /**
     * Builds a PDV with a number and an enseigne code.
     *
     * @param number the PDV number
     * @param enseigneCode the enseigne code, possibly null
     * @return the built PDV
     */
    private Pdv pdv(String number, String enseigneCode) {
        Pdv pdv = new Pdv();
        pdv.pdvNumber = number;
        pdv.enseigneCode = enseigneCode;
        return pdv;
    }

    /**
     * A null PDV number resolves to no inherited values (null-number arm).
     */
    @Test
    void resolveForPdvReturnsEmptyOnNullNumber() {
        EchelonSettingService service = new EchelonSettingService();
        assertTrue(service.resolveForPdv(null).isEmpty());
    }

    /**
     * An unknown PDV resolves to no inherited values (unknown-PDV arm).
     */
    @Test
    void resolveForPdvReturnsEmptyOnUnknownPdv() {
        EchelonSettingService service = new EchelonSettingService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "99999", null);
            assertTrue(service.resolveForPdv("99999").isEmpty());
        }
    }

    /**
     * A PDV with no enseigne inherits only its own PDV-level values
     * (enseigne-null arm, country-null arm).
     */
    @Test
    void resolveForPdvUsesPdvLevelOnlyWhenNoEnseigne() {
        EchelonSettingService service = new EchelonSettingService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", null));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "12345"))
                    .thenReturn(List.of(row("display.show-ean", "true")));
            Map<String, String> resolved = service.resolveForPdv("12345");
            assertEquals(1, resolved.size());
            assertEquals("true", resolved.get("display.show-ean"));
        }
    }

    /**
     * A PDV whose enseigne code points to no enseigne skips the country level
     * (enseigne-present-but-unknown arm).
     */
    @Test
    void resolveForPdvSkipsCountryWhenEnseigneUnknown() {
        EchelonSettingService service = new EchelonSettingService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", "IF"));
            stubEnseigne(panache, "IF", null);
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.ENSEIGNE, "IF"))
                    .thenReturn(List.of(row("display.show-ean", "ens")));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "12345"))
                    .thenReturn(List.of());
            Map<String, String> resolved = service.resolveForPdv("12345");
            assertEquals("ens", resolved.get("display.show-ean"));
        }
    }

    /**
     * A PDV whose enseigne has no country resolves the enseigne and PDV levels
     * but no country (enseigne-present, country-null arm).
     */
    @Test
    void resolveForPdvSkipsCountryWhenEnseigneHasNone() {
        EchelonSettingService service = new EchelonSettingService();
        Enseigne enseigne = new Enseigne();
        enseigne.code = "IF";
        enseigne.countryCode = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", "IF"));
            stubEnseigne(panache, "IF", enseigne);
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.ENSEIGNE, "IF"))
                    .thenReturn(List.of(row("k", "ens")));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "12345"))
                    .thenReturn(List.of());
            Map<String, String> resolved = service.resolveForPdv("12345");
            assertEquals("ens", resolved.get("k"));
        }
    }

    /**
     * The full three-level chain resolves country, enseigne and PDV, and the
     * more specific echelon overrides the less specific (PDV over ENSEIGNE over
     * COUNTRY).
     */
    @Test
    void resolveForPdvChainsAllLevelsWithMoreSpecificWinning() {
        EchelonSettingService service = new EchelonSettingService();
        Enseigne enseigne = new Enseigne();
        enseigne.code = "IF";
        enseigne.countryCode = "FR";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", "IF"));
            stubEnseigne(panache, "IF", enseigne);
            stubCountry(panache, "FR", null);
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.COUNTRY, "FR"))
                    .thenReturn(List.of(row("shared", "country"), row("countryOnly", "cv")));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.ENSEIGNE, "IF"))
                    .thenReturn(List.of(row("shared", "enseigne"), row("ensOnly", "ev")));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "12345"))
                    .thenReturn(List.of(row("shared", "pdv")));
            Map<String, String> resolved = service.resolveForPdv("12345");
            assertEquals("pdv", resolved.get("shared"));
            assertEquals("cv", resolved.get("countryOnly"));
            assertEquals("ev", resolved.get("ensOnly"));
            assertEquals(3, resolved.size());
        }
    }

    /**
     * The date-aware overload gives each row the value in force that day
     * (BO-03-12-03/04): an undated row its value, a change programmed in the
     * past its pending value, a change programmed in the future the CURRENT
     * value next to it, and a row with no value in force (a pending value alone,
     * still to come) leaves the key inherited — every arm of the overlay guard.
     */
    @Test
    void resolveForPdvGatesRowsOnEffectiveDate() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate asOf = LocalDate.of(2026, 6, 15);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", null));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "12345"))
                    .thenReturn(List.of(
                            programmed("past", "old", "p", LocalDate.of(2026, 1, 1)),
                            row("undated", "n", null),
                            programmed("future", "current", "f", LocalDate.of(2026, 12, 1)),
                            programmed("onlyPending", null, "x", LocalDate.of(2026, 12, 1))));
            Map<String, String> resolved = service.resolveForPdv("12345", asOf);
            assertEquals("p", resolved.get("past"));
            assertEquals("n", resolved.get("undated"));
            assertEquals("current", resolved.get("future"));
            assertFalse(resolved.containsKey("onlyPending"));
            assertEquals(3, resolved.size());
        }
    }

    /**
     * A change programmed below an inherited value keeps the inherited value
     * until its date and replaces it from that day: the PDV programs "pdv" for
     * December over the enseigne's "enseigne".
     */
    @Test
    void aProgrammedValueAloneKeepsTheInheritedOneUntilItsDate() {
        EchelonSettingService service = new EchelonSettingService();
        Enseigne enseigne = new Enseigne();
        enseigne.code = "ITM";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", "ITM"));
            stubEnseigne(panache, "ITM", enseigne);
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.ENSEIGNE, "ITM"))
                    .thenReturn(List.of(row("k", "enseigne")));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "12345"))
                    .thenReturn(List.of(programmed("k", null, "pdv", LocalDate.of(2026, 12, 1))));
            assertEquals("enseigne", service.resolveForPdv("12345", LocalDate.of(2026, 11, 30)).get("k"));
            assertEquals("pdv", service.resolveForPdv("12345", LocalDate.of(2026, 12, 1)).get("k"));
        }
    }

    /**
     * Builds a row carrying a current value and a change programmed for a day.
     *
     * @param key the catalog key
     * @param current the current value, or null
     * @param pending the pending value, or null for a programmed removal
     * @param date the day the pending value applies
     * @return the built row
     */
    private EchelonSetting programmed(String key, String current, String pending, LocalDate date) {
        EchelonSetting setting = row(key, current, date);
        setting.pendingValue = pending;
        return setting;
    }

    /**
     * Stubs {@code EchelonSetting.findValue(level, code, key)} to return the row.
     *
     * @param panache the active static mock
     * @param level the level to match
     * @param code the echelon code to match
     * @param key the key to match
     * @param row the row to resolve, or null
     */
    @SuppressWarnings("unchecked")
    private void stubFind(MockedStatic<PanacheEntityBase> panache, EchelonLevel level, String code, String key,
                          EchelonSetting row) {
        PanacheQuery<EchelonSetting> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        panache.when(() -> EchelonSetting.find(SETTING_FIND, level, code, key)).thenReturn(query);
    }

    /**
     * Builds a row whose {@code delete()} is neutralised, so a test can tell
     * whether the service removed it.
     *
     * @param level the echelon level
     * @param code the echelon code
     * @param key the catalog key
     * @param current the current value, or null
     * @param pending the pending value, or null
     * @param date the programmed day, or null when nothing is programmed
     * @return the spied row
     */
    private EchelonSetting spied(EchelonLevel level, String code, String key, String current, String pending,
                                 LocalDate date) {
        EchelonSetting setting = spy(new EchelonSetting());
        doNothing().when(setting).delete();
        setting.level = level;
        setting.echelonCode = code;
        setting.settingKey = key;
        setting.settingValue = current;
        setting.pendingValue = pending;
        setting.effectiveDate = date;
        return setting;
    }

    /**
     * A value dated after today, on a key with no row, is inserted as the
     * PENDING value only: no current value, so the key keeps inheriting until
     * the date (insert arm, dated).
     */
    @Test
    void setDatedOnInsertProgramsThePendingValueOnly() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate effect = LocalDate.of(2026, 11, 1);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<EchelonSetting> construction = mockConstruction(EchelonSetting.class)) {
            stubFind(panache, EchelonLevel.ENSEIGNE, "IF", "k", null);
            service.set(EchelonLevel.ENSEIGNE, "IF", "k", "v", effect, LocalDate.of(2026, 10, 25));
            EchelonSetting created = construction.constructed().get(0);
            assertEquals(EchelonLevel.ENSEIGNE, created.level);
            assertEquals("IF", created.echelonCode);
            assertEquals("k", created.settingKey);
            assertNull(created.settingValue);
            assertEquals("v", created.pendingValue);
            assertEquals(effect, created.effectiveDate);
            verify(created, times(1)).persist();
        }
    }

    /**
     * A dated value programmed next to an existing current value leaves the
     * current value in place (update arm, dated, different value).
     */
    @Test
    void setDatedOnUpdateKeepsTheCurrentValue() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate effect = LocalDate.of(2026, 11, 1);
        EchelonSetting existing = row("k", "old");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<EchelonSetting> construction = mockConstruction(EchelonSetting.class)) {
            stubFind(panache, EchelonLevel.PDV, "12345", "k", existing);
            service.set(EchelonLevel.PDV, "12345", "k", "new", effect, LocalDate.of(2026, 10, 25));
            assertEquals("old", existing.settingValue);
            assertEquals("new", existing.pendingValue);
            assertEquals(effect, existing.effectiveDate);
            assertTrue(construction.constructed().isEmpty());
        }
    }

    /**
     * A dated value equal to the current one programs nothing and leaves a
     * change already programmed on the key untouched (update arm, dated, same
     * value) — the whole register form re-posted with a date.
     */
    @Test
    void setDatedEqualToTheCurrentValueProgramsNothing() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate programmedDay = LocalDate.of(2026, 12, 1);
        EchelonSetting existing = programmed("k", "same", "later", programmedDay);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, EchelonLevel.PDV, "12345", "k", existing);
            service.set(EchelonLevel.PDV, "12345", "k", "same", LocalDate.of(2026, 11, 1),
                    LocalDate.of(2026, 10, 25));
            assertEquals("same", existing.settingValue);
            assertEquals("later", existing.pendingValue);
            assertEquals(programmedDay, existing.effectiveDate);
        }
    }

    /**
     * A date already come (today) is an immediate value: it replaces the current
     * value and keeps the change programmed on the key (update arm, not dated
     * although a date was given).
     */
    @Test
    void setWithTodaysDateIsImmediateAndKeepsTheProgrammedChange() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate today = LocalDate.of(2026, 10, 25);
        LocalDate programmedDay = LocalDate.of(2026, 12, 1);
        EchelonSetting existing = programmed("k", "old", "later", programmedDay);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, EchelonLevel.PDV, "12345", "k", existing);
            service.set(EchelonLevel.PDV, "12345", "k", "now", today, today);
            assertEquals("now", existing.settingValue);
            assertEquals("later", existing.pendingValue);
            assertEquals(programmedDay, existing.effectiveDate);
        }
    }

    /**
     * The public dated {@code set} decides against today: a date far in the
     * future is programmed next to the current value.
     */
    @Test
    void setPublicDatedProgramsAgainstToday() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate effect = LocalDate.now().plusYears(5);
        EchelonSetting existing = row("k", "old");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, EchelonLevel.PDV, "12345", "k", existing);
            service.set(EchelonLevel.PDV, "12345", "k", "new", effect);
            assertEquals("old", existing.settingValue);
            assertEquals("new", existing.pendingValue);
            assertEquals(effect, existing.effectiveDate);
        }
    }

    /**
     * {@code resolve} returns the value when an echelon posed it (present arm)
     * and empty otherwise (absent arm).
     */
    @Test
    void resolveReturnsPresentAndAbsent() {
        EchelonSettingService service = new EchelonSettingService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", null));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "12345"))
                    .thenReturn(List.of(row("k", "v")));
            assertEquals(Optional.of("v"), service.resolve("12345", "k"));
            assertEquals(Optional.empty(), service.resolve("12345", "other"));
        }
    }

    /**
     * {@code set} inserts a new row when none exists (insert arm, immediate),
     * setting the business fields and persisting once.
     */
    @Test
    void setInsertsWhenAbsent() {
        EchelonSettingService service = new EchelonSettingService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<EchelonSetting> construction = mockConstruction(EchelonSetting.class)) {
            stubFind(panache, EchelonLevel.ENSEIGNE, "IF", "k", null);
            service.set(EchelonLevel.ENSEIGNE, "IF", "k", "v");
            assertEquals(1, construction.constructed().size());
            EchelonSetting created = construction.constructed().get(0);
            assertEquals(EchelonLevel.ENSEIGNE, created.level);
            assertEquals("IF", created.echelonCode);
            assertEquals("k", created.settingKey);
            assertEquals("v", created.settingValue);
            assertNull(created.pendingValue);
            assertNull(created.effectiveDate);
            verify(created, times(1)).persist();
        }
    }

    /**
     * {@code set} updates the value in place without constructing a row when
     * one already exists (update arm, immediate).
     */
    @Test
    void setUpdatesWhenPresent() {
        EchelonSettingService service = new EchelonSettingService();
        EchelonSetting existing = row("k", "old");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<EchelonSetting> construction = mockConstruction(EchelonSetting.class)) {
            stubFind(panache, EchelonLevel.PDV, "12345", "k", existing);
            service.set(EchelonLevel.PDV, "12345", "k", "new");
            assertEquals("new", existing.settingValue);
            assertNull(existing.effectiveDate);
            assertTrue(construction.constructed().isEmpty());
        }
    }

    /**
     * {@code clear} removes a row holding only a current value and reports
     * success (found arm, nothing programmed).
     */
    @Test
    void clearRemovesWhenPresent() {
        EchelonSettingService service = new EchelonSettingService();
        EchelonSetting existing = spied(EchelonLevel.ENSEIGNE, "IF", "k", "v", null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, EchelonLevel.ENSEIGNE, "IF", "k", existing);
            assertTrue(service.clear(EchelonLevel.ENSEIGNE, "IF", "k"));
            verify(existing, times(1)).delete();
        }
    }

    /**
     * {@code clear} on a row carrying a programmed change removes only the
     * current value: the row and its pending value stay (found arm, programmed).
     */
    @Test
    void clearKeepsTheProgrammedChange() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate day = LocalDate.of(2026, 12, 1);
        EchelonSetting existing = spied(EchelonLevel.ENSEIGNE, "IF", "k", "v", "later", day);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, EchelonLevel.ENSEIGNE, "IF", "k", existing);
            assertTrue(service.clear(EchelonLevel.ENSEIGNE, "IF", "k"));
            assertNull(existing.settingValue);
            assertEquals("later", existing.pendingValue);
            assertEquals(day, existing.effectiveDate);
            verify(existing, never()).delete();
        }
    }

    /**
     * {@code clear} reports failure and deletes nothing when no row exists
     * (absent arm).
     */
    @Test
    void clearReportsFailureWhenAbsent() {
        EchelonSettingService service = new EchelonSettingService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, EchelonLevel.ENSEIGNE, "IF", "k", null);
            assertFalse(service.clear(EchelonLevel.ENSEIGNE, "IF", "k"));
        }
    }

    /**
     * {@code clear} reports failure and keeps the row when it only carries a
     * programmed value — there is no current value to remove (null-value arm).
     */
    @Test
    void clearReportsFailureOnAProgrammedValueAlone() {
        EchelonSettingService service = new EchelonSettingService();
        EchelonSetting existing = spied(EchelonLevel.ENSEIGNE, "IF", "k", null, "later", LocalDate.of(2026, 12, 1));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, EchelonLevel.ENSEIGNE, "IF", "k", existing);
            assertFalse(service.clear(EchelonLevel.ENSEIGNE, "IF", "k"));
            assertEquals("later", existing.pendingValue);
            verify(existing, never()).delete();
        }
    }

    /**
     * A dated removal with no date, or a date already come, removes the current
     * value at once (both arms of the immediate guard).
     */
    @Test
    void clearDatedWithoutAFutureDateIsImmediate() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate today = LocalDate.of(2026, 10, 25);
        EchelonSetting first = spied(EchelonLevel.PDV, "12345", "a", "v", null, null);
        EchelonSetting second = spied(EchelonLevel.PDV, "12345", "b", "v", null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, EchelonLevel.PDV, "12345", "a", first);
            stubFind(panache, EchelonLevel.PDV, "12345", "b", second);
            assertTrue(service.clear(EchelonLevel.PDV, "12345", "a", null, today));
            assertTrue(service.clear(EchelonLevel.PDV, "12345", "b", today, today));
            verify(first, times(1)).delete();
            verify(second, times(1)).delete();
        }
    }

    /**
     * A removal dated after today is PROGRAMMED: the current value stays in
     * force, a null pending value waits for the date (future arm, current value
     * present).
     */
    @Test
    void clearDatedProgramsTheRemoval() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate day = LocalDate.of(2026, 11, 1);
        EchelonSetting existing = spied(EchelonLevel.PDV, "12345", "k", "v", "other", LocalDate.of(2026, 12, 1));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, EchelonLevel.PDV, "12345", "k", existing);
            assertTrue(service.clear(EchelonLevel.PDV, "12345", "k", day, LocalDate.of(2026, 10, 25)));
            assertEquals("v", existing.settingValue);
            assertNull(existing.pendingValue);
            assertEquals(day, existing.effectiveDate);
            verify(existing, never()).delete();
        }
    }

    /**
     * A removal dated after today on a key with no row, or with a row holding
     * no current value, programs nothing (both arms of the nothing-to-remove
     * guard): a programmed value alone is left as it is.
     */
    @Test
    void clearDatedWithNothingToRemoveProgramsNothing() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate today = LocalDate.of(2026, 10, 25);
        LocalDate pendingDay = LocalDate.of(2026, 12, 1);
        EchelonSetting alone = spied(EchelonLevel.PDV, "12345", "b", null, "later", pendingDay);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, EchelonLevel.PDV, "12345", "a", null);
            stubFind(panache, EchelonLevel.PDV, "12345", "b", alone);
            assertFalse(service.clear(EchelonLevel.PDV, "12345", "a", LocalDate.of(2026, 11, 1), today));
            assertFalse(service.clear(EchelonLevel.PDV, "12345", "b", LocalDate.of(2026, 11, 1), today));
            assertEquals("later", alone.pendingValue);
            assertEquals(pendingDay, alone.effectiveDate);
        }
    }

    /**
     * The public dated {@code clear} decides against today: a removal five
     * years ahead is programmed.
     */
    @Test
    void clearPublicDatedProgramsAgainstToday() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate day = LocalDate.now().plusYears(5);
        EchelonSetting existing = spied(EchelonLevel.PDV, "12345", "k", "v", null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, EchelonLevel.PDV, "12345", "k", existing);
            assertTrue(service.clear(EchelonLevel.PDV, "12345", "k", day));
            assertEquals("v", existing.settingValue);
            assertEquals(day, existing.effectiveDate);
        }
    }

    /**
     * {@code programmed} lists the changes programmed after today.
     */
    @Test
    void programmedListsTheChangesToCome() {
        EchelonSettingService service = new EchelonSettingService();
        List<EchelonSetting> rows = List.of(programmed("k", "v", "w", LocalDate.now().plusDays(3)));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EchelonSetting.list(
                    "effectiveDate > ?1 order by effectiveDate, echelonCode, settingKey", LocalDate.now()))
                    .thenReturn(rows);
            assertSame(rows, service.programmed());
        }
    }

    /**
     * {@code promoteDue} folds every due change into its current value: a
     * pending value becomes current, a programmed removal deletes its row, and
     * the count says how many were folded.
     */
    @Test
    void promoteDueFoldsTheChangesWhoseDateHasCome() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate today = LocalDate.of(2026, 11, 1);
        EchelonSetting value = spied(EchelonLevel.PDV, "12345", "a", "old", "new", today);
        EchelonSetting removal = spied(EchelonLevel.PDV, "12345", "b", "old", null, today);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EchelonSetting.list("effectiveDate <= ?1", today)).thenReturn(List.of(value, removal));
            assertEquals(2, service.promoteDue(today));
            assertEquals("new", value.settingValue);
            assertNull(value.pendingValue);
            assertNull(value.effectiveDate);
            verify(value, never()).delete();
            verify(removal, times(1)).delete();
        }
    }

    /**
     * {@code promoteDue} folds nothing when no change is due.
     */
    @Test
    void promoteDueWithNothingDueFoldsNothing() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate today = LocalDate.of(2026, 11, 1);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EchelonSetting.list("effectiveDate <= ?1", today)).thenReturn(List.of());
            assertEquals(0, service.promoteDue(today));
        }
    }

    /**
     * Folding the menu of a checkout point writes it on the point's own column
     * too (BO-03-10-09), a programmed removal empties the column, and an unknown
     * point is skipped — the three arms of the mirror.
     */
    @Test
    void promotingAPointMenuWritesThePointColumn() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate today = LocalDate.of(2026, 11, 1);
        EchelonSetting menu = spied(EchelonLevel.CHECKOUT_POINT, "POS01", "menu.code", "SLS", "LIGNE", today);
        EchelonSetting removal = spied(EchelonLevel.CHECKOUT_POINT, "POS02", "menu.code", "SLS", null, today);
        EchelonSetting unknown = spied(EchelonLevel.CHECKOUT_POINT, "POS09", "menu.code", "SLS", "LIGNE", today);
        CheckoutPoint first = point("POS01", null);
        first.menuTemplateCode = "SLS";
        CheckoutPoint second = point("POS02", null);
        second.menuTemplateCode = "SLS";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPoint(panache, "POS01", first);
            stubPoint(panache, "POS02", second);
            stubPoint(panache, "POS09", null);
            panache.when(() -> EchelonSetting.list("effectiveDate <= ?1", today))
                    .thenReturn(List.of(menu, removal, unknown));
            assertEquals(3, service.promoteDue(today));
            assertEquals("LIGNE", first.menuTemplateCode);
            assertNull(second.menuTemplateCode);
            assertEquals("LIGNE", unknown.settingValue);
        }
    }

    /**
     * Only the menu of a CHECKOUT POINT is mirrored: another key of a point and
     * the menu of an island leave every point column alone (the two other arms
     * of the mirror guard).
     */
    @Test
    void promotingAnotherKeyOrLevelTouchesNoPoint() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate today = LocalDate.of(2026, 11, 1);
        EchelonSetting otherKey = spied(EchelonLevel.CHECKOUT_POINT, "POS01", "ticket.vat-table", "NONE", "SEPARATE", today);
        EchelonSetting island = spied(EchelonLevel.ISLAND, "L1", "menu.code", "SLS", "LIGNE", today);
        CheckoutPoint first = point("POS01", null);
        first.menuTemplateCode = "SLS";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPoint(panache, "POS01", first);
            panache.when(() -> EchelonSetting.list("effectiveDate <= ?1", today)).thenReturn(List.of(otherKey, island));
            assertEquals(2, service.promoteDue(today));
            assertEquals("SEPARATE", otherKey.settingValue);
            assertEquals("LIGNE", island.settingValue);
            assertEquals("SLS", first.menuTemplateCode);
        }
    }

    /**
     * {@code advance} applies a programmed change now; it refuses an unknown id
     * and a row with nothing programmed (the three arms).
     */
    @Test
    void advanceAppliesTheProgrammedValueNow() {
        EchelonSettingService service = new EchelonSettingService();
        EchelonSetting row = spied(EchelonLevel.PDV, "12345", "k", "old", "new", LocalDate.of(2026, 12, 1));
        EchelonSetting plain = spied(EchelonLevel.PDV, "12345", "p", "v", null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(null);
            panache.when(() -> PanacheEntityBase.findById(2L)).thenReturn(plain);
            panache.when(() -> PanacheEntityBase.findById(3L)).thenReturn(row);
            assertFalse(service.advance(1L));
            assertFalse(service.advance(2L));
            assertTrue(service.advance(3L));
            assertEquals("new", row.settingValue);
            assertNull(row.pendingValue);
            assertNull(row.effectiveDate);
            assertEquals("v", plain.settingValue);
        }
    }

    /**
     * {@code cancel} removes only the pending value and keeps the current one;
     * a row holding nothing else disappears; an unknown id and a row with
     * nothing programmed are refused (the four arms).
     */
    @Test
    void cancelRemovesOnlyThePendingValue() {
        EchelonSettingService service = new EchelonSettingService();
        EchelonSetting kept = spied(EchelonLevel.PDV, "12345", "k", "old", "new", LocalDate.of(2026, 12, 1));
        EchelonSetting alone = spied(EchelonLevel.PDV, "12345", "a", null, "new", LocalDate.of(2026, 12, 1));
        EchelonSetting plain = spied(EchelonLevel.PDV, "12345", "p", "v", null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(null);
            panache.when(() -> PanacheEntityBase.findById(2L)).thenReturn(plain);
            panache.when(() -> PanacheEntityBase.findById(3L)).thenReturn(kept);
            panache.when(() -> PanacheEntityBase.findById(4L)).thenReturn(alone);
            assertFalse(service.cancel(1L));
            assertFalse(service.cancel(2L));
            assertTrue(service.cancel(3L));
            assertTrue(service.cancel(4L));
            assertEquals("old", kept.settingValue);
            assertNull(kept.pendingValue);
            assertNull(kept.effectiveDate);
            verify(kept, never()).delete();
            verify(alone, times(1)).delete();
            verify(plain, never()).delete();
        }
    }

    /**
     * {@code personalizedPdvs} returns nothing when the enseigne has no PDV
     * (empty-enseigne arm) and never queries the settings.
     */
    @Test
    void personalizedPdvsEmptyWhenEnseigneHasNoPdv() {
        EchelonSettingService service = new EchelonSettingService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Pdv.list(PDV_BY_ENSEIGNE, "IF")).thenReturn(List.of());
            assertTrue(service.personalizedPdvs("IF", "k").isEmpty());
            panache.verify(() -> EchelonSetting.list(SETTING_FOR_KEY, EchelonLevel.PDV, "k"), never());
        }
    }

    /**
     * {@code personalizedPdvs} keeps only the PDVs that carry their own value
     * for the key (both the personalised arm and the plain arm of the filter).
     */
    @Test
    void personalizedPdvsFiltersToOverriddenOnes() {
        EchelonSettingService service = new EchelonSettingService();
        Pdv overridden = pdv("11111", "IF");
        Pdv plain = pdv("22222", "IF");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Pdv.list(PDV_BY_ENSEIGNE, "IF")).thenReturn(List.of(overridden, plain));
            EchelonSetting override = new EchelonSetting();
            override.echelonCode = "11111";
            panache.when(() -> EchelonSetting.list(SETTING_FOR_KEY, EchelonLevel.PDV, "k"))
                    .thenReturn(List.of(override));
            List<Pdv> result = service.personalizedPdvs("IF", "k");
            assertEquals(1, result.size());
            assertSame(overridden, result.get(0));
        }
    }

    /**
     * {@code migratePdv} re-keys every PDV-level setting from the old number to
     * the new one (BO-02-05-01) and returns the count — the loop-entered arm.
     */
    @Test
    void migratePdvRekeysPdvSettings() {
        EchelonSettingService service = new EchelonSettingService();
        EchelonSetting first = row("discount.enabled", "false");
        first.level = EchelonLevel.PDV;
        first.echelonCode = "07039";
        EchelonSetting second = row("display.show-ean", "true");
        second.level = EchelonLevel.PDV;
        second.echelonCode = "07039";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "07039"))
                    .thenReturn(List.of(first, second));
            int migrated = service.migratePdv("07039", "07040");
            assertEquals(2, migrated);
            assertEquals("07040", first.echelonCode);
            assertEquals("07040", second.echelonCode);
        }
    }

    /**
     * {@code migratePdv} on a PDV that posed no echelon setting re-keys nothing
     * and returns zero — the loop-not-entered arm.
     */
    @Test
    void migratePdvOnNoSettingsMigratesNothing() {
        EchelonSettingService service = new EchelonSettingService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "07039"))
                    .thenReturn(List.of());
            assertEquals(0, service.migratePdv("07039", "07040"));
        }
    }

    /**
     * Any PosSettingsService catalog key posed at an echelon is inherited by a
     * PDV automatically (BO-02-05-04): the resolution engine is generic, so a
     * key taken straight from {@link PosSettingsService#CATALOG} — the very list
     * the echelon admin screen offers — resolves through the enseigne level
     * without the engine naming it.
     */
    @Test
    void resolveInheritsAnyPosSettingsCatalogKey() {
        EchelonSettingService service = new EchelonSettingService();
        String catalogKey = PosSettingsService.CATALOG.get(0).key();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", "IF"));
            stubEnseigne(panache, "IF", null);
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.ENSEIGNE, "IF"))
                    .thenReturn(List.of(row(catalogKey, "inherited")));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "12345"))
                    .thenReturn(List.of());
            assertEquals("inherited", service.resolve("12345", catalogKey).orElse(null));
        }
    }

    // --- Five-level resolution: ISLAND and CHECKOUT_POINT (BO-03-09/03-10) ---

    /** The query string of {@link CheckoutPoint#findByTerminal}. */
    private static final String POINT_BY_TERMINAL = "terminalId";

    /**
     * Stubs {@code CheckoutPoint.findByTerminal(terminal)} to return the row.
     *
     * @param panache the active static mock
     * @param terminalId the terminal to match
     * @param row the checkout point to resolve, or null
     */
    @SuppressWarnings("unchecked")
    private void stubPoint(MockedStatic<PanacheEntityBase> panache, String terminalId, CheckoutPoint row) {
        PanacheQuery<CheckoutPoint> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        panache.when(() -> CheckoutPoint.find(POINT_BY_TERMINAL, terminalId)).thenReturn(query);
    }

    /**
     * Builds a checkout point pointing at an island.
     *
     * @param terminalId the terminal identifier
     * @param islandCode the island code, or null
     * @return the built point
     */
    private CheckoutPoint point(String terminalId, String islandCode) {
        CheckoutPoint point = new CheckoutPoint();
        point.terminalId = terminalId;
        point.islandCode = islandCode;
        return point;
    }

    /**
     * A null terminal falls back to the PDV resolution alone: neither the island
     * nor the checkout-point layer is consulted.
     */
    @Test
    void resolveForTerminalNullTerminalFallsBackToPdv() {
        EchelonSettingService service = new EchelonSettingService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", null));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "12345"))
                    .thenReturn(List.of(row("k", "pdv")));
            Map<String, String> resolved = service.resolveForTerminal("12345", null, LocalDate.of(2026, 6, 15));
            assertEquals("pdv", resolved.get("k"));
        }
    }

    /**
     * The full five-level precedence: a value posed on the checkout point wins
     * over the island's, which wins over the PDV's — and each level contributes
     * the keys the more specific ones leave untouched.
     */
    @Test
    void resolveForTerminalCheckoutPointWinsOverIslandOverPdv() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate asOf = LocalDate.of(2026, 6, 15);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", null));
            stubPoint(panache, "POS01", point("POS01", "AVANT"));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "12345"))
                    .thenReturn(List.of(row("k", "pdv"), row("m", "pdvM"), row("onlyPdv", "P")));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.ISLAND, "AVANT"))
                    .thenReturn(List.of(row("k", "island"), row("m", "islandM"), row("onlyIsland", "I")));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.CHECKOUT_POINT, "POS01"))
                    .thenReturn(List.of(row("k", "point")));
            Map<String, String> resolved = service.resolveForTerminal("12345", "POS01", asOf);
            assertEquals("point", resolved.get("k"));
            assertEquals("islandM", resolved.get("m"));
            assertEquals("I", resolved.get("onlyIsland"));
            assertEquals("P", resolved.get("onlyPdv"));
        }
    }

    /**
     * A terminal with no checkout point skips the island layer but still takes a
     * value posed directly on its identifier at the CHECKOUT_POINT level.
     */
    @Test
    void resolveForTerminalWithNoPointSkipsTheIslandLayer() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate asOf = LocalDate.of(2026, 6, 15);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", null));
            stubPoint(panache, "POS01", null);
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "12345"))
                    .thenReturn(List.of(row("k", "pdv")));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.CHECKOUT_POINT, "POS01"))
                    .thenReturn(List.of(row("k", "point")));
            Map<String, String> resolved = service.resolveForTerminal("12345", "POS01", asOf);
            assertEquals("point", resolved.get("k"));
        }
    }

    /**
     * A checkout point tied to no island skips the island layer (the
     * {@code islandCode != null} arm), the checkout-point layer still applying.
     */
    @Test
    void resolveForTerminalWithPointButNoIslandSkipsTheIslandLayer() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate asOf = LocalDate.of(2026, 6, 15);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", null));
            stubPoint(panache, "POS01", point("POS01", null));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "12345"))
                    .thenReturn(List.of(row("k", "pdv")));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.CHECKOUT_POINT, "POS01"))
                    .thenReturn(List.of(row("j", "point")));
            Map<String, String> resolved = service.resolveForTerminal("12345", "POS01", asOf);
            assertEquals("pdv", resolved.get("k"));
            assertEquals("point", resolved.get("j"));
        }
    }

    /**
     * The public {@code resolveForTerminal} delegates to the dated core with the
     * current day; with only immediate rows it returns the resolved values.
     */
    @Test
    void resolveForTerminalPublicUsesToday() {
        EchelonSettingService service = new EchelonSettingService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", null));
            stubPoint(panache, "POS01", point("POS01", "AVANT"));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "12345"))
                    .thenReturn(List.of());
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.ISLAND, "AVANT"))
                    .thenReturn(List.of());
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.CHECKOUT_POINT, "POS01"))
                    .thenReturn(List.of(row("k", "point")));
            assertEquals("point", service.resolveForTerminal("12345", "POS01").get("k"));
        }
    }

    /** LC-02-07-08: the same visibility choice inherits through all five levels, most specific first. */
    @Test
    void touchEanVisibilityResolvesAcrossAllFiveEchelons() {
        EchelonSettingService service = new EchelonSettingService();
        Enseigne enseigne = new Enseigne();
        enseigne.code = "ITM";
        enseigne.countryCode = "FR";
        EchelonLevel[] levels = {EchelonLevel.COUNTRY, EchelonLevel.ENSEIGNE, EchelonLevel.PDV,
                EchelonLevel.ISLAND, EchelonLevel.CHECKOUT_POINT};
        String[] codes = {"FR", "ITM", "12345", "AVANT", "POS01"};
        List<List<EchelonSetting>> values = new java.util.ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", "ITM"));
            stubEnseigne(panache, "ITM", enseigne);
            stubCountry(panache, "FR", null);
            stubPoint(panache, "POS01", point("POS01", "AVANT"));
            for (int index = 0; index < levels.length; index++) {
                EchelonLevel level = levels[index];
                String code = codes[index];
                List<EchelonSetting> rows = new java.util.ArrayList<>();
                values.add(rows);
                panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, level, code)).thenReturn(rows);
            }
            for (int index = 0; index < levels.length; index++) {
                String expected = String.valueOf(index % 2 == 0);
                values.get(index).add(row("touch.show-ean", expected));
                assertEquals(expected, service.resolveForTerminal("12345", "POS01").get("touch.show-ean"));
            }
        }
    }

    /**
     * Stubs {@code Country.findByCode(code)} to return the given row.
     *
     * @param panache the active static mock
     * @param code the code to match
     * @param row the row to resolve, or null
     */
    @SuppressWarnings("unchecked")
    private void stubCountry(MockedStatic<PanacheEntityBase> panache, String code, Country row) {
        PanacheQuery<Country> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        panache.when(() -> Country.find(ENSEIGNE_BY_CODE, code)).thenReturn(query);
    }

    /**
     * BO-02-05-04: the reference language of the country, then of the enseigne,
     * becomes the system language the PDV inherits, a value posed at the PDV
     * still winning; the origins say where each value comes from.
     */
    @Test
    void theLanguageOfTheEchelonsIsInherited() {
        EchelonSettingService service = new EchelonSettingService();
        Enseigne dutch = new Enseigne();
        dutch.code = "IB";
        dutch.countryCode = "BE";
        dutch.defaultLanguage = " nl ";
        Enseigne silent = new Enseigne();
        silent.code = "IS";
        silent.countryCode = "BE";
        silent.defaultLanguage = " ";
        Country belgium = new Country();
        belgium.code = "BE";
        belgium.defaultLanguage = "fr";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "10001", pdv("10001", "IB"));
            stubPdv(panache, "10002", pdv("10002", "IS"));
            stubPdv(panache, "10003", pdv("10003", "IB"));
            stubEnseigne(panache, "IB", dutch);
            stubEnseigne(panache, "IS", silent);
            stubCountry(panache, "BE", belgium);
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "10003"))
                    .thenReturn(List.of(row("language.system", "pt")));
            assertEquals("nl", service.resolveForPdv("10001").get("language.system"));
            assertEquals("fr", service.resolveForPdv("10002").get("language.system"));
            assertEquals("pt", service.resolveForPdv("10003").get("language.system"));
            List<EchelonSettingService.Inherited> inherited = service.inheritedFor("10001");
            assertEquals(List.of(new EchelonSettingService.Inherited("language.system", "nl", "ENSEIGNE", "IB")),
                    inherited);
            assertEquals(List.of(new EchelonSettingService.Inherited("language.system", "fr", "COUNTRY", "BE")),
                    service.inheritedFor("10002"));
            assertEquals(List.of(new EchelonSettingService.Inherited("language.system", "pt", "PDV", "10003")),
                    service.inheritedFor("10003"));
        }
    }

    /**
     * A country the tree does not hold, or one without a language, poses no
     * language, and an unknown PDV inherits nothing.
     */
    @Test
    void anUnknownCountryPosesNoLanguage() {
        EchelonSettingService service = new EchelonSettingService();
        Enseigne enseigne = new Enseigne();
        enseigne.code = "IF";
        enseigne.countryCode = "XX";
        Enseigne other = new Enseigne();
        other.code = "IG";
        other.countryCode = "YY";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "10001", pdv("10001", "IF"));
            stubPdv(panache, "10002", pdv("10002", "IG"));
            stubPdv(panache, "99999", null);
            stubEnseigne(panache, "IF", enseigne);
            stubEnseigne(panache, "IG", other);
            stubCountry(panache, "XX", null);
            stubCountry(panache, "YY", new Country());
            assertNull(service.resolveForPdv("10001").get("language.system"));
            assertNull(service.resolveForPdv("10002").get("language.system"));
            assertTrue(service.inheritedFor("99999").isEmpty());
        }
    }
}
