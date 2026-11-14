package com.intermarche.edge.service.fiscal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link FiscalFiles} (BO-05-01): cells, amounts, fingerprints,
 * zips and JSON strings.
 */
class FiscalFilesTest {

    /**
     * A cell drops what would cut a row, a row joins its cells, an amount has
     * two decimals.
     */
    @Test
    void cellsRowsAndAmounts() {
        assertEquals("", FiscalFiles.cell(null));
        assertEquals("a,b c d", FiscalFiles.cell("a;b\nc\rd"));
        assertEquals("1;;x\n", FiscalFiles.row(1, null, "x"));
        assertEquals("0.00", FiscalFiles.money(null));
        assertEquals("1.24", FiscalFiles.money(new BigDecimal("1.235")));
        assertNull(FiscalFiles.clip(null, 3));
        assertEquals("abc", FiscalFiles.clip("abc", 3));
        assertEquals("ab", FiscalFiles.clip("abc", 2));
    }

    /**
     * The fingerprint is the SHA-256; a runtime lacking an algorithm is a
     * failure stated as such.
     */
    @Test
    void theFingerprintIsTheSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                FiscalFiles.sha256("abc".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalStateException.class, () -> FiscalFiles.digest("NO-SUCH"));
    }

    /**
     * A zip holds the files in order, then the manifest of their fingerprints.
     *
     * @throws IOException never, the zip is in memory
     */
    @Test
    void azipHoldsTheFilesAndTheirManifest() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a.csv", "abc");
        files.put("b.txt", "é");
        byte[] zip = FiscalFiles.zip(files, LocalDateTime.of(2026, 10, 1, 9, 0));
        Map<String, String> read = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                read.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(java.util.List.of("a.csv", "b.txt", "empreintes.txt"), java.util.List.copyOf(read.keySet()));
        assertEquals("é", read.get("b.txt"));
        assertTrue(read.get("empreintes.txt").startsWith("FICHIER;SHA256\na.csv;ba7816bf"));
    }

    /**
     * A stream refusing the bytes is a failure stated as such.
     */
    @Test
    void arefusingStreamFails() {
        OutputStream refusing = new OutputStream() {
            /**
             * Refuses every byte.
             *
             * @param b the byte
             * @throws IOException always
             */
            @Override
            public void write(int b) throws IOException {
                throw new IOException("plein");
            }
        };
        assertThrows(UncheckedIOException.class,
                () -> FiscalFiles.write(Map.of("a", "b"), LocalDateTime.of(2026, 1, 1, 0, 0), refusing));
    }

    /**
     * A body is a Jackson object: quotes, backslashes and control characters
     * are escaped, null stays the JSON null, and the text parses back.
     *
     * @throws Exception never, the text being valid JSON
     */
    @Test
    void bodiesAreValidJson() throws Exception {
        String body = FiscalFiles.object().put("text", "a\"b\\c\nd").put("none", (String) null).toString();
        com.fasterxml.jackson.databind.JsonNode tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("a\"b\\c\nd", tree.get("text").asText());
        assertEquals(true, tree.get("none").isNull());
    }

    /**
     * An acknowledgement is the {@code ack} of a JSON answer, else its
     * {@code id}, else the whole answer; a text that is no JSON is kept, and
     * no body gives none.
     */
    @Test
    void theAcknowledgementIsWhatTheOperatorReads() {
        assertEquals("A-1", FiscalFiles.acknowledgement("{\"ack\":\"A-1\",\"id\":\"I\"}"));
        assertEquals("I-2", FiscalFiles.acknowledgement("{\"ack\":null,\"id\":\"I-2\"}"));
        assertEquals("{\"status\":\"ok\"}", FiscalFiles.acknowledgement(" {\"status\":\"ok\"} "));
        assertEquals("{\"id\":null}", FiscalFiles.acknowledgement("{\"id\":null}"));
        assertEquals("reçu", FiscalFiles.acknowledgement(" reçu "));
        assertNull(FiscalFiles.acknowledgement(null));
        assertNull(FiscalFiles.acknowledgement(" "));
    }
}
