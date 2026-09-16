package com.intermarche.pos.domain.barcode;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Describes a known type of voucher (gift voucher, loyalty cheque, Catalina, etc.).
 * <p>
 * At scan or manual entry time, a voucher number is matched against each active
 * type (ordered by {@link #priority}) to determine its type and, when possible,
 * its amount.
 * <p>
 * A type is ADMINISTERED when {@link #codeLength} is set: the back office then
 * states the range in plain terms — a literal {@link #prefix}, a total length, a
 * character kind and a list of {@link CouponField} positions — and
 * {@code CouponPatternService} GENERATES {@link #matchPattern} and
 * {@link #amountPattern} from them. A type left without a code length keeps the
 * hand-written patterns it was loaded with, which is what lets the two
 * generations coexist.
 */
@Entity
@Table(name = "coupon_type")
public class CouponType extends PanacheEntity {

    /**
     * The stable technical code of the type (for example, CATALINA, GIFT_VOUCHER).
     */
    @Column(name = "code", unique = true, nullable = false, length = 50)
    public String code;

    /**
     * The human-readable label shown to the cashier (for example, "Chèque cadeau").
     */
    @Column(name = "label", nullable = false, length = 100)
    public String label;

    /**
     * The regular expression a voucher number must match to be recognized as this type.
     * <p>
     * Also used in manual entry to detect typing mistakes: a number matching no pattern
     * is rejected.
     */
    @Column(name = "match_pattern", nullable = false, length = 255)
    public String matchPattern;

    /**
     * Indicates whether the amount is encoded in the number or must be entered manually.
     */
    @Column(name = "amount_source", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    public AmountSource amountSource;

    /**
     * The regular expression used to extract the amount from the number when
     * {@link #amountSource} is {@link AmountSource#ENCODED}. The first capturing group
     * must hold the amount in cents. Ignored for {@link AmountSource#MANUAL}.
     */
    @Column(name = "amount_pattern", length = 255)
    public String amountPattern;

    /**
     * Whether this type is currently active and should be considered at scan time.
     */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /**
     * Distinguishes deposit-return vouchers from payment vouchers.
     * <p>
     * When true, a scanned voucher of this type adds a negative line to the
     * ticket (déconsigne) instead of registering a payment; such a type must
     * carry its amount encoded ({@link AmountSource#ENCODED}). The two
     * families never mix: payment resolution and the payment panel only see
     * non-deposit types, the deposit scan handler only sees deposit types.
     */
    @Column(name = "deposit_line", nullable = false)
    public boolean depositLine = false;

    /**
     * The order in which types are tested against a number; lower runs first.
     */
    @Column(name = "priority", nullable = false)
    public int priority = 100;

    /**
     * The literal head every code of the range carries, or null when the range
     * has none (BO-03-06-02).
     */
    @Column(name = "prefix", length = 40)
    public String prefix;

    /**
     * The total number of characters of a code of the range, or null when the
     * range is not administered and keeps its hand-written patterns
     * (BO-03-06-10).
     */
    @Column(name = "code_length")
    public Integer codeLength;

    /**
     * The characters the range accepts outside its administered fields
     * (BO-03-06-09).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "code_kind", length = 16)
    public CouponField.Kind codeKind = CouponField.Kind.NUMERIC;

    /**
     * Whether a price made ENTIRELY of nines means "amount unknown, ask the
     * cashier" (BO-03-06-12). A voucher printed before its value was known
     * carries 9999 where its amount would be; without this flag the register
     * would settle it for 99,99 €. Off, an all-nines price is an ordinary
     * amount like any other.
     */
    @Column(name = "manual_amount_on_all_nines", nullable = false)
    public boolean manualAmountOnAllNines = false;

    /**
     * The checkout islands accepting this range, as a semicolon list of island
     * codes (BO-03-06-07). Empty: every island accepts it, which is what a
     * store that never split its lanes wants.
     */
    @Column(name = "island_codes", length = 1000)
    public String islandCodes;

    /**
     * The administered positions of the range, ordered by offset.
     */
    @OneToMany(mappedBy = "couponType", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("offsetPosition")
    public List<CouponField> fields = new ArrayList<>();

    /**
     * The administered controls of the range, ordered by kind.
     */
    @OneToMany(mappedBy = "couponType", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("kind")
    public List<CouponControl> controls = new ArrayList<>();

    /**
     * Indicates whether the range is administered, and therefore whether its
     * patterns are generated rather than hand-written.
     *
     * @return true when a code length is administered
     */
    public boolean isAdministered() {
        return codeLength != null && codeLength > 0;
    }

    /**
     * Returns the administered field carrying that role.
     *
     * @param role the role to look for, or null
     * @return the field, or null when the range administers none for that role
     */
    public CouponField fieldOf(CouponField.Role role) {
        if (role == null || fields == null) {
            return null;
        }
        for (CouponField field : fields) {
            if (field != null && field.role == role) {
                return field;
            }
        }
        return null;
    }

    /**
     * Returns the administered control of that kind.
     *
     * @param kind the control to look for, or null
     * @return the control, or null when the range administers none of that kind
     */
    public CouponControl controlOf(CouponControl.Kind kind) {
        if (kind == null || controls == null) {
            return null;
        }
        for (CouponControl control : controls) {
            if (control != null && control.kind == kind) {
                return control;
            }
        }
        return null;
    }

    /**
     * Returns the level the range administers for a control, silence being the
     * level of a control nobody administered.
     *
     * @param kind the control to look for, or null
     * @return the administered level, or {@link AlertLevel#NONE}
     */
    public AlertLevel levelOf(CouponControl.Kind kind) {
        CouponControl control = controlOf(kind);
        return control == null || control.level == null ? AlertLevel.NONE : control.level;
    }

    /**
     * Tells whether this range is accepted on the given checkout island
     * (BO-03-06-07).
     *
     * <p>A range naming no island is accepted everywhere; a range naming some
     * is accepted only there, and a register belonging to no island is then
     * outside the list like any other.
     *
     * @param islandCode the code of the island the register belongs to, or null
     * @return true when the range may be used on that island
     */
    public boolean acceptedOnIsland(String islandCode) {
        if (islandCodes == null || islandCodes.isBlank()) {
            return true;
        }
        if (islandCode == null || islandCode.isBlank()) {
            return false;
        }
        String wanted = islandCode.trim();
        for (String raw : islandCodes.split(";")) {
            if (wanted.equals(raw.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the literal head of the range, never null.
     *
     * @return the prefix, or the empty string when the range has none
     */
    public String effectivePrefix() {
        return prefix == null ? "" : prefix;
    }

    /**
     * Tests whether the given voucher number is recognized as this type.
     *
     * @param number the voucher number, scanned or typed
     * @return true if the number matches this type's pattern
     */
    public boolean matches(String number) {
        if (number == null || matchPattern == null) {
            return false;
        }
        return number.matches(matchPattern);
    }

    /**
     * Indicates whether the cashier must enter the amount for this type.
     *
     * @return true if the amount cannot be derived from the number
     */
    public boolean requiresManualAmount() {
        return amountSource == AmountSource.MANUAL;
    }

    /**
     * Indicates whether the cashier must enter the amount for a GIVEN code:
     * either the range never encodes one, or the code carries the all-nines
     * price that means "value not yet known" (BO-03-06-12).
     *
     * @param number the voucher number, scanned or typed, or null
     * @return true when the amount cannot be read off this code
     */
    public boolean requiresManualAmount(String number) {
        return requiresManualAmount() || hasAllNinesPrice(number);
    }

    /**
     * Tells whether the administered price position of this code is made
     * entirely of nines, which the range may have declared to mean "ask the
     * cashier" (BO-03-06-12).
     *
     * <p>Silent unless the range both administers the flag and administers a
     * {@link CouponField.Role#PRICE} position: without a position there is no
     * price to look at, and a range that did not ask for the rule never gets
     * it.
     *
     * @param number the voucher number, or null
     * @return true when the code carries an all-nines price on a range asking for it
     */
    public boolean hasAllNinesPrice(String number) {
        if (!manualAmountOnAllNines || number == null) {
            return false;
        }
        CouponField price = fieldOf(CouponField.Role.PRICE);
        if (price == null) {
            return false;
        }
        String raw = price.raw(number);
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) != '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Indicates whether this type carries a voucher number.
     * <p>
     * A type without a match pattern represents a numberless voucher (generic /
     * ephemeral coupon) for which only an amount is entered.
     *
     * @return true if a number is expected for this type
     */
    public boolean hasNumber() {
        return matchPattern != null && !matchPattern.isBlank();
    }

    /**
     * Extracts the amount encoded in the given number, when applicable.
     *
     * <p>An administered range reads the amount POSITIONALLY, from its
     * {@link CouponField.Role#PRICE} field and with the decimal count that
     * field carries; the pattern is then only there to recognise the code. A
     * range without that field falls back to the hand-written
     * {@link #amountPattern}, whose first capturing group holds cents.
     *
     * @param number the voucher number
     * @return the extracted amount, or null if the amount is not encoded or cannot be read
     */
    public BigDecimal extractAmount(String number) {
        if (amountSource != AmountSource.ENCODED || number == null) {
            return null;
        }
        CouponField price = fieldOf(CouponField.Role.PRICE);
        if (price != null) {
            return price.decimalValue(number);
        }
        if (amountPattern == null) {
            return null;
        }
        Matcher matcher = Pattern.compile(amountPattern).matcher(number);
        if (!matcher.find() || matcher.groupCount() < 1) {
            return null;
        }
        try {
            long cents = Long.parseLong(matcher.group(1));
            return BigDecimal.valueOf(cents, 2);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns the active coupon types ordered by ascending priority,
     * regardless of their kind.
     *
     * @return the ordered list of active types
     */
    public static List<CouponType> listActiveByPriority() {
        return list("active = true order by priority");
    }

    /**
     * Returns the active payment voucher types (deposit-return types excluded)
     * ordered by ascending priority.
     *
     * @return the ordered list of active payment types
     */
    public static List<CouponType> listActivePaymentTypes() {
        return list("active = true and depositLine = false order by priority");
    }

    /**
     * Returns the active deposit-return voucher types ordered by ascending
     * priority.
     *
     * @return the ordered list of active deposit-return types
     */
    public static List<CouponType> listActiveDepositTypes() {
        return list("active = true and depositLine = true order by priority");
    }

    /**
     * Returns the active voucher type carrying that code.
     *
     * <p>Used where the back office names a type by its code rather than by a scanned
     * number — a GS1 gift document or coupon, whose identifier says what the paper is
     * and leaves the store to say which of its own settlement types that is
     * ({@code LC-11-03-05/06}).
     *
     * @param code the type's code, as administered
     * @return the type, or null when no ACTIVE type carries that code
     */
    public static CouponType findActiveByCode(String code) {
        return code == null || code.isBlank()
                ? null
                : find("code = ?1 and active = true", code.trim()).firstResult();
    }

    /**
     * Defines how the monetary value of a voucher is obtained.
     */
    public enum AmountSource {

        /**
         * The amount is encoded within the voucher number and can be extracted from it.
         */
        ENCODED,

        /**
         * The amount comes from the STORED-VALUE REGISTRY: the number is a
         * pure identifier, the registry holds the live balance and the
         * redemption applies min(balance, remaining due) — credit notes and
         * gift cards (phase: credit notes & gift cards).
         */
        REGISTRY,

        /**
         * The amount cannot be derived from the number and must be entered by the cashier
         * (for example, Catalina coupons).
         */
        MANUAL
    }
}
