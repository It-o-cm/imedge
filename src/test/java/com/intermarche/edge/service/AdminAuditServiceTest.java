package com.intermarche.edge.service;

import com.intermarche.edge.domain.audit.AdminAuditEntry;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.setting.Feature;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Unit tests for {@link AdminAuditService} (BO-01-01-01, BO-01-01-03),
 * targeting 100% branch coverage: an entry written in its own transaction
 * with a known account and the node's point of sale; an unknown account and
 * no echelon service; texts cut to their columns; a log that cannot be
 * written swallowed.
 */
class AdminAuditServiceTest {

    /**
     * Intercepts the transaction so its runnable runs in place.
     *
     * @param tx the static mock of the transaction API
     */
    private void runInPlace(MockedStatic<QuarkusTransaction> tx) {
        TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
        tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(runner).run(any(Runnable.class));
    }

    /**
     * Makes the login finder answer one account, or none.
     *
     * @param panache the static mock of the Panache base
     * @param account the account, possibly null
     */
    @SuppressWarnings("unchecked")
    private void account(MockedStatic<PanacheEntityBase> panache, Employee account) {
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(account);
        panache.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(query);
    }

    /**
     * A gesture is written with who, where, what and how it ended.
     */
    @Test
    void aGestureIsWritten() {
        AdminAuditService service = new AdminAuditService();
        service.nodeEchelonService = mock(NodeEchelonService.class);
        when(service.nodeEchelonService.currentPdv()).thenReturn("07039");
        Employee marie = new Employee();
        marie.firstName = "Marie";
        marie.lastName = "Curie";
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<AdminAuditEntry> rows = mockConstruction(AdminAuditEntry.class,
                     withSettings().defaultAnswer(CALLS_REAL_METHODS),
                     (row, context) -> doNothing().when(row).persist())) {
            runInPlace(tx);
            account(panache, marie);
            service.record("mcurie", Feature.ACCOUNT, CrudOption.UPDATE, "POST /admin/accounts/reset", "12",
                    AdminAuditEntry.Outcome.DONE, "Secrets réinitialisés", "admin");
            AdminAuditEntry entry = rows.constructed().get(0);
            assertNotNull(entry.occurredAt);
            assertEquals("mcurie", entry.loginName);
            assertEquals("Marie Curie", entry.fullName);
            assertEquals("account", entry.featureKey);
            assertEquals("UPDATE", entry.option);
            assertEquals("POST /admin/accounts/reset", entry.requestPath);
            assertEquals("12", entry.targetRef);
            assertEquals(AdminAuditEntry.Outcome.DONE, entry.outcome);
            assertEquals("Secrets réinitialisés", entry.detail);
            assertEquals("admin", entry.validatorLogin);
            assertEquals("07039", entry.pdv);
            verify(entry).persist();
        }
    }

    /**
     * An unknown account keeps no name, a service without echelon keeps no
     * point of sale, and long texts are cut to their columns.
     */
    @Test
    void longTextsAreCutAndUnknownsLeftEmpty() {
        AdminAuditService service = new AdminAuditService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<AdminAuditEntry> rows = mockConstruction(AdminAuditEntry.class,
                     withSettings().defaultAnswer(CALLS_REAL_METHODS),
                     (row, context) -> doNothing().when(row).persist())) {
            account(panache, null);
            service.persist("ghost", Feature.SETTINGS, CrudOption.VIEW, "/".repeat(300), "x".repeat(90),
                    AdminAuditEntry.Outcome.DENIED, "d".repeat(600), null);
            AdminAuditEntry entry = rows.constructed().get(0);
            assertNull(entry.fullName);
            assertNull(entry.pdv);
            assertEquals(255, entry.requestPath.length());
            assertEquals(80, entry.targetRef.length());
            assertEquals(500, entry.detail.length());
        }
        assertNull(AdminAuditService.cut(null, 5));
        assertEquals("abc", AdminAuditService.cut("abc", 5));
    }

    /**
     * A log that cannot be written never undoes the gesture: the failure is swallowed.
     */
    @Test
    void anUnwritableLogIsSwallowed() {
        AdminAuditService service = new AdminAuditService();
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
            tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
            doThrow(new IllegalStateException("db down")).when(runner).run(any(Runnable.class));
            assertDoesNotThrow(() -> service.record("admin", Feature.SETTINGS, CrudOption.UPDATE, "POST /admin/settings",
                    null, AdminAuditEntry.Outcome.DONE, null, null));
        }
    }
}
