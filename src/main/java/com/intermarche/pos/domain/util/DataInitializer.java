package com.intermarche.pos.domain.util;

import com.intermarche.pos.domain.barcode.*;
import com.intermarche.pos.domain.catalog.*;
import com.intermarche.pos.domain.payment.*;
import com.intermarche.pos.domain.people.*;
import com.intermarche.pos.domain.sale.*;
import com.intermarche.pos.domain.session.*;
import com.intermarche.pos.domain.setting.*;
import com.intermarche.pos.domain.store.*;
import com.intermarche.pos.domain.sync.*;
import com.intermarche.pos.domain.catalog.Nomenclature;
import com.intermarche.pos.domain.catalog.NomenclatureLevel;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;

/**
 * Development/test data seeder.
 * <p>
 * Restricted to the dev and test build profiles: this bean wipes and reloads
 * the referential tables at startup, which must never run in production
 * (each reboot would purge the register's database). Production
 * referentials are fed through the CSV imports.
 * <p>
 * VALUATION-ENGINE COMPATIBILITY (phase 7): the catalog seeded here is the
 * MIRROR of the valuation engine's own seed (its ProductImporterClient /
 * PriceImporterClient / ProductFamilyImporterClient / StoreImporterClient
 * files are the reference) so both systems resolve the SAME base prices:
 * same store {@code 0101}, same EANs {@code 33000000000xx}, same
 * HT/TTC/VAT/priority price rows (DEFAULT usage only — BASE_FOR_DISCOUNT is
 * an engine-internal concept), same start date 2026-01-12, same promo
 * layers (EANs ...001, ...002 and ...020 carry a priority-1 price both
 * sides). PLUs and icons are REGISTER-LOCAL concepts added on top (the EAN
 * is the shared key); meal-voucher eligibility and family flags live in the
 * ENGINE only. PLU numbering follows trade practice: real IFPS codes for
 * produce (4020 Golden apples, 4062 cucumber, 4796 cherry tomatoes) and
 * plausible internal counter series for the rest (2xx cut/deli counters,
 * 5xx bulk) — no public standard exists outside produce. The two forbidden test products keep non-engine EANs on
 * purpose: they can never reach a basket.
 */
@ApplicationScoped
@IfBuildProfile(anyOf = {"dev", "test"})
public class DataInitializer {

    /** Price window start shared with the engine's seed (12 Jan 2026). */
    private static final LocalDateTime PRICE_START = LocalDateTime.of(2026, 1, 12, 0, 0, 0);

    /**
     * The seeded VAT referential: rate as the seed writes it, number and label.
     *
     * <p>Numbered the way the French referential does — 1 normal, 2 réduit,
     * 3 intermédiaire, 4 super-réduit, 5 exonéré — so a demonstration ticket
     * prints a VAT number a reader recognizes.
     */
    private static final String[][] SEEDED_VAT_RATES = {
            {"0.2000", "1", "Taux normal"},
            {"0.0550", "2", "Taux réduit"},
            {"0.1000", "3", "Taux intermédiaire"},
            {"0.0210", "4", "Taux super-réduit"},
            {"0.0000", "5", "Exonéré"}};

    /**
     * The regimes written by {@link #loadVatRates()}, indexed by the rate as
     * the seed writes it.
     *
     * <p>Held here rather than looked up per price: the seed states rates
     * because that is what a demonstration price list reads like, the model
     * states regimes, and this map is the one place the two meet.
     */
    private final java.util.Map<String, VatRate> seededVatRates = new java.util.HashMap<>();

    /**
     * Wipes and reloads the referential tables at startup (dev/test only).
     *
     * @param ev the startup event
     */
    @Transactional
    void onStart(@Observes StartupEvent ev) {
        Employee.deleteAll();
        Price.deleteAll();
        Product.deleteAll();
        NomenclatureLevel.deleteAll();
        ProductFamily.deleteAll();
        Nomenclature.deleteAll();
        CouponType.deleteAll();
        VatRate.deleteAll();

        createStore();
        loadVatRates();
        loadEmployees();
        loadProductsAndFamilies();
        loadCouponTypes();
        loadNomenclature();
    }

