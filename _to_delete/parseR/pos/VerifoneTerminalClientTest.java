package com.intermarche.pos.ui.hardware.terminal;

import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link VerifoneTerminalClient} SKELETON: what is
 * testable today is the discipline around the missing protocol — the
 * unreachable-client error leg, the not-implemented error leg, the busy
 * rejection and the inert session hooks. The exchange runs on the client's
 * own thread, so the tests await the callback with a latch.
 */
class VerifoneTerminalClientTest {

    /**
     * A transport stub whose reachability is fixed and whose exchange
     * always hits the not-implemented barrier (the real one's behavior
     * until the protocol specification is available).
     */
    private static class StubTransport extends VerifoneTransport {

        /** The fixed reachability answer. */
        private final boolean reachable;

        /**
         * Creates the stub with a fixed reachability.
         *
         * @param reachable the answer {@code isReachable()} returns
         */
        StubTransport(boolean reachable) {
            super("127.0.0.1", 1, 10);
            this.reachable = reachable;
        }

        /**
         * Returns the fixed reachability.
         *
         * @return the configured answer
         */
        @Override
        public boolean isReachable() {
            return reachable;
        }

        /**
         * Behaves like the real transport pending the specification.
         *
         * @param requestFrame the encoded request
         * @return never returns
         * @throws IOException never
         */
        @Override
        public String exchange(String requestFrame) throws IOException {
            throw new UnsupportedOperationException("spec required");
        }
    }

    /**
     * A callback that latches on any terminal leg and captures the error
     * message, used when a test only needs the exchange to terminate.
     */
    private static class LatchingCallback implements TerminalTransactionCallback {

        /** Fires once any terminal leg has been received. */
        private final CountDownLatch latch = new CountDownLatch(1);

        /** Captures the message received on the error leg, if any. */
        private final AtomicReference<String> error = new AtomicReference<>();

        /**
         * Unexpected accept leg: only releases the latch.
         *
         * @param outcome the unexpected outcome
         */
        @Override
        public void onAccepted(TerminalOutcome outcome) {
            latch.countDown();
        }

        /**
         * Unexpected refuse leg: only releases the latch.
         *
         * @param outcome the unexpected outcome
         */
        @Override
        public void onRefused(TerminalOutcome outcome) {
            latch.countDown();
        }

        /**
         * Captures the error message and releases the latch.
         *
         * @param message the operator-facing message
         */
        @Override
        public void onError(String message) {
            error.set(message);
            latch.countDown();
        }
    }

