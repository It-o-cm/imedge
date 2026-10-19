package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Customer-facing display (phase 4): a dedicated page opened on the second
 * screen, polling its own fragment with the same version mechanism as the
 * main screen. Shows the running cart, the totals, the payment progress and,
 * once complete, the change and the digital receipt link. No lock applies —
 * it is a display, not a control surface.
 * <p>
 * The screen is DERIVED, never commanded: {@code /customer-data} projects
 * the register's {@code PosState} into a display phase (welcome when locked
 * or empty, running cart, payment in progress, thank-you with change and
 * digital link, orange banner in training) and the page's versioned poll
 * redraws on change — the register never pushes anything to the display,
 * which is what lets it be a plain second browser window with zero setup.
 * Because it reads shared state without a lock gate, it must stay
 * READ-ONLY: any control added here would bypass the operator lock.
 */
@Path("/")
public class CustomerDisplayResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CustomerDisplayResource.class);

    @Inject @Location("customer") Template customer;
    @Inject PosState state;

    /** The back-office parameters (EAN, welcome messages — LC-10-01-20/21). */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * Shows the customer display page.
     *
     * @return the customer display page
     */
    @GET
    @Path("/customer")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance customerPage() {
        LOGGER.info("Entering method customerPage");
        state.customerDisplaySeenAt = System.currentTimeMillis();
        LOGGER.info("Exiting method customerPage");
        return customer.data("state", state);
    }

    /**
     * Polling endpoint of the customer display, version-gated like the main
     * screen fragment.
     *
     * @param clientVersion the version known by the display, or null
     * @return a JSON map with the change flag and, when changed, the display data
     */
    @GET
    @Path("/customer-data")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> customerData(@QueryParam("v") Long clientVersion) {
        LOGGER.info("Entering method customerData with clientVersion: " + clientVersion);
        // Every poll is the proof that a customer screen exists and is showing this
        // page: it is the only evidence there is, a screen being no device on any bus.
        // The hardware gate reads this timestamp.
        state.customerDisplaySeenAt = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();
        if (clientVersion != null && state.version == clientVersion) {
            result.put("changed", false);
            LOGGER.info("Exiting method customerData");
            return result;
        }
        result.put("changed", true);
        result.put("version", state.version);
        result.put("locked", state.isLocked());
        // LC-10-01-20/21: the idle message is administered — one text for an
        // open register waiting for a sale, another for a locked one.
        result.put("welcomeMessage", state.isLocked()
                ? posSettingsService.customerClosedMessage()
                : posSettingsService.customerOpenMessage());
        result.put("training", state.trainingMode);
        // The one PUSHED value of this page: what a line display would have shown.
        // Everything else here is derived from the sale, but "PAIEMENT REFUSE" is an
        // event, and this screen is the only customer display this till has.
        result.put("message", state.customerMessage);
        result.put("empty", state.ticket.items.isEmpty());
        result.put("total", state.ticket.getTotalFormatted());
        result.put("remaining", state.getRemainingFormatted());
        // LC-07-03-03: the customer display carries BOTH figures where the shop
        // rounds — what the sale owes, and what the customer hands over.
        result.put("roundedRemaining", state.getCashRoundedRemainingFormatted());
        result.put("rounding", state.isCashRoundingVisible());
        // BO-10-03-03: the holder's name on the CUSTOMER display, where the
        // shop asks for it — a separate decision from the cashier screen's,
        // because this one faces the queue.
        result.put("fidelityHolder",
                posSettingsService.fidelityShowHolderNameOnCustomerDisplay()
                        ? state.fidelity.getHolderName() : "");
        result.put("paying", state.payment.paymentInProgress);
        result.put("complete", state.payment.transactionComplete);
        result.put("change", state.payment.lastChangeAmount != null
                ? state.payment.lastChangeAmount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", ",")
                : "");
        // BO-10-07-02: the digital-receipt QR code is emitted only when the
        // back office activated it; disabled, the display shows no QR code.
        result.put("digitalPath", posSettingsService.customerQrEnabled() ? digitalPath() : "");

        List<Map<String, String>> items = new ArrayList<>();
        for (TicketState.TicketItem item : state.ticket.items) {
            Map<String, String> row = new HashMap<>();
            row.put("label", item.label);
            if (posSettingsService.showEan() && item.ean != null) {
                // LC-02-04-02: the EAN accompanies the label when configured.
                row.put("ean", item.ean);
            }
            row.put("qty", quantityDisplay(item));
            row.put("amount", item.getPriceFormatted());
            // P3-5 / LC-02-05-04: the decomposition line (qty × unit price) the
            // customer reads under the label, empty for a single-unit piece.
            row.put("detail", item.getDetailLine());
            items.add(row);
        }
        result.put("items", items);
        LOGGER.info("Exiting method customerData");
        return result;
    }

    /**
     * Builds the digital receipt path of the current draft, or an empty string.
     *
     * @return the digital receipt path, or ""
     */
    private String digitalPath() {
        if (state.payment.ticketDbId == null) return "";
        Ticket ticket = Ticket.findById(state.payment.ticketDbId);
        if (ticket == null || ticket.digitalKey == null) return "";
        return "/t/" + ticket.id + "/" + ticket.digitalKey;
    }

    /**
     * Formats an item quantity for the customer display: kilograms for
     * weighed lines, a unit count otherwise. A price-embedded sticker line
     * shows no quantity at all — the sticker fixed the price, the weight is
     * unknown at the register.
     *
     * @param item the ticket item
     * @return the formatted quantity, empty for price-embedded stickers
     */
    private String quantityDisplay(TicketState.TicketItem item) {
        if (item.priceEmbedded) {
            return "";
        }
        if (item.plu != null && !item.plu.isEmpty()) {
            return String.format("%.3f kg", item.quantity).replace(".", ",");
        }
        // LC-02-03-02: an article sold by a unit of measure states it here too — the
        // customer reads "2,36 m", not "x2,36".
        if (item.unitName != null && !item.unitName.isBlank()) {
            return String.format("%.2f", item.quantity).replace(".", ",")
                    + " " + item.unitName.trim();
        }
        if (item.quantity.stripTrailingZeros().scale() <= 0) {
            return "x" + item.quantity.stripTrailingZeros().toPlainString();
        }
        return "x" + String.format("%.2f", item.quantity).replace(".", ",");
    }
}
