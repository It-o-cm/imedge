package com.intermarche.edge.service.sync;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Referential snapshot payloads pulled by the registers from the store node
 * (phase 6 lot 3). Natural keys only, like the document synchronization:
 * family code, product EAN, employee login, coupon-type code; prices
 * reference their product by EAN. Dates travel as ISO-8601 strings.
 * <p>
 * Any field added here must also be added to the canonical string of
 * {@code RefExportService} (fingerprint input) and to the corresponding
 * apply method — the three evolve as one.
 * <p>
 * The two domain lists live here rather than on the exporter because both
 * ends read them: the store node exports those domains, the downstream node
 * pulls them. Holding them on the exporter made the pull loop — common to
 * both roles — depend on the store half of the synchronization.
 */
public final class RefPayloads {

    /**
     * Non-instantiable payload container.
     */
    private RefPayloads() {}

    /**
     * The referential domains a REGISTER pulls from its store node, in apply
     * order.
     * <p>
     * PRODUCTS comes BEFORE FAMILIES: a family payload carries the articles it
     * contains, so the articles must already exist when the family is wired.
     * Nothing points the other way — an article holds no family reference, the
     * association is owned by the family — so the swap is safe.
     */
    public static final List<String> DOMAINS =
            List.of("ENGINE_FEEDS", "MEASURE_UNITS", "PRODUCTS", "FAMILIES", "TOUCH_GROUPS", "VAT_RATES", "PRICES",
                    "EMPLOYEES", "COUPON_TYPES", "ARTICLE_RANGES", "ISLANDS", "CHECKOUT_POINTS",
                    "TENDERS",
                    "DOCUMENT_TEMPLATES", "PRINTABLE_DOCUMENTS", "PROVIDER_MESSAGES", "SETTINGS",
                    "CASH_MENUS", "STORE",
                    "CUSTOMERS", "CURRENCIES", "POSTAL_CODES", "POS_LISTS", "RETURN_TYPES",
                    "PREPAID_CARD_TYPES", "STORE_IMAGES");

    /**
     * The echelon domains a STORE node pulls from the CENTRAL node (route A),
     * in apply order: the organisation tree top-down (a PDV references its
     * enseigne, an enseigne its country) then the parameters posed on it,
     * then the accounts the centre administers for that point of sale with
     * the profiles they hold (BO-01-01-03, BO-01-03-04): profiles before the
     * accounts, both before the assignments binding them. A
     * register never pulls these — it receives only the resolved SETTINGS.
     */
    public static final List<String> ECHELON_DOMAINS =
            List.of("COUNTRIES", "ENSEIGNES", "PDVS", "ECHELON_SETTINGS",
                    "PROFILES", "ACCOUNTS", "ACCOUNT_PROFILES");

    /**
     * How one article group is drawn on the touch grid (upsert by group code).
     * <p>
     * A domain of its own, and not four more fields on {@link FamilyDto},
     * because the two have different owners and different lifetimes: the
     * nomenclature comes from the gestion commerciale and is replaced whole at
     * every integration, the touch configuration is administered in the back
     * office. Merged, re-importing the nomenclature erased the shop's grid.
     */
    public static class TouchGroupDto {
        /** The code of the group this row configures (upsert key). */
        public String familyCode;
        /** Whether the group touch is pinned (BO-03-01-07). */
        public boolean pinned;
        /** The touch size SMALL/NORMAL/LARGE (BO-03-01-08), never null. */
        public String buttonSize;
        /** The custom display rank (BO-03-01-11). */
        public int displayOrder;
        /** The sales volume for the volume order (BO-03-01-13). */
        public long salesVolume;
        /** Islands the group is drawn on (BO-03-01-21), codes split by ';', or null for all. */
        public String islandCodes;
        /** Registers the group is drawn on (BO-03-01-22), ids split by ';', or null for all. */
        public String terminalIds;
        /** Kinds of point of contact (BO-03-01-23), names split by ';', or null for all. */
        public String tpvTypes;
        /** The group's label in other languages, as lang=libellé pairs (BO-03-01-19). */
        public String labels;
    }

    /**
     * A product family (upsert by code).
     */
    public static class FamilyDto {
        /** The family code (upsert key). */
        public String code;
        /** The family description. */
        public String description;
        /** The display flags. */
        public String flags;
        /**
         * The codes of the families this one hangs under (BO-03-01-01).
         * <p>
         * A LIST, not a single parent: the model allows a family to hang under
         * several, and picking one would invent a rule the product has never
         * written. Empty for a root family.
         */
        public java.util.List<String> parentCodes = new java.util.ArrayList<>();
        /**
         * The EANs of the articles this family contains (BO-03-01-04/16).
         * <p>
         * Carried here rather than on the article, because the family owns the
         * association. Without it the register receives flat, empty groups —
         * the touch grid of a freshly pulled register would show buttons with
         * nothing behind them.
         */
        public java.util.List<String> productEans = new java.util.ArrayList<>();
    }

