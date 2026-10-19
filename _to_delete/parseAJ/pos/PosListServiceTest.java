package com.intermarche.pos.service;

import com.intermarche.pos.domain.setting.PosList;
import com.intermarche.pos.domain.setting.PosListItem;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PosListService}, the resolver of the administered
 * lists ({@code BO-04-02-07}, {@code BO-04-03-12}).
 *
 * <p>The only collaborators are a mocked {@link PosSettingsService} — the
 * fallback road — and the static finder of {@link PosList}, intercepted with
 * {@code mockStatic}: an unstubbed kind answers null, which is exactly the
 * "no list distributed" case the fallback is written for.
 *
 * <p>Branch enumeration (both arms): each of the four resolvers by the
 * referential and by the fallback; the function filter matching, not matching
 * and absent; the four option markers of a return type plus an unknown one
 * and a null option string; an inactive entry; a container with no weight and
 * one with a negative weight; {@code returnType} on null, blank, a matching
 * label and an unknown one; and the EXPENSE / non-EXPENSE arms of the
 * justification fallback.
 */
class PosListServiceTest {

    /**
     * Builds a service whose settings source is a fresh mock.
     *
     * @return the service under test
     */
    private PosListService service() {
        PosListService service = new PosListService();
        service.posSettingsService = mock(PosSettingsService.class);
        return service;
    }

    /**
     * Builds a list carrying the given entries.
     *
     * @param kind what the list drives
     * @param functions the functions it serves, or null for all
     * @param items the entries
     * @return the list
     */
    private PosList list(PosList.Kind kind, String functions, PosListItem... items) {
        PosList posList = new PosList();
        posList.code = "L";
        posList.label = "Liste";
        posList.kind = kind;
        posList.functions = functions;
        posList.active = true;
        for (PosListItem item : items) {
            item.posList = posList;
            posList.items.add(item);
        }
        return posList;
    }

    /**
     * Builds an active entry carrying a code and a label.
     *
     * @param code the entry code
     * @param label the entry label
     * @return the entry
     */
    private PosListItem item(String code, String label) {
        PosListItem item = new PosListItem();
        item.code = code;
        item.label = label;
        item.active = true;
        return item;
    }

