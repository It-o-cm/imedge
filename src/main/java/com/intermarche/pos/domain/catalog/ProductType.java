package com.intermarche.pos.domain.catalog;

/**
 * Enumeration representing how a product is quantified and sold.
 * Distinguishing between weighted items and unit items is critical
 * for supermarket Point of Sale (POS) systems.
 * <p>
 * Sale-flow consequences: UNIT lines display "x n", can merge (when
 * unmodified, same price) and accept the line-quantity edition; WEIGHT
 * lines price per kilogram, display "n,nnn kg", never merge (one weighing =
 * one line) and refuse the quantity edition — their quantity IS the weight,
 * set by the scale, the fruits screen or an embedded-weight 2x label.
 */
public enum ProductType {

    /**
     * Sold by a single unit (e.g., a cereal box, a toothbrush).
     * The barcode scan directly adds one item to the cart.
     */
    UNIT,

    /**
     * Sold by weight (e.g., fruits, vegetables, deli meat).
     * Typically requires a scale-generated barcode or manual weight entry.
     */
    WEIGHT,

    /**
     * Sold by volume (e.g., bulk liquids, fuel).
     */
    VOLUME
}