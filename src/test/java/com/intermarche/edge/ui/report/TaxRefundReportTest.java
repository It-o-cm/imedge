package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.TaxRefundForm;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TaxRefundReport}: the forms of the period with their
 * amounts by rate and their state, the state options, and the metadata.
 */
class TaxRefundReportTest {

    /**
     * Builds a form.
     *
     * @param number the number
     * @param status the status
     * @return the form
     */
    private TaxRefundForm form(String number, String status) {
        TaxRefundForm form = new TaxRefundForm();
        form.number = number;
        form.status = status;
        form.createdAt = LocalDateTime.of(2026, 10, 20, 10, 0);
        form.customerName = "John Smith";
        form.refundAmount = new BigDecimal("12.00");
        form.vat20 = new BigDecimal("12.00");
        return form;
    }

    /**
     * Every form of the period, waiting for the visa or refunded.
     */
    @Test
    void everyFormWithItsState() {
        TaxRefundForm refunded = form("D2", TaxRefundForm.REFUNDED);
        refunded.refundedAt = LocalDateTime.of(2026, 10, 21, 9, 0);
        QueryStub stub = new QueryStub().answer("from TaxRefundForm f", List.of(form("D1", TaxRefundForm.ISSUED),
                refunded));
        TaxRefundReport report = new TaxRefundReport();
        report.entityManager = stub.entityManager;
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(2, rows.size());
        assertEquals("D1", rows.get(0).get(1));
        assertEquals("John Smith", rows.get(0).get(2));
        assertEquals(new BigDecimal("12.00"), rows.get(0).get(11));
        assertEquals("En attente du visa", rows.get(0).get(12));
        assertEquals("Remboursé", rows.get(1).get(12));
        assertEquals(refunded.refundedAt, rows.get(1).get(13));
        assertFalse(stub.parameters.containsKey("status"));
    }

    /**
     * A state option restricts the query to its status.
     */
    @Test
    void theStateOptionRestricts() {
        QueryStub stub = new QueryStub();
        TaxRefundReport report = new TaxRefundReport();
        report.entityManager = stub.entityManager;
        ReportCriteria criteria = new ReportCriteria();
        criteria.option = TaxRefundForm.REFUNDED;
        assertTrue(report.rows(criteria).isEmpty());
        assertEquals(TaxRefundForm.REFUNDED, stub.parameters.get("status"));
        assertTrue(stub.asked("and f.status = :status"));
    }

    /**
     * The metadata: identity, columns, options and filters.
     */
    @Test
    void metadataDescribesTheReport() {
        TaxRefundReport report = new TaxRefundReport();
        assertEquals("tax-refunds", report.code());
        assertEquals("Suivi du remboursement de la détaxe", report.label());
        assertEquals("BO-06-07-03", report.requirement());
        assertEquals(ReportFamily.TAX, report.family());
        assertEquals(14, report.columns().size());
        assertEquals("Bordereaux", report.optionLabel());
        assertEquals(3, report.optionChoices().size());
        assertTrue(report.filters().isEmpty());
    }
}
