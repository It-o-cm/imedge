# États financiers X et Z — campagne nœud magasin

*Établi le 06/10/2026, après trois tours d'arbitrage avec l'expert métier
Intermarché. Les règles du §2 sont ses réponses, pas des hypothèses : elles ne
se rediscutent pas en cours de route.*

Tu construis les **états financiers X et Z** du nœud magasin : les compteurs
chaînés par caissier et par point de vente, leur remise à zéro, les quatre
contenus financiers qu'ils restituent, l'écran qui les édite et les deux champs
qui manquent au transport côté caisse.

C'est une campagne **unique**, à dérouler d'un bout à l'autre sans rendre la
main. Les étapes du §10 sont un ordre d'exécution, pas des lots à faire valider.

---

## 0. Deux pièges avant la première ligne

**Le `CLAUDE.md` de ce dépôt est un contrat de GÉNÉRATION DE TESTS.** Sa
section « Scope — STRICT : never modify anything under `src/main` » vise les
campagnes de couverture. Elle **ne s'applique pas ici** : cette campagne écrit
du code de production. Tout le reste de `CLAUDE.md` s'applique intégralement —
anglais, Javadoc sur chaque méthode sans exception, tests unitaires purs
JUnit 5 + Mockito, jamais de `@QuarkusTest`, jamais de H2, pas de ligne vide
dans un corps de méthode, assertions `org.junit.jupiter.api.Assertions` seules.

**Le modèle et les payloads de synchronisation d'imedge sont une COPIE de ceux
d'impos**, maintenue identique au caractère près, package et imports exceptés.
Les deux champs du §8 se posent donc **des deux côtés**, dans les deux dépôts,
avec la même Javadoc. Ne jamais « améliorer » un côté seul.

---

## 1. Ce qu'on construit, et ce que ce n'est pas

**X est une lecture, Z est une lecture suivie d'une remise à zéro.** C'est tout
l'écart, et il commande l'architecture.

Le nœud magasin porte déjà douze rapports (`ui/report`), qui **recalculent** à
la demande sur une période choisie (`ReportPeriod`). Un Z n'est pas un rapport :
c'est un **document figé**. Il gèle ses totaux à l'émission, porte un numéro de
séquence, se chaîne au précédent par signature, et doit ressortir identique dix
mois plus tard — même si un article a changé de nomenclature entre-temps, même
si un ticket est arrivé en retard. Un rapport qui recalculerait donnerait un
autre chiffre, et c'est exactement ce qu'un Z ne doit jamais faire.

Conséquences, à tenir :

- **Un Z stocke ses chiffres**, il ne stocke pas une requête.
- **Ne modifie aucun rapport existant** de `ui/report`. Tu peux lire leur code
  pour la mécanique de regroupement, tu n'y touches pas.
- Un X, lui, **n'est pas persisté** : il se calcule sur l'intervalle ouvert en
  cours et se jette. Seule la lecture est tracée, par un `TechnicalEvent`.

---

## 2. Les arbitrages de l'expert — la loi de ce chantier

**R1 — Deux porteurs de compteur : le caissier et le PDV.** Un compteur Z par
caissier, un compteur Z par point de vente. Rien d'autre.

**R2 — Le PDV sort un Z tous les soirs, toutes les semaines et tous les mois**,
quel que soit le mode de fond de caisse. Ses trois compteurs sont
**indépendants** : le Z semaine n'est pas la somme rejouée des Z jour, c'est son
propre compteur, qui vaut par construction le total des Z jour depuis la
dernière RAZ hebdomadaire. Les deux chiffres se comparent, et un écart est une
anomalie à afficher (§4).

**R3 — Le caissier ne porte QU'UN SEUL compteur**, remis à zéro à chaque RAZ à
laquelle il est chaîné :
- fond de caisse **fixe** : chaîné au jour. Le Z caissier est quotidien, et il
  n'existe donc **ni état hebdomadaire ni état mensuel par caissier** au sens du
  Z ;
- fond de caisse **flottant** : chaîné à la semaine et au mois, pas au jour.

**R4 — La borne mensuelle coupe la semaine en deux.** La période d'un compteur
est toujours « depuis la dernière RAZ », fermée par celle des bornes qui arrive
la première. Le 31 tombant un mercredi en fond flottant : RAZ le mercredi soir
(lundi → mercredi), puis RAZ le dimanche soir (jeudi → dimanche).

