package com.intermarche.edge.ui.journal;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JournalResource}.
 * <p>
 * The resource is a thin GET adapter: it parses the URI into a
 * {@link JournalCriteria}, delegates to a mocked {@link JournalService}, and
 * wires a mocked Qute template. Each endpoint has a single path (no
 * conditional branches), so one test per endpoint pins the delegation and the
 * response shape; {@code baseQuery} is exercised separately on its skip-page
 * and multi-value arms. No database, no Quarkus context.
 */
class JournalResourceTest {

    /**
     * Builds a resource over mocked templates and service.
     *
     * @return the wired resource
     */
    private JournalResource newResource() {
        JournalResource resource = new JournalResource();
        resource.journal = mock(Template.class);
        resource.journalDetail = mock(Template.class);
        resource.journalService = mock(JournalService.class);
        resource.journalDuplicate = mock(Template.class);
        return resource;
    }

    /**
     * Wires a self-returning template instance on the page template.
     *
     * @param template the template to wire
     * @return the mocked instance the chain returns
     */
    private TemplateInstance wire(Template template) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(template.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds a UriInfo whose query parameters are empty.
     *
     * @return the mocked UriInfo
     */
    private UriInfo emptyUri() {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        return uriInfo;
    }

    /**
     * Builds an empty result page of the service's page size.
     *
     * @param <T> the row type
     * @return the empty page
     */
    private <T> JournalPage<T> emptyPage() {
        return new JournalPage<>(List.of(), 1, JournalService.PAGE_SIZE, 0);
    }

    /**
     * The transactional endpoint runs the ticket search and renders the page
     * with the transactional tab.
     */
    @Test
    void transactionalRendersTicketSearch() {
        JournalResource resource = newResource();
        TemplateInstance instance = wire(resource.journal);
        JournalPage<JournalRow> page = emptyPage();
        when(resource.journalService.search(any())).thenReturn(page);
        assertSame(instance, resource.transactional(emptyUri()));
        verify(resource.journal).data("tab", "transactional");
        verify(instance).data("tickets", page);
        verify(resource.journalService).search(any());
    }

    /**
     * The functional endpoint runs the event search and renders the page with
     * the functional tab.
     */
    @Test
    void functionalRendersEventSearch() {
        JournalResource resource = newResource();
        TemplateInstance instance = wire(resource.journal);
        JournalPage<JournalEventRow> page = emptyPage();
        when(resource.journalService.searchEvents(any())).thenReturn(page);
        assertSame(instance, resource.functional(emptyUri()));
        verify(resource.journal).data("tab", "functional");
        verify(instance).data("events", page);
        verify(resource.journalService).searchEvents(any());
    }

    /**
     * The movements endpoint runs the cash-movement search and renders the page
     * with the movements tab.
     */
    @Test
    void movementsRendersMovementSearch() {
        JournalResource resource = newResource();
        TemplateInstance instance = wire(resource.journal);
        JournalPage<JournalMovementRow> page = emptyPage();
        when(resource.journalService.searchMovements(any())).thenReturn(page);
        assertSame(instance, resource.movements(emptyUri()));
        verify(resource.journal).data("tab", "movements");
        verify(instance).data("movements", page);
        verify(resource.journalService).searchMovements(any());
    }

    /**
     * The detail endpoint materializes the ticket and renders the detail
     * template.
     */
    @Test
    void detailRendersTicket() {
        JournalResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.journalDetail.data(anyString(), any())).thenReturn(instance);
        JournalTicketDetail detail = new JournalTicketDetail("C04-00000001", "C04", "CLOSED",
                "31/08/2026", "10:00", "Jean Dupont", "12341234", "IM Lyon", "",
                "3,00", "2,85", "0,15", List.of(), List.of(), List.of());
        when(resource.journalService.buildDetail(1L)).thenReturn(detail);
        assertSame(instance, resource.detail(1L));
        verify(resource.journalDetail).data("ticket", detail);
    }

