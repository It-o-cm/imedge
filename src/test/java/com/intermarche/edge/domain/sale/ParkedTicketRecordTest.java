package com.intermarche.edge.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ParkedTicketRecord} (LC-04-01-03), targeting 100%
 * branch coverage.
 *
 * <p>Plain JUnit 5; the finders resolve to {@link PanacheEntityBase} and are
 * mocked statically. Branch enumeration: the signature over a full record and
 * over one with no total; a chain that holds, one whose link is wrong and one
 * whose record was altered; the finders.
 */
class ParkedTicketRecordTest {

    /**
     * A record of the chain, signed.
     *
     * @param previous the previous signature
     * @param event what happened
     * @return the record
     */
    private ParkedTicketRecord record(String previous, ParkedTicketRecord.Event event) {
        ParkedTicketRecord record = new ParkedTicketRecord();
        record.terminalId = "POS01";
        record.sourceId = 7L;
        record.ticketNumber = "POS01-00000004";
        record.event = event;
        record.eventDate = LocalDateTime.of(2026, 10, 24, 10, 15, 30);
        record.operator = "12341234";
        record.itemCount = 2;
        record.totalAmount = new BigDecimal("5.3");
        record.content = "1 x LAIT @ 1.15 = 1.15\n";
        record.previousSignature = previous;
        record.signature = record.computeSignature();
        return record;
    }

    /**
     * The signature is a stable 64-character hex over the fields, the total
     * written at two decimals, and changes with any field; a record without a
     * total signs too.
     */
    @Test
    void theSignatureCoversTheFields() {
        ParkedTicketRecord record = record(ParkedTicketRecord.GENESIS, ParkedTicketRecord.Event.PARKED);
        assertEquals(64, record.signature.length());
        assertEquals(record.signature, record.computeSignature());
        record.totalAmount = new BigDecimal("5.30");
        assertEquals(record.signature, record.computeSignature());
        record.content = "2 x LAIT @ 1.15 = 2.30\n";
        assertNotEquals(record.signature, record.computeSignature());
        record.totalAmount = null;
        assertEquals(64, record.computeSignature().length());
        assertEquals(3, ParkedTicketRecord.Event.values().length);
        assertSame(ParkedTicketRecord.Event.PURGED, ParkedTicketRecord.Event.valueOf("PURGED"));
    }

    /**
     * A runtime without SHA-256 cannot sign, and says so.
     */
    @Test
    void aRuntimeWithoutSha256CannotSign() {
        ParkedTicketRecord record = record(ParkedTicketRecord.GENESIS, ParkedTicketRecord.Event.PARKED);
        try (MockedStatic<java.security.MessageDigest> jca = mockStatic(java.security.MessageDigest.class)) {
            jca.when(() -> java.security.MessageDigest.getInstance("SHA-256"))
                    .thenThrow(new java.security.NoSuchAlgorithmException("none"));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, record::computeSignature);
        }
    }

    /**
     * A chain holds when each record carries its own signature and the one
     * before it; a wrong link or an altered record is the first broken one.
     */
    @Test
    void theChainIsChecked() {
        ParkedTicketRecord first = record(ParkedTicketRecord.GENESIS, ParkedTicketRecord.Event.PARKED);
        ParkedTicketRecord second = record(first.signature, ParkedTicketRecord.Event.RESUMED);
        assertNull(ParkedTicketRecord.firstBroken(List.of(first, second)));
        assertNull(ParkedTicketRecord.firstBroken(List.of()));
        assertSame(second, ParkedTicketRecord.firstBroken(List.of(second)));
        second.itemCount = 3;
        assertSame(second, ParkedTicketRecord.firstBroken(List.of(first, second)));
    }

    /**
     * The finders query by register, by source and by period.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theFinders() {
        ParkedTicketRecord found = record(ParkedTicketRecord.GENESIS, ParkedTicketRecord.Event.PARKED);
        LocalDateTime from = LocalDateTime.of(2026, 10, 24, 0, 0);
        LocalDateTime to = from.plusDays(1);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ParkedTicketRecord> last = mock(PanacheQuery.class);
            when(last.firstResult()).thenReturn(found);
            panache.when(() -> PanacheEntityBase.find("terminalId = ?1 and sourceId is null order by id desc", "POS01"))
                    .thenReturn(last);
            assertSame(found, ParkedTicketRecord.findLastByTerminal("POS01"));
            PanacheQuery<ParkedTicketRecord> source = mock(PanacheQuery.class);
            when(source.firstResult()).thenReturn(found);
            panache.when(() -> PanacheEntityBase.find("terminalId = ?1 and sourceId = ?2", "POS01", 7L))
                    .thenReturn(source);
            assertSame(found, ParkedTicketRecord.findBySource("POS01", 7L));
            panache.when(() -> PanacheEntityBase.list("eventDate >= ?1 and eventDate < ?2 order by terminalId, eventDate, id",
                    from, to)).thenReturn(List.of(found));
            assertEquals(List.of(found), ParkedTicketRecord.listBetween(from, to));
            panache.when(() -> PanacheEntityBase.list("terminalId = ?1 order by sourceId, id", "POS01"))
                    .thenReturn(List.of(found));
            assertEquals(List.of(found), ParkedTicketRecord.listChain("POS01"));
            panache.when(() -> PanacheEntityBase.list(org.mockito.ArgumentMatchers.startsWith(
                    "from ParkedTicketRecord r where r.event = ?1 and r.sourceId is not null and not exists"),
                    org.mockito.ArgumentMatchers.eq(ParkedTicketRecord.Event.PARKED))).thenReturn(List.of(found));
            assertEquals(List.of(found), ParkedTicketRecord.listPending());
        }
    }
}
