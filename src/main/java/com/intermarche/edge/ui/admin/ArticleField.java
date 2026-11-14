package com.intermarche.edge.ui.admin;

/**
 * The data of the ARTICLE FICHE a profile can be granted or denied the right
 * to change (BO-02-03-33): "cette fonction doit être impérativement
 * administrable (rattachée à un profil) pour chaque donnée de la fiche".
 *
 * <p>One constant per group of fields the fiche saves together; the codes are
 * what {@code ArticleFieldRight} stores and the labels what the rights screen
 * shows. {@link #DELETE} is the removal of the article itself.
 */
public enum ArticleField {

    /** The EAN, the article's portable identity. */
    EAN("Code EAN"),
    /** The commercial name. */
    NAME("Libellé commercial"),
    /** The checkout label. */
    CHECKOUT_LABEL("Libellé encaissement"),
    /** The internal code. */
    INTERNAL_CODE("Code interne"),
    /** The additional sale codes. */
    SALE_CODES("Codes de vente supplémentaires"),
    /** The PLU. */
    PLU("PLU"),
    /** The product type. */
    TYPE("Type"),
    /** The unit of measure. */
    UNIT("Unité de mesure"),
    /** The reference weight and volume. */
    MEASURES("Poids et volume de référence"),
    /** The gift-card amount. */
    GIFT_CARD("Carte cadeau"),
    /** The brand and the description. */
    BRAND("Marque et description"),
    /** The activity, the sale ban and the age control. */
    STATUS("Statut, vente interdite et contrôle d'âge"),
    /** The cashier messages of the article. */
    MESSAGES("Messages en caisse"),
    /** The linked and complementary articles. */
    LINKS("Articles liés et complémentaires"),
    /** The checkout mode. */
    CHECKOUT_MODE("Mode de passage en caisse"),
    /** The VAT code. */
    VAT("Code TVA"),
    /** The filing in the nomenclature. */
    NOMENCLATURE("Rattachement à la nomenclature"),
    /** The supplier and its article. */
    SUPPLIER("Fournisseur"),
    /** The declared and custom attributes. */
    ATTRIBUTES("Attributs"),
    /** The on-screen article-code flag. */
    PRESENTATION("Code article à l'écran et sur le ticket"),
    /** The removal of the article. */
    DELETE("Suppression de l'article");

    /** The French label shown on the rights screen. */
    private final String label;

    /**
     * Builds a field with its label.
     *
     * @param label the French label
     */
    ArticleField(String label) {
        this.label = label;
    }

    /**
     * Returns the French label of the field.
     *
     * @return the label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Returns the stored code of the field.
     *
     * @return the constant name
     */
    public String getCode() {
        return name();
    }
}
