package com.intermarche.edge.ui.hardware;

import org.jboss.logging.Logger;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * The store logo, turned into what a receipt printer can print.
 *
 * <p>A thermal head prints dots, not pixels of an image file: one bit per dot, eight
 * dots per byte, black where the bit is set. So the logo is read once from the
 * classpath, scaled to the paper, thresholded to pure black and white, and packed into
 * that form. The result is cached: a receipt must not pay for image decoding.
 *
 * <p>The file is optional by design. A till with no logo prints exactly what it printed
 * before, and a file that cannot be read is reported once and then ignored — a receipt
 * that fails to print because of decoration would be a poor trade.
 */
public final class ReceiptLogo {

    private static final Logger LOGGER = Logger.getLogger(ReceiptLogo.class);

    /** Where the logo is looked for on the classpath. */
    private static final String RESOURCE = "/print/logo.png";

    /**
     * Width the logo is printed at, in dots.
     *
     * <p>An 80 mm head prints 8 dots per millimetre, so 576 dots edge to edge; 384 is
     * 48 mm, a header that reads without eating the roll. It is the DEFAULT only:
     * {@code print.logo-width-dots} states the head's own width (BO-03-03-14). The logo is scaled to it in
     * BOTH directions: a wordmark exported small would otherwise print at a fifth of
     * the paper's width, and one exported for screen would eat the margins.
     */
    private static final int PRINT_WIDTH = 384;

    /** Dots packed into one byte of a raster row. */
    private static final int DOTS_PER_BYTE = 8;

    /** Luminance below which a pixel is printed black. */
    private static final int BLACK_THRESHOLD = 128;

    /** Weights of the sRGB channels in the luminance used for thresholding. */
    private static final int RED_WEIGHT = 299;
    private static final int GREEN_WEIGHT = 587;
    private static final int BLUE_WEIGHT = 114;
    private static final int WEIGHT_TOTAL = 1000;

    /**
     * The directives already computed, keyed by the print width in dots; the
     * value is empty when there is no printable logo.
     *
     * <p>Keyed on the width because the width is ADMINISTERED (BO-03-03-14): a
     * shop that widens its logo must see the wider one on the next receipt,
     * without anyone having to remember to clear a cache, and a register that
     * never changes it still decodes the file once.
     */
    private static final java.util.Map<Integer, String> DIRECTIVES =
            new java.util.HashMap<>();

    /** The logo as a data URI, computed once; empty when there is no logo file. */
    private static String dataUri;

    /**
     * The administered image the cached directive was rasterised from, or
     * null when none has been asked for yet ({@code BO-03-03-15}).
     *
     * <p>ONE entry and not a map: a register prints the logo of its own
     * store, so the administered image changes when the back office replaces
     * it and not from one document to the next. Caching the last one asked
     * for is therefore caching all of them, and it cannot grow.
     */
    private static String administeredSource;

    /** The width the cached administered directive was rasterised at. */
    private static int administeredWidth;

    /** The cached directive of the administered image, possibly empty. */
    private static String administeredDirective;

    /** Whether the data URI has been built already. */
    private static boolean dataUriLoaded;

    /** Not instantiable: the logo is a single classpath resource. */
    private ReceiptLogo() {
    }

    /**
     * The print directive carrying the logo, at the default width.
     *
     * @return the directive line WITHOUT its newline, or empty when this till has no
     *         logo or the file could not be read
     */
    public static synchronized String directive() {
        return directive(PRINT_WIDTH);
    }

    /**
     * The print directive carrying the logo, at the width the shop administers
     * (BO-03-03-14).
     *
     * <p>The width is the printer's, not the layout's: an Epson TMH6000 prints
     * 8 dots per millimetre, so the same logo file has to be rasterised again
     * when the shop states a different head width. A width narrower than one
     * packed byte cannot be rasterised at all and falls back to the default
     * rather than failing a receipt.
     *
     * @param widthDots the width to print at, in thermal-head dots
     * @return the directive line WITHOUT its newline, or empty when this till has no
     *         logo or the file could not be read
     */
    public static synchronized String directive(int widthDots) {
        int width = widthDots < DOTS_PER_BYTE ? PRINT_WIDTH : widthDots;
        String cached = DIRECTIVES.get(width);
        if (cached != null) {
            return cached;
        }
        String computed = read(width).orElse("");
        DIRECTIVES.put(width, computed);
        return computed;
    }

    /**
     * The print directive carrying the ADMINISTERED logo of this store
     * ({@code BO-03-03-15}), at the width the shop administers.
     *
     * <p>The administered image wins over the installation's file, and an
     * empty one falls back on it: a store that has imported no logo prints
     * exactly what it printed before, and one whose import is unreadable
     * prints that too rather than nothing.
     *
     * @param imageData the administered image as a {@code data:} URI or a
     *        bare base64 payload, possibly null or empty
     * @param widthDots the width to print at, in thermal-head dots
     * @return the directive line WITHOUT its newline, or empty when neither
     *         the administered image nor the installation's file is printable
     */
    public static synchronized String directive(String imageData, int widthDots) {
        if (imageData == null || imageData.isBlank()) {
            return directive(widthDots);
        }
        int width = widthDots < DOTS_PER_BYTE ? PRINT_WIDTH : widthDots;
        if (imageData.equals(administeredSource) && width == administeredWidth) {
            return administeredDirective;
        }
        administeredSource = imageData;
        administeredWidth = width;
        administeredDirective = rasterise(imageData, width);
        return administeredDirective;
    }

