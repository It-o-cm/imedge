package com.intermarche.edge.graphql;

import com.intermarche.edge.service.sync.store.RefExportService;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link ReferentialChangeNotifier}: a GraphQL mutation moves
 * the change stamp like a back-office save, after its commit only.
 */
class ReferentialChangeNotifierTest {

    /**
     * Builds a notifier over mocks.
     *
     * @param export the exporter whose stamp moves
     * @param transactions the transaction registry
     * @return the notifier
     */
    private ReferentialChangeNotifier notifier(RefExportService export, TransactionSynchronizationRegistry transactions) {
        ReferentialChangeNotifier notifier = new ReferentialChangeNotifier();
        notifier.refExportService = export;
        notifier.transactions = transactions;
        return notifier;
    }

    /** Outside any transaction the stamp moves at once. */
    @Test
    void withoutTransactionTheStampMovesAtOnce() {
        RefExportService export = mock(RefExportService.class);
        TransactionSynchronizationRegistry transactions = mock(TransactionSynchronizationRegistry.class);
        notifier(export, transactions).changed();
        verify(export).referentialChanged();
        verify(transactions, never()).registerInterposedSynchronization(org.mockito.ArgumentMatchers.any());
    }

    /**
     * Inside a transaction nothing moves until its completion: the stamp moves
     * on a commit and stays on a rollback.
     */
    @Test
    void insideATransactionTheStampMovesOnCommitOnly() {
        RefExportService export = mock(RefExportService.class);
        TransactionSynchronizationRegistry transactions = mock(TransactionSynchronizationRegistry.class);
        when(transactions.getTransactionKey()).thenReturn("tx-1");
        notifier(export, transactions).changed();
        ArgumentCaptor<Synchronization> captor = ArgumentCaptor.forClass(Synchronization.class);
        verify(transactions).registerInterposedSynchronization(captor.capture());
        verify(export, never()).referentialChanged();
        Synchronization synchronization = captor.getValue();
        synchronization.beforeCompletion();
        verify(export, never()).referentialChanged();
        synchronization.afterCompletion(Status.STATUS_ROLLEDBACK);
        verify(export, never()).referentialChanged();
        synchronization.afterCompletion(Status.STATUS_COMMITTED);
        verify(export, times(1)).referentialChanged();
    }
}
