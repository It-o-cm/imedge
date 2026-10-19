package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PaymentResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState} and its
 * {@link PaymentState} sub-state, a {@link PaymentService}, a
 * {@link VoucherService}, a {@link TicketPrinterService} and two Qute
 * {@link Template}s ({@code pay}, {@code main}). Every collaborator is a
 * Mockito mock: templates echo a recognizable {@link TemplateInstance} chain
 * so the returned view can be identified, {@code PosState} carries a mocked
 * {@code payment} holder whose delegating calls are verified and whose public
 * fields the tests drive and assert directly. The Panache static finders
 * {@code CouponType.find(...)}, {@code CouponType.listActivePaymentTypes()}
 * and {@code Ticket.findById(...)} resolve to {@link PanacheEntityBase} /
 * {@link CouponType} under plain {@code mvn test} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}. Tests assert absolute expected
 * values and verify delegation, covering both arms of every guard: the two
 * {@code digitalPath} guards (id null, ticket/key null compound), the four
 * {@code parseAmount} cases (null, blank, valid, invalid), the voucher-type
 * and voucher-number guards, the print training / draft / exception /
 * completion-modal branches and the reprint-last guard.
 */
class PaymentResourceTest {

    /**
     * Builds a {@link PaymentResource} whose collaborators are fresh mocks
     * wired onto its package-private fields, including the
     * {@link PosState#payment} sub-state holder so no direct field access hits
     * a null.
     *
     * @return a resource with fully mocked state, services and templates
     */
    private PaymentResource newResource() {
        PaymentResource resource = new PaymentResource();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        resource.i18n = TestMessages.french();
        resource.state = mock(PosState.class);
        resource.state.payment = mock(PaymentState.class);
        resource.paymentService = mock(PaymentService.class);
        // The control point watching the abandon of settlements already taken
        // (BO-10-02-02) answers as it does with nothing administered — no
        // control —, which is what every case of this class asserts.
        resource.endorsementService =
                mock(com.intermarche.pos.ui.endorsement.EndorsementService.class);
        when(resource.endorsementService.check(any(), any()))
                .thenReturn(com.intermarche.pos.ui.endorsement.ControlLevel.NONE);
        resource.voucherService = mock(VoucherService.class);
        resource.ticketPrinterService = mock(TicketPrinterService.class);
        // Every display of the payment screen offers the fidelity lease its
        // half-life renewal (imfid spec §5.1): the collaborator is asked on
        // EVERY GET /pay, so it belongs to the fixture, not to a single test.
        resource.fidelityService = mock(com.intermarche.pos.ui.fidelity.FidelityService.class);
        // The conditional-printing rule (LC-08-03): every display of the payment
        // screen asks it whether the choice buttons are offered.
        resource.printPolicy = mock(com.intermarche.pos.ui.hardware.PrintPolicy.class);
        // The payment screen also carries the foreign-currency list (LC-07-14) and
        // the backup-monetics endorsement rule (LC-07-07-09): both are asked on
        // EVERY GET /pay, so they belong to the fixture like the two above.
        resource.foreignCurrencyService = mock(ForeignCurrencyService.class);
        resource.creditClientService = mock(CreditClientService.class);
        resource.backupPaymentService = mock(BackupPaymentService.class);
        resource.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        // The administered tender rules (BO-03-02-03/23): every display of the
        // payment screen asks which tenders it offers and which of them pre-fill
        // the remaining due, so the collaborator belongs to the fixture.
        resource.tenderRulesService = mock(TenderRulesService.class);
        resource.pay = mock(Template.class);
        return resource;
    }

    /**
     * The data keys the payment page sets, in the order the resource sets them.
     *
     * <p>ONE declaration for the whole chain. The links used to be stubbed and
     * verified by hand-written index, so inserting one data call renumbered every
     * link and broke tests that were not about it. Both the stubbing and the lookup
     * below derive from this list, which is therefore the only thing to touch when
     * the page carries one more value.
     */
    private static final List<String> PAY_DATA_KEYS =
            List.of("offeredTenders", "tenderDefaults", "couponTypes", "currencies",
                    "backupEndorsement", "showCustomerDetails", "showCustomerDiscount",
                    "digitalPath", "printConditional", "printChoices");

    /**
     * Stubs the whole {@code pay} template chain, one link per data key, with
     * permissive matchers on every value but the state.
     *
     * @param resource the resource whose {@code pay} template is stubbed
     * @return the chained {@link TemplateInstance} mocks, the last of which is the
     *         final rendered view
     */
    private TemplateInstance[] stubPayChain(PaymentResource resource) {
        TemplateInstance[] chain = new TemplateInstance[PAY_DATA_KEYS.size() + 1];
        for (int i = 0; i < chain.length; i++) {
            chain[i] = mock(TemplateInstance.class);
        }
        when(resource.pay.data("state", resource.state)).thenReturn(chain[0]);
        for (int i = 0; i < PAY_DATA_KEYS.size(); i++) {
            when(chain[i].data(eq(PAY_DATA_KEYS.get(i)), any())).thenReturn(chain[i + 1]);
        }
        return chain;
    }

    /**
     * Returns the link a given data key is set ON, so a test can verify the value
     * without knowing where in the chain it falls.
     *
     * @param chain the chain returned by {@link #stubPayChain}
     * @param key one of {@link #PAY_DATA_KEYS}
     * @return the template instance the key is set on
     */
    private TemplateInstance linkSetting(TemplateInstance[] chain, String key) {
        return chain[PAY_DATA_KEYS.indexOf(key)];
    }

    /**
     * Returns the last link of a stubbed chain, which is the rendered view.
     *
     * @param chain the chain returned by {@link #stubPayChain}
     * @return the final template instance
     */
    private TemplateInstance renderedView(TemplateInstance[] chain) {
        return chain[chain.length - 1];
    }

