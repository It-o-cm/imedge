package com.intermarche.pos.domain.session;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashSession}, targeting 100% branch coverage.
 * <p>
 * The only branching method is {@code getOpeningFloatFormatted} (its null
 * guard). {@code findOpenByTerminal} resolves the Panache static finder, which
 * under plain {@code mvn test} falls back to {@link PanacheEntityBase}, so it is
 * intercepted with {@link org.mockito.Mockito#mockStatic}. {@code getChecksum},
 * the default field initializer and the {@link CashSession.SessionStatus} enum
 * are exercised for line coverage. Every test is fully isolated and asserts
 * absolute expected values.
 */
class CashSessionTest {

    /**
     * Builds a mocked {@link PanacheQuery} whose {@code firstResult()} yields
     * the given session, mirroring the finder's terminal call.
     *
     * @param session the session to return, or null for none
     * @return the mocked query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<CashSession> query(CashSession session) {
        PanacheQuery<CashSession> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(session);
        return query;
    }

    /**
     * A fresh session defaults its status to OPEN via the field initializer.
     */
    @Test
    void statusDefaultsToOpen() {
        CashSession session = new CashSession();
        Assertions.assertEquals(CashSession.SessionStatus.OPEN, session.status);
    }

    /**
     * findOpenByTerminal returns the query's first result when a session is open.
     */
    @Test
    void findOpenByTerminalReturnsOpenSession() {
        CashSession open = new CashSession();
        PanacheQuery<CashSession> query = query(open);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashSession.find("terminalId = ?1 and status = ?2",
                    "C04", CashSession.SessionStatus.OPEN)).thenReturn(query);
            Assertions.assertSame(open, CashSession.findOpenByTerminal("C04"));
        }
    }

    /**
     * findOpenByTerminal returns null when the query has no first result.
     */
    @Test
    void findOpenByTerminalReturnsNullWhenNoneOpen() {
        PanacheQuery<CashSession> query = query(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashSession.find("terminalId = ?1 and status = ?2",
                    "C09", CashSession.SessionStatus.OPEN)).thenReturn(query);
            Assertions.assertNull(CashSession.findOpenByTerminal("C09"));
        }
    }

    /**
     * getOpeningFloatFormatted returns the sentinel when the float is null
     * (null-guard true arm).
     */
    @Test
    void getOpeningFloatFormattedNullReturnsZero() {
        CashSession session = new CashSession();
        session.openingFloat = null;
        Assertions.assertEquals("0,00", session.getOpeningFloatFormatted());
    }

    /**
     * getOpeningFloatFormatted formats a non-null float with two decimals and a
     * French comma (null-guard false arm).
     */
    @Test
    void getOpeningFloatFormattedNonNullFormatsWithComma() {
        CashSession session = new CashSession();
        session.openingFloat = new BigDecimal("150.5");
        Assertions.assertEquals("150,50", session.getOpeningFloatFormatted());
    }

    /**
     * getOpeningFloatFormatted rounds HALF_UP to two decimals before formatting.
     */
    @Test
    void getOpeningFloatFormattedRoundsHalfUp() {
        CashSession session = new CashSession();
        session.openingFloat = new BigDecimal("1.005");
        Assertions.assertEquals("1,01", session.getOpeningFloatFormatted());
    }

    /**
     * getChecksum returns the Objects.hash of the six identifying and financial
     * fields.
     */
    @Test
    void getChecksumMatchesObjectsHash() {
        CashSession session = new CashSession();
        session.sessionNumber = "C04-S00012";
        session.terminalId = "C04";
        session.status = CashSession.SessionStatus.CLOSED;
        session.openingDate = LocalDateTime.of(2026, 8, 3, 8, 0);
        session.openingFloat = new BigDecimal("200.00");
        session.countedAmount = new BigDecimal("350.00");
        session.variance = new BigDecimal("0.50");
        int expected = Objects.hash("C04-S00012", "C04", CashSession.SessionStatus.CLOSED,
                new BigDecimal("200.00"), new BigDecimal("350.00"), new BigDecimal("0.50"));
        Assertions.assertEquals(expected, session.getChecksum());
    }

    /**
     * getChecksum tolerates the null financial fields of a still-open session.
     */
    @Test
    void getChecksumWithNullFieldsMatchesObjectsHash() {
        CashSession session = new CashSession();
        session.sessionNumber = "C04-S00013";
        session.terminalId = "C04";
        session.openingFloat = new BigDecimal("100.00");
        int expected = Objects.hash("C04-S00013", "C04", CashSession.SessionStatus.OPEN,
                new BigDecimal("100.00"), null, null);
        Assertions.assertEquals(expected, session.getChecksum());
    }

    /**
     * The SessionStatus enum exposes exactly its two declared constants and
     * round-trips through valueOf.
     */
    @Test
    void sessionStatusEnumHasTwoConstants() {
        Assertions.assertEquals(2, CashSession.SessionStatus.values().length);
        Assertions.assertEquals(CashSession.SessionStatus.OPEN,
                CashSession.SessionStatus.valueOf("OPEN"));
        Assertions.assertEquals(CashSession.SessionStatus.CLOSED,
                CashSession.SessionStatus.valueOf("CLOSED"));
    }
}
