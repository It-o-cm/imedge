package com.intermarche.pos.domain.catalog.attribute;

/**
 * Value type of a declared product attribute (BO-02-03-18).
 * <p>
 * The type drives how a raw text value stored in {@link
 * com.intermarche.pos.domain.catalog.Product#attributes} is parsed by {@link
 * ProductAttributes} and which widget the back-office article form renders.
 * A product attribute is always persisted as text — the type is the contract
 * telling both ends how to read it — mirroring the typed-catalog doctrine of
 * {@code PosSettingsService.Type}.
 */
public enum ProductAttributeType {

    /** A boolean flag, parsed with {@link Boolean#parseBoolean(String)}. */
    BOOL,

    /** An integer value. */
    INT,

    /** A decimal value carried as {@link java.math.BigDecimal}. */
    DECIMAL,

    /** A free text value. */
    TEXT
}
