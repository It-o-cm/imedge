package com.intermarche.edge.imports;

import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.setting.StoreMessage;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StoreMessageFeedResource}, the node end of the
 * register message channel, and of the display-duration lifecycle it now
 * carries (LC-01-08-05).
 * <p>
 * The Panache finders ({@code StoreMessage.listServable},
 * {@code CheckoutPoint.findByTerminal}, {@code CashSession.findOpenByTerminal})
 * are intercepted with {@code mockStatic}; the serving memory is the REAL
 * object, because the feed's contract is precisely what it does over time:
 * serve once, keep serving inside the administered window, answer the single
 * blank that clears the lane's banner when the window closes or the notice is
 * taken down, and never serve a consumed notice again. Every leg of the gate
 * guard and of the trigger resolution has its own case. Each test is fully
 * isolated and asserts absolute expected values.
 */
class StoreMessageFeedResourceTest {

    /**
     * Builds a store-role resource with an open token and a real serving
     * memory.
     *
     * @param serving the serving memory the feed keeps
     * @return the ready resource
     */
    private StoreMessageFeedResource feed(StoreMessageServing serving) {
        StoreMessageFeedResource resource = new StoreMessageFeedResource();
        resource.role = "store";
        resource.token = Optional.empty();
        resource.serving = serving;
        return resource;
    }

    /**
     * Builds a servable immediate notice for the whole store.
     *
     * @param id the message id
     * @param text the message text
     * @param durationSeconds the administered display duration
     * @return the message
     */
    private StoreMessage notice(long id, String text, int durationSeconds) {
        StoreMessage message = new StoreMessage();
        message.id = id;
        message.text = text;
        message.durationSeconds = durationSeconds;
        message.audience = StoreMessage.Audience.ALL;
        message.trigger = StoreMessage.Trigger.IMMEDIATE;
        return message;
    }

