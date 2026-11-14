package com.intermarche.edge.domain.catalog;

import com.intermarche.edge.domain.catalog.attribute.ProductAttributes;

import java.math.BigDecimal;

/**
 * Resolves the VAT a sale line of an article is ventilated at (BO-02-03-26,
 * -27): the rate, and whether it is an EXEMPTION — a statement distinct from
 * a rate of zero, which the documents print apart.
 *
 * <p>Precedence, most authoritative first:
 * <ol>
 *   <li>the article's own exemption flag ({@code VAT_EXEMPT}): exempt, rate
 *       zero, whatever the regimes say;</li>
 *   <li>the VAT regime the article itself names ({@code VAT_NUMBER}),
 *       integrated with the article from the Gestion Commerciale or changed on
 *       its fiche — the article is where the tax status lives;</li>
 *   <li>the regime of the price the line is rung at, as it always was;</li>
 *   <li>the register's default rate when neither says anything.</li>
 * </ol>
 * A regime declared exempt in the VAT referential makes the line exempt too.
 *
 * <p>Non-instantiable: one static entry, called at the snapshot moment of a
 * sale line by every road an article is rung by.
 */
public final class ArticleVat {

    /**
     * Non-instantiable utility.
     */
    private ArticleVat() {
    }

    /**
     * The VAT of one line: its rate and whether it is an exemption.
     *
     * @param rate the rate as a fraction of one, zero when exempt
     * @param exempt whether the line is exempt of VAT
     */
    public record Resolution(BigDecimal rate, boolean exempt) {
    }

    /**
     * Resolves the VAT of a line of the given article rung at the given price.
     *
     * @param product the article, or null
     * @param price the price the line is rung at, or null
     * @param defaultRate the register's default rate
     * @return the resolution, never null
     */
    public static Resolution resolve(Product product, Price price, BigDecimal defaultRate) {
        BigDecimal rate = price != null && price.vatRate() != null ? price.vatRate() : defaultRate;
        boolean exempt = price != null && price.vat != null && price.vat.exempt;
        Integer number = ProductAttributes.vatNumber(product);
        VatRate own = number == null ? null : VatRate.findByNumber(number);
        if (own != null) {
            rate = own.rate;
            exempt = own.exempt;
        }
        if (ProductAttributes.vatExempt(product)) {
            exempt = true;
        }
        return new Resolution(exempt ? BigDecimal.ZERO : rate, exempt);
    }
}
