package com.intermarche.edge.service;

import com.intermarche.edge.domain.setting.PosSetting;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The register's BACK-OFFICE PARAMETERS: the typed catalog of known keys
 * (label, type, default) and the cached access to their current values.
 * <p>
 * Values live in {@code pos_settings} — administered on the store node's
 * back office and distributed by the referential pull (domain SETTINGS) —
 * and an ABSENT row means the default applies. Two keys keep their
 * historical {@code application.properties} fallback ({@code
 * pos.display.show-ean}, {@code pos.auth.idle-lockout-seconds}): a value
 * set there still works on a node that has never been administered.
 * <p>
 * The whole table is cached in memory and invalidated on every admin save
 * and every pull apply — settings are read on hot paths (the lock filter,
 * the polls) and must never cost a query per request.
 * <p>
 * Between the node's own overrides and the catalog default sits the ECHELON
 * inheritance (BO-02-05-04): when this node carries a {@code pos.pdv.number},
 * a key with no local override is resolved from the PDV's echelon chain
 * (PDV &rarr; enseigne &rarr; country) through {@link EchelonSettingService}.
 * A local override therefore always wins and survives a fresh pull of the
 * echelon defaults — the two live in different tables. The inherited values
 * are cached alongside the local ones and dropped together, so the echelon
 * lookup never touches the database on a hot path. On a node with no
 * {@code pos.pdv.number} the layer is inert and behaviour is unchanged, which
 * keeps a standalone register selling exactly as before.
 */
@ApplicationScoped
public class PosSettingsService {

    private static final Logger LOGGER = Logger.getLogger(PosSettingsService.class);

    /** Value type of a catalog entry — drives the admin form widget. */
    public enum Type { BOOL, INT, TEXT }

    /**
     * One catalog entry: the contract of a known parameter key.
     *
     * @param key the storage key
     * @param type the value type
     * @param section the admin-page section heading
     * @param label the operator-facing label (French)
     * @param hint the operator-facing explanation (French)
     * @param defaultValue the value applying when no row overrides it
     * @param configFallback the legacy MicroProfile key consulted before the
     *        default when no row exists, or null
     */
    public record Def(String key, Type type, String section, String label,
                      String hint, String defaultValue, String configFallback) {
    }

