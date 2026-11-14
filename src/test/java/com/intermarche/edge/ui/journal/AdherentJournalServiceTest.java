package com.intermarche.edge.ui.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.intermarche.edge.domain.deploy.UplinkedTicket;
import com.intermarche.edge.domain.store.Country;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.PdvHistory;
import com.intermarche.edge.service.deploy.JournalUplinkService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AdherentJournalService}. */
class AdherentJournalServiceTest {

    /**
     * Builds the service with a real JSON mapper.
     *
     * @return the service
     */
    private AdherentJournalService service() {
        AdherentJournalService service = new AdherentJournalService();
        service.objectMapper = new ObjectMapper();
        return service;
    }

    /**
     * Builds a point of sale.
     *
     * @param number its number
     * @param name its name
     * @param enseigne its enseigne
     * @param adherent its adhérent
     * @return the point of sale
     */
    private Pdv pdv(String number, String name, String enseigne, String adherent) {
        Pdv pdv = new Pdv();
        pdv.pdvNumber = number;
        pdv.name = name;
        pdv.enseigneCode = enseigne;
        pdv.adherentCode = adherent;
        return pdv;
    }

    /**
     * Builds an enseigne.
     *
     * @param code its code
     * @param country its country
     * @return the enseigne
     */
    private Enseigne enseigne(String code, String country) {
        Enseigne enseigne = new Enseigne();
        enseigne.code = code;
        enseigne.countryCode = country;
        return enseigne;
    }

    /**
     * Builds a received ticket.
     *
     * @param id its id
     * @param pdv the number it is filed under
     * @param badge the cashier badge, possibly null
     * @param total the total, possibly null
     * @return the row
     */
    private UplinkedTicket ticket(long id, String pdv, String badge, BigDecimal total) {
        UplinkedTicket ticket = new UplinkedTicket();
        ticket.id = id;
        ticket.pdvNumber = pdv;
        ticket.ticketNumber = "POS01-" + id;
        ticket.terminalId = "POS01";
        ticket.cashierBadge = badge;
        ticket.status = "CLOSED";
        ticket.kind = UplinkedTicket.SALE;
        ticket.ticketDate = LocalDateTime.of(2026, 10, 25, 16, 50);
        ticket.totalIncludingTax = total;
        ticket.itemCount = 2;
        ticket.sourceUpdatedAt = LocalDateTime.of(2026, 10, 25, 16, 50, 1);
        ticket.receivedAt = LocalDateTime.of(2026, 10, 25, 16, 51);
        return ticket;
    }

