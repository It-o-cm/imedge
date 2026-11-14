package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.setting.StoreMessage;
import com.intermarche.edge.domain.setting.StoreMessageAck;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SurveyReport} and {@link PostalCodeReport}: the
 * answers of each survey with their customers, the sales of the tickets they
 * name and their shares, the surveys picked, the recall of each survey in the
 * preamble, and the metadata; the postal-code state keeps only the surveys
 * asking a postal code.
 */
class SurveyReportTest {

    /** A moment of the period. */
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 20, 10, 0);

    /**
     * Builds a survey.
     *
     * @param id the id
     * @param type the response type, possibly null
     * @param options the options, possibly null
     * @return the survey
     */
    static StoreMessage survey(long id, StoreMessage.ResponseType type, String options) {
        StoreMessage survey = new StoreMessage();
        survey.id = id;
        survey.question = "Question " + id;
        survey.responseType = type;
        survey.options = options;
        return survey;
    }

    /**
     * Builds an answer.
     *
     * @param survey the survey's id
     * @param answer the answer
     * @param ticket the ticket's number, possibly null
     * @return the answer
     */
    private StoreMessageAck ack(long survey, String answer, String ticket) {
        StoreMessageAck ack = new StoreMessageAck();
        ack.messageId = survey;
        ack.answer = answer;
        ack.ticketNumber = ticket;
        ack.confirmedAt = AT;
        return ack;
    }

    /**
     * Builds a ticket.
     *
     * @param number the number
     * @param total the total, possibly null
     * @param points the points earned, possibly null
     * @return the ticket
     */
    private Ticket ticket(String number, String total, String points) {
        Ticket ticket = new Ticket();
        ticket.ticketNumber = number;
        ticket.totalIncludingTax = total == null ? null : new BigDecimal(total);
        ticket.fidelityEarnTotal = points == null ? null : new BigDecimal(points);
        ticket.itemCount = 3;
        return ticket;
    }

    /**
     * The stub of the surveys, answers, tickets and period sales.
     *
     * @param periodSales the period's sales, or null for a null sum
     * @return the stub
     */
    private QueryStub stub(BigDecimal periodSales) {
        return new QueryStub()
                .answer("from StoreMessage m", List.of(survey(1, StoreMessage.ResponseType.OPTIONS, "Oui;Non"),
                        survey(2, StoreMessage.ResponseType.POSTAL_CODE, null)))
                .answer("from StoreMessageAck a", List.of(ack(1, "Oui", "T1"), ack(1, "Oui", "T9"),
                        ack(1, "Non", null), ack(2, "75001", "T2"), ack(3, "Autre", null)))
                .answer("t.ticketNumber in :numbers", List.of(ticket("T1", "40.00", "4"), ticket("T2", null, null)))
                .answer("select sum(t.totalIncludingTax)", Arrays.asList(periodSales));
    }

    /**
     * Per survey and answer: the customers and their share, the sales of the
     * tickets named and their share of the period, the basket, the articles
     * and the points; an answer to an unknown survey is left out.
     */
    @Test
    void everyAnswerOfEverySurvey() {
        SurveyReport report = new SurveyReport();
        report.entityManager = stub(new BigDecimal("200.00")).entityManager;
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(3, rows.size());
        assertEquals(Arrays.asList("1", "Question 1", "Non", 1L, new BigDecimal("33.3"), BigDecimal.ZERO,
                new BigDecimal("0.0"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), rows.get(0));
        assertEquals(Arrays.asList("1", "Question 1", "Oui", 2L, new BigDecimal("66.7"), new BigDecimal("40.00"),
                new BigDecimal("20.0"), new BigDecimal("40.00"), new BigDecimal("3.00"), new BigDecimal("4.00")),
                rows.get(1));
        assertEquals("75001", rows.get(2).get(2));
        assertEquals(BigDecimal.ZERO, rows.get(2).get(5));
        assertEquals(new BigDecimal("0.00"), rows.get(2).get(9));
    }

    /**
     * The surveys picked restrict the answers; a period without sale reads no
     * share of sales; a period without answer asks for no ticket.
     */
    @Test
    void picksAndEmptyPeriods() {
        SurveyReport report = new SurveyReport();
        report.entityManager = stub(null).entityManager;
        ReportCriteria criteria = new ReportCriteria();
        criteria.picks.add("2");
        List<List<Object>> rows = report.rows(criteria);
        assertEquals(1, rows.size());
        assertEquals(BigDecimal.ZERO, rows.get(0).get(6));
        QueryStub quiet = new QueryStub();
        report.entityManager = quiet.entityManager;
        assertTrue(report.rows(new ReportCriteria()).isEmpty());
        assertFalse(quiet.asked(":numbers"));
        assertEquals(BigDecimal.ZERO, SurveyReport.share(BigDecimal.ONE, BigDecimal.ZERO));
    }

    /**
     * The choices list the surveys; the preamble recalls the surveys picked:
     * their answers, their dates and whether they are active.
     */
    @Test
    void choicesAndRecall() {
        SurveyReport report = new SurveyReport();
        report.entityManager = stub(null).entityManager;
        assertEquals(List.of("1:1 — Question 1", "2:2 — Question 2"), report.pickChoices());
        ReportCriteria criteria = new ReportCriteria();
        criteria.picks.add("1");
        assertEquals(List.of("Sondage n° 1 — Question 1 — réponses : Oui;Non — du — au — — actif",
                        "Sondage n° 1 — moment : Immédiat — jours : tous les jours — heures : 00:00 – 24:00"),
                report.preamble(criteria));
        StoreMessage dated = survey(3, StoreMessage.ResponseType.POSTAL_CODE, " ");
        dated.startsAt = AT;
        dated.endsAt = AT.plusDays(1);
        dated.active = false;
        assertEquals("Sondage n° 3 — Question 3 — réponses : Saisie d'un code postal — du 20/10/2026 10:00"
                + " au 21/10/2026 10:00 — inactif", SurveyReport.recall(dated));
        assertEquals("Sondage n° 4 — Question 4 — réponses :  — du — au — — actif",
                SurveyReport.recall(survey(4, null, null)));
    }

    /**
     * The display recall names the moment, the days and the daily hours in the
     * edition's language; a survey without moment names none.
     */
    @Test
    void theDisplayRecallFollowsTheLanguage() {
        StoreMessage survey = survey(7, StoreMessage.ResponseType.YES_NO, null);
        survey.trigger = StoreMessage.Trigger.TRANSACTION_END;
        survey.activeDays = "1;2";
        survey.dailyFrom = java.time.LocalTime.of(8, 0);
        survey.dailyTo = java.time.LocalTime.of(12, 30);
        assertEquals("Sondage n° 7 — moment : En fin de transaction — jours : Lun, Mar — heures : 08:00 – 12:30",
                SurveyReport.display(survey, "fr"));
        assertEquals("Enquête nr. 7 — moment: " + com.intermarche.edge.ui.admin.BackOfficeMessages.in("nl")
                        .text("En fin de transaction") + " — dagen: Ma, Di — uren: 08:00 – 12:30",
                SurveyReport.display(survey, "nl"));
        survey.trigger = null;
        assertEquals("Sondage n° 7 — moment :  — jours : Lun, Mar — heures : 08:00 – 12:30",
                SurveyReport.display(survey, null));
    }

    /**
     * The postal-code state keeps the surveys asking a postal code and names
     * the answer column.
     */
    @Test
    void thePostalCodeState() {
        PostalCodeReport report = new PostalCodeReport();
        report.entityManager = stub(new BigDecimal("200.00")).entityManager;
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        assertEquals("75001", rows.get(0).get(2));
        assertEquals("postal-codes", report.code());
        assertEquals("Sondage codes postaux", report.label());
        assertEquals("BO-06-03-03", report.requirement());
        assertEquals("Code postal", report.columns().get(2).label());
        assertEquals(10, report.columns().size());
    }

    /**
     * The metadata of the survey state.
     */
    @Test
    void metadataDescribesTheReport() {
        SurveyReport report = new SurveyReport();
        assertEquals("surveys", report.code());
        assertEquals("État sondages clients", report.label());
        assertEquals("BO-06-03-02", report.requirement());
        assertEquals(ReportFamily.CUSTOMERS, report.family());
        assertEquals("Réponse", report.columns().get(2).label());
        assertEquals(3, report.chartColumn());
        assertEquals(2, report.chartLabelColumn());
        assertEquals("survey", report.breakKey());
        assertEquals("Sondages", report.pickLabel());
        assertTrue(report.filters().isEmpty());
    }
}