    /**
     * The catalog of administrable parameters — the admin page renders it
     * in order, sections grouped. Adding a parameter is one entry here plus
     * its consumer.
     */
    public static final List<Def> CATALOG = List.of(
        new Def("display.show-ean", Type.BOOL, "AFFICHAGE",
                "EAN sur les écrans et le ticket",
                "L'EAN accompagne le libellé article sur la visu caisse, la visu client et le ticket imprimé (LC-02-04).",
                "false", "pos.display.show-ean"),
        new Def("auth.idle-lockout-seconds", Type.INT, "SESSION CAISSE",
                "Pause automatique (secondes)",
                "Délai d'inactivité avant verrouillage automatique de la caisse. 0 = désactivé (LC-01-03-02).",
                "0", "pos.auth.idle-lockout-seconds"),
        new Def("gesture.endorsement-required", Type.BOOL, "GESTES DE PRIX",
                "Autorisation superviseur sur les gestes",
                "Remises, discounts, forçages et remises ticket exigent un avenant manager. Désactivé : le caissier applique seul (LC-03-02-08).",
                "true", null),
        new Def("discount.line-max-percent", Type.INT, "GESTES DE PRIX",
                "Discount article : % maximum",
                "Pourcentage maximal d'un discount de ligne (LC-03-02-07).",
                "100", null),
        new Def("discount.global-max-percent", Type.INT, "GESTES DE PRIX",
                "Remise ticket : % maximum",
                "Pourcentage maximal d'une remise ticket (LC-03-02-13).",
                "100", null),
        new Def("discount.presets", Type.TEXT, "GESTES DE PRIX",
                "Remises et rabais prédéfinis",
                "Touches de remise prêtes à l'emploi, séparées par « ; ». Une entrée est un libellé, puis « > », puis PERCENT ou AMOUNT, « : » et la valeur ; ajouter « ,CUMUL » autorise cette remise sur un article déjà remisé ou en promotion. Exemple : « -20%>PERCENT:20 ; 2€ de moins>AMOUNT:2,CUMUL ». Vide : aucune touche prédéfinie, seule la saisie libre (BO-03-07-03, BO-03-07-06, BO-03-07-11).",
                "", null),
        new Def("discount.cumulative-on-discounted", Type.BOOL, "GESTES DE PRIX",
                "Cumul sur un article déjà remisé",
                "Une remise ou un rabais saisi librement s'applique sur un article qui porte déjà une remise manuelle ou une promotion automatique. Désactivé : le geste est refusé sur cette ligne. Une touche prédéfinie portant CUMUL passe outre ce défaut (BO-03-07-11).",
                "true", null),
        new Def("discount.global-on-discounted", Type.BOOL, "GESTES DE PRIX",
                "Remise globale sur les articles déjà remisés",
                "La remise ou le rabais posé sur le total du ticket porte aussi sur les lignes qui portent déjà une remise manuelle ou une promotion automatique. Désactivé : ces lignes sortent de la base ET de la répartition — la remise globale coûte alors exactement ce qu'elle annonce sur les lignes restantes (BO-03-07-12).",
                "true", null),
        new Def("discount.enabled", Type.BOOL, "GESTES DE PRIX",
                "Remises et rabais actifs",
                "Active les gestes remise et rabais en caisse. Désactivé : le caissier ne peut appliquer ni remise ni rabais, ni à la ligne ni au ticket (BO-03-07-01).",
                "true", null),
        new Def("price.show-original-on-force", Type.BOOL, "GESTES DE PRIX",
                "Afficher le prix d'origine au forçage",
                "Le prix initial de l'article est rappelé à l'écran lors d'un forçage de prix. Désactivé : le prix d'origine est masqué (BO-10-07-12).",
                "true", null),
        new Def("customer.message-open", Type.TEXT, "AFFICHEUR CLIENT",
                "Message caisse ouverte",
                "Texte affiché sur l'écran client en attente de transaction (LC-10-01-20).",
                "Bienvenue", null),
        new Def("customer.message-closed", Type.TEXT, "AFFICHEUR CLIENT",
                "Message caisse fermée",
                "Texte affiché sur l'écran client quand la caisse est fermée (LC-10-01-21).",
                "Caisse fermée", null),
        new Def("customer.qr-enabled", Type.BOOL, "AFFICHEUR CLIENT",
                "QR code ticket sur l'afficheur client",
                "Le QR code de récupération du ticket dématérialisé s'affiche sur l'écran client en fin de transaction. Désactivé : aucun QR code (BO-10-07-02).",
                "true", null),
        new Def("parking.print-receipt", Type.BOOL, "TICKETS",
                "Imprimer le ticket de mise en attente",
                "Un reçu portant le numéro de reprise sort à chaque mise en attente (LC-04-01-02).",
                "true", null),
        new Def("ticket.email-format", Type.TEXT, "TICKETS",
                "Forme du ticket envoyé par email",
                "Comment le ticket voyage dans l'email : BODY (dans le corps du message, défaut), ATTACHMENT (en pièce jointe) ou BOTH (les deux). Une valeur inconnue retombe sur BODY (LC-08-02-07/08).",
                "BODY", null),
        new Def("ticket.email-editable", Type.BOOL, "TICKETS",
                "Adresse du client fidélisé modifiable",
                "L'adresse email récupérée du référentiel client peut être corrigée en caisse avant l'envoi. Désactivé : elle est envoyée telle quelle et seul un client sans adresse connue en fait saisir une (LC-08-02-10).",
                "true", null),
        new Def("currency.symbol", Type.TEXT, "DEVISE",
                "Symbole monétaire",
                "Symbole accolé aux montants imprimés sur les documents de caisse — E, EUR, € selon ce que l'imprimante sait sortir. Vide : les montants sont imprimés nus (BO-10-07-05).",
                "E", null),
        new Def("currency.symbol-print", Type.BOOL, "DEVISE",
                "Imprimer le symbole monétaire",
                "Les montants des documents de caisse portent le symbole monétaire. Désactivé : ils sont imprimés nus, ce que demandent les magasins dont le papier est déjà libellé (BO-10-07-04).",
                "true", null),
        new Def("currency.symbol-position", Type.TEXT, "DEVISE",
                "Position du symbole monétaire",
                "Côté du montant où le symbole est imprimé : RIGHT (après le montant, défaut) ou LEFT (avant). Une valeur inconnue retombe sur RIGHT (BO-10-07-06).",
                "RIGHT", null),
        new Def("ticket.line-order", Type.TEXT, "TICKETS",
                "Ordre des articles sur le ticket",
                "Ordre d'impression des articles sur le ticket de caisse : ENTRY (ordre d'enregistrement, défaut), LABEL (alphabétique par libellé) ou FAMILY (regroupés sous le libellé de leur famille). Une valeur inconnue retombe sur ENTRY (LC-08-01-07).",
                "ENTRY", null),
        new Def("ticket.second-code-print", Type.BOOL, "TICKETS",
                "Imprimer le second code de l'article",
                "Le code interne rattaché à l'EAN13 est imprimé sous la ligne de l'article qui en porte un — ce que demandent les produits dématérialisés. Désactivé : seule la ligne article est imprimée (BO-10-02-20).",
                "false", null),
        new Def("ticket.family-alphabetical", Type.BOOL, "TICKETS",
                "Familles triées alphabétiquement",
                "Quand les articles sont regroupés par famille (ordre FAMILY), les intitulés de famille sortent dans l'ordre alphabétique, qui est le comportement par défaut. Désactivé : les familles sortent dans l'ordre d'apparition de leur premier article (BO-03-03-17).",
                "true", null),
        new Def("ticket.line-attribute", Type.TEXT, "TICKETS",
                "Attribut article de tri du ticket",
                "Code de l'attribut article servant à trier ou isoler les lignes du ticket. Vide : aucun tri par attribut (BO-03-03-18).",
                "", null),
        new Def("ticket.line-attribute-isolate", Type.TEXT, "TICKETS",
                "Isolement des lignes portant l'attribut",
                "Où sortent les lignes portant l'attribut de tri : NONE (tri seul, défaut), START (isolées en début de ticket) ou END (isolées en fin). Une valeur inconnue retombe sur NONE (BO-03-03-18).",
                "NONE", null),
        new Def("ticket.barcode-prefix", Type.TEXT, "TICKETS",
                "Préfixe du code-barres imprimé sur le ticket",
                "Préfixe ajouté devant le numéro de ticket dans le code-barres imprimé en pied de ticket, pour que les portiques ou les bornes de jeux reconnaissent la plage qui leur est réservée. Vide : le code-barres porte le numéro de ticket seul, comme aujourd'hui (BO-03-06-55).",
                "", null),
        new Def("ticket.vat-code", Type.BOOL, "TICKETS",
                "Imprimer le code du taux de TVA sur la ligne",
                "Le numéro du régime de TVA de l'article sort sur sa ligne, à côté du libellé. Désactivé : la ligne ne porte que son prix, et le détail de TVA reste dans le tableau des totaux. Activé en France, désactivé en Belgique (BO-03-03-37).",
                "false", null),
        new Def("ticket.exit-ticket", Type.BOOL, "TICKETS",
                "Ticket de sortie à la place du ticket",
                "Un ticket de sortie — titre, code-barres, total TTC, nombre d'articles et les articles les plus chers — est imprimé À LA PLACE du ticket de caisse. Désactivé : le ticket de caisse sort normalement (BO-03-03-36).",
                "false", null),
        new Def("ticket.exit-ticket-articles", Type.INT, "TICKETS",
                "Articles les plus chers sur le ticket de sortie",
                "Combien d'articles, du plus cher au moins cher, sont nommés sur le ticket de sortie. Hors de la plage 1 à 20, la valeur retombe sur 3 (BO-03-03-36).",
                "3", null),
        new Def("parking.print-resume", Type.BOOL, "TICKETS",
                "Imprimer le ticket de reprise",
                "Un ticket de reprise — date, numéro du ticket et numéro du ticket d'origine — sort à chaque reprise d'un ticket mis en attente. Désactivé : la reprise n'imprime rien (BO-03-03-07).",
                "false", null),
        new Def("ticket.line-discount-print", Type.BOOL, "TICKETS",
                "Imprimer les remises de ligne",
                "La remise, le rabais ou le prix forcé d'une ligne sort sous l'article concerné. Désactivé : seule la remise ticket sort sous les totaux (BO-03-03-22).",
                "false", null),
        new Def("ticket.immediate-discount-total", Type.BOOL, "TICKETS",
                "Imprimer le total des remises immédiates",
                "Une ligne de cumul des remises immédiates — remises de ligne et remise ticket — sort sous les totaux. Désactivé : aucune ligne de cumul (BO-03-03-24).",
                "false", null),
        new Def("print.logo-width-dots", Type.INT, "TICKETS",
                "Largeur du logo imprimé, en points",
                "Largeur à laquelle le logo est imprimé, en points de la tête thermique : 384 points soit 48 mm par défaut, jusqu'à 576 points pour toute la largeur d'un rouleau 80 mm (Epson TMH6000 IV et V). Une valeur hors de l'intervalle 8-576 retombe sur 384 (BO-03-03-14).",
                "384", null),
        new Def("credit-note.print-expiry", Type.BOOL, "TICKETS",
                "Imprimer la date de validité de l'avoir",
                "La date de fin de validité de l'avoir sort sur le bon. Désactivé : le bon ne porte que son numéro et son montant (BO-03-03-08).",
                "false", null),
        new Def("print.conditional-enabled", Type.BOOL, "IMPRESSION CONDITIONNELLE",
                "Choix d'impression en fin de transaction",
                "La caisse propose au caissier quels documents imprimer : tous, ticket de caisse, ticket carte bancaire, bon d'achat ou aucun. Désactivé : la caisse imprime comme aujourd'hui, sans écran de choix (LC-08-03-01).",
                "false", null),
        new Def("print.forced-documents", Type.TEXT, "IMPRESSION CONDITIONNELLE",
                "Documents toujours imprimés",
                "Documents imprimés d'office quel que soit le choix du caissier, séparés par des points-virgules : TICKET, CARTE, BON. Vide : le choix du caissier s'applique seul (LC-08-03-07).",
                "", null),
        new Def("print.force-ticket-glc", Type.BOOL, "IMPRESSION CONDITIONNELLE",
                "Forcer le ticket comportant un article sous GLC",
                "Un ticket de caisse comprenant un article soumis à la Garantie Légale de Conformité est imprimé d'office (LC-08-03-09).",
                "true", null),
        new Def("print.force-card-credit", Type.BOOL, "IMPRESSION CONDITIONNELLE",
                "Forcer le ticket carte bancaire de type crédit",
                "Le ticket carte bancaire d'un remboursement (retour article, ticket retour) est imprimé d'office (LC-08-03-10).",
                "true", null),
        new Def("print.force-card-signature", Type.BOOL, "IMPRESSION CONDITIONNELLE",
                "Forcer le ticket carte bancaire à signature",
                "Le ticket carte bancaire demandant la signature du client est imprimé d'office (LC-08-03-11).",
                "true", null),
        new Def("print.force-card-tna", Type.BOOL, "IMPRESSION CONDITIONNELLE",
                "Forcer le ticket carte bancaire TNA",
                "Le ticket carte bancaire d'une transaction non aboutie, portant la mention « abandon débit », est imprimé d'office (LC-08-03-12).",
                "true", null),
        new Def("payment.degraded-mode", Type.BOOL, "MONÉTIQUE",
                "Mode dégradé monétique",
                "Bascule manuelle en cas de coupure vers les serveurs monétique : les paiements carte sont acceptés immédiatement, sans interroger le TPE (BO-03-12-05).",
                "false", null),
        new Def("payment.degraded-forced-endorsement", Type.BOOL, "MONÉTIQUE",
                "Autorisation superviseur pour le mode dégradé forcé",
                "L'activation et la levée du mode dégradé monétique forcé exigent un aval superviseur. Désactivé : l'opérateur décide seul (LC-07-08-03/04).",
                "true", null),
        new Def("payment.degraded-forced-minutes", Type.INT, "MONÉTIQUE",
                "Durée du mode dégradé forcé (minutes)",
                "Au bout de ce délai, le mode dégradé monétique forcé se lève tout seul : l'opérateur qui l'a activé ne sera pas celui qui sera encore là trois heures plus tard (LC-07-08-04).",
                "60", null),
        new Def("terminal.pdv-label-enabled", Type.BOOL, "MONÉTIQUE",
                "Ajouter le Label Point De Vente à la requête monétique",
                "Le Label Point De Vente administré ci-dessous est ajouté à chaque requête envoyée au client monétique. Désactivé : la requête ne le porte pas (BO-03-09-31).",
                "false", null),
        new Def("terminal.pdv-label", Type.TEXT, "MONÉTIQUE",
                "Label Point De Vente",
                "Le Label Point De Vente tel que l'acquéreur l'a écrit, sous la forme {TAG}valeur : la caisse l'émet verbatim, elle ne compose ni le tag ni la valeur. Vide : rien n'est ajouté, même si l'ajout est activé (BO-03-09-31).",
                "", null),
        new Def("ticket.header-message", Type.TEXT, "MESSAGES TICKET",
                "Message en début de ticket",
                "Texte imprimé en tête du ticket, sous l'adresse du magasin. Vide : aucun message (BO-03-08-03).",
                "", null),
        new Def("ticket.footer-message", Type.TEXT, "MESSAGES TICKET",
                "Message en fin de ticket",
                "Texte imprimé en pied du ticket, après la formule de politesse. Vide : aucun message (BO-03-08-03, BO-03-08-05).",
                "", null),
        new Def("drawer.open-on-payment", Type.BOOL, "TIROIR",
                "Ouverture du tiroir au paiement",
                "Le tiroir s'ouvre sur les règlements physiques (espèces, chèque, titres-restaurant). Désactivé : le tiroir reste fermé au paiement (BO-10-02-12).",
                "true", null),
        new Def("drawer.open-on-login", Type.BOOL, "TIROIR",
                "Ouverture du tiroir à la prise de poste",
                "Le tiroir s'ouvre au déverrouillage de la caisse pour installer le fond. Désactivé : le tiroir reste fermé à la connexion (BO-10-02-25).",
                "true", null),
        new Def("backup.manual-endorsement", Type.BOOL, "SECOURS MONETIQUE",
                "Autorisation superviseur pour la validation manuelle",
                "La validation manuelle d'un paiement 'secours monétique' — saisie du montant accepté sans lecture du QR-code de retour — exige un aval superviseur. Désactivé : l'opérateur valide seul (LC-07-07-09).",
                "true", null),
        new Def("backup.method-labels", Type.TEXT, "SECOURS MONETIQUE",
                "Table des moyens de paiement du secours monétique",
                "Correspondance entre l'identifiant renvoyé par le terminal mobile et le libellé enregistré sur le ticket, sous la forme ID=LIBELLE séparés par des virgules. Un identifiant absent de la table est enregistré en 'SECOURS MONETIQUE' (LC-07-07-08).",
                "20=CB EMV,43=AMERICAN EXPRESS,53=CB SANS CONTACT,3F=CARTE TRD CB,1010=TRD SODEXO", null),
        new Def("balance.counter-price", Type.BOOL, "TICKET COMPTOIR",
                "Prix du ticket comptoir : celui du comptoir",
                "Les lignes d'un ticket unique balance sont enregistrées au prix calculé par le comptoir. Désactivé : chaque ligne est revalorisée au prix du référentiel, le poids pesé faisant foi et le comptoir n'étant plus qu'un peseur (LC-06-01-04).",
                "true", null),
        new Def("cash.rounding-step-cents", Type.INT, "ARRONDI ESPECES",
                "Pas d'arrondi du règlement espèces (centimes)",
                "Le montant réglable en espèces est arrondi au multiple le plus proche de ce pas, et l'écart est enregistré sur le moyen de paiement ARRONDI. 0 ou 1 : aucun arrondi (France). 5 : obligation légale belge depuis le 01/12/2019 (LC-07-03-01).",
                "0", null),
        new Def("credit.allowed-in-degraded", Type.BOOL, "CREDIT CLIENT",
                "Crédit client autorisé en mode dégradé",
                "Le règlement en crédit client reste possible quand le référentiel client n'est plus à jour (coupure entre le BackOffice et la caisse). Désactivé : le crédit client est refusé tant que le référentiel n'a pas été rafraîchi, l'encours et le plafond n'étant plus fiables (LC-07-09-05).",
                "false", null),
        new Def("customer.account-pattern", Type.TEXT, "CREDIT CLIENT",
                "Plage des numéros de compte client",
                "Motif de reconnaissance d'un numéro de compte client, sous forme d'expression régulière (par exemple ^CC\\d{8}$). Un numéro hors de la plage est refusé avant toute recherche. Vide : tout numéro est accepté et aucune plage n'est reconnue au scan (BO-03-06-52).",
                "", null),
        new Def("credit.degraded-after-minutes", Type.INT, "CREDIT CLIENT",
                "Ancienneté du référentiel client tolérée (minutes)",
                "Au-delà de ce délai sans tirage réussi du référentiel client, la caisse se considère en mode dégradé pour le crédit client (LC-07-09-05).",
                "60", null),
        new Def("customer.volatile-creation-enabled", Type.BOOL, "CLIENT EN CAISSE",
                "Creation d'un client de passage en caisse",
                "L'hote(sse) peut creer en caisse un client VOLATIL — un client de passage, sans compte ni credit, cree pour porter une facture. Desactive : seuls les clients en compte deja integres sont utilisables (BO-10-04-01).",
                "true", null),
        new Def("customer.account-creation-blocked", Type.BOOL, "CLIENT EN CAISSE",
                "Creation d'un client EN COMPTE bloquee",
                "La creation d'un client en compte est refusee, en caisse comme au back-office : un client en compte entre par l'integration de la gestion commerciale. Desactive : la creation redevient possible (BO-10-04-05, BO-02-04-12).",
                "true", null),
        new Def("customer.show-details", Type.BOOL, "CLIENT EN CAISSE",
                "Affichage des coordonnees du client",
                "Quand un client est identifie, ses coordonnees sont montrees a l'hote(sse). Desactive : seuls son numero et sa raison sociale apparaissent (BO-10-04-02).",
                "true", null),
        new Def("customer.update-enabled", Type.BOOL, "CLIENT EN CAISSE",
                "Mise a jour des coordonnees en caisse",
                "L'hote(sse) peut corriger les coordonnees du client identifie ; la correction est enregistree en base et portee sur le document imprime. Desactive : la fiche est en lecture seule en caisse (BO-10-04-03/04).",
                "false", null),
        new Def("customer.show-discount", Type.BOOL, "CLIENT EN CAISSE",
                "Affichage de la remise du client en compte",
                "La remise accordee au client en compte est annoncee a l'hote(sse) des que le client est scanne ou saisi. Desactive : la remise s'applique sans etre annoncee (BO-10-04-08).",
                "true", null),
        new Def("customer.discount-segment", Type.TEXT, "CLIENT EN CAISSE",
                "Segment des clients en compte remises",
                "Nom du segment auquel un client en compte doit appartenir pour que sa remise s'applique. Vide : la remise de la fiche s'applique a tout client en compte qui en porte une (BO-10-04-07).",
                "", null),
        new Def("customer.credit-tender", Type.TEXT, "CLIENT EN CAISSE",
                "Moyen de paiement du credit client",
                "Cle du moyen de paiement sous lequel le credit client est enregistre sur le ticket et dans les etats. Une cle inconnue retombe sur CREDIT (BO-10-04-09).",
                "CREDIT", null),
        new Def("customer.partial-credit-allowed", Type.BOOL, "CLIENT EN CAISSE",
                "Paiement partiel en credit client",
                "Le credit client peut ne regler qu'une partie du ticket, le reste etant paye autrement. Desactive : le credit client regle la totalite du reste du, ou rien (BO-10-04-10).",
                "true", null),
        new Def("customer.offline-message", Type.TEXT, "CLIENT EN CAISSE",
                "Alerte des fonctions client hors ligne",
                "Message montre a l'hote(sse) quand une fonction client est demandee alors que la caisse est autonome et que son referentiel n'est plus a jour (BO-10-04-11/12).",
                "REFERENTIEL CLIENT NON A JOUR - CREDIT REFUSE", null),
        new Def("customer.over-limit-message", Type.TEXT, "CLIENT EN CAISSE",
                "Alerte de depassement du plafond",
                "Message montre a l'hote(sse) quand le reglement porterait le client au-dela de son plafond de credit. Les jetons {encours} et {plafond} y sont remplaces par les montants (BO-10-04-13).",
                "PLAFOND DEPASSE - ENCOURS {encours} / PLAFOND {plafond} - AUTORISATION REQUISE", null),
        new Def("customer.default-tax-id", Type.TEXT, "CLIENT EN CAISSE",
                "NIF par defaut",
                "Numero fiscal presente en caisse avant validation du ticket, que l'hote(sse) peut remplacer par celui du client. Vide : aucun NIF n'est propose (BO-10-04-15).",
                "999999990", null),
        new Def("customer.default-tax-id-label", Type.TEXT, "CLIENT EN CAISSE",
                "Libelle du NIF par defaut",
                "Libelle imprime a la place du numero quand le NIF presente est celui par defaut (BO-10-04-16).",
                "CONSUMIDOR FINAL", null),
        new Def("customer.foreign-tax-id-countries", Type.TEXT, "CLIENT EN CAISSE",
                "Pays proposes pour un NIF etranger",
                "Codes pays ISO separes par une virgule, proposes en caisse pour la saisie d'un NIF etranger. Le NIF portugais obeit a son propre algorithme et n'est pas dans cette liste (BO-10-04-17).",
                "ES,FR,DE,IT,GB", null),
        new Def("customer.invoice-export-due-date", Type.BOOL, "CLIENT EN CAISSE",
                "Date d'echeance dans les extractions de factures",
                "Les extractions de factures clients en compte portent la date d'echeance du client (BO-10-04-19).",
                "true", null),
        new Def("customer.invoice-export-ecotax", Type.BOOL, "CLIENT EN CAISSE",
                "Eco-taxe dans les extractions de factures",
                "Les extractions de factures portent le montant d'eco-taxe associe a la transaction reglee en credit client (BO-10-04-14).",
                "false", null),
        new Def("scan.ean13-check-digit", Type.BOOL, "SCAN",
                "Contrôle du checkdigit EAN13",
                "Au scan d'un EAN13, la clé de contrôle est vérifiée et un code invalide est refusé. Désactivé : aucun contrôle de clé (BO-10-02-21).",
                "false", null),
        new Def("scan.supervisor-prefix", Type.TEXT, "SCAN",
                "Préfixe du code-barres superviseur",
                "Préfixe imprimé devant le code superviseur sur son code-barres. Tant qu'il est posé, une identification superviseur n'est acceptée QUE d'un code préfixé : le code seul, qui se tape, ne vaut plus identification. Vide : le code superviseur suffit, comme avant (BO-10-02-32).",
                "", null),
        new Def("sale.allow-during-payment", Type.BOOL, "SCAN",
                "Vente d'un article pendant le règlement",
                "Un article scanné alors qu'un règlement est en cours est enregistré sur la vente. Désactivé — le défaut — il est refusé et le caissier en est averti : un panier qui grossit pendant qu'on encaisse est un panier dont le total ne vaut plus (BO-10-02-15).",
                "false", null),
        new Def("dashboard.alerts-enabled", Type.BOOL, "SUPERVISION",
                "Alertes caisses sur le back-office",
                "Les appels superviseur des caisses s'affichent sur le tableau de bord du back-office. Désactivé : aucune alerte n'est remontée à l'écran (BO-10-08-01).",
                "true", null),
        new Def("supervisor.service-reasons", Type.TEXT, "SUPERVISION",
                "Motifs de demande de service",
                "Motifs proposés à l'écran d'appel superviseur, séparés par des points-virgules — liste administrée au lieu des quatre boutons figés (EXP-84).",
                "Annulation;Prix / étiquette;Retour client;Autre", null),
        new Def("fidelity.advantages-enabled", Type.BOOL, "FIDÉLITÉ",
                "Avantages fidélité sur le ticket",
                "La section « CAGNOTTE DU JOUR » et les avantages fidélité sont imprimés sur le ticket. Désactivé : aucune section fidélité n'est imprimée (BO-10-03-15).",
                "true", null),
        new Def("fidelity.projected-label", Type.TEXT, "FIDÉLITÉ",
                "Libellé de la cagnotte prévisionnelle",
                "Libellé imprimé devant le solde de cagnotte projeté après la vente (imfid réponse B). Le montant est prévisionnel : rien n'est crédité avant l'ingestion du ticket (BO-03-03-31).",
                "CAGNOTTE PREVISIONNELLE", null),
        new Def("fidelity.allow-multiple-scan", Type.BOOL, "FIDÉLITÉ",
                "Scan multiple de carte de fidélité",
                "Plusieurs cartes de fidélité peuvent être scannées pendant la transaction, seule la dernière est retenue. Désactivé : une carte déjà scannée bloque les suivantes (BO-10-03-02).",
                "true", null),
        new Def("cash.movement-endorsement-threshold", Type.TEXT, "MOUVEMENTS DE CAISSE",
                "Seuil d'aval manager (€)",
                "Montant au-delà duquel un mouvement de caisse (prélèvement, apport, dépense, acompte, déclaration) exige un aval manager. Un mouvement à ce montant ou en dessous passe sans aval (BO-04-01-44).",
                "100.00", null),
        new Def("cash.movement-reasons", Type.TEXT, "MOUVEMENTS DE CAISSE",
                "Motifs autorisés",
                "Motifs proposés au caissier pour un mouvement de caisse, séparés par des points-virgules. Vide : la saisie du motif reste libre (BO-04-01-44).",
                "Prélèvement coffre;Apport de fond;Achat de timbres;Dépense pharmacie;Erreur de caisse", null),
        new Def("invoice.customer-fields", Type.TEXT, "FACTURE",
                "Champs du client en compte",
                "Champs demandés à la création d'un client en caisse, séparés par des points-virgules, une étoile marquant les obligatoires : companyName, contactName, street, postalCode, city, siret, vatNumber, phone, email. Vide : tous les champs, raison sociale obligatoire (LC-08-04-10).",
                "", null),
        new Def("invoice.document-types", Type.TEXT, "FACTURE",
                "Types de document actifs",
                "Types de document proposés en caisse après la saisie du ticket, séparés par des points-virgules : FACTURE, BON_LIVRAISON. Un seul type actif : l'étape de choix n'est pas affichée. Vide : facture seule (LC-08-04-04/05).",
                "FACTURE", null),
        new Def("invoice.document-output", Type.TEXT, "FACTURE",
                "Imprimante par type de document",
                "Imprimante de chaque type de document, séparés par des points-virgules, sous la forme TYPE:IMPRIMANTE — imprimantes : TICKET (rouleau de caisse), FACTURETTE (station à insertion), A4 (imprimante réseau). Exemple : FACTURE:A4;BON_LIVRAISON:TICKET. Un type non nommé sort sur le rouleau (LC-08-04-11).",
                "", null),
        new Def("invoice.slip-lines", Type.INT, "FACTURE",
                "Lignes par facturette",
                "Nombre de lignes imprimées sur une facturette avant de demander la feuille suivante. En dessous de 1, la facturette est imprimée d'un seul tenant (LC-08-04-12/13).",
                "30", null),
        new Def("invoice.auto-print", Type.TEXT, "FACTURE",
                "Document imprimé automatiquement par moyen de règlement",
                "Document émis automatiquement en fin de transaction selon le règlement, séparés par des points-virgules, sous la forme REGLEMENT:TYPE — exemple CREDIT:FACTURE. Le document n'est émis que si le règlement désigne un client en compte. Vide : aucune émission automatique (LC-08-04-16).",
                "", null),
        new Def("drawer.withdrawal-methods", Type.TEXT, "PRÉLÈVEMENT ET TRANSFERT",
                "Moyens de règlement prélevables manuellement",
                "Moyens de règlement proposés au prélèvement manuel, séparés par des points-virgules, sous la forme CLÉ:LIBELLÉ — exemple CASH:Espèces;CHEQUE:Chèques. Un moyen prélevé automatiquement n'y figure pas et n'est pas proposé en caisse (LC-12-03-02/03).",
                "CASH:Espèces;CHEQUE:Chèques;TR:Titres-restaurant;VOUCHER:Bons", null),
        new Def("drawer.withdrawal-print", Type.BOOL, "PRÉLÈVEMENT ET TRANSFERT",
                "Impression du ticket de prélèvement",
                "Un ticket de prélèvement est imprimé après validation, détaillant les dénominations pour les espèces et les transactions pour les autres moyens (LC-12-03-07).",
                "true", null),
        new Def("drawer.transfer-methods", Type.TEXT, "PRÉLÈVEMENT ET TRANSFERT",
                "Moyens de règlement transférables",
                "Moyens de règlement proposés en source et en destination d'un transfert de règlement, même syntaxe que le prélèvement. Vide : la liste du prélèvement s'applique (LC-12-10-02).",
                "", null),
        new Def("drawer.transfer-print", Type.BOOL, "PRÉLÈVEMENT ET TRANSFERT",
                "Impression du ticket de transfert",
                "Un ticket « Transfert règlement » est imprimé après validation, indiquant les moyens source et destination et le montant transféré (LC-12-10-05).",
                "true", null),
        new Def("abandon.reasons", Type.TEXT, "ABANDON DE TICKET",
                "Motifs d'abandon",
                "Motifs proposés au caissier pour abandonner un ticket, séparés par des points-virgules. Le motif choisi est journalisé et imprimé sur le ticket d'abandon. Vide : la saisie du motif n'est pas demandée (LC-04-04-10).",
                "Erreur de saisie;Client parti sans payer;Article indisponible;Problème de règlement;Ticket de test", null),
        new Def("abandon.partial-payment", Type.TEXT, "ABANDON DE TICKET",
                "Abandon avec règlement partiel",
                "Ce que fait la caisse quand un règlement partiel est déjà enregistré : CONFIRM (l'écran annonce les règlements qui seront annulés et l'opérateur valide) ou BLOCK (l'abandon est refusé, l'opérateur doit annuler le règlement d'abord). Vide ou inconnu : CONFIRM (LC-04-04-06/07/08/09).",
                "CONFIRM", null),
        new Def("abandon.print", Type.TEXT, "ABANDON DE TICKET",
                "Impression du ticket d'abandon",
                "Quand le ticket d'abandon est imprimé : NEVER (jamais), ALWAYS (systématiquement) ou ON_DEMAND (l'opérateur décide sur l'écran d'abandon). Vide ou inconnu : NEVER (LC-04-04-12).",
                "NEVER", null),
        new Def("abandon.print-detail", Type.BOOL, "ABANDON DE TICKET",
                "Détail des articles sur le ticket d'abandon",
                "Le ticket d'abandon liste les articles du ticket abandonné. Désactivé : seuls l'entête, le motif et le total abandonné sont imprimés (LC-04-04-12).",
                "true", null),
        new Def("tender.restricted", Type.TEXT, "RÈGLEMENTS RESTREINTS",
                "Règlements restreints affichés en caisse",
                "Moyens de paiement restreints dont l'assiette éligible est affichée pendant la vente, séparés par des points-virgules, sous la forme CODE:ATTRIBUT:LIBELLÉ — attributs disponibles : MEAL_VOUCHER_ELIGIBLE, ECO_VOUCHER_ELIGIBLE, SOCIAL_CARD_ELIGIBLE. Vide : titre-restaurant, éco-chèque et carte achat sociale (LC-09-01-11 à -18).",
                "", null),
        new Def("customer.local-code-range", Type.TEXT, "CLIENT EN CAISSE",
                "Plage des codes clients attribués en caisse",
                "Plage de numéros que la caisse attribue aux clients qu'elle crée, sous la forme PRÉFIXE:PREMIER-DERNIER (ex. CLI:500000-599999). Le préfixe et les bornes sont ceux que le central réserve au point de vente, pour qu'un client créé en caisse ne collisionne jamais avec un client intégré. Vide : numérotation par caisse CAISSE-CLInnnnnn, celle d'avant ce paramètre (BO-03-06-53).",
                "", null),
        new Def("keyboard.enter-as-total", Type.BOOL, "TOUCHES CAISSE",
                "Touche entrée valant TOTAL",
                "Sur l'écran de vente, la touche entrée frappée SANS RIEN DE TAPÉ ouvre le règlement. Désactivé — le défaut — elle ne fait rien, ce qu'elle a toujours fait : un code tapé est validé par elle dans les deux cas (BO-10-02-13).",
                "false", null),
        new Def("touch.external-apps", Type.TEXT, "TOUCHES CAISSE",
                "Applications tierces appelées depuis la caisse",
                "Applications tierces atteignables depuis le poste, séparées par des points-virgules, sous la forme LIBELLÉ>URL (ex. Carte fidélité>https://fid.magasin/caisse). Une entrée mal formée est écartée. Vide : aucune application proposée (BO-03-09-27).",
                "", null),
        new Def("tender.local-range", Type.TEXT, "RÈGLEMENTS RESTREINTS",
                "Plage d'identifiants modifiable par le point de vente",
                "Identifiants fonctionnels de modes de règlement que le magasin peut modifier, sous la forme de bornes séparées par des virgules (ex. 500-599,900). Tout identifiant hors de cette plage est central et figé : le magasin ne garde que l'activation. Vide : le magasin modifie tous les modes de règlement (BO-03-02-06, BO-03-02-07).",
                "", null),
        new Def("cheque.endorsement-lines", Type.TEXT, "RÈGLEMENTS RESTREINTS",
                "Endos imprimé au dos du chèque",
                "Ce qui est imprimé au dos du chèque pendant que le lecteur le tient, une ligne par point-virgule. Jetons disponibles : {date}, {heure}, {montant}, {caisse}, {pdv}. Vide : la date et l'heure puis le montant, comme avant ce paramètre (BO-03-02-43).",
                "", null),
        new Def("gs1.expiry-alert", Type.TEXT, "CODES GS1",
                "Alerte date de péremption article",
                "Ce que fait la caisse quand un code GS1 porte une date de péremption (AI 17) proche, atteinte ou dépassée : NONE (rien), INFO (message au caissier, l'article est enregistré) ou BLOCK (article refusé). Vide ou inconnu : NONE (LC-11-03-13).",
                "NONE", null),
        new Def("gs1.expiry-warn-days", Type.INT, "CODES GS1",
                "Nombre de jours « date proche »",
                "Nombre de jours avant la date de péremption à partir duquel elle est considérée comme proche. Zéro : seule une date atteinte ou dépassée déclenche l'alerte (LC-11-03-13).",
                "0", null),
        new Def("gs1.coupon-expiry-alert", Type.TEXT, "CODES GS1",
                "Alerte date d'expiration coupon",
                "Ce que fait la caisse quand un coupon GS1 porte une date d'expiration (AI 17) atteinte ou dépassée : NONE, INFO ou BLOCK. Vide ou inconnu : NONE (LC-11-03-13).",
                "BLOCK", null),
        new Def("gs1.gdti-coupon-types", Type.TEXT, "CODES GS1",
                "Chèques cadeaux GS1 par émetteur",
                "Type de règlement associé à un chèque cadeau GS1 (AI 253) selon son code émetteur, séparés par des points-virgules, sous la forme PREFIXE:CODE_TYPE — exemple 9526000:CADEAU. Le préfixe le plus long l'emporte. Vide : aucun chèque cadeau GS1 accepté (LC-11-03-05).",
                "", null),
        new Def("gs1.gcn-coupon-type", Type.TEXT, "CODES GS1",
                "Type de règlement des coupons GS1",
                "Code du type de règlement associé à un coupon GS1 (AI 255). Vide : aucun coupon GS1 accepté en règlement (LC-11-03-06).",
                "", null),
        new Def("sogec.bon-prefixes", Type.TEXT, "CODES GS1",
                "Préfixes des bons SOGEC",
                "Préfixes reconnaissant un bon SOGEC, séparés par des points-virgules ; un code portant l'un d'eux part dans le circuit de compensation SOGEC au lieu du coupon GS1 générique. Vide : aucun bon SOGEC reconnu (EXP-58).",
                "", null),
        new Def("touch.groups-per-page", Type.INT, "TOUCHES CAISSE",
                "Nombre de touches groupe par page",
                "Nombre de touches de groupe d'articles affichees par page sur l'ecran de saisie directe. En dessous de 1 la valeur par defaut s'applique (BO-03-01-06).",
                "12", null),
        new Def("touch.display-order", Type.TEXT, "TOUCHES CAISSE",
                "Ordre des touches groupe",
                "Ordre d'affichage des touches groupe sur l'ecran de saisie directe : ALPHA (alphabetique, defaut), CUSTOM (ordre personnalise de la fiche groupe) ou VOLUME (volume de vente decroissant). Une valeur inconnue retombe sur ALPHA (BO-03-01-10/11/13).",
                "ALPHA", null),
        new Def("auth.idle-action", Type.TEXT, "SESSION CAISSE",
                "Sécurité sans activité",
                "Ce que fait la caisse au bout du délai d'inactivité : LOCK (verrouillage — l'opérateur reste en poste et reprend sa vente avec son seul code, défaut) ou CLOSE (fermeture de la session de travail — l'opérateur est déconnecté et une prise de poste complète est nécessaire). Une valeur inconnue retombe sur LOCK (BO-10-02-28).",
                "LOCK", null),
        new Def("auth.badge-scan-enabled", Type.BOOL, "SESSION CAISSE",
                "Prise de poste au scan du badge",
                "Le scan d'un badge deverrouille la caisse ou vise un avenant manager. Desactive : le badge scanne est ignore et l'operateur saisit son identifiant a la main (BO-10-02-29/30).",
                "true", null),
        new Def("auth.password-required-on-open", Type.BOOL, "SESSION CAISSE",
                "Mot de passe a la prise de poste",
                "Le badge scanne ouvre la caisse SEUL quand ce parametre est desactive. Actif : le badge remplit l'identifiant et l'operateur saisit son code (LC-01-01-03/09, BO-10-02-22).",
                "true", null),
        new Def("auth.password-required-on-close", Type.BOOL, "SESSION CAISSE",
                "Mot de passe a la fermeture",
                "La deconnexion demande le code de l'operateur. Desactive : la touche ferme la caisse sans rien demander (LC-01-02-02/03, BO-10-02-24).",
                "false", null),
        new Def("session.close-endorsement-on-pending", Type.BOOL, "SESSION CAISSE",
                "Avenant si tickets en attente",
                "Fermer la caisse en laissant un ticket en attente non repris exige une autorisation superviseur. Desactive : la fermeture passe avec un simple avertissement (LC-01-02-10, BO-10-02-08).",
                "true", null),
        new Def("session.print-open-receipt", Type.BOOL, "TICKETS",
                "Ticket d'ouverture de caisse",
                "Impression d'un ticket a la prise de poste : caisse, operateur, horodatage (LC-01-01-08, BO-10-07-09).",
                "false", null),
        new Def("session.print-close-receipt", Type.BOOL, "TICKETS",
                "Ticket de fermeture de caisse",
                "Impression d'un ticket a la fermeture : caisse, operateur, horodatage, et la mention du forcage le cas echeant (LC-01-02-11, BO-10-07-09).",
                "false", null),
        new Def("period.auto-close-enabled", Type.BOOL, "FIN DE PÉRIODE",
                "Fermeture automatique des caisses",
                "Les caisses restees ouvertes sont fermees d'office a l'approche de la fin de periode, l'ordre etant releve par chaque caisse a son tour de boucle. Desactive : une caisse oubliee reste ouverte jusqu'au lendemain (LC-01-02-08, BO-09-01-06/09).",
                "false", null),
        new Def("period.end-time", Type.TEXT, "FIN DE PÉRIODE",
                "Heure de fin de periode",
                "Heure de la fin de periode, au format HH:mm. Une valeur illisible retombe sur la valeur par defaut (BO-09-01-06).",
                "23:30", null),
        new Def("period.close-lead-minutes", Type.INT, "FIN DE PÉRIODE",
                "Delai de fermeture avant la fin de periode (minutes)",
                "Nombre de minutes avant la fin de periode a partir duquel les caisses encore ouvertes sont fermees. Une caisse en pleine vente n'est pas fermee : l'ordre reste en attente et la caisse le reprend des qu'elle est au repos (BO-09-03-07).",
                "5", null),
        new Def("period.pdv-actions", Type.TEXT, "FIN DE PÉRIODE",
                "Actions de la fin de période du point de vente",
                "Ce que la fin de période du POINT DE VENTE enchaîne, noms séparés par des points-virgules et dans l'ordre : CLOSE_REGISTERS (fermer les caisses restées ouvertes), SAFE_COUNT (comptage du coffre), Z_REPORT (état Z consolidé), BANK_DEPOSIT (remise en banque). Un nom inconnu est ignoré. Vide : la fin de période ne fait que ce que les clés ci-dessus décrivent, caisse par caisse (BO-03-12-02).",
                "", null),
        new Def("cash.default-opening-float", Type.TEXT, "SESSION CAISSE",
                "Fond de caisse par defaut",
                "Montant pre-rempli du fond de caisse a l'ouverture de session, que le caissier peut corriger. Administre par magasin et herite par echelon (BO-03-02-41).",
                "150.00", null),
        new Def("drawer.open-on-session-close", Type.BOOL, "TIROIR",
                "Ouverture du tiroir a la cloture de session",
                "Le tiroir s'ouvre au lancement de la cloture (comptage du Z). Desactive : le tiroir reste ferme, le comptage se fait tiroir clos (BO-10-02-26).",
                "true", null),
        new Def("ticket.vat-breakdown-enabled", Type.BOOL, "TICKETS",
                "Ventilation TVA sur le ticket",
                "Le detail de la TVA par taux (HT / TVA) est imprime sur le ticket de caisse et le ticket de retour. Desactive : aucune ventilation TVA n'est imprimee (BO-10-06-04).",
                "true", null),
        new Def("fidelity.print-card-number", Type.BOOL, "FIDÉLITÉ",
                "Imprimer le numéro de carte fidélité",
                "La mention carte fidélité et son numéro sortent en zone d'avantage carte du ticket. Désactivé : le ticket ne nomme pas la carte (BO-03-03-32).",
                "false", null),
        new Def("fidelity.print-balances", Type.BOOL, "FIDÉLITÉ",
                "Imprimer les soldes de la carte fidélité",
                "La zone soldes carte sort en fin de section fidélité : solde disponible, total des avantages du jour, montant utilisé et nouveau solde après transaction. Désactivé : aucune zone soldes (BO-03-03-31, BO-03-03-34).",
                "false", null),
        new Def("fidelity.advantage-grouping", Type.TEXT, "FIDÉLITÉ",
                "Regroupement des avantages fidélité",
                "Comment les lignes d'avantage sont regroupées sous la cagnotte du jour : TYPE (par type d'avantage, défaut), CATEGORY (par catégorie) ou NONE (liste à plat). Une valeur inconnue retombe sur TYPE (BO-03-03-25, BO-03-03-33).",
                "TYPE", null),
        new Def("fidelity.non-holder-message", Type.TEXT, "FIDÉLITÉ",
                "Message fidélité pour les non porteurs",
                "Texte imprimé sur le ticket d'un client sans carte de fidélité, quand le service fidélité a répondu. Vide : rien n'est imprimé (BO-03-03-28).",
                "", null),
        new Def("fidelity.external-enabled", Type.BOOL, "FIDÉLITÉ",
                "Service fidelite externe actif",
                "L'interrogation du service fidelite imfid (cagnotte, avantages, paiement fidelite) est active. Desactive : la caisse se comporte comme si aucun service fidelite n'etait configure, meme si son URL est renseignee (BO-10-03-07).",
                "true", null),
        new Def("cash.movement-tenders", Type.TEXT, "MOUVEMENTS DE CAISSE",
                "Moyens de paiement d'un mouvement",
                "Moyens de paiement proposes pour un mouvement de caisse (prelevement/apport d'un cheque, d'un titre-restaurant...), separes par des points-virgules. Vide : le mouvement porte toujours sur les especes (BO-03-02-20).",
                "", null),
        new Def("cash.withdrawal-destinations", Type.TEXT, "MOUVEMENTS DE CAISSE",
                "Destinations de prélèvement",
                "Destinations proposées au caissier pour un prélèvement, en plus du coffre qui est toujours offert, séparées par des points-virgules. La destination est obligatoire : un prélèvement qui ne dit pas où va l'argent n'est pas opposable (LC-12-03).",
                "Caisse centrale;Coffre-fort magasin;Remise en banque", null),
        new Def("discount.line-max-amount", Type.TEXT, "GESTES DE PRIX",
                "Plafond de remise ligne (€)",
                "Montant maximum d'une remise en euros sur une ligne. Une remise à ce montant passe, au-delà elle est refusée. 0 = pas de plafond en euros (BO-03-07-04).",
                "0.00", null),
        new Def("discount.global-max-amount", Type.TEXT, "GESTES DE PRIX",
                "Plafond de remise ticket (€)",
                "Montant maximum d'une remise ticket en euros. Une remise à ce montant passe, au-delà elle est refusée. 0 = pas de plafond en euros (BO-03-07-04).",
                "0.00", null),
        new Def("fidelity.show-holder-name", Type.BOOL, "FIDÉLITÉ",
                "Afficher le nom du porteur",
                "Le nom et le prénom trouvés par une recherche de carte sont affichés en caisse à côté du numéro. Désactivé : la caisse n'affiche que le numéro de carte, comme pour une carte scannée (BO-10-03-04).",
                "true", null),
        new Def("fidelity.card-pattern", Type.TEXT, "FIDÉLITÉ",
                "Plage des cartes de fidélité",
                "Motif de reconnaissance d'une carte de fidélité au scan, sous forme d'expression régulière (par exemple ^299\\d{10}$). Vide : la propriété de déploiement scan.pattern.fidelity s'applique (BO-03-06-54).",
                "", "scan.pattern.fidelity"),
        new Def("fidelity.jv-promotion-enabled", Type.BOOL, "FIDÉLITÉ",
                "Promotions personnalisées à la carte",
                "La carte de fidélité est transmise au moteur de promotion, qui lit les offres personnelles du porteur et peut les déclencher. Désactivé : le panier est valorisé anonymement et aucune promotion personnalisée n'est déclenchée (BO-10-03-22).",
                "true", null),
        new Def("fidelity.template-online", Type.TEXT, "FIDÉLITÉ",
                "Gabarit de la zone fidélité — porteur, en ligne",
                "Code du gabarit imprimé à la place de la zone fidélité intégrée quand une carte a été présentée et que le programme a répondu. Vide : la caisse imprime sa zone intégrée (BO-10-03-05, BO-10-03-12).",
                "", null),
        new Def("fidelity.template-offline", Type.TEXT, "FIDÉLITÉ",
                "Gabarit de la zone fidélité — porteur, hors ligne",
                "Code du gabarit imprimé quand une carte a été présentée et que le programme n'a pas répondu. Vide : la caisse imprime sa zone intégrée, carte et message hors ligne (BO-10-03-05, BO-10-03-11).",
                "", null),
        new Def("fidelity.template-non-holder-online", Type.TEXT, "FIDÉLITÉ",
                "Gabarit de la zone fidélité — non porteur, en ligne",
                "Code du gabarit imprimé quand aucune carte n'a été présentée et que le programme a répondu, c'est-à-dire pour la projection anonyme. Vide : la caisse imprime sa zone intégrée (BO-10-03-05).",
                "", null),
        new Def("fidelity.template-non-holder-offline", Type.TEXT, "FIDÉLITÉ",
                "Gabarit de la zone fidélité — non porteur, hors ligne",
                "Code du gabarit imprimé quand aucune carte n'a été présentée et que le programme n'a pas répondu. Vide : la caisse imprime sa zone intégrée, limitée au message du magasin (BO-10-03-05, BO-10-03-13).",
                "", null),
        new Def("fidelity.template-grouped", Type.TEXT, "FIDÉLITÉ",
                "Gabarit de la zone fidélité regroupée par catégorie",
                "Code du gabarit imprimé à la place du gabarit porteur en ligne quand le regroupement des avantages vaut CATEGORY. Vide : le gabarit porteur en ligne s'applique, regroupement ou non (BO-10-03-21).",
                "", null),
        new Def("fidelity.url", Type.TEXT, "FIDÉLITÉ",
                "URL du service fidélité",
                "Adresse de base du service fidélité appelé pour valoriser le panier avec les avantages fid, projeter la cagnotte et envoyer les transactions. Vide : la propriété de déploiement pos.fid.url s'applique (BO-11-04-04).",
                "", "pos.fid.url"),
        new Def("fidelity.user", Type.TEXT, "FIDÉLITÉ",
                "Compte machine du service fidélité",
                "Identifiant du compte machine de la caisse auprès du service fidélité. Vide : la propriété de déploiement pos.fid.user s'applique. Le mot de passe reste un secret de déploiement et n'est pas administrable (BO-11-04-04).",
                "", "pos.fid.user"),
        new Def("fidelity.offline-message", Type.TEXT, "FIDÉLITÉ",
                "Message ticket quand la fidélité est indisponible",
                "Message imprimé sur le ticket d'un porteur quand le service fidélité n'a pas répondu pendant la vente. Le jeton {carte} y est remplacé par le numéro de carte. Vide : le ticket ne dit rien (BO-03-03-29).",
                "CARTE {carte} - FIDELITE INDISPONIBLE, VOS AVANTAGES SERONT CREDITES ULTERIEUREMENT", null),
        new Def("fidelity.offline-message-no-card", Type.TEXT, "FIDÉLITÉ",
                "Message ticket sans carte quand la fidélité est indisponible",
                "Message imprimé sur le ticket d'un client SANS carte quand le service fidélité n'a pas répondu pendant la vente. Vide : le ticket ne dit rien (BO-03-03-30).",
                "FIDELITE INDISPONIBLE - PRESENTEZ VOTRE CARTE LORS DE VOTRE PROCHAIN PASSAGE", null),
        new Def("fidelity.anonymous-projection", Type.BOOL, "FIDÉLITÉ",
                "Projection cagnotte sans carte",
                "Sur un ticket sans carte, la caisse demande à imfid ce que le client aurait gagné avec la carte et l'imprime (imfid réponse C). Désactivé : rien n'est demandé ni imprimé pour un ticket sans carte (BO-03-03-28).",
                "false", null),
        new Def("training.theme", Type.TEXT, "MODE ÉCOLE",
                "Thème de l'écran caisse en formation",
                "Nom du thème appliqué à l'écran caisse en mode école, pour le distinguer de l'écran de vente standard (sombre, clair). Vide : la formation garde le thème habituel et seul le bandeau la signale (BO-10-07-08).",
                "", null),
        new Def("retention.sales-days", Type.INT, "RÉTENTION DES DONNÉES",
                "Ventes article : rétention (jours)",
                "Nombre de jours de conservation des ventes article (tickets clôturés) avant SUPPRESSION par le balayage de fin de période. Une vente rattachée à une facture ou à un remboursement n'est jamais supprimée. 0 = conservation illimitée. Suppression retenue ; l'archivage est une décision client, hors implémentation (BO-10-08-03).",
                "0", null),
        new Def("retention.cashier-reports-count", Type.INT, "RÉTENTION DES DONNÉES",
                "États caissières : nombre de rapports conservés",
                "NOMBRE de rapports caissières (états X du journal technique) conservés — les plus anciens au-delà de ce nombre sont supprimés. Un nombre de rapports, PAS un nombre de jours. 0 = conservation illimitée (BO-10-08-07).",
                "0", null),
        new Def("retention.daily-tables-days", Type.INT, "RÉTENTION DES DONNÉES",
                "Tables quotidiennes : rétention (jours)",
                "Nombre de jours de conservation des événements techniques quotidiens non couverts par une ligne dédiée (hors journal d'accès et alertes). 0 = conservation illimitée (BO-10-08-08).",
                "0", null),
        new Def("retention.access-journal-days", Type.INT, "RÉTENTION DES DONNÉES",
                "Journal d'accès utilisateurs : rétention (jours)",
                "Nombre de jours de conservation du journal d'accès (verrouillages d'authentification, mots de passe, verrouillage / déverrouillage de caisse). 0 = conservation illimitée (BO-10-08-09).",
                "0", null),
        new Def("retention.alerts-days", Type.INT, "RÉTENTION DES DONNÉES",
                "Alertes : rétention (jours)",
                "Nombre de jours de conservation des alertes (appels superviseur) tracées au journal technique. 0 = conservation illimitée (BO-10-08-11).",
                "0", null),
        new Def("retention.parked-tickets-days", Type.INT, "RÉTENTION DES DONNÉES",
                "Tickets en attente : rétention (jours)",
                "Nombre de jours de conservation des tickets en attente (mis en parking) avant suppression. Un ticket en attente rattaché à une session encore ouverte n'est pas supprimé. 0 = conservation illimitée (BO-10-08-12).",
                "0", null),
        new Def("retention.invoices-days", Type.INT, "RÉTENTION DES DONNÉES",
                "Factures / bons de livraison : rétention (jours)",
                "Nombre de jours de conservation des factures et bons de livraison. Une facture liée à un client encore actif (non bloqué) n'est pas supprimée. 0 = conservation illimitée (BO-10-08-16).",
                "0", null),
        new Def("retention.expired-promotions-days", Type.INT, "RÉTENTION DES DONNÉES",
                "Promotions échues : rétention (jours)",
                "Nombre de jours après la fin de validité au-delà duquel une promotion échue est supprimée. 0 = conservation illimitée (BO-10-08-17).",
                "0", null),
        new Def("retention.expired-promotions-cap", Type.INT, "RÉTENTION DES DONNÉES",
                "Promotions échues : plafond supprimé par passage",
                "Nombre maximum de promotions échues supprimées à CHAQUE passage du balayage, pour que la purge ne bloque pas la fin de période. Ce n'est pas une rétention, c'est un plafond par exécution. 0 = pas de plafond (BO-10-08-18).",
                "0", null),
        new Def("retention.deleted-articles-days", Type.INT, "RÉTENTION DES DONNÉES",
                "Articles « supprimés » : rétention (jours)",
                "Nombre de jours de conservation des articles « supprimés » (désactivés) avant suppression définitive. Un article encore référencé par un prix ou une ligne de ticket n'est pas supprimé. 0 = conservation illimitée (BO-10-08-24).",
                "0", null),
        new Def("retention.customers-days", Type.INT, "RÉTENTION DES DONNÉES",
                "Clients : purge après dernière activité (jours)",
                "Nombre de jours après la dernière activité (dernière mise à jour de la fiche) au-delà duquel un client est purgé. Un client avec un solde de crédit non nul ou encore lié à une facture n'est pas purgé. 0 = conservation illimitée (BO-10-08-26).",
                "0", null),
        new Def("session.welcome-message", Type.TEXT, "SESSION CAISSE",
                "Message d'accueil caisse fermée",
                "Texte d'accueil affiché sur l'écran de la caisse fermée, invitant l'opérateur à se connecter (EXP-01, LC-01-01).",
                "Bonjour : Connectez-vous", null),
        new Def("sale.waiting-label", Type.TEXT, "AFFICHAGE",
                "Libellé d'attente d'articles",
                "Texte affiché sur la visu caisse quand le ticket est vide, entre deux clients (EXP-03).",
                "CLIENT SUIVANT", null),
        new Def("sale.line-warning-threshold", Type.TEXT, "AFFICHAGE",
                "Seuil d'avertissement prix article (\u20ac)",
                "Prix unitaire au-del\u00e0 duquel la saisie d'un article affiche un avertissement au caissier. 0 = aucun avertissement.",
                "10.00", null),
        new Def("cash.drawer-cap", Type.TEXT, "TIROIR",
                "Plafond de contenu du tiroir (€)",
                "Montant d'espèces au-delà duquel la caisse affiche une alerte PRÉLÈVEMENT sans bloquer la vente, calé sur le montant garanti par l'assurance. 0 = aucun plafond (EXP-17).",
                "0.00", null),
        new Def("gesture.endorsement-cancel-line", Type.BOOL, "GESTES NÉGATIFS",
                "Aval superviseur : annulation de ligne",
                "L'annulation d'une ligne déjà enregistrée exige un aval superviseur. Désactivé : le caissier annule seul (EXP-32).",
                "true", null),
        new Def("gesture.endorsement-cancel-last", Type.BOOL, "GESTES NÉGATIFS",
                "Aval superviseur : annulation du dernier article",
                "L'annulation rapide du dernier article saisi exige un aval superviseur. Désactivé : le caissier annule seul le dernier article (EXP-32).",
                "false", null),
        new Def("gesture.endorsement-abandon", Type.BOOL, "GESTES NÉGATIFS",
                "Aval superviseur : abandon de ticket",
                "L'abandon du ticket en cours exige un aval superviseur. Désactivé : le caissier abandonne seul (EXP-32).",
                "true", null),
        new Def("gesture.endorsement-return", Type.BOOL, "GESTES NÉGATIFS",
                "Aval superviseur : retour",
                "Un retour d'article exige un aval superviseur. Désactivé : le caissier enregistre seul le retour (EXP-32).",
                "true", null),
        new Def("gesture.endorsement-refund", Type.BOOL, "GESTES NÉGATIFS",
                "Aval superviseur : remboursement",
                "Le remboursement d'un retour exige un aval superviseur. Désactivé : le caissier rembourse seul (EXP-32).",
                "true", null),
        new Def("gesture.endorsement-discount", Type.BOOL, "GESTES NÉGATIFS",
                "Aval superviseur : remise / discount",
                "Une remise ou un discount (ligne ou ticket) exige un aval superviseur. Désactivé : le caissier applique seul (EXP-32).",
                "true", null),
        new Def("gesture.endorsement-price-force", Type.BOOL, "GESTES NÉGATIFS",
                "Aval superviseur : forçage de prix",
                "Le forçage du prix d'une ligne exige un aval superviseur. Désactivé : le caissier force seul (EXP-32).",
                "true", null),
        new Def("cash.variance-threshold", Type.TEXT, "CLÔTURE",
                "Seuil d'écart de caisse (€)",
                "Écart de caisse (valeur absolue) au-delà duquel la clôture alerte et exige un recomptage ou une validation superviseur nominative. Usuellement 1 € (EXP-82).",
                "1.00", null),
        new Def("cash.float-mode", Type.TEXT, "CLÔTURE",
                "Mode de fond de caisse",
                "FIXED : fond fixe, la caissière compte son tiroir chaque soir et la RAZ caissière est quotidienne (défaut). FLOATING_WEEK_MONTH : fond flottant, le comptage réel et la RAZ caissière n'ont lieu qu'en fin de semaine et le dernier jour du mois. FLOATING_MONTH : fond flottant avec RAZ caissière mensuelle seulement (cas rare). Les soirs sans comptage prélèvent le surplus au-dessus du fond initial et ne calculent aucun écart. FLOATING est accepté comme synonyme de FLOATING_WEEK_MONTH (EXP-95).",
                "FIXED", null),
        new Def("cash.variance-amount-visible", Type.BOOL, "CLÔTURE",
                "Montant de l'écart visible du caissier",
                "Activé : l'écran d'écart affiche le montant constaté. Désactivé (défaut) : il annonce seulement que l'écart dépasse le seuil, sans le chiffre — un caissier qui lit l'écart peut ajuster son comptage jusqu'à le faire disparaître. Le montant reste dans le Z, dans le journal et pour la responsable.",
                "false", null),
        // ÉTATS FINANCIERS (X/Z): the store node's X readings and Z documents.
        new Def("collect.point-label", Type.TEXT, "ARTICLES A ENLEVER",
                "Nom du point de retrait",
                "Le lieu ou le client va retirer les articles marques A ENLEVER, imprime sur le bon d'enlevement : le client quitte la caisse et s'y rend en vehicule, donc le bon doit le nommer. Un seul point de retrait par magasin (le BATI en bricolage, le DRIVE en alimentaire). Vide : le bon n'en nomme aucun (LC-02-08-05).",
                "", null),
        new Def("collect.voucher-copies", Type.INT, "ARTICLES A ENLEVER",
                "Nombre d'exemplaires du bon d'enlevement",
                "Le bon n'est pas un document fiscal : c'est une preuve de sortie de marchandise. Deux exemplaires permettent au client de LAISSER le sien au point de retrait, que le responsable releve le soir, et d'en GARDER un portant le reste a enlever. 1 : un seul bon, celui que le client garde (LC-02-08-05).",
                "2", null),
        new Def("movement.financial-sources", Type.TEXT, "MOUVEMENT FINANCIER",
                "Moyens de paiement source",
                "Moyens de paiement proposés en SOURCE d'un mouvement financier — un montant perçu dans ce moyen est rendu dans un autre hors d'une vente, séparés par des points-virgules, sous la forme CLÉ:LIBELLÉ — exemple CASH:Espèces;CARD:Carte bancaire. Vide : le mouvement financier n'est pas proposé (LC-12-06-01/02).",
                "CASH:Espèces;CARD:Carte bancaire", null),
        new Def("movement.financial-destinations", Type.TEXT, "MOUVEMENT FINANCIER",
                "Moyens de paiement destination",
                "Moyens de paiement proposés en DESTINATION d'un mouvement financier, même syntaxe que la source — exemple CREDIT:Crédit client, sert notamment à régler la dette d'un client en compte. Vide : le mouvement financier n'est pas proposé (LC-12-06-01/02).",
                "CREDIT:Crédit client;CASH:Espèces", null),
        new Def("movement.financial-print", Type.BOOL, "MOUVEMENT FINANCIER",
                "Impression du ticket de mouvement financier",
                "Un ticket « Mouvement financier » est imprimé après validation, indiquant le moyen perçu, le moyen rendu et le montant. Désactivé : aucun ticket n'est imprimé (LC-12-06-03).",
                "true", null),
        new Def("change-order.print", Type.BOOL, "COMMANDE DE MONNAIE",
                "Impression du bordereau de commande de monnaie",
                "Le bordereau de commande de monnaie est imprimé : numéro de caisse, opérateur, horodatage et un tableau des dénominations avec les colonnes montant et nombre de rouleaux, remplies ou vides selon la saisie. Désactivé : aucun bordereau rempli n'est imprimé (LC-12-05-08).",
                "true", null),
        new Def("change-order.roll-values", Type.TEXT, "COMMANDE DE MONNAIE",
                "Valeur des rouleaux par dénomination",
                "Correspondance entre la valeur faciale d'une pièce ou d'un billet et la valeur en euros d'un rouleau complet, séparées par des points-virgules, sous la forme FACIALE:VALEUR_ROULEAU — exemple 0.20:8.00;0.01:0.50. Sert au calcul croisé montant <-> nombre de rouleaux (LC-12-05-06/07). Vide : aucun calcul croisé n'est proposé.",
                "2:100.00;1:50.00;0.50:20.00;0.20:8.00;0.10:4.00;0.05:2.00;0.02:1.00;0.01:0.50", null),
        new Def("return.reasons", Type.TEXT, "RETOUR CLIENT",
                "Motifs de retour",
                "Motifs proposés au caissier lors d'un retour, séparés par des points-virgules. Le motif choisi s'applique à tout le retour, est journalisé et imprimé sur le ticket de retour (exploitable dans les rapports BackOffice). Vide : la saisie du motif n'est pas demandée (LC-05-05-01/02).",
                "Article défectueux;Erreur de caisse;Produit non conforme;Changement d'avis;Article périmé", null),
        new Def("return.print-reason", Type.BOOL, "RETOUR CLIENT",
                "Impression du motif de retour",
                "Le motif choisi est imprimé sur le ticket de retour. Désactivé : le motif est journalisé mais n'apparaît pas sur le ticket (LC-05-05-03).",
                "true", null),
        new Def("return.in-sale-allowed", Type.BOOL, "RETOUR CLIENT",
                "Retour d'article dans un ticket de vente",
                "Des articles peuvent être repris dans un ticket qui porte déjà des ventes : le total du ticket est alors positif ou négatif selon les montants. Désactivé : un retour s'enregistre sur son propre ticket, jamais mêlé à une vente (LC-05-01-01).",
                "true", null),
        new Def("return.dedicated-register", Type.BOOL, "RETOUR CLIENT",
                "Caisse dédiée aux retours",
                "Cette caisse n'accepte que des retours : le mode retour y est permanent et aucune vente n'y est possible. Désactivé : la caisse vend et reprend (LC-05-01-03).",
                "false", null),
        new Def("return.types", Type.TEXT, "RETOUR CLIENT",
                "Types de retours",
                "Types de retours proposés en caisse en plus des deux gestes de base, séparés par des points-virgules. Un type s'écrit « Libellé », ou « Libellé>TICKET » pour un type qui ouvre un ticket de retour dédié au lieu de reprendre l'article dans la vente. Vide : seuls les deux gestes de base sont proposés (LC-05-04-01).",
                "Contenant;Article défectueux>TICKET", null),
        new Def("return.foreign-store-accepted", Type.BOOL, "RETOUR CLIENT",
                "Retour d'un ticket d'un autre magasin",
                "Un ticket émis par un autre point de vente est accepté en retour. Désactivé : le retour ne se fait que sur le point de vente où les achats ont été effectués, ce qui est la règle entre adhérents indépendants (LC-05-02-07).",
                "false", null),
        new Def("return.without-origin-allowed", Type.BOOL, "RETOUR CLIENT",
                "Retour sans ticket d'origine",
                "Un article peut être repris sans que son ticket d'origine soit identifié : les articles sont saisis librement et leur prix reste modifiable. Désactivé : tout retour passe par le rappel du ticket d'origine (LC-05-02-09/14).",
                "true", null),
        new Def("language.system", Type.TEXT, "LANGUE",
                "Langue système du point de vente",
                "Langue par défaut de la visu caisse, de la visu client et du ticket : fr, nl ou pt. C'est celle qui s'applique quand l'opérateur n'en a pas choisi d'autre et qu'aucune langue ne lui est administrée. Vide ou inconnue : français (LC-13-06-01/05/10).",
                "fr", null),
        new Def("language.available", Type.TEXT, "LANGUE",
                "Langues proposées en caisse",
                "Langues que l'opérateur peut choisir, séparées par des points-virgules, par exemple fr;nl;pt. La liste des langues disponibles n'est pas figée dans le logiciel : elle est découverte des fichiers de messages livrés, et de ceux déposés dans le répertoire pos.messages.dir. Une langue sans fichier de messages est ignorée ; liste vide : seul le français est proposé (LC-13-06-03/04).",
                "fr;nl;pt", null),
        new Def("language.operator-change", Type.BOOL, "LANGUE",
                "Changement de langue pendant la session",
                "L'opérateur peut changer la langue à tout moment, y compris pendant une transaction. Désactivé : le changement n'est possible qu'avant l'ouverture de caisse (LC-13-06-04).",
                "true", null),
        new Def("language.customer-second", Type.TEXT, "LANGUE",
                "Deuxième langue de la visu client et du ticket",
                "Deuxième langue affichée à côté de la première sur la visu client et le ticket, pour les régions bilingues : « Terug te geven / A rendre ». Vide : visu client et ticket monolingues (LC-13-06-06/11).",
                "", null),
        new Def("island.change-allowed", Type.BOOL, "ILOT / TPV",
                "Rendu monnaie autorisé sur l'îlot",
                "Le rendu monnaie est autorisé sur cet îlot de caisses. Désactivé : l'îlot interdit le rendu même là où la règle du moyen de paiement l'autoriserait — l'interdiction d'îlot prime (BO-03-10-04).",
                "true", null),
        new Def("island.auto-declaration", Type.BOOL, "ILOT / TPV",
                "Déclaration automatique de fin de période",
                "À la clôture, l'hôtesse est dispensée du comptage : le montant déclaré est le théorique et le journal note que la déclaration était automatique. Désactivé : l'écran de comptage est présenté comme aujourd'hui (BO-03-10-10).",
                "false", null),
        new Def("island.unknown-article-ticket", Type.BOOL, "ILOT / TPV",
                "Imprimer le ticket « article inconnu »",
                "Au scan d'un code que rien ne reconnaît, un ticket est imprimé portant le code lu, la caisse et l'heure — le papier qu'un chef de rayon rapporte au référentiel. Désactivé : seul le message écran, comme aujourd'hui (BO-03-10-05).",
                "false", null),
        new Def("island.block-article-popups", Type.BOOL, "ILOT / TPV",
                "Bloquer les messages liés à l'article",
                "Les messages d'information liés à la vente d'un article — le message administré sur l'article, l'alerte de prix élevé — ne sont plus affichés sur les caisses de cet îlot. Les REFUS ne sont jamais tus : un geste interdit reste interdit et le dit (BO-03-10-13).",
                "false", null),
        new Def("island.block-payment-popups", Type.BOOL, "ILOT / TPV",
                "Bloquer les messages liés au règlement",
                "Les messages d'information liés à un moyen de paiement — les alertes non bloquantes des points de contrôle de règlement — ne sont plus affichés sur les caisses de cet îlot. Les refus et les demandes d'aval ne sont jamais tus (BO-03-10-14).",
                "false", null),
        new Def("island.promotion-channel", Type.TEXT, "ILOT / TPV",
                "Canal promotion de l'îlot",
                "Canal remonté à la fidélité pour les ventes de cet îlot (point de vente, Web, autres). Vide : aucun canal n'est ajouté et la remontée reste identique à l'octet près (BO-03-10-08).",
                "", null),
        new Def("balance.default-tare", Type.TEXT, "BALANCE",
                "Tare logicielle par défaut (kg)",
                "Tare envoyée à la balance à chaque pesée, sauf tare article ou tare contenant qui la remplacent ponctuellement. La balance priorise cette tare et renvoie le poids net, enregistré tel quel. 0 : aucune tare par défaut (LC-14-01-03/06).",
                "0", null),
        new Def("balance.tare-containers", Type.TEXT, "BALANCE",
                "Contenants de tare (code:libellé:poids)",
                "Liste des contenants proposés au caissier pour une tare ponctuelle, séparés par des points-virgules, sous la forme CODE:LIBELLÉ:POIDS_KG (ex. SACPAP:Sac kraft:0.010;BQS:Barquette S:0.035). La tare contenant remplace ponctuellement la tare article ou par défaut et n'est pas persistante. Vide : aucun contenant proposé (LC-14-01-04/05).",
                "", null),
        new Def("control.cancel-line", Type.TEXT, "POINTS DE CONTRÔLE",
                "Contrôle : annulation d'article",
                "Niveau du contrôle : NONE (aucun contrôle), WARN (alerte non bloquante, le geste passe et l'opérateur est prévenu), BLOCK (geste refusé, qu'aucune habilitation de la caisse ne lève) ou ENDORSE (geste refusé jusqu'à l'aval d'un superviseur). Une valeur inconnue retombe sur le défaut de ce point. Sans effet quand l'aval superviseur de l'annulation de ligne est désactivé : ce drapeau dit SI le geste est surveillé, cette clé dit ce qui se passe alors (BO-10-02-01).",
                "ENDORSE", null),
        new Def("control.cancel-payment", Type.TEXT, "POINTS DE CONTRÔLE",
                "Contrôle : annulation d'un paiement partiel",
                "Niveau du contrôle : NONE (aucun contrôle), WARN (alerte non bloquante, le geste passe et l'opérateur est prévenu), BLOCK (geste refusé, qu'aucune habilitation de la caisse ne lève) ou ENDORSE (geste refusé jusqu'à l'aval d'un superviseur). Une valeur inconnue retombe sur le défaut de ce point. Porte sur l'abandon des règlements déjà encaissés d'un ticket en cours de paiement (BO-10-02-02).",
                "NONE", null),
        new Def("control.refund", Type.TEXT, "POINTS DE CONTRÔLE",
                "Contrôle : remboursement",
                "Niveau du contrôle : NONE (aucun contrôle), WARN (alerte non bloquante, le geste passe et l'opérateur est prévenu), BLOCK (geste refusé, qu'aucune habilitation de la caisse ne lève) ou ENDORSE (geste refusé jusqu'à l'aval d'un superviseur). Une valeur inconnue retombe sur le défaut de ce point. Sans effet quand l'aval superviseur du remboursement est désactivé : ce drapeau dit SI le geste est surveillé, cette clé dit ce qui se passe alors (BO-10-02-03).",
                "ENDORSE", null),
        new Def("control.drawer-out-of-sale", Type.TEXT, "POINTS DE CONTRÔLE",
                "Contrôle : ouverture du tiroir hors vente",
                "Niveau du contrôle : NONE (aucun contrôle), WARN (alerte non bloquante, le geste passe et l'opérateur est prévenu), BLOCK (geste refusé, qu'aucune habilitation de la caisse ne lève) ou ENDORSE (geste refusé jusqu'à l'aval d'un superviseur). Une valeur inconnue retombe sur le défaut de ce point. Porte sur les ouvertures qui ne règlent aucune vente : prise de poste et clôture de session (BO-10-02-04). ENDORSE y vaut BLOCK : aucune cérémonie d'aval n'existe à ce moment.",
                "NONE", null),
        new Def("control.quantity", Type.TEXT, "POINTS DE CONTRÔLE",
                "Contrôle : saisie de quantité",
                "Niveau du contrôle : NONE (aucun contrôle), WARN (alerte non bloquante, le geste passe et l'opérateur est prévenu), BLOCK (geste refusé, qu'aucune habilitation de la caisse ne lève) ou ENDORSE (geste refusé jusqu'à l'aval d'un superviseur). Une valeur inconnue retombe sur le défaut de ce point. Déclenché au-delà de la quantité plafond ci-dessous (BO-10-02-06).",
                "NONE", null),
        new Def("control.quantity-max", Type.INT, "POINTS DE CONTRÔLE",
                "Quantité plafond d'une ligne",
                "Quantité au-delà de laquelle le contrôle de saisie de quantité se déclenche. La borne absolue de 999 reste appliquée quoi qu'il arrive (BO-10-02-06).",
                "999", null),
        new Def("control.ticket-total", Type.TEXT, "POINTS DE CONTRÔLE",
                "Contrôle : total du ticket",
                "Niveau du contrôle : NONE (aucun contrôle), WARN (alerte non bloquante, le geste passe et l'opérateur est prévenu), BLOCK (geste refusé, qu'aucune habilitation de la caisse ne lève) ou ENDORSE (geste refusé jusqu'à l'aval d'un superviseur). Une valeur inconnue retombe sur le défaut de ce point. Déclenché au-delà du total plafond ci-dessous (BO-10-02-07). ENDORSE y vaut BLOCK : aucune cérémonie d'aval n'existe à ce moment.",
                "NONE", null),
        new Def("control.ticket-total-max", Type.TEXT, "POINTS DE CONTRÔLE",
                "Total de ticket plafond (€)",
                "Total TTC au-delà duquel le contrôle du total de ticket se déclenche. Vide : aucun plafond, le contrôle ne se déclenche jamais (BO-10-02-07).",
                "", null),
        new Def("control.parked-at-close", Type.TEXT, "POINTS DE CONTRÔLE",
                "Contrôle : clôture avec des tickets en attente",
                "Niveau du contrôle : NONE (aucun contrôle), WARN (alerte non bloquante, le geste passe et l'opérateur est prévenu), BLOCK (geste refusé, qu'aucune habilitation de la caisse ne lève) ou ENDORSE (geste refusé jusqu'à l'aval d'un superviseur). Une valeur inconnue retombe sur le défaut de ce point. Les tickets en attente non repris sont annulés par la clôture : ce contrôle est ce qui en prévient le caissier (BO-10-02-08). ENDORSE y vaut BLOCK : aucune cérémonie d'aval n'existe à ce moment.",
                "NONE", null),
        new Def("control.card-payment", Type.TEXT, "POINTS DE CONTRÔLE",
                "Contrôle : règlement par carte",
                "Niveau du contrôle : NONE (aucun contrôle), WARN (alerte non bloquante, le geste passe et l'opérateur est prévenu), BLOCK (geste refusé, qu'aucune habilitation de la caisse ne lève) ou ENDORSE (geste refusé jusqu'à l'aval d'un superviseur). Une valeur inconnue retombe sur le défaut de ce point. Porte sur l'usage des moyens de paiement monétiques en caisse (BO-10-02-10). ENDORSE y vaut BLOCK : aucune cérémonie d'aval n'existe à ce moment.",
                "NONE", null),
        new Def("control.item-amount", Type.TEXT, "POINTS DE CONTRÔLE",
                "Contrôle : montant d'un article",
                "Niveau du contrôle : NONE (aucun contrôle), WARN (alerte non bloquante, le geste passe et l'opérateur est prévenu), BLOCK (geste refusé, qu'aucune habilitation de la caisse ne lève) ou ENDORSE (geste refusé jusqu'à l'aval d'un superviseur). Une valeur inconnue retombe sur le défaut de ce point. Porte sur le prix forcé d'une ligne, comparé en valeur absolue : une vente et un retour sont plafonnés de la même façon (BO-10-02-16).",
                "NONE", null),
        new Def("control.item-amount-max", Type.TEXT, "POINTS DE CONTRÔLE",
                "Montant d'article plafond (€)",
                "Montant au-delà duquel le contrôle du montant d'article se déclenche, comparé en valeur absolue. Vide : aucun plafond, le contrôle ne se déclenche jamais (BO-10-02-16).",
                "", null),
        new Def("control.payment-amount", Type.TEXT, "POINTS DE CONTRÔLE",
                "Contrôle : montant d'un règlement",
                "Niveau du contrôle : NONE (aucun contrôle), WARN (alerte non bloquante, le geste passe et l'opérateur est prévenu), BLOCK (geste refusé, qu'aucune habilitation de la caisse ne lève) ou ENDORSE (geste refusé jusqu'à l'aval d'un superviseur). Une valeur inconnue retombe sur le défaut de ce point. Porte sur le montant saisi pour un moyen de paiement, comparé en valeur absolue (BO-10-02-17). ENDORSE y vaut BLOCK : aucune cérémonie d'aval n'existe à ce moment.",
                "NONE", null),
        new Def("control.payment-amount-max", Type.TEXT, "POINTS DE CONTRÔLE",
                "Montant de règlement plafond (€)",
                "Montant au-delà duquel le contrôle du montant de règlement se déclenche, comparé en valeur absolue. Vide : aucun plafond, le contrôle ne se déclenche jamais (BO-10-02-17).",
                "", null),
        new Def("financial.closed-weekdays", Type.TEXT, "ÉTATS FINANCIERS",
                "Jours de fermeture hebdomadaire",
                "Jours où le magasin ne travaille pas, noms DayOfWeek anglais (MONDAY, SUNDAY…) séparés par « ; ». Aucun Z quotidien n'est émis ces jours-là et la borne hebdomadaire tombe à la fin du dernier jour travaillé (R6). Vide : tous les jours sont travaillés.",
                "", null),
        new Def("financial.closed-dates", Type.TEXT, "ÉTATS FINANCIERS",
                "Fermetures exceptionnelles",
                "Dates de fermeture ponctuelle au format AAAA-MM-JJ, séparées par « ; » (jours fériés, inventaire…). Traitées comme un jour non travaillé au même titre que les jours de fermeture hebdomadaire (R6). Vide : aucune fermeture exceptionnelle.",
                "", null),
        new Def("financial.hourly-slot-minutes", Type.INT, "ÉTATS FINANCIERS",
                "Largeur de tranche horaire (min)",
                "Largeur, en minutes, des tranches du CA par tranche horaire (R17) : 15, 30 ou 60. Les tranches couvrent du premier au dernier ticket de la période, sans trou. Défaut 60.",
                "60", null),
        new Def("financial.nomenclature-level", Type.INT, "ÉTATS FINANCIERS",
                "Rang de restitution du CA par nomenclature",
                "Niveau de la nomenclature IFLS auquel le CA par nomenclature est restitué (R16) : 0 est la racine. Défaut 1.",
                "1", null),
        new Def("financial.sectors", Type.TEXT, "ÉTATS FINANCIERS",
                "Secteurs du CA",
                "Association d'un code de nœud de rang 0 à l'un des quatre secteurs FRAIS_TRAD, FRAIS_LS, SEC, NON_ALIMENTAIRE, sous la forme « code>SECTEUR » séparée par « ; » (R16). Les trois premiers forment le CA alimentaire.",
                "10>FRAIS_TRAD;20>FRAIS_LS;30>SEC;40>NON_ALIMENTAIRE", null),
        new Def("financial.best-sellers-count", Type.INT, "ÉTATS FINANCIERS",
                "Profondeur des meilleures ventes",
                "Nombre d'articles retenus au classement des meilleures ventes, classées sur la quantité décroissante (R19). Défaut 20.",
                "20", null),
        new Def("financial.auto-emit-enabled", Type.BOOL, "ÉTATS FINANCIERS",
                "Émission automatique de fin de période",
                "Activé (défaut) : les Z de fin de période (jour, semaine, mois) sont émis automatiquement à la clôture. Désactivé : seule l'émission manuelle depuis l'écran produit des Z.",
                "true", null),
        new Def("gesture.endorsement-scope", Type.TEXT, "GESTES NÉGATIFS",
                "Portée de l'aval superviseur",
                "PER_OP : l'aval vaut pour un seul geste (défaut). PER_TICKET : une fois donné, l'aval reste attaché au ticket jusqu'à la touche FIN SUPERVISEUR ou la fin du ticket (EXP-33).",
                "PER_OP", null),
        new Def("payment.trd-card-cap", Type.TEXT, "RÈGLEMENTS RESTREINTS",
                "Plafond carte TRD (€)",
                "Montant maximum réglable par carte titre-restaurant (TRD) sur une transaction, en plus de l'assiette éligible. 0 = plafond non appliqué, seule l'assiette limite (EXP-60).",
                "0.00", null),
        // FLUX SORTANTS (BO-11-01-02/03/04/05). Administrés ici et non en
        // propriétés de déploiement pour une raison du cahier des charges:
        // l'activation et l'adresse sont demandées « à l'échelle Pays /
        // Enseigne / Point de Vente », ce que la résolution par échelons de ce
        // catalogue donne déjà. Une adresse vide vaut flux désactivé: un export
        // qui ne sait pas où écrire ne doit pas écrire ailleurs.
        new Def("export.directory", Type.TEXT, "FLUX SORTANTS",
                "Répertoire de dépôt des flux",
                "Chemin où le nœud dépose les fichiers des flux sortants. Vide : aucun flux n'est produit (BO-11-01-02).",
                "", "pos.export.directory"),
        new Def("export.accounting-cutover-hour", Type.INT, "FLUX SORTANTS",
                "Heure de bascule comptable",
                "Heure à partir de laquelle une vente est rattachée à la journée comptable suivante. 0 : la journée comptable est la journée civile. S'applique conjointement aux flux chiffre d'affaires et livre de caisse (BO-11-01-05).",
                "0", null),
        new Def("export.revenue-enabled", Type.BOOL, "FLUX SORTANTS",
                "Flux chiffre d'affaires par date",
                "Produit le flux de chiffre d'affaires par journée comptable, cumulable sur plusieurs journées (BO-11-01-03).",
                "false", null),
        new Def("export.cash-book-enabled", Type.BOOL, "FLUX SORTANTS",
                "Flux livre de caisse par date",
                "Produit le flux livre de caisse — montants par moyen de paiement — par journée comptable (BO-11-01-04).",
                "false", null),
        new Def("export.family-sales-enabled", Type.BOOL, "FLUX SORTANTS",
                "Flux CA par famille et par îlot",
                "Produit le flux de chiffre d'affaires ventilé par famille d'articles et par îlot de caisse, avec les remises portées par les lignes (BO-11-01-08).",
                "false", null),
        new Def("export.transactions-enabled", Type.BOOL, "FLUX SORTANTS",
                "Flux détail quotidien des transactions",
                "Produit le détail ligne à ligne des transactions de la journée comptable, lignes annulées incluses et marquées (BO-11-01-14).",
                "false", null),
        new Def("export.period-summary-enabled", Type.BOOL, "FLUX SORTANTS",
                "Flux synthèse des transactions de clôture",
                "Produit, par caisse, les compteurs et la plage de numéros de tickets de la journée comptable, ventes et non-ventes séparées, pour le contrôle de complétude (BO-11-01-17).",
                "false", null),
        new Def("export.productivity-enabled", Type.BOOL, "FLUX SORTANTS",
                "Flux productivité des caisses",
                "Produit les indicateurs de productivité par caissière et par prise de poste : tickets, articles, durée, articles par heure, panier moyen (BO-11-01-21).",
                "false", null),
        new Def("export.customer-invoices-enabled", Type.BOOL, "FLUX SORTANTS",
                "Flux factures clients en compte (clifact)",
                "Produit le fichier plat des factures et bons de livraison clients en compte : identité, coordonnées, échéance, lignes facturées, écotaxe. Activation demandée à l'échelle Pays / Enseigne / Point de Vente (BO-11-01-06, BO-11-01-12).",
                "false", null),
        new Def("export.erp-invoices-enabled", Type.BOOL, "FLUX SORTANTS",
                "Flux factures du jour vers l'ERP",
                "Expose les factures émises dans la journée à destination de l'ERP comptable, une ligne par document, duplicata signalé (BO-11-01-22).",
                "false", null),
        new Def("export.fidelity-vat-enabled", Type.BOOL, "FLUX SORTANTS",
                "Flux mensuel conso fidélité par TVA",
                "Produit, une fois par mois, les règlements fidélité ventilés par taux de TVA au prorata du TTC de chaque ticket (BO-11-01-13).",
                "false", null),
        new Def("export.order-vat-enabled", Type.BOOL, "FLUX SORTANTS",
                "Flux ventilation TVA des commandes",
                "Produit la ventilation par taux de TVA des montants des commandes de la journée comptable (BO-11-01-23).",
                "false", null),
        new Def("export.panels-enabled", Type.BOOL, "FLUX SORTANTS",
                "Flux panels marketing (scan)",
                "Produit les trois fichiers de panel — Scannels, Scvenmag, Tlogextr — dans une archive unique MS.<PDV>.<jour>.zip (BO-11-01-19).",
                "false", null),
        // FIL DE L'EAU (BO-11-02). Séparé des flux sortants : ceux-là déposent un
        // fichier par période, celui-ci pousse chaque vente aux tiers abonnés dès
        // qu'elle est consolidée. Les destinataires ne sont pas administrés ici
        // mais dans la table des fournisseurs de service (BO-03-11) : brancher un
        // nouveau tiers est une ligne de déclaration, pas un paramètre.
        new Def("stream.enabled", Type.BOOL, "FIL DE L'EAU",
                "Diffusion des ventes au fil de l'eau",
                "Pousse chaque ticket, retour et ticket monétique aux fournisseurs de service abonnés dès la consolidation. Désactivé : rien n'est mis en file, et la file existante n'est plus dépilée (BO-11-02-02/03/04).",
                "false", null),
        new Def("stream.delivery-seconds", Type.INT, "FIL DE L'EAU",
                "Cadence de diffusion (secondes)",
                "Intervalle entre deux passes de livraison. Un événement non livré garde sa place et sa place dans l'ordre : rien n'est jamais abandonné (BO-11-02-05).",
                "10", null),
        new Def("stream.compress-image", Type.BOOL, "FIL DE L'EAU",
                "Compresser l'image du ticket",
                "L'image imprimée du ticket part compressée (gzip puis Base64) au lieu du texte brut. L'encodage voyage dans la charge : le lecteur n'a jamais à le deviner (BO-11-02-02).",
                "false", null));

