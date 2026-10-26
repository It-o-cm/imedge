package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminSettingsResource}.
 * <p>
 * The resource is a Qute-backed back-office page over a mocked
 * {@link PosSettingsService}. The template is a Mockito mock whose chained
 * {@code data(...)} returns a mocked instance; the rendered entry list is
 * captured to assert its projection. No database and no Quarkus context is
 * booted.
 * <p>
 * Branch enumeration (every arm exercised — 100%): {@code settingsPage} covers
 * the grouping into foldable sections (new heading / same heading arms) and the
 * {@code bool} / {@code integer} type ternaries over the whole catalog;
 * {@code save} covers the missing-field arm (continue), a valid integer
 * (parse-ok / non-negative arm) stored normalized, a non-numeric integer and a
 * negative integer (both the invalid arm, early red redirect), a boolean
 * normalized, a text stored verbatim, and the success green redirect.
 * <p>
 * The closed-set parameters add their own arms, on both paths: the projection
 * with its values, the projection WITHOUT any (a free field), the widening of
 * the list by a stored value it does not carry, and on the save a word outside
 * the set (refused) against the stored value itself (let through).
 */
class AdminSettingsResourceTest {

    /**
     * Builds a resource over a mocked template and settings service.
     *
     * @return the wired resource
     */
    private AdminSettingsResource newResource() {
        AdminSettingsResource resource = new AdminSettingsResource();
        resource.adminSettings = mock(Template.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the page template to a single
     * self-returning instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked template instance the chain returns
     */
    private TemplateInstance wireTemplate(AdminSettingsResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminSettings.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * {@code settingsPage} projects the whole catalog into foldable SECTIONS,
     * each holding its parameters in catalog order, and flags the boolean and
     * integer widgets from the type.
     */
    @Test
    @SuppressWarnings("unchecked")
    void settingsPageProjectsCatalog() {
        AdminSettingsResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        when(resource.posSettingsService.value(anyString())).thenReturn("V");
        assertEquals(instance, resource.settingsPage("hi", false));
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(resource.adminSettings).data(eq("sections"), captor.capture());
        List<AdminSettingsResource.Section> sections =
                (List<AdminSettingsResource.Section>) captor.getValue();
        List<AdminSettingsResource.Entry> entries = new java.util.ArrayList<>();
        for (AdminSettingsResource.Section section : sections) {
            assertFalse(section.getEntries().isEmpty(), "section vide: " + section.getLabel());
            assertEquals(section.getEntries().size(), section.getCount(),
                    "count#" + section.getLabel());
            for (AdminSettingsResource.Entry entry : section.getEntries()) {
                assertEquals(section.getLabel(), entry.section, "rubrique#" + entry.key);
            }
            entries.addAll(section.getEntries());
        }
        // Expected BY KEY, not by position. Three parallel boolean arrays indexed
        // by catalog rank meant that adding one parameter — and the register adds
        // them by the handful — shifted every expectation after it and the test
        // failed on entries it was not about. A new parameter is one line here.
        String[][] expected = {
            //  key                                   section                      bool   integer
            {"display.show-ean",                    "AFFICHAGE",                  "b", ""},
            // lot 18: E
            {"channel.ecommerce.enabled", "CANAUX EXTERNES", "b", ""},
            {"channel.ecommerce.price-source", "CANAUX EXTERNES", "", ""},
            {"channel.ecommerce.forbidden-items", "CANAUX EXTERNES", "", ""},
            {"channel.ecommerce.unknown-item-ean", "CANAUX EXTERNES", "", ""},
            {"channel.ecommerce.promotion-channel", "CANAUX EXTERNES", "", ""},
            {"channel.ecommerce.island", "CANAUX EXTERNES", "", ""},
            {"channel.ecommerce.terminal", "CANAUX EXTERNES", "", ""},
            {"channel.ecommerce.operator", "CANAUX EXTERNES", "", ""},
            {"channel.ecommerce.default-tender", "CANAUX EXTERNES", "", ""},
            {"channel.ecommerce.archive", "CANAUX EXTERNES", "b", ""},
            {"channel.ecommerce.archive-days", "CANAUX EXTERNES", "", "i"},
            {"channel.ecommerce.return-ticket-types", "CANAUX EXTERNES", "", ""},
            {"channel.ecommerce.return-article-types", "CANAUX EXTERNES", "", ""},
            {"channel.selfscan.enabled", "CANAUX EXTERNES", "b", ""},
            {"channel.selfscan.retrieval-mode", "CANAUX EXTERNES", "", ""},
            {"channel.selfscan.add-items", "CANAUX EXTERNES", "", ""},
            {"channel.selfscan.identifier", "CANAUX EXTERNES", "", ""},
            {"channel.selfscan.price-source", "CANAUX EXTERNES", "", ""},
            {"channel.selfscan.coupons", "CANAUX EXTERNES", "b", ""},
            {"channel.selfscan.unknown-item-ean", "CANAUX EXTERNES", "", ""},
            {"channel.selfscan.promotion-channel", "CANAUX EXTERNES", "", ""},
            {"channel.selfscan.forbidden-items", "CANAUX EXTERNES", "", ""},
            {"channel.commercial.forbidden-items", "CANAUX EXTERNES", "", ""},
            {"channel.commercial.retrieval-mode", "CANAUX EXTERNES", "", ""},
            {"channel.commercial.transmission", "CANAUX EXTERNES", "", ""},
            {"channel.commercial.pull-url", "CANAUX EXTERNES", "", ""},
            {"channel.commercial.pull-minutes", "CANAUX EXTERNES", "", "i"},
            {"channel.commercial.auto-print-template", "CANAUX EXTERNES", "", ""},
            {"channel.commercial.deposit-tender", "CANAUX EXTERNES", "", ""},
            {"channel.commercial.promotion-channel", "CANAUX EXTERNES", "", ""},
            {"channel.commercial.price-source", "CANAUX EXTERNES", "", ""},
            {"channel.qbusting.retrieval-mode", "CANAUX EXTERNES", "", ""},
            {"channel.qbusting.promotion-channel", "CANAUX EXTERNES", "", ""},
            {"channel.qbusting.forbidden-items", "CANAUX EXTERNES", "", ""},
            {"channel.qbusting.price-source", "CANAUX EXTERNES", "", ""},
            {"channel.mobility.promotion-channel", "CANAUX EXTERNES", "", ""},
            {"channel.mobility.price-source", "CANAUX EXTERNES", "", ""},
            {"channel.mobility.forbidden-items", "CANAUX EXTERNES", "", ""},
            {"channel.mobility.fictitious-tender", "CANAUX EXTERNES", "", ""},
            {"channel.sales-slip.retrieval-mode", "CANAUX EXTERNES", "", ""},
            {"channel.sales-slip.price-source", "CANAUX EXTERNES", "", ""},
            // lot 18: F
            // lot 18: G
            {"fidelity.pot-balances-enabled",       "FIDÉLITÉ",                   "b", ""},
            {"fidelity.payment-tender",             "FIDÉLITÉ",                   "",  ""},
            {"fidelity.password",                   "FIDÉLITÉ",                   "",  ""},
            {"fidelity.connect-timeout-ms",         "FIDÉLITÉ",                   "",  "i"},
            {"fidelity.read-timeout-ms",            "FIDÉLITÉ",                   "",  "i"},
            {"fidelity.order-date-enabled",         "FIDÉLITÉ",                   "b", ""},
            // lot 18: H
            {"fiscal.saft-id-fs", "FISCAL", "", "i"},
            {"fiscal.saft-id-nc", "FISCAL", "", "i"},
            {"ecotax.attributes", "FISCAL", "", ""},
            {"cheque.unit-text", "DEVISE", "", ""},
            {"cheque.cent-text", "DEVISE", "", ""},
            {"ticket.eft-logos", "TICKETS", "b", ""},
            {"ticket.family-subtotals", "TICKETS", "b", ""},
            {"ticket.bold-amounts", "TICKETS", "b", ""},
            {"language.customer-display", "LANGUE", "", ""},
            {"customer.message-open-second", "AFFICHEUR CLIENT", "", ""},
            {"customer.message-closed-second", "AFFICHEUR CLIENT", "", ""},
            {"display.offline-enabled", "AFFICHAGE", "b", ""},
            {"display.offline-delay-seconds", "AFFICHAGE", "", "i"},
            {"unit.measure-decimals", "UNITÉS DE MESURE", "", "i"},
            {"unit.weight-decimals", "UNITÉS DE MESURE", "", "i"},
            {"unit.weight-symbol", "UNITÉS DE MESURE", "", ""},
            {"ticket.cancelled-lines-print", "TICKETS", "b", ""},
            {"ticket.identical-lines-detailed", "TICKETS", "b", ""},
            // lot 21: S
            {"journal.secondary-monetic-servers", "SUPERVISION", "", ""},
            {"auth.endorsement-profiles", "GESTES NÉGATIFS", "", ""},
            {"control.alert-rules", "POINTS DE CONTRÔLE", "", ""},
            {"justification.function-routes", "POINTS DE CONTRÔLE", "", ""},
            {"control.indicators-bar", "SUPERVISION", "b", ""},
            {"control.revenue-level", "SUPERVISION", "", "i"},
            {"control.revenue-top", "SUPERVISION", "", "i"},
            {"audit.watched-menus", "SUPERVISION", "", ""},
            {"supervision.global-alerts", "SUPERVISION", "b", ""},
            {"supervision.live-event-types", "SUPERVISION", "", ""},
            // lot 21: T
            {"integration.pull-feeds", "CANAUX EXTERNES", "", ""},
            {"integration.pull-minutes", "CANAUX EXTERNES", "", "i"},
            {"export.family-sales-upstream-offers", "FLUX SORTANTS", "", ""},
            {"export.gs1-payments-enabled", "FLUX SORTANTS", "b", ""},
            {"gs1.central-burn", "CODES GS1", "b", ""},
            {"vignette.enabled", "VIGNETTES", "b", ""},
            {"vignette.amount-step", "VIGNETTES", "", ""},
            {"vignette.per-step", "VIGNETTES", "", "i"},
            {"export.vignettes-enabled", "FLUX SORTANTS", "b", ""},
            {"kiosk.enabled", "RÉFÉRENTIEL ARTICLES", "b", ""},
            {"kiosk.format", "RÉFÉRENTIEL ARTICLES", "", ""},
            {"eeg.auto-push-enabled", "RÉFÉRENTIEL ARTICLES", "b", ""},
            {"eeg.auto-push-seconds", "RÉFÉRENTIEL ARTICLES", "", "i"},
            {"hardware.cashchanger.movements", "PÉRIPHÉRIQUES", "b", ""},
            {"nexo.hub-url", "PRODUITS DÉMATÉRIALISÉS", "", ""},
            {"nexo.poi-id", "PRODUITS DÉMATÉRIALISÉS", "", ""},
            {"nexo.activation-moment", "PRODUITS DÉMATÉRIALISÉS", "", ""},
            {"nexo.payment-acknowledgement", "PRODUITS DÉMATÉRIALISÉS", "b", ""},
            {"channel.ecommerce.valuation-enabled", "CANAUX EXTERNES", "b", ""},
            {"channel.ecommerce.status-return", "CANAUX EXTERNES", "b", ""},
            {"fidelity.vt-sync", "FIDÉLITÉ", "b", ""},
            {"fidelity.vt-payments", "FIDÉLITÉ", "b", ""},
            {"fidelity.gt-enabled", "FIDÉLITÉ", "b", ""},
            {"fidelity.gt-seconds", "FIDÉLITÉ", "", "i"},
            {"fidelity.gt-path", "FIDÉLITÉ", "", ""},
            {"fidelity.vt2-enabled", "FIDÉLITÉ", "b", ""},
            // lot 21: U
            {"fiscal.archive-auto", "FISCAL", "b", ""},
            {"fiscal.online-retention-years", "FISCAL", "", "i"},
            {"fiscal.einvoicing-enabled", "FISCAL", "b", ""},
            {"fiscal.einvoicing-mention", "FISCAL", "", ""},
            {"fiscal.pdp-provider", "FISCAL", "", ""},
            {"fiscal.ereporting-enabled", "FISCAL", "b", ""},
            {"fiscal.ereporting-period", "FISCAL", "", ""},
            {"fiscal.saft-realtime", "FISCAL", "b", ""},
            {"fiscal.saft-deadline-day", "FISCAL", "", "i"},
            {"fiscal.transmission-retry-seconds", "FISCAL", "", "i"},
            {"safe.pouch-requires-day-validation", "COFFRE", "b", ""},
            {"safe.pouches-per-day", "COFFRE", "", "i"},
            {"safe.forcing-level-control", "COFFRE", "b", ""},
            {"safe.advance-limit-control", "COFFRE", "b", ""},
            // lot 21: V
            {"report.express-max-items", "RAPPORTS", "", "i"},
            {"report.station-families", "RAPPORTS", "", ""},
            // lot 21: W
            // lot 21: Z
            {"fiscal.at-subuser", "FISCAL", "", ""},
            {"fiscal.at-password", "FISCAL", "", ""},
            {"fiscal.at-public-certificate", "FISCAL", "", ""},
            // lot 20: P
            {"article.complementary-suggestions", "RÉFÉRENTIEL ARTICLES", "b", ""},
            {"article.complementary-max", "RÉFÉRENTIEL ARTICLES", "", "i"},
            {"article.meal-voucher-eligible-only", "RÉFÉRENTIEL ARTICLES", "b", ""},
            {"article.deposit-control", "RÉFÉRENTIEL ARTICLES", "b", ""},
            {"eeg.enabled", "RÉFÉRENTIEL ARTICLES", "b", ""},
            // lot 20: Q
            {"channel.commercial.customer-uplink-url", "CANAUX EXTERNES", "", ""},
            {"channel.commercial.customer-uplink-seconds", "CANAUX EXTERNES", "", "i"},
            // lot 20: R
            {"channel.integration.token-required", "CANAUX EXTERNES", "b", ""},
            {"channel.ecommerce.fidelity-earn", "CANAUX EXTERNES", "b", ""},
            {"period.auto-launch", "FIN DE PÉRIODE", "b", ""},
            {"period.parked-record", "FIN DE PÉRIODE", "b", ""},
            {"period.parked-raz", "FIN DE PÉRIODE", "b", ""},
            {"period.forced-close-ticket", "FIN DE PÉRIODE", "b", ""},
            {"period.sco-auto-declaration", "FIN DE PÉRIODE", "b", ""},
            {"period.hooks-enabled", "FIN DE PÉRIODE", "b", ""},
            {"period.hook-before", "FIN DE PÉRIODE", "", ""},
            {"period.hook-after", "FIN DE PÉRIODE", "", ""},
            {"period.hook-timeout-seconds", "FIN DE PÉRIODE", "", "i"},
            {"period.register-check", "FIN DE PÉRIODE", "b", ""},
            {"period.reports-printer", "FIN DE PÉRIODE", "", ""},
            // lot 19: K
            {"ticket.header-template", "TICKETS", "", ""},
            {"ticket.footer-template", "TICKETS", "", ""},
            {"ticket.vat-table", "TICKETS", "", ""},
            {"print.logo-code", "TICKETS", "", ""},
            {"menu.code", "MENU CAISSE", "", ""},
            {"queue.single-enabled", "FILE UNIQUE", "b", ""},
            {"queue.single-url", "FILE UNIQUE", "", ""},
            {"hardware.drawer.enabled", "TIROIR", "b", ""},
            {"hardware.drawer.address", "TIROIR", "", ""},
            {"hardware.scanner.driver", "PÉRIPHÉRIQUES", "", ""},
            {"hardware.scanner.name-patterns", "PÉRIPHÉRIQUES", "", ""},
            {"hardware.scanner.opos-device", "PÉRIPHÉRIQUES", "", ""},
            {"hardware.scanner2.enabled", "PÉRIPHÉRIQUES", "b", ""},
            {"hardware.scanner2.name-patterns", "PÉRIPHÉRIQUES", "", ""},
            {"hardware.printer.type", "PÉRIPHÉRIQUES", "", ""},
            {"hardware.printer.speed", "PÉRIPHÉRIQUES", "", "i"},
            {"hardware.printer.code-page", "PÉRIPHÉRIQUES", "", "i"},
            {"hardware.printer.paper-width", "PÉRIPHÉRIQUES", "", "i"},
            {"hardware.printer.cheque-flip", "PÉRIPHÉRIQUES", "b", ""},
            {"printer2.enabled", "PÉRIPHÉRIQUES", "b", ""},
            {"printer2.format", "PÉRIPHÉRIQUES", "", ""},
            {"printer2.connection", "PÉRIPHÉRIQUES", "", ""},
            {"printer2.address", "PÉRIPHÉRIQUES", "", ""},
            {"payment.terminal-type", "MONÉTIQUE", "", ""},
            {"payment.terminal-address", "MONÉTIQUE", "", ""},
            {"terminal.island-label", "MONÉTIQUE", "", ""},
            {"catalina.enabled", "FIL DE L'EAU", "b", ""},
            {"export.planexa-enabled", "FLUX SORTANTS", "b", ""},
            // lot 19: L
            {"cheque.guarantee-provider", "CHEQUE", "", ""},
            {"cheque.guarantee-url", "CHEQUE", "", ""},
            {"cheque.guarantee-timeout-ms", "CHEQUE", "", "i"},
            {"deposit.article-eans", "RETOUR CLIENT", "", ""},
            {"payment.card-receipt-printed", "MONETIQUE", "b", ""},
            {"payment.degraded-release-endorsement", "MONÉTIQUE", "b", ""},
            // lot 19: M
            {"ticket.family-level", "TICKETS", "", "i"},
            {"ticket.promotion-placement", "TICKETS", "", ""},
            {"invoice.article-attributes", "FACTURE", "", ""},
            {"invoice.a4-lines-per-page", "FACTURE", "", "i"},
            {"invoice.account-number-required", "FACTURE", "b", ""},
            {"invoice.customer-validation", "FACTURE", "b", ""},
            {"fidelity.short-message-printed", "FIDÉLITÉ", "b", ""},
            {"fidelity.commercial-messages-max", "FIDÉLITÉ", "", "i"},
            // lot 19: N
            {"touch.article-sort-volume", "TOUCHES CAISSE", "b", ""},
            {"touch.sales-volume-days", "TOUCHES CAISSE", "", "i"},
            {"valuation.local-only", "SCAN", "b", ""},
            // lot 18: I
            {"supervision.report-seconds",          "SUPERVISION",                "",  "i"},
            {"supervision.function-usage-enabled",  "SUPERVISION",                "b", ""},
            {"supervision.remote-maintenance-enabled", "SUPERVISION",             "b", ""},
            {"integration.retry-enabled",           "SUPERVISION",                "b", ""},
            {"integration.retry-minutes",           "SUPERVISION",                "",  "i"},
            {"integration.retry-max-attempts",      "SUPERVISION",                "",  "i"},
            {"dashboard.mobile-alerts-enabled",     "SUPERVISION",                "b", ""},
            {"deployment.auto-update-at-startup",   "DÉPLOIEMENT",                "b", ""},
            {"deployment.max-attempts",             "DÉPLOIEMENT",                "",  "i"},
            {"deployment.rollback-enabled",         "DÉPLOIEMENT",                "b", ""},
            {"auth.idle-lockout-seconds",           "SESSION CAISSE",             "",  "i"},
            {"gesture.endorsement-required",        "GESTES DE PRIX",             "b", ""},
            {"discount.line-max-percent",           "GESTES DE PRIX",             "",  "i"},
            {"discount.global-max-percent",         "GESTES DE PRIX",             "",  "i"},
            {"discount.presets",                    "GESTES DE PRIX",             "",  ""},
            {"discount.cumulative-on-discounted",   "GESTES DE PRIX",             "b", ""},
            {"discount.global-on-discounted",       "GESTES DE PRIX",             "b", ""},
            {"discount.enabled",                    "GESTES DE PRIX",             "b", ""},
            {"price.show-original-on-force",        "GESTES DE PRIX",             "b", ""},
            {"customer.message-open",               "AFFICHEUR CLIENT",           "",  ""},
            {"customer.message-closed",             "AFFICHEUR CLIENT",           "",  ""},
            {"customer.qr-enabled",                 "AFFICHEUR CLIENT",           "b", ""},
            {"customer.eco-voucher-eligible",       "AFFICHEUR CLIENT",           "b", ""},
            {"customer.social-card-eligible",       "AFFICHEUR CLIENT",           "b", ""},
            {"parking.print-mode",                 "TICKETS",                    "",  ""},
            {"parking.receipt-detail",             "TICKETS",                    "b", ""},
            {"parking.print-receipt",               "TICKETS",                    "b", ""},
            {"ticket.email-format",                 "TICKETS",                    "",  ""},
            {"ticket.email-editable",               "TICKETS",                    "b", ""},
            {"currency.symbol",                     "DEVISE",                     "",  ""},
            {"currency.symbol-print",               "DEVISE",                     "b", ""},
            {"currency.symbol-position",            "DEVISE",                     "",  ""},
            {"ticket.line-order",                   "TICKETS",                    "",  ""},
            {"retention.barcodes-expired-unused-days", "RÉTENTION DES CODES-BARRES", "", "i"},
            {"retention.barcodes-expired-used-days", "RÉTENTION DES CODES-BARRES", "",  "i"},
            {"retention.barcodes-undated-days",      "RÉTENTION DES CODES-BARRES", "",  "i"},
            {"ticket.search-days",                  "TICKETS",                    "",  "i"},
            {"ticket.item-count-print",             "TICKETS",                    "",  ""},
            {"ticket.unknown-article-barcode",      "TICKETS",                    "b", ""},
            {"ticket.second-code-print",            "TICKETS",                    "b", ""},
            {"ticket.family-alphabetical",          "TICKETS",                    "b", ""},
            {"ticket.line-attribute",               "TICKETS",                    "",  ""},
            {"ticket.line-attribute-isolate",       "TICKETS",                    "",  ""},
            {"ticket.barcode-enabled",              "TICKETS",                    "b", ""},
            {"ticket.barcode-format",               "TICKETS",                    "",  ""},
            {"ticket.barcode-prefix",               "TICKETS",                    "",  ""},
            {"ticket.email-offer", "TICKETS", "b", ""},
            {"cash.deposit-print", "MOUVEMENTS DE CAISSE", "b", ""},
            {"ticket.vat-code",                     "TICKETS",                    "b", ""},
            {"ticket.exit-ticket",                  "TICKETS",                    "b", ""},
            {"ticket.exit-ticket-articles",         "TICKETS",                    "",  "i"},
            {"parking.print-resume",                "TICKETS",                    "b", ""},
            {"ticket.line-discount-print",          "TICKETS",                    "b", ""},
            {"ticket.immediate-discount-total",     "TICKETS",                    "b", ""},
            {"print.logo-width-dots",               "TICKETS",                    "",  "i"},
            {"credit-note.print-expiry",            "TICKETS",                    "b", ""},
            {"print.conditional-enabled",           "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"print.forced-documents",              "IMPRESSION CONDITIONNELLE",  "",  ""},
            {"print.force-ticket-glc",              "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"print.force-card-credit",             "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"print.force-card-signature",          "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"print.force-card-tna",                "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"payment.degraded-mode",               "MONÉTIQUE",                  "b", ""},
            {"payment.degraded-forced-endorsement", "MONÉTIQUE",                  "b", ""},
            {"payment.degraded-forced-minutes",     "MONÉTIQUE",                  "",  "i"},
            {"terminal.pdv-label-enabled",          "MONÉTIQUE",                  "b", ""},
            {"terminal.pdv-label",                  "MONÉTIQUE",                  "",  ""},
            {"ticket.header-message",               "MESSAGES TICKET",            "",  ""},
            {"ticket.footer-message",               "MESSAGES TICKET",            "",  ""},
            {"ticket.footer-url",                   "MESSAGES TICKET",            "",  ""},
            {"ticket.footer-url-format",            "MESSAGES TICKET",            "",  ""},
            {"drawer.open-on-payment",              "TIROIR",                     "b", ""},
            {"drawer.open-on-login",                "TIROIR",                     "b", ""},
            {"backup.manual-endorsement",           "SECOURS MONETIQUE",          "b", ""},
            {"backup.method-labels",                "SECOURS MONETIQUE",          "",  ""},
            {"balance.counter-price",               "TICKET COMPTOIR",            "b", ""},
            {"hardware.printer.enabled", "PÉRIPHÉRIQUES", "b", ""},
            {"hardware.scanner.enabled", "PÉRIPHÉRIQUES", "b", ""},
            {"hardware.scale.enabled", "PÉRIPHÉRIQUES", "b", ""},
            {"hardware.cashchanger.enabled", "PÉRIPHÉRIQUES", "b", ""},
            {"maintenance.verifone.enabled", "MAINTENANCE", "b", ""},
            {"maintenance.verifone.start", "MAINTENANCE", "", ""},
            {"maintenance.verifone.end", "MAINTENANCE", "", ""},
            {"maintenance.verifone.random", "MAINTENANCE", "b", ""},
            {"maintenance.verifone.stagger-seconds", "MAINTENANCE", "", "i"},
            {"maintenance.restart-reasons", "MAINTENANCE", "", ""},
            {"maintenance.command.application", "MAINTENANCE", "", ""},
            {"maintenance.command.reboot", "MAINTENANCE", "", ""},
            {"maintenance.command.shutdown", "MAINTENANCE", "", ""},
            {"cheque.front-lines", "CHEQUE", "", ""},
            {"cheque.beneficiary", "CHEQUE", "", ""},
            {"cheque.place", "CHEQUE", "", ""},
            {"cheque.bank-reference", "CHEQUE", "", ""},
            {"cheque.account-number", "CHEQUE", "", ""},
            {"payment.verifone-method-map", "MONETIQUE", "", ""},
            {"payment.card-receipt-attached", "MONETIQUE", "b", ""},
            {"payment.cashback-enabled", "MONETIQUE", "b", ""},
            {"payment.cashback-max", "MONETIQUE", "", ""},
            {"donation.rounding-step-cents",         "ARRONDI SOLIDAIRE",          "",  "i"},
            {"donation.rounding-nearest",            "ARRONDI SOLIDAIRE",          "b", ""},
            {"cash.rounding-step-cents",            "ARRONDI ESPECES",            "",  "i"},
            {"credit.allowed-in-degraded",          "CREDIT CLIENT",              "b", ""},
            {"customer.account-pattern",            "CREDIT CLIENT",              "",  ""},
            {"credit.degraded-after-minutes",       "CREDIT CLIENT",              "",  "i"},
            {"customer.volatile-creation-enabled",  "CLIENT EN CAISSE",           "b", ""},
            {"customer.account-creation-blocked",   "CLIENT EN CAISSE",           "b", ""},
            {"customer.show-details",               "CLIENT EN CAISSE",           "b", ""},
            {"customer.update-enabled",             "CLIENT EN CAISSE",           "b", ""},
            {"customer.show-discount",              "CLIENT EN CAISSE",           "b", ""},
            {"customer.discount-segment",           "CLIENT EN CAISSE",           "",  ""},
            {"customer.credit-tender",              "CLIENT EN CAISSE",           "",  ""},
            {"customer.partial-credit-allowed",     "CLIENT EN CAISSE",           "b", ""},
            {"customer.offline-message",            "CLIENT EN CAISSE",           "",  ""},
            {"customer.over-limit-message",         "CLIENT EN CAISSE",           "",  ""},
            {"customer.default-tax-id",             "CLIENT EN CAISSE",           "",  ""},
            {"customer.tax-id-required",            "CLIENT EN CAISSE",           "b", ""},
            {"customer.tax-id-pt-check",            "CLIENT EN CAISSE",           "b", ""},
            {"customer.tax-id-scan-pattern",        "CLIENT EN CAISSE",           "",  ""},
            {"customer.default-tax-id-label",       "CLIENT EN CAISSE",           "",  ""},
            {"customer.foreign-tax-id-countries",   "CLIENT EN CAISSE",           "",  ""},
            {"customer.invoice-export-due-date",    "CLIENT EN CAISSE",           "b", ""},
            {"customer.invoice-export-ecotax",      "CLIENT EN CAISSE",           "b", ""},
            {"scan.ean13-check-digit",              "SCAN",                       "b", ""},
            {"scan.supervisor-prefix",              "SCAN",                       "",  ""},
            {"sale.allow-during-payment",           "SCAN",                       "b", ""},
            {"dashboard.alerts-enabled",            "SUPERVISION",                "b", ""},
            {"dashboard.indicators",                "SUPERVISION",                "",  ""},
            {"supervision.register-offline-minutes", "SUPERVISION",               "",  "i"},
            {"supervision.settled-calls-kept",      "SUPERVISION",                "b", ""},
            {"auth.endorsement-roles",              "GESTES NÉGATIFS",            "",  ""},
            {"supervision.unknown-item-alert",      "SUPERVISION",                "b", ""},
            {"supervisor.service-reasons",          "SUPERVISION",                "",  ""},
            {"fidelity.advantages-enabled",         "FIDÉLITÉ",                   "b", ""},
            {"fidelity.projected-label",            "FIDÉLITÉ",                   "",  ""},
            {"fidelity.allow-multiple-scan",        "FIDÉLITÉ",                   "b", ""},
            {"cash.movement-endorsement-threshold", "MOUVEMENTS DE CAISSE",       "",  ""},
            {"cash.movement-reasons",               "MOUVEMENTS DE CAISSE",       "",  ""},
            {"invoice.customer-fields",             "FACTURE",                    "",  ""},
            {"invoice.document-types",              "FACTURE",                    "",  ""},
            {"invoice.document-output",             "FACTURE",                    "",  ""},
            {"invoice.decimals-unit-ht",            "FACTURE",                    "",  "i"},
            {"invoice.decimals-vat-base",           "FACTURE",                    "",  "i"},
            {"invoice.decimals-vat-amount",         "FACTURE",                    "",  "i"},
            {"invoice.decimals-total-ht",           "FACTURE",                    "",  "i"},
            {"invoice.decimals-total-ttc",          "FACTURE",                    "",  "i"},
            {"invoice.slip-lines",                  "FACTURE",                    "",  "i"},
            {"invoice.auto-print",                  "FACTURE",                    "",  ""},
            {"drawer.withdrawal-methods",           "PRÉLÈVEMENT ET TRANSFERT",   "",  ""},
            {"drawer.withdrawal-print",             "PRÉLÈVEMENT ET TRANSFERT",   "b", ""},
            {"drawer.transfer-methods",             "PRÉLÈVEMENT ET TRANSFERT",   "",  ""},
            {"drawer.transfer-print",               "PRÉLÈVEMENT ET TRANSFERT",   "b", ""},
            {"abandon.reasons",                     "ABANDON DE TICKET",          "",  ""},
            {"abandon.partial-payment",             "ABANDON DE TICKET",          "",  ""},
            {"abandon.print",                       "ABANDON DE TICKET",          "",  ""},
            {"abandon.print-detail",                "ABANDON DE TICKET",          "b", ""},
            {"tender.restricted",                   "RÈGLEMENTS RESTREINTS",      "",  ""},
            {"customer.local-code-range",           "CLIENT EN CAISSE",           "",  ""},
            {"keyboard.enter-as-total",             "TOUCHES CAISSE",             "b", ""},
            {"touch.external-apps",                 "TOUCHES CAISSE",             "",  ""},
            {"tender.local-range",                  "RÈGLEMENTS RESTREINTS",      "",  ""},
            {"cheque.endorsement-lines",            "RÈGLEMENTS RESTREINTS",      "",  ""},
            {"gs1.expiry-alert",                    "CODES GS1",                  "",  ""},
            {"gs1.expiry-warn-days",                "CODES GS1",                  "",  "i"},
            {"gs1.coupon-expiry-alert",             "CODES GS1",                  "",  ""},
            {"gs1.gdti-coupon-types",               "CODES GS1",                  "",  ""},
            {"gs1.gcn-coupon-type",                 "CODES GS1",                  "",  ""},
            {"sogec.bon-prefixes",                  "CODES GS1",                  "",  ""},
            {"touch.price-currency",                "TOUCHES CAISSE",             "b", ""},
            {"report.show-sales", "RAPPORT CA", "b", ""},
            {"report.show-returns", "RAPPORT CA", "b", ""},
            {"report.show-expenses", "RAPPORT CA", "b", ""},
            {"report.show-cancellations", "RAPPORT CA", "b", ""},
            {"report.show-abandoned", "RAPPORT CA", "b", ""},
            {"report.show-price-overrides", "RAPPORT CA", "b", ""},
            {"report.show-deposits", "RAPPORT CA", "b", ""},
            {"report.show-withdrawals", "RAPPORT CA", "b", ""},
            {"report.show-tenders", "RAPPORT CA", "b", ""},
            {"touch.grid-columns",                 "TOUCHES CAISSE",             "", "i"},
            {"touch.show-ean",                    "TOUCHES CAISSE",             "b", ""},
            {"touch.article-order",                "TOUCHES CAISSE",             "", ""},
            {"touch.groups-per-page",               "TOUCHES CAISSE",             "",  "i"},
            {"touch.display-order",                 "TOUCHES CAISSE",             "",  ""},
            {"auth.bo-max-failures",                "SESSION CAISSE",             "",  "i"},
            {"auth.bo-lockout-minutes",             "SESSION CAISSE",             "",  "i"},
            {"auth.bo-sign-in",                     "SESSION CAISSE",             "",  ""},
            {"auth.password-expiry-days",           "SESSION CAISSE",             "",  "i"},
            {"auth.idle-action",                    "SESSION CAISSE",             "",  ""},
            {"auth.badge-scan-enabled",             "SESSION CAISSE",             "b", ""},
            {"auth.password-required-on-open",      "SESSION CAISSE",             "b", ""},
            {"auth.dallas-key",                     "SESSION CAISSE",             "b", ""},
            {"auth.password-required-on-close",     "SESSION CAISSE",             "b", ""},
            {"session.close-endorsement-on-pending","SESSION CAISSE",             "b", ""},
            {"session.print-open-receipt",          "TICKETS",                    "b", ""},
            {"session.print-close-receipt",         "TICKETS",                    "b", ""},
            {"period.alert-countdown-minutes",      "FIN DE PÉRIODE",             "",  "i"},
            {"period.week-end-day",                 "FIN DE PÉRIODE",             "",  ""},
            {"period.month-end-day",                "FIN DE PÉRIODE",             "",  "i"},
            {"period.year-end-date",                "FIN DE PÉRIODE",             "",  ""},
            {"period.reports",                      "FIN DE PÉRIODE",             "",  ""},
            {"period.reports-format",               "FIN DE PÉRIODE",             "",  ""},
            {"period.auto-close-enabled",           "FIN DE PÉRIODE",             "b", ""},
            {"period.end-time",                     "FIN DE PÉRIODE",             "",  ""},
            {"period.close-lead-minutes",           "FIN DE PÉRIODE",             "",  "i"},
            {"period.reset-ticket-counter",         "FIN DE PÉRIODE",             "b", ""},
            {"period.pdv-actions",                  "FIN DE PÉRIODE",             "",  ""},
            {"cash.default-opening-float",          "SESSION CAISSE",             "",  ""},
            {"drawer.open-on-session-close",        "TIROIR",                     "b", ""},
            {"ticket.vat-breakdown-enabled",        "TICKETS",                    "b", ""},
            {"fidelity.print-card-number",          "FIDÉLITÉ",                   "b", ""},
            {"fidelity.print-balances",             "FIDÉLITÉ",                   "b", ""},
            {"fidelity.advantage-grouping",         "FIDÉLITÉ",                   "",  ""},
            {"fidelity.non-holder-message",         "FIDÉLITÉ",                   "",  ""},
            {"fidelity.external-enabled",           "FIDÉLITÉ",                   "b", ""},
            {"cash.movement-tenders",               "MOUVEMENTS DE CAISSE",       "",  ""},
            {"cash.withdrawal-destinations",        "MOUVEMENTS DE CAISSE",       "",  ""},
            {"discount.line-max-amount",            "GESTES DE PRIX",             "",  ""},
            {"discount.global-max-amount",          "GESTES DE PRIX",             "",  ""},
            {"fidelity.show-holder-name-customer",  "FIDÉLITÉ",                   "b", ""},
            {"fidelity.show-balance-customer",      "FIDÉLITÉ",                   "b", ""},
            {"fidelity.show-holder-name",           "FIDÉLITÉ",                   "b", ""},
            {"fidelity.card-pattern",               "FIDÉLITÉ",                   "",  ""},
            {"fidelity.jv-promotion-enabled",       "FIDÉLITÉ",                   "b", ""},
            {"fidelity.template-online",            "FIDÉLITÉ",                   "",  ""},
            {"fidelity.template-offline",           "FIDÉLITÉ",                   "",  ""},
            {"fidelity.template-non-holder-online", "FIDÉLITÉ",                   "",  ""},
            {"fidelity.template-non-holder-offline", "FIDÉLITÉ",                  "",  ""},
            {"fidelity.template-grouped",           "FIDÉLITÉ",                   "",  ""},
            {"fidelity.url",                        "FIDÉLITÉ",                   "",  ""},
            {"fidelity.user",                       "FIDÉLITÉ",                   "",  ""},
            {"fidelity.offline-message",            "FIDÉLITÉ",                   "",  ""},
            {"fidelity.offline-message-no-card",    "FIDÉLITÉ",                   "",  ""},
            {"fidelity.anonymous-projection",       "FIDÉLITÉ",                   "b", ""},
            {"gesture.function-endorsements",        "GESTES ET AVALS",             "",  ""},
            {"training.blocked-keys",               "MODE ÉCOLE",                 "",  ""},
            {"training.theme",                      "MODE ÉCOLE",                 "",  ""},
            {"retention.sales-days",                "RÉTENTION DES DONNÉES",      "",  "i"},
            {"retention.cashier-reports-count",     "RÉTENTION DES DONNÉES",      "",  "i"},
            {"retention.daily-tables-days",         "RÉTENTION DES DONNÉES",      "",  "i"},
            {"retention.access-journal-days",       "RÉTENTION DES DONNÉES",      "",  "i"},
            {"retention.alerts-days",               "RÉTENTION DES DONNÉES",      "",  "i"},
            {"retention.parked-tickets-days",       "RÉTENTION DES DONNÉES",      "",  "i"},
            {"retention.invoices-days",             "RÉTENTION DES DONNÉES",      "",  "i"},
            {"retention.expired-promotions-days",   "RÉTENTION DES DONNÉES",      "",  "i"},
            {"retention.expired-promotions-cap",    "RÉTENTION DES DONNÉES",      "",  "i"},
            {"retention.deleted-articles-days",     "RÉTENTION DES DONNÉES",      "",  "i"},
            {"customer.integration-require-address", "CLIENT EN COMPTE",          "b", ""},
            {"referential.update-audit-enabled",    "SUPERVISION",                "b", ""},
            {"retention.integration-results-days",  "RÉTENTION DES DONNÉES",      "",  "i"},
            {"retention.customers-days",            "RÉTENTION DES DONNÉES",      "",  "i"},
            {"retention.scheduled-reports-days",    "RÉTENTION DES DONNÉES",      "",  "i"},
            {"session.welcome-message",             "SESSION CAISSE",             "",  ""},
            {"sale.waiting-label",                  "AFFICHAGE",                  "",  ""},
            {"sale.line-warning-threshold",         "AFFICHAGE",                  "",  ""},
            {"cash.drawer-cap",                     "TIROIR",                     "",  ""},
            {"gesture.endorsement-cancel-line",     "GESTES NÉGATIFS",            "b", ""},
            {"gesture.endorsement-cancel-last",     "GESTES NÉGATIFS",            "b", ""},
            {"gesture.endorsement-abandon",         "GESTES NÉGATIFS",            "b", ""},
            {"gesture.endorsement-return",          "GESTES NÉGATIFS",            "b", ""},
            {"gesture.endorsement-refund",          "GESTES NÉGATIFS",            "b", ""},
            {"gesture.endorsement-discount",        "GESTES NÉGATIFS",            "b", ""},
            {"gesture.endorsement-price-force",     "GESTES NÉGATIFS",            "b", ""},
            {"gesture.endorsement-ticket-reassign", "GESTES NÉGATIFS",            "b", ""},
            {"reassign.pdv-duplicate",              "TICKETS",                    "b", ""},
            {"cash.variance-threshold",             "CLÔTURE",                    "",  ""},
            {"cash.float-mode",                     "CLÔTURE",                    "",  ""},
            {"cash.variance-amount-visible",        "CLÔTURE",                    "b", ""},
            {"collect.point-label",                 "ARTICLES A ENLEVER",         "",  ""},
            {"collect.voucher-copies",              "ARTICLES A ENLEVER",         "",  "i"},
            {"movement.financial-sources",          "MOUVEMENT FINANCIER",        "",  ""},
            {"movement.financial-destinations",     "MOUVEMENT FINANCIER",        "",  ""},
            {"movement.financial-print",            "MOUVEMENT FINANCIER",        "b", ""},
            {"change-order.print",                  "COMMANDE DE MONNAIE",        "b", ""},
            {"change-order.roll-values",            "COMMANDE DE MONNAIE",        "",  ""},
            {"return.reasons",                      "RETOUR CLIENT",              "",  ""},
            {"return.print-reason",                 "RETOUR CLIENT",              "b", ""},
            {"return.in-sale-allowed",              "RETOUR CLIENT",              "b", ""},
            {"return.dedicated-register",           "RETOUR CLIENT",              "b", ""},
            {"return.types",                        "RETOUR CLIENT",              "",  ""},
            {"return.foreign-store-accepted",       "RETOUR CLIENT",              "b", ""},
            {"return.without-origin-allowed",       "RETOUR CLIENT",              "b", ""},
            {"language.system",                     "LANGUE",                     "",  ""},
            {"language.available",                  "LANGUE",                     "",  ""},
            {"language.operator-change",            "LANGUE",                     "b", ""},
            {"language.customer-second",            "LANGUE",                     "",  ""},
            {"keyboard.layout",                     "LANGUE",                     "",  ""},
            {"language.customer-choice",            "LANGUE",                     "b", ""},
            {"language.fidelity-holder",            "LANGUE",                     "b", ""},
            {"island.change-allowed",               "ILOT / TPV",                 "b", ""},
            {"island.auto-declaration",             "ILOT / TPV",                 "b", ""},
            {"island.unknown-article-ticket",       "ILOT / TPV",                 "b", ""},
            {"island.block-article-popups",         "ILOT / TPV",                 "b", ""},
            {"island.block-payment-popups",         "ILOT / TPV",                 "b", ""},
            {"island.promotion-channel",            "ILOT / TPV",                 "",  ""},
            {"balance.default-tare",                "BALANCE",                    "",  ""},
            {"balance.metrology-body",              "BALANCE",                    "",  ""},
            {"balance.metrology-certificate",       "BALANCE",                    "",  ""},
            {"balance.metrology-crc",               "BALANCE",                    "",  ""},
            {"fidelity.send-subfamily",             "FIDÉLITÉ",                   "b", ""},
            {"ticket.ecotax-print",                 "TICKETS",                    "",  ""},
            {"ticket.due-amount-large",             "TICKETS",                    "b", ""},
            {"fiscal.certificate-number",           "FISCAL",                     "",  ""},
            {"fiscal.pt-documents",                 "FISCAL",                     "b", ""},
            {"fiscal.atcud-codes",                  "FISCAL",                     "",  ""},
            {"order.external-code-pattern",         "DEVIS ET COMMANDES",         "",  ""},
            {"order.recall-price-source",           "DEVIS ET COMMANDES",         "",  ""},
            {"order.deposit-invoice",               "DEVIS ET COMMANDES",         "b", ""},
            {"selfscan.code-pattern",               "SELFSCANNING",               "",  ""},
            {"selfscan.partial-control-rate",       "SELFSCANNING",               "",  "i"},
            {"selfscan.partial-control-items",      "SELFSCANNING",               "",  "i"},
            {"giftcard.payment-ticket",             "CARTE CADEAU",               "b", ""},
            {"return.separate-detail",              "RETOUR CLIENT",              "b", ""},
            {"external-program.label",              "PROGRAMME EXTERNE",          "",  ""},
            {"external-program.path",               "PROGRAMME EXTERNE",          "",  ""},
            {"external-program.arguments",          "PROGRAMME EXTERNE",          "",  ""},
            {"fidelity.card-luhn",                  "FIDÉLITÉ",                    "b", ""},
            {"gs1.expiry-block-days",               "CODES GS1",                   "",  "i"},
            {"gs1.expiry-message",                  "CODES GS1",                   "",  ""},
            {"gs1.short-date-message",              "CODES GS1",                   "",  ""},
            {"qbusting.code-pattern",               "QBUSTING",                    "",  ""},
            {"scan.symbologies",                    "SCAN",                        "",  ""},
            {"scan.offline-alert",                  "SCAN",                        "",  ""},
            {"voucher.unknown-alert",               "SCAN",                        "",  ""},
            {"balance.ftp-host",                    "BALANCE",                     "",  ""},
            {"balance.ftp-port",                    "BALANCE",                     "",  "i"},
            {"balance.ftp-user",                    "BALANCE",                     "",  ""},
            {"balance.ftp-password",                "BALANCE",                     "",  ""},
            {"balance.ftp-directory",               "BALANCE",                     "",  ""},
            {"balance.ftp-interval-seconds",        "BALANCE",                     "",  "i"},
            {"balance.print-tare",                  "BALANCE",                    "b", ""},
            {"balance.tare-containers",             "BALANCE",                    "",  ""},
            {"control.cancel-line",                 "POINTS DE CONTRÔLE",         "",  ""},
            {"control.cancel-payment",              "POINTS DE CONTRÔLE",         "",  ""},
            {"control.refund",                      "POINTS DE CONTRÔLE",         "",  ""},
            {"control.drawer-out-of-sale",          "POINTS DE CONTRÔLE",         "",  ""},
            {"control.quantity",                    "POINTS DE CONTRÔLE",         "",  ""},
            {"control.quantity-max",                "POINTS DE CONTRÔLE",         "",  "i"},
            {"control.ticket-total",                "POINTS DE CONTRÔLE",         "",  ""},
            {"control.ticket-total-max",            "POINTS DE CONTRÔLE",         "",  ""},
            {"control.parked-at-close",             "POINTS DE CONTRÔLE",         "",  ""},
            {"control.card-payment",                "POINTS DE CONTRÔLE",         "",  ""},
            {"control.item-amount",                 "POINTS DE CONTRÔLE",         "",  ""},
            {"control.item-amount-max",             "POINTS DE CONTRÔLE",         "",  ""},
            {"control.payment-amount",              "POINTS DE CONTRÔLE",         "",  ""},
            {"control.payment-amount-max",          "POINTS DE CONTRÔLE",         "",  ""},
            {"financial.closed-weekdays",           "ÉTATS FINANCIERS",           "",  ""},
            {"financial.closed-dates",              "ÉTATS FINANCIERS",           "",  ""},
            {"financial.hourly-slot-minutes",       "ÉTATS FINANCIERS",           "",  "i"},
            {"financial.nomenclature-level",        "ÉTATS FINANCIERS",           "",  "i"},
            {"financial.sectors",                   "ÉTATS FINANCIERS",           "",  ""},
            {"financial.best-sellers-count",        "ÉTATS FINANCIERS",           "",  "i"},
            {"financial.auto-emit-enabled",         "ÉTATS FINANCIERS",           "b", ""},
            {"gesture.endorsement-scope",           "GESTES NÉGATIFS",            "",  ""},
            {"payment.trd-card-cap",                "RÈGLEMENTS RESTREINTS",      "",  ""},
            {"export.directory",                    "FLUX SORTANTS",              "",  ""},
            {"export.accounting-cutover-hour",      "FLUX SORTANTS",              "",  "i"},
            {"export.revenue-enabled",              "FLUX SORTANTS",              "b", ""},
            {"export.cash-book-enabled",            "FLUX SORTANTS",              "b", ""},
            {"export.family-sales-enabled",         "FLUX SORTANTS",              "b", ""},
            {"export.transactions-enabled",         "FLUX SORTANTS",              "b", ""},
            {"export.period-summary-enabled",       "FLUX SORTANTS",              "b", ""},
            {"export.productivity-enabled",         "FLUX SORTANTS",              "b", ""},
            {"export.customer-invoices-enabled",    "FLUX SORTANTS",              "b", ""},
            {"export.erp-invoices-enabled",         "FLUX SORTANTS",              "b", ""},
            {"export.fidelity-vat-enabled",         "FLUX SORTANTS",              "b", ""},
            {"export.order-vat-enabled",            "FLUX SORTANTS",              "b", ""},
            {"export.panels-enabled",               "FLUX SORTANTS",              "b", ""},
            {"stream.enabled",                      "FIL DE L'EAU",               "b", ""},
            {"stream.delivery-seconds",             "FIL DE L'EAU",               "",  "i"},
            // lot 18: F rows, at the catalog position of the F block (after stream.delivery-seconds)
            {"drawer.open-in-training",             "TIROIR",                     "b", ""},
            {"scan.unknown-manual-entry",           "SCAN",                       "b", ""},
            {"calc.rounding-mode",                  "ARRONDI DES CALCULS",        "",  ""},
            {"invoice.decimals-line-total-ht",      "FACTURE",                    "",  "i"},
            {"auth.pin-length",                     "SESSION CAISSE",             "",  "i"},
            {"supervisor.validation-mode",          "SESSION CAISSE",             "",  ""},
            {"sale.group-lines",                    "AFFICHAGE",                  "b", ""},
            {"sale.forbidden-message",              "AFFICHAGE",                  "",  ""},
            {"balance.ticket-mode",                 "BALANCE",                    "",  ""},
            {"referential.defer-during-sale",       "SUPERVISION",                "b", ""},
            {"gs1.enabled",                         "CODES GS1",                  "b", ""},
            {"gs1.ai-alerts",                       "CODES GS1",                  "",  ""},
            {"stream.compress-image",               "FIL DE L'EAU",               "b", ""},
        };
        // Compared BY KEY, as the comment above promises: each group of a lot
        // adds its rows under its own marker, wherever the catalog files them.
        assertEquals(expected.length, entries.size());
        java.util.Map<String, AdminSettingsResource.Entry> byKey = new java.util.HashMap<>();
        for (AdminSettingsResource.Entry entry : entries) {
            byKey.put(entry.key, entry);
        }
        assertEquals(expected.length, byKey.size());
        for (String[] row : expected) {
            AdminSettingsResource.Entry entry = byKey.get(row[0]);
            assertTrue(entry != null, "key#" + row[0]);
            assertEquals(row[1], entry.section, "section#" + row[0]);
            assertEquals("b".equals(row[2]), entry.bool, "bool#" + entry.key);
            assertEquals("i".equals(row[3]), entry.integer, "integer#" + entry.key);
            // BO-10-03-08: a secret never travels back to the page.
            assertEquals(PosSettingsService.isSecret(entry.key) ? "" : "V", entry.value, "value#" + entry.key);
        }
        // A section opens on every heading change and ONLY there: consecutive
        // parameters of one heading must not split it into two panels, and two
        // headings must not be folded into one. The runs are read in catalog
        // order, which is the order the entries come in.
        List<String> headings = sections.stream()
                .map(AdminSettingsResource.Section::getLabel).toList();
        List<String> expectedHeadings = new java.util.ArrayList<>();
        String previousSection = null;
        for (PosSettingsService.Def def : PosSettingsService.CATALOG) {
            if (!def.section().equals(previousSection)) {
                expectedHeadings.add(def.section());
            }
            previousSection = def.section();
        }
        assertEquals(expectedHeadings, headings);
        verify(instance).data("settingCount", expected.length);
        verify(instance).data("notice", "hi");
        verify(instance).data("noticeOk", false);
    }

    /**
     * {@code save} normalizes and stores every posted field — a valid integer
     * (parse-ok / non-negative arm), a boolean (normalized) and a text
     * (verbatim) — then redirects green; a missing field is skipped (continue
     * arm).
     */
    @Test
    void saveStoresValidFields() {
        AdminSettingsResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("display.show-ean", "TRUE");
        form.putSingle("auth.idle-lockout-seconds", "30");
        form.putSingle("customer.message-open", "  Bonjour  ");
        // discount.* and the rest are missing -> continue arm.
        Response response = resource.save(form);
        verify(resource.posSettingsService).store("display.show-ean", "true");
        verify(resource.posSettingsService).store("auth.idle-lockout-seconds", "30");
        verify(resource.posSettingsService).store("customer.message-open", "Bonjour");
        verify(resource.posSettingsService, never()).store(eq("discount.line-max-percent"), anyString());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        assertTrue(response.getLocation().toString().contains("3+param"));
    }

    /**
     * A CLOSED-SET PARAMETER IS PROJECTED WITH ITS VALUES, a free one with an
     * empty list, and a value stored OUTSIDE the set joins the list rather than
     * disappearing from it (the widening arm): a node left on the {@code
     * FLOATING} synonym must read {@code FLOATING} on its page, not {@code
     * FIXED}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void settingsPageOffersClosedValuesAndKeepsAStoredSynonym() {
        AdminSettingsResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        when(resource.posSettingsService.value(anyString())).thenReturn("V");
        when(resource.posSettingsService.value("control.cancel-line")).thenReturn("ENDORSE");
        when(resource.posSettingsService.value("cash.float-mode")).thenReturn("FLOATING");
        assertEquals(instance, resource.settingsPage(null, true));
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(resource.adminSettings).data(eq("sections"), captor.capture());
        List<AdminSettingsResource.Section> sections =
                (List<AdminSettingsResource.Section>) captor.getValue();
        java.util.Map<String, AdminSettingsResource.Entry> byKey = new java.util.HashMap<>();
        for (AdminSettingsResource.Section section : sections) {
            for (AdminSettingsResource.Entry entry : section.getEntries()) {
                byKey.put(entry.key, entry);
            }
        }
        assertEquals(List.of("NONE", "WARN", "BLOCK", "ENDORSE"),
                byKey.get("control.cancel-line").choices);
        assertTrue(byKey.get("customer.message-open").choices.isEmpty());
        assertEquals(List.of("FIXED", "FLOATING_WEEK_MONTH", "FLOATING_MONTH", "FLOATING"),
                byKey.get("cash.float-mode").choices);
    }

    /**
     * {@code save} refuses a word outside the closed set — naming what was
     * expected — and stores nothing: the register would not recognise it and
     * would fall back on the default, leaving the shop with a control it
     * believes it has set.
     */
    @Test
    void saveRejectsAValueOutsideTheClosedSet() {
        AdminSettingsResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("control.cancel-line", "BLOK");
        Response response = resource.save(form);
        verify(resource.posSettingsService, never()).store(eq("control.cancel-line"), anyString());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} lets the value ALREADY STORED through even when the set no
     * longer offers it (the unchanged arm), so a node carrying a synonym keeps
     * a saveable page.
     */
    @Test
    void saveAcceptsTheStoredValueOutsideTheClosedSet() {
        AdminSettingsResource resource = newResource();
        when(resource.posSettingsService.value("cash.float-mode")).thenReturn("FLOATING");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("cash.float-mode", "FLOATING");
        Response response = resource.save(form);
        verify(resource.posSettingsService).store("cash.float-mode", "FLOATING");
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
    }

    /**
     * {@code save} rejects a non-numeric integer with a red redirect (invalid
     * arm) and stores nothing past it (early return).
     */
    @Test
    void saveRejectsNonNumericInteger() {
        AdminSettingsResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("auth.idle-lockout-seconds", "abc");
        Response response = resource.save(form);
        verify(resource.posSettingsService, never()).store(anyString(), anyString());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} rejects a negative integer with a red redirect (negative
     * arm of the invalid guard).
     */
    @Test
    void saveRejectsNegativeInteger() {
        AdminSettingsResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("auth.idle-lockout-seconds", "-5");
        Response response = resource.save(form);
        verify(resource.posSettingsService, never()).store(anyString(), anyString());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} normalizes a boolean field to its canonical text form.
     */
    @Test
    void saveNormalizesBoolean() {
        AdminSettingsResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("gesture.endorsement-required", "garbage");
        Response response = resource.save(form);
        verify(resource.posSettingsService).store("gesture.endorsement-required", "false");
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        assertFalse(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code adminRoot} routes a signed-in administrator to the parameters:
     * the non-null arm of the identity guard, the true arm of {@code hasRole}
     * and the true arm of the routing ternary.
     */
    @Test
    void adminRootRoutesAdminToSettings() {
        AdminSettingsResource resource = newResource();
        resource.identity = mock(SecurityIdentity.class);
        when(resource.identity.hasRole(Employee.EmployeeRole.ADMIN.name())).thenReturn(true);
        Response response = resource.adminRoot();
        assertEquals(303, response.getStatus());
        assertEquals("/admin/settings", response.getLocation().toString());
    }

    /**
     * {@code adminRoot} routes a signed-in non-administrator to the
     * supervision: the non-null arm of the identity guard, the false arm of
     * {@code hasRole} and the false arm of the routing ternary.
     */
    @Test
    void adminRootRoutesNonAdminToDashboard() {
        AdminSettingsResource resource = newResource();
        resource.identity = mock(SecurityIdentity.class);
        when(resource.identity.hasRole(Employee.EmployeeRole.ADMIN.name())).thenReturn(false);
        Response response = resource.adminRoot();
        assertEquals(303, response.getStatus());
        assertEquals("/dashboard", response.getLocation().toString());
    }

    /**
     * {@code adminRoot} routes to the supervision when there is no identity:
     * the null arm of the identity guard short-circuits before {@code hasRole}
     * and lands on the false arm of the routing ternary.
     */
    @Test
    void adminRootRoutesNullIdentityToDashboard() {
        AdminSettingsResource resource = newResource();
        resource.identity = null;
        Response response = resource.adminRoot();
        assertEquals(303, response.getStatus());
        assertEquals("/dashboard", response.getLocation().toString());
    }
    /** BO-03: canonical, blank and absent closed settings remain displayed without adding false choices. */
    @Test
    void settingsProjectionHandlesBlankAndAbsentClosedValues() {
        AdminSettingsResource resource = newResource();
        TemplateInstance view = wireTemplate(resource);
        for (String current : new String[]{null, "", "FIXED"}) {
            when(resource.posSettingsService.value("cash.float-mode")).thenReturn(current);
            org.junit.jupiter.api.Assertions.assertSame(view, resource.settingsPage(null, true));
        }
        var posted = new MultivaluedHashMap<String, String>();
        posted.putSingle("cash.float-mode", "FIXED");
        assertTrue(resource.save(posted).getLocation().toString().contains("noticeOk=true"));
        verify(resource.posSettingsService).store("cash.float-mode", "FIXED");
    }


    /**
     * {@code adminRoot} lands a profile-based operator on a screen they may
     * open: the supervision when their navigation holds it, otherwise its
     * first screen, and their favorites when no screen is opened to them.
     */
    @Test
    void adminRootLandsOnAnOpenScreen() {
        AdminSettingsResource resource = newResource();
        resource.identity = mock(SecurityIdentity.class);
        resource.backOfficeMenu = mock(BackOfficeMenu.class);
        BackOfficeMenu.Group articles = new BackOfficeMenu.Group("Articles",
                java.util.List.of(new BackOfficeMenu.Entry("Articles", "/admin/articles")));
        BackOfficeMenu.Group supervision = new BackOfficeMenu.Group("Supervision", java.util.List.of(
                new BackOfficeMenu.Entry("Journal", "/admin/journal"),
                new BackOfficeMenu.Entry("Supervision de la ligne", "/dashboard")));
        when(resource.backOfficeMenu.getGroups()).thenReturn(java.util.List.of(), java.util.List.of(articles),
                java.util.List.of(articles, supervision));
        assertEquals("/admin/favorites", resource.adminRoot().getLocation().toString());
        assertEquals("/admin/articles", resource.adminRoot().getLocation().toString());
        assertEquals("/dashboard", resource.adminRoot().getLocation().toString());
        when(resource.identity.hasRole(Employee.EmployeeRole.ADMIN.name())).thenReturn(true);
        assertEquals("/admin/settings", resource.adminRoot().getLocation().toString());
    }

    /**
     * BO-10-03-08: the loyalty password is offered as a masked, EMPTY field that
     * only says whether a value is stored — stored, not stored, and a key the
     * service cannot resolve (null) — while an ordinary text keeps its value.
     */
    @Test
    @SuppressWarnings("unchecked")
    void settingsPageMasksTheSecret() {
        for (String stored : new String[] {"s3cret", "", null}) {
            AdminSettingsResource resource = newResource();
            wireTemplate(resource);
            when(resource.posSettingsService.value(anyString())).thenReturn("V");
            when(resource.posSettingsService.value("fidelity.password")).thenReturn(stored);
            resource.settingsPage(null, true);
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(resource.adminSettings).data(eq("sections"), captor.capture());
            java.util.Map<String, AdminSettingsResource.Entry> byKey = new java.util.HashMap<>();
            for (AdminSettingsResource.Section section : (List<AdminSettingsResource.Section>) captor.getValue()) {
                for (AdminSettingsResource.Entry entry : section.getEntries()) {
                    byKey.put(entry.key, entry);
                }
            }
            AdminSettingsResource.Entry secret = byKey.get("fidelity.password");
            assertTrue(secret.secret);
            assertEquals("", secret.value);
            assertEquals("s3cret".equals(stored), secret.secretSet, "secretSet#" + stored);
            assertFalse(byKey.get("fidelity.user").secret);
            assertEquals("V", byKey.get("fidelity.user").value);
        }
    }

    /**
     * BO-10-03-08: a secret field left EMPTY keeps the stored secret (nothing
     * stored), a typed one is stored, and an empty ordinary text is stored as
     * empty.
     */
    @Test
    void saveKeepsAnEmptySecretAndStoresATypedOne() {
        AdminSettingsResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("fidelity.password", "  ");
        form.putSingle("fidelity.user", "");
        resource.save(form);
        verify(resource.posSettingsService, never()).store(eq("fidelity.password"), anyString());
        verify(resource.posSettingsService).store("fidelity.user", "");
        form.remove("fidelity.user");
        form.putSingle("fidelity.password", "n3w");
        Response response = resource.save(form);
        verify(resource.posSettingsService).store("fidelity.password", "n3w");
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
    }
}
