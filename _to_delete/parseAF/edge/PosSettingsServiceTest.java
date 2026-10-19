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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.ArgumentMatchers.anyString;
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
     * (exception arm): the cache is dropped and the default is returned.
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
                    .thenReturn(List.of(row("print.forced-documents", " ticket ; ; carte ")));
            assertEquals(List.of("TICKET", "CARTE"), populated.printForcedDocuments());
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
}
