package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * In-memory state of the refund screen: ticket search, selected ticket,
 * per-line refund quantities, manual amount and paging.
 * <p>
 * Phase 3 lot 4: carries the refusal message of the double-refund and
 * ticket-cap guards.
 * <p>
 * Everything here is PRE-DECISION staging: searched tickets, the selected
 * one, the per-line quantities the cashier dials and the manual amount are
 * all inert until a refund method is endorsed — the parked-gesture pattern
 * again, with the staging on this state instead of an action string. The
 * per-line quantities key on the ORIGINAL line ids (register-local), which
 * is fine here precisely because refunds run on the ticket's own register
 * (mono-register decision of phase 3).
 */
public class RefundState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Number of ticket lines per detail page. */
    private static final int PAGE_SIZE = 6;

    /** The typed search pattern. */
    public String searchPattern = "";

    /** The tickets found by the search. */
    public List<Ticket> foundTickets = new ArrayList<>();

    /** The ticket being refunded, or null. */
    public Ticket selectedTicket = null;

    /**
     * The ids of the selected ticket's lines already FULLY returned by past
     * refunds (EXP-37): the return detail greys them and makes them
     * non-selectable. Computed when the ticket is selected.
     */
    public java.util.Set<Long> fullyReturnedLineIds = new java.util.HashSet<>();

    /**
     * Whether a line has already been fully returned (EXP-37).
     *
     * @param lineId the database id of the line
     * @return true when nothing remains to return on that line
     */
    public boolean isFullyReturned(Long lineId) {
        return fullyReturnedLineIds.contains(lineId);
    }

    /** The refund quantities keyed by original line id. */
    public Map<Long, BigDecimal> returnQuantities = new HashMap<>();

    /** The current detail page. */
    public int detailPage = 0;

    /** The line selected for direct quantity typing, or null. */
    public Long selectedLineId = null;

    /** True while the global amount is being typed. */
    public boolean isEditingAmount = false;

    /** The manually typed global amount, or null. */
    public BigDecimal manualTotalAmount = null;

    /** The refusal message of the refund guards, or null. */
    public String errorMessage = null;

    /**
     * The return reason chosen from the administered list, or null when none
     * is chosen. A single reason applies to the whole return (all returned
     * lines at once): the group assignment of LC-05-05-02.
     */
    public String returnReason = null;

    /**
     * Code of that administered reason ({@code BO-06-06-05}), or null when
     * none is chosen — kept beside the label and cleared with it, because a
     * state that groups by code must never read a code the chosen reason no
     * longer carries.
     */
    public String returnReasonCode = null;

    /** The in-account customer selected for a credit refund (LC-05-06-05), or null. */
    public String creditAccountNumber = null;

    /** The display name of the selected credit-refund customer, or null. */
    public String creditAccountName = null;

    /**
     * Indicates whether a ticket is selected (detail mode).
     *
     * @return true when a ticket is selected
     */
    public boolean isTicketSelected() {
        return selectedTicket != null;
    }

    /**
     * Returns the chosen return reason, or null when none is chosen.
     *
     * @return the return reason, or null
     */
    public String getReturnReason() {
        return returnReason;
    }

    /**
     * Returns the ticket lines visible on the current detail page.
     *
     * @return the sublist of lines for the current page
     */
    public List<TicketLine> getVisibleLines() {
        if (selectedTicket == null || selectedTicket.lines == null) return Collections.emptyList();
        List<TicketLine> allLines = refundableLines();

        int maxPage = Math.max(0, (allLines.size() - 1) / PAGE_SIZE);
        if (detailPage > maxPage) detailPage = maxPage;

        // Same invariant as the reprint screen: the clamp above already
        // keeps the window inside the list, so no further bound check is
        // reachable.
        int from = detailPage * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, allLines.size());
        return allLines.subList(from, to);
    }

    /**
     * Returns the refundable lines of the selected ticket: the sold lines, with
     * the cancelled articles filtered out (lot C4, BO-04-01-16). A cancelled
     * article was never sold, so it can never be returned — it is a journal
     * witness only, invisible to the returns screen and its pagination.
     *
     * @return the non-cancelled lines of the selected ticket
     */
    private List<TicketLine> refundableLines() {
        return selectedTicket.lines.stream().filter(l -> !l.cancelled).toList();
    }

    /**
     * Returns the detail page a line is displayed on (LC-05-02-11): a scanned or
     * keyed article is useless to the cashier if the line it bumped sits on
     * another page, so the screen follows the find.
     *
     * @param lineId the database id of the line, or null
     * @return the zero-based page index, or the current page when the line is not
     *         one of the refundable lines
     */
    public int pageOf(Long lineId) {
        if (lineId == null || selectedTicket == null || selectedTicket.lines == null) {
            return detailPage;
        }
        List<TicketLine> allLines = refundableLines();
        for (int index = 0; index < allLines.size(); index++) {
            if (lineId.equals(allLines.get(index).id)) {
                return index / PAGE_SIZE;
            }
        }
        return detailPage;
    }

    /**
     * Returns the refund quantity of a line.
     *
     * @param lineId the database id of the line
     * @return the refund quantity, or ZERO
     */
    public BigDecimal getReturnQty(Long lineId) {
        if (lineId == null || returnQuantities == null) return BigDecimal.ZERO;
        return returnQuantities.getOrDefault(lineId, BigDecimal.ZERO);
    }

    /**
     * Indicates whether a line has a positive refund quantity.
     *
     * @param lineId the database id of the line
     * @return true when the refund quantity is strictly positive
     */
    public boolean hasReturnQty(Long lineId) {
        return getReturnQty(lineId).compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Indicates whether the given line is selected for typing.
     *
     * @param lineId the database id of the line
     * @return true when the line is selected
     */
    public boolean isLineSelected(Long lineId) {
        return lineId != null && lineId.equals(selectedLineId);
    }

    /**
     * Returns the refund total: the manual amount when typed, otherwise the sum
     * of the selected line quantities at the price actually paid on each
     * original line (LC-05-02-12) — advantages of the sale included, so the
     * till never hands back more than it took.
     *
     * @return the refund total including tax
     */
    public BigDecimal getTotalRefundAmount() {
        if (manualTotalAmount != null) return manualTotalAmount;

        if (selectedTicket == null) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;

        for (TicketLine line : refundableLines()) {
            BigDecimal qty = returnQuantities.getOrDefault(line.id, BigDecimal.ZERO);
            if (qty.compareTo(BigDecimal.ZERO) > 0) {
                // Rounded line by line, to the centime, exactly as the VAT
                // ventilation of the refund is: the effective unit price carries
                // four decimals, and a total built from unrounded products would
                // not match the restituted HT and VAT it is supposed to sum to.
                total = total.add(line.getEffectiveUnitPrice().multiply(qty)
                        .setScale(2, RoundingMode.HALF_UP));
            }
        }
        return total;
    }

    /**
     * Returns the refund total formatted for display (2 decimals).
     *
     * @return the formatted refund total
     */
    public String getTotalRefundFormatted() {
        BigDecimal total = getTotalRefundAmount();
        BigDecimal rounded = total.setScale(2, RoundingMode.HALF_UP);
        return rounded.toPlainString();
    }

    /**
     * Drops the ticket selection and its staging (quantities, typed amount,
     * pagination, guard message) while KEEPING the search fields: a new
     * search must supersede a stale selection, so the redirect-after-search
     * GET renders the fresh result list and never a leftover detail screen.
     */
    public void clearSelection() {
        selectedTicket = null;
        fullyReturnedLineIds.clear();
        returnQuantities.clear();
        detailPage = 0;
        selectedLineId = null;
        isEditingAmount = false;
        manualTotalAmount = null;
        errorMessage = null;
        returnReason = null;
        returnReasonCode = null;
        creditAccountNumber = null;
        creditAccountName = null;
    }

    /**
     * Clears the whole refund state.
     */
    public void clear() {
        searchPattern = "";
        foundTickets.clear();
        selectedTicket = null;
        fullyReturnedLineIds.clear();
        returnQuantities.clear();
        detailPage = 0;
        selectedLineId = null;
        isEditingAmount = false;
        manualTotalAmount = null;
        errorMessage = null;
        returnReason = null;
        returnReasonCode = null;
        creditAccountNumber = null;
        creditAccountName = null;
    }

    /**
     * Indicates whether a previous detail page exists.
     *
     * @return true if not on the first page
     */
    public boolean isHasDetailPrev() {
        return detailPage > 0;
    }

    /**
     * Indicates whether a next detail page exists.
     *
     * @return true if more lines follow the current page
     */
    public boolean isHasDetailNext() {
        if (selectedTicket == null || selectedTicket.lines == null) return false;
        return (detailPage + 1) * PAGE_SIZE < refundableLines().size();
    }

    /**
     * Returns the 1-based current detail page number for display.
     *
     * @return the current page number
     */
    public int getDetailPageDisplay() {
        return detailPage + 1;
    }

    /**
     * Returns the total number of detail pages (at least one).
     *
     * @return the page count
     */
    public int getDetailTotalPages() {
        if (selectedTicket == null || selectedTicket.lines == null || refundableLines().isEmpty()) return 1;
        return (int) Math.ceil((double) refundableLines().size() / PAGE_SIZE);
    }
}
