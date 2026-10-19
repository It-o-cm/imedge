package com.intermarche.pos.ui.fidelity;

import com.intermarche.pos.ui.PosState;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Attaches a fidelity card to the current ticket.
 * <p>
 * Two callers share this single entry point: the fidelity SCAN handler
 * (primary path — card recognized by the {@code scan.pattern.fidelity}
 * regex anywhere during the sale) and the manual-entry page (fallback for
 * an unreadable card). The next draft sync persists the card.
 */
@ApplicationScoped
public class FidelityService {

    private static final Logger LOGGER = Logger.getLogger(FidelityService.class);

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** The loyalty-service client (phase: imfid integration, lot 1). */
    @Inject
    ImfidClient imfidClient;

    /** Administered settings — carries the holder-name display flag. */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * Circuit breaker on imfid, mirroring the valuation engine's: after a
     * failure, no further call for {@code retrySeconds} — a dead loyalty
     * service never taxes every scan with its timeout (spec §2.2: the sale
     * is never blocked).
     */
    private volatile long fidSkipUntil = 0L;

    /** Breaker window, seconds. */
    private static final int RETRY_SECONDS = 10;

    /**
     * Whether the loyalty service is CURRENTLY unreachable, as this register
     * knows it (BO-03-03-29, BO-03-03-30).
     *
     * <p>The answer is the breaker, not a fresh call: asking imfid whether imfid
     * answers would tax the closing with the very timeout the breaker exists to
     * avoid. A register therefore learns the service is down from the last call
     * that failed, and says so on the tickets that follow within the breaker
     * window — including the ones made without a card, which is what
     * BO-03-03-30 needs.
     *
     * <p>A shop that never configured the loyalty service is NOT unavailable:
     * there is nothing to reach, and a receipt apologizing for a programme the
     * shop does not run would be a defect.
     *
     * @return true when the service is configured and the breaker is open
     */
    public boolean isUnavailable() {
        LOGGER.info("Entering method isUnavailable");
        if (!imfidClient.isConfigured()) {
            LOGGER.info("Exiting method isUnavailable");
            return false;
        }
        LOGGER.info("Exiting method isUnavailable");
        return System.currentTimeMillis() < fidSkipUntil;
    }

    /**
     * Announces the loyalty wiring once at boot, like the valuation engine.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (imfidClient.isConfigured()) {
            LOGGER.infof("Service fidélité imfid ACTIF: %s", imfidClient.targetUrl());
        } else {
            LOGGER.info("Service fidélité imfid NON CONFIGURÉ: earn et paiement fidélité désactivés");
        }
    }

    /** Revaluation trigger on card attachment (fresh couple with the card). */
    @Inject
    com.intermarche.pos.ui.ticket.TicketService ticketService;

    /**
     * Attaches the card to the in-memory state and wakes the polling — then
     * REVALUES the cart: the engine request must carry the customerCode, and
     * the earn projection must be fed a couple THAT SEES THE CARD (a couple
     * serialized before the attachment projects a spec-correct but useless
     * "no card = zero"). The revaluation path itself refreshes the earn
     * (onValuation). Found by the demo pre-flight (fid01).
     *
     * @param state the current POS state
     * @param card the card number, scanned or typed
     */
    public void validateCard(PosState state, String card) {
        LOGGER.info("Entering method validateCard with state: " + state + ", card: " + card);
        state.fidelity.assignCard(card);
        refreshAccountDisplay(state);
        // BEFORE the revaluation, and that order carries: the basket about to
        // leave must already hold the promotions, or the engine prices a sale
        // the holder is entitled to a discount on without seeing it.
        refreshCardPromotions(state);
        // Promotions addendum §2/§3.1: the register applies the IMMEDIATE_DISCOUNT
        // promotions itself (guarded so it never doubles with the engine — see
        // applyCardPromotions). Done before the revaluation so the discounted
        // lines are the ones synced and priced.
        applyCardPromotions(state);
        if (!state.ticket.items.isEmpty()) {
            ticketService.recalculateTotal(state);
        }
        state.touch(); // Indispensable pour le polling
        LOGGER.info("Exiting method validateCard");
    }

    /**
     * Confirms the replacement of the attached card by the one whose scan was
     * parked (LC-03-04-08): the new card goes through the ONE attachment entry
     * point, exactly as if it had been scanned on an empty ticket. A proposal
     * already consumed — or parked for a card since detached — is a no-op.
     *
     * @param state the current POS state
     */
    public void confirmReplacement(PosState state) {
        LOGGER.info("Entering method confirmReplacement with state: " + state);
        String parked = state.fidelity.pendingReplacementCard;
        if (parked != null && !parked.isBlank()) {
            validateCard(state, parked);
        }
        state.fidelity.pendingReplacementCard = null;
        LOGGER.info("Exiting method confirmReplacement");
    }

    /**
     * Keeps the attached card and drops the parked replacement (LC-03-04-08):
     * the second scan is the customer's or the operator's mistake, and the
     * first holder keeps their advantages. Idempotent by construction.
     *
     * @param state the current POS state
     */
    public void keepReplacement(PosState state) {
        LOGGER.info("Entering method keepReplacement with state: " + state);
        state.fidelity.pendingReplacementCard = null;
        state.touch();
        LOGGER.info("Exiting method keepReplacement");
    }