    /**
     * Builds a Mockito transport whose {@code isReachable()} parks the
     * exchange thread — signalling entry, then awaiting the release latch —
     * so the client stays in flight ({@code busy == true}) long enough for
     * the calling thread to exercise the busy-rejection and abort-while-busy
     * legs. It returns unreachable once released so the parked exchange ends
     * deterministically on the unreachable leg; no real socket is opened.
     *
     * @param entered counted down once the probe is entered
     * @param release awaited inside the probe until counted down
     * @return the blocking Mockito transport
     */
    private VerifoneTransport blockingTransport(CountDownLatch entered, CountDownLatch release) {
        VerifoneTransport transport = Mockito.mock(VerifoneTransport.class);
        Mockito.when(transport.isReachable()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return false;
        });
        return transport;
    }

    /**
     * Awaits the single callback leg fired by a debit request.
     *
     * @param client the client under test
     * @param amount the amount to request
     * @return the error message received on the error leg
     * @throws InterruptedException when the wait is interrupted
     */
    private String awaitError(VerifoneTerminalClient client, String amount)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        client.requestDebit(new BigDecimal(amount), new TerminalTransactionCallback() {
            /**
             * Fails the test path: the skeleton never accepts.
             *
             * @param outcome the unexpected outcome
             */
            @Override
            public void onAccepted(TerminalOutcome outcome) {
                error.set("UNEXPECTED-ACCEPT");
                latch.countDown();
            }

            /**
             * Fails the test path: the skeleton never refuses.
             *
             * @param outcome the unexpected outcome
             */
            @Override
            public void onRefused(TerminalOutcome outcome) {
                error.set("UNEXPECTED-REFUSE");
                latch.countDown();
            }

            /**
             * Captures the expected error leg.
             *
             * @param message the operator-facing message
             */
            @Override
            public void onError(String message) {
                error.set(message);
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "callback never fired");
        return error.get();
    }

    /**
     * An unreachable monetique client ends on the error leg with the
     * unreachable message (reachability guard arm).
     *
     * @throws InterruptedException never under the test timeout
     */
    @Test
    void unreachableClientReportsError() throws InterruptedException {
        VerifoneTerminalClient client = new VerifoneTerminalClient(new StubTransport(false), TestMessages.french());
        assertEquals("CLIENT MONETIQUE INJOIGNABLE", awaitError(client, "10.00"));
    }

    /**
     * A reachable client still ends on the not-implemented error leg until
     * the protocol specification is available (UnsupportedOperation arm).
     *
     * @throws InterruptedException never under the test timeout
     */
    @Test
    void reachableClientReportsNotImplemented() throws InterruptedException {
        VerifoneTerminalClient client = new VerifoneTerminalClient(new StubTransport(true), TestMessages.french());
        assertEquals("MONETIQUE VERIFONE NON DISPONIBLE - SPECIFICATION PROTOCOLE REQUISE", awaitError(client, "10.00"));
    }

    /**
     * The credit path shares the exchange discipline (same error leg).
     *
     * @throws InterruptedException never under the test timeout
     */
    @Test
    void creditSharesExchangeDiscipline() throws InterruptedException {
        VerifoneTerminalClient client = new VerifoneTerminalClient(new StubTransport(false), TestMessages.french());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        client.requestCredit(new BigDecimal("3.00"), new TerminalTransactionCallback() {
            /**
             * Unexpected leg.
             *
             * @param outcome the unexpected outcome
             */
            @Override
            public void onAccepted(TerminalOutcome outcome) {
                latch.countDown();
            }

            /**
             * Unexpected leg.
             *
             * @param outcome the unexpected outcome
             */
            @Override
            public void onRefused(TerminalOutcome outcome) {
                latch.countDown();
            }

            /**
             * Expected error leg.
             *
             * @param message the operator-facing message
             */
            @Override
            public void onError(String message) {
                error.set(message);
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("CLIENT MONETIQUE INJOIGNABLE", error.get());
    }

    /**
     * The session hooks are inert until the specification arrives, and the
     * name identifies the implementation; abort with nothing in flight is a
     * no-op.
     */
    @Test
    void hooksNameAndIdleAbortAreInert() {
        VerifoneTerminalClient client = new VerifoneTerminalClient(new StubTransport(true), TestMessages.french());
        client.onRegisterOpened();
        client.onRegisterClosed();
        client.abort();
        assertEquals("verifone", client.name());
    }

    /**
     * A reachable client whose transport returns a normal response frame runs
     * the response-mapping leg ({@code handleResponse}, L161-162, L205-211):
     * the frame is decoded and the outcome built, yet the exchange still ends
     * on the not-implemented error leg because the decision tags are not yet
     * specified. A Mockito mock returns the frame so no real socket is opened.
     *
     * @throws InterruptedException never under the test timeout
     * @throws IOException never — the mock returns a frame instead of failing
     */
    @Test
    void reachableClientMappingResponseReportsNotImplemented()
            throws InterruptedException, IOException {
        VerifoneTransport transport = Mockito.mock(VerifoneTransport.class);
        Mockito.when(transport.isReachable()).thenReturn(true);
        Mockito.when(transport.exchange(ArgumentMatchers.anyString())).thenReturn("{D16}72");
        VerifoneTerminalClient client = new VerifoneTerminalClient(transport, TestMessages.french());
        assertEquals("MONETIQUE VERIFONE NON DISPONIBLE - SPECIFICATION PROTOCOLE REQUISE", awaitError(client, "12.00"));
    }

    /**
     * A reachable client whose transport fails with a non-Unsupported
     * exception takes the generic catch leg (L166-168) and reports the
     * unreachable message rather than the not-implemented one. A Mockito mock
     * throws a runtime exception from {@code exchange} so no real socket is
     * opened.
     *
     * @throws InterruptedException never under the test timeout
     * @throws IOException never — the mock throws a runtime exception instead
     */
    @Test
    void genericExchangeFailureReportsUnreachable()
            throws InterruptedException, IOException {
        VerifoneTransport transport = Mockito.mock(VerifoneTransport.class);
        Mockito.when(transport.isReachable()).thenReturn(true);
        Mockito.when(transport.exchange(ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("boom"));
        VerifoneTerminalClient client = new VerifoneTerminalClient(transport, TestMessages.french());
        assertEquals("CLIENT MONETIQUE INJOIGNABLE", awaitError(client, "8.00"));
    }

    /**
     * A second exchange requested while one is in flight is rejected
     * synchronously with the already-in-course message (the busy-CAS true arm
     * on L150-152): the first debit parks the exchange thread with
     * {@code busy == true}, so the second CAS from the calling thread fails.
     *
     * @throws InterruptedException never under the test timeout
     */
    @Test
    void secondExchangeWhileBusyIsRejected() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        VerifoneTerminalClient client = new VerifoneTerminalClient(blockingTransport(entered, release), TestMessages.french());
        LatchingCallback first = new LatchingCallback();
        client.requestDebit(new BigDecimal("5.00"), first);
        assertTrue(entered.await(5, TimeUnit.SECONDS), "exchange never started");
        LatchingCallback second = new LatchingCallback();
        client.requestDebit(new BigDecimal("7.00"), second);
        assertTrue(second.latch.await(5, TimeUnit.SECONDS), "rejection never fired");
        assertEquals("TRANSACTION MONETIQUE DEJA EN COURS", second.error.get());
        release.countDown();
        assertTrue(first.latch.await(5, TimeUnit.SECONDS), "first exchange never ended");
        assertEquals("CLIENT MONETIQUE INJOIGNABLE", first.error.get());
    }

    /**
     * An abort issued while a transaction is in flight takes the busy true arm
     * on L103 and only flags the exchange (a no-op observable outcome): the
     * parked first debit keeps {@code busy == true}, so {@code abort()} enters
     * the guard, and the exchange still ends on the unreachable leg.
     *
     * @throws InterruptedException never under the test timeout
     */
    @Test
    void abortWhileBusyFlagsInFlightExchange() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        VerifoneTerminalClient client = new VerifoneTerminalClient(blockingTransport(entered, release), TestMessages.french());
        LatchingCallback callback = new LatchingCallback();
        client.requestDebit(new BigDecimal("9.00"), callback);
        assertTrue(entered.await(5, TimeUnit.SECONDS), "exchange never started");
        client.abort();
        release.countDown();
        assertTrue(callback.latch.await(5, TimeUnit.SECONDS), "exchange never ended");
        assertEquals("CLIENT MONETIQUE INJOIGNABLE", callback.error.get());
    }

    /**
     * EXP-54: {@code mapEntryMode} maps a contactless entry-mode tag to
     * {@code "SANS_CONTACT"} (each known contactless code).
     */
    @Test
    void mapEntryModeMapsContactlessCodes() {
        assertEquals("SANS_CONTACT", VerifoneTerminalClient.mapEntryMode("C"));
        assertEquals("SANS_CONTACT", VerifoneTerminalClient.mapEntryMode("ctls"));
        assertEquals("SANS_CONTACT", VerifoneTerminalClient.mapEntryMode("NFC"));
    }

    /**
     * EXP-54: {@code mapEntryMode} maps a chip-and-PIN entry-mode tag to
     * {@code "CODE"} (each known PIN code).
     */
    @Test
    void mapEntryModeMapsPinCodes() {
        assertEquals("CODE", VerifoneTerminalClient.mapEntryMode("P"));
        assertEquals("CODE", VerifoneTerminalClient.mapEntryMode("pin"));
        assertEquals("CODE", VerifoneTerminalClient.mapEntryMode("CHIP"));
    }

    /**
     * EXP-54: {@code mapEntryMode} returns null for a null tag (not
     * discriminated) and for an unknown tag value.
     */
    @Test
    void mapEntryModeReturnsNullWhenNullOrUnknown() {
        org.junit.jupiter.api.Assertions.assertNull(VerifoneTerminalClient.mapEntryMode(null));
        org.junit.jupiter.api.Assertions.assertNull(VerifoneTerminalClient.mapEntryMode("ZZ"));
    }

    /**
     * BO-03-09-31: the administered Label Point De Vente is appended to the
     * request VERBATIM when its activation key is on, and nothing is appended
     * when the key is off — the frame is then the one the client built before
     * the key existed (both arms of the activation guard).
     */
    @Test
    void theAdministeredPdvLabelTravelsOnlyWhenActivated() {
        com.intermarche.pos.service.PosSettingsService settings =
                Mockito.mock(com.intermarche.pos.service.PosSettingsService.class);
        Mockito.when(settings.terminalPdvLabelEnabled()).thenReturn(true);
        Mockito.when(settings.terminalPdvLabel()).thenReturn("  {LPDV}MAGASIN 34  ");
        VerifoneTerminalClient on = new VerifoneTerminalClient(
                new StubTransport(true), TestMessages.french(), settings);
        assertTrue(on.buildRequest("debit", new BigDecimal("12.00")).endsWith("{LPDV}MAGASIN 34"));

        Mockito.when(settings.terminalPdvLabelEnabled()).thenReturn(false);
        VerifoneTerminalClient off = new VerifoneTerminalClient(
                new StubTransport(true), TestMessages.french(), settings);
        org.junit.jupiter.api.Assertions.assertFalse(
                off.buildRequest("debit", new BigDecimal("12.00")).contains("LPDV"));
    }

    /**
     * An activation with NOTHING administered appends nothing: an empty label
     * is not a label. A client built without the administered parameters at all
     * behaves the same way (the null-service and the blank-label legs).
     */
    @Test
    void anEmptyPdvLabelAppendsNothing() {
        com.intermarche.pos.service.PosSettingsService settings =
                Mockito.mock(com.intermarche.pos.service.PosSettingsService.class);
        Mockito.when(settings.terminalPdvLabelEnabled()).thenReturn(true);
        Mockito.when(settings.terminalPdvLabel()).thenReturn("   ");
        VerifoneTerminalClient blank = new VerifoneTerminalClient(
                new StubTransport(true), TestMessages.french(), settings);
        String blankFrame = blank.buildRequest("debit", new BigDecimal("12.00"));

        Mockito.when(settings.terminalPdvLabel()).thenReturn(null);
        VerifoneTerminalClient nullLabel = new VerifoneTerminalClient(
                new StubTransport(true), TestMessages.french(), settings);

        VerifoneTerminalClient noSettings = new VerifoneTerminalClient(
                new StubTransport(true), TestMessages.french());
        String bareFrame = noSettings.buildRequest("debit", new BigDecimal("12.00"));
        assertEquals(bareFrame, blankFrame);
        assertEquals(bareFrame, nullLabel.buildRequest("debit", new BigDecimal("12.00")));
    }

    /**
     * BO-03-09-30: the VIC product codes of the tenders in service are appended
     * verbatim, in administered order, parsed with the very codec the wire uses
     * — nothing here composes a tag.
     */
    @Test
    void theAdministeredVicProductCodesTravelVerbatim() {
        com.intermarche.pos.domain.payment.TenderDefinition mealpass =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        mealpass.code = "TR";
        mealpass.active = true;
        mealpass.vicProductCode = "{DAJ}01";
        com.intermarche.pos.domain.payment.TenderDefinition giftpass =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        giftpass.code = "VOUCHER";
        giftpass.active = true;
        giftpass.vicProductCode = "{DCJ}01";
        VerifoneTerminalClient client = new VerifoneTerminalClient(
                new StubTransport(true), TestMessages.french(), null);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> ms =
                     Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            ms.when(() -> com.intermarche.pos.domain.payment.TenderDefinition
                            .list("order by displayOrder, code"))
                    .thenReturn(java.util.List.of(mealpass, giftpass));
            String frame = client.buildRequest("debit", new BigDecimal("12.00"));
            assertTrue(frame.endsWith("{DAJ}01{DCJ}01"), frame);
        }
    }

    /**
     * A referential the client cannot reach costs the customer nothing: the
     * codes are an addition to the request, never a condition of it (the catch
     * arm — no static mock here, so the finder raises).
     */
    @Test
    void anUnreachableReferentialDoesNotStopTheRequest() {
        VerifoneTerminalClient client = new VerifoneTerminalClient(
                new StubTransport(true), TestMessages.french(), null);
        String frame = client.buildRequest("debit", new BigDecimal("12.00"));
        assertTrue(frame.contains("AMOUNT-PLACEHOLDER"), frame);
    }
}
