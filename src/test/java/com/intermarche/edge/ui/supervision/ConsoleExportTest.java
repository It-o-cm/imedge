package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.ui.report.ReportColumn;
import com.intermarche.edge.ui.report.ReportCriteria;
import com.intermarche.edge.ui.report.ReportFamily;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link ConsoleExport}. */
class ConsoleExportTest {

    /** The columns of the tests. */
    private static final List<ReportColumn> COLUMNS = List.of(ReportColumn.text("pdv", "PDV"),
            ReportColumn.text("status", "Statut"));

    /** The rows of the tests. */
    private static final List<List<Object>> ROWS = List.of(List.of("07039", "OK"));

    /** BO-08-04-15: the CSV export carries the heading, the columns and the rows. */
    @Test
    void csvExportCarriesTheList() {
        Response response = ConsoleExport.export("csv", "activite", "Console d'activité", COLUMNS, ROWS);
        assertEquals(200, response.getStatus());
        assertEquals("attachment; filename=\"activite.csv\"", response.getHeaderString("Content-Disposition"));
        String csv = new String((byte[]) response.getEntity(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("PDV;Statut"));
        assertTrue(csv.contains("07039;OK"));
    }

    /** BO-08-04-15: the XLS export is an Office Open XML workbook. */
    @Test
    void xlsxExportIsAWorkbook() {
        Response response = ConsoleExport.export("XLSX", "activite", "Console d'activité", COLUMNS, ROWS);
        assertEquals("attachment; filename=\"activite.xlsx\"", response.getHeaderString("Content-Disposition"));
        byte[] bytes = (byte[]) response.getEntity();
        assertEquals('P', bytes[0]);
        assertEquals('K', bytes[1]);
    }

    /** The table answers the writers whatever the criteria. */
    @Test
    void theTableIsAFixedReport() {
        ConsoleExport.Table table = new ConsoleExport.Table("c", "l", COLUMNS, ROWS);
        assertEquals("BO-08", table.requirement());
        assertEquals(ReportFamily.SALES, table.family());
        assertEquals(ROWS, table.rows(ReportCriteria.fromParams(null)));
    }
}
