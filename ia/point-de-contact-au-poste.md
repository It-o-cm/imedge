# Faire descendre le point de contact au poste

Vingt-et-une lignes du questionnaire sont en partiel pour une seule raison : le
point de contact d'encaissement (BO-03-09, BO-03-10) est administré dans le back
office, la résolution à cinq échelons est écrite, la caisse se nomme déjà sur
chaque tirage — et rien ne transporte le résultat. Ce chantier ferme cet écart,
et rien d'autre.

Il porte sur **deux dépôts** : `imedge` (la descente) et `impos` (la réception).
Le contrat de fil du §2 est arbitré : il s'applique aux deux, et il ne se
rediscute pas d'un côté sans l'autre.

---

## 1. L'état exact des lieux

Ne le redécouvre pas, et ne recode pas ce qui existe.

**Dans `imedge` :**

- `domain/store/CheckoutPoint.java` existe depuis le 28 septembre. Onze
  attributs : `terminalId` (clé unique), `pdvNumber`, `islandCode`, `label`,
  `active`, `tpvType`, `technicalHostessBadge`, `cashierDisplayType`,
  `cashierDisplayResolution`, `customerDisplayType`,
  `customerDisplayResolution`, `menuTemplateCode`. Les écrans d'administration
  existent aussi (`ui/admin/AdminCheckoutPointResource`, `/save`, `/delete`).
- `service/EchelonSettingService.resolveForTerminal(pdvNumber, terminalId, asOf)`
  implémente déjà la chaîne complète COUNTRY → ENSEIGNE → PDV → ISLAND →
  CHECKOUT_POINT, l'île étant lue sur le point du terminal. **Personne ne
  l'appelle** : `PosSettingsService` ligne 676 et ligne 734 appellent tous deux
  `resolveForPdv`.
- `service/sync/RefPayloads.DOMAINS` compte seize domaines. Il n'y a pas de
  `CHECKOUT_POINTS`.
- `imports/RefExportResource` ne lit aucun en-tête de terminal.
- `service/sync/store/RefExportService.toDto(CheckoutIsland)` dénormalise la
  liste `terminalIds` depuis `CheckoutPoint` pour que le fil reste identique
  octet pour octet.

**Dans `impos` :**

- `service/sync/RefPullService` ligne 430 envoie déjà l'en-tête
  `X-Pos-Terminal` sur chaque requête de tirage, et sa Javadoc dit pourquoi :
  « un nœud plus ancien ignore un en-tête qu'il ne connaît pas ». **La moitié
  caisse de la poignée de main est faite.**
- Il n'y a pas d'entité `CheckoutPoint`. `CheckoutIsland` est là, avec sa liste
  `terminalIds`, et `CouponCheckService` ligne 115 retrouve l'île d'un terminal
  en balayant cette liste à l'envers.

Le chantier est donc exactement : brancher l'en-tête, résoudre par terminal,
ajouter le domaine, le recevoir, et le faire lire.

---

## 2. Le contrat de fil — arbitré

### A · L'en-tête `X-Pos-Terminal`

Toute requête `GET /api/referential/*` peut le porter.

- **Absent ou vide** : le comportement d'aujourd'hui, à l'identique — résolution
  pour le PDV du nœud. C'est ce qui fait qu'une caisse non mise à jour continue
  de fonctionner contre un nœud qui a bougé.
- **Présent** : le nœud résout pour ce terminal.

Ce n'est **pas** une authentification : l'en-tête nomme, il ne prouve rien — le
jeton `pos.sync.token` et la garde `pos.role` restent seuls juges, et tu n'y
touches pas. Un identifiant de terminal inconnu n'est donc **pas une erreur** :
il se résout comme le PDV, plus ce qui serait posé au niveau CHECKOUT_POINT sur
cet identifiant, c'est-à-dire normalement rien. Jamais de 4xx sur un terminal
inconnu.

### B · SETTINGS résolu par terminal

- `PosSettingsService` reçoit une surcharge
  `administeredValues(String terminalId)`. La méthode sans argument garde son
  sens — « le PDV de ce nœud » — et garde ses appelants.