    /** The four levels of a control point, shared by the eight gestures. */
    private static final List<String> CONTROL_LEVELS =
            List.of("NONE", "WARN", "BLOCK", "ENDORSE");

    /**
     * THE VALUES A CLOSED-SET PARAMETER MAY TAKE, by key.
     *
     * <p>Several TEXT parameters hold no free text at all: they hold one word
     * out of a known list, and the register reads it with a tolerant
     * {@code of(raw, fallback)} that falls back on the parameter's default when
     * it cannot recognise the word. That tolerance is right at the READING end —
     * an unreadable value must not silently remove a control nor add one — and
     * it is exactly what makes a typo invisible at the WRITING end: {@code BLOK}
     * for {@code BLOCK}, and the shop believes it has blocked a gesture that
     * still passes, with nothing on screen to say otherwise. So the admin page
     * offers these parameters as a list to pick from instead of a field to type
     * in, and the save refuses a word that is not on the list.
     *
     * <p>HERE AND NOT ON {@link Def}, deliberately: this catalogue is kept a
     * character-for-character copy of the register's, where a parameter is only
     * ever READ, and adding a component to the record would make every entry of
     * the two files stop matching. A map beside the catalogue carries what only
     * the administration needs.
     *
     * <p>{@code language.system} is NOT here: the languages a node offers are
     * the bundles present on its classpath, which no written list can state.
     * Neither are the parameters that hold a LIST of values ({@code
     * return.types}, {@code movement.financial-sources}…): what is closed there
     * is each element, not the field.
     */
    public static final Map<String, List<String>> CLOSED_VALUES = Map.ofEntries(
            Map.entry("control.cancel-line", CONTROL_LEVELS),
            Map.entry("control.cancel-payment", CONTROL_LEVELS),
            Map.entry("control.refund", CONTROL_LEVELS),
            Map.entry("control.drawer-out-of-sale", CONTROL_LEVELS),
            Map.entry("control.quantity", CONTROL_LEVELS),
            Map.entry("control.ticket-total", CONTROL_LEVELS),
            Map.entry("control.parked-at-close", CONTROL_LEVELS),
            Map.entry("control.card-payment", CONTROL_LEVELS),
            Map.entry("control.item-amount", CONTROL_LEVELS),
            Map.entry("control.payment-amount", CONTROL_LEVELS),
            Map.entry("cash.float-mode",
                    List.of("FIXED", "FLOATING_WEEK_MONTH", "FLOATING_MONTH")),
            Map.entry("gesture.endorsement-scope", List.of("PER_OP", "PER_TICKET")),
            Map.entry("fidelity.advantage-grouping", List.of("TYPE", "CATEGORY", "NONE")),
            Map.entry("ticket.line-attribute-isolate", List.of("NONE", "START", "END")),
            Map.entry("island.promotion-channel",
                    List.of("", "POINT_OF_SALE", "WEB", "OTHER")));

