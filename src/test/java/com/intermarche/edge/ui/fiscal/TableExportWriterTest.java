package com.intermarche.edge.ui.fiscal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link TableExportWriter} (BO-05-01-06): the CSV, the
 * spreadsheet and the PDF of a table.
 */
class TableExportWriterTest {

    /** The header of the tests. */
    private static final List<String> HEADER = List.of("TYPE", "MONTANT");

    /**
     * Builds the rows of the tests.
     *
     * @param count how many
     * @return the rows
     */
    private List<List<Object>> rows(int count) {
        List<List<Object>> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            rows.add(Arrays.asList("FS;x\n(é)", new BigDecimal("12.50")));
        }
        rows.add(Arrays.asList(null, null));
        return rows;
    }

    /**
     * The CSV carries its byte order mark, the header, the rows, a separator
     * or a line break inside a value replaced.
     */
    @Test
    void thecsvCarriesTheRows() {
        String csv = new String(TableExportWriter.csv(HEADER, rows(1)), StandardCharsets.UTF_8);
        assertEquals("﻿TYPE;MONTANT\r\nFS,x (é);12.50\r\n;\r\n", csv);
    }

    /**
     * The workbook is a zip of the five parts, numbers written as numbers and
     * texts as inline strings, escaped.
     *
     * @throws IOException never, the zip is in memory
     */
    @Test
    void theworkbookHoldsTheSheet() throws IOException {
        byte[] xlsx = TableExportWriter.xlsx("Transactions & Co", HEADER, rows(1));
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(xlsx), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                parts.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(List.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/worksheets/sheet1.xml"), List.copyOf(parts.keySet()));
        assertTrue(parts.get("xl/workbook.xml").contains("name=\"Transactions &amp; Co\""));
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<c r=\"B2\"><v>12.50</v></c>"));
        assertTrue(sheet.contains("<c r=\"A1\" t=\"inlineStr\"><is><t>TYPE</t></is></c>"));
        assertTrue(sheet.contains("<c r=\"A3\" t=\"inlineStr\"><is><t></t></is></c>"));
    }

    /**
     * The columns are lettered as a spreadsheet does.
     */
    @Test
    void thecolumnsAreLettered() {
        assertEquals("A", TableExportWriter.column(0));
        assertEquals("Z", TableExportWriter.column(25));
        assertEquals("AA", TableExportWriter.column(26));
        assertEquals("AZ", TableExportWriter.column(51));
        assertEquals("BA", TableExportWriter.column(52));
    }

    /**
     * A stream refusing the workbook fails as such.
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
        assertThrows(UncheckedIOException.class, () -> TableExportWriter.zip(Map.of("a", "b"), refusing));
    }

    /**
     * The PDF is a valid file of as many pages as the rows ask, its title on
     * every page, its specials escaped; an empty table gives one page.
     */
    @Test
    void thepdfIsPaginated() {
        String pdf = new String(TableExportWriter.pdf("Export (test)", HEADER, rows(60), new int[] {6, 8}),
                Charset.forName("windows-1252"));
        assertTrue(pdf.startsWith("%PDF-1.4\n"));
        assertTrue(pdf.endsWith("%%EOF\n"));
        assertTrue(pdf.contains("/Count 2"));
        assertTrue(pdf.contains("(Export \\(test\\)  —  page 2 / 2)"));
        assertTrue(pdf.contains("/BaseFont /Courier"));
        String empty = new String(TableExportWriter.pdf("Vide", HEADER, List.of(), new int[] {6}),
                Charset.forName("windows-1252"));
        assertTrue(empty.contains("/Count 1"));
    }

    /**
     * A row is laid out in fixed columns: texts cut and padded on the right,
     * numbers on the left, a column without width at twelve characters.
     */
    @Test
    void arowIsLaidOutInFixedColumns() {
        assertEquals("abc    12.50 x", TableExportWriter.format(Arrays.asList("abcdef", new BigDecimal("12.50"), "x"),
                new int[] {3, 8}));
        assertEquals("", TableExportWriter.format(Arrays.asList((Object) null), new int[] {2}));
        assertEquals("(a\\\\b\\(c\\))", TableExportWriter.show("a\\b(c)"));
        assertEquals("a&amp;&lt;&gt;&quot;", TableExportWriter.escape("a&<>\""));
    }
}
