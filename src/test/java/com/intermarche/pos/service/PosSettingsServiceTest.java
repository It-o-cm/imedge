package com.intermarche.pos.service;

import com.intermarche.pos.domain.setting.PosSetting;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
 * false), and each public accessor. 10 two-way decision points (20 branches).
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
}
