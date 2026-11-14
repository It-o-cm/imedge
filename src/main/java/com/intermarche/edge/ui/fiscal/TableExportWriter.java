package com.intermarche.edge.ui.fiscal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a table of the fiscal screens in the three formats a point of sale
 * asks for ({@code BO-05-01-06}): CSV, spreadsheet (Office Open XML) and PDF.
 *
 * <p>Self-contained on purpose: the table of the transactions export is a
 * fiscal extraction, laid out once here, and depends on no report frame. The
 * spreadsheet is a minimal workbook of one sheet — numbers as numbers, texts as
 * inline strings — and the PDF a landscape listing in Helvetica, paginated,
 * which prints as it shows.
 */
public final class TableExportWriter {

    /** The lines of a PDF page, heading included. */
    static final int LINES_PER_PAGE = 50;

    /** The encoding of the PDF's standard font. */
    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    /**
     * Not instantiable: static helpers only.
     */
    private TableExportWriter() {
    }

    /**
     * Writes a table as semicolon CSV, UTF-8 with its byte order mark so a
     * spreadsheet opens the accents right.
     *
     * @param header the column titles
     * @param rows the rows, a cell being a text or a number
     * @return the file's bytes
     */
    public static byte[] csv(List<String> header, List<List<Object>> rows) {
        StringBuilder text = new StringBuilder("﻿");
        line(text, new ArrayList<>(header));
        for (List<Object> row : rows) {
            line(text, row);
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Appends one CSV line.
     *
     * @param text the buffer
     * @param cells the cells
     */
    private static void line(StringBuilder text, List<?> cells) {
        for (int index = 0; index < cells.size(); index++) {
            Object cell = cells.get(index);
            text.append(index == 0 ? "" : ";").append(cell == null ? ""
                    : cell.toString().replace(';', ',').replace('\n', ' ').replace('\r', ' '));
        }
        text.append("\r\n");
    }

    /**
     * Writes a table as a one-sheet workbook.
     *
     * @param sheet the sheet's name
     * @param header the column titles
     * @param rows the rows, a number written as a number
     * @return the file's bytes
     */
    public static byte[] xlsx(String sheet, List<String> header, List<List<Object>> rows) {
        StringBuilder data = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        List<List<Object>> all = new ArrayList<>();
        all.add(new ArrayList<>(header));
        all.addAll(rows);
        for (int r = 0; r < all.size(); r++) {
            data.append("<row r=\"").append(r + 1).append("\">");
            List<Object> row = all.get(r);
            for (int c = 0; c < row.size(); c++) {
                Object cell = row.get(c);
                String ref = column(c) + (r + 1);
                if (cell instanceof Number number) {
                    data.append("<c r=\"").append(ref).append("\"><v>").append(number).append("</v></c>");
                } else {
                    data.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>")
                            .append(escape(cell == null ? "" : cell.toString())).append("</t></is></c>");
                }
            }
            data.append("</row>");
        }
        data.append("</sheetData></worksheet>");
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "</Types>");
        parts.put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
        parts.put("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"" + escape(sheet) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "</Relationships>");
        parts.put("xl/worksheets/sheet1.xml", data.toString());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        zip(parts, bytes);
        return bytes.toByteArray();
    }

    /**
     * Writes the parts of a workbook into a zip.
     *
     * @param parts the part names and their text
     * @param target the stream the zip is written to
     * @throws UncheckedIOException when the stream refuses the bytes
     */
    static void zip(Map<String, String> parts, OutputStream target) {
        try (ZipOutputStream zip = new ZipOutputStream(target, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException refused) {
            throw new UncheckedIOException(refused);
        }
    }

    /**
     * The spreadsheet letters of a column.
     *
     * @param index the column, from zero
     * @return its letters: A to Z, then AA, AB…
     */
    static String column(int index) {
        StringBuilder letters = new StringBuilder();
        int value = index + 1;
        while (value > 0) {
            int rest = (value - 1) % 26;
            letters.insert(0, (char) ('A' + rest));
            value = (value - 1) / 26;
        }
        return letters.toString();
    }

    /**
     * Writes a table as a paginated landscape PDF listing.
     *
     * @param title the title printed at the top of every page
     * @param header the column titles
     * @param rows the rows
     * @param widths the width of each column, in characters
     * @return the file's bytes
     */
    public static byte[] pdf(String title, List<String> header, List<List<Object>> rows, int[] widths) {
        List<String> lines = new ArrayList<>();
        for (List<Object> row : rows) {
            lines.add(format(new ArrayList<>(row), widths));
        }
        List<List<String>> pages = new ArrayList<>();
        int perPage = LINES_PER_PAGE - 3;
        for (int start = 0; start < lines.size() || pages.isEmpty(); start += perPage) {
            pages.add(lines.subList(Math.min(start, lines.size()), Math.min(start + perPage, lines.size())));
        }
        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder kids = new StringBuilder();
        for (int page = 0; page < pages.size(); page++) {
            kids.append(4 + page * 2).append(" 0 R ");
        }
        objects.add("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pages.size() + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>");
        String heading = format(new ArrayList<>(header), widths);
        for (int page = 0; page < pages.size(); page++) {
            StringBuilder stream = new StringBuilder("BT /F1 7 Tf 30 565 Td 9 TL ");
            stream.append(show(title + "  —  page " + (page + 1) + " / " + pages.size())).append(" Tj T* ");
            stream.append(show(heading)).append(" Tj T* ");
            stream.append(show("-".repeat(Math.min(heading.length(), 220)))).append(" Tj T* ");
            for (String line : pages.get(page)) {
                stream.append(show(line)).append(" Tj T* ");
            }
            stream.append("ET");
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 842 595] /Resources << /Font << /F1 3 0 R >> >> "
                    + "/Contents " + (5 + page * 2) + " 0 R >>");
            objects.add("<< /Length " + stream.toString().getBytes(WIN_ANSI).length + " >>\nstream\n" + stream
                    + "\nendstream");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n");
        for (int index = 0; index < objects.size(); index++) {
            offsets.add(out.size());
            write(out, (index + 1) + " 0 obj\n" + objects.get(index) + "\nendobj\n");
        }
        int xref = out.size();
        StringBuilder table = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            table.append(String.format("%010d 00000 n \n", offset));
        }
        table.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        write(out, table.toString());
        return out.toByteArray();
    }

    /**
     * Lays a row out in fixed-width columns.
     *
     * @param cells the cells
     * @param widths the width of each column
     * @return the line
     */
    static String format(List<Object> cells, int[] widths) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < cells.size(); index++) {
            int width = index < widths.length ? widths[index] : 12;
            String text = cells.get(index) == null ? "" : cells.get(index).toString();
            text = text.length() > width ? text.substring(0, width) : text;
            boolean number = cells.get(index) instanceof Number;
            line.append(number ? " ".repeat(width - text.length()) + text : text + " ".repeat(width - text.length()))
                    .append(' ');
        }
        return line.toString().stripTrailing();
    }

    /**
     * Writes a text as a PDF string operand, its specials escaped.
     *
     * @param text the text
     * @return the operand
     */
    static String show(String text) {
        return "(" + text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")";
    }

    /**
     * Writes text in the PDF's encoding.
     *
     * @param out the stream
     * @param text the text
     */
    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(WIN_ANSI));
    }

    /**
     * Escapes a value for XML.
     *
     * @param value the value
     * @return the escaped value
     */
    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
