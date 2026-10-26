package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.safe.SafeDayValidation;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.service.CashSessionService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AdminDayHistoryResource}.
 *
 * <p>The day consolidated over every register. What is proved here is that the
 * breakdown by tender SUMS the sessions' own reports rather than recomputing
 * them, that a null amount inside one counts as zero, and that the endorsement
 * of a cashier's movement is refused without a name — the requirement asks for a
 * validation, and an unsigned one validates nothing.
 */
class AdminDayHistoryResourceTest {

    /**
     * Builds a resource with mocked template and consolidation.
     *
     * @return the wired resource
     */
    private AdminDayHistoryResource newResource() {
        AdminDayHistoryResource resource = new AdminDayHistoryResource();
        resource.adminSafeDays = mock(Template.class);
        resource.cashSessionService = mock(CashSessionService.class);
        resource.i18n = mock(BackOfficeMessages.class);
        when(resource.i18n.code(anyString(), any())).thenAnswer(call -> String.valueOf(call.getArguments()[1]));
        return resource;
    }

    /**
     * Stubs a template's fluent chain to a recognizable view.
     *
     * @param template the template to stub
     * @return the view every chain call returns
     */
    private TemplateInstance stub(Template template) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(template.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds a closed session of a register.
     *
     * @param terminal the register identifier
     * @param withdrawn what was taken out, as a plain decimal string, null for none
     * @return the session
     */
    private CashSession session(String terminal, String withdrawn) {
        CashSession session = new CashSession();
        session.terminalId = terminal;
        session.sessionNumber = terminal + "-1";
        session.withdrawnAmount = withdrawn == null ? null : new BigDecimal(withdrawn);
        return session;
    }

    /**
     * Builds the consolidation of one session.
     *
     * @param totals the theoretical by tender key
     * @return the report
     */
    private CashSessionService.SessionReport report(Map<String, BigDecimal> totals) {
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.theoreticalByMethod = new LinkedHashMap<>(totals);
        return report;
    }

    /**
     * BO-05-02-08 (recette RT): the per-tender theoretical of the day is the
     * registers' THEORETICAL, never their bare settlements — the cash line is the
     * Z's own theoretical cash (float, cash-ins and expenses included), whatever
     * the tickets alone took in cash.
     */
    @Test
    void theDayStatesTheTheoreticalNotTheSettlements() {
        AdminDayHistoryResource resource = newResource();
        CashSession session = session("C01", "30.00");
        CashSessionService.SessionReport report = report(Map.of("CASH", new BigDecimal("185.00")));
        report.totalsByMethod.put("CASH", new BigDecimal("100.00"));
        when(resource.cashSessionService.buildReport(session)).thenReturn(report);
        Map<String, BigDecimal> totals = resource.totalsByTender(List.of(session));
        assertEquals(1, totals.size());
        assertEquals(new BigDecimal("185.00"), totals.get("CASH"));
    }

    /**
     * Stubs the sessions of one day.
     *
     * @param panache the active PanacheEntityBase mock
     * @param day the day examined
     * @param sessions the sessions to resolve
     */
    @SuppressWarnings("unchecked")
    private void stubSessions(MockedStatic<PanacheEntityBase> panache, LocalDate day,
            List<CashSession> sessions) {
        PanacheQuery<CashSession> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(sessions);
        panache.when(() -> CashSession.find(
                "status = ?1 and closingDate >= ?2 and closingDate < ?3 order by terminalId",
                CashSession.SessionStatus.CLOSED, day.atStartOfDay(),
                day.plusDays(1).atStartOfDay())).thenReturn(query);
    }

    /**
     * Stubs the drawer movements of one day.
     *
     * @param panache the active PanacheEntityBase mock
     * @param day the day examined
     * @param movements the movements to resolve
     */
    @SuppressWarnings("unchecked")
    private void stubMovements(MockedStatic<PanacheEntityBase> panache, LocalDate day,
            List<CashMovement> movements) {
        PanacheQuery<CashMovement> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(movements);
        panache.when(() -> CashMovement.find(
                "movementDate >= ?1 and movementDate < ?2 order by movementDate",
                day.atStartOfDay(), day.plusDays(1).atStartOfDay())).thenReturn(query);
        stubValidation(panache, day, null);
    }

    /**
     * Stubs the validation of one day (BO-05-02-09).
     *
     * @param panache the active PanacheEntityBase mock
     * @param day the day examined
     * @param validation the validation to resolve, null for a day not validated
     */
    @SuppressWarnings("unchecked")
    private void stubValidation(MockedStatic<PanacheEntityBase> panache, LocalDate day,
            SafeDayValidation validation) {
        PanacheQuery<SafeDayValidation> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(validation);
        panache.when(() -> SafeDayValidation.find("accountingDay", day)).thenReturn(query);
        PanacheQuery<Object> noTender = mock(PanacheQuery.class);
        panache.when(() -> PanacheEntityBase.find(org.mockito.ArgumentMatchers.eq("code"),
                any(Object[].class))).thenReturn(noTender);
    }

    /**
     * The day's takings are the sum of the sessions' own reports, tender by
     * tender, with the totals and the withdrawals beside them.
     */
    @Test
    void thedaysTakingsSumTheSessionsReports() {
        AdminDayHistoryResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafeDays);
        CashSession first = session("C01", "500.00");
        CashSession second = session("C02", "750.00");
        when(resource.cashSessionService.buildReport(first)).thenReturn(report(
                Map.of("CASH", new BigDecimal("300.00"))));
        when(resource.cashSessionService.buildReport(second)).thenReturn(report(
                new LinkedHashMap<>(Map.of("CASH", new BigDecimal("200.00")))));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubSessions(panache, LocalDate.of(2026, 9, 15), List.of(first, second));
            stubMovements(panache, LocalDate.of(2026, 9, 15), List.of());
            assertSame(view, resource.day("2026-09-15", null, true));
        }
        verify(view).data("total", "500.00");
        verify(view).data("withdrawn", "1250.00");
        verify(view).data("day", "2026-09-15");
    }

    /**
     * A report carrying a null amount counts it as zero — the null arm of that
     * guard — and a day with no session totals nothing.
     */
    @Test
    void anUnsetTotalCountsAsZero() {
        AdminDayHistoryResource resource = newResource();
        CashSession session = session("C01", null);
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("CASH", null);
        when(resource.cashSessionService.buildReport(session)).thenReturn(report(totals));
        assertEquals(BigDecimal.ZERO, resource.sum(resource.totalsByTender(
                List.of(session)).values()));
        assertTrue(resource.totalsByTender(List.of()).isEmpty());
        assertEquals(BigDecimal.ZERO, resource.withdrawnOf(List.of(session)));
    }

    /**
     * The same tender seen on two registers is summed once, not listed twice.
     */
    @Test
    void thesameTenderOnTwoRegistersIsSummedOnce() {
        AdminDayHistoryResource resource = newResource();
        CashSession first = session("C01", "0.00");
        CashSession second = session("C02", "0.00");
        when(resource.cashSessionService.buildReport(first))
                .thenReturn(report(Map.of("CASH", new BigDecimal("300.00"))));
        when(resource.cashSessionService.buildReport(second))
                .thenReturn(report(Map.of("CASH", new BigDecimal("200.00"))));
        List<AdminDayHistoryResource.TenderRow> rows =
                resource.rowsOf(resource.totalsByTender(List.of(first, second)));
        assertEquals(1, rows.size());
        assertEquals("CASH", rows.get(0).getMethod());
        assertEquals("500.00", rows.get(0).getAmount());
    }

    /**
     * Without a day, the page examines today — the null arm of that guard.
     */
    @Test
    void withoutAdayThePageExaminesToday() {
        AdminDayHistoryResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafeDays);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubSessions(panache, LocalDate.now(), List.of());
            stubMovements(panache, LocalDate.now(), List.of());
            resource.day(null, null, true);
        }
        verify(view).data("day", LocalDate.now().toString());
    }

    /**
     * Validating a cashier's movement writes the name of whoever checked it.
     */
    @Test
    void validatingAmovementWritesWhoCheckedIt() {
        AdminDayHistoryResource resource = newResource();
        CashMovement movement = mock(CashMovement.class);
        movement.movementUid = "MV-1";
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "3");
        form.add("day", "2026-09-15");
        form.add("endorsedBy", " B12 ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashMovement.findById(3L)).thenReturn(movement);
            Response response = resource.validate(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertTrue(response.getLocation().toString().contains("day=2026-09-15"));
            assertEquals("B12", movement.endorsedBy);
            verify(movement).persist();
        }
    }

    /**
     * Validating an unknown movement is refused, whether the identifier is
     * absent or names nothing — the two legs of that guard.
     */
    @Test
    void validatingAnUnknownMovementIsRefused() {
        AdminDayHistoryResource resource = newResource();
        assertTrue(resource.validate(new MultivaluedHashMap<>()).getLocation().toString()
                .contains("noticeOk=false"));
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "9");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashMovement.findById(9L)).thenReturn(null);
            assertTrue(resource.validate(form).getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * An UNSIGNED validation is refused: a validation nobody signed validates
     * nothing.
     */
    @Test
    void anUnsignedValidationIsRefused() {
        AdminDayHistoryResource resource = newResource();
        CashMovement movement = mock(CashMovement.class);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "3");
        form.add("endorsedBy", "   ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashMovement.findById(3L)).thenReturn(movement);
            Response response = resource.validate(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
            assertNull(movement.endorsedBy);
        }
    }

    /**
     * The extraction states one row per tender and names the file after the day.
     */
    @Test
    void theextractionStatesOneRowPerTender() {
        AdminDayHistoryResource resource = newResource();
        CashSession session = session("C01", "0.00");
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("CASH;CB", new BigDecimal("300.00"));
        totals.put(null, new BigDecimal("12.00"));
        when(resource.cashSessionService.buildReport(session)).thenReturn(report(totals));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubSessions(panache, LocalDate.of(2026, 9, 15), List.of(session));
            Response response = resource.export("2026-09-15");
            assertEquals(200, response.getStatus());
            String csv = String.valueOf(response.getEntity());
            assertTrue(csv.startsWith("JOURNEE;MOYEN;MONTANT\n"));
            assertTrue(csv.contains("2026-09-15;CASH,CB;300.00"));
            assertTrue(csv.contains("2026-09-15;;12.00"));
            assertTrue(response.getHeaderString("Content-Disposition")
                    .contains("journee-2026-09-15.csv"));
        }
    }

    /**
     * The extraction of an unnamed day extracts today — the null arm of that
     * guard.
     */
    @Test
    void theextractionOfAnUnnamedDayExtractsToday() {
        AdminDayHistoryResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubSessions(panache, LocalDate.now(), List.of());
            assertTrue(resource.export(null).getHeaderString("Content-Disposition")
                    .contains("journee-" + LocalDate.now() + ".csv"));
        }
    }

    /**
     * An employee is re-read by identifier, and an absent one names nobody —
     * both arms of that guard.
     */
    @Test
    void anEmployeeIsReReadByIdentifier() {
        AdminDayHistoryResource resource = newResource();
        Employee employee = new Employee();
        assertNull(resource.employeeOf(null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(5L)).thenReturn(employee);
            assertSame(employee, resource.employeeOf(5L));
        }
    }

    /**
     * A null amount inside the sum counts as zero — the null arm of that guard —
     * and a printable row of a null total shows it as zero rather than exploding.
     */
    @Test
    void thesumAndTheRowsTolerateNullAmounts() {
        AdminDayHistoryResource resource = newResource();
        assertEquals(BigDecimal.ZERO,
                resource.sum(java.util.Arrays.asList((BigDecimal) null)));
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("CASH", null);
        List<AdminDayHistoryResource.TenderRow> rows = resource.rowsOf(totals);
        assertEquals("0.00", rows.get(0).getAmount());
    }
    /** BO-10: declaration summaries distinguish absent, blank, invalid, empty, matching and mismatching tender counts. */
    @Test
    void coveragePaperDeclarationSummaryLegs() {
        var resource = newResource();
        resource.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var session = new CashSession();
        for (String raw : new String[]{null, " ", "invalid", "{}"}) {
            session.paperDeclaration = raw;
            org.junit.jupiter.api.Assertions.assertNull(resource.paperDeclarationSummary(session));
        }
        session.paperDeclaration = "{\"CHEQUE\":{\"declared\":2,\"theoretical\":2},\"TR\":{\"declared\":1,\"theoretical\":1}}";
        assertEquals("CHEQUE 2/2, TR 1/1", resource.paperDeclarationSummary(session));
        session.paperDeclaration = "{\"CHEQUE\":{\"declared\":1,\"theoretical\":2}}";
        assertEquals("ÉCART — CHEQUE 1/2", resource.paperDeclarationSummary(session));
    }

    /** BO-10: the day page includes the frozen declaration summary under its session identity. */
    @Test
    void coverageDayPageCarriesPaperDeclaration() {
        var resource = newResource();
        resource.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var view = stub(resource.adminSafeDays);
        var session = session("C01", "0.00");
        session.paperDeclaration = "{\"CHEQUE\":{\"declared\":1,\"theoretical\":1}}";
        var report = report(Map.of());
        when(resource.cashSessionService.buildReport(session)).thenReturn(report);
        try (var base = mockStatic(PanacheEntityBase.class)) {
            stubSessions(base, LocalDate.of(2026, 10, 20), List.of(session));
            stubMovements(base, LocalDate.of(2026, 10, 20), List.of());
            assertSame(view, resource.day("2026-10-20", null, true));
        }
        verify(view).data("declarations", Map.of(session.sessionNumber, "CHEQUE 1/1"));
    }

    /** The day examined by the controller's gestures. */
    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);

    /**
     * Builds a posted form.
     *
     * @param pairs the field names and values, alternately
     * @return the form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            form.add(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    /**
     * Builds a drawer movement of the day.
     *
     * @param type its type
     * @param amount its amount, as a plain decimal string, null for none
     * @return the movement
     */
    private CashMovement movement(CashMovement.MovementType type, String amount) {
        CashMovement movement = new CashMovement();
        movement.movementUid = "MV-1";
        movement.terminalId = "C01";
        movement.type = type;
        movement.paymentMethod = "CHEQUE";
        movement.transferTo = "CARD";
        movement.orderReference = "CMD-1";
        movement.amount = amount == null ? null : new BigDecimal(amount);
        movement.movementDate = DAY.atTime(10, 0);
        return movement;
    }

    /**
     * Reads the notice a redirect carries.
     *
     * @param response the redirect
     * @return its location, decoded
     */
    private String location(Response response) {
        return java.net.URLDecoder.decode(response.getLocation().toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * A correction writes a NEW movement carrying the difference, validated by
     * the controller, on the same register, session, tender and day — the
     * register's witness is left as it was (BO-05-02-09).
     */
    @Test
    void acorrectionWritesTheDifferenceAsANewMovement() {
        AdminDayHistoryResource resource = newResource();
        CashMovement original = movement(CashMovement.MovementType.WITHDRAWAL, "200.00");
        CashSession session = session("C01", "0.00");
        original.session = session;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            panache.when(() -> CashMovement.findById(7L)).thenReturn(original);
            stubValidation(panache, DAY, null);
            String location = location(resource.correct(form("id", "7", "day", "2026-09-15", "amount", "180,00",
                    "reason", "billet compté deux fois", "endorsedBy", "11111111")));
            assertTrue(location.contains("Correction enregistrée."));
            assertTrue(location.contains("noticeOk=true"));
            CashMovement correction = created.constructed().get(0);
            assertTrue(correction.movementUid.startsWith("BO-"));
            assertEquals(35, correction.movementUid.length());
            assertEquals("C01", correction.terminalId);
            assertSame(session, correction.session);
            assertEquals(CashMovement.MovementType.WITHDRAWAL, correction.type);
            assertEquals("CHEQUE", correction.paymentMethod);
            assertEquals("CARD", correction.transferTo);
            assertEquals("CMD-1", correction.orderReference);
            assertEquals(new BigDecimal("-20.00"), correction.amount);
            assertEquals("Correction du mouvement du 15/09/2026 10:00 : billet compté deux fois", correction.reason);
            assertEquals(DAY.atTime(10, 0, 1), correction.movementDate);
            assertEquals("11111111", correction.endorsedBy);
            verify(correction).persist();
        }
        assertEquals(new BigDecimal("200.00"), original.amount);
    }

    /**
     * A declaration counts as its last value: its correction states the
     * corrected amount itself; a movement recorded without amount or date
     * corrects from zero and from now.
     */
    @Test
    void adeclarationIsCorrectedByItsNewValue() {
        AdminDayHistoryResource resource = newResource();
        CashMovement declaration = movement(CashMovement.MovementType.DECLARATION, null);
        declaration.movementDate = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            panache.when(() -> CashMovement.findById(7L)).thenReturn(declaration);
            stubValidation(panache, LocalDate.now(), null);
            resource.correct(form("id", "7", "amount", "350", "reason", "recompté", "endorsedBy", "B1"));
            assertEquals(new BigDecimal("350"), created.constructed().get(0).amount);
        }
    }

    /**
     * A correction is refused on every leg: no identifier, an unknown
     * movement, a validated day, no controller, no reason, no amount, and an
     * amount equal to the recorded one.
     */
    @Test
    void acorrectionIsRefusedOnEveryLeg() {
        AdminDayHistoryResource resource = newResource();
        assertTrue(location(resource.correct(form("day", "2026-09-15"))).contains("Mouvement inconnu."));
        CashMovement original = movement(CashMovement.MovementType.EXPENSE, "15.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            assertTrue(location(resource.correct(form("id", "8"))).contains("Mouvement inconnu."));
            panache.when(() -> CashMovement.findById(7L)).thenReturn(original);
            stubValidation(panache, DAY, new SafeDayValidation());
            assertTrue(location(resource.correct(form("id", "7", "amount", "1", "reason", "r", "endorsedBy", "B")))
                    .contains("déjà validée"));
            stubValidation(panache, DAY, null);
            assertTrue(location(resource.correct(form("id", "7", "amount", "1", "reason", "r")))
                    .contains("Nom ou badge du valideur obligatoire."));
            assertTrue(location(resource.correct(form("id", "7", "amount", "1", "endorsedBy", "B")))
                    .contains("Motif obligatoire."));
            assertTrue(location(resource.correct(form("id", "7", "reason", "r", "endorsedBy", "B")))
                    .contains("Montant corrigé obligatoire."));
            assertTrue(location(resource.correct(form("id", "7", "amount", "15", "reason", "r", "endorsedBy", "B")))
                    .contains("Le montant corrigé est celui du mouvement."));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * A transfer writes a TRANSFER movement on the session, from the tender
     * named, cash when none is, to the other, validated by the controller.
     */
    @Test
    @SuppressWarnings("unchecked")
    void atransferMovesASettlementBetweenTenders() {
        AdminDayHistoryResource resource = newResource();
        CashSession session = session("C01", "0.00");
        Employee cashier = new Employee();
        session.openingCashier = cashier;
        PanacheQuery<CashSession> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(session);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            panache.when(() -> CashSession.find("sessionNumber", "C01-1")).thenReturn(found);
            stubValidation(panache, DAY, null);
            assertTrue(location(resource.transfer(form("day", "2026-09-15", "session", "C01-1", "to", "CARD",
                    "amount", "12,50", "reason", "erreur de touche", "endorsedBy", "B1")))
                    .contains("Transfert de règlement enregistré."));
            CashMovement transfer = created.constructed().get(0);
            assertEquals(CashMovement.MovementType.TRANSFER, transfer.type);
            assertEquals("CASH", transfer.paymentMethod);
            assertEquals("CARD", transfer.transferTo);
            assertEquals(new BigDecimal("12.50"), transfer.amount);
            assertSame(session, transfer.session);
            assertSame(cashier, transfer.cashier);
            assertEquals("C01", transfer.terminalId);
            assertEquals(DAY, transfer.movementDate.toLocalDate());
            assertEquals("Transfert de règlement : erreur de touche", transfer.reason);
            assertEquals("B1", transfer.endorsedBy);
            assertTrue(transfer.movementUid.startsWith("BO-"));
            assertTrue(transfer.movementUid.length() <= 36);
            verify(transfer).persist();
            stubValidation(panache, LocalDate.now(), null);
            resource.transfer(form("session", "C01-1", "from", "CHEQUE", "to", "CASH", "amount", "3",
                    "reason", "r", "endorsedBy", "B1"));
            assertEquals("CHEQUE", created.constructed().get(1).paymentMethod);
            assertEquals(LocalDate.now(), created.constructed().get(1).movementDate.toLocalDate());
        }
    }

    /**
     * A transfer is refused on every leg: no session named, an unknown one, a
     * validated day, no destination, the same tender at both ends, no amount
     * and a non-positive one.
     */
    @Test
    @SuppressWarnings("unchecked")
    void atransferIsRefusedOnEveryLeg() {
        AdminDayHistoryResource resource = newResource();
        assertTrue(location(resource.transfer(form("day", "2026-09-15"))).contains("Session inconnue."));
        PanacheQuery<CashSession> missing = mock(PanacheQuery.class);
        PanacheQuery<CashSession> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(session("C01", "0.00"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            panache.when(() -> CashSession.find("sessionNumber", "NONE")).thenReturn(missing);
            panache.when(() -> CashSession.find("sessionNumber", "C01-1")).thenReturn(found);
            assertTrue(location(resource.transfer(form("session", "NONE"))).contains("Session inconnue."));
            stubValidation(panache, DAY, new SafeDayValidation());
            assertTrue(location(resource.transfer(form("day", "2026-09-15", "session", "C01-1", "reason", "r",
                    "endorsedBy", "B"))).contains("déjà validée"));
            stubValidation(panache, DAY, null);
            String base = "2026-09-15";
            assertTrue(location(resource.transfer(form("day", base, "session", "C01-1", "reason", "r",
                    "endorsedBy", "B", "amount", "1"))).contains("doivent différer"));
            assertTrue(location(resource.transfer(form("day", base, "session", "C01-1", "reason", "r",
                    "endorsedBy", "B", "from", "CARD", "to", "CARD", "amount", "1"))).contains("doivent différer"));
            assertTrue(location(resource.transfer(form("day", base, "session", "C01-1", "reason", "r",
                    "endorsedBy", "B", "to", "CARD"))).contains("strictement positif"));
            assertTrue(location(resource.transfer(form("day", base, "session", "C01-1", "reason", "r",
                    "endorsedBy", "B", "to", "CARD", "amount", "0"))).contains("strictement positif"));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * Validating the day freezes its net revenue — tickets less refunds — and
     * its takings tender by tender, under the controller's name.
     */
    @Test
    void validatingTheDayFreezesItsNetRevenue() {
        AdminDayHistoryResource resource = newResource();
        CashSession first = session("C01", "0.00");
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("CASH", new BigDecimal("300"));
        totals.put("CARD", new BigDecimal("120.5"));
        CashSessionService.SessionReport report = report(totals);
        report.totalIncludingTax = new BigDecimal("420.50");
        report.totalRefunds = new BigDecimal("20.50");
        when(resource.cashSessionService.buildReport(first)).thenReturn(report);
        CashMovement validated = movement(CashMovement.MovementType.DEPOSIT, "10");
        validated.endorsedBy = "B1";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeDayValidation> created = mockConstruction(SafeDayValidation.class)) {
            stubSessions(panache, DAY, List.of(first));
            stubMovements(panache, DAY, List.of(validated));
            assertTrue(location(resource.validateDay(form("day", "2026-09-15", "endorsedBy", "CTRL")))
                    .contains("Chiffre d'affaires net de la journée validé."));
            SafeDayValidation validation = created.constructed().get(0);
            assertEquals(DAY, validation.accountingDay);
            assertEquals(new BigDecimal("400.00"), validation.netRevenue);
            assertEquals("CASH=300.00;CARD=120.50", validation.tenderTotals);
            assertEquals("CTRL", validation.validatedBy);
            verify(validation).persist();
        }
    }

    /**
     * The day is not validated twice, nor without a controller, nor when no
     * session closed, nor while a movement waits for its validation.
     */
    @Test
    void validatingTheDayIsRefusedOnEveryLeg() {
        AdminDayHistoryResource resource = newResource();
        CashSession first = session("C01", "0.00");
        when(resource.cashSessionService.buildReport(first)).thenReturn(report(Map.of()));
        SafeDayValidation done = new SafeDayValidation();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeDayValidation> created = mockConstruction(SafeDayValidation.class)) {
            stubSessions(panache, LocalDate.now(), List.of());
            stubMovements(panache, LocalDate.now(), List.of());
            assertTrue(location(resource.validateDay(form())).contains("Nom ou badge du valideur obligatoire."));
            assertTrue(location(resource.validateDay(form("endorsedBy", "B"))).contains("rien à valider"));
            stubSessions(panache, DAY, List.of(first));
            stubMovements(panache, DAY, List.of(movement(CashMovement.MovementType.DEPOSIT, "1")));
            assertTrue(location(resource.validateDay(form("day", "2026-09-15", "endorsedBy", "B")))
                    .contains("ne sont pas validés"));
            stubValidation(panache, DAY, done);
            assertTrue(location(resource.validateDay(form("day", "2026-09-15", "endorsedBy", "B")))
                    .contains("déjà validée"));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * The page states the day's validation, its net revenue, how many
     * movements still wait and the tenders a transfer may name.
     */
    @Test
    void thePageStatesTheValidationOfTheDay() {
        AdminDayHistoryResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafeDays);
        CashMovement waiting = movement(CashMovement.MovementType.DEPOSIT, "1");
        SafeDayValidation validation = new SafeDayValidation();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubSessions(panache, DAY, List.of());
            stubMovements(panache, DAY, List.of(waiting));
            stubValidation(panache, DAY, validation);
            resource.day("2026-09-15", null, true);
        }
        verify(view).data("validation", validation);
        verify(view).data("netRevenue", "0.00");
        verify(view).data("pending", 1L);
        verify(view).data("tenderCodes", List.of("CASH", "CHEQUE", "CARD"));
        verify(view).data("tenderLabels", Map.of("CASH", "CASH", "CHEQUE", "CHEQUE", "CARD", "CARD"));
    }

    /**
     * The net revenue tolerates a report without totals, the pending count
     * reads a blank validation as none, and the tenders skip a movement naming
     * none — both arms of every guard.
     */
    @Test
    void theHelpersOfTheValidationTolerateMissingValues() {
        AdminDayHistoryResource resource = newResource();
        CashSession session = session("C01", "0.00");
        CashSessionService.SessionReport report = report(Map.of());
        report.totalIncludingTax = null;
        report.totalRefunds = null;
        when(resource.cashSessionService.buildReport(session)).thenReturn(report);
        assertEquals(BigDecimal.ZERO, resource.netRevenueOf(List.of(session)));
        CashMovement blank = movement(CashMovement.MovementType.DEPOSIT, "1");
        blank.endorsedBy = " ";
        blank.paymentMethod = " ";
        CashMovement none = movement(CashMovement.MovementType.DEPOSIT, "1");
        none.paymentMethod = null;
        none.endorsedBy = "B";
        assertEquals(1L, AdminDayHistoryResource.pendingOf(List.of(blank, none)));
        assertEquals(List.of("CASH", "CARD"), AdminDayHistoryResource.tenderCodesOf(
                Map.of("CARD", BigDecimal.ONE), List.of(blank, none)));
        CashMovement transfer = movement(CashMovement.MovementType.TRANSFER, "1");
        transfer.paymentMethod = "CASH";
        transfer.transferTo = "TR";
        CashMovement blankTarget = movement(CashMovement.MovementType.TRANSFER, "1");
        blankTarget.transferTo = " ";
        blankTarget.paymentMethod = null;
        CashMovement noTarget = movement(CashMovement.MovementType.DEPOSIT, "1");
        noTarget.paymentMethod = null;
        noTarget.transferTo = null;
        assertEquals(List.of("CASH", "TR"), AdminDayHistoryResource.tenderCodesOf(Map.of(),
                List.of(transfer, blankTarget, noTarget)));
        assertEquals("abc", AdminDayHistoryResource.clip("abc"));
        assertEquals(255, AdminDayHistoryResource.clip("x".repeat(300)).length());
        assertFalse(AdminDayHistoryResource.clip("x".repeat(300)).length() > 255);
    }

    /**
     * A tender shows the name of its nature in the page's language; a key the
     * bundles do not name shows the label it is declared under, else itself
     * when it is not declared or declared without label.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thetendersShowTheirLabels() {
        com.intermarche.edge.domain.payment.TenderDefinition card =
                new com.intermarche.edge.domain.payment.TenderDefinition();
        card.label = "Carte bancaire";
        com.intermarche.edge.domain.payment.TenderDefinition unnamed =
                new com.intermarche.edge.domain.payment.TenderDefinition();
        unnamed.label = " ";
        com.intermarche.edge.domain.payment.TenderDefinition bare =
                new com.intermarche.edge.domain.payment.TenderDefinition();
        PanacheQuery<Object> cards = mock(PanacheQuery.class);
        when(cards.firstResult()).thenReturn(card);
        PanacheQuery<Object> unnamedOnes = mock(PanacheQuery.class);
        when(unnamedOnes.firstResult()).thenReturn(unnamed);
        PanacheQuery<Object> bareOnes = mock(PanacheQuery.class);
        when(bareOnes.firstResult()).thenReturn(bare);
        PanacheQuery<Object> none = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "CARD")).thenReturn(cards);
            panache.when(() -> PanacheEntityBase.find("code", "X")).thenReturn(unnamedOnes);
            panache.when(() -> PanacheEntityBase.find("code", "Y")).thenReturn(bareOnes);
            panache.when(() -> PanacheEntityBase.find("code", "Z")).thenReturn(none);
            AdminDayHistoryResource resource = newResource();
            when(resource.i18n.code("t.x21-tender-", "TR")).thenReturn("Titre restaurant");
            Map<String, String> labels = resource.tenderLabelsOf(List.of("CARD", "X", "Y", "TR", "Z"));
            assertEquals(List.of("CARD", "X", "Y", "TR", "Z"), List.copyOf(labels.keySet()));
            assertEquals("Carte bancaire", labels.get("CARD"));
            assertEquals("X", labels.get("X"));
            assertEquals("Y", labels.get("Y"));
            assertEquals("Titre restaurant", labels.get("TR"));
            assertEquals("Z", labels.get("Z"));
            panache.verify(() -> PanacheEntityBase.find("code", "TR"), org.mockito.Mockito.never());
        }
    }

    /**
     * A controller's name or badge is required and holds in 20 characters.
     */
    @Test
    void thecontrollersNameHoldsInTwentyCharacters() {
        assertEquals("Nom ou badge du valideur obligatoire.", AdminDayHistoryResource.endorsementRefusal(null));
        assertNull(AdminDayHistoryResource.endorsementRefusal("x".repeat(20)));
        assertEquals("Nom ou badge du valideur trop long : 20 caractères au plus.",
                AdminDayHistoryResource.endorsementRefusal("x".repeat(21)));
    }
}
