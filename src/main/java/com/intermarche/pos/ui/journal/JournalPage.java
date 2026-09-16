package com.intermarche.pos.ui.journal;

import java.util.List;

/**
 * One window over a journal result set: the rows of the current page, the page
 * coordinates and the total number of matching rows.
 * <p>
 * It exists because the journal is a control tool. A search that returned the
 * first thousand rows out of three thousand without saying so did not degrade
 * the experience, it made the reader conclude wrong. The total is therefore
 * always counted and the list always states which slice of it is on screen, so
 * a page never hides what it left out.
 *
 * @param <T> the row type of the paged list
 */
public class JournalPage<T> {

    /** The rows of the current page, in query order (possibly empty). */
    public final List<T> rows;

    /** The 1-based number of the current page, always within the page count. */
    public final int number;

    /** The number of rows a full page holds. */
    public final int size;

    /** The total number of rows matching the criteria, all pages together. */
    public final long total;

    /**
     * Builds a page window.
     *
     * @param rows the rows of the current page
     * @param number the 1-based current page number
     * @param size the page size
     * @param total the total number of matching rows
     */
    public JournalPage(List<T> rows, int number, int size, long total) {
        this.rows = rows;
        this.number = number;
        this.size = size;
        this.total = total;
    }

    /**
     * Computes the number of pages a result set spans, never less than one so
     * an empty result still has a page 1 to display.
     *
     * @param total the total number of matching rows
     * @param size the page size, expected strictly positive
     * @return the page count, at least 1
     */
    public static int pageCount(long total, int size) {
        if (total <= 0) {
            return 1;
        }
        return (int) ((total + size - 1) / size);
    }

    /**
     * Returns the number of pages this result set spans.
     *
     * @return the page count, at least 1
     */
    public int getPageCount() {
        return pageCount(total, size);
    }

    /**
     * Tells whether the current page holds no row at all.
     *
     * @return true when the current page is empty
     */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * Tells whether the current page is the first one.
     *
     * @return true when there is no previous page
     */
    public boolean isFirst() {
        return number <= 1;
    }

    /**
     * Tells whether the current page is the last one.
     *
     * @return true when there is no next page
     */
    public boolean isLast() {
        return number >= getPageCount();
    }

    /**
     * Returns the number of the previous page, clamped to the first one.
     *
     * @return the previous page number
     */
    public int getPrevious() {
        return isFirst() ? 1 : number - 1;
    }

    /**
     * Returns the number of the next page, clamped to the last one.
     *
     * @return the next page number
     */
    public int getNext() {
        return isLast() ? getPageCount() : number + 1;
    }

    /**
     * Returns the 1-based index, within the whole result set, of the first row
     * shown on this page.
     *
     * @return the first row index, 0 when the page is empty
     */
    public long getFirstIndex() {
        if (rows.isEmpty()) {
            return 0;
        }
        return (long) (number - 1) * size + 1;
    }

    /**
     * Returns the 1-based index, within the whole result set, of the last row
     * shown on this page.
     *
     * @return the last row index, 0 when the page is empty
     */
    public long getLastIndex() {
        if (rows.isEmpty()) {
            return 0;
        }
        return (long) (number - 1) * size + rows.size();
    }
}
