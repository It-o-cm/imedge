package com.intermarche.edge.service.deploy;

import com.intermarche.edge.domain.deploy.FunctionUsage;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link FunctionUsageService}. */
class FunctionUsageServiceTest {

    /**
     * Builds the service over mocked settings.
     *
     * @param enabled whether the counting is active
     * @return the service
     */
    private FunctionUsageService service(boolean enabled) {
        FunctionUsageService service = new FunctionUsageService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.functionUsageEnabled()).thenReturn(enabled);
        return service;
    }

    /**
     * Makes the key finder answer a counter.
     *
     * @param panache the static mock
     * @param row the counter, possibly null
     */
    @SuppressWarnings("unchecked")
    private void found(MockedStatic<PanacheEntityBase> panache, FunctionUsage row) {
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        panache.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(query);
    }

    /** BO-08-02-02: a disabled counting writes nothing. */
    @Test
    void aDisabledCountingWritesNothing() {
        try (MockedConstruction<FunctionUsage> rows = mockConstruction(FunctionUsage.class)) {
            service(false).record("C04", "07039", FunctionUsage.REGISTER, "/action/pay");
            assertEquals(0, rows.constructed().size());
        }
    }

    /** BO-08-02-02: the first use of the day creates the counter; a long function code is cut. */
    @Test
    void theFirstUseCreatesTheCounter() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<FunctionUsage> rows = mockConstruction(FunctionUsage.class)) {
            found(panache, null);
            service(true).record("C04", "07039", FunctionUsage.REGISTER, "f".repeat(130));
            FunctionUsage row = rows.constructed().get(0);
            assertEquals(1, row.useCount);
            assertEquals(120, row.functionCode.length());
            assertEquals("07039", row.pdvNumber);
            assertEquals(LocalDate.now(), row.usageDay);
            verify(row).persist();
        }
    }

    /** BO-08-02-02: a later use increments the counter in place. */
    @Test
    void aLaterUseIncrements() {
        FunctionUsage row = mock(FunctionUsage.class);
        row.useCount = 4;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            found(panache, row);
            service(true).record("C04", null, FunctionUsage.REGISTER, "/action/pay");
        }
        assertEquals(5, row.useCount);
        verify(row).persist();
    }

    /** BO-08-02-02: the counters of the last days travel as they are. */
    @Test
    void recentMapsTheCounters() {
        FunctionUsage row = new FunctionUsage();
        row.usageDay = LocalDate.of(2026, 10, 24);
        row.nodeId = "C04";
        row.pdvNumber = "07039";
        row.channel = FunctionUsage.REGISTER;
        row.functionCode = "/action/pay";
        row.useCount = 9;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class))).thenReturn(List.of(row));
            List<DeployPayloads.Usage> usages = service(true).recent(1);
            assertEquals(1, usages.size());
            assertEquals("2026-10-24", usages.get(0).day);
            assertEquals("C04", usages.get(0).nodeId);
            assertEquals("07039", usages.get(0).pdvNumber);
            assertEquals("/action/pay", usages.get(0).function);
            assertEquals(FunctionUsage.REGISTER, usages.get(0).channel);
            assertEquals(9, usages.get(0).count);
        }
    }

    /** BO-08-02-02: a reported counter replaces the one held, or creates it. */
    @Test
    void mergeReplacesOrCreates() {
        DeployPayloads.Usage usage = new DeployPayloads.Usage();
        usage.day = "2026-10-24";
        usage.nodeId = "C04";
        usage.pdvNumber = "07039";
        usage.channel = FunctionUsage.REGISTER;
        usage.function = "/action/pay";
        usage.count = 12;
        FunctionUsage held = mock(FunctionUsage.class);
        held.useCount = 3;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            found(panache, held);
            service(true).merge(List.of(usage));
        }
        assertEquals(12, held.useCount);
        assertEquals("07039", held.pdvNumber);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<FunctionUsage> rows = mockConstruction(FunctionUsage.class)) {
            found(panache, null);
            service(true).merge(List.of(usage));
            FunctionUsage created = rows.constructed().get(0);
            assertEquals(12, created.useCount);
            assertEquals("/action/pay", created.functionCode);
            assertEquals(LocalDate.of(2026, 10, 24), created.usageDay);
            verify(created).persist();
        }
    }
}
