# Campagne e2e — imedge (nuit)

Tu écris, cette nuit, les tests de bout en bout d'imedge à partir du catalogue
`e2e-scenarios.md` (racine du dépôt). Tu travailles seul, personne ne répondra à
une question : quand un choix se présente, prends le plus prudent, applique-le,
et consigne-le dans le rapport.

## Les deux documents qui font foi

- `CLAUDE.md` — le contrat de forme. Il s'applique ENTIER, à UNE exception près,
  posée par le catalogue lui-même : dans la couche e2e, `@QuarkusTest` est
  légitime et la base H2 vit. C'est la seule entorse. Tout le reste tient :
  Javadoc sur CHAQUE méthode (tests, helpers privés, méthodes de profil
  compris), code et commentaires en anglais, `org.junit.jupiter.api.Assertions`
  seulement, jamais AssertJ, aucune ligne vide dans un corps de méthode.
- `e2e-scenarios.md` — le QUOI. 121 fonctionnalités (A1 à R8) réparties en
  18 groupes, chacune en table *Fonction / Cas / Attendu*. Une ligne de table =
  un `@Test`. Les attendus sont des codes HTTP et des messages EXACTS : on les
  assertit à la lettre.

## Convention d'implémentation (celle du catalogue, non négociable)

- Une classe par groupe : `Group<X>IT` dans `com.intermarche.e2e`
  (`src/test/java/com/intermarche/e2e/`). Pas de sous-paquet.
- Un `@Test` par ligne de table. Nom de méthode préfixé par l'identifiant :
  `f3_unknownFeedCodeAnswers404()`. La Javadoc cite l'identifiant et recopie
  le cas et l'attendu du catalogue.
- Les profils de test (`QuarkusTestProfile`) et UN SEUL utilitaire partagé
  (`E2eSupport` : connexion, jeton, fabriques de charges) vivent dans ce même
  paquet. Pas de classe utilitaire par groupe, pas de paquet `support`.
- Pas de navigateur, pas de Playwright (`CLAUDE.md`). Une page HTML se teste
  par HTTP : code, en-tête `Location`, et présence dans le corps du texte
  attendu.
- RestAssured est permis pour émettre la requête et lire code et en-têtes
  (`then().statusCode(…)`, comme `SecurityExtensionLivenessIT`). Le contenu du
  corps s'extrait (`extract().asString()`, `jsonPath()`) puis s'assertit avec
  `Assertions`.

## Lancement

Un seul groupe à la fois :

```
mvn -q verify -DskipUTs=true -DskipITs=false -DskipJsTests=true -Djacoco.skip=true -Dit.test=GroupFIT
```

Toute la couche e2e, en fin de groupe :

```
mvn -q verify -DskipUTs=true -DskipITs=false -DskipJsTests=true -Djacoco.skip=true
```

Après chaque lancement, lire dans `target/failsafe-reports` le nombre de tests
EXÉCUTÉS. Un total qui ne bouge pas après l'ajout de méthodes signifie que rien
n'a tourné — c'est déjà arrivé sur ce projet. Ce n'est pas un succès.

## Montage — ce que tu as, et ce que tu fabriques

Lire d'abord `SecurityExtensionLivenessIT` (le patron existant),
`application.properties` et `DataInitializer`.

- **Le jeu de données.** `DataInitializer` est `@IfBuildProfile(dev, test)` : il
  tourne sous `@QuarkusTest`. Comptes semés : `admin`/`admin` (ADMIN),
  `mcurie` (MANAGER, lire son mot de passe dans le fichier), `aeinstein`
  (PICKER), `jdupont` (CASHIER, sans back-office). Un magasin existe. S'en
  servir, ne pas le re-semer.
- **La base est partagée.** Quarkus garde la même application, donc la même
  H2 en mémoire, pour toutes les classes d'un même profil. Chaque test crée ses
  propres données sous des clés uniques (suffixe `UUID`) et n'assertit jamais un
  compte global de lignes — seulement ce qu'il a lui-même posé. Aucun test ne
  dépend de l'ordre d'exécution. Un test qui modifie un réglage partagé le
  remet en place dans un `finally`.
