package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.catalog.VatRate;
import com.intermarche.pos.domain.sale.VatBreakdown;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the two FOOT-OF-DOCUMENT blocks of
 * {@link TicketPrinterService}: the VAT legal mentions
 * ({@code BO-02-02-02}) and the administered web address
 * ({@code BO-03-05-08}).
 *
 * <p>Both are pure string builders over a mocked {@link PosSettingsService}
 * and, for the mentions, the custom static finder of {@link VatRate}
 * intercepted with {@code mockStatic}. No ticket, no hardware, no database.
 *
 * <p>Branch enumeration (both arms): a regime with a mention, one without,
 * one whose mention is blank, a rate no regime carries, and two regimes
 * sharing one mention; the four address formats plus an unknown one, a blank
 * address, and a printer built with no settings at all.
 */
class TicketPrinterFooterTest {

    /**
     * Builds a printer whose settings answer the given address and format.
     *
     * @param url the administered address, or null to leave it unstubbed
     * @param format the administered format, or null
     * @return the printer under test
     */
    private TicketPrinterService printer(String url, String format) {
        TicketPrinterService service = new TicketPrinterService();
        service.i18n = TestMessages.french();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.ticketFooterUrl()).thenReturn(url);
        when(service.posSettingsService.ticketFooterUrlFormat()).thenReturn(format);
        return service;
    }

    /**
     * Builds a ventilation over one or two rates.
     *
     * @param rates the rates to add, each for one euro
     * @return the ventilation
     */
    private VatBreakdown breakdown(String... rates) {
        VatBreakdown breakdown = new VatBreakdown();
        for (String rate : rates) {
            breakdown.add(new BigDecimal(rate), new BigDecimal("1.00"));
        }
        return breakdown;
    }

    /**
     * Builds a regime carrying a legal mention.
     *
     * @param mention the mention, possibly null or blank
     * @return the regime
     */
    private VatRate regime(String mention) {
        VatRate regime = new VatRate();
        regime.legalMention = mention;
        return regime;
    }

    /**
     * BO-02-02-02: the mention of each regime present is printed, once, in
     * the order of the ventilation.
     */
    @Test
    void eachMentionIsPrintedOnce() {
        TicketPrinterService service = printer("", "");
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class)) {
            rates.when(() -> VatRate.findByRate(new BigDecimal("0.2000")))
                    .thenReturn(regime("TVA sur les débits"));
            rates.when(() -> VatRate.findByRate(new BigDecimal("0.0550")))
                    .thenReturn(regime("Autoliquidation"));
            String printed = service.vatLegalMentions(breakdown("0.2000", "0.0550"));
            assertTrue(printed.contains("TVA sur les débits"));
            assertTrue(printed.contains("Autoliquidation"));
            assertEquals(2, printed.split("\n").length);
        }
    }

    /**
     * BO-02-02-02: two regimes naming the SAME sentence print it once, and a
     * regime whose mention is null or blank prints nothing.
     */
    @Test
    void oneMentionSharedIsPrintedOnce() {
        TicketPrinterService service = printer("", "");
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class)) {
            rates.when(() -> VatRate.findByRate(new BigDecimal("0.2000")))
                    .thenReturn(regime("Même phrase"));
            rates.when(() -> VatRate.findByRate(new BigDecimal("0.0550")))
                    .thenReturn(regime(" Même phrase "));
            assertEquals("Même phrase\n", service.vatLegalMentions(breakdown("0.2000", "0.0550")));
            rates.when(() -> VatRate.findByRate(new BigDecimal("0.0550")))
                    .thenReturn(regime("   "));
            rates.when(() -> VatRate.findByRate(new BigDecimal("0.2000")))
                    .thenReturn(regime(null));
            assertEquals("", service.vatLegalMentions(breakdown("0.2000", "0.0550")));
        }
    }

    /**
     * BO-02-02-02: a rate no regime carries obliges nothing, and an empty
     * ventilation prints nothing at all.
     */
    @Test
    void anUnknownRateObligesNothing() {
        TicketPrinterService service = printer("", "");
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class)) {
            rates.when(() -> VatRate.findByRate(new BigDecimal("0.2000"))).thenReturn(null);
            assertEquals("", service.vatLegalMentions(breakdown("0.2000")));
            assertEquals("", service.vatLegalMentions(breakdown()));
        }
    }

    /**
     * BO-03-05-08: the administered address is emitted as a QR directive by
     * default, as a bar code when the shop asks, and written out in clear
     * when it asks for text.
     */
    @Test
    void theAddressIsRenderedAsTheShopAsked() {
        assertEquals("[[QR https://magasin.fr/avis]]\n",
                printer("https://magasin.fr/avis", "QR").footerUrlBlock());
        assertEquals("[[BARCODE https://magasin.fr/avis]]\n",
                printer("https://magasin.fr/avis", "BARCODE").footerUrlBlock());
        assertTrue(printer("https://magasin.fr/avis", "TEXTE").footerUrlBlock()
                .contains("https://magasin.fr/avis"));
    }

    /**
     * BO-03-05-08: AUCUN prints nothing even with an address, a blank
     * address prints nothing whatever the format, and a word this version
     * does not know falls back on the QR code rather than on silence.
     */
    @Test
    void nothingIsPrintedWhenNothingWasAsked() {
        assertEquals("", printer("https://magasin.fr", "AUCUN").footerUrlBlock());
        assertEquals("", printer("   ", "QR").footerUrlBlock());
        assertEquals("", printer(null, "QR").footerUrlBlock());
        assertEquals("[[QR https://magasin.fr]]\n",
                printer("https://magasin.fr", "AUTRE").footerUrlBlock());
        assertEquals("[[QR https://magasin.fr]]\n",
                printer("https://magasin.fr", null).footerUrlBlock());
    }

    /**
     * A printer built by hand with no settings at all prints no address
     * rather than failing: the receipt must print.
     */
    @Test
    void aPrinterWithoutSettingsPrintsNoAddress() {
        TicketPrinterService service = new TicketPrinterService();
        assertEquals("", service.footerUrlBlock());
    }
}
