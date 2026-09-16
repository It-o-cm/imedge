package com.intermarche.pos.domain.store;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.util.Objects;
import com.intermarche.pos.domain.BaseEntity;

/**
 * Entity representing a physical Store (Supermarket/Hypermarket).
 * <p>
 * One row per node in practice: each register (and the store node) carries
 * its own copy, seeded or imported, and {@code code} is the storeCode
 * travelling in the sync payloads — the ingestion resolves tickets against
 * it. Deliberately outside the centralized referential pull (static row).
 * <p>
 * This class extends {@link BaseEntity} to inherit ID, versioning,
 * and audit fields.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "stores",
        indexes = {
                @Index(name = "idx_store_code", columnList = "code"),
                @Index(name = "idx_store_name", columnList = "name")
        }
)
@Cacheable
public class Store extends BaseEntity {

    // --------------------------------------------------
    // Store Details
    // --------------------------------------------------

    /**
     * The unique code of the store (e.g., "0034").
     * The unique constraint implicitly creates a unique index.
     */
    @Column(unique = true, nullable = false, length = 20)
    @NotBlank(message = "Store code is mandatory")
    public String code;

    /**
     * The name of the store (e.g., "Intermarché Lyon Centre").
     * Indexed to speed up search queries.
     */
    @Column(nullable = false)
    @NotBlank(message = "Store name is mandatory")
    public String name;

    /**
     * The full address of the store including GPS coordinates.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "streetLine1", column = @Column(name = "address_street_line1")),
            @AttributeOverride(name = "streetLine2", column = @Column(name = "address_street_line2")),
            @AttributeOverride(name = "postalCode", column = @Column(name = "address_postal_code")),
            @AttributeOverride(name = "city", column = @Column(name = "address_city")),
            @AttributeOverride(name = "country", column = @Column(name = "address_country")),
            @AttributeOverride(name = "latitude", column = @Column(name = "address_latitude")),
            @AttributeOverride(name = "longitude", column = @Column(name = "address_longitude"))
    })
    public Address address;

    /**
     * Default display theme of this node's screens (data-theme value), or
     * null for the built-in dark theme. Overridden per cashier by
     * {@code Employee.theme}. Node-local like the rest of the Store row.
     */
    @jakarta.persistence.Column(name = "theme", length = 20)
    public String theme;

    /**
     * Intra-community VAT number (BO-03-12-06), or null when not filled in
     * yet. Node-local like the rest of the Store row.
     */
    @jakarta.persistence.Column(name = "vat_number", length = 32)
    public String vatNumber;

    /**
     * SIRET business registration number (BO-03-12-06), or null when not
     * filled in yet. Node-local like the rest of the Store row.
     */
    @jakarta.persistence.Column(name = "siret", length = 32)
    public String siret;

    /**
     * Store phone number (BO-03-12-06), or null when not filled in yet.
     * Node-local like the rest of the Store row.
     */
    @jakarta.persistence.Column(name = "phone", length = 32)
    public String phone;

    /**
     * Bank (cheque) account number (BO-03-12-06), or null when not filled in
     * yet. Node-local like the rest of the Store row.
     */
    @jakarta.persistence.Column(name = "bank_account_number", length = 64)
    public String bankAccountNumber;

    /**
     * The legal entity that operates the store, when it differs from the trade name.
     * <p>
     * On a real Intermarché document the two are printed one under the other: the
     * trade name identifies the shop ("Intermarché Super Alim. Talence"), the legal
     * name identifies who is liable ("SA JANSELIN"). An invoice needs the second,
     * a receipt only ever showed the first. Null when the two are the same.
     */
    @jakarta.persistence.Column(name = "legal_name", length = 120)
    public String legalName;

    /**
     * The trade and companies register entry of the operating company, printed in
     * the legal footer of an invoice; null when not filled in yet.
     */
    @jakarta.persistence.Column(name = "rcs", length = 64)
    public String rcs;

    /**
     * The share capital of the operating company, printed in the same footer; null
     * when not filled in yet.
     */
    @jakarta.persistence.Column(name = "share_capital", precision = 19, scale = 2)
    public java.math.BigDecimal shareCapital;

    /**
     * Store fax number, printed in the seller's block of an invoice beside the
     * telephone; null when not filled in yet.
     */
    @jakarta.persistence.Column(name = "fax", length = 32)
    public String fax;

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a store by its unique code.
     *
     * @param code The store code
     * @return The Store or null
     */
    public static Store findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Calculates a checksum based on the product's key attributes.
     * @return Checksum integer value
     */
    @Override
    public int getChecksum() {
        int addressChecksum = address == null ? 0 : address.getChecksum();
        int checksum = Objects.hash(code, name, addressChecksum, vatNumber, siret, phone,
                bankAccountNumber, legalName, rcs, shareCapital, fax);
        return checksum;
    }
}