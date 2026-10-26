package com.intermarche.edge.ui.hardware;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

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

    /** Prefix of a raster directive for a square logo scaled to a 192-dot head. */
    private static final String NARROW_PREFIX = "[[IMAGE 192 192 ";

    /** Bytes a 192x192 raster packs: (192 + 7) / 8 dots-per-byte times 192 rows. */
    private static final int NARROW_BITS = ((192 + 7) / 8) * 192;

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
        clearStaticMap("DIRECTIVES");
        setStatic("dataUriLoaded", false);
        setStatic("dataUri", null);
        // BO-03-03-15: the single-entry cache of the ADMINISTERED image, so a
        // scenario never reads the picture the previous one imported.
        setStatic("administeredSource", null);
        setStatic("administeredWidth", 0);
        setStatic("administeredDirective", null);
    }

    /**
     * Encodes an image as the PNG {@code data:} URI the back office
     * distributes ({@code BO-03-03-15}).
     *
     * @param image the image to encode
     * @return the data URI
     * @throws Exception when the image cannot be written
     */
    private String dataUriOf(BufferedImage image) throws Exception {
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
    }

    /**
     * Empties a private static {@link java.util.Map} cache of {@link ReceiptLogo} by
     * reflection; the field is {@code final}, so it is cleared in place rather than
     * reassigned.
     *
     * @param name the field name
     * @throws Exception when the field cannot be accessed
     */
    private void clearStaticMap(String name) throws Exception {
        Field field = ReceiptLogo.class.getDeclaredField(name);
        field.setAccessible(true);
        ((java.util.Map<?, ?>) field.get(null)).clear();
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
            assertEquals(directive, ReceiptLogo.directive());
            imageIo.verify(() -> ImageIO.read(any(InputStream.class)), org.mockito.Mockito.times(1));
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

    // --------------------------------------------------
    // directive(int)
    // --------------------------------------------------

    /**
     * Proves (width ternary true arm) a width below one packed byte falls back to
     * the default: {@code directive(4)} rasterises the readable logo at the 384-dot
     * paper exactly as the no-argument call does.
     *
     * @throws Exception when a cache reset fails
     */
    @Test
    void directiveFallsBackToDefaultWhenWidthTooSmall() throws Exception {
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenReturn(mixedInkImage());
            String directive = ReceiptLogo.directive(4);
            assertTrue(directive.startsWith(SQUARE_PREFIX));
            assertTrue(directive.endsWith(DIRECTIVE_SUFFIX));
            String payload = directive.substring(SQUARE_PREFIX.length(),
                    directive.length() - DIRECTIVE_SUFFIX.length());
            assertEquals(SQUARE_BITS, Base64.getDecoder().decode(payload).length);
        }
    }

    /**
     * Proves (width ternary false arm, cached absent arm) an administered head
     * width flows through the rasteriser: {@code directive(192)} scales the readable
     * square logo to a 192x192 raster whose decoded payload is exactly 4608 bytes.
     *
     * @throws Exception when a cache reset fails
     */
    @Test
    void directiveRasterisesAtAdministeredWidth() throws Exception {
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenReturn(mixedInkImage());
            String directive = ReceiptLogo.directive(192);
            assertTrue(directive.startsWith(NARROW_PREFIX));
            assertTrue(directive.endsWith(DIRECTIVE_SUFFIX));
            String payload = directive.substring(NARROW_PREFIX.length(),
                    directive.length() - DIRECTIVE_SUFFIX.length());
            assertEquals(NARROW_BITS, Base64.getDecoder().decode(payload).length);
        }
    }

    /**
     * Proves the directive cache is keyed on the width: two distinct widths are each
     * decoded once, and a repeat of the first width is served from the cache without
     * a third read.
     *
     * @throws Exception when a cache reset fails
     */
    @Test
    void directiveCachesPerWidth() throws Exception {
        resetCaches();
        try (MockedStatic<ImageIO> imageIo = mockStatic(ImageIO.class)) {
            imageIo.when(() -> ImageIO.read(any(InputStream.class))).thenReturn(mixedInkImage());
            String narrow = ReceiptLogo.directive(192);
            String wide = ReceiptLogo.directive(384);
            String narrowAgain = ReceiptLogo.directive(192);
            assertTrue(narrow.startsWith(NARROW_PREFIX));
            assertTrue(wide.startsWith(SQUARE_PREFIX));
            assertEquals(narrow, narrowAgain);
            imageIo.verify(() -> ImageIO.read(any(InputStream.class)), times(2));
        }
    }

    /**
     * BO-03-03-15: an administered image is rasterised to the paper exactly
     * as the installation's file is — cropped to its ink, scaled to the
     * width, packed into dots — and a bare base64 payload reads the same as
     * a {@code data:} URI.
     *
     * @throws Exception when the fixture cannot be built
     */
    @Test
    void anAdministeredImageIsRasterisedToThePaper() throws Exception {
        resetCaches();
        String uri = dataUriOf(mixedInkImage());
        String directive = ReceiptLogo.directive(uri, 384);
        assertTrue(directive.startsWith(SQUARE_PREFIX));
        assertTrue(directive.endsWith(DIRECTIVE_SUFFIX));
        assertEquals(SQUARE_BITS, Base64.getDecoder().decode(
                directive.substring(SQUARE_PREFIX.length(),
                        directive.length() - DIRECTIVE_SUFFIX.length())).length);
        resetCaches();
        String bare = uri.substring(uri.indexOf(',') + 1);
        assertEquals(directive, ReceiptLogo.directive(bare, 384));
    }

    /**
     * BO-03-03-15: the administered rasterisation is memoised on the picture
     * AND the width — the same pair answers the cached string, a different
     * width recomputes.
     *
     * @throws Exception when the fixture cannot be built
     */
    @Test
    void theAdministeredRasterIsMemoisedOnPictureAndWidth() throws Exception {
        resetCaches();
        String uri = dataUriOf(mixedInkImage());
        String first = ReceiptLogo.directive(uri, 384);
        assertSame(first, ReceiptLogo.directive(uri, 384));
        String narrow = ReceiptLogo.directive(uri, 192);
        assertTrue(narrow.startsWith("[[IMAGE 192 192 "));
        assertNotEquals(first, narrow);
    }

    /**
     * BO-03-03-15: a width narrower than one packed byte falls back on the
     * default paper width rather than failing a receipt, and the one-argument
     * entry prints at that same default.
     *
     * @throws Exception when the fixture cannot be built
     */
    @Test
    void anImpossibleWidthFallsBackOnThePaper() throws Exception {
        resetCaches();
        String uri = dataUriOf(mixedInkImage());
        assertTrue(ReceiptLogo.directive(uri, 4).startsWith(SQUARE_PREFIX));
        resetCaches();
        assertTrue(ReceiptLogo.directive(uri).startsWith(SQUARE_PREFIX));
    }

    /**
     * BO-03-03-15: an administered picture that is not readable at all —
     * invalid base64, or bytes that are not an image — prints nothing, and
     * an entirely white one prints nothing either. None of the three fails
     * the document.
     *
     * @throws Exception when the fixture cannot be built
     */
    @Test
    void anUnprintableAdministeredImagePrintsNothing() throws Exception {
        resetCaches();
        assertEquals("", ReceiptLogo.directive("data:image/png;base64,!!!!", 384));
        resetCaches();
        assertEquals("", ReceiptLogo.directive("data:image/png;base64,AAAA", 384));
        resetCaches();
        assertEquals("", ReceiptLogo.directive(dataUriOf(whiteImage()), 384));
    }

    /**
     * BO-03-03-15: no administered picture falls back on the installation's
     * file, for the raster as for the page rendering.
     *
     * @throws Exception when the caches cannot be reset
     */
    @Test
    void noAdministeredImageFallsBackOnTheInstallation() throws Exception {
        resetCaches();
        assertEquals(ReceiptLogo.directive(384), ReceiptLogo.directive(null, 384));
        assertEquals(ReceiptLogo.directive(384), ReceiptLogo.directive("   ", 384));
        assertEquals(ReceiptLogo.dataUri(), ReceiptLogo.dataUri(null));
        assertEquals(ReceiptLogo.dataUri(), ReceiptLogo.dataUri("  "));
    }

    /**
     * BO-03-03-15: a page-rendered document is handed the administered
     * picture as it stands, a bare payload being given back its prefix.
     *
     * @throws Exception when the fixture cannot be built
     */
    @Test
    void thePageRenderingHandsOverTheAdministeredPicture() throws Exception {
        resetCaches();
        String uri = dataUriOf(mixedInkImage());
        assertEquals(uri, ReceiptLogo.dataUri(uri));
        assertEquals("data:image/png;base64,ABC", ReceiptLogo.dataUri("ABC"));
    }
    /** BO-03-03-15: malformed data URIs without a comma cannot become a printable logo. */
    @Test
    void dataUriWithoutPayloadSeparatorPrintsNothing() throws Exception {
        resetCaches();
        assertEquals("", ReceiptLogo.directive("data:image/png;base64", 384));
    }

    /** BO-03-03-15: a failing resource close, alone or during decoding failure, never prevents a receipt. */
    @Test
    void resourceCloseFailuresLeaveBothLogoOutputsEmpty() throws Exception {
        for (String entry : new String[]{"directive", "dataUri"}) {
            for (boolean decodeFails : new boolean[]{false, true}) {
                InputStream stream = org.mockito.Mockito.mock(InputStream.class);
                org.mockito.Mockito.doThrow(new IOException("close failed")).when(stream).close();
                Class<?> isolated = logoWithResource(stream);
                try (var images = mockStatic(ImageIO.class)) {
                    if (decodeFails) images.when(() -> ImageIO.read(stream)).thenThrow(new IOException("decode failed"));
                    else images.when(() -> ImageIO.read(stream)).thenReturn(mixedInkImage());
                    assertEquals("", isolated.getMethod(entry).invoke(null));
                }
                org.mockito.Mockito.verify(stream).close();
            }
        }
    }

    /**
     * Loads a fresh logo utility whose classpath resource is the supplied stream.
     * The original code source keeps normal instrumentation active without changing global caches.
     * @param stream mocked classpath resource
     * @return independently initialized logo class
     * @throws Exception when the class bytes cannot be loaded
     */
    private Class<?> logoWithResource(InputStream stream) throws Exception {
        String name = ReceiptLogo.class.getName();
        ClassLoader parent = ReceiptLogo.class.getClassLoader();
        byte[] bytes;
        try (var original = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
            bytes = original.readAllBytes();
        }
        ClassLoader loader = new ClassLoader(parent) {
            /** Defines only the logo utility locally so all ImageIO collaborators retain their identity. */
            @Override
            protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(requested)) return super.loadClass(requested, resolve);
                Class<?> loaded = findLoadedClass(requested);
                if (loaded == null) loaded = defineClass(requested, bytes, 0, bytes.length, ReceiptLogo.class.getProtectionDomain());
                if (resolve) resolveClass(loaded);
                return loaded;
            }

            /** Supplies the failure-controlled classpath logo and delegates every other resource. */
            @Override
            public InputStream getResourceAsStream(String resource) {
                return "print/logo.png".equals(resource) ? stream : super.getResourceAsStream(resource);
            }
        };
        return Class.forName(name, true, loader);
    }

}
