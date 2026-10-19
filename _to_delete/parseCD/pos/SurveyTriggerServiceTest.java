package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.home.HomeService;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SurveyTriggerService}, the three administered
 * moments of the survey (LC-03-07-01, -02 and -03).
 * <p>
 * The channel's caisse end (the client) and the banner's applier (the home
 * service) are mocks; the register state is a real {@link PosState} graph
 * whose ticket sub-state is real too, so the once-per-transaction flag is
 * exercised exactly as the sale funnel reads it. Both arms of every guard
 * are covered: the empty cart, the already-asked transaction, the silent
 * node, and the mapping of the survey machinery the channel now carries.
 */
class SurveyTriggerServiceTest {

    /**
     * Builds a trigger service with a real state and mocked collaborators.
     *
     * @return a service whose asks are observable on its client and applier
     */
    private SurveyTriggerService newService() {
        SurveyTriggerService service = new SurveyTriggerService();
        service.client = mock(BackOfficeMessageClient.class);
        service.homeService = mock(HomeService.class);
        service.state = new PosState();
        return service;
    }

    /**
     * LC-03-07-01: the beginning-of-transaction survey is asked ONCE, at the
     * first article that makes the cart non-empty — the second mutation of
     * the same transaction asks nothing.
     */
    @Test
    void theTransactionStartSurveyIsAskedOncePerTransaction() {
        SurveyTriggerService service = newService();
        service.state.ticket.items.add(new TicketState.TicketItem(
                "123", null, "L", java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                java.math.BigDecimal.ZERO));
        when(service.client.fetchPending("TRANSACTION_START")).thenReturn(
                new BackOfficeMessageClient.PendingMessage(
                        null, "Premier article ?", "Oui;Non", null, false, null, null));
        service.maybeFireOnTransactionStart(service.state);
        verify(service.client).fetchPending("TRANSACTION_START");
        ArgumentCaptor<PosState.BackOfficeSurvey> captor =
                ArgumentCaptor.forClass(PosState.BackOfficeSurvey.class);
        verify(service.homeService).receiveBackOfficeMessage(isNull(), captor.capture());
        assertEquals("Premier article ?", captor.getValue().question);
        assertTrue(service.state.surveyTransactionStartAsked);
        service.maybeFireOnTransactionStart(service.state);
        verify(service.client, org.mockito.Mockito.times(1))
                .fetchPending("TRANSACTION_START");
    }

    /**
     * LC-03-07-01, the empty arm: an EMPTY cart is not the beginning of a
     * transaction — nothing is asked and the flag stays down, so the first
     * article really rung up is the one that asks.
     */
    @Test
    void anEmptyCartAsksNothing() {
        SurveyTriggerService service = newService();
        service.maybeFireOnTransactionStart(service.state);
        verifyNoInteractions(service.client);
        assertFalse(service.state.surveyTransactionStartAsked);
    }

    /**
     * LC-03-07-01, the silent arm: a node that answers nothing leaves the
     * sale exactly as it was — the flag is still consumed, so the transaction
     * asks once and not once per mutation.
     */
    @Test
    void aSilentNodeConsumesTheAskAndDisturbsNothing() {
        SurveyTriggerService service = newService();
        service.state.ticket.items.add(new TicketState.TicketItem(
                "123", null, "L", java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                java.math.BigDecimal.ZERO));
        when(service.client.fetchPending("TRANSACTION_START")).thenReturn(null);
        service.maybeFireOnTransactionStart(service.state);
        verify(service.homeService, never()).receiveBackOfficeMessage(any(), any());
        assertTrue(service.state.surveyTransactionStartAsked);
    }

    /**
     * LC-03-07-02: the end-of-transaction survey is asked where the paid
     * ticket closes, and the moment names itself on the ask.
     */
    @Test
    void theTransactionEndSurveyIsAskedAtTheClose() {
        SurveyTriggerService service = newService();
        when(service.client.fetchPending("TRANSACTION_END")).thenReturn(
                new BackOfficeMessageClient.PendingMessage(
                        null, "Ticket OK ?", "Oui;Non", null, false, null, null));
        service.fireOnTransactionEnd(service.state);
        verify(service.client).fetchPending("TRANSACTION_END");
        verify(service.homeService).receiveBackOfficeMessage(eq(""), any());
    }

    /**
     * LC-03-07-03: the scan asks for the survey its code names, by id, and
     * the banner machinery — type, obligation, mask, list — travels with it.
     */
    @Test
    void theScanAsksForTheSurveyItsCodeNames() {
        SurveyTriggerService service = newService();
        when(service.client.fetchSurvey(12L)).thenReturn(
                new BackOfficeMessageClient.PendingMessage(
                        null, "Code postal ?", null, "POSTAL_CODE", true, "CCCCC",
                        "33980;62100"));
        service.fireByScan(service.state, 12L);
        verify(service.client).fetchSurvey(12L);
        ArgumentCaptor<PosState.BackOfficeSurvey> captor =
                ArgumentCaptor.forClass(PosState.BackOfficeSurvey.class);
        verify(service.homeService).receiveBackOfficeMessage(isNull(), captor.capture());
        PosState.BackOfficeSurvey survey = captor.getValue();
        assertEquals("POSTAL_CODE", survey.responseType);
        assertTrue(survey.required);
        assertEquals("CCCCC", survey.postalFormat);
        assertEquals(java.util.List.of("33980", "62100"), survey.postalCodes);
        assertFalse(survey.isOptionsType());
        assertFalse(survey.isInformative());
    }

    /**
     * The plain notice of a scanned code is applied with NO survey — the scan
     * of a notice id is the shop's prerogative, not the register's.
     */
    @Test
    void aScannedNoticeCarriesNoSurvey() {
        SurveyTriggerService service = newService();
        when(service.client.fetchSurvey(3L)).thenReturn(
                new BackOfficeMessageClient.PendingMessage(
                        "Nettoyage allée 4", null, null, null, false, null, null));
        service.fireByScan(service.state, 3L);
        verify(service.homeService).receiveBackOfficeMessage(eq("Nettoyage allée 4"), isNull());
    }

    /**
     * The null type reads as OPTIONS and the INFORMATIVE one as itself — the
     * two readings of the banner's arms.
     */
    @Test
    void theTypeArmsReadAsOptionsAndInformative() {
        PosState.BackOfficeSurvey legacy = new PosState.BackOfficeSurvey();
        legacy.question = "Q";
        assertTrue(legacy.isOptionsType());
        assertFalse(legacy.isInformative());
        PosState.BackOfficeSurvey informative = new PosState.BackOfficeSurvey();
        informative.question = "Q";
        informative.responseType = "informative";
        assertFalse(informative.isOptionsType());
        assertTrue(informative.isInformative());
        PosState.BackOfficeSurvey options = new PosState.BackOfficeSurvey();
        options.question = "Q";
        options.responseType = "OPTIONS";
        assertTrue(options.isOptionsType());
        assertFalse(options.isInformative());
        assertNull(legacy.postalFormat);
    }
}
