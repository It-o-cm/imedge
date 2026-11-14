package com.intermarche.edge.service.sync;

import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The CENTRALISED BURN of the vouchers (BO-11-01-18): the store node, which
 * holds the closed sales of every register of the shop, tells whether a
 * voucher was already consumed — on which register, on which ticket — so a
 * GS1 coupon presented at a second lane is refused there rather than paid
 * twice.
 *
 * <p>A voucher is BURNED once a CLOSED ticket of the shop settled it. A voucher
 * read but left out of the payment, or registered on a sale that was then
 * abandoned, burned nothing: the burn follows the money, not the scan.
 */
@ApplicationScoped
public class VoucherBurnService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(VoucherBurnService.class);

    /** The query of the closed sales that settled a voucher number. */
    static final String BURN_QUERY = "select t from Ticket t join t.payments p"
            + " where treat(p as VoucherPayment).voucherNumber = ?1 and t.status = ?2"
            + " order by t.closingDate";

    /**
     * Where a voucher was burned.
     *
     * @param number the voucher number
     * @param terminalId the register that settled it
     * @param ticketNumber the ticket it settled
     * @param burnedAt when that ticket was closed, ISO-8601 to the second, or null
     */
    public record Burn(String number, String terminalId, String ticketNumber, String burnedAt) {
    }

    /**
     * Finds the first closed sale of the shop that settled a voucher.
     *
     * @param number the voucher number, possibly null or blank
     * @return the burn, or null when no closed sale settled it
     */
    @Transactional
    public Burn findBurn(String number) {
        LOGGER.info("Entering method findBurn");
        if (number == null || number.isBlank()) {
            LOGGER.info("Exiting method findBurn: no number");
            return null;
        }
        String trimmed = number.trim();
        Ticket ticket = Ticket.<Ticket>find(BURN_QUERY, trimmed, Ticket.TicketStatus.CLOSED).firstResult();
        LOGGER.info("Exiting method findBurn");
        return ticket == null ? null : new Burn(trimmed, ticket.terminalId, ticket.ticketNumber,
                ticket.closingDate == null ? null
                : ticket.closingDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
    }

    /**
     * Keeps, among the given codes, the ones a closed sale of the shop burned.
     *
     * @param codes the codes, possibly holding nulls
     * @return the burned codes, in the given order
     */
    public Set<String> burnedAmong(Collection<String> codes) {
        Set<String> burned = new LinkedHashSet<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) {
                continue;
            }
            List<Ticket> tickets = Ticket.<Ticket>find(BURN_QUERY, code.trim(), Ticket.TicketStatus.CLOSED).list();
            if (!tickets.isEmpty()) {
                burned.add(code);
            }
        }
        return burned;
    }
}
