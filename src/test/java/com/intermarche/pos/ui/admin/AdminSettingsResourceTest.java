package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.service.PosSettingsService;
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
            {"auth.idle-lockout-seconds",           "SESSION CAISSE",             "",  "i"},
            {"gesture.endorsement-required",        "GESTES DE PRIX",             "b", ""},
            {"discount.line-max-percent",           "GESTES DE PRIX",             "",  "i"},
            {"discount.global-max-percent",         "GESTES DE PRIX",             "",  "i"},
            {"discount.enabled",                    "GESTES DE PRIX",             "b", ""},
            {"price.show-original-on-force",        "GESTES DE PRIX",             "b", ""},
            {"customer.message-open",               "AFFICHEUR CLIENT",           "",  ""},
            {"customer.message-closed",             "AFFICHEUR CLIENT",           "",  ""},
            {"customer.qr-enabled",                 "AFFICHEUR CLIENT",           "b", ""},
            {"parking.print-receipt",               "TICKETS",                    "b", ""},
            {"ticket.email-format",                 "TICKETS",                    "",  ""},
            {"ticket.email-editable",               "TICKETS",                    "b", ""},
            {"ticket.line-order",                   "TICKETS",                    "",  ""},
            {"print.conditional-enabled",           "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"print.forced-documents",              "IMPRESSION CONDITIONNELLE",  "",  ""},
            {"print.force-ticket-glc",              "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"print.force-card-credit",             "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"print.force-card-signature",          "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"print.force-card-tna",                "IMPRESSION CONDITIONNELLE",  "b", ""},
            {"payment.degraded-mode",               "MONÉTIQUE",                  "b", ""},
            {"payment.degraded-forced-endorsement", "MONÉTIQUE",                  "b", ""},
            {"payment.degraded-forced-minutes",     "MONÉTIQUE",                  "",  "i"},
            {"ticket.header-message",               "MESSAGES TICKET",            "",  ""},
            {"ticket.footer-message",               "MESSAGES TICKET",            "",  ""},
            {"drawer.open-on-payment",              "TIROIR",                     "b", ""},
            {"drawer.open-on-login",                "TIROIR",                     "b", ""},
            {"backup.manual-endorsement",           "SECOURS MONETIQUE",          "b", ""},
            {"backup.method-labels",                "SECOURS MONETIQUE",          "",  ""},
            {"balance.counter-price",               "TICKET COMPTOIR",            "b", ""},
            {"cash.rounding-step-cents",            "ARRONDI ESPECES",            "",  "i"},
            {"credit.allowed-in-degraded",          "CREDIT CLIENT",              "b", ""},
            {"customer.account-pattern",            "CREDIT CLIENT",              "",  ""},
            {"credit.degraded-after-minutes",       "CREDIT CLIENT",              "",  "i"},
            {"customer.volatile-creation-enabled",    "CLIENT EN CAISSE",           "b", ""},
            {"customer.account-creation-blocked",     "CLIENT EN CAISSE",           "b", ""},
            {"customer.show-details",                 "CLIENT EN CAISSE",           "b", ""},
            {"customer.update-enabled",               "CLIENT EN CAISSE",           "b", ""},
            {"customer.show-discount",                "CLIENT EN CAISSE",           "b", ""},
            {"customer.discount-segment",             "CLIENT EN CAISSE",           "", ""},
            {"customer.credit-tender",                "CLIENT EN CAISSE",           "", ""},
            {"customer.partial-credit-allowed",       "CLIENT EN CAISSE",           "b", ""},
            {"customer.offline-message",              "CLIENT EN CAISSE",           "", ""},
            {"customer.over-limit-message",           "CLIENT EN CAISSE",           "", ""},
            {"customer.default-tax-id",               "CLIENT EN CAISSE",           "", ""},
            {"customer.default-tax-id-label",         "CLIENT EN CAISSE",           "", ""},
            {"customer.foreign-tax-id-countries",     "CLIENT EN CAISSE",           "", ""},
            {"customer.invoice-export-due-date",      "CLIENT EN CAISSE",           "b", ""},
            {"customer.invoice-export-ecotax",        "CLIENT EN CAISSE",           "b", ""},
            {"scan.ean13-check-digit",              "SCAN",                       "b", ""},
            {"dashboard.alerts-enabled",            "SUPERVISION",                "b", ""},
            {"supervisor.service-requests",         "SUPERVISION",                "",  ""},
            {"fidelity.advantages-enabled",         "FIDÉLITÉ",                   "b", ""},
            {"fidelity.projected-label",            "FIDÉLITÉ",                   "",  ""},
            {"fidelity.allow-multiple-scan",        "FIDÉLITÉ",                   "b", ""},
            {"cash.movement-endorsement-threshold", "MOUVEMENTS DE CAISSE",       "",  ""},
            {"cash.movement-reasons",               "MOUVEMENTS DE CAISSE",       "",  ""},
            {"invoice.customer-fields",             "FACTURE",                    "",  ""},
            {"invoice.document-types",              "FACTURE",                    "",  ""},
            {"invoice.document-output",             "FACTURE",                    "",  ""},
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
            {"gs1.expiry-alert",                    "CODES GS1",                  "",  ""},
            {"gs1.expiry-warn-days",                "CODES GS1",                  "",  "i"},
            {"gs1.coupon-expiry-alert",             "CODES GS1",                  "",  ""},
            {"gs1.gdti-coupon-types",               "CODES GS1",                  "",  ""},
            {"gs1.gcn-coupon-type",                 "CODES GS1",                  "",  ""},
            {"touch.groups-per-page",               "TOUCHES CAISSE",             "",  "i"},
            {"touch.display-order",                 "TOUCHES CAISSE",             "",  ""},
            {"auth.badge-scan-enabled",             "SESSION CAISSE",             "b", ""},
            {"auth.password-required-on-open",      "SESSION CAISSE",             "b", ""},
            {"auth.password-required-on-close",     "SESSION CAISSE",             "b", ""},
            {"session.close-endorsement-on-pending", "SESSION CAISSE",            "b", ""},
            {"session.print-open-receipt",          "TICKETS",                    "b", ""},
            {"session.print-close-receipt",         "TICKETS",                    "b", ""},
            {"period.auto-close-enabled",           "FIN DE PÉRIODE",             "b", ""},
            {"period.end-time",                     "FIN DE PÉRIODE",             "",  ""},
            {"period.close-lead-minutes",           "FIN DE PÉRIODE",             "",  "i"},
            {"cash.default-opening-float",          "SESSION CAISSE",             "",  ""},
            {"drawer.open-on-session-close",        "TIROIR",                     "b", ""},
            {"ticket.vat-breakdown-enabled",        "TICKETS",                    "b", ""},
            {"fidelity.external-enabled",           "FIDÉLITÉ",                   "b", ""},
            {"cash.movement-tenders",               "MOUVEMENTS DE CAISSE",       "",  ""},
            {"discount.line-max-amount",            "GESTES DE PRIX",             "",  ""},
            {"discount.global-max-amount",          "GESTES DE PRIX",             "",  ""},
            {"fidelity.show-holder-name",           "FIDÉLITÉ",                   "b", ""},
            {"fidelity.card-pattern",               "FIDÉLITÉ",                   "",  ""},
            {"fidelity.jv-promotion-enabled",       "FIDÉLITÉ",                   "b", ""},
            {"fidelity.url",                        "FIDÉLITÉ",                   "",  ""},
            {"fidelity.user",                       "FIDÉLITÉ",                   "",  ""},
            {"fidelity.offline-message",            "FIDÉLITÉ",                   "",  ""},
            {"fidelity.offline-message-no-card",    "FIDÉLITÉ",                   "",  ""},
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
            {"retention.customers-days",            "RÉTENTION DES DONNÉES",      "",  "i"},
            {"drawer.content-ceiling",              "TIROIR",                     "",  ""},
            {"cash.discrepancy-threshold",          "SESSION CAISSE",             "",  ""},
            {"cash.float-mode",                     "SESSION CAISSE",             "",  ""},
            {"auth.endorse-line-cancel",            "AUTORISATIONS",              "b", ""},
            {"auth.endorse-last-item-cancel",       "AUTORISATIONS",              "b", ""},
            {"auth.endorse-ticket-abandon",         "AUTORISATIONS",              "b", ""},
            {"auth.endorse-ticket-return",          "AUTORISATIONS",              "b", ""},
            {"auth.endorse-return-without-ticket",  "AUTORISATIONS",              "b", ""},
            {"auth.endorse-refund",                 "AUTORISATIONS",              "b", ""},
            {"auth.endorse-discount",               "AUTORISATIONS",              "b", ""},
            {"auth.endorse-price-override",         "AUTORISATIONS",              "b", ""},
            {"auth.endorsement-scope",              "AUTORISATIONS",              "",  ""},
            {"ui.login-greeting",                   "SESSION CAISSE",             "",  ""},
            {"customer.next-customer-message",      "AFFICHEUR CLIENT",           "",  ""},
        };
        assertEquals(expected.length, entries.size());
        for (int i = 0; i < expected.length; i++) {
            AdminSettingsResource.Entry entry = entries.get(i);
            assertEquals(expected[i][0], entry.key, "key#" + i);
            assertEquals(expected[i][1], entry.section, "section#" + i);
            assertEquals("b".equals(expected[i][2]), entry.bool, "bool#" + entry.key);
            assertEquals("i".equals(expected[i][3]), entry.integer, "integer#" + entry.key);
            assertEquals("V", entry.value);
        }
        // A section opens on every heading change and ONLY there: consecutive
        // parameters of one heading must not split it into two panels, and two
        // headings must not be folded into one.
        List<String> headings = sections.stream()
                .map(AdminSettingsResource.Section::getLabel).toList();
        List<String> expectedHeadings = new java.util.ArrayList<>();
        String previousSection = null;
        for (String[] row : expected) {
            if (!row[1].equals(previousSection)) {
                expectedHeadings.add(row[1]);
            }
            previousSection = row[1];
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
}
