package com.intermarche.pos.ui.journal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JournalPage}.
 * <p>
 * The page window is pure arithmetic over four fields, so every derived value
 * is pinned on both arms: the empty result set, the single page, the first,
 * middle and last page of a multi-page set, and the exact-multiple boundary
 * where the last page is full. No mocks, no context.
 */
class JournalPageTest {

    /**
     * Builds a page of the given shape, filled with as many placeholder rows as
     * it should hold.
     *
     * @param rowCount the number of rows on the page
     * @param number the 1-based page number
     * @param size the page size
     * @param total the total row count
     * @return the built page
     */
    private JournalPage<String> page(int rowCount, int number, int size, long total) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            rows.add("row" + i);
        }
        return new JournalPage<>(rows, number, size, total);
    }

    /**
     * The constructor keeps every coordinate as given.
     */
    @Test
    void constructorKeepsCoordinates() {
        JournalPage<String> page = page(2, 3, 100, 250);
        assertEquals(2, page.rows.size());
        assertEquals(3, page.number);
        assertEquals(100, page.size);
        assertEquals(250L, page.total);
    }

    /**
     * An empty result set still spans one page, is both first and last, and
     * reports no row indices (pageCount zero arm, isEmpty true arm).
     */
    @Test
    void emptyResultSetSpansOnePage() {
        JournalPage<String> page = page(0, 1, 100, 0);
        assertTrue(page.isEmpty());
        assertEquals(1, page.getPageCount());
        assertTrue(page.isFirst());
        assertTrue(page.isLast());
        assertEquals(1, page.getPrevious());
        assertEquals(1, page.getNext());
        assertEquals(0L, page.getFirstIndex());
        assertEquals(0L, page.getLastIndex());
    }

    /**
     * A partial single page reports its real row indices and no neighbours
     * (pageCount rounding-up arm).
     */
    @Test
    void singlePartialPageReportsItsIndices() {
        JournalPage<String> page = page(30, 1, 100, 30);
        assertFalse(page.isEmpty());
        assertEquals(1, page.getPageCount());
        assertTrue(page.isFirst());
        assertTrue(page.isLast());
        assertEquals(1L, page.getFirstIndex());
        assertEquals(30L, page.getLastIndex());
    }

    /**
     * The first page of a multi-page set has a next page but no previous one
     * (isFirst true arm, isLast false arm).
     */
    @Test
    void firstPageOfManyHasANextOnly() {
        JournalPage<String> page = page(100, 1, 100, 250);
        assertEquals(3, page.getPageCount());
        assertTrue(page.isFirst());
        assertFalse(page.isLast());
        assertEquals(1, page.getPrevious());
        assertEquals(2, page.getNext());
        assertEquals(1L, page.getFirstIndex());
        assertEquals(100L, page.getLastIndex());
    }

    /**
     * A middle page has both neighbours and offset row indices (isFirst false
     * arm, isLast false arm).
     */
    @Test
    void middlePageHasBothNeighbours() {
        JournalPage<String> page = page(100, 2, 100, 250);
        assertFalse(page.isFirst());
        assertFalse(page.isLast());
        assertEquals(1, page.getPrevious());
        assertEquals(3, page.getNext());
        assertEquals(101L, page.getFirstIndex());
        assertEquals(200L, page.getLastIndex());
    }

    /**
     * The last, partial page has a previous page and no next one (isLast true
     * arm on a partial page).
     */
    @Test
    void lastPartialPageHasAPreviousOnly() {
        JournalPage<String> page = page(50, 3, 100, 250);
        assertFalse(page.isFirst());
        assertTrue(page.isLast());
        assertEquals(2, page.getPrevious());
        assertEquals(3, page.getNext());
        assertEquals(201L, page.getFirstIndex());
        assertEquals(250L, page.getLastIndex());
    }

    /**
     * A total that is an exact multiple of the page size ends on a full last
     * page, with no phantom page after it (pageCount exact-multiple arm).
     */
    @Test
    void exactMultipleEndsOnAFullPage() {
        assertEquals(2, JournalPage.pageCount(200, 100));
        JournalPage<String> page = page(100, 2, 100, 200);
        assertTrue(page.isLast());
        assertEquals(101L, page.getFirstIndex());
        assertEquals(200L, page.getLastIndex());
    }

    /**
     * A negative total is treated like an empty one (pageCount guard on the
     * strictly-negative arm).
     */
    @Test
    void negativeTotalSpansOnePage() {
        assertEquals(1, JournalPage.pageCount(-5, 100));
    }
}
