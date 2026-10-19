package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.ui.PriceModType;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * State of a pending manager-endorsement request (ticket/line cancellation,
 * price modification).
 * <p>
 * Phase 0: the pending price-modification value is a {@link BigDecimal}.
 * <p>
 * This is the PARKED-GESTURE half of the endorsement pattern: the guarded
 * action is encoded as a string ({@code requestedAction}) or as the
 * price-mod triple, the modal opens, and NOTHING has happened yet — the
 * gesture only executes when the dispatch validates a manager PIN. One
 * pending request at a time by construction (a new request overwrites the
 * previous one); {@code scannedBadge} mirrors the lock-page mailbox so a
 * manager can badge instead of typing their login on the modal.
 */
public class EndorsementState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** True while an endorsement request is active. */
    public boolean active = false;

    /** The requested action code, or null. */
    public String requestedAction = null;

    /** The manager badge scanned during the endorsement, or null. */
    public String scannedBadge = null;

    /**
     * Who, at the back office, authorised the gesture this lane is holding
     * ({@code BO-04-02-02}), or null when nobody did.
     *
     * <p>An authorisation and not a performance: it lets the pending gesture
     * through without a code being typed at the till, and the cashier still
     * presses the key they were pressing. Cleared with the request, like
     * everything else here, so one authorisation can never serve a second
     * gesture.
     */
    public String remoteGrant = null;

    /** The current endorsement error, or null. */
    public String error = null;

    /**
     * The parked form of a guarded gesture that carries more than an action
     * code — a cash movement, a withdrawal, a transfer.
     *
     * <p>The price modification parks its three values in dedicated fields
     * because it predates this one; a drawer operation carries an arbitrary
     * handful of strings, and copying the whole form is what lets ANY screen
     * borrow the modal instead of growing its own badge and PIN boxes.
     * Empty when the pending gesture needs nothing but its code.
     */
    public java.util.Map<String, String> pendingForm = new java.util.LinkedHashMap<>();

    /**
     * Where to send the operator once the gesture has run, or null for the
     * sale screen.
     *
     * <p>A ticket gesture ends on the sale screen, which is where it was made.
     * A drawer operation was made on its own screen and the operator usually
     * chains another one, so it goes back there with its outcome.
     */
    public String returnPath = null;

    /** The pending price-modification type (REMISE, DISCOUNT, FORCE_PRICE), or null. */
    public PriceModType pendingPriceType = null;

    /** The uid of the targeted ticket line, or null. */
    public String pendingTargetUid = null;

    /** The pending price-modification value (euros or percent depending on the type). */
    public BigDecimal pendingValue = BigDecimal.ZERO;

    /**
     * Opens an endorsement request for the given action.
     *
     * @param action the action code requiring authorization
     */
    public void request(String action) {
        request(action, java.util.Map.of(), null);
    }

    /**
     * Opens an endorsement request for a gesture that carries a form.
     *
     * @param action the action code requiring authorization
     * @param form the gesture's parked fields, copied; empty when it has none
     * @param returnPath where to land once the gesture ran, or null for the
     *        sale screen
     */
    public void request(String action, java.util.Map<String, String> form, String returnPath) {
        this.active = true;
        this.requestedAction = action;
        this.error = null;
        this.scannedBadge = null;
        this.pendingForm = new java.util.LinkedHashMap<>(form != null ? form : java.util.Map.of());
        this.returnPath = returnPath;
        clearPendingPrice();
    }

    /**
     * Opens an endorsement request for a price modification.
     *
     * @param type the modification type (REMISE, DISCOUNT, FORCE_PRICE)
     * @param uid the uid of the targeted ticket line
     * @param value the modification value (euros or percent depending on the type)
     */
    public void requestPriceModification(PriceModType type, String uid, BigDecimal value) {
        this.pendingForm = new java.util.LinkedHashMap<>();
        this.returnPath = null;
        this.active = true;
        this.requestedAction = "PRICE_MODIFICATION";
        this.pendingPriceType = type;
        this.pendingTargetUid = uid;
        this.pendingValue = value != null ? value : BigDecimal.ZERO;
        this.error = null;
        this.scannedBadge = null;
    }

    /**
     * Clears the endorsement request and any pending price modification.
     */
    public void clear() {
        this.active = false;
        this.requestedAction = null;
        this.error = null;
        this.scannedBadge = null;
        this.remoteGrant = null;
        this.pendingForm = new java.util.LinkedHashMap<>();
        this.returnPath = null;
        clearPendingPrice();
    }

    /**
     * Clears the pending price-modification fields.
     */
    private void clearPendingPrice() {
        this.pendingPriceType = null;
        this.pendingTargetUid = null;
        this.pendingValue = BigDecimal.ZERO;
    }

    /**
     * Records the manager badge scanned during the endorsement.
     *
     * @param badge the scanned badge id
     */
    public void setScannedBadge(String badge) { this.scannedBadge = badge; }

    /**
     * Clears the scanned manager badge.
     */
    public void clearScannedBadge() { this.scannedBadge = null; }
}
