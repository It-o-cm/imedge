# Campagne couverture — imedge (2e passe)

`CLAUDE.md` à la racine d'imedge est le contrat de forme. Sa règle la plus
structurante : le domaine, les charges de synchronisation et le back-office sont
une COPIE fichier pour fichier de ceux d'impos. On n'améliore jamais une classe
copiée d'un seul côté.

## Ce qu'une première passe a déjà fait, et ce qu'elle a manqué

Quinze classes de test ont été créées aujourd'hui : `AdminCampaignResourceTest`,
`SyncSupervisionResourceTest`, `SyncSupervisionServiceTest`,
`EngineFeedRelayResourceTest`, `ProductFamilyCsvResourceTest`,
`BudgetStatusClientTest`, `CampaignServiceTest`, `CampaignFeedProjectorTest`,
`EngineFeedDeliveryServiceTest`, `RefPullServiceErrorMapTest`,
`ScheduledDeliveryServiceTest`, `DataInitializerTest`, `CampaignStatusTest`,
`CampaignTest`.

**Et le compte de classes sans test n'a pas bougé : toujours 36 sur 231.** La
passe a enrichi ce qui était déjà couvert. Le vide est intact :

| Paquet | Classes sans aucun test |
|---|---|
| `ui/report` | **22** |
| `imports` | 2 |
| `domain/sync` | 2 |
| `domain/setting` | 2 |
| `domain/sale` | 2 |
| `domain/payment` | 2 |
| `service`, `domain/store`, `domain/session`, `domain/barcode` | 1 chacun |

Le rapport JaCoCo date de 18:03, avant les derniers ajouts. Le régénérer avant
toute chose :

```
mvn -q verify && mvn -q jacoco:report
```

Repère de la dernière mesure fiable : 5877 / 6633 branches, **88,6 %**, avec 756
branches manquantes dont 290 dans `ui.report`, 146 dans `ui.admin`, 108 dans
`imports`. Ne jamais estimer une couverture en lisant le code : la seule source
est `target/site/jacoco/jacoco.csv`.

## Étape 1 — rendre la suite verte AVANT d'ajouter quoi que ce soit

La première passe a laissé des échecs. Deux ont été corrigés à la main, les
corrections sont en place et servent de patron :

- `EngineFeedRelayResourceTest` : le `@BeforeEach` moquait onze collaborateurs
  sur douze. Corrigé par l'ajout de `egalimRegimeCsvResource`.
- `ProductFamilyCsvResourceTest`, deux méthodes sur le chemin de mise à jour :
  elles appelaient `processLineLogic` sans `mockStatic`. Corrigé par un
  `try (MockedStatic<PanacheEntityBase> …)` rendant la MÊME instance.

Relancer la classe entière et corriger ce qui reste, avec la même discipline :
ne pas supprimer un test pour faire passer la suite, ne pas assouplir une
assertion.

## Les deux pièges qui ont produit ces échecs

**Un collaborateur non moqué fait tomber des tests qui n'ont rien à voir.**
`EngineFeedRelayResource.delegates()` construit sa table entière à chaque appel,
avec des références de méthode liées (`storeCsvResource::importStores`). Une
référence liée sur un receveur nul lève à sa CRÉATION — le compilateur insère un
`Objects.requireNonNull` sur le receveur capturé. Donc un seul champ oublié fait
échouer les douze routes, avec une pile qui accuse toujours la même ligne.

Contrôle à passer sur toute classe à plusieurs `@Inject` :

```
grep -c "@Inject" <Classe>.java
grep -c "resource\..* = mock(" <Classe>Test.java
```

Les deux nombres doivent coïncider.

**Le chemin de mise à jour passe désormais par `findById`.** Les importeurs
réattachent l'entité avant de la muter — la reprise par paliers vide le contexte
de persistance entre deux essais, donc l'entité pré-chargée est détachée. Tout
test qui appelle `processLineLogic` avec l'entité POSÉE DANS LE CONTEXTE prend
cette branche et doit ouvrir un `mockStatic(PanacheEntityBase.class)` rendant la
**même instance** — sinon le test observe un objet que la production n'a pas
touché et ses assertions ne vérifient plus rien.

Un test à contrôler de ce chef :
`ProductCsvResourceTest.processLineLogicSkipsUpdateWhenChecksumMatches`. Il prend
a priori la branche « inchangé » qui n'atteint pas `findById`, mais il n'a pas
été vérifié.

## Étape 2 — l'ordre, et il n'est pas discutable

1. **`ui/report`** — 290 branches manquantes, 22 classes sans test. Les rapports
   sont des agrégations pures : peu de collaborateurs, beaucoup de branches de
   regroupement, de tri et de bornes. Meilleur rendement du dépôt, et c'est
   exactement ce que la première passe a évité.
2. **`imports`** — deux classes sans test, et le patron existe déjà dans le
   paquet pour les autres importeurs.
3. **`ui/admin`** — 146 branches, dominées par `AdminNomenclatureResource`
   (79 manquantes sur 356).
4. Le reste, par `BRANCH_MISSED` décroissant.

