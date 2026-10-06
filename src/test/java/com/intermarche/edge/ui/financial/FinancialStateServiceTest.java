package com.intermarche.edge.ui.financial;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.FinancialState;
import com.intermarche.edge.domain.session.FinancialState.PeriodKind;
import com.intermarche.edge.domain.session.FinancialState.StateScope;
import com.intermarche.edge.domain.session.FinancialState.StateType;
import com.intermarche.edge.domain.session.FinancialStateLine;
import com.intermarche.edge.domain.session.FinancialStateLine.StateSection;
import com.intermarche.edge.service.FinancialStateEmissionService;
import com.intermarche.edge.ui.report.ReportData;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FinancialStateService}.
 *
 * <p>Plain JUnit 5 + Mockito. The emission service is mocked; the Panache finders
 * are intercepted through {@link PanacheEntityBase}. No Quarkus, no database.
 */
class FinancialStateServiceTest {

    /**
     * Builds a service with a mocked emission service.
     *
     * @return the service
     */
    private FinancialStateService newService() {
        FinancialStateService service = new FinancialStateService();
        service.emissionService = mock(FinancialStateEmissionService.class);
        return service;
    }

    /**
     * Builds a line of a section carrying an amount.
     *
     * @param section the section
     * @param code the code
     * @param amount the amount
     * @return the line
     */
    private FinancialStateLine line(StateSection section, String code, String amount) {
        FinancialStateLine line = new FinancialStateLine(section, 0, code, code, new BigDecimal(amount));
        return line;
    }

    /**
     * {@code periodBound} gives the next midnight for a day, the next Monday for a
     * week, and the first of next month for a month (R5).
     */
    @Test
    void periodBoundCoversTheThreePeriods() {
        FinancialStateService service = newService();
        LocalDate day = LocalDate.of(2026, 1, 7);
        assertEquals(LocalDateTime.of(2026, 1, 8, 0, 0), service.periodBound(PeriodKind.DAY, day));
        assertEquals(LocalDateTime.of(2026, 1, 12, 0, 0), service.periodBound(PeriodKind.WEEK, day));
        assertEquals(LocalDateTime.of(2026, 2, 1, 0, 0), service.periodBound(PeriodKind.MONTH, day));
    }

    /**
     * {@code hasAnomaly} flags a non-zero control variance, flags a late ticket,
     * and clears a state with neither (a null variance counting as none).
     */
    @Test
    void hasAnomalyFlagsVarianceOrLateTickets() {
        FinancialStateService service = newService();
        FinancialState variance = new FinancialState();
        variance.controlVariance = new BigDecimal("1.0000");
        assertTrue(service.hasAnomaly(variance));
        FinancialState late = new FinancialState();
        late.controlVariance = BigDecimal.ZERO;
        late.lateTicketCount = 3;
        assertTrue(service.hasAnomaly(late));
        FinancialState clean = new FinancialState();
        clean.controlVariance = null;
        clean.lateTicketCount = 0;
        assertFalse(service.hasAnomaly(clean));
    }

    /**
     * {@code export} keeps only the asked section's lines and maps each to the
     * generic seven-column row, a null quantity becoming an empty cell.
     */
    @Test
    void exportBuildsOneReportPerSection() {
        FinancialStateService service = newService();
        FinancialState state = new FinancialState();
        state.id = 7L;
        state.stateType = StateType.Z;
        FinancialStateLine settlement = line(StateSection.SETTLEMENT, "CASH", "10.0000");
        FinancialStateLine vat = line(StateSection.VAT, "0.2000", "2.0000");
        FinancialStateLine bestSeller = line(StateSection.BEST_SELLER, "E1", "5.0000");
        bestSeller.quantity = new BigDecimal("3.0000");
        state.lines = List.of(settlement, vat, bestSeller);
        ReportData data = service.export(state, StateSection.VAT);
        assertEquals(1, data.rows.size());
        assertEquals("0.2000", data.rows.get(0).get(0));
        assertEquals(new BigDecimal("2.0000"), data.rows.get(0).get(5));
        assertEquals(7, data.definition.columns().size());
        assertTrue(data.definition.code().contains("vat"));
    }

    /**
     * {@code export} carries the quantity as its plain string when present and a
     * null cell when absent.
     */
    @Test
    void exportRendersQuantityWhenPresent() {
        FinancialStateService service = newService();
        FinancialState state = new FinancialState();
        state.id = 8L;
        state.stateType = StateType.Z;
        FinancialStateLine withQuantity = line(StateSection.BEST_SELLER, "E1", "5.0000");
        withQuantity.quantity = new BigDecimal("3.0000");
        FinancialStateLine withoutQuantity = line(StateSection.BEST_SELLER, "E2", "4.0000");
        state.lines = List.of(withQuantity, withoutQuantity);
        ReportData data = service.export(state, StateSection.BEST_SELLER);
        assertEquals(2, data.rows.size());
        assertEquals("3.0000", data.rows.get(0).get(2));
        assertEquals(null, data.rows.get(1).get(2));
    }

    /**
     * The section report definition exposes its identity and ignores the criteria,
     * the frozen rows being supplied to the report directly.
     */
    @Test
    void sectionReportDefinitionExposesItsIdentity() {
        FinancialStateService.SectionReportDefinition definition =
                new FinancialStateService.SectionReportDefinition("c", "l", FinancialStateService.COLUMNS);
        assertEquals("c", definition.code());
        assertEquals("l", definition.label());
        assertEquals("X/Z", definition.requirement());
        assertEquals(com.intermarche.edge.ui.report.ReportFamily.CASHIERS, definition.family());
        assertTrue(definition.rows(new com.intermarche.edge.ui.report.ReportCriteria()).isEmpty());
    }

    /**
     * {@code emitManual} delegates to the emission service with the create flag off
     * and the operator recorded, at the period's natural bound.
     */
    @Test
    void emitManualDelegatesToTheEmissionService() {
        FinancialStateService service = newService();
        Employee operator = new Employee();
        FinancialState emitted = new FinancialState();
        when(service.emissionService.emitZ(eq(StateScope.STORE), eq(PeriodKind.DAY), isNull(),
                any(), eq(false), eq(operator))).thenReturn(emitted);
        assertSame(emitted, service.emitManual(StateScope.STORE, PeriodKind.DAY, null, operator));
        verify(service.emissionService).emitZ(eq(StateScope.STORE), eq(PeriodKind.DAY), isNull(),
                any(), eq(false), eq(operator));
    }

    /**
     * {@code readX} delegates to the emission service and returns its X.
     */
    @Test
    void readXDelegatesToTheEmissionService() {
        FinancialStateService service = newService();
        FinancialState x = new FinancialState();
        when(service.emissionService.readX(StateScope.STORE, PeriodKind.DAY, null)).thenReturn(x);
        assertSame(x, service.readX(StateScope.STORE, PeriodKind.DAY, null));
    }

    /**
     * {@code listEmitted} queries the Z documents newest first, and {@code byId}
     * reads one by its id.
     */
    @Test
    void listEmittedAndByIdQueryPanache() {
        FinancialStateService service = newService();
        FinancialState z = new FinancialState();
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            statics.when(() -> PanacheEntityBase.list(any(String.class), any(Object[].class)))
                    .thenReturn(List.of(z));
            statics.when(() -> PanacheEntityBase.findById(7L)).thenReturn(z);
            assertEquals(1, service.listEmitted().size());
            assertSame(z, service.byId(7L));
        }
    }
}
