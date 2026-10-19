package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.service.CashMovementService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.auth.AuthState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashMovementResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, a
 * {@link CashSessionService}, a {@link CashMovementService}, a
 * {@link PosSettingsService}, an {@link EndorsementService} and one Qute
 * {@link Template}. Every collaborator is a Mockito mock; {@code PosState} is a
 * mock carrying a real {@link AuthState} so {@code operatorId} /
 * {@code operatorBadgeId} read without a null, and the sole static call
 * ({@code Employee.findById}) is intercepted with
 * {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase}. No
 * database and no Quarkus context are booted.
 * <p>
 * Branch enumeration (both arms of every decision, 100%): the page's eight error
 * codes plus the {@code ok != null} saved flag; {@code rejectionOf}'s three
 * refusals (a withdrawal and a coin supply, which belong to their own screens; an
 * expense with no reason; a down-payment with no order reference) and the
 * declaration that passes them all; {@code record}'s training gate,
 * {@code session == null}, {@code type == null} (via a null and an unknown
 * name), {@code requiresEndorsement} with the endorsement-resolved and
 * endorsement-refused arms, the {@code operatorId != null} cashier ternary and
 * the {@code recorded == null} arm; {@code resolveEndorsement}'s supervisor,
 * authorized and refused roads; and every {@code parseAmount} case (null,
 * blank, valid, invalid).
 */
class CashMovementResourceTest {

