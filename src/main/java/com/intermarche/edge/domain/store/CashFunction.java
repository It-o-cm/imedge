package com.intermarche.edge.domain.store;

import java.util.ArrayList;
import java.util.List;

/**
 * The FUNCTION KEYS of the register's sale screen that a cash menu may show or
 * hide (BO-03-10-16, BO-03-10-17).
 *
 * <p>Each constant names one key by the screen paths it opens — the link of
 * an anchor or the action of a form — because that is what the sale screen
 * renders and what a register can hide without rewriting its page: a model
 * that hides a function hides every element of the key grid leading there.
 * A key reached by two paths (park and resume, invoice and the Portuguese
 * fiscal documents) carries both.
 *
 * <p>The store node and the registers hold the SAME enum: the node offers its
 * labels on the menu screen, the register turns the codes it receives into
 * the selectors it hides.
 */
public enum CashFunction {

    /** Cancel the selected line. */
    CANCEL_LINE("Annuler la ligne", "/action/cancelLine"),

    /** Cancel by scanning the article. */
    CANCEL_BY_SCAN("Annuler par scan", "/action/cancel-by-scan"),

    /** Cancel a group of articles. */
    CANCEL_GROUP("Annuler un groupe d'articles", "/manual?cancel=true"),

    /** Change the quantity. */
    QUANTITY("Quantité", "/action/price-mod/quantity"),

    /** Key a gross weight before the article. */
    MANUAL_WEIGHT("Poids manuel", "/manual-weight", "/action/manual-weight/cancel"),

    /** Mark an article to be collected elsewhere. */
    COLLECT("Article à enlever", "/action/collect"),

    /** Repeat the last article. */
    REPEAT("Répéter l'article", "/action/repeat"),

    /** The fruit and vegetable screen. */
    FRUITS("Fruits et légumes", "/fruits"),

    /** Direct entry of a code. */
    MANUAL("Saisie directe", "/manual"),

    /** The article keys of level zero, one per administered article (BO-03-01-04/05). */
    LEVEL_ZERO("Touches articles directes", "/action/manual-add-known"),

    /** Article search. */
    SEARCH("Recherche article", "/search"),

    /** The subtotal. */
    SUBTOTAL("Sous-total", "/action/subtotal"),

    /** The read-only article information. */
    ARTICLE_INFO("Information article", "/article-info"),

    /** Lock the register. */
    LOCK("Verrouiller", "/action/lock"),

    /** Close the register. */
    CLOSE("Fermer la caisse", "/close"),

    /** The cash session screen. */
    SESSION("Session de caisse", "/session"),

    /** The float calculator. */
    CASH_CALCULATOR("Calculatrice de fond", "/cash-calculator"),

    /** The training mode toggle. */
    TRAINING("Mode formation", "/action/training"),

    /** The declaration of the vignettes taken at the start of the shift (BO-11-01-20). */
    VIGNETTES("Vignettes", "/vignettes"),

    /** Park and resume a ticket. */
    PARK("Mise en attente", "/parked", "/action/parked/park"),

    /** Abandon the ticket. */
    ABANDON("Annuler le ticket", "/abandon"),

    /** The return mode toggle. */
    RETURN_MODE("Mode retour", "/action/return-mode"),

    /** The return of a sold article. */
    RETURN("Retour article", "/return"),

    /** The invoice and the Portuguese fiscal documents. */
    INVOICE("Facture", "/invoice", "/fiscal-pt"),

    /** The search of a closed ticket. */
    REPRINT("Recherche ticket", "/reprint"),

    /** Reprint the last ticket. */
    PRINT_LAST("Dernier ticket", "/action/print-last"),

    /** Reprint the last barcode. */
    PRINT_LAST_BARCODE("Dernier code-barres", "/action/print-last-barcode"),

    /** Reprint the last card slip. */
    PRINT_LAST_CARD("Dernier duplicata carte", "/action/print-last-card"),

    /** Send the last ticket by email. */
    TICKET_EMAIL("Ticket par email", "/ticket-email"),

    /** The customer documents. */
    CUSTOMER_DOCUMENTS("Documents client", "/customer-documents"),

