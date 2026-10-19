package com.intermarche.edge.ui.admin;

import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the REFUSALS of {@link AdminMessageResource} (BO-03-08-04).
 *
 * <p>Only the refusals, and deliberately: every one of them answers before the
 * resource touches the database, so they are the part of this screen a unit
 * test can state exactly. Each case is one guard, and the enumeration below is
 * the list of ways a message can be wrong — no text and no question, an
 * unknown population, an unknown moment, an unknown confirmation mode, a
 * population that names nobody, a date nobody can read, and a period that ends
 * before it starts.
 */
class AdminMessageResourceTest {

    /**
     * A form carrying the three choices a valid message needs, so each test
     * only breaks the one thing it is about.
     *
     * @return a form that would otherwise be accepted
     */
    private MultivaluedMap<String, String> validForm() {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("text", "FERMETURE A 19H");
        form.putSingle("audience", "ALL");
        form.putSingle("trigger", "IMMEDIATE");
        form.putSingle("confirmation", "NONE");
        form.putSingle("responseType", "OPTIONS");
        return form;
    }

    /**
     * Asserts the answer is the screen's refusal, carrying the expected notice.
     *
     * @param response the answer under test
     * @param notice the sentence the screen must carry
     */
    private void assertRefused(Response response, String notice) {
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        String location = response.getLocation().toString();
        assertTrue(location.startsWith("/admin/messages?"), location);
        assertTrue(location.contains("noticeOk=false"), location);
        assertTrue(java.net.URLDecoder.decode(location, java.nio.charset.StandardCharsets.UTF_8)
                .contains(notice), location);
    }

    /**
     * A creation with neither text nor question says nothing, and an empty
     * banner served to forty registers is a defect rather than a message.
     */
    @Test
    void aMessageWithNothingToSayIsRefused() {
        MultivaluedMap<String, String> form = validForm();
        form.remove("text");
        assertRefused(new AdminMessageResource().saveMessage(form), "ne dit rien");
    }

    /**
     * An unknown population, moment or confirmation mode is refused by name
     * rather than silently falling back on a default — three guards, one per
     * choice the screen offers.
     */
    @Test
    void anUnknownChoiceIsRefusedByName() {
        MultivaluedMap<String, String> audience = validForm();
        audience.putSingle("audience", "TOUT LE MONDE");
        assertRefused(new AdminMessageResource().saveMessage(audience), "Population inconnue");
        MultivaluedMap<String, String> trigger = validForm();
        trigger.putSingle("trigger", "PLUS TARD");
        assertRefused(new AdminMessageResource().saveMessage(trigger), "Moment d'affichage inconnu");
        MultivaluedMap<String, String> confirmation = validForm();
        confirmation.putSingle("confirmation", "PEUT-ETRE");
        assertRefused(new AdminMessageResource().saveMessage(confirmation),
                "Mode de confirmation inconnu");
    }

    /**
     * A population other than "every register" that names nobody would be
     * served to nobody: the screen says so instead of writing a message that
     * never arrives.
     */
    @Test
    void aTargetedMessageThatNamesNobodyIsRefused() {
        MultivaluedMap<String, String> form = validForm();
        form.putSingle("audience", "TERMINAL");
        assertRefused(new AdminMessageResource().saveMessage(form), "demande de nommer");
    }

    /**
     * A date nobody can read is refused with the mask expected, rather than
     * being stored as no bound at all — a typo must not turn a dated notice
     * into a permanent one.
     */
    @Test
    void anUnreadableDateIsRefused() {
        MultivaluedMap<String, String> form = validForm();
        form.putSingle("startsAt", "12 octobre");
        assertRefused(new AdminMessageResource().saveMessage(form), "Période de diffusion invalide");
    }

    /**
     * A period that ends before it starts is served to nobody, ever: the screen
     * refuses it rather than storing a message that cannot be shown.
     */
    @Test
    void aPeriodThatEndsBeforeItStartsIsRefused() {
        MultivaluedMap<String, String> form = validForm();
        form.putSingle("startsAt", "12/10/2026 18:00");
        form.putSingle("endsAt", "12/10/2026 09:00");
        assertRefused(new AdminMessageResource().saveMessage(form), "précède son début");
    }

    /**
     * LC-03-07-06 to -09: an unknown response TYPE is refused by name, like
     * the other administered choices — a survey the screen cannot type an
     * answer to is a defect, not a survey.
     */
    @Test
    void anUnknownResponseTypeIsRefusedByName() {
        MultivaluedMap<String, String> form = validForm();
        form.putSingle("responseType", "PIERRE-PAPIER-CISEAUX");
        assertRefused(new AdminMessageResource().saveMessage(form), "Type de réponse inconnu");
    }
}
