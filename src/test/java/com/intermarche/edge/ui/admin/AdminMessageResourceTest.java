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
    /** BO-03-08: a question-only creation persists an empty notice with its administered survey values. */
    @Test
    void createSurveyWithoutNoticeText() {
        var resource = new AdminMessageResource();
        var form = validForm();
        form.remove("text");
        form.putSingle("question", "Choose");
        form.putSingle("audience", "TERMINAL");
        form.putSingle("audienceValue", "C04");
        form.putSingle("startsAt", "20/10/2026 08:00");
        form.putSingle("endsAt", "20/10/2026 09:00");
        form.putSingle("durationSeconds", "15");
        try (var created = org.mockito.Mockito.mockConstruction(com.intermarche.edge.domain.setting.StoreMessage.class)) {
            assertTrue(resource.saveMessage(form).getLocation().toString().contains("noticeOk=true"));
            var message = created.constructed().getFirst();
            assertEquals("", message.text);
            assertEquals("Choose", message.question);
            assertEquals(15, message.durationSeconds);
            org.mockito.Mockito.verify(message).persist();
        }
    }

    /** BO-03-08: updating a known message can clear it; unknown ids and malformed enum values are explicit refusals. */
    @Test
    void updateAndUnknownMessageGuards() {
        var resource = new AdminMessageResource();
        var message = new com.intermarche.edge.domain.setting.StoreMessage();
        var form = validForm();
        form.putSingle("id", "8");
        form.remove("text");
        form.putSingle("durationSeconds", "bad");
        try (var base = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            base.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.findById(8L)).thenReturn(message);
            assertTrue(resource.saveMessage(form).getLocation().toString().contains("noticeOk=true"));
            assertEquals("", message.text);
            assertEquals(0, message.durationSeconds);
            form.putSingle("startsAt", "20/10/2026 08:00");
            assertTrue(resource.saveMessage(form).getLocation().toString().contains("noticeOk=true"));
            form.remove("startsAt");
            form.putSingle("endsAt", "20/10/2026 09:00");
            assertTrue(resource.saveMessage(form).getLocation().toString().contains("noticeOk=true"));
            form.putSingle("id", "9");
            assertRefused(resource.saveMessage(form), "Message inconnu");
        }
        form = validForm();
        form.putSingle("responseType", "UNKNOWN");
        assertRefused(resource.saveMessage(form), "Type de réponse inconnu");
        form.putSingle("id", "bad");
        assertRefused(resource.saveMessage(form), "Type de réponse inconnu");
    }

    /** BO-03-08: deletion rejects missing/unknown ids and removes acknowledgements before a known message. */
    @Test
    void deletionBranches() {
        var resource = new AdminMessageResource();
        var form = new MultivaluedHashMap<String, String>();
        assertRefused(resource.deleteMessage(form), "Message inconnu");
        var message = org.mockito.Mockito.mock(com.intermarche.edge.domain.setting.StoreMessage.class);
        try (var base = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            form.putSingle("id", "9");
            assertRefused(resource.deleteMessage(form), "Message inconnu");
            base.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.findById(9L)).thenReturn(message);
            assertTrue(resource.deleteMessage(form).getLocation().toString().contains("noticeOk=true"));
            base.verify(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.delete("messageId", 9L));
            org.mockito.Mockito.verify(message).delete();
        }
    }

    /** BO-03-08: message rows expose acknowledgements and all four combinations of diffusion bounds. */
    @Test
    void rowPeriodsAndPageProjection() {
        var message = new com.intermarche.edge.domain.setting.StoreMessage();
        message.id = 8L;
        var acks = java.util.List.<com.intermarche.edge.domain.setting.StoreMessageAck>of();
        var row = new AdminMessageResource.Row(message, acks);
        assertEquals(message, row.getMessage());
        assertEquals(acks, row.getAcks());
        assertEquals("—", row.getPeriod());
        message.startsAt = java.time.LocalDateTime.of(2026, 10, 20, 8, 0);
        assertEquals("20/10/2026 08:00 → ", row.getPeriod());
        message.endsAt = java.time.LocalDateTime.of(2026, 10, 20, 9, 0);
        assertEquals("20/10/2026 08:00 → 20/10/2026 09:00", row.getPeriod());
        message.startsAt = null;
        assertEquals(" → 20/10/2026 09:00", row.getPeriod());
        var resource = new AdminMessageResource();
        resource.adminMessages = org.mockito.Mockito.mock(io.quarkus.qute.Template.class);
        var view = org.mockito.Mockito.mock(io.quarkus.qute.TemplateInstance.class);
        org.mockito.Mockito.when(resource.adminMessages.data(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(view);
        org.mockito.Mockito.when(view.data(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(view);
        try (var messages = org.mockito.Mockito.mockStatic(com.intermarche.edge.domain.setting.StoreMessage.class);
             var confirmations = org.mockito.Mockito.mockStatic(com.intermarche.edge.domain.setting.StoreMessageAck.class)) {
            messages.when(com.intermarche.edge.domain.setting.StoreMessage::listAllOrdered).thenReturn(java.util.List.of(message));
            confirmations.when(() -> com.intermarche.edge.domain.setting.StoreMessageAck.listFor(8L)).thenReturn(acks);
            assertEquals(view, resource.messagesPage(null, "false"));
            assertEquals(view, resource.messagesPage(null, null));
        }
        org.mockito.Mockito.verify(view).data("noticeOk", false);
        org.mockito.Mockito.verify(view).data("noticeOk", true);
    }

    /** BO-03-08: a question without display text is persisted with empty text and missing numeric fields use defaults. */
    @Test
    void questionWithoutTextKeepsAnEmptyDisplayText() throws Exception {
        var posted = validForm();
        posted.remove("text");
        posted.putSingle("question", "QUESTION");
        AdminMessageResource resource = new AdminMessageResource();
        try (var created = org.mockito.Mockito.mockConstruction(com.intermarche.edge.domain.setting.StoreMessage.class)) {
            assertEquals(303, resource.saveMessage(posted).getStatus());
            assertEquals("", created.constructed().get(0).text);
        }
        var method = AdminMessageResource.class.getDeclaredMethod("intOr", String.class, int.class);
        method.setAccessible(true);
        assertEquals(20, method.invoke(resource, null, 20));
    }


    /** BO-03-08: a non-null notice text is kept verbatim after form trimming, independently of the question-only arm. */
    @Test
    void noticeTextIsPersistedWhenPresent() {
        var resource = new AdminMessageResource();
        try (var created = org.mockito.Mockito.mockConstruction(com.intermarche.edge.domain.setting.StoreMessage.class)) {
            assertTrue(resource.saveMessage(validForm()).getLocation().toString().contains("noticeOk=true"));
            assertEquals("FERMETURE A 19H", created.constructed().getFirst().text);
        }
    }

    /**
     * BO-06-03-01: the days ticked are joined, anything but an ISO day number
     * dropped, none read as every day; a time is read, absent or malformed as
     * none; a saved survey keeps them.
     */
    @Test
    void theDaysAndHoursOfASurvey() {
        assertEquals(null, AdminMessageResource.days(null));
        assertEquals(null, AdminMessageResource.days(java.util.Arrays.asList(null, "9", "x")));
        assertEquals("1;7", AdminMessageResource.days(java.util.List.of(" 1", "7")));
        assertEquals(null, AdminMessageResource.timeOf(null));
        assertEquals(null, AdminMessageResource.timeOf("midi"));
        assertEquals(java.time.LocalTime.of(9, 30), AdminMessageResource.timeOf(" 09:30 "));
        var form = validForm();
        form.put("day", java.util.List.of("2", "4"));
        form.putSingle("dailyFrom", "08:00");
        form.putSingle("dailyTo", "12:00");
        try (var created = org.mockito.Mockito.mockConstruction(com.intermarche.edge.domain.setting.StoreMessage.class)) {
            new AdminMessageResource().saveMessage(form);
            var message = created.constructed().getFirst();
            assertEquals("2;4", message.activeDays);
            assertEquals(java.time.LocalTime.of(8, 0), message.dailyFrom);
            assertEquals(java.time.LocalTime.of(12, 0), message.dailyTo);
        }
    }

    /**
     * Builds the upload of a postal-code list.
     *
     * @param id the survey's identifier, possibly null
     * @param file the file, possibly null
     * @return the form
     */
    private AdminMessageResource.PostalCodeForm upload(String id, java.io.InputStream file) {
        AdminMessageResource.PostalCodeForm form = new AdminMessageResource.PostalCodeForm();
        form.id = id;
        form.file = file;
        return form;
    }

    /**
     * BO-06-03-03: a list of postal codes imported for a survey is put in its
     * mask, duplicates removed, in the file's order.
     */
    @Test
    void aPostalCodeListIsImported() {
        var message = new com.intermarche.edge.domain.setting.StoreMessage();
        message.postalFormat = "CCCC-CCC";
        try (var base = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            base.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.findById(8L)).thenReturn(message);
            Response response = new AdminMessageResource().importPostalCodes(upload("8",
                    new java.io.ByteArrayInputStream("4100300\n4100-300;1000-001\n".getBytes())));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertEquals("4100-300;1000-001", message.postalCodes);
            assertRefused(new AdminMessageResource().importPostalCodes(upload("8",
                    new java.io.ByteArrayInputStream(new byte[0]))), "Fichier sans aucun code postal");
            java.io.InputStream broken = new java.io.InputStream() {
                /**
                 * {@inheritDoc}
                 *
                 * @return never
                 * @throws java.io.IOException always
                 */
                @Override
                public int read() throws java.io.IOException {
                    throw new java.io.IOException("coupé");
                }
            };
            assertRefused(new AdminMessageResource().importPostalCodes(upload("8", broken)),
                    "Fichier de codes postaux illisible");
            assertRefused(new AdminMessageResource().importPostalCodes(upload("9", null)), "Message inconnu");
        }
        assertRefused(new AdminMessageResource().importPostalCodes(null), "Message inconnu");
        assertRefused(new AdminMessageResource().importPostalCodes(upload("x", null)), "Message inconnu");
    }
}