    /**
     * A product (upsert by EAN).
     */
    public static class ProductDto {
        /** The EAN (upsert key). */
        public String ean;
        /** The PLU, or null. */
        public String plu;
        /** The product name. */
        public String name;
        /** The description, or null. */
        public String description;
        /** The display icon, or null. */
        public String icon;
        /** The touch image as a base64 data URI, or null (BO-03-01-15). */
        public String imageData;
        /** The brand, or null. */
        public String brand;
        /** The reference weight, or null. */
        public BigDecimal referenceWeight;
        /** The reference volume, or null. */
        public BigDecimal referenceVolume;
        /** The product type name. */
        public String productType;
        /** The unit name, or null. */
        public String unitName;
        /** Whether the product is active. */
        public boolean active;
        /** Whether the product is forbidden to sale. */
        public boolean forbiddenToSale;
        /** The minimum age required to sell the article, or null (BO-02-03-08). */
        public Integer ageRestriction;
        /** The checkout label, or null (BO-02-03-02). */
        public String checkoutLabel;
        /** The message shown when the article is rung, or null (BO-03-08-09). */
        public String infoMessage;
        /** The internal code, or null (BO-02-03-04). */
        public String internalCode;
        /** True when the product is sold loose (bulk), weight known only at weighing time. */
        public boolean variableWeight;
        /**
         * The declared attributes, code&nbsp;→&nbsp;text value (BO-02-03-18).
         * A {@link TreeMap} so JSON and the canonical fingerprint are ordered
         * by key; never null (empty when the article carries no attribute).
         */
        public Map<String, String> attributes = new TreeMap<>();
        /** The quantity the shop's registers sold over the administered window (BO-03-01-13). */
        public long salesVolume;
        /**
         * The additional sale codes of the article, sorted, never null (BO-02-03-03):
         * the register resolves any of them to this article.
         */
        public List<String> saleCodes = new java.util.ArrayList<>();
    }

    /**
     * A price row (prices are replaced as a whole, no upsert key).
     */
    /**
     * One VAT regime of the referential (BO-02-03-14).
     *
     * <p>Its own domain, applied BEFORE the prices: a price names a regime by
     * its number, so the table has to exist when the price rows land.
     */
    public static class VatRateDto {
        /** The regime number, the upsert key. */
        public Integer number;
        /** The rate as a fraction, 0.0550 for five and a half percent. */
        public BigDecimal rate;
        /** What a human reads in front of the rate, or null. */
        public String label;
        /** The ISO country the regime belongs to, or null for all of them. */
        public String country;
        /** The legal mention the regime obliges the documents to carry, or null. */
        public String legalMention;
        /** Whether the regime is an exemption rather than a mere zero rate (BO-02-02-01). */
        public boolean exempt;
        /** The administration's code of the exemption, or null (BO-02-02-01). */
        public String exemptionCode;
    }

    public static class PriceDto {
        /** The EAN of the priced product. */
        public String productEan;
        /** The price excluding tax. */
        public BigDecimal priceExcludingTax;
        /** The price including tax. */
        public BigDecimal priceIncludingTax;
        /**
         * The NUMBER of the VAT regime this price falls under, or null when it
         * names none. The rate itself travels in its own domain, once, instead
         * of being repeated on every price row.
         */
        public Integer vatNumber;
        /** The selection priority. */
        public Integer priority;
        /** The validity start, ISO-8601, or null. */
        public String startDateTime;
        /** The validity end, ISO-8601, or null. */
        public String endDateTime;
    }

    /**
     * An employee (upsert by login; local lockout counters are preserved).
     */
    public static class EmployeeDto {
        /** The login (upsert key). */
        public String loginName;
        /** The first name. */
        public String firstName;
        /** The last name. */
        public String lastName;
        /** The password hash. */
        public String password;
        /** The email. */
        public String email;
        /** The role name. */
        public String role;
        /** The badge id, or null. */
        public String badgeId;
        /**
         * Whether the holder carries a supervisor card (BO-03-06-51); null from
         * a node that does not know the column, read as no card.
         */
        public Boolean supervisorCard;
        /**
         * The names of the profiles the holder is bound to, joined by
         * semicolons, or null (BO-04-02-01).
         */
        public String profiles;
        /** The personal display theme, or null (follows the store). */
        public String theme;
        /** Interface language administered for this employee, or null (BO-01-01-04). */
        public String language;
        /** When the secret was last changed, ISO-8601, or null (BO-01-02-12). */
        public String passwordChangedAt;
        /** The Dallas key identifier, or null (BO-01-02-07). */
        public String dallasKey;
        /** Whether the employee is active. */
        public boolean active;
    }

    /**
     * A back-office parameter (upsert by key; an absent key means the
     * register reverts to the catalog default).
     */
    public static class SettingDto {
        /** The parameter key (upsert key). */
        public String key;
        /** The parameter value, as text. */
        public String value;
    }

    /**
     * A verbatim engine feed (upsert by code): the raw CSV parcel shipped
     * unopened from the store node to the registers for their local
     * valuation engine. The version is the SHA-256 of the content.
     */
    public static class EngineFeedDto {
        /** The feed code (upsert key). */
        public String code;
        /** The content version (SHA-256 hex). */
        public String version;
        /** The raw CSV content, verbatim. */
        public String content;
        /** The fingerprint the file was integrated over, empty for an empty referential, or null. */
        public String baseFingerprint;
        /** The fingerprint the integration left, or null. */
        public String resultFingerprint;
    }

