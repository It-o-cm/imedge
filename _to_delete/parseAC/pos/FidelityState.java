package com.intermarche.pos.ui.fidelity;

import java.io.Serializable;

/**
 * In-memory fidelity state of the current ticket: at most one card,
 * attached by scan or manual entry.
 * <p>
 * Semantic contract: {@code active} is what enables the CAGNOTTE payment
 * button; the card is copied onto the draft at every sync
 * ({@code Ticket.fidelityCard}), so it SURVIVES a register restart, follows
 * a parked ticket, and travels to the store node — this state is the
 * working copy, the draft is the durable one. Cleared with the ticket; a
 * second card simply replaces the first (last presented wins). Until the
 * phase 7 valuation engine, the card is identification only: no real
 * balance is read or credited.
 */
public class FidelityState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** True once a card is attached; enables the CAGNOTTE payment button. */
    public boolean active = false;

    /** The attached card number, or an empty string. */
    /**
     * The IMMEDIATE-DISCOUNT promotions imfid serves for the attached card,
     * read ONCE at attachment and carried into every valuation of the sale.
     * <p>
     * Empty is the normal state: no card, a holder without promotion, or an
     * imfid that could not be reached — the basket is then priced exactly as
     * it was before this existed. The cagnotte promotions are never here:
     * imfid serves those itself.
     */
    public java.util.List<ImfidClient.CardPromotion> cardPromotions = new java.util.ArrayList<>();

    public String label = "";

    /** Displayed earn projection total (imfid /api/earn), or null when none. */
    public java.math.BigDecimal earnTotal = null;

    /** Per-rule projection lines (label + amount) — printed labels come from here. */
    public java.util.List<EarnLine> earnEntries = new java.util.ArrayList<>();

    /**
     * Non-blocking messages the fidelity service sent back with the projection
     * (imfid response {@code warnings}, EXP-76): shown to the cashier on the
     * sale screen. Deserialized before but never surfaced — this is the channel.
     */
    public java.util.List<String> fidelityWarnings = new java.util.ArrayList<>();

    /** Burnable base of the current ticket (caps the fidelity payment), or null. */
    public java.math.BigDecimal burnableBase = null;

    /**
     * The last /valuation couple, VERBATIM (spec §1) — captured on the
     * valuation path, fed to /api/earn and re-sent in the ticket-closed
     * event. Never reconstructed.
     */
    public String lastValuationRequestJson = null;

    /** The matching raw response JSON. */
    public String lastValuationResponseJson = null;

    /**
     * The projected balance after this ticket's earn (imfid response B,
     * {@code balances.projectedAfterEarn}), or null when no card is resolved or
     * the projection is anonymous. A FORECAST printed as PREVISIONNEL — nothing
     * is credited before ingestion.
     */
    public java.math.BigDecimal projectedAfterEarn = null;

    /** The ledger read instant of the balances block (imfid response B), or null. */
    public String balanceAsOf = null;

    /**
     * True when the staged {@link #earnTotal} is an ANONYMOUS projection (imfid
     * response C) — a non-holder ticket showing « ce que vous auriez gagné avec
     * la carte ». It carries no balances and a zero burnable base.
     */
    public boolean anonymousProjection = false;

    /**
     * The balance AFTER the fidelity burn, read from the ledger at lease
     * confirmation (imfid response B), or null when no burn was confirmed. The
     * register never recomputes it — it prints what the ledger returned.
     */
    public java.math.BigDecimal balanceAfter = null;

    /** One displayed earn line: the rule label, its amount and its advantage grouping. */
    public static class EarnLine implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The rule code (displayedEarn trace at close). */
        public String ruleCode;
        /** The label to display and print. */
        public String label;
        /** The earn amount. */
        public java.math.BigDecimal amount;
        /** The advantage type code the line groups under, or null (legacy rule). */
        public String advantageType;
        /** The advantage type label, printed verbatim, or null. */
        public String advantageTypeLabel;
        /** The order the advantage-type groups print in, or null. */
        public Integer advantageTypeOrder;
        /** The advantage category code, or null. */
        public String advantageCategory;
        /** The advantage category label, printed verbatim, or null. */
        public String advantageCategoryLabel;

        /**
         * Creates a displayed earn line without advantage grouping (legacy).
         *
         * @param ruleCode the rule code
         * @param label the printable label
         * @param amount the earn amount
         */
        public EarnLine(String ruleCode, String label, java.math.BigDecimal amount) {
            this(ruleCode, label, amount, null, null, null, null, null);
        }

        /**
         * Creates a displayed earn line carrying its advantage grouping (imfid
         * response A).
         *
         * @param ruleCode the rule code
         * @param label the printable label
         * @param amount the earn amount
         * @param advantageType the advantage type code, or null
         * @param advantageTypeLabel the advantage type label, or null
         * @param advantageTypeOrder the group print order, or null
         * @param advantageCategory the advantage category code, or null
         * @param advantageCategoryLabel the advantage category label, or null
         */
        public EarnLine(String ruleCode, String label, java.math.BigDecimal amount,
                String advantageType, String advantageTypeLabel, Integer advantageTypeOrder,
                String advantageCategory, String advantageCategoryLabel) {
            this.ruleCode = ruleCode;
            this.label = label;
            this.amount = amount;
            this.advantageType = advantageType;
            this.advantageTypeLabel = advantageTypeLabel;
            this.advantageTypeOrder = advantageTypeOrder;
            this.advantageCategory = advantageCategory;
            this.advantageCategoryLabel = advantageCategoryLabel;
        }
    }

    /** Clears the displayed projection (degraded imfid, card removed, new sale). */
    public void clearEarn() {
        earnTotal = null;
        earnEntries = new java.util.ArrayList<>();
        burnableBase = null;
        projectedAfterEarn = null;
        balanceAsOf = null;
        anonymousProjection = false;
        fidelityWarnings = new java.util.ArrayList<>();
    }

    /**
     * The holder's last name, known ONLY when the card was attached through
     * the identity lookup (addendum §3.3) — a scanned card carries no
     * identity, by the pseudonymity doctrine. Operator-facing display only.
     */
    public String holderLastName = null;

    /** The holder's first name — same provenance and rules as the last name. */
    public String holderFirstName = null;

    /**
     * The holder's e-mail as the referential knows it (LC-08-02-09), or null.
     * <p>
     * Only a LOOKUP carries it: imfid answers a card scan with a status and a
     * balance, never with an identity, so a card that arrived by the scan bus leaves
     * this empty and the operator types the address instead. Shown to the OPERATOR
     * before an e-mail is sent, never read out to the customer.
     */
    public String holderEmail = null;

    /** ACTIVE | PENDING_ACTIVATION | RESILIATED, read at attachment, or null. */
    public String accountStatus = null;

    /**
     * The AVAILABLE balance read at attachment (spec §4 — net of leases),
     * or null when imfid could not answer (degraded = show nothing).
     */
    public java.math.BigDecimal availableBalance = null;

    /**
     * The outcome of the LAST holder lookup (addendum §3), rendered by the
     * fidelity page after the post/redirect/get hop — storing it here is what
     * makes the result list survive a browser refresh (the refresh re-GETs
     * /fidelity instead of replaying the POST). Transient: never persisted,
     * dropped with the process. Cleared when a card attaches or the ticket
     * ends.
     */
    public transient LookupView lastLookup = null;

    /** The search mode of the last lookup (card | tel | email | name). */
    public String lastLookupMode = "card";

    /** The criterion typed for the last lookup, echoed back to the page. */
    public String lastLookupValue = "";

    /** Matches shown per lookup page — sized so the fidelity page chrome plus
     * one page and its pager always fit the 600px screen. */
    private static final int LOOKUP_PAGE_SIZE = 4;

    /** The 0-based current page of the lookup result list. */
    public int lastLookupPage = 0;

    /**
     * Returns the lookup matches visible on the current page, clamping the
     * page index inside the list (same invariant as the refund detail page).
     *
     * @return the sublist of matches for the current page, or an empty list
     */
    public java.util.List<ImfidClient.LookupMatch> getVisibleMatches() {
        if (lastLookup == null || lastLookup.matches == null) {
            return java.util.Collections.emptyList();
        }
        java.util.List<ImfidClient.LookupMatch> all = lastLookup.matches;
        int maxPage = Math.max(0, (all.size() - 1) / LOOKUP_PAGE_SIZE);
        if (lastLookupPage > maxPage) lastLookupPage = maxPage;
        if (lastLookupPage < 0) lastLookupPage = 0;
        int from = lastLookupPage * LOOKUP_PAGE_SIZE;
        int to = Math.min(from + LOOKUP_PAGE_SIZE, all.size());
        return all.subList(from, to);
    }

    /**
     * Indicates whether a previous lookup page exists.
     *
     * @return true if not on the first page
     */
    public boolean isHasLookupPrev() {
        return lastLookupPage > 0;
    }

    /**
     * Indicates whether a next lookup page exists.
     *
     * @return true if more matches follow the current page
     */
    public boolean isHasLookupNext() {
        if (lastLookup == null || lastLookup.matches == null) return false;
        return (lastLookupPage + 1) * LOOKUP_PAGE_SIZE < lastLookup.matches.size();
    }

    /**
     * Returns the 1-based current lookup page number for display.
     *
     * @return the current page number
     */
    public int getLookupPageDisplay() {
        return lastLookupPage + 1;
    }

    /**
     * Returns the total number of lookup pages (at least one).
     *
     * @return the page count
     */
    public int getLookupPageCount() {
        if (lastLookup == null || lastLookup.matches == null || lastLookup.matches.isEmpty()) {
            return 1;
        }
        return (lastLookup.matches.size() - 1) / LOOKUP_PAGE_SIZE + 1;
    }

    /**
     * Attaches a card; silently ignores null or too-short values (a guard
     * against empty submits, not a format check — the format check lives in
     * the scan handler's pattern). Any holder identity or account data of a
     * PREVIOUS card is dropped: re-selecting a card REPLACES the former one
     * entirely, and a scanned card must never inherit another holder's name.
     *
     * @param card the card number
     */
    public void assignCard(String card) {
        if (card != null && card.length() > 2) {
            this.active = true;
            this.label = card;
            this.holderLastName = null;
            this.holderFirstName = null;
            this.holderEmail = null;
            this.accountStatus = null;
            this.availableBalance = null;
            // A card never inherits the promotions of the one it replaces:
            // they are the former holder's personal rights.
            this.cardPromotions = new java.util.ArrayList<>();
            clearLookup();
        }
    }

    /**
     * Drops the stored lookup outcome and resets the search echo to the
     * default card mode (a fresh fidelity page shows no stale result list).
     */
    public void clearLookup() {
        lastLookup = null;
        lastLookupMode = "card";
        lastLookupValue = "";
        lastLookupPage = 0;
    }

    /**
     * Detaches the card (called when the ticket is cleared).
     */
    public void clear() {
        active = false;
        label = "";
        holderLastName = null;
        holderFirstName = null;
        holderEmail = null;
        accountStatus = null;
        availableBalance = null;
        balanceAfter = null;
        clearLookup();
    }

    /**
     * The HOLDER'S NAME alone, upper-cased surname then first name, for a
     * screen that states the identity and nothing else (BO-10-03-03).
     *
     * <p>Empty when no card is attached or when no lookup brought a name back:
     * a scanned card carries a number and not an identity, and a display must
     * not invent one.
     *
     * @return the holder's name, empty when there is none to state
     */
    public String getHolderName() {
        if (!active || holderLastName == null || holderLastName.isBlank()) {
            return "";
        }
        if (holderFirstName == null || holderFirstName.isBlank()) {
            return holderLastName.toUpperCase();
        }
        return holderLastName.toUpperCase() + " " + holderFirstName;
    }

    /**
     * Returns the operator-facing summary of the attached card: holder (when
     * known from a lookup), card number and available balance — the line the
     * main screen shows next to the fidelity icon.
     *
     * @return the display line, or null when no card is attached
     */
    public String getDisplaySummary() {
        if (!active) return null;
        StringBuilder sb = new StringBuilder();
        if (holderLastName != null) {
            sb.append(holderLastName.toUpperCase());
            if (holderFirstName != null && !holderFirstName.isBlank()) {
                sb.append(' ').append(holderFirstName);
            }
            sb.append(" \u00b7 ");
        }
        sb.append(label);
        if (availableBalance != null) {
            sb.append(" \u00b7 ").append(String.format("%.2f", availableBalance).replace('.', ',')).append(" \u20ac");
        }
        return sb.toString();
    }
}