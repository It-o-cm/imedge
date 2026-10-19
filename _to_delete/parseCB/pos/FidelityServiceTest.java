package com.intermarche.pos.ui.fidelity;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FidelityService}.
 * <p>
 * The service is a two-line delegator: it forwards the card to the
 * {@link FidelityState#assignCard(String)} of a mocked {@link PosState} and then
 * wakes the polling via {@link PosState#touch()}. The method is branch-free, so
 * these tests assert exact delegation, the invocation order of the two calls, and
 * that a {@code null} card is forwarded verbatim rather than filtered.
 */
class FidelityServiceTest {

    /**
     * Builds a {@link PosState} mock whose public {@code fidelity} field is itself
     * a mock, so both collaborators can be verified independently.
     *
     * @return a mocked state carrying a mocked {@link FidelityState}
     */
    private PosState newState() {
        PosState state = mock(PosState.class);
        state.fidelity = mock(FidelityState.class);
        // Attaching a card REVALUES a non-empty cart (the engine request must
        // carry the customerCode, and the earn projection must be fed a
        // couple that SEES the card). The ticket sub-state is therefore read
        // on every attachment: a real, EMPTY one keeps these tests on the
        // "nothing to revalue" arm, with no TicketService needed.
        state.ticket = new com.intermarche.pos.ui.ticket.TicketState();
        return state;
    }

    /**
     * A non-null card is assigned to the fidelity state and the polling is woken,
     * in that order, with no other interaction on the state.
     */
    @Test
    void validateCardAssignsCardThenTouches() {
        FidelityService service = new FidelityService();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        PosState state = newState();
        service.validateCard(state, "1234567890123");
        InOrder order = inOrder(state.fidelity, state);
        order.verify(state.fidelity).assignCard("1234567890123");
        order.verify(state).touch();
        verifyNoMoreInteractions(state.fidelity);
    }

    /**
     * LC-03-04-08: confirming the proposal walks the parked card through the
     * ONE attachment entry point — exactly as if it had been scanned on an
     * empty ticket — and the proposal is consumed whatever happens.
     */
    @Test
    void confirmReplacementWalksTheParkedCardThroughValidateCard() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        PosState state = newState();
        state.fidelity.pendingReplacementCard = "2990000000019";
        service.confirmReplacement(state);
        verify(state.fidelity).assignCard("2990000000019");
        verify(state).touch();
        assertNull(state.fidelity.pendingReplacementCard);
    }

    /**
     * LC-03-04-08: keeping the attached card drops the parked replacement and
     * touches the state so the proposal leaves the screen — the first holder
     * keeps their card, their advantages and their earn.
     */
    @Test
    void keepReplacementDropsTheProposalAndKeepsTheAttachedCard() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        PosState state = newState();
        state.fidelity.pendingReplacementCard = "2990000000019";
        service.keepReplacement(state);
        verify(state.fidelity, never()).assignCard(any());
        assertNull(state.fidelity.pendingReplacementCard);
        verify(state).touch();
    }

    /**
     * LC-03-04-08: confirming with NO proposal parked is a no-op — a stale
     * page or a double tap attaches nothing and wakes nothing.
     */
    @Test
    void confirmReplacementWithoutAProposalIsANoOp() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        PosState state = newState();
        service.confirmReplacement(state);
        verify(state.fidelity, never()).assignCard(any());
        verify(state, never()).touch();
    }

    /**
     * A {@code null} card is forwarded verbatim to {@code assignCard}; the service
     * applies no guard, so the touch still fires.
     */
    @Test
    void validateCardForwardsNullCardVerbatim() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        PosState state = newState();
        service.validateCard(state, null);
        verify(state.fidelity).assignCard(null);
        verify(state).touch();
        verifyNoMoreInteractions(state.fidelity);
    }

    /**
     * The verified {@code fidelity} target is the exact instance held by the state,
     * documenting that the service reads {@code state.fidelity} rather than caching
     * its own reference.
     */
    @Test
    void validateCardUsesStatesOwnFidelityInstance() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        PosState state = newState();
        FidelityState fidelity = state.fidelity;
        service.validateCard(state, "42");
        assertSame(fidelity, state.fidelity);
        verify(fidelity).assignCard("42");
    }

    // --- reserveLease ---

    /**
     * Wires a service with a mocked imfid client on a real, card-bearing
     * state.
     *
     * @param card the attached card number
     * @return the service under test
     */
    private FidelityService serviceWithCard(String card) {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(true);
        service.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        return service;
    }

    /**
     * Builds a real state carrying an attached card.
     *
     * @param card the card number
     * @return the wired state
     */
    private com.intermarche.pos.ui.PosState stateWithCard(String card) {
        com.intermarche.pos.ui.PosState state = new com.intermarche.pos.ui.PosState();
        state.fidelity.assignCard(card);
        return state;
    }

    /**
     * Builds an account with the given available balance.
     *
     * @param status the account status
     * @param balance the book balance
     * @param available the available balance (net of leases)
     * @return the account
     */
    private ImfidClient.AccountInfo account(String status, String balance, String available) {
        ImfidClient.AccountInfo account = new ImfidClient.AccountInfo();
        account.status = status;
        account.balance = new BigDecimal(balance);
        account.availableBalance = new BigDecimal(available);
        return account;
    }

    /**
     * Builds a confirmation result carrying a status and an optional ledger
     * balance after the burn (imfid response B).
     *
     * @param status the HTTP status
     * @param balanceAfter the ledger balance after the burn, or null
     * @return the confirmation result
     */
    private ImfidClient.ConfirmResult confirmResult(int status, String balanceAfter) {
        ImfidClient.ConfirmResult result = new ImfidClient.ConfirmResult();
        result.httpStatus = status;
        result.balanceAfter = balanceAfter == null ? null : new BigDecimal(balanceAfter);
        return result;
    }

    /**
     * Builds a granted reservation result.
     *
     * @param id the lease id
     * @return the result
     */
    private ImfidClient.ReservationResult granted(long id) {
        ImfidClient.ReservationResult result = new ImfidClient.ReservationResult();
        result.httpStatus = 201;
        result.reservationId = id;
        return result;
    }

    /**
     * Builds a refusal result.
     *
     * @param status the HTTP status
     * @param reason the refusal reason, or null
     * @return the result
     */
    private ImfidClient.ReservationResult refused(int status, String reason) {
        ImfidClient.ReservationResult result = new ImfidClient.ReservationResult();
        result.httpStatus = status;
        result.reason = reason;
        return result;
    }

    /**
     * NO card attached: the lease is refused before any network call — you
     * cannot reserve against a customer you have not identified.
     */
    @Test
    void reserveLeaseRefusesWithoutCard() {
        FidelityService service = serviceWithCard(null);
        com.intermarche.pos.ui.PosState state = new com.intermarche.pos.ui.PosState();
        FidelityService.BurnVerdict verdict =
                service.reserveLease(state, BigDecimal.TEN, new BigDecimal("20.00"));
        assertEquals("CARTE FIDÉLITÉ REQUISE", verdict.refusalMessage);
        verifyNoInteractions(service.imfidClient);
    }

    /**
     * Attaching a card reads the holder's immediate-discount promotions and
     * keeps them on the state, ready for the valuation requests of the sale.
     *
     * @throws Exception when the mocked client declares one
     */
    @Test
    void validateCardLoadsTheCardPromotions() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        com.intermarche.pos.ui.PosState state = new com.intermarche.pos.ui.PosState();
        ImfidClient.CardPromotion promotion = new ImfidClient.CardPromotion();
        promotion.ean = "3000000000018";
        promotion.promotionType = "AMOUNT";
        promotion.value = new BigDecimal("0.50");
        promotion.benefit = "IMMEDIATE_DISCOUNT";
        when(service.imfidClient.promotions("2990000000019"))
                .thenReturn(new java.util.ArrayList<>(java.util.List.of(promotion)));

        service.validateCard(state, "2990000000019");

        assertEquals(1, state.fidelity.cardPromotions.size());
        assertEquals("3000000000018", state.fidelity.cardPromotions.get(0).ean);
    }

    /**
     * An imfid that fails leaves the promotion list EMPTY and the sale goes
     * on: a personal discount is worth losing, a blocked register is not.
     *
     * @throws Exception when the mocked client declares one
     */
    @Test
    void validateCardKeepsSellingWhenThePromotionsCannotBeRead() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        com.intermarche.pos.ui.PosState state = new com.intermarche.pos.ui.PosState();
        when(service.imfidClient.promotions("2990000000019"))
                .thenThrow(new ImfidUnavailableException("/api/accounts", 503));

        service.validateCard(state, "2990000000019");

        assertTrue(state.fidelity.active);
        assertTrue(state.fidelity.cardPromotions.isEmpty());
    }

    /**
     * An UNCONFIGURED loyalty service is never called for promotions either:
     * a register deployed without imfid prices its baskets exactly as it did
     * before card promotions existed.
     *
     * @throws Exception when the mocked client declares one
     */
    @Test
    void validateCardReadsNoPromotionWhenImfidIsNotConfigured() throws Exception {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(false);
        com.intermarche.pos.ui.PosState state = new com.intermarche.pos.ui.PosState();

        service.validateCard(state, "2990000000019");

        verify(service.imfidClient, never()).promotions(any());
        assertTrue(state.fidelity.cardPromotions.isEmpty());
    }

    /**
     * An UNCONFIGURED loyalty service refuses the payment: the register does
     * not debit a balance it cannot read.
     */
    @Test
    void reserveLeaseRefusesWhenNotConfigured() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(false);
        FidelityService.BurnVerdict verdict = service.reserveLease(
                stateWithCard("2990000000019"), BigDecimal.TEN, new BigDecimal("20.00"));
        assertEquals("SERVICE FIDÉLITÉ INDISPONIBLE", verdict.refusalMessage);
    }

    /**
     * An UNKNOWN card is refused at the account read, before any reservation.
     */
    @Test
    void reserveLeaseRefusesUnknownCard() throws Exception {
        FidelityService service = serviceWithCard("2990000000404");
        when(service.imfidClient.account(any())).thenReturn(null);
        FidelityService.BurnVerdict verdict = service.reserveLease(
                stateWithCard("2990000000404"), BigDecimal.TEN, new BigDecimal("20.00"));
        assertEquals("CARTE FIDÉLITÉ INCONNUE", verdict.refusalMessage);
    }

    /**
     * The POS-side cap is the MINIMUM of the remaining due, the burnable base
     * and the available balance — asking for more than any of them grants
     * only the smallest.
     */
    @Test
    void reserveLeaseCapsAtTheSmallestOfTheThreeLimits() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        com.intermarche.pos.ui.PosState state = stateWithCard("2990000000019");
        state.fidelity.burnableBase = new BigDecimal("8.00");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "5.00"));
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(granted(77L));

        FidelityService.BurnVerdict verdict =
                service.reserveLease(state, new BigDecimal("50.00"), new BigDecimal("20.00"));

        assertEquals(0, new BigDecimal("5.00").compareTo(verdict.grantedAmount));
        verify(service.imfidClient).reserve(eq("2990000000019"), eq(new BigDecimal("5.00")), any());
        assertEquals(77L, state.payment.fidReservationId);
    }

    /**
     * A NULL requested amount means "as much as possible": the cap itself is
     * reserved.
     */
    @Test
    void reserveLeaseWithoutRequestedAmountTakesTheWholeCap() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        com.intermarche.pos.ui.PosState state = stateWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "12.00"));
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(granted(77L));

        FidelityService.BurnVerdict verdict =
                service.reserveLease(state, null, new BigDecimal("20.00"));

        assertEquals(0, new BigDecimal("12.00").compareTo(verdict.grantedAmount));
    }

    /**
     * A cap that falls to ZERO (nothing left to pay, or an empty available
     * balance) refuses with its own message rather than reserving nothing.
     */
    @Test
    void reserveLeaseRefusesWhenNothingIsUsable() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "0.00"));
        FidelityService.BurnVerdict verdict = service.reserveLease(
                stateWithCard("2990000000019"), BigDecimal.TEN, new BigDecimal("20.00"));
        assertEquals("CAGNOTTE: AUCUN MONTANT UTILISABLE", verdict.refusalMessage);
    }

    /**
     * The three 422 reasons map to the EXACT cashier messages of the closed
     * nomenclature — the wording is a contract with the demo script and the
     * store staff, not a free-form log line.
     */
    @Test
    void reserveLeaseMapsTheClosedRefusalNomenclature() throws Exception {
        String[][] cases = {
                {"INSUFFICIENT_BALANCE", "SOLDE CAGNOTTE INSUFFISANT"},
                {"DAILY_RULE", "CAGNOTTE DÉJÀ UTILISÉE AUJOURD'HUI"},
                {"ACCOUNT_STATUS", "COMPTE FIDÉLITÉ INACTIF"},
                {"SOMETHING_NEW", "PAIEMENT FIDÉLITÉ REFUSÉ"},
                {null, "PAIEMENT FIDÉLITÉ REFUSÉ"}};
        for (String[] testCase : cases) {
            FidelityService service = serviceWithCard("2990000000019");
            when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "50.00"));
            when(service.imfidClient.reserve(any(), any(), any()))
                    .thenReturn(refused(422, testCase[0]));
            FidelityService.BurnVerdict verdict = service.reserveLease(
                    stateWithCard("2990000000019"), BigDecimal.TEN, new BigDecimal("20.00"));
            assertEquals(testCase[1], verdict.refusalMessage,
                    "reason " + testCase[0] + " doit donner ce message");
        }
    }

    /**
     * A 404 at reservation time (the card vanished between the two calls) and
     * a 409 (lease held by another register) have their own messages.
     */
    @Test
    void reserveLeaseMapsUnknownCardAndBusyLease() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "50.00"));
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(refused(409, null));
        assertEquals("CAGNOTTE RÉSERVÉE SUR UNE AUTRE CAISSE", service.reserveLease(
                stateWithCard("2990000000019"), BigDecimal.TEN, new BigDecimal("20.00"))
                .refusalMessage);

        FidelityService other = serviceWithCard("2990000000019");
        when(other.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "50.00"));
        when(other.imfidClient.reserve(any(), any(), any())).thenReturn(refused(404, null));
        assertEquals("CARTE FIDÉLITÉ INCONNUE", other.reserveLease(
                stateWithCard("2990000000019"), BigDecimal.TEN, new BigDecimal("20.00"))
                .refusalMessage);
    }

    /**
     * A TRANSPORT failure opens the breaker: the payment is refused, and the
     * NEXT attempt is refused too WITHOUT calling imfid again — a dead
     * service never taxes every gesture with its timeout.
     */
    @Test
    void reserveLeaseTransportFailureOpensTheBreaker() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenThrow(new java.io.IOException("down"));

        assertEquals("SERVICE FIDÉLITÉ INDISPONIBLE", service.reserveLease(
                stateWithCard("2990000000019"), BigDecimal.TEN, new BigDecimal("20.00"))
                .refusalMessage);
        assertEquals("SERVICE FIDÉLITÉ INDISPONIBLE", service.reserveLease(
                stateWithCard("2990000000019"), BigDecimal.TEN, new BigDecimal("20.00"))
                .refusalMessage);
        verify(service.imfidClient, times(1)).account(any());
    }

    // --- releaseLease / confirmLease ---

    /**
     * Releasing with NO lease is a no-op: nothing to give back.
     */
    @Test
    void releaseLeaseWithoutLeaseIsNoOp() {
        FidelityService service = serviceWithCard("2990000000019");
        service.releaseLease(new com.intermarche.pos.ui.PosState());
        verify(service.imfidClient, never()).release(anyLong());
    }

    /**
     * Releasing hands the lease back and FORGETS it locally, so a second
     * cancellation cannot release it twice.
     */
    @Test
    void releaseLeaseGivesItBackAndForgetsIt() {
        FidelityService service = serviceWithCard("2990000000019");
        com.intermarche.pos.ui.PosState state = new com.intermarche.pos.ui.PosState();
        state.payment.fidReservationId = 77L;
        service.releaseLease(state);
        verify(service.imfidClient).release(77L);
        assertNull(state.payment.fidReservationId);
    }

    /**
     * Confirming with NO lease returns null and calls nothing — a sale paid
     * without the cagnotte carries no reservation id in its event.
     */
    @Test
    void confirmLeaseWithoutLeaseReturnsNull() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        assertNull(service.confirmLease(new com.intermarche.pos.ui.PosState(),
                java.time.LocalDate.of(2026, 8, 12)));
        verify(service.imfidClient, never()).confirm(anyLong(), any());
    }

    /**
     * An EXPIRED lease (410) NEVER blocks the closing: the reservation id is
     * still returned so the ticket-closed event carries it, and imfid's
     * ingestion — the authority — creates the BURN with its warning.
     */
    @Test
    void confirmLeaseToleratesAnExpiredLease() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        com.intermarche.pos.ui.PosState state = new com.intermarche.pos.ui.PosState();
        state.payment.fidReservationId = 77L;
        when(service.imfidClient.confirm(77L, "2026-08-12")).thenReturn(confirmResult(410, null));
        assertEquals(77L, service.confirmLease(state, java.time.LocalDate.of(2026, 8, 12)));
    }

    /**
     * An UNREACHABLE imfid at the fiscal moment does not block either: the id
     * travels, the ingestion decides.
     */
    @Test
    void confirmLeaseSurvivesAnUnreachableService() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        com.intermarche.pos.ui.PosState state = new com.intermarche.pos.ui.PosState();
        state.payment.fidReservationId = 77L;
        when(service.imfidClient.confirm(anyLong(), any())).thenThrow(new java.io.IOException("down"));
        assertEquals(77L, service.confirmLease(state, java.time.LocalDate.of(2026, 8, 12)));
    }

    // --- buildTicketClosedPayload ---

    /**
     * With NO valuation couple there is no payload at all: imfid recomputes
     * from the engine's own answer, so an event without it would be useless.
     */
    @Test
    void buildTicketClosedPayloadIsNullWithoutCouple() {
        FidelityService service = serviceWithCard("2990000000019");
        assertNull(service.buildTicketClosedPayload(new com.intermarche.pos.ui.PosState(),
                "2026-C04-000001", "2990000000019", java.time.LocalDate.of(2026, 8, 12), 77L));
    }

    /**
     * The payload embeds the couple VERBATIM and the displayed earn as a
     * trace, plus the reservation id that links the close to its lease.
     */
    @Test
    void buildTicketClosedPayloadEmbedsCoupleAndDisplayedEarn() {
        FidelityService service = serviceWithCard("2990000000019");
        com.intermarche.pos.ui.PosState state = stateWithCard("2990000000019");
        state.fidelity.lastValuationRequestJson = "{\"lines\":[]}";
        state.fidelity.lastValuationResponseJson = "{\"total\":42.00}";
        state.fidelity.earnEntries.add(
                new FidelityState.EarnLine("SOCLE", "Socle", new BigDecimal("0.28")));
        state.fidelity.earnEntries.add(
                new FidelityState.EarnLine("F&L", "Fruits", new BigDecimal("0.75")));

        String payload = service.buildTicketClosedPayload(state, "2026-C04-000001",
                "2990000000019", java.time.LocalDate.of(2026, 8, 12), 77L);

        assertTrue(payload.contains("\"valuationRequest\":{\"lines\":[]}"));
        assertTrue(payload.contains("\"valuationResponse\":{\"total\":42.00}"));
        assertTrue(payload.contains("\"ticketRef\":\"2026-C04-000001\""));
        assertTrue(payload.contains("\"fiscalDate\":\"2026-08-12\""));
        assertTrue(payload.contains("\"reservationId\":77"));
        assertTrue(payload.contains("{\"ruleCode\":\"SOCLE\",\"amount\":0.28}"));
        assertTrue(payload.contains("},{"), "les lignes d'earn doivent être séparées: " + payload);
    }

    /**
     * BO-03-10-08: when the island administers a promotion channel it rides the
     * ticket-closed payload as a {@code channel} member.
     */
    @Test
    void buildTicketClosedPayloadEmbedsThePromotionChannelWhenAdministered() {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.posSettingsService.islandPromotionChannel()).thenReturn("PDV");
        com.intermarche.pos.ui.PosState state = stateWithCard("2990000000019");
        state.fidelity.lastValuationRequestJson = "{}";
        state.fidelity.lastValuationResponseJson = "{}";
        String payload = service.buildTicketClosedPayload(state, "2026-C04-000001",
                "2990000000019", java.time.LocalDate.of(2026, 8, 12), null);
        assertTrue(payload.contains("\"channel\":\"PDV\""), "canal attendu: " + payload);
    }

    /**
     * BO-03-10-08: an empty channel adds NO member — the payload stays identical
     * to the byte to what it was before the option existed (the compat leg).
     */
    @Test
    void buildTicketClosedPayloadOmitsTheChannelWhenBlank() {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.posSettingsService.islandPromotionChannel()).thenReturn("");
        com.intermarche.pos.ui.PosState state = stateWithCard("2990000000019");
        state.fidelity.lastValuationRequestJson = "{}";
        state.fidelity.lastValuationResponseJson = "{}";
        String payload = service.buildTicketClosedPayload(state, "2026-C04-000001",
                "2990000000019", java.time.LocalDate.of(2026, 8, 12), null);
        assertFalse(payload.contains("channel"), "aucun canal ne doit apparaître: " + payload);
    }

    /**
     * WITHOUT a lease, no {@code reservationId} is written: a sale paid
     * entirely in cash still declares its earn, but claims no burn.
     */
    @Test
    void buildTicketClosedPayloadOmitsTheReservationWhenThereIsNoLease() {
        FidelityService service = serviceWithCard("2990000000019");
        com.intermarche.pos.ui.PosState state = stateWithCard("2990000000019");
        state.fidelity.lastValuationRequestJson = "{}";
        state.fidelity.lastValuationResponseJson = "{}";

        String payload = service.buildTicketClosedPayload(state, "2026-C04-000001",
                "2990000000019", java.time.LocalDate.of(2026, 8, 12), null);

        assertFalse(payload.contains("reservationId"));
        assertTrue(payload.contains("\"displayedEarn\":[]"));
    }

    // --- loadConsultation ---

    /**
     * With no card attached, the consultation shows its invitation rather
     * than an empty panel.
     */
    @Test
    void loadConsultationWithoutCardInvitesToScan() {
        FidelityService service = serviceWithCard("2990000000019");
        FidelityService.Consultation view =
                service.loadConsultation(new com.intermarche.pos.ui.PosState());
        assertEquals("AUCUNE CARTE ATTACHÉE — SCANNEZ OU SAISISSEZ LA CARTE", view.message);
        assertFalse(view.hasAccount());
    }

    /**
     * The three imfid statuses are translated into the register's French
     * vocabulary; an unknown status passes through untouched rather than
     * disappearing.
     */
    @Test
    void loadConsultationTranslatesTheAccountStatus() throws Exception {
        String[][] cases = {{"ACTIVE", "ACTIF"}, {"PENDING_ACTIVATION", "EN ATTENTE D'ACTIVATION"},
                {"RESILIATED", "RÉSILIÉ"}, {"FUTURE_STATUS", "FUTURE_STATUS"}};
        for (String[] testCase : cases) {
            FidelityService service = serviceWithCard("2990000000019");
            when(service.imfidClient.account(any()))
                    .thenReturn(account(testCase[0], "56.70", "46.70"));
            when(service.imfidClient.movements(any(), anyInt(), anyInt())).thenReturn(null);
            FidelityService.Consultation view =
                    service.loadConsultation(stateWithCard("2990000000019"));
            assertEquals(testCase[1], view.status);
        }
    }

    /**
     * The movement types are translated once, server-side, into the labels
     * the panel displays — the template never maps a nomenclature.
     */
    @Test
    void loadConsultationTranslatesMovementTypes() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "46.70"));
        ImfidClient.MovementsPage page = new ImfidClient.MovementsPage();
        page.items.add(movement("EARN", "1.03", "2026-08-12", "SOCLE"));
        page.items.add(movement("BURN", "-10.00", "2026-08-12", null));
        page.items.add(movement("RETURN_DEBIT", "-2.00", "2026-08-12", null));
        page.items.add(movement("FUTURE_TYPE", "1.00", "2026-08-12", null));
        when(service.imfidClient.movements(any(), anyInt(), anyInt())).thenReturn(page);

        FidelityService.Consultation view =
                service.loadConsultation(stateWithCard("2990000000019"));

        assertTrue(view.hasAccount());
        assertEquals(4, view.rows.size());
        assertEquals("CAGNOTTE (SOCLE)", view.rows.get(0)[1]);
        assertEquals("+1.03 €", view.rows.get(0)[2]);
        assertEquals("UTILISATION EN CAISSE", view.rows.get(1)[1]);
        assertEquals("-10.00 €", view.rows.get(1)[2]);
        assertEquals("REPRISE SUR RETOUR", view.rows.get(2)[1]);
        assertEquals("FUTURE_TYPE", view.rows.get(3)[1]);
    }

    /**
     * Builds a movement row.
     *
     * @param type the movement type
     * @param amount the signed amount
     * @param fiscalDate the fiscal date, or null
     * @param ruleCode the rule code, or null
     * @return the movement
     */
    private ImfidClient.Movement movement(String type, String amount, String fiscalDate,
                                          String ruleCode) {
        ImfidClient.Movement movement = new ImfidClient.Movement();
        movement.type = type;
        movement.amount = new BigDecimal(amount);
        movement.fiscalDate = fiscalDate;
        movement.ruleCode = ruleCode;
        return movement;
    }

    /**
     * An UNKNOWN card shows its message instead of an error page.
     */
    @Test
    void loadConsultationOnUnknownCardShowsAMessage() throws Exception {
        FidelityService service = serviceWithCard("2990000000404");
        when(service.imfidClient.account(any())).thenReturn(null);
        FidelityService.Consultation view =
                service.loadConsultation(stateWithCard("2990000000404"));
        assertEquals("CARTE FIDÉLITÉ INCONNUE", view.message);
        assertFalse(view.hasAccount());
    }

    /**
     * A DEAD imfid degrades to a message — never an error page, and the
     * breaker opens so the next screen refresh does not hang again.
     */
    @Test
    void loadConsultationDegradesWhenTheServiceIsDown() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenThrow(new java.io.IOException("down"));
        FidelityService.Consultation view =
                service.loadConsultation(stateWithCard("2990000000019"));
        assertEquals("SERVICE FIDÉLITÉ INDISPONIBLE", view.message);
        assertFalse(view.hasAccount());
    }

    // --- onValuation / onValuationUnavailable ---

    /**
     * The valuation hook stores the couple VERBATIM — the two raw strings are
     * kept as given, since imfid replays the engine's own answer.
     */
    @Test
    void onValuationStoresTheCoupleVerbatim() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        com.intermarche.pos.ui.PosState state = stateWithCard("2990000000019");
        ImfidClient.EarnProjection projection = new ImfidClient.EarnProjection();
        projection.total = new BigDecimal("1.03");
        projection.burnableBase = new BigDecimal("47.11");
        when(service.imfidClient.earn(any(), any())).thenReturn(projection);

        service.onValuation(state, "{\"req\":1}", "{\"resp\":2}");

        assertEquals("{\"req\":1}", state.fidelity.lastValuationRequestJson);
        assertEquals("{\"resp\":2}", state.fidelity.lastValuationResponseJson);
        verify(service.imfidClient).earn("{\"req\":1}", "{\"resp\":2}");
        assertEquals(0, new BigDecimal("1.03").compareTo(state.fidelity.earnTotal));
        assertEquals(0, new BigDecimal("47.11").compareTo(state.fidelity.burnableBase));
    }

    /**
     * A DEAD imfid hides the projection and DROPS nothing else: the sale goes
     * on, the couple stays, only the badge disappears — the degraded display
     * shows itself by showing nothing.
     */
    @Test
    void onValuationHidesTheProjectionWhenImfidIsDown() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        com.intermarche.pos.ui.PosState state = stateWithCard("2990000000019");
        when(service.imfidClient.earn(any(), any())).thenThrow(new java.io.IOException("down"));

        service.onValuation(state, "{}", "{}");

        assertNull(state.fidelity.earnTotal);
        assertTrue(state.fidelity.earnEntries.isEmpty());
        assertTrue(state.fidelity.active);
    }

    /**
     * WITHOUT a card, no projection is even requested: the earn belongs to a
     * customer, and there is none.
     */
    @Test
    void onValuationAsksNothingWithoutACard() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        com.intermarche.pos.ui.PosState state = new com.intermarche.pos.ui.PosState();
        service.onValuation(state, "{}", "{}");
        verify(service.imfidClient, never()).earn(any(), any());
        assertNull(state.fidelity.earnTotal);
    }

    /**
     * A LOCAL or DEGRADED valuation drops the stale couple and clears the
     * display: without a verbatim couple there is nothing honest to project.
     */
    @Test
    void onValuationUnavailableDropsCoupleAndProjection() {
        FidelityService service = serviceWithCard("2990000000019");
        com.intermarche.pos.ui.PosState state = stateWithCard("2990000000019");
        state.fidelity.lastValuationRequestJson = "{}";
        state.fidelity.lastValuationResponseJson = "{}";
        state.fidelity.earnTotal = new BigDecimal("1.03");

        service.onValuationUnavailable(state);

        assertNull(state.fidelity.lastValuationRequestJson);
        assertNull(state.fidelity.lastValuationResponseJson);
        assertNull(state.fidelity.earnTotal);
    }

    /**
     * A movement with a NULL type takes the switch's null-safe SELECTOR and
     * falls through to the default, which hands back the type itself — so the
     * row is listed with a NULL label. What matters here is that one
     * malformed entry does not break the whole consultation: the other
     * movements are still displayed.
     */
    @Test
    void loadConsultationHandlesAMovementWithoutType() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "46.70"));
        ImfidClient.MovementsPage page = new ImfidClient.MovementsPage();
        page.items.add(movement(null, "1.00", "2026-08-12", null));
        when(service.imfidClient.movements(any(), anyInt(), anyInt())).thenReturn(page);

        FidelityService.Consultation view =
                service.loadConsultation(stateWithCard("2990000000019"));

        assertEquals(1, view.rows.size());
        assertNull(view.rows.get(0)[1]);
    }

    /**
     * An account with a NULL status takes the null-safe SELECTOR of the
     * status switch and lands on the default, which hands back the status
     * itself — the panel then shows no status rather than failing. The
     * balance, which is what the cashier actually needs, is still displayed.
     */
    @Test
    void loadConsultationHandlesAnAccountWithoutStatus() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        ImfidClient.AccountInfo account = account("ACTIVE", "56.70", "46.70");
        account.status = null;
        when(service.imfidClient.account(any())).thenReturn(account);
        when(service.imfidClient.movements(any(), anyInt(), anyInt())).thenReturn(null);

        FidelityService.Consultation view =
                service.loadConsultation(stateWithCard("2990000000019"));

        assertNull(view.status);
        assertTrue(view.hasAccount());
        assertEquals(0, new BigDecimal("46.70").compareTo(view.available));
    }

    // --- requested amount legs ---

    /**
     * A requested amount BELOW the cap is granted as asked: the cashier may
     * spend part of the balance and keep the rest for another day.
     */
    @Test
    void reserveLeaseGrantsARequestBelowTheCap() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "50.00"));
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(granted(77L));

        FidelityService.BurnVerdict verdict = service.reserveLease(
                stateWithCard("2990000000019"), new BigDecimal("4.00"), new BigDecimal("20.00"));

        assertEquals(0, new BigDecimal("4.00").compareTo(verdict.grantedAmount));
    }

    /**
     * A ZERO request means "as much as possible" (second leg of the guard,
     * {@code signum() == 0}) — the pay screen sends zero when the cashier
     * validates without typing an amount.
     */
    @Test
    void reserveLeaseTreatsAZeroRequestAsTheWholeCap() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "12.00"));
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(granted(77L));

        FidelityService.BurnVerdict verdict = service.reserveLease(
                stateWithCard("2990000000019"), BigDecimal.ZERO, new BigDecimal("20.00"));

        assertEquals(0, new BigDecimal("12.00").compareTo(verdict.grantedAmount));
    }

    /**
     * A NEGATIVE request is treated the same way (same leg, {@code signum()
     * < 0}): a nonsensical entry can never make the register reserve a
     * negative amount, which imfid would refuse anyway.
     */
    @Test
    void reserveLeaseTreatsANegativeRequestAsTheWholeCap() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "12.00"));
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(granted(77L));

        FidelityService.BurnVerdict verdict = service.reserveLease(
                stateWithCard("2990000000019"), new BigDecimal("-5.00"), new BigDecimal("20.00"));

        assertEquals(0, new BigDecimal("12.00").compareTo(verdict.grantedAmount));
    }

    /**
     * An account WITHOUT an available balance leaves the cap to the other two
     * limits: an imfid that omits the figure must not silently zero the
     * payment, so the remaining due and the burnable base decide alone.
     */
    @Test
    void reserveLeaseCapsWithoutAnAvailableBalance() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        state.fidelity.burnableBase = new BigDecimal("7.00");
        ImfidClient.AccountInfo account = account("ACTIVE", "56.70", "50.00");
        account.availableBalance = null;
        when(service.imfidClient.account(any())).thenReturn(account);
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(granted(77L));

        FidelityService.BurnVerdict verdict =
                service.reserveLease(state, new BigDecimal("50.00"), new BigDecimal("20.00"));

        assertEquals(0, new BigDecimal("7.00").compareTo(verdict.grantedAmount));
    }

    /**
     * With NEITHER a burnable base NOR an available balance, the remaining
     * due is the only cap — the register still lets the customer pay, and
     * imfid remains free to refuse.
     */
    @Test
    void reserveLeaseCapsAtTheRemainingDueAlone() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        ImfidClient.AccountInfo account = account("ACTIVE", "56.70", "50.00");
        account.availableBalance = null;
        when(service.imfidClient.account(any())).thenReturn(account);
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(granted(77L));

        FidelityService.BurnVerdict verdict =
                service.reserveLease(state, null, new BigDecimal("6.00"));

        assertEquals(0, new BigDecimal("6.00").compareTo(verdict.grantedAmount));
    }

    // --- leaseTicketRef ---

    /**
     * The lease reference names the DRAFT: {@code D-<draftId>}. It is the
     * RENEWAL KEY — an identical re-POST under the same reference refreshes
     * the expiry instead of stacking a second lease — which is why it must be
     * stable for the whole sale and derived from the draft, not from the
     * closed ticket (which does not exist yet at reservation time).
     */
    @Test
    void reserveLeaseNamesTheDraftInTheTicketRef() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        state.payment.ticketDbId = 42L;
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "50.00"));
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(granted(77L));

        service.reserveLease(state, BigDecimal.TEN, new BigDecimal("20.00"));

        verify(service.imfidClient).reserve(any(), any(), eq("D-42"));
    }

    /**
     * WITHOUT a draft the reference falls back on {@code D-0} rather than
     * carrying a null: imfid keys its renewals on this string, and a null
     * would break the composed body — the fallback keeps the reservation
     * possible on a sale whose draft is not yet persisted.
     */
    @Test
    void reserveLeaseFallsBackToZeroWhenThereIsNoDraft() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        state.payment.ticketDbId = null;
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "50.00"));
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(granted(77L));

        service.reserveLease(state, BigDecimal.TEN, new BigDecimal("20.00"));

        verify(service.imfidClient).reserve(any(), any(), eq("D-0"));
    }

    /**
     * The renewal reuses the SAME reference as the reservation — that
     * identity is the whole mechanism: a different string would create a
     * second lease and lock twice the amount on the customer's balance.
     */
    @Test
    void maybeRenewLeaseReusesTheSameTicketRef() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithLease(100);
        state.payment.ticketDbId = 42L;
        ImfidClient.ReservationResult renewed = granted(77L);
        renewed.httpStatus = 200;
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(renewed);

        service.maybeRenewLease(state);

        verify(service.imfidClient).reserve(any(), any(), eq("D-42"));
    }

    // --- onStart ---

    /**
     * A CONFIGURED loyalty service announces its target at boot — the line an
     * operator looks for in the log before a demo ("Service fidélité imfid
     * ACTIF"). The probe here is the target read; the log itself is not an
     * assertable contract.
     */
    @Test
    void onStartReadsTheTargetWhenConfigured() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(true);
        when(service.imfidClient.targetUrl()).thenReturn("http://localhost:8060");
        service.onStart(null);
        verify(service.imfidClient).targetUrl();
    }

    /**
     * An UNCONFIGURED service announces the disabled mode and never asks for
     * a target there is none of.
     */
    @Test
    void onStartAsksNoTargetWhenNotConfigured() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(false);
        service.onStart(null);
        verify(service.imfidClient, never()).targetUrl();
    }

    // --- validateCard revaluation ---

    /**
     * Attaching a card on a NON-EMPTY cart REVALUES it: the engine request
     * must carry the customerCode, and the earn projection must be fed a
     * couple that SEES the card. Skipping this is what once left the badge
     * at zero until the next scan.
     */
    @Test
    void validateCardRevaluesANonEmptyCart() {
        FidelityService service = serviceWithCard("2990000000019");
        service.ticketService = mock(com.intermarche.pos.ui.ticket.TicketService.class);
        PosState state = new PosState();
        state.ticket.items.add(new com.intermarche.pos.ui.ticket.TicketState.TicketItem());

        service.validateCard(state, "2990000000019");

        verify(service.ticketService).recalculateTotal(state);
        assertTrue(state.fidelity.active);
    }

    /**
     * On an EMPTY cart nothing is revalued: there is no line to price, and
     * the couple will be built by the first scan anyway.
     */
    @Test
    void validateCardDoesNotRevalueAnEmptyCart() {
        FidelityService service = serviceWithCard("2990000000019");
        service.ticketService = mock(com.intermarche.pos.ui.ticket.TicketService.class);
        PosState state = new PosState();

        service.validateCard(state, "2990000000019");

        verifyNoInteractions(service.ticketService);
        assertTrue(state.fidelity.active);
    }

    // --- lease bookkeeping ---

    /**
     * A RENEWAL answers 200 where a first reservation answers 201 — the two
     * share the grant arm, so an existing lease refreshed through the normal
     * path is treated exactly like a fresh one.
     */
    @Test
    void reserveLeaseAcceptsA200AsAGrant() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        ImfidClient.ReservationResult refreshed = granted(77L);
        refreshed.httpStatus = 200;
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "50.00"));
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(refreshed);

        FidelityService.BurnVerdict verdict =
                service.reserveLease(state, new BigDecimal("4.00"), new BigDecimal("20.00"));

        assertEquals(0, new BigDecimal("4.00").compareTo(verdict.grantedAmount));
        assertEquals(77L, state.payment.fidReservationId);
    }

    /**
     * A grant WITH an expiry computes the lease duration, which is what the
     * half-life renewal later measures itself against.
     */
    @Test
    void reserveLeaseRecordsTheLeaseDurationWhenAnExpiryIsGiven() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        ImfidClient.ReservationResult result = granted(77L);
        result.expiresAt = java.time.LocalDateTime.now().plusSeconds(600).toString();
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "50.00"));
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(result);

        service.reserveLease(state, BigDecimal.TEN, new BigDecimal("20.00"));

        assertTrue(state.payment.fidLeaseSeconds > 500,
                "la durée du bail doit être mesurée: " + state.payment.fidLeaseSeconds);
    }

    /**
     * A grant WITHOUT an expiry leaves the duration at zero — the renewal
     * simply never fires, and the 410 at confirm is tolerated by design.
     */
    @Test
    void reserveLeaseLeavesTheDurationAtZeroWithoutAnExpiry() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "50.00"));
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(granted(77L));

        service.reserveLease(state, BigDecimal.TEN, new BigDecimal("20.00"));

        assertNull(state.payment.fidLeaseExpiresAt);
        assertEquals(0L, state.payment.fidLeaseSeconds);
    }

    /**
     * An UNFORESEEN status (a 500, a future code) falls back on the generic
     * refusal rather than letting the payment through: an answer the register
     * does not understand is never a grant.
     */
    @Test
    void reserveLeaseRefusesOnAnUnforeseenStatus() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "50.00"));
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(refused(500, null));

        assertEquals("PAIEMENT FIDÉLITÉ REFUSÉ", service.reserveLease(
                stateWithCard("2990000000019"), BigDecimal.TEN, new BigDecimal("20.00"))
                .refusalMessage);
    }

    // --- maybeRenewLease ---

    /**
     * Builds a state holding an active lease expiring in the given number of
     * seconds, out of a lease that lasted 600 s.
     *
     * @param remainingSeconds seconds left before expiry
     * @return the wired state
     */
    private PosState stateWithLease(long remainingSeconds) {
        PosState state = stateWithCard("2990000000019");
        state.payment.fidReservationId = 77L;
        state.payment.fidLeaseExpiresAt = java.time.LocalDateTime.now().plusSeconds(remainingSeconds);
        state.payment.fidLeaseSeconds = 600L;
        state.payment.payments.add(new com.intermarche.pos.ui.payment.PaymentState.PaymentEntry(
                "FIDELITY", new BigDecimal("10.00")));
        return state;
    }

    /**
     * NO lease: nothing to renew.
     */
    @Test
    void maybeRenewLeaseDoesNothingWithoutALease() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        service.maybeRenewLease(new PosState());
        verify(service.imfidClient, never()).reserve(any(), any(), any());
    }

    /**
     * A lease with NO recorded expiry is never renewed (second leg): there is
     * no half-life to measure.
     */
    @Test
    void maybeRenewLeaseDoesNothingWithoutAnExpiry() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        state.payment.fidReservationId = 77L;
        service.maybeRenewLease(state);
        verify(service.imfidClient, never()).reserve(any(), any(), any());
    }

    /**
     * A lease with NO recorded duration is never renewed (third leg).
     */
    @Test
    void maybeRenewLeaseDoesNothingWithoutADuration() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithLease(100);
        state.payment.fidLeaseSeconds = 0L;
        service.maybeRenewLease(state);
        verify(service.imfidClient, never()).reserve(any(), any(), any());
    }

    /**
     * A FRESH lease (more than half its life left) is left alone: renewing on
     * every payment-screen refresh would hammer imfid for nothing.
     */
    @Test
    void maybeRenewLeaseLeavesAFreshLeaseAlone() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        service.maybeRenewLease(stateWithLease(500));
        verify(service.imfidClient, never()).reserve(any(), any(), any());
    }

    /**
     * Past HALF-LIFE the lease is renewed with the amount ALREADY RESERVED —
     * an identical re-POST refreshes the expiry instead of stacking a second
     * lease — and the new expiry replaces the old one.
     */
    @Test
    void maybeRenewLeaseRefreshesTheExpiryAtHalfLife() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithLease(100);
        ImfidClient.ReservationResult renewed = granted(77L);
        renewed.httpStatus = 200;
        renewed.expiresAt = java.time.LocalDateTime.now().plusSeconds(600).toString();
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(renewed);

        service.maybeRenewLease(state);

        verify(service.imfidClient).reserve(eq("2990000000019"), eq(new BigDecimal("10.00")), any());
        assertTrue(state.payment.fidLeaseExpiresAt.isAfter(java.time.LocalDateTime.now().plusSeconds(500)));
    }

    /**
     * A REFUSED renewal leaves the current expiry untouched: the lease still
     * runs until its original TTL, and the 410 at confirm is tolerated.
     */
    @Test
    void maybeRenewLeaseKeepsTheExpiryWhenTheRenewalIsRefused() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithLease(100);
        java.time.LocalDateTime before = state.payment.fidLeaseExpiresAt;
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(refused(409, null));

        service.maybeRenewLease(state);

        assertEquals(before, state.payment.fidLeaseExpiresAt);
    }

    /**
     * A renewal that FAILS at transport is swallowed: a missed renewal is
     * harmless by design, and it must never surface on the payment screen.
     */
    @Test
    void maybeRenewLeaseSwallowsTransportFailures() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithLease(100);
        java.time.LocalDateTime before = state.payment.fidLeaseExpiresAt;
        when(service.imfidClient.reserve(any(), any(), any()))
                .thenThrow(new java.io.IOException("down"));

        service.maybeRenewLease(state);

        assertEquals(before, state.payment.fidLeaseExpiresAt);
    }

    /**
     * An UNPARSEABLE expiry becomes null rather than breaking the renewal:
     * the lease then simply lives out its TTL.
     */
    @Test
    void maybeRenewLeaseTurnsAnUnparseableExpiryIntoNull() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithLease(100);
        ImfidClient.ReservationResult renewed = granted(77L);
        renewed.httpStatus = 201;
        renewed.expiresAt = "pas-une-date";
        when(service.imfidClient.reserve(any(), any(), any())).thenReturn(renewed);

        service.maybeRenewLease(state);

        assertNull(state.payment.fidLeaseExpiresAt);
    }

    // --- confirmLease unexpected status ---

    /**
     * An UNEXPECTED status (neither 200 nor 410) is logged and tolerated: the
     * reservation id still travels, and the ingestion decides.
     */
    @Test
    void confirmLeaseToleratesAnUnexpectedStatus() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = new PosState();
        state.payment.fidReservationId = 77L;
        when(service.imfidClient.confirm(anyLong(), any())).thenReturn(confirmResult(404, null));
        assertEquals(77L, service.confirmLease(state, java.time.LocalDate.of(2026, 8, 12)));
    }

    /**
     * A NOMINAL confirmation (200) returns the id without a word.
     */
    @Test
    void confirmLeaseReturnsTheIdOnSuccess() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = new PosState();
        state.payment.fidReservationId = 77L;
        when(service.imfidClient.confirm(anyLong(), any())).thenReturn(confirmResult(200, null));
        assertEquals(77L, service.confirmLease(state, java.time.LocalDate.of(2026, 8, 12)));
    }

    // --- buildTicketClosedPayload guard legs ---

    /**
     * A REQUEST without its response yields no payload (second leg): half a
     * couple is not a couple.
     */
    @Test
    void buildTicketClosedPayloadIsNullWithoutTheResponse() {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        state.fidelity.lastValuationRequestJson = "{}";
        assertNull(service.buildTicketClosedPayload(state, "2026-C04-000001",
                "2990000000019", java.time.LocalDate.of(2026, 8, 12), null));
    }

    /**
     * A RESPONSE without its request yields no payload either (first leg).
     */
    @Test
    void buildTicketClosedPayloadIsNullWithoutTheRequest() {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        state.fidelity.lastValuationResponseJson = "{}";
        assertNull(service.buildTicketClosedPayload(state, "2026-C04-000001",
                "2990000000019", java.time.LocalDate.of(2026, 8, 12), null));
    }

    // --- loadConsultation edges ---

    /**
     * An UNCONFIGURED service says so rather than pretending the card is
     * unknown — the two situations call for different actions from the staff.
     */
    @Test
    void loadConsultationSaysWhenTheServiceIsNotConfigured() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(false);
        FidelityService.Consultation view =
                service.loadConsultation(stateWithCard("2990000000019"));
        assertEquals("SERVICE FIDÉLITÉ NON CONFIGURÉ", view.message);
    }

    /**
     * A NULL movements page shows the account without history rather than
     * failing: the balance is the useful part, the history is a bonus.
     */
    @Test
    void loadConsultationShowsTheAccountWithoutMovements() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "46.70"));
        when(service.imfidClient.movements(any(), anyInt(), anyInt())).thenReturn(null);
        FidelityService.Consultation view =
                service.loadConsultation(stateWithCard("2990000000019"));
        assertTrue(view.hasAccount());
        assertTrue(view.rows.isEmpty());
    }

    /**
     * A page whose item list is NULL is handled the same way (second leg of
     * the guard) — a tolerant reader may leave it unset.
     */
    @Test
    void loadConsultationHandlesAPageWithoutItems() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "46.70"));
        ImfidClient.MovementsPage page = new ImfidClient.MovementsPage();
        page.items = null;
        when(service.imfidClient.movements(any(), anyInt(), anyInt())).thenReturn(page);
        FidelityService.Consultation view =
                service.loadConsultation(stateWithCard("2990000000019"));
        assertTrue(view.rows.isEmpty());
    }

    /**
     * An EARN WITHOUT a rule code shows the bare label: the parenthesis is
     * only added when there is something to put in it.
     */
    @Test
    void loadConsultationEarnWithoutRuleCodeShowsTheBareLabel() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "46.70"));
        ImfidClient.MovementsPage page = new ImfidClient.MovementsPage();
        page.items.add(movement("EARN", "1.03", "2026-08-12", null));
        when(service.imfidClient.movements(any(), anyInt(), anyInt())).thenReturn(page);
        FidelityService.Consultation view =
                service.loadConsultation(stateWithCard("2990000000019"));
        assertEquals("CAGNOTTE", view.rows.get(0)[1]);
    }

    /**
     * The remaining movement types are translated too — including the ones a
     * customer only ever sees on a bad day (expiry, activation void).
     */
    @Test
    void loadConsultationTranslatesTheRemainingMovementTypes() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "46.70"));
        ImfidClient.MovementsPage page = new ImfidClient.MovementsPage();
        page.items.add(movement("REFUND_CREDIT", "5.00", "2026-08-12", null));
        page.items.add(movement("ADJUSTMENT", "1.00", "2026-08-12", null));
        page.items.add(movement("EXPIRY", "-3.00", "2026-08-12", null));
        page.items.add(movement("ACTIVATION_VOID", "-1.00", "2026-08-12", null));
        when(service.imfidClient.movements(any(), anyInt(), anyInt())).thenReturn(page);

        FidelityService.Consultation view =
                service.loadConsultation(stateWithCard("2990000000019"));

        assertEquals("REMBOURSEMENT EN CAGNOTTE", view.rows.get(0)[1]);
        assertEquals("AJUSTEMENT", view.rows.get(1)[1]);
        assertEquals("PÉREMPTION", view.rows.get(2)[1]);
        assertEquals("ANNULATION D'ACTIVATION", view.rows.get(3)[1]);
    }

    /**
     * The displayed DATE prefers the fiscal date; without one it falls back
     * on the creation instant TRUNCATED to its day, and on nothing at all it
     * shows an empty cell rather than a null.
     */
    @Test
    void loadConsultationPicksTheDisplayedDate() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "46.70"));
        ImfidClient.MovementsPage page = new ImfidClient.MovementsPage();
        page.items.add(movement("ADJUSTMENT", "1.00", "2026-08-12", null));
        ImfidClient.Movement created = movement("ADJUSTMENT", "1.00", null, null);
        created.createdAt = "2026-08-11T14:03:22";
        page.items.add(created);
        ImfidClient.Movement shortDate = movement("ADJUSTMENT", "1.00", null, null);
        shortDate.createdAt = "2026";
        page.items.add(shortDate);
        page.items.add(movement("ADJUSTMENT", "1.00", null, null));
        when(service.imfidClient.movements(any(), anyInt(), anyInt())).thenReturn(page);

        FidelityService.Consultation view =
                service.loadConsultation(stateWithCard("2990000000019"));

        assertEquals("2026-08-12", view.rows.get(0)[0]);
        assertEquals("2026-08-11", view.rows.get(1)[0]);
        assertEquals("", view.rows.get(2)[0]);
        assertEquals("", view.rows.get(3)[0]);
    }

    /**
     * The displayed AMOUNT carries an explicit PLUS on credits — a history
     * where debits and credits look alike is unreadable — and nothing but the
     * currency when the amount is missing.
     */
    @Test
    void loadConsultationSignsTheDisplayedAmount() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.account(any())).thenReturn(account("ACTIVE", "56.70", "46.70"));
        ImfidClient.MovementsPage page = new ImfidClient.MovementsPage();
        page.items.add(movement("ADJUSTMENT", "1.00", "2026-08-12", null));
        page.items.add(movement("ADJUSTMENT", "-1.00", "2026-08-12", null));
        page.items.add(movement("ADJUSTMENT", "0.00", "2026-08-12", null));
        ImfidClient.Movement noAmount = movement("ADJUSTMENT", "1.00", "2026-08-12", null);
        noAmount.amount = null;
        page.items.add(noAmount);
        when(service.imfidClient.movements(any(), anyInt(), anyInt())).thenReturn(page);

        FidelityService.Consultation view =
                service.loadConsultation(stateWithCard("2990000000019"));

        assertEquals("+1.00 €", view.rows.get(0)[2]);
        assertEquals("-1.00 €", view.rows.get(1)[2]);
        assertEquals("0.00 €", view.rows.get(2)[2]);
        assertEquals(" €", view.rows.get(3)[2]);
    }

    // --- refreshEarn guards ---

    /**
     * A cart with a couple but NO CONFIGURED service projects nothing (second
     * leg of the guard).
     */
    @Test
    void refreshEarnAsksNothingWhenTheServiceIsNotConfigured() throws Exception {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(false);
        PosState state = stateWithCard("2990000000019");
        service.onValuation(state, "{}", "{}");
        verify(service.imfidClient, never()).earn(any(), any());
        assertNull(state.fidelity.earnTotal);
    }

    /**
     * A null REQUEST leg blocks the projection (third leg): the couple is
     * fed verbatim or not at all.
     */
    @Test
    void refreshEarnAsksNothingWithoutTheRequestSide() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        service.onValuation(state, null, "{}");
        verify(service.imfidClient, never()).earn(any(), any());
    }

    /**
     * A null RESPONSE leg blocks it too (fourth leg).
     */
    @Test
    void refreshEarnAsksNothingWithoutTheResponseSide() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        service.onValuation(state, "{}", null);
        verify(service.imfidClient, never()).earn(any(), any());
    }

    /**
     * While the BREAKER is open no projection is requested: the previous
     * failure bought ten seconds of silence, and this proves the second call
     * is not merely refused but never even attempted.
     */
    @Test
    void refreshEarnStaysSilentWhileTheBreakerIsOpen() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        when(service.imfidClient.earn(any(), any())).thenThrow(new java.io.IOException("down"));

        service.onValuation(state, "{}", "{}");
        service.onValuation(state, "{}", "{}");

        verify(service.imfidClient, times(1)).earn(any(), any());
        assertNull(state.fidelity.earnTotal);
    }

    /**
     * A projection with NO entry list is accepted: the total is displayed and
     * the printed section simply has no rule lines.
     */
    @Test
    void refreshEarnAcceptsAProjectionWithoutEntries() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        ImfidClient.EarnProjection projection = new ImfidClient.EarnProjection();
        projection.total = new BigDecimal("1.03");
        projection.entries = null;
        when(service.imfidClient.earn(any(), any())).thenReturn(projection);

        service.onValuation(state, "{}", "{}");

        assertEquals(0, new BigDecimal("1.03").compareTo(state.fidelity.earnTotal));
        assertTrue(state.fidelity.earnEntries.isEmpty());
    }

    /**
     * The entries are mapped into printable lines, code, label and amount
     * preserved — those labels are the ONLY rule data the register prints.
     */
    @Test
    void refreshEarnMapsTheEntriesIntoPrintableLines() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = stateWithCard("2990000000019");
        ImfidClient.EarnProjection projection = new ImfidClient.EarnProjection();
        projection.total = new BigDecimal("1.03");
        ImfidClient.EarnEntry entry = new ImfidClient.EarnEntry();
        entry.ruleCode = "F&L-SAM";
        entry.label = "Fruits & légumes samedi";
        entry.amount = new BigDecimal("0.75");
        projection.entries = java.util.List.of(entry);
        when(service.imfidClient.earn(any(), any())).thenReturn(projection);

        service.onValuation(state, "{}", "{}");

        assertEquals(1, state.fidelity.earnEntries.size());
        assertEquals("F&L-SAM", state.fidelity.earnEntries.get(0).ruleCode);
        assertEquals("Fruits & légumes samedi", state.fidelity.earnEntries.get(0).label);
        assertEquals(0, new BigDecimal("0.75").compareTo(state.fidelity.earnEntries.get(0).amount));
    }

    // --- lookupCards ---

    /**
     * Builds a service whose imfid client is a configured mock.
     *
     * @return the service under test
     */
    private FidelityService lookupService() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(true);
        service.posSettingsService = settings(true);
        return service;
    }

    /**
     * Builds an administered-settings mock answering the holder-name display
     * flag (BO-10-03-04) with the given value.
     *
     * @param showHolderName the administered value of {@code fidelity.show-holder-name}
     * @return the settings mock
     */
    private com.intermarche.pos.service.PosSettingsService settings(boolean showHolderName) {
        com.intermarche.pos.service.PosSettingsService settings =
                mock(com.intermarche.pos.service.PosSettingsService.class);
        when(settings.fidelityShowHolderName()).thenReturn(showHolderName);
        return settings;
    }

    /**
     * Builds a lookup result carrying the given matches.
     *
     * @param n the number of synthetic matches, or -1 for a null match list
     * @param crm whether the CRM flag is raised
     * @param reason the refusal reason
     * @return the result
     */
    private ImfidClient.LookupResult lookupResult(int n, boolean crm, String reason) {
        ImfidClient.LookupResult result = new ImfidClient.LookupResult();
        result.crmManaged = crm;
        result.refusalReason = reason;
        if (n >= 0) {
            result.matches = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                result.matches.add(new ImfidClient.LookupMatch());
            }
        }
        return result;
    }

    /**
     * {@code lookupCards} short-circuits to the unavailable message when the
     * service is not configured (not-configured arm).
     */
    @Test
    void lookupCardsUnavailableWhenNotConfigured() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(false);
        LookupView view = service.lookupCards("06", null, null, null);
        assertEquals("SERVICE FIDÉLITÉ INDISPONIBLE", view.message);
        assertNull(view.matches);
    }

    /**
     * {@code lookupCards} refuses an all-blank criterion set (no-criterion
     * arm) without any network call.
     *
     * @throws Exception never
     */
    @Test
    void lookupCardsRequiresACriterion() throws Exception {
        FidelityService service = lookupService();
        LookupView view = service.lookupCards("  ", "", "  ", "x");
        assertEquals("SAISISSEZ UN TÉLÉPHONE, UN E-MAIL, UN NOM OU UNE VILLE", view.message);
        verify(service.imfidClient, never()).lookup(any(), any(), any(), any(), any());
    }

    /**
     * {@code lookupCards} maps a CRM-managed answer to its dedicated message
     * (crm arm).
     *
     * @throws Exception never
     */
    @Test
    void lookupCardsReportsCrmMode() throws Exception {
        FidelityService service = lookupService();
        when(service.imfidClient.lookup(any(), any(), any(), any(), any()))
                .thenReturn(lookupResult(-1, true, null));
        LookupView view = service.lookupCards(null, "jean@x.fr", null, null);
        assertEquals("IDENTITÉS GÉRÉES PAR LE CRM - RECHERCHE INDISPONIBLE EN CAISSE", view.message);
        assertNull(view.matches);
    }

    /**
     * {@code lookupCards} maps a null-match refusal to the refused message
     * (matches-null arm).
     *
     * @throws Exception never
     */
    @Test
    void lookupCardsReportsRefusal() throws Exception {
        FidelityService service = lookupService();
        when(service.imfidClient.lookup(any(), any(), any(), any(), any()))
                .thenReturn(lookupResult(-1, false, "SOME_REASON"));
        LookupView view = service.lookupCards(null, null, "Dupont", null);
        assertEquals("RECHERCHE REFUSÉE PAR LE SERVICE FIDÉLITÉ", view.message);
        assertNull(view.matches);
    }

    /**
     * {@code lookupCards} annotates an empty result set (empty arm) while
     * still exposing the (empty) match list.
     *
     * @throws Exception never
     */
    @Test
    void lookupCardsReportsNoMatch() throws Exception {
        FidelityService service = lookupService();
        when(service.imfidClient.lookup(any(), any(), any(), any(), any()))
                .thenReturn(lookupResult(0, false, null));
        LookupView view = service.lookupCards(null, null, "Dupont", null);
        assertEquals("AUCUNE CARTE TROUVÉE - ESSAYEZ UN AUTRE CRITÈRE", view.message);
        assertTrue(view.matches.isEmpty());
    }

    /**
     * {@code lookupCards} warns about truncation when the list hits the cap of
     * 20 (cap arm), still exposing the matches.
     *
     * @throws Exception never
     */
    @Test
    void lookupCardsWarnsWhenCapped() throws Exception {
        FidelityService service = lookupService();
        when(service.imfidClient.lookup(any(), any(), any(), any(), any()))
                .thenReturn(lookupResult(20, false, null));
        LookupView view = service.lookupCards("0612345678", null, null, null);
        assertEquals("TROP DE CORRESPONDANCES - PRÉCISEZ LE CRITÈRE (TÉLÉPHONE)", view.message);
        assertEquals(20, view.matches.size());
    }

    /**
     * {@code lookupCards} exposes a bounded result set with no message
     * (in-range arm).
     *
     * @throws Exception never
     */
    @Test
    void lookupCardsExposesBoundedMatches() throws Exception {
        FidelityService service = lookupService();
        when(service.imfidClient.lookup(any(), any(), any(), any(), any()))
                .thenReturn(lookupResult(3, false, null));
        LookupView view = service.lookupCards(null, null, "Dupont", "Jean");
        assertNull(view.message);
        assertEquals(3, view.matches.size());
    }

    /**
     * A transport failure opens the breaker (exception arm): the first call
     * degrades and a second call short-circuits on the breaker without a
     * second network call.
     *
     * @throws Exception never
     */
    @Test
    void lookupCardsTransportFailureOpensBreaker() throws Exception {
        FidelityService service = lookupService();
        when(service.imfidClient.lookup(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));
        LookupView first = service.lookupCards("0612345678", null, null, null);
        assertEquals("SERVICE FIDÉLITÉ INDISPONIBLE", first.message);
        LookupView second = service.lookupCards("0612345678", null, null, null);
        assertEquals("SERVICE FIDÉLITÉ INDISPONIBLE", second.message);
        verify(service.imfidClient, times(1)).lookup(any(), any(), any(), any(), any());
    }

    // --- attachLookedUpCard ---

    /**
     * {@code attachLookedUpCard} refuses a resiliated card (RESILIATED arm)
     * without attaching anything.
     */
    @Test
    void attachRefusesResiliatedCard() {
        FidelityService service = lookupService();
        PosState state = new PosState();
        String message = service.attachLookedUpCard(state, "2990000000019", "Dupont", "Jean", "RESILIATED", null);
        assertEquals("CARTE RÉSILIÉE - INVITER LE CLIENT À PASSER À L'ACCUEIL", message);
        assertFalse(state.fidelity.active);
    }

    /**
     * {@code attachLookedUpCard} attaches a pending card, records the holder,
     * defaults the status (status-null arm) and warns that the cagnotte cannot
     * be spent (PENDING_ACTIVATION arm).
     */
    @Test
    void attachPendingCardWarnsAndDefaultsStatus() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(false);
        service.posSettingsService = settings(true);
        PosState state = new PosState();
        String message = service.attachLookedUpCard(state, "2990000000019", "Dupont", "Jean", "PENDING_ACTIVATION", null);
        assertNull(message);
        assertTrue(state.fidelity.active);
        assertEquals("Dupont", state.fidelity.holderLastName);
        assertEquals("Jean", state.fidelity.holderFirstName);
        assertEquals("PENDING_ACTIVATION", state.fidelity.accountStatus);
        assertEquals("CARTE EN ATTENTE D'ACTIVATION - CAGNOTTE SANS UTILISATION", state.ticket.transientError);
    }

    /**
     * {@code attachLookedUpCard} attaches an active card without warning
     * (PENDING_ACTIVATION false arm), defaulting the status when the refresh
     * read nothing (status-null arm).
     */
    @Test
    void attachActiveCardDefaultsStatusWhenRefreshSilent() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(false);
        service.posSettingsService = settings(true);
        PosState state = new PosState();
        String message = service.attachLookedUpCard(state, "2990000000019", "Dupont", null, "ACTIVE", null);
        assertNull(message);
        assertEquals("ACTIVE", state.fidelity.accountStatus);
        assertNull(state.ticket.transientError);
    }

    /**
     * {@code attachLookedUpCard} keeps the status the live refresh already set
     * (status-non-null arm), not overwriting it with the passed value.
     *
     * @throws Exception never
     */
    @Test
    void attachKeepsStatusReadLive() throws Exception {
        FidelityService service = lookupService();
        when(service.imfidClient.account("2990000000019")).thenReturn(account("ACTIVE", "10", "10"));
        PosState state = new PosState();
        service.attachLookedUpCard(state, "2990000000019", "Dupont", "Jean", "PENDING_ACTIVATION", null);
        assertEquals("ACTIVE", state.fidelity.accountStatus);
    }

    // --- refreshAccountDisplay (through validateCard) ---

    /**
     * The account refresh skips an inactive card (inactive arm): a too-short
     * value leaves the card unattached and the account untouched.
     *
     * @throws Exception never
     */
    @Test
    void refreshSkipsWhenCardInactive() throws Exception {
        FidelityService service = lookupService();
        PosState state = new PosState();
        service.validateCard(state, "12");
        verify(service.imfidClient, never()).account(any());
    }

    /**
     * The account refresh skips when the service is not configured
     * (not-configured arm).
     *
     * @throws Exception never
     */
    @Test
    void refreshSkipsWhenNotConfigured() throws Exception {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(false);
        PosState state = new PosState();
        service.validateCard(state, "2990000000019");
        verify(service.imfidClient, never()).account(any());
    }

    /**
     * The account refresh leaves the display fields null when imfid returns no
     * account (account-null arm).
     *
     * @throws Exception never
     */
    @Test
    void refreshLeavesFieldsNullOnNullAccount() throws Exception {
        FidelityService service = lookupService();
        when(service.imfidClient.account("2990000000019")).thenReturn(null);
        PosState state = new PosState();
        service.validateCard(state, "2990000000019");
        assertNull(state.fidelity.accountStatus);
        assertNull(state.fidelity.availableBalance);
    }

    /**
     * The account refresh projects the status and available balance when imfid
     * answers (account-non-null arm).
     *
     * @throws Exception never
     */
    @Test
    void refreshPopulatesFromAccount() throws Exception {
        FidelityService service = lookupService();
        when(service.imfidClient.account("2990000000019")).thenReturn(account("ACTIVE", "20", "15"));
        PosState state = new PosState();
        service.validateCard(state, "2990000000019");
        assertEquals("ACTIVE", state.fidelity.accountStatus);
        assertEquals(0, new BigDecimal("15").compareTo(state.fidelity.availableBalance));
    }

    /**
     * A refresh transport failure opens the breaker (exception arm): the
     * fields stay null and a second attachment does not call imfid again.
     *
     * @throws Exception never
     */
    @Test
    void refreshExceptionOpensBreaker() throws Exception {
        FidelityService service = lookupService();
        when(service.imfidClient.account("2990000000019")).thenThrow(new RuntimeException("down"));
        PosState state = new PosState();
        service.validateCard(state, "2990000000019");
        assertNull(state.fidelity.accountStatus);
        service.validateCard(state, "2990000000019");
        verify(service.imfidClient, times(1)).account("2990000000019");
    }

    /**
     * Builds a service and a real state for the holder-address cases: no imfid is
     * needed, the address travels from the lookup match the caller echoes back.
     *
     * @return the service, its imfid client reporting itself unconfigured
     */
    private FidelityService attachService() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(false);
        service.posSettingsService = settings(true);
        return service;
    }

    // --- attachLookedUpCard : l'adresse du porteur (LC-08-02-09) ---

    /**
     * The holder's address travels from the lookup match into the register state, so
     * the till can offer it before sending the receipt.
     */
    @Test
    void attachLookedUpCardKeepsTheHolderEmail() {
        FidelityService service = attachService();
        PosState state = new PosState();
        service.attachLookedUpCard(state, "2990000000019", "Dupont", "Jean", "ACTIVE",
                " Jean.Dupont@example.org ");
        assertEquals("Jean.Dupont@example.org", state.fidelity.holderEmail);
    }

    /**
     * A referential that holds no address leaves the field empty rather than a blank
     * string the screen would offer as an address (blank arm).
     */
    @Test
    void attachLookedUpCardWithABlankEmailKeepsNone() {
        FidelityService service = attachService();
        PosState state = new PosState();
        service.attachLookedUpCard(state, "2990000000019", "Dupont", "Jean", "ACTIVE", "   ");
        assertNull(state.fidelity.holderEmail);
    }

    /**
     * A match carrying no address at all leaves the field empty (null arm).
     */
    @Test
    void attachLookedUpCardWithoutEmailKeepsNone() {
        FidelityService service = attachService();
        PosState state = new PosState();
        service.attachLookedUpCard(state, "2990000000019", "Dupont", "Jean", "ACTIVE", null);
        assertNull(state.fidelity.holderEmail);
    }

    /**
     * A refused card records nothing, its address included.
     */
    @Test
    void aRefusedCardRecordsNoEmail() {
        FidelityService service = attachService();
        PosState state = new PosState();
        service.attachLookedUpCard(state, "2990000000019", "Dupont", "Jean", "RESILIATED",
                "jean@example.org");
        assertNull(state.fidelity.holderEmail);
    }

    // --- attachLookedUpCard : affichage du nom du porteur (BO-10-03-04) ---

    /**
     * The administered flag ON carries the holder's name onto the register, so
     * the operator sees it beside the card number (true arm).
     */
    @Test
    void theAdministeredFlagCarriesTheHolderName() {
        FidelityService service = attachService();
        PosState state = new PosState();
        service.attachLookedUpCard(state, "2990000000019", "Dupont", "Jean", "ACTIVE", null);
        assertEquals("Dupont", state.fidelity.holderLastName);
        assertEquals("Jean", state.fidelity.holderFirstName);
        assertEquals("DUPONT Jean · 2990000000019", state.fidelity.getDisplaySummary());
    }

    /**
     * The administered flag OFF keeps the register pseudonymous: neither name is
     * stored and the summary shows the card number alone (false arm). Asserting a
     * SECOND administered value is what proves the flag is read rather than a
     * literal being returned.
     */
    @Test
    void theAdministeredFlagOffKeepsTheRegisterPseudonymous() {
        FidelityService service = attachService();
        service.posSettingsService = settings(false);
        PosState state = new PosState();
        service.attachLookedUpCard(state, "2990000000019", "Dupont", "Jean", "ACTIVE", null);
        assertNull(state.fidelity.holderLastName);
        assertNull(state.fidelity.holderFirstName);
        assertEquals("2990000000019", state.fidelity.getDisplaySummary());
    }

    /**
     * The flag governs the NAME only: the holder's e-mail still travels, because
     * the receipt address is a different requirement (LC-08-02-09).
     */
    @Test
    void theAdministeredFlagDoesNotGovernTheHolderEmail() {
        FidelityService service = attachService();
        service.posSettingsService = settings(false);
        PosState state = new PosState();
        service.attachLookedUpCard(state, "2990000000019", "Dupont", "Jean", "ACTIVE",
                "jean@example.org");
        assertNull(state.fidelity.holderLastName);
        assertEquals("jean@example.org", state.fidelity.holderEmail);
    }

    // --- isUnavailable (BO-03-03-29 / BO-03-03-30) ---

    /**
     * A shop that never configured the loyalty service is NOT unavailable:
     * there is nothing to reach, and a receipt apologizing for a programme the
     * shop does not run would be a defect. First leg of that guard.
     */
    @Test
    void anunconfiguredProgrammeIsNotUnavailable() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(false);
        assertFalse(service.isUnavailable());
    }

    /**
     * A configured service that has not failed is available: the breaker is
     * shut, so the closing freezes nothing on the sale. Second leg, false arm.
     */
    @Test
    void aconfiguredProgrammeThatAnswersIsAvailable() {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(true);
        assertFalse(service.isUnavailable());
    }

    /**
     * A configured service whose last call FAILED is unavailable for the
     * breaker window: that is how a register learns the programme is down, and
     * how the tickets that follow — with or without a card — carry their
     * administered message. Second leg, true arm.
     */
    @Test
    void aconfiguredProgrammeThatFailedIsUnavailable() throws Exception {
        FidelityService service = new FidelityService();
        service.i18n = TestMessages.french();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(true);
        when(service.imfidClient.earn(any(), any())).thenThrow(new java.io.IOException("down"));
        PosState state = new PosState();
        state.fidelity.assignCard("2990000000019");
        service.onValuation(state, "{}", "{}");
        assertTrue(service.isUnavailable());
    }

    // --- imfid contracts: advantage grouping, balances, anonymous, promotions ---

    /**
     * Builds an earn projection carrying one advantage-typed entry and a
     * balances block (imfid responses A and B).
     *
     * @return the projection
     */
    private ImfidClient.EarnProjection advantageProjection() {
        ImfidClient.EarnProjection projection = new ImfidClient.EarnProjection();
        projection.total = new BigDecimal("3.90");
        projection.burnableBase = new BigDecimal("28.40");
        ImfidClient.EarnEntry entry = new ImfidClient.EarnEntry();
        entry.ruleCode = "BOOST_MARQUE_2026";
        entry.label = "Boost marque";
        entry.amount = new BigDecimal("1.50");
        entry.advantageType = "PRODUCT";
        entry.advantageTypeLabel = "Vos avantages produits";
        entry.advantageTypeOrder = 10;
        projection.entries.add(entry);
        ImfidClient.Balances balances = new ImfidClient.Balances();
        balances.available = new BigDecimal("42.30");
        balances.projectedAfterEarn = new BigDecimal("46.20");
        balances.asOf = "2026-09-17T14:01:57";
        projection.balances = balances;
        return projection;
    }

    /**
     * A CARD refresh carries the entry's advantage grouping onto the staged
     * earn line and the balances block onto the projected fields (imfid A + B).
     *
     * @throws Exception never (mocked client)
     */
    @Test
    void onValuationStagesAdvantageFieldsAndBalances() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        when(service.imfidClient.earn(any(), any())).thenReturn(advantageProjection());
        PosState state = new PosState();
        state.fidelity.assignCard("2990000000019");
        service.onValuation(state, "{}", "{}");
        FidelityState.EarnLine line = state.fidelity.earnEntries.get(0);
        assertEquals("PRODUCT", line.advantageType);
        assertEquals("Vos avantages produits", line.advantageTypeLabel);
        assertEquals(10, line.advantageTypeOrder);
        assertEquals(0, new BigDecimal("46.20").compareTo(state.fidelity.projectedAfterEarn));
        assertEquals("2026-09-17T14:01:57", state.fidelity.balanceAsOf);
        assertFalse(state.fidelity.anonymousProjection);
    }

    /**
     * A card-less ticket, with the store setting on, asks imfid for the
     * ANONYMOUS projection and flags the staged total as anonymous, with no
     * projected balance (imfid response C).
     *
     * @throws Exception never (mocked client)
     */
    @Test
    void onValuationProjectsAnonymouslyWithoutACardWhenEnabled() throws Exception {
        FidelityService service = serviceWithCard("unused");
        when(service.posSettingsService.fidelityAnonymousProjection()).thenReturn(true);
        ImfidClient.EarnProjection projection = new ImfidClient.EarnProjection();
        projection.total = new BigDecimal("1.20");
        projection.burnableBase = BigDecimal.ZERO;
        when(service.imfidClient.earn(any(), any(), eq("ANONYMOUS"))).thenReturn(projection);
        PosState state = new PosState();
        service.onValuation(state, "{}", "{}");
        assertTrue(state.fidelity.anonymousProjection);
        assertEquals(0, new BigDecimal("1.20").compareTo(state.fidelity.earnTotal));
        assertNull(state.fidelity.projectedAfterEarn);
        verify(service.imfidClient).earn(any(), any(), eq("ANONYMOUS"));
    }

    /**
     * A card-less ticket with the setting OFF asks nothing anonymous — the
     * behaviour is strictly unchanged.
     *
     * @throws Exception never (mocked client)
     */
    @Test
    void onValuationAsksNoAnonymousProjectionWhenDisabled() throws Exception {
        FidelityService service = serviceWithCard("unused");
        when(service.posSettingsService.fidelityAnonymousProjection()).thenReturn(false);
        PosState state = new PosState();
        service.onValuation(state, "{}", "{}");
        assertFalse(state.fidelity.anonymousProjection);
        assertNull(state.fidelity.earnTotal);
        verify(service.imfidClient, never()).earn(any(), any(), any());
    }

    /**
     * Confirming a lease reads {@code balanceAfter} from the ledger body onto
     * the state (imfid response B); a null {@code balanceBefore} is tolerated.
     *
     * @throws Exception never (mocked client)
     */
    @Test
    void confirmLeaseReadsBalanceAfterFromTheLedger() throws Exception {
        FidelityService service = serviceWithCard("2990000000019");
        PosState state = new PosState();
        state.payment.fidReservationId = 77L;
        when(service.imfidClient.confirm(77L, "2026-08-12")).thenReturn(confirmResult(200, "30.30"));
        assertEquals(77L, service.confirmLease(state, java.time.LocalDate.of(2026, 8, 12)));
        assertEquals(0, new BigDecimal("30.30").compareTo(state.fidelity.balanceAfter));
    }

    /**
     * PERCENT is a fraction of the net line price (§3.1): 0.10 of 3,50 € is
     * 0,35 €.
     */
    @Test
    void immediateDiscountPercentIsFractionOfNet() {
        assertEquals(0, new BigDecimal("0.35").compareTo(FidelityService.immediateDiscount(
                "PERCENT", new BigDecimal("0.10"), new BigDecimal("3.50"), 1)));
    }

    /**
     * AMOUNT is a euro-per-unit times the unit count (§3.1): 0,50 € over two
     * units is 1,00 €.
     */
    @Test
    void immediateDiscountAmountIsPerUnit() {
        assertEquals(0, new BigDecimal("1.00").compareTo(FidelityService.immediateDiscount(
                "AMOUNT", new BigDecimal("0.5000"), new BigDecimal("6.00"), 2)));
    }

    /**
     * An unknown promotion type and a non-positive value both yield a zero
     * discount (§3.1 defensive arms).
     */
    @Test
    void immediateDiscountUnknownTypeOrNonPositiveIsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(FidelityService.immediateDiscount(
                "COUPON", new BigDecimal("0.10"), new BigDecimal("3.50"), 1)));
        assertEquals(0, BigDecimal.ZERO.compareTo(FidelityService.immediateDiscount(
                "PERCENT", BigDecimal.ZERO, new BigDecimal("3.50"), 1)));
        assertEquals(0, BigDecimal.ZERO.compareTo(FidelityService.immediateDiscount(
                "AMOUNT", null, new BigDecimal("3.50"), 1)));
    }

    /**
     * Builds an immediate-discount card promotion.
     *
     * @param ean the promoted EAN
     * @param type PERCENT or AMOUNT
     * @param value the promotion value
     * @param benefit CAGNOTTE or IMMEDIATE_DISCOUNT
     * @return the promotion
     */
    private ImfidClient.CardPromotion promo(String ean, String type, String value, String benefit) {
        ImfidClient.CardPromotion promotion = new ImfidClient.CardPromotion();
        promotion.ean = ean;
        promotion.promotionType = type;
        promotion.value = new BigDecimal(value);
        promotion.benefit = benefit;
        return promotion;
    }

    /**
     * With the engine forwarding OFF, the register applies an IMMEDIATE_DISCOUNT
     * to the matching-EAN line itself (§2/§3.1): café 0,50 € × 2 units = 1,00 €.
     */
    @Test
    void applyCardPromotionsAppliesImmediateDiscountWhenEngineForwardingOff() {
        FidelityService service = serviceWithCard("2990000000019");
        service.ticketService = mock(com.intermarche.pos.ui.ticket.TicketService.class);
        when(service.posSettingsService.fidelityJvPromotionEnabled()).thenReturn(false);
        PosState state = new PosState();
        state.ticket.addItem("3300000000004", null, "CAFE GRAINS 500G",
                new BigDecimal("3.00"), new BigDecimal("2"), new BigDecimal("5.5"));
        com.intermarche.pos.ui.ticket.TicketState.TicketItem item = state.ticket.items.get(0);
        state.fidelity.cardPromotions = java.util.List.of(
                promo("3300000000004", "AMOUNT", "0.5000", "IMMEDIATE_DISCOUNT"));
        service.applyCardPromotions(state);
        verify(service.ticketService).applyRemise(item, new BigDecimal("1.00"));
    }

    /**
     * With the engine forwarding ON, the register applies NOTHING — the engine
     * applies the discount, and §2 forbids both.
     */
    @Test
    void applyCardPromotionsSkippedWhenEngineForwardingOn() {
        FidelityService service = serviceWithCard("2990000000019");
        service.ticketService = mock(com.intermarche.pos.ui.ticket.TicketService.class);
        when(service.posSettingsService.fidelityJvPromotionEnabled()).thenReturn(true);
        PosState state = new PosState();
        state.ticket.addItem("3300000000004", null, "CAFE",
                new BigDecimal("3.00"), new BigDecimal("2"), new BigDecimal("5.5"));
        state.fidelity.cardPromotions = java.util.List.of(
                promo("3300000000004", "AMOUNT", "0.5000", "IMMEDIATE_DISCOUNT"));
        service.applyCardPromotions(state);
        verify(service.ticketService, never()).applyRemise(any(), any());
    }

    /**
     * A promotion whose EAN is on no cart line applies nothing.
     */
    @Test
    void applyCardPromotionsIgnoresNonMatchingEan() {
        FidelityService service = serviceWithCard("2990000000019");
        service.ticketService = mock(com.intermarche.pos.ui.ticket.TicketService.class);
        when(service.posSettingsService.fidelityJvPromotionEnabled()).thenReturn(false);
        PosState state = new PosState();
        state.ticket.addItem("9999999999999", null, "AUTRE",
                new BigDecimal("3.00"), new BigDecimal("1"), new BigDecimal("5.5"));
        state.fidelity.cardPromotions = java.util.List.of(
                promo("3300000000004", "AMOUNT", "0.5000", "IMMEDIATE_DISCOUNT"));
        service.applyCardPromotions(state);
        verify(service.ticketService, never()).applyRemise(any(), any());
    }

    /**
     * A CAGNOTTE promotion is NEVER applied by the register (§2): it is skipped
     * even when present in the list — the double advantage is impossible.
     */
    @Test
    void applyCardPromotionsNeverAppliesCagnotte() {
        FidelityService service = serviceWithCard("2990000000019");
        service.ticketService = mock(com.intermarche.pos.ui.ticket.TicketService.class);
        when(service.posSettingsService.fidelityJvPromotionEnabled()).thenReturn(false);
        PosState state = new PosState();
        state.ticket.addItem("3400000000003", null, "THE VERT",
                new BigDecimal("3.50"), new BigDecimal("1"), new BigDecimal("5.5"));
        state.fidelity.cardPromotions = java.util.List.of(
                promo("3400000000003", "PERCENT", "0.1000", "CAGNOTTE"));
        service.applyCardPromotions(state);
        verify(service.ticketService, never()).applyRemise(any(), any());
    }
}