    /**
     * Reads the attached card's account once to feed the main-screen summary
     * (status + available balance). Best effort under the breaker: a silent
     * degraded leaves the fields null and the screen simply shows the bare
     * card number — the sale is never blocked (spec §2.2).
     *
     * @param state the current POS state
     */
    private void refreshAccountDisplay(PosState state) {
        if (!state.fidelity.active || !imfidClient.isConfigured()
                || System.currentTimeMillis() < fidSkipUntil) {
            return;
        }
        try {
            ImfidClient.AccountInfo account = imfidClient.account(state.fidelity.label);
            if (account != null) {
                state.fidelity.accountStatus = account.status;
                state.fidelity.availableBalance = account.availableBalance;
            }
        } catch (Exception e) {
            fidSkipUntil = System.currentTimeMillis() + RETRY_SECONDS * 1000L;
            LOGGER.warnf("Lecture compte fidélité indisponible (%s): affichage sans solde", e.getMessage());
        }
    }

    /**
     * Reads the attached card's immediate-discount promotions once, so the
     * valuation requests of this sale can carry them.
     * <p>
     * Best effort under the same breaker as the account display: an imfid
     * that does not answer leaves the list EMPTY and the sale goes on priced
     * without the personal discounts — never blocked, never half-applied.
     * The failure is logged because a holder silently losing an advantage is
     * exactly what an operator cannot see.
     *
     * @param state the current POS state
     */
    private void refreshCardPromotions(PosState state) {
        if (!state.fidelity.active || !imfidClient.isConfigured()
                || System.currentTimeMillis() < fidSkipUntil) {
            return;
        }
        try {
            state.fidelity.cardPromotions = imfidClient.promotions(state.fidelity.label);
        } catch (Exception e) {
            fidSkipUntil = System.currentTimeMillis() + RETRY_SECONDS * 1000L;
            LOGGER.warnf("Promotions carte indisponibles (%s): vente valorisée sans elles",
                    e.getMessage());
        }
    }

    /**
     * Searches cards by holder identity (addendum §3) and returns a
     * ready-to-render outcome. ONE criterion per call, by the addendum's
     * priority: phone, else e-mail, else name (+ optional first name) —
     * phone and e-mail match EXACTLY after imfid's normalization, name and
     * first name by PREFIX (addendum v1.1); "contient" never exists.
     *
     * @param phone the typed phone, or null
     * @param email the typed e-mail, or null
     * @param name the typed last name, or null
     * @param firstName the typed first name, or null
     * @return the lookup view (matches, or a display message)
     */
    public LookupView lookupCards(String phone, String email, String name, String firstName) {
        return lookupCards(phone, email, name, firstName, null);
    }

    /**
     * Looks up cards by identity with the city criterion (EXP-74).
     *
     * @param phone the typed phone, or null
     * @param email the typed e-mail, or null
     * @param name the typed last name, or null
     * @param firstName the typed first name, or null
     * @param city the typed city, or null
     * @return the lookup view (matches, or a display message)
     */
    public LookupView lookupCards(String phone, String email, String name, String firstName,
            String city) {
        LOGGER.info("Entering method lookupCards with phone: " + phone + ", email: " + email + ", name: " + name + ", firstName: " + firstName + ", city: " + city);
        LookupView view = new LookupView();
        if (!imfidClient.isConfigured() || System.currentTimeMillis() < fidSkipUntil) {
            view.message = i18n.get("op.serviceFideliteIndisponible");
            LOGGER.info("Exiting method lookupCards");
            return view;
        }
        boolean hasCriterion = notBlank(phone) || notBlank(email) || notBlank(name) || notBlank(city);
        if (!hasCriterion) {
            view.message = i18n.get("op.saisissezTelephoneEMail");
            LOGGER.info("Exiting method lookupCards");
            return view;
        }
        try {
            ImfidClient.LookupResult result = imfidClient.lookup(phone, email, name, firstName, city);
            if (result.crmManaged) {
                // Addendum §2: identity lives in the CRM — out of imfid's
                // contract, and no CRM connector exists on this register.
                view.message = i18n.get("op.identitesGereesCrmRecherche");
                LOGGER.info("Exiting method lookupCards");
                return view;
            }
            if (result.matches == null) {
                LOGGER.warnf("Lookup fidélité refusé: %s", result.refusalReason);
                view.message = i18n.get("op.rechercheRefuseeServiceFidelite");
                LOGGER.info("Exiting method lookupCards");
                return view;
            }
            view.matches = result.matches;
            if (result.matches.isEmpty()) {
                view.message = i18n.get("op.aucuneCarteTrouveeEssayez");
            } else if (result.matches.size() >= 20) {
                // Addendum §3.3: the list is CAPPED at 20 — a full page means
                // truncation, ask for a more discriminating criterion.
                view.message = i18n.get("op.tropCorrespondancesPrecisezCritere");
            }
            LOGGER.info("Exiting method lookupCards");
            return view;
        } catch (Exception e) {
            fidSkipUntil = System.currentTimeMillis() + RETRY_SECONDS * 1000L;
            LOGGER.warnf("Lookup fidélité indisponible (%s)", e.getMessage());
            view.message = i18n.get("op.serviceFideliteIndisponible");
            LOGGER.info("Exiting method lookupCards");
            return view;
        }
    }

