package com.intermarche.edge.service;

import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.ui.PriceModType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** LC-12-07-02: persisted operation counts and amounts shared by the screen and receipt. */
@ApplicationScoped
public class RevenueReportService {
    /** The inherited BackOffice rubric visibility. */
    @Inject PosSettingsService settings;

    /** One displayed rubric; a tender code is kept separately from the translatable rubric key.
     * @param key operator bundle key
     * @param tender tender code, empty for a general rubric
     * @param count operation count, not article quantity
     * @param amountFormatted recorded tax-included amount
     */
    public record Row(String key, String tender, int count, String amountFormatted) { }

    /** One counted group of persisted operations. */
    static class Total {
        int count;
        BigDecimal amount = BigDecimal.ZERO;

        /** LC-12-07-02: adds one operation, preserving its recorded monetary sign.
         * @param value operation amount
         */
        void add(BigDecimal value) {
            count++;
            amount = amount.add(value);
        }
    }

    /** LC-12-07-02: assembles the configured rubrics without changing any fiscal document.
     * @param session current reporting period
     * @param operatorId connected employee; null explicitly selects the full register
     * @return ordered rows shared by display and printing
     */
    public List<Row> rows(CashSession session, Long operatorId) {
        Map<String, Total> totals = new LinkedHashMap<>();
        for (String key : List.of("sales", "returns", "expenses", "cancellations", "abandoned",
                "price-overrides", "deposits", "withdrawals")) {
            totals.put(key, new Total());
        }
        Map<String, Total> tenders = new LinkedHashMap<>();
        List<Ticket> tickets = operatorId == null ? Ticket.list("session = ?1 order by id", session)
                : Ticket.list("session = ?1 and cashier.id = ?2 order by id", session, operatorId);
        for (Ticket ticket : tickets) {
            if (ticket.status == Ticket.TicketStatus.CANCELLED) {
                totals.get("abandoned").add(ticket.totalIncludingTax.abs());
            } else if (ticket.status == Ticket.TicketStatus.CLOSED) {
                BigDecimal returned = BigDecimal.ZERO;
                boolean hasReturn = false;
                boolean sold = false;
                for (TicketLine line : ticket.lines) {
                    if (line.cancelled) continue;
                    if (line.quantity.signum() < 0) {
                        hasReturn = true;
                        returned = returned.add(line.totalPrice.abs());
                    } else {
                        sold = true;
                    }
                    if (line.modifierType == PriceModType.FORCE_PRICE) {
                        totals.get("price-overrides").add(line.totalPrice.abs());
                    }
                }
                // A mixed ticket contributes once to each applicable document count.
                if (!hasReturn && ticket.totalIncludingTax.signum() < 0) {
                    returned = ticket.totalIncludingTax.abs();
                    hasReturn = true;
                }
                if (hasReturn) totals.get("returns").add(returned);
                if (sold || ticket.totalIncludingTax.signum() > 0) {
                    totals.get("sales").add(ticket.totalIncludingTax.add(returned));
                }
                for (var payment : ticket.payments) {
                    tenders.computeIfAbsent(payment.getMethodKey(), unused -> new Total()).add(payment.amount);
                }
            }
        }
        List<Refund> refunds = operatorId == null
                ? Refund.list("session = ?1 and status = ?2", session, Refund.RefundStatus.CLOSED)
                : Refund.list("session = ?1 and status = ?2 and cashierBadgeId in"
                        + " (select e.badgeId from Employee e where e.id = ?3)", session, Refund.RefundStatus.CLOSED, operatorId);
        for (Refund refund : refunds) {
            totals.get("returns").add(refund.totalAmount);
            if (refund.refundMethod != null) {
                String tender = switch (refund.refundMethod) {
                    case CASH -> "CASH";
                    case CARD -> "CARD";
                    case VOUCHER -> "VOUCHER";
                    case LOYALTY -> "FIDELITY";
                    case CREDIT -> "CREDIT";
                };
                tenders.computeIfAbsent(tender, unused -> new Total()).add(refund.totalAmount.negate());
            }
        }
        List<TicketLine> cancellations = operatorId == null
                ? TicketLine.list("select l from Ticket t join t.lines l where t.session = ?1 and l.cancelled = true", session)
                : TicketLine.list("select l from Ticket t join t.lines l where t.session = ?1 and l.cancelled = true and (l.cancelledBy in"
                        + " (select e.badgeId from Employee e where e.id = ?2)"
                        + " or (l.cancelledBy is null and t.cashier.id = ?2))", session, operatorId);
        for (TicketLine line : cancellations) totals.get("cancellations").add(line.totalPrice.abs());
        List<CashMovement> movements = operatorId == null ? CashMovement.list("session = ?1", session)
                : CashMovement.list("session = ?1 and cashier.id = ?2", session, operatorId);
        for (CashMovement movement : movements) {
            String key = switch (movement.type) {
                case EXPENSE -> "expenses";
                case DEPOSIT -> "deposits";
                case WITHDRAWAL -> "withdrawals";
                case CUSTOMER_DEPOSIT, DECLARATION, TRANSFER -> null;
            };
            if (key != null && movement.amount != null) totals.get(key).add(movement.amount);
        }
        List<Row> rows = new ArrayList<>();
        for (var entry : totals.entrySet()) {
            if (settings.reportSectionVisible(entry.getKey())) {
                rows.add(row("report.section." + entry.getKey(), "", entry.getValue()));
            }
        }
        if (settings.reportSectionVisible("tenders")) {
            for (var entry : tenders.entrySet()) rows.add(row("report.section.tenders", entry.getKey(), entry.getValue()));
        }
        return rows;
    }

    /** LC-12-07-02: rounds at presentation only, identically for paper and screen.
     * @param key rubric bundle key
     * @param tender optional tender code
     * @param total accumulated operations
     * @return immutable presentation row
     */
    private Row row(String key, String tender, Total total) {
        return new Row(key, tender, total.count,
                total.amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ','));
    }
}