    /**
     * Seeds the Intermarché France nomenclature: its four levels and a slice
     * of the published hierarchy, codes and labels taken verbatim from the
     * group's file.
     * <p>
     * A slice and not the whole file — two and a half thousand nodes in a
     * dev seed would slow every start for nothing — but a REAL slice: the same
     * codes, the same cumulative lengths, three activities opening on their
     * rayons, familles and sous-familles. The screen and the drill-down are
     * therefore exercised on the real coding, not on a shape invented here.
     */
    private void loadNomenclature() {
        Nomenclature itm = new Nomenclature();
        itm.code = "ITM_FR";
        itm.label = "Intermarché France";
        itm.enseigneCode = "ITM";
        itm.persist();
        seedLevel(itm, 0, "Activité", 2, false);
        seedLevel(itm, 1, "Rayon", 4, false);
        seedLevel(itm, 2, "Famille", 8, false);
        seedLevel(itm, 3, "Sous-famille", 12, true);

        seedNode(itm, 0, "10", "FRAIS TRAD");
        seedNode(itm, 1, "1002", "BOUCHERIE / VOLAILLE TRAD");
        seedNode(itm, 2, "10020200", "VIANDE BOVINE TRAD");
        seedNode(itm, 3, "100202000001", "VIANDE BOVINE LAIT/STD TRAD");
        seedNode(itm, 3, "100202000002", "VIANDE BOVINE TRAD");
        seedNode(itm, 3, "100202000003", "VIANDE BOVINE IMPORT TRAD");
        seedNode(itm, 2, "10020202", "VEAU TR");
        seedNode(itm, 3, "100202020001", "VEAU TRAD");
        seedNode(itm, 2, "10020204", "VIANDE PORCINE TRAD");
        seedNode(itm, 1, "1004", "CHARCUTERIE TRAD");
        seedNode(itm, 2, "10040200", "CHARCUTERIE COUPE");
        seedNode(itm, 3, "100402000001", "JAMBON BLANC COUPE");
        seedNode(itm, 1, "1006", "TRAITEUR TRAD");
        seedNode(itm, 0, "20", "FRAIS LS");
        seedNode(itm, 1, "2020", "CREMERIE LS");
        seedNode(itm, 2, "20200200", "LAIT");
        seedNode(itm, 3, "202002000001", "LAIT DEMI-ECREME");
        seedNode(itm, 1, "2022", "SURGELES LS");
        seedNode(itm, 0, "40", "SEC LS");
        seedNode(itm, 1, "4040", "EPICERIE SUCREE");
        seedNode(itm, 2, "40400200", "BISCUITS");
        fileSeededArticles();
    }

    /**
     * Files a handful of seeded articles under their sous-famille, so the
     * nomenclature is not only administered but USED.
     * <p>
     * Without this the screen shows a classification nothing belongs to, and
     * the sale line still records the touch group: an article reaches the
     * register's {@code familyCode} through its membership, not through the
     * existence of a scheme. These four are the ones whose seeded name matches
     * a seeded node — a demonstration, not an import.
     */
    private void fileSeededArticles() {
        fileArticle("Lait UHT 1L", "202002000001");
        fileArticle("Jambon Blanc 100g", "100402000001");
        fileArticle("Biscuits Chocolat 200g", "40400200");
        fileArticle("Poulet Rôti 1.2kg", "100202000002");
    }

    /**
     * Files one seeded article under one node, by name.
     *
     * @param name the seeded article name
     * @param nodeCode the nomenclature node code
     */
    private void fileArticle(String name, String nodeCode) {
        Product article = Product.find("name", name).firstResult();
        if (article != null) {
            ProductFamily.fileUnderNomenclature(article, nodeCode);
        }
    }

    /**
     * Seeds one level of a scheme.
     *
     * @param scheme the scheme
     * @param rank the 0-based rank
     * @param label the level name
     * @param codeLength the cumulative code length at this level
     * @param custom whether a point of sale may redefine it
     */
    private void seedLevel(Nomenclature scheme, int rank, String label, int codeLength,
                           boolean custom) {
        NomenclatureLevel level = new NomenclatureLevel();
        level.nomenclature = scheme;
        level.rank = rank;
        level.label = label;
        level.codeLength = codeLength;
        level.custom = custom;
        level.persist();
    }

    /**
     * Seeds one node of a scheme.
     *
     * @param scheme the scheme
     * @param level the 0-based level
     * @param code the cumulative node code
     * @param label the node label
     */
    private void seedNode(Nomenclature scheme, int level, String code, String label) {
        ProductFamily node = new ProductFamily();
        node.code = code;
        node.description = label;
        node.nomenclature = scheme;
        node.level = level;
        node.persist();
    }

    /**
     * Back-office password given to the seeded MANAGER account.
     * <p>
     * A bootstrap value, and named like one: it is written in this source
     * file, so the account carries {@code mustChangePassword} and is
     * confined to the password screen until someone replaces it. Only the
     * supervising accounts get a back-office password at all — a cashier
     * has no back office, and the absence of a password is what says so.
     */
    private static final String BOOTSTRAP_BACK_OFFICE_PASSWORD = "changeme00";

