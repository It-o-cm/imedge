package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.safe.AccountingPeriod;
import com.intermarche.edge.domain.safe.SafeMovement;
import com.intermarche.edge.domain.safe.SafeMovementType;
import com.intermarche.edge.service.SafeService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AdminSafeJournalResource}.
 *
 * <p>The report the shops print. What is proved here is the running balance it
 * carries line by line, the two columns a movement writes in — never both — and
 * the three fallbacks of its bounds: the day asked for, the start of the open
 * period, and the epoch when the safe has never been opened.
 */
class AdminSafeJournalResourceTest {

    /**
     * Builds a resource with mocked template and safe.
     *
     * @return the wired resource
     */
    private AdminSafeJournalResource newResource() {
        AdminSafeJournalResource resource = new AdminSafeJournalResource();
        resource.adminSafeJournal = mock(Template.class);
        resource.safeService = mock(SafeService.class);
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
     * Builds a movement of a kind and an amount.
     *
     * @param type the kind
     * @param amount the amount, as a plain decimal string
     * @return the movement
     */
    private SafeMovement movement(SafeMovementType type, String amount) {
        SafeMovement movement = new SafeMovement();
        movement.type = type;
        movement.amount = new BigDecimal(amount);
        movement.label = "Mouvement";
        movement.movementDate = LocalDate.of(2026, 9, 15);
        return movement;
    }

    /**
     * Each line carries its column and the balance the safe stands at once it is
     * counted: a credit writes on the right, a debit on the left, never both.
     */
    @Test
    void eachLineCarriesItsColumnAndTheRunningBalance() {
        AdminSafeJournalResource resource = newResource();
        List<AdminSafeJournalResource.JournalRow> rows = resource.rows(
                List.of(movement(SafeMovementType.POUCH, "1000.00"),
                        movement(SafeMovementType.EXPENSE, "40.00")),
                new BigDecimal("100.00"));
        assertEquals(2, rows.size());
        assertEquals("", rows.get(0).getDebit());
        assertEquals("1000.00", rows.get(0).getCredit());
        assertEquals("1100.00", rows.get(0).getBalance());
        assertEquals("40.00", rows.get(1).getDebit());
        assertEquals("", rows.get(1).getCredit());
        assertEquals("1060.00", rows.get(1).getBalance());
        assertSame(SafeMovementType.EXPENSE, rows.get(1).getMovement().type);
    }

    /**
     * A movement carrying no kind writes in neither column — the null arm of
     * that guard, which a malformed row must not make explode.
     */
    @Test
    void amovementWithoutAkindWritesInNeitherColumn() {
        AdminSafeJournalResource resource = newResource();
        List<AdminSafeJournalResource.JournalRow> rows =
                resource.rows(List.of(movement(null, "40.00")), BigDecimal.ZERO);
        assertEquals("", rows.get(0).getDebit());
        assertEquals("", rows.get(0).getCredit());
        assertEquals("40.00", rows.get(0).getBalance());
    }

    /**
     * The page carries the bounds asked for, the report brought forward and the
     * two column totals.
     */
    @Test
    void thepageCarriesTheBoundsAndTheTotals() {
        AdminSafeJournalResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafeJournal);
        when(resource.safeService.journal(LocalDate.of(1970, 1, 1), LocalDate.of(2026, 8, 31)))
                .thenReturn(List.of(movement(SafeMovementType.POUCH, "100.00")));
        when(resource.safeService.journal(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(List.of(movement(SafeMovementType.POUCH, "1000.00"),
                        movement(SafeMovementType.EXPENSE, "40.00")));
        assertSame(view, resource.journal("2026-09-01", "2026-09-30"));
        verify(view, "opening", "100.00");
        verify(view, "closing", "1060.00");
        verify(view, "totalDebit", "40.00");
        verify(view, "totalCredit", "1000.00");
        verify(view, "from", "2026-09-01");
        verify(view, "to", "2026-09-30");
    }

    /**
     * Asserts that one data key was set with one value.
     *
     * @param view the chained view
     * @param key the data key
     * @param value the expected value
     */
    private void verify(TemplateInstance view, String key, String value) {
        org.mockito.Mockito.verify(view).data(eq(key), eq(value));
    }

    /**
     * Without a first day, the journal starts at the open period's first day,
     * and at the epoch when the safe has never been opened — the three legs of
     * that fallback.
     */
    @Test
    void withoutAfirstDayTheJournalStartsAtThePeriodOrTheEpoch() {
        AdminSafeJournalResource resource = newResource();
        stub(resource.adminSafeJournal);
        AccountingPeriod period = new AccountingPeriod();
        period.startDate = LocalDate.of(2026, 9, 1);
        when(resource.safeService.openPeriod()).thenReturn(period, period, (AccountingPeriod) null);
        when(resource.safeService.journal(any(), any())).thenReturn(List.of());
        resource.journal(null, "2026-09-30");
        org.mockito.Mockito.verify(resource.safeService)
                .journal(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        resource.journal("  ", "2026-09-30");
        resource.journal(null, "2026-09-30");
        org.mockito.Mockito.verify(resource.safeService)
                .journal(LocalDate.of(1970, 1, 1), LocalDate.of(2026, 9, 30));
    }

    /**
     * An open period with no first day falls back to the epoch too — the second
     * leg of that compound guard.
     */
    @Test
    void anOpenPeriodWithoutAfirstDayFallsBackToTheEpoch() {
        AdminSafeJournalResource resource = newResource();
        stub(resource.adminSafeJournal);
        AccountingPeriod period = new AccountingPeriod();
        when(resource.safeService.openPeriod()).thenReturn(period);
        when(resource.safeService.journal(any(), any())).thenReturn(List.of());
        resource.journal(null, "2026-09-30");
        org.mockito.Mockito.verify(resource.safeService)
                .journal(LocalDate.of(1970, 1, 1), LocalDate.of(2026, 9, 30));
    }

    /**
     * Without a last day, the journal stops today.
     */
    @Test
    void withoutAlastDayTheJournalStopsToday() {
        AdminSafeJournalResource resource = newResource();
        stub(resource.adminSafeJournal);
        when(resource.safeService.journal(any(), any())).thenReturn(List.of());
        resource.journal("2026-09-01", null);
        org.mockito.Mockito.verify(resource.safeService)
                .journal(LocalDate.of(2026, 9, 1), LocalDate.now());
    }

    /**
     * The extraction states one row per movement, the running balance included,
     * and names the file after the days it covers.
     */
    @Test
    void theextractionStatesOneRowPerMovement() {
        AdminSafeJournalResource resource = newResource();
        SafeMovement expense = movement(SafeMovementType.EXPENSE, "40.00");
        expense.reference = "FACT;12";
        when(resource.safeService.journal(LocalDate.of(1970, 1, 1), LocalDate.of(2026, 8, 31)))
                .thenReturn(List.of());
        when(resource.safeService.journal(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(List.of(expense));
        Response response = resource.export("2026-09-01", "2026-09-30");
        assertEquals(200, response.getStatus());
        String csv = String.valueOf(response.getEntity());
        assertTrue(csv.startsWith("DATE;NATURE;LIBELLE;REFERENCE;DEBIT;CREDIT;SOLDE;ETAT\n"));
        assertTrue(csv.contains("2026-09-15;Dépense;Mouvement;FACT,12;40.00;;-40.00;A pointer"));
        assertTrue(response.getHeaderString("Content-Disposition")
                .contains("journal-coffre-2026-09-01-2026-09-30.csv"));
    }

    /**
     * A validated movement is extracted as such, and a movement carrying no day
     * and no reference leaves those cells empty — the null arms of the export's
     * own guards.
     */
    @Test
    void theextractionToleratesTheMissingParts() {
        AdminSafeJournalResource resource = newResource();
        SafeMovement bare = movement(SafeMovementType.RECEIPT, "30.00");
        bare.movementDate = null;
        bare.status = SafeMovement.MovementStatus.VALIDATED;
        when(resource.safeService.journal(LocalDate.of(1970, 1, 1), LocalDate.of(2026, 8, 31)))
                .thenReturn(List.of());
        when(resource.safeService.journal(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(List.of(bare));
        String csv = String.valueOf(resource.export("2026-09-01", "2026-09-30").getEntity());
        assertTrue(csv.contains(";Recette;Mouvement;;;30.00;30.00;Valide"));
    }

    /**
     * The extraction without a last day stops today — the null arm of that guard
     * in the export path.
     */
    @Test
    void theextractionWithoutAlastDayStopsToday() {
        AdminSafeJournalResource resource = newResource();
        when(resource.safeService.journal(any(), any())).thenReturn(List.of());
        Response response = resource.export("2026-09-01", null);
        assertTrue(response.getHeaderString("Content-Disposition")
                .contains("journal-coffre-2026-09-01-" + LocalDate.now() + ".csv"));
    }

    /**
     * The column totals tolerate a movement with no kind — the null arm of the
     * kind guard, which writes in neither column — and a matching movement with
     * no amount, counted as zero and formatted as zero.
     */
    @Test
    void thecolumnTotalsTolerateAnullKindAndAnullAmount() {
        AdminSafeJournalResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafeJournal);
        SafeMovement kindless = new SafeMovement();
        kindless.type = null;
        kindless.amount = new BigDecimal("5.00");
        kindless.movementDate = LocalDate.of(2026, 9, 15);
        SafeMovement amountless = new SafeMovement();
        amountless.type = SafeMovementType.POUCH;
        amountless.amount = null;
        amountless.movementDate = LocalDate.of(2026, 9, 15);
        when(resource.safeService.journal(LocalDate.of(1970, 1, 1), LocalDate.of(2026, 8, 31)))
                .thenReturn(List.of());
        when(resource.safeService.journal(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(List.of(kindless, amountless));
        assertSame(view, resource.journal("2026-09-01", "2026-09-30"));
    }
}
