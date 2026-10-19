package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.home.HomeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BackOfficeMessagePoller}, targeting 100% branch coverage.
 * <p>
 * The poller pulls a pending back-office message and applies it through
 * {@link HomeService}. Collaborators are mocked; the scheduled executor is read
 * by reflection to assert the startup guard arms without waiting on a real
 * schedule. Each test is isolated and asserts absolute expected values.
 */
class BackOfficeMessagePollerTest {

    /**
     * Builds a poller with mocked collaborators and the given node URL.
     *
     * @param url the node message URL Optional
     * @return the wired poller
     */
    private BackOfficeMessagePoller poller(Optional<String> url) {
        BackOfficeMessagePoller poller = new BackOfficeMessagePoller();
        poller.client = mock(BackOfficeMessageClient.class);
        poller.homeService = mock(HomeService.class);
        poller.state = mock(PosState.class);
        poller.nodeUrl = url;
        poller.pollSeconds = 20L;
        return poller;
    }

    /**
     * Reads the private {@code executor} field.
     *
     * @param poller the poller
     * @return the executor, or null
     * @throws Exception if reflection fails
     */
    private ScheduledExecutorService executor(BackOfficeMessagePoller poller) throws Exception {
        Field field = BackOfficeMessagePoller.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(poller);
    }

    /**
     * A null fetch is a no-op: the current banner is kept, nothing applied, no
     * touch (null-message arm).
     */
    @Test
    void pollOnceKeepsBannerOnNullFetch() {
        BackOfficeMessagePoller poller = poller(Optional.of("http://node/msg"));
        when(poller.client.fetchPending()).thenReturn(null);
        poller.pollOnce();
        verify(poller.homeService, never()).receiveBackOfficeMessage(any(), any());
        verify(poller.state, never()).touch();
    }

    /**
     * A plain message (no question) is applied with a null survey and touches the
     * state (question-null arm).
     */
    @Test
    void pollOnceAppliesAPlainMessage() {
        BackOfficeMessagePoller poller = poller(Optional.of("http://node/msg"));
        when(poller.client.fetchPending()).thenReturn(
                new BackOfficeMessageClient.PendingMessage("Réunion 14h", null, null, null, false, null, null));
        poller.pollOnce();
        verify(poller.homeService).receiveBackOfficeMessage(eq("Réunion 14h"), isNull());
        verify(poller.state).touch();
    }

    /**
     * A blank question is treated as no survey (question-blank arm).
     */
    @Test
    void pollOnceTreatsBlankQuestionAsNoSurvey() {
        BackOfficeMessagePoller poller = poller(Optional.of("http://node/msg"));
        when(poller.client.fetchPending()).thenReturn(
                new BackOfficeMessageClient.PendingMessage("txt", "   ", "a;b", null, false, null, null));
        poller.pollOnce();
        verify(poller.homeService).receiveBackOfficeMessage(eq("txt"), isNull());
    }

    /**
     * A survey (question + options) is built, blank options dropped, and applied
     * (question-present arm, options non-null with a blank entry skipped).
     */
    @Test
    void pollOnceBuildsASurveyDroppingBlankOptions() {
        BackOfficeMessagePoller poller = poller(Optional.of("http://node/msg"));
        when(poller.client.fetchPending()).thenReturn(
                new BackOfficeMessageClient.PendingMessage(null, "Café ?", "Oui; ;Non", null, false, null, null));
        ArgumentCaptor<PosState.BackOfficeSurvey> captor =
                ArgumentCaptor.forClass(PosState.BackOfficeSurvey.class);
        poller.pollOnce();
        verify(poller.homeService).receiveBackOfficeMessage(isNull(), captor.capture());
        PosState.BackOfficeSurvey survey = captor.getValue();
        assertEquals("Café ?", survey.question);
        assertEquals(java.util.List.of("Oui", "Non"), survey.options);
    }

    /**
     * A survey whose options are null yields an empty option list (options-null arm).
     */
    @Test
    void pollOnceSurveyWithNullOptionsHasNoOptions() {
        BackOfficeMessagePoller poller = poller(Optional.of("http://node/msg"));
        when(poller.client.fetchPending()).thenReturn(
                new BackOfficeMessageClient.PendingMessage(null, "Q", null, null, false, null, null));
        ArgumentCaptor<PosState.BackOfficeSurvey> captor =
                ArgumentCaptor.forClass(PosState.BackOfficeSurvey.class);
        poller.pollOnce();
        verify(poller.homeService).receiveBackOfficeMessage(isNull(), captor.capture());
        assertEquals(0, captor.getValue().options.size());
    }

    /**
     * {@code pollSafely} swallows an exception thrown by the tick, so a failed
     * poll never kills the loop (catch arm).
     */
    @Test
    void pollSafelySwallowsErrors() {
        BackOfficeMessagePoller poller = poller(Optional.of("http://node/msg"));
        when(poller.client.fetchPending()).thenThrow(new RuntimeException("boom"));
        poller.pollSafely();
        verify(poller.client).fetchPending();
    }

    /**
     * {@code onStart} starts no loop when no node URL is configured (absent arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void onStartSkipsWhenNoUrl() throws Exception {
        BackOfficeMessagePoller poller = poller(Optional.empty());
        poller.onStart(null);
        assertNull(executor(poller));
    }

    /**
     * {@code onStart} starts no loop when the node URL is blank (blank arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void onStartSkipsWhenBlankUrl() throws Exception {
        BackOfficeMessagePoller poller = poller(Optional.of("  "));
        poller.onStart(null);
        assertNull(executor(poller));
    }

    /**
     * {@code onStart} creates the executor when a node URL is configured, and
     * {@code onStop} shuts it down (started arm + non-null onStop arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void onStartCreatesExecutorAndOnStopShutsItDown() throws Exception {
        BackOfficeMessagePoller poller = poller(Optional.of("http://node/msg"));
        poller.onStart(null);
        ScheduledExecutorService executor = executor(poller);
        assertNotNull(executor);
        poller.onStop();
        org.junit.jupiter.api.Assertions.assertTrue(executor.isShutdown());
    }

    /**
     * {@code onStop} is a no-op when no executor was ever created (null arm).
     */
    @Test
    void onStopIsNoOpWhenExecutorNull() {
        BackOfficeMessagePoller poller = poller(Optional.empty());
        poller.onStop();
    }
}