    /** The customer's tax identifier on the sale. */
    SALE_TAX_ID("Numéro fiscal du client", "/sale-tax-id"),

    /** The fidelity screen. */
    FIDELITY("Fidélité", "/fidelity"),

    /** The balance of a voucher or a card. */
    VOUCHER_BALANCE("Solde bon ou carte", "/voucher-balance"),

    /** A discount on the line price. */
    DISCOUNT("Remise sur prix", "/action/price-mod/remise"),

    /** A discount on the ticket. */
    TICKET_DISCOUNT("Remise ticket", "/action/price-mod/global_remise"),

    /** A cash movement. */
    CASH_MOVEMENT("Mouvement de caisse", "/cash-movement"),

    /** A withdrawal. */
    WITHDRAWAL("Prélèvement", "/withdrawal"),

    /** A float supply. */
    FLOAT_SUPPLY("Apport de monnaie", "/apport"),

    /** A transfer between tenders. */
    TRANSFER("Transfert de règlement", "/transfer"),

    /** A financial movement. */
    FINANCIAL_MOVEMENT("Mouvement financier", "/financial-movement"),

    /** A change order. */
    CHANGE_ORDER("Commande de monnaie", "/change-order"),

    /** The return of a deposit. */
    DEPOSIT_RETURN("Déconsignation", "/action/deposit"),

    /** The cash level of the coin machine (BO-11-03-03). */
    CASH_CHANGER("Monnayeur", "/cash-changer"),

    /** The forced opening of the drawer. */
    DRAWER_OPEN("Ouverture du tiroir", "/drawer-open"),

    /** The call to a supervisor. */
    SUPERVISOR("Appel superviseur", "/supervisor"),

    /** The forced degraded monetique. */
    MONETICS_DEGRADED("Monétique dégradée", "/action/monetics-degraded"),

    /** The recall of a code. */
    RECALL("Rappel par code", "/recall"),

    /** The back-office message. */
    BO_MESSAGE("Message du back-office", "/bo-message"),

    /** The order taken at the register. */
    ORDER("Commande client", "/order"),

    /** The operator badge print. */
    BADGE_PRINT("Badge opérateur", "/badge-print"),

    /** The PIN change. */
    PIN_CHANGE("Changer le code PIN", "/pin-change"),

    /** The theme choice. */
    THEME("Thème", "/theme-select"),

    /** The language choice. */
    LANGUAGE("Langue", "/language-select"),

    /** The external program of the register. */
    EXTERNAL_PROGRAM("Programme externe", "/action/external-program"),

    /** The third-party applications, one key each (BO-03-09-27). */
    EXTERNAL_APPS("Applications tierces", "/external-app"),

    /** The single-queue availability (BO-03-09-10). */
    SINGLE_QUEUE("File unique", "/action/single-queue");

    /** The French label the menu screen shows. */
    private final String label;

    /** The screen paths the key leads to. */
    private final List<String> paths;

    /**
     * Builds a function with its label and paths.
     *
     * @param label the French label
     * @param paths the screen paths of the key
     */
    CashFunction(String label, String... paths) {
        this.label = label;
        this.paths = List.of(paths);
    }

    /**
     * Returns the French label the menu screen shows.
     *
     * @return the label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Returns the screen paths the key leads to.
     *
     * @return the paths, never empty
     */
    public List<String> getPaths() {
        return paths;
    }

    /**
     * Resolves a function by its name, tolerating case and blanks.
     *
     * @param raw the name, possibly null
     * @return the function, or null when the name is unknown
     */
    public static CashFunction fromName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (CashFunction function : values()) {
            if (function.name().equalsIgnoreCase(raw.trim())) {
                return function;
            }
        }
        return null;
    }

    /**
     * Parses a semicolon list of function names, the unknown ones and the
     * duplicates dropped, in the order written.
     *
     * @param raw the list, possibly null
     * @return the functions, empty when none is readable
     */
    public static List<CashFunction> parseList(String raw) {
        List<CashFunction> functions = new ArrayList<>();
        if (raw == null) {
            return functions;
        }
        for (String part : raw.split(";")) {
            CashFunction function = fromName(part);
            if (function != null && !functions.contains(function)) {
                functions.add(function);
            }
        }
        return functions;
    }
}
