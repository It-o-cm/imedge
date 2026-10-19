package com.intermarche.pos.ui.fidelity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FidelityState}.
 * <p>
 * A pure in-memory holder with no collaborator: every test builds a fresh
 * instance and asserts absolute values. Three behaviours carry meaning beyond
 * their code — the attachment guard (a length check, NOT a format check: the
 * format lives in the scan handler's pattern), the last-presented-card-wins
 * replacement, and the separation between {@code clear()} (the card leaves
 * with the ticket) and {@code clearEarn()} (only the projection is hidden,
 * the card stays). Both arms of every guard are covered.
 */
class FidelityStateTest {

    /**
     * A fresh state carries no card and no projection — the state a sale must
     * begin from.
     */
    @Test
    void startsPristine() {
        FidelityState fid = new FidelityState();
        assertFalse(fid.active);
        assertEquals("", fid.label);
        assertNull(fid.earnTotal);
        assertNull(fid.burnableBase);
        assertTrue(fid.earnEntries.isEmpty());
        assertNull(fid.lastValuationRequestJson);
        assertNull(fid.lastValuationResponseJson);
    }

    /**
     * A real card number attaches and switches the state ACTIVE — the flag
     * that enables the CAGNOTTE payment button.
     */
    @Test
    void assignCardAttachesRealNumber() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        assertTrue(fid.active);
        assertEquals("2990000000019", fid.label);
    }

    /**
     * A NULL card is silently ignored (first leg of the guard): an empty
     * submit must not attach anything.
     */
    @Test
    void assignCardIgnoresNull() {
        FidelityState fid = new FidelityState();
        fid.assignCard(null);
        assertFalse(fid.active);
        assertEquals("", fid.label);
    }

    /**
     * A TOO-SHORT value is ignored (second leg): the guard is a length floor
     * against empty submits, not a format check — the discriminant pattern
     * lives in the scan handler.
     */
    @Test
    void assignCardIgnoresTooShortValue() {
        FidelityState fid = new FidelityState();
        fid.assignCard("12");
        assertFalse(fid.active);
        assertEquals("", fid.label);
    }

    /**
     * Three characters ARE accepted — the boundary of {@code length() > 2}.
     */
    @Test
    void assignCardAcceptsThreeCharacters() {
        FidelityState fid = new FidelityState();
        fid.assignCard("123");
        assertTrue(fid.active);
        assertEquals("123", fid.label);
    }

    /**
     * A second card REPLACES the first: last presented wins, and there is
     * never more than one card on a ticket.
     */
    @Test
    void assignCardLastPresentedWins() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.assignCard("2990000000040");
        assertEquals("2990000000040", fid.label);
        assertTrue(fid.active);
    }

    /**
     * A rejected card leaves a previously attached one UNTOUCHED: a stray
     * empty submit must not detach the customer's card.
     */
    @Test
    void assignCardRejectionKeepsThePreviousCard() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.assignCard("");
        assertTrue(fid.active);
        assertEquals("2990000000019", fid.label);
    }

    /**
     * {@code clearEarn()} hides the projection but KEEPS the card: this is
     * the degraded-imfid path — the sale goes on with its card attached, only
     * the advantage badge disappears.
     */
    @Test
    void clearEarnHidesProjectionButKeepsTheCard() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.earnTotal = new BigDecimal("1.03");
        fid.burnableBase = new BigDecimal("47.11");
        fid.earnEntries.add(new FidelityState.EarnLine("SOCLE", "Cagnotte socle", BigDecimal.ONE));

        fid.clearEarn();

        assertNull(fid.earnTotal);
        assertNull(fid.burnableBase);
        assertTrue(fid.earnEntries.isEmpty());
        assertTrue(fid.active);
        assertEquals("2990000000019", fid.label);
    }

    /**
     * {@code clearEarn()} replaces the entry list rather than emptying it in
     * place, so a snapshot handed out earlier is never mutated behind the
     * caller's back.
     */
    @Test
    void clearEarnReplacesTheEntryList() {
        FidelityState fid = new FidelityState();
        fid.earnEntries.add(new FidelityState.EarnLine("SOCLE", "Cagnotte socle", BigDecimal.ONE));
        java.util.List<FidelityState.EarnLine> snapshot = fid.earnEntries;

        fid.clearEarn();

        assertEquals(1, snapshot.size());
        assertTrue(fid.earnEntries.isEmpty());
    }

    /**
     * {@code clear()} detaches the card — called when the ticket is cleared.
     */
    @Test
    void clearDetachesTheCard() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.clear();
        assertFalse(fid.active);
        assertEquals("", fid.label);
    }

    /**
     * {@code clear()} does NOT clear the projection: the two concerns are
     * separate, and the end-of-sale broom is what runs both.
     */
    @Test
    void clearLeavesTheProjectionToClearEarn() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.earnTotal = new BigDecimal("1.03");
        fid.clear();
        assertEquals(0, new BigDecimal("1.03").compareTo(fid.earnTotal));
    }

    /**
     * An earn line carries the three fields the printed ticket needs: the
     * rule code (traced at close), the label (printed) and the amount.
     */
    @Test
    void earnLineCarriesCodeLabelAndAmount() {
        FidelityState.EarnLine line =
                new FidelityState.EarnLine("F&L-SAM", "Fruits & légumes samedi", new BigDecimal("0.75"));
        assertEquals("F&L-SAM", line.ruleCode);
        assertEquals("Fruits & légumes samedi", line.label);
        assertEquals(0, new BigDecimal("0.75").compareTo(line.amount));
        assertNull(line.advantageType);
        assertNull(line.advantageTypeOrder);
    }

    /**
     * The advantage-carrying constructor keeps the five grouping fields (imfid
     * response A).
     */
    @Test
    void earnLineCarriesTheAdvantageGrouping() {
        FidelityState.EarnLine line = new FidelityState.EarnLine("BOOST", "Boost marque",
                new BigDecimal("1.50"), "PRODUCT", "Vos avantages produits", 10,
                "FRAIS", "Produits frais");
        assertEquals("PRODUCT", line.advantageType);
        assertEquals("Vos avantages produits", line.advantageTypeLabel);
        assertEquals(10, line.advantageTypeOrder);
        assertEquals("FRAIS", line.advantageCategory);
        assertEquals("Produits frais", line.advantageCategoryLabel);
    }

    /**
     * {@code clearEarn()} also drops the projected balance, its as-of and the
     * anonymous-projection flag (imfid responses B and C), so a fresh valuation
     * never shows a stale forecast.
     */
    @Test
    void clearEarnAlsoResetsTheProjectionFields() {
        FidelityState fid = new FidelityState();
        fid.projectedAfterEarn = new BigDecimal("46.20");
        fid.balanceAsOf = "2026-09-17T14:01:57";
        fid.anonymousProjection = true;
        fid.clearEarn();
        assertNull(fid.projectedAfterEarn);
        assertNull(fid.balanceAsOf);
        assertFalse(fid.anonymousProjection);
    }

    /**
     * Re-assigning a card PURGES the previous holder identity, account status
     * and available balance: a scanned card must never inherit the former
     * holder's name or balance (the pseudonymity doctrine).
     */
    @Test
    void assignCardPurgesHolderStatusAndBalance() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.holderLastName = "Dupont";
        fid.holderFirstName = "Jean";
        fid.accountStatus = "ACTIVE";
        fid.availableBalance = new BigDecimal("12.00");
        fid.assignCard("2990000000040");
        assertEquals("2990000000040", fid.label);
        assertNull(fid.holderLastName);
        assertNull(fid.holderFirstName);
        assertNull(fid.accountStatus);
        assertNull(fid.availableBalance);
    }

    /**
     * {@code getDisplaySummary} returns null when no card is attached
     * (inactive arm).
     */
    @Test
    void displaySummaryIsNullWithoutCard() {
        assertNull(new FidelityState().getDisplaySummary());
    }

    /**
     * {@code getDisplaySummary} shows the bare card number when no holder and
     * no balance are known (holder-null / balance-null arms).
     */
    @Test
    void displaySummaryShowsBareCard() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        assertEquals("2990000000019", fid.getDisplaySummary());
    }

    /**
     * {@code getDisplaySummary} prefixes the upper-cased holder and first name
     * when both are known (holder-non-null / first-name-present arms).
     */
    @Test
    void displaySummaryShowsHolderAndFirstName() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.holderLastName = "Dupont";
        fid.holderFirstName = "Jean";
        assertEquals("DUPONT Jean · 2990000000019", fid.getDisplaySummary());
    }

    /**
     * {@code getDisplaySummary} omits a blank first name (first-name-blank
     * arm), showing only the upper-cased holder before the card.
     */
    @Test
    void displaySummaryOmitsBlankFirstName() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.holderLastName = "Dupont";
        fid.holderFirstName = "  ";
        assertEquals("DUPONT · 2990000000019", fid.getDisplaySummary());
    }

    /**
     * {@code getDisplaySummary} shows the upper-cased holder alone when the
     * first name is null (first-name-null arm of the compound guard), the card
     * following the middle-dot separator.
     */
    @Test
    void displaySummaryShowsHolderWithoutFirstName() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.holderLastName = "Dupont";
        fid.holderFirstName = null;
        assertEquals("DUPONT · 2990000000019", fid.getDisplaySummary());
    }

    /**
     * {@code getDisplaySummary} appends the French-formatted available balance
     * when known (balance-non-null arm).
     */
    @Test
    void displaySummaryAppendsBalance() {
        FidelityState fid = new FidelityState();
        fid.assignCard("2990000000019");
        fid.availableBalance = new BigDecimal("12.5");
        assertEquals("2990000000019 · 12,50 €", fid.getDisplaySummary());
    }

    /**
     * Builds a lookup view carrying {@code count} matches, each tagged with a
     * distinct card number so page slices can be identified by their first
     * element — the fixture behind every pagination test.
     *
     * @param count the number of matches to seed
     * @return a lookup view with a populated match list
     */
    private LookupView viewWithMatches(int count) {
        LookupView view = new LookupView();
        view.matches = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            ImfidClient.LookupMatch match = new ImfidClient.LookupMatch();
            match.card = "CARD-" + i;
            view.matches.add(match);
        }
        return view;
    }

    /**
     * {@code getVisibleMatches} returns an empty list when no lookup ran
     * (first leg of the null guard: {@code lastLookup == null}).
     */
    @Test
    void visibleMatchesEmptyWhenNoLookup() {
        FidelityState fid = new FidelityState();
        assertTrue(fid.getVisibleMatches().isEmpty());
    }

    /**
     * {@code getVisibleMatches} returns an empty list when a lookup exists but
     * carries no match list (second leg: {@code lastLookup.matches == null}).
     */
    @Test
    void visibleMatchesEmptyWhenMatchesNull() {
        FidelityState fid = new FidelityState();
        fid.lastLookup = new LookupView();
        fid.lastLookup.matches = null;
        assertTrue(fid.getVisibleMatches().isEmpty());
    }

    /**
     * {@code getVisibleMatches} returns the current page slice when the page
     * index is in range (both guard legs false, both clamps not taken): four
     * matches per page, first slice starts at the head of the list.
     */
    @Test
    void visibleMatchesReturnsCurrentPage() {
        FidelityState fid = new FidelityState();
        fid.lastLookup = viewWithMatches(5);
        fid.lastLookupPage = 0;
        java.util.List<ImfidClient.LookupMatch> page = fid.getVisibleMatches();
        assertEquals(4, page.size());
        assertEquals("CARD-0", page.get(0).card);
        assertEquals(0, fid.lastLookupPage);
    }

    /**
     * {@code getVisibleMatches} clamps a page index beyond the last page down
     * to the maximum page ({@code lastLookupPage > maxPage} arm true): with
     * five matches the max page is 1, showing the single trailing match.
     */
    @Test
    void visibleMatchesClampsPageBeyondLast() {
        FidelityState fid = new FidelityState();
        fid.lastLookup = viewWithMatches(5);
        fid.lastLookupPage = 9;
        java.util.List<ImfidClient.LookupMatch> page = fid.getVisibleMatches();
        assertEquals(1, page.size());
        assertEquals("CARD-4", page.get(0).card);
        assertEquals(1, fid.lastLookupPage);
    }

    /**
     * {@code getVisibleMatches} clamps a negative page index up to zero
     * ({@code lastLookupPage < 0} arm true, the beyond-last arm false),
     * returning the first slice.
     */
    @Test
    void visibleMatchesClampsNegativePage() {
        FidelityState fid = new FidelityState();
        fid.lastLookup = viewWithMatches(5);
        fid.lastLookupPage = -3;
        java.util.List<ImfidClient.LookupMatch> page = fid.getVisibleMatches();
        assertEquals(4, page.size());
        assertEquals("CARD-0", page.get(0).card);
        assertEquals(0, fid.lastLookupPage);
    }

    /**
     * {@code isHasLookupPrev} is true past the first page (guard arm true).
     */
    @Test
    void hasLookupPrevTrueBeyondFirstPage() {
        FidelityState fid = new FidelityState();
        fid.lastLookupPage = 1;
        assertTrue(fid.isHasLookupPrev());
    }

    /**
     * {@code isHasLookupPrev} is false on the first page (guard arm false).
     */
    @Test
    void hasLookupPrevFalseOnFirstPage() {
        FidelityState fid = new FidelityState();
        fid.lastLookupPage = 0;
        assertFalse(fid.isHasLookupPrev());
    }

    /**
     * {@code isHasLookupNext} is false when no lookup ran (first leg of the
     * null guard: {@code lastLookup == null}).
     */
    @Test
    void hasLookupNextFalseWhenNoLookup() {
        FidelityState fid = new FidelityState();
        assertFalse(fid.isHasLookupNext());
    }

    /**
     * {@code isHasLookupNext} is false when the lookup carries no match list
     * (second leg: {@code lastLookup.matches == null}).
     */
    @Test
    void hasLookupNextFalseWhenMatchesNull() {
        FidelityState fid = new FidelityState();
        fid.lastLookup = new LookupView();
        fid.lastLookup.matches = null;
        assertFalse(fid.isHasLookupNext());
    }

    /**
     * {@code isHasLookupNext} is true when a further page follows (both guard
     * legs false, the size comparison arm true): five matches, first page.
     */
    @Test
    void hasLookupNextTrueWhenMorePages() {
        FidelityState fid = new FidelityState();
        fid.lastLookup = viewWithMatches(5);
        fid.lastLookupPage = 0;
        assertTrue(fid.isHasLookupNext());
    }

    /**
     * {@code isHasLookupNext} is false on the last page (size comparison arm
     * false): five matches, second page.
     */
    @Test
    void hasLookupNextFalseOnLastPage() {
        FidelityState fid = new FidelityState();
        fid.lastLookup = viewWithMatches(5);
        fid.lastLookupPage = 1;
        assertFalse(fid.isHasLookupNext());
    }

    /**
     * {@code getLookupPageDisplay} returns the 1-based page number for the
     * pager label.
     */
    @Test
    void lookupPageDisplayIsOneBased() {
        FidelityState fid = new FidelityState();
        fid.lastLookupPage = 2;
        assertEquals(3, fid.getLookupPageDisplay());
    }

    /**
     * {@code getLookupPageCount} is one when no lookup ran (first leg of the
     * guard: {@code lastLookup == null}).
     */
    @Test
    void lookupPageCountIsOneWhenNoLookup() {
        FidelityState fid = new FidelityState();
        assertEquals(1, fid.getLookupPageCount());
    }

    /**
     * {@code getLookupPageCount} is one when the lookup carries no match list
     * (second leg: {@code lastLookup.matches == null}).
     */
    @Test
    void lookupPageCountIsOneWhenMatchesNull() {
        FidelityState fid = new FidelityState();
        fid.lastLookup = new LookupView();
        fid.lastLookup.matches = null;
        assertEquals(1, fid.getLookupPageCount());
    }

    /**
     * {@code getLookupPageCount} is one when the match list is empty (third
     * leg: {@code lastLookup.matches.isEmpty()}).
     */
    @Test
    void lookupPageCountIsOneWhenMatchesEmpty() {
        FidelityState fid = new FidelityState();
        fid.lastLookup = viewWithMatches(0);
        assertEquals(1, fid.getLookupPageCount());
    }

    /**
     * {@code getLookupPageCount} counts the pages when matches are present
     * (all three guard legs false): five matches over a page size of four
     * span two pages.
     */
    @Test
    void lookupPageCountCountsPages() {
        FidelityState fid = new FidelityState();
        fid.lastLookup = viewWithMatches(5);
        assertEquals(2, fid.getLookupPageCount());
    }

    /**
     * BO-10-03-03: the holder's name alone — surname upper-cased, first name as
     * it came — and empty whenever there is no identity to state: no card, no
     * lookup, or a lookup that brought back a blank.
     */
    @Test
    void theHolderNameIsStatedOnlyWhenThereIsOne() {
        FidelityState none = new FidelityState();
        assertEquals("", none.getHolderName());

        FidelityState carded = new FidelityState();
        carded.assignCard("6045200000123");
        assertEquals("", carded.getHolderName());

        FidelityState blank = new FidelityState();
        blank.assignCard("6045200000123");
        blank.holderLastName = "   ";
        assertEquals("", blank.getHolderName());

        FidelityState surnameOnly = new FidelityState();
        surnameOnly.assignCard("6045200000123");
        surnameOnly.holderLastName = "Dupont";
        assertEquals("DUPONT", surnameOnly.getHolderName());

        FidelityState blankFirstName = new FidelityState();
        blankFirstName.assignCard("6045200000123");
        blankFirstName.holderLastName = "Dupont";
        blankFirstName.holderFirstName = "  ";
        assertEquals("DUPONT", blankFirstName.getHolderName());

        FidelityState full = new FidelityState();
        full.assignCard("6045200000123");
        full.holderLastName = "Dupont";
        full.holderFirstName = "Jean";
        assertEquals("DUPONT Jean", full.getHolderName());
    }
}