    /**
     * Builds a resource with fresh mocks and a real {@link AuthState} carrying a
     * logged operator (id 7, badge {@code M1}).
     *
     * @return the wired resource
     */
    private CashMovementResource newResource() {
        CashMovementResource resource = new CashMovementResource();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        resource.i18n = TestMessages.french();
        resource.state = mock(PosState.class);
        AuthState auth = new AuthState();
        auth.operatorId = 7L;
        auth.operatorBadgeId = "M1";
        resource.state.auth = auth;
        resource.cashSessionService = mock(CashSessionService.class);
        resource.cashMovementService = mock(CashMovementService.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        // The administered lists (BO-04-03-12), consulted for the justifications.
        resource.posListService = mock(com.intermarche.pos.service.PosListService.class);
        resource.endorsementService = mock(EndorsementService.class);
        resource.cashMovement = mock(Template.class);
        resource.ticketPrinterService =
                mock(com.intermarche.pos.ui.hardware.TicketPrinterService.class);
        resource.ticketNumberService =
                mock(com.intermarche.pos.service.TicketNumberService.class);
        return resource;
    }

    /**
     * Stubs the five-link {@code cashMovement} template chain with permissive
     * matchers on the reasons, threshold, saved and error data.
     *
     * @param resource the resource whose template is stubbed
     * @return the chain, the last element being the rendered view
     */
    private TemplateInstance[] stubPageChain(CashMovementResource resource) {
        when(resource.posListService.justifications(
                com.intermarche.pos.service.PosListService.FUNCTION_EXPENSE)).thenReturn(List.of("Coffre"));
        when(resource.posSettingsService.cashMovementTenders()).thenReturn(List.of());
        when(resource.posSettingsService.cashMovementEndorsementThreshold())
                .thenReturn(new BigDecimal("100.00"));
        TemplateInstance ti1 = mock(TemplateInstance.class);
        TemplateInstance ti2 = mock(TemplateInstance.class);
        TemplateInstance ti3 = mock(TemplateInstance.class);
        TemplateInstance ti4 = mock(TemplateInstance.class);
        TemplateInstance ti5 = mock(TemplateInstance.class);
        TemplateInstance tiTenders = mock(TemplateInstance.class);
        when(resource.cashMovement.data("state", resource.state)).thenReturn(ti1);
        when(ti1.data(eq("reasons"), any())).thenReturn(ti2);
        when(ti2.data(eq("tenders"), any())).thenReturn(tiTenders);
        when(tiTenders.data(eq("threshold"), any())).thenReturn(ti3);
        when(ti3.data(eq("saved"), any())).thenReturn(ti4);
        when(ti4.data(eq("error"), any())).thenReturn(ti5);
        return new TemplateInstance[]{ti1, ti2, ti3, ti4, ti5};
    }

    /**
     * Asserts the given response is a 303 redirect to the expected location.
     *
     * @param response the response under test
     * @param location the expected {@code Location} header value
     */
    private void assertRedirect(Response response, String location) {
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals(location, response.getLocation().toString());
    }

    /**
     * Builds the template chain with the given administered lists, asserting
     * nothing, so a caller can check WHICH values reach the page.
     *
     * @param resource the resource whose template is stubbed
     * @param reasons the administered movement reasons
     * @param tenders the administered movement tenders
     * @return the two links carrying the reasons and the tenders
     */
    private TemplateInstance[] stubChainWith(CashMovementResource resource,
                                             List<String> reasons, List<String> tenders) {
        when(resource.posListService.justifications(
                com.intermarche.pos.service.PosListService.FUNCTION_EXPENSE)).thenReturn(reasons);
        when(resource.posSettingsService.cashMovementTenders()).thenReturn(tenders);
        when(resource.posSettingsService.cashMovementEndorsementThreshold())
                .thenReturn(new BigDecimal("100.00"));
        TemplateInstance ti1 = mock(TemplateInstance.class);
        TemplateInstance tiReasons = mock(TemplateInstance.class);
        TemplateInstance tiTenders = mock(TemplateInstance.class);
        TemplateInstance ti3 = mock(TemplateInstance.class);
        TemplateInstance ti4 = mock(TemplateInstance.class);
        TemplateInstance ti5 = mock(TemplateInstance.class);
        when(resource.cashMovement.data("state", resource.state)).thenReturn(ti1);
        when(ti1.data(eq("reasons"), any())).thenReturn(tiReasons);
        when(tiReasons.data(eq("tenders"), any())).thenReturn(tiTenders);
        when(tiTenders.data(eq("threshold"), any())).thenReturn(ti3);
        when(ti3.data(eq("saved"), any())).thenReturn(ti4);
        when(ti4.data(eq("error"), any())).thenReturn(ti5);
        return new TemplateInstance[]{ti1, tiReasons};
    }

    /**
     * The page hands the template the ADMINISTERED movement reasons, whatever
     * they are (BO-04-03-10): two distinct administered lists reach the page
     * as themselves, which no hard-coded list could satisfy.
     */
    @Test
    void pageHandsTheTemplateTheAdministeredReasons() {
        CashMovementResource first = newResource();
        TemplateInstance[] a = stubChainWith(first, List.of("Coffre", "Erreur"), List.of());
        first.cashMovementPage(null, null);
        verify(a[0]).data("reasons", List.of("Coffre", "Erreur"));
        CashMovementResource second = newResource();
        TemplateInstance[] b = stubChainWith(second, List.of("Banque"), List.of());
        second.cashMovementPage(null, null);
        verify(b[0]).data("reasons", List.of("Banque"));
    }

    /**
     * The page hands the template the ADMINISTERED movement tenders, whatever
     * they are (BO-03-02-20), on two distinct administered lists.
     */
    @Test
    void pageHandsTheTemplateTheAdministeredTenders() {
        CashMovementResource first = newResource();
        TemplateInstance[] a = stubChainWith(first, List.of(), List.of("ESPECES", "CHEQUE"));
        first.cashMovementPage(null, null);
        verify(a[1]).data("tenders", List.of("ESPECES", "CHEQUE"));
        CashMovementResource second = newResource();
        TemplateInstance[] b = stubChainWith(second, List.of(), List.of("CB"));
        second.cashMovementPage(null, null);
        verify(b[1]).data("tenders", List.of("CB"));
    }

    // --- cashMovementPage ---

    /**
     * The page renders the no-session message (first {@code equals} true arm)
     * and the not-saved flag (ok null arm).
     */
    @Test
    void pageNoSessionMessage() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("no-session", null));
        verify(chain[2]).data("saved", false);
        verify(chain[3]).data("error", "AUCUNE SESSION OUVERTE");
    }

    /**
     * The page renders the bad-type message (second {@code equals} true arm).
     */
    @Test
    void pageBadTypeMessage() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("bad-type", null));
        verify(chain[3]).data("error", "TYPE DE MOUVEMENT INVALIDE");
    }

    /**
     * The page renders the own-screen message: a withdrawal or a coin supply posted
     * here is sent back to its own screen, where it is counted.
     */
    @Test
    void pageOwnScreenMessage() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("own-screen", null));
        verify(chain[3]).data("error", "PRÉLÈVEMENT ET APPORT : ÉCRANS DÉDIÉS");
    }

    /**
     * The page renders the missing-reason message of a refused expense.
     */
    @Test
    void pageMissingReasonMessage() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("reason", null));
        verify(chain[3]).data("error", "MOTIF OBLIGATOIRE");
    }

    /**
     * The page renders the missing-reference message of a refused down-payment.
     */
    @Test
    void pageMissingReferenceMessage() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("reference", null));
        verify(chain[3]).data("error", "RÉFÉRENCE DE COMMANDE OBLIGATOIRE");
    }

    /**
     * The page renders the endorsement message (third {@code equals} true arm).
     */
    @Test
    void pageEndorsementMessage() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("endorsement", null));
        verify(chain[3]).data("error", "AVAL MANAGER REFUSÉ OU MANQUANT");
    }

    /**
     * The page renders the training message (fourth {@code equals} true arm).
     */
    @Test
    void pageTrainingMessage() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("training", null));
        verify(chain[3]).data("error", "INDISPONIBLE EN FORMATION");
    }

    /**
     * The page renders a null message for an unknown code (all {@code equals}
     * false arm) and the saved flag when {@code ok} is present (ok non-null arm).
     */
    @Test
    void pageUnknownErrorAndSavedFlag() {
        CashMovementResource resource = newResource();
        TemplateInstance[] chain = stubPageChain(resource);
        assertSame(chain[4], resource.cashMovementPage("bogus", "1"));
        verify(chain[2]).data("saved", true);
        verify(chain[3]).data("error", (String) null);
    }

    // --- record ---

    /**
     * A record in training mode is blocked with the training redirect
     * ({@code trainingMode} true arm); nothing is looked up or written.
     */
    @Test
    void recordTrainingModeBlocked() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = true;
        assertRedirect(resource.record("EXPENSE", "10", "Timbres", null, null),
                "/cash-movement?error=training");
        verifyNoInteractions(resource.cashSessionService);
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A record with no open session redirects with {@code no-session}
     * ({@code session == null} true arm).
     */
    @Test
    void recordNoSessionRedirects() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        assertRedirect(resource.record("EXPENSE", "10", "Timbres", null, null),
                "/cash-movement?error=no-session");
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A record with a null type name redirects with {@code bad-type}
     * ({@code parseType} value-null arm, so {@code type == null} true arm).
     */
    @Test
    void recordNullTypeRedirectsBadType() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record(null, "10", "Timbres", null, null),
                "/cash-movement?error=bad-type");
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A record with an unknown type name redirects with {@code bad-type}
     * ({@code parseType} {@code IllegalArgumentException} arm).
     */
    @Test
    void recordUnknownTypeRedirectsBadType() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("BOGUS", "10", "Timbres", null, null),
                "/cash-movement?error=bad-type");
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A below-threshold record with a logged operator writes the movement and
     * redirects with {@code ok=1} ({@code requiresEndorsement} false arm,
     * {@code operatorId != null} true arm, {@code recorded == null} false arm);
     * the valid comma amount exercises the {@code parseAmount} success branch and
     * the resolved cashier is carried to the service.
     */
    @Test
    void recordBelowThresholdWritesAndConfirms() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.EXPENSE), eq(new BigDecimal("30.00")),
                eq("Timbres"), eq(null), eq(null), eq(null), eq(null))).thenReturn(recorded);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("EXPENSE", "30,00", "Timbres", null, null),
                    "/cash-movement?ok=1");
        }
        verify(resource.state).touch();
    }

    /**
     * A movement carrying a tender (BO-03-02-20) passes it through to the
     * service as the {@code paymentMethod}, so a cheque withdrawal does not move
     * the cash theoretical; a blank tender would land as null (cash) instead.
     */
    @Test
    void recordPassesTenderToService() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.EXPENSE), any(),
                eq("Timbres"), eq(null), eq("CHEQUE"), eq(null), eq(null))).thenReturn(recorded);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("EXPENSE", "10", "Timbres", null, "CHEQUE"),
                    "/cash-movement?ok=1");
        }
        verify(resource.cashMovementService).record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.EXPENSE), any(), eq("Timbres"), eq(null),
                eq("CHEQUE"), eq(null), eq(null));
    }

    /**
     * A blank tender lands as a null payment method (BO-03-02-20, the
     * non-null-but-blank leg of the guard) — the movement then concerns the cash.
     */
    @Test
    void recordBlankTenderBecomesNullPaymentMethod() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.EXPENSE), any(),
                eq("Timbres"), eq(null), eq(null), eq(null), eq(null))).thenReturn(recorded);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("EXPENSE", "10", "Timbres", null, "   "),
                    "/cash-movement?ok=1");
        }
        verify(resource.cashMovementService).record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.EXPENSE), any(), eq("Timbres"), eq(null),
                eq(null), eq(null), eq(null));
    }

    /**
     * An above-threshold record self-endorsed by a connected supervisor writes
     * with the operator's own badge ({@code requiresEndorsement} true arm,
     * {@code resolveEndorsement} supervisor arm, {@code endorsedBy != null});
     * the null amount exercises the {@code parseAmount} null branch.
     */
    @Test
    void recordAboveThresholdSelfEndorsedWrites() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(true);
        when(resource.cashMovementService.record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.EXPENSE), eq(BigDecimal.ZERO),
                eq("Timbres"), eq("M1"), eq(null), eq(null), eq(null))).thenReturn(recorded);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("EXPENSE", null, "Timbres", null, null),
                    "/cash-movement?ok=1");
        }
        verify(resource.endorsementService, never()).authorize(any(), any(), any());
    }

    /**
     * A movement the shared modal has authorized is written with the endorsing
     * badge ({@code performEndorsed} replaying the parked form); the blank
     * amount exercises the {@code parseAmount} blank branch.
     */
    @Test
    void recordAboveThresholdManagerAuthorizedWrites() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(false);
        when(resource.cashMovementService.record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.EXPENSE), eq(BigDecimal.ZERO),
                eq("Pharmacie"), eq("22222222"), eq(null), eq(null), eq(null))).thenReturn(recorded);
        java.util.Map<String, String> parked = new java.util.LinkedHashMap<>();
        parked.put("type", "EXPENSE");
        parked.put("amount", "   ");
        parked.put("reason", "Pharmacie");
        parked.put("paymentMethod", null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.performEndorsed(parked, "22222222"),
                    "/cash-movement?ok=1");
        }
    }

    /**
     * An above-threshold record with no supervisor logged PARKS the form and
     * opens the shared modal instead of writing: the screen redirects to itself,
     * the endorsement request carries the four typed fields and the return path,
     * and nothing reaches the movement service.
     */
    @Test
    void recordAboveThresholdParksTheFormAndAsksTheModal() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(false);
        assertRedirect(resource.record("EXPENSE", "150", "Timbres", null, null),
                "/cash-movement");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, String>> form = ArgumentCaptor.forClass(java.util.Map.class);
        verify(resource.endorsementService).requestAuthorization(eq(resource.state),
                eq("CASH_MOVEMENT"), form.capture(), eq("/cash-movement"));
        assertEquals("EXPENSE", form.getValue().get("type"));
        assertEquals("150", form.getValue().get("amount"));
        assertEquals("Timbres", form.getValue().get("reason"));
        assertEquals("", form.getValue().get("reference"));
        assertEquals("", form.getValue().get("paymentMethod"));
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A movement replayed WITHOUT an endorsing badge — the modal was dismissed
     * and the form reached {@code perform} anyway — is refused rather than
     * written ({@code endorsedBy == null} arm of the guard).
     */
    @Test
    void replayWithoutBadgeIsRefused() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        java.util.Map<String, String> parked = new java.util.LinkedHashMap<>();
        parked.put("type", "EXPENSE");
        parked.put("amount", "150");
        parked.put("reason", "Timbres");
        parked.put("paymentMethod", null);
        assertRedirect(resource.performEndorsed(parked, null),
                "/cash-movement?error=endorsement");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A record the service refuses (returns null) redirects with
     * {@code endorsement} ({@code recorded == null} true arm); a null operator id
     * exercises the {@code operatorId != null} false arm (no cashier resolved)
     * and the unparsable amount exercises the {@code parseAmount} catch branch.
     */
    @Test
    void recordServiceRefusalRedirects() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        resource.state.auth.operatorId = null;
        CashSession session = mock(CashSession.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.record(eq(session), eq(null),
                eq(CashMovement.MovementType.DECLARATION), eq(BigDecimal.ZERO),
                eq("Comptage"), eq(null), eq(null), eq(null), eq(null))).thenReturn(null);
        assertRedirect(resource.record("DECLARATION", "abc", "Comptage", null, null),
                "/cash-movement?error=endorsement");
        verify(resource.state).touch();
    }

    /**
     * An above-threshold record parked with a NULL amount, a NULL reason and a
     * PRESENT tender exercises the opposite arm of the parking ternaries:
     * {@code amountStr == null} true, {@code reason == null} true,
     * {@code reference == null} true and {@code paymentMethod == null} false — the
     * null fields land as empty strings and the present tender lands as itself in
     * the parked form. The type is a DECLARATION, the one movement that asks for
     * neither a reason nor a reference.
     */
    @Test
    void recordParksNullAmountNullReasonAndPresentTender() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(true);
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(false);
        assertRedirect(resource.record("DECLARATION", null, null, null, "CB"),
                "/cash-movement");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, String>> form = ArgumentCaptor.forClass(java.util.Map.class);
        verify(resource.endorsementService).requestAuthorization(eq(resource.state),
                eq("CASH_MOVEMENT"), form.capture(), eq("/cash-movement"));
        assertEquals("DECLARATION", form.getValue().get("type"));
        assertEquals("", form.getValue().get("amount"));
        assertEquals("", form.getValue().get("reason"));
        assertEquals("", form.getValue().get("reference"));
        assertEquals("CB", form.getValue().get("paymentMethod"));
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A WITHDRAWAL posted to this screen is refused: the prélèvement has its own
     * screen, where it is counted denomination by denomination and prints its
     * bordereau. The flat form here would record it without that count.
     */
    @Test
    void recordRefusesAWithdrawalWhichHasItsOwnScreen() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("WITHDRAWAL", "30", "Coffre", null, null),
                "/cash-movement?error=own-screen");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A DEPOSIT posted to this screen is refused for the same reason — the second
     * leg of the own-screen disjunction: the apport de monnaie is counted on its
     * own page.
     */
    @Test
    void recordRefusesADepositWhichHasItsOwnScreen() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("DEPOSIT", "30", "Apport", null, null),
                "/cash-movement?error=own-screen");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * An EXPENSE with NO reason is refused (null leg of the reason guard): cash
     * that left the drawer must say what it paid for.
     */
    @Test
    void recordRefusesAnExpenseWithoutAReason() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("EXPENSE", "30", null, null, null),
                "/cash-movement?error=reason");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * An EXPENSE whose reason is blank is refused too (isBlank leg of the reason
     * guard) — the empty entry of the administered list is not a reason.
     */
    @Test
    void recordRefusesAnExpenseWithABlankReason() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("EXPENSE", "30", "   ", null, null),
                "/cash-movement?error=reason");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A CUSTOMER_DEPOSIT with NO order reference is refused (null leg of the
     * reference guard): an advance the store cashes must name what it advances on.
     */
    @Test
    void recordRefusesADownPaymentWithoutAnOrderReference() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("CUSTOMER_DEPOSIT", "30", null, null, null),
                "/cash-movement?error=reference");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A CUSTOMER_DEPOSIT whose reference is blank is refused too (isBlank leg of
     * the reference guard).
     */
    @Test
    void recordRefusesADownPaymentWithABlankOrderReference() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.record("CUSTOMER_DEPOSIT", "30", null, "  ", null),
                "/cash-movement?error=reference");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A CUSTOMER_DEPOSIT is written through {@code recordAcompte} (EXP-85), which
     * carries the order reference in its own column: that column is what a later
     * recall of the order reads to deduct what was already collected. The generic
     * writer is not used, which would have left the reference as prose.
     */
    @Test
    void recordADownPaymentThroughTheAcompteWriter() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.recordAcompte(session, cashier,
                new BigDecimal("30"), " CMD42 ")).thenReturn(recorded);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("CUSTOMER_DEPOSIT", "30", null, " CMD42 ", null),
                    "/cash-movement?ok=1");
        }
        verify(resource.cashMovementService).recordAcompte(session, cashier,
                new BigDecimal("30"), " CMD42 ");
        verify(resource.cashMovementService, never()).record(any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    /**
     * A down-payment the acompte writer refuses (it returns null on an amount or a
     * reference it will not write) redirects rather than confirming, and prints
     * nothing.
     */
    @Test
    void recordADownPaymentTheWriterRefuses() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.recordAcompte(any(), any(), any(), any()))
                .thenReturn(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(mock(Employee.class));
            assertRedirect(resource.record("CUSTOMER_DEPOSIT", "0", null, "CMD42", null),
                    "/cash-movement?error=endorsement");
        }
        verifyNoInteractions(resource.ticketPrinterService);
    }

    /**
     * Every movement recorded here PRINTS its justificatif, carrying the type's
     * wording, the amount and the reason: these three movements used to leave the
     * drawer with no paper to file beside the receipts.
     */
    @Test
    void recordPrintsTheJustificatif() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        // The slip states the reason the movement WAS RECORDED with, read back off
        // the written movement rather than off the form.
        recorded.reason = "Timbres";
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.record(any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(recorded);
        when(resource.ticketNumberService.getTerminalId()).thenReturn("C01");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("EXPENSE", "12,50", "Timbres", null, null),
                    "/cash-movement?ok=1");
        }
        verify(resource.ticketPrinterService).printMovementTicket("Dépense",
                new BigDecimal("12.50"), "Timbres", null, null, "C01");
    }

    /**
     * A DECLARATION asks for neither a reason nor a reference (both guards' type
     * arms false) and is recorded as it stands, with its own wording on the
     * justificatif.
     */
    @Test
    void recordADeclarationNeedsNeitherReasonNorReference() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        CashMovement recorded = mock(CashMovement.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(session);
        when(resource.cashMovementService.requiresEndorsement(any())).thenReturn(false);
        when(resource.cashMovementService.record(any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(recorded);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertRedirect(resource.record("DECLARATION", "200", null, null, null),
                    "/cash-movement?ok=1");
        }
        verify(resource.cashMovementService).record(eq(session), eq(cashier),
                eq(CashMovement.MovementType.DECLARATION), eq(new BigDecimal("200")),
                eq(null), eq(null), eq(null), eq(null), eq(null));
        verify(resource.ticketPrinterService).printMovementTicket(eq("Déclaration (comptage)"),
                any(), eq(null), eq(null), any(), any());
    }

    /**
     * A parked movement replayed while the register slipped into training mode
     * is blocked with the training redirect ({@code perform}'s
     * {@code trainingMode} true arm, reachable only through
     * {@code performEndorsed}); nothing is looked up or written.
     */
    @Test
    void performEndorsedTrainingModeBlocked() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = true;
        java.util.Map<String, String> parked = new java.util.LinkedHashMap<>();
        parked.put("type", "WITHDRAWAL");
        parked.put("amount", "150");
        parked.put("reason", "Coffre");
        parked.put("paymentMethod", null);
        assertRedirect(resource.performEndorsed(parked, "22222222"),
                "/cash-movement?error=training");
        verifyNoInteractions(resource.cashSessionService);
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A parked movement replayed after the session closed is refused with the
     * no-session redirect ({@code perform}'s {@code session == null} true arm);
     * nothing reaches the movement service.
     */
    @Test
    void performEndorsedNoSessionRedirects() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        java.util.Map<String, String> parked = new java.util.LinkedHashMap<>();
        parked.put("type", "WITHDRAWAL");
        parked.put("amount", "150");
        parked.put("reason", "Coffre");
        parked.put("paymentMethod", null);
        assertRedirect(resource.performEndorsed(parked, "22222222"),
                "/cash-movement?error=no-session");
        verifyNoInteractions(resource.cashMovementService);
    }

    /**
     * A parked movement whose type name no longer parses is refused with the
     * bad-type redirect ({@code perform}'s {@code type == null} true arm, via a
     * null parked type); nothing reaches the movement service.
     */
    @Test
    void performEndorsedBadTypeRedirects() {
        CashMovementResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        java.util.Map<String, String> parked = new java.util.LinkedHashMap<>();
        parked.put("type", null);
        parked.put("amount", "150");
        parked.put("reason", "Coffre");
        parked.put("paymentMethod", null);
        assertRedirect(resource.performEndorsed(parked, "22222222"),
                "/cash-movement?error=bad-type");
        verifyNoInteractions(resource.cashMovementService);
    }
}
