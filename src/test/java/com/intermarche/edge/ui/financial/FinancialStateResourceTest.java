package com.intermarche.edge.ui.financial;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.FinancialState;
import com.intermarche.edge.domain.session.FinancialState.PeriodKind;
import com.intermarche.edge.domain.session.FinancialState.StateScope;
import com.intermarche.edge.domain.session.FinancialStateLine.StateSection;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.service.PermissionService;
import com.intermarche.edge.ui.report.ReportCriteria;
import com.intermarche.edge.ui.report.ReportData;
import com.intermarche.edge.ui.report.ReportService;
import com.intermarche.edge.ui.report.ReportWriter;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FinancialStateResource}.
 *
 * <p>Plain JUnit 5 + Mockito: the Qute templates, the permission layer, the
 * identity, the screen service and the report writers are all mocked, so the
 * resource's routing, permission gating and parameter parsing are tested without
 * Quarkus. A denied right redirects (303); a missing object answers 404.
 */
class FinancialStateResourceTest {

    /** The chainable template instance every template returns. */
    private TemplateInstance instance;

    /**
     * Builds a resource with mocked collaborators, authorised by default.
     *
     * @return the resource
     */
    private FinancialStateResource newResource() {
        FinancialStateResource resource = new FinancialStateResource();
        instance = mock(TemplateInstance.class);
        when(instance.data(anyString(), any())).thenReturn(instance);
        Template listTemplate = mock(Template.class);
        when(listTemplate.data(anyString(), any())).thenReturn(instance);
        Template detailTemplate = mock(Template.class);
        when(detailTemplate.data(anyString(), any())).thenReturn(instance);
        resource.adminFinancialStates = listTemplate;
        resource.adminFinancialState = detailTemplate;
        resource.financialStateService = mock(FinancialStateService.class);
        resource.permissionService = mock(PermissionService.class);
        resource.reportService = mock(ReportService.class);
        resource.identity = mock(SecurityIdentity.class);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("admin");
        when(resource.identity.getPrincipal()).thenReturn(principal);
        return resource;
    }

    /**
     * Authorises or refuses the given option on the financial-state feature.
     *
     * @param resource the resource
     * @param option the option
     * @param allowed whether it is granted
     */
    private void grant(FinancialStateResource resource, CrudOption option, boolean allowed) {
        when(resource.permissionService.isAllowed("admin", Feature.FINANCIAL_STATE, option)).thenReturn(allowed);
    }

    /**
     * The list page renders when the view right is granted.
     */
    @Test
    void listPageRendersWhenAllowed() {
        FinancialStateResource resource = newResource();
        grant(resource, CrudOption.VIEW, true);
        when(resource.financialStateService.listEmitted()).thenReturn(List.of());
        Object result = resource.listPage(null, true);
        assertSame(instance, result);
        verify(resource.financialStateService).listEmitted();
    }

    /**
     * The list page flags the states the service reports anomalous and leaves the
     * clean ones unflagged, exercising both arms of the flag loop.
     */
    @Test
    void listPageFlagsAnomalousStates() {
        FinancialStateResource resource = newResource();
        grant(resource, CrudOption.VIEW, true);
        FinancialState flagged = new FinancialState();
        flagged.id = 1L;
        FinancialState clean = new FinancialState();
        clean.id = 2L;
        when(resource.financialStateService.listEmitted()).thenReturn(List.of(flagged, clean));
        when(resource.financialStateService.hasAnomaly(flagged)).thenReturn(true);
        when(resource.financialStateService.hasAnomaly(clean)).thenReturn(false);
        Object result = resource.listPage(null, true);
        assertSame(instance, result);
        verify(resource.financialStateService).hasAnomaly(flagged);
        verify(resource.financialStateService).hasAnomaly(clean);
    }

