package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.ui.admin.BackOfficeMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link CallWording} (BO-04-02-12, LC-01-05-08).
 */
class CallWordingTest {

    /**
     * Messages whose action labels are the given ones, any other code read as itself.
     *
     * @return the messages
     */
    private BackOfficeMessages messages() {
        BackOfficeMessages i18n = mock(BackOfficeMessages.class);
        when(i18n.code(anyString(), any())).thenAnswer(invocation -> String.valueOf((Object) invocation.getArgument(1)));
        when(i18n.code("t.s21-act-", "DRAWER_FORCE_OPEN")).thenReturn("Lade openen zonder verkoop");
        when(i18n.code("t.s21-act-", "FORCE_PRICE")).thenReturn("Prijs forceren");
        when(i18n.code("t.s21-act-", "PRINT_BADGE")).thenReturn("Badge afdrukken");
        when(i18n.text("ALERTE CAISSE")).thenReturn("KASSAWAARSCHUWING");
        when(i18n.text(null)).thenReturn("");
        when(i18n.get("t.rs21-target-payment")).thenReturn("Betaalmiddel");
        when(i18n.money(any())).thenAnswer(invocation -> ((java.math.BigDecimal) invocation.getArgument(0))
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ','));
        return i18n;
    }

    /**
     * An action code is read as its label, whole or by its family, its
     * figures formatted, an alert read by its target; any
     * other text is shown as sent.
     */
    @Test
    void gestureReadsTheActionCode() {
        BackOfficeMessages i18n = messages();
        assertEquals("", CallWording.gesture(i18n, null));
        assertEquals("Lade openen zonder verkoop", CallWording.gesture(i18n, "DRAWER_FORCE_OPEN"));
        assertEquals("Prijs forceren 1", CallWording.gesture(i18n, "FORCE_PRICE 1"));
        assertEquals("Prijs forceren 1,50", CallWording.gesture(i18n, "FORCE_PRICE 1.5"));
        assertEquals("Badge afdrukken", CallWording.gesture(i18n, "PRINT_BADGE_12341234"));
        assertEquals("UNKNOWN_GESTURE", CallWording.gesture(i18n, "UNKNOWN_GESTURE"));
        assertEquals("NOPE", CallWording.gesture(i18n, "NOPE"));
        assertEquals("Betaalmiddel CHEQUE — Paiement", CallWording.gesture(i18n, "payment:CHEQUE — Paiement"));
        assertEquals("Monnaie vite", CallWording.gesture(i18n, "Monnaie vite"));
    }

    /**
     * The reason is the French sentence translated.
     */
    @Test
    void reasonIsTranslated() {
        BackOfficeMessages i18n = messages();
        assertEquals("KASSAWAARSCHUWING", CallWording.reason(i18n, "ALERTE CAISSE"));
        assertEquals("", CallWording.reason(i18n, null));
    }
}