    /**
     * A country echelon (upsert by code) pulled by a store node from the
     * central node (domain COUNTRIES). Top of the organisation tree.
     */
    public static class CountryDto {
        /** The country code (upsert key). */
        public String code;
        /** The country name. */
        public String name;
        /** The default language, or null. */
        public String defaultLanguage;
    }

    /**
     * An enseigne echelon (upsert by code) pulled by a store node from the
     * central node (domain ENSEIGNES). Attached to a country by code.
     */
    public static class EnseigneDto {
        /** The enseigne code (upsert key). */
        public String code;
        /** The enseigne name. */
        public String name;
        /** The code of the country it hangs under, or null. */
        public String countryCode;
        /** The default language, or null. */
        public String defaultLanguage;
    }

    /**
     * A point de vente (upsert by number) pulled by a store node from the
     * central node (domain PDVS). Attached to an enseigne by code.
     */
    public static class PdvDto {
        /** The five-digit PDV number (upsert key). */
        public String pdvNumber;
        /** The PDV name. */
        public String name;
        /** The code of the enseigne it hangs under, or null. */
        public String enseigneCode;
        /** The adhérent grouping code, or null. */
        public String adherentCode;
        /** Whether the PDV is active. */
        public boolean active;
    }

    /**
     * A back-office parameter posed at an echelon (upsert by the triplet
     * level/code/key) pulled by a store node from the central node (domain
     * ECHELON_SETTINGS). Carries the current value and, when a change is
     * programmed, the pending value and the date it applies from.
     */
    public static class EchelonSettingDto {
        /** The echelon level name (COUNTRY, ENSEIGNE or PDV). */
        public String level;
        /** The echelon code at that level (part of the upsert key). */
        public String echelonCode;
        /** The catalog key (part of the upsert key). */
        public String settingKey;
        /** The current value, as text, or null when only a change is programmed. */
        public String settingValue;
        /** The pending value programmed for the effect date, or null (a programmed removal). */
        public String pendingValue;
        /** The effect date of the pending value (ISO-8601 yyyy-MM-dd), or null when nothing is programmed. */
        public String effectiveDate;
    }

    /**
     * A coupon type (upsert by code).
     */
    public static class CheckoutIslandDto {
        /** The island code (upsert key). */
        public String code;
        /** The display label. */
        public String label;
        /** Whether the island is in service. */
        public boolean active;
        /**
         * OLD FORM (BO-03-06-07): the attached registers as a semicolon list of
         * terminal identifiers. Still emitted while the estate migrates, so a
         * caisse that reads only this field keeps its islands.
         */
        public String terminalIds;
        /**
         * NEW FORM (BO-03-09/BO-03-10): the attachment carried by the CONTACT
         * POINTS of the island. A caisse accepts both forms without preference;
         * when this list is present the effective terminals are read from it,
         * otherwise from {@link #terminalIds}. Never renamed away the old field:
         * losing the islands at the first pull is the one unacceptable failure.
         */
        public java.util.List<ContactPointDto> contactPoints;
    }

    /**
     * One CONTACT POINT of a checkout island (BO-03-09) — the level between the
     * island and the register that carries the register attachment in the new
     * payload form. Extra fields the node may add are tolerated (the register's
     * Jackson ignores unknown properties).
     */
    public static class ContactPointDto {
        /** The contact-point code. */
        public String code;
        /** The identifier of the register bound to this contact point. */
        public String terminalId;
    }