    /**
     * Back-office password of the seeded ADMIN account.
     * <p>
     * A demo convenience, and nothing else: unlike the bootstrap value above
     * it survives the first sign-in, because the account is seeded WITHOUT
     * {@code mustChangePassword} so that admin/admin keeps working from one
     * demo to the next. It is a trivial password on the account that holds
     * every right — acceptable on a dev/test seed running on a laptop, never
     * on an exposed instance.
     */
    private static final String ADMIN_BACK_OFFICE_PASSWORD = "admin";

    /**
     * Seeds the test employees (badge, login, PIN, role, back-office
     * password).
     */
    private void loadEmployees() {
        // Format : createEmployee(BadgeID 8chiffres, login, PIN 4chiffres, Prénom, Nom, Rôle, mot de passe back-office)
        createEmployee("11111111", "mcurie", "1111", "Marie", "Curie", Employee.EmployeeRole.MANAGER,
                BOOTSTRAP_BACK_OFFICE_PASSWORD);
        // Theme demo: Marie prefers the light theme (cashier preference
        // overrides the store default — see ThemeService)
        Employee marie = Employee.find("loginName", "mcurie").firstResult();
        marie.theme = "clair";
        createEmployee("22222222", "aeinstein", "2222", "Albert", "Einstein", Employee.EmployeeRole.PICKER, null);
        createEmployee("00000000", "admin", "0000", "Le", "Manager", Employee.EmployeeRole.ADMIN,
                ADMIN_BACK_OFFICE_PASSWORD);
        // Demo account: its password is deliberately kept, so the forced
        // change createEmployee derives from a non-null password is undone.
        Employee admin = Employee.find("loginName", "admin").firstResult();
        admin.mustChangePassword = false;
        createEmployee("12341234",  "jdupont","1234", "Jean", "Dupont", Employee.EmployeeRole.CASHIER, null);
    }

