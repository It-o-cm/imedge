package com.intermarche.edge.imports;

import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductType;
import com.intermarche.edge.domain.setting.ServiceProvider;
import com.intermarche.edge.service.PosSettingsService;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The EXPOSURE OF THE PRICES to the price kiosks (BO-11-02-06): a kiosk the
 * customer scans an article at asks the encashment base, on demand, what the
 * article costs at the tills right now — the price that applies at this very
 * instant, offers included, never a copy that ages on the kiosk.
 *
 * <p>The kiosks are DECLARED, not trusted: each one is a provider of type
 * {@code Borne de prix} of the provider table (BO-03-11), and a request is
 * answered only when it presents, as a bearer credential, the key of a kiosk
 * provider in service. The answer is written in the format the kiosk's
 * protocol expects — JSON, XML or one text line — as the store administers it.
 */
@Path("/api/price-kiosk")
public class PriceKioskResource {

    /** The media type of a refusal: a short French sentence, never an octet stream. */
    static final String REFUSAL_TYPE = "text/plain; charset=UTF-8";

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PriceKioskResource.class);

    /** The prefix of a bearer credential. */
    private static final String BEARER = "Bearer ";

    /** The unit of a price per kilogram. */
    static final String KILOGRAM = "kg";

    /** The unit of a price per litre. */
    static final String LITRE = "l";

    /** The referential units that make an article's price convertible per kilogram. */
    private static final java.util.Set<String> WEIGHT_UNITS = java.util.Set.of("kg", "g");

    /** The referential units that make an article's price convertible per litre. */
    private static final java.util.Set<String> VOLUME_UNITS = java.util.Set.of("l", "cl", "ml");

    /** The administered switch and format. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Answers the price of one article to a declared kiosk.
     *
     * @param authorization the {@code Authorization} header, {@code Bearer <key>}
     * @param ean the article's EAN as the kiosk scanned it
     * @return 200 with the label and the price, 401 for an undeclared kiosk, 403
     *     when the exposure is off, 404 for an article the base does not sell
     */
    @GET
    @Path("/{ean}")
    @PermitAll
    @Transactional
    public Response price(@HeaderParam("Authorization") String authorization, @PathParam("ean") String ean) {
        LOGGER.info("Entering method price with ean: " + ean);
        if (!posSettingsService.kioskEnabled()) {
            LOGGER.info("Exiting method price: exposure off");
            return Response.status(Response.Status.FORBIDDEN).entity("Exposition des prix désactivée").type(REFUSAL_TYPE).build();
        }
        if (!declaredKiosk(authorization)) {
            LOGGER.info("Exiting method price: undeclared kiosk");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Borne de prix non déclarée").type(REFUSAL_TYPE).build();
        }
        Product product = Product.findActiveByEan(ean == null ? null : ean.trim());
        Price price = product == null ? null : Price.findCurrentPrice(product.id);
        if (price == null || price.priceIncludingTax == null) {
            LOGGER.info("Exiting method price: unknown article");
            return Response.status(Response.Status.NOT_FOUND).entity("Article inconnu").type(REFUSAL_TYPE).build();
        }
        String amount = price.priceIncludingTax.setScale(2, RoundingMode.HALF_UP).toPlainString();
        boolean offer = price.priority != null && price.priority > 0;
        UnitPrice unit = unitPrice(product, price.priceIncludingTax);
        String unitAmount = unit == null ? "" : unit.amount().toPlainString();
        String unitName = unit == null ? "" : unit.unit();
        String format = posSettingsService.kioskFormat();
        LOGGER.info("Exiting method price");
        return switch (format) {
            case "XML" -> Response.ok("<?xml version=\"1.0\" encoding=\"UTF-8\"?><price><ean>"
                    + escape(product.ean) + "</ean><label>" + escape(product.name) + "</label><amount>"
                    + amount + "</amount><currency>EUR</currency><offer>" + offer + "</offer>"
                    + (unit == null ? "" : "<unitPrice unit=\"" + unitName + "\">" + unitAmount + "</unitPrice>")
                    + "</price>",
                    "application/xml").build();
            case "TEXT" -> Response.ok(product.ean + "|" + clean(product.name) + "|" + amount
                    + "|" + unitAmount + "|" + unitName, "text/plain").build();
            default -> Response.ok(new KioskPrice(product.ean, product.name, price.priceIncludingTax
                    .setScale(2, RoundingMode.HALF_UP), "EUR", offer,
                    unit == null ? null : unit.amount(), unit == null ? null : unit.unit()),
                    "application/json").build();
        };
    }

    /**
     * The price per kilogram or per litre the kiosk shows beside the price
     * (BO-11-02-06, LS-11-01-02), as the parameter {@code kiosk.enabled}
     * promises it: the current price itself for an article sold by weight
     * (loose or typed WEIGHT) or by volume, the price divided by the
     * referential's reference weight for an article whose unit is the
     * kilogram or the gram, by its reference volume for one whose unit is the
     * litre, the centilitre or the millilitre. Nothing for an article sold by
     * the piece, or whose reference quantity is missing.
     *
     * @param product the article
     * @param current the current tax-included price
     * @return the unit price, two decimals, or null when none applies
     */
    static UnitPrice unitPrice(Product product, BigDecimal current) {
        if (product.variableWeight || product.productType == ProductType.WEIGHT) {
            return new UnitPrice(current.setScale(2, RoundingMode.HALF_UP), KILOGRAM);
        }
        if (product.productType == ProductType.VOLUME) {
            return new UnitPrice(current.setScale(2, RoundingMode.HALF_UP), LITRE);
        }
        String unit = product.unitName == null ? "" : product.unitName.trim().toLowerCase(java.util.Locale.ROOT);
        if (WEIGHT_UNITS.contains(unit)) {
            return perReference(current, product.referenceWeight, KILOGRAM);
        }
        if (VOLUME_UNITS.contains(unit)) {
            return perReference(current, product.referenceVolume, LITRE);
        }
        return null;
    }

    /**
     * Divides a price by a reference quantity.
     *
     * @param current the price
     * @param reference the reference quantity, possibly null
     * @param unit the unit the quantity is expressed in
     * @return the unit price, or null when the reference is missing or not positive
     */
    private static UnitPrice perReference(BigDecimal current, BigDecimal reference, String unit) {
        if (reference == null || reference.signum() <= 0) {
            return null;
        }
        return new UnitPrice(current.divide(reference, 2, RoundingMode.HALF_UP), unit);
    }

    /**
     * A price per unit of measure.
     *
     * @param amount the price per unit, two decimals
     * @param unit {@code kg} or {@code l}
     */
    record UnitPrice(BigDecimal amount, String unit) {
    }

    /**
     * The JSON answer of a kiosk.
     *
     * @param ean the article's EAN
     * @param label the article's label
     * @param price the current tax-included price
     * @param currency the currency
     * @param offer whether an offer price applies
     * @param unitPrice the price per kilogram or per litre, or null
     * @param unit {@code kg} or {@code l}, or null
     */
    public record KioskPrice(String ean, String label, BigDecimal price, String currency, boolean offer,
                             BigDecimal unitPrice, String unit) {
    }

    /**
     * Tells whether the caller presents the key of a kiosk provider in service.
     *
     * @param authorization the {@code Authorization} header, possibly null
     * @return true for a declared kiosk
     */
    private boolean declaredKiosk(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return false;
        }
        String key = authorization.substring(BEARER.length()).trim();
        for (ServiceProvider kiosk : ServiceProvider.listActiveOfType(ServiceProvider.ProviderType.BORNE_PRIX)) {
            if (kiosk.hasCredential() && kiosk.authKey.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Escapes a text for XML.
     *
     * @param text the text, possibly null
     * @return the escaped text, empty for null
     */
    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Removes the text separator from a label.
     *
     * @param text the label, possibly null
     * @return the cleaned label, empty for null
     */
    private static String clean(String text) {
        return text == null ? "" : text.replace('|', ' ');
    }
}