    /**
     * One administered CHECKOUT POINT — a register seen from the back office
     * (BO-03-09, BO-03-10), with the attributes the lane cannot know about
     * itself: its island, its kind, its technical hostess, its two displays,
     * its cash-menu model, and whether it is in service at all.
     *
     * <p>Distinct from {@link ContactPointDto}, which carries nothing but the
     * attachment of a register to an island inside the ISLANDS payload. This one
     * is the point itself, pulled as its own domain.
     *
     * <p>The snapshot is GLOBAL and not filtered to the terminal that asks: a
     * register receives every point of its store, because the technical hostess
     * supervises the self-service points beside her, the remote close names
     * another register, and the island's composition is read here. Filtering
     * would save a handful of rows and remove all three.
     *
     * <p>Enumerations travel by their NAME in text, like every other enum on
     * this wire, never by their ordinal: a value inserted in the middle of an
     * enum would otherwise silently renumber what the registers already hold.
     * A null stays null: the register's own column carries its default, and
     * inventing one here would hide a point the back office has not finished
     * describing.
     */
    public static class CheckoutPointDto {
        /** The register identifier ({@code pos.terminal.id}), the upsert key. */
        public String terminalId;
        /** The point-of-vente number the register belongs to, or null. */
        public String pdvNumber;
        /** The code of the island the register belongs to, or null. */
        public String islandCode;
        /** The human-readable label shown in the back office. */
        public String label;
        /** Whether the register is in service (BO-03-09-01). */
        public boolean active;
        /** The kind of checkout point (BO-03-09-07), by name. */
        public String tpvType;
        /** The badge of the technical hostess (BO-03-09-08), or null. */
        public String technicalHostessBadge;
        /** The type of the cashier display (BO-03-09-12), by name. */
        public String cashierDisplayType;
        /** The resolution of the cashier display, free text, or null. */
        public String cashierDisplayResolution;
        /** The type of the customer display (BO-03-09-13), by name. */
        public String customerDisplayType;
        /** The resolution of the customer display, free text, or null. */
        public String customerDisplayResolution;
        /** The protocol of the attached scale (BO-03-09-15), by name. */
        public String scaleProtocol;
        /** Where the scale answers (BO-03-09-15), or null. */
        public String scaleAddress;
        /** The port the scale answers on (BO-03-09-15), or null. */
        public Integer scalePort;
        /** The protocol of the attached coin machine (BO-03-09-24/-25/-26), by name. */
        public String coinMachineProtocol;
        /** Where the coin machine answers, or null. */
        public String coinMachineAddress;
        /** The port the coin machine answers on, or null. */
        public Integer coinMachinePort;
        /** The code of the cash-menu model assigned (BO-03-10-16), or null. */
        public String menuTemplateCode;
    }

    /**
     * ONE DOCUMENT the registers may print over a closed ticket (BO-03-04-06,
     * -09, -10 and -14).
     */
    public static class PrintableDocumentDto {
        /** The document code (upsert key). */
        public String code;
        /** The name printed on the document. */
        public String label;
        /** The nature of the document: FACTURE or BON_LIVRAISON. */
        public String kind;
        /** The printer it comes out of, or null for the administered default. */
        public String output;
        /** The template laying it out, or null for the register's own layout. */
        public String templateCode;
        /** Whether the registers offer it. */
        public boolean active;
        /** The order the documents are offered in. */
        public int priority;
    }

    /**
     * ONE PROVIDER MESSAGE translated into the shop's words (BO-03-08-06
     * and -07).
     */
    public static class ProviderMessageDto {
        /** The provider the code comes from (half of the upsert key). */
        public String providerCode;
        /** The code the provider answers (the other half). */
        public String messageCode;
        /** What the cashier reads, or null to keep the provider's text. */
        public String screenText;
        /** What the ticket prints, or null to print nothing. */
        public String ticketText;
        /** Whether the substitution is applied. */
        public boolean active;
    }

    /**
     * One administered DOCUMENT TEMPLATE — the layout of one printed document
     * (BO-03-03).
     */
    public static class DocumentTemplateDto {
        /** The template code (upsert key). */
        public String code;
        /** The display label. */
        public String label;
        /** The document the template lays out. */
        public String documentType;
        /** Whether the template is used. */
        public boolean active;
        /** The order competing templates of one document type are tried in. */
        public int priority;
        /** The width the template lays out for, in characters. */
        public int width;
        /** How many copies of the document are printed. */
        public int copies;
        /** The Qute source. */
        public String source;
        /** The administered name of the document, null when the register's own stands. */
        public String title;
        /** The administered name of a REPRINT, null when the register's own stands. */
        public String duplicateTitle;
        /** The prefix the document's number carries, null when none. */
        public String numberPrefix;
        /** The name of each printed copy, in order, separated by semicolons. */
        public String copyTitles;
        /** The echelon the template is posed at, or null for every point of sale (BO-02-05-04). */
        public String echelonScope;
    }

