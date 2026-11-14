package com.intermarche.edge.domain.audit;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Parameters;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link AdminAuditEntry#search} (BO-01-01-03), targeting 100%
 * branch coverage: every filter absent, then every filter present.
 */
class AdminAuditEntryTest {

    /**
     * Without filter the whole log is read newest first; each filter adds its
     * clause and its parameter.
     */
    @Test
    void theFiltersBuildTheQuery() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(anyString(), any(Parameters.class))).thenReturn(List.of());
            assertEquals(List.of(), AdminAuditEntry.search(null, null, null, null));
            LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
            LocalDateTime to = LocalDateTime.of(2026, 10, 25, 0, 0);
            AdminAuditEntry.search(from, to, "admin", "account");
            ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Parameters> parameters = ArgumentCaptor.forClass(Parameters.class);
            panache.verify(() -> PanacheEntityBase.list(query.capture(), parameters.capture()),
                    org.mockito.Mockito.times(2));
            assertEquals("1 = 1 order by occurredAt desc, id desc", query.getAllValues().get(0));
            assertEquals("1 = 1 and occurredAt >= :from and occurredAt < :to and loginName = :login"
                    + " and featureKey = :feature order by occurredAt desc, id desc", query.getAllValues().get(1));
            assertEquals(4, parameters.getAllValues().get(1).map().size());
        }
    }
}
