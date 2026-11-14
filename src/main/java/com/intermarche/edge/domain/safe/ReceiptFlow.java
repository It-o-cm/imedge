package com.intermarche.edge.domain.safe;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One of the EXISTING FEEDS that bring the safe the takings collected outside
 * the registers ({@code BO-05-02-03}): a fuel station, a gas supplier — each with
 * its own file layout.
 *
 * <p>The feeds are not rewritten to suit the node: the node reads them as they
 * are. A flow declares the separator of its file, the format of its dates and
 * which of its columns carries each value the safe needs — the reference that
 * identifies a receipt, the amount, the day, the wording and none, one or
 * several VAT triplets. Connecting a fourth feed, or following a supplier who
 * renames a column, is a row of the back office and not a release.
 */
@Entity
@Table(name = "receipt_flows",
        indexes = {
                @Index(name = "idx_receipt_flow_code", columnList = "code", unique = true)
        })
public class ReceiptFlow extends PanacheEntity {

    /** The code the feed is deposited under ({@code POST /feeds/import/SAFE_RECEIPTS?mode=CODE}). */
    @Column(name = "code", nullable = false, length = 30)
    public String code;

    /** The operator-facing name of the feed. */
    @Column(name = "label", nullable = false, length = 120)
    public String label;

    /** The column separator of the feed's file. */
    @Column(name = "field_separator", nullable = false, length = 3)
    public String separator = ";";

    /**
     * Which column of the file carries each value, as
     * {@code REFERENCE=COLUMN;AMOUNT=COLUMN;...}: the keys are the safe's own
     * columns ({@code REFERENCE}, {@code LABEL}, {@code AMOUNT}, {@code DATE},
     * {@code VAT_RATE}, {@code VAT_BASE}, {@code VAT_AMOUNT}, and the same
     * triplet suffixed {@code _2} to {@code _5} for further rates).
     */
    @Column(name = "mapping", nullable = false, length = 1000)
    public String mapping;

    /** The pattern of the file's dates, such as {@code dd/MM/yyyy}; blank for ISO days. */
    @Column(name = "date_pattern", length = 30)
    public String datePattern;

    /** Whether the feed is integrated; an inactive feed is refused. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /** When a file of this feed was last integrated, or null. */
    @Column(name = "last_import_at")
    public LocalDateTime lastImportAt;

    /** What the last integration answered, or null. */
    @Column(name = "last_result", length = 255)
    public String lastResult;

    /**
     * Resolves a feed by its code, case ignored.
     *
     * @param code the code, possibly null
     * @return the feed, or null when the code names none
     */
    public static ReceiptFlow findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return ReceiptFlow.<ReceiptFlow>find("code", code.trim().toUpperCase(Locale.ROOT)).firstResult();
    }

    /**
     * Every feed, in code order.
     *
     * @return the feeds
     */
    public static List<ReceiptFlow> listOrdered() {
        return ReceiptFlow.<ReceiptFlow>find("order by code").list();
    }

    /**
     * Reads the mapping: the safe's columns, in their declared order, each with
     * the column of the file that carries it.
     *
     * @return the mapping, upper case, empty when none is declared
     */
    public Map<String, String> columns() {
        Map<String, String> columns = new LinkedHashMap<>();
        if (mapping == null) {
            return columns;
        }
        for (String pair : mapping.split("[;\\n]")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && equals < pair.length() - 1) {
                columns.put(pair.substring(0, equals).trim().toUpperCase(Locale.ROOT),
                        pair.substring(equals + 1).trim().toUpperCase(Locale.ROOT));
            }
        }
        return columns;
    }
}
