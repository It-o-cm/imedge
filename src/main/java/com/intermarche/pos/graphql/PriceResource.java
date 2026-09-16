package com.intermarche.pos.graphql;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * GraphQL API for managing Prices.
 * <p>
 * Implements {@link GraphQLTrait} for centralized error handling.
 * Manages prices for specific products.
 * <p>
 * Place in the POS architecture: this GraphQL layer is the ADMINISTRATION
 * and exploration surface of the referentials — no POS screen and no
 * synchronization flow uses it. Since the phase 6 centralized referentials,
 * the node matters: a mutation performed on a REGISTER is transient (the
 * next fingerprint pull from the store node overwrites or deactivates it);
 * mutations belong on the STORE node, where they change the domain
 * fingerprint and propagate to every register within
 * {@code pos.referential.pull-seconds}.
 * <p>
 * Deleting a price is harmless by design: nothing references price rows
 * (ticket lines snapshot their values at scan time) and the phase 6 pull
 * replaces the register-side price table wholesale anyway.
 */
@GraphQLApi
@ApplicationScoped
@RunOnVirtualThread
public class PriceResource implements GraphQLTrait {

    private static final Logger LOGGER = Logger.getLogger(PriceResource.class);

    // --------------------------------------------------
    // Queries (Retrieve) -> MANAGER only
    // --------------------------------------------------

    /**
     * Retrieves the list of all prices in the database.
     *
     * @return A list of all Price entities.
     */
    @Query
    @Description("Get the list of all prices")
    @RolesAllowed("MANAGER")
    public List<Price> allPrices() {
        LOGGER.info("Entering method allPrices");
        List<Price> result = Price.listAll();
        LOGGER.info("Exiting method allPrices");
        return result;
    }

    /**
     * Retrieves a specific price by its unique identifier.
     *
     * @param id The unique ID of the price.
     * @return The Price entity.
     * @throws NoSuchElementException if the price with the given ID does not exist.
     */
    @Query
    @Description("Get a price by its ID")
    @RolesAllowed("MANAGER")
    public Price price(@Name("id") Long id) {
        LOGGER.info("Entering method price with id: " + id);
        Price price = Price.findById(id);
        if (price == null) {
            LOGGER.error("Price with id " + id + " not found");
            throw new NoSuchElementException("Price with id " + id + " not found");
        }
        LOGGER.info("Exiting method price");
        return price;
    }

    /**
     * Retrieves the current active price for a specific product.
     *
     * @param productId The ID of the product.
     * @return The active Price entity.
     * @throws NoSuchElementException if no active price is found.
     */
    @Query
    @Description("Get the current active price for a product")
    @RolesAllowed("MANAGER")
    public Price currentPrice(@Name("productId") Long productId) {
        LOGGER.info("Entering method currentPrice for product: " + productId);
        Price price = Price.findCurrentPrice(productId);
        if (price == null) {
            LOGGER.warn("No current active price found for product " + productId);
        }
        LOGGER.info("Exiting method currentPrice");
        return price;
    }

    // --------------------------------------------------
    // Mutations (Create, Update, Delete) -> ADMIN only
    // All methods delegate to the default method 'execute' from GraphQLTrait.
    // --------------------------------------------------

