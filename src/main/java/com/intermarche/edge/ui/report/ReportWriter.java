package com.intermarche.edge.ui.report;

/**
 * Renders an executed report into ONE downloadable container.
 *
 * <p>Three implementations, one per format the questionnaire demands for EVERY
 * report — CSV (BO-06-01-02), spreadsheet (BO-06-01-01) and PDF (BO-06-01-03).
 * They see {@link ReportData}, never a particular report, which is what makes
 * the three formats a property of the frame rather than something each report
 * has to remember to offer.
 */
public interface ReportWriter {

    /**
     * Returns the format token that names this writer in a URL.
     *
     * @return {@code csv}, {@code xlsx} or {@code pdf}
     */
    String format();

    /**
     * Returns the media type the response announces.
     *
     * @return the MIME type
     */
    String contentType();

    /**
     * Returns the extension the downloaded file carries.
     *
     * @return the extension, without its dot
     */
    String extension();

    /**
     * Renders the report.
     *
     * @param data the executed report
     * @param criteria the edition parameters, printed on the document so a
     *                 sheet found on a desk states the period it covers
     * @return the container's bytes
     */
    byte[] write(ReportData data, ReportCriteria criteria);
}