    /**
     * Returns the values the given parameter may take.
     *
     * @param key the storage key
     * @return the allowed values in display order, or an empty list when the
     *         parameter is not a closed set
     */
    public static List<String> closedValues(String key) {
        return CLOSED_VALUES.getOrDefault(key, List.of());
    }

    /**
     * The echelon inheritance engine, or null on a node where none is wired
     * (a plain register constructed without CDI in a unit test): the layer is
     * then skipped entirely.
     */
    @Inject
    EchelonSettingService echelonSettings;

    /**
     * This node's own point-of-vente number, absent on a standalone register
     * or the store node before it is commissioned into the organisation tree.
     * Absent means no echelon layer applies.
     */
    @Inject
    @ConfigProperty(name = "pos.pdv.number")
    Optional<String> nodePdvNumber;

    /** The cached local override rows, or null when a reload is due. */
    private volatile Map<String, String> cache = null;

    /** The cached echelon-inherited values for this node, or null when due. */
    private volatile Map<String, String> inheritedCache = null;

    /**
     * Returns the catalog entry of a key.
     *
     * @param key the catalog key
     * @return the entry, or null for an unknown key
     */
    public Def def(String key) {
        LOGGER.info("Entering method def with key: " + key);
        for (Def d : CATALOG) {
            if (d.key().equals(key)) { LOGGER.info("Exiting method def"); return d; }
        }
        LOGGER.info("Exiting method def");
        return null;
    }

