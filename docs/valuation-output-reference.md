# imvaluation — Référence de la sortie `/valuation` (pour le calcul des droits fidélité)

Exemple joint : `valuation-request.json` (l'entrée) → `valuation-response.json` (la
sortie). L'exemple exerce **toutes** les familles de résultat que le moteur sait
produire : geste commercial, bundle mixte, N+M (2+1), lignes standard mono et
multi-taux, produit au poids, livraison, consigne de panier, remise vignettes, deux
bons immédiats cumulés, franco de port, assiette titre-restaurant, deux suggestions
d'upsell.

## 1. Contexte d'appel

- `POST /valuation` sur `:8090`, `Content-Type: application/json`, **HTTP Basic**
  (tout utilisateur authentifié, aucun rôle exigé).
- Réponse **200** = le JSON ci-dessous. Autres statuts : **400** panier invalide au
  schéma, **422** items non traités (`Valuation failed: Some items could not be
  processed by any offer.`), **500** erreur de configuration (EAN inconnu, prix
  absent, offre mal configurée…). Les corps 4xx/5xx ne sont pas garantis (exception
  sans entité) ; le message exact est journalisé et tracé en base
  (`valuation_traces.error_message`).
- Jeu de données de l'exemple : le seed du `ValuationEndToEndTest` (magasin `0101`,
  prix valides depuis `2026-01-12T00:00:00`, 9 offres).

## 2. Structure racine — exactement 5 clés

| Clé | Type | Contenu |
|---|---|---|
| `offers` | array | Ce que le client PAIE : chaque application d'offre avec son montant et sa décomposition par ligne |
| `advantages` | array | Ce qui est DÉDUIT ou SIGNALÉ : remises, assiette titre-restaurant, suggestions d'upsell |
| `totalPrice` | object | Total net du panier = Σ `offers[].amount` − Σ `advantages[].discountAmount` |
| `vatBreakdown` | array | Ventilation TVA par taux, triée par taux croissant |
| `availableToUpcell` | object | Map EAN → reliquat parti au tarif standard (matière à suggestion) |

**⚠ `offers` et `advantages` sont des ensembles NON ORDONNÉS** (HashSet côté moteur) :
l'ordre des éléments change d'un appel à l'autre. Toujours discriminer par contenu,
jamais par index.

## 3. Le bloc monétaire commun (`AmountEvaluation`)

```json
{ "amountExcludingTax": 11.25, "amountIncludingTax": 13.50, "vatRate": 0.2000 }
```

- HT et TTC : échelle 2, arrondi HALF_UP. `vatRate` : **fraction** (0.2000 = 20 %),
  échelle 4.
- Sur `totalPrice`, `vatRate` vaut **toujours `0.0000`** (mélange de taux) — ne jamais
  l'utiliser ; la TVA réelle est dans `vatBreakdown`.

## 4. `offers[]` — trois clés par élément : `type`, `amount`, `items`

`type` est une **chaîne descriptive, pas un code** — c'est le seul discriminant.
Formats littéraux possibles :

