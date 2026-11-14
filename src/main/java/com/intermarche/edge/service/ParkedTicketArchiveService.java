package com.intermarche.edge.service;

import com.intermarche.edge.domain.sale.ParkedTicketRecord;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.sync.SyncOutbox;
import com.intermarche.edge.service.sync.register.SyncOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Keeps the INALTERABLE TRACE of the tickets put on hold (LC-04-01-03, article
 * 88 of the law against VAT fraud).
 *
 * <p>Each putting on hold, each resumption and each purge at the Z closing
 * writes a {@link ParkedTicketRecord}: the ticket's content at that moment,
 * signed and chained to the register's previous record, then pushed to the store
 * node, which keeps it beside the closed tickets. Nothing ever updates or deletes
 * a record, so a ticket on hold, resumed or not, leaves a trace that can be read
 * and checked as long as the store keeps its data.
 *
 * <p>It runs inside the caller's transaction: a putting on hold whose trace
 * cannot be written is not a putting on hold.
 */
@ApplicationScoped
public class ParkedTicketArchiveService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ParkedTicketArchiveService.class);

    /** Pushes each record to the store node. */
    @Inject
    SyncOutboxService syncOutboxService;

    /**
     * Records what happened to a ticket on hold.
     *
     * @param ticket the ticket, as it stands
     * @param event what happened
     * @param operator the operator's badge, or the purge's author
     * @return the signed record
     */
    public ParkedTicketRecord record(Ticket ticket, ParkedTicketRecord.Event event, String operator) {
        LOGGER.info("Entering method record with ticket: " + ticket.ticketNumber + ", event: " + event);
        ParkedTicketRecord record = new ParkedTicketRecord();
        record.terminalId = ticket.terminalId;
        record.ticketNumber = ticket.ticketNumber;
        record.event = event;
        record.eventDate = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        record.operator = operator;
        StringBuilder content = new StringBuilder();
        int count = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) {
                continue;
            }
            BigDecimal lineTotal = line.totalPrice == null ? BigDecimal.ZERO : line.totalPrice;
            content.append(plain(line.quantity)).append(" x ").append(line.productLabel)
                    .append(" @ ").append(plain(line.unitPrice)).append(" = ").append(plain(lineTotal))
                    .append('\n');
            // LS-03-04-04: the articles on hold, not the lines.
            count += line.returnLine ? 0 : line.articleUnits();
            total = total.add(lineTotal);
        }
        record.itemCount = count;
        record.totalAmount = total.setScale(2, RoundingMode.HALF_UP);
        record.content = content.toString();
        ParkedTicketRecord previous = ParkedTicketRecord.findLastByTerminal(ticket.terminalId);
        record.previousSignature = previous == null ? ParkedTicketRecord.GENESIS : previous.signature;
        record.signature = record.computeSignature();
        record.persist();
        syncOutboxService.enqueue(SyncOutbox.EntityType.PARKING, record.id);
        LOGGER.info("Exiting method record");
        return record;
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
}