**R5 — Semaine du lundi, mois calendaires.** Le mois calendaire EST le mois
comptable Intermarché.

**R6 — Les jours non travaillés sont paramétrés**, et **un CA nul vaut jour non
travaillé**. Aucun Z quotidien n'est émis pour un jour non travaillé, et la
borne hebdomadaire tombe à la fin du **dernier jour travaillé** de la semaine.

**R7 — CA brut avant toute opération négative, CA net après.** C'est le CA net
que demande le fisc, et les deux figurent sur l'état.

**R8 — Deux lignes de retour, jamais une : retour AVEC ticket et retour SANS
ticket.** L'annulation d'un ticket déjà payé est un retour de marchandise et
part sur la ligne « retour avec ticket ».

**R9 — Une ligne « abandon de ticket »**, distincte des retours : un ticket
abandonné avant paiement n'a jamais été une vente.

**R10 — L'annulation de ligne a deux visages.** La caissière peut annuler la
**dernière** ligne seule ; l'annulation d'une ligne quelconque passe par l'aval
d'un superviseur et c'est celle-là qui est un **indicateur de fraude**. L'état
les compte séparément.

**R11 — Les espèces, c'est ce qui reste dans le tiroir après rendu de monnaie.**
Donc le montant du règlement, jamais le montant remis par le client.

**R12 — Les cartes cadeaux sortent du CA** : c'est un mode de règlement, pas une
vente. Elles figurent en émission, sur leur propre ligne.

**R13 — Consignes et déconsignes sont des articles** : elles restent dans le CA.

**R14 — Nombre de clients = tickets positifs.** Panier moyen = CA **net du PDV**
divisé par ce nombre.

**R15 — Chaque article compte pour 1 quantité quel que soit son poids.** Un
article retourné **diminue** le nombre d'articles vendus.

**R16 — % de CA alimentaire = frais TRAD + frais LS + sec**, rapporté au CA net.
Le quatrième secteur est le non alimentaire.

**R17 — La tranche horaire d'un ticket est celle de sa clôture, c'est-à-dire de
son paiement.** Les tranches vont du premier au dernier ticket de la période,
pas de 00:00 à 23:59.

**R18 — Le X du PDV porte tous les tickets depuis l'ouverture** pour le jour,
depuis le lundi matin pour la semaine, depuis le 01 pour le mois. C'est
exactement l'intervalle ouvert du compteur correspondant.

**R19 — Les meilleures ventes sont classées sur la QUANTITÉ**, avec le CA en
face, et la profondeur du classement est paramétrable par PDV.

**R20 — Les titres restaurant dématérialisés (TRD) ont leur propre ligne de
règlement**, malgré des volumes très faibles.

---

## 3. Le modèle

Deux entités, dans `domain/session` — pas de paquet neuf pour deux classes.

### `FinancialState` — le document

| Champ | Type | Rôle |
|---|---|---|
| `stateType` | enum `StateType` { `X`, `Z` } | lecture ou RAZ |
| `scope` | enum `StateScope` { `STORE`, `CASHIER` } | le porteur du compteur |
| `periodKind` | enum `PeriodKind` { `DAY`, `WEEK`, `MONTH` } | la borne |
| `cashier` | `@ManyToOne Employee` | null en scope `STORE` |
| `sequence` | `long` | numéro du Z dans sa chaîne, 0 pour un X |
| `fromInstant` / `toInstant` | `LocalDateTime` | l'intervalle, demi-ouvert |
| `emittedAt` / `emittedBy` | `LocalDateTime` / `Employee` | qui a édité |
| `automatic` | `boolean` | émis par la fin de période, non par un opérateur |
| `grossTurnover` | `BigDecimal(19,4)` | R7 |
| `discountTotal` | | l'écart brut → net porté par les remises |
| `refundWithTicketTotal` / `refundWithoutTicketTotal` | | R8 |
| `netTurnover` | | R7 |
| `vatTotal` | | |
| `customerCount` | `int` | R14 |
| `itemCount` | `int` | R15 |
| `averageBasket` | `BigDecimal(19,4)` | R14 |
| `foodShare` | `BigDecimal(9,4)` | R16, en pourcentage |
| `cancelledLineCount` / `cancelledLineTotal` | | R10, annulations de dernière ligne |
| `endorsedCancelCount` / `endorsedCancelTotal` | | R10, indicateur de fraude |
| `abandonedTicketCount` / `abandonedTicketTotal` | | R9 |
| `giftCardCount` / `giftCardTotal` | | R12 |
| `controlTotal` | `BigDecimal(19,4)` | R2 : la somme des Z de rang inférieur |
| `controlVariance` | `BigDecimal(19,4)` | R2 : `netTurnover - controlTotal` |
| `lateTicketCount` | `int` | tickets arrivés après l'émission du Z précédent |
| `floatMode` | `String(30)` | le mode de fond au moment de l'émission |
| `signature` / `previousSignature` | `String(64)` | le chaînage |
| `printCount` | `int` | une réédition n'est pas une nouvelle émission |
| `lines` | `@OneToMany FinancialStateLine` | les quatre contenus |