    /**
     * Makes a query answer a count and a page of rows.
     *
     * @param total the count
     * @param rows the rows
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<PanacheEntityBase> query(long total, List<? extends PanacheEntityBase> rows) {
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.count()).thenReturn(total);
        when(query.page(any(Page.class))).thenReturn(query);
        when(query.list()).thenReturn((List<PanacheEntityBase>) rows);
        return query;
    }

    /** BO-02-05-05: without echelon filter every ticket is read, each row naming its adhérent and point of sale. */
    @Test
    @SuppressWarnings("unchecked")
    void anUnfilteredSearchReadsEveryPointOfSale() {
        AdherentJournalService service = service();
        Pdv lyon = pdv("10001", "Lyon", "IF", "AD1");
        Pdv moved = pdv("10004", "Porto", "IP", null);
        UplinkedTicket first = ticket(1L, "10001", "123", new BigDecimal("36"));
        UplinkedTicket second = ticket(2L, "10001", null, null);
        UplinkedTicket former = ticket(3L, "10003", "9", BigDecimal.ONE);
        UplinkedTicket stranger = ticket(4L, "99999", "9", BigDecimal.ONE);
        PanacheQuery<PanacheEntityBase> query = query(250, List.of(first, second, former, stranger));
        AdherentJournalCriteria criteria = new AdherentJournalCriteria();
        criteria.page = 9;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<PdvHistory> history = mockStatic(PdvHistory.class)) {
            ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
            panache.when(() -> PanacheEntityBase.find(any(String.class), any(Sort.class), params.capture()))
                    .thenReturn(query);
            panache.when(() -> PanacheEntityBase.findById(40L)).thenReturn(moved);
            pdvs.when(() -> Pdv.findByNumber("10001")).thenReturn(lyon);
            history.when(() -> PdvHistory.formerHolderOf("10003")).thenReturn(40L);
            history.when(() -> PdvHistory.formerHolderOf("99999")).thenReturn(null);
            JournalPage<AdherentJournalRow> page = service.search(criteria);
            assertEquals(250, page.total);
            assertEquals(3, page.number);
            assertTrue(params.getValue().isEmpty());
            assertEquals(4, page.rows.size());
            AdherentJournalRow row = page.rows.get(0);
            assertEquals("AD1", row.adherent);
            assertEquals("Lyon", row.pdvName);
            assertEquals("123", row.cashier);
            assertEquals("36,00", row.amount);
            assertEquals("25/10/2026", row.date);
            assertEquals("16:50", row.time);
            assertEquals(2, row.itemCount);
            assertEquals(UplinkedTicket.SALE, row.kind);
            assertEquals("", page.rows.get(1).cashier);
            assertEquals("0,00", page.rows.get(1).amount);
            assertEquals("—", page.rows.get(2).adherent);
            assertEquals("Porto (ancien n°, actuel 10004)", page.rows.get(2).pdvName);
            assertEquals("—", page.rows.get(3).adherent);
            assertEquals("", page.rows.get(3).pdvName);
            pdvs.verify(() -> Pdv.findByNumber("10001"), org.mockito.Mockito.times(1));
        }
    }

    /** A page below the first is read as the first. */
    @Test
    void aPageBelowTheFirstIsTheFirst() {
        AdherentJournalService service = service();
        PanacheQuery<PanacheEntityBase> query = query(0, List.of());
        AdherentJournalCriteria criteria = new AdherentJournalCriteria();
        criteria.page = 0;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Sort.class), anyMap())).thenReturn(query);
            assertEquals(1, service.search(criteria).number);
        }
    }

    /** An echelon filter that keeps no point of sale reads no ticket. */
    @Test
    void aFilterKeepingNoPointOfSaleReadsNothing() {
        AdherentJournalService service = service();
        AdherentJournalCriteria criteria = new AdherentJournalCriteria();
        criteria.adherent = "AD9";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<Enseigne> enseignes = mockStatic(Enseigne.class)) {
            pdvs.when(Pdv::listAllOrdered).thenReturn(List.of(pdv("10001", "Lyon", "IF", "AD1")));
            enseignes.when(Enseigne::listAllOrdered).thenReturn(List.of());
            JournalPage<AdherentJournalRow> page = service.search(criteria);
            assertEquals(0, page.total);
            assertTrue(page.rows.isEmpty());
            panache.verify(() -> PanacheEntityBase.find(anyString(), any(Sort.class), anyMap()), never());
        }
    }

    /** An echelon filter that keeps points of sale reads their tickets only. */
    @Test
    @SuppressWarnings("unchecked")
    void aFilterKeepingPointsOfSaleReadsTheirTickets() {
        AdherentJournalService service = service();
        AdherentJournalCriteria criteria = new AdherentJournalCriteria();
        criteria.adherent = "AD1";
        PanacheQuery<PanacheEntityBase> query = query(0, List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<Enseigne> enseignes = mockStatic(Enseigne.class);
             MockedStatic<JournalUplinkService> uplink = mockStatic(JournalUplinkService.class)) {
            pdvs.when(Pdv::listAllOrdered).thenReturn(List.of(pdv("10001", "Lyon", "IF", "AD1")));
            enseignes.when(Enseigne::listAllOrdered).thenReturn(List.of(enseigne("IF", "FRA")));
            uplink.when(() -> JournalUplinkService.numbersOf("10001")).thenReturn(Set.of("10001", "10003"));
            ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Sort.class), params.capture()))
                    .thenReturn(query);
            service.search(criteria);
            assertEquals(Set.of("10001", "10003"), params.getValue().get("pdvs"));
        }
    }

    /** Each echelon filter keeps only the points of sale it names. */
    @Test
    void eachEchelonFilterKeepsItsPointsOfSale() {
        AdherentJournalService service = service();
        AdherentJournalCriteria criteria = new AdherentJournalCriteria();
        criteria.adherent = "AD1";
        criteria.enseigne = "IF";
        criteria.country = "FRA";
        criteria.pdv = "10001";
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<Enseigne> enseignes = mockStatic(Enseigne.class);
             MockedStatic<JournalUplinkService> uplink = mockStatic(JournalUplinkService.class)) {
            pdvs.when(Pdv::listAllOrdered).thenReturn(List.of(
                    pdv("10002", "Other adherent", "IF", "AD2"),
                    pdv("10003", "Other enseigne", "IP", "AD1"),
                    pdv("10004", "Other country", "IB", "AD1"),
                    pdv("10005", "Other number", "IF", "AD1"),
                    pdv("10001", "Kept", "IF", "AD1")));
            enseignes.when(Enseigne::listAllOrdered).thenReturn(List.of(enseigne("IF", "FRA"), enseigne("IP", "PRT"),
                    enseigne("IB", "BEL")));
            uplink.when(() -> JournalUplinkService.numbersOf("10001")).thenReturn(new LinkedHashSet<>(List.of("10001")));
            uplink.when(() -> JournalUplinkService.numbersOf("10004")).thenReturn(new LinkedHashSet<>(List.of("10004")));
            assertEquals(Set.of("10001"), service.numbers(criteria));
            AdherentJournalCriteria belgium = new AdherentJournalCriteria();
            belgium.adherent = "AD1";
            belgium.country = "BEL";
            assertEquals(Set.of("10004"), service.numbers(belgium));
            uplink.verify(() -> JournalUplinkService.numbersOf(anyString()), org.mockito.Mockito.times(3));
        }
        assertNull(service.numbers(new AdherentJournalCriteria()));
    }

    /**
     * A renumbered point of sale is found by its FORMER number too: the
     * search by 10002 keeps the point of sale now numbered 10008, with all
     * its numbers, and the search by its current number still does.
     */
    @Test
    void aFormerNumberFindsTheRenumberedPointOfSale() {
        AdherentJournalService service = service();
        AdherentJournalCriteria former = new AdherentJournalCriteria();
        former.pdv = "10002";
        AdherentJournalCriteria current = new AdherentJournalCriteria();
        current.pdv = "10008";
        AdherentJournalCriteria unknown = new AdherentJournalCriteria();
        unknown.pdv = "10009";
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<Enseigne> enseignes = mockStatic(Enseigne.class);
             MockedStatic<JournalUplinkService> uplink = mockStatic(JournalUplinkService.class)) {
            pdvs.when(Pdv::listAllOrdered).thenReturn(List.of(pdv("10001", "Lyon", "IF", "AD1"),
                    pdv("10008", "Villeurbanne", "IF", "AD1")));
            enseignes.when(Enseigne::listAllOrdered).thenReturn(List.of(enseigne("IF", "FRA")));
            uplink.when(() -> JournalUplinkService.numbersOf("10001")).thenReturn(new LinkedHashSet<>(List.of("10001")));
            uplink.when(() -> JournalUplinkService.numbersOf("10008"))
                    .thenReturn(new LinkedHashSet<>(List.of("10008", "10002")));
            assertEquals(Set.of("10008", "10002"), service.numbers(former));
            assertEquals(Set.of("10008", "10002"), service.numbers(current));
            assertEquals(Set.of(), service.numbers(unknown));
        }
    }

    /** Every journal criterion becomes one AND-ed condition. */
    @Test
    void everyCriterionIsOneCondition() {
        AdherentJournalCriteria criteria = new AdherentJournalCriteria();
        criteria.dateFrom = LocalDate.of(2026, 10, 1);
        criteria.dateTo = LocalDate.of(2026, 10, 25);
        criteria.terminal = "pos01";
        criteria.ticket = "a2";
        criteria.amountMin = BigDecimal.ONE;
        criteria.amountMax = BigDecimal.TEN;
        criteria.kind = UplinkedTicket.CANCELLED;
        AdherentJournalService.Where where = AdherentJournalService.where(criteria, Set.of("10001"));
        assertEquals("ticketDate is not null and pdvNumber in :pdvs and ticketDate >= :from and ticketDate < :to"
                + " and upper(terminalId) = :terminal and upper(ticketNumber) like :ticket"
                + " and totalIncludingTax >= :amountMin and totalIncludingTax <= :amountMax and kind = :kind",
                where.clause);
        assertEquals(LocalDate.of(2026, 10, 1).atStartOfDay(), where.params.get("from"));
        assertEquals(LocalDate.of(2026, 10, 26).atStartOfDay(), where.params.get("to"));
        assertEquals("POS01", where.params.get("terminal"));
        assertEquals("%A2%", where.params.get("ticket"));
        assertEquals(BigDecimal.ONE, where.params.get("amountMin"));
        assertEquals(BigDecimal.TEN, where.params.get("amountMax"));
        assertEquals("CANCELLED", where.params.get("kind"));
        AdherentJournalService.Where none = AdherentJournalService.where(new AdherentJournalCriteria(), null);
        assertEquals("ticketDate is not null", none.clause);
        assertTrue(none.params.isEmpty());
    }

    /** The detail rebuilds the store's materialized detail around the ticket's origin. */
    @Test
    void theDetailRebuildsTheStoresDetail() {
        AdherentJournalService service = service();
        UplinkedTicket row = ticket(5L, "10001", "123", new BigDecimal("36"));
        row.printed = "TICKET";
        row.detail = "{\"cashier\":\"Jean Dupont\",\"store\":\"Lyon\",\"fidelityCard\":\"\",\"totalExcludingTax\":\"30,00\","
                + "\"totalVat\":\"6,00\",\"lines\":[{\"number\":1,\"label\":\"CASSEROLE\",\"ean\":\"330\",\"plu\":null,"
                + "\"quantity\":\"2\",\"total\":\"36,00\",\"modifier\":null,\"cancelled\":true}],"
                + "\"payments\":[{\"method\":\"CASH\",\"amount\":\"36,00\",\"entryMode\":null}],"
                + "\"fidelityGroups\":[{\"header\":\"Remises\",\"advantages\":[{\"label\":\"-10%\",\"amount\":\"1,00\"}]}]}";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Pdv> pdvs = mockStatic(Pdv.class)) {
            panache.when(() -> PanacheEntityBase.findById(5L)).thenReturn(row);
            pdvs.when(() -> Pdv.findByNumber("10001")).thenReturn(pdv("10001", "Lyon", "IF", "AD1"));
            AdherentJournalDetail detail = service.detail(5L);
            assertEquals("AD1", detail.adherent);
            assertEquals("10001", detail.pdv);
            assertEquals("Lyon", detail.pdvName);
            assertEquals(UplinkedTicket.SALE, detail.kind);
            assertEquals("25/10/2026 16:51", detail.receivedAt);
            assertEquals("TICKET", detail.printed);
            JournalTicketDetail ticket = detail.ticket;
            assertEquals("POS01-5", ticket.number);
            assertEquals("POS01", ticket.terminal);
            assertEquals("CLOSED", ticket.status);
            assertEquals("25/10/2026", ticket.date);
            assertEquals("16:50", ticket.time);
            assertEquals("Jean Dupont", ticket.cashier);
            assertEquals("123", ticket.cashierBadge);
            assertEquals("Lyon", ticket.store);
            assertEquals("36,00", ticket.totalIncludingTax);
            assertEquals("30,00", ticket.totalExcludingTax);
            assertEquals("6,00", ticket.totalVat);
            assertEquals(1, ticket.lines.size());
            JournalTicketDetail.Line line = ticket.lines.get(0);
            assertEquals(1, line.number);
            assertEquals("CASSEROLE", line.label);
            assertEquals("330", line.ean);
            assertEquals("", line.plu);
            assertEquals("2", line.quantity);
            assertEquals("36,00", line.total);
            assertEquals("", line.modifier);
            assertTrue(line.cancelled);
            assertEquals("CASH", ticket.payments.get(0).method);
            assertEquals("36,00", ticket.payments.get(0).amount);
            assertNull(ticket.payments.get(0).entryMode);
            assertEquals("Remises", ticket.fidelityGroups.get(0).header);
            assertEquals("-10%", ticket.fidelityGroups.get(0).advantages.get(0).label);
            assertEquals("1,00", ticket.fidelityGroups.get(0).advantages.get(0).amount);
        }
    }

    /** A detail without stored detail, badge or printout still opens, empty. */
    @Test
    void aBareDetailStillOpens() {
        AdherentJournalService service = service();
        UplinkedTicket row = ticket(6L, "99999", null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<PdvHistory> history = mockStatic(PdvHistory.class)) {
            panache.when(() -> PanacheEntityBase.findById(6L)).thenReturn(row);
            history.when(() -> PdvHistory.formerHolderOf("99999")).thenReturn(null);
            AdherentJournalDetail detail = service.detail(6L);
            assertEquals("—", detail.adherent);
            assertEquals("", detail.pdvName);
            assertEquals("", detail.printed);
            assertEquals("", detail.ticket.cashierBadge);
            assertEquals("", detail.ticket.cashier);
            assertEquals("0,00", detail.ticket.totalIncludingTax);
            assertTrue(detail.ticket.lines.isEmpty());
            assertTrue(detail.ticket.payments.isEmpty());
            assertTrue(detail.ticket.fidelityGroups.isEmpty());
            assertNull(service.detail(7L));
        }
    }

    /** An unreadable stored detail reads as an empty one. */
    @Test
    void anUnreadableDetailIsEmpty() {
        AdherentJournalService service = service();
        assertSame(MissingNode.getInstance(), service.read(null));
        assertSame(MissingNode.getInstance(), service.read("{not json"));
        assertEquals("x", service.read("{\"a\":\"x\"}").path("a").asText());
    }

    /** The form lists the adhérents once each, sorted, and the organisation. */
    @Test
    void theFormListsTheOrganisation() {
        AdherentJournalService service = service();
        List<Pdv> all = List.of(pdv("10001", "A", "IF", "AD2"), pdv("10002", "B", "IF", null),
                pdv("10003", "C", "IF", "AD1"), pdv("10004", "D", "IF", "AD2"));
        List<Country> countries = List.of(new Country());
        List<Enseigne> enseignes = List.of(enseigne("IF", "FRA"));
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<Country> country = mockStatic(Country.class);
             MockedStatic<Enseigne> enseigne = mockStatic(Enseigne.class)) {
            pdvs.when(Pdv::listAllOrdered).thenReturn(all);
            country.when(Country::listAllOrdered).thenReturn(countries);
            enseigne.when(Enseigne::listAllOrdered).thenReturn(enseignes);
            assertEquals(List.of("AD1", "AD2"), service.adherents());
            assertSame(all, service.pdvs());
            assertSame(countries, service.countries());
            assertSame(enseignes, service.enseignes());
        }
    }

    /** A holder that is not known under any number is none. */
    @Test
    void anUnknownNumberHasNoHolder() {
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<PdvHistory> history = mockStatic(PdvHistory.class)) {
            history.when(() -> PdvHistory.formerHolderOf("99999")).thenReturn(null);
            assertNull(AdherentJournalService.holderOf("99999"));
        }
        Pdv unattached = pdv("10001", "Lyon", "IF", null);
        assertEquals("—", AdherentJournalService.adherentOf(unattached));
        assertEquals("—", AdherentJournalService.adherentOf(null));
        assertFalse(AdherentJournalService.nameOf(unattached, "10001").contains("ancien"));
        assertEquals("", AdherentJournalService.text(MissingNode.getInstance(), "x"));
    }
}