    /**
     * BO-04-02-07: a distributed service-request list answers, severity
     * included, and an inactive entry is left out.
     */
    @Test
    void serviceRequestTypesReadsTheReferential() {
        PosListService service = service();
        PosListItem kept = item("ANNUL", "Annulation");
        kept.severity = 3;
        PosListItem silent = item("VIEUX", "Motif retiré");
        silent.active = false;
        try (MockedStatic<PosList> lists = mockStatic(PosList.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.SERVICE_REQUEST))
                    .thenReturn(List.of(list(PosList.Kind.SERVICE_REQUEST, null, kept, silent)));
            List<PosListService.ServiceRequestType> types = service.serviceRequestTypes();
            assertEquals(1, types.size());
            assertEquals("ANNUL", types.get(0).code());
            assertEquals("Annulation", types.get(0).label());
            assertEquals(3, types.get(0).severity());
        }
    }

    /**
     * BO-04-02-07: with nothing distributed, the semicolon setting answers,
     * each reason being its own code and carrying no severity; blank entries
     * are dropped.
     */
    @Test
    void serviceRequestTypesFallsBackOnTheSetting() {
        PosListService service = service();
        when(service.posSettingsService.supervisorServiceReasons())
                .thenReturn("Annulation; ;Prix / étiquette;");
        try (MockedStatic<PosList> lists = mockStatic(PosList.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.SERVICE_REQUEST))
                    .thenReturn(List.of());
            List<PosListService.ServiceRequestType> types = service.serviceRequestTypes();
            assertEquals(2, types.size());
            assertEquals("Annulation", types.get(0).code());
            assertEquals(0, types.get(0).severity());
            assertEquals("Prix / étiquette", types.get(1).label());
        }
    }

    /**
     * BO-04-03-12: a justification list bound to EXPENSE answers that
     * function, and a list bound to another one is not mixed in.
     */
    @Test
    void justificationsFilterOnTheFunction() {
        PosListService service = service();
        PosList expense = list(PosList.Kind.JUSTIFICATION, "EXPENSE;DEPOSIT",
                item("TIMBRES", "Achat de timbres"));
        PosList cancel = list(PosList.Kind.JUSTIFICATION, "TICKET_CANCEL",
                item("ERREUR", "Erreur de saisie"));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.JUSTIFICATION))
                    .thenReturn(List.of(expense, cancel));
            assertEquals(List.of("Achat de timbres"),
                    service.justifications(PosListService.FUNCTION_EXPENSE));
            assertEquals(List.of("Erreur de saisie"), service.justifications("TICKET_CANCEL"));
        }
    }

    /**
     * BO-04-03-12: lists exist but none serves the function asked about, so
     * nothing is offered — and the EXPENSE setting is NOT answered to another
     * gesture.
     */
    @Test
    void justificationsEmptyWhenNoListServesTheFunction() {
        PosListService service = service();
        PosList expense = list(PosList.Kind.JUSTIFICATION, "EXPENSE",
                item("TIMBRES", "Achat de timbres"));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.JUSTIFICATION))
                    .thenReturn(List.of(expense));
            assertTrue(service.justifications("WITHDRAWAL").isEmpty());
        }
    }

    /**
     * BO-04-03-12: with nothing distributed, EXPENSE falls back on
     * {@code cash.movement-reasons} and any other function offers nothing.
     */
    @Test
    void justificationsFallBackOnTheExpenseSettingOnly() {
        PosListService service = service();
        when(service.posSettingsService.cashMovementReasons())
                .thenReturn(List.of("Achat de timbres"));
        try (MockedStatic<PosList> lists = mockStatic(PosList.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.JUSTIFICATION))
                    .thenReturn(List.of());
            assertEquals(List.of("Achat de timbres"),
                    service.justifications(PosListService.FUNCTION_EXPENSE));
            assertTrue(service.justifications("WITHDRAWAL").isEmpty());
            assertTrue(service.justifications(null).isEmpty());
        }
    }

    /**
     * LC-05-04-01: a distributed return-type list reads the four option
     * markers, ignores an unknown one and accepts an entry carrying none.
     */
    @Test
    void returnTypesReadTheOptionMarkers() {
        PosListService service = service();
        PosListItem full = item("SANS", "Sans ticket");
        full.options = "TICKET,NOENDORSE,NOORIGIN,PRICE,INCONNU";
        PosListItem bare = item("ECHANGE", "Échange");
        try (MockedStatic<PosList> lists = mockStatic(PosList.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_TYPE))
                    .thenReturn(List.of(list(PosList.Kind.RETURN_TYPE, null, full, bare)));
            List<PosSettingsService.ReturnType> types = service.returnTypes();
            assertEquals(2, types.size());
            assertTrue(types.get(0).dedicatedTicket());
            assertTrue(types.get(0).endorsementExempt());
            assertTrue(types.get(0).originOptional());
            assertTrue(types.get(0).priceConfirmed());
            assertEquals("Échange", types.get(1).label());
            assertTrue(!types.get(1).dedicatedTicket() && !types.get(1).endorsementExempt()
                    && !types.get(1).originOptional() && !types.get(1).priceConfirmed());
        }
    }

    /**
     * LC-05-04-01: with nothing distributed, the administered string answers.
     */
    @Test
    void returnTypesFallBackOnTheSetting() {
        PosListService service = service();
        List<PosSettingsService.ReturnType> administered =
                List.of(new PosSettingsService.ReturnType("Échange", false));
        when(service.posSettingsService.returnTypes()).thenReturn(administered);
        try (MockedStatic<PosList> lists = mockStatic(PosList.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_TYPE))
                    .thenReturn(List.of());
            assertEquals(administered, service.returnTypes());
        }
    }

    /**
     * BO-03-13-06/-08/-10: a type is resolved by its label, case-insensitively;
     * a null, a blank and an unknown label resolve to nothing.
     */
    @Test
    void returnTypeResolvesByLabel() {
        PosListService service = service();
        try (MockedStatic<PosList> lists = mockStatic(PosList.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.RETURN_TYPE))
                    .thenReturn(List.of(list(PosList.Kind.RETURN_TYPE, null,
                            item("ECHANGE", "Échange"))));
            assertEquals("Échange", service.returnType("  échange ").label());
            assertNull(service.returnType("Inconnu"));
            assertNull(service.returnType(null));
            assertNull(service.returnType("   "));
        }
    }

    /**
     * LC-14-01-04: a distributed container list answers its weights, an entry
     * carrying none or a negative one being dropped.
     */
    @Test
    void tareContainersReadTheReferential() {
        PosListService service = service();
        PosListItem kept = item("BQS", "Barquette S");
        kept.numericValue = new BigDecimal("0.035");
        PosListItem weightless = item("RIEN", "Sans poids");
        PosListItem negative = item("NEG", "Poids négatif");
        negative.numericValue = new BigDecimal("-0.010");
        try (MockedStatic<PosList> lists = mockStatic(PosList.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.TARE_CONTAINER))
                    .thenReturn(List.of(list(PosList.Kind.TARE_CONTAINER, null,
                            kept, weightless, negative)));
            List<PosSettingsService.TareContainer> containers = service.tareContainers();
            assertEquals(1, containers.size());
            assertEquals("BQS", containers.get(0).code());
            assertEquals(new BigDecimal("0.035"), containers.get(0).tareKg());
        }
    }

    /**
     * LC-14-01-04: with nothing distributed, the administered string answers.
     */
    @Test
    void tareContainersFallBackOnTheSetting() {
        PosListService service = service();
        List<PosSettingsService.TareContainer> administered = List.of(
                new PosSettingsService.TareContainer("SACPAP", "Sac kraft", new BigDecimal("0.010")));
        when(service.posSettingsService.tareContainers()).thenReturn(administered);
        try (MockedStatic<PosList> lists = mockStatic(PosList.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.TARE_CONTAINER))
                    .thenReturn(List.of());
            assertEquals(administered, service.tareContainers());
        }
    }

    /**
     * A finder answering null — the kind was never asked of the database —
     * takes the same road as an empty one: the fallback.
     */
    @Test
    void aNullFinderResultFallsBack() {
        PosListService service = service();
        when(service.posSettingsService.supervisorServiceReasons()).thenReturn("Autre");
        try (MockedStatic<PosList> lists = mockStatic(PosList.class)) {
            lists.when(() -> PosList.findActiveByKind(PosList.Kind.SERVICE_REQUEST))
                    .thenReturn(null);
            assertEquals(1, service.serviceRequestTypes().size());
        }
    }
}