    /**
     * Attaches a card picked in the lookup list (addendum §4): RESILIATED is
     * refused (front desk, never the register), PENDING_ACTIVATION attaches
     * with the earn-only warning, ACTIVE attaches silently. The attachment
     * itself is the SAME single entry point as a scan — the found number is
     * used exactly like a scanned one, holder identity added on top for the
     * operator's verbal check.
     *
     * @param state the current POS state
     * @param card the selected card number
     * @param lastName the holder's last name from the lookup
     * @param firstName the holder's first name from the lookup
     * @param status the account status from the lookup
     * @param email the holder's e-mail from the lookup, or null when the referential
     *        holds none (LC-08-02-09)
     * @return null on success, or the refusal message to display
     */
    public String attachLookedUpCard(PosState state, String card, String lastName,
                                     String firstName, String status, String email) {
        LOGGER.info("Entering method attachLookedUpCard with state: " + state + ", card: " + card + ", lastName: " + lastName + ", firstName: " + firstName + ", status: " + status + ", email: " + email);
        if ("RESILIATED".equals(status)) {
            LOGGER.info("Exiting method attachLookedUpCard");
            return i18n.get("op.carteResilieeInviterClient");
        }
        validateCard(state, card);
        // BO-10-03-04: the holder's name is carried onto the register only when
        // the store administers it. Off, the register keeps the pseudonymity of
        // a scanned card even after a lookup — nothing to show, nothing stored.
        boolean showHolderName = posSettingsService.fidelityShowHolderName();
        state.fidelity.holderLastName = showHolderName ? lastName : null;
        state.fidelity.holderFirstName = showHolderName ? firstName : null;
        // LC-08-02-09: the address the register offers before sending the receipt.
        // Blank means the referential holds none — the operator types one.
        state.fidelity.holderEmail = email == null || email.isBlank() ? null : email.trim();
        if (state.fidelity.accountStatus == null) {
            state.fidelity.accountStatus = status;
        }
        if ("PENDING_ACTIVATION".equals(status)) {
            // The card earns but cannot burn (reservation would answer 422
            // ACCOUNT_STATUS) — tell the cashier so the client is told.
            state.ticket.setError(i18n.get("op.carteAttenteActivationCagnotte"));
        }
        LOGGER.info("Exiting method attachLookedUpCard");
        return null;
    }

    /**
     * Returns true when the value carries text.
     *
     * @param s the value
     * @return true when non-null and non-blank
     */
    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Render-ready outcome of a holder lookup: the matches and/or the
     * message replacing or annotating them.
     */
    /**
     * Valuation-path hook — an ENGINE outcome was applied: the couple is
     * captured VERBATIM (spec §1) and the projection refreshes (spec §3:
     * /api/earn after each revaluation, pure read, as often as needed).
     *
     * @param state the current POS state
     * @param requestJson the raw /valuation request JSON, as sent
     * @param responseJson the raw /valuation response JSON, as received
     */
    public void onValuation(PosState state, String requestJson, String responseJson) {
        LOGGER.info("Entering method onValuation with state: " + state + ", requestJson: " + requestJson + ", responseJson: " + responseJson);
        state.fidelity.lastValuationRequestJson = requestJson;
        state.fidelity.lastValuationResponseJson = responseJson;
        refreshEarn(state);
        LOGGER.info("Exiting method onValuation");
    }

    /**
     * Valuation-path hook — no engine couple exists (LOCAL or DEGRADED
     * valuation): without a verbatim couple there is no projection to ask
     * for; the display clears and the stale couple is dropped.
     *
     * @param state the current POS state
     */
    public void onValuationUnavailable(PosState state) {
        LOGGER.info("Entering method onValuationUnavailable with state: " + state);
        state.fidelity.lastValuationRequestJson = null;
        state.fidelity.lastValuationResponseJson = null;
        state.fidelity.clearEarn();
        LOGGER.info("Exiting method onValuationUnavailable");
    }

