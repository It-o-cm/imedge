package com.intermarche.edge.service.sync;

import com.intermarche.edge.domain.sync.RegisterPresence;
import com.intermarche.edge.service.PosSettingsService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RegisterPresenceService} ({@code BO-04-03-05},
 * {@code BO-04-03-06}).
 *
 * <p>The two finders are custom statics of {@link RegisterPresence} and are
 * intercepted with {@code mockStatic}. Branch enumeration (both arms): a
 * null and a blank terminal, which touch nothing; a register already known,
 * whose stamp is moved; a register silent within and beyond the tolerance; a
 * row whose last sight is null, which can only be offline; and the
 * tolerance absent, zero and negative, which all answer the default.
 *
 * <p>The CREATING arm of {@code touch} persists a new row, so it is left to
 * the end-to-end run: a persist outside a transaction would be the only
 * thing such a case proved.
 */
class RegisterPresenceServiceTest {

    /**
     * Builds a service whose administered tolerance is the given value.
     *
     * @param minutes the administered tolerance
     * @return the service under test
     */
    private RegisterPresenceService service(int minutes) {
        RegisterPresenceService service = new RegisterPresenceService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.registerOfflineMinutes()).thenReturn(minutes);
        return service;
    }

    /**
     * Builds a presence row.
     *
     * @param terminalId the register
     * @param lastSeenAt when it last spoke, possibly null
     * @return the row
     */
    private RegisterPresence presence(String terminalId, LocalDateTime lastSeenAt) {
        RegisterPresence row = new RegisterPresence();
        row.terminalId = terminalId;
        row.lastSeenAt = lastSeenAt;
        return row;
    }

    /**
     * BO-04-03-05: a register nobody named touches nothing at all.
     */
    @Test
    void anUnnamedRegisterTouchesNothing() {
        RegisterPresenceService service = service(3);
        try (MockedStatic<RegisterPresence> rows = mockStatic(RegisterPresence.class)) {
            service.touch(null);
            service.touch("   ");
            rows.verify(() -> RegisterPresence.findByTerminal(org.mockito.ArgumentMatchers.any()),
                    never());
        }
    }

    /**
     * BO-04-03-05: a register already known has its stamp moved forward,
     * trimmed of whatever the caller padded it with.
     */
    @Test
    void aKnownRegisterHasItsStampMovedForward() {
        RegisterPresenceService service = service(3);
        RegisterPresence row = mock(RegisterPresence.class);
        try (MockedStatic<RegisterPresence> rows = mockStatic(RegisterPresence.class)) {
            rows.when(() -> RegisterPresence.findByTerminal("CAISSE1")).thenReturn(row);
            service.touch("  CAISSE1 ");
            assertNotNull(row.lastSeenAt);
        }
    }

    /**
     * BO-04-03-05: a register silent within the tolerance is online, one
     * silent beyond it is not, and a row that has never been stamped can
     * only be offline.
     */
    @Test
    void silenceIsMeasuredAgainstTheTolerance() {
        RegisterPresenceService service = service(3);
        LocalDateTime now = LocalDateTime.now();
        try (MockedStatic<RegisterPresence> rows = mockStatic(RegisterPresence.class)) {
            rows.when(RegisterPresence::allByAge).thenReturn(List.of(
                    presence("MUETTE", now.minusMinutes(30)),
                    presence("VIVANTE", now.minusMinutes(1)),
                    presence("JAMAIS", null)));
            List<RegisterPresenceService.Presence> all = service.all();
            assertEquals(3, all.size());
            assertFalse(all.get(0).online());
            assertEquals(30L, all.get(0).silentMinutes());
            assertTrue(all.get(1).online());
            assertFalse(all.get(2).online());
            List<RegisterPresenceService.Presence> offline = service.offline();
            assertEquals(2, offline.size());
            assertEquals("MUETTE", offline.get(0).terminalId());
        }
    }

    /**
     * BO-04-03-05: a tolerance of zero or less answers the default rather
     * than declaring every lane offline between two polls.
     */
    @Test
    void anAbsurdToleranceAnswersTheDefault() {
        assertEquals(RegisterPresenceService.DEFAULT_OFFLINE_MINUTES, service(0).offlineMinutes());
        assertEquals(RegisterPresenceService.DEFAULT_OFFLINE_MINUTES, service(-5).offlineMinutes());
        assertEquals(7, service(7).offlineMinutes());
    }

    /**
     * A node no register has ever spoken to reports nothing rather than
     * inventing the lanes the shop declared.
     */
    @Test
    void aNodeNobodySpokeToReportsNothing() {
        RegisterPresenceService service = service(3);
        try (MockedStatic<RegisterPresence> rows = mockStatic(RegisterPresence.class)) {
            rows.when(RegisterPresence::allByAge).thenReturn(List.of());
            assertTrue(service.all().isEmpty());
            assertTrue(service.offline().isEmpty());
        }
    }
}