    /**
     * Creates a new price in the database.
     * Validates that Product exists and that the combination (Product, Priority, StartDate, Usage) is unique.
     *
     * @param input The input record containing price details.
     * @return The newly created Price entity.
     * @throws GraphQLException if validation fails or a persistence error occurs.
     */
    @Mutation
    @Description("Create a new price")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Price createPrice(@Name("input") PriceRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method createPrice for product: " + input.productId);
            // Check if Product exists
            Product product = Product.findById(input.productId);
            if (product == null) {
                LOGGER.warn("Attempt to create price for non-existent product id: " + input.productId);
                throw new NoSuchElementException("Product with id '" + input.productId + "' not found.");
            }
            // Check Uniqueness Constraint: product_id + priority + startDateTime + priceUsage must be unique
            long existingCount = Price.count(
                    "product.id = ?1 and priority = ?3 and startDateTime = ?4 and priceUsage = ?5",
                    input.productId, input.priority, input.startDateTime);
            if (existingCount > 0) {
                LOGGER.warn("Attempt to create duplicate price (product, priority, start, usage)");
                throw new AlreadyExistsException("A price with the same priority, start date, and usage already exists for this product.");
            }
            // Creation Logic
            Price price = new Price();
            price.product = product;
            price.priceExcludingTax = input.priceExcludingTax;
            price.priceIncludingTax = input.priceIncludingTax;
            price.vatRate = input.vatRate;
            price.priority = input.priority;
            price.startDateTime = input.startDateTime;
            price.endDateTime = input.endDateTime;
            price.persist();
            LOGGER.info("Exiting method createPrice. Created ID: " + price.id);
            return price;
        }, PriceResource.class, "createPrice");
    }

    /**
     * Updates an existing price identified by its ID.
     * Validates new uniqueness if the key fields (Priority, StartDateTime, PriceUsage) are changed.
     *
     * @param id    The ID of the price to update.
     * @param input The input record containing fields to update.
     * @return The updated Price entity.
     * @throws GraphQLException if validation fails or the price is not found.
     */
    @Mutation
    @Description("Update an existing price")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public Price updatePrice(@Name("id") Long id, @Name("input") PriceRecord input) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method updatePrice for id: " + id);
            Price price = Price.findById(id);
            if (price == null) {
                LOGGER.error("Price with id " + id + " not found");
                throw new NoSuchElementException("Price with id " + id + " not found");
            }
            // Capture current state
            Long currentProductId = price.product.id;
            Integer currentPriority = price.priority;
            LocalDateTime currentStart = price.startDateTime;
            // Validate existence of new Product if changed
            if (input.productId != null && !input.productId.equals(currentProductId)) {
                Product newProduct = Product.findById(input.productId);
                if (newProduct == null) {
                    throw new NoSuchElementException("Product with id '" + input.productId + "' not found.");
                }
            }
            // Determine target values for uniqueness check
            Long targetProductId = input.productId != null ? input.productId : currentProductId;
            Integer targetPriority = input.priority != null ? input.priority : currentPriority;
            LocalDateTime targetStart = input.startDateTime != null ? input.startDateTime : currentStart;
            // Only check uniqueness if at least one of the key fields changed
            if (!targetProductId.equals(currentProductId) || !targetPriority.equals(currentPriority) || !targetStart.equals(currentStart) ) {
                long conflictCount = Price.count(
                        "product.id = ?1 and priority = ?2 and startDateTime = ?3 and id <> ?4",
                        targetProductId, targetPriority, targetStart, id
                );
                if (conflictCount > 0) {
                    LOGGER.warn("Attempt to update price to conflicting (product, priority, start)");
                    throw new AlreadyExistsException("Another price with this priority, start date already exists.");
                }
            }
            // Apply Updates
            if (input.productId != null) price.product = Product.findById(input.productId);
            if (input.priceExcludingTax != null) price.priceExcludingTax = input.priceExcludingTax;
            if (input.priceIncludingTax != null) price.priceIncludingTax = input.priceIncludingTax;
            if (input.vatRate != null) price.vatRate = input.vatRate;
            if (input.priority != null) price.priority = input.priority;
            if (input.startDateTime != null) price.startDateTime = input.startDateTime;
            if (input.endDateTime != null) price.endDateTime = input.endDateTime;
            LOGGER.info("Exiting method updatePrice");
            return price;
        }, PriceResource.class, "updatePrice");
    }

    /**
     * Deletes a price by its unique identifier.
     *
     * @param id The ID of the price to delete.
     * @return true if the price was deleted, false otherwise.
     * @throws GraphQLException if a persistence error occurs.
     */
    @Mutation
    @Description("Delete a price by ID")
    @RolesAllowed("ADMIN")
    @Transactional(rollbackOn = Exception.class)
    public boolean deletePrice(@Name("id") Long id) throws GraphQLException {
        return this.execute(() -> {
            LOGGER.info("Entering method deletePrice for id: " + id);
            boolean result = Price.deleteById(id);
            LOGGER.info("Exiting method deletePrice. Result: " + result);
            return result;
        }, PriceResource.class, "deletePrice");
    }

    /**
     * Input record type for GraphQL Price mutations.
     * <p>
     * Contains fields corresponding to the {@link Price} entity.
     */
    @Input
    static public class PriceRecord {

        /**
         * The ID of the Product associated with this price.
         */
        public Long productId;

        /**
         * The price excluding tax (HT).
         */
        public BigDecimal priceExcludingTax;

        /**
         * The price including tax (TTC).
         */
        public BigDecimal priceIncludingTax;

        /**
         * The applicable VAT rate.
         */
        public BigDecimal vatRate;

        /**
         * The priority of the price (used for conflict resolution).
         */
        public Integer priority;

        /**
         * The start date and time of validity.
         */
        public LocalDateTime startDateTime;

        /**
         * The end date and time of validity.
         */
        public LocalDateTime endDateTime;

        /**
         * Default constructor.
         */
        public PriceRecord() {
        }

        /**
         * Returns a string representation of the record.
         */
        @Override
        public String toString() {
            return "PriceRecord [productId=" + productId +
                    ", priceExcludingTax=" + priceExcludingTax +
                    ", priceIncludingTax=" + priceIncludingTax +
                    ", vatRate=" + vatRate +
                    ", priority=" + priority +
                    ", startDateTime=" + startDateTime +
                    ", endDateTime=" + endDateTime + "]";
        }
    }
}