    /**
     * Reserves the fidelity-payment lease for the requested amount (spec
     * §5.1). The POS-side cap is applied FIRST: {@code min(requested,
     * remaining, burnableBase, availableBalance)} — the account is read live
     * for its available balance. Business refusals come back as display
     * messages; only a granted lease returns an amount.
     *
     * @param state the current POS state
     * @param requested the amount asked by the cashier (null/zero = max)
     * @param remaining the remaining due of the ticket
     * @return the verdict: granted amount, or the refusal message
     */
    public BurnVerdict reserveLease(PosState state, java.math.BigDecimal requested,
                                    java.math.BigDecimal remaining) {
        LOGGER.info("Entering method reserveLease with state: " + state + ", requested: " + requested + ", remaining: " + remaining);
        if (!state.fidelity.active) {
            LOGGER.info("Exiting method reserveLease");
            return BurnVerdict.refuse(i18n.get("op.carteFideliteRequise"));
        }
        if (!imfidClient.isConfigured() || System.currentTimeMillis() < fidSkipUntil) {
            LOGGER.info("Exiting method reserveLease");
            return BurnVerdict.refuse(i18n.get("op.serviceFideliteIndisponible"));
        }
        try {
            ImfidClient.AccountInfo account = imfidClient.account(state.fidelity.label);
            if (account == null) {
                LOGGER.info("Exiting method reserveLease");
                return BurnVerdict.refuse(i18n.get("op.carteFideliteInconnue"));
            }
            java.math.BigDecimal cap = remaining;
            if (state.fidelity.burnableBase != null) {
                cap = cap.min(state.fidelity.burnableBase);
            }
            if (account.availableBalance != null) {
                cap = cap.min(account.availableBalance);
            }
            if (cap.signum() <= 0) {
                LOGGER.info("Exiting method reserveLease");
                return BurnVerdict.refuse(i18n.get("op.cagnotteAucunMontantUtilisable"));
            }
            java.math.BigDecimal amount = (requested == null || requested.signum() <= 0)
                    ? cap : requested.min(cap);
            String ticketRef = leaseTicketRef(state);
            ImfidClient.ReservationResult result =
                    imfidClient.reserve(state.fidelity.label, amount, ticketRef);
            switch (result.httpStatus) {
                case 201, 200 -> {
                    state.payment.fidReservationId = result.reservationId;
                    state.payment.fidLeaseExpiresAt = parseLocal(result.expiresAt);
                    if (state.payment.fidLeaseExpiresAt != null) {
                        state.payment.fidLeaseSeconds = java.time.Duration
                                .between(java.time.LocalDateTime.now(),
                                        state.payment.fidLeaseExpiresAt).getSeconds();
                    }
                    LOGGER.info("Exiting method reserveLease");
                    return BurnVerdict.grant(amount);
                }
                case 404 -> { LOGGER.info("Exiting method reserveLease"); return BurnVerdict.refuse(i18n.get("op.carteFideliteInconnue")); }
                case 409 -> { LOGGER.info("Exiting method reserveLease"); return BurnVerdict.refuse("CAGNOTTE RÉSERVÉE SUR UNE AUTRE CAISSE"); }
                case 422 -> {
                    LOGGER.info("Exiting method reserveLease");
                    return BurnVerdict.refuse(switch (result.reason == null ? "" : result.reason) {
                        case "INSUFFICIENT_BALANCE" -> i18n.get("op.soldeCagnotteInsuffisant");
                        case "DAILY_RULE" -> i18n.get("op.cagnotteDejaUtiliseeAujourd");
                        case "ACCOUNT_STATUS" -> i18n.get("op.compteFideliteInactif");
                        default -> i18n.get("op.paiementFideliteRefuse");
                    });
                }
                default -> { LOGGER.info("Exiting method reserveLease"); return BurnVerdict.refuse(i18n.get("op.paiementFideliteRefuse")); }
            }
        } catch (Exception e) {
            fidSkipUntil = System.currentTimeMillis() + RETRY_SECONDS * 1000L;
            LOGGER.warnf("imfid indisponible au burn (%s)", e.getMessage());
            LOGGER.info("Exiting method reserveLease");
            return BurnVerdict.refuse(i18n.get("op.serviceFideliteIndisponible"));
        }
    }

    /**
     * Renews the active lease at half-life (spec §5.1 recommendation) — an
     * identical re-POST refreshes {@code expiresAt}. Called opportunistically
     * from the payment screen; a miss is harmless (the 410 at confirm is
     * tolerated by design).
     *
     * @param state the current POS state
     */
    public void maybeRenewLease(PosState state) {
        LOGGER.info("Entering method maybeRenewLease with state: " + state);
        if (state.payment.fidReservationId == null
                || state.payment.fidLeaseExpiresAt == null
                || state.payment.fidLeaseSeconds <= 0) {
            LOGGER.info("Exiting method maybeRenewLease");
            return;
        }
        long remainingSeconds = java.time.Duration.between(java.time.LocalDateTime.now(),
                state.payment.fidLeaseExpiresAt).getSeconds();
        if (remainingSeconds > state.payment.fidLeaseSeconds / 2) {
            LOGGER.info("Exiting method maybeRenewLease");
            return;
        }
        try {
            java.math.BigDecimal reserved = state.payment.payments.stream()
                    .filter(p -> "FIDELITY".equals(p.method))
                    .map(p -> p.amount)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            ImfidClient.ReservationResult result = imfidClient.reserve(
                    state.fidelity.label, reserved, leaseTicketRef(state));
            if (result.httpStatus == 200 || result.httpStatus == 201) {
                state.payment.fidLeaseExpiresAt = parseLocal(result.expiresAt);
            }
        } catch (Exception e) {
            LOGGER.debugf("Renouvellement de bail fidélité manqué (%s) — le 410 au confirm est toléré",
                    e.getMessage());
        }
        LOGGER.info("Exiting method maybeRenewLease");
    }

