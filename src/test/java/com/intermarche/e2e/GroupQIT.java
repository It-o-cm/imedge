package com.intermarche.e2e;

import com.intermarche.pos.domain.sale.DocumentType;
import com.intermarche.pos.domain.session.DocumentCounter;
import com.intermarche.pos.domain.session.TicketCounter;
import com.intermarche.pos.domain.sync.RegisterCommand;
import com.intermarche.pos.service.PeriodCloseService;
import com.intermarche.pos.service.TicketNumberService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Group Q — closure and end-of-day SERVICES, exercised in-container against a
 * booted store node (real beans, real H2). These are not HTTP endpoints, so the
 * beans are injected and driven directly; that is a legitimate integration test
 * here (the app is booted), distinct from the plain-unit tests CLAUDE.md forbids
 * for domain classes.
 *
 * <p>Scope is the deterministic {@code [E]} rows:
 * <ul>
 *   <li>Q2 {@link TicketNumberService} — every sequence format, lazy counter
 *       creation and continuity (terminal {@code POS01}, the config default);</li>
 *   <li>Q1/Q2 {@link PeriodCloseService} disarmed paths — the fresh node leaves
 *       {@code period.auto-close-enabled} at its catalogue default {@code false},
 *       so {@code tick()} issues no order and {@code countdownMinutes()} is -1;
 *       the end-of-period label reflects the default {@code 23:30}.</li>
 * </ul>
 *
 * <p>Deferred (listed, not written) because not reachable deterministically at
 * the node-alone level:
 * <ul>
 *   <li>Q1 "within the closing window → one order per open register", idempotence
 *       per register, emission and the window lead/normal/midnight arithmetic:
 *       {@code inClosingWindow} is package-private and the whole path depends on
 *       the wall clock and on open {@code CashSession}s a register pushes ([R]).</li>
 *   <li>Q2 "within horizon → minutes" / "far → -1": clock-dependent;
 *       {@code minutesUntil} is package-private (not callable from this package).</li>
 *   <li>Q4 {@code TicketRenderService.renderTicket}: needs a complete persisted
 *       ticket graph (store, cashier, lines, payments, valuations) — best
 *       exercised where a sale is played ([R]). Its unknown-number / blank-number
 *       → null rows are the {@code SyncIngestService.renderTicketDuplicate} path,
 *       already covered by GroupCIT C10.</li>
 *   <li>Q5 technical close events: {@code order(..)} is package-private and its
 *       only public entry {@code tick()} is clock- and session-gated ([R]).</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(GroupQIT.Profile.class)
class GroupQIT {

    /** Own profile so the class boots fresh; no override is needed. */
    public static class Profile implements QuarkusTestProfile {

        /**
         * No configuration override — the catalogue defaults (disarmed auto-close,
         * end-time 23:30, terminal POS01) are exactly what these rows assert.
         *
         * @return an empty override map
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of();
        }
    }

    /** The per-register numbering service under test (Q3). */
    @Inject
    TicketNumberService ticketNumberService;

    /** The end-of-period service under test (Q1/Q2). */
    @Inject
    PeriodCloseService periodCloseService;