    /**
     * Seeds the engine-mirrored catalog: 33 products, their DEFAULT price
     * rows for store 0101 (promo layers included) and the engine's family
     * tree, plus two register-only forbidden test products.
     */
    private void loadProductsAndFamilies() {
        // --- Engine family tree (ProductFamilyImporterClient mirror) ---
        ProductFamily pommes = createFamily("POMMES", "Pommes à croquer");
        ProductFamily racines = createFamily("RACINES", "Légumes racines");
        ProductFamily fruits = createFamily("FRUITS", "Rayon Fruits");
        ProductFamily legumes = createFamily("LEGUMES", "Rayon Légumes");
        ProductFamily eaux = createFamily("EAU_MINERALE", "Eaux Minérales");
        ProductFamily sodas = createFamily("SODAS", "Sodas");
        ProductFamily boissons = createFamily("BOISSONS", "Rayon Boissons");
        ProductFamily alimentaire = createFamily("ALIMENTAIRE", "Rayon Alimentaire");
        ProductFamily cuisson = createFamily("CUISSON", "Instruments de Cuisine");
        // Register-only shelf for the engine products without an engine family
        ProductFamily epicerie = createFamily("EPICERIE", "Épicerie & Divers");

        fruits.productFamilies.add(pommes);
        legumes.productFamilies.add(racines);
        boissons.productFamilies.add(eaux);
        boissons.productFamilies.add(sodas);
        alimentaire.productFamilies.add(fruits);
        alimentaire.productFamilies.add(legumes);
        alimentaire.productFamilies.add(boissons);

        // Fruits & Légumes aisle marking (weighing screen, read by
        // FruitService.FRUITS_VEGETABLES_FLAG): the flag is hierarchy-resolved,
        // so tagging the two aisle roots covers POMMES and RACINES below them.
        // Literal token here: the domain seed does not reach into the UI layer.
        fruits.addFlag("FRUITS_VEGETABLES");
        legumes.addFlag("FRUITS_VEGETABLES");

        // --- Engine catalog (ProductImporterClient mirror; PLU/icon = register-local) ---
        Product p01 = createProduct(pommes, "Pommes Golden", "Pommes fraîches bio", "Brand A", "4020", "3300000000001", "🍎", "1.000", "2.500", ProductType.WEIGHT, "kg");
        Product p02 = createProduct(epicerie, "Lait UHT 1L", "Lait demi-écrémé", "Brand B", null, "3300000000002", "🥛", "1.000", "1.000", ProductType.UNIT, "L");
        Product p03 = createProduct(epicerie, "Baguette Tradition", "Pain de tradition", "Brand C", null, "3300000000003", "🥖", "0.250", "0.600", ProductType.UNIT, "kg");
        Product p04 = createProduct(pommes, "Café Grains 500g", "Café moulu arabica", "Brand D", null, "3300000000004", "☕", "0.500", "1.250", ProductType.UNIT, "kg");
        Product p05 = createProduct(epicerie, "Pâtes Penne 500g", "Pâtes alimentaires", "Brand E", "501", "3300000000005", "🍝", "0.500", "1.250", ProductType.WEIGHT, "kg");
        Product p06 = createProduct(epicerie, "Huile d'Olive 1L", "Huile vierge extra", "Brand F", null, "3300000000006", "🫒", "1.000", "1.000", ProductType.UNIT, "L");
        Product p07 = createProduct(eaux, "Eau Minérale 1.5L", "Eau de source", "Brand G", null, "3300000000007", "💧", "1.500", "1.500", ProductType.UNIT, "L");
        Product p08 = createProduct(epicerie, "Jambon Blanc 100g", "Tranches de jambon", "Brand H", "210", "3300000000008", "🥓", "0.100", "0.250", ProductType.WEIGHT, "kg");
        Product p09 = createProduct(epicerie, "Beurre Doux 250g", "Motte de beurre", "Brand I", "220", "3300000000009", "🧈", "0.250", "0.600", ProductType.WEIGHT, "kg");
        Product p10 = createProduct(epicerie, "Yaourt Nature 4x125g", "Pots de yaourt", "Brand J", null, "3300000000010", "🥛", "0.500", "1.250", ProductType.UNIT, "kg");
        Product p11 = createProduct(sodas, "Coca-Cola 1.5L", "Boisson gazeuse", "Brand K", null, "3300000000011", "🥤", "1.500", "1.500", ProductType.UNIT, "L");
        Product p12 = createProduct(sodas, "Orangina 1.25L", "Boisson aux agrumes", "Brand L", null, "3300000000012", "🥤", "1.250", "1.250", ProductType.UNIT, "L");
        Product p13 = createProduct(epicerie, "Biscuits Chocolat 200g", "Paquet de biscuits", "Brand M", null, "3300000000013", "🍪", "0.200", "0.500", ProductType.UNIT, "kg");
        Product p14 = createProduct(epicerie, "Chips Classiques 150g", "Chips de pomme de terre", "Brand N", null, "3300000000014", "🍟", "0.150", "0.400", ProductType.UNIT, "kg");
        Product p15 = createProduct(epicerie, "Sauce Tomate 500g", "Sauce bolognaise", "Brand O", null, "3300000000015", "🍅", "0.500", "1.250", ProductType.UNIT, "kg");
        Product p16 = createProduct(epicerie, "Purée de Pomme de Terre 500g", "Purée instantanée", "Brand P", null, "3300000000016", "🥔", "0.500", "1.250", ProductType.UNIT, "kg");
        Product p17 = createProduct(racines, "Concombre", "Légume frais", "Brand Q", "4062", "3300000000017", "🥒", "0.300", "0.750", ProductType.WEIGHT, "kg");
        Product p18 = createProduct(racines, "Tomates Cerises 500g", "Tomates rondes", "Brand R", "4796", "3300000000018", "🍅", "0.500", "1.250", ProductType.WEIGHT, "kg");
        Product p19 = createProduct(epicerie, "Oeufs Bio 6 unités", "Oeufs frais gros", "Brand S", null, "3300000000019", "🥚", "0.360", "0.900", ProductType.UNIT, "kg");
        Product p20 = createProduct(epicerie, "Poulet Rôti 1.2kg", "Poulet fermier", "Brand T", "230", "3300000000020", "🍗", "1.200", "3.000", ProductType.WEIGHT, "kg");
        Product p21 = createProduct(epicerie, "Saumon Fume 200g", "Tranches de saumon", "Brand U", "240", "3300000000021", "🐟", "0.200", "0.500", ProductType.WEIGHT, "kg");
        Product p22 = createProduct(epicerie, "Riz Basmati 1kg", "Riz long grain", "Brand V", null, "3300000000022", "🍚", "1.000", "2.500", ProductType.UNIT, "kg");
        Product p23 = createProduct(epicerie, "Lentilles Vertes 500g", "Légumes secs", "Brand W", null, "3300000000023", "🫘", "0.500", "1.250", ProductType.UNIT, "kg");
        Product p24 = createProduct(epicerie, "Miel d'Acacia 500g", "Pot de miel", "Brand X", null, "3300000000024", "🍯", "0.500", "1.250", ProductType.UNIT, "kg");
        Product p25 = createProduct(epicerie, "Lessive Liquide 1.5L", "Lessive linge", "Brand Y", null, "3300000000025", "🧴", "1.500", "1.500", ProductType.UNIT, "L");
        Product p26 = createProduct(epicerie, "Eponge Vaisselle 3 unités", "Eponges abrasives", "Brand Z", null, "3300000000026", "🧽", "0.100", "0.250", ProductType.UNIT, "kg");
        Product p27 = createProduct(epicerie, "Coton Bio 500g", "Disques de coton", "Brand A1", null, "3300000000027", "🧻", "0.500", "1.250", ProductType.UNIT, "kg");
        Product p28 = createProduct(epicerie, "Piles AA 4 unités", "Piles alcalines", "Brand B1", null, "3300000000028", "🔋", "0.080", "0.200", ProductType.UNIT, "kg");
        Product p29 = createProduct(epicerie, "Chewing-Gum Menthe", "Pommes de menthe", "Brand C1", null, "3300000000029", "🍬", "0.050", "0.125", ProductType.UNIT, "kg");
        Product p30 = createProduct(epicerie, "Dentifrice Menthe 100ml", "Tube dentifrice", "Brand D1", null, "3300000000030", "🪥", "0.100", "0.100", ProductType.UNIT, "L");
        Product p31 = createProduct(cuisson, "Poêle Antiadhésive 28cm", "Poêle fonte alum", "Tefal", null, "3300000000031", "🍳", "0.800", "0.000", ProductType.UNIT, "pcs");
        Product p32 = createProduct(cuisson, "Casserole Inox 20cm", "Casserole acier inox", "Staub", null, "3300000000032", "🍲", "1.200", "0.000", ProductType.UNIT, "pcs");
        Product p33 = createProduct(cuisson, "Set de Couteaux Chef", "Couteaux acier inox", "Sabatier", null, "3300000000033", "🔪", "0.500", "0.000", ProductType.UNIT, "pcs");

        // Variable-weight marking: only true bulk is weighable at the
        // register. The other WEIGHT-typed rows (pâtes 500g, jambon 100g,
        // beurre 250g, tomates cerises 500g, poulet 1.2kg, saumon 200g) are
        // pre-packed at a fixed weight and behave as units in the lane.
        p01.variableWeight = true; // Pommes Golden, sold loose
        p17.variableWeight = true; // Concombre, sold loose

        // --- Engine price rows for store 0101 (PriceImporterClient mirror,
        //     DEFAULT usage only; ...001/...002/...020 carry a priority-1 promo) ---
        createPrice(p01, "1.00", "1.20", "0.2000", 0);
        createPrice(p01, "0.90", "1.08", "0.2000", 1);
        createPrice(p02, "2.50", "3.00", "0.2000", 0);
        createPrice(p02, "2.30", "2.76", "0.2000", 1);
        createPrice(p03, "0.80", "0.96", "0.2000", 0);
        createPrice(p04, "3.50", "4.20", "0.2000", 0);
        createPrice(p05, "1.20", "1.44", "0.2000", 0);
        createPrice(p06, "5.00", "6.00", "0.2000", 0);
        createPrice(p07, "0.50", "0.60", "0.2000", 0);
        createPrice(p08, "2.00", "2.40", "0.2000", 0);
        createPrice(p09, "2.50", "3.00", "0.2000", 0);
        createPrice(p10, "1.50", "1.80", "0.2000", 0);
        createPrice(p11, "1.80", "2.16", "0.2000", 0);
        createPrice(p12, "1.90", "2.28", "0.2000", 0);
        createPrice(p13, "2.00", "2.40", "0.2000", 0);
        createPrice(p14, "1.50", "1.80", "0.2000", 0);
        createPrice(p15, "1.80", "2.16", "0.2000", 0);
        createPrice(p16, "2.00", "2.40", "0.2000", 0);
        createPrice(p17, "3.00", "3.60", "0.2000", 0);
        createPrice(p18, "4.00", "4.80", "0.2000", 0);
        createPrice(p19, "3.50", "4.20", "0.2000", 0);
        createPrice(p20, "10.00", "10.55", "0.0550", 0);
        createPrice(p20, "9.50", "10.02", "0.0550", 1);
        createPrice(p21, "8.00", "9.60", "0.2000", 0);
        createPrice(p22, "2.50", "2.64", "0.0550", 0);
        createPrice(p23, "2.00", "2.11", "0.0550", 0);
        createPrice(p24, "5.00", "6.00", "0.2000", 0);
        createPrice(p25, "3.00", "3.60", "0.2000", 0);
        createPrice(p26, "2.00", "2.40", "0.2000", 0);
        createPrice(p27, "4.00", "4.80", "0.2000", 0);
        createPrice(p28, "6.00", "7.20", "0.2000", 0);
        createPrice(p29, "1.50", "1.80", "0.2000", 0);
        createPrice(p30, "2.50", "3.00", "0.2000", 0);
        createPrice(p31, "12.00", "14.40", "0.2000", 0);
        createPrice(p32, "15.00", "18.00", "0.2000", 0);
        createPrice(p33, "25.00", "30.00", "0.2000", 0);

        // --- Gift cards (register-local money products: VAT 0, non-engine
        //     EANs; selling one issues a registry instrument at the fiscal
        //     moment — phase: credit notes & gift cards) ---
        Product gift25 = createProduct(epicerie, "Carte Cadeau 25", "Carte cadeau Intermarché",
                "Intermarché", null, "3400025000001", "🎁", "0.010", "0.010",
                ProductType.UNIT, "u");
        gift25.giftCardAmount = new java.math.BigDecimal("25.00");
        createPrice(gift25, "25.00", "25.00", "0.0000", 0);
        Product gift50 = createProduct(epicerie, "Carte Cadeau 50", "Carte cadeau Intermarché",
                "Intermarché", null, "3400050000001", "🎁", "0.010", "0.010",
                ProductType.UNIT, "u");
        gift50.giftCardAmount = new java.math.BigDecimal("50.00");
        createPrice(gift50, "50.00", "50.00", "0.0000", 0);

        // --- Age-restricted local product (18+, non-engine EAN — the engine's
        //     unknown-EAN contract absorbs it if ever sent) ---
        Product wine = createProduct(epicerie, "Vin Rouge Bordeaux 75cl", "AOC Bordeaux",
                "Château Test", null, "3400018000001", "🍷", "0.750", "1.200",
                ProductType.UNIT, "L");
        wine.ageRestriction = 18;
        createPrice(wine, "5.42", "6.50", "0.2000", 0);

        // --- Register-only forbidden test products (non-engine EANs on purpose) ---
        createForbiddenProduct(racines, "Champignon sauvage non contrôlé", "4099", "3400409900001", "🍄", "3.79", "4.00");
        createForbiddenProduct(epicerie, "Lot rappelé (retrait conso)", null, "3660000099999", "⛔", "4.74", "5.00");

        pommes.persist();
        racines.persist();
        fruits.persist();
        legumes.persist();
        eaux.persist();
        sodas.persist();
        boissons.persist();
        alimentaire.persist();
        cuisson.persist();
        epicerie.persist();
    }