    /**
     * Releases the active lease (payment cancelled — spec §5.3) and forgets
     * it. Idempotent and failure-tolerant: an unreleased lease expires by
     * TTL.
     *
     * @param state the current POS state
     */
    public void releaseLease(PosState state) {
        LOGGER.info("Entering method releaseLease with state: " + state);
        if (state.payment.fidReservationId != null) {
            imfidClient.release(state.payment.fidReservationId);
            state.payment.fidReservationId = null;
            state.payment.fidLeaseExpiresAt = null;
            state.payment.fidLeaseSeconds = 0L;
        }
        LOGGER.info("Exiting method releaseLease");
    }

    /**
     * Confirms the lease at the fiscal moment (spec §5.2). A 410 (lease
     * expired) NEVER blocks the closing: the reservation id still travels in
     * the ticket-closed event and the ingestion, authoritative, creates the
     * BURN with its warning.
     *
     * @param state the current POS state
     * @param fiscalDate the ticket's fiscal date
     * @return the reservation id to carry in the ticket-closed event, or null
     */
    public Long confirmLease(PosState state, java.time.LocalDate fiscalDate) {
        LOGGER.info("Entering method confirmLease with state: " + state + ", fiscalDate: " + fiscalDate);
        Long reservationId = state.payment.fidReservationId;
        if (reservationId == null) {
            LOGGER.info("Exiting method confirmLease");
            return null;
        }
        try {
            ImfidClient.ConfirmResult confirmation = imfidClient.confirm(reservationId, fiscalDate.toString());
            if (confirmation.httpStatus == 410) {
                LOGGER.warn("Bail fidélité expiré au moment fiscal: l'ingestion tranchera "
                        + "(EXPIRED_LEASE_CONFIRMED)");
            } else if (confirmation.httpStatus != 200) {
                LOGGER.warnf("Confirmation de bail fidélité inattendue: HTTP %d", confirmation.httpStatus);
            }
            // imfid response B: balanceAfter comes from the ledger and is printed
            // as SOLDE APRES; the register never recomputes it. balanceBefore may
            // be null on an idempotent replay — tolerated, it is not required here.
            if (confirmation.balanceAfter != null) {
                state.fidelity.balanceAfter = confirmation.balanceAfter;
            }
        } catch (Exception e) {
            LOGGER.warnf("Confirmation de bail fidélité injoignable (%s): l'ingestion tranchera",
                    e.getMessage());
        }
        LOGGER.info("Exiting method confirmLease");
        return reservationId;
    }

    /**
     * Composes the ticket-closed event payload (spec §6): the verbatim
     * couple, the displayed earn (trace only), the card, the fiscal date and
     * the reservation id — by string composition, the couple never
     * reserialized.
     *
     * @param state the current POS state (couple + displayed entries)
     * @param ticketRef the closed ticket's unique reference
     * @param card the attached card number
     * @param fiscalDate the ticket's fiscal date
     * @param reservationId the confirmed lease id, or null
     * @return the composed JSON body, or null when no couple exists
     */
    public String buildTicketClosedPayload(PosState state, String ticketRef, String card,
                                           java.time.LocalDate fiscalDate, Long reservationId) {
        LOGGER.info("Entering method buildTicketClosedPayload with state: " + state + ", ticketRef: " + ticketRef + ", card: " + card + ", fiscalDate: " + fiscalDate + ", reservationId: " + reservationId);
        if (state.fidelity.lastValuationRequestJson == null
                || state.fidelity.lastValuationResponseJson == null) {
            LOGGER.info("Exiting method buildTicketClosedPayload");
            return null;
        }
        StringBuilder displayed = new StringBuilder("[");
        for (int i = 0; i < state.fidelity.earnEntries.size(); i++) {
            FidelityState.EarnLine line = state.fidelity.earnEntries.get(i);
            if (i > 0) displayed.append(',');
            displayed.append("{\"ruleCode\":\"").append(line.ruleCode)
                    .append("\",\"amount\":").append(line.amount.toPlainString()).append('}');
        }
        displayed.append(']');
        // BO-03-10-08: the island's promotion channel rides the ticket-closed
        // event towards the loyalty programme. Appended ONLY when administered,
        // so an unposted channel leaves the payload identical to the byte to
        // what it was before the option existed.
        String channel = posSettingsService.islandPromotionChannel();
        String channelMember = (channel != null && !channel.isBlank())
                ? ",\"channel\":\"" + channel + "\"" : "";
        LOGGER.info("Exiting method buildTicketClosedPayload");
        return "{\"ticketRef\":\"" + ticketRef + "\","
                + "\"card\":\"" + card + "\","
                + "\"fiscalDate\":\"" + fiscalDate + "\","
                + "\"valuationRequest\":" + state.fidelity.lastValuationRequestJson + ","
                + "\"valuationResponse\":" + state.fidelity.lastValuationResponseJson + ","
                + "\"displayedEarn\":" + displayed
                + (reservationId != null ? ",\"reservationId\":" + reservationId : "")
                + channelMember
                + "}";
    }

