package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.barcode.StoredValueRecord;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.CashMovement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static com.intermarche.edge.ui.report.QueryStub.row;
import static com.intermarche.edge.ui.report.QueryStub.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StoredValueReport}: the credit notes, gift cards and
 * down-payments issued or used over the period, one row per use or per
 * unused instrument, the three options, the registers chosen, the issuer
 * read off the sale or the refund, and the metadata.
 */
class StoredValueReportTest {

    /** The day of the period. */
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    /** A moment of the period. */
    private static final LocalDateTime AT = DAY.atTime(10, 0);

    /**
     * Builds the criteria of the day.
     *
     * @param option the option, or null
     * @return the criteria
     */
    private ReportCriteria day(String option) {
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.RANGE;
        criteria.rangeFrom = DAY;
        criteria.rangeTo = DAY;
        criteria.option = option;
        return criteria;
    }

    /**
     * Builds an instrument.
     *
     * @param number the number
     * @param kind the kind
     * @param issuedAt when it was issued, possibly null
     * @param document the issuing document, possibly null
     * @return the instrument
     */
    private StoredValueRecord record(String number, String kind, LocalDateTime issuedAt, String document) {
        StoredValueRecord record = new StoredValueRecord();
        record.number = number;
        record.kind = kind;
        record.issuedAt = issuedAt;
        record.issuingDocumentNumber = document;
        record.initialAmount = new BigDecimal("10.00");
        record.issuingTerminalId = "C01";
        return record;
    }

    /**
     * Builds a down-payment.
     *
     * @param reference the order, possibly null
     * @param badge the cashier's badge, or null for no cashier
     * @return the down-payment
     */
    private CashMovement deposit(String reference, String badge) {
        CashMovement deposit = new CashMovement();
        deposit.orderReference = reference;
        deposit.movementDate = AT.minusHours(1);
        deposit.terminalId = "C01";
        deposit.amount = new BigDecimal("20.00");
        if (badge != null) {
            deposit.cashier = new Employee();
            deposit.cashier.badgeId = badge;
        }
        return deposit;
    }

    /**
     * The stub of the period.
     *
     * @return the stub
     */
    private QueryStub stub() {
        return new QueryStub()
                .answer("select p.voucherNumber", rows(
                        row("GC1", "T5", AT.plusHours(1), "111", new BigDecimal("5.00"), "C01"),
                        row("GC2", "T6", AT.plusHours(2), null, new BigDecimal("3.00"), "C09")))
                .answer("select p.orderCode", rows(row("CMD1", "T7", AT.plusHours(3), "222",
                        new BigDecimal("20.00"), "C01")))
                .answer("from StoredValueRecord r", List.of(
                        record("GC1", "GIFT_CARD", AT, "T1"),
                        record("CN1", "CREDIT_NOTE", AT.minusDays(3), null),
                        record("GC2", "GIFT_CARD", null, null),
                        record("CN2", "CREDIT_NOTE", DAY.plusDays(1).atStartOfDay(), null)))
                .answer("from CashMovement m", List.of(deposit("CMD1", "333"), deposit(null, null)))
                .answer("where t.ticketNumber = :number", List.of("444"));
    }

    /**
     * Runs the report.
     *
     * @param stub the stub
     * @param criteria the edition parameters
     * @return the rows
     */
    private List<List<Object>> run(QueryStub stub, ReportCriteria criteria) {
        StoredValueReport report = new StoredValueReport();
        report.entityManager = stub.entityManager;
        return report.rows(criteria);
    }