    /**
     * Seeds the coupon types (payment vouchers and the deposit-return type).
     */
    private void loadCouponTypes() {
        // NB : les formats réels des bons Intermarché ne sont pas publics ;
        //      les motifs ci-dessous sont des exemples à adapter au format réel.
        // amountPattern : 1er groupe capturant = montant en centimes (si ENCODED).
        // Chaque plage porte AUSSI sa description administrée (préfixe, longueur,
        // position du montant) : c'est elle qui engendre le motif au back-office,
        // et un test vérifie qu'elle redonne exactement les motifs écrits ici.

        // Chèque cadeau : 10 chiffres, 4 derniers = montant en centimes.
        // Registry-backed instruments: the number is a pure identifier, the
        // registry holds the balance (phase: credit notes & gift cards).
        administer(createCouponType("AVOIR", "Avoir", "^297\\d{12}$",
                CouponType.AmountSource.REGISTRY, null, 5), "297", 15, -1, 0);
        administer(createCouponType("GIFT_CARD", "Carte cadeau", "^296\\d{12}$",
                CouponType.AmountSource.REGISTRY, null, 5), "296", 15, -1, 0);
        administer(createCouponType("GIFT_VOUCHER", "Chèque cadeau", "^\\d{10}$",
                CouponType.AmountSource.ENCODED, "\\d{6}(\\d{4})$", 10), "", 10, 6, 4);

        // Bon enseigne : préfixe 50 + 12 chiffres, 4 derniers = montant en centimes.
        administer(createCouponType("STORE_VOUCHER", "Bon enseigne", "^50\\d{12}$",
                CouponType.AmountSource.ENCODED, "\\d{10}(\\d{4})$", 20), "50", 14, 10, 4);

        // Chèque fidélité : préfixe 789 + 12 chiffres, 4 derniers = montant en centimes.
        administer(createCouponType("LOYALTY_CHEQUE", "Chèque fidélité", "^789\\d{12}$",
                CouponType.AmountSource.ENCODED, "\\d{11}(\\d{4})$", 30), "789", 15, 11, 4);

        // Catalina : préfixe 0482 + 10 chiffres ; montant non déductible -> saisie manuelle.
        administer(createCouponType("CATALINA", "Catalina", "^0482\\d{10}$",
                CouponType.AmountSource.MANUAL, null, 40), "0482", 14, -1, 0);

        // Deposit-return vouchers (reverse vending machine): 298 + 6-digit
        // serial + 4-digit amount in cents. Scanned on the sale screen, they
        // become a negative ticket line, never a payment.
        administer(createDepositCouponType("DEPOSIT_VOUCHER", "Bon de consigne", "^298\\d{10}$",
                "^298\\d{6}(\\d{4})$", 5), "298", 13, 9, 4);

        // Bon générique / éphémère : aucun numéro -> aucune plage à administrer.
        createCouponType("GENERIC", "Bon générique", "",
                CouponType.AmountSource.MANUAL, null, 100);
    }

