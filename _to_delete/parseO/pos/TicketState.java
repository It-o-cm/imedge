package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.PriceModType;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * In-memory state of the ticket being built at the register.
 * <p>
 * All monetary amounts and quantities are held as {@link BigDecimal} (phase 0):
 * unit prices and totals use up to 4 decimals internally, displays are rounded
 * to 2 decimals. Physical weights coming from the scale remain double at the
 * hardware boundary and are converted to BigDecimal when a line is created.
 * <p>
 * Phase 1: each line carries the VAT rate captured at sale time, and the
 * ticket total is the sum of the line totals rounded to the cent — the same
 * rule the fiscal persistence applies, so the displayed, charged and persisted
 * amounts are identical.
 * <p>
 * The item uid minted here is the FIRST LIFE of the line identity: it
 * becomes the persisted lineUid, the sync key and the future valuation
 * lineId — same string end to end. Merge policy lives at addItem: only an
 * unmodified unit EAN line at the same price absorbs a new scan; anything
 * weighed, negative, price-modified or code-less starts its own line. The
 * transient scanned-sticker set (anti double-scan of price-embedded 2x
 * labels) is deliberately in-memory only: a restart forgets it, an
 * accepted limit.
 */
public class TicketState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Parent POS state, used to propagate change notifications. Transient: not serialized. */
    private transient PosState parent;

    /** The ordered list of ticket lines. */
    public List<TicketItem> items = new ArrayList<>();

    /**
     * The lines CANCELLED during this sale, kept aside for the screen.
     *
     * <p>A CANCELLATION IS A FACT OF THE SALE, not an erasure of it. The draft
     * already keeps it — {@code cancelItemById} marks the persisted line before
     * the cart lets go of it (lot C4, {@code BO-04-01-16}) — but the register
     * dropped it from its own state, so the operator and the customer saw the
     * line vanish and had nothing left to point at. It now stays on the screen,
     * struck through.
     *
     * <p>HELD IN A LIST OF ITS OWN, and that is the whole design. Left in
     * {@link #items} a cancelled line would be counted by the total, reached by
     * the merge, selectable, targetable by the price gestures, pushed to the
     * store node and printed on the receipt — six behaviours to teach about a
     * seventh state. Parked here it is invisible to every one of them by
     * construction, and visible to the one surface that asked for it.
     */
    public List<TicketItem> cancelledItems = new ArrayList<>();

    /**
     * Price-embedded scale-label codes already scanned on this ticket, to
     * refuse the accidental double scan of one physical sticker (in-memory
     * only: cleared with the ticket, empty again after a restart recovery).
     */
    public transient Set<String> scannedStickerCodes = new HashSet<>();

    /**
     * The uid of the line waiting for its activation code (BO-03-06-44), or
     * null when the register is not waiting for a second scan.
     *
     * <p>ONE line at a time, deliberately: the register asks for the code of
     * the article it has just rung, and the operator answers before ringing
     * the next one. Queuing several would make the second scan ambiguous, which
     * is the one thing a code that activates a gift card cannot be.
     */
    public transient String pendingActivationUid = null;

    /**
     * The names of the promotions a scanned barcode armed on this ticket
     * (BO-03-06-43), in the order they were armed.
     *
     * <p>A list and not a set, because the order is what the operator saw, and
     * {@link #armPromotion(String)} is what keeps it free of duplicates. Held
     * on the ticket like the tare and the sticker codes above: a promotion a
     * customer triggered has no business reaching the next one.
     */
    public List<String> scannedPromotions = new ArrayList<>();

    /**
     * The container weight a TARE barcode announced, in kilograms, waiting to be
     * deducted from the next weighed line (BO-03-06-60), or null when none was
     * announced.
     *
     * <p>Held on the ticket and not on the register: a tare announced on one
     * ticket has no business reaching the next customer, so it leaves with the
     * ticket like the scanned sticker codes above.
     */
    public BigDecimal pendingTareKg = null;

    /**
     * Whether the ticket-level discount leaves the already-reduced lines alone
     * (BO-03-07-12), as the store administered it when the gesture was made.
     *
     * <p>Carried on the ticket and not read from the back office at every
     * allocation: the allocation runs at every recomputation of the total, and
     * the rule belongs to the gesture that was made — a parameter changed
     * mid-sale must not silently re-cut a discount already shown to the
     * customer.
     */
    public boolean globalDiscountSparesReducedLines = false;

    /** Running total of the ticket (tax included), unrounded accumulation. */
    public BigDecimal totalAmount = BigDecimal.ZERO;

    /** Last weight reported by the scale (kg). Hardware boundary value, kept as double. */
    public double currentWeight = 0.0;

    /** Last weight actually used for a weighed line, for duplicate-weighing detection. */
    public double lastRecordedWeight = Double.NaN;

    /**
     * Highest age threshold ALREADY VERIFIED on this ticket (0 = none): once
     * the cashier checked an ID for 18, further 18-restricted scans of the
     * same sale pass without a second prompt. Deliberately NOT persisted on
     * the draft — after a crash recovery the check is asked again, the
     * conservative side of the law.
     */
    public int ageVerifiedThreshold = 0;

    /**
     * Requested ticket-level discount kind: "AMOUNT" (euros) or "PERCENT",
     * or null when no global discount is set (phase: global ticket
     * discount). The request is the durable intent; the allocation is
     * recomputed at every total recomputation.
     */
    public String globalDiscountType = null;

    /** Requested ticket-level discount value (euros or percent). */
    public BigDecimal globalDiscountValue = null;

    /**
     * The APPLIED global-discount amount of the last recomputation (what the
     * screen, the printers and the draft show), or null when none.
     */
    public BigDecimal globalDiscountApplied = null;

    /**
     * Sets (or clears, with a null/zero value) the ticket-level discount
     * request. Allocation happens at the next total recomputation.
     *
     * @param kind "AMOUNT" or "PERCENT"
     * @param value the requested value, or null/zero to clear
     */
    public void setGlobalDiscount(String kind, BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            globalDiscountType = null;
            globalDiscountValue = null;
        } else {
            globalDiscountType = kind;
            globalDiscountValue = value;
        }
    }

    /** Transient message shown on the ticket area — a refusal or a confirmation. */
    public String transientError = null;

    /**
     * True when {@link #transientError} carries a CONFIRMATION rather than a
     * refusal.
     *
     * <p>The zone was named after errors and drawn as one — red, and an "ERR"
     * marker on the right — but confirmations have always ridden it too
     * (SUPERVISEUR PRÉVENU). A register that answers a successful print with a
     * red ERR is a register the operator learns to distrust, so the zone now
     * says which of the two it is showing.
     */
    public boolean transientOk = false;

    /**
     * True when {@link #transientError} carries a WARNING rather than a
     * refusal or a confirmation.
     *
     * <p>Three things ride one zone and they do not mean the same: a refusal
     * stops the gesture, a confirmation acknowledges it, a warning lets it
     * through and asks the operator to look. Drawing a warning in red with
     * ERR would teach the cashier to ignore red; drawing it green with OK
     * would hide it. Hence the third state rather than a second use of
     * {@link #transientOk}.
     */
    public boolean transientWarn = false;

    /**
     * Attaches the parent POS state so that mutations can bump its version.
     *
     * @param parent the owning POS state
     */
    public void setParent(PosState parent) {
        this.parent = parent;
    }

    /**
     * Notifies the parent state that this ticket changed (polling version bump).
     */
    void onChange() {
        if (this.parent != null) {
            this.parent.touch();
        }
    }

    /**
     * Adds an item to the ticket.
     * <p>
     * Phase 3 merge rules: only unit EAN lines merge, and only into a line
     * that carries no price modification, is not negative, and has the same
     * unit price as the incoming item. Weighed lines (PLU) never merge — each
     * weighing is its own line — and neither do deposit or in-store sticker
     * lines (no code carried).
     *
     * @param ean the EAN code, or null
     * @param plu the PLU code, or null
     * @param label the display label of the line
     * @param unitPrice the unit price including tax
     * @param qtyToAdd the quantity to add (units, or kg for weighed items)
     * @param vatRate the VAT rate captured at sale time (e.g. 0.2000), or null for 0%
     */
    public void addItem(String ean, String plu, String label, BigDecimal unitPrice, BigDecimal qtyToAdd, BigDecimal vatRate) {
        // LC-02-08-02: the "à enlever" key arms ONE article, and this is the funnel
        // every way of entering one goes through — a scan, a keyed EAN, a group tile.
        // The same property is what carries lineJustEntered, raised on BOTH exits
        // below (a merge into an existing line is an entry too) and consumed once
        // by the service that owns the settings: the state records that an article
        // was entered, the service decides whether that deserves a message.
        // Reading the flag here rather than at each entry point is what makes the
        // three of them behave the same without any of them knowing about the desk.
        boolean toCollect = parent != null && parent.collectArmed;
        // EXP-38/40: in return mode (the day's return till) an entered article
        // rings as a RETURN — a negative line the customer is refunded for — so
        // the funnel negates the quantity and marks the line. The same funnel
        // serves the ordinary sale, so one flag turns every entry path around.
        boolean asReturn = parent != null && parent.returnMode;
        // Only unit EAN sales are mergeable (weighed lines: one line per weighing).
        // A RETURN never merges: folding it into a positive sale line would quietly
        // reduce that line instead of standing as its own refunded line (EXP-38).
        boolean mergeable = (plu == null || plu.isEmpty()) && ean != null && !ean.isEmpty() && !asReturn;
        // LC-02-13-04/05/06: an armed quantity replaces the one the caller passed —
        // on a UNIT line only. A weighed line's quantity is a weight the scale
        // measured, and no key may overwrite a measurement; the arming is consumed
        // all the same, so it never leaks onto the article after it.
        BigDecimal quantity = qtyToAdd;
        if (parent != null && parent.armedQuantity != null) {
            if (plu == null || plu.isEmpty()) {
                quantity = parent.armedQuantity;
            }
            parent.armedQuantity = null;
        }

        if (mergeable) {
            // A MERGE NEVER CROSSES A SUB-TOTAL. The sub-total is a PRINTED and
            // ANNOUNCED figure: the operator pressed the key, the customer read
            // the amount, and the lines above it are settled as far as the two of
            // them are concerned. Folding a new scan into a line that sits above
            // that frontier raises a quantity the sub-total already counted, so
            // the figure on the paper stops matching the lines it totalled —
            // found by the field expert, and visible on the first scan of an
            // article the customer had already taken before asking for a
            // sub-total. The search therefore starts AFTER the last frontier.
            for (TicketItem item : items.subList(mergeFloor(), items.size())) {
                boolean sameEan = item.ean != null && item.ean.equals(ean)
                        && (item.plu == null || item.plu.isEmpty());
                if (!sameEan) continue;

                boolean unmodified = item.modifierLabel == null
                        && item.unitPrice.compareTo(item.originalUnitPrice) == 0;
                boolean samePrice = item.unitPrice.compareTo(unitPrice) == 0;
                boolean positive = item.getTotalPrice().signum() >= 0;
                // Two articles that leave the shop by different doors are not the
                // same line: merging a collected one into a carried one would put
                // the whole quantity on the collection voucher, or none of it.
                boolean sameDestination = item.toCollect == toCollect;

                if (unmodified && samePrice && positive && sameDestination) {
                    item.quantity = item.quantity.add(quantity);
                    recomputeTotal();
                    if (parent != null) {
                        parent.lastEnteredItemId = item.uid;
                        parent.collectArmed = false;
                        parent.lineJustEntered = true;
                        // P1: bring the view onto the line just fed.
                        parent.showLinePage(item.uid);
                    }
                    onChange();
                    return;
                }
                // A matching EAN that cannot merge (modified price, markdown...)
                // falls through: a distinct line is created below.
                break;
            }
        }

        TicketItem newItem = new TicketItem(ean, plu, label, unitPrice,
                asReturn ? quantity.negate() : quantity, vatRate);
        newItem.toCollect = toCollect;
        newItem.returnLine = asReturn;
        items.add(newItem);
        recomputeTotal();

        if (parent != null) {
            parent.lastEnteredItemId = newItem.uid;
            parent.collectArmed = false;
            parent.lineJustEntered = true;
            // P1: a new line is on the last page — bring the view onto it.
            parent.showLinePage(newItem.uid);
        }

        onChange();
    }


    /**
     * CANCELS the line identified by its uid: it leaves the cart and stays on
     * the screen, struck through at the rank it occupied.
     *
     * <p>Distinct from {@link #removeItemById(String)}, which erases a line that
     * was never sold — the solidarity-rounding line withdrawn at payment. A
     * cancellation is remembered; a withdrawal is not.
     *
     * <p>The sub-total frontier does NOT travel with it: the marker belongs to
     * the group of lines, not to the line that happened to be last when the key
     * was pressed, and a cancelled line carrying it would draw a second,
     * amountless sub-total on the screen.
     *
     * @param uid the unique identifier of the line to cancel
     * @return true when a line was cancelled
     */
    public boolean cancelLine(String uid) {
        if (uid == null) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            TicketItem item = items.get(i);
            if (!item.uid.equals(uid)) {
                continue;
            }
            item.cancelled = true;
            item.subtotalBoundary = false;
            item.subtotalAmount = null;
            item.displayIndex = i;
            items.remove(i);
            cancelledItems.add(item);
            recomputeTotal();
            onChange();
            return true;
        }
        return false;
    }

    /**
     * The lines the screen shows: the live ones, with the cancelled ones put back
     * at the rank they held.
     *
     * <p>A cancelled line's recorded rank can drift — lines entered or cancelled
     * after it move the list under it — so the insertion is clamped to the end
     * rather than trusted. The order is for the eye, not for arithmetic.
     *
     * @return the display sequence, live and cancelled lines together
     */
    public List<TicketItem> displayItems() {
        if (cancelledItems.isEmpty()) {
            return items;
        }
        List<TicketItem> out = new ArrayList<>(items);
        for (TicketItem cancelled : cancelledItems) {
            int at = cancelled.displayIndex;
            if (at < 0 || at > out.size()) {
                at = out.size();
            }
            out.add(at, cancelled);
        }
        return out;
    }

    /**
     * Removes the line identified by its uid, if present, and recomputes the total.
     *
     * @param uid the unique identifier of the line to remove
     */
    public void removeItemById(String uid) {
        if (uid == null) return;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).uid.equals(uid)) {
                items.remove(i);
                recomputeTotal();
                onChange();
                return;
            }
        }
    }

    /**
     * Recomputes the ticket total as the sum of the line totals rounded to the
     * cent — the fiscal aggregation rule shared with the persistence layer.
     */
    public void recomputeTotal() {
        allocateGlobalDiscount();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal sinceMarker = BigDecimal.ZERO;
        for (TicketItem item : items) {
            BigDecimal lineTotal = item.getTotalPrice().setScale(2, RoundingMode.HALF_UP);
            total = total.add(lineTotal);
            // EXP-43: the running sum since the previous subtotal marker, exposed on
            // the boundary line. It never splits the ticket — total above sums every
            // line regardless of markers.
            sinceMarker = sinceMarker.add(lineTotal);
            if (item.subtotalBoundary) {
                item.subtotalAmount = sinceMarker;
                sinceMarker = BigDecimal.ZERO;
            } else {
                item.subtotalAmount = null;
            }
        }
        this.totalAmount = total;
    }

    /**
     * Index of the first line a merge may reach: just after the last line
     * carrying a sub-total frontier, or zero when the ticket has none.
     *
     * @return the lowest index a merge may consider
     */
    private int mergeFloor() {
        for (int index = items.size() - 1; index >= 0; index--) {
            if (items.get(index).subtotalBoundary) {
                return index + 1;
            }
        }
        return 0;
    }

    /**
     * Places a SOUS-TOTAL marker after the last line (EXP-43): the sum since the
     * previous marker is drawn as a subtotal there. The merge chain is broken so
     * the next identical scan starts a new line — the next shopper's item does not
     * fold into the previous shopper's last line.
     *
     * @return true when a marker was placed, false when the ticket is empty
     */
    public boolean markSubtotal() {
        if (items.isEmpty()) {
            return false;
        }
        items.get(items.size() - 1).subtotalBoundary = true;
        if (parent != null) {
            parent.lastEnteredItemId = null;
        }
        recomputeTotal();
        onChange();
        return true;
    }

    /**
     * Whether a line already carries a reduction (BO-03-07-12): a manual
     * gesture, or an automatic promotion the engine valued it below its own
     * price.
     *
     * <p>Shares are reset before the allocation, so {@code getTotalPrice()} is
     * not used here: the comparison is the ENGINE's figure against the local
     * one, which is what an automatic promotion is.
     *
     * @param item the line
     * @return true when the line is already reduced
     */
    private boolean isReduced(TicketItem item) {
        if (item.modifierType != null) {
            return true;
        }
        if (item.valuedTotal == null || item.unitPrice == null || item.quantity == null) {
            return false;
        }
        return item.valuedTotal.compareTo(item.unitPrice.multiply(item.quantity)) < 0;
    }

    /**
     * Allocates the ticket-level discount onto the POSITIVE lines, prorata
     * of their DISCOUNTABLE (valued) totals with the cent residue on the largest share —
     * the reconciler's allocation doctrine reapplied: the fiscal stays 100%
     * carried by the lines, no floating ticket-level amount ever exists.
     * Runs at EVERY total recomputation, so the allocation always follows
     * the current valued totals (the discount applies on what the customer
     * actually pays, AFTER the engine's offers — the trade's director
     * gesture). A percent request is converted to an amount on the current
     * base; an amount request is clamped to the base.
     */
    private void allocateGlobalDiscount() {
        for (TicketItem item : items) {
            item.globalDiscountShare = null;
        }
        globalDiscountApplied = null;
        if (globalDiscountType == null || items.isEmpty()) {
            return;
        }
        BigDecimal base = BigDecimal.ZERO;
        for (TicketItem item : items) {
            // BO-02-03-09: a line whose article bans discounts is out of the
            // BASE as well as out of the allocation. Leaving it in the base
            // would shrink everyone else's share to pay for a discount this
            // line never receives — the ticket discount would silently cost
            // less than it says.
            if (item.moneyProduct || item.discountForbidden) continue;
            // BO-03-07-12: a line already reduced may be spared, and it then
            // leaves the BASE as well as the allocation — the same doctrine as
            // the article that bans discounts just above.
            if (globalDiscountSparesReducedLines && isReduced(item)) continue;
            BigDecimal lineTotal = item.getTotalPrice();
            if (lineTotal.signum() > 0) {
                base = base.add(lineTotal);
            }
        }
        if (base.signum() <= 0) {
            return;
        }
        BigDecimal amount = "PERCENT".equals(globalDiscountType)
                ? base.multiply(globalDiscountValue)
                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                : globalDiscountValue.min(base).setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            return;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        TicketItem largest = null;
        BigDecimal largestTotal = BigDecimal.ZERO;
        for (TicketItem item : items) {
            if (item.moneyProduct || item.discountForbidden) continue;
            if (globalDiscountSparesReducedLines && isReduced(item)) continue;
            // Shares were ALL reset to null at the top of this method, so
            // getTotalPrice() already IS the pre-share (valued) line total —
            // the allocation base per line. Re-adding the share here would be
            // a no-op (an earlier version did, and the branch could never
            // fire).
            BigDecimal lineTotal = item.getTotalPrice();
            if (lineTotal.signum() <= 0) continue;
            BigDecimal share = amount.multiply(lineTotal)
                    .divide(base, 2, RoundingMode.HALF_UP);
            item.globalDiscountShare = share;
            allocated = allocated.add(share);
            if (largest == null || lineTotal.compareTo(largestTotal) > 0) {
                largest = item;
                largestTotal = lineTotal;
            }
        }
        // The rounding residue lands on the largest line so the shares always
        // sum back to the applied amount. No null check on `largest`: a
        // positive base means at least one line was allocated, so the loop
        // above always designated one (the guarded version could never take
        // its false arm).
        BigDecimal residue = amount.subtract(allocated);
        if (residue.signum() != 0) {
            largest.globalDiscountShare = largest.globalDiscountShare.add(residue);
        }
        globalDiscountApplied = amount;
    }

    /**
     * Returns the total of the lines an article attribute declares eligible to
     * a meal voucher (BO-02-03-06), which caps a meal-ticket settlement when no
     * engine advantage states another base.
     *
     * @return the eligible total, zero when no line is eligible
     */
    public BigDecimal mealVoucherEligibleTotal() {
        BigDecimal total = BigDecimal.ZERO;
        for (TicketItem item : items) {
            if (!item.mealVoucherEligible) {
                continue;
            }
            BigDecimal lineTotal = item.getTotalPrice();
            if (lineTotal.signum() > 0) {
                total = total.add(lineTotal);
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Takes the announced container tare and forgets it.
     *
     * <p>Read ONCE: a tare belongs to the container that was just put on the
     * scale, and leaving it armed would quietly lighten the following line too
     * (BO-03-06-60).
     *
     * @return the announced tare in kilograms, or null when none was announced
     */
    public BigDecimal consumePendingTare() {
        BigDecimal tare = pendingTareKg;
        pendingTareKg = null;
        return tare;
    }

    /**
     * Clears the whole ticket state (lines, total, weights, error).
     */
    public void clear() {
        items.clear();
        cancelledItems.clear();
        scannedStickerCodes.clear();
        scannedPromotions.clear();
        pendingActivationUid = null;
        pendingTareKg = null;
        totalAmount = BigDecimal.ZERO;
        currentWeight = 0.0;
        lastRecordedWeight = Double.NaN;
        ageVerifiedThreshold = 0;
        globalDiscountType = null;
        globalDiscountValue = null;
        globalDiscountApplied = null;
        globalDiscountSparesReducedLines = false;
        transientError = null;
        transientOk = false;
        transientWarn = false;
        onChange();
    }

    /**
     * Stores the activation code of the line the register is waiting on
     * (BO-03-06-44) and stops waiting.
     *
     * <p>The wait is cleared WHATEVER the outcome: a code the register cannot
     * attach to a line (the line was cancelled, the uid no longer exists) must
     * not leave the next scan hostage to a line nobody can see.
     *
     * @param code the scanned activation code, or null
     * @return the line the code was stored on, or null when there was nothing
     *         to wait for, nothing to store, or no line carrying the uid
     */
    public TicketItem storePendingActivation(String code) {
        String uid = pendingActivationUid;
        pendingActivationUid = null;
        if (uid == null || code == null || code.isBlank()) {
            return null;
        }
        for (TicketItem item : items) {
            if (item != null && uid.equals(item.uid)) {
                item.activationCode = code.trim();
                onChange();
                return item;
            }
        }
        return null;
    }

    /**
     * Arms a named promotion on this ticket (BO-03-06-43).
     *
     * <p>Idempotent on purpose: scanning the same promotion barcode twice is a
     * gesture a customer makes, not a second right. A blank name is refused
     * rather than armed — the engine would have nothing to resolve.
     *
     * @param promotionCode the administered promotion name, or null
     * @return true when the name was armed by this call, false when it was
     *         blank or already armed
     */
    public boolean armPromotion(String promotionCode) {
        if (promotionCode == null || promotionCode.isBlank()) {
            return false;
        }
        String name = promotionCode.trim();
        if (scannedPromotions.contains(name)) {
            return false;
        }
        scannedPromotions.add(name);
        onChange();
        return true;
    }

    /**
     * Sets a transient error message shown on the ticket area.
     *
     * @param err the error message
     */
    public void setError(String err) {
        this.transientError = err;
        this.transientOk = false;
        this.transientWarn = false;
        onChange();
    }

    /**
     * Sets a transient CONFIRMATION shown on the ticket area — the same zone as
     * {@link #setError(String)}, drawn as an acknowledgement instead of a refusal.
     *
     * @param message the confirmation message
     */
    public void setNotice(String message) {
        this.transientError = message;
        this.transientOk = true;
        this.transientWarn = false;
        onChange();
    }

    /**
     * Sets a transient WARNING shown on the ticket area — the same zone as
     * {@link #setError(String)}, drawn as a caution: the gesture went
     * through, the operator is asked to look at it.
     *
     * @param message the warning message
     */
    public void setWarning(String message) {
        this.transientError = message;
        this.transientOk = false;
        this.transientWarn = true;
        onChange();
    }

    /**
     * Records the current weight reported by the scale.
     *
     * @param w the weight in kg
     */
    public void setWeight(double w) {
        this.currentWeight = w;
        onChange();
    }

    /**
     * Returns the ticket total formatted for display (2 decimals, French comma).
     *
     * @return the formatted total
     */
    public String getTotalFormatted() { return formatPrice(totalAmount); }

    /**
     * Returns the running total of the ticket (tax included).
     *
     * @return the total amount
     */
    public BigDecimal getTotalAmount() { return totalAmount; }

    /**
     * Returns the ticket lines.
     *
     * @return the list of lines
     */
    public List<TicketItem> getItems() { return items; }

    /**
     * Returns the left-hand label of the on-screen global-discount row:
     * "REMISE TICKET", suffixed with the requested rate for a PERCENT
     * request (e.g. "REMISE TICKET (10 %)").
     *
     * @return the display label, or null when no discount is applied
     */
    public String getGlobalDiscountLabel() {
        if (globalDiscountApplied == null) return null;
        if ("PERCENT".equals(globalDiscountType)) {
            return parent.messages().format("paper.remiseTicketTaux",
                    globalDiscountValue.stripTrailingZeros().toPlainString());
        }
        return parent.messages().get("paper.remiseTicket");
    }

    /**
     * Returns the applied global-discount amount formatted for display,
     * signed (e.g. "-1,81").
     *
     * @return the formatted amount, or null when no discount is applied
     */
    public String getGlobalDiscountAppliedFormatted() {
        if (globalDiscountApplied == null) return null;
        return "-" + formatPrice(globalDiscountApplied);
    }

    /**
     * Formats a monetary value with 2 decimals and a French comma separator.
     *
     * @param value the value to format
     * @return the formatted string
     */
    private String formatPrice(BigDecimal value) {
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        return String.format("%.2f", rounded).replace(".", ",");
    }

    /**
     * The ONE decomposition line "quantité × prix unitaire" shared by the three
     * surfaces (P3-5, LC-02-05-04) — the in-memory line, the customer display and
     * the printed receipt build it here so they can never disagree. Four cases,
     * one form: a price-embedded line (weight deduced), a weighed line (quantity
     * is the weight in kg), a measure-unit line (the referential unit, never
     * hardcoded), and a multi-unit piece; empty for a single unit sold by the
     * piece, which teaches nothing. The euro glyph is a parameter because the
     * screens use "€" and the ESC/POS receipt uses "E".
     *
     * @param quantity the line quantity (a weight in kg for a weighed line)
     * @param unitPrice the unit price (per kg for a weighed line)
     * @param unitName the referential unit of measure, or null/blank when none
     * @param weighed true when the line is a weighed (PLU) sale
     * @param embeddedWeight the deduced weight of a price-embedded line, or null
     * @param embeddedUnitPrice the referential price per kilo, or null
     * @param euro the euro glyph to print ("€" on screen, "E" on paper)
     * @return the decomposition line, or an empty string when none applies
     */
    public static String detailLineOf(BigDecimal quantity, BigDecimal unitPrice, String unitName,
            boolean weighed, BigDecimal embeddedWeight, BigDecimal embeddedUnitPrice, String euro) {
        if (embeddedWeight != null && embeddedUnitPrice != null) {
            return String.format("%.3f kg x %.2f " + euro + "/kg", embeddedWeight, embeddedUnitPrice)
                    .replace('.', ',');
        }
        if (weighed) {
            return String.format("%.3f kg x %.2f " + euro + "/kg", quantity, unitPrice).replace('.', ',');
        }
        if (unitName != null && !unitName.isBlank()) {
            return String.format("%s %s x %.2f " + euro + "/%s",
                    quantity.stripTrailingZeros().toPlainString(), unitName, unitPrice, unitName)
                    .replace('.', ',');
        }
        if (quantity.compareTo(BigDecimal.ONE) > 0) {
            return String.format("%s x %.2f " + euro,
                    quantity.stripTrailingZeros().toPlainString(), unitPrice).replace('.', ',');
        }
        return "";
    }

    // --- INNER CLASS: TICKET ITEM ---

    /**
     * A single line of the in-memory ticket.
     */
    public static class TicketItem implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Unique, stable identifier of the line — also the contractual lineId toward external services. */
        public String uid;

        /** The EAN code, or null (direct entry, deposit return). */
        public String ean;

        /** The PLU code, or null. A non-empty PLU denotes a weighed line. */
        public String plu;

        /** The display label. */
        public String label;

        /** The unit price including tax. */
        public BigDecimal unitPrice;

        /** The quantity (units, or kg for weighed lines). */
        public BigDecimal quantity;

        /** The unit price before any price modification, for display and audit. */
        public BigDecimal originalUnitPrice = BigDecimal.ZERO;

        /** The VAT rate captured at sale time (e.g. 0.2000 for 20%). */
        public BigDecimal vatRate = BigDecimal.ZERO;

        /** The label of the applied price modification, or null. */
        public String modifierLabel = null;

        /**
         * Structured type of the applied manual gesture (REMISE, DISCOUNT,
         * FORCE_PRICE), or null. Captured at application time so the
         * valuation request can carry the gesture as data
         * (manualDiscountAmount / manualDiscountPercent / manualForcedPrice)
         * instead of a pre-baked price — phase 7.
         */
        public PriceModType modifierType = null;

        /**
         * Structured value of the applied manual gesture, as typed by the
         * cashier: euros off the LINE total for REMISE, percentage for
         * DISCOUNT, new LINE total for FORCE_PRICE. Null when no gesture.
         */
        public BigDecimal modifierValue = null;

        /**
         * Creates a ticket line.
         *
         * @param ean the EAN code, or null
         * @param plu the PLU code, or null
         * @param label the display label
         * @param unitPrice the unit price including tax
         * @param quantity the quantity
         * @param vatRate the VAT rate captured at sale time, or null for 0%
         */
        public TicketItem(String ean, String plu, String label, BigDecimal unitPrice, BigDecimal quantity, BigDecimal vatRate) {
            this.uid = UUID.randomUUID().toString();
            this.ean = ean;
            this.plu = plu;
            this.label = label;
            this.unitPrice = unitPrice;
            this.originalUnitPrice = unitPrice;
            this.quantity = quantity;
            this.vatRate = vatRate != null ? vatRate : BigDecimal.ZERO;
        }

        /**
         * Default constructor for serialization frameworks.
         */
        public TicketItem() {}

        /**
         * Returns the line total (unit price times quantity).
         *
         * @return the line total including tax
         */
        /**
         * Engine-valued line total (tax included), or null when the line is
         * locally priced. THE single override point of phase 7: when set,
         * every consumer of {@link #getTotalPrice()} — screen totals, draft
         * lines, VAT ventilation, printing — follows the engine's figure
         * with no further wiring. Cleared when the payments are cancelled.
         */
        public BigDecimal valuedTotal = null;

        /**
         * Returns the line total: the engine-valued amount when present,
         * the local unit-price math otherwise.
         *
         * @return the effective line total, tax included
         */
        /**
         * This line's allocated share of the ticket-level discount, or null
         * (phase: global ticket discount). Recomputed at every total
         * recomputation, never persisted per line — the synced line totals
         * already include it.
         */
        public BigDecimal globalDiscountShare = null;

        /**
         * True when this line sells a MONEY PRODUCT (a gift card): excluded
         * from the global-discount allocation, from the price gestures, from
         * the engine valuation and from returns — one does not discount,
         * promote or refund money (phase: credit notes & gift cards).
         * Re-derived from the product at draft recovery.
         */
        public boolean moneyProduct = false;

        /**
         * True when this line was rung from a PRICE-EMBEDDED balance sticker
         * (EAN 2x, prefixes 21-22): the sticker total IS the line's price and
         * the catalog knows nothing about it. The valuation engine must
         * receive it through the surcharge trio (pricePerUnitExclTax /
         * InclTax / vatRate, all-or-nothing) instead of re-pricing the EAN
         * from its own catalog. Persisted on the draft and read back at
         * recovery — the draft is the durable truth of what was rung up.
         */
        public boolean priceEmbedded = false;

        /**
         * On a price-embedded weighed line (EXP-26, LC-02-05-01), the WEIGHT
         * deduced from the read total and the referential price per kilo — the
         * inverse of the usual direction. Null on every other line. Shown with
         * {@link #embeddedUnitPrice} on both visus and the ticket (LC-02-05-04).
         */
        public BigDecimal embeddedWeight = null;

        /**
         * On a price-embedded weighed line (EXP-26), the referential price per
         * kilo used to deduce {@link #embeddedWeight}. Null on every other line.
         */
        public BigDecimal embeddedUnitPrice = null;

        /**
         * Whether this line carries a deduced weight to display (EXP-26): both the
         * weight and the price per kilo were resolved.
         *
         * @return true when the deduced-weight detail is available
         */
        public boolean hasEmbeddedWeight() {
            return embeddedWeight != null && embeddedUnitPrice != null;
        }

        /**
         * The deduced-weight detail for a price-embedded line, formatted for the
         * screens and the ticket (LC-02-05-04): "0,750 kg à 4,00 €/kg".
         *
         * @return the formatted detail, or an empty string when none applies
         */
        public String getEmbeddedWeightLabel() {
            if (!hasEmbeddedWeight()) {
                return "";
            }
            return String.format("%.3f kg à %.2f €/kg", embeddedWeight, embeddedUnitPrice)
                    .replace('.', ',');
        }

        /**
         * The ONE decomposition line shown under the label — quantity and unit
         * price — on the three surfaces (P3-5, LC-02-05-04): "5 x 2,50 €" for a
         * multi-unit piece, "1,25 m x 12,00 €/m" for a measure unit (the unit is
         * the referential one, never hardcoded), "0,188 kg x 17,99 €/kg" for a
         * weighed line, and the same form for a price-embedded line where the
         * WEIGHT is DEDUCED from the read price and the referential price per kilo
         * (LC-02-05-01). Empty when it would teach nothing — a single unit sold by
         * the piece.
         *
         * @return the decomposition line, or an empty string when none applies
         */
        public String getDetailLine() {
            return detailLineOf(quantity, unitPrice, unitName,
                    plu != null && !plu.isEmpty(), embeddedWeight, embeddedUnitPrice, "€");
        }

        /**
         * True when the article sold on this line forbids discounts and rebates
         * (BO-02-03-09): the line price gestures (remise, discount) refuse to
         * touch it, exactly as they refuse a {@link #moneyProduct}. Snapshotted
         * from the product at add time. In-memory guard only — like the
         * scanned-sticker set it is not restored by a draft recovery: the ban is
         * a UI safeguard, not a fiscal fact, and no discount can have been
         * applied to a banned line for the recovery to have to preserve.
         */
        public boolean discountForbidden = false;

        /**
         * The article software tare in kilograms snapshotted from the product
         * at add time (LC-14-01-07), or null when the article declares none. It
         * lets a MANUAL weight re-entry (LC-14-01-08) deduct the article tare
         * without a fresh catalog lookup, exactly as {@link #mealVoucherEligible}
         * snapshots the eligible base.
         */
        public BigDecimal tareKg = null;

        /**
         * True when the article sold on this line may be settled with a meal
         * voucher (BO-02-03-06). Snapshotted from the product at add time, like
         * {@link #discountForbidden}, so the eligible base is known even when
         * the valuation engine has not answered — a register in local mode must
         * not accept meal tickets on a bottle of wine.
         */
        public boolean mealVoucherEligible = false;

        /**
         * True when the article on this line is NOT carried out of the shop by the
         * customer but collected afterwards at the goods desk
         * ({@code LC-02-08}).
         * <p>
         * It changes nothing about the sale — the article is rung, paid and printed
         * exactly like any other — and everything about what happens next: the line
         * is marked on screen for the cashier, and it is the collection voucher
         * printed after the receipt that lets the desk hand the goods over.
         */
        public boolean toCollect = false;

        /**
         * True when a SOUS-TOTAL is drawn AFTER this line (EXP-43): the marker the
         * SOUS-TOTAL key places to separate one shopper's items from the next in a
         * shared basket. It is a positional marker, NOT a basket split — the ticket
         * total and the threshold offers still see the whole ticket, and the
         * valuation payload is unchanged. Persisted on the draft line so the printed
         * ticket, its duplicata and the dematerialized ticket carry it too.
         */
        public boolean subtotalBoundary = false;

        /**
         * True when this line is a RETURN — an article the customer brings back,
         * carried as a negative contribution inside a mixed ticket (EXP-38/39/40).
         * <p>
         * It is excluded from the valuation as a promo-eligible SALE line — one does
         * not earn an offer on a returned article — and never merges into a positive
         * sale line. Persisted on the draft so a mixed ticket survives a recovery.
         */
        public boolean returnLine = false;

        /**
         * True when the line was CANCELLED during the sale and is kept on the
         * screen as a witness, struck through.
         *
         * <p>Only a line parked in {@link TicketState#cancelledItems} carries it:
         * a line in the cart is never cancelled, which is what lets every other
         * behaviour ignore the state entirely.
         */
        public boolean cancelled = false;

        /**
         * The rank this line held in the cart when it was cancelled, so the
         * screen can put it back where the operator saw it. Meaningless on a live
         * line.
         */
        public int displayIndex = -1;

        /**
         * The running sum of the line totals since the previous subtotal marker,
         * set on a boundary line at each {@link #recomputeTotal()} (EXP-43). Transient
         * display value: it is recomputed from the lines, never persisted.
         */
        public transient BigDecimal subtotalAmount = null;

        /**
         * Returns the subtotal amount carried by this boundary line, formatted for
         * display (EXP-43), or null when this line is not a subtotal boundary.
         *
         * @return the formatted subtotal, or null
         */
        public String getSubtotalFormatted() {
            if (!subtotalBoundary || subtotalAmount == null) {
                return null;
            }
            return String.format("%.2f", subtotalAmount.setScale(2, RoundingMode.HALF_UP)).replace(".", ",");
        }

        /**
         * The restricted-tender eligibilities the article carried when it was rung
         * ({@code LC-09-01-11} to {@code -18}), comma-joined, or null when it carried
         * none.
         * <p>
         * Snapshotted like the price and the VAT rate, and read back at recovery for
         * the same reason: what an article was payable with when it was sold is a fact
         * of the sale, and neither a referential edited afterwards nor a restart may
         * move it.
         */
        public String restrictedTenders = null;

        /**
         * The article's unit of measure ({@code LC-02-03-02}), or null for an article
         * sold by the piece.
         * <p>
         * Carried by the line so the three surfaces that state the sale — the register
         * display, the customer display and the receipt — say "2,36 x 4,99 €/m"
         * without any of them reading the referential back.
         */
        public String unitName = null;

        /**
         * Whether the article is of the DECIMAL-QUANTITY type ({@code LC-02-13-02},
         * referential attribute {@code BO-02-03-23}) — sold by the metre, the square
         * metre or the litre, so its quantity legitimately carries decimals.
         * <p>
         * Carried by the line for the same reason as {@link #unitName}: the quantity
         * key must know whether the decimals it is handed are legitimate, and asking
         * the referential again at that moment would make the answer depend on a
         * catalog that may have been purged since the article was rung.
         */
        public boolean decimalQuantity = false;

        /**
         * Every identifier the GS1 code that rang this line carried, as the
         * technical journal writes them, or null on a line rung any other way
         * ({@code LC-11-03-02}).
         * <p>
         * IT IS KEPT WHOLE AND UNINTERPRETED, including the identifiers this
         * version has no rule for: the requirement is that a decoded identifier
         * be recorded "même s'il n'y a pas d'usage ou règle de gestion
         * défini(e)", and the batch number, the serial number or the country of
         * origin of a line become answerable questions only if the line kept
         * them. It carries no rule of its own and nothing computes from it.
         */
        public String gs1Data = null;

        /**
         * The expiry date the GS1 code carried ({@code LC-11-03-12}), or null.
         * <p>
         * Held as a field of its own beside {@link #gs1Data}, because it is the
         * one identifier with a rule: the shop's alert level reads it, and the
         * valuation engine receives it so a promotion can be triggered on a
         * short-dated article ({@code LC-11-03-13}).
         */
        public java.time.LocalDate gs1ExpiryDate = null;

        /**
         * The activation code of a dematerialised article, scanned right after
         * its EAN (BO-03-06-44), or null on every ordinary line.
         *
         * <p>Held on the LINE and not on the register, because it belongs to
         * one item: a gift card and the phone top-up beside it each carry their
         * own code, and a reclamation three weeks later is answered by the line
         * that kept it. Carried into the persisted ticket line beside
         * {@link #gs1ExpiryDate}, for the same reason.
         */
        public String activationCode = null;

        /**
         * The provider that owes the activation of this line (BO-03-06-44), as
         * the referential named it, or null.
         *
         * <p>Snapshotted on the line rather than read back from the catalog: a
         * provider the back office changes next month must not rewrite what a
         * sale already settled.
         */
        public String activationProvider = null;

        public BigDecimal getTotalPrice() {
            BigDecimal __t = valuedTotal != null ? valuedTotal : unitPrice.multiply(quantity);
            if (globalDiscountShare != null) {
                __t = __t.subtract(globalDiscountShare);
            }
            return __t;
        }


        /**
         * Returns the HTML fragment displaying the quantity and label of the line.
         * <p>
         * A line carrying a PLU is displayed as a weighed line (kg, 3 decimals);
         * otherwise as a unit line (integer quantity when whole).
         *
         * @return the HTML fragment for the ticket display
         */
        public String getHtml() {
            if (ean == null && label.contains("<span")) return label;

            // A filled PLU denotes a weighed sale
            if (plu != null && !plu.isEmpty()) {
                // A price-embedded sticker fixed the PRICE, not the weight:
                // the weight is unknown at the register, so the line shows no
                // quantity at all — never a phantom "1,000 kg". The internal
                // quantity stays 1 as the neutral multiplier so the line total
                // remains exactly the sticker price.
                if (priceEmbedded) {
                    return label;
                }
                String weightFormatted = String.format("%.3f", quantity).replace(".", ",");
                return String.format("<span class='qty'>%s kg</span> %s", weightFormatted, label);
            }
            // Unit sale
            else {
                boolean isOne = quantity.compareTo(BigDecimal.ONE) == 0;
                String qtyClass = isOne ? "qty unit-qty" : "qty";
                boolean isWhole = quantity.stripTrailingZeros().scale() <= 0;
                String qtyDisplay = isWhole
                        ? quantity.stripTrailingZeros().toPlainString()
                        : String.format("%.2f", quantity).replace(".", ",");
                // LC-02-03-02: a measure line carries its quantity and unit as the
                // badge ("2,36 m"); the price-per-unit decomposition lives once in
                // getDetailLine, shown as the sub-line under the label (P4).
                if (unitName != null && !unitName.isBlank()) {
                    return String.format("<span class='%s'>%s %s</span> %s",
                            qtyClass, qtyDisplay, unitName, label);
                }
                return String.format("<span class='%s'>x%s</span> %s", qtyClass, qtyDisplay, label);
            }
        }

        /**
         * Returns the line total formatted for display (2 decimals, French comma).
         *
         * @return the formatted line total
         */
        public String getPriceFormatted() {
            BigDecimal rounded = getTotalPrice().setScale(2, RoundingMode.HALF_UP);
            return String.format("%.2f", rounded).replace(".", ",");
        }

        /**
         * Returns the label of the applied price modification, or null.
         *
         * @return the modifier label
         */
        public String getModifierLabel() {
            return modifierLabel;
        }

        /**
         * Indicates whether the line total is negative (deposit return, etc.).
         *
         * @return true if the line total is strictly negative
         */
        public boolean isNegative() {
            return getTotalPrice().signum() < 0;
        }

        /**
         * Qute-friendly alias of {@link #isNegative()}.
         *
         * @return true if the line total is strictly negative
         */
        public boolean getNegative() {
            return isNegative();
        }
    }
}
