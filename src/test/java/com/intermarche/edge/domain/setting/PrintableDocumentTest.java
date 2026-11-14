package com.intermarche.edge.domain.setting;

import com.intermarche.edge.domain.sale.DocumentType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PrintableDocument} (BO-03-04-06, -09, -10 and -14).
 *
 * <p>Branches: {@code findByCode} on a null, a blank and a real code;
 * {@code outputOf} on a null, an unknown, a blank and a known name.
 */
class PrintableDocumentTest {

    /** A new document is an active invoice offered in the default order, on no printer of its own. */
    @Test
    void aNewDocumentIsAnActiveInvoice() {
        PrintableDocument document = new PrintableDocument();
        assertEquals(DocumentType.FACTURE, document.kind);
        assertTrue(document.active);
        assertEquals(100, document.priority);
        assertNull(document.output);
        assertEquals(List.of("TICKET", "FACTURETTE", "A4"), PrintableDocument.OUTPUTS);
    }

    /** The registers are offered the active documents, the back office lists them all, both in order. */
    @Test
    void theListsAreReadInAdministeredOrder() {
        PrintableDocument row = new PrintableDocument();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("active = true order by priority, code"))
                    .thenReturn(List.of(row));
            panache.when(() -> PanacheEntityBase.list("order by priority, code")).thenReturn(List.of(row, row));
            assertEquals(List.of(row), PrintableDocument.listActiveOrdered());
            assertEquals(List.of(row, row), PrintableDocument.listAllOrdered());
        }
    }

    /** A missing or blank code finds nothing without a query; a code is looked up trimmed. */
    @Test
    void findByCodeLooksUpATrimmedCode() {
        PrintableDocument row = new PrintableDocument();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<PrintableDocument> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(row);
            panache.when(() -> PanacheEntityBase.find("code", "FACT_PRO")).thenReturn(query);
            assertNull(PrintableDocument.findByCode(null));
            assertNull(PrintableDocument.findByCode("  "));
            assertSame(row, PrintableDocument.findByCode(" FACT_PRO "));
        }
    }

    /** A printer name is read case-blind and trimmed; a missing, blank or unknown one reads as none. */
    @Test
    void outputOfReadsTheThreePrinters() {
        assertEquals("FACTURETTE", PrintableDocument.outputOf(" facturette "));
        assertEquals("A4", PrintableDocument.outputOf("A4"));
        assertNull(PrintableDocument.outputOf(null));
        assertNull(PrintableDocument.outputOf("   "));
        assertNull(PrintableDocument.outputOf("LASER"));
    }
}
