package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;

/**
 * JAX-RS resource driving the payment screen: payment method actions, voucher
 * entry flow, payment history pagination, finalization and printing.
 * <p>
 * Phase 0: form amounts are parsed straight into {@link BigDecimal}.
 * <p>
 * The completion modal's two buttons are a CONTRACT with a deliberate
 * order: IMPRIMER prints the still-OPEN draft by id (the original print,
 * before any duplicata counting), TERMINER triggers the fiscal moment
 * (validation, chaining, number) and clears the screen — printing after
 * TERMINER goes through the reprint flow with its numbered duplicatas. In
 * training the print branch swaps to the in-memory training receipt, since
 * no draft exists to print.
 */
@Path("/")
@DrawerMustBeClosed
public class PaymentResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PaymentResource.class);

    /**
     * The localised operator messages: every sentence this class shows the
     * operator is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;


    @Inject Template pay;

    /** The control points of the sale (BO-10-02-02). */
    @Inject com.intermarche.pos.ui.endorsement.EndorsementService endorsementService;

    @Inject PaymentService paymentService;
    @Inject VoucherService voucherService;
    @Inject
    TicketPrinterService ticketPrinterService;
    @Inject PosState state;
    /** The back-office parameters read by the payment page (LC-07-07-09). */
    @Inject com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** Ticket lifecycle service — used by the payment-phase ticket abandon. */
    @Inject com.intermarche.pos.ui.ticket.TicketService ticketService;
    /** Loyalty lease renewal on payment-screen refresh (imfid lot 2). */
    @Inject com.intermarche.pos.ui.fidelity.FidelityService fidelityService;
    /** The conditional-printing rule — drives the end-of-transaction choice (LC-08-03). */
    @Inject com.intermarche.pos.ui.hardware.PrintPolicy printPolicy;
    /** The customer-credit panel of the payment screen (LC-07-09). */
    @Inject CreditClientService creditClientService;
    /** The foreign-currency panel of the payment screen (LC-07-14). */
    @Inject ForeignCurrencyService foreignCurrencyService;
    /** The backup-monetics panel of the payment screen (LC-07-07-06/09). */
    @Inject BackupPaymentService backupPaymentService;

    /** The administered tender rules — which tenders the screen offers (BO-03-02). */
    @Inject TenderRulesService tenderRulesService;

    /**
     * Shows the payment page, creating the draft ticket on first entry.
     *
     * @return the payment page
     */
    @GET
    @Path("/pay")
    @DrawerMayBeOpen
    public TemplateInstance showPaymentPage() {
        LOGGER.info("Entering method showPaymentPage");
        paymentService.initPayment(state);
        // Opportunistic half-life renewal of the fidelity lease (spec §5.1).
        fidelityService.maybeRenewLease(state);
        state.payment.inputMode = null;
        state.payment.temporaryInput = "0,00";
        LOGGER.info("Exiting method showPaymentPage");
        return pay.data("state", state)
                .data("offeredTenders", tenderRulesService.offered())
                .data("tenderDefaults", tenderRulesService.administeredDefaultAmounts())
                .data("couponTypes", CouponType.listActivePaymentTypes())
                .data("currencies", foreignCurrencyService.listCurrencies())
                .data("backupEndorsement", posSettingsService.backupManualEndorsement())
                // BO-10-04-02 et -08 : le panneau crédit montre les coordonnées
                // du client et lui annonce sa remise, ou non, selon le magasin.
                .data("showCustomerDetails", posSettingsService.showCustomerDetails())
                .data("showCustomerDiscount",
                        creditClientService.discountAnnounced(state.payment.creditCustomer))
                .data("digitalPath", digitalPath())
                .data("printConditional", printPolicy.isConditionalEnabled())
                .data("printChoices",
                        java.util.List.of(com.intermarche.pos.ui.hardware.PrintChoice.values()));
    }

    /**
     * Builds the online digital-receipt path of the current draft, or null.
     *
     * @return the digital receipt path, or null when unavailable
     */
    private String digitalPath() {
        if (state.payment.ticketDbId == null) return null;
        Ticket ticket = Ticket.findById(state.payment.ticketDbId);
        if (ticket == null || ticket.digitalKey == null) return null;
        return "/t/" + ticket.id + "/" + ticket.digitalKey;
    }

    /**
     * Cancels the pending virtual-terminal card request from the register.
     *
     * @return a redirect back to the payment page
     */
    @GET
    @Path("/action/card-cancel")
    public Response cancelPendingCard() {
        LOGGER.info("Entering method cancelPendingCard");
        paymentService.cancelPendingCard(state);
        LOGGER.info("Exiting method cancelPendingCard");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Cancels the pending cheque reading from the register.
     *
     * @return a redirect back to the payment page
     */
    @GET
    @Path("/action/cheque-cancel")
    public Response cancelPendingCheque() {
        LOGGER.info("Entering method cancelPendingCheque");
        paymentService.cancelPendingCheque(state);
        LOGGER.info("Exiting method cancelPendingCheque");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Toggles the solidarity round-up line on the current ticket.
     *
     * @return a redirect back to the payment page
     */
    @GET
    @Path("/action/donation")
    public Response toggleDonation() {
        LOGGER.info("Entering method toggleDonation");
        paymentService.toggleDonationRoundup(state);
        LOGGER.info("Exiting method toggleDonation");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Parses a form amount (French comma tolerated) into a BigDecimal.
     *
     * @param value the raw form value
     * @return the parsed amount, or ZERO when blank or invalid
     */
    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.replace(",", "."));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Applies the PAYMENT AMOUNT control point to a figure the cashier just
     * keyed (BO-10-02-17).
     *
     * <p>Said HERE, at the screen, and not in each tender's service: the
     * ceiling watches what a person types, and every panel of this screen types
     * into the same parser. A tender the register computes for itself — the
     * remaining due, the cash rounding, the change — never passes through
     * here, and must not: the shop plafonds what is keyed, not what it owes.
     *
     * <p>THE COMPARISON IS ABSOLUTE, for the reason the article's ceiling gives:
     * the questionnaire asks the ceiling to hold for sales and for returns, and
     * a refund is the same figure with the other sign.
     *
     * <p>The point is not endorsable — see {@code ControlPoint.isEndorsable()}
     * — so a shop administering an aval gets a refusal here rather than a
     * ceremony the payment screen has no modal for.
     *
     * @param amount the amount keyed, possibly zero when the field was blank
     * @return true when the settlement may go on
     */
    private boolean paymentAmountAllowed(BigDecimal amount) {
        java.math.BigDecimal ceiling = posSettingsService.controlPaymentAmountMax();
        if (ceiling == null || amount == null || amount.abs().compareTo(ceiling) <= 0) {
            return true;
        }
        com.intermarche.pos.ui.endorsement.ControlLevel level =
                endorsementService.check(state, com.intermarche.pos.ui.endorsement.ControlPoint.PAYMENT_AMOUNT);
        // BO-03-10-14: silenced by the island, the refusal below never is.
        if (level == com.intermarche.pos.ui.endorsement.ControlLevel.WARN) {
            if (!posSettingsService.islandBlockPaymentPopups()) {
                state.ticket.setWarning(endorsementService.controlMessage(
                        com.intermarche.pos.ui.endorsement.ControlPoint.PAYMENT_AMOUNT, level));
            }
        } else if (!level.allows()) {
            state.ticket.setError(endorsementService.controlMessage(
                    com.intermarche.pos.ui.endorsement.ControlPoint.PAYMENT_AMOUNT, level));
        }
        return level.allows();
    }

    /**
     * Registers a card payment.
     *
     * @param amountStr the amount typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-card")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doCardPayment(@FormParam("amount") String amountStr) {
        LOGGER.info("Entering method doCardPayment with amountStr: " + amountStr);
        BigDecimal amount = parseAmount(amountStr);
        if (!paymentAmountAllowed(amount)) {
            LOGGER.info("Exiting method doCardPayment");
        return Response.seeOther(URI.create("/pay")).build();
        }
        paymentService.processCard(state, amount);
        LOGGER.info("Exiting method doCardPayment");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers a cash payment from the tendered amount.
     *
     * @param givenStr the cash amount handed over by the customer
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-cash")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doCashPayment(@FormParam("given") String givenStr) {
        LOGGER.info("Entering method doCashPayment with givenStr: " + givenStr);
        BigDecimal amount = parseAmount(givenStr);
        if (!paymentAmountAllowed(amount)) {
            LOGGER.info("Exiting method doCashPayment");
        return Response.seeOther(URI.create("/pay")).build();
        }
        paymentService.processCash(state, amount);
        LOGGER.info("Exiting method doCashPayment");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Pays out the net refund of a mixed ticket whose signed total is negative
     * (EXP-38): the returns outweigh the purchases and the register gives the
     * difference back in the chosen tender.
     *
     * @param methodKey the tender the refund is paid out in (default CASH)
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-net-refund")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doNetRefund(@FormParam("method") String methodKey) {
        LOGGER.info("Entering method doNetRefund with method: " + methodKey);
        paymentService.processNetRefund(state,
                (methodKey == null || methodKey.isBlank()) ? "CASH" : methodKey.trim());
        LOGGER.info("Exiting method doNetRefund");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers a meal-ticket payment.
     *
     * @param amountStr the amount typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-tr")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doTrPayment(@FormParam("amount") String amountStr) {
        LOGGER.info("Entering method doTrPayment with amountStr: " + amountStr);
        BigDecimal amount = parseAmount(amountStr);
        if (!paymentAmountAllowed(amount)) {
            LOGGER.info("Exiting method doTrPayment");
        return Response.seeOther(URI.create("/pay")).build();
        }
        paymentService.processTicketResto(state, amount);
        LOGGER.info("Exiting method doTrPayment");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers a fidelity payment.
     *
     * @param amountStr the amount typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-fid")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doFidelityPayment(@FormParam("amount") String amountStr) {
        LOGGER.info("Entering method doFidelityPayment with amountStr: " + amountStr);
        BigDecimal amount = parseAmount(amountStr);
        if (!paymentAmountAllowed(amount)) {
            LOGGER.info("Exiting method doFidelityPayment");
        return Response.seeOther(URI.create("/pay")).build();
        }
        paymentService.processFidelity(state, amount);
        LOGGER.info("Exiting method doFidelityPayment");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers a cheque payment.
     *
     * @param amountStr the amount typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-cheque")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doChequePayment(@FormParam("amount") String amountStr) {
        LOGGER.info("Entering method doChequePayment with amountStr: " + amountStr);
        BigDecimal amount = parseAmount(amountStr);
        if (!paymentAmountAllowed(amount)) {
            LOGGER.info("Exiting method doChequePayment");
        return Response.seeOther(URI.create("/pay")).build();
        }
        paymentService.processCheque(state, amount);
        LOGGER.info("Exiting method doChequePayment");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers a TRD-card (dematerialized meal-voucher) payment (EXP-60), bound
     * to the meal-voucher eligible base and the administered plafond.
     *
     * @param amountStr the amount typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-trd")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doTrdPayment(@FormParam("amount") String amountStr) {
        LOGGER.info("Entering method doTrdPayment with amountStr: " + amountStr);
        BigDecimal amount = parseAmount(amountStr);
        if (!paymentAmountAllowed(amount)) {
            LOGGER.info("Exiting method doTrdPayment");
        return Response.seeOther(URI.create("/pay")).build();
        }
        paymentService.processTrdCard(state, amount);
        LOGGER.info("Exiting method doTrdPayment");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers a Resto Flash mobile meal-voucher payment (EXP-67): the mobile
     * token is confirmed with the Resto Flash service before it settles.
     *
     * @param token the mobile payment token presented by the customer
     * @param amountStr the amount typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-restoflash")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doRestoFlashPayment(@FormParam("token") String token,
                                        @FormParam("amount") String amountStr) {
        LOGGER.info("Entering method doRestoFlashPayment with amountStr: " + amountStr);
        BigDecimal amount = parseAmount(amountStr);
        if (!paymentAmountAllowed(amount)) {
            LOGGER.info("Exiting method doRestoFlashPayment");
        return Response.seeOther(URI.create("/pay")).build();
        }
        paymentService.processRestoFlash(state, token, amount);
        LOGGER.info("Exiting method doRestoFlashPayment");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers a supplier gift-card payment (EXP-66): the issuer registry is
     * interrogated for the card's validity and balance before it settles.
     *
     * @param cardNumber the scanned or keyed gift-card number
     * @param amountStr the amount typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-giftcard")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doGiftCardPayment(@FormParam("cardNumber") String cardNumber,
                                      @FormParam("amount") String amountStr) {
        LOGGER.info("Entering method doGiftCardPayment with amountStr: " + amountStr);
        BigDecimal amount = parseAmount(amountStr);
        if (!paymentAmountAllowed(amount)) {
            LOGGER.info("Exiting method doGiftCardPayment");
        return Response.seeOther(URI.create("/pay")).build();
        }
        paymentService.processSupplierGiftCard(state, cardNumber, amount);
        LOGGER.info("Exiting method doGiftCardPayment");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers a SOGEC bon payment through the compensation circuit (EXP-58).
     *
     * @param couponCode the SOGEC bon code
     * @param amountStr the amount typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-sogec")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doSogecPayment(@FormParam("couponCode") String couponCode,
                                   @FormParam("amount") String amountStr) {
        LOGGER.info("Entering method doSogecPayment with amountStr: " + amountStr);
        BigDecimal amount = parseAmount(amountStr);
        if (!paymentAmountAllowed(amount)) {
            LOGGER.info("Exiting method doSogecPayment");
        return Response.seeOther(URI.create("/pay")).build();
        }
        paymentService.processSogec(state, couponCode, amount);
        LOGGER.info("Exiting method doSogecPayment");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Opens the voucher panel so the cashier can choose a type.
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/voucher-open")
    public Response openVoucherPanel() {
        LOGGER.info("Entering method openVoucherPanel");
        state.payment.clearPendingVoucher();
        state.payment.voucherPanelOpen = true;
        state.touch();
        LOGGER.info("Exiting method openVoucherPanel");
        return Response.seeOther(URI.create("/pay")).build();
    }

    // --------------------------------------------------
    // Backup monetics (LC-07-07-06/09)
    // --------------------------------------------------

    /**
     * Opens the backup-monetics panel and emits the request QR code
     * (LC-07-07-06/07).
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/backup-open")
    public Response openBackupPanel() {
        LOGGER.info("Entering method openBackupPanel");
        backupPaymentService.openPanel(state);
        LOGGER.info("Exiting method openBackupPanel");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Closes the backup-monetics panel, abandoning the pending request.
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/backup-cancel")
    public Response closeBackupPanel() {
        LOGGER.info("Entering method closeBackupPanel");
        backupPaymentService.closePanel(state);
        LOGGER.info("Exiting method closeBackupPanel");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Validates the answer scanned off the mobile terminal (LC-07-07-08).
     *
     * @param payload the scanned text
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/backup-scan")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response validateBackupScan(@FormParam("payload") String payload) {
        LOGGER.info("Entering method validateBackupScan with payload: " + payload);
        backupPaymentService.validateScanned(state, payload);
        LOGGER.info("Exiting method validateBackupScan");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Switches the panel to keying the outcome in, for a till whose scanner cannot
     * read 2D codes (LC-07-07-09).
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/backup-manual")
    public Response openBackupManualEntry() {
        LOGGER.info("Entering method openBackupManualEntry");
        state.payment.backupManualEntry = true;
        state.payment.backupError = null;
        state.touch();
        LOGGER.info("Exiting method openBackupManualEntry");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers the amount the operator keyed in (LC-07-07-09).
     *
     * @param amountStr the amount the mobile terminal accepted
     * @param login the supervisor's login, when one is required
     * @param password the supervisor's password
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-backup")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doBackupPayment(@FormParam("amount") String amountStr,
            @FormParam("login") String login, @FormParam("password") String password) {
        LOGGER.info("Entering method doBackupPayment with amountStr: " + amountStr + ", login: " + login + ", password: ***");
        BigDecimal amount = parseAmount(amountStr);
        if (!paymentAmountAllowed(amount)) {
            LOGGER.info("Exiting method doBackupPayment");
        return Response.seeOther(URI.create("/pay")).build();
        }
        backupPaymentService.validateManually(state, amount, login, password);
        LOGGER.info("Exiting method doBackupPayment");
        return Response.seeOther(URI.create("/pay")).build();
    }

    // --------------------------------------------------
    // Foreign currency (LC-07-14)
    // --------------------------------------------------

    /**
     * Opens the foreign-currency panel over the payment screen.
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/currency-open")
    public Response openCurrencyPanel() {
        LOGGER.info("Entering method openCurrencyPanel");
        foreignCurrencyService.openPanel(state);
        LOGGER.info("Exiting method openCurrencyPanel");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Closes the foreign-currency panel without settling anything.
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/currency-cancel")
    public Response closeCurrencyPanel() {
        LOGGER.info("Entering method closeCurrencyPanel");
        foreignCurrencyService.closePanel(state);
        LOGGER.info("Exiting method closeCurrencyPanel");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Selects the currency the customer is paying in, which reveals the rate and
     * the amount due in that currency (LC-07-14-04).
     *
     * @param code the ISO code of the selected currency
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/currency-select")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response selectCurrency(@FormParam("code") String code) {
        LOGGER.info("Entering method selectCurrency with code: " + code);
        foreignCurrencyService.selectCurrency(state, code);
        LOGGER.info("Exiting method selectCurrency");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers a settlement handed over in the selected currency (LC-07-14-03).
     *
     * @param amountStr the amount handed over, in that currency
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-currency")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doCurrencyPayment(@FormParam("amount") String amountStr) {
        LOGGER.info("Entering method doCurrencyPayment with amountStr: " + amountStr);
        BigDecimal amount = parseAmount(amountStr);
        if (!paymentAmountAllowed(amount)) {
            LOGGER.info("Exiting method doCurrencyPayment");
        return Response.seeOther(URI.create("/pay")).build();
        }
        foreignCurrencyService.processCurrency(state, amount);
        LOGGER.info("Exiting method doCurrencyPayment");
        return Response.seeOther(URI.create("/pay")).build();
    }

    // --------------------------------------------------
    // Customer credit (LC-07-09)
    // --------------------------------------------------

    /**
     * Opens the customer-credit panel over the payment screen.
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/credit-open")
    public Response openCreditPanel() {
        LOGGER.info("Entering method openCreditPanel");
        creditClientService.openPanel(state);
        LOGGER.info("Exiting method openCreditPanel");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Closes the customer-credit panel without settling anything.
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/credit-cancel")
    public Response closeCreditPanel() {
        LOGGER.info("Entering method closeCreditPanel");
        creditClientService.closePanel(state);
        LOGGER.info("Exiting method closeCreditPanel");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Names the account by its number (LC-07-09-02).
     *
     * @param number the account number typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/credit-number")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response selectCreditAccountByNumber(@FormParam("number") String number) {
        LOGGER.info("Entering method selectCreditAccountByNumber with number: " + number);
        creditClientService.selectByNumber(state, number);
        LOGGER.info("Exiting method selectCreditAccountByNumber");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Looks accounts up by name (LC-07-09-07).
     *
     * @param search the name fragment typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/credit-search")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response searchCreditAccounts(@FormParam("search") String search) {
        LOGGER.info("Entering method searchCreditAccounts with search: " + search);
        creditClientService.searchByName(state, search);
        LOGGER.info("Exiting method searchCreditAccounts");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Names the account picked from the search results (LC-07-09-08).
     *
     * @param customerId the database id of the picked account
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/credit-select")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response selectCreditAccount(@FormParam("customerId") String customerId) {
        LOGGER.info("Entering method selectCreditAccount with customerId: " + customerId);
        Long id = null;
        try {
            id = customerId == null ? null : Long.valueOf(customerId.trim());
        } catch (NumberFormatException e) {
            id = null;
        }
        creditClientService.selectById(state, id);
        LOGGER.info("Exiting method selectCreditAccount");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Charges the named account (LC-07-09-01/03).
     *
     * @param amountStr the amount typed by the cashier, blank for the whole due
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-credit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doCreditPayment(@FormParam("amount") String amountStr) {
        LOGGER.info("Entering method doCreditPayment with amountStr: " + amountStr);
        BigDecimal amount = parseAmount(amountStr);
        if (!paymentAmountAllowed(amount)) {
            LOGGER.info("Exiting method doCreditPayment");
        return Response.seeOther(URI.create("/pay")).build();
        }
        creditClientService.processCredit(state, amount);
        LOGGER.info("Exiting method doCreditPayment");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Lets a supervisor allow the account's ceiling to be passed (LC-07-09-04).
     *
     * @param login the supervisor's login
     * @param password the supervisor's password
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/credit-authorize")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response authorizeCreditOverLimit(@FormParam("login") String login,
            @FormParam("password") String password) {
        LOGGER.info("Entering method authorizeCreditOverLimit with login: " + login + ", password: ***");
        creditClientService.authorizeOverLimit(state, login, password);
        LOGGER.info("Exiting method authorizeCreditOverLimit");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Gives up on the settlement held back for authorization, keeping the account
     * named so a smaller amount can be typed (LC-07-09-04).
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/credit-authorize-cancel")
    public Response cancelCreditOverLimit() {
        LOGGER.info("Entering method cancelCreditOverLimit");
        creditClientService.cancelOverLimit(state);
        LOGGER.info("Exiting method cancelCreditOverLimit");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Lets a supervisor allow an administered tender bound to be passed for the
     * settlement held back (BO-03-02-10/12/13/14).
     *
     * @param login the supervisor's login
     * @param password the supervisor's password
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/tender-authorize")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response authorizeHeldTender(@FormParam("login") String login,
            @FormParam("password") String password) {
        LOGGER.info("Entering method authorizeHeldTender with login: " + login + ", password: ***");
        paymentService.authorizeHeldTender(state, login, password);
        LOGGER.info("Exiting method authorizeHeldTender");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Gives up on the settlement held back for a supervisor's authorization
     * (BO-03-02-10/12/13/14).
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/tender-authorize-cancel")
    public Response cancelHeldTender() {
        LOGGER.info("Entering method cancelHeldTender");
        paymentService.cancelHeldTender(state);
        LOGGER.info("Exiting method cancelHeldTender");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Starts a manual voucher entry for the selected type.
     * <p>
     * For a type carrying a number, the cashier is asked to type the number next;
     * for a numberless type, the amount is requested directly.
     *
     * @param code the technical code of the selected coupon type
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/voucher-select")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response selectVoucherType(@FormParam("code") String code) {
        LOGGER.info("Entering method selectVoucherType with code: " + code);
        CouponType type = CouponType.find("code = ?1 and active = true", code).firstResult();
        state.payment.clearPendingVoucher();
        state.payment.voucherPanelOpen = true;
        if (type != null) {
            state.payment.pendingVoucherTypeCode = type.code;
            state.payment.pendingVoucherLabel = type.label;
            if (!type.hasNumber()) {
                state.payment.pendingVoucherNeedsAmount = true;
            }
        }
        state.touch();
        LOGGER.info("Exiting method selectVoucherType");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Validates a manually typed voucher number against the selected type.
     * <p>
     * If the number does not match the type's pattern, an error is shown (likely a
     * typing mistake). If the amount is encoded, the payment is registered; otherwise
     * the amount is requested.
     *
     * @param number the voucher number typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/voucher-number")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response validateVoucherNumber(@FormParam("number") String number) {
        LOGGER.info("Entering method validateVoucherNumber with number: " + number);
        String code = state.payment.pendingVoucherTypeCode;
        CouponType type = (code != null)
                ? CouponType.find("code = ?1 and active = true", code).firstResult()
                : null;

        if (type == null) {
            state.payment.voucherError = i18n.get("op.typeBonInconnu");
            state.touch();
            LOGGER.info("Exiting method validateVoucherNumber");
            return Response.seeOther(URI.create("/pay")).build();
        }

        if (number == null || !type.matches(number)) {
            state.payment.voucherError = i18n.get("op.numeroNonReconnuVerifiez");
            state.touch();
            LOGGER.info("Exiting method validateVoucherNumber");
            return Response.seeOther(URI.create("/pay")).build();
        }

        state.payment.voucherError = null;
        state.payment.pendingVoucherNumber = number;

        if (type.requiresManualAmount()) {
            state.payment.pendingVoucherNeedsAmount = true;
            state.touch();
        } else {
            voucherService.applyEncodedVoucher(state, type, number);
            state.payment.clearPendingVoucher();
            state.touch();
        }
        LOGGER.info("Exiting method validateVoucherNumber");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers the pending voucher with the amount entered by the cashier.
     *
     * @param amountStr the amount typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/voucher-amount")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response validateVoucherAmount(@FormParam("amount") String amountStr) {
        LOGGER.info("Entering method validateVoucherAmount with amountStr: " + amountStr);
        String code = state.payment.pendingVoucherTypeCode;
        CouponType type = (code != null)
                ? CouponType.find("code = ?1 and active = true", code).firstResult()
                : null;
        BigDecimal amount = parseAmount(amountStr);
        if (!paymentAmountAllowed(amount)) {
            // The pending voucher is LEFT PENDING: the cashier re-keys a figure
            // the shop accepts rather than losing the voucher being entered.
            LOGGER.info("Exiting method validateVoucherAmount");
            return Response.seeOther(URI.create("/pay")).build();
        }
        voucherService.applyManualVoucher(state, type, state.payment.pendingVoucherNumber, amount);
        state.payment.clearPendingVoucher();
        state.touch();
        LOGGER.info("Exiting method validateVoucherAmount");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Cancels the in-progress voucher entry.
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/voucher-cancel")
    public Response cancelVoucher() {
        LOGGER.info("Entering method cancelVoucher");
        state.payment.clearPendingVoucher();
        state.touch();
        LOGGER.info("Exiting method cancelVoucher");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Moves the payment history to the previous page.
     *
     * @return a redirect back to the payment page
     */
    @GET
    @Path("/action/payments/prev")
    @DrawerMayBeOpen
    public Response paymentsPrev() {
        LOGGER.info("Entering method paymentsPrev");
        state.payment.prevPage();
        state.touch();
        LOGGER.info("Exiting method paymentsPrev");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Moves the payment history to the next page.
     *
     * @return a redirect back to the payment page
     */
    @GET
    @Path("/action/payments/next")
    @DrawerMayBeOpen
    public Response paymentsNext() {
        LOGGER.info("Entering method paymentsNext");
        state.payment.nextPage();
        state.touch();
        LOGGER.info("Exiting method paymentsNext");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Finalizes the transaction (closes the ticket) and redirects to the
     * main page.
     *
     * @return a 303 redirect to the main page — this GET mutates state, so
     *         it must never RENDER a page the browser then sits on: the sale
     *         screen reloads itself on a payability flip, and a reload of a
     *         rendered {@code /action/finish} would replay the fiscal close
     *         onto the NEXT freshly started draft (same replay class as the
     *         PRG-converted POSTs)
     */
    @GET
    @Path("/action/finish")
    // The drawer is OPEN at this exact moment on a cash sale, and closing the
    // sale is not "selling on": the guard must not block it, or the cashier
    // cannot finish and is sent back to the payment page once the drawer is
    // shut. The guard still stands on the home page this redirects to, so no
    // NEW sale starts with the drawer out.
    @DrawerMayBeOpen
    public Response validatePayment() {
        LOGGER.info("Entering method validatePayment");
        paymentService.finalizeTransaction(state);
        LOGGER.info("Exiting method validatePayment");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Cancels the registered payments (in memory and on the draft) and
     * redirects to the main page.
     *
     * @return a 303 redirect to the main page (same replay-safety rule as
     *         {@link #validatePayment()}: a mutating GET never renders the
     *         page it lands on)
     */
    @GET
    @Path("/action/cancel")
    public Response cancelPayment() {
        LOGGER.info("Entering method cancelPayment");
        // BO-10-02-02: abandoning settlements already taken is a watched
        // gesture when the shop asks for it — nothing, a warning, a refusal, or
        // a manager's aval, which parks the gesture and replays it on approval.
        com.intermarche.pos.ui.endorsement.ControlLevel level =
                endorsementService.check(state, com.intermarche.pos.ui.endorsement.ControlPoint.CANCEL_PAYMENT);
        if (level == com.intermarche.pos.ui.endorsement.ControlLevel.BLOCK) {
            state.ticket.setError(endorsementService.controlMessage(
                    com.intermarche.pos.ui.endorsement.ControlPoint.CANCEL_PAYMENT, level));
            LOGGER.info("Exiting method cancelPayment");
            return Response.seeOther(URI.create("/pay")).build();
        }
        if (level == com.intermarche.pos.ui.endorsement.ControlLevel.ENDORSE) {
            endorsementService.requestAuthorization(state, "CANCEL_PAYMENT");
            LOGGER.info("Exiting method cancelPayment");
            return Response.seeOther(URI.create("/pay")).build();
        }
        // BO-03-10-14: silenced by the island; the endorsement request and the
        // refusal, handled above, are not.
        if (level == com.intermarche.pos.ui.endorsement.ControlLevel.WARN
                && !posSettingsService.islandBlockPaymentPopups()) {
            state.ticket.setWarning(endorsementService.controlMessage(
                    com.intermarche.pos.ui.endorsement.ControlPoint.CANCEL_PAYMENT, level));
        }
        paymentService.cancelPayments(state);
        LOGGER.info("Exiting method cancelPayment");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Cancels a SINGLE registered payment without purging the others (EXP-63),
     * then stays on the payment screen so the operator keys the corrected tender.
     *
     * @param index the 0-based index of the payment to cancel
     * @return a 303 redirect to the payment screen (mutating GET, PRG-safe)
     */
    @GET
    @Path("/action/payment-cancel/{index}")
    public Response cancelOnePayment(@PathParam("index") int index) {
        LOGGER.info("Entering method cancelOnePayment with index: " + index);
        paymentService.cancelOnePayment(state, index);
        LOGGER.info("Exiting method cancelOnePayment");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Prints the current ticket and redirects to the appropriate page.
     *
     * @return a 303 redirect (PRG pattern, so a browser reload never replays
     *         the POST): to the payment page while the transaction modal is
     *         shown, otherwise to the main page
     */
    @POST
    @Path("/action/print")
    public Response printTicket() {
        LOGGER.info("Entering method printTicket");
        Long ticketId = state.payment.ticketDbId;
        if (state.trainingMode) {
            ticketPrinterService.printTrainingReceipt(state);
        } else if (ticketId != null) {
            try {
                ticketPrinterService.printTicket(ticketId);
            } catch (Exception e) {
                System.err.println("Erreur impression: " + e.getMessage());
            }
        }
        if (state.payment.transactionComplete) {
            LOGGER.info("Exiting method printTicket");
            return Response.seeOther(URI.create("/pay")).build();
        }
        LOGGER.info("Exiting method printTicket");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Applies the cashier's end-of-transaction printing choice (LC-08-03-01 to
     * LC-08-03-06) and returns to the completion modal, which then shows the
     * choice as applied.
     * <p>
     * In training nothing is persisted, so the choice can only produce the
     * in-memory training receipt: it is printed when the choice asks for the
     * sale ticket, and the choice is recorded so the buttons stop offering
     * themselves — exactly as on a real sale.
     *
     * @param choice the raw choice name posted by the modal
     * @return a 303 redirect to the payment page (PRG pattern, so a browser
     *         reload never prints a second time)
     */
    @POST
    @Path("/action/print-choice")
    public Response applyPrintChoice(@FormParam("choice") String choice) {
        LOGGER.info("Entering method applyPrintChoice with choice: " + choice);
        com.intermarche.pos.ui.hardware.PrintChoice picked =
                com.intermarche.pos.ui.hardware.PrintChoice.of(choice);
        if (state.trainingMode) {
            if (picked.isSaleTicket()) {
                ticketPrinterService.printTrainingReceipt(state);
            }
            state.payment.printChoice = picked;
            state.payment.printApplied = true;
        } else {
            paymentService.applyPrintChoice(state, picked);
        }
        LOGGER.info("Exiting method applyPrintChoice");
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Reprints the last closed ticket and returns to the home page.
     *
     * @return a redirect to the home page
     */
    @GET
    @Path("/action/reprint-last")
    public Response reprintLastTicket() {
        LOGGER.info("Entering method reprintLastTicket");
        if (state.lastClosedTicketId != null) {
            try {
                ticketPrinterService.printTicket(state.lastClosedTicketId);
            } catch (Exception e) {
                System.err.println("Erreur réimpression: " + e.getMessage());
            }
        }
        LOGGER.info("Exiting method reprintLastTicket");
        return Response.seeOther(URI.create("/")).build();
    }
    /**
     * Abandons the WHOLE ticket from the payment phase, without going back to the
     * article-entry screen first (LC-04-04-05).
     *
     * <p>It no longer abandons anything by itself: the shop's abandon rules — what a
     * settlement already taken forbids, which reason is given, what is printed
     * ({@code LC-04-04-06} to {@code -12}) — live on the abandon screen, and a second
     * road to the same gesture would be a road around those rules.
     *
     * @return a redirect to the abandon screen
     */
    @GET
    @Path("/action/pay/abandon-ticket")
    public Response abandonTicketFromPayment() {
        LOGGER.info("Entering method abandonTicketFromPayment");
        LOGGER.info("Exiting method abandonTicketFromPayment");
        return Response.seeOther(URI.create("/abandon")).build();
    }
}