    /**
     * Loads the in-store consultation of the attached card (spec §4):
     * status, balance, AVAILABLE balance and the recent movements — rendered
     * server-side as display rows (date, label, signed amount), the type
     * nomenclature translated once here. Degraded imfid = a message, never
     * an error page.
     *
     * @param state the current POS state
     * @return the consultation view (either populated, or carrying a message)
     */
    public Consultation loadConsultation(PosState state) {
        LOGGER.info("Entering method loadConsultation with state: " + state);
        Consultation view = new Consultation();
        if (!state.fidelity.active) {
            view.message = i18n.get("op.aucuneCarteAttacheeScannez");
            LOGGER.info("Exiting method loadConsultation");
            return view;
        }
        if (!imfidClient.isConfigured()) {
            view.message = i18n.get("op.serviceFideliteNonConfigure");
            LOGGER.info("Exiting method loadConsultation");
            return view;
        }
        try {
            ImfidClient.AccountInfo account = imfidClient.account(state.fidelity.label);
            if (account == null) {
                view.message = i18n.get("op.carteFideliteInconnue");
                LOGGER.info("Exiting method loadConsultation");
                return view;
            }
            view.status = switch (account.status == null ? "" : account.status) {
                case "ACTIVE" -> i18n.get("op.actif");
                case "PENDING_ACTIVATION" -> i18n.get("op.attenteActivation");
                case "RESILIATED" -> i18n.get("op.resilie");
                default -> account.status;
            };
            view.balance = account.balance;
            view.available = account.availableBalance;
            ImfidClient.MovementsPage movementsPage =
                    imfidClient.movements(state.fidelity.label, 0, 15);
            if (movementsPage != null && movementsPage.items != null) {
                for (ImfidClient.Movement movement : movementsPage.items) {
                    String label = switch (movement.type == null ? "" : movement.type) {
                        case "EARN" -> movement.ruleCode != null
                                ? i18n.format("op.cagnotteRegle", movement.ruleCode)
                                : i18n.get("op.cagnotte");
                        case "BURN" -> i18n.get("op.utilisationCaisse");
                        case "RETURN_DEBIT" -> i18n.get("op.repriseRetour");
                        case "REFUND_CREDIT" -> i18n.get("op.remboursementCagnotte");
                        case "ADJUSTMENT" -> i18n.get("op.ajustement");
                        case "EXPIRY" -> i18n.get("op.peremption");
                        case "ACTIVATION_VOID" -> i18n.get("op.annulationActivation");
                        default -> movement.type;
                    };
                    String date = movement.fiscalDate != null ? movement.fiscalDate
                            : (movement.createdAt != null && movement.createdAt.length() >= 10
                                    ? movement.createdAt.substring(0, 10) : "");
                    String amount = (movement.amount != null && movement.amount.signum() > 0 ? "+" : "")
                            + (movement.amount != null ? movement.amount.toPlainString() : "") + " €";
                    view.rows.add(new String[] { date, label, amount });
                }
            }
        } catch (Exception e) {
            fidSkipUntil = System.currentTimeMillis() + RETRY_SECONDS * 1000L;
            view.message = i18n.get("op.serviceFideliteIndisponible");
        }
        LOGGER.info("Exiting method loadConsultation");
        return view;
    }

    /** The in-store consultation view, assembled server-side. */
    public static class Consultation {
        /** The translated account status, or null. */
        public String status;
        /** The book balance, or null. */
        public java.math.BigDecimal balance;
        /** The available balance (net of leases), or null. */
        public java.math.BigDecimal available;
        /** Display rows: date, label, signed amount. */
        public java.util.List<String[]> rows = new java.util.ArrayList<>();
        /** The message shown instead of data (degraded, unknown, no card). */
        public String message;

        /**
         * Tells whether account figures are present.
         *
         * @return true when the account was read
         */
        public boolean hasAccount() { return balance != null; }
    }

    /**
     * The stable per-draft lease reference (renewal key). The CLOSED ticket
     * reference differs — the lease links to the close through the
     * reservation id carried by the ticket-closed event, per spec §6.
     *
     * @param state the current POS state
     * @return the lease ticketRef
     */
    private String leaseTicketRef(PosState state) {
        return "D-" + (state.payment.ticketDbId != null ? state.payment.ticketDbId : 0);
    }

