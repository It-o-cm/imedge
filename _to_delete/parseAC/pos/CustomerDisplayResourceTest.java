package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerDisplayResource}, the read-only customer-facing
 * second screen. Both endpoints are thin projections of {@link PosState}:
 * {@code customerPage()} renders a template and {@code customerData()} builds a
 * version-gated JSON snapshot delegating to the private {@code digitalPath()}
 * and {@code quantityDisplay()} helpers. All collaborators are mocked; the
 * {@code Ticket.findById} static finder resolves to {@link PanacheEntityBase}
 * under plain {@code mvn test} and is intercepted with
 * {@link org.mockito.Mockito#mockStatic}. Tests cover the version gate (both
 * arms and the null client version), the change-amount ternary, the empty and
 * populated item loop, the three digital-path guards and the three quantity
 * display formats (20 branches).
 */
class CustomerDisplayResourceTest {

    /**
     * Builds a {@link CustomerDisplayResource} whose collaborators are fresh
     * mocks wired onto its package-private fields, including mocked
     * {@link PosState#ticket} and {@link PosState#payment} sub-states with an
     * empty item list so no direct field access hits a null.
     *
     * @return a resource with fully mocked state and template
     */
    private CustomerDisplayResource newResource() {
        CustomerDisplayResource resource = new CustomerDisplayResource();
        resource.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(resource.posSettingsService.customerOpenMessage()).thenReturn("Bienvenue");
        when(resource.posSettingsService.customerClosedMessage()).thenReturn("Caisse fermée");
        // The customer-display QR defaults to ON, the pre-existing behavior the
        // digital-path cases rely on (BO-10-07-02).
        when(resource.posSettingsService.customerQrEnabled()).thenReturn(true);
        resource.state = mock(PosState.class);
        resource.state.ticket = mock(TicketState.class);
        resource.state.ticket.items = new ArrayList<>();
        resource.state.payment = mock(PaymentState.class);
        return resource;
    }

    /**
     * Builds a mocked ticket item carrying the given quantity fields and a
     * stubbed formatted price.
     *
     * @param plu the PLU code, or null
     * @param quantity the quantity
     * @param label the display label
     * @param priceFormatted the value returned by {@code getPriceFormatted()}
     * @return the mocked ticket item
     */
    private TicketState.TicketItem item(String plu, BigDecimal quantity, String label, String priceFormatted) {
        TicketState.TicketItem it = mock(TicketState.TicketItem.class);
        it.plu = plu;
        it.quantity = quantity;
        it.label = label;
        when(it.getPriceFormatted()).thenReturn(priceFormatted);
        return it;
    }

    // --- customerPage ---

    /**
     * {@code customerPage()} renders the customer template bound to the state.
     */
    @Test
    void customerPageRendersCustomerTemplate() {
        CustomerDisplayResource resource = newResource();
        resource.customer = mock(Template.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.customer.data("state", resource.state)).thenReturn(view);
        assertSame(view, resource.customerPage());
    }

    // --- customerData: version gate ---