### `FinancialStateLine` — une ligne de n'importe quelle section

| Champ | Rôle |
|---|---|
| `section` | enum `StateSection` { `SETTLEMENT`, `VAT`, `HOURLY`, `NOMENCLATURE`, `SECTOR`, `BEST_SELLER`, `CASHIER` } |
| `rank` | l'ordre d'impression dans la section |
| `code` / `label` | le règlement, la tranche, le nœud IFLS, l'EAN, le login |
| `quantity` | `BigDecimal(19,4)`, nullable |
| `amount` | `BigDecimal(19,4)` |
| `amountExcludingTax` / `vatAmount` | nullables, servies par `VAT` et `NOMENCLATURE` |
| `share` | `BigDecimal(9,4)`, part du CA net de l'état, nullable |

Une seule table pour les sept sections : un Z a vingt lignes de nomenclature et
quatre de TVA, et sept tables porteraient sept fois le même schéma.

### Le chaînage

`signature` = SHA-256 hexadécimal de la concaténation, séparée par `|`, de :
`stateType`, `scope`, `periodKind`, le login du caissier ou la chaîne vide,
`sequence`, `fromInstant`, `toInstant`, `grossTurnover`, `netTurnover`,
`customerCount`, `itemCount` et `previousSignature`. Même technique que
`EngineFeedService` / `RefExportService` : `MessageDigest.getInstance("SHA-256")`.
`previousSignature` est la signature du Z précédent de la **même chaîne**, la
chaîne vide pour le premier.

---

## 4. Le compteur, la RAZ, les arrivées tardives

**Il n'y a pas d'entité compteur, et c'est voulu : le compteur EST la chaîne des
Z.** Une chaîne est identifiée par le triplet (`scope`, `periodKind`,
`cashier`). Pour une chaîne donnée :

- `fromInstant` du prochain Z = `toInstant` du Z précédent de la chaîne ; pour le
  premier Z, l'instant du premier ticket connu du magasin ;
- `toInstant` = la borne atteinte ;
- `sequence` = `sequence` du précédent + 1, alloué sous verrou pessimiste
  (`find(...).withLock(LockModeType.PESSIMISTIC_WRITE)`) sur le dernier Z de la
  chaîne, de sorte que deux émissions simultanées ne puissent pas prendre le même
  numéro.

C'est R3 et R4 sans un seul `if` de plus : un caissier n'a qu'une chaîne, dont
la borne est le jour en fond fixe et la semaine ou le mois en flottant, et la
borne mensuelle ferme l'intervalle courant quand elle arrive la première.

**Émission automatique.** Elle s'accroche au tick de `PeriodCloseService`, qui
sait déjà lire l'heure de fin de période. **Avant** l'appel à
`retentionPurgeService.sweep()`, jamais après : la purge peut emporter des
tickets dont le Z a besoin. Une fois la fin de période atteinte, et une seule
fois par jour :

1. Si le jour est travaillé (R6) : Z `STORE`/`DAY`.
2. Si le jour est le dernier jour travaillé de sa semaine : Z `STORE`/`WEEK`.
3. Si le jour est le dernier jour travaillé de son mois : Z `STORE`/`MONTH`.
4. Pour chaque caissier ayant encaissé depuis sa dernière RAZ : son Z unique, à
   la borne que R3 lui donne selon `cash.float-mode`.

**Idempotence.** Un Z par chaîne et par borne. Une seconde demande sur une borne
déjà émise ne crée rien : elle rend le Z existant et incrémente `printCount`.

**Émission manuelle.** Le même service, appelé depuis l'écran, avec
`emittedBy` renseigné et `automatic` à faux. Elle respecte la même idempotence.

