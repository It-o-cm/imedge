package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.audit.AdminAuditEntry;
import com.intermarche.edge.domain.setting.Feature;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminAuditResource} (BO-01-01-01, BO-01-01-03),
 * targeting 100% branch coverage: the period defaulted and given, the account
 * and screen filters blank and given; an entry complete, and one with every
 * optional part absent, an unknown screen and option; every outcome label;
 * the CSV extraction with its separators replaced.
 */
class AdminAuditResourceTest {

    /**
     * A complete entry.
     *
     * @return the entry
     */
    private AdminAuditEntry complete() {
        AdminAuditEntry entry = new AdminAuditEntry();
        entry.occurredAt = LocalDateTime.of(2026, 10, 24, 18, 5, 9);
        entry.loginName = "admin";
        entry.fullName = "Le Manager";
        entry.featureKey = Feature.ACCOUNT.getKey();
        entry.option = "DELETE";
        entry.requestPath = "POST /admin/accounts/delete";
        entry.targetRef = "12";
        entry.outcome = AdminAuditEntry.Outcome.DONE;
        entry.detail = "Compte supprimé; définitivement";
        entry.validatorLogin = "mcurie";
        entry.pdv = "07039";
        return entry;
    }

    /**
     * An entry with every optional part absent, an unknown screen and option.
     *
     * @return the entry
     */
    private AdminAuditEntry bare() {
        AdminAuditEntry entry = new AdminAuditEntry();
        entry.loginName = "ghost";
        entry.featureKey = "gone";
        entry.option = "SOMETHING";
        entry.requestPath = "GET /admin/gone";
        return entry;
    }

    /**
     * The log lists the filtered entries over the period asked, or the last
     * seven days, and carries the filters back.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theLogIsListed() {
        AdminAuditResource resource = new AdminAuditResource();
        resource.adminAudit = mock(Template.class);
        TemplateInstance view = mock(TemplateInstance.class, RETURNS_SELF);
        when(resource.adminAudit.data(anyString(), any())).thenReturn(view);
        try (MockedStatic<AdminAuditEntry> entries = mockStatic(AdminAuditEntry.class)) {
            entries.when(() -> AdminAuditEntry.search(any(), any(), any(), any())).thenReturn(List.of(complete(), bare()));
            assertSame(view, resource.list("2026-10-01", "2026-10-24", " admin ", " account ", 1));
            entries.verify(() -> AdminAuditEntry.search(LocalDateTime.of(2026, 10, 1, 0, 0),
                    LocalDateTime.of(2026, 10, 25, 0, 0), "admin", "account"));
            verify(view).data("login", "admin");
            verify(view).data("feature", "account");
            ArgumentCaptor<Object> page = ArgumentCaptor.forClass(Object.class);
            verify(resource.adminAudit).data(eq("page"), page.capture());
            List<AdminAuditResource.AuditRow> rows =
                    ((com.intermarche.edge.ui.journal.JournalPage<AdminAuditResource.AuditRow>) page.getValue()).rows;
            AdminAuditResource.AuditRow first = rows.get(0);
            assertEquals("24/10/2026 18:05:09", first.moment);
            assertEquals("Comptes", first.feature);
            assertEquals("Suppression", first.option);
            assertEquals("Effectué", first.outcome);
            assertTrue(first.done);
            AdminAuditResource.AuditRow second = rows.get(1);
            assertEquals("", second.moment);
            assertEquals("gone", second.feature);
            assertEquals("SOMETHING", second.option);
            assertEquals("", second.outcome);
            assertFalse(second.done);
            assertEquals("", second.fullName);
            resource.list(null, null, null, " ", 1);
            entries.verify(() -> AdminAuditEntry.search(LocalDate.now().minusDays(7).atStartOfDay(),
                    LocalDate.now().plusDays(1).atStartOfDay(), null, null));
            verify(view).data("login", "");
        }
    }

    /**
     * The extraction carries every entry, its separators replaced.
     */
    @Test
    void theLogIsExtracted() {
        AdminAuditResource resource = new AdminAuditResource();
        AdminAuditEntry withNullOption = bare();
        withNullOption.option = null;
        withNullOption.loginName = null;
        withNullOption.outcome = AdminAuditEntry.Outcome.DENIED;
        try (MockedStatic<AdminAuditEntry> entries = mockStatic(AdminAuditEntry.class)) {
            entries.when(() -> AdminAuditEntry.search(any(), any(), isNull(), isNull()))
                    .thenReturn(List.of(complete(), withNullOption));
            Response response = resource.export(null, "2026-10-24", null, null);
            String csv = (String) response.getEntity();
            assertTrue(csv.startsWith("DATE;IDENTIFIANT;NOM;ECRAN;OPTION;REQUETE;CIBLE;ISSUE;DETAIL;VALIDEUR;PDV\n"));
            assertTrue(csv.contains("Compte supprimé, définitivement;mcurie;07039\n"));
            assertTrue(csv.contains(";;Droit refusé;;;\n"));
            assertTrue(csv.contains("\n;;;gone;"));
            assertTrue(response.getHeaderString("Content-Disposition").endsWith("-2026-10-24.csv\""));
        }
    }

    /**
     * Every outcome reads in French.
     */
    @Test
    void outcomesAreLabelled() {
        assertEquals("", AdminAuditResource.outcomeLabel(null));
        assertEquals("Effectué", AdminAuditResource.outcomeLabel(AdminAuditEntry.Outcome.DONE));
        assertEquals("Refusé par l'écran", AdminAuditResource.outcomeLabel(AdminAuditEntry.Outcome.REFUSED));
        assertEquals("Droit refusé", AdminAuditResource.outcomeLabel(AdminAuditEntry.Outcome.DENIED));
        assertEquals("Validation", AdminAuditResource.outcomeLabel(AdminAuditEntry.Outcome.VALIDATION));
    }
}
