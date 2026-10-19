package com.intermarche.pos.ui.fidelity;

import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FidelityResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, its
 * {@link FidelityState} sub-state, a {@link FidelityService} and the
 * {@code fidelity} Qute {@link Template}. Every collaborator is a Mockito mock,
 * except the {@link FidelityState} sub-state which is a REAL instance so the
 * many public-field reads and writes the resource performs never hit a null.
 * The template echoes a recognizable {@link TemplateInstance} through its
 * fluent {@code data(..)} chain so the returned view can be identified with
 * {@code assertSame}. Tests assert absolute expected values (redirect status
 * and Location, returned view, stored state fields) and verify delegation,
 * covering both arms of the {@code value} and {@code mode} null ternaries, all
 * three {@code mode} legs, both arms of the {@code space > 0} guard and both
 * arms of the {@code refusal != null} guard.
 */
class FidelityResourceTest {

    /**
     * Builds a {@link FidelityResource} whose service and template are fresh
     * mocks and whose {@link PosState} is a mock carrying a REAL
     * {@link FidelityState} sub-state, so field access never hits a null.
     *
     * @return a resource with mocked service and template over a real fidelity state
     */
    private FidelityResource newResource() {
        FidelityResource resource = new FidelityResource();
        resource.state = mock(PosState.class);
        resource.state.fidelity = new FidelityState();
        resource.fidelityService = mock(FidelityService.class);
        resource.fidelity = mock(Template.class);
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        resource.i18n = com.intermarche.pos.ui.TestMessages.french();
        return resource;
    }