    /**
     * Returns the CURRENT value of a key: the stored row when present, else
     * the legacy configuration fallback when declared, else the default.
     *
     * @param key the catalog key
     * @return the effective value, as text
     */
    public String value(String key) {
        LOGGER.info("Entering method value with key: " + key);
        Map<String, String> values = cache;
        if (values == null) {
            values = loadAll();
            cache = values;
        }
        String stored = values.get(key);
        if (stored != null) { LOGGER.info("Exiting method value"); return stored; }
        Def d = def(key);
        if (d == null) { LOGGER.info("Exiting method value"); return null; }
        String inherited = inherited().get(key);
        if (inherited != null) { LOGGER.info("Exiting method value"); return inherited; }
        if (d.configFallback() != null) {
            Optional<String> fromConfig = ConfigProvider.getConfig()
                    .getOptionalValue(d.configFallback(), String.class);
            if (fromConfig.isPresent()) { LOGGER.info("Exiting method value"); return fromConfig.get(); }
        }
        LOGGER.info("Exiting method value");
        return d.defaultValue();
    }

    /**
     * Returns the echelon-inherited values of this node, resolved once and
     * cached until the next invalidation.
     *
     * @return the inherited key-to-value map, never null
     */
    private Map<String, String> inherited() {
        Map<String, String> values = inheritedCache;
        if (values == null) {
            values = loadInherited();
            inheritedCache = values;
        }
        return values;
    }

    /**
     * Resolves this node's echelon-inherited values, or an empty map when no
     * echelon layer applies (no engine wired, or no PDV number on this node).
     *
     * @return the inherited key-to-value map, never null
     */
    private Map<String, String> loadInherited() {
        if (echelonSettings == null || nodePdvNumber == null || nodePdvNumber.isEmpty()) {
            return Map.of();
        }
        try {
            return echelonSettings.resolveForPdv(nodePdvNumber.get());
        } catch (Exception e) {
            // A failed resolution (boot ordering, missing table) falls back to
            // the local overrides and defaults; the next access retries.
            LOGGER.warnf("Resolution des echelons impossible (%s): valeurs locales", e.getMessage());
            inheritedCache = null;
            return Map.of();
        }
    }

    /**
     * Reads every stored row — one query, called only on a cold cache.
     *
     * @return the key-to-value map of stored overrides
     */
    private Map<String, String> loadAll() {
        Map<String, String> values = new HashMap<>();
        try {
            for (PosSetting row : PosSetting.<PosSetting>listAll()) {
                values.put(row.settingKey, row.settingValue);
            }
        } catch (Exception e) {
            // A failed read (boot ordering, missing table) falls back to the
            // defaults; the next access retries.
            LOGGER.warnf("Lecture des paramètres impossible (%s): valeurs par défaut", e.getMessage());
            cache = null;
        }
        return values;
    }

    /**
     * Returns the ADMINISTERED parameters with their EFFECTIVE value on this
     * node — the keys that carry a local override OR an echelon-inherited value,
     * each resolved through the precedence (local override &gt; echelon), ordered
     * by key. This is what the store node PUBLISHES down the SETTINGS pull (route
     * A, BO-02-05-04): the register receives the resolved result, never the
     * echelon chain, and a key that only has its catalog default is absent — the
     * register recomputes the default itself.
     * <p>
     * The echelon layer is resolved FRESH here (not from the long-lived cache)
     * so a value whose effect date has just come is picked up on the next
     * fingerprint recomputation without any invalidation — this is what makes a
     * scheduled echelon change apply on its own (BO-03-12-03/04). On a node with
     * no echelon engine or no PDV number the map is just the local overrides.
     *
     * @return the administered key-to-effective-value map, ordered by key
     */
    public Map<String, String> administeredValues() {
        LOGGER.info("Entering method administeredValues");
        LOGGER.info("Exiting method administeredValues");
        return administeredValues(null);
    }

    /**
     * Returns the administered parameters with their EFFECTIVE value FOR ONE
     * REGISTER — the same resolution as {@link #administeredValues()}, but with
     * the two echelons of the shop added when the register names itself: its
     * island, then the checkout point itself.
     *
     * <p>This is what closes the twenty-one partial lines of BO-03-09/03-10: a
     * value posed on one lane wins over its island's, which wins over the PDV's,
     * which wins over the enseigne's and the country's. A null or blank terminal
     * resolves for the PDV alone, which is what an older register that sends no
     * identifier receives — so a lane that has not been updated keeps working
     * unchanged.
     *
     * <p>An unknown terminal is NOT an error: it resolves like the PDV plus
     * whatever was posed on that identifier at the CHECKOUT_POINT level, which
     * is normally nothing. The identifier names, it proves nothing — the token
     * and the role gate remain the only judges of who may ask.
     *
     * @param terminalId the register's identifier, or null for the PDV alone
     * @return the administered key-to-effective-value map, ordered by key
     */
    public Map<String, String> administeredValues(String terminalId) {
        LOGGER.info("Entering method administeredValues with terminalId: " + terminalId);
        Map<String, String> local = cache;
        if (local == null) {
            local = loadAll();
            cache = local;
        }
        Map<String, String> echelon;
        if (echelonSettings == null || nodePdvNumber == null || nodePdvNumber.isEmpty()) {
            echelon = Map.of();
        } else if (terminalId == null || terminalId.isBlank()) {
            echelon = echelonSettings.resolveForPdv(nodePdvNumber.get());
        } else {
            echelon = echelonSettings.resolveForTerminal(nodePdvNumber.get(), terminalId.trim());
        }
        java.util.TreeMap<String, String> result = new java.util.TreeMap<>(echelon);
        result.putAll(local);
        LOGGER.info("Exiting method administeredValues");
        return result;
    }

    /**
     * Drops both caches — called after every admin save and pull apply. The
     * echelon cache is dropped too so a re-pulled enseigne default is picked
     * up on the next read.
     */
    public void invalidate() {
        LOGGER.info("Entering method invalidate");
        cache = null;
        inheritedCache = null;
        LOGGER.info("Exiting method invalidate");
    }

    /**
     * Stores one value (upsert by key) — the admin page's save path.
     *
     * @param key the catalog key
     * @param value the new value, already validated by the caller
     */
    @Transactional
    public void store(String key, String value) {
        LOGGER.info("Entering method store with key: " + key + ", value: " + value);
        PosSetting row = PosSetting.findByKey(key);
        if (row == null) {
            row = new PosSetting();
            row.settingKey = key;
        }
        row.settingValue = value;
        row.persist();
        invalidate();
        LOGGER.info("Exiting method store");
    }

    /**
     * Returns a boolean parameter.
     *
     * @param key the catalog key
     * @return the effective boolean value
     */
    private boolean boolValue(String key) {
        return Boolean.parseBoolean(value(key));
    }

    /**
     * Returns an integer parameter, guarding against a corrupt row.
     *
     * @param key the catalog key
     * @return the effective integer value, or the catalog default when corrupt
     */
    private int intValue(String key) {
        try {
            return Integer.parseInt(value(key).trim());
        } catch (Exception e) {
            return Integer.parseInt(def(key).defaultValue());
        }
    }

    /**
     * Returns a decimal (monetary) parameter, guarding against a corrupt row.
     *
     * @param key the catalog key
     * @return the effective decimal value, or the catalog default when corrupt
     */
    private BigDecimal bigDecimalValue(String key) {
        try {
            return new BigDecimal(value(key).trim());
        } catch (Exception e) {
            return new BigDecimal(def(key).defaultValue());
        }
    }

    /**
     * Whether the article EAN accompanies the label on screen and paper.
     *
     * @return true when the EAN is displayed
     */
    public boolean showEan() {
        LOGGER.info("Entering method showEan");
        LOGGER.info("Exiting method showEan");
        return boolValue("display.show-ean");
    }

    /**
     * The idle-lockout delay in seconds; 0 disables the automatic pause.
     *
     * @return the delay in seconds
     */
    public long idleLockoutSeconds() {
        LOGGER.info("Entering method idleLockoutSeconds");
        LOGGER.info("Exiting method idleLockoutSeconds");
        return intValue("auth.idle-lockout-seconds");
    }

    /**
     * Whether price gestures require a manager endorsement.
     *
     * @return true when the endorsement ceremony applies
     */
    public boolean gestureEndorsementRequired() {
        LOGGER.info("Entering method gestureEndorsementRequired");
        LOGGER.info("Exiting method gestureEndorsementRequired");
        return boolValue("gesture.endorsement-required");
    }

    /**
     * The maximal percentage of a line discount.
     *
     * @return the cap, in percent
     */
    public int lineMaxDiscountPercent() {
        LOGGER.info("Entering method lineMaxDiscountPercent");
        LOGGER.info("Exiting method lineMaxDiscountPercent");
        return intValue("discount.line-max-percent");
    }

    /**
     * The maximal percentage of a whole-ticket discount.
     *
     * @return the cap, in percent
     */
    public int globalMaxDiscountPercent() {
        LOGGER.info("Entering method globalMaxDiscountPercent");
        LOGGER.info("Exiting method globalMaxDiscountPercent");
        return intValue("discount.global-max-percent");
    }

    /**
     * The customer-display message while the register is open and idle.
     *
     * @return the display text
     */
    public String customerOpenMessage() {
        LOGGER.info("Entering method customerOpenMessage");
        LOGGER.info("Exiting method customerOpenMessage");
        return value("customer.message-open");
    }

    /**
     * The customer-display message while the register is locked.
     *
     * @return the display text
     */
    public String customerClosedMessage() {
        LOGGER.info("Entering method customerClosedMessage");
        LOGGER.info("Exiting method customerClosedMessage");
        return value("customer.message-closed");
    }

    /**
     * Whether the operator must key a password to take the post.
     *
     * <p>When this is off, a scanned badge opens the register on its own
     * (LC-01-01-03): the badge IS the credential. It never lets a typed
     * identifier in without a password — only a scan, which is a physical
     * object the operator carries.
     *
     * @return true when a password is required at opening
     */
    public boolean passwordRequiredOnOpen() {
        LOGGER.info("Entering method passwordRequiredOnOpen");
        LOGGER.info("Exiting method passwordRequiredOnOpen");
        return boolValue("auth.password-required-on-open");
    }

