package com.intermarche.edge.ui.admin;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ArticleListReader} (BO-03-01-16).
 *
 * <p>Plain JUnit: the workbooks are built in memory as the ZIP of XML parts an
 * {@code .xlsx} is, so every shape of cell the reader handles is stated here
 * — shared, rich, inline, numeric, scientific, empty, with indentation
 * between the elements — together with the
 * text lists and the files it must refuse.
 */
class ArticleListReaderTest {

    /**
     * Builds a workbook from its parts.
     *
     * @param parts the part names and their XML
     * @return the zipped workbook
     * @throws IOException never, the archive being in memory
     */
    private static byte[] workbook(Map<String, String> parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /**
     * Reads a workbook built from its parts.
     *
     * @param parts the part names and their XML
     * @return the codes read
     * @throws IOException when the workbook is refused
     */
    private static List<String> readWorkbook(Map<String, String> parts) throws IOException {
        return ArticleListReader.read(new ByteArrayInputStream(workbook(parts)));
    }

    /**
     * An absent file and an empty one carry no code.
     *
     * @throws IOException never
     */
    @Test
    void anAbsentOrEmptyFileCarriesNothing() throws IOException {
        assertTrue(ArticleListReader.read(null).isEmpty());
        assertTrue(ArticleListReader.read(new ByteArrayInputStream(new byte[0])).isEmpty());
    }

    /**
     * A text list is split on whitespace, commas and semicolons, a byte-order
     * mark and quotes dropped — a one-character file and one starting with
     * {@code P} but not a ZIP included.
     *
     * @throws IOException never
     */
    @Test
    void aTextListIsSplitIntoItsCodes() throws IOException {
        assertEquals(List.of("EAN", "3017620422003", "INT-9", "1234"), ArticleListReader.read(
                new ByteArrayInputStream("﻿EAN\r\n\"3017620422003\";INT-9, 1234\n".getBytes(StandardCharsets.UTF_8))));
        assertEquals(List.of("P"), ArticleListReader.read(new ByteArrayInputStream("P".getBytes(StandardCharsets.UTF_8))));
        assertEquals(List.of("Pomme"), ArticleListReader.read(
                new ByteArrayInputStream("Pomme".getBytes(StandardCharsets.UTF_8))));
        assertTrue(ArticleListReader.textTokens("  ").isEmpty());
    }

    /**
     * Every cell of the FIRST sheet is read in order: a shared string, a rich
     * shared string, an inline string, a number, a number in scientific
     * notation, a text that merely looks scientific; empty and blank cells and
     * stray markup are stepped over, and the second sheet is ignored.
     *
     * @throws IOException never
     */
    @Test
    void theCellsOfTheFirstSheetAreRead() throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<Types/>");
        parts.put("xl/sharedStrings.xml", "<sst>\n<t>orphan</t><si><t>EAN</t></si>\n"
                + "<si><r><t>30176</t></r><r><t>20422003</t></r></si><si><t>  </t></si>"
                + "<si><si><t>nested</t></si></si></sst>");
        parts.put("xl/worksheets/sheet1.xml", "<worksheet>\n<sheetData><v>stray</v><row>\n"
                + "<c r=\"A1\" t=\"s\"><v>0</v></c>"
                + "<c r=\"A2\" t=\"s\"><v>1</v></c>"
                + "<c r=\"A3\" t=\"inlineStr\"><is><t>INT-9</t></is></c>"
                + "<c r=\"A4\"><v>3560070123456</v></c>"
                + "<c r=\"A5\"><v>3.017620422003E12</v></c>"
                + "<c r=\"A6\" t=\"str\"><v>1E5</v></c>"
                + "<c r=\"A7\"/>"
                + "<c r=\"A8\" t=\"s\"><v>2</v></c>"
                + "<c r=\"A9\"><c r=\"A10\"/></c>"
                + "</row></sheetData></worksheet>");
        parts.put("xl/worksheets/sheet2.xml", "<worksheet><sheetData><row><c><v>99</v></c></row></sheetData></worksheet>");
        assertEquals(List.of("EAN", "3017620422003", "INT-9", "3560070123456", "3017620422003", "1E5"),
                readWorkbook(parts));
    }

    /**
     * A workbook whose cells share no string reads its numbers alone.
     *
     * @throws IOException never
     */
    @Test
    void aWorkbookWithoutSharedStringsReadsItsValues() throws IOException {
        assertEquals(List.of("42"), readWorkbook(Map.of("xl/worksheets/sheet1.xml",
                "<worksheet><sheetData><row><c><v>42</v></c></row></sheetData></worksheet>")));
    }

    /**
     * A workbook without a sheet, with a malformed sheet, or pointing to a
     * shared string that does not exist is refused as unreadable.
     */
    @Test
    void anUnreadableWorkbookIsRefused() {
        assertThrows(IOException.class, () -> readWorkbook(Map.of("xl/sharedStrings.xml", "<sst/>")));
        assertThrows(IOException.class, () -> readWorkbook(Map.of("xl/worksheets/sheet1.xml", "<worksheet>")));
        assertThrows(IOException.class, () -> readWorkbook(Map.of("xl/worksheets/sheet1.xml",
                "<worksheet><c t=\"s\"><v>5</v></c></worksheet>")));
    }
}