    /**
     * Stubs the statics one pending() call reads: the servable messages and
     * the terminal's island and session. The finders run REAL code — the
     * null guard of {@code findByTerminal} among it — so what is stubbed is
     * the intercepted Panache find, on the very query the finder issues,
     * returning a query mock whose first result is the one the resource
     * will read.
     *
     * @param panache the active Panache static mock
     * @param messages the servable messages
     * @param terminal the register asking
     * @param session the open session on that register, or null
     */
    private void stub(MockedStatic<PanacheEntityBase> panache, List<StoreMessage> messages,
                      String terminal, CashSession session) {
        panache.when(() -> StoreMessage.listServable()).thenReturn(messages);
        panache.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                .find("terminalId", terminal)).thenReturn(mock(PanacheQuery.class));
        PanacheQuery<CashSession> open = openSessionQuery(session);
        panache.when(() -> CashSession.find("terminalId = ?1 and status = ?2",
                terminal, CashSession.SessionStatus.OPEN)).thenReturn(open);
    }

    /**
     * Wraps a session in the query the open-session finder resolves.
     *
     * @param session the open session, or null for none
     * @return a query mock whose first result is that session
     */
    private PanacheQuery<CashSession> openSessionQuery(CashSession session) {
        PanacheQuery<CashSession> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(session);
        return query;
    }

    /**
     * The two gates of the machine surface: an off-role node answers 403 and
     * a mismatched token 401, without reading the referential at all.
     */
    @Test
    void theGatesRefuseOffRoleAndBadToken() {
        StoreMessageFeedResource offRole = new StoreMessageFeedResource();
        offRole.role = "register";
        offRole.token = Optional.empty();
        offRole.serving = new StoreMessageServing();
        assertEquals(Response.Status.FORBIDDEN.getStatusCode(),
                offRole.pending(null, "C04", null).getStatus());
        StoreMessageFeedResource badToken = feed(new StoreMessageServing());
        badToken.token = Optional.of("secret");
        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(),
                badToken.pending("wrong", "C04", null).getStatus());
    }

    /**
     * A register the store has nothing to say to gets a 204, and keeps
     * getting it — nothing was ever served, so no blank ever goes out
     * (the no-message, nothing-consumed arm).
     */
    @Test
    void aRegisterWithNothingToShowGetsANoContent() {
        StoreMessageServing serving = new StoreMessageServing();
        StoreMessageFeedResource resource = feed(serving);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, List.of(), "C04", null);
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                    resource.pending(null, "C04", null).getStatus());
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                    resource.pending(null, "C04", null).getStatus());
        }
    }

    /**
     * An applying message is served in the frozen wire shape — text, question
     * and options — and served AGAIN on the next poll inside its window: the
     * lane's banner is already up and re-serving changes nothing on it.
     */
    @Test
    void anApplyingMessageIsServedAndKeptInsideItsWindow() {
        StoreMessageServing serving = new StoreMessageServing();
        StoreMessageFeedResource resource = feed(serving);
        StoreMessage message = notice(7L, "Consigne du responsable", 0);
        message.question = "Rayon propre ?";
        message.options = "Oui;Non";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, List.of(message), "C04", null);
            Response first = resource.pending(null, "C04", null);
            assertEquals(Response.Status.OK.getStatusCode(), first.getStatus());
            StoreMessageFeedResource.PendingMessage body =
                    (StoreMessageFeedResource.PendingMessage) first.getEntity();
            assertEquals("Consigne du responsable", body.text());
            assertEquals("Rayon propre ?", body.question());
            assertEquals("Oui;Non", body.options());
            Response second = resource.pending(null, "C04", null);
            assertEquals(Response.Status.OK.getStatusCode(), second.getStatus());
            assertEquals("Consigne du responsable",
                    ((StoreMessageFeedResource.PendingMessage) second.getEntity()).text());
        }
    }

    /**
     * LC-01-08-05: when the administered display duration has elapsed the
     * node answers the BLANK message exactly once — the lane clears its
     * banner — and then nothing: the notice is consumed and never served to
     * that register again.
     */
    @Test
    void anExpiredMessageIsBlankedOnceThenNeverServed() {
        StoreMessageServing serving = new StoreMessageServing();
        StoreMessageFeedResource resource = feed(serving);
        StoreMessage message = notice(7L, "Consigne du responsable", 60);
        // The window opened two minutes ago: sixty administered seconds are
        // long gone, and the first service is what anchors it.
        serving.record("C04", 7L, 60, LocalDateTime.now().minusSeconds(120));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, List.of(message), "C04", null);
            Response clearing = resource.pending(null, "C04", null);
            assertEquals(Response.Status.OK.getStatusCode(), clearing.getStatus());
            StoreMessageFeedResource.PendingMessage body =
                    (StoreMessageFeedResource.PendingMessage) clearing.getEntity();
            assertEquals("", body.text());
            assertNull(body.question());
            assertNull(body.options());
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                    resource.pending(null, "C04", null).getStatus());
        }
    }

    /**
     * LC-01-08-05: a message the back office took down — deactivated, period
     * over, audience changed — gets the same single blank answer on the lane
     * that was shown it, then nothing.
     */
    @Test
    void aTakenDownMessageIsBlankedOnceOnTheLaneThatSawIt() {
        StoreMessageServing serving = new StoreMessageServing();
        StoreMessageFeedResource resource = feed(serving);
        StoreMessage message = notice(7L, "Consigne du responsable", 0);
        serving.record("C04", 7L, 0, LocalDateTime.now());
        // The store deactivates the notice: it stops applying.
        message.active = false;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, List.of(), "C04", null);
            Response clearing = resource.pending(null, "C04", null);
            assertEquals(Response.Status.OK.getStatusCode(), clearing.getStatus());
            assertEquals("", ((StoreMessageFeedResource.PendingMessage)
                    clearing.getEntity()).text());
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                    resource.pending(null, "C04", null).getStatus());
        }
    }

    /**
     * LC-01-08-05: an acknowledged message is never served again to the lane
     * whose cashier read it — the resolve skips it and the next servable
     * notice, when there is one, takes the banner.
     */
    @Test
    void anAcknowledgedMessageIsSkippedForItsLane() {
        StoreMessageServing serving = new StoreMessageServing();
        StoreMessageFeedResource resource = feed(serving);
        StoreMessage acked = notice(7L, "Consigne lue", 0);
        StoreMessage next = notice(8L, "Nouvelle consigne", 0);
        serving.record("C04", 7L, 0, LocalDateTime.now());
        serving.consume("C04");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, List.of(acked, next), "C04", null);
            Response served = resource.pending(null, "C04", null);
            assertEquals("Nouvelle consigne", ((StoreMessageFeedResource.PendingMessage)
                    served.getEntity()).text());
        }
    }

    /**
     * BO-03-08-01: a message triggered at the OPENING of a session waits for
     * a session that began after the message did — a cashier already at their
     * post when the notice was written does not get it.
     */
    @Test
    void aSessionOpenTriggerWaitsForANewerSession() {
        StoreMessageServing serving = new StoreMessageServing();
        StoreMessageFeedResource resource = feed(serving);
        StoreMessage message = notice(7L, "Bienvenue", 0);
        message.trigger = StoreMessage.Trigger.SESSION_OPEN;
        message.startsAt = LocalDateTime.of(2026, 10, 16, 8, 0, 0);
        CashSession older = new CashSession();
        older.openingDate = LocalDateTime.of(2026, 10, 16, 7, 0, 0);
        CashSession newer = new CashSession();
        newer.openingDate = LocalDateTime.of(2026, 10, 16, 9, 0, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, List.of(message), "C04", older);
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                    resource.pending(null, "C04", null).getStatus());
            PanacheQuery<CashSession> newerQuery = openSessionQuery(newer);
            panache.when(() -> CashSession.find("terminalId = ?1 and status = ?2",
                    "C04", CashSession.SessionStatus.OPEN)).thenReturn(newerQuery);
            assertEquals(Response.Status.OK.getStatusCode(),
                    resource.pending(null, "C04", null).getStatus());
        }
    }

    /**
     * BO-03-08-02: a message triggered at the CLOSING waits for the register
     * to have no open session, and is served while it has none.
     */
    @Test
    void aSessionCloseTriggerWaitsForNoSession() {
        StoreMessageServing serving = new StoreMessageServing();
        StoreMessageFeedResource resource = feed(serving);
        StoreMessage message = notice(7L, "Pensez au comptage", 0);
        message.trigger = StoreMessage.Trigger.SESSION_CLOSE;
        CashSession open = new CashSession();
        open.openingDate = LocalDateTime.of(2026, 10, 16, 8, 0, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, List.of(message), "C04", open);
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                    resource.pending(null, "C04", null).getStatus());
            PanacheQuery<CashSession> closedQuery = openSessionQuery(null);
            panache.when(() -> CashSession.find("terminalId = ?1 and status = ?2",
                    "C04", CashSession.SessionStatus.OPEN)).thenReturn(closedQuery);
            assertEquals(Response.Status.OK.getStatusCode(),
                    resource.pending(null, "C04", null).getStatus());
        }
    }

    /**
     * The blank answer itself is a message a register can parse: the poller
     * of a deployed lane reads {@code \{text, question, options\}} and clears
     * its banner on the blank text.
     */
    @Test
    void theBlankAnswerCarriesTheFrozenShape() {
        StoreMessageFeedResource.PendingMessage blank =
                new StoreMessageFeedResource.PendingMessage("", null, null, null, false, null, null);
        assertEquals("", blank.text());
        assertNull(blank.question());
        assertNull(blank.options());
    }

    /**
     * LC-03-07-01: a register naming its moment is served ONLY the messages
     * administered for it — the immediate notice written last does not
     * hijack the ask.
     */
    @Test
    void aMomentAskServesOnlyTheMessagesOfThatMoment() {
        StoreMessageFeedResource resource = feed(new StoreMessageServing());
        StoreMessage immediate = notice(9L, "Reunion 14h", 0);
        StoreMessage start = notice(8L, "", 0);
        start.question = "Premier article ?";
        start.trigger = StoreMessage.Trigger.TRANSACTION_START;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, List.of(immediate, start), "C04", null);
            Response answered = resource.pending(null, "C04", "TRANSACTION_START");
            assertEquals(Response.Status.OK.getStatusCode(), answered.getStatus());
            assertEquals("Premier article ?", ((StoreMessageFeedResource.PendingMessage)
                    answered.getEntity()).question());
            Response ordinary = resource.pending(null, "C04", null);
            assertEquals("Reunion 14h", ((StoreMessageFeedResource.PendingMessage)
                    ordinary.getEntity()).text());
        }
    }

    /**
     * BO-06-03-01: a survey of the start or the end of the ticket is never
     * served by the ordinary poll — the register that is at neither moment
     * would show it out of any transaction — and still is when its moment is
     * asked for; served for a moment, it is no banner: the ordinary poll does
     * not take it down, and the next transaction is asked it again.
     */
    @Test
    void theOrdinaryPollNeverServesATransactionMomentSurvey() {
        StoreMessageFeedResource resource = feed(new StoreMessageServing());
        StoreMessage start = notice(8L, "", 0);
        start.question = "Premier article ?";
        start.trigger = StoreMessage.Trigger.TRANSACTION_START;
        StoreMessage end = notice(7L, "", 0);
        end.question = "Ticket OK ?";
        end.trigger = StoreMessage.Trigger.TRANSACTION_END;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, List.of(start, end), "C04", null);
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                    resource.pending(null, "C04", null).getStatus());
            assertEquals("Ticket OK ?", ((StoreMessageFeedResource.PendingMessage)
                    resource.pending(null, "C04", "TRANSACTION_END").getEntity()).question());
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                    resource.pending(null, "C04", null).getStatus());
            assertEquals("Ticket OK ?", ((StoreMessageFeedResource.PendingMessage)
                    resource.pending(null, "C04", "TRANSACTION_END").getEntity()).question());
        }
        assertEquals(false, StoreMessageFeedResource.isTransactionMoment(null));
        assertEquals(false, StoreMessageFeedResource.isTransactionMoment(StoreMessage.Trigger.IMMEDIATE));
        assertEquals(true, StoreMessageFeedResource.isTransactionMoment(StoreMessage.Trigger.TRANSACTION_START));
        assertEquals(true, StoreMessageFeedResource.isTransactionMoment(StoreMessage.Trigger.TRANSACTION_END));
    }

    /**
     * BO-06-03-02: a TRANSACTION moment without survey of its own answers
     * nothing, and leaves the survey on screen alone: only the ordinary poll
     * takes a message down.
     */
    @Test
    void aMomentWithoutSurveyLeavesTheShownOneAlone() {
        StoreMessageFeedResource resource = feed(new StoreMessageServing());
        StoreMessage immediate = notice(9L, "", 0);
        immediate.question = "Prospectus ?";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, List.of(immediate), "C04", null);
            assertEquals("Prospectus ?", ((StoreMessageFeedResource.PendingMessage)
                    resource.pending(null, "C04", null).getEntity()).question());
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                    resource.pending(null, "C04", "TRANSACTION_END").getStatus());
            assertEquals("Prospectus ?", ((StoreMessageFeedResource.PendingMessage)
                    resource.pending(null, "C04", null).getEntity()).question());
        }
    }

    /**
     * LC-03-07-02: the end-of-transaction moment serves its own survey even
     * while the register's session is still open — the register names the
     * moment, the node does not re-decide it.
     */
    @Test
    void aTransactionEndAskServesItsSurveyWhileTheSessionIsOpen() {
        StoreMessageFeedResource resource = feed(new StoreMessageServing());
        StoreMessage end = notice(5L, "", 0);
        end.question = "Ticket OK ?";
        end.trigger = StoreMessage.Trigger.TRANSACTION_END;
        CashSession open = new CashSession();
        open.openingDate = LocalDateTime.of(2026, 10, 16, 8, 0, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, List.of(end), "C04", open);
            Response answered = resource.pending(null, "C04", "TRANSACTION_END");
            assertEquals(Response.Status.OK.getStatusCode(), answered.getStatus());
            assertEquals("Ticket OK ?", ((StoreMessageFeedResource.PendingMessage)
                    answered.getEntity()).question());
        }
    }

    /**
     * LC-03-07-06 to -10: the wire object carries the survey's answer
     * machinery — the response type, the obligation, the postal mask and the
     * administered list of codes.
     */
    @Test
    void theServedSurveyCarriesItsAnswerMachinery() {
        StoreMessageFeedResource resource = feed(new StoreMessageServing());
        StoreMessage postal = notice(4L, "", 0);
        postal.question = "Code postal ?";
        postal.responseType = StoreMessage.ResponseType.POSTAL_CODE;
        postal.required = true;
        postal.postalFormat = "CCCCC";
        postal.postalCodes = "33980;62100";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, List.of(postal), "C04", null);
            Response answered = resource.pending(null, "C04", null);
            StoreMessageFeedResource.PendingMessage served =
                    (StoreMessageFeedResource.PendingMessage) answered.getEntity();
            assertEquals("POSTAL_CODE", served.responseType());
            assertTrue(served.required());
            assertEquals("CCCCC", served.postalFormat());
            assertEquals("33980;62100", served.postalCodes());
        }
    }

    /**
     * BO-06-03-01: a yes / no question is served as the choice of its two
     * answers — « Oui;Non » when it names none, blank or not, its own
     * otherwise.
     */
    @Test
    void aYesNoQuestionIsServedAsAChoice() {
        StoreMessageFeedResource resource = feed(new StoreMessageServing());
        StoreMessage survey = notice(5L, "", 0);
        survey.question = "Satisfait ?";
        survey.responseType = StoreMessage.ResponseType.YES_NO;
        StoreMessageFeedResource.PendingMessage served = resource.pendingMessage(survey);
        assertEquals("OPTIONS", served.responseType());
        assertEquals("Oui;Non", served.options());
        assertEquals("Satisfait ?", served.question());
        survey.options = " ";
        assertEquals("Oui;Non", resource.pendingMessage(survey).options());
        survey.options = "Yes;No";
        assertEquals("Yes;No", resource.pendingMessage(survey).options());
    }

    /**
     * LC-03-07-03: a scanned id is served when the store still stands behind
     * the message — active, in period, addressed to that register — and the
     * serving memory is neither consulted nor written: the scan asks, the
     * scan gets, once.
     */
    @Test
    void aScannedIdServesItsSurveyWithoutTheServingMemory() {
        StoreMessageFeedResource resource = feed(new StoreMessageServing());
        StoreMessage scanned = notice(12L, "", 0);
        scanned.question = "Rayon propre ?";
        scanned.options = "Oui;Non";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> StoreMessage.findById(12L)).thenReturn(scanned);
            panache.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .find("terminalId", "C04")).thenReturn(mock(PanacheQuery.class));
            Response answered = resource.survey(null, "C04", 12L);
            assertEquals(Response.Status.OK.getStatusCode(), answered.getStatus());
            StoreMessageFeedResource.PendingMessage served =
                    (StoreMessageFeedResource.PendingMessage) answered.getEntity();
            assertEquals("Rayon propre ?", served.question());
            assertEquals("Oui;Non", served.options());
        }
    }

    /**
     * LC-03-07-03, the refusal arms: a scanned id nobody administers, and a
     * scanned id the audience excludes, answer nothing — the register clears
     * its scan and carries on.
     */
    @Test
    void aScannedIdNobodyServesAnswersNothing() {
        StoreMessageFeedResource resource = feed(new StoreMessageServing());
        StoreMessage otherTill = notice(12L, "", 0);
        otherTill.question = "Rayon propre ?";
        otherTill.audience = StoreMessage.Audience.TERMINAL;
        otherTill.audienceValue = "C07";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> StoreMessage.findById(12L)).thenReturn(otherTill);
            panache.when(() -> com.intermarche.edge.domain.store.CheckoutPoint
                    .find("terminalId", "C04")).thenReturn(mock(PanacheQuery.class));
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                    resource.survey(null, "C04", 12L).getStatus());
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                    resource.survey(null, "C04", null).getStatus());
        }
    }
    /** LC-03-07: the survey endpoint enforces the store role and token before resolving nullable/missing ids. */
    @Test
    void coverageSurveyEndpointGuards() {
        var resource = feed(new StoreMessageServing());
        resource.role = "register";
        assertEquals(403, resource.survey(null, null, null).getStatus());
        resource.role = "store";
        resource.token = java.util.Optional.of("TOKEN");
        assertEquals(401, resource.survey("wrong", null, null).getStatus());
        assertEquals(204, resource.survey("TOKEN", null, null).getStatus());
        var message = notice(8L, "NOTICE", 0);
        try (var base = mockStatic(PanacheEntityBase.class)) {
            assertEquals(204, resource.survey("TOKEN", null, 9L).getStatus());
            base.when(() -> PanacheEntityBase.findById(8L)).thenReturn(message);
            assertEquals(200, resource.survey("TOKEN", null, 8L).getStatus());
            message.active = false;
            assertEquals(204, resource.survey("TOKEN", null, 8L).getStatus());
        }
    }

    /** LC-03-07: every transaction trigger and absent register path is interpreted without inventing a session. */
    @Test
    void coverageTriggerAndMomentGuards() throws Exception {
        var resource = feed(new StoreMessageServing());
        var trigger = StoreMessageFeedResource.class.getDeclaredMethod("triggerOf", String.class);
        trigger.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertNull(trigger.invoke(resource, " "));
        for (var kind : StoreMessage.Trigger.values()) assertEquals(kind, trigger.invoke(resource, kind.name()));
        var moment = StoreMessageFeedResource.class.getDeclaredMethod("momentHasCome", StoreMessage.class, String.class);
        moment.setAccessible(true);
        var message = notice(8L, "NOTICE", 0);
        message.trigger = StoreMessage.Trigger.TRANSACTION_START;
        assertEquals(true, moment.invoke(resource, message, null));
        message.trigger = StoreMessage.Trigger.TRANSACTION_END;
        assertEquals(true, moment.invoke(resource, message, null));
        message.trigger = StoreMessage.Trigger.SESSION_CLOSE;
        assertEquals(true, moment.invoke(resource, message, " "));
        var island = StoreMessageFeedResource.class.getDeclaredMethod("islandOf", String.class);
        island.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertNull(island.invoke(resource, " "));
    }

    /** BO-03-08: opening notices require a current, sufficiently recent session and keep the distributed island. */
    @Test
    void sessionOpeningMomentRequiresARecentOpenSession() throws Exception {
        var resource = feed(new StoreMessageServing());
        var message = notice(20L, "OPENING", 0);
        message.trigger = StoreMessage.Trigger.SESSION_OPEN;
        var now = LocalDateTime.now();
        var session = new com.intermarche.edge.domain.session.CashSession();
        session.openingDate = now;
        var point = new com.intermarche.edge.domain.store.CheckoutPoint();
        point.islandCode = "ISLAND";
        try (var sessions = mockStatic(com.intermarche.edge.domain.session.CashSession.class);
             var points = mockStatic(com.intermarche.edge.domain.store.CheckoutPoint.class);
             var messages = mockStatic(StoreMessage.class)) {
            messages.when(StoreMessage::listServable).thenReturn(java.util.List.of(message));
            points.when(() -> com.intermarche.edge.domain.store.CheckoutPoint.findByTerminal("C04")).thenReturn(point);
            org.junit.jupiter.api.Assertions.assertNull(resource.resolve("C04", now, null));
            org.junit.jupiter.api.Assertions.assertFalse(resource.momentHasCome(message, null));
            sessions.when(() -> com.intermarche.edge.domain.session.CashSession.findOpenByTerminal("C04")).thenReturn(session);
            message.startsAt = null;
            org.junit.jupiter.api.Assertions.assertTrue(resource.momentHasCome(message, "C04"));
            message.startsAt = now.plusMinutes(1);
            org.junit.jupiter.api.Assertions.assertFalse(resource.momentHasCome(message, "C04"));
            message.startsAt = now.minusMinutes(1);
            org.junit.jupiter.api.Assertions.assertTrue(resource.momentHasCome(message, "C04"));
            var island = StoreMessageFeedResource.class.getDeclaredMethod("islandOf", String.class);
            island.setAccessible(true);
            assertEquals("ISLAND", island.invoke(resource, "C04"));
            message.active = false;
            org.junit.jupiter.api.Assertions.assertNull(resource.resolve("C04", now, null));
            message.active = true;
            resource.token = java.util.Optional.of("TOKEN");
            assertEquals(200, resource.pending("TOKEN", "C04", "UNKNOWN").getStatus());
        }
    }

}