    /**
     * Whether closing the register asks the operator for their password.
     *
     * @return true when a password is required at closing
     */
    public boolean passwordRequiredOnClose() {
        LOGGER.info("Entering method passwordRequiredOnClose");
        LOGGER.info("Exiting method passwordRequiredOnClose");
        return boolValue("auth.password-required-on-close");
    }

    /**
     * Whether a parked ticket left behind blocks the close until a supervisor
     * endorses it.
     *
     * @return true when the endorsement is required
     */
    public boolean closeEndorsementOnPending() {
        LOGGER.info("Entering method closeEndorsementOnPending");
        LOGGER.info("Exiting method closeEndorsementOnPending");
        return boolValue("session.close-endorsement-on-pending");
    }

    /**
     * Whether taking the post prints an opening receipt.
     *
     * @return true when the opening receipt is printed
     */
    public boolean printOpenReceipt() {
        LOGGER.info("Entering method printOpenReceipt");
        LOGGER.info("Exiting method printOpenReceipt");
        return boolValue("session.print-open-receipt");
    }

    /**
     * Whether closing the register prints a closing receipt.
     *
     * @return true when the closing receipt is printed
     */
    public boolean printCloseReceipt() {
        LOGGER.info("Entering method printCloseReceipt");
        LOGGER.info("Exiting method printCloseReceipt");
        return boolValue("session.print-close-receipt");
    }

    /**
     * Whether the register may open a VOLATILE customer — a passing customer,
     * without an account ({@code BO-10-04-01}).
     *
     * @return true when the till may create one
     */
    public boolean volatileCustomerCreationEnabled() {
        LOGGER.info("Entering method volatileCustomerCreationEnabled");
        LOGGER.info("Exiting method volatileCustomerCreationEnabled");
        return boolValue("customer.volatile-creation-enabled");
    }

    /**
     * Whether creating a customer IN ACCOUNT is refused, at the till and in the
     * back office alike ({@code BO-10-04-05}, {@code BO-02-04-12}).
     *
     * @return true when the creation is blocked
     */
    public boolean accountCustomerCreationBlocked() {
        LOGGER.info("Entering method accountCustomerCreationBlocked");
        LOGGER.info("Exiting method accountCustomerCreationBlocked");
        return boolValue("customer.account-creation-blocked");
    }

    /**
     * Whether an identified customer's details are shown to the operator
     * ({@code BO-10-04-02}).
     *
     * @return true when the details are shown
     */
    public boolean showCustomerDetails() {
        LOGGER.info("Entering method showCustomerDetails");
        LOGGER.info("Exiting method showCustomerDetails");
        return boolValue("customer.show-details");
    }

    /**
     * Whether the operator may correct a customer's details at the till
     * ({@code BO-10-04-03}, {@code BO-10-04-04}).
     *
     * @return true when the correction is allowed
     */
    public boolean customerUpdateEnabled() {
        LOGGER.info("Entering method customerUpdateEnabled");
        LOGGER.info("Exiting method customerUpdateEnabled");
        return boolValue("customer.update-enabled");
    }

    /**
     * Whether the discount of a customer in account is announced to the
     * operator ({@code BO-10-04-08}).
     *
     * @return true when the discount is announced
     */
    public boolean showCustomerDiscount() {
        LOGGER.info("Entering method showCustomerDiscount");
        LOGGER.info("Exiting method showCustomerDiscount");
        return boolValue("customer.show-discount");
    }

    /**
     * The segment a customer in account must belong to for their discount to
     * apply ({@code BO-10-04-07}).
     *
     * @return the segment, empty when every account's discount applies
     */
    public String customerDiscountSegment() {
        LOGGER.info("Entering method customerDiscountSegment");
        LOGGER.info("Exiting method customerDiscountSegment");
        return value("customer.discount-segment");
    }

    /**
     * The tender key customer credit is registered under
     * ({@code BO-10-04-09}).
     *
     * @return the administered key, never blank
     */
    public String customerCreditTender() {
        LOGGER.info("Entering method customerCreditTender");
        String key = value("customer.credit-tender");
        LOGGER.info("Exiting method customerCreditTender");
        return key == null || key.isBlank() ? "CREDIT" : key.trim();
    }

    /**
     * Whether customer credit may settle only PART of the ticket
     * ({@code BO-10-04-10}).
     *
     * @return true when a partial settlement is accepted
     */
    public boolean partialCreditAllowed() {
        LOGGER.info("Entering method partialCreditAllowed");
        LOGGER.info("Exiting method partialCreditAllowed");
        return boolValue("customer.partial-credit-allowed");
    }

    /**
     * The alert shown when a customer function is asked for on a register whose
     * referential is no longer trustworthy ({@code BO-10-04-11/12}).
     *
     * @return the administered message
     */
    public String customerOfflineMessage() {
        LOGGER.info("Entering method customerOfflineMessage");
        LOGGER.info("Exiting method customerOfflineMessage");
        return value("customer.offline-message");
    }

    /**
     * The alert shown when the settlement would pass the customer's ceiling
     * ({@code BO-10-04-13}).
     *
     * @return the administered message, its two tokens not yet replaced
     */
    public String customerOverLimitMessage() {
        LOGGER.info("Entering method customerOverLimitMessage");
        LOGGER.info("Exiting method customerOverLimitMessage");
        return value("customer.over-limit-message");
    }

    /**
     * The message a HOLDER's receipt carries when the loyalty service did not
     * answer during the sale ({@code BO-03-03-29}).
     *
     * @return the administered message, its {@code {carte}} token not yet
     *         replaced; empty when the shop prints nothing
     */
    public String fidelityOfflineMessage() {
        LOGGER.info("Entering method fidelityOfflineMessage");
        LOGGER.info("Exiting method fidelityOfflineMessage");
        return value("fidelity.offline-message");
    }

    /**
     * The message a receipt carries when the customer presented NO card and the
     * loyalty service did not answer either ({@code BO-03-03-30}).
     *
     * @return the administered message, empty when the shop prints nothing
     */
    public String fidelityOfflineMessageNoCard() {
        LOGGER.info("Entering method fidelityOfflineMessageNoCard");
        LOGGER.info("Exiting method fidelityOfflineMessageNoCard");
        return value("fidelity.offline-message-no-card");
    }

    /**
     * The fiscal identifier presented at the till before the ticket is
     * validated ({@code BO-10-04-15}).
     *
     * @return the administered number, empty when none is proposed
     */
    public String defaultTaxId() {
        LOGGER.info("Entering method defaultTaxId");
        LOGGER.info("Exiting method defaultTaxId");
        return value("customer.default-tax-id");
    }

    /**
     * The label printed in place of the default fiscal identifier
     * ({@code BO-10-04-16}).
     *
     * @return the administered label
     */
    public String defaultTaxIdLabel() {
        LOGGER.info("Entering method defaultTaxIdLabel");
        LOGGER.info("Exiting method defaultTaxIdLabel");
        return value("customer.default-tax-id-label");
    }

    /**
     * The country codes offered at the till for a foreign fiscal identifier
     * ({@code BO-10-04-17}).
     *
     * @return the administered list, comma-separated
     */
    public String foreignTaxIdCountries() {
        LOGGER.info("Entering method foreignTaxIdCountries");
        LOGGER.info("Exiting method foreignTaxIdCountries");
        return value("customer.foreign-tax-id-countries");
    }

    /**
     * Whether an invoice extraction carries the customer's due date
     * ({@code BO-10-04-19}).
     *
     * @return true when the due date is exported
     */
    public boolean invoiceExportDueDate() {
        LOGGER.info("Entering method invoiceExportDueDate");
        LOGGER.info("Exiting method invoiceExportDueDate");
        return boolValue("customer.invoice-export-due-date");
    }

    /**
     * Whether an invoice extraction carries the eco-tax of the settlement
     * ({@code BO-10-04-14}).
     *
     * @return true when the eco-tax is exported
     */
    public boolean invoiceExportEcoTax() {
        LOGGER.info("Entering method invoiceExportEcoTax");
        LOGGER.info("Exiting method invoiceExportEcoTax");
        return boolValue("customer.invoice-export-ecotax");
    }

    /**
     * Whether the registers still open are closed of their own accord as the
     * period ends (LC-01-02-08, BO-09-01-06/09).
     *
     * @return true when the automatic close is armed
     */
    public boolean periodAutoCloseEnabled() {
        LOGGER.info("Entering method periodAutoCloseEnabled");
        LOGGER.info("Exiting method periodAutoCloseEnabled");
        return boolValue("period.auto-close-enabled");
    }

    /**
     * The moment the period ends, as the administered {@code HH:mm} text.
     *
     * <p>Returned as text and parsed by its consumer: a malformed row must not
     * take the whole settings screen down, and the node that reads it is the
     * one able to say what it does with an unreadable hour.
     *
     * @return the administered value, never null
     */
    public String periodEndTime() {
        LOGGER.info("Entering method periodEndTime");
        LOGGER.info("Exiting method periodEndTime");
        return value("period.end-time");
    }

    /**
     * How many minutes before the period ends the open registers are asked to
     * close (BO-09-03-07).
     *
     * @return the lead in minutes, the catalog default when the row is corrupt
     */
    public int periodCloseLeadMinutes() {
        LOGGER.info("Entering method periodCloseLeadMinutes");
        LOGGER.info("Exiting method periodCloseLeadMinutes");
        return intValue("period.close-lead-minutes");
    }

    /**
     * Whether parking a ticket prints the resume receipt.
     *
     * @return true when the parked receipt is printed
     */
    public boolean parkingPrintReceipt() {
        LOGGER.info("Entering method parkingPrintReceipt");
        LOGGER.info("Exiting method parkingPrintReceipt");
        return boolValue("parking.print-receipt");
    }

    /**
     * Whether the manual monetique degraded mode is active (BO-03-12-05):
     * card payments bypass the configured terminal and are accepted
     * immediately.
     *
     * @return true when degraded mode is on
     */
    public boolean paymentDegradedMode() {
        LOGGER.info("Entering method paymentDegradedMode");
        LOGGER.info("Exiting method paymentDegradedMode");
        return boolValue("payment.degraded-mode");
    }

    /**
     * Whether the line and ticket discount/rebate gestures are offered at the
     * register (BO-03-07-01): disabled, the cashier can apply none of them.
     *
     * @return true when discounts and rebates are active
     */
    public boolean discountEnabled() {
        LOGGER.info("Entering method discountEnabled");
        LOGGER.info("Exiting method discountEnabled");
        return boolValue("discount.enabled");
    }

    /**
     * Whether a forced price recalls the article's original price on screen
     * (BO-10-07-12): disabled, the original price is masked.
     *
     * @return true when the original price is shown on a price forcing
     */
    public boolean priceShowOriginalOnForce() {
        LOGGER.info("Entering method priceShowOriginalOnForce");
        LOGGER.info("Exiting method priceShowOriginalOnForce");
        return boolValue("price.show-original-on-force");
    }

    /**
     * The message printed at the TOP of the sale ticket, under the store
     * address (BO-03-08-03); empty when no header message is administered.
     *
     * @return the ticket header message, possibly empty
     */
    public String ticketHeaderMessage() {
        LOGGER.info("Entering method ticketHeaderMessage");
        LOGGER.info("Exiting method ticketHeaderMessage");
        return value("ticket.header-message");
    }

    /**
     * The message printed at the BOTTOM of the sale ticket, after the closing
     * courtesy line (BO-03-08-03, BO-03-08-05); empty when none is administered.
     *
     * @return the ticket footer message, possibly empty
     */
    public String ticketFooterMessage() {
        LOGGER.info("Entering method ticketFooterMessage");
        LOGGER.info("Exiting method ticketFooterMessage");
        return value("ticket.footer-message");
    }

    /**
     * Whether the physical-tender payments open the cash drawer (BO-10-02-12):
     * disabled, the drawer stays shut on cash, cheque and meal-voucher tenders.
     *
     * @return true when a physical payment opens the drawer
     */
    public boolean drawerOpenOnPayment() {
        LOGGER.info("Entering method drawerOpenOnPayment");
        LOGGER.info("Exiting method drawerOpenOnPayment");
        return boolValue("drawer.open-on-payment");
    }

    /**
     * Whether customer credit stays available while the client referential is
     * stale ({@code LC-07-09-05}).
     *
     * @return true when the shop accepts the risk of an out-of-date balance
     */
    /**
     * The legal cash-rounding step in cents ({@code LC-07-03-01}); zero or one
     * means the shop does not round.
     *
     * @return the rounding step in cents
     */
    public int cashRoundingStepCents() {
        LOGGER.info("Entering method cashRoundingStepCents");
        LOGGER.info("Exiting method cashRoundingStepCents");
        return intValue("cash.rounding-step-cents");
    }

    /**
     * Whether a counter-ticket line keeps the price the scale computed, rather than
     * being re-priced from the catalog ({@code LC-06-01-04}).
     *
     * @return true when the counter's own price is booked
     */
    public boolean balanceCounterPrice() {
        LOGGER.info("Entering method balanceCounterPrice");
        LOGGER.info("Exiting method balanceCounterPrice");
        return boolValue("balance.counter-price");
    }

    /**
     * Whether a manual backup-monetics validation needs a supervisor
     * ({@code LC-07-07-09}).
     *
     * @return true when the shop requires an endorsement
     */
    public boolean backupManualEndorsement() {
        LOGGER.info("Entering method backupManualEndorsement");
        LOGGER.info("Exiting method backupManualEndorsement");
        return boolValue("backup.manual-endorsement");
    }

    /**
     * Whether forcing or releasing the monetics degraded mode needs a supervisor
     * ({@code LC-07-08-03/04}).
     *
     * @return true when the shop requires an endorsement
     */
    public boolean moneticsDegradedForcedEndorsement() {
        LOGGER.info("Entering method moneticsDegradedForcedEndorsement");
        LOGGER.info("Exiting method moneticsDegradedForcedEndorsement");
        return boolValue("payment.degraded-forced-endorsement");
    }

    /**
     * How long a forced monetics degraded mode lasts before releasing itself
     * ({@code LC-07-08-04}).
     *
     * @return the forcing duration in minutes
     */
    public int moneticsDegradedForcedMinutes() {
        LOGGER.info("Entering method moneticsDegradedForcedMinutes");
        LOGGER.info("Exiting method moneticsDegradedForcedMinutes");
        return intValue("payment.degraded-forced-minutes");
    }

    /**
     * The administered scheme table of the backup monetics ({@code LC-07-07-08}).
     *
     * @return the {@code ID=LABEL} pairs, comma separated
     */
    public String backupMethodLabels() {
        LOGGER.info("Entering method backupMethodLabels");
        LOGGER.info("Exiting method backupMethodLabels");
        return value("backup.method-labels");
    }

    /**
     * Whether customer credit may still settle a sale while the client
     * referential is stale ({@code LC-07-09-05}): disabled, credit is refused
     * until the referential has been refreshed, the outstanding balance and the
     * ceiling no longer being trustworthy.
     *
     * @return true when credit is allowed in degraded mode
     */
    public boolean creditAllowedInDegraded() {
        LOGGER.info("Entering method creditAllowedInDegraded");
        LOGGER.info("Exiting method creditAllowedInDegraded");
        return boolValue("credit.allowed-in-degraded");
    }

    /**
     * How long the client referential may go without a successful pull before
     * customer credit is considered degraded ({@code LC-07-09-05}).
     *
     * @return the tolerated staleness in minutes
     */
    public int creditDegradedAfterMinutes() {
        LOGGER.info("Entering method creditDegradedAfterMinutes");
        LOGGER.info("Exiting method creditDegradedAfterMinutes");
        return intValue("credit.degraded-after-minutes");
    }

    /**
     * Whether taking the post (unlock) opens the cash drawer to install the
     * float (BO-10-02-25): disabled, the drawer stays shut at login.
     *
     * @return true when the unlock pulse opens the drawer
     */
    /**
     * The directory the outgoing flows are written to (BO-11-01-02), or an empty
     * string when none is administered.
     *
     * <p>An empty directory DISABLES every flow rather than falling back to a
     * working directory: a file dropped where nobody collects it is worse than no
     * file, because the accounting side then waits for something that was
     * produced and lost.
     *
     * @return the destination directory, trimmed, possibly empty
     */
    public String exportDirectory() {
        LOGGER.info("Entering method exportDirectory");
        String directory = value("export.directory");
        LOGGER.info("Exiting method exportDirectory");
        return directory == null ? "" : directory.trim();
    }

    /**
     * The accounting cut-over hour (BO-11-01-05): the hour from which a sale
     * belongs to the NEXT accounting day.
     *
     * <p>Zero — the default — makes the accounting day the civil day, which is
     * what the node did before this parameter existed. A value outside 0..23 is
     * read as zero rather than refused: a corrupt row must not stop the exports.
     *
     * @return the cut-over hour, between 0 and 23
     */
    public int accountingCutoverHour() {
        LOGGER.info("Entering method accountingCutoverHour");
        int hour = intValue("export.accounting-cutover-hour");
        LOGGER.info("Exiting method accountingCutoverHour");
        return (hour < 0 || hour > 23) ? 0 : hour;
    }

    /**
     * Whether the revenue-by-date flow is produced (BO-11-01-03).
     *
     * @return true when the flow is activated at this echelon
     */
    public boolean exportRevenueEnabled() {
        LOGGER.info("Entering method exportRevenueEnabled");
        LOGGER.info("Exiting method exportRevenueEnabled");
        return boolValue("export.revenue-enabled");
    }

    /**
     * Whether the cash-book-by-date flow is produced (BO-11-01-04).
     *
     * @return true when the flow is activated at this echelon
     */
    public boolean exportCashBookEnabled() {
        LOGGER.info("Entering method exportCashBookEnabled");
        LOGGER.info("Exiting method exportCashBookEnabled");
        return boolValue("export.cash-book-enabled");
    }

    /**
     * Whether the revenue-by-family-and-island flow is produced (BO-11-01-08).
     *
     * @return true when the flow is activated at this echelon
     */
    public boolean exportFamilySalesEnabled() {
        LOGGER.info("Entering method exportFamilySalesEnabled");
        LOGGER.info("Exiting method exportFamilySalesEnabled");
        return boolValue("export.family-sales-enabled");
    }

    /**
     * Whether the daily transaction-detail flow is produced (BO-11-01-14).
     *
     * @return true when the flow is activated at this echelon
     */
    public boolean exportTransactionsEnabled() {
        LOGGER.info("Entering method exportTransactionsEnabled");
        LOGGER.info("Exiting method exportTransactionsEnabled");
        return boolValue("export.transactions-enabled");
    }

    /**
     * Whether the closing-synthesis flow is produced (BO-11-01-17).
     *
     * @return true when the flow is activated at this echelon
     */
    public boolean exportPeriodSummaryEnabled() {
        LOGGER.info("Entering method exportPeriodSummaryEnabled");
        LOGGER.info("Exiting method exportPeriodSummaryEnabled");
        return boolValue("export.period-summary-enabled");
    }

    /**
     * Whether the checkout-productivity flow is produced (BO-11-01-21).
     *
     * @return true when the flow is activated at this echelon
     */
    public boolean exportProductivityEnabled() {
        LOGGER.info("Entering method exportProductivityEnabled");
        LOGGER.info("Exiting method exportProductivityEnabled");
        return boolValue("export.productivity-enabled");
    }

    /**
     * Whether the customer-account invoice flow is produced (BO-11-01-06/12).
     *
     * @return true when the flow is activated at this echelon
     */
    public boolean exportCustomerInvoicesEnabled() {
        LOGGER.info("Entering method exportCustomerInvoicesEnabled");
        LOGGER.info("Exiting method exportCustomerInvoicesEnabled");
        return boolValue("export.customer-invoices-enabled");
    }

    /**
     * Whether the day's invoices are exposed to the ERP (BO-11-01-22).
     *
     * @return true when the flow is activated at this echelon
     */
    public boolean exportErpInvoicesEnabled() {
        LOGGER.info("Entering method exportErpInvoicesEnabled");
        LOGGER.info("Exiting method exportErpInvoicesEnabled");
        return boolValue("export.erp-invoices-enabled");
    }

    /**
     * Whether the monthly fidelity/VAT flow is produced (BO-11-01-13).
     *
     * @return true when the flow is activated at this echelon
     */
    public boolean exportFidelityVatEnabled() {
        LOGGER.info("Entering method exportFidelityVatEnabled");
        LOGGER.info("Exiting method exportFidelityVatEnabled");
        return boolValue("export.fidelity-vat-enabled");
    }

    /**
     * Whether the order VAT ventilation is produced (BO-11-01-23).
     *
     * @return true when the flow is activated at this echelon
     */
    public boolean exportOrderVatEnabled() {
        LOGGER.info("Entering method exportOrderVatEnabled");
        LOGGER.info("Exiting method exportOrderVatEnabled");
        return boolValue("export.order-vat-enabled");
    }

    /**
     * Whether the marketing-panel archive is produced (BO-11-01-19).
     *
     * @return true when the flow is activated at this echelon
     */
    public boolean exportPanelsEnabled() {
        LOGGER.info("Entering method exportPanelsEnabled");
        LOGGER.info("Exiting method exportPanelsEnabled");
        return boolValue("export.panels-enabled");
    }

    /**
     * Whether the at-the-flow stream is pushing to its subscribers (BO-11-02-02).
     *
     * @return true when the stream is activated at this echelon
     */
    public boolean streamEnabled() {
        LOGGER.info("Entering method streamEnabled");
        LOGGER.info("Exiting method streamEnabled");
        return boolValue("stream.enabled");
    }

