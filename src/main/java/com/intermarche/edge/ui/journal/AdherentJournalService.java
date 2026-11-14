package com.intermarche.edge.ui.journal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.intermarche.edge.domain.deploy.UplinkedTicket;
import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.PdvHistory;
import com.intermarche.edge.service.deploy.JournalUplinkService;
import com.intermarche.edge.ui.supervision.ConsoleFilter;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Read side of the electronic journal regrouped by adhérent (BO-02-05-05), on
 * the central node.
 *
 * <p>It reads the tickets the store nodes pushed up ({@link UplinkedTicket}):
 * the echelon filters — adhérent, country, enseigne, point of sale — resolve,
 * through the organisation the central node administers, to the numbers of
 * the points of sale kept, former numbers included (a renumbered point of sale
 * keeps its past tickets under its former number, BO-02-05-01); the journal
 * criteria then narrow the tickets, one page at a time, each search preceded
 * by its own count. The detail is the one the store's journal shows,
 * materialized by the store and rebuilt here from what it sent.
 */
@ApplicationScoped
public class AdherentJournalService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdherentJournalService.class);

    /** The number of rows one page shows. */
    static final int PAGE_SIZE = 100;

    /** The label of a point of sale attached to no adhérent. */
    static final String NO_ADHERENT = "—";

    /** French date format. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** French time format. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Reads the detail the store materialized. */
    @Inject
    ObjectMapper objectMapper;

    /** A JPQL condition and its parameters. */
    static final class Where {

        /** The conditions, AND-ed. */
        final String clause;

        /** The named parameters. */
        final Map<String, Object> params;

        /**
         * Builds a condition.
         *
         * @param clause the conditions
         * @param params the parameters
         */
        Where(String clause, Map<String, Object> params) {
            this.clause = clause;
            this.params = params;
        }
    }

    /**
     * Searches the tickets of the points of sale the criteria keep.
     *
     * @param criteria the criteria
     * @return one page of rows and the total count
     */
    @Transactional
    public JournalPage<AdherentJournalRow> search(AdherentJournalCriteria criteria) {
        LOGGER.info("Entering method search with adherent: " + criteria.adherent);
        Set<String> numbers = numbers(criteria);
        if (numbers != null && numbers.isEmpty()) {
            LOGGER.info("Exiting method search: no point of sale kept");
            return new JournalPage<>(List.of(), 1, PAGE_SIZE, 0);
        }
        Where where = where(criteria, numbers);
        PanacheQuery<UplinkedTicket> query = UplinkedTicket.find(where.clause,
                Sort.descending("ticketDate").and("id", Sort.Direction.Descending), where.params);
        long total = query.count();
        int pages = JournalPage.pageCount(total, PAGE_SIZE);
        int number = Math.max(1, Math.min(criteria.page, pages));
        Map<String, Pdv> holders = new HashMap<>();
        List<AdherentJournalRow> rows = new ArrayList<>();
        for (UplinkedTicket ticket : query.page(Page.of(number - 1, PAGE_SIZE)).list()) {
            Pdv holder = holders.computeIfAbsent(ticket.pdvNumber, AdherentJournalService::holderOf);
            rows.add(new AdherentJournalRow(ticket.id, adherentOf(holder), ticket.pdvNumber,
                    nameOf(holder, ticket.pdvNumber), ticket.terminalId, ticket.ticketNumber,
                    ticket.cashierBadge == null ? "" : ticket.cashierBadge, ticket.ticketDate.format(DATE),
                    ticket.ticketDate.format(TIME), euros(ticket.totalIncludingTax), ticket.itemCount, ticket.kind));
        }
        LOGGER.info("Exiting method search");
        return new JournalPage<>(rows, number, PAGE_SIZE, total);
    }

    /**
     * The numbers of the points of sale the echelon filters keep.
     *
     * @param criteria the criteria
     * @return the numbers, former ones included, or null when no echelon filter is set
     */
    Set<String> numbers(AdherentJournalCriteria criteria) {
        if (!criteria.narrowsEchelons()) {
            return null;
        }
        Map<String, String> countries = new HashMap<>();
        for (Enseigne enseigne : Enseigne.listAllOrdered()) {
            countries.put(enseigne.code, enseigne.countryCode);
        }
        Set<String> numbers = new LinkedHashSet<>();
        for (Pdv pdv : Pdv.listAllOrdered()) {
            if (ConsoleFilter.keeps(criteria.adherent, pdv.adherentCode)
                    && ConsoleFilter.keeps(criteria.enseigne, pdv.enseigneCode)
                    && ConsoleFilter.keeps(criteria.country, countries.get(pdv.enseigneCode))) {
                // A renumbered point of sale is found by its current number
                // AND by any of its former ones: its past tickets were filed
                // under the number it carried then (BO-02-05-01).
                Set<String> owned = JournalUplinkService.numbersOf(pdv.pdvNumber);
                if (criteria.pdv == null || owned.contains(criteria.pdv)) {
                    numbers.addAll(owned);
                }
            }
        }
        return numbers;
    }

    /**
     * Builds the condition of a search.
     *
     * @param criteria the criteria
     * @param numbers the points of sale kept, or null for all
     * @return the condition
     */
    static Where where(AdherentJournalCriteria criteria, Set<String> numbers) {
        StringBuilder clause = new StringBuilder("ticketDate is not null");
        Map<String, Object> params = new LinkedHashMap<>();
        if (numbers != null) {
            clause.append(" and pdvNumber in :pdvs");
            params.put("pdvs", numbers);
        }
        if (criteria.dateFrom != null) {
            clause.append(" and ticketDate >= :from");
            params.put("from", criteria.dateFrom.atStartOfDay());
        }
        if (criteria.dateTo != null) {
            clause.append(" and ticketDate < :to");
            params.put("to", criteria.dateTo.plusDays(1).atStartOfDay());
        }
        if (criteria.terminal != null) {
            clause.append(" and upper(terminalId) = :terminal");
            params.put("terminal", criteria.terminal.toUpperCase());
        }
        if (criteria.ticket != null) {
            clause.append(" and upper(ticketNumber) like :ticket");
            params.put("ticket", "%" + criteria.ticket.toUpperCase() + "%");
        }
        if (criteria.amountMin != null) {
            clause.append(" and totalIncludingTax >= :amountMin");
            params.put("amountMin", criteria.amountMin);
        }
        if (criteria.amountMax != null) {
            clause.append(" and totalIncludingTax <= :amountMax");
            params.put("amountMax", criteria.amountMax);
        }
        if (criteria.kind != null) {
            clause.append(" and kind = :kind");
            params.put("kind", criteria.kind);
        }
        return new Where(clause.toString(), params);
    }

    /**
     * Opens one ticket.
     *
     * @param id the row's database id
     * @return the detail, or null when no ticket has that id
     */
    @Transactional
    public AdherentJournalDetail detail(Long id) {
        LOGGER.info("Entering method detail with id: " + id);
        UplinkedTicket row = UplinkedTicket.findById(id);
        if (row == null) {
            LOGGER.info("Exiting method detail: unknown ticket");
            return null;
        }
        Pdv holder = holderOf(row.pdvNumber);
        JsonNode node = read(row.detail);
        List<JournalTicketDetail.Line> lines = new ArrayList<>();
        for (JsonNode line : node.path("lines")) {
            lines.add(new JournalTicketDetail.Line(line.path("number").asInt(), text(line, "label"),
                    nullable(line, "ean"), nullable(line, "plu"), text(line, "quantity"), text(line, "total"),
                    nullable(line, "modifier"), line.path("cancelled").asBoolean()));
        }
        List<JournalTicketDetail.Payment> payments = new ArrayList<>();
        for (JsonNode payment : node.path("payments")) {
            payments.add(new JournalTicketDetail.Payment(text(payment, "method"), text(payment, "amount"),
                    nullable(payment, "entryMode"), nullable(payment, "guaranteeAuthorization"),
                    nullable(payment, "guaranteeProvider")));
        }
        List<JournalTicketDetail.FidelityGroup> groups = new ArrayList<>();
        for (JsonNode group : node.path("fidelityGroups")) {
            List<JournalTicketDetail.FidelityAdvantage> advantages = new ArrayList<>();
            for (JsonNode advantage : group.path("advantages")) {
                advantages.add(new JournalTicketDetail.FidelityAdvantage(text(advantage, "label"),
                        text(advantage, "amount")));
            }
            groups.add(new JournalTicketDetail.FidelityGroup(text(group, "header"), advantages));
        }
        JournalTicketDetail ticket = new JournalTicketDetail(row.ticketNumber, row.terminalId, row.status,
                row.ticketDate.format(DATE), row.ticketDate.format(TIME), text(node, "cashier"),
                row.cashierBadge == null ? "" : row.cashierBadge, text(node, "store"), text(node, "fidelityCard"),
                euros(row.totalIncludingTax), text(node, "totalExcludingTax"), text(node, "totalVat"),
                lines, payments, groups);
        LOGGER.info("Exiting method detail");
        return new AdherentJournalDetail(adherentOf(holder), row.pdvNumber, nameOf(holder, row.pdvNumber),
                row.kind, row.receivedAt.format(DATE) + " " + row.receivedAt.format(TIME),
                row.printed == null ? "" : row.printed, ticket);
    }

    /**
     * The adhérents the organisation knows, for the form.
     *
     * @return the adhérent codes, sorted
     */
    @Transactional
    public List<String> adherents() {
        Set<String> codes = new TreeSet<>();
        for (Pdv pdv : Pdv.listAllOrdered()) {
            if (pdv.adherentCode != null) {
                codes.add(pdv.adherentCode);
            }
        }
        return new ArrayList<>(codes);
    }

    /**
     * The countries, for the form.
     *
     * @return the countries, ordered
     */
    @Transactional
    public List<Country> countries() {
        return Country.listAllOrdered();
    }

    /**
     * The enseignes, for the form.
     *
     * @return the enseignes, ordered
     */
    @Transactional
    public List<Enseigne> enseignes() {
        return Enseigne.listAllOrdered();
    }

    /**
     * The points of sale, for the form.
     *
     * @return the points of sale, by number
     */
    @Transactional
    public List<Pdv> pdvs() {
        return Pdv.listAllOrdered();
    }

    /**
     * The point of sale that carries, or carried, a number.
     *
     * @param number the number a ticket is filed under
     * @return the point of sale, or null when the organisation does not know it
     */
    static Pdv holderOf(String number) {
        Pdv pdv = Pdv.findByNumber(number);
        if (pdv != null) {
            return pdv;
        }
        Long former = PdvHistory.formerHolderOf(number);
        return former == null ? null : Pdv.findById(former);
    }

    /**
     * The adhérent a row shows.
     *
     * @param holder the point of sale, possibly null
     * @return its adhérent, or a dash
     */
    static String adherentOf(Pdv holder) {
        return holder == null || holder.adherentCode == null ? NO_ADHERENT : holder.adherentCode;
    }

    /**
     * The name a row shows: the point of sale's, a former number saying so.
     *
     * @param holder the point of sale, possibly null
     * @param number the number the ticket is filed under
     * @return the name, empty when unknown
     */
    static String nameOf(Pdv holder, String number) {
        if (holder == null) {
            return "";
        }
        return number.equals(holder.pdvNumber) ? holder.name
                : holder.name + " (ancien n°, actuel " + holder.pdvNumber + ")";
    }

    /**
     * Formats an amount with two decimals and a French comma.
     *
     * @param amount the amount, possibly null
     * @return the formatted amount
     */
    static String euros(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /**
     * Reads the stored detail, an unreadable one as an empty one.
     *
     * @param json the JSON, possibly null
     * @return the tree, a missing node when absent or unreadable
     */
    JsonNode read(String json) {
        if (json == null) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException unreadable) {
            return MissingNode.getInstance();
        }
    }

    /**
     * Reads a text field, empty when absent.
     *
     * @param node the object
     * @param field the field
     * @return the text, never null
     */
    static String text(JsonNode node, String field) {
        String value = nullable(node, field);
        return value == null ? "" : value;
    }

    /**
     * Reads a text field, null when absent.
     *
     * @param node the object
     * @param field the field
     * @return the text, or null
     */
    static String nullable(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