    /**
     * One administered TENDER — a settlement method put in service and bounded
     * (BO-03-02-03/04/10 to 30).
     */
    public static class TenderDefinitionDto {
        /** The settlement key, equal to the payment discriminator (upsert key). */
        public String code;
        /** The functional identifier administered by the store. */
        public String functionalId;
        /** The display label. */
        public String label;
        /** Code of the group the method is totalled under (BO-06-06-09), or null. */
        public String groupCode;
        /** Label of that group (BO-06-06-09), or null. */
        public String groupLabel;
        /** Whether the tender is in service. */
        public boolean active;
        /** The order the tenders are offered in. */
        public int displayOrder;
        /** The first ceiling of one settlement, or null when unbounded. */
        public String maxAmount;
        /** What the register does when the first ceiling is exceeded. */
        public String maxAmountControl;
        /** The second ceiling of one settlement, or null when unbounded. */
        public String secondMaxAmount;
        /** What the register does when the second ceiling is exceeded. */
        public String secondMaxAmountControl;
        /** The floor of one settlement, or null when unbounded. */
        public String minAmount;
        /** What the register does when the floor is not reached. */
        public String minAmountControl;
        /** How many settlements of this tender one sale may carry, or null. */
        public Integer maxCount;
        /** What the register does when the count is reached. */
        public String maxCountControl;
        /** The largest change this tender may give back, or null. */
        public String maxChangeAmount;
        /** What the register does when the change ceiling is exceeded. */
        public String maxChangeControl;
        /** Whether the tender may be used to refund a customer. */
        public boolean refundAllowed;
        /** Whether an overpayment on this tender gives change back. */
        public boolean changeAllowed;
        /** The tender the change is given in, or null for the tender itself. */
        public String changeTenderCode;
        /** The VIC product code of the multi-product card (BO-03-09-30), or null. */
        public String vicProductCode;
        /** Whether a self-service lane may settle with this tender (BO-03-02-27). */
        public boolean selfServiceAllowed = true;
        /** Whether settling with it on a self-service lane calls the hostess (BO-03-02-29). */
        public boolean selfServiceSupervision;
        /** Whether the cashier's holding is declared automatically. */
        public boolean cashierDeclaration;
        /** Whether this tender is withdrawn from the drawer automatically. */
        public boolean automaticWithdrawal;
        /** When the cash drawer opens for this tender. */
        public String drawerOpening;
        /** Whether cash movements may use this tender. */
        public boolean movementAllowed;
        /** Whether the takings of this tender are deposited at the bank. */
        public boolean bankDeposit;
        /** Whether this tender may make up the opening float. */
        public boolean floatAllowed;
        /** Whether the remaining due is pre-filled when this tender is picked. */
        public boolean defaultsToTotal;
        /** Whether the withdrawal report details this tender. */
        public boolean withdrawalReportDetail;
        /** Whether the use of this tender is reported to the fidelity programme. */
        public boolean fidelityReported;
        /** The gabarit printed when this tender is used, or null. */
        public String documentTemplateCode;
        /** How many times that slip is printed. */
        public int printCount;
        /** Whether a duplicate of that slip is printed beside it. */
        public boolean printDuplicate;
        /** Whether that slip is printed even when the receipt is not. */
        public boolean printForced;
        /** The settlement nature of a tender the store added (BO-03-02-01), or null. */
        public String nature;
        /** The islands on which this tender calls a supervisor (BO-03-02-29), or null. */
        public String supervisionIslands;
        /** The monetics answers selecting this tender (BO-03-02-44), or null. */
        public String moneticCodes;
        /** Whether cash goes through the coin machine (BO-03-02-36). */
        public boolean coinMachine;
        /** Whether a cheque is read by the MICR reader (BO-03-02-24). */
        public boolean micrReading = true;
        /** Whether a cheque needs a supervisor's authorization (BO-03-02-24). */
        public boolean chequeAuthorization;
        /** Whether the cashier keys the cheque's account number (BO-03-02-24). */
        public boolean accountNumberEntry;
        /** Whether the cheque front is printed automatically (BO-03-02-24). */
        public boolean chequeAutoPrint;
    }

    /**
     * One administered ARTICLE barcode range (BO-03-06-02/03/04/05/10).
     */
    public static class ArticleBarcodeRangeDto {
        /** The range code (upsert key). */
        public String code;
        /** The display label. */
        public String label;
        /** Whether the range is considered at scan time. */
        public boolean active;
        /** The order the ranges are tested in. */
        public int priority;
        /** The literal head every code of the range carries. */
        public String prefix;
        /** The total code length. */
        public int codeLength;
        /** The character kind outside the two administered segments. */
        public String codeKind;
        /** The 0-based offset of the segment naming the article. */
        public int articlePosition;
        /** The number of characters of the article segment. */
        public int articleLength;
        /** What the value segment carries (PRICE or WEIGHT). */
        public String valueSource;
        /** The 0-based offset of the segment carrying the value. */
        public int valuePosition;
        /** The number of characters of the value segment. */
        public int valueLength;
        /** The number of decimals the value segment carries. */
        public int valueDecimals;
        /** The currency a PRICE segment is expressed in. */
        public String currency;
        /** Whether the last character is a verified check digit. */
        public boolean checkDigit;
        /** The generated recognition pattern. */
        public String matchPattern;
        /** What the register does with a code of the range (BO-03-06-56/-60/-61). */
        public String purpose;
        /** The promotion a PROMOTION range names (BO-03-06-43), or null. */
        public String promotionCode;
        /** The third-party service an EXTERNAL_LOOKUP range asks (BO-03-06-41), or null. */
        public String lookupUrl;
        /** The register function a FUNCTION range calls (BO-03-06-59), or null. */
        public String functionCode;
        /** What a PROMOTION range's promotion is worth: PERCENT, AMOUNT or null (BO-03-06-43). */
        public String promotionType;
        /** Where an EXTERNAL_LOOKUP answer carries what the register acts on, or null (BO-03-06-41). */
        public String lookupField;
    }

