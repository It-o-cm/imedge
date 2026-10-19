package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The ONE ticket search engine (EXP-47), shared by the duplicata (reprint) and
 * the return: it finds CLOSED sale tickets by number, by date, or by amount.
 *
 * <p>Written once and branched both ways so the two screens can never search a
 * closed ticket differently. Register-local by construction: this node's database
 * holds only its own store's tickets, so the store boundary is honoured (a ticket
 * from another store is simply not here) while another till or another day of the
 * SAME store — once consolidated — are reachable. A retention window bounds the
 * search the same way the return already did.
 *
 * <p>The three criteria are exposed both explicitly ({@link #searchClosed}) and
 * through a single free-text field ({@link #searchByPattern}), because the return
 * screen presents one keypad: a value shaped like an amount searches the amount, a
 * value shaped like a date searches the day, anything else searches the number.
 */
@ApplicationScoped
public class TicketSearchService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TicketSearchService.class);

    /** How far back a closed ticket stays searchable when nothing is administered (days). */
    static final int RETENTION_DAYS = 30;

    /** The back-office parameters, for the administered search depth (BO-10-07-16). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * The depth this register searches a closed ticket on, in days
     * (BO-10-07-16).
     *
     * <p>The administered value when the shop posed one, and the built-in
     * thirty days otherwise — including when a register was assembled without a
     * settings service, which only happens in a test harness that is not
     * exercising this rule.
     *
     * <p>Package-visible so its three legs are exercised directly rather than
     * through a search whose limit is a moving instant.
     *
     * @return the depth in days, always strictly positive
     */
    int searchDays() {
        if (posSettingsService == null) {
            return RETENTION_DAYS;
        }
        int administered = posSettingsService.ticketSearchDays();
        return administered > 0 ? administered : RETENTION_DAYS;
    }

    /** The shortest free-text pattern accepted, to avoid returning the world. */
    static final int MIN_PATTERN = 3;

    /**
     * Searches the CLOSED tickets by exactly one criterion — the first non-null of
     * number, date, amount. All null (or a blank number) returns an empty list.
     *
     * @param number a ticket-number fragment, or null
     * @param date a sale day, or null
     * @param amount a tax-included total, or null
     * @return the matching CLOSED tickets within the retention window, newest first
     */
    public List<Ticket> searchClosed(String number, LocalDate date, BigDecimal amount) {
        LOGGER.info("Entering method searchClosed with number: " + number + ", date: " + date + ", amount: " + amount);
        List<Ticket> results = searchClosed(number, date, amount, false);
        LOGGER.info("Exiting method searchClosed with " + results.size() + " result(s)");
        return results;
    }

    /**
     * Searches the CLOSED tickets by exactly one criterion, optionally keeping
     * only the tickets NO client is attached to — the selection pool of the
     * reassignment (LC-03-06-02): a ticket that already carries a card has
     * had its advantages, and the questionnaire forbids offering it again.
     *
     * @param number a ticket-number fragment, or null
     * @param date a sale day, or null
     * @param amount a tax-included total, or null
     * @param unassignedOnly true to keep only the tickets with no fidelity card
     * @return the matching CLOSED tickets within the retention window, newest first
     */
    public List<Ticket> searchClosed(String number, LocalDate date, BigDecimal amount,
            boolean unassignedOnly) {
        LOGGER.info("Entering method searchClosed with number: " + number + ", date: " + date + ", amount: " + amount + ", unassignedOnly: " + unassignedOnly);
        LocalDateTime limit = LocalDateTime.now().minusDays(searchDays());
        String cardFilter = unassignedOnly ? " and fidelityCard is null" : "";
        List<Ticket> results;
        if (amount != null) {
            results = Ticket.find(
                    "status = ?1 and totalIncludingTax = ?2 and creationDate > ?3" + cardFilter
                            + " order by creationDate desc",
                    Ticket.TicketStatus.CLOSED, amount, limit).list();
        } else if (date != null) {
            results = Ticket.find(
                    "status = ?1 and creationDate >= ?2 and creationDate < ?3 and creationDate > ?4" + cardFilter
                            + " order by creationDate desc",
                    Ticket.TicketStatus.CLOSED, date.atStartOfDay(), date.plusDays(1).atStartOfDay(), limit).list();
        } else if (number != null && !number.isBlank()) {
            results = Ticket.find(
                    "status = ?1 and lower(ticketNumber) like lower(?2) and creationDate > ?3" + cardFilter
                            + " order by creationDate desc",
                    Ticket.TicketStatus.CLOSED, "%" + number.trim() + "%", limit).list();
        } else {
            results = unassignedOnly
                    ? Ticket.find("status = ?1 and creationDate > ?2 and fidelityCard is null"
                            + " order by creationDate desc",
                            Ticket.TicketStatus.CLOSED, limit).list()
                    : List.of();
        }
        LOGGER.info("Exiting method searchClosed with " + results.size() + " result(s)");
        return results;
    }

    /**
     * Searches from a single free-text field: an amount ({@code 12,50} / {@code 12.50})
     * searches the amount, a {@code dd/MM/yyyy} date searches the day, anything else at
     * least {@value #MIN_PATTERN} characters long searches the number. A shorter, un-typed
     * pattern returns nothing.
     *
     * @param pattern the raw value keyed on the search screen
     * @return the matching CLOSED tickets, newest first
     */
    public List<Ticket> searchByPattern(String pattern) {
        LOGGER.info("Entering method searchByPattern with pattern: " + pattern);
        List<Ticket> results = searchByPattern(pattern, false);
        LOGGER.info("Exiting method searchByPattern with " + results.size() + " result(s)");
        return results;
    }

    /**
     * Searches from a single free-text field, optionally keeping only the
     * tickets no client is attached to — the reassignment pool of
     * LC-03-06-02: an amount ({@code 12,50} / {@code 12.50}) searches the
     * amount, a {@code dd/MM/yyyy} date searches the day, anything else at
     * least {@value #MIN_PATTERN} characters long searches the number. A
     * shorter, un-typed pattern returns nothing.
     *
     * @param pattern the raw value keyed on the search screen
     * @param unassignedOnly true to keep only the tickets with no fidelity card
     * @return the matching CLOSED tickets, newest first
     */
    public List<Ticket> searchByPattern(String pattern, boolean unassignedOnly) {
        LOGGER.info("Entering method searchByPattern with pattern: " + pattern + ", unassignedOnly: " + unassignedOnly);
        if (pattern == null || pattern.trim().length() < MIN_PATTERN) {
            LOGGER.info("Exiting method searchByPattern");
            return List.of();
        }
        String trimmed = pattern.trim();
        BigDecimal amount = asAmount(trimmed);
        if (amount != null) {
            LOGGER.info("Exiting method searchByPattern");
            return searchClosed(null, null, amount, unassignedOnly);
        }
        LocalDate date = asDate(trimmed);
        if (date != null) {
            LOGGER.info("Exiting method searchByPattern");
            return searchClosed(null, date, null, unassignedOnly);
        }
        LOGGER.info("Exiting method searchByPattern");
        return searchClosed(trimmed, null, null, unassignedOnly);
    }

    /**
     * Reads a value as a monetary amount when it is a decimal carrying a separator
     * (comma or dot) — a bare integer is a ticket number, not an amount.
     *
     * @param value the trimmed value
     * @return the amount, or null when the value is not a decimal amount
     */
    private BigDecimal asAmount(String value) {
        if (!value.matches("\\d+[.,]\\d{1,2}")) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads a value as a {@code dd/MM/yyyy} date.
     *
     * @param value the trimmed value
     * @return the date, or null when the value is not such a date
     */
    private LocalDate asDate(String value) {
        if (!value.matches("\\d{2}/\\d{2}/\\d{4}")) {
            return null;
        }
        try {
            return LocalDate.parse(value,
                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
