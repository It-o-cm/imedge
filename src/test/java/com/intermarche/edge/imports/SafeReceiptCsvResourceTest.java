package com.intermarche.edge.imports;

import com.intermarche.edge.domain.safe.AccountingPeriod;
import com.intermarche.edge.domain.safe.SafeMovement;
import com.intermarche.edge.domain.safe.SafeMovementType;
import com.intermarche.edge.domain.safe.SafeVatLine;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link SafeReceiptCsvResource}.
 *
 * <p>The staged transaction machinery belongs to {@link ImporterCsvResource} and
 * is tested there; what is exercised here is what this subclass adds — the
 * UPSERT ON THE REFERENCE that stops a feed resending yesterday's file from
 * crediting the safe twice, the refusal to reopen a closed period, and the
 * optional VAT triplet.
 *
 * <p>Branch enumeration: {@code processLineLogic} — an unreadable, absent, zero
 * and negative amount; a new row with and without an open period; an existing
 * row correctable and frozen. {@code decimal} and {@code date} — the readable,
 * the blank, the null and the unreadable. {@code vatLinesOf} — a stated triplet
 * and a blank one.
 */
class SafeReceiptCsvResourceTest {

    /** The resource under test. */
    private final SafeReceiptCsvResource resource = new SafeReceiptCsvResource();

    /** The header the rows are built against. */
    private static final List<String> COLUMNS = List.of(
            SafeReceiptCsvResource.COL_REFERENCE, SafeReceiptCsvResource.COL_LABEL,
            SafeReceiptCsvResource.COL_AMOUNT, SafeReceiptCsvResource.COL_DATE,
            SafeReceiptCsvResource.COL_VAT_RATE, SafeReceiptCsvResource.COL_VAT_BASE,
            SafeReceiptCsvResource.COL_VAT_AMOUNT);

