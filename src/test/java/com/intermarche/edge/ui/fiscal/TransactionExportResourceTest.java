package com.intermarche.edge.ui.fiscal;

import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link TransactionExportResource} (BO-05-01-06): the filters
 * read from the form, the page, and the extraction in the three formats.
 */
class TransactionExportResourceTest {

    /**
     * Builds the resource with its collaborators mocked, the search answering
     * one document and one customer.
     *
     * @return the resource
     */
    private TransactionExportResource resource() {
        TransactionExportResource resource = new TransactionExportResource();
        resource.adminFiscalTransactions = mock(Template.class);
        resource.transactionExportService = mock(TransactionExportService.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        when(resource.posSettingsService.fiscalOnlineRetentionYears()).thenReturn(12);
        TransactionExportService.Result result = new TransactionExportService.Result();
        TransactionExportService.Row row = new TransactionExportService.Row();
        row.type = "FS";
        row.number = "C01-00000001";
        row.issuedAt = LocalDateTime.of(2026, 10, 2, 9, 0);
        row.totalIncludingTax = new BigDecimal("12");
        result.rows.add(row);
        TransactionExportService.CustomerTotal customer = new TransactionExportService.CustomerTotal();
        customer.customer = "4000123";
        customer.count = 1;
        result.customers.add(customer);
        when(resource.transactionExportService.search(any())).thenReturn(result);
        return resource;
    }

    /**
     * The page shows the search with its filters, the years kept online and
     * the query repeating the search.
     */
    @Test
    void thepageShowsTheSearch() {
        TransactionExportResource resource = resource();
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.adminFiscalTransactions.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        assertSame(view, resource.page("2026-10-01", "2026-10-31", "ft", "PT 1", "C-1", "Dupont & fils", "T1",
                "1,5", "", "2", "300"));
        ArgumentCaptor<TransactionExportService.Criteria> criteria =
                ArgumentCaptor.forClass(TransactionExportService.Criteria.class);
        verify(resource.transactionExportService).search(criteria.capture());
        assertEquals("FT", criteria.getValue().type());
        assertEquals(new BigDecimal("1.5"), criteria.getValue().htMin());
        assertNull(criteria.getValue().htMax());
        verify(view).data("years", 12);
        verify(view).data("query", "from=2026-10-01&to=2026-10-31&type=FT&nif=PT+1&customerNumber=C-1"
                + "&customerName=Dupont+%26+fils&ticketNumber=T1&htMin=1%2C5&ttcMin=2&ttcMax=300");
        verify(view).data("shown", 1);
        verify(view).data("oldest", LocalDate.now().minusYears(12)
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        verify(view).data("invalidAmount", false);
    }

    /**
     * An amount typed that reads as no number is flagged, a blank one or a
     * number with a decimal comma is not.
     */
    @Test
    void anAmountThatIsNoNumberIsFlagged() {
        assertTrue(TransactionExportResource.invalid(null, " ", "1,5", "abc"));
        assertFalse(TransactionExportResource.invalid(null, " ", "1,5"));
    }

    /**
     * Without filters, the month so far is searched over every type; an
     * unknown type reads as every type.
     */
    @Test
    void withoutFiltersTheMonthSoFarIsSearched() {
        TransactionExportService.Criteria criteria = TransactionExportResource.criteria(null, "x", "ZZ", " ", null,
                null, null, null, null, null, null);
        assertEquals(LocalDate.now().withDayOfMonth(1), criteria.from());
        assertEquals(LocalDate.now(), criteria.to());
        assertNull(criteria.type());
        assertNull(criteria.nif());
        assertNull(TransactionExportResource.criteria(null, null, null, null, null, null, null, null, null, null,
                null).type());
    }

    /**
     * The page shows at most its share of a long result.
     */
    @Test
    void thepageShowsItsShareOfALongResult() {
        TransactionExportResource resource = resource();
        TransactionExportService.Result result = new TransactionExportService.Result();
        for (int index = 0; index < TransactionExportResource.SHOWN + 3; index++) {
            result.rows.add(new TransactionExportService.Row());
        }
        when(resource.transactionExportService.search(any())).thenReturn(result);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.adminFiscalTransactions.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        resource.page(null, null, null, null, null, null, null, null, null, null, null);
        verify(view).data("shown", TransactionExportResource.SHOWN);
    }

    /**
     * The extraction carries the documents, the grand total and the totals by
     * customer, in CSV by default, in the spreadsheet or in PDF.
     */
    @Test
    void theExtractionComesInThreeFormats() {
        TransactionExportResource resource = resource();
        Response csv = resource.export(null, "2026-10-01", "2026-10-31", null, null, null, null, null, null, null,
                null, null);
        String text = new String((byte[]) csv.getEntity(), StandardCharsets.UTF_8);
        assertTrue(text.startsWith("﻿TYPE;NUMERO;DATE;CAISSE;TICKET;N_CLIENT;NOM_CLIENT;NIF;TOTAL_HT;TVA;TOTAL_TTC"));
        assertTrue(text.contains("FS;C01-00000001;2026-10-02 09:00:00;;;;;;0.00;0.00;12.00"));
        assertTrue(text.contains("TOTAL;;;;;;;;0.00;0.00;0.00"));
        assertTrue(text.contains("CLIENT;1 doc.;;;;4000123;;;0.00;0.00;0.00"));
        assertEquals("attachment; filename=\"transactions_2026-10-01_2026-10-31.csv\"",
                csv.getHeaderString("Content-Disposition"));
        Response xlsx = resource.export(" XLSX ", "2026-10-01", "2026-10-31", null, null, null, null, null, null,
                null, null, null);
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsx.getMediaType().toString());
        Response pdf = resource.export("pdf", "2026-10-01", "2026-10-31", null, null, null, null, null, null, null,
                null, null);
        assertEquals("application/pdf", pdf.getMediaType().toString());
        assertTrue(new String((byte[]) pdf.getEntity(), StandardCharsets.ISO_8859_1).startsWith("%PDF"));
        assertTrue(new String((byte[]) pdf.getEntity(), StandardCharsets.ISO_8859_1).contains("du 01/10/2026 au 31/10/2026"));
        assertEquals("text/csv;charset=UTF-8", resource.export("odt", null, null, null, null, null, null, null, null,
                null, null, null).getMediaType().toString().replace(" ", ""));
    }

    /**
     * The query string leaves out the filters not set; the helpers read blank
     * values as none.
     */
    @Test
    void thequeryLeavesOutTheFiltersNotSet() {
        TransactionExportService.Criteria criteria = new TransactionExportService.Criteria(LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31), null, null, null, null, null, null, null, null, null);
        assertEquals("from=2026-01-01&to=2026-01-31", TransactionExportResource.query(criteria, null, " ", null, null));
        assertEquals("", TransactionExportResource.param("x", null));
        assertNull(TransactionExportResource.text(null));
        assertEquals(new BigDecimal("1.00"), TransactionExportResource.scaled(BigDecimal.ONE));
    }
}
