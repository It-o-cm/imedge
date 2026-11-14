package com.intermarche.edge.ui.supervision;

import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link ConsoleFilter}. */
class ConsoleFilterTest {

    /** An empty query filters nothing, grouped by point of sale over seven days. */
    @Test
    void anEmptyQueryFiltersNothing() {
        ConsoleFilter filter = ConsoleFilter.from(new MultivaluedHashMap<>());
        assertEquals(ConsoleFilter.PDV, filter.group);
        assertNull(filter.country);
        assertEquals(7, filter.days);
        assertTrue(filter.keepsStatus(false));
        assertTrue(filter.keepsEchelon("FR", "ITM", "07039"));
        assertEquals("group=PDV&days=7", filter.query());
    }

    /** Every parameter is read, trimmed, blank as absent, and reproduced for the export links. */
    @Test
    void everyParameterIsRead() {
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("group", "COUNTRY");
        params.putSingle("country", " FR ");
        params.putSingle("enseigne", "ITM");
        params.putSingle("pdv", "07039");
        params.putSingle("status", "KO");
        params.putSingle("kind", "REGISTER");
        params.putSingle("anomaly", "SYNC");
        params.putSingle("domain", "VAT_RATES");
        params.putSingle("category", "PERIPHERAL");
        params.putSingle("channel", "CAISSE");
        params.putSingle("product", "IMPOS");
        params.putSingle("lot", "L 1");
        params.putSingle("days", "30");
        ConsoleFilter filter = ConsoleFilter.from(params);
        assertEquals("FR", filter.country);
        assertEquals(30, filter.days);
        assertEquals("group=COUNTRY&country=FR&enseigne=ITM&pdv=07039&status=KO&kind=REGISTER&anomaly=SYNC"
                + "&domain=VAT_RATES&category=PERIPHERAL&channel=CAISSE&product=IMPOS&lot=L+1&days=30", filter.query());
        assertTrue(filter.keepsStatus(false));
        assertFalse(filter.keepsStatus(true));
        filter.status = "OK";
        assertTrue(filter.keepsStatus(true));
        assertTrue(filter.keepsEchelon("fr", "ITM", "07039"));
        assertFalse(filter.keepsEchelon("PT", "ITM", "07039"));
        assertFalse(filter.keepsEchelon("FR", "BRI", "07039"));
        assertFalse(filter.keepsEchelon("FR", "ITM", "07040"));
        params.putSingle("days", "x");
        params.putSingle("group", " ");
        assertEquals(7, ConsoleFilter.from(params).days);
        assertEquals(ConsoleFilter.PDV, ConsoleFilter.from(params).group);
    }

    /** BO-08-01-17: the organisation view puts each line in its group, a dash when unknown. */
    @Test
    void groupOfFollowsTheView() {
        ConsoleFilter filter = new ConsoleFilter();
        filter.group = ConsoleFilter.GLOBAL;
        assertEquals("Organisation", filter.groupOf("FR", "ITM", "07039"));
        filter.group = ConsoleFilter.COUNTRY;
        assertEquals("FR", filter.groupOf("FR", "ITM", "07039"));
        filter.group = ConsoleFilter.ENSEIGNE;
        assertEquals("—", filter.groupOf("FR", null, "07039"));
        filter.group = ConsoleFilter.PDV;
        assertEquals("07039", filter.groupOf("FR", "ITM", "07039"));
    }
    /**
     * Stubs the adhérent of one point of sale.
     *
     * @param panache the active static mock
     * @param number the point of sale
     * @param adherent its adhérent, or null for an unknown point of sale
     */
    @SuppressWarnings("unchecked")
    private void stubAdherent(org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache,
                              String number, String adherent) {
        com.intermarche.edge.domain.store.Pdv pdv = null;
        if (adherent != null) {
            pdv = new com.intermarche.edge.domain.store.Pdv();
            pdv.adherentCode = adherent;
        }
        io.quarkus.hibernate.orm.panache.PanacheQuery<Object> query =
                org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        org.mockito.Mockito.when(query.firstResult()).thenReturn(pdv);
        panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.find("pdvNumber", number))
                .thenReturn(query);
    }

    /**
     * BO-02-05-05: the adhérent is read and reproduced, keeps the points of
     * sale it operates whatever the case and leaves the others out, and the
     * adhérent view groups the lines by adhérent, a dash for a point of sale
     * attached to none.
     */
    @Test
    void theAdherentFiltersAndGroups() {
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("group", "ADHERENT");
        params.putSingle("adherent", " AD1 ");
        ConsoleFilter filter = ConsoleFilter.from(params);
        assertEquals("AD1", filter.adherent);
        assertEquals("group=ADHERENT&adherent=AD1&days=7", filter.query());
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubAdherent(panache, "10001", "ad1");
            stubAdherent(panache, "10002", "AD2");
            stubAdherent(panache, "10003", null);
            assertTrue(filter.keepsEchelon("FR", "ITM", "10001"));
            assertFalse(filter.keepsEchelon("FR", "ITM", "10002"));
            assertFalse(filter.keepsEchelon("FR", "ITM", "10003"));
            assertFalse(filter.keepsEchelon("FR", "ITM", null));
            assertEquals("ad1", filter.groupOf("FR", "ITM", "10001"));
            assertEquals("—", filter.groupOf("FR", "ITM", "10003"));
        }
    }
}
