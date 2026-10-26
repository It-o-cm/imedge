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
 * <p>
 * impos is a REGISTER: it receives already-RESOLVED settings from its store
 * node (imedge) and holds no echelon chain of its own — it never pulls the
 * echelon domains, so its echelon tables stay empty and the layer resolves
 * nothing there, even when a {@code pos.pdv.number} is set. Resolution on a
 * register is therefore local override &rarr; legacy {@code
 * application.properties} fallback &rarr; catalog default.
 */
@ApplicationScoped
public class PosSettingsService {

    private static final Logger LOGGER = Logger.getLogger(PosSettingsService.class);

    /** Value type of a catalog entry — drives the admin form widget. */
    public enum Type { BOOL, INT, TEXT }

    /** The shortest validation code the tax administration delivers (BO-03-04-27). */
    public static final int ATCUD_MIN_LENGTH = 8;

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
        new Def("customer.eco-voucher-eligible", Type.BOOL, "AFFICHEUR CLIENT",
                "Montant éligible aux éco-chèques sur l'afficheur client",
                "L'écran client affiche en permanence, jusqu'au paiement, le total des articles éligibles aux éco-chèques, recalculé à chaque changement du ticket. Désactivé : la mention n'apparaît pas (LC-10-01-09).",
                "false", null),
        new Def("customer.social-card-eligible", Type.BOOL, "AFFICHEUR CLIENT",
                "Montant éligible à la carte achat sociale sur l'afficheur client",
                "L'écran client affiche en permanence, jusqu'au paiement, le total des articles éligibles à la carte achat sociale, recalculé à chaque changement du ticket. Désactivé : la mention n'apparaît pas (LC-10-01-11).",
                "false", null),
        new Def("parking.print-mode", Type.TEXT, "TICKETS",
                "Impression de mise en attente : mode",
                "AUTO : reçu systématique ; REQUEST : impression à la demande dans la liste des tickets en attente (LC-04-01-02).",
                "AUTO", null),
        new Def("parking.receipt-detail", Type.BOOL, "TICKETS",
                "Détail des articles sur le reçu de mise en attente",
                "Activé : articles non annulés ; désactivé : identité, code-barres et total seuls (LC-04-01-02).",
                "true", null),
        new Def("parking.print-receipt", Type.BOOL, "TICKETS",
                "Imprimer le ticket de mise en attente",
                "Un reçu portant le numéro de reprise sort à chaque mise en attente (LC-04-01-02).",
                "true", null),
        new Def("ticket.email-format", Type.TEXT, "TICKETS",
                "Forme du ticket envoyé par email",
                "Comment le ticket voyage dans l'email : BODY (dans le corps du message, défaut), ATTACHMENT (en pièce jointe) ou BOTH (les deux). Avec logo : corps HTML avec images intégrées et alternative texte, pièce jointe HTML autonome. Sans logo : texte et pièce jointe TXT. Une valeur inconnue retombe sur BODY (LC-08-02-07/08).",
                "BODY", null),
        new Def("ticket.email-editable", Type.BOOL, "TICKETS",
                "Adresse du client fidélisé modifiable",
                "L'adresse email récupérée du référentiel client peut être corrigée en caisse avant l'envoi. Désactivé : elle est envoyée telle quelle et seul un client sans adresse connue en fait saisir une (LC-08-02-10, BO-10-07-23).",
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
        new Def("retention.barcodes-expired-unused-days", Type.INT, "RÉTENTION DES CODES-BARRES",
                "Jours de conservation des codes-barres expirés non utilisés",
                "Nombre de jours pendant lesquels un bon, un avoir ou une carte cadeau dont la validité est passée SANS avoir jamais été utilisé reste en base avant suppression, compté depuis sa date de validité. 0 : conservation illimitée (BO-10-08-13).",
                "0", null),
        new Def("retention.barcodes-expired-used-days", Type.INT, "RÉTENTION DES CODES-BARRES",
                "Jours de conservation des codes-barres expirés utilisés",
                "Nombre de jours pendant lesquels un instrument daté et épuisé reste en base après son épuisement, avant suppression. 0 : conservation illimitée (BO-10-08-14).",
                "0", null),
        new Def("retention.barcodes-undated-days", Type.INT, "RÉTENTION DES CODES-BARRES",
                "Jours de conservation des codes-barres sans date de validité",
                "Nombre de jours pendant lesquels un instrument SANS date de validité reste en base après son émission, avant suppression. 0 : conservation illimitée (BO-10-08-15).",
                "0", null),
        new Def("ticket.search-days", Type.INT, "TICKETS",
                "Profondeur de recherche d'un ticket (jours)",
                "Nombre de jours en arrière sur lesquels la caisse cherche un ticket clôturé — réimpression, échange, remboursement — avant d'abandonner la recherche. 0 ou valeur négative : la profondeur intégrée de 30 jours (BO-10-07-16).",
                "30", null),
        new Def("ticket.item-count-print", Type.TEXT, "TICKETS",
                "Impression du nombre d'articles",
                "Où le nombre d'articles de la vente est imprimé : NONE (nulle part, défaut), TOTAL_LINE (entre parenthèses sur la ligne du total à payer) ou OWN_LINE (sur une ligne à lui, sous le total). Une valeur inconnue retombe sur NONE (BO-10-07-10).",
                "NONE", null),
        new Def("ticket.unknown-article-barcode", Type.BOOL, "TICKETS",
                "Code-barres sur le ticket article inconnu",
                "Le ticket d'article inconnu porte le code lu en CODE-BARRES sous sa transcription, pour que le rayon le rescanne au lieu de le ressaisir. Désactivé : le code n'est écrit qu'en clair (BO-10-07-11).",
                "false", null),
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
        new Def("ticket.barcode-enabled", Type.BOOL, "TICKETS",
                "Code d'identification en pied de ticket",
                "Imprime le code unique du ticket en dernière position. Désactivé : aucun code d'identification, sans modifier les autres codes des documents (LC-08-01-03).",
                "true", null),
        new Def("ticket.barcode-format", Type.TEXT, "TICKETS",
                "Format du code d'identification du ticket",
                "CODE128 (1D, défaut) ou QR (2D). Le même numéro et le préfixe administré sont encodés dans les deux formats. Une valeur inconnue conserve CODE128 (LC-08-01-03).",
                "CODE128", null),
        new Def("ticket.barcode-prefix", Type.TEXT, "TICKETS",
                "Préfixe du code-barres imprimé sur le ticket",
                "Préfixe ajouté devant le numéro de ticket dans le code-barres imprimé en pied de ticket, pour que les portiques ou les bornes de jeux reconnaissent la plage qui leur est réservée. Vide : le code-barres porte le numéro de ticket seul, comme aujourd'hui (BO-03-06-55).",
                "", null),
        new Def("ticket.email-offer", Type.BOOL, "TICKETS",
                "Proposer le ticket par e-mail en fin de vente",
                "Ouvre la proposition d'envoi après la clôture d'une vente, sans envoyer automatiquement de message : l'email part vers l'adresse choisie en caisse. Désactivé : aucune proposition en fin de vente (LC-08-02-05, BO-10-07-22).", "false", null),
        new Def("cash.deposit-print", Type.BOOL, "MOUVEMENTS DE CAISSE",
                "Imprimer le ticket d'apport caisse",
                "Imprime le ticket d'apport avec caisse, numéro et nom d'opérateur et montant total (LC-12-04-04).", "true", null),
        new Def("ticket.vat-code", Type.BOOL, "TICKETS",
                "Imprimer le code du taux de TVA sur la ligne",
                "La lettre du régime de TVA de l'article sort sur sa ligne et dans le tableau de TVA (0=A, 1=B, 26=AA). Désactivé : la ligne ne porte que son prix, et le détail de TVA reste dans le tableau des totaux. Activé en France, désactivé en Belgique (BO-03-03-37).",
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
                "Documents imprimés d'office quel que soit le choix du caissier, séparés par des points-virgules : TICKET, CARTE, BON, AVOIR, FID, ENLEVEMENT, RETOUR, ACOMPTE, CARTE_PREPAYEE (justificatif de paiement par carte cadeau ou prépayée), DEMAT (ticket d'activation d'un produit dématérialisé). TICKET, CARTE et BON complètent le choix du caissier. Les autres documents, y compris l'avoir de remboursement et les deux parcours d'acompte, sortent seulement s'ils sont nommés ici lorsque le choix d'impression est activé. Vide : aucun document associé ne sort. Choix d'impression désactivé : impression habituelle. Les copies client restent disponibles par email même sans papier (LC-08-03-07, BO-03-03-01).",
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
                "Autorisation superviseur pour activer le mode dégradé",
                "L'activation du mode dégradé monétique forcé en caisse exige un aval superviseur. Désactivé : l'opérateur l'active seul. La levée a son propre paramètre (LC-07-08-03, BO-03-02-49).",
                "true", null),
        new Def("payment.degraded-forced-minutes", Type.INT, "MONÉTIQUE",
                "Durée du mode dégradé forcé (minutes)",
                "Au bout de ce délai, le mode dégradé monétique forcé en caisse se lève tout seul, et le mode dégradé activé au BackOffice cesse de s'appliquer sur chaque caisse : l'opérateur qui l'a activé ne sera pas celui qui sera encore là trois heures plus tard (LC-07-08-04, BO-03-02-48).",
                "15", null),
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
        new Def("ticket.footer-url", Type.TEXT, "MESSAGES TICKET",
                "Adresse imprimée en pied de ticket",
                "Adresse web imprimée en pied de ticket, sous le message de fin — questionnaire de satisfaction, programme de fidélité, mentions en ligne. Vide : rien n'est imprimé (BO-03-05-08).",
                "", null),
        new Def("ticket.footer-url-format", Type.TEXT, "MESSAGES TICKET",
                "Format de l'adresse en pied de ticket",
                "Comment l'adresse ci-dessus est rendue : QR (code QR, défaut), BARCODE (code-barres), TEXTE (l'adresse écrite en clair) ou AUCUN (rien n'est imprimé, même si une adresse est saisie). Une valeur inconnue retombe sur QR (BO-03-05-08).",
                "QR", null),
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
        new Def("hardware.printer.enabled", Type.BOOL, "PÉRIPHÉRIQUES",
                "Imprimante tickets activé",
                "Désactivé : le périphérique est signalé désactivé et ne bloque pas la vente (LC-13-02-09/-14).",
                "true", null),
        new Def("hardware.scanner.enabled", Type.BOOL, "PÉRIPHÉRIQUES",
                "Scanner et douchette activé",
                "Désactivé : le périphérique est signalé désactivé et ne bloque pas la vente (LC-13-02-09/-14).",
                "true", null),
        new Def("hardware.scale.enabled", Type.BOOL, "PÉRIPHÉRIQUES",
                "Balance activé",
                "Désactivé : le périphérique est signalé désactivé et ne bloque pas la vente (LC-13-02-09/-14).",
                "true", null),
        new Def("hardware.cashchanger.enabled", Type.BOOL, "PÉRIPHÉRIQUES",
                "Monnayeur activé",
                "Activé : la touche MONNAYEUR règle les espèces sur le monnayeur, qui rend la monnaie ; le montant inséré s'affiche sur les deux visus, l'espèce est prélevée vers le coffre à la fin de la vente, et les seuils signalés par le monnayeur sont annoncés à l'ouverture et après chaque vente (LC-07-15-01 à -04, LC-13-01-01).",
                "false", null),
        new Def("maintenance.verifone.enabled", Type.BOOL, "MAINTENANCE",
                "Maintenance Verifone nocturne", "Active c2g sur les deux serveurs, hors session et paiement (LC-13-04-03).", "false", null),
        new Def("maintenance.verifone.start", Type.TEXT, "MAINTENANCE",
                "Début de plage Verifone", "Heure locale HH:mm (LC-13-04-05).", "02:00", null),
        new Def("maintenance.verifone.end", Type.TEXT, "MAINTENANCE",
                "Fin de plage Verifone", "Heure locale HH:mm, après le début (LC-13-04-05).", "03:00", null),
        new Def("maintenance.verifone.random", Type.BOOL, "MAINTENANCE",
                "Heure Verifone aléatoire", "Tirage renouvelé au redémarrage et à la fin de période. Sinon décalage selon le numéro de caisse (LC-13-04-04/-05).", "true", null),
        new Def("maintenance.verifone.stagger-seconds", Type.INT, "MAINTENANCE",
                "Décalage Verifone par caisse (secondes)", "Début de plage + numéro de caisse × décalage, borné à la plage (LC-13-04-04).", "30", null),
        new Def("maintenance.restart-reasons", Type.TEXT, "MAINTENANCE",
                "Motifs de redémarrage",
                "Motifs proposés, séparés par des points-virgules. Vide : motifs traduits fournis par la caisse (LC-13-03-04).",
                "", null),
        new Def("maintenance.command.application", Type.TEXT, "MAINTENANCE",
                "Commande de relance applicative",
                "Exécutable et arguments séparés par des points-virgules. Vide : simulation, aucune commande système (LC-13-03-01).",
                "", null),
        new Def("maintenance.command.reboot", Type.TEXT, "MAINTENANCE",
                "Commande de redémarrage du poste",
                "Exécutable et arguments séparés par des points-virgules. Vide : simulation. Aval superviseur, session fermée et confirmation obligatoires (LC-13-03-05).",
                "", null),
        new Def("maintenance.command.shutdown", Type.TEXT, "MAINTENANCE",
                "Commande d’arrêt du poste",
                "Exécutable et arguments séparés par des points-virgules. Vide : simulation. Aval superviseur, session fermée et confirmation obligatoires (LC-13-03-06).",
                "", null),
        new Def("cheque.front-lines", Type.TEXT, "CHEQUE",
                "Présentation du recto chèque", "Lignes séparées par des points-virgules. Jetons montant, montant-lettres, date, heure, pdv, caisse, ticket, beneficiaire, lieu, banque, compte (LC-07-05-06).", "{montant-lettres};{montant} EUR;{beneficiaire};{lieu} {date} {heure};{pdv} / {caisse} / {ticket}", null),
        new Def("cheque.beneficiary", Type.TEXT, "CHEQUE",
                "Bénéficiaire chèque", "Bénéficiaire imprimable par le jeton {beneficiaire} (LC-07-05-07).", "", null),
        new Def("cheque.place", Type.TEXT, "CHEQUE",
                "Lieu du chèque", "Lieu imprimable par le jeton {lieu} (LC-07-05-06).", "", null),
        new Def("cheque.bank-reference", Type.TEXT, "CHEQUE",
                "Référence banque chèque", "Référence optionnelle imprimable par {banque} (LC-07-05-07).", "", null),
        new Def("cheque.account-number", Type.TEXT, "CHEQUE",
                "Compte bénéficiaire chèque", "Compte optionnel imprimable par {compte} (LC-07-05-07).", "", null),
        new Def("payment.verifone-method-map", Type.TEXT, "MONETIQUE",
                "Correspondance applications Verifone",
                "Associations D16:code=MOYEN ou D46:code=MOYEN séparées par des points-virgules. D16 connu prime, puis D46 ; sinon CARD (LC-07-07-04).",
                "D16:3F=TRD;D16:4C=TRD;D16:4D=TRD;D16:4E=TRD", null),
        new Def("payment.card-receipt-attached", Type.BOOL, "MONETIQUE",
                "Ticket monétique attaché au ticket de vente",
                "Activé : pas de coupe avant le ticket client Verifone ; le ticket commerçant reste séparé et n'est pas archivé (LC-07-04-11).",
                "false", null),
        new Def("payment.cashback-enabled", Type.BOOL, "MONETIQUE",
                "Cashback par carte bancaire",
                "Le client peut régler en carte un montant supérieur au reste dû et recevoir l'écart en espèces : le montant carte saisi au-delà du reste dû, ou la touche CASHBACK qui demande le rendu espèces voulu et calcule le montant à débiter. Désactivé : le montant carte est limité au reste dû (LC-07-04-07/08).",
                "false", null),
        new Def("payment.cashback-max", Type.TEXT, "MONETIQUE",
                "Plafond du cashback (€)",
                "Montant maximal rendu en espèces par cashback sur une transaction ; au-delà, la demande est refusée avant d'interroger le TPE. 0 : aucun plafond (LC-07-04-07/08).",
                "60.00", null),
        new Def("donation.rounding-step-cents", Type.INT, "ARRONDI SOLIDAIRE",
                "Palier du don (centimes)",
                "Palier proposé pour le don : 5, 10, 100 centimes, par exemple (LC-07-17-02).",
                "100", null),
        new Def("donation.rounding-nearest", Type.BOOL, "ARRONDI SOLIDAIRE",
                "Don au palier le plus proche",
                "Activé : palier le plus proche ; désactivé : palier supérieur. Aucun don négatif n'est ajouté (LC-07-17-02).",
                "false", null),
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
                "Création d'un client de passage en caisse",
                "L'hôte(sse) peut créer en caisse un client VOLATIL — un client de passage, sans compte ni crédit, créé pour porter une facture. Désactivé : seuls les clients en compte déjà intégrés sont utilisables (BO-10-04-01).",
                "true", null),
        new Def("customer.account-creation-blocked", Type.BOOL, "CLIENT EN CAISSE",
                "Création d'un client EN COMPTE bloquée",
                "La création d'un client en compte est refusée, en caisse comme au back-office : un client en compte entre par l'intégration de la gestion commerciale. Désactivé : la création redevient possible (BO-10-04-05, BO-02-04-12).",
                "true", null),
        new Def("customer.show-details", Type.BOOL, "CLIENT EN CAISSE",
                "Affichage des coordonnées du client",
                "Quand un client est identifié, ses coordonnées sont montrées à l'hôte(sse). Désactivé : seuls son numéro et sa raison sociale apparaissent (BO-10-04-02).",
                "true", null),
        new Def("customer.update-enabled", Type.BOOL, "CLIENT EN CAISSE",
                "Mise à jour des coordonnées en caisse",
                "L'hôte(sse) peut corriger les coordonnées du client identifié ; la correction est enregistrée en base et portée sur le document imprimé. Désactivé : la fiche est en lecture seule en caisse (BO-10-04-03/04).",
                "false", null),
        new Def("customer.show-discount", Type.BOOL, "CLIENT EN CAISSE",
                "Affichage de la remise du client en compte",
                "La remise accordée au client en compte est annoncée à l'hôte(sse) dès que le client est scanné ou saisi. Désactivé : la remise s'applique sans être annoncée (BO-10-04-08).",
                "true", null),
        new Def("customer.discount-segment", Type.TEXT, "CLIENT EN CAISSE",
                "Segment des clients en compte remises",
                "Nom du segment auquel un client en compte doit appartenir pour que sa remise s'applique. Vide : la remise de la fiche s'applique à tout client en compte qui en porte une (BO-10-04-07).",
                "", null),
        new Def("customer.credit-tender", Type.TEXT, "CLIENT EN CAISSE",
                "Moyen de paiement du crédit client",
                "Clé du moyen de paiement sous lequel le crédit client est enregistré sur le ticket et dans les états. Une clé inconnue retombe sur CREDIT (BO-10-04-09).",
                "CREDIT", null),
        new Def("customer.partial-credit-allowed", Type.BOOL, "CLIENT EN CAISSE",
                "Paiement partiel en crédit client",
                "Le crédit client peut ne régler qu'une partie du ticket, le reste étant payé autrement. Désactivé : le crédit client règle la totalité du reste dû, ou rien (BO-10-04-10).",
                "true", null),
        new Def("customer.offline-message", Type.TEXT, "CLIENT EN CAISSE",
                "Alerte des fonctions client hors ligne",
                "Message montré à l'hôte(sse) quand une fonction client est demandée alors que la caisse est autonome et que son référentiel n'est plus à jour (BO-10-04-11/12).",
                "REFERENTIEL CLIENT NON A JOUR - CREDIT REFUSE", null),
        new Def("customer.over-limit-message", Type.TEXT, "CLIENT EN CAISSE",
                "Alerte de dépassement du plafond",
                "Message montré à l'hôte(sse) quand le règlement porterait le client au-delà de son plafond de crédit. Les jetons {encours} et {plafond} y sont remplacés par les montants (BO-10-04-13).",
                "PLAFOND DEPASSE - ENCOURS {encours} / PLAFOND {plafond} - AUTORISATION REQUISE", null),
        new Def("customer.default-tax-id", Type.TEXT, "CLIENT EN CAISSE",
                "NIF par défaut",
                "Numéro fiscal présenté en caisse avant validation du ticket, que l'hôte(sse) peut remplacer par celui du client. Vide : aucun NIF n'est proposé (BO-10-04-15).",
                "999999990", null),
        new Def("customer.tax-id-required", Type.BOOL, "CLIENT EN CAISSE",
                "NIF client obligatoire pour encaisser",
                "Aucun encaissement ne commence sans NIF client : à l'entrée en paiement, la caisse ouvre d'abord le masque NIF CLIENT, NIF proposé compris, et refuse un NIF vide. Désactivé : le NIF reste facultatif (LC-08-06-01).",
                "false", null),
        new Def("customer.tax-id-pt-check", Type.BOOL, "CLIENT EN CAISSE",
                "Contrôle de validité du NIF portugais",
                "Un NIF saisi ou scanné qui n'est fait que de chiffres, précédés ou non de PT, doit compter 9 chiffres, commencer par un préfixe légal et porter la bonne clé de contrôle (modulo 11) ; sinon il est refusé. Un NIF étranger, qui contient des lettres, n'est pas contrôlé (LC-08-06-04).",
                "false", null),
        new Def("customer.tax-id-scan-pattern", Type.TEXT, "CLIENT EN CAISSE",
                "Code-barre porteur d'un NIF",
                "Expression régulière reconnaissant, au scan, le code-barre qui porte un NIF client (carte d'achat sociale, par exemple) ; le groupe entre parenthèses désigne le NIF. Le NIF scanné est enregistré dans la transaction comme s'il avait été saisi, avec le même contrôle. Vide : aucun code-barre NIF reconnu (LC-08-06-05).",
                "^(?:PT|NIF)(\\d{9})$", null),
        new Def("customer.default-tax-id-label", Type.TEXT, "CLIENT EN CAISSE",
                "Libellé du NIF par défaut",
                "Libellé imprimé à la place du numéro quand le NIF présenté est celui par défaut (BO-10-04-16).",
                "CONSUMIDOR FINAL", null),
        new Def("customer.foreign-tax-id-countries", Type.TEXT, "CLIENT EN CAISSE",
                "Pays proposés pour un NIF étranger",
                "Codes pays ISO séparés par une virgule, proposés en caisse pour la saisie d'un NIF étranger. Le NIF portugais obéit à son propre algorithme et n'est pas dans cette liste (BO-10-04-17).",
                "ES,FR,DE,IT,GB", null),
        new Def("customer.invoice-export-due-date", Type.BOOL, "CLIENT EN CAISSE",
                "Date d'échéance dans les extractions de factures",
                "Les extractions de factures clients en compte portent la date d'échéance du client (BO-10-04-19).",
                "true", null),
        new Def("customer.invoice-export-ecotax", Type.BOOL, "CLIENT EN CAISSE",
                "Éco-taxe dans les extractions de factures",
                "Les extractions de factures portent le montant d'éco-taxe associé à la transaction réglée en crédit client (BO-10-04-14), et le fichier clitva.dat des factures clients en compte une ligne d'éco-taxe après les lignes de TVA de chaque facture (BO-11-01-12).",
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
        new Def("dashboard.indicators", Type.TEXT, "SUPERVISION",
                "Indicateurs du tableau de bord",
                "Indicateurs compl\u00e9mentaires affich\u00e9s sous les cumuls du jour, s\u00e9par\u00e9s par des points-virgules et dans l'ordre d'affichage voulu : endorsements, endorsements-denied, age-checks, supervisor-calls, parked-tickets, paid-cancelled, duplicates, price-checks, training, cash-movements, offline-registers. Toute autre donnée du système s'adresse aussi : event:TYPE (événements du jour d'un type du journal, par exemple event:PRICE_MODIFIED ou event:CONTROL_ALERT), payment:CLE (règlements du jour d'un moyen de paiement), family:CODE (CA du jour d'une branche de la nomenclature), movement:TYPE (mouvements de caisse du jour d'un type). Un code inconnu est ignor\u00e9. Vide : aucun indicateur compl\u00e9mentaire, seuls les cinq cumuls fixes restent (BO-04-03-01/-03/-15).",
                "endorsements;age-checks;cash-movements;offline-registers", null),
        new Def("supervision.register-offline-minutes", Type.INT, "SUPERVISION",
                "Silence toléré d'une caisse (minutes)",
                "Nombre de minutes pendant lesquelles une caisse peut ne plus donner signe de vie avant que le back-office la déclare déconnectée. Chaque caisse vient chercher ses ordres toutes les vingt secondes, donc une valeur de trois minutes laisse passer huit tours manqués. 0 ou moins : trois minutes, une tolérance nulle déclarerait tout le monde hors ligne entre deux appels (BO-04-03-05).",
                "3", null),
        new Def("supervision.settled-calls-kept", Type.BOOL, "SUPERVISION",
                "Garder les demandes traitées à l'écran",
                "Une demande de service tranchée — déjà traitée en caisse, renvoyée en caisse ou refusée — reste affichée sur le tableau de bord, clairement marquée, jusqu'à son acquittement. Désactivé : elle disparaît de la liste dès qu'elle est tranchée (BO-04-02-03/-04/-05).",
                "true", null),
        new Def("auth.endorsement-roles", Type.TEXT, "GESTES NÉGATIFS",
                "Rôles habilités à donner un aval",
                "Rôles dont le titulaire peut avaliser un geste en caisse, séparés par des points-virgules (MANAGER, ADMIN, CASHIER). Vide ou illisible : MANAGER;ADMIN, les deux rôles qui avalisaient avant que la liste soit administrable (BO-04-02-01).",
                "MANAGER;ADMIN", null),
        new Def("supervision.unknown-item-alert", Type.BOOL, "SUPERVISION",
                "Alerter le back-office sur article inconnu",
                "Un article scanné que le référentiel ne connaît pas remonte une demande de service au tableau de bord, au même titre qu'un appel superviseur. Désactivé — le défaut — l'article inconnu n'est consultable qu'après coup au journal (BO-04-02-06).",
                "false", null),
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
                "Motifs de dépense",
                "Motifs proposés au caissier pour une DÉPENSE payée sur le tiroir, séparés par des points-virgules ; le motif est obligatoire, un motif manquant refuse la dépense. La liste ne mélange plus les motifs de prélèvement et d'apport, qui ont leurs propres écrans (BO-04-01-44).",
                "Achat de timbres;Frais de restaurant;Dépense pharmacie;Fournitures;Erreur de caisse", null),
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
        new Def("invoice.decimals-unit-ht", Type.INT, "FACTURE",
                "Décimales du prix unitaire HT",
                "Nombre de décimales du prix unitaire hors taxe sur la facture et le bon de livraison : 2 ou 3. Une autre valeur retombe sur 2 (BO-10-02-19).",
                "2", null),
        new Def("invoice.decimals-vat-base", Type.INT, "FACTURE",
                "Décimales de la base HT du tableau de TVA",
                "Nombre de décimales de la base hors taxe de chaque ligne du tableau de TVA : 2 ou 3. Une autre valeur retombe sur 2 (BO-10-02-19).",
                "2", null),
        new Def("invoice.decimals-vat-amount", Type.INT, "FACTURE",
                "Décimales du montant de TVA",
                "Nombre de décimales du montant de taxe de chaque ligne du tableau de TVA : 2 ou 3. Une autre valeur retombe sur 2 (BO-10-02-19).",
                "2", null),
        new Def("invoice.decimals-total-ht", Type.INT, "FACTURE",
                "Décimales du total HT",
                "Nombre de décimales du total hors taxe du pied de document : 2 ou 3. Une autre valeur retombe sur 2 (BO-10-02-19).",
                "2", null),
        new Def("invoice.decimals-total-ttc", Type.INT, "FACTURE",
                "Décimales du total TTC",
                "Nombre de décimales du total toutes taxes du pied de document : 2 ou 3. Une autre valeur retombe sur 2 (BO-10-02-19).",
                "2", null),
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
                "Ce qui est imprimé au dos du chèque pendant que le lecteur le tient, une ligne par point-virgule. Jetons disponibles : {date}, {heure}, {montant}, {montant-lettres}, {caisse}, {pdv}, {ticket} (LC-07-05-03). Vide : la date et l'heure puis le montant, comme avant ce paramètre (BO-03-02-43).",
                "", null),
        new Def("gs1.expiry-alert", Type.TEXT, "CODES GS1",
                "Alerte date de péremption article",
                "Ce que fait la caisse quand un code GS1 porte une date de péremption (AI 17) proche, atteinte ou dépassée : NONE (rien), INFO (message au caissier, l'article est enregistré), BLOCK (article refusé) ou SUPERVISOR (article refusé, un superviseur peut forcer son enregistrement). Vide ou inconnu : NONE (LC-11-03-13, BO-03-06-64).",
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
        new Def("touch.price-currency", Type.BOOL, "TOUCHES CAISSE",
                "Devise sur les touches articles",
                "Affiche EUR après le prix TTC sur les touches articles. Désactivé : seul le montant est affiché (LC-02-07-11).",
                "true", null),
        new Def("report.show-sales", Type.BOOL, "RAPPORT CA",
                "Ventes dans le rapport CA",
                "Tickets de vente et montant TTC vendu, retours exclus. Affichage et impression du rapport CA (LC-12-07-02).", "true", null),
        new Def("report.show-returns", Type.BOOL, "RAPPORT CA",
                "Retours dans le rapport CA",
                "Documents de retour et tickets contenant un retour ; montant TTC retourné. Affichage et impression du rapport CA (LC-12-07-02).", "true", null),
        new Def("report.show-expenses", Type.BOOL, "RAPPORT CA",
                "Dépenses dans le rapport CA",
                "Nombre et montant des dépenses de caisse. Affichage et impression du rapport CA (LC-12-07-02).", "true", null),
        new Def("report.show-cancellations", Type.BOOL, "RAPPORT CA",
                "Annulations articles dans le rapport CA",
                "Nombre de lignes annulées et montant TTC retiré. Affichage et impression du rapport CA (LC-12-07-02).", "true", null),
        new Def("report.show-abandoned", Type.BOOL, "RAPPORT CA",
                "Transactions abandonnées dans le rapport CA",
                "Nombre et montant TTC des tickets abandonnés. Affichage et impression du rapport CA (LC-12-07-02).", "true", null),
        new Def("report.show-price-overrides", Type.BOOL, "RAPPORT CA",
                "Forçages prix dans le rapport CA",
                "Nombre de lignes vendues avec prix forcé et leur montant TTC final. Affichage et impression du rapport CA (LC-12-07-02).", "true", null),
        new Def("report.show-deposits", Type.BOOL, "RAPPORT CA",
                "Apports dans le rapport CA",
                "Nombre et montant des apports au tiroir. Affichage et impression du rapport CA (LC-12-07-02).", "true", null),
        new Def("report.show-withdrawals", Type.BOOL, "RAPPORT CA",
                "Prélèvements dans le rapport CA",
                "Nombre et montant des prélèvements du tiroir. Affichage et impression du rapport CA (LC-12-07-02).", "true", null),
        new Def("report.show-tenders", Type.BOOL, "RAPPORT CA",
                "Règlements par moyen dans le rapport CA",
                "Nombre de règlements et montant encaissé par moyen de paiement. Affichage et impression du rapport CA (LC-12-07-02).", "true", null),
        new Def("touch.grid-columns", Type.INT, "TOUCHES CAISSE",
                "Colonnes de la grille articles",
                "Nombre de colonnes de saisie directe, de 2 à 6 (LC-02-07-02).", "3", null),
        new Def("touch.show-ean", Type.BOOL, "TOUCHES CAISSE",
                "EAN sur les touches articles",
                "Affiche le code EAN sous le libellé des articles dans les groupes de saisie directe, quel que soit le tri choisi (LC-02-07-08).", "false", null),
        new Def("touch.article-order", Type.TEXT, "TOUCHES CAISSE",
                "Ordre des touches articles",
                "EAN séparés par des points-virgules, dans l'ordre souhaité. Les articles non cités suivent par ordre alphabétique (LC-02-07-03).", "", null),
        new Def("touch.groups-per-page", Type.INT, "TOUCHES CAISSE",
                "Nombre de touches groupe par page",
                "Nombre de touches de groupe d'articles affichées par page sur l'écran de saisie directe. En dessous de 1 la valeur par défaut s'applique (BO-03-01-06).",
                "12", null),
        new Def("touch.display-order", Type.TEXT, "TOUCHES CAISSE",
                "Ordre des touches groupe",
                "Ordre d'affichage des touches groupe sur l'écran de saisie directe : ALPHA (alphabétique, défaut), CUSTOM (ordre personnalisé de la fiche groupe) ou VOLUME (volume de vente décroissant). Une valeur inconnue retombe sur ALPHA (BO-03-01-10/11/13).",
                "ALPHA", null),
        new Def("auth.bo-max-failures", Type.INT, "SESSION CAISSE",
                "Échecs avant verrouillage du compte back-office",
                "Nombre d'authentifications back-office erronées consécutives au bout desquelles le compte est verrouillé. 0 : aucun verrouillage, les tentatives ne sont pas comptées (BO-01-01-01).",
                "3", null),
        new Def("auth.bo-lockout-minutes", Type.INT, "SESSION CAISSE",
                "Durée du verrouillage back-office (minutes)",
                "Durée pendant laquelle un compte verrouillé pour échecs reste inaccessible. 0 ou moins : quinze minutes, parce qu'un verrouillage sans durée serait un compte perdu (BO-01-01-01).",
                "15", null),
        new Def("auth.bo-sign-in", Type.TEXT, "SESSION CAISSE",
                "Connexion au back-office",
                "Porte d'entrée du back-office à cet échelon : ANY (mot de passe ou SSO du groupement, défaut), PASSWORD (mot de passe seulement) ou SSO (SSO seulement : aucun mot de passe local n'ouvre le back-office). S'ajoute au mode de connexion de chaque compte ; une valeur inconnue retombe sur ANY (BO-01-02-01, BO-01-02-09).",
                "ANY", null),
        new Def("auth.password-expiry-days", Type.INT, "SESSION CAISSE",
                "Expiration du secret (jours)",
                "Nombre de jours au bout desquels la caisse et le back-office invitent le titulaire à changer son code. 0 : aucune expiration, le code reste valable indéfiniment. Un secret jamais changé est considéré comme échu dès qu'un délai est posé (BO-01-02-12).",
                "0", null),
        new Def("auth.idle-action", Type.TEXT, "SESSION CAISSE",
                "Sécurité sans activité",
                "Ce que fait la caisse au bout du délai d'inactivité : LOCK (verrouillage — l'opérateur reste en poste et reprend sa vente avec son seul code, défaut) ou CLOSE (fermeture de la session de travail — l'opérateur est déconnecté et une prise de poste complète est nécessaire). Une valeur inconnue retombe sur LOCK (BO-10-02-28).",
                "LOCK", null),
        new Def("auth.badge-scan-enabled", Type.BOOL, "SESSION CAISSE",
                "Prise de poste au scan du badge",
                "Le scan d'un badge déverrouille la caisse ou vise un avenant manager. Désactivé : le badge scanné est ignoré et l'opérateur saisit son identifiant à la main (BO-10-02-29/30).",
                "true", null),
        new Def("auth.password-required-on-open", Type.BOOL, "SESSION CAISSE",
                "Mot de passe à la prise de poste",
                "Le badge scanné ouvre la caisse SEUL quand ce paramètre est désactivé. Actif : le badge remplit l'identifiant et l'opérateur saisit son code (LC-01-01-03/09, BO-10-02-22).",
                "true", null),
        new Def("auth.dallas-key", Type.BOOL, "SESSION CAISSE",
                "Connexion par clé Dallas",
                "La clé Dallas (iButton) attribuée à l'opérateur à l'écran Comptes ouvre la caisse à elle seule, sans code PIN — la connexion sans mot de passe des caisses libre-service (BO-01-02-07). Désactivé : une clé lue est ignorée.",
                "false", null),
        new Def("auth.password-required-on-close", Type.BOOL, "SESSION CAISSE",
                "Mot de passe à la fermeture",
                "La déconnexion demande le code de l'opérateur. Désactivé : la touche ferme la caisse sans rien demander (LC-01-02-02/03, BO-10-02-24).",
                "false", null),
        new Def("session.close-endorsement-on-pending", Type.BOOL, "SESSION CAISSE",
                "Avenant si tickets en attente",
                "Fermer la caisse en laissant un ticket en attente non repris exige une autorisation superviseur. Désactivé : la fermeture passe avec un simple avertissement (LC-01-02-10, BO-10-02-08).",
                "true", null),
        new Def("session.print-open-receipt", Type.BOOL, "TICKETS",
                "Ticket d'ouverture de caisse",
                "Impression d'un ticket à la prise de poste : caisse, opérateur, horodatage (LC-01-01-08, BO-10-07-09).",
                "false", null),
        new Def("session.print-close-receipt", Type.BOOL, "TICKETS",
                "Ticket de fermeture de caisse",
                "Impression d'un ticket à la fermeture : caisse, opérateur, horodatage, et la mention du forçage le cas échéant (LC-01-02-11, BO-10-07-09).",
                "false", null),
        new Def("period.alert-countdown-minutes", Type.INT, "FIN DE PÉRIODE",
                "Compte à rebours de l'alerte de fin de journée (minutes)",
                "Combien de minutes avant la fin de période le back-office commence à annoncer le compte à rebours et ses alertes — caisses ouvertes, caisses déconnectées, tickets en attente. 0 ou moins : soixante minutes (BO-09-01-04, BO-09-02-02).",
                "60", null),
        new Def("period.week-end-day", Type.TEXT, "FIN DE PÉRIODE",
                "Jour de fin de semaine",
                "Jour de la semaine qui ferme la semaine, en anglais et en majuscules (MONDAY à SUNDAY). Illisible ou inconnu : SUNDAY (BO-09-01-07).",
                "SUNDAY", null),
        new Def("period.month-end-day", Type.INT, "FIN DE PÉRIODE",
                "Jour de fin de mois",
                "Quantième qui ferme le mois. 0, une valeur négative ou un quantième plus grand que le mois : le DERNIER jour du mois, seule lecture qui fonctionne en février (BO-09-01-07).",
                "0", null),
        new Def("period.year-end-date", Type.TEXT, "FIN DE PÉRIODE",
                "Date de fin d'année",
                "Jour et mois qui ferment l'année, au format jj/mm. Illisible : 31/12 (BO-09-01-07).",
                "31/12", null),
        new Def("period.reports", Type.TEXT, "FIN DE PÉRIODE",
                "Rapports édités en fin de période",
                "Codes des états édités automatiquement à la fin de la période, séparés par des points-virgules et dans l'ordre d'édition. Un code inconnu est ignoré. Vide : aucune édition automatique (BO-09-01-10, BO-09-01-11).",
                "", null),
        new Def("period.reports-format", Type.TEXT, "FIN DE PÉRIODE",
                "Format des rapports édités",
                "Format de rendu des états édités en fin de période : pdf, xlsx ou csv. Inconnu : pdf (BO-09-01-11).",
                "pdf", null),
        new Def("period.auto-close-enabled", Type.BOOL, "FIN DE PÉRIODE",
                "Fermeture automatique des caisses",
                "Les caisses restées ouvertes sont fermées d'office à l'approche de la fin de période, l'ordre étant relevé par chaque caisse à son tour de boucle. Désactivé : une caisse oubliée reste ouverte jusqu'au lendemain (LC-01-02-08, BO-09-01-06/09).",
                "false", null),
        new Def("period.end-time", Type.TEXT, "FIN DE PÉRIODE",
                "Heure de fin de période",
                "Heure de la fin de période, au format HH:mm. Une valeur illisible retombe sur la valeur par défaut (BO-09-01-06).",
                "23:30", null),
        new Def("period.close-lead-minutes", Type.INT, "FIN DE PÉRIODE",
                "Délai de fermeture avant la fin de période (minutes)",
                "Nombre de minutes avant la fin de période à partir duquel les caisses encore ouvertes sont fermées. Une caisse en pleine vente n'est pas fermée : l'ordre reste en attente et la caisse le reprend dès qu'elle est au repos (BO-09-03-07).",
                "5", null),
        new Def("period.reset-ticket-counter", Type.BOOL, "FIN DE PÉRIODE",
                "Remettre le compteur de tickets à 1",
                "Le compteur de transactions de la caisse repart à 1 à chaque fin de période. Désactivé — le défaut — il continue sans discontinuer. Seul le numéro de ticket repart : la numérotation fiscale, elle, ne compte jamais à rebours et n'est pas touchée (BO-09-03-04).",
                "false", null),
        new Def("period.pdv-actions", Type.TEXT, "FIN DE PÉRIODE",
                "Actions de la fin de période du point de vente",
                "Ce que la fin de période du POINT DE VENTE enchaîne, noms séparés par des points-virgules et dans l'ordre : CLOSE_REGISTERS (fermer les caisses restées ouvertes), SAFE_COUNT (comptage du coffre), Z_REPORT (état Z consolidé), BANK_DEPOSIT (remise en banque). Un nom inconnu est ignoré. Vide : la fin de période ne fait que ce que les clés ci-dessus décrivent, caisse par caisse (BO-03-12-02).",
                "", null),
        new Def("cash.default-opening-float", Type.TEXT, "SESSION CAISSE",
                "Fond de caisse par défaut",
                "Montant pré-rempli du fond de caisse à l'ouverture de session, que le caissier peut corriger. Administré par magasin et hérité par échelon (BO-03-02-41).",
                "150.00", null),
        new Def("drawer.open-on-session-close", Type.BOOL, "TIROIR",
                "Ouverture du tiroir à la clôture de session",
                "Le tiroir s'ouvre au lancement de la clôture (comptage du Z). Désactivé : le tiroir reste fermé, le comptage se fait tiroir clos (BO-10-02-26).",
                "true", null),
        new Def("ticket.vat-breakdown-enabled", Type.BOOL, "TICKETS",
                "Ventilation TVA sur le ticket",
                "Le détail de la TVA par taux (HT / TVA) est imprimé sur le ticket de caisse et le ticket de retour. Désactivé : aucune ventilation TVA n'est imprimée (BO-10-06-04).",
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
                "Comment les lignes d'avantage sont regroupées sous la cagnotte du jour : TYPE (par type d'avantage, défaut), CATEGORY (par catégorie) ou NONE (liste à plat). Avec CATEGORY, les groupes sortent dans l'ordre de priorité de catégorie communiqué par la réponse du programme de fidélité, puis dans l'ordre d'arrivée pour les catégories sans rang. Une valeur inconnue retombe sur TYPE (BO-03-03-25, BO-03-03-33, BO-10-03-20).",
                "TYPE", null),
        new Def("fidelity.non-holder-message", Type.TEXT, "FIDÉLITÉ",
                "Message fidélité pour les non porteurs",
                "Texte imprimé sur le ticket d'un client sans carte de fidélité, quand le service fidélité a répondu. Vide : rien n'est imprimé (BO-03-03-28).",
                "", null),
        new Def("fidelity.external-enabled", Type.BOOL, "FIDÉLITÉ",
                "Service fidélité externe actif",
                "L'interrogation du service fidélité imfid (cagnotte, avantages, paiement fidélité) est active. Désactivé : la caisse se comporte comme si aucun service fidélité n'était configuré, même si son URL est renseignée (BO-10-03-07).",
                "true", null),
        new Def("cash.movement-tenders", Type.TEXT, "MOUVEMENTS DE CAISSE",
                "Moyens de paiement d'un mouvement",
                "Moyens de paiement proposés pour un mouvement de caisse (prélèvement/apport d'un chèque, d'un titre-restaurant...), séparés par des points-virgules. Vide : le mouvement porte toujours sur les espèces (BO-03-02-20).",
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
        new Def("fidelity.show-holder-name-customer", Type.BOOL, "FIDÉLITÉ",
                "Afficher le nom du porteur sur le viseur client",
                "Le nom et le prénom du porteur, quand une recherche les a trouvés, sont affichés sur le VISEUR CLIENT au rattachement de la carte. Désactivé — le défaut — le viseur client n'affiche aucune identité : il est tourné vers la salle, et le client qui suit lit l'écran du client devant lui (BO-10-03-03).",
                "false", null),
        new Def("fidelity.show-balance-customer", Type.BOOL, "FIDÉLITÉ",
                "Afficher la cagnotte du porteur sur le viseur client",
                "La cagnotte disponible de la carte rattachée, lue auprès de la fidélité, est affichée sur le VISEUR CLIENT jusqu'au paiement. Indépendant du nom du porteur : le magasin peut montrer la cagnotte sans l'identité. Désactivé — le défaut — le viseur client n'affiche aucun solde (LC-03-04-13).",
                "false", null),
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
                "Identifiant du compte machine de la caisse auprès du service fidélité. Vide : la propriété de déploiement pos.fid.user s'applique. Le mot de passe se règle par le paramètre « Mot de passe du service fidélité » (BO-11-04-04, BO-10-03-08).",
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
        new Def("gesture.function-endorsements", Type.TEXT, "GESTES ET AVALS",
                "Aval superviseur par fonction",
                "Une règle par ligne : GET /manual=REQUIRED ou POST /sale-tax-id=REQUIRED. NONE dispense l’aval de la fonction ; les blocages et seuils métier restent applicables. L’annulation de l’unique article conserve son aval obligatoire. Le suffixe /* couvre les sous-chemins. La dernière règle correspondante prime. Le geste et son formulaire attendent l'aval puis sont rejoués une seule fois. Les routes de connexion, d'aval et de sondage restent accessibles (LC-01-05-01).",
                "", null),
        new Def("training.blocked-keys", Type.TEXT, "MODE ÉCOLE",
                "Touches et fonctions bloquées en formation",
                "Une règle par ligne : path:/pay bloque le paiement ; path:/action/discount/* bloque toutes les actions de remise ; key: suivi d'un identifiant du rapport d'utilisation des touches bloque cette touche précise. Les touches restent visibles et expliquent leur refus. Vide : aucun blocage supplémentaire. La sortie de formation reste accessible ; les interdictions de produire des opérations réelles restent prioritaires (LC-13-05-04).",
                "", null),
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
        new Def("customer.integration-require-address", Type.BOOL, "CLIENT EN COMPTE",
                "Adresse obligatoire à l'intégration",
                "Une ligne du flux client en compte dont la rue, le code postal ou la ville manque est REJETÉE et nommée dans le compte rendu d'import, le reste du fichier étant intégré. Désactivé — le défaut — la ligne est intégrée avec l'adresse qu'elle porte (BO-10-08-27).",
                "false", null),
        new Def("referential.update-audit-enabled", Type.BOOL, "SUPERVISION",
                "Tracer les intégrations de référentiel",
                "Chaque intégration de référentiel — article, nomenclature, client, promotion — laisse une ligne datée dans l'audit de mise à jour, consultable sur l'écran de supervision des synchronisations. Désactivé — le défaut — seule l'empreinte courante est conservée, sans historique (BO-10-08-23).",
                "false", null),
        new Def("retention.integration-results-days", Type.INT, "RÉTENTION DES DONNÉES",
                "Jours de conservation des résultats d'intégration",
                "Nombre de jours de conservation des lignes de l'audit de mise à jour du référentiel avant SUPPRESSION par le balayage de fin de période. 0 = conservation illimitée (BO-10-08-10).",
                "0", null),
        new Def("retention.customers-days", Type.INT, "RÉTENTION DES DONNÉES",
                "Clients : purge après dernière activité (jours)",
                "Nombre de jours après la dernière activité (dernière mise à jour de la fiche) au-delà duquel un client est purgé. Un client avec un solde de crédit non nul ou encore lié à une facture n'est pas purgé. 0 = conservation illimitée (BO-10-08-26).",
                "0", null),
        new Def("retention.scheduled-reports-days", Type.INT, "RÉTENTION DES DONNÉES",
                "Rétention des éditions de fin de période (jours)",
                "Nombre de jours pendant lesquels les états édités automatiquement à la fin de chaque période sont conservés avec leur document. 0 : conservation illimitée, comme toute rétention non armée (BO-09-03-02).",
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
        new Def("gesture.endorsement-ticket-reassign", Type.BOOL, "GESTES NÉGATIFS",
                "Aval superviseur : réaffectation d'un ticket",
                "La réaffectation d'un ticket clôturé à un client fidélisé exige un aval superviseur (LC-03-06-01). Désactivé : le caissier réaffecte seul.",
                "true", null),
        new Def("reassign.pdv-duplicate", Type.BOOL, "TICKETS",
                "Duplicata PDV de la réaffectation",
                "La réaffectation d'un ticket imprime aussi un duplicata pour le point de vente (LC-03-06-05). Désactivé : seul le ticket du client sort.",
                "false", null),
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
                "Le lieu où le client va retirer les articles marqués A ENLEVER, imprimé sur le bon d'enlèvement : le client quitte la caisse et s'y rend en véhicule, donc le bon doit le nommer. Un seul point de retrait par magasin (le BATI en bricolage, le DRIVE en alimentaire). Vide : le bon n'en nomme aucun (LC-02-08-05).",
                "", null),
        new Def("collect.voucher-copies", Type.INT, "ARTICLES A ENLEVER",
                "Nombre d'exemplaires du bon d'enlèvement",
                "Le bon n'est pas un document fiscal : c'est une preuve de sortie de marchandise. Deux exemplaires permettent au client de LAISSER le sien au point de retrait, que le responsable relève le soir, et d'en GARDER un portant le reste à enlever. 1 : un seul bon, celui que le client garde (LC-02-08-05).",
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
                "Composition des rouleaux, séparés par des points-virgules, sous la forme FACIALE:MONTANT_ROULEAU:NOMBRE_DE_PIECES — exemple 0.20:8.00:40;0.01:0.50:50 ; le nombre de pièces peut être omis (il vaut alors le montant divisé par la valeur faciale), et une entrée dont les trois valeurs ne concordent pas est écartée. Sert au calcul croisé montant <-> nombre de rouleaux de la commande de monnaie et aux rouleaux proposés au comptage de caisse (LC-12-05-06/07, BO-03-02-42). Vide : aucun calcul croisé, et le comptage propose ses rouleaux habituels.",
                "2:50.00:25;1:25.00:25;0.50:20.00:40;0.20:8.00:40;0.10:4.00:40;0.05:2.00:40;0.02:1.00:50;0.01:0.50:50", null),
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
                "Langue par défaut du BackOffice, de la visu caisse, de la visu client et du ticket : fr, nl ou pt. C'est celle qui s'applique quand l'opérateur n'en a pas choisi d'autre et qu'aucune langue ne lui est administrée. Vide ou inconnue : français (LC-13-06-01/05/10, BO-01-01-04, BO-10-07-18).",
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
        new Def("keyboard.layout", Type.TEXT, "LANGUE",
                "Disposition du clavier virtuel",
                "Disposition des touches lettres des claviers alphabétiques et alphanumériques de la visu caisse, selon le pays ou la région : AZERTY, QWERTY, QWERTZ ou ABCD. Vide ou inconnue : AZERTY (LC-09-01-09).",
                "AZERTY", null),
        new Def("language.customer-choice", Type.BOOL, "LANGUE",
                "Choix de la langue par le client",
                "Le client choisit lui-même, sur la visu client tactile, la langue de la visu client et de son ticket de caisse parmi les langues proposées en caisse ; le choix vaut pour la transaction en cours et la visu client passe alors en une seule langue. Désactivé : aucune touche de langue sur la visu client (LC-13-06-08/13).",
                "false", null),
        new Def("language.fidelity-holder", Type.BOOL, "LANGUE",
                "Langue du client fidèle",
                "La langue que la base des clients fidélisés enregistre pour le porteur de la carte identifiée en caisse devient la langue de la visu client, du ticket de caisse et du ticket conservé pour sa restitution par API, pour la transaction en cours ; une langue que la caisse ne propose pas est ignorée. Désactivé : la langue reste celle de la caisse ou celle que le client choisit (LC-13-06-09, LC-13-06-14).",
                "false", null),
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
        new Def("balance.metrology-body", Type.TEXT, "BALANCE",
                "Organisme de certification métrologique",
                "Nom de l'organisme qui a certifié l'ensemble de pesage de la caisse (organisme notifié), affiché par la touche MÉTROLOGIE de l'écran de maintenance. Vide : « non renseigné » (LC-14-03-01).",
                "", null),
        new Def("balance.metrology-certificate", Type.TEXT, "BALANCE",
                "N° de certificat métrologique",
                "Numéro du certificat d'examen de type ou d'évaluation de l'ensemble de pesage, affiché par la touche MÉTROLOGIE de l'écran de maintenance. Vide : « non renseigné » (LC-14-03-01).",
                "", null),
        new Def("balance.metrology-crc", Type.TEXT, "BALANCE",
                "CRC de la balance",
                "Somme de contrôle (CRC) du logiciel légal de la balance, telle que la déclare son certificat, affichée par la touche MÉTROLOGIE de l'écran de maintenance pour comparaison avec celle de la balance. Administrable par caisse, le modèle de balance pouvant varier d'une caisse à l'autre. Vide : « non renseigné » (LC-14-03-01).",
                "", null),
        new Def("fidelity.send-subfamily", Type.BOOL, "FIDÉLITÉ",
                "Envoyer la famille et la sous-famille au moteur",
                "Chaque ligne du panier transmis au moteur de promotion porte le code de la famille de l'article et celui de sa sous-famille, pour que des avantages puissent être déclenchés à ce niveau ; le panier part tel quel au programme de fidélité, qui reçoit donc la sous-famille de chaque article (gestion article à la sous-famille, Intermarché France). Désactivé : la ligne ne porte que son code article (BO-10-03-19, BO-10-08-22).",
                "false", null),
        new Def("ticket.ecotax-print", Type.TEXT, "TICKETS",
                "Impression de l'éco-participation",
                "Où l'éco-participation des articles qui en portent une est imprimée sur le ticket : NONE (nulle part, défaut), LINE (sous chaque article concerné) ou TOTAL (une seule ligne, sous la ventilation de TVA). Une valeur inconnue retombe sur NONE (BO-10-06-06).",
                "NONE", null),
        new Def("ticket.due-amount-large", Type.BOOL, "TICKETS",
                "Montant dû en caractères agrandis",
                "La ligne du total à payer est émise en caractères double hauteur et double largeur, par la même directive que la mise en gras. Désactivé : elle est imprimée dans la fonte courante (BO-10-07-14).",
                "false", null),
        new Def("fiscal.certificate-number", Type.TEXT, "FISCAL",
                "N° de certification fiscale",
                "Numéro de certification du logiciel délivré par l'administration fiscale, imprimé sur tous les documents de vente et de retour de la caisse. Vide : aucune mention n'est imprimée, ce qui est le cas de tout pays qui n'en exige pas. Posé à l'échelon PAYS, il descend à toutes les caisses de ce pays (BO-10-06-03).",
                "", null),
        new Def("fiscal.pt-documents", Type.BOOL, "FISCAL",
                "Documents fiscaux portugais",
                "La caisse émet les documents fiscaux portugais : une fatura simplificada (FS) par vente, portant le NIF du client, ou une fatura (FT) quand le client la demande pendant la vente ou le paiement ; une nota de crédito (NC) après un retour ou pour annuler une FS convertie en FT après coup ; un recibo (RC) sur une FT. Chaque document porte son numéro de série, son ATCUD, les 4 caractères de sa signature chaînée, la mention de certification et le QR code fiscal. Une vente payée en partie par carte achat sociale est éditée sur deux FS, les articles éligibles réglés par la carte sur la première. Désactivé : la caisse imprime ses tickets comme avant (LC-08-01-05, LC-08-01-06, LC-08-04-18 à -23, LC-05-07-03).",
                "false", null),
        new Def("fiscal.atcud-codes", Type.TEXT, "FISCAL",
                "Codes de validation ATCUD des séries",
                "Code de validation attribué par l'administration fiscale à chaque série de documents de la caisse, sous la forme TYPE:CODE séparés par des points-virgules (ex. FS:AAJFJMVNTN;FT:AAJFJ3VNTN;NC:AAJFJ4VNTN;RC:AAJFJ5VNTN). L'ATCUD imprimé est le code, un tiret, puis le rang du document dans sa série. Administrable par caisse, chaque caisse ayant ses propres séries. Un code de moins de 8 caractères n'est pas un code de l'administration et vaut absence de code. Type sans code : ATCUD « 0-rang » (LC-08-04-23, BO-03-04-27).",
                "", null),
        new Def("order.external-code-pattern", Type.TEXT, "DEVIS ET COMMANDES",
                "Codes des devis, commandes et bons de vente externes",
                "Expression régulière reconnaissant, au scan, le code-barre imprimé sur un devis, une commande ou un bon de vente émis par l'outil de gestion commerciale (ex. (DV|CM|BV)[0-9]{6,}). Le code reconnu rappelle le document déposé sur le nœud magasin, comme une saisie à l'écran RAPPEL. Vide : seuls les codes de commande créés en caisse sont reconnus au scan, les autres se saisissent à l'écran RAPPEL (LC-06-02-01, LC-06-02-09).",
                "", null),
        new Def("order.recall-price-source", Type.TEXT, "DEVIS ET COMMANDES",
                "Prix des articles d'un document rappelé",
                "Prix appliqué aux articles d'un devis, d'une commande ou d'un bon de vente rappelé en caisse : DOCUMENT (le prix mentionné dans le document, défaut) ou CAISSE (le prix courant de l'article dans la base de la caisse ; un article inconnu de la caisse garde le prix du document). Une valeur inconnue retombe sur DOCUMENT (LC-06-02-08, LC-06-02-12).",
                "DOCUMENT", null),
        new Def("order.deposit-invoice", Type.BOOL, "DEVIS ET COMMANDES",
                "Facture d'acompte",
                "Chaque acompte perçu sur une commande ou un devis — créé en caisse ou rappelé — imprime en plus du justificatif une facture d'acompte numérotée : identité du magasin, articles et montants de la commande, montant de l'acompte et sa TVA par taux, déjà versé et reste dû. Désactivé : seul le justificatif d'acompte est imprimé (LC-08-04-15).",
                "false", null),
        new Def("selfscan.code-pattern", Type.TEXT, "SELFSCANNING",
                "Codes des paniers selfscanning",
                "Expression régulière reconnaissant, au scan, le code-barre du panier qu'un client a constitué sur un appareil de selfscanning du magasin (ex. SS[0-9]{8}). Le code reconnu rappelle le panier déposé sur le nœud magasin, depuis n'importe quelle caisse, et ouvre l'écran SELFSCANNING : contrôle, puis intégration dans la vente. Vide : les paniers se rappellent par leur code à l'écran RAPPEL (LC-06-05-03).",
                "", null),
        new Def("selfscan.partial-control-rate", Type.INT, "SELFSCANNING",
                "Taux de contrôle partiel",
                "Pourcentage des paniers selfscanning tirés au sort pour un contrôle partiel à leur rappel en caisse, en plus de ceux pour lesquels l'appareil demande lui-même un contrôle. 0 : seuls les contrôles demandés par l'appareil (LC-06-05-04).",
                "0", null),
        new Def("selfscan.partial-control-items", Type.INT, "SELFSCANNING",
                "Articles d'un contrôle partiel",
                "Nombre d'articles que le caissier re-scanne lors d'un contrôle partiel ; un article absent du panier ou en excédent fait basculer en contrôle total (LC-06-05-04, LC-06-05-05).",
                "3", null),
        new Def("giftcard.payment-ticket", Type.BOOL, "CARTE CADEAU",
                "Ticket de paiement carte cadeau",
                "Un ticket « paiement carte cadeau » (code de la carte, montant, ancien et nouveau solde, fournisseur et son code) sort en plus du ticket de caisse. Désactivé : seul le ticket de caisse est imprimé (LC-07-11-03).",
                "true", null),
        new Def("return.separate-detail", Type.BOOL, "RETOUR CLIENT",
                "Détail des retours sur un ticket séparé",
                "Sur un ticket mêlant ventes et retours, le détail des articles retournés et de leurs recalculs s'imprime sur un ticket séparé, le ticket principal n'en portant que la valeur. Désactivé : les retours sont détaillés sur le ticket principal (LC-05-07-02).",
                "false", null),
        new Def("external-program.label", Type.TEXT, "PROGRAMME EXTERNE",
                "Libellé de la touche du programme externe",
                "Libellé de la touche de l'écran caisse qui lance le programme externe installé sur la caisse, par exemple le cash management du monnayeur (LC-09-01-04).",
                "PROGRAMME EXTERNE", null),
        new Def("external-program.path", Type.TEXT, "PROGRAMME EXTERNE",
                "Chemin du programme externe",
                "Chemin d'accès complet du programme lancé par la touche de l'écran caisse. Vide : la touche n'apparaît pas (LC-09-01-04).",
                "", null),
        new Def("external-program.arguments", Type.TEXT, "PROGRAMME EXTERNE",
                "Arguments du programme externe",
                "Arguments passés au programme, séparés par des espaces ; aucun n'est interprété par un interpréteur de commandes (LC-09-01-04).",
                "", null),
        new Def("fidelity.card-luhn", Type.BOOL, "FIDÉLITÉ",
                "Clé de contrôle Luhn des cartes de fidélité",
                "Une carte reconnue par la plage des cartes de fidélité n'est acceptée que si son dernier chiffre est la clé de Luhn des précédents, quelle que soit sa longueur (19 chiffres compris). Désactivé : la plage seule décide, comme avant (BO-03-06-54).",
                "false", null),
        new Def("gs1.expiry-block-days", Type.INT, "CODES GS1",
                "Jours restants en dessous desquels l'article est refusé",
                "Avec l'alerte BLOCK ou SUPERVISOR, un article GS1 est refusé quand il reste MOINS que ce nombre de jours avant sa date de péremption (AI 17), le jour même comptant zéro : 0 refuse une date dépassée, 1 refuse aussi la date du jour (BO-03-06-64).",
                "0", null),
        new Def("gs1.expiry-message", Type.TEXT, "CODES GS1",
                "Message de l'article refusé pour sa date",
                "Message affiché au caissier quand un article GS1 est refusé pour sa date de péremption ; {date} est remplacé par la date lue. Vide : « ARTICLE PÉRIMÉ (date) » (BO-03-06-64).",
                "", null),
        new Def("gs1.short-date-message", Type.TEXT, "CODES GS1",
                "Message de l'article en date courte",
                "Message affiché au caissier quand un article GS1 est en date courte ; {date} est remplacé par la date lue. Vide : « DATE COURTE (date) » (BO-03-06-64).",
                "", null),
        new Def("qbusting.code-pattern", Type.TEXT, "QBUSTING",
                "Plage des transactions qbusting",
                "Motif de reconnaissance, en expression régulière, du code-barres d'une transaction qbusting (panier constitué dans la file d'attente) : scanné en caisse, il charge le panier déposé sur le nœud magasin. Vide : aucune transaction qbusting reconnue (BO-03-06-57).",
                "", null),
        new Def("scan.symbologies", Type.TEXT, "SCAN",
                "Symbologies acceptées",
                "Symbologies de codes-barres acceptées en caisse, séparées par des points-virgules, parmi EAN13 (UPC-A compris), EAN8, CODE128, CODE39, ITF, GS1, QR et DATAMATRIX ; le lecteur les annonce par leur identifiant de symbologie (]E0, ]C0, ]Q1, ]d1…), un code lu sans identifiant restant accepté. Vide : toutes les symbologies sont acceptées (BO-03-06-01).",
                "", null),
        new Def("scan.offline-alert", Type.TEXT, "SCAN",
                "Alerte de lecture hors ligne",
                "Ce que fait la caisse quand elle lit un code-barres alors qu'elle a perdu le lien avec le nœud magasin : NONE (rien), INFO (message au caissier, le code est traité) ou BLOCK (le code est refusé). Vide ou inconnu : NONE (BO-03-06-45).",
                "NONE", null),
        new Def("voucher.unknown-alert", Type.TEXT, "SCAN",
                "Alerte bon inconnu",
                "Ce que fait la caisse quand un bon, un avoir ou une carte cadeau scanné n'est connu ni de la caisse ni du nœud magasin : INFO (message, aucun règlement) ou BLOCK (message d'erreur, le bon est refusé). Vide ou inconnu : BLOCK (BO-03-06-48).",
                "BLOCK", null),
        new Def("balance.ftp-host", Type.TEXT, "BALANCE",
                "Serveur FTP des balances",
                "Adresse du serveur FTP où les balances rayon déposent les fichiers de leurs tickets balance. Vide : aucune récupération FTP, les tickets balance arrivent par l'API du nœud (BO-03-06-56).",
                "", null),
        new Def("balance.ftp-port", Type.INT, "BALANCE",
                "Port FTP des balances",
                "Port du serveur FTP des balances (BO-03-06-56).",
                "21", null),
        new Def("balance.ftp-user", Type.TEXT, "BALANCE",
                "Utilisateur FTP des balances",
                "Compte de connexion au serveur FTP des balances (BO-03-06-56).",
                "", null),
        new Def("balance.ftp-password", Type.TEXT, "BALANCE",
                "Mot de passe FTP des balances",
                "Mot de passe du compte FTP des balances (BO-03-06-56).",
                "", null),
        new Def("balance.ftp-directory", Type.TEXT, "BALANCE",
                "Répertoire FTP des balances",
                "Répertoire du serveur FTP lu par le nœud ; chaque fichier traité est renommé avec le suffixe .done (BO-03-06-56).",
                "/", null),
        new Def("balance.ftp-interval-seconds", Type.INT, "BALANCE",
                "Intervalle de récupération FTP",
                "Nombre de secondes entre deux lectures du répertoire FTP des balances. Zéro : récupération arrêtée (BO-03-06-56).",
                "0", null),
        new Def("balance.print-tare", Type.BOOL, "BALANCE",
                "Imprimer la tare appliquée",
                "La tare réellement appliquée à une pesée — contenant, article ou tare par défaut — est imprimée en kilogrammes sous la ligne de l'article pesé. Désactivé : le ticket n'énonce que le poids net, comme avant (BO-10-05-04).",
                "false", null),
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
        // ---- lot 18: E parameters ----
        new Def("channel.ecommerce.enabled", Type.BOOL, "CANAUX EXTERNES",
                "E-commerce : activation",
                "Le point de vente intègre les commandes e-commerce validées : le nœud magasin accepte leur dépôt, les enregistre comme transactions du magasin et la caisse reprend les paniers e-commerce. Désactivé : toute commande e-commerce est refusée (BO-10-01-01).",
                "false", null),
        new Def("channel.ecommerce.price-source", Type.TEXT, "CANAUX EXTERNES",
                "E-commerce : prix des articles",
                "Prix retenu pour les articles d'un panier e-commerce : POS (le prix courant de la base du point de vente ; un article inconnu garde le prix de la transaction) ou TRANSACTION (le prix de la transaction). Vide : la règle des documents rappelés s'applique (BO-10-01-07).",
                "", null),
        new Def("channel.ecommerce.forbidden-items", Type.TEXT, "CANAUX EXTERNES",
                "E-commerce : articles en vente interdite",
                "Intégration d'une commande e-commerce portant un article en vente interdite : ACCEPT (intégrée, l'article signalé au compte rendu) ou REJECT (rejetée en entier, rien n'est enregistré ; le compte rendu nomme l'article) (BO-10-01-14).",
                "ACCEPT", null),
        new Def("channel.ecommerce.unknown-item-ean", Type.TEXT, "CANAUX EXTERNES",
                "E-commerce : EAN générique des articles inconnus",
                "EAN qui porte, sans bloquer l'intégration, un article d'une commande e-commerce que la base du point de vente ne connaît pas : la ligne garde son libellé, marqué « ART. INCONNU » avec le code d'origine, sur le ticket et au compte rendu d'intégration. Vide : la ligne garde son propre code, le compte rendu la signale toujours (BO-10-01-06).",
                "", null),
        new Def("channel.ecommerce.promotion-channel", Type.TEXT, "CANAUX EXTERNES",
                "E-commerce : canal promotion",
                "Canal transmis au moteur de promotion (avantage carte et remise immédiate) pour les paniers e-commerce repris en caisse : POINT_OF_SALE (point de vente), WEB ou OTHER (autre). Vide : aucun canal n'est transmis et la requête reste identique à l'octet près (BO-10-01-13).",
                "", null),
        new Def("channel.ecommerce.island", Type.TEXT, "CANAUX EXTERNES",
                "E-commerce : îlot de rattachement",
                "Code de l'îlot auquel la caisse virtuelle e-commerce est rattachée : le chiffre d'affaires des transactions e-commerce y est affecté et ses règles (flux, états par îlot) s'y appliquent. Vide : la caisse virtuelle n'est rattachée à aucun îlot (BO-10-01-08).",
                "", null),
        new Def("channel.ecommerce.terminal", Type.TEXT, "CANAUX EXTERNES",
                "E-commerce : caisse virtuelle",
                "Identifiant de la caisse virtuelle sur laquelle le nœud magasin enregistre les transactions e-commerce intégrées ; le numéro de ticket est cet identifiant suivi de la référence de la commande (BO-10-01-08).",
                "ECOM", null),
        new Def("channel.ecommerce.operator", Type.TEXT, "CANAUX EXTERNES",
                "E-commerce : opérateur d'intégration",
                "Identifiant de l'employé au nom duquel les transactions e-commerce sont enregistrées dans l'encaissement ; un identifiant inconnu fait rejeter l'intégration, motif au compte rendu (BO-10-01-03).",
                "admin", null),
        new Def("channel.ecommerce.default-tender", Type.TEXT, "CANAUX EXTERNES",
                "E-commerce : moyen de paiement par défaut",
                "Code du moyen de paiement (CASH, CARD, CHEQUE, TR, VOUCHER…) sous lequel une commande e-commerce réglée par un moyen inconnu de l'encaissement est intégrée ; le compte rendu signale la substitution (BO-10-01-09).",
                "CARD", null),
        new Def("channel.ecommerce.archive", Type.BOOL, "CANAUX EXTERNES",
                "E-commerce : archivage des commandes d'origine",
                "La commande e-commerce telle que la plateforme l'a envoyée est archivée avec son compte rendu, pour l'analyse support (écran Commandes e-commerce). Désactivé : seul le compte rendu est gardé (BO-10-01-11).",
                "true", null),
        new Def("channel.ecommerce.archive-days", Type.INT, "CANAUX EXTERNES",
                "E-commerce : durée d'archivage (jours)",
                "Au-delà de ce nombre de jours, la purge de fin de période supprime les commandes e-commerce archivées. 0 : conservées sans limite (BO-10-01-11).",
                "0", null),
        new Def("channel.ecommerce.return-ticket-types", Type.TEXT, "CANAUX EXTERNES",
                "E-commerce : types de retour ticket autorisés",
                "Types de retour (libellés de la liste des types de retour, séparés par « ; ») autorisés pour le retour du ticket entier d'une transaction e-commerce ; un autre type, ou aucun type, est refusé. Vide : tout retour est autorisé (BO-10-01-04).",
                "", null),
        new Def("channel.ecommerce.return-article-types", Type.TEXT, "CANAUX EXTERNES",
                "E-commerce : types de retour article autorisés",
                "Types de retour (libellés séparés par « ; ») autorisés pour le retour d'une partie des articles d'une transaction e-commerce ; un autre type, ou aucun type, est refusé. Vide : tout retour est autorisé (BO-10-01-05).",
                "", null),
        new Def("channel.selfscan.enabled", Type.BOOL, "CANAUX EXTERNES",
                "Selfscanning : activation",
                "Le self scanning est ouvert sur ce point de vente : la caisse récupère et contrôle les paniers des appareils de selfscanning. Désactivé : tout panier selfscanning est refusé avec un message (BO-10-01-15).",
                "true", null),
        new Def("channel.selfscan.retrieval-mode", Type.TEXT, "CANAUX EXTERNES",
                "Selfscanning : mode de récupération",
                "Comment la caisse récupère un panier selfscanning : SCAN (lecture de son code-barres), KEYED (saisie de son code à l'écran RAPPEL) ou BOTH (les deux) (BO-10-01-16).",
                "BOTH", null),
        new Def("channel.selfscan.add-items", Type.TEXT, "CANAUX EXTERNES",
                "Selfscanning : ajout d'articles en caisse",
                "Quand le caissier peut saisir des articles autour de la récupération d'un panier selfscanning : BEFORE (avant seulement), AFTER (après seulement), BOTH (avant et après) ou NONE (ni avant ni après) (BO-10-01-17).",
                "BOTH", null),
        new Def("channel.selfscan.identifier", Type.TEXT, "CANAUX EXTERNES",
                "Selfscanning : identifiant de reprise",
                "Ce qui identifie le panier selfscanning à reprendre : TRANSACTION (le code-barres de la transaction) ou CUSTOMER (le numéro de client, saisi à l'écran RAPPEL : la caisse reprend le dernier panier de ce client) (BO-10-01-18).",
                "TRANSACTION", null),
        new Def("channel.selfscan.price-source", Type.TEXT, "CANAUX EXTERNES",
                "Selfscanning : prix des articles",
                "Prix retenu pour les articles d'un panier selfscanning : POS (le prix courant de la base du point de vente ; un article inconnu garde le prix de la transaction) ou TRANSACTION (le prix de la transaction). Vide : la règle des documents rappelés s'applique (BO-10-01-19).",
                "", null),
        new Def("channel.selfscan.coupons", Type.BOOL, "CANAUX EXTERNES",
                "Selfscanning : coupons du panier",
                "Les coupons et bons d'achat scannés dans un panier selfscanning sont acceptés et repris en caisse comme règlements. Désactivé : ils ne sont pas repris et la caisse le signale (BO-10-01-20).",
                "true", null),
        new Def("channel.selfscan.unknown-item-ean", Type.TEXT, "CANAUX EXTERNES",
                "Selfscanning : EAN générique des articles inconnus",
                "EAN qui porte, sans bloquer la reprise, un article d'un panier selfscanning que la base du point de vente ne connaît pas : la ligne garde son libellé, marqué « ART. INCONNU » avec le code d'origine, à l'écran et sur le ticket. Vide : la ligne garde son propre code, sans marque (BO-10-01-21).",
                "", null),
        new Def("channel.selfscan.promotion-channel", Type.TEXT, "CANAUX EXTERNES",
                "Selfscanning : canal promotion",
                "Canal transmis au moteur de promotion (avantage carte et remise immédiate) pour les paniers selfscanning : POINT_OF_SALE (point de vente), WEB ou OTHER (autre). Vide : aucun canal n'est transmis et la requête reste identique à l'octet près (BO-10-01-22).",
                "", null),
        new Def("channel.selfscan.forbidden-items", Type.TEXT, "CANAUX EXTERNES",
                "Selfscanning : articles en vente interdite",
                "Intégration d'un panier selfscanning portant un article en vente interdite : ACCEPT (intégré comme les autres) ou REJECT (rejeté en entier ; la caisse affiche le libellé, le code et le message de l'article, et ne charge rien) (BO-10-01-23).",
                "ACCEPT", null),
        new Def("channel.commercial.forbidden-items", Type.TEXT, "CANAUX EXTERNES",
                "Gestion commerciale : articles en vente interdite",
                "Intégration d'une commande, d'un devis ou d'un bon de vente de la gestion commerciale portant un article en vente interdite : ACCEPT (intégré comme les autres) ou REJECT (rejeté en entier ; la caisse affiche le libellé, le code et le message de l'article, et ne charge rien) (BO-10-01-24).",
                "ACCEPT", null),
        new Def("channel.commercial.retrieval-mode", Type.TEXT, "CANAUX EXTERNES",
                "Gestion commerciale : mode de récupération",
                "Comment la caisse récupère une commande ou un devis de la gestion commerciale : SCAN (lecture de son code-barres), KEYED (saisie de son code à l'écran RAPPEL) ou BOTH (les deux) (BO-10-01-25).",
                "BOTH", null),
        new Def("channel.commercial.transmission", Type.TEXT, "CANAUX EXTERNES",
                "Gestion commerciale : transmission des commandes",
                "Sens et mécanisme d'échange des commandes et devis : PUSH (la gestion commerciale les dépose sur le nœud magasin, POST /api/orders) ou PULL (le nœud magasin les tire lui-même à l'adresse et à la fréquence ci-dessous) (BO-10-01-25).",
                "PUSH", null),
        new Def("channel.commercial.pull-url", Type.TEXT, "CANAUX EXTERNES",
                "Gestion commerciale : adresse de tirage",
                "Adresse HTTP que le nœud magasin interroge en mode PULL ; elle répond la liste JSON des documents dans la forme du dépôt. Vide : aucun tirage (BO-10-01-25).",
                "", null),
        new Def("channel.commercial.pull-minutes", Type.INT, "CANAUX EXTERNES",
                "Gestion commerciale : fréquence de tirage (minutes)",
                "Intervalle entre deux tirages des commandes en mode PULL ; 0 vaut une minute (BO-10-01-25).",
                "15", null),
        new Def("channel.commercial.auto-print-template", Type.TEXT, "CANAUX EXTERNES",
                "Gestion commerciale : document imprimé à l'encaissement",
                "Code du modèle de document (écran Modèles de documents) imprimé automatiquement après l'encaissement d'une commande ou d'un devis de la gestion commerciale. Vide : aucun document de plus (BO-10-01-26).",
                "", null),
        new Def("channel.commercial.deposit-tender", Type.TEXT, "CANAUX EXTERNES",
                "Gestion commerciale : moyen de paiement des acomptes",
                "Code du moyen de paiement (CASH, CARD, CHEQUE…) sous lequel l'acompte perçu en caisse sur une commande ou un devis est enregistré ; seul un acompte en espèces entre dans le tiroir. Vide : espèces (BO-10-01-27).",
                "", null),
        new Def("channel.commercial.promotion-channel", Type.TEXT, "CANAUX EXTERNES",
                "Gestion commerciale : canal promotion",
                "Canal transmis au moteur de promotion (avantage carte et remise immédiate) pour les commandes, devis et bons de vente de la gestion commerciale : POINT_OF_SALE (point de vente), WEB ou OTHER (autre). Vide : aucun canal n'est transmis et la requête reste identique à l'octet près (BO-10-01-28).",
                "", null),
        new Def("channel.commercial.price-source", Type.TEXT, "CANAUX EXTERNES",
                "Gestion commerciale : prix des articles",
                "Prix retenu pour les articles d'une commande ou d'un devis de la gestion commerciale : POS (le prix courant de la base du point de vente ; un article inconnu garde le prix de la commande) ou TRANSACTION (le prix de la commande). Vide : la règle des documents rappelés s'applique (BO-10-01-29).",
                "", null),
        new Def("channel.qbusting.retrieval-mode", Type.TEXT, "CANAUX EXTERNES",
                "Qbusting : mode de récupération",
                "Comment la caisse récupère un panier qbusting : SCAN (lecture de son code-barres), KEYED (saisie de son code à l'écran RAPPEL) ou BOTH (les deux) (BO-10-01-30).",
                "BOTH", null),
        new Def("channel.qbusting.promotion-channel", Type.TEXT, "CANAUX EXTERNES",
                "Qbusting : canal promotion",
                "Canal transmis au moteur de promotion (avantage carte et remise immédiate) pour les paniers qbusting : POINT_OF_SALE (point de vente), WEB ou OTHER (autre). Vide : aucun canal n'est transmis et la requête reste identique à l'octet près (BO-10-01-31).",
                "", null),
        new Def("channel.qbusting.forbidden-items", Type.TEXT, "CANAUX EXTERNES",
                "Qbusting : articles en vente interdite",
                "Intégration d'une transaction qbusting portant un article en vente interdite : ACCEPT (intégré comme les autres) ou REJECT (rejeté en entier ; la caisse affiche le libellé, le code et le message de l'article, et ne charge rien) (BO-10-01-32).",
                "ACCEPT", null),
        new Def("channel.qbusting.price-source", Type.TEXT, "CANAUX EXTERNES",
                "Qbusting : prix des articles",
                "Prix retenu pour les articles d'un panier qbusting : POS (le prix courant de la base du point de vente ; un article inconnu garde le prix de la transaction) ou TRANSACTION (le prix de la transaction). Vide : la règle des documents rappelés s'applique (BO-10-01-33).",
                "", null),
        new Def("channel.mobility.promotion-channel", Type.TEXT, "CANAUX EXTERNES",
                "Mobilité : canal promotion",
                "Canal transmis au moteur de promotion (avantage carte et remise immédiate) pour les transactions mobilité : POINT_OF_SALE (point de vente), WEB ou OTHER (autre). Vide : aucun canal n'est transmis et la requête reste identique à l'octet près (BO-10-01-34).",
                "", null),
        new Def("channel.mobility.price-source", Type.TEXT, "CANAUX EXTERNES",
                "Mobilité : prix des articles",
                "Prix retenu pour les articles d'un panier mobilité : POS (le prix courant de la base du point de vente ; un article inconnu garde le prix de la transaction) ou TRANSACTION (le prix de la transaction). Vide : la règle des documents rappelés s'applique (BO-10-01-36).",
                "", null),
        new Def("channel.mobility.forbidden-items", Type.TEXT, "CANAUX EXTERNES",
                "Mobilité : articles en vente interdite",
                "Intégration d'une transaction mobilité portant un article en vente interdite : ACCEPT (intégré comme les autres) ou REJECT (rejeté en entier ; la caisse affiche le libellé, le code et le message de l'article, et ne charge rien) (BO-10-01-37).",
                "ACCEPT", null),
        new Def("channel.mobility.fictitious-tender", Type.TEXT, "CANAUX EXTERNES",
                "Mobilité : moyen de paiement complémentaire",
                "Code du moyen de paiement ajouté à l'intégration d'une transaction mobilité dont le total payé est inférieur au total du ticket (promotion déclenchée en mobilité mais pas à l'encaissement), pour la différence. Vide : la différence reste due en caisse (BO-10-01-39).",
                "", null),
        new Def("channel.sales-slip.retrieval-mode", Type.TEXT, "CANAUX EXTERNES",
                "Bon de vente : mode de récupération",
                "Comment la caisse récupère un bon de vente — valable le jour même seulement, son numéro étant réinitialisé chaque jour : SCAN (lecture de son code-barres), KEYED (saisie de son code à l'écran RAPPEL) ou BOTH (les deux) (BO-10-01-40).",
                "BOTH", null),
        new Def("channel.sales-slip.price-source", Type.TEXT, "CANAUX EXTERNES",
                "Bon de vente : prix des articles",
                "Prix retenu pour les articles d'un bon de vente : POS (le prix courant de la base du point de vente ; un article inconnu garde le prix du bon de vente) ou TRANSACTION (le prix du bon de vente). Vide : la règle des documents rappelés s'applique (BO-10-01-41).",
                "", null),

        // ---- lot 18: F parameters ----
        new Def("drawer.open-in-training", Type.BOOL, "TIROIR",
                "Ouverture du tiroir en mode école",
                "En mode école, le tiroir s'ouvre comme en vente réelle (paiement, prise de poste, clôture). Désactivé : aucune impulsion d'ouverture n'est envoyée tant que la caisse est en mode école, le tiroir restant fermé sur un encaissement fictif (BO-10-02-12).",
                "true", null),
        new Def("scan.unknown-manual-entry", Type.BOOL, "SCAN",
                "Saisie famille/code sur article inconnu",
                "Au scan d'un code que rien ne reconnaît, le message « article inconnu » s'affiche et la caisse enchaîne sur l'écran de saisie directe pour vendre l'article par sa famille ou son code. Désactivé : seul le message s'affiche (BO-10-02-14).",
                "false", null),
        new Def("calc.rounding-mode", Type.TEXT, "ARRONDI DES CALCULS",
                "Règle d'arrondi des calculs de caisse",
                "Règle appliquée quand un montant calculé en caisse est ramené à deux décimales — total de ligne, ventilation de TVA, hors taxe des factures : HALF_UP (au plus proche, la demie vers le haut, défaut), HALF_EVEN (au plus proche, la demie vers le pair), HALF_DOWN (au plus proche, la demie vers le bas), UP (toujours vers le haut), DOWN (toujours vers le bas). Une valeur inconnue retombe sur HALF_UP (BO-10-02-18).",
                "HALF_UP", null),
        new Def("invoice.decimals-line-total-ht", Type.INT, "FACTURE",
                "Décimales du total HT de la ligne",
                "Nombre de décimales du total hors taxe de chaque ligne article sur la facture et le bon de livraison : 2 ou 3. Une autre valeur retombe sur 2 (BO-10-02-19).",
                "2", null),
        new Def("auth.pin-length", Type.INT, "SESSION CAISSE",
                "Nombre de chiffres du code PIN",
                "Nombre de chiffres du code saisi en caisse : pavé de connexion, changement de code et code généré à la création d'un compte. De 4 à 8 ; en dessous, 4, au-dessus, 8 (BO-10-02-23).",
                "4", null),
        new Def("supervisor.validation-mode", Type.TEXT, "SESSION CAISSE",
                "Mode de validation superviseur",
                "Ce que le panneau d'autorisation superviseur demande : CODE_PASSWORD (code superviseur, tapé ou lu, puis mot de passe — défaut), BARCODE (code-barres superviseur lu, seul), BARCODE_CODE_PASSWORD (code-barres lu, puis code tapé, puis mot de passe), BARCODE_PASSWORD (code-barres lu, puis mot de passe) ou DALLAS (clé Dallas du superviseur, seule). Une valeur inconnue retombe sur CODE_PASSWORD (BO-10-02-31).",
                "CODE_PASSWORD", null),
        new Def("sale.group-lines", Type.BOOL, "AFFICHAGE",
                "Regrouper les mêmes articles",
                "Un article scanné à nouveau s'ajoute à la quantité de sa ligne au lieu d'ouvrir une ligne de plus, tant que la ligne n'a ni prix modifié, ni sous-total après elle. Désactivé : chaque article est une ligne. Les pesées restent une ligne chacune (BO-10-08-32).",
                "true", null),
        new Def("sale.forbidden-message", Type.TEXT, "AFFICHAGE",
                "Message de l'article en vente interdite",
                "Message affiché en caisse quand un article interdit à la vente ou en retrait/rappel est scanné et que sa fiche ne porte pas de message propre (attribut FORBIDDEN_SALE_MESSAGE). Vide : « ARTICLE EN RETRAIT/RAPPEL » (BO-10-07-20).",
                "", null),
        new Def("balance.ticket-mode", Type.TEXT, "BALANCE",
                "Mode de récupération des tickets balance",
                "Comment le nœud magasin reçoit les paniers des balanciers, que la caisse lit au scan du ticket balance : API_FTP (poussés sur l'API du nœud et relevés sur le serveur FTP administré, défaut), API (API seule, le FTP n'est pas relevé), FTP (FTP seul, l'API refuse les paniers) ou OFF (aucun ticket balance : le nœud n'en reçoit plus et la caisse ne reconnaît plus leur code-barres). Une valeur inconnue retombe sur API_FTP (BO-10-05-01).",
                "API_FTP", null),
        new Def("referential.defer-during-sale", Type.BOOL, "SUPERVISION",
                "Mises à jour article sans impact sur la vente",
                "Une mise à jour des articles, prix, familles, TVA, plages de codes, types de bons ou flux du moteur de promotions arrivant pendant une vente est différée jusqu'à ce que la caisse soit libre : le panier en cours garde les prix et promotions avec lesquels il a été commencé. Désactivé : la mise à jour s'applique aussitôt (BO-10-08-25).",
                "true", null),
        new Def("gs1.enabled", Type.BOOL, "CODES GS1",
                "Lecture des codes GS1",
                "La caisse lit les codes GS1 (Databar, DataMatrix, QR code, Digital Link) et applique leurs identifiants d'application. Désactivé : un code GS1 n'est plus interprété et suit le chemin d'un code ordinaire (BO-10-09-01).",
                "true", null),
        new Def("gs1.ai-alerts", Type.TEXT, "CODES GS1",
                "Alertes par identifiant GS1",
                "Alerte levée à la lecture d'un identifiant d'application, séparées par des points-virgules, sous la forme AI=NIVEAU:message — exemple 10=SUPERVISOR:Lot {value} à contrôler;21=WARN:Numéro de série {value}. Niveaux : INFO (message, l'article est enregistré), WARN (avertissement, l'article est enregistré), BLOCK (article refusé) ou SUPERVISOR (article refusé, un superviseur peut forcer son enregistrement). {value} est remplacé par la valeur lue, {ai} par l'identifiant. Vide : aucune alerte (BO-10-09-04).",
                "", null),

        // ---- lot 18: G parameters ----
        new Def("fidelity.pot-balances-enabled", Type.BOOL, "FIDÉLITÉ",
                "Demander les soldes des cagnottes",
                "La caisse demande au programme de fidélité le solde de CHACUNE de ses cagnottes (FID Portugal), calculé avec les éléments de la transaction en cours : avantages fidélité du ticket et paiement fidélité déjà réglé. Les soldes sont affichés sur l'écran fidélité de la caisse et relus à la clôture du ticket. Désactivé — le défaut — seul le solde du compte est interrogé (BO-10-02-33).",
                "false", null),
        new Def("fidelity.payment-tender", Type.TEXT, "FIDÉLITÉ",
                "Moyen de paiement du paiement fidélité",
                "Clé du moyen de paiement sous lequel un règlement sur la cagnotte fidélité est enregistré sur le ticket, dans les états et dans les flux. Une clé inconnue retombe sur FIDELITY (BO-10-03-06).",
                "FIDELITY", null),
        new Def("fidelity.password", Type.TEXT, "FIDÉLITÉ",
                "Mot de passe du service fidélité",
                "Mot de passe du compte machine de la caisse auprès du service fidélité, distribué aux caisses avec les autres paramètres et jamais réaffiché : un champ laissé vide conserve la valeur enregistrée. Vide : la propriété de déploiement pos.fid.password s'applique (BO-10-03-08).",
                "", null),
        new Def("fidelity.connect-timeout-ms", Type.INT, "FIDÉLITÉ",
                "Délai de connexion au service fidélité (ms)",
                "Temps accordé à l'ouverture de la connexion vers le service fidélité, en millisecondes. Au-delà, la caisse affiche que la fidélité est indisponible et la vente continue sans elle. 0 : 1500 ms (BO-10-03-10).",
                "1500", null),
        new Def("fidelity.read-timeout-ms", Type.INT, "FIDÉLITÉ",
                "Délai de réponse du service fidélité (ms)",
                "Temps accordé au service fidélité pour répondre à un appel (cagnotte, avantages, recherche, paiement), en millisecondes. Au-delà, la caisse affiche que la fidélité est indisponible et la vente continue sans elle. 0 : 2500 ms (BO-10-03-10).",
                "2500", null),
        new Def("fidelity.order-date-enabled", Type.BOOL, "FIDÉLITÉ",
                "Envoyer la date de commande au moteur de promotion",
                "Quand le ticket reprend une commande, le panier transmis au moteur de promotion — et, par lui, au programme de fidélité — porte la date de la commande, pour que les avantages acquis à cette date soient déclenchés, campagnes échues comprises. Désactivé — le défaut — seule la date du ticket est transmise (BO-10-03-17).",
                "false", null),

        // ---- lot 18: H parameters ----
        new Def("fiscal.saft-id-fs", Type.INT, "FISCAL",
                "Identifiant SAF-T de la fatura simplificada (FS)",
                "Code numérique du type de document vente ticket portugais, porté par chaque FS émise et repris dans l'exportation SAF-T. Le préfixe FS reste celui du gabarit du document (BO-10-06-01).",
                "1", null),
        new Def("fiscal.saft-id-nc", Type.INT, "FISCAL",
                "Identifiant SAF-T de la nota de crédito (NC)",
                "Code numérique du type de document avoir portugais, porté par chaque NC émise et repris dans l'exportation SAF-T. Le préfixe NC reste celui du gabarit du document (BO-10-06-02).",
                "2", null),
        new Def("ecotax.attributes", Type.TEXT, "FISCAL",
                "Attributs article portant une éco-taxe",
                "Codes des attributs dynamiques article dont la valeur est un montant d'éco-taxe unitaire, séparés par des points-virgules, sans limite de nombre. Chaque code peut être suivi de « =Libellé » (le libellé imprimé) et de « >NOPRINT » pour que cette éco-taxe ne soit pas imprimée sur le ticket ; elle reste totalisée sur les factures, bons de livraison et leurs extractions. Exemple : « DEEE=Eco DEEE;DEA=Eco mobilier;PILES;PNEUS>NOPRINT;EMB ». L'attribut ECO_TAX est toujours une éco-taxe imprimée, sauf s'il est listé avec >NOPRINT. Vide : seul ECO_TAX (BO-10-06-05, BO-10-06-06).",
                "", null),
        new Def("cheque.unit-text", Type.TEXT, "DEVISE",
                "Texte chèque des unités monétaires",
                "Mot imprimé sur le chèque après le montant des unités écrit en lettres, par exemple « Euro(s) ». Vide : le mot de la langue de la caisse, accordé au nombre (BO-10-07-05).",
                "", null),
        new Def("cheque.cent-text", Type.TEXT, "DEVISE",
                "Texte chèque des centimes",
                "Mot imprimé sur le chèque après les centimes écrits en lettres, par exemple « Cent(s) ». Vide : le mot de la langue de la caisse, accordé au nombre (BO-10-07-05).",
                "", null),
        new Def("ticket.eft-logos", Type.BOOL, "TICKETS",
                "Logos monétique sur le ticket monétique",
                "Le ticket monétique sort avec ses images : le logo monétique administré en tête de chaque ticket du terminal de paiement, et le logo sans contact sur celui d'un paiement sans contact, obligation légale. Les images sont celles de l'écran Images (usages « Logo monétique » et « Logo sans contact ») ; sans image administrée rien n'est ajouté. Désactivé : les trames du terminal sortent seules (BO-10-07-13).",
                "true", null),
        new Def("ticket.family-subtotals", Type.BOOL, "TICKETS",
                "Sous-totaux par famille",
                "Quand les articles sont imprimés par nomenclature (ordre FAMILY), un sous-total de la famille est imprimé sous ses articles. Désactivé : les familles s'enchaînent sans sous-total (BO-10-07-15).",
                "false", null),
        new Def("ticket.bold-amounts", Type.BOOL, "TICKETS",
                "Montants en gras",
                "Les montants des lignes articles, des sous-totaux et du total à payer du ticket de caisse sont émis en gras, par la directive de mise en gras de l'imprimante, posée après cadrage. Désactivé : fonte courante (BO-10-07-15).",
                "false", null),
        new Def("language.customer-display", Type.TEXT, "LANGUE",
                "Première langue de la visu client",
                "Langue principale de la visu client, indépendante de celle du caissier : fr, nl ou pt. La deuxième langue de la visu client s'affiche à côté d'elle. Vide ou inconnue : la langue de la caisse (BO-10-07-17).",
                "", null),
        new Def("customer.message-open-second", Type.TEXT, "AFFICHEUR CLIENT",
                "Message caisse ouverte (deuxième langue)",
                "Traduction du message caisse ouverte dans la deuxième langue de la visu client, affichée à côté du message principal quand la visu client est bilingue. Vide : le message principal seul (BO-10-07-17).",
                "", null),
        new Def("customer.message-closed-second", Type.TEXT, "AFFICHEUR CLIENT",
                "Message caisse fermée (deuxième langue)",
                "Traduction du message caisse fermée dans la deuxième langue de la visu client, affichée à côté du message principal quand la visu client est bilingue. Vide : le message principal seul (BO-10-07-17).",
                "", null),
        new Def("display.offline-enabled", Type.BOOL, "AFFICHAGE",
                "Apparence caisse en mode déconnecté",
                "Quand la caisse ne joint plus son serveur magasin, en transaction ou hors transaction, l'écran caisse change d'apparence (fond d'écran et bandeau « MODE DÉCONNECTÉ ») au bout du délai ci-dessous, et reprend son apparence connectée dès que le serveur répond. Désactivé : apparence inchangée (BO-10-07-19).",
                "false", null),
        new Def("display.offline-delay-seconds", Type.INT, "AFFICHAGE",
                "Délai avant l'apparence déconnectée (secondes)",
                "Durée pendant laquelle la caisse doit rester sans réponse du serveur magasin avant de prendre l'apparence déconnectée. 0 : dès la première perte de liaison ; une valeur négative vaut 0 (BO-10-07-19).",
                "60", null),
        new Def("unit.measure-decimals", Type.INT, "UNITÉS DE MESURE",
                "Décimales des quantités en unité de mesure",
                "Nombre de décimales affichées et imprimées après la virgule pour une quantité vendue à l'unité de mesure (mètre, litre…) qui n'est pas entière, de 0 à 4. Hors de cette plage : 2 (BO-10-08-28).",
                "2", null),
        new Def("unit.weight-decimals", Type.INT, "UNITÉS DE MESURE",
                "Décimales des quantités pesées",
                "Nombre de décimales affichées et imprimées après la virgule pour un poids, de 0 à 4. Hors de cette plage : 3 (BO-10-08-28).",
                "3", null),
        new Def("unit.weight-symbol", Type.TEXT, "UNITÉS DE MESURE",
                "Symbole des quantités pesées",
                "Symbole affiché sur la visu caisse et la visu client et imprimé sur les tickets à côté d'un poids et d'un prix au poids, par exemple kg ou KG. Vide : kg (BO-10-08-29).",
                "kg", null),
        new Def("ticket.cancelled-lines-print", Type.BOOL, "TICKETS",
                "Lignes annulées sur le ticket",
                "Les lignes annulées en caisse (annulation ligne ou annulation article) sont imprimées sur le ticket et sur le reçu de mise en attente, marquées ANNULÉ et hors des totaux. Désactivé : elles n'y figurent pas (BO-10-08-30).",
                "false", null),
        new Def("ticket.identical-lines-detailed", Type.BOOL, "TICKETS",
                "Articles identiques détaillés sur le ticket",
                "Chaque article identique saisi plusieurs fois est imprimé sur sa propre ligne, à l'unité. Désactivé, ce qui est l'optimisation par défaut : les articles identiques sortent regroupés sur une seule ligne portant la quantité (BO-10-08-31).",
                "false", null),

        // ---- lot 18: I parameters ----
        // SUPERVISION ET DÉPLOIEMENT (BO-08). Les rapports des nœuds remontent la
        // version, la santé et l'inventaire ; le déploiement descend la version à
        // faire tourner, avec ses reprises et son retour arrière.
        new Def("supervision.report-seconds", Type.INT, "SUPERVISION",
                "Cadence des rapports de nœud (secondes)",
                "Intervalle entre deux rapports d'une caisse à son magasin et d'un magasin au central : version, état de mise à jour, périphériques, inventaire, intégrations, usage des fonctions. Lu au démarrage du nœud ; zéro ou moins : soixante secondes (BO-08-04-15, BO-08-04-16, BO-08-04-20, BO-08-03-01).",
                "60", null),
        new Def("supervision.function-usage-enabled", Type.BOOL, "SUPERVISION",
                "Comptage de l'usage des fonctions",
                "Chaque fonction de caisse et chaque geste du back-office est compté par jour, par nœud et par fonction, puis remonté au central pour les statistiques. Désactivé : rien n'est compté (BO-08-02-02).",
                "true", null),
        new Def("supervision.remote-maintenance-enabled", Type.BOOL, "SUPERVISION",
                "Prise en main à distance",
                "Les ordres de maintenance émis par le central ou le magasin (relance TPV, réinitialisation d'un périphérique, relance des référentiels, contrôle de mise à jour) sont exécutés par le nœud. Désactivé : le nœud les refuse, et le refus est tracé (BO-08-04-17).",
                "true", null),
        new Def("integration.retry-enabled", Type.BOOL, "SUPERVISION",
                "Relance automatique des intégrations en erreur",
                "Un fichier déposé dont l'intégration a échoué ou laissé des lignes rejetées est conservé et réintégré automatiquement à la cadence administrée. Désactivé : seule la relance manuelle de la console d'intégration le rejoue (BO-08-04-03, BO-08-04-04, BO-08-04-05, BO-08-04-06, BO-08-04-07, BO-08-04-08).",
                "true", null),
        new Def("integration.retry-minutes", Type.INT, "SUPERVISION",
                "Délai entre deux relances d'intégration (minutes)",
                "Temps minimal entre deux réintégrations automatiques d'un même dépôt en erreur. Zéro ou moins : dix minutes (BO-08-04-03, BO-08-04-08).",
                "10", null),
        new Def("integration.retry-max-attempts", Type.INT, "SUPERVISION",
                "Nombre maximal de relances d'intégration",
                "Au-delà, le dépôt est abandonné par la relance automatique et ne repart que par la relance manuelle. Zéro ou moins : cinq (BO-08-04-03, BO-08-04-09, BO-08-04-13).",
                "5", null),
        new Def("dashboard.mobile-alerts-enabled", Type.BOOL, "SUPERVISION",
                "Alertes caisses sur appareil mobile",
                "Les alertes caisses (appels superviseur) s'affichent sur la page mobile du back-office, où un responsable les acquitte, les autorise ou les refuse à distance. Désactivé : la page mobile n'affiche aucune alerte (BO-10-08-02).",
                "false", null),
        new Def("deployment.auto-update-at-startup", Type.BOOL, "DÉPLOIEMENT",
                "Mise à jour automatique au démarrage",
                "Un nœud dont la version est inférieure à celle de son lot de déploiement se met à jour à son démarrage, même hors de la fenêtre du lot : la caisse ou le magasin saisonnier rattrape la version en s'allumant. Désactivé : il attend la prochaine fenêtre (BO-08-03-07).",
                "true", null),
        new Def("deployment.max-attempts", Type.INT, "DÉPLOIEMENT",
                "Nombre de tentatives de mise à jour",
                "Une mise à jour en échec est retentée automatiquement au rapport suivant du nœud, jusqu'à ce nombre ; au-delà, seul un forçage depuis la console de déploiement la relance. Zéro ou moins : trois (BO-08-04-01, BO-08-04-02).",
                "3", null),
        new Def("deployment.rollback-enabled", Type.BOOL, "DÉPLOIEMENT",
                "Retour arrière automatique",
                "Une version installée qui échoue à son autotest est remplacée automatiquement par la version applicative précédente, sans toucher aux données des référentiels. Désactivé : le nœud reste sur la version en échec, signalée en anomalie (BO-08-04-14).",
                "true", null),

        // ---- lot 19: K parameters ----
        // CONFIGURATION DES POINTS DE CONTACT (BO-03-09, BO-03-10). Chaque clé
        // se pose à tous les échelons, l'îlot et le point de caisse compris :
        // c'est ce qui la rend « réglable par TPV » et applicable à un groupe.
        new Def("ticket.header-template", Type.TEXT, "TICKETS",
                "Modèle d'entête de ticket",
                "Code du gabarit Entête de ticket imprimé en tête des tickets de cette caisse, choisi parmi les gabarits administrés. Vide ou code inconnu : le premier gabarit d'entête actif, sinon le message d'entête (BO-03-09-02).",
                "", null),
        new Def("ticket.footer-template", Type.TEXT, "TICKETS",
                "Modèle de pied de ticket",
                "Code du gabarit Pied de ticket imprimé au bas des tickets de cette caisse, choisi parmi les gabarits administrés. Vide ou code inconnu : le premier gabarit de pied actif, sinon le message de pied (BO-03-09-02).",
                "", null),
        new Def("ticket.vat-table", Type.TEXT, "TICKETS",
                "Tableau TVA du ticket",
                "Modèle du tableau de TVA imprimé sur le ticket : NONE (aucun), COMBINED (combiné au ticket, sous le total), SEPARATE (séparé, sur un coupon à part après le ticket) ou AMOUNT_ONLY (uniquement le montant de la TVA). Vide : la ventilation TVA du ticket décide (BO-03-09-03).",
                "", null),
        new Def("print.logo-code", Type.TEXT, "TICKETS",
                "Logo imprimé",
                "Code du logo de ticket imprimé en tête des documents de cette caisse, choisi parmi les logos importés dans la bibliothèque d'images. Vide ou code inconnu : le logo résolu pour le magasin (BO-03-09-04).",
                "", null),
        new Def("menu.code", Type.TEXT, "MENU CAISSE",
                "Menu caisse",
                "Code du menu caisse affiché par la caisse, choisi parmi les menus administrés. Posé sur l'îlot, il vaut pour ses caisses ; posé sur la caisse, il l'emporte sur celui de l'îlot ; posé au pays ou à l'enseigne, il en devient le menu par défaut. Vide : toutes les touches (BO-03-09-06, BO-03-10-09, BO-03-10-16, BO-03-10-18).",
                "", null),
        new Def("queue.single-enabled", Type.BOOL, "FILE UNIQUE",
                "File unique activée",
                "La caisse affiche la touche FILE UNIQUE, qui permet à l'hôtesse de déclarer au système tiers de file unique que la caisse est disponible pour le client suivant ou non disponible. Désactivé : ni touche ni envoi (BO-03-09-10).",
                "false", null),
        new Def("queue.single-url", Type.TEXT, "FILE UNIQUE",
                "Adresse du système de file unique",
                "URL à laquelle la caisse publie son état de disponibilité : POST d'un corps JSON portant le terminal et l'état AVAILABLE ou UNAVAILABLE. Vide : l'état n'est pas publié (BO-03-09-10).",
                "", null),
        new Def("hardware.drawer.enabled", Type.BOOL, "TIROIR",
                "Tiroir-caisse activé",
                "Le pilote du tiroir-caisse est actif sur cette caisse : impulsions d'ouverture et lecture du capteur. Désactivé : aucune impulsion n'est envoyée et le tiroir est réputé fermé (BO-03-09-11).",
                "true", null),
        new Def("hardware.drawer.address", Type.TEXT, "TIROIR",
                "Adresse du tiroir-caisse",
                "Adresse du pilote du tiroir transmise au module matériel : port d'entrée-sortie (par exemple 0x48F), port série (par exemple /dev/ttyS0) ou PRINTER pour un tiroir branché sur l'imprimante. Vide : l'adresse de lancement du module (BO-03-09-11).",
                "", null),
        new Def("hardware.scanner.driver", Type.TEXT, "PÉRIPHÉRIQUES",
                "Pilote du scanner",
                "Pilote de lecture du scanner : AUTO (découverte automatique), EVDEV (clavier HID), HIDRAW (HID POS) ou OPOS (pilote OPOS Datalogic, tenu par le module matériel). Une valeur inconnue retombe sur AUTO (BO-03-09-16, BO-03-09-17).",
                "AUTO", null),
        new Def("hardware.scanner.name-patterns", Type.TEXT, "PÉRIPHÉRIQUES",
                "Scanners reconnus",
                "Fragments du nom des périphériques reconnus comme scanner, séparés par des points-virgules, par exemple datalogic;honeywell. Vide : la liste de déploiement de la caisse (BO-03-09-16, BO-03-09-17).",
                "", null),
        new Def("hardware.scanner.opos-device", Type.TEXT, "PÉRIPHÉRIQUES",
                "Périphérique OPOS du scanner",
                "Nom logique OPOS du scanner Datalogic transmis au module matériel quand le pilote est OPOS, par exemple DLS_Magellan. Vide : le périphérique par défaut du pilote (BO-03-09-17).",
                "", null),
        new Def("hardware.scanner2.enabled", Type.BOOL, "PÉRIPHÉRIQUES",
                "Second scanner activé",
                "Un second scanner, côté client, lit en même temps que le premier les cartes de fidélité et les coupons : ses lectures vont à la fidélité et aux bons, jamais aux articles. Désactivé : tous les scanners alimentent la vente (BO-03-09-18).",
                "false", null),
        new Def("hardware.scanner2.name-patterns", Type.TEXT, "PÉRIPHÉRIQUES",
                "Second scanner : périphériques",
                "Fragments du nom des périphériques qui forment le second scanner, séparés par des points-virgules, par exemple zebra;client. Vide : aucun périphérique n'est désigné et le second scanner reste muet (BO-03-09-18).",
                "", null),
        new Def("hardware.printer.type", Type.TEXT, "PÉRIPHÉRIQUES",
                "Type d'imprimante",
                "Modèle de l'imprimante ticket transmis au pilote : ESCPOS (générique), EPSON_TM, WINCOR_TH, IBM_4610 ou TOSHIBA_TCX. Une valeur inconnue retombe sur ESCPOS (BO-03-09-21).",
                "ESCPOS", null),
        new Def("hardware.printer.speed", Type.INT, "PÉRIPHÉRIQUES",
                "Vitesse de l'imprimante (bauds)",
                "Vitesse de communication de l'imprimante ticket transmise au pilote, en bauds (9600, 19200, 38400, 115200). 0 ou valeur négative : la vitesse du pilote (BO-03-09-21).",
                "0", null),
        new Def("hardware.printer.code-page", Type.INT, "PÉRIPHÉRIQUES",
                "Page d'encodage de l'imprimante",
                "Page de code des caractères de l'imprimante ticket, par exemple 858 (Europe de l'Ouest avec l'euro), 850 ou 1252. 0 ou valeur négative : 858 (BO-03-09-21).",
                "858", null),
        new Def("hardware.printer.paper-width", Type.INT, "PÉRIPHÉRIQUES",
                "Largeur du papier (mm)",
                "Taille de page de l'imprimante ticket : 80 ou 58 millimètres. Toute autre valeur : 80 (BO-03-09-21).",
                "80", null),
        new Def("hardware.printer.cheque-flip", Type.BOOL, "PÉRIPHÉRIQUES",
                "Retournement du chèque",
                "L'imprimante retourne le chèque pour en imprimer le recto après la lecture de la ligne magnétique. Désactivé : le chèque n'est pas retourné (BO-03-09-21).",
                "false", null),
        new Def("printer2.enabled", Type.BOOL, "PÉRIPHÉRIQUES",
                "Seconde imprimante activée",
                "Une seconde imprimante est proposée à l'hôtesse pour imprimer un document hors du rouleau de caisse. Désactivé : seuls le rouleau et l'imprimante réseau de déploiement servent (BO-03-09-22).",
                "false", null),
        new Def("printer2.format", Type.TEXT, "PÉRIPHÉRIQUES",
                "Format de la seconde imprimante",
                "Format des documents sortis sur la seconde imprimante : A4 (page A4) ou TICKET (texte du rouleau). Une valeur inconnue retombe sur A4 (BO-03-09-22).",
                "A4", null),
        new Def("printer2.connection", Type.TEXT, "PÉRIPHÉRIQUES",
                "Raccordement de la seconde imprimante",
                "NETWORK (imprimante réseau, l'adresse est une URL) ou LOCAL (imprimante branchée sur la caisse et tenue par le module matériel, l'adresse est le nom de la file d'impression). Une valeur inconnue retombe sur NETWORK (BO-03-09-22).",
                "NETWORK", null),
        new Def("printer2.address", Type.TEXT, "PÉRIPHÉRIQUES",
                "Adresse de la seconde imprimante",
                "URL du service d'impression réseau, ou nom de la file d'impression locale. Vide : la seconde imprimante n'est pas utilisable (BO-03-09-22).",
                "", null),
        new Def("payment.terminal-type", Type.TEXT, "MONÉTIQUE",
                "Type de monétique",
                "Monétique de cette caisse : VERIFONE (monétique centralisée), INGENICO_SIBS (Ingenico, Portugal, SIBS) ou WORLDLINE (Belgique). Vide : la monétique de déploiement. Pris en compte au démarrage de la caisse (BO-03-09-19).",
                "", null),
        new Def("payment.terminal-address", Type.TEXT, "MONÉTIQUE",
                "Adresse de la monétique",
                "Adresse hôte:port du client monétique de cette caisse, par exemple 127.0.0.1:8200. Vide : l'adresse de déploiement (BO-03-09-19).",
                "", null),
        new Def("terminal.island-label", Type.TEXT, "MONÉTIQUE",
                "Label monétique de l'îlot",
                "Label d'îlot transmis dans les requêtes de la monétique centralisée Verifone sous le tag D7V ; il se pose sur l'îlot et vaut pour toutes ses caisses. Vide : aucun tag D7V (BO-03-09-29).",
                "", null),
        new Def("catalina.enabled", Type.BOOL, "FIL DE L'EAU",
                "Flux Catalina activé",
                "Les tickets de vente de cette caisse, lignes articles et moyens de paiement, remontent en temps réel au serveur Catalina déclaré parmi les fournisseurs de service (protocole Catalina). Désactivé : rien n'est envoyé à Catalina (BO-03-09-20).",
                "false", null),
        new Def("export.planexa-enabled", Type.BOOL, "FLUX SORTANTS",
                "Flux Planexa",
                "Le magasin produit le fichier Planexa des transactions par caisse et par tranche horaire, base du planning des hôtesses ; les caisses des îlots exclus du flux Planexa n'y figurent pas (BO-03-10-06).",
                "false", null),

        // ---- lot 19: L parameters ----
        new Def("cheque.guarantee-provider", Type.TEXT, "CHEQUE",
                "Service de garantie chèque",
                "Nom du prestataire de garantie chèque interrogé quand un moyen de paiement chèque porte l'option « Autorisation chèque » (ex. SIMULATEUR en démonstration). La caisse demande l'autorisation au service : accordée, le chèque est pris sans superviseur et le numéro d'autorisation est enregistré sur le règlement ; refusée, le chèque est refusé ; service injoignable, l'autorisation superviseur prend le relais. Vide : aucune garantie, l'autorisation superviseur s'applique comme avant (BO-03-02-24).",
                "", null),
        new Def("cheque.guarantee-url", Type.TEXT, "CHEQUE",
                "Adresse du service de garantie chèque",
                "Adresse de base du service de garantie chèque (la caisse appelle <adresse>/api/cheque-guarantee/authorize). Vide : l'adresse configurée sur la caisse, qui est en développement le service simulé de la caisse elle-même (BO-03-02-24).",
                "", null),
        new Def("cheque.guarantee-timeout-ms", Type.INT, "CHEQUE",
                "Délai de réponse de la garantie chèque (ms)",
                "Délai maximal d'attente de la réponse du service de garantie chèque, en millisecondes ; au-delà, le service est réputé injoignable et l'autorisation superviseur prend le relais (BO-03-02-24).",
                "3000", null),
        new Def("deposit.article-eans", Type.TEXT, "RETOUR CLIENT",
                "Articles de consigne",
                "Codes EAN des articles de consigne (bouteille, casier, contenant consigné), séparés par des points-virgules. Une ligne de vente de ces articles alimente le côté consigne de l'état consigne / déconsigne, une ligne reprise le côté déconsigne. Vide : seuls les retours déconsigne et les bons de déconsigne alimentent l'état (BO-03-13-11).",
                "", null),
        new Def("payment.card-receipt-printed", Type.BOOL, "MONETIQUE",
                "Impression du ticket carte bancaire",
                "Le ticket carte bancaire est imprimé : associé au ticket de vente ou sur un ticket séparé selon le paramètre « Ticket monétique attaché au ticket de vente ». Désactivé : aucun ticket carte n'est imprimé, sauf celui que le client doit signer ; le ticket reste archivé pour un duplicata (BO-03-02-34).",
                "true", null),
        new Def("payment.degraded-release-endorsement", Type.BOOL, "MONÉTIQUE",
                "Autorisation superviseur pour lever le mode dégradé",
                "La levée du mode dégradé monétique forcé en caisse exige un aval superviseur. Désactivé : l'opérateur le lève seul (LC-07-08-04, BO-03-02-50).",
                "true", null),

        // ---- lot 19: M parameters ----
        new Def("ticket.family-level", Type.INT, "TICKETS",
                "Niveau de nomenclature du regroupement des articles",
                "Quand les articles sont regroupés par famille, le niveau de la nomenclature sous lequel ils sont rassemblés : 1 pour le premier niveau (rayon), 2 pour le deuxième, et ainsi de suite. L'intitulé imprimé est le libellé du nœud de ce niveau. 0 : la famille de l'article, comme avant. Un niveau que la nomenclature n'a pas, ou un article sans nomenclature, garde sa propre famille (BO-03-03-17).",
                "0", null),
        new Def("ticket.promotion-placement", Type.TEXT, "TICKETS",
                "Emplacement des lignes de promotion",
                "Où le ticket imprime les promotions du moteur : LINE sous l'article concerné, FOOTER regroupées au pied du ticket, ENGINE selon l'emplacement porté par chaque avantage (sous l'article quand le moteur le demande, au pied sinon). Valeur inconnue : ENGINE (BO-03-03-23).",
                "ENGINE", null),
        new Def("invoice.article-attributes", Type.TEXT, "FACTURE",
                "Attributs article imprimés sur la facture",
                "Codes des attributs article imprimés sous chaque ligne de la facture et du bon de livraison, séparés par des points-virgules (ex. ORIGINE;CATEGORIE;VARIETE). Chaque attribut s'imprime sous la forme « libellé : valeur », un attribut sans valeur pour l'article est omis. Ces attributs ne sont jamais imprimés sur le ticket de caisse. Vide : seule la famille de l'article est rappelée (BO-03-03-21).",
                "", null),
        new Def("invoice.a4-lines-per-page", Type.INT, "FACTURE",
                "Lignes articles par page A4",
                "Nombre de lignes articles d'une page de facture A4 : au-delà, le document passe sur une nouvelle page, chaque page reprenant l'entête et la mention « page X / Y ». 0 : une seule page (BO-03-03-02).",
                "25", null),
        new Def("invoice.account-number-required", Type.BOOL, "FACTURE",
                "Numéro de compte client obligatoire",
                "Une facture ou un bon de livraison ne peut être émis que pour un client portant un numéro de compte client, imprimé dans le bloc client du document. Désactivé : le numéro s'imprime quand le client en a un (BO-03-04-16).",
                "false", null),
        new Def("invoice.customer-validation", Type.BOOL, "FACTURE",
                "Validation des données client avant impression",
                "Après la sélection du client, la caisse affiche ses données (adresse, SIRET, TVA, NIF, compte) pour que l'hôte de caisse les valide ou les modifie avant l'impression du document. Désactivé : le document s'affiche directement, sans étape de validation (BO-03-04-18).",
                "true", null),
        new Def("fidelity.short-message-printed", Type.BOOL, "FIDÉLITÉ",
                "Message avantage fidélité 2 lignes",
                "Le message court (2 lignes) transmis par le programme de fidélité s'imprime dans la zone avantage carte, avant la liste des avantages de la transaction. Désactivé : il n'est pas imprimé (BO-03-03-26).",
                "true", null),
        new Def("fidelity.commercial-messages-max", Type.INT, "FIDÉLITÉ",
                "Messages commerciaux fidélité 10 lignes",
                "Nombre maximal de messages commerciaux (10 lignes chacun) transmis par le programme de fidélité imprimés en fin de section fidélité du ticket, de 0 à 5. 0 : aucun message commercial n'est imprimé (BO-03-03-27).",
                "5", null),

        // ---- lot 19: N parameters ----

        // ---- lot 20: P parameters ----
        new Def("article.complementary-suggestions", Type.BOOL, "RÉFÉRENTIEL ARTICLES",
                "Proposer les articles complémentaires",
                "À la vente d'un article qui déclare des articles complémentaires, la caisse affiche leurs libellés pour que l'hôtesse les propose au client. Désactivé : rien n'est proposé (BO-02-03-16).",
                "true", null),
        new Def("article.complementary-max", Type.INT, "RÉFÉRENTIEL ARTICLES",
                "Nombre d'articles complémentaires proposés",
                "Nombre maximal d'articles complémentaires affichés à la vente d'un article, de 1 à 10 (BO-02-03-16).",
                "3", null),
        new Def("article.meal-voucher-eligible-only", Type.BOOL, "RÉFÉRENTIEL ARTICLES",
                "Titre-restaurant limité aux articles éligibles",
                "Sans avantage du moteur de valorisation, un titre-restaurant ne règle que les articles portant le drapeau d'éligibilité titre-restaurant ; un ticket sans article éligible refuse le titre. Désactivé : un ticket sans article éligible accepte le titre comme avant (BO-02-03-06).",
                "true", null),
        new Def("article.deposit-control", Type.BOOL, "RÉFÉRENTIEL ARTICLES",
                "Contrôle de dépose en caisse libre-service",
                "La caisse publie à la balance de sécurité de la caisse libre-service le poids attendu de chaque ligne — poids pesé pour un article pesé, poids de référence sinon — et retire du contrôle les articles encombrants. Désactivé : aucune ligne n'est contrôlée (BO-02-03-24, BO-02-03-25).",
                "true", null),
        new Def("eeg.enabled", Type.BOOL, "RÉFÉRENTIEL ARTICLES",
                "Flux étiquettes électroniques (EEG)",
                "Active la lecture du prix affiché par les étiquettes électroniques sur la fiche article et l'envoi des prix d'une liste d'articles à tous les fournisseurs d'EEG actifs du point de vente. Désactivé : aucun appel aux fournisseurs (BO-02-03-37, BO-02-03-38).",
                "false", null),

        // ---- lot 20: Q parameters ----
        new Def("channel.commercial.customer-uplink-url", Type.TEXT, "CANAUX EXTERNES",
                "Gestion commerciale : adresse de remontée des clients",
                "Adresse HTTP de la gestion commerciale à laquelle le nœud magasin envoie (POST, JSON) chaque fiche client en compte créée ou modifiée au back-office ou en caisse. Une remontée n'est acquittée que par une réponse 2xx ; sans acquittement elle est rejouée jusqu'à ce qu'elle passe. Vide : les modifications attendent et partent dès que l'adresse est renseignée (BO-02-04-13).",
                "", null),
        new Def("channel.commercial.customer-uplink-seconds", Type.INT, "CANAUX EXTERNES",
                "Gestion commerciale : délai entre deux rejeux (secondes)",
                "Délai entre deux tentatives d'envoi des fiches clients que la gestion commerciale n'a pas encore acquittées, de 5 à 3600 secondes (BO-02-04-13).",
                "60", null),

        // ---- lot 20: R parameters ----

        // ---- lot 21: S parameters ----
        new Def("journal.secondary-monetic-servers", Type.TEXT, "SUPERVISION",
                "Serveurs monétiques secondaires",
                "Codes du serveur d'acceptation (balise VWAY rendue par le terminal) qui désignent le serveur secondaire du mode dégradé — 4, celui que le mode dégradé force (LC-07-08-02) —, séparés par des points-virgules. Le journal électronique retrouve les tickets réglés par carte sur l'un de ces serveurs, à destination du service support. Vide : aucun serveur n'est tenu pour secondaire (BO-04-01-48).",
                "4", null),
        new Def("auth.endorsement-profiles", Type.TEXT, "GESTES NÉGATIFS",
                "Profils habilités à donner un aval",
                "Noms des profils dont le titulaire peut avaliser un geste en caisse, quel que soit son rôle, séparés par des points-virgules. Une règle d'aval par fonction peut aussi nommer ses propres profils après le mode, par exemple POST /action/pay-tender=REQUIRED:Chef de caisse. Vide : seuls les rôles habilités avalisent (BO-04-02-01).",
                "", null),
        new Def("control.alert-rules", Type.TEXT, "POINTS DE CONTRÔLE",
                "Alertes de caisse",
                "Une alerte par ligne : cible = type ; options. Cibles : function:POST /abandon (une fonction), payment:CHEQUE (un moyen de paiement), article:3017620422003 ou article:F1020 (un article ou une famille), barcode:20* (un préfixe de code-barres), offline (caisse hors ligne), expired (date limite dépassée lue sur un code GS1). Types : YESNO (question oui/non en caisse), SUPERVISOR (validation superviseur), BACKOFFICE (alerte d'information au back-office), BLOCK (fonction bloquée). Options séparées par des points-virgules : text=message affiché, notify (notification à l'hôte(sse) de caisse), max=n (nombre de fois dans la transaction). Vide : aucune alerte (BO-04-03-08, BO-04-03-09).",
                "", null),
        new Def("justification.function-routes", Type.TEXT, "POINTS DE CONTRÔLE",
                "Fonctions de caisse justifiées",
                "Une fonction par ligne : méthode chemin=code de fonction, par exemple POST /abandon=CANCEL_TICKET. Quand une liste de justifications sert ce code de fonction, la caisse demande la justification avant d'exécuter la fonction, la trace au journal électronique puis rejoue la fonction. Une fonction sans liste administrée s'exécute sans question (BO-04-03-10, BO-04-03-12).",
                "POST /abandon=CANCEL_TICKET\nPOST /action/price-mod/submit=PRICE_MOD", null),
        new Def("control.indicators-bar", Type.BOOL, "SUPERVISION",
                "Indicateurs de contrôle sur tous les écrans",
                "Affiche en tête de chaque écran du back-office, pour les profils qui ont accès aux indicateurs de contrôle, le CA du jour, le CA par rayon ou famille, le panier moyen en quantité, le nombre de clients et le comparatif avec l'année précédente. Désactivé : les indicateurs ne sont plus affichés que sur leur propre écran (BO-04-03-01).",
                "true", null),
        new Def("control.revenue-level", Type.INT, "SUPERVISION",
                "Niveau de nomenclature du détail du CA",
                "Rang du niveau de nomenclature auquel le CA du jour est détaillé (1 : rayon, 2 : famille, et ainsi de suite sur toute la nomenclature). 0 ou un niveau inconnu : la famille de rattachement de l'article (BO-04-03-01).",
                "1", null),
        new Def("control.revenue-top", Type.INT, "SUPERVISION",
                "Nombre de rubriques du détail du CA",
                "Nombre de rayons ou familles affichés dans le détail du CA, les plus forts d'abord, de 1 à 20 (BO-04-03-01).",
                "5", null),
        new Def("audit.watched-menus", Type.TEXT, "SUPERVISION",
                "Menus du back-office surveillés",
                "Écrans du back-office dont chaque ouverture est tracée au journal électronique, séparés par des points-virgules : un code d'écran (journal, settings, account...) ou un début de chemin (/admin/journal). * : tous les écrans. Vide : seules les connexions sont tracées (BO-04-03-04).",
                "", null),
        new Def("supervision.global-alerts", Type.BOOL, "SUPERVISION",
                "Alertes de caisse sur tous les écrans",
                "Une demande d'autorisation ou de service émise par une caisse s'affiche immédiatement en tête de chaque écran du back-office, pour les profils qui ont accès à la supervision, avec ses options de traitement (autoriser, à traiter en caisse, refuser, acquitter). Désactivé : les demandes ne s'affichent que sur le tableau de bord (BO-04-02-12).",
                "true", null),
        new Def("supervision.live-event-types", Type.TEXT, "SUPERVISION",
                "Opérations du suivi en temps réel",
                "Types d'opérations affichés par l'écran de surveillance des caisses en temps réel, séparés par des points-virgules (TICKET_CLOSED, TICKET_CANCELLED, LINE_CANCELLED, PRICE_MODIFIED, DRAWER_OPENED, REFUND_CREATED, PAYMENT_CANCELLED, CONTROL_ALERT...). Vide : toutes les opérations (BO-04-03-07).",
                "TICKET_CLOSED;TICKET_CANCELLED;LINE_CANCELLED;PRICE_MODIFIED;DRAWER_OPENED;DRAWER_FORCED_OPEN;REFUND_CREATED;PAYMENT_CANCELLED;ENDORSEMENT_GRANTED;ENDORSEMENT_DENIED;CONTROL_ALERT;JUSTIFICATION_CHOSEN", null),

        // ---- lot 21: T parameters ----
        new Def("integration.pull-feeds", Type.TEXT, "CANAUX EXTERNES",
                "Flux d'intégration : flux récupérés",
                "Codes des flux que le nœud magasin va chercher, séparés par « ; », à l'adresse du fournisseur de service de type Service commercial en protocole HTTP/JSON déclaré à l'écran Fournisseurs de service : articles PRODUCTS, familles FAMILIES, clients CUSTOMERS, TVA VAT_RATES, nomenclature NOMENCLATURE=<schéma>. Chaque flux est lu en GET sur <adresse>/<code> avec la clé du fournisseur, et intégré comme un dépôt. Un fichier identique au dernier intégré n'est pas rejoué. Vide : rien n'est récupéré (BO-11-01-01).",
                "", null),
        new Def("integration.pull-minutes", Type.INT, "CANAUX EXTERNES",
                "Flux d'intégration : fréquence (minutes)",
                "Intervalle entre deux récupérations des flux d'intégration, de 1 à 1440 minutes (BO-11-01-01).",
                "60", null),
        new Def("export.family-sales-upstream-offers", Type.TEXT, "FLUX SORTANTS",
                "Offres remboursées par l'amont",
                "Débuts de libellé d'offre du moteur de promotion, séparés par « ; », dont les remises immédiates sont remboursées par l'amont : leur montant, porté par chaque ligne remisée, alimente la colonne REMISES_AMONT du flux CA par famille et par îlot. Vide : toutes les remises immédiates du moteur sont réputées remboursées par l'amont (BO-11-01-08).",
                "", null),
        new Def("export.gs1-payments-enabled", Type.BOOL, "FLUX SORTANTS",
                "Flux paiements GS1 et bons scannés",
                "Produit chaque jour le fichier des codes de paiement lus en caisse — bons GS1 (DataMatrix, QR, Databar), bons d'achat, avoirs, cartes — avec leur caisse, leur ticket, leur montant et leur état de consommation, et des bons réglés sur les tickets clôturés (BO-11-01-18).",
                "false", null),
        new Def("gs1.central-burn", Type.BOOL, "CODES GS1",
                "Consommation centralisée des bons",
                "Avant d'accepter un bon GS1, la caisse demande au nœud magasin s'il a déjà été réglé sur un ticket d'une caisse du magasin ; un bon déjà consommé est refusé. Désactivé : seule la vente en cours est contrôlée (BO-11-01-18).",
                "false", null),
        new Def("vignette.enabled", Type.BOOL, "VIGNETTES",
                "Gestion des vignettes",
                "La caisse calcule le nombre théorique de vignettes de chaque ticket selon la formule ci-dessous, l'imprime sur le ticket et offre l'écran de déclaration des vignettes distribuées par la caissière sur sa prise de poste (BO-11-01-20).",
                "false", null),
        new Def("vignette.amount-step", Type.TEXT, "VIGNETTES",
                "Vignettes : tranche d'achat (€)",
                "Montant TTC d'achat ouvrant droit aux vignettes : le nombre théorique d'un ticket vaut le nombre de tranches entières de ce montant multiplié par le nombre de vignettes par tranche. 0 : aucune vignette théorique (BO-11-01-20).",
                "10.00", null),
        new Def("vignette.per-step", Type.INT, "VIGNETTES",
                "Vignettes par tranche",
                "Nombre de vignettes distribuées par tranche d'achat entière (BO-11-01-20).",
                "1", null),
        new Def("export.vignettes-enabled", Type.BOOL, "FLUX SORTANTS",
                "Flux vignettes théoriques et déclarées",
                "Produit chaque jour, par caisse et par prise de poste, le nombre de tickets, de vignettes théoriques calculées selon la formule administrée et de vignettes déclarées par la caissière, et leur écart (BO-11-01-20).",
                "false", null),
        new Def("kiosk.enabled", Type.BOOL, "RÉFÉRENTIEL ARTICLES",
                "Exposition des prix aux bornes de prix",
                "Le nœud magasin répond aux bornes de prix déclarées à l'écran Fournisseurs de service (type Borne de prix) sur GET /api/price-kiosk/<EAN>, la clé du fournisseur présentée en en-tête Authorization: Bearer : libellé, prix TTC, prix au kilo ou au litre. Désactivé : toute demande est refusée (BO-11-02-06).",
                "false", null),
        new Def("kiosk.format", Type.TEXT, "RÉFÉRENTIEL ARTICLES",
                "Format de réponse des bornes de prix",
                "Format attendu par le protocole de la borne : JSON, XML ou TEXT (une ligne EAN|libellé|prix). Toute autre valeur : JSON (BO-11-02-06).",
                "JSON", null),
        new Def("eeg.auto-push-enabled", Type.BOOL, "RÉFÉRENTIEL ARTICLES",
                "Envoi automatique des prix aux étiquettes",
                "Chaque prix créé, modifié ou entré en vigueur est envoyé de lui-même à tous les fournisseurs d'étiquettes électroniques actifs du point de vente ; au premier passage, tous les prix courants partent. Un fournisseur qui n'a pas répondu reçoit le passage suivant tout ce qu'il a manqué. Désactivé : seul l'envoi manuel d'une liste d'articles s'applique (BO-11-03-05).",
                "false", null),
        new Def("eeg.auto-push-seconds", Type.INT, "RÉFÉRENTIEL ARTICLES",
                "Cadence d'envoi aux étiquettes (secondes)",
                "Intervalle entre deux envois automatiques des prix aux étiquettes électroniques, de 10 à 86400 secondes (BO-11-03-05).",
                "300", null),
        new Def("hardware.cashchanger.movements", Type.BOOL, "PÉRIPHÉRIQUES",
                "Apports et prélèvements par le monnayeur",
                "Un apport de monnaie en espèces est versé dans le monnayeur, qui compte ce qu'il a accepté ; un prélèvement d'espèces est délivré par le monnayeur, qui rend ce qu'il a distribué ; c'est le montant du monnayeur qui est enregistré. L'écran Monnayeur affiche le niveau d'espèces par coupure. Désactivé : apports et prélèvements se comptent à la main (BO-11-03-03).",
                "false", null),
        new Def("nexo.hub-url", Type.TEXT, "PRODUITS DÉMATÉRIALISÉS",
                "Adresse du serveur NEXO Retailer",
                "Adresse de base du serveur des produits dématérialisés (cartes et coffrets cadeaux) que la caisse appelle en protocole NEXO Retailer, sur <adresse>/api/nexo. Vide : la propriété de déploiement pos.nexo.hub-url s'applique (BO-11-03-04).",
                "", null),
        new Def("nexo.poi-id", Type.TEXT, "PRODUITS DÉMATÉRIALISÉS",
                "Identifiant POI NEXO",
                "Identifiant POIID que la caisse adresse sur le serveur NEXO Retailer. Vide : la propriété de déploiement pos.nexo.poi-id s'applique (BO-11-03-04).",
                "", null),
        new Def("nexo.activation-moment", Type.TEXT, "PRODUITS DÉMATÉRIALISÉS",
                "Moment de l'échange NEXO à la vente",
                "SCAN : le produit dématérialisé est activé dès sa vente, au scan de son code. PAYMENT : il est activé à l'entrée en paiement, tous les produits du ticket ensemble ; un produit refusé sort du ticket avant tout règlement (BO-11-03-04).",
                "SCAN", null),
        new Def("nexo.payment-acknowledgement", Type.BOOL, "PRODUITS DÉMATÉRIALISÉS",
                "Acquittement des paiements par carte prépayée",
                "À la clôture du ticket, chaque paiement par carte prépayée validé par le serveur NEXO Retailer lui est acquitté par un message Payment de type Completion. Désactivé : seule la validation du paiement est demandée (BO-11-03-04).",
                "false", null),
        new Def("channel.ecommerce.valuation-enabled", Type.BOOL, "CANAUX EXTERNES",
                "E-commerce : moteur de valorisation exposé",
                "La plateforme e-commerce fait valoriser ses paniers par le moteur de promotion du point de vente sur POST /api/ecommerce/valuation, avec le même jeton que le dépôt des commandes. Désactivé : la demande est refusée (BO-11-04-03).",
                "false", null),
        new Def("channel.ecommerce.status-return", Type.BOOL, "CANAUX EXTERNES",
                "E-commerce : retour du statut d'intégration",
                "Le compte rendu d'intégration de chaque commande e-commerce — intégrée, rejetée, canal fermé —, avec le numéro du point de vente, est renvoyé aux fournisseurs de service de type E-commerce, rejoué jusqu'à son acquittement. Désactivé : la plateforme ne lit le statut qu'en le demandant (BO-11-04-03).",
                "false", null),
        new Def("fidelity.vt-sync", Type.BOOL, "FIDÉLITÉ",
                "Appel fidélité VT synchrone",
                "À la fin du ticket, la caisse envoie le panier au service fidélité et attend sa réponse (appel VT), dans le délai de réponse administré. Un envoi non abouti est confié au renvoi GT. Désactivé : le panier part en différé par la file d'envoi (BO-11-04-05).",
                "false", null),
        new Def("fidelity.vt-payments", Type.BOOL, "FIDÉLITÉ",
                "Données de paiement dans l'appel VT",
                "L'appel VT porte l'ensemble des règlements du ticket : moyen, montant, référence d'autorisation. Désactivé : il ne porte que le panier valorisé (BO-11-04-05).",
                "false", null),
        new Def("fidelity.gt-enabled", Type.BOOL, "FIDÉLITÉ",
                "Renvoi GT des transactions non abouties",
                "Les transactions fidélité qui n'ont pas abouti en fin de ticket sont renvoyées au service fidélité par l'appel GT, à la cadence ci-dessous, jusqu'à leur acceptation. Désactivé : elles restent en attente et ne sont pas renvoyées (BO-11-04-06).",
                "true", null),
        new Def("fidelity.gt-seconds", Type.INT, "FIDÉLITÉ",
                "Cadence du renvoi GT (secondes)",
                "Intervalle entre deux renvois GT des transactions fidélité non abouties, de 5 à 3600 secondes (BO-11-04-06).",
                "10", null),
        new Def("fidelity.gt-path", Type.TEXT, "FIDÉLITÉ",
                "Chemin de l'appel GT",
                "Chemin, sur l'adresse du service fidélité, auquel l'appel GT renvoie une transaction non aboutie. Vide : le chemin de l'appel VT (/api/events/ticket-closed, /api/events/ticket-return) (BO-11-04-06).",
                "", null),
        new Def("fidelity.vt2-enabled", Type.BOOL, "FIDÉLITÉ",
                "Appel fidélité VT avant impression",
                "Avant l'impression du ticket, la caisse rappelle le service fidélité avec la transaction en cours, paiement fidélité compris, et imprime le solde de la carte qu'il renvoie. Désactivé : le solde imprimé est celui de la dernière valorisation (BO-11-04-07).",
                "false", null),

        // ---- lot 21: U parameters ----
        new Def("fiscal.archive-auto", Type.BOOL, "FISCAL",
                "Archives fiscales mensuelles automatiques",
                "Le nœud magasin produit lui-même, dès le début de chaque mois, les archives fiscales du mois écoulé TaxAuditExtracts_XXXXX_aaaammjj.zip (tickets, lignes, règlements, TVA, retours, factures, sessions, mouvements) et TaxAuditReports_XXXXX_aaaammjj.zip (chiffre d'affaires journalier, TVA par taux, règlements par moyen, sessions), mises à disposition du contrôleur de gestion à l'écran Archives fiscales. Désactivé : les archives ne se produisent qu'à la demande (BO-05-01-01).",
                "true", null),
        new Def("fiscal.online-retention-years", Type.INT, "FISCAL",
                "Conservation en ligne des tickets et factures (années)",
                "Durée pendant laquelle les tickets, factures, avoirs et documents fiscaux restent consultables, extractibles et imprimables à l'écran Export des transactions : aucune purge de rétention ne supprime une vente ou une facture plus récente, quel que soit le délai de rétention administré par ailleurs (BO-05-01-06).",
                "12", null),
        new Def("fiscal.einvoicing-enabled", Type.BOOL, "FISCAL",
                "Facturation électronique (e-invoicing)",
                "Chaque facture émise par les caisses à un client professionnel assujetti en France (SIRET ou TVA FR) est convertie au format structuré UN/CEFACT CII (profil EN 16931) et déposée sur la plateforme de dématérialisation désignée, avec suivi de son cycle de vie (déposée, rejetée, mise à disposition, approuvée, refusée, encaissée…) ; la caisse imprime la mention de facture électronique sur la facture. Désactivé : aucune facture n'est transmise (BO-05-01-02).",
                "false", null),
        new Def("fiscal.einvoicing-mention", Type.TEXT, "FISCAL",
                "Mention de facture électronique",
                "Texte que la caisse imprime au pied de chaque facture lorsque la facturation électronique est active (catégorie de l'opération, transmission par plateforme agréée). Vide : aucune mention (BO-05-01-02).",
                "Facture électronique transmise par plateforme de dématérialisation agréée — catégorie de l'opération : livraison de biens.", null),
        new Def("fiscal.pdp-provider", Type.TEXT, "FISCAL",
                "Plateforme de dématérialisation (code du fournisseur)",
                "Code du fournisseur de service de type Fiscal (écran Fournisseurs de service) qui joue la plateforme de dématérialisation : son adresse et sa clé servent au dépôt des factures électroniques et à la transmission des données d'e-reporting (BO-05-01-02, BO-05-01-03).",
                "", null),
        new Def("fiscal.ereporting-enabled", Type.BOOL, "FISCAL",
                "E-reporting des transactions",
                "À la fin de chaque période, le nœud magasin extrait les transactions B2C et B2B internationales consolidées (ventes et retours, par jour et par taux de TVA), les met au format de transmission et les transmet à la plateforme de dématérialisation, puis suit l'accusé de réception ; un envoi en échec est rejoué. Désactivé : les périodes ne se transmettent qu'à la demande (BO-05-01-03).",
                "false", null),
        new Def("fiscal.ereporting-period", Type.TEXT, "FISCAL",
                "Périodicité de l'e-reporting",
                "DECADE : une transmission par décade (du 1er au 10, du 11 au 20, du 21 à la fin du mois) ; MONTH : une transmission par mois (BO-05-01-03).",
                "DECADE", null),
        new Def("fiscal.saft-realtime", Type.BOOL, "FISCAL",
                "SAF-T au fil de l'eau",
                "Chaque document fiscal portugais remonté par une caisse (FS, FT, NC, RC) est transmis une fois, aussitôt, à chaque fournisseur de service actif de protocole SAFT : au format RegisterInvoice au service de l'administration fiscale (type Fiscal) pour les factures et avoirs, au format SAF-T (PT) aux autres ; un envoi en échec est rejoué, et les données restent consultables par l'API /api/fiscal/saft/documents. Désactivé : aucun envoi au fil de l'eau (BO-05-01-05, BO-11-04-02).",
                "false", null),
        new Def("fiscal.saft-deadline-day", Type.INT, "FISCAL",
                "SAF-T mensuel : jour limite d'extraction",
                "Jour du mois avant lequel le point de vente extrait et transmet le fichier SAF-T du mois précédent ; l'écran Export SAF-T le rappelle et signale le retard, de 1 à 28 (BO-05-01-04).",
                "25", null),
        new Def("fiscal.transmission-retry-seconds", Type.INT, "FISCAL",
                "Délai entre deux tentatives de transmission fiscale (secondes)",
                "Délai avant de rejouer un dépôt de facture électronique, une transmission d'e-reporting ou un envoi SAF-T en échec, de 10 à 86400 secondes (BO-05-01-02, BO-05-01-03, BO-05-01-05).",
                "300", null),
        new Def("safe.pouch-requires-day-validation", Type.BOOL, "COFFRE",
                "Remise au coffre après validation de la journée",
                "Une pochette ne se déclare qu'une fois la journée comptable validée à l'écran Historique des journées : chaque mouvement des caissières validé, puis le chiffre d'affaires net de la journée. Désactivé : une pochette se déclare à tout moment (BO-05-02-10).",
                "true", null),
        new Def("safe.pouches-per-day", Type.INT, "COFFRE",
                "Nombre de pochettes par journée comptable",
                "Nombre maximal de pochettes remises au coffre pour une même journée comptable ; 0 : sans limite (BO-05-02-10).",
                "2", null),
        new Def("safe.forcing-level-control", Type.BOOL, "COFFRE",
                "Contrôle du niveau de forçage",
                "Un mouvement du coffre saisi par un salarié portant un niveau de forçage ne se valide que par un salarié de niveau strictement supérieur. Désactivé : tout valideur autorisé valide (BO-05-02-15).",
                "true", null),
        new Def("safe.advance-limit-control", Type.BOOL, "COFFRE",
                "Contrôle du plafond d'acompte",
                "Une dépense du coffre faite à un salarié qui porte un plafond d'acompte est refusée quand elle porterait le total qui lui a été versé au-delà de ce plafond. Désactivé : le plafond est indicatif (BO-05-02-15).",
                "true", null),

        // ---- lot 21: V parameters ----
        new Def("report.express-max-items", Type.INT, "RAPPORTS",
                "Commande express : nombre maximal d'articles",
                "Une commande d'au plus ce nombre d'articles est une commande express, au-delà une commande normale, dans le profil des performances caissières ; en dessous de 1 : 10 articles (BO-06-06-02).",
                "10", null),
        new Def("report.station-families", Type.TEXT, "RAPPORTS",
                "Familles de la station-service",
                "Codes des familles (ou des rayons, activités) de la station-service, séparés par des points-virgules : leur chiffre d'affaires est exclu de l'état TVA par nomenclature. Vide : le point de vente n'a pas de station (BO-06-07-01).",
                "", null),

        // ---- lot 21: W parameters ----
        new Def("channel.integration.token-required", Type.BOOL, "CANAUX EXTERNES",
                "Jeton de système tiers obligatoire",
                "Une vente déposée par un système tiers (e-commerce, mobilité) doit présenter le jeton du système déclaré à l'écran Systèmes tiers, dans l'en-tête X-Integration-Token ; le jeton de synchronisation des caisses n'y suffit plus. Désactivé : le jeton d'un système déclaré l'identifie, et le jeton de synchronisation reste accepté (BO-07-01-01).",
                "false", null),
        new Def("channel.ecommerce.fidelity-earn", Type.BOOL, "CANAUX EXTERNES",
                "Cagnottage fidélité des commandes e-commerce",
                "Une commande e-commerce intégrée portant une carte de fidélité déclenche son cagnottage : l'événement de ticket clôturé, canal Web, part vers le programme de fidélité par la file d'envoi du nœud magasin, comme un ticket de caisse. Désactivé : la commande n'est pas cagnottée (BO-07-01-03).",
                "true", null),
        new Def("period.auto-launch", Type.BOOL, "FIN DE PÉRIODE",
                "Lancement automatique de la fin de période",
                "À l'heure de fin de période, le nœud magasin lance seul le traitement de fin de période : points d'entrée techniques, enregistrement des tickets en attente, contrôle des caisses, états financiers, clôture consolidée, éditions programmées, volumes de vente, purge de rétention. Désactivé : rien ne part tout seul, le traitement se lance depuis l'écran Fin de journée (BO-09-03-03).",
                "true", null),
        new Def("period.parked-record", Type.BOOL, "FIN DE PÉRIODE",
                "Enregistrement des tickets en attente à la fin de journée",
                "La fin de journée, automatique ou lancée depuis le back-office, se fait avec les tickets en attente : chacun est enregistré — caisse, numéro, caissière, articles, montant — et inscrit au journal électronique. Désactivé : seule la clôture Z en caisse écrit leur sort (BO-09-01-05, BO-09-02-01).",
                "true", null),
        new Def("period.parked-raz", Type.BOOL, "FIN DE PÉRIODE",
                "Remise à zéro automatique des tickets en attente",
                "À la fin de période automatique, les tickets restés en attente sont remis à zéro : chaque caisse reçoit l'ordre de les annuler et en trace la purge, comme à la clôture Z. Désactivé : la remise à zéro se commande depuis l'écran Fin de journée (BO-09-01-08).",
                "false", null),
        new Def("period.forced-close-ticket", Type.BOOL, "FIN DE PÉRIODE",
                "Ticket technique à la fermeture forcée",
                "Une caisse fermée d'office par la fin de période ou depuis le back-office imprime un ticket technique — caisse, session, opérateur, cause, tickets en attente — et l'inscrit au journal électronique. Désactivé : seul le ticket de fermeture paramétré s'imprime (BO-09-01-06, BO-09-02-03).",
                "true", null),
        new Def("period.sco-auto-declaration", Type.BOOL, "FIN DE PÉRIODE",
                "Déclaration automatique des hôtes des îlots libre-service",
                "À la fin de période, la session d'un hôte rattaché à un îlot de caisses libre-service en déclaration automatique est clôturée sur son montant théorique, sans comptage, et son Z s'imprime. Les hôtes se rattachent à l'îlot à l'écran Îlots de caisse ; un îlot sans hôte désigné prend tous les hôtes de ses caisses (BO-09-03-01).",
                "false", null),
        new Def("period.hooks-enabled", Type.BOOL, "FIN DE PÉRIODE",
                "Points d'entrée techniques de fin de période",
                "Les scripts désignés ci-dessous sont exécutés avant et après le traitement de fin de période, automatique ou manuel ; leur code retour et leur sortie sont conservés et inscrits au journal. Désactivé : aucun script n'est exécuté (BO-09-02-04).",
                "false", null),
        new Def("period.hook-before", Type.TEXT, "FIN DE PÉRIODE",
                "Script avant fin de période",
                "Nom du script exécuté avant le traitement de fin de période, pris dans le répertoire des points d'entrée du nœud magasin (lettres, chiffres, point, tiret, souligné). Vide : rien avant (BO-09-02-04).",
                "", null),
        new Def("period.hook-after", Type.TEXT, "FIN DE PÉRIODE",
                "Script après fin de période",
                "Nom du script exécuté après le traitement de fin de période, pris dans le répertoire des points d'entrée du nœud magasin. Vide : rien après (BO-09-02-04).",
                "", null),
        new Def("period.hook-timeout-seconds", Type.INT, "FIN DE PÉRIODE",
                "Durée maximale d'un script de fin de période (s)",
                "Au-delà, le script est arrêté et son exécution notée en échec ; le traitement de fin de période continue. En dessous de 1 : 60 secondes (BO-09-02-04).",
                "60", null),
        new Def("period.register-check", Type.BOOL, "FIN DE PÉRIODE",
                "Vérification caisse / serveur en fin de période",
                "Pendant la fin de période, chaque caisse compare ses ventes du jour à celles que le serveur a reçues ; en cas d'écart elle renvoie ses ventes et resynchronise son référentiel, et le résultat est inscrit au journal. Désactivé : seul le contrôle continu des référentiels s'applique (BO-09-03-05).",
                "false", null),
        new Def("period.reports-printer", Type.TEXT, "FIN DE PÉRIODE",
                "Imprimante des états de fin de période",
                "Imprimante réseau, au format hôte:port (port 9100 par défaut), à laquelle chaque état édité automatiquement en fin de période est envoyé dès son édition. Vide : les états sont seulement conservés et téléchargeables depuis l'écran Fin de journée (BO-09-01-10).",
                "", null),
        new Def("touch.article-sort-volume", Type.BOOL, "TOUCHES CAISSE",
                "Articles des groupes triés par volume de vente",
                "Les articles d'un groupe de l'écran de saisie directe sont affichés du plus vendu au moins vendu, selon le volume de vente calculé par le nœud magasin sur les ventes des caisses. Désactivé : ils sont affichés par ordre alphabétique de libellé, le tri appliqué par défaut quand aucun tri n'est choisi en caisse (BO-03-01-10, BO-03-01-13).",
                "false", null),
        new Def("touch.sales-volume-days", Type.INT, "TOUCHES CAISSE",
                "Période du volume de vente (jours)",
                "Nombre de jours de ventes des caisses sur lesquels le nœud magasin calcule le volume de vente des articles et des groupes, recalculé à chaque fin de période ou à la demande depuis l'écran des touches. En dessous de 1 la valeur par défaut s'applique (BO-03-01-13).",
                "30", null),
        new Def("valuation.local-only", Type.BOOL, "SCAN",
                "Moteur de promotion non appelé",
                "La caisse ne fait plus appel au moteur de promotion et valorise le panier seule ; les codes-barres destinés au moteur sont alors refusés au scan. Désactivé : la caisse appelle le moteur configuré sur le nœud et lui transmet les codes-barres scannés (BO-03-06-42).",
                "false", null),

        // ---- lot 21: Z parameters ----
        new Def("fiscal.at-subuser", Type.TEXT, "FISCAL",
                "Sous-utilisateur du webservice de l'AT",
                "Identifiant du sous-utilisateur de communication des factures à l'Autoridade Tributária, sous la forme NIF/numéro (par exemple 599999993/37), porté en clair dans l'en-tête WS-Security de chaque message RegisterInvoice. Vide, comme le mot de passe ou le certificat : les messages partent sans en-tête WS-Security, ce que dit l'écran SAF-T (BO-11-04-02).",
                "", null),
        new Def("fiscal.at-password", Type.TEXT, "FISCAL",
                "Mot de passe du sous-utilisateur de l'AT",
                "Mot de passe du sous-utilisateur, jamais réaffiché : un champ laissé vide conserve la valeur enregistrée. À chaque envoi il est chiffré par une clé de session AES-128 tirée au hasard, elle-même chiffrée par la clé publique de l'AT. Vide : les messages partent sans en-tête WS-Security (BO-11-04-02).",
                "", null),
        new Def("fiscal.at-public-certificate", Type.TEXT, "FISCAL",
                "Certificat de la clé publique de l'AT (PEM)",
                "Certificat X.509 publié par l'AT pour le webservice de communication des factures, au format PEM (de -----BEGIN CERTIFICATE----- à -----END CERTIFICATE-----) : sa clé publique RSA chiffre la clé de session de chaque message. Illisible : les envois à l'AT échouent et sont rejoués. Vide : les messages partent sans en-tête WS-Security (BO-11-04-02).",
                "", null),

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
            Map.entry("parking.print-mode", List.of("AUTO", "REQUEST")),
            Map.entry("ticket.barcode-format", List.of("CODE128", "QR")),
            Map.entry("ticket.vat-table", List.of("", "NONE", "COMBINED", "SEPARATE", "AMOUNT_ONLY")),
            Map.entry("hardware.scanner.driver", List.of("AUTO", "EVDEV", "HIDRAW", "OPOS")),
            Map.entry("hardware.printer.type",
                    List.of("ESCPOS", "EPSON_TM", "WINCOR_TH", "IBM_4610", "TOSHIBA_TCX")),
            Map.entry("printer2.format", List.of("A4", "TICKET")),
            Map.entry("printer2.connection", List.of("NETWORK", "LOCAL")),
            Map.entry("payment.terminal-type", List.of("", "VERIFONE", "INGENICO_SIBS", "WORLDLINE")),
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
            Map.entry("keyboard.layout", List.of("AZERTY", "QWERTY", "QWERTZ", "ABCD")),
            Map.entry("kiosk.format", List.of("JSON", "XML", "TEXT")),
            Map.entry("nexo.activation-moment", List.of("SCAN", "PAYMENT")),
            Map.entry("island.promotion-channel",
                    List.of("", "POINT_OF_SALE", "WEB", "OTHER")),
            Map.entry("channel.ecommerce.price-source",
                    List.of("", "POS", "TRANSACTION")),
            Map.entry("channel.ecommerce.forbidden-items",
                    List.of("ACCEPT", "REJECT")),
            Map.entry("channel.ecommerce.promotion-channel",
                    List.of("", "POINT_OF_SALE", "WEB", "OTHER")),
            Map.entry("channel.selfscan.retrieval-mode",
                    List.of("BOTH", "SCAN", "KEYED")),
            Map.entry("channel.selfscan.add-items",
                    List.of("BOTH", "BEFORE", "AFTER", "NONE")),
            Map.entry("channel.selfscan.identifier",
                    List.of("TRANSACTION", "CUSTOMER")),
            Map.entry("channel.selfscan.price-source",
                    List.of("", "POS", "TRANSACTION")),
            Map.entry("channel.selfscan.promotion-channel",
                    List.of("", "POINT_OF_SALE", "WEB", "OTHER")),
            Map.entry("channel.selfscan.forbidden-items",
                    List.of("ACCEPT", "REJECT")),
            Map.entry("channel.commercial.forbidden-items",
                    List.of("ACCEPT", "REJECT")),
            Map.entry("channel.commercial.retrieval-mode",
                    List.of("BOTH", "SCAN", "KEYED")),
            Map.entry("channel.commercial.transmission",
                    List.of("PUSH", "PULL")),
            Map.entry("channel.commercial.promotion-channel",
                    List.of("", "POINT_OF_SALE", "WEB", "OTHER")),
            Map.entry("channel.commercial.price-source",
                    List.of("", "POS", "TRANSACTION")),
            Map.entry("channel.qbusting.retrieval-mode",
                    List.of("BOTH", "SCAN", "KEYED")),
            Map.entry("channel.qbusting.promotion-channel",
                    List.of("", "POINT_OF_SALE", "WEB", "OTHER")),
            Map.entry("channel.qbusting.forbidden-items",
                    List.of("ACCEPT", "REJECT")),
            Map.entry("channel.qbusting.price-source",
                    List.of("", "POS", "TRANSACTION")),
            Map.entry("channel.mobility.promotion-channel",
                    List.of("", "POINT_OF_SALE", "WEB", "OTHER")),
            Map.entry("channel.mobility.price-source",
                    List.of("", "POS", "TRANSACTION")),
            Map.entry("channel.mobility.forbidden-items",
                    List.of("ACCEPT", "REJECT")),
            Map.entry("channel.sales-slip.retrieval-mode",
                    List.of("BOTH", "SCAN", "KEYED")),
            Map.entry("channel.sales-slip.price-source",
                    List.of("", "POS", "TRANSACTION")),
            Map.entry("scan.offline-alert", List.of("NONE", "INFO", "BLOCK")),
            Map.entry("voucher.unknown-alert", List.of("INFO", "BLOCK")),
            // lot 21: U
            Map.entry("fiscal.ereporting-period", List.of("DECADE", "MONTH")));

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
     * On the store node, absent means no echelon layer applies.
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
        Map<String, String> values = settings();
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
     * <p>
     * A failed read answers NULL and not an empty map, because the two mean
     * opposite things: an empty map is a register nobody has parameterised, a
     * failure is a register whose parameters could not be read. The caller
     * caches the first and must not cache the second — see {@link #settings()}.
     * This method no longer clears the cache itself: it was clearing a field
     * its own caller then overwrote with the empty map, so the announced retry
     * never happened and the defaults stuck until the next explicit
     * invalidation.
     *
     * @return the key-to-value map of stored overrides, or null when the read failed
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
            return null;
        }
        return values;
    }

    /**
     * Returns the stored overrides, reading them once and caching them until
     * the next invalidation.
     * <p>
     * The ONE place the local cache is populated, and it populates it only on
     * a successful read: a failure serves the empty map for this call alone,
     * so the very next access tries the database again. A register that could
     * not read its parameters at boot must recover by itself, and a read made
     * from a thread with no request context (a background loop of the store
     * node) must not leave the node publishing the defaults to its registers,
     * until someone saves a setting or restarts it.
     *
     * @return the key-to-value map of stored overrides, never null
     */
    private Map<String, String> settings() {
        Map<String, String> values = cache;
        if (values != null) {
            return values;
        }
        Map<String, String> loaded = loadAll();
        if (loaded == null) {
            return Map.of();
        }
        cache = loaded;
        return loaded;
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
     * no echelon engine or no PDV number the map is just the local overrides, and
     * so it is on a register, whose echelon tables are empty: there it returns the
     * keys carrying a local override, and the register recomputes the default.
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
        Map<String, String> local = settings();
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
     * The name of the place the customer collects the marked articles at.
     *
     * <p>ONE PLACE PER STORE, and its name changes with the format: the BATI
     * counter in a hardware store, the DRIVE in a food store. The customer pays
     * at the till and DRIVES there, so the slip that proves what is owed to them
     * has to say where to go. Blank when the store administered none, and the
     * slip then names no place rather than inventing one.
     *
     * @return the collection point's name, blank when none is administered
     */
    public String collectPointLabel() {
        LOGGER.info("Entering method collectPointLabel");
        LOGGER.info("Exiting method collectPointLabel");
        return value("collect.point-label");
    }

    /**
     * How many copies of the handover slip the till prints.
     *
     * <p>TWO BY DEFAULT, because the slip has two readers and they keep
     * different halves of the same fact: the collection desk keeps one as the
     * proof that goods left the shop — the manager collects them in the evening
     * — and the customer keeps one carrying what is left to collect. A single
     * copy cannot do both, and the per-article "retiré / reste" boxes are
     * designed for the one the customer keeps.
     *
     * @return the number of copies, never below one
     */
    public int collectVoucherCopies() {
        LOGGER.info("Entering method collectVoucherCopies");
        int copies = intValue("collect.voucher-copies");
        LOGGER.info("Exiting method collectVoucherCopies");
        return Math.max(1, copies);
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
     * The welcome message shown on the closed-register lock screen (EXP-01):
     * the invitation to log in that replaces the historical
     * "inutilisable" text.
     *
     * @return the administered welcome message
     */
    public String welcomeMessage() {
        LOGGER.info("Entering method welcomeMessage");
        LOGGER.info("Exiting method welcomeMessage");
        return value("session.welcome-message");
    }

    /**
     * The waiting label shown on the sale screen between two customers, when the
     * ticket is empty (EXP-03): the retail "next customer" prompt that replaces
     * the historical "waiting for articles" text.
     *
     * @return the administered waiting label
     */
    public String waitingLabel() {
        LOGGER.info("Entering method waitingLabel");
        LOGGER.info("Exiting method waitingLabel");
        return value("sale.waiting-label");
    }

    /**
     * The drawer content cap in euros (EXP-17): the amount of cash above which
     * the register warns to withdraw, calé sur le montant garanti par l'assurance.
     * Zero disables the cap.
     *
     * @return the administered cap, guarded against a corrupt row
     */
    public BigDecimal drawerCap() {
        LOGGER.info("Entering method drawerCap");
        LOGGER.info("Exiting method drawerCap");
        return bigDecimalValue("cash.drawer-cap");
    }

    /**
     * The roles whose holder may endorse a guarded gesture
     * ({@code BO-04-02-01}), as a raw semicolon-separated string.
     *
     * @return the administered roles, never null
     */
    public String endorsementRoles() {
        LOGGER.info("Entering method endorsementRoles");
        LOGGER.info("Exiting method endorsementRoles");
        return value("auth.endorsement-roles");
    }

    /**
     * Whether an unknown article scanned at the till raises a service
     * request at the back office ({@code BO-04-02-06}).
     *
     * @return true when the alert is administered
     */
    public boolean unknownItemAlertEnabled() {
        LOGGER.info("Entering method unknownItemAlertEnabled");
        LOGGER.info("Exiting method unknownItemAlertEnabled");
        return boolValue("supervision.unknown-item-alert");
    }

    /**
     * The administered service-request reasons shown on the supervisor-call
     * screen (EXP-84), as a raw semicolon-separated string.
     *
     * @return the administered reasons string, never null
     */
    public String supervisorServiceReasons() {
        LOGGER.info("Entering method supervisorServiceReasons");
        LOGGER.info("Exiting method supervisorServiceReasons");
        return value("supervisor.service-reasons");
    }

    /**
     * The per-transaction cap of a TRD-card payment in euros (EXP-60); zero
     * means only the meal-voucher eligible base limits the payment.
     *
     * @return the administered TRD cap, guarded against a corrupt row
     */
    public BigDecimal trdCardCap() {
        LOGGER.info("Entering method trdCardCap");
        LOGGER.info("Exiting method trdCardCap");
        return bigDecimalValue("payment.trd-card-cap");
    }

    /**
     * The administered SOGEC bon prefixes (EXP-58), semicolon-separated; empty
     * when no SOGEC bon is recognised.
     *
     * @return the administered SOGEC prefixes
     */
    public String sogecBonPrefixes() {
        LOGGER.info("Entering method sogecBonPrefixes");
        LOGGER.info("Exiting method sogecBonPrefixes");
        return value("sogec.bon-prefixes");
    }

    /**
     * The cash-discrepancy threshold in euros (EXP-82): the absolute variance
     * above which the Z close alerts and demands a recount or a supervisor
     * validation. Guarded against a corrupt row.
     *
     * @return the administered threshold
     */
    public BigDecimal varianceThreshold() {
        LOGGER.info("Entering method varianceThreshold");
        LOGGER.info("Exiting method varianceThreshold");
        return bigDecimalValue("cash.variance-threshold");
    }

    /**
     * Whether the discrepancy screen shows the cashier the AMOUNT of the gap
     * (recette), disabled by default.
     *
     * <p>A blind count is the whole point of counting: a cashier who reads "écart
     * +12,40 €" knows exactly what to change to make it vanish, and the figure she
     * declares stops being a count. Disabled, the screen says only that the gap
     * crossed the threshold, and the two exits are unchanged — recount, or have a
     * supervisor validate. The amount itself is never hidden from the people who
     * arbitrate: it is on the Z, in the journal and in the report the manager reads.
     *
     * @return true when the amount is shown on the discrepancy screen
     */
    public boolean varianceAmountVisible() {
        LOGGER.info("Entering method varianceAmountVisible");
        LOGGER.info("Exiting method varianceAmountVisible");
        return boolValue("cash.variance-amount-visible");
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
     * Whether a Dallas key touched to the reader opens the register on its
     * own, without a PIN (BO-01-02-07).
     *
     * @return true when the Dallas key signs the operator in
     */
    public boolean dallasKeyOpens() {
        LOGGER.info("Entering method dallasKeyOpens");
        LOGGER.info("Exiting method dallasKeyOpens");
        return boolValue("auth.dallas-key");
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
        // value() never yields null for this catalog key (default CREDIT).
        return key.isBlank() ? "CREDIT" : key.trim();
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
     * Whether a customer fiscal identifier is mandatory before a transaction
     * can be settled (LC-08-06-01).
     *
     * @return true when payment cannot start without a NIF
     */
    public boolean taxIdRequired() {
        LOGGER.info("Entering method taxIdRequired");
        LOGGER.info("Exiting method taxIdRequired");
        return boolValue("customer.tax-id-required");
    }

    /**
     * Whether a Portuguese NIF entered or scanned at the till is checked
     * against the legal construction rules (LC-08-06-04).
     *
     * @return true when a malformed Portuguese NIF is refused
     */
    public boolean taxIdPortugueseCheck() {
        LOGGER.info("Entering method taxIdPortugueseCheck");
        LOGGER.info("Exiting method taxIdPortugueseCheck");
        return boolValue("customer.tax-id-pt-check");
    }

    /**
     * The regular expression recognising a scanned barcode that carries a
     * customer NIF, its first group naming the NIF (LC-08-06-05).
     *
     * @return the administered pattern, trimmed, empty when none is administered
     */
    public String taxIdScanPattern() {
        LOGGER.info("Entering method taxIdScanPattern");
        String pattern = value("customer.tax-id-scan-pattern").trim();
        LOGGER.info("Exiting method taxIdScanPattern");
        return pattern;
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

    /** LC-04-01-02: whether parking waits for the operator's print request.
     * @return true for REQUEST; unknown modes preserve automatic printing
     */
    public boolean parkingPrintOnRequest() {
        return "REQUEST".equalsIgnoreCase(value("parking.print-mode"));
    }

    /** LC-04-01-02: whether the waiting receipt includes the live articles.
     * @return the administered detail flag
     */
    public boolean parkingReceiptDetailed() {
        return boolValue("parking.receipt-detail");
    }

    /**
     * One third-party application the register can reach (BO-03-09-27).
     *
     * @param label the operator-facing wording of the touch
     * @param url the address the register opens
     */
    public record ExternalApp(String label, String url) {
    }

    /**
     * The third-party applications the register offers (BO-03-09-27), in
     * administered order.
     *
     * <p>A malformed entry is DROPPED rather than refused: one typo in the back
     * office must not cost the cashier the other applications. An entry naming
     * no address, or no wording, is malformed — a touch nobody can label and a
     * touch leading nowhere are both useless.
     *
     * <p>ADDRESSES ONLY, deliberately. The requirement says "URL ou
     * exécutable"; a register that runs an arbitrary binary because a
     * referential row said so is a security hole, so the executable half is
     * not implemented and the line is rated accordingly.
     *
     * @return the administered applications, empty when none is administered
     */
    public java.util.List<ExternalApp> externalApps() {
        LOGGER.info("Entering method externalApps");
        java.util.List<ExternalApp> apps = new java.util.ArrayList<>();
        String raw = value("touch.external-apps");
        if (raw.isBlank()) {
            LOGGER.info("Exiting method externalApps");
            return apps;
        }
        for (String entry : raw.split(";")) {
            String trimmed = entry.trim();
            int arrow = trimmed.indexOf('>');
            if (arrow <= 0 || arrow == trimmed.length() - 1) {
                continue;
            }
            String label = trimmed.substring(0, arrow).trim();
            String url = trimmed.substring(arrow + 1).trim();
            apps.add(new ExternalApp(label, url));
        }
        LOGGER.info("Exiting method externalApps");
        return apps;
    }

    /**
     * One action the point of sale's period end performs (BO-03-12-02).
     */
    public enum PeriodAction {

        /** Close the registers left open. */
        CLOSE_REGISTERS,

        /** Count the safe. */
        SAFE_COUNT,

        /** Emit the consolidated Z report. */
        Z_REPORT,

        /** Prepare the bank deposit. */
        BANK_DEPOSIT
    }

    /**
     * What the POINT OF SALE's period end chains, in administered order
     * (BO-03-12-02).
     *
     * <p>Above the cash sessions: the three {@code period.*} keys beside this
     * one already say WHEN a period ends and what each REGISTER does about it,
     * and the opening calendar is the pair of {@code financial.closed-*} keys.
     * All of them are catalog keys, so the head office already poses them at
     * the PDV echelon and every register inherits them — that part needed no
     * code. What this key adds is what the STORE does at that moment, as an
     * ordered list the back office writes.
     *
     * <p>An unknown name is DROPPED rather than refused: a typo must not leave
     * a store unable to close its day. A duplicate is kept once — chaining the
     * same action twice is never what a shop means.
     *
     * @return the administered actions in order, empty when none is administered
     */
    public java.util.List<PeriodAction> periodPdvActions() {
        LOGGER.info("Entering method periodPdvActions");
        java.util.List<PeriodAction> actions = new java.util.ArrayList<>();
        String raw = value("period.pdv-actions");
        if (raw.isBlank()) {
            LOGGER.info("Exiting method periodPdvActions");
            return actions;
        }
        for (String entry : raw.split(";")) {
            String name = entry.trim().toUpperCase();
            if (name.isEmpty()) {
                continue;
            }
            try {
                PeriodAction action = PeriodAction.valueOf(name);
                if (!actions.contains(action)) {
                    actions.add(action);
                }
            } catch (IllegalArgumentException e) {
                LOGGER.debugf("Action de fin de periode inconnue, ignoree: %s", name);
            }
        }
        LOGGER.info("Exiting method periodPdvActions");
        return actions;
    }

    /**
     * The range of customer numbers the register attributes to the customers it
     * creates (BO-03-06-53), empty when none is administered.
     *
     * @return the administered range, empty when none is administered
     */
    public String customerLocalCodeRange() {
        LOGGER.info("Entering method customerLocalCodeRange");
        LOGGER.info("Exiting method customerLocalCodeRange");
        return value("customer.local-code-range");
    }

    /**
     * The range of tender functional identifiers the STORE may modify
     * (BO-03-02-06 and -07), empty when it may modify them all.
     *
     * @return the administered range, empty when none is administered
     */
    public String tenderLocalRange() {
        LOGGER.info("Entering method tenderLocalRange");
        LOGGER.info("Exiting method tenderLocalRange");
        return value("tender.local-range");
    }

    /**
     * What is printed on the back of a cheque (BO-03-02-43), one line per
     * semicolon, empty when nothing is administered.
     *
     * @return the administered endorsement, empty when none is administered
     */
    public String chequeEndorsementLines() {
        LOGGER.info("Entering method chequeEndorsementLines");
        LOGGER.info("Exiting method chequeEndorsementLines");
        return value("cheque.endorsement-lines");
    }

    /**
     * Whether the administered Label Point De Vente is added to every
     * monetique request (BO-03-09-31).
     *
     * @return true when the label travels with the request
     */
    public boolean terminalPdvLabelEnabled() {
        LOGGER.info("Entering method terminalPdvLabelEnabled");
        LOGGER.info("Exiting method terminalPdvLabelEnabled");
        return boolValue("terminal.pdv-label-enabled");
    }

    /**
     * The Label Point De Vente as the acquirer wrote it (BO-03-09-31), in the
     * {@code {TAG}value} form the register emits verbatim.
     *
     * @return the administered label, empty when none is administered
     */
    public String terminalPdvLabel() {
        LOGGER.info("Entering method terminalPdvLabel");
        LOGGER.info("Exiting method terminalPdvLabel");
        return value("terminal.pdv-label");
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
     * The web address printed at the FOOT of the sale and return documents
     * ({@code BO-03-05-08}); empty when none is administered.
     *
     * @return the administered address, possibly empty
     */
    public String ticketFooterUrl() {
        LOGGER.info("Entering method ticketFooterUrl");
        LOGGER.info("Exiting method ticketFooterUrl");
        return value("ticket.footer-url");
    }

    /**
     * How the footer address is rendered ({@code BO-03-05-08}): {@code QR},
     * {@code BARCODE}, {@code TEXTE} or {@code AUCUN}.
     *
     * <p>Answered raw, the printer service deciding what an unknown value
     * means: the catalogue describes the four words, and a register that
     * meets a fifth prints the code it has always been able to print rather
     * than nothing.
     *
     * @return the administered format, never null
     */
    public String ticketFooterUrlFormat() {
        LOGGER.info("Entering method ticketFooterUrlFormat");
        LOGGER.info("Exiting method ticketFooterUrlFormat");
        return value("ticket.footer-url-format");
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
     * Returns the administered activation of one peripheral role (LC-13-02-09/-14).
     * @param role printer, scanner or scale
     * @return true when the peripheral is enabled
     */
    public boolean hardwareEnabled(String role) {
        return boolValue("hardware." + role + ".enabled");
    }

    /**
     * Returns the donation step administered at the applicable hierarchy level
     * (LC-07-17-02); a nonpositive value falls back to one euro.
     *
     * @return the donation step in cents
     */
    public int donationRoundingStepCents() {
        int step = intValue("donation.rounding-step-cents");
        return step > 0 ? step : 100;
    }

    /** Returns the administered WCL application-to-tender mapping (LC-07-07-04).
     * @return mapping rules
     */
    public String verifoneMethodMap() {
        return value("payment.verifone-method-map");
    }

    /** Whether the WCL customer receipt stays attached to the sale (LC-07-04-11).
     * @return configured attachment policy
     */
    public boolean cardReceiptAttached() {
        return boolValue("payment.card-receipt-attached");
    }

    /**
     * Whether a card settlement may exceed the remaining due, the difference
     * being given back in cash (LC-07-04-07/08).
     *
     * @return true when the register offers cashback on a card
     */
    public boolean cashbackEnabled() {
        LOGGER.info("Entering method cashbackEnabled");
        LOGGER.info("Exiting method cashbackEnabled");
        return boolValue("payment.cashback-enabled");
    }

    /**
     * The most cash a cashback may give back on one transaction
     * (LC-07-04-07/08); zero means no ceiling.
     *
     * @return the administered ceiling in euros, the catalogue default when unreadable
     */
    public BigDecimal cashbackMax() {
        LOGGER.info("Entering method cashbackMax");
        LOGGER.info("Exiting method cashbackMax");
        return bigDecimalValue("payment.cashback-max");
    }

    /**
     * Selects nearest-step donation rounding instead of upper-step rounding
     * (LC-07-17-02).
     *
     * @return true for nearest-step rounding
     */
    public boolean donationRoundingNearest() {
        return boolValue("donation.rounding-nearest");
    }

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
        return directory.trim();
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
     * Whether a card-less ticket asks imfid for the anonymous earn projection
     * — « ce que vous auriez gagné avec la carte » (imfid response C,
     * BO-03-03-28). Disabled: nothing is asked or printed without a card.
     *
     * @return true when the anonymous projection is enabled
     */
    public boolean fidelityAnonymousProjection() {
        LOGGER.info("Entering method fidelityAnonymousProjection");
        LOGGER.info("Exiting method fidelityAnonymousProjection");
        return boolValue("fidelity.anonymous-projection");
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
     * Whether the holder's NAME is shown on the CUSTOMER display at card
     * attachment (BO-10-03-03).
     *
     * <p>Distinct from the cashier-side rule (BO-10-03-04) and off by default,
     * which is not the same prudence twice: the cashier screen faces one
     * person, the customer display faces the queue.
     *
     * @return true when the customer display states the holder
     */
    public boolean fidelityShowHolderNameOnCustomerDisplay() {
        LOGGER.info("Entering method fidelityShowHolderNameOnCustomerDisplay");
        LOGGER.info("Exiting method fidelityShowHolderNameOnCustomerDisplay");
        return boolValue("fidelity.show-holder-name-customer");
    }

    /**
     * Whether the attached card's available balance is shown on the CUSTOMER
     * display (LC-03-04-13).
     *
     * <p>Separate from the holder's name (BO-10-03-03): a shop may show the
     * customer what their card holds without naming them to the queue.
     *
     * @return true when the customer display states the available balance
     */
    public boolean fidelityShowBalanceOnCustomerDisplay() {
        LOGGER.info("Entering method fidelityShowBalanceOnCustomerDisplay");
        LOGGER.info("Exiting method fidelityShowBalanceOnCustomerDisplay");
        return boolValue("fidelity.show-balance-customer");
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
     * The number of DECIMALS a commercial document prints a figure with
     * (BO-10-02-19), clamped to the two values the requirement allows.
     *
     * <p>Two or three, and nothing else: a document administered with seven
     * decimals would be unreadable, and one administered with none would state
     * a price that is not the price. Anything outside the range answers two,
     * which is what every document printed before the keys existed.
     *
     * @param key the settings key carrying the figure's decimals
     * @return 2 or 3
     */
    private int documentDecimals(String key) {
        int decimals = intValue(key);
        return decimals == 3 ? 3 : 2;
    }

    /**
     * Decimals of the UNIT PRICE EXCLUDING TAX on a commercial document
     * (BO-10-02-19).
     *
     * @return 2 or 3
     */
    public int invoiceUnitExcludingTaxDecimals() {
        LOGGER.info("Entering method invoiceUnitExcludingTaxDecimals");
        int decimals = documentDecimals("invoice.decimals-unit-ht");
        LOGGER.info("Exiting method invoiceUnitExcludingTaxDecimals");
        return decimals;
    }

    /**
     * Decimals of the TAXABLE BASE of a VAT row (BO-10-02-19).
     *
     * @return 2 or 3
     */
    public int invoiceVatBaseDecimals() {
        LOGGER.info("Entering method invoiceVatBaseDecimals");
        int decimals = documentDecimals("invoice.decimals-vat-base");
        LOGGER.info("Exiting method invoiceVatBaseDecimals");
        return decimals;
    }

    /**
     * Decimals of the TAX AMOUNT of a VAT row (BO-10-02-19).
     *
     * @return 2 or 3
     */
    public int invoiceVatAmountDecimals() {
        LOGGER.info("Entering method invoiceVatAmountDecimals");
        int decimals = documentDecimals("invoice.decimals-vat-amount");
        LOGGER.info("Exiting method invoiceVatAmountDecimals");
        return decimals;
    }

    /**
     * Decimals of the TOTAL EXCLUDING TAX in the document foot (BO-10-02-19).
     *
     * @return 2 or 3
     */
    public int invoiceTotalExcludingTaxDecimals() {
        LOGGER.info("Entering method invoiceTotalExcludingTaxDecimals");
        int decimals = documentDecimals("invoice.decimals-total-ht");
        LOGGER.info("Exiting method invoiceTotalExcludingTaxDecimals");
        return decimals;
    }

    /**
     * Decimals of the TOTAL INCLUDING TAX in the document foot (BO-10-02-19).
     *
     * @return 2 or 3
     */
    public int invoiceTotalIncludingTaxDecimals() {
        LOGGER.info("Entering method invoiceTotalIncludingTaxDecimals");
        int decimals = documentDecimals("invoice.decimals-total-ttc");
        LOGGER.info("Exiting method invoiceTotalIncludingTaxDecimals");
        return decimals;
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
     * Where the ticket states its ARTICLE COUNT (BO-10-07-10), read as
     * administered and resolved by the caller — {@code TOTAL_LINE},
     * {@code OWN_LINE}, or anything else, which means nowhere.
     *
     * @return the administered placement
     */
    public String ticketItemCountPrint() {
        LOGGER.info("Entering method ticketItemCountPrint");
        String placement = value("ticket.item-count-print");
        LOGGER.info("Exiting method ticketItemCountPrint");
        return placement;
    }

    /**
     * Whether the unknown-article slip carries the read code as a BARCODE
     * (BO-10-07-11).
     *
     * @return true when the slip is meant to be rescanned
     */
    public boolean unknownArticleBarcodePrinted() {
        LOGGER.info("Entering method unknownArticleBarcodePrinted");
        LOGGER.info("Exiting method unknownArticleBarcodePrinted");
        return boolValue("ticket.unknown-article-barcode");
    }

    /**
     * How far back the register searches a closed ticket before giving up, in
     * DAYS (BO-10-07-16).
     *
     * <p>Zero or negative is not a depth of zero — a register that answers
     * nothing to every search would look broken — but the absence of an
     * administered one: the caller then keeps its built-in depth.
     *
     * @return the administered depth in days, zero or less when there is none
     */
    public int ticketSearchDays() {
        LOGGER.info("Entering method ticketSearchDays");
        int days = intValue("ticket.search-days");
        LOGGER.info("Exiting method ticketSearchDays");
        return days;
    }

    /**
     * Days an EXPIRED and never-redeemed instrument is kept (BO-10-08-13).
     *
     * @return the delay in days, zero meaning no limit
     */
    public int barcodeExpiredUnusedDays() {
        LOGGER.info("Entering method barcodeExpiredUnusedDays");
        int days = intValue("retention.barcodes-expired-unused-days");
        LOGGER.info("Exiting method barcodeExpiredUnusedDays");
        return days;
    }

    /**
     * Days a dated and EXHAUSTED instrument is kept after exhaustion
     * (BO-10-08-14).
     *
     * @return the delay in days, zero meaning no limit
     */
    public int barcodeExpiredUsedDays() {
        LOGGER.info("Entering method barcodeExpiredUsedDays");
        int days = intValue("retention.barcodes-expired-used-days");
        LOGGER.info("Exiting method barcodeExpiredUsedDays");
        return days;
    }

    /**
     * Days an instrument carrying NO validity date is kept after issue
     * (BO-10-08-15).
     *
     * @return the delay in days, zero meaning no limit
     */
    public int barcodeUndatedDays() {
        LOGGER.info("Entering method barcodeUndatedDays");
        int days = intValue("retention.barcodes-undated-days");
        LOGGER.info("Exiting method barcodeUndatedDays");
        return days;
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
     * password has its own parameter (BO-10-03-08).
     *
     * @return the loyalty machine-account user, possibly empty
     */
    public String fidelityUser() {
        LOGGER.info("Entering method fidelityUser");
        LOGGER.info("Exiting method fidelityUser");
        return value("fidelity.user");
    }

    /**
     * Returns the store's generic function approval rules (LC-01-05-01).
     * @return newline-separated method/path rules, empty by default
     */
    public String functionEndorsements() {
        return value("gesture.function-endorsements");
    }

    /**
     * Returns the administered training key rules without changing inherited values (LC-13-05-04).
     * @return newline-separated path or key rules, empty by default
     */
    public String trainingBlockedKeys() {
        return value("training.blocked-keys");
    }

    /**
     * Whether the sale identification code is printed (LC-08-01-03).
     * @return true by default, false when suppressed by the store
     */
    public boolean ticketBarcodeEnabled() {
        return boolValue("ticket.barcode-enabled");
    }

    /**
     * Selects the two-dimensional identification code without changing its payload (LC-08-01-03).
     * @return true for QR; unrecognized values retain the historical CODE128 format
     */
    public boolean ticketBarcodeQr() {
        return "QR".equalsIgnoreCase(value("ticket.barcode-format").trim());
    }

    /**
     * The prefix the printed ticket barcode carries (BO-03-06-55).
     *
     * <p>A PREFIX and not a whole code: the barcode must stay the one a return
     * or a claim is looked up by, so the ticket number is kept and the store
     * only says which range it belongs to — which is what a gate or a game
     * kiosk filters on.
     *
     * @return the administered prefix, empty when none
     */
    public String ticketBarcodePrefix() {
        LOGGER.info("Entering method ticketBarcodePrefix");
        LOGGER.info("Exiting method ticketBarcodePrefix");
        return value("ticket.barcode-prefix");
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
     * Whether the customer display shows the total of the articles eligible
     * for eco-vouchers (LC-10-01-09).
     *
     * @return true when the eco-voucher eligible total is shown to the customer
     */
    public boolean customerEcoVoucherEligible() {
        LOGGER.info("Entering method customerEcoVoucherEligible");
        LOGGER.info("Exiting method customerEcoVoucherEligible");
        return boolValue("customer.eco-voucher-eligible");
    }

    /**
     * Whether the customer display shows the total of the articles eligible
     * for the social purchase card (LC-10-01-11).
     *
     * @return true when the social-card eligible total is shown to the customer
     */
    public boolean customerSocialCardEligible() {
        LOGGER.info("Entering method customerSocialCardEligible");
        LOGGER.info("Exiting method customerSocialCardEligible");
        return boolValue("customer.social-card-eligible");
    }

    /**
     * The letter layout of the register's alphabetic and alphanumeric virtual
     * keyboards, by country or region (LC-09-01-09): AZERTY, QWERTY, QWERTZ or
     * ABCD, trimmed and upper-cased. A blank setting answers AZERTY; an unknown
     * value is passed through and the keyboard library falls back to AZERTY.
     *
     * @return the administered layout name, never blank
     */
    public String keyboardLayout() {
        LOGGER.info("Entering method keyboardLayout");
        String layout = value("keyboard.layout").trim().toUpperCase(java.util.Locale.ROOT);
        LOGGER.info("Exiting method keyboardLayout");
        return layout.isEmpty() ? "AZERTY" : layout;
    }

    /**
     * Whether the language the loyalty base records for the identified holder
     * becomes the customer language of the sale (LC-13-06-09, -14).
     *
     * @return true when the holder's language is applied
     */
    public boolean fidelityHolderLanguage() {
        LOGGER.info("Entering method fidelityHolderLanguage");
        LOGGER.info("Exiting method fidelityHolderLanguage");
        return boolValue("language.fidelity-holder");
    }

    /**
     * Whether the customer may choose, on the touch customer display itself, the
     * language of that display and of the sale receipt for the current
     * transaction (LC-13-06-08 / -13).
     *
     * @return true when the customer display offers its language keys
     */
    public boolean customerLanguageChoice() {
        LOGGER.info("Entering method customerLanguageChoice");
        LOGGER.info("Exiting method customerLanguageChoice");
        return boolValue("language.customer-choice");
    }

    /**
     * Whether articles may be taken back inside a ticket that already carries
     * sales (LC-05-01-01). The ticket then mixes sold and returned lines and
     * its total is positive or negative according to the amounts. Disabled: a
     * return runs on a ticket of its own.
     *
     * @return true when a sale ticket may carry returned articles
     */
    public boolean returnInSaleAllowed() {
        LOGGER.info("Entering method returnInSaleAllowed");
        LOGGER.info("Exiting method returnInSaleAllowed");
        return boolValue("return.in-sale-allowed");
    }

    /**
     * Whether this register is dedicated to returns (LC-05-01-03): the return
     * mode is permanent there and no sale is possible. Disabled: the register
     * both sells and takes back.
     *
     * @return true when the register accepts returns only
     */
    public boolean returnDedicatedRegister() {
        LOGGER.info("Entering method returnDedicatedRegister");
        LOGGER.info("Exiting method returnDedicatedRegister");
        return boolValue("return.dedicated-register");
    }

    /**
     * Whether a return may be recorded without an identified origin ticket
     * (LC-05-02-09/14): the returned articles are then keyed freely, at a price
     * the cashier may still change. Disabled: every return goes through the
     * recall of its origin ticket.
     *
     * @return true when a return without origin ticket is accepted
     */
    public boolean returnWithoutOriginAllowed() {
        LOGGER.info("Entering method returnWithoutOriginAllowed");
        LOGGER.info("Exiting method returnWithoutOriginAllowed");
        return boolValue("return.without-origin-allowed");
    }

    /**
     * One return type the back office offers in addition to the two base
     * gestures (LC-05-04-01).
     *
     * @param label what the key is called on the screen, never blank
     * @param dedicatedTicket true when the key opens a dedicated return ticket,
     *        false when it takes the article back inside the sale
     */
    public record ReturnType(String label, boolean dedicatedTicket,
                             boolean endorsementExempt, boolean originOptional,
                             boolean priceConfirmed) {

        /**
         * Builds a type carrying only the base gesture, every option off — the
         * shape every administered entry had before the options existed.
         *
         * @param label the operator-facing label
         * @param dedicatedTicket whether the key opens a dedicated return ticket
         */
        public ReturnType(String label, boolean dedicatedTicket) {
            this(label, dedicatedTicket, false, false, false);
        }
    }

    /**
     * ONE ready-made discount key (BO-03-07-03 and BO-03-07-06).
     *
     * <p>A label the cashier taps, a kind and a value — nothing else. The two
     * kinds are the two gestures the register already performs: a PERCENTAGE off
     * the line, or an AMOUNT in euros off the line. The key spares the cashier
     * the numpad, which is what the questionnaire asks for: "sélectionner
     * directement la touche avec le bon pourcentage".
     *
     * @param label what the key is called on the screen, never blank
     * @param percent true for a percentage, false for an amount in euros
     * @param value the percentage or the amount, strictly positive
     * @param cumulative true when the key may be used on a line that already
     *        carries a discount or an automatic promotion (BO-03-07-11)
     */
    public record DiscountPreset(String label, boolean percent, java.math.BigDecimal value,
                                 boolean cumulative) {
    }

    /**
     * The ready-made discount keys the back office administers (BO-03-07-03,
     * -06 and -11), parsed from the semicolon-separated list.
     *
     * <p>An entry is {@code label>KIND:value} with KIND either {@code PERCENT}
     * or {@code AMOUNT}, optionally followed by {@code ,CUMUL} to let the key be
     * used on an article that already carries a reduction. An entry whose kind
     * is unknown, whose value is not a positive number or which carries no
     * label is DROPPED rather than guessed: a key that takes off a figure
     * nobody administered is worse than a key that is not offered.
     *
     * @return the administered keys, in order, never null
     */
    public List<DiscountPreset> discountPresets() {
        LOGGER.info("Entering method discountPresets");
        String raw = value("discount.presets");
        if (raw.isBlank()) {
            LOGGER.info("Exiting method discountPresets");
            return List.of();
        }
        List<DiscountPreset> presets = new ArrayList<>();
        for (String part : raw.split(";")) {
            String entry = part.trim();
            int marker = entry.indexOf('>');
            if (entry.isEmpty() || marker < 0) {
                continue;
            }
            String label = entry.substring(0, marker).trim();
            if (label.isEmpty()) {
                continue;
            }
            boolean percent = false;
            boolean amount = false;
            boolean cumulative = false;
            java.math.BigDecimal figure = null;
            for (String option : entry.substring(marker + 1).split(",\\s*(?=[A-Za-z])")) {
                String name = option.trim();
                if ("CUMUL".equalsIgnoreCase(name)) {
                    cumulative = true;
                    continue;
                }
                int colon = name.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String kind = name.substring(0, colon).trim();
                percent = percent || "PERCENT".equalsIgnoreCase(kind);
                amount = amount || "AMOUNT".equalsIgnoreCase(kind);
                try {
                    figure = new java.math.BigDecimal(
                            name.substring(colon + 1).trim().replace(',', '.'));
                } catch (NumberFormatException e) {
                    figure = null;
                }
            }
            if ((!percent && !amount) || figure == null || figure.signum() <= 0) {
                continue;
            }
            presets.add(new DiscountPreset(label, percent, figure, cumulative));
        }
        LOGGER.info("Exiting method discountPresets");
        return presets;
    }

    /**
     * The ready-made discount key carrying that label, or null when none does.
     *
     * @param label the label to resolve, possibly null
     * @return the key, or null when the list names none
     */
    public DiscountPreset discountPreset(String label) {
        LOGGER.info("Entering method discountPreset with label: " + label);
        if (label == null || label.isBlank()) {
            LOGGER.info("Exiting method discountPreset");
            return null;
        }
        String typed = label.trim();
        for (DiscountPreset candidate : discountPresets()) {
            if (candidate.label().equalsIgnoreCase(typed)) {
                LOGGER.info("Exiting method discountPreset");
                return candidate;
            }
        }
        LOGGER.info("Exiting method discountPreset");
        return null;
    }

    /**
     * Whether a freely keyed discount may be applied to a line that already
     * carries one (BO-03-07-11).
     *
     * <p>Defaults to true, which is what the register did before the key
     * existed. A ready-made key carrying {@code CUMUL} passes over this
     * default; one that does not carry it obeys it.
     *
     * @return true when the cumulation is allowed
     */
    public boolean discountCumulativeOnDiscounted() {
        LOGGER.info("Entering method discountCumulativeOnDiscounted");
        LOGGER.info("Exiting method discountCumulativeOnDiscounted");
        return boolValue("discount.cumulative-on-discounted");
    }

    /**
     * Whether the TICKET-level discount bears on the lines that already carry a
     * reduction (BO-03-07-12).
     *
     * <p>Defaults to true. Switched off, those lines leave the base AND the
     * allocation — the same doctrine as an article that bans discounts
     * (BO-02-03-09): leaving them in the base would shrink everyone else's
     * share to pay for a discount they never receive.
     *
     * @return true when the global discount bears on reduced lines
     */
    public boolean discountGlobalOnDiscounted() {
        LOGGER.info("Entering method discountGlobalOnDiscounted");
        LOGGER.info("Exiting method discountGlobalOnDiscounted");
        return boolValue("discount.global-on-discounted");
    }

    /**
     * The return types offered in caisse (LC-05-04-01), parsed from the
     * semicolon-separated administered list. An entry is a label, optionally
     * followed by {@code >} and the OPTIONS of the type, separated by commas:
     *
     * <ul>
     *   <li>{@code TICKET} — the key opens a dedicated return ticket rather than
     *       taking the article back inside the sale (LC-05-04-01).</li>
     *   <li>{@code NOENDORSE} — the type needs NO supervisor endorsement
     *       (BO-03-13-08): an exchange of equal value does not call a manager.</li>
     *   <li>{@code NOORIGIN} — the type may be used when the original ticket was
     *       not found (BO-03-13-06), even where the store forbids the
     *       without-origin return in general.</li>
     *   <li>{@code PRICE} — each returned line has its price CONFIRMED or
     *       changed by the cashier (BO-03-13-10).</li>
     * </ul>
     *
     * <p>An unknown marker is ignored rather than refused, so a typo offers a key
     * that works — the rule the single {@code >TICKET} marker already followed,
     * and an entry written for the older format still reads the same way. Blank
     * entries are dropped and an empty setting yields an empty list — only the
     * two base gestures are then offered. Never null.
     *
     * @return the administered return types, in order
     */
    public List<ReturnType> returnTypes() {
        LOGGER.info("Entering method returnTypes");
        String raw = value("return.types");
        if (raw.isBlank()) {
            LOGGER.info("Exiting method returnTypes");
            return List.of();
        }
        List<ReturnType> types = new ArrayList<>();
        for (String part : raw.split(";")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int marker = entry.indexOf('>');
            String label = marker < 0 ? entry : entry.substring(0, marker).trim();
            if (label.isEmpty()) {
                continue;
            }
            boolean dedicated = false;
            boolean exempt = false;
            boolean originOptional = false;
            boolean priceConfirmed = false;
            if (marker >= 0) {
                for (String option : entry.substring(marker + 1).split(",\\s*(?=[A-Za-z])")) {
                    String name = option.trim();
                    if ("TICKET".equalsIgnoreCase(name)) {
                        dedicated = true;
                    } else if ("NOENDORSE".equalsIgnoreCase(name)) {
                        exempt = true;
                    } else if ("NOORIGIN".equalsIgnoreCase(name)) {
                        originOptional = true;
                    } else if ("PRICE".equalsIgnoreCase(name)) {
                        priceConfirmed = true;
                    }
                }
            }
            types.add(new ReturnType(label, dedicated, exempt, originOptional, priceConfirmed));
        }
        LOGGER.info("Exiting method returnTypes");
        return types;
    }

    /**
     * Whether a ticket issued by ANOTHER point of sale is accepted in return
     * (LC-05-02-07). Disabled — the catalog default — the return happens only
     * where the purchase was made, which is the rule between independent
     * retailers: each one owns its own stock and its own accounts.
     *
     * @return true when a foreign-store ticket may be returned here
     */
    public boolean returnForeignStoreAccepted() {
        LOGGER.info("Entering method returnForeignStoreAccepted");
        LOGGER.info("Exiting method returnForeignStoreAccepted");
        return boolValue("return.foreign-store-accepted");
    }

    /**
     * Whether the island this register belongs to allows change to be given
     * (BO-03-10-04). The node resolves the value per contact point / island /
     * PDV and delivers it through the SETTINGS domain. Defaults to true, so an
     * unposted setting leaves the change decision entirely to the tender rule,
     * exactly as before the option existed.
     *
     * @return true when the island allows change
     */
    public boolean islandChangeAllowed() {
        LOGGER.info("Entering method islandChangeAllowed");
        LOGGER.info("Exiting method islandChangeAllowed");
        return boolValue("island.change-allowed");
    }

    /**
     * Whether a slip is printed when a scanned code is recognized by nothing
     * (BO-03-10-05).
     *
     * <p>Resolved per island like the other island keys. Defaults to false, so
     * an unposted setting leaves the unknown code exactly where it was: a
     * message on the screen and no paper.
     *
     * @return true when the unknown-article slip is printed
     */
    public boolean islandUnknownArticleTicket() {
        LOGGER.info("Entering method islandUnknownArticleTicket");
        LOGGER.info("Exiting method islandUnknownArticleTicket");
        return boolValue("island.unknown-article-ticket");
    }

    /**
     * Whether the island silences the INFORMATIVE messages tied to an article
     * (BO-03-10-13).
     *
     * <p>Informative only. A refusal is never silenced — a gesture the store
     * forbids stays forbidden and says so — so this key reads the warnings and
     * never the errors.
     *
     * @return true when the article messages are blocked on this island
     */
    public boolean islandBlockArticlePopups() {
        LOGGER.info("Entering method islandBlockArticlePopups");
        LOGGER.info("Exiting method islandBlockArticlePopups");
        return boolValue("island.block-article-popups");
    }

    /**
     * Whether the island silences the INFORMATIVE messages tied to a tender
     * (BO-03-10-14).
     *
     * <p>Same rule as the article key: the non-blocking alerts of the payment
     * control points are silenced, the refusals and the endorsement requests
     * are not.
     *
     * @return true when the payment messages are blocked on this island
     */
    public boolean islandBlockPaymentPopups() {
        LOGGER.info("Entering method islandBlockPaymentPopups");
        LOGGER.info("Exiting method islandBlockPaymentPopups");
        return boolValue("island.block-payment-popups");
    }

    /**
     * Whether the island is set to declare the end-of-period cash count
     * automatically (BO-03-10-10): the cashier is dispensed from counting, the
     * declared amount is the theoretical, and the journal marks the declaration
     * as automatic. Defaults to false, so an unposted setting keeps the counting
     * screen exactly as today.
     *
     * @return true when the count is declared automatically at closing
     */
    public boolean islandAutoDeclaration() {
        LOGGER.info("Entering method islandAutoDeclaration");
        LOGGER.info("Exiting method islandAutoDeclaration");
        return boolValue("island.auto-declaration");
    }

    /**
     * The promotion channel of the island reported to the loyalty programme
     * (BO-03-10-08): point of sale, Web or another channel. Defaults to the
     * empty string, in which case no channel is added and the loyalty remontée
     * stays identical to the byte to what it is today. Never null.
     *
     * @return the promotion channel, or an empty string when none is administered
     */
    public String islandPromotionChannel() {
        LOGGER.info("Entering method islandPromotionChannel");
        LOGGER.info("Exiting method islandPromotionChannel");
        return value("island.promotion-channel");
    }

    /**
     * One administered tare container (LC-14-01-04): its code (used for a
     * selection tap or a scanned barcode), its cashier-facing label and its
     * tare in kilograms.
     *
     * @param code the container code / barcode
     * @param label the cashier-facing label
     * @param tareKg the container tare in kilograms
     */
    public record TareContainer(String code, String label, BigDecimal tareKg) {
    }

    /**
     * The software tare sent to the scale by default (LC-14-01-03), parsed from
     * the administered value; an unreadable value is zero, never an error — one
     * malformed setting must not stop the scale from weighing. Never null.
     *
     * @return the default tare in kilograms, zero when none is administered
     */
    public BigDecimal defaultTare() {
        LOGGER.info("Entering method defaultTare");
        LOGGER.info("Exiting method defaultTare");
        return bigDecimalValue("balance.default-tare");
    }

    /**
     * The body that certified the register's weighing system, as the metrology
     * screen shows it (LC-14-03-01).
     *
     * @return the administered name, trimmed, empty when none is administered
     */
    public String metrologyBody() {
        LOGGER.info("Entering method metrologyBody");
        String body = value("balance.metrology-body").trim();
        LOGGER.info("Exiting method metrologyBody");
        return body;
    }

    /**
     * The number of the weighing system's metrology certificate (LC-14-03-01).
     *
     * @return the administered number, trimmed, empty when none is administered
     */
    public String metrologyCertificate() {
        LOGGER.info("Entering method metrologyCertificate");
        String certificate = value("balance.metrology-certificate").trim();
        LOGGER.info("Exiting method metrologyCertificate");
        return certificate;
    }

    /**
     * The CRC of the scale's legal software, as its certificate declares it
     * (LC-14-03-01).
     *
     * @return the administered CRC, trimmed, empty when none is administered
     */
    public String metrologyScaleCrc() {
        LOGGER.info("Entering method metrologyScaleCrc");
        String crc = value("balance.metrology-crc").trim();
        LOGGER.info("Exiting method metrologyScaleCrc");
        return crc;
    }

    /**
     * The administered tare containers (LC-14-01-04), parsed from the
     * semicolon-separated {@code CODE:LIBELLÉ:POIDS_KG} list; a row missing a
     * field or carrying an unreadable weight is dropped, and an empty setting
     * yields an empty list — no container is then offered. Never null.
     *
     * @return the administered tare containers, in order
     */
    public List<TareContainer> tareContainers() {
        LOGGER.info("Entering method tareContainers");
        String raw = value("balance.tare-containers");
        if (raw.isBlank()) {
            LOGGER.info("Exiting method tareContainers");
            return List.of();
        }
        List<TareContainer> containers = new ArrayList<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] fields = trimmed.split(":");
            if (fields.length != 3) {
                continue;
            }
            String code = fields[0].trim();
            String label = fields[1].trim();
            if (code.isEmpty() || label.isEmpty()) {
                continue;
            }
            try {
                BigDecimal tareKg = new BigDecimal(fields[2].trim().replace(',', '.'));
                if (tareKg.signum() < 0) {
                    continue;
                }
                containers.add(new TareContainer(code, label, tareKg));
            } catch (NumberFormatException e) {
                // A malformed weight drops just its row, never the whole list.
            }
        }
        LOGGER.info("Exiting method tareContainers");
        return containers;
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
     * The unit price above which entering an article warns the cashier.
     * <p>
     * Administered rather than compiled in, like every other threshold of
     * this register: a shop sells nothing above ten euros and another sells
     * nothing below. Zero switches the warning off entirely.
     *
     * @return the warning threshold, in euros
     */
    public BigDecimal saleLineWarningThreshold() {
        LOGGER.info("Entering method saleLineWarningThreshold");
        LOGGER.info("Exiting method saleLineWarningThreshold");
        return bigDecimalValue("sale.line-warning-threshold");
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

    /** LC-02-07-08: applies the inherited EAN display choice to every article-touch order.
     * @return true when article tiles must display their EAN
     */
    public boolean touchShowEan() {
        return boolValue("touch.show-ean");
    }

    /** LC-02-07-11: whether article-touch prices include their EUR currency label.
     * @return the administered currency display flag
     */
    public boolean touchPriceCurrency() {
        return boolValue("touch.price-currency");
    }

    /** LC-12-07-02: applies the inherited visibility of one revenue-report rubric.
     * @param section catalogued rubric suffix
     * @return whether this rubric is displayed and printed
     */
    public boolean reportSectionVisible(String section) {
        return boolValue("report.show-" + section);
    }

    /** LC-02-07-02: bounds the administered grid width to usable touch layouts.
     * @return two to six columns
     */
    public int touchGridColumns() {
        return Math.max(2, Math.min(6, intValue("touch.grid-columns")));
    }

    /** LC-02-07-03: resolves the stable, administered priority list without duplicate EANs.
     * @return ordered article identities; unlisted articles retain alphabetical order
     */
    public java.util.List<String> touchArticleOrder() {
        return java.util.Arrays.stream(value("touch.article-order").split(";"))
                .map(String::trim).filter(code -> !code.isEmpty()).distinct().toList();
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
     * The reasons offered to the cashier for a DRAWER EXPENSE (BO-04-01-44),
     * parsed from the semicolon-separated administered list; blank entries are
     * dropped, and an empty or blank setting yields an empty list (free reason
     * entry). Never null.
     *
     * <p>Only the expense asks for one. The list used to serve every movement type
     * and therefore mixed the motives of gestures that no longer sit on the same
     * screen — a prélèvement and an apport are counted on their own pages — which
     * offered "Apport de fond" as the reason for an expense.
     *
     * @return the authorized expense reasons, in catalog order
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
     * The destinations offered for a manual withdrawal besides the safe
     * ({@code LC-12-03}), parsed from the semicolon-separated administered list;
     * blank entries are dropped, and an empty setting yields an empty list — the
     * safe then being the only destination. Never null.
     *
     * <p>The screen used to carry two buttons, VALIDER LE PRÉLÈVEMENT and VERS LE
     * COFFRE, whose difference nothing on the page explained: one flagged the
     * movement as feeding the safe, the other named no destination at all. A
     * withdrawal that does not say where the money went is not opposable, so the
     * destination is now chosen and mandatory.
     *
     * @return the administered withdrawal destinations, in catalog order
     */
    public List<String> withdrawalDestinations() {
        LOGGER.info("Entering method withdrawalDestinations");
        String raw = value("cash.withdrawal-destinations");
        if (raw.isBlank()) {
            LOGGER.info("Exiting method withdrawalDestinations");
            return List.of();
        }
        List<String> destinations = new ArrayList<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                destinations.add(trimmed);
            }
        }
        LOGGER.info("Exiting method withdrawalDestinations");
        return destinations;
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
     * The return reasons offered to the cashier (LC-05-05-01), parsed from the
     * semicolon-separated administered list; blank entries are dropped, and an
     * empty setting yields an empty list — the reason is then not asked for.
     * Never null.
     *
     * @return the administered return reasons, in order
     */
    public List<String> returnReasons() {
        LOGGER.info("Entering method returnReasons");
        String raw = value("return.reasons");
        if (raw.isBlank()) {
            LOGGER.info("Exiting method returnReasons");
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                reasons.add(trimmed);
            }
        }
        LOGGER.info("Exiting method returnReasons");
        return reasons;
    }

    /**
     * Whether the chosen return reason is printed on the return ticket
     * (LC-05-05-03). Disabled: the reason is still journaled but does not reach
     * the paper.
     *
     * @return true when the reason is printed on the return ticket
     */
    public boolean printReturnReason() {
        LOGGER.info("Entering method printReturnReason");
        LOGGER.info("Exiting method printReturnReason");
        return boolValue("return.print-reason");
    }

    /**
     * The store's system language (LC-13-06-01): the language of the register
     * screens, the customer display and the ticket when the operator has chosen
     * none and none is administered for them.
     *
     * @return the administered system language tag
     */
    public String systemLanguage() {
        LOGGER.info("Entering method systemLanguage");
        LOGGER.info("Exiting method systemLanguage");
        return value("language.system");
    }

    /**
     * The languages the operator may choose from (LC-13-06-03/04), parsed from
     * the semicolon-separated administered list; blank entries are dropped and an
     * empty setting yields an empty list. Never null — the caller narrows it to
     * the languages a bundle ships.
     *
     * @return the administered language tags, in order
     */
    public List<String> availableLanguages() {
        LOGGER.info("Entering method availableLanguages");
        String raw = value("language.available");
        if (raw.isBlank()) {
            LOGGER.info("Exiting method availableLanguages");
            return List.of();
        }
        List<String> languages = new ArrayList<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                languages.add(trimmed);
            }
        }
        LOGGER.info("Exiting method availableLanguages");
        return languages;
    }

    /**
     * Whether the operator may change the language DURING the session, a
     * transaction included (LC-13-06-04). Disabled, the change is only possible
     * before the register is opened, which LC-13-06-03 always allows.
     *
     * @return true when the language may be changed at any moment
     */
    public boolean languageChangeAllowed() {
        LOGGER.info("Entering method languageChangeAllowed");
        LOGGER.info("Exiting method languageChangeAllowed");
        return boolValue("language.operator-change");
    }

    /**
     * The second language of the customer display and the ticket in a bilingual
     * region (LC-13-06-06/11), or a blank string when the store is monolingual.
     *
     * @return the administered second language tag, possibly blank
     */
    public String customerSecondLanguage() {
        LOGGER.info("Entering method customerSecondLanguage");
        LOGGER.info("Exiting method customerSecondLanguage");
        return value("language.customer-second");
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
     * The payment means offered as the SOURCE of a financial movement
     * (LC-12-06-01/02), as the raw semicolon list of {@code CLÉ:LIBELLÉ} pairs;
     * blank means the gesture is not offered. Never null.
     *
     * @return the administered source list
     */
    public String financialMovementSources() {
        LOGGER.info("Entering method financialMovementSources");
        LOGGER.info("Exiting method financialMovementSources");
        return value("movement.financial-sources");
    }

    /**
     * The payment means offered as the DESTINATION of a financial movement
     * (LC-12-06-01/02), as the raw semicolon list of {@code CLÉ:LIBELLÉ} pairs;
     * blank means the gesture is not offered. Never null.
     *
     * @return the administered destination list
     */
    public String financialMovementDestinations() {
        LOGGER.info("Entering method financialMovementDestinations");
        LOGGER.info("Exiting method financialMovementDestinations");
        return value("movement.financial-destinations");
    }

    /**
     * Whether a financial-movement ticket is printed (LC-12-06-03).
     *
     * @return true when the ticket comes out
     */
    public boolean financialMovementPrint() {
        LOGGER.info("Entering method financialMovementPrint");
        LOGGER.info("Exiting method financialMovementPrint");
        return boolValue("movement.financial-print");
    }

    /**
     * Whether the filled change-order slip is printed (LC-12-05-08).
     *
     * @return true when the filled slip comes out
     */
    public boolean changeOrderPrint() {
        LOGGER.info("Entering method changeOrderPrint");
        LOGGER.info("Exiting method changeOrderPrint");
        return boolValue("change-order.print");
    }

    /**
     * The administered correspondence between a coin/note face value and the
     * euro value of a whole roll of that denomination (LC-12-05-06/07), as the
     * raw semicolon list of {@code FACIALE:VALEUR_ROULEAU} pairs; blank means no
     * cross-calculation is offered. Never null.
     *
     * @return the administered roll-value list
     */
    public String changeOrderRollValues() {
        LOGGER.info("Entering method changeOrderRollValues");
        LOGGER.info("Exiting method changeOrderRollValues");
        return value("change-order.roll-values");
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

    /** LC-08-02-05: whether a completed sale offers the optional e-mail capture screen.
     * @return the inherited BackOffice offer flag
     */
    public boolean ticketEmailOffer() {
        return boolValue("ticket.email-offer");
    }

    /** LC-12-04-04: whether a recorded cash supply prints its identification slip.
     * @return the inherited BackOffice print flag
     */
    public boolean cashDepositPrint() {
        return boolValue("cash.deposit-print");
    }

    /**
     * Whether the VAT regime NUMBER prints on the article line (BO-03-03-37).
     *
     * <p>A number and not a rate: the regime is numbered once by the commercial
     * management, and that number is what a Belgian or French audit reads back.
     *
     * @return true when the VAT code is printed on each article line
     */
    public boolean ticketVatCode() {
        LOGGER.info("Entering method ticketVatCode");
        LOGGER.info("Exiting method ticketVatCode");
        return boolValue("ticket.vat-code");
    }

    /**
     * Whether the exit ticket is printed INSTEAD OF the sale receipt
     * (BO-03-03-36).
     *
     * @return true when the exit ticket replaces the receipt
     */
    public boolean exitTicketEnabled() {
        LOGGER.info("Entering method exitTicketEnabled");
        LOGGER.info("Exiting method exitTicketEnabled");
        return boolValue("ticket.exit-ticket");
    }

    /**
     * How many of the dearest articles the exit ticket names (BO-03-03-36).
     *
     * <p>Bounded here rather than at the printer: a paramétreur who types a
     * hundred must cost the default and never a paper longer than the receipt
     * the document is meant to replace.
     *
     * @return the number of articles named, always between 1 and 20
     */
    public int exitTicketArticles() {
        LOGGER.info("Entering method exitTicketArticles");
        int count = intValue("ticket.exit-ticket-articles");
        LOGGER.info("Exiting method exitTicketArticles");
        return count < 1 || count > 20 ? 3 : count;
    }

    /**
     * Whether a resume ticket is printed when a parked sale comes back
     * (BO-03-03-07).
     *
     * @return true when the resume prints its own document
     */
    public boolean parkingPrintResume() {
        LOGGER.info("Entering method parkingPrintResume");
        LOGGER.info("Exiting method parkingPrintResume");
        return boolValue("parking.print-resume");
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
     * accepted as the former spelling of {@code FLOATING_WEEK_MONTH}. The register
     * and the store node read the same catalog entry, since the chaining of the
     * cashier Z reads it.
     *
     * <p>The point of the period is that the monthly revenue matches what was
     * banked: the shop's own daily reset is a different event, which belongs to the
     * store node.
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
     * names (R3).
     *
     * <p>Read as a prefix rather than compared to a list: the two floating modes
     * differ only in how often the cashier is actually counted, and every caller
     * that asks this question wants to know whether the evening may skip the count
     * at all — the financial state, in particular, only needs to know whether the
     * cashier Z skips the day.
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
     * Whether a specific negative gesture requires a supervisor endorsement
     * (EXP-32): reads {@code gesture.endorsement-<gestureKey>}. Each negative
     * function carries its own administered flag rather than sharing one.
     *
     * @param gestureKey the gesture suffix (e.g. "cancel-line", "abandon", "refund")
     * @return true when that gesture requires an endorsement
     */
    public boolean gestureEndorsementRequired(String gestureKey) {
        LOGGER.info("Entering method gestureEndorsementRequired with gestureKey: " + gestureKey);
        LOGGER.info("Exiting method gestureEndorsementRequired");
        return boolValue("gesture.endorsement-" + gestureKey);
    }

    /**
     * Whether the reassignment of a closed ticket to a fidelity customer also
     * prints a duplicata for the POINT OF VENTE (LC-03-06-05): the customer's
     * new ticket always prints, the shop's copy is administered.
     *
     * @return true when the shop's duplicata prints too
     */
    public boolean reassignPdvDuplicate() {
        LOGGER.info("Entering method reassignPdvDuplicate");
        LOGGER.info("Exiting method reassignPdvDuplicate");
        return boolValue("reassign.pdv-duplicate");
    }

    /**
     * The scope of a supervisor endorsement (EXP-33): {@code PER_OP} (each gesture
     * asks) or {@code PER_TICKET} (the endorsement stays open until FIN SUPERVISEUR
     * or the end of the ticket).
     *
     * @return the administered scope, never null
     */
    public String endorsementScope() {
        LOGGER.info("Entering method endorsementScope");
        LOGGER.info("Exiting method endorsementScope");
        return value("gesture.endorsement-scope");
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

    /**
     * The administered LEVEL of a control point (BO-10-02-01 to -10), as it
     * stands in the catalogue.
     *
     * <p>Read as text and resolved by {@code ControlLevel}, not here: the
     * register's rules normalize what a paramétreur typed, this service only
     * hands it over.
     *
     * @param key the control point's settings key
     * @return the administered value, never null
     */
    public String controlLevel(String key) {
        LOGGER.info("Entering method controlLevel with key: " + key);
        LOGGER.info("Exiting method controlLevel");
        return value(key);
    }

    /**
     * The quantity beyond which the quantity control fires (BO-10-02-06).
     *
     * @return the administered ceiling, in units
     */
    public int controlQuantityMax() {
        LOGGER.info("Entering method controlQuantityMax");
        LOGGER.info("Exiting method controlQuantityMax");
        return intValue("control.quantity-max");
    }

    /**
     * The ticket total beyond which the ticket-total control fires
     * (BO-10-02-07).
     *
     * <p>Null when the shop administers no ceiling, which is the default and
     * means the control never fires — a blank is the absence of a ceiling and
     * not a ceiling of zero, which would alert on every sale.
     *
     * @return the ceiling, or null when none is administered or the value
     *         cannot be read as an amount
     */
    public java.math.BigDecimal controlTicketTotalMax() {
        LOGGER.info("Entering method controlTicketTotalMax");
        String raw = value("control.ticket-total-max");
        if (raw.isBlank()) {
            LOGGER.info("Exiting method controlTicketTotalMax");
            return null;
        }
        try {
            LOGGER.info("Exiting method controlTicketTotalMax");
            return new java.math.BigDecimal(raw.trim().replace(',', '.'));
        } catch (NumberFormatException malformed) {
            // A ceiling nobody can read is no ceiling: a typo must not alert on
            // every sale of the day.
            LOGGER.warnf("Plafond de total de ticket illisible (%s), aucun plafond appliqué", raw);
            LOGGER.info("Exiting method controlTicketTotalMax");
            return null;
        }
    }

    /**
     * The article amount beyond which the item-amount control fires
     * (BO-10-02-16).
     *
     * @return the ceiling, or null when none is administered or the value
     *         cannot be read as an amount
     */
    public java.math.BigDecimal controlItemAmountMax() {
        LOGGER.info("Entering method controlItemAmountMax");
        java.math.BigDecimal ceiling = amountCeiling("control.item-amount-max", "montant d'article");
        LOGGER.info("Exiting method controlItemAmountMax");
        return ceiling;
    }

    /**
     * The settlement amount beyond which the payment-amount control fires
     * (BO-10-02-17).
     *
     * @return the ceiling, or null when none is administered or the value
     *         cannot be read as an amount
     */
    public java.math.BigDecimal controlPaymentAmountMax() {
        LOGGER.info("Entering method controlPaymentAmountMax");
        java.math.BigDecimal ceiling = amountCeiling("control.payment-amount-max", "montant de règlement");
        LOGGER.info("Exiting method controlPaymentAmountMax");
        return ceiling;
    }

    /**
     * Reads an administered AMOUNT CEILING, blank and unreadable alike meaning
     * no ceiling at all.
     *
     * <p>A blank is the absence of a ceiling and not a ceiling of zero, which
     * would fire on every sale; a typo answers the same, because a figure
     * nobody can read must not start refusing gestures across the shop.
     *
     * @param key the settings key carrying the ceiling
     * @param label the wording naming that ceiling in the warning log
     * @return the ceiling, or null when there is none to apply
     */
    private java.math.BigDecimal amountCeiling(String key, String label) {
        String raw = value(key);
        if (raw.isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(raw.trim().replace(',', '.'));
        } catch (NumberFormatException malformed) {
            LOGGER.warnf("Plafond de %s illisible (%s), aucun plafond appliqué", label, raw);
            return null;
        }
    }

    /**
     * Whether an account-customer row without a complete address is REJECTED at
     * integration (BO-10-08-27).
     *
     * @return true when the three address lines are required
     */
    public boolean customerIntegrationRequiresAddress() {
        LOGGER.info("Entering method customerIntegrationRequiresAddress");
        LOGGER.info("Exiting method customerIntegrationRequiresAddress");
        return boolValue("customer.integration-require-address");
    }

    /**
     * Whether the APPLIED tare is printed under a weighed line (BO-10-05-04).
     *
     * @return true when the ticket states the tare in kilograms
     */
    public boolean tarePrinted() {
        LOGGER.info("Entering method tarePrinted");
        LOGGER.info("Exiting method tarePrinted");
        return boolValue("balance.print-tare");
    }

    /**
     * The FISCAL CERTIFICATION NUMBER of the software, as the administration
     * delivered it (BO-10-06-03).
     *
     * @return the administered number, blank where no country requires one
     */
    public String fiscalCertificateNumber() {
        LOGGER.info("Entering method fiscalCertificateNumber");
        String number = value("fiscal.certificate-number");
        LOGGER.info("Exiting method fiscalCertificateNumber");
        return number;
    }

    /**
     * Whether the register issues the PORTUGUESE FISCAL DOCUMENTS — FS, FT, NC
     * and RC (LC-08-01-05, LC-08-04-18 to -23, LC-05-07-03).
     *
     * @return true when every sale and return is stated on a Portuguese document
     */
    public boolean ptDocuments() {
        LOGGER.info("Entering method ptDocuments");
        LOGGER.info("Exiting method ptDocuments");
        return boolValue("fiscal.pt-documents");
    }

    /**
     * The ATCUD VALIDATION CODE the tax administration gave to this register's
     * series of one document type (LC-08-04-23).
     *
     * <p>Read off the administered {@code TYPE:CODE} list; an entry that is not a
     * pair is ignored, and a type without a code answers {@code 0}, the code the
     * administration accepts for a series not yet communicated.
     *
     * @param type the document type, FS, FT, NC or RC
     * @return the validation code, {@code 0} when none is administered
     */
    public String atcudCode(String type) {
        LOGGER.info("Entering method atcudCode with type: " + type);
        for (String entry : value("fiscal.atcud-codes").split(";")) {
            String[] pair = entry.split(":");
            // BO-03-04-27: a validation code is at least eight characters
            // long; anything shorter is not the administration's code.
            if (pair.length == 2 && pair[0].trim().equalsIgnoreCase(type)
                    && pair[1].trim().length() >= ATCUD_MIN_LENGTH) {
                LOGGER.info("Exiting method atcudCode");
                return pair[1].trim();
            }
        }
        LOGGER.info("Exiting method atcudCode");
        return "0";
    }

    /**
     * The pattern of the codes printed on the quotes, orders and sale vouchers of
     * the commercial management tool, recognized at the scan (LC-06-02-01, -09).
     *
     * @return the administered regular expression, blank when none is administered
     */
    public String orderExternalCodePattern() {
        LOGGER.info("Entering method orderExternalCodePattern");
        LOGGER.info("Exiting method orderExternalCodePattern");
        return value("order.external-code-pattern");
    }

    /**
     * Whether a recalled document's articles take the register's own current
     * price rather than the price the document states (LC-06-02-08, -12).
     *
     * @return true when the store administers the register's price ({@code CAISSE})
     */
    public boolean orderRecallPriceFromRegister() {
        LOGGER.info("Entering method orderRecallPriceFromRegister");
        LOGGER.info("Exiting method orderRecallPriceFromRegister");
        return "CAISSE".equalsIgnoreCase(value("order.recall-price-source").trim());
    }

    /**
     * Whether each deposit taken on an order prints a deposit invoice
     * (LC-08-04-15).
     *
     * @return true when the store prints the deposit invoice
     */
    public boolean orderDepositInvoice() {
        LOGGER.info("Entering method orderDepositInvoice");
        LOGGER.info("Exiting method orderDepositInvoice");
        return boolValue("order.deposit-invoice");
    }

    /**
     * The pattern of the codes of the baskets built on the store's selfscanning
     * devices, recognized at the scan (LC-06-05-03).
     *
     * @return the administered regular expression, blank when none is administered
     */
    public String selfScanCodePattern() {
        LOGGER.info("Entering method selfScanCodePattern");
        LOGGER.info("Exiting method selfScanCodePattern");
        return value("selfscan.code-pattern");
    }

    /**
     * The percentage of selfscanning baskets drawn for a partial control
     * (LC-06-05-04).
     *
     * @return the rate, 0 to 100
     */
    public int selfScanPartialControlRate() {
        LOGGER.info("Entering method selfScanPartialControlRate");
        LOGGER.info("Exiting method selfScanPartialControlRate");
        return Math.max(0, Math.min(100, intValue("selfscan.partial-control-rate")));
    }

    /**
     * Whether a gift-card payment prints its own ticket besides the receipt
     * (LC-07-11-03).
     *
     * @return true when the slip is printed
     */
    public boolean giftCardPaymentTicket() {
        LOGGER.info("Entering method giftCardPaymentTicket");
        LOGGER.info("Exiting method giftCardPaymentTicket");
        return boolValue("giftcard.payment-ticket");
    }

    /**
     * Whether the returns of a mixed ticket are detailed on a separate ticket
     * (LC-05-07-02).
     *
     * @return true when the detail is printed apart
     */
    public boolean returnSeparateDetail() {
        LOGGER.info("Entering method returnSeparateDetail");
        LOGGER.info("Exiting method returnSeparateDetail");
        return boolValue("return.separate-detail");
    }

    /**
     * The label of the key launching the external program (LC-09-01-04).
     *
     * @return the label
     */
    public String externalProgramLabel() {
        LOGGER.info("Entering method externalProgramLabel");
        LOGGER.info("Exiting method externalProgramLabel");
        return value("external-program.label");
    }

    /**
     * The path of the external program (LC-09-01-04).
     *
     * @return the path, trimmed, blank when none
     */
    public String externalProgramPath() {
        LOGGER.info("Entering method externalProgramPath");
        LOGGER.info("Exiting method externalProgramPath");
        return value("external-program.path").trim();
    }

    /**
     * The arguments of the external program (LC-09-01-04).
     *
     * @return the arguments as administered, trimmed
     */
    public String externalProgramArguments() {
        LOGGER.info("Entering method externalProgramArguments");
        LOGGER.info("Exiting method externalProgramArguments");
        return value("external-program.arguments").trim();
    }

    /**
     * Whether a loyalty card must carry a valid Luhn check digit (BO-03-06-54).
     *
     * @return true when the check digit is controlled
     */
    public boolean fidelityCardLuhn() {
        LOGGER.info("Entering method fidelityCardLuhn");
        LOGGER.info("Exiting method fidelityCardLuhn");
        return boolValue("fidelity.card-luhn");
    }

    /**
     * The number of remaining days below which a GS1 article is refused (BO-03-06-64).
     *
     * @return the number of days, never negative
     */
    public int gs1ExpiryBlockDays() {
        LOGGER.info("Entering method gs1ExpiryBlockDays");
        LOGGER.info("Exiting method gs1ExpiryBlockDays");
        return Math.max(intValue("gs1.expiry-block-days"), 0);
    }

    /**
     * The message shown when a GS1 article is refused for its date (BO-03-06-64).
     *
     * @return the administered message, blank when the default applies
     */
    public String gs1ExpiryMessage() {
        LOGGER.info("Entering method gs1ExpiryMessage");
        LOGGER.info("Exiting method gs1ExpiryMessage");
        return value("gs1.expiry-message").trim();
    }

    /**
     * The message shown when a GS1 article is short-dated (BO-03-06-64).
     *
     * @return the administered message, blank when the default applies
     */
    public String gs1ShortDateMessage() {
        LOGGER.info("Entering method gs1ShortDateMessage");
        LOGGER.info("Exiting method gs1ShortDateMessage");
        return value("gs1.short-date-message").trim();
    }

    /**
     * The pattern of the queue-busting transaction codes (BO-03-06-57).
     *
     * @return the pattern, blank when none is administered
     */
    public String qbustingCodePattern() {
        LOGGER.info("Entering method qbustingCodePattern");
        LOGGER.info("Exiting method qbustingCodePattern");
        return value("qbusting.code-pattern").trim();
    }

    /**
     * The barcode symbologies the register accepts (BO-03-06-01).
     *
     * @return the semicolon list, blank when every symbology is accepted
     */
    public String scanSymbologies() {
        LOGGER.info("Entering method scanSymbologies");
        LOGGER.info("Exiting method scanSymbologies");
        return value("scan.symbologies").trim();
    }

    /**
     * What the register does when it reads a barcode while offline (BO-03-06-45).
     *
     * @return NONE, INFO or BLOCK as administered
     */
    public String scanOfflineAlert() {
        LOGGER.info("Entering method scanOfflineAlert");
        LOGGER.info("Exiting method scanOfflineAlert");
        return value("scan.offline-alert").trim();
    }

    /**
     * What the register does when a scanned voucher is known nowhere (BO-03-06-48).
     *
     * @return INFO or BLOCK as administered
     */
    public String voucherUnknownAlert() {
        LOGGER.info("Entering method voucherUnknownAlert");
        LOGGER.info("Exiting method voucherUnknownAlert");
        return value("voucher.unknown-alert").trim();
    }

    /**
     * The number of articles re-scanned in a partial control (LC-06-05-04).
     *
     * @return the number of articles, at least one
     */
    public int selfScanPartialControlItems() {
        LOGGER.info("Entering method selfScanPartialControlItems");
        LOGGER.info("Exiting method selfScanPartialControlItems");
        return Math.max(1, intValue("selfscan.partial-control-items"));
    }

    /**
     * Whether the basket sent to the offer engine carries the article's FAMILY
     * and SUB-FAMILY (BO-10-03-19).
     *
     * @return true when the nomenclature travels with each line
     */
    public boolean fidelitySendSubFamily() {
        LOGGER.info("Entering method fidelitySendSubFamily");
        LOGGER.info("Exiting method fidelitySendSubFamily");
        return boolValue("fidelity.send-subfamily");
    }

    /**
     * Whether the AMOUNT DUE is emitted in enlarged characters (BO-10-07-14).
     *
     * @return true when the total line carries the enlargement directive
     */
    public boolean dueAmountLarge() {
        LOGGER.info("Entering method dueAmountLarge");
        LOGGER.info("Exiting method dueAmountLarge");
        return boolValue("ticket.due-amount-large");
    }

    /**
     * Where the ECO-PARTICIPATION is printed on the ticket (BO-10-06-06), read
     * as administered and resolved by the caller — {@code LINE},
     * {@code TOTAL}, or anything else, which means nowhere.
     *
     * @return the administered placement
     */
    public String ecoTaxPrint() {
        LOGGER.info("Entering method ecoTaxPrint");
        String placement = value("ticket.ecotax-print");
        LOGGER.info("Exiting method ecoTaxPrint");
        return placement;
    }

    /**
     * Whether each referential integration leaves a dated row in the audit
     * (BO-10-08-23).
     *
     * @return true when the integrations are traced
     */
    public boolean referentialUpdateAuditEnabled() {
        LOGGER.info("Entering method referentialUpdateAuditEnabled");
        LOGGER.info("Exiting method referentialUpdateAuditEnabled");
        return boolValue("referential.update-audit-enabled");
    }

    /**
     * Whether the register's TRANSACTION COUNTER restarts at one at the end of
     * a period (BO-09-03-04).
     *
     * @return true when the ticket numbering restarts
     */
    public boolean resetTicketCounterAtPeriodEnd() {
        LOGGER.info("Entering method resetTicketCounterAtPeriodEnd");
        LOGGER.info("Exiting method resetTicketCounterAtPeriodEnd");
        return boolValue("period.reset-ticket-counter");
    }

    /**
     * The number of days after which a secret must be renewed (BO-01-02-12).
     *
     * @return the delay in days, zero meaning no expiry at all
     */
    public int passwordExpiryDays() {
        LOGGER.info("Entering method passwordExpiryDays");
        int days = intValue("auth.password-expiry-days");
        LOGGER.info("Exiting method passwordExpiryDays");
        return days;
    }

    /**
     * The back-office sign-in mode posed at this node's echelon (BO-01-02-09).
     *
     * @return ANY, PASSWORD or SSO; ANY for an unknown value
     */
    public com.intermarche.edge.domain.people.Employee.AuthMode backOfficeSignIn() {
        LOGGER.info("Entering method backOfficeSignIn");
        LOGGER.info("Exiting method backOfficeSignIn");
        return com.intermarche.edge.domain.people.Employee.AuthMode.from(value("auth.bo-sign-in"));
    }

    /**
     * How many consecutive wrong back-office passwords lock an account
     * (BO-01-01-01).
     *
     * @return the threshold, zero meaning no lockout at all
     */
    public int backOfficeMaxFailures() {
        LOGGER.info("Entering method backOfficeMaxFailures");
        int threshold = intValue("auth.bo-max-failures");
        LOGGER.info("Exiting method backOfficeMaxFailures");
        return threshold;
    }

    /**
     * How long a register may stay silent before the back office declares it
     * disconnected ({@code BO-04-03-05}).
     *
     * @return the administered tolerance in minutes
     */
    public int registerOfflineMinutes() {
        LOGGER.info("Entering method registerOfflineMinutes");
        LOGGER.info("Exiting method registerOfflineMinutes");
        return intValue("supervision.register-offline-minutes");
    }

    /**
     * The complementary indicators the supervision dashboard must show, in
     * display order ({@code BO-04-03-01}, {@code BO-04-03-03},
     * {@code BO-04-03-15}).
     *
     * @return the administered list, semicolon-separated, blank for none
     */
    public String dashboardIndicators() {
        LOGGER.info("Entering method dashboardIndicators");
        LOGGER.info("Exiting method dashboardIndicators");
        return value("dashboard.indicators");
    }

    /**
     * The weekday that closes the week ({@code BO-09-01-07}).
     *
     * @return the administered weekday, in English and upper case
     */
    public String periodWeekEndDay() {
        LOGGER.info("Entering method periodWeekEndDay");
        LOGGER.info("Exiting method periodWeekEndDay");
        return value("period.week-end-day");
    }

    /**
     * The day of month that closes the month ({@code BO-09-01-07}).
     *
     * @return the administered day, zero or less meaning the month's last
     */
    public int periodMonthEndDay() {
        LOGGER.info("Entering method periodMonthEndDay");
        LOGGER.info("Exiting method periodMonthEndDay");
        return intValue("period.month-end-day");
    }

    /**
     * The day and month that close the year ({@code BO-09-01-07}).
     *
     * @return the administered date, as {@code jj/mm}
     */
    public String periodYearEndDate() {
        LOGGER.info("Entering method periodYearEndDate");
        LOGGER.info("Exiting method periodYearEndDate");
        return value("period.year-end-date");
    }

    /**
     * The reports edited automatically at the end of period
     * ({@code BO-09-01-10}, {@code BO-09-01-11}).
     *
     * @return the administered list, semicolon-separated, blank for none
     */
    public String periodReports() {
        LOGGER.info("Entering method periodReports");
        LOGGER.info("Exiting method periodReports");
        return value("period.reports");
    }

    /**
     * The format those editions are rendered in ({@code BO-09-01-11}).
     *
     * @return the administered format token
     */
    public String periodReportsFormat() {
        LOGGER.info("Entering method periodReportsFormat");
        LOGGER.info("Exiting method periodReportsFormat");
        return value("period.reports-format");
    }

    /**
     * How long before the end of period the back office starts announcing
     * the countdown and its alerts ({@code BO-09-01-04},
     * {@code BO-09-02-02}).
     *
     * @return the administered horizon in minutes
     */
    public int alertCountdownMinutes() {
        LOGGER.info("Entering method alertCountdownMinutes");
        LOGGER.info("Exiting method alertCountdownMinutes");
        return intValue("period.alert-countdown-minutes");
    }

    /**
     * Whether a settled service request stays on the dashboard, marked with
     * what became of it ({@code BO-04-02-03}, {@code -04}, {@code -05});
     * disabled, it leaves the list the moment it is settled.
     *
     * @return true when a settled demand is kept displayed
     */
    public boolean settledCallsKept() {
        LOGGER.info("Entering method settledCallsKept");
        LOGGER.info("Exiting method settledCallsKept");
        return boolValue("supervision.settled-calls-kept");
    }

    /**
     * How long a locked back-office account stays locked, in minutes
     * (BO-01-01-01).
     *
     * <p>Zero or less answers fifteen and not zero: a lockout with no duration
     * is an account lost until somebody edits the database.
     *
     * @return the duration in minutes, always strictly positive
     */
    public int backOfficeLockoutMinutes() {
        LOGGER.info("Entering method backOfficeLockoutMinutes");
        int minutes = intValue("auth.bo-lockout-minutes");
        LOGGER.info("Exiting method backOfficeLockoutMinutes");
        return minutes > 0 ? minutes : 15;
    }
    // ---- lot 18: E getters ----

    /**
     * Whether this point of sale integrates the validated e-commerce orders (BO-10-01-01).
     *
     * @return true when enabled
     */
    public boolean ecommerceEnabled() {
        LOGGER.info("Entering method ecommerceEnabled");
        LOGGER.info("Exiting method ecommerceEnabled");
        return boolValue("channel.ecommerce.enabled");
    }

    /**
     * The price source of the articles of an e-commerce basket recalled at the till: POS (the register's price), TRANSACTION (the document's), or empty for the global rule of the recalled documents (BO-10-01-07).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String ecommercePriceSource() {
        LOGGER.info("Entering method ecommercePriceSource");
        LOGGER.info("Exiting method ecommercePriceSource");
        return value("channel.ecommerce.price-source").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Whether an e-commerce order carrying an article forbidden to sale is integrated (ACCEPT) or rejected whole (REJECT) (BO-10-01-14).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String ecommerceForbiddenItems() {
        LOGGER.info("Entering method ecommerceForbiddenItems");
        LOGGER.info("Exiting method ecommerceForbiddenItems");
        return value("channel.ecommerce.forbidden-items").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The generic EAN carrying the articles of an e-commerce order the referential does not know, or empty when the line keeps its own code (BO-10-01-06).
     *
     * @return the administered value, trimmed, possibly empty
     */
    public String ecommerceUnknownItemEan() {
        LOGGER.info("Entering method ecommerceUnknownItemEan");
        LOGGER.info("Exiting method ecommerceUnknownItemEan");
        return value("channel.ecommerce.unknown-item-ean").trim();
    }

    /**
     * The channel sent to the promotion engine for the e-commerce baskets: POINT_OF_SALE, WEB, OTHER, or empty for none (BO-10-01-13).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String ecommercePromotionChannel() {
        LOGGER.info("Entering method ecommercePromotionChannel");
        LOGGER.info("Exiting method ecommercePromotionChannel");
        return value("channel.ecommerce.promotion-channel").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The code of the island the e-commerce virtual register belongs to, or empty for none (BO-10-01-08).
     *
     * @return the administered value, trimmed, possibly empty
     */
    public String ecommerceIsland() {
        LOGGER.info("Entering method ecommerceIsland");
        LOGGER.info("Exiting method ecommerceIsland");
        return value("channel.ecommerce.island").trim();
    }

    /**
     * The identifier of the virtual register the e-commerce transactions are booked on (BO-10-01-08).
     *
     * @return the administered value, trimmed, possibly empty
     */
    public String ecommerceTerminal() {
        LOGGER.info("Entering method ecommerceTerminal");
        LOGGER.info("Exiting method ecommerceTerminal");
        return value("channel.ecommerce.terminal").trim();
    }

    /**
     * The login of the employee the e-commerce transactions are booked in the name of (BO-10-01-03).
     *
     * @return the administered value, trimmed, possibly empty
     */
    public String ecommerceOperator() {
        LOGGER.info("Entering method ecommerceOperator");
        LOGGER.info("Exiting method ecommerceOperator");
        return value("channel.ecommerce.operator").trim();
    }

    /**
     * The tender an e-commerce order paid by a means the register does not know is integrated under (BO-10-01-09).
     *
     * @return the administered value, trimmed, possibly empty
     */
    public String ecommerceDefaultTender() {
        LOGGER.info("Entering method ecommerceDefaultTender");
        LOGGER.info("Exiting method ecommerceDefaultTender");
        return value("channel.ecommerce.default-tender").trim();
    }

    /**
     * Whether the e-commerce order as the platform sent it is archived beside its report (BO-10-01-11).
     *
     * @return true when enabled
     */
    public boolean ecommerceArchive() {
        LOGGER.info("Entering method ecommerceArchive");
        LOGGER.info("Exiting method ecommerceArchive");
        return boolValue("channel.ecommerce.archive");
    }

    /**
     * How many days the archived e-commerce orders are kept before the end-of-period purge, zero for no limit (BO-10-01-11).
     *
     * @return the administered number, never negative
     */
    public int ecommerceArchiveDays() {
        LOGGER.info("Entering method ecommerceArchiveDays");
        LOGGER.info("Exiting method ecommerceArchiveDays");
        return Math.max(0, intValue("channel.ecommerce.archive-days"));
    }

    /**
     * The return types allowed for the return of a whole e-commerce ticket, semicolon-separated, empty for all (BO-10-01-04).
     *
     * @return the administered value, trimmed, possibly empty
     */
    public String ecommerceReturnTicketTypes() {
        LOGGER.info("Entering method ecommerceReturnTicketTypes");
        LOGGER.info("Exiting method ecommerceReturnTicketTypes");
        return value("channel.ecommerce.return-ticket-types").trim();
    }

    /**
     * The return types allowed for the return of part of the articles of an e-commerce ticket, semicolon-separated, empty for all (BO-10-01-05).
     *
     * @return the administered value, trimmed, possibly empty
     */
    public String ecommerceReturnArticleTypes() {
        LOGGER.info("Entering method ecommerceReturnArticleTypes");
        LOGGER.info("Exiting method ecommerceReturnArticleTypes");
        return value("channel.ecommerce.return-article-types").trim();
    }

    /**
     * Whether self-scanning is open on this point of sale (BO-10-01-15).
     *
     * @return true when enabled
     */
    public boolean selfScanEnabled() {
        LOGGER.info("Entering method selfScanEnabled");
        LOGGER.info("Exiting method selfScanEnabled");
        return boolValue("channel.selfscan.enabled");
    }

    /**
     * How the till retrieves a self-scanning basket: SCAN (its barcode read), KEYED (its code keyed on the recall screen) or BOTH (BO-10-01-16).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String selfScanRetrievalMode() {
        LOGGER.info("Entering method selfScanRetrievalMode");
        LOGGER.info("Exiting method selfScanRetrievalMode");
        return value("channel.selfscan.retrieval-mode").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * When articles may be keyed around the retrieval of a self-scanning basket: BEFORE, AFTER, BOTH or NONE (BO-10-01-17).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String selfScanAddItems() {
        LOGGER.info("Entering method selfScanAddItems");
        LOGGER.info("Exiting method selfScanAddItems");
        return value("channel.selfscan.add-items").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * What identifies the self-scanning basket to recall: TRANSACTION (its barcode) or CUSTOMER (the customer number) (BO-10-01-18).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String selfScanIdentifier() {
        LOGGER.info("Entering method selfScanIdentifier");
        LOGGER.info("Exiting method selfScanIdentifier");
        return value("channel.selfscan.identifier").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The price source of the articles of a self-scanning basket recalled at the till: POS (the register's price), TRANSACTION (the document's), or empty for the global rule of the recalled documents (BO-10-01-19).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String selfScanPriceSource() {
        LOGGER.info("Entering method selfScanPriceSource");
        LOGGER.info("Exiting method selfScanPriceSource");
        return value("channel.selfscan.price-source").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Whether the coupons scanned in a self-scanning basket are accepted and taken back at the till (BO-10-01-20).
     *
     * @return true when enabled
     */
    public boolean selfScanCoupons() {
        LOGGER.info("Entering method selfScanCoupons");
        LOGGER.info("Exiting method selfScanCoupons");
        return boolValue("channel.selfscan.coupons");
    }

    /**
     * The generic EAN carrying the articles of a self-scanning basket the referential does not know, or empty when the line keeps its own code (BO-10-01-21).
     *
     * @return the administered value, trimmed, possibly empty
     */
    public String selfScanUnknownItemEan() {
        LOGGER.info("Entering method selfScanUnknownItemEan");
        LOGGER.info("Exiting method selfScanUnknownItemEan");
        return value("channel.selfscan.unknown-item-ean").trim();
    }

    /**
     * The channel sent to the promotion engine for the self-scanning baskets: POINT_OF_SALE, WEB, OTHER, or empty for none (BO-10-01-22).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String selfScanPromotionChannel() {
        LOGGER.info("Entering method selfScanPromotionChannel");
        LOGGER.info("Exiting method selfScanPromotionChannel");
        return value("channel.selfscan.promotion-channel").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Whether a self-scanning basket carrying an article forbidden to sale is integrated (ACCEPT) or rejected whole (REJECT) (BO-10-01-23).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String selfScanForbiddenItems() {
        LOGGER.info("Entering method selfScanForbiddenItems");
        LOGGER.info("Exiting method selfScanForbiddenItems");
        return value("channel.selfscan.forbidden-items").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Whether a commercial-management basket carrying an article forbidden to sale is integrated (ACCEPT) or rejected whole (REJECT) (BO-10-01-24).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String commercialForbiddenItems() {
        LOGGER.info("Entering method commercialForbiddenItems");
        LOGGER.info("Exiting method commercialForbiddenItems");
        return value("channel.commercial.forbidden-items").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * How the till retrieves a commercial-management basket: SCAN (its barcode read), KEYED (its code keyed on the recall screen) or BOTH (BO-10-01-25).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String commercialRetrievalMode() {
        LOGGER.info("Entering method commercialRetrievalMode");
        LOGGER.info("Exiting method commercialRetrievalMode");
        return value("channel.commercial.retrieval-mode").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The direction and mechanism the commercial-management documents reach the store node by: PUSH or PULL (BO-10-01-25).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String commercialTransmission() {
        LOGGER.info("Entering method commercialTransmission");
        LOGGER.info("Exiting method commercialTransmission");
        return value("channel.commercial.transmission").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The address the store node pulls the commercial-management documents from in PULL mode, empty for none (BO-10-01-25).
     *
     * @return the administered value, trimmed, possibly empty
     */
    public String commercialPullUrl() {
        LOGGER.info("Entering method commercialPullUrl");
        LOGGER.info("Exiting method commercialPullUrl");
        return value("channel.commercial.pull-url").trim();
    }

    /**
     * The interval between two pulls of the commercial-management documents in PULL mode, in minutes, never less than one (BO-10-01-25).
     *
     * @return the administered number, never negative
     */
    public int commercialPullMinutes() {
        LOGGER.info("Entering method commercialPullMinutes");
        LOGGER.info("Exiting method commercialPullMinutes");
        return Math.max(0, intValue("channel.commercial.pull-minutes"));
    }

    /**
     * The code of the document template printed automatically after a commercial-management document is settled, empty for none (BO-10-01-26).
     *
     * @return the administered value, trimmed, possibly empty
     */
    public String commercialAutoPrintTemplate() {
        LOGGER.info("Entering method commercialAutoPrintTemplate");
        LOGGER.info("Exiting method commercialAutoPrintTemplate");
        return value("channel.commercial.auto-print-template").trim();
    }

    /**
     * The tender the deposits taken on a commercial-management document are recorded under, empty for cash (BO-10-01-27).
     *
     * @return the administered value, trimmed, possibly empty
     */
    public String commercialDepositTender() {
        LOGGER.info("Entering method commercialDepositTender");
        LOGGER.info("Exiting method commercialDepositTender");
        return value("channel.commercial.deposit-tender").trim();
    }

    /**
     * The channel sent to the promotion engine for the commercial-management baskets: POINT_OF_SALE, WEB, OTHER, or empty for none (BO-10-01-28).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String commercialPromotionChannel() {
        LOGGER.info("Entering method commercialPromotionChannel");
        LOGGER.info("Exiting method commercialPromotionChannel");
        return value("channel.commercial.promotion-channel").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The price source of the articles of a commercial-management basket recalled at the till: POS (the register's price), TRANSACTION (the document's), or empty for the global rule of the recalled documents (BO-10-01-29).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String commercialPriceSource() {
        LOGGER.info("Entering method commercialPriceSource");
        LOGGER.info("Exiting method commercialPriceSource");
        return value("channel.commercial.price-source").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * How the till retrieves a queue-busting basket: SCAN (its barcode read), KEYED (its code keyed on the recall screen) or BOTH (BO-10-01-30).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String qbustingRetrievalMode() {
        LOGGER.info("Entering method qbustingRetrievalMode");
        LOGGER.info("Exiting method qbustingRetrievalMode");
        return value("channel.qbusting.retrieval-mode").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The channel sent to the promotion engine for the queue-busting baskets: POINT_OF_SALE, WEB, OTHER, or empty for none (BO-10-01-31).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String qbustingPromotionChannel() {
        LOGGER.info("Entering method qbustingPromotionChannel");
        LOGGER.info("Exiting method qbustingPromotionChannel");
        return value("channel.qbusting.promotion-channel").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Whether a queue-busting basket carrying an article forbidden to sale is integrated (ACCEPT) or rejected whole (REJECT) (BO-10-01-32).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String qbustingForbiddenItems() {
        LOGGER.info("Entering method qbustingForbiddenItems");
        LOGGER.info("Exiting method qbustingForbiddenItems");
        return value("channel.qbusting.forbidden-items").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The price source of the articles of a queue-busting basket recalled at the till: POS (the register's price), TRANSACTION (the document's), or empty for the global rule of the recalled documents (BO-10-01-33).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String qbustingPriceSource() {
        LOGGER.info("Entering method qbustingPriceSource");
        LOGGER.info("Exiting method qbustingPriceSource");
        return value("channel.qbusting.price-source").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The channel sent to the promotion engine for the mobility baskets: POINT_OF_SALE, WEB, OTHER, or empty for none (BO-10-01-34).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String mobilityPromotionChannel() {
        LOGGER.info("Entering method mobilityPromotionChannel");
        LOGGER.info("Exiting method mobilityPromotionChannel");
        return value("channel.mobility.promotion-channel").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The price source of the articles of a mobility basket recalled at the till: POS (the register's price), TRANSACTION (the document's), or empty for the global rule of the recalled documents (BO-10-01-36).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String mobilityPriceSource() {
        LOGGER.info("Entering method mobilityPriceSource");
        LOGGER.info("Exiting method mobilityPriceSource");
        return value("channel.mobility.price-source").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Whether a mobility basket carrying an article forbidden to sale is integrated (ACCEPT) or rejected whole (REJECT) (BO-10-01-37).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String mobilityForbiddenItems() {
        LOGGER.info("Entering method mobilityForbiddenItems");
        LOGGER.info("Exiting method mobilityForbiddenItems");
        return value("channel.mobility.forbidden-items").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The tender added for the difference when a mobility transaction is integrated with a paid total below the ticket total, empty for none (BO-10-01-39).
     *
     * @return the administered value, trimmed, possibly empty
     */
    public String mobilityFictitiousTender() {
        LOGGER.info("Entering method mobilityFictitiousTender");
        LOGGER.info("Exiting method mobilityFictitiousTender");
        return value("channel.mobility.fictitious-tender").trim();
    }

    /**
     * How the till retrieves a sales-slip basket: SCAN (its barcode read), KEYED (its code keyed on the recall screen) or BOTH (BO-10-01-40).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String salesSlipRetrievalMode() {
        LOGGER.info("Entering method salesSlipRetrievalMode");
        LOGGER.info("Exiting method salesSlipRetrievalMode");
        return value("channel.sales-slip.retrieval-mode").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The price source of the articles of a sales-slip basket recalled at the till: POS (the register's price), TRANSACTION (the document's), or empty for the global rule of the recalled documents (BO-10-01-41).
     *
     * @return the administered word, trimmed and upper-cased, possibly empty
     */
    public String salesSlipPriceSource() {
        LOGGER.info("Entering method salesSlipPriceSource");
        LOGGER.info("Exiting method salesSlipPriceSource");
        return value("channel.sales-slip.price-source").trim().toUpperCase(java.util.Locale.ROOT);
    }

    // ---- lot 18: F getters ----

    /**
     * Whether the drawer opens while the register is in training mode
     * (BO-10-02-12).
     *
     * @return true when the training mode opens the drawer like a real sale
     */
    public boolean drawerOpenInTraining() {
        LOGGER.info("Entering method drawerOpenInTraining");
        LOGGER.info("Exiting method drawerOpenInTraining");
        return boolValue("drawer.open-in-training");
    }

    /**
     * Whether an unknown scanned code chains to the direct entry by family or
     * code (BO-10-02-14).
     *
     * @return true when the register proposes the family/code entry
     */
    public boolean unknownScanManualEntry() {
        LOGGER.info("Entering method unknownScanManualEntry");
        LOGGER.info("Exiting method unknownScanManualEntry");
        return boolValue("scan.unknown-manual-entry");
    }

    /**
     * The rounding rule of the amounts computed at the till (BO-10-02-18):
     * line totals, VAT ventilation, tax-excluded figures of the invoices.
     *
     * <p>Only the five modes the parameter offers are read; anything else,
     * {@code UNNECESSARY} included — which throws on the first inexact
     * division — answers {@code HALF_UP}, the rule every computation followed
     * before the parameter existed.
     *
     * @return the administered rounding mode, never null
     */
    public java.math.RoundingMode calcRoundingMode() {
        LOGGER.info("Entering method calcRoundingMode");
        String raw = value("calc.rounding-mode");
        String name = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        java.math.RoundingMode mode = switch (name) {
            case "HALF_EVEN" -> java.math.RoundingMode.HALF_EVEN;
            case "HALF_DOWN" -> java.math.RoundingMode.HALF_DOWN;
            case "UP" -> java.math.RoundingMode.UP;
            case "DOWN" -> java.math.RoundingMode.DOWN;
            default -> java.math.RoundingMode.HALF_UP;
        };
        LOGGER.info("Exiting method calcRoundingMode");
        return mode;
    }

    /**
     * Decimals of the TOTAL EXCLUDING TAX of an article line on a commercial
     * document (BO-10-02-19).
     *
     * @return 2 or 3
     */
    public int invoiceLineTotalExcludingTaxDecimals() {
        LOGGER.info("Entering method invoiceLineTotalExcludingTaxDecimals");
        int decimals = documentDecimals("invoice.decimals-line-total-ht");
        LOGGER.info("Exiting method invoiceLineTotalExcludingTaxDecimals");
        return decimals;
    }

    /**
     * The number of digits of the register PIN (BO-10-02-23), clamped to the
     * range the keypads and the generator accept.
     *
     * @return between 4 and 8
     */
    public int pinLength() {
        LOGGER.info("Entering method pinLength");
        int length = Math.min(Math.max(intValue("auth.pin-length"), 4), 8);
        LOGGER.info("Exiting method pinLength");
        return length;
    }

    /**
     * The supervisor validation mode, as administered (BO-10-02-31). Read
     * tolerantly by the endorsement panel, which falls back on code and
     * password for anything it does not know.
     *
     * @return the raw administered mode
     */
    public String supervisorValidationMode() {
        LOGGER.info("Entering method supervisorValidationMode");
        LOGGER.info("Exiting method supervisorValidationMode");
        return value("supervisor.validation-mode");
    }

    /**
     * Whether a repeated article is grouped into the quantity of its line on
     * the sale screen (BO-10-08-32).
     *
     * @return true when the same articles are grouped
     */
    public boolean saleGroupLines() {
        LOGGER.info("Entering method saleGroupLines");
        LOGGER.info("Exiting method saleGroupLines");
        return boolValue("sale.group-lines");
    }

    /**
     * The default message of an article refused for a forbidden sale or a
     * recall, when its fiche carries none (BO-10-07-20).
     *
     * @return the administered message, blank when none
     */
    public String forbiddenSaleMessage() {
        LOGGER.info("Entering method forbiddenSaleMessage");
        LOGGER.info("Exiting method forbiddenSaleMessage");
        return value("sale.forbidden-message");
    }

    /**
     * The retrieval mode of the counter-scale baskets (BO-10-05-01),
     * normalised to one of its four values.
     *
     * @return API_FTP, API, FTP or OFF
     */
    public String balanceTicketMode() {
        LOGGER.info("Entering method balanceTicketMode");
        String raw = value("balance.ticket-mode");
        String mode = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        LOGGER.info("Exiting method balanceTicketMode");
        return List.of("API", "FTP", "OFF").contains(mode) ? mode : "API_FTP";
    }

    /**
     * Whether the register reads the counter-scale tickets at all
     * (BO-10-05-01).
     *
     * @return false only in mode OFF
     */
    public boolean balanceTicketEnabled() {
        LOGGER.info("Entering method balanceTicketEnabled");
        LOGGER.info("Exiting method balanceTicketEnabled");
        return !"OFF".equals(balanceTicketMode());
    }

    /**
     * Whether the store node accepts the baskets the scales push on its API
     * (BO-10-05-01).
     *
     * @return true in modes API_FTP and API
     */
    public boolean balanceTicketApiAccepted() {
        LOGGER.info("Entering method balanceTicketApiAccepted");
        String mode = balanceTicketMode();
        LOGGER.info("Exiting method balanceTicketApiAccepted");
        return "API_FTP".equals(mode) || "API".equals(mode);
    }

    /**
     * Whether the store node polls the administered FTP server for the
     * baskets (BO-10-05-01).
     *
     * @return true in modes API_FTP and FTP
     */
    public boolean balanceTicketFtpPolled() {
        LOGGER.info("Entering method balanceTicketFtpPolled");
        String mode = balanceTicketMode();
        LOGGER.info("Exiting method balanceTicketFtpPolled");
        return "API_FTP".equals(mode) || "FTP".equals(mode);
    }

    /**
     * Whether a referential update reaching the register during a sale is
     * deferred until the register is free (BO-10-08-25).
     *
     * @return true when the sale in progress is protected
     */
    public boolean referentialDeferDuringSale() {
        LOGGER.info("Entering method referentialDeferDuringSale");
        LOGGER.info("Exiting method referentialDeferDuringSale");
        return boolValue("referential.defer-during-sale");
    }

    /**
     * Whether the register reads the GS1 codes (BO-10-09-01).
     *
     * @return true when the GS1 reading is active
     */
    public boolean gs1Enabled() {
        LOGGER.info("Entering method gs1Enabled");
        LOGGER.info("Exiting method gs1Enabled");
        return boolValue("gs1.enabled");
    }

    /**
     * The alerts administered per GS1 application identifier (BO-10-09-04),
     * raw: {@code AI=LEVEL:message} entries separated by semicolons.
     *
     * @return the administered table, blank when none
     */
    public String gs1AiAlerts() {
        LOGGER.info("Entering method gs1AiAlerts");
        LOGGER.info("Exiting method gs1AiAlerts");
        return value("gs1.ai-alerts");
    }

    // ---- lot 18: G getters ----

    /**
     * The catalog keys holding a SECRET (BO-10-03-08): the back office never
     * displays their value back — the field is offered empty and a field left
     * empty keeps the value stored — and the echelon editor masks the typing.
     */
    public static final Set<String> SECRET_KEYS = Set.of("fidelity.password", "fiscal.at-password");

    /**
     * Tells whether a catalog key holds a secret the back office must mask
     * (BO-10-03-08).
     *
     * @param key the storage key, possibly null
     * @return true when the key is a secret
     */
    public static boolean isSecret(String key) {
        return key != null && SECRET_KEYS.contains(key);
    }

    /**
     * Whether the register asks the loyalty programme for the balance of each
     * of its pots, computed with the current transaction (BO-10-02-33).
     *
     * @return true when the pot balances are requested
     */
    public boolean fidelityPotBalancesEnabled() {
        LOGGER.info("Entering method fidelityPotBalancesEnabled");
        LOGGER.info("Exiting method fidelityPotBalancesEnabled");
        return boolValue("fidelity.pot-balances-enabled");
    }

    /**
     * The tender key a loyalty-purse settlement is registered under
     * (BO-10-03-06). Whether the register knows the key is the caller's to
     * check, as for the customer-credit tender.
     *
     * @return the administered key, FIDELITY when left blank
     */
    public String fidelityPaymentTender() {
        LOGGER.info("Entering method fidelityPaymentTender");
        String key = value("fidelity.payment-tender");
        LOGGER.info("Exiting method fidelityPaymentTender");
        return key.isBlank() ? "FIDELITY" : key.trim();
    }

    /**
     * The Basic-auth password of the register's machine account on the loyalty
     * service (BO-10-03-08); empty when the deployment property applies.
     *
     * @return the loyalty machine-account password, possibly empty
     */
    public String fidelityPassword() {
        LOGGER.info("Entering method fidelityPassword");
        LOGGER.info("Exiting method fidelityPassword");
        return value("fidelity.password");
    }

    /**
     * The connection timeout towards the loyalty service, in milliseconds
     * (BO-10-03-10). Zero or less answers the historical 1500 ms: a timeout of
     * nothing would make every call fail at once.
     *
     * @return the timeout in milliseconds, always strictly positive
     */
    public int fidelityConnectTimeoutMs() {
        LOGGER.info("Entering method fidelityConnectTimeoutMs");
        int ms = intValue("fidelity.connect-timeout-ms");
        LOGGER.info("Exiting method fidelityConnectTimeoutMs");
        return ms > 0 ? ms : 1500;
    }

    /**
     * The response timeout of one loyalty-service call, in milliseconds
     * (BO-10-03-10). Zero or less answers the historical 2500 ms.
     *
     * @return the timeout in milliseconds, always strictly positive
     */
    public int fidelityReadTimeoutMs() {
        LOGGER.info("Entering method fidelityReadTimeoutMs");
        int ms = intValue("fidelity.read-timeout-ms");
        LOGGER.info("Exiting method fidelityReadTimeoutMs");
        return ms > 0 ? ms : 2500;
    }

    /**
     * Whether the basket sent to the promotion engine carries the date of the
     * order the ticket takes over (BO-10-03-17).
     *
     * @return true when the order date is sent
     */
    public boolean fidelityOrderDateEnabled() {
        LOGGER.info("Entering method fidelityOrderDateEnabled");
        LOGGER.info("Exiting method fidelityOrderDateEnabled");
        return boolValue("fidelity.order-date-enabled");
    }

    // ---- lot 18: H getters ----

    /**
     * The numeric SAF-T identifier of a Portuguese document type
     * (BO-10-06-01, BO-10-06-02): the simplified invoice and the credit note
     * carry the administered one, any other type none.
     *
     * @param type the document type code (FS, FT, NC, RC), possibly null
     * @return the identifier, or null when the type carries none
     */
    public Integer saftDocumentId(String type) {
        LOGGER.info("Entering method saftDocumentId with type: " + type);
        if ("FS".equals(type)) {
            LOGGER.info("Exiting method saftDocumentId");
            return intValue("fiscal.saft-id-fs");
        }
        if ("NC".equals(type)) {
            LOGGER.info("Exiting method saftDocumentId");
            return intValue("fiscal.saft-id-nc");
        }
        LOGGER.info("Exiting method saftDocumentId");
        return null;
    }

    /**
     * The eco-taxes the article attributes carry (BO-10-06-05, BO-10-06-06),
     * the built-in {@code ECO_TAX} attribute always among them.
     *
     * @return the eco-tax definitions, in administered order
     */
    public java.util.List<com.intermarche.edge.domain.catalog.attribute.EcoTaxDefinition> ecoTaxes() {
        LOGGER.info("Entering method ecoTaxes");
        LOGGER.info("Exiting method ecoTaxes");
        return com.intermarche.edge.domain.catalog.attribute.EcoTaxDefinition.parse(
                value("ecotax.attributes"));
    }

    /**
     * The word printed on a cheque after the units written in letters
     * (BO-10-07-05), or null when the language's own wording stands.
     *
     * @return the administered word, trimmed, or null when blank
     */
    public String chequeUnitText() {
        LOGGER.info("Entering method chequeUnitText");
        LOGGER.info("Exiting method chequeUnitText");
        return trimmedOrNull(value("cheque.unit-text"));
    }

    /**
     * The word printed on a cheque after the cents written in letters
     * (BO-10-07-05), or null when the language's own wording stands.
     *
     * @return the administered word, trimmed, or null when blank
     */
    public String chequeCentText() {
        LOGGER.info("Entering method chequeCentText");
        LOGGER.info("Exiting method chequeCentText");
        return trimmedOrNull(value("cheque.cent-text"));
    }

    /**
     * Whether the payment terminal's receipts carry the administered EFT and
     * contactless logos (BO-10-07-13).
     *
     * @return true when the logos are printed
     */
    public boolean eftLogosPrinted() {
        LOGGER.info("Entering method eftLogosPrinted");
        LOGGER.info("Exiting method eftLogosPrinted");
        return boolValue("ticket.eft-logos");
    }

    /**
     * Whether a subtotal closes each family when the receipt prints by
     * nomenclature (BO-10-07-15).
     *
     * @return true when family subtotals are printed
     */
    public boolean ticketFamilySubtotals() {
        LOGGER.info("Entering method ticketFamilySubtotals");
        LOGGER.info("Exiting method ticketFamilySubtotals");
        return boolValue("ticket.family-subtotals");
    }

    /**
     * Whether the amounts of the sale receipt are emitted in bold
     * (BO-10-07-15).
     *
     * @return true when the amounts are bold
     */
    public boolean ticketBoldAmounts() {
        LOGGER.info("Entering method ticketBoldAmounts");
        LOGGER.info("Exiting method ticketBoldAmounts");
        return boolValue("ticket.bold-amounts");
    }

    /**
     * The main language of the customer display, independent of the
     * cashier's (BO-10-07-17).
     *
     * @return the administered language tag, possibly blank
     */
    public String customerDisplayLanguage() {
        LOGGER.info("Entering method customerDisplayLanguage");
        LOGGER.info("Exiting method customerDisplayLanguage");
        return value("language.customer-display");
    }

    /**
     * The open-register message in the customer display's second language
     * (BO-10-07-17), or null when none is administered.
     *
     * @return the translated message, trimmed, or null when blank
     */
    public String customerOpenMessageSecond() {
        LOGGER.info("Entering method customerOpenMessageSecond");
        LOGGER.info("Exiting method customerOpenMessageSecond");
        return trimmedOrNull(value("customer.message-open-second"));
    }

    /**
     * The closed-register message in the customer display's second language
     * (BO-10-07-17), or null when none is administered.
     *
     * @return the translated message, trimmed, or null when blank
     */
    public String customerClosedMessageSecond() {
        LOGGER.info("Entering method customerClosedMessageSecond");
        LOGGER.info("Exiting method customerClosedMessageSecond");
        return trimmedOrNull(value("customer.message-closed-second"));
    }

    /**
     * Whether the register screen takes its disconnected appearance when the
     * store node stops answering (BO-10-07-19).
     *
     * @return true when the offline appearance is active
     */
    public boolean offlineDisplayEnabled() {
        LOGGER.info("Entering method offlineDisplayEnabled");
        LOGGER.info("Exiting method offlineDisplayEnabled");
        return boolValue("display.offline-enabled");
    }

    /**
     * How long the store node must stay silent before the disconnected
     * appearance shows (BO-10-07-19).
     *
     * @return the delay in seconds, never negative
     */
    public int offlineDisplayDelaySeconds() {
        LOGGER.info("Entering method offlineDisplayDelaySeconds");
        int seconds = intValue("display.offline-delay-seconds");
        LOGGER.info("Exiting method offlineDisplayDelaySeconds");
        return Math.max(0, seconds);
    }

    /**
     * The decimals of a non-whole quantity sold by a unit of measure
     * (BO-10-08-28).
     *
     * @return the decimals, from 0 to 4
     */
    public int measureDecimals() {
        LOGGER.info("Entering method measureDecimals");
        LOGGER.info("Exiting method measureDecimals");
        return boundedDecimals("unit.measure-decimals");
    }

    /**
     * The decimals of a weight (BO-10-08-28).
     *
     * @return the decimals, from 0 to 4
     */
    public int weightDecimals() {
        LOGGER.info("Entering method weightDecimals");
        LOGGER.info("Exiting method weightDecimals");
        return boundedDecimals("unit.weight-decimals");
    }

    /**
     * Reads a decimals parameter, a value outside 0 to 4 answering the
     * catalog default.
     *
     * @param key the catalog key
     * @return the decimals, from 0 to 4
     */
    private int boundedDecimals(String key) {
        int decimals = intValue(key);
        return decimals < 0 || decimals > 4 ? Integer.parseInt(def(key).defaultValue()) : decimals;
    }

    /**
     * The symbol written beside a weight on the screens and the receipts
     * (BO-10-08-29).
     *
     * @return the administered symbol, {@code kg} when blank
     */
    public String weightSymbol() {
        LOGGER.info("Entering method weightSymbol");
        String symbol = trimmedOrNull(value("unit.weight-symbol"));
        LOGGER.info("Exiting method weightSymbol");
        return symbol == null ? "kg" : symbol;
    }

    /**
     * How the screens and the receipts write a quantity (BO-10-08-28,
     * BO-10-08-29): the administered decimals and weight symbol together.
     *
     * @return the writing of quantities, never null
     */
    public com.intermarche.edge.domain.catalog.UnitFormat unitFormat() {
        LOGGER.info("Entering method unitFormat");
        LOGGER.info("Exiting method unitFormat");
        return new com.intermarche.edge.domain.catalog.UnitFormat(measureDecimals(), weightDecimals(),
                weightSymbol());
    }

    /**
     * Whether cancelled lines print on the receipt and the parking slip,
     * marked as cancelled (BO-10-08-30).
     *
     * @return true when cancelled lines are printed
     */
    public boolean cancelledLinesPrinted() {
        LOGGER.info("Entering method cancelledLinesPrinted");
        LOGGER.info("Exiting method cancelledLinesPrinted");
        return boolValue("ticket.cancelled-lines-print");
    }

    /**
     * Whether identical articles print one per line instead of grouped on one
     * line carrying the quantity (BO-10-08-31).
     *
     * @return true when identical articles are detailed unit by unit
     */
    public boolean identicalLinesDetailed() {
        LOGGER.info("Entering method identicalLinesDetailed");
        LOGGER.info("Exiting method identicalLinesDetailed");
        return boolValue("ticket.identical-lines-detailed");
    }

    // ---- lot 18: I getters ----

    /**
     * Seconds between two node reports (BO-08-04-15/16/20, BO-08-03-01).
     *
     * <p>Zero or less answers sixty: a node that reports continuously would
     * load its upstream for nothing.
     *
     * @return the cadence in seconds, always strictly positive
     */
    public int supervisionReportSeconds() {
        int seconds = intValue("supervision.report-seconds");
        return seconds > 0 ? seconds : 60;
    }

    /**
     * Whether every register function and back-office gesture is counted
     * (BO-08-02-02).
     *
     * @return true when the counting is active
     */
    public boolean functionUsageEnabled() {
        return boolValue("supervision.function-usage-enabled");
    }

    /**
     * Whether this node carries out the remote maintenance orders
     * (BO-08-04-17).
     *
     * @return true when remote maintenance is allowed
     */
    public boolean remoteMaintenanceEnabled() {
        return boolValue("supervision.remote-maintenance-enabled");
    }

    /**
     * Whether a failed deposited integration is replayed automatically
     * (BO-08-04-03 to -08).
     *
     * @return true when the automatic replay is active
     */
    public boolean integrationRetryEnabled() {
        return boolValue("integration.retry-enabled");
    }

    /**
     * Minutes between two automatic replays of one failed deposit
     * (BO-08-04-03 to -08).
     *
     * @return the delay in minutes, always strictly positive
     */
    public int integrationRetryMinutes() {
        int minutes = intValue("integration.retry-minutes");
        return minutes > 0 ? minutes : 10;
    }

    /**
     * How many automatic replays a failed deposit gets (BO-08-04-03 to -13).
     *
     * @return the attempts, always strictly positive
     */
    public int integrationRetryMaxAttempts() {
        int attempts = intValue("integration.retry-max-attempts");
        return attempts > 0 ? attempts : 5;
    }

    /**
     * Whether the register alerts are shown on the mobile page
     * (BO-10-08-02).
     *
     * @return true when the mobile alert page is active
     */
    public boolean mobileAlertsEnabled() {
        return boolValue("dashboard.mobile-alerts-enabled");
    }

    /**
     * Whether a node lower than its lot's version updates when it starts,
     * outside the lot's window (BO-08-03-07).
     *
     * @return true when the start-up update is active
     */
    public boolean deploymentAutoUpdateAtStartup() {
        return boolValue("deployment.auto-update-at-startup");
    }

    /**
     * How many automatic attempts a failed update gets (BO-08-04-01).
     *
     * @return the attempts, always strictly positive
     */
    public int deploymentMaxAttempts() {
        int attempts = intValue("deployment.max-attempts");
        return attempts > 0 ? attempts : 3;
    }

    /**
     * Whether a version failing its self-test is rolled back to the previous
     * one (BO-08-04-14).
     *
     * @return true when the automatic rollback is active
     */
    public boolean deploymentRollbackEnabled() {
        return boolValue("deployment.rollback-enabled");
    }

    // ---- lot 19: K getters ----
    /** The VAT table models a ticket may carry (BO-03-09-03). */
    public static final List<String> VAT_TABLE_MODES =
            List.of("NONE", "COMBINED", "SEPARATE", "AMOUNT_ONLY");

    /** The scanner drivers a register may be given (BO-03-09-16/17). */
    public static final List<String> SCANNER_DRIVERS = List.of("AUTO", "EVDEV", "HIDRAW", "OPOS");

    /** The receipt printer models the driver knows (BO-03-09-21). */
    public static final List<String> PRINTER_TYPES =
            List.of("ESCPOS", "EPSON_TM", "WINCOR_TH", "IBM_4610", "TOSHIBA_TCX");

    /** The payment terminal families a register may be given (BO-03-09-19). */
    public static final List<String> PAYMENT_TERMINAL_TYPES =
            List.of("VERIFONE", "INGENICO_SIBS", "WORLDLINE");

    /**
     * The code of the ticket-header template this register prints
     * (BO-03-09-02).
     *
     * @return the trimmed code, empty when none is chosen
     */
    public String ticketHeaderTemplate() {
        return value("ticket.header-template").trim();
    }

    /**
     * The code of the ticket-footer template this register prints
     * (BO-03-09-02).
     *
     * @return the trimmed code, empty when none is chosen
     */
    public String ticketFooterTemplate() {
        return value("ticket.footer-template").trim();
    }

    /**
     * The VAT table model this register prints on its tickets (BO-03-09-03).
     *
     * <p>An empty or unreadable choice is not a fifth model: it is the shop
     * that never chose, and the historical switch of the VAT breakdown then
     * decides between the combined table and the amount alone, exactly as the
     * ticket printed before.
     *
     * @return one of {@link #VAT_TABLE_MODES}, never null
     */
    public String vatTableMode() {
        String raw = value("ticket.vat-table").trim().toUpperCase(java.util.Locale.ROOT);
        if (VAT_TABLE_MODES.contains(raw)) {
            return raw;
        }
        return vatBreakdownEnabled() ? "COMBINED" : "AMOUNT_ONLY";
    }

    /**
     * The code of the receipt logo this register prints (BO-03-09-04).
     *
     * @return the trimmed code, empty when none is chosen
     */
    public String logoCode() {
        return value("print.logo-code").trim();
    }

    /**
     * The code of the cash menu this register draws (BO-03-09-06,
     * BO-03-10-09, BO-03-10-16, BO-03-10-18).
     *
     * @return the trimmed code, empty when none is chosen
     */
    public String menuCode() {
        return value("menu.code").trim();
    }

    /**
     * Whether this register takes part in the single queue (BO-03-09-10).
     *
     * @return true when the single-queue key is shown and published
     */
    public boolean singleQueueEnabled() {
        return boolValue("queue.single-enabled");
    }

    /**
     * Where this register publishes its single-queue availability
     * (BO-03-09-10).
     *
     * @return the trimmed URL, empty when none is administered
     */
    public String singleQueueUrl() {
        return value("queue.single-url").trim();
    }

    /**
     * The address of the drawer driver handed to the hardware module
     * (BO-03-09-11).
     *
     * @return the trimmed address, empty for the module's own
     */
    public String drawerAddress() {
        return value("hardware.drawer.address").trim();
    }

    /**
     * The scanner driver of this register (BO-03-09-16/17).
     *
     * @return one of {@link #SCANNER_DRIVERS}, AUTO when unreadable
     */
    public String scannerDriver() {
        String raw = value("hardware.scanner.driver").trim().toUpperCase(java.util.Locale.ROOT);
        return SCANNER_DRIVERS.contains(raw) ? raw : "AUTO";
    }

    /**
     * The device-name fragments recognised as a scanner (BO-03-09-16/17).
     *
     * @return the trimmed list, empty for the deployment's own
     */
    public String scannerNamePatterns() {
        return value("hardware.scanner.name-patterns").trim();
    }

    /**
     * The OPOS logical device of the Datalogic scanner (BO-03-09-17).
     *
     * @return the trimmed name, empty for the driver's default
     */
    public String scannerOposDevice() {
        return value("hardware.scanner.opos-device").trim();
    }

    /**
     * Whether a second, customer-side scanner is active (BO-03-09-18).
     *
     * @return true when the second scanner feeds the fidelity and coupons
     */
    public boolean secondScannerEnabled() {
        return boolValue("hardware.scanner2.enabled");
    }

    /**
     * The device-name fragments forming the second scanner (BO-03-09-18).
     *
     * @return the trimmed list, empty when none is designated
     */
    public String secondScannerNamePatterns() {
        return value("hardware.scanner2.name-patterns").trim();
    }

    /**
     * The receipt printer model handed to the driver (BO-03-09-21).
     *
     * @return one of {@link #PRINTER_TYPES}, ESCPOS when unreadable
     */
    public String printerType() {
        String raw = value("hardware.printer.type").trim().toUpperCase(java.util.Locale.ROOT);
        return PRINTER_TYPES.contains(raw) ? raw : "ESCPOS";
    }

    /**
     * The receipt printer's line speed in bauds (BO-03-09-21).
     *
     * @return the speed, zero for the driver's own
     */
    public int printerSpeed() {
        return Math.max(0, intValue("hardware.printer.speed"));
    }

    /**
     * The receipt printer's character code page (BO-03-09-21).
     *
     * @return the code page, 858 when absurd
     */
    public int printerCodePage() {
        int page = intValue("hardware.printer.code-page");
        return page > 0 ? page : 858;
    }

    /**
     * The receipt printer's paper width in millimetres (BO-03-09-21).
     *
     * @return 58 or 80
     */
    public int printerPaperWidth() {
        return intValue("hardware.printer.paper-width") == 58 ? 58 : 80;
    }

    /**
     * Whether the printer turns the cheque over to print its front
     * (BO-03-09-21).
     *
     * @return true when the cheque is flipped
     */
    public boolean chequeFlip() {
        return boolValue("hardware.printer.cheque-flip");
    }

    /**
     * Whether a second printer is offered to the operator (BO-03-09-22).
     *
     * @return true when the second printer is active
     */
    public boolean secondPrinterEnabled() {
        return boolValue("printer2.enabled");
    }

    /**
     * The format of the second printer (BO-03-09-22).
     *
     * @return A4 or TICKET, A4 when unreadable
     */
    public String secondPrinterFormat() {
        return "TICKET".equalsIgnoreCase(value("printer2.format").trim()) ? "TICKET" : "A4";
    }

    /**
     * How the second printer is reached (BO-03-09-22).
     *
     * @return NETWORK or LOCAL, NETWORK when unreadable
     */
    public String secondPrinterConnection() {
        return "LOCAL".equalsIgnoreCase(value("printer2.connection").trim()) ? "LOCAL" : "NETWORK";
    }

    /**
     * The address of the second printer: an URL or a local queue name
     * (BO-03-09-22).
     *
     * @return the trimmed address, empty when none is administered
     */
    public String secondPrinterAddress() {
        return value("printer2.address").trim();
    }

    /**
     * The payment terminal family of this register (BO-03-09-19).
     *
     * @return one of {@link #PAYMENT_TERMINAL_TYPES}, or an empty string for
     *         the deployment's own
     */
    public String paymentTerminalType() {
        String raw = value("payment.terminal-type").trim().toUpperCase(java.util.Locale.ROOT);
        return PAYMENT_TERMINAL_TYPES.contains(raw) ? raw : "";
    }

    /**
     * The host:port of this register's monetique client (BO-03-09-19).
     *
     * @return the trimmed address, empty for the deployment's own
     */
    public String paymentTerminalAddress() {
        return value("payment.terminal-address").trim();
    }

    /**
     * The island label sent under the D7V tag of the Verifone requests
     * (BO-03-09-29).
     *
     * @return the trimmed label, empty when none is administered
     */
    public String islandPaymentLabel() {
        return value("terminal.island-label").trim();
    }

    /**
     * Whether this node's own sales stream to Catalina (BO-03-09-20).
     *
     * @return true when the Catalina flow is active
     */
    public boolean catalinaEnabled() {
        return boolValue("catalina.enabled");
    }

    /**
     * Whether the sales of ONE register stream to Catalina, resolved down to
     * that register's island and checkout point (BO-03-09-20) — what the store
     * node asks of every ticket it consolidates.
     *
     * @param terminalId the register that rang the sale, or null
     * @return true when the Catalina flow is active for that register
     */
    public boolean catalinaEnabledFor(String terminalId) {
        String resolved = administeredValues(terminalId).get("catalina.enabled");
        return Boolean.parseBoolean(resolved == null ? def("catalina.enabled").defaultValue() : resolved);
    }

    /**
     * Whether the Planexa flow is produced (BO-03-10-06).
     *
     * @return true when the store emits the Planexa file
     */
    public boolean planexaExportEnabled() {
        return boolValue("export.planexa-enabled");
    }

    // ---- lot 19: L getters ----

    /**
     * The cheque-guarantee provider asked for a cheque authorization
     * (BO-03-02-24), or the empty string when the shop administers none.
     *
     * @return the provider name, trimmed, never null
     */
    public String chequeGuaranteeProvider() {
        LOGGER.info("Entering method chequeGuaranteeProvider");
        LOGGER.info("Exiting method chequeGuaranteeProvider");
        return value("cheque.guarantee-provider").trim();
    }

    /**
     * The base address of the cheque-guarantee service (BO-03-02-24), or the
     * empty string when the register's configured address applies.
     *
     * @return the address, trimmed, never null
     */
    public String chequeGuaranteeUrl() {
        LOGGER.info("Entering method chequeGuaranteeUrl");
        LOGGER.info("Exiting method chequeGuaranteeUrl");
        return value("cheque.guarantee-url").trim();
    }

    /**
     * How long the register waits for the cheque-guarantee answer
     * (BO-03-02-24), at least 500 milliseconds.
     *
     * @return the timeout in milliseconds
     */
    public int chequeGuaranteeTimeoutMillis() {
        LOGGER.info("Entering method chequeGuaranteeTimeoutMillis");
        LOGGER.info("Exiting method chequeGuaranteeTimeoutMillis");
        return Math.max(500, intValue("cheque.guarantee-timeout-ms"));
    }

    /**
     * The EAN codes of the DEPOSIT articles (BO-03-13-11): a sale of one is a
     * consigne charged, a return of one a déconsigne.
     *
     * @return the codes, trimmed, in order, possibly empty
     */
    public List<String> depositArticleEans() {
        LOGGER.info("Entering method depositArticleEans");
        List<String> eans = new ArrayList<>();
        for (String part : value("deposit.article-eans").split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                eans.add(trimmed);
            }
        }
        LOGGER.info("Exiting method depositArticleEans");
        return eans;
    }

    /**
     * Whether the card receipt is printed at all (BO-03-02-34): attached or
     * separate as {@link #cardReceiptAttached()} says, or not at all.
     *
     * @return true when the card receipt is printed
     */
    public boolean cardReceiptPrinted() {
        LOGGER.info("Entering method cardReceiptPrinted");
        LOGGER.info("Exiting method cardReceiptPrinted");
        return boolValue("payment.card-receipt-printed");
    }

    /**
     * Whether releasing the forced monetics degraded mode needs a supervisor
     * (BO-03-02-50).
     *
     * @return true when a supervisor endorses the release
     */
    public boolean moneticsDegradedReleaseEndorsement() {
        LOGGER.info("Entering method moneticsDegradedReleaseEndorsement");
        LOGGER.info("Exiting method moneticsDegradedReleaseEndorsement");
        return boolValue("payment.degraded-release-endorsement");
    }

    // ---- lot 19: M getters ----

    /**
     * The nomenclature LEVEL the articles are grouped under (BO-03-03-17).
     *
     * @return the level, one-based, zero for the article's own family
     */
    public int ticketFamilyLevel() {
        return Math.max(0, intValue("ticket.family-level"));
    }

    /**
     * Where the receipt prints the engine's promotion lines (BO-03-03-23).
     *
     * @return {@code LINE}, {@code FOOTER} or {@code ENGINE}
     */
    public String ticketPromotionPlacement() {
        String placement = value("ticket.promotion-placement").trim().toUpperCase(java.util.Locale.ROOT);
        return "LINE".equals(placement) || "FOOTER".equals(placement) ? placement : "ENGINE";
    }

    /**
     * The article attributes printed under each line of an invoice
     * (BO-03-03-21).
     *
     * @return the attribute codes, in administered order, empty when none
     */
    public List<String> invoiceArticleAttributes() {
        List<String> codes = new ArrayList<>();
        for (String part : value("invoice.article-attributes").split(";")) {
            String code = part.trim();
            if (!code.isEmpty() && !codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /**
     * How many article lines one A4 page of a document carries (BO-03-03-02).
     *
     * @return the lines per page, zero for a single page
     */
    public int invoiceA4LinesPerPage() {
        return Math.max(0, intValue("invoice.a4-lines-per-page"));
    }

    /**
     * Whether a document may only be issued for a customer carrying an account
     * number (BO-03-04-16).
     *
     * @return true when the account number is mandatory
     */
    public boolean invoiceAccountNumberRequired() {
        return boolValue("invoice.account-number-required");
    }

    /**
     * Whether the customer's data are shown for validation before the document
     * is printed (BO-03-04-18).
     *
     * @return true when the validation step is offered
     */
    public boolean invoiceCustomerValidation() {
        return boolValue("invoice.customer-validation");
    }

    /**
     * Whether the loyalty programme's two-line advantage message is printed
     * (BO-03-03-26).
     *
     * @return true when the short message is printed
     */
    public boolean fidelityShortMessagePrinted() {
        return boolValue("fidelity.short-message-printed");
    }

    /**
     * How many of the loyalty programme's commercial messages are printed
     * (BO-03-03-27).
     *
     * @return the count, between zero and five
     */
    public int fidelityCommercialMessagesMax() {
        return Math.min(5, Math.max(0, intValue("fidelity.commercial-messages-max")));
    }

    // ---- lot 19: N getters ----

    // ---- lot 20: P getters ----

    /**
     * Whether the register suggests the complementary articles of the article
     * just sold (BO-02-03-16).
     *
     * @return true when the suggestions are shown
     */
    public boolean articleComplementarySuggestions() {
        return boolValue("article.complementary-suggestions");
    }

    /**
     * How many complementary articles the register suggests at most
     * (BO-02-03-16).
     *
     * @return the count, between one and ten
     */
    public int articleComplementaryMax() {
        return Math.min(10, Math.max(1, intValue("article.complementary-max")));
    }

    /**
     * Whether a meal voucher settles only the articles flagged eligible when the
     * valuation engine states no advantage (BO-02-03-06).
     *
     * @return true when a ticket without an eligible article refuses the voucher
     */
    public boolean mealVoucherEligibleOnly() {
        return boolValue("article.meal-voucher-eligible-only");
    }

    /**
     * Whether the register publishes the deposit control of a self-service lane
     * (BO-02-03-24, BO-02-03-25).
     *
     * @return true when the lines are controlled on the security scale
     */
    public boolean depositControlEnabled() {
        return boolValue("article.deposit-control");
    }

    /**
     * Whether the electronic shelf label flow is active (BO-02-03-37,
     * BO-02-03-38).
     *
     * @return true when the EEG providers are called
     */
    public boolean eegEnabled() {
        return boolValue("eeg.enabled");
    }

    // ---- lot 20: Q getters ----

    /**
     * The address the store node sends the modified customer files to, for the
     * commercial management to acknowledge (BO-02-04-13).
     *
     * @return the administered address, trimmed, empty when none
     */
    public String customerUplinkUrl() {
        LOGGER.info("Entering method customerUplinkUrl");
        LOGGER.info("Exiting method customerUplinkUrl");
        return value("channel.commercial.customer-uplink-url").trim();
    }

    /**
     * The delay between two attempts at sending the customer files not yet
     * acknowledged, in seconds, between 5 and 3600 (BO-02-04-13).
     *
     * @return the administered delay, bounded
     */
    public int customerUplinkSeconds() {
        LOGGER.info("Entering method customerUplinkSeconds");
        LOGGER.info("Exiting method customerUplinkSeconds");
        return Math.min(3600, Math.max(5, intValue("channel.commercial.customer-uplink-seconds")));
    }

    // ---- lot 20: R getters ----

    // ---- lot 21: S getters ----

    /**
     * The acceptance-server codes that designate the secondary monetics
     * server of the degraded mode (BO-04-01-48).
     *
     * @return the codes, trimmed, possibly empty
     */
    public List<String> secondaryMoneticServers() {
        LOGGER.info("Entering method secondaryMoneticServers");
        List<String> codes = new java.util.ArrayList<>();
        for (String part : value("journal.secondary-monetic-servers").split(";")) {
            if (!part.isBlank()) {
                codes.add(part.trim());
            }
        }
        LOGGER.info("Exiting method secondaryMoneticServers");
        return codes;
    }

    /**
     * The profiles whose holders may endorse a gesture whatever their role
     * (BO-04-02-01).
     *
     * @return the semicolon-separated profile names, empty by default
     */
    public String endorsementProfiles() {
        LOGGER.info("Entering method endorsementProfiles");
        LOGGER.info("Exiting method endorsementProfiles");
        return value("auth.endorsement-profiles");
    }

    /**
     * The administered register alerts, one rule per line (BO-04-03-08/09).
     *
     * @return the rules, empty by default
     */
    public String controlAlertRules() {
        LOGGER.info("Entering method controlAlertRules");
        LOGGER.info("Exiting method controlAlertRules");
        return value("control.alert-rules");
    }

    /**
     * The register functions a justification is asked for, one
     * {@code METHOD path=FUNCTION} rule per line (BO-04-03-10/12).
     *
     * @return the rules
     */
    public String justificationRoutes() {
        LOGGER.info("Entering method justificationRoutes");
        LOGGER.info("Exiting method justificationRoutes");
        return value("justification.function-routes");
    }

    /**
     * Whether the control indicators head every back-office screen
     * (BO-04-03-01).
     *
     * @return true by default
     */
    public boolean controlIndicatorsBar() {
        LOGGER.info("Entering method controlIndicatorsBar");
        LOGGER.info("Exiting method controlIndicatorsBar");
        return boolValue("control.indicators-bar");
    }

    /**
     * The nomenclature level rank the day's revenue is detailed at
     * (BO-04-03-01).
     *
     * @return the rank, 0 or less for the article's own family
     */
    public int controlRevenueLevel() {
        LOGGER.info("Entering method controlRevenueLevel");
        LOGGER.info("Exiting method controlRevenueLevel");
        return intValue("control.revenue-level");
    }

    /**
     * How many headings the revenue detail shows, between 1 and 20
     * (BO-04-03-01).
     *
     * @return the bounded count
     */
    public int controlRevenueTop() {
        LOGGER.info("Entering method controlRevenueTop");
        LOGGER.info("Exiting method controlRevenueTop");
        return Math.min(20, Math.max(1, intValue("control.revenue-top")));
    }

    /**
     * The back-office screens whose opening is journalled (BO-04-03-04).
     *
     * @return the semicolon-separated screen codes or path prefixes, empty by default
     */
    public String watchedMenus() {
        LOGGER.info("Entering method watchedMenus");
        LOGGER.info("Exiting method watchedMenus");
        return value("audit.watched-menus");
    }

    /**
     * Whether the register requests are announced on every back-office
     * screen (BO-04-02-12).
     *
     * @return true by default
     */
    public boolean globalAlertsEnabled() {
        LOGGER.info("Entering method globalAlertsEnabled");
        LOGGER.info("Exiting method globalAlertsEnabled");
        return boolValue("supervision.global-alerts");
    }

    /**
     * The event types the real-time register watch shows (BO-04-03-07).
     *
     * @return the type names, trimmed, empty for every type
     */
    public List<String> liveEventTypes() {
        LOGGER.info("Entering method liveEventTypes");
        List<String> types = new java.util.ArrayList<>();
        for (String part : value("supervision.live-event-types").split(";")) {
            if (!part.isBlank()) {
                types.add(part.trim());
            }
        }
        LOGGER.info("Exiting method liveEventTypes");
        return types;
    }

    // ---- lot 21: T getters ----

    /**
     * The feed codes the store node pulls from the commercial service
     * (BO-11-01-01), each optionally carrying its nomenclature scheme after an
     * equals sign, in the administered order.
     *
     * @return the trimmed, non-blank entries, empty when nothing is pulled
     */
    public List<String> integrationPullFeeds() {
        List<String> feeds = new ArrayList<>();
        for (String entry : value("integration.pull-feeds").split(";")) {
            if (!entry.isBlank()) {
                feeds.add(entry.trim());
            }
        }
        return feeds;
    }

    /**
     * The interval between two pulls of the integration feeds, in minutes,
     * between 1 and 1440 (BO-11-01-01).
     *
     * @return the administered interval, bounded
     */
    public int integrationPullMinutes() {
        return Math.min(1440, Math.max(1, intValue("integration.pull-minutes")));
    }

    /**
     * The offer-label prefixes whose immediate discounts the upstream refunds
     * (BO-11-01-08); empty means every engine discount is refunded upstream.
     *
     * @return the trimmed, non-blank prefixes
     */
    public List<String> exportUpstreamOffers() {
        List<String> prefixes = new ArrayList<>();
        for (String entry : value("export.family-sales-upstream-offers").split(";")) {
            if (!entry.isBlank()) {
                prefixes.add(entry.trim());
            }
        }
        return prefixes;
    }

    /**
     * Whether the GS1 payments and scanned vouchers flow is produced
     * (BO-11-01-18).
     *
     * @return true when the flow is activated at this echelon
     */
    public boolean exportGs1PaymentsEnabled() {
        return boolValue("export.gs1-payments-enabled");
    }

    /**
     * Whether the register asks the store node whether a GS1 voucher was
     * already burned on another register (BO-11-01-18).
     *
     * @return true when the burn is centralised
     */
    public boolean gs1CentralBurn() {
        return boolValue("gs1.central-burn");
    }

    /**
     * Whether the vignettes are managed (BO-11-01-20).
     *
     * @return true when the theoretical count is printed and the declaration offered
     */
    public boolean vignetteEnabled() {
        return boolValue("vignette.enabled");
    }

    /**
     * The purchase step, tax included, that earns the vignettes of one step
     * (BO-11-01-20); a negative value reads as zero, which earns none.
     *
     * @return the step, never negative
     */
    public BigDecimal vignetteAmountStep() {
        return bigDecimalValue("vignette.amount-step").max(BigDecimal.ZERO);
    }

    /**
     * How many vignettes one whole purchase step earns (BO-11-01-20), never
     * negative.
     *
     * @return the vignettes per step
     */
    public int vignettePerStep() {
        return Math.max(0, intValue("vignette.per-step"));
    }

    /**
     * Whether the vignettes flow is produced (BO-11-01-20).
     *
     * @return true when the flow is activated at this echelon
     */
    public boolean exportVignettesEnabled() {
        return boolValue("export.vignettes-enabled");
    }

    /**
     * Whether the store node answers the price kiosks (BO-11-02-06).
     *
     * @return true when the kiosk exposure is on
     */
    public boolean kioskEnabled() {
        return boolValue("kiosk.enabled");
    }

    /**
     * The answer format the price kiosks expect (BO-11-02-06): JSON, XML or
     * TEXT, anything else reading as JSON.
     *
     * @return the format, upper case
     */
    public String kioskFormat() {
        String format = value("kiosk.format").trim().toUpperCase(java.util.Locale.ROOT);
        return "XML".equals(format) || "TEXT".equals(format) ? format : "JSON";
    }

    /**
     * Whether the prices are pushed to the electronic shelf labels on their own
     * (BO-11-03-05).
     *
     * @return true when the automatic push runs
     */
    public boolean eegAutoPushEnabled() {
        return boolValue("eeg.auto-push-enabled");
    }

    /**
     * The interval between two automatic pushes to the labels, in seconds,
     * between 10 and 86400 (BO-11-03-05).
     *
     * @return the administered interval, bounded
     */
    public int eegAutoPushSeconds() {
        return Math.min(86400, Math.max(10, intValue("eeg.auto-push-seconds")));
    }

    /**
     * Whether the cash changer carries out the cash loans and withdrawals
     * (BO-11-03-03).
     *
     * @return true when the machine counts and dispenses them
     */
    public boolean cashChangerMovements() {
        return boolValue("hardware.cashchanger.movements");
    }

    /**
     * The administered base URL of the NEXO Retailer server (BO-11-03-04), empty
     * when the deployment property applies.
     *
     * @return the trimmed URL, possibly empty
     */
    public String nexoHubUrl() {
        return value("nexo.hub-url").trim();
    }

    /**
     * The administered POI identifier of the NEXO Retailer server
     * (BO-11-03-04), empty when the deployment property applies.
     *
     * @return the trimmed identifier, possibly empty
     */
    public String nexoPoiId() {
        return value("nexo.poi-id").trim();
    }

    /**
     * Whether the dematerialised products are activated when the payment
     * starts rather than at their scan (BO-11-03-04).
     *
     * @return true when the administered moment is PAYMENT
     */
    public boolean nexoActivationAtPayment() {
        return "PAYMENT".equalsIgnoreCase(value("nexo.activation-moment").trim());
    }

    /**
     * Whether each prepaid-card payment is acknowledged to the NEXO server at
     * the close of the ticket (BO-11-03-04).
     *
     * @return true when the acknowledgement is sent
     */
    public boolean nexoPaymentAcknowledgement() {
        return boolValue("nexo.payment-acknowledgement");
    }

    /**
     * Whether the e-commerce platform may have its baskets valued by the store's
     * engine (BO-11-04-03).
     *
     * @return true when the valuation is exposed
     */
    public boolean ecommerceValuationEnabled() {
        return boolValue("channel.ecommerce.valuation-enabled");
    }

    /**
     * Whether the integration report of each e-commerce order is sent back to
     * the platform (BO-11-04-03).
     *
     * @return true when the status is returned
     */
    public boolean ecommerceStatusReturn() {
        return boolValue("channel.ecommerce.status-return");
    }

    /**
     * Whether the loyalty VT call is made synchronously at the end of the
     * ticket (BO-11-04-05).
     *
     * @return true when the register waits for the loyalty answer
     */
    public boolean fidelityVtSync() {
        return boolValue("fidelity.vt-sync");
    }

    /**
     * Whether the loyalty VT call carries the ticket's settlements
     * (BO-11-04-05).
     *
     * @return true when the payments travel with the basket
     */
    public boolean fidelityVtPayments() {
        return boolValue("fidelity.vt-payments");
    }

    /**
     * Whether the loyalty transactions that did not get through are resent by
     * the GT call (BO-11-04-06).
     *
     * @return true when the GT resend runs
     */
    public boolean fidelityGtEnabled() {
        return boolValue("fidelity.gt-enabled");
    }

    /**
     * The interval between two GT resends, in seconds, between 5 and 3600
     * (BO-11-04-06).
     *
     * @return the administered interval, bounded
     */
    public int fidelityGtSeconds() {
        return Math.min(3600, Math.max(5, intValue("fidelity.gt-seconds")));
    }

    /**
     * The path the GT call resends to (BO-11-04-06), empty when the VT path
     * applies.
     *
     * @return the trimmed path, possibly empty
     */
    public String fidelityGtPath() {
        return value("fidelity.gt-path").trim();
    }

    /**
     * Whether the loyalty service is called again before the ticket is printed,
     * to print the balance with the current transaction (BO-11-04-07).
     *
     * @return true when the VT2 call is made
     */
    public boolean fidelityVt2Enabled() {
        return boolValue("fidelity.vt2-enabled");
    }

    // ---- lot 21: U getters ----

    /**
     * Whether the node produces the monthly fiscal archives by itself
     * (BO-05-01-01).
     *
     * @return true when the previous month is archived automatically
     */
    public boolean fiscalArchiveAuto() {
        return boolValue("fiscal.archive-auto");
    }

    /**
     * How many years the tickets and invoices stay online (BO-05-01-06).
     *
     * @return the years, at least one
     */
    public int fiscalOnlineRetentionYears() {
        return Math.max(1, intValue("fiscal.online-retention-years"));
    }

    /**
     * Whether the invoices are transmitted as electronic invoices
     * (BO-05-01-02).
     *
     * @return true when e-invoicing is active
     */
    public boolean einvoicingEnabled() {
        return boolValue("fiscal.einvoicing-enabled");
    }

    /**
     * The mention printed at the foot of an invoice when e-invoicing is
     * active (BO-05-01-02).
     *
     * @return the mention, trimmed, empty when e-invoicing is off or none is administered
     */
    public String einvoicingMention() {
        return einvoicingEnabled() ? value("fiscal.einvoicing-mention").trim() : "";
    }

    /**
     * The code of the service provider playing the dematerialisation platform
     * (BO-05-01-02, BO-05-01-03).
     *
     * @return the code, trimmed, empty when none is administered
     */
    public String pdpProviderCode() {
        return value("fiscal.pdp-provider").trim();
    }

    /**
     * Whether the transactions are e-reported (BO-05-01-03).
     *
     * @return true when the closed periods are transmitted automatically
     */
    public boolean ereportingEnabled() {
        return boolValue("fiscal.ereporting-enabled");
    }

    /**
     * The period of the e-reporting (BO-05-01-03).
     *
     * @return {@code MONTH}, or {@code DECADE} for any other value
     */
    public String ereportingPeriod() {
        return "MONTH".equalsIgnoreCase(value("fiscal.ereporting-period").trim()) ? "MONTH" : "DECADE";
    }

    /**
     * Whether the Portuguese fiscal documents are sent at the flow, once to
     * each SAFT endpoint (BO-05-01-05, BO-11-04-02).
     *
     * @return true when every document is transmitted as it is consolidated
     */
    public boolean saftRealtime() {
        return boolValue("fiscal.saft-realtime");
    }

    /**
     * The day of the month before which the monthly SAF-T is extracted
     * (BO-05-01-04).
     *
     * @return the day, between 1 and 28
     */
    public int saftDeadlineDay() {
        return Math.min(28, Math.max(1, intValue("fiscal.saft-deadline-day")));
    }

    /**
     * The delay before a failed fiscal transmission is tried again
     * (BO-05-01-02, BO-05-01-03, BO-05-01-05).
     *
     * @return the delay in seconds, between 10 and 86400
     */
    public int fiscalRetrySeconds() {
        return Math.min(86400, Math.max(10, intValue("fiscal.transmission-retry-seconds")));
    }

    /**
     * Whether a pouch waits for the validation of its accounting day
     * (BO-05-02-10).
     *
     * @return true when the day must be validated first
     */
    public boolean pouchRequiresDayValidation() {
        return boolValue("safe.pouch-requires-day-validation");
    }

    /**
     * How many pouches an accounting day takes at most (BO-05-02-10).
     *
     * @return the count, zero meaning no limit
     */
    public int pouchesPerDay() {
        return Math.max(0, intValue("safe.pouches-per-day"));
    }

    /**
     * Whether a safe movement is validated only by a higher forcing level
     * (BO-05-02-15).
     *
     * @return true when the forcing levels order the validations
     */
    public boolean forcingLevelControl() {
        return boolValue("safe.forcing-level-control");
    }

    /**
     * Whether the advance ceiling of an employee is enforced (BO-05-02-15).
     *
     * @return true when an expense beyond the ceiling is refused
     */
    public boolean advanceLimitControl() {
        return boolValue("safe.advance-limit-control");
    }

    // ---- lot 21: V getters ----

    /**
     * The most articles an EXPRESS order holds in the cashier performance
     * profile (BO-06-06-02).
     *
     * @return the threshold, 10 when the administered value is below 1
     */
    public int reportExpressMaxItems() {
        int threshold = intValue("report.express-max-items");
        return threshold < 1 ? 10 : threshold;
    }

    /**
     * The family codes of the shop's fuel station, whose turnover the VAT
     * state by nomenclature can exclude (BO-06-07-01).
     *
     * @return the codes, trimmed, empty when the shop has no station
     */
    public java.util.List<String> reportStationFamilies() {
        java.util.List<String> codes = new java.util.ArrayList<>();
        for (String raw : value("report.station-families").split(";")) {
            if (!raw.isBlank()) {
                codes.add(raw.trim());
            }
        }
        return codes;
    }

    // ---- lot 21: W getters ----

    /**
     * Whether a third-party system must present its own token to deposit a
     * sale (BO-07-01-01).
     *
     * @return true when the shared synchronization token no longer suffices
     */
    public boolean integrationTokenRequired() {
        return boolValue("channel.integration.token-required");
    }

    /**
     * Whether an integrated e-commerce order carrying a loyalty card earns
     * (BO-07-01-03).
     *
     * @return true when its ticket-closed event is queued for the programme
     */
    public boolean ecommerceFidelityEarn() {
        return boolValue("channel.ecommerce.fidelity-earn");
    }

    /**
     * Whether the store node launches the end-of-period treatment on its own
     * (BO-09-03-03).
     *
     * @return true when the treatment starts at the end of period
     */
    public boolean periodAutoLaunch() {
        return boolValue("period.auto-launch");
    }

    /**
     * Whether the end of day records every ticket left on hold
     * (BO-09-01-05, BO-09-02-01).
     *
     * @return true when the tickets on hold are recorded at the close
     */
    public boolean periodParkedRecord() {
        return boolValue("period.parked-record");
    }

    /**
     * Whether the automatic end of period resets the tickets left on hold
     * (BO-09-01-08).
     *
     * @return true when their registers are ordered to cancel them
     */
    public boolean periodParkedRaz() {
        return boolValue("period.parked-raz");
    }

    /**
     * Whether a register closed by force prints a technical ticket
     * (BO-09-01-06, BO-09-02-03).
     *
     * @return true when the technical ticket is printed
     */
    public boolean periodForcedCloseTicket() {
        return boolValue("period.forced-close-ticket");
    }

    /**
     * Whether the hosts of the self-service islands are declared
     * automatically at the end of period (BO-09-03-01).
     *
     * @return true when their sessions close on their theoretical amount
     */
    public boolean periodScoAutoDeclaration() {
        return boolValue("period.sco-auto-declaration");
    }

    /**
     * Whether the technical entry points of the end of period run
     * (BO-09-02-04).
     *
     * @return true when the administered scripts are executed
     */
    public boolean periodHooksEnabled() {
        return boolValue("period.hooks-enabled");
    }

    /**
     * The script run before the end-of-period treatment (BO-09-02-04).
     *
     * @return its name, empty when none
     */
    public String periodHookBefore() {
        return value("period.hook-before").trim();
    }

    /**
     * The script run after the end-of-period treatment (BO-09-02-04).
     *
     * @return its name, empty when none
     */
    public String periodHookAfter() {
        return value("period.hook-after").trim();
    }

    /**
     * How long an end-of-period script may run (BO-09-02-04): a value below
     * one is treated as unset and the catalog default applies.
     *
     * @return the strictly positive limit, in seconds
     */
    public int periodHookTimeoutSeconds() {
        int configured = intValue("period.hook-timeout-seconds");
        return configured < 1 ? Integer.parseInt(def("period.hook-timeout-seconds").defaultValue()) : configured;
    }

    /**
     * Whether the registers check their data against the server's during the
     * end of period (BO-09-03-05).
     *
     * @return true when the check is ordered
     */
    public boolean periodRegisterCheck() {
        return boolValue("period.register-check");
    }

    /**
     * The network printer the end-of-period editions are sent to
     * (BO-09-01-10).
     *
     * @return {@code host:port}, empty when none
     */
    public String periodReportsPrinter() {
        return value("period.reports-printer").trim();
    }

    /**
     * Whether the articles of a direct-entry group are shown best seller
     * first rather than alphabetically (BO-03-01-10, BO-03-01-13).
     *
     * @return true for the sales-volume order, false for the alphabetical one
     */
    public boolean touchArticleSortVolume() {
        return boolValue("touch.article-sort-volume");
    }

    /**
     * The number of days of sales the store node computes the sales volume on
     * (BO-03-01-13): a value below one is treated as unset and the catalog
     * default applies.
     *
     * @return the strictly positive window, in days
     */
    public int touchSalesVolumeDays() {
        int configured = intValue("touch.sales-volume-days");
        return configured < 1 ? Integer.parseInt(def("touch.sales-volume-days").defaultValue()) : configured;
    }

    /**
     * Whether the register values the basket alone, without calling the
     * promotion engine (BO-03-06-42).
     *
     * @return true when the engine call is switched off
     */
    public boolean valuationLocalOnly() {
        return boolValue("valuation.local-only");
    }

    // ---- lot 21: Z getters ----

    /**
     * The communication subuser of the AT's invoice web service, carried in
     * clear in the WS-Security header (BO-11-04-02).
     *
     * @return the subuser, trimmed, empty when none is administered
     */
    public String atSubuser() {
        return value("fiscal.at-subuser").trim();
    }

    /**
     * The password of the AT's communication subuser, encrypted at each send
     * (BO-11-04-02).
     *
     * @return the password, empty when none is administered
     */
    public String atPassword() {
        return value("fiscal.at-password");
    }

    /**
     * The certificate of the AT's public key, in PEM, whose RSA key encrypts
     * the session key of each message (BO-11-04-02).
     *
     * @return the certificate, trimmed, empty when none is administered
     */
    public String atPublicCertificate() {
        return value("fiscal.at-public-certificate").trim();
    }

}
