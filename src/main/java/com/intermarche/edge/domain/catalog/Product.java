package com.intermarche.edge.domain.catalog;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import com.intermarche.edge.domain.BaseEntity;

/**
 * Entity representing a Product in a supermarket POS system.
 * <p>
 * This class focuses strictly on the product's identity and intrinsic attributes.
 * It does NOT contain references to categories, as categorization depends on the Store.
 * <p>
 * Semantic contract:
 * <ul>
 *   <li>{@code active} and {@code forbiddenToSale} are different axes:
 *       inactive means "gone from the referential" (hidden from search,
 *       deactivated — never deleted — by the referential pull, because
 *       ticket lines hold product foreign keys); forbidden means "present
 *       but blocked", refused at scan time with a cashier error.</li>
 *   <li>{@code ean} is the portable identity everywhere: catalog upserts,
 *       sync payloads, search-to-cart addition. {@code plu} (unique,
 *       nullable) is the weighed-sale identity: 2x scale labels and the
 *       fruits screen resolve through it, and a typed PLU behaves like a
 *       quantity-1 sale.</li>
 * </ul>
 * <p>
 * This class extends {@link BaseEntity} to inherit ID, versioning,
 * and audit fields.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "products",
        indexes = @Index(name = "idx_product_name", columnList = "name")
)
@Cacheable
public class Product extends BaseEntity {

    // --------------------------------------------------
    // Identification
    // --------------------------------------------------

    /**
     * The EAN (European Article Number) code, commonly known as barcode.
     * This is the primary identifier used for lookups at the register.
     * Unique constraint implies a database index.
     */
    @Column(name = "ean", unique = true, nullable = false, length = 13)
    @NotBlank(message = "EAN is mandatory")
    public String ean;

    @Column(name = "plu", unique = true, nullable = true, length = 4)
    public String plu;

    /**
     * True when the product is sold loose (bulk): its weight is only known at
     * weighing time — the register scale (FRUITS &amp; LÉGUMES screen) or a 2x
     * label printed by the aisle scale. False for pre-packed goods, including
     * WEIGHT-typed ones sold at a fixed pack weight (500&nbsp;g pasta,
     * 100&nbsp;g ham): those behave as units at the register and never appear
     * on the weighing screen.
     */
    @Column(name = "variable_weight")
    public boolean variableWeight;

    // --------------------------------------------------
    // Product Details
    // --------------------------------------------------

    @Column(nullable = false)
    @NotBlank(message = "Product name is mandatory")
    public String name;

    /**
     * The checkout label (libellé encaissement, BO-02-03-02): the short label
     * printed on the ticket and shown on the sale line, distinct from the
     * commercial {@link #name}. When null the sale surface falls back to the
     * commercial name, so an article without a dedicated checkout label behaves
     * exactly as before.
     */
    @Column(name = "checkout_label", length = 100)
    public String checkoutLabel;

    /**
     * An information message shown to the cashier WHEN THIS ARTICLE IS RUNG
     * (BO-03-08-09); null when the article says nothing.
     *
     * <p>On the article and not in a table of rules, because the message IS an
     * attribute of the article: "produit fragile", "vérifier la date", "appeler
     * le rayon". A store writes it where it writes the article's label, and it
     * travels down the same road.
     *
     * <p>Never blocking. It is an information, so it leaves a notice and the
     * sale goes on — an article the shop wants to refuse is handled by a
     * control point, not by a sentence.
     */
    @Column(name = "info_message", length = 200)
    public String infoMessage;

    /**
     * The internal code (code interne, BO-02-03-04): an in-store identifier a
     * cashier can scan or key to reach the article in addition to its EAN.
     * Unique across the referential, nullable when the article has none.
     */
    @Column(name = "internal_code", unique = true, length = 50)
    public String internalCode;

    @Column(length = 255)
    public String description;

    @Column(length = 10)
    public String icon;

    /**
     * The touch image of the article (BO-03-01-15), a base64 {@code data:} URI
     * of a small resized picture set in the back office and rendered on the
     * caisse touch grids (FRUITS &amp; LÉGUMES and SAISIE DIRECTE) in place of
     * the {@link #icon} emoji when present. Referential data: it rides the
     * PRODUCTS domain of the pull so a picture attached in the back office
     * reaches the registers at the next tirage. Null when the article carries
     * no picture. Deliberately EXCLUDED from {@link #getChecksum()}, exactly as
     * {@link #icon} is, so a picture change does not churn the row version.
     */
    @Lob
    @Column(name = "image_data")
    public String imageData;

    /**
     * The brand of the product.
     * Optional field to specify the manufacturer or brand name.
     */
    @Column(name = "brand", length = 100)
    public String brand;

    // --------------------------------------------------
    // Dimensions & Weight
    // --------------------------------------------------

    /**
     * Reference weight for the product.
     * <p>
     * This optional field can be used for inventory (pallet weight),
     * or as a default weight for products sold by unit (e.g., average weight of a melon).
     * Stored in Kilograms (kg) with 3 decimal places (gram precision).
     * Null if not applicable.
     */
    @Column(name = "reference_weight", precision = 10, scale = 3)
    public BigDecimal referenceWeight;

    /**
     * Reference volume for the product.
     * <p>
     * This optional field can be used for inventory (bottle size, tank capacity),
     * or as a default volume for products sold by unit (e.g., standard drink size).
     * Stored in Liters (L) with 3 decimal places (milliliter precision).
     * Null if not applicable.
     */
    @Column(name = "reference_volume", precision = 10, scale = 3)
    public BigDecimal referenceVolume;

    /**
     * Defines how the product is sold (Unit vs Weight).
     * Intrinsic to the product definition.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @NotNull(message = "Product type is mandatory")
    public ProductType productType;

    /**
     * Display unit for the product (e.g., "kg", "pcs", "L").
     */
    @Column(name = "unit_name", length = 20)
    public String unitName;

    // --------------------------------------------------
    // Status
    // --------------------------------------------------

    /**
     * Flag to indicate if the product definition is globally active.
     * Note: A product can be active globally but not present in a specific store's categories.
     */
    @Column(name = "is_active", nullable = false)
    public boolean active = true;

    /**
     * Flag to indicate that the product is forbidden to sell at the register.
     * <p>
     * When true, scanning or keying this product must be refused: no ticket line is
     * created and an error is shown to the cashier.
     */
    @Column(name = "forbidden_to_sale", nullable = false)
    public boolean forbiddenToSale = false;

    /**
     * Minimum customer age required to sell this product (e.g. 18 for
     * alcohol), or null when the product is unrestricted. A restricted scan
     * suspends the sale until the cashier confirms the ID check or refuses
     * the sale — both journalized (phase: age control).
     */
    @jakarta.persistence.Column(name = "age_restriction")
    public Integer ageRestriction;

    /**
     * When set, this product IS a gift card of that denomination: selling it
     * issues, at the fiscal moment of the sale, an ACTIVE registry
     * instrument of this amount (phase: credit notes & gift cards). Gift
     * cards sell at VAT 0 — multi-purpose vouchers are out of VAT scope at
     * issuance under French rules.
     */
    @jakarta.persistence.Column(name = "gift_card_amount", precision = 10, scale = 2)
    public java.math.BigDecimal giftCardAmount;

    /**
     * The EGAlim regime CODE this article is classified under, or null when the
     * Gestion Commerciale states none.
     *
     * <p>A CODE and not a ceiling. The legal ceiling of a regime moves — the loi
     * Travert took DPH from 34 % to 40 % — and it moves for a whole category at
     * once; storing the number of percent on a hundred thousand article rows
     * would mean rewriting the catalog to apply a law. The code points at the
     * {@link EgalimRegime} row that carries the ceiling of the day.
     *
     * <p>This node COMPUTES nothing with it: the promotional-generosity cap is
     * arbitrated by the valuation engine, which receives the classification
     * through the article feed and the ceilings through the
     * {@code EGALIM_REGIMES} feed. The column exists here so the article fiche
     * and the article list can SHOW an operator which regime an article falls
     * under, and so a regime absent from the referential can be seen rather
     * than guessed.
     *
     * <p>Null is "not classified", which the engine treats as uncapped. It is
     * not a synonym for the exempt regime: the exempt regime is a stated
     * classification, null is the absence of one.
     */
    @jakarta.persistence.Column(name = "egalim_regime", length = 30)
    public String egalimRegime;

    // --------------------------------------------------
    // Declared attributes (BO-02-03-18)
    // --------------------------------------------------

    /**
     * The declared attributes of the article, code&nbsp;→&nbsp;text value
     * (BO-02-03-18). The map is open: any attribute code integrated from the
     * Gestion Commerciale is stored here, which is how the "minimum 99 attributs
     * possibles" requirement is met — the storage is unbounded and only the
     * subset the register acts upon is declared in {@code
     * ProductAttributeCatalog}. Read through {@code ProductAttributes}, never by
     * poking the map directly. Values are text; the catalog carries the type.
     * <p>
     * This is referential data (integrated and distributed by the draw), NOT
     * per-register local state: it lives on the store side of the frontier and
     * flows to every register through the PRODUCTS domain.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_attributes",
            joinColumns = @JoinColumn(name = "product_id"))
    @MapKeyColumn(name = "attr_code", length = 50)
    @Column(name = "attr_value", length = 255)
    public Map<String, String> attributes = new HashMap<>();

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------


    /**
     * The effective sale-line label (BO-02-03-02): the checkout label when it
     * is set and non-blank, otherwise the commercial {@link #name}. Both the
     * scan chain and the manual-add paths route their line label through here,
     * so an article without a dedicated checkout label reads exactly as before.
     *
     * @return the checkout label when present, else the commercial name
     */
    public String saleLabel() {
        return (checkoutLabel != null && !checkoutLabel.isBlank()) ? checkoutLabel : name;
    }

    /**
     * Finds a product by its unique EAN code.
     * This is the most frequently used method at checkout.
     *
     * @param ean The scanned EAN code
     * @return The Product or null
     */
    public static Product findByEan(String ean) {
        return find("ean", ean).firstResult();
    }

    /**
     * Finds a product by its unique PLU (Price Look-Up) code.
     *
     * @param plu The PLU code used to identify the product.
     * @return The Product with the specified PLU code, or null if no such product exists.
     */
    public static Product findByPlu(String plu) {
        return find("plu", plu).firstResult();
    }

    /**
     * Finds a product by EAN code, ensuring it is globally active.
     *
     * @param ean The scanned EAN code
     * @return The active Product or null
     */
    public static Product findActiveByEan(String ean) {
        return find("ean = ?1 and active = true", ean).firstResult();
    }

    /**
     * Finds an active Product by its PLU (Price Look-Up) code.
     *
     * @param plu The PLU (Price Look-Up) code used to identify the product.
     * @return The active Product with the given PLU code, or null if none exists.
     */
    public static Product findActiveByPlu(String plu) {
        return find("plu = ?1 and active = true", plu).firstResult();
    }

    /**
     * Finds a product by its unique internal code (code interne, BO-02-03-04).
     *
     * @param internalCode the internal code
     * @return the Product or null
     */
    public static Product findByInternalCode(String internalCode) {
        return find("internalCode", internalCode).firstResult();
    }

    /**
     * Finds a product by its internal code, ensuring it is globally active.
     *
     * @param internalCode the internal code
     * @return the active Product or null
     */
    public static Product findActiveByInternalCode(String internalCode) {
        return find("internalCode = ?1 and active = true", internalCode).firstResult();
    }

    /**
     * Calculates a checksum based on the product's key attributes.
     * <p>
     * The declared {@link #attributes} map IS included since BO-02-03-18: the
     * shared referential feed now carries attributes through the generic
     * {@code ATTRIBUTES} column, so an attribute change must be a detectable
     * change like any other field — otherwise a re-import that only edits an
     * attribute would be skipped by the checksum short-circuit and the column
     * would be a dead button on update. The map is hashed order-independently
     * (its {@link Map#hashCode()} sums entry hashes), and the CSV importer
     * mirrors this in its incoming-checksum computation. The presentation
     * {@code icon}/{@code imageData} stay excluded, as before. {@code
     * checkoutLabel} and {@code internalCode} remain included and mirrored.
     * {@code egalimRegime} is included for the same reason as the attributes: a
     * reclassification arriving alone in a feed must be a detectable change,
     * otherwise the checksum short-circuit would skip it and the fiche would
     * keep showing the former category.
     *
     * @return Checksum integer value
     */
    @Override
    public int getChecksum() {
        return Objects.hash(ean, plu==null? "":plu ,name, description, brand, referenceWeight, referenceVolume, productType, unitName, active, forbiddenToSale, checkoutLabel, internalCode, variableWeight, egalimRegime, attributes);
    }
}
