package com.intermarche.pos.domain.payment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the payment method keys to the concrete {@code TicketPayment} subclasses
 * they select. The keys are the JPA discriminator values of the single-table
 * hierarchy — the same strings the session reports and the sync payloads use —
 * so every surface names the methods identically.
 *
 * <p>The registry lives beside the entities it maps and NOT beside one of its
 * callers: the register reads it to know which methods it may offer
 * ({@code TenderRulesService}) and the store node reads it to filter the
 * journal (BO-04-01-06). Filed under either caller it would be code one product
 * carries for the other's sake.
 */
public final class PaymentTypes {

    /** The immutable key → entity-class registry, in display order. */
    private static final Map<String, Class<?>> BY_KEY = new LinkedHashMap<>();

    static {
        BY_KEY.put("CASH", CashPayment.class);
        BY_KEY.put("CARD", CardPayment.class);
        BY_KEY.put("CHEQUE", ChequePayment.class);
        BY_KEY.put("VOUCHER", VoucherPayment.class);
        BY_KEY.put("FIDELITY", FidelityPayment.class);
        BY_KEY.put("TR", TicketRestoPayment.class);
        BY_KEY.put("CREDIT", CreditPayment.class);
        BY_KEY.put("ARRONDI", RoundingPayment.class);
        BY_KEY.put("DEVISE", ForeignCurrencyPayment.class);
        BY_KEY.put("SECOURS", BackupPayment.class);
        // EXP-58/60/66/67: appended at the END (like every prior addition) so a
        // shop that bookmarked its journal-filter order is not shuffled.
        BY_KEY.put("TRD", TrdCardPayment.class);
        BY_KEY.put("RESTOFLASH", RestoFlashPayment.class);
        BY_KEY.put("GIFTCARD", SupplierGiftCardPayment.class);
        BY_KEY.put("SOGEC", SogecPayment.class);
        // LC-06-03-07: appended at the END, like every prior addition — the
        // deposit recovery of an order recalled at the till.
        BY_KEY.put("ACOMPTE", DepositPayment.class);
    }

    /**
     * Not instantiable.
     */
    private PaymentTypes() {
    }

    /**
     * Resolves a method key to its entity class.
     *
     * @param key the discriminator key, possibly unknown or null
     * @return the entity class, or null when the key is unknown
     */
    public static Class<?> forKey(String key) {
        return BY_KEY.get(key);
    }

    /**
     * Returns the known method keys in display order.
     *
     * @return the ordered key set
     */
    public static java.util.Set<String> keys() {
        return BY_KEY.keySet();
    }
}
