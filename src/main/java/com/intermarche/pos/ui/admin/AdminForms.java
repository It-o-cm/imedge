package com.intermarche.pos.ui.admin;

import com.intermarche.pos.ui.journal.JournalPage;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * The reading of a posted back-office form, gathered once.
 *
 * <p>Every safe screen posts the same shapes — an identifier, an amount typed
 * with a French comma, a day, a checkbox — and answers the same way, with a 303
 * carrying a one-shot notice. Ten screens each writing their own
 * {@code blankToNull} is ten chances for one of them to trim differently, and
 * the first symptom would be an amount that saves on one screen and refuses on
 * the next.
 *
 * <p>Static and stateless on purpose: there is nothing to inject and nothing to
 * remember between two reads.
 */
public final class AdminForms {

    /**
     * Non-instantiable helper.
     */
    private AdminForms() {
    }

    /**
     * Reads a posted text, a blank one counting as absent.
     *
     * @param form the posted form
     * @param field the field name
     * @return the trimmed value, or null when the field is absent or blank
     */
    public static String text(MultivaluedMap<String, String> form, String field) {
        String value = form.getFirst(field);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Reads a posted checkbox.
     *
     * @param form the posted form
     * @param field the field name
     * @return true when the browser sent the field at all
     */
    public static boolean checkbox(MultivaluedMap<String, String> form, String field) {
        return form.getFirst(field) != null;
    }

    /**
     * Reads a posted identifier.
     *
     * @param value the posted value, possibly null or unreadable
     * @return the identifier, or null
     */
    public static Long id(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Reads a posted whole number.
     *
     * @param value the posted value, possibly null or unreadable
     * @param fallback what an absent or unreadable value means
     * @return the number, or the fallback
     */
    public static int integer(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /**
     * Reads a posted amount, the French decimal comma accepted.
     *
     * @param value the posted value, possibly null or unreadable
     * @return the amount, or null when nothing readable was typed
     */
    public static BigDecimal amount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim().replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Reads a posted day, written the way an HTML date field writes it.
     *
     * @param value the posted value, possibly null or unreadable
     * @return the day, or null when nothing readable was typed
     */
    public static LocalDate day(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The number of rows one page of a back-office list shows, everywhere.
     *
     * <p>One value and not one per screen, so a page turn means the same thing
     * wherever the operator is.
     */
    public static final int PAGE_SIZE = 100;

    /**
     * Cuts an already-filtered list into the requested page.
     *
     * <p>For the screens whose filter stays in memory because translating it
     * would not be faithful — a match on a DERIVED value, say. It caps what the
     * page renders and lets the list state what it is not showing; it does NOT
     * cap what was read, so it is the right answer only where the population is
     * bounded by nature, and the wrong one for a catalog. Those screens get
     * {@code PanacheQuery.page} instead.
     *
     * @param <T> the row type
     * @param all the whole filtered result, in display order
     * @param requested the 1-based page number asked for
     * @return the requested window, clamped into the pages the result has
     */
    public static <T> JournalPage<T> page(List<T> all, int requested) {
        int count = JournalPage.pageCount(all.size(), PAGE_SIZE);
        int number = requested < 1 ? 1 : Math.min(requested, count);
        int from = (number - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, all.size());
        List<T> rows = from >= all.size() ? List.of() : all.subList(from, to);
        return new JournalPage<>(rows, number, PAGE_SIZE, all.size());
    }

    /**
     * Renders a single search fragment as the prefix a page link carries over.
     *
     * @param field the query-parameter name
     * @param value the fragment, possibly null or blank
     * @return the query string, terminated by an ampersand, or empty
     */
    public static String pageParam(String field, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return field + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8) + "&";
    }

    /**
     * Builds the 303 that carries a one-shot outcome message back to a screen.
     *
     * @param path the screen to go back to
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    public static Response redirect(String path, String notice, boolean ok) {
        return Response.seeOther(URI.create(path + (path.contains("?") ? "&" : "?") + "notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8) + "&noticeOk=" + ok)).build();
    }
}