**Jours non travaillés (R6).** Un jour est non travaillé s'il figure dans
`financial.closed-weekdays`, ou s'il est dans `financial.closed-dates`, ou si son
CA net est nul. Le dernier jour travaillé d'une semaine est le dernier jour
travaillé inférieur ou égal au dimanche ; même raisonnement pour le mois.

**Arrivées tardives.** Une caisse hors ligne pousse ses tickets plus tard. Un
ticket dont la clôture tombe dans un intervalle déjà clos par un Z **n'est jamais
rétro-intégré** — un Z ne se recalcule pas. Il est compté dans le Z suivant de la
chaîne, et le nombre de ces tickets est porté par `lateTicketCount` et affiché à
l'écran. Un Z dont `lateTicketCount` n'est pas nul le dit sur son édition.

**Le contrôle de R2.** À l'émission d'un Z `STORE`/`WEEK`, `controlTotal` reçoit
la somme des `netTurnover` des Z `STORE`/`DAY` de l'intervalle, `controlVariance`
l'écart avec son propre `netTurnover`. Même règle pour le mois sur les semaines
qu'il contient entièrement, complétées par les jours des semaines coupées par la
borne mensuelle.

---

## 5. Les quatre contenus

### 5.1 Le CA financier (R7, R8, R9, R10, R11, R12, R13, R14, R15)

Les identités à respecter, et à **asserter dans les tests** :

```
grossTurnover  = somme sur les lignes de vente de quantity x (originalUnitPrice ou unitPrice)
discountTotal  = grossTurnover - somme des totalPrice de ces memes lignes
netTurnover    = grossTurnover - discountTotal - refundWithTicketTotal - refundWithoutTicketTotal
```

Lignes de vente = lignes de tickets **clos**, non annulées, de quantité
positive, `moneyProduct` à faux. Un seul chiffre de remise, qui absorbe par
construction la remise de ligne, la quote-part de remise ticket et la promotion
du moteur de valorisation : trois origines, un écart, et aucun trou possible
entre le brut et le net.

Retours : les lignes de quantité négative des tickets de vente **et** les
`Refund`. `originalTicketId` non nul → avec ticket, nul → sans ticket.