    /**
     * Adds to a seeded coupon type the administered description of its range:
     * the literal head, the total length and, when the amount sits in the
     * number, the position of the price field (BO-03-06).
     *
     * @param type the seeded type
     * @param prefix the literal head, possibly empty
     * @param codeLength the total number of characters of a code of the range
     * @param priceOffset the zero-based position of the price, or a negative
     *        value when the range carries no price
     * @param priceLength the number of characters of the price, ignored when
     *        the range carries none
     */
    private void administer(CouponType type, String prefix, int codeLength,
                            int priceOffset, int priceLength) {
        type.prefix = prefix.isEmpty() ? null : prefix;
        type.codeLength = codeLength;
        type.codeKind = CouponField.Kind.NUMERIC;
        type.fields = new ArrayList<>();
        if (priceOffset >= 0) {
            CouponField price = new CouponField();
            price.couponType = type;
            price.role = CouponField.Role.PRICE;
            price.offsetPosition = priceOffset;
            price.fieldLength = priceLength;
            price.kind = CouponField.Kind.NUMERIC;
            price.decimals = 2;
            price.currency = CouponField.PriceCurrency.EUR;
            type.fields.add(price);
        }
    }

    /**
     * Creates and persists a test employee.
     *
     * @param badgeId the 8-digit physical badge identifier
     * @param loginName the login name
     * @param pin the 4-digit PIN, opening the REGISTER (hashed)
     * @param firstName the first name
     * @param lastName the last name
     * @param role the employee role
     * @param backOfficePassword the back-office password, opening the
     *        ADMINISTRATION (hashed), or null for an employee who has no
     *        back office
     */
    private void createEmployee(String badgeId, String loginName, String pin, String firstName, String lastName,
                                Employee.EmployeeRole role, String backOfficePassword) {
        Employee emp = new Employee();
        emp.badgeId = badgeId; // Identifiant physique du badge (8 chiffres)
        emp.loginName = loginName;
        emp.password = Employee.hashPassword(pin); // Hash du PIN (4 chiffres)
        emp.firstName = firstName;
        emp.lastName = lastName;
        emp.email = firstName.toLowerCase() + "." + lastName.toLowerCase() + "@test.com";
        emp.role = role;
        emp.active = true;
        emp.setBackOfficePassword(backOfficePassword);
        // The password comes from this file, so it is known outside the
        // account: the first sign-in is confined to the password screen.
        emp.mustChangePassword = backOfficePassword != null;
        emp.persist();
    }

