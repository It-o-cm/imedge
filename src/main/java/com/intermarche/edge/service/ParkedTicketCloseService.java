package com.intermarche.edge.service;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.ParkedTicketRecord;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.session.DayEndParkedTicket;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.sync.RegisterCommand;
import com.intermarche.edge.service.sync.RegisterCommandService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the end of day does with the TICKETS LEFT ON HOLD (BO-09-01-05,
 * BO-09-01-08, BO-09-02-01).
 *
 * <p>RECORDED: the end of day — automatic or launched from the back office —
 * closes WITH the carts left parked and writes every one of them down at the
 * close, with what it carried, in a row of its own and in the electronic
 * journal. Their fate is no longer written only by the Z closing a register
 * performs whenever its operator gets to it.
 *
 * <p>RESET: the back office orders the registers holding parked carts to
 * cancel them — the « RAZ ticket en attente » — by hand from the end-of-day
 * screen, or at the automatic end of period where the store administers it.
 * The register carries the order out exactly as its Z closing purges a cart:
 * the ticket is cancelled and the purge is traced in its inalterable chain of
 * parked-ticket records.
 *
 * <p>WHAT IS ON HOLD, AS THE NODE KNOWS IT. A register keeps its parked carts
 * and pushes, for each putting on hold, resumption and purge, the signed
 * record of its chain; the node therefore knows a ticket is still on hold from
 * its last record — a putting on hold no resumption nor purge followed —
 * besides any parked ticket row a register pushed.
 */
