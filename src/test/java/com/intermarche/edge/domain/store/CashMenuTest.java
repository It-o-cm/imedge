package com.intermarche.edge.domain.store;

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
 * Unit tests for {@link CashMenu} (BO-03-10-16/17): the finders, the parsing
 * of the menu keys and hidden functions, and the normalisation of the screen
 * models.
 */
class CashMenuTest {

    /** A fresh model shows every key in the standard screens. */
    @Test
    void aFreshModelIsStandard() {
        CashMenu menu = new CashMenu();
        assertTrue(menu.active);
        assertEquals("STANDARD", menu.cashierScreen);
        assertEquals("STANDARD", menu.customerScreen);
        assertEquals("DETAILED", menu.ticketModel);
        assertEquals(0, menu.keyColumns);
        assertTrue(menu.menuKeyList().isEmpty());
        assertTrue(menu.hiddenFunctionList().isEmpty());
    }

    /** The finder trims the code and short-circuits a null or blank one; the lists forward. */
    @Test
    @SuppressWarnings("unchecked")
    void findersResolve() {
        CashMenu menu = new CashMenu();
        PanacheQuery<CashMenu> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(menu);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CashMenu.find("code", "SLS")).thenReturn(query);
            ms.when(() -> CashMenu.list("order by code")).thenReturn(List.of(menu));
            ms.when(() -> CashMenu.list("active = true order by code")).thenReturn(List.of());
            assertSame(menu, CashMenu.findByCode(" SLS "));
            assertEquals(List.of(menu), CashMenu.listAllOrdered());
            assertTrue(CashMenu.listActive().isEmpty());
        }
        assertNull(CashMenu.findByCode(null));
        assertNull(CashMenu.findByCode(" "));
    }

    /** The menu keys keep the known ones once, in order, case ignored. */
    @Test
    void menuKeysAreParsed() {
        CashMenu menu = new CashMenu();
        menu.menuKeys = " ARTICLES ;reglement;ghost;articles";
        assertEquals(List.of("articles", "reglement"), menu.menuKeyList());
        menu.hiddenFunctions = "ABANDON;LOCK";
        assertEquals(List.of(CashFunction.ABANDON, CashFunction.LOCK), menu.hiddenFunctionList());
    }

    /** A screen value is normalised, an absent or unknown one falls back on the first. */
    @Test
    void valuesAreNormalised() {
        assertEquals("COMPACT", CashMenu.normalized(" compact ", CashMenu.CASHIER_SCREENS));
        assertEquals("STANDARD", CashMenu.normalized("HUGE", CashMenu.CASHIER_SCREENS));
        assertEquals("DETAILED", CashMenu.normalized(null, CashMenu.TICKET_MODELS));
    }

    /** The administered layout is the one drawn. */
    @Test
    void effectiveLayoutIsTheAdministeredOne() {
        CashMenu menu = new CashMenu();
        menu.menuKeys = "ticket";
        menu.layout = "{\"tabs\":[{\"id\":\"x1\"}]}";
        assertEquals(List.of(), menu.effectiveLayout().standardTabIds());
        assertEquals("x1", menu.effectiveLayout().tabs.get(0).id);
    }

    /** Without layout, the standard one, reduced to the bar keys named when there are some. */
    @Test
    void effectiveLayoutFallsBackOnTheStandardOne() {
        CashMenu menu = new CashMenu();
        assertEquals(CashMenu.MENU_KEYS, menu.effectiveLayout().standardTabIds());
        menu.menuKeys = "ticket;articles";
        MenuLayout layout = menu.effectiveLayout();
        assertEquals(List.of("ticket", "articles"), layout.standardTabIds());
        assertEquals(11, layout.tab("ticket").keys.size());
    }
}
