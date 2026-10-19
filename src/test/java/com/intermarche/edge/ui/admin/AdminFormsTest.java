package com.intermarche.edge.ui.admin;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link AdminForms}.
 *
 * <p>The reading of a posted form, gathered once so ten screens cannot trim
 * differently. Every reader is asserted on all three of its cases — a usable
 * value, an absent one, an unreadable one — because the whole point of the class
 * is that the absent and the unreadable are handled the same way everywhere.
 */
class AdminFormsTest {

    /**
     * Builds a form carrying one field.
     *
     * @param field the field name
     * @param value the posted value
     * @return the form
     */
    private MultivaluedMap<String, String> form(String field, String value) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add(field, value);
        return form;
    }

    /**
     * A text is trimmed, and a blank or absent one reads as nothing — the three
     * legs of its guard.
     */
    @Test
    void atextIsTrimmedAndAblankOneIsNothing() {
        assertEquals("Acme", AdminForms.text(form("label", "  Acme  "), "label"));
        assertNull(AdminForms.text(form("label", "   "), "label"));
        assertNull(AdminForms.text(new MultivaluedHashMap<>(), "label"));
    }

    /**
     * A checkbox is true as soon as the browser sent it, whatever it sent, and
     * false when it sent nothing — the two arms.
     */
    @Test
    void acheckboxIsTrueAsSoonAsItIsSent() {
        assertTrue(AdminForms.checkbox(form("active", "on"), "active"));
        assertTrue(AdminForms.checkbox(form("active", ""), "active"));
        assertFalse(AdminForms.checkbox(new MultivaluedHashMap<>(), "active"));
    }

    /**
     * An identifier is read, and an absent, blank or unreadable one is nothing —
     * the three legs of its guard.
     */
    @Test
    void anIdentifierIsReadOrNothing() {
        assertEquals(Long.valueOf(42L), AdminForms.id(" 42 "));
        assertNull(AdminForms.id(null));
        assertNull(AdminForms.id("   "));
        assertNull(AdminForms.id("quarante-deux"));
    }

    /**
     * A whole number is read, and an absent, blank or unreadable one falls back
     * — the three legs of its guard.
     */
    @Test
    void awholeNumberIsReadOrFallsBack() {
        assertEquals(7, AdminForms.integer(" 7 ", 0));
        assertEquals(0, AdminForms.integer(null, 0));
        assertEquals(0, AdminForms.integer("  ", 0));
        assertEquals(3, AdminForms.integer("sept", 3));
    }

    /**
     * An amount is read with the French comma and with spaced thousands, and an
     * absent, blank or unreadable one is nothing — the three legs of its guard.
     */
    @Test
    void anAmountIsReadOrNothing() {
        assertEquals(new BigDecimal("12.50"), AdminForms.amount("12,50"));
        assertEquals(new BigDecimal("12.50"), AdminForms.amount(" 12.50 "));
        assertEquals(new BigDecimal("1200.00"), AdminForms.amount("1 200,00"));
        assertEquals(new BigDecimal("-15.00"), AdminForms.amount("-15,00"));
        assertNull(AdminForms.amount(null));
        assertNull(AdminForms.amount("  "));
        assertNull(AdminForms.amount("douze"));
    }

    /**
     * A day is read the way an HTML date field writes it, and an absent, blank
     * or unreadable one is nothing — the three legs of its guard.
     */
    @Test
    void adayIsReadOrNothing() {
        assertEquals(LocalDate.of(2026, 9, 16), AdminForms.day(" 2026-09-16 "));
        assertNull(AdminForms.day(null));
        assertNull(AdminForms.day("   "));
        assertNull(AdminForms.day("16/09/2026"));
    }

    /**
     * The redirect carries the notice encoded, and opens the query string or
     * continues it — both arms of that guard.
     */
    @Test
    void theredirectCarriesTheNoticeEncoded() {
        Response plain = AdminForms.redirect("/admin/safe/movements", "Mouvement validé.", true);
        assertEquals(303, plain.getStatus());
        assertEquals("/admin/safe/movements?notice=Mouvement+valid%C3%A9.&noticeOk=true",
                plain.getLocation().toString());
        Response continued = AdminForms.redirect("/admin/safe/days?day=2026-09-16", "Fait.", false);
        assertEquals("/admin/safe/days?day=2026-09-16&notice=Fait.&noticeOk=false",
                continued.getLocation().toString());
    }

    // --------------------------------------------------
    // page / pageParam
    // --------------------------------------------------

    /**
     * Builds a list of the given size, its rows being their own index.
     *
     * @param size how many rows
     * @return the rows
     */
    private java.util.List<Integer> rows(int size) {
        java.util.List<Integer> rows = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rows.add(i);
        }
        return rows;
    }

    /**
     * A result shorter than one page is one page, whole, and the window says
     * so.
     */
    @Test
    void aShortResultIsOneWholePage() {
        com.intermarche.edge.ui.journal.JournalPage<Integer> page = AdminForms.page(rows(3), 1);
        Assertions.assertEquals(3, page.rows.size());
        Assertions.assertEquals(1, page.number);
        Assertions.assertEquals(3L, page.total);
        Assertions.assertEquals(1, page.getPageCount());
    }

    /**
     * An empty result still has a page one, empty.
     */
    @Test
    void anEmptyResultStillHasAFirstPage() {
        com.intermarche.edge.ui.journal.JournalPage<Integer> page =
                AdminForms.page(java.util.List.of(), 1);
        Assertions.assertTrue(page.rows.isEmpty());
        Assertions.assertEquals(1, page.number);
        Assertions.assertEquals(0L, page.total);
    }

    /**
     * A long result is cut at the page size, and the second page holds the
     * remainder.
     */
    @Test
    void aLongResultIsCutAtThePageSize() {
        com.intermarche.edge.ui.journal.JournalPage<Integer> first =
                AdminForms.page(rows(250), 1);
        Assertions.assertEquals(AdminForms.PAGE_SIZE, first.rows.size());
        Assertions.assertEquals(0, first.rows.get(0));
        com.intermarche.edge.ui.journal.JournalPage<Integer> third =
                AdminForms.page(rows(250), 3);
        Assertions.assertEquals(50, third.rows.size());
        Assertions.assertEquals(200, third.rows.get(0));
        Assertions.assertEquals(3, third.number);
    }

    /**
     * A page number below one is clamped to the first page (true arm of the
     * clamp).
     */
    @Test
    void aPageBelowOneIsClampedToTheFirst() {
        com.intermarche.edge.ui.journal.JournalPage<Integer> page = AdminForms.page(rows(250), 0);
        Assertions.assertEquals(1, page.number);
        Assertions.assertEquals(0, page.rows.get(0));
    }

    /**
     * A page number beyond the result is clamped to the last page (false arm of
     * the clamp), which is what a stale link lands on.
     */
    @Test
    void aPageBeyondTheResultIsClampedToTheLast() {
        com.intermarche.edge.ui.journal.JournalPage<Integer> page = AdminForms.page(rows(250), 99);
        Assertions.assertEquals(3, page.number);
        Assertions.assertEquals(200, page.rows.get(0));
    }

    /**
     * A blank fragment carries nothing over (both legs of the guard).
     */
    @Test
    void aBlankFragmentCarriesNothingOver() {
        Assertions.assertEquals("", AdminForms.pageParam("text", null));
        Assertions.assertEquals("", AdminForms.pageParam("text", "   "));
    }

    /**
     * A fragment is carried over URL-encoded and terminated by an ampersand, so
     * a page number appends to it.
     */
    @Test
    void aFragmentIsCarriedOverEncoded() {
        Assertions.assertEquals("text=Pes%C3%A9s&", AdminForms.pageParam("text", "Pesés"));
    }
}