    /**
     * Creates and persists a product family.
     *
     * @param code the stable family code
     * @param description the display description
     * @return the persisted family
     */
    private ProductFamily createFamily(String code, String description) {
        ProductFamily f = new ProductFamily();
        f.code = code;
        f.description = description;
        f.persist();
        return f;
    }

    /**
     * Creates a product mirroring one engine catalog row and links it to a
     * family. The EAN, name, description, brand, reference weight/volume,
     * type and unit come verbatim from the engine's ProductImporterClient
     * CSV; the PLU and icon are register-local additions.
     *
     * @param family the family to attach the product to
     * @param name the product name
     * @param description the product description
     * @param brand the product brand
     * @param plu the register-local PLU code, or null
     * @param ean the EAN code (shared key with the engine)
     * @param icon the register-local display icon
     * @param referenceWeight the reference weight (engine CSV value)
     * @param referenceVolume the reference volume (engine CSV value)
     * @param productType the product type (UNIT or WEIGHT)
     * @param unitName the unit name (engine CSV value)
     * @return the persisted product
     */
    private Product createProduct(ProductFamily family, String name, String description, String brand,
                                  String plu, String ean, String icon,
                                  String referenceWeight, String referenceVolume,
                                  ProductType productType, String unitName) {
        Product p = new Product();
        p.name = name;
        p.description = description;
        p.brand = brand;
        p.plu = plu;
        p.ean = ean;
        p.icon = icon;
        p.referenceWeight = new BigDecimal(referenceWeight);
        p.referenceVolume = new BigDecimal(referenceVolume);
        p.productType = productType;
        p.unitName = unitName;
        p.active = true;
        p.persist();
        family.products.add(p);
        return p;
    }

    /**
     * Creates a product flagged as forbidden to sale and links it to a
     * family (register-only test fixture, non-engine EAN on purpose).
     *
     * @param family the family to attach the product to
     * @param name the product name
     * @param plu the PLU code, or null
     * @param ean the EAN code
     * @param icon the display icon
     * @param priceHT the price excluding tax (5.5% rate)
     * @param priceTTC the price including tax
     */
    private void createForbiddenProduct(ProductFamily family, String name, String plu, String ean, String icon,
                                        String priceHT, String priceTTC) {
        Product p = new Product();
        p.name = name;
        p.plu = plu;
        p.ean = ean;
        p.icon = icon;
        p.productType = ProductType.UNIT;
        p.unitName = "pc";
        p.active = true;
        p.forbiddenToSale = true;
        p.persist();

        createPrice(p, priceHT, priceTTC, "0.0550", 0);
        family.products.add(p);
    }