    /**
     * The cadence of the at-the-flow delivery loop, in seconds (BO-11-02-05).
     *
     * <p>Clamped to at least one second: a cadence of zero would turn the loop
     * into a busy wait, and a negative one is refused by the scheduler outright.
     *
     * @return the cadence, at least one second
     */
    public int streamDeliverySeconds() {
        LOGGER.info("Entering method streamDeliverySeconds");
        int seconds = intValue("stream.delivery-seconds");
        LOGGER.info("Exiting method streamDeliverySeconds");
        return seconds < 1 ? 1 : seconds;
    }

    /**
     * Whether the streamed ticket image travels compressed (BO-11-02-02).
     *
     * @return true when the image is gzipped and Base64-encoded
     */
    public boolean streamCompressImage() {
        LOGGER.info("Entering method streamCompressImage");
        LOGGER.info("Exiting method streamCompressImage");
        return boolValue("stream.compress-image");
    }

    /**
     * Returns this node's own point-of-vente number, or an empty string on a node
     * that was never commissioned into the organisation tree.
     *
     * <p>Empty rather than null, because every caller writes it into a file name
     * or a document: a null here would travel as the word "null" into a name the
     * receiving side matches on.
     *
     * @return the PDV number, or an empty string
     */
    public String pdvNumber() {
        LOGGER.info("Entering method pdvNumber");
        LOGGER.info("Exiting method pdvNumber");
        return nodePdvNumber == null ? "" : nodePdvNumber.orElse("");
    }

    /**
     * Whether taking the post (unlock) opens the cash drawer to install the
     * float (BO-10-02-25): disabled, the drawer stays shut at login.
     *
     * @return true when the unlock pulse opens the drawer
     */
    public boolean drawerOpenOnLogin() {
        LOGGER.info("Entering method drawerOpenOnLogin");
        LOGGER.info("Exiting method drawerOpenOnLogin");
        return boolValue("drawer.open-on-login");
    }

    /**
     * Whether a scanned EAN13 has its check digit validated before lookup
     * (BO-10-02-21): disabled, no key control is performed.
     *
     * @return true when the EAN13 check digit is enforced
     */
    public boolean ean13CheckDigitEnabled() {
        LOGGER.info("Entering method ean13CheckDigitEnabled");
        LOGGER.info("Exiting method ean13CheckDigitEnabled");
        return boolValue("scan.ean13-check-digit");
    }

    /**
     * Whether the register alerts (supervisor calls) are shown on the back
     * office dashboard (BO-10-08-01): disabled, no alert reaches the screen.
     *
     * @return true when the dashboard shows the alerts
     */
    public boolean dashboardAlertsEnabled() {
        LOGGER.info("Entering method dashboardAlertsEnabled");
        LOGGER.info("Exiting method dashboardAlertsEnabled");
        return boolValue("dashboard.alerts-enabled");
    }

    /**
     * Whether the loyalty advantage section is printed on the sale ticket
     * (BO-10-03-15): disabled, no fidelity section is printed.
     *
     * @return true when the advantage section is printed
     */
    public boolean fidelityAdvantagesEnabled() {
        LOGGER.info("Entering method fidelityAdvantagesEnabled");
        LOGGER.info("Exiting method fidelityAdvantagesEnabled");
        return boolValue("fidelity.advantages-enabled");
    }

    /**
     * The administered label printed before the projected loyalty balance
     * (imfid response B): the forecast cagnotte after this sale, credited only
     * at ingestion (BO-03-03-31).
     *
     * @return the projected-balance label
     */
    public String fidelityProjectedLabel() {
        LOGGER.info("Entering method fidelityProjectedLabel");
        LOGGER.info("Exiting method fidelityProjectedLabel");
        return value("fidelity.projected-label");
    }

    /**
     * Whether several loyalty cards may be scanned during one transaction
     * (BO-10-03-02): enabled, the last card wins; disabled, a card already
     * attached blocks any further scan.
     *
     * @return true when multiple card scans are allowed
     */
    public boolean fidelityAllowMultipleScan() {
        LOGGER.info("Entering method fidelityAllowMultipleScan");
        LOGGER.info("Exiting method fidelityAllowMultipleScan");
        return boolValue("fidelity.allow-multiple-scan");
    }

    /**
     * Whether the holder's name found by a loyalty lookup is carried onto the
     * register (BO-10-03-04): disabled, the register keeps the pseudonymity of
     * a scanned card and shows the card number alone.
     *
     * @return true when the holder's name may be displayed
     */
    public boolean fidelityShowHolderName() {
        LOGGER.info("Entering method fidelityShowHolderName");
        LOGGER.info("Exiting method fidelityShowHolderName");
        return boolValue("fidelity.show-holder-name");
    }

    /**
     * The range of account-customer numbers the register accepts for a credit
     * settlement (BO-03-06-52), as a regular expression; empty when any number
     * is accepted and none is recognised at scan.
     *
     * @return the account-number regex, possibly empty
     */
    public String customerAccountPattern() {
        LOGGER.info("Entering method customerAccountPattern");
        LOGGER.info("Exiting method customerAccountPattern");
        return value("customer.account-pattern");
    }

    /**
     * The recognition pattern of a loyalty card at scan (BO-03-06-54); empty
     * when the {@code scan.pattern.fidelity} deployment property applies.
     *
     * @return the card recognition regex, possibly empty
     */
    public String fidelityCardPattern() {
        LOGGER.info("Entering method fidelityCardPattern");
        LOGGER.info("Exiting method fidelityCardPattern");
        return value("fidelity.card-pattern");
    }

    /**
     * Whether card-linked personal promotions are active (BO-10-03-22): the
     * loyalty card is sent to the promotion engine, which reads the holder's
     * eligible personal offers and can trigger them. Disabled, the basket is
     * priced anonymously and no personal promotion is triggered.
     *
     * @return true when card-linked promotions are active
     */
    public boolean fidelityJvPromotionEnabled() {
        LOGGER.info("Entering method fidelityJvPromotionEnabled");
        LOGGER.info("Exiting method fidelityJvPromotionEnabled");
        return boolValue("fidelity.jv-promotion-enabled");
    }

    /**
     * The supervisor barcode's PREFIX (BO-10-02-32).
     *
     * <p>Blank is the absence of a prefix and the behaviour every register had
     * before it existed: the supervisor code alone identifies. Administered,
     * the prefix is what a barcode carries and a keyboard does not, which is
     * the whole point of the parameter.
     *
     * @return the administered prefix, blank when there is none
     */
    public String supervisorBadgePrefix() {
        LOGGER.info("Entering method supervisorBadgePrefix");
        String prefix = value("scan.supervisor-prefix");
        LOGGER.info("Exiting method supervisorBadgePrefix");
        return prefix;
    }

    /**
     * Whether an article may be rung while a settlement is under way
     * (BO-10-02-15).
     *
     * @return true when the sale accepts articles during the settlement
     */
    public boolean saleAllowedDuringPayment() {
        LOGGER.info("Entering method saleAllowedDuringPayment");
        LOGGER.info("Exiting method saleAllowedDuringPayment");
        return boolValue("sale.allow-during-payment");
    }

    /**
     * What the register does when the idle delay expires (BO-10-02-28), read as
     * administered and resolved by the caller — {@code CLOSE} or anything else,
     * which means the lock.
     *
     * @return the administered action
     */
    public String idleAction() {
        LOGGER.info("Entering method idleAction");
        String action = value("auth.idle-action");
        LOGGER.info("Exiting method idleAction");
        return action;
    }

    /**
     * Whether the article's SECOND CODE is printed under its ticket line
     * (BO-10-02-20).
     *
     * @return true when the second code goes on the paper
     */
    public boolean secondCodePrinted() {
        LOGGER.info("Entering method secondCodePrinted");
        LOGGER.info("Exiting method secondCodePrinted");
        return boolValue("ticket.second-code-print");
    }

    /**
     * Whether the enter key opens the settlement on an empty buffer
     * (BO-10-02-13).
     *
     * @return true when enter alone is the TOTAL key
     */
    public boolean enterAsTotal() {
        LOGGER.info("Entering method enterAsTotal");
        LOGGER.info("Exiting method enterAsTotal");
        return boolValue("keyboard.enter-as-total");
    }

    /**
     * The TEMPLATE CODE naming the administered loyalty zone for this sale's
     * case, or null when the store administered none (BO-10-03-05).
     *
     * <p>Four cases and not one: a card was presented or not, the programme
     * answered or not. A fifth key takes precedence over the holder-online one
     * when the advantages are grouped by category (BO-10-03-21), because that
     * is the only case where a grouped layout has anything to group.
     *
     * <p>A blank key is the absence of a template and not a template named by
     * the empty string: the register then prints its built-in zone, which is
     * what every store does until a paramétreur has been there.
     *
     * @param cardPresent whether a loyalty card was attached to the sale
     * @param programmeUnavailable whether the loyalty service failed to answer
     * @param groupedByCategory whether the advantages are grouped by category
     * @return the administered template code, or null when there is none
     */
    public String fidelityTemplateCode(boolean cardPresent,
            boolean programmeUnavailable, boolean groupedByCategory) {
        LOGGER.info("Entering method fidelityTemplateCode");
        if (cardPresent && !programmeUnavailable && groupedByCategory) {
            String grouped = trimmedOrNull(value("fidelity.template-grouped"));
            if (grouped != null) {
                LOGGER.info("Exiting method fidelityTemplateCode");
                return grouped;
            }
        }
        String key;
        if (cardPresent) {
            key = programmeUnavailable ? "fidelity.template-offline" : "fidelity.template-online";
        } else {
            key = programmeUnavailable
                    ? "fidelity.template-non-holder-offline"
                    : "fidelity.template-non-holder-online";
        }
        String code = trimmedOrNull(value(key));
        LOGGER.info("Exiting method fidelityTemplateCode");
        return code;
    }

    /**
     * Reads an administered value as a template code, a blank one meaning none.
     *
     * <p>Never handed a null: every caller passes a CATALOGUED key, and a
     * catalogued key whose row is missing or empty answers its default value,
     * which for these five is the empty string.
     *
     * @param raw the administered value, blank when nothing was administered
     * @return the trimmed value, or null when there is nothing to read
     */
    private String trimmedOrNull(String raw) {
        return raw.isBlank() ? null : raw.trim();
    }

    /**
     * The base URL of the loyalty service this register calls (BO-11-04-04);
     * empty when the {@code pos.fid.url} deployment property applies.
     *
     * @return the loyalty service base URL, possibly empty
     */
    public String fidelityUrl() {
        LOGGER.info("Entering method fidelityUrl");
        LOGGER.info("Exiting method fidelityUrl");
        return value("fidelity.url");
    }

    /**
     * The Basic-auth user of the register's machine account on the loyalty
     * service (BO-11-04-04); empty when the deployment property applies. The
     * password is NOT administrable and stays a deployment secret.
     *
     * @return the loyalty machine-account user, possibly empty
     */
    public String fidelityUser() {
        LOGGER.info("Entering method fidelityUser");
        LOGGER.info("Exiting method fidelityUser");
        return value("fidelity.user");
    }

    /**
     * The theme the register's screens take in training mode (BO-10-07-08):
     * the school screen is then visibly another screen than the sale one.
     * Blank, training keeps the ordinary theme resolution.
     *
     * @return the training theme name, or an empty string when none
     */
    public String trainingTheme() {
        LOGGER.info("Entering method trainingTheme");
        LOGGER.info("Exiting method trainingTheme");
        return value("training.theme");
    }

    /**
     * Whether the digital-receipt QR code is shown on the customer display at
     * the end of a transaction (BO-10-07-02): disabled, no QR code is shown.
     *
     * @return true when the customer-display QR code is shown
     */
    public boolean customerQrEnabled() {
        LOGGER.info("Entering method customerQrEnabled");
        LOGGER.info("Exiting method customerQrEnabled");
        return boolValue("customer.qr-enabled");
    }

    /**
     * Whether a scanned employee badge is honoured at the lock and endorsement
     * screens (BO-10-02-29/30): disabled, a scanned badge is ignored and the
     * operator must key an identifier by hand.
     *
     * @return true when badge scanning takes the post or endorses
     */
    public boolean badgeScanEnabled() {
        LOGGER.info("Entering method badgeScanEnabled");
        LOGGER.info("Exiting method badgeScanEnabled");
        return boolValue("auth.badge-scan-enabled");
    }

    /**
     * The pre-filled opening float shown on the session-opening form
     * (BO-03-02-41), administered by store and inherited by echelon like any
     * catalog key. The cashier may still correct it before opening.
     *
     * @return the default opening float, as administered text
     */
    public String defaultOpeningFloat() {
        LOGGER.info("Entering method defaultOpeningFloat");
        LOGGER.info("Exiting method defaultOpeningFloat");
        return value("cash.default-opening-float");
    }

    /**
     * Whether launching the Z closing opens the cash drawer (BO-10-02-26):
     * disabled, the drawer stays shut and the count is done drawer-closed.
     *
     * @return true when the close start opens the drawer
     */
    public boolean drawerOpenOnSessionClose() {
        LOGGER.info("Entering method drawerOpenOnSessionClose");
        LOGGER.info("Exiting method drawerOpenOnSessionClose");
        return boolValue("drawer.open-on-session-close");
    }

    /**
     * Whether the per-rate VAT ventilation is printed on the sale and refund
     * tickets (BO-10-06-04): disabled, no VAT breakdown line is printed.
     *
     * @return true when the VAT breakdown is printed
     */
    public boolean vatBreakdownEnabled() {
        LOGGER.info("Entering method vatBreakdownEnabled");
        LOGGER.info("Exiting method vatBreakdownEnabled");
        return boolValue("ticket.vat-breakdown-enabled");
    }

    /**
     * Whether the external imfid loyalty service is queried at all
     * (BO-10-03-07): disabled, the register behaves as if no loyalty service
     * were configured even when its URL is set — the operational off switch a
     * store keeps beside the credentials.
     *
     * @return true when the external loyalty service is active
     */
    public boolean fidelityExternalEnabled() {
        LOGGER.info("Entering method fidelityExternalEnabled");
        LOGGER.info("Exiting method fidelityExternalEnabled");
        return boolValue("fidelity.external-enabled");
    }