    /**
     * The export endpoint returns the CSV as a downloadable attachment.
     */
    @Test
    void exportReturnsCsvAttachment() {
        JournalResource resource = newResource();
        resource.i18n = mock(com.intermarche.edge.ui.admin.BackOfficeMessages.class);
        when(resource.journalService.exportCsv(any(), any())).thenReturn("H\nrow\n");
        Response response = resource.export(emptyUri());
        assertEquals(200, response.getStatus());
        assertEquals("H\nrow\n", response.getEntity());
        assertTrue(response.getHeaderString("Content-Disposition").contains("journal.csv"));
    }

    /**
     * {@code baseQuery} is empty when the request carries no criterion (empty
     * map arm).
     */
    @Test
    void baseQueryIsEmptyWithoutCriteria() {
        assertEquals("", newResource().baseQuery(emptyUri()));
    }

    /**
     * {@code baseQuery} re-encodes every criterion, repeats a multi-valued key
     * once per value and drops the page parameter, so a pager link keeps the
     * search and moves only the page (skip arm and kept arm).
     */
    @Test
    void baseQueryKeepsCriteriaAndDropsThePage() {
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("text", "lait demi");
        params.put("method", List.of("CARD", "CASH"));
        params.putSingle("page", "4");
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(params);
        String query = newResource().baseQuery(uriInfo);
        assertTrue(query.contains("text=lait+demi&"));
        assertTrue(query.contains("method=CARD&"));
        assertTrue(query.contains("method=CASH&"));
        assertTrue(query.endsWith("&"));
        assertFalse(query.contains("page="));
    }
    /** LC-13-05-01: consolidated per-key counts preserve identifiers and CSV quoting. */
    @Test
    void exportsKeyUsageCounters() {
        JournalResource resource = newResource();
        resource.keyUsageEntityManager = mock(jakarta.persistence.EntityManager.class);
        @SuppressWarnings("unchecked")
        jakarta.persistence.TypedQuery<Object[]> query = mock(jakarta.persistence.TypedQuery.class);
        when(resource.keyUsageEntityManager.createQuery(anyString(), org.mockito.ArgumentMatchers.eq(Object[].class))).thenReturn(query);
        when(query.setParameter("type", com.intermarche.edge.domain.session.TechnicalEvent.EventType.TOUCH_USED)).thenReturn(query);
        when(query.getResultList()).thenReturn(java.util.Collections.singletonList(new Object[] {"key;\"one", "C04", null, 2L}));
        Response response = resource.keyUsage();
        assertTrue(response.getEntity().toString().contains("\"key;\"\"one\";\"C04\";\"\";2\n"));
        assertTrue(response.getHeaderString("Content-Disposition").contains("key-usage.csv"));
    }

    /** LC-13-05-01: an unused register produces a valid empty counter export. */
    @Test
    void exportsEmptyKeyUsageCounters() {
        JournalResource resource = newResource();
        resource.keyUsageEntityManager = mock(jakarta.persistence.EntityManager.class);
        @SuppressWarnings("unchecked")
        jakarta.persistence.TypedQuery<Object[]> query = mock(jakarta.persistence.TypedQuery.class);
        when(resource.keyUsageEntityManager.createQuery(anyString(), org.mockito.ArgumentMatchers.eq(Object[].class))).thenReturn(query);
        when(query.setParameter("type", com.intermarche.edge.domain.session.TechnicalEvent.EventType.TOUCH_USED)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        assertEquals("key;register;operator;count\n", resource.keyUsage().getEntity());
    }

    /**
     * Records what a template binds.
     *
     * @param template the template
     * @param bound the map receiving the bound values
     * @return the instance the chain returns
     */
    private TemplateInstance record(Template template, java.util.Map<String, Object> bound) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(template.data(anyString(), any())).thenAnswer(inv -> {
            bound.put(inv.getArgument(0), inv.getArgument(1));
            return instance;
        });
        when(instance.data(anyString(), any())).thenAnswer(inv -> {
            bound.put(inv.getArgument(0), inv.getArgument(1));
            return instance;
        });
        return instance;
    }

