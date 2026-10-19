package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.ui.ticket.TicketState.TicketItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TicketState} and its inner {@link TicketItem}.
 * <p>
 * Plain JUnit 5 tests: {@code TicketState} is a POJO with no Panache
 * collaborators, so a real {@link PosState} parent is used to observe the
 * {@code touch()} / {@code lastEnteredItemId} side effects, and the null
 * arm is exercised by leaving the parent unset.
 */
class TicketStateTest {

    /**
     * Builds a unit EAN item (mergeable candidate) with the given price.
     *
     * @param ean the EAN code
     * @param price the unit price
     * @param qty the quantity
     * @return the built ticket item
     */
    private TicketItem unitItem(String ean, String price, String qty) {
        return new TicketItem(ean, null, "L", new BigDecimal(price), new BigDecimal(qty), null);
    }

    /**
     * onChange with a null parent must be a silent no-op (null arm).
     */
    @Test
    void onChangeWithNullParentDoesNothing() {
        TicketState ts = new TicketState();
        ts.setError("boom");
        assertEquals("boom", ts.transientError);
    }

    /**
     * onChange with a non-null parent bumps the parent version (non-null arm).
     */
    @Test
    void onChangeWithParentTouchesIt() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        long before = parent.version;
        ts.setWeight(1.5);
        assertEquals(before + 1, parent.version);
        assertEquals(1.5, ts.currentWeight);
    }

    /**
     * addItem on an empty ticket with a mergeable unit EAN creates a line and,
     * with a parent, records lastEnteredItemId (line 130 non-null arm).
     */
    @Test
    void addItemToEmptyTicketCreatesLineAndRecordsId() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, new BigDecimal("0.2000"));
        assertEquals(1, ts.items.size());
        assertEquals(ts.items.get(0).uid, parent.lastEnteredItemId);
        assertEquals(0, new BigDecimal("1.00").compareTo(ts.totalAmount));
    }

    /**
     * addItem without a parent still creates the line (line 130 null arm).
     */
    @Test
    void addItemWithoutParentCreatesLine() {
        TicketState ts = new TicketState();
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(1, ts.items.size());
    }

    /**
     * Two identical unmodified positive same-price unit EAN scans merge into
     * one line: quantity added, total recomputed, lastEnteredItemId updated
     * (merge success path, line 116 non-null arm).
     */
    @Test
    void addItemMergesIdenticalUnitEan() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        String uid = ts.items.get(0).uid;
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(1, ts.items.size());
        assertEquals(0, new BigDecimal("2").compareTo(ts.items.get(0).quantity));
        assertEquals(uid, parent.lastEnteredItemId);
    }

    /**
     * A merge on a parentless ticket still merges (line 116 null arm).
     */
    @Test
    void addItemMergesWithoutParent() {
        TicketState ts = new TicketState();
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(1, ts.items.size());
        assertEquals(0, new BigDecimal("2").compareTo(ts.items.get(0).quantity));
    }

    /**
     * A weighed incoming line (non-empty PLU) is never mergeable and always
     * starts its own line (plu non-empty arm of mergeable).
     */
    @Test
    void addItemWeighedNeverMerges() {
        TicketState ts = new TicketState();
        ts.addItem("123", "9", "Apples", new BigDecimal("1.00"), new BigDecimal("1.000"), null);
        ts.addItem("123", "9", "Apples", new BigDecimal("1.00"), new BigDecimal("1.000"), null);
        assertEquals(2, ts.items.size());
    }

    /**
     * An empty PLU keeps the line mergeable (plu.isEmpty() true arm).
     */
    @Test
    void addItemEmptyPluIsMergeable() {
        TicketState ts = new TicketState();
        ts.addItem("123", "", "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("123", "", "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(1, ts.items.size());
    }

    /**
     * A null EAN is not mergeable and starts a new line (ean == null arm).
     */
    @Test
    void addItemNullEanNotMergeable() {
        TicketState ts = new TicketState();
        ts.addItem(null, null, "Deposit", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem(null, null, "Deposit", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * An empty EAN is not mergeable (ean.isEmpty() true arm).
     */
    @Test
    void addItemEmptyEanNotMergeable() {
        TicketState ts = new TicketState();
        ts.addItem("", null, "X", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("", null, "X", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * A stored item whose PLU is non-empty is not a merge target: sameEan false
     * on the plu clause, loop continues, new line created.
     */
    @Test
    void addItemExistingWeighedIsNotMergeTarget() {
        TicketState ts = new TicketState();
        ts.addItem("123", "9", "Apples", new BigDecimal("1.00"), new BigDecimal("1.000"), null);
        ts.addItem("123", null, "Apples", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * A stored item with a null EAN is skipped in the merge scan
     * (item.ean == null arm of sameEan).
     */
    @Test
    void addItemExistingNullEanSkipped() {
        TicketState ts = new TicketState();
        ts.addItem(null, null, "Deposit", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * A stored item with a different EAN is skipped (equals false arm).
     */
    @Test
    void addItemDifferentEanSkipped() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("222", null, "B", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * A matching EAN carrying a modifier label cannot merge: unmodified false on
     * the modifierLabel clause, break, distinct line created.
     */
    @Test
    void addItemModifiedLineBreaksToNewLine() {
        TicketState ts = new TicketState();
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.items.get(0).modifierLabel = "REMISE";
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * A matching EAN whose unit price diverged from its original cannot merge
     * (unmodified false on the compareTo clause), break, new line.
     */
    @Test
    void addItemPriceDivergedBreaksToNewLine() {
        TicketState ts = new TicketState();
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.items.get(0).unitPrice = new BigDecimal("2.00");
        ts.addItem("123", null, "Milk", new BigDecimal("2.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * A matching unmodified EAN at a different price cannot merge (samePrice
     * false), break, new line.
     */
    @Test
    void addItemDifferentPriceBreaksToNewLine() {
        TicketState ts = new TicketState();
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("123", null, "Milk", new BigDecimal("2.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * A matching unmodified same-price EAN whose total is negative cannot merge
     * (positive false), break, new line.
     */
    @Test
    void addItemNegativeLineBreaksToNewLine() {
        TicketState ts = new TicketState();
        ts.addItem("123", null, "Return", new BigDecimal("-1.00"), BigDecimal.ONE, null);
        ts.addItem("123", null, "Return", new BigDecimal("-1.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }



    /**
     * removeItemById with a null uid returns immediately (null arm).
     */
    @Test
    void removeItemByIdNullUid() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.removeItemById(null);
        assertEquals(1, ts.items.size());
    }

    /**
     * removeItemById with a matching uid removes that line (match arm).
     */
    @Test
    void removeItemByIdMatch() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("222", null, "B", new BigDecimal("2.00"), BigDecimal.ONE, null);
        String uid = ts.items.get(0).uid;
        ts.removeItemById(uid);
        assertEquals(1, ts.items.size());
        assertEquals("222", ts.items.get(0).ean);
    }

    /**
     * removeItemById with an unknown uid removes nothing (no-match arm).
     */
    @Test
    void removeItemByIdNoMatch() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.removeItemById("does-not-exist");
        assertEquals(1, ts.items.size());
    }

    /**
     * recomputeTotal sums line totals rounded to the cent (loop with items).
     */
    @Test
    void recomputeTotalSumsRoundedLineTotals() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.005"), BigDecimal.ONE, null);
        ts.addItem("222", null, "B", new BigDecimal("2.00"), BigDecimal.ONE, null);
        assertEquals(0, new BigDecimal("3.01").compareTo(ts.totalAmount));
    }

    /**
     * recomputeTotal on an empty ticket yields zero (loop with no items).
     */
    @Test
    void recomputeTotalEmpty() {
        TicketState ts = new TicketState();
        ts.recomputeTotal();
        assertEquals(0, BigDecimal.ZERO.compareTo(ts.totalAmount));
    }

    /**
     * clear resets every field and notifies the parent.
     */
    @Test
    void clearResetsEverything() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        ts.addItem("111", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.scannedStickerCodes.add("code");
        ts.currentWeight = 2.0;
        ts.lastRecordedWeight = 3.0;
        ts.transientError = "err";
        ts.transientOk = true;
        long before = parent.version;
        ts.clear();
        assertTrue(ts.items.isEmpty());
        assertTrue(ts.scannedStickerCodes.isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(ts.totalAmount));
        assertEquals(0.0, ts.currentWeight);
        assertTrue(Double.isNaN(ts.lastRecordedWeight));
        assertNull(ts.transientError);
        assertFalse(ts.transientOk);
        assertEquals(before + 1, parent.version);
    }

    /**
     * setError stores the message, notifies, and marks the zone as a REFUSAL.
     */
    @Test
    void setErrorStoresMessage() {
        TicketState ts = new TicketState();
        ts.setError("oops");
        assertEquals("oops", ts.transientError);
        assertFalse(ts.transientOk);
    }

    /**
     * setNotice stores the message in the same zone and marks it as a
     * CONFIRMATION, so a successful print is not drawn as an error.
     */
    @Test
    void setNoticeStoresConfirmation() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        long before = parent.version;
        ts.setNotice("TICKET RÉIMPRIMÉ");
        assertEquals("TICKET RÉIMPRIMÉ", ts.transientError);
        assertTrue(ts.transientOk);
        assertEquals(before + 1, parent.version);
    }

    /**
     * A refusal after a confirmation takes the zone back: the OK marker must not
     * survive the next failure.
     */
    @Test
    void setErrorAfterNoticeClearsTheConfirmationFlag() {
        TicketState ts = new TicketState();
        ts.setNotice("TICKET RÉIMPRIMÉ");
        ts.setError("AUCUN TICKET");
        assertEquals("AUCUN TICKET", ts.transientError);
        assertFalse(ts.transientOk);
    }

    /**
     * getTotalFormatted renders the total with two decimals and a French comma.
     */
    @Test
    void getTotalFormattedRendersFrenchComma() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.50"), BigDecimal.ONE, null);
        assertEquals("1,50", ts.getTotalFormatted());
    }

    /**
     * getTotalAmount and getItems expose the backing fields.
     */
    @Test
    void gettersExposeState() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertSame(ts.items, ts.getItems());
        assertSame(ts.totalAmount, ts.getTotalAmount());
    }

    /**
     * The item constructor keeps a supplied VAT rate (non-null arm).
     */
    @Test
    void itemConstructorKeepsVatRate() {
        TicketItem item = new TicketItem("1", null, "L", BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.2000"));
        assertEquals(0, new BigDecimal("0.2000").compareTo(item.vatRate));
    }

    /**
     * The item constructor defaults a null VAT rate to zero (null arm).
     */
    @Test
    void itemConstructorDefaultsNullVatRate() {
        TicketItem item = new TicketItem("1", null, "L", BigDecimal.ONE, BigDecimal.ONE, null);
        assertEquals(0, BigDecimal.ZERO.compareTo(item.vatRate));
    }

    /**
     * getTotalPrice returns the engine-valued total when present (non-null arm).
     */
    @Test
    void getTotalPriceUsesValuedTotal() {
        TicketItem item = unitItem("1", "1.00", "2");
        item.valuedTotal = new BigDecimal("5.00");
        assertEquals(0, new BigDecimal("5.00").compareTo(item.getTotalPrice()));
    }

    /**
     * getTotalPrice falls back to the local math when unvalued (null arm).
     */
    @Test
    void getTotalPriceFallsBackToLocalMath() {
        TicketItem item = unitItem("1", "1.50", "2");
        assertEquals(0, new BigDecimal("3.00").compareTo(item.getTotalPrice()));
    }


    /**
     * getHtml returns the raw label for a code-less span line (both arms true).
     */
    @Test
    void getHtmlReturnsSpanLabelForCodelessLine() {
        TicketItem item = new TicketItem(null, null, "<span>Donation</span>", BigDecimal.ONE, BigDecimal.ONE, null);
        assertEquals("<span>Donation</span>", item.getHtml());
    }

    /**
     * getHtml with a null EAN but a plain label falls through (contains false arm).
     */
    @Test
    void getHtmlCodelessPlainLabelFallsThrough() {
        TicketItem item = new TicketItem(null, null, "Plain", BigDecimal.ONE, BigDecimal.ONE, null);
        assertEquals("<span class='qty unit-qty'>x1</span> Plain", item.getHtml());
    }

    /**
     * getHtml with a non-null EAN never returns the span label (ean != null arm).
     */
    @Test
    void getHtmlWithEanUsesUnitBranch() {
        TicketItem item = new TicketItem("1", null, "<span>x</span>", BigDecimal.ONE, BigDecimal.ONE, null);
        assertEquals("<span class='qty unit-qty'>x1</span> <span>x</span>", item.getHtml());
    }

    /**
     * getHtml renders a weighed line with three decimals (plu non-empty arm,
     * priceEmbedded false arm).
     */
    @Test
    void getHtmlWeighedLine() {
        TicketItem item = new TicketItem("1", "9", "Apples", new BigDecimal("1.00"), new BigDecimal("1.234"), null);
        assertEquals("<span class='qty'>1,234 kg</span> Apples", item.getHtml());
    }

    /**
     * getHtml on a price-embedded sticker line (PLU present) renders the label
     * alone: the sticker fixed the price, the weight is unknown, so no
     * quantity fragment — never a phantom "1,000 kg" (priceEmbedded true arm).
     */
    @Test
    void getHtmlPriceEmbeddedStickerShowsNoWeight() {
        TicketItem item = new TicketItem("1", "9", "Apples", new BigDecimal("2.67"), BigDecimal.ONE, null);
        item.priceEmbedded = true;
        assertEquals("Apples", item.getHtml());
    }

    /**
     * getHtml renders a non-one whole unit quantity with the plain qty class
     * (isOne false, isWhole true arms).
     */
    @Test
    void getHtmlUnitWholeNonOne() {
        TicketItem item = new TicketItem("1", null, "A", new BigDecimal("1.00"), new BigDecimal("2"), null);
        assertEquals("<span class='qty'>x2</span> A", item.getHtml());
    }

    /**
     * getHtml renders a fractional unit quantity with two decimals
     * (isWhole false arm).
     */
    @Test
    void getHtmlUnitFractional() {
        TicketItem item = new TicketItem("1", null, "A", new BigDecimal("1.00"), new BigDecimal("2.50"), null);
        assertEquals("<span class='qty'>x2,50</span> A", item.getHtml());
    }

    /**
     * getHtml with a null PLU takes the unit branch (plu == null arm).
     */
    @Test
    void getHtmlNullPluIsUnitBranch() {
        TicketItem item = new TicketItem("1", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals("<span class='qty unit-qty'>x1</span> A", item.getHtml());
    }

    /**
     * getHtml with an empty PLU also takes the unit branch (plu.isEmpty() arm).
     */
    @Test
    void getHtmlEmptyPluIsUnitBranch() {
        TicketItem item = new TicketItem("1", "", "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals("<span class='qty unit-qty'>x1</span> A", item.getHtml());
    }

    /**
     * getPriceFormatted rounds and renders with a French comma.
     */
    @Test
    void getPriceFormattedRendersFrenchComma() {
        TicketItem item = unitItem("1", "1.005", "1");
        assertEquals("1,01", item.getPriceFormatted());
    }

    /**
     * getModifierLabel returns the stored label, null by default.
     */
    @Test
    void getModifierLabelReturnsField() {
        TicketItem item = unitItem("1", "1.00", "1");
        assertNull(item.getModifierLabel());
        item.modifierLabel = "REMISE";
        assertEquals("REMISE", item.getModifierLabel());
    }

    /**
     * isNegative and its Qute alias are true for a negative total.
     */
    @Test
    void isNegativeTrueForNegativeTotal() {
        TicketItem item = unitItem("1", "-1.00", "1");
        assertTrue(item.isNegative());
        assertTrue(item.getNegative());
    }

    /**
     * isNegative and its Qute alias are false for a positive total.
     */
    @Test
    void isNegativeFalseForPositiveTotal() {
        TicketItem item = unitItem("1", "1.00", "1");
        assertFalse(item.isNegative());
        assertFalse(item.getNegative());
    }

    // --------------------------------------------------
    // setGlobalDiscount
    // --------------------------------------------------

    /**
     * A POSITIVE value ARMS the whole-ticket request: the kind and the value
     * are stored verbatim (guard false arm). The request is what survives on
     * the draft; the allocated amount is derived later.
     */
    @Test
    void setGlobalDiscountStoresPositiveRequest() {
        TicketState ts = new TicketState();
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("5.00"));
        assertEquals("GLOBAL_REMISE", ts.globalDiscountType);
        assertEquals(0, new BigDecimal("5.00").compareTo(ts.globalDiscountValue));
    }

    /**
     * A null value ERASES a previously armed request (first leg of the guard
     * true): the ticket goes back to its undiscounted state — this is how the
     * cashier cancels a whole-ticket gesture, and the kind is dropped too so
     * no orphan type survives.
     */
    @Test
    void setGlobalDiscountNullValueErasesPreviousRequest() {
        TicketState ts = new TicketState();
        ts.setGlobalDiscount("GLOBAL_DISCOUNT", new BigDecimal("10"));
        ts.setGlobalDiscount("GLOBAL_DISCOUNT", null);
        assertNull(ts.globalDiscountType);
        assertNull(ts.globalDiscountValue);
    }

    /**
     * A ZERO value erases as well (second leg, {@code signum() == 0}): typing
     * 0 on the modal is the documented way to clear the discount, and the
     * KIND is ignored on that path — it must not linger.
     */
    @Test
    void setGlobalDiscountZeroErases() {
        TicketState ts = new TicketState();
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("3"));
        ts.setGlobalDiscount("GLOBAL_REMISE", BigDecimal.ZERO);
        assertNull(ts.globalDiscountType);
        assertNull(ts.globalDiscountValue);
    }

    /**
     * A NEGATIVE value erases too (second leg, {@code signum() < 0}): the
     * guard is {@code <= 0}, so no gesture can ever INFLATE a ticket total.
     */
    @Test
    void setGlobalDiscountNegativeErases() {
        TicketState ts = new TicketState();
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("2"));
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("-1"));
        assertNull(ts.globalDiscountType);
        assertNull(ts.globalDiscountValue);
    }

    /**
     * A second positive request REPLACES the first, kind included: the
     * request is a single slot, never an accumulation — two gestures in a row
     * must not stack into a double discount.
     */
    @Test
    void setGlobalDiscountReplacesRatherThanAccumulates() {
        TicketState ts = new TicketState();
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("5.00"));
        ts.setGlobalDiscount("GLOBAL_DISCOUNT", new BigDecimal("10"));
        assertEquals("GLOBAL_DISCOUNT", ts.globalDiscountType);
        assertEquals(0, new BigDecimal("10").compareTo(ts.globalDiscountValue));
    }

    /**
     * The setter records the REQUEST only — it never computes the allocated
     * amount, which the next total recomputation produces (the request
     * survives, the allocation is derived).
     */
    @Test
    void setGlobalDiscountLeavesAllocationUntouched() {
        TicketState ts = new TicketState();
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("5.00"));
        assertNull(ts.globalDiscountApplied);
    }

    // --------------------------------------------------
    // allocateGlobalDiscount (through recomputeTotal)
    // --------------------------------------------------

    /**
     * Adds a line to the ticket and returns it, for building allocation cases.
     *
     * @param ts the ticket under test
     * @param price the unit price
     * @param qty the quantity
     * @return the created line
     */
    private TicketItem addLine(TicketState ts, String price, String qty) {
        TicketItem item = unitItem("EAN" + ts.items.size(), price, qty);
        ts.items.add(item);
        return item;
    }

    /**
     * With NO request armed, every share is wiped and no amount is applied
     * (first guard, {@code globalDiscountType == null} leg).
     */
    @Test
    void allocateWithoutRequestClearsShares() {
        TicketState ts = new TicketState();
        TicketItem line = addLine(ts, "10.00", "1");
        line.globalDiscountShare = new BigDecimal("3.00");
        ts.recomputeTotal();
        assertNull(line.globalDiscountShare);
        assertNull(ts.globalDiscountApplied);
    }

    /**
     * An armed request on an EMPTY ticket applies nothing (first guard,
     * {@code items.isEmpty()} leg).
     */
    @Test
    void allocateOnEmptyTicketAppliesNothing() {
        TicketState ts = new TicketState();
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("5.00"));
        ts.recomputeTotal();
        assertNull(ts.globalDiscountApplied);
    }

    /**
     * A euro request is capped at the base: asking 50 € on a 10 € ticket
     * discounts 10 €, never more — the total can reach zero but never turns
     * negative ({@code min(value, base)} leg).
     */
    @Test
    void allocateEurosIsCappedAtTheBase() {
        TicketState ts = new TicketState();
        TicketItem line = addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("50.00"));
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("10.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("10.00").compareTo(line.globalDiscountShare));
        assertEquals(0, BigDecimal.ZERO.compareTo(ts.totalAmount));
    }

    /**
     * A percentage request is computed on the base and spread pro rata: 10 %
     * on 10 € + 30 € gives 4,00 € split 1,00 / 3,00 (PERCENT leg).
     */
    @Test
    void allocatePercentSpreadsProRata() {
        TicketState ts = new TicketState();
        TicketItem small = addLine(ts, "10.00", "1");
        TicketItem large = addLine(ts, "30.00", "1");
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("4.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("1.00").compareTo(small.globalDiscountShare));
        assertEquals(0, new BigDecimal("3.00").compareTo(large.globalDiscountShare));
        assertEquals(0, new BigDecimal("36.00").compareTo(ts.totalAmount));
    }

    /**
     * The rounding RESIDUE lands on the LARGEST line so the shares always sum
     * back to the applied amount to the cent: 1,00 € over three 10 € lines
     * spreads 0,33 × 3 = 0,99, and the missing cent is added to one line.
     */
    @Test
    void allocateResidueGoesToTheLargestLine() {
        TicketState ts = new TicketState();
        TicketItem first = addLine(ts, "10.00", "1");
        TicketItem second = addLine(ts, "10.00", "1");
        TicketItem third = addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("1.00"));
        ts.recomputeTotal();
        BigDecimal sum = first.globalDiscountShare
                .add(second.globalDiscountShare).add(third.globalDiscountShare);
        assertEquals(0, new BigDecimal("1.00").compareTo(sum));
        assertEquals(0, new BigDecimal("1.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("29.00").compareTo(ts.totalAmount));
    }

    /**
     * BO-03-07-12: where the store spares the already-reduced lines, they leave
     * the BASE as well as the allocation — so the 10 % lands entirely on the
     * untouched line and the discount costs exactly what it says. Both ways a
     * line can be reduced are exercised: a manual modifier and an engine-valued
     * total under the local price.
     */
    @Test
    void allocateSparesTheReducedLinesWhenTheStoreSaysSo() {
        TicketState ts = new TicketState();
        TicketItem plain = addLine(ts, "10.00", "1");
        TicketItem manual = addLine(ts, "30.00", "1");
        manual.modifierType = com.intermarche.pos.ui.PriceModType.REMISE;
        TicketItem promoted = addLine(ts, "20.00", "1");
        promoted.valuedTotal = new BigDecimal("15.00");
        ts.globalDiscountSparesReducedLines = true;
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        // Base = the 10 € line alone: 1,00 € of discount, all of it on that line.
        assertEquals(0, new BigDecimal("1.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("1.00").compareTo(plain.globalDiscountShare));
        assertNull(manual.globalDiscountShare);
        assertNull(promoted.globalDiscountShare);
    }

    /**
     * The other leg: left ON — the catalogue default — the reduced lines are in
     * the base and receive their share, exactly as before the key existed.
     */
    @Test
    void allocateCoversTheReducedLinesByDefault() {
        TicketState ts = new TicketState();
        TicketItem plain = addLine(ts, "10.00", "1");
        TicketItem manual = addLine(ts, "30.00", "1");
        manual.modifierType = com.intermarche.pos.ui.PriceModType.REMISE;
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("4.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("1.00").compareTo(plain.globalDiscountShare));
        assertEquals(0, new BigDecimal("3.00").compareTo(manual.globalDiscountShare));
    }

    /**
     * A line the engine valued AT or ABOVE its local price is not a reduced
     * line, and one it never valued is not either — the two false legs of the
     * promotion test.
     */
    @Test
    void aLineTheEngineDidNotReduceIsNotSpared() {
        TicketState ts = new TicketState();
        TicketItem valuedUp = addLine(ts, "10.00", "1");
        valuedUp.valuedTotal = new BigDecimal("10.00");
        TicketItem unvalued = addLine(ts, "30.00", "1");
        ts.globalDiscountSparesReducedLines = true;
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("4.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("1.00").compareTo(valuedUp.globalDiscountShare));
        assertEquals(0, new BigDecimal("3.00").compareTo(unvalued.globalDiscountShare));
    }

    /**
     * BO-03-07-12: the rule leaves with the ticket, like every other decision
     * taken for one sale.
     */
    @Test
    void clearForgetsTheSparingRule() {
        TicketState ts = new TicketState();
        ts.globalDiscountSparesReducedLines = true;
        ts.clear();
        assertFalse(ts.globalDiscountSparesReducedLines);
    }

    /**
     * MONEY PRODUCTS are excluded from the base AND from the allocation: a
     * gift card is value, not goods — discounting it would create money.
     */
    @Test
    void allocateExcludesMoneyProducts() {
        TicketState ts = new TicketState();
        TicketItem goods = addLine(ts, "10.00", "1");
        TicketItem giftCard = addLine(ts, "50.00", "1");
        giftCard.moneyProduct = true;
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("1.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("1.00").compareTo(goods.globalDiscountShare));
        assertNull(giftCard.globalDiscountShare);
    }

    /**
     * NEGATIVE lines (deposits) stay out of the base and receive no share:
     * a discount never reduces what the customer is owed back.
     */
    @Test
    void allocateIgnoresNegativeLines() {
        TicketState ts = new TicketState();
        TicketItem goods = addLine(ts, "10.00", "1");
        TicketItem deposit = addLine(ts, "-2.00", "1");
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("1.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("1.00").compareTo(goods.globalDiscountShare));
        assertNull(deposit.globalDiscountShare);
    }

    /**
     * A ticket whose only positive value is a gift card has an EMPTY base:
     * nothing is applied ({@code base.signum() <= 0} guard).
     */
    @Test
    void allocateReturnsWhenBaseIsEmpty() {
        TicketState ts = new TicketState();
        TicketItem giftCard = addLine(ts, "50.00", "1");
        giftCard.moneyProduct = true;
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("5.00"));
        ts.recomputeTotal();
        assertNull(ts.globalDiscountApplied);
        assertNull(giftCard.globalDiscountShare);
    }

    /**
     * A percentage so small it rounds to zero applies NOTHING rather than a
     * zero-amount discount ({@code amount.signum() <= 0} guard).
     */
    @Test
    void allocateReturnsWhenAmountRoundsToZero() {
        TicketState ts = new TicketState();
        addLine(ts, "0.10", "1");
        ts.setGlobalDiscount("PERCENT", new BigDecimal("1"));
        ts.recomputeTotal();
        assertNull(ts.globalDiscountApplied);
    }

    /**
     * The allocation is IDEMPOTENT across recomputations: shares are reset
     * and rebuilt from the pre-share line totals, so a second scan (or a
     * second call) never compounds the discount.
     */
    @Test
    void allocateIsIdempotentAcrossRecomputations() {
        TicketState ts = new TicketState();
        TicketItem line = addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        ts.recomputeTotal();
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("1.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("1.00").compareTo(line.globalDiscountShare));
        assertEquals(0, new BigDecimal("9.00").compareTo(ts.totalAmount));
    }

    /**
     * The request SURVIVES a cart change and RE-ALLOCATES on the new base:
     * 10 % of 10 € becomes 10 % of 30 € when two lines join — the demo's
     * "la remise se ré-alloue au scan suivant".
     */
    @Test
    void allocateReallocatesWhenCartGrows() {
        TicketState ts = new TicketState();
        addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        addLine(ts, "20.00", "1");
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("3.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("27.00").compareTo(ts.totalAmount));
    }

    // --------------------------------------------------
    // getGlobalDiscountLabel / getGlobalDiscountAppliedFormatted
    // --------------------------------------------------

    /**
     * getGlobalDiscountLabel returns null when nothing was applied
     * (globalDiscountApplied == null, true arm of the L381 guard).
     */
    @Test
    void getGlobalDiscountLabelNullWhenNoDiscountApplied() {
        TicketState ts = new TicketState();
        assertNull(ts.getGlobalDiscountLabel());
    }

    /**
     * getGlobalDiscountLabel on an applied PERCENT request suffixes the rate,
     * trailing zeros stripped (applied non-null false arm of L381, PERCENT true
     * arm of L382).
     */
    @Test
    void getGlobalDiscountLabelPercentSuffixesRate() {
        TicketState ts = new TicketState();
        ts.setParent(TestMessages.stateWithMessages());
        addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        assertEquals("REMISE TICKET (10 %)", ts.getGlobalDiscountLabel());
    }

    /**
     * getGlobalDiscountLabel on an applied non-PERCENT (euro) request returns
     * the bare label with no suffix (PERCENT false arm of L382).
     */
    @Test
    void getGlobalDiscountLabelAmountHasNoSuffix() {
        TicketState ts = new TicketState();
        ts.setParent(TestMessages.stateWithMessages());
        addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("3.00"));
        ts.recomputeTotal();
        assertEquals("REMISE TICKET", ts.getGlobalDiscountLabel());
    }

    /**
     * getGlobalDiscountAppliedFormatted returns null when nothing was applied
     * (globalDiscountApplied == null, true arm of the L395 guard).
     */
    @Test
    void getGlobalDiscountAppliedFormattedNullWhenNoDiscount() {
        TicketState ts = new TicketState();
        assertNull(ts.getGlobalDiscountAppliedFormatted());
    }

    /**
     * getGlobalDiscountAppliedFormatted renders the applied amount signed and
     * with a French comma (applied non-null, false arm of the L395 guard).
     */
    @Test
    void getGlobalDiscountAppliedFormattedSignedFrenchComma() {
        TicketState ts = new TicketState();
        addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("3.00"));
        ts.recomputeTotal();
        assertEquals("-3,00", ts.getGlobalDiscountAppliedFormatted());
    }

    /**
     * The no-arg item constructor yields a serialization-friendly blank line.
     */
    @Test
    void itemDefaultConstructor() {
        TicketItem item = new TicketItem();
        assertNull(item.ean);
        assertEquals(0, BigDecimal.ZERO.compareTo(item.vatRate));
    }

    // --------------------------------------------------
    // The "à enlever" arming (LC-02-08-02)
    // --------------------------------------------------

    /**
     * Builds a ticket attached to a register state, which is what the arming needs.
     *
     * @return the state, its ticket wired to it
     */
    private com.intermarche.pos.ui.PosState statefulTicket() {
        com.intermarche.pos.ui.PosState state = new com.intermarche.pos.ui.PosState();
        state.ticket.setParent(state);
        return state;
    }

    /**
     * An armed key marks the next article and disarms itself: it arms ONE article, so
     * the rest of the basket is not quietly sent to the collection desk too.
     */
    @Test
    void theArmedKeyMarksOneArticleAndDisarms() {
        com.intermarche.pos.ui.PosState state = statefulTicket();
        state.collectArmed = true;
        state.ticket.addItem("3017620422003", null, "PATE", new BigDecimal("2.00"),
                BigDecimal.ONE, new BigDecimal("0.20"));
        assertTrue(state.ticket.items.get(0).toCollect);
        assertFalse(state.collectArmed);
    }

    /**
     * Nothing armed leaves an ordinary line — the false arm.
     */
    @Test
    void withoutArmingTheLineIsOrdinary() {
        com.intermarche.pos.ui.PosState state = statefulTicket();
        state.ticket.addItem("3017620422003", null, "PATE", new BigDecimal("2.00"),
                BigDecimal.ONE, new BigDecimal("0.20"));
        assertFalse(state.ticket.items.get(0).toCollect);
    }

    /**
     * A ticket with no register behind it is never armed — the null-parent leg, which
     * every plain unit test of this class takes.
     */
    @Test
    void aParentlessTicketIsNeverArmed() {
        TicketState ts = new TicketState();
        ts.addItem("3017620422003", null, "PATE", new BigDecimal("2.00"),
                BigDecimal.ONE, new BigDecimal("0.20"));
        assertFalse(ts.items.get(0).toCollect);
    }

    /**
     * TWO ARTICLES THAT LEAVE BY DIFFERENT DOORS ARE NOT THE SAME LINE: an armed scan
     * of an EAN already on the ticket makes a second line rather than merging, or the
     * whole quantity would land on the collection voucher.
     */
    @Test
    void aCollectedArticleDoesNotMergeIntoACarriedOne() {
        com.intermarche.pos.ui.PosState state = statefulTicket();
        state.ticket.addItem("3017620422003", null, "PATE", new BigDecimal("2.00"),
                BigDecimal.ONE, new BigDecimal("0.20"));
        state.collectArmed = true;
        state.ticket.addItem("3017620422003", null, "PATE", new BigDecimal("2.00"),
                BigDecimal.ONE, new BigDecimal("0.20"));
        assertEquals(2, state.ticket.items.size());
        assertFalse(state.ticket.items.get(0).toCollect);
        assertTrue(state.ticket.items.get(1).toCollect);
    }

    /**
     * Two collected articles of the same EAN DO merge, and the merge disarms the key
     * exactly as a new line does — the same-destination arm of the guard.
     */
    @Test
    void twoCollectedArticlesMerge() {
        com.intermarche.pos.ui.PosState state = statefulTicket();
        state.collectArmed = true;
        state.ticket.addItem("3017620422003", null, "PATE", new BigDecimal("2.00"),
                BigDecimal.ONE, new BigDecimal("0.20"));
        state.collectArmed = true;
        state.ticket.addItem("3017620422003", null, "PATE", new BigDecimal("2.00"),
                BigDecimal.ONE, new BigDecimal("0.20"));
        assertEquals(1, state.ticket.items.size());
        assertEquals(0, new BigDecimal("2").compareTo(state.ticket.items.get(0).quantity));
        assertTrue(state.ticket.items.get(0).toCollect);
        assertFalse(state.collectArmed);
    }

    /**
     * An unarmed scan of an EAN already marked does NOT merge into it either — the
     * mirror of the first case, which a cashier scanning the same article for the
     * customer's own bag takes.
     */
    @Test
    void aCarriedArticleDoesNotMergeIntoACollectedOne() {
        com.intermarche.pos.ui.PosState state = statefulTicket();
        state.collectArmed = true;
        state.ticket.addItem("3017620422003", null, "PATE", new BigDecimal("2.00"),
                BigDecimal.ONE, new BigDecimal("0.20"));
        state.ticket.addItem("3017620422003", null, "PATE", new BigDecimal("2.00"),
                BigDecimal.ONE, new BigDecimal("0.20"));
        assertEquals(2, state.ticket.items.size());
        assertTrue(state.ticket.items.get(0).toCollect);
        assertFalse(state.ticket.items.get(1).toCollect);
    }

    /**
     * An armed quantity replaces the one the caller passed on a UNIT line, and is
     * consumed by that one line: the article after it is rung at one
     * ({@code LC-02-13-04/05/06/07}).
     */
    @Test
    void addItemUsesTheArmedQuantityOnceOnAUnitLine() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        parent.armedQuantity = new BigDecimal("3");
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(0, new BigDecimal("3").compareTo(ts.items.get(0).quantity));
        assertNull(parent.armedQuantity);
        ts.addItem("456", null, "Bread", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(0, BigDecimal.ONE.compareTo(ts.items.get(1).quantity));
    }

    /**
     * An armed quantity applies to a MERGE too: three more of an article already on
     * the ticket raise its line by three, not by one.
     */
    @Test
    void addItemUsesTheArmedQuantityOnAMerge() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        parent.armedQuantity = new BigDecimal("3");
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(1, ts.items.size());
        assertEquals(0, new BigDecimal("4").compareTo(ts.items.get(0).quantity));
        assertNull(parent.armedQuantity);
    }

    /**
     * A WEIGHED line keeps the weight the scale measured: the arming is consumed all
     * the same, so it never leaks onto the article that follows
     * ({@code LC-02-13-09}).
     */
    @Test
    void addItemNeverOverwritesAWeightWithAnArmedQuantity() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        parent.armedQuantity = new BigDecimal("3");
        ts.addItem("123", "0042", "Tomates", new BigDecimal("2.00"), new BigDecimal("0.500"), null);
        assertEquals(0, new BigDecimal("0.500").compareTo(ts.items.get(0).quantity));
        assertNull(parent.armedQuantity);
    }

    /**
     * An empty PLU is a unit line, so the arming applies to it — the leg a null-only
     * test would leave unproven.
     */
    @Test
    void addItemTreatsAnEmptyPluAsAUnitLineForTheArming() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        parent.armedQuantity = new BigDecimal("3");
        ts.addItem("123", "", "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(0, new BigDecimal("3").compareTo(ts.items.get(0).quantity));
    }

    /**
     * With nothing armed, the caller's quantity stands — the arm where the register
     * has no figure kept.
     */
    @Test
    void addItemKeepsTheCallersQuantityWhenNothingIsArmed() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), new BigDecimal("2"), null);
        assertEquals(0, new BigDecimal("2").compareTo(ts.items.get(0).quantity));
    }

    /**
     * A parentless ticket has nowhere to read an arming from and rings the caller's
     * quantity — the null-parent arm of the arming guard.
     */
    @Test
    void addItemWithoutParentIgnoresAnyArming() {
        TicketState ts = new TicketState();
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), new BigDecimal("2"), null);
        assertEquals(0, new BigDecimal("2").compareTo(ts.items.get(0).quantity));
    }

    /**
     * The unit of measure travels with the line: the label badge shows the
     * quantity and its unit ("2,36 m"), and the price-per-unit decomposition lives
     * once in the detail line (P4) — no longer inlined in the badge. A line without
     * a unit keeps the plain wording and has no unit in its detail.
     */
    @Test
    void theLineShowsItsUnitOfMeasureWhenItHasOne() {
        TicketState ts = new TicketState();
        ts.addItem("123", null, "CABLE", new BigDecimal("4.99"), new BigDecimal("2.36"), null);
        TicketState.TicketItem line = ts.items.get(0);
        line.unitName = "m";
        assertTrue(line.getHtml().contains("2,36 m"));
        assertFalse(line.getHtml().contains("m x 4,99"));
        assertEquals("2,36 m x 4,99 €/m", line.getDetailLine());
        line.unitName = "";
        assertFalse(line.getHtml().contains("2,36 m"));
        line.unitName = null;
        assertFalse(line.getHtml().contains("2,36 m"));
    }

    /**
     * P3-5: the shared decomposition line covers its four cases — a multi-unit
     * piece, a measure unit (referential unit), a weighed line, and a
     * price-embedded line whose weight is deduced.
     */
    @Test
    void detailLineCoversTheFourCases() {
        assertEquals("5 x 2,50 €", TicketState.detailLineOf(new BigDecimal("5"),
                new BigDecimal("2.50"), null, false, null, null, "€"));
        assertEquals("1,25 m x 12,00 €/m", TicketState.detailLineOf(new BigDecimal("1.25"),
                new BigDecimal("12.00"), "m", false, null, null, "€"));
        assertEquals("0,188 kg x 17,99 €/kg", TicketState.detailLineOf(new BigDecimal("0.188"),
                new BigDecimal("17.99"), null, true, null, null, "€"));
        assertEquals("0,188 kg x 17,99 €/kg", TicketState.detailLineOf(new BigDecimal("1"),
                new BigDecimal("0"), null, false, new BigDecimal("0.188"), new BigDecimal("17.99"), "€"));
    }

    /**
     * P3-5: a single unit sold by the piece teaches nothing, so the detail line is
     * empty (the qty-not-above-one arm).
     */
    @Test
    void detailLineIsEmptyForASingleUnitPiece() {
        assertEquals("", TicketState.detailLineOf(new BigDecimal("1"),
                new BigDecimal("2.50"), null, false, null, null, "€"));
        assertEquals("", TicketState.detailLineOf(new BigDecimal("1"),
                new BigDecimal("2.50"), "", false, null, null, "€"));
    }

    /**
     * P3-5: the euro glyph is the caller's — "€" on screen, "E" on the ESC/POS
     * receipt.
     */
    @Test
    void detailLineUsesTheGivenEuroGlyph() {
        assertEquals("5 x 2,50 E", TicketState.detailLineOf(new BigDecimal("5"),
                new BigDecimal("2.50"), null, false, null, null, "E"));
    }

    // --- Remise globale et articles sans remise (BO-02-03-09) ---

    /**
     * A line whose article bans discounts receives NO share of the ticket
     * discount, and the other line carries the whole of it.
     */
    @Test
    void aLineBanningDiscountsGetsNoShareOfTheTicketDiscount() {
        TicketState ts = new TicketState();
        TicketItem banned = addLine(ts, "10.00", "1");
        banned.discountForbidden = true;
        TicketItem ordinary = addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("4.00"));
        ts.recomputeTotal();
        assertNull(banned.globalDiscountShare);
        assertEquals(0, new BigDecimal("4.00").compareTo(ordinary.globalDiscountShare));
        assertEquals(0, new BigDecimal("4.00").compareTo(ts.globalDiscountApplied));
    }

    /**
     * The banned line is out of the BASE too: a 50 % request on a ticket of two
     * ten-euro lines, one of them banned, discounts five euros — half of the
     * discountable ten — and not ten.
     */
    @Test
    void aBannedLineIsOutOfThePercentageBase() {
        TicketState ts = new TicketState();
        TicketItem banned = addLine(ts, "10.00", "1");
        banned.discountForbidden = true;
        addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("PERCENT", new BigDecimal("50"));
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("5.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("15.00").compareTo(ts.totalAmount));
    }

    /**
     * A euro request is capped at the DISCOUNTABLE base: asking 50 € on a
     * ticket whose only ten euros are banned discounts nothing at all.
     */
    @Test
    void aTicketOfBannedLinesOnlyDiscountsNothing() {
        TicketState ts = new TicketState();
        TicketItem banned = addLine(ts, "10.00", "1");
        banned.discountForbidden = true;
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("50.00"));
        ts.recomputeTotal();
        assertNull(ts.globalDiscountApplied);
        assertNull(banned.globalDiscountShare);
        assertEquals(0, new BigDecimal("10.00").compareTo(ts.totalAmount));
    }

    /**
     * With no line banned, the allocation is unchanged — the guard's false arm.
     */
    @Test
    void anOrdinaryTicketIsAllocatedAsBefore() {
        TicketState ts = new TicketState();
        TicketItem first = addLine(ts, "10.00", "1");
        TicketItem second = addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("4.00"));
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("2.00").compareTo(first.globalDiscountShare));
        assertEquals(0, new BigDecimal("2.00").compareTo(second.globalDiscountShare));
    }

    // --- Assiette titre-restaurant portée par les lignes (BO-02-03-06) ---

    /**
     * The eligible total sums the lines their article declares eligible, and
     * only those.
     */
    @Test
    void theEligibleTotalSumsTheEligibleLinesOnly() {
        TicketState ts = new TicketState();
        TicketItem eligible = addLine(ts, "12.34", "1");
        eligible.mealVoucherEligible = true;
        addLine(ts, "99.00", "1");
        assertEquals(0, new BigDecimal("12.34").compareTo(ts.mealVoucherEligibleTotal()));
    }

    /**
     * A SECOND eligible line adds to the base, quantity included.
     */
    @Test
    void theEligibleTotalAddsEveryEligibleLine() {
        TicketState ts = new TicketState();
        TicketItem first = addLine(ts, "10.00", "2");
        first.mealVoucherEligible = true;
        TicketItem second = addLine(ts, "1.50", "1");
        second.mealVoucherEligible = true;
        assertEquals(0, new BigDecimal("21.50").compareTo(ts.mealVoucherEligibleTotal()));
    }

    /**
     * A ticket with no eligible line has a zero base, and so does an empty one.
     */
    @Test
    void aTicketWithoutAnEligibleLineHasNoBase() {
        TicketState ts = new TicketState();
        addLine(ts, "10.00", "1");
        assertEquals(0, BigDecimal.ZERO.compareTo(ts.mealVoucherEligibleTotal()));
        assertEquals(0, BigDecimal.ZERO.compareTo(new TicketState().mealVoucherEligibleTotal()));
    }

    /**
     * A negative eligible line is skipped rather than subtracted: a deposit
     * return does not shrink what a meal ticket may settle.
     */
    @Test
    void aNegativeEligibleLineIsSkipped() {
        TicketState ts = new TicketState();
        TicketItem eligible = addLine(ts, "10.00", "1");
        eligible.mealVoucherEligible = true;
        TicketItem deposit = addLine(ts, "-1.00", "1");
        deposit.mealVoucherEligible = true;
        assertEquals(0, new BigDecimal("10.00").compareTo(ts.mealVoucherEligibleTotal()));
    }

    /**
     * {@code setWarning} lights the WARNING state: the message rides the
     * shared zone, it is neither a refusal nor a confirmation.
     */
    @org.junit.jupiter.api.Test
    void setWarningMarksTheZoneAsAWarning() {
        TicketState ticket = new TicketState();
        ticket.setWarning("PRIX ÉLEVÉ");
        org.junit.jupiter.api.Assertions.assertEquals("PRIX ÉLEVÉ", ticket.transientError);
        org.junit.jupiter.api.Assertions.assertTrue(ticket.transientWarn);
        org.junit.jupiter.api.Assertions.assertFalse(ticket.transientOk);
    }

    /**
     * A refusal after a warning clears the warning mark: the zone shows one
     * thing at a time and says which.
     */
    @org.junit.jupiter.api.Test
    void setErrorClearsTheWarningMark() {
        TicketState ticket = new TicketState();
        ticket.setWarning("PRIX ÉLEVÉ");
        ticket.setError("REFUS");
        org.junit.jupiter.api.Assertions.assertEquals("REFUS", ticket.transientError);
        org.junit.jupiter.api.Assertions.assertFalse(ticket.transientWarn);
        org.junit.jupiter.api.Assertions.assertFalse(ticket.transientOk);
    }

    /**
     * A confirmation after a warning clears the warning mark too.
     */
    @org.junit.jupiter.api.Test
    void setNoticeClearsTheWarningMark() {
        TicketState ticket = new TicketState();
        ticket.setWarning("PRIX ÉLEVÉ");
        ticket.setNotice("FAIT");
        org.junit.jupiter.api.Assertions.assertEquals("FAIT", ticket.transientError);
        org.junit.jupiter.api.Assertions.assertFalse(ticket.transientWarn);
        org.junit.jupiter.api.Assertions.assertTrue(ticket.transientOk);
    }

    /**
     * Clearing the ticket clears the warning mark with the message.
     */
    @org.junit.jupiter.api.Test
    void clearClearsTheWarningMark() {
        TicketState ticket = new TicketState();
        ticket.setWarning("PRIX ÉLEVÉ");
        ticket.clear();
        org.junit.jupiter.api.Assertions.assertNull(ticket.transientError);
        org.junit.jupiter.api.Assertions.assertFalse(ticket.transientWarn);
    }

    /**
     * {@code addItem} raises the entry flag on the parent for a NEW line.
     */
    @org.junit.jupiter.api.Test
    void addItemRaisesTheEntryFlagOnANewLine() {
        com.intermarche.pos.ui.PosState parent = new com.intermarche.pos.ui.PosState();
        parent.ticket.addItem("3000000000021", null, "WHISKY", new java.math.BigDecimal("24.90"),
                java.math.BigDecimal.ONE, new java.math.BigDecimal("0.20"));
        org.junit.jupiter.api.Assertions.assertTrue(parent.lineJustEntered);
    }

    /**
     * It raises it on a MERGE too: entering an article a second time is an
     * entry, even though no new line appears.
     */
    @org.junit.jupiter.api.Test
    void addItemRaisesTheEntryFlagOnAMerge() {
        com.intermarche.pos.ui.PosState parent = new com.intermarche.pos.ui.PosState();
        parent.ticket.addItem("3000000000021", null, "WHISKY", new java.math.BigDecimal("24.90"),
                java.math.BigDecimal.ONE, new java.math.BigDecimal("0.20"));
        parent.lineJustEntered = false;
        parent.ticket.addItem("3000000000021", null, "WHISKY", new java.math.BigDecimal("24.90"),
                java.math.BigDecimal.ONE, new java.math.BigDecimal("0.20"));
        org.junit.jupiter.api.Assertions.assertEquals(1, parent.ticket.items.size());
        org.junit.jupiter.api.Assertions.assertTrue(parent.lineJustEntered);
    }

    /**
     * {@code markSubtotal} on an empty ticket places nothing and returns false.
     */
    @Test
    void markSubtotalOnEmptyTicketReturnsFalse() {
        TicketState ts = new TicketState();
        assertFalse(ts.markSubtotal());
    }

    /**
     * {@code markSubtotal} marks the last line, breaks the merge chain and sums
     * the group since the previous marker.
     */
    @Test
    void markSubtotalMarksLastLineAndSumsGroup() {
        PosState parent = new PosState();
        parent.ticket.addItem("1", null, "A", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        parent.ticket.addItem("2", null, "B", new BigDecimal("3.00"), BigDecimal.ONE, BigDecimal.ZERO);
        assertTrue(parent.ticket.markSubtotal());
        TicketItem last = parent.ticket.items.get(1);
        assertTrue(last.subtotalBoundary);
        assertEquals(0, last.subtotalAmount.compareTo(new BigDecimal("5.00")));
        assertNull(parent.lastEnteredItemId);
    }

    /**
     * A second marker sums only the lines since the first, and each boundary
     * carries its own group total.
     */
    @Test
    void markSubtotalSumsSinceThePreviousMarker() {
        PosState parent = new PosState();
        parent.ticket.addItem("1", null, "A", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        parent.ticket.markSubtotal();
        parent.ticket.addItem("2", null, "B", new BigDecimal("3.00"), BigDecimal.ONE, BigDecimal.ZERO);
        parent.ticket.addItem("3", null, "C", new BigDecimal("4.00"), BigDecimal.ONE, BigDecimal.ZERO);
        parent.ticket.markSubtotal();
        assertEquals(0, parent.ticket.items.get(0).subtotalAmount.compareTo(new BigDecimal("2.00")));
        assertEquals(0, parent.ticket.items.get(2).subtotalAmount.compareTo(new BigDecimal("7.00")));
    }

    /**
     * A non-boundary line reports no subtotal (the non-boundary arm of the
     * recompute walk and of {@code getSubtotalFormatted}).
     */
    @Test
    void nonBoundaryLineHasNoSubtotal() {
        PosState parent = new PosState();
        parent.ticket.addItem("1", null, "A", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        TicketItem only = parent.ticket.items.get(0);
        assertNull(only.subtotalAmount);
        assertNull(only.getSubtotalFormatted());
    }

    /**
     * A boundary line formats its subtotal for display.
     */
    @Test
    void boundaryLineFormatsItsSubtotal() {
        PosState parent = new PosState();
        parent.ticket.addItem("1", null, "A", new BigDecimal("2.50"), BigDecimal.ONE, BigDecimal.ZERO);
        parent.ticket.markSubtotal();
        assertEquals("2,50", parent.ticket.items.get(0).getSubtotalFormatted());
    }

    /**
     * A boundary flag without a computed amount still formats to null (the
     * null-amount arm of {@code getSubtotalFormatted}).
     */
    @Test
    void boundaryWithoutAmountFormatsToNull() {
        TicketItem item = new TicketItem("1", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, BigDecimal.ZERO);
        item.subtotalBoundary = true;
        assertNull(item.getSubtotalFormatted());
    }

    /**
     * addItem in RETURN MODE (EXP-40) rings the article as a return: a negative
     * line marked {@code returnLine}, dragging the signed total below zero
     * (the {@code parent.returnMode} true arm).
     */
    @Test
    void addItemInReturnModeCreatesNegativeReturnLine() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        parent.returnMode = true;
        ts.setParent(parent);
        ts.addItem("123", null, "Milk", new BigDecimal("2.00"), BigDecimal.ONE, new BigDecimal("0.2000"));
        assertEquals(1, ts.items.size());
        assertTrue(ts.items.get(0).returnLine);
        assertTrue(ts.items.get(0).isNegative());
        assertEquals(0, ts.totalAmount.compareTo(new BigDecimal("-2.00")));
    }

    /**
     * A return line never merges into a positive sale line of the same EAN
     * (EXP-38): folding it in would quietly reduce the sale line instead of
     * standing as its own refunded line (the {@code !asReturn} mergeable arm).
     */
    @Test
    void returnLineDoesNotMergeIntoPositiveSaleLine() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        ts.addItem("123", null, "Milk", new BigDecimal("2.00"), BigDecimal.ONE, new BigDecimal("0.2000"));
        parent.returnMode = true;
        ts.addItem("123", null, "Milk", new BigDecimal("2.00"), BigDecimal.ONE, new BigDecimal("0.2000"));
        assertEquals(2, ts.items.size());
        assertEquals(0, ts.totalAmount.compareTo(BigDecimal.ZERO));
    }

    /**
     * {@code hasEmbeddedWeight} is true only when BOTH the deduced weight and the
     * price per kilo are set (EXP-26): both-set true arm.
     */
    @Test
    void hasEmbeddedWeightTrueWhenBothSet() {
        TicketItem item = new TicketItem("1", null, "L", BigDecimal.ONE, BigDecimal.ONE, null);
        item.embeddedWeight = new BigDecimal("0.750");
        item.embeddedUnitPrice = new BigDecimal("4.00");
        assertTrue(item.hasEmbeddedWeight());
        assertEquals("0,750 kg à 4,00 €/kg", item.getEmbeddedWeightLabel());
    }

    /**
     * {@code hasEmbeddedWeight} is false when either the weight or the price per
     * kilo is null (EXP-26): both null-arms, and the label is then empty.
     */
    @Test
    void hasEmbeddedWeightFalseWhenEitherNull() {
        TicketItem noPrice = new TicketItem("1", null, "L", BigDecimal.ONE, BigDecimal.ONE, null);
        noPrice.embeddedWeight = new BigDecimal("0.750");
        assertFalse(noPrice.hasEmbeddedWeight());
        assertEquals("", noPrice.getEmbeddedWeightLabel());
        TicketItem noWeight = new TicketItem("1", null, "L", BigDecimal.ONE, BigDecimal.ONE, null);
        noWeight.embeddedUnitPrice = new BigDecimal("4.00");
        assertFalse(noWeight.hasEmbeddedWeight());
        assertEquals("", noWeight.getEmbeddedWeightLabel());
    }

    /**
     * A MERGE NEVER CROSSES A SUB-TOTAL. The same EAN scanned after a sub-total
     * marker starts a NEW line instead of raising the quantity of the line the
     * marker already totalled — otherwise the printed and announced figure stops
     * matching the lines above it (reported by the field expert).
     */
    @Test
    void aMergeDoesNotCrossASubtotal() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        assertTrue(ts.markSubtotal());
        ts.addItem("111", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        assertEquals(2, ts.items.size());
        assertEquals(0, BigDecimal.ONE.compareTo(ts.items.get(0).quantity));
        assertEquals(0, BigDecimal.ONE.compareTo(ts.items.get(1).quantity));
    }

    /**
     * BELOW the frontier the merge works as before: a second scan of the same
     * EAN entered after the marker folds into the line entered after it too —
     * the floor is the LAST frontier, not a ban on merging.
     */
    @Test
    void aMergeStillWorksBelowTheLastSubtotal() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.markSubtotal();
        ts.addItem("222", null, "LAIT", new BigDecimal("1.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.addItem("222", null, "LAIT", new BigDecimal("1.00"), BigDecimal.ONE, BigDecimal.ZERO);
        assertEquals(2, ts.items.size());
        assertEquals(0, new BigDecimal("2").compareTo(ts.items.get(1).quantity));
    }

    /**
     * With TWO frontiers only the LAST one floors the merge: a scan after the
     * second marker ignores the line that sits between the two markers.
     */
    @Test
    void onlyTheLastSubtotalFloorsTheMerge() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.markSubtotal();
        ts.addItem("222", null, "LAIT", new BigDecimal("1.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.markSubtotal();
        ts.addItem("222", null, "LAIT", new BigDecimal("1.00"), BigDecimal.ONE, BigDecimal.ZERO);
        assertEquals(3, ts.items.size());
    }

    /**
     * With NO frontier at all the floor is zero and the historical merge is
     * unchanged — the arm of the loop that returns 0.
     */
    @Test
    void withoutASubtotalTheMergeReachesTheWholeTicket() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.addItem("222", null, "LAIT", new BigDecimal("1.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.addItem("111", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        assertEquals(2, ts.items.size());
        assertEquals(0, new BigDecimal("2").compareTo(ts.items.get(0).quantity));
    }

    /**
     * {@code cancelLine} takes the line out of the cart, keeps it aside for the
     * screen with its rank, and drops it from the total.
     */
    @Test
    void cancelLineParksTheLineForTheScreen() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.addItem("222", null, "LAIT", new BigDecimal("1.00"), BigDecimal.ONE, BigDecimal.ZERO);
        String uid = ts.items.get(0).uid;
        assertTrue(ts.cancelLine(uid));
        assertEquals(1, ts.items.size());
        assertEquals(1, ts.cancelledItems.size());
        assertTrue(ts.cancelledItems.get(0).cancelled);
        assertEquals(0, ts.cancelledItems.get(0).displayIndex);
        assertEquals(0, new BigDecimal("1.00").compareTo(ts.totalAmount));
    }

    /**
     * {@code cancelLine} answers false on a null uid and on an unknown one, and
     * parks nothing — the two refusal arms.
     */
    @Test
    void cancelLineRefusesANullAndAnUnknownUid() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        assertFalse(ts.cancelLine(null));
        assertFalse(ts.cancelLine("nope"));
        assertTrue(ts.cancelledItems.isEmpty());
        assertEquals(1, ts.items.size());
    }

    /**
     * A cancelled line does NOT carry its sub-total frontier away: the marker
     * belongs to the group, and a parked line holding it would draw a second,
     * amountless sub-total on the screen.
     */
    @Test
    void aCancelledLineDropsItsSubtotalFrontier() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.markSubtotal();
        assertTrue(ts.items.get(0).subtotalBoundary);
        ts.cancelLine(ts.items.get(0).uid);
        assertFalse(ts.cancelledItems.get(0).subtotalBoundary);
        assertNull(ts.cancelledItems.get(0).subtotalAmount);
    }

    /**
     * {@code displayItems} returns the cart itself when nothing was cancelled —
     * the short arm — and puts a cancelled line back at its rank otherwise.
     */
    @Test
    void displayItemsPutsCancelledLinesBackAtTheirRank() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.addItem("222", null, "LAIT", new BigDecimal("1.00"), BigDecimal.ONE, BigDecimal.ZERO);
        assertSame(ts.items, ts.displayItems());
        ts.cancelLine(ts.items.get(0).uid);
        java.util.List<TicketState.TicketItem> shown = ts.displayItems();
        assertEquals(2, shown.size());
        assertEquals("PAIN", shown.get(0).label);
        assertTrue(shown.get(0).cancelled);
        assertEquals("LAIT", shown.get(1).label);
    }

    /**
     * A recorded rank that no longer fits — the cart shrank under it — is
     * clamped to the end rather than trusted, so the display never throws.
     */
    @Test
    void displayItemsClampsADriftedRank() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.addItem("222", null, "LAIT", new BigDecimal("1.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.addItem("333", null, "OEUF", new BigDecimal("3.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.cancelLine(ts.items.get(2).uid);
        ts.removeItemById(ts.items.get(0).uid);
        ts.removeItemById(ts.items.get(0).uid);
        java.util.List<TicketState.TicketItem> shown = ts.displayItems();
        assertEquals(1, shown.size());
        assertEquals("OEUF", shown.get(0).label);
    }

    /**
     * A NEGATIVE recorded rank is clamped too (the first leg of the clamp).
     */
    @Test
    void displayItemsClampsANegativeRank() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.cancelLine(ts.items.get(0).uid);
        ts.cancelledItems.get(0).displayIndex = -5;
        assertEquals(1, ts.displayItems().size());
    }

    /**
     * {@code clear} forgets the cancelled lines as well: a new sale starts with
     * an empty screen, witnesses included.
     */
    @Test
    void clearForgetsTheCancelledLines() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "PAIN", new BigDecimal("2.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.addItem("222", null, "LAIT", new BigDecimal("1.00"), BigDecimal.ONE, BigDecimal.ZERO);
        ts.cancelLine(ts.items.get(0).uid);
        ts.clear();
        assertTrue(ts.items.isEmpty());
        assertTrue(ts.cancelledItems.isEmpty());
    }

    /**
     * An announced container tare is read ONCE (BO-03-06-60): the second reader
     * gets nothing, so the tare lightens one line and not the next.
     */
    @Test
    void thePendingTareIsReadOnce() {
        TicketState ts = new TicketState();
        assertNull(ts.consumePendingTare());
        ts.pendingTareKg = new BigDecimal("0.250");
        assertEquals(new BigDecimal("0.250"), ts.consumePendingTare());
        assertNull(ts.pendingTareKg);
        assertNull(ts.consumePendingTare());
    }

    /**
     * A tare leaves with the ticket: the next customer does not inherit the
     * previous one's bowl.
     */
    @Test
    void clearForgetsTheAnnouncedTare() {
        TicketState ts = new TicketState();
        ts.pendingTareKg = new BigDecimal("0.250");
        ts.clear();
        assertNull(ts.pendingTareKg);
    }

    /**
     * Arming a promotion by barcode (BO-03-06-43): a name is armed once, the
     * same name a second time changes nothing, and a null or blank name is
     * refused rather than armed — the engine would have nothing to resolve.
     */
    @Test
    void armPromotionTakesANameOnceAndRefusesAnEmptyOne() {
        TicketState ts = new TicketState();
        assertTrue(ts.scannedPromotions.isEmpty());
        assertFalse(ts.armPromotion(null));
        assertFalse(ts.armPromotion("   "));
        assertTrue(ts.scannedPromotions.isEmpty());
        assertTrue(ts.armPromotion("  PROMO_RENTREE  "));
        assertEquals(java.util.List.of("PROMO_RENTREE"), ts.scannedPromotions);
        assertFalse(ts.armPromotion("PROMO_RENTREE"));
        assertEquals(1, ts.scannedPromotions.size());
        assertTrue(ts.armPromotion("PROMO_NOEL"));
        assertEquals(java.util.List.of("PROMO_RENTREE", "PROMO_NOEL"), ts.scannedPromotions);
    }

    /**
     * {@code storePendingActivation} (BO-03-06-44) stores the code on the
     * awaited line, trimmed, and CLOSES the wait whatever the outcome: nothing
     * awaited, a null code, a blank code, a line that no longer exists and a
     * null entry in the list all answer null without leaving the next scan
     * hostage.
     */
    @Test
    void storePendingActivationFindsTheAwaitedLineAndAlwaysClosesTheWait() {
        TicketState ts = new TicketState();
        assertNull(ts.storePendingActivation("ACT-1"));
        TicketItem line = new TicketItem("1", null, "CARTE", BigDecimal.ONE,
                BigDecimal.ONE, null);
        ts.items.add(null);
        ts.items.add(line);

        ts.pendingActivationUid = line.uid;
        assertNull(ts.storePendingActivation(null));
        assertNull(ts.pendingActivationUid);
        assertNull(line.activationCode);

        ts.pendingActivationUid = line.uid;
        assertNull(ts.storePendingActivation("   "));
        assertNull(ts.pendingActivationUid);
        assertNull(line.activationCode);

        ts.pendingActivationUid = "autre";
        assertNull(ts.storePendingActivation("ACT-1"));
        assertNull(ts.pendingActivationUid);
        assertNull(line.activationCode);

        ts.pendingActivationUid = line.uid;
        assertSame(line, ts.storePendingActivation("  ACT-1  "));
        assertEquals("ACT-1", line.activationCode);
        assertNull(ts.pendingActivationUid);
    }

    /**
     * An awaited activation leaves with the ticket: the next customer's first
     * scan is not swallowed as the previous customer's gift-card code
     * (BO-03-06-44).
     */
    @Test
    void clearForgetsTheAwaitedActivation() {
        TicketState ts = new TicketState();
        ts.pendingActivationUid = "une-ligne";
        ts.clear();
        assertNull(ts.pendingActivationUid);
    }

    /**
     * A promotion leaves with the ticket: the promotion one customer triggered
     * is not the next customer's (BO-03-06-43).
     */
    @Test
    void clearForgetsTheArmedPromotions() {
        TicketState ts = new TicketState();
        assertTrue(ts.armPromotion("PROMO_RENTREE"));
        ts.clear();
        assertTrue(ts.scannedPromotions.isEmpty());
    }
}
