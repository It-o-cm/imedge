package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jboss.logging.Logger;

/**
 * INFO ARTICLE — an article lookup (EXP-93). A code is scanned or keyed and the
 * register shows the article's label, code, price and VAT.
 *
 * <p>It is reachable CAISSE FERMÉE without a user code — it is on the
 * {@code LockCheckFilter} allowlist and needs neither an open session nor a
 * logged-in operator, because looking up what an article costs is not a sale.
 * It is not drawer-guarded either (no {@code @DrawerMustBeClosed}): a pure
 * consultation never touches the drawer.
 *
 * <p>THE LOOKUP CAN END IN A SALE, and that was the recette's point: a cashier who
 * looks an article up for a customer is usually about to sell it, and the screen
 * offered no way out but RETOUR. A found article can now be TAKEN — one unit, added
 * through the scan admission like any typed code — and RETOUR keeps its meaning,
 * which is "no".
 *
 * <p>THE TAKE KEY IS CONDITIONAL, and that is what preserves the read-only reason
 * above: it shows only when the register is unlocked AND a session is open, which is
 * exactly when a ticket can exist. Caisse fermée, or before anyone logged in, the
 * screen is the pure consultation it has always been.
 */
@Path("/")
public class ArticleInfoResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ArticleInfoResource.class);

    /** The article-info screen. */
    @Inject @Location("article-info") Template articleInfo;

    /** The ticket the taken article is added to. */
    @Inject TicketService ticketService;

    /** Whether a ticket can exist at all: the lock and the logged operator. */
    @Inject PosState state;

    /** Whether a session is open, the other half of "a ticket can exist". */
    @Inject CashSessionService cashSessionService;

    /** The electronic journal this screen writes its price checks to (BO-04-01-38). */
    @Inject com.intermarche.pos.service.TechnicalEventService technicalEventService;

    /**
     * Shows the article-info screen: a lookup form, and — when a code is given —
     * the found article read-only, or a not-found message.
     *
     * @param code the code to look up (EAN, PLU or internal code), or null/blank
     *        to show just the form
     * @return the article-info page
     */
    @GET
    @Path("/article-info")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance infoPage(@QueryParam("code") String code) {
        LOGGER.info("Entering method infoPage with code: " + code);
        ArticleInfo info = (code == null || code.isBlank()) ? null : lookup(code.trim());
        boolean notFound = code != null && !code.isBlank() && info == null;
        // BO-04-01-38: the check itself is the event, found or not — a code
        // nobody recognises is exactly what a shop looks for afterwards. The
        // empty form is not a check and writes nothing.
        if (code != null && !code.isBlank()) {
            technicalEventService.log(
                    com.intermarche.pos.domain.session.TechnicalEvent.EventType.PRICE_CHECKED,
                    info == null ? code.trim() + " (inconnu)" : code.trim() + " " + info.label,
                    state.auth.operatorBadgeId);
        }
        LOGGER.info("Exiting method infoPage");
        return articleInfo
                .data("code", code == null ? "" : code)
                .data("info", info)
                .data("notFound", notFound)
                .data("canTake", canTake());
    }

    /**
     * Whether the screen may offer to TAKE the article: the register is unlocked and
     * a session is open, which together are the only state in which a ticket exists.
     *
     * @return true when the found article can be added to a ticket
     */
    boolean canTake() {
        return !state.isLocked() && cashSessionService.getOpenSession() != null;
    }

    /**
     * Takes the looked-up article: ONE unit, added through the scan admission — the
     * same chain a scanner walks, which is the only one that knows the EANs, the GS1
     * payloads, the vouchers and the price-embedded labels.
     *
     * <p>Refused in the states where no ticket can exist, so a code posted straight
     * at this action caisse fermée adds nothing. The cashier lands back on the sale
     * screen, where the line is.
     *
     * @param code the code of the article shown on the screen
     * @return a redirect to the sale screen, or back to the lookup when the register
     *     is in no state to sell
     */
    @GET
    @Path("/action/article-info/take")
    public Response takeArticle(@QueryParam("code") String code) {
        LOGGER.info("Entering method takeArticle with code: " + code);
        String trimmed = code == null ? "" : code.trim();
        if (trimmed.isEmpty() || !canTake()) {
            LOGGER.info("Exiting method takeArticle: rien a prendre");
            return Response.seeOther(URI.create("/article-info")).build();
        }
        ticketService.processScan(trimmed);
        LOGGER.info("Exiting method takeArticle");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Looks an article up by EAN, then PLU, then internal code — read only.
     *
     * @param code the code keyed or scanned
     * @return the article info, or null when no active article carries the code
     */
    private ArticleInfo lookup(String code) {
        Product product = Product.findActiveByEan(code);
        if (product == null) {
            product = Product.findActiveByPlu(code);
        }
        if (product == null) {
            return null;
        }
        Price price = Price.findCurrentPrice(product.id);
        return new ArticleInfo(product, price);
    }

    /**
     * The read-only view of an article: label, code, price and VAT.
     */
    public static class ArticleInfo {
        /** The article's sale label. */
        public final String label;
        /** The article's EAN, or a dash when it has none. */
        public final String ean;
        /** The article's PLU, or a dash when it has none. */
        public final String plu;
        /** The article's brand, or a dash. */
        public final String brand;
        /** The price including tax, formatted with a French comma, or a dash when unpriced. */
        public final String priceFormatted;
        /** The VAT rate as a percentage, formatted, or a dash when unpriced. */
        public final String vatFormatted;

        /**
         * Builds the view from a product and its current price.
         *
         * @param product the found product
         * @param price the current price, or null when the article is unpriced
         */
        ArticleInfo(Product product, Price price) {
            this.label = product.saleLabel();
            this.ean = product.ean == null || product.ean.isBlank() ? "—" : product.ean;
            this.plu = product.plu == null || product.plu.isBlank() ? "—" : product.plu;
            this.brand = product.brand == null || product.brand.isBlank() ? "—" : product.brand;
            if (price == null || price.priceIncludingTax == null) {
                this.priceFormatted = "—";
            } else {
                this.priceFormatted = String.format("%.2f",
                        price.priceIncludingTax.setScale(2, RoundingMode.HALF_UP)).replace('.', ',');
            }
            BigDecimal vat = price == null ? null : price.vatRate();
            if (vat == null) {
                this.vatFormatted = "—";
            } else {
                this.vatFormatted = vat.multiply(BigDecimal.valueOf(100))
                        .stripTrailingZeros().toPlainString() + " %";
            }
        }

        /** @return the article's sale label */
        public String getLabel() { return label; }

        /** @return the article's EAN or a dash */
        public String getEan() { return ean; }

        /** @return the article's PLU or a dash */
        public String getPlu() { return plu; }

        /** @return the article's brand or a dash */
        public String getBrand() { return brand; }

        /** @return the price including tax, formatted, or a dash */
        public String getPriceFormatted() { return priceFormatted; }

        /** @return the VAT rate as a formatted percentage, or a dash */
        public String getVatFormatted() { return vatFormatted; }
    }
}
