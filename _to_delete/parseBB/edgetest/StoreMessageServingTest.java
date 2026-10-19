package com.intermarche.edge.imports;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StoreMessageServing}, targeting 100% branch coverage.
 * <p>
 * The memory is a plain state holder, so every leg is driven directly: the
 * FIRST service anchors the display window and a re-record does not restart
 * it, a consumed message (expired, taken down or acknowledged) is never
 * served again, and a duration of zero never expires. Each test is fully
 * isolated and asserts absolute expected values.
 */
class StoreMessageServingTest {

    /**
     * Builds a memory holding one recorded service.
     *
     * @param terminal the register served
     * @param messageId the message served
     * @param duration the administered display duration
     * @param servedAt the first service moment
     * @return the ready memory
     */
    private StoreMessageServing served(String terminal, Long messageId, int duration,
                                       LocalDateTime servedAt) {
        StoreMessageServing serving = new StoreMessageServing();
        serving.record(terminal, messageId, duration, servedAt);
        return serving;
    }

    /**
     * The display window is anchored at the FIRST service: recording the same
     * message again does not restart it, so the banner still expires when the
     * shop said it would.
     */
    @Test
    void reRecordingDoesNotRestartTheWindow() {
        LocalDateTime first = LocalDateTime.of(2026, 10, 16, 10, 0, 0);
        StoreMessageServing serving = served("C04", 7L, 60, first);
        LocalDateTime later = first.plusSeconds(50);
        serving.record("C04", 7L, 60, later);
        assertFalse(serving.durationOver("C04", later));
        // Fifty seconds after the RE-record but sixty-one after the first:
        // the window counted from the first is over.
        assertTrue(serving.durationOver("C04", first.plusSeconds(61)));
    }

    /**
     * Recording a DIFFERENT message replaces the entry — a replaced notice
     * anchors its own window, and the replaced one was never consumed.
     */
    @Test
    void recordingAnotherMessageReplacesTheEntry() {
        LocalDateTime first = LocalDateTime.of(2026, 10, 16, 10, 0, 0);
        StoreMessageServing serving = served("C04", 7L, 0, first);
        serving.record("C04", 8L, 60, first.plusSeconds(30));
        assertFalse(serving.durationOver("C04", first.plusSeconds(40)));
        assertTrue(serving.durationOver("C04", first.plusSeconds(91)));
    }

    /**
     * A duration of ZERO never expires — the banner stays until the store
     * replaces or takes down the notice, which is the administration screen's
     * promise.
     */
    @Test
    void aZeroDurationNeverExpires() {
        LocalDateTime first = LocalDateTime.of(2026, 10, 16, 10, 0, 0);
        StoreMessageServing serving = served("C04", 7L, 0, first);
        assertFalse(serving.durationOver("C04", first.plusDays(7)));
    }

    /**
     * The null legs: recording with a null terminal or a null message is a
     * no-op, an unknown terminal has no window to close, and nothing is ever
     * consumed for a null pair.
     */
    @Test
    void nullInputsAreNoOps() {
        StoreMessageServing serving = new StoreMessageServing();
        serving.record(null, 7L, 60, LocalDateTime.now());
        serving.record("C04", null, 60, LocalDateTime.now());
        assertFalse(serving.durationOver("C04", LocalDateTime.now()));
        assertFalse(serving.durationOver(null, LocalDateTime.now()));
        assertFalse(serving.consume(null));
        assertFalse(serving.consume("C04"));
        assertFalse(serving.isConsumed(null, 7L));
        assertFalse(serving.isConsumed("C04", null));
    }

    /**
     * Consuming the served message answers that the banner must be cleared
     * exactly once, then nothing — the notice is never served to that lane
     * again (expired, taken down or acknowledged, the three ways a message
     * disappears, LC-01-08-05).
     */
    @Test
    void consumingAnswersOnceThenNeverServesAgain() {
        LocalDateTime first = LocalDateTime.of(2026, 10, 16, 10, 0, 0);
        StoreMessageServing serving = served("C04", 7L, 60, first);
        assertTrue(serving.consume("C04"));
        assertFalse(serving.consume("C04"));
        assertTrue(serving.isConsumed("C04", 7L));
        assertFalse(serving.isConsumed("C04", 8L));
        assertFalse(serving.isConsumed("C05", 7L));
        // No entry anymore: there is no window left to close.
        assertFalse(serving.durationOver("C04", first.plusSeconds(120)));
    }
}