    /**
     * Resets the counters the numbering tests create and any orders, so each
     * test starts from a virgin sequence (lazy re-creation makes the first
     * number 1 again).
     */
    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            RegisterCommand.deleteAll();
            DocumentCounter.delete("terminalId = ?1", "POS01");
            TicketCounter.delete("terminalId = ?1", "POS01");
        });
    }

    /**
     * Q3 (ticket number): the first ticket number of a fresh counter is
     * {@code POS01-00000001} — lazy creation at {@code lastNumber=0} then
     * incremented, formatted {@code <terminal>-<8 digits>}.
     */
    @Test
    void q3_nextTicketNumber_formatAndLazyStart() {
        String number = ticketNumberService.nextTicketNumber();
        Assertions.assertEquals("POS01-00000001", number,
                "the first ticket number of a fresh counter");
    }

    /**
     * Q3 (session number): the first session number is {@code POS01-S00001},
     * formatted {@code <terminal>-S<5 digits>}.
     */
    @Test
    void q3_nextSessionNumber_format() {
        Assertions.assertEquals("POS01-S00001", ticketNumberService.nextSessionNumber(),
                "the first session number of a fresh counter");
    }

    /**
     * Q3 (refund number): the first refund number is {@code POS01-R000001},
     * formatted {@code <terminal>-R<6 digits>}.
     */
    @Test
    void q3_nextRefundNumber_format() {
        Assertions.assertEquals("POS01-R000001", ticketNumberService.nextRefundNumber(),
                "the first refund number of a fresh counter");
    }

    /**
     * Q3 (customer number): the first account-customer number is
     * {@code POS01-CLI000001}, formatted {@code <terminal>-CLI<6 digits>}.
     */
    @Test
    void q3_nextCustomerNumber_format() {
        Assertions.assertEquals("POS01-CLI000001", ticketNumberService.nextCustomerNumber(),
                "the first customer number of a fresh counter");
    }

    /**
     * Q3 (document number): a document is numbered on its own per-type counter,
     * so the first FACTURE is {@code POS01-F000001} ({@code <terminal>-<prefix><6 digits>}).
     */
    @Test
    void q3_nextDocumentNumber_perTypeCounter() {
        Assertions.assertEquals("POS01-F000001",
                ticketNumberService.nextDocumentNumber(DocumentType.FACTURE),
                "the first FACTURE number of a fresh document counter");
    }

    /**
     * Q3 (continuity): a sequence on one register is strictly increasing with no
     * gap or duplicate.
     */
    @Test
    void q3_ticketSequence_isStrictlyIncreasing() {
        String first = ticketNumberService.nextTicketNumber();
        String second = ticketNumberService.nextTicketNumber();
        String third = ticketNumberService.nextTicketNumber();
        Assertions.assertEquals("POS01-00000001", first, "first");
        Assertions.assertEquals("POS01-00000002", second, "second");
        Assertions.assertEquals("POS01-00000003", third, "third");
    }

    /**
     * Q3 (independent counters): the document sequence does not share the ticket
     * counter — numbering a ticket then a FACTURE gives each its own 1.
     */
    @Test
    void q3_documentCounter_independentFromTicketCounter() {
        Assertions.assertEquals("POS01-00000001", ticketNumberService.nextTicketNumber(),
                "ticket sequence starts at 1");
        Assertions.assertEquals("POS01-F000001",
                ticketNumberService.nextDocumentNumber(DocumentType.FACTURE),
                "the document sequence has its own 1, unaffected by the ticket one");
    }

    /**
     * Q1 (disarmed): with automatic close at its default {@code false}, a tick
     * issues no order at all (no register command is created).
     */
    @Test
    void q1_tick_disarmed_issuesNoOrder() {
        periodCloseService.tick();
        long orders = QuarkusTransaction.requiringNew().call(() -> RegisterCommand.count());
        Assertions.assertEquals(0L, orders,
                "a disarmed auto-close leaves no register command");
    }

    /**
     * Q2 (disarmed): the countdown is -1 (the banner stays mute) while automatic
     * close is disarmed.
     */
    @Test
    void q2_countdown_disarmed_isMinusOne() {
        Assertions.assertEquals(-1L, periodCloseService.countdownMinutes(),
                "a disarmed auto-close makes the countdown mute (-1)");
    }

    /**
     * Q2 (label): the end-of-period label is the administered hour formatted
     * {@code HH:mm} — the catalogue default {@code 23:30} on a fresh node.
     */
    @Test
    void q2_endOfPeriodLabel_isFormattedHourDefault() {
        Assertions.assertEquals("23:30", periodCloseService.endOfPeriodLabel(),
                "the default end-of-period hour, formatted HH:mm");
    }
}