    /**
     * One administered voucher range (BO-03-06).
     */
    public static class CouponTypeDto {
        /** The type code (upsert key). */
        public String code;
        /** The display label. */
        public String label;
        /** The recognition regex, or empty for numberless types. */
        public String matchPattern;
        /** The amount source name (ENCODED or MANUAL). */
        public String amountSource;
        /** The amount extraction regex, or null. */
        public String amountPattern;
        /** The matching priority. */
        public int priority;
        /** Whether the type is active. */
        public boolean active;
        /** Whether the type is a deposit-return line type. */
        public boolean depositLine;
        /** The literal head every code of the range carries, or null. */
        public String prefix;
        /** The total code length, or null when the range is not administered. */
        public Integer codeLength;
        /** The character kind outside the administered fields, or null. */
        public String codeKind;
        /** Whether an all-nines price means "ask the cashier" (BO-03-06-12). */
        public boolean manualAmountOnAllNines;
        /** The checkout islands accepting the range, or null for all (BO-03-06-07). */
        public String islandCodes;
        /** The tender the range is settled as (BO-03-06-08), or null. */
        public String tenderCode;
        /** The instrument kind the register issues under the range (BO-03-06-30 to -38), or null. */
        public String issuedKind;
        /** The card protocol of a prepaid range (BO-03-02-37), or null. */
        public String cardProtocol;
        /** The administered positions of the range, empty when there are none. */
        public List<CouponFieldDto> fields = new ArrayList<>();
        /** The administered controls of the range, empty when there are none. */
        public List<CouponControlDto> controls = new ArrayList<>();
    }

    /**
     * One administered control of a barcode range (BO-03-06): what the register
     * checks, how loudly it reacts and what it says. A control is what gives an
     * administered position a reader, so it travels with the type.
     */
    public static class CouponControlDto {
        /** The control kind name, which is also its key within its type. */
        public String kind;
        /** The alert level name. */
        public String level;
        /** The administered wording, or null for the control's own. */
        public String message;
    }

    /**
     * One administered position of a barcode range (BO-03-06): where a value
     * sits in the code and how to read it. The recognition pattern of the
     * owning type is generated from these rows, so they travel with it.
     */
    public static class CouponFieldDto {
        /** The role name, which is also the field's key within its type. */
        public String role;
        /** The zero-based position of the field's first character. */
        public int offsetPosition;
        /** The number of characters the field spans. */
        public int fieldLength;
        /** The character kind name. */
        public String kind;
        /** The decimal count of a monetary field, or null for the default. */
        public Integer decimals;
        /** The date or time layout name, or null. */
        public String dateFormat;
        /** The currency name of a price field, or null for euros. */
        public String currency;
    }

    /**
     * An account customer as the commercial management holds it, pulled by the
     * register ({@code LC-07-09}).
     *
     * <p>The register can CREATE such a customer at the till (the invoice flow does)
     * and pushes it upward on the outbox, but the credit ceiling and the outstanding
     * balance travel the other way only: they are the shop's figures, and a register
     * that could raise its own ceiling would not be a control.
     */
    public static class CustomerDto {
        /** The account number (upsert key). */
        public String accountNumber;
        /** The business name. */
        public String companyName;
        /** Where the customer came from: REGISTER or INTEGRATED (BO-02-04-22). */
        public String origin;
        /** The contact's civility, or null (BO-02-04-02). */
        public String civility;
        /** The contact's family name, or null. */
        public String lastName;
        /** The contact's given name, or null. */
        public String firstName;
        /** The street line of the billing address, or null. */
        public String street;
        /** The postal code of the billing address, or null. */
        public String postalCode;
        /** The city of the billing address, or null. */
        public String city;
        /** The SIRET, or null. */
        public String siret;
        /** The intra-community VAT number, or null. */
        public String vatNumber;
        /** The telephone number, or null. */
        public String phone;
        /** The electronic address, or null. */
        public String email;
        /** The fiscal identifier (NIF), or null (BO-02-04-18/20). */
        public String taxId;
        /** The commercial segment, or null (BO-10-04-07). */
        public String segment;
        /** True when the account is blocked at the register (BO-02-04-06). */
        public boolean blocked;
        /** The due date as {@code yyyy-MM-dd}, or null (BO-02-04-08). */
        public String dueDate;
        /** The granted discount in percent, or null (BO-02-04-09). */
        public String discountPercent;
        /** The administered credit ceiling, or null when credit is not granted. */
        public String creditLimit;
        /** The outstanding balance, or null for none. */
        public String creditBalance;
        /** The free fields the commercial management filled (BO-02-04-11). */
        public List<FreeFieldDto> freeFields = new ArrayList<>();
    }

    /**
     * One free field of a customer in account ({@code BO-02-04-11}).
     */
    public static class FreeFieldDto {
        /** The slot the commercial management numbers the field by. */
        public int slot;
        /** The label shown in front of the value, or null. */
        public String label;
        /** The value, or null. */
        public String value;
    }

    /**
     * A postal code and the place it designates ({@code BO-02-04-15/16/21}).
     *
     * <p>Upserted on the PAIR and not on the code: a French code covers several
     * communes and a Portuguese one covers a street, so keying on the code
     * alone would keep one of them and lose the rest.
     */
    public static class PostalCodeDto {
        /** The postal code. */
        public String code;
        /** The place it designates. */
        public String place;
        /** The ISO country, or null. */
        public String country;
    }