    /**
     * The print directive carrying the ADMINISTERED logo of this store, at
     * the default width ({@code BO-03-03-15}) — the width the documents that
     * do not read the administered head width print at.
     *
     * @param imageData the administered image, possibly null or empty
     * @return the directive line WITHOUT its newline, or empty when neither
     *         the administered image nor the installation's file is printable
     */
    public static synchronized String directive(String imageData) {
        return directive(imageData, PRINT_WIDTH);
    }

    /**
     * Rasterises an administered image at a width, an unreadable or entirely
     * white one answering the empty string.
     *
     * @param imageData the administered image
     * @param width the width to rasterise at, in dots
     * @return the directive, or an empty string
     */
    private static String rasterise(String imageData, int width) {
        BufferedImage source = decode(imageData);
        if (source == null) {
            return "";
        }
        BufferedImage fitted = fit(source, width);
        if (fitted == null) {
            LOGGER.warnf("Logo administre entierement blanc : en-tete texte seul");
            return "";
        }
        return encode(fitted);
    }

    /**
     * Decodes an administered image, which travels as a {@code data:} URI or
     * as a bare base64 payload.
     *
     * @param imageData the administered image
     * @return the decoded image, or null when it cannot be read
     */
    private static BufferedImage decode(String imageData) {
        String payload = imageData.trim();
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma >= 0) {
            payload = payload.substring(comma + 1);
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            BufferedImage source = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (source == null) {
                LOGGER.warnf("Image administree illisible : en-tete texte seul");
            }
            return source;
        } catch (IllegalArgumentException | IOException e) {
            LOGGER.warnf(e, "Image administree illisible : en-tete texte seul");
            return null;
        }
    }

    /**
     * The ADMINISTERED logo as a {@code data:} URI, for a document rendered
     * as a page ({@code BO-03-03-15}); an empty administered image falls back
     * on the installation's file.
     *
     * @param imageData the administered image, possibly null or empty
     * @return the data URI, or empty when there is no logo at all
     */
    public static synchronized String dataUri(String imageData) {
        if (imageData == null || imageData.isBlank()) {
            return dataUri();
        }
        String payload = imageData.trim();
        return payload.startsWith("data:") ? payload : "data:image/png;base64," + payload;
    }

    /**
     * The logo as a {@code data:} URI, for a document rendered as a page rather than
     * as printer dots.
     *
     * <p>The SAME file as the receipt's, deliberately: a till showing one logo on a
     * screen and printing another is a defect nobody notices until a customer holds
     * both. Inlined rather than served, so a rendered page stays self-contained —
     * it is also what will be handed to a PDF renderer, which has no web server to
     * fetch an image from.
     *
     * <p>CROPPED TO ITS INK, like the receipt's, and for the reason the receipt has:
     * the file is a SQUARE whose wordmark occupies a band in the middle. Handed over
     * raw it draws a block of white as tall as it is wide, which on a 460 px working
     * area is most of the screen spent on nothing. The very same {@code fit} the
     * printer uses is what produces it.
     *
     * @return the data URI, or empty when this till has no logo or the file could
     *         not be read
     */
    public static synchronized String dataUri() {
        if (!dataUriLoaded) {
            dataUriLoaded = true;
            dataUri = croppedPng().map(bytes -> "data:image/png;base64,"
                    + java.util.Base64.getEncoder().encodeToString(bytes)).orElse("");
        }
        return dataUri;
    }

    /**
     * Reads the logo, crops it to its ink and re-encodes it as a PNG.
     *
     * @return the cropped image's bytes, or empty when there is nothing printable
     */
    private static Optional<byte[]> croppedPng() {
        InputStream stream = ReceiptLogo.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            LOGGER.infof("Aucun logo (%s absent) : document sans logo", RESOURCE);
            return Optional.empty();
        }
        try (stream) {
            return croppedPng(stream);
        } catch (IOException e) {
            LOGGER.warnf(e, "Logo illisible (%s) : document sans logo", RESOURCE);
            return Optional.empty();
        }
    }

    /**
     * LC-08-01: Encodes the opened logo without duplicating resource cleanup on its early returns.
     *
     * @param stream the non-null resource owned by the caller
     * @return the encoded logo, or empty when the image has no printable content
     * @throws IOException when the image cannot be decoded or encoded
     */
    private static Optional<byte[]> croppedPng(InputStream stream) throws IOException {
        BufferedImage source = ImageIO.read(stream);
        if (source == null) {
            LOGGER.warnf("Logo illisible (%s) : document sans logo", RESOURCE);
            return Optional.empty();
        }
        BufferedImage fitted = fit(source, PRINT_WIDTH);
        if (fitted == null) {
            LOGGER.warnf("Logo entierement blanc (%s) : document sans logo", RESOURCE);
            return Optional.empty();
        }
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        ImageIO.write(fitted, "png", png);
        return Optional.of(png.toByteArray());
    }

    /**
     * Reads the logo and encodes it at the given width.
     *
     * @param width the width to rasterise at, in dots
     * @return the directive, or empty when there is nothing printable
     */
    private static Optional<String> read(int width) {
        InputStream stream = ReceiptLogo.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            LOGGER.infof("Aucun logo de ticket (%s absent) : en-tete texte seul", RESOURCE);
            return Optional.empty();
        }
        try (stream) {
            return read(stream, width);
        } catch (IOException e) {
            LOGGER.warnf(e, "Logo de ticket illisible (%s) : en-tete texte seul", RESOURCE);
            return Optional.empty();
        }
    }

    /**
     * LC-08-01: Rasterises the opened logo without duplicating resource cleanup on its early returns.
     *
     * @param stream the non-null resource owned by the caller
     * @param width the target printer width in dots
     * @return the encoded logo, or empty when the image has no printable content
     * @throws IOException when the image cannot be decoded or encoded
     */
    private static Optional<String> read(InputStream stream, int width) throws IOException {
        BufferedImage source = ImageIO.read(stream);
        if (source == null) {
            LOGGER.warnf("Logo de ticket illisible (%s) : en-tete texte seul", RESOURCE);
            return Optional.empty();
        }
        BufferedImage fitted = fit(source, width);
        if (fitted == null) {
            LOGGER.warnf("Logo de ticket entierement blanc (%s) : en-tete texte seul", RESOURCE);
            return Optional.empty();
        }
        return Optional.of(encode(fitted));
    }

    /**
     * Flattens the image onto white, crops it to its ink and scales it to the paper.
     *
     * <p>Flattening comes first and matters: a logo exported with transparency would
     * otherwise threshold its transparent background to black and print a solid
     * rectangle. Cropping comes next because a logo file is usually a square canvas
     * with the mark floating in the middle, and the margin is not part of the mark:
     * printed as it is, it wastes paper and shrinks what can be read.
     *
     * @param source the decoded file
     * @param width the width to scale to, in dots
     * @return an opaque, paper-width image, or {@code null} when the file has no ink
     */
    private static BufferedImage fit(BufferedImage source, int width) {
        BufferedImage opaque = flatten(source);
        Rectangle ink = inkBounds(opaque);
        if (ink == null) {
            return null;
        }
        BufferedImage cropped = opaque.getSubimage(ink.x, ink.y, ink.width, ink.height);
        int height = Math.max(1, cropped.getHeight() * width / cropped.getWidth());
        BufferedImage fitted = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = fitted.createGraphics();
        canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        canvas.setColor(Color.WHITE);
        canvas.fillRect(0, 0, width, height);
        canvas.drawImage(cropped, 0, 0, width, height, null);
        canvas.dispose();
        return fitted;
    }

    /**
     * Draws the image onto a white, opaque canvas.
     *
     * @param source the decoded file
     * @return the same image, opaque
     */
    private static BufferedImage flatten(BufferedImage source) {
        BufferedImage opaque = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = opaque.createGraphics();
        canvas.setColor(Color.WHITE);
        canvas.fillRect(0, 0, source.getWidth(), source.getHeight());
        canvas.drawImage(source, 0, 0, null);
        canvas.dispose();
        return opaque;
    }

    /**
     * Finds the smallest rectangle holding every pixel that will print black.
     *
     * @param image the opaque image
     * @return the ink bounds, or {@code null} when the image is entirely white
     */
    private static Rectangle inkBounds(BufferedImage image) {
        int left = image.getWidth();
        int top = image.getHeight();
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (isBlack(image.getRGB(x, y))) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        return right < 0 ? null : new Rectangle(left, top, right - left + 1, bottom - top + 1);
    }

    /**
     * Packs the image into rows of dots and writes the directive.
     *
     * @param image the opaque, paper-sized image
     * @return the directive line
     */
    private static String encode(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int bytesPerRow = (width + DOTS_PER_BYTE - 1) / DOTS_PER_BYTE;
        byte[] bits = new byte[bytesPerRow * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (isBlack(image.getRGB(x, y))) {
                    // The leftmost dot of a byte is its most significant bit; the dots
                    // padding the last byte of a row stay clear, so a width that is not
                    // a multiple of eight prints white paper, not a black edge.
                    bits[y * bytesPerRow + x / DOTS_PER_BYTE]
                            |= (byte) (0x80 >> (x % DOTS_PER_BYTE));
                }
            }
        }
        return "[[IMAGE " + width + " " + height + " "
                + Base64.getEncoder().encodeToString(bits) + "]]";
    }

    /**
     * Decides whether a pixel prints black.
     *
     * @param rgb the opaque pixel
     * @return true when its luminance is below the threshold
     */
    private static boolean isBlack(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        int luminance = (red * RED_WEIGHT + green * GREEN_WEIGHT + blue * BLUE_WEIGHT) / WEIGHT_TOTAL;
        return luminance < BLACK_THRESHOLD;
    }
}