- **Le rôle et le jeton** se posent par `QuarkusTestProfile.getConfigOverrides()`,
  JAMAIS par un `src/test/resources/application.properties` (il masquerait
  toute la configuration principale). Profils attendus, pas davantage :
  store sans jeton (défaut), store avec `pos.sync.token`, `central`,
  `register`. Chaque profil redémarre l'application : ne pas en créer un par
  classe.
- **Les boucles de fond** (`pos.referential.pull-seconds`,
  `pos.valuation.feed-delivery-seconds`, `pos.sync.interval-seconds`) se
  règlent dans le profil pour ne pas interférer : désactivées, ou dirigées vers
  un bouchon.
- **L'authentification.** Pour le groupe A, et pour tout cas dont
  l'authentification EST le sujet (filtre de mot de passe imposé, session,
  déconnexion) : vraie connexion par `POST /j_security_check`, cookie
  récupéré. Ailleurs, `@TestSecurity` (déjà en dépendance) est permis.
- **[E]** — nœud seul. C'est la majorité du catalogue ; tout se fait.
- **[R]** — la caisse n'est qu'un CLIENT du nœud. On ne la démarre pas : le
  test joue la caisse en émettant lui-même les requêtes qu'elle émettrait
  (`/api/sync/**`, `/api/referential/**`, `/api/commands/**`), avec les charges
  du format de fil partagé (`service/sync/RefPayloads` et voisins). Ne jamais
  réécrire ce format à la main quand la classe existe.
- **[V]** — le moteur de valorisation se remplace par un bouchon HTTP du JDK
  (`com.sun.net.httpserver.HttpServer`, port libre) monté par un
  `QuarkusTestResourceLifecycleManager` qui pose `pos.valuation.url` et
  `pos.valuation.query-url`. Le bouchon enregistre ce qu'il reçoit ; le test
  l'assertit. Une attente asynchrone se fait par sondage borné (pas de 200 ms,
  plafond 10 s), jamais par un `Thread.sleep` fixe.
- **[C]** — même principe : la brique centrale vue du magasin est un bouchon
  qui sert `/api/referential/versions` et `/{domain}`, posé via
  `pos.sync.central-url`. Le rôle `central` lui-même se teste sur le nœud sous
  test, par le profil `central`. Ce qui exige DEUX imedge réels dialoguant
  n'est pas faisable dans une JVM : chaque cas de ce genre se rapporte en une
  ligne (« non automatisable ici : … »), il ne s'écrit pas.
- **Aucune dépendance nouvelle.** Ne pas toucher au `pom.xml` : pas de
  WireMock, pas de Testcontainers. Le JDK et ce qui est déjà déclaré suffisent.

## Le code fait foi — les divergences se rapportent, elles ne se tranchent pas

Le catalogue a été écrit par lecture du code, il peut se tromper.

- Si le test échoue, relire le code de la ressource avant toute chose.
- **Le catalogue se trompe sur le code** (message, code HTTP, ordre des
  gardes) : le test assertit ce que fait le code, et la divergence entre au
  rapport, identifiant et ligne à l'appui. Ne pas modifier `e2e-scenarios.md`.
- **Le code a un défaut réel** (une fuite, une garde absente, une donnée
  corrompue — pas un choix contre-intuitif, le catalogue en fige plusieurs
  exprès) : le test assertit le bon comportement et porte
  `@Disabled("BUG <id> : <une phrase>")`. Il ne s'assouplit pas pour passer.
- Les scénarios dont l'application SOUS TEST est la caisse appartiennent à
  impos (`DemoStoreIT`) : ne pas les écrire ici, les nommer au rapport.

## L'ordre — celui du catalogue

1. **F** — la porte d'import et ses onze délégations.
2. **C**, puis **D** — ingestion, service du référentiel.
3. **G**, puis **E** — supervision, brique centrale.
4. **J** — les rapports ; la concordance CSV/XLSX/PDF.
5. **A**, puis **B** — droits et rôles.
6. **R** — robustesse.
7. **H, I, K, L, M, N, O, P, Q**.

