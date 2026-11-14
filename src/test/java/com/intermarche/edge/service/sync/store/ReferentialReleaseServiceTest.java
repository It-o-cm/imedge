package com.intermarche.edge.service.sync.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.setting.EchelonLevel;
import com.intermarche.edge.domain.setting.EchelonSetting;
import com.intermarche.edge.domain.setting.PosSetting;
import com.intermarche.edge.domain.setting.StoreImage;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.sync.ReferentialRelease;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.sync.RefApplyService;
import com.intermarche.edge.service.sync.RefPayloads;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReferentialReleaseService} (BO-03-12-03, BO-03-12-04):
 * the arming, the capture before a programmed change and the releases it
 * opens, the held rows the exporter answers on both sides of the date, the
 * loop that releases them, their move and their cancellation, and the dated
 * parameters.
 */
class ReferentialReleaseServiceTest {

    /** The query of the pending releases. */
    private static final String PENDING = "status = ?1 order by effectiveAt, domain";

    /** The query of one domain's pending release. */
    private static final String ONE_PENDING = "status = ?1 and domain = ?2";

    /**
     * Builds a service on mocked collaborators and a real JSON mapper.
     *
     * @return the service
     */
    private ReferentialReleaseService service() {
        ReferentialReleaseService service = new ReferentialReleaseService();
        service.role = "store";
        service.refExportService = mock(RefExportService.class);
        service.refApplyService = mock(RefApplyService.class);
        service.posSettingsService = mock(PosSettingsService.class);
        service.echelonSettings = mock(com.intermarche.edge.service.EchelonSettingService.class);
        service.objectMapper = new ObjectMapper();
        return service;
    }

    /**
     * Builds a pending release holding rows.
     *
     * @param service the service whose writer encodes the version
     * @param domain the domain
     * @param effectiveAt the date of effect
     * @param scopes the held rows by scope
     * @param source the source tables, or null
     * @return the release
     */
    private ReferentialRelease release(ReferentialReleaseService service, String domain, LocalDateTime effectiveAt,
                                       Map<String, List<?>> scopes, String source) {
        ReferentialRelease release = new ReferentialRelease();
        release.id = 5L;
        release.domain = domain;
        release.effectiveAt = effectiveAt;
        release.programmedAt = LocalDateTime.of(2026, 10, 25, 9, 30);
        release.programmedBy = "admin";
        release.origin = "admin/articles/save";
        release.held = service.write(scopes, source);
        return release;
    }

    /**
     * Builds one price row.
     *
     * @param ean the article
     * @return the row
     */
    private RefPayloads.PriceDto price(String ean) {
        RefPayloads.PriceDto dto = new RefPayloads.PriceDto();
        dto.productEan = ean;
        return dto;
    }

    /**
     * Builds one account row.
     *
     * @param login the login
     * @return the row
     */
    private RefPayloads.EmployeeDto account(String login) {
        RefPayloads.EmployeeDto dto = new RefPayloads.EmployeeDto();
        dto.loginName = login;
        dto.role = "CASHIER";
        return dto;
    }

    /**
     * Builds a query answering one row.
     *
     * @param row the row, or null
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Object> one(Object row) {
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /**
     * Sets the day the loop last looked at.
     *
     * @param service the service
     * @param day the day
     * @throws Exception never
     */
    private void lastDay(ReferentialReleaseService service, LocalDate day) throws Exception {
        Field field = ReferentialReleaseService.class.getDeclaredField("lastDay");
        field.setAccessible(true);
        field.set(service, day);
    }

    /**
     * Reads the held cache of a service.
     *
     * @param service the service
     * @return the cache, possibly null
     * @throws Exception never
     */
    private Object heldCache(ReferentialReleaseService service) throws Exception {
        Field field = ReferentialReleaseService.class.getDeclaredField("heldCache");
        field.setAccessible(true);
        return field.get(service);
    }

    /** The loop starts and stops cleanly, a stop without start being harmless. */
    @Test
    void theLoopStartsAndStops() {
        ReferentialReleaseService service = service();
        service.stop();
        service.onStart(null);
        service.stop();
    }