    /**
     * A unit of measure of the article referential (BO-02-03-17): the code the
     * Gestion Commerciale sends with an article and the symbol it prints. It
     * travels BEFORE the articles, so a register applying an article file
     * resolves the unit code exactly as the store node did.
     */
    public static class MeasureUnitDto {
        /** The code, one or two characters (upsert key). */
        public String code;
        /** The label. */
        public String label;
        /** The symbol printed beside a quantity. */
        public String symbol;
        /** Whether a decimal quantity is allowed. */
        public boolean decimalAllowed;
        /** Whether the unit is in use. */
        public boolean active;
    }

    /**
     * A foreign currency the shop accepts, with the rate it applies
     * ({@code LC-07-14}).
     */
    public static class CurrencyDto {
        /** The ISO code (upsert key). */
        public String code;
        /** The name shown to the cashier. */
        public String label;
        /** The symbol printed beside an amount, or null. */
        public String symbol;
        /** How many euros one unit is worth, as text to keep the row canonical. */
        public String euroPerUnit;
        /** Whether the till offers it. */
        public boolean active;
        /** The order it appears in on the payment screen. */
        public int displayOrder;
        /** The tender the change is given in, or null for euros. */
        public String changeTenderCode;
    }

    /**
     * An administered NAMED LIST and its entries ({@code BO-04-02-07},
     * {@code BO-04-03-12}): the service-request reasons, the justifications,
     * the return types and the tare containers, which used to travel as four
     * semicolon-separated settings.
     *
     * <p>The entries ride INSIDE the list rather than as a domain of their
     * own, like the free fields of a customer: a list without its entries is
     * not a half-applied list, it is a wrong one, and the two must land in
     * the same transaction.
     */
    public static class PosListDto {
        /** The list code (upsert key). */
        public String code;
        /** The heading the entries are grouped under. */
        public String label;
        /** What the list drives: SERVICE_REQUEST, JUSTIFICATION, RETURN_TYPE or TARE_CONTAINER. */
        public String kind;
        /** The register functions it serves, semicolon-separated, or null for all. */
        public String functions;
        /** Whether the list is offered at all. */
        public boolean active;
        /** The entries, in display order; never null on the wire. */
        public java.util.List<PosListItemDto> items;
    }

    /**
     * ONE entry of an administered list. What it carries besides its label
     * depends on the kind of its list: a severity for a service request, a
     * weight for a tare container, option markers for a return type.
     */
    public static class PosListItemDto {
        /** The entry code, unique inside its list. */
        public String code;
        /** What the key is called in front of the cashier. */
        public String label;
        /** The rank the entry is drawn at, lowest first. */
        public int displayOrder;
        /** The priority of a service request, 0 when unused. */
        public int severity;
        /** The weight of a tare container in kilograms, as text, or null. */
        public String numericValue;
        /** The option markers of a return type, comma-separated, or null. */
        public String options;
        /** Whether the entry is offered at all. */
        public boolean active;
    }

    /**
     * An administered RETURN TYPE ({@code BO-03-13-01} to {@code -04},
     * {@code -11}, {@code -12}), upserted by code; a type the snapshot no
     * longer names is deleted at the register.
     */
    public static class ReturnTypeDto {
        /** The type code (upsert key). */
        public String code;
        /** What the key is called in front of the cashier. */
        public String label;
        /** Whether the type opens a dedicated return ticket on an identified sale. */
        public boolean ticketMode;
        /** Whether the type needs no supervisor endorsement. */
        public boolean endorsementExempt;
        /** Whether the type may be used without the original ticket. */
        public boolean originOptional;
        /** Whether each returned line has its price confirmed. */
        public boolean priceConfirmed;
        /** Whether the type is a déconsigne. */
        public boolean depositReturn;
        /** The attached RETURN barcode range code, or null. */
        public String rangeCode;
        /** The rank the key is drawn at. */
        public int displayOrder;
        /** Whether the register offers the type. */
        public boolean active;
    }

    /**
     * An administered TYPE OF PREPAID CARD ({@code BO-03-02-37}), upserted by
     * code; a type the snapshot no longer names is deleted at the register.
     */
    public static class PrepaidCardTypeDto {
        /** The type code (upsert key). */
        public String code;
        /** What the type is called. */
        public String label;
        /** The protocol, NEXO or LOCAL. */
        public String protocol;
        /** The issuing provider, or null. */
        public String provider;
        /** The ranges, {@code prefix} or {@code prefix:length}, semicolon-separated. */
        public String ranges;
        /** Whether a card of the type may settle a sale. */
        public boolean paymentAllowed;
        /** Whether its balance may be consulted. */
        public boolean balanceAllowed;
        /** Whether it may be activated at the sale. */
        public boolean activationAllowed;
        /** The rank the type is listed at. */
        public int displayOrder;
        /** Whether the register knows the type. */
        public boolean active;
    }