    /**
     * Creates a price row mirroring one engine DEFAULT price row: exact
     * HT/TTC/rate/priority, window opening at {@link #PRICE_START} with no
     * end — so both systems resolve the same figure for any in-window date.
     *
     * @param product the priced product
     * @param priceHT the price excluding tax (engine CSV value)
     * @param priceTTC the price including tax (engine CSV value)
     * @param vatRate the VAT rate (engine CSV value, e.g. "0.2000")
     * @param priority the resolution priority (higher wins)
     */
    private void createPrice(Product product, String priceHT, String priceTTC, String vatRate, int priority) {
        Price p = new Price();
        p.product = product;
        p.priceExcludingTax = new BigDecimal(priceHT);
        p.priceIncludingTax = new BigDecimal(priceTTC);
        p.vat = seededVatRates.get(vatRate);
        p.priority = priority;
        p.startDateTime = PRICE_START;
        p.endDateTime = null;
        p.persist();
    }

    /**
     * Writes the VAT referential, once, before any price names it.
     *
     * <p>Five regimes and no more: a price of this seed carries one of the
     * five rates {@link #SEEDED_VAT_RATES} declares, and a rate outside them
     * would leave its price without a regime — which the scan paths survive by
     * falling back on the administered default, exactly as for a product with
     * no price at all.
     */
    private void loadVatRates() {
        seededVatRates.clear();
        for (String[] seeded : SEEDED_VAT_RATES) {
            VatRate regime = new VatRate(Integer.valueOf(seeded[1]),
                    new BigDecimal(seeded[0]), seeded[2]);
            regime.persist();
            seededVatRates.put(seeded[0], regime);
        }
    }

    /**
     * Creates and persists a payment coupon type.
     *
     * @param code the stable technical code of the type
     * @param label the label shown on the payment screen
     * @param matchPattern the recognition regex
     * @param amountSource where the amount comes from (ENCODED or MANUAL)
     * @param amountPattern the extraction regex (first group = cents), or null
     * @param priority the matching priority (lower runs first)
     * @return the persisted type, so the caller can administer its range
     */
    private CouponType createCouponType(String code, String label, String matchPattern,
                                        CouponType.AmountSource amountSource, String amountPattern, int priority) {
        CouponType ct = new CouponType();
        ct.code = code;
        ct.label = label;
        ct.matchPattern = matchPattern;
        ct.amountSource = amountSource;
        ct.amountPattern = amountPattern;
        ct.active = true;
        ct.priority = priority;
        ct.persist();
        return ct;
    }

    /**
     * Creates and persists a deposit-return coupon type (negative ticket line
     * at scan time); the amount is necessarily encoded in the number.
     *
     * @param code the stable technical code of the type
     * @param label the label shown on the ticket line
     * @param matchPattern the recognition regex
     * @param amountPattern the extraction regex (first group = cents)
     * @param priority the matching priority (lower runs first)
     * @return the persisted type, so the caller can administer its range
     */
    private CouponType createDepositCouponType(String code, String label, String matchPattern,
                                               String amountPattern, int priority) {
        CouponType ct = new CouponType();
        ct.code = code;
        ct.label = label;
        ct.matchPattern = matchPattern;
        ct.amountSource = CouponType.AmountSource.ENCODED;
        ct.amountPattern = amountPattern;
        ct.active = true;
        ct.priority = priority;
        ct.depositLine = true;
        ct.persist();
        return ct;
    }

    /**
     * Creates the register's store row — code 0101, the engine's primary
     * test store (Lille), so every valuation request targets a store the
     * engine knows.
     */
    private void createStore() {
        Store s = new Store();
        s.name = "Intermarché Test 1";
        s.code = "0101";
        s.address = new Address();
        s.address.streetLine1 = "1 Rue du Test";
        s.address.streetLine2 = "ZI Nord";
        s.address.postalCode = "59000";
        s.address.country = "France";
        s.address.city = "Lille";
        // A receipt can be anonymous; an invoice cannot. Its seller's block and its
        // legal footer state who is liable, so a demonstration store without a legal
        // identity issues a document with an empty header and an empty foot.
        s.legalName = "SA TEST DISTRIBUTION";
        s.rcs = "Lille Métropole 123 456 789";
        s.shareCapital = new java.math.BigDecimal("40000");
        s.siret = "12345678900017";
        s.vatNumber = "FR12123456789";
        s.phone = "03 20 00 00 00";
        s.fax = "03 20 00 00 01";
        s.persist();
    }
}
