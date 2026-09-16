package com.intermarche.pos.ui.admin;

import jakarta.enterprise.context.ApplicationScoped;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import org.jboss.logging.Logger;

/**
 * Resizes the touch pictures administered in the back office (BO-03-01-24): it
 * takes a base64 image (a bare payload or a {@code data:} URI produced by the
 * browser's {@code FileReader}), decodes it, scales it DOWN so its longest side
 * never exceeds {@link #MAX_DIMENSION} pixels — never up, a small picture is
 * kept as is — and re-encodes it as a PNG {@code data:} URI ready to store on
 * {@link com.intermarche.pos.domain.catalog.Product#imageData} and ship on the PRODUCTS
 * domain of the tirage. Bounding the size here is what lets a manager import a
 * batch of full-resolution photos without bloating the referential snapshot.
 * <p>
 * Serves the back-office image import only, hence its place in {@code ui.admin}
 * rather than {@code service}. Stateless and deterministic: pure unit testable.
 */
@ApplicationScoped
public class ImageResizeService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ImageResizeService.class);

    /** The maximum length, in pixels, of the longest side of a stored picture. */
    static final int MAX_DIMENSION = 128;

    /** The prefix of the base64 {@code data:} URI this service emits. */
    static final String DATA_URI_PREFIX = "data:image/png;base64,";

    /**
     * Resizes an image to the default {@link #MAX_DIMENSION} and returns it as a
     * PNG {@code data:} URI.
     *
     * @param input the source image, a bare base64 payload or a data URI, or null
     * @return the resized PNG data URI, or null when the input is null or blank
     * @throws IllegalArgumentException when the input is not a readable image
     */
    public String resizeToDataUri(String input) {
        LOGGER.info("Entering method resizeToDataUri with input: " + input);
        LOGGER.info("Exiting method resizeToDataUri");
        return resizeToDataUri(input, MAX_DIMENSION);
    }

    /**
     * Resizes an image so its longest side is at most {@code maxDimension} and
     * returns it as a PNG {@code data:} URI.
     *
     * @param input the source image, a bare base64 payload or a data URI, or null
     * @param maxDimension the maximum length of the longest side, in pixels
     * @return the resized PNG data URI, or null when the input is null or blank
     * @throws IllegalArgumentException when the input is not a readable image
     */
    String resizeToDataUri(String input, int maxDimension) {
        if (input == null || input.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(stripDataUri(input.trim()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Image illisible (base64 invalide).");
        }
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalArgumentException("Image illisible.");
        }
        if (source == null) {
            throw new IllegalArgumentException("Image illisible (format non reconnu).");
        }
        BufferedImage output = scale(source, maxDimension);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(output, "png", out);
            return DATA_URI_PREFIX + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalArgumentException("Image inencodable.");
        }
    }

    /**
     * Strips the {@code data:...;base64,} prefix of a data URI, leaving a bare
     * base64 payload untouched.
     *
     * @param value the trimmed source string
     * @return the bare base64 payload
     */
    private String stripDataUri(String value) {
        int comma = value.indexOf(',');
        if (value.startsWith("data:") && comma >= 0) {
            return value.substring(comma + 1);
        }
        return value;
    }

    /**
     * Scales an image down so its longest side is at most {@code maxDimension},
     * preserving the aspect ratio; an image already within the bound is returned
     * unchanged (no upscaling).
     *
     * @param source the decoded source image
     * @param maxDimension the maximum length of the longest side, in pixels
     * @return the scaled image, or the source when it already fits
     */
    private BufferedImage scale(BufferedImage source, int maxDimension) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxDimension) {
            return source;
        }
        double ratio = (double) maxDimension / longest;
        int newWidth = Math.max(1, (int) Math.round(width * ratio));
        int newHeight = Math.max(1, (int) Math.round(height * ratio));
        BufferedImage target = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, newWidth, newHeight, null);
        graphics.dispose();
        return target;
    }
}