    /**
     * {@code customerData()} reports no change when a non-null client version
     * matches the current state version (guard true / true).
     */
    @Test
    void customerDataUnchangedWhenVersionMatches() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 7L;
        Map<String, Object> result = resource.customerData(7L);
        assertEquals(1, result.size());
        assertEquals(false, result.get("changed"));
        verifyNoInteractions(resource.state.ticket);
    }

    /**
     * {@code customerData()} builds a full snapshot when a non-null client
     * version differs from the current one (guard true / false), with no
     * change amount (ternary false), an empty ticket (loop not entered) and a
     * null draft id (digital path short-circuit).
     */
    @Test
    void customerDataChangedWhenVersionDiffers() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 2L;
        resource.state.trainingMode = false;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        resource.state.payment.paymentInProgress = false;
        resource.state.payment.transactionComplete = false;
        resource.state.payment.lastChangeAmount = null;
        resource.state.payment.ticketDbId = null;
        resource.state.customerMessage = "";
        Map<String, Object> result = resource.customerData(1L);
        assertEquals(16, result.size());
        assertEquals(true, result.get("changed"));
        assertEquals(2L, result.get("version"));
        assertEquals(false, result.get("locked"));
        assertEquals("Bienvenue", result.get("welcomeMessage"));
        assertEquals(false, result.get("training"));
        assertEquals(true, result.get("empty"));
        assertEquals("0,00", result.get("total"));
        assertEquals("0,00", result.get("remaining"));
        assertEquals(false, result.get("paying"));
        assertEquals(false, result.get("complete"));
        assertEquals("", result.get("change"));
        assertEquals("", result.get("message"));
        assertEquals("", result.get("digitalPath"));
        List<?> items = (List<?>) result.get("items");
        assertTrue(items.isEmpty());
    }

    /**
     * {@code customerData()} builds a full snapshot when the client version is
     * null (guard short-circuit false), carrying a change amount (ternary
     * true), a populated ticket (loop entered) covering the four quantity
     * formats — kilograms, whole units, fractional units, and the empty
     * quantity of a price-embedded sticker — and a resolvable digital path
     * (all three guards false).
     */
    @Test
    void customerDataFullSnapshotWithItemsAndChangeAndDigitalPath() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 9L;
        resource.state.trainingMode = true;
        when(resource.state.isLocked()).thenReturn(true);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("12,34");
        resource.state.payment.paymentInProgress = true;
        resource.state.payment.transactionComplete = true;
        resource.state.payment.lastChangeAmount = new BigDecimal("2.5");
        resource.state.payment.ticketDbId = 42L;
        resource.state.customerMessage = "PAIEMENT REFUSE";
        resource.state.ticket.items.add(item("100", new BigDecimal("1.5"), "Bananas", "3,00"));
        resource.state.ticket.items.add(item(null, new BigDecimal("2"), "Milk", "2,00"));
        resource.state.ticket.items.add(item("", new BigDecimal("1.25"), "Nails", "4,00"));
        TicketState.TicketItem sticker = item("200", BigDecimal.ONE, "Ham", "5,00");
        sticker.priceEmbedded = true;
        resource.state.ticket.items.add(sticker);
        Ticket ticket = mock(Ticket.class);
        ticket.id = 42L;
        ticket.digitalKey = "ABCDEF0123456789";
        when(resource.state.getCashRoundedRemainingFormatted()).thenReturn("24,60");
        when(resource.state.isCashRoundingVisible()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(42L)).thenReturn(ticket);
            Map<String, Object> result = resource.customerData(null);
            assertEquals(16, result.size());
            assertEquals(true, result.get("changed"));
            assertEquals(9L, result.get("version"));
            assertEquals(true, result.get("locked"));
            assertEquals("Caisse fermée", result.get("welcomeMessage"));
            assertEquals(true, result.get("training"));
            assertEquals(false, result.get("empty"));
            assertEquals("12,34", result.get("total"));
            assertEquals(true, result.get("paying"));
            assertEquals(true, result.get("complete"));
            assertEquals("2,50", result.get("change"));
            assertEquals("PAIEMENT REFUSE", result.get("message"));
            assertEquals("/t/42/ABCDEF0123456789", result.get("digitalPath"));
            // LC-07-03-03: where the shop rounds, the customer display carries BOTH
            // figures — what the sale owes and what can be handed over in coins.
            assertEquals("24,60", result.get("roundedRemaining"));
            assertEquals(true, result.get("rounding"));
            @SuppressWarnings("unchecked")
            List<Map<String, String>> items = (List<Map<String, String>>) result.get("items");
            assertEquals(4, items.size());
            assertEquals("Bananas", items.get(0).get("label"));
            assertEquals("1,500 kg", items.get(0).get("qty"));
            assertEquals("3,00", items.get(0).get("amount"));
            assertEquals("Milk", items.get(1).get("label"));
            assertEquals("x2", items.get(1).get("qty"));
            assertEquals("2,00", items.get(1).get("amount"));
            assertEquals("Nails", items.get(2).get("label"));
            assertEquals("x1,25", items.get(2).get("qty"));
            assertEquals("Ham", items.get(3).get("label"));
            assertEquals("", items.get(3).get("qty"));
            assertEquals("4,00", items.get(2).get("amount"));
        }
    }

    /**
     * BO-10-07-02: with the customer-display QR DISABLED, the snapshot carries
     * an empty digital path — the {@code digitalPath()} helper is
     * short-circuited by the {@code customerQrEnabled()} false arm, so no
     * ticket lookup happens.
     */
    @Test
    void customerDataQrDisabledYieldsEmptyDigitalPath() {
        CustomerDisplayResource resource = newResource();
        when(resource.posSettingsService.customerQrEnabled()).thenReturn(false);
        resource.state.version = 1L;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        resource.state.payment.lastChangeAmount = null;
        Map<String, Object> result = resource.customerData(null);
        assertEquals("", result.get("digitalPath"));
    }

    /**
     * With the EAN display ON (display.show-ean, BO-10-02-35), a line carrying
     * an EAN exposes it on the customer screen while a line without one does
     * not — both arms of the {@code showEan() && item.ean != null} guard.
     */
    @Test
    void customerDataShowsEanOnlyWhenEnabledAndPresent() {
        CustomerDisplayResource resource = newResource();
        when(resource.posSettingsService.showEan()).thenReturn(true);
        resource.state.version = 3L;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("3,00");
        resource.state.payment.lastChangeAmount = null;
        TicketState.TicketItem withEan = item(null, new BigDecimal("1"), "PAIN", "2,00");
        withEan.ean = "3017620422003";
        TicketState.TicketItem noEan = item(null, new BigDecimal("1"), "LAIT", "1,00");
        noEan.ean = null;
        resource.state.ticket.items.add(withEan);
        resource.state.ticket.items.add(noEan);
        Map<String, Object> result = resource.customerData(null);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> items = (List<Map<String, String>>) result.get("items");
        assertEquals("3017620422003", items.get(0).get("ean"));
        assertFalse(items.get(1).containsKey("ean"));
    }

    // --- digitalPath guards ---

    /**
     * {@code digitalPath()} yields an empty string when the draft id resolves
     * to no ticket (first arm of the null guard true).
     */
    @Test
    void customerDataDigitalPathEmptyWhenTicketNotFound() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 1L;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        resource.state.payment.lastChangeAmount = null;
        resource.state.payment.ticketDbId = 5L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            Map<String, Object> result = resource.customerData(null);
            assertEquals("", result.get("digitalPath"));
        }
    }

    /**
     * {@code digitalPath()} yields an empty string when the found ticket has no
     * digital key (second arm of the null guard true).
     */
    @Test
    void customerDataDigitalPathEmptyWhenDigitalKeyNull() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 1L;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        resource.state.payment.lastChangeAmount = null;
        resource.state.payment.ticketDbId = 6L;
        Ticket ticket = mock(Ticket.class);
        ticket.id = 6L;
        ticket.digitalKey = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(6L)).thenReturn(ticket);
            Map<String, Object> result = resource.customerData(null);
            assertEquals("", result.get("digitalPath"));
            assertFalse(result.containsKey("nonexistent"));
        }
    }

    /**
     * LC-02-03-02: a non-weighed line sold under a unit of measure states the
     * quantity with its unit — the true/true leg of the
     * {@code unitName != null && !unitName.isBlank()} guard — while a line whose
     * unit name is present but blank falls back to the plain count, the
     * blank leg the null guard alone would miss.
     */
    @Test
    void customerDataFormatsTheUnitOfMeasureAndFallsBackWhenBlank() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 4L;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        resource.state.payment.lastChangeAmount = null;
        TicketState.TicketItem measured = item(null, new BigDecimal("2.36"), "CABLE", "5,00");
        measured.unitName = "m";
        TicketState.TicketItem blankUnit = item(null, new BigDecimal("2"), "LAIT", "2,00");
        blankUnit.unitName = "   ";
        resource.state.ticket.items.add(measured);
        resource.state.ticket.items.add(blankUnit);
        Map<String, Object> result = resource.customerData(null);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> items = (List<Map<String, String>>) result.get("items");
        assertEquals("2,36 m", items.get(0).get("qty"));
        assertEquals("x2", items.get(1).get("qty"));
    }

    /**
     * BO-10-03-03: the customer display names the holder only where the shop
     * asked for it — the cashier-side rule is a separate decision.
     */
    @Test
    void theCustomerDisplayNamesTheHolderOnlyWhenAsked() {
        CustomerDisplayResource naming = newResource();
        naming.state.fidelity = new com.intermarche.pos.ui.fidelity.FidelityState();
        naming.state.fidelity.assignCard("6045200000123");
        naming.state.fidelity.holderLastName = "Dupont";
        naming.state.fidelity.holderFirstName = "Jean";
        when(naming.posSettingsService.fidelityShowHolderNameOnCustomerDisplay())
                .thenReturn(true);
        assertEquals("DUPONT Jean", naming.customerData(null).get("fidelityHolder"));

        CustomerDisplayResource silent = newResource();
        silent.state.fidelity = new com.intermarche.pos.ui.fidelity.FidelityState();
        silent.state.fidelity.assignCard("6045200000123");
        silent.state.fidelity.holderLastName = "Dupont";
        assertEquals("", silent.customerData(null).get("fidelityHolder"));
    }
}