    /**
     * Stubs {@code CouponType.find("code = ?1 and active = true", code)} on the
     * given Panache static mock to return a query yielding the given type.
     *
     * @param panache the Panache static mock
     * @param code the technical code searched for
     * @param type the type the query's {@code firstResult()} returns, or null
     */
    private void stubFind(MockedStatic<PanacheEntityBase> panache, String code, CouponType type) {
        @SuppressWarnings("unchecked")
        PanacheQuery<CouponType> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(type);
        panache.when(() -> CouponType.find("code = ?1 and active = true", code)).thenReturn(query);
    }

    /**
     * Asserts the given response is a 303 redirect to {@code /pay}.
     *
     * @param response the response under test
     */
    private void assertRedirectPay(Response response) {
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/pay", response.getLocation().toString());
    }

    // --- showPaymentPage / digitalPath ---

    /**
     * {@code showPaymentPage()} resets the numpad, initializes the payment and
     * renders the page with a null digital path when no draft id exists
     * ({@code ticketDbId == null}, first digitalPath guard true).
     */
    @Test
    void showPaymentPageRendersWithoutDraft() {
        PaymentResource resource = newResource();
        resource.state.payment.ticketDbId = null;
        TemplateInstance[] chain = stubPayChain(resource);
        try (MockedStatic<CouponType> coupon = mockStatic(CouponType.class)) {
            coupon.when(CouponType::listActivePaymentTypes).thenReturn(List.of());
            assertSame(renderedView(chain), resource.showPaymentPage());
        }
        verify(resource.paymentService).initPayment(resource.state);
        assertNull(resource.state.payment.inputMode);
        assertEquals("0,00", resource.state.payment.temporaryInput);
        verify(linkSetting(chain, "digitalPath")).data("digitalPath", null);
    }