    /**
     * Parses an ISO local date-time, null-safe.
     *
     * @param iso the ISO string, or null
     * @return the parsed instant, or null
     */
    private java.time.LocalDateTime parseLocal(String iso) {
        try {
            return iso == null ? null : java.time.LocalDateTime.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    /** The verdict of a lease reservation. */
    public static class BurnVerdict {
        /** The granted amount, or null when refused. */
        public java.math.BigDecimal grantedAmount;
        /** The refusal display message, or null when granted. */
        public String refusalMessage;

        /**
         * Builds a granted verdict.
         *
         * @param amount the granted amount
         * @return the verdict
         */
        static BurnVerdict grant(java.math.BigDecimal amount) {
            BurnVerdict verdict = new BurnVerdict();
            verdict.grantedAmount = amount;
            return verdict;
        }

        /**
         * Builds a refusal verdict.
         *
         * @param message the display message
         * @return the verdict
         */
        static BurnVerdict refuse(String message) {
            BurnVerdict verdict = new BurnVerdict();
            verdict.refusalMessage = message;
            return verdict;
        }
    }

    /**
     * Refreshes the displayed earn projection from imfid, under the breaker.
     * Degraded (unconfigured, breaker open, transport failure, 4xx) = the
     * projection is HIDDEN (spec §2.2), never a blocking error.
     *
     * @param state the current POS state
     */
    private void refreshEarn(PosState state) {
        FidelityState fid = state.fidelity;
        boolean coupleReady = imfidClient.isConfigured()
                && fid.lastValuationRequestJson != null && fid.lastValuationResponseJson != null
                && System.currentTimeMillis() >= fidSkipUntil;
        if (!coupleReady) {
            fid.clearEarn();
            return;
        }
        if (fid.active) {
            refreshCardEarn(fid);
        } else if (posSettingsService.fidelityAnonymousProjection()) {
            refreshAnonymousEarn(fid);
        } else {
            fid.clearEarn();
        }
    }

    /**
     * Refreshes the earn projection for the ATTACHED card (imfid responses A
     * and B): the entries carry their advantage grouping and the response's
     * {@code balances} block feeds the PREVISIONNEL projection. Degraded = the
     * projection is hidden, never a blocking error.
     *
     * @param fid the fidelity sub-state
     */
    private void refreshCardEarn(FidelityState fid) {
        try {
            ImfidClient.EarnProjection projection =
                    imfidClient.earn(fid.lastValuationRequestJson, fid.lastValuationResponseJson);
            fid.clearEarn();
            fid.earnTotal = projection.total;
            fid.burnableBase = projection.burnableBase;
            stageEntries(fid, projection);
            stageWarnings(fid, projection);
            if (projection.balances != null) {
                fid.projectedAfterEarn = projection.balances.projectedAfterEarn;
                fid.balanceAsOf = projection.balances.asOf;
            }
        } catch (Exception e) {
            fidSkipUntil = System.currentTimeMillis() + RETRY_SECONDS * 1000L;
            fid.clearEarn();
            LOGGER.warnf("imfid indisponible (%s): projection d'earn masquée, prochain essai dans %d s",
                    e.getMessage(), RETRY_SECONDS);
        }
    }

    /**
     * Refreshes the ANONYMOUS projection for a card-less ticket (imfid response
     * C): « ce que vous auriez gagné avec la carte ». The call carries
     * {@code projectionMode=ANONYMOUS}; the answer has no balances and a zero
     * burnable base, so only the total and its entries are staged and the line
     * is flagged as an anonymous projection.
     *
     * @param fid the fidelity sub-state
     */
    private void refreshAnonymousEarn(FidelityState fid) {
        try {
            ImfidClient.EarnProjection projection = imfidClient.earn(
                    fid.lastValuationRequestJson, fid.lastValuationResponseJson, "ANONYMOUS");
            fid.clearEarn();
            fid.earnTotal = projection.total;
            fid.burnableBase = projection.burnableBase;
            fid.anonymousProjection = true;
            stageEntries(fid, projection);
            stageWarnings(fid, projection);
        } catch (Exception e) {
            fidSkipUntil = System.currentTimeMillis() + RETRY_SECONDS * 1000L;
            fid.clearEarn();
            LOGGER.warnf("imfid indisponible (%s): projection anonyme masquée, prochain essai dans %d s",
                    e.getMessage(), RETRY_SECONDS);
        }
    }

    /**
     * Stages the projection's entries as displayed earn lines, carrying each
     * entry's advantage grouping (imfid response A). The five advantage fields
     * may be null (legacy rule) — they are staged as-is and the ticket falls
     * back to a flat list.
     *
     * @param fid the fidelity sub-state
     * @param projection the parsed earn projection
     */
    /**
     * Stages the projection's non-blocking warnings on the fidelity state so the
     * cashier sees the fidelity service's messages (EXP-76): the {@code warnings}
     * field was deserialized but never surfaced. A null or empty list leaves the
     * channel empty.
     *
     * @param fid the fidelity sub-state
     * @param projection the parsed earn projection
     */
    private void stageWarnings(FidelityState fid, ImfidClient.EarnProjection projection) {
        if (projection.warnings == null || projection.warnings.isEmpty()) {
            return;
        }
        fid.fidelityWarnings = new java.util.ArrayList<>(projection.warnings);
    }

    private void stageEntries(FidelityState fid, ImfidClient.EarnProjection projection) {
        if (projection.entries == null) {
            return;
        }
        for (ImfidClient.EarnEntry entry : projection.entries) {
            fid.earnEntries.add(new FidelityState.EarnLine(entry.ruleCode, entry.label, entry.amount,
                    entry.advantageType, entry.advantageTypeLabel, entry.advantageTypeOrder,
                    entry.advantageCategory, entry.advantageCategoryLabel));
        }
    }

    /**
     * Applies the card's IMMEDIATE_DISCOUNT promotions to the cart as line
     * remises (promotions addendum §3.1): PERCENT = value × the net price of
     * the matching lines; AMOUNT = value × the number of units, a weighed line
     * counting as ONE; the discount hits the lines carrying exactly the
     * promotion's EAN, rounded to the cent, once per promotion.
     * <p>
     * §2 — NEVER BOTH. When the store forwards these promotions to the
     * valuation engine ({@code fidelityJvPromotionEnabled}), the engine applies
     * them and the register must not, or the advantage is doubled. The register
     * applies them itself ONLY when that forwarding is off. CAGNOTTE promotions
     * never reach here (the {@code benefit=IMMEDIATE_DISCOUNT} request filter
     * excludes them) and are never applied by the register.
     * <p>
     * KNOWN LIMITATION (flagged for arbitrage): on an ENGINE-valued line the
     * engine's {@code valuedTotal} overrides the unit price this remise edits,
     * so the caisse-side application takes effect only on locally priced lines;
     * a definitive activation needs the valuation-precedence arbitrage.
     *
     * @param state the current POS state
     */
    public void applyCardPromotions(PosState state) {
        LOGGER.info("Entering method applyCardPromotions with state: " + state);
        if (state.fidelity.cardPromotions == null || state.fidelity.cardPromotions.isEmpty()) {
            LOGGER.info("Exiting method applyCardPromotions");
            return;
        }
        if (posSettingsService.fidelityJvPromotionEnabled()) {
            LOGGER.info("Exiting method applyCardPromotions");
            return;
        }
        for (ImfidClient.CardPromotion promotion : state.fidelity.cardPromotions) {
            if (promotion == null || promotion.ean == null || promotion.ean.isBlank()
                    || !ImfidClient.BENEFIT_IMMEDIATE_DISCOUNT.equals(promotion.benefit)) {
                continue;
            }
            applyOnePromotion(state, promotion);
        }
        LOGGER.info("Exiting method applyCardPromotions");
    }

    /**
     * Applies one IMMEDIATE_DISCOUNT promotion to every cart line carrying its
     * EAN (promotions addendum §3.1). The discount is computed once over the
     * matching lines and taken on the first one through
     * {@link com.intermarche.pos.ui.ticket.TicketService#applyRemise}.
     *
     * @param state the current POS state
     * @param promotion the immediate-discount promotion to apply
     */
    private void applyOnePromotion(PosState state, ImfidClient.CardPromotion promotion) {
        java.util.List<com.intermarche.pos.ui.ticket.TicketState.TicketItem> matches =
                new java.util.ArrayList<>();
        for (com.intermarche.pos.ui.ticket.TicketState.TicketItem item : state.ticket.items) {
            if (item != null && promotion.ean.equals(item.ean)) {
                matches.add(item);
            }
        }
        if (matches.isEmpty()) {
            return;
        }
        java.math.BigDecimal netTotal = java.math.BigDecimal.ZERO;
        long units = 0L;
        for (com.intermarche.pos.ui.ticket.TicketState.TicketItem item : matches) {
            netTotal = netTotal.add(item.getTotalPrice());
            units += unitsOf(item);
        }
        java.math.BigDecimal discount = immediateDiscount(
                promotion.promotionType, promotion.value, netTotal, units);
        if (discount.signum() > 0) {
            ticketService.applyRemise(matches.get(0), discount);
        }
    }

    /**
     * Counts the units of a line for an AMOUNT-per-unit promotion (§3.1 /
     * §22.2): a weighed line (a non-blank PLU) counts as ONE, a unit line
     * counts its whole quantity.
     *
     * @param item the cart line
     * @return the unit count, one for a weighed line
     */
    private long unitsOf(com.intermarche.pos.ui.ticket.TicketState.TicketItem item) {
        boolean weighed = item.plu != null && !item.plu.isBlank();
        if (weighed) {
            return 1L;
        }
        return item.quantity == null ? 0L : item.quantity.longValue();
    }

    /**
     * Computes an IMMEDIATE_DISCOUNT (promotions addendum §3.1), rounded to the
     * cent once: PERCENT = value (a FRACTION, 0.05 = 5 %) × the net price;
     * AMOUNT = value (euro per unit) × the number of units. Any other type or a
     * non-positive value yields zero.
     *
     * @param promotionType PERCENT or AMOUNT
     * @param value the promotion value (fraction for PERCENT, euro/unit for AMOUNT)
     * @param netTotal the net price of the matching lines (PERCENT base)
     * @param units the number of units (AMOUNT multiplier)
     * @return the discount amount, rounded to the cent, never negative
     */
    static java.math.BigDecimal immediateDiscount(String promotionType, java.math.BigDecimal value,
            java.math.BigDecimal netTotal, long units) {
        if (value == null || value.signum() <= 0) {
            return java.math.BigDecimal.ZERO;
        }
        java.math.BigDecimal raw;
        if ("PERCENT".equals(promotionType)) {
            raw = value.multiply(netTotal == null ? java.math.BigDecimal.ZERO : netTotal);
        } else if ("AMOUNT".equals(promotionType)) {
            raw = value.multiply(java.math.BigDecimal.valueOf(units));
        } else {
            return java.math.BigDecimal.ZERO;
        }
        java.math.BigDecimal rounded = raw.setScale(2, java.math.RoundingMode.HALF_UP);
        return rounded.signum() < 0 ? java.math.BigDecimal.ZERO : rounded;
    }
}