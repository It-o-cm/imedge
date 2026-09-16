package com.intermarche.pos.service;

import com.intermarche.pos.domain.session.DocumentCounter;
import com.intermarche.pos.domain.sale.DocumentType;
import com.intermarche.pos.domain.session.TicketCounter;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketNumberService}.
 * <p>
 * All Panache active-record static access ({@code TicketCounter.find}) is
 * intercepted with {@link org.mockito.Mockito#mockStatic}, and the lazy
 * creation path is intercepted with {@link org.mockito.Mockito#mockConstruction}
 * so no database or Quarkus context is needed. The two branches of the class
 * live in {@link TicketNumberService#lockCounter(String)} and
 * {@link TicketNumberService#lockDocumentCounter(String, DocumentType)}
 * (counter found vs. counter created); both arms of each are covered.
 */
class TicketNumberServiceTest {

    /** The terminal identifier used across the tests. */
    private static final String TERMINAL = "C04";

    /**
     * Builds a service instance with the terminal id field populated, bypassing
     * the {@code @ConfigProperty} injection that a plain unit test does not run.
     *
     * @return a ready-to-use service bound to {@link #TERMINAL}
     */
    private TicketNumberService newService() {
        TicketNumberService service = new TicketNumberService();
        service.terminalId = TERMINAL;
        return service;
    }

    /**
     * Creates a mocked Panache query whose {@code withLock/firstResult} chain
     * resolves to the given counter (or {@code null} to simulate absence).
     *
     * @param result the counter the query must return, possibly {@code null}
     * @return the configured mocked query
     */
    private PanacheQuery<TicketCounter> queryReturning(TicketCounter result) {
        @SuppressWarnings("unchecked")
        PanacheQuery<TicketCounter> query = mock(PanacheQuery.class);
        when(query.withLock(LockModeType.PESSIMISTIC_WRITE)).thenReturn(query);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Creates a mocked Panache query whose {@code withLock/firstResult} chain
     * resolves to the given document counter (or {@code null} to simulate absence).
     *
     * @param result the document counter the query must return, possibly {@code null}
     * @return the configured mocked query
     */
    private PanacheQuery<DocumentCounter> docQueryReturning(DocumentCounter result) {
        @SuppressWarnings("unchecked")
        PanacheQuery<DocumentCounter> query = mock(PanacheQuery.class);
        when(query.withLock(LockModeType.PESSIMISTIC_WRITE)).thenReturn(query);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Verifies that {@code nextTicketNumber} increments the ticket sequence of
     * the existing counter and formats it as {@code <terminal>-<8 digits>}.
     */
    @Test
    void nextTicketNumberIncrementsAndFormats() {
        TicketCounter counter = new TicketCounter();
        counter.lastNumber = 122L;
        PanacheQuery<TicketCounter> query = queryReturning(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TicketCounter.find("terminalId", TERMINAL)).thenReturn(query);
            String number = newService().nextTicketNumber();
            assertEquals("C04-00000123", number);
            assertEquals(123L, counter.lastNumber);
        }
    }

    /**
     * Verifies that {@code nextSessionNumber} increments the session sequence of
     * the existing counter and formats it as {@code <terminal>-S<5 digits>}.
     */
    @Test
    void nextSessionNumberIncrementsAndFormats() {
        TicketCounter counter = new TicketCounter();
        counter.lastSessionNumber = 11L;
        PanacheQuery<TicketCounter> query = queryReturning(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TicketCounter.find("terminalId", TERMINAL)).thenReturn(query);
            String number = newService().nextSessionNumber();
            assertEquals("C04-S00012", number);
            assertEquals(12L, counter.lastSessionNumber);
        }
    }

    /**
     * Verifies that {@code nextRefundNumber} increments the refund sequence of
     * the existing counter and formats it as {@code <terminal>-R<6 digits>}.
     */
    @Test
    void nextRefundNumberIncrementsAndFormats() {
        TicketCounter counter = new TicketCounter();
        counter.lastRefundNumber = 11L;
        PanacheQuery<TicketCounter> query = queryReturning(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TicketCounter.find("terminalId", TERMINAL)).thenReturn(query);
            String number = newService().nextRefundNumber();
            assertEquals("C04-R000012", number);
            assertEquals(12L, counter.lastRefundNumber);
        }
    }

    /**
     * Covers the non-null arm of {@code lockCounter}: an existing counter is
     * locked and returned as-is, without any lazy creation/persist.
     */
    @Test
    void lockCounterReturnsExistingCounterWithoutPersisting() {
        TicketCounter counter = new TicketCounter();
        PanacheQuery<TicketCounter> query = queryReturning(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<TicketCounter> created = mockConstruction(TicketCounter.class)) {
            mocked.when(() -> TicketCounter.find("terminalId", TERMINAL)).thenReturn(query);
            TicketCounter result = newService().lockCounter(TERMINAL);
            assertSame(counter, result);
            verify(query).withLock(LockModeType.PESSIMISTIC_WRITE);
            assertEquals(0, created.constructed().size());
        }
    }

    /**
     * Covers the null arm of {@code lockCounter}: when no counter exists a new
     * one is created, seeded (terminal id and zero sequence) and persisted.
     */
    @Test
    void lockCounterCreatesAndPersistsCounterWhenAbsent() {
        PanacheQuery<TicketCounter> query = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<TicketCounter> created = mockConstruction(TicketCounter.class)) {
            mocked.when(() -> TicketCounter.find("terminalId", TERMINAL)).thenReturn(query);
            TicketCounter result = newService().lockCounter(TERMINAL);
            assertEquals(1, created.constructed().size());
            TicketCounter newCounter = created.constructed().get(0);
            assertSame(newCounter, result);
            assertEquals(TERMINAL, newCounter.terminalId);
            assertEquals(0L, newCounter.lastNumber);
            verify(newCounter, times(1)).persist();
        }
    }

    /**
     * Verifies that {@code nextCustomerNumber} increments the customer sequence of
     * the existing counter and formats it as {@code <terminal>-CLI<6 digits>}.
     */
    @Test
    void nextCustomerNumberIncrementsAndFormats() {
        TicketCounter counter = new TicketCounter();
        counter.lastCustomerNumber = 41L;
        PanacheQuery<TicketCounter> query = queryReturning(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TicketCounter.find("terminalId", TERMINAL)).thenReturn(query);
            String number = newService().nextCustomerNumber();
            assertEquals("C04-CLI000042", number);
            assertEquals(42L, counter.lastCustomerNumber);
        }
    }

    /**
     * Verifies that {@code nextDocumentNumber} increments the per-type document
     * sequence of the existing counter and formats it as
     * {@code <terminal>-<typePrefix><6 digits>}; also covers the non-null arm of
     * {@code lockDocumentCounter} (existing counter locked and returned, no create).
     */
    @Test
    void nextDocumentNumberIncrementsAndFormats() {
        DocumentCounter counter = new DocumentCounter();
        counter.lastNumber = 122L;
        PanacheQuery<DocumentCounter> query = docQueryReturning(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<DocumentCounter> created = mockConstruction(DocumentCounter.class)) {
            mocked.when(() -> DocumentCounter.find("terminalId = ?1 and documentType = ?2", TERMINAL, DocumentType.FACTURE)).thenReturn(query);
            String number = newService().nextDocumentNumber(DocumentType.FACTURE);
            assertEquals("C04-F000123", number);
            assertEquals(123L, counter.lastNumber);
            assertEquals(0, created.constructed().size());
        }
    }

    /**
     * Covers the non-null arm of {@code lockDocumentCounter}: an existing counter
     * is locked and returned as-is, without any lazy creation/persist.
     */
    @Test
    void lockDocumentCounterReturnsExistingCounterWithoutPersisting() {
        DocumentCounter counter = new DocumentCounter();
        PanacheQuery<DocumentCounter> query = docQueryReturning(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<DocumentCounter> created = mockConstruction(DocumentCounter.class)) {
            mocked.when(() -> DocumentCounter.find("terminalId = ?1 and documentType = ?2", TERMINAL, DocumentType.FACTURE)).thenReturn(query);
            DocumentCounter result = newService().lockDocumentCounter(TERMINAL, DocumentType.FACTURE);
            assertSame(counter, result);
            verify(query).withLock(LockModeType.PESSIMISTIC_WRITE);
            assertEquals(0, created.constructed().size());
        }
    }

    /**
     * Covers the null arm of {@code lockDocumentCounter}: when no counter exists a
     * new one is created, seeded (terminal id, document type and zero sequence) and
     * persisted.
     */
    @Test
    void lockDocumentCounterCreatesAndPersistsCounterWhenAbsent() {
        PanacheQuery<DocumentCounter> query = docQueryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<DocumentCounter> created = mockConstruction(DocumentCounter.class)) {
            mocked.when(() -> DocumentCounter.find("terminalId = ?1 and documentType = ?2", TERMINAL, DocumentType.BON_LIVRAISON)).thenReturn(query);
            DocumentCounter result = newService().lockDocumentCounter(TERMINAL, DocumentType.BON_LIVRAISON);
            assertEquals(1, created.constructed().size());
            DocumentCounter newCounter = created.constructed().get(0);
            assertSame(newCounter, result);
            assertEquals(TERMINAL, newCounter.terminalId);
            assertEquals(DocumentType.BON_LIVRAISON, newCounter.documentType);
            assertEquals(0L, newCounter.lastNumber);
            verify(newCounter, times(1)).persist();
        }
    }

    /**
     * Verifies that {@code getTerminalId} returns the configured terminal id.
     */
    @Test
    void getTerminalIdReturnsConfiguredValue() {
        TicketNumberService service = newService();
        assertNotNull(service.getTerminalId());
        assertEquals(TERMINAL, service.getTerminalId());
    }
}
