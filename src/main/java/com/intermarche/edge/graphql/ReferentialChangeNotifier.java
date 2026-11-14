package com.intermarche.edge.graphql;

import com.intermarche.edge.service.sync.store.RefExportService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Moves the referential change stamp after a GraphQL mutation, exactly as a
 * back-office form save does (BO-03-12-01).
 *
 * <p>The back office moves the stamp in a JAX-RS response filter, after the
 * resource method has committed; the GraphQL endpoint is not served by
 * JAX-RS, so its mutations reached the registers only at the next
 * fingerprint refresh. The stamp must move AFTER the commit: a register that
 * reacts to it within a second recomputes the fingerprints, and fingerprints
 * computed over the not-yet-committed state would be cached as current and
 * hide the change. The move is therefore registered on the mutation's
 * transaction and runs once it has committed; a rolled-back mutation moves
 * nothing, and a call outside any transaction moves the stamp at once.
 */
@ApplicationScoped
public class ReferentialChangeNotifier {

    /** The exporter whose stamp moves. */
    @Inject
    RefExportService refExportService;

    /** The transaction the mutation runs in. */
    @Inject
    TransactionSynchronizationRegistry transactions;

    /**
     * Records that a mutation changed the referential: the stamp moves when
     * the current transaction commits, or at once when there is none.
     */
    public void changed() {
        if (transactions.getTransactionKey() == null) {
            refExportService.referentialChanged();
            return;
        }
        transactions.registerInterposedSynchronization(new Synchronization() {
            /**
             * Nothing to do before the commit.
             */
            @Override
            public void beforeCompletion() {
            }

            /**
             * Moves the stamp once the transaction has committed.
             *
             * @param status the outcome of the transaction
             */
            @Override
            public void afterCompletion(int status) {
                if (status == Status.STATUS_COMMITTED) {
                    refExportService.referentialChanged();
                }
            }
        });
    }
}
