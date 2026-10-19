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
     * {@code returnType} resolves a label whatever its case, and answers null
     * for a null one, a blank one and one the list does not name — the four arms
     * of its lookup.
     */
    @Test
    void returnTypeResolvesALabelOrAnswersNull() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(PanacheEntityBase::listAll)
                    .thenReturn(List.of(row("return.types", "Echange>NOENDORSE")));
            PosSettingsService service = new PosSettingsService();
            assertTrue(service.returnType(" echange ").endorsementExempt());
            assertNull(service.returnType(null));
            assertNull(service.returnType("   "));
            assertNull(service.returnType("Inconnu"));
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
     * A FAILED read caches nothing, so the very next access tries the database
     * again: two {@code value} calls issue two {@code listAll}.
     * <p>
     * This is the defect the guard exists for. {@code loadAll} used to clear
     * the cache itself and hand back an empty map, which its own caller then
     * stored — so the empty map became the register's parameters until someone
     * saved a setting or restarted it. A register that cannot read its
     * parameters at boot must recover on its own.
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
}