## Cinq classes se transposent depuis impos

`EgalimRegimeCsvResource`, `NomenclatureCsvResource`, `NomenclatureImportService`,
`TicketLineValuation`, `RoundingPayment` ont déjà une classe de test dans impos.
Le code étant copié fichier pour fichier, le test l'est aussi : transposer, ne
pas réinventer. Si la transposition ne compile pas, les deux copies ont divergé —
le dire en une ligne, c'est un défaut à part entière.

## La nullité — la première chose à couvrir, pas la dernière

Un rapport à 100 % de branches laisse passer une NullPointerException quand
chaque branche n'a été atteinte que par son bras non nul. Pour CHAQUE méthode,
passer cette liste en entier :

- **Chaque paramètre** à `null`, y compris ceux que la javadoc jure non nuls.
- **Chaque garde composée** : un cas par jambe VRAIE.
  `if (a == null || b == null || c.isEmpty())` réclame trois tests, plus celui où
  tout passe. Une seule jambe testée laisse les autres non exercées même quand
  JaCoCo affiche la ligne en vert.
- **Chaque ternaire**, ses deux bras séparément.
- **Chaque valeur rendue par un collaborateur moqué** : `null`,
  `Optional.empty()`, liste vide.
- **Chaîne, collection, tableau** : `null`, vide, et pour une chaîne, blanc.
- **Élément nul DANS une collection** que la méthode parcourt.
- **Champ d'entité non renseigné** : `checksum`, une date, un libellé — pas
  seulement une entité absente.
- **Nombre** : `null`, zéro et négatif sont trois cas.

Pour `ui/report` en particulier : une ligne sans montant, une session sans
caissier, un jour sans vente et un regroupement vide sont quatre cas distincts,
et ce sont eux qui cassent en production.

Le rapport de chaque classe chiffre les cas de nullité ajoutés.

## Autres pièges du dépôt

- Entités Panache non enhanced sous `mvn test` : `mockStatic(PanacheEntityBase.class)`
  pour les finders, `mockConstruction` pour neutraliser `persist()`.
- `BaseEntity.checksum` est un `Integer` : la comparaison passe par
  `changedFrom(int)`. Un `checksum != x` direct déballe et lève.
- `pos.role` vaut `store` dans `application.properties` mais `register` par
  défaut dans le code : un test qui lit le rôle doit le poser explicitement.
- `RefPullService` isole chaque domaine dans son propre try/catch et retient une
  erreur par domaine — les deux bras se testent.
- L'arbre porte des modifications non commitées étrangères à la campagne. Les
  laisser, ne pas les corriger, les nommer dans le rapport.

## Interdits

- Ne rien modifier sous `src/main`. Un obstacle à la testabilité se rapporte en
  une ligne et s'arrête là.
- Ne jamais « améliorer » une classe copiée d'impos d'un seul côté.
- Pas de `@QuarkusTest`, pas de H2, pas d'AssertJ, pas de démarrage
  d'application pour un test de classe.
- Pas de test sans vérification. Un test qui appelle la méthode et s'arrête n'est
  recevable que si aucun `mockStatic` n'est ouvert — toute entrée-sortie lèverait
  alors d'elle-même — ou si la propriété testée EST « ne lève pas » ; sa javadoc
  doit le dire.
- Ne pas supprimer ni assouplir un test pour faire passer la suite.
- Ne pas réécrire, renuméroter ni reformater une classe existante : on y ajoute.
- Jamais de `git push`.

## Branches qu'il ne faut PAS forcer

À rapporter plutôt qu'à couvrir : bras défensifs d'un `switch` sur une
énumération exhaustive, `catch` que le montage ne peut pas produire sans
instrumenter la production, code injoignable derrière une garde qu'une autre
garde rend impossible. Chacune se justifie en une phrase avec son numéro de ligne.

## Vérifier que le test a réellement tourné

Après le premier `mvn -Dtest=<Classe>Test test`, lire le nombre de tests
exécutés. **Un total inchangé après l'ajout de méthodes signifie que rien n'a
tourné** — c'est arrivé sur ce projet. Ce n'est pas un succès.

## Critères d'acceptation

1. `mvn -q verify` vert, hors les rouges pré-existants nommés un par un.
2. **Zéro classe sans test dans `ui/report`** — c'est le critère qui distingue
   cette passe de la précédente.
3. Branches globales au-dessus de **95 %** (départ 88,6 %).
4. Aucun fichier de `src/main` modifié.
5. Pour chaque classe à plusieurs `@Inject`, le compte des mocks du `@BeforeEach`
   égale le compte des `@Inject`.

## Rapport, à chaque classe

```
<Classe> : branches n/n (x %) -> m/m (y %)
tests ajoutes : k  dont cas de nullite : j
tests executes : avant -> apres
mocks au BeforeEach : k / <nb @Inject>
branches laissees : ligne L (raison)
divergence avec impos : ... (ou aucune)
obstacle src/main : ... (ou aucun)
```

Rapport final : couverture avant et après, classes de `ui/report` traitées,
branches laissées avec leur raison, divergences constatées entre imedge et impos.
