package com.intermarche.edge.ui.report;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Renders a report as a real spreadsheet (BO-06-01-01).
 *
 * <p>Written by hand, with no library added to the build. An .xlsx is a ZIP of
 * XML parts, and the part of the format a report needs — a sheet, a style table
 * and inline strings — is small and stable, where a spreadsheet library is tens
 * of megabytes in a register's deployment and a permanent upgrade obligation for
 * something the node does in three hundred lines. The standard library ships the
 * ZIP.
 *
 * <p>NUMBERS ARE WRITTEN AS NUMBERS, dates as dates, each with its display
 * format. That is the whole difference between this and the CSV: the recipient
 * opens the file and sums a column, sorts on an amount, filters on a date —
 * which is exactly why the questionnaire asks for Excel AND for CSV rather than
 * treating them as one requirement.
 */
@ApplicationScoped
public class XlsxReportWriter implements ReportWriter {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(XlsxReportWriter.class);

    /** The day Excel's serial numbering counts from (its 1900 leap-year bug included). */
    private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);

    /** Style index of the bold title and header cells. */
    private static final int STYLE_BOLD = 1;

    /** Style index of an amount cell. */
    private static final int STYLE_AMOUNT = 2;

    /** Style index of a percentage cell. */
    private static final int STYLE_PERCENT = 3;

    /** Style index of a decimal figure that is not money. */
    private static final int STYLE_DECIMAL = 8;

    /** Style index of a whole-number cell. */
    private static final int STYLE_INTEGER = 4;

    /** Style index of a day cell. */
    private static final int STYLE_DATE = 5;

    /** Style index of a day-and-time cell. */
    private static final int STYLE_DATETIME = 6;

    /** Style index of a time-of-day cell. */
    private static final int STYLE_TIME = 7;

    /**
     * {@inheritDoc}
     *
     * @return the {@code xlsx} token
     */
    @Override
    public String format() {
        return "xlsx";
    }

    /**
     * {@inheritDoc}
     *
     * @return the OOXML spreadsheet media type
     */
    @Override
    public String contentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    /**
     * {@inheritDoc}
     *
     * @return the {@code xlsx} extension
     */
    @Override
    public String extension() {
        return "xlsx";
    }

    /**
     * {@inheritDoc}
     *
     * @param data the executed report
     * @param criteria the edition parameters, written on the first row
     * @return the workbook's bytes
     */
    @Override
    public byte[] write(ReportData data, ReportCriteria criteria) {
        LOGGER.info("Entering method write for report: " + data.definition.code());
        com.intermarche.edge.ui.admin.BackOfficeMessages messages =
                com.intermarche.edge.ui.admin.BackOfficeMessages.in(criteria.language);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", contentTypes());
            put(zip, "_rels/.rels", rootRelations());
            put(zip, "xl/workbook.xml", workbook(messages.text(data.definition.label())));
            put(zip, "xl/_rels/workbook.xml.rels", workbookRelations());
            put(zip, "xl/styles.xml", styles());
            put(zip, "xl/worksheets/sheet1.xml", sheet(data, criteria, messages));
        } catch (IOException impossible) {
            // A ByteArrayOutputStream does not fail on write; the checked
            // exception exists for streams that reach a device.
            throw new UncheckedIOException(impossible);
        }
        LOGGER.info("Exiting method write");
        return bytes.toByteArray();
    }

    /**
     * Adds one stored part to the package.
     *
     * @param zip the package being written
     * @param name the part's path inside the package
     * @param content the part's XML
     * @throws IOException never, in practice: the sink is in memory
     */
    private void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * Declares the media type of every part.
     *
     * @return the content-types part
     */
    private String contentTypes() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                </Types>""";
    }

    /**
     * Points the package at its workbook.
     *
     * @return the root relationship part
     */
    private String rootRelations() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>""";
    }

    /**
     * Declares the single sheet and names it after the report.
     *
     * @param label the report's title
     * @return the workbook part
     */
    private String workbook(String label) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets>
                </workbook>""".formatted(sheetName(label));
    }

    /**
     * Points the workbook at its sheet and its styles.
     *
     * @return the workbook relationship part
     */
    private String workbookRelations() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>""";
    }

    /**
     * Declares the display formats the cells refer to by index.
     *
     * @return the styles part
     */
    private String styles() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <numFmts count="6">
                <numFmt numFmtId="164" formatCode="#,##0.00\\ &quot;€&quot;"/>
                <numFmt numFmtId="165" formatCode="0.0\\ &quot;%&quot;"/>
                <numFmt numFmtId="166" formatCode="#,##0"/>
                <numFmt numFmtId="167" formatCode="dd/mm/yyyy"/>
                <numFmt numFmtId="168" formatCode="dd/mm/yyyy\\ hh:mm"/>
                <numFmt numFmtId="169" formatCode="hh:mm"/>
                </numFmts>
                <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
                <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
                <borders count="1"><border/></borders>
                <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                <cellXfs count="9">
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
                <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
                <xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
                <xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
                <xf numFmtId="167" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
                <xf numFmtId="168" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
                <xf numFmtId="169" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
                <xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
                </cellXfs>
                </styleSheet>""";
    }

    /**
     * Writes the sheet: a title row naming the period, a header row, the rows,
     * and the total row when there is one; the texts in the edition's language.
     *
     * @param data the executed report
     * @param criteria the edition parameters
     * @param messages the language of the edition
     * @return the worksheet part
     */
    private String sheet(ReportData data, ReportCriteria criteria,
                         com.intermarche.edge.ui.admin.BackOfficeMessages messages) {
        List<ReportColumn> columns = data.definition.columns();
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""");
        int line = 1;
        xml.append("<row r=\"").append(line).append("\">");
        xml.append(inline("A" + line, STYLE_BOLD,
                messages.text(data.definition.label()) + " — " + ReportHeading.period(criteria, messages)));
        xml.append("</row>");
        for (String note : data.definition.preamble(criteria)) {
            line++;
            xml.append("<row r=\"").append(line).append("\">").append(inline("A" + line, 0, messages.text(note)))
                    .append("</row>");
        }
        line++;
        xml.append("<row r=\"").append(line).append("\">");
        for (int c = 0; c < columns.size(); c++) {
            xml.append(inline(reference(c, line), STYLE_BOLD, messages.text(columns.get(c).label())));
        }
        xml.append("</row>");
        for (List<Object> row : data.rows) {
            line++;
            xml.append(row(columns, row, line, ReportData.isSubtotal(row) ? STYLE_BOLD : 0, messages));
        }
        if (data.hasTotals()) {
            line++;
            xml.append(row(columns, data.totals, line, STYLE_BOLD, messages));
        }
        if (data.definition.signature() != null) {
            line += 2;
            xml.append("<row r=\"").append(line).append("\">")
                    .append(inline("A" + line, STYLE_BOLD, messages.text(data.definition.signature()) + " :"))
                    .append("</row>");
        }
        xml.append("</sheetData></worksheet>");
        return xml.toString();
    }

    /**
     * Writes one row of cells.
     *
     * @param columns the report's columns
     * @param row the values
     * @param line the 1-based row number
     * @param textStyle the style a textual cell takes, so a total row is bold
     * @param messages the language of the edition, for the textual cells
     * @return the row's XML
     */
    private String row(List<ReportColumn> columns, List<Object> row, int line, int textStyle,
                       com.intermarche.edge.ui.admin.BackOfficeMessages messages) {
        StringBuilder xml = new StringBuilder("<row r=\"" + line + "\">");
        for (int c = 0; c < columns.size(); c++) {
            Object value = c < row.size() ? row.get(c) : null;
            if (value == null) {
                continue;
            }
            xml.append(cell(columns.get(c), value, reference(c, line), textStyle, messages));
        }
        return xml.append("</row>").toString();
    }

    /**
     * Writes one cell, typed by its column's kind.
     *
     * @param column the column the value belongs to
     * @param value the raw value, never null
     * @param reference the cell's A1 reference
     * @param textStyle the style a textual cell takes
     * @param messages the language of the edition, for a textual cell
     * @return the cell's XML
     */
    private String cell(ReportColumn column, Object value, String reference, int textStyle,
                        com.intermarche.edge.ui.admin.BackOfficeMessages messages) {
        return switch (column.kind()) {
            case AMOUNT -> number(reference, STYLE_AMOUNT, ReportFormat.decimal(value));
            case PERCENT -> number(reference, STYLE_PERCENT, ReportFormat.decimal(value));
            case DECIMAL -> number(reference, STYLE_DECIMAL, ReportFormat.decimal(value));
            case INTEGER -> number(reference, STYLE_INTEGER,
                    ReportFormat.decimal(value).setScale(0, java.math.RoundingMode.HALF_UP));
            case DATE -> serial(reference, STYLE_DATE, value);
            case DATETIME -> serial(reference, STYLE_DATETIME, value);
            case TIME -> serial(reference, STYLE_TIME, value);
            case TEXT -> inline(reference, textStyle, messages.text(value));
        };
    }

    /**
     * Writes a numeric cell.
     *
     * @param reference the cell's A1 reference
     * @param style the style index
     * @param value the number
     * @return the cell's XML
     */
    private String number(String reference, int style, BigDecimal value) {
        return "<c r=\"" + reference + "\" s=\"" + style + "\"><v>" + value.toPlainString() + "</v></c>";
    }

    /**
     * Writes a date, a time or a moment as an Excel serial number.
     *
     * <p>A value the sheet cannot date — anything that is not one of the three
     * temporal types — falls back to an inline string rather than to a wrong
     * number: a report that shows its value as text is readable, one that shows
     * 0 dated 30/12/1899 is not.
     *
     * @param reference the cell's A1 reference
     * @param style the style index
     * @param value the temporal value
     * @return the cell's XML
     */
    private String serial(String reference, int style, Object value) {
        BigDecimal days;
        if (value instanceof LocalDateTime moment) {
            days = BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(EPOCH, moment.toLocalDate()))
                    .add(fraction(moment.toLocalTime()));
        } else if (value instanceof LocalDate day) {
            days = BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(EPOCH, day));
        } else if (value instanceof LocalTime moment) {
            days = fraction(moment);
        } else {
            return inline(reference, 0, String.valueOf(value));
        }
        return "<c r=\"" + reference + "\" s=\"" + style + "\"><v>" + days.toPlainString() + "</v></c>";
    }

    /**
     * Converts a time of day into the fraction of a day Excel stores.
     *
     * @param time the time of day
     * @return the fraction, between zero and one
     */
    private BigDecimal fraction(LocalTime time) {
        return BigDecimal.valueOf(Duration.between(LocalTime.MIDNIGHT, time).getSeconds())
                .divide(BigDecimal.valueOf(86400), 10, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Writes a textual cell, its characters carried inline rather than through a
     * shared-strings table.
     *
     * @param reference the cell's A1 reference
     * @param style the style index
     * @param value the text
     * @return the cell's XML
     */
    private String inline(String reference, int style, String value) {
        return "<c r=\"" + reference + "\" s=\"" + style + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                + escape(value) + "</t></is></c>";
    }

    /**
     * Builds a cell's A1 reference from its zero-based column and its row.
     *
     * @param column the zero-based column index
     * @param line the 1-based row number
     * @return the reference, for example {@code AA12}
     */
    String reference(int column, int line) {
        StringBuilder letters = new StringBuilder();
        int rest = column;
        while (true) {
            letters.insert(0, (char) ('A' + rest % 26));
            rest = rest / 26 - 1;
            if (rest < 0) {
                break;
            }
        }
        return letters + String.valueOf(line);
    }

    /**
     * Escapes the five characters XML reserves.
     *
     * @param value the raw text
     * @return the escaped text
     */
    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /**
     * Shortens and cleans a report title into a legal sheet name.
     *
     * <p>Excel refuses a sheet name longer than 31 characters or carrying one of
     * seven punctuation marks, and refuses to OPEN the file rather than
     * correcting it — so the cleaning happens here, not in the reader.
     *
     * @param label the report's title
     * @return a legal sheet name
     */
    String sheetName(String label) {
        String cleaned = label.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        if (cleaned.isEmpty()) {
            cleaned = "Rapport";
        }
        return escape(cleaned.length() > 31 ? cleaned.substring(0, 31).trim() : cleaned);
    }
}