    /**
     * LC-13-05-06: the training tab reads the training table over the period
     * asked — today when no day or an unreadable one is given, the first day
     * when the last precedes it — and totals it, a sale without total counting
     * for nothing.
     */
    @Test
    void theTrainingTabReadsThePeriod() {
        JournalResource resource = newResource();
        resource.journalTraining = mock(Template.class);
        java.util.Map<String, Object> bound = new java.util.HashMap<>();
        TemplateInstance instance = record(resource.journalTraining, bound);
        com.intermarche.edge.domain.sale.TrainingTicket first = new com.intermarche.edge.domain.sale.TrainingTicket();
        first.id = 1L;
        first.totalIncludingTax = new java.math.BigDecimal("3.72");
        first.issuedAt = java.time.LocalDateTime.of(2026, 10, 24, 10, 30, 19);
        first.terminalId = "C04";
        first.cashierLogin = "jdupont";
        first.itemCount = 2;
        first.content = "[[IMAGE 384 72 AAAA]]\nMODE FORMATION";
        com.intermarche.edge.domain.sale.TrainingTicket blank = new com.intermarche.edge.domain.sale.TrainingTicket();
        java.time.LocalDate today = java.time.LocalDate.now();
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.edge.domain.sale.TrainingTicket.list(
                    "parkedAt is null and issuedAt >= ?1 and issuedAt < ?2 order by issuedAt desc",
                    java.time.LocalDate.of(2026, 10, 1).atStartOfDay(),
                    java.time.LocalDate.of(2026, 10, 25).atStartOfDay())).thenReturn(List.of(first, blank));
            assertSame(instance, resource.training("2026-10-01", " 2026-10-24 "));
            assertEquals("2026-10-01", bound.get("from"));
            assertEquals("2026-10-24", bound.get("to"));
            assertEquals("3,72", bound.get("total"));
            List<?> rows = (List<?>) bound.get("rows");
            assertEquals(java.util.Map.of("id", 1L, "issuedAt", "24/10/2026 10:30", "terminalId", "C04",
                    "cashierLogin", "jdupont", "itemCount", 2, "total", "3,72", "content", "MODE FORMATION"),
                    rows.get(0));
            java.util.Map<String, Object> empty = new java.util.HashMap<>();
            empty.put("id", null);
            empty.put("issuedAt", "");
            empty.put("terminalId", "");
            empty.put("cashierLogin", "");
            empty.put("itemCount", 0);
            empty.put("total", "0,00");
            empty.put("content", "");
            assertEquals(empty, rows.get(1));
            resource.training(null, "nonsense");
            assertEquals(today.toString(), bound.get("from"));
            assertEquals(today.toString(), bound.get("to"));
            resource.training(" ", null);
            assertEquals(today.toString(), bound.get("from"));
            resource.training("2026-10-24", "2026-10-01");
            assertEquals("2026-10-24", bound.get("to"));
        }
    }

    /**
     * LC-13-05-06: a training sale's detail shows the row the id names, or none.
     */
    @Test
    void theTrainingDetailShowsTheRow() {
        JournalResource resource = newResource();
        resource.journalTrainingDetail = mock(Template.class);
        TemplateInstance instance = mock(TemplateInstance.class);
        com.intermarche.edge.domain.sale.TrainingTicket training = new com.intermarche.edge.domain.sale.TrainingTicket();
        training.id = 5L;
        java.util.Map<String, Object> bound = new java.util.HashMap<>();
        when(resource.journalTrainingDetail.data(anyString(), any())).thenAnswer(inv -> {
            bound.put(inv.getArgument(0), inv.getArgument(1));
            return instance;
        });
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.edge.domain.sale.TrainingTicket.findById(5L)).thenReturn(training);
            assertSame(instance, resource.trainingDetail(5L));
            assertEquals(5L, ((java.util.Map<?, ?>) bound.get("training")).get("id"));
            assertSame(instance, resource.trainingDetail(6L));
            assertEquals(null, bound.get("training"));
        }
    }

    /**
     * A signed record of a ticket on hold.
     *
     * @param terminal the register
     * @param source the register's id
     * @param event what happened, or null
     * @param previous the previous signature
     * @return the record
     */
    private com.intermarche.edge.domain.sale.ParkedTicketRecord parkedRecord(String terminal, long source,
            com.intermarche.edge.domain.sale.ParkedTicketRecord.Event event, String previous) {
        com.intermarche.edge.domain.sale.ParkedTicketRecord record = new com.intermarche.edge.domain.sale.ParkedTicketRecord();
        record.terminalId = terminal;
        record.sourceId = source;
        record.ticketNumber = terminal + "-00000004";
        record.event = event;
        record.eventDate = java.time.LocalDateTime.of(2026, 10, 24, 10, 15, 30);
        record.operator = "12341234";
        record.itemCount = 2;
        record.totalAmount = new java.math.BigDecimal("5.30");
        record.content = "2 x LAIT @ 1.15 = 2.3\n";
        record.previousSignature = previous;
        record.signature = record.computeSignature();
        return record;
    }

    /**
     * LC-04-01-03: the tickets-on-hold tab lists the signed records of the period,
     * each event named, and states each register's chain checked over all its
     * records — intact, or broken at the record that breaks it.
     */
    @Test
    void theParkedTabListsTheRecordsAndChecksTheChains() {
        JournalResource resource = newResource();
        resource.journalParked = mock(Template.class);
        java.util.Map<String, Object> bound = new java.util.HashMap<>();
        TemplateInstance instance = record(resource.journalParked, bound);
        com.intermarche.edge.domain.sale.ParkedTicketRecord parked = parkedRecord("POS01", 1L,
                com.intermarche.edge.domain.sale.ParkedTicketRecord.Event.PARKED,
                com.intermarche.edge.domain.sale.ParkedTicketRecord.GENESIS);
        com.intermarche.edge.domain.sale.ParkedTicketRecord resumed = parkedRecord("POS01", 2L,
                com.intermarche.edge.domain.sale.ParkedTicketRecord.Event.RESUMED, parked.signature);
        com.intermarche.edge.domain.sale.ParkedTicketRecord purged = parkedRecord("POS02", 5L,
                com.intermarche.edge.domain.sale.ParkedTicketRecord.Event.PURGED, "FORGED");
        com.intermarche.edge.domain.sale.ParkedTicketRecord blank = new com.intermarche.edge.domain.sale.ParkedTicketRecord();
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.edge.domain.sale.ParkedTicketRecord.list(
                    "eventDate >= ?1 and eventDate < ?2 order by terminalId, eventDate, id",
                    java.time.LocalDate.of(2026, 10, 24).atStartOfDay(),
                    java.time.LocalDate.of(2026, 10, 25).atStartOfDay()))
                    .thenReturn(List.of(parked, resumed, purged, blank));
            panache.when(() -> com.intermarche.edge.domain.sale.ParkedTicketRecord.list(
                    "terminalId = ?1 order by sourceId, id", "POS01")).thenReturn(List.of(parked, resumed));
            panache.when(() -> com.intermarche.edge.domain.sale.ParkedTicketRecord.list(
                    "terminalId = ?1 order by sourceId, id", "POS02")).thenReturn(List.of(purged));
            panache.when(() -> com.intermarche.edge.domain.sale.ParkedTicketRecord.list(
                    "terminalId = ?1 order by sourceId, id", "null")).thenReturn(List.of());
            assertSame(instance, resource.parked("2026-10-24", "2026-10-24"));
            assertEquals("2026-10-24", bound.get("from"));
            List<?> rows = (List<?>) bound.get("rows");
            assertEquals(4, rows.size());
            java.util.Map<?, ?> first = (java.util.Map<?, ?>) rows.get(0);
            assertEquals("24/10/2026 10:15:30", first.get("eventDate"));
            assertEquals("POS01", first.get("terminalId"));
            assertEquals("POS01-00000004", first.get("ticketNumber"));
            assertEquals("MISE EN ATTENTE", first.get("event"));
            assertEquals("12341234", first.get("operator"));
            assertEquals(2, first.get("itemCount"));
            assertEquals("5,30", first.get("total"));
            assertEquals("2 x LAIT @ 1.15 = 2.3\n", first.get("content"));
            assertEquals(parked.signature.substring(0, 16), first.get("signature"));
            assertEquals("REPRISE", ((java.util.Map<?, ?>) rows.get(1)).get("event"));
            assertEquals("PURGE À LA CLÔTURE", ((java.util.Map<?, ?>) rows.get(2)).get("event"));
            java.util.Map<?, ?> empty = (java.util.Map<?, ?>) rows.get(3);
            assertEquals("", empty.get("eventDate"));
            assertEquals("", empty.get("terminalId"));
            assertEquals("", empty.get("ticketNumber"));
            assertEquals("", empty.get("event"));
            assertEquals("", empty.get("operator"));
            assertEquals("0,00", empty.get("total"));
            assertEquals("", empty.get("content"));
            assertEquals("", empty.get("signature"));
            List<?> chains = (List<?>) bound.get("chains");
            assertEquals(java.util.Map.of("terminalId", "POS01", "state", "INTÈGRE (2 enregistrement(s))",
                    "intact", "true"), chains.get(0));
            assertEquals(java.util.Map.of("terminalId", "POS02",
                    "state", "ROMPUE à l'enregistrement 5 (ticket POS02-00000004)", "intact", "false"), chains.get(1));
            assertEquals("INTÈGRE (0 enregistrement(s))", ((java.util.Map<?, ?>) chains.get(2)).get("state"));
            purged.signature = "ab";
            resource.parked("2026-10-24", "2026-10-01");
            assertEquals("2026-10-24", bound.get("to"));
            assertEquals("ab", ((java.util.Map<?, ?>) ((List<?>) bound.get("rows")).get(2)).get("signature"));
        }
    }

    /**
     * BO-03-06-62: the deposit slips the tills printed are listed beside the
     * instruments, most recent first — the order's reference as their
     * barcode, no balance and no end of validity; a slip without reference or
     * movement id shows a dash and nothing.
     */
    @Test
    void theIssuedBarcodesTabListsTheDepositSlips() {
        JournalResource resource = newResource();
        resource.journalBarcodesIssued = mock(Template.class);
        java.util.Map<String, Object> bound = new java.util.HashMap<>();
        record(resource.journalBarcodesIssued, bound);
        com.intermarche.edge.domain.barcode.StoredValueRecord note =
                new com.intermarche.edge.domain.barcode.StoredValueRecord();
        note.issuingTerminalId = "POS01";
        note.number = "297000000000001";
        note.kind = "CREDIT_NOTE";
        note.initialAmount = java.math.BigDecimal.ONE;
        note.balance = java.math.BigDecimal.ONE;
        note.issuedAt = java.time.LocalDateTime.of(2026, 10, 24, 10, 0);
        com.intermarche.edge.domain.session.CashMovement deposit = new com.intermarche.edge.domain.session.CashMovement();
        deposit.terminalId = "POS03";
        deposit.movementUid = "MV-7";
        deposit.orderReference = "POS03-CMD12";
        deposit.amount = new java.math.BigDecimal("50");
        deposit.movementDate = java.time.LocalDateTime.of(2026, 10, 24, 11, 0);
        com.intermarche.edge.domain.session.CashMovement bare = new com.intermarche.edge.domain.session.CashMovement();
        bare.terminalId = "POS04";
        bare.amount = java.math.BigDecimal.TEN;
        bare.movementDate = java.time.LocalDateTime.of(2026, 10, 24, 9, 0);
        java.time.LocalDateTime from = java.time.LocalDate.of(2026, 10, 24).atStartOfDay();
        java.time.LocalDateTime to = java.time.LocalDate.of(2026, 10, 25).atStartOfDay();
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.edge.domain.barcode.StoredValueRecord.list(
                    "issuedAt >= ?1 and issuedAt < ?2 order by issuedAt desc, id desc", from, to))
                    .thenReturn(List.of(note));
            panache.when(() -> com.intermarche.edge.domain.session.CashMovement.list(
                    "type = ?1 and movementDate >= ?2 and movementDate < ?3",
                    com.intermarche.edge.domain.session.CashMovement.MovementType.CUSTOMER_DEPOSIT, from, to))
                    .thenReturn(List.of(bare, deposit));
            resource.barcodesIssued("2026-10-24", "2026-10-24");
            List<?> rows = (List<?>) bound.get("rows");
            assertEquals(3, rows.size());
            java.util.Map<?, ?> first = (java.util.Map<?, ?>) rows.get(0);
            assertEquals("POS03", first.get("terminalId"));
            assertEquals("MV-7", first.get("documentNumber"));
            assertEquals("POS03-CMD12", first.get("number"));
            assertEquals("Acompte", first.get("kind"));
            assertEquals("50,00", first.get("amount"));
            assertEquals("", first.get("balance"));
            assertEquals("24/10/2026 11:00:00", first.get("issuedAt"));
            assertEquals("—", first.get("validUntil"));
            assertEquals("297000000000001", ((java.util.Map<?, ?>) rows.get(1)).get("number"));
            java.util.Map<?, ?> last = (java.util.Map<?, ?>) rows.get(2);
            assertEquals("", last.get("documentNumber"));
            assertEquals("—", last.get("number"));
        }
    }

    /**
     * BO-03-06-62: the printed-barcodes tab lists the instruments the tills
     * printed over the period — register, transaction, kind, amount, balance,
     * date of printing and end of validity — a card named as such, a note
     * without document nor dates shown blank; a last day before the first is
     * taken as the first.
     */
    @Test
    void theIssuedBarcodesTabListsThePrintedInstruments() {
        JournalResource resource = newResource();
        resource.journalBarcodesIssued = mock(Template.class);
        java.util.Map<String, Object> bound = new java.util.HashMap<>();
        TemplateInstance instance = record(resource.journalBarcodesIssued, bound);
        com.intermarche.edge.domain.barcode.StoredValueRecord card =
                new com.intermarche.edge.domain.barcode.StoredValueRecord();
        card.issuingTerminalId = "POS02";
        card.issuingDocumentNumber = "POS02-00000009";
        card.number = "296000000000004";
        card.kind = "GIFT_CARD";
        card.initialAmount = new java.math.BigDecimal("30");
        card.balance = new java.math.BigDecimal("12.5");
        card.issuedAt = java.time.LocalDateTime.of(2026, 10, 24, 9, 30, 15);
        card.validUntil = java.time.LocalDateTime.of(2027, 10, 24, 0, 0);
        com.intermarche.edge.domain.barcode.StoredValueRecord note =
                new com.intermarche.edge.domain.barcode.StoredValueRecord();
        note.issuingTerminalId = "POS01";
        note.number = "297000000000001";
        note.kind = "CREDIT_NOTE";
        note.initialAmount = java.math.BigDecimal.ONE;
        note.balance = java.math.BigDecimal.ZERO;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.edge.domain.barcode.StoredValueRecord.list(
                    "issuedAt >= ?1 and issuedAt < ?2 order by issuedAt desc, id desc",
                    java.time.LocalDate.of(2026, 10, 24).atStartOfDay(),
                    java.time.LocalDate.of(2026, 10, 25).atStartOfDay()))
                    .thenReturn(List.of(card, note));
            assertSame(instance, resource.barcodesIssued("2026-10-24", "2026-10-01"));
            assertEquals("2026-10-24", bound.get("from"));
            assertEquals("2026-10-24", bound.get("to"));
            List<?> rows = (List<?>) bound.get("rows");
            java.util.Map<?, ?> first = (java.util.Map<?, ?>) rows.get(0);
            assertEquals("POS02", first.get("terminalId"));
            assertEquals("POS02-00000009", first.get("documentNumber"));
            assertEquals("296000000000004", first.get("number"));
            assertEquals("Carte cadeau", first.get("kind"));
            assertEquals("30,00", first.get("amount"));
            assertEquals("12,50", first.get("balance"));
            assertEquals("24/10/2026 09:30:15", first.get("issuedAt"));
            assertEquals("24/10/2027", first.get("validUntil"));
            java.util.Map<?, ?> second = (java.util.Map<?, ?>) rows.get(1);
            assertEquals("", second.get("documentNumber"));
            assertEquals("Avoir", second.get("kind"));
            assertEquals("", second.get("issuedAt"));
            assertEquals("—", second.get("validUntil"));
            resource.barcodesIssued("2026-10-01", "2026-10-31");
            assertEquals("2026-10-31", bound.get("to"));
        }
    }

    /**
     * BO-03-06-63: the read-barcodes tab lists the payment barcodes read over
     * the period — register, transaction, range, amount, end of validity, date
     * of use and whether the code was known — a reading without them shown
     * blank.
     */
    @Test
    void theScannedBarcodesTabListsTheReadings() {
        JournalResource resource = newResource();
        resource.journalBarcodesScanned = mock(Template.class);
        java.util.Map<String, Object> bound = new java.util.HashMap<>();
        TemplateInstance instance = record(resource.journalBarcodesScanned, bound);
        com.intermarche.edge.domain.barcode.ScannedBarcode used =
                new com.intermarche.edge.domain.barcode.ScannedBarcode();
        used.terminalId = "POS01";
        used.ticketNumber = "POS01-00000010";
        used.code = "297000000000001";
        used.typeLabel = "Avoir magasin";
        used.amount = new java.math.BigDecimal("2.4");
        used.validUntil = java.time.LocalDateTime.of(2027, 1, 31, 0, 0);
        used.scannedAt = java.time.LocalDateTime.of(2026, 10, 26, 11, 0, 5);
        used.found = true;
        com.intermarche.edge.domain.barcode.ScannedBarcode unknown =
                new com.intermarche.edge.domain.barcode.ScannedBarcode();
        unknown.terminalId = "POS02";
        unknown.code = "123";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.edge.domain.barcode.ScannedBarcode.list(
                    "scannedAt >= ?1 and scannedAt < ?2 order by scannedAt desc, id desc",
                    java.time.LocalDate.of(2026, 10, 1).atStartOfDay(),
                    java.time.LocalDate.of(2026, 11, 1).atStartOfDay()))
                    .thenReturn(List.of(used, unknown));
            assertSame(instance, resource.barcodesScanned("2026-10-01", "2026-10-31"));
            List<?> rows = (List<?>) bound.get("rows");
            java.util.Map<?, ?> first = (java.util.Map<?, ?>) rows.get(0);
            assertEquals("POS01", first.get("terminalId"));
            assertEquals("POS01-00000010", first.get("ticketNumber"));
            assertEquals("297000000000001", first.get("code"));
            assertEquals("Avoir magasin", first.get("type"));
            assertEquals("2,40", first.get("amount"));
            assertEquals("31/01/2027", first.get("validUntil"));
            assertEquals("26/10/2026 11:00:05", first.get("scannedAt"));
            assertEquals(true, first.get("found"));
            java.util.Map<?, ?> second = (java.util.Map<?, ?>) rows.get(1);
            assertEquals("", second.get("ticketNumber"));
            assertEquals("", second.get("type"));
            assertEquals("—", second.get("amount"));
            assertEquals("—", second.get("validUntil"));
            assertEquals("", second.get("scannedAt"));
            assertEquals(false, second.get("found"));
            resource.barcodesScanned("2026-10-01", "2026-09-01");
            assertEquals("2026-10-01", bound.get("to"));
        }
    }

    /**
     * The duplicate of a document is rendered from the service (BO-04-01-53);
     * the search page offers the events list (BO-04-01-14).
     */
    @Test
    void duplicateAndEventsList() {
        JournalResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.journalDuplicate.data("duplicate", null)).thenReturn(instance);
        assertSame(instance, resource.duplicate(5L));
        TemplateInstance page = wire(resource.journal);
        when(resource.journalService.search(any())).thenReturn(emptyPage());
        resource.transactional(emptyUri());
        verify(page).data("journalEvents", JournalEvent.values());
    }
}
