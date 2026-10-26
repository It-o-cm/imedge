package com.intermarche.edge.service;

import com.intermarche.edge.domain.setting.PosSetting;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PosSettingsService}.
 * <p>
 * The service is a Panache active-record consumer over {@link PosSetting}
 * ({@code listAll}, the delegated {@code find} of {@code findByKey}, and the
 * {@code persist} of the upsert). Every static access resolves to
 * {@link PanacheEntityBase} and is intercepted with
 * {@link org.mockito.Mockito#mockStatic}; the inserted row is neutralized with
 * {@link org.mockito.Mockito#mockConstruction}. The legacy configuration
 * fallback goes through {@link ConfigProvider}, itself mocked statically. No
 * database and no Quarkus context is booted.
 * <p>
 * Branch enumeration (every arm exercised — 100%):
 * {@code def} (found / not found), {@code value} (cold-cache load then warm
 * reuse, stored-present arm, unknown-key null arm, config-fallback present /
 * absent arm, no-fallback default arm), {@code loadAll} (success / exception
 * arm), {@code invalidate}, {@code store} (insert / update arm),
 * {@code intValue} (parse ok / corrupt-row arm), {@code boolValue} (true /
 * false), and each public accessor. The financial accessors add their own
 * decision points: {@code floatingFloat} (FLOATING prefix / not),
 * {@code financialClosedWeekdays} and {@code financialClosedDates} (valid add /
 * blank continue / unparsable catch), and {@code financialSectors} (valid put /
 * blank / no-separator / trailing-separator). Every arm is exercised (100%).
 */
class PosSettingsServiceTest {

    /**
     * Builds a {@link PosSetting} row carrying the given key and value.
     *
     * @param key the setting key
     * @param value the setting value
     * @return the row
     */
    private PosSetting row(String key, String value) {
        PosSetting row = mock(PosSetting.class);
        row.settingKey = key;
        row.settingValue = value;
        return row;
    }

    /**
     * Builds a Panache query whose {@code firstResult} resolves to the value.
     *
     * @param result the value the query returns
     * @param <T> the queried type
     * @return the configured mocked query
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> query(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * {@code def} returns the catalog entry for a known key (found arm) and
     * null for an unknown one (not-found arm).
     */
    @Test
    void defReturnsEntryOrNull() {
        PosSettingsService service = new PosSettingsService();
        assertEquals("display.show-ean", service.def("display.show-ean").key());
        assertNull(service.def("no.such.key"));
    }

    /**
     * {@code value} loads the table on a cold cache and returns the stored
     * override (stored-present arm); a second call reuses the warm cache
     * (loadAll not called again).
     */
    @Test
    void valueReturnsStoredOverrideAndCachesTable() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("display.show-ean", "true")));
            assertEquals("true", service.value("display.show-ean"));
            assertEquals("true", service.value("display.show-ean"));
            ms.verify(PanacheEntityBase::listAll, times(1));
        }
    }

    /**
     * {@code value} returns null for an unknown key with no stored row
     * (def-null arm), the table being empty.
     */
    @Test
    void valueReturnsNullForUnknownKey() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertNull(service.value("no.such.key"));
        }
    }

    /**
     * {@code value} consults the legacy configuration fallback when no row
     * exists and the key declares one (config-present arm).
     */
    @Test
    void valueFallsBackToConfigWhenPresent() {
        PosSettingsService service = new PosSettingsService();
        Config config = mock(Config.class);
        when(config.getOptionalValue("pos.display.show-ean", String.class)).thenReturn(Optional.of("true"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
                MockedStatic<ConfigProvider> cfg = mockStatic(ConfigProvider.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            cfg.when(ConfigProvider::getConfig).thenReturn(config);
            assertEquals("true", service.value("display.show-ean"));
        }
    }

    /**
     * {@code value} falls through to the catalog default when the declared
     * configuration fallback is absent (config-absent arm).
     */
    @Test
    void valueFallsBackToDefaultWhenConfigAbsent() {
        PosSettingsService service = new PosSettingsService();
        Config config = mock(Config.class);
        when(config.getOptionalValue("pos.display.show-ean", String.class)).thenReturn(Optional.empty());
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
                MockedStatic<ConfigProvider> cfg = mockStatic(ConfigProvider.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            cfg.when(ConfigProvider::getConfig).thenReturn(config);
            assertEquals("false", service.value("display.show-ean"));
        }
    }

    /**
     * {@code value} returns the catalog default for a key without any
     * configuration fallback (no-fallback arm).
     */
    @Test
    void valueReturnsDefaultWhenNoFallback() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("true", service.value("gesture.endorsement-required"));
        }
    }

    /**
     * {@code loadAll} swallows a read failure and falls back to defaults
     * (exception arm): nothing is cached and the default is returned.
     */
    @Test
    void valueSurvivesReadFailure() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenThrow(new RuntimeException("no table"));
            assertEquals("true", service.value("gesture.endorsement-required"));
        }
    }

    /**
     * A FAILED read caches nothing, so the very next access tries the database
     * again: two {@code value} calls issue two {@code listAll}.
     * <p>
     * This is the defect the guard exists for. {@code loadAll} used to clear
     * the cache itself and hand back an empty map, which its own caller then
     * stored — so the empty map became the node's parameters until someone
     * saved a setting or restarted it. A register that cannot read its
     * parameters at boot, like a store node whose background loop reads them
     * without a request context, must recover on its own.
     */
    @Test
    void aFailedReadIsNotCachedAndTheNextAccessRetries() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenThrow(new RuntimeException("no table"));
            assertEquals("true", service.value("gesture.endorsement-required"));
            assertEquals("true", service.value("gesture.endorsement-required"));
            ms.verify(PanacheEntityBase::listAll, times(2));
        }
    }

    /**
     * A read that FAILS then SUCCEEDS serves the default first and the stored
     * override next — the recovery the retry buys, asserted on the value and
     * not only on the call count.
     */
    @Test
    void aReadRecoveringAfterAFailureServesTheStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenThrow(new RuntimeException("no table"))
                    .thenReturn(List.of(row("gesture.endorsement-required", "false")));
            assertEquals("true", service.value("gesture.endorsement-required"));
            assertEquals("false", service.value("gesture.endorsement-required"));
        }
    }

    /**
     * A SUCCESSFUL read is cached, failure or not afterwards: once the table
     * has answered, a later throw never reaches the caller because no reload
     * is attempted.
     */
    @Test
    void aSuccessfulReadIsCachedAndNeverReloaded() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("gesture.endorsement-required", "false")));
            assertEquals("false", service.value("gesture.endorsement-required"));
            assertEquals("false", service.value("gesture.endorsement-required"));
            ms.verify(PanacheEntityBase::listAll, times(1));
        }
    }

    /**
     * {@code administeredValues} goes through the same cache as {@code value}:
     * a failed read caches nothing there either, so the screen that renders the
     * administered values does not freeze the defaults in.
     */
    @Test
    void administeredValuesDoesNotCacheAFailedRead() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenThrow(new RuntimeException("no table"));
            service.administeredValues();
            service.administeredValues();
            ms.verify(PanacheEntityBase::listAll, times(2));
        }
    }

    /**
     * {@code customerCreditTender} returns the stored key TRIMMED when it is
     * present and non-blank (BO-10-04-09): both compound-guard legs are false
     * ({@code key == null} false, {@code key.isBlank()} false) and the ternary
     * takes its false arm, {@code key.trim()}.
     */
    @Test
    void customerCreditTenderTrimsStoredKey() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.credit-tender", "  CB_CREDIT  ")));
            assertEquals("CB_CREDIT", service.customerCreditTender());
        }
    }

    /**
     * {@code customerCreditTender} falls back to {@code CREDIT} when the stored
     * value is blank (BO-10-04-09): the guard {@code key.isBlank()} is true and
     * the ternary takes its true arm.
     */
    @Test
    void customerCreditTenderFallsBackToCreditWhenBlank() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.credit-tender", "   ")));
            assertEquals("CREDIT", service.customerCreditTender());
        }
    }

    /**
     * {@code store} inserts a new row when none exists (insert arm): the
     * constructed instance gets the key and value and is persisted, and the
     * cache is dropped so the next read reloads.
     */
    @Test
    void storeInsertsWhenAbsent() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
                MockedConstruction<PosSetting> created = mockConstruction(PosSetting.class)) {
            PanacheQuery<PosSetting> absent = query(null);
            ms.when(() -> PanacheEntityBase.find("settingKey", "display.show-ean")).thenReturn(absent);
            service.store("display.show-ean", "true");
            PosSetting inserted = created.constructed().get(0);
            assertEquals("display.show-ean", inserted.settingKey);
            assertEquals("true", inserted.settingValue);
            verify(inserted, times(1)).persist();
        }
    }

    /**
     * {@code store} updates the existing row when one is found (update arm):
     * the found row's value is overwritten and persisted, no row constructed.
     */
    @Test
    void storeUpdatesWhenPresent() {
        PosSettingsService service = new PosSettingsService();
        PosSetting existing = mock(PosSetting.class);
        existing.settingKey = "display.show-ean";
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
                MockedConstruction<PosSetting> created = mockConstruction(PosSetting.class)) {
            PanacheQuery<PosSetting> present = query(existing);
            ms.when(() -> PanacheEntityBase.find("settingKey", "display.show-ean")).thenReturn(present);
            service.store("display.show-ean", "true");
            assertTrue(created.constructed().isEmpty());
            assertEquals("true", existing.settingValue);
            verify(existing, times(1)).persist();
        }
    }

    /**
     * {@code invalidate} drops the cache: after a first cold load, a store's
     * invalidation forces a fresh {@code listAll} on the next value read.
     */
    @Test
    void invalidateForcesReload() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            service.value("display.show-ean");
            service.invalidate();
            service.value("display.show-ean");
            ms.verify(PanacheEntityBase::listAll, times(2));
        }
    }

    /**
     * {@code intValue} returns the parsed integer for a well-formed row
     * (parse-ok arm).
     */
    @Test
    void intValueParsesStoredNumber() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("auth.idle-lockout-seconds", " 42 ")));
            assertEquals(42L, service.idleLockoutSeconds());
        }
    }

    /**
     * A retention parameter reads its stored value, and an absent row falls to
     * the catalog default of zero — the unlimited-retention default that keeps a
     * fresh node from deleting a thing (BO-10-08).
     */
    @Test
    void retentionSettingReadsTheStoredValueOrDefaultsToZero() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("retention.sales-days", "30")));
            assertEquals(30, service.retentionSetting("retention.sales-days"));
            assertEquals(0, service.retentionSetting("retention.customers-days"));
        }
    }

    /**
     * The projected-cagnotte label reads its stored row, and an absent row falls
     * to the catalog default (BO-03-03-31) — the label imedge prints before the
     * forecast balance ported from impos.
     */
    @Test
    void fidelityProjectedLabelReadsTheStoredValueOrDefault() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("fidelity.projected-label", "SOLDE A VENIR")));
            assertEquals("SOLDE A VENIR", service.fidelityProjectedLabel());
        }
        PosSettingsService fresh = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("CAGNOTTE PREVISIONNELLE", fresh.fidelityProjectedLabel());
        }
    }

    /**
     * {@code intValue} falls back to the catalog default on a corrupt row
     * (parse-fail arm).
     */
    @Test
    void intValueFallsBackOnCorruptRow() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("discount.line-max-percent", "oops")));
            assertEquals(100, service.lineMaxDiscountPercent());
        }
    }

    /**
     * Each public accessor projects its stored row: the boolean accessors
     * (true / false arms), the remaining integer accessor and the two text
     * accessors, plus the parking flag.
     */
    @Test
    void accessorsProjectStoredValues() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("display.show-ean", "true"),
                    row("gesture.endorsement-required", "false"),
                    row("discount.global-max-percent", "30"),
                    row("customer.message-open", "Bonjour"),
                    row("customer.message-closed", "Fermé"),
                    row("parking.print-receipt", "false"),
                    row("payment.degraded-mode", "true"),
                    row("discount.enabled", "false"),
                    row("price.show-original-on-force", "false"),
                    row("ticket.header-message", "Promo"),
                    row("ticket.footer-message", "A bientot"),
                    row("customer.qr-enabled", "false"),
                    row("drawer.open-on-payment", "false"),
                    row("drawer.open-on-login", "false"),
                    row("scan.ean13-check-digit", "true"),
                    row("dashboard.alerts-enabled", "false"),
                    row("fidelity.advantages-enabled", "false"),
                    row("fidelity.allow-multiple-scan", "false")));
            assertTrue(service.showEan());
            assertFalse(service.gestureEndorsementRequired());
            assertEquals(30, service.globalMaxDiscountPercent());
            assertEquals("Bonjour", service.customerOpenMessage());
            assertEquals("Fermé", service.customerClosedMessage());
            assertFalse(service.parkingPrintReceipt());
            assertTrue(service.paymentDegradedMode());
            assertFalse(service.discountEnabled());
            assertFalse(service.priceShowOriginalOnForce());
            assertEquals("Promo", service.ticketHeaderMessage());
            assertEquals("A bientot", service.ticketFooterMessage());
            assertFalse(service.customerQrEnabled());
            assertFalse(service.drawerOpenOnPayment());
            assertFalse(service.drawerOpenOnLogin());
            assertTrue(service.ean13CheckDigitEnabled());
            assertFalse(service.dashboardAlertsEnabled());
            assertFalse(service.fidelityAdvantagesEnabled());
            assertFalse(service.fidelityAllowMultipleScan());
        }
    }

    /** LC-10-01-09/11: both customer-display bases are hidden by default and shown once administered. */
    @Test
    void customerRestrictedBasesDefaultHiddenAndFollowTheirRows() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(service.customerEcoVoucherEligible());
            assertFalse(service.customerSocialCardEligible());
        }
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("customer.eco-voucher-eligible", "true"),
                    row("customer.social-card-eligible", "true")));
            assertTrue(administered.customerEcoVoucherEligible());
            assertTrue(administered.customerSocialCardEligible());
        }
    }

    /**
     * LC-09-01-09: the virtual keyboard layout answers AZERTY by default, an
     * administered value trimmed and upper-cased, and AZERTY again when the
     * administered value is blank (both arms of the blank guard).
     */
    @Test
    void keyboardLayoutDefaultsToAzertyAndFollowsItsRow() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("AZERTY", service.keyboardLayout());
        }
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("keyboard.layout", " qwertz ")));
            assertEquals("QWERTZ", administered.keyboardLayout());
        }
        PosSettingsService blank = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("keyboard.layout", "  ")));
            assertEquals("AZERTY", blank.keyboardLayout());
        }
    }

    /** LC-13-06-08/13: the customer's own language choice is off by default and offered once administered. */
    @Test
    void customerLanguageChoiceDefaultsOffAndFollowsItsRow() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(service.customerLanguageChoice());
        }
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("language.customer-choice", "true")));
            assertTrue(administered.customerLanguageChoice());
        }
    }

    /**
     * LC-08-06-01/04/05: the required NIF and the Portuguese check are off by
     * default and follow their rows; the scan pattern answers the catalogue's
     * default trimmed, and an administered one.
     */
    @Test
    void saleTaxIdControlsDefaultAndFollowTheirRows() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(service.taxIdRequired());
            assertFalse(service.taxIdPortugueseCheck());
            assertEquals("^(?:PT|NIF)(\\d{9})$", service.taxIdScanPattern());
        }
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("customer.tax-id-required", "true"),
                    row("customer.tax-id-pt-check", "true"),
                    row("customer.tax-id-scan-pattern", "  ^CAS(\\d{9})$ ")));
            assertTrue(administered.taxIdRequired());
            assertTrue(administered.taxIdPortugueseCheck());
            assertEquals("^CAS(\\d{9})$", administered.taxIdScanPattern());
        }
    }

    /**
     * LC-14-03-01: the three metrology items are empty by default and read
     * their administered rows, trimmed.
     */
    @Test
    void metrologyItemsDefaultEmptyAndFollowTheirRows() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("", service.metrologyBody());
            assertEquals("", service.metrologyCertificate());
            assertEquals("", service.metrologyScaleCrc());
        }
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("balance.metrology-body", " LNE "),
                    row("balance.metrology-certificate", "LNE-12345"),
                    row("balance.metrology-crc", " 8F3A")));
            assertEquals("LNE", administered.metrologyBody());
            assertEquals("LNE-12345", administered.metrologyCertificate());
            assertEquals("8F3A", administered.metrologyScaleCrc());
        }
    }

    /** LC-03-04-13: the customer-display balance is hidden by default and shown once administered. */
    @Test
    void customerDisplayBalanceDefaultHiddenAndFollowsItsRow() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(service.fidelityShowBalanceOnCustomerDisplay());
        }
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("fidelity.show-balance-customer", "true")));
            assertTrue(administered.fidelityShowBalanceOnCustomerDisplay());
        }
    }

    /** LC-07-17-02: defaults retain the upper-euro donation rule. */
    @Test
    void donationDefaults() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(100, service.donationRoundingStepCents());
            assertFalse(service.donationRoundingNearest());
        }
    }

    /** LC-07-17-02: a stored positive step and nearest rule override defaults. */
    @Test
    void donationAdministeredValues() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("donation.rounding-step-cents", "5"),
                    row("donation.rounding-nearest", "true")));
            assertEquals(5, service.donationRoundingStepCents());
            assertTrue(service.donationRoundingNearest());
        }
    }

    /** LC-07-17-02: zero cannot become a divisor when the catalogue is malformed. */
    @Test
    void donationZeroStepFallsBack() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("donation.rounding-step-cents", "0")));
            assertEquals(100, service.donationRoundingStepCents());
        }
    }

    /** LC-07-17-02: a negative step also retains the usable default. */
    @Test
    void donationNegativeStepFallsBack() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("donation.rounding-step-cents", "-5")));
            assertEquals(100, service.donationRoundingStepCents());
        }
    }

    /**
     * The remaining public accessors each project their stored row: the integer
     * accessors ({@code cashRoundingStepCents}, {@code moneticsDegradedForcedMinutes},
     * {@code creditDegradedAfterMinutes}), the boolean accessors
     * ({@code balanceCounterPrice}, {@code backupManualEndorsement},
     * {@code moneticsDegradedForcedEndorsement}, {@code creditAllowedInDegraded},
     * {@code printConditionalEnabled}, {@code printForceTicketGlc},
     * {@code printForceCardCredit}, {@code printForceCardSignature},
     * {@code printForceCardTna}, {@code ticketEmailEditable}) and the text
     * accessors ({@code backupMethodLabels}, {@code invoiceCustomerFields},
     * {@code ticketLineOrder}, {@code ticketEmailFormat}).
     */
    @Test
    void remainingAccessorsProjectStoredValues() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("cash.rounding-step-cents", "5"),
                    row("balance.counter-price", "false"),
                    row("backup.manual-endorsement", "false"),
                    row("payment.degraded-forced-endorsement", "false"),
                    row("payment.degraded-forced-minutes", "90"),
                    row("backup.method-labels", "20=CB"),
                    row("credit.allowed-in-degraded", "true"),
                    row("credit.degraded-after-minutes", "30"),
                    row("print.conditional-enabled", "true"),
                    row("print.force-ticket-glc", "false"),
                    row("print.force-card-credit", "false"),
                    row("print.force-card-signature", "false"),
                    row("print.force-card-tna", "false"),
                    row("invoice.customer-fields", "companyName*;email"),
                    row("ticket.line-order", "LABEL"),
                    row("ticket.email-format", "ATTACHMENT"),
                    row("ticket.email-editable", "false"),
                    row("fidelity.offline-message", "FIDELITE HS {carte}"),
                    row("fidelity.offline-message-no-card", "PRESENTEZ VOTRE CARTE")));
            assertEquals(5, service.cashRoundingStepCents());
            assertFalse(service.balanceCounterPrice());
            assertFalse(service.backupManualEndorsement());
            assertFalse(service.moneticsDegradedForcedEndorsement());
            assertEquals(90, service.moneticsDegradedForcedMinutes());
            assertEquals("20=CB", service.backupMethodLabels());
            assertTrue(service.creditAllowedInDegraded());
            assertEquals(30, service.creditDegradedAfterMinutes());
            assertTrue(service.printConditionalEnabled());
            assertFalse(service.printForceTicketGlc());
            assertFalse(service.printForceCardCredit());
            assertFalse(service.printForceCardSignature());
            assertFalse(service.printForceCardTna());
            assertEquals("companyName*;email", service.invoiceCustomerFields());
            assertEquals("LABEL", service.ticketLineOrder());
            assertEquals("ATTACHMENT", service.ticketEmailFormat());
            assertFalse(service.ticketEmailEditable());
            // BO-03-03-29 / BO-03-03-30: the two receipt messages of a silent
            // loyalty service — a holder is told their advantages will follow,
            // a non-holder is invited to present a card next time.
            assertEquals("FIDELITE HS {carte}", service.fidelityOfflineMessage());
            assertEquals("PRESENTEZ VOTRE CARTE", service.fidelityOfflineMessageNoCard());
        }
    }

    /**
     * {@code printForcedDocuments} splits the administered list, uppercasing and
     * trimming each entry and dropping blank ones (loop iterates then exits,
     * {@code !isEmpty} both arms, {@code isBlank} false arm), and returns an empty
     * list when the setting is blank ({@code isBlank} true arm, the {@code
     * List.of()} early return). A held {@code service} reference is used for each
     * call so both arms register their line and branch coverage.
     */
    @Test
    void printForcedDocumentsCoversBothArms() {
        PosSettingsService populated = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("print.forced-documents", " ticket ; ; carte ; retour ; avoir ; acompte ")));
            assertEquals(List.of("TICKET", "CARTE", "RETOUR", "AVOIR", "ACOMPTE"), populated.printForcedDocuments());
        }
        PosSettingsService blank = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("print.forced-documents", "   ")));
            assertTrue(blank.printForcedDocuments().isEmpty());
        }
    }

    /**
     * {@code cashMovementEndorsementThreshold} parses a well-formed stored
     * amount (parse-ok arm) and falls back to the catalog default on a corrupt
     * row (parse-fail arm).
     */
    @Test
    void cashMovementEndorsementThresholdParsesOrFallsBack() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.movement-endorsement-threshold", " 250.50 ")));
            assertEquals(0, new java.math.BigDecimal("250.50")
                    .compareTo(new PosSettingsService().cashMovementEndorsementThreshold()));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.movement-endorsement-threshold", "oops")));
            assertEquals(0, new java.math.BigDecimal("100.00")
                    .compareTo(new PosSettingsService().cashMovementEndorsementThreshold()));
        }
    }

    /**
     * {@code cashMovementReasons} splits the administered list, trimming each
     * entry and dropping blank ones ({@code !isEmpty} both arms), and returns an
     * empty list for a blank setting (isBlank true arm).
     */
    @Test
    void cashMovementReasonsSplitsAndDropsBlanks() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.movement-reasons", " Coffre ; ; Timbres ")));
            assertEquals(List.of("Coffre", "Timbres"), new PosSettingsService().cashMovementReasons());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.movement-reasons", "   ")));
            assertTrue(new PosSettingsService().cashMovementReasons().isEmpty());
        }
    }

    /**
     * {@code withdrawalDestinations} splits the administered list, trimming each
     * entry and dropping blank ones ({@code !isEmpty} both arms), and returns an
     * empty list for a blank setting (isBlank true arm) — the safe then being the
     * only destination the screen offers.
     */
    @Test
    void withdrawalDestinationsSplitsAndDropsBlanks() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.withdrawal-destinations",
                            " Caisse centrale ; ; Banque ")));
            assertEquals(List.of("Caisse centrale", "Banque"),
                    new PosSettingsService().withdrawalDestinations());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.withdrawal-destinations", "   ")));
            assertTrue(new PosSettingsService().withdrawalDestinations().isEmpty());
        }
    }

    /**
     * {@code returnReasons} splits the administered list, trimming each entry
     * and dropping blank ones ({@code !isEmpty} both arms, LC-05-05-01), and
     * returns an empty list for a blank setting (isBlank true arm).
     */
    @Test
    void returnReasonsSplitsAndDropsBlanks() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.reasons", " Erreur de caisse ; ; Article périmé ")));
            assertEquals(List.of("Erreur de caisse", "Article périmé"),
                    new PosSettingsService().returnReasons());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.reasons", "   ")));
            assertTrue(new PosSettingsService().returnReasons().isEmpty());
        }
    }

    /**
     * {@code financialMovementSources} and {@code financialMovementDestinations}
     * hand back the administered raw lists verbatim (LC-12-06-02).
     */
    @Test
    void financialMovementListsReturnTheAdministeredRawValues() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("movement.financial-sources", "CASH:Espèces"),
                    row("movement.financial-destinations", "CREDIT:Crédit client")));
            PosSettingsService service = new PosSettingsService();
            assertEquals("CASH:Espèces", service.financialMovementSources());
            assertEquals("CREDIT:Crédit client", service.financialMovementDestinations());
        }
    }

    /**
     * {@code financialMovementPrint} reads the administered flag in both arms
     * (LC-12-06-03).
     */
    @Test
    void financialMovementPrintCoversBothArms() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("movement.financial-print", "true")));
            assertTrue(new PosSettingsService().financialMovementPrint());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("movement.financial-print", "false")));
            assertFalse(new PosSettingsService().financialMovementPrint());
        }
    }

    /**
     * {@code changeOrderPrint} reads the administered flag in both arms
     * (LC-12-05-08).
     */
    @Test
    void changeOrderPrintCoversBothArms() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("change-order.print", "true")));
            assertTrue(new PosSettingsService().changeOrderPrint());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("change-order.print", "false")));
            assertFalse(new PosSettingsService().changeOrderPrint());
        }
    }

    /**
     * {@code changeOrderRollValues} hands back the administered raw
     * correspondence verbatim (LC-12-05-06/07).
     */
    @Test
    void changeOrderRollValuesReturnsTheAdministeredRawValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("change-order.roll-values", "0.20:8.00;0.01:0.50")));
            assertEquals("0.20:8.00;0.01:0.50", new PosSettingsService().changeOrderRollValues());
        }
    }

    /**
     * {@code printReturnReason} reads the administered flag in both arms
     * (LC-05-05-03): the stored override wins over the catalog default.
     */
    @Test
    void printReturnReasonReadsAdministeredFlag() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.print-reason", "false")));
            assertFalse(new PosSettingsService().printReturnReason());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.print-reason", "true")));
            assertTrue(new PosSettingsService().printReturnReason());
        }
    }

    /**
     * {@code returnForeignStoreAccepted} reads the administered flag in both
     * arms, and falls back to the catalog default (refused) with no row
     * (LC-05-02-07).
     */
    @Test
    void returnForeignStoreAcceptedReadsAdministeredFlag() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.foreign-store-accepted", "true")));
            assertTrue(new PosSettingsService().returnForeignStoreAccepted());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.foreign-store-accepted", "false")));
            assertFalse(new PosSettingsService().returnForeignStoreAccepted());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(new PosSettingsService().returnForeignStoreAccepted());
        }
    }

    /**
     * {@code returnTypes} parses a label alone as an IN-SALE type and a label
     * followed by the TICKET marker as a dedicated-ticket one, keeping the
     * administered order (LC-05-04-01).
     */
    @Test
    void returnTypesParsesLabelsAndMarkers() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.types", "Contenant;Defectueux>TICKET")));
            List<PosSettingsService.ReturnType> types = new PosSettingsService().returnTypes();
            assertEquals(2, types.size());
            assertEquals("Contenant", types.get(0).label());
            assertFalse(types.get(0).dedicatedTicket());
            assertEquals("Defectueux", types.get(1).label());
            assertTrue(types.get(1).dedicatedTicket());
        }
    }

    /**
     * The TICKET marker is read without regard to case, and an unknown marker
     * reads as the in-sale gesture: a typo offers a key that works
     * (LC-05-04-01).
     */
    @Test
    void returnTypesToleratesTheMarkerCaseAndUnknownMarkers() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.types", "A> ticket ;B>AUTRE")));
            List<PosSettingsService.ReturnType> types = new PosSettingsService().returnTypes();
            assertTrue(types.get(0).dedicatedTicket());
            assertFalse(types.get(1).dedicatedTicket());
        }
    }

    /**
     * BO-03-07-03/-06/-11: a ready-made discount key is a label, a kind, a
     * value and an optional cumulation permission. Both kinds are read, the
     * comma decimal is accepted, and the permission is read whatever its case.
     */
    @Test
    void discountPresetsReadsBothKindsAndTheCumulPermission() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("discount.presets",
                    "-20%>PERCENT:20 ; 2 euros>amount:2,5 , cumul ")));
            List<PosSettingsService.DiscountPreset> presets =
                    new PosSettingsService().discountPresets();
            assertEquals(2, presets.size());
            assertEquals("-20%", presets.get(0).label());
            assertTrue(presets.get(0).percent());
            assertEquals(new java.math.BigDecimal("20"), presets.get(0).value());
            assertFalse(presets.get(0).cumulative());
            assertEquals("2 euros", presets.get(1).label());
            assertFalse(presets.get(1).percent());
            assertEquals(new java.math.BigDecimal("2.5"), presets.get(1).value());
            assertTrue(presets.get(1).cumulative());
        }
    }

    /**
     * Every malformed entry is DROPPED rather than guessed: no marker, no label,
     * an unknown kind, no value, an unparsable value, a value at zero and a
     * negative one. A key that takes off a figure nobody administered is worse
     * than a key that is not offered.
     */
    @Test
    void discountPresetsDropsEveryMalformedEntry() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("discount.presets",
                    "sans marqueur;>PERCENT:10; A>VOLUME:10; B>PERCENT; C>PERCENT:abc;"
                    + " D>PERCENT:0; E>AMOUNT:-3;  ;F>PERCENT:5")));
            List<PosSettingsService.DiscountPreset> presets =
                    new PosSettingsService().discountPresets();
            assertEquals(1, presets.size());
            assertEquals("F", presets.get(0).label());
        }
    }

    /**
     * An empty setting offers no key at all, and {@code discountPreset} answers
     * null for a null label, a blank one and one the list does not name.
     */
    @Test
    void discountPresetResolvesALabelOrAnswersNull() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(new PosSettingsService().discountPresets().isEmpty());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("discount.presets", "-20%>PERCENT:20")));
            PosSettingsService service = new PosSettingsService();
            assertEquals("-20%", service.discountPreset(" -20% ").label());
            assertNull(service.discountPreset(null));
            assertNull(service.discountPreset("  "));
            assertNull(service.discountPreset("-30%"));
        }
    }

    /**
     * BO-03-07-11 and -12: both cumulation keys default to TRUE — the register's
     * behaviour before they existed — and are read as administered.
     */
    @Test
    void theTwoCumulationKeysDefaultToTrue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            PosSettingsService service = new PosSettingsService();
            assertTrue(service.discountCumulativeOnDiscounted());
            assertTrue(service.discountGlobalOnDiscounted());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("discount.cumulative-on-discounted", "false"),
                    row("discount.global-on-discounted", "false")));
            PosSettingsService service = new PosSettingsService();
            assertFalse(service.discountCumulativeOnDiscounted());
            assertFalse(service.discountGlobalOnDiscounted());
        }
    }

    /**
     * BO-03-13-06/-08/-10: the three OPTIONS of a return type are read after the
     * marker, separated by commas and whatever the case, beside the historical
     * TICKET one. A type that names none carries none.
     */
    @Test
    void returnTypesReadsTheThreeOptions() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("return.types",
                    "Echange>NOENDORSE;Sans ticket>ticket, noorigin ,PRICE;Simple")));
            List<PosSettingsService.ReturnType> types = new PosSettingsService().returnTypes();
            assertEquals(3, types.size());

            assertEquals("Echange", types.get(0).label());
            assertFalse(types.get(0).dedicatedTicket());
            assertTrue(types.get(0).endorsementExempt());
            assertFalse(types.get(0).originOptional());
            assertFalse(types.get(0).priceConfirmed());

            assertEquals("Sans ticket", types.get(1).label());
            assertTrue(types.get(1).dedicatedTicket());
            assertFalse(types.get(1).endorsementExempt());
            assertTrue(types.get(1).originOptional());
            assertTrue(types.get(1).priceConfirmed());

            assertEquals("Simple", types.get(2).label());
            assertFalse(types.get(2).dedicatedTicket());
            assertFalse(types.get(2).endorsementExempt());
            assertFalse(types.get(2).originOptional());
            assertFalse(types.get(2).priceConfirmed());
        }
    }

    /**
     * Blank entries and entries with no label before the marker are dropped
     * (LC-05-04-01).
     */
    @Test
    void returnTypesDropsBlankEntries() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.types", "Contenant;;  ; >TICKET")));
            List<PosSettingsService.ReturnType> types = new PosSettingsService().returnTypes();
            assertEquals(1, types.size());
            assertEquals("Contenant", types.get(0).label());
        }
    }

    /**
     * An empty setting offers no type at all: only the two base gestures remain
     * (LC-05-04-01, blank arm).
     */
    @Test
    void returnTypesIsEmptyWhenNoneAreAdministered() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.types", "   ")));
            assertTrue(new PosSettingsService().returnTypes().isEmpty());
        }
    }

    /**
     * {@code returnInSaleAllowed} reads the administered flag in both arms, and
     * falls back to the catalog default (allowed) with no row (LC-05-01-01).
     */
    @Test
    void returnInSaleAllowedReadsAdministeredFlag() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.in-sale-allowed", "false")));
            assertFalse(new PosSettingsService().returnInSaleAllowed());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.in-sale-allowed", "true")));
            assertTrue(new PosSettingsService().returnInSaleAllowed());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(new PosSettingsService().returnInSaleAllowed());
        }
    }

    /**
     * {@code returnDedicatedRegister} reads the administered flag in both arms,
     * and falls back to the catalog default (not dedicated) with no row
     * (LC-05-01-03).
     */
    @Test
    void returnDedicatedRegisterReadsAdministeredFlag() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.dedicated-register", "true")));
            assertTrue(new PosSettingsService().returnDedicatedRegister());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.dedicated-register", "false")));
            assertFalse(new PosSettingsService().returnDedicatedRegister());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(new PosSettingsService().returnDedicatedRegister());
        }
    }

    /**
     * {@code returnWithoutOriginAllowed} reads the administered flag in both
     * arms, and falls back to the catalog default (allowed) with no row
     * (LC-05-02-09/14).
     */
    @Test
    void returnWithoutOriginAllowedReadsAdministeredFlag() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.without-origin-allowed", "false")));
            assertFalse(new PosSettingsService().returnWithoutOriginAllowed());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.without-origin-allowed", "true")));
            assertTrue(new PosSettingsService().returnWithoutOriginAllowed());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(new PosSettingsService().returnWithoutOriginAllowed());
        }
    }

    /**
     * {@code defaultTare} reads the administered default tare, and returns zero
     * from the catalog default when no row overrides it (LC-14-01-03).
     */
    @Test
    void defaultTareReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("balance.default-tare", "0.010")));
            assertEquals(0, new java.math.BigDecimal("0.010")
                    .compareTo(new PosSettingsService().defaultTare()));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(0, java.math.BigDecimal.ZERO
                    .compareTo(new PosSettingsService().defaultTare()));
        }
    }

    /**
     * {@code tareContainers} parses the administered {@code CODE:LIBELLÉ:POIDS}
     * list, keeping well-formed rows and dropping the malformed ones: a wrong
     * field count, a blank code, a blank label, a negative weight and an
     * unreadable weight are each dropped, while the valid rows survive in order
     * (LC-14-01-04, both arms of every row guard).
     */
    @Test
    void tareContainersParsesAndDropsMalformedRows() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("balance.tare-containers",
                    "SACPAP:Sac kraft:0.010; BQS : Barquette S : 0,035 ;"
                            + "BADFIELDS:only-two;:Sans code:0.01;NOLABEL::0.02;"
                            + "NEG:Negatif:-0.01;NAN:Illisible:lourd")));
            List<PosSettingsService.TareContainer> containers =
                    new PosSettingsService().tareContainers();
            assertEquals(2, containers.size());
            assertEquals("SACPAP", containers.get(0).code());
            assertEquals("Sac kraft", containers.get(0).label());
            assertEquals(0, new java.math.BigDecimal("0.010").compareTo(containers.get(0).tareKg()));
            assertEquals("BQS", containers.get(1).code());
            assertEquals("Barquette S", containers.get(1).label());
            assertEquals(0, new java.math.BigDecimal("0.035").compareTo(containers.get(1).tareKg()));
        }
    }

    /**
     * {@code tareContainers} returns an empty list for a blank setting (isBlank
     * true arm) — no container is then offered.
     */
    @Test
    void tareContainersEmptyForBlankSetting() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("balance.tare-containers", "   ")));
            assertTrue(new PosSettingsService().tareContainers().isEmpty());
        }
    }

    /**
     * {@code touchGroupsPerPage} floors a value below one back to the catalog
     * default (below-one arm) and keeps a positive value verbatim (positive
     * arm).
     */
    @Test
    void touchGroupsPerPageFloorsBelowOne() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("touch.groups-per-page", "0")));
            assertEquals(12, new PosSettingsService().touchGroupsPerPage());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("touch.groups-per-page", "20")));
            assertEquals(20, new PosSettingsService().touchGroupsPerPage());
        }
    }

    /**
     * {@code touchDisplayOrder} normalizes the administered value: CUSTOM (first
     * disjunct true), VOLUME (second disjunct true, case-insensitively) and any
     * other value including the default (both disjuncts false) folding to ALPHA.
     */
    @Test
    void touchDisplayOrderNormalizes() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("touch.display-order", "CUSTOM")));
            assertEquals("CUSTOM", new PosSettingsService().touchDisplayOrder());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("touch.display-order", " volume ")));
            assertEquals("VOLUME", new PosSettingsService().touchDisplayOrder());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("touch.display-order", "weird")));
            assertEquals("ALPHA", new PosSettingsService().touchDisplayOrder());
        }
    }

    /**
     * Builds a service wired with an echelon engine and a node PDV number.
     *
     * @param engine the echelon engine, or null
     * @param pdvNumber the node PDV number Optional, or null when unset
     * @return the wired service
     */
    private PosSettingsService wired(EchelonSettingService engine, Optional<String> pdvNumber) {
        PosSettingsService service = new PosSettingsService();
        service.echelonSettings = engine;
        service.nodePdvNumber = pdvNumber;
        return service;
    }

    /**
     * {@code value} returns the echelon-inherited value when no local override
     * exists (inherited-present arm, all three legs of the load guard false),
     * and the inherited map is resolved once and cached across reads.
     */
    @Test
    void valueUsesInheritedWhenNoLocalOverride() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        when(engine.resolveForPdv("12345")).thenReturn(Map.of("gesture.endorsement-required", "false"));
        PosSettingsService service = wired(engine, Optional.of("12345"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(service.gestureEndorsementRequired());
            assertFalse(service.gestureEndorsementRequired());
            verify(engine, times(1)).resolveForPdv("12345");
        }
    }

    /**
     * A local override wins over the echelon-inherited value and never
     * consults the echelon engine — the "surcharge locale survit" guarantee:
     * the stored row is read before the inheritance layer.
     */
    @Test
    void localOverrideWinsOverInheritance() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        when(engine.resolveForPdv("12345")).thenReturn(Map.of("gesture.endorsement-required", "false"));
        PosSettingsService service = wired(engine, Optional.of("12345"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("gesture.endorsement-required", "true")));
            assertTrue(service.gestureEndorsementRequired());
            verify(engine, never()).resolveForPdv(org.mockito.ArgumentMatchers.anyString());
        }
    }

    /**
     * The inheritance layer is skipped when this node carries no PDV number
     * ({@code nodePdvNumber} null leg): the engine is never consulted and the
     * catalog default applies.
     */
    @Test
    void inheritanceSkippedWhenNoPdvNumber() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        PosSettingsService service = wired(engine, null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.gestureEndorsementRequired());
            verify(engine, never()).resolveForPdv(org.mockito.ArgumentMatchers.anyString());
        }
    }

    /**
     * The inheritance layer is skipped when the PDV number is present but blank
     * ({@code isEmpty} leg): the engine is never consulted.
     */
    @Test
    void inheritanceSkippedWhenPdvNumberEmpty() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        PosSettingsService service = wired(engine, Optional.empty());
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.gestureEndorsementRequired());
            verify(engine, never()).resolveForPdv(org.mockito.ArgumentMatchers.anyString());
        }
    }

    /**
     * A failed echelon resolution falls back to the catalog default (the
     * try/catch exception arm) rather than propagating, so a central hiccup
     * never stops a node reading its parameters.
     */
    @Test
    void inheritanceSurvivesResolveFailure() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        when(engine.resolveForPdv("12345")).thenThrow(new RuntimeException("central down"));
        PosSettingsService service = wired(engine, Optional.of("12345"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.gestureEndorsementRequired());
        }
    }

    /**
     * {@code administeredValues} publishes the RESOLVED effective values a store
     * exports to its registers (route A): the echelon-inherited keys overlaid by
     * the local overrides, the local winning on a shared key (all three legs of
     * the guard false, echelon-present arm). It resolves fresh on each call so a
     * crossed effect date is picked up (engine consulted every time), while the
     * local table is cached ({@code cache == null} then reuse arm).
     */
    @Test
    void administeredValuesMergesLocalOverEchelonResolvingFresh() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        when(engine.resolveForPdv("12345")).thenReturn(Map.of(
                "shared", "echelon", "ensOnly", "ev"));
        PosSettingsService service = wired(engine, Optional.of("12345"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("shared", "local"), row("localOnly", "lv")));
            Map<String, String> first = service.administeredValues();
            Map<String, String> second = service.administeredValues();
            assertEquals("local", first.get("shared"));
            assertEquals("ev", first.get("ensOnly"));
            assertEquals("lv", first.get("localOnly"));
            assertEquals(3, first.size());
            assertEquals(first, second);
            ms.verify(PanacheEntityBase::listAll, times(1));
            verify(engine, times(2)).resolveForPdv("12345");
        }
    }

    /**
     * {@code administeredValues} skips the echelon layer when no engine is wired
     * (echelonSettings-null leg) and returns the local overrides alone.
     */
    @Test
    void administeredValuesSkipsEchelonWhenEngineNull() {
        PosSettingsService service = wired(null, Optional.of("12345"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("k", "v")));
            Map<String, String> resolved = service.administeredValues();
            assertEquals(Map.of("k", "v"), resolved);
        }
    }

    /**
     * {@code administeredValues} skips the echelon layer when this node carries
     * no PDV number (nodePdvNumber-null leg): the engine is never consulted.
     */
    @Test
    void administeredValuesSkipsEchelonWhenNoPdvNumber() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        PosSettingsService service = wired(engine, null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("k", "v")));
            Map<String, String> resolved = service.administeredValues();
            assertEquals(Map.of("k", "v"), resolved);
            verify(engine, never()).resolveForPdv(org.mockito.ArgumentMatchers.anyString());
        }
    }

    /**
     * {@code administeredValues} skips the echelon layer when the PDV number is
     * present but blank ({@code isEmpty} leg): the engine is never consulted.
     */
    @Test
    void administeredValuesSkipsEchelonWhenPdvEmpty() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        PosSettingsService service = wired(engine, Optional.empty());
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("k", "v")));
            Map<String, String> resolved = service.administeredValues();
            assertEquals(Map.of("k", "v"), resolved);
            verify(engine, never()).resolveForPdv(org.mockito.ArgumentMatchers.anyString());
        }
    }

    /**
     * {@code badgeScanEnabled} reads its stored boolean value (BO-10-02-29/30).
     */
    @Test
    void badgeScanEnabledReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("auth.badge-scan-enabled", "false")));
            assertFalse(service.badgeScanEnabled());
        }
    }

    /**
     * {@code defaultOpeningFloat} reads its stored value (BO-03-02-41).
     */
    @Test
    void defaultOpeningFloatReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("cash.default-opening-float", "200.00")));
            assertEquals("200.00", service.defaultOpeningFloat());
        }
    }

    /**
     * {@code drawerOpenOnSessionClose} reads its stored boolean value (BO-10-02-26).
     */
    @Test
    void drawerOpenOnSessionCloseReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("drawer.open-on-session-close", "false")));
            assertFalse(service.drawerOpenOnSessionClose());
        }
    }

    /**
     * {@code vatBreakdownEnabled} reads its stored boolean value (BO-10-06-04).
     */
    @Test
    void vatBreakdownEnabledReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("ticket.vat-breakdown-enabled", "false")));
            assertFalse(service.vatBreakdownEnabled());
        }
    }

    /**
     * {@code fidelityExternalEnabled} reads its stored boolean value (BO-10-03-07).
     */
    @Test
    void fidelityExternalEnabledReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("fidelity.external-enabled", "false")));
            assertFalse(service.fidelityExternalEnabled());
        }
    }

    /**
     * {@code cashMovementTenders} parses the semicolon list, trimming and
     * dropping blank entries (BO-03-02-20, non-blank arm and the blank-part skip).
     */
    @Test
    void cashMovementTendersParsesSemicolonList() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.movement-tenders", "ESPECES; CHEQUE ; ;TR")));
            assertEquals(List.of("ESPECES", "CHEQUE", "TR"), service.cashMovementTenders());
        }
    }

    /**
     * {@code cashMovementTenders} yields an empty list on the blank default
     * (BO-03-02-20, blank arm — a movement then always concerns the cash).
     */
    @Test
    void cashMovementTendersEmptyWhenBlank() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.cashMovementTenders().isEmpty());
        }
    }

    /**
     * The remaining boolean accessors each project their stored row: session,
     * customer, period, fidelity and drawer/transfer/abandon flags not exercised
     * above, driven with values opposite to their catalog default so a wrong key
     * mapping would surface.
     */
    @Test
    void remainingBooleanAccessorsProjectStoredValues() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("auth.password-required-on-open", "false"),
                    row("auth.password-required-on-close", "true"),
                    row("session.close-endorsement-on-pending", "false"),
                    row("session.print-open-receipt", "true"),
                    row("session.print-close-receipt", "true"),
                    row("customer.volatile-creation-enabled", "false"),
                    row("customer.account-creation-blocked", "false"),
                    row("customer.show-details", "false"),
                    row("customer.update-enabled", "true"),
                    row("customer.show-discount", "false"),
                    row("customer.partial-credit-allowed", "false"),
                    row("customer.invoice-export-due-date", "false"),
                    row("customer.invoice-export-ecotax", "true"),
                    row("period.auto-close-enabled", "true"),
                    row("fidelity.show-holder-name", "false"),
                    row("fidelity.jv-promotion-enabled", "false"),
                    row("drawer.withdrawal-print", "false"),
                    row("drawer.transfer-print", "false"),
                    row("abandon.print-detail", "false")));
            assertFalse(service.passwordRequiredOnOpen());
            assertTrue(service.passwordRequiredOnClose());
            assertFalse(service.closeEndorsementOnPending());
            assertTrue(service.printOpenReceipt());
            assertTrue(service.printCloseReceipt());
            assertFalse(service.volatileCustomerCreationEnabled());
            assertFalse(service.accountCustomerCreationBlocked());
            assertFalse(service.showCustomerDetails());
            assertTrue(service.customerUpdateEnabled());
            assertFalse(service.showCustomerDiscount());
            assertFalse(service.partialCreditAllowed());
            assertFalse(service.invoiceExportDueDate());
            assertTrue(service.invoiceExportEcoTax());
            assertTrue(service.periodAutoCloseEnabled());
            assertFalse(service.fidelityShowHolderName());
            assertFalse(service.fidelityJvPromotionEnabled());
            assertFalse(service.drawerWithdrawalPrint());
            assertFalse(service.drawerTransferPrint());
            assertFalse(service.abandonPrintDetail());
        }
    }

    /**
     * The remaining text accessors each project their stored row: customer,
     * period, fidelity, invoice, GS1, tender, abandon and drawer text values not
     * exercised above.
     */
    @Test
    void remainingTextAccessorsProjectStoredValues() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("customer.discount-segment", "GOLD"),
                    row("customer.offline-message", "HORS LIGNE"),
                    row("customer.over-limit-message", "PLAFOND {plafond}"),
                    row("customer.default-tax-id", "111111111"),
                    row("customer.default-tax-id-label", "CLIENT"),
                    row("customer.foreign-tax-id-countries", "ES,FR"),
                    row("period.end-time", "22:00"),
                    row("customer.account-pattern", "^CC\\d{8}$"),
                    row("fidelity.card-pattern", "^299\\d{10}$"),
                    row("fidelity.url", "http://fid"),
                    row("fidelity.user", "caisse01"),
                    row("training.theme", "sombre"),
                    row("invoice.document-types", "FACTURE;BON_LIVRAISON"),
                    row("invoice.document-output", "FACTURE:A4"),
                    row("invoice.auto-print", "CREDIT:FACTURE"),
                    row("gs1.expiry-alert", "INFO"),
                    row("tender.restricted", "TR:MEAL_VOUCHER_ELIGIBLE:Titres"),
                    row("abandon.reasons", "Erreur;Client parti"),
                    row("drawer.withdrawal-methods", "CASH:Especes"),
                    row("drawer.transfer-methods", "CHEQUE:Cheques"),
                    row("abandon.partial-payment", "BLOCK"),
                    row("abandon.print", "ALWAYS"),
                    row("gs1.coupon-expiry-alert", "INFO"),
                    row("gs1.gdti-coupon-types", "9526000:CADEAU"),
                    row("gs1.gcn-coupon-type", "COUPON")));
            assertEquals("GOLD", service.customerDiscountSegment());
            assertEquals("HORS LIGNE", service.customerOfflineMessage());
            assertEquals("PLAFOND {plafond}", service.customerOverLimitMessage());
            assertEquals("111111111", service.defaultTaxId());
            assertEquals("CLIENT", service.defaultTaxIdLabel());
            assertEquals("ES,FR", service.foreignTaxIdCountries());
            assertEquals("22:00", service.periodEndTime());
            assertEquals("^CC\\d{8}$", service.customerAccountPattern());
            assertEquals("^299\\d{10}$", service.fidelityCardPattern());
            assertEquals("http://fid", service.fidelityUrl());
            assertEquals("caisse01", service.fidelityUser());
            assertEquals("sombre", service.trainingTheme());
            assertEquals("FACTURE;BON_LIVRAISON", service.invoiceDocumentTypes());
            assertEquals("FACTURE:A4", service.invoiceDocumentOutput());
            assertEquals("CREDIT:FACTURE", service.invoiceAutoPrint());
            assertEquals("INFO", service.gs1ExpiryAlert());
            assertEquals("TR:MEAL_VOUCHER_ELIGIBLE:Titres", service.restrictedTenders());
            assertEquals("Erreur;Client parti", service.abandonReasons());
            assertEquals("CASH:Especes", service.drawerWithdrawalMethods());
            assertEquals("CHEQUE:Cheques", service.drawerTransferMethods());
            assertEquals("BLOCK", service.abandonPartialPayment());
            assertEquals("ALWAYS", service.abandonPrint());
            assertEquals("INFO", service.gs1CouponExpiryAlert());
            assertEquals("9526000:CADEAU", service.gs1GiftCouponTypes());
            assertEquals("COUPON", service.gs1CouponType());
        }
    }

    /**
     * The remaining integer accessors each project their stored row
     * ({@code periodCloseLeadMinutes}, {@code invoiceSlipLines},
     * {@code gs1ExpiryWarnDays}).
     */
    @Test
    void remainingIntegerAccessorsProjectStoredValues() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("period.close-lead-minutes", "8"),
                    row("invoice.slip-lines", "40"),
                    row("gs1.expiry-warn-days", "3")));
            assertEquals(8, service.periodCloseLeadMinutes());
            assertEquals(40, service.invoiceSlipLines());
            assertEquals(3, service.gs1ExpiryWarnDays());
        }
    }

    /**
     * The remaining decimal accessors each project their stored row
     * ({@code lineMaxDiscountAmount}, {@code globalMaxDiscountAmount}).
     */
    @Test
    void remainingDecimalAccessorsProjectStoredValues() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("discount.line-max-amount", "10.00"),
                    row("discount.global-max-amount", "25.50")));
            assertEquals(0, new java.math.BigDecimal("10.00").compareTo(service.lineMaxDiscountAmount()));
            assertEquals(0, new java.math.BigDecimal("25.50").compareTo(service.globalMaxDiscountAmount()));
        }
    }

    /**
     * {@code customerCreditTender} trims a stored non-blank key (else arm) and
     * folds a blank stored value back to {@code CREDIT} (isBlank true arm).
     */
    @Test
    void customerCreditTenderTrimsOrFoldsToDefault() {
        PosSettingsService trimmed = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("customer.credit-tender", "  ACOMPTE  ")));
            assertEquals("ACOMPTE", trimmed.customerCreditTender());
        }
        PosSettingsService blank = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("customer.credit-tender", "   ")));
            assertEquals("CREDIT", blank.customerCreditTender());
        }
    }

    /**
     * {@code administeredValues} with NO echelon engine wired resolves nothing
     * from the echelons and publishes the local overrides alone — the first leg
     * of the three-legged guard.
     */
    @Test
    void administeredValuesWithoutEchelonEngineIsLocalOnly() {
        PosSettingsService service = new PosSettingsService();
        service.echelonSettings = null;
        service.nodePdvNumber = Optional.of("01234");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("display.show-ean", "true")));
            assertEquals(Map.of("display.show-ean", "true"), service.administeredValues("POS07"));
        }
    }

    /**
     * {@code administeredValues} on a node with NO point-of-vente number
     * resolves nothing either — the second and third legs of the same guard,
     * empty Optional and all.
     */
    @Test
    void administeredValuesWithoutPdvNumberIsLocalOnly() {
        EchelonSettingService echelons = mock(EchelonSettingService.class);
        PosSettingsService service = new PosSettingsService();
        service.echelonSettings = echelons;
        service.nodePdvNumber = Optional.empty();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.administeredValues("POS07").isEmpty());
        }
        verifyNoInteractions(echelons);
    }

    /**
     * A NULL terminal resolves for the point of vente alone: what an older
     * register that names itself in no header receives, unchanged.
     */
    @Test
    void administeredValuesWithoutTerminalResolvesForThePdv() {
        EchelonSettingService echelons = mock(EchelonSettingService.class);
        when(echelons.resolveForPdv("01234")).thenReturn(Map.of("display.show-ean", "false"));
        PosSettingsService service = new PosSettingsService();
        service.echelonSettings = echelons;
        service.nodePdvNumber = Optional.of("01234");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(Map.of("display.show-ean", "false"), service.administeredValues(null));
        }
        verify(echelons).resolveForPdv("01234");
    }

    /**
     * A BLANK terminal is the same case as a null one — the second leg of
     * {@code terminalId == null || terminalId.isBlank()}.
     */
    @Test
    void administeredValuesWithABlankTerminalResolvesForThePdv() {
        EchelonSettingService echelons = mock(EchelonSettingService.class);
        when(echelons.resolveForPdv("01234")).thenReturn(Map.of("display.show-ean", "false"));
        PosSettingsService service = new PosSettingsService();
        service.echelonSettings = echelons;
        service.nodePdvNumber = Optional.of("01234");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(Map.of("display.show-ean", "false"), service.administeredValues("   "));
        }
        verify(echelons).resolveForPdv("01234");
    }

    /**
     * A NAMED terminal walks the full five-level chain, and the identifier is
     * trimmed before it is resolved.
     */
    @Test
    void administeredValuesWithATerminalResolvesForThatTerminal() {
        EchelonSettingService echelons = mock(EchelonSettingService.class);
        when(echelons.resolveForTerminal("01234", "POS07"))
                .thenReturn(Map.of("display.show-ean", "true"));
        PosSettingsService service = new PosSettingsService();
        service.echelonSettings = echelons;
        service.nodePdvNumber = Optional.of("01234");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(Map.of("display.show-ean", "true"),
                    service.administeredValues("  POS07  "));
        }
        verify(echelons).resolveForTerminal("01234", "POS07");
    }

    /**
     * A LOCAL override still wins over the terminal resolution: the node's own
     * row is the last layer, exactly as it is over the PDV resolution.
     */
    @Test
    void administeredValuesLetTheLocalOverrideBeatTheTerminalResolution() {
        EchelonSettingService echelons = mock(EchelonSettingService.class);
        when(echelons.resolveForTerminal("01234", "POS07"))
                .thenReturn(Map.of("display.show-ean", "false"));
        PosSettingsService service = new PosSettingsService();
        service.echelonSettings = echelons;
        service.nodePdvNumber = Optional.of("01234");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("display.show-ean", "true")));
            assertEquals(Map.of("display.show-ean", "true"), service.administeredValues("POS07"));
        }
    }

    /**
     * The no-argument form keeps its meaning — the point of vente of this node —
     * and never consults a terminal.
     */
    @Test
    void administeredValuesWithoutArgumentStillResolvesForThePdv() {
        EchelonSettingService echelons = mock(EchelonSettingService.class);
        when(echelons.resolveForPdv("01234")).thenReturn(Map.of("display.show-ean", "false"));
        PosSettingsService service = new PosSettingsService();
        service.echelonSettings = echelons;
        service.nodePdvNumber = Optional.of("01234");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(Map.of("display.show-ean", "false"), service.administeredValues());
        }
        verify(echelons, never()).resolveForTerminal(anyString(), anyString());
    }

    /**
     * On a REGISTER the merged layer leaves behaviour unchanged: the register
     * pulls no echelon domain, so with the REAL engine and a {@code
     * pos.pdv.number} set the PDV is unknown locally, the inheritance finds
     * nothing, a key resolves to its catalog default and the administered map
     * holds only the local overrides — exactly the pre-merge register result.
     */
    @Test
    void registerWithoutEchelonRowsKeepsLocalThenDefaultResolution() {
        PosSettingsService service = wired(new EchelonSettingService(), Optional.of("12345"));
        PanacheQuery<Object> unknownPdv = query(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("display.show-ean", "false")));
            ms.when(() -> PanacheEntityBase.find("pdvNumber", "12345")).thenReturn(unknownPdv);
            assertTrue(service.gestureEndorsementRequired());
            assertEquals(Map.of("display.show-ean", "false"), service.administeredValues());
        }
    }

    /**
     * {@code floatMode} returns the administered mode, and {@code floatingFloat}
     * reads its {@code FLOATING} prefix — true for a floating mode (startsWith
     * true arm).
     */
    @Test
    void floatModeAndFloatingFloatOnAFloatingMode() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("cash.float-mode", "FLOATING_WEEK_MONTH")));
            assertEquals("FLOATING_WEEK_MONTH", service.floatMode());
            assertTrue(service.floatingFloat());
        }
    }

    /**
     * {@code floatingFloat} is false on the fixed mode (startsWith false arm),
     * the catalog default when no row overrides it.
     */
    @Test
    void floatingFloatIsFalseOnFixedMode() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("FIXED", service.floatMode());
            assertFalse(service.floatingFloat());
        }
    }

    /**
     * {@code financialClosedWeekdays} parses the valid names (add arm), drops a
     * blank entry (continue arm) and an unparsable one (catch arm), case being
     * folded to upper.
     */
    @Test
    void financialClosedWeekdaysParsesValidAndDropsBlankAndBad() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("financial.closed-weekdays", "sunday; ;WEDNESDAY;notaday")));
            Set<DayOfWeek> days = service.financialClosedWeekdays();
            assertEquals(2, days.size());
            assertTrue(days.contains(DayOfWeek.SUNDAY));
            assertTrue(days.contains(DayOfWeek.WEDNESDAY));
        }
    }

    /**
     * {@code financialClosedWeekdays} yields an empty set on the blank default
     * (every token blank), never null.
     */
    @Test
    void financialClosedWeekdaysEmptyOnBlankDefault() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.financialClosedWeekdays().isEmpty());
        }
    }

    /**
     * {@code financialClosedDates} parses the valid dates (add arm), drops a
     * blank entry (continue arm) and an unparsable one (catch arm).
     */
    @Test
    void financialClosedDatesParsesValidAndDropsBlankAndBad() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("financial.closed-dates", "2026-12-25; ;2026-07-14;nope")));
            Set<LocalDate> dates = service.financialClosedDates();
            assertEquals(2, dates.size());
            assertTrue(dates.contains(LocalDate.of(2026, 12, 25)));
            assertTrue(dates.contains(LocalDate.of(2026, 7, 14)));
        }
    }

    /**
     * {@code financialClosedDates} yields an empty set on the blank default,
     * never null.
     */
    @Test
    void financialClosedDatesEmptyOnBlankDefault() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.financialClosedDates().isEmpty());
        }
    }

    /**
     * {@code financialHourlySlotMinutes} returns the administered width, and
     * {@code financialNomenclatureLevel} the administered level.
     */
    @Test
    void financialSlotAndLevelReturnAdministeredIntegers() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("financial.hourly-slot-minutes", "30"),
                    row("financial.nomenclature-level", "2")));
            assertEquals(30, service.financialHourlySlotMinutes());
            assertEquals(2, service.financialNomenclatureLevel());
        }
    }

    /**
     * {@code financialHourlySlotMinutes} and {@code financialNomenclatureLevel}
     * resolve to their catalog defaults when no row overrides them.
     */
    @Test
    void financialSlotAndLevelFallBackToDefaults() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(60, service.financialHourlySlotMinutes());
            assertEquals(1, service.financialNomenclatureLevel());
        }
    }

    /**
     * {@code financialSectors} parses the valid pairs (put arm), drops a blank
     * entry (continue arm), a pair with no separator (sep &lt;= 0 arm) and a pair
     * ending on the separator (sep == end arm).
     */
    @Test
    void financialSectorsParsesPairsAndDropsMalformed() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("financial.sectors", "10>FRAIS_TRAD; ;nosep;40>;20>FRAIS_LS")));
            Map<String, String> sectors = service.financialSectors();
            assertEquals(2, sectors.size());
            assertEquals("FRAIS_TRAD", sectors.get("10"));
            assertEquals("FRAIS_LS", sectors.get("20"));
        }
    }

    /**
     * {@code financialSectors} resolves to the catalog default, the four shipped
     * sectors, when no row overrides it.
     */
    @Test
    void financialSectorsFallsBackToTheFourDefaults() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            Map<String, String> sectors = service.financialSectors();
            assertEquals(4, sectors.size());
            assertEquals("FRAIS_TRAD", sectors.get("10"));
            assertEquals("NON_ALIMENTAIRE", sectors.get("40"));
        }
    }

    /**
     * {@code financialBestSellersCount} returns the administered depth, and
     * {@code financialAutoEmitEnabled} the administered flag (boolValue true arm).
     */
    @Test
    void financialBestSellersAndAutoEmitReturnAdministeredValues() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("financial.best-sellers-count", "5"),
                    row("financial.auto-emit-enabled", "true")));
            assertEquals(5, service.financialBestSellersCount());
            assertTrue(service.financialAutoEmitEnabled());
        }
    }

    /**
     * {@code financialAutoEmitEnabled} is false when disabled (boolValue false
     * arm), and {@code financialBestSellersCount} falls back to its default.
     */
    @Test
    void financialAutoEmitDisabledAndBestSellersDefault() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("financial.auto-emit-enabled", "false")));
            assertFalse(service.financialAutoEmitEnabled());
            assertEquals(20, service.financialBestSellersCount());
        }
    }

    /**
     * THE PORTED REGISTER KEYS ARE ALL THERE.
     *
     * <p>The node administers the register's parameters since the administration
     * left the lane: a key a register READS and this catalogue does not carry is
     * a key nobody can set in the shop — the back office never writes it, and the
     * register is left with its own default for good. The keys of chantier A are
     * therefore named here one by one, so that dropping one from {@code CATALOG}
     * fails here instead of quietly taking a parameter out of the back office.
     */
    @Test
    void catalogueCarriesEveryPortedRegisterKey() {
        Set<String> keys = PosSettingsService.CATALOG.stream()
                .map(PosSettingsService.Def::key)
                .collect(java.util.stream.Collectors.toSet());
        for (String ported : List.of(
                "control.cancel-line", "control.cancel-payment", "control.refund",
                "control.drawer-out-of-sale", "control.quantity", "control.quantity-max",
                "control.ticket-total", "control.ticket-total-max", "control.parked-at-close",
                "control.card-payment",
                "return.types", "return.reasons", "return.print-reason",
                "return.in-sale-allowed", "return.without-origin-allowed",
                "return.foreign-store-accepted", "return.dedicated-register",
                "language.system", "language.available", "language.operator-change",
                "language.customer-second",
                "movement.financial-sources", "movement.financial-destinations",
                "movement.financial-print",
                "change-order.print", "change-order.roll-values",
                "island.auto-declaration", "island.change-allowed", "island.promotion-channel",
                "balance.default-tare", "balance.tare-containers",
                "collect.point-label", "collect.voucher-copies",
                "cash.withdrawal-destinations", "cash.variance-amount-visible",
                "sale.line-warning-threshold", "fidelity.anonymous-projection",
                "sogec.bon-prefixes")) {
            assertTrue(keys.contains(ported), "key missing from the catalogue: " + ported);
        }
    }

    /**
     * EVERY CLOSED SET NAMES A PARAMETER OF THE CATALOGUE, and names one it can
     * actually serve: the key exists, it is a TEXT parameter (a closed set means
     * nothing on a boolean or an integer, which already have their widget), its
     * list is not empty, and the parameter's own DEFAULT is one of the values
     * offered — otherwise the admin page would open on a value its own list
     * cannot show, and saving would rewrite it.
     */
    @Test
    void closedValueSetsMatchTheirCatalogueEntry() {
        for (Map.Entry<String, List<String>> closed : PosSettingsService.CLOSED_VALUES.entrySet()) {
            PosSettingsService.Def entry = PosSettingsService.CATALOG.stream()
                    .filter(candidate -> candidate.key().equals(closed.getKey()))
                    .findFirst()
                    .orElse(null);
            assertTrue(entry != null, "key unknown to the catalogue: " + closed.getKey());
            assertEquals(PosSettingsService.Type.TEXT, entry.type(),
                    "a closed set on a non-TEXT parameter: " + entry.key());
            assertFalse(closed.getValue().isEmpty(), "empty value set: " + entry.key());
            assertTrue(closed.getValue().contains(entry.defaultValue()),
                    "the default is not among the offered values: " + entry.key());
        }
    }

    /**
     * BO-10-02-16 and -17: the two amount control points of the register are at
     * the node's catalogue, level and ceiling, under the control-points
     * section; and the two levels are offered as a closed set, like the eight
     * before them. Without these four entries no back-office screen can write
     * them and the register keeps its silent default.
     */
    @Test
    void catalogueCarriesTheTwoAmountControlPoints() {
        for (String key : List.of("control.item-amount", "control.item-amount-max",
                "control.payment-amount", "control.payment-amount-max")) {
            PosSettingsService.Def entry = PosSettingsService.CATALOG.stream()
                    .filter(candidate -> candidate.key().equals(key))
                    .findFirst()
                    .orElse(null);
            assertTrue(entry != null, "key missing from the catalogue: " + key);
            assertEquals("POINTS DE CONTRÔLE", entry.section(), key);
            assertEquals(PosSettingsService.Type.TEXT, entry.type(), key);
        }
        assertEquals(List.of("NONE", "WARN", "BLOCK", "ENDORSE"),
                PosSettingsService.closedValues("control.item-amount"));
        assertEquals(List.of("NONE", "WARN", "BLOCK", "ENDORSE"),
                PosSettingsService.closedValues("control.payment-amount"));
        assertTrue(PosSettingsService.closedValues("control.item-amount-max").isEmpty());
        assertTrue(PosSettingsService.closedValues("control.payment-amount-max").isEmpty());
    }

    /**
     * {@code closedValues} answers the set of a closed parameter and an EMPTY
     * list for a free one — the two arms the admin page branches on.
     */
    @Test
    void closedValuesAnswersTheSetOrNothing() {
        assertEquals(List.of("NONE", "WARN", "BLOCK", "ENDORSE"),
                PosSettingsService.closedValues("control.cancel-line"));
        assertTrue(PosSettingsService.closedValues("customer.message-open").isEmpty());
    }

    /**
     * LC-09-01-09: the keyboard layout is administered in the LANGUE section
     * as a closed choice of the four layouts the register draws, AZERTY first
     * as the default.
     */
    @Test
    void keyboardLayoutIsAClosedChoiceOfTheFourLayouts() {
        PosSettingsService.Def entry = PosSettingsService.CATALOG.stream()
                .filter(candidate -> candidate.key().equals("keyboard.layout"))
                .findFirst()
                .orElse(null);
        assertTrue(entry != null, "key missing from the catalogue: keyboard.layout");
        assertEquals("LANGUE", entry.section());
        assertEquals("AZERTY", entry.defaultValue());
        assertEquals(List.of("AZERTY", "QWERTY", "QWERTZ", "ABCD"),
                PosSettingsService.closedValues("keyboard.layout"));
    }

    /**
     * THE CATALOGUE IS WELL FORMED.
     *
     * <p>Every entry names a key, a section, a label and a hint, because the
     * admin page renders the four of them; and no key is carried twice, which
     * would show the parameter under two headings and let the save of one
     * overwrite the other.
     */
    @Test
    void catalogueEntriesAreUniqueAndFullyDescribed() {
        Set<String> keys = PosSettingsService.CATALOG.stream()
                .map(PosSettingsService.Def::key)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(PosSettingsService.CATALOG.size(), keys.size(), "a key is carried twice");
        for (PosSettingsService.Def def : PosSettingsService.CATALOG) {
            assertFalse(def.key().isBlank(), "a catalogue entry carries no key");
            assertFalse(def.section().isBlank(), "no section for " + def.key());
            assertFalse(def.label().isBlank(), "no label for " + def.key());
            assertFalse(def.hint().isBlank(), "no hint for " + def.key());
        }
    }

    /**
     * BO-10-03-05 / BO-10-03-11 / BO-10-03-12 / BO-10-03-21: the gabarit of the
     * loyalty zone is chosen by the SALE'S CASE — a card was presented or not,
     * the programme answered or not — and the grouped gabarit takes precedence
     * over the holder-online one when the advantages are grouped by category.
     */
    @Test
    void theLoyaltyZoneGabaritFollowsTheCase() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("fidelity.template-online", "FID-ON"),
                    row("fidelity.template-offline", "FID-OFF"),
                    row("fidelity.template-non-holder-online", "FID-NH-ON"),
                    row("fidelity.template-non-holder-offline", "FID-NH-OFF"),
                    row("fidelity.template-grouped", "  FID-CAT  ")));
            // All three legs of the grouped guard true, and the code trimmed.
            assertEquals("FID-CAT", service.fidelityTemplateCode(true, false, true));
            // Grouping off: the holder-online gabarit answers.
            assertEquals("FID-ON", service.fidelityTemplateCode(true, false, false));
            // Grouping asked for, but the programme did not answer: the
            // grouped guard falls on its middle leg.
            assertEquals("FID-OFF", service.fidelityTemplateCode(true, true, true));
            // Grouping asked for, but no card: it falls on its first leg.
            assertEquals("FID-NH-ON", service.fidelityTemplateCode(false, false, true));
            assertEquals("FID-NH-OFF", service.fidelityTemplateCode(false, true, true));
        }
    }

    /**
     * A blank gabarit code is the ABSENCE of a gabarit and not a gabarit named
     * by the empty string: the register prints its built-in loyalty zone, which
     * is what every shop does until a paramétreur has been there (BO-10-03-05).
     */
    @Test
    void anUnadministeredLoyaltyGabaritIsNoGabarit() {
        PosSettingsService grouped = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            // Grouping asked for and no grouped gabarit administered: the
            // holder-online gabarit answers instead of nothing.
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("fidelity.template-grouped", "   "),
                    row("fidelity.template-online", "FID-ON")));
            assertEquals("FID-ON", grouped.fidelityTemplateCode(true, false, true));
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertNull(bare.fidelityTemplateCode(true, false, true));
            assertNull(bare.fidelityTemplateCode(true, false, false));
            assertNull(bare.fidelityTemplateCode(true, true, false));
            assertNull(bare.fidelityTemplateCode(false, false, false));
            assertNull(bare.fidelityTemplateCode(false, true, false));
        }
    }

    /**
     * BO-10-07-04 / BO-10-07-05 / BO-10-07-06: the three currency parameters
     * project their rows, and fall back to the catalogue defaults — the symbol
     * E, printed, after the amount — which is what the register printed before
     * they existed.
     */
    @Test
    void theCurrencyParametersProjectTheirRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("currency.symbol", "€"),
                    row("currency.symbol-print", "false"),
                    row("currency.symbol-position", "LEFT")));
            assertEquals("€", administered.currencySymbol());
            assertFalse(administered.currencySymbolPrinted());
            assertEquals("LEFT", administered.currencySymbolPosition());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("E", bare.currencySymbol());
            assertTrue(bare.currencySymbolPrinted());
            assertEquals("RIGHT", bare.currencySymbolPosition());
        }
    }

    /**
     * BO-10-02-13 / -15 / -20 / -28 / -32: the five register rules of this lot
     * project their rows, and fall back to the catalogue defaults — which are
     * in every case the behaviour the register had before the key existed.
     */
    @Test
    void theFiveRegisterRulesProjectTheirRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("keyboard.enter-as-total", "true"),
                    row("sale.allow-during-payment", "true"),
                    row("ticket.second-code-print", "true"),
                    row("auth.idle-action", "CLOSE"),
                    row("scan.supervisor-prefix", "SUP")));
            assertTrue(administered.enterAsTotal());
            assertTrue(administered.saleAllowedDuringPayment());
            assertTrue(administered.secondCodePrinted());
            assertEquals("CLOSE", administered.idleAction());
            assertEquals("SUP", administered.supervisorBadgePrefix());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(bare.enterAsTotal());
            assertFalse(bare.saleAllowedDuringPayment());
            assertFalse(bare.secondCodePrinted());
            assertEquals("LOCK", bare.idleAction());
            assertEquals("", bare.supervisorBadgePrefix());
        }
    }

    /**
     * BO-10-02-19: each figure of a commercial document carries its own
     * administered number of decimals, and anything but three answers two —
     * which is what every document printed before the keys existed.
     */
    @Test
    void theDocumentDecimalsAreTwoOrThreeAndNothingElse() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("invoice.decimals-unit-ht", "3"),
                    row("invoice.decimals-vat-base", "3"),
                    row("invoice.decimals-vat-amount", "7"),
                    row("invoice.decimals-total-ht", "0"),
                    row("invoice.decimals-total-ttc", "3")));
            assertEquals(3, administered.invoiceUnitExcludingTaxDecimals());
            assertEquals(3, administered.invoiceVatBaseDecimals());
            assertEquals(2, administered.invoiceVatAmountDecimals());
            assertEquals(2, administered.invoiceTotalExcludingTaxDecimals());
            assertEquals(3, administered.invoiceTotalIncludingTaxDecimals());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(2, bare.invoiceUnitExcludingTaxDecimals());
            assertEquals(2, bare.invoiceVatBaseDecimals());
            assertEquals(2, bare.invoiceVatAmountDecimals());
            assertEquals(2, bare.invoiceTotalExcludingTaxDecimals());
            assertEquals(2, bare.invoiceTotalIncludingTaxDecimals());
        }
    }

    /**
     * BO-10-07-10 / BO-10-07-11: the article-count placement and the
     * unknown-article barcode project their rows, and fall back to the
     * catalogue defaults — nowhere, and no barcode.
     */
    @Test
    void theTicketCountAndUnknownBarcodeProjectTheirRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("ticket.item-count-print", "OWN_LINE"),
                    row("ticket.unknown-article-barcode", "true")));
            assertEquals("OWN_LINE", administered.ticketItemCountPrint());
            assertTrue(administered.unknownArticleBarcodePrinted());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("NONE", bare.ticketItemCountPrint());
            assertFalse(bare.unknownArticleBarcodePrinted());
        }
    }

    /**
     * BO-10-07-16 and BO-10-08-13/-14/-15: the search depth and the three
     * barcode retention delays project their rows, and fall back to the
     * catalogue defaults — thirty days of search, and no purge at all.
     */
    @Test
    void theSearchDepthAndBarcodeDelaysProjectTheirRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("ticket.search-days", "90"),
                    row("retention.barcodes-expired-unused-days", "365"),
                    row("retention.barcodes-expired-used-days", "180"),
                    row("retention.barcodes-undated-days", "730")));
            assertEquals(90, administered.ticketSearchDays());
            assertEquals(365, administered.barcodeExpiredUnusedDays());
            assertEquals(180, administered.barcodeExpiredUsedDays());
            assertEquals(730, administered.barcodeUndatedDays());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(30, bare.ticketSearchDays());
            assertEquals(0, bare.barcodeExpiredUnusedDays());
            assertEquals(0, bare.barcodeExpiredUsedDays());
            assertEquals(0, bare.barcodeUndatedDays());
        }
    }

    /**
     * BO-10-08-27: the address control projects its row and defaults to off —
     * a feed integrates what it carries until the shop asks otherwise.
     */
    @Test
    void theAddressControlProjectsItsRow() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(
                    List.of(row("customer.integration-require-address", "true")));
            assertTrue(administered.customerIntegrationRequiresAddress());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(bare.customerIntegrationRequiresAddress());
        }
    }

    /**
     * BO-10-03-03 / BO-10-05-04: the customer-display holder rule and the
     * printed tare project their rows, and both default to off — the viseur
     * client names nobody and the ticket states only the net weight, which is
     * what they did before the keys existed.
     */
    @Test
    void theCustomerHolderAndTareRulesProjectTheirRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("fidelity.show-holder-name-customer", "true"),
                    row("balance.print-tare", "true")));
            assertTrue(administered.fidelityShowHolderNameOnCustomerDisplay());
            assertTrue(administered.tarePrinted());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(bare.fidelityShowHolderNameOnCustomerDisplay());
            assertFalse(bare.tarePrinted());
        }
    }

    /**
     * BO-10-06-03: the fiscal certification number projects its row, and is
     * blank by default — no country but the one that asks for it pays for it.
     */
    @Test
    void theFiscalCertificateNumberProjectsItsRow() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(
                    List.of(row("fiscal.certificate-number", "0000/AT")));
            assertEquals("0000/AT", administered.fiscalCertificateNumber());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("", bare.fiscalCertificateNumber());
        }
    }

    /**
     * LC-08-04-23: the Portuguese documents and the ATCUD codes project their
     * rows; a malformed or blank entry is ignored and a type without a code
     * answers 0, and both default to off and to 0.
     */
    @Test
    void thePortugueseDocumentsAndTheirAtcudCodesProjectTheirRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("fiscal.pt-documents", "true"),
                    row("fiscal.atcud-codes", "BAD;NC: ;ft: AAJFJ3VNTN ;FS:AAJFJMVNTN")));
            assertTrue(administered.ptDocuments());
            assertEquals("AAJFJMVNTN", administered.atcudCode("FS"));
            assertEquals("AAJFJ3VNTN", administered.atcudCode("FT"));
            assertEquals("0", administered.atcudCode("NC"));
            assertEquals("0", administered.atcudCode("RC"));
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(bare.ptDocuments());
            assertEquals("0", bare.atcudCode("FS"));
        }
    }

    /**
     * LC-06-02-01, -08 / LC-08-04-15: the external code pattern, the recalled
     * documents' price source and the deposit invoice project their rows; the
     * price source reads the register's price only on {@code CAISSE}, and all
     * three default to nothing, the document's price and off.
     */
    @Test
    void theOrderSettingsProjectTheirRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("order.external-code-pattern", "(DV|BV)[0-9]+"),
                    row("order.recall-price-source", " caisse "),
                    row("order.deposit-invoice", "true")));
            assertEquals("(DV|BV)[0-9]+", administered.orderExternalCodePattern());
            assertTrue(administered.orderRecallPriceFromRegister());
            assertTrue(administered.orderDepositInvoice());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("", bare.orderExternalCodePattern());
            assertFalse(bare.orderRecallPriceFromRegister());
            assertFalse(bare.orderDepositInvoice());
        }
    }

    /**
     * LC-06-05-03 / -04, LC-13-06-09 / -14: the selfscanning settings and the
     * loyalty holder's language project their rows, the rate bounded to 0..100
     * and the partial control to at least one article; by default no pattern, no
     * draw, three articles and no holder language.
     */
    @Test
    void theSelfScanAndHolderLanguageSettingsProjectTheirRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("selfscan.code-pattern", "SS[0-9]{8}"),
                    row("selfscan.partial-control-rate", "150"),
                    row("selfscan.partial-control-items", "0"),
                    row("language.fidelity-holder", "true")));
            assertEquals("SS[0-9]{8}", administered.selfScanCodePattern());
            assertEquals(100, administered.selfScanPartialControlRate());
            assertEquals(1, administered.selfScanPartialControlItems());
            assertTrue(administered.fidelityHolderLanguage());
        }
        PosSettingsService negative = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("selfscan.partial-control-rate", "-5"),
                    row("selfscan.partial-control-items", "4")));
            assertEquals(0, negative.selfScanPartialControlRate());
            assertEquals(4, negative.selfScanPartialControlItems());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("", bare.selfScanCodePattern());
            assertEquals(0, bare.selfScanPartialControlRate());
            assertEquals(3, bare.selfScanPartialControlItems());
            assertFalse(bare.fidelityHolderLanguage());
        }
    }

    /**
     * LC-07-11-03, LC-05-07-02, LC-09-01-04: the gift-card slip, the separate
     * return detail and the external program project their rows; by default the
     * slip is printed, the returns are detailed on the receipt, and no program
     * is administered behind the default label.
     */
    @Test
    void theHubTicketsReturnsAndExternalProgramSettingsProjectTheirRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("giftcard.payment-ticket", "false"),
                    row("return.separate-detail", "true"),
                    row("external-program.label", "CASH MANAGEMENT"),
                    row("external-program.path", " /opt/cash/cm "),
                    row("external-program.arguments", " --till POS01 ")));
            assertFalse(administered.giftCardPaymentTicket());
            assertTrue(administered.returnSeparateDetail());
            assertEquals("CASH MANAGEMENT", administered.externalProgramLabel());
            assertEquals("/opt/cash/cm", administered.externalProgramPath());
            assertEquals("--till POS01", administered.externalProgramArguments());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(bare.giftCardPaymentTicket());
            assertFalse(bare.returnSeparateDetail());
            assertEquals("PROGRAMME EXTERNE", bare.externalProgramLabel());
            assertEquals("", bare.externalProgramPath());
            assertEquals("", bare.externalProgramArguments());
        }
    }

    /**
     * BO-03-06: the barcode parameters of the lot project their rows, a
     * negative day count being read as zero; by default no key control, no
     * block before the date is past, the default messages, no qbusting range,
     * every symbology, no offline alert and a blocking unknown-voucher alert.
     */
    @Test
    void theBarcodeSettingsProjectTheirRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("fidelity.card-luhn", "true"),
                    row("gs1.expiry-block-days", "-3"),
                    row("gs1.expiry-message", " Date expirée "),
                    row("gs1.short-date-message", " Date courte {date} "),
                    row("qbusting.code-pattern", " QB\\d{8} "),
                    row("scan.symbologies", " EAN13;QR "),
                    row("scan.offline-alert", " INFO "),
                    row("voucher.unknown-alert", " INFO ")));
            assertTrue(administered.fidelityCardLuhn());
            assertEquals(0, administered.gs1ExpiryBlockDays());
            assertEquals("Date expirée", administered.gs1ExpiryMessage());
            assertEquals("Date courte {date}", administered.gs1ShortDateMessage());
            assertEquals("QB\\d{8}", administered.qbustingCodePattern());
            assertEquals("EAN13;QR", administered.scanSymbologies());
            assertEquals("INFO", administered.scanOfflineAlert());
            assertEquals("INFO", administered.voucherUnknownAlert());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(bare.fidelityCardLuhn());
            assertEquals(0, bare.gs1ExpiryBlockDays());
            assertEquals("", bare.gs1ExpiryMessage());
            assertEquals("", bare.gs1ShortDateMessage());
            assertEquals("", bare.qbustingCodePattern());
            assertEquals("", bare.scanSymbologies());
            assertEquals("NONE", bare.scanOfflineAlert());
            assertEquals("BLOCK", bare.voucherUnknownAlert());
        }
        PosSettingsService two = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("gs1.expiry-block-days", "2")));
            assertEquals(2, two.gs1ExpiryBlockDays());
        }
    }

    /**
     * BO-10-03-19 / BO-10-07-14: the nomenclature sending and the enlarged
     * amount due project their rows, and both default to off.
     */
    @Test
    void theNomenclatureAndLargeAmountRulesProjectTheirRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("fidelity.send-subfamily", "true"),
                    row("ticket.due-amount-large", "true")));
            assertTrue(administered.fidelitySendSubFamily());
            assertTrue(administered.dueAmountLarge());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(bare.fidelitySendSubFamily());
            assertFalse(bare.dueAmountLarge());
        }
    }

    /**
     * BO-10-06-06: the eco-participation placement projects its row and falls
     * back to nowhere, which is what every ticket printed before the key.
     */
    @Test
    void theEcoTaxPlacementProjectsItsRow() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(
                    List.of(row("ticket.ecotax-print", "TOTAL")));
            assertEquals("TOTAL", administered.ecoTaxPrint());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("NONE", bare.ecoTaxPrint());
        }
    }

    /**
     * BO-10-08-10 / BO-10-08-23: the audit activation and the retention of its
     * rows project their values, and both default to off — no trace, and no
     * purge of a trace that does not exist.
     */
    @Test
    void theIntegrationAuditRulesProjectTheirRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("referential.update-audit-enabled", "true"),
                    row("retention.integration-results-days", "90")));
            assertTrue(administered.referentialUpdateAuditEnabled());
            assertEquals(90, administered.retentionSetting("retention.integration-results-days"));
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(bare.referentialUpdateAuditEnabled());
            assertEquals(0, bare.retentionSetting("retention.integration-results-days"));
        }
    }

    /**
     * BO-09-03-04: the counter reset projects its row and is off by default —
     * a numbering that restarts without anyone asking would be a numbering
     * nobody could follow.
     */
    @Test
    void theTicketCounterResetProjectsItsRow() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(
                    List.of(row("period.reset-ticket-counter", "true")));
            assertTrue(administered.resetTicketCounterAtPeriodEnd());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(bare.resetTicketCounterAtPeriodEnd());
        }
    }

    /**
     * BO-01-02-12: the expiry delay projects its row and is zero by default —
     * no policy, and no account caught by one nobody posed.
     */
    @Test
    void thePasswordExpiryDelayProjectsItsRow() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(
                    List.of(row("auth.password-expiry-days", "90")));
            assertEquals(90, administered.passwordExpiryDays());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(0, bare.passwordExpiryDays());
        }
    }

    /**
     * BO-01-01-01: the back-office lockout projects its two values, and a
     * duration of zero answers fifteen minutes — a lockout with no duration is
     * an account lost until somebody edits the database.
     */
    @Test
    void theBackOfficeLockoutProjectsItsRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("auth.bo-max-failures", "5"),
                    row("auth.bo-lockout-minutes", "60")));
            assertEquals(5, administered.backOfficeMaxFailures());
            assertEquals(60, administered.backOfficeLockoutMinutes());
        }
        PosSettingsService impossible = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(
                    List.of(row("auth.bo-lockout-minutes", "0")));
            assertEquals(15, impossible.backOfficeLockoutMinutes());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(3, bare.backOfficeMaxFailures());
            assertEquals(15, bare.backOfficeLockoutMinutes());
        }
    }

    /**
     * BO-01-02-07: whether the Dallas key opens the register alone reads its
     * stored boolean, off by default.
     */
    @Test
    void dallasKeyOpensReadsStoredValue() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("auth.dallas-key", "true")));
            assertTrue(administered.dallasKeyOpens());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(bare.dallasKeyOpens());
        }
    }

    /**
     * {@code passwordRequiredOnOpen} reads its stored boolean value
     * (LC-01-01-03/09, BO-10-02-22).
     */
    @Test
    void passwordRequiredOnOpenReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("auth.password-required-on-open", "false")));
            assertFalse(service.passwordRequiredOnOpen());
        }
    }

    /**
     * {@code passwordRequiredOnClose} reads its stored boolean value
     * (LC-01-02-02/03, BO-10-02-24).
     */
    @Test
    void passwordRequiredOnCloseReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("auth.password-required-on-close", "true")));
            assertTrue(service.passwordRequiredOnClose());
        }
    }

    /**
     * {@code closeEndorsementOnPending} reads its stored boolean value
     * (LC-01-02-10, BO-10-02-08).
     */
    @Test
    void closeEndorsementOnPendingReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("session.close-endorsement-on-pending", "false")));
            assertFalse(service.closeEndorsementOnPending());
        }
    }

    /**
     * {@code printOpenReceipt} reads its stored boolean value
     * (LC-01-01-08, BO-10-07-09).
     */
    @Test
    void printOpenReceiptReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("session.print-open-receipt", "true")));
            assertTrue(service.printOpenReceipt());
        }
    }

    /**
     * {@code printCloseReceipt} reads its stored boolean value
     * (LC-01-02-11, BO-10-07-09).
     */
    @Test
    void printCloseReceiptReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("session.print-close-receipt", "true")));
            assertTrue(service.printCloseReceipt());
        }
    }

    /**
     * {@code volatileCustomerCreationEnabled} reads its stored boolean value
     * (BO-10-04-01).
     */
    @Test
    void volatileCustomerCreationEnabledReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.volatile-creation-enabled", "false")));
            assertFalse(service.volatileCustomerCreationEnabled());
        }
    }

    /**
     * {@code accountCustomerCreationBlocked} reads its stored boolean value
     * (BO-10-04-05, BO-02-04-12).
     */
    @Test
    void accountCustomerCreationBlockedReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.account-creation-blocked", "false")));
            assertFalse(service.accountCustomerCreationBlocked());
        }
    }

    /**
     * {@code showCustomerDetails} reads its stored boolean value (BO-10-04-02).
     */
    @Test
    void showCustomerDetailsReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.show-details", "false")));
            assertFalse(service.showCustomerDetails());
        }
    }

    /**
     * {@code customerUpdateEnabled} reads its stored boolean value
     * (BO-10-04-03/04).
     */
    @Test
    void customerUpdateEnabledReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.update-enabled", "true")));
            assertTrue(service.customerUpdateEnabled());
        }
    }

    /**
     * {@code showCustomerDiscount} reads its stored boolean value (BO-10-04-08).
     */
    @Test
    void showCustomerDiscountReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.show-discount", "false")));
            assertFalse(service.showCustomerDiscount());
        }
    }

    /**
     * {@code customerDiscountSegment} reads its stored text value (BO-10-04-07).
     */
    @Test
    void customerDiscountSegmentReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.discount-segment", "GOLD")));
            assertEquals("GOLD", service.customerDiscountSegment());
        }
    }

    /**
     * {@code partialCreditAllowed} reads its stored boolean value (BO-10-04-10).
     */
    @Test
    void partialCreditAllowedReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.partial-credit-allowed", "false")));
            assertFalse(service.partialCreditAllowed());
        }
    }

    /**
     * {@code customerOfflineMessage} reads its stored text value (BO-10-04-11/12).
     */
    @Test
    void customerOfflineMessageReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.offline-message", "HORS LIGNE")));
            assertEquals("HORS LIGNE", service.customerOfflineMessage());
        }
    }

    /**
     * {@code customerOverLimitMessage} reads its stored text value (BO-10-04-13).
     */
    @Test
    void customerOverLimitMessageReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.over-limit-message", "PLAFOND {plafond}")));
            assertEquals("PLAFOND {plafond}", service.customerOverLimitMessage());
        }
    }

    /**
     * {@code defaultTaxId} reads its stored text value (BO-10-04-15).
     */
    @Test
    void defaultTaxIdReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.default-tax-id", "123456789")));
            assertEquals("123456789", service.defaultTaxId());
        }
    }

    /**
     * {@code defaultTaxIdLabel} reads its stored text value (BO-10-04-16).
     */
    @Test
    void defaultTaxIdLabelReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.default-tax-id-label", "CLIENT FINAL")));
            assertEquals("CLIENT FINAL", service.defaultTaxIdLabel());
        }
    }

    /**
     * {@code foreignTaxIdCountries} reads its stored text value (BO-10-04-17).
     */
    @Test
    void foreignTaxIdCountriesReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.foreign-tax-id-countries", "ES,PT")));
            assertEquals("ES,PT", service.foreignTaxIdCountries());
        }
    }

    /**
     * {@code invoiceExportDueDate} reads its stored boolean value (BO-10-04-19).
     */
    @Test
    void invoiceExportDueDateReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.invoice-export-due-date", "false")));
            assertFalse(service.invoiceExportDueDate());
        }
    }

    /**
     * {@code invoiceExportEcoTax} reads its stored boolean value (BO-10-04-14).
     */
    @Test
    void invoiceExportEcoTaxReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.invoice-export-ecotax", "true")));
            assertTrue(service.invoiceExportEcoTax());
        }
    }

    /**
     * {@code periodAutoCloseEnabled} reads its stored boolean value
     * (LC-01-02-08, BO-09-01-06/09).
     */
    @Test
    void periodAutoCloseEnabledReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("period.auto-close-enabled", "true")));
            assertTrue(service.periodAutoCloseEnabled());
        }
    }

    /**
     * {@code periodEndTime} reads its stored text value (BO-09-01-06).
     */
    @Test
    void periodEndTimeReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("period.end-time", "22:00")));
            assertEquals("22:00", service.periodEndTime());
        }
    }

    /**
     * {@code periodCloseLeadMinutes} reads its stored integer value (BO-09-03-07).
     */
    @Test
    void periodCloseLeadMinutesReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("period.close-lead-minutes", "15")));
            assertEquals(15, service.periodCloseLeadMinutes());
        }
    }

    /**
     * {@code fidelityShowHolderName} reads its stored boolean value (BO-10-03-04).
     */
    @Test
    void fidelityShowHolderNameReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("fidelity.show-holder-name", "false")));
            assertFalse(service.fidelityShowHolderName());
        }
    }

    /**
     * {@code customerAccountPattern} reads its stored text value (BO-03-06-52).
     */
    @Test
    void customerAccountPatternReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("customer.account-pattern", "^CC\\d{8}$")));
            assertEquals("^CC\\d{8}$", service.customerAccountPattern());
        }
    }

    /**
     * {@code fidelityCardPattern} reads its stored text value (BO-03-06-54).
     */
    @Test
    void fidelityCardPatternReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("fidelity.card-pattern", "^299\\d{10}$")));
            assertEquals("^299\\d{10}$", service.fidelityCardPattern());
        }
    }

    /**
     * {@code fidelityJvPromotionEnabled} reads its stored boolean value
     * (BO-10-03-22).
     */
    @Test
    void fidelityJvPromotionEnabledReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("fidelity.jv-promotion-enabled", "false")));
            assertFalse(service.fidelityJvPromotionEnabled());
        }
    }

    /**
     * {@code fidelityUrl} reads its stored text value (BO-11-04-04).
     */
    @Test
    void fidelityUrlReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("fidelity.url", "https://fid.example")));
            assertEquals("https://fid.example", service.fidelityUrl());
        }
    }

    /**
     * {@code fidelityUser} reads its stored text value (BO-11-04-04).
     */
    @Test
    void fidelityUserReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("fidelity.user", "caisse-c04")));
            assertEquals("caisse-c04", service.fidelityUser());
        }
    }

    /**
     * {@code trainingTheme} reads its stored text value (BO-10-07-08).
     */
    @Test
    void trainingThemeReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("training.theme", "sombre")));
            assertEquals("sombre", service.trainingTheme());
        }
    }

    /**
     * {@code lineMaxDiscountAmount} parses its stored decimal value (BO-03-07-04).
     */
    @Test
    void lineMaxDiscountAmountReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("discount.line-max-amount", "12.50")));
            assertEquals(0, new java.math.BigDecimal("12.50").compareTo(service.lineMaxDiscountAmount()));
        }
    }

    /**
     * {@code globalMaxDiscountAmount} parses its stored decimal value
     * (BO-03-07-04).
     */
    @Test
    void globalMaxDiscountAmountReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("discount.global-max-amount", "40.00")));
            assertEquals(0, new java.math.BigDecimal("40.00").compareTo(service.globalMaxDiscountAmount()));
        }
    }

    /**
     * {@code invoiceDocumentTypes} reads its stored text value (LC-08-04-04).
     */
    @Test
    void invoiceDocumentTypesReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("invoice.document-types", "FACTURE;BON_LIVRAISON")));
            assertEquals("FACTURE;BON_LIVRAISON", service.invoiceDocumentTypes());
        }
    }

    /**
     * {@code invoiceDocumentOutput} reads its stored text value (LC-08-04-11).
     */
    @Test
    void invoiceDocumentOutputReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("invoice.document-output", "FACTURE:A4")));
            assertEquals("FACTURE:A4", service.invoiceDocumentOutput());
        }
    }

    /**
     * {@code invoiceSlipLines} reads its stored integer value (LC-08-04-12/13).
     */
    @Test
    void invoiceSlipLinesReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("invoice.slip-lines", "12")));
            assertEquals(12, service.invoiceSlipLines());
        }
    }

    /**
     * {@code invoiceAutoPrint} reads its stored text value (LC-08-04-16).
     */
    @Test
    void invoiceAutoPrintReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("invoice.auto-print", "CREDIT:FACTURE")));
            assertEquals("CREDIT:FACTURE", service.invoiceAutoPrint());
        }
    }

    /**
     * {@code gs1ExpiryAlert} reads its stored text value (LC-11-03-13).
     */
    @Test
    void gs1ExpiryAlertReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("gs1.expiry-alert", "BLOCK")));
            assertEquals("BLOCK", service.gs1ExpiryAlert());
        }
    }

    /**
     * {@code restrictedTenders} reads its stored text value (LC-09-01-11 to -18).
     */
    @Test
    void restrictedTendersReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("tender.restricted", "TR:MEAL_VOUCHER_ELIGIBLE:Titre")));
            assertEquals("TR:MEAL_VOUCHER_ELIGIBLE:Titre", service.restrictedTenders());
        }
    }

    /**
     * {@code abandonReasons} reads its stored text value (LC-04-04-10).
     */
    @Test
    void abandonReasonsReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("abandon.reasons", "Erreur;Client parti")));
            assertEquals("Erreur;Client parti", service.abandonReasons());
        }
    }

    /**
     * {@code drawerWithdrawalMethods} reads its stored text value
     * (LC-12-03-02/03).
     */
    @Test
    void drawerWithdrawalMethodsReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("drawer.withdrawal-methods", "CASH:Especes")));
            assertEquals("CASH:Especes", service.drawerWithdrawalMethods());
        }
    }

    /**
     * {@code drawerWithdrawalPrint} reads its stored boolean value (LC-12-03-07).
     */
    @Test
    void drawerWithdrawalPrintReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("drawer.withdrawal-print", "false")));
            assertFalse(service.drawerWithdrawalPrint());
        }
    }

    /**
     * {@code drawerTransferMethods} reads its stored text value (LC-12-10-02).
     */
    @Test
    void drawerTransferMethodsReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("drawer.transfer-methods", "CASH:Especes;CHEQUE:Cheques")));
            assertEquals("CASH:Especes;CHEQUE:Cheques", service.drawerTransferMethods());
        }
    }

    /**
     * {@code drawerTransferPrint} reads its stored boolean value (LC-12-10-05).
     */
    @Test
    void drawerTransferPrintReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("drawer.transfer-print", "false")));
            assertFalse(service.drawerTransferPrint());
        }
    }

    /**
     * {@code abandonPartialPayment} reads its stored text value
     * (LC-04-04-06 to -09).
     */
    @Test
    void abandonPartialPaymentReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("abandon.partial-payment", "BLOCK")));
            assertEquals("BLOCK", service.abandonPartialPayment());
        }
    }

    /**
     * {@code abandonPrint} reads its stored text value (LC-04-04-12).
     */
    @Test
    void abandonPrintReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("abandon.print", "ALWAYS")));
            assertEquals("ALWAYS", service.abandonPrint());
        }
    }

    /**
     * {@code abandonPrintDetail} reads its stored boolean value (LC-04-04-12).
     */
    @Test
    void abandonPrintDetailReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("abandon.print-detail", "false")));
            assertFalse(service.abandonPrintDetail());
        }
    }

    /**
     * {@code gs1ExpiryWarnDays} reads its stored integer value (LC-11-03-13).
     */
    @Test
    void gs1ExpiryWarnDaysReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("gs1.expiry-warn-days", "7")));
            assertEquals(7, service.gs1ExpiryWarnDays());
        }
    }

    /**
     * {@code gs1CouponExpiryAlert} reads its stored text value (LC-11-03-13).
     */
    @Test
    void gs1CouponExpiryAlertReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("gs1.coupon-expiry-alert", "INFO")));
            assertEquals("INFO", service.gs1CouponExpiryAlert());
        }
    }

    /**
     * {@code gs1GiftCouponTypes} reads its stored text value (LC-11-03-05).
     */
    @Test
    void gs1GiftCouponTypesReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("gs1.gdti-coupon-types", "9526000:CADEAU")));
            assertEquals("9526000:CADEAU", service.gs1GiftCouponTypes());
        }
    }

    /**
     * {@code gs1CouponType} reads its stored text value (LC-11-03-06).
     */
    @Test
    void gs1CouponTypeReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("gs1.gcn-coupon-type", "COUPON")));
            assertEquals("COUPON", service.gs1CouponType());
        }
    }

    /**
     * {@code islandChangeAllowed} (BO-03-10-04) defaults to true when no row is
     * posted (current behaviour) and honours a stored prohibition — both arms.
     */
    @Test
    void islandChangeAllowedReadsAdministeredFlag() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(new PosSettingsService().islandChangeAllowed());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("island.change-allowed", "false")));
            assertFalse(new PosSettingsService().islandChangeAllowed());
        }
    }

    /**
     * {@code islandAutoDeclaration} (BO-03-10-10) defaults to false when no row
     * is posted (the counting screen stays) and honours a stored activation —
     * both arms.
     */
    @Test
    void islandAutoDeclarationReadsAdministeredFlag() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(new PosSettingsService().islandAutoDeclaration());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("island.auto-declaration", "true")));
            assertTrue(new PosSettingsService().islandAutoDeclaration());
        }
    }

    /**
     * {@code islandPromotionChannel} (BO-03-10-08) defaults to the empty string
     * when no row is posted (no channel is added, remontée unchanged) and
     * returns a stored channel — both arms.
     */
    @Test
    void islandPromotionChannelReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("", new PosSettingsService().islandPromotionChannel());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("island.promotion-channel", "WEB")));
            assertEquals("WEB", new PosSettingsService().islandPromotionChannel());
        }
    }

    /**
     * {@code welcomeMessage} (EXP-01) defaults to the invitation to log in when
     * no row is posted, and returns a stored override — both arms.
     */
    @Test
    void welcomeMessageReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("Bonjour : Connectez-vous", new PosSettingsService().welcomeMessage());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("session.welcome-message", "Bienvenue !")));
            assertEquals("Bienvenue !", new PosSettingsService().welcomeMessage());
        }
    }

    /**
     * {@code waitingLabel} (EXP-03) defaults to the retail "next customer"
     * prompt when no row is posted, and returns a stored override — both arms.
     */
    @Test
    void waitingLabelReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("CLIENT SUIVANT", new PosSettingsService().waitingLabel());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("sale.waiting-label", "AU SUIVANT")));
            assertEquals("AU SUIVANT", new PosSettingsService().waitingLabel());
        }
    }

    /**
     * {@code supervisorServiceReasons} reads its administered semicolon list
     * (EXP-84): the catalog default when nothing overrides, the stored row when
     * it does.
     */
    @Test
    void supervisorServiceReasonsReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("Annulation;Prix / étiquette;Retour client;Autre",
                    new PosSettingsService().supervisorServiceReasons());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("supervisor.service-reasons", "Coffre;Autre")));
            assertEquals("Coffre;Autre", new PosSettingsService().supervisorServiceReasons());
        }
    }

    /**
     * {@code drawerCap} (EXP-17) defaults to zero (no cap) when no row is
     * posted, returns a stored amount, and falls back to the catalog default
     * when the stored row is corrupt (bigDecimalValue guard) — all three arms.
     */
    @Test
    void drawerCapReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(0, new java.math.BigDecimal("0.00").compareTo(new PosSettingsService().drawerCap()));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.drawer-cap", "1000.00")));
            assertEquals(0, new java.math.BigDecimal("1000.00").compareTo(new PosSettingsService().drawerCap()));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.drawer-cap", "not-a-number")));
            assertEquals(0, new java.math.BigDecimal("0.00").compareTo(new PosSettingsService().drawerCap()));
        }
    }

    /**
     * {@code trdCardCap} reads the administered TRD-card cap (EXP-60): the
     * catalog default 0.00, a stored value, and a corrupt row falling back to the
     * default via {@code bigDecimalValue}.
     */
    @Test
    void trdCardCapReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(0, new java.math.BigDecimal("0.00").compareTo(new PosSettingsService().trdCardCap()));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("payment.trd-card-cap", "19.00")));
            assertEquals(0, new java.math.BigDecimal("19.00").compareTo(new PosSettingsService().trdCardCap()));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("payment.trd-card-cap", "not-a-number")));
            assertEquals(0, new java.math.BigDecimal("0.00").compareTo(new PosSettingsService().trdCardCap()));
        }
    }

    /**
     * {@code varianceThreshold()} reads the administered value (default 1.00,
     * stored override, corrupt-row fallback) — EXP-82.
     */
    @Test
    void varianceThresholdReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(0, new java.math.BigDecimal("1.00").compareTo(new PosSettingsService().varianceThreshold()));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.variance-threshold", "5.00")));
            assertEquals(0, new java.math.BigDecimal("5.00").compareTo(new PosSettingsService().varianceThreshold()));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.variance-threshold", "x")));
            assertEquals(0, new java.math.BigDecimal("1.00").compareTo(new PosSettingsService().varianceThreshold()));
        }
    }

    /**
     * {@code floatMode()} reads the administered value (default FIXED, stored
     * override) — EXP-95.
     */
    @Test
    void floatModeReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("FIXED", new PosSettingsService().floatMode());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.float-mode", "FLOATING")));
            assertEquals("FLOATING", new PosSettingsService().floatMode());
        }
    }

    /**
     * {@code varianceAmountVisible} defaults to FALSE — the cashier is told that the
     * gap crossed the threshold, not how much — and follows the stored row on both
     * arms when the shop administers one.
     */
    @Test
    void varianceAmountVisibleIsHiddenByDefault() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(new PosSettingsService().varianceAmountVisible());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.variance-amount-visible", "true")));
            assertTrue(new PosSettingsService().varianceAmountVisible());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.variance-amount-visible", "false")));
            assertFalse(new PosSettingsService().varianceAmountVisible());
        }
    }

    /**
     * {@code floatingFloat} answers on the mode's PREFIX: the two floating modes and
     * the former {@code FLOATING} spelling all float, the fixed float does not. A
     * mode nobody administered reads as the catalog default, which is fixed.
     */
    @Test
    void floatingFloatAnswersOnEveryFloatingMode() {
        for (String mode : List.of("FLOATING", "FLOATING_WEEK_MONTH", "FLOATING_MONTH")) {
            try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
                ms.when(PanacheEntityBase::listAll)
                        .thenReturn(List.of(row("cash.float-mode", mode)));
                assertTrue(new PosSettingsService().floatingFloat(), mode);
            }
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("cash.float-mode", "FIXED")));
            assertFalse(new PosSettingsService().floatingFloat());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(new PosSettingsService().floatingFloat());
        }
    }

    /**
     * {@code gestureEndorsementRequired(key)} (EXP-32) reads the per-gesture flag:
     * cancel-line defaults on, cancel-last defaults off, and a stored override wins
     * — both arms of one gesture plus a distinct default.
     */
    @Test
    void gestureEndorsementRequiredReadsPerGestureFlag() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(new PosSettingsService().gestureEndorsementRequired("cancel-line"));
            assertFalse(new PosSettingsService().gestureEndorsementRequired("cancel-last"));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("gesture.endorsement-cancel-line", "false")));
            assertFalse(new PosSettingsService().gestureEndorsementRequired("cancel-line"));
        }
    }

    /**
     * {@code endorsementScope} (EXP-33) defaults to PER_OP and returns a stored
     * PER_TICKET override — both arms.
     */
    @Test
    void endorsementScopeReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("PER_OP", new PosSettingsService().endorsementScope());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("gesture.endorsement-scope", "PER_TICKET")));
            assertEquals("PER_TICKET", new PosSettingsService().endorsementScope());
        }
    }

    /**
     * {@code saleLineWarningThreshold} (BO-10) returns the catalog default and a
     * stored override — both arms.
     *
     * <p>Tested on a REAL instance and not through a stub: eleven test lines
     * elsewhere stub this accessor to drive their own subject, which covers those
     * callers and never this method. A wrong catalog key compiles, returns the
     * wrong value and warns the cashier about the wrong price.
     */
    @Test
    void saleLineWarningThresholdReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(new java.math.BigDecimal("10.00"),
                    new PosSettingsService().saleLineWarningThreshold());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("sale.line-warning-threshold", "25.50")));
            assertEquals(new java.math.BigDecimal("25.50"),
                    new PosSettingsService().saleLineWarningThreshold());
        }
    }

    /**
     * {@code fidelityProjectedLabel} (BO-03-03-31) returns the catalog default
     * and a stored override — both arms.
     */
    @Test
    void fidelityProjectedLabelReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("CAGNOTTE PREVISIONNELLE",
                    new PosSettingsService().fidelityProjectedLabel());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("fidelity.projected-label", "VOTRE CAGNOTTE")));
            assertEquals("VOTRE CAGNOTTE", new PosSettingsService().fidelityProjectedLabel());
        }
    }

    /**
     * {@code fidelityAnonymousProjection} (BO-03-03-28) is OFF by default and
     * reads a stored activation — both arms.
     */
    @Test
    void fidelityAnonymousProjectionReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(new PosSettingsService().fidelityAnonymousProjection());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("fidelity.anonymous-projection", "true")));
            assertTrue(new PosSettingsService().fidelityAnonymousProjection());
        }
    }

    /**
     * {@code sogecBonPrefixes} (EXP-58) is EMPTY by default — no SOGEC voucher is
     * recognised until the shop administers a prefix — and returns the stored
     * list otherwise. Both arms.
     */
    @Test
    void sogecBonPrefixesReadsAdministeredValue() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("", new PosSettingsService().sogecBonPrefixes());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("sogec.bon-prefixes", "0300;0301")));
            assertEquals("0300;0301", new PosSettingsService().sogecBonPrefixes());
        }
    }

    // --------------------------------------------------
    // Campaign 2 — language accessors and a tare empty-row leg
    // --------------------------------------------------

    /**
     * {@code systemLanguage} returns the administered system-language tag
     * (LC-13-06-01), read from the stored override.
     */
    @Test
    void systemLanguageReadsAdministeredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("language.system", "nl")));
            assertEquals("nl", service.systemLanguage());
        }
    }

    /**
     * {@code availableLanguages} parses the semicolon-separated list, trims each
     * entry and drops the blank ones (LC-13-06-03/04): "fr;;nl" keeps "fr" and
     * "nl" (the non-blank true legs of the loop) and skips the empty middle entry
     * (the {@code !trimmed.isEmpty()} false leg), the setting being non-blank (the
     * {@code raw.isBlank()} false leg).
     */
    @Test
    void availableLanguagesParsesAndDropsBlankEntries() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("language.available", "fr;;nl")));
            assertEquals(List.of("fr", "nl"), service.availableLanguages());
        }
    }

    /**
     * {@code availableLanguages} returns an empty list for a blank setting (the
     * {@code raw.isBlank()} true leg) — the caller then offers French only.
     */
    @Test
    void availableLanguagesEmptyForBlankSetting() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("language.available", "   ")));
            assertTrue(service.availableLanguages().isEmpty());
        }
    }

    /**
     * {@code languageChangeAllowed} reads the administered flag (LC-13-06-04),
     * here the stored false override.
     */
    @Test
    void languageChangeAllowedReadsStoredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("language.operator-change", "false")));
            assertFalse(service.languageChangeAllowed());
        }
    }

    /**
     * {@code customerSecondLanguage} returns the administered second-language tag
     * (LC-13-06-06/11), read from the stored override.
     */
    @Test
    void customerSecondLanguageReadsAdministeredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("language.customer-second", "nl")));
            assertEquals("nl", service.customerSecondLanguage());
        }
    }

    /**
     * {@code tareContainers} skips an empty entry produced by a doubled separator
     * (the {@code trimmed.isEmpty()} true leg of the row loop) while keeping the
     * two well-formed rows around it (LC-14-01-04).
     */
    @Test
    void tareContainersDropsAnEmptyEntry() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("balance.tare-containers",
                    "SACPAP:Sac kraft:0.010;;BQS:Barquette:0.035")));
            List<PosSettingsService.TareContainer> containers =
                    new PosSettingsService().tareContainers();
            assertEquals(2, containers.size());
            assertEquals("SACPAP", containers.get(0).code());
            assertEquals("BQS", containers.get(1).code());
        }
    }

    // --------------------------------------------------
    // Articles a enlever — point de retrait et exemplaires du bon
    // --------------------------------------------------

    /**
     * {@code collectPointLabel} returns the administered collection point: one
     * place per store, named on the handover slip because the customer drives
     * there after paying (LC-02-08-05).
     */
    @Test
    void collectPointLabelReadsAdministeredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("collect.point-label", "BATI")));
            assertEquals("BATI", service.collectPointLabel());
        }
    }

    /**
     * {@code collectPointLabel} answers the catalog default — blank — when the
     * store administered none, and the slip then names no place.
     */
    @Test
    void collectPointLabelDefaultsToBlank() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("", service.collectPointLabel());
        }
    }

    /**
     * {@code collectVoucherCopies} returns the administered count — the two
     * copies the process needs, one kept by the collection desk and one by the
     * customer.
     */
    @Test
    void collectVoucherCopiesReadsAdministeredValue() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("collect.voucher-copies", "3")));
            assertEquals(3, service.collectVoucherCopies());
        }
    }

    /**
     * {@code collectVoucherCopies} never answers below one: a count administered
     * at zero or in the negative would print no slip at all, and the articles
     * would leave the shop with nothing proving it — the clamp arm.
     */
    @Test
    void collectVoucherCopiesNeverFallsBelowOne() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("collect.voucher-copies", "0")));
            assertEquals(1, service.collectVoucherCopies());
        }
        PosSettingsService negative = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("collect.voucher-copies", "-2")));
            assertEquals(1, negative.collectVoucherCopies());
        }
    }

    /**
     * {@code collectVoucherCopies} answers the catalog default of two when the
     * store administered nothing — the process the field expert described.
     */
    @Test
    void collectVoucherCopiesDefaultsToTwo() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(2, service.collectVoucherCopies());
        }
    }

    /**
     * The BO-03-03 accessors each project their stored row: the text ones
     * ({@code ticketLineAttribute}, {@code ticketLineAttributeIsolate},
     * {@code fidelityAdvantageGrouping}, {@code fidelityNonHolderMessage}) and
     * the boolean ones ({@code ticketFamilyAlphabetical},
     * {@code ticketLineDiscountPrint}, {@code ticketImmediateDiscountTotal},
     * {@code creditNotePrintExpiry}, {@code fidelityPrintCardNumber},
     * {@code fidelityPrintBalances}).
     */
    @Test
    void documentLayoutAccessorsProjectStoredValues() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("ticket.family-alphabetical", "false"),
                    row("ticket.line-attribute", "RAYON"),
                    row("ticket.line-attribute-isolate", "START"),
                    row("ticket.line-discount-print", "true"),
                    row("ticket.immediate-discount-total", "true"),
                    row("credit-note.print-expiry", "true"),
                    row("fidelity.print-card-number", "true"),
                    row("fidelity.print-balances", "true"),
                    row("fidelity.advantage-grouping", "CATEGORY"),
                    row("fidelity.non-holder-message", "PENSEZ A VOTRE CARTE")));
            assertFalse(service.ticketFamilyAlphabetical());
            assertEquals("RAYON", service.ticketLineAttribute());
            assertEquals("START", service.ticketLineAttributeIsolate());
            assertTrue(service.ticketLineDiscountPrint());
            assertTrue(service.ticketImmediateDiscountTotal());
            assertTrue(service.creditNotePrintExpiry());
            assertTrue(service.fidelityPrintCardNumber());
            assertTrue(service.fidelityPrintBalances());
            assertEquals("CATEGORY", service.fidelityAdvantageGrouping());
            assertEquals("PENSEZ A VOTRE CARTE", service.fidelityNonHolderMessage());
        }
    }

    /**
     * The same accessors on a register that administered NOTHING answer the
     * catalog defaults — which are the register's historical behaviour: the
     * family headings alphabetical, no attribute criterion, no new zone
     * printed, and the advantages grouped by type.
     */
    @Test
    void documentLayoutAccessorsDefaultToTheHistoricalBehaviour() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.ticketFamilyAlphabetical());
            assertEquals("", service.ticketLineAttribute());
            assertEquals("NONE", service.ticketLineAttributeIsolate());
            assertFalse(service.ticketLineDiscountPrint());
            assertFalse(service.ticketImmediateDiscountTotal());
            assertFalse(service.creditNotePrintExpiry());
            assertFalse(service.fidelityPrintCardNumber());
            assertFalse(service.fidelityPrintBalances());
            assertEquals("TYPE", service.fidelityAdvantageGrouping());
            assertEquals("", service.fidelityNonHolderMessage());
        }
    }

    /**
     * BO-03-10-05/-13/-14: the three island keys are OFF by default, so a store
     * that administers nothing keeps the unknown code on the screen only and
     * every message it had.
     */
    @Test
    void theThreeIslandKeysAreOffByDefault() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(service.islandUnknownArticleTicket());
            assertFalse(service.islandBlockArticlePopups());
            assertFalse(service.islandBlockPaymentPopups());
        }
    }

    /**
     * Administered by the island, each of the three is read as administered.
     */
    @Test
    void theThreeIslandKeysAreReadAsAdministered() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("island.unknown-article-ticket", "true"),
                    row("island.block-article-popups", "true"),
                    row("island.block-payment-popups", "true")));
            assertTrue(service.islandUnknownArticleTicket());
            assertTrue(service.islandBlockArticlePopups());
            assertTrue(service.islandBlockPaymentPopups());
        }
    }

    /**
     * BO-03-03-37, -36 and -07: the three new ticket parameters are OFF by
     * default, so a store that administers nothing prints exactly what it
     * printed before, and the exit ticket names three articles.
     */
    @Test
    void theNewTicketParametersAreOffByDefault() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(service.ticketVatCode());
            assertFalse(service.exitTicketEnabled());
            assertFalse(service.parkingPrintResume());
            assertEquals(3, service.exitTicketArticles());
        }
    }

    /**
     * Administered, each of the three is read as administered (the true arms).
     */
    @Test
    void theNewTicketParametersAreReadAsAdministered() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("ticket.vat-code", "true"),
                    row("ticket.exit-ticket", "true"),
                    row("parking.print-resume", "true"),
                    row("ticket.exit-ticket-articles", "5")));
            assertTrue(service.ticketVatCode());
            assertTrue(service.exitTicketEnabled());
            assertTrue(service.parkingPrintResume());
            assertEquals(5, service.exitTicketArticles());
        }
    }

    /**
     * BO-03-03-36: {@code exitTicketArticles} refuses a count that would make
     * the exit ticket longer than the receipt it replaces — both legs of its
     * guard, below one and past twenty — and keeps the two bounds themselves.
     */
    @Test
    void exitTicketArticlesRefusesACountOutsideItsBounds() {
        PosSettingsService tooFew = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("ticket.exit-ticket-articles", "0")));
            assertEquals(3, tooFew.exitTicketArticles());
        }
        PosSettingsService tooMany = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("ticket.exit-ticket-articles", "21")));
            assertEquals(3, tooMany.exitTicketArticles());
        }
        PosSettingsService lowBound = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("ticket.exit-ticket-articles", "1")));
            assertEquals(1, lowBound.exitTicketArticles());
        }
        PosSettingsService highBound = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("ticket.exit-ticket-articles", "20")));
            assertEquals(20, highBound.exitTicketArticles());
        }
    }

    /**
     * BO-03-03-14: {@code logoWidthDots} refuses a width the head cannot print
     * — both legs of its guard, below one packed byte and past an 80 mm roll —
     * and keeps a width inside the paper, which is the guard's false arm.
     */
    @Test
    void logoWidthDotsRefusesAWidthThePaperCannotHold() {
        PosSettingsService tooNarrow = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("print.logo-width-dots", "4")));
            assertEquals(384, tooNarrow.logoWidthDots());
        }
        PosSettingsService tooWide = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("print.logo-width-dots", "600")));
            assertEquals(384, tooWide.logoWidthDots());
        }
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("print.logo-width-dots", "576")));
            assertEquals(576, administered.logoWidthDots());
        }
    }

    /**
     * The two bounds themselves are accepted: eight dots is one packed byte and
     * 576 is the roll edge to edge, so neither is "outside the paper".
     */
    @Test
    void logoWidthDotsAcceptsItsOwnBounds() {
        PosSettingsService smallest = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("print.logo-width-dots", "8")));
            assertEquals(8, smallest.logoWidthDots());
        }
        PosSettingsService defaulted = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(384, defaulted.logoWidthDots());
        }
    }

    /**
     * BO-10-02-01 to -10: the control accessors project their stored row — the
     * level read as text, the quantity ceiling as an integer, the ticket
     * ceiling as an amount.
     */
    @Test
    void controlAccessorsProjectStoredValues() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("control.cancel-line", "WARN"),
                    row("control.card-payment", "BLOCK"),
                    row("control.quantity-max", "24"),
                    row("control.ticket-total-max", "1500.00")));
            assertEquals("WARN", service.controlLevel("control.cancel-line"));
            assertEquals("BLOCK", service.controlLevel("control.card-payment"));
            assertEquals(24, service.controlQuantityMax());
            assertEquals(0, new java.math.BigDecimal("1500.00")
                    .compareTo(service.controlTicketTotalMax()));
        }
    }

    /**
     * The catalogue defaults of the control points reproduce the register's
     * historical behaviour: an aval on the two gestures that already carried
     * one, no control at all on the six others, no ticket ceiling, and the
     * quantity ceiling at the absolute bound the code already enforced.
     */
    @Test
    void controlAccessorsDefaultToTheHistoricalBehaviour() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("ENDORSE", service.controlLevel("control.cancel-line"));
            assertEquals("ENDORSE", service.controlLevel("control.refund"));
            assertEquals("NONE", service.controlLevel("control.cancel-payment"));
            assertEquals("NONE", service.controlLevel("control.drawer-out-of-sale"));
            assertEquals("NONE", service.controlLevel("control.quantity"));
            assertEquals("NONE", service.controlLevel("control.ticket-total"));
            assertEquals("NONE", service.controlLevel("control.parked-at-close"));
            assertEquals("NONE", service.controlLevel("control.card-payment"));
            assertEquals("NONE", service.controlLevel("control.item-amount"));
            assertEquals("NONE", service.controlLevel("control.payment-amount"));
            assertEquals(999, service.controlQuantityMax());
            assertNull(service.controlTicketTotalMax());
            assertNull(service.controlItemAmountMax());
            assertNull(service.controlPaymentAmountMax());
        }
    }

    /**
     * BO-10-02-07: a ticket ceiling nobody can read is NO ceiling — the blank
     * arm, which is the default, and the malformed arm, which is a typo. A
     * ceiling read as zero would alert on every sale of the day.
     */
    @Test
    void theTicketCeilingIsNoCeilingWhenItCannotBeRead() {
        PosSettingsService blank = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("control.ticket-total-max", "   ")));
            assertNull(blank.controlTicketTotalMax());
        }
        PosSettingsService malformed = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("control.ticket-total-max", "mille")));
            assertNull(malformed.controlTicketTotalMax());
        }
    }

    /**
     * BO-10-02-07: the ceiling is typed by a person, so the French decimal
     * comma is read as a decimal point and the surrounding blanks are ignored.
     */
    @Test
    void theTicketCeilingReadsAFrenchAmount() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("control.ticket-total-max", " 1500,50 ")));
            assertEquals(0, new java.math.BigDecimal("1500.50")
                    .compareTo(service.controlTicketTotalMax()));
        }
    }

    /**
     * BO-10-02-16 and -17: the two amount ceilings project their stored row,
     * French comma and surrounding blanks included.
     */
    @Test
    void theTwoAmountCeilingsProjectStoredValues() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("control.item-amount", "BLOCK"),
                    row("control.payment-amount", "WARN"),
                    row("control.item-amount-max", " 299,99 "),
                    row("control.payment-amount-max", "5000.00")));
            assertEquals("BLOCK", service.controlLevel("control.item-amount"));
            assertEquals("WARN", service.controlLevel("control.payment-amount"));
            assertEquals(0, new java.math.BigDecimal("299.99")
                    .compareTo(service.controlItemAmountMax()));
            assertEquals(0, new java.math.BigDecimal("5000.00")
                    .compareTo(service.controlPaymentAmountMax()));
        }
    }

    /**
     * BO-10-02-16 and -17: an amount ceiling nobody can read is NO ceiling —
     * the blank arm and the malformed arm, for each of the two keys, because a
     * ceiling read as zero would refuse every article and every settlement.
     */
    @Test
    void anAmountCeilingIsNoCeilingWhenItCannotBeRead() {
        PosSettingsService blankItem = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("control.item-amount-max", "   ")));
            assertNull(blankItem.controlItemAmountMax());
        }
        PosSettingsService malformedItem = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("control.item-amount-max", "cent")));
            assertNull(malformedItem.controlItemAmountMax());
        }
        PosSettingsService blankPayment = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("control.payment-amount-max", "")));
            assertNull(blankPayment.controlPaymentAmountMax());
        }
        PosSettingsService malformedPayment = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("control.payment-amount-max", "5 000 €")));
            assertNull(malformedPayment.controlPaymentAmountMax());
        }
    }

    /**
     * BO-03-09-31: the Label Point De Vente and its activation key project
     * their stored rows, and both fall back to the catalog defaults — off, and
     * no label — when nothing is administered.
     */
    @Test
    void thePdvLabelAndItsActivationProjectTheirRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("terminal.pdv-label-enabled", "true"),
                    row("terminal.pdv-label", "{LPDV}MAGASIN 34")));
            assertTrue(administered.terminalPdvLabelEnabled());
            assertEquals("{LPDV}MAGASIN 34", administered.terminalPdvLabel());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(bare.terminalPdvLabelEnabled());
            assertEquals("", bare.terminalPdvLabel());
        }
    }

    /**
     * BO-03-02-06/-07 and BO-03-02-43: the locally modifiable range and the
     * cheque endorsement project their stored rows, and both fall back to the
     * catalog default — empty — when nothing is administered.
     */
    @Test
    void theTenderRangeAndTheChequeEndorsementProjectTheirRows() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("tender.local-range", "500-599,900"),
                    row("cheque.endorsement-lines", "{date} {heure};{montant} EUR;CAISSE {caisse}")));
            assertEquals("500-599,900", administered.tenderLocalRange());
            assertEquals("{date} {heure};{montant} EUR;CAISSE {caisse}",
                    administered.chequeEndorsementLines());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("", bare.tenderLocalRange());
            assertEquals("", bare.chequeEndorsementLines());
        }
    }

    /**
     * BO-03-09-27: the third-party applications are read in administered order,
     * and every malformed entry is DROPPED rather than refused — one typo must
     * not cost the cashier the others. Each leg: no arrow, nothing before the
     * arrow, nothing after it, and an entry that is only spaces.
     */
    @Test
    void theExternalApplicationsDropEveryMalformedEntry() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("touch.external-apps",
                    "  Fid > https://fid.magasin/caisse  ;sans fleche;>sans libelle;"
                            + "sans adresse>;   ;Photo>https://photo.magasin/")));
            List<PosSettingsService.ExternalApp> apps = service.externalApps();
            assertEquals(2, apps.size());
            assertEquals("Fid", apps.get(0).label());
            assertEquals("https://fid.magasin/caisse", apps.get(0).url());
            assertEquals("Photo", apps.get(1).label());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(bare.externalApps().isEmpty());
        }
    }

    /**
     * BO-03-12-02: the point of sale's period-end actions are read in
     * administered order, an unknown name is DROPPED rather than refused (a typo
     * must not leave a store unable to close its day), a duplicate is kept once,
     * and nothing administered answers an empty list.
     */
    @Test
    void thePeriodEndActionsAreReadInAdministeredOrder() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("period.pdv-actions",
                    " z_report ; CLOSE_REGISTERS ; danser ;  ; Z_REPORT ; BANK_DEPOSIT ")));
            assertEquals(List.of(PosSettingsService.PeriodAction.Z_REPORT,
                            PosSettingsService.PeriodAction.CLOSE_REGISTERS,
                            PosSettingsService.PeriodAction.BANK_DEPOSIT),
                    service.periodPdvActions());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(bare.periodPdvActions().isEmpty());
        }
        PosSettingsService blank = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("period.pdv-actions", "   ")));
            assertTrue(blank.periodPdvActions().isEmpty());
        }
        // The four names are all understood, and SAFE_COUNT is the one the
        // cases above do not exercise.
        assertEquals(4, PosSettingsService.PeriodAction.values().length);
        assertEquals(PosSettingsService.PeriodAction.SAFE_COUNT,
                PosSettingsService.PeriodAction.valueOf("SAFE_COUNT"));
    }

    /**
     * BO-03-06-53: the range of customer numbers reserved to the point of sale
     * projects its stored row, and falls back to the catalog default — empty —
     * when nothing is administered.
     */
    @Test
    void theCustomerCodeRangeProjectsItsRow() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(
                    List.of(row("customer.local-code-range", "CLI:500000-599999")));
            assertEquals("CLI:500000-599999", administered.customerLocalCodeRange());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("", bare.customerLocalCodeRange());
        }
    }

    /**
     * BO-01-02-09: the back-office sign-in mode reads the administered door,
     * ANY by default.
     */
    @Test
    void theBackOfficeSignInModeIsRead() {
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("auth.bo-sign-in", "sso")));
            assertEquals(com.intermarche.edge.domain.people.Employee.AuthMode.SSO, administered.backOfficeSignIn());
        }
        PosSettingsService bare = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(com.intermarche.edge.domain.people.Employee.AuthMode.ANY, bare.backOfficeSignIn());
        }
    }

    /** LC-13-02-09/-14: every peripheral defaults to enabled. */
    @Test
    void peripheralActivationDefaults() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.hardwareEnabled("printer"));
            assertTrue(service.hardwareEnabled("scanner"));
            assertTrue(service.hardwareEnabled("scale"));
        }
    }

    /** LC-13-02-09/-14: each administered role can be disabled independently. */
    @Test
    void peripheralActivationAdministeredValues() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("hardware.printer.enabled", "false"),
                    row("hardware.scanner.enabled", "false"),
                    row("hardware.scale.enabled", "false")));
            assertFalse(service.hardwareEnabled("printer"));
            assertFalse(service.hardwareEnabled("scanner"));
            assertFalse(service.hardwareEnabled("scale"));
        }
    }

    /** BO-11/BO-03: corrupt lower/upper numeric bounds are handled independently of valid values. */
    @Test
    void coverageExportStreamAndLogoBounds() {
        for (int[] values : new int[][]{{-1, 0, 0}, {24, 2, 577}, {12, 5, 8}}) {
            java.util.List<PosSetting> rows = java.util.List.of(
                    row("export.accounting-cutover-hour", String.valueOf(values[0])),
                    row("stream.delivery-seconds", String.valueOf(values[1])),
                    row("print.logo-width-dots", String.valueOf(values[2])));
            try (var base = mockStatic(PanacheEntityBase.class)) {
                base.when(PanacheEntityBase::listAll).thenReturn(rows);
                var service = new PosSettingsService();
                assertEquals(values[0] < 0 || values[0] > 23 ? 0 : values[0], service.accountingCutoverHour());
                assertEquals(Math.max(1, values[1]), service.streamDeliverySeconds());
                assertEquals(values[2] < 8 || values[2] > 576 ? 384 : values[2], service.logoWidthDots());
            }
        }
        PosSettingsService bare = new PosSettingsService();
        assertEquals("", bare.pdvNumber());
        bare.nodePdvNumber = java.util.Optional.empty();
        assertEquals("", bare.pdvNumber());
        bare.nodePdvNumber = java.util.Optional.of("12345");
        assertEquals("12345", bare.pdvNumber());
    }

    /**
     * BO-03-09-27/BO-03-12-02 and BO-10-04-09/BO-11-01-02: known
     * catalog entries always supply a non-null default, making null guards unreachable.
     */
    @Test
    void knownTextSettingsKeepNonNullDefaults() {
        PosSettingsService service = new PosSettingsService();
        assertTrue(service.def("customer.credit-tender").defaultValue() != null);
        assertTrue(service.def("export.directory").defaultValue() != null);
        assertTrue(service.def("touch.external-apps").defaultValue() != null);
        assertTrue(service.def("period.pdv-actions").defaultValue() != null);
    }

    /** BO-03-07: repeated preset options retain their flags while the final amount remains authoritative. */
    @Test
    void discountPresetsRetainFlagsAcrossRepeatedOptions() {
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(PanacheEntityBase::listAll).thenReturn(List.of(row("discount.presets", "P>PERCENT:5,PERCENT:10;A>AMOUNT:1,AMOUNT:2")));
            var presets = new PosSettingsService().discountPresets();
            assertEquals(2, presets.size());
            assertTrue(presets.get(0).percent());
            assertEquals(new java.math.BigDecimal("10"), presets.get(0).value());
            assertFalse(presets.get(1).percent());
            assertEquals(new java.math.BigDecimal("2"), presets.get(1).value());
        }
    }

    /** LC-01/LC-07/LC-08: recently administered settings project their exact catalog values. */
    @Test
    void cashierSettingsExposeTheirAdministeredValues() {
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("auth.endorsement-roles", "MANAGER"),
                    row("supervision.unknown-item-alert", "true"),
                    row("reassign.pdv-duplicate", "true"),
                    row("ticket.footer-url", "https://shop/receipt"),
                    row("ticket.footer-url-format", "QR"),
                    row("payment.verifone-method-map", "CARD=1"),
                    row("payment.card-receipt-attached", "true"),
                    row("ticket.barcode-prefix", "TKT")));
            PosSettingsService service = new PosSettingsService();
            assertEquals("MANAGER", service.endorsementRoles());
            assertTrue(service.unknownItemAlertEnabled());
            assertTrue(service.reassignPdvDuplicate());
            assertEquals("https://shop/receipt", service.ticketFooterUrl());
            assertEquals("QR", service.ticketFooterUrlFormat());
            assertEquals("CARD=1", service.verifoneMethodMap());
            assertTrue(service.cardReceiptAttached());
            assertEquals("TKT", service.ticketBarcodePrefix());
            var type = new PosSettingsService.ReturnType("RETURN", true);
            assertTrue(type.dedicatedTicket());
            assertEquals("RETURN", type.label());
        }
    }

    /** LC-07-04-07/08: cashback is off with a 60 euro ceiling by default, and follows the back office once administered. */
    @Test
    void cashbackSettingsUseTheirDefaultsAndAdministeredValues() {
        try (var rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of());
            PosSettingsService defaults = new PosSettingsService();
            assertFalse(defaults.cashbackEnabled());
            assertEquals(new java.math.BigDecimal("60.00"), defaults.cashbackMax());
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("payment.cashback-enabled", "true"),
                    row("payment.cashback-max", "40")));
            PosSettingsService administered = new PosSettingsService();
            assertTrue(administered.cashbackEnabled());
            assertEquals(new java.math.BigDecimal("40"), administered.cashbackMax());
        }
    }

    /** BO-03-03/05 and LC-07-04/07: stored overrides reach consumers without reverting to catalog defaults; covers the accessor return paths. */
    @Test
    void administeredReceiptPresentationValuesReachTheirConsumers() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("ticket.footer-url", "configured-ticket.footer-url"),
                    row("ticket.footer-url-format", "configured-ticket.footer-url-format"),
                    row("payment.verifone-method-map", "configured-payment.verifone-method-map"),
                    row("payment.card-receipt-attached", "true"),
                    row("ticket.family-alphabetical", "true"),
                    row("ticket.line-attribute", "configured-ticket.line-attribute"),
                    row("ticket.line-attribute-isolate", "configured-ticket.line-attribute-isolate"),
                    row("ticket.line-discount-print", "true"),
                    row("ticket.immediate-discount-total", "true"),
                    row("credit-note.print-expiry", "true"),
                    row("fidelity.print-card-number", "true"),
                    row("fidelity.print-balances", "true"),
                    row("fidelity.advantage-grouping", "configured-fidelity.advantage-grouping"),
                    row("fidelity.non-holder-message", "configured-fidelity.non-holder-message")));
            assertEquals("configured-ticket.footer-url", service.ticketFooterUrl());
            assertEquals("configured-ticket.footer-url-format", service.ticketFooterUrlFormat());
            assertEquals("configured-payment.verifone-method-map", service.verifoneMethodMap());
            assertEquals(true, service.cardReceiptAttached());
            assertEquals(true, service.ticketFamilyAlphabetical());
            assertEquals("configured-ticket.line-attribute", service.ticketLineAttribute());
            assertEquals("configured-ticket.line-attribute-isolate", service.ticketLineAttributeIsolate());
            assertEquals(true, service.ticketLineDiscountPrint());
            assertEquals(true, service.ticketImmediateDiscountTotal());
            assertEquals(true, service.creditNotePrintExpiry());
            assertEquals(true, service.fidelityPrintCardNumber());
            assertEquals(true, service.fidelityPrintBalances());
            assertEquals("configured-fidelity.advantage-grouping", service.fidelityAdvantageGrouping());
            assertEquals("configured-fidelity.non-holder-message", service.fidelityNonHolderMessage());
        }
    }

    /** BO-11-01/02: stored overrides reach consumers without reverting to catalog defaults; covers the accessor return paths. */
    @Test
    void administeredExportsAndStreamingValuesReachTheirConsumers() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("export.directory", "  /tmp/administered-flows  "),
                    row("export.revenue-enabled", "true"),
                    row("export.cash-book-enabled", "true"),
                    row("export.family-sales-enabled", "true"),
                    row("export.transactions-enabled", "true"),
                    row("export.period-summary-enabled", "true"),
                    row("export.productivity-enabled", "true"),
                    row("export.customer-invoices-enabled", "true"),
                    row("export.erp-invoices-enabled", "true"),
                    row("export.fidelity-vat-enabled", "true"),
                    row("export.order-vat-enabled", "true"),
                    row("export.panels-enabled", "true"),
                    row("stream.enabled", "true"),
                    row("stream.compress-image", "true")));
            assertEquals("/tmp/administered-flows", service.exportDirectory());
            assertEquals(true, service.exportRevenueEnabled());
            assertEquals(true, service.exportCashBookEnabled());
            assertEquals(true, service.exportFamilySalesEnabled());
            assertEquals(true, service.exportTransactionsEnabled());
            assertEquals(true, service.exportPeriodSummaryEnabled());
            assertEquals(true, service.exportProductivityEnabled());
            assertEquals(true, service.exportCustomerInvoicesEnabled());
            assertEquals(true, service.exportErpInvoicesEnabled());
            assertEquals(true, service.exportFidelityVatEnabled());
            assertEquals(true, service.exportOrderVatEnabled());
            assertEquals(true, service.exportPanelsEnabled());
            assertEquals(true, service.streamEnabled());
            assertEquals(true, service.streamCompressImage());
        }
    }

    /** BO-04-02/03 and BO-09-01/02: stored overrides reach consumers without reverting to catalog defaults; covers the accessor return paths. */
    @Test
    void administeredSupervisionAndPeriodsValuesReachTheirConsumers() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("supervision.register-offline-minutes", "17"),
                    row("dashboard.indicators", "configured-dashboard.indicators"),
                    row("period.week-end-day", "configured-period.week-end-day"),
                    row("period.month-end-day", "17"),
                    row("period.year-end-date", "configured-period.year-end-date"),
                    row("period.reports", "configured-period.reports"),
                    row("period.reports-format", "configured-period.reports-format"),
                    row("period.alert-countdown-minutes", "17"),
                    row("supervision.settled-calls-kept", "true")));
            assertEquals(17, service.registerOfflineMinutes());
            assertEquals("configured-dashboard.indicators", service.dashboardIndicators());
            assertEquals("configured-period.week-end-day", service.periodWeekEndDay());
            assertEquals(17, service.periodMonthEndDay());
            assertEquals("configured-period.year-end-date", service.periodYearEndDate());
            assertEquals("configured-period.reports", service.periodReports());
            assertEquals("configured-period.reports-format", service.periodReportsFormat());
            assertEquals(17, service.alertCountdownMinutes());
            assertEquals(true, service.settledCallsKept());
        }
    }

    /** LC-02-07-11: article-price currency is shown by default and can be hidden by a stored back-office override. */
    @Test
    void articlePriceCurrencyUsesItsDefaultAndAdministeredOverride() {
        try (var rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(new PosSettingsService().touchPriceCurrency());
            var override = row("touch.price-currency", "false");
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(override));
            assertFalse(new PosSettingsService().touchPriceCurrency());
        }
    }

    /** LC-04-01-02: default AUTO with detail, REQUEST and unknown-mode fallback use mirrored catalog keys. */
    @Test
    void waitingReceiptPolicyIsAdministeredAndDefaultsToAutomaticDetail() {
        assertEquals(List.of("AUTO", "REQUEST"), PosSettingsService.closedValues("parking.print-mode"));
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(service.parkingPrintOnRequest());
            assertTrue(service.parkingReceiptDetailed());
            service.invalidate();
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("parking.print-mode", "request"), row("parking.receipt-detail", "false")));
            assertTrue(service.parkingPrintOnRequest());
            assertFalse(service.parkingReceiptDetailed());
            service.invalidate();
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of(row("parking.print-mode", "UNKNOWN")));
            assertFalse(service.parkingPrintOnRequest());
        }
    }

    /** LC-02-07-02/03: persisted layout limits and ordered, deduplicated EAN priorities are applied. */
    @Test
    void articleTouchLayoutAndPrioritiesFollowSettings() {
        PosSettingsService service = org.mockito.Mockito.spy(new PosSettingsService());
        org.mockito.Mockito.doReturn(" 300 ; 100 ;300;; ").when(service).value("touch.article-order");
        assertEquals(java.util.List.of("300", "100"), service.touchArticleOrder());
        org.mockito.Mockito.doReturn("1").when(service).value("touch.grid-columns");
        assertEquals(2, service.touchGridColumns());
        org.mockito.Mockito.doReturn("4").when(service).value("touch.grid-columns");
        assertEquals(4, service.touchGridColumns());
        org.mockito.Mockito.doReturn("9").when(service).value("touch.grid-columns");
        assertEquals(6, service.touchGridColumns());
        org.mockito.Mockito.doReturn("").when(service).value("touch.article-order");
        assertTrue(service.touchArticleOrder().isEmpty());
    }

    /** LC-12-07-02: every catalogued rubric defaults visible and honors its inherited false value. */
    @Test
    void revenueRubricsFollowTheCataloguedVisibility() {
        var service = org.mockito.Mockito.spy(new PosSettingsService());
        for (String section : java.util.List.of("sales", "returns", "expenses", "cancellations", "abandoned", "price-overrides", "deposits", "withdrawals", "tenders")) {
            assertEquals("true", service.def("report.show-" + section).defaultValue());
            org.mockito.Mockito.doReturn("true").when(service).value("report.show-" + section);
            assertTrue(service.reportSectionVisible(section));
            org.mockito.Mockito.doReturn("false").when(service).value("report.show-" + section);
            assertFalse(service.reportSectionVisible(section));
        }
    }

    /** LC-08-02-05/LC-12-04-04: optional e-mail offer defaults off, historical supply printing defaults on. */
    @Test
    void emailOfferAndCashSupplyPrintingFollowInheritedFlags() {
        var service = org.mockito.Mockito.spy(new PosSettingsService());
        assertEquals("false", service.def("ticket.email-offer").defaultValue());
        assertEquals("true", service.def("cash.deposit-print").defaultValue());
        org.mockito.Mockito.doReturn("false").when(service).value("ticket.email-offer");
        org.mockito.Mockito.doReturn("true").when(service).value("cash.deposit-print");
        assertFalse(service.ticketEmailOffer());
        assertTrue(service.cashDepositPrint());
        org.mockito.Mockito.doReturn("true").when(service).value("ticket.email-offer");
        org.mockito.Mockito.doReturn("false").when(service).value("cash.deposit-print");
        assertTrue(service.ticketEmailOffer());
        assertFalse(service.cashDepositPrint());
    }

    /** LC-13-05-04: absent rules leave training usable; inherited text is preserved exactly. */
    @Test
    void trainingBlockedKeysDefaultsAndStoredRules() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("", service.trainingBlockedKeys());
        }
        service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(row("training.blocked-keys", "path:/pay\nkey:/:discount")));
            assertEquals("path:/pay\nkey:/:discount", service.trainingBlockedKeys());
        }
    }

    /** LC-08-01-03 and LC-01-05-01: catalog defaults and explicit inherited choices remain effective. */
    @Test
    void barcodeAndFunctionApprovalSettings() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.ticketBarcodeEnabled());
            assertFalse(service.ticketBarcodeQr());
            assertEquals("", service.functionEndorsements());
        }
        service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(row("ticket.barcode-enabled", "false"),
                    row("ticket.barcode-format", " qr "), row("gesture.function-endorsements", "POST /sale-tax-id=REQUIRED")));
            assertFalse(service.ticketBarcodeEnabled());
            assertTrue(service.ticketBarcodeQr());
            assertEquals("POST /sale-tax-id=REQUIRED", service.functionEndorsements());
        }
    }

    /** LC-02-07-08: tiles hide EANs by default and honor explicit true/false BackOffice values. */
    @Test
    void touchEanVisibilityHasASeparateAdministeredDefault() {
        try (var rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of());
            var service = new PosSettingsService();
            assertFalse(service.touchShowEan());
            var def = PosSettingsService.CATALOG.stream().filter(d -> d.key().equals("touch.show-ean")).findFirst().orElseThrow();
            assertEquals(PosSettingsService.Type.BOOL, def.type());
            assertEquals("false", def.defaultValue());
            var override = row("touch.show-ean", "true");
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(override));
            service.invalidate();
            assertTrue(service.touchShowEan());
            override.settingValue = "false";
            service.invalidate();
            assertFalse(service.touchShowEan());
        }
    }
    // ---- lot 18: E ----

    /**
     * The ecommerce channel getters of lot 18 answer their catalogue defaults when
     * nothing is administered, and the administered value — trimmed, and
     * upper-cased for a closed set — when a row overrides it.
     */
    @Test
    void ecommerceChannelGettersReadDefaultsAndOverrides() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            PosSettingsService service = new PosSettingsService();
            assertEquals(false, service.ecommerceEnabled());
            assertEquals("", service.ecommercePriceSource());
            assertEquals("ACCEPT", service.ecommerceForbiddenItems());
            assertEquals("", service.ecommerceUnknownItemEan());
            assertEquals("", service.ecommercePromotionChannel());
            assertEquals("", service.ecommerceIsland());
            assertEquals("ECOM", service.ecommerceTerminal());
            assertEquals("admin", service.ecommerceOperator());
            assertEquals("CARD", service.ecommerceDefaultTender());
            assertEquals(true, service.ecommerceArchive());
            assertEquals(0, service.ecommerceArchiveDays());
            assertEquals("", service.ecommerceReturnTicketTypes());
            assertEquals("", service.ecommerceReturnArticleTypes());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("channel.ecommerce.enabled", "true"),
                    row("channel.ecommerce.price-source", "pos"),
                    row("channel.ecommerce.forbidden-items", "reject"),
                    row("channel.ecommerce.unknown-item-ean", " X1 "),
                    row("channel.ecommerce.promotion-channel", "point_of_sale"),
                    row("channel.ecommerce.island", " X1 "),
                    row("channel.ecommerce.terminal", " X1 "),
                    row("channel.ecommerce.operator", " X1 "),
                    row("channel.ecommerce.default-tender", " X1 "),
                    row("channel.ecommerce.archive", "false"),
                    row("channel.ecommerce.archive-days", "7"),
                    row("channel.ecommerce.return-ticket-types", " X1 "),
                    row("channel.ecommerce.return-article-types", " X1 ")));
            PosSettingsService service = new PosSettingsService();
            assertEquals(true, service.ecommerceEnabled());
            assertEquals("POS", service.ecommercePriceSource());
            assertEquals("REJECT", service.ecommerceForbiddenItems());
            assertEquals("X1", service.ecommerceUnknownItemEan());
            assertEquals("POINT_OF_SALE", service.ecommercePromotionChannel());
            assertEquals("X1", service.ecommerceIsland());
            assertEquals("X1", service.ecommerceTerminal());
            assertEquals("X1", service.ecommerceOperator());
            assertEquals("X1", service.ecommerceDefaultTender());
            assertEquals(false, service.ecommerceArchive());
            assertEquals(7, service.ecommerceArchiveDays());
            assertEquals("X1", service.ecommerceReturnTicketTypes());
            assertEquals("X1", service.ecommerceReturnArticleTypes());
        }
    }

    /**
     * The selfScan channel getters of lot 18 answer their catalogue defaults when
     * nothing is administered, and the administered value — trimmed, and
     * upper-cased for a closed set — when a row overrides it.
     */
    @Test
    void selfScanChannelGettersReadDefaultsAndOverrides() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            PosSettingsService service = new PosSettingsService();
            assertEquals(true, service.selfScanEnabled());
            assertEquals("BOTH", service.selfScanRetrievalMode());
            assertEquals("BOTH", service.selfScanAddItems());
            assertEquals("TRANSACTION", service.selfScanIdentifier());
            assertEquals("", service.selfScanPriceSource());
            assertEquals(true, service.selfScanCoupons());
            assertEquals("", service.selfScanUnknownItemEan());
            assertEquals("", service.selfScanPromotionChannel());
            assertEquals("ACCEPT", service.selfScanForbiddenItems());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("channel.selfscan.enabled", "false"),
                    row("channel.selfscan.retrieval-mode", "scan"),
                    row("channel.selfscan.add-items", "before"),
                    row("channel.selfscan.identifier", "customer"),
                    row("channel.selfscan.price-source", "pos"),
                    row("channel.selfscan.coupons", "false"),
                    row("channel.selfscan.unknown-item-ean", " X1 "),
                    row("channel.selfscan.promotion-channel", "point_of_sale"),
                    row("channel.selfscan.forbidden-items", "reject")));
            PosSettingsService service = new PosSettingsService();
            assertEquals(false, service.selfScanEnabled());
            assertEquals("SCAN", service.selfScanRetrievalMode());
            assertEquals("BEFORE", service.selfScanAddItems());
            assertEquals("CUSTOMER", service.selfScanIdentifier());
            assertEquals("POS", service.selfScanPriceSource());
            assertEquals(false, service.selfScanCoupons());
            assertEquals("X1", service.selfScanUnknownItemEan());
            assertEquals("POINT_OF_SALE", service.selfScanPromotionChannel());
            assertEquals("REJECT", service.selfScanForbiddenItems());
        }
    }

    /**
     * The commercial channel getters of lot 18 answer their catalogue defaults when
     * nothing is administered, and the administered value — trimmed, and
     * upper-cased for a closed set — when a row overrides it.
     */
    @Test
    void commercialChannelGettersReadDefaultsAndOverrides() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            PosSettingsService service = new PosSettingsService();
            assertEquals("ACCEPT", service.commercialForbiddenItems());
            assertEquals("BOTH", service.commercialRetrievalMode());
            assertEquals("PUSH", service.commercialTransmission());
            assertEquals("", service.commercialPullUrl());
            assertEquals(15, service.commercialPullMinutes());
            assertEquals("", service.commercialAutoPrintTemplate());
            assertEquals("", service.commercialDepositTender());
            assertEquals("", service.commercialPromotionChannel());
            assertEquals("", service.commercialPriceSource());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("channel.commercial.forbidden-items", "reject"),
                    row("channel.commercial.retrieval-mode", "scan"),
                    row("channel.commercial.transmission", "pull"),
                    row("channel.commercial.pull-url", " X1 "),
                    row("channel.commercial.pull-minutes", "7"),
                    row("channel.commercial.auto-print-template", " X1 "),
                    row("channel.commercial.deposit-tender", " X1 "),
                    row("channel.commercial.promotion-channel", "point_of_sale"),
                    row("channel.commercial.price-source", "pos")));
            PosSettingsService service = new PosSettingsService();
            assertEquals("REJECT", service.commercialForbiddenItems());
            assertEquals("SCAN", service.commercialRetrievalMode());
            assertEquals("PULL", service.commercialTransmission());
            assertEquals("X1", service.commercialPullUrl());
            assertEquals(7, service.commercialPullMinutes());
            assertEquals("X1", service.commercialAutoPrintTemplate());
            assertEquals("X1", service.commercialDepositTender());
            assertEquals("POINT_OF_SALE", service.commercialPromotionChannel());
            assertEquals("POS", service.commercialPriceSource());
        }
    }

    /**
     * The qbusting channel getters of lot 18 answer their catalogue defaults when
     * nothing is administered, and the administered value — trimmed, and
     * upper-cased for a closed set — when a row overrides it.
     */
    @Test
    void qbustingChannelGettersReadDefaultsAndOverrides() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            PosSettingsService service = new PosSettingsService();
            assertEquals("BOTH", service.qbustingRetrievalMode());
            assertEquals("", service.qbustingPromotionChannel());
            assertEquals("ACCEPT", service.qbustingForbiddenItems());
            assertEquals("", service.qbustingPriceSource());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("channel.qbusting.retrieval-mode", "scan"),
                    row("channel.qbusting.promotion-channel", "point_of_sale"),
                    row("channel.qbusting.forbidden-items", "reject"),
                    row("channel.qbusting.price-source", "pos")));
            PosSettingsService service = new PosSettingsService();
            assertEquals("SCAN", service.qbustingRetrievalMode());
            assertEquals("POINT_OF_SALE", service.qbustingPromotionChannel());
            assertEquals("REJECT", service.qbustingForbiddenItems());
            assertEquals("POS", service.qbustingPriceSource());
        }
    }

    /**
     * The mobility channel getters of lot 18 answer their catalogue defaults when
     * nothing is administered, and the administered value — trimmed, and
     * upper-cased for a closed set — when a row overrides it.
     */
    @Test
    void mobilityChannelGettersReadDefaultsAndOverrides() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            PosSettingsService service = new PosSettingsService();
            assertEquals("", service.mobilityPromotionChannel());
            assertEquals("", service.mobilityPriceSource());
            assertEquals("ACCEPT", service.mobilityForbiddenItems());
            assertEquals("", service.mobilityFictitiousTender());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("channel.mobility.promotion-channel", "point_of_sale"),
                    row("channel.mobility.price-source", "pos"),
                    row("channel.mobility.forbidden-items", "reject"),
                    row("channel.mobility.fictitious-tender", " X1 ")));
            PosSettingsService service = new PosSettingsService();
            assertEquals("POINT_OF_SALE", service.mobilityPromotionChannel());
            assertEquals("POS", service.mobilityPriceSource());
            assertEquals("REJECT", service.mobilityForbiddenItems());
            assertEquals("X1", service.mobilityFictitiousTender());
        }
    }

    /**
     * The salesSlip channel getters of lot 18 answer their catalogue defaults when
     * nothing is administered, and the administered value — trimmed, and
     * upper-cased for a closed set — when a row overrides it.
     */
    @Test
    void salesSlipChannelGettersReadDefaultsAndOverrides() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            PosSettingsService service = new PosSettingsService();
            assertEquals("BOTH", service.salesSlipRetrievalMode());
            assertEquals("", service.salesSlipPriceSource());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("channel.sales-slip.retrieval-mode", "scan"),
                    row("channel.sales-slip.price-source", "pos")));
            PosSettingsService service = new PosSettingsService();
            assertEquals("SCAN", service.salesSlipRetrievalMode());
            assertEquals("POS", service.salesSlipPriceSource());
        }
    }

    // ---- lot 18: F ----

    /**
     * Lot 18 group F: every new parameter reads its catalog default on a
     * register nobody has parameterised.
     */
    @Test
    void lot18GroupFDefaults() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.drawerOpenInTraining());
            assertFalse(service.unknownScanManualEntry());
            assertEquals(java.math.RoundingMode.HALF_UP, service.calcRoundingMode());
            assertEquals(2, service.invoiceLineTotalExcludingTaxDecimals());
            assertEquals(4, service.pinLength());
            assertEquals("CODE_PASSWORD", service.supervisorValidationMode());
            assertTrue(service.saleGroupLines());
            assertEquals("", service.forbiddenSaleMessage());
            assertEquals("API_FTP", service.balanceTicketMode());
            assertTrue(service.balanceTicketEnabled());
            assertTrue(service.balanceTicketApiAccepted());
            assertTrue(service.balanceTicketFtpPolled());
            assertTrue(service.referentialDeferDuringSale());
            assertTrue(service.gs1Enabled());
            assertEquals("", service.gs1AiAlerts());
        }
    }

    /**
     * Lot 18 group F: administered values are read, the rounding rule and the
     * retrieval mode tolerantly, the PIN length and the line decimals clamped.
     */
    @Test
    void lot18GroupFAdministeredValues() {
        PosSettingsService service = org.mockito.Mockito.spy(new PosSettingsService());
        org.mockito.Mockito.doReturn("false").when(service).value("drawer.open-in-training");
        org.mockito.Mockito.doReturn("true").when(service).value("scan.unknown-manual-entry");
        org.mockito.Mockito.doReturn("3").when(service).value("invoice.decimals-line-total-ht");
        org.mockito.Mockito.doReturn("DALLAS").when(service).value("supervisor.validation-mode");
        org.mockito.Mockito.doReturn("false").when(service).value("sale.group-lines");
        org.mockito.Mockito.doReturn("RETRAIT").when(service).value("sale.forbidden-message");
        org.mockito.Mockito.doReturn("false").when(service).value("referential.defer-during-sale");
        org.mockito.Mockito.doReturn("false").when(service).value("gs1.enabled");
        org.mockito.Mockito.doReturn("10=INFO").when(service).value("gs1.ai-alerts");
        assertFalse(service.drawerOpenInTraining());
        assertTrue(service.unknownScanManualEntry());
        assertEquals(3, service.invoiceLineTotalExcludingTaxDecimals());
        assertEquals("DALLAS", service.supervisorValidationMode());
        assertFalse(service.saleGroupLines());
        assertEquals("RETRAIT", service.forbiddenSaleMessage());
        assertFalse(service.referentialDeferDuringSale());
        assertFalse(service.gs1Enabled());
        assertEquals("10=INFO", service.gs1AiAlerts());
        java.util.Map<String, java.math.RoundingMode> modes = new java.util.LinkedHashMap<>();
        modes.put(" half_even ", java.math.RoundingMode.HALF_EVEN);
        modes.put("HALF_DOWN", java.math.RoundingMode.HALF_DOWN);
        modes.put("UP", java.math.RoundingMode.UP);
        modes.put("DOWN", java.math.RoundingMode.DOWN);
        modes.put("UNNECESSARY", java.math.RoundingMode.HALF_UP);
        for (java.util.Map.Entry<String, java.math.RoundingMode> mode : modes.entrySet()) {
            org.mockito.Mockito.doReturn(mode.getKey()).when(service).value("calc.rounding-mode");
            assertEquals(mode.getValue(), service.calcRoundingMode(), mode.getKey());
        }
        org.mockito.Mockito.doReturn(null).when(service).value("calc.rounding-mode");
        assertEquals(java.math.RoundingMode.HALF_UP, service.calcRoundingMode());
        org.mockito.Mockito.doReturn("2").when(service).value("auth.pin-length");
        assertEquals(4, service.pinLength());
        org.mockito.Mockito.doReturn("12").when(service).value("auth.pin-length");
        assertEquals(8, service.pinLength());
        org.mockito.Mockito.doReturn("6").when(service).value("auth.pin-length");
        assertEquals(6, service.pinLength());
    }

    /**
     * Lot 18 group F (BO-10-05-01): the four retrieval modes of the counter
     * tickets, an unknown or missing one reading as API_FTP.
     */
    @Test
    void lot18GroupFBalanceTicketModes() {
        PosSettingsService service = org.mockito.Mockito.spy(new PosSettingsService());
        org.mockito.Mockito.doReturn(" api ").when(service).value("balance.ticket-mode");
        assertEquals("API", service.balanceTicketMode());
        assertTrue(service.balanceTicketApiAccepted());
        assertFalse(service.balanceTicketFtpPolled());
        org.mockito.Mockito.doReturn("FTP").when(service).value("balance.ticket-mode");
        assertFalse(service.balanceTicketApiAccepted());
        assertTrue(service.balanceTicketFtpPolled());
        assertTrue(service.balanceTicketEnabled());
        org.mockito.Mockito.doReturn("OFF").when(service).value("balance.ticket-mode");
        assertFalse(service.balanceTicketEnabled());
        assertFalse(service.balanceTicketApiAccepted());
        assertFalse(service.balanceTicketFtpPolled());
        org.mockito.Mockito.doReturn("BOTH").when(service).value("balance.ticket-mode");
        assertEquals("API_FTP", service.balanceTicketMode());
        org.mockito.Mockito.doReturn(null).when(service).value("balance.ticket-mode");
        assertEquals("API_FTP", service.balanceTicketMode());
    }

    // ---- lot 18: G ----

    /**
     * Lot 18 group G: the loyalty parameters project their administered rows —
     * pot balances (BO-10-02-33), payment tender trimmed (BO-10-03-06), password
     * kept verbatim (BO-10-03-08), both timeouts (BO-10-03-10) and the order
     * date (BO-10-03-17).
     */
    @Test
    void lot18GroupGLoyaltyParametersProjectTheirRows() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("fidelity.pot-balances-enabled", "true"),
                    row("fidelity.payment-tender", " VOUCHER "),
                    row("fidelity.password", " s3cret "),
                    row("fidelity.connect-timeout-ms", "700"),
                    row("fidelity.read-timeout-ms", "900"),
                    row("fidelity.order-date-enabled", "true")));
            assertTrue(service.fidelityPotBalancesEnabled());
            assertEquals("VOUCHER", service.fidelityPaymentTender());
            assertEquals(" s3cret ", service.fidelityPassword());
            assertEquals(700, service.fidelityConnectTimeoutMs());
            assertEquals(900, service.fidelityReadTimeoutMs());
            assertTrue(service.fidelityOrderDateEnabled());
        }
    }

    /**
     * Lot 18 group G: without any row the loyalty parameters fall back on their
     * defaults — nothing requested, the FIDELITY tender, no administered
     * password, the historical 1500/2500 ms and no order date.
     */
    @Test
    void lot18GroupGLoyaltyParametersDefault() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertFalse(service.fidelityPotBalancesEnabled());
            assertEquals("FIDELITY", service.fidelityPaymentTender());
            assertEquals("", service.fidelityPassword());
            assertEquals(1500, service.fidelityConnectTimeoutMs());
            assertEquals(2500, service.fidelityReadTimeoutMs());
            assertFalse(service.fidelityOrderDateEnabled());
        }
    }

    /**
     * Lot 18 group G: a blank tender answers FIDELITY and a non-positive
     * timeout answers the historical value (BO-10-03-06, BO-10-03-10).
     */
    @Test
    void lot18GroupGBlankTenderAndZeroTimeoutsFallBack() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("fidelity.payment-tender", "  "),
                    row("fidelity.connect-timeout-ms", "0"),
                    row("fidelity.read-timeout-ms", "-5")));
            assertEquals("FIDELITY", service.fidelityPaymentTender());
            assertEquals(1500, service.fidelityConnectTimeoutMs());
            assertEquals(2500, service.fidelityReadTimeoutMs());
        }
    }

    /**
     * Lot 18 group G: only the loyalty password is a secret the back office
     * masks, and a null key is none (BO-10-03-08).
     */
    @Test
    void lot18GroupGOnlyThePasswordIsSecret() {
        assertTrue(PosSettingsService.isSecret("fidelity.password"));
        assertFalse(PosSettingsService.isSecret("fidelity.user"));
        assertFalse(PosSettingsService.isSecret(null));
        assertEquals(Set.of("fidelity.password", "fiscal.at-password"), PosSettingsService.SECRET_KEYS);
        assertTrue(PosSettingsService.isSecret("fiscal.at-password"));
    }

    // ---- lot 18: H ----

    /**
     * Builds a service whose stored overrides are the rows given (lot 18, group H).
     *
     * @param rows the static mock of the Panache finders
     * @param overrides the stored rows
     * @return a fresh service reading those rows
     */
    private PosSettingsService groupHService(MockedStatic<PanacheEntityBase> rows, PosSetting... overrides) {
        rows.when(PanacheEntityBase::listAll).thenReturn(List.of(overrides));
        return new PosSettingsService();
    }

    /** BO-10-06-01/02: FS and NC carry their administered SAF-T identifier, the other types none. */
    @Test
    void saftDocumentIdentifiersFollowTheirType() {
        try (var rows = mockStatic(PanacheEntityBase.class)) {
            var service = groupHService(rows);
            assertEquals(1, service.saftDocumentId("FS"));
            assertEquals(2, service.saftDocumentId("NC"));
            assertNull(service.saftDocumentId("FT"));
            assertNull(service.saftDocumentId(null));
            service = groupHService(rows, row("fiscal.saft-id-fs", "7"), row("fiscal.saft-id-nc", "8"));
            assertEquals(7, service.saftDocumentId("FS"));
            assertEquals(8, service.saftDocumentId("NC"));
        }
    }

    /** BO-10-06-05/06: the eco-taxes always hold the built-in attribute, then the declared ones. */
    @Test
    void ecoTaxesParseTheAdministeredList() {
        try (var rows = mockStatic(PanacheEntityBase.class)) {
            var service = groupHService(rows);
            assertEquals(List.of(new com.intermarche.edge.domain.catalog.attribute.EcoTaxDefinition("ECO_TAX", null, true)),
                    service.ecoTaxes());
            service = groupHService(rows, row("ecotax.attributes", "DEEE=Eco DEEE>NOPRINT"));
            assertEquals(2, service.ecoTaxes().size());
            assertEquals(new com.intermarche.edge.domain.catalog.attribute.EcoTaxDefinition("DEEE", "Eco DEEE", false),
                    service.ecoTaxes().get(1));
        }
    }

    /** BO-10-07-05: the cheque words are trimmed, blank meaning the language's own. */
    @Test
    void chequeWordsAreTrimmedOrAbsent() {
        try (var rows = mockStatic(PanacheEntityBase.class)) {
            var service = groupHService(rows);
            assertNull(service.chequeUnitText());
            assertNull(service.chequeCentText());
            service = groupHService(rows, row("cheque.unit-text", " Euro(s) "), row("cheque.cent-text", "Cent(s)"));
            assertEquals("Euro(s)", service.chequeUnitText());
            assertEquals("Cent(s)", service.chequeCentText());
        }
    }

    /** BO-10-07-13/15, BO-10-07-19, BO-10-08-30/31: the switches keep their catalog defaults and honour overrides. */
    @Test
    void groupHSwitchesHaveTheirDefaults() {
        try (var rows = mockStatic(PanacheEntityBase.class)) {
            var service = groupHService(rows);
            assertTrue(service.eftLogosPrinted());
            assertFalse(service.ticketFamilySubtotals());
            assertFalse(service.ticketBoldAmounts());
            assertFalse(service.offlineDisplayEnabled());
            assertFalse(service.cancelledLinesPrinted());
            assertFalse(service.identicalLinesDetailed());
            service = groupHService(rows, row("ticket.eft-logos", "false"), row("ticket.family-subtotals", "true"),
                    row("ticket.bold-amounts", "true"), row("display.offline-enabled", "true"),
                    row("ticket.cancelled-lines-print", "true"), row("ticket.identical-lines-detailed", "true"));
            assertFalse(service.eftLogosPrinted());
            assertTrue(service.ticketFamilySubtotals());
            assertTrue(service.ticketBoldAmounts());
            assertTrue(service.offlineDisplayEnabled());
            assertTrue(service.cancelledLinesPrinted());
            assertTrue(service.identicalLinesDetailed());
        }
    }

    /** BO-10-07-17: the customer display's first language and its translated messages. */
    @Test
    void customerDisplayLanguagesAreAdministered() {
        try (var rows = mockStatic(PanacheEntityBase.class)) {
            var service = groupHService(rows);
            assertEquals("", service.customerDisplayLanguage());
            assertNull(service.customerOpenMessageSecond());
            assertNull(service.customerClosedMessageSecond());
            service = groupHService(rows, row("language.customer-display", "nl"),
                    row("customer.message-open-second", " Welkom "), row("customer.message-closed-second", "Gesloten"));
            assertEquals("nl", service.customerDisplayLanguage());
            assertEquals("Welkom", service.customerOpenMessageSecond());
            assertEquals("Gesloten", service.customerClosedMessageSecond());
        }
    }

    /** BO-10-07-19: the offline delay is never negative. */
    @Test
    void offlineDelayIsNeverNegative() {
        try (var rows = mockStatic(PanacheEntityBase.class)) {
            assertEquals(60, groupHService(rows).offlineDisplayDelaySeconds());
            assertEquals(0, groupHService(rows, row("display.offline-delay-seconds", "-5")).offlineDisplayDelaySeconds());
            assertEquals(10, groupHService(rows, row("display.offline-delay-seconds", "10")).offlineDisplayDelaySeconds());
        }
    }

    /** BO-10-08-28/29: the decimals stay between 0 and 4 and the weight symbol is never blank. */
    @Test
    void unitWritingIsBounded() {
        try (var rows = mockStatic(PanacheEntityBase.class)) {
            var service = groupHService(rows);
            assertEquals(2, service.measureDecimals());
            assertEquals(3, service.weightDecimals());
            assertEquals("kg", service.weightSymbol());
            assertEquals(new com.intermarche.edge.domain.catalog.UnitFormat(2, 3, "kg"), service.unitFormat());
            service = groupHService(rows, row("unit.measure-decimals", "4"), row("unit.weight-decimals", "0"),
                    row("unit.weight-symbol", " KG "));
            assertEquals(new com.intermarche.edge.domain.catalog.UnitFormat(4, 0, "KG"), service.unitFormat());
            service = groupHService(rows, row("unit.measure-decimals", "5"), row("unit.weight-decimals", "-1"),
                    row("unit.weight-symbol", " "));
            assertEquals(new com.intermarche.edge.domain.catalog.UnitFormat(2, 3, "kg"), service.unitFormat());
        }
    }

    /** BO-10-02-18: the rounding mode is read case-insensitively, anything else being half-up. */
    @Test
    void roundingModeFallsBackToHalfUp() {
        try (var rows = mockStatic(PanacheEntityBase.class)) {
            assertEquals(java.math.RoundingMode.HALF_UP, groupHService(rows).calcRoundingMode());
            assertEquals(java.math.RoundingMode.HALF_EVEN,
                    groupHService(rows, row("calc.rounding-mode", " half_even ")).calcRoundingMode());
            assertEquals(java.math.RoundingMode.HALF_UP,
                    groupHService(rows, row("calc.rounding-mode", "BOGUS")).calcRoundingMode());
            assertEquals(java.math.RoundingMode.HALF_UP,
                    groupHService(rows, row("calc.rounding-mode", " ")).calcRoundingMode());
        }
    }

    // ---- lot 18: I ----

    /** BO-08 / BO-10-08-02: the supervision and deployment parameters answer their catalog defaults. */
    @Test
    void lot18SupervisionAndDeploymentDefaults() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(60, service.supervisionReportSeconds());
            assertTrue(service.functionUsageEnabled());
            assertTrue(service.remoteMaintenanceEnabled());
            assertTrue(service.integrationRetryEnabled());
            assertEquals(10, service.integrationRetryMinutes());
            assertEquals(5, service.integrationRetryMaxAttempts());
            assertFalse(service.mobileAlertsEnabled());
            assertTrue(service.deploymentAutoUpdateAtStartup());
            assertEquals(3, service.deploymentMaxAttempts());
            assertTrue(service.deploymentRollbackEnabled());
        }
    }

    /** BO-08 / BO-10-08-02: stored overrides reach the supervision and deployment getters. */
    @Test
    void lot18SupervisionAndDeploymentOverrides() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("supervision.report-seconds", "30"),
                    row("supervision.function-usage-enabled", "false"),
                    row("supervision.remote-maintenance-enabled", "false"),
                    row("integration.retry-enabled", "false"),
                    row("integration.retry-minutes", "2"),
                    row("integration.retry-max-attempts", "7"),
                    row("dashboard.mobile-alerts-enabled", "true"),
                    row("deployment.auto-update-at-startup", "false"),
                    row("deployment.max-attempts", "4"),
                    row("deployment.rollback-enabled", "false")));
            assertEquals(30, service.supervisionReportSeconds());
            assertFalse(service.functionUsageEnabled());
            assertFalse(service.remoteMaintenanceEnabled());
            assertFalse(service.integrationRetryEnabled());
            assertEquals(2, service.integrationRetryMinutes());
            assertEquals(7, service.integrationRetryMaxAttempts());
            assertTrue(service.mobileAlertsEnabled());
            assertFalse(service.deploymentAutoUpdateAtStartup());
            assertEquals(4, service.deploymentMaxAttempts());
            assertFalse(service.deploymentRollbackEnabled());
        }
    }

    /** BO-08: an absurd cadence, delay or attempt count falls back to a strictly positive value. */
    @Test
    void lot18NonPositiveValuesFallBack() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("supervision.report-seconds", "0"),
                    row("integration.retry-minutes", "-1"),
                    row("integration.retry-max-attempts", "0"),
                    row("deployment.max-attempts", "0")));
            assertEquals(60, service.supervisionReportSeconds());
            assertEquals(10, service.integrationRetryMinutes());
            assertEquals(5, service.integrationRetryMaxAttempts());
            assertEquals(3, service.deploymentMaxAttempts());
        }
    }

    // ---- lot 19: K ----

    /** BO-03-09/BO-03-10: with nothing stored, every lot-19 K getter answers its catalog default. */
    @Test
    void lot19PointOfContactDefaults() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("", service.ticketHeaderTemplate());
            assertEquals("", service.ticketFooterTemplate());
            assertEquals("COMBINED", service.vatTableMode());
            assertEquals("", service.logoCode());
            assertEquals("", service.menuCode());
            assertFalse(service.singleQueueEnabled());
            assertEquals("", service.singleQueueUrl());
            assertTrue(service.hardwareEnabled("drawer"));
            assertEquals("", service.drawerAddress());
            assertEquals("AUTO", service.scannerDriver());
            assertEquals("", service.scannerNamePatterns());
            assertEquals("", service.scannerOposDevice());
            assertFalse(service.secondScannerEnabled());
            assertEquals("", service.secondScannerNamePatterns());
            assertEquals("ESCPOS", service.printerType());
            assertEquals(0, service.printerSpeed());
            assertEquals(858, service.printerCodePage());
            assertEquals(80, service.printerPaperWidth());
            assertFalse(service.chequeFlip());
            assertFalse(service.secondPrinterEnabled());
            assertEquals("A4", service.secondPrinterFormat());
            assertEquals("NETWORK", service.secondPrinterConnection());
            assertEquals("", service.secondPrinterAddress());
            assertEquals("", service.paymentTerminalType());
            assertEquals("", service.paymentTerminalAddress());
            assertEquals("", service.islandPaymentLabel());
            assertFalse(service.catalinaEnabled());
            assertFalse(service.planexaExportEnabled());
        }
    }

    /** BO-03-09/BO-03-10: stored values reach every lot-19 K getter, trimmed and normalised. */
    @Test
    void lot19PointOfContactOverrides() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("ticket.header-template", " HEAD1 "),
                    row("ticket.footer-template", " FOOT1 "),
                    row("ticket.vat-table", " separate "),
                    row("print.logo-code", " LOGO2 "),
                    row("menu.code", " SLS "),
                    row("queue.single-enabled", "true"),
                    row("queue.single-url", " http://queue/state "),
                    row("hardware.drawer.enabled", "false"),
                    row("hardware.drawer.address", " 0x48F "),
                    row("hardware.scanner.driver", "opos"),
                    row("hardware.scanner.name-patterns", " datalogic "),
                    row("hardware.scanner.opos-device", " DLS_Magellan "),
                    row("hardware.scanner2.enabled", "true"),
                    row("hardware.scanner2.name-patterns", " zebra "),
                    row("hardware.printer.type", "epson_tm"),
                    row("hardware.printer.speed", "38400"),
                    row("hardware.printer.code-page", "1252"),
                    row("hardware.printer.paper-width", "58"),
                    row("hardware.printer.cheque-flip", "true"),
                    row("printer2.enabled", "true"),
                    row("printer2.format", "ticket"),
                    row("printer2.connection", "local"),
                    row("printer2.address", " A4-BUREAU "),
                    row("payment.terminal-type", "worldline"),
                    row("payment.terminal-address", " 10.0.0.5:8200 "),
                    row("terminal.island-label", " ILOT1 "),
                    row("catalina.enabled", "true"),
                    row("export.planexa-enabled", "true")));
            assertEquals("HEAD1", service.ticketHeaderTemplate());
            assertEquals("FOOT1", service.ticketFooterTemplate());
            assertEquals("SEPARATE", service.vatTableMode());
            assertEquals("LOGO2", service.logoCode());
            assertEquals("SLS", service.menuCode());
            assertTrue(service.singleQueueEnabled());
            assertEquals("http://queue/state", service.singleQueueUrl());
            assertFalse(service.hardwareEnabled("drawer"));
            assertEquals("0x48F", service.drawerAddress());
            assertEquals("OPOS", service.scannerDriver());
            assertEquals("datalogic", service.scannerNamePatterns());
            assertEquals("DLS_Magellan", service.scannerOposDevice());
            assertTrue(service.secondScannerEnabled());
            assertEquals("zebra", service.secondScannerNamePatterns());
            assertEquals("EPSON_TM", service.printerType());
            assertEquals(38400, service.printerSpeed());
            assertEquals(1252, service.printerCodePage());
            assertEquals(58, service.printerPaperWidth());
            assertTrue(service.chequeFlip());
            assertTrue(service.secondPrinterEnabled());
            assertEquals("TICKET", service.secondPrinterFormat());
            assertEquals("LOCAL", service.secondPrinterConnection());
            assertEquals("A4-BUREAU", service.secondPrinterAddress());
            assertEquals("WORLDLINE", service.paymentTerminalType());
            assertEquals("10.0.0.5:8200", service.paymentTerminalAddress());
            assertEquals("ILOT1", service.islandPaymentLabel());
            assertTrue(service.catalinaEnabled());
            assertTrue(service.planexaExportEnabled());
        }
    }

    /** BO-03-09-03/16/19/21: unreadable choices fall back, absurd numbers are bounded. */
    @Test
    void lot19PointOfContactFallbacks() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("ticket.vat-table", "TOTAL"),
                    row("ticket.vat-breakdown-enabled", "false"),
                    row("hardware.scanner.driver", "SERIAL"),
                    row("hardware.printer.type", "DOT_MATRIX"),
                    row("hardware.printer.speed", "-5"),
                    row("hardware.printer.code-page", "0"),
                    row("hardware.printer.paper-width", "76"),
                    row("payment.terminal-type", "PAX")));
            assertEquals("AMOUNT_ONLY", service.vatTableMode());
            assertEquals("AUTO", service.scannerDriver());
            assertEquals("ESCPOS", service.printerType());
            assertEquals(0, service.printerSpeed());
            assertEquals(858, service.printerCodePage());
            assertEquals(80, service.printerPaperWidth());
            assertEquals("", service.paymentTerminalType());
        }
    }

    /** BO-03-09-20: the Catalina activation is resolved for the register that rang the sale. */
    @Test
    void lot19CatalinaIsResolvedPerTerminal() {
        EchelonSettingService engine = mock(EchelonSettingService.class);
        when(engine.resolveForTerminal("12345", "C04")).thenReturn(Map.of("catalina.enabled", "true"));
        when(engine.resolveForTerminal("12345", "C05")).thenReturn(Map.of());
        PosSettingsService service = wired(engine, Optional.of("12345"));
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.catalinaEnabledFor("C04"));
            assertFalse(service.catalinaEnabledFor("C05"));
        }
    }

    // ---- lot 19: L ----

    /**
     * BO-03-02-24 / BO-03-13-11 (lot 19 L2): with nothing administered no
     * cheque guarantee is named, the delay is 3000 ms and no deposit article
     * exists; administered, the values are trimmed, the delay floored at
     * 500 ms and the deposit articles split, blanks dropped.
     */
    @Test
    void lot19L2GettersFollowTheirRows() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("", service.chequeGuaranteeProvider());
            assertEquals("", service.chequeGuaranteeUrl());
            assertEquals(3000, service.chequeGuaranteeTimeoutMillis());
            assertEquals(List.of(), service.depositArticleEans());
        }
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("cheque.guarantee-provider", " SIMULATEUR "),
                    row("cheque.guarantee-url", " http://localhost:8080 "),
                    row("cheque.guarantee-timeout-ms", "100"),
                    row("deposit.article-eans", " 3000000000017 ; ;3000000000024")));
            assertEquals("SIMULATEUR", administered.chequeGuaranteeProvider());
            assertEquals("http://localhost:8080", administered.chequeGuaranteeUrl());
            assertEquals(500, administered.chequeGuaranteeTimeoutMillis());
            assertEquals(List.of("3000000000017", "3000000000024"), administered.depositArticleEans());
        }
    }

    /**
     * BO-03-02-34/-50: the card receipt is printed and the release of the
     * forced degraded mode is endorsed by default; both follow their rows.
     */
    @Test
    void lot19LGettersFollowTheirRows() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertTrue(service.cardReceiptPrinted());
            assertTrue(service.moneticsDegradedReleaseEndorsement());
            assertEquals(15, service.moneticsDegradedForcedMinutes());
        }
        PosSettingsService administered = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("payment.card-receipt-printed", "false"),
                    row("payment.degraded-release-endorsement", "false")));
            assertFalse(administered.cardReceiptPrinted());
            assertFalse(administered.moneticsDegradedReleaseEndorsement());
        }
    }

    // ---- lot 19: M ----

    /**
     * Lot 19 M: with nothing administered, every new parameter answers its
     * catalog default — the register prints as before, offers the validation
     * step and lets five commercial messages through.
     */
    @Test
    void lot19MDefaultsKeepTheHistoricalBehaviour() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals(0, service.ticketFamilyLevel());
            assertEquals("ENGINE", service.ticketPromotionPlacement());
            assertEquals(List.of(), service.invoiceArticleAttributes());
            assertEquals(25, service.invoiceA4LinesPerPage());
            assertFalse(service.invoiceAccountNumberRequired());
            assertTrue(service.invoiceCustomerValidation());
            assertTrue(service.fidelityShortMessagePrinted());
            assertEquals(5, service.fidelityCommercialMessagesMax());
        }
    }

    /**
     * Lot 19 M: administered values are read typed — the level, both
     * placements, the attribute list trimmed and deduplicated, the page size,
     * the three switches and a message count inside its bounds.
     */
    @Test
    void lot19MAdministeredValuesAreRead() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("ticket.family-level", "2"),
                    row("ticket.promotion-placement", " line "),
                    row("invoice.article-attributes", " ORIGINE ;;CATEGORIE;ORIGINE; "),
                    row("invoice.a4-lines-per-page", "12"),
                    row("invoice.account-number-required", "true"),
                    row("invoice.customer-validation", "false"),
                    row("fidelity.short-message-printed", "false"),
                    row("fidelity.commercial-messages-max", "3")));
            assertEquals(2, service.ticketFamilyLevel());
            assertEquals("LINE", service.ticketPromotionPlacement());
            assertEquals(List.of("ORIGINE", "CATEGORIE"), service.invoiceArticleAttributes());
            assertEquals(12, service.invoiceA4LinesPerPage());
            assertTrue(service.invoiceAccountNumberRequired());
            assertFalse(service.invoiceCustomerValidation());
            assertFalse(service.fidelityShortMessagePrinted());
            assertEquals(3, service.fidelityCommercialMessagesMax());
        }
    }

    /**
     * Lot 19 M: out-of-bound values are brought back — a negative level or
     * page size reads zero, FOOTER is kept, an unknown placement reads ENGINE,
     * and the message count is held between zero and five.
     */
    @Test
    void lot19MOutOfBoundValuesAreBroughtBack() {
        PosSettingsService low = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("ticket.family-level", "-1"),
                    row("ticket.promotion-placement", "footer"),
                    row("invoice.a4-lines-per-page", "-3"),
                    row("fidelity.commercial-messages-max", "-2")));
            assertEquals(0, low.ticketFamilyLevel());
            assertEquals("FOOTER", low.ticketPromotionPlacement());
            assertEquals(0, low.invoiceA4LinesPerPage());
            assertEquals(0, low.fidelityCommercialMessagesMax());
        }
        PosSettingsService high = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("ticket.promotion-placement", "AILLEURS"),
                    row("fidelity.commercial-messages-max", "9")));
            assertEquals("ENGINE", high.ticketPromotionPlacement());
            assertEquals(5, high.fidelityCommercialMessagesMax());
        }
    }

    /**
     * BO-03-04-27: a validation code shorter than eight characters is not the
     * administration's code and reads as none; eight characters are enough.
     */
    @Test
    void lot19MAShortAtcudCodeIsRefused() {
        PosSettingsService service = new PosSettingsService();
        try (MockedStatic<PanacheEntityBase> rows = mockStatic(PanacheEntityBase.class)) {
            rows.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("fiscal.atcud-codes", "FS:ABCDEFG;FT:ABCDEFGH")));
            assertEquals("0", service.atcudCode("FS"));
            assertEquals("ABCDEFGH", service.atcudCode("FT"));
        }
    }

    // ---- lot 19: N ----

    /**
     * Lot 19 N getters: the article order defaults to alphabetical and the
     * engine call to on, both readable when administered; the sales-volume
     * window floors a value below one to its default and keeps a positive one
     * (BO-03-01-10, BO-03-01-13, BO-03-06-42).
     */
    @Test
    void lot19GroupNGettersReadTheirParameters() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("touch.sales-volume-days", "0")));
            PosSettingsService service = new PosSettingsService();
            assertFalse(service.touchArticleSortVolume());
            assertFalse(service.valuationLocalOnly());
            assertEquals(30, service.touchSalesVolumeDays());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("touch.article-sort-volume", "true"),
                    row("valuation.local-only", "true"),
                    row("touch.sales-volume-days", "7")));
            PosSettingsService service = new PosSettingsService();
            assertTrue(service.touchArticleSortVolume());
            assertTrue(service.valuationLocalOnly());
            assertEquals(7, service.touchSalesVolumeDays());
        }
    }

    // ---- lot 20: P ----

    /**
     * The group P getters read their administered values: the complementary
     * suggestions and their count, the meal-voucher rule, the deposit control
     * and the EEG flow (BO-02-03-06, -16, -24, -25, -37, -38).
     */
    @Test
    void lot20GroupPGettersReadAdministeredValues() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("article.complementary-suggestions", "false"),
                    row("article.complementary-max", "4"),
                    row("article.meal-voucher-eligible-only", "false"),
                    row("article.deposit-control", "false"),
                    row("eeg.enabled", "true")));
            PosSettingsService service = new PosSettingsService();
            assertFalse(service.articleComplementarySuggestions());
            assertEquals(4, service.articleComplementaryMax());
            assertFalse(service.mealVoucherEligibleOnly());
            assertFalse(service.depositControlEnabled());
            assertTrue(service.eegEnabled());
        }
    }

    /**
     * The group P getters fall back on their catalog defaults, and the
     * complementary count is clamped to one to ten.
     */
    @Test
    void lot20GroupPGettersDefaultAndClamp() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("article.complementary-max", "40")));
            PosSettingsService service = new PosSettingsService();
            assertTrue(service.articleComplementarySuggestions());
            assertEquals(10, service.articleComplementaryMax());
            assertTrue(service.mealVoucherEligibleOnly());
            assertTrue(service.depositControlEnabled());
            assertFalse(service.eegEnabled());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("article.complementary-max", "0")));
            assertEquals(1, new PosSettingsService().articleComplementaryMax());
        }
    }

    // ---- lot 20: Q ----

    /**
     * BO-02-04-13: the uplink address is read trimmed, empty by default, and the
     * replay delay is bounded between 5 and 3600 seconds, 60 by default.
     */
    @Test
    void customerUplinkSettingsAreReadAndBounded() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            PosSettingsService service = new PosSettingsService();
            assertEquals("", service.customerUplinkUrl());
            assertEquals(60, service.customerUplinkSeconds());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("channel.commercial.customer-uplink-url", " http://gc/clients "),
                    row("channel.commercial.customer-uplink-seconds", "2")));
            PosSettingsService service = new PosSettingsService();
            assertEquals("http://gc/clients", service.customerUplinkUrl());
            assertEquals(5, service.customerUplinkSeconds());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("channel.commercial.customer-uplink-seconds", "9999")));
            assertEquals(3600, new PosSettingsService().customerUplinkSeconds());
        }
    }

    // ---- lot 20: R ----

    /**
     * The lot 20 group R getters read their parameters: defaults first, then
     * administered values, the script names trimmed and a hook duration
     * below one falling back on the catalog default.
     */
    @Test
    void lot20GroupRGettersReadTheirParameters() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("period.hook-timeout-seconds", "0")));
            PosSettingsService service = new PosSettingsService();
            assertFalse(service.integrationTokenRequired());
            assertTrue(service.ecommerceFidelityEarn());
            assertTrue(service.periodAutoLaunch());
            assertTrue(service.periodParkedRecord());
            assertFalse(service.periodParkedRaz());
            assertTrue(service.periodForcedCloseTicket());
            assertFalse(service.periodScoAutoDeclaration());
            assertFalse(service.periodHooksEnabled());
            assertEquals("", service.periodHookBefore());
            assertEquals("", service.periodHookAfter());
            assertEquals(60, service.periodHookTimeoutSeconds());
            assertFalse(service.periodRegisterCheck());
            assertEquals("", service.periodReportsPrinter());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("channel.integration.token-required", "true"),
                    row("channel.ecommerce.fidelity-earn", "false"),
                    row("period.auto-launch", "false"),
                    row("period.parked-record", "false"),
                    row("period.parked-raz", "true"),
                    row("period.forced-close-ticket", "false"),
                    row("period.sco-auto-declaration", "true"),
                    row("period.hooks-enabled", "true"),
                    row("period.hook-before", " before.sh "),
                    row("period.hook-after", " after.sh "),
                    row("period.hook-timeout-seconds", "5"),
                    row("period.register-check", "true"),
                    row("period.reports-printer", " printer:9100 ")));
            PosSettingsService service = new PosSettingsService();
            assertTrue(service.integrationTokenRequired());
            assertFalse(service.ecommerceFidelityEarn());
            assertFalse(service.periodAutoLaunch());
            assertFalse(service.periodParkedRecord());
            assertTrue(service.periodParkedRaz());
            assertFalse(service.periodForcedCloseTicket());
            assertTrue(service.periodScoAutoDeclaration());
            assertTrue(service.periodHooksEnabled());
            assertEquals("before.sh", service.periodHookBefore());
            assertEquals("after.sh", service.periodHookAfter());
            assertEquals(5, service.periodHookTimeoutSeconds());
            assertTrue(service.periodRegisterCheck());
            assertEquals("printer:9100", service.periodReportsPrinter());
        }
    }

    // ---- lot 21: S ----

    /**
     * The lot 21 group S getters read their parameters: defaults first, then
     * administered values, the lists trimmed and their blanks dropped, the
     * revenue detail size bounded (BO-04-01-48, BO-04-02-01/12, BO-04-03-01/04/07/08/09/10/12).
     */
    @Test
    void lot21GroupSGettersReadTheirParameters() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("control.revenue-top", "0")));
            PosSettingsService service = new PosSettingsService();
            assertEquals(List.of("4"), service.secondaryMoneticServers());
            assertEquals("", service.endorsementProfiles());
            assertEquals("", service.controlAlertRules());
            assertEquals("POST /abandon=CANCEL_TICKET\nPOST /action/price-mod/submit=PRICE_MOD",
                    service.justificationRoutes());
            assertTrue(service.controlIndicatorsBar());
            assertEquals(1, service.controlRevenueLevel());
            assertEquals(1, service.controlRevenueTop());
            assertEquals("", service.watchedMenus());
            assertTrue(service.globalAlertsEnabled());
            assertEquals(12, service.liveEventTypes().size());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("journal.secondary-monetic-servers", " 2 ; ;S2"),
                    row("auth.endorsement-profiles", "Chef"),
                    row("control.alert-rules", "offline=BLOCK"),
                    row("justification.function-routes", "POST /a=A"),
                    row("control.indicators-bar", "false"),
                    row("control.revenue-level", "2"),
                    row("control.revenue-top", "99"),
                    row("audit.watched-menus", "*"),
                    row("supervision.global-alerts", "false"),
                    row("supervision.live-event-types", " TICKET_CLOSED ; ")));
            PosSettingsService service = new PosSettingsService();
            assertEquals(List.of("2", "S2"), service.secondaryMoneticServers());
            assertEquals("Chef", service.endorsementProfiles());
            assertEquals("offline=BLOCK", service.controlAlertRules());
            assertEquals("POST /a=A", service.justificationRoutes());
            assertFalse(service.controlIndicatorsBar());
            assertEquals(2, service.controlRevenueLevel());
            assertEquals(20, service.controlRevenueTop());
            assertEquals("*", service.watchedMenus());
            assertFalse(service.globalAlertsEnabled());
            assertEquals(List.of("TICKET_CLOSED"), service.liveEventTypes());
        }
    }

    // ---- lot 21: T ----

    /**
     * The lot 21 group T getters read their parameters: the catalog defaults
     * first, then administered values — lists split and trimmed with their
     * blank entries dropped, intervals bounded on both sides, the kiosk format
     * normalised and an unknown one falling back on JSON.
     */
    @Test
    void lot21GroupTGettersReadTheirParameters() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            PosSettingsService service = new PosSettingsService();
            assertEquals(List.of(), service.integrationPullFeeds());
            assertEquals(60, service.integrationPullMinutes());
            assertEquals(List.of(), service.exportUpstreamOffers());
            assertFalse(service.exportGs1PaymentsEnabled());
            assertFalse(service.gs1CentralBurn());
            assertFalse(service.vignetteEnabled());
            assertEquals(new java.math.BigDecimal("10.00"), service.vignetteAmountStep());
            assertEquals(1, service.vignettePerStep());
            assertFalse(service.exportVignettesEnabled());
            assertFalse(service.kioskEnabled());
            assertEquals("JSON", service.kioskFormat());
            assertFalse(service.eegAutoPushEnabled());
            assertEquals(300, service.eegAutoPushSeconds());
            assertFalse(service.cashChangerMovements());
            assertEquals("", service.nexoHubUrl());
            assertEquals("", service.nexoPoiId());
            assertFalse(service.nexoActivationAtPayment());
            assertFalse(service.nexoPaymentAcknowledgement());
            assertFalse(service.ecommerceValuationEnabled());
            assertFalse(service.ecommerceStatusReturn());
            assertFalse(service.fidelityVtSync());
            assertFalse(service.fidelityVtPayments());
            assertTrue(service.fidelityGtEnabled());
            assertEquals(10, service.fidelityGtSeconds());
            assertEquals("", service.fidelityGtPath());
            assertFalse(service.fidelityVt2Enabled());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("integration.pull-feeds", " PRODUCTS ; ;NOMENCLATURE=IMKT"),
                    row("integration.pull-minutes", "0"),
                    row("export.family-sales-upstream-offers", "AMONT; ;CENTRALE "),
                    row("export.gs1-payments-enabled", "true"),
                    row("gs1.central-burn", "true"),
                    row("vignette.enabled", "true"),
                    row("vignette.amount-step", "-5"),
                    row("vignette.per-step", "-2"),
                    row("export.vignettes-enabled", "true"),
                    row("kiosk.enabled", "true"),
                    row("kiosk.format", " xml "),
                    row("eeg.auto-push-enabled", "true"),
                    row("eeg.auto-push-seconds", "1"),
                    row("hardware.cashchanger.movements", "true"),
                    row("nexo.hub-url", " http://hub "),
                    row("nexo.poi-id", " POI1 "),
                    row("nexo.activation-moment", " payment "),
                    row("nexo.payment-acknowledgement", "true"),
                    row("channel.ecommerce.valuation-enabled", "true"),
                    row("channel.ecommerce.status-return", "true"),
                    row("fidelity.vt-sync", "true"),
                    row("fidelity.vt-payments", "true"),
                    row("fidelity.gt-enabled", "false"),
                    row("fidelity.gt-seconds", "1"),
                    row("fidelity.gt-path", " /api/gt "),
                    row("fidelity.vt2-enabled", "true")));
            PosSettingsService service = new PosSettingsService();
            assertEquals(List.of("PRODUCTS", "NOMENCLATURE=IMKT"), service.integrationPullFeeds());
            assertEquals(1, service.integrationPullMinutes());
            assertEquals(List.of("AMONT", "CENTRALE"), service.exportUpstreamOffers());
            assertTrue(service.exportGs1PaymentsEnabled());
            assertTrue(service.gs1CentralBurn());
            assertTrue(service.vignetteEnabled());
            assertEquals(java.math.BigDecimal.ZERO, service.vignetteAmountStep());
            assertEquals(0, service.vignettePerStep());
            assertTrue(service.exportVignettesEnabled());
            assertTrue(service.kioskEnabled());
            assertEquals("XML", service.kioskFormat());
            assertTrue(service.eegAutoPushEnabled());
            assertEquals(10, service.eegAutoPushSeconds());
            assertTrue(service.cashChangerMovements());
            assertEquals("http://hub", service.nexoHubUrl());
            assertEquals("POI1", service.nexoPoiId());
            assertTrue(service.nexoActivationAtPayment());
            assertTrue(service.nexoPaymentAcknowledgement());
            assertTrue(service.ecommerceValuationEnabled());
            assertTrue(service.ecommerceStatusReturn());
            assertTrue(service.fidelityVtSync());
            assertTrue(service.fidelityVtPayments());
            assertFalse(service.fidelityGtEnabled());
            assertEquals(5, service.fidelityGtSeconds());
            assertEquals("/api/gt", service.fidelityGtPath());
            assertTrue(service.fidelityVt2Enabled());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("integration.pull-minutes", "99999"),
                    row("kiosk.format", "text"),
                    row("eeg.auto-push-seconds", "999999"),
                    row("fidelity.gt-seconds", "99999")));
            PosSettingsService service = new PosSettingsService();
            assertEquals(1440, service.integrationPullMinutes());
            assertEquals("TEXT", service.kioskFormat());
            assertEquals(86400, service.eegAutoPushSeconds());
            assertEquals(3600, service.fidelityGtSeconds());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("kiosk.format", "SOAP")));
            assertEquals("JSON", new PosSettingsService().kioskFormat());
        }
    }

    // ---- lot 21: U ----

    /**
     * The group U getters read their administered values: the fiscal archives,
     * the online retention, e-invoicing and its platform, e-reporting and its
     * period, the SAF-T at the flow and its deadline, the retry delay, and the
     * safe controls (BO-05-01-01 to -06, BO-05-02-10, BO-05-02-15).
     */
    @Test
    void lot21GroupUGettersReadAdministeredValues() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("fiscal.archive-auto", "false"),
                    row("fiscal.online-retention-years", "15"),
                    row("fiscal.einvoicing-enabled", "true"),
                    row("fiscal.einvoicing-mention", " Mention "),
                    row("fiscal.pdp-provider", " PDP1 "),
                    row("fiscal.ereporting-enabled", "true"),
                    row("fiscal.ereporting-period", "month"),
                    row("fiscal.saft-realtime", "true"),
                    row("fiscal.saft-deadline-day", "20"),
                    row("fiscal.transmission-retry-seconds", "60"),
                    row("safe.pouch-requires-day-validation", "false"),
                    row("safe.pouches-per-day", "3"),
                    row("safe.forcing-level-control", "false"),
                    row("safe.advance-limit-control", "false")));
            PosSettingsService service = new PosSettingsService();
            assertFalse(service.fiscalArchiveAuto());
            assertEquals(15, service.fiscalOnlineRetentionYears());
            assertTrue(service.einvoicingEnabled());
            assertEquals("Mention", service.einvoicingMention());
            assertEquals("PDP1", service.pdpProviderCode());
            assertTrue(service.ereportingEnabled());
            assertEquals("MONTH", service.ereportingPeriod());
            assertTrue(service.saftRealtime());
            assertEquals(20, service.saftDeadlineDay());
            assertEquals(60, service.fiscalRetrySeconds());
            assertFalse(service.pouchRequiresDayValidation());
            assertEquals(3, service.pouchesPerDay());
            assertFalse(service.forcingLevelControl());
            assertFalse(service.advanceLimitControl());
        }
    }

    /**
     * The group U getters fall back on their catalog defaults — the mention
     * empty while e-invoicing is off — and their bounds are clamped.
     */
    @Test
    void lot21GroupUGettersDefaultAndClamp() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            PosSettingsService service = new PosSettingsService();
            assertEquals(List.of("DECADE", "MONTH"), PosSettingsService.closedValues("fiscal.ereporting-period"));
            assertTrue(service.fiscalArchiveAuto());
            assertEquals(12, service.fiscalOnlineRetentionYears());
            assertFalse(service.einvoicingEnabled());
            assertEquals("", service.einvoicingMention());
            assertEquals("", service.pdpProviderCode());
            assertFalse(service.ereportingEnabled());
            assertEquals("DECADE", service.ereportingPeriod());
            assertFalse(service.saftRealtime());
            assertEquals(25, service.saftDeadlineDay());
            assertEquals(300, service.fiscalRetrySeconds());
            assertTrue(service.pouchRequiresDayValidation());
            assertEquals(2, service.pouchesPerDay());
            assertTrue(service.forcingLevelControl());
            assertTrue(service.advanceLimitControl());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("fiscal.online-retention-years", "0"),
                    row("fiscal.saft-deadline-day", "40"),
                    row("fiscal.transmission-retry-seconds", "1"),
                    row("safe.pouches-per-day", "-1")));
            PosSettingsService service = new PosSettingsService();
            assertEquals(1, service.fiscalOnlineRetentionYears());
            assertEquals(28, service.saftDeadlineDay());
            assertEquals(10, service.fiscalRetrySeconds());
            assertEquals(0, service.pouchesPerDay());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("fiscal.saft-deadline-day", "0"),
                    row("fiscal.transmission-retry-seconds", "100000")));
            PosSettingsService service = new PosSettingsService();
            assertEquals(1, service.saftDeadlineDay());
            assertEquals(86400, service.fiscalRetrySeconds());
        }
    }

    // ---- lot 21: V ----

    /**
     * The report parameters (BO-06-06-02, BO-06-07-01): the express threshold
     * defaults to ten and falls back to ten below one; the station families
     * default to none and are read as a list, blanks dropped.
     */
    @Test
    void lot21VReportGetters() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            PosSettingsService service = new PosSettingsService();
            assertEquals(10, service.reportExpressMaxItems());
            assertEquals(List.of(), service.reportStationFamilies());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("report.express-max-items", "0"),
                    row("report.station-families", " 9000 ;; 9100;")));
            PosSettingsService service = new PosSettingsService();
            assertEquals(10, service.reportExpressMaxItems());
            assertEquals(List.of("9000", "9100"), service.reportStationFamilies());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(row("report.express-max-items", "5")));
            assertEquals(5, new PosSettingsService().reportExpressMaxItems());
        }
    }

    // ---- lot 21: W ----

    // ---- lot 21: Z ----

    /**
     * The AT web-service credentials (BO-11-04-02): none by default; the
     * subuser and the certificate are read trimmed, the password as keyed.
     */
    @Test
    void lot21ZAtGetters() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of());
            PosSettingsService service = new PosSettingsService();
            assertEquals("", service.atSubuser());
            assertEquals("", service.atPassword());
            assertEquals("", service.atPublicCertificate());
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll).thenReturn(List.of(
                    row("fiscal.at-subuser", " 599999993/37 "),
                    row("fiscal.at-password", " dev pass "),
                    row("fiscal.at-public-certificate", " -----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE----- ")));
            PosSettingsService service = new PosSettingsService();
            assertEquals("599999993/37", service.atSubuser());
            assertEquals(" dev pass ", service.atPassword());
            assertEquals("-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----", service.atPublicCertificate());
        }
    }

}
