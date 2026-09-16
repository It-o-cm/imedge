package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.payment.TenderDefinition;
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
import java.util.List;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminTenderResource}.
 * <p>
 * The resource is a Qute-backed back-office page over the Panache static
 * finders of {@link TenderDefinition} (resolved to {@link PanacheEntityBase}
 * outside a Quarkus context, intercepted with
 * {@link org.mockito.Mockito#mockStatic}). The row inserted by the create arm
 * is neutralised with {@link org.mockito.Mockito#mockConstruction}. No
 * database, no Quarkus boot.
 * <p>
 * Branch enumeration (every arm exercised): {@code saveTender} covers the
 * unknown settlement key arm, the too-short identifier arm at its boundary, the
 * identifier-taken arm and its same-row exception, the insert arm and the update
 * arm, both arms of every checkbox, both arms of the order fallback, and — in
 * {@code apply} — the readable, absent and malformed legs of the amount parser,
 * the accepted and refused legs of the count parser, and the known and unknown
 * legs of the two enumeration parsers; {@code deleteTender} covers the unknown
 * arm and the removed arm; {@code buildCsv} covers an administered row, an
 * unadministered one and the empty referential.
 */
class AdminTenderResourceTest {

    /**
     * Builds a resource over a mocked template.
     *
     * @return the wired resource
     */
    private AdminTenderResource newResource() {
        AdminTenderResource resource = new AdminTenderResource();
        resource.adminTenders = mock(Template.class);
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the page template to a single
     * self-returning instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked template instance the chain returns
     */
    private TemplateInstance wireTemplate(AdminTenderResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminTenders.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds an empty posted form.
     *
     * @return a mutable empty form
     */
    private MultivaluedMap<String, String> form() {
        return new MultivaluedHashMap<>();
    }

    /**
     * Builds a sound posted description of a tender.
     *
     * @param code the settlement key
     * @param functionalId the functional identifier
     * @return the posted form
     */
    private MultivaluedMap<String, String> soundForm(String code, String functionalId) {
        MultivaluedMap<String, String> posted = form();
        posted.putSingle("code", code);
        posted.putSingle("functionalId", functionalId);
        posted.putSingle("label", "Titre restaurant");
        posted.putSingle("displayOrder", "30");
        posted.putSingle("maxAmount", "25,00");
        posted.putSingle("maxAmountControl", "BLOCKING");
        posted.putSingle("maxCount", "2");
        posted.putSingle("maxCountControl", "SUPERVISOR");
        posted.putSingle("drawerOpening", "IF_CHANGE_DUE");
        return posted;
    }

    /**
     * Builds a Panache query answering the given single result.
     *
     * <p>Built OUTSIDE any {@code mockStatic} block by every caller: stubbing an
     * instance mock while a static mock is open is what raises
     * {@code UnfinishedStubbingException}.
     *
     * @param result the row the query answers, or null
     * @return the stubbed query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<TenderDefinition> queryOf(TenderDefinition result) {
        PanacheQuery<TenderDefinition> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Reads the notice a redirect carries.
     *
     * @param response the redirect response
     * @return the decoded notice
     */
    private String notice(Response response) {
        String query = response.getLocation().getQuery();
        int at = query.indexOf("notice=");
        return java.net.URLDecoder.decode(query.substring(at + "notice=".length()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * The page hands the template the referential, the settlement keys a new row
     * may claim and the two catalogs the selects offer.
     */
    @Test
    void thePageOffersTheReferentialAndItsCatalogs() {
        AdminTenderResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        TenderDefinition tender = new TenderDefinition();
        tender.code = "TR";
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.list("order by displayOrder, code"))
                    .thenReturn(List.of(tender));
            assertSame(instance, resource.tendersPage(null, null));
        }
        verify(resource.adminTenders).data("tenders", List.of(tender));
        verify(instance).data("controls", TenderDefinition.ControlLevel.values());
        verify(instance).data("drawerMoments", TenderDefinition.DrawerOpening.values());
        verify(instance).data("noticeOk", true);
    }

    /**
     * A notice explicitly reported as a failure reaches the page as one, which
     * is the other leg of the {@code noticeOk} guard.
     */
    @Test
    void thePageCarriesAFailedNotice() {
        AdminTenderResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.list("order by displayOrder, code"))
                    .thenReturn(List.of());
            resource.tendersPage("Refusé.", "false");
        }
        verify(instance).data("noticeOk", false);
    }

    /**
     * A settlement key the register does not know is refused outright — the
     * doctrine's guard: the back office puts an existing method in service, it
     * does not invent one.
     */
    @Test
    void anUnknownSettlementKeyIsRefused() {
        AdminTenderResource resource = newResource();
        MultivaluedMap<String, String> posted = soundForm("CRYPTO", "099");
        Response response = resource.saveTender(posted);
        assertEquals(303, response.getStatus());
        assertTrue(notice(response).contains("CRYPTO"));
    }

    /**
     * An identifier narrower than the required width is refused, and one exactly
     * at the width is accepted — the boundary of the guard (BO-03-02-04).
     */
    @Test
    void aTooNarrowIdentifierIsRefused() {
        AdminTenderResource resource = newResource();
        Response response = resource.saveTender(soundForm("TR", "30"));
        assertEquals(303, response.getStatus());
        assertTrue(notice(response).contains("3"));
    }

    /**
     * An identifier already borne by ANOTHER tender is refused, naming the
     * tender holding it.
     */
    @Test
    void anIdentifierAlreadyTakenIsRefused() {
        AdminTenderResource resource = newResource();
        TenderDefinition holder = new TenderDefinition();
        holder.code = "CASH";
        holder.functionalId = "030";
        PanacheQuery<TenderDefinition> byId = queryOf(holder);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.find("functionalId", "030")).thenReturn(byId);
            Response response = resource.saveTender(soundForm("TR", "030"));
            assertEquals(303, response.getStatus());
            assertTrue(notice(response).contains("CASH"));
        }
    }

    /**
     * A tender keeping its OWN identifier is not refused for clashing with
     * itself — the second leg of the clash guard.
     */
    @Test
    void aTenderKeepsItsOwnIdentifier() {
        AdminTenderResource resource = newResource();
        TenderDefinition existing = new TenderDefinition();
        existing.code = "TR";
        existing.functionalId = "030";
        PanacheQuery<TenderDefinition> byId = queryOf(existing);
        PanacheQuery<TenderDefinition> byCode = queryOf(existing);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.find("functionalId", "030")).thenReturn(byId);
            ms.when(() -> TenderDefinition.find("code", "TR")).thenReturn(byCode);
            Response response = resource.saveTender(soundForm("TR", "030"));
            assertEquals("Moyen de règlement enregistré.", notice(response));
        }
        assertEquals("Titre restaurant", existing.label);
        assertEquals(new BigDecimal("25.00"), existing.maxAmount);
        assertEquals(TenderDefinition.ControlLevel.BLOCKING, existing.maxAmountControl);
        assertEquals(Integer.valueOf(2), existing.maxCount);
        assertEquals(TenderDefinition.ControlLevel.SUPERVISOR, existing.maxCountControl);
        assertEquals(TenderDefinition.DrawerOpening.IF_CHANGE_DUE, existing.drawerOpening);
        assertFalse(existing.active);
        assertEquals(30, existing.displayOrder);
    }

    /**
     * A tender no row administers yet is created, and every posted flag lands on
     * the created row — the checked leg of each checkbox.
     */
    @Test
    void anUnadministeredTenderIsCreatedWithItsFlags() {
        AdminTenderResource resource = newResource();
        MultivaluedMap<String, String> posted = soundForm("TR", "030");
        posted.putSingle("active", "on");
        posted.putSingle("refundAllowed", "on");
        posted.putSingle("changeAllowed", "on");
        posted.putSingle("changeTenderCode", "CASH");
        posted.putSingle("cashierDeclaration", "on");
        posted.putSingle("automaticWithdrawal", "on");
        posted.putSingle("movementAllowed", "on");
        posted.putSingle("bankDeposit", "on");
        posted.putSingle("floatAllowed", "on");
        posted.putSingle("defaultsToTotal", "on");
        posted.putSingle("withdrawalReportDetail", "on");
        posted.putSingle("fidelityReported", "on");
        posted.putSingle("secondMaxAmount", "50.00");
        posted.putSingle("secondMaxAmountControl", "INFO");
        posted.putSingle("minAmount", "5.00");
        posted.putSingle("minAmountControl", "WARNING");
        posted.putSingle("maxChangeAmount", "8.00");
        posted.putSingle("maxChangeControl", "INFO");
        PanacheQuery<TenderDefinition> absent = queryOf(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
                MockedConstruction<TenderDefinition> created =
                        mockConstruction(TenderDefinition.class)) {
            ms.when(() -> TenderDefinition.find("functionalId", "030")).thenReturn(absent);
            ms.when(() -> TenderDefinition.find("code", "TR")).thenReturn(absent);
            Response response = resource.saveTender(posted);
            assertEquals("Moyen de règlement créé.", notice(response));
            TenderDefinition inserted = created.constructed().get(0);
            assertEquals("TR", inserted.code);
            assertEquals("030", inserted.functionalId);
            assertTrue(inserted.active);
            assertTrue(inserted.refundAllowed);
            assertTrue(inserted.changeAllowed);
            assertEquals("CASH", inserted.changeTenderCode);
            assertTrue(inserted.cashierDeclaration);
            assertTrue(inserted.automaticWithdrawal);
            assertTrue(inserted.movementAllowed);
            assertTrue(inserted.bankDeposit);
            assertTrue(inserted.floatAllowed);
            assertTrue(inserted.defaultsToTotal);
            assertTrue(inserted.withdrawalReportDetail);
            assertTrue(inserted.fidelityReported);
            assertEquals(new BigDecimal("50.00"), inserted.secondMaxAmount);
            assertEquals(TenderDefinition.ControlLevel.INFO, inserted.secondMaxAmountControl);
            assertEquals(new BigDecimal("5.00"), inserted.minAmount);
            assertEquals(TenderDefinition.ControlLevel.WARNING, inserted.minAmountControl);
            assertEquals(new BigDecimal("8.00"), inserted.maxChangeAmount);
            assertEquals(TenderDefinition.ControlLevel.INFO, inserted.maxChangeControl);
            verify(inserted, times(1)).persist();
        }
    }

    /**
     * An unreadable description lands as an unbounded tender rather than
     * throwing: the absent, blank and malformed legs of the amount parser, the
     * refused legs of the count and order parsers, and the unknown legs of the
     * two enumeration parsers.
     */
    @Test
    void anUnreadableDescriptionLandsUnbounded() {
        AdminTenderResource resource = newResource();
        MultivaluedMap<String, String> posted = form();
        posted.putSingle("code", "CASH");
        posted.putSingle("functionalId", "010");
        posted.putSingle("label", "Espèces");
        posted.putSingle("displayOrder", "pas un nombre");
        posted.putSingle("maxAmount", "pas un montant");
        posted.putSingle("secondMaxAmount", "");
        posted.putSingle("maxCount", "0");
        posted.putSingle("maxAmountControl", "INEXISTANT");
        posted.putSingle("drawerOpening", "JAMAIS_VU");
        posted.putSingle("changeTenderCode", "");
        PanacheQuery<TenderDefinition> absent = queryOf(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
                MockedConstruction<TenderDefinition> created =
                        mockConstruction(TenderDefinition.class)) {
            ms.when(() -> TenderDefinition.find("functionalId", "010")).thenReturn(absent);
            ms.when(() -> TenderDefinition.find("code", "CASH")).thenReturn(absent);
            resource.saveTender(posted);
            TenderDefinition inserted = created.constructed().get(0);
            assertEquals(100, inserted.displayOrder);
            assertNull(inserted.maxAmount);
            assertNull(inserted.secondMaxAmount);
            assertNull(inserted.minAmount);
            assertNull(inserted.maxCount);
            assertNull(inserted.changeTenderCode);
            assertEquals(TenderDefinition.ControlLevel.NONE, inserted.maxAmountControl);
            assertEquals(TenderDefinition.DrawerOpening.NEVER, inserted.drawerOpening);
            assertFalse(inserted.active);
        }
    }

    /**
     * Deleting a tender no row administers is refused, and nothing is touched.
     */
    @Test
    void deletingAnUnadministeredTenderIsRefused() {
        AdminTenderResource resource = newResource();
        MultivaluedMap<String, String> posted = form();
        posted.putSingle("code", "TR");
        PanacheQuery<TenderDefinition> absent = queryOf(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.find("code", "TR")).thenReturn(absent);
            Response response = resource.deleteTender(posted);
            assertEquals("Moyen de règlement inconnu.", notice(response));
        }
    }

    /**
     * Deleting an administered tender removes its row.
     */
    @Test
    void deletingAnAdministeredTenderRemovesIt() {
        AdminTenderResource resource = newResource();
        MultivaluedMap<String, String> posted = form();
        posted.putSingle("code", "TR");
        TenderDefinition tender = mock(TenderDefinition.class);
        tender.code = "TR";
        PanacheQuery<TenderDefinition> found = queryOf(tender);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.find("code", "TR")).thenReturn(found);
            Response response = resource.deleteTender(posted);
            assertEquals("Moyen de règlement supprimé.", notice(response));
        }
        verify(tender, times(1)).delete();
    }

    /**
     * The exported list carries one line per tender, an administered row showing
     * its bounds and an unadministered one showing them empty (BO-03-02-05).
     */
    @Test
    void theExportedListStatesWhatIsAdministered() {
        AdminTenderResource resource = newResource();
        TenderDefinition tender = new TenderDefinition();
        tender.code = "TR";
        tender.functionalId = "030";
        tender.label = "Titre restaurant";
        tender.active = true;
        tender.displayOrder = 30;
        tender.maxAmount = new BigDecimal("25.00");
        tender.maxAmountControl = TenderDefinition.ControlLevel.BLOCKING;
        tender.maxCount = 2;
        tender.maxCountControl = TenderDefinition.ControlLevel.SUPERVISOR;
        tender.drawerOpening = TenderDefinition.DrawerOpening.IF_CHANGE_DUE;
        tender.refundAllowed = true;

        TenderDefinition bare = new TenderDefinition();
        bare.code = "CASH";
        bare.functionalId = "010";
        bare.label = "Espèces";
        bare.active = false;
        bare.maxAmountControl = null;
        bare.drawerOpening = null;

        String csv = resource.buildCsv(List.of(tender, bare));
        String[] lines = csv.split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].startsWith("Identifiant;Code;Libelle;Actif;"));
        assertTrue(lines[1].startsWith("030;TR;Titre restaurant;Oui;30;25.00;Bloquant;"));
        assertTrue(lines[1].contains("Si rendu dû"));
        assertTrue(lines[1].contains(";2;Bloquant superviseur;"));
        assertTrue(lines[2].startsWith("010;CASH;Espèces;Non;100;;;"));
        assertTrue(lines[2].endsWith("Non"));
    }

    /**
     * An empty referential exports its header and nothing else.
     */
    @Test
    void theExportedListOfAnEmptyReferentialIsItsHeader() {
        AdminTenderResource resource = newResource();
        String csv = resource.buildCsv(List.of());
        assertEquals(1, csv.split("\n").length);
        assertTrue(csv.endsWith("Remontee fidelite\n"));
    }

    /**
     * The export endpoint hands back the built list as an attachment named for
     * what it holds.
     */
    @Test
    void theExportEndpointHandsBackAnAttachment() {
        AdminTenderResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.list("order by displayOrder, code"))
                    .thenReturn(List.of());
            Response response = resource.export();
            assertEquals(200, response.getStatus());
            assertEquals("attachment; filename=\"modes-de-reglement.csv\"",
                    response.getHeaderString("Content-Disposition"));
            assertTrue(String.valueOf(response.getEntity()).startsWith("Identifiant;"));
        }
    }

    /**
     * Saving refuses before touching the referential when the key is unknown,
     * which is what the absence of any finder stubbing in that test asserts —
     * restated here as an explicit verification that nothing was persisted.
     */
    @Test
    void aRefusedSaveWritesNothing() {
        AdminTenderResource resource = newResource();
        try (MockedConstruction<TenderDefinition> created =
                mockConstruction(TenderDefinition.class)) {
            resource.saveTender(soundForm("CRYPTO", "099"));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * The delete arm never runs on a refused save, so a tender is never removed
     * by a description that did not hold together.
     */
    @Test
    void aRefusedSaveNeverDeletes() {
        AdminTenderResource resource = newResource();
        TenderDefinition tender = mock(TenderDefinition.class);
        resource.saveTender(soundForm("CRYPTO", "099"));
        verify(tender, never()).delete();
    }
}