    /**
     * Builds a row from its cells, in header order.
     *
     * @param cells the cells of the row
     * @return the parsed row
     */
    private ImporterCsvResource.LineData row(String... cells) {
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int index = 0; index < COLUMNS.size(); index++) {
            header.put(COLUMNS.get(index), index);
        }
        return new ImporterCsvResource.LineData(1, header, cells,
                SafeReceiptCsvResource.COL_REFERENCE);
    }

    /**
     * Builds an integrated receipt already in the database.
     *
     * @param reference the feed's reference
     * @param correctable whether its period still accepts a correction
     * @return the movement
     */
    private SafeMovement existing(String reference, boolean correctable) {
        SafeMovement movement = mock(SafeMovement.class);
        movement.id = 3L;
        movement.type = SafeMovementType.AUTOMATIC_RECEIPT;
        movement.reference = reference;
        when(movement.isCorrectable()).thenReturn(correctable);
        return movement;
    }

    /**
     * An amount is read with the decimal comma and the decimal point, and an
     * absent, blank or unreadable one is nothing — the four legs of that guard.
     */
    @Test
    void anAmountIsReadOrNothing() {
        assertEquals(new BigDecimal("12.50"), SafeReceiptCsvResource.decimal("12,50"));
        assertEquals(new BigDecimal("1200.00"), SafeReceiptCsvResource.decimal("1 200.00"));
        assertNull(SafeReceiptCsvResource.decimal(null));
        assertNull(SafeReceiptCsvResource.decimal("   "));
        assertNull(SafeReceiptCsvResource.decimal("douze"));
    }

    /**
     * A day is read as ISO, and an absent, blank or unreadable one is nothing —
     * the four legs of that guard.
     */
    @Test
    void adayIsReadOrNothing() {
        assertEquals(LocalDate.of(2026, 9, 15), SafeReceiptCsvResource.date(" 2026-09-15 "));
        assertNull(SafeReceiptCsvResource.date(null));
        assertNull(SafeReceiptCsvResource.date("  "));
        assertNull(SafeReceiptCsvResource.date("15/09/2026"));
    }

    /**
     * A row carrying a VAT triplet states one line; a row carrying none states
     * nothing — the two arms of that reader.
     */
    @Test
    void thevatTripletIsReadWhenTheFeedCarriesOne() {
        List<SafeVatLine> stated = resource.vatLinesOf(
                row("R-1", "Station", "120,00", "2026-09-15", "20,00", "100,00", "20,00"));
        assertEquals(1, stated.size());
        assertEquals(new BigDecimal("100.00"), stated.get(0).base);
        assertTrue(resource.vatLinesOf(row("R-1", "Station", "120,00", "2026-09-15", "", "", ""))
                .isEmpty());
    }

    /**
     * A row with an unusable amount writes nothing at all — the four legs of
     * that guard: absent, unreadable, zero, negative.
     */
    @Test
    void arowWithAnUnusableAmountWritesNothing() {
        int[] counters = new int[2];
        Map<String, Object> known = new HashMap<>();
        resource.processLineLogic(row("R-1", "Station", "", "2026-09-15"), known, counters);
        resource.processLineLogic(row("R-1", "Station", "beaucoup", "2026-09-15"), known, counters);
        resource.processLineLogic(row("R-1", "Station", "0", "2026-09-15"), known, counters);
        resource.processLineLogic(row("R-1", "Station", "-12,00", "2026-09-15"), known, counters);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * A reference the safe has never seen writes nothing when NO period is open:
     * the one door, shut for the feed as for the screens.
     */
    @Test
    void anewRowWritesNothingWithoutAnOpenPeriod() {
        int[] counters = new int[2];
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<AccountingPeriod> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> AccountingPeriod.find("status = ?1 order by startDate desc",
                    AccountingPeriod.PeriodStatus.OPEN)).thenReturn(query);
            resource.processLineLogic(row("R-1", "Station", "120,00", "2026-09-15"),
                    new HashMap<>(), counters);
        }
        assertEquals(0, counters[0]);
    }

    /**
     * A reference already integrated is CORRECTED, not added again: that is what
     * stops a feed resending its file from crediting the safe twice.
     */
    @Test
    void areferenceAlreadyIntegratedIsCorrected() {
        int[] counters = new int[2];
        SafeMovement movement = existing("R-1", true);
        Map<String, Object> known = new HashMap<>();
        known.put("R-1", movement);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeMovement.findById(3L)).thenReturn(movement);
            resource.processLineLogic(
                    row("R-1", "Station-service", "150,00", "2026-09-16", "20,00", "125,00",
                            "25,00"), known, counters);
        }
        assertEquals(0, counters[0]);
        assertEquals(1, counters[1]);
        assertEquals(new BigDecimal("150.00"), movement.amount);
        assertEquals("Station-service", movement.label);
        assertEquals(LocalDate.of(2026, 9, 16), movement.movementDate);
        assertEquals(1, movement.vatLines.size());
    }

    /**
     * A row whose period has been CLOSED is left alone: the closure is not
     * reopened behind its own back, and the line counts as neither created nor
     * updated because nothing happened.
     */
    @Test
    void arowOfAclosedPeriodIsLeftAlone() {
        int[] counters = new int[2];
        SafeMovement frozen = existing("R-1", false);
        Map<String, Object> known = new HashMap<>();
        known.put("R-1", frozen);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeMovement.findById(3L)).thenReturn(frozen);
            resource.processLineLogic(row("R-1", "Station", "150,00", "2026-09-16"), known,
                    counters);
        }
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
        assertNull(frozen.amount);
    }

    /**
     * A row carrying no wording and no day is named after its reference and
     * counts for today — the two fallbacks of that writer.
     */
    @Test
    void arowWithoutAwordingOrAdayFallsBack() {
        int[] counters = new int[2];
        SafeMovement movement = existing("R-2", true);
        Map<String, Object> known = new HashMap<>();
        known.put("R-2", movement);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeMovement.findById(3L)).thenReturn(movement);
            resource.processLineLogic(row("R-2", "  ", "150,00", ""), known, counters);
        }
        assertEquals("Recette intégrée R-2", movement.label);
        assertEquals(LocalDate.now(), movement.movementDate);
    }

    /**
     * The bulk fetch asks for the integrated receipts the chunk's references
     * name, and answers an empty map on an empty chunk — both arms.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thebulkFetchAsksForTheChunksReferences() {
        SafeMovement movement = existing("R-1", true);
        List<ImporterCsvResource.LineData> lines =
                List.of(row("R-1", "Station", "120,00", "2026-09-15"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeMovement.list("type = ?1 and reference IN ?2",
                    SafeMovementType.AUTOMATIC_RECEIPT, Set.of("R-1")))
                    .thenReturn(List.of(movement));
            Map<String, Object> rows = resource.processChunkWithFallback(lines, Set.of("R-1"),
                    new int[2], new java.util.ArrayList<>());
            assertSame(movement, rows.get("R-1"));
            assertTrue(resource.processChunkWithFallback(List.of(), Set.of(), new int[2],
                    new java.util.ArrayList<>()).isEmpty());
        }
    }

    /**
     * The one-by-one fallback finds an integrated receipt by its reference, and
     * answers null on one the safe has never seen — both arms.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theFallbackFindsOneRowByItsReference() {
        SafeMovement movement = existing("R-1", true);
        PanacheQuery<SafeMovement> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(movement);
        PanacheQuery<SafeMovement> missing = mock(PanacheQuery.class);
        when(missing.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeMovement.find("type = ?1 and reference = ?2",
                    SafeMovementType.AUTOMATIC_RECEIPT, "R-1")).thenReturn(found);
            panache.when(() -> SafeMovement.find("type = ?1 and reference = ?2",
                    SafeMovementType.AUTOMATIC_RECEIPT, "R-9")).thenReturn(missing);
            assertSame(movement,
                    resource.findEntityForLine(row("R-1", "Station", "120,00", "2026-09-15")));
            assertNull(resource.findEntityForLine(row("R-9", "Station", "120,00", "2026-09-15")));
        }
    }

    /**
     * A reference the safe has never seen is CREATED PENDING when a period is
     * open: the movement is stamped AUTOMATIC_RECEIPT, dated, valued and
     * persisted, and the created counter — not the updated one — is stepped.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anewRowWithAnOpenPeriodIsCreated() {
        int[] counters = new int[2];
        AccountingPeriod period = mock(AccountingPeriod.class);
        EntityManager entityManager = mock(EntityManager.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<Panache> panacheStatic = mockStatic(Panache.class);
                MockedConstruction<SafeMovement> construction = mockConstruction(SafeMovement.class)) {
            PanacheQuery<AccountingPeriod> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(period);
            panache.when(() -> AccountingPeriod.find("status = ?1 order by startDate desc",
                    AccountingPeriod.PeriodStatus.OPEN)).thenReturn(query);
            panacheStatic.when(Panache::getEntityManager).thenReturn(entityManager);
            resource.processLineLogic(
                    row("R-3", "Station", "120,00", "2026-09-15", "20,00", "100,00", "20,00"),
                    new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            SafeMovement created = construction.constructed().get(0);
            assertEquals(SafeMovementType.AUTOMATIC_RECEIPT, created.type);
            assertEquals("R-3", created.reference);
            assertSame(period, created.period);
            assertEquals(SafeMovement.MovementStatus.PENDING, created.status);
            assertEquals(new BigDecimal("120.00"), created.amount);
            assertEquals("Station", created.label);
            assertEquals(LocalDate.of(2026, 9, 15), created.movementDate);
            assertEquals(1, created.vatLines.size());
            verify(entityManager).persist(created);
        }
    }

    /**
     * A row whose wording cell is absent altogether — not merely blank — also
     * falls back to the reference-derived label: the null arm of that guard, the
     * blank arm being covered elsewhere.
     */
    @Test
    void arowWithAnAbsentWordingFallsBack() {
        int[] counters = new int[2];
        SafeMovement movement = existing("R-5", true);
        Map<String, Object> known = new HashMap<>();
        known.put("R-5", movement);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeMovement.findById(3L)).thenReturn(movement);
            resource.processLineLogic(row("R-5", null, "150,00", "2026-09-16"), known, counters);
        }
        assertEquals("Recette intégrée R-5", movement.label);
    }

    /**
     * The public entry point delegates to the staged import keyed on REFERENCE
     * with AMOUNT required: a header missing AMOUNT is refused with a 400, which
     * proves the key and required columns this importer hands down.
     */
    @Test
    void theentryPointDelegatesKeyedOnReferenceRequiringAmount() {
        InputStream stream = new ByteArrayInputStream(
                "REFERENCE\nR-1".getBytes(StandardCharsets.UTF_8));
        Response response = resource.importReceipts(stream);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains(SafeReceiptCsvResource.COL_AMOUNT));
    }
}
