package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.setting.StoreMessage;
import com.intermarche.edge.domain.setting.StoreMessageAck;
import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ÉTAT SONDAGES CLIENTS (BO-06-03-02): survey by survey — its number, the
 * question shown, the possible answers and the activation period recalled
 * above the list — and for each answer given, the number and share of the
 * customers who gave it, the sales of their tickets and their share of the
 * period's sales, the mean basket, the mean number of articles and the mean
 * loyalty points earned on those tickets.
 *
 * <p>An answer is tied to its customer's TICKET: the register names, with the
 * answer, the ticket being rung up or the one it just closed, and the node reads
 * that ticket's amount, articles and points. An answer an older register sent
 * without its ticket is still counted among the customers, with no sales.
 *
 * <p>The edition is restricted to one survey, several or all, over a date or a
 * range of dates.
 */
@ApplicationScoped
public class SurveyReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SurveyReport.class);

    /** The day format of the activation period. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** The persistence session the queries run on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "surveys";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État sondages clients";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-03-02";
    }

    /**
     * {@inheritDoc}
     *
     * @return the customers family
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.CUSTOMERS;
    }

    /**
     * {@inheritDoc}
     *
     * @return the survey, the answer and its figures
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("survey", "N° du sondage"),
                ReportColumn.text("question", "Question"),
                ReportColumn.text("answer", "Réponse"),
                ReportColumn.count("customers", "Nombre de clients"),
                ReportColumn.percent("customerShare", "% de clients"),
                ReportColumn.amount("sales", "Montant des ventes"),
                ReportColumn.percent("salesShare", "% des ventes"),
                ReportColumn.average("basket", "Panier moyen"),
                ReportColumn.decimal("items", "Nb moyen d'articles"),
                ReportColumn.decimal("points", "Nb moyen de points fidélité gagnés"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the number of customers
     */
    @Override
    public int chartColumn() {
        return 3;
    }

    /**
     * {@inheritDoc}
     *
     * @return the answer
     */
    @Override
    public int chartLabelColumn() {
        return 2;
    }

    /**
     * {@inheritDoc}
     *
     * @return the survey number: a subtotal closes each survey
     */
    @Override
    public String breakKey() {
        return "survey";
    }

    /**
     * {@inheritDoc}
     *
     * @return what the survey choice is called on the screen
     */
    @Override
    public String pickLabel() {
        return "Sondages";
    }

    /**
     * {@inheritDoc}
     *
     * @return every survey, by number
     */
    @Override
    public List<String> pickChoices() {
        List<String> choices = new ArrayList<>();
        for (StoreMessage survey : surveys()) {
            choices.add(survey.id + ":" + survey.id + " — " + survey.question);
        }
        return choices;
    }

    /**
     * {@inheritDoc}
     *
     * @return no register criterion: an answer is the customer's
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.noneOf(ReportFilter.class);
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return per survey kept: its number, question, answers and activation period
     */
    @Override
    public List<String> preamble(ReportCriteria criteria) {
        List<String> lines = new ArrayList<>();
        for (StoreMessage survey : surveys()) {
            if (criteria.picked(String.valueOf(survey.id))) {
                lines.add(recall(survey));
                lines.add(display(survey, criteria.language));
            }
        }
        return lines;
    }

    /**
     * Recalls a survey in one line.
     *
     * @param survey the survey
     * @return its number, question, answers and activation period
     */
    static String recall(StoreMessage survey) {
        return "Sondage n° " + survey.id + " — " + survey.question
                + " — réponses : " + (survey.options == null || survey.options.isBlank()
                        ? (survey.responseType == null ? "" : survey.responseType.getLabel()) : survey.options)
                + " — du " + (survey.startsAt == null ? "—" : DAY.format(survey.startsAt))
                + " au " + (survey.endsAt == null ? "—" : DAY.format(survey.endsAt))
                + (survey.active ? " — actif" : " — inactif");
    }

    /**
     * Recalls when a survey is shown (BO-06-03-02): its display moment, its
     * days and its daily hours. Written straight in the edition's language —
     * the days are a list no single bundle sentence holds.
     *
     * @param survey the survey
     * @param language the edition's language, possibly null for French
     * @return the line, for example {@code Sondage n° 7 — moment : Immédiat — jours : Lun, Mar — heures : 08:00 – 12:00}
     */
    static String display(StoreMessage survey, String language) {
        com.intermarche.edge.ui.admin.BackOfficeMessages messages =
                com.intermarche.edge.ui.admin.BackOfficeMessages.in(language);
        List<String> days = new ArrayList<>();
        for (Integer day : survey.days()) {
            days.add(messages.get("t.r21v-jour-" + day));
        }
        DateTimeFormatter hour = DateTimeFormatter.ofPattern("HH:mm");
        return messages.get("p.r21rv-sondage-affichage")
                .replace("{0}", String.valueOf(survey.id))
                .replace("{1}", survey.trigger == null ? "" : messages.text(survey.trigger.getLabel()))
                .replace("{2}", days.isEmpty() ? messages.get("t.r21rv-tous-les-jours") : String.join(", ", days))
                .replace("{3}", survey.dailyFrom == null ? "00:00" : hour.format(survey.dailyFrom))
                .replace("{4}", survey.dailyTo == null ? "24:00" : hour.format(survey.dailyTo));
    }

    /**
     * Lists the surveys: the messages carrying a question.
     *
     * @return the surveys, by number
     */
    List<StoreMessage> surveys() {
        return entityManager.createQuery("select m from StoreMessage m where m.question is not null order by m.id",
                StoreMessage.class).getResultList();
    }

    /** One answer's running figures. */
    static final class Answer {

        /** The customers who gave it. */
        long customers;

        /** Their tickets found. */
        long tickets;

        /** Their sales. */
        BigDecimal sales = BigDecimal.ZERO;

        /** Their articles. */
        long items;

        /** Their loyalty points earned. */
        BigDecimal points = BigDecimal.ZERO;
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return per survey, one row per answer given
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        Map<Long, StoreMessage> surveys = new HashMap<>();
        for (StoreMessage survey : surveys()) {
            if (criteria.picked(String.valueOf(survey.id))) {
                surveys.put(survey.id, survey);
            }
        }
        List<StoreMessageAck> acks = entityManager.createQuery(
                        "select a from StoreMessageAck a where a.answer is not null and a.confirmedAt >= :from"
                                + " and a.confirmedAt < :to order by a.messageId, a.answer", StoreMessageAck.class)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to())
                .getResultList();
        Map<String, Ticket> tickets = tickets(acks);
        BigDecimal periodSales = periodSales(criteria);
        Map<Long, Map<String, Answer>> byAnswer = new TreeMap<>();
        Map<Long, Long> answered = new HashMap<>();
        for (StoreMessageAck ack : acks) {
            if (!surveys.containsKey(ack.messageId)) {
                continue;
            }
            Answer answer = byAnswer.computeIfAbsent(ack.messageId, k -> new TreeMap<>())
                    .computeIfAbsent(ack.answer, k -> new Answer());
            answer.customers++;
            answered.merge(ack.messageId, 1L, Long::sum);
            Ticket ticket = ack.ticketNumber == null ? null : tickets.get(ack.ticketNumber);
            if (ticket != null) {
                answer.tickets++;
                answer.sales = answer.sales.add(ticket.totalIncludingTax == null ? BigDecimal.ZERO
                        : ticket.totalIncludingTax);
                answer.items += ticket.itemCount;
                answer.points = answer.points.add(ticket.fidelityEarnTotal == null ? BigDecimal.ZERO
                        : ticket.fidelityEarnTotal);
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Answer>> survey : byAnswer.entrySet()) {
            long total = answered.get(survey.getKey());
            for (Map.Entry<String, Answer> entry : survey.getValue().entrySet()) {
                Answer answer = entry.getValue();
                rows.add(Arrays.asList(
                        String.valueOf(survey.getKey()),
                        surveys.get(survey.getKey()).question,
                        entry.getKey(),
                        answer.customers,
                        share(BigDecimal.valueOf(answer.customers), BigDecimal.valueOf(total)),
                        answer.sales,
                        share(answer.sales, periodSales),
                        CashierPerformanceReport.money(answer.sales, answer.tickets),
                        CashierStateReport.ratio(answer.items, answer.tickets),
                        answer.tickets == 0 ? BigDecimal.ZERO
                                : answer.points.divide(BigDecimal.valueOf(answer.tickets), 2, RoundingMode.HALF_UP)));
            }
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Reads the tickets the answers name.
     *
     * @param acks the answers
     * @return the tickets, by number
     */
    private Map<String, Ticket> tickets(List<StoreMessageAck> acks) {
        Set<String> numbers = new java.util.HashSet<>();
        for (StoreMessageAck ack : acks) {
            if (ack.ticketNumber != null) {
                numbers.add(ack.ticketNumber);
            }
        }
        Map<String, Ticket> tickets = new HashMap<>();
        if (numbers.isEmpty()) {
            return tickets;
        }
        for (Ticket ticket : entityManager.createQuery(
                        "select t from Ticket t where t.ticketNumber in :numbers", Ticket.class)
                .setParameter("numbers", numbers).getResultList()) {
            tickets.put(ticket.ticketNumber, ticket);
        }
        return tickets;
    }

    /**
     * Reads the sales of the period, the whole the answering customers' share
     * is read against.
     *
     * @param criteria the edition parameters
     * @return the closed tickets' total
     */
    private BigDecimal periodSales(ReportCriteria criteria) {
        List<BigDecimal> total = entityManager.createQuery(
                        "select sum(t.totalIncludingTax) from Ticket t where t.status = :closed"
                                + " and t.closingDate >= :from and t.closingDate < :to", BigDecimal.class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to())
                .getResultList();
        return total.isEmpty() || total.get(0) == null ? BigDecimal.ZERO : total.get(0);
    }

    /**
     * Computes a share in percent.
     *
     * @param part the part
     * @param whole the whole
     * @return the share at one decimal, zero for a zero whole
     */
    static BigDecimal share(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }
}
