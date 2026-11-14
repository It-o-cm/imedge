package com.intermarche.edge.ui.journal;

import com.intermarche.edge.ui.admin.BackOfficeMessages;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link EventWording} (BO-04-01, BO-04-03-07, BO-04-03-14).
 */
class EventWordingTest {

    /** The labels the mocked bundles know, key to Dutch text. */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("t.s21-act-DRAWER_FORCE_OPEN", "Lade openen zonder verkoop"),
            Map.entry("t.s21-act-REFUND", "Terugbetaling"),
            Map.entry("t.s21-act-FORCE_PRICE", "Prijs forceren"),
            Map.entry("t.rs21-code-PRICE_MOD", "Prijswijziging"),
            Map.entry("t.x21-tender-CASH", "Contant"),
            Map.entry("t.x21-tender-CHEQUE", "Cheque"),
            Map.entry("t.rs21-target-article", "Artikel"),
            Map.entry("t.rs21-target-family", "Familie"),
            Map.entry("t.rs21-target-payment", "Betaalmiddel"),
            Map.entry("t.rs21-target-offline", "Kassa offline"),
            Map.entry("t.rs21-target-function", "Functie"),
            Map.entry("t.rs21-decision-YESNO", "ja/nee-vraag gesteld"),
            Map.entry("t.rs21-lock-MANUEL", "Handmatige vergrendeling"),
            Map.entry("t.rs21-close-CLOSED", "afgesloten"),
            Map.entry("t.x21-fs-period-DAY", "Dag"),
            Map.entry("t.x21-fs-period-WEEK", "Week"),
            Map.entry("t.rs21-back-office", "Back-office"),
            Map.entry("t.rs21-aval", "goedkeuring"),
            Map.entry("t.rs21-motif", "reden"),
            Map.entry("t.rs21-fond", "kassafonds"),
            Map.entry("t.rs21-connexion", "Aanmelding"),
            Map.entry("t.rs21-duplicata", "duplicaat nr."),
            Map.entry("p.rs21-store-close", "Geconsolideerde afsluiting van {0}: {1}"),
            Map.entry("p.fin-de-periode-du-x-x", "Einde periode van {0}: {1}"));

    /**
     * Dutch bundles: the known labels, any other key or code read as itself,
     * a few French sentences translated, amounts with a comma.
     *
     * @return the messages
     */
    private BackOfficeMessages messages() {
        BackOfficeMessages i18n = mock(BackOfficeMessages.class);
        when(i18n.get(anyString())).thenAnswer(call -> LABELS.getOrDefault(call.getArgument(0), call.getArgument(0)));
        when(i18n.code(anyString(), any())).thenAnswer(call -> {
            String code = String.valueOf((Object) call.getArgument(1));
            return LABELS.getOrDefault(call.getArgument(0) + code, code);
        });
        when(i18n.text(any())).thenAnswer(call -> String.valueOf((Object) call.getArgument(0)));
        when(i18n.text("Paramètres caisse")).thenReturn("Kassaparameters");
        when(i18n.text("Autorisation par Le Manager")).thenReturn("Toestemming door Le Manager");
        when(i18n.text("AUTORISATION SUPERVISEUR")).thenReturn("TOESTEMMING SUPERVISOR");
        when(i18n.text("Session du 25/10/2026")).thenReturn("Sessie van 25/10/2026");
        when(i18n.text("Refus par Le Manager — AUTORISATION SUPERVISEUR : Demande refusée par le back-office"))
                .thenReturn("Weigering door Le Manager — AUTORISATION SUPERVISEUR : Demande refusée par le back-office");
        when(i18n.text("Refus par Le Manager")).thenReturn("Weigering door Le Manager");
        when(i18n.text("Demande refusée par le back-office")).thenReturn("Verzoek geweigerd door de backoffice");
        when(i18n.text("Fermeture forcée de la caisse POS02 — back-office"))
                .thenReturn("Geforceerde sluiting van kassa POS02 — backoffice");
        when(i18n.money(any())).thenAnswer(call -> ((BigDecimal) call.getArgument(0))
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ','));
        return i18n;
    }

    /**
     * The wording a template reaches by its name, on its bundles.
     *
     * @return the wording
     */
    private EventWording wording() {
        EventWording wording = new EventWording();
        wording.i18n = messages();
        return wording;
    }

    /**
     * The back office's own events are filed under the back office, the
     * others under their register, an absent register read as empty.
     */
    @Test
    void terminalNamesTheBackOfficeForItsOwnEvents() {
        EventWording wording = wording();
        assertEquals("Back-office", wording.terminal("BACK_OFFICE_SIGNED_IN", "POS01"));
        assertEquals("Back-office", wording.terminal("BACK_OFFICE_MENU_OPENED", null));
        assertEquals("POS02", wording.terminal("TICKET_CLOSED", "POS02"));
        assertEquals("", wording.terminal("TICKET_CLOSED", null));
    }

    /**
     * A cancelled payment reads its method by its label, its amount kept.
     */
    @Test
    void paymentReadsTheMethod() {
        EventWording wording = wording();
        assertEquals("", wording.payment(null));
        assertEquals("Contant 1,00", wording.payment("CASH 1,00"));
        assertEquals("Contant", wording.payment("CASH"));
        assertEquals("cash 1,00", wording.payment("cash 1,00"));
    }

    /**
     * A code is worded: alert targets by their kind, upper-case codes by
     * their label, anything else as recorded.
     */
    @Test
    void codeIsWorded() {
        EventWording wording = wording();
        assertEquals("", wording.code("X", null));
        assertEquals("", wording.code("X", " "));
        assertEquals("Artikel 3300000000013", wording.code("CONTROL_ALERT", "article:3300000000013"));
        assertEquals("article:3300000000013 YESNO", wording.code("CONTROL_ALERT", "article:3300000000013 YESNO"));
        assertEquals("Prijswijziging", wording.code("JUSTIFICATION_CHOSEN", "PRICE_MOD"));
        assertEquals("Prijs forceren", wording.code("PRICE_MODIFIED", "FORCE_PRICE"));
        assertEquals("Contant", wording.code("PAYMENT_CANCELLED", "CASH"));
        assertEquals("NOPE", wording.code("X", "NOPE"));
        assertEquals("journal", wording.code("BACK_OFFICE_MENU_OPENED", "journal"));
    }

    /**
     * The recorded shapes are rebuilt from labels.
     */
    @Test
    void detailRebuildsTheKnownShapes() {
        EventWording wording = wording();
        assertEquals("", wording.detail("X", null));
        assertEquals("", wording.detail("X", "  "));
        assertEquals("Handmatige vergrendeling", wording.detail("REGISTER_LOCKED", "MANUEL"));
        assertEquals("manuel x", wording.detail("REGISTER_LOCKED", "manuel x"));
        assertEquals("BISCUITS — Prijs forceren", wording.detail("PRICE_MODIFIED", "BISCUITS FORCE_PRICE"));
        assertEquals("biscuits forcés", wording.detail("PRICE_MODIFIED", "biscuits forcés"));
        assertEquals("POS01-00000004 — Contant", wording.detail("PAYMENT_CANCELLED", "POS01-00000004 CASH"));
        assertEquals("POS01-00000004", wording.detail("PAYMENT_CANCELLED", "POS01-00000004"));
        assertEquals("LAIT CASH", wording.detail("TICKET_CLOSED", "LAIT CASH"));
        assertEquals("Lade openen zonder verkoop — mcurie", wording.detail("ENDORSEMENT_GRANTED", "DRAWER_FORCE_OPEN par mcurie"));
        assertEquals("Prijs forceren 1,50 — mcurie", wording.detail("ENDORSEMENT_GRANTED", "FORCE_PRICE 1.5 par mcurie"));
        assertEquals("Terugbetaling — mcurie", wording.detail("ENDORSEMENT_GRANTED", "REFUND_CASH_7 par mcurie"));
        assertEquals("Artikel 3300000000012 — ja/nee-vraag gesteld",
                wording.detail("CONTROL_ALERT", "article:3300000000012 YESNO"));
        assertEquals("Kassa offline", wording.detail("CONTROL_ALERT", "offline"));
        assertEquals("MON - Ajout de monnaie (goedkeuring mcurie)",
                wording.detail("DRAWER_FORCED_OPEN", "MON - Ajout de monnaie (aval mcurie)"));
        assertEquals("POS01-00000007 — Contant 6,00 — reden : Article défectueux",
                wording.detail("REFUND_CREATED", "POS01-00000007 CASH 6.00 MOTIF:Article défectueux"));
        assertEquals("POS01-00000007 — Contant 6,00", wording.detail("REFUND_CREATED", "POS01-00000007 CASH 6"));
        assertEquals("POS02-S00001 — kassafonds 100,00", wording.detail("SESSION_OPENED", "POS02-S00001 fond 100"));
        assertEquals("admin : Kassaparameters (/admin/settings)",
                wording.detail("BACK_OFFICE_MENU_OPENED", "admin : Paramètres caisse (/admin/settings)"));
        assertEquals("Aanmelding admin", wording.detail("BACK_OFFICE_SIGNED_IN", "connexion admin"));
        assertEquals("POS01-00000017 — duplicaat nr. 1", wording.detail("DUPLICATA_PRINTED", "POS01-00000017 n°1"));
        assertEquals("1234567890123 — 0,80", wording.detail("SOGEC_COMPENSATED", "1234567890123 0.80"));
        assertEquals("Geconsolideerde afsluiting van 25/10/2026: afgesloten",
                wording.detail("SESSION_CLOSED", "Clôture consolidée du point de vente du 2026-10-25 : CLOSED"));
        assertEquals("Einde periode van 25/10/2026: Dag, Week",
                wording.detail("SESSION_CLOSED", "Fin de période du 2026-10-25 : [DAY, , WEEK]"));
        assertEquals("Toestemming door Le Manager — TOESTEMMING SUPERVISOR Lade openen zonder verkoop",
                wording.detail("SUPERVISION_HANDLED",
                        "Autorisation par Le Manager — AUTORISATION SUPERVISEUR DRAWER_FORCE_OPEN"));
    }

    /**
     * Any other text goes through the bundles, its dates, decimals and
     * action codes written the way the page reads them.
     */
    @Test
    void detailNormalizesAnyOtherText() {
        EventWording wording = wording();
        assertEquals("Sessie van 25/10/2026", wording.detail("X", "Session du 2026-10-25"));
        assertEquals("Le 25/10/2026 14:05 : 2,50 et Prijs forceren puis UNKNOWN_CODE",
                wording.detail("X", "Le 2026-10-25T14:05:33.123 : 2.5 et FORCE_PRICE puis UNKNOWN_CODE"));
        assertEquals("LAIT UHT 1.5L", wording.detail("LINE_CANCELLED", "LAIT UHT 1.5L"));
        assertEquals("Weigering door Le Manager — TOESTEMMING SUPERVISOR : Verzoek geweigerd door de backoffice",
                wording.detail("SUPERVISION_HANDLED",
                        "Refus par Le Manager — AUTORISATION SUPERVISEUR : Demande refusée par le back-office"));
        assertEquals("Geforceerde sluiting van kassa POS02 — backoffice",
                wording.detail("SUPERVISOR_CALL", "Fermeture forcée de la caisse POS02 — back-office"));
        assertEquals("inconnu — X1 : autre", wording.detail("X", "inconnu — X1 : autre"));
    }

    /**
     * An alert's message is worded by its target; a message naming none is
     * left to the caller.
     */
    @Test
    void alertMessageWordsTheTarget() {
        BackOfficeMessages i18n = messages();
        assertNull(EventWording.alertMessage(i18n, null));
        assertNull(EventWording.alertMessage(i18n, "FORCE_PRICE 1"));
        assertEquals("Betaalmiddel Cheque — Chèque à contrôler",
                EventWording.alertMessage(i18n, "payment:CHEQUE — Chèque à contrôler"));
    }

    /**
     * A target is worded by its kind: a tender by its label, a family by its
     * code, anything else as administered.
     */
    @Test
    void targetWordsEachKind() {
        BackOfficeMessages i18n = messages();
        assertEquals("Betaalmiddel Cheque", EventWording.target(i18n, "payment", "cheque"));
        assertEquals("Betaalmiddel", EventWording.target(i18n, "payment", ""));
        assertEquals("Familie 1020", EventWording.target(i18n, "article", "F1020"));
        assertEquals("Familie 1020", EventWording.target(i18n, "article", "f1020"));
        assertEquals("Artikel F", EventWording.target(i18n, "article", "F"));
        assertEquals("Artikel Fx", EventWording.target(i18n, "article", "Fx"));
        assertEquals("Artikel A1", EventWording.target(i18n, "article", "A1"));
        assertEquals("Functie GET /drawer-open", EventWording.target(i18n, "function", "GET /drawer-open"));
        assertEquals("Kassa offline", EventWording.target(i18n, "offline", null));
    }

    /**
     * An action reads its label whole or by family, its code when none is known.
     */
    @Test
    void actionFallsBackOnTheFamilyThenTheCode() {
        BackOfficeMessages i18n = messages();
        assertEquals("Terugbetaling", EventWording.action(i18n, "REFUND"));
        assertEquals("Terugbetaling", EventWording.action(i18n, "REFUND_CASH_7"));
        assertEquals("NOPE", EventWording.action(i18n, "NOPE"));
        assertEquals("UNKNOWN_CODE", EventWording.action(i18n, "UNKNOWN_CODE"));
    }

    /**
     * A sentence's placeholders are filled in order.
     */
    @Test
    void fillInsertsTheValues() {
        assertEquals("a 1 b 2", EventWording.fill("a {0} b {1}", "1", "2"));
        assertEquals("none", EventWording.fill("none"));
    }
}