Un groupe se termine avant d'ouvrir le suivant. Un groupe bloqué (montage
impossible, application qui ne démarre pas sous le profil) se rapporte et on
passe au suivant : on ne reste pas coincé la nuit sur un groupe.

## Pour chaque groupe

1. Lire la section du catalogue, puis le code des ressources qu'elle nomme —
   et seulement lui.
2. Lister les lignes de table ; classer chacune : écrite / non automatisable
   ici (raison) / relève d'impos.
3. Écrire la classe, lancer le groupe seul, corriger jusqu'au vert (hors
   `@Disabled` BUG).
4. Lancer toute la couche e2e : un groupe ne doit pas casser les précédents
   (données partagées, profils).
5. Commiter le groupe (voir plus bas).

Un groupe dont aucune ligne n'est automatisable ici, ou déjà couverte, est
un succès les mains vides : il se rapporte, il ne s'invente pas de test pour
exister.

## Stabilité

Le jeu doit tenir dans la durée : stable sans être couplé à l'implémentation.

- Assertions sur le contrat observable (code, message exact, en-tête, effet
  relu par une autre route HTTP ou une requête GraphQL), pas sur la forme
  interne d'une page.
- Pas de dépendance à l'heure, au fuseau, au jour de la semaine : une date
  s'injecte ou se lit, elle ne se devine pas.
- Pas de port fixe (8070, 8090) dans un test : Quarkus test et les bouchons
  prennent un port libre.
- Faire tourner chaque classe DEUX fois de suite avant de la déclarer verte :
  un test qui ne passe qu'une fois (donnée déjà présente, clé non unique) est
  un test faux.

## Interdits

- Ne rien modifier sous `src/main`, ni `pom.xml`, ni `e2e-scenarios.md`, ni
  `CLAUDE.md`. Un obstacle se rapporte en une ligne.
- Pas de `src/test/resources/application.properties`.
- Ne toucher à aucun test existant (unitaire, JS, `SecurityExtensionLivenessIT`).
- Ne pas supprimer ni assouplir un test pour faire passer la couche.
- Pas de test sans assertion.
- L'arbre porte des modifications non commitées étrangères à la campagne
  (sources, `pom.xml`, `data/`). Les laisser telles quelles, ne jamais les
  indexer.
- Jamais de `git push`.

## Commits

Un commit par groupe terminé, et seulement tes fichiers :
`git add src/test/java/com/intermarche/e2e/<fichiers du groupe>` — jamais
`git add -A`, jamais `git add .`, jamais `commit -a`. Message :
`e2e: groupe <X> — <n> tests (<d> désactivés BUG)`.

## Rapport, à chaque groupe

```
Groupe <X> : lignes du catalogue <N>
  ecrites <n> | non automatisables ici <m> | relevent d'impos <k>
  tests executes : avant -> apres | verts <v> | @Disabled BUG <d>
  profils utilises : ...
  divergences catalogue/code : <id> ligne <cas> — catalogue dit … / code fait …
  bugs : <id> — <une phrase>
  obstacle src/main : ... (ou aucun)
  commit : <sha>
```

## Rapport final

Écrit dans `ia/e2e-campaign-rapport.md`, commité en dernier, et recopié en fin
de session :

1. Tableau par groupe : lignes du catalogue, écrites, non automatisables,
   relevant d'impos, désactivées BUG.
2. Total des tests e2e exécutés, avant (3) et après.
3. Liste des bugs, un par ligne, avec le test `@Disabled` qui les porte.
4. Liste des divergences catalogue/code, à reporter dans `e2e-scenarios.md`
   par un humain.
5. Groupes non commencés ou bloqués, avec la raison.

## Critères d'acceptation

1. `mvn -q verify -DskipUTs=true -DskipITs=false` vert, hors `@Disabled` BUG
   nommés un par un.
2. Groupes F, C, D, G, E et J traités.
3. Chaque classe verte deux fois de suite.
4. Aucun fichier hors `src/test/java/com/intermarche/e2e/` et
   `ia/e2e-campaign-rapport.md` modifié par la campagne.