    /**
     * An administered IMAGE resolved for the asking store
     * ({@code BO-03-03-15}, {@code BO-10-03-09}): the receipt logo, or the
     * visual of a loyalty advantage.
     *
     * <p>THE ECHELON IS NOT ON THE WIRE. The store node resolves the chain —
     * PDV, enseigne, country, group default — and sends the winner, exactly
     * as it sends resolved parameters and never the echelon rows.
     */
    public static class StoreImageDto {
        /** The image code (upsert key). */
        public String code;
        /** What the administrator calls it. */
        public String label;
        /** What it is for: RECEIPT_LOGO or LOYALTY_ADVANTAGE. */
        public String usage;
        /** The loyalty advantage it illustrates, or null for a logo. */
        public String advantageCode;
        /** The image itself, a PNG {@code data:} URI. */
        public String imageData;
        /** Whether the register uses it at all. */
        public boolean active;
    }

    /**
     * A profile the central node administers (domain PROFILES, upsert by
     * name), with its grants. The validating profile travels by NAME: ids are
     * local to each node.
     */
    public static class ProfileDto {
        /** The profile name (upsert key). */
        public String name;
        /** The description, or null. */
        public String description;
        /** The hierarchical level. */
        public int hierarchyLevel;
        /** The grants, feature by option. */
        public List<ProfileGrantDto> grants = new java.util.ArrayList<>();
    }

    /** One grant of a distributed profile. */
    public static class ProfileGrantDto {
        /** The feature key. */
        public String featureKey;
        /** The option name (VIEW, CREATE, UPDATE, DELETE). */
        public String option;
        /** Whether the option asks for a validation. */
        public boolean requiresValidation;
        /** The name of the validating profile, or null. */
        public String validatingProfileName;
    }

    /**
     * An account the central node administers for the asking point of sale
     * (domain ACCOUNTS, upsert by login): the same identifiers as at the
     * centre, back-office password hash included, so a central account signs
     * in to the store's back office as it does to the central one
     * (BO-01-01-03).
     */
    public static class AccountDto {
        /** The login (upsert key). */
        public String loginName;
        /** The first name. */
        public String firstName;
        /** The surname. */
        public String lastName;
        /** The electronic address. */
        public String email;
        /** The register PIN hash. */
        public String password;
        /** The back-office password hash, or null. */
        public String backOfficePassword;
        /** The role name. */
        public String role;
        /** The badge, or null. */
        public String badgeId;
        /** Whether the account is active. */
        public boolean active;
        /** The telephone, or null. */
        public String phone;
        /** The start date (ISO), or null. */
        public String startDate;
        /** The department, or null. */
        public String department;
        /** The hierarchical level. */
        public int hierarchyLevel;
        /** The language, or null. */
        public String language;
        /** The echelon scopes, or null for the whole organisation. */
        public String echelonScopes;
        /** The back-office sign-in mode name. */
        public String authMode;
        /** Whether the holder must change the back-office password. */
        public boolean mustChangePassword;
        /** When the secret was last changed (ISO), or null. */
        public String passwordChangedAt;
        /** Whether the holder carries a supervisor card. */
        public boolean supervisorCard;
        /** The Dallas key identifier, or null (BO-01-02-07). */
        public String dallasKey;
    }

    /**
     * A profile assignment of a distributed account (domain ACCOUNT_PROFILES):
     * account by login, profile by name, and the echelon it applies to.
     */
    public static class AccountProfileDto {
        /** The account's login. */
        public String loginName;
        /** The profile's name. */
        public String profileName;
        /** The echelon scope. */
        public String echelonScope;
    }

    /**
     * One CASH MENU MODEL (domain CASH_MENUS, upsert by code) — what a register
     * draws of its sale screen (BO-03-10-16, BO-03-10-17).
     */
    public static class CashMenuDto {
        /** The model code (upsert key). */
        public String code;
        /** The human-readable label. */
        public String label;
        /** Whether the model is in service. */
        public boolean active;
        /** The menu-bar keys shown, semicolon separated, or null for all. */
        public String menuKeys;
        /** The function keys hidden, semicolon separated, or null. */
        public String hiddenFunctions;
        /** The number of key columns, zero for proportional. */
        public int keyColumns;
        /** The cashier screen model. */
        public String cashierScreen;
        /** The customer screen model. */
        public String customerScreen;
        /** The virtual ticket model. */
        public String ticketModel;
    }

    /**
     * The STORE RECORD the node administers (domain STORE, one row): the data
     * the register's document headers read (BO-03-10-19, BO-03-10-20,
     * BO-03-12-01). The theme stays node-local and is not carried.
     */
    public static class StoreDto {
        /** The store code. */
        public String code;
        /** The store name. */
        public String name;
        /** The first street line, or null. */
        public String streetLine1;
        /** The second street line, or null. */
        public String streetLine2;
        /** The postal code, or null. */
        public String postalCode;
        /** The city, or null. */
        public String city;
        /** The country, or null. */
        public String country;
        /** The intra-community VAT number, or null. */
        public String vatNumber;
        /** The SIRET, or null. */
        public String siret;
        /** The phone number, or null. */
        public String phone;
        /** The bank (cheque) account number, or null. */
        public String bankAccountNumber;
        /** The legal name, or null. */
        public String legalName;
        /** The trade register entry, or null. */
        public String rcs;
        /** The share capital as a plain decimal, or null. */
        public String shareCapital;
        /** The fax number, or null. */
        public String fax;
    }
}