@ApplicationScoped
public class ParkedTicketCloseService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ParkedTicketCloseService.class);

    /** The administered activation of the recording. */
    @Inject
    PosSettingsService posSettingsService;

    /** The order book the reset orders are left in. */
    @Inject
    RegisterCommandService registerCommandService;

    /** The electronic journal. */
    @Inject
    TechnicalEventService technicalEventService;

    /**
     * A ticket still on hold, as the end of day states it.
     *
     * @param terminalId the register it waits on
     * @param ticketNumber its number
     * @param cashier who parked it, or an empty string
     * @param itemCount how many articles it holds
     * @param amount its amount so far, or null when unknown
     * @param content its lines, one per row
     */
    public record Pending(String terminalId, String ticketNumber, String cashier, int itemCount,
                          BigDecimal amount, String content) {
    }

    /**
     * The tickets still on hold, of one register or of all, the deactivated
     * registers left out: the parked ticket rows first, then the tickets whose
     * last chain record puts them on hold.
     *
     * @param terminalId the register, or null for every register
     * @return the tickets on hold
     */
    public List<Pending> pending(String terminalId) {
        LOGGER.info("Entering method pending with terminalId: " + terminalId);
        List<Pending> found = new ArrayList<>();
        Set<String> numbers = new HashSet<>();
        for (Ticket ticket : parked(terminalId)) {
            found.add(of(ticket));
            numbers.add(ticket.ticketNumber);
        }
        for (ParkedTicketRecord record : pendingRecords()) {
            if (!numbers.contains(record.ticketNumber) && kept(record.terminalId, terminalId)) {
                found.add(new Pending(record.terminalId, record.ticketNumber, cashierOf(record.operator),
                        record.itemCount, record.totalAmount, record.content == null ? "" : record.content));
            }
        }
        LOGGER.info("Exiting method pending with " + found.size() + " ticket(s)");
        return found;
    }

    /**
     * Records every ticket on hold at the end of a day, once per ticket and
     * day (BO-09-01-05, BO-09-02-01).
     *
     * @param day the day whose end is recorded
     * @param recordedBy who or what ended the day
     * @return how many tickets were recorded by this call
     */
    @Transactional
    public int record(LocalDate day, String recordedBy) {
        LOGGER.info("Entering method record with day: " + day);
        if (!posSettingsService.periodParkedRecord()) {
            LOGGER.info("Exiting method record: recording off");
            return 0;
        }
        int recorded = 0;
        for (Pending ticket : pending(null)) {
            if (recordOf(day, ticket.ticketNumber()) != null) {
                continue;
            }
            DayEndParkedTicket row = row(day, ticket, recordedBy, DayEndParkedTicket.Fate.RECORDED);
            save(row);
            technicalEventService.log(TechnicalEvent.EventType.TICKET_PARKED,
                    "Clôture avec ticket en attente " + row.ticketNumber + " sur la caisse " + row.terminalId
                            + " : " + row.itemCount + " article(s), " + row.amount.toPlainString());
            recorded++;
        }
        if (recorded > 0) {
            technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                    "Fin de journée du " + day + " : " + recorded + " ticket(s) en attente enregistré(s)");
        }
        LOGGER.info("Exiting method record with " + recorded + " ticket(s)");
        return recorded;
    }

    /**
     * Orders the reset of the tickets on hold, of one register or of all
     * (BO-09-01-08).
     *
     * @param terminalId the register, or null for every register
     * @param orderedBy who ordered it
     * @param day the day the reset belongs to
     * @return how many tickets the reset covers
     */
    @Transactional
    public int raz(String terminalId, String orderedBy, LocalDate day) {
        LOGGER.info("Entering method raz with terminalId: " + terminalId);
        Set<String> registers = new LinkedHashSet<>();
        List<Pending> tickets = pending(terminalId);
        for (Pending ticket : tickets) {
            DayEndParkedTicket row = recordOf(day, ticket.ticketNumber());
            if (row == null) {
                row = row(day, ticket, orderedBy, DayEndParkedTicket.Fate.RAZ_ORDERED);
                save(row);
            }
            row.fate = DayEndParkedTicket.Fate.RAZ_ORDERED;
            registers.add(row.terminalId);
            technicalEventService.log(TechnicalEvent.EventType.TICKET_CANCELLED,
                    "Remise à zéro du ticket en attente " + row.ticketNumber + " demandée à la caisse "
                            + row.terminalId + " par " + orderedBy);
        }
        for (String register : registers) {
            registerCommandService.issue(register, RegisterCommand.CommandType.PURGE_PARKED_TICKETS,
                    orderedBy, "Remise à zéro des tickets en attente");
        }
        LOGGER.info("Exiting method raz with " + tickets.size() + " ticket(s)");
        return tickets.size();
    }

    /**
     * The latest records, for the end-of-day screen.
     *
     * @param limit how many
     * @return the records, most recent first
     */
    public List<DayEndParkedTicket> latest(int limit) {
        LOGGER.info("Entering method latest with limit: " + limit);
        LOGGER.info("Exiting method latest");
        return DayEndParkedTicket.latest(limit);
    }

    /**
     * Builds the record of a ticket on hold, every mandatory column set.
     *
     * @param day the day
     * @param ticket the ticket on hold
     * @param recordedBy who recorded it
     * @param fate what the end of day did with it
     * @return the record, not yet persisted
     */
    static DayEndParkedTicket row(LocalDate day, Pending ticket, String recordedBy, DayEndParkedTicket.Fate fate) {
        DayEndParkedTicket row = new DayEndParkedTicket();
        row.closeDay = day;
        row.recordedAt = LocalDateTime.now();
        row.recordedBy = recordedBy == null || recordedBy.isBlank() ? "back-office" : recordedBy.trim();
        row.terminalId = ticket.terminalId();
        row.ticketNumber = ticket.ticketNumber();
        row.cashier = ticket.cashier();
        row.itemCount = ticket.itemCount();
        row.amount = ticket.amount() == null ? BigDecimal.ZERO : ticket.amount();
        row.content = ticket.content();
        row.fate = fate;
        return row;
    }

    /**
     * A parked ticket row as a ticket on hold: its register, its cashier, its
     * lines still sold and its amount.
     *
     * @param ticket the parked ticket
     * @return the ticket on hold
     */
    static Pending of(Ticket ticket) {
        StringBuilder content = new StringBuilder();
        int count = 0;
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) {
                continue;
            }
            count++;
            content.append(plain(line.quantity)).append(" x ").append(line.productLabel)
                    .append(" = ").append(plain(line.totalPrice)).append('\n');
        }
        return new Pending(ticket.terminalId == null ? "" : ticket.terminalId, ticket.ticketNumber,
                ticket.cashier == null ? "" : ticket.cashier.getFullName().strip(), count,
                ticket.totalIncludingTax, content.toString());
    }

    /**
     * A quantity or an amount without trailing zeros.
     *
     * @param value the value, or null
     * @return its plain writing, "0" when null
     */
    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /**
     * The tickets on hold, the deactivated registers left out.
     *
     * @param terminalId the register, or null for every register
     * @return the tickets
     */
    List<Ticket> parked(String terminalId) {
        List<Ticket> tickets = new ArrayList<>();
        for (Ticket ticket : Ticket.<Ticket>list("status", Ticket.TicketStatus.PARKED)) {
            if (kept(ticket.terminalId, terminalId)) {
                tickets.add(ticket);
            }
        }
        return tickets;
    }

    /**
     * Whether a register is kept: in service, and the one asked for when one is.
     *
     * @param register the ticket's register
     * @param terminalId the register asked for, or null for every register
     * @return true when the register is kept
     */
    boolean kept(String register, String terminalId) {
        return !CheckoutPoint.isDeactivated(register) && (terminalId == null || terminalId.equals(register));
    }

    /**
     * The chain records of the tickets still on hold.
     *
     * @return the records
     */
    List<ParkedTicketRecord> pendingRecords() {
        return ParkedTicketRecord.listPending();
    }

    /**
     * The name of the cashier a badge names, the badge itself when the node
     * does not know it.
     *
     * @param badge the badge, or null
     * @return the name, or an empty string without badge
     */
    String cashierOf(String badge) {
        if (badge == null || badge.isBlank()) {
            return "";
        }
        Employee employee = Employee.find("badgeId", badge.trim()).firstResult();
        return employee == null ? badge.trim() : employee.getFullName().strip();
    }

    /**
     * The record of a ticket for a day.
     *
     * @param day the day
     * @param ticketNumber the ticket's number
     * @return the record, or null
     */
    DayEndParkedTicket recordOf(LocalDate day, String ticketNumber) {
        return DayEndParkedTicket.findFor(day, ticketNumber);
    }

    /**
     * Persists a record.
     *
     * @param row the record, its mandatory columns set
     */
    void save(DayEndParkedTicket row) {
        row.persist();
    }
}
