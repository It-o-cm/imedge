package com.intermarche.pos.ui.admin;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ImageResizeService}.
 * <p>
 * A stateless, deterministic image transformer: tests feed it PNG payloads
 * generated in-memory (no fixture files), then decode the returned data URI to
 * assert the resulting pixel dimensions. Every branch is exercised — the
 * null/blank guard (both legs), the {@code data:} prefix strip (with prefix,
 * without prefix, and a {@code data:} string with no comma), the invalid-base64
 * and unreadable-image rejections, and the downscale versus keep-as-is arms in
 * both landscape and portrait orientations.
 * <p>
 * Residue: the two {@link IOException} catch blocks around
 * {@link ImageIO#read} and {@link ImageIO#write} are unreachable with in-memory
 * byte streams (a {@link ByteArrayInputStream}/{@link ByteArrayOutputStream}
 * never throws {@link IOException}); they are defensive and left uncovered.
 */
class ImageResizeServiceTest {

    /**
     * Builds a solid-red PNG of the given size and returns its bare base64.
     *
     * @param width the image width
     * @param height the image height
     * @return the base64 of the encoded PNG
     * @throws IOException never, in practice (in-memory stream)
     */
    private String pngBase64(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /**
     * Decodes a PNG data URI returned by the service back into an image.
     *
     * @param dataUri the returned data URI
     * @return the decoded image
     * @throws IOException never, in practice (in-memory stream)
     */
    private BufferedImage decode(String dataUri) throws IOException {
        assertTrue(dataUri.startsWith(ImageResizeService.DATA_URI_PREFIX));
        String base64 = dataUri.substring(ImageResizeService.DATA_URI_PREFIX.length());
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
    }

    /**
     * A null or blank input yields null on both legs of the guard.
     */
    @Test
    void nullOrBlankReturnsNull() {
        ImageResizeService service = new ImageResizeService();
        assertNull(service.resizeToDataUri(null));
        assertNull(service.resizeToDataUri("   "));
    }

    /**
     * A landscape image larger than the bound is scaled down keeping the aspect
     * ratio (bare base64 input, no prefix).
     *
     * @throws IOException never, in practice
     */
    @Test
    void resizesLargeLandscape() throws IOException {
        ImageResizeService service = new ImageResizeService();
        BufferedImage result = decode(service.resizeToDataUri(pngBase64(200, 100), 50));
        assertEquals(50, result.getWidth());
        assertEquals(25, result.getHeight());
    }

    /**
     * A portrait image larger than the bound is scaled down keeping the aspect
     * ratio (the height-longest arm).
     *
     * @throws IOException never, in practice
     */
    @Test
    void resizesLargePortrait() throws IOException {
        ImageResizeService service = new ImageResizeService();
        BufferedImage result = decode(service.resizeToDataUri(pngBase64(100, 200), 50));
        assertEquals(25, result.getWidth());
        assertEquals(50, result.getHeight());
    }

    /**
     * An image already within the default bound is kept at its original size
     * (the no-upscale arm, through the public default-dimension entry point).
     *
     * @throws IOException never, in practice
     */
    @Test
    void keepsSmallImageAtDefaultBound() throws IOException {
        ImageResizeService service = new ImageResizeService();
        BufferedImage result = decode(service.resizeToDataUri(pngBase64(10, 10)));
        assertEquals(10, result.getWidth());
        assertEquals(10, result.getHeight());
    }

    /**
     * BO-03-01-24: an oversized image is scaled down to the DEFAULT bound the
     * register actually applies, through the single-argument entry point the
     * touch screen calls.
     *
     * <p>Every other case here passes its own {@code maxDimension}, so none of
     * them notices what the default is: raising it to 4096 leaves them green.
     * This case pins the figure the production path uses — 300×200 comes back
     * 128×85 — and the one below pins its exact frontier.
     *
     * @throws IOException never, in practice
     */
    @Test
    void resizesToTheDefaultMaxDimension() throws IOException {
        ImageResizeService service = new ImageResizeService();
        BufferedImage result = decode(service.resizeToDataUri(pngBase64(300, 200)));
        assertEquals(128, result.getWidth());
        assertEquals(85, result.getHeight());
    }

    /**
     * BO-03-01-24: the default bound is INCLUSIVE — a square one pixel over it
     * is scaled, and the scaling lands exactly on the bound.
     *
     * @throws IOException never, in practice
     */
    @Test
    void defaultMaxDimensionBoundIsInclusive() throws IOException {
        ImageResizeService service = new ImageResizeService();
        BufferedImage scaled = decode(service.resizeToDataUri(pngBase64(129, 129)));
        assertEquals(128, scaled.getWidth());
        BufferedImage untouched = decode(service.resizeToDataUri(pngBase64(128, 128)));
        assertEquals(128, untouched.getWidth());
    }

    /**
     * A {@code data:} URI input has its prefix stripped before decoding.
     *
     * @throws IOException never, in practice
     */
    @Test
    void acceptsDataUriPrefix() throws IOException {
        ImageResizeService service = new ImageResizeService();
        String input = "data:image/png;base64," + pngBase64(8, 8);
        BufferedImage result = decode(service.resizeToDataUri(input, 50));
        assertEquals(8, result.getWidth());
        assertEquals(8, result.getHeight());
    }

    /**
     * A {@code data:} string with no comma is not treated as a prefixed URI and
     * fails base64 decoding (the prefix-true, comma-false arm).
     */
    @Test
    void rejectsDataStringWithoutComma() {
        ImageResizeService service = new ImageResizeService();
        assertThrows(IllegalArgumentException.class, () -> service.resizeToDataUri("data:@@@"));
    }

    /**
     * A payload that is not valid base64 is rejected (the decode catch arm).
     */
    @Test
    void rejectsInvalidBase64() {
        ImageResizeService service = new ImageResizeService();
        assertThrows(IllegalArgumentException.class, () -> service.resizeToDataUri("@@@not-base64@@@"));
    }

    /**
     * Valid base64 that does not decode to a recognized image is rejected (the
     * null-source arm).
     */
    @Test
    void rejectsNonImagePayload() {
        ImageResizeService service = new ImageResizeService();
        String notImage = Base64.getEncoder().encodeToString("hello world".getBytes());
        assertThrows(IllegalArgumentException.class, () -> service.resizeToDataUri(notImage));
    }
}
