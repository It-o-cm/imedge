package com.intermarche.pos.domain.catalog.attribute;

/**
 * One catalog entry: the contract of a well-known product attribute
 * (BO-02-03-18).
 * <p>
 * A definition names the attribute the register and the back-office both
 * understand — its stable {@code code}, its {@link ProductAttributeType},
 * the operator-facing {@code label} and the {@code defaultValue} that applies
 * when a product carries no value for that code. It is the product-attribute
 * counterpart of {@code PosSettingsService.Def}, kept deliberately minimal:
 * the sale-behaviour wiring lives in {@link ProductAttributes}, not here.
 *
 * @param code the stable storage code (for example {@code VAT_EXEMPT})
 * @param type the value type driving parsing and the admin widget
 * @param label the operator-facing label (French)
 * @param defaultValue the value applying when a product carries no row for
 *        this code
 */
public record ProductAttributeDef(String code, ProductAttributeType type,
                                  String label, String defaultValue) {
}