    /**
     * The tenders a cash movement may concern (BO-03-02-20), parsed from the
     * semicolon-separated administered list; blank entries are dropped, and an
     * empty setting yields an empty list — the movement then always concerns the
     * cash, exactly as before the option existed. Never null.
     *
     * @return the administered movement tenders, in order
     */
    public List<String> cashMovementTenders() {
        LOGGER.info("Entering method cashMovementTenders");
        String raw = value("cash.movement-tenders");
        if (raw.isBlank()) {
            LOGGER.info("Exiting method cashMovementTenders");
            return List.of();
        }
        List<String> tenders = new ArrayList<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                tenders.add(trimmed);
            }
        }
        LOGGER.info("Exiting method cashMovementTenders");
        return tenders;
    }

    /**
     * The amount above which a cash movement (withdrawal, deposit, expense,
     * customer down-payment, cash-count declaration) requires a manager
     * endorsement (BO-04-01-44). Administered through the SETTINGS domain, so a
     * store node can raise or lower it without redeploying a register.
     *
     * @return the endorsement threshold, in euros
     */
    public BigDecimal cashMovementEndorsementThreshold() {
        LOGGER.info("Entering method cashMovementEndorsementThreshold");
        LOGGER.info("Exiting method cashMovementEndorsementThreshold");
        return bigDecimalValue("cash.movement-endorsement-threshold");
    }

    /**
     * The largest discount, in euros, a cashier may take off ONE line
     * (BO-03-07-04).
     *
     * <p>The percentage cap and this one are independent guards: a shop can
     * allow 100 % and still forbid handing back more than ten euros on a line.
     * Zero means no euro ceiling at all, which is the default — a register that
     * suddenly started refusing remises because a cap was introduced would be a
     * checkout outage.
     *
     * @return the ceiling in euros, zero when none is administered
     */
    public BigDecimal lineMaxDiscountAmount() {
        LOGGER.info("Entering method lineMaxDiscountAmount");
        LOGGER.info("Exiting method lineMaxDiscountAmount");
        return bigDecimalValue("discount.line-max-amount");
    }

    /**
     * The largest discount, in euros, a cashier may take off the WHOLE ticket
     * (BO-03-07-04).
     *
     * @return the ceiling in euros, zero when none is administered
     */
    public BigDecimal globalMaxDiscountAmount() {
        LOGGER.info("Entering method globalMaxDiscountAmount");
        LOGGER.info("Exiting method globalMaxDiscountAmount");
        return bigDecimalValue("discount.global-max-amount");
    }

    /**
     * The number of group touches shown per page on the SAISIE DIRECTE grid
     * (BO-03-01-06): a value below one is treated as unset and the catalog
     * default applies, so the register never renders a zero-tile page.
     *
     * @return the strictly positive page size
     */
    public int touchGroupsPerPage() {
        LOGGER.info("Entering method touchGroupsPerPage");
        int configured = intValue("touch.groups-per-page");
        LOGGER.info("Exiting method touchGroupsPerPage");
        return configured < 1 ? Integer.parseInt(def("touch.groups-per-page").defaultValue()) : configured;
    }

    /**
     * The group-touch display order (BO-03-01-10/11/13): the administered value
     * normalized to one of {@code ALPHA}, {@code CUSTOM} or {@code VOLUME}, any
     * other value (including an unexpected one) falling back to {@code ALPHA}.
     *
     * @return the normalized order mode, never null
     */
    public String touchDisplayOrder() {
        LOGGER.info("Entering method touchDisplayOrder");
        String raw = value("touch.display-order").trim().toUpperCase();
        if (raw.equals("CUSTOM") || raw.equals("VOLUME")) {
            LOGGER.info("Exiting method touchDisplayOrder");
            return raw;
        }
        LOGGER.info("Exiting method touchDisplayOrder");
        return "ALPHA";
    }

    /**
     * The reasons offered to the cashier for a cash movement (BO-04-01-44),
     * parsed from the semicolon-separated administered list; blank entries are
     * dropped, and an empty or blank setting yields an empty list (free reason
     * entry). Never null.
     *
     * @return the authorized movement reasons, in catalog order
     */
    public List<String> cashMovementReasons() {
        LOGGER.info("Entering method cashMovementReasons");
        // value() never returns null for a catalog key: an absent row falls to
        // the catalog default, which is a non-null string.
        String raw = value("cash.movement-reasons");
        if (raw.isBlank()) {
            LOGGER.info("Exiting method cashMovementReasons");
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                reasons.add(trimmed);
            }
        }
        LOGGER.info("Exiting method cashMovementReasons");
        return reasons;
    }

    /**
     * Whether the register offers the end-of-transaction printing choice
     * (LC-08-03-01): enabled, the cashier picks which documents are printed
     * and the forced rules below apply on top of that choice; disabled, the
     * register prints exactly as it did before the option existed.
     *
     * @return true when conditional printing is active
     */
    public boolean printConditionalEnabled() {
        LOGGER.info("Entering method printConditionalEnabled");
        LOGGER.info("Exiting method printConditionalEnabled");
        return boolValue("print.conditional-enabled");
    }

    /**
     * The documents printed WHATEVER the cashier chooses (LC-08-03-07),
     * parsed from the semicolon-separated administered list and normalized to
     * upper case; blank entries are dropped and a blank setting yields an empty
     * list (the cashier's choice then applies alone). Never null.
     *
     * @return the forced document keys, in administered order
     */
    public List<String> printForcedDocuments() {
        LOGGER.info("Entering method printForcedDocuments");
        // value() never returns null for a catalog key: an absent row falls to
        // the catalog default, which is a non-null string.
        String raw = value("print.forced-documents");
        if (raw.isBlank()) {
            LOGGER.info("Exiting method printForcedDocuments");
            return List.of();
        }
        List<String> documents = new ArrayList<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim().toUpperCase();
            if (!trimmed.isEmpty()) {
                documents.add(trimmed);
            }
        }
        LOGGER.info("Exiting method printForcedDocuments");
        return documents;
    }

    /**
     * Whether a sale ticket carrying an article under the legal conformity
     * guarantee is printed whatever the cashier chose (LC-08-03-09).
     *
     * @return true when the GLC ticket is forced
     */
    public boolean printForceTicketGlc() {
        LOGGER.info("Entering method printForceTicketGlc");
        LOGGER.info("Exiting method printForceTicketGlc");
        return boolValue("print.force-ticket-glc");
    }

    /**
     * Whether the card receipt of a refund — a "credit" card transaction —
     * is printed whatever the cashier chose (LC-08-03-10).
     *
     * @return true when the credit card receipt is forced
     */
    public boolean printForceCardCredit() {
        LOGGER.info("Entering method printForceCardCredit");
        LOGGER.info("Exiting method printForceCardCredit");
        return boolValue("print.force-card-credit");
    }

    /**
     * Whether a card receipt asking for the customer's signature is printed
     * whatever the cashier chose (LC-08-03-11).
     *
     * @return true when the signature card receipt is forced
     */
    public boolean printForceCardSignature() {
        LOGGER.info("Entering method printForceCardSignature");
        LOGGER.info("Exiting method printForceCardSignature");
        return boolValue("print.force-card-signature");
    }

    /**
     * Whether the card receipt of a not-completed transaction (TNA), carrying
     * the "abandon débit" mention, is printed (LC-08-03-12).
     *
     * @return true when the TNA card receipt is forced
     */
    public boolean printForceCardTna() {
        LOGGER.info("Entering method printForceCardTna");
        LOGGER.info("Exiting method printForceCardTna");
        return boolValue("print.force-card-tna");
    }

    /**
     * The administered customer-creation mask of the invoice screen
     * (LC-08-04-10), as the raw semicolon list; the invoice screen turns it into
     * typed fields.
     *
     * @return the administered list, never null
     */
    public String invoiceCustomerFields() {
        LOGGER.info("Entering method invoiceCustomerFields");
        LOGGER.info("Exiting method invoiceCustomerFields");
        return value("invoice.customer-fields");
    }

    /**
     * The document kinds the back office activated for the registers
     * (LC-08-04-04), as the raw semicolon list of enum names; the invoice screen
     * turns it into the list of kinds it offers.
     *
     * @return the administered list, never null
     */
    public String invoiceDocumentTypes() {
        LOGGER.info("Entering method invoiceDocumentTypes");
        LOGGER.info("Exiting method invoiceDocumentTypes");
        return value("invoice.document-types");
    }

    /**
     * The printer each kind of document comes out of (LC-08-04-11), as the raw
     * semicolon list of {@code TYPE:IMPRIMANTE} pairs; the invoice flow turns it
     * into the target of the kind being issued.
     *
     * @return the administered list, never null
     */
    public String invoiceDocumentOutput() {
        LOGGER.info("Entering method invoiceDocumentOutput");
        LOGGER.info("Exiting method invoiceDocumentOutput");
        return value("invoice.document-output");
    }

    /**
     * How many lines are printed on one slip before the operator is asked for the
     * next sheet (LC-08-04-12/13). At or below zero the slip is printed whole.
     *
     * @return the administered line count
     */
    public int invoiceSlipLines() {
        LOGGER.info("Entering method invoiceSlipLines");
        LOGGER.info("Exiting method invoiceSlipLines");
        return intValue("invoice.slip-lines");
    }

    /**
     * The document emitted automatically at the end of a sale, per payment method
     * (LC-08-04-16), as the raw semicolon list of {@code REGLEMENT:TYPE} pairs.
     *
     * @return the administered list, never null
     */
    public String invoiceAutoPrint() {
        LOGGER.info("Entering method invoiceAutoPrint");
        LOGGER.info("Exiting method invoiceAutoPrint");
        return value("invoice.auto-print");
    }

    /**
     * What the register does with an expiry date carried by a GS1 code on an
     * article (LC-11-03-13).
     *
     * @return the administered level, never null
     */
    public String gs1ExpiryAlert() {
        LOGGER.info("Entering method gs1ExpiryAlert");
        LOGGER.info("Exiting method gs1ExpiryAlert");
        return value("gs1.expiry-alert");
    }

    /**
     * The restricted tenders whose eligible base is shown during the sale
     * (LC-09-01-11 to -18), as the raw semicolon list of
     * {@code CODE:ATTRIBUT:LIBELLÉ} triples.
     *
     * @return the administered list, never null
     */
    public String restrictedTenders() {
        LOGGER.info("Entering method restrictedTenders");
        LOGGER.info("Exiting method restrictedTenders");
        return value("tender.restricted");
    }

    /**
     * The abandon reasons offered to the cashier (LC-04-04-10), as the raw
     * semicolon list. Blank means the reason is not asked for.
     *
     * @return the administered list, never null
     */
    public String abandonReasons() {
        LOGGER.info("Entering method abandonReasons");
        LOGGER.info("Exiting method abandonReasons");
        return value("abandon.reasons");
    }

    /**
     * The tenders offered for a manual withdrawal (LC-12-03-02/03), as the raw
     * semicolon list of {@code CLÉ:LIBELLÉ} pairs.
     *
     * @return the administered list, never null
     */
    public String drawerWithdrawalMethods() {
        LOGGER.info("Entering method drawerWithdrawalMethods");
        LOGGER.info("Exiting method drawerWithdrawalMethods");
        return value("drawer.withdrawal-methods");
    }

    /**
     * Whether a withdrawal ticket is printed (LC-12-03-07).
     *
     * @return true when the ticket comes out
     */
    public boolean drawerWithdrawalPrint() {
        LOGGER.info("Entering method drawerWithdrawalPrint");
        LOGGER.info("Exiting method drawerWithdrawalPrint");
        return boolValue("drawer.withdrawal-print");
    }

    /**
     * The tenders offered as the source and the destination of a settlement
     * transfer (LC-12-10-02); blank means the withdrawal list applies.
     *
     * @return the administered list, never null
     */
    public String drawerTransferMethods() {
        LOGGER.info("Entering method drawerTransferMethods");
        LOGGER.info("Exiting method drawerTransferMethods");
        return value("drawer.transfer-methods");
    }

    /**
     * Whether a transfer ticket is printed (LC-12-10-05).
     *
     * @return true when the ticket comes out
     */
    public boolean drawerTransferPrint() {
        LOGGER.info("Entering method drawerTransferPrint");
        LOGGER.info("Exiting method drawerTransferPrint");
        return boolValue("drawer.transfer-print");
    }

    /**
     * What the register does when a partial settlement is already registered and
     * the ticket is abandoned (LC-04-04-06 to -09): {@code CONFIRM} or
     * {@code BLOCK}.
     *
     * @return the administered behaviour, never null
     */
    public String abandonPartialPayment() {
        LOGGER.info("Entering method abandonPartialPayment");
        LOGGER.info("Exiting method abandonPartialPayment");
        return value("abandon.partial-payment");
    }

    /**
     * When the abandon ticket is printed (LC-04-04-12): {@code NEVER},
     * {@code ALWAYS} or {@code ON_DEMAND}.
     *
     * @return the administered rule, never null
     */
    public String abandonPrint() {
        LOGGER.info("Entering method abandonPrint");
        LOGGER.info("Exiting method abandonPrint");
        return value("abandon.print");
    }

    /**
     * Whether the abandon ticket lists the articles of the abandoned sale
     * (LC-04-04-12).
     *
     * @return true when the article detail is printed
     */
    public boolean abandonPrintDetail() {
        LOGGER.info("Entering method abandonPrintDetail");
        LOGGER.info("Exiting method abandonPrintDetail");
        return boolValue("abandon.print-detail");
    }

    /**
     * How many days before an expiry date it counts as near (LC-11-03-13).
     *
     * @return the administered number of days
     */
    public int gs1ExpiryWarnDays() {
        LOGGER.info("Entering method gs1ExpiryWarnDays");
        LOGGER.info("Exiting method gs1ExpiryWarnDays");
        return intValue("gs1.expiry-warn-days");
    }

    /**
     * What the register does with an expiry date carried by a GS1 coupon
     * (LC-11-03-13).
     *
     * @return the administered level, never null
     */
    public String gs1CouponExpiryAlert() {
        LOGGER.info("Entering method gs1CouponExpiryAlert");
        LOGGER.info("Exiting method gs1CouponExpiryAlert");
        return value("gs1.coupon-expiry-alert");
    }

    /**
     * The settlement type of a GS1 gift document, by issuer prefix (LC-11-03-05),
     * as the raw semicolon list of {@code PREFIXE:CODE_TYPE} pairs.
     *
     * @return the administered list, never null
     */
    public String gs1GiftCouponTypes() {
        LOGGER.info("Entering method gs1GiftCouponTypes");
        LOGGER.info("Exiting method gs1GiftCouponTypes");
        return value("gs1.gdti-coupon-types");
    }

    /**
     * The settlement type of a GS1 coupon (LC-11-03-06).
     *
     * @return the administered coupon-type code, never null
     */
    public String gs1CouponType() {
        LOGGER.info("Entering method gs1CouponType");
        LOGGER.info("Exiting method gs1CouponType");
        return value("gs1.gcn-coupon-type");
    }

    /**
     * The order the articles are printed in on the sale ticket (LC-08-01-07), as
     * administered. The printing rule normalizes it — an unknown value falls back
     * to the entry order there — so this stays a plain read.
     *
     * @return the administered order, never null
     */
    public String ticketLineOrder() {
        LOGGER.info("Entering method ticketLineOrder");
        LOGGER.info("Exiting method ticketLineOrder");
        return value("ticket.line-order");
    }

    /**
     * The SYMBOL printed beside an amount on the register's documents
     * (BO-10-07-05).
     *
     * @return the administered symbol, blank when the shop prints bare amounts
     */
    public String currencySymbol() {
        LOGGER.info("Entering method currencySymbol");
        String symbol = value("currency.symbol");
        LOGGER.info("Exiting method currencySymbol");
        return symbol;
    }

    /**
     * Whether the register prints the currency symbol at all (BO-10-07-04).
     *
     * @return true when amounts carry the symbol
     */
    public boolean currencySymbolPrinted() {
        LOGGER.info("Entering method currencySymbolPrinted");
        LOGGER.info("Exiting method currencySymbolPrinted");
        return boolValue("currency.symbol-print");
    }

    /**
     * The SIDE of the amount the symbol is printed on (BO-10-07-06), read as
     * administered and resolved by the caller — {@code LEFT} or anything else,
     * which means the right.
     *
     * @return the administered position
     */
    public String currencySymbolPosition() {
        LOGGER.info("Entering method currencySymbolPosition");
        String position = value("currency.symbol-position");
        LOGGER.info("Exiting method currencySymbolPosition");
        return position;
    }

    /**
     * How the ticket travels in the e-mail (LC-08-02-07/08), as administered. The
     * mail service normalizes it — an unknown value falls back to the message body
     * there — so this stays a plain read.
     *
     * @return the administered form, never null
     */
    public String ticketEmailFormat() {
        LOGGER.info("Entering method ticketEmailFormat");
        LOGGER.info("Exiting method ticketEmailFormat");
        return value("ticket.email-format");
    }

    /**
     * Whether the address read from the customer referential may be corrected at the
     * register before the receipt is sent (LC-08-02-10): disabled, a known address is
     * sent as it stands.
     *
     * @return true when the retrieved address is editable
     */
    public boolean ticketEmailEditable() {
        LOGGER.info("Entering method ticketEmailEditable");
        LOGGER.info("Exiting method ticketEmailEditable");
        return boolValue("ticket.email-editable");
    }

    /**
     * The administered value of a data-retention parameter — a number of days
     * for an age-based policy, or a number of items to keep for a count-based
     * policy (BO-10-08-03 to -26).
     *
     * <p>Zero means UNLIMITED retention and is the delivered default of every
     * retention key: a purge only ever acts on an explicit decision, so shipping
     * the feature on an existing base destroys nothing nobody asked to destroy. A
     * corrupt or absent row also resolves to zero through {@link #intValue}, so an
     * unreadable value can never silently start deleting.
     *
     * @param key the retention catalog key
     * @return the administered integer, or 0 (unlimited) when absent or corrupt
     */
    public int retentionSetting(String key) {
        LOGGER.info("Entering method retentionSetting with key: " + key);
        LOGGER.info("Exiting method retentionSetting");
        return intValue(key);
    }

    /**
     * The float mode (EXP-95), which also fixes the period of the CASHIER's own
     * financial reset (R3): {@code FIXED} (counted every evening, the cashier's
     * Z is daily — the default), {@code FLOATING_WEEK_MONTH} (real count and
     * cashier reset at the end of the week and on the last day of the month) or
     * {@code FLOATING_MONTH} (monthly only, a rare case). {@code FLOATING} is
     * accepted as the former spelling of {@code FLOATING_WEEK_MONTH}. Aligned on
     * the register's catalog, since the chaining of the cashier Z reads it.
     *
     * @return the administered mode, never null
     */
    public String floatMode() {
        LOGGER.info("Entering method floatMode");
        LOGGER.info("Exiting method floatMode");
        return value("cash.float-mode");
    }

    /**
     * Whether the shop runs on a FLOATING float, whichever reset period the mode
     * names (R3): read as a prefix because the two floating modes differ only in
     * how often the cashier is counted, and the financial state only needs to
     * know whether the cashier Z skips the day.
     *
     * @return true when the float floats
     */
    public boolean floatingFloat() {
        LOGGER.info("Entering method floatingFloat");
        String mode = floatMode();
        LOGGER.info("Exiting method floatingFloat");
        // floatMode() reads value("cash.float-mode"), a catalog key whose default
        // is FIXED, so it never yields null here.
        return mode.startsWith("FLOATING");
    }

    /**
     * The weekdays the shop never trades (R6), parsed from the semicolon-separated
     * list of {@link DayOfWeek} names; blank and unparsable entries are dropped,
     * and a blank setting yields an empty set (every day worked). Never null.
     *
     * @return the closed weekdays, in administered order
     */
    public Set<DayOfWeek> financialClosedWeekdays() {
        LOGGER.info("Entering method financialClosedWeekdays");
        Set<DayOfWeek> days = new LinkedHashSet<>();
        for (String part : value("financial.closed-weekdays").split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                days.add(DayOfWeek.valueOf(trimmed.toUpperCase()));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Ignoring unparsable closed weekday: " + trimmed);
            }
        }
        LOGGER.info("Exiting method financialClosedWeekdays");
        return days;
    }

    /**
     * The exceptional closure dates (R6), parsed from the semicolon-separated
     * list of {@code AAAA-MM-JJ} values; blank and unparsable entries are dropped,
     * and a blank setting yields an empty set. Never null.
     *
     * @return the closure dates, in administered order
     */
    public Set<LocalDate> financialClosedDates() {
        LOGGER.info("Entering method financialClosedDates");
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (String part : value("financial.closed-dates").split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                dates.add(LocalDate.parse(trimmed));
            } catch (RuntimeException e) {
                LOGGER.warn("Ignoring unparsable closed date: " + trimmed);
            }
        }
        LOGGER.info("Exiting method financialClosedDates");
        return dates;
    }

    /**
     * The width in minutes of an hourly-turnover slot (R17): 15, 30 or 60.
     * Resolves to the catalog default when the row is absent or corrupt.
     *
     * @return the slot width in minutes
     */
    public int financialHourlySlotMinutes() {
        LOGGER.info("Entering method financialHourlySlotMinutes");
        LOGGER.info("Exiting method financialHourlySlotMinutes");
        return intValue("financial.hourly-slot-minutes");
    }

    /**
     * The nomenclature level the turnover-by-nomenclature section is restituted at
     * (R16): 0 being the root. Resolves to the catalog default when absent or
     * corrupt.
     *
     * @return the restitution level
     */
    public int financialNomenclatureLevel() {
        LOGGER.info("Entering method financialNomenclatureLevel");
        LOGGER.info("Exiting method financialNomenclatureLevel");
        return intValue("financial.nomenclature-level");
    }

    /**
     * The sector of each rank-0 nomenclature node (R16), parsed from the
     * semicolon-separated list of {@code code>SECTOR} pairs; blank and malformed
     * entries are dropped, and the last value wins on a repeated code. Never null.
     *
     * @return the node-code to sector map, in administered order
     */
    public Map<String, String> financialSectors() {
        LOGGER.info("Entering method financialSectors");
        Map<String, String> sectors = new LinkedHashMap<>();
        for (String part : value("financial.sectors").split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int sep = trimmed.indexOf('>');
            if (sep <= 0 || sep == trimmed.length() - 1) {
                LOGGER.warn("Ignoring malformed sector mapping: " + trimmed);
                continue;
            }
            sectors.put(trimmed.substring(0, sep).trim(), trimmed.substring(sep + 1).trim());
        }
        LOGGER.info("Exiting method financialSectors");
        return sectors;
    }

    /**
     * The depth of the best-sellers ranking (R19). Resolves to the catalog
     * default when the row is absent or corrupt.
     *
     * @return the number of best sellers kept
     */
    public int financialBestSellersCount() {
        LOGGER.info("Entering method financialBestSellersCount");
        LOGGER.info("Exiting method financialBestSellersCount");
        return intValue("financial.best-sellers-count");
    }

    /**
     * Whether the end-of-period Z documents are emitted automatically at close
     * (R4). Disabled, only the screen's manual emission produces a Z.
     *
     * @return true when automatic emission is active
     */
    public boolean financialAutoEmitEnabled() {
        LOGGER.info("Entering method financialAutoEmitEnabled");
        LOGGER.info("Exiting method financialAutoEmitEnabled");
        return boolValue("financial.auto-emit-enabled");
    }

    /**
     * Whether family headings print in alphabetical order (BO-03-03-17).
     *
     * <p>Read only when the articles are grouped by family: with any other
     * print order there is no heading to order.
     *
     * @return true when the family headings are ordered alphabetically
     */
    public boolean ticketFamilyAlphabetical() {
        LOGGER.info("Entering method ticketFamilyAlphabetical");
        LOGGER.info("Exiting method ticketFamilyAlphabetical");
        return boolValue("ticket.family-alphabetical");
    }

    /**
     * The article attribute the receipt lines are sorted or separated on
     * (BO-03-03-18).
     *
     * @return the attribute code, blank when no attribute sorting is wanted
     */
    public String ticketLineAttribute() {
        LOGGER.info("Entering method ticketLineAttribute");
        LOGGER.info("Exiting method ticketLineAttribute");
        return value("ticket.line-attribute");
    }

    /**
     * Where the lines carrying the sorting attribute are isolated
     * (BO-03-03-18), as administered. The renderer normalizes it, so this stays
     * a plain read.
     *
     * @return NONE, START or END as administered, never null
     */
    public String ticketLineAttributeIsolate() {
        LOGGER.info("Entering method ticketLineAttributeIsolate");
        LOGGER.info("Exiting method ticketLineAttributeIsolate");
        return value("ticket.line-attribute-isolate");
    }

    /**
     * Whether a line's discount, rebate or forced price prints under its
     * article (BO-03-03-22).
     *
     * @return true when the line-level discounts are printed
     */
    public boolean ticketLineDiscountPrint() {
        LOGGER.info("Entering method ticketLineDiscountPrint");
        LOGGER.info("Exiting method ticketLineDiscountPrint");
        return boolValue("ticket.line-discount-print");
    }

    /**
     * Whether the cumulated immediate discounts print under the totals
     * (BO-03-03-24).
     *
     * @return true when the immediate-discount total is printed
     */
    public boolean ticketImmediateDiscountTotal() {
        LOGGER.info("Entering method ticketImmediateDiscountTotal");
        LOGGER.info("Exiting method ticketImmediateDiscountTotal");
        return boolValue("ticket.immediate-discount-total");
    }

    /**
     * The width the store logo is printed at, in thermal-head dots
     * (BO-03-03-14).
     *
     * <p>Bounded here rather than at the printer: a value a paramétreur mistyped
     * must cost the default header and never a logo wider than the paper, which
     * the head would clip mid-raster. Eight dots is one packed byte, the
     * smallest width that can be packed at all; 576 dots is an 80 mm roll edge
     * to edge.
     *
     * @return the print width in dots, always between 8 and 576
     */
    public int logoWidthDots() {
        LOGGER.info("Entering method logoWidthDots");
        int dots = intValue("print.logo-width-dots");
        LOGGER.info("Exiting method logoWidthDots");
        return dots < 8 || dots > 576 ? 384 : dots;
    }

    /**
     * Whether the credit note states its end of validity (BO-03-03-08).
     *
     * @return true when the expiry date is printed on the voucher
     */
    public boolean creditNotePrintExpiry() {
        LOGGER.info("Entering method creditNotePrintExpiry");
        LOGGER.info("Exiting method creditNotePrintExpiry");
        return boolValue("credit-note.print-expiry");
    }

    /**
     * Whether the receipt names the loyalty card it was attached to
     * (BO-03-03-32).
     *
     * @return true when the card mention and number are printed
     */
    public boolean fidelityPrintCardNumber() {
        LOGGER.info("Entering method fidelityPrintCardNumber");
        LOGGER.info("Exiting method fidelityPrintCardNumber");
        return boolValue("fidelity.print-card-number");
    }

    /**
     * Whether the receipt carries the card's balance zone (BO-03-03-31,
     * BO-03-03-34).
     *
     * @return true when the balances are printed
     */
    public boolean fidelityPrintBalances() {
        LOGGER.info("Entering method fidelityPrintBalances");
        LOGGER.info("Exiting method fidelityPrintBalances");
        return boolValue("fidelity.print-balances");
    }

    /**
     * How the advantage lines are grouped under the day's earn (BO-03-03-25,
     * BO-03-03-33), as administered. The printer normalizes it, so this stays a
     * plain read.
     *
     * @return TYPE, CATEGORY or NONE as administered, never null
     */
    public String fidelityAdvantageGrouping() {
        LOGGER.info("Entering method fidelityAdvantageGrouping");
        LOGGER.info("Exiting method fidelityAdvantageGrouping");
        return value("fidelity.advantage-grouping");
    }

    /**
     * The message printed for a customer who presented no loyalty card
     * (BO-03-03-28).
     *
     * <p>Distinct from {@link #fidelityOfflineMessageNoCard()}, which answers
     * for a non-holder the service could not be asked about: this one is said
     * when the programme DID answer and the customer simply has no card.
     *
     * @return the administered text, blank when the shop says nothing
     */
    public String fidelityNonHolderMessage() {
        LOGGER.info("Entering method fidelityNonHolderMessage");
        LOGGER.info("Exiting method fidelityNonHolderMessage");
        return value("fidelity.non-holder-message");
    }
}
