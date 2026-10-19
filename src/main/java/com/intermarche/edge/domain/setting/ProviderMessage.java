package com.intermarche.edge.domain.setting;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.List;

/**
 * WHAT A PROVIDER SAYS, SAID IN THE SHOP'S WORDS (BO-03-08-06 and -07).
 *
 * <p>A third party answers in its own language: {@code ERROR CODE 4 ITEM} from
 * the dematerialised-goods provider, a settlement code from a gift-card issuer.
 * Showing that to a cashier is showing them someone else's log. This table is
 * the translation: for a provider and a message code, what the SCREEN says and
 * what the TICKET prints.
 *
 * <p>Two columns and not one, because the two are not the same sentence. The
 * screen tells a cashier what to do — "Produit indisponible, proposez un
 * autre montant" — and the ticket tells a customer what happened — "Carte
 * cadeau : 20,00 € réglés, solde 5,00 €". A row may carry one, the other, or
 * both; where it carries none, the provider's own text stands, which is the
 * behaviour of every register that has no such row.
 *
 * <p>Keyed on the PAIR provider plus code: two providers numbering their errors
 * from one are not saying the same thing with code 4.
 */
@Entity
@Table(name = "provider_messages",
        indexes = @Index(name = "idx_provider_message",
                columnList = "provider_code, message_code", unique = true))
public class ProviderMessage extends PanacheEntity {

    /** The provider the code comes from, as the providers screen names it. */
    @Column(name = "provider_code", nullable = false, length = 30)
    public String providerCode;

    /** The code the provider answers. */
    @Column(name = "message_code", nullable = false, length = 50)
    public String messageCode;

    /** What the cashier reads on the screen, or null to keep the provider's text. */
    @Column(name = "screen_text", length = 200)
    public String screenText;

    /** What the customer reads on the ticket, or null to print nothing. */
    @Column(name = "ticket_text", length = 200)
    public String ticketText;

    /** Whether the substitution is applied. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /**
     * The substitution for one provider code, or null when the shop declared
     * none.
     *
     * <p>Null and not an empty row: a caller that finds nothing keeps the
     * provider's own text, which is what a register did before this table
     * existed and what it must keep doing for a code nobody has translated.
     *
     * @param providerCode the provider, or null
     * @param messageCode the code it answered, or null
     * @return the substitution, or null when there is none
     */
    public static ProviderMessage findFor(String providerCode, String messageCode) {
        if (providerCode == null || messageCode == null) {
            return null;
        }
        return find("providerCode = ?1 and messageCode = ?2 and active = true",
                providerCode, messageCode).firstResult();
    }

    /**
     * What the cashier must read for one provider code.
     *
     * @param providerCode the provider
     * @param messageCode the code it answered
     * @param providerText the provider's own text, shown when nothing is declared
     * @return the sentence to show, never null when the provider's text is not
     */
    public static String screenTextFor(String providerCode, String messageCode,
            String providerText) {
        ProviderMessage substitution = findFor(providerCode, messageCode);
        if (substitution == null || substitution.screenText == null
                || substitution.screenText.isBlank()) {
            return providerText;
        }
        return substitution.screenText.trim();
    }

    /**
     * What the ticket must print for one provider code.
     *
     * <p>Null when the shop declared no ticket text, and null means NOTHING IS
     * PRINTED: a provider's technical answer has no business on a customer's
     * paper unless the shop wrote the sentence itself.
     *
     * @param providerCode the provider
     * @param messageCode the code it answered
     * @return the sentence to print, or null to print nothing
     */
    public static String ticketTextFor(String providerCode, String messageCode) {
        ProviderMessage substitution = findFor(providerCode, messageCode);
        if (substitution == null || substitution.ticketText == null
                || substitution.ticketText.isBlank()) {
            return null;
        }
        return substitution.ticketText.trim();
    }

    /**
     * Every declared substitution, by provider then by code.
     *
     * @return the whole table, in administration order
     */
    public static List<ProviderMessage> listAllOrdered() {
        return list("order by providerCode, messageCode");
    }
}
