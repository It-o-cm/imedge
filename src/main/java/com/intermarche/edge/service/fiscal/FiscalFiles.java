package com.intermarche.edge.service.fiscal;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The small writing rules every fiscal file of the node shares
 * ({@code BO-05-01}): a CSV cell, an amount, a zip, a fingerprint, a JSON
 * string. One place, so the archives, the e-invoices, the e-reports and the
 * SAF-T sends never disagree on how an amount or a fingerprint is written.
 */
public final class FiscalFiles {

    /**
     * Not instantiable: static helpers only.
     */
    private FiscalFiles() {
    }

    /**
     * Writes a CSV cell: a separator or a line break inside a value would cut
     * the row, so they are replaced.
     *
     * @param value the value, possibly null
     * @return the cell, empty for null
     */
    public static String cell(Object value) {
        return value == null ? "" : value.toString().replace(';', ',').replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * Joins cells into one CSV row, its line break included.
     *
     * @param values the values of the row
     * @return the row
     */
    public static String row(Object... values) {
        StringBuilder row = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            row.append(index == 0 ? "" : ";").append(cell(values[index]));
        }
        return row.append('\n').toString();
    }

    /**
     * Writes an amount with two decimals and a point.
     *
     * @param value the amount, null for zero
     * @return the written amount
     */
    public static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * The SHA-256 fingerprint of some bytes.
     *
     * @param bytes the bytes
     * @return the fingerprint, 64 hexadecimal characters
     */
    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest("SHA-256").digest(bytes));
    }

    /**
     * A digest algorithm; SHA-256 ships with every Java runtime.
     *
     * @param algorithm the algorithm's name
     * @return the digest
     * @throws IllegalStateException when the runtime lacks the algorithm
     */
    static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException(absent);
        }
    }

    /**
     * Packs files into a zip, each entry dated at the instant given, and a
     * manifest of their fingerprints after them.
     *
     * @param files the file names and their text, in order
     * @param at the instant the entries carry
     * @return the zip's bytes
     */
    public static byte[] zip(Map<String, String> files, LocalDateTime at) {
        Map<String, String> all = new LinkedHashMap<>(files);
        StringBuilder manifest = new StringBuilder("FICHIER;SHA256\n");
        for (Map.Entry<String, String> file : files.entrySet()) {
            manifest.append(file.getKey()).append(';')
                    .append(sha256(file.getValue().getBytes(StandardCharsets.UTF_8))).append('\n');
        }
        all.put("empreintes.txt", manifest.toString());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        write(all, at, bytes);
        return bytes.toByteArray();
    }

    /**
     * Writes the entries of a zip to a stream.
     *
     * @param all the file names and their text, in order
     * @param at the instant the entries carry
     * @param target the stream the zip is written to
     * @throws UncheckedIOException when the stream refuses the bytes
     */
    static void write(Map<String, String> all, LocalDateTime at, java.io.OutputStream target) {
        try (ZipOutputStream zip = new ZipOutputStream(target, StandardCharsets.UTF_8)) {
            long time = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            for (Map.Entry<String, String> file : all.entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(time);
                zip.putNextEntry(entry);
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException refused) {
            throw new UncheckedIOException(refused);
        }
    }

    /**
     * Starts a JSON object for a body sent to a fiscal platform: Jackson writes
     * it, so every text is escaped and the answer is always valid JSON.
     *
     * @return an empty JSON object
     */
    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    /**
     * The acknowledgement a platform answered, as an operator reads it: the
     * {@code ack} or {@code id} of a JSON answer, else the answer as it came.
     *
     * @param body the answer's body, possibly null
     * @return the acknowledgement, null for no body
     */
    public static String acknowledgement(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode answer = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(body);
            com.fasterxml.jackson.databind.JsonNode named = answer.hasNonNull("ack") ? answer.get("ack")
                    : answer.get("id");
            return named == null || named.isNull() ? body.trim() : named.asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
            return body.trim();
        }
    }

    /**
     * Clips a text to a column's length.
     *
     * @param value the text, possibly null
     * @param length the column's length
     * @return the text, at most that long, null kept null
     */
    public static String clip(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
