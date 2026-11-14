package com.intermarche.edge.service.fiscal;

import com.intermarche.edge.domain.fiscal.FiscalArchive;
import com.intermarche.edge.domain.payment.CardPayment;
import com.intermarche.edge.domain.payment.CashPayment;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.service.NodeEchelonService;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link FiscalArchiveService} (BO-05-01-01): the two archives of
 * a period, their files and contents, their names, the signature chain check,
 * and the automatic production of the month just ended.
 */
class FiscalArchiveServiceTest {

    /** The first day of the period. */
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);

    /** The last day of the period. */
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    /**
     * Builds the service with its collaborators mocked.
     *
     * @param pdv the point of sale's number the node states
     * @return the service
     */
    private FiscalArchiveService service(String pdv) {
        FiscalArchiveService service = new FiscalArchiveService();
        service.nodeEchelonService = mock(NodeEchelonService.class);
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.nodeEchelonService.currentPdv()).thenReturn(pdv);
        return service;
    }

    /**
     * Builds a ticket of a register.
     *
     * @param number its number
     * @param terminal its register
     * @param status its status
     * @param signature its signature
     * @param previous the signature it chains to
     * @return the ticket
     */
    private Ticket ticket(String number, String terminal, Ticket.TicketStatus status, String signature,
            String previous) {
        Ticket ticket = new Ticket();
        ticket.id = (long) number.hashCode();
        ticket.ticketNumber = number;
        ticket.terminalId = terminal;
        ticket.status = status;
        ticket.creationDate = LocalDateTime.of(2026, 9, 15, 10, 0);
        ticket.closingDate = LocalDateTime.of(2026, 9, 15, 10, 5);
        ticket.signature = signature;
        ticket.previousSignature = previous;
        ticket.totalExcludingTax = new BigDecimal("10.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.totalIncludingTax = new BigDecimal("12.00");
        TicketLine line = new TicketLine();
        line.lineNumber = 1;
        line.lineUid = "U1";
        line.ean = "3000000000001";
        line.productLabel = "Café";
        line.quantity = BigDecimal.ONE;
        line.unitPrice = new BigDecimal("12.00");
        line.totalPrice = new BigDecimal("12.00");
        line.vatRate = new BigDecimal("0.20");
        TicketLine cancelled = new TicketLine();
        cancelled.cancelled = true;
        cancelled.totalPrice = BigDecimal.ONE;
        TicketLine untaxed = new TicketLine();
        untaxed.totalPrice = null;
        ticket.lines = new ArrayList<>(List.of(line, cancelled, untaxed));
        return ticket;
    }

    /**
     * Stubs the reading of a period.
     *
     * @param panache the active PanacheEntityBase mock
     * @param tickets the tickets
     * @param refunds the returns
     * @param invoices the invoices
     * @param sessions the sessions
     * @param movements the movements
     * @param documents the fiscal documents
     */
    private void stubPeriod(MockedStatic<PanacheEntityBase> panache, List<Ticket> tickets, List<Refund> refunds,
            List<Invoice> invoices, List<CashSession> sessions, List<CashMovement> movements,
            List<FiscalDocument> documents) {
        LocalDateTime start = FROM.atStartOfDay();
        LocalDateTime end = TO.plusDays(1).atStartOfDay();
        panache.when(() -> PanacheEntityBase.list("creationDate >= ?1 and creationDate < ?2 order by terminalId, "
                + "creationDate", start, end)).thenReturn(new ArrayList<>(tickets));
        panache.when(() -> PanacheEntityBase.list("creationDate >= ?1 and creationDate < ?2 order by creationDate",
                start, end)).thenReturn(new ArrayList<>(refunds));
        panache.when(() -> PanacheEntityBase.list("issueDate >= ?1 and issueDate < ?2 order by issueDate",
                start, end)).thenReturn(new ArrayList<>(invoices));
        panache.when(() -> PanacheEntityBase.list("closingDate >= ?1 and closingDate < ?2 order by terminalId, "
                + "closingDate", start, end)).thenReturn(new ArrayList<>(sessions));
        panache.when(() -> PanacheEntityBase.list("movementDate >= ?1 and movementDate < ?2 order by movementDate",
                start, end)).thenReturn(new ArrayList<>(movements));
        panache.when(() -> PanacheEntityBase.list("issuedAt >= ?1 and issuedAt < ?2 order by documentType, series, "
                + "rank", start, end)).thenReturn(new ArrayList<>(documents));
    }

    /**
     * Reads the files of a zip.
     *
     * @param zip the zip's bytes
     * @return the files by name
     * @throws IOException never, the zip is in memory
     */
    private Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    /**
     * The two archives of a period are produced under their names and
     * fingerprints, the extracts carrying every kind of data and the reports
     * their aggregates and the breaks of the signature chain.
     *
     * @throws IOException never
     */
    @Test
    void thetwoArchivesOfAPeriodAreProduced() throws IOException {
        FiscalArchiveService service = service(" 07039 ");
        Ticket first = ticket("T1", "C01", Ticket.TicketStatus.CLOSED, "S1", "");
        first.payments.add(new CashPayment(new BigDecimal("12.00"), new BigDecimal("20.00")));
        CashSession session = new CashSession();
        session.sessionNumber = "C01-S00001";
        session.terminalId = "C01";
        first.session = session;
        Employee cashier = new Employee();
        cashier.loginName = "jdupont";
        first.cashier = cashier;
        Ticket second = ticket("T2", "C01", Ticket.TicketStatus.CLOSED, "S2", "WRONG");
        second.closingDate = null;
        second.payments.add(new CardPayment(new BigDecimal("12.00")));
        Ticket parked = ticket("T3", "C02", Ticket.TicketStatus.PARKED, null, null);
        Ticket chained = ticket("T4", "C02", Ticket.TicketStatus.CANCELLED, "S4", null);
        Ticket next = ticket("T5", "C02", Ticket.TicketStatus.CANCELLED, "S5", "S4");
        Ticket blank = ticket("T6", "C02", Ticket.TicketStatus.CANCELLED, " ", null);
        TicketLine free = new TicketLine();
        free.totalPrice = BigDecimal.ONE;
        free.vatRate = null;
        blank.lines.add(free);
        Refund refund = new Refund();
        refund.refundNumber = "R1";
        refund.terminalId = "C01";
        refund.creationDate = LocalDateTime.of(2026, 9, 16, 9, 0);
        refund.refundMethod = Refund.RefundMethod.CASH;
        refund.totalAmount = new BigDecimal("-3.00");
        Refund bare = new Refund();
        bare.refundNumber = "R2";
        bare.terminalId = "C09";
        bare.creationDate = LocalDateTime.of(2026, 9, 17, 9, 0);
        Invoice invoice = new Invoice();
        invoice.documentNumber = "C01-F1";
        invoice.customerName = "ACME";
        CashMovement movement = new CashMovement();
        movement.movementUid = "M1";
        movement.session = session;
        movement.type = CashMovement.MovementType.WITHDRAWAL;
        movement.amount = new BigDecimal("50");
        CashMovement loose = new CashMovement();
        loose.movementUid = "M2";
        FiscalDocument document = new FiscalDocument();
        document.number = "FS C01/1";
        document.documentType = FiscalDocument.Type.FS;
        FiscalDocument cancelled = new FiscalDocument();
        cancelled.number = "FS C01/2";
        cancelled.cancelled = true;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<FiscalArchive> created = mockConstruction(FiscalArchive.class)) {
            stubPeriod(panache, List.of(first, second, parked, chained, next, blank), List.of(refund, bare),
                    List.of(invoice), List.of(session), List.of(movement, loose), List.of(document, cancelled));
            List<FiscalArchive> archives = service.generate(FROM, TO, "admin", false);
            assertEquals(2, archives.size());
            FiscalArchive extracts = created.constructed().get(0);
            FiscalArchive reports = created.constructed().get(1);
            assertEquals(FiscalArchive.Kind.TAX_AUDIT_EXTRACTS, extracts.kind);
            assertEquals("TaxAuditExtracts_07039_20260930.zip", extracts.fileName);
            assertEquals("TaxAuditReports_07039_20260930.zip", reports.fileName);
            assertEquals("07039", extracts.pdvNumber);
            assertEquals(FROM, extracts.periodFrom);
            assertEquals(TO, extracts.periodTo);
            assertEquals("admin", extracts.generatedBy);
            assertFalse(extracts.automatic);
            assertEquals(FiscalFiles.sha256(extracts.content), extracts.sha256);
            assertEquals(extracts.content.length, extracts.sizeBytes);
            verify(extracts).persist();
            Map<String, String> data = unzip(extracts.content);
            assertEquals(List.of("LISEZMOI.txt", "tickets.csv", "lignes.csv", "reglements.csv", "tva.csv",
                    "retours.csv", "factures.csv", "sessions.csv", "mouvements.csv", "documents-fiscaux.csv",
                    "empreintes.txt"), List.copyOf(data.keySet()));
            assertTrue(data.get("tickets.csv").contains("T1;C01;C01-S00001;2026-09-15T10:00;2026-09-15T10:05;CLOSED;"
                    + "jdupont;10.00;2.00;12.00;0;S1;"));
            assertTrue(data.get("tickets.csv").contains("T3;C02;;"));
            assertTrue(data.get("lignes.csv").contains("T1;1;U1;3000000000001;Café;1;12.00;12.00;0.20;N"));
            assertTrue(data.get("lignes.csv").contains(";O\n"));
            assertTrue(data.get("reglements.csv").contains("T1;CASH;12.00"));
            assertTrue(data.get("tva.csv").contains("T1;0.2;10.00;2.00;12.00"));
            assertTrue(data.get("retours.csv").contains("R1;;C01;2026-09-16T09:00;CASH;0.00;0.00;-3.00;"));
            assertTrue(data.get("factures.csv").contains("C01-F1;FACTURE;"));
            assertTrue(data.get("mouvements.csv").contains("M1;;C01-S00001;WITHDRAWAL;;;50.00;"));
            assertTrue(data.get("mouvements.csv").contains("M2;;;;;;0.00;"));
            assertTrue(data.get("documents-fiscaux.csv").contains("FS C01/1;FS;"));
            assertTrue(data.get("documents-fiscaux.csv").contains(";O\n"));
            Map<String, String> aggregates = unzip(reports.content);
            assertEquals(List.of("ca-journalier.csv", "tva-par-taux.csv", "reglements-par-moyen.csv", "sessions.csv",
                    "synthese.txt", "empreintes.txt"), List.copyOf(aggregates.keySet()));
            assertTrue(aggregates.get("ca-journalier.csv").contains("2026-09-15;C01;2;20.00;4.00;24.00;0.00;24.00"));
            assertTrue(aggregates.get("ca-journalier.csv").contains("2026-09-16;C01;0;0.00;0.00;0.00;3.00;-3.00"));
            assertTrue(aggregates.get("ca-journalier.csv").contains("2026-09-17;C09;0;0.00;0.00;0.00;0.00;0.00"));
            assertTrue(aggregates.get("tva-par-taux.csv").contains("2026-09-15;0.2;2;20.00;4.00;24.00"));
            assertTrue(aggregates.get("reglements-par-moyen.csv").contains("2026-09-15;CASH;1;12.00"));
            String synthesis = aggregates.get("synthese.txt");
            assertTrue(synthesis.contains("Point de vente : 07039"));
            assertTrue(synthesis.contains("Tickets enregistrés : 6"));
            assertTrue(synthesis.contains("Tickets clos : 2"));
            assertTrue(synthesis.contains("Net TTC : 21.00"));
            assertTrue(synthesis.contains("Chaînage des signatures, caisse C01 : 1 rupture(s)"));
            assertFalse(synthesis.contains("intègre"));
        }
    }

    /**
     * An empty period gives archives whose synthesis says the chain is whole;
     * a node knowing no point of sale names its files 00000, and an archive
     * produced by nobody named is the node's.
     *
     * @throws IOException never
     */
    @Test
    void anEmptyPeriodHasAWholeChain() throws IOException {
        FiscalArchiveService service = service(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<FiscalArchive> created = mockConstruction(FiscalArchive.class)) {
            stubPeriod(panache, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            service.generate(FROM, TO, " ", true);
            FiscalArchive reports = created.constructed().get(1);
            assertEquals("TaxAuditReports_00000_20260930.zip", reports.fileName);
            assertEquals(FiscalArchiveService.NODE, reports.generatedBy);
            assertTrue(reports.automatic);
            assertTrue(unzip(reports.content).get("synthese.txt").contains("Chaînage des signatures : intègre"));
            service.generate(FROM, TO, null, true);
            assertEquals(FiscalArchiveService.NODE, created.constructed().get(2).generatedBy);
        }
        assertEquals("00000", service(" ").pdvNumber());
    }

    /**
     * The monthly SAF-T file is named as an XML file.
     */
    @Test
    void themonthlySaftFileIsAnXmlFile() {
        assertEquals("SAFT-PT_07039_20260930.xml",
                FiscalArchiveService.fileName(FiscalArchive.Kind.SAFT_MONTHLY, "07039", TO));
        assertEquals("", FiscalArchiveService.dayOf(null));
    }

    /**
     * The month just ended is produced once when the back office asks for it,
     * never when it does not, never twice.
     */
    @Test
    @SuppressWarnings("unchecked")
    void themonthJustEndedIsArchivedOnce() {
        FiscalArchiveService service = service("07039");
        LocalDate today = LocalDate.of(2026, 10, 3);
        assertFalse(service.autoArchive(today));
        when(service.posSettingsService.fiscalArchiveAuto()).thenReturn(true);
        PanacheQuery<FiscalArchive> done = mock(PanacheQuery.class);
        when(done.list()).thenReturn(List.of(new FiscalArchive()), List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<FiscalArchive> created = mockConstruction(FiscalArchive.class)) {
            panache.when(() -> PanacheEntityBase.find("kind = ?1 and periodFrom = ?2 and periodTo = ?3 order by id desc",
                    FiscalArchive.Kind.TAX_AUDIT_EXTRACTS, FROM, TO)).thenReturn(done);
            stubPeriod(panache, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            assertFalse(service.autoArchive(today));
            assertTrue(service.autoArchive(today));
            assertEquals(2, created.constructed().size());
            assertTrue(created.constructed().get(0).automatic);
        }
    }

    /**
     * The signature chain counts a break only between two signed tickets of
     * one register whose link does not hold.
     */
    @Test
    void thechainCountsOnlyRealBreaks() {
        Map<String, String> last = new HashMap<>();
        Map<String, Integer> breaks = new HashMap<>();
        Ticket unsigned = new Ticket();
        FiscalArchiveService.chain(unsigned, last, breaks);
        assertTrue(last.isEmpty());
        Ticket a = new Ticket();
        a.signature = "A";
        FiscalArchiveService.chain(a, last, breaks);
        Ticket b = new Ticket();
        b.signature = "B";
        b.previousSignature = "A";
        FiscalArchiveService.chain(b, last, breaks);
        assertTrue(breaks.isEmpty());
        Ticket c = new Ticket();
        c.signature = "C";
        c.previousSignature = "X";
        FiscalArchiveService.chain(c, last, breaks);
        assertEquals(Map.of("null", 1), breaks);
    }
}
