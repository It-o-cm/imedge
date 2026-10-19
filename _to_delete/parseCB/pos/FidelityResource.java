package com.intermarche.pos.ui.fidelity;

import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import org.jboss.logging.Logger;

/**
 * JAX-RS resource of the manual fidelity-card entry page — the fallback
 * when the card does not scan (the scan handler is the primary path). Class
 * is {@code @DrawerMustBeClosed} like the other sale screens: attaching a
 * card is a sale gesture, not a drawer one.
 */
@Path("/")
@DrawerMustBeClosed
public class FidelityResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FidelityResource.class);

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    @Inject Template fidelity;
    @Inject FidelityService fidelityService;
    @Inject
    PosState state;

    /**
     * Confirms the replacement of the attached card by the parked one
     * (LC-03-04-08): the proposal was made when a second, different card was
     * scanned on a ticket that already carried one, and the operator's OUI
     * walks the new card through the ordinary attachment.
     *
     * @return a 303 redirect to the main page (PRG pattern, so a browser
     *         reload never replays the gesture)
     */
    @GET
    @Path("/action/fidelity-replace/confirm")
    public Response confirmReplacement() {
        LOGGER.info("Entering method confirmReplacement");
        fidelityService.confirmReplacement(state);
        LOGGER.info("Exiting method confirmReplacement");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Keeps the attached card and drops the parked replacement (LC-03-04-08):
     * the NON button of the proposal.
     *
     * @return a 303 redirect to the main page (PRG pattern, so a browser
     *         reload never replays the gesture)
     */
    @GET
    @Path("/action/fidelity-replace/keep")
    public Response keepReplacement() {
        LOGGER.info("Entering method keepReplacement");
        fidelityService.keepReplacement(state);
        LOGGER.info("Exiting method keepReplacement");
        return Response.seeOther(URI.create("/")).build();
    }

    // --- Vue ---

    /**
     * Shows the manual card-entry page.
     *
     * @return the fidelity page
     */
    @GET
    @Path("/fidelity") // Chemin complet
    public TemplateInstance fidelityPage() {
        LOGGER.info("Entering method fidelityPage");
        // In-store consultation (imfid spec §4): balance and history of the
        // attached card, assembled server-side; degraded = a message.
        // The lookup outcome is read from the state (stored by the POST
        // before its 303 hop here), so refreshing the page re-renders the
        // same result list instead of replaying a POST.
        LOGGER.info("Exiting method fidelityPage");
        return fidelity.data("state", state)
                .data("lookup", state.fidelity.lastLookup)
                .data("searchMode", state.fidelity.lastLookupMode)
                .data("searchValue", state.fidelity.lastLookupValue)
                .data("searchFirstName", state.fidelity.lastLookupFirstName)
                .data("consultation", fidelityService.loadConsultation(state));
    }

    // --- Action ---

    /**
     * Attaches the typed card and returns to the home page.
     *
     * @param card the typed card number
     * @return a 303 redirect to the main page (PRG pattern, so a browser
     *         reload never replays the POST)
     */
    @POST
    @Path("/action/fidelity") // Chemin complet
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response validateFidelity(@FormParam("card") String card) {
        LOGGER.info("Entering method validateFidelity with card: " + card);
        fidelityService.validateCard(state, card);
        LOGGER.info("Exiting method validateFidelity");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Searches cards by holder identity (addendum §3) and re-renders the
     * fidelity page with the matches. ONE criterion per call, chosen by the
     * the mode the operator picked: {@code tel}, {@code email} or {@code name}.
     * In name mode the LAST NAME and the FIRST NAME are TWO fields
     * (LC-03-04-17) — a compound last name ("DE LA CRUZ") searches as itself,
     * and the line asks for an error message when either field is left empty
     * rather than a silent search on a word that happens to miss.
     *
     * @param mode the search mode (tel | email | name)
     * @param value the operator's input, as typed (imfid normalizes)
     * @param firstName the first-name input of the name mode, as typed
     * @return a 303 redirect to the fidelity page (PRG pattern, so a browser
     *         refresh never replays the POST); the outcome plus the echoed
     *         search mode and criteria travel through the fidelity state and
     *         the page re-renders in the SAME mode with both criteria displayed
     */
    @POST
    @Path("/action/fidelity-lookup")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response lookupFidelity(@FormParam("mode") String mode,
                                           @FormParam("value") String value,
                                           @FormParam("firstName") String firstName) {
        LOGGER.info("Entering method lookupFidelity with mode: " + mode + ", value: " + value + ", firstName: " + firstName);
        String phone = null, email = null, name = null, city = null;
        String input = value != null ? value.trim() : "";
        String givenName = firstName != null ? firstName.trim() : "";
        if ("tel".equals(mode)) {
            phone = input;
        } else if ("email".equals(mode)) {
            email = input;
        } else if ("ville".equals(mode)) {
            // EXP-74: the city criterion — one more search axis alongside
            // phone / e-mail / name.
            city = input;
        } else {
            // LC-03-04-17: the name search carries BOTH criteria, and neither
            // is optional — the two fields exist so a compound last name is
            // itself and the first name is its own prefix, and the line asks
            // the register to say when one of the two was left empty.
            name = input;
            if (name.isBlank() || givenName.isBlank()) {
                LookupView refusal = new LookupView();
                refusal.message = i18n.get("fidelity.nomEtPrenomRequis");
                state.fidelity.lastLookup = refusal;
                state.fidelity.lastLookupMode = mode != null ? mode : "name";
                state.fidelity.lastLookupValue = input;
                state.fidelity.lastLookupFirstName = givenName;
                state.fidelity.lastLookupPage = 0;
                LOGGER.info("Exiting method lookupFidelity");
                return Response.seeOther(URI.create("/fidelity")).build();
            }
        }
        LookupView lookup =
                fidelityService.lookupCards(phone, email, name, "name".equals(mode) ? givenName : null, city);
        state.fidelity.lastLookup = lookup;
        state.fidelity.lastLookupMode = mode != null ? mode : "name";
        state.fidelity.lastLookupValue = input;
        state.fidelity.lastLookupFirstName = "name".equals(mode) ? givenName : "";
        state.fidelity.lastLookupPage = 0;
        LOGGER.info("Exiting method lookupFidelity");
        return Response.seeOther(URI.create("/fidelity")).build();
    }

    /**
     * Changes the lookup result page (same pattern as the refund detail
     * pagination) and re-renders the fidelity page; the index is clamped by
     * the state when the list is read.
     *
     * @param page the target 0-based page index
     * @return the fidelity page on the requested result page
     */
    @GET
    @Path("/fidelity/page/{p}")
    public TemplateInstance changeLookupPage(@PathParam("p") int page) {
        LOGGER.info("Entering method changeLookupPage with page: " + page);
        state.fidelity.lastLookupPage = Math.max(0, page);
        LOGGER.info("Exiting method changeLookupPage");
        return fidelityPage();
    }

    /**
     * Attaches a card selected in the lookup list (addendum §4) and returns
     * to the sale screen; a refused status (RESILIATED) re-renders the page
     * with the refusal. The card REPLACES any previously attached one, and
     * the found number then flows exactly like a scanned card.
     *
     * @param card the selected card number
     * @param lastName the holder's last name, echoed from the lookup
     * @param firstName the holder's first name, echoed from the lookup
     * @param status the account status, echoed from the lookup
     * @param email the holder's e-mail, echoed from the lookup (LC-08-02-09)
     * @return a 303 redirect (PRG pattern, so a browser reload never replays
     *         the POST): to the main page on success, or back to the fidelity
     *         page with the refusal stored in the fidelity state
     */
    @POST
    @Path("/action/fidelity-select")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response selectFidelity(@FormParam("card") String card,
                                           @FormParam("lastName") String lastName,
                                           @FormParam("firstName") String firstName,
                                           @FormParam("status") String status,
                                           @FormParam("email") String email) {
        LOGGER.info("Entering method selectFidelity with card: " + card + ", lastName: " + lastName + ", firstName: " + firstName + ", status: " + status + ", email: " + email);
        String refusal = fidelityService.attachLookedUpCard(state, card, lastName, firstName,
                status, email);
        if (refusal != null) {
            // The refusal replaces the stored result list; the search mode
            // and criterion are kept so the operator stays in context.
            LookupView lookup = new LookupView();
            lookup.message = refusal;
            state.fidelity.lastLookup = lookup;
            LOGGER.info("Exiting method selectFidelity");
            return Response.seeOther(URI.create("/fidelity")).build();
        }
        LOGGER.info("Exiting method selectFidelity");
        return Response.seeOther(URI.create("/")).build();
    }
}