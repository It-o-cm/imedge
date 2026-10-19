package com.intermarche.edge.imports;

import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.setting.StoreMessage;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

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
     * the terminal's island and session.
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
                .findByTerminal(anyString())).thenReturn(null);
        panache.when(() -> CashSession.findOpenByTerminal(anyString())).thenReturn(session);
        if (terminal == null) {
            panache.when(() -> CashSession.findOpenByTerminal(null)).thenReturn(session);
        }
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
                offRole.pending(null, "C04").getStatus());
        StoreMessageFeedResource badToken = feed(new StoreMessageServing());
        badToken.token = Optional.of("secret");
        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(),
                badToken.pending("wrong", "C04").getStatus());
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
                    resource.pending(null, "C04").getStatus());
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                    resource.pending(null, "C04").getStatus());
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
            Response first = resource.pending(null, "C04");
            assertEquals(Response.Status.OK.getStatusCode(), first.getStatus());
            StoreMessageFeedResource.PendingMessage body =
                    (StoreMessageFeedResource.PendingMessage) first.getEntity();
            assertEquals("Consigne du responsable", body.text());
            assertEquals("Rayon propre ?", body.question());
            assertEquals("Oui;Non", body.options());
            Response second = resource.pending(null, "C04");
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
            Response clearing = resource.pending(null, "C04");
            assertEquals(Response.Status.OK.getStatusCode(), clearing.getStatus());
            StoreMessageFeedResource.PendingMessage body =
                    (StoreMessageFeedResource.PendingMessage) clearing.getEntity();
            assertEquals("", body.text());
            assertNull(body.question());
            assertNull(body.options());
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                    resource.pending(null, "C04").getStatus());
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
            Response clearing = resource.pending(null, "C04");
            assertEquals(Response.Status.OK.getStatusCode(), clearing.getStatus());
            assertEquals("", ((StoreMessageFeedResource.PendingMessage)
                    clearing.getEntity()).text());
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                    resource.pending(null, "C04").getStatus());
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
            Response served = resource.pending(null, "C04");
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
                    resource.pending(null, "C04").getStatus());
            panache.when(() -> CashSession.findOpenByTerminal(anyString())).thenReturn(newer);
            assertEquals(Response.Status.OK.getStatusCode(),
                    resource.pending(null, "C04").getStatus());
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
                    resource.pending(null, "C04").getStatus());
            panache.when(() -> CashSession.findOpenByTerminal(anyString())).thenReturn(null);
            assertEquals(Response.Status.OK.getStatusCode(),
                    resource.pending(null, "C04").getStatus());
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
                new StoreMessageFeedResource.PendingMessage("", null, null);
        assertEquals("", blank.text());
        assertNull(blank.question());
        assertNull(blank.options());
    }
}