Hors CA, en indicateurs : lignes annulées (R10, deux compteurs selon l'aval),
tickets abandonnés (R9, `status = CANCELLED`), cartes cadeaux émises (R12,
lignes `moneyProduct`).

Nombre d'articles (R15) : une quantité non entière compte 1, une quantité
entière compte sa valeur ; un retour retranche selon la même règle.

Nombre de clients (R14) : tickets clos de `grandTotal` strictement positif.
Panier moyen = `netTurnover / customerCount`, deux décimales, zéro si aucun
client.

Section `SETTLEMENT` : une ligne par moyen de règlement, le montant des espèces
pris sur `amount` et non sur `tenderedAmount` (R11), une ligne propre pour les
TRD (R20), les devises portant leur montant en devise dans `quantity`.

Section `CASHIER` : sur un état de scope `STORE`, une ligne par caissier avec son
CA net, son nombre de clients et son panier moyen. **Sauf** Z `STORE`/`DAY` en
fond flottant : l'expert a tranché qu'il n'y a pas de détail caissier sur
celui-là, seulement le financier du PDV.

### 5.2 Le CA par tranche horaire (R17)

Tranche prise sur `closingDate`, à défaut `creationDate`. Largeur donnée par
`financial.hourly-slot-minutes`. Les tranches couvrent de la tranche du premier
ticket à celle du dernier ticket de l'intervalle, bornes comprises, **sans
trous** : une tranche sans vente au milieu de la journée est une ligne à zéro,
parce que son absence se lit comme une erreur d'édition.

### 5.3 Le CA par nomenclature IFLS (R16)

`TicketLine.familyCode` porte le nœud feuille. Les ancêtres se remontent par
`Nomenclature.parentCodeOf` et `levelOfCode` sur la nomenclature IFLS. Une ligne
`NOMENCLATURE` par nœud du niveau de restitution
(`financial.nomenclature-level`, rang par défaut 1), et une ligne `SECTOR` par
secteur, avec son sous-total.

Les quatre secteurs viennent de `financial.sectors`, qui associe un code de
nœud de rang 0 à l'un des quatre : `FRAIS_TRAD`, `FRAIS_LS`, `SEC`,
`NON_ALIMENTAIRE`. Un `familyCode` qui ne se résout pas va sur une ligne
`NON CLASSÉ` et n'est **jamais** absorbé dans un secteur : un chiffre mal classé
qui disparaît dans un sous-total est pire qu'un chiffre visiblement non classé.

`foodShare` = (FRAIS_TRAD + FRAIS_LS + SEC) / `netTurnover`, en pourcentage à
deux décimales, zéro si le CA net est nul.

### 5.4 Les meilleures ventes (R19)

Regroupement par EAN, à défaut PLU, à défaut libellé. Classement sur la quantité
décroissante, puis sur le montant décroissant pour départager. Profondeur :
`financial.best-sellers-count`.

---

## 6. L'écran, les droits, les exports

**`ui/financial`** : `FinancialStateResource` et `FinancialStateService` — un
service qui sert un flux d'écran vit dans `ui.*`, pas dans `service`. Le calcul
pur des totaux, lui, va dans `service/FinancialStateCalculator`, parce qu'il ne
dépend d'aucun écran et qu'il doit être testable seul.

Deux gabarits, sur `admin-layout.html` comme tous les autres :
`admin-financial-states.html` (la liste des Z émis, le bouton d'édition d'un X,
l'alerte d'écart de contrôle et de tickets tardifs) et
`admin-financial-state.html` (un état, ses sections, impression navigateur).

Deux entrées dans `Feature`, sous `Menu.FINANCIER` :

```
FINANCIAL_STATE("financial_state", Menu.FINANCIER, "États financiers X/Z", "/admin/financial-states")
```

Les droits passent par `PermissionService.decide(login, Feature.FINANCIAL_STATE,
option)` comme partout : `READ` pour un X et pour relire un Z, `CREATE` pour
émettre un Z. **Émettre un Z n'est pas une lecture** et ne doit pas tomber sous
le droit de lecture.

Exports : **aucun nouveau rédacteur**. Chaque section devient un `ReportData` et
part dans `CsvReportWriter`, `XlsxReportWriter`, `PdfReportWriter` existants.

Journal : un `TechnicalEvent` à chaque émission de Z et à chaque lecture de X.
Ajoute les types manquants à `TechnicalEvent.EventType` — et n'oublie pas que
`TechnicalEventTest` compte les valeurs de l'énumération, ce compte est à mettre
à jour dans le même commit.

---

## 7. Les paramètres

Au catalogue de `PosSettingsService` (imedge), rubrique **ÉTATS FINANCIERS**,
avec leur accesseur et la Javadoc qui dit à quoi sert la valeur :

| Clé | Type | Défaut | Rôle |
|---|---|---|---|
| `financial.closed-weekdays` | TEXT | vide | jours de fermeture hebdomadaire, noms `DayOfWeek` séparés par `;` (R6) |
| `financial.closed-dates` | TEXT | vide | fermetures exceptionnelles, `AAAA-MM-JJ` séparées par `;` (R6) |
| `financial.hourly-slot-minutes` | INT | 60 | largeur de tranche, 15, 30 ou 60 (R17) |
| `financial.nomenclature-level` | INT | 1 | rang de restitution du CA par nomenclature |
| `financial.sectors` | TEXT | `10>FRAIS_TRAD;20>FRAIS_LS;30>SEC;40>NON_ALIMENTAIRE` | les quatre secteurs (R16) |
| `financial.best-sellers-count` | INT | 20 | profondeur du classement (R19) |
| `financial.auto-emit-enabled` | BOOL | true | l'émission automatique de fin de période |

**Et une correction à faire au passage, parce que le chaînage en dépend :**
`cash.float-mode` d'imedge ne connaît que `FIXED` et `FLOATING`, alors
qu'impos en expose trois (`FIXED`, `FLOATING_WEEK_MONTH`, `FLOATING_MONTH`, plus
`FLOATING` comme synonyme de la deuxième). Aligne le libellé et la description
d'imedge sur ceux d'impos, à l'identique. R3 lit cette valeur : un mode mal
interprété décale toutes les RAZ caissier.

---

## 8. Les deux champs qui manquent au transport

`SyncPayloads.LineDto` ne porte pas ce que les états financiers exigent. Deux
champs, **dans les deux dépôts**, à l'identique.

