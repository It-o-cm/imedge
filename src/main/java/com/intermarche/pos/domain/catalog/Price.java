package com.intermarche.pos.domain.catalog;

import com.intermarche.pos.domain.util.DateTimeProvider;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import com.intermarche.pos.domain.BaseEntity;

/**
 * Entity representing a Price for a specific Product.
 * <p>
 * Prices are time-sensitive. The validity is defined by an interval [start, end).
 * Both start and end dates can be null, representing the beginning and end of time respectively.
 * A priority indicator is used to resolve overlaps: a higher priority overrides a lower one.
 * <p>
 * Resolution semantics ({@code findCurrentPrice}): candidate rows are those
 * whose window contains "now" (null bounds meaning ±infinity), ordered by
 * priority descending, first one wins — a promotion is therefore a
 * higher-priority row overlaying the base price for its window, no deletion
 * involved. "Now" comes from {@code DateTimeProvider}, so tests can pin the
 * resolution inside or outside a window. Amounts are scale 4; the VAT rate
 * is a fraction (0.0550), captured on the ticket line at scan time — a later
 * price change never rewrites sold lines. Centralized referentials replace
 * the whole price table on change (nothing references price rows: lines
 * snapshot their values).
 * <p>
 * This class extends {@link BaseEntity} to inherit ID, versioning,
 * and audit fields.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "prices",
        indexes = {
                @Index(name = "idx_price_validity", columnList = "product_id, start_date_time, end_date_time")
        }
)
@Cacheable
public class Price extends BaseEntity {

    // --------------------------------------------------
    // Relations
    // --------------------------------------------------

    /**
     * The product this price applies to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @NotNull(message = "Product is mandatory")
    public Product product;

    // --------------------------------------------------
    // Pricing Details
    // --------------------------------------------------

    /**
     * Price Excluding Tax (Prix HT).
     * High precision (scale=4) to avoid rounding errors during tax calculations.
     */
    @Column(name = "price_excluding_tax", nullable = false, precision = 19, scale = 4)
    @NotNull(message = "Price Excluding Tax is mandatory")
    @PositiveOrZero(message = "Price must be positive")
    public BigDecimal priceExcludingTax;

    /**
     * Price Including Tax (Prix TTC).
     */
    @Column(name = "price_including_tax", nullable = false, precision = 19, scale = 4)
    @NotNull(message = "Price Including Tax is mandatory")
    @PositiveOrZero(message = "Price must be positive")
    public BigDecimal priceIncludingTax;

    /**
     * VAT Rate (Taux de TVA) applied to this price (e.g., 0.2000 for 20%).
     */
    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 4)
    @NotNull(message = "VAT Rate is mandatory")
    @PositiveOrZero(message = "VAT Rate must be positive")
    public BigDecimal vatRate;

    // --------------------------------------------------
    // Priority & Validity
    // --------------------------------------------------

    /**
     * Priority of this price.
     * Used when multiple prices are valid for the same product at the same time.
     * The price with the higher priority value should be selected.
     * Default is 0 (Standard price).
     */
    @Column(name = "priority", nullable = false)
    public Integer priority = 0;

    /**
     * Start date and time of the price validity.
     * If null, the price is valid from the beginning of time.
     */
    @Column(name = "start_date_time")
    public LocalDateTime startDateTime;

    /**
     * End date and time of the price validity.
     * If null, the price is valid indefinitely (until end of time).
     */
    @Column(name = "end_date_time")
    public LocalDateTime endDateTime;

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds the valid price for a specific product, store, date, and usage.
     * <p>
     * Validity logic:
     * <ul>
     *   <li>Start: Must be null OR <= targetDate.</li>
     *   <li>End: Must be null OR > targetDate.</li>
     * </ul>
     * If multiple prices match the criteria, the one with the HIGHEST priority is returned.
     *
     * @param productId The ID of the product
     * @param date      The date to check validity against
     * @return The active Price with the highest priority, or null
     */
    public static Price findActivePriceAtDate(Long productId, LocalDateTime date) {
        // We use a query that handles null dates by treating them as +/- infinity
        return find(
                "product.id = ?1 " +
                        "and (startDateTime is null or startDateTime <= ?2) " +
                        "and (endDateTime is null or endDateTime > ?2) " +
                        "order by priority DESC",
                productId, date
        ).firstResult();
    }

    /**
     * Finds the current active price for a specific product, and usage.
     * <p>
     * Uses {@link DateTimeProvider} to determine "now".
     *
     * @param productId The ID of the product
     * @return The active Price or null
     */
    public static Price findCurrentPrice(Long productId) {
        return findActivePriceAtDate(productId, DateTimeProvider.now());
    }

    /**
     * Lists EVERY price row of a product, most recent window first
     * (BO-02-03-34/35): the article fiche reads this to render both the sale
     * price history (all rows) and the offers (the {@code priority > 0} rows
     * that overlay the base price for a window). A read-only projection — no
     * row is ever mutated here — so it never rewrites a sold line, consistent
     * with the price-resolution contract of this entity.
     *
     * @param productId the product id
     * @return the price rows, most recent start first, then priority; empty
     *         when the product has no price
     */
    public static List<Price> findByProduct(Long productId) {
        return list("product.id = ?1 order by startDateTime desc, priority desc", productId);
    }

    /**
     * Checks if this price is currently active based on the DateTimeProvider.
     *
     * @return true if now is within the validity interval, false otherwise
     */
    public boolean isActive() {
        LocalDateTime now = DateTimeProvider.now();
        boolean startValid = (startDateTime == null) || (startDateTime.isBefore(now) || startDateTime.isEqual(now));
        boolean endValid = (endDateTime == null) || endDateTime.isAfter(now);
        return startValid && endValid;
    }

    // --------------------------------------------------
    // Equals and HashCode
    // --------------------------------------------------
    // Relying on BaseEntity implementation using ID.

    /**
     * Computes a checksum for the Price entity based on its significant fields.
     * <p>
     * This method combines the product EAN, store code, price usage, pricing details,
     * priority, and validity dates to generate a hash code.
     *
     * @return The computed checksum as an integer.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(product.ean, priceExcludingTax, priceIncludingTax, vatRate, priority, startDateTime, endDateTime);
    }
}