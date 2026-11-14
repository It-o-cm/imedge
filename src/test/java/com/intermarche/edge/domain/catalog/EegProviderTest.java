package com.intermarche.edge.domain.catalog;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link EegProvider} (BO-02-03-37/38): the finders and the
 * checksum.
 */
class EegProviderTest {

    /**
     * The finders list every provider, the active ones, and find one by code,
     * a null code answering null.
     */
    @Test
    void findersReadTheProviders() {
        EegProvider provider = new EegProvider();
        @SuppressWarnings("unchecked")
        PanacheQuery<EegProvider> query = mock(PanacheQuery.class,
                invocation -> "firstResult".equals(invocation.getMethod().getName()) ? provider : null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EegProvider.list("order by code")).thenReturn(List.of(provider));
            panache.when(() -> EegProvider.list("active = true order by code")).thenReturn(List.of());
            panache.when(() -> EegProvider.find("code", "SES")).thenReturn(query);
            assertEquals(List.of(provider), EegProvider.listAllOrdered());
            assertEquals(List.of(), EegProvider.listActive());
            assertSame(provider, EegProvider.findByCode("SES"));
            assertNull(EegProvider.findByCode(null));
        }
    }

    /**
     * The checksum hashes every business field, the connector defaulting to the
     * simulated one.
     */
    @Test
    void checksumHashesTheBusinessFields() {
        EegProvider provider = new EegProvider();
        provider.code = "SES";
        provider.label = "SES-imagotag";
        assertEquals(EegProvider.KIND_SIMULATED, provider.kind);
        assertEquals(Objects.hash("SES", "SES-imagotag", "SIMULATED", null, true), provider.getChecksum());
    }
}
