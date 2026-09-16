package com.intermarche.pos.ui.hardware;

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

import org.jboss.logging.Logger;

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
     * 48 mm, a header that reads without eating the roll. The logo is scaled to it in
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

    /** The directive, computed once; empty when there is no printable logo. */
    private static String directive;

    /** Whether the logo has been looked for already. */
    private static boolean loaded;

    /** The logo as a data URI, computed once; empty when there is no logo file. */
    private static String dataUri;

    /** Whether the data URI has been built already. */
    private static boolean dataUriLoaded;

    /** Not instantiable: the logo is a single classpath resource. */
    private ReceiptLogo() {
    }

    /**
     * The print directive carrying the logo.
     *
     * @return the directive line WITHOUT its newline, or empty when this till has no
     *         logo or the file could not be read
     */
    public static synchronized String directive() {
        if (!loaded) {
            loaded = true;
            directive = read().orElse("");
        }
        return directive;
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
        try (InputStream stream = ReceiptLogo.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                LOGGER.infof("Aucun logo (%s absent) : document sans logo", RESOURCE);
                return Optional.empty();
            }
            BufferedImage source = ImageIO.read(stream);
            if (source == null) {
                LOGGER.warnf("Logo illisible (%s) : document sans logo", RESOURCE);
                return Optional.empty();
            }
            BufferedImage fitted = fit(source);
            if (fitted == null) {
                LOGGER.warnf("Logo entierement blanc (%s) : document sans logo", RESOURCE);
                return Optional.empty();
            }
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            ImageIO.write(fitted, "png", png);
            return Optional.of(png.toByteArray());
        } catch (IOException e) {
            LOGGER.warnf(e, "Logo illisible (%s) : document sans logo", RESOURCE);
            return Optional.empty();
        }
    }

    /**
     * Reads the logo and encodes it.
     *
     * @return the directive, or empty when there is nothing printable
     */
    private static Optional<String> read() {
        try (InputStream stream = ReceiptLogo.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                LOGGER.infof("Aucun logo de ticket (%s absent) : en-tete texte seul", RESOURCE);
                return Optional.empty();
            }
            BufferedImage source = ImageIO.read(stream);
            if (source == null) {
                LOGGER.warnf("Logo de ticket illisible (%s) : en-tete texte seul", RESOURCE);
                return Optional.empty();
            }
            BufferedImage fitted = fit(source);
            if (fitted == null) {
                LOGGER.warnf("Logo de ticket entierement blanc (%s) : en-tete texte seul", RESOURCE);
                return Optional.empty();
            }
            return Optional.of(encode(fitted));
        } catch (IOException e) {
            LOGGER.warnf(e, "Logo de ticket illisible (%s) : en-tete texte seul", RESOURCE);
            return Optional.empty();
        }
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
     * @return an opaque, paper-width image, or {@code null} when the file has no ink
     */
    private static BufferedImage fit(BufferedImage source) {
        BufferedImage opaque = flatten(source);
        Rectangle ink = inkBounds(opaque);
        if (ink == null) {
            return null;
        }
        BufferedImage cropped = opaque.getSubimage(ink.x, ink.y, ink.width, ink.height);
        int height = Math.max(1, cropped.getHeight() * PRINT_WIDTH / cropped.getWidth());
        BufferedImage fitted = new BufferedImage(PRINT_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = fitted.createGraphics();
        canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        canvas.setColor(Color.WHITE);
        canvas.fillRect(0, 0, PRINT_WIDTH, height);
        canvas.drawImage(cropped, 0, 0, PRINT_WIDTH, height, null);
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
