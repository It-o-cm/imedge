package com.intermarche.edge.ui.admin;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads the article codes of a list a user uploads onto a group of articles
 * (BO-03-01-16): an Excel workbook or a CSV / plain-text list.
 *
 * <p>Written by hand, with no library added to the build, like the workbook
 * writer of the reports: an {@code .xlsx} is a ZIP of XML parts, and what a
 * list of articles needs of it is the text of the cells of its first sheet —
 * the shared strings a cell points to, the inline strings, and the numbers,
 * which is how Excel stores an EAN typed in a cell. Formatting, formulas and
 * the other sheets are of no interest here.
 *
 * <p>Anything that is not a ZIP is read as text, every token between
 * whitespace, commas and semicolons being one code: a CSV exported from any
 * tool, or a list typed one code per line.
 *
 * <p>The XML reader refuses DTDs and external entities: the file comes from a
 * user's workstation and is never trusted to point the node anywhere.
 */
public final class ArticleListReader {

    /** The ZIP signature every workbook starts with. */
    private static final byte[] ZIP_MAGIC = {'P', 'K'};

    /** The part holding the strings the cells share. */
    private static final String SHARED_STRINGS = "xl/sharedStrings.xml";

    /** The folder of the worksheets, the first one in name order being read. */
    private static final String SHEETS = "xl/worksheets/sheet";

    /**
     * Not instantiable: reading is a function.
     */
    private ArticleListReader() {
    }

    /**
     * Reads every code an uploaded list carries, in order.
     *
     * @param file the uploaded content, possibly null
     * @return the non-blank codes, trimmed, empty for an empty or absent file
     * @throws IOException when the workbook cannot be read
     */
    public static List<String> read(InputStream file) throws IOException {
        if (file == null) {
            return List.of();
        }
        byte[] bytes = file.readAllBytes();
        if (bytes.length >= 2 && bytes[0] == ZIP_MAGIC[0] && bytes[1] == ZIP_MAGIC[1]) {
            return workbookCells(bytes);
        }
        return textTokens(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Splits a text list into its codes.
     *
     * @param text the decoded file, a byte-order mark and quotes tolerated
     * @return the non-blank tokens
     */
    static List<String> textTokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : text.replace("﻿", "").replace("\"", "").split("[\\s,;]+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * Reads the cells of the first sheet of a workbook.
     *
     * @param bytes the workbook
     * @return the non-blank cell texts, in document order
     * @throws IOException when the archive or its XML cannot be read
     */
    static List<String> workbookCells(byte[] bytes) throws IOException {
        Map<String, byte[]> parts = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals(SHARED_STRINGS) || name.startsWith(SHEETS)) {
                    ByteArrayOutputStream content = new ByteArrayOutputStream();
                    zip.transferTo(content);
                    parts.put(name, content.toByteArray());
                }
            }
        }
        String firstSheet = null;
        for (String name : parts.keySet()) {
            if (name.startsWith(SHEETS)) {
                firstSheet = name;
                break;
            }
        }
        if (firstSheet == null) {
            throw new IOException("Classeur sans feuille");
        }
        try {
            byte[] shared = parts.get(SHARED_STRINGS);
            List<String> strings = shared == null ? List.of() : sharedStrings(shared);
            return sheetCells(parts.get(firstSheet), strings);
        } catch (XMLStreamException | RuntimeException e) {
            throw new IOException("Classeur illisible", e);
        }
    }

    /**
     * Opens a hardened XML reader over a part.
     *
     * @param part the XML part
     * @return the reader
     * @throws XMLStreamException when the part cannot be opened
     */
    private static XMLStreamReader reader(byte[] part) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory.createXMLStreamReader(new ByteArrayInputStream(part));
    }

    /**
     * Reads the shared-string table: one entry per {@code si}, the text of its
     * {@code t} runs joined.
     *
     * @param part the shared-strings part
     * @return the strings, by index
     * @throws XMLStreamException when the part is not well formed
     */
    private static List<String> sharedStrings(byte[] part) throws XMLStreamException {
        List<String> strings = new ArrayList<>();
        XMLStreamReader xml = reader(part);
        StringBuilder current = null;
        boolean inText = false;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = xml.getLocalName();
                if ("si".equals(name)) {
                    current = new StringBuilder();
                }
                inText = "t".equals(name);
            } else if (event == XMLStreamConstants.CHARACTERS && inText && current != null) {
                current.append(xml.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                inText = false;
                if ("si".equals(xml.getLocalName()) && current != null) {
                    strings.add(current.toString());
                    current = null;
                }
            }
        }
        return strings;
    }

    /**
     * Reads the cells of a sheet: a shared string by its index, an inline
     * string by its text, any other value as it is written, a number in
     * scientific notation brought back to its digits.
     *
     * @param part the sheet part
     * @param strings the shared strings
     * @return the non-blank cell texts, trimmed
     * @throws XMLStreamException when the part is not well formed
     */
    private static List<String> sheetCells(byte[] part, List<String> strings) throws XMLStreamException {
        List<String> cells = new ArrayList<>();
        XMLStreamReader xml = reader(part);
        String type = null;
        StringBuilder value = null;
        boolean inValue = false;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = xml.getLocalName();
                if ("c".equals(name)) {
                    type = xml.getAttributeValue(null, "t");
                    value = new StringBuilder();
                }
                inValue = "v".equals(name) || "t".equals(name);
            } else if (event == XMLStreamConstants.CHARACTERS && inValue && value != null) {
                value.append(xml.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                inValue = false;
                if ("c".equals(xml.getLocalName()) && value != null) {
                    String text = cellText(type, value.toString().trim(), strings);
                    if (!text.isEmpty()) {
                        cells.add(text);
                    }
                    value = null;
                }
            }
        }
        return cells;
    }

    /**
     * Turns one cell's raw value into its text.
     *
     * @param type the cell's {@code t} attribute, or null for a number
     * @param raw the raw value, trimmed
     * @param strings the shared strings
     * @return the cell's text, trimmed, empty for an empty cell
     */
    private static String cellText(String type, String raw, List<String> strings) {
        if (raw.isEmpty()) {
            return "";
        }
        if ("s".equals(type)) {
            return strings.get(Integer.parseInt(raw)).trim();
        }
        if (type == null && raw.matches("-?\\d+(\\.\\d+)?[eE][+-]?\\d+")) {
            return new BigDecimal(raw).toBigIntegerExact().toString();
        }
        return raw;
    }
}