    /** One pass of the loop runs in its own transaction and survives a failure. */
    @Test
    @SuppressWarnings("unchecked")
    void aLoopPassRunsInATransactionAndSurvivesAFailure() throws Exception {
        ReferentialReleaseService service = service();
        TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
            when(runner.call(any(Callable.class))).thenReturn(0);
            service.promoteDueSafely();
            when(runner.call(any(Callable.class))).thenThrow(new IllegalStateException("base"));
            service.promoteDueSafely();
            verify(runner, times(2)).call(any(Callable.class));
        }
    }

    /** Only a store node holds versions for registers. */
    @Test
    void onlyAStoreIsEnabled() {
        ReferentialReleaseService service = service();
        assertTrue(service.enabled());
        service.role = "central";
        assertFalse(service.enabled());
    }

    /** Arming needs a login and a date to come; a disarm forgets it. */
    @Test
    void armingNeedsALoginAndADateToCome() {
        ReferentialReleaseService service = service();
        LocalDateTime tomorrow = LocalDateTime.now().plusDays(1);
        assertFalse(service.arm(null, tomorrow));
        assertFalse(service.arm("admin", null));
        assertFalse(service.arm("admin", LocalDateTime.now().minusMinutes(1)));
        assertNull(service.armedAt("admin"));
        assertTrue(service.arm("admin", tomorrow));
        assertEquals(tomorrow, service.armedAt("admin"));
        assertNull(service.armedAt(null));
        service.disarm(null);
        assertEquals(tomorrow, service.armedAt("admin"));
        service.disarm("admin");
        assertNull(service.armedAt("admin"));
    }

    /** A date armed and since passed is forgotten: the changes are immediate again. */
    @Test
    @SuppressWarnings("unchecked")
    void aPassedArmedDateIsForgotten() throws Exception {
        ReferentialReleaseService service = service();
        Field field = ReferentialReleaseService.class.getDeclaredField("armed");
        field.setAccessible(true);
        ((Map<String, LocalDateTime>) field.get(service)).put("admin", LocalDateTime.now().minusMinutes(1));
        assertNull(service.armedAt("admin"));
        assertTrue(((Map<String, LocalDateTime>) field.get(service)).isEmpty());
    }

    /**
     * The capture takes every distributed domain not held, per register for a
     * domain resolved per register, and the tables behind the resolved domains.
     */
    @Test
    void theCaptureTakesEveryDomainNotHeld() {
        ReferentialReleaseService service = service();
        CheckoutPoint point = new CheckoutPoint();
        point.terminalId = "C04";
        List<?> rows = List.of(price("1"));
        when(service.refExportService.isTerminalScoped("EMPLOYEES")).thenReturn(true);
        when(service.refExportService.liveRows(anyString(), any())).thenAnswer(invocation -> rows);
        when(service.refExportService.fingerprintOf(rows)).thenReturn("fp");
        EchelonSetting dated = new EchelonSetting();
        dated.level = EchelonLevel.PDV;
        dated.echelonCode = "0123";
        dated.settingKey = "ticket.footer";
        dated.settingValue = "MERCI";
        dated.pendingValue = "AU REVOIR";
        dated.effectiveDate = LocalDate.of(2026, 11, 2);
        EchelonSetting immediate = new EchelonSetting();
        immediate.level = EchelonLevel.CHECKOUT_POINT;
        immediate.echelonCode = "C04";
        immediate.settingKey = "menu.code";
        immediate.settingValue = "SLS";
        PosSetting local = new PosSetting();
        local.settingKey = "display.show-ean";
        local.settingValue = "true";
        StoreImage logo = new StoreImage();
        logo.code = "LOGO";
        logo.label = "Logo";
        logo.usage = StoreImage.Usage.values()[0];
        logo.level = EchelonLevel.PDV;
        StoreImage visual = new StoreImage();
        visual.code = "VIS";
        visual.label = "Visuel";
        visual.usage = StoreImage.Usage.values()[0];
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(PENDING, ReferentialRelease.Status.PENDING)).thenReturn(List.of());
            panache.when(() -> PanacheEntityBase.list("order by terminalId")).thenReturn(List.of(point));
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of(dated, immediate), List.of(local),
                    List.of(logo, visual));
            ReferentialReleaseService.Capture capture = service.capture(LocalDateTime.of(2026, 11, 1, 6, 0), "admin");
            assertEquals(RefPayloads.DOMAINS, new ArrayList<>(capture.rows.keySet()));
            assertEquals(List.of("", "C04"), new ArrayList<>(capture.rows.get("EMPLOYEES").keySet()));
            assertEquals(List.of(""), new ArrayList<>(capture.rows.get("PRICES").keySet()));
            assertEquals("fp", capture.fingerprints.get("EMPLOYEES").get("C04"));
            assertSame(rows, capture.rows.get("PRICES").get(""));
            verify(service.refExportService).liveRows("EMPLOYEES", "C04");
            verify(service.refExportService).liveRows("EMPLOYEES", null);
            assertTrue(capture.settingsSource.contains("\"effectiveDate\":\"2026-11-02\""));
            assertTrue(capture.settingsSource.contains("\"pendingValue\":\"AU REVOIR\""));
            assertTrue(capture.settingsSource.contains("\"pendingValue\":null"));
            assertTrue(capture.settingsSource.contains("\"effectiveDate\":null"));
            assertTrue(capture.settingsSource.contains("display.show-ean"));
            assertTrue(capture.imagesSource.contains("\"level\":\"PDV\""));
            assertTrue(capture.imagesSource.contains("\"level\":null"));
            assertEquals("admin", capture.user);
        }
    }

    /** A held domain is not captured again, nor the tables behind it. */
    @Test
    void aHeldDomainIsNotCapturedAgain() {
        ReferentialReleaseService service = service();
        LocalDateTime later = LocalDateTime.now().plusDays(1);
        Map<String, List<?>> scopes = Map.of("", List.of());
        ReferentialRelease settings = release(service, "SETTINGS", later, scopes, null);
        ReferentialRelease images = release(service, "STORE_IMAGES", later, scopes, null);
        when(service.refExportService.liveRows(anyString(), any())).thenAnswer(invocation -> List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(PENDING, ReferentialRelease.Status.PENDING))
                    .thenReturn(List.of(settings, images));
            panache.when(() -> PanacheEntityBase.list("order by terminalId")).thenReturn(List.of());
            ReferentialReleaseService.Capture capture = service.capture(later, "admin");
            assertFalse(capture.rows.containsKey("SETTINGS"));
            assertFalse(capture.rows.containsKey("STORE_IMAGES"));
            assertEquals(RefPayloads.DOMAINS.size() - 2, capture.rows.size());
            assertNull(capture.settingsSource);
            assertNull(capture.imagesSource);
        }
    }

    /**
     * A programmed post holds the domains it changed — on any register scope —
     * keeps the tables behind the resolved ones, and skips an unchanged domain
     * and a domain held meanwhile.
     */
    @Test
    void aProgrammedPostHoldsTheDomainsItChanged() throws Exception {
        ReferentialReleaseService service = service();
        LocalDateTime at = LocalDateTime.of(2026, 11, 1, 6, 0);
        ReferentialReleaseService.Capture capture = new ReferentialReleaseService.Capture(at, "admin");
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (String domain : List.of("PRODUCTS", "PRICES", "TENDERS", "SETTINGS", "STORE_IMAGES")) {
            capture.rows.put(domain, Map.of("", List.of()));
            capture.fingerprints.put(domain, Map.of("", "old"));
        }
        fingerprints.put("", "same");
        fingerprints.put("C04", "old");
        capture.rows.put("EMPLOYEES", Map.of("", List.of(), "C04", List.of()));
        capture.fingerprints.put("EMPLOYEES", fingerprints);
        capture.settingsSource = "{\"echelon\":[],\"local\":[]}";
        capture.imagesSource = "{\"images\":[]}";
        when(service.refExportService.liveFingerprint("PRODUCTS", null)).thenReturn("old");
        when(service.refExportService.liveFingerprint("PRICES", null)).thenReturn("new");
        when(service.refExportService.liveFingerprint("TENDERS", null)).thenReturn("new");
        when(service.refExportService.liveFingerprint("SETTINGS", null)).thenReturn("new");
        when(service.refExportService.liveFingerprint("STORE_IMAGES", null)).thenReturn("new");
        when(service.refExportService.liveFingerprint("EMPLOYEES", null)).thenReturn("same");
        when(service.refExportService.liveFingerprint("EMPLOYEES", "C04")).thenReturn("new");
        PanacheQuery<Object> absent = one(null);
        PanacheQuery<Object> present = one(new ReferentialRelease());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<ReferentialRelease> created = mockConstruction(ReferentialRelease.class)) {
            for (String domain : List.of("PRICES", "SETTINGS", "STORE_IMAGES", "EMPLOYEES")) {
                panache.when(() -> PanacheEntityBase.find(ONE_PENDING, ReferentialRelease.Status.PENDING, domain))
                        .thenReturn(absent);
            }
            panache.when(() -> PanacheEntityBase.find(ONE_PENDING, ReferentialRelease.Status.PENDING, "TENDERS"))
                    .thenReturn(present);
            List<String> held = service.record(capture, "admin/articles/save");
            assertEquals(List.of("PRICES", "SETTINGS", "STORE_IMAGES", "EMPLOYEES"), held);
            assertEquals(4, created.constructed().size());
            ReferentialRelease prices = created.constructed().get(0);
            assertEquals("PRICES", prices.domain);
            assertEquals(ReferentialRelease.Status.PENDING, prices.status);
            assertEquals(at, prices.effectiveAt);
            assertEquals("admin", prices.programmedBy);
            assertEquals("admin/articles/save", prices.origin);
            assertFalse(prices.complete);
            assertNotNull(prices.programmedAt);
            assertTrue(prices.held.contains("\"source\":null"));
            assertTrue(created.constructed().get(1).held.contains("echelon"));
            assertTrue(created.constructed().get(2).held.contains("images"));
            verify(prices).persist();
            assertNull(heldCache(service));
        }
    }

    /** A post that changed no distributed domain holds nothing. */
    @Test
    void aPostThatChangedNothingHoldsNothing() {
        ReferentialReleaseService service = service();
        ReferentialReleaseService.Capture capture = new ReferentialReleaseService.Capture(
                LocalDateTime.of(2026, 11, 1, 6, 0), "admin");
        capture.rows.put("PRICES", Map.of("", List.of()));
        capture.fingerprints.put("PRICES", Map.of("", "same"));
        when(service.refExportService.liveFingerprint("PRICES", null)).thenReturn("same");
        assertEquals(List.of(), service.record(capture, "admin/profiles/save"));
    }

    /**
     * A complete deployment needs a date to come, and then holds every domain
     * not held yet, changed or not.
     */
    @Test
    void aCompleteDeploymentHoldsEveryDomain() {
        ReferentialReleaseService service = service();
        assertEquals(List.of(), service.programComplete("admin", null));
        assertEquals(List.of(), service.programComplete("admin", LocalDateTime.now().minusMinutes(1)));
        LocalDateTime at = LocalDateTime.now().plusDays(1);
        when(service.refExportService.liveRows(anyString(), any())).thenAnswer(invocation -> List.of());
        PanacheQuery<Object> absent = one(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<ReferentialRelease> created = mockConstruction(ReferentialRelease.class)) {
            panache.when(() -> PanacheEntityBase.list(PENDING, ReferentialRelease.Status.PENDING)).thenReturn(List.of());
            panache.when(() -> PanacheEntityBase.list("order by terminalId")).thenReturn(List.of());
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of());
            panache.when(() -> PanacheEntityBase.find(eq(ONE_PENDING), (Object) any(), (Object) any())).thenReturn(absent);
            List<String> held = service.programComplete("admin", at);
            assertEquals(RefPayloads.DOMAINS, held);
            assertTrue(created.constructed().get(0).complete);
            assertEquals(ReferentialReleaseService.COMPLETE_ORIGIN, created.constructed().get(0).origin);
            verify(service.refExportService, never()).liveFingerprint(anyString(), any());
        }
    }

    /** An unwritable version or source is reported, not swallowed. */
    @Test
    void anUnwritableVersionIsReported() throws Exception {
        ReferentialReleaseService service = service();
        service.objectMapper = mock(ObjectMapper.class);
        when(service.objectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("x") { });
        assertThrows(IllegalStateException.class, () -> service.write(Map.of(), null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertThrows(IllegalStateException.class, service::settingsSource);
        }
    }

    /** An unreadable version or source is reported, not swallowed. */
    @Test
    void anUnreadableVersionIsReported() {
        ReferentialReleaseService service = service();
        ReferentialRelease release = new ReferentialRelease();
        release.domain = "PRICES";
        release.held = "{not json";
        assertThrows(IllegalStateException.class, () -> service.read(release));
        assertThrows(IllegalStateException.class, () -> service.readSource(release));
    }

    /** The source of a version is absent, null, or the tables captured. */
    @Test
    void theSourceOfAVersionIsReadBack() {
        ReferentialReleaseService service = service();
        ReferentialRelease release = new ReferentialRelease();
        release.held = "{\"scopes\":{}}";
        assertNull(service.readSource(release));
        release.held = service.write(Map.of(), null);
        assertNull(service.readSource(release));
        release.held = service.write(Map.of(), "{\"images\":[]}");
        assertTrue(service.readSource(release).get("images").isArray());
    }

    /**
     * The held rows answer until the date only, per register when it was
     * captured, the common rows otherwise; read once from the base.
     */
    @Test
    void heldRowsAnswerUntilTheDate() {
        ReferentialReleaseService service = service();
        Map<String, List<?>> scopes = new LinkedHashMap<>();
        scopes.put("", List.of(price("ALL")));
        scopes.put("C04", List.of(price("C04")));
        ReferentialRelease future = release(service, "PRICES", LocalDateTime.now().plusDays(1), scopes, null);
        ReferentialRelease past = release(service, "TENDERS", LocalDateTime.now().minusMinutes(1),
                Map.of("", List.of()), null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(PENDING, ReferentialRelease.Status.PENDING))
                    .thenReturn(List.of(future, past));
            assertNull(service.heldRows("PRODUCTS", null));
            assertNull(service.heldRows("TENDERS", null));
            assertEquals("ALL", ((RefPayloads.PriceDto) service.heldRows("PRICES", null).get(0)).productEan);
            assertEquals("C04", ((RefPayloads.PriceDto) service.heldRows("PRICES", " C04 ").get(0)).productEan);
            assertEquals("ALL", ((RefPayloads.PriceDto) service.heldRows("PRICES", "C99").get(0)).productEan);
            assertEquals(2, service.pendingCount());
            panache.verify(() -> PanacheEntityBase.list(PENDING, ReferentialRelease.Status.PENDING), times(1));
        }
    }

    /** The loop releases the due releases and moves the stamp. */
    @Test
    void theLoopReleasesTheDueReleases() throws Exception {
        ReferentialReleaseService service = service();
        lastDay(service, LocalDate.now());
        ReferentialRelease due = release(service, "PRICES", LocalDateTime.now().minusMinutes(1),
                Map.of("", List.of()), null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(eq("status = ?1 and effectiveAt <= ?2"), (Object) any(), (Object) any()))
                    .thenReturn(List.of(due));
            assertEquals(1, service.promoteDue());
            assertEquals(ReferentialRelease.Status.RELEASED, due.status);
            assertNull(due.held);
            assertNull(due.closedBy);
            assertNotNull(due.closedAt);
            verify(service.refExportService).referentialChanged();
            verify(service.posSettingsService, never()).invalidate();
        }
    }

    /** Nothing due on the same day moves nothing. */
    @Test
    void nothingDueMovesNothing() throws Exception {
        ReferentialReleaseService service = service();
        lastDay(service, LocalDate.now());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(eq("status = ?1 and effectiveAt <= ?2"), (Object) any(), (Object) any()))
                    .thenReturn(List.of());
            assertEquals(0, service.promoteDue());
            verify(service.refExportService, never()).referentialChanged();
            verify(service.echelonSettings, never()).promoteDue(any());
        }
    }

    /**
     * The day dated parameters come into effect folds them into their current
     * value and moves the stamp; a day without moves nothing.
     */
    @Test
    void theDayOfDatedParametersMovesTheStamp() throws Exception {
        ReferentialReleaseService service = service();
        lastDay(service, LocalDate.now().minusDays(1));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(eq("status = ?1 and effectiveAt <= ?2"), (Object) any(), (Object) any()))
                    .thenReturn(List.of());
            when(service.echelonSettings.promoteDue(LocalDate.now())).thenReturn(2);
            assertEquals(0, service.promoteDue());
            verify(service.echelonSettings).promoteDue(LocalDate.now());
            verify(service.posSettingsService).invalidate();
            verify(service.refExportService).referentialChanged();
        }
        ReferentialReleaseService quiet = service();
        lastDay(quiet, LocalDate.now().minusDays(1));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(eq("status = ?1 and effectiveAt <= ?2"), (Object) any(), (Object) any()))
                    .thenReturn(List.of());
            when(quiet.echelonSettings.promoteDue(LocalDate.now())).thenReturn(0);
            assertEquals(0, quiet.promoteDue());
            verify(quiet.posSettingsService, never()).invalidate();
            verify(quiet.refExportService, never()).referentialChanged();
        }
    }

    /** A release is moved to a later date, or released at once when the date has come. */
    @Test
    void aReleaseIsMovedOrReleasedNow() {
        ReferentialReleaseService service = service();
        ReferentialRelease release = release(service, "PRICES", LocalDateTime.now().plusDays(1),
                Map.of("", List.of()), null);
        ReferentialRelease closed = new ReferentialRelease();
        closed.status = ReferentialRelease.Status.CANCELLED;
        LocalDateTime later = LocalDateTime.now().plusDays(3);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(null);
            panache.when(() -> PanacheEntityBase.findById(2L)).thenReturn(closed);
            panache.when(() -> PanacheEntityBase.findById(5L)).thenReturn(release);
            assertFalse(service.reschedule(1L, later, "admin"));
            assertFalse(service.reschedule(2L, later, "admin"));
            assertTrue(service.reschedule(5L, later, "admin"));
            assertEquals(later, release.effectiveAt);
            assertEquals(ReferentialRelease.Status.PENDING, release.status);
            verify(service.refExportService, never()).referentialChanged();
            assertTrue(service.reschedule(5L, LocalDateTime.now(), "admin"));
            assertEquals(ReferentialRelease.Status.RELEASED, release.status);
            assertEquals("admin", release.closedBy);
            verify(service.refExportService).referentialChanged();
        }
    }

    /** A cancelled release puts the held version back into the tables. */
    @Test
    @SuppressWarnings("unchecked")
    void aCancelledReleasePutsTheHeldVersionBack() {
        ReferentialReleaseService service = service();
        ReferentialRelease release = release(service, "PRICES", LocalDateTime.now().plusDays(1),
                Map.of("", List.of(price("3700000000017"))), null);
        ReferentialRelease closed = new ReferentialRelease();
        closed.status = ReferentialRelease.Status.RELEASED;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(null);
            panache.when(() -> PanacheEntityBase.findById(2L)).thenReturn(closed);
            panache.when(() -> PanacheEntityBase.findById(5L)).thenReturn(release);
            assertFalse(service.cancel(1L, "admin"));
            assertFalse(service.cancel(2L, "admin"));
            assertTrue(service.cancel(5L, "admin"));
            assertEquals(ReferentialRelease.Status.CANCELLED, release.status);
            assertNull(release.held);
            org.mockito.ArgumentCaptor<List<RefPayloads.PriceDto>> rows = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(service.refApplyService).applyPrices(rows.capture());
            assertEquals("3700000000017", rows.getValue().get(0).productEan);
            verify(service.refExportService).referentialChanged();
        }
    }

    /** Every table domain is put back by the apply code a register runs. */
    @Test
    void everyTableDomainIsPutBackByTheRegistersApply() {
        ReferentialReleaseService service = service();
        RefApplyService apply = service.refApplyService;
        List<?> rows = new ArrayList<>();
        for (String domain : List.of("ENGINE_FEEDS", "MEASURE_UNITS", "PRODUCTS", "FAMILIES", "TOUCH_GROUPS", "PRICES",
                "COUPON_TYPES", "ARTICLE_RANGES", "ISLANDS", "CHECKOUT_POINTS", "TENDERS", "DOCUMENT_TEMPLATES",
                "PRINTABLE_DOCUMENTS", "PROVIDER_MESSAGES", "CASH_MENUS", "STORE", "CUSTOMERS", "CURRENCIES",
                "POSTAL_CODES", "POS_LISTS", "RETURN_TYPES", "PREPAID_CARD_TYPES")) {
            service.restore(domain, rows, null);
        }
        verifyApplied(apply, rows);
    }

    /**
     * Verifies that each table domain went through its apply method.
     *
     * @param apply the mocked apply service
     * @param rows the rows passed
     */
    @SuppressWarnings("unchecked")
    private void verifyApplied(RefApplyService apply, List<?> rows) {
        verify(apply).applyEngineFeeds((List<RefPayloads.EngineFeedDto>) rows);
        verify(apply).applyMeasureUnits((List<RefPayloads.MeasureUnitDto>) rows);
        verify(apply).applyProducts((List<RefPayloads.ProductDto>) rows);
        verify(apply).applyFamilies((List<RefPayloads.FamilyDto>) rows);
        verify(apply).applyTouchGroups((List<RefPayloads.TouchGroupDto>) rows);
        verify(apply).applyPrices((List<RefPayloads.PriceDto>) rows);
        verify(apply).applyCouponTypes((List<RefPayloads.CouponTypeDto>) rows);
        verify(apply).applyArticleBarcodeRanges((List<RefPayloads.ArticleBarcodeRangeDto>) rows);
        verify(apply).applyCheckoutIslands((List<RefPayloads.CheckoutIslandDto>) rows);
        verify(apply).applyCheckoutPoints((List<RefPayloads.CheckoutPointDto>) rows);
        verify(apply).applyTenders((List<RefPayloads.TenderDefinitionDto>) rows);
        verify(apply).applyDocumentTemplates((List<RefPayloads.DocumentTemplateDto>) rows);
        verify(apply).applyPrintableDocuments((List<RefPayloads.PrintableDocumentDto>) rows);
        verify(apply).applyProviderMessages((List<RefPayloads.ProviderMessageDto>) rows);
        verify(apply).applyCashMenus((List<RefPayloads.CashMenuDto>) rows);
        verify(apply).applyStore((List<RefPayloads.StoreDto>) rows);
        verify(apply).applyCustomers((List<RefPayloads.CustomerDto>) rows);
        verify(apply).applyCurrencies((List<RefPayloads.CurrencyDto>) rows);
        verify(apply).applyPostalCodes((List<RefPayloads.PostalCodeDto>) rows);
        verify(apply).applyPosLists((List<RefPayloads.PosListDto>) rows);
        verify(apply).applyReturnTypes((List<RefPayloads.ReturnTypeDto>) rows);
        verify(apply).applyPrepaidCardTypes((List<RefPayloads.PrepaidCardTypeDto>) rows);
    }

    /** The VAT put back is followed by the prices, which name their regime by number. */
    @Test
    @SuppressWarnings("unchecked")
    void theVatPutBackRelinksThePrices() {
        ReferentialReleaseService service = service();
        List<?> prices = List.of(price("1"));
        List<?> rates = List.of(new RefPayloads.VatRateDto());
        when(service.refExportService.liveRows("PRICES", null)).thenAnswer(invocation -> prices);
        service.restore("VAT_RATES", rates, null);
        verify(service.refApplyService).applyVatRates((List<RefPayloads.VatRateDto>) rates);
        verify(service.refApplyService).applyPrices((List<RefPayloads.PriceDto>) prices);
    }

    /**
     * The accounts put back deactivate those the held version lacks, but an
     * account the registers never receive stays active.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theAccountsPutBackKeepTheUndistributedOnes() {
        ReferentialReleaseService service = service();
        List<RefPayloads.EmployeeDto> held = List.of(account("jdupont"));
        when(service.refExportService.liveRows("EMPLOYEES", null)).thenAnswer(invocation -> List.of(account("new")));
        Employee distributed = new Employee();
        distributed.loginName = "jdupont";
        Employee created = new Employee();
        created.loginName = "new";
        Employee outside = new Employee();
        outside.loginName = "central";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("active", true)).thenReturn(List.of(distributed, created, outside));
            org.mockito.Mockito.doAnswer(invocation -> {
                distributed.active = true;
                created.active = false;
                outside.active = false;
                return null;
            }).when(service.refApplyService).applyEmployees(held);
            service.restore("EMPLOYEES", held, null);
            assertTrue(distributed.active);
            assertFalse(created.active);
            assertTrue(outside.active);
        }
    }

    /** The parameter tables and the image library are put back as captured. */
    @Test
    void theResolvedDomainsArePutBackFromTheirTables() throws Exception {
        ReferentialReleaseService service = service();
        JsonNode settings = service.objectMapper.readTree("{\"echelon\":["
                + "{\"level\":\"PDV\",\"echelonCode\":\"0123\",\"settingKey\":\"k\",\"settingValue\":\"v\","
                + "\"pendingValue\":\"w2\",\"effectiveDate\":\"2026-11-02\"},"
                + "{\"level\":\"ISLAND\",\"echelonCode\":\"I1\",\"settingKey\":\"k2\",\"settingValue\":null,"
                + "\"pendingValue\":null,\"effectiveDate\":null}],"
                + "\"local\":[{\"settingKey\":\"l\",\"settingValue\":\"w\"}]}");
        String usage = StoreImage.Usage.values()[0].name();
        JsonNode images = service.objectMapper.readTree("{\"images\":["
                + "{\"code\":\"LOGO\",\"label\":\"Logo\",\"usage\":\"" + usage + "\",\"level\":\"PDV\","
                + "\"echelonCode\":\"0123\",\"advantageCode\":null,\"imageData\":\"AAA\",\"active\":true},"
                + "{\"code\":\"VIS\",\"label\":\"Visuel\",\"usage\":\"" + usage + "\",\"level\":null,"
                + "\"echelonCode\":null,\"advantageCode\":\"ADV\",\"imageData\":null,\"active\":false}]}");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<EchelonSetting> echelon = mockConstruction(EchelonSetting.class);
                MockedConstruction<PosSetting> local = mockConstruction(PosSetting.class);
                MockedConstruction<StoreImage> library = mockConstruction(StoreImage.class)) {
            service.restore("SETTINGS", List.of(), settings);
            service.restore("STORE_IMAGES", List.of(), images);
            panache.verify(PanacheEntityBase::deleteAll, times(3));
            assertEquals(2, echelon.constructed().size());
            EchelonSetting first = echelon.constructed().get(0);
            assertEquals(EchelonLevel.PDV, first.level);
            assertEquals("0123", first.echelonCode);
            assertEquals("k", first.settingKey);
            assertEquals("v", first.settingValue);
            assertEquals("w2", first.pendingValue);
            assertEquals(LocalDate.of(2026, 11, 2), first.effectiveDate);
            assertNull(echelon.constructed().get(1).effectiveDate);
            assertNull(echelon.constructed().get(1).settingValue);
            assertNull(echelon.constructed().get(1).pendingValue);
            verify(first).persist();
            assertEquals("l", local.constructed().get(0).settingKey);
            assertEquals("w", local.constructed().get(0).settingValue);
            verify(service.posSettingsService).invalidate();
            StoreImage logo = library.constructed().get(0);
            assertEquals("LOGO", logo.code);
            assertEquals("Logo", logo.label);
            assertEquals(EchelonLevel.PDV, logo.level);
            assertEquals("AAA", logo.imageData);
            assertTrue(logo.active);
            StoreImage visual = library.constructed().get(1);
            assertNull(visual.level);
            assertNull(visual.echelonCode);
            assertEquals("ADV", visual.advantageCode);
            assertFalse(visual.active);
            verify(visual).persist();
        }
    }

    /** An unknown domain cannot be put back. */
    @Test
    void anUnknownDomainCannotBePutBack() {
        ReferentialReleaseService service = service();
        assertThrows(IllegalArgumentException.class, () -> service.restore("NOPE", List.of(), null));
    }

    /** An optional text field is absent, null or a value. */
    @Test
    void anOptionalTextIsRead() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"a\":null,\"b\":\"x\"}");
        assertNull(ReferentialReleaseService.text(node, "missing"));
        assertNull(ReferentialReleaseService.text(node, "a"));
        assertEquals("x", ReferentialReleaseService.text(node, "b"));
    }

    /** The screen lists the pending releases and tells whether the tables already differ. */
    @Test
    void thePendingReleasesTellTheirDifference() {
        ReferentialReleaseService service = service();
        LocalDateTime at = LocalDateTime.of(2026, 11, 1, 6, 0);
        ReferentialRelease prices = release(service, "PRICES", at, Map.of("", List.of()), null);
        ReferentialRelease tenders = release(service, "TENDERS", at, Map.of("", List.of()), null);
        tenders.complete = true;
        when(service.refExportService.fingerprintOf(any())).thenReturn("held");
        when(service.refExportService.liveFingerprint("PRICES", null)).thenReturn("moved");
        when(service.refExportService.liveFingerprint("TENDERS", null)).thenReturn("held");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(PENDING, ReferentialRelease.Status.PENDING))
                    .thenReturn(List.of(prices, tenders));
            List<ReferentialReleaseService.PendingRow> rows = service.pending();
            assertEquals(2, rows.size());
            assertEquals("PRICES", rows.get(0).domain());
            assertEquals("01/11/2026 06:00", rows.get(0).effective());
            assertEquals("25/10/2026 09:30", rows.get(0).programmed());
            assertEquals("admin", rows.get(0).programmedBy());
            assertEquals("admin/articles/save", rows.get(0).origin());
            assertEquals(5L, rows.get(0).id());
            assertTrue(rows.get(0).divergent());
            assertFalse(rows.get(0).complete());
            assertFalse(rows.get(1).divergent());
            assertTrue(rows.get(1).complete());
        }
    }

    /** The history shows who closed each release and when, blank when its date came. */
    @Test
    @SuppressWarnings("unchecked")
    void theHistoryShowsTheClosedReleases() {
        ReferentialReleaseService service = service();
        ReferentialRelease cancelled = new ReferentialRelease();
        cancelled.domain = "PRICES";
        cancelled.effectiveAt = LocalDateTime.of(2026, 11, 1, 6, 0);
        cancelled.status = ReferentialRelease.Status.CANCELLED;
        cancelled.closedAt = LocalDateTime.of(2026, 10, 26, 8, 15);
        cancelled.closedBy = "admin";
        ReferentialRelease released = new ReferentialRelease();
        released.domain = "TENDERS";
        released.effectiveAt = LocalDateTime.of(2026, 10, 30, 6, 0);
        released.status = ReferentialRelease.Status.RELEASED;
        PanacheQuery<Object> ordered = mock(PanacheQuery.class);
        PanacheQuery<Object> page = mock(PanacheQuery.class);
        when(ordered.page(0, ReferentialReleaseService.HISTORY_SIZE)).thenReturn(page);
        when(page.list()).thenReturn(List.of(cancelled, released));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("status <> ?1 order by closedAt desc, id desc",
                    ReferentialRelease.Status.PENDING)).thenReturn(ordered);
            List<ReferentialReleaseService.HistoryRow> rows = service.history();
            assertEquals(new ReferentialReleaseService.HistoryRow("PRICES", "01/11/2026 06:00", "CANCELLED",
                    "26/10/2026 08:15", "admin"), rows.get(0));
            assertEquals(new ReferentialReleaseService.HistoryRow("TENDERS", "30/10/2026 06:00", "RELEASED",
                    "", ""), rows.get(1));
        }
    }

    /**
     * The dated parameters are listed through the echelon engine, brought into
     * effect now or cancelled — the stamp moves only when the engine found a
     * programmed value (both arms of each action).
     */
    @Test
    void theDatedParametersAreListedAdvancedOrCancelled() {
        ReferentialReleaseService service = service();
        EchelonSetting dated = new EchelonSetting();
        dated.effectiveDate = LocalDate.now().plusDays(3);
        when(service.echelonSettings.programmed()).thenReturn(List.of(dated));
        when(service.echelonSettings.advance(1L)).thenReturn(false);
        when(service.echelonSettings.cancel(1L)).thenReturn(false);
        when(service.echelonSettings.advance(9L)).thenReturn(true);
        when(service.echelonSettings.cancel(9L)).thenReturn(true);
        assertEquals(List.of(dated), service.datedSettings());
        assertFalse(service.advanceSetting(1L));
        assertFalse(service.cancelSetting(1L));
        verify(service.refExportService, never()).referentialChanged();
        verify(service.posSettingsService, never()).invalidate();
        assertTrue(service.advanceSetting(9L));
        assertTrue(service.cancelSetting(9L));
        verify(service.echelonSettings).advance(9L);
        verify(service.echelonSettings).cancel(9L);
        verify(service.posSettingsService, times(2)).invalidate();
        verify(service.refExportService, times(2)).referentialChanged();
    }

    /** The held rows read back carry the payload type of their domain. */
    @Test
    void theHeldRowsCarryTheirPayloadType() {
        ReferentialReleaseService service = service();
        ReferentialRelease release = release(service, "EMPLOYEES", LocalDateTime.now().plusDays(1),
                Map.of("", List.of(account("jdupont"))), null);
        Object row = service.read(release).scopes().get("").get(0);
        assertEquals(RefPayloads.EmployeeDto.class, row.getClass());
        assertEquals(RefPayloads.DOMAINS.size(), ReferentialReleaseService.DTO_TYPES.size());
        for (String domain : RefPayloads.DOMAINS) {
            assertNotNull(ReferentialReleaseService.DTO_TYPES.get(domain), domain);
        }
    }

    /**
     * Inside a transaction, the held versions are dropped now and again once it
     * is over, and the stamp moves on a commit only.
     */
    @Test
    void insideATransactionTheChangeIsRepeatedOnceItIsOver() throws Exception {
        ReferentialReleaseService service = service();
        service.transactions = mock(jakarta.transaction.TransactionSynchronizationRegistry.class);
        when(service.transactions.getTransactionKey()).thenReturn(new Object());
        service.changed(true);
        service.changed(false);
        org.mockito.ArgumentCaptor<jakarta.transaction.Synchronization> callbacks =
                org.mockito.ArgumentCaptor.forClass(jakarta.transaction.Synchronization.class);
        verify(service.transactions, times(2)).registerInterposedSynchronization(callbacks.capture());
        verify(service.refExportService, never()).referentialChanged();
        jakarta.transaction.Synchronization moving = callbacks.getAllValues().get(0);
        jakarta.transaction.Synchronization quiet = callbacks.getAllValues().get(1);
        moving.beforeCompletion();
        moving.afterCompletion(jakarta.transaction.Status.STATUS_ROLLEDBACK);
        verify(service.refExportService, never()).referentialChanged();
        moving.afterCompletion(jakarta.transaction.Status.STATUS_COMMITTED);
        quiet.afterCompletion(jakarta.transaction.Status.STATUS_COMMITTED);
        verify(service.refExportService, times(1)).referentialChanged();
        assertNull(heldCache(service));
    }

    /** Outside a transaction the stamp moves at once when asked, and only then. */
    @Test
    void outsideATransactionTheChangeIsImmediate() {
        ReferentialReleaseService service = service();
        service.transactions = mock(jakarta.transaction.TransactionSynchronizationRegistry.class);
        service.changed(false);
        verify(service.refExportService, never()).referentialChanged();
        service.changed(true);
        verify(service.refExportService).referentialChanged();
        verify(service.transactions, never()).registerInterposedSynchronization(any());
    }
}
