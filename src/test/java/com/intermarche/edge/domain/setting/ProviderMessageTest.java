package com.intermarche.edge.domain.setting;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProviderMessage} (BO-03-08-06 and -07).
 *
 * <p>Branch enumeration: {@code findFor} with a null provider, a null code and
 * a pair that matches; {@code screenTextFor} with no row, a row whose screen
 * text is null, one whose text is blank and one that speaks — the three
 * fall-back legs answering the PROVIDER'S own text; {@code ticketTextFor} with
 * the same four, the three fall-back legs answering NULL instead, because
 * nothing is printed where the shop wrote nothing. The asymmetry between the
 * two is the point of the pair, and it is asserted rather than assumed.
 */
class ProviderMessageTest {

    /** The query finder of the pair lookup. */
    private static final String FINDER =
            "providerCode = ?1 and messageCode = ?2 and active = true";

    /**
     * A Panache query answering one row.
     *
     *
     * <p>Built with an ANSWER and not with {@code when(...)}: this helper is
     * called INSIDE the argument list of an outer {@code thenReturn(...)}, and a
     * second stubbing started while the first is unfinished is what Mockito
     * refuses with {@code UnfinishedStubbingException}.
     * @param result the row answered, or null for none
     * @return the stubbed query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<ProviderMessage> query(ProviderMessage result) {
        return mock(PanacheQuery.class, invocation ->
                "firstResult".equals(invocation.getMethod().getName()) ? result : null);
    }

    /**
     * A declared substitution.
     *
     * @param screen what the cashier reads, possibly null
     * @param ticket what the ticket prints, possibly null
     * @return the row
     */
    private ProviderMessage row(String screen, String ticket) {
        ProviderMessage message = new ProviderMessage();
        message.providerCode = "ILLICADO";
        message.messageCode = "4";
        message.screenText = screen;
        message.ticketText = ticket;
        message.active = true;
        return message;
    }

    /**
     * A null provider or a null code is looked up for nothing: the pair IS the
     * key, and half a key opens no row.
     */
    @Test
    void halfAKeyFindsNothing() {
        assertNull(ProviderMessage.findFor(null, "4"));
        assertNull(ProviderMessage.findFor("ILLICADO", null));
    }

    /**
     * The screen keeps the PROVIDER'S own text when nothing is declared, when
     * the row says nothing for the screen, and when what it says is blank —
     * the three legs that must not blank out a cashier's message.
     */
    @Test
    void theScreenKeepsTheProviderTextWhenNothingIsDeclared() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProviderMessage.find(FINDER, "ILLICADO", "4"))
                    .thenReturn(query(null));
            assertEquals("ERROR CODE 4 ITEM",
                    ProviderMessage.screenTextFor("ILLICADO", "4", "ERROR CODE 4 ITEM"));
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProviderMessage.find(FINDER, "ILLICADO", "4"))
                    .thenReturn(query(row(null, "sur le ticket")));
            assertEquals("ERROR CODE 4 ITEM",
                    ProviderMessage.screenTextFor("ILLICADO", "4", "ERROR CODE 4 ITEM"));
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProviderMessage.find(FINDER, "ILLICADO", "4"))
                    .thenReturn(query(row("   ", null)));
            assertEquals("ERROR CODE 4 ITEM",
                    ProviderMessage.screenTextFor("ILLICADO", "4", "ERROR CODE 4 ITEM"));
        }
    }

    /**
     * A declared screen text replaces the provider's, trimmed.
     */
    @Test
    void aDeclaredScreenTextReplacesTheProviderOne() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProviderMessage.find(FINDER, "ILLICADO", "4"))
                    .thenReturn(query(row("  Produit indisponible  ", null)));
            assertEquals("Produit indisponible",
                    ProviderMessage.screenTextFor("ILLICADO", "4", "ERROR CODE 4 ITEM"));
        }
    }

    /**
     * THE TICKET IS THE OTHER WAY ROUND: nothing declared means NOTHING
     * PRINTED, not the provider's text. A third party's technical answer has no
     * business on a customer's paper unless the shop wrote the sentence.
     */
    @Test
    void theTicketPrintsNothingWhenNothingIsDeclared() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProviderMessage.find(FINDER, "ILLICADO", "4"))
                    .thenReturn(query(null));
            assertNull(ProviderMessage.ticketTextFor("ILLICADO", "4"));
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProviderMessage.find(FINDER, "ILLICADO", "4"))
                    .thenReturn(query(row("a l'ecran", null)));
            assertNull(ProviderMessage.ticketTextFor("ILLICADO", "4"));
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProviderMessage.find(FINDER, "ILLICADO", "4"))
                    .thenReturn(query(row(null, "  ")));
            assertNull(ProviderMessage.ticketTextFor("ILLICADO", "4"));
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProviderMessage.find(FINDER, "ILLICADO", "4"))
                    .thenReturn(query(row(null, " Carte cadeau : solde 5,00 EUR ")));
            assertEquals("Carte cadeau : solde 5,00 EUR",
                    ProviderMessage.ticketTextFor("ILLICADO", "4"));
        }
    }
}