    /**
     * Reading a cashier-scope X resolves the cashier by login.
     */
    @Test
    void readXResolvesTheCashierScope() {
        FinancialStateResource resource = newResource();
        grant(resource, CrudOption.VIEW, true);
        Employee bob = new Employee();
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(bob);
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            statics.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(query);
            when(resource.financialStateService.readX(StateScope.CASHIER, PeriodKind.WEEK, bob))
                    .thenReturn(new FinancialState());
            Object result = resource.readX("CASHIER", "WEEK", "bob");
            assertSame(instance, result);
            verify(resource.financialStateService).readX(StateScope.CASHIER, PeriodKind.WEEK, bob);
        }
    }

    /**
     * Emitting a cashier-scope Z resolves the cashier by login.
     */
    @Test
    void emitResolvesTheCashierScope() {
        FinancialStateResource resource = newResource();
        grant(resource, CrudOption.CREATE, true);
        Employee someone = new Employee();
        FinancialState emitted = new FinancialState();
        emitted.id = 4L;
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(someone);
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            statics.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(query);
            when(resource.financialStateService.emitManual(eq(StateScope.CASHIER), eq(PeriodKind.DAY), eq(someone), any()))
                    .thenReturn(emitted);
            Response response = (Response) resource.emit("CASHIER", "DAY", "bob");
            assertEquals(303, response.getStatus());
            assertEquals("/admin/financial-states/4", response.getLocation().getPath());
        }
    }

    /**
     * The list page redirects when the view right is refused.
     */
    @Test
    void listPageRedirectsWhenDenied() {
        FinancialStateResource resource = newResource();
        grant(resource, CrudOption.VIEW, false);
        Object result = resource.listPage(null, true);
        assertEquals(303, ((jakarta.ws.rs.core.Response) result).getStatus());
    }

    /**
     * Reading an X renders on the view right and calls the service for the store
     * scope, no cashier resolved.
     */
    @Test
    void readXRendersAndReadsTheStoreScope() {
        FinancialStateResource resource = newResource();
        grant(resource, CrudOption.VIEW, true);
        when(resource.financialStateService.readX(StateScope.STORE, PeriodKind.DAY, null))
                .thenReturn(new FinancialState());
        Object result = resource.readX("STORE", "DAY", null);
        assertSame(instance, result);
        verify(resource.financialStateService).readX(StateScope.STORE, PeriodKind.DAY, null);
    }

    /**
     * Reading an X redirects when the view right is refused.
     */
    @Test
    void readXRedirectsWhenDenied() {
        FinancialStateResource resource = newResource();
        grant(resource, CrudOption.VIEW, false);
        Object result = resource.readX("STORE", "DAY", null);
        assertEquals(303, ((jakarta.ws.rs.core.Response) result).getStatus());
    }

    /**
     * Showing a Z renders the document when it exists and the view right is granted.
     */
    @Test
    void showRendersAnExistingState() {
        FinancialStateResource resource = newResource();
        grant(resource, CrudOption.VIEW, true);
        when(resource.financialStateService.byId(5L)).thenReturn(new FinancialState());
        Object result = resource.show(5L);
        assertSame(instance, result);
    }

    /**
     * Showing an unknown Z answers 404.
     */
    @Test
    void showAnswers404WhenUnknown() {
        FinancialStateResource resource = newResource();
        grant(resource, CrudOption.VIEW, true);
        when(resource.financialStateService.byId(5L)).thenReturn(null);
        Object result = resource.show(5L);
        assertEquals(404, ((jakarta.ws.rs.core.Response) result).getStatus());
    }

    /**
     * Showing a Z redirects when the view right is refused.
     */
    @Test
    void showRedirectsWhenDenied() {
        FinancialStateResource resource = newResource();
        grant(resource, CrudOption.VIEW, false);
        Object result = resource.show(5L);
        assertEquals(303, ((jakarta.ws.rs.core.Response) result).getStatus());
    }

    /**
     * Emitting a Z needs the CREATE right, resolves the operator and redirects to
     * the emitted document.
     */
    @Test
    void emitCreatesAndRedirectsToTheDocument() {
        FinancialStateResource resource = newResource();
        grant(resource, CrudOption.CREATE, true);
        Employee operator = new Employee();
        FinancialState emitted = new FinancialState();
        emitted.id = 9L;
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(operator);
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            statics.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(query);
            when(resource.financialStateService.emitManual(eq(StateScope.STORE), eq(PeriodKind.DAY), eq(null), any()))
                    .thenReturn(emitted);
            Response response = (Response) resource.emit("STORE", "DAY", null);
            assertEquals(303, response.getStatus());
            assertEquals("/admin/financial-states/9", response.getLocation().getPath());
        }
    }

    /**
     * Emitting a Z redirects (refused) when the CREATE right is missing — emitting
     * is not a reading (§6).
     */
    @Test
    void emitRedirectsWhenCreateDenied() {
        FinancialStateResource resource = newResource();
        grant(resource, CrudOption.CREATE, false);
        Response response = (Response) resource.emit("STORE", "DAY", null);
        assertEquals(303, response.getStatus());
        assertEquals("/admin", response.getLocation().getPath());
    }

    /**
     * Exporting a section streams the writer's bytes as an attachment.
     */
    @Test
    void exportStreamsTheWriterBytes() {
        FinancialStateResource resource = newResource();
        grant(resource, CrudOption.VIEW, true);
        FinancialState state = new FinancialState();
        state.id = 3L;
        when(resource.financialStateService.byId(3L)).thenReturn(state);
        ReportData data = new ReportData(
                new FinancialStateService.SectionReportDefinition("financial-3-vat", "l", FinancialStateService.COLUMNS),
                List.of());
        when(resource.financialStateService.export(state, StateSection.VAT)).thenReturn(data);
        ReportWriter writer = mock(ReportWriter.class);
        when(writer.write(any(ReportData.class), any(ReportCriteria.class))).thenReturn(new byte[]{1, 2});
        when(writer.contentType()).thenReturn("text/csv");
        when(writer.extension()).thenReturn("csv");
        when(resource.reportService.writer("csv")).thenReturn(writer);
        Response response = resource.export(3L, "VAT", "csv");
        assertEquals(200, response.getStatus());
        assertEquals("text/csv", response.getMediaType().toString());
    }

    /**
     * Exporting answers 404 when the state, the section or the writer cannot be
     * resolved, and redirects when the view right is refused.
     */
    @Test
    void exportAnswers404OrRedirects() {
        FinancialStateResource resource = newResource();
        grant(resource, CrudOption.VIEW, true);
        ReportWriter writer = mock(ReportWriter.class);
        when(resource.reportService.writer("csv")).thenReturn(writer);
        when(resource.financialStateService.byId(3L)).thenReturn(null);
        assertEquals(404, resource.export(3L, "VAT", "csv").getStatus());
        when(resource.financialStateService.byId(3L)).thenReturn(new FinancialState());
        assertEquals(404, resource.export(3L, "NOPE", "csv").getStatus());
        when(resource.reportService.writer("csv")).thenReturn(null);
        assertEquals(404, resource.export(3L, "VAT", "csv").getStatus());
        grant(resource, CrudOption.VIEW, false);
        assertEquals(303, resource.export(3L, "VAT", "csv").getStatus());
    }

    /**
     * The parsers default an absent or unknown scope to the store, an unknown
     * period to the day, and answer null for an unknown section.
     */
    @Test
    void parsersDefaultAndTolerateUnknownValues() {
        FinancialStateResource resource = newResource();
        assertEquals(StateScope.STORE, resource.parseScope(null));
        assertEquals(StateScope.CASHIER, resource.parseScope("cashier"));
        assertEquals(PeriodKind.DAY, resource.parsePeriod("bogus"));
        assertEquals(PeriodKind.MONTH, resource.parsePeriod("month"));
        assertEquals(StateSection.SETTLEMENT, resource.parseSection("settlement"));
        assertEquals(null, resource.parseSection("nope"));
    }
}