**8.1 `moneyProduct` (R12).** `TicketLine.moneyProduct` existe **des deux
côtés** ; seul le DTO ne le transporte pas. Donc : le champ dans `LineDto`, son
remplissage dans `SyncOutboxService` côté impos, sa reprise dans
`SyncIngestService` côté imedge. Sans lui, le nœud magasin ne peut pas sortir
les cartes cadeaux du CA, et le CA net est faux de tout ce qu'un magasin vend
de cartes.

**8.2 `cancelledLast` (R10).** Champ neuf, à créer. Sur
`TicketLine` d'impos **et** d'imedge :

```java
/**
 * Whether this line was the LAST of the ticket when it was cancelled.
 *
 * <p>The distinction is the whole point of the fraud indicator. A cashier may
 * cancel the last line alone — a mis-scan corrected on the spot. Cancelling any
 * other line needs a supervisor's endorsement, and it is that cancellation the
 * financial state counts apart: a line pulled out of the middle of a ticket is
 * the gesture a fraud hides behind.
 *
 * <p>False on a line that was never cancelled, where it means nothing: read it
 * only together with {@link #cancelled}.
 */
@Column(name = "cancelled_last", nullable = false)
public boolean cancelledLast;
```

Renseigné côté impos là où la ligne est annulée, par comparaison du numéro de
ligne annulée au dernier numéro du ticket au moment du geste. Puis `LineDto`,
`SyncOutboxService`, `SyncIngestService`.

**Ce qui n'est PAS à faire** : les tickets abandonnés voyagent déjà, avec
`TicketDto.status = CANCELLED` (R9), et `PaymentDto` porte déjà `amount` et
`tenderedAmount`, donc R11 ne demande aucun développement de transport.

---

## 9. Les règles de code

- Anglais, code et commentaires. Les libellés d'écran restent en français.
- **Javadoc sur chaque méthode sans exception** : getters, setters,
  constructeurs, méthodes privées, callbacks de cycle de vie, redéfinitions.
- Pas de paquet à classe unique. `service` = services généraux ; un service qui
  sert un flux d'écran va dans `ui.*`.
- Périmètre strict : rien d'autre que ce que ce document demande. Une amélioration
  repérée hors périmètre se signale en une ligne dans le rapport, elle ne
  s'applique pas.
- Tests : une classe de test complète par classe de production, **chaque jambe de
  chaque expression booléenne composée** couverte, nullités comprises. Les
  identités du §5.1 sont des tests, pas des commentaires.
- `git add` et `git commit` uniquement. **Jamais de `git push`**, jamais de
  branche, jamais de `git` autre que ces deux commandes.

---

## 10. Ordre d'exécution

Un commit par étape, message en une ligne, impératif, en anglais.

1. **Transport** (§8) : les deux champs dans les deux dépôts, DTO, outbox,
   ingest, et les tests des deux côtés. À faire en premier : tout le reste s'y
   appuie.
2. **Paramètres** (§7), correction de `cash.float-mode` comprise.
3. **Modèle** (§3) : les deux entités, leurs énumérations, le calcul de
   signature, les tests.
4. **Calculateur** (`service/FinancialStateCalculator`, §5) : les quatre contenus
   et les identités. C'est la pièce la plus lourde et la plus testable ; elle ne
   connaît ni écran ni émission.
5. **Émission et chaînage** (§4) : le service d'émission, l'allocation de
   séquence sous verrou, l'idempotence, les jours non travaillés, le contrôle de
   R2, les tickets tardifs.
6. **Accrochage à la fin de période** : le tick de `PeriodCloseService`, avant la
   purge.
7. **Écran, droits, exports, journal** (§6).
8. **Relecture** : relis les vingt règles du §2 une à une contre le code écrit, et
   rapporte pour chacune l'endroit qui la porte. Une règle sans endroit est un
   oubli, pas un détail.

---

## 11. Rapport

Par étape, à la fin de l'étape :

```
etape <n> : <titre>
fichiers crees : ...
fichiers modifies : ...
tests ajoutes : k   branches couvertes : n/n (x %)
tests executes : avant -> apres
hors perimetre repere : ... (ou aucun)
```

Puis un rapport final en quatre listes : les vingt règles du §2 avec l'endroit
qui porte chacune ; la couverture de branches des classes créées ; ce qui reste
à faire et pourquoi ; les questions que le code a soulevées et que ce document
ne tranche pas — formulées pour l'expert métier, une phrase chacune, sans y
répondre à sa place.
