package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.domain.session.TechnicalEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests of {@link SupervisionJournal} (BO-04-03-14).
 */
class SupervisionJournalTest {

    /**
     * A decision is written under the register, with who decided and on what;
     * a long text is cut, an unnamed register is « ? ».
     */
    @Test
    void writesTheDecision() {
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedConstruction<TechnicalEvent> events = mockConstruction(TechnicalEvent.class)) {
            TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
            tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
            doAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            }).when(runner).run(any(Runnable.class));
            SupervisionJournal journal = new SupervisionJournal();
            journal.handled("C04", "REFUS", "Marie", "Monnaie");
            journal.handled(null, "ACQUITTEMENT", "Marie", null);
            journal.handled("C04", "AUTORISATION", "Marie", "  ");
            journal.handled("C04", "AUTORISATION", "Marie", "x".repeat(300));
            assertEquals(4, events.constructed().size());
            TechnicalEvent first = events.constructed().get(0);
            assertEquals("Refus par Marie — Monnaie", first.detail);
            assertEquals("C04", first.terminalId);
            assertEquals(TechnicalEvent.EventType.SUPERVISION_HANDLED, first.eventType);
            assertEquals("REFUS", first.code);
            assertEquals("?", events.constructed().get(1).terminalId);
            assertEquals("Acquittement par Marie", events.constructed().get(1).detail);
            assertEquals("Autorisation par Marie", events.constructed().get(2).detail);
            assertEquals(255, events.constructed().get(3).detail.length());
        }
    }

    /**
     * A journal that cannot be written never fails the decision.
     */
    @Test
    void aFailingJournalIsSwallowed() {
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
            tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
            doThrow(new IllegalStateException("down")).when(runner).run(any(Runnable.class));
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> new SupervisionJournal().handled("C04", "REFUS", "Marie", null));
        }
    }

    /**
     * Each decision is written in words; one without words keeps its code.
     */
    @Test
    void decisionsAreWorded() {
        assertEquals("Acquittement", SupervisionJournal.wording("ACQUITTEMENT"));
        assertEquals("Renvoi en caisse", SupervisionJournal.wording("A_TRAITER_EN_CAISSE"));
        assertEquals("Refus", SupervisionJournal.wording("REFUS"));
        assertEquals("Autorisation", SupervisionJournal.wording("AUTORISATION"));
        assertEquals("Autorisation refusée (profil)", SupervisionJournal.wording("AUTORISATION_REFUSEE"));
        assertEquals("Fermeture forcée", SupervisionJournal.wording("FERMETURE_FORCEE"));
        assertEquals("AUTRE", SupervisionJournal.wording("AUTRE"));
        assertEquals("null", SupervisionJournal.wording(null));
    }
}
