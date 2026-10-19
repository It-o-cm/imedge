package com.intermarche.pos.service;

import com.intermarche.pos.domain.payment.StoredValue;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StoredValueRetentionService}.
 *
 * <p>The sweep is a Panache consumer over {@link StoredValue}: the three
 * deletes resolve to {@link PanacheEntityBase} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}. No database is booted.
 *
 * <p>Branch enumeration: {@code limitOf} (zero / negative / positive), each of
 * the three deletes with and without an administered delay, and the summary log
 * guard (something deleted / nothing deleted).
 */
class StoredValueRetentionServiceTest {

    /**
     * Builds a sweep whose three delays answer the given values.
     *
     * @param unused the expired-and-unused delay
     * @param used the exhausted delay
     * @param undated the undated delay
     * @return a ready-to-test sweep
     */
    private StoredValueRetentionService newService(int unused, int used, int undated) {
        StoredValueRetentionService service = new StoredValueRetentionService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.barcodeExpiredUnusedDays()).thenReturn(unused);
        when(service.posSettingsService.barcodeExpiredUsedDays()).thenReturn(used);
        when(service.posSettingsService.barcodeUndatedDays()).thenReturn(undated);
        return service;
    }

    /**
     * BO-10-08-13/-14/-15: nothing administered — the catalogue default — is
     * nothing deleted, and not a single query issued.
     */
    @Test
    void nothingAdministeredDeletesNothing() {
        StoredValueRetentionService service = newService(0, 0, -1);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            assertEquals(0L, service.sweep());
            ms.verify(() -> StoredValue.delete(anyString(), any(Object.class)), never());
        }
    }

    /**
     * BO-10-08-13/-14/-15: the three delays administered issue three deletes,
     * and the sweep answers their sum.
     */
    @Test
    void theThreeDelaysIssueTheirThreeDeletes() {
        StoredValueRetentionService service = newService(365, 180, 730);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> StoredValue.delete(anyString(), any(Object.class))).thenReturn(2L);
            assertEquals(6L, service.sweep());
            ms.verify(() -> StoredValue.delete(anyString(), any(Object.class)), times(3));
        }
    }

    /**
     * BO-10-08-14: one delay administered alone issues ONE delete — the three
     * families are not the same family, and a shop may purge only the settled
     * instruments.
     */
    @Test
    void oneDelayAloneIssuesOneDelete() {
        StoredValueRetentionService service = newService(0, 180, 0);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> StoredValue.delete(anyString(), any(Object.class))).thenReturn(4L);
            assertEquals(4L, service.sweep());
            ms.verify(() -> StoredValue.delete(
                    eq("validUntil is not null and exhaustedAt is not null and exhaustedAt < ?1"),
                    any(Object.class)), times(1));
        }
    }
}
