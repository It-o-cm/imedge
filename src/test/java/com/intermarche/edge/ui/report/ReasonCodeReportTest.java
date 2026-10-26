package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.ui.PriceModType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.intermarche.edge.ui.report.QueryStub.row;
import static com.intermarche.edge.ui.report.QueryStub.rows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReasonCodeReport}: every source of justified gestures
 * — drawer openings, cancelled lines, price reductions and forcings, returns,
 * cash movements, supervision — typed, named by cashier, sorted, restricted to
 * the types and cashiers chosen; the drawer reason parsed out of its detail.
 */
class ReasonCodeReportTest {

    /** A moment of the period. */
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 20, 10, 0);

    /**
     * Builds an employee.
     *
     * @param badge the badge
     * @param first the first name, possibly null
     * @param last the last name, possibly null
     * @return the employee
     */
    private Employee employee(String badge, String first, String last) {
        Employee employee = new Employee();
        employee.badgeId = badge;
        employee.loginName = "111".equals(badge) ? "jdupont" : null;
        employee.firstName = first;
        employee.lastName = last;
        return employee;
    }

    /**
     * A stub answering every source once.
     *
     * @return the stub
     */
    private QueryStub everySource() {
        return new QueryStub()
                .answer("from Employee e", List.of(employee("111", "Jean", "Dupont"), employee("222", null, null)))
                .answer("e.eventType = :type", rows(row(AT.plusMinutes(1), "C01", "111", "OT1 - Monnaie (aval 999)")))
                .answer("l.cancelled = true", rows(row(AT.plusMinutes(2), null, "222", "AN1", "Erreur")))
                .answer("l.modifierType is not null", rows(
                        row(AT.plusMinutes(3), "C01", "111", PriceModType.FORCE_PRICE, "Prix forcé"),
                        row(AT.plusMinutes(4), "C01", "111", PriceModType.REMISE, null),
                        row(AT.plusMinutes(5), "C01", "111", PriceModType.QUANTITY, "x")))
                .answer("from Refund r", rows(row(AT.plusMinutes(6), "C01", "333", null, "Défectueux")))
                .answer("from CashMovement m", rows(row(AT.plusMinutes(7), "C01", "111",
                        CashMovement.MovementType.EXPENSE, "Timbres")))
                .answer("e.eventType in :types", rows(row(null, "C01", null,
                        TechnicalEvent.EventType.SUPERVISOR_CALLED, null), row(AT.plusMinutes(8), "C01", "jdupont",
                        TechnicalEvent.EventType.ENDORSEMENT_GRANTED, "CANCEL_TICKET par jdupont")));
    }

    /**
     * Run over the stub.
     *
     * @param stub the stub
     * @param criteria the edition parameters
     * @return the rows
     */
    private List<List<Object>> run(QueryStub stub, ReportCriteria criteria) {
        ReasonCodeReport report = new ReasonCodeReport();
        report.entityManager = stub.entityManager;
        return report.rows(criteria);
    }

    /**
     * Every type is listed, typed and named — a supervisor by login as well as
     * by badge — oldest first, a gesture without moment first of all.
     */
    @Test
    void everyTypeIsListedInOrder() {
        List<List<Object>> rows = run(everySource(), new ReportCriteria());
        assertEquals(8, rows.size());
        assertEquals("SUPERVISION", rows.get(0).get(5));
        assertEquals("", rows.get(0).get(2));
        assertEquals("SUPERVISOR_CALLED", rows.get(0).get(7));
        assertEquals("", rows.get(0).get(8));
        List<Object> drawer = rows.get(1);
        assertEquals("C01", drawer.get(1));
        assertEquals("111", drawer.get(2));
        assertEquals("Jean", drawer.get(3));
        assertEquals("Dupont", drawer.get(4));
        assertEquals("DRAWER_OPEN", drawer.get(5));
        assertEquals("Ouverture de tiroir", drawer.get(6));
        assertEquals("OT1", drawer.get(7));
        assertEquals("Monnaie", drawer.get(8));
        List<Object> cancel = rows.get(2);
        assertEquals("", cancel.get(1));
        assertEquals("", cancel.get(3));
        assertEquals("", cancel.get(4));
        assertEquals("CANCEL_LINE", cancel.get(5));
        assertEquals("AN1", cancel.get(7));
        assertEquals("PRICE_FORCING", rows.get(3).get(5));
        assertEquals("FORCE_PRICE", rows.get(3).get(7));
        assertEquals("DISCOUNT", rows.get(4).get(5));
        assertEquals("", rows.get(4).get(8));
        List<Object> refund = rows.get(5);
        assertEquals("RETURN", refund.get(5));
        assertEquals("", refund.get(3));
        assertEquals("", refund.get(7));
        assertEquals("CASH_MOVEMENT", rows.get(6).get(5));
        assertEquals("EXPENSE", rows.get(6).get(7));
        assertEquals("Timbres", rows.get(6).get(8));
        List<Object> endorsed = rows.get(7);
        assertEquals("jdupont", endorsed.get(2));
        assertEquals("Jean", endorsed.get(3));
        assertEquals("Dupont", endorsed.get(4));
        assertEquals("ENDORSEMENT_GRANTED", endorsed.get(7));
        assertEquals("Aval de jdupont : Annulation du ticket", endorsed.get(8));
    }

    /**
     * A survey answer journaled on the supervisor-call road is no reason code
     * and is left out; a genuine call keeps its reason (BO-06-03-02).
     */
    @Test
    void surveyAnswersAreLeftOut() {
        ReportCriteria criteria = new ReportCriteria();
        criteria.picks.add("SUPERVISION");
        QueryStub stub = new QueryStub()
                .answer("from Employee e", List.of())
                .answer("e.eventType in :types", rows(
                        row(AT, "C01", "111", TechnicalEvent.EventType.SUPERVISOR_CALLED, "SONDAGE — Q ? : Oui"),
                        row(AT.plusMinutes(1), "C01", "111", TechnicalEvent.EventType.SUPERVISOR_CALLED, "Autre")));
        List<List<Object>> rows = run(stub, criteria);
        assertEquals(1, rows.size());
        assertEquals("Autre", rows.get(0).get(8));
    }

    /**
     * An endorsement reads as who endorsed what, its action code labelled —
     * whole or without the segments the register appends — a refusal said so;
     * a call, a free sentence and a missing detail are kept as they are.
     */
    @Test
    void supervisionLabelsAreWorded() {
        assertEquals("Aval de mcurie : Mode école", ReasonCodeReport.supervisionLabel(
                TechnicalEvent.EventType.ENDORSEMENT_GRANTED, "TRAINING_TOGGLE par mcurie"));
        assertEquals("Aval de mcurie : Remboursement", ReasonCodeReport.supervisionLabel(
                TechnicalEvent.EventType.ENDORSEMENT_GRANTED, "REFUND_VOUCHER_2 par mcurie"));
        assertEquals("Aval refusé — 999 : Annulation du ticket", ReasonCodeReport.supervisionLabel(
                TechnicalEvent.EventType.ENDORSEMENT_DENIED, "CANCEL_TICKET par 999"));
        assertEquals("ACTION par x", ReasonCodeReport.supervisionLabel(
                TechnicalEvent.EventType.SUPERVISOR_CALLED, "ACTION par x"));
        assertEquals("Mode dégradé monétique levé par Jean", ReasonCodeReport.supervisionLabel(
                TechnicalEvent.EventType.ENDORSEMENT_GRANTED, "Mode dégradé monétique levé par Jean"));
        assertEquals("", ReasonCodeReport.supervisionLabel(TechnicalEvent.EventType.ENDORSEMENT_GRANTED, null));
        assertEquals("UNKNOWN", ReasonCodeReport.actionLabel("UNKNOWN"));
        assertEquals("ZZ_YY", ReasonCodeReport.actionLabel("ZZ_YY"));
    }

    /**
     * The types chosen and the cashiers chosen restrict the list; a forcing
     * alone leaves the reductions out.
     */
    @Test
    void theTypesAndCashiersChosenRestrict() {
        ReportCriteria criteria = new ReportCriteria();
        criteria.picks.add("PRICE_FORCING");
        criteria.cashiers.add("111");
        QueryStub stub = everySource();
        List<List<Object>> rows = run(stub, criteria);
        assertEquals(1, rows.size());
        assertEquals("PRICE_FORCING", rows.get(0).get(5));
        assertFalse(stub.asked("from Refund r"));
        ReportCriteria reductions = new ReportCriteria();
        reductions.picks.add("DISCOUNT");
        List<List<Object>> discounts = run(everySource(), reductions);
        assertEquals(1, discounts.size());
        assertEquals("DISCOUNT", discounts.get(0).get(5));
    }

    /**
     * The returns alone, for one cashier: neither reductions nor forcings are
     * read, and the return of another cashier is left out.
     */
    @Test
    void theReturnsOfOneCashier() {
        ReportCriteria criteria = new ReportCriteria();
        criteria.picks.add("RETURN");
        criteria.cashiers.add("111");
        QueryStub stub = everySource();
        assertTrue(run(stub, criteria).isEmpty());
        assertFalse(stub.asked("l.modifierType is not null"));
        assertTrue(stub.asked("from Refund r"));
    }

    /**
     * The drawer reason is read as code and label, the endorsement stripped;
     * a reason without code keeps its label; a missing detail reads blank.
     */
    @Test
    void theDrawerReasonIsParsed() {
        assertArrayEquals(new String[]{"OT1", "Monnaie"}, ReasonCodeReport.drawerReason("OT1 - Monnaie (aval 999)"));
        assertArrayEquals(new String[]{"OT2", "Change"}, ReasonCodeReport.drawerReason("OT2 - Change"));
        assertArrayEquals(new String[]{"", "Sans motif"}, ReasonCodeReport.drawerReason("Sans motif (aval 1)"));
        assertArrayEquals(new String[]{"", ""}, ReasonCodeReport.drawerReason(null));
    }

    /**
     * The metadata: identity, the nine columns, the type choice and the filters.
     */
    @Test
    void metadataDescribesTheReport() {
        ReasonCodeReport report = new ReasonCodeReport();
        assertEquals("reason-codes", report.code());
        assertEquals("État des codes raison", report.label());
        assertEquals("BO-06-06-07", report.requirement());
        assertEquals(ReportFamily.CASHIERS, report.family());
        assertEquals(9, report.columns().size());
        assertEquals("Types de code raison", report.pickLabel());
        assertEquals(7, report.pickChoices().size());
        assertTrue(report.pickChoices().contains("DRAWER_OPEN:Ouverture de tiroir"));
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.CASHIERS),
                report.filters());
    }
}
