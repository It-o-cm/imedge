package com.intermarche.pos.ui.hardware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link ReceiptLogo}.
 *
 * <p>{@code ReceiptLogo} is a stateless-looking utility with two memoised entry
 * points, {@link ReceiptLogo#directive()} (raster directive for the thermal head)
 * and {@link ReceiptLogo#dataUri()} (a {@code data:} URI for a rendered page).
 * Both read the SAME classpath resource {@code /print/logo.png} once and cache the
 * result in private static fields; the private pipeline
 * ({@code read}/{@code croppedPng} → {@code fit} → {@code flatten} →
 * {@code inkBounds} → {@code encode} → {@code isBlack}) is exercised only through
 * those two methods.
 *
 * <p>Two collaborators are intercepted. {@link javax.imageio.ImageIO#read} is
 * stubbed with {@link org.mockito.Mockito#mockStatic}: it decides the
 * {@code source == null} arm (returns {@code null}), the readable-image arm
 * (returns a hand-built {@link BufferedImage}), the entirely-white arm (returns a
 * white image, so {@code inkBounds} yields {@code null} and {@code fit} returns
 * {@code null}) and the {@code IOException} catch arm (throws). Under that static
 * mock {@link javax.imageio.ImageIO#write} is an unstubbed no-op returning
 * {@code false} and writing nothing, so {@code croppedPng}'s byte array is empty
 * by construction and the resulting {@code data:} URI is exactly its prefix — a
 * deterministic, absolute value. The {@code stream == null} arm (resource absent)
 * is reached by temporarily moving the packaged {@code target/classes/print/logo.png}
 * aside and restoring it in a {@code finally}, the only way to make the class's own
 * {@code getResourceAsStream} return {@code null} without touching production code.
 *
 * <p>The private static caches are cleared by reflection at the start of every test
 * so each scenario is fully isolated. Assertions are on absolute expected values:
 * the raster directive of a 20x20-ink square scaled to the 384-dot paper is exactly
 * {@code "[[IMAGE 384 384 <base64> ]]"} whose decoded payload is exactly
 * {@code (384/8) * 384 = 18432} bytes, and an absent logo yields the empty string.
 *
 * <p>Branch inventory covered: {@code directive()} loaded/not-loaded arms;
 * {@code dataUri()} loaded/not-loaded arms and the {@code map}/{@code orElse}
 * (present/empty) arms; {@code read()} and {@code croppedPng()} each with their
 * {@code stream == null}, {@code source == null}, {@code fitted == null}, success
 * and {@code IOException} arms; {@code fit()} {@code ink == null}/non-null arms;
 * {@code inkBounds()} the {@code isBlack} true/false arms and the
 * {@code right < 0 ? null : rectangle} ternary; {@code encode()} the {@code isBlack}
 * true/false arms; {@code isBlack()} the below/at-threshold luminance arms.
 */
class ReceiptLogoTest {

    /** Prefix of a raster directive for a logo scaled to the 384-dot paper as a square. */
    private static final String SQUARE_PREFIX = "[[IMAGE 384 384 ";

    /** Suffix closing every raster directive. */
    private static final String DIRECTIVE_SUFFIX = "]]";

    /** Bytes a 384x384 raster packs: (384 + 7) / 8 dots-per-byte times 384 rows. */
    private static final int SQUARE_BITS = ((384 + 7) / 8) * 384;

    /**
     * Sets a private static field of {@link ReceiptLogo} by reflection.
     *
     * @param name the field name
     * @param value the value to assign
     * @throws Exception when the field cannot be accessed
     */
    private void setStatic(String name, Object value) throws Exception {
        Field field = ReceiptLogo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    /**
     * Clears the memoised caches so each scenario recomputes from scratch.
     *
     * @throws Exception when a cache field cannot be reset
     */
    private void resetCaches() throws Exception {
        setStatic("loaded", false);
        setStatic("directive", null);
        setStatic("dataUriLoaded", false);
        setStatic("dataUri", null);
    }

    /**
     * Builds a 40x40 image whose ink is a 20x20 square carrying both black and
     * white pixels, so cropping yields a square (printed height equals the 384-dot
     * width) and the packed raster exercises both {@code isBlack} arms.
     *
     * @return a readable image with mixed ink
     */
    private BufferedImage mixedInkImage() {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = image.createGraphics();
        canvas.setColor(Color.WHITE);
        canvas.fillRect(0, 0, 40, 40);
        canvas.setColor(Color.BLACK);
        canvas.fillRect(10, 20, 20, 10);
        canvas.fillRect(10, 10, 20, 1);
        canvas.dispose();
        return image;
    }

    /**
     * Builds an entirely white image, so {@code inkBounds} finds no ink.
     *
     * @return a white image with no printable pixel
     */
    private BufferedImage whiteImage() {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = image.createGraphics();
        canvas.setColor(Color.WHITE);
        canvas.fillRect(0, 0, 8, 8);
        canvas.dispose();
        return image;
    }

    /**
     * Resolves the packaged logo to its filesystem path (the classpath entry is the
     * {@code target/classes} directory under {@code mvn test}).
     *
     * @return the logo file path
     * @throws Exception when the resource URL cannot be resolved
     */
    private Path logoPath() throws Exception {
        URL url = ReceiptLogo.class.getResource("/print/logo.png");
        return Paths.get(url.toURI());
    }

    // --------------------------------------------------
    // directive()
    // --------------------------------------------------

    /**
     * Proves (loaded false arm, read success, fit non-null, encode both isBlack
     * arms) the readable logo yields the raster directive of a 384x384 square whose
     * decoded payload is exactly 18432 bytes.
     *
     * @throws Exception when a cache reset fails
     */
    @Test
    void directiveEncodesReadableLogoAsRaster() throws Exception {
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenReturn(mixedInkImage());
            String directive = ReceiptLogo.directive();
            assertTrue(directive.startsWith(SQUARE_PREFIX));
            assertTrue(directive.endsWith(DIRECTIVE_SUFFIX));
            String payload = directive.substring(SQUARE_PREFIX.length(),
                    directive.length() - DIRECTIVE_SUFFIX.length());
            byte[] bits = Base64.getDecoder().decode(payload);
            assertEquals(SQUARE_BITS, bits.length);
        }
    }

    /**
     * Proves (source == null arm) an undecodable file yields no directive.
     *
     * @throws Exception when a cache reset fails
     */
    @Test
    void directiveIsEmptyWhenImageUndecodable() throws Exception {
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenReturn(null);
            assertEquals("", ReceiptLogo.directive());
        }
    }

    /**
     * Proves (fit == null arm via inkBounds null ternary) an entirely white logo
     * yields no directive.
     *
     * @throws Exception when a cache reset fails
     */
    @Test
    void directiveIsEmptyWhenLogoIsAllWhite() throws Exception {
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenReturn(whiteImage());
            assertEquals("", ReceiptLogo.directive());
        }
    }

    /**
     * Proves (IOException catch arm) a read failure yields no directive.
     *
     * @throws Exception when a cache reset fails
     */
    @Test
    void directiveIsEmptyWhenReadThrows() throws Exception {
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenThrow(new IOException("boom"));
            assertEquals("", ReceiptLogo.directive());
        }
    }

    /**
     * Proves (loaded true arm) the directive is computed once: a second call
     * returns the cached value without re-reading the image.
     *
     * @throws Exception when a cache reset fails
     */
    @Test
    void directiveIsComputedOnlyOnce() throws Exception {
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenReturn(mixedInkImage());
            String first = ReceiptLogo.directive();
            String second = ReceiptLogo.directive();
            assertEquals(first, second);
            imageIo.verify(() -> ImageIO.read(any(InputStream.class)), times(1));
        }
    }

    /**
     * Proves (stream == null arm) an absent resource yields no directive: the
     * packaged logo is moved aside for the call and restored afterwards.
     *
     * @throws Exception when a cache reset or file move fails
     */
    @Test
    void directiveIsEmptyWhenResourceAbsent() throws Exception {
        resetCaches();
        Path logo = logoPath();
        Path parked = logo.resolveSibling("logo.png.parked");
        Files.move(logo, parked, StandardCopyOption.REPLACE_EXISTING);
        try {
            assertEquals("", ReceiptLogo.directive());
        } finally {
            Files.move(parked, logo, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // --------------------------------------------------
    // dataUri()
    // --------------------------------------------------

    /**
     * Proves (dataUriLoaded false arm, croppedPng success, map present arm) a
     * readable logo yields a PNG {@code data:} URI. Under the static mock
     * {@code ImageIO.write} is a no-op writing no bytes, so the encoded payload is
     * empty and the URI is exactly its prefix.
     *
     * @throws Exception when a cache reset fails
     */
    @Test
    void dataUriEncodesReadableLogoAsPngUri() throws Exception {
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenReturn(mixedInkImage());
            assertEquals("data:image/png;base64,", ReceiptLogo.dataUri());
        }
    }

    /**
     * Proves (source == null arm and orElse empty arm) an undecodable file yields no
     * data URI.
     *
     * @throws Exception when a cache reset fails
     */
    @Test
    void dataUriIsEmptyWhenImageUndecodable() throws Exception {
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenReturn(null);
            assertEquals("", ReceiptLogo.dataUri());
        }
    }

    /**
     * Proves (fitted == null arm of croppedPng) an entirely white logo yields no
     * data URI.
     *
     * @throws Exception when a cache reset fails
     */
    @Test
    void dataUriIsEmptyWhenLogoIsAllWhite() throws Exception {
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenReturn(whiteImage());
            assertEquals("", ReceiptLogo.dataUri());
        }
    }

    /**
     * Proves (IOException catch arm of croppedPng) a read failure yields no data URI.
     *
     * @throws Exception when a cache reset fails
     */
    @Test
    void dataUriIsEmptyWhenReadThrows() throws Exception {
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenThrow(new IOException("boom"));
            assertEquals("", ReceiptLogo.dataUri());
        }
    }

    /**
     * Proves (dataUriLoaded true arm) the data URI is computed once: a second call
     * returns the cached value without re-reading the image.
     *
     * @throws Exception when a cache reset fails
     */
    @Test
    void dataUriIsComputedOnlyOnce() throws Exception {
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenReturn(mixedInkImage());
            String first = ReceiptLogo.dataUri();
            String second = ReceiptLogo.dataUri();
            assertEquals(first, second);
            imageIo.verify(() -> ImageIO.read(any(InputStream.class)), times(1));
        }
    }

    /**
     * Proves (stream == null arm of croppedPng) an absent resource yields no data
     * URI: the packaged logo is moved aside for the call and restored afterwards.
     *
     * @throws Exception when a cache reset or file move fails
     */
    @Test
    void dataUriIsEmptyWhenResourceAbsent() throws Exception {
        resetCaches();
        Path logo = logoPath();
        Path parked = logo.resolveSibling("logo.png.parked");
        Files.move(logo, parked, StandardCopyOption.REPLACE_EXISTING);
        try {
            assertEquals("", ReceiptLogo.dataUri());
        } finally {
            Files.move(parked, logo, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Guards against a false green on the caching tests: a fresh, uncached read of
     * an undecodable file must return empty rather than a stale non-empty value.
     *
     * @throws Exception when a cache reset fails
     */
    @Test
    void directiveRecomputesAfterCacheReset() throws Exception {
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenReturn(mixedInkImage());
            assertFalse(ReceiptLogo.directive().isEmpty());
        }
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenReturn(null);
            assertEquals("", ReceiptLogo.directive());
        }
    }
}