| Préfixe de `type` | Signification | `items` |
|---|---|---|
| `Standard: EAN=<ean>, Qty=<q>` | Ligne au tarif catalogue | 1 tranche |
| `Manual Gesture: EAN=<ean> (forced price <v>)` / `(amount -<v>)` / `(percent -<v>%)` | Geste de caisse (remplace le prix ; la ligne est exclue de toute remise et de l'upsell) | tranches des lignes sources |
| `Mixed Bundle Promo: <code>` | ⚠ **C'est l'offre N+M** (libellé trompeur, figé) | bloc payé PUIS bloc remisé (montant 0.00 si gratuité 100 %) |
| `MixedBundle: <code> x<n> for <ttc>€` | Bundle mixte (prix de lot) | tranches des composants au prorata |
| `Delivery: <code> (<km> km) for <prix>€` | Frais de livraison | **`[]`** |
| `Deposit Basket: <n> x <prix>€` | Consigne de paniers | **`[]`** |

- `items[]` : `{ lineId, produceEan, quantity, amount }` — la restitution **par ligne
  de panier d'origine** (`lineId` = celui de la requête). Invariant garanti :
  **Σ `items[].amount` = `amount` de l'offre, au centime**.
- Le `vatRate` de chaque item est le **taux réel du produit** — c'est la bonne base
  pour un calcul de droits par ligne/produit.
- ⚠ Le code d'offre n'existe pas en champ séparé : il faut l'extraire du `type`
  (ex. `Mixed Bundle Promo: PROMO_2FOR1_3300`).
- ⚠ Deux offres du même `type` peuvent coexister (deux tranches de prix d'un même
  EAN → deux lignes `Standard`).

## 5. `advantages[]` — trois formes, à discriminer par la présence des champs

**Règle de discrimination robuste** (recommandée pour le connecteur fidélité) :

1. `discountAmount` présent → **vraie remise**, déjà déduite de `totalPrice`.
2. `suggestion` présent → **upsell informatif**, aucun impact monétaire.
3. `type == "MEAL_VOUCHER"` → **assiette titre-restaurant**, aucun impact monétaire.

### 5.1 Remises (`discountAmount` non nul)

```json
{ "type": "Immediate Voucher Discount : PROMO_STORE_101",
  "offer": "Standard: EAN=3300000000001, Qty=1.0",
  "discountAmount": { "amountExcludingTax": 0.17, "amountIncludingTax": 0.20, "vatRate": 0.2000 } }
```

- `discountAmount` est **stocké POSITIF** ; le signe vit dans la soustraction du
  total. Pour la fidélité : `montant réellement payé = Σ offers − Σ discountAmount`.
- `offer` = le `type` de l'application d'offre ciblée (chaîne, même format que §4) —
  c'est le lien remise → ligne(s) : en passant par les `items` de l'offre ciblée on
  ré-affecte la remise aux `lineId`.
- Types littéraux : `Immediate Voucher Discount : <code>` (⚠ espace avant les
  deux-points), `Vignette Discount: <code> (<n> vignettes used, applied <m> times)`,
  `Free Delivery Threshold Discount: <code>`.
- Plusieurs remises peuvent viser la même offre (cumul — ici deux bons sur la pomme).
- ⚠ Les bons immédiats et les vignettes ne sont **pas plafonnés** au prix du
  produit : un total négatif est théoriquement possible.

### 5.2 Assiette titre-restaurant

```json
{ "type": "MEAL_VOUCHER", "offerCode": "MEAL_VOUCHER_0101",
  "totalEligibleAmount": 7.71, "threshold": 25.00 }
```

- `totalEligibleAmount` = somme TTC des produits porteurs du flag d'éligibilité
  (familles remontées hiérarchiquement), **nette des remises** portant sur ces
  offres, hors lignes gestées, hors livraison/consigne.
- ⚠ Le montant **plafonné** (`min(assiette, threshold)`) est calculé en interne mais
  **PAS exposé** : si le plafond importe au calcul de droits, c'est au consommateur
  de faire `min(totalEligibleAmount, threshold)`.

### 5.3 Suggestions d'upsell

```json
{ "type": "Upsell N+M: PROMO_2FOR1_3300 (Need 2.00 of 3300000000001)",
  "suggestion": { "ean": "3300000000001", "quantity": 2.0, "offerCode": "PROMO_2FOR1_3300" } }
```

- Purement informatif (« ajoutez 2 pommes pour compléter un lot 2+1 »). Les champs
  structurés sont dans `suggestion` — ne pas parser le `type`.
- ⚠ Le `type` contient un `%.2f` **dépendant de la locale de la JVM** : sur un
  serveur en `fr_FR` on lira `Need 2,00 of …` (virgule). Raison de plus pour ne
  s'appuyer que sur `suggestion`.

## 6. `totalPrice`, `vatBreakdown`, `availableToUpcell`

- `totalPrice` : le net à payer. Invariants : `totalPrice.TTC = Σ offers.TTC − Σ
  discounts.TTC` et `totalPrice.TTC = Σ vatBreakdown[].amountIncludingTax` — au
  centime (les écarts d'arrondi sont réconciliés sur le taux le plus élevé).
- `vatBreakdown[]` : `{ vatRate, amountExcludingTax, vatAmount, amountIncludingTax }`
  par taux, trié croissant ; `vatAmount = TTC − HT` ligne à ligne. C'est LA source
  fiable de TVA (jamais `totalPrice.vatRate`).
- `availableToUpcell` : map `EAN → { lineId, produceEan, quantity }` — uniquement ce
  qui a fini au tarif **standard** (le reliquat non consommé par les
  bundles/N+M/gestes). Utile en fidélité pour distinguer « acheté plein tarif » de
  « acheté sous promotion ».

## 7. Chiffres de l'exemple, pour recette du connecteur

| Contrôle | Valeur |
|---|---|
| Σ `offers[].amount.amountIncludingTax` | 65.25 |
| Σ `advantages[].discountAmount.amountIncludingTax` | 18.14 (7.92 + 0.20 + 0.12 + 9.90) |
| `totalPrice.amountIncludingTax` | **47.11** |
| `totalPrice.amountExcludingTax` | 40.46 |
| Σ `vatBreakdown[].amountIncludingTax` | 10.55 + 36.56 = 47.11 ✓ |
| TVA collectée | 0.55 + 6.10 = 6.65 |
| Assiette titre-restaurant | 7.71 (café du bundle 2.87 + bloc payé N+M 2.64 + pomme standard nette 1.00 + eaux 1.20) |

Lecture métier du panier : 4 pommes → 3 absorbées par le 2+1 (2 payées, 1 offerte),
la 4ᵉ au tarif standard remisée par deux bons ; café + biscuits → bundle à 4.50 ;
chips restées au tarif standard (substitut non utilisé) ; poulet à 5,5 % de TVA ;
poêle remisée de 50 % contre 5 vignettes ; casserole sous geste −25 % (exclue de
toute autre remise) ; livraison 9.90 à 10.23 km **entièrement offerte** par le franco
(panier marchandises > 20 €) ; 2 consignes de panier (19.15 L / 10 L par panier).

## 8. Pièges récapitulés pour l'intégrateur fidélité

1. Ordre non déterministe de `offers` et `advantages` — discriminer par contenu.
2. `type` = chaîne descriptive ; le code d'offre s'extrait du texte (ou de
   `offerCode`/`suggestion.offerCode` quand il existe en structuré).
3. `Mixed Bundle Promo: …` est une offre **N+M**, pas un bundle mixte.
4. Remises **positives**, déjà déduites du total — ne pas les re-déduire.
5. `totalPrice.vatRate` toujours `0.0000` — TVA via `vatBreakdown`.
6. `payableAmount` du titre-restaurant non exposé — plafonner côté consommateur.
7. Montants négatifs possibles (remises non plafonnées) — prévoir le cas.
8. Locale JVM dans les libellés d'upsell (`2.00` vs `2,00`).
9. `quantity` est un double : unités pour un produit UNIT, **kilogrammes ou litres**
   pour WEIGHT/VOLUME (ex. poulet `1.2`).
10. Une ligne sous geste manuel (`Manual Gesture`) n'apparaît dans AUCUNE remise ni
    dans l'assiette TR — si les droits fidélité excluent les gestes, le filtre est
    déjà fait ; sinon, la retrouver via son offre.
11. Le prix de référence d'une ligne standard passe du tarif `DEFAULT` au tarif
    `BASE_FOR_DISCOUNT` dès qu'une remise la vise (ici la 4ᵉ pomme vaut 1.32 et non
    1.20, et la poêle 15.84 et non 14.40) : les remises se calculent sur une base
    majorée — à connaître si les droits se calculent « avant remise ».

## 9. Reproduire cette sortie sur une instance réelle

```bash
# seed préalable : rejouer les imports du ValuationEndToEndTest (ou ImportAllClient)
curl -u admin:admin -H "Content-Type: application/json" \
     -d @valuation-request.json http://localhost:8090/valuation | jq .
```

Note d'honnêteté : cette réponse a été **dérivée du code à la main** (sérialisation
et arithmétique du moteur vérifiées classe par classe), le bac à sable de rédaction
n'ayant pas accès à Maven Central pour builder l'application. Les montants et les
libellés sont calculés selon la sémantique exacte du moteur sur le seed du test e2e ;
avant de figer le contrat côté fidélité, rejouer la requête ci-dessus sur une
instance réelle pour confirmer bit à bit (seule vraie inconnue résiduelle : l'ordre
des clés/éléments, non contractuel de toute façon).
