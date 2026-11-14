package com.intermarche.edge.service;

import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.catalog.ProductType;
import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.domain.people.Employee;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The BASE DATA a point of sale trains its staff on before it opens
 * (BO-03-10-18): a training nomenclature, test articles priced in it, and a
 * generic register user.
 *
 * <p>Installed on demand from the back office and IDEMPOTENT: a family, an
 * article or the user already present is left as it is, so pressing the
 * button twice installs nothing twice. The data travel to the registers by
 * the referential like any other article, family and employee; the training
 * mode of the register then sells them without touching the shop's figures.
 */
@ApplicationScoped
public class TrainingDataService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TrainingDataService.class);

    /** The code of the training family. */
    public static final String FAMILY_CODE = "FORMATION";

    /** The badge of the generic register user. */
    public static final String USER_BADGE = "99990000";

    /** The login of the generic register user. */
    public static final String USER_LOGIN = "formation";

    /** The PIN of the generic register user. */
    public static final String USER_PIN = "9999";

    /** The test articles: EAN, name, price including tax. */
    static final List<String[]> ARTICLES = List.of(
            new String[]{"3770000990010", "ARTICLE TEST 1", "1.00"},
            new String[]{"3770000990027", "ARTICLE TEST 2", "2.50"},
            new String[]{"3770000990034", "ARTICLE TEST 3", "10.00"});

    /**
     * Installs what is missing of the training data.
     *
     * @return how many rows were created: family, articles and user together
     */
    @Transactional
    public int install() {
        LOGGER.info("Entering method install");
        int created = 0;
        ProductFamily family = ProductFamily.findByCode(FAMILY_CODE);
        if (family == null) {
            family = new ProductFamily();
            family.code = FAMILY_CODE;
            family.description = "Formation caisse";
            family.persist();
            created++;
        }
        VatRate vat = VatRate.<VatRate>find("order by number").firstResult();
        for (String[] article : ARTICLES) {
            if (Product.findByEan(article[0]) != null) {
                continue;
            }
            Product product = new Product();
            product.ean = article[0];
            product.name = article[1];
            product.description = article[1];
            product.productType = ProductType.UNIT;
            product.unitName = "pc";
            product.active = true;
            product.persist();
            family.products.add(product);
            Price price = new Price();
            price.product = product;
            price.priceIncludingTax = new BigDecimal(article[2]);
            price.vat = vat;
            BigDecimal rate = vat == null || vat.rate == null ? BigDecimal.ZERO : vat.rate;
            price.priceExcludingTax = price.priceIncludingTax.divide(BigDecimal.ONE.add(rate), 4,
                    RoundingMode.HALF_UP);
            price.startDateTime = LocalDateTime.of(2000, 1, 1, 0, 0);
            price.persist();
            created++;
        }
        if (Employee.findLogin(USER_LOGIN) == null && Employee.findLogin(USER_BADGE) == null) {
            Employee user = new Employee();
            user.badgeId = USER_BADGE;
            user.loginName = USER_LOGIN;
            user.password = Employee.hashPassword(USER_PIN);
            user.firstName = "Caisse";
            user.lastName = "Formation";
            user.email = "formation@formation.local";
            user.role = Employee.EmployeeRole.CASHIER;
            user.active = true;
            user.persist();
            created++;
        }
        LOGGER.info("Exiting method install");
        return created;
    }
}
