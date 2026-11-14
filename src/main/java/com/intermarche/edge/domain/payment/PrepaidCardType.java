package com.intermarche.edge.domain.payment;

import com.intermarche.edge.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Objects;

/**
 * A TYPE OF PREPAID CARD — a gift card, a gift box, a supplier card —
 * administered in the back office and distributed to the registers
 * ({@code BO-03-02-37}).
 *
 * <p>A type says what a card IS before anything is asked of it: the
 * PROTOCOL it is spoken in ({@link #PROTOCOL_NEXO} through the third-party
 * payment hub, {@link #PROTOCOL_LOCAL} by the register's own registry), the
 * PROVIDER that issues it, the RANGES its numbers fall in, and which of the
 * three gestures the register may perform on it — paying with it, consulting
 * its balance, activating it at the sale.
 *
 * <p>The ranges are written {@code prefix} or {@code prefix:length},
 * separated by semicolons ({@code 6299;296:13}): a number belongs to the type
 * when it starts with one of the prefixes and, where a length is given, has
 * exactly that many characters. When two types claim the same number, the
 * longest prefix wins — the narrowest range is the most deliberate one.
 */
@Entity
@Table(name = "prepaid_card_types",
        indexes = {
                @Index(name = "idx_prepaid_card_type_code", columnList = "code")
        }
)
public class PrepaidCardType extends BaseEntity {

    /** The protocol of a card spoken through the third-party payment hub. */
    public static final String PROTOCOL_NEXO = "NEXO";

    /** The protocol of a card the register handles in its own registry. */
    public static final String PROTOCOL_LOCAL = "LOCAL";

    /** The protocols a type may name. */
    public static final List<String> PROTOCOLS = List.of(PROTOCOL_NEXO, PROTOCOL_LOCAL);

    /** The type's code, unique, the key the distribution upserts on. */
    @Column(name = "code", nullable = false, unique = true, length = 40)
    @NotBlank
    public String code;

    /** What the type is called in front of the cashier. */
    @Column(name = "label", nullable = false, length = 80)
    @NotBlank
    public String label;

    /** The protocol, {@link #PROTOCOL_NEXO} or {@link #PROTOCOL_LOCAL}. */
    @Column(name = "protocol", nullable = false, length = 16)
    @NotBlank
    public String protocol = PROTOCOL_NEXO;

    /**
     * The provider issuing the cards, named to the hub at activation, or null
     * when the hub is left to name it.
     */
    @Column(name = "provider", length = 40)
    public String provider;

    /** The ranges, {@code prefix} or {@code prefix:length}, semicolon-separated. */
    @Column(name = "ranges", nullable = false, length = 500)
    @NotBlank
    public String ranges;

    /** Whether a card of this type may settle a sale. */
    @Column(name = "payment_allowed", nullable = false)
    public boolean paymentAllowed = true;

    /** Whether the balance of a card of this type may be consulted. */
    @Column(name = "balance_allowed", nullable = false)
    public boolean balanceAllowed = true;

    /** Whether a card of this type may be activated at the sale. */
    @Column(name = "activation_allowed", nullable = false)
    public boolean activationAllowed = true;

    /** The rank the type is listed at, lowest first. */
    @Column(name = "display_order", nullable = false)
    public int displayOrder;

    /** Whether the register knows the type at all. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /**
     * Finds a type by its code.
     *
     * @param code the code looked for, possibly null
     * @return the type, or null when no type carries that code
     */
    public static PrepaidCardType findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return find("code", code.trim()).firstResult();
    }

    /**
     * Lists every administered type, active or not, in display order.
     *
     * @return the types, possibly empty
     */
    public static List<PrepaidCardType> findAllOrdered() {
        return list("order by displayOrder, code");
    }

    /**
     * Finds the ACTIVE type a card number belongs to, the longest matching
     * prefix winning.
     *
     * @param number the card number, possibly null
     * @return the type, or null when no active type claims the number
     */
    public static PrepaidCardType findFor(String number) {
        if (number == null || number.isBlank()) {
            return null;
        }
        String card = number.trim();
        PrepaidCardType best = null;
        int bestLength = -1;
        for (PrepaidCardType type : PrepaidCardType.<PrepaidCardType>list(
                "active = true order by displayOrder, code")) {
            int length = type.matchLength(card);
            if (length > bestLength) {
                best = type;
                bestLength = length;
            }
        }
        return best;
    }

    /**
     * Tells how long the prefix of this type matching a number is.
     *
     * @param number the trimmed card number, never null
     * @return the length of the longest matching prefix, or -1 when no range matches
     */
    public int matchLength(String number) {
        int best = -1;
        if (ranges == null) {
            return best;
        }
        for (String raw : ranges.split(";")) {
            String entry = raw.trim();
            int colon = entry.indexOf(':');
            String prefix = colon < 0 ? entry : entry.substring(0, colon).trim();
            if (prefix.isEmpty() || !number.startsWith(prefix)) {
                continue;
            }
            if (colon >= 0 && !lengthMatches(entry.substring(colon + 1).trim(), number)) {
                continue;
            }
            best = Math.max(best, prefix.length());
        }
        return best;
    }

    /**
     * Tells whether a number has the length a range entry states.
     *
     * @param length the stated length, as written
     * @param number the card number
     * @return true when the length is readable and equal to the number's
     */
    private static boolean lengthMatches(String length, String number) {
        try {
            return Integer.parseInt(length) == number.length();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Tells whether the type is spoken through the third-party hub.
     *
     * @return true for the NEXO protocol
     */
    public boolean isNexo() {
        return PROTOCOL_NEXO.equalsIgnoreCase(protocol);
    }

    /**
     * Hashes the business fields, for the change detection of the base entity.
     *
     * @return the checksum of this row
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, label, protocol, provider, ranges, paymentAllowed,
                balanceAllowed, activationAllowed, displayOrder, active);
    }
}