    /**
     * {@code doNetRefund} defaults a blank tender to CASH and redirects to /pay
     * (EXP-38, blank/null arm of the method guard).
     */
    @Test
    void doNetRefundDefaultsToCash() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doNetRefund(null));
        verify(resource.paymentService).processNetRefund(resource.state, "CASH");
    }

    /**
     * {@code doNetRefund} trims a supplied tender and pays the net refund back in
     * it (EXP-38, non-blank arm).
     */
    @Test
    void doNetRefundTrimsGivenMethod() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doNetRefund("  CARD  "));
        verify(resource.paymentService).processNetRefund(resource.state, "CARD");
    }

    /**
     * {@code cancelOnePayment} delegates the single-payment cancel to the service
     * (EXP-63) and stays on the payment screen.
     */
    @Test
    void cancelOnePaymentDelegatesAndStaysOnPay() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.cancelOnePayment(1));
        verify(resource.paymentService).cancelOnePayment(resource.state, 1);
    }

    /**
     * {@code showPaymentPage()} yields a null digital path when the draft id
     * matches no ticket (first guard false, {@code ticket == null} true).
     */
    @Test
    void showPaymentPageNullDigitalPathWhenTicketMissing() {
        PaymentResource resource = newResource();
        resource.state.payment.ticketDbId = 5L;
        TemplateInstance[] chain = stubPayChain(resource);
        try (MockedStatic<CouponType> coupon = mockStatic(CouponType.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            coupon.when(CouponType::listActivePaymentTypes).thenReturn(List.of());
            panache.when(() -> Ticket.findById(5L)).thenReturn(null);
            assertSame(renderedView(chain), resource.showPaymentPage());
        }
        verify(linkSetting(chain, "digitalPath")).data("digitalPath", null);
    }

    /**
     * {@code showPaymentPage()} yields a null digital path when the ticket has
     * no digital key (first guard false, {@code ticket == null} false,
     * {@code digitalKey == null} true).
     */
    @Test
    void showPaymentPageNullDigitalPathWhenNoKey() {
        PaymentResource resource = newResource();
        resource.state.payment.ticketDbId = 5L;
        TemplateInstance[] chain = stubPayChain(resource);
        Ticket ticket = mock(Ticket.class);
        ticket.digitalKey = null;
        try (MockedStatic<CouponType> coupon = mockStatic(CouponType.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            coupon.when(CouponType::listActivePaymentTypes).thenReturn(List.of());
            panache.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            assertSame(renderedView(chain), resource.showPaymentPage());
        }
        verify(linkSetting(chain, "digitalPath")).data("digitalPath", null);
    }

    /**
     * {@code showPaymentPage()} builds the online receipt path when the ticket
     * carries a digital key (both digitalPath guards false).
     */
    @Test
    void showPaymentPageBuildsDigitalPath() {
        PaymentResource resource = newResource();
        resource.state.payment.ticketDbId = 5L;
        TemplateInstance[] chain = stubPayChain(resource);
        Ticket ticket = mock(Ticket.class);
        ticket.id = 42L;
        ticket.digitalKey = "abcdef";
        try (MockedStatic<CouponType> coupon = mockStatic(CouponType.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            coupon.when(CouponType::listActivePaymentTypes).thenReturn(List.of());
            panache.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            assertSame(renderedView(chain), resource.showPaymentPage());
        }
        verify(linkSetting(chain, "digitalPath")).data("digitalPath", "/t/42/abcdef");
    }

    // --- cancelPendingCard / toggleDonation ---

    /**
     * {@code cancelPendingCard()} delegates to the service and redirects to the
     * payment page.
     */
    @Test
    void cancelPendingCardDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.cancelPendingCard());
        verify(resource.paymentService).cancelPendingCard(resource.state);
    }

    /**
     * {@code cancelPendingCheque()} delegates to the service and redirects to
     * the payment page.
     */
    @Test
    void cancelPendingChequeDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.cancelPendingCheque());
        verify(resource.paymentService).cancelPendingCheque(resource.state);
    }

    /**
     * {@code toggleDonation()} delegates to the service and redirects to the
     * payment page.
     */
    @Test
    void toggleDonationDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.toggleDonation());
        verify(resource.paymentService).toggleDonationRoundup(resource.state);
    }

    // --- backup monetics (LC-07-07-06/09) ---

    /**
     * {@code openBackupPanel()} opens the backup-monetics panel through the
     * service and redirects to the payment page.
     */
    @Test
    void openBackupPanelDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.openBackupPanel());
        verify(resource.backupPaymentService).openPanel(resource.state);
    }

    /**
     * {@code closeBackupPanel()} closes the backup-monetics panel through the
     * service and redirects to the payment page.
     */
    @Test
    void closeBackupPanelDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.closeBackupPanel());
        verify(resource.backupPaymentService).closePanel(resource.state);
    }

    /**
     * {@code validateBackupScan()} hands the scanned payload to the service and
     * redirects to the payment page.
     */
    @Test
    void validateBackupScanDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.validateBackupScan("SCANNED"));
        verify(resource.backupPaymentService).validateScanned(resource.state, "SCANNED");
    }

    /**
     * {@code openBackupManualEntry()} switches the panel to manual keying,
     * clears any backup error, touches the state and redirects.
     */
    @Test
    void openBackupManualEntrySwitchesToKeying() {
        PaymentResource resource = newResource();
        resource.state.payment.backupError = "old error";
        assertRedirectPay(resource.openBackupManualEntry());
        assertTrue(resource.state.payment.backupManualEntry);
        assertNull(resource.state.payment.backupError);
        verify(resource.state).touch();
    }

    /**
     * {@code doBackupPayment()} parses the keyed amount and validates it
     * manually with the supervisor credentials, then redirects.
     */
    @Test
    void doBackupPaymentDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doBackupPayment("20,00", "mcurie", "1111"));
        verify(resource.backupPaymentService).validateManually(resource.state,
                new BigDecimal("20.00"), "mcurie", "1111");
    }

    // --- foreign currency (LC-07-14) ---

    /**
     * {@code openCurrencyPanel()} opens the foreign-currency panel through the
     * service and redirects to the payment page.
     */
    @Test
    void openCurrencyPanelDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.openCurrencyPanel());
        verify(resource.foreignCurrencyService).openPanel(resource.state);
    }

    /**
     * {@code closeCurrencyPanel()} closes the foreign-currency panel through the
     * service and redirects to the payment page.
     */
    @Test
    void closeCurrencyPanelDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.closeCurrencyPanel());
        verify(resource.foreignCurrencyService).closePanel(resource.state);
    }

    /**
     * {@code selectCurrency()} hands the selected ISO code to the service and
     * redirects to the payment page.
     */
    @Test
    void selectCurrencyDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.selectCurrency("USD"));
        verify(resource.foreignCurrencyService).selectCurrency(resource.state, "USD");
    }

    /**
     * {@code doCurrencyPayment()} parses the amount handed over in the currency
     * and processes it through the service, then redirects.
     */
    @Test
    void doCurrencyPaymentDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doCurrencyPayment("30,00"));
        verify(resource.foreignCurrencyService).processCurrency(resource.state,
                new BigDecimal("30.00"));
    }

    // --- customer credit (LC-07-09) ---

    /**
     * {@code openCreditPanel()} opens the customer-credit panel through the
     * service and redirects to the payment page.
     */
    @Test
    void openCreditPanelDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.openCreditPanel());
        verify(resource.creditClientService).openPanel(resource.state);
    }

    /**
     * {@code closeCreditPanel()} closes the customer-credit panel through the
     * service and redirects to the payment page.
     */
    @Test
    void closeCreditPanelDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.closeCreditPanel());
        verify(resource.creditClientService).closePanel(resource.state);
    }

    /**
     * {@code selectCreditAccountByNumber()} names the account by its number
     * through the service and redirects to the payment page.
     */
    @Test
    void selectCreditAccountByNumberDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.selectCreditAccountByNumber("00123"));
        verify(resource.creditClientService).selectByNumber(resource.state, "00123");
    }

    /**
     * {@code searchCreditAccounts()} looks accounts up by name fragment through
     * the service and redirects to the payment page.
     */
    @Test
    void searchCreditAccountsDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.searchCreditAccounts("Dupont"));
        verify(resource.creditClientService).searchByName(resource.state, "Dupont");
    }

    /**
     * {@code doCreditPayment()} parses the amount and charges the named account
     * through the service, then redirects.
     */
    @Test
    void doCreditPaymentDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doCreditPayment("15,00"));
        verify(resource.creditClientService).processCredit(resource.state,
                new BigDecimal("15.00"));
    }

    /**
     * {@code authorizeCreditOverLimit()} passes the supervisor credentials to
     * the service to allow the ceiling to be passed, then redirects.
     */
    @Test
    void authorizeCreditOverLimitDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.authorizeCreditOverLimit("mcurie", "1111"));
        verify(resource.creditClientService).authorizeOverLimit(resource.state, "mcurie", "1111");
    }

    /**
     * {@code cancelCreditOverLimit()} gives up on the held-back settlement
     * through the service and redirects to the payment page.
     */
    @Test
    void cancelCreditOverLimitDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.cancelCreditOverLimit());
        verify(resource.creditClientService).cancelOverLimit(resource.state);
    }

    // --- Administered tender bound passed by a supervisor (BO-03-02-10) ---

    /**
     * {@code authorizeHeldTender()} passes the supervisor credentials to the
     * payment service to release the held settlement, then redirects.
     */
    @Test
    void authorizeHeldTenderDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.authorizeHeldTender("mcurie", "1111"));
        verify(resource.paymentService).authorizeHeldTender(resource.state, "mcurie", "1111");
    }

    /**
     * {@code cancelHeldTender()} gives up on the held settlement through the
     * payment service and redirects to the payment page.
     */
    @Test
    void cancelHeldTenderDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.cancelHeldTender());
        verify(resource.paymentService).cancelHeldTender(resource.state);
    }

    // --- selectCreditAccount (LC-07-09-08) ---

    /**
     * {@code selectCreditAccount()} passes a null id when no customer is posted
     * and redirects (ternary {@code customerId == null} true arm).
     */
    @Test
    void selectCreditAccountNullIdSelectsNull() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.selectCreditAccount(null));
        verify(resource.creditClientService).selectById(resource.state, null);
    }

    /**
     * {@code selectCreditAccount()} parses a numeric id (trimmed) and names the
     * account (ternary {@code customerId == null} false arm, valid number).
     */
    @Test
    void selectCreditAccountValidIdSelectsById() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.selectCreditAccount("  42 "));
        verify(resource.creditClientService).selectById(resource.state, 42L);
    }

    /**
     * {@code selectCreditAccount()} falls back to a null id on an unparsable
     * value ({@code NumberFormatException} catch arm).
     */
    @Test
    void selectCreditAccountInvalidIdSelectsNull() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.selectCreditAccount("abc"));
        verify(resource.creditClientService).selectById(resource.state, null);
    }

    // --- payment methods / parseAmount ---

    /**
     * {@code doCardPayment()} parses a valid French-comma amount and registers
     * the card payment (parseAmount: non-null, non-blank, valid number).
     */
    @Test
    void doCardPaymentParsesValidAmount() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doCardPayment("10,50"));
        verify(resource.paymentService).processCard(resource.state, new BigDecimal("10.50"));
    }

    /**
     * {@code doCashPayment()} treats a null amount as zero (parseAmount:
     * {@code value == null} true arm).
     */
    @Test
    void doCashPaymentTreatsNullAsZero() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doCashPayment(null));
        verify(resource.paymentService).processCash(resource.state, BigDecimal.ZERO);
    }

    /**
     * {@code doTrPayment()} treats a blank amount as zero (parseAmount:
     * non-null, {@code isBlank()} true arm).
     */
    @Test
    void doTrPaymentTreatsBlankAsZero() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doTrPayment("   "));
        verify(resource.paymentService).processTicketResto(resource.state, BigDecimal.ZERO);
    }

    /**
     * {@code doFidelityPayment()} treats an unparsable amount as zero
     * (parseAmount: non-null, non-blank, {@code NumberFormatException} catch
     * arm).
     */
    @Test
    void doFidelityPaymentTreatsInvalidAsZero() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doFidelityPayment("abc"));
        verify(resource.paymentService).processFidelity(resource.state, BigDecimal.ZERO);
    }

    /**
     * {@code doChequePayment()} parses a plain integer amount and registers the
     * cheque payment.
     */
    @Test
    void doChequePaymentParsesAmount() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doChequePayment("5"));
        verify(resource.paymentService).processCheque(resource.state, new BigDecimal("5"));
    }

    // --- openVoucherPanel ---

    /**
     * {@code openVoucherPanel()} clears any pending voucher, opens the panel,
     * touches the state and redirects.
     */
    @Test
    void openVoucherPanelClearsAndOpens() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.openVoucherPanel());
        verify(resource.state.payment).clearPendingVoucher();
        assertTrue(resource.state.payment.voucherPanelOpen);
        verify(resource.state).touch();
    }

    // --- selectVoucherType ---

    /**
     * {@code selectVoucherType()} opens the panel but sets no pending type when
     * the code matches no active type ({@code type == null} arm).
     */
    @Test
    void selectVoucherTypeUnknownCodeSetsNothing() {
        PaymentResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "X", null);
            assertRedirectPay(resource.selectVoucherType("X"));
        }
        verify(resource.state.payment).clearPendingVoucher();
        assertTrue(resource.state.payment.voucherPanelOpen);
        assertNull(resource.state.payment.pendingVoucherTypeCode);
        verify(resource.state).touch();
    }

    /**
     * {@code selectVoucherType()} records the type and does NOT request an
     * amount for a numbered type ({@code type != null}, {@code hasNumber()}
     * true so {@code !hasNumber()} false).
     */
    @Test
    void selectVoucherTypeNumberedSetsType() {
        PaymentResource resource = newResource();
        CouponType type = mock(CouponType.class);
        type.code = "GIFT";
        type.label = "Chèque cadeau";
        when(type.hasNumber()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", type);
            assertRedirectPay(resource.selectVoucherType("GIFT"));
        }
        assertEquals("GIFT", resource.state.payment.pendingVoucherTypeCode);
        assertEquals("Chèque cadeau", resource.state.payment.pendingVoucherLabel);
        assertFalse(resource.state.payment.pendingVoucherNeedsAmount);
        verify(resource.state).touch();
    }

    /**
     * {@code selectVoucherType()} requests the amount directly for a numberless
     * type ({@code type != null}, {@code hasNumber()} false so
     * {@code !hasNumber()} true).
     */
    @Test
    void selectVoucherTypeNumberlessRequestsAmount() {
        PaymentResource resource = newResource();
        CouponType type = mock(CouponType.class);
        type.code = "CATALINA";
        type.label = "Coupon";
        when(type.hasNumber()).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "CATALINA", type);
            assertRedirectPay(resource.selectVoucherType("CATALINA"));
        }
        assertEquals("CATALINA", resource.state.payment.pendingVoucherTypeCode);
        assertTrue(resource.state.payment.pendingVoucherNeedsAmount);
        verify(resource.state).touch();
    }

    // --- validateVoucherNumber ---

    /**
     * {@code validateVoucherNumber()} rejects entry with a type error when no
     * type is pending (ternary {@code code != null} false, {@code type == null}
     * true).
     */
    @Test
    void validateVoucherNumberNoPendingCode() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = null;
        assertRedirectPay(resource.validateVoucherNumber("123"));
        assertEquals("Type de bon inconnu", resource.state.payment.voucherError);
        verify(resource.state).touch();
        verifyNoInteractions(resource.voucherService);
    }

    /**
     * {@code validateVoucherNumber()} rejects entry with a type error when the
     * pending code no longer resolves (ternary {@code code != null} true,
     * {@code type == null} true).
     */
    @Test
    void validateVoucherNumberTypeGone() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = "GIFT";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", null);
            assertRedirectPay(resource.validateVoucherNumber("123"));
        }
        assertEquals("Type de bon inconnu", resource.state.payment.voucherError);
        verify(resource.state).touch();
    }

    /**
     * {@code validateVoucherNumber()} rejects a null number with a
     * not-recognized error ({@code type != null}, {@code number == null} true
     * arm short-circuits the compound guard).
     */
    @Test
    void validateVoucherNumberNullNumber() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = "GIFT";
        CouponType type = mock(CouponType.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", type);
            assertRedirectPay(resource.validateVoucherNumber(null));
        }
        assertEquals("Numéro non reconnu — vérifiez la saisie", resource.state.payment.voucherError);
        verify(resource.state).touch();
        verify(type, never()).matches(any());
    }

    /**
     * {@code validateVoucherNumber()} rejects a non-matching number with a
     * not-recognized error ({@code number != null}, {@code !matches} true).
     */
    @Test
    void validateVoucherNumberNotMatching() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = "GIFT";
        CouponType type = mock(CouponType.class);
        when(type.matches("bad")).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", type);
            assertRedirectPay(resource.validateVoucherNumber("bad"));
        }
        assertEquals("Numéro non reconnu — vérifiez la saisie", resource.state.payment.voucherError);
        verify(resource.state).touch();
    }

    /**
     * {@code validateVoucherNumber()} accepts a matching number and requests
     * the amount when the type needs a manual amount ({@code !matches} false,
     * {@code requiresManualAmount()} true).
     */
    @Test
    void validateVoucherNumberMatchingNeedsAmount() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = "GIFT";
        CouponType type = mock(CouponType.class);
        when(type.matches("good")).thenReturn(true);
        when(type.requiresManualAmount()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", type);
            assertRedirectPay(resource.validateVoucherNumber("good"));
        }
        assertNull(resource.state.payment.voucherError);
        assertEquals("good", resource.state.payment.pendingVoucherNumber);
        assertTrue(resource.state.payment.pendingVoucherNeedsAmount);
        verify(resource.state).touch();
        verifyNoInteractions(resource.voucherService);
    }

    /**
     * {@code validateVoucherNumber()} applies an encoded voucher immediately
     * and clears the entry when no manual amount is needed ({@code !matches}
     * false, {@code requiresManualAmount()} false).
     */
    @Test
    void validateVoucherNumberMatchingEncoded() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = "GIFT";
        CouponType type = mock(CouponType.class);
        when(type.matches("good")).thenReturn(true);
        when(type.requiresManualAmount()).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", type);
            assertRedirectPay(resource.validateVoucherNumber("good"));
        }
        assertEquals("good", resource.state.payment.pendingVoucherNumber);
        verify(resource.voucherService).applyEncodedVoucher(resource.state, type, "good");
        verify(resource.state.payment).clearPendingVoucher();
        verify(resource.state).touch();
    }

    // --- the PAYMENT AMOUNT control point (BO-10-02-17) ---

    /**
     * Arms the settlement ceiling and the level the control answers, and gives
     * the state the ticket the control speaks through.
     *
     * @param resource the resource under test
     * @param ceiling the administered ceiling
     * @param level what the control point answers here and now
     */
    private void armPaymentCeiling(PaymentResource resource, String ceiling,
            com.intermarche.pos.ui.endorsement.ControlLevel level) {
        resource.state.ticket = mock(com.intermarche.pos.ui.ticket.TicketState.class);
        when(resource.posSettingsService.controlPaymentAmountMax())
                .thenReturn(new BigDecimal(ceiling));
        when(resource.endorsementService.check(resource.state,
                com.intermarche.pos.ui.endorsement.ControlPoint.PAYMENT_AMOUNT)).thenReturn(level);
        when(resource.endorsementService.controlMessage(
                com.intermarche.pos.ui.endorsement.ControlPoint.PAYMENT_AMOUNT, level))
                .thenReturn("MONTANT D'UN RÈGLEMENT");
    }

    /**
     * BO-10-02-17, no ceiling administered: the control is never reached and
     * the settlement is registered. This is the catalogue's default.
     */
    @Test
    void aKeyedAmountIsUncontrolledWhenNoCeilingIsAdministered() {
        PaymentResource resource = newResource();
        when(resource.posSettingsService.controlPaymentAmountMax()).thenReturn(null);
        assertRedirectPay(resource.doCardPayment("900,00"));
        verify(resource.paymentService).processCard(resource.state, new BigDecimal("900.00"));
        verify(resource.endorsementService, never()).check(any(),
                eq(com.intermarche.pos.ui.endorsement.ControlPoint.PAYMENT_AMOUNT));
    }

    /**
     * BO-10-02-17, the figure AT the ceiling: the comparison is "beyond", so
     * the ceiling itself settles — the boundary arm.
     */
    @Test
    void aKeyedAmountAtTheCeilingSettles() {
        PaymentResource resource = newResource();
        armPaymentCeiling(resource, "500.00", com.intermarche.pos.ui.endorsement.ControlLevel.BLOCK);
        assertRedirectPay(resource.doCardPayment("500,00"));
        verify(resource.paymentService).processCard(resource.state, new BigDecimal("500.00"));
        verify(resource.endorsementService, never()).check(any(),
                eq(com.intermarche.pos.ui.endorsement.ControlPoint.PAYMENT_AMOUNT));
    }

    /**
     * BO-10-02-17, beyond the ceiling at level NONE: the point is reached and
     * lets the settlement through, saying nothing.
     */
    @Test
    void aKeyedAmountBeyondTheCeilingSettlesAtLevelNone() {
        PaymentResource resource = newResource();
        armPaymentCeiling(resource, "500.00", com.intermarche.pos.ui.endorsement.ControlLevel.NONE);
        assertRedirectPay(resource.doCardPayment("900,00"));
        verify(resource.paymentService).processCard(resource.state, new BigDecimal("900.00"));
        verify(resource.state.ticket, never()).setWarning(any());
        verify(resource.state.ticket, never()).setError(any());
    }

    /**
     * BO-10-02-17, WARN: the cashier is told and the settlement is registered
     * all the same — the non-blocking alert.
     */
    @Test
    void aKeyedAmountBeyondTheCeilingWarnsAndSettles() {
        PaymentResource resource = newResource();
        armPaymentCeiling(resource, "500.00", com.intermarche.pos.ui.endorsement.ControlLevel.WARN);
        assertRedirectPay(resource.doCashPayment("900,00"));
        verify(resource.state.ticket).setWarning("MONTANT D'UN RÈGLEMENT");
        verify(resource.paymentService).processCash(resource.state, new BigDecimal("900.00"));
    }

    /**
     * BO-03-10-14: an island that blocks the payment pop-ups silences the
     * non-blocking alert — and the settlement still goes through, because the
     * message was informative and nothing else changed.
     */
    @Test
    void anIslandBlockingPaymentPopupsSilencesTheAlert() {
        PaymentResource resource = newResource();
        armPaymentCeiling(resource, "500.00", com.intermarche.pos.ui.endorsement.ControlLevel.WARN);
        when(resource.posSettingsService.islandBlockPaymentPopups()).thenReturn(true);
        assertRedirectPay(resource.doCashPayment("900,00"));
        verify(resource.state.ticket, never()).setWarning(any());
        verify(resource.paymentService).processCash(resource.state, new BigDecimal("900.00"));
    }

    /**
     * BO-03-10-14, the other leg: an island that blocks the pop-ups does NOT
     * silence a REFUSAL — a settlement the store forbids stays forbidden and
     * says so.
     */
    @Test
    void anIslandBlockingPaymentPopupsStillRefuses() {
        PaymentResource resource = newResource();
        armPaymentCeiling(resource, "500.00", com.intermarche.pos.ui.endorsement.ControlLevel.BLOCK);
        when(resource.posSettingsService.islandBlockPaymentPopups()).thenReturn(true);
        assertRedirectPay(resource.doCardPayment("900,00"));
        verify(resource.state.ticket).setError("MONTANT D'UN RÈGLEMENT");
    }

    /**
     * BO-10-02-17, BLOCK: the settlement is refused, the tender service is
     * never called and the screen carries the refusal.
     */
    @Test
    void aKeyedAmountBeyondTheCeilingIsRefusedAtLevelBlock() {
        PaymentResource resource = newResource();
        armPaymentCeiling(resource, "500.00", com.intermarche.pos.ui.endorsement.ControlLevel.BLOCK);
        assertRedirectPay(resource.doCardPayment("900,00"));
        verify(resource.state.ticket).setError("MONTANT D'UN RÈGLEMENT");
        verify(resource.paymentService, never()).processCard(any(), any());
    }

    /**
     * BO-10-02-17, ENDORSE administered on a point no ceremony can serve: the
     * level that reaches this screen refuses, because the payment screen has no
     * modal to park a settlement in.
     */
    @Test
    void aKeyedAmountBeyondTheCeilingIsRefusedAtLevelEndorse() {
        PaymentResource resource = newResource();
        armPaymentCeiling(resource, "500.00", com.intermarche.pos.ui.endorsement.ControlLevel.ENDORSE);
        assertRedirectPay(resource.doChequePayment("900,00"));
        verify(resource.state.ticket).setError("MONTANT D'UN RÈGLEMENT");
        verify(resource.paymentService, never()).processCheque(any(), any());
    }

    /**
     * BO-10-02-17, A REFUND IS PLAFONNED LIKE A SETTLEMENT: a negative figure
     * beyond the ceiling in absolute value is controlled too.
     */
    @Test
    void aNegativeKeyedAmountIsComparedInAbsoluteValue() {
        PaymentResource resource = newResource();
        armPaymentCeiling(resource, "500.00", com.intermarche.pos.ui.endorsement.ControlLevel.BLOCK);
        assertRedirectPay(resource.doCashPayment("-900,00"));
        verify(resource.state.ticket).setError("MONTANT D'UN RÈGLEMENT");
        verify(resource.paymentService, never()).processCash(any(), any());
    }

    /**
     * BO-10-02-17 on the voucher panel: a refused amount LEAVES THE VOUCHER
     * PENDING, so the cashier re-keys a figure the shop accepts instead of
     * losing the voucher being entered.
     */
    @Test
    void aRefusedVoucherAmountLeavesTheVoucherPending() {
        PaymentResource resource = newResource();
        armPaymentCeiling(resource, "500.00", com.intermarche.pos.ui.endorsement.ControlLevel.BLOCK);
        resource.state.payment.pendingVoucherTypeCode = null;
        resource.state.payment.pendingVoucherNumber = "N1";
        assertRedirectPay(resource.validateVoucherAmount("900,00"));
        verify(resource.voucherService, never()).applyManualVoucher(any(), any(), any(), any());
        verify(resource.state.payment, never()).clearPendingVoucher();
        verify(resource.state.ticket).setError("MONTANT D'UN RÈGLEMENT");
    }

    // --- validateVoucherAmount ---

    /**
     * {@code validateVoucherAmount()} resolves the pending type and applies the
     * manual voucher with the parsed amount (ternary {@code code != null}
     * true).
     */
    @Test
    void validateVoucherAmountWithType() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = "GIFT";
        resource.state.payment.pendingVoucherNumber = "N1";
        CouponType type = mock(CouponType.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", type);
            assertRedirectPay(resource.validateVoucherAmount("12,00"));
        }
        verify(resource.voucherService).applyManualVoucher(resource.state, type, "N1", new BigDecimal("12.00"));
        verify(resource.state.payment).clearPendingVoucher();
        verify(resource.state).touch();
    }

    /**
     * {@code validateVoucherAmount()} passes a null type when no code is
     * pending (ternary {@code code != null} false).
     */
    @Test
    void validateVoucherAmountNoType() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = null;
        resource.state.payment.pendingVoucherNumber = "N1";
        assertRedirectPay(resource.validateVoucherAmount("12,00"));
        verify(resource.voucherService).applyManualVoucher(resource.state, null, "N1", new BigDecimal("12.00"));
        verify(resource.state.payment).clearPendingVoucher();
        verify(resource.state).touch();
    }

    // --- cancelVoucher / pagination ---

    /**
     * {@code cancelVoucher()} clears the pending voucher, touches the state and
     * redirects.
     */
    @Test
    void cancelVoucherClearsAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.cancelVoucher());
        verify(resource.state.payment).clearPendingVoucher();
        verify(resource.state).touch();
    }

    /**
     * {@code paymentsPrev()} pages the history back, touches the state and
     * redirects.
     */
    @Test
    void paymentsPrevPagesBack() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.paymentsPrev());
        verify(resource.state.payment).prevPage();
        verify(resource.state).touch();
    }

    /**
     * {@code paymentsNext()} pages the history forward, touches the state and
     * redirects.
     */
    @Test
    void paymentsNextPagesForward() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.paymentsNext());
        verify(resource.state.payment).nextPage();
        verify(resource.state).touch();
    }

    // --- validatePayment / cancelPayment ---

    /**
     * {@code validatePayment()} finalizes the transaction and redirects to
     * the main page (replay-safe mutating GET: a reload of the landed URL
     * must never replay the fiscal close).
     */
    @Test
    void validatePaymentFinalizesAndRedirectsHome() {
        PaymentResource resource = newResource();
        Response response = resource.validatePayment();
        verify(resource.paymentService).finalizeTransaction(resource.state);
        assertEquals(303, response.getStatus());
        assertEquals("/", response.getLocation().toString());
    }

    /**
     * The finish action opts OUT of the drawer guard, and that opt-out is the
     * behaviour, not a detail: on a cash sale the drawer is open at that exact
     * moment, and without it the guard blocks the close and sends the cashier
     * back to the payment page once the drawer is shut.
     */
    @Test
    void validatePaymentIsAllowedWithTheDrawerOpen() throws NoSuchMethodException {
        assertTrue(PaymentResource.class.getMethod("validatePayment")
                .isAnnotationPresent(com.intermarche.pos.ui.DrawerMayBeOpen.class));
    }

    /**
     * The cancel action, by contrast, stays under the guard: it goes back to
     * the sale screen, which is selling on.
     */
    @Test
    void cancelPaymentStaysUnderTheDrawerGuard() throws NoSuchMethodException {
        assertFalse(PaymentResource.class.getMethod("cancelPayment")
                .isAnnotationPresent(com.intermarche.pos.ui.DrawerMayBeOpen.class));
    }

    /**
     * {@code cancelPayment()} cancels the registered payments and redirects
     * to the main page (same replay-safety rule as the finish action).
     */
    @Test
    void cancelPaymentCancelsAndRedirectsHome() {
        PaymentResource resource = newResource();
        Response response = resource.cancelPayment();
        verify(resource.paymentService).cancelPayments(resource.state);
        assertEquals(303, response.getStatus());
        assertEquals("/", response.getLocation().toString());
    }

    // --- printTicket ---

    /**
     * {@code printTicket()} prints the in-memory training receipt in training
     * mode and redirects to the main page when the modal is not shown
     * ({@code trainingMode} true, {@code transactionComplete} false; PRG
     * pattern).
     */
    @Test
    void printTicketTrainingRedirectsHome() {
        PaymentResource resource = newResource();
        resource.state.trainingMode = true;
        resource.state.payment.ticketDbId = 9L;
        resource.state.payment.transactionComplete = false;
        Response response = resource.printTicket();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketPrinterService).printTrainingReceipt(resource.state);
        verify(resource.ticketPrinterService, never()).printTicket(any());
    }

    /**
     * {@code printTicket()} prints the draft by id and, when the completion
     * modal is shown, redirects to the payment page ({@code trainingMode}
     * false, {@code ticketId != null}, no exception,
     * {@code transactionComplete} true; PRG pattern).
     */
    @Test
    void printTicketDraftRedirectsToPayWhenComplete() {
        PaymentResource resource = newResource();
        resource.state.trainingMode = false;
        resource.state.payment.ticketDbId = 9L;
        resource.state.payment.transactionComplete = true;
        Response response = resource.printTicket();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/pay", response.getLocation().toString());
        verify(resource.ticketPrinterService).printTicket(9L);
        verify(resource.ticketPrinterService, never()).printTrainingReceipt(any());
    }

    /**
     * {@code printTicket()} swallows a printing failure and redirects to the
     * main page ({@code trainingMode} false, {@code ticketId != null},
     * printer throws, {@code transactionComplete} false; PRG pattern).
     */
    @Test
    void printTicketSwallowsPrintFailure() {
        PaymentResource resource = newResource();
        resource.state.trainingMode = false;
        resource.state.payment.ticketDbId = 9L;
        resource.state.payment.transactionComplete = false;
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(resource.ticketPrinterService).printTicket(9L);
        Response response = resource.printTicket();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketPrinterService).printTicket(9L);
    }

    /**
     * {@code printTicket()} prints nothing when not training and no draft
     * exists, redirecting to the main page ({@code trainingMode} false,
     * {@code ticketId != null} false, {@code transactionComplete} false; PRG
     * pattern).
     */
    @Test
    void printTicketNoDraftRedirectsHome() {
        PaymentResource resource = newResource();
        resource.state.trainingMode = false;
        resource.state.payment.ticketDbId = null;
        resource.state.payment.transactionComplete = false;
        Response response = resource.printTicket();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketPrinterService, never()).printTicket(any());
        verify(resource.ticketPrinterService, never()).printTrainingReceipt(any());
    }

    // --- applyPrintChoice ---

    /**
     * {@code applyPrintChoice()} hands the parsed choice to the payment
     * service outside training and redirects to the payment page, where the
     * modal then shows the choice as applied ({@code trainingMode} false; PRG
     * pattern).
     */
    @Test
    void applyPrintChoiceDelegatesOutsideTraining() {
        PaymentResource resource = newResource();
        resource.state.trainingMode = false;
        Response response = resource.applyPrintChoice("SALE_TICKET");
        assertRedirectPay(response);
        verify(resource.paymentService).applyPrintChoice(resource.state,
                com.intermarche.pos.ui.hardware.PrintChoice.SALE_TICKET);
        verify(resource.ticketPrinterService, never()).printTrainingReceipt(any());
    }

    /**
     * {@code applyPrintChoice()} prints the in-memory training receipt when the
     * choice asks for the sale ticket in training, and records the choice
     * itself ({@code trainingMode} true, sale-ticket leg true).
     */
    @Test
    void applyPrintChoiceTrainingPrintsTheTrainingReceipt() {
        PaymentResource resource = newResource();
        resource.state.trainingMode = true;
        Response response = resource.applyPrintChoice("ALL");
        assertRedirectPay(response);
        verify(resource.ticketPrinterService).printTrainingReceipt(resource.state);
        verify(resource.paymentService, never()).applyPrintChoice(any(), any());
        assertEquals(com.intermarche.pos.ui.hardware.PrintChoice.ALL,
                resource.state.payment.printChoice);
        assertTrue(resource.state.payment.printApplied);
    }

    /**
     * {@code applyPrintChoice()} prints nothing in training when the choice
     * excludes the sale ticket, but still records it so the buttons stop
     * offering themselves ({@code trainingMode} true, sale-ticket leg false).
     */
    @Test
    void applyPrintChoiceTrainingPrintsNothingWithoutTheSaleTicket() {
        PaymentResource resource = newResource();
        resource.state.trainingMode = true;
        Response response = resource.applyPrintChoice("NONE");
        assertRedirectPay(response);
        verify(resource.ticketPrinterService, never()).printTrainingReceipt(any());
        assertEquals(com.intermarche.pos.ui.hardware.PrintChoice.NONE,
                resource.state.payment.printChoice);
        assertTrue(resource.state.payment.printApplied);
    }

    /**
     * {@code applyPrintChoice()} falls back to "tous les tickets" on an
     * unreadable posted value: a lost form value never suppresses a document.
     */
    @Test
    void applyPrintChoiceFallsBackToAllOnAnUnknownValue() {
        PaymentResource resource = newResource();
        resource.state.trainingMode = false;
        Response response = resource.applyPrintChoice(null);
        assertRedirectPay(response);
        verify(resource.paymentService).applyPrintChoice(resource.state,
                com.intermarche.pos.ui.hardware.PrintChoice.ALL);
    }

    // --- reprintLastTicket ---

    /**
     * {@code reprintLastTicket()} reprints the last closed ticket and redirects
     * home ({@code lastClosedTicketId != null}, no exception).
     */
    @Test
    void reprintLastPrintsAndRedirectsHome() {
        PaymentResource resource = newResource();
        resource.state.lastClosedTicketId = 7L;
        Response response = resource.reprintLastTicket();
        verify(resource.ticketPrinterService).printTicket(7L);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
    }

    /**
     * {@code reprintLastTicket()} swallows a reprint failure and still
     * redirects home ({@code lastClosedTicketId != null}, printer throws).
     */
    @Test
    void reprintLastSwallowsFailure() {
        PaymentResource resource = newResource();
        resource.state.lastClosedTicketId = 7L;
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(resource.ticketPrinterService).printTicket(7L);
        Response response = resource.reprintLastTicket();
        verify(resource.ticketPrinterService).printTicket(7L);
        assertEquals("/", response.getLocation().toString());
    }

    /**
     * {@code reprintLastTicket()} prints nothing and redirects home when there
     * is no last closed ticket ({@code lastClosedTicketId != null} false).
     */
    @Test
    void reprintLastNoTicketRedirectsHome() {
        PaymentResource resource = newResource();
        resource.state.lastClosedTicketId = null;
        Response response = resource.reprintLastTicket();
        verify(resource.ticketPrinterService, never()).printTicket(any());
        assertEquals("/", response.getLocation().toString());
    }

    /**
     * {@code abandonTicketFromPayment} sends the operator to the ABANDON SCREEN and
     * abandons nothing by itself ({@code LC-04-04-06} to {@code -12}).
     *
     * <p>The screen is where the shop's rules live: the reason chosen from the
     * administered list, the settlements already taken named before they are undone,
     * the abandon ticket printed or not. Undoing them here would apply none of the
     * three, and would leave a second abandon path to keep in step with the first —
     * so this key touches no service at all.
     */
    @Test
    void abandonTicketFromPaymentSendsTheOperatorToTheAbandonScreen() {
        PaymentResource resource = newResource();
        resource.ticketService = mock(com.intermarche.pos.ui.ticket.TicketService.class);
        Response response = resource.abandonTicketFromPayment();
        assertEquals(303, response.getStatus());
        assertEquals("/abandon", response.getLocation().toString());
        verifyNoInteractions(resource.ticketService);
        verify(resource.paymentService, never()).cancelPayments(resource.state);
    }

    // --- Step 6 tenders (EXP-58/60/66/67) ---

    /**
     * {@code doTrdPayment} parses the amount (French comma) and delegates to the
     * TRD-card payment, then redirects to the pay screen (EXP-60).
     */
    @Test
    void doTrdPaymentDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doTrdPayment("10,00"));
        verify(resource.paymentService).processTrdCard(resource.state, new BigDecimal("10.00"));
    }

    /**
     * {@code doRestoFlashPayment} delegates with the token and parsed amount,
     * then redirects to the pay screen (EXP-67).
     */
    @Test
    void doRestoFlashPaymentDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doRestoFlashPayment("TOK", "12,50"));
        verify(resource.paymentService).processRestoFlash(resource.state, "TOK", new BigDecimal("12.50"));
    }

    /**
     * {@code doGiftCardPayment} delegates with the card number and parsed amount,
     * then redirects to the pay screen (EXP-66).
     */
    @Test
    void doGiftCardPaymentDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doGiftCardPayment("GC1", "20,00"));
        verify(resource.paymentService).processSupplierGiftCard(resource.state, "GC1", new BigDecimal("20.00"));
    }

    /**
     * {@code doSogecPayment} delegates with the coupon code and parsed amount,
     * then redirects to the pay screen (EXP-58).
     */
    @Test
    void doSogecPaymentDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doSogecPayment("SG1", "5,00"));
        verify(resource.paymentService).processSogec(resource.state, "SG1", new BigDecimal("5.00"));
    }
}