    /**
     * Every instrument issued or used: its uses, or itself when unused, by
     * date of issue, an instrument without date first; one issued and used
     * elsewhere than the period is left out.
     */
    @Test
    void everyInstrumentIssuedOrUsed() {
        QueryStub stub = stub();
        List<List<Object>> rows = run(stub, day(null));
        assertEquals(4, rows.size());
        List<Object> gc2 = rows.get(0);
        assertEquals("GC2", gc2.get(1));
        assertEquals("", gc2.get(3));
        assertEquals("", gc2.get(7));
        assertEquals("T6", gc2.get(8));
        List<Object> deposit = rows.get(1);
        assertEquals("Acompte", deposit.get(0));
        assertEquals("CMD1", deposit.get(1));
        assertEquals("Acompte commande CMD1", deposit.get(2));
        assertEquals("333", deposit.get(3));
        assertEquals("222", deposit.get(7));
        List<Object> unused = rows.get(2);
        assertEquals("", unused.get(1));
        assertEquals("", unused.get(3));
        assertEquals("", unused.get(7));
        assertNull(unused.get(9));
        assertNull(unused.get(10));
        List<Object> gc1 = rows.get(3);
        assertEquals("Carte cadeau / bon d'achat", gc1.get(0));
        assertEquals("Carte cadeau / bon d'achat GC1", gc1.get(2));
        assertEquals("444", gc1.get(3));
        assertEquals("T1", gc1.get(4));
        assertEquals("111", gc1.get(7));
        assertEquals(new BigDecimal("5.00"), gc1.get(10));
        assertTrue(stub.asked("or r.number in :used"));
        assertTrue(stub.asked("or m.orderReference in :used"));
    }

    /**
     * The issued option keeps what was issued in the period, the used option
     * what was used in it; a register chosen drops the uses made elsewhere;
     * a period without use asks for no used instrument.
     */
    @Test
    void theOptionsAndRegistersSelect() {
        assertEquals(3, run(stub(), day(StoredValueReport.ISSUED)).size());
        List<List<Object>> used = run(stub(), day(StoredValueReport.USED));
        assertEquals(3, used.size());
        assertFalse(used.stream().anyMatch(row -> "".equals(row.get(1))));
        ReportCriteria register = day("ALL");
        register.terminals.add("C01");
        List<List<Object>> rows = run(stub(), register);
        assertEquals(3, rows.size());
        assertFalse(rows.stream().anyMatch(row -> "GC2".equals(row.get(1))));
        ReportCriteria elsewhere = day("ALL");
        elsewhere.terminals.add("C09");
        List<List<Object>> remote = run(stub(), elsewhere);
        assertEquals(1, remote.size());
        assertEquals("GC2", remote.get(0).get(1));
        QueryStub quiet = new QueryStub();
        assertTrue(run(quiet, day(null)).isEmpty());
        assertFalse(quiet.asked(":used"));
    }

    /**
     * The issuer is the sale's cashier, else the refund's; a document known
     * to neither, or naming no cashier, reads blank.
     */
    @Test
    void theIssuerIsFound() {
        List<Object> sale = List.of(record("CN9", "CREDIT_NOTE", AT, "R1"));
        QueryStub nameless = new QueryStub().answer("from StoredValueRecord r", sale)
                .answer("where t.ticketNumber = :number", Arrays.asList((Object) null));
        assertEquals("", run(nameless, day(null)).get(0).get(3));
        QueryStub refund = new QueryStub().answer("from StoredValueRecord r", sale)
                .answer("where r.refundNumber = :number", List.of("555"));
        List<Object> refunded = run(refund, day(null)).get(0);
        assertEquals("Avoir", refunded.get(0));
        assertEquals("555", refunded.get(3));
        assertEquals("R1", refunded.get(4));
        QueryStub anonymous = new QueryStub().answer("from StoredValueRecord r", sale)
                .answer("where r.refundNumber = :number", Arrays.asList((Object) null));
        assertEquals("", run(anonymous, day(null)).get(0).get(3));
        QueryStub unknown = new QueryStub().answer("from StoredValueRecord r", sale);
        assertEquals("", run(unknown, day(null)).get(0).get(3));
    }

    /**
     * The metadata: identity, columns, options and filters.
     */
    @Test
    void metadataDescribesTheReport() {
        StoredValueReport report = new StoredValueReport();
        assertEquals("stored-values", report.code());
        assertEquals("État acomptes / avoirs / coupons / bons", report.label());
        assertEquals("BO-06-06-06", report.requirement());
        assertEquals(11, report.columns().size());
        assertEquals("Instruments édités", report.optionLabel());
        assertEquals(3, report.optionChoices().size());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND), report.filters());
        assertFalse(report.family().toString().isEmpty());
    }
}