    /**
     * Stubs the {@code fidelity} template fluent chain to return a single
     * recognizable view for the given resource.
     *
     * @param resource the resource whose {@code fidelity} template is stubbed
     * @return the view every {@code data(..)} call in the chain returns
     */
    private TemplateInstance stubFidelity(FidelityResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.fidelity.data("state", resource.state)).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        return view;
    }

    // --- fidelityPage ---

    /**
     * {@code fidelityPage()} assembles the page from the template chain, feeding
     * the consultation loaded from the state, and returns the rendered view.
     */
    @Test
    void fidelityPageRendersConsultationView() {
        FidelityResource resource = newResource();
        TemplateInstance view = stubFidelity(resource);
        FidelityService.Consultation consultation = mock(FidelityService.Consultation.class);
        when(resource.fidelityService.loadConsultation(resource.state)).thenReturn(consultation);
        assertSame(view, resource.fidelityPage());
        verify(resource.fidelityService).loadConsultation(resource.state);
        verify(resource.fidelity).data("state", resource.state);
    }

    // --- validateFidelity ---

    /**
     * {@code validateFidelity()} delegates the typed card to the service and
     * redirects to the home page (PRG pattern).
     */
    @Test
    void validateFidelityAttachesCardAndRedirectsHome() {
        FidelityResource resource = newResource();
        Response response = resource.validateFidelity("123456");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.fidelityService).validateCard(resource.state, "123456");
    }

    // --- lookupFidelity ---

    /**
     * {@code lookupFidelity()} in {@code tel} mode trims the non-null value
     * (value ternary true arm), searches by phone only (tel leg) and echoes the
     * non-null mode (mode ternary true arm), storing the outcome and resetting
     * the page.
     */
    @Test
    void lookupFidelityTelModeSearchesByPhone() {
        FidelityResource resource = newResource();
        LookupView lookup = new LookupView();
        when(resource.fidelityService.lookupCards("0601", null, null, null, null)).thenReturn(lookup);
        Response response = resource.lookupFidelity("tel", "  0601  ", null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/fidelity", response.getLocation().toString());
        verify(resource.fidelityService).lookupCards("0601", null, null, null, null);
        assertSame(lookup, resource.state.fidelity.lastLookup);
        assertEquals("tel", resource.state.fidelity.lastLookupMode);
        assertEquals("0601", resource.state.fidelity.lastLookupValue);
        assertEquals(0, resource.state.fidelity.lastLookupPage);
    }

    /**
     * {@code lookupFidelity()} in {@code email} mode (tel leg false, email leg
     * true) searches by e-mail only and echoes the mode.
     */
    @Test
    void lookupFidelityEmailModeSearchesByEmail() {
        FidelityResource resource = newResource();
        LookupView lookup = new LookupView();
        when(resource.fidelityService.lookupCards(null, "a@b.fr", null, null, null)).thenReturn(lookup);
        Response response = resource.lookupFidelity("email", "a@b.fr", null);
        assertEquals("/fidelity", response.getLocation().toString());
        verify(resource.fidelityService).lookupCards(null, "a@b.fr", null, null, null);
        assertEquals("email", resource.state.fidelity.lastLookupMode);
        assertEquals("a@b.fr", resource.state.fidelity.lastLookupValue);
    }

    /**
     * LC-03-04-17: {@code lookupFidelity()} in {@code name} mode carries TWO
     * criteria — the last name is its own field and never splits again, the
     * first name is the second field, and both travel to the holder
     * directory as the two prefixes it already knows.
     */
    @Test
    void lookupFidelityNameModeCarriesBothFields() {
        FidelityResource resource = newResource();
        LookupView lookup = new LookupView();
        when(resource.fidelityService.lookupCards(null, null, "DE LA CRUZ", "JACQUES", null)).thenReturn(lookup);
        Response response = resource.lookupFidelity("name", "DE LA CRUZ", "JACQUES");
        assertEquals("/fidelity", response.getLocation().toString());
        verify(resource.fidelityService).lookupCards(null, null, "DE LA CRUZ", "JACQUES", null);
        assertEquals("name", resource.state.fidelity.lastLookupMode);
        assertEquals("DE LA CRUZ", resource.state.fidelity.lastLookupValue);
        assertEquals("JACQUES", resource.state.fidelity.lastLookupFirstName);
    }

    /**
     * LC-03-04-17, the demanded error: a name search missing EITHER field is
     * refused with its own sentence — no directory call, the typed halves
     * echoed so the operator completes rather than retypes.
     */
    @Test
    void lookupFidelityNameModeRefusesAMissingField() {
        FidelityResource resource = newResource();
        Response noFirstName = resource.lookupFidelity("name", "DURAND", " ");
        assertEquals("/fidelity", noFirstName.getLocation().toString());
        verifyNoInteractions(resource.fidelityService);
        assertEquals("NOM ET PRÉNOM REQUIS POUR LA RECHERCHE",
                resource.state.fidelity.lastLookup.message);
        assertEquals("name", resource.state.fidelity.lastLookupMode);
        assertEquals("DURAND", resource.state.fidelity.lastLookupValue);
        Response noLastName = resource.lookupFidelity("name", "", "JACQUES");
        verifyNoInteractions(resource.fidelityService);
        assertEquals("NOM ET PRÉNOM REQUIS POUR LA RECHERCHE",
                resource.state.fidelity.lastLookup.message);
        assertEquals("JACQUES", resource.state.fidelity.lastLookupFirstName);
    }

    /**
     * EXP-74: {@code lookupFidelity()} in {@code ville} mode searches by city —
     * the city is the only criterion passed, the other four null.
     */
    @Test
    void lookupFidelityVilleModeSearchesByCity() {
        FidelityResource resource = newResource();
        LookupView lookup = new LookupView();
        when(resource.fidelityService.lookupCards(null, null, null, null, "LYON")).thenReturn(lookup);
        Response response = resource.lookupFidelity("ville", "LYON", null);
        assertEquals("/fidelity", response.getLocation().toString());
        verify(resource.fidelityService).lookupCards(null, null, null, null, "LYON");
        assertEquals("ville", resource.state.fidelity.lastLookupMode);
        assertEquals("LYON", resource.state.fidelity.lastLookupValue);
    }

    /**
     * {@code lookupFidelity()} with a null mode and null value takes the value
     * ternary false arm (input defaults to empty), falls through to the name
     * leg, echoes {@code "name"} via the mode ternary false arm — and is
     * refused by the two-fields rule of LC-03-04-17 rather than searching the
     * directory on an empty word.
     */
    @Test
    void lookupFidelityNullModeAndValueDefaultsToNameEmpty() {
        FidelityResource resource = newResource();
        Response response = resource.lookupFidelity(null, null, null);
        assertEquals("/fidelity", response.getLocation().toString());
        verifyNoInteractions(resource.fidelityService);
        assertEquals("name", resource.state.fidelity.lastLookupMode);
        assertEquals("", resource.state.fidelity.lastLookupValue);
        assertEquals("NOM ET PRÉNOM REQUIS POUR LA RECHERCHE",
                resource.state.fidelity.lastLookup.message);
    }

    // --- changeLookupPage ---

    /**
     * {@code changeLookupPage()} stores a positive page index unchanged and
     * re-renders the fidelity page.
     */
    @Test
    void changeLookupPageStoresPositiveIndexAndRenders() {
        FidelityResource resource = newResource();
        TemplateInstance view = stubFidelity(resource);
        assertSame(view, resource.changeLookupPage(4));
        assertEquals(4, resource.state.fidelity.lastLookupPage);
    }

    /**
     * {@code changeLookupPage()} clamps a negative page index to zero and
     * re-renders the fidelity page.
     */
    @Test
    void changeLookupPageClampsNegativeIndexToZero() {
        FidelityResource resource = newResource();
        TemplateInstance view = stubFidelity(resource);
        assertSame(view, resource.changeLookupPage(-3));
        assertEquals(0, resource.state.fidelity.lastLookupPage);
    }

    // --- selectFidelity ---

    /**
     * {@code selectFidelity()} on a refusal (refusal != null true arm) stores a
     * new lookup view carrying the refusal message and redirects back to the
     * fidelity page.
     */
    @Test
    void selectFidelityRefusalReRendersFidelityPage() {
        FidelityResource resource = newResource();
        when(resource.fidelityService.attachLookedUpCard(resource.state, "555", "DURAND",
                "JACQUES", "RESILIATED", "a@b.fr")).thenReturn("Carte résiliée");
        Response response = resource.selectFidelity("555", "DURAND", "JACQUES", "RESILIATED", "a@b.fr");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/fidelity", response.getLocation().toString());
        assertEquals("Carte résiliée", resource.state.fidelity.lastLookup.message);
    }

    /**
     * {@code selectFidelity()} on success (refusal != null false arm) leaves the
     * stored lookup untouched and redirects to the home page.
     */
    @Test
    void selectFidelitySuccessRedirectsHome() {
        FidelityResource resource = newResource();
        when(resource.fidelityService.attachLookedUpCard(resource.state, "555", "DURAND",
                "JACQUES", "ACTIVE", "a@b.fr")).thenReturn(null);
        Response response = resource.selectFidelity("555", "DURAND", "JACQUES", "ACTIVE", "a@b.fr");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        assertNull(resource.state.fidelity.lastLookup);
    }
}