- La résolution des échelons y reste **fraîche**, jamais mise en cache : c'est
  déjà la règle, et c'est elle qui fait qu'une date d'effet échue s'applique
  toute seule (BO-03-12-03/04).
- `RefExportService.settingsPage` prend le terminal et le passe à la résolution.
- **L'empreinte de SETTINGS doit être calculée par terminal.** C'est le piège de
  tout le lot : aujourd'hui `fingerprintCache` et `fingerprintCachedAt` sont
  indexés par domaine seul. Si SETTINGS reste indexé ainsi, la caisse B s'entend
  répondre « rien n'a changé » parce que la caisse A vient de réchauffer le
  cache avec *sa* résolution — la descente serait silencieusement fausse, et le
  symptôme (un poste qui prend les paramètres d'un autre) est de ceux qu'on
  met trois jours à comprendre. La clé devient `domaine` pour les domaines
  globaux, et `domaine + '@' + terminal` pour SETTINGS ; un terminal nul retombe
  sur la clé nue.
- L'invariant tient : **une caisse ne reçoit jamais la chaîne des échelons**,
  seulement le résultat. La Javadoc de `RefPayloads.ECHELON_DOMAINS` le dit ;
  elle continue de dire vrai.

### C · Le domaine `CHECKOUT_POINTS`

- Ajouté à `RefPayloads.DOMAINS`, **juste après `ISLANDS`** : un point nomme son
  île par code, l'inverse n'est pas vrai, et la caisse lit ainsi un graphe
  complet en un seul cycle.
- `RefPayloads.CheckoutPointDto` porte les douze champs de l'entité, aux mêmes
  noms. Les énumérations voyagent par leur **nom** en texte, comme toutes les
  autres de ce fil, jamais par leur rang.
- L'instantané est **global**, non filtré par terminal : la caisse reçoit tous
  les points de son magasin. C'est voulu — l'hôtesse technique supervise les
  points libre-service voisins, la clôture distante nomme une autre caisse, la
  composition de l'île se lit là. L'empreinte de ce domaine reste donc globale,
  et le §B ne le concerne pas.
- `canonical(row)` et le DTO évoluent ensemble : l'invariant de maintenance écrit
  en tête de `RefExportService` s'applique au nouveau domaine comme aux autres.
- **`ISLANDS` ne change pas.** Sa liste `terminalIds` dénormalisée reste sur le
  fil octet pour octet. Le point est la vérité, mais unifier les deux n'est pas
  ce lot-ci.

### D · Côté caisse

- Une entité miroir `CheckoutPoint` dans `com.intermarche.pos.domain.store` —
  mêmes colonnes, mêmes noms, et seulement les recherches dont la caisse a
  besoin. Les énumérations `TpvType` et `DisplayType` descendent avec elle.
- `RefApplyService.applyCheckoutPoints`, upsert par `terminalId`, avec le même
  traitement des lignes disparues en amont que `applyCheckoutIslands` : recopie
  sa forme, ne l'invente pas.
- `case "CHECKOUT_POINTS" ->` dans `RefPullService`, à sa place dans l'ordre
  d'application.

---

## 3. Ce que la caisse en fait

Un objet qui descend et que personne ne lit ne vaut rien. Mais on ne lit pas la
table en onze endroits : **un seul point d'accès**,
`CheckoutPointService` dans `com.intermarche.pos.service` — il n'injecte pas
`PosState` et ne sert pas un flux d'écran, sa place est là. Il expose le point de
*ce* poste, résolu depuis `pos.terminal.id`, et null quand le nœud ne l'a pas
encore envoyé. Aucun autre lecteur de la table ailleurs.

**Ce qui se branche maintenant, parce que le crochet existe :**

- `active == false` → la prise de poste est **refusée**, avec un message
  explicite (BO-03-09-01). Un point désactivé qui fonctionne quand même n'est pas
  une désactivation. Le message entre dans l'inventaire existant, groupe O-L de
  `e2e-scenarios.md`, jamais dans un satellite.
- `islandCode` → `CouponCheckService` ligne 115 lit l'île sur le point quand le
  point est connu, et garde le balayage inverse de `terminalIds` en repli pour un
  nœud qui n'envoie pas encore le domaine. Deux chemins, le second nommé comme
  repli dans la Javadoc.
- `customerDisplayType == NONE` → un poste déclaré sans afficheur client ne doit
  plus être compté en défaut parce qu'il n'interroge pas ; `customerDisplaySeenAt`
  et sa lecture dans `HardwareService` existent déjà. C'est tout : aucun
  changement de rendu.

**Ce qui devient disponible et que tu ne branches pas ici** — tu le signales en
une ligne à la fin plutôt que d'inventer un consommateur : `tpvType`,
`technicalHostessBadge`, les deux résolutions d'afficheur, `menuTemplateCode`.
La bibliothèque des modèles de menu est le chantier 03-03/03-04 ; les résolutions
attendent un kiosque qui les lise ; l'hôtesse technique attend le flux
libre-service. On pose la descente, pas les écrans.

---

## 4. Portée

- Tu ne traites que les §2 et §3. Un refactoring rencontré en chemin se signale
  en une ligne ; il ne se fait pas.
- Tu ne touches ni à la garde `pos.role`, ni au jeton, ni au domaine `ISLANDS`,
  ni à la sémantique de `administeredValues()` sans argument.
- Javadoc sur **toutes** les méthodes, sans exception : accesseurs,
  constructeurs, méthodes privées, redéfinitions, méthodes de test comprises.
  Tout le code et tous les commentaires en anglais.
- Le contrat se documente là où ce fil a toujours été documenté : la Javadoc de
  `RefPayloads`, celle du nouveau DTO, celle de `RefExportService`, plus le
  paragraphe du tirage référentiel **dans** `spec-diffusion-supervision.md`.
  Jamais un fichier satellite.
- Git : un commit par dépôt, sur la branche courante. Jamais de branche, jamais
  de push.

## 5. Tests

JUnit 5 + Mockito nu, selon `CLAUDE.md`. Jamais `@QuarkusTest`, jamais H2 pour un
test de classe. **Chaque jambe de chaque expression booléenne composée a son cas
de test**, nullité comprise. Sur ce lot, au minimum :

- en-tête absent, en-tête vide, en-tête d'un terminal inconnu, d'un terminal sans
  île, d'un terminal avec île ;
- une clé posée à la fois au niveau ISLAND et au niveau CHECKOUT_POINT : c'est le
  plus spécifique qui gagne ; la même clé posée à PDV et à ISLAND ;
- une date d'effet encore future posée au niveau ISLAND, puis au niveau
  CHECKOUT_POINT : ignorée jusqu'au jour dit ;
- **l'empreinte** : deux terminaux dans la même fenêtre de TTL obtiennent chacun
  son empreinte SETTINGS ; un domaine global n'en devient pas dépendant du
  terminal ; un terminal nul retrouve la clé nue ;
- `active` vrai et faux, `islandCode` nul et connu, chaque énumération nulle et
  posée, `customerDisplayType` NONE et autre ;
- l'application : création, mise à jour, point disparu en amont.

Le point 2 du §3 mérite le test qui aurait attrapé l'écart : un terminal dont
l'île n'est atteignable que par le point, et pas par la liste de l'île.

E2E : dans `imedge`, groupes **D** (service du référentiel aux caisses) et **M**
(paramétrage) ; dans `impos`, groupe **L** (consolidation magasin) et **A** pour
la prise de poste refusée. Dans les fichiers existants, dans le groupe qui
correspond.

`mvn verify` vert dans les deux dépôts avant de rendre.

## 6. Ce que tu rends

Par dépôt : ce qui a été fait, les fichiers touchés, le verdict de `mvn verify`.

À la fin : les lignes BO-03-09 / BO-03-10 que la descente sert désormais et
celles qui restent en partiel avec la raison, ce qui descend sans consommateur,
et — en une ligne chacune — les questions ouvertes et les améliorations hors
périmètre repérées en chemin.
