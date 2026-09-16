package com.intermarche.pos.service.sync;

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
            List.of("PRODUCTS", "FAMILIES", "TOUCH_GROUPS", "PRICES", "EMPLOYEES", "COUPON_TYPES",
                    "ARTICLE_RANGES", "ISLANDS", "TENDERS", "DOCUMENT_TEMPLATES", "SETTINGS", "ENGINE_FEEDS",
                    "CUSTOMERS", "CURRENCIES");

    /**
     * The echelon domains a STORE node pulls from the CENTRAL node (route A),
     * in apply order: the organisation tree top-down (a PDV references its
     * enseigne, an enseigne its country) then the parameters posed on it. A
     * register never pulls these — it receives only the resolved SETTINGS.
     */
    public static final List<String> ECHELON_DOMAINS =
            List.of("COUNTRIES", "ENSEIGNES", "PDVS", "ECHELON_SETTINGS");

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
    }

    /**
     * A price row (prices are replaced as a whole, no upsert key).
     */
    public static class PriceDto {
        /** The EAN of the priced product. */
        public String productEan;
        /** The price excluding tax. */
        public BigDecimal priceExcludingTax;
        /** The price including tax. */
        public BigDecimal priceIncludingTax;
        /** The VAT rate. */
        public BigDecimal vatRate;
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
        /** The personal display theme, or null (follows the store). */
        public String theme;
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
     * ECHELON_SETTINGS). Carries the effect date the value applies from.
     */
    public static class EchelonSettingDto {
        /** The echelon level name (COUNTRY, ENSEIGNE or PDV). */
        public String level;
        /** The echelon code at that level (part of the upsert key). */
        public String echelonCode;
        /** The catalog key (part of the upsert key). */
        public String settingKey;
        /** The value, as text. */
        public String settingValue;
        /** The effect date (ISO-8601 yyyy-MM-dd), or null for immediate effect. */
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
        /** The attached registers, as a semicolon list of terminal identifiers. */
        public String terminalIds;
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
        /** The administered credit ceiling, or null when credit is not granted. */
        public String creditLimit;
        /** The outstanding balance, or null for none. */
        public String creditBalance;
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
    }
}
