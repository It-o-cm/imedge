package com.intermarche.pos.service;

import com.intermarche.pos.domain.setting.EchelonLevel;
import com.intermarche.pos.domain.setting.EchelonSetting;
import com.intermarche.pos.domain.store.Enseigne;
import com.intermarche.pos.domain.store.Pdv;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
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
     * The date-aware overload gates each row on its effect date (BO-03-12-03/04):
     * a row dated in the past and an undated row are in effect, a row dated in
     * the future is ignored until its day — covering all three arms of the
     * {@code effectiveDate == null || !isAfter(asOf)} overlay guard.
     */
    @Test
    void resolveForPdvGatesRowsOnEffectiveDate() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate asOf = LocalDate.of(2026, 6, 15);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPdv(panache, "12345", pdv("12345", null));
            panache.when(() -> EchelonSetting.list(SETTING_FOR_ECHELON, EchelonLevel.PDV, "12345"))
                    .thenReturn(List.of(
                            row("past", "p", LocalDate.of(2026, 1, 1)),
                            row("undated", "n", null),
                            row("future", "f", LocalDate.of(2026, 12, 1))));
            Map<String, String> resolved = service.resolveForPdv("12345", asOf);
            assertEquals("p", resolved.get("past"));
            assertEquals("n", resolved.get("undated"));
            assertFalse(resolved.containsKey("future"));
            assertEquals(2, resolved.size());
        }
    }

    /**
     * {@code set} with an effect date stores it on the inserted row (insert arm).
     */
    @Test
    void setStoresEffectiveDateOnInsert() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate effect = LocalDate.of(2026, 3, 1);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<EchelonSetting> construction = mockConstruction(EchelonSetting.class)) {
            PanacheQuery<EchelonSetting> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> EchelonSetting.find(SETTING_FIND, EchelonLevel.ENSEIGNE, "IF", "k"))
                    .thenReturn(query);
            service.set(EchelonLevel.ENSEIGNE, "IF", "k", "v", effect);
            EchelonSetting created = construction.constructed().get(0);
            assertEquals(effect, created.effectiveDate);
            verify(created, times(1)).persist();
        }
    }

    /**
     * {@code set} with an effect date stores it on the updated row (update arm).
     */
    @Test
    void setStoresEffectiveDateOnUpdate() {
        EchelonSettingService service = new EchelonSettingService();
        LocalDate effect = LocalDate.of(2026, 3, 1);
        EchelonSetting existing = row("k", "old");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<EchelonSetting> construction = mockConstruction(EchelonSetting.class)) {
            PanacheQuery<EchelonSetting> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            panache.when(() -> EchelonSetting.find(SETTING_FIND, EchelonLevel.PDV, "12345", "k"))
                    .thenReturn(query);
            service.set(EchelonLevel.PDV, "12345", "k", "new", effect);
            assertEquals("new", existing.settingValue);
            assertEquals(effect, existing.effectiveDate);
            assertTrue(construction.constructed().isEmpty());
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
     * {@code set} inserts a new row when none exists (insert arm), setting the
     * four business fields and persisting once.
     */
    @Test
    void setInsertsWhenAbsent() {
        EchelonSettingService service = new EchelonSettingService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<EchelonSetting> construction = mockConstruction(EchelonSetting.class)) {
            PanacheQuery<EchelonSetting> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> EchelonSetting.find(SETTING_FIND, EchelonLevel.ENSEIGNE, "IF", "k"))
                    .thenReturn(query);
            service.set(EchelonLevel.ENSEIGNE, "IF", "k", "v");
            assertEquals(1, construction.constructed().size());
            EchelonSetting created = construction.constructed().get(0);
            assertEquals(EchelonLevel.ENSEIGNE, created.level);
            assertEquals("IF", created.echelonCode);
            assertEquals("k", created.settingKey);
            assertEquals("v", created.settingValue);
            verify(created, times(1)).persist();
        }
    }

    /**
     * {@code set} updates the value in place without constructing a row when
     * one already exists (update arm).
     */
    @Test
    void setUpdatesWhenPresent() {
        EchelonSettingService service = new EchelonSettingService();
        EchelonSetting existing = row("k", "old");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<EchelonSetting> construction = mockConstruction(EchelonSetting.class)) {
            PanacheQuery<EchelonSetting> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            panache.when(() -> EchelonSetting.find(SETTING_FIND, EchelonLevel.PDV, "12345", "k"))
                    .thenReturn(query);
            service.set(EchelonLevel.PDV, "12345", "k", "new");
            assertEquals("new", existing.settingValue);
            assertTrue(construction.constructed().isEmpty());
        }
    }

    /**
     * {@code clear} removes the row and reports success when one exists
     * (found arm).
     */
    @Test
    void clearRemovesWhenPresent() {
        EchelonSettingService service = new EchelonSettingService();
        EchelonSetting existing = mock(EchelonSetting.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<EchelonSetting> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            panache.when(() -> EchelonSetting.find(SETTING_FIND, EchelonLevel.ENSEIGNE, "IF", "k"))
                    .thenReturn(query);
            assertTrue(service.clear(EchelonLevel.ENSEIGNE, "IF", "k"));
            verify(existing, times(1)).delete();
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
            PanacheQuery<EchelonSetting> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> EchelonSetting.find(SETTING_FIND, EchelonLevel.ENSEIGNE, "IF", "k"))
                    .thenReturn(query);
            assertFalse(service.clear(EchelonLevel.ENSEIGNE, "IF", "k"));
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
}
