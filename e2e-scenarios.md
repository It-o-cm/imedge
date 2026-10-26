# Catalogue des scénarios e2e — imedge (nœud magasin & brique centrale)

Couche complémentaire des tests unitaires : ici l'application démarre pour de
vrai, la base H2 vit, et les échanges passent par HTTP.

imedge ne vend rien. Il agrège ce que les caisses poussent, tient et sert le
référentiel, porte le back-office, supervise la ligne et sort les rapports. Un
seul exécutable prend le rôle `register`, `store` ou `central` selon
`pos.role` — et le rôle décide de tout ce qui suit.

## Infrastructure par scénario

- **[E]** nœud seul, en rôle `store`, base H2, aucune caisse.
- **[R]** + une caisse (impos) qui pousse ses ventes et tire son référentiel.
- **[C]** + une brique centrale, c'est-à-dire un second imedge en `pos.role=central`.
- **[V]** + le moteur de valorisation sur :8090, pour la livraison des flux.

`CLAUDE.md` pose une frontière qui tient toujours : quand l'application SOUS
TEST est la caisse, le scénario appartient au catalogue d'impos (groupe L,
`DemoStoreIT`). Ce catalogue-ci ne couvre que les parcours dont imedge est le
sujet. Les scénarios [R] y figurent parce que la caisse n'y est qu'un client du
nœud — jamais l'objet de la vérification.

Convention : chaque scénario = parcours → attendus vérifiables (écran, base,
réponse HTTP, fichier produit, journal).

---

## A. Authentification, profils et droits

**A1 Amorçage du premier administrateur** `EmployeeBootstrap` (démarrage) [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `onStart` (StartupEvent) | Base vide + `pos.bootstrap.admin.password` non vide | Compte ADMIN créé : badge `00000000`, login `pos.bootstrap.admin.username` (déf. `admin`), email déf. `admin@pos.local`, `active=true`, `mustChangePassword=true` ; log WARN ; connexion possible mais confinée au changement de mot de passe |
| `onStart` | Base vide + password absent ou blanc | Aucun compte créé ; log ERROR nommant `POS_ADMIN_PASSWORD` (« NOBODY can sign in on this node ») ; personne ne peut se connecter |
| `onStart` | `Employee.count() > 0` (au moins un compte) + password configuré | Aucune création (court-circuit `count>0`) ; un mot de passe déjà changé n'est jamais réinitialisé |
| `onStart` | Relance sur base déjà amorcée | Idempotent : aucun second compte, table intacte |

**A2 Routage d'entrée du nœud** `GET /` · `GET /admin` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /` (NodeHomeResource) | Quelconque (surface ouverte) | 303 `/admin` (jamais 404) |
| `GET /admin` (AdminSettingsResource, `@Authenticated`) | Anonyme | 302 `/admin/login` (form auth) |
| `GET /admin` | Compte ADMIN | 303 `/admin/settings` |
| `GET /admin` | Compte authentifié non-ADMIN | 303 `/dashboard` |

**A3 Connexion / déconnexion** `/admin/login` · `/j_security_check` · `/admin/logout` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /admin/login` (`@PermitAll`) | Anonyme ou déjà connecté | 200 page de connexion (accessible même authentifié) |
| `GET /admin/login?error` | `error` présent | 200 ; message EXACT « Identifiant ou code incorrect, ou compte désactivé ou verrouillé. » ; ne nomme jamais lequel des deux champs |
| `GET /admin/login?notice=…` | `notice` présent | 200 ; la notice one-shot est affichée telle quelle |
| `POST /j_security_check` (form auth Quarkus) | Identifiants valides | Session posée (cookie `quarkus-credential`), 302 vers l'accueil |
| `POST /j_security_check` | Identifiants faux / compte désactivé / verrouillé | 302 `/admin/login?error` ; aucune session |
| `POST /admin/logout` (`@Authenticated`) | Authentifié | 303 `/admin/login?notice=Vous êtes déconnecté.` + cookie `quarkus-credential` vidé (`maxAge=0`, `httpOnly`, `path=/`) |
| `POST /admin/logout` | Anonyme | 302 `/admin/login` (garde `@Authenticated`) |

**A4 Filtre de mot de passe imposé** `PasswordChangeFilter` (AUTHORIZATION+10) [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `filter` | Authentifié, `mustChangePassword=true`, GET d'une page `/admin` ou `/dashboard` (hors exclusions) | Requête avortée → 303 `/admin/password` |
| `filter` | Pages exclues `/admin/password`, `/admin/logout`, `/admin/login` | Non redirigées (sinon boucle) |
| `filter` | Requête portant un en-tête `Authorization` (HTTP Basic, client machine) | Non redirigée quel que soit le chemin — c'est ce qui garde `/dashboard-data` lisible par une machine |
| `filter` | Surfaces machine hors préfixes gardés (`/api/**`, `/feeds/**`, `/graphql`) | Jamais redirigées (l'API répond normalement) |
| `filter` | `mustChangePassword` absent ou `false` | Aucune redirection |
| `filter` | Anonyme | Le filtre passe la main (la garde de rôle refuse avant, sur le rôle plutôt que sur le mot de passe) |

**A5 Changement de mot de passe** `/admin/password` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /admin/password` (`@Authenticated`) | Authentifié | 200 page ; drapeau `imposed` selon l'attribut `mustChangePassword` |
| `GET /admin/password` | Anonyme | 302 `/admin/login` |
| `POST /admin/password` | `renewed` null ou < 8 caractères | 303 `/admin/password?error=Le nouveau mot de passe doit compter au moins 8 caractères.` |
| `POST /admin/password` | `renewed` ≠ `confirmation` | 303 error « Les deux saisies du nouveau mot de passe diffèrent. » |
| `POST /admin/password` | `renewed` == `current` | 303 error « Le nouveau mot de passe doit être différent de l'actuel. » |
| `POST /admin/password` | `current` faux OU employé introuvable | 303 error « Mot de passe actuel incorrect. » ; rien modifié |
| `POST /admin/password` | Succès | Mot de passe remplacé, `mustChangePassword=false`, event `PASSWORD_CHANGED` (par badge), cookie vidé (`maxAge=0`), 303 `/admin/login?notice=Mot de passe modifié. Reconnectez-vous.` |

**A6 Profils** `/admin/profiles` (PermissionService · `Feature.PROFILE`) [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Accès — anonyme | Toute route | 302 `/admin/login` (`@Authenticated`) |
| Accès — non-ADMIN sans droit PROFILE | Toute route | 303 `/admin?notice=Droit refusé : gestion des profils.&noticeOk=false` (jamais 403) |
| Accès — ADMIN (superuser) ou porteur du grant | Toute route | Autorisé (ADMIN bypasse le lookup de profils) |
| `GET /` (list) | Droit VIEW | 200 liste des profils ordonnés + notice one-shot |
| `GET /new` | Droit VIEW | 200 formulaire vierge (toutes cases décochées) |
| `GET /edit?id` | id null ou profil inconnu | 303 `/admin/profiles?notice=Profil introuvable.&noticeOk=false` |
| `GET /edit?id` | Profil existant, droit VIEW | 200 formulaire reflétant les grants |
| `POST /` (save, création) | Droit CREATE ; `name` vide | 303 « Le nom du profil est obligatoire. » (`noticeOk=false`) |
| `POST /` (save) | Nom déjà pris par un autre profil | 303 « Un profil porte déjà le nom « X ». » |
| `POST /` (save, création) | Nom libre | Profil persisté avec ses grants (grid feature×option, menu-grants dépliés, dédup, termes de validation) ; 303 « Profil « X » enregistré. » (`noticeOk=true`) |
| `POST /` (save, édition) | id posé mais profil introuvable | 303 « Profil introuvable. » |
| `POST /delete` | Droit DELETE ; id null/blanc/inconnu | 303 « Profil introuvable. » |
| `POST /delete` | Profil existant | Bindings `EmployeeProfile` du profil supprimés puis profil supprimé ; 303 « Profil « X » supprimé. » |

**A7 Affectation des profils aux comptes** `/admin/users` (PermissionService · `Feature.USER`) [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Accès — anonyme | Toute route | 302 `/admin/login` |
| Accès — non-ADMIN sans droit USER | Toute route | 303 `/admin?notice=Droit refusé : affectation des profils.&noticeOk=false` |
| Accès — ADMIN ou porteur du grant | Toute route | Autorisé |
| `GET /` (list) | Droit VIEW | 200 : chaque employé avec ses bindings (profil + échelon), profil supprimé → « (profil supprimé) » |
| `POST /assign` | Droit UPDATE ; `employeeId` ou `profileId` null/illisible | 303 « Utilisateur et profil sont obligatoires. » |
| `POST /assign` | Employé ou profil introuvable | 303 « Utilisateur ou profil introuvable. » |
| `POST /assign` | Binding (employé, profil, échelon) déjà existant | 303 « Ce profil est déjà affecté à cet échelon. » |
| `POST /assign` | Nouveau binding (échelon vide → `GLOBAL`) | Binding persisté ; droits actifs immédiatement, sans reconnexion ; 303 « Profil « X » affecté à <nom complet>. » |
| `POST /unassign` | Droit DELETE ; `bindingId` null ou binding inconnu | 303 « Affectation introuvable. » |
| `POST /unassign` | Binding existant | Binding supprimé ; 303 « Affectation retirée. » |

**A8 Favoris d'affichage** `/admin/favorites` (`@Authenticated`, sans droit dédié) [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Accès — anonyme | Toute route | 302 `/admin/login` |
| Accès — tout compte authentifié | Toute route | Autorisé (chacun administre ses propres favoris) |
| `GET /` (page) | Employé courant introuvable | 303 `/admin/favorites?notice=Compte introuvable.&noticeOk=false` |
| `GET /` (page) | Employé résolu | 200 : uniquement les features VIEW-ables (PermissionService), état masqué/visible par ligne, groupées par menu |
| `POST /` (save) | Employé courant introuvable | 303 « Compte introuvable. » |
| `POST /` (save) | Sélection de clés à masquer | Set réécrit (clear puis persist) ; seules les features VIEW-ables sont honorées, une clé forgée/inconnue est ignorée (n'élargit ni ne restreint les droits) ; 303 « Préférences d'affichage enregistrées. » |

**A9 Fiche salarié (paie)** `/admin/employees` (`@RolesAllowed("ADMIN")`) [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Accès — anonyme | Toute route | 302 `/admin/login` (form auth) |
| Accès — authentifié non-ADMIN | Toute route | 403 |
| `GET /` (list) | Filtre `text` (nom/prénom/login, insensible casse) + `page` | 200 liste paginée triée par nom ; filtre sans résultat → état vide propre |
| `POST /` (save) | `id` null ou salarié introuvable | 303 « Salarié inconnu. » (`noticeOk=false`) |
| `POST /` (save) | `advanceLimit` négatif | 303 « Le plafond d'acompte ne peut pas être négatif. » |
| `POST /` (save) | `forcingLevel` négatif | Ramené à 0 (`Math.max(...,0)`), enregistrement accepté |
| `POST /` (save) | Colonnes paie valides (`legalEntity`, `forcingLevel`, `advanceLimit`) | Seules ces colonnes écrites (login/mot de passe/badge/rôle intouchés) ; 303 « Fiche salarié enregistrée. » (`noticeOk=true`) |

---

## B. Rôle du nœud — garde `pos.role` et jeton `pos.sync.token`

Rappel de gate (identique aux quatre surfaces) : la **garde de rôle est évaluée AVANT le jeton** (un nœud hors rôle avec mauvais jeton reçoit 403, pas 401) ; le jeton n'est exigé que si `pos.sync.token` est configuré non vide — absent/blanc ⇒ aucune authentification (mode démo).

**B1 Ingestion des remontées de caisse** `/api/sync/**` (rôle `store`) [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Toutes routes `/api/sync/*` | `pos.role` ≠ `store` (register par défaut, ou central) | 403 « Ce nœud n'a pas le rôle store » |
| Toutes routes | Jeton configuré, en-tête `X-Sync-Token` absent ou faux | 401 « Jeton de synchronisation invalide » |
| Toutes routes | Jeton configuré, `X-Sync-Token` correct | Passe la garde |
| Toutes routes | Jeton absent de la config | Aucune authentification exigée |
| `POST /session|/ticket|/movement|/refund|/event|/customer|/document|/balance-ticket` | Rôle store + jeton bon + payload valide | 200 « OK » (upsert) |
| Idem | Échec de résolution métier (`IllegalStateException`, entité référencée absente) | 409 rejouable, corps = message de l'exception (register conserve dans l'outbox et retente) |
| Idem | Autre exception | 500, corps = message |
| `GET /balance-ticket/{reference}` | Hors rôle store | 403 « Ce nœud n'a pas le rôle store » |
| `GET /balance-ticket/{reference}` | Rôle store, référence inconnue ou déjà consommée | 404 (le GET consomme : 2ᵉ scan → 404) |
| `GET /balance-ticket/{reference}` | Rôle store, référence servie | 200 JSON du ticket |
| `GET /ticket/{n}/duplicata` | Hors rôle store | 403 « Ce nœud n'a pas le rôle store » |
| `GET /ticket/{n}/duplicata` | Ticket inconnu du magasin | 404 « Ticket inconnu du magasin » |
| `GET /ticket/{n}/duplicata` | Ticket connu | 200 duplicata rendu (text/plain) |

**B2 Export du référentiel** `/api/referential/**` (rôle `store` ou `central`) [E] [R] [C]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /versions` et `GET /{domain}` | `pos.role` = `register` (ni store ni central) | 403 « Ce nœud n'a pas le rôle store ou central » |
| Idem | Jeton configuré, `X-Sync-Token` absent/faux | 401 « Jeton invalide » |
| Idem | Jeton configuré correct, ou jeton absent de la config | Passe la garde |
| `GET /versions` | Rôle `store` | 200 : empreintes des **17 domaines MAGASIN** (`ENGINE_FEEDS, PRODUCTS, FAMILIES, TOUCH_GROUPS, VAT_RATES, PRICES, EMPLOYEES, COUPON_TYPES, ARTICLE_RANGES, ISLANDS, CHECKOUT_POINTS, TENDERS, DOCUMENT_TEMPLATES, SETTINGS, CUSTOMERS, CURRENCIES, POSTAL_CODES`) |
| `GET /versions` | Rôle `central` | 200 : empreintes des **4 domaines d'ÉCHELON** (`COUNTRIES, ENSEIGNES, PDVS, ECHELON_SETTINGS`) — listes disjointes des domaines magasin |
| `GET /{domain}?page&size` | Domaine inconnu (`getPage` lève `IllegalArgumentException`) | 400, corps = message |
| `GET /{domain}?page&size` | Domaine valide (`size≤0` ou `>5000` → 1000 ; `page` < 0 → 0) | 200 page de snapshot |

**B3 Ordres de caisse** `/api/commands/**` (interdit au rôle `register`) [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /{terminalId}` et `POST /{commandUid}/ack` | `pos.role` = `register` | 403 `{"error":"Ce nœud ne distribue pas d'ordres"}` (store ET central passent la garde) |
| Idem | Jeton configuré, `X-Sync-Token` absent/faux | 401 `{"error":"Jeton de synchronisation invalide"}` |
| Idem | Jeton correct ou non configuré | Passe la garde |
| `GET /{terminalId}` | Rôle store/central, jeton bon | 200 tableau JSON des ordres non acquittés (`uid`, `type`, `issuedBy`, `reason`) |
| `POST /{commandUid}/ack` | Ordre stampé | 200 `{"acknowledged":true}` |
| `POST /{commandUid}/ack` | Ordre inconnu ou déjà acquitté | 404 `{"acknowledged":false}` |

**B4 Appel superviseur** `/api/supervisor/call` (`@PermitAll`, rôle `store`) [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `POST /api/supervisor/call` | `pos.role` ≠ `store` | 403 « Ce nœud n'a pas le rôle store » |
| `POST /api/supervisor/call` | Jeton configuré, `X-Sync-Token` absent/faux | 401 « Jeton invalide » |
| `POST /api/supervisor/call` | Rôle store + jeton bon (ou non configuré) | 200 « OK » ; l'appel (terminal, opérateur, motif) est enregistré au registre → bandeau dashboard |

---

Notes de vérification (lecture du code, pour le relais) :
- Fichiers lus : `ui/admin/AdminAuthResource.java`, `AdminProfileResource.java`, `AdminUserProfileResource.java`, `AdminFavoriteResource.java`, `AdminEmployeeResource.java`, `AdminSettingsResource.java` (routage `/admin`), `ui/NodeHomeResource.java`, `security/EmployeeBootstrap.java`, `security/PasswordChangeFilter.java`, `service/PermissionService.java`, `imports/SyncIngestResource.java`, `imports/RefExportResource.java`, `imports/RegisterCommandResource.java`, `ui/dashboard/DashboardResource.java`, `service/sync/RefPayloads.java`.
- Deux subtilités relevées dans le code (contre-intuitives, donc figées telles quelles) : la garde de `/api/commands/**` interdit uniquement le rôle `register` — un nœud `central` distribue donc des ordres ; et les messages de jeton diffèrent d'une surface à l'autre (« Jeton de synchronisation invalide » pour sync/commands, « Jeton invalide » pour referential/supervisor).
- Je n'ai pas modifié le fichier `e2e-scenarios.md` : la consigne demandait de « rendre uniquement le markdown ». À intégrer par copier-coller en remplacement des groupes A et B existants.

## C. Ingestion des remontées de caisse [E] [R]

`imports/SyncIngestResource` + `service/sync/store/SyncIngestService`. Garde commune des POST (méthode `handle`) : hors rôle → **403** « Ce nœud n'a pas le rôle store » ; `pos.sync.token` configuré non vide et `X-Sync-Token` discordant → **401** « Jeton de synchronisation invalide » ; jeton absent de la config → aucune auth. Succès → **200** corps `OK`. `IllegalStateException` → **409** (corps = message) ; toute autre exception → **500** (corps = message). Les deux GET répliquent la même garde en ligne (mêmes messages).

**C1 Session poussée** `POST /api/sync/session` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Garde rôle | nœud register/central | 403 « Ce nœud n'a pas le rôle store » |
| Garde jeton | jeton exigé, en-tête faux/absent | 401 « Jeton de synchronisation invalide » |
| Upsert | session inédite (clé `sessionNumber`) | 200 `OK`, `CashSession` créée |
| Idempotence | même corps rejoué | 200 `OK`, `find("sessionNumber")` → même ligne, zéro doublon |
| Caissier ouverture inconnu | `openingCashierLogin` absent du nœud | 409 « Employé inconnu sur le nœud magasin: <login> » |
| Caissier clôture inconnu | `closingCashierLogin` non nul et inconnu | 409 « Employé inconnu sur le nœud magasin: <login> » |
| Tolérance null | `closingCashierLogin` null | accepté, `closingCashier=null` |
| Tolérance null | `closingDate` null | accepté (`parse` tolère null) |
| Date d'ouverture exigée | `openingDate` null | 500 (`@NotNull` sur `CashSession.openingDate` ; le nœud n'invente pas l'heure d'ouverture) |
| Enum invalide | `status` hors `SessionStatus` | 500 (`valueOf`) |
| Corps illisible | JSON malformé | 400, rien en base |

**C2 Ticket poussé** `POST /api/sync/ticket` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Garde rôle / jeton | idem C1 | 403 / 401 |
| Upsert graphe | ticket inédit (clé `ticketNumber`) | 200 `OK`, ticket + lignes + règlements persistés |
| Idempotence | rejeu | remplacement en bloc (`lines.clear`, `payments.clear`, orphan removal) ; `previousCash` relu pour ne pas créditer le monnayeur 2× |
| Aucun magasin | nœud sans magasin (`storeCode` absent, `Store.findAll` vide) | 409 « Aucun magasin sur le nœud magasin » |
| Repli magasin | `storeCode` inconnu mais un magasin local existe | accepté, rattaché au magasin unique |
| Caissier inconnu | `cashierLogin` absent du nœud | 409 « Employé inconnu sur le nœud magasin: <login> » |
| Mode de paiement inconnu | `methodKey` sans factory (et pas de `voucherLabel`) | 409 « Mode de paiement inconnu: <methodKey> » |
| Ordre session (réel) | ticket avant sa session | 200 `OK`, `session=null` (lien non exigé — PAS de 409, contrairement à C3 du doc) |
| Session absente tolérée | `sessionNumber` null ou introuvable | `ticket.session=null` |
| Produit best-effort | résolution PLU puis EAN, sinon null | ligne conservée, `product` possiblement null |
| Modificateur inconnu | `modifierType` non reconnu | `PriceModType.of` → aucun geste, ticket consolidé |
| Contenu formaté | `formattedContent` null/blanc | ancien contenu conservé (non écrasé) |
| Ligne annulée | `cancelled=true` | ligne conservée marquée (témoin journal) |
| Enum invalide | `status`/`valuationStatus` hors enum | 500 (`valueOf`) |
| Corps illisible | JSON malformé | 400, rien en base |

**C3 Mouvement de caisse** `POST /api/sync/movement` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Garde rôle / jeton | idem | 403 / 401 |
| Upsert | mouvement inédit (clé `movementUid`) | 200 `OK`, `CashMovement` créé |
| Idempotence | rejeu | même ligne, mis à jour |
| Session tolérée | `sessionNumber` null ou introuvable | `session=null`, mouvement accepté |
| Caissier toléré | `cashierLogin` null | `cashier=null` |
| Caissier inconnu | `cashierLogin` non nul et inconnu | 409 « Employé inconnu sur le nœud magasin: <login> » |
| Enum invalide | `type` hors `MovementType` | 500 (`valueOf`) |
| Corps illisible | JSON malformé | 400 |

**C4 Remboursement** `POST /api/sync/refund` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Garde rôle / jeton | idem | 403 / 401 |
| Upsert | remboursement inédit (clé `refundNumber`), lié à `originalTicketId` | 200 `OK` |
| Idempotence | rejeu | même ligne, lignes reconstruites |
| Ticket d'origine absent | `originalTicketNumber` introuvable | 409 « Ticket d'origine absent: <num> » |
| Ligne d'origine absente | `originalLineUid` introuvable dans les lignes du ticket | 409 « Ligne d'origine absente: <uid> » |
| Ligne sans uid | `originalLineUid` null | 409 « Ligne d'origine absente: null » (toujours exigée) |
| Méthode tolérée | `refundMethod` null | `refundMethod=null` |
| Session tolérée | `sessionNumber` null/introuvable | `session=null` |
| Enum invalide | `status` hors `RefundStatus`, ou `refundMethod` non nul invalide | 500 (`valueOf`) |
| Corps illisible | JSON malformé | 400 |

**C5 Événement technique** `POST /api/sync/event` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Garde rôle / jeton | idem | 403 / 401 |
| Upsert | événement inédit (clé `eventUid`), horodaté, `operatorBadgeId` conservé | 200 `OK` |
| Idempotence | rejeu | même ligne mise à jour |
| Enum invalide | `type` hors `EventType` | 500 (`valueOf`) |
| Tolérance null | `detail`/`eventDate` null | accepté |
| Corps illisible | JSON malformé | 400 |

**C6 Client en compte** `POST /api/sync/customer` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Garde rôle / jeton | idem | 403 / 401 |
| Upsert | client inédit (clé `accountNumber`), adresse créée si null | 200 `OK` |
| Idempotence | rejeu / rejeu après édition | une seule ligne, champs mis à jour |
| Aucune référence | payload ne cite rien | ne park jamais (pas de 409) |
| Corps illisible | JSON malformé | 400 |

**C7 Document remonté** `POST /api/sync/document` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Garde rôle / jeton | idem | 403 / 401 |
| Upsert | document inédit (clé `documentNumber`), bloc client stocké tel que poussé | 200 `OK` |
| Type par défaut | `documentType` null | `DocumentType.FACTURE` |
| Enum invalide | `documentType` non nul invalide | 500 (`valueOf`) |
| Ticket non exigé | `ticketNumber` inconnu du nœud | `ticket=null`, `ticketNumber` conservé |
| Numéro de ticket exigé | `ticketNumber` null/blanc | 500 (`@NotBlank` sur `Invoice.ticketNumber` ; une facture nomme sa vente) |
| Client non exigé | `customerAccountNumber` null/introuvable | `customer=null` |
| Totaux null → 0 | `totalExcludingTax`/`IncludingTax`/`Vat`/`EcoTax` null | valent `ZERO` (colonnes obligatoires) |
| Date d'échéance illisible | `customerDueDate` non ISO | null (`parseDay` tolère) |
| Corps illisible | JSON malformé | 400 |

**C8 Ticket de balance déposé** `POST /api/sync/balance-ticket` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Garde rôle / jeton | idem | 403 / 401 |
| Upsert | ticket inédit (clé `reference`) | 200 `OK`, `BalanceTicket` + lignes |
| Idempotence | rejeu d'un ticket non consommé | mis à jour, lignes reconstruites |
| Déjà consommé | rejeu d'un ticket déjà servi | 200 `OK` mais poussée ignorée (return, aucune résurrection) |
| Émission null | `emittedAt` null | `LocalDateTime.now()` |
| Lignes null | `dto.lines` null | accepté, aucune ligne |
| Corps illisible | JSON malformé | 400 |

**C9 Ticket de balance relu** `GET /api/sync/balance-ticket/{reference}` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Garde rôle | hors rôle store | 403 « Ce nœud n'a pas le rôle store » |
| Garde jeton | jeton discordant | 401 « Jeton de synchronisation invalide » |
| Servir + consommer | référence tenue non consommée | 200 corps DTO ; `consumedAt`/`consumedByTerminal` posés (GET qui écrit) |
| Référence inconnue | aucune telle référence | 404 (corps vide) |
| Déjà servie | seconde lecture même papier | 404 (corps vide) |

**C10 Duplicata de ticket** `GET /api/sync/ticket/{ticketNumber}/duplicata` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Garde rôle | hors rôle store | 403 « Ce nœud n'a pas le rôle store » |
| Garde jeton | jeton discordant | 401 « Jeton de synchronisation invalide » |
| Rendu | ticket tenu | 200 texte rendu, **rien écrit, aucun compteur** (READ — contredit le C11 « compteur incrémenté » du doc) |
| Ticket inconnu | numéro introuvable | 404 « Ticket inconnu du magasin » |
| Numéro vide | `ticketNumber` null/blanc | `renderTicketDuplicate` → null → 404 « Ticket inconnu du magasin » |

---

**C11 L'état d'intégration des imports centraux (15/10)** `ImporterCsvResource`, `RefApplyService` [E] [C] (BO-08-01-04)

Un import central qui échoue le disait à celui qui avait lancé l'import, et à personne d'autre. L'exigence demande un **état consultable** : qu'est-ce qui est entré, quand, et avec quelle issue. Le mécanisme d'intégration existait déjà pour les référentiels diffusés ; il suffisait de l'appeler aussi depuis les imports CSV.

- **Le domaine est dérivé du nom de la classe d'import**, coupé à vingt caractères : aucune liste à tenir à jour, et un nouvel import s'enregistre sans qu'on y pense.
- **L'enregistrement avale ses propres pannes.** Un import réussi dont la trace ne s'écrit pas reste un import réussi ; l'inverse — un import annulé parce que son journal est indisponible — serait absurde.
- **Les deux issues sont écrites**, le succès comme l'échec, et l'échec porte son motif : un état qui ne montre que les succès ne sert à rien.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `auditDomain()` | nom court / nom long | dérivé / coupé à vingt |
| Import | succès | issue favorable enregistrée |
| Import | `IOException` | issue défavorable, motif porté |
| Import | `Throwable` inattendu | issue défavorable, motif porté |
| Enregistrement | journal indisponible | import inchangé, aucune exception remontée |

## D. Service du référentiel aux caisses [E] [R]

`imports/RefExportResource` + `service/sync/store/RefExportService`. Garde commune (`gate`) : rôle ni store ni central → **403** « Ce nœud n'a pas le rôle store ou central » ; jeton configuré non vide et discordant → **401** « Jeton invalide » ; jeton absent → ouvert.
**Domaines réels** : `RefPayloads.DOMAINS` = **17** (ENGINE_FEEDS, PRODUCTS, FAMILIES, TOUCH_GROUPS, VAT_RATES, PRICES, EMPLOYEES, COUPON_TYPES, ARTICLE_RANGES, ISLANDS, CHECKOUT_POINTS, TENDERS, DOCUMENT_TEMPLATES, SETTINGS, CUSTOMERS, CURRENCIES, POSTAL_CODES) ; `RefPayloads.ECHELON_DOMAINS` = **4** (COUNTRIES, ENSEIGNES, PDVS, ECHELON_SETTINGS). Le `switch` de `getPage` couvre l'union exacte = **21** cas.

**D1 Empreintes par domaine** `GET /api/referential/versions` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Garde rôle | rôle register | 403 « Ce nœud n'a pas le rôle store ou central » |
| Garde jeton | jeton discordant | 401 « Jeton invalide » |
| Liste store | rôle store | 200 map SHA-256, **17** domaines (`DOMAINS`) |
| Liste central | rôle central | 200 map, **4** domaines (`ECHELON_DOMAINS`), disjointe de la précédente |
| Stabilité | deux appels sans changement | empreintes identiques (cache TTL 60 s) |
| Recalcul | empreinte expirée (>60 s) | recalculée par domaine, insérée dans la map ordonnée |
| Domaine vide | domaine sans donnée | empreinte stable (digest vide), jamais d'erreur |

**D2 Page de snapshot** `GET /api/referential/{domain}?page&size` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Garde rôle | rôle register | 403 « Ce nœud n'a pas le rôle store ou central » |
| Garde jeton | jeton discordant | 401 « Jeton invalide » |
| Page nominale | domaine connu, page valide | 200 liste de DTO au format fil, ordre canonique |
| Casse normalisée | `products`, `Products` | `domain.toUpperCase()` → traité comme `PRODUCTS` |
| Domaine vide | domaine sans donnée | 200 liste vide, aucune erreur |
| Page au-delà | `page` > dernière | 200 liste vide |
| Domaine inconnu | code absent du `switch` | 400 « Domaine inconnu: <DOMAINE> » (`IllegalArgumentException`, majusculisé) |
| Borne size ≤ 0 | `size=0` ou négatif | ramené à 1000 par défaut |
| Borne size > 5000 | `size=6000` | ramené à 1000 |
| Borne page négative | `page=-1` | `Math.max(0, page)` → 0 |
| Ordre d'application | domaine servi par ordre fixe (PRODUCTS avant FAMILIES, VAT_RATES avant PRICES via l'ordre de `DOMAINS`) | ordre annoncé respecté |

**D3 Descente du point de contact au poste** (BO-03-09, BO-03-10) [E] [R]

La caisse se nomme sur chaque tirage par l'en-tête `X-Pos-Terminal` ; le nœud s'en sert pour résoudre, et pour rien d'autre — il nomme, il ne prouve pas, la garde de rôle et le jeton restent seuls juges.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Domaine CHECKOUT_POINTS | `GET /api/referential/CHECKOUT_POINTS` | 200, un DTO par point du magasin, ordre `terminalId`, douze champs, énumérations par NOM |
| Instantané global | deux caisses différentes demandent le domaine | mêmes lignes : la caisse reçoit TOUS les points, pas le sien seul |
| Type non décrit | point sans `tpvType` / sans type d'afficheur | champs à `null` sur le fil, jamais une valeur par défaut inventée |
| Place dans l'ordre | position de CHECKOUT_POINTS dans `DOMAINS` | juste après ISLANDS : un point nomme son île, l'île existe d'abord |
| SETTINGS sans en-tête | en-tête absent | résolution PDV seule — exactement l'ancien comportement |
| SETTINGS en-tête vide | en-tête `"   "` | idem en-tête absent |
| SETTINGS avec en-tête | en-tête `POS07` | chaîne complète PAYS → ENSEIGNE → PDV → ÎLOT → POINT DE CONTACT |
| Terminal inconnu | en-tête d'un terminal sans point | **pas une erreur** : résolution PDV plus ce qui serait posé sur cet identifiant |
| Spécificité | même clé posée à l'ÎLOT et au POINT | le point gagne ; posée à PDV et ÎLOT, l'île gagne |
| Date d'effet | clé posée à l'ÎLOT puis au POINT avec une date future | ignorée jusqu'au jour dit |
| Surcharge locale | clé posée sur le nœud ET résolue par terminal | la surcharge locale gagne, comme sur la résolution PDV |
| **Empreinte par terminal** | deux caisses interrogent `/versions` dans la même fenêtre de 60 s | **chacune reçoit l'empreinte de SA résolution** ; indexée par domaine seul, la seconde s'entendrait répondre « rien n'a changé » et garderait les paramètres de la première |
| Empreinte terminal nul | en-tête absent ou vide | clé de cache nue, partagée |
| Empreinte domaine global | VAT_RATES demandé par deux caisses | une seule empreinte : un domaine global ne devient pas dépendant du terminal |
| Cohérence empreinte/contenu | `/versions` puis `/{domain}` avec le même en-tête | le hash et les lignes viennent du même identifiant |

---

## E. Échelons et brique centrale [C]

**E1 Page échelons** `GET /admin/echelons` [C]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| echelonsPage | Anonyme | 401 (ou 302 login) — `@RolesAllowed("ADMIN")` |
| echelonsPage | Rôle non-ADMIN (CASHIER…) | 403 |
| echelonsPage | ADMIN, sans viewEnseigne/viewKey | 200, page rendue ; `personalised` = liste vide ; data countries/enseignes/pdvs/keys (CATALOG) |
| echelonsPage | viewEnseigne et viewKey renseignés (non blancs) | `personalised` = `echelonSettings.personalizedPdvs(viewEnseigne, viewKey)` |
| echelonsPage | viewEnseigne renseigné, viewKey blanc (ou l'inverse) | `personalised` = liste vide (garde `!= null && !isBlank()` sur les deux) |
| echelonsPage | noticeOk absent | défaut `true` (`@DefaultValue("true")`) |
| echelonsPage | data `closedValues` | carte JSON `{"clé": ["A","B"]}` des seules clés à valeurs fermées, construite depuis `PosSettingsService.CLOSED_VALUES` ; les clés libres en sont ABSENTES (le script rend alors le champ libre) |

**E2 Enregistrement pays** `POST /admin/echelons/country` [C]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| saveCountry | Anonyme / non-ADMIN | 401(302) / 403 |
| saveCountry | code vide/blanc | 303 → notice « Le code pays est obligatoire. », noticeOk=false ; rien créé |
| saveCountry | code nouveau | Country créé + persist, name/defaultLanguage posés ; 303 « Pays enregistré. » ok=true |
| saveCountry | code existant | Country retrouvé, name/defaultLanguage mis à jour (pas de doublon) ; 303 « Pays enregistré. » |
| saveCountry | defaultLanguage vide | stocké null (blankToNull) |

**E3 Enregistrement enseigne** `POST /admin/echelons/enseigne` [C]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| saveEnseigne | Anonyme / non-ADMIN | 401(302) / 403 |
| saveEnseigne | code vide/blanc | 303 « Le code enseigne est obligatoire. » ok=false ; rien créé |
| saveEnseigne | code nouveau | Enseigne créée + persist ; name/countryCode/defaultLanguage posés ; 303 « Enseigne enregistrée. » ok=true |
| saveEnseigne | code existant | mise à jour sur place ; 303 « Enseigne enregistrée. » |
| saveEnseigne | countryCode / defaultLanguage vides | stockés null (blankToNull) |

**E4 Enregistrement PDV** `POST /admin/echelons/pdv` [C]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| savePdv | Anonyme / non-ADMIN | 401(302) / 403 |
| savePdv | numéro null/absent | 303 « Le numéro de PDV doit comporter exactement 5 chiffres. » ok=false |
| savePdv | numéro ≠ 5 caractères (ex. « 123 », « 123456 ») | idem 303 refus 5 chiffres |
| savePdv | numéro à 5 caractères non tous chiffres (ex. « 12A45 ») | idem 303 refus 5 chiffres (isValidNumber) |
| savePdv | numéro 5 chiffres, pas de rename, PDV inexistant | création + persist ; name/enseigneCode/adherentCode/active posés ; 303 « Point de vente enregistré. » ok=true |
| savePdv | numéro existant, pas de rename | mise à jour sur place ; 303 « Point de vente enregistré. » |
| savePdv | rename (originalNumber ≠ number), original introuvable | 303 « PDV à renommer introuvable. » ok=false |
| savePdv | rename, numéro cible déjà utilisé (findByNumber(number)≠null) | 303 « Ce numéro de PDV est déjà utilisé. » ok=false ; pas de migration |
| savePdv | rename valide (original trouvé, cible libre) | pdvNumber ré-affecté + `echelonSettings.migratePdv(original,number)` ; 303 « Point de vente enregistré. » ok=true |
| savePdv | originalNumber == number | traité comme non-rename (branche else) |
| savePdv | champ « active » présent / absent | active = (getFirst("active")!=null) → true / false |
| savePdv | enseigneCode / adherentCode vides | stockés null (blankToNull) |

**E5 Paramètre d'échelon (pose)** `POST /admin/echelons/setting` [C]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| saveSetting | Anonyme / non-ADMIN | 401(302) / 403 |
| saveSetting | level inconnu/blanc (parseLevel→null) | 303 « Niveau, échelon et paramètre sont obligatoires. » ok=false |
| saveSetting | echelonCode vide | idem 303 obligatoires ok=false |
| saveSetting | key vide | idem 303 obligatoires ok=false |
| saveSetting | clé à valeurs fermées, valeur hors liste (ex. `control.cancel-line` = `BLOK`) | 303 « Valeur invalide pour « <clé> » — attendu : NONE, WARN, BLOCK, ENDORSE. » ok=false ; rien posé, date non lue |
| saveSetting | clé à valeurs fermées, valeur de la liste (ex. `BLOCK`) | acceptée → `set(level, code, key, value, date)` |
| saveSetting | clé à valeurs fermées, valeur vide alors que `""` est dans la liste (`island.promotion-channel`) | acceptée (le « — aucune — » de la liste) |
| saveSetting | clé libre (hors `CLOSED_VALUES`) | aucun contrôle de valeur — texte accepté tel quel |
| saveSetting | valides, effectiveDate vide | date=null (effet immédiat) ; `echelonSettings.set(...,null)` ; 303 « Paramètre d'échelon enregistré. » ok=true |
| saveSetting | valides, effectiveDate ISO valide (AAAA-MM-JJ) | date parsée ; set(...,date) ; 303 « Paramètre d'échelon enregistré. » ok=true |
| saveSetting | effectiveDate non-ISO (ex. « 26/09/2026 ») | 303 « Date d'effet invalide (attendu AAAA-MM-JJ). » ok=false ; rien posé |
| saveSetting | level=COUNTRY/ENSEIGNE/PDV | accepté (chaque valeur d'EchelonLevel) |

**E6 Paramètre d'échelon (effacement)** `POST /admin/echelons/setting/clear` [C]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| clearSetting | Anonyme / non-ADMIN | 401(302) / 403 |
| clearSetting | level null / echelonCode vide / key vide | 303 « Niveau, échelon et paramètre sont obligatoires. » ok=false |
| clearSetting | valeur existante (clear→true) | ligne supprimée ; 303 « Paramètre d'échelon supprimé. » ok=true |
| clearSetting | aucune valeur à cet échelon (clear→false) | 303 « Aucun paramètre à supprimer à cet échelon. » ok=false |

**E7 Résolution par échelon** `EchelonSettingService.resolveForPdv / resolve / personalizedPdvs / set / clear / migratePdv` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| resolveForPdv | pdvNumber null | map vide |
| resolveForPdv | PDV inconnu (findByNumber null) | map vide |
| resolveForPdv | PDV sans enseigne (enseigneCode null) | seule couche PDV appliquée (ni pays ni enseigne) |
| resolveForPdv | PDV avec enseigne mais Enseigne introuvable | countryCode reste null → couches ENSEIGNE + PDV, pas COUNTRY |
| resolveForPdv | enseigne rattachée à un pays | overlay COUNTRY puis ENSEIGNE puis PDV |
| resolveForPdv | même clé posée pays + enseigne + PDV | valeur PDV gagne (plus spécifique écrase) |
| resolveForPdv | même clé posée pays + enseigne, rien au PDV | valeur enseigne gagne sur pays |
| resolveForPdv(asOf) | ligne effectiveDate == null | appliquée (effet immédiat) |
| resolveForPdv(asOf) | effectiveDate <= asOf | appliquée |
| resolveForPdv(asOf) | effectiveDate > asOf (future) | ignorée (isAfter true) tant que le jour n'est pas venu |
| resolve | clé posée | Optional présent avec la valeur |
| resolve | clé non posée | Optional.empty |
| set (upsert) | ligne inexistante | EchelonSetting créé + persist (level/code/key/value/effectiveDate) |
| set (upsert) | ligne existante | settingValue + effectiveDate mis à jour, pas de doublon |
| clear | ligne existante | delete + true |
| clear | ligne absente | false, aucun delete |
| migratePdv | plusieurs lignes PDV sur oldNumber | chaque echelonCode ré-affecté à newNumber ; retourne le compte migré |
| migratePdv | aucune ligne sur oldNumber | retourne 0 |
| personalizedPdvs | enseigne sans PDV (listByEnseigne vide) | List.of() (vide) |
| personalizedPdvs | PDV de l'enseigne avec override PDV sur la clé | ces PDV retournés, ordonnés par numéro |
| personalizedPdvs | PDV sans override sur la clé | exclus du résultat |

**E8 Chaîne central→magasin & invariant de seed** `RefPullService / RefApplyService / DataInitializer.loadEchelons` [C]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| domainsToPull | rôle « store » | pull des ECHELON_DOMAINS = COUNTRIES, ENSEIGNES, PDVS, ECHELON_SETTINGS |
| applyDomain | fingerprint distinct | applyCountries / applyEnseignes / applyPdvs / applyEchelonSettings selon le domaine puis recordApplied |
| applyDomain | fingerprint identique au local | domaine sauté (pas d'application) |
| loadEchelons | rôle par défaut « store » (défaut d'imedge) | retour immédiat ; COUNTRIES/ENSEIGNES/PDVS/ECHELON_SETTINGS restent VIDES tant que le central n'a rien servi (jamais seedées au magasin) |
| loadEchelons | rôle « central » | wipe + seed FR / ITM / PDV 07039 / EchelonSetting COUNTRY FR receipt.footer.legal |
| Invariant magasin | démarrage store node seul | les 4 tables échelon vides ; peuplées ensuite par le pull de fingerprints depuis le central |

**E9 Résolution à cinq niveaux — îlot et point de caisse** `EchelonSettingService#resolveForTerminal` [E]

La chaîne d'échelons passe de trois à cinq niveaux : COUNTRY → ENSEIGNE → PDV → ISLAND → CHECKOUT_POINT, résolution du plus spécifique au plus général (BO-03-09/03-10). C'est ce geste qui rend « réglable par TPV » les clés partielles. Les deux niveaux du bas sont administrés localement au magasin (échelon `ISLAND` codé par le code d'îlot, `CHECKOUT_POINT` codé par l'identifiant de terminal).

| Fonction | Cas | Attendu |
| --- | --- | --- |
| resolveForTerminal | terminal null | repli sur `resolveForPdv` seul (ni îlot ni point) |
| resolveForTerminal | clé posée PDV + îlot + point | valeur du POINT gagne (plus spécifique) |
| resolveForTerminal | clé posée PDV + îlot, rien au point | valeur de l'ÎLOT gagne sur le PDV |
| resolveForTerminal | îlot du terminal via `CheckoutPoint` | îlot lu du point de caisse (`findByTerminal`) |
| resolveForTerminal | terminal sans point de caisse | couche îlot sautée, couche point (par id) tout de même appliquée |
| resolveForTerminal | point rattaché à aucun îlot (`islandCode` null) | couche îlot sautée, couche point appliquée |
| resolveForTerminal(asOf) | ligne d'effet future à un niveau bas | ignorée tant que le jour n'est pas venu (même garde que les autres niveaux) |
| resolveForTerminal (public) | sans asOf | délègue au cœur daté avec le jour courant |
| applyEchelonSettings | pull central | efface seulement COUNTRY/ENSEIGNE/PDV (`level in ?1`), JAMAIS `deleteAll` — les lignes ISLAND/CHECKOUT_POINT locales survivent |

## F. Flux entrants et relais vers le moteur [E] [V]

**F1 Porte unique d'import** `POST /feeds/import/{code}` · `GET /feeds/import/status` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| importFeed | Anonyme | 401 — `@RolesAllowed("ADMIN")` |
| importFeed | Rôle non-ADMIN | 403 |
| importFeed | code reconnu par un importeur (casse indifférente, normalisé trim+upper) | délégation à l'importeur, sa réponse propre remonte (created/updated) |
| importFeed | code du catalogue moteur sans importeur (STORE_GROUPS…) corps non vide | stockage verbatim ; 200 `{"code":"<CODE>", "version":"<sha256>"}` |
| importFeed | code du catalogue moteur, corps vide/blanc | 400 `{"error":"Empty feed body"}` |
| importFeed | code inconnu (ni délégué ni catalogue) | 400 `{"error":"Unknown feed code '<CODE>' - known codes: <liste triée délégués + codes catalogue>"}` |
| importFeed | code null | normalisé en "" → code inconnu → 400 message ci-dessus |
| status | Anonyme / non-ADMIN | 401 / 403 |
| status | ADMIN | 200, tableau JSON par feed du CATALOG existant : code, version, appliedVersion (ou null), applied (version==appliedVersion), lastError (guillemets échappés) ; feed absent en base → sauté |

**F2 Mécanique commune d'import CSV** `ImporterCsvResource.importCsvStream` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Colonne clé ou requise absente du header | header sans une colonne déclarée | 400 `{"error":"Missing required columns: <noms manquants, clé d'abord puis requises en ordre>"}` |
| Header valide, lignes correctes | fichier nominal | 200 `{"createdCount":X, "updatedCount":Y}` |
| Ligne à moins de cellules que le header | ligne tronquée | entrée d'erreur « Line N ignored (fewer cells than the header): <ligne> », ligne ignorée, import poursuit (200) |
| Ligne à clé vide/nulle | cellule clé vide | entrée d'erreur « Line N ignored (empty key '<keyColumn>') », ligne ignorée |
| Ligne vide | ligne blanche | ignorée silencieusement |
| Header dupliqué | colonne répétée | 1re occurrence gagne (log warn), pas d'échange de champ |
| Colonne inconnue de l'importeur | colonne d'un autre outil | ignorée (schéma union toléré) |
| Ré-import du même fichier | checksum par ligne inchangé | no-op, updatedCount ne compte que les vrais changements |
| Chunk 1000 en échec transactionnel | ligne poison dans le lot | repli par paliers 1000→100→10→1 ; en 1-par-1 findEntityForLine réattache l'entité détachée ; ligne fautive → « Line N (<code>): <message> », les autres passent |
| Flux d'entrée illisible (IOException) | stream cassé | 500 « Error reading file: <msg> » |
| Erreur inattendue | Throwable | 500 « Unexcepted error: <msg> » |
| captureFeed | feedCode() non null, import terminé | fichier brut stocké verbatim via engineFeedService.store (même si erreurs de ligne) |
| captureFeed | échec de capture | loggé, n'échoue pas l'import |

**F3 StoreCsvResource** — clé `CODE`, requises `NAME, STREET_LINE1, STREET_LINE2, POSTAL_CODE, CITY, COUNTRY` — relaie feed `STORES` [E] [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| importStores | header complet, données valides | créations/màj magasin ; 200 counts ; feed STORES capturé verbatim |
| importStores | CODE absent | 400 « Missing required columns: CODE » |
| importStores | une requise absente (ex. POSTAL_CODE) | 400 « Missing required columns: POSTAL_CODE » |
| importStores | CODE vide sur une ligne | ligne ignorée (empty key 'CODE') |

**F4 ProductCsvResource** — clé `EAN`, requises `NAME, DESCRIPTION, BRAND, REFERENCE_WEIGHT, REFERENCE_VOLUME, PRODUCT_TYPE, UNIT_NAME, ACTIVE` — relaie feed `PRODUCTS` [E] [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| importProducts | header complet, lignes valides | créations/màj produit ; 200 counts ; feed PRODUCTS capturé |
| importProducts | EAN absent | 400 « Missing required columns: EAN » |
| importProducts | requise absente (ex. PRODUCT_TYPE) | 400 « Missing required columns: PRODUCT_TYPE » |
| importProducts | EAN vide sur une ligne | ligne ignorée (empty key 'EAN') |

**F5 ProductFamilyCsvResource** — clé `CODE`, requises `DESCRIPTION, FLAGS, PRODUCT_EANS, SUBFAMILY_CODES` — relaie feed `FAMILIES` [E] [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| importProductFamilies | header complet, lignes valides | créations/màj familles ; 200 counts ; feed FAMILIES capturé |
| importProductFamilies | CODE absent | 400 « Missing required columns: CODE » |
| importProductFamilies | requise absente (ex. SUBFAMILY_CODES) | 400 « Missing required columns: SUBFAMILY_CODES » |

**F6 VatRateCsvResource** — clé `VAT_NUMBER`, requise `RATE` — relaie feed `VAT_RATES` [E] [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| importVatRates | header valide | créations/màj taux TVA ; 200 counts ; feed VAT_RATES capturé |
| importVatRates | VAT_NUMBER absent | 400 « Missing required columns: VAT_NUMBER » |
| importVatRates | RATE absente | 400 « Missing required columns: RATE » |

**F7 EgalimRegimeCsvResource** — clé `CODE`, aucune colonne requise — relaie feed `EGALIM_REGIMES` [E] [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| importEgalimRegimes | header avec CODE | créations/màj régimes ; 200 counts ; feed EGALIM_REGIMES capturé |
| importEgalimRegimes | CODE absent | 400 « Missing required columns: CODE » |
| importEgalimRegimes | header CODE seul (LABEL/CAP_RATE absents) | accepté (aucune requise), colonnes lues quand présentes |

**F8 PriceCsvResource** — clé `EAN`, requises `PRICE_EXCL_TAX, PRICE_INCL_TAX, PRIORITY, START_DATE, END_DATE` — relaie feed `PRICES` [E] [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| importPrices | header valide | créations/màj prix ; 200 counts ; feed PRICES capturé |
| importPrices | EAN absent | 400 « Missing required columns: EAN » |
| importPrices | requise absente (ex. START_DATE) | 400 « Missing required columns: START_DATE » |

**F9 EmployeeCsvResource** — clé `BADGE_ID`, requises `LOGIN, PIN, FIRST_NAME, LAST_NAME, EMAIL, ROLE` — NE relaie AUCUN feed (feedCode null) [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| importEmployees | header valide | créations/màj employés ; 200 counts ; aucun feed moteur capturé |
| importEmployees | BADGE_ID absent | 400 « Missing required columns: BADGE_ID » |
| importEmployees | requise absente (ex. ROLE) | 400 « Missing required columns: ROLE » |

**F10 AccountCustomerCsvResource** — clé `ACCOUNT_NUMBER`, requise `COMPANY_NAME` — NE relaie AUCUN feed (feedCode null) [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| importCustomers | header valide, mode absent/≠full | import delta ; 200 counts ; aucun blocage des absents |
| importCustomers | mode=full (casse indifférente) | import complet puis blockAbsentAccounts : comptes non vus et non déjà bloqués passés blocked=true |
| importCustomers | mode=full, feed vide (seen vide) | blocage de tous les comptes blocked=false |
| importCustomers | ACCOUNT_NUMBER absent | 400 « Missing required columns: ACCOUNT_NUMBER » |
| importCustomers | COMPANY_NAME absente | 400 « Missing required columns: COMPANY_NAME » |

**F11 PostalCodeCsvResource** — clé `CODE`, requise `PLACE` — NE relaie AUCUN feed [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| importPostalCodes | header valide | créations/màj codes postaux ; 200 counts |
| importPostalCodes | CODE absent | 400 « Missing required columns: CODE » |
| importPostalCodes | PLACE absente | 400 « Missing required columns: PLACE » |

**F12 SafeReceiptCsvResource** — clé `REFERENCE`, requise `AMOUNT` — NE relaie AUCUN feed [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| importReceipts | header valide | créations/màj justificatifs coffre ; 200 counts |
| importReceipts | REFERENCE absente | 400 « Missing required columns: REFERENCE » |
| importReceipts | AMOUNT absente | 400 « Missing required columns: AMOUNT » |

**F13 NomenclatureCsvResource** — colonnes `NIVEAU, NUMERO, LIBELLE` (séparateur `|`), scheme sur la requête — NE relaie AUCUN feed (mécanique propre, hors ImporterCsvResource) [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| importNomenclature | scheme + fichier valides, sans erreur | 200 JSON (created/updated/levels) ; scheme upserté avec label/enseigne |
| importNomenclature | scheme null/blanc | 400 `{"errors":["Le paramètre scheme est obligatoire."]}` |
| importNomenclature | fichier absent (stream null) | 400 avec complaint « Fichier absent. » |
| importNomenclature | fichier vide (pas de header) | 400 « Fichier vide. » |
| importNomenclature | colonne NIVEAU/NUMERO/LIBELLE absente | 400 « Colonne <NIVEAU|NUMERO|LIBELLE> absente. » (un message par manquante) |
| importNomenclature | ligne au niveau illisible | 400 « Ligne N : niveau illisible. » (complaint bloquante) |
| importNomenclature | chargement avec erreurs métier (outcome non clean) | 409 CONFLICT + JSON outcome.errors |

**F14 Codes verbatim moteur** `POST /feeds/import/{STORE_GROUPS|CATEGORY_STORAGES|BUDGET_GROUPS|OFFERS}` [E] [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| STORE_GROUPS | corps non vide | capture verbatim (non ouverte), 200 `{"code":"STORE_GROUPS", "version":"<sha256>"}` |
| CATEGORY_STORAGES | corps non vide | capture verbatim, 200 `{"code":"CATEGORY_STORAGES","version":"..."}` |
| BUDGET_GROUPS | corps non vide | capture verbatim, 200 `{"code":"BUDGET_GROUPS","version":"..."}` (nb : normalement projeté depuis les campagnes) |
| OFFERS | corps non vide | capture verbatim, 200 `{"code":"OFFERS","version":"..."}` |
| chacun | corps vide/blanc | 400 `{"error":"Empty feed body"}` |
| chacun | même contenu re-posté (sha inchangé) | store no-op, même version retournée, receivedAt inchangé |
| chacun | contenu modifié re-posté | nouvelle version (sha256 différent), receivedAt màj, feed en attente de livraison |

**F15 Livraison au moteur** `EngineFeedService / EngineFeedDeliveryService` [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| onStart | pos.valuation.url absent/blanc | boucle désactivée (log), pas d'executor |
| onStart | url présente | executor démarré, 1er run à min(20s, période) puis toutes les deliverySeconds |
| pendingFeeds | feed dont version==appliedVersion | exclu (à jour) |
| pendingFeeds | feed dont version≠appliedVersion | inclus, en ordre CATALOG (partagés avant spécifiques) |
| deliverPending | tous feeds, moteur 2xx | chacun POSTé sur enginePath ; markApplied(code,version) ; erreur effacée |
| deliverPending | 1er feed échoue (HTTP non-2xx) | recordFailure « HTTP <code> sur <path>: <body> », markError ; walk STOPPE (feeds suivants non livrés, ordre préservé) |
| deliver | moteur coupé (exception/timeout) | recordFailure « <ExceptionClass>: <msg> », flux conservé, retry au prochain tick |
| deliver | version < 12 caractères | shortVersion tronque sans exception (pas de faux échec après ACK) |
| markError | message > 500 car. | tronqué à 500 |
| deliver | user configuré | header Authorization Basic (user:password) ; sinon aucun header auth |
| triggerDelivery() (global manuel) | cycle OK | deliverPending ; retourne null |
| triggerDelivery() | exception atteignant le lookup | retourne e.getMessage() ; échec HTTP par-feed reste sur le feed, ne remonte pas |
| triggerDelivery(code) (ciblé) | code en attente, livré 2xx | markApplied ; retourne null ; autres feeds intacts |
| triggerDelivery(code) | code en attente, livraison échoue | retourne deliveryError(code) = dernier lastError (ou « livraison du flux <code> en échec ») |
| triggerDelivery(code) | code sans rien en attente (déjà appliqué/inconnu) | retourne null sans toucher le moteur |
| Reprise sans double application | feed déjà appliqué re-livré | no-op moteur (idempotence par checksum), pas de double |
| authorization store node | boucle tourne sur rôle store comme sur caisse | livraison vers le moteur local du magasin identique |

**F16 Planification différée** `ScheduledDeliveryService` + `EngineFeedDeliveryService.runScheduled` [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| schedule | feedCode nommé, date valide | ScheduledDelivery PENDING persisté (feedCode, scheduledAt, createdBy, createdAt) |
| schedule | feedCode null/blanc | normalisé null → planification globale (tous feeds) |
| cancel | booking PENDING existant | supprimé, retourne true |
| cancel | id inexistant | false |
| cancel | booking déjà DONE/FAILED | false (non touché) |
| claimDue | bookings dont scheduledAt <= now (PENDING) | snapshots Due (id, feedCode), plus ancien d'abord |
| runScheduled | Due sans feedCode | triggerDelivery() global puis markResult |
| runScheduled | Due avec feedCode | triggerDelivery(code) puis markResult |
| runScheduled | une réservation échoue | markResult FAILED, les autres continuent |
| markResult | error null | status DONE, message null |
| markResult | error non null | status FAILED, message tronqué à 500 |
| markResult | booking disparu (annulé entre claim et result) | no-op |
| list | affichage supervision | tous bookings, plus récent d'abord |

## G. Supervision et diffusion [E] [R] [C] [V]

Surface `ui/supervision/SyncSupervisionResource` (+ `SyncSupervisionService`, `RefPullService`, `EngineFeedDeliveryService`, `ScheduledDeliveryService`). Toutes les routes sous `/admin/sync` portent `@RolesAllowed("ADMIN")` ; tout POST répond en 303 + notice one-shot (`noticeOk` vrai/faux). Le nœud ne connaît que lui-même : chaque action est locale.

**G1 Page de supervision** `GET /admin/sync` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `page` | Accès ADMIN | 200 HTML : trois sections (flux moteur, tirage, outbox) + planifications + cadences, rendues par `admin-sync` |
| `page` | Accès anonyme | 302 vers `/admin/login`, jamais de page partielle ni de 500 |
| `page` | Accès authentifié non-ADMIN | 403, aucune action rendue |
| `page` | `sessionOpen` transmis | vrai si `cashSessionService.getOpenSession() != null`, faux sinon (pilote l'affichage du bouton force-close) |
| `page` | `notice`/`noticeOk` en query | notice affichée avec sa couleur ; `noticeOk` par défaut `true` quand absent |
| `page` | Nœud sain | `healthy=true` quand aucun flux en erreur ET outbox vide |
| `page` | Nœud en erreur | `engineError=true` dès qu'un flux moteur porte une `lastError`, drapeau rouge |

**G2 Rafraîchissement automatique** `GET /admin/sync/{feeds|pulls|outbox}` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `feedsFragment` | ADMIN | 200 : fragment `feeds` seul (corps du tableau flux moteur) |
| `pullsFragment` | ADMIN | 200 : fragment `pulls` seul (corps du tableau tirage) |
| `outboxFragment` | ADMIN | 200 : fragment `backlog` seul (corps du tableau outbox) |
| fragments | Anonyme | 302 vers `/admin/login` ; non-ADMIN → 403 |
| refresh JS | Onglet visible | les trois corps de tableaux remplacés toutes les 2 s sans rechargement, scroll et actions préservés |
| refresh JS | Onglet masqué (`document.hidden`) | rafraîchissement suspendu (patron `valuation-refresh`), repris à la ré-affichage |

**G3 Relance du tirage** `POST /admin/sync/pull` [E] [C]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `relaunchPull` | Cycle réussi (`triggerPull` renvoie null) | 303, notice verte « Tirage des référentiels relancé. » ; event `REFERENTIAL_PULL_FORCED` « Tirage manuel des référentiels réussi » ; `lastSuccessfulPull` rafraîchi |
| `relaunchPull` | Cycle en échec (message retourné) | 303, notice rouge « Tirage des référentiels en échec : <msg> » ; event `REFERENTIAL_PULL_FORCED` « …en échec: <msg> » ; `lastSuccessfulPull` inchangé |
| `relaunchPull` | Central injoignable (rôle store) | échec propre, message HTTP visible, aucune donnée locale effacée |
| `relaunchPull` | Anonyme / non-ADMIN | 302 login / 403 |

**G4 Relance de livraison globale** `POST /admin/sync/deliver` [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `relaunchDelivery` | Livraison réussie | 303, notice verte « Livraison des flux moteur relancée. » ; event `ENGINE_FEED_DELIVERY_FORCED` « Livraison manuelle des flux moteur réussie » |
| `relaunchDelivery` | Échec au niveau du lookup pending (`triggerDelivery` renvoie un message) | 303, notice rouge « Livraison des flux moteur en échec : <msg> » ; event « …en échec: <msg> » |
| `relaunchDelivery` | Un flux échoue en HTTP | l'erreur est portée par la ligne du flux (tableau), la notice globale reste verte ; la marche s'arrête au 1er échec (ordre d'application préservé) |
| `relaunchDelivery` | Anonyme / non-ADMIN | 302 login / 403 |

**G5 Livraison ciblée / rejeu** `POST /admin/sync/deliver/{code}` [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `relaunchDeliveryFor` | Flux en attente livré | 303, notice verte « Flux <code> livré. » ; seul ce flux part, les autres gardent leur version acquittée ; event « Livraison manuelle du flux moteur <code> réussie » |
| `relaunchDeliveryFor` | Livraison du flux échouée | 303, notice rouge « Livraison du flux <code> en échec : <erreur relue sur le flux> » ; event « …<code> en échec: <msg> » |
| `relaunchDeliveryFor` | Rejeu d'une ligne en erreur (moteur revenu) | même bouton, ligne remise à jour, `markApplied` posé, PAS de double application (idempotence checksum aval) |
| `relaunchDeliveryFor` | Code déjà acquitté / rien en attente | 303, notice verte « Flux <code> livré. » sans toucher le moteur (no-op propre) |
| `relaunchDeliveryFor` | Code inconnu / hors catalogue | idem : rien en attente → « Flux <code> livré. » (aucun flux atteint) |
| `relaunchDeliveryFor` | Anonyme / non-ADMIN | 302 login / 403 |

**G6 Planification d'une livraison** `POST /admin/sync/schedule` [E] [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `schedule` | `scheduledAt` valide + `feedCode` renseigné | 303, notice verte « Livraison planifiée. » ; `ScheduledDelivery` PENDING créée pour ce flux, `createdBy` = utilisateur courant |
| `schedule` | `feedCode` vide/blanc | booking « Tous les flux » (feedCode normalisé à null) |
| `schedule` | `scheduledAt` absent ou blanc | 303, notice rouge « Date de planification manquante. » ; rien créé |
| `schedule` | `scheduledAt` non parsable ISO | 303, notice rouge « Date de planification invalide. » ; rien créé |
| exécution | Minute échue, boucle de livraison réveillée | booking exécuté (`claimDue` → livraison → `markResult`), statut DONE, `message` null |
| exécution | Livraison planifiée échouée | statut FAILED + message tronqué (≤500), visible en section « Planifications » |
| exécution | Un booking échoue | n'arrête pas les autres bookings dus |
| `schedule` | Anonyme / non-ADMIN | 302 login / 403 |

**G7 Annulation d'une planification** `POST /admin/sync/schedule/{id}/cancel` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `cancelSchedule` | Booking encore PENDING | 303, notice verte « Planification annulée. » ; ligne supprimée, jamais exécutée |
| `cancelSchedule` | Booking déjà DONE/FAILED | 303, notice rouge « Planification introuvable ou déjà exécutée. » ; ligne intacte |
| `cancelSchedule` | Id inconnu | 303, notice rouge « Planification introuvable ou déjà exécutée. » |
| `cancelSchedule` | Anonyme / non-ADMIN | 302 login / 403 |

**G8 Fermeture forcée de session** `POST /admin/sync/force-close` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `forceCloseSession` | Session ouverte sur le nœud | 303, notice verte « Session <N> clôturée de force. » ; `closeSession(null, 0, 0, null)` (aucun caissier, aucun comptage), clôture journalisée |
| `forceCloseSession` | Aucune session ouverte (`report == null`) | 303, notice rouge « Aucune session ouverte sur ce nœud. » |
| `forceCloseSession` | Anonyme / non-ADMIN | 302 login / 403 |

**G9 Table des tirages référentiel** `SyncSupervisionService.build` + `RefPullService` [E] [C]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| table pulls | Domaine appliqué | une ligne : domaine, empreinte (12 car.), date d'application, `lastError` null |
| table pulls | Domaine jamais appliqué mais en erreur | ligne ajoutée depuis `getLastErrors()` : empreinte « — », date « — », `lastError` = message ; apparaît quand même |
| table pulls | Domaine listé deux fois | dédup : une erreur déjà rattachée à un `RefState` n'ajoute pas de ligne fantôme |
| `pullOnce` | Fichier invalide sur un domaine | ce domaine porte son erreur (`lastErrors.put`), **les autres du cycle sont tirés quand même** ; 1re erreur relancée en `IllegalStateException` |
| `pullOnce` | Isolation par domaine | une seule erreur retenue par domaine (écrasée au tir suivant) ; domaine réappliqué → retiré de `lastErrors` |
| `getLastSuccessfulPull` | Cycle avec au moins un échec | `lastSuccessfulPull` NON avancé : le dernier tirage affiché ne recule jamais |
| `getLastSuccessfulPull` | Après redémarrage | null → affiché « — » ; ne ment pas sur un tirage qui n'a pas eu lieu (état en mémoire) |
| `getLastError` | Domaine sain | renvoie null |

**G10 Table des flux moteur** `EngineFeedService.feedStates` [E] [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| table feeds | Flux reçu et acquitté | version = appliedVersion (12 car.), `applied=true`, dates reçu/appliqué, `lastError` null |
| table feeds | Flux reçu non acquitté | `appliedVersion` « — », `applied=false`, `appliedAt` « — » |
| table feeds | Flux jamais reçu (code du catalogue absent) | ligne omise (bras null), comme l'ancien `/feeds/import/status` |
| table feeds | Flux en erreur de livraison | `lastError` affichée, `engineError=true` sur la vue |
| ordre | — | lignes en ordre catalogue (STORES…OFFERS), l'ordre de livraison |

**G11 Backlog de l'outbox** `SyncOutboxService.backlog` [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| table backlog | Caisse coupée, poussées en attente | une ligne par type (SESSION/MOVEMENT/TICKET/REFUND/EVENT/CUSTOMER/DOCUMENT), `count`, pire `maxAttempts`, dernière erreur |
| table backlog | Type vide | ligne omise (kinds vides sautés) |
| `outboxCount` | Somme | total des `count` ; `healthy=false` dès que > 0 |
| table backlog | Lien revenu, poussées acquittées | lignes disparaissent (rows supprimées), backlog se vide |

**G12 Cadences affichées** `SyncSupervisionService.build` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `pullSeconds` | Valeur de `pos.referential.pull-seconds` (déf. 300) | affichée telle quelle |
| `deliverySeconds` | Valeur de `pos.valuation.feed-delivery-seconds` (déf. 30) | affichée telle quelle |
| `pushSeconds` | Valeur de `pos.sync.interval-seconds` (déf. 10) | affichée telle quelle |

**G13 Fil de l'eau** `service/sync/TicketStreamService` + `StreamEvent` + `StreamPayloads` (BO-11-02-02/03/04/05) [E] [R]

Trois exigences, UN mécanisme : elles ne diffèrent que par qui lit. BO-11-02-02 expose les tickets aux applications tierces, BO-11-02-03 les tickets monétiques, BO-11-02-04 les données de transaction que la vidéosurveillance et la file unique consomment. Les destinataires ne sont pas écrits dans le code : ce sont les fournisseurs de service déclarés (M11), donc brancher un nouveau tiers est une ligne de back-office. Un abonné reçoit les types que son TYPE justifie — un collecteur monétique reçoit les tickets monétiques et aucun panier, un système d'exploitation magasin reçoit tickets et retours et aucune autorisation de carte.

La mise en file se fait DANS la transaction d'ingestion, la livraison en dehors : un ticket consolidé mais non mis en file ne serait jamais diffusé, alors qu'un événement en file dont la livraison échoue est simplement réessayé. La charge est figée à la mise en file — c'est tout l'intérêt du fil de l'eau : le tiers est informé de ce qui s'est passé alors, pas de ce que la base contient quand le lien revient.

**Rien n'est jamais abandonné** : pas de plafond de tentatives, pas de chemin d'abandon. L'exigence demande une reprise « sans perte de données » ; renoncer serait le seul mode de défaillance qu'elle interdit.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Activation | diffusion désactivée sur l'échelon | rien mis en file, la base n'est pas même touchée |
| Mise en file | ticket null, ticket sans numéro | pas un événement |
| Mise en file | retour null, retour sans numéro | pas un événement |
| Abonnés | aucun fournisseur déclaré | rien mis en file, la charge n'est même pas construite |
| Abonnés | fournisseur hors service, sans adresse, sans clé | pas abonné : mettre en file pour lui serait mettre en file un échec certain |
| Abonnés | type du destinataire | monétique → tickets monétiques ; exploitation et décisionnel → tickets et retours ; les deux ensembles ne se croisent jamais |
| Monétique | vente en espèces | aucun ticket monétique : un collecteur monétique rapproche des autorisations, une vente en espèces n'en a aucune |
| Monétique | vente par carte | ticket ET ticket monétique, chacun à son abonné ; le monétique porte le n° d'autorisation et le mode dégradé, **jamais le panier** |
| Charge ticket | contenu | identité, statut, caissière, prise de poste, totaux, lignes (annulées marquées), règlements, PDV du nœud |
| Image du ticket | aucun contenu imprimé poussé | ni image ni encodage : le lecteur doit distinguer « pas d'image » de « image que je n'ai pas su décoder » |
| Image du ticket | compression non administrée | image en clair, encodage `plain` |
| Image du ticket | compression administrée | gzip puis Base64, encodage `gzip+base64`, et le contenu se rouvre sur ce que la caisse a imprimé |
| Re-poussée | ticket repoussé, événement encore EN ATTENTE | la ligne est **réécrite**, pas doublée ; tentatives et erreur remises à zéro — c'est un nouvel état, lui imputer les échecs du précédent se lirait comme un point d'accès qui refuse |
| Re-poussée | ticket repoussé APRÈS livraison | nouvel événement, l'ancienne ligne remplacée : le ticket a changé après avoir été diffusé |
| Charge illisible | charge non sérialisable | journalisée, rien mis en file, la vente n'est pas refusée pour autant |
| Livraison | réponse 2xx | ligne supprimée — c'est ce que « livré » veut dire ; la clé présentée est celle de l'abonné, jamais une clé partagée |
| Livraison | réponse non 2xx | une tentative de plus, `HTTP <code>` en dernière erreur, ligne conservée |
| Livraison | hôte injoignable | une tentative de plus, « injoignable », ligne conservée |
| Livraison | abonné passé hors service au back-office | **aucune tentative comptée** et aucun appel : le compteur doit se lire « l'autre bord échoue », pas « un administrateur a coupé » |
| Livraison | abonné supprimé | événement conservé, intact et visible |
| Livraison | un abonné en échec parmi deux | la passe continue : l'autre est livré |
| Ordre | plusieurs événements en attente | les plus anciens d'abord : un tiers qui lit un flux de transactions l'attend dans l'ordre vécu par le magasin |
| Healthcheck | tous les abonnés à jour | **aucune ligne** — un tableau de zéros est un tableau que personne ne regarde |
| Healthcheck | deux abonnés en attente | une ligne chacun : en-cours, **pire** nombre de tentatives, dernière erreur, joignabilité |
| Healthcheck | abonné sans libellé, abonné supprimé | nommé par son code |
| Verdict du nœud | engine et outbox propres, un événement non diffusé | nœud **non** synchronisé : le fil de l'eau rejoint le verdict au lieu de se cacher dessous |
| Verdict du nœud | en-cours sans erreur | en attente mais pas en erreur : un flux qui rattrape n'est pas un flux en panne |
| Reprise manuelle | `POST /admin/sync/stream`, diffusion activée | une passe immédiate, journalisée au type `STREAM_DELIVERY_FORCED` avec le nombre d'événements livrés |
| Reprise manuelle | diffusion désactivée | refusée, rien livré, rien journalisé : un bouton pressé sur un mécanisme éteint doit le dire |
| Reprise manuelle | rôle non ADMIN | refusée par `@RolesAllowed` |
| Boucle | diffusion désactivée | la passe ne part pas |
| Boucle | échec quelconque | avalé, la boucle n'est jamais annulée pour le reste de la journée |
| Mode école | ticket d'apprentissage | la caisse ne pousse pas le marquage « mode école » aujourd'hui : un ticket d'apprentissage voyage par son **statut**, pas par un mode. Écart nommé, pas silencieux |

**G9 Flux sortants comptables** `service/export/OutboundFlowService` + `AccountingDay` + `FlatRecord` (BO-11-01-02 à 23 sauf -01/-07/-18/-20, BO-11-03-01, BO-10-04-14) [E]

Onze flux produits depuis ce que le nœud détient déjà. Chaque entrée du catalogue déclare sa PÉRIODE (journée comptable, ou mois) et sa FORME (fichier séparé, fichier plat à champs positionnés, archive multi-membres) ; c'est ce qui fait tenir sous un seul `produce` un pli mensuel sur les tickets, un pli quotidien sur les factures et une archive de trois membres. Ajouter un flux, c'est une entrée de catalogue, un pli et une clé d'activation — jamais une branche dans le moteur.

| Flux | Fichier | Période | Source |
| --- | --- | --- | --- |
| Chiffre d'affaires (BO-11-01-03) | `tranca3` | journée | tickets |
| Livre de caisse (BO-11-01-04) | `tranfc` | journée | tickets |
| CA par famille et par îlot (BO-11-01-08) | `cafamil` | journée | tickets |
| Détail des transactions (BO-11-01-14) | `trandet` | journée | tickets |
| Synthèse de clôture (BO-11-01-17) | `transyn` | journée | tickets |
| Productivité des caisses (BO-11-01-21) | `prodcai` | journée | tickets |
| Factures clients en compte (BO-11-01-06/12) | `clifact.dat` | journée | factures |
| Factures du jour vers l'ERP (BO-11-01-22) | `facterp` | journée | factures |
| Conso fidélité par TVA (BO-11-01-13) | `fidtva` | **mois** | tickets |
| Ventilation TVA des commandes (BO-11-01-23) | `tvacmde` | journée | commandes |
| Panels marketing (BO-11-01-19) | `MS.<PDV>.<jour>.zip` | journée | tickets |

Activation **flux par flux**, répertoire de dépôt et heure de bascule sont des paramètres administrés, donc résolus par échelon Pays / Enseigne / PDV — c'est ce que le cahier des charges demande, et ce que la résolution du catalogue donne déjà.

`tranca3`, `tranfc`, `clifact` et les trois membres de l'archive sont les noms du cahier des charges et ne se maquillent pas. Les autres sont des noms maison, à confirmer contre l'annexe du système receveur : le cahier des charges demande le flux sans nommer son fichier. Même réserve sur la DISPOSITION de `clifact.dat` : l'écrivain à champs positionnés est le mécanisme, l'ordre et la largeur des champs sont la lecture maison de l'exigence.

Ce qui n'est PAS dans ce moteur, et pour quelle raison — un flux absent l'est pour un motif nommé, jamais faute de rédacteur : les vignettes (BO-11-01-20) et les bons GS1 (BO-11-01-18) demandent une capture caisse qui n'existe pas ; Catalina (BO-11-01-07), SAFT, NEXO et les étiquettes électroniques sont des protocoles tiers, chacun son chantier, et chacun déclare désormais son point d'accès dans M11 plutôt que dans un flux.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Journée comptable | bascule à 0 | journée comptable = journée civile, minuit compris |
| Journée comptable | vente à 1 h 30, bascule à 2 h | rattachée à la journée PRÉCÉDENTE |
| Journée comptable | vente à 2 h 00 pile, bascule à 2 h | rattachée à la journée NOUVELLE (la borne appartient au jour qui s'ouvre) |
| Journée comptable | bascule hors 0..23 (ligne corrompue) | lue comme 0, les exports ne s'arrêtent pas |
| Bornes | début et fin de deux journées consécutives | elles se rejoignent sans se chevaucher : un ticket compté une fois |
| Activation | flux non activé sur l'échelon | aucun fichier, aucune erreur |
| Destination | flux activé, répertoire non administré | aucun fichier, avertissement au journal — un fichier déposé où personne ne le relève est pire qu'absent |
| Journée vide | aucun ticket clôturé | **aucun fichier**, pas un fichier vide : les deux ne disent pas la même chose au système receveur |
| CA par TVA | deux taux sur la journée | une ligne par taux (TTC, HT, TVA) plus une ligne TOTAL |
| CA, ligne annulée | ligne `cancelled` sur un ticket clôturé | exclue du chiffre d'affaires, conservée sur le ticket pour la piste d'audit |
| CA, montant nul | ligne sans total | repliée à zéro, la ventilation ne tombe pas |
| Livre de caisse | deux moyens de paiement, trois règlements | une ligne par moyen avec son nombre de règlements, plus une ligne TOTAL |
| Format des montants | tous les flux | deux décimales, point décimal, aucune locale — le fichier est lu par un système, pas par une personne |
| Statut des tickets | tickets OPEN, PARKED, CANCELLED présents en base | jamais exportés : seuls les CLOSED sont des ventes |
| Boucle quotidienne | journée déjà produite | rien, sans réécrire le fichier |
| Boucle quotidienne | journée comptable terminée | produite une fois, puis mémorisée |
| Boucle quotidienne | écriture en échec | journée **non** mémorisée, la minute suivante réessaie ; l'échec n'annule jamais la boucle |
| Régénération à la demande | `POST /admin/sync/export` sans date | la veille |
| Régénération à la demande | date `aaaa-mm-jj` valide | cette journée, même chemin que la production planifiée |
| Régénération à la demande | date illisible | refusée avec un message nommant le format attendu, rien n'est produit |
| Régénération à la demande | rôle non ADMIN | refusée par `@RolesAllowed` |
| Taux de TVA sur le fichier | taux à 5,5 % | écrit `0.0550` : la fraction du référentiel sur quatre décimales, jamais `0.06` — un taux qui n'existe pas sur un fichier avec lequel le receveur calcule la taxe |
| CA par famille/îlot | deux îlots, deux familles | une ligne par couple (îlot, famille) : la même famille sur deux îlots fait deux lignes, plus une ligne TOTAL |
| CA par famille/îlot | caisse absente du parc, point sans n° de caisse, point sans îlot | code îlot vide, jamais d'échec ; un point sans n° de caisse n'est pas indexé |
| CA par famille/îlot | ligne sans famille, sans quantité | code famille vide, quantité 0,000 |
| CA par famille/îlot | libellé famille porté par la 2e ligne seulement | libellé pris sur la PREMIÈRE ligne qui en porte un, jamais écrasé ensuite — sinon la cellule est libellée par hasard |
| CA par famille/îlot | remise de ligne : prix d'origine 7,00 → 6,00 × 2 | colonne REMISES à 2,00 |
| CA par famille/îlot | prix FORCÉ À LA HAUSSE | remise à 0,00, jamais négative — un forçage n'est pas une remise |
| CA par famille/îlot | ligne sans prix d'origine, sans prix, sans quantité | remise à 0,00 sur chacune des trois jambes |
| CA par famille/îlot | avantages fidélité du ticket | **absents de la colonne REMISES** : un avantage est porté par le ticket, pas par une ligne ; l'attribuer à une famille serait l'inventer |
| Détail des transactions | ligne annulée | **présente**, marquée `O` en dernière colonne : une ligne retirée du détail ne peut plus jamais être recomptée |
| Détail des transactions | ticket sans date de clôture, sans numéro, sans caisse | colonnes vides, la ligne sort quand même |
| Détail des transactions | ticket sans caissière, caissière sans login | `INCONNU` sur les deux jambes |
| Séparateur dans un libellé | libellé portant `;` | remplacé par une espace : le nombre de colonnes de la ligne ne bouge pas |
| Synthèse de clôture | ticket avec articles, ticket sans article | comptés séparément : ventes et non-ventes (ouvertures de tiroir) |
| Synthèse de clôture | trois tickets sur une caisse, un sur une autre | une ligne par caisse avec premier et dernier numéro, plus une ligne TOTAL (sans plage) |
| Synthèse de clôture | ticket sans total, sans numéro | repliés à 0,00 et à vide |
| Productivité | deux tickets d'une même prise de poste | une ligne : tickets, articles, CA, durée, articles/heure, panier moyen |
| Productivité | même caissière sur deux prises de poste | **deux lignes** : une productivité moyennée sur les deux ne dit rien d'aucune |
| Productivité | ticket rattaché à aucune session | prise de poste vide, durée 0, cadence 0 |
| Productivité | session encore ouverte | mesurée jusqu'à sa dernière vente, pas écartée : la journée est terminée même si la session est restée ouverte |
| Productivité | session sans ouverture, sans fin du tout, bornes inversées | durée 0 sur les trois jambes — jamais négative, le receveur divise par elle |
| Fidélité/TVA | cadence | fichier stampé `aaaamm`, lecture des tickets du MOIS, bornes du mois prises aux journées comptables de son premier et de son dernier matin |
| Fidélité/TVA | règlement fidélité sur un panier à deux taux | ventilé au prorata du TTC de chaque taux |
| Fidélité/TVA | ventilation non divisible (10,00 sur trois tiers égaux) | 3,33 + 3,33 + **3,34** : le dernier taux absorbe le résidu, le flux se reboucle exactement sur ce qui a été réglé |
| Fidélité/TVA | ticket réglé sans fidélité, règlement fidélité sans montant | ignorés |
| Fidélité/TVA | règlement fidélité sur un ticket sans base taxable | porté au taux zéro, pas jeté |
| Fidélité/TVA | mois sans aucun ticket clôturé | aucun fichier |
| Factures (plat) | facture avec son ticket | un enregistrement `H` par document, un `L` par ligne facturée, chaque champ à son offset |
| Factures (plat) | ligne annulée | absente : une ligne annulée n'a jamais été facturée |
| Factures (plat) | document dont le ticket n'est plus rattaché | **en-tête seul** : un en-tête manquant est une créance manquante |
| Factures (plat) | type, instant, adresse, totaux absents | blancs et zéros, chaque champ suivant toujours à son offset |
| Factures (plat) | civilité | prise sur la fiche client, blanche en son absence |
| Factures (plat) | taux de TVA | en points de base : `0.2000` devient `002000` |
| Factures (plat) | écotaxe | dernier champ de l'en-tête, en centimes (BO-10-04-14) |
| Format plat | texte plus long que son champ | **tronqué**, jamais laissé décaler la suite |
| Format plat | texte plus court, texte absent | complété d'espaces à la largeur exacte |
| Format plat | saut de ligne ou `;` dans une valeur | remplacé par une espace : un saut de ligne couperait un enregistrement en deux |
| Format plat | montant négatif | signe en première position du champ |
| Format plat | montant trop large pour son champ | écrit en **neuf** : un montant tronqué est un faux plausible, un champ de neuf est visiblement impossible |
| Format plat | montant négatif trop large | déborde une position plus tôt, le signe prenant un caractère |
| Factures ERP | document imprimé au moins une fois | colonne DUPLICATA à `O` — une réimpression n'est pas une seconde créance |
| Factures ERP | document sans totaux | 0,00 par colonne, ligne quand même émise |
| Factures ERP | journée sans aucun document | aucun des deux fichiers factures |
| TVA commandes | commande à deux taux | une ligne par taux avec son nombre de lignes, plus une ligne TOTAL comptant les commandes |
| TVA commandes | commande **sans lignes** | comptée entière au taux zéro : son total est un engagement que la déclaration doit voir |
| TVA commandes | ligne sans taux, ligne sans total | taux zéro, montant zéro |
| TVA commandes | journée sans commande | aucun fichier |
| TVA commandes | date de la commande | `orderDate`, date MÉTIER posée à la création — pas le `createdAt` d'audit, qui déménagerait la commande le jour où on la corrige |
| Panels | archive | un seul zip portant les trois membres `Scannels`, `Scvenmag`, `Tlogextr`, suffixés du jour de l'année sur trois chiffres |
| Panels | nom de l'archive | `MS.<PDV>.<jour>.zip` quand le nœud connaît son PDV |
| Panels | nœud sans PDV | retombe sur le nom du catalogue, jamais le mot « null » dans un nom sur lequel l'autre bord fait sa reconnaissance |
| Panels | ligne annulée | hors du panel de scan et hors de la synthèse magasin |

---

## H. Référentiel article [E]

Convention commune à tout `/admin/**` de ce groupe : `@RolesAllowed("ADMIN")`, cycle POST → 303 + notice one-shot (`noticeOk`), GET → 200. Identifiants de POST illisibles absorbés en `null` (→ « introuvable », pas de 400). **Matrice d'accès (chaque route, chaque verbe)** : anonyme → 401 (302 vers `/admin/login` sur GET HTML) ; authentifié non-ADMIN → 403 ; ADMIN → 200/303.

---

**H1/H2/H3/H11 Fiche & liste article** `/admin/articles` [E] — `AdminArticleResource`

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /admin/articles` | Liste nominale | 200, page de 100 articles (`PAGE_SIZE=100`) triés par EAN, formulaire critères + sélections |
| `GET` (filtre multicritère) | Critères en query string (`ArticleSearchCriteria.fromParams`) | 200, liste filtrée ; filtre sans résultat → liste vide propre |
| `GET` (sélection appliquée) | `?selection=<nom>` connu | 200, critères issus de `selection.criteria` (la sélection prime sur les critères libres) |
| `GET` (sélection appliquée) | `?selection=<nom>` inconnu | 200, `blankToNull` → sélection null → critères libres |
| `GET` (pagination) | `?page=0` ou négatif | clampé à 1 |
| `GET` (pagination) | `?page` > dernière | clampé à la dernière page |
| `GET` (pagination) | `?page=abc` (non entier, `@QueryParam int`) | 404 |
| `GET` colonne/fiche EGAlim | Article portant un code de régime connu | plafond affiché (`getCapFormatted() + " %"` ou « non plafonné ») |
| `GET` colonne EGAlim | Code de régime non déclaré | libellé « non plafonné » n'est pas rendu ; code absent de `egalimCaps` → cellule neutre, pas d'erreur |
| `GET /admin/articles/edit` | `?id=<connu>` | 200, fiche + historique prix + offres (priority>0) + prix courant |
| `GET /admin/articles/edit` | `?id=<inconnu>` | 200, `product=null` (form vide) |
| `GET /admin/articles/edit` | `?id=abc` (`@QueryParam Long`) | 404 |
| `POST /admin/articles/edit` | id illisible/absent/inconnu → `product==null` | 303 « Article introuvable. » (`noticeOk=false`) |
| `POST /admin/articles/edit` | Nominal | 303 « Fiche article « <ean> » enregistrée. » |
| `POST` parsing champs | `active`/`forbiddenToSale`/`variableWeight`/`codeOnScreen` présents | =true ; absents → false |
| `POST` parsing `ageRestriction` | ≤0, blanc ou illisible | → null |
| `POST` parsing `ageRestriction` | entier > 0 | conservé |
| `POST` parsing décimaux | `referenceWeight`/`referenceVolume` (scale 3), `giftCardAmount` (scale 2), virgule tolérée | normalisé ; blanc/illisible → null |
| `POST` attributs catalogue | Chaque `ProductAttributeCatalog.CATALOG` | présent → "true", absent → "false" |
| `POST` attributs custom | Valeur blanche | attribut retiré de la map ; valeur non blanche → posée |
| `POST /admin/articles/bulk-code` | `value=on` + critères | 303 « Code article à l'écran activé sur N article(s). » (N = tout le périmètre) |
| `POST /admin/articles/bulk-code` | sans `value` | 303 « Code article à l'écran désactivé sur N article(s). » |

---

**H5 Attributs d'article** `/admin/article-attributes` [E] — `AdminArticleAttributeResource`

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET` | Liste | 200, définitions triées par code |
| `POST` | Code ou libellé vide (code trim+MAJUSCULE) | 303 « Le code et le libellé de l'attribut sont obligatoires. » |
| `POST` | id posté mais définition inconnue | 303 « Attribut introuvable. » |
| `POST` | Code déjà porté par une autre définition | 303 « Un attribut porte déjà le code « <CODE> ». » |
| `POST` | Création nominale | 303 « Attribut « <CODE> » enregistré. » (code forcé en MAJUSCULE) |
| `POST` | Renommage nominal (id connu, code libre) | 303 « Attribut « <CODE> » enregistré. » |
| `POST /delete` | id absent/inconnu | 303 « Attribut introuvable. » |
| `POST /delete` | Nominal | 303 « Attribut « <CODE> » supprimé. » (valeurs déjà saisies conservées sur les fiches) |

---

**H7 Sélections d'articles** `/admin/article-selections` [E] — `AdminArticleSelectionResource`

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET` | Liste | 200, sélections triées par nom + catalogue attributs + types |
| `POST` | Nom vide | 303 « Le nom de la sélection est obligatoire. » |
| `POST` | id posté mais sélection inconnue | 303 « Sélection introuvable. » |
| `POST` | Nom déjà porté par une autre sélection | 303 « Une sélection porte déjà le nom « <nom> ». » |
| `POST` | Création/redéfinition nominale | 303 « Sélection « <nom> » enregistrée. » (critères normalisés en query string canonique) |
| `POST /delete` | id absent/inconnu | 303 « Sélection introuvable. » |
| `POST /delete` | Nominal | 303 « Sélection « <nom> » supprimée. » |

---

**H6 Groupes d'articles** `/admin/article-groups` [E] — `AdminProductFamilyResource`

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET` | Arbre (liste plate ordonnée par code, parent + nb membres) | 200 ; `?edit=<id>` ouvre le panneau avec membres triés par EAN |
| `GET` (pagination) | `page<1` → 1 ; `page>fin` → dernière ; `?page=abc` | clamp ; 404 sur non entier |
| `POST` (création) | code vide | 303 « Le code du groupe est obligatoire. » |
| `POST` (création) | code déjà pris | 303 « Un groupe porte déjà le code « <code> ». » |
| `POST` (création) | `parentId` inconnu | 303 « Groupe parent introuvable. » |
| `POST` (création) | Nominal | 303 « Groupe « <code> » créé. » |
| `POST` (mise à jour) | id inconnu | 303 « Groupe introuvable. » |
| `POST` (mise à jour) | `parentId == id` (auto-référence) | 303 « Un groupe ne peut pas être son propre parent. » |
| `POST` (mise à jour) | `parentId` inconnu | 303 « Groupe parent introuvable. » |
| `POST` (mise à jour) | Nominal (renommage/re-parent) | 303 « Groupe « <code> » mis à jour. » (code jamais changé) |
| `POST /delete` | id absent/inconnu | 303 « Groupe introuvable. » |
| `POST /delete` | Nominal | 303 « Groupe « <code> » supprimé. » |
| `POST /duplicate` | `sourceId` absent/inconnu | 303 « Groupe à dupliquer introuvable. » |
| `POST /duplicate` | `newCode` vide | 303 « Le code du nouveau groupe est obligatoire. » |
| `POST /duplicate` | `newCode` déjà pris | 303 « Un groupe porte déjà le code « <newCode> ». » |
| `POST /duplicate` | Nominal | 303 « Groupe « <src> » dupliqué en « <newCode> ». » (description, flags, membres copiés ; sous-arbre non copié) |
| `POST /articles` | id groupe inconnu | 303 « Groupe introuvable. » |
| `POST /articles` | Aucun article résolu | 303 « Aucun article sélectionné. » |
| `POST /articles` | Nominal (codes/EAN/PLU/plage EAN/groupe source/libellé, dédup `LinkedHashSet`) | 303 « N article(s) ajouté(s) au groupe « <code> ». » (token non résolu ignoré) |
| `POST /level-zero/articles` | Nominal | groupe `NIVEAU-0` créé à la volée si absent, puis « N article(s) ajouté(s)… » / « Aucun article sélectionné. » |
| `POST /articles/remove` | id groupe inconnu | 303 « Groupe introuvable. » |
| `POST /articles/remove` | `productId` inconnu | 303 « Article introuvable. » |
| `POST /articles/remove` | Nominal | 303 « Article « <name> » retiré du groupe. » |
| `POST /articles/toggle` | `productId` inconnu | 303 « Article introuvable. » |
| `POST /articles/toggle` | Nominal | bascule `active` → 303 « Article « <name> » réactivé. » / « … désactivé. » |

---

**H9 Nomenclatures** `/admin/nomenclatures` [E] — `AdminNomenclatureResource`

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET` | Page (schéma résolu ou 1er actif, niveau par niveau, fil d'Ariane) | 200 |
| `GET` (pagination) | `page` clampé entre 1 et totalPages | — |
| `POST /import` (multipart) | `scheme` vide/absent | 303 « Le code de la nomenclature est obligatoire. » |
| `POST /import` | Fichier illisible (complaints du reader) | 303 résumé des refus (3 premiers + « (+N autre(s)) ») |
| `POST /import` | Fichier sans ligne exploitable | 303 « Fichier sans aucune ligne exploitable. » |
| `POST /import` | Résultat non propre (`!outcome.isClean()`) | 303 résumé de `outcome.errors` |
| `POST /import` | Nominal | 303 « N nœud(s) créé(s), M mis à jour, K niveau(x). » |
| `POST /nodes` | schéma inconnu | 303 « Nomenclature introuvable. » |
| `POST /nodes` | code ou libellé vide | 303 « Le code et le libellé sont obligatoires. » |
| `POST /nodes` | longueur de code sans niveau | 303 « Code « <c> » : aucun niveau de <schéma> n'a des codes de <n> caractères. » |
| `POST /nodes` | nœud parent (préfixe) inexistant | 303 « Code « <c> » : le nœud parent « <p> » n'existe pas. » |
| `POST /nodes` | code déjà dans une autre nomenclature | 303 « Le code « <c> » appartient déjà à la nomenclature <autre>. » |
| `POST /nodes` | Création/renommage nominal | 303 « Nœud « <c> » créé. » / « … renommé. » |
| `POST /nodes/delete` | schéma inconnu / nœud introuvable | 303 « Nomenclature introuvable. » / « Nœud « <c> » introuvable. » |
| `POST /nodes/delete` | nœud avec enfants | 303 « Nœud « <c> » : N nœud(s) en dépendent, supprimez-les d'abord. » |
| `POST /nodes/delete` | nœud avec articles rattachés | 303 « Nœud « <c> » : N article(s) y sont rattachés, reclassez-les d'abord. » |
| `POST /nodes/delete` | Nominal | 303 « Nœud « <c> » supprimé. » |
| `GET /api/children` | schéma inconnu | 404, corps `[]` |
| `GET /api/children` | Nominal | 200, JSON `[{code,label,level,openable}]` |
| `GET /api/search` | schéma inconnu ou requête vide | 200 `[]` |
| `GET /api/search` | Nominal | 200, hits (≤50) avec `path` |
| `POST /api/nodes` | Selon `applyNode` | 200 `{ok,message}` ; schéma inconnu → 404 `{ok:false,"Nomenclature introuvable."}` |
| `POST /api/nodes/delete` | Selon `removeNode` | 200 `{ok,message}` ; schéma inconnu → 404 |
| `POST /api/nodes/move` | nœud premier niveau (rank 0) | 200 `{ok:false}` « … un nœud de premier niveau n'a pas de parent à changer. » |
| `POST /api/nodes/move` | cible pas au niveau X-1 | « … il ne peut être déposé que sur un nœud de niveau <X-1>. » |
| `POST /api/nodes/move` | parent introuvable | « Nœud « <p> » introuvable. » |
| `POST /api/nodes/move` | dépôt sous lui-même | « … on ne peut pas le déposer sous lui-même. » |
| `POST /api/nodes/move` | déjà sous ce parent | « Nœud « <c> » est déjà sous « <p> ». » |
| `POST /api/nodes/move` | niveau plein | « … aucun numéro libre sous « <p> ». » |
| `POST /api/nodes/move` | Nominal | 200 `{ok:true, code:<moved>}` « … déplacé en « <moved> » — N nœud(s) recodé(s). » (mention renumérotation si numéro pris) |
| `POST /api/nodes/recode` | nœud introuvable / longueur sans niveau | « Nœud « <c> » introuvable. » / « … aucun niveau … caractères. » |
| `POST /api/nodes/recode` | segment mauvaise longueur | « Nœud « <c> » : le numéro compte N caractère(s), pas M. » |
| `POST /api/nodes/recode` | code inchangé | « Nœud « <c> » porte déjà ce numéro. » |
| `POST /api/nodes/recode` | code cible déjà pris | « Le code « <r> » est déjà pris. » |
| `POST /api/nodes/recode` | Nominal | 200 `{ok:true, code:<recoded>}` « … renuméroté en « <r> » — N nœud(s) recodé(s). » |
| `POST /levels` | schéma inconnu | 303 « Nomenclature introuvable. » |
| `POST /levels` | longueur < 1/illisible | 303 « Niveau R : le nombre de caractères doit être un entier d'au moins 1. » |
| `POST /levels` | longueurs non strictement croissantes | 303 « Niveau R : les codes doivent s'allonger d'un niveau à l'autre, au-delà de N caractères. » |
| `POST /levels` | niveau resizé mais peuplé | 303 « Niveau R : N nœud(s) y sont posés ou en dessous — la longueur des codes ne peut plus changer. » |
| `POST /levels` | Nominal | 303 « N niveau(x) enregistré(s). » |
| `POST /levels/add` | schéma inconnu / longueur <1 | 303 « Nomenclature introuvable. » / « Le nombre de caractères doit être un entier d'au moins 1. » |
| `POST /levels/add` | longueur ≤ niveau précédent | 303 « Un niveau plus bas porte des codes plus longs : au-delà de N caractères. » |
| `POST /levels/add` | Nominal | 303 « Niveau R ajouté (N caractères). » (label par défaut « Niveau R ») |
| `POST /levels/delete` | schéma inconnu / rank introuvable | 303 « Nomenclature introuvable. » / « Niveau introuvable. » |
| `POST /levels/delete` | niveau peuplé (à ce rang ou en dessous) | 303 « Niveau R : N nœud(s) y sont posés ou en dessous — retirez-les d'abord. » |
| `POST /levels/delete` | Nominal | 303 « Niveau R retiré. » (rangs inférieurs décalés) |

---

**H8 Taux de TVA** `/admin/vat-rates` [E] — `AdminVatRateResource`

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET` | Page (régimes par numéro + nb prix qui les nomment) | 200 |
| `POST /save` | numéro absent/illisible/négatif | 303 « Le numéro de TVA doit être un entier positif. » |
| `POST /save` | taux vide/illisible/hors [0,100] | 303 « Le taux doit être un pourcentage entre 0 et 100. » |
| `POST /save` | id posté mais régime inconnu | 303 « Régime de TVA introuvable. » |
| `POST /save` | numéro déjà porté par un autre régime | 303 « Le numéro <n> est déjà porté par un autre régime. » |
| `POST /save` | Création nominale | 303 « Régime de TVA créé. » (pourcent → fraction scale 4 : 5,5 → 0.0550) |
| `POST /save` | Mise à jour nominale | 303 « Régime de TVA enregistré. » |
| `POST /delete` | id absent/inconnu | 303 « Régime de TVA introuvable. » |
| `POST /delete` | régime référencé par N prix | 303 « Régime utilisé par N prix : repointez-les avant de le retirer. » |
| `POST /delete` | Nominal (0 prix) | 303 « Régime de TVA retiré. » |

---

**H4 Régimes EGAlim** `/admin/egalim-regimes` [E] — `AdminEgalimRegimeResource`

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET` | Page (régimes par code) | 200 |
| `POST /save` | code vide ou > 30 caractères | 303 « Le code du régime est obligatoire et limité à 30 caractères. » |
| `POST /save` | plafond saisi mais illisible/hors [0,100] (`rawCap != null`) | 303 « Le plafond doit être un pourcentage entre 0 et 100, ou vide pour un régime non plafonné. » |
| `POST /save` | id posté mais régime inconnu | 303 « Régime EGAlim introuvable. » |
| `POST /save` | code déjà porté par un autre régime | 303 « Le code <CODE> est déjà porté par un autre régime. » |
| `POST /save` | plafond vide (`rawCap==null`) | accepté → régime non plafonné (`cap=null`) |
| `POST /save` | Création nominale | 303 « Régime EGAlim créé. » (code → MAJUSCULE, pourcent → fraction scale 4) |
| `POST /save` | Mise à jour nominale | 303 « Régime EGAlim enregistré. » |
| `POST /delete` | id absent/inconnu | 303 « Régime EGAlim introuvable. » |
| `POST /delete` | Nominal | 303 « Régime <CODE> retiré : les articles qui le portent ne sont plus plafonnés. » |

---

**H10 Codes postaux** `/admin/postal-codes` [E] — `AdminPostalCodeResource`

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET` | Liste + recherche (`?text=` sur code ou localité, DB `like`) | 200 |
| `GET` (pagination) | `page<1` → 1 ; `page>fin` → dernière ; `?page=abc` | clamp ; 404 sur non entier |
| `POST` | code ou localité vide | 303 « Code postal et localité obligatoires. » |
| `POST` | couple code/localité déjà existant (autre id) | 303 « Ce couple code / localité existe déjà. » |
| `POST` | Création/correction nominale (même code, localités ≠ → 2 lignes ; COUNTRY null accepté) | 303 « Code postal enregistré. » |
| `POST /delete` | id absent/inconnu | 303 « Code postal inconnu. » |
| `POST /delete` | Nominal (suppression réelle, pas de désactivation) | 303 « Code postal supprimé. » |

---

**H11 Pays et mention légale d'un taux de TVA (15/10)** `/admin/vat-rates`, `VatRate`, `RefExportService` [E] [R] [C] (BO-02-02-02, -03)

Un taux de TVA portait son numéro, sa valeur et son libellé. Il lui manquait les deux informations que la facture et le ticket réclament : **le pays** qui l'institue, et **la mention légale** qu'il oblige à imprimer (autoliquidation, exonération, régime particulier). Deux colonnes, administrables, diffusées — et lues par les documents (L35).

- **Le pays est normalisé à la saisie** : coupé à trois caractères, mis en majuscules, vide ramené à l'absence. Un référentiel qui accepte à la fois `fr`, `FR ` et `France` est un référentiel qu'aucun état ne peut regrouper.
- **Les deux colonnes entrent dans l'empreinte de diffusion** : un taux dont seule la mention change doit redescendre aux caisses, sinon le ticket imprime l'ancienne.
- **Rien n'est obligatoire** : la France n'exige aucune mention sur les taux ordinaires, et un champ obligatoire aurait forcé à inventer un texte.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `country(raw)` | `" fra nce"` / `"fr"` / `""` / null | tronqué majuscule / `FR` / null / null |
| Écran | enregistrement nominal | pays et mention persistés |
| `equals`/`hashCode` | mention modifiée | taux considéré différent |
| `toDto` | pays et mention absents | champs nuls, DTO valide |
| `canonical` | mention modifiée | empreinte modifiée |
| `applyVatRates` | DTO portant les deux champs | taux local aligné |

## I. Coffre et monnayeur [E]

Toutes les surfaces `@RolesAllowed("ADMIN")` : anonyme → 401, non-ADMIN → 403. Cycle back-office : POST → 303 + notice one-shot (`noticeOk`) ; identifiant illisible d'un POST absorbé en `null` (d'où « inconnu »). GET liste → 200 HTML.

**I1 Périodes du coffre** `/admin/safe/periods` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /` | Liste | 200 ; période ouverte en tête, solde, nb mouvements à pointer, libellé suggéré = `yyyy-MM` |
| `POST /` open | `label` vide/blanc | 303, `noticeOk=false`, « Libellé de période obligatoire. » |
| `POST /` open | Aucune période ouverte | 303 ok, « Période <label> ouverte. » |
| `POST /` open | Une période déjà ouverte (service rend null) | 303 false, « Une période est déjà ouverte. » |
| `POST /close` | Aucune période ouverte | 303 false, « Aucune période comptable ouverte. » |
| `POST /close` | Mouvements non validés (`countPending>0`) | 303 false, « Des mouvements de la période ne sont pas validés. » |
| `POST /close` | Période sans reste à pointer | 303 ok, « Période close. » ; solde figé, `closedBy`/`closedAt`/`endDate` posés |
| Accès | anonyme / non-ADMIN | 401 / 403 |

**I2 Journée du coffre** `/admin/safe/days` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /?day=` | Jour valide / blanc | 200 ; jour = valeur ou aujourd'hui ; sessions CLOSED du jour, ventilation par moyen, total, mouvements caisse, retiré |
| `GET /?day=abc` | Jour illisible | 200 ; retombe sur aujourd'hui |
| `POST /validate` | `id` illisible/inconnu | 303 false, « Mouvement inconnu. » (retour vers `?day=` posté) |
| `POST /validate` | `endorsedBy` vide/blanc | 303 false, « Nom ou badge du valideur obligatoire. » |
| `POST /validate` | Endossement fourni | 303 ok, « Mouvement validé. » ; `endorsedBy` écrit sur le CashMovement |
| `GET /export?day=` | Export | 200 `text/csv`, `filename="journee-<jour>.csv"`, en-tête `JOURNEE;MOYEN;MONTANT`, une ligne par moyen |
| Accès | anonyme / non-ADMIN | 401 / 403 |

**I3 Mouvements du coffre** `/admin/safe/movements` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /` | Liste | 200 ; mouvements de la période ouverte, solde, nb à pointer, types manuels, employés actifs, 3 lignes TVA |
| `POST /` record | `type` inconnu ou non manuel | 303 false, « Nature de mouvement inconnue. » |
| `POST /` record | `amount` null / ≤ 0 | 303 false, « Montant obligatoire et strictement positif. » |
| `POST /` record | `label` vide/blanc | 303 false, « Libellé obligatoire. » |
| `POST /` record | Aucune période ouverte (service rend null) | 303 false, « Aucune période comptable ouverte. » |
| `POST /` record | Valide (TVA : ligne blanche ignorée, ≤ 3 lignes) | 303 ok, « Mouvement enregistré. » |
| `POST /validate` | `id` illisible/inconnu | 303 false, « Mouvement inconnu. » |
| `POST /validate` | Déjà validé (service rend false) | 303 false, « Ce mouvement est déjà validé. » |
| `POST /validate` | Pointage effectif | 303 ok, « Mouvement validé. » |
| `POST /validate-all` | Boucle sur la période | 303 ok, « N mouvement(s) validé(s). » (N = déplacés vers validé) |
| `POST /correct` | Mouvement null ou non corrigeable (validé/période close) | 303 false, « Ce mouvement est validé ou sa période est close. » |
| `POST /correct` | Corrigeable | 303 ok, « Mouvement corrigé. » ; `amount` pris en `abs`, label trim, date si fournie |
| Accès | anonyme / non-ADMIN | 401 / 403 |

**I4 Inventaires du coffre** `/admin/safe/inventories` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /` | Liste | 200 ; inventaires récents, coupures billets/pièces, solde théorique |
| `POST /` count | Aucune coupure saisie (toutes blanches) | 303 false, « Aucune coupure comptée. » |
| `POST /` count | Comptage sans écart (`isBalanced`) | 303 ok, « Inventaire sans écart. » |
| `POST /` count | Comptage avec écart | 303 false, « Inventaire enregistré, écart de N €. » ; quantité négative ramenée à 0 ; qty=0 comptée |
| `POST /adjust` | `id` null / inventaire sans écart (service) | 303 false, « Cet inventaire ne présente pas d'écart à régulariser. » |
| `POST /adjust` | Écart mais aucune période ouverte | 303 false, « Aucune période comptable ouverte. » |
| `POST /adjust` | Régularisation écrite | 303 ok, « Écart régularisé. » (mouvement d'ajustement créé) |
| Accès | anonyme / non-ADMIN | 401 / 403 |

**I5 Pochettes** `/admin/safe/pouches` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /?day=` | Liste | 200 ; pochettes, sessions CLOSED du jour, total retiré, terminaux |
| `POST /` deposit | `pouchNumber` vide/blanc | 303 false, « Numéro de pochette obligatoire. » |
| `POST /` deposit | Numéro déjà existant | 303 false, « Cette pochette existe déjà : <n> » |
| `POST /` deposit | `amount` null / ≤ 0 | 303 false, « Montant obligatoire et strictement positif. » |
| `POST /` deposit | Aucune période ouverte (service) | 303 false, « Aucune période comptable ouverte. » |
| `POST /` deposit | Valide | 303 ok, « Pochette remise au coffre. » ; jour défaut = aujourd'hui |
| Accès | anonyme / non-ADMIN | 401 / 403 |

**I6 Dépôts en banque** `/admin/safe/bank-deposits` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /` | Liste | 200 ; dépôts, pochettes en coffre disponibles, total dispo |
| `POST /` deposit | `bagNumber` vide/blanc | 303 false, « Numéro de sac obligatoire. » |
| `POST /` deposit | Numéro de sac déjà existant | 303 false, « Ce numéro de sac existe déjà : <n> » |
| `POST /` deposit | Aucune pochette cochée (ou toutes hors coffre) | 303 false, « Aucune pochette sélectionnée. » |
| `POST /` deposit | Pochettes ok mais aucune période ouverte (service) | 303 false, « Aucune période comptable ouverte. » |
| `POST /` deposit | Valide | 303 ok, « Remise en banque enregistrée. » ; montant = somme des pochettes, jamais saisi |
| `GET /{id}/bordereau` | Dépôt inconnu / id null | 404 |
| `GET /{id}/bordereau` | Dépôt connu | 200 `text/plain`, `filename="bordereau-especes-<sac>.txt"`, ligne par pochette + TOTAL EUR |
| `GET /{id}/versement` | Dépôt inconnu / id null | 404 |
| `GET /{id}/versement` | Dépôt connu | 200 `text/plain`, `filename="versement-especes-<sac>.txt"`, nombre de pochettes + montant versé EUR |
| Accès | anonyme / non-ADMIN | 401 / 403 |

**I7 Commandes de monnaie** `/admin/safe/coin-orders` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /` | Liste | 200 ; commandes récentes, en attente de livraison, dénominations |
| `POST /` order | `orderNumber` vide/blanc | 303 false, « Numéro de demande obligatoire. » |
| `POST /` order | Numéro déjà existant | 303 false, « Ce numéro de demande existe déjà : <n> » |
| `POST /` order | Aucune quantité > 0 saisie | 303 false, « Aucune quantité demandée. » |
| `POST /` order | Valide | 303 ok, « Demande de monnaie enregistrée. » ; statut REQUESTED, `requestedBy` posé |
| `POST /receive` | `id` null / commande inconnue | 303 false, « Demande inconnue. » |
| `POST /receive` | Commande n'attend plus de livraison (service) | 303 false, « Cette demande n'attend plus de livraison. » |
| `POST /receive` | Aucune quantité reçue saisie | 303 false, « Aucune quantité reçue n'a été saisie. » |
| `POST /receive` | Reçue mais aucune période ouverte | 303 false, « Aucune période comptable ouverte. » |
| `POST /receive` | Réception conforme (`isFullyFulfilled`) | 303 ok, « Réception enregistrée, conforme à la demande. » |
| `POST /receive` | Réception différente | 303 ok, « Réception enregistrée, différente de la demande. » |
| `POST /cancel` | `id` null / non en attente (reçue, annulée…) | 303 false, « Cette demande ne peut plus être annulée. » |
| `POST /cancel` | En attente | 303 ok, « Demande annulée. » ; statut CANCELLED |
| `GET /{id}/slip` | Commande inconnue / id null | 404 |
| `GET /{id}/slip` | Commande connue | 200 `text/plain`, `filename="demande-monnaie-<n>.txt"`, ligne par dénomination + TOTAL EUR |
| Accès | anonyme / non-ADMIN | 401 / 403 |

**I8 Journal du coffre** `/admin/safe/journal` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /?from=&to=` | Consultation | 200 ; lecture seule (aucun POST) ; solde d'ouverture, solde de clôture, total débit/crédit, solde courant ligne à ligne |
| `GET /` | `from` blanc/illisible | Début = début de la période ouverte, sinon 1970-01-01 |
| `GET /` | `to` blanc/illisible | Fin = aujourd'hui |
| `GET /export?from=&to=` | Export | 200 `text/csv`, `filename="journal-coffre-<from>-<to>.csv"`, en-tête `DATE;NATURE;LIBELLE;REFERENCE;DEBIT;CREDIT;SOLDE;ETAT` ; ETAT = `Valide`/`A pointer` |
| Accès | anonyme / non-ADMIN | 401 / 403 |

**I9 Monnayeurs** `/admin/coin-machines` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /` | Liste | 200 ; monnayeurs ordonnés, total tenu ; I10 : total = somme des `declaredAmount`, zéro monnayeur → total 0,00, aucune division par zéro |
| `POST /` save | `code` vide/blanc | 303 false, « Code du monnayeur obligatoire. » |
| `POST /` save | Code déjà pris par un autre | 303 false, « Ce code de monnayeur existe déjà : <code> » |
| `POST /` save | Création / mise à jour (même id) | 303 ok, « Monnayeur enregistré. » ; `active` = case cochée |
| `POST /count` | `id` null / monnayeur inconnu | 303 false, « Monnayeur inconnu. » |
| `POST /count` | `countedAmount` null / < 0 | 303 false, « Montant compté obligatoire et positif. » |
| `POST /count` | Comptage conforme (`isReconciled`) | 303 ok, « Comptage conforme au montant tenu par le logiciel. » |
| `POST /count` | Comptage avec écart | 303 false, « Comptage enregistré, écart de N €. » ; total tenu inchangé |
| `POST /align` | Monnayeur null / jamais compté (`countedAmount==null`) | 303 false, « Aucun comptage à reprendre. » |
| `POST /align` | Réalignement | 303 ok, « Montant tenu par le logiciel réaligné. » ; `declaredAmount = countedAmount` |
| `POST /load` | `amount` null / == 0 | 303 false, « Montant de chargement obligatoire et non nul. » |
| `POST /load` | Code inconnu (service rend null) | 303 false, « Monnayeur inconnu. » |
| `POST /load` | Chargement (négatif = retrait) | 303 ok, « Chargement enregistré. » |
| `GET /export` | Export | 200 `text/csv`, `filename="monnayeurs.csv"`, en-tête `CODE;LIBELLE;CAISSE;ACTIF;TENU;COMPTE;ECART` ; ACTIF = `Oui`/`Non` |
| Accès | anonyme / non-ADMIN | 401 / 403 |

---

## J. Rapports [E]

`ReportResource` — `@RolesAllowed({"ADMIN","MANAGER"})` : anonyme → 401, ni ADMIN ni MANAGER → 403. Montants rendus pour l'humain (`ReportFormat`) : `1 234,50 €` (espace fine insécable, virgule), pourcent `0,0 %`, entiers groupés ; dates `dd/MM/yyyy`.

**J1 Catalogue des rapports** `/admin/reports` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /` | Liste | 200 HTML ; rapports groupés par famille, découverts par CDI, triés par `family.ordinal` puis code exigence |
| `GET /` | Familles affichées | Seules les familles NON vides : SALES, États caissières, Règlements, TVA — la famille CUSTOMERS (« Clients ») existe mais n'a aucun rapport → jamais affichée |
| `GET /` | Nombre de rapports | 11 |
| Accès | anonyme / rôle insuffisant | 401 / 403 |

**J2 Les onze rapports** `/admin/reports/{code}` [E]

| Rapport (code) | Famille | Exigence | Graphique | Option propre |
| --- | --- | --- | --- | --- |
| État ventes par famille (`family-sales`) | Ventes | BO-06-04-01 | oui, col. 2 | — |
| État ventes horaires (`hourly-sales`) | Ventes | BO-06-04-07 | oui, col. 2 | « Filtre article (code famille ou début d'EAN) » |
| État productivité horaire (`hourly-productivity`) | Ventes | BO-06-04-06 | oui, col. 1 | « Largeur de tranche » (15/30/60, défaut 60) |
| État caissière (`cashiers`) | États caissières | BO-06-06-03 | oui, col. 3 | — |
| État annulation (`cancellations`) | États caissières | BO-06-06-04 | non (-1) | — |
| État des forçages prix (`price-forcings`) | États caissières | BO-06-06-10 | non | « Sens du forçage » (all/down/up, défaut all) |
| État des codes raison (`reason-codes`) | États caissières | BO-06-06-07 | non | — |
| État remboursement (`refunds`) | États caissières | BO-06-06-05 | non | — |
| État des tickets en attente (`pending-tickets`) | États caissières | BO-06-06-01 | non | recall/non recall |
| État des règlements (`settlements`) | Règlements | BO-06-06-09 | oui, col. 4 | — |
| État TVA par nomenclature (`vat-by-family`) | TVA | BO-06-07-01 | non | — |

Pour chaque rapport : données semées → colonnes, totaux (ligne `totals`) et graphique conformes à des valeurs absolues.

**J3 Écran d'un rapport (mécaniques communes)** `/admin/reports/{code}` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /{code}` | Rapport connu | 200 HTML ; définition, critères, data, colonnes, en-tête période, chart |
| `GET /{code}` | Code inconnu | 404 « Rapport inconnu : <code> » |
| `?period=TODAY` | Journée en cours | Bornes = minuit aujourd'hui → minuit demain |
| `?period=DAY&reference=` | Jour | [jour 00:00, jour+1 00:00[ |
| `?period=WEEK` | Semaine | Lundi précédent-ou-même → +1 semaine |
| `?period=MONTH` | Mois | 1er du mois → +1 mois |
| `?period=YEAR` | Année | 1er janv. → +1 an |
| `?period=RANGE&from=&to=` | Plage | [from 00:00, to+1 00:00[ (borne haute exclusive) |
| `?period=` inconnu/blanc | Valeur non enum | Retombe sur TODAY (pas d'erreur) |
| `?reference=abc` / `from=abc` | Date illisible | Lue comme absente (pas d'erreur), pas de refus serveur |
| Période | En-tête | TODAY/DAY → « <label> : <jour> » ; RANGE jour ≠ → « du <j1> au <j2> » |
| RANGE début > fin | Plage inversée | Intervalle vide → rapport à état vide (pas de 500) ; aucun refus HTTP côté ressource |
| `?page=` | Bornage | page < 1 → 1 ; page > fin → dernière ; null → 1 ; `PAGE_SIZE=100`, `hasPrev`/`hasNext` cohérents |
| Chart | Rapport à colonne tracée, 1..40 lignes | SVG inline rendu, `hasChart=true` |
| Chart | Rapport sans colonne tracée (-1) | `hasChart=false`, aucun SVG |
| Chart | > 40 lignes (`MAX_BARS`) | Pas de chart (`available=false`) |
| Chart | Toutes valeurs ≤ 0 | `<p class="report-chart-empty">Aucune valeur à représenter sur la période.</p>` |
| État vide | Journée sans vente | État vide explicite, pas de ligne à zéro trompeuse ni division par zéro sur panier moyen |
| Critères | filtre caisse/opérateur/famille | Total filtré ≤ total global ; somme des filtres disjoints = global ; critère absent = non ajouté (pas un joker) |
| Options | `hourly-productivity ?option=15/30/60` | Largeur de tranche appliquée ; autre valeur → 60 |
| Options | `price-forcings ?option=down/up/all` | down = démarques seules, up = majorations seules, all = les deux |
| Options | `hourly-sales ?option=<code fam/EAN>` | Filtre article appliqué |
| Accès | anonyme / rôle insuffisant | 401 / 403 |

**J4 Export d'un rapport** `/admin/reports/{code}/export?format=` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `?format=csv` | CSV | 200 `text/csv; charset=UTF-8`, `filename="<code>-<yyyyMMdd>.csv"` ; BOM, séparateur `;`, 1re ligne = libellé + période, en-têtes, une ligne par ligne + totaux ; valeurs à la française |
| `?format=xlsx` | XLSX | 200 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `.xlsx` ; classeur ouvrable, une feuille, NOMBRES BRUTS (pas via ReportFormat) + format de cellule |
| `?format=pdf` | PDF | 200 `application/pdf`, `.pdf` ; montants écrits comme à l'écran (`1 234,50 €`) |
| Concordance | csv/xlsx/pdf du même rapport | Mêmes totaux au centime |
| `?format=` absent/blanc | Writer null | 404 « Format de rapport inconnu : <format> » |
| `?format=xls` | Format inconnu | 404 « Format de rapport inconnu : xls » |
| `/{code}/export` | Code inconnu | 404 « Rapport inconnu : <code> » (levé avant le writer) |
| Export vs écran | Pagination | L'export sort le rapport ENTIER (jamais la page visible) |
| Accès | anonyme / rôle insuffisant | 401 / 403 |

---

**J5 Régimes de TVA — le référentiel des taux devient un état (15/10)** `/admin/reports`, `VatRegimeReport` [E] (BO-02-02-03)

Le paramétrage des taux existait, l'état qui les montre n'existait pas : un comptable qui demande « quels régimes utilisons-nous, et sur combien d'articles » devait ouvrir l'écran d'administration et compter à l'œil. Le cadre d'états réduit la réponse à une classe : code, taux, libellé, pays, mention légale, articles tarifés, exonéré.

- **Le comptage se fait côté base**, un `count(p)` par taux sur les prix qui le portent. Un état qui chargerait les prix pour les compter serait un état qui tombe le jour où le référentiel grossit.
- **« Exonéré » est une lecture, pas une colonne stockée** : un taux à zéro est un régime d'exonération, et c'est ce que la colonne dit.
- Famille `TAX` : l'état se range avec la ventilation de TVA, pas avec les ventes.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `code` / `family` | — | `vat-regimes`, `TAX` |
| `columns` | — | sept colonnes, dans l'ordre |
| `rows` | aucun taux | aucune ligne |
| `rows` | taux sans pays ni mention | cellules vides, jamais absentes |
| `rows` | taux à 0 / taux positif | exonéré oui / non |
| `rows` | prix portant le taux | compte exact |

**J6 Taux de porteurs de carte — par caissière ou pour le magasin (15/10)** `/admin/reports`, `FidelityCardRateReport` [E] (BO-06-06-11)

Combien de tickets portent une carte de fidélité ? La question se pose à deux échelles, et les deux intéressent : le magasin veut son taux, le responsable de caisses veut savoir **qui** demande la carte. Une option, pas deux états : `BY_CASHIER` par défaut, `BY_STORE` pour le cumul.

- **Le dénominateur est le ticket clos**, jamais le brouillon : un panier abandonné n'a pas refusé de carte, il n'a rien dit.
- **Le taux est calculé une fois, côté état**, et sort en pourcentage formaté : les écrits ne refont pas la division.
- Famille `CASHIERS`, période et caisses héritées du cadre : l'état ne redéfinit aucun filtre.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `optionChoices` | — | `BY_CASHIER` (défaut) et `BY_STORE` |
| `rows` | aucun ticket | aucune ligne |
| `rows` | zéro ticket pour une caissière retenue | taux 0,0 %, pas de division par zéro |
| `rows` | `BY_CASHIER` | une ligne par caissière, badge et nom |
| `rows` | `BY_STORE` | une seule ligne cumulée |
| `rows` | option inconnue | repli sur le défaut |

**J7 TVA des avantages fidélité — une répartition au prorata (15/10)** `/admin/reports`, `FidelityVatReport` [E] (BO-06-07-02)

Un avantage fidélité payé sur un panier à trois taux n'appartient à aucun des trois : il faut le **répartir**. L'état le fait au prorata du TTC de chaque seau de ventilation du ticket, le seul partage que les données du ticket permettent de justifier ligne par ligne.

- **Le prorata se calcule par ticket**, jamais sur le cumul de la période : deux tickets de compositions différentes n'ont pas la même clé de répartition.
- **Un ticket à TTC nul ne répartit rien** plutôt que de diviser par zéro, et son avantage reste visible dans le total.
- **L'état se ferme sur une ligne Total** : un état de TVA dont les lignes ne se somment pas à l'œil n'est pas un état de TVA.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `columns` | — | huit colonnes, dont la part répartie |
| `rows` | aucun avantage | aucune ligne, pas même le total |
| `rows` | un taux | tout l'avantage sur ce taux |
| `rows` | trois taux | parts au prorata du TTC de chaque seau |
| `rows` | ticket à TTC nul | aucune part, total inchangé |
| `rows` | plusieurs tickets | somme des parts = somme des avantages |

**J8 Promotions par caissier — ce que chaque poste a accordé (15/10)** `/admin/reports`, `PromotionCashierReport` [E] (BO-06-08-01)

Les lignes d'avantage fidélité portent déjà le code de la règle qui les a produites ; personne ne les avait jamais regroupées par règle **et** par caissière. C'est exactement ce que l'exigence demande, et cela ne coûte qu'un regroupement.

- **Deux clés, pas une** : la règle sans le poste ne dit pas où regarder, le poste sans la règle ne dit pas quoi regarder.
- **Une règle sans code reste comptée** sous un libellé explicite : une promotion anonyme est une promotion qui a tout de même été accordée.
- Famille `SALES` : c'est de la vente, pas de la fiscalité.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `columns` | — | huit colonnes, dont le nombre et le montant |
| `rows` | aucune ligne d'avantage | aucune ligne |
| `rows` | une règle, deux caissières | deux lignes |
| `rows` | deux règles, une caissière | deux lignes |
| `rows` | code de règle absent | ligne conservée, libellé de repli |
| `rows` | caissière absente du ticket | ligne conservée, poste vide |

## K. Journal et tableau de bord [E] [R]

Matrice d'accès (par surface) : `/admin/journal` et ses onglets (`/functional`, `/movements`, `/ticket/{id}`, `/export`) sont **ADMIN ET MANAGER** ; `/dashboard` et `/dashboard-data`, `/dashboard/ack/{id}`, `/dashboard/force-close/{id}` sont **MANAGER/ADMIN** ; `POST /api/supervisor/call` est **PermitAll** mais gardé par rôle `store` + jeton.

**K1 Journal transactionnel** `GET /admin/journal` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Accès | Anonyme | 302 redirection `/admin/login` |
| Accès | MANAGER | 200 page transactionnelle |
| Accès | ADMIN | 200 |
| Accès | Rôle sans ADMIN/MANAGER (ex. CASHIER) | 403 |
| Périmètre | Aucun filtre | Seuls tickets CLOSED + CANCELLED (jamais les drafts) |
| Filtre texte | Fragment | Match sur `ticketNumber`, `fidelityCard` ou libellé de ligne non annulée |
| Filtre caissière | min/max | `cashier.badgeId` borné |
| Filtre TPV | min/max | `terminalId` borné |
| Filtre transaction | min/max | `ticketNumber` borné |
| Filtre montant TTC | min/max | `totalIncludingTax` borné |
| Filtre montant brut | min/max | `TTC + coalesce(remise globale,0)` borné |
| Filtre heure | from/to | `extract(hour from creationDate)` borné |
| Filtre modes paiement | plusieurs cochés | OR sur `type(payment)` ; clé inconnue ignorée |
| Filtre dates | from/to | `creationDate` borné |
| Filtre PLU article | min/max | `exists` ligne non annulée, `coalesce(l.plu, pr.plu)` (PLU effectif) |
| Filtre familles | plage min/max ET/OU multi-select | `familyCode` snapshot ligne non annulée ; plage et liste indépendantes |
| Filtre TVA | taux | `exists` ligne non annulée `vatRate` |
| Filtre réduction | min/max | `exists` ligne non annulée, `modifierType in (REMISE, DISCOUNT)` seulement (jamais FORCE_PRICE) |
| Filtre n° autorisation | min/max | `exists` CardPayment `authorizationNumber is not null` (exclut le dégradé) |
| Filtre article remboursé | PLU + montant min/max | `exists` sur Refund lié `originalTicketId`, PLU effectif de la ligne d'origine |
| Filtre article annulé | PLU + montant min/max | `exists` ligne `cancelled = true` |
| Flags | CANCELLED, DISCOUNT, RETURN, NEGATIVE, ZERO_PRICE, UNKNOWN_ITEM, CANCELLED_ARTICLE, VOUCHER, CARD (carte+backup), DEGRADED, DEGRADED_MANUAL | Une condition par flag coché |
| Tri | colonne + sens | `order by <col> asc|desc, t.id asc` (tiebreak id) |
| Pagination | page < 1 | ramenée à 1 |
| Pagination | page > dernière | ramenée à la dernière |
| Pagination | taille | 100 lignes/page ; total toujours compté (pas de troncature silencieuse) |
| Résultat vide | filtre sans match | Page vide propre, total 0 |

**K1b Onglet fonctionnel** `GET /admin/journal/functional` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Accès | Anonyme / non-pilote | 302 login / 403 |
| Filtre types | plusieurs `eventType` | OR ; nom inconnu ignoré |
| Filtre caissière | min/max | `operatorBadgeId` borné ; événement sans opérateur (badge null) exclu |
| Filtre TPV / dates / texte | min/max, from/to, fragment sur `detail` | conditions ANDées |
| Tri | — | `eventDate desc, id asc` |

**K1c Onglet mouvements** `GET /admin/journal/movements` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Filtre types | plusieurs `MovementType` | OR |
| Filtre montant | min/max | `m.amount` borné |
| Filtre caissière | min/max | `m.cashier.badgeId` borné ; mouvement sans caissière exclu |
| Filtre TPV / dates | min/max, from/to | ANDées |
| Tri | — | `movementDate desc, id asc` |

**K2 Détail d'un ticket** `GET /admin/journal/ticket/{id}` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Détail | id connu | Ticket rendu : lignes, paiements, totaux TTC/HT/TVA, caissière, magasin, carte fidélité |
| Lignes annulées | ticket avec lignes annulées | Lignes marquées `cancelled=true`, présentes (pas supprimées) |
| Introuvable | id inconnu | `ticket = null`, page rendue (pas de 500) |

**K3 Export CSV** `GET /admin/journal/export` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Export | filtres courants | `text/csv`, `Content-Disposition: attachment; filename="journal.csv"` |
| En-tête | — | `TPV;Transaction;Caissiere;Date;Heure;Montant;Articles;Ecole;Autonome` |
| Contenu | — | Même requête que l'écran mais tout le jeu (fenêtres de 1000) ; montants virgule FR, dates `dd/MM/yyyy`, heures `HH:mm` |

**K4 Tableau de bord (données)** `GET /dashboard-data` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Accès | Anonyme | 401 |
| Accès | MANAGER | 200 JSON |
| Accès | ADMIN | 200 |
| Accès | rôle sans droit | 403 |
| CA du jour | `revenue` | somme `totalIncludingTax` des CLOSED à `closingDate ≥ minuit`, virgule FR |
| Tickets | `ticketCount` | count des CLOSED du jour |
| Panier moyen | `averageBasket` | `revenue / ticketCount` scale 2, ou `0,00` si 0 ticket |
| CA par caisse | `perRegister` | par `terminalId`, CA + tickets, ordonné |
| Taux d'annulation | `cancellationRate` | `cancelled*100/(cancelled+ticketCount)` scale 1 ; CANCELLED comptés à la **création**, CA à la **clôture** (deux instants) ; `0,0` si dénominateur nul |
| Remboursements | `refundTotal`/`refundCount` | Refund à `creationDate ≥ minuit` |
| Top ventes | `topSales` | top 10 lignes positives non annulées, group `productLabel`, quantité desc |
| Sessions actives | `sessions` | sessions OPEN ; `closing=true` si une fermeture forcée est déjà en attente |
| Fin de période | `periodCountdown`/`periodEnd` | compte à rebours ; -1 = ne rien dire |
| Appels superviseur | alertes désactivées (`dashboardAlertsEnabled=false`) | `calls` = liste vide |
| Appels superviseur | alertes activées + appels en attente | `calls` renseignés (id, terminal, operator, reason, time) |
| Vide | nœud fraîchement démarré | `revenue 0,00`, `ticketCount 0`, `averageBasket 0,00`, `cancellationRate 0,0`, listes vides (K8) |

**K5 Page dashboard / ack / force-close** [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Page | `GET /dashboard` MANAGER/ADMIN | 200 (gabarit admin-layout) |
| Page | anonyme | 302 login |
| Acquittement | `GET /dashboard/ack/{id}` MANAGER/ADMIN | 204, appel retiré du registre |
| Fermeture forcée | `POST /dashboard/force-close/{terminalId}` MANAGER/ADMIN | 204, commande `CLOSE_SESSION` déposée (émetteur = nom complet, motif « Fermeture demandée depuis le back-office ») ; session affiche `closing=true` tant qu'en attente (K6) |

**K7 Réception appel superviseur** `POST /api/supervisor/call` [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Hors rôle | nœud ≠ store | 403 « Ce nœud n'a pas le rôle store », aucun bandeau |
| Jeton | `pos.sync.token` configuré, `X-Sync-Token` faux/absent | 401 « Jeton invalide », aucun appel enregistré (K7) |
| Jeton | jeton absent de la config (démo) | aucune authentification exigée |
| Succès | rôle store + bon jeton | 200 « OK », appel ajouté au registre (bandeau si alertes activées) |

**K8 Événements techniques de tenue de poste (chantier B — aligné sur impos)** `JournalService` / `journal.html` [E]

Alignement sur impos (source de vérité) : `TechnicalEvent.EventType` reçoit exactement les deux valeurs qu'impos a ajoutées après `GS1_DECODED` — `PAID_TICKET_CANCELLED` (EXP-49) et `SOGEC_COMPENSATION` (EXP-58). Les 9 événements que j'avais inventés (déverrouillage forcé, fin de journée forcée, till closed/reopened, session superviseur, plafond tiroir, validation d'écart, refus dernier article) sont RETIRÉS : impos ne les émet pas — il journalise ces gestes sous des types existants (`REGISTER_UNLOCKED` détaillé, `SESSION_CLOSED`, `ENDORSEMENT_GRANTED/DENIED`) ou pas du tout (plafond tiroir = alerte écran non journalisée). La recherche/filtre/affichage du journal étant génériques, les deux nouveaux types sont cherchables sans autre code.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Filtre | case à cocher par type | `PAID_TICKET_CANCELLED` et `SOGEC_COMPENSATION` apparaissent (générique `EventType.values()`) |
| Recherche | `eventType=PAID_TICKET_CANCELLED` | journal filtré sur l'annulation d'un ticket payé (EXP-49) |
| Recherche | `eventType=SOGEC_COMPENSATION` | compensation d'un bon SOGEC (EXP-58) |
| Gestes réutilisant l'existant | déverrouillage forcé (EXP-14), écart (EXP-82), session superviseur (EXP-33) | cherchés via `REGISTER_UNLOCKED` (détail), `SESSION_CLOSED`, `ENDORSEMENT_GRANTED/DENIED` comme impos — pas de type dédié |
| Reste à faire | objets persistés structurés (apport par coupure, comptage moyens papier, bordereau autonome) | impos les porte en JSON sur les payloads MOVEMENT/close, pas en entités/`EntityType` dédiés — rien à ingérer de nouveau |

**K9 Canal montant back-office ↔ caisse (chantier C — aligné sur impos)** `DashboardResource#receiveCall` [E] [R]

Canal MONTANT (EXP-19/84) : la caisse POST `/api/supervisor/call` en JSON `{terminalId, operator, reason, message?}` (jeton `X-Sync-Token`). Le `reason` vient de la liste administrée `supervisor.service-reasons` (EXP-84) ; le `message` libre (EXP-19) est désormais porté par la `CallDto` et exposé sur le tableau de bord. Canal DESCENDANT (EXP-20) : impos ne lit PAS un type de commande inconnu dans le poll `/api/commands` ; le message BO se pousse par `POST /api/bo-message` sur la caisse. J'ai donc RETIRÉ `RegisterCommand.DISPLAY_MESSAGE` et l'écran `/admin/register-messages` (orphelins de fil) — voir « reste à faire ».

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Montant | `POST /api/supervisor/call` avec `message` | `message` porté par `CallDto` → `SupervisorCallRegistry.add(...,message)` → affiché sur le dashboard (EXP-19) |
| Montant | `reason` de la liste administrée | `supervisor.service-reasons` (clé alignée sur impos) fournit les motifs à la caisse ; vide → saisie libre seule (EXP-84) |
| Montant | hors rôle store / mauvais jeton | 403 / 401, rien enregistré |
| Reste à faire (descendant, EXP-20) | pousser le message BO sur la caisse | `POST /api/bo-message` (form `text`/`question`/`options`) — nécessite l'adressage des caisses côté nœud (les caisses interrogent le nœud, pas l'inverse) — signalé |

**K10 Relais vers les services tiers (endpoints exposés à la caisse)** `GiftCardResource`, `RestoFlashResource`, `SmsResource`, `ExternalRelayService` [E]

Le nœud expose à la caisse trois endpoints (garde rôle store + jeton `X-Sync-Token`) qui relaient vers le service tiers configuré, tolérants : URL amont absente ou en échec → repli sûr, jamais d'erreur bloquant la vente.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Carte cadeau (EXP-66) | `GET /api/giftcard/{cardNumber}` | relais vers `pos.giftcard.issuer-url` → `{valid,balance}` ; amont absent → `{valid:false,balance:0}` ; 401/403 sur garde |
| Resto Flash (EXP-67) | `POST /api/restoflash/confirm` `{token,amount}` | relais vers `pos.restoflash.url` ; amont absent → `{accepted:false}` ; 401/403 sur garde |
| SMS (EXP-48) | `POST /api/sms/send` `{ticket,phone}` | relais vers `pos.sms.gateway-url` ; amont absent → `{sent:false}` ; 401/403 sur garde |
| Relais | amont configuré / en échec (exception) | passe le statut+corps amont / repli non-atteint (jamais d'exception) |

---

**K11 L'aval donné depuis le back-office, et l'article inconnu qui appelle (15/10)** `/dashboard`, `EndorsementService`, `RemoteCommandService` [E] [R] (BO-04-02-01, -02, -06)

La demande de service savait être refusée depuis le back-office (L34) mais pas **accordée** : le responsable devait marcher jusqu'à la caisse et y taper son code. Le canal descendant existait déjà ; il manquait l'ordre, et la caisse qui le consomme.

- **Un aval accordé à distance est un aval**, pas un contournement : il est journalisé comme tel, nommé par le responsable qui l'a donné depuis l'écran, et il est **à un coup** — consommé, il disparaît, et le geste suivant redemande une autorisation.
- **Les rôles habilités deviennent un paramètre** (`auth.endorsement-roles`) au lieu des deux rôles câblés dans le code. Vide, illisible ou absurde : `MANAGER;ADMIN`, c'est-à-dire la conduite d'avant.
- **Un article inconnu peut appeler le back-office** (`supervision.unknown-item-alert`, inactif par défaut) : scanné et introuvable, il remonte une demande de service au même titre qu'un appel superviseur, au lieu de n'être lisible qu'après coup au journal.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `/dashboard/call/{id}/authorise` | demande en attente | marquée autorisée, ordre émis au nom du responsable |
| `/dashboard/call/{id}/authorise` | demande inconnue | 404, aucun ordre |
| `remotelyGranted` | aval armé / absent | motif rendu puis effacé / null |
| `endorses` | liste nominale / vide / illisible | rôles lus / repli / repli |
| `validateEndorsement` | aval distant présent | action exécutée sans saisie de code |
| `addUnknownItem` | paramètre actif / inactif | demande remontée / rien |

**K12 Les indicateurs du tableau de bord deviennent un paramètre (15/10)** `/dashboard`, `DashboardIndicator`, `DashboardIndicatorService` [E] [R] (BO-04-03-01, -03, -15)

Les cinq cumuls du tableau de bord — CA, tickets, panier moyen, taux d'annulation, remboursements — sont écrits en JPQL et identiques partout. Les trois exigences demandent le contraire : **que le magasin choisisse ce qu'il regarde.** Les cumuls fixes restent ; une seconde rangée de tuiles, administrée, s'ajoute en dessous.

- **Un catalogue de onze indicateurs**, dont neuf sont un comptage du journal du jour : ajouter un indicateur sur le journal, c'est une constante. Les deux autres — le solde des mouvements de caisse et le nombre de caisses muettes — se calculent autrement, et le `switch` du service est **exhaustif sans arm par défaut** : une constante que personne n'a appris à calculer ne compile pas, ce qui vaut mieux qu'une tuile à zéro pour l'éternité.
- **Le paramètre porte l'ordre d'affichage**, pas seulement la sélection : `dashboard.indicators` est une liste à points-virgules lue de gauche à droite.
- **Un code inconnu est ignoré, pas refusé.** Un paramètre tapé à la main ne doit pas pouvoir faire tomber l'écran qu'un responsable regarde toute la journée ; une tuile manquante le dit plus clairement qu'une page d'erreur. Vide : aucune tuile complémentaire, et la carte disparaît.
- **La page reste un afficheur bête** : le serveur envoie la valeur déjà formatée et le suffixe à mettre derrière.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `selected` | vide / null / illisible | aucune sélection, aucune exception |
| `selected` | codes inconnus seulement | aucune sélection |
| `selected` | ordre administré, doublon, espaces | ordre respecté, doublon une fois, espaces tolérés |
| `indicators` | indicateur de journal | comptage, suffixe vide |
| `indicators` | comptage nul | « 0 », jamais null |
| `indicators` | mouvements de caisse | montant signé, virgule française, suffixe € |
| `indicators` | caisses muettes | compte de la liste de présence |
| `dashboard-data` | aucune sélection | liste vide portée, carte masquée |

**K13 Les connexions back-office au journal (15/10)** `EmployeeIdentityProvider`, `TechnicalEvent` [E] (BO-04-03-04)

Les échecs d'authentification étaient comptés et pouvaient verrouiller un compte ; les **réussites** ne laissaient aucune trace. Une arrivée au back-office est un événement du magasin, pas une ligne de log technique.

- **Seule la branche qui a VÉRIFIÉ un mot de passe écrit.** Le fournisseur de confiance repasse par le même code à chaque requête d'une session ouverte, sans secret : journaliser là aurait écrit une ligne par page vue au lieu d'une ligne par arrivée.
- **L'identifiant voyage dans le détail, le badge dans sa colonne, le secret nulle part** — pas même tronqué.
- **Un journal indisponible ne refuse pas la connexion** : l'opérateur a prouvé qui il est, et l'écrit est gardé comme le comptage des échecs l'est déjà.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `identityOf` | mot de passe correct | événement `BACK_OFFICE_SIGNED_IN`, badge et identifiant |
| `identityOf` | mot de passe absent (cookie restauré) | aucun écrit |
| `identityOf` | mot de passe faux | aucun écrit, échec compté |
| `identityOf` | journal en panne | connexion accordée tout de même |
| `EventType` | — | trente-quatre constantes, la nouvelle en queue |

## L. Documents et gabarits [E]

Surfaces `@RolesAllowed("ADMIN")` : anonyme → 401, non-ADMIN → 403.

**L1 Documents émis en caisse** `/admin/documents` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /` | Liste (lecture seule, aucun POST) | 200 HTML ; documents (Invoice) les plus récents d'abord ; types = `DocumentType.values()` |
| `?from=&to=` | Bornes de période | Période poussée en base : 4 requêtes selon bornes présentes/absentes ; borne haute = jour+1 exclusif |
| `?from=abc` / `?to=abc` | Date illisible | Bord absent (pas d'erreur) |
| `?text=` | Fragment | Filtre en mémoire sur numéro OU nom client OU n° de compte (insensible casse) |
| `?taxId=` | Fragment fiscal | Filtre sur `customerTaxId` (contient, insensible casse) |
| `?type=FACTURE` / `BON_LIVRAISON` | Type | Ne garde que ce type ; autre/blanc → tous |
| `GET /export?...` | Export filtré | 200 `text/csv; charset=UTF-8`, `filename="documents-<aujourd'hui>.csv"` ; en-tête 16 colonnes (`DOCUMENT_NUMBER;…;PRINT_COUNT`) ; montants en `toPlainString`, `;` neutralisé |
| Bloc client | Affiché | Celui porté par le DOCUMENT (jamais la fiche client actuelle) |
| Accès | anonyme / non-ADMIN | 401 / 403 |

**L2 Gabarits de documents** `/admin/templates` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `GET /` | Page | 200 HTML ; aperçu de chaque gabarit administré vs sa vente de démo ; **15 types** (`SALE_RECEIPT`…`LOYALTY_RECEIPT`, plus les quatre bannières `TICKET_HEADER`, `TICKET_FOOTER`, `INVOICE_HEADER`, `INVOICE_FOOTER`) ; picker « Partir de » même type d'abord |
| `POST /preview` | Aperçu | 200 HTML ; rend la source posée SANS persister ; draft porte source + rendu (ou erreur) ; ligne en base intacte |
| `POST /preview` | Source cassée | 200 ; draft `error` = complainte du parseur, `rendered=null`, aucune persistance |
| `POST /copy` | Picker `template:<code>` | 200 ; charge la source du gabarit administré ; CODE jamais copié |
| `POST /copy` | Picker `example:<TYPE>` | 200 ; charge la source d'usine du type |
| `POST /copy` | Pick vide / code supprimé | 200 ; retombe sur l'exemple du type courant (boîte non vidée) |
| `POST /save` | `code` vide/blanc | 303 false, « Le code du gabarit est obligatoire. » |
| `POST /save` | `documentType` inconnu/blanc | 303 false, « Type de document inconnu. » |
| `POST /save` | Source qui ne compile pas | 303 false, « Gabarit refusé : <complainte> » |
| `POST /save` | `width` < 20 ou > 120 (défaut 42, illisible → 42) | 303 false, « La largeur doit être comprise entre 20 et 120. » |
| `POST /save` | Création (code inédit) | 303 ok, « Gabarit créé. » ; `active` = champ présent ; cache vidé |
| `POST /save` | Mise à jour (code existant) | 303 ok, « Gabarit enregistré. » |
| `POST /delete` | Code inconnu | 303 false, « Gabarit inconnu. » |
| `POST /delete` | Code connu | 303 ok, « Gabarit supprimé. » ; cache vidé |
| `GET /api/references?type=` | JSON | 200 `application/json` ; tableau `{expression,sample,loop}` du type ; type inconnu/blanc → SALE_RECEIPT |
| Accès | anonyme / non-ADMIN | 401 / 403 (y compris `/save`, `/delete`, `/api/references`) |

Notes de lecture (chemins load-bearing) :
- Messages exacts et bornes vérifiés dans le code : `SafeService.NO_OPEN_PERIOD = "Aucune période comptable ouverte."`, `PENDING_MOVEMENTS`, `FROZEN_MOVEMENT` ; `DocumentTemplate.MIN_WIDTH=20 / MAX_WIDTH=120` ; `ReportChart.MAX_BARS=40` ; `ReportResource.PAGE_SIZE=100`.
- `ReportResource.export` lève `NotFoundException` (404) pour le code inconnu ET pour le format inconnu (« Format de rapport inconnu : … » / « Rapport inconnu : … »).
- Le catalogue expose 11 rapports sur 4 familles non vides ; la famille CUSTOMERS existe dans l'enum mais reste masquée (aucun `ReportDefinition` ne la porte) — assertion clé de J1.
- `AdminSafeJournalResource` et `AdminDocumentResource` sont strictement en lecture (aucun POST) ; leurs seuls effets d'écriture sont des exports CSV 200.
- Fichiers lus : les 11 resources coffre/monnayeur + `SafeService`, les writers/format/criteria/chart/catalog/period/family/definition de `ui/report`, `AdminDocumentResource`, `AdminTemplateResource`, `DocumentTemplate`.

**L2bis Bannières d'entête et de pied (lot 1 du chantier documents, 12/10)** [E/S] (BO-03-05-01 à -04, BO-03-04-02 et -08)

L'entête et le pied administrés étaient **une ligne de 120 caractères toujours centrée** (`ticket.header-message`, `ticket.footer-message`), insérée en dur par le rendu Java de la caisse. Ils deviennent quatre **gabarits** comme les autres — `TICKET_HEADER`, `TICKET_FOOTER`, `INVOICE_HEADER`, `INVOICE_FOOTER` — et héritent de tout ce que l'écran des gabarits porte déjà : autant de lignes que le magasin en écrit (le minimum de dix demandé est sans objet), des modèles **nommés et codés** donc plus de dix cohabitant sous des codes différents et choisis par priorité, l'aperçu contre la vente de démonstration, le contrôle de compilation au save, et la descente en caisse par le domaine `DOCUMENT_TEMPLATES`. L'alignement se dit dans la source : texte brut à gauche, `{fmt.centre(...)}` au centre, `{fmt.right(...)}` à droite — cette dernière primitive est ajoutée à `DocumentLayout` par ce lot.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Ticket | bannière d'entête administrée | ses lignes s'impriment sous l'adresse du magasin, telles que la source les a mises en page ; le message centré unique n'est PAS imprimé |
| Ticket | bannière de pied administrée | idem après la formule de politesse, avant le code-barres d'identification |
| Ticket | entête administrée, pied absent | l'entête est la bannière, le pied reste le message centré unique — les deux bannières sont indépendantes |
| Ticket | aucune bannière | comportement d'avant au caractère près : message centré s'il est non blanc, rien sinon |
| Ticket | bannière finissant par des lignes vides | les vides de FIN sont écartés (un bloc de texte finit toujours par un saut), ceux du MILIEU sont gardés — c'est un espacement voulu |
| Ticket | gabarit `SALE_RECEIPT` administré | aucune bannière n'est ajoutée : l'entête est dans la source de l'auteur, l'imprimer en plus la dirait deux fois |
| Facture | entête et pied administrés | encadrent le rendu intégré — avant le bloc vendeur, après les règlements — sans toucher une ligne de ce qui est entre les deux |
| Facture | gabarit `INVOICE` administré | même règle que le ticket : pas de bannière autour d'un document entièrement réécrit |
| `fmt.right` | texte plus court / exactement à la largeur / plus long / null | calé à droite / inchangé / **jamais coupé** / une ligne de blancs |

**L2ter Nom du document et nom de réimpression (lot 3, 12/10)** [E/S] (BO-03-04-12 et -13)

Deux colonnes sur le gabarit — `title` et `duplicateTitle` — éditées sur `/admin/templates`, portées par le DTO `DOCUMENT_TEMPLATES` et dans son empreinte, appliquées au nœud comme à la caisse. Elles sont **à côté de la mise en page et non dedans** : un magasin qui garde le document intégré peut vouloir le renommer, et réécrire tout un gabarit pour changer deux mots n'est pas un prix raisonnable.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `titleFor` | aucune ligne / ligne sans nom / nom blanc / nom posé | null, null, null, le nom **rogné** ; la première ligne non blanche dans l'ordre administré gagne |
| `titleFor` | ligne nommée **sans source** | le nom est lu quand même — renommer un document qu'on n'a pas réécrit est le cas normal, et cette ligne n'est pas rendable (`findFor` répond null sur la même ligne) |
| `titleFor` | type null | null |
| `duplicateTitleFor` | ligne qui nomme le document mais pas sa réimpression | null — les deux mots sont séparés : le Portugal imprime DUPLICADO là où la France imprime DUPLICATA |
| Caisse | réimpression avec nom administré | `*** DUPLICADO N2 ***` à la place de `*** DUPLICATA N°2 ***` ; **le rang reste calculé par la caisse** |
| Caisse | ticket de retour, avoir, carte cadeau | leur titre vient du gabarit quand il est posé, du libellé intégré sinon |
| Écran | champ vidé | stocké null, pas chaîne vide : une chaîne vide s'imprimerait comme un titre de zéro caractère |

**L2quater Les zones (lot 4, 12/10)** [E/S] (BO-03-04-03, -04 et -07)

Une zone est **un gabarit de type `FRAGMENT`** et rien d'autre : pas d'entité nouvelle, pas d'écran nouveau, pas de domaine de tirage nouveau. Elle hérite donc de l'écran, du contrôle de compilation, de l'aperçu et de la descente `DOCUMENT_TEMPLATES`. Un document la nomme par `[[ZONE <code>]]`, et le rendu met la source de la zone à cet endroit **avant de compiler** : les zones d'un document sont les marqueurs que sa source porte, dans l'ordre où elle les porte — ce qui est à la fois le « les unes derrière les autres » et le « positionnement saisi » que l'exigence demande. Plusieurs zones sous des codes différents sont plusieurs MODÈLES de la même zone, et le document choisit celui qu'il nomme.

L'expansion est faite au moment de la compilation, pour deux raisons : le contrôle que le back-office passe au save voit le document que l'imprimante verra, et la forme composée est mise en cache comme les autres — un document fait de six zones coûte une compilation, pas sept.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `expandZones` | source sans marqueur / source null | rendue telle quelle / null |
| `expandZones` | zone connue | la source de la zone prend la place du marqueur |
| `expandZones` | zone nommant une autre zone | composée en profondeur, jusqu'à cinq niveaux |
| `expandZones` | zone se nommant **elle-même** | s'arrête à la borne et laisse le marqueur — la caisse ne bloque pas |
| `expandZones` | code inconnu (zone supprimée) | s'efface — le document s'imprime sans elle, plutôt que d'imprimer le marqueur sur le papier du client |
| Aperçu | le nœud rend comme la caisse | `DocumentLayout` est **la même classe des deux côtés**, liée à la même largeur administrée : `{fmt.centre(...)}` montre ici les caractères que le rouleau imprimera. Sans ça l'aperçu du nœud affichait une erreur sur une mise en page que la caisse rendait parfaitement |

**L2quinquies Lignes de bannière et gras (lot 2, 12/10)** [E/S] (BO-03-05-05 et -06)

L'exigence veut une bannière éditée **ligne à ligne** : activer ou désactiver la ligne, la centrer, la justifier, la mettre en gras, et une ligne désactivée ne s'imprime pas. Une zone de texte libre n'offre rien de tout cela, et une seconde table à côté de la mise en page donnerait à une bannière **deux vérités**. Il y a donc une seule vérité — la source de la bannière — et `BannerLines` en est la porte à double sens : `parse` la lit en lignes pour la table, `format` réécrit les lignes en source. **L'aller-retour est exact** sur toutes les formes que la table produit, ce qui est ce qui autorise à passer de la table à la source brute sans perdre une ligne.

Le gras est une **directive** — `[[BOLD]]…[[/BOLD]]` — émise comme `[[BARCODE …]]` et `[[IMAGE …]]` le sont déjà : la caisse reste du texte et le pont matériel la traduit en commandes d'imprimante. **Le module matériel n'est pas dans ce dépôt** : tant qu'il n'interprète pas la paire, une ligne grasse sort avec ses marqueurs visibles — une panne qu'un paramétreur sait lire, et non des octets de contrôle illégaux.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `parse` | source null / vide | aucune ligne |
| `parse` | texte brut / `{fmt.centre("x")}` / `{fmt.right("x")}` | GAUCHE / CENTRE / DROITE |
| `parse` | `{fmt.bold(…)}` autour d'une ligne alignée ou brute | gras, l'enveloppe n'est pas prise pour le texte |
| `parse` | `{! OFF !}` devant la ligne | inactive **en gardant son texte** — une ligne saisonnière s'éteint en octobre et se rallume en décembre sans être retapée |
| `parse` | ligne que la classe n'a pas écrite (`Caisse {terminal}`) | rendue verbatim en ligne GAUCHE — la table ne jette jamais ce qu'elle ne comprend pas |
| `parse` | lignes vides de FIN / du MILIEU / vide mais centrée | rognées / gardées / gardée (c'est un blanc voulu) |
| `format` | null / aucune ligne / chaque forme / texte null | vide, vide, la forme que `parse` relit, une ligne vide |
| Aller-retour | bannière portant **toutes** les formes à la fois | source identique au caractère près |
| Écran | bannière | la table s'affiche, plus **trois lignes vides** toujours offertes — on ajoute des lignes sans script |
| Save | la table est postée | elle fait la source ; sinon la zone de texte reste la porte |
| Save | ligne vide et rien de coché | jetée — les lignes d'appoint ne deviennent pas des lignes de papier |
| Save | ligne vide mais cochée | gardée — cocher une case sur une ligne vide est une décision, pas un oubli |
| `fmt.bold` | texte null ou vide | chaîne vide, **pas** une paire de marqueurs autour de rien |

**L2sexies Documents fiscaux du Portugal (lot 5, 12/10)** [E/S] (BO-03-04-19, -21 à -26 et -28)

Quatre types de document — `FS`, `FT`, `NC`, `RC` — **et pas une ligne de Java pour les imprimer** : leur mise en page est un gabarit, le nombre d'exemplaires est le `copies` du gabarit, le nom de chaque exemplaire est dans `copyTitles`, le préfixe de leur numéro est dans `numberPrefix`. C'est ce qui permet d'ouvrir un pays sans livrer une version.

La NUMÉROTATION est une entité à part, `DocumentSequence`, et le choix mérite d'être dit : le Portugal veut un numéro unique **par type, par caisse, par point de vente et par année**, là où le compteur de tickets a une seule clé et ne repart jamais à zéro. Ajouter l'année au `TicketCounter` aurait remis à zéro la séquence des tickets avec elle — or c'est l'ancre de la chaîne fiscale, perpétuelle par la loi. Le verrou pessimiste et la transaction de l'appelant sont ceux du compteur de tickets : deux caisses tiennent deux lignes, une caisse sérialise ses propres documents.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `prefixFor` | rien / préfixe blanc / préfixe posé / type null | null, null, le préfixe rogné, null |
| `nextDocumentNumber` | aucun préfixe administré | le type se nomme lui-même — deux séries restent distinctes avant tout paramétrage |
| `nextDocumentNumber` | forme | `PRÉFIXE CAISSE/ANNÉE/NNNNNNNN` |
| `DocumentSequence.next` | type, caisse, PDV ou année inédits | nouvelle ligne à 1 ; **la ligne de l'année précédente est intacte**, c'est ce qu'une administration vient rechercher |
| `copyTitleFor` | rang 1 / 2 / 3 sur `ORIGINAL;DUPLICADO;TRIPLICADO` | les trois noms |
| `copyTitleFor` | rang AU-DELÀ des noms administrés | null — un quatrième exemplaire nommé TRIPLICADO dirait quelque chose de faux sur un document fiscal |
| `copyTitleFor` | rang < 1 / ligne sans noms / nom vide à ce rang / rien d'administré | null à chaque fois |
| Écran | préfixe et noms d'exemplaires | deux champs sur chaque carte de gabarit, vidés → null |
| Tirage | les quatre colonnes | voyagent dans le DTO `DOCUMENT_TEMPLATES`, comptent dans l'empreinte, appliquées au nœud comme à la caisse |

Reste hors de ce lot et **bloqué sur un document externe** : l'ATCUD et le QR code (BO-03-04-27, BO-03-05-08), dont le contenu et le format sont fixés par la spécification fiscale portugaise.

**L4 Messages aux caisses (chantier 03-08, 12/10)** `/admin/messages`, `GET /api/messages/pending` [E/S] (BO-03-08-01, -02, -04 et -08)

**Le bout caisse de ce canal existait depuis EXP-20 ; le bout nœud n'existait pas.** Une ligne sonde `pos.message.node-url?terminal=X` toutes les vingt secondes, attend un objet `{text, question, options}`, affiche la bannière sur l'écran de vente et renvoie la réponse d'un sondage. Personne ne servait cet endpoint. Ce chantier l'écrit.

**La forme servie n'est pas négociable** : les caisses sont déployées et sondent ces trois champs ; en servir d'autres voudrait dire une livraison sur chaque ligne du magasin. Le nœud répond donc exactement cela, et tout ce que l'écran sait de plus — la période, la population, le moment, le mode de confirmation — est résolu **avant** de répondre.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `appliesTo` | message désactivé | servi à personne — c'est ainsi qu'on retire une consigne |
| `appliesTo` | aucune période / avant le début / après la fin | toujours servi / pas encore / plus ; **les deux bornes sont inclusives** (une consigne posée pour midi est servie à midi) |
| `appliesTo` | population TOUTES / UNE CAISSE / UN ÎLOT | tout le monde / la caisse nommée seule / les caisses de l'îlot ; valeur absente → servi à personne |
| `appliesTo` | population UN OPÉRATEUR | servi à **toutes** les caisses — le nœud ne sait pas qui est connecté où, le filtrage est celui de la caisse. Seule des quatre à fonctionner ainsi, et c'est testé comme tel pour que l'asymétrie soit délibérée |
| `appliesTo` | hors période mais adressé à cette caisse | pas servi — la période prime, ce qui permet de préparer la consigne de demain |
| `momentHasCome` | IMMÉDIAT | servi au prochain sondage, donc sous vingt secondes |
| `momentHasCome` | À L'OUVERTURE | servi si la session ouverte sur cette caisse a commencé **après** le message ; un caissier déjà en poste ne le reçoit pas — c'est la seule lecture de « à l'ouverture » qui ne revienne pas à « tout de suite ». Début non borné → n'attend rien |
| `momentHasCome` | À LA FERMETURE | servi quand la caisse n'a **plus** de session ouverte — là où le caissier le lit, après le Z |
| `pending` | caisse sans message | 204 ; le sondeur laisse sa bannière intacte |
| `pending` | plusieurs messages applicables | **le plus récent gagne** — la caisse n'affiche qu'une bannière, et c'est ce qu'attend un magasin qui corrige une consigne |
| `pending` | caisse inconnue du référentiel | traitée sans îlot plutôt qu'en erreur — une ligne non déclarée sonde quand même |
| `pending` | rôle ≠ store / jeton faux | 403 / 401, comme les autres surfaces machine |
| Confirmation | réponse de sondage remontant en appel superviseur `SONDAGE` | **aucun canal nouveau** : la caisse remonte déjà par là, et cet appel nomme déjà la caisse et l'opérateur. Le nœud en fait un accusé horodaté — exactement le « n° caissière, nom-prénom, date » demandé |
| Confirmation | appel d'un autre motif / sans séparateur / question inconnue | rien n'est classé — un appel superviseur n'est pas un accusé, et mieux vaut ne rien classer que classer sous le mauvais message |
| Écran | message sans texte **ni** question | refusé — une bannière vide servie à quarante caisses est un défaut, pas un message |
| Écran | population ciblée sans valeur / date illisible / fin avant début | refusés, chacun par son nom |
| Écran | liste des accusés | **nominative et non comptée** : un magasin qui diffuse une consigne a besoin de savoir QUELLE caissière ne l'a pas lue |
| Suppression | message supprimé | ses accusés partent avec lui — ils nommeraient un message qui n'existe plus |

**L5 Message porté par l'article (12/10)** `/admin/article` [E/S] (BO-03-08-09)

Une colonne `infoMessage` sur l'article, pas une table de règles : le message EST un attribut de l'article — « produit fragile », « vérifier la date », « appeler le rayon ». Le magasin l'écrit où il écrit le libellé, et il descend par le même domaine `PRODUCTS`.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Caisse | article portant un message, venant d'être saisi | la phrase s'affiche en notice ; **la vente continue** — un article à refuser relève d'un point de contrôle, pas d'une phrase |
| Caisse | message **et** prix au-dessus du seuil | le message de l'article passe d'abord : les deux partagent un emplacement de notice et le message est le plus spécifique des deux |
| Caisse | une erreur est déjà à l'écran | ni l'un ni l'autre — une ligne refusée ne doit pas être recouverte par une notice sur la ligne d'avant |
| Caisse | article sans message / ligne sans EAN / EAN inconnu | rien |
| Écran | champ vidé | stocké null : l'article ne dit rien |

**L6 Messages des fournisseurs (12/10)** `/admin/providers`, domaine `PROVIDER_MESSAGES` [E/S] (BO-03-08-06 et -07)

Un tiers répond dans sa langue — `ERROR CODE 4 ITEM` — et montrer ça à un caissier, c'est lui montrer le journal de quelqu'un d'autre. L'entité `ProviderMessage` est la traduction, **clée sur la PAIRE fournisseur + code** : deux émetteurs qui numérotent leurs erreurs à partir de un ne disent pas la même chose avec le code 4. Le domaine de tirage passe de 17 à **18** (`PROVIDER_MESSAGES`, entre `DOCUMENT_TEMPLATES` et `SETTINGS`), l'union du `switch` de `getPage` de 21 à **22**.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `findFor` | fournisseur null / code null | rien — la paire est la clé, une demi-clé n'ouvre rien |
| `screenTextFor` | rien de déclaré / texte écran null / texte écran blanc | **le texte du fournisseur** dans les trois cas — ne jamais vider le message d'un caissier |
| `screenTextFor` | texte déclaré | remplace, rogné |
| `ticketTextFor` | rien de déclaré / texte ticket null / blanc | **null — rien n'est imprimé**, et c'est la règle INVERSE de l'écran : la réponse technique d'un tiers n'a pas sa place sur le papier du client sans une phrase écrite par le magasin |
| Tirage | ligne retirée du snapshot | **supprimée** et non désactivée — une substitution que personne ne déclare plus n'a pas d'histoire à garder, et la laisser ferait hériter la phrase d'un autre au magasin qui réutilise le code |
| Écran | fournisseur ou code absent | refusé |

Écart assumé sur ces deux lignes : **aucun client fournisseur ne renvoie aujourd'hui de code message** — les intégrations répondent un verdict, pas un code — donc la substitution n'a rien à remplacer tant qu'un protocole ne le fournit pas. Le référentiel, son écran et sa descente sont là ; le point de consommation attend l'intégration.

**L7 Le justificatif du moyen de paiement (12/10)** `/admin/tenders` [E/S] (BO-03-02-26, -31, -32 et -33)

Les commentaires de ces quatre lignes demandaient tous de « créer le moteur de modèles de document ». **Il existe depuis ce matin.** Il ne manquait que le lien : quatre colonnes sur le moyen de paiement.

- `documentTemplateCode` — le gabarit imprimé quand ce moyen est utilisé. **Nommé par son code et non par clé étrangère** : les gabarits voyagent sur leur propre domaine, et un moyen qui en nomme un pas encore arrivé ne doit pas être bloqué pour autant.
- `printCount` — le nombre d'impressions, 1 par défaut. **Sur le moyen et pas seulement sur le gabarit** : la même mise en page peut sortir une fois pour un moyen et deux pour un autre, et l'exigence demande le compte « par moyen de paiement ».
- `printDuplicate` — un duplicata à côté des originaux. Pas la même chose qu'imprimer deux fois : il est rendu **comme une réimpression**, donc il porte le nom de réimpression du gabarit (lot 3 de ce matin).
- `printForced` — imprimé même quand le ticket de caisse ne l'est pas. Le justificatif est une preuve de RÈGLEMENT, le ticket une preuve de VENTE.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `printTenderSlips` | moyen nommant un gabarit, 2 exemplaires | deux impressions, deux coupes |
| `printTenderSlips` | duplicata demandé | un exemplaire de plus, rendu en réimpression |
| `printTenderSlips` | ticket NON imprimé, moyen non forcé / forcé | rien / le justificatif sort quand même |
| `printTenderSlips` | ticket null / sans règlement / moyen inconnu / sans gabarit / gabarit absent | rien à chaque fois — jamais un règlement mis en échec par un justificatif |
| `printTenderSlips` | le même moyen a réglé deux fois | **un seul justificatif** : deux lignes d'espèces sont une façon de payer, en imprimer deux dirait le nombre d'exemplaires par accident |
| Tirage | compte absent ou nul venant d'un nœud plus ancien | lu comme 1 — un moyen qui imprime un justificatif l'imprime |
| Appel | après le ticket et le ticket CB, dans `applyPrintChoice` | reçoit la décision d'impression de `PrintPolicy` |

**L8 Le taux de change et sa date (12/10)** `/admin/currencies` [E/S] (BO-03-02-39 et -40)

L'écran des devises existait depuis EXP-22, avec son taux. Ce qu'il ne portait pas, c'est **quand** ce taux est devenu le taux — ni **dans quelle monnaie** le rendu se fait. Les deux lignes ne demandaient pas un écran de plus, elles demandaient deux champs et une table.

- `Currency.changeTenderCode` — le moyen de paiement dans lequel la monnaie est rendue, **vide = en euros**. C'est un code de MOYEN DE PAIEMENT et non un code de devise : rendre en francs suisses et rendre en espèces suisses ne se décident pas au même endroit, et c'est le moyen qui porte le tiroir.
- `CurrencyRateHistory` — une ligne par CHANGEMENT de taux : l'ancien taux, le nouveau, la date, l'opérateur. Écrite par l'écran et **jamais modifiée** : un historique qui se corrige n'est pas un historique.
- Le taux en vigueur reste la colonne de la devise. Aucun lecteur existant ne change — une conversion qui demandait son taux à la devise continue de le lui demander.
- L'écriture est **conditionnée au changement réel** et faite **avant** le remplacement. Enregistrer la devise sans toucher au taux n'écrit rien : un historique rempli de lignes qui disent le même chiffre cache les trois qui ne le disent pas.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `record` | taux suivant null | rien d'écrit |
| `record` | taux inchangé | rien d'écrit |
| `record` | même valeur écrite autrement (`0.95` / `0.9500`) | rien d'écrit — la comparaison porte sur la valeur, pas sur le texte |
| `record` | premier taux (pas de précédent) | une ligne, prédécesseur null |
| `record` | changement réel | une ligne portant les DEUX taux |
| `listFor` | une devise | ses changements, le plus récent d'abord |
| Ligne | à la construction | elle s'horodate elle-même ; l'appelant ne choisit pas la date |
| `saveCurrency` | taux modifié | l'ancien taux est gardé, et gardé AVANT d'être remplacé |
| `saveCurrency` | taux réenregistré à l'identique | historique muet |
| `saveCurrency` | `changeTenderCode` posté / vide | enregistré / rendu en euros |
| Écran | `GET /admin/currencies` | chaque devise sert son historique, sous elle, le plus récent d'abord |
| Diffusion | `CURRENCIES` | `changeTenderCode` descend à la caisse et entre dans l'empreinte |
| Diffusion | instantané siège **sans** le champ (nœud) | le choix du magasin est conservé — la monnaie de rendu est décidée sur le nœud |

**Hors périmètre, signé et non fait** : l'historique n'est pas encore montré à la caisse, et le rendu dans la monnaie nommée par `changeTenderCode` est un travail de la campagne miroir — la colonne descend, le calcul du rendu reste à écrire côté caisse.

**L9 La destination d'une plage de codes-barres (12/10)** `/admin/coupons`, domaine `ARTICLE_RANGES` [E/S] (BO-03-06-56, -60 et -61)

Le vocabulaire positionnel des plages article disait depuis EXP-26 **comment lire** un code : préfixe, longueur, nature des caractères, deux zones. Il n'a jamais dit **ce que le code veut dire** — toute plage voulait dire « un article avec son prix ou son poids », parce que c'était la seule chose que la chaîne savait en faire. Trois lignes du questionnaire demandent trois autres sens sur le même vocabulaire. Aucune ne méritait une seconde table : même préfixe, même longueur, mêmes deux zones.

- `ArticleBarcodeRange.purpose` — ARTICLE (par défaut, donc toute plage administrée avant la colonne continue exactement ce qu'elle faisait), REFUND, TARE, BALANCE_TICKET. Administrée sur `/admin/coupons`, elle descend aux caisses avec la plage et **entre dans l'empreinte** : un plan lu de la même façon mais traité autrement n'est pas le même plan.
- `RangePurposeScanHandler` (nouveau, priorité 1) — il ne décode rien : la plage l'a fait. Il porte le SENS. `WeightedEanScanHandler` refuse les mêmes codes pour la même raison, donc la disjonction exigée par le contrat de chaîne tient par une décision unique, prise sur la plage.
- REFUND — le code nomme l'article et porte son prix ; la ligne est passée en retour sur la vente en cours, **sans ticket d'origine** : le papier EST l'autorisation. C'est précisément pourquoi les deux autorisations administrées sur les retours (`returnWithoutOriginAllowed`, `returnInSaleAllowed`) sont honorées ici mot pour mot : un code-barres n'est pas un contournement de paramétrage.
- TARE — le code porte le poids du contenant ; il est gardé **sur le ticket** (il part avec lui) et déduit de la prochaine ligne pesée, là où un poids devient une quantité. Lu une seule fois : une tare allège une ligne, pas la suivante.
- BALANCE_TICKET — la référence du sachet comptoir, prise **brute, zéros de tête compris** : le magasin la rapproche caractère par caractère de ce que la balance a poussé. La plage administrée est consultée d'abord ; tant qu'aucune n'existe, la disposition 2x historique répond, inchangée.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `purpose` | plage d'avant la colonne | ARTICLE — rien ne bouge |
| Chaîne | plage REFUND / TARE / BALANCE_TICKET | `WeightedEanScanHandler` laisse passer, sans message |
| `RangePurposeScanHandler` | code nul / blanc / aucune plage / plage ARTICLE / plage BALANCE_TICKET | non consommé à chaque fois |
| `RangePurposeScanHandler` | clé de contrôle administrée, fausse / juste | laisse passer / agit |
| TARE | poids lisible | annoncé, notice « TARE x KG PRISE EN COMPTE », aucune ligne |
| TARE | poids nul / zone illisible | refusé « TARE ILLISIBLE » — une tare de zéro et une tare illisible ne sont pas la même chose |
| TARE | ligne pesée suivante | poids diminué de la tare, tare consommée |
| TARE | tare ≥ poids pesé | « POIDS INVALIDE » — facturer le saladier n'est pas une vente |
| TARE | fin de ticket | oubliée (`clear`) |
| REFUND | plage à prix | retour quantité 1 au prix du code |
| REFUND | plage à poids | retour du poids lu, au prix du catalogue ; sans prix courant, 0 et TVA par défaut |
| REFUND | poids nul | « POIDS INVALIDE » |
| REFUND | retour sans origine interdit | « RETOUR SANS TICKET D'ORIGINE INTERDIT » |
| REFUND | retour en vente interdit, ticket commencé / vide | refusé / accepté |
| REFUND | article inconnu | « ARTICLE BALANCE INTROUVABLE (x) » |
| REFUND | zones hors du code | non consommé — jamais un retour sur un article que personne n'a nommé |
| REFUND | mode retour déjà actif / inactif | restitué tel quel après la saisie |
| BALANCE_TICKET | plage administrée, longueur et position propres | référence lue là où le magasin la met |
| BALANCE_TICKET | référence à zéros de tête | gardée telle quelle |
| BALANCE_TICKET | zone illisible / plage d'un autre sens | repli sur la disposition 2x historique |
| Diffusion | `ARTICLE_RANGES` | `purpose` descend, entre dans l'empreinte ; instantané muet → ARTICLE à la caisse, choix du magasin conservé sur le nœud |

**Déjà couvert, recoté sans une ligne de code** : BO-03-06-25 (date AAAAMMJJ). Le format `YYYYMMDD` et son décodage existent dans `CouponField.DateFormat` depuis le moteur de coupons, la position de lecture est administrée sur la zone, et l'écran propose tous les formats. Le commentaire éditeur qui demandait de l'ajouter était périmé.

**Hors périmètre, signé et non fait** : l'alimentation des sachets comptoir se fait par POUSSAGE HTTP (`SyncIngestResource`) et non par récupération FTP comme le dit l'exigence — le format et le contenu sont les mêmes, le transport non.

**L10 Trois documents que le moteur de gabarits ne portait pas (12/10)** `/admin/templates` [E/S] (BO-03-03-07, -36 et -37)

Le moteur de gabarits compte maintenant **22 types de documents**. Deux de plus ici, et une colonne d'imprimerie qui manquait sur la ligne article. Aucun des trois ne demandait une donnée nouvelle : tout est dérivable de la vente ou déjà dans le référentiel.

- **`EXIT_TICKET` — le ticket de sortie (BO-03-03-36).** Substitutif et non additionnel, et c'est tout l'intérêt : un magasin dont les clients montrent un papier à la sortie n'a pas besoin du ticket complet dans leur main, il a besoin des quelques chiffres qu'un portique ou un hôte vérifie — un total, un nombre d'articles, et les plus chers nommés, pour qu'un chariot se compare au papier sans lire quatre-vingts lignes. Titre, code-barres (la primitive existait), total TTC, nombre d'articles, et les X articles les plus chers — X étant paramétré, borné à 1–20 côté paramétrage et non côté imprimante. Le remplacement tient en **une** branche dans `applyPrintChoice` : l'imprimeur répond zéro quand le magasin ne l'administre pas, et le ticket sort comme avant.
- **`PARKED_TICKET_RESUME` — le ticket de reprise (BO-03-03-07).** Le papier de mise en attente dit ce qu'on a mis de côté ; celui-ci dit que ça revient, et nomme **les deux numéros** — celui du ticket qui continue, et celui que portait le papier. Sur une reprise locale ils sont le même ; sur une reprise inter-caisses (EXP-46) ils diffèrent, et c'est exactement là-dessus qu'une contestation deux heures plus tard se joue. Le numéro d'origine est lu AVANT que la reprise ne réhéberge le ticket.
- **Le code du taux de TVA sur la ligne (BO-03-03-37).** Le numéro de régime existait depuis toujours sur le référentiel ; ce qui manquait était de l'imprimer. Il est retrouvé à partir du TAUX que la ligne a figé au scan, donc un ticket réimprimé deux ans plus tard énonce le régime de SA vente et non celui d'aujourd'hui. Un taux qu'aucun régime ne porte n'imprime rien plutôt qu'un numéro invérifiable. Activable en France, désactivable en Belgique, comme l'exigence le demande.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `printExitTicket` | non administré / vente inconnue | zéro, rien d'imprimé |
| `printExitTicket` | administré | titre, total TTC, nombre d'articles, les X plus chers, numéro et code-barres |
| `printExitTicket` | X = 2 sur trois lignes | les deux plus chères, la plus chère d'abord ; la troisième absente |
| `printExitTicket` | ligne annulée | jamais nommée — elle n'est pas vendue |
| `printExitTicket` | moins de lignes que X | toutes nommées |
| `printExitTicket` | vente sans ligne ni total | document quand même, 0,00 |
| `printExitTicket` | gabarit administré | la mise en page du magasin remplace tout, le papier est coupé de même |
| `applyPrintChoice` | ticket de sortie imprimé / pas administré | le ticket de caisse NE sort pas / sort comme avant |
| Paramètre | nombre d'articles hors 1–20 / aux bornes | retombe sur 3 / accepté |
| `printResumeTicket` | non administré / brouillon nul | zéro, rien d'imprimé |
| `printResumeTicket` | administré | titre, date d'IMPRESSION, numéro du ticket, numéro d'origine |
| `resume` | reprise locale | les deux numéros sont le même, le document les dit tous les deux |
| `resumeForeign` | reprise inter-caisses | le numéro du papier, lu avant le réhébergement |
| Code TVA | administré / par défaut | « CODE TVA : n » sous l'article / rien |
| Code TVA | ligne sans taux / taux sans régime / régime sans numéro | rien — jamais un numéro invérifiable |

**Déjà couvert, recoté sans une ligne de code** : BO-03-03-23 (lignes de promotion sous l'article). Le placement est dicté par le `printMode` de l'avantage moteur — `LINE` imprime sous l'article concerné, son absence vaut pied de ticket — et le mécanisme de caisse est en place et testé. Ce qui manque est le remplissage du champ par le moteur, c'est-à-dire un point de contrat imfid et non une mise en page : la ligne passe donc à **1a**, comme les messages fournisseurs.

**Correction de dette trouvée en chemin** : `DocumentTemplateTest` affirmait encore **12** types de documents des deux côtés alors que les lots du matin en avaient porté le nombre à 20 — un test rouge laissé derrière. Il en affirme maintenant 22, et nomme les deux nouveaux.

**L11 Les périphériques du point de contact (12/10)** `/admin/checkout-points`, domaine `CHECKOUT_POINTS` [E/S] (BO-03-09-15, -24, -25 et -26)

Quatre lignes qui demandent la même chose quatre fois : **protocole, adresse, port**. Une balance DIGI, un monnayeur Gunnebo, un Glory, un Toshiba. Elles ne demandent pas quatre écrans ni quatre tables : elles demandent que le point de contact d'encaissement porte ses périphériques, comme il porte déjà ses afficheurs (BO-03-09-12/13) — type administré, résolution administrée, et aucun pilote dans ces dépôts.

- `ScaleProtocol` — NONE, **CD06** (celui que la caisse pilotait déjà, réglé en ligne de commande), **DIGI** (BO-03-09-15).
- `CoinMachineProtocol` — NONE, **GUNNEBO**, **GLORY**, **TOSHIBA** : **un seul choix administré pour trois lignes**, parce que les trois demandent le même triplet et ne diffèrent que par le nom. Un magasin qui change de fournisseur change une valeur.
- Six colonnes sur `CheckoutPoint`, administrées sur l'écran existant, descendues aux caisses avec le point et **entrées dans l'empreinte** : une adresse corrigée au siège fait repasser la caisse au rattrapage.
- Le port est un `Integer` **nullable et jamais zéro** : une machine sans port et un port mal saisi ne doivent pas se lire pareil, et zéro est un port que la caisse essaierait. Borné à 1–65535 au paramétrage et non à l'imprimante.
- Un instantané muet laisse la caisse en NONE — aucune machine, ce qu'elle avait avant que la colonne existe, et jamais un protocole que personne n'a administré.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Écran | `GET /admin/checkout-points` | les deux listes de protocoles servies au formulaire |
| Save | protocole, adresse, port postés | enregistrés sur le point |
| Save | protocole inconnu (balance / monnayeur) | NONE — jamais un protocole inventé |
| Save | port vide / non numérique / 0 / 65536 | non enregistré (null) |
| Save | port 1 / 65535 | acceptés, les deux bornes |
| `fromName` | nom connu (espaces compris) / null / inconnu | résolu / null / null |
| Diffusion | `CHECKOUT_POINTS` | les six valeurs descendent et entrent dans l'empreinte |
| Diffusion | instantané sans protocole | NONE à la caisse, port null |

**Hors périmètre, signé et non fait** : le DIALOGUE avec ces machines. Le protocole est nommé, l'adresse et le port sont administrés et distribués ; parler DIGI, Gunnebo, Glory ou Toshiba est le travail du module matériel, qui n'est pas dans ces dépôts — comme les afficheurs, dont le type est administré depuis BO-03-09-12 sans qu'aucun pilote d'afficheur n'existe ici.

**L12 Où un groupe d'articles est affiché (12/10)** `/admin/touches`, domaine `TOUCH_GROUPS` [E/S] (BO-03-01-21, -22 et -23)

Les trois lignes disaient « rattacher les groupes d'articles aux îlots, aux caisses, aux types de point de contact », et les trois commentaires éditeur disaient « il faut d'abord modéliser l'îlot et la caisse ». **Les deux entités existent** — `CheckoutIsland` depuis le lot 03-10, `CheckoutPoint` et son `tpvType` depuis 03-09. Il ne manquait que la portée.

- Trois colonnes sur `TouchGroupSetting`, le bon propriétaire : la configuration d'écran, pas la nomenclature — un réimport du référentiel articles n'efface pas la portée.
- **Rien de coché = partout.** C'est le comportement de la grille avant la colonne : un magasin qui n'ouvre jamais ce volet garde sa grille à l'identique. Une portée vide qui vaudrait « nulle part » cacherait tous les groupes au premier déploiement.
- **Les trois portées se cumulent** : îlot ET caisse ET type. Et un type plutôt qu'une liste de caisses, parce que « tous les libre-service » est une règle qui doit survivre à l'ouverture du suivant.
- La caisse lit son îlot et son type **sur le point de contact distribué**, pas sur elle-même : ce n'est pas la caisse qui décide dans quel îlot elle est.
- Un sous-groupe hors périmètre n'est pas davantage affiché sous son parent : la portée est sur le groupe, pas sur son rang.
- La portée entre dans **l'empreinte** du domaine : un groupe déplacé d'un îlot à l'autre fait repasser les caisses au rattrapage.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `isDrawnOn` | aucune portée (null) / portées blanches | affiché partout, même sur une caisse qui ne sait rien dire d'elle |
| `isDrawnOn` | îlot nommé : le bon / un autre / aucun | affiché / non / **non** |
| `isDrawnOn` | caisse nommée : la bonne / une autre / aucune | affiché / non / non |
| `isDrawnOn` | type nommé : le bon / un autre / blanc | affiché / non / non |
| `isDrawnOn` | îlot ET type, un seul des deux tenant | non affiché |
| `isDrawnOn` | valeurs séparées par « ; » avec espaces | comparées élaguées |
| Écran | portée cochée | repliée en liste séparée par « ; » |
| Écran | rien de coché / valeur blanche postée | **null** et non chaîne vide |
| Caisse | grille racine, catégorie, sous-groupes | filtrés aux trois endroits |
| Empreinte | groupe déplacé d'îlot, de caisse, de type | empreinte différente à chaque fois |
| Diffusion | instantané sans portée | le groupe redevient affiché partout |

**Hors périmètre, signé et non fait** : BO-03-01-18, la désactivation d'un ARTICLE par îlot ou par caisse (P2). Même mécanique, mais posée sur le produit, c'est-à-dire sur un référentiel de quarante mille lignes diffusées — ça mérite son propre lot.

**L13 Ce que l'îlot décide, et la plage du code-barres du ticket (12/10)** `/admin/settings` [E/S] (BO-03-10-05, -13, -14 et BO-03-06-55)

Les clés d'îlot sont un mécanisme **déjà résolu** : le nœud résout la valeur par point de contact / îlot / PDV et la livre par le domaine `SETTINGS` (c'est ainsi que `island.change-allowed` et `island.auto-declaration` fonctionnent depuis le lot 03-10). Trois lignes de plus ne demandaient donc que trois clés et leur effet.

- **`island.unknown-article-ticket` (BO-03-10-05)** — un vingt-quatrième type de document, `UNKNOWN_ARTICLE_TICKET` : le code **tel qu'il a été lu**, la caisse, l'heure. Ce n'est pas un second message d'écran : c'est le papier qu'un chef de rayon rapporte au référentiel. Le message écran est posé **avant** le papier et non à sa place, une caisse verrouillée n'imprime rien, et un `UnknownScanHandler` sans imprimante (hors CDI) affiche quand même : le papier est un ajout, jamais une condition. Mise en page redéfinissable par gabarit comme les autres.
- **`island.block-article-popups` (BO-03-10-13)** et **`island.block-payment-popups` (BO-03-10-14)** — les messages d'INFORMATION sont tus, **les refus ne le sont jamais**. C'est la ligne de partage, et elle est nette dans le code : ce qui passe par `setWarning` peut être tu, ce qui passe par `setError` jamais. Un geste que le magasin interdit reste interdit et le dit.
- **`ticket.barcode-prefix` (BO-03-06-55)** — la primitive code-barres existait (le commentaire éditeur qui demandait de la créer était périmé, c'est la cinquième de la journée). Ce qui manquait était la **plage** : un préfixe administré devant le numéro, pour que les portiques et les bornes de jeux reconnaissent ce qui leur est réservé. Un PRÉFIXE et non un code entier : le numéro reste celui par lequel un retour ou une réclamation se retrouve.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Clés d'îlot | rien d'administré | les trois à faux : écran seul, tous les messages |
| `printUnknownArticleTicket` | îlot sans papier / code nul / code blanc | zéro, rien d'imprimé |
| `printUnknownArticleTicket` | îlot avec papier | titre, code lu élagué, caisse, date |
| `printUnknownArticleTicket` | gabarit administré | la mise en page du magasin, papier coupé |
| `UnknownScanHandler` | code inconnu | message ÉCRAN **et** demande de papier |
| `UnknownScanHandler` | caisse verrouillée | ni message ni papier |
| `UnknownScanHandler` | sans imprimante (hors CDI) | message quand même |
| Messages article | îlot bloquant | message d'article ET alerte prix élevé tus |
| Messages règlement | îlot bloquant, niveau WARN | alerte tue, le règlement passe |
| Messages règlement | îlot bloquant, niveau BLOCK | **refus maintenu et affiché** |
| Code-barres ticket | préfixe administré (avec espaces) | `[[BARCODE 29C04-00000001]]`, élagué |
| Code-barres ticket | préfixe nul / blanc | le numéro seul, comme avant la clé |

**Dette trouvée en chemin** : le message d'article (BO-03-08-09, livré ce matin) interroge le catalogue à **chaque** entrée de ligne, et `TicketServiceTest` ne stubait pas ce `Product.find` — la suite impos était rouge sur toute la famille « prix élevé ». Le helper d'entrée de ligne le stube maintenant, et le message d'article a enfin son propre test.

**Lot abandonné, raison donnée** : la monétique (BO-03-09-30 codes produit VIC, BO-03-09-31 Label PDV). La trame de REQUÊTE Verifone est un `TODO(spec Verifone)` dans `VerifoneTerminalClient` — il n'y a pas de champ où poser un label ni un code produit. Ajouter les paramètres administrés sans trame où les émettre serait du remplissage de cotation. La classe que citaient les commentaires éditeur (`WimPaymentTerminal`) **n'existe pas dans ces dépôts**.

**L14 Ce qu'un type de retour emporte avec lui (12/10)** `/admin/settings`, `HomeService`, `TicketService` [E/S] (BO-03-13-06, -08 et -10)

Les types de retour existaient déjà comme une simple liste de libellés (`return.types`). Ce qui manquait n'était pas l'écran mais le fait qu'un type **emporte ses règles** : le magasin qui ouvre un type « Retour SAV » veut que ce type-là, et lui seul, dispense du cautionnement. La syntaxe reste celle de toutes les autres clés composées du catalogue : un libellé, `>`, puis les options séparées par des virgules. Une option inconnue est **ignorée** et non refusée — une clé administrée ne doit pas pouvoir empêcher une caisse d'ouvrir.

- **`TICKET` (BO-03-13-06)** — le type imprime son propre document, séparé du ticket de vente.
- **`NOORIGIN` (BO-03-13-08)** — ce type-là autorise le retour **sans ticket d'origine**, même quand la clé générale `return.without-origin` reste à faux. La permission générale et la permission du type sont lues en OU : l'une suffit, aucune des deux n'annule l'autre.
- **`NOENDORSE` (BO-03-13-10)** — ce type-là dispense du cautionnement. Là encore un OU avec `gestureAuthorized` : la dispense du type n'éteint pas le cautionnement des autres types, et un type non dispensé continue à demander l'autorisation comme avant.
- **`PRICE`** — le prix est **confirmé** à la saisie : la ligne passe par la même suspension pour saisie de prix que les articles à prix libre. La confirmation ne vaut que si le mode retour est **actif** : hors retour, un type qui porte `PRICE` ne change rien.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `returnTypes` | libellé seul | les quatre options à faux (compatibilité) |
| `returnTypes` | `>TICKET,NOORIGIN,NOENDORSE,PRICE` | les quatre options à vrai |
| `returnTypes` | option inconnue | ignorée, les autres options tenues |
| `returnType(libellé)` | casse différente / libellé absent / clé vide / nul | résolu sans la casse, nul sinon |
| `toggleReturnMode` | clé générale fausse, type `NOORIGIN` | retour **ouvert** |
| `toggleReturnMode` | clé générale fausse, type sans option | retour refusé, comme avant |
| `toggleReturnMode` | type `NOENDORSE` sans autorisation | retour ouvert sans cautionnement |
| `toggleReturnMode` | type ordinaire sans autorisation | cautionnement demandé |
| `suspendForEntry` | mode retour + type `PRICE` | suspension pour saisie de prix |
| `suspendForEntry` | hors retour / type sans `PRICE` / aucun type actif | rien demandé |

**L15 Les remises prêtes à l'emploi, et ce qu'une remise refuse de cumuler (12/10)** `/admin/settings`, `TicketService`, `TicketState` [E/S] (BO-03-07-03, -06, -11 et -12)

Le moteur de remises était complet ; trois clés manquaient pour que le magasin décide **ce qui se cumule**. C'est le même constat que les plages de codes-barres : le mécanisme est là, l'administrabilité non.

- **`discount.presets` (BO-03-07-03 et -06)** — des remises nommées, prêtes à l'emploi : `libellé>PERCENT:10` ou `libellé>AMOUNT:1,50`, avec une virgule décimale acceptée comme partout ailleurs dans le catalogue, et un `,CUMUL` final optionnel. Une entrée mal formée est **écartée** (pas de valeur, valeur non numérique, nature inconnue, libellé vide) : une clé fautive réduit la liste, elle ne casse pas l'écran. Coté 1a et non 1 : les remises administrées s'appliquent par les gestes ordinaires (`applyPreset` rejoint `applyDiscount` / `applyRemise`), la touche dédiée dans la modale de modification de prix reste à poser.
- **`discount.cumulative-on-discounted` (BO-03-07-11)** — une **seconde** remise sur une ligne déjà réduite. Vrai par défaut : le comportement historique est intact. À faux, la seconde remise est refusée, et une remise portée par un préréglage `CUMUL` passe outre la clé — **dans les deux sens** : un préréglage peut autoriser ce que la clé refuse comme refuser ce que la clé autorise.
- **`discount.global-on-discounted` (BO-03-07-12)** — la remise **globale** sur un ticket qui porte déjà des lignes réduites. À faux, les lignes déjà réduites sont épargnées **de l'assiette et de la répartition** : c'était le piège, épargner seulement la répartition aurait fait disparaître des centimes. La règle est fixée au moment où la remise globale est posée et oubliée avec le ticket.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `discountPresets` | `PERCENT` / `AMOUNT` / virgule décimale / `CUMUL` | les quatre lues comme annoncé |
| `discountPresets` | sans valeur / valeur non numérique / nature inconnue / libellé vide | entrée écartée, les autres tenues |
| `discountPreset(libellé)` | casse différente / absent / nul | résolu sans la casse, nul sinon |
| Clés de cumul | rien d'administré / administrées à faux | vrai par défaut, puis faux |
| `applyDiscount` / `applyRemise` | ligne déjà réduite, cumul permis | la seconde remise s'applique |
| `applyDiscount` / `applyRemise` | ligne déjà réduite, cumul refusé | la seconde remise est refusée |
| `applyPreset` | préréglage pourcentage / montant | la remise correspondante posée |
| `applyPreset` | libellé inconnu / ligne nulle | faux, rien posé |
| `applyPreset` | `CUMUL` contre clé à faux, et sans `CUMUL` contre clé à vrai | le préréglage l'emporte dans les deux sens |
| `allocateGlobalDiscount` | lignes réduites épargnées | hors assiette **et** hors répartition |
| `allocateGlobalDiscount` | lignes réduites non épargnées | répartition sur tout le ticket |
| `allocateGlobalDiscount` | ligne que le moteur n'a pas réduite | jamais épargnée |
| `clear` | ticket suivant | la règle d'épargne est oubliée |

**L16 Trois choses de plus qu'une plage de codes-barres peut vouloir dire (12/10)** `/admin/coupons`, `ui/scanner` [E/S] (BO-03-06-43, -41 et -44)

Même constat que pour la destination d'une plage : le vocabulaire positionnel dit **comment lire** un code, il ne disait pas tout ce qu'un code peut **vouloir dire**. Trois significations de plus, sur la même table et le même écran. Les trois sont cotées **1a**, et la raison est la même dans les trois cas : ce qui manque n'est pas chez nous.

- **`PROMOTION` + `promotion_code` (BO-03-06-43)** — une plage **nomme** une promotion ; le scan arme ce nom sur le ticket et le panier le porte au moteur dans un bloc dédié (`scannedPromotions`, nul quand rien n'a été scanné, donc la requête reste octet pour octet celle que le moteur recevait). Un NOM et rien d'autre, délibérément : la caisse ne sait pas ce que vaut une promotion et n'a pas à le décider — c'est une règle que le moteur possède, exactement comme pour une promotion portée par la carte. Le second scan du même code est une **mise en garde** et non un refus ; une plage PROMOTION qui ne nomme rien est refusée haut et fort, parce qu'armer un nom vide enverrait au moteur une promotion que personne ne peut résoudre. **1a** : la synthèse de l'offre à partir du nom reste à écrire côté imvaluation.
- **`EXTERNAL_LOOKUP` + `lookup_url` (BO-03-06-41)** — certains codes ne sont pas à ce référentiel de les lire : un bon de partenaire, une souche de billetterie, un coupon imprimé par un système que personne ici ne modélise. L'URL nomme le service qui sait, et peut porter `{code}` là où le code lu doit aller (sinon il est ajouté à la fin). Trois sorties, et la caisse dit laquelle : la réponse nomme un article du catalogue → la ligne est passée **au prix du magasin** (le tiers a dit ce que le code EST, le magasin dit toujours ce qu'il coûte) ; la réponse ne nomme rien de connu → elle est affichée **telle quelle**, parce qu'un partenaire qui refuse un bon écrit la phrase que le client doit entendre ; pas de réponse du tout → l'opérateur est prévenu et le code est consommé, car le laisser tomber en « code inconnu » serait un mensonge sur ce qui s'est passé. Deux secondes de patience au maximum : un client en caisse n'attend pas la panne d'un tiers. **1a** : le format de réponse d'un partenaire donné reste à cartographier — ici la réponse est lue comme du texte, pas comme un schéma inventé.
- **`ACTIVATION_REQUIRED` + `ACTIVATION_PROVIDER` (BO-03-06-44)** — le produit démat. Le drapeau est une **propriété article** et non une colonne de plus : le questionnaire demande « le flag d'activation au catalogue produit », et le catalogue d'attributs est le véhicule administré qui existe déjà (treize attributs côté caisse, quinze maintenant). La ligne est passée **d'abord** — le client la paie — puis la caisse demande le code d'activation, et le scan suivant répond. Le code est conservé sur la ligne en mémoire **et** sur la ligne de ticket persistée, avec le fournisseur qui le doit : un client qui revient trois semaines plus tard avec une carte cadeau qui n'a jamais marché est répondu par la ligne vendue, et par rien d'autre. **1a** : la transmission au fournisseur attend son protocole — même refus que pour la monétique Verifone, on ne fabrique pas une trame que personne n'a spécifiée.

**Le handler d'activation est disjoint par l'ÉTAT et non par la forme.** Un code d'activation n'a pas de forme : c'est ce que le fournisseur a imprimé. Ce qui se sait, c'est si la caisse **attend** une réponse. `ActivationCodeScanHandler` est seul en priorité **-1**, avant toute forme testée, et tant que l'attente est ouverte il avale tout, badge compris — délibérément : la caisse a posé une question et traite le scan suivant comme la réponse. Le contrat de chaîne dans `ScanContext` le dit, parce que c'est une décision et pas un accident. Une attente est fermée **quoi qu'il arrive** : si la ligne attendue a disparu (annulée sous la main de l'opérateur), l'opérateur est prévenu et l'attente se referme, plutôt que de laisser la caisse avaler tous les scans suivants.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `triggersPromotion` | autre destination / nom nul / nom blanc / nom posé | faux, faux, faux, vrai |
| Plage PROMOTION | code lu | promotion armée, message, **aucune ligne** |
| Plage PROMOTION | même code deux fois | mise en garde, pas un refus |
| Plage PROMOTION | aucun nom administré | refusé et dit |
| Panier moteur | promotions armées / aucune | bloc porté / bloc absent |
| `hasExternalLookup` | autre destination / URL nulle / URL blanche / URL posée | faux, faux, faux, vrai |
| `lookup` | URL avec `{code}` / sans | code substitué à sa place / ajouté à la fin |
| `lookup` | statut < 200 / ≥ 300 / réponse nulle | aucune réponse |
| `lookup` | corps nul / blanc / trop long | rien, rien, tronqué à 200 |
| `lookup` | coupure réseau / interruption / URL mal formée | nul, nul **et drapeau reposé**, nul |
| Plage EXTERNAL_LOOKUP | réponse = EAN / = PLU | ligne passée au prix du magasin |
| Plage EXTERNAL_LOOKUP | réponse inconnue du catalogue | affichée telle quelle, aucune ligne |
| Plage EXTERNAL_LOOKUP | service injoignable / hors CDI | prévenu, code consommé |
| `activationRequired` | absent / vrai / faux / produit nul | faux, vrai, faux, faux |
| `activationProvider` | absent / blanc / nommé | nul, nul, nom élagué |
| Article démat scanné | ligne passée | attente ouverte sur CETTE ligne + demande à l'écran |
| Article ordinaire | ligne passée | aucune attente, aucun fournisseur |
| Scan suivant | code d'activation | stocké élagué sur la ligne, attente fermée |
| Scan suivant | code nul / blanc | attente **maintenue ouverte** |
| Scan suivant | ligne attendue disparue | prévenu, attente fermée quand même |
| Persistance | ligne démat / ligne ordinaire | code et fournisseur portés / nuls |
| `clear` | ticket suivant | ni promotion armée ni attente d'activation |

**L17 Ce qu'on peut administrer d'une monétique dont la trame n'existe pas (12/10)** `/admin/tenders`, `/admin/settings`, `VerifoneTerminalClient` [E/S] (BO-03-09-30 et -31)

Ces deux lignes étaient à **3** avec une raison vraie : le constructeur de requête de `VerifoneTerminalClient` est un `TODO(spec Verifone)` — code message, numéro de caisse, format du montant, devise, discriminant d'opération — et il n'y a donc pas de champ où poser quoi que ce soit. Ce constat ne bouge pas. Mais en relisant mes propres commentaires, la moitié de chaque ligne ne dépend **pas** de la trame : « porter le code produit sur le moyen de paiement » et « ajouter au catalogue le paramètre qui active son ajout » sont de l'administration, et l'administration, elle, se fait. Les deux passent donc à **1a**.

- **`vic_product_code` sur le moyen de paiement (BO-03-09-30)** — le code produit VIC de la carte multiproduit, là où l'exigence le demande : **sur le moyen de paiement**, pas dans une table de plus. Stocké **verbatim**, tel que l'acquéreur l'écrit (`{DAJ}01` Mealpass, `{DBJ}01` Ecopass, `{DCJ}01` Gift pass) : la paire qu'un tag et sa valeur forment est à l'acquéreur de la dire, pas à nous de la composer. Écran `/admin/tenders`, diffusion par le domaine `TENDERS`, empreinte incluse. La caisse ne retient que les moyens **en service** : déclarer au terminal un produit que la caisse refusera ensuite, c'est le proposer au client pour rien.
- **`terminal.pdv-label` + `terminal.pdv-label-enabled` (BO-03-09-31)** — le Label Point De Vente et, exactement comme le commentaire le demandait, **la clé qui active son ajout**. Même doctrine verbatim : le libellé est saisi sous la forme `{TAG}valeur` et émis sans retouche. Aucun tag n'est inventé — c'est là tout l'intérêt de la saisie verbatim, et c'est ce qui distingue ce lot d'une trame fabriquée. Le catalogue de paramètres passe à **216** clefs des deux côtés.
- **La lecture est faite par le codec de la trame elle-même.** `VerifoneFrameCodec` lit déjà la syntaxe `{TAG}valeur` — celle dans laquelle le questionnaire écrit ces codes. Les paires administrées sont donc décodées par le même code que la réponse du terminal : zéro supposition.
- **Ce qui reste à 3 dans ces deux lignes** : la **place** de ces paires dans la requête. Elles sont ajoutées en fin de trame, ce qui est la seule chose honnête à faire d'une paire dont personne n'a spécifié la position. Et `WimPaymentTerminal`, que citaient les commentaires éditeur, n'existe toujours pas dans ces dépôts.
- **Le référentiel est une addition, jamais une condition.** La lecture des codes VIC se fait sur le fil d'échange, sous garde : un référentiel injoignable — pas de contexte de persistance, un hoquet de base au mauvais moment — ne doit pas coûter son paiement au client. Le paramètre administré est **transmis** au client monétique et non injecté, pour la raison qui vaut déjà pour le résolveur de messages : cette classe est construite par `TerminalClientProducer` et pas par le conteneur.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `activeVicProductCodes` | moyen en service avec code | retenu, élagué, dans l'ordre administré |
| `activeVicProductCodes` | hors service / sans code / code blanc | écarté (chaque jambe) |
| `activeVicProductCodes` | rien d'administré | liste vide, requête inchangée |
| `buildRequest` | codes VIC administrés | paires ajoutées verbatim, dans l'ordre |
| `buildRequest` | référentiel injoignable | requête construite quand même |
| `buildRequest` | label activé (avec espaces) | ajouté élagué en fin de trame |
| `buildRequest` | label désactivé | rien d'ajouté |
| `buildRequest` | label blanc / nul / service absent | trame identique à celle d'avant la clé |
| Clés d'écran | rien d'administré | ajout désactivé, label vide |
| `/admin/tenders` | code posté (avec espaces) / blanc | élagué / **nul**, pas une paire vide |
| Diffusion | code nommé par le central / absent | imposé / celui du magasin conservé |
| Empreinte `TENDERS` | code VIC posé | empreinte changée |

**L18 Ce que le central gèle, ce que le magasin garde, et le papier d'un règlement rendu (12/10)** `/admin/tenders`, `/admin/settings`, `PaymentService` [E/S] (BO-03-02-06, -07, -35 et -43)

Quatre lignes à **3** dont les commentaires éditeur réclamaient de créer trois choses qui **existent déjà** : le référentiel des modes de règlement, le moteur de gabarits de documents, et la commande d'endossement de l'imprimante. Les quatre passent à **1**.

- **La plage centrale figée (BO-03-02-06 et -07)** — « créer le référentiel de modes de règlement (identifiant sur 3 digits, écran, export) » : il existe depuis le lot 03-02 (`TenderDefinition.functionalId`, `MIN_FUNCTIONAL_ID_LENGTH = 3`, écran `/admin/tenders`, export CSV, domaine `TENDERS`). Ce qui manquait était le **découpage**. Une clé le porte : `tender.local-range` énumère les identifiants que le MAGASIN peut modifier, sous la forme qu'un acheteur écrit sur le papier (`500-599,900`) ; tout le reste est central. Hors de la plage, l'écran n'enregistre **que l'activation** — le reste de la description postée est jeté plutôt qu'appliqué à moitié —, un identifiant central ne se **crée** pas en magasin, et un mode central ne se **supprime** pas (le supprimer rendrait à la caisse ses propres défauts, exactement ce que le central a figé : il se désactive). La plage est lue sur l'identifiant **posté** autant que sur le stocké, pour qu'on ne sorte pas un mode de la plage centrale en le renommant.
- **Et l'héritage par échelon, sans une ligne de code de plus.** Le commentaire demandait « un découpage central/local dans `EchelonSettingService` ». La plage ÉTANT une clé du catalogue, elle descend déjà par les cinq échelons existants (PAYS → ENSEIGNE → PDV → ÎLOT → POINT DE CONTACT) : le central pose la plage au pays ou à l'enseigne, chaque PDV l'hérite, et un magasin ne peut pas se l'élargir puisque la clé vient d'au-dessus. Le mécanisme était là ; il suffisait de lui donner quelque chose à porter. **Vide : le magasin modifie tout**, ce qu'il pouvait avant que la règle existe.
- **Le ticket d'annulation de règlement (BO-03-02-35)** — « ce qui suppose de créer le moteur de modèles de document absent » : **faux**, le moteur a vingt-quatre types, vingt-cinq maintenant. `PAYMENT_CANCELLATION` est un type comme les autres : mise en page redéfinissable par gabarit, starter intégré en secours, papier coupé. Un règlement défait est un **événement** et non un non-événement : le client a tendu un chèque ou une carte et la caisse les a rendus. Le papier dit quel moyen, pour combien, sur quel ticket et quand. Émis sur les **deux** chemins d'annulation — un ticket par règlement dans l'annulation globale (imprimés **avant** que la liste soit vidée, après il n'y a plus rien à nommer), et le seul règlement retiré dans l'annulation unitaire (EXP-63). Le libellé est celui que le référentiel administre, la clé de règlement sinon. **Une panne d'imprimante ne fait jamais échouer l'annulation** : rendre le chèque est le geste, le papier en est le témoin.
- **L'endos administré (BO-03-02-43)** — « commande d'endossement de l'imprimante » : **elle existe**. `ChequeReadingService.read(endorsement, …)` imprime déjà au dos pendant que le lecteur tient le chèque, et `PaymentService.endorsementText()` composait la date et le montant **en dur**. La clé `cheque.endorsement-lines` administre ce contenu, une ligne par point-virgule, avec les jetons `{date}`, `{heure}`, `{montant}`, `{caisse}`, `{pdv}`. Une ligne administrée vide n'imprime rien plutôt qu'une bande blanche — le dos d'un chèque est étroit — et un jeton auquel la caisse ne sait pas répondre rend **vide** : un chèque portant `{pdv}` à l'encre est pire qu'un chèque ne portant rien là.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `inRange` | `007` dans `001-099` / bornes incluses / `100` | vrai, vrai, faux |
| `inRange` | identifiant seul / liste mêlant plage et singleton | reconnus |
| `inRange` | identifiant non numérique | match littéral seulement |
| `inRange` | borne mal formée / partie vide | ignorée, les saines répondent |
| `inRange` | identifiant nul / blanc / plage nulle / blanche | faux (chaque jambe) |
| `locallyModifiable` | plage absente / blanche | vrai : tout modifiable |
| `locallyModifiable` | hors plage / dans la plage | faux / vrai |
| `/admin/tenders` | mode central enregistré | **seule l'activation** posée, le reste intact |
| `/admin/tenders` | mode local enregistré | administré comme avant |
| `/admin/tenders` | identifiant central créé en magasin | refusé |
| `/admin/tenders` | mode central supprimé | refusé, rien d'effacé |
| `printPaymentCancellation` | moyen nul / blanc / montant nul / zéro / négatif | zéro, rien d'imprimé |
| `printPaymentCancellation` | moyen et montant posés | moyen élagué, montant, ticket, papier coupé |
| `printPaymentCancellation` | ticket inconnu | ligne vide, jamais « null » |
| `printPaymentCancellation` | gabarit administré | la mise en page du magasin |
| `cancelOnePayment` | index valide / index refusé | un ticket / aucun |
| `cancelPayments` | deux règlements / aucun | deux tickets / aucun |
| Endos | rien d'administré | date puis montant, comme avant la clé |
| Endos | lignes administrées avec jetons | une ligne par point-virgule, jetons remplis |
| Endos | ligne administrée vide | sautée |
| Endos | jeton sans réponse (`{caisse}`, `{pdv}` hors CDI) | rendu vide, jamais le jeton à l'encre |
| Clefs d'écran | rien d'administré | plage vide, endos vide |

**Ce qui reste à 3 dans 03-02**, et pourquoi : le ticket CB (BO-03-02-34, trame d'**impression** Verifone), la borne de paiement (BO-03-02-36, le **pilote** de monnayeur — l'administration existe depuis le lot 03-09 : protocole, adresse et port par point de contact), le chèque auto (BO-03-02-24, la demande d'autorisation chèque — protocole acquéreur), et les deux lignes de caisse libre-service (BO-03-02-27 et -29) qui demandent un **parcours client complet** : l'attribut existe (`TpvType`, trois familles libre-service), l'écran n'existe pas, et le `SupervisorCallRegistry` que cite le commentaire -29 n'existe pas non plus dans ces dépôts — deuxième classe fantôme après `WimPaymentTerminal`.

**L19 Sept lignes dont les commentaires étaient périmés (12/10)** `/admin/touches`, `/admin/articles`, `ui/scanner`, `ui/hardware` [E/S] (BO-03-03-09, -11, BO-03-01-18, -19, -20, BO-03-06-58, BO-03-09-27)

Sept lignes à **3** dont les commentaires éditeur réclamaient de créer des choses qui existaient : l'entité acompte, le solde d'une carte cadeau, le modèle de l'îlot et de la caisse, l'entité commande. Quatre inventaires faux de plus, après les six de la journée.

- **Le justificatif d'acompte (BO-03-03-09)** — « adossé à une vraie entité acompte qui n'existe pas » : **faux**, `CustomerOrder.depositPaid` existe depuis EXP-85, avec `OrderKind.isDepositAllowed()`. Un vingt-sixième type de document : la commande, le client, le montant, le récapitulatif TVA **tendu** par l'appelant (un acompte découpe la TVA de la commande qu'il règle, et cette imprimante n'interroge rien), et son numéro unique **en code-barres** pour que le solde se règle en le scannant. Rien n'est imprimé sans numéro ni sans montant positif : une preuve que personne ne peut retrouver n'est pas une preuve. **1a** : l'émission attend la commande **au niveau caisse** — impos ne porte aucune entité commande, elle vit sur le nœud magasin.
- **Le justificatif de paiement par carte cadeau (BO-03-03-11)** — « le solde restant n'est pas porté non plus » : **faux**, `GiftCardIssuerClient.IssuerVerdict(valid, balance)` le renvoie et le règlement y est plafonné (EXP-66). Vingt-septième type, distinct du **bon d'émission** : celui-ci est pour le client qui **dépense** une carte, et ce qu'il ajoute est le solde qu'elle porte encore — la réponse de l'émetteur **moins** ce qui vient d'être dépensé, jamais négatif. Le numéro est imprimé **masqué**, quatre derniers caractères : une carte cadeau est de la monnaie au porteur, et un numéro entier sur un ticket laissé dans un caddie est une carte qu'un autre dépense. Émis au règlement, une panne d'imprimante ne le fait jamais échouer.
- **La désactivation d'un article par îlot ou par caisse (BO-03-01-18)** — « modéliser l'îlot et la caisse » : **faux**, les deux entités existent depuis 03-09/03-10. Deux propriétés article de plus (`DISABLED_ISLANDS`, `DISABLED_TERMINALS`), sur le véhicule administré qui existe déjà et qui est déjà diffusé avec le produit — ni table ni domaine de plus. **Une question, cinq demandeurs** : l'EAN catalogue, le code interne, le PLU tapé, l'étiquette balance et la charge GS1 mènent au même article par des routes différentes, et un article sorti d'un îlot ne doit pas y rentrer par la balance. La décision vit donc dans `ArticleScopeService`, une fois, et les cinq chemins la consultent. Une caisse que le back-office ne décrit pas **continue de vendre** : une caisse absente du référentiel est un problème de référentiel, pas une raison de refuser un client.
- **Les traductions, groupes (BO-03-01-19) et articles (BO-03-01-20)** — une table de traduction, un domaine de diffusion et un écran de saisie étaient demandés ; **deux colonnes ont suffi**, sur les deux véhicules administrés qui portent déjà ces objets : `TouchGroupSetting.labels` pour les groupes (et non la nomenclature — celle-ci est remplacée en entier à chaque intégration de la gestion commerciale, un libellé que le magasin a tapé pour ses clients néerlandophones doit y survivre) et l'attribut `TRANSLATED_LABELS` pour les articles. Même syntaxe des deux côtés, `nl=Zuivel;pt=Lacteos`, parce qu'un magasin n'a à en apprendre qu'une. La langue est celle où la caisse est réglée (`language.system`, `language.available`, le mécanisme était là), et le repli est le libellé du catalogue — ce que chaque touche et chaque ligne portaient avant que les traductions existent. Les libellés d'article passent par **un seul accesseur** consulté par les six chemins d'entrée.
- **La commande rappelée par code-barres (BO-03-06-58)** — « créer l'entité commande/devis » : **faux**, `CustomerOrder` existe. Septième destination de plage, `CUSTOMER_ORDER` : la référence est lue **brute** du segment article, zéros de tête compris, parce qu'une référence est une clé que le magasin rapproche caractère par caractère — le même raisonnement que la référence de ticket comptoir. **1a** : ce que la caisse FAIT de la référence attend la commande au niveau caisse, et l'import Mercalys/SAP est hors de ces dépôts.
- **L'application tierce en caisse (BO-03-09-27)** — clé `touch.external-apps`, `LIBELLÉ>URL` séparés par des points-virgules, entrée mal formée écartée. L'appel depuis le poste passe par `/external-app/{rang}` : la caisse résout l'entrée par son **rang administré** et redirige, pour qu'aucune adresse ne voyage dans une URL de caisse — une touche qui posterait l'adresse laisserait ouvrir n'importe quoi en fabriquant un lien, ce que précisément un référentiel d'applications autorisées sert à empêcher. **1a**, et deux refus assumés : la tuile dans la barre de touches de l'écran de vente reste à poser, et le « ou exécutable » du questionnaire n'est **pas** implémenté — une caisse qui lance un binaire arbitraire parce qu'une ligne de référentiel le dit est un trou de sécurité.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `printDepositReceipt` | sans numéro / montant nul, zéro | zéro, rien d'imprimé |
| `printDepositReceipt` | commande, client, montant, TVA tendue | tout porté, numéro en code-barres |
| `printDepositReceipt` | ligne de TVA nulle / client inconnu | sautée / vide, jamais « null » |
| `maskedCardNumber` | nul / blanc / 4 caractères / plus long | vide, vide, entier, `****` + 4 |
| `printGiftCardPaymentReceipt` | solde connu / inconnu | ligne portée / ligne absente |
| Carte cadeau | solde 50, règlement 30 | solde restant 20 |
| Carte cadeau | solde 12, demande 30 | règlement 12, solde restant **zéro** |
| Carte cadeau | carte refusée | aucun ticket |
| `soldOn` | listes vides / nommant autre chose | vendu partout |
| `soldOn` | nommant cette caisse / cet îlot | refusé (casse et espaces ignorés) |
| `soldHere` | caisse absente du référentiel / hors CDI / base injoignable | **continue de vendre** |
| Scan EAN | article hors périmètre / dans le périmètre | refusé et dit / passé |
| `translatedLabel` (article, groupe) | langue administrée / absente / nulle / blanche | libellé, nul, nul, nul |
| `translatedLabel` | paire sans langue / sans libellé / vide | nul |
| `saleLabel` | traduction présente / absente | traduction / libellé catalogue |
| Empreinte `TOUCH_GROUPS` | libellé traduit posé | empreinte changée |
| Diffusion groupes | libellés nommés par le central / absents | imposés / ceux du magasin conservés |
| Plage CUSTOMER_ORDER | code lu | référence **brute** armée, aucune ligne |
| Plage CUSTOMER_ORDER | segment illisible | refusé et dit |
| `externalApps` | sans flèche / sans libellé / sans adresse / vide | écartées, les autres tenues |
| `/external-app/{rang}` | rang administré / négatif / au-delà / liste vide | redirection 303 / 404 |
| `clear` | ticket suivant | la commande rappelée est oubliée |

**L20 Le libre-service, le client de caisse et la fin de période du magasin (12/10)** `/admin/tenders`, `/admin/settings`, `PaymentService`, `TicketNumberService` [E/S] (BO-03-02-27, -29, BO-03-06-53, BO-03-12-02)

J'avais classé ces lignes « demande une surface entièrement neuve ». C'était faux pour trois d'entre elles, et ma propre arithmétique me l'a fait remarquer : si elles ne sont pas bloquées, elles sont faisables.

- **Le règlement en libre-service (BO-03-02-27)** — je disais « il faut d'abord le parcours client ». Non : la ligne parle d'un **moyen de paiement**, pas d'un écran. Deux colonnes sur le référentiel des règlements et la règle est administrable et opposable aujourd'hui. `self_service_allowed`, **vraie par défaut** pour qu'un magasin qui n'ouvre jamais cette partie continue d'offrir tous les moyens partout. Une caisse sans personne derrière ne peut ni endosser un chèque ni vérifier une signature : le moyen que le magasin y interdit est refusé à l'UNIQUE entonnoir par lequel tout règlement passe (`handlePaymentWithChange`), avant que rien ne soit enregistré.
- **La supervision du règlement en libre-service (BO-03-02-29)** — `self_service_supervision`, distincte de l'interdiction : un magasin peut laisser une caisse libre-service prendre un titre-restaurant **et** vouloir que quelqu'un le regarde. L'appel réutilise le **maintien superviseur** que toute règle de règlement administrée lève déjà, donc l'hôtesse voit le panneau qu'elle connaît, et il est sauté quand une autorisation est déjà en main — sinon on appellerait l'hôtesse pour le règlement qu'elle vient d'autoriser. **1a** : administré par moyen de paiement, la nuance par îlot n'est pas modélisée. `SupervisorCallRegistry` que citait le commentaire n'existe toujours pas, et n'est pas nécessaire.
- **Les deux règles sont lues sur la CAISSE, pas sur sa croyance.** Le type de point de contact vient du référentiel que le back-office a distribué (`CheckoutPoint.isSelfService()`), et une caisse que le référentiel ne décrit pas est traitée comme **tenue** : une caisse absente du référentiel est un problème de référentiel, pas une raison de refuser un client au moment précis où il paie.
- **Le client créé en caisse (BO-03-06-53)** — « introduire l'entité client et sa création en caisse (écran ui/ dédié) » : **l'entité ET la création existent**. `InvoiceService.createCustomer` ouvre un `AccountCustomer` en `Origin.VOLATILE` depuis le parcours facture, le numérote, le sauve et le déclare au back-office par l'outbox (LC-08-04-08/09, BO-10-04-01) — c'est exactement « pour les demandes de facture ». Ce qui manquait est l'autre moitié de la phrase : **la plage de codes**. `customer.local-code-range`, sous la forme `PRÉFIXE:PREMIER-DERNIER`, et le numéro est la borne basse plus ce que cette caisse a déjà créé, pour qu'un client ouvert en caisse ne collisionne jamais avec un client intégré. Plage absente, illisible ou **épuisée** : retour à la numérotation par caisse — une caisse à bout de numéros réservés doit encore pouvoir imprimer la facture du client qui est devant elle.
- **La fin de période du point de vente (BO-03-12-02)** — le commentaire demandait trois choses ; **deux existaient**. Le calendrier d'ouverture, ce sont `financial.closed-weekdays` et `financial.closed-dates` ; les paramètres d'automatisation, ce sont `period.auto-close-enabled`, `period.end-time` et `period.close-lead-minutes`. Et comme ce sont des clés du catalogue, le central les pose **à l'échelon PDV** et chaque caisse les hérite : le « niveau point de vente » ne demandait pas une ligne de code. La troisième chose, à savoir ce que le MAGASIN enchaîne à ce moment-là, est maintenant administrée : `period.pdv-actions` (`CLOSE_REGISTERS;SAFE_COUNT;Z_REPORT;BANK_DEPOSIT`, dans l'ordre écrit, nom inconnu ignoré, doublon gardé une fois). **1a** : l'objet de clôture consolidée au-dessus des sessions reste à construire.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Règlement libre-service | moyen interdit, caisse libre-service | refusé et dit, rien d'enregistré |
| Règlement libre-service | moyen surveillé, caisse libre-service | maintien superviseur, rien d'enregistré |
| Règlement libre-service | caisse tenue / caisse non décrite | rien d'opposé, règlement passé |
| `/admin/tenders` | cases cochées / décochées | les deux règles posées / à faux |
| Diffusion `TENDERS` | les deux règles | portées verbatim, empreinte changée |
| `nextCustomerNumber` | plage administrée | borne basse + créations de cette caisse |
| `nextCustomerNumber` | sans deux-points / sans tiret / sans préfixe | repli caisse |
| `nextCustomerNumber` | bornes illisibles / inversées / **épuisées** | repli caisse |
| `nextCustomerNumber` | rien d'administré / hors CDI | repli caisse |
| `periodPdvActions` | liste administrée | dans l'ordre écrit |
| `periodPdvActions` | nom inconnu / doublon / entrée vide | ignoré, gardé une fois, sautée |
| `periodPdvActions` | rien d'administré / blanc | liste vide |

**Pas faite : BO-03-03-15** (logo par pays / enseigne / PDV). C'est la seule des cinq que je laisse, et la raison est structurelle : les dix-huit domaines de diffusion ne portent **aucun** objet d'échelon vers la caisse, et le logo est binaire — une valeur de paramètre est plafonnée à 255 caractères. Il faut donc un dix-neuvième domaine (ou une charge « magasin »), trois colonnes d'image côté central avec leurs écrans d'import, la résolution « l'échelon le plus proche gagne » à l'export, et `ReceiptLogo` — aujourd'hui une ressource unique du classpath, en cache statique — qui rastérise ce que le référentiel a envoyé. L'impression graphique, elle, existe déjà.

**L21 Quatre modèles de ticket fidélité, et pourquoi ce sont quatre types et pas quatre branches (14/10)** `/admin/documents`, `/admin/settings`, `TicketPrinterService` [E/S] (BO-10-03-05, -11, -12, -13 et -21)

Les commentaires éditeur de ces cinq lignes demandaient tous la même chose : « introduire des modèles de ticket administrables, entité modèle, moteur de rendu en remplacement de la mise en page en dur de `TicketPrinterService` ». Le moteur existe depuis HD_35 et porte vingt-huit types de document. Ce qui manquait n'était pas le moteur, c'était **le choix du gabarit par cas**.

- **Quatre cas, quatre types de document.** `LOYALTY_ONLINE`, `LOYALTY_OFFLINE`, `LOYALTY_NON_HOLDER_ONLINE`, `LOYALTY_NON_HOLDER_OFFLINE` — une carte a été présentée ou non, le programme a répondu ou non. Ce sont quatre **types** et non quatre branches d'un seul, parce que l'écran d'administration dérive sa liste de valeurs disponibles des données d'exemple du type : un paramétreur qui ouvre le gabarit « non porteur » ne doit pas y voir proposés un numéro de carte et un solde que son cas ne porte jamais. C'est aussi pourquoi ces types ne sont pas des documents : ils n'ont ni adresse de magasin ni numéro de ticket, ils sont une **zone** imprimée à l'intérieur d'un ticket qui porte déjà les deux.
- **Cinq clés, pas huit.** `fidelity.template-online`, `-offline`, `-non-holder-online`, `-non-holder-offline` nomment le gabarit de chaque cas ; `fidelity.template-grouped` (BO-10-03-21) prend le pas sur le premier **quand, et seulement quand, le regroupement des avantages vaut `CATEGORY`** — c'est le seul cas où une mise en page regroupée a quelque chose à regrouper. Un porteur hors ligne n'a pas d'avantages, un non porteur n'a pas de carte : leur donner une variante regroupée aurait été trois clés de plus pour aucun ticket de plus.
- **Vide veut dire « rien », pas « le gabarit nommé par la chaîne vide ».** Une clé blanche, c'est la caisse qui imprime sa zone intégrée — ce que fait tout magasin tant qu'un paramétreur n'est pas passé. Aucun ticket existant ne change de forme du fait de ce lot.
- **Le gabarit administré remplace la zone, il ne s'y ajoute pas.** Les cinq blocs intégrés — numéro de carte, cagnotte du jour et avantages, soldes, message hors ligne, mot au non porteur — sont tous conditionnés à l'absence de gabarit. Sans cela, un magasin qui écrit sa propre zone verrait la sienne **et** celle de la caisse.
- **Un gabarit nommé mais introuvable, ou qui ne compile pas, rend `null`** et la zone intégrée reprend la main. C'est la doctrine des justificatifs de règlement, et la raison est la même : une mise en page que personne ne sait rendre ne doit pas coûter son ticket au client.
- **Les données d'exemple du nœud ont été remises au niveau de celles de la caisse** — type, catégorie et rang d'avantage, soldes prévisionnel et après débit, projection anonyme. Elles avaient divergé, et une clé que l'aperçu ne propose pas est une clé que le paramétreur n'écrit pas.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `fidelityTemplateCode` | carte + en ligne + regroupé, gabarit regroupé posé | le gabarit regroupé, valeur détourée |
| `fidelityTemplateCode` | carte + en ligne + regroupé, gabarit regroupé vide | le gabarit porteur en ligne |
| `fidelityTemplateCode` | carte + en ligne, sans regroupement | gabarit porteur en ligne |
| `fidelityTemplateCode` | carte + hors ligne (regroupement demandé) | gabarit porteur hors ligne |
| `fidelityTemplateCode` | sans carte + en ligne (regroupement demandé) | gabarit non porteur en ligne |
| `fidelityTemplateCode` | sans carte + hors ligne | gabarit non porteur hors ligne |
| `fidelityTemplateCode` | rien d'administré, les cinq cas | `null` |
| `printTicket` | gabarit porteur en ligne administré | la zone administrée, **sans** le numéro de carte intégré |
| `printTicket` | aucun gabarit, regroupement par catégorie | zone intégrée, aucun rendu demandé |
| `printTicket` | gabarit nommé introuvable | zone intégrée |
| `/admin/documents` | les quatre nouveaux types | aperçu alimenté par les valeurs fidélité |

**L22 Un symbole monétaire, et les soixante-neuf endroits qui l'écrivaient en dur (14/10)** `/admin/settings`, `TicketPrinterService` [E/S] (BO-10-07-04, -05 et -06)

Le commentaire disait « les montants sont aujourd'hui suffixés en dur ». Il avait raison, et plus qu'il ne croyait : le littéral `" E"` était concaténé à **soixante-neuf endroits** du service d'impression. Aucun paramètre n'aurait pu les atteindre tous ; c'est pourquoi la ligne était non couverte et pas partielle.

- **Un seul endroit où un montant prend son symbole.** `money(String)` reçoit un montant déjà écrit et rend ce qui part à l'imprimante. Les soixante-neuf sites y passent désormais, et c'est le seul changement de forme du service : aucune mise en page n'a bougé.
- **Les valeurs par défaut rendent l'octet près ce que la caisse imprimait hier** — symbole `E`, à droite, un espace entre les deux. C'est la condition pour qu'un lot de ce volume soit sans risque : le test de référence de mise en page (`TicketLayoutGoldenMasterTest`) compare des tickets entiers caractère par caractère, et il passe inchangé.
- **« Ne pas imprimer » veut dire nu, pas « suivi d'un espace ».** L'espace séparateur appartient au symbole : un magasin qui coupe le symbole, ou qui en administre un vide, n'hérite pas d'un blanc en fin de ligne dont il ne saurait quoi faire.
- **La position inconnue retombe à droite**, comme l'ordre de ticket inconnu retombe sur l'ordre d'enregistrement : une valeur mal saisie ne doit pas changer la forme du papier.
- **Une caisse construite sans service de paramètres garde le symbole intégré** plutôt que d'échouer à imprimer — c'est le cas d'un harnais de test qui n'imprime aucun document paramétré, et la même doctrine que le gabarit introuvable.
- **Ce qui n'est pas couvert, et pourquoi.** BO-10-07-05 demande aussi les deux textes du chèque — « Euro(s) », « Centime(s) » — pour l'écriture du montant en lettres. Il n'existe aucun module d'impression de chèque dans ces dépôts (BO-03-02-24 est non couvert pour la même raison), et administrer deux clés que personne ne lit serait une coquille. La ligne reste donc **partielle**, avec son écart nommé.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `money` | défauts du catalogue | `2,00 E`, identique à l'existant |
| `money` | position `left` (casse libre) | `EUR 2,00` |
| `money` | position inconnue | à droite |
| `money` | impression coupée | montant nu, sans espace |
| `money` | symbole blanc | montant nu, sans espace |
| `money` | service de paramètres absent | symbole intégré |
| `currencySymbol` / `-Printed` / `-Position` | lignes posées / rien d'administré | valeurs posées / `E`, vrai, `RIGHT` |

**L23 Cinq règles de caisse qui n'avaient pas de clé (14/10)** `/admin/settings`, `AuthScanHandler`, `EanScanHandler`, `LockCheckFilter`, `TicketPrinterService` [E/S] (BO-10-02-13, -15, -20, -28 et -32)

Cinq lignes du bloc Règles — Caisse décrivaient chacune un comportement que la caisse a déjà, à un paramètre près. Aucune ne demandait de mécanisme ; toutes demandaient **la clé et le point d'application**.

- **La touche entrée valant TOTAL (BO-10-02-13)** — `keyboard.enter-as-total`, exposée aux gabarits par le même global Arc que le thème, donc **sans plomberie par écran**. Elle ne porte que sur la frappe **à vide** : un code tapé est validé par entrée dans les deux cas, et un magasin qui laisse la clé à faux ne voit aucune habitude changer.
- **La vente pendant le règlement (BO-10-02-15)** — `sale.allow-during-payment`, **fausse par défaut**. La règle vit dans `TicketService.saleRefusedDuringPayment` et non dans chaque handler : un article entre par le scan, par un code tapé ou par un PLU, et les trois doivent répondre la même chose. Au scan, le code refusé est aussi **consommé** — sans cela il continuerait sa route et un handler derrière l'enregistrerait par un autre chemin.
- **Le second code au ticket (BO-10-02-20)** — `ticket.second-code-print`, et le code interne **gelé sur la ligne** (`TicketLine.secondCode`) au moment où elle est créée, à côté de la famille et pour la même raison : un duplicata trois mois plus tard doit redire ce que le client a eu en main, pas ce que le référentiel porte aujourd'hui. Un article sans second code n'imprime rien, pas une ligne indentée vide.
- **Verrouillage ou fermeture sur inactivité (BO-10-02-28)** — `auth.idle-action`. `LOCK` est le défaut et c'est l'existant (EXP-12 : l'opérateur est mis de côté et reprend sa vente avec son seul code) ; `CLOSE` appelle `logout()` et la caisse redevient libre, prise de poste complète. Une valeur inconnue retombe sur `LOCK` : une saisie fautive ne doit pas déconnecter une caissière au milieu d'un panier.
- **Le préfixe du code-barres superviseur (BO-10-02-32)** — `scan.supervisor-prefix`, et c'est la seule des cinq qui change une règle de sécurité. Tant qu'un préfixe est posé, **une identification superviseur n'est acceptée que d'un code préfixé** : le code seul, celui qui se tape, n'endosse plus rien. La prise de poste, elle, est l'inverse — le badge la prend, le code-barres superviseur non. Le préfixe est **retiré avant lecture**, donc l'identifiant stocké reste celui de l'opérateur. Préfixe vide : rien ne change.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `saleRefusedDuringPayment` | rien en cours | passe, quelle que soit la règle |
| `saleRefusedDuringPayment` | règlement en cours, interdit | refusé et dit |
| `saleRefusedDuringPayment` | règlement en cours, autorisé | passe, rien dit |
| `EanScanHandler` | article scanné pendant le règlement | refusé **et consommé**, aucune ligne |
| `AuthScanHandler` | code préfixé / nu, avenant actif | endosse (préfixe retiré) / ignoré |
| `AuthScanHandler` | code nu / préfixé, caisse verrouillée | prend le poste / ignoré |
| `LockCheckFilter` | délai écoulé, `LOCK` (défaut) | caisse en pause, opérateur de côté |
| `LockCheckFilter` | délai écoulé, `close` (casse libre) | caisse verrouillée, personne de côté |
| `printTicket` | second code demandé / non demandé | imprimé sous l'article / absent |
| `printTicket` | article sans second code | aucune ligne indentée |
| Les cinq clés | lignes posées / rien d'administré | valeurs posées / défauts = existant |

**L24 Deux ou trois décimales, et le calcul qui doit suivre (14/10)** `/admin/settings`, `InvoiceDocument` [E/S] (BO-10-02-19)

L'exigence demande six figures configurables sur deux ou trois décimales — prix unitaire HT, total HT de ligne, base HT et montant de TVA, total HT et total TTC. Cinq existent sur le document commercial ; la sixième, le **total HT par ligne**, n'y figure pas du tout.

- **Cinq clés `invoice.decimals-*`**, et non une seule : l'exigence laisse un magasin déclarer son prix unitaire sur trois décimales et garder ses totaux sur deux, ce que demande un commerce de produits pesés. Elles sont portées par un petit `record Decimals` que le service de facture remplit à partir du catalogue, pour que la mise en page n'ait pas à connaître les paramètres.
- **Deux ou trois, et rien d'autre.** Une valeur hors plage retombe sur deux. Une facture à sept décimales serait illisible, une facture à zéro décimale énoncerait un prix qui n'est pas le prix.
- **Le calcul suit l'affichage, sinon la décimale ment.** Le prix HT était calculé à l'échelle 2 avant d'être formaté ; l'imprimer sur trois décimales aurait donné `12,230` là où le calcul donne `12,227`. La division porte désormais l'échelle administrée, et la constante qui figeait l'arrondi à deux a disparu.
- **Les documents existants ne bougent pas :** la fabrique courte, celle que tout le code appelait, passe `Decimals.standard()`, c'est-à-dire deux partout.
- **Ce qui n'est pas couvert, et pourquoi.** Le document commercial n'a pas de colonne « total HT » par ligne : il porte le prix unitaire HT, le prix unitaire TTC et le total TTC. Ajouter une sixième colonne au tableau des articles n'est pas un paramètre, c'est une refonte de la mise en page du document, et elle n'est pas demandée ici. La ligne reste donc **partielle**, avec cet écart nommé.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `invoice.decimals-*` | `3` / `7` / `0` / rien | 3 / 2 / 2 / 2 |
| `InvoiceDocument.of` | PU HT à 3, TTC à 3, reste à 2 | `12,227`, `50,79`, `56,600` |
| `Decimals.standard` | — | deux partout |
| fabrique courte | documents existants | inchangés |

**L25 Le ticket, son archive et les codes-barres qui dorment (14/10)** `/admin/settings`, `TicketPrinterService`, `TicketSearchService`, `StoredValueRetentionService`, `AccountCustomerCsvResource` [E/S] (BO-10-07-10, -11, -16, BO-10-08-13, -14, -15 et -27)

Sept lignes, trois endroits : ce que le ticket dit, jusqu'où la caisse cherche, et ce qu'elle finit par oublier.

- **Le nombre d'articles (BO-10-07-10)** — `ticket.item-count-print` : `NONE`, `TOTAL_LINE` (entre parenthèses sur la ligne du total à payer) ou `OWN_LINE` (sous le total). Le compte est lu sur le **ticket clôturé**, donc un duplicata redit le chiffre que le client a vu. Valeur inconnue : nulle part.
- **Le code-barres du ticket article inconnu (BO-10-07-11)** — `ticket.unknown-article-barcode`. Le ticket existait depuis BO-03-10-05 et portait le code en clair ; il porte maintenant la **directive `[[BARCODE …]]`** sous la transcription, pour que le rayon le rescanne au lieu de retaper treize chiffres. Le gabarit administré la reçoit aussi, par sa donnée `document.barcode`, pour la placer où il veut.
- **La profondeur de recherche (BO-10-07-16)** — `ticket.search-days`. La fenêtre de recherche d'un ticket clôturé était une **constante** de trente jours dans `TicketSearchService` ; c'est elle que l'exigence appelle « délai avant abandon ». Zéro ou négatif n'est pas une profondeur de zéro — une caisse qui ne retrouve plus rien aurait l'air cassée — mais l'absence de paramètre : les trente jours intégrés reprennent.
- **Les trois purges de codes-barres (BO-10-08-13/-14/-15)** — le balayage de rétention vit sur le **nœud** et ces instruments vivent sur la **caisse** : il fallait donc un balayage côté caisse, `StoredValueRetentionService`, et c'est le seul code neuf de ce lot. Trois familles qui ne sont pas la même : expiré jamais utilisé, daté puis épuisé, jamais daté — trois délais, administrés dans le **même catalogue** et descendus par la même diffusion. Zéro = conservation illimitée, comme sur le nœud. Il tourne à la **fermeture de session**, parce que la caisse n'a pas d'ordonnanceur et que c'est le seul moment où elle ne vend certainement pas ; une purge qui échoue est journalisée et **ne fait pas échouer la fermeture**, qui est déjà écrite.
- **L'adresse obligatoire à l'intégration client (BO-10-08-27)** — le commentaire demandait « introduire le client en compte (entité et intégration) » ; `AccountCustomer` et son flux CSV existent, et c'est d'ailleurs sur cette entité que porte déjà la purge BO-10-08-26. Ce qui manquait est le contrôle : `customer.integration-require-address`, et une ligne sans rue, sans code postal ou sans ville est **rejetée en nommant les colonnes manquantes** dans le compte rendu d'import — l'importateur sait déjà isoler une ligne fautive et poursuivre le fichier.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `printTicket` | `total_line` / `OWN_LINE` / inconnu | `TOTAL TTC (3)` / ligne dédiée / rien |
| `printUnknownArticleTicket` | code-barres demandé / non | directive présente / code en clair seul |
| `searchDays` | sans service / 90 / 0 / −5 | 30 / 90 / 30 / 30 |
| `sweep` | rien d'administré | aucune requête, zéro supprimé |
| `sweep` | trois délais | trois suppressions, somme rendue |
| `sweep` | un seul délai | une suppression, celle de sa famille |
| fermeture de session | purge en échec | fermeture écrite, échec journalisé |
| Intégration client | adresse incomplète, contrôle actif | ligne rejetée, colonnes nommées |
| Intégration client | contrôle inactif | ligne intégrée telle quelle |

**L26 Le nom sur le viseur client, et la tare qui part à la balance (14/10)** `/admin/settings`, `CustomerDisplayResource`, `TicketPrinterService` [E/S] (BO-10-03-03 et BO-10-05-04)

Deux lignes, deux données qui existaient déjà et qui n'allaient nulle part.

- **Le nom du porteur sur le viseur client (BO-10-03-03)** — `FidelityState` porte `holderLastName` et `holderFirstName` depuis la recherche fidélité, et l'écran caissier les affiche sous la règle de BO-10-03-04. Le viseur client, lui, ne les recevait pas. Clé **distincte** (`fidelity.show-holder-name-customer`), **fausse par défaut**, et ce n'est pas la même prudence deux fois : l'écran caissier fait face à une personne, le viseur client fait face à la file. Nouvelle méthode `getHolderName()` — nom en capitales, prénom tel quel — vide dès qu'il n'y a pas d'identité à énoncer : une carte scannée porte un numéro, pas une identité, et un écran ne doit pas en inventer une.
- **La tare imprimée (BO-10-05-04)** — la caisse résolvait déjà la tare (contenant > article > défaut) et l'**envoyait à la balance**, qui rend le poids net ; cette valeur n'était ensuite conservée nulle part. Elle est maintenant posée sur la ligne en mémoire (`appliedTareKg`, distincte de `tareKg` qui est la tare de l'article gardée pour une resaisie manuelle), gelée sur la ligne vendue comme la famille et le second code, et imprimée sous l'article quand `balance.print-tare` le demande. Une tare nulle n'est pas une déduction : rien n'est imprimé.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `getHolderName` | sans carte / carte sans recherche | vide |
| `getHolderName` | nom blanc / prénom blanc | vide / nom seul |
| `getHolderName` | nom et prénom | `DUPONT Jean` |
| `customerData` | règle active / inactive | nom poussé / chaîne vide |
| `printTicket` | tare 0,035 demandée | `TARE 0,035 kg` sous la ligne |
| `printTicket` | tare nulle / règle inactive | rien |

**L27 Le numéro de certification, et le pays qui le demande (14/10)** `/admin/settings`, `TicketPrinterService` [E/S] (BO-10-06-03)

Le commentaire demandait le numéro « au référentiel magasin, avec son écran, puis distribué aux caisses ». Une clé du catalogue fait tout cela sans entité ni écran neufs — et mieux : le mécanisme d'échelon la pose **à l'échelon PAYS**, d'où elle descend à toutes les caisses du pays, ce qu'un champ sur le magasin aurait demandé de recopier magasin par magasin.

- **`fiscal.certificate-number`**, vide par défaut. Vide veut dire qu'aucune mention n'est imprimée : les tickets des pays qui n'exigent rien ne changent pas d'une ligne, ce qui est la condition pour poser une mention fiscale sur un chemin d'impression commun.
- **Imprimé sur les documents de vente ET de retour**, par une seule méthode appelée aux deux endroits — c'est le mot « ensemble » de l'exigence, et le découpage évite qu'un des deux documents dérive de l'autre.
- **Ce qui n'est pas couvert, et pourquoi.** L'exigence demande aussi que le numéro figure dans **l'exportation SAF-T**. Il n'existe aucune exportation SAF-T dans ces dépôts — ni sur la caisse ni sur le nœud — et l'écrire supposerait le schéma officiel portugais, qui est le même document externe que celui qui bloque BO-03-04-27. La ligne reste donc **partielle**, avec cet écart nommé.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `fiscalCertificateNumber` | numéro posé / rien | le numéro / chaîne vide |
| `printTicket` | numéro administré (espaces compris) | `CERTIFICAT N° 0000/AT`, détouré |
| `printRefund` | numéro administré | la même ligne sur le ticket de retour |
| les deux | numéro blanc | aucune ligne |

**L28 Ce que le panier emporte, et ce que l'imprimante doit agrandir (14/10)** `/admin/settings`, `ValuationService`, `TicketPrinterService` [E/S] (BO-10-03-19, -20, BO-10-07-14, BO-10-09-09)

Quatre lignes du bloc Règles, dont **deux n'avaient besoin de rien** : la recette les a trouvées servies.

- **Le regroupement des avantages par catégorie (BO-10-03-20)** — déjà là. `fidelity.advantage-grouping` accepte `TYPE`, `CATEGORY` et `NONE`, et la catégorie comme son libellé voyagent sur la ligne d'avantage gelée, donc le choix ne coûte aucune seconde requête. **1a** et pas entier : l'exigence demande aussi la *priorité* de catégorie « communiquée par la réponse », et le programme n'envoie pas de rang propre aux catégories — seuls les types en ont un. Les groupes sortent donc dans l'ordre d'arrivée.
- **La promotion de péremption (BO-10-09-09)** — déjà là aussi. L'AI 17 est décodé, la date est posée sur la ligne (`gs1ExpiryDate`) **et transmise au moteur** dans `ItemDto.bestBeforeDate`. Ce qui reste — le délai qui déclenche l'offre — est une règle d'offre, administrée dans le moteur et pas dans ce back-office. La ligne est **entière** côté caisse et nœud.
- **La sous-famille au moteur (BO-10-03-19)** — `fidelity.send-subfamily`. La famille la plus profonde sous laquelle le référentiel classe l'article **est** la sous-famille, et son parent est la famille : les deux se lisent d'une seule interrogation, et c'est pourquoi elles ne sont pas cherchées séparément. Article sans famille, article inconnu, ligne sans identifiant : rien n'est envoyé plutôt qu'un code vide sous lequel le moteur regrouperait.
- **Le montant dû agrandi (BO-10-07-14)** — `ticket.due-amount-large` et une directive `[[DOUBLE]]…[[/DOUBLE]]`, de la même forme que la mise en gras déjà émise. La ligne est **alignée d'abord, encadrée ensuite** : les marqueurs ne sont pas des caractères que le cadrage a comptés — c'est l'erreur naturelle, et le Javadoc du gras la nomme déjà. **1a** : comme le gras, la directive attend son interprétation par le module matériel, qui n'est pas dans ces dépôts.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `fillNomenclature` | règle inactive | rien envoyé, référentiel non interrogé |
| `fillNomenclature` | article au PLU, famille à parent | sous-famille et famille |
| `fillNomenclature` | article à l'EAN, parent blanc | sous-famille seule |
| `fillNomenclature` | sans identifiant / inconnu / sans famille | rien |
| `printTicket` | montant dû agrandi / non | directive encadrant la ligne / aucune |

**L29 L'éco-participation, et la mémoire des intégrations (14/10)** `/admin/settings`, `/admin/sync`, `TicketPrinterService`, `RefPullService` [E/S] (BO-10-06-06, BO-10-08-10 et -23)

Deux lignes que j'avais écartées trop vite, et la recette a montré pourquoi.

- **L'éco-participation au ticket (BO-10-06-06)** — j'avais écrit « attend que l'éco-taxe soit modélisée ». Elle l'est : `ECO_TAX` est un attribut article décimal depuis le catalogue d'attributs, et la **facture la totalise déjà** (`Invoice.totalEcoTax`). Ce qui manquait était le ticket. `ticket.ecotax-print` : `NONE`, `LINE` (sous chaque article concerné) ou `TOTAL` (une ligne sous la ventilation de TVA). Le montant est **lu sur l'article comme la facture le lit**, pas gelé sur la ligne : les deux documents d'une même vente doivent énoncer le même chiffre, c'est la règle que BO-03-03-04 impose déjà au tableau de TVA. Un article sans éco-participation n'imprime rien — pas un zéro que personne ne doit. **1a** : l'exigence dit « chaque écotaxe », et il n'existe pas de référentiel d'éco-taxes — une seule valeur par article — donc le choix est global et non par éco-taxe.
- **L'audit des mises à jour du référentiel (BO-10-08-23) et sa rétention (BO-10-08-10)** — `RefState` dit ce que le nœud **détient** : une ligne par domaine, écrasée à chaque application. C'est un état, et un état n'a pas de mémoire : « quand les articles ont-ils échoué, et pourquoi » ne se lit nulle part. `RefApplyAudit` est cette mémoire — une ligne par tentative, avec son domaine, son empreinte visée, son horodatage, son issue et son motif d'échec.
  - **Écrite seulement si le magasin le demande** (`referential.update-audit-enabled`, fausse par défaut) : un nœud qui intègre quinze domaines à chaque cycle remplirait sinon une table que personne ne lit.
  - **Écrite après l'application, jamais à sa place**, et une panne d'écriture de trace est journalisée **sans faire échouer l'intégration** : une trace n'est pas une condition de fonctionnement.
  - **Purgée comme toute histoire**, par une `Policy` de plus dans le balayage de fin de période (`retention.integration-results-days`, 0 = illimité) — une trace gardée pour toujours finit supprimée à la main.
  - **Consultable** dans une carte de l'écran de supervision des synchronisations, à côté des états de tirage qu'elle complète. Traçage désactivé, la carte le **dit** au lieu de montrer un tableau vide qu'on lirait « il ne s'est jamais rien passé ».
  - **1a pour BO-10-08-23** : l'audit est au niveau du domaine (article, nomenclature, client, promotion) et non article par article — les quinze méthodes d'application ne rendent pas de compte de lignes, et leur en faire rendre un n'est pas ce que l'exigence demande.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `ecoTaxPrint` | `TOTAL` posé / rien | `TOTAL` / `NONE` |
| `printTicket` | `line`, article taxé × 2 | une ligne sous l'article |
| `printTicket` | `TOTAL`, vente taxée | une ligne, avant le règlement |
| `printTicket` | article sans éco-taxe / règle inactive | rien |
| `recordAudit` | traçage inactif | aucune ligne écrite |
| `pullOnce` | domaine appliqué / en échec | ligne APPLIED / FAILED avec son motif |
| `pullOnce` | écriture d'audit en panne | intégration poursuivie, panne journalisée |
| `/admin/sync` | traçage inactif | la carte le dit, pas de tableau vide |
| balayage | délai administré | lignes d'audit purgées au-delà |

**L30 Trois lignes trouvées en relisant les autres blocs (14/10)** `/products/import`, `/article-info`, `/admin/settings` [E/S] (BO-02-03-10, BO-04-01-38, BO-09-03-04)

Les blocs 03 et 10 épuisés, j'ai relu les 76 lignes non couvertes des neuf autres. Trois tombent sous le même constat que d'habitude.

- **Le message article à l'intégration (BO-02-03-10)** — le commentaire demandait « ajouter un message article (champ Product ou entrée du catalogue d'attributs), le porter dans l'import, la diffusion PRODUCTS et l'écran ». Tout existait **sauf une chose** : `Product.infoMessage` est une colonne des deux côtés, l'écran `/admin/articles` l'édite, la diffusion l'exporte, l'applique et l'intègre à l'empreinte, et la caisse en fait un avertissement au scan. Le **flux de la gestion commerciale** était la seule route qui ne le portait pas : une colonne `INFO_MESSAGE` de plus, et la ligne est servie. Une entrée absente laisse `null` et non chaîne vide — la caisse avertit sur un message qui existe, et une chaîne vide n'en est pas un.
- **La vérification de prix au journal (BO-04-01-38)** — le journal électronique cherche par type d'événement, et sa liste de critères est **dérivée de l'énumération** : un type de plus y apparaît tout seul. `PRICE_CHECKED` est écrit par l'écran d'information article, **trouvé ou non** — un code que personne ne reconnaît est exactement ce qu'un magasin cherche après coup. Le formulaire vide n'est pas une vérification et n'écrit rien.
- **Le compteur de tickets remis à 1 en fin de période (BO-09-03-04)** — `period.reset-ticket-counter`, appliquée à la clôture de session, au même endroit et avec la même doctrine que la purge des codes-barres : une remise à zéro qui échoue est journalisée et **ne fait pas échouer une clôture déjà écrite**. Seul le numéro de ticket repart : le numéro de session, celui de remboursement et celui de client continuent, et la **numérotation fiscale** n'est même pas sur cette ligne — une séquence fiscale qui compterait à rebours serait refusée par l'administration.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `feedProduct` | colonne `INFO_MESSAGE` présente / absente | message repris / `null` |
| `infoPage` | code trouvé / inconnu | événement écrit dans les deux cas |
| `infoPage` | formulaire vide ou blanc | rien écrit |
| `resetTicketNumbers` | compteur à 4217 | ticket à 0, session, retour et client intacts |
| clôture de session | règle active, remise en échec | clôture écrite, échec journalisé |

**L31 L'écran qui manquait : les comptes (15/10)** `/admin/accounts`, `EmployeeIdentityProvider`, `AuthService` [E/S] (BO-01-01-01, -02, -06 et BO-01-02-12)

Le back-office avait **deux** écrans autour d'un compte et **aucun ne le créait** : `/admin/employees` règle le volet financier d'une fiche qui existe, `/admin/users` rattache des profils à un titulaire qui existe. Les comptes venaient du seed. C'est ce vide qui rendait partielles quatre lignes du bloc 01 et impossible la cinquième.

- **`/admin/accounts` crée, modifie, désactive et supprime** (BO-01-01-01, -02). Un seul geste pose les **deux** secrets d'un titulaire neuf — le code caisse et le mot de passe back-office — parce qu'un compte créé avec un seul des deux est un compte sur lequel il faut revenir ; et le titulaire doit le changer à sa première entrée.
- **La suppression demande deux fois.** La confirmation que l'exigence nomme est sur le bouton, et le serveur **refuse un titulaire que l'historique nomme** : un compte qui a avalisé, compté ou vendu se désactive, il ne s'efface pas — l'effacer orphelinerait le journal qui le nomme.
- **Département et niveau hiérarchique (BO-01-01-02)** ouvrent la délégation : un compte n'administre que des comptes d'un niveau **strictement inférieur** au sien, donc un responsable gère son équipe et personne ne relève ses propres droits. Le département est un texte libre et non une énumération : l'exigence liste des exemples et finit par « etc. ».
- **Téléphone et date de début (BO-01-01-06)**, facultatifs comme l'exigence le demande. La date est déclarative et non opposable : refuser une entrée sur une date que personne n'a vérifiée à la création enfermerait un magasin dehors sur une faute de frappe.
- **Le verrouillage après échecs (BO-01-01-01)** — `failedAttempts` et `lockedUntil` étaient sur l'entité et **lus à chaque authentification** ; rien ne les écrivait jamais, donc le verrouillage ne pouvait pas arriver. Les échecs sont maintenant comptés, le seuil et la durée sont administrés (3 et 15 minutes par défaut), et une entrée réussie **efface le compteur** : des échecs comptés doivent être consécutifs, sinon deux fautes lundi et une vendredi verrouillent un compte que son titulaire ne saurait pas expliquer.
- **L'expiration de session back-office** est posée en configuration (30 minutes d'inactivité, cookie renouvelé toutes les 5).
- **L'expiration du secret (BO-01-02-12)** — `auth.password-expiry-days`, **zéro par défaut**. Un secret jamais changé est échu dès qu'un délai existe : un compte qui porte encore le code de sa création est exactement celui pour qui la politique est écrite. Côté back-office, l'échéance alimente l'attribut que le filtre de changement de mot de passe lit déjà ; côté caisse, elle lève la même demande que le forçage superviseur d'EXP-14, donc la caissière retrouve l'écran qu'elle connaît. La date du dernier changement **descend par la diffusion** : le nœud est l'autorité sur le secret, la caisse ne pourrait pas savoir seule qu'il a changé.
- **La langue du titulaire suit le même chemin.** `Employee.language` existait **sur la caisse**, était lue par le moteur de messages… et n'existait ni sur le nœud ni dans la diffusion : personne ne pouvait la renseigner. Elle est maintenant administrée et diffusée.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `save` | identifiant, nom ou adresse absent | refusé, chacun pour soi |
| `save` | identifiant pris par un autre / par soi-même | refusé / accepté |
| `save` | compte neuf sans code / code trop court | refusé |
| `save` / `delete` | identifiant inconnu | refusé |
| `delete` | titulaire nommé par l'historique | refusé, désactivation proposée |
| `role` | absent, blanc, inconnu / connu | CASHIER / le rôle |
| `secretExpired` | délai 0 ou négatif | jamais échu |
| `secretExpired` | jamais changé, délai posé | échu |
| `secretExpired` | changé il y a 10 j / 91 j, délai 90 | valable / échu |
| verrouillage BO | seuil atteint / entrée réussie | compte verrouillé / compteur effacé |
| empreinte EMPLOYEES | langue et date de changement | dans l'empreinte, nulles comprises |

**L32 Les listes qui auraient dû être des référentiels (15/10)** `/admin/lists`, `PosListService`, domaine `POS_LISTS` [E/S/R] (BO-04-02-07, BO-04-03-12)

Quatre choses que la caissière touche étaient administrées **sous la forme d'une chaîne à points-virgules** : les motifs de demande de service (`supervisor.service-reasons`), les motifs de dépense (`cash.movement-reasons`), les types de retour (`return.types`) et les contenants de tare (`balance.tare-containers`). Une chaîne dessine quatre touches et ne fait **rien d'autre** : pas de code, donc rien en aval ne peut nommer une entrée ; pas de sévérité, donc le back-office ne peut pas trier ce qu'il reçoit ; pas de regroupement, donc les mêmes motifs ne peuvent pas servir deux gestes sous un même libellé.

- **Une entité, un écran, une diffusion — une fois, pas quatre.** `PosList` porte un code, un libellé, un **genre** (demandes de service, justifications, types de retour, contenants de tare) et, pour une liste de justifications, les **fonctions de caisse** qu'elle sert. `PosListItem` porte un code, un libellé, un rang et — **selon le genre** — une sévérité, un poids ou des marqueurs d'option. Quatre tables auraient voulu dire quatre écrans, quatre domaines de diffusion et quatre méthodes d'application pour un seul geste : administrer une liste de choses que la caissière touche.
- **Le paramètre n'est pas supprimé, et c'est délibéré.** Tant qu'aucune liste d'un genre n'est distribuée, la caisse lit la chaîne qu'elle a toujours lue : un magasin qui n'ouvre jamais l'écran se comporte **exactement** comme avant. Le référentiel l'emporte dès qu'une liste de ce genre existe. Le bouton **Reprendre les valeurs actuelles** fait la bascule en un geste : il lit les quatre paramètres tels quels et écrit les listes qu'ils décrivent, **sans toucher** à une liste déjà créée — le geste est un point de départ, pas une remise à zéro.
- **La sévérité est affichée à réception (BO-04-02-07).** Le nœud résout le motif reçu contre ses types administrés — par **libellé** (ce que la caisse envoie) ou par **code** — et la bannière du tableau de bord annonce `P2 —` devant l'appel. Un motif que rien ne reconnaît ne porte **aucune** priorité plutôt qu'une priorité inventée : il vient d'une caisse qui lit encore le paramètre, ou d'un geste qui appelle avec un motif à lui, SONDAGE le premier.
- **Les justifications sont liées à des fonctions (BO-04-03-12).** Une liste nommant des fonctions ne sert que celles-là ; une liste n'en nommant aucune les sert toutes, ce qu'un magasin à une seule liste de motifs veut. Le repli, lui, est **limité à EXPENSE** : `cash.movement-reasons` nomme les motifs d'une dépense tiroir et rien d'autre, donc le servir à un autre geste proposerait « Achat de timbres » comme motif d'annulation.
- **Reste à faire sur BO-04-03-12** : seule la dépense tiroir consulte aujourd'hui sa liste. Les autres gestes — annulation de ligne, annulation de ticket, forçage de prix, remise — n'ont pas d'écran de justification à alimenter ; les brancher demande d'abord ces écrans, pas ce référentiel.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `/admin/lists` `save` | code ou libellé absent | refusé, chacun pour soi |
| `/admin/lists` `save` | code pris par une autre liste | refusé |
| `/admin/lists` `item/save` | liste inconnue / code ou libellé absent | refusé |
| `bootstrap` | listes déjà présentes / paramètres vides | rien créé, notice verte |
| `bootstrap` | quatre paramètres renseignés | quatre listes reprises, codes dérivés |
| `serviceRequestTypes` | liste distribuée / aucune | référentiel / chaîne à points-virgules |
| `justifications` | fonction servie / non servie / aucune liste | entrées / vide / repli EXPENSE seul |
| `returnTypes` | marqueurs TICKET, NOENDORSE, NOORIGIN, PRICE / inconnu | lus / ignoré |
| `tareContainers` | poids absent ou négatif | entrée écartée |
| empreinte POS_LISTS | une entrée modifiée | empreinte déplacée |
| `severityOf` | libellé, code, entrée inactive, motif inconnu | priorité / 0 |

**L33 Le logo n'était pas une image, c'était un fichier (15/10)** `/admin/images`, `StoreImageService`, domaine `STORE_IMAGES` [E/S/R] (BO-03-03-15, BO-10-03-09)

La caisse imprimait **un** logo : `/print/logo.png`, une ressource de son classpath. Un groupe qui tient plusieurs pays, plusieurs enseignes et plusieurs concepts ne pouvait donc pas leur donner un papier différent — et l'exigence en demande **au moins dix**. L'impression graphique, elle, existait déjà et n'a pas bougé : c'est la SOURCE de l'image qui manquait.

- **Un logo est une ligne posée à un échelon.** `StoreImage` porte un code, un libellé, un usage, l'échelon (pays, enseigne, PDV — ou rien, et c'est le défaut du groupe) et l'image elle-même, en `data:` URI PNG comme les visuels de touches (BO-03-01-24) : un instantané qui reste du texte reste comparable, empreintable et journalisable, et le back-office sait déjà redimensionner une image.
- **L'échelon le plus proche gagne, et la résolution reste sur le nœud.** Un logo posé sur le PDV l'emporte sur celui de son enseigne, qui l'emporte sur celui de son pays, qui l'emporte sur le défaut du groupe — la même marche que la résolution des paramètres. La caisse ne reçoit **que le résultat** : elle n'a pas à détenir l'organigramme du groupe pour imprimer son propre papier, exactement comme elle reçoit des paramètres résolus et jamais les lignes d'échelon. Les deux échelons **internes au magasin** — l'îlot et le point de contact — ne sont pas offerts : un logo est le papier d'un magasin, pas d'une caisse, et deux caisses côte à côte imprimant des bannières différentes serait un défaut.
- **Le même écran administre les visuels d'avantage fidélité (BO-10-03-09)**, parce que c'est le même objet : une image importée, posée à un échelon, portée aux caisses par le même domaine. Ce qui les distingue est l'usage et, pour un visuel, l'avantage qu'il illustre — le **code de la règle** fidélité, ou son **type** pour une famille entière, la règle précise l'emportant sur la famille.
- **Le visuel s'imprime AU-DESSUS de sa ligne**, jamais à sa place : l'image illustre l'avantage, elle ne l'énonce pas. Une imprimante qui laisse tomber les images — ou un magasin qui n'a rien administré — rend exactement le ticket d'avant, qui est la règle que suit tout ajout graphique sur ce papier.
- **Rien d'administré, rien de changé.** Aucune image distribuée : la caisse imprime le logo de son installation, le fichier qu'elle a toujours lu. Une image illisible, ou entièrement blanche, imprime l'en-tête texte plutôt que d'échouer le document.
- **La facture suit le ticket (15/10).** `InvoiceDocument` porte désormais le logo administré comme un champ final de plus, posé par le service qui construit le document — le seul qui tienne le référentiel ; un document mis en page n'a rien à aller chercher en base. Les signatures existantes sont intactes : une surcharge de plus porte le logo, l'ancienne délègue avec `null`, et un document construit par un test ou par l'éditeur de gabarits imprime exactement ce qu'il imprimait. Un magasin dont le back-office a importé un logo le voit maintenant sur sa facture comme sur son ticket, et les deux ne peuvent plus diverger.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `closenessTo` | PDV / enseigne / pays / défaut du groupe | 3 / 2 / 1 / 0 |
| `closenessTo` | échelon d'un autre magasin, îlot, point de contact | -1 |
| `closest` | les quatre en concurrence | le PDV l'emporte |
| `closest` | aucun candidat, aucun applicable | rien |
| `closest` | égalité au même échelon | départagée par le code |
| `closest` | nœud sans PDV, PDV inconnu, PDV sans enseigne | défaut du groupe |
| `advantageVisuals` | deux échelons pour un même avantage | un visuel, le plus proche |
| `resolvedImages` | logo + visuels / sans logo | logo d'abord / visuels seuls |
| `/admin/images` `save` | code ou libellé absent, code déjà pris | refusé |
| `/admin/images` `save` | échelon sans code, visuel sans avantage | refusé |
| `/admin/images` `save` | image neuve sans fichier / fichier illisible | refusé |
| `directive(image, largeur)` | image valide, base64 nu, URI data | même tramage |
| `directive(image, largeur)` | base64 invalide, octets non-image, image blanche | rien imprimé |
| `directive(image, largeur)` | image vide ou nulle | repli sur le fichier d'installation |
| empreinte STORE_IMAGES | image remplacée à nom constant | empreinte déplacée |
| ticket | avantage avec / sans visuel | image au-dessus de la ligne / ligne seule |
| `InvoiceDocument.getLogo` | base64 nu / URI data / vide / absent | URI data / inchangée / repli installation / repli installation |

**L34 Le registre des appels superviseur : un événement qui devient une demande (15/10)** `/dashboard`, `SupervisorCallRegistry`, `RemoteCommandService` [E/S/R] (BO-04-02-03, -04, -05)

Le nœud recevait déjà les appels superviseur des caisses et les affichait en bandeau. Un bandeau n'a qu'une issue : **Acquitter**. Une demande, elle, a un **sort** — elle a été réglée à la caisse, on la renvoie à la caisse, ou on la refuse — et c'est exactement ce que les trois exigences demandent. L'appel devient donc une demande avec un statut, et le statut est écrit.

- **Déjà traitée en caisse (BO-04-02-03), automatiquement.** Neuf fois sur dix le superviseur est à trois mètres et règle la demande avant que quiconque ait lu le bandeau — lequel restait affiché, et un responsable marchait vers une caisse dont le problème était résolu. La caisse est la seule à le savoir, donc **la caisse le dit** : tout aval accordé pousse un avis au nœud, qui marque *toutes* les demandes encore ouvertes de cette caisse. Toutes et pas la dernière : deux appels avant l'arrivée du superviseur sont **un** besoin. L'avis est tiré et oublié — un aval ne doit pas échouer parce que le nœud est lent — et le pire des cas est le bandeau qu'un responsable acquitte à la main, c'est-à-dire la conduite d'aujourd'hui.
- **À traiter en caisse (BO-04-02-04).** Le back-office regarde la demande et décide que la caisse la règle elle-même. **Rien ne descend** : le responsable le dit à la personne qui est à la caisse, et le tableau enregistre la décision pour qu'un second responsable ne s'y rende pas. Ce que la caisse doit apprendre — le refus — a son propre ordre.
- **Refusée (BO-04-02-05), avec un canal descendant qui existait déjà.** Le refus laisse à la caisse un ordre `SUPERVISOR_REFUSED` dans le registre d'ordres que la fermeture forcée emprunte depuis la phase 5 : la caisse le ramasse à son tour de boucle, écrit le **libellé que le responsable a tapé** dans la zone de message du caissier, et l'acquitte. Acquitté quoi que fasse l'écran : un refus est une **réponse**, et une réponse délivrée deux fois se lit comme un second refus. Un ordre sans libellé dit le moins qui reste vrai.
- **Le motif voyage dans un SECOND motif de lecture.** La caisse lit les ordres avec une expression régulière à deux champs ; l'élargir aurait voulu dire une caisse qui cesse de lire **tous** les ordres — la fermeture forcée comprise — le jour où le nœud insère un champ entre les deux. Le motif est donc lu à part, dans la queue de l'objet de son propre ordre : introuvable, il ne coûte que son propre texte.
- **Rester affichée ou disparaître (les trois exigences).** `supervision.settled-calls-kept`, active par défaut : une demande tranchée reste au tableau, **clairement marquée** par ce qu'elle est devenue (et par le motif du refus), jusqu'à son acquittement ; désactivée, elle quitte la liste au moment où elle est tranchée. L'acquittement, lui, n'est pas un sort : c'est un responsable qui dit avoir lu, et la demande quitte le tableau quel que soit le paramètre — sinon rien ne l'en retirerait jamais.
- **La sévérité du type administré (BO-04-02-07, L32) s'affiche devant le motif** : `P2 — CAISSE1 demande un superviseur`. Un motif que rien ne reconnaît ne porte aucune priorité plutôt qu'une priorité inventée.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `settle` | demande tranchée, paramètre actif / inactif | marquée et gardée / retirée |
| `settle` | identifiant inconnu | rien tranché |
| `settleAtTill` | deux appels de la même caisse | les deux marqués |
| `settleAtTill` | autre caisse, déjà tranchée, caisse non nommée | intacts, zéro |
| `/dashboard/call/{id}/to-till` | demande en attente | marquée, aucun ordre émis |
| `/dashboard/call/{id}/refuse` | avec / sans libellé | ordre émis, libellé ou défaut |
| `/dashboard/call/{id}/refuse` | demande inconnue | 404, aucun ordre |
| `/api/supervisor/settled` | nœud hors rôle / jeton faux / nominal | 403 / 401 / marquage |
| `orders` | motif présent, absent, échappé, deux ordres | motif propre à chaque ordre |
| `refuse` (caisse) | ordre avec / sans libellé | message affiché, ordre acquitté |
| `dashboard-data` | demande tranchée | statut, libellé et motif portés à l'écran |

**L3 Commandes & devis — rappel par code (chantier D expert)** `/admin/orders`, `CustomerOrderService` [E]

Un seul mécanisme de rappel par code (`CustomerOrder` + `CustomerOrderService`) dont dérivent les quatre documents de l'onglet G : devis, commande, bon de vente, click and collect (EXP-90/91/92). Le back-office saisit et remet un code ; la caisse le rappelle et le règle en totalité ou par acompte (EXP-85).

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Écran | `GET /admin/orders` ADMIN | 200 (commandes récentes d'abord, types proposés) |
| Create | code vide / déjà pris / type inconnu / total négatif | 303 refus respectif, `noticeOk=false` |
| Create | succès | commande OPEN persistée, « … code <c> remis au client. » true |
| Rappel | `recall(code)` OPEN ou PARTIALLY_PAID | commande renvoyée à la caisse |
| Rappel | code inconnu, ou PAID/COLLECTED/CANCELLED | null (rien de rappelable) |
| Acompte | kind sans acompte (bon de vente, click&collect) | refusé (`isDepositAllowed`=false, EXP-85) |
| Acompte | montant null / ≤ 0 / > reste | refusé |
| Acompte | partiel puis soldant | PARTIALLY_PAID puis PAID (reste = 0) |
| Solde | `settleRemaining` sur commande rappelable | PAID, dépôt = total |
| Retrait | `collect` sur commande PAID | COLLECTED ; refusé sinon |
| Annulation | commande rappelable / déjà close | annulée / « rien à annuler » |
| Rappel caisse | `GET /api/orders/{code}` rôle store + jeton | 200 JSON au format `RecalledDocument` d'impos : `{kind: DEVIS\|COMMANDE\|BON_VENTE\|CLICK_COLLECT, code, lines:[{ean,label,quantity,unitPrice,vatRate}], acompteAlreadyPaid}` (+ status/remaining ignorés) si rappelable ; 404 sinon ; 401 mauvais jeton ; 403 hors rôle |
| Reprise inter-caisses (EXP-46) | `GET /api/parked/{ticketNumber}` | 200 locator (où dort le ticket : `terminalId`, statut) si PARKED ; 404 sinon — mode connecté, le hors-ligne reste sur sa caisse |
| Raccord coffre (EXP-87) | ingestion d'un mouvement WITHDRAWAL créé | alimente le coffre en `POUCH` (crédit, PENDING, période ouverte), une fois, réf = uid ; DEPOSIT/mise à jour n'alimentent pas |
| Reste à faire | transfert live du panier garé vers l'autre caisse, saisie des lignes détaillées de commande | couplés à la production côté caisse — signalés |

**L35 La mention légale de TVA sur le ticket et la facture (15/10)** `TicketPrinterService`, `InvoiceDocument` [E] [R] (BO-02-02-02)

Un taux de TVA peut obliger à imprimer un texte — autoliquidation, exonération, régime particulier. Le référentiel porte désormais ce texte (H11) ; restait à le faire sortir sur les deux documents qui le réclament.

- **Sur le ticket, après la ventilation** : les mentions des taux effectivement présents sur la vente, **distinctes** et dans leur ordre d'apparition, repliées à la largeur du papier. Deux taux portant la même mention ne l'impriment qu'une fois.
- **Sur la facture, ligne par ligne** : la mention accompagne le seau de TVA qu'elle concerne, pas le pied du document, parce qu'une facture à trois taux peut porter trois régimes.
- **Le remboursement imprime les mêmes mentions que la vente** : c'est le même régime fiscal, en sens inverse.
- Un taux sans mention n'imprime rien du tout, et c'est le cas ordinaire.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `vatLegalMentions` | aucun taux ne porte de mention | aucune ligne |
| `vatLegalMentions` | deux taux, même mention | une seule ligne |
| `vatLegalMentions` | mention plus large que le papier | repliée, jamais tronquée |
| Ticket de vente / remboursement | mentions présentes | imprimées après la ventilation |
| `InvoiceDocument` | seau avec / sans mention | ligne de mention / rien |

**L36 Le pied de ticket administré, en QR code (15/10)** `TicketPrinterService`, `DocumentLayout` [E] [R] (BO-03-05-08)

Le pied de ticket pouvait porter du texte. L'exigence demande d'y porter une **adresse** — enquête de satisfaction, ticket dématérialisé, mentions légales en ligne — et une adresse qu'on tape à la main sur un téléphone n'est pas une adresse qu'on visite.

- **Deux paramètres, pas un** : `ticket.footer-url` porte l'adresse, `ticket.footer-url-format` dit comment l'imprimer — QR, code-barres, texte, ou rien.
- **Le format inconnu imprime un QR** plutôt que de ne rien imprimer : une adresse administrée qui disparaît sans un mot est le pire des trois résultats.
- **L'adresse vide n'imprime rien**, quel que soit le format : c'est l'état par défaut, et un cadre vide sur chaque ticket coûte du papier à tout le monde.
- La directive `[[QR ...]]` rejoint `[[BARCODE ...]]`, `[[IMAGE ...]]` et `[[DOUBLE]]` : le gabarit décrit, le pilote imprime.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `footerUrlBlock` | adresse vide / nulle | rien |
| `footerUrlBlock` | format `QR` / `BARCODE` / `TEXTE` | directive QR / code-barres / ligne de texte |
| `footerUrlBlock` | format `AUCUN` | rien |
| `footerUrlBlock` | format inconnu / paramètres illisibles | QR |
| `DocumentLayout.qr` / `.barcode` | valeur quelconque | directive bien formée |

**L37 Création de commande en caisse (16/10)** `OrderResource`, `OrderCreationService`, `OrderRecallResource`, `CustomerOrderService` [E] [R] (LC-06-03-01/-02/-03/-05/-06/-07/-08)

La caisse crée la commande elle-même : les articles sont enregistrés comme ceux d'une vente (Epic 2), l'acompte TTC est perçu, son code-barres rouvre la commande — et le document commandé vit sur le nœud magasin. LC-06-03-04 (l'acompte comme mouvement financier référencé) était déjà couvert ; ce chantier livre le reste.

- **La caisse reste maîtresse de son panier** : l'écran `/order` (touche COMMANDE du menu AUTRE) montre les articles saisis et le total, et refuse de créer quoi que ce soit sans session ouverte, sur ticket vide, en mode formation, ou sur un acompte illisible, non strictement positif ou supérieur au total.
- **La TVA de l'acompte est ventilée au prorata du total de la commande, par taux** (LC-06-03-05), dans la même règle que le ticket (TVA = TTC − TTC/(1+taux)), le dernier seau absorbant l'arrondi pour que les parts somment exactement dans l'acompte.
- **Le ticket d'acompte est la clé du retour client** (LC-06-03-06) : articles, montant perçu, récapitulatif de la TVA calculée sur l'acompte, et le code de la commande en code-barres.
- **La poussée et le règlement sont tolérants** : le nœud injoignable n'entrave ni la perception ni la clôture ; la caisse autonome garde la commande sur son ticket d'acompte (résidu [N]).

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Écran caisse | `GET /order` (touche COMMANDE) | articles du panier, total, saisie d'acompte |
| Refus de création | pas de session / ticket vide / mode formation / acompte null, ≤ 0 ou > total | retour `/order?error=…`, rien d'enregistré, le panier est intact |
| Création | acompte TTC valide | mouvement `CUSTOMER_DEPOSIT` portant le code commande ; TVA par taux pro-rata (`depositVatDetail`) ; ticket d'acompte imprimé (articles + montant + TVA + `[[BARCODE <code>]]`) ; brouillon du panier annulé ; notice « COMMANDE <code> CRÉÉE » |
| Code commande | réservation | `<terminal>-CMD<6 chiffres>`, séquence ORDER propre — distinct de tout numéro de ticket |
| Ingestion | `POST /api/orders` corps `RecalledDocument` (kind COMMANDE) rôle store + jeton | 200, commande stockée OPEN, `depositVatDetail` tenu, lignes gelées ; idempotent par code (re-poussée = même ligne rafraîchie) |
| Ingestion | hors rôle / mauvais jeton / corps null, code blanc ou kind inconnu | 403 / 401 / 400, rien n'est écrit |
| Écran back-office | `GET /admin/orders` avec commande poussée | la commande créée en caisse est listée, sa colonne « TVA acompte » rend la ventilation par taux |
| Rappel | `GET /api/orders/{code}` commande poussée | 200 JSON `RecalledDocument` + `depositVatDetail` + `totalAmount`, `acompteAlreadyPaid` = acompte du mouvement référencé |
| Reprise par scan | scan du code-barres du ticket d'acompte sur l'écran de vente | articles chargés dans le panier, règlement « REPRISE D'ACOMPTE » crédité du montant, reste à payer = solde (LC-06-03-07) |
| Reprise manuelle | saisie du code sur `/recall` | même chargement (parcours existant EXP-90) |
| Solde | règlement du reste par le moyen choisi | ticket final portant, DISTINCTEMENT, la TVA totale du ticket et « TVA DÉJÀ PERÇUE SUR L'ACOMPTE » ventilée par taux (LC-06-03-08) |
| Ingestion du ticket final | upsert du ticket soldant avec paiement ACOMPTE | la ligne `DepositPayment` tient `vatDetail` + `orderCode` ; le duplicata du nœud récite la même mention |
| Clôture | `POST /api/orders/{code}/settled` à la clôture de la vente | commande PAID puis COLLECTED ; 200 idempotent (re-clôture = 200) ; code inconnu = 404 |
| Anti-rejeu | re-scan du code d'une commande réglée | plus rien de rappelable (404 au rappel) |

## M. Paramétrage [E] [R]

Toutes les surfaces de M sont `@RolesAllowed("ADMIN")` : anonyme → 302 login (page HTML), non-ADMIN → 403. POST → 303 + notice one-shot (`noticeOk`).

**M1 Écran de paramétrage** `/admin/settings` + racine `/admin` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Racine | `GET /admin` ADMIN | 303 `/admin/settings` |
| Racine | `GET /admin` non-admin authentifié | 303 `/dashboard` |
| Racine | `GET /admin` anonyme | 302 login |
| Écran | `GET /admin/settings` ADMIN | 200, clés groupées par section, valeur effective par clé (catalogue) |
| Écran | non-ADMIN | 403 |
| Save | N clés valides | 303 « N paramètre(s) enregistré(s). Application aux caisses au prochain tirage. » `noticeOk=true` |
| Save | clé non postée (absente du form) | ignorée, non comptée |
| Save | entier valide | stocké tel quel |
| Save | entier négatif | 303 « Valeur invalide pour « <label> » — entier positif attendu. » `noticeOk=false` (refus au 1er invalide ; clés précédentes déjà stockées) |
| Save | entier non numérique | même refus |
| Save | booléen | normalisé `true`/`false` (`Boolean.parseBoolean`) |
| Widget | clé à VALEURS FERMÉES (`PosSettingsService.CLOSED_VALUES`) | rendue en liste de choix et non en champ libre ; une valeur stockée hors liste (synonyme, valeur ancienne) est ajoutée à la liste et affichée telle quelle |
| Save | valeur hors de la liste fermée | 303 « Valeur invalide pour « <label> » — attendu : A, B, C. » `noticeOk=false`, rien stocké |
| Save | valeur hors liste mais ÉGALE à celle déjà stockée | acceptée (une configuration portant un synonyme reste enregistrable) |
| Cache | 2 lectures autour d'une écriture | `store()` appelle `invalidate()` → la 2e lecture voit la nouvelle valeur, sans redémarrage (M4) |

**M6 Fiche magasin** `/admin/store` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Écran | ADMIN, PDV présent | 200 (adresse initialisée si nulle) |
| Écran | aucun PDV | `store = null`, page rendue |
| Save | aucun PDV sur le nœud | 303 « Aucun point de vente sur ce nœud : importez-le avant de le configurer. » false |
| Save | succès | 303 « Informations du point de vente enregistrées. » true (nom, adresse, n° TVA, SIRET, tél, RIB ; n° PDV/`code` jamais lu du form) |
| Save | champ absent | valeur courante conservée |

**M7 Modes de règlement** `/admin/tenders` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Écran | `GET /admin/tenders` | 200 (rangs ordonnés, clés de règlement, contrôles, moments tiroir) ; `noticeOk` = `!"false"` |
| Save | code hors `PaymentTypes.keys()` | 303 « Moyen de règlement inconnu de la caisse : <code> » false |
| Save | identifiant < `MIN_FUNCTIONAL_ID_LENGTH` | « L'identifiant doit compter au moins N caractères. » false |
| Save | identifiant déjà pris par un autre code | « L'identifiant <id> est déjà pris par <code>. » false |
| Save | code inexistant (création) | « Moyen de règlement créé. » true |
| Save | code existant (mise à jour) | « Moyen de règlement enregistré. » true |
| Delete | code inconnu | « Moyen de règlement inconnu. » false |
| Delete | succès | « Moyen de règlement supprimé. » true (retire l'administration, pas la nature en caisse) |
| Export | `GET /admin/tenders/export` | `text/csv`, `filename="modes-de-reglement.csv"`, en-tête 29 colonnes |

**M8 Touches** `/admin/touches` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Écran | `GET /admin/touches` | 200 (groupes = familles order by code, mode d'ordre, touches/page, `pinnedCount`, `maxPinned=4`) |
| Config | id illisible/inconnu | 303 « Groupe introuvable. » false |
| Config | `buttonSize` hors SMALL/NORMAL/LARGE | « Taille de touche invalide pour « <code> ». » false |
| Config | `displayOrder` négatif/illisible | « Ordre personnalisé invalide — entier positif attendu. » false |
| Config | `salesVolume` négatif/illisible | « Volume de vente invalide — entier positif attendu. » false |
| Config | succès | « Touche du groupe « <code> » mise à jour. » true |
| Pin | id inconnu | « Groupe introuvable. » false |
| Pin | épingler alors que 4 déjà épinglés (plafond) | « Maximum 4 groupes épinglés — désépinglez-en un d'abord. » false |
| Pin | épingler (< plafond) | « Groupe « <code> » épinglé. » true |
| Pin | désépingler | toujours permis, « Groupe « <code> » désépinglé. » true |
| Image | `imgCode`/`imgData` null ou codes vide | « Aucune image à importer. » false |
| Image | ligne code vide / data blanc / index sans data / code non résolu / resize échoue | ignorée (skipped++) |
| Image | import | « N image(s) importée(s), M ignorée(s). » `noticeOk` = `attached > 0` |
| Image clear | code vide/inconnu | « Article introuvable. » false |
| Image clear | succès | « Image de « <name> » supprimée. » true |

**M10 Coupons** `/admin/coupons` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Écran | `GET /admin/coupons` | 200 (types, plages articles, îlots, catalogues) |
| Type save | code vide | « Le code de la plage est obligatoire. » false |
| Type save | `amountSource` inconnu | « Origine du montant inconnue. » false |
| Type save | `codeKind` inconnu | « Type de caractères inconnu. » false |
| Type save | `validate` non vide | 1er problème renvoyé, false |
| Type save | création / mise à jour | « Plage créée. » / « Plage enregistrée. » true |
| Field save | type inconnu | « Plage inconnue. » false |
| Field save | rôle inconnu | « Rôle de champ inconnu. » false |
| Field save | kind inconnu | « Type de caractères inconnu. » false |
| Field save | `validate` non vide | 1er problème, false |
| Field save | succès | « Champ <ROLE> enregistré. » true (remplace le champ de même rôle) |
| Field delete | type inconnu | « Plage inconnue. » false |
| Field delete | rôle inconnu | « Rôle de champ inconnu. » false |
| Field delete | aucun champ ce rôle | « Aucun champ <ROLE> sur cette plage. » false |
| Field delete | succès | « Champ <ROLE> supprimé. » true |
| Control save | type inconnu | « Plage inconnue. » false |
| Control save | kind inconnu | « Contrôle inconnu. » false |
| Control save | succès | « Contrôle <KIND> enregistré. » true (remplace même kind) |
| Control delete | type inconnu / kind inconnu | « Plage inconnue. » / « Contrôle inconnu. » false |
| Control delete | aucun contrôle | « Aucun contrôle <KIND> sur cette plage. » false |
| Control delete | succès | « Contrôle <KIND> supprimé. » true |
| Article-range save | code vide | « Le code de la plage article est obligatoire. » false |
| Article-range save | `codeKind` inconnu | « Type de caractères inconnu. » false |
| Article-range save | `valueSource` inconnu | « Nature de la valeur inconnue. » false |
| Article-range save | `validateRange` non vide | 1er problème, false |
| Article-range save | création / mise à jour | « Plage article créée. » / « Plage article enregistrée. » true |
| Article-range delete | code inconnu | « Plage article inconnue. » false |
| Article-range delete | succès | « Plage article supprimée. » true |
| Island save | code vide | « Le code de l'îlot est obligatoire. » false |
| Island save | création / mise à jour | « Îlot créé. » / « Îlot enregistré. » true |
| Island delete | code inconnu | « Îlot inconnu. » false |
| Island delete | succès | « Îlot supprimé. » true |

**M11 Rétention des données** `/admin/settings` — rubrique « RÉTENTION DES DONNÉES » [E]

Onze clés de rétention ajoutées au catalogue `PosSettingsService`, rendues par l'écran de paramétrage générique (M1) sous une rubrique dédiée — pas d'écran propre. Toutes de type INT, défaut `0` = conservation illimitée. La saisie et la validation sont celles de M1 (POST unique, entier ≥ 0, refus au 1er invalide).

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Écran | rubrique « RÉTENTION DES DONNÉES » | 11 clés INT rendues (widget number, min 0), valeur effective par clé, `count` = 11 |
| Défaut | clé jamais administrée | valeur `0` → conservation illimitée, le balayage n'y touche pas |
| Type -07 | `retention.cashier-reports-count` | INT interprété comme un NOMBRE de rapports, pas des jours (piège colonne H) |
| Type -18 | `retention.expired-promotions-cap` | INT interprété comme un plafond PAR PASSAGE, pas une rétention (piège colonne H) |
| Type -03 | `retention.sales-days` | INT en jours ; suppression retenue (l'archivage est une décision client, hors implémentation) |
| Save | délai / plafond entier ≥ 0 | stocké tel quel, appliqué au prochain balayage de fin de période |
| Save | entier négatif / non numérique | 303 « Valeur invalide pour « <label> » — entier positif attendu. » `noticeOk=false` (garde M1) |
| Absent du catalogue | -10, -13, -14, -15 | aucune clé rendue — pas de cible persistée dans imedge (voir tableau de cotation) ; un paramètre inerte donnerait une fausse assurance RGPD |

**M12 Points de caisse** `/admin/checkout-points` [E]

La caisse comme objet administré (BO-03-09). `@RolesAllowed("ADMIN")`, POST → 303 + notice one-shot. Upsert par identifiant de terminal.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Écran | `GET /admin/checkout-points` ADMIN | 200 (points ordonnés, îlots, PDV, types de TPV, types d'afficheur) |
| Save | `terminalId` vide | 303 « L'identifiant du terminal est obligatoire. » false |
| Save | `islandCode` inconnu | 303 « Îlot inconnu : <code> » false |
| Save | `pdvNumber` inconnu | 303 « Point de vente inconnu : <n> » false |
| Save | `technicalHostessBadge` inconnu | 303 « Hôtesse inconnue (badge <b>). » false (BO-03-09-08) |
| Save | terminal absent (création) | point créé + persist ; type/afficheurs/hôtesse/menu posés ; « Point de caisse créé. » true |
| Save | terminal présent (mise à jour) | mise à jour sur place ; « Point de caisse enregistré. » true |
| Save | `tpvType` inconnu | repli sur `CASH_REGISTER` (BO-03-09-07) |
| Save | `cashierDisplayType`/`customerDisplayType` inconnu | repli sur `NONE` (BO-03-09-12/13) |
| Save | liens valides (îlot + PDV + hôtesse) | rattachements posés, création acceptée (BO-03-09-05/08) |
| Delete | terminal inconnu | « Point de caisse inconnu. » false |
| Delete | succès | « Point de caisse supprimé. » true |

**M13 Îlots de caisse** `/admin/checkout-islands` [E]

L'îlot promu en objet de paramétrage (BO-03-10). `@RolesAllowed("ADMIN")`, upsert par code. Le CRUD des îlots QUITTE l'écran des coupons (qui continue seulement à les lire).

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Écran | `GET /admin/checkout-islands` ADMIN | 200 (îlots, PDV, canaux de promotion) |
| Save | `code` vide | 303 « Le code de l'îlot est obligatoire. » false (BO-03-10-01) |
| Save | `pdvNumber` inconnu | 303 « Point de vente inconnu : <n> » false |
| Save | code absent (création) | îlot créé + persist ; PDV, canal, rendu monnaie, déclaration auto posés ; « Îlot créé. » true |
| Save | code présent (mise à jour) | mise à jour sur place ; « Îlot enregistré. » true |
| Save | `promotionChannel` inconnu | repli sur `POINT_OF_SALE` (BO-03-10-08) |
| Save | rendu monnaie coché / décoché | `changeGivingAllowed` true / false (BO-03-10-04) |
| Save | déclaration auto fin de période | `autoPeriodCloseDeclaration` (BO-03-10-10) |
| Save | `active` décoché | îlot désactivé ET propagation : les caisses actives de l'îlot désactivées (BO-03-10-03) |
| Delete | code inconnu | « Îlot inconnu. » false |
| Delete | caisses encore rattachées (`countByIsland > 0`) | « Îlot rattaché à N caisse(s) : détachez-les avant de le supprimer. » false (BO-03-10-02) |
| Delete | îlot sans caisse | « Îlot supprimé. » true |
| Niveau échelon | pose d'une clé au niveau `ISLAND` / `CHECKOUT_POINT` | option ajoutée à l'écran des échelons (E9) — « le choix du niveau » |

**M14 Paramètres de tenue de poste (chantier A — clés alignées sur impos)** `/admin/settings` + `/admin/echelons` [E]

`Def` ajoutés au catalogue `PosSettingsService` avec les CLÉS EXACTES d'impos (source de vérité — sans quoi la caisse lit une clé que le back-office n'écrit pas). Rendus par l'écran générique (M1) et posables à tout échelon (E5) : administration, chaîne d'échelons et descente `ECHELON_SETTINGS` sont génériques, un `Def` suffit ; la caisse ne reçoit que la valeur résolue. Le nœud n'ajoute AUCUN accesseur (la caisse les porte).

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Tiroir | `cash.drawer-cap` (€) | rendu section TIROIR ; 0 = pas de plafond (EXP-17) |
| Écart | `cash.variance-threshold` (€) | rendu CLÔTURE ; défaut 1,00 € (EXP-82) |
| Fond | `cash.float-mode` | rendu CLÔTURE ; FIXED (défaut) / FLOATING (EXP-95) |
| Matrice | 7 `gesture.endorsement-{cancel-line,cancel-last,abandon,return,refund,discount,price-force}` (BOOL) | rendus GROUPÉS sous GESTES NÉGATIFS ; défaut true sauf `cancel-last` (false) (EXP-32) |
| Portée aval | `gesture.endorsement-scope` | GESTES NÉGATIFS ; PER_OP (défaut) / PER_TICKET (EXP-33) |
| Libellés | `session.welcome-message` (SESSION CAISSE), `sale.waiting-label` (AFFICHAGE) | administrés (EXP-01, EXP-03) |
| Divers | `payment.trd-card-cap` (RÈGLEMENTS RESTREINTS), `supervisor.service-reasons` (SUPERVISION) | EXP-60, EXP-84 |
| Échelon | pose d'un `gesture.endorsement-*` au niveau ENSEIGNE, surchargé au PDV | résolution la plus spécifique gagne (E7) — « réglable par échelon » |
| Points de contrôle | 10 `control.*` : `cancel-line`, `cancel-payment`, `refund`, `drawer-out-of-sale`, `quantity` + `quantity-max`, `ticket-total` + `ticket-total-max`, `parked-at-close`, `card-payment` | rendus GROUPÉS sous POINTS DE CONTRÔLE ; niveau NONE/WARN/BLOCK/ENDORSE par geste, défaut identique à celui que porte l'énumération de la caisse (BO-10-02-01 à -10) |
| Retour client | 7 `return.{types,reasons,print-reason,in-sale-allowed,without-origin-allowed,foreign-store-accepted,dedicated-register}` | rendus RETOUR CLIENT (LC-05-02) |
| Langues | 4 `language.{system,available,operator-change,customer-second}` | rendus LANGUE — la langue du magasin et celles qu'il offre (LC-13-06) |
| Mouvement financier | 3 `movement.financial-{sources,destinations,print}` | rendus MOUVEMENT FINANCIER |
| Commande de monnaie | `change-order.{print,roll-values}` | rendus COMMANDE DE MONNAIE |
| Îlot / TPV | 3 `island.{auto-declaration,change-allowed,promotion-channel}` | rendus ILOT / TPV — le pendant administrable par clé de l'écran M13 (BO-03-10) |
| Balance | `balance.{default-tare,tare-containers}` | rendus BALANCE — tare par défaut et catalogue des contenants (LC-14-01) |
| Articles à enlever | `collect.{point-label,voucher-copies}` | rendus ARTICLES A ENLEVER (LC-02-08) |
| Dans leur rubrique existante | `cash.withdrawal-destinations` (MOUVEMENTS DE CAISSE), `cash.variance-amount-visible` (CLÔTURE), `sale.line-warning-threshold` (AFFICHAGE), `fidelity.anonymous-projection` (FIDÉLITÉ), `sogec.bon-prefixes` (CODES GS1) | ajoutés à la suite des clés déjà portées de la même rubrique |

Deuxième passe du chantier A (12/10) : les **38 clés** du catalogue de la caisse qui manquaient encore à celui du nœud y sont portées **verbatim** — clé, type, rubrique, libellé, aide, défaut et repli MicroProfile recopiés sans un caractère de changement, parce que c'est la MÊME exigence administrée au même endroit. Le catalogue passe de 161 à **199** `Def`, aucune clé en double, aucune rubrique scindée qui ne l'était déjà. Le nœud n'ajoute toujours aucun accesseur : il administre et il diffuse.

**Les valeurs énumérées se CHOISISSENT.** Treize de ces clés ne portent pas du texte libre mais un mot d'une liste fermée : les huit niveaux de contrôle (`NONE`, `WARN`, `BLOCK`, `ENDORSE`), `cash.float-mode`, `gesture.endorsement-scope`, `fidelity.advantage-grouping`, `ticket.line-attribute-isolate` et `island.promotion-channel`. La caisse les lit avec un repli tolérant — une valeur illisible retombe sur le défaut du paramètre, pour qu'elle n'enlève ni n'ajoute un contrôle par accident — et c'est précisément ce qui rendait une faute de frappe INVISIBLE côté administration : `BLOK` au lieu de `BLOCK`, et le magasin croit avoir bloqué un geste qui passe toujours. Ces clés sont donc offertes en liste de choix (`CLOSED_VALUES` à côté du catalogue, pas dans le `Def` : les deux catalogues restent copiables au caractère) et le save refuse le hors-liste, en nommant les valeurs attendues. `language.system` n'en fait pas partie — les langues d'un nœud sont les bundles présents sur son classpath, qu'aucune liste écrite ne peut énoncer — ni les clés qui portent une LISTE de valeurs (`return.types`, `movement.financial-sources`…), où ce qui est fermé est l'élément, pas le champ.

LA LISTE VAUT AUX DEUX ENDROITS OÙ L'ON POSE UNE VALEUR. L'écran des échelons (`/admin/echelons`, E1/E5) choisit d'abord la clé dans une liste puis saisit sa valeur : le champ y est donc PILOTÉ par la clé choisie. La page sert la carte `closedValues` et un script bascule, à chaque changement de clé, entre le champ libre et une liste de choix — les deux contrôles portent le même `name="value"` et celui qui ne sert pas est `disabled`, donc non posté, ce qui évite d'avoir à fabriquer un second paramètre de formulaire. Le refus côté serveur ne dépend pas de ce script : `saveSetting` refuse le hors-liste en nommant les valeurs attendues, qu'un poste administre par l'écran ou par un POST direct. Un échelon n'a PAS le droit d'inventer une valeur que le magasin refuserait : un geste est bloqué ou il ne l'est pas, et un `BLOK` posé à l'enseigne descendrait dans tous les magasins de l'enseigne.

**Deux points de contrôle de plus (12/10, BO-10-02-16 et -17).** Le questionnaire demande le même
contrôle — alerte, blocage, aval — sur le MONTANT d'un article et sur le MONTANT d'un règlement.
La caisse ne savait plafonner ni l'un ni l'autre : quatre clés entrent aux deux catalogues, deux
niveaux (`control.item-amount`, `control.payment-amount`) offerts en liste fermée comme les huit
précédents, et deux plafonds (`control.item-amount-max`, `control.payment-amount-max`) laissés
libres car ce sont des montants. Vides par défaut : un plafond vide est l'ABSENCE de plafond et non
un plafond de zéro, qui refuserait chaque article et chaque règlement du magasin — et un plafond
illisible répond pareil, pour qu'une faute de frappe ne bloque pas une ligne de caisses. La caisse
compare EN VALEUR ABSOLUE : l'exigence veut le même plafond pour les ventes et pour les retours, et
un retour est le même nombre avec l'autre signe. L'énumération des points passe donc de huit à dix,
dont cinq avalisables — le prix forcé l'est, le règlement ne l'est pas, faute de cérémonie à
l'écran de paiement.

Le CONTRÔLE DE DÉRIVE est un `comm` des deux catalogues : après cette passe, il ne doit plus rester côté nœud que ses clés propres (`export.*`, `financial.*`, `retention.*`, `stream.*`) et, côté caisse, rien.

```
grep -o 'new Def("[^"]*"' ../impos/src/main/java/com/intermarche/pos/service/PosSettingsService.java | sed 's/new Def("//;s/"//' | sort > /tmp/keys-caisse
grep -o 'new Def("[^"]*"' src/main/java/com/intermarche/edge/service/PosSettingsService.java | sed 's/new Def("//;s/"//' | sort > /tmp/keys-noeud
comm -23 /tmp/keys-caisse /tmp/keys-noeud
```

**M15 Devises** `/admin/currencies` [E]

Nouveau référentiel de devises administré au nœud magasin et descendu aux caisses par le tirage `CURRENCIES`. Rend le taux du franc administrable (EXP-22, LC-02-05-02). `@RolesAllowed("ADMIN")`, upsert par code ISO.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Écran | `GET /admin/currencies` ADMIN | 200 (devises ordonnées par ordre puis code) |
| Save | code vide | 303 « Le code de la devise est obligatoire. » false |
| Save | taux absent / ≤ 0 | 303 « Le taux (euros pour une unité) doit être un nombre strictement positif. » false |
| Save | création (code absent) | code mis en majuscules, taux pleine précision (6,55957) ; « Devise créée. » true |
| Save | mise à jour (code présent) | mise à jour sur place ; « Devise enregistrée. » true |
| Delete | code inconnu / vide | « Devise inconnue. » false |
| Delete | succès | « Devise supprimée. » true |
| Descente | devise active | exportée par `CURRENCIES` et appliquée en caisse par `applyCurrencies` (chemin existant) |

**M11 Fournisseurs de service** `/admin/providers` [E] — `AdminProviderResource` + `ServiceProvider` (BO-03-11-01 à 06)

La page est la DÉCLARATION, jamais la conversation : elle enregistre que les cartes cadeaux sont émises par un fournisseur parlé en NEXO Retailer à telle adresse, appelé au scan. Porter un protocole reste une livraison, et un fournisseur déclaré dans un protocole que cette version ne porte pas n'est simplement jamais appelé. C'est le préalable des flux à protocole — Catalina, SAFT, NEXO, étiquettes électroniques, borne de prix — qui déclarent tous leur point d'accès ici au lieu de le recâbler chacun en dur.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Écran | ADMIN | 200, déclaration en ordre de code, catalogues type / protocole / phase d'appel |
| Écran | clé d'authentification posée | la page reçoit `hasCredential`, **jamais la clé** : pas d'affichage, pas d'export, pas de log |
| Journal technique | POST `/save` | le formulaire posté n'est **pas** journalisé, contrairement aux autres écrans d'administration : il porte la clé |
| Save | code absent ou blanc | « Le code du fournisseur est obligatoire. » false |
| Save | type inconnu | « Type de fournisseur inconnu. » false |
| Save | protocole inconnu | « Protocole inconnu. » false |
| Save | phase d'appel inconnue | « Phase d'appel inconnue. » false |
| Save | création | ligne créée avec type, protocole, phase, adresse, sélection d'articles, gabarit ; « Fournisseur déclaré. » true |
| Save | code réutilisé | mise à jour sur place, rien de persisté deux fois ; « Fournisseur enregistré. » true |
| Save | clé postée | enregistrée |
| Save | champ clé **vide** | la clé enregistrée est **conservée** : le formulaire ne peut pas la réafficher, donc corriger un libellé la posterait vide et débrancherait le fournisseur |
| Save | case « en service » décochée | fournisseur hors service |
| Clear credential | code inconnu | « Fournisseur inconnu. » false |
| Clear credential | succès | clé vidée — il faut un chemin pour RETIRER une clé, puisqu'un champ vide la conserve |
| Delete | code inconnu | « Fournisseur inconnu. » false |
| Delete | succès | ligne supprimée ; « Fournisseur supprimé. » true |
| `hasCredential` | clé nulle / faite d'espaces / posée | false, false, true — une clé faite d'espaces n'authentifie rien |
| `isCalledDuringSale` | phase nulle / hors vente / chacune des trois phases de vente | false, false, true ×3 |
| `isReachable` | hors service / sans adresse / adresse blanche / sans clé / nominal | les quatre refus séparément, puis true — ils ne disent pas la même chose et l'écran le dit |
| `findByCode` | null / blanc | null **sans toucher la base** |
| `findByCode` | code entouré d'espaces | trimé avant la recherche |
| Défauts | déclaration neuve | type dématérialisé, protocole HTTP/JSON, phase **hors vente**, en service, sans clé — un fournisseur n'arrive pas sur le chemin d'encaissement en oubliant de dire le contraire |
| Menu | — | `Feature.SERVICE_PROVIDER` dans PARAMETRAGE, chemin `/admin/providers` : l'écran existe dans le menu parce qu'une feature le nomme, et il se cache par profil |

---

## N. Campagnes et offres [E] [V]

`/admin/campaigns` : `@RolesAllowed("ADMIN")` partout (visibilité nav gardée par le `Feature` CAMPAIGN). Chaque save reprojette le flux `BUDGET_GROUPS`.

**N1 Liste et fiche** `/admin/campaigns` [E] [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Liste | `GET /admin/campaigns` ADMIN | 200, campagnes ordonnées + consommation live (`BudgetStatusClient`) + `today` |
| Fiche | `GET /fiche?code=` connu | 200, campagne + statut budget |
| Fiche | code inconnu | `campaign = null`, page rendue |
| Accès | non-ADMIN / anonyme | 403 / 302 login |

**N2 Création** `POST /create` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Create | code déjà pris | 303 liste « Une campagne porte déjà le code <code>. » false |
| Create | période invalide (`validFrom` pas avant `validTo`) | « Campagne refusée : period.invalid » false |
| Create | plafond montant ≤ 0 | « Campagne refusée : amount.invalid » false |
| Create | plafond applications ≤ 0 | « Campagne refusée : applications.invalid » false |
| Create | offre déjà rattachée ailleurs | « Campagne refusée : offer.claimed:<code> » false |
| Create | succès | projection `BUDGET_GROUPS` + « Campagne <code> créée. » true |

**N3 Édition et offres** `POST /save`, `POST /offers` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Save | campagne introuvable | 303 liste « Campagne introuvable. » false |
| Save | validation échoue | 303 fiche « Campagne refusée : <clés> » false |
| Save | succès | projection + « Campagne <code> enregistrée. » true |
| Offers | campagne introuvable | 303 liste « Campagne introuvable. » false |
| Offers | `offerCode` null | 303 fiche « Aucune offre indiquée. » false |
| Offers | `add` offre déjà à une autre campagne | « L'offre <code> est déjà rattachée à une autre campagne. » false |
| Offers | `add` (non présente) / `remove` | ajout / retrait puis update + projection, « Offres de <code> mises à jour. » true |

**N4 Duplication** `POST /duplicate` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Dup | `newCode` null | 303 fiche « Le nouveau code est obligatoire. » false |
| Dup | `newCode` déjà pris | « Une campagne porte déjà le code <newCode>. » false |
| Dup | source inconnue | « Campagne refusée : source.unknown » false |
| Dup | succès | copie de tous les champs ; offres copiées suffixe `-2` si `withOffers` ; projection + 303 fiche(newCode) « Campagne <newCode> créée par duplication. » true |

**N5 Projection du flux** `CampaignFeedProjector` [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Projection | à chaque save | `EngineFeedService.store("BUDGET_GROUPS", contenu)` |
| En-tête | — | `CODE|VALID_FROM|VALID_TO|MAX_AMOUNT|MAX_APPLICATIONS|PER_STORE` |
| Lignes | — | une par campagne, ordre code (`listAllOrdered`) ; bornes/plafonds vides = cellules vides ; `maxAmount` scale 2 ; `perStore` `true`/`false` |
| Ordre de livraison | flux tiré par la caisse | livré au moteur **AVANT OFFERS** (une offre référence son groupe) |
| N6 vide | aucune campagne / campagne sans offre | flux = en-tête seul, valide, pas d'échec |

---

## O. Clients en compte [E] [R]

`/admin/customers` : `@RolesAllowed("ADMIN")`. Croisement avec l'ingestion `/api/sync/customer` (rôle store + jeton) et l'origine (REGISTER / INTEGRATED / VOLATILE / BACK_OFFICE).

**O1 Liste et export** `/admin/customers`, `/export` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Liste | ADMIN | 200, liste filtrée + pagination + drapeau `creationBlocked` |
| Filtre texte | fragment | match sur n° compte / raison sociale / contact (en mémoire, insensible à la casse) |
| Filtre `taxId` | fragment | match sur `taxId` ; `taxId` null exclu |
| Filtre `origin` | REGISTER/INTEGRATED/VOLATILE/BACK_OFFICE | match exact `origin.name()` ; vide = tous ; `origin` null exclu si filtre posé |
| Filtre `blocked` | `yes` / `no` / autre | bloqués / non bloqués / tous |
| Tri | — | `companyName` |
| Export | `GET /export` | `text/csv; charset=UTF-8`, `filename="clients-<date>.csv"`, en-tête 17 colonnes, `;` neutralisé (→`,`) et `\n`→espace dans les cellules, même recherche |
| Accès | non-ADMIN / anonyme | 403 / 302 login |

**O2 Fiche et création au back-office** `/edit`, `/new` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Fiche | `GET /edit?id=` connu | 200, fiche + dernier paiement crédit (`CreditPayment` par `closingDate` desc, ou null) |
| Fiche | id null / inconnu | `customer = null`, page rendue |
| New (form) | `GET /new`, création bloquée | 303 liste « Création de compte client interdite par le paramétrage. » false |
| New (form) | `GET /new`, autorisé | 200 fiche vide (`creating=true`) |
| Add | création bloquée | 303 liste même message false |
| Add | `accountNumber` vide | 303 `/new` « N° de compte obligatoire. » false |
| Add | `companyName` vide | « Raison sociale obligatoire. » false |
| Add | n° de compte déjà attribué | « N° de compte déjà attribué : <n> » false |
| Add | `taxId` déjà enregistré | « N° fiscal déjà enregistré : <taxId> » false |
| Add | succès | `origin = BACK_OFFICE`, persist → 303 `/edit?id=` « Compte créé. » true |

**O3 Correction d'une fiche** `POST /edit` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Save | id illisible/inconnu | 303 liste « Client inconnu. » false |
| Save | `companyName` vide | 303 `/edit?id=` « Raison sociale obligatoire. » false |
| Save | succès | `accountNumber` ET `origin` **non lus** du form (conservés), persist → « Fiche enregistrée. » true |

**O4 Ingestion caisse et conflit d'origine** `POST /api/sync/customer` [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Garde | hors rôle store | 403 « Ce nœud n'a pas le rôle store » |
| Garde | jeton discordant | 401 « Jeton de synchronisation invalide » |
| Ingestion | nouveau `accountNumber` | upsert, ligne créée avec `origin = REGISTER` (défaut, non posé par le DTO), visible au back-office (O3 scénario) |
| Ingestion | rejeu / repush après édition | upsert sur `accountNumber`, une seule ligne |
| Conflit d'origine | client BACK_OFFICE (ou INTEGRATED) repoussé par la caisse | `ingestCustomer` ne lit jamais `origin` → **origine existante conservée** ; les autres champs sont écrasés par le DTO (règle figée O4) |
| Import CSV | flux `CUSTOMERS` | `origin = INTEGRATED` ; `mode=full` **bloque** (ne supprime pas) les comptes absents |

**O5 Erreur d'intégration** [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Supervision | fichier `CUSTOMERS` invalide | erreur du domaine visible en supervision (nœud comme caisse) ; le reste du cycle est tiré quand même |

---

Fichiers lus (chemins absolus) : `/Users/henridarmet/Documents/Workspaces/imedge/src/main/java/com/intermarche/edge/ui/journal/JournalResource.java`, `.../ui/journal/JournalService.java`, `.../ui/dashboard/DashboardResource.java`, `.../ui/dashboard/DashboardService.java`, `.../ui/admin/AdminSettingsResource.java`, `.../ui/admin/AdminStoreResource.java`, `.../ui/admin/AdminTenderResource.java`, `.../ui/admin/AdminTouchResource.java`, `.../ui/admin/AdminCouponResource.java`, `.../ui/admin/AdminCampaignResource.java`, `.../ui/admin/AdminCustomerResource.java`, `.../service/campaign/CampaignService.java`, `.../service/campaign/CampaignFeedProjector.java`, `.../service/PosSettingsService.java`, `.../imports/SyncIngestResource.java`, `.../service/sync/store/SyncIngestService.java`, `.../imports/AccountCustomerCsvResource.java`, `.../domain/payment/AccountCustomer.java`.

Points de code notables (au-delà de S-C) : la fiche magasin ne lit jamais le code PDV du form ; `save` client ne lit ni `accountNumber` ni `origin` ; l'ingestion caisse ne pose jamais `origin` (défaut REGISTER en création, conservation en update) ; `CampaignFeedProjector` livre `BUDGET_GROUPS` avant `OFFERS` ; les appels superviseur du dashboard ne sont exposés que si `dashboardAlertsEnabled()`.

## P. GraphQL [E]

Endpoint unique `/graphql`. **Matrice d'accès (par opération)** : queries `@RolesAllowed("MANAGER")`, mutations `@RolesAllowed("ADMIN")` ; anonyme → 401 (la politique HTTP exige une authentification sur `/graphql`) ; rôle insuffisant → **200 portant le refus dans l'enveloppe `errors`**, `data` à null — GraphQL sur HTTP ne promet pas de 403 une fois la requête authentifiée, et le refus est vérifié là où il est émis ; autorisé → 200. Gestion d'erreurs centralisée par `GraphQLTrait.execute` : `AlreadyExistsException` et `IllegalArgumentException`/autres → `GraphQLException` dans l'enveloppe `errors` (pas 500) ; `PersistenceException` → `GraphQLException` « Database error while performing <op>. Please check your data. » ; `NoSuchElementException` relancée telle quelle (message spécifique dans `errors`). GraphQL répond toujours 200 sauf refus d'accès.

---

**P2 Product** `ProductResource` — `/graphql`

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `allProducts` (Q, MANAGER) | anonyme / non-MANAGER / nominal | 401 / 403 / 200 liste |
| `product(id)` (Q) | id inconnu | error `NoSuchElementException` « Product with id <id> not found » |
| `product(id)` | Nominal | 200, article + attributs |
| `productByEan(ean)` (Q) | EAN inconnu | error « Product with ean <ean> not found » |
| `productByEan(ean)` | Nominal | 200, article |
| `createProduct(input)` (M, ADMIN) | anonyme / non-ADMIN | 401 / 403 |
| `createProduct` | EAN déjà existant | error `AlreadyExistsException` « Product with ean '<ean>' already exists. » |
| `createProduct` | nom déjà existant | error « Product with name '<name>' already exists. » |
| `createProduct` | `productType` invalide (`valueOf`) | error (enveloppe) « An error occurred during createProduct. » |
| `createProduct` | Nominal | 200, article créé (`active` défaut true) |
| `updateProduct(id,input)` (M) | id inconnu | error « Product with id <id> not found » |
| `updateProduct` | nouvel EAN en collision | error « Another product with ean '<ean>' already exists. » |
| `updateProduct` | nouveau nom en collision | error « Another product with name '<name>' already exists. » |
| `updateProduct` | `productType` invalide | error « An error occurred during updateProduct. » |
| `updateProduct` | Nominal (partiel, champs non-null) | 200, article mis à jour |
| `deleteProduct(id)` (M) | id inconnu | 200 `false` |
| `deleteProduct` | article référencé (FK lignes ticket) | error « Database error while performing deleteProduct. Please check your data. » |
| `deleteProduct` | Nominal (jamais vendu) | 200 `true` |

---

**P1 Price** `PriceResource` — `/graphql`

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `allPrices` (Q, MANAGER) | anonyme / non-MANAGER / nominal | 401 / 403 / 200 |
| `price(id)` (Q) | id inconnu | error « Price with id <id> not found » |
| `price(id)` | Nominal | 200 |
| `currentPrice(productId)` (Q) | aucun prix actif | 200 `null` (pas d'erreur, warn loggé) |
| `currentPrice(productId)` | Nominal | 200, prix actif |
| `createPrice(input)` (M, ADMIN) | anonyme / non-ADMIN | 401 / 403 |
| `createPrice` | produit inexistant | error `NoSuchElementException` « Product with id '<id>' not found. » |
| `createPrice` | doublon (produit, priorité, début, usage) | (visé) `AlreadyExistsException` « A price with the same priority, start date, and usage already exists for this product. » |
| `createPrice` | Nominal | 200, prix créé (VAT via `findByNumber`) |
| `updatePrice(id,input)` (M) | id inconnu | error « Price with id <id> not found » |
| `updatePrice` | nouveau `productId` inexistant | error « Product with id '<id>' not found. » |
| `updatePrice` | clé (produit/priorité/début) en collision | error `AlreadyExistsException` « Another price with this priority, start date already exists. » |
| `updatePrice` | Nominal | 200, prix mis à jour |
| `deletePrice(id)` (M) | id inconnu | 200 `false` |
| `deletePrice` | Nominal (rien ne référence un prix) | 200 `true` |

Note bug (src/main, non modifié) : le contrôle d'unicité de `createPrice` construit une requête à placeholders `?1 ?3 ?4 ?5` mais ne passe que 3 arguments — `Price.count(...)` lèvera à l'exécution ; le doublon est donc en pratique capté par le `catch(Exception)` → enveloppe « An error occurred during createPrice. » plutôt que l'`AlreadyExistsException` visée.

---

**P3 ProductFamily** `ProductFamilyResource` — `/graphql`

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `allProductFamilies` (Q, MANAGER) | anonyme / non-MANAGER / nominal | 401 / 403 / 200 |
| `productFamily(id)` (Q) | id inconnu | error « ProductFamily with id <id> not found » |
| `productFamily(id)` | Nominal | 200, famille + enfants |
| `productFamilyByCode(code)` (Q) | code inconnu | error « ProductFamily with code <code> not found » |
| `productFamilyByCode(code)` | Nominal | 200 |
| `createProductFamily(input)` (M, ADMIN) | anonyme / non-ADMIN | 401 / 403 |
| `createProductFamily` | code déjà existant | error « ProductFamily with code '<code>' already exists. » |
| `createProductFamily` | description déjà existante | error « ProductFamily with description '<desc>' already exists. » |
| `createProductFamily` | EAN enfant inconnu | error « Product with ean '<ean>' not found. » |
| `createProductFamily` | sous-famille (code) inconnue | error « ProductFamily with code '<code>' not found. » |
| `createProductFamily` | Nominal | 200, famille créée (produits/sous-familles par code) |
| `updateProductFamily(id,input)` (M) | id inconnu | error « ProductFamily with id <id> not found » |
| `updateProductFamily` | description en collision | error « Another family with description '<desc>' already exists. » |
| `updateProductFamily` | EAN inconnu (remplacement) | error « Product with code '<ean>' not found. » |
| `updateProductFamily` | sous-famille inconnue | error « ProductFamily with code '<code>' not found. » |
| `updateProductFamily` | auto-référence (enfant = elle-même) | `IllegalArgumentException` « A ProductFamily cannot contain itself. » → enveloppe « An error occurred during updateProductFamily. » |
| `updateProductFamily` | Nominal | 200, relations remplacées |
| `deleteProductFamily(id)` (M) | id inconnu | 200 `false` |
| `deleteProductFamily` | Nominal | 200 `true` |

---

**P4 Store** `StoreResource` — `/graphql`

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `allStores` (Q, MANAGER) | anonyme / non-MANAGER / nominal | 401 / 403 / 200 |
| `store(id)` (Q) | id inconnu | error « Store with id <id> not found » |
| `store(id)` | Nominal | 200, fiche magasin |
| `storeByCode(code)` (Q) | code inconnu | error « Store with code <code> not found » |
| `storeByCode(code)` | Nominal | 200 |
| `createStore(input)` (M, ADMIN) | anonyme / non-ADMIN | 401 / 403 |
| `createStore` | code déjà existant | error « Store with code '<code>' already exists. » |
| `createStore` | nom déjà existant | error « Store with name '<name>' already exists. » |
| `createStore` | Nominal | 200, magasin + adresse créés |
| `updateStore(id,input)` (M) | id inconnu | error « Store with id <id> not found » |
| `updateStore` | code en collision | error « Another store with code '<code>' already exists. » |
| `updateStore` | nom en collision | error « Another store with name '<name>' already exists. » |
| `updateStore` | Nominal (adresse créée si null) | 200 |
| `deleteStore(id)` (M) | id inconnu | 200 `false` |
| `deleteStore` | magasin référencé (FK tickets) | error « Database error while performing deleteStore. Please check your data. » |
| `deleteStore` | Nominal | 200 `true` |

---

Notes de portée (obstacles relevés dans src/main, non modifiés, 1 ligne chacun) :
- `PriceResource.createPrice` : requête d'unicité `Price.count("... = ?1 and priority = ?3 and startDateTime = ?4 and priceUsage = ?5", productId, priority, startDateTime)` — placeholders `?3 ?4 ?5` avec seulement 3 arguments positionnels (mappés `?1 ?2 ?3`) → lève à l'exécution, le doublon ne remonte donc jamais comme `AlreadyExistsException` mais en enveloppe générique.
- Détail transverse GraphQL : `IllegalArgumentException` (auto-référence famille, `ProductType.valueOf` invalide) n'est pas relancée telle quelle — le message métier est enveloppé en « An error occurred during <op>. » (le message d'origine reste dans la cause).

## Q. Clôture et fin de journée [E] [R]

Un tableau par mécanique. Note importante : `PeriodCloseService` du code n'est **pas** une clôture comptable qui « refuse si caisse ouverte » (Q1/Q2 du doc) : c'est une boucle de fin de période qui **dépose un ordre `CLOSE_SESSION_IF_IDLE`** à chaque caisse encore ouverte. Aucune clôture n'est faite par le nœud, aucune liste de caisses fautives n'est renvoyée. Les tables suivent le code réel.

**Q1 Ordre de fermeture de fin de période** `service/PeriodCloseService` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Désarmé | `periodAutoCloseEnabled()=false` | `tick` return immédiat, aucun ordre |
| Hors fenêtre | `inClosingWindow(now)=false` | return, aucun ordre |
| Dans la fenêtre | sessions `OPEN` présentes | un ordre `CLOSE_SESSION_IF_IDLE` par caisse ouverte |
| Idempotence par caisse | `pendingCount(terminalId) > 0` | `order` saute (un seul ordre par caisse sur toute la fenêtre) |
| Émission | caisse sans ordre en attente | `issue(...,"Fin de période","Fermeture automatique de fin de période")` + event journalisé |
| Fenêtre lead 0 | `lead=0` | fenêtre = exactement la minute de fin de période |
| Fenêtre normale | `opening.isBefore(end)` | `now ≥ opening && now < end` |
| Fenêtre à cheval minuit | `opening.isAfter(end)` | `now ≥ opening || now < end` |
| Heure illisible | `period.end-time` non parsable | repli sur défaut du `CATALOG`, warn journalisé |
| Robustesse | exception dans `tick` | `tickSafely` avale, warn, nouvel essai à la minute suivante |

**Q2 Compte à rebours de fin de période** `service/PeriodCloseService#countdownMinutes` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Désarmé | `periodAutoCloseEnabled()=false` | -1 (bandeau muet) |
| Loin | minutes restantes > 60 (`ALERT_HORIZON_MINUTES`) | -1 |
| Dans l'horizon | minutes ≤ 60 | minutes restantes |
| Roulement minuit | `now` après `end` | `minutesUntil` + 1440, jamais négatif |
| Libellé | `endOfPeriodLabel()` | heure formatée `HH:mm` |

**Q3 Numérotation par caisse** `service/TicketNumberService` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Ticket | `nextTicketNumber()` | `<terminal>-<8 chiffres>` ex. `C04-00000123`, `lastNumber++` sous verrou pessimiste |
| Session | `nextSessionNumber()` | `<terminal>-S<5 chiffres>` ex. `C04-S00012` |
| Remboursement | `nextRefundNumber()` | `<terminal>-R<6 chiffres>` ex. `C04-R000012` |
| Client | `nextCustomerNumber()` | `<terminal>-CLI<6 chiffres>` ex. `C04-CLI000042` |
| Document | `nextDocumentNumber(type)` | `<terminal>-<préfixe><6 chiffres>` sur `DocumentCounter` propre au type (n'attend pas le verrou ticket) |
| Création paresseuse | compteur inexistant | créé `lastNumber=0` puis incrémenté |
| Continuité | séquence sur une caisse | strictement croissante, pas de trou ni doublon (compteur persisté ⇒ survit au redémarrage) |
| Atomicité | verrou tenu jusqu'au commit | numérotation + chaînage commit/rollback ensemble |
| Piège collision | deux caisses même `pos.terminal.id` (défaut `POS01`) | numéros en collision au nœud (symptôme négatif documenté) |

**Q4 Rendu d'un ticket archivé** `service/TicketRenderService` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Layout administré | gabarit `SALE_RECEIPT` présent | rendu par le gabarit administré |
| Repli intégré | aucun gabarit (service null ou render null) | rendu 42 colonnes intégré |
| Duplicata | `renderTicket(t,true,n)` | bannière `*** DUPLICATA N°<n> ***` |
| Ticket inconnu | `renderTicketDuplicate` numéro introuvable | null (→ 404 côté ressource) |
| Numéro vide | `renderTicketDuplicate` null/blanc | null |
| Rang duplicata | `renderTicketDuplicate` d'un ticket tenu | `renderTicket(t,true,max(1,printCount))`, aucune écriture |
| Ligne annulée | `line.cancelled` | exclue de l'impression ET de la ventilation TVA |
| EAN | `showEan()` vrai et EAN non vide | EAN imprimé sous le libellé |
| Cagnotte | `fidelityAdvantagesEnabled()` && `earnTotal>0` | section « CAGNOTTE DU JOUR » + lignes |
| Ventilation TVA | `vatBreakdownEnabled()` | une ligne par taux, sinon aucune |
| Arrondi | `RoundingPayment` | imprimé avec le signe inversé (`negate`) |
| Devise | `ForeignCurrencyPayment` | trois figures : montant €, montant devise, taux |
| Crédit | `CreditPayment` | ligne débiteur : date, `COMPTE <n>`, nom |
| Code-barres | fin de ticket | `[[BARCODE <ticketNumber>]]` |
| À l'identique | mêmes règles que l'imprimante caisse | duplicata = document d'origine, caractère par caractère |

**Q5 Événements techniques de clôture** `service/PeriodCloseService#order` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Journalisation | ordre déposé à une caisse | event `SESSION_CLOSED` « Fermeture de fin de période demandée à la caisse <terminalId> » |
| Remontée | chaque clôture demandée | l'event part dans l'outbox |
| Un seul par caisse | ordre déjà en attente | aucun nouvel event (garde `pendingCount`) |

**Q6 Balayage de rétention des données** `service/RetentionPurgeService` + `PeriodCloseService#maybeSweep` [E] [R]

Un seul mécanisme pour les rétentions du back-office (BO-10-08-03 à -26) : un catalogue de POLITIQUES (`RetentionPurgeService.CATALOG`) décrites une fois — entité, colonne de date, garde de sécurité référentielle, clé du délai, plafond éventuel, mode — et un balayage générique qui les lit. Aucun `if` par exigence ; deux modes seulement (`AGE_DAYS`, `KEEP_LATEST`). Le balayage s'accroche au `tick()` de fin de période : il attend d'avoir atteint l'heure de fin (après la fenêtre de fermeture), tourne UNE fois par jour, ne tourne jamais pendant que la journée se clôt. Chaque passage qui supprime trace table + nombre + borne au journal technique (LOG, pas le journal `TechnicalEvent` — classe copiée du nœud caisse, un type d'événement ne s'ajoute pas d'un seul côté).

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Déclenchement | `now ≥ endOfPeriod()` et `lastPurgeDay ≠ today` | `sweep()` lancé une fois, `lastPurgeDay = today` |
| Avant la fin | `now < endOfPeriod()` | pas de balayage (ne lit pas des tables en cours d'écriture) |
| Idempotence jour | `lastPurgeDay = today` | balayage sauté (un seul par période) |
| Redémarrage | `lastPurgeDay` en mémoire perdu | rejoue le soir même, sans dégât (purge idempotente) |
| Zéro = illimité | délai / plafond `≤ 0` | politique inerte, aucune requête émise |
| Âge (bulk) | délai `> 0`, sans plafond | `delete … where e.<date> < ?1 [and (<garde>)]`, borne = `now − N jours` |
| Âge date pure | -17 promotions (`validTo` `LocalDate`) | borne construite en `LocalDate` |
| Plafond | -17 avec `retention.expired-promotions-cap > 0` (-18) | sélection des N plus anciens (`order by … asc`, `setMaxResults`), suppression par id ; le backlog ne bloque pas la fin de période |
| Plafond vide | rien au-delà du plafond | aucune suppression émise |
| Comptage (KEEP_LATEST) | -07 rapports X, `count = N` | garde les N plus récents (`order by … desc`), supprime le surplus par id |
| Comptage sous le seuil | rows `≤ N` | aucune suppression |
| Garde référentielle -03 | vente rattachée à une facture / un remboursement | non supprimée (sous-requête `id not in …`) |
| Garde référentielle -12 | ticket en attente sur session `OPEN` | non supprimé (`e.session is null or e.session.status <> OPEN`) |
| Garde référentielle -16 | facture d'un client actif (non `blocked`) | non supprimée |
| Garde référentielle -24 | article désactivé encore lié à un prix / une ligne | non supprimé |
| Garde référentielle -26 | client à solde crédit ≠ 0 ou lié à une facture | non purgé |
| Trace | passage qui supprime | LOG « Rétention <code> : N ligne(s) supprimée(s) de « <table> » (borne …) » |
| Trace | passage qui ne supprime rien | aucune trace |
| Robustesse | une politique lève une exception | loggée et sautée, les autres politiques tournent (la purge ne bloque pas la fin de période) |

---

Points notables relevés dans le code (écarts avec `e2e-scenarios.md`, aucun fichier `src/main` modifié) :
- **C3 du doc** (« ticket avant session → 409 ») : `ingestTicket` n'exige PAS la session — le ticket est ingéré avec `session=null`. Le 409 d'ordre concerne le REFUND (ticket/ligne d'origine), pas le ticket.
- **C11 du doc** (« duplicata compté, POST, compteur incrémenté ») : la route réelle est un **GET** `/ticket/{ticketNumber}/duplicata` qui **n'écrit rien et n'incrémente aucun compteur** ; 404 « Ticket inconnu du magasin ».
- **D6 du doc** (« domaine inconnu → 404 ») : le code renvoie **400** « Domaine inconnu: <DOMAINE> ».
- **Q1/Q2 du doc** (« clôture de période, refus si caisse ouverte avec liste des caisses ») : `PeriodCloseService` ne clôture rien et ne renvoie aucune liste ; il dépose un ordre `CLOSE_SESSION_IF_IDLE` par caisse ouverte, idempotent par caisse.
- Nombres exacts de domaines : `DOMAINS` = 16, `ECHELON_DOMAINS` = 4 (union = 20 dans `getPage`).

**Q7 La fin de journée assistée (15/10)** `/admin/day-end`, `DayEndService`, `RegisterPresenceService` [E] [R] (BO-04-03-05, -06, BO-09-01-04, -05, -06, -08, BO-09-02-01, -02, -03, BO-09-03-03)

Fermer le magasin était une suite de gestes dispersés : regarder les sessions ouvertes sur le tableau de bord, forcer une fermeture caisse par caisse, se demander si une caisse éteinte avait reçu l'ordre, et découvrir le lendemain un panier resté en attente. Dix exigences disaient la même chose : **un écran qui dit où en est la journée, et un bouton qui la termine.**

- **La présence des caisses ne se déduit pas de ce qu'on reçoit, mais de ce qu'on ne reçoit plus.** Aucun protocole nouveau : chaque caisse vient chercher ses ordres toutes les vingt secondes, et c'est cette visite qui est horodatée. Passé le silence toléré — `supervision.register-offline-minutes`, trois minutes, soit huit tours manqués — la caisse est déclarée déconnectée, au tableau de bord comme ici.
- **L'état de la journée tient en quatre cartes** : fermeture automatique et fin de période, caisses ouvertes, caisses muettes, paniers en attente. Une alerte s'allume dès que l'horizon administré — `period.alert-countdown-minutes`, soixante minutes — est atteint et que quelque chose résiste.
- **Le lancement DEMANDE, il ne fait pas.** Chaque caisse ouverte reçoit un `CLOSE_SESSION_IF_IDLE` — un ordre qui se dérobe si la caisse est en pleine vente, parce qu'une fermeture au milieu d'un panier est une vente perdue. Une caisse qui porte déjà un tel ordre n'en reçoit pas un second : une main impatiente ne doit pas remplir le registre.
- **Tout est journalisé**, le lancement comme les caisses qu'il a visées : une fin de journée doit pouvoir s'expliquer le lendemain.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `touch` | première visite / visite suivante | présence créée / horodatage remplacé |
| `offline()` | silence inférieur / supérieur au seuil | absente / listée avec ses minutes |
| `offlineMinutes()` | paramètre ≤ 0 ou illisible | trois minutes |
| `state()` | rien d'ouvert, rien en attente | aucune alerte |
| `state()` | horizon atteint et caisse ouverte | alerte |
| `launch` | deux caisses ouvertes | deux ordres, deux événements |
| `launch` | caisse portant déjà l'ordre | aucun second ordre |
| Écran | `GET /admin/day-end` MANAGER / CASHIER | 200 / 403 |
| Écran | `POST /admin/day-end/launch` | 303 et compte rendu |

## R. Robustesse et reprise [E] [R] [C] [V]

Cross-cutting : chaque thème vérifie que le nœud absorbe une coupure ou un redémarrage sans perte ni double application. Vérification toujours côté nœud (la caisse et le moteur n'y sont que des clients).

**R1 Caisse coupée du nœud** `/api/sync/**` [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Poussée | Nœud injoignable pendant les ventes | les ventes continuent en caisse, l'outbox caisse s'accumule (row par ticket/session/mouvement, jointe à la transaction métier) |
| Rattrapage | Lien rétabli | tout l'arriéré poussé, drainé dans l'ordre (sessions → tickets → refunds), acquitté puis supprimé |
| Idempotence | Rejeu d'un corps déjà ingéré | upsert par clé naturelle, zéro doublon (C1/C2) |
| Vérification | Après rattrapage | le compte en base du nœud égale le nombre d'entités produites en caisse |
| Ordre | Ticket avant sa session | 409 rejouable, pas de ticket orphelin ; session puis ticket → les deux passent |

**R2 Nœud coupé du central** `/api/referential/**` + `RefPullService` [C]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Service caisses | `pos.sync.central-url` mort (rôle store) | le nœud continue de servir ses caisses, tirage échelon en échec seulement |
| Supervision | Cycle en erreur | erreur visible en supervision (`lastErrors` / notice de relance rouge) |
| Non-destruction | Central absent | rien d'effacé localement, empreintes magasin intactes |
| Échelons | Nœud magasin frais, central absent | les quatre domaines d'échelon restent VIDES (le magasin ne les fabrique pas) |

**R3 Moteur coupé** `EngineFeedDeliveryService` [V]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Capture | Moteur down au dépôt d'un flux | flux stocké (`store`), version calculée, marqué non livré (appliedVersion ≠ version) |
| Livraison | Cycle avec moteur down | `deliver` échoue → `markError` sur la ligne, retourne false ; la marche STOPPE au 1er échec (OFFERS jamais livré si PRODUCTS a échoué) |
| Reprise | Moteur revenu | cycle suivant (ou bouton ciblé) → `markApplied`, `lastError` effacée |
| Sans double application | Flux déjà appliqué / à moitié appliqué | idempotence checksum aval : no-op ou complétion, jamais doublé |
| Log | Erreur inchangée d'un tick à l'autre | logguée une seule fois (dédup sur message) |

**R4 Collision `pos.terminal.id`** `/api/sync/ticket` [R] — test NÉGATIF

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Ingestion | Deux caisses, même `pos.terminal.id`, mêmes numéros | collision sur la clé naturelle (terminalId, ticketNumber) → upsert écrase, perte silencieuse |
| Diagnostic | Vérification du compte en base | le compte trahit la perte ; le test FIGE le symptôme pour le diagnostic terrain (aucune protection attendue) |

**R5 Redémarrage du nœud en plein tirage** `RefPullService` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Reprise | Arrêt pendant un cycle | reprise au cycle suivant, aucun domaine à moitié appliqué |
| Auto-guérison | Snapshot déchiré (pages sans transaction partagée) | empreinte non enregistrée (`recordApplied` en dernier) → empreinte recalculée discordante → re-pull propre au cycle suivant |
| État mémoire | Après redémarrage | `lastSuccessfulPull` = null → « — » ; `lastErrors` vidé, re-signalé au prochain cycle |

**R6 Deux instances sur la même base H2** `sh/imedge.sh` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Démarrage | Seconde instance sur le même fichier H2 | verrou de fichier, démarrage refusé avec message clair |
| Intégrité | — | base non corrompue, la première instance continue ; piège documenté dans `sh/imedge.sh` |

**R7 Base vide au démarrage** back-office [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| `/admin/sync` | Nœud frais | 200, `healthy=true`, tables vides, `lastPull` « — », cadences affichées |
| `/dashboard` | Nœud frais | 200, tout à zéro et libellé comme tel, pas de division par zéro |
| Toutes pages BO | Nœud frais | chaque page `/admin/**` répond avec son état vide, aucune ne tombe en 500 |

**R8 Transaction longue** `ImporterCsvResource` [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Import massif | Au-delà du délai de transaction | 200, reprise par paliers 1000 → 100 → 10 → 1 |
| Compte rendu | Lignes fautives | erreur PAR LIGNE conservée, comptes created/updated rendus |
| Intégrité | — | aucune ligne à moitié appliquée ; `findById` réattache l'entité détachée sur le chemin de mise à jour |

---

Sources lues : `SyncSupervisionResource.java`, `SyncSupervisionService.java`, `RefPullService.java`, `EngineFeedDeliveryService.java`, `EngineFeedService.java`, `ScheduledDeliveryService.java`, `SyncOutboxService.java`, `spec-diffusion-supervision.md`, `e2e-scenarios.md` (groupes G, R et S-F).

Points notables relevés dans le code (pas des bugs, mais des comportements exacts à figer) :
- `deliver/{code}` sur un code inconnu/hors catalogue ou déjà acquitté renvoie une notice VERTE « Flux <code> livré. » (no-op traité comme succès) — contre-intuitif mais voulu.
- Les surfaces HTML de `/admin/sync` renvoient 302 vers `/admin/login` pour un anonyme (convention S-E), et 403 pour un non-ADMIN authentifié.
- `lastSuccessfulPull` et `lastErrors` de `RefPullService` sont EN MÉMOIRE : un redémarrage les remet à null/vide (ligne directrice « ne recule jamais / ne ment pas »).

---

## S. Générosité — bloc 03 du questionnaire caisse [R] [E]

Scénarios issus de la campagne bloc 03 (HD_42). Les tables suivent le code livré ;
les références LC-03-xx-yy sont les lignes de l'onglet « Ligne de caisses ».

**S1 Touches de remise préprogrammées** `POST /action/price-mod/preset` · modale de geste de prix [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Modale ligne ouverte | `discount.presets` administre « -10%>PERCENT:10 ; 2€>AMOUNT:2 » | Les touches « -10% » et « 2€ » sont rendues dans la modale (LC-03-02-02/-04) ; les taper applique le geste ordinaire sur la ligne visée |
| Touche valide | ligne 10,00 €, touche « -10% » | Remise 10 % appliquée (prix 9,00 €), modale fermée, total recalculé |
| Touche inconnue | POST avec un libellé non administré | Erreur « TOUCHE DE REMISE INCONNUE », rien appliqué, modale fermée |
| Ligne disparue | POST avec un uid absent du ticket | Erreur « LIGNE INTROUVABLE » |
| Article déjà remisé sans CUMUL | preset sans `,CUMUL` sur une ligne réduite | Erreur « ARTICLE DÉJÀ REMISÉ » (BO-03-07-11), rien appliqué |
| Remises désactivées | `discount.enabled=false`, touche valide | Erreur « REMISES DÉSACTIVÉES » (BO-03-07-01) |
| Liste vide | `discount.presets` vide | Aucune touche rendue, la modale reste celle d'avant |

**S2 Garde-fous des gestes de prix** `TicketService#applyRemise/applyGlobalDiscount` [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Plafond % implicite ligne (LC-03-02-12) | rabais 6 € sur une ligne à 10 €, `discount.line-max-percent=50` | Refus silencieux, prix inchangé ; à 5 € exactement la remise passe |
| Plafond % implicite ticket (LC-03-02-14) | rabais global 60 € sur une base de 100 €, `discount.global-max-percent=50` | Erreur « REMISE SUPERIEURE AU PLAFOND » ; à 50 € exactement le geste passe |
| Article déjà remisé (LC-03-02-10) | remise saisie sur ligne réduite, `discount.cumulative-on-discounted=false` | Erreur « ARTICLE DÉJÀ REMISÉ », geste direct, aval approuvé et touche préréglée non cumulable |
| Toutes lignes remisées (LC-03-02-15) | remise globale, `discount.global-on-discounted=false`, toutes les lignes réduites | Erreur « TOUTES LES LIGNES SONT DÉJÀ REMISÉES », rien posé ; une ligne propre suffit à faire passer le geste ; ticket vide : le geste s'arme comme avant |

**S3 Le client fidélisé en caisse** `FidelityScanHandler` · `/action/fidelity-lookup` · `TicketPrinterService` [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Remplacement proposé (LC-03-04-08) | seconde carte DIFFÉRENTE scannée, scan multiple autorisé (défaut) | Modale « REMPLACER LA CARTE ? » nommant la carte attachée et la nouvelle ; OUI attache la nouvelle par le chemin d'attachement ordinaire, NON garde l'ancienne ; rien n'est substitué en silence |
| Même carte rescannée (LC-03-04-08) | scan de la carte déjà attachée | Attachement direct (rafraîchissement), aucune modale |
| Scan multiple interdit (BO-10-03-02) | `fidelity.allow-multiple-scan=false`, seconde carte | « CARTE FIDÉLITÉ DÉJÀ SCANNÉE », inchangé |
| Nom et prénom en deux champs (LC-03-04-17) | mode NOM : champ NOM « DE LA CRUZ », champ PRÉNOM « JACQUES » | Les deux critères distincts partent à la recherche, le nom composé se cherche tel quel, les deux champs sont échoés après POST |
| Champ manquant (LC-03-04-17) | mode NOM, prénom ou nom vide | « NOM ET PRÉNOM REQUIS POUR LA RECHERCHE », aucun appel au service de fidélité, critères échoés |
| Code postal (LC-03-04-18) | modèle du porteur imfid (CardHolder) | AUCUNE adresse en base (ni code postal, ni ville, ni rue) et `/api/cards/lookup` ignore le critère ville — la donnée manque côté imfid, la ligne reste non couverte par la caisse |
| Soldes imprimés (LC-03-05-03) | ticket porteur d'une carte, `fidelity-print-balances` actif | Zone « SOLDES CARTE » : solde disponible (ancien) + NOUVEAU SOLDE = projection de cagnotte pour une vente à gain seul, solde confirmé du grand livre pour une vente réglée sur la carte |

**S4 Réaffectation d'un ticket à un client fidélisé** `ReprintResource` · `TicketReassignmentService` [R] [E]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Touche et aval (LC-03-06-01) | touche RÉAFFECTATION du menu réimpression, `gesture.endorsement-ticket-reassign` à vrai | Modale d'aval sur le détail du ticket ; à faux, le geste enchaîné court sans aval |
| Recherche des non affectés (LC-03-06-02) | mode réaffectation, recherche par numéro / date / montant | Seuls les tickets clôturés SANS carte de fidélité sont listés ; un ticket déjà affecté n'est pas sélectionnable (refus nommé côté serveur aussi) |
| Saisie de la carte (LC-03-06-03) | masque carte sur le détail d'un ticket non affecté | Numéro tapé au pavé ; carte vide → « NUMÉRO DE CARTE FIDÉLITÉ REQUIS » |
| Recalcul des avantages (LC-03-06-04) | attachement, moteur joignable | Panier reconstruit DÉTACHÉ, valorisé avec le customerCode, trace TicketLineValuation réécrite sur le ticket fermé ; écart de montant annoncé (« AVANTAGES RECALCULÉS — ÉCART DE x € ») et journalisé, aucun encaissement ni remboursement effectué |
| Recalcul dégradé (LC-03-06-04) | moteur injoignable | Carte attachée, opération tracée, « RÉAFFECTÉE SANS RECALCUL — MOTEUR DE VALORISATION INDISPONIBLE » |
| Impression (LC-03-06-05) | geste abouti | Le nouveau ticket (carte + avantages) sort pour le client ; `reassign.pdv-duplicate` à vrai → un duplicata PDV sort de plus |
| Trace (LC-03-06-06) | geste abouti | Événement technique TICKET_REASSIGNED portant carte, montant, avantages, caisse, opérateur — poussé au nœud par la voie EVENT ; ticket resynchronisé par la voie TICKET |

**S5 Sondage client — déclenchements et types de réponse** `StoreMessageFeedResource` · `SurveyTriggerService` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Début de transaction (LC-03-07-01) | sondage administré `TRANSACTION_START`, premier article scanné | La caisse demande le sondage du moment UNE fois par transaction (au premier article) ; sondage immédiat ne hijaque pas la demande |
| Fin de transaction (LC-03-07-02) | sondage `TRANSACTION_END`, ticket payé | La caisse demande au moment de la clôture ; le nœud sert le sondage du moment même si la session est ouverte |
| Scan (LC-03-07-03) | code `SONDAGE-12` scanné (pattern `scan.pattern.survey`, un groupe capturant = l'identifiant) | La caisse demande le sondage 12 au nœud (`GET /api/messages/survey/12`) ; marche pendant et hors transaction ; code non reconnu → chaîne inchangée ; identifiant exclu de l'audience → rien |
| Réponse numérique (LC-03-07-06) | sondage `NUMERIC` | Pavé de saisie ; un nombre de 1 à 10 chiffres remonte ; toute autre valeur → « NOMBRE INVALIDE » et le sondage reste |
| Code postal (LC-03-07-07) | sondage `POSTAL_CODE`, masque administré (CCCCC / CCCC / CCCC-CCC) | Contrôle du format par pays ; valeur hors masque → « CODE POSTAL INVALIDE », le sondage reste ; sans masque : bornes 4–10 caractères |
| Liste des codes (LC-03-07-08) | liste `postalCodes` administrée | Contrôle d'appartenance ; code bien formé hors liste → « CODE POSTAL HORS LISTE » |
| Informatif (LC-03-07-09) | sondage `INFORMATIVE` | Le bandeau porte un bouton VALIDER seul, rien ne se saisit, la validation remonte avec le marqueur VU — le type est déclaré, pas une convention d'options |
| Facultatif (LC-03-07-10) | sondage `required=false` | Touche PASSER : le sondage disparaît sans remontée ET la caisse mémorise le passe-droit (le même sondage ne revient pas au tirage suivant) ; un autre message ou l'effacement du canal oublie la mémo ; `required=true` : aucune touche PASSER |

## Couverture — état de départ

Ce catalogue énumère, par fonctionnalité, chaque fonction et chacun de ses cas
nominal et d'erreur (colonne *Attendu* = code HTTP + message exact / effet). La vue
condensée antérieure (151 scénarios) est remplacée par ces tables exhaustives.

État des tests écrits aujourd'hui : `src/test/java/com/intermarche/edge/**IT.java`
ne contient qu'une classe, `SecurityExtensionLivenessIT` (3 tests) — elle couvre trois
gardes de sécurité : import CSV anonyme refusé (B1/F), adresse par domaine retirée,
requête GraphQL anonyme refusée (P). La consolidation deux-nœuds (une caisse pousse,
le magasin consolide) est prouvée par `DemoStoreIT`, qui appartient à **impos**. Tout
le reste est à écrire.

## Convention d'implémentation

Une classe par groupe, `Group<X>IT` dans `com.intermarche.e2e`, un `@Test` par
fonction/cas, l'identifiant de la fonctionnalité dans le nom de la méthode et sa
javadoc. `@QuarkusTest` est ici légitime — c'est la couche qui boote l'application,
contrairement aux tests unitaires que `CLAUDE.md` interdit de booter. Failsafe est déjà
configuré (`<skipITs>true</skipITs>` ; lancer par `mvn verify -DskipUTs=true -DskipITs=false`).

## Priorité d'exécution

1. **F** — la porte d'import et ses onze délégations : la table a déjà cassé en entier.
2. **C** et **D** — ingestion et service du référentiel : ce que le nœud fait pour vivre.
3. **G** et **E** — supervision et brique centrale, avec la pile de démo à cinq services.
4. **J** — onze rapports, la concordance CSV/XLSX/PDF est le test le moins cher.
5. **A** et **B** — droits et rôles : la sécurité, une seule garde vérifiée aujourd'hui.
6. **R** — la robustesse, une fois le reste debout.
7. Le reste : **H, I, K, L, M, N, O, P, Q**.

Les scénarios [C] demandent la pile complète ; les [V] le moteur ; les [R] une caisse.
Les [E] ne demandent qu'un nœud et sa base, et ils sont la majorité.


## HD_43 campaign — phase 0 and first P1 checks

- LC-01-06-01: request an operator badge, approve with a supervisor and inspect the receipt body for `[[BARCODE badge-id]]`. Scan the printed CODE128 to authenticate. A missing badge prints a dash and no barcode directive. The new badge path still requires a physical print-and-scan check; the bridge CODE128 primitive was previously proven on the TM-H6000V.
- LC-11-01-07/-08: verify the owned bridge path `ReceiptBody` → `HardwareEndpoints` → `EscPosPrinter.printBarcode`. It emits GS k 73, CODE128 set B and a human-readable caption; 253 data characters fit the 255-byte payload, exceeding the 38-character requirement. This is a code reread, not a new physical measurement.
- LC-07-04-01: keep the distinction between the real P400 login/card-reading/refusal exchange documented on 2026-09-07 and an accepted debit, which the HANDOFF does not demonstrate. No new hardware proof has been obtained in this campaign.
- LC-07-02-02/-03/-04/-05/-06/-07: with 21.42 euros unpaid, open CASH. Check the dedicated exact key and upper keys 22, 25, 30 and 40. With 9.42 unpaid, repeated 10-euro suggestions appear once. Touch each key and validate its amount through the existing cash form.
- LC-07-02-09, LC-07-03-04: expand coins and notes; distinguish each labelled denomination. Administer a five-cent cash step: 23.42 suggests 23.40, 23.43 suggests 23.45, and one- and two-cent keys disappear. Repeat after a partial payment to check the unpaid remainder.
- LC-07-01-02/-03/-04/-05: register a cash partial payment, request return to items and check that the payment stays present and the explicit cancellation proposal appears. Confirm cancellation and check that the existing cancellation controls apply before returning to items. Repeat with an accepted card partial payment. With no partial payment, return directly.
- LC-07-17-02: administer donation.rounding-step-cents and donation.rounding-nearest at the applicable country/banner/store/island/register level. With an upper 10-cent step, 4.35 suggests 0.05; with nearest five-cent rounding, 4.33 suggests 0.02 and 4.32 adds no negative donation. Toggle an existing donation to remove it. Check that the default remains upper-euro rounding.

- LC-07-05-05: administer CHEQUE_DEFERRED independently of CHEQUE, refresh the register referential, take a deferred manual cheque and verify its distinct payment discriminator in the draft, closed ticket, synchronization and accounting filter. Verify independent ceiling/change/drawer settings. No terminal or MICR read is requested by this manual tender.
- LC-07-10-05: reserve a loyalty debit and verify that a signature slip states the card identity and granted amount, while no payment is yet booked. Obtain the customer's handwritten signature and explicitly confirm it: the settlement is then booked. Refuse the signature: no payment is booked and the lease is released. Repeat the initial request while waiting: no second slip or reservation replaces the original. Confirm twice: only one settlement is booked.

These new UI paths have not been exercised on the physical register. Questionnaire HD_42 remains unchanged pending completion and validation of the campaign.


### HD_43 — P1 completion and non-Verifone peripheral maintenance

- LC-07-16-01: administer each tender's existing drawerOpening rule in imedge. With CARD=AFTER_PAYMENT, a complete card sale opens the drawer; CARD=NEVER keeps it closed. With CASH=AFTER_RECEIPT, a completed cash sale waits for receipt printing. A partial payment does not open the drawer. Mixed tenders are all consulted and one sale sends at most one opening order. Repeat in training: no physical opening. Existing defaults remain applicable only to unadministered physical tenders.
- LC-07-05-03: administer cheque.endorsement-lines with {montant-lettres}, {montant}, {pdv}, {caisse}, {ticket}, {date} and {heure}. Verify the actual draft ticket number and the amount spelled in the selected French, Dutch or Portuguese language. The text still uses the existing endorsement path; recto cheque printing belongs to P3 and is not claimed here.
- LC-13-02-01/-02/-03: open MAINTENANCE, reset the printer and run the localised test print. With no binding or a failing device, check the explicit failure. Simulated file transport proves emission of ESC @ and test text; no new physical printer test has been run.
- LC-13-02-04/-05/-06/-07: use the scanner and handheld reset/test actions on the distinctive striped screen. Scan a label and verify that it is displayed without adding any article to the sale. Both roles share the same HID transport on the documented register; reset closes and reopens that transport. The auxiliary trailer identifies handheld samples, including padded reports. The bridge test window expires after sixty seconds, while the register continues to isolate scans for as long as its maintenance screen remains open. Explicit exit restores the selling route.

Automated evidence so far: complete impos suite passes after P1; maintenance driver/endpoint tests pass on file transports and fake scanners. These tests do not constitute new physical proof.

### HD_43 — workstation controls, peripheral availability and key counters

- LC-13-03-01/-02/-04/-05/-06: keep the cash session closed. Select APPLICATION, REBOOT or SHUTDOWN and an administered reason, enter supervisor credentials through the touch keyboard, then prepare the independent bridge confirmation. No command runs before confirmation. Opening a session revokes the permit before persisting the session. Reject a missing supervisor, an open session, an unknown reason, a stale token and a second confirmation. An empty command explicitly simulates; a configured command is passed as executable/arguments without a shell. Unit tests use a fake runner and never restart the host. WORKSTATION_REQUEST records the prepared request in the store journal; actual confirmed execution remains in the bridge's local workstation-control.log and is not yet consolidated as an execution result.
- LC-13-03-03: open the independently served confirmation before making the POS unavailable. It remains usable without the POS JVM for five minutes after preparation. An unexpected frozen POS without an already armed permit cannot obtain fresh supervisor approval; retain partial coverage for that case.
- LC-13-02-09/-10/-12/-14: disable printer, scanner or scale at the applicable hierarchy level. Confirm the disabled indicator differs from the failed indicator. A failed printer must not prevent sale completion or ticket synchronization. A failed scale suppresses subsequent weighing waits until an explicit maintenance probe succeeds. Disconnect the scanner after login: its read-only status is polled every fifteen seconds and the error appears without preventing manual entry. Reconnect it and observe recovery. The campaign uses simulated failures; unplugging actual hardware remains a physical check.
- LC-13-05-01: use configured product/family keys, tender buttons and maintenance buttons. Their stable DOM identity, configured handler or action is journaled as TOUCH_USED with register/operator/timestamp, then pushed through the existing event outbox. Keyboard/PIN keys are excluded. Request `/admin/journal/key-usage.csv` as ADMIN/MANAGER and verify counts grouped by key, register and operator, including CSV quoting. The JavaScript suite covers every branch of key-usage.js.

### HD_43 — documented Verifone simulator and monetary accounting

The simulator evidence is explicitly a documented WCL-frame simulator, implemented by `WimPaymentTerminalTest`, `PaymentOperationTest`, `HardwareBridgeTerminalClientTest`, `VerifoneOutcomeTest` and the payment/refund orchestration tests. It does not load the Linux WCL native library on the Mac and is not a new physical P400 acceptance proof.

| Requirement | Scenario | Expected evidence |
| --- | --- | --- |
| LC-07-04-01 | Debit `{SV}s0{CD}c1{D10}500{D0}978{D13}0{D7V}A99054{TT}1`, interleaved c20 display messages and final RS01 or RS10 | Display lines continue until the final decision; RS00–09 accept, RS10–18 refuse; refusal never books money. |
| Credit and LC-07-04-11 | Credit `{SV}s0{CD}c2{D10}500{D0}978{D7V}A99054{TT}1` from a net-negative basket and from the endorsed Return screen | No outgoing settlement or closed return exists before actual acceptance. Refusal/failure leaves the return unpaid. A duplicate callback does not refund twice. |
| LC-01-02-09 | Close a session after monetary use | The bridge receives logoff c2h; the next session can log in again. Native logoff has not been executed in this campaign. |
| LC-07-04-10/-11/-12 | Return D3L with leading spaces, newlines, equals signs, customer/merchant separator and a refusal frame | Base64 preserves the bridge response. Supplied frames print exactly, without an added header/newline. Both the conditional print path and legacy print button apply the administered attachment rule. Merchant frames are transient and receipt bodies are not logged. |
| LC-07-07-03/-04 | Return VWAY2, known D16, then unknown D16 with known D46 | Persist the actual selected tender and server; round-trip them through the outbox and imedge ingestion. The store node has the register's corresponding tender identities. No Restoflash or other excluded contract is introduced by those data identities. |
| LC-07-06-02 | Select CB/TRD with a 20-euro basket and an 8-euro eligible allowance | One request carries DAJ01, D10=2000 and D20=800. Returned D10 determines the amount actually booked; a partial TRD acceptance leaves the balance due. Local tender controls run before the monetary request; a later configuration change cannot discard an accepted bank result. Supervisor approval replays the terminal request rather than booking a local card payment. |
| LC-07-08-02 | Force degraded mode for debit, credit and CB/TRD | WCL receives VWAY4, including the combined eligible amount when applicable. The bridge still waits for the terminal's decision; it never routes these bridge operations to local auto-acceptance. |
| LC-07-08-01 | WCL displays information about automatic offline operation | c20 messages reach the waiting overlay and D32 is retained. Interactive validation replies remain unimplemented because their request/response command is absent from the local catalogue. Keep partial coverage. |
| LC-07-17-05 | Return a customer frame, a merchant frame and supplementary D3L donation receipts | Every frame prints in order without rewording. The POS does not add another donation line. |

LC-07-07-02 remains partial: the DLL guide documents an abort acknowledgement and SWIMAPI_ERROR_CHRONO=6, but the actual in-flight-abort frame is absent from the local documents. Transaction cancellation c3 must not be guessed as an in-flight abort. LC-07-07-05 remains uncovered: the questionnaire itself requires a protocol evolution carrying multiple applications and amounts in one monetary response. LC-07-01-05 also remains partial because local cancellation of a recorded payment does not issue an external reversal.

### HD_43 — scheduled Verifone maintenance

- LC-13-04-03: enable maintenance.verifone.enabled and configure a night window. c2g is sent to VWAY1 and then VWAY2, never concurrently. An open cash session or running monetary operation defers dispatch; the end of the night window prevents a daytime start. A refused maintenance does not disable payments or prevent the next server from being considered.
- LC-13-04-04: disable random timing and set maintenance.verifone.stagger-seconds. Verify register digits select a bounded sequential offset inside the configured window.
- LC-13-04-05: enable random timing. Verify the planned instant is within the configured window, changes after period-close reset and is regenerated after an application restart or date change.
- LC-13-04-06: accept a primary and a secondary maintenance in the simulator. Inspect verifone-maintenance.txt: each server retains its own last-success timestamp. A refusal, another operation's response or an unwritable trace cannot manufacture a saved success. The bridge operation identity prevents a later card debit from being misread as maintenance success.

The questionnaire says c2g. The local WCL catalogue also prescribes c2g instead of its obsolete maintenance command; it does not establish a c18 maintenance frame. All new scheduling evidence is simulated, not a physical overnight run.

### HD_43 — cheque front and optional PDV data

- LC-07-05-03/-06/-07: configure cheque.front-lines and the beneficiary/place/bank-reference/account-number settings through imedge. Include amount words, numeric amount, actual draft number, store/register identifiers and date/time. Use the cheque form's PRINT FRONT button before validating the tender. It requests paper and prints through POSPrinter/PTR_S_SLIP without requiring a magnetic reading and without booking a cheque payment. Verify the completion notice, paper/transport error, duplicate-request guard and training simulation.
- The slip-control simulator verifies open, claim, enable, beginInsertion/endInsertion, exact printNormal text, beginRemoval/endRemoval and every release step. A printing failure must still release the device. The existing MICR/endorsement operation and front printing share one busy guard.
- Reverse the cheque for the existing reading/endorsement path. Optional beneficiary, bank and account tokens can also appear in cheque.endorsement-lines. No Transax/Verifiance call or authorization number is invented for manual cheques.
- Source for the standard slip sequence: [JavaPOS POSPrinterControl12](https://github.com/JavaPOSWorkingGroup/javapos-contracts/blob/master/src/main/java/jpos/POSPrinterControl12.java) and the [UPOS slip handling diagram](https://public.dhe.ibm.com/software/retail/poseng/opos/upos17.pdf). Actual front alignment and the new text on the TM-H6000V still require a physical paper check.

### HD_43 — physical qualification commands for Henri

These commands were prepared but were not executed by the agent. Run them on the physical till after installing the newly built bridge. The configured working directory and jar name come from sh/caisse.sh. CLI WCL commands require the existing hardware daemon to be stopped so only one client owns station 1. Use an approved test card; tpe-pay below requests a real one-euro debit.

```bash
cd /root/Henri
/opt/java/jdk-25.0.4.1+1/bin/java --enable-native-access=ALL-UNNAMED -jar /root/Henri/impos-hw.jar tpe-test --wcl-dir /root/Henri/Client_Verifone_13.1.7_Linux_DEV/ClientKStrad --station 1 --message '{SV}s0{CD}c2g{D7V}A99054'
/opt/java/jdk-25.0.4.1+1/bin/java --enable-native-access=ALL-UNNAMED -jar /root/Henri/impos-hw.jar tpe-pay --wcl-dir /root/Henri/Client_Verifone_13.1.7_Linux_DEV/ClientKStrad --station 1 --ilot A99054 --amount 100
```

For credit and slip qualification, restart the configured bridge and keep the cash session closed. These HTTP calls use the configured hardware port 8081. The credit requests a real one-euro refund. After the cardholder interaction, repeat the status GET until state=DONE. Do not send another operation while it is RUNNING. The status command omits the encoded private receipt body.

```bash
curl -fsS -X POST -H 'Content-Type: text/plain' --data-binary 100 http://127.0.0.1:8081/api/hardware/payment/credit
curl -fsS http://127.0.0.1:8081/api/hardware/payment | sed '/^answer64=/d'
```

Present a test slip at the cheque station for the following print-only check. It creates no payment and does not require magnetic ink. Poll its status after taking the paper back.

```bash
printf '%s\n' 'TEST HD_43' 'un euro' '1,00 EUR' | curl -fsS -X POST -H 'Content-Type: text/plain' --data-binary @- http://127.0.0.1:8081/api/hardware/cheque/front
curl -fsS http://127.0.0.1:8081/api/hardware/cheque
```

Return the decision/result codes and the observed paper result. Existing physical evidence remains the 2026-09-07 P400 login/card-read/refusal and 2026-09-08 MICR reading recorded in HANDOFF.md. No accepted debit, credit, new front print, workstation restart/reboot/shutdown or overnight maintenance was executed on hardware during this campaign.

### HD_43 — scope and delivery verification

HD_43 changes only ratings/comments for the sixty reviewed rows of Ligne de caisses, plus their local row heights so the new comments can be read. The other worksheet parts, styles, validations and embedded objects are preserved from HD_42. Fifty-seven ratings rise: fifty-four from code, three from rereading existing code, zero from new physical evidence. Five full-coverage proposals are explicitly marked disputable in the report: LC-07-04-01, LC-07-05-06 and LC-13-03-01/-05/-06.

The excluded monnayeur, foreign/proprietary payment contracts, cashback, fidelity PIN, residual language lines, automatic cheque contracts and training-transaction synchronization remain unchanged. No production build or physical hardware command was run. The only commissioning repairs beyond the listed feature paths are the invalid dynamic map expression in admin-safe-days.html and the misplaced Refund association annotations that prevented imedge from starting; both were exposed by DEV packaging/startup checks.


### HD_43 — tests, cleanup and cashier P1 completion (2026-10-20)

These scenarios use the embedded simulator and disposable demo data. The unit tests exercise the rendered Qute fragments and actual touch-keyboard JavaScript; they are not a claim that the following screen journeys were run on a physical checkout.

- **HD43-P1-01 [S] — LC-02-07-14/15, article-group thumbnails.** In the back office, associate a resized image with an EAN article and leave another article without an image. Pull PRODUCTS to the register, open SAISIE DIRECTE and its group, then move to another page and return. The illustrated article shows the inline distributed thumbnail and its wording; the other keeps its wording without a broken image. No image URL or additional image download is requested when opening the group. Tapping either article still selects its original EAN. Check on the 800×600 layout as well as the wider layout.
- **HD43-P1-02 [S] — LC-04-05-02, ticket-search identities.** Prepare two closed tickets with different checkout identifiers, dates, numbers and totals. Search from the duplicata screen and from the return screen. Every result shows the date/time, ticket number, checkout and total; select a row and verify it opens the same ticket. A legacy ticket without checkout identity shows a dash. Keep all six duplicata rows and the return selection reachable at 800×600.
- **HD43-P1-03 [S] — LC-09-01-10, complete e-mail symbol keys.** Open the ticket-email screen and enter `o'connor+tag@example.fr` exclusively with the touch keys. The display and posted address keep the apostrophe and plus. Independently enter each of `! # $ % & ' * + / = ? ^ _` plus the backtick, braces, vertical bar, tilde, hyphen, dot and at-sign; each key inserts its own literal character. Quotation marks, backslash, space, parentheses, square brackets, colon, semicolon, comma and angle brackets are also available. Erase and clear still work, and the keyboard remains scrollable when the available height is small.
- **HD43-REG-01 [S] — BO-03-02-06/07, central-tender boundary.** With local range 500–599, edit a central tender 030 and submit identifier 500 with a changed label and maximum. Only activation may change: identifier, label and maximum stay central. Repeat with a local tender 500 submitted as 030: the range boundary cannot be crossed. A genuine local creation in the local range retains its receipt-copy and forced-print settings.
- **HD43-REG-02 [S] — LC-03-04-17, default fidelity-name lookup.** Submit a lookup with last name DURAND and first name JEAN while omitting the mode field. The normal name mode is selected, both criteria reach the lookup service, and both remain displayed after the response. Blank last-name and first-name refusals remain unchanged.

The cleanup keeps stream closure and I/O-failure handling, perpetual fiscal numbering, GS1 settlement admission and drawer endorsement revalidation under isolated unit tests. No production build, real payment or physical maintenance command belongs to this lot.


### HD_43 — remaining line coverage and unreachable-code cleanup (2026-10-20)

- **HD43-REG-03 [S] — BO-08-01-04, buffered CSV line endings.** Import a disposable file mixing CRLF, CR and LF: header on physical line 1, a valid row on line 2, an empty line 3, a valid row on line 4 and a truncated row on line 5. The two valid rows are imported and the rejection identifies physical line 5. An unreadable incoming stream still reports a failed import. Parsing the already buffered text has no additional I/O failure path.
- **HD43-REG-04 [S] — LC-01-07-05 and LC-01-08-05, interrupted supervisor exchanges.** With a pending request or unread back-office message, interrupt the simulated store exchange. The pending content remains visible and the operation reports interruption; retry after restoring the connection. An ordinary connection failure also retains the unread message.
- **HD43-REG-05 [S] — BO-11-01-05, export boundaries.** Set accounting cutover to 03:00. For daily ticket, invoice and order exports, include the opening instant and exclude the next opening instant. For December, use 1 December at 03:00 through 1 January at 03:00, excluding the latter. Existing archive ordering and page limits remain intact.

These regression contracts are exercised by isolated unit tests with mocked persistence, schedulers, transactions, codecs and transport. The unit campaign also checks cryptographic failure propagation, retention and counter reset after session persistence, optional terminal display callbacks, SMTP credential lookup and JSON payload reconstruction. No physical checkout journey or real SMTP delivery is claimed by these tests.


### HD_43 — price consultation, article prices, EAN-8 and combined ticket search (2026-10-20)

- **HD43-P1-04 [S] — LC-02-14-12, scanner price consultation.** Open INFO ARTICLE with a sale draft containing lines, a selected line and an existing message. Scan a priced EAN, then an unknown code. The consultation shows the price or the unknown-article response; the sale lines, selection, total and message remain unchanged. Repeat while locked and while unlocked with no cash session. RETOUR restores ordinary sale scanning immediately. JE PRENDS, offered only when selling is allowed, ends consultation routing before adding one unit through normal admission.
- **HD43-REG-06 [S] — LC-02-14-12, consultation lifecycle.** Open two successive consultation pages. An old page's delayed poll or exit callback cannot renew, consume or cancel the newer lease. A scan is consumed once. Leaving via normal HTML navigation releases the scanner; closing the page sends its lease release; a disappeared browser expires after thirty seconds without a successful poll. Simulated failed and slow polls preserve the displayed result and cannot overlap.
- **HD43-P1-05 [S] — LC-02-07-11, article-touch price and currency.** Administer an article's current price and a higher-priority price in an active date window. Its SAISIE DIRECTE touch shows the same TTC as its sale scan, rounded to cents. An unpriced article shows a dash. Under country/brand/PDV/island/checkout parameter inheritance, toggle `touch.price-currency`: the amount stays the same and EUR appears only when enabled. Verify a category containing both illustrated and plain products, a second page and the 800×600 layout.
- **HD43-P1-06 [S] — LC-11-01-01, EAN-8 key.** Provide active products for valid EAN-8 codes 12345670 and 96385074. Scanning either follows ordinary pricing and article controls. Change only the final key to create an invalid code: CODE EAN INVALIDE is shown before any product lookup or line addition. Repeat with EAN-13 checking disabled and enabled; EAN-8 validation remains mandatory and the existing EAN-13 setting retains its own effect.
- **HD43-P1-07 [S] — LC-04-05-01, independent and cumulative ticket criteria.** Prepare closed tickets differing in number, loyalty card, sold EAN, date, TTC, checkout and payment method. From both RETOUR and DUPLICATA, search each criterion separately, then combine them. Every entered field applies with AND, with no duplicate rows from multiple matching lines or payments. Closed status and the administered search depth remain mandatory. The reassignment mode additionally keeps only tickets without an attached loyalty customer. Search uses the store tickets present on this node.
- **HD43-REG-07 [S] — LC-04-05-01, touch input and invalid filters.** Enter a ticket fragment containing a checkout prefix and hyphen, a date with slashes and an amount through the cents keypad; switch fields and retain the other values. Select a payment method, clear every field, close the panel and resume the ordinary search keypad. Impossible dates, malformed amounts and unknown payment methods show an explicit error and query no broader ticket set. Return results remain scrollable when several tickets match. Check at 800×600.

The Java campaign exercises scanner routing before any draft mutation, lease expiry and stale-page callbacks, current-price resolution, parameter defaults/overrides, EAN-8 admission and rejection, combined JPQL criteria and the two resource/service paths. Real Qute fragments and the consultation template are rendered in unit tests; JavaScript tests tap the generated criteria keys and execute the consultation polling script. The former native FIFO shutdown fixture is replaced by an isolated mocked binding that deterministically returns after shutdown, retaining the no-decode assertion. Complete offline unit suites are run directly; no physical scanner, payment or full screen journey is claimed as newly qualified here.

### HD_43 — 800×600 browser recipe and article promotion labels (2026-10-21)

Executed on an isolated dev register at localhost:18080 with an H2 in-memory database, simulated hardware, no store synchronization, no external valuation or loyalty connection. The effective browser viewport was checked as 800×600. All cash operations used disposable demonstration data; no physical device or real payment was used. The register was stopped after the recipe.

- **HD43-UI-01 [S] — LC-02-14-12, consultation and layout.** From a connected operator with an open cash session, open INFO ARTICLE and push EAN 3300000000001 through the scanner intake. The page refreshes automatically to Pommes Golden, TTC 1.08 EUR; RETOUR shows an empty ticket and total 0.00 EUR. At 800×600, the original panel pushed the footer below the viewport. The corrected bounded grid and compact panel keep the price, VAT and RETOUR visible. The page is also readable while locked after a dev restart. Physical scanner input and the full lease/browser-loss matrix remain covered by unit contracts rather than this browser recipe.
- **HD43-UI-02 [S] — LC-02-07-11, touch price and usable keypad.** Navigate SAISIE DIRECTE > LAIT DEMI-ECREME. LAIT UHT 1L shows 2.76 EUR; selecting and validating it records a 2.76 EUR ticket line. The original narrow sidebar clipped the last keypad column. Bounded keypad columns and wrapping family fields keep every numeric key and both footer actions visible at 800×600. The back-office currency toggle/inheritance was not exercised in this isolated register recipe.
- **HD43-UI-03 [S] — LC-11-01-01, EAN-8 admission.** With the 2.76 EUR milk line present, scan 12345678: CODE EAN INVALIDE appears and no line is added. Scan valid 96385074, absent from the demo catalog: CODE INCONNU appears, proving the valid key reaches ordinary lookup. The unit campaign separately covers a priced active EAN-8 product and both EAN-13 parameter states.
- **HD43-UI-04 [S] — LC-04-05-01, cumulative search.** Settle the disposable milk ticket in simulated cash, close the simulated drawer, open TICKET > RECHERCHE TICKET and expand RECHERCHE MULTICRITERE. Enter 2.76 using the touch amount keypad and choose ESPÈCES: POS01-00000001 is returned. Add an EAN absent from this ticket: no ticket is returned, proving AND semantics. Clear criteria, enter 31/02/2026 using the date keypad and search: DATE INVALIDE is shown. All criteria and action buttons fit at 800×600. The return-screen journey, all seven matching criteria together, loyalty, retention and foreign-register retrieval were not newly exercised in this browser recipe.
- **HD43-UI-05 [S] — LC-02-11-04, unique-line protection.** On the single milk line, ANNULER LIGNE refuses the gesture with DERNIER ARTICLE — UTILISEZ L'ABANDON DE TICKET; the total stays 2.76 EUR. This confirms EXP-35 but conflicts with the questionnaire wording requiring supervisor endorsement and reason. No policy change or coverage promotion is made pending Henri's decision.
- **HD43-P1-08 [S] — LC-03-01-04, promotion text under its article.** On an engine-valued basket, display each applied discount label directly under its article in the polled receipt. Aggregate multiple labels using the reconciler's existing allocation, independently of paper LINE/FOOTER placement. Revaluation without a discount or coverage clears obsolete text; local/degraded reversion clears it too. Cancelled articles show no promotion label. Preserve the engine's totals and paper placement. New isolated tests verify applied and merged labels, removal on revaluation/reversion, and actual Qute rendering with HTML escaping and cancelled/undiscounted cases. External-engine browser qualification remains to run; this recipe did not inject a fake promotion into the live register.

Audit of LC-02-11-03: HomeService already requests administered cancellation reasons before endorsement, checks the reason against the configured list, and retains normal authorization controls. Existing HomeService unit contracts cover reason request/absence, accepted/unknown reasons and abandonment. The questionnaire comment claiming no reason collection is stale; the complete administered-list/supervisor browser journey still needs qualification before updating that assessment.

Validation: `./mvnw -o test` in impos succeeds: 6,825 Java tests and 90 JavaScript tests, no failure/error/skip. A fresh JaCoCo run covers 25,016/25,016 lines and 12,452/12,452 branches; all instruction/method/class counters also have zero missed. imedge sources were unchanged in this lot; its last complete verified run remains 4,145 Java plus 11 JavaScript tests, with 20,036/20,036 lines and 9,652/9,652 branches. Questionnaire ratings and the PDF were not promoted on the strength of these partial browser journeys.

### HD_43 — unique sold article cancellation: decision implemented (2026-10-21)

Henri explicitly chose “Autoriser avec aval et motif”. This supersedes the EXP-35 refusal observed in HD43-UI-05 above; that historical browser observation no longer describes the current sale-line policy.

- **HD43-P1-09 [S] — LC-02-11-04, mandatory reason then endorsement.** On a single sold article, press ANNULER LIGNE, or target it through cancel-by-scan. A configured cancellation reason must be selected before the endorsement request opens. A cashier cannot execute the cancellation through an unrestricted `cancel-last` setting, a WARN control level, or an existing per-ticket supervisor session: this gesture explicitly opens its own approval request. The existing connected-supervisor approval button and authenticated remote approval remain legitimate approval paths. Until approval succeeds, the line and total stay unchanged. Approved execution preserves the reason until the cancellation witness is persisted, removes the article from the active cart and keeps its cancelled display witness.
- **HD43-REG-08 [S] — LC-02-11-04, missing or invalid reason.** With no CANCEL_LINE reasons distributed by the back office, refuse the unique sold article gesture and display the new localized mandatory-configuration message. No approval request or cancellation occurs. An unknown reason is refused and leaves the reason selection pending. Abandoning reason selection keeps the article. Abandoning endorsement clears the pending reason, label and target so the next gesture cannot inherit the previous justification. Refused approval retains the pending request for a legitimate retry without cancelling the article.
- **HD43-REG-09 [S] — LC-02-11-03/04, existing boundaries.** Other lines retain their administered reason/endorsement controls. The existing negative-only/deposit-return exemption remains unchanged because that line did not sell an article. Recheck both direct cancellation and cancel-by-scan, plus ordinary supervisor and connected-supervisor approval. The full browser journey with administered back-office reasons remains to qualify.

The previous refusal test was obsolete after Henri's explicit policy choice; it now asserts refusal only when required reasons have not been configured. New HomeService contracts verify reason-first routing, forced approval and invalid-reason refusal. EndorsementService verifies reason cleanup, EndorsementResource verifies the reason survives until approved dispatch, and the existing TicketService contract verifies persistence of reason code/label on the cancellation witness. No assertion was removed to conceal a failure.

Complete offline impos suite: 6,828 Java tests plus 90 JavaScript tests pass, with no failure/error/skip. Fresh JaCoCo coverage: 25,026/25,026 lines and 12,458/12,458 branches; all instruction/method/class counters also have zero missed. imedge production sources and questionnaire ratings remain unchanged in this policy lot.

### HD_43 — ordered cross-application recipe completed (2026-10-21)

Executed in the requested order on isolated dev nodes: imedge localhost:18070, impos localhost:18080 and the real imvaluation localhost:18090. Every database was H2 in memory with drop-and-create, hardware was simulated and the effective sale viewport was checked as 800×600. No imvaluation source or pricing rule was changed. The three owned dev processes were stopped after the recipe.

1. **HD43-P1-09 / REG-08, cancellation.** In imedge's administered-list screen, created HD43_CANCEL, kind JUSTIFICATION, function CANCEL_LINE, with ERREUR / Erreur de saisie. The register's real referential pull applied it. Scanned a single oil article at 6.00 EUR, requested cancellation and returned from the reason screen: article and total unchanged. Selected the reason and submitted supervisor mcurie with incorrect PIN 9999: AUTORISATION REFUSÉE, article unchanged. Abandoned the endorsement and requested cancellation again: the reason was asked again. Selected ERREUR, submitted mcurie/1111 through the touch keys: cancelled witness remained visible and the total became 0.00 EUR. Runtime journal contains ENDORSEMENT_DENIED, ENDORSEMENT_GRANTED and successful markLineCancelled(ticketId=1, reasonCode=ERREUR). Persistence-field contents are additionally checked by isolated TicketService tests. Back-office help incorrectly named LINE_CANCEL; corrected the text to the actual CANCEL_LINE function.
2. **HD43-P1-08, real-engine promotion display.** Started the actual imvaluation dev engine. One apple article displayed the combined Immediate Voucher labels PROMO_STORE_101 and BRI_APPLES_DISCOUNT, with engine total 0.89 EUR. A second scan changed the engine selection to Mixed Bundle Promo: PROMO_2FOR1_3300, total 1.19 EUR. This exposed a missing UI leg: an offer's reduced price can carry the promotion without a separate discount advantage. The register reconciler now uses the engine offer label for that price-reduction leg. After reloading the dev register and repeating the scans, the two immediate-discount labels were replaced by the engine's bundle label under the apple line. The unit fixture uses the previous valued total 0.89 and catalog base 2×1.08 to ensure the display decision compares with the catalog base, not a stale prior valuation. Returning to a regular offer removes its label. No calculation, eligibility or application of N+M was moved into impos.
3. **HD43-P1-05, BackOffice currency distribution.** Through imedge's parameter screen, set touch.price-currency=false and saved. After the real pull, SAISIE DIRECTE > LAIT DEMI-ECREME displayed LAIT UHT 1L 2.76 without EUR. Set the same parameter to true in imedge, saved and reloaded the article screen: 2.76 EUR returned, amount unchanged. This qualifies the store-level setting and its distribution; the complete five-level inheritance matrix remains covered by service contracts, not newly played here.
4. **HD43-UI-06 [S], sale keypad layout.** At 800×600 the sale actions and total pushed the lower keypad rows beneath the footer. Bound the main grid's middle track and let action rows share the remaining height, with compact panel spacing. All numeric rows, clear, decimal, erase and OK now stay above the footer. Tapped 1 then 0 and observed 10 in the pre-entry display; cleared it normally. No key was removed or moved out of the bottom-right area.

Final validation: impos 6,829 Java +90 JavaScript tests, imedge 4,145 Java +11 JavaScript tests, all passing with no Java failure/error/skip. Fresh JaCoCo: impos 25,028/25,028 lines and 12,460/12,460 branches; imedge 20,036/20,036 lines and 9,652/9,652 branches. Instrumented JavaScript remains 100%. No physical hardware qualification is claimed.

Questionnaire update: LC-02-11-03 promoted by RELECTURE (existing administered-reason path); LC-02-11-04 and LC-03-01-04 promoted by CODE. Only L/O on rows 118, 119 and 174, plus their row heights, changed. Other workbook cells and ZIP components were preserved and compared. P1: 361 full, 41 partial, 75 not covered, total 477; full 75.7%, partial 8.6%, full-or-partial 84.3%. Whole checkout questionnaire: 482 full, 72 partial, 176 not covered, total 730; weighted coverage 71.0%. The existing synthesis PDF is updated to nine pages, retaining the initial proof pages 3–5.

### HD_43 — read-only audit of all 41 partial P1 (2026-10-21)

Scope: compare the current questionnaire's partial P1 requirements with impos, imedge and impos-hw sources. This is a source audit, not a new browser or physical-device qualification. No production source, questionnaire rating or synthesis PDF was changed. The previous complete test results above remain the reference; tests were not rerun for this documentation-only pass. Evidence below names classes/methods in the relevant repository; it does not imply an external provider contract has been qualified.

Three requirements are candidates for promotion by RELECTURE: LC-02-02-02, LC-02-12-06 and LC-04-04-01. Their old comments contradict implemented paths. They remain rated partial in this audit. The other 38 retain a functional gap or require further qualification. Twelve partial rows had no assessment comment; the table supplies an explicit diagnosis for them.

| Row / requirement | Audit conclusion and remaining gap | Source evidence |
| --- | --- | --- |
| 18 / LC-01-02-07 | Partial. Remote forced logout does not establish closure of the register cash session. A paused register has no active badge, so its remote close can be acknowledged without dispatching closure. | impos RemoteCommandService.apply, SessionCloseService.close; imedge DashboardResource.forceClose |
| 23 / LC-01-03-01 | Partial; stale comment. Pause preserves the ticket and productivity reporting exists. Report duration still uses elapsed shift time without deducting pauses. | impos AuthService.pause, AuthState.pause; imedge OutboundFlowService.ShiftCell.minutes, HourlyProductivityReport |
| 32 / LC-01-05-01 | Partial; the old count of four switches is obsolete. Multiple gesture endorsement flags exist, but no complete configurable policy for every function/key was established. | impos PosSettingsService.gestureEndorsementRequired, EndorsementService.gestureAuthorized |
| 58 / LC-02-02-02 | RELECTURE candidate. Weight to three decimals, unit price/kg and total are represented on POS, customer display and paper. The old missing-price/two-decimal comment is obsolete. Physical print qualification is separate. | impos TicketState.detailLineOf/getDetailLine; ticket.html; CustomerDisplayResource.customerData and customer.html; TicketPrinterService weighed-line rendering |
| 86 / LC-02-07-02 | Partial. Configured page size is capped by the fixed twelve-tile grid and reserved navigation/pinned cells; arbitrary BackOffice key counts/layout are not supported. | impos ManualService GRID_TILES/perPage; manual.html |
| 87 / LC-02-07-03 | Partial. Family/group sorting is configurable, article sorting remains name/EAN. | impos ManualService.orderComparator; ManualRepository product query |
| 108 / LC-02-09-01 | Partial. Second-code collection and persistence exist; no Nexo provider activation/approval before admission was found. | impos EanScanHandler, ActivationCodeScanHandler, TicketState.storePendingActivation, TicketPersistenceService |
| 109 / LC-02-09-02 | Partial. Cancellation is local; provider-approved Nexo activation cancellation is absent. | impos TicketService.cancelItemById; ActivationCodeScanHandler |
| 110 / LC-02-09-03 | Partial. No activation-provider receipt with old/new balances and provider transaction reference was established. Ordinary second-code or local gift-card receipts do not satisfy that exchange. | impos TicketPrinterService; persisted TicketLine activation fields |
| 115 / LC-02-10-03 | Partial. BackOffice VAT-code toggle exists, but receipt codes use the regime number rather than the required A/B/C letters. | impos PosSettingsService ticket.vat-code; TicketPrinterService.vatCodeOf |
| 126 / LC-02-12-06 | RELECTURE candidate. Cancel-by-scan removes one unit by default from a multi-unit ordinary article. Explicit keyed quantities and weighed/embedded-price articles have distinct handling. This is not the whole-line cancellation button. | impos HomeService.cancelByScan/decrementByScan; HomeServiceTest.decrementByScanTakesOneUnitWhenNothingWasKeyed |
| 198 / LC-03-04-05 | Partial. A loyalty card can attach during payment, but ordinary engine revaluation exits while payment is in progress. | impos FidelityService.validateCard; ValuationService.revalue |
| 235 / LC-04-01-02 | Partial. Parked receipt prints clear-text identity and details under a boolean switch; barcode and detailed/non-detailed/on-request options are missing. CODE128 support itself exists. | impos TicketParkingService; TicketPrinterService.printParkedTicket |
| 236 / LC-04-01-03 | Partial. Parking/resumption/purge traces exist; no equivalent sealed retention chain for parked/cancelled documents or complete compliance proof was established. This audit makes no legal-conformity determination. | impos TicketPersistenceService.closeTicket and parking lifecycle |
| 250 / LC-04-04-01 | RELECTURE candidate. Abandonment endorsement is configurable; the old systematic-endorsement comment is obsolete. | impos HomeService.cancelTicket; gesture.endorsement-abandon; HomeServiceTest direct/endorsement paths |
| 266 / LC-04-05-05 | Partial. Retrieved receipt details exist, without an accessible explanation of the promotion calculation. Current live-ticket promotion labels do not supply that recalled-ticket view. | impos ReprintResource/ReprintService/templates; TicketReassignmentService |
| 276 / LC-05-02-02 | Partial; stale comment. Return types can request a catalog-price confirmation/edit prompt. After a quantity prompt, however, confirmEntry chains to price only for the product's priceToEnter flag, omitting the return-type priceConfirmed flag. | impos TicketService.suspendForEntry/returnPriceConfirmed/confirmEntry; PosListService.returnTypes |
| 290 / LC-05-03-01 | Partial. No external Nexo balance/deactivation sequence for a returned dematerialized product was found. Local credit-note handling is separate. | impos RefundService, activation handlers; impos-hw payment adapters |
| 297 / LC-05-06-03 | Partial. Dedicated refund flow supports asynchronous terminal credit and hardware c2 routing. General negative-ticket card payment is not established: processCard rejects non-positive amounts. Physical terminal qualification remains. | impos RefundService.requestCardCredit; PaymentService.processCard; HardwareBridgeTerminalClient; impos-hw HardwareHttpServer, WimPaymentTerminal.credit |
| 305 / LC-05-07-02 | Partial. Refund revaluation summary and loyalty return event exist. Full coupon/voucher/loyalty impact details and the separate mixed-sale/return receipt are not established. | impos RefundService; TicketPrinterService.printRefund/appendRecalculationBlock |
| 398 / LC-07-07-02 | Partial; external protocol dependency. Local abort concepts and CHRONO=6 recognition do not supply a documented WCL abort request, response serialization and hardware HTTP path. c3 must not be invented as abort. | impos PaymentTerminalClient; impos-hw WimClient, payment routes/operation |
| 406 / LC-07-08-01 | Partial; protocol/qualification dependency. Degraded progress/status relay exists; interactive operator validation and its terminal reply exchange remain absent. | impos-hw WimPaymentTerminal progress handling, PaymentOperation |
| 427 / LC-07-11-01 | Partial; stale local-only comment. A configurable HTTP issuer query exists, including balance, but is not Nexo. Balance is used to cap payment without the required explicit balance-then-amount presentation. | impos GiftCardIssuerClient.query; PaymentService.processSupplierGiftCard |
| 429 / LC-07-11-03 | Partial. Slip has masked card, debit and remaining balance. Old balance, provider code and a dedicated BackOffice activation policy are missing from the established path. | impos PaymentService.printGiftCardPaymentSlip; TicketPrinterService.printGiftCardPaymentReceipt |
| 455 / LC-08-01-03 | Partial. Ticket identity is printed as a one-dimensional barcode without the required activation/type choice. Footer URL QR configuration is a different feature. | impos TicketPrinterService.renderTicket/ticketBarcode/footerUrlBlock |
| 460 / LC-08-02-02 | Partial. Closed-sale formatted content is synchronized; complete formatted coverage of all customer/shop/technical documents is absent. Base64URL is not required by this row and is not counted as a separate functional gap. | impos Ticket.formattedContent, SyncOutboxService; shared invoice payload |
| 463 / LC-08-02-05 | Partial. Mail formatting options exist, but no BackOffice switch governing the end-of-transaction email offer was established. | impos TicketEmailResource; ticket.email-format settings and payment templates |
| 464 / LC-08-02-06 | Partial. Sale email works; all customer-document categories, excluding technical documents, are not covered by this mail pipeline. | impos TicketMailService; DigitalTicketResource |
| 477 / LC-08-03-07 | Partial; stale list. Forced-print policy now includes AVOIR, FID and ENLEVEMENT as well as TICKET/CARTE/BON. Full required document coverage, including refund/deposit policy, remains incomplete. | impos PrintPolicy; print.forced-documents catalog; RefundService |
| 478 / LC-08-03-08 | Partial / inventory qualification. Automatic opening/closing switches and an unknown-article slip setting now exist; old claims that these are absent are obsolete. A complete mapping of every technical document's activation and print trigger remains to qualify. | impos PosSettingsService.printOpenReceipt/printCloseReceipt/islandUnknownArticleTicket; SessionCloseService; TicketPrinterService.printUnknownArticleSlip |
| 525 / LC-08-06-02 | Partial. Default NIF is available in invoice customer entry, not a universal sale-level NIF workflow. | impos InvoiceService.prefilledCustomerData; fiscalIdentifier |
| 526 / LC-08-06-03 | Partial. Invoice NIF can be edited; ordinary-sale NIF editing remains unestablished. | impos InvoiceService customer-data handling |
| 534 / LC-08-06-11 | Partial. Country/NIF and final-consumer formatting exist on supported documents; distinct Portuguese FS/FT/Recibo/Nota de Credito coverage is missing. | impos InvoiceService; domain.sale.DocumentType |
| 558 / LC-10-01-01 | Partial pending visual qualification; stale fixed-font comment. Shared clamp-based sizing and flexible/scrolling layout exist. Absence of media queries is not proof of failure. Customer-screen checks at all required resolutions/ratios remain to run. | impos customer.html and shared theme |
| 560 / LC-10-01-03 | Partial. Valued line amounts are present, but explicit discounts/rebates/promotion labels are absent from customer payload/rendering. POS promotionLabel does not reach this screen. | impos CustomerDisplayResource.customerData; customer.html |
| 574 / LC-10-01-17 | Partial. Customer display receives remaining amount and payment state, without a list of each registered tender label and amount. | impos CustomerDisplayResource.customerData; customer.html |
| 639 / LC-12-04-04 | Partial. Deposit slip includes terminal, operator name and total; operator number and BackOffice-controlled activation remain missing. | impos TicketPrinterService.printDepositTicket; deposit caller |
| 651 / LC-12-07-01 | Partial. X report uses the cash session, without the required operator-period selection and endorsement. | impos CashSessionResource.showXReport/printXReport; CashSessionService.buildReport |
| 652 / LC-12-07-02 | Partial. Session totals/tenders/movements exist, but not all requested counts/amounts and configurable report sections. Separate BackOffice reports do not complete the POS report requirement. | impos CashSessionService.SessionReport; imedge specialized cashier/cancellation/price-forcing reports |
| 701 / LC-13-05-04 | Partial. Training restrictions are hardcoded in individual flows; no complete BackOffice per-key allow/block policy was found. | impos RefundService, ReprintService, CashSessionResource; training settings |
| 709 / LC-13-06-06 | Partial. Customer static labels use a single-language resolver. A bilingual resolver exists elsewhere, but is not applied to all customer mentions. Article labels should remain unchanged. | impos customer.html; CustomerDisplayResource; i18n get/both callers |

Unchanged authoritative indicator: 361/477 full (75.7%), 41/477 partial (8.6%), 75/477 not covered (15.7%); **402/477 full or partial (84.3%)**. Candidate reclassification is not included in those numbers. Next action, if authorized, should distinguish the three evidence-backed reclassifications, targeted functional work, visual recipes and external terminal/Nexo contracts rather than treating all 41 as new development.

### HD_43 — three RELECTURE promotions and first remaining-P1 fixes (2026-10-21)

Henri approved the three audit candidates. LC-02-02-02 (row 58, weighed detail on POS/customer/paper), LC-02-12-06 (row 126, one-unit cancel-by-scan) and LC-04-04-01 (row 250, configurable abandonment endorsement) now read “1. Couvert intégralement”. Their comments explicitly identify RELECTURE and the existing code/test evidence. No physical qualification is added. Only L/O and row heights on those three workbook rows change; all other cell values and ZIP components are compared with the baseline. The existing synthesis PDF is updated to eleven pages; initial proof pages 3–5 are preserved. Campaign total: 71 promotions, 64 CODE and 7 RELECTURE.

The following two code corrections start the remaining partial-P1 work; they are not additional questionnaire promotions in this lot:

- **HD43-P1-10 [S] — LC-05-02-02, return quantity followed by confirmed price.** Configure a manual-return type with price confirmation and an article that asks for quantity, but not ordinarily for price. Start the return: the catalog price remains visible. Validate quantity 2.5: no line is added, and the price prompt follows. Enter a price below or above the catalog price: exactly one negative-quantity line is recorded at that entered price. Types without confirmation and unknown types keep quantity-only admission at catalog price. TicketService.confirmEntry omitted the return-type policy when chaining prompts; the code was wrong. Two new isolated tests cover lower/higher prices, the negative extended amount, no early insertion, and disabled/missing policy. Existing tests retain the sale and product-driven price paths. The weighed PLU path still bypasses this entry mechanism, so the whole requirement remains partial; no new browser or scale qualification is claimed.
- **HD43-P1-11 [S] — LC-10-01-03, customer-display advantages.** Project the existing engine promotion label and manual price-modifier label under the corresponding customer article. A cancelled line projects neither label. Show an allocated global ticket discount once as an informational row; do not subtract it again from the already-valued total. Revaluation or removal of the advantage replaces the DOM and clears obsolete labels. New labels use textContent. One new resource test checks article association, cancelled suppression and the global discount payload. Three DOM tests execute the real embedded customer script and verify label escaping, unrelated articles without advantages, refresh/removal, an unchanged valued total and partial-payment visibility. The missing projection/rendering was a code defect. The two exact payload-size assertions increase from 17 to 19 because the snapshot gains discountLabel and discountAmount; all previous assertions remain. Full visual customer-screen qualification is not newly claimed; the questionnaire rating remains partial in this lot.

Complete offline impos validation: `./mvnw -o test` passes 6,832 Java tests and 93 JavaScript tests, no failure/error/Java skip. Fresh JaCoCo counters: 25,032/25,032 lines, 12,466/12,466 branches, zero missed instruction/method/class counters. The instrumented external JavaScript sources remain at 100%; the three new embedded-script DOM tests are not included in that instrumentation percentage. A subsequent targeted DOM rerun passes after removing unrendered Qute/head content from the test fixture only. imedge and impos-hw production sources are unchanged; imedge's prior complete validation remains 4,145 Java plus 11 JavaScript tests, 20,036/20,036 lines and 9,652/9,652 branches.

Current authoritative P1 indicator: **364/477 full (76.3%), 38/477 partial (8.0%), 75/477 not covered (15.7%); 402/477 full or partial (84.3%)**. Whole checkout questionnaire: 485 full, 69 partial, 176 not covered, 730 total; weighted coverage 71.2%. The earlier audit table is historical: the three approved candidates are now promoted and the two defects described above have been corrected within the stated limits.

### HD_43 — customer display and weighed return completed (2026-10-21)

Henri approved completing the proposed block, and asked that every future proposal state its concrete gain. Gain delivered here: customers can read applied advantages and each settlement, with bilingual standard mentions; cashiers can change a weighed return's price without weighing the article again.

- **HD43-P1-10 [S] — LC-05-02-02, weighed return price.** The PLU flow now parks its net quantity, PLU and applied tare before insertion when the administered return type requests price confirmation. The catalog unit price is visible; accepting a lower or higher positive price inserts one negative-quantity line with the original tare and weighing. Confirmation resolves by PLU even without an EAN. Invalid price keeps the prompt open. Cancelling clears the retained metadata and does not consume the duplicate-weighing guard; rescanning the same weight remains possible. The last recorded weight advances only when a line is actually admitted. Two additional isolated tests check the lower/higher paths, negative quantity, metadata, exactly one scale read per article, invalid input and cancel/retry. This is software evidence with a mocked scale, not a new physical qualification.
- **HD43-P1-12 [S] — LC-10-01-17, each registered settlement.** CustomerDisplayResource projects the ordered registered payments with their administered wording, bilingual fallback or specific voucher label, and amount applied to the ticket. Cash tendered/change does not replace the applied amount. The cart and thank-you screen both display them; cancelled entries disappear on refresh. Tests cover every known fallback, absent/null/blank administered label, unknown method, voucher wording, cash applied amount, order and the completion/cancellation DOM refresh. No card authorization or payment processing is changed.
- **HD43-P1-13 [S] — LC-13-06-06, bilingual customer mentions.** The existing customer second-language setting drives the standard Qute mentions and dynamic payment/remaining/cash/change/digital-receipt labels through Messages.both. Ticket-level discount wording uses the same resolver, including its percentage. Four keys are added together to FR/NL/PT. Product and administered free-text labels are preserved. CustomerTemplateTest renders the actual template with the real FR/NL bundles; the dynamic projection and DOM contracts check bilingual wording without changing article labels. No new parameter is introduced.
- **HD43-P1-11 / UI-07 [S] — LC-10-01-03, 800×600 and 1024×768.** Captured the actual HTML rendered by the isolated dev register, then exercised its real browser script with a local fixture payload containing line promotion, manual discount, global discount, two settlements, a long article label and bilingual payment mentions. Two iframe content viewports have the exact required dimensions. This visual harness is explicitly a fixture, not a new live-engine sale or hardware recipe. The first capture exposed an escaped total entity and a wrapped euro suffix. Use the plain total message, keep amount/currency together, bound the scrollable lines and reserve payment space. Final browser inspection shows the advantages, both payment amounts, total and remaining amount in both sizes; a completion fixture shows both settlements and change on the thank-you screen. The static bilingual template is separately covered by the real-bundle test. Proof image: /tmp/hd43-customer-visual/resolutions.png. LC-10-01-01 remains partial: this lot does not qualify all other display ratios/resolutions.

Final complete offline impos run: 6,837 Java tests, no failure/error/skip; 94 JavaScript tests pass. Fresh JaCoCo: 25,084/25,084 lines, 12,488/12,488 branches; instruction, complexity, method and class counters also have zero missed. The embedded customer-script tests are distinct from the external JavaScript instrumentation percentage, which remains 100%. Full runs were repeated only for new functional/test changes. The code was responsible for missing customer information, incomplete price-prompt chaining, premature weighing-guard consumption and the total layout defect; tests were extended without removing assertions. Snapshot-size expectations reflect the two new payments/labels fields. No imedge or impos-hw source changed; their previously recorded verification remains the reference. The owned dev register was stopped; all databases were disposable H2 in memory, hardware simulated, valuation/store URLs blank. No physical device was actuated.

Questionnaire: LC-05-02-02, LC-10-01-03, LC-10-01-17 and LC-13-06-06 promoted by CODE. Only L/O and row heights on rows 276, 560, 574 and 709 change; other values and ZIP components are compared with the baseline. PDF now has twelve pages, preserving initial proof pages 3–5. Campaign: 75 promotions, 68 CODE and 7 RELECTURE.

**Current P1: 368/477 full (77.1%), 34/477 partial (7.1%), 75/477 not covered (15.7%); full or partial 402/477 (84.3%).** Whole checkout questionnaire: 489 full, 65 partial, 176 not covered, total 730; weighted coverage 71.4%. Earlier partial assessments in this log are historical and superseded by this completed lot where named.

### HD_43 — remote closing, net productivity and parked receipt completed (2026-10-21)

Henri approved these three items in order. Delivered gain: the store sees a real session closure, productivity measures time outside recorded pauses, and a waiting cart can be resumed by scanning its receipt. No imvaluation or impos-hw source was changed; no physical device was used.

- **HD43-P1-14 [S] — LC-01-02-07, close active or paused register.** RemoteCommandService always reaches the closing seam, including a paused operator and an operator-free register whose cash session might remain open. It shares the payment/session-opening monitor until the transactional close returns; only then is the remote order acknowledged. SessionCloseService resolves the active or retained paused identity, cancels an unpaid persisted draft, closes CashSession and logs the operator out. An unfinished/in-flight or partly paid settlement leaves the command pending; a completed sale is never cancelled. CashSessionService preserves the existing closing lifecycle (parked cancellation, journal, retention, terminal hook and outbox), but a remote closing keeps counted amount, variance and withdrawal unknown rather than inventing a zero discrepancy. Its journal explicitly says “fermeture forcée sans comptage”. An already closed session is idempotent; database failure never causes an acknowledgement. Tests assert active/paused identity, ordering, unknown count fields, SESSION sync, partial-payment preservation, failure/no ACK and the shared state monitor.
- **HD43-P1-15 [S] — LC-01-03-01, exclude pauses from cashier productivity.** The register's existing pause preserves the cart and records REGISTER_LOCKED/REGISTER_UNLOCKED under the operator badge. imedge's CASHIER_PRODUCTIVITY export now reads those transitions for the exact badge, terminal and shift bounds, including the last sale as the existing bound of a still-open session. Completed pauses, repeated lock/unlock events, initial resume and unfinished final pause are folded once; milliseconds are subtracted before whole-minute rounding. A two-hour shift with twenty minutes of pause has 100 worked minutes, so 60 items give 36.00 items/hour; turnover and average basket do not change. Tests cover no events, completed/repeated/initial/trailing/full pauses, sub-minute precision, absent/blank badges, null cashier and exact query parameters/order. Different terminals are separate cells. Legacy rows without a usable nominative journal retain their known elapsed duration: missing pauses are not guessed. HourlyProductivityReport measures sales/customer/register counts by time slot and has no shift-duration divisor to change.
- **HD43-P1-16 [S] — LC-04-01-02, scannable waiting receipt.** The printed CODE128 directive carries the exact existing resume number, also shown in clear text. The historical parking.print-receipt flag stays the master activation. New mirrored, echelon-administrable parameters are parking.print-mode (AUTO default, REQUEST on demand) and parking.receipt-detail (true default). The BackOffice offers the closed AUTO/REQUEST choice; the register retains AUTO fallback for unknown stored values. The parked list exposes IMPRIMER when printing is enabled; this action prints without resuming and refuses missing, foreign or no-longer-parked tickets. Summary mode retains barcode/identity/total and omits articles; detail excludes cancelled lines. Tests cover default/REQUEST/disabled/unknown policy, ownership, resource redirects, no side-effect resume, barcode and detail selection. The parked golden master was reviewed and changed only to add the explicitly required barcode line; its mock now reflects the catalog's default detail=true.

Live cross-application recipe used imedge localhost:18070 and impos localhost:18080, construction profile dev, separate H2 in-memory databases with drop-and-create, virtual payment terminal and embedded hardware simulator. The register's hardware REST client was explicitly pointed at its own dev port. The initial standalone hardware URL could not revoke workstation permits; the recipe was restarted with the correct simulator loopback before any successful cash-session opening. All sessions and articles below are disposable demo data, with no external valuation engine.

1. Signed in as seeded cashier/manager mcurie and opened POS01-S00001 with float 100.00. Used the real pause route; the lock page named Marie Curie as the paused operator. Sent the force-close command through the actual authenticated imedge endpoint. The register journal records SESSION_CLOSED “POS01-S00001 - fermeture forcée sans comptage”, then the forced operator closing. The command list becomes empty and /dashboard-data lists no open POS01 session.
2. Opened POS01-S00002. Saved REQUEST with article detail=false in the actual BackOffice settings; used the real referential replay. Scanned milk EAN 3300000000002, total 2.76. Parking printed nothing. The actual /parked HTML contained its print action. An explicit print produced a summary without LAIT and with [[BARCODE POS01-00000001]]. Sent that exact scanned number through /api/pos/scan; the ticket was restored with total 2.76 and its article. The journal records TICKET_RESUMED.
3. Saved AUTO with article detail=true in imedge. An immediate second pull was still inside the existing 60-second fingerprint cache, so the first immediate-auto expectation was premature. After normal cache expiry and replay, the received policy applied: resumed and reparked the same cart, and the simulator printed the barcode plus LAIT automatically. No cache code was modified for this lot.
4. Requested force-close while the operator was active, with that unpaid parked cart. The second session was shown open before the command and absent afterwards; /api/commands/POS01 again returns []. A browser login to the actual store dashboard confirms “Aucune session ouverte”. Screenshot: /tmp/hd43-session-recipe/dashboard-closed.png. The owned register and store dev processes and their children were stopped after the recipe. Simulated CODE128 and scan-bus round-trip are software proof, not a new thermal-printer/physical-scanner qualification.

Validation: final complete `./mvnw -o test` succeeds in both repositories. impos: 6,847 Java tests, 94 JavaScript tests; JaCoCo 25,120/25,120 lines and 12,524/12,524 branches. imedge: 4,149 Java tests, 11 JavaScript tests; 20,058/20,058 lines and 9,678/9,678 branches. No Java failure/error/skip; all missed instruction/complexity/method/class counters also zero. Two operator keys, parked.printReceipt and parked.printDisabled, are added together in FR/NL/PT. Both new parameter accessors/catalogs are verified, including defaults and persisted choices.

Test adjustments preserve their assertions: remote-command tests target the new persistence seam (including already-closed/orphan-session checks); success is explicitly stubbed for its boolean result. The new monitor assertion initially fired during Mockito restubbing outside an actual close; doReturn/doThrow fixes that fixture, leaving the monitor assertion intact. Printer mocks explicitly use the actual detail=true default; the golden reference adds only the requested barcode. The BackOffice catalog projection's exact expected list gains the two new keys. Those old expectations/fixtures were out of date for the new behavior; no assertion was disabled or removed.

Questionnaire rows 18 (LC-01-02-07), 23 (LC-01-03-01) and 235 (LC-04-01-02) are promoted by CODE. Only their L/O cells and row heights change; other workbook values and ZIP components are compared to the baseline. Existing synthesis PDF now has thirteen pages, with the original proof pages 3–5 preserved. Campaign total: 78 promotions, 71 CODE and 7 RELECTURE.

**Current P1: 371/477 integral (77.8%), 31/477 partial (6.5%), 75/477 not covered (15.7%); integral or partial 402/477 (84.3%).** Whole checkout questionnaire: 492 integral, 62 partial, 176 not covered, total 730; weighted coverage 71.6%. Earlier partial comments in the historical audit are superseded for these three named lines.


## HD_43 — article touch layout, historical promotions and endorsed operator revenue (2026-10-21)

Four P1 requirements completed by CODE: LC-02-07-02, LC-02-07-03, LC-04-05-05 and LC-12-07-01. Gains: fewer article pages, useful articles first, historical pricing explanations during returns, and approved operator-specific revenue checks.

### Article layout and order

1. In the existing BackOffice settings, set `touch.groups-per-page=24`, `touch.grid-columns=4`, and `touch.article-order=3300000000002;3300000000019`. Both catalogs declare the two new settings; the existing five-level inheritance and SETTINGS distribution apply.
2. Pull the register referential, open SAISIE DIRECTE / EPICERIE. The milk and eggs appear first, followed by unlisted articles alphabetically. The actual demo group shows all 22 articles in four columns, without clipping the numeric keyboard. This was verified against the real imedge and impos services and the rendered browser page.
3. Unit tests additionally verify 24 articles on page one and the 25th on page two, deterministic database ordering before offset/limit, parameter-bound EAN values, empty and duplicated priority entries, two-to-six column bounds, wide pinned tiles, and the reserved navigation cell. No full catalog is loaded to sort a page.
4. The configured page size includes pinned content; navigation adds one cell. The old fixed twelve-cell screen ceiling has been removed. An existing volume-order test had configured only two content cells while expecting four including pinned groups; its configuration now explicitly requests four, preserving every ordering assertion.

### Historical promotion explanations

1. Recall a persisted ticket and press DÉTAIL DES PROMOTIONS. The existing detail remains paginated and retains quantity, description, line amount and total.
2. The new view reads that ticket's persisted TicketLineValuation rows: local/valued amounts, offer and advantage labels, allocated advantage, correction label and amount. It also shows recorded manual/global discounts and loyalty lines.
3. No valuation request or recomputation occurs. Missing historical traces produce an explicit unavailable-trace message rather than invented promotion facts. Missing tickets return to history.
4. Unit tests render a recorded 12.00 to 9.00 valuation, a 4.00 advantage and 1.00 correction, manual/global discounts and loyalty. Real-service demo: a simulated milk sale at 2.76 EUR was completed and recalled; its unvalued ticket correctly states that no calculation trace is available. This live case does not claim a new engine qualification.

### Endorsed operator revenue

1. Both the read route and the legacy direct-print route expose only a supervisor authorization step. Invalid credentials disclose no figures and do not load a report or print.
2. Approve using the shared tactile identification panel. The snapshot selects only CLOSED tickets belonging to the connected employee and the current cash session, not the employee who opened that session. It shows ticket count, TTC revenue and settlement totals.
3. Printing requires a fresh approved request. Both grants and refusals are journaled through the existing endorsement service; printing adds X_REPORT_PRINTED. The query and print run under the same register lock as session transitions. No persistent authorization grant is introduced.
4. The existing register-wide X remains available through a separate approved action, with its float, movements and theoretical drawer figures. The operator-only print omits those register-wide balances.
5. Actual demo: wrong named credentials refused; named supervisor approval succeeded; the browser tactile identifier-plus-PIN path succeeded. A completed simulated cash sale produced an approved operator screen and printed receipt for Marie Curie at 2.76 EUR. The existing global X still showed the 100.00 EUR float and the 2.76 EUR sale.

### Validation and resulting coverage

Offline complete suites: impos 6,859 Java tests + 94 JavaScript; imedge 4,150 Java + 11 JavaScript. Zero failures, errors or skipped Java tests. JaCoCo: impos 25,198/25,198 lines and 12,566/12,566 branches; imedge 20,061/20,061 lines and 9,680/9,680 branches. All missed instruction, method, complexity and class counters are also zero. Instrumented external JavaScript remains at 100%; this is not a claim of instrumentation of all embedded template scripts.

Two new catalog entries required extending the exact expected BackOffice catalog in its test. The test fixture, not the catalog code, was out of date. New test fixture errors (private accessor stubbing, missing static assertion import and inherited Panache finder mocking) were corrected; no assertions were removed or disabled.

P1: 375/477 fully covered (78.6%), 27/477 partially covered (5.7%), and 75 not covered. Fully or partially covered: 402/477 (84.3%). Overall Ligne de caisses: 496 full, 58 partial, 176 not covered; weighted coverage 71.9%. Cumulative campaign promotions: 82, comprising 75 CODE and 7 RELECTURE. LC-12-07-02 remains partial: configurable detailed accounting rubrics and their per-rubric transaction counts were not part of this lot.

All live checks use dev builds, H2 in memory recreated on startup and simulated hardware. No physical printer or payment terminal qualification, production build, or change to impos-hw is claimed.


## HD_43 — configurable revenue-report operation rubrics (2026-10-21)

LC-12-07-02 completed by CODE. Gain: one approved report exposes operation counts and TTC amounts without collecting them from separate screens.

### Calculation and scope

- The operator report selects the connected employee and current CashSession. The register-wide X explicitly selects the full session. The supervisor approval introduced for LC-12-07-01 remains before reading or printing either scope.
- Sales count completed sale tickets, not quantities. Mixed tickets contribute their sold part to sales and their returned part to returns; a ticket can therefore contribute once to both document counts. Pure and zero-value returns and free sales are tested. Open and parked tickets do not contribute sales.
- Finalized Refund documents add to returns. Their outgoing settlements reduce the corresponding tender amount; negative ticket settlements also retain their sign. Refunds with an unknown historical method are counted as returns without inventing a tender identity.
- Cancelled article rows retain their removed TTC amount and are scoped by cancellation badge, with ticket cashier fallback only for legacy rows without an author. The query uses the actual unidirectional Ticket.lines association. An initial inverse-path query failed in the live recipe; the code and its unit fixture were corrected, and the real scoped and register-wide queries were replayed successfully.
- Abandoned tickets are counted separately with their recorded TTC amount. Forced-price rows use the final persisted affected line and its TTC magnitude; they do not fabricate a history of overwritten price gestures. Deposits, withdrawals and expenses are distinct movement counts/amounts. Declarations, transfers and customer down-payments are not silently classified as cash supplies or sales.
- Rubrics overlap and are not an additive revenue reconciliation; the shared screen explains the counting basis and net tender figures. Amounts are accumulated before presentation rounding.

### BackOffice and shared presentation

Nine BOOL catalog entries, default true, exist in impos and imedge: report.show-sales, report.show-returns, report.show-expenses, report.show-cancellations, report.show-abandoned, report.show-price-overrides, report.show-deposits, report.show-withdrawals and report.show-tenders. Existing country/brand/store/island/register inheritance and SETTINGS distribution apply.

The operator screen, register X detail and paper share the same calculated operationRows snapshot. The new table carries rubric, count and amount, including zero operations. The operator paper contains only configured rubrics. An administered X layout is followed by the same configured rows; the Z receipt presentation stays unchanged. All fourteen new operator message keys are aligned in FR/NL/PT.

### Actual service recipe

Run dev builds of imedge on 18070 and impos on 18080, using H2 in-memory databases recreated on startup and loopback simulated hardware. Sign in as seeded supervisor Marie Curie, open a period with 100 EUR float, complete a simulated milk sale at 2.76 EUR, record a 10 EUR supply through the apport screen, a 3 EUR withdrawal to Caisse centrale and a 2 EUR STAMPS expense.

The approved operator report and simulated receipt show sales 1 / 2.76, supply 1 / 10.00, withdrawal 1 / 3.00, expense 1 / 2.00, and cash settlement 1 / 2.76. The shared tactile identifier-and-PIN approval was exercised in the browser and the whole report was visually reviewed.

Set report.show-expenses=false and report.show-price-overrides=false in the actual BackOffice settings. After normal referential fingerprint cache renewal, replay the pull. Both rubrics disappear from screen and paper, while the 10 EUR supply remains. The global X also renders successfully with its original drawer information and the detailed operation table. No physical printer, terminal transaction or production build is claimed. Owned demo processes are stopped after verification.

### Tests and cotation

Complete offline suites: impos 6,864 Java + 94 JavaScript; imedge 4,151 Java + 11 JavaScript. Zero Java failures, errors or skipped tests. Java coverage: impos 25,286/25,286 lines and 12,637/12,637 branches; imedge 20,062/20,062 lines and 9,680/9,680 branches. Every JaCoCo missed instruction, complexity, method and class counter is zero. External instrumented JavaScript remains at 100%; embedded template scripts are not all instrumented.

Tests cover mixed/pure/free returns, free sales, legacy negative tickets, cancelled lines, force-price sales and returns, all movement types, absent legacy movement amount, all refund methods, null legacy refund method, scoped and global reads, visibility true/false, empty rubrics, accumulated rounding, actual Qute row rendering, operator receipt and administered X/Z behavior. The exact BackOffice catalog expectation was extended by nine entries; its fixture was out of date, not the catalog code. A Panache inherited-static mock collision was corrected by explicitly ordered ticket query fixtures, without weakening assertions.

Questionnaire changes are restricted to L652/O652 and its row height. Previous promotions and all other workbook components are preserved. The PDF adds the current lot while preserving previous proof and lot pages.

P1: 376/477 full (78.8%), 26/477 partial (5.5%), 75 not covered. Full or partial: 402/477 (84.3%). Ligne de caisses: 497 full, 57 partial, 176 not covered; weighted coverage 72.0%. Campaign: 83 promotions, comprising 76 CODE and 7 RELECTURE. No new physical proof.


## HD_43 — four P1: e-mail, loyalty, cash supply and VAT letters (2026-10-22)

### Scope and operator benefit

LC-08-02-05 proposes the e-mail receipt at the end of a completed sale. LC-03-04-05 applies a card scanned during payment before the first settlement. LC-12-04-04 makes the cash-supply slip optional and identifies the operator. LC-02-10-03 prints alphabetic VAT codes on article rows and the matching breakdown. No promotion calculation is moved out of imvaluation.

### Settings and scenarios

Both catalogs declare ticket.email-offer (BOOL, false) and cash.deposit-print (BOOL, true). Existing country/brand/store/island/register inheritance and SETTINGS distribution apply. The existing ticket.vat-code setting controls the VAT letters; its help now documents the mapping. The single new paper.numeroOperateur key is translated in all three aligned FR/NL/PT bundles.

1. Enable ticket.email-offer in the actual store BackOffice, allow the normal referential cache renewal and pull SETTINGS. Complete a non-training sale. Finalization snapshots the loyalty address before clearing the current ticket, then opens /ticket-email only after successful closure. An open cash drawer does not hide this read-only proposal. The known-address editing policy is preserved. No message is transmitted without a separate operator action. Disable the setting and repeat: no automatic e-mail page. Tests also cover failed/incomplete closure, absent ticket ID, training mode and replay after closure.
2. Scan an article, enter payment with no settlement, then scan a loyalty card. The shared state monitor covers card attachment, total recalculation and the special valuation hook. Verify customerCode reaches imvaluation and the reconciler and loyalty projection receive its answer. Outside payment, the existing scan flow keeps its original single valuation. Test every refusal: training, completed transaction, existing payments, nonzero paid amount, pending card, cheque, signature, cheque-front print, suspended tender, pending credit and backup request. General valuation remains blocked during payment; only this pre-settlement card hook is admitted.
3. Enable cash.deposit-print and record an apport. The slip includes register, date, badge number, operator name, denomination detail, total and signature. Disable the setting: the movement is still recorded and neither text nor cut is emitted. Tests cover missing/blank legacy operator identity and actual badge propagation from the resource.
4. Enable ticket.vat-code and print or reprint a sale. VAT regime numbers map 0=A, 1=B, 25=Z, 26=AA and 27=AB, using a long intermediate to avoid overflow. The same letter appears on the article and rate breakdown. Missing rate/regime, absent number and negative number produce no invented identity. Disable the setting: article codes and breakdown letters disappear. Tests cover every mapping and absent-value branch.

### Actual service recipe and limits

Dev builds of imvaluation (18090), imedge (18070) and impos (18080) run on dedicated H2 in-memory databases recreated at startup, using loopback simulated hardware. After BackOffice distribution, a milk basket at 2.76 EUR enters payment; card 2990000000019 appears as customerCode in the real engine request log. A simulated cash settlement closes the sale and opens the e-mail proposal while the drawer is open. The actual page was visually checked at 1280 x 720. No SMTP relay is configured and no e-mail or SMS is sent. The external imfid service is not qualified here; the projection hook and resulting state are covered by unit tests.

The simulated reprint contains CODE TVA B and TVA B 20.00% for the milk. An apport of 10 EUR prints POS01, badge 11111111, Marie Curie and the amount. All three flags are then disabled in the real BackOffice. After normal cache renewal and a fresh pull, an apport remains recorded (ok=1) with an empty printer buffer. The next sale returns to the normal home route after drawer closure; its reprint contains no article VAT code or breakdown letter. The drawer guard on the normal home route remains active. The first sale did not request a paper copy, so its initial empty printer buffer was checked via the existing last-ticket reprint rather than by changing the printing policy.

All owned dev processes are stopped after verification. No physical hardware, production build or real terminal settlement is claimed. Existing offer-feed demo date warnings and the unavailable external imfid service are not hidden or counted as new integration proof.

### Tests, corrections and cotation

Complete offline suites: impos 6,874 Java + 94 JavaScript; imedge 4,152 Java + 11 JavaScript. No failures, errors or skipped Java tests. Fresh JaCoCo data: impos 25,315/25,315 lines and 12,687/12,687 branches; imedge 20,064/20,064 lines and 9,680/9,680 branches. All missed instruction, complexity, method and class counters are zero. External instrumented JavaScript remains at 100%; embedded scripts are not all instrumented.

The code needed the four missing behaviors and the drawer exception for the proposal. New test fixtures initially expected cardNumber instead of the contractual customerCode (cardNumber is conditional on card promotions), and treated a relay configuration read as a transmission; those expectations were corrected to assert the actual contract and absence of send. Required valuation mocks, printer signatures and exact catalog entries were updated without disabling tests or weakening their behavioral assertions.

Questionnaire edits are restricted to L115/O115, L198/O198, L463/O463 and L639/O639 and their four row heights. All other cell values and archive components remain unchanged. The PDF updates summary/coverage/tests and adds page 16; earlier proof and lot pages are preserved.

P1: 380/477 full (79.7%), 22/477 partial (4.6%), 75 not covered. Full or partial: 402/477 (84.3%). Ligne de caisses: 501 full, 53 partial, 176 not covered; weighted coverage 72.3%. Campaign: 87 promotions, comprising 80 CODE and 7 RELECTURE. No new physical proof.

## HD_43 — configurable training keys, LC-13-05-04 (2026-10-22)

### Benefit and configuration

The store chooses which gestures trainees can use while retaining the normal screen layout. A refused key remains visible and opens a translated, touch-sized acknowledgement; closing it restores focus and keeps the current screen.

Both settings catalogs add training.blocked-keys in MODE ÉCOLE, TEXT, default empty. Existing five-level inheritance and SETTINGS distribution apply. The value has one rule per line, not semicolon-separated values, so an onclick identity containing semicolons remains intact:

- path:/pay selects that exact route; path:/manual selects direct entry.
- path:/action/discount/* selects the root and its slash-delimited descendants, not a similarly named sibling.
- key: followed by an identity from the existing touch-usage report selects a particular configured key, DOM id, handler with arguments, submitted choice or link. Example: key:/:/article-info.

The path rules protect the designated HTTP routes and their corresponding visible navigation/submit controls. Key rules select local controls in the browser; they do not purport to authorize or deny an unrelated direct HTTP route. Administrators must name the action routes they want refused, including mutation routes when relevant. Blank lines are discarded; unrelated rule prefixes do not block routes. Empty configuration adds no restrictions. Existing hard-coded safeguards against real sales, customer messages and cash movements in training remain in force; this parameter cannot enable those operations.

### Enforcement and cases

TouchUsageWriter emits the policy JSON and its deferred capture script only in training operator documents containing the terminal, before the usage observer. JSON less-than characters are escaped, including a literal closing script in a key identity. Normal and non-operator pages receive no training policy. The script and configuration are emitted together; no unreachable absent-configuration branch is retained.

Click capture prevents the blocked handler and navigation before they run. Keyboard form submission is also checked, including submitter overrides and a submit without an explicit button. The identity reads no amount, password or other entered field. The dialogue reuses its tactile OK acknowledgement and is centered over the unchanged layout. Its acknowledgement, the home error route and the /action/training exit cannot be blocked by the added policy.

TrainingCheckFilter runs after authentication/session guards and before resource execution. It normalizes paths with and without a leading slash, refuses configured routes with a 303 to the existing translated register error, and does nothing in ordinary mode. This applies before both GET and POST resource bodies. Exact and subtree rules are tested at root, child, sibling and nonmatching paths.

### Actual service recipe

Start dev builds of imedge on 18070 and impos on 18080, with dedicated H2 in-memory databases recreated at startup and hardware loopback. Sign into the BackOffice and set training.blocked-keys to path:/pay, path:/manual and key:/:/article-info on separate lines. Sign into the register as seeded Marie Curie and enter training without opening a cash session. Allow normal referential fingerprint cache renewal and replay the pull.

The actual rendered policy contains those three rules. In the browser at 1280 x 720, INFO ARTICLE remains visible; clicking it stays on the same page and shows FONCTION INDISPONIBLE EN FORMATION with OK. The acknowledgement restores the unchanged main screen. Direct HTTP requests to /pay and /manual are refused before the resource and return the same translated refusal on the main page.

Clear the setting in the real BackOffice. After normal cache renewal and replay, the policy rules are empty. /manual opens again; clicking INFO ARTICLE reaches the actual read-only information screen. Exiting training returns to the normal session guard because no session was opened, and the normal document contains no training policy. No sale, cash movement, physical hardware or external message is performed. Owned demo processes are stopped after verification.

### Validation and cotation

Complete offline suites: impos 6,885 Java + 102 JavaScript; imedge 4,153 Java + 11 JavaScript. No failures, errors or skipped Java tests. Fresh JaCoCo: impos 25,346/25,346 lines and 12,715/12,715 branches; imedge 20,065/20,065 lines and 9,680/9,680 branches. All missed instruction, complexity, method and class counters are zero. External instrumented JavaScript is also at 100% of statements, branches, functions and lines. The final centering-only style adjustment was followed by the JavaScript suite and a fresh browser screenshot; no Java source changed after the complete suites.

Two new fixture mistakes were corrected: the state field is transientError, and jsdom needs a test-only implementation of native dialog.showModal/close. The code was corrected for centered dialog placement after visual inspection. No tests were disabled and no behavioral assertions were weakened. The exact BackOffice catalog fixture includes the new entry; both settings accessors test absent and stored rules. One new acknowledgement key is translated in FR/NL/PT.

Questionnaire edits are restricted to L701/O701 and its row height. All other cell values and archive components are preserved. The PDF updates summary/coverage/tests and adds page 17, preserving prior proof and lot pages.

P1: 381/477 full (79.9%), 21/477 partial (4.4%), 75 not covered. Full or partial: 402/477 (84.3%). Ligne de caisses: 502 full, 52 partial, 176 not covered; weighted coverage 72.3%. Campaign: 88 promotions, comprising 81 CODE and 7 RELECTURE. No new physical proof.

## HD_43 — sale NIF and OK acknowledgement (2026-10-22)

### Scope and benefit

LC-08-06-02 and LC-08-06-03 now apply to the ordinary sale itself. NIF CLIENT in the customer/loyalty menu opens a tactile mask without creating a customer record or issuing an invoice. The training acknowledgement changes from the previous wording to OK in FR/NL/PT; its behavior and centered layout stay the same.

The existing customer.default-tax-id setting proposes 999999990 by default, with the existing five-level inheritance and SETTINGS distribution. The mask reads that value only when no sale choice exists. An explicitly cleared choice remains empty. GET and cancellation write nothing. The actual shared keyboard initializes from the hidden field, displays the offered value and replaces the prefilled value on the first typed character. The operator may accept, replace or clear it.

### Persistence, boundaries and output

The optional customerTaxId belongs to TicketState and to the persisted Ticket in impos and imedge. Ordinary draft synchronization writes it; local parked-ticket/startup recovery restores it. Ticket clearing removes it so the next customer cannot inherit it. Both shared TicketDto contracts and outbox mappings include the field; store ingestion copies it. Unit tests carry a non-default value through serialization, ingestion, persistence and recovery. No remote cross-till cart-transfer extension is claimed by this change.

Saving uses the existing settlement monitor and is refused while payment is in progress or the transaction is complete. Training delegates to the existing draft funnel, which writes nothing. The 30-character identifier accepts letters, digits, spaces, dots, slashes and hyphens, uppercases using Locale.ROOT and rejects control characters/printer directive syntax. This adds no new country-specific tax validation algorithm.

The selected NIF is visible in the polled ticket fragment. The built-in receipt includes it. Administered sale data and sample previews expose document.taxId and document.taxIdLabel, and the default sale template includes the printable value. When the choice equals the administered anonymous NIF, customer.default-tax-id-label is printed; a missing or blank label falls back to the number. With no choice, no NIF is invented or printed. Customer-account records and invoice snapshots remain independent.

### Actual service recipe

Run dev builds of imedge (18070) and impos (18080), dedicated H2 in-memory databases recreated at startup and loopback simulated hardware. Sign in as Marie Curie, open a session and open NIF CLIENT. At 1280 x 720, the actual mask shows 999999990. Click the numeric keys to replace it with 123456789 and validate; the ordinary sale shows the chosen NIF without entering invoicing.

Scan milk, park the ticket and resume it. Reopening the mask still shows 123456789. Complete the simulated cash sale and reprint: the receipt contains the selected NIF. Open the mask for the next sale: it proposes 999999990 again. Accept it, sell milk and complete: the receipt contains CONSUMIDOR FINAL. On a third sale explicitly clear the choice and complete: the receipt has no selected or anonymous NIF. These receipts are verified in the simulator; no physical printer or terminal is used.

Set training.blocked-keys=path:/manual at the actual BackOffice and distribute after normal cache renewal. Enter training and click SAISIE DIRECTE: the refusal dialogue shows OK. Acknowledge it and leave training. The new NIF entry is also visible in the existing GÉNÉROSITÉ menu. No external message or production build is involved. Owned demo processes are stopped after verification.

### Tests and cotation

Complete offline suites: impos 6,893 Java + 105 JavaScript; imedge 4,153 Java + 11 JavaScript. No failures, errors or skipped Java tests. Fresh JaCoCo: impos 25,376/25,376 lines and 12,741/12,741 branches; imedge 20,067/20,067 lines and 9,680/9,680 branches. All missed instruction, complexity, method and class counters are zero. External instrumented JavaScript remains at 100% of statements, branches, functions and lines; embedded scripts are not all instrumented.

Tests cover inherited/default/null/blank prefill, a chosen and explicitly empty value, numeric/foreign identifiers, clearing and next-sale reset, unsafe/overlong input, both payment refusal legs, saving under the state monitor, actual Qute escaping and actual tactile initialization/clicks. Existing persistence/recovery/sync assertions are extended to preserve the field. Printer tests cover absent, empty, explicit and anonymous identifiers and missing/blank wording. One new JS fixture incorrectly searched textContent for a keyboard key rendered with innerText; the fixture now uses the actual rendering contract, retaining the click and resulting value assertions. No test is disabled or weakened.

The questionnaire changes only L525/O525 and L526/O526 and their two row heights. Other cell values and archive components are preserved. The PDF updates summary/coverage/tests, changes the training acknowledgement wording on page 17 and appends page 18; other prior proof and lot pages are preserved.

P1: 383/477 full (80.3%), 19/477 partial (4.0%), 75 not covered. Full or partial: 402/477 (84.3%). Ligne de caisses: 504 full, 50 partial, 176 not covered; weighted coverage 72.5%. Campaign: 90 promotions, comprising 83 CODE and 7 RELECTURE. No new physical proof.

## HD_43 — ticket code and generic function approval (2026-10-22)

### LC-08-01-03: configurable 1D/2D identification

Both catalogs declare ticket.barcode-enabled (BOOL, true) and ticket.barcode-format (TEXT, CODE128). The BackOffice presents CODE128 and QR as closed choices; a legacy unknown format falls back to CODE128. Existing five-level inheritance and SETTINGS distribution apply. The same persisted unique number, including the existing administered prefix, is encoded in either format.

The selected identification directive is last in the receipt, before cutting. Administered layouts follow the same activation and format policy as the built-in receipt: matching old 1D/QR identification directives are removed before appending the selected one, so a custom template cannot accidentally duplicate the identifier or bypass suppression. Administered data exposes document.identificationCode. Other document barcodes and the website/footer URL keep their own policies.

The missing hardware link is implemented in impos-hw: ReceiptBody parses QR directives, HardwareEndpoints dispatches them and ReceiptPrinter/EscPosPrinter emit native model-2 QR commands. Epson's primary reference specifies [QR command functions](https://download4.epson.biz/sec_pubs/pos/reference_en/escpos/gs_lparen_lk.html), including [function 180 data storage](https://download4.epson.biz/sec_pubs/pos/reference_en/escpos/gs_lparen_lk_fn180.html) and [function 181 printing](https://download4.epson.biz/sec_pubs/pos/reference_en/escpos/gs_lparen_lk_fn181.html). Tests assert exact command bytes, model, module size, correction level, UTF-8 payload and low/high byte length. Empty/overlong payloads fail explicitly; malformed directives stay visible text. These are file-transport tests, not physical QR qualification.

### LC-01-05-01: approval policy and continuation

Both catalogs add gesture.function-endorsements, TEXT, default empty. One method/path rule per line selects an operator function, for example POST /sale-tax-id=REQUIRED, GET /manual=REQUIRED or GET /action/training=NONE. REQUIRED adds the existing supervisor ceremony. NONE exempts the selected function's ordinary approval checks, including checks previously fixed in code. No matching rule preserves existing behavior. The last matching rule wins; /* explicitly includes a path and its descendants, with a slash boundary. Unknown modes do not overwrite a valid matching rule.

The generic gate covers operator GET navigation and urlencoded POST forms. It does not turn machine APIs, authentication, approval endpoints, polls or static assets into supervisor gestures. A protected GET cannot be bypassed with HEAD (405), and trailing-slash variants match the same rule. Other methods are outside this gate; an unsupported POST media type returns 415 and an oversized captured form returns 413, rather than executing without approval. Form names, repeated values, query arguments and original values are retained in memory, without logging field values or borrowing the manager PIN for the replay.

The first request is stopped before its resource and opens the existing translated modal. Valid credentials, remote approval and the existing PER_OP/PER_TICKET convention stay in the existing endorsement mechanism. A one-use grant is bound to method, local target/query, original form, operator, ticket, cart content, training mode and settlements. It cannot execute a different form, act on a changed sale or silently authorize a replay. The browser resumes the original GET or submits the escaped frozen POST using the ordinary redirect flow. A reused token creates a new approval request; it does not execute the resource again.

The approved or exempt context is request-scoped and reached through methods so CDI resolves the correct request instance. It does not change the operator role. Existing hard business blocks remain. The unique sold article still requires its configured reason and a real fresh supervisor approval: an ordinary NONE exemption does not waive that requirement; an exact approved function does not ask for a second identical approval once the reason is present.

### Actual service recipe

Start dev builds of imedge on 18070 and impos on 18080, dedicated H2 in-memory databases recreated at startup and loopback simulated hardware. Stage QR plus POST NIF/GET manual REQUIRED and training NONE in the real BackOffice before starting the register. Sign into the register as seeded cashier Jean Dupont, open a session and scan milk.

Post NIF 123456789. The request redirects to the pending modal; reopening the mask still offers the default, proving no mutation occurred. A wrong manager PIN is refused. A correct Marie Curie approval returns the frozen continuation. Opening that URL in the actual browser automatically submits the form and shows the chosen NIF on the ordinary sale. At 1280 x 720, SAISIE DIRECTE then asks for its own approval, proving the previous request did not leave a reusable supervisor bypass. Approve it: the original GET opens. Reusing its URL/token returns to a new modal, without re-executing the function. Cancel that new demand.

Complete the simulated cash sale and reprint: POS01-00000001 ends with its QR directive before the simulator's cut marker. Set CODE128 and replace the function rules with NONE. After normal referential cache renewal, a manager triggers the pull; the cashier cannot invoke that supervisory endpoint (403 is the existing role boundary). Return to the cashier: NIF and manual entry need no modal, and the second sale prints CODE128 for POS01-00000002. The cashier enters and leaves training without endorsement under its explicit NONE rule, while retaining the CASHIER role.

Disable ticket.barcode-enabled in the BackOffice. After cache renewal and a manager-triggered pull, return to the cashier, close the drawer opened by the login/override flow and reprint. The receipt remains nonempty and keeps its plain ticket number, with neither identification directive. QR, 1D and disabled content are checked independently of the simulator's cut decoration. No physical printer, terminal, external message or production build is used. All owned demo processes are stopped.

### Validation and fixture corrections

Complete offline suites: impos 6,908 Java + 105 JavaScript; imedge 4,154 Java + 11 JavaScript; impos-hw 69 Java. No failures, errors or skipped Java tests. Fresh JaCoCo: impos 25,510/25,510 lines and 12,877/12,877 branches; imedge 20,071/20,071 lines and 9,680/9,680 branches. Every missed instruction, complexity, method and class counter is zero. External instrumented JavaScript remains at 100%; embedded scripts are not all instrumented. No blanket coverage claim is made for impos-hw.

Tests cover exact/subtree/overridden/unknown rules, trailing-slash variants, refused HEAD execution, absent and stale pending requests, wrong method/target/body/token, repeated fields, query preservation, one-use consumption, supervisor shortcuts, every infrastructure exclusion leg, explicit NONE, request-only context, legacy endorsement exemption, actual approved unique-article cancellation with reason, continuation rendering and Qute escaping. Hardware tests cover mixed text/1D/QR parsing and actual endpoint-to-driver bytes on a disposable file.

The new catalog fixture initially classified barcode-enabled as text; it now checks BOOL. Historical printer mocks omitted its new true default; the golden-reference fixture now mirrors the live catalog and all four old receipt references remain unchanged. Two exact administered-layout expectations intentionally add the required final code while retaining all original content assertions. The initial recipe incorrectly counted the simulator's cut marker as receipt text, and a cashier pull attempt correctly returned 403; both recipe steps were corrected without changing product code or relaxing assertions. The hardware test uses an explicit exact expected byte sequence. No tests are disabled.

Questionnaire changes are restricted to L455/O455 and L32/O32 and their two row heights. Other cell values and archive components are preserved. The PDF updates summary/coverage/tests and adds pages 19–20; previous proof and lot pages remain unchanged.

P1: 385/477 full (80.7%), 17/477 partial (3.6%), 75 not covered. Full or partial: 402/477 (84.3%). Ligne de caisses: 506 full, 48 partial, 176 not covered; weighted coverage 72.6%. Campaign: 92 promotions, comprising 85 CODE and 7 RELECTURE. No new physical proof.

## HD_43 — Responsive customer display (22 October 2026)

LC-10-01-01 is promoted by CODE. The gain is a readable ticket, total and payment summary on small and widescreen customer displays. Only the customer template's layout changes; no business state, message key, setting or hardware driver changes.

### Recipe

Start impos in the dev build profile with a disposable H2 memory database, drop-and-create and simulated hardware. Render the actual `/customer` Qute page. In a local recipe harness, serve that HTML and its original embedded script in exact-size browser frames, feeding controlled customer-data payloads. Repeat at 800x600, 1024x768 and 1366x768 (16:9):

1. Welcome, with training and display-message banners present together.
2. Running sale containing 30 articles, long labels, EAN, weighted quantities, promotions and manual advantages.
3. Partial payment with bilingual labels, two tenders, remaining amount and cash rounding; include a long loyalty-holder name.
4. Thank-you with both payments, change, a digital path and a square image fixture in the QR slot. This tests image proportions and layout, not QR decoding.

Expected: no horizontal page overflow; the active screen fits between the banners; neither banner covers the header or footer. Header and footer wrap as necessary. The article list has useful space and scrolls; payments remain above the footer; the full total stays visible. Welcome and thank-you content remain accessible by vertical scrolling if necessary. The QR slot stays square without stretching. Inspect the screenshots at each size; reduce only the harness preview scale when needed, preserving the iframe's actual viewport dimensions.

### Validation and cotation

All twelve browser geometry cases pass and screenshots of the three payment layouts are inspected, plus the 800x600 thank-you layout. The full offline impos suite passes: 6,908 Java and 105 JavaScript tests, no failures/errors/skipped Java tests. JaCoCo remains 25,510/25,510 lines and 12,877/12,877 branches, every missed counter zero. External instrumented JavaScript remains at 100%; embedded scripts are not all instrumented. No Java or embedded JavaScript code changes in this lot. No test assertion is removed or weakened. Imedge and impos-hw source code is unchanged; their previous full-suite evidence remains recorded above.

Workbook changes are limited to L558/O558 and that row's height. All other cell values and ZIP components are preserved. PDF summary pages 1–2 are refreshed and page 21 records the responsive proof; pages 3–20 remain identical. No physical customer screen is qualified, no production build or external transmission is used. Owned demo processes are stopped after the recipe.

P1: 386/477 full (80.9%), 16/477 partial (3.4%), 75 not covered. Full or partial: 402/477 (84.3%). Ligne de caisses: 507 full, 47 partial, 176 not covered; weighted coverage 72.7%. Campaign: 93 promotions, comprising 86 CODE and 7 RELECTURE. No new physical proof.

## HD_43 — Other customer documents by e-mail (22 October 2026)

LC-08-02-06 is promoted by CODE. The gain is one tactile selection/preview/delivery screen for issued customer documents, avoiding paper reprints. The existing sale-receipt e-mail flow remains available.

### Issuance and customer-only boundary

CustomerDocument stores the family, issue reference, exact rendered customer copy, issue time and last confirmed destination. CustomerDocumentService persists the copy within a transaction and pages history twenty rows at a time, newest first. Only explicit customer issuance sites call the archive: return, card customer copy, voucher/gift card, credit voucher, deposit, loyalty, exit, resume, collection, exchange, parked receipt, payment cancellation and invoice/delivery note. Administered and built-in receipt paths both freeze their rendered output. Invoice archival precedes roll/slip/A4 routing.

There is no global printer interception. Merchant card frames, unclassified additional frames, X/Z reports and technical/store documents never enter this archive. For monetic sale/refund frames, only the explicitly identified customerReceiptFrame is archived; merchant and additional frames keep their existing paper path. Unit assertions verify the customer frame is archived and the merchant frame is not. The unclassified raw printer entry point does not archive anything.

The /customer-documents screen is linked from the ticket menu and existing e-mail screen. It shows family, reference, date, escaped preview, history navigation, a required document radio selection and one existing tactile e-mail keyboard. The POST accepts an archive identifier and address, never caller-supplied document content or type. Unknown/missing identifiers and malformed addresses are refused. Training blocks both history reads and delivery. Documents issued before this archive existed are not automatically reconstructed; the existing sale path retains its frozen/rendered fallback.

TicketMailService shares transport/MIME assembly with the old sale path, keeping the existing BODY/ATTACHMENT/BOTH setting. A true SMTP acceptance, an unavailable configured relay and an unconfigured relay have distinct translated outcomes. The request is journalled in all delivery paths. The old now-unused forwarding MIME builder is removed. Twenty-three new message keys are aligned across FR/NL/PT. No new setting or change to imedge/impos-hw source code is needed.

### Local runtime recipe

Build with the dev construction profile, then run with dev configuration, a disposable H2 memory database with drop-and-create and the simulated hardware endpoints. Point quarkus.rest-client.hardware-api.url and pos.scanner.sink-url at this owned loopback instance (port 18080). Use a loopback-only SMTP collector on 127.0.0.1:18025; no external address or mail service is contacted.

1. Sign in with the seeded manager, accept simulated hardware, close the simulated drawer and open the session.
2. Make a cash sale, recall it and return a manually selected 1.00 amount to a voucher. The issued credit and return copies appear separately.
3. Create an order with a 0.50 deposit. Its deposit receipt appears under its order reference.
4. Make a sale paid on the virtual card terminal, accept it in the simulator, finish and request the card duplicate. Its customer card copy appears under POS01-00000003; the order had consumed sequence number 2.
5. In the browser choose the return, expand its preview, type demo@example.test entirely with the tactile keyboard and submit. The page reports SMTP acceptance. Send the credit, deposit and card copy through the same endpoint.
6. Inspect all four captured MIME messages locally. The recipient is demo@example.test and each body exactly equals the selected archived preview after SMTP line-ending normalization: return, credit voucher, deposit and card duplicate. No merchant content occurs.
7. Request an absent archive identifier: refusal, no message. Open history page 1 after these four documents: empty history with backward navigation. Enter training and verify no selection and no send, then leave training.
8. Stop the local SMTP collector and submit again. The configured-relay failure is displayed, with no success message. No network destination outside loopback is used. Stop owned demo processes after verification.

### Tests, fixes and documentary proof

Full offline impos suite: 6,918 Java + 105 JavaScript tests pass, no failures, errors or skipped Java tests. JaCoCo: 25,601/25,601 lines and 12,903/12,903 branches, every missed counter zero. External instrumented JavaScript stays at 100%; embedded scripts are not all instrumented. Tests cover persisted family/reference/content/time, pagination and all family labels, selection/destination guards, training, actual delivery outcomes, MIME body and attachment content, recipient, frozen-copy use, Qute escaping and the invoice/customer-frame archive boundaries. Existing receipt expectations remain intact.

Two setup issues were corrected without weakening assertions: the code initially formed a dynamic family key that the bundle-source guard treated as an absent prefix; explicit family label keys now resolve the real bundles. The new printer test initially omitted its Panache stand-in; the fixture now supplies it. The first card recipe expectation named sale 2, but order creation legitimately consumed it: the recipe now checks sale 3. No test is disabled.

Questionnaire changes are restricted to L464/O464 and that row height. All other cell values and ZIP components are verified unchanged. PDF summary pages 1–2 are refreshed and page 22 records the lot; previous proof pages 3–21 are preserved. No production build, physical hardware action or new physical qualification is claimed.

P1: 387/477 full (81.1%), 15/477 partial (3.1%), 75 not covered. Full or partial: 402/477 (84.3%). Ligne de caisses: 508 full, 46 partial, 176 not covered; weighted coverage 72.7%. Campaign: 94 promotions, comprising 87 CODE and 7 RELECTURE.

## HD_43 — Forced return, credit and deposit paper (22 October 2026)

LC-08-03-07 is promoted by CODE. The gain is BackOffice control over mandatory paper in return/credit/deposit flows, consistent with sale printing. This lot is restricted to those document families, their setting descriptions, tests and cotation.

### Policy and electronic-copy preservation

The existing print.forced-documents list gains RETOUR and ACOMPTE; AVOIR also governs the voucher issued by a refund. Both impos and imedge catalogs describe the same extended list. No new setting is introduced: the existing five-level inheritance and SETTINGS payload carry these values unchanged.

With print.conditional-enabled off, legacy printing remains. With it on, each associated family prints only when its own key is present. The shared printer helper archives the customer copy first, then decides paper and cut. Suppression means no print and no cut, while e-mail selection still finds the issued copy. Deposit methods return 0 when paper is suppressed and 1 when printed. Both deposit render paths, built-in and administered return/deposit layouts, credit vouchers and the return-without-immediate-cut variant retain their contracts. Other card/technical-document rules are unchanged.

### Automated validation

Complete offline suites pass: impos 6,919 Java + 105 JavaScript; imedge 4,154 Java + 11 JavaScript. No failures, errors or skipped Java tests. Fresh JaCoCo: impos 25,596/25,596 lines and 12,905/12,905 branches; imedge 20,071/20,071 lines and 9,680/9,680 branches. All missed instruction, complexity, method and class counters are zero. External instrumented JavaScript remains at 100%; embedded scripts are not all instrumented.

The new printer test exercises ten combinations: conditional printing disabled, enabled with an empty list, and each of RETOUR/AVOIR/ACOMPTE forced individually, each with and without an administered layout. It counts exactly the expected receipt prints and cuts, checks both deposit return values and verifies all four electronic archives independently of paper. Existing no-policy fixtures retain historical behavior. Both catalog-parser tests now assert the extended names alongside TICKET/CARTE. No assertion is removed or weakened; no test is disabled.

### Runtime recipe through the real BackOffice

Use dev construction-profile builds of imedge on loopback port 18070 and impos on 18080, disposable H2 memory databases with drop-and-create and simulated hardware. Set the hardware and scanner client URLs back to the owned register instance and pos.sync.store-url to the owned store node. No physical hardware, production build or external SMTP is used.

1. Sign into imedge with its seeded admin. Set print.conditional-enabled=true and print.forced-documents=RETOUR;AVOIR;ACOMPTE before starting the register. Its initial SETTINGS pull applies both values.
2. Sign into impos with the seeded manager, override simulated hardware, close the drawer and open the session. Make the initial cash sale. The document-choice POST must be submitted after the drawer is closed: an initial attempt before closure correctly returned drawer-error; a later correctly ordered NONE submission is verified separately.
3. Clear the simulated printer. Recall sale 1, select a manual 1.00 return and refund to a voucher. The output contains both AVOIR and TICKET DE RETOUR. Clear, leave the return, register an article and create an order with a 0.50 deposit. The ACOMPTE paper appears. Three customer copies are available.
4. In imedge keep conditional printing enabled and clear the forced list. After the normal fingerprint-cache renewal, trigger /supervisor/health/feeds/replay as the manager. A new SETTINGS apply is recorded.
5. Clear the printer, make another 1.00 voucher return on sale 1, then another order/deposit. The printer content remains exactly empty after each operation. Customer history now contains six copies, including the suppressed-paper return, credit and deposit. An e-mail request for the newest is journalled with the explicit no-relay outcome, without sending externally.
6. In imedge disable conditional printing while leaving the forced list empty. After cache renewal and a manager pull, make a further 0.50 voucher return and a new 0.50 order deposit. The return/credit and deposit papers reappear, proving legacy behavior is restored.
7. Inspect the customer-copy screen and the actual archived return preview. Stop both owned demo processes. No listeners remain on their ports.

Only L477/O477 and the corresponding row height change in the questionnaire; every other cell and archive component is verified preserved. The PDF refreshes summary pages 1–2 and appends page 23; prior proof pages 3–22 remain unchanged. No new physical qualification is claimed.

P1: 388/477 full (81.3%), 14/477 partial (2.9%), 75 not covered. Full or partial: 402/477 (84.3%). Ligne de caisses: 509 full, 45 partial, 176 not covered; weighted coverage 72.8%. Campaign: 95 promotions, comprising 88 CODE and 7 RELECTURE.

Observation recorded during this lot: logo directives were visible as raw text in some customer previews/e-mail copies. This is resolved by the logo-rendering correction documented below.

## HD_43 — Logos in electronic previews and e-mail (22 October 2026)

This resolves the logo observation above without promoting another requirement. The gain is a visible logo instead of the IMAGE instruction and its packed raster in customer previews and e-mail. Scope is the logo rendering of existing frozen customer copies and the existing sale-mail path; paper content and stored raw copies are unchanged.

### Frozen-source rendering and fonts

CustomerReceiptRendering recognizes standalone IMAGE lines, interprets width/height and MSB-first packed rows, and produces PNGs in their original document positions. It never consults the current store image when rendering an older copy. All surrounding text is escaped before HTML emission; the customer document template only receives the renderer's trusted output. Invalid framing, base64, dimensions, packed lengths, excessive allocation or PNG encoding failure omit the logo while retaining readable text.

Existing fonts are retained: the interface uses the theme's UI family, and the receipt uses its existing Courier New / Courier / monospace document family. No font file, external font service or download is introduced. Text and logo are aligned in the preview and the electronic copy.

The existing BODY, ATTACHMENT and BOTH settings remain. A valid logo makes the body multipart/alternative: clean text plus HTML with related CID-linked PNG parts. Repeated identical logos get distinct local Content-IDs. A logo-bearing attachment is autonomous HTML with embedded PNG data; no external image fetch is needed. Without a logo, the old plain body and TXT attachment remain. Both catalogs describe this; there are no new settings or message keys. Other printer directives are outside this logo correction.

### Tests and runtime evidence

Complete offline suites: impos 6,926 Java + 105 JavaScript; imedge 4,154 Java + 11 JavaScript. No failures, errors or skipped Java tests. JaCoCo: impos 25,691/25,691 lines and 12,937/12,937 branches; imedge 20,071/20,071 lines and 9,680/9,680 branches. Every missed counter is zero. External instrumented JavaScript remains at 100%; embedded scripts are not all instrumented.

Tests verify exact black/white PNG pixels and dimensions, non-byte-aligned widths, multiple repeated logos and image positions, plain/empty text, malformed and excessive rasters, encoder errors, HTML escaping, the real Qute preview and the existing font family. All three mail formats are checked with captured MIME messages, including alternative text, CID references, inline PNG bytes, autonomous HTML attachments and clean-text fallback. Existing assertions and paper references are preserved.

Run dev construction-profile imedge/impos builds on owned loopback ports with disposable H2 memory/drop-and-create and simulated hardware. Use a loopback-only SMTP collector. In the BackOffice choose BOTH and distribute it through SETTINGS. Wait for the initial SETTINGS apply before the recipe: the first early MIME check had assumed BOTH while the register was still legitimately using its BODY default; the recipe now gates on the completed apply rather than treating a pull race as a product defect.

Create an order/deposit and open its actual customer preview. The logo is visible, with no IMAGE instruction or raw raster shown as text. Send that copy through /customer-documents. Make a separate cash sale and send it through the existing /ticket-email path. Inspect both messages at the local collector: clean text/HTML, valid CID-linked PNGs and autonomous HTML attachments. The deposit PNG bytes match the actual preview data URI and the image embedded in its attachment. Open the received HTML attachment in the browser and inspect it. No external destination, remote asset, font service, physical hardware or production build is used. Stop all owned demo processes afterwards.

Only O464 changes in the questionnaire; no rating changes. Other cells, row attributes and archive components are verified preserved. PDF summary pages are refreshed and page 24 records the correction; previous proof pages 3–23 remain unchanged.

P1 unchanged: 388/477 full (81.3%), 14/477 partial (2.9%), 75 not covered. Full or partial: 402/477 (84.3%). Ligne de caisses: 509 full, 45 partial, 176 not covered; weighted coverage 72.8%. No additional promotion: 95 campaign promotions, comprising 88 CODE and 7 RELECTURE.


## HD_43 — LC-10-01-07 : assiette titres-restaurant sur écran client

Gain : le client connaît le montant des articles éligibles avant de choisir son règlement.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé. Ouvrir une session et l’écran `/customer`. Déclarer un article éligible via `PRODUCTS.ATTRIBUTES` (`MEAL_VOUCHER_ELIGIBLE=true`) et conserver un autre article non éligible. Attendre l’application du référentiel avant les scans.

1. Scanner l’article éligible : « Articles éligibles aux titres-restaurant » apparaît avec son montant. Scanner l’autre article : le total du ticket augmente, l’assiette reste identique.
2. Scanner à nouveau le premier article après le second : son ancienne ligne peut être fusionnée. L’assiette double sans inclure l’article non éligible. Vérifier également une saisie manuelle, un GS1, une étiquette pesée et une résolution externe d’article.
3. Appliquer une remise de ligne, puis une remise ticket : l’assiette suit les montants valorisés et la ventilation de la remise. Annuler la ligne éligible : l’assiette revient à zéro. Ajouter à nouveau un article éligible : elle est rétablie.
4. Passer au paiement puis enregistrer un règlement partiel : l’assiette reste visible et ne diminue pas du règlement. Il s’agit du total des articles éligibles, distinct du plafond de règlement et du reste à payer.
5. Avec une réponse imvaluation exploitable, son assiette est prioritaire. Sans réponse moteur ou sans assiette dans la réponse, utiliser les lignes locales. Une assiette moteur négative est présentée à zéro. Vérifier les arrondis au centime et les libellés FR/NL/PT, y compris en affichage bilingue.
6. Vérifier la lisibilité avec les polices existantes, le total et le reste à payer, y compris sur 800 × 600. Les articles peuvent défiler sans faire disparaître l’assiette. Une transaction terminée ou une caisse vide reprend son écran habituel.

Recette exécutée le 22/10/2026 avec imedge et impos réels, imvaluation indisponible (repli local) : lait 2,76 €, autre article 0,96 €, second lait fusionné = assiette 5,52 €, remise ticket de 10 % = 4,97 €, annulation = 0,00 €. Après nouvel ajout, assiette 2,49 €, conservée pendant un paiement espèces partiel de 1,00 € (total 3,35 €, reste 2,35 €). Écran réel inspecté au paiement, également en 800 × 600. Priorité et cas limites moteur, remises et annulation : tests unitaires ; GS1, étiquettes et résolution externe : tests des handlers. Aucun essai moteur réel ni matériel physique pour ce lot.

Validation : impos 6 930 tests Java + 106 JavaScript ; imedge 4 154 Java + 11 JavaScript, sans échec. JaCoCo : impos 25 708 lignes et 12 945 branches, imedge 20 071 lignes et 9 680 branches, tous les compteurs manqués à zéro. JavaScript externe instrumenté à 100 %. Les attentes de taille JSON intègrent le champ ajouté ; les doubles de ticket scanner simulent l’enregistrement attendu.

Cotation : LC-10-01-07 promue par CODE de non couverte à intégralement couverte. P1 : 389/477 intégrales (81,6 %), 14 partielles (2,9 %), soit 403/477 intégrales ou partielles (84,5 %). Campagne : 96 promotions, dont 89 CODE et 7 RELECTURE.


## HD_43 — LC-02-03-11 : messages article bloquants

Gain : une consigne obligatoire refuse l’enregistrement de l’article et doit être lue avant de poursuivre la vente.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé. Sur deux fiches dans `/admin/articles/edit`, renseigner le message article ; cocher `INFO_MESSAGE_BLOCKING` seulement pour la première. Attendre la distribution des produits à la caisse. Les attributs texte et décimaux existants doivent être conservés lors de la sauvegarde de cette case.

1. Saisir l’article informatif : il est enregistré et son message apparaît sans empêcher la poursuite de la vente. Un message vide, blanc ou sans mode bloquant ne bloque pas la caisse.
2. Scanner l’article bloquant : vérifier l’ouverture automatique de la page « MESSAGE ARTICLE BLOQUANT », le texte exact et la mention que l’article n’a pas été enregistré. Aucun changement de quantité, ligne, total ou règlement ne doit en résulter.
3. Pendant l’attente, scanner un autre article : réponse HTTP 409, aucun ajout. Les accès directs au paiement, à la saisie et aux remises reviennent au message. Les états du matériel, l’affichage client et le verrouillage restent disponibles. La suppression des messages informatifs d’îlot ne neutralise pas un refus bloquant.
4. Cliquer « J’AI LU LE MESSAGE » : retour à la vente, ticket inchangé. L’article refusé n’est ni ajouté ni rejoué. Un nouvel essai de saisie du même article doit produire un nouvel acquittement ; le formulaire précédent ne peut pas le valider.
5. Répéter par EAN saisi, code interne, PLU, groupe d’articles, entrée guidée, GS1, étiquette pesée et résolution externe. Vérifier aussi les articles des commandes rappelées et des tickets comptoir : le contrôle des messages précède le chargement de leurs lignes, sans chargement partiel.
6. Modifier seulement le texte par le flux `PRODUCTS.INFO_MESSAGE`, sans changer l’identité de l’article : le nouveau texte doit être appliqué. Vérifier également son effacement explicite et la conservation du texte si la colonne est absente. Le mode transite dans `ATTRIBUTES` et les snapshots JSON.
7. Vérifier le texte échappé, les libellés FR/NL/PT, les polices existantes et la lisibilité en 800 × 600. Les validations périmées ou absentes ne doivent pas acquitter un autre message.

Recette exécutée le 22/10/2026 sur imedge et impos réels, bases jetables et simulateur : deux fiches configurées puis distribuées par le snapshot. La baguette avec message informatif est enregistrée (0,96 €). Le lait avec message bloquant est refusé : le ticket reste à 0,96 €. Le navigateur passe automatiquement sur la consigne. Scan suivant refusé, paiement/saisie/remise directs bloqués, validation périmée sans effet, puis acquittement réel au navigateur sans ajout. Un second scan du lait nécessite un nouvel identifiant d’acquittement. Rendu inspecté en résolution normale et 800 × 600. Autres voies d’admission et flux CSV vérifiés par tests unitaires. Aucun essai matériel physique ni échange imvaluation réussi pour ce lot.

Validation : impos 6 948 tests Java + 106 JavaScript ; imedge 4 155 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 25 768 lignes et 13 007 branches, imedge 20 074 lignes et 9 686 branches ; tous les compteurs manqués sont à zéro. Le JavaScript externe instrumenté reste à 100 %. Les attentes de taille des catalogues intègrent le nouvel attribut et vérifient sa valeur par défaut ; aucune assertion n’est retirée. La sauvegarde BackOffice est corrigée pour préserver les attributs non booléens, testés avec un numéro de lot et une éco-taxe.

Cotation : LC-02-03-11 promue par CODE de non couverte à intégralement couverte. Le statut source « Supprimée » en K70 est conservé ; seuls L70, O70 et la hauteur de cette ligne changent. P1 : 390/477 intégrales (81,8 %), 14 partielles (2,9 %), soit 404/477 intégrales ou partielles (84,7 %). Campagne : 97 promotions, dont 90 CODE et 7 RELECTURE.


## HD_43 — LC-02-03-04 : article lié automatiquement

Gain : les consignes suivent les bouteilles lors des ajouts, corrections et annulations, sans correction manuelle du ticket.

Préconditions : construction dev, H2 mémoire jetable et matériel simulé. Sur la fiche article BackOffice de la bouteille, saisir l’EAN de la consigne dans `LINKED_ARTICLE_EAN`. Attendre la distribution du référentiel à la caisse. Le champ vide désactive l’association.

1. Scanner une bouteille : une bouteille et une consigne sont enregistrées. Répéter deux fois : trois bouteilles et trois consignes. Modifier le nombre de bouteilles : les consignes suivent ; la valorisation porte sur le ticket complet.
2. Tenter de changer, décrémenter ou annuler seulement la consigne automatique : refus demandant d’agir sur l’article principal. Une consigne saisie séparément reste une ligne indépendante, sans fusion avec celle de la bouteille.
3. Mettre le ticket en attente puis le reprendre : retrouver les mêmes liens et quantités. Modifier à nouveau le nombre de bouteilles : la consigne existante est ajustée, sans doublon. Vérifier aussi la récupération du brouillon après redémarrage et les champs échangés avec le magasin.
4. Annuler la bouteille par le parcours autorisé : sa consigne et les éventuels descendants sont retirés avec le même opérateur et motif dans les témoins du journal. L’article de même EAN saisi indépendamment reste présent.
5. En retour, la consigne porte le même signe et le même nombre que la bouteille. Le mode « à enlever » est conservé. Les associations en chaîne restent séparées pour chaque principal.
6. Refuser un EAN lié absent/inactif ou une boucle au BackOffice avant toute modification de fiche. Une boucle issue d’un autre import est également refusée en caisse. Si la consigne ne peut pas être admise, aucune nouvelle bouteille seule n’est conservée : retirer l’ajout ou restaurer la dernière quantité acceptée. Les demandes de saisie différée ne doivent pas créer de consigne orpheline.
7. Vérifier les libellés FR/NL/PT et les polices existantes. Les parcours EAN, GS1, pesée, saisie guidée/manuelle, résolution externe et rappels capturent l’association avant la valorisation.

Recette exécutée le 22/10/2026 sur imedge et impos réels : association temporaire entre lait et baguette du jeu de démonstration. Réglage sauvegardé au BackOffice et distribué par le référentiel ; association inverse en boucle refusée. Scan : 1/1 ; répétition : 2/2 ; correction : 3/3 ; correction indépendante refusée. Ajout séparé de la baguette : ligne indépendante. Mise en attente/reprise, correction à 2/2 puis annulation du lait : les deux lignes liées disparaissent et la baguette indépendante reste seule. Répétition contrôlée également au navigateur jusqu’à 3/3. Autres admissions, retours, refus/rollback, témoins et synchronisation vérifiés par tests unitaires. Imvaluation indisponible : valorisation locale de secours. Aucun essai matériel physique.

Validation : impos 6 961 tests Java + 106 JavaScript ; imedge 4 157 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 25 893 lignes et 13 089 branches ; imedge 20 097 lignes et 9 698 branches ; tous les compteurs manqués à zéro. JavaScript instrumenté à 100 %. Les anciennes attentes de type du catalogue omettaient le nouvel attribut texte ; les mocks statiques de balance masquaient les valeurs par défaut réelles. Ces fixtures sont corrigées sans retrait d’assertion. Le nouveau contrôle ne recalcule pas les paniers sans association.

Cotation : LC-02-03-04 promue par CODE de non couverte à intégralement couverte. Seuls L63, O63 et la hauteur de la ligne sont modifiés ; les composants du classeur sont conservés. PDF : pages 1–2 actualisées, page 27 ajoutée, pages 3–26 préservées. P1 : 391/477 intégrales (82,0 %), 14 partielles (2,9 %), soit 405/477 intégrales ou partielles (84,9 %). Campagne : 98 promotions, dont 91 CODE et 7 RELECTURE.


## HD_43 — LC-02-07-18 : tri temporaire des articles

Gain : retrouver plus vite un article dans son groupe par libellé ou EAN, sans changer le réglage du magasin.

Préconditions : construction dev, H2 mémoire jetable et matériel simulé. Configurer un ordre BackOffice reconnaissable via `touch.article-order` et un nombre d’articles par page assez petit pour avoir plusieurs pages. Distribuer les paramètres et produits à la caisse. Ouvrir la saisie directe puis un groupe contenant plusieurs articles.

1. Vérifier que l’ordre BackOffice est initialement sélectionné. La racine des groupes ne propose pas de tri d’articles. Les sous-groupes, groupes permanents et retours conservent leur navigation.
2. Choisir « Libellé A à Z », « Libellé Z à A », « Code EAN croissant » puis « Code EAN décroissant », en validant avec TRIER. Chaque nouveau choix revient à la première page. En tri par code, les EAN sont lisibles sur les touches avec le libellé et le prix.
3. Parcourir toutes les pages : vérifier l’ordre global, sans doublon ni omission entre pages. Les liens précédent/suivant conservent le tri. L’ordre est appliqué dans la requête avant pagination ; le catalogue entier n’est pas chargé pour trier seulement la page visible.
4. Choisir « Ordre BackOffice » : les priorités du magasin reviennent immédiatement. Quitter le groupe, puis y revenir par la navigation normale : l’ordre BackOffice est à nouveau utilisé. Vérifier aussi après verrouillage/déverrouillage puis fermeture/reconnexion de l’opérateur.
5. Un choix de tri inconnu revient au défaut. Aucune option libre ne doit être interprétée comme un fragment SQL. Aucun paramètre ni état durable/session n’est écrit : seule la requête de consultation et ses liens de pagination portent le tri.
6. Vérifier les libellés FR/NL/PT, les polices existantes et le rendu normal puis 800 × 600 : choix de tri, grille, prix, EAN, pagination et pavé numérique complet accessibles.

Recette exécutée le 22/10/2026 sur imedge et impos réels, profil dev et bases jetables. Réglage temporaire BackOffice : lait puis œufs en tête, quatre articles par page. Groupe Épicerie sur six pages : les quatre tris sont parcourus entièrement, sans doublon ; les listes par nom et EAN sont comparées à leur ordre attendu. Les priorités initiales reviennent après sortie/réentrée, verrouillage et fermeture/reconnexion réelle. Au navigateur : sélection tactile du tri EAN décroissant, validation, page suivante avec tri conservé, retour puis nouvelle entrée avec ordre BackOffice. Rendus normal et 800 × 600 inspectés ; toutes les lignes du pavé restent visibles. Aucun matériel physique. La consultation de référentiel ne dépend pas d’imvaluation.

Validation : impos 6 966 tests Java + 106 JavaScript ; imedge 4 157 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 25 912 lignes et 13 102 branches ; imedge 20 097 lignes et 9 698 branches ; tous les compteurs manqués à zéro. JavaScript instrumenté à 100 %. Les tests couvrent les cinq modes et choix absents/inconnus, les requêtes avant pagination, le rendu des sélections et des EAN, et la restauration du défaut. Les fixtures existantes reçoivent le nouveau mode explicite ; aucune assertion n’est retirée.

Cotation : LC-02-07-18 promue par CODE de non couverte à intégralement couverte. K88 (« Ajoutée ») est conservé ; seuls L88, O88 et la hauteur de ligne changent dans le classeur, dont les composants sont préservés. PDF : pages 1–2 actualisées, page 28 ajoutée, pages 3–27 préservées. P1 : 392/477 intégrales (82,2 %), 14 partielles (2,9 %), soit 406/477 intégrales ou partielles (85,1 %). Campagne : 99 promotions, dont 92 CODE et 7 RELECTURE.


## HD_43 — LC-02-07-08 : EAN sur les touches articles

Gain : vérifier le code d’un article sur sa touche sans changer l’ordre de consultation.

Préconditions : construction dev, H2 mémoire jetable et matériel simulé. Le paramètre `touch.show-ean` apparaît dans TOUCHES CAISSE et dans les clés administrables par échelon. Il est indépendant de `display.show-ean` (autres écrans et ticket), désactivé par défaut, et hérité aux niveaux pays, enseigne, magasin, îlot et caisse. Ouvrir un groupe de saisie directe contenant plusieurs articles et pages.

1. Sans valeur configurée, vérifier que les EAN ne sont pas affichés. Le masquage vaut dans les cinq modes : ordre BackOffice, libellé croissant/décroissant et EAN croissant/décroissant.
2. Activer le paramètre au BackOffice. Attendre la publication du référentiel puis son application en caisse : les empreintes sont mises en cache pendant 60 secondes, auxquelles s’ajoute le délai de tirage configuré. Aucun redémarrage n’est nécessaire.
3. Dans chaque mode et sur plusieurs pages, vérifier le code exact sous le libellé, avec ses zéros initiaux. L’affichage ne change ni le libellé, ni le prix, ni l’ordre des touches, ni la sélection d’un article. Les codes absents ou vides ne produisent pas de zone vide. Les groupes et retours ne reçoivent pas de code article.
4. Désactiver le réglage puis attendre sa distribution : tous les modes masquent à nouveau les EAN, y compris les tris par code. Réactiver sans redémarrage. Les priorités d’échelon restent celles du mécanisme existant, avec la caisse plus précise que l’îlot, le magasin, l’enseigne et le pays.
5. Vérifier le rendu normal puis 800 × 600 avec les polices existantes : codes, libellés, prix, pagination et pavé numérique complet accessibles. Les touches restent sélectionnables.

Recette du 22/10/2026 sur imedge et impos réels en dev, bases jetables et matériel simulé : valeur par défaut masquée puis activation, désactivation et réactivation depuis la page BackOffice. Contrôle des cinq modes sur deux pages. Les codes affichés sont comparés aux EAN des touches ; les tuples code/libellé/prix et leur ordre restent identiques avant et après bascule. Le réglage est présent dans les pages de paramètres et d’échelons. Vérification au navigateur de l’ordre BackOffice puis du tri par libellé ; rendus normal et 800 × 600 inspectés. Délai normal des empreintes respecté, tirage accéléré à deux secondes seulement pour la recette. Aucun essai physique ; ce parcours ne dépend pas d’imvaluation.

Validation : impos 6 969 tests Java + 106 JavaScript ; imedge 4 159 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 25 915 lignes et 13 102 branches ; imedge 20 098 lignes et 9 698 branches, tous les compteurs manqués à zéro. JavaScript instrumenté à 100 %. Tests du défaut et des deux valeurs, de l’héritage aux cinq niveaux, du rendu dans chaque tri et des codes absents/vides/à zéro initial. Les fixtures des réglages invalident le cache après modification des données, comme l’application réelle ; les attentes de rendu suivent le nouveau choix BackOffice.

Cotation : seule LC-02-07-08 est promue par CODE de non couverte à intégralement couverte. Seuls L93, O93 et la hauteur de ligne changent dans le classeur ; les autres cellules et composants sont conservés. PDF : pages 1–2 actualisées, page 29 ajoutée, pages 3–28 préservées. P1 : 393/477 intégrales (82,4 %), 14 partielles (2,9 %), soit 407/477 intégrales ou partielles (85,3 %). Campagne : 100 promotions, dont 93 CODE et 7 RELECTURE.


## HD_43 — LC-02-12-05 : annulation depuis un groupe d’articles

Gain : retirer un article en retrouvant sa touche habituelle, sans nouveau scan ni recherche de ligne dans le ticket.

Préconditions : construction dev, H2 mémoire jetable et matériel simulé. Ouvrir une session caissier, enregistrer plusieurs articles dont une ligne unitaire multiple. Prévoir un compte superviseur distinct. Pour vérifier la règle utilisateur du dernier article vendu, distribuer une justification active pour la fonction `CANCEL_LINE`. Une bouteille et sa consigne peuvent être liées via `LINKED_ARTICLE_EAN`.

1. Appuyer sur ANNULER PAR GROUPE : le titre annonce ANNULER UN ARTICLE. Naviguer dans les groupes, les pages et les tris : le contexte d’annulation est conservé. Aucun article inconnu ni saisie de quantité n’est proposé. Retourner à la vente : aucun mode persistant d’annulation n’est laissé armé.
2. Sélectionner un article absent du ticket : refus, sans ajout ni aval. Sélectionner un article présent : aucune modification avant autorisation superviseur. La quantité présaisie ne doit pas devenir une quantité de vente ni élargir ce retrait d’une unité.
3. Présenter le code d’un simple caissier : autorisation refusée, ticket intact. Abandonner la demande : ticket intact. Présenter le code valide d’un superviseur : retrait d’une seule unité de la ligne unitaire multiple. Aucun motif ordinaire n’est demandé, même si une liste de motifs d’annulation de ligne est administrée.
4. La cible de l’aval est l’UID de la ligne désignée. Une cible disparue ne doit jamais être remplacée par une autre occurrence du même EAN. Les lignes pesées, à prix embarqué, monétaires et négatives gardent leur traitement d’objet entier.
5. Pour une bouteille liée, la consigne suit la nouvelle quantité et disparaît si la bouteille est retirée. Une consigne automatique seule est protégée. Si le même EAN a aussi été vendu indépendamment, la sélection privilégie cette vente indépendante, qui reste annulable.
6. Retirer le dernier article vendu : conserver la règle utilisateur précédemment décidée, avec motif obligatoire et aval frais. Sans liste de motifs, refuser cette annulation ; avec une liste, proposer les motifs avant l’aval. Un panier devenu unique pendant l’attente ne peut pas contourner cette protection avec un aval ancien.
7. Vérifier les libellés FR/NL/PT et les polices existantes. Inspecter le parcours normal et 800 × 600 : commande de tri, touches, consigne d’utilisation, retour et écran de vente avec son pavé complet accessibles.

Recette exécutée le 22/10/2026 sur imedge et impos réels : Jean Dupont (CASHIER) et Marie Curie (MANAGER) distincts. Deux laits, deux articles liés et un café sont au ticket. La sélection attend l’aval ; le code du caissier est refusé, le ticket reste intact. L’aval manager retire un lait et un article lié. Abandon et sélection d’un article absent sans effet ; consigne automatique protégée. Ajout séparé du même EAN puis annulation : seule la ligne indépendante disparaît. Nouvelle sélection de la bouteille : sa famille disparaît après aval. Le café restant demande le motif ERROR puis un nouvel aval avant suppression. Le parcours tactile est également exécuté au navigateur : ANNULER PAR GROUPE, groupe LAIT DEMI-ECREME, sélection, identification manager et PIN, confirmation d’une unité retirée ; le total passe de 11,64 € à 7,92 €. Rendus normal et 800 × 600 inspectés, y compris le pavé de vente complet. Imvaluation indisponible : valorisation locale de secours. Aucun matériel physique.

Validation : impos 6 979 tests Java + 108 JavaScript ; imedge 4 159 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 25 960 lignes et 13 144 branches ; imedge 20 098 lignes et 9 698 branches ; tous les compteurs manqués à zéro. JavaScript externe instrumenté à 100 %. Les nouveaux tests couvrent sélection/cible figée, refus et aval, protection du dernier article, types physiques, vente indépendante d’un EAN lié, contexte de navigation, rendu et sélection JavaScript sans admission de vente. Les anciennes fixtures de vue reçoivent explicitement le contexte normal de consultation.

Cotation : seule LC-02-12-05 est promue par CODE de non couverte à intégralement couverte. Le commentaire précise l’exception du dernier article vendu, qui respecte la décision utilisateur antérieure. Seuls L125, O125 et la hauteur de ligne changent dans le classeur ; autres cellules et composants conservés. PDF : pages 1–2 actualisées, page 30 ajoutée, pages 3–29 préservées. P1 : 394/477 intégrales (82,6 %), 14 partielles (2,9 %), soit 408/477 intégrales ou partielles (85,5 %). Campagne : 101 promotions, dont 94 CODE et 7 RELECTURE.


## HD_43 — LC-02-14-01 / -17 / -18 : INFO ARTICLE, quantité et poids

Gain : annoncer le prix exact d’une quantité, d’une étiquette prépesée ou d’un article posé sur la balance, sans modifier le ticket.

Préconditions : construction dev, H2 mémoire jetable et matériel simulé. imedge distribue les articles, prix, PLU et plages de codes-barres existants ; aucun nouveau paramètre. Les polices et le thème existants sont conservés.

1. Caisse fermée, ouvrir INFO ARTICLE : consulter le lait et saisir 1,125 avec le pavé tactile. Le code article est conservé, le prix à l’unité vaut 2,76 et le total 3,11. Aucune vente n’est proposée caisse fermée. Les quantités nulles, négatives ou mal formées sont signalées sans total fictif.
2. Vérifier qu’un seul pavé apparaît. Le champ actif est encadré en bleu et nommé au-dessus du pavé. Passer du code à la quantité puis revenir : les deux valeurs ne se mélangent pas, les zéros initiaux du code restent conservés. Une virgule décimale et au moins trois chiffres après la virgule sont saisissables ; la première frappe remplace la valeur proposée.
3. Caisse ouverte, créer un ticket avec un café puis consulter le lait à 1,125. Comparer les lignes et le total avant/après : aucune modification. Le scanner de la page ne doit alimenter que le jeton de consultation.
4. Scanner l’étiquette PPV 2104020001080 : PLU 4020, prix/kg 1,08, montant imprimé 1,08 et poids déduit 1 kg. Scanner l’étiquette à poids embarqué 2304020008755 : poids net 0,875 kg et total 0,95, sans nouvelle tare. Une clé invalide ou une plage non article ne produit aucune consultation erronée. Les plages BackOffice, devises et préfixes existants sont utilisés.
5. Préparer le simulateur à 0,875 kg puis rechercher le PLU 4020 : la balance est interrogée, le poids net 0,875 kg, le prix/kg 1,08 et le total 0,95 apparaissent. PESER À NOUVEAU relance une lecture. La tare article ou défaut est envoyée à la balance et ne doit pas être déduite deux fois. Les quantités et tares ponctuelles de vente ne sont ni lues ni consommées ; les poids invalides ne deviennent pas des montants.
6. Quitter la consultation et vendre le PPV précédemment consulté : il doit être accepté, la consultation n’ayant pas consommé sa protection de double scan. Vérifier les messages FR/NL/PT, le rendu normal et 800 × 600, les champs lisibles et toutes les touches du pavé accessibles.

Recette exécutée le 22/10/2026 sur imedge et impos réels en dev, bases jetables et balance simulée : consultation caisse fermée ; quantité 1,125 × 2,76 = 3,11 ; saisies invalides ; étiquettes PPV et poids embarqué ; pesée 0,875 kg × 1,08 = 0,95 ; capture scanner par le jeton INFO ARTICLE. Les lignes et le total du ticket restent identiques. Le PPV consulté est ensuite accepté en vente. Le premier rendu à deux pavés a été écarté après retour utilisateur : le rendu final utilise un seul pavé contextuel avec champ actif explicite, testé au navigateur et à 800 × 600. Aucun matériel physique ; ce calcul de prix de référence ne dépend pas d’imvaluation.

Validation : impos 6 993 tests Java + 112 JavaScript ; imedge 4 159 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 26 019 lignes et 13 220 branches ; imedge 20 098 lignes et 9 698 branches ; tous les compteurs manqués à zéro. JavaScript externe instrumenté à 100 %. Tests du calcul décimal, de l’arrondi final, des refus, du prix absent ou nul, du net de balance, des deux types d’étiquette, des plages et devises, du routage de consultation et du pavé contextuel sans mélange des champs. Les fixtures de ressource existantes reçoivent le calculateur de consultation. Une erreur de gabarit Qute introduite pendant le lot a été corrigée dans le code ; aucune assertion n’a été affaiblie.

Cotation : LC-02-14-01, LC-02-14-17 et LC-02-14-18 promues par CODE de non couvertes à intégralement couvertes. Seuls L148/O148, L164/O164 et L165/O165 et les hauteurs de ces lignes changent dans le classeur ; les autres cellules et composants sont préservés. PDF : pages 1–2 actualisées, page 31 ajoutée, pages 3–30 conservées. P1 : 397/477 intégrales (83,2 %), 14 partielles (2,9 %), soit 411/477 intégrales ou partielles (86,2 %). Campagne : 104 promotions, dont 97 CODE et 7 RELECTURE.


## HD_43 — LC-10-01-09 / -11 : montants éligibles éco-chèques et carte achat sociale sur écran client

Gain : avant de payer, le client voit combien il peut régler en éco-chèques et avec la carte achat sociale.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé. Dans PARAMÈTRES CAISSE > AFFICHEUR CLIENT, deux réglages désactivés par défaut : `customer.eco-voucher-eligible` (LC-10-01-09) et `customer.social-card-eligible` (LC-10-01-11). Ils figurent aussi dans les clés administrables par échelon : l’un se règle pour la Belgique, l’autre pour le Portugal. Sur les fiches articles, cocher `ECO_VOUCHER_ELIGIBLE` et/ou `SOCIAL_CARD_ELIGIBLE` ; garder un article sans éligibilité. Attendre la distribution des paramètres et produits à la caisse, puis ouvrir `/customer`.

1. Réglages désactivés : aucune mention éco-chèque ni carte sociale, quel que soit le ticket. L’assiette titres-restaurant reste à sa place.
2. Activer les deux réglages au BackOffice, sans redémarrage. Après distribution, ouvrir une vente : sous l’assiette titres-restaurant, « Articles éligibles aux éco-chèques » puis « Articles éligibles à la carte achat sociale », dans cet ordre, à 0,00 € tant qu’aucun article n’est éligible.
3. Scanner un article éligible aux deux moyens : les deux montants prennent sa valeur. Scanner l’article non éligible : le total augmente, les montants restent identiques. Scanner à nouveau le premier article : sa ligne antérieure est fusionnée, les montants doublent, l’article non éligible ne devient pas éligible. Scanner un article éligible à la seule carte sociale : seul le second montant augmente.
4. Vérifier les autres voies d’admission : saisie manuelle, groupe d’articles, GS1, étiquette pesée et code résolu par plage d’articles portent la même éligibilité.
5. Appliquer une remise de ligne et une remise ticket : les montants suivent les lignes valorisées et la ventilation de la remise. Annuler une ligne éligible avec l’aval d’un superviseur : son montant disparaît. Une ligne négative n’est pas comptée.
6. Passer au paiement et enregistrer un règlement partiel : les deux montants restent affichés, inchangés, à côté du reste à payer. Ce sont les totaux des articles éligibles, ni des plafonds de règlement ni des soldes. La fin de transaction affiche l’écran de remerciement habituel.
7. Désactiver un seul réglage au BackOffice : après distribution, sa ligne disparaît, l’autre reste. Les deux désactivés : la zone n’occupe plus aucune place.
8. Avec une réponse imvaluation exploitable, les montants suivent les lignes valorisées par le moteur ; impos n’applique aucune règle de promotion propre. Imvaluation ne calcule pas d’assiette éco-chèque ni carte sociale.
9. Vérifier les libellés FR/NL/PT, y compris en affichage bilingue, les polices et le thème existants, et la lisibilité en 1280 × 800 puis 800 × 600 : total, reste à payer, assiette titres-restaurant et les deux montants visibles, sans défilement horizontal.

Recette exécutée le 22/10/2026 sur imedge (18070) et impos (18080) réels, profil dev, bases H2 mémoire jetables et matériel simulé. Au navigateur, BackOffice : les deux réglages activés sur la page des paramètres, lait cochée éco-chèque et carte sociale, café carte sociale ; les deux clés sont présentes sur la page des échelons. Caisse : lait 2,76 / 2,76 ; baguette sans effet (total 3,72) ; second lait fusionné 5,52 / 5,52 ; café 5,52 / 9,72 (total 10,68). Écran client inspecté en 1280 × 800 et 800 × 600 : trois lignes d’assiette lisibles au-dessus du total, sans défilement horizontal. Paiement espèces partiel de 5,00 € : montants conservés, reste 5,68 € ; paiement terminé, rendu 4,32 €. Éco-chèques désactivés puis distribués : seule la carte sociale reste ; nouvelle vente lait + café + baguette = 6,96 €, puis 2,76 € après annulation du café avalisée par Marie Curie (MANAGER). Remises et répartition : tests unitaires ; voies EAN, GS1 et plage d’articles : tests des handlers ; saisie manuelle, groupe et pesée : chemins couverts par la suite existante, non rejoués en recette. Imvaluation indisponible : valorisation locale de secours. Aucun essai physique.

Validation : impos 7 000 tests Java + 114 JavaScript ; imedge 4 160 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 26 082 lignes et 13 234 branches ; imedge 20 132 lignes et 9 698 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %. Les attentes de taille JSON intègrent le champ ajouté ; la table des paramètres BackOffice intègre les deux clés.

Cotation : LC-10-01-09 et LC-10-01-11 promues par CODE de non couvertes à intégralement couvertes. Seuls L566, O566, L568, O568 et les hauteurs de ces deux lignes changent dans le classeur ; les autres cellules et composants sont conservés. PDF : pages 1–2 actualisées, page 32 ajoutée, pages 3–31 préservées. P1 : 399/477 intégrales (83,6 %), 14 partielles (2,9 %), soit 413/477 intégrales ou partielles (86,6 %). Ligne de caisses : 520 intégrales, 45 partielles, 165 non couvertes ; couverture pondérée 74,3 %. Campagne : 106 promotions, dont 99 CODE et 7 RELECTURE.


## HD_43 — LC-10-01-04 / LC-03-04-13 / LC-14-02-07 : écran client, avantages, cagnotte et tare

Gain : sur l’écran client, le client repère tout de suite les articles remisés, voit la cagnotte disponible de sa carte avant de payer et lit la tare déduite d’une pesée.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé, service fidélité joignable (`pos.fid.url`). Dans PARAMÈTRES CAISSE > FIDÉLITÉ, le réglage `fidelity.show-balance-customer` (LC-03-04-13) est désactivé par défaut et figure dans les clés administrables par échelon ; il est indépendant de `fidelity.show-holder-name-customer` (BO-10-03-03, nom du porteur). Une tare logicielle doit s’appliquer à un article pesé : tare de contenant, tare article ou `balance.default-tare`. Ouvrir une session et l’écran `/customer`.

1. LC-10-01-04 : appliquer une remise ou un discount sur une ligne, ou obtenir une promotion du moteur. Sous l’article, chaque avantage apparaît comme une étiquette verte en gras, sur sa propre ligne, avec une barre verticale ; le montant de la ligne passe en vert. Une ligne sans avantage garde un montant blanc et aucune étiquette. Une remise ticket reste la ligne distincte « REMISE TICKET », sans étiquette sur les articles.
2. Annuler l’avantage ou la ligne : au rafraîchissement, l’étiquette disparaît et le montant reprend sa couleur. Les libellés sont écrits comme du texte : un libellé contenant du HTML n’est jamais interprété.
3. LC-14-02-07 : peser un article avec tare (balance simulée) : sous la décomposition « poids × prix/kg », « Tare déduite : 0,050 kg » apparaît. Une pesée sans tare, une tare à zéro et un article à l’unité n’affichent rien. Le poids affiché est le poids net déjà facturé ; aucune seconde déduction.
4. LC-03-04-13 : réglage désactivé, rattacher une carte : aucun solde à l’écran client. Activer le réglage au BackOffice et attendre la distribution : « Cagnotte disponible : X € » apparaît dans l’en-tête, sans le nom du porteur si son réglage reste désactivé. Activer aussi le nom : les deux coexistent.
5. Service fidélité muet ou solde non lu : rien n’est affiché, jamais un zéro inventé. Carte retirée ou vente terminée : la mention disparaît.
6. Passer au paiement et enregistrer un règlement partiel : étiquettes, tare et cagnotte restent visibles avec le reste à payer.
7. Désactiver la cagnotte au BackOffice : après distribution, la mention disparaît sans redémarrage.
8. Vérifier les libellés FR/NL/PT, y compris en affichage bilingue, les polices et le thème existants (sombre et clair), et la lisibilité en 1280 × 800 puis 800 × 600, sans défilement horizontal.

Recette exécutée le 23/10/2026 sur imedge (18070) et impos (18080) réels, profil dev, bases H2 mémoire jetables, matériel simulé ; service fidélité simulé par un bouchon local répondant cagnotte 12,35 € (ni imfid ni imvaluation lancés). BackOffice au navigateur : `fidelity.show-balance-customer` activé, `balance.default-tare` = 0,050 ; la clé figure sur la page des échelons. Caisse : carte 2990000000012 rattachée, « Cagnotte disponible : 12,35 € » sans nom ; deux laits (5,52 €) ; pommes pesées 0,800 kg brut → 0,750 kg net à 1,08 €/kg = 0,81 €, « Tare déduite : 0,050 kg » ; discount 10 % sur les laits avalisé par Marie Curie (MANAGER) : étiquette « Discount -10,00% » sous le lait, montant 4,97 € en vert, total 5,78 €. Paiement espèces partiel de 2,00 € : reste 3,78 €, étiquette, tare et cagnotte conservées. Cagnotte désactivée au BackOffice : mention retirée après distribution. Rendus 1280 × 800 et 800 × 600 inspectés, thème clair contrôlé. Promotions moteur : tests JavaScript, aucun échange imvaluation. Aucun essai physique.

Validation : impos 7 003 tests Java + 117 JavaScript ; imedge 4 161 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 26 094 lignes et 13 242 branches ; imedge 20 135 lignes et 9 698 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %. Les attentes de taille JSON intègrent le champ ajouté ; la table des paramètres BackOffice intègre la nouvelle clé.

Cotation : LC-10-01-04, LC-03-04-13 et LC-14-02-07 promues par CODE de non couvertes à intégralement couvertes. Le commentaire de LC-03-04-13 annonçant l’absence d’affichage était obsolète pour le nom (BO-10-03-03) ; la cagnotte est ajoutée par ce lot. Seuls L205, O205, L561, O561, L726, O726 et les hauteurs de ces trois lignes changent dans le classeur. PDF : pages 1–2 actualisées, page 33 ajoutée, pages 3–32 préservées. P1 : 402/477 intégrales (84,3 %), 14 partielles (2,9 %), soit 416/477 intégrales ou partielles (87,2 %). Ligne de caisses : 523 intégrales, 45 partielles, 162 non couvertes ; couverture pondérée 74,7 %. Campagne : 109 promotions, dont 102 CODE et 7 RELECTURE.


## HD_43 — LC-12-01-01 / -02 et LC-01-05-08 : ouverture forcée du tiroir, aval depuis le BackOffice

Gain : le tiroir ne s’ouvre plus hors vente sans motif ni aval, chaque ouverture se retrouve au journal du BackOffice, et le responsable autorise ou refuse une demande d’aval depuis son poste sans se déplacer en caisse.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé, caisse rattachée au nœud (`pos.sync.store-url`). Au BackOffice, écran des listes : créer une liste de nature Justifications servant la fonction `DRAWER_OPEN` (le code figure désormais dans l’aide de l’écran) avec deux motifs, puis attendre sa distribution. Ouvrir une session caissier ; prévoir un compte superviseur.

1. LC-12-01-01 : menu TIROIR, ticket vide : la touche « OUVERTURE TIROIR » est offerte. Avec un article au ticket, une ligne annulée ou un paiement en cours, la touche disparaît ; un accès direct à `/drawer-open` ou `/action/drawer-open` est refusé avec « OUVERTURE TIROIR : HORS TRANSACTION UNIQUEMENT », tiroir fermé.
2. LC-12-01-02 : la touche ouvre « OUVERTURE FORCÉE DU TIROIR » avec les motifs administrés. RETOUR revient à la vente sans rien ouvrir. Un code de motif inconnu est refusé (« MOTIF D’OUVERTURE INCONNU »), retour au choix. Sans liste administrée, l’écran n’apparaît pas : la demande d’aval suit directement.
3. Après le motif, la modale d’autorisation s’ouvre ; le tiroir reste fermé. Le code d’un caissier est refusé. Le code d’un superviseur ouvre le tiroir, la vente reprend après fermeture physique. Un superviseur connecté en caisse ouvre sans modale, sous son propre badge.
4. Chaque ouverture écrit un événement `DRAWER_FORCED_OPEN` portant le badge du caissier, le code et le libellé du motif (ou « Sans motif ») et l’auteur de l’aval. Dans imedge, Journal > fonctionnel, la case `DRAWER_FORCED_OPEN` filtre ces ouvertures.
5. LC-01-05-08 : dès qu’une demande d’autorisation s’ouvre en caisse, quel que soit le geste, le tableau de bord du BackOffice affiche « POSxx demande un superviseur — AUTORISATION SUPERVISEUR (opérateur, heure) — geste », sans appel manuel du caissier. Le geste est nommé par son code ; une annulation de ligne apparaît sans l’identifiant technique de la ligne, une modification de prix par son type et sa valeur.
6. Refuser depuis le BackOffice avec un motif : au relevé suivant des ordres, la modale se ferme en caisse, le motif saisi par le responsable s’affiche dans la zone de message, la fonction n’est pas exécutée. Un refus ne ferme pas une demande d’aval restée locale (caisse sans nœud).
7. Autoriser depuis le BackOffice : la caisse affiche la notice d’autorisation et la modale présente « VALIDER (AUTORISÉ PAR LE BACK-OFFICE) » ; une touche exécute le geste, sans code. Les autres demandes encore ouvertes de la caisse passent « déjà traitée en caisse ».
8. Aval donné en caisse par code : la demande passe « déjà traitée en caisse » au BackOffice. ANNULER dans la modale retire la demande du tableau de bord, sauf si la caisse a aussi un appel superviseur en cours.
9. Vérifier les libellés FR/NL/PT, les polices et le thème existants, et la lisibilité en 800 × 600 : touche du menu TIROIR, écran des motifs, modale avec la touche de validation BackOffice.

Recette exécutée le 23/10/2026 sur imedge (18070) et impos (18080) réels, profil dev, bases H2 mémoire jetables, matériel simulé, relevé des ordres accéléré à 3 s. Au BackOffice, liste « TIROIR » (fonction DRAWER_OPEN) avec ERR « Erreur de rendu monnaie » et CHG « Change pour un client », distribuée à la caisse. Navigateur en 800 × 600 : menu TIROIR, touche OUVERTURE TIROIR, écran des deux motifs, choix ERR, modale d’autorisation. Tableau de bord : « POS01 demande un superviseur — AUTORISATION SUPERVISEUR (Jean Dupont, 11:17) — DRAWER_FORCE_OPEN ». Refus « Pas d ouverture sans responsable » : modale fermée en caisse, motif affiché, tiroir fermé. Nouvelle demande autorisée depuis le BackOffice : touche verte de validation visible dans la modale, un appui ouvre le tiroir ; journal imedge filtré sur DRAWER_FORCED_OPEN : « POS01 12341234 … ERR - Erreur de rendu monnaie (aval Autorisé par Le Manager) ». Abandon d’une demande : retirée du tableau de bord. Motif CHG : code du caissier refusé, code de Marie Curie accepté, tiroir ouvert, demande « déjà traitée en caisse », événement « CHG - Change pour un client (aval mcurie) ». Lait au ticket : touche absente, accès direct refusé, tiroir fermé. Aucun essai physique.

Validation : impos 7 020 tests Java + 117 JavaScript ; imedge 4 161 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 26 189 lignes et 13 292 branches ; imedge 20 136 lignes et 9 698 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %. Les tests de vocabulaire du journal intègrent le nouveau type dans les deux projets.

Cotation : LC-12-01-01, LC-12-01-02 et LC-01-05-08 promues par CODE de non couvertes à intégralement couvertes. Seuls L39, O39, L620, O620, L621, O621 et les hauteurs de ces trois lignes changent dans le classeur. PDF : pages 1–2 actualisées, page 34 ajoutée, pages 3–33 préservées. P1 : 405/477 intégrales (84,9 %), 14 partielles (2,9 %), soit 419/477 intégrales ou partielles (87,8 %). Ligne de caisses : 526 intégrales, 45 partielles, 159 non couvertes ; couverture pondérée 75,1 %. Campagne : 112 promotions, dont 105 CODE et 7 RELECTURE.


## HD_43 — LC-09-01-01 / LC-09-01-09 : visu caisse adaptable et clavier virtuel par pays

Gain : la même caisse se déploie sur toutes les dalles du parc, tactiles comprises, sans écran réduit ni flou, et le clavier virtuel présente aux caissiers la disposition de leur pays.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé, caisse rattachée au nœud (`pos.sync.store-url`). Dans PARAMÈTRES CAISSE > LANGUE, le réglage `keyboard.layout` (LC-09-01-09) vaut AZERTY par défaut ; il est proposé en liste fermée AZERTY, QWERTY, QWERTZ, ABCD et figure dans les clés administrables par échelon. Ouvrir une session caissier ; prévoir un ticket clôturé pour l’écran ticket par e-mail.

1. LC-09-01-01 : afficher la vente, le paiement, la recherche, fruits et légumes, la saisie directe, le rappel, le thème, le solde de bon, le badge, la facture, la réimpression, le retour, le rapport X et le ticket par e-mail en 800 × 600, 1024 × 768, 1280 × 1024, 1360 × 768 et 1920 × 1080 (ratios 4/3, 5/4, 16/9). Chaque écran occupe toute la dalle, sans défilement horizontal ni touche hors écran ; les textes et les touches grandissent avec la dalle sans être étirés ; les images d’articles gardent leurs proportions.
2. Rejouer les mêmes écrans dans un navigateur tactile (ou en émulation mobile) : la page est mise en page à la largeur réelle de la dalle, sans réduction d’ensemble ni flou. Le seul défilement admis est celui, prévu, de la zone clavier de l’écran e-mail en 800 × 600.
3. LC-09-01-09 : réglage par défaut, les claviers de l’identification, de la recherche, du rappel et de l’e-mail présentent AZERTY, à l’identique de l’existant.
4. Passer le réglage à QWERTY au BackOffice, sans redémarrage. Après distribution : rangées QWERTYUIOP / ASDFGHJKL / ZXCVBNM ; la rangée de neuf lettres ne remonte pas la suivante. Sur la recherche, la dernière rangée garde l’espace (signe ␣) et EFFACER ; sur l’e-mail, « @._ » suivent ZXCVBNM et tous les symboles restent disponibles.
5. Saisir au clavier virtuel « LAIT » sur la recherche : le lait est trouvé. Saisir « zoe@mail.fr » sur l’écran e-mail : l’adresse affichée est exacte, en minuscules.
6. Passer à QWERTZ (Y et Z permutés), puis à ABCD (ordre alphabétique, ESPACE et EFFACER complets) : chaque disposition est distribuée et appliquée sur les mêmes écrans. Sur l’écran d’identification en ABCD, saisir « jdupont » au clavier virtuel.
7. Revenir à AZERTY : rendu identique à l’existant. Une valeur inconnue écrite en base, ou non lue, affiche AZERTY.
8. Vérifier la lisibilité en 800 × 600 et 1360 × 768, les polices et le thème existants ; libellés des touches inchangés.

Recette exécutée le 23/10/2026 sur imedge (18070) et impos (18080) réels, profil dev, bases H2 mémoire jetables, matériel simulé. Navigateur Chromium : quatorze écrans × cinq définitions, en mode bureau et en émulation tactile, soit 140 mesures : largeur de mise en page égale à celle de la dalle, aucun défilement horizontal, aucune touche hors écran, à l’exception de EFFACER et C du clavier e-mail en 800 × 600, situées dans la zone clavier défilante prévue. En émulation tactile, sans la déclaration de largeur ajoutée, le thème, le solde de bon et la réimpression étaient mis en page sur 980 px puis réduits à 82 % ; avec elle, à 800 px sans réduction. BackOffice : liste fermée de quatre valeurs dans la section LANGUE, clé présente sur la page des échelons. QWERTY distribué : recherche et rappel 1234567890 / QWERTYUIOP / ASDFGHJKL / ZXCVBNM ␣ EFFACER ; e-mail ZXCVBNM@._ puis les symboles ; « lait » trouvé, « zoe@mail.fr » saisi. QWERTZ : YXCVBNM. ABCD : recherche, rappel et identification ABCDEFGHIJ / KLMNOPQRST / UVWXYZ, « jdupont » saisi. Retour à AZERTY : capture de la recherche identique au pixel près à celle d’avant le lot. Aucun essai sur dalle physique.

Validation : impos 7 024 tests Java + 294 JavaScript ; imedge 4 163 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 26 197 lignes et 13 296 branches ; imedge 20 141 lignes et 9 700 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %. Les tests de gabarit Qute fournissent la disposition ; la table des paramètres BackOffice intègre la nouvelle clé.

Cotation : LC-09-01-01 et LC-09-01-09 promues par CODE de non couvertes à intégralement couvertes. Seuls L535, O535, L548, O548 et les hauteurs de ces deux lignes changent dans le classeur. PDF : pages 1–2 actualisées, page 35 ajoutée, pages 3–34 préservées. P1 : 407/477 intégrales (85,3 %), 14 partielles (2,9 %), soit 421/477 intégrales ou partielles (88,3 %). Ligne de caisses : 528 intégrales, 45 partielles, 157 non couvertes ; couverture pondérée 75,4 %. Campagne : 114 promotions, dont 107 CODE et 7 RELECTURE.


## HD_43 — LC-13-06-08 / LC-13-06-13 : langue choisie par le client, visu client et ticket

Gain : le client choisit d’un geste, sur la visu client, la langue de l’écran et de son ticket, sans interrompre le caissier, qui garde l’interface dans sa propre langue.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé, caisse rattachée au nœud (`pos.sync.store-url`). Dans PARAMÈTRES CAISSE > LANGUE, le réglage `language.customer-choice` (LC-13-06-08/13) est désactivé par défaut et figure dans les clés administrables par échelon ; les langues proposées en caisse (`language.available`) valent fr;nl;pt. Ouvrir une session caissier et l’écran `/customer` (visu client tactile).

1. Réglage désactivé : la visu client n’offre aucune touche de langue, quelle que soit la phase ; un appel direct à `/customer/language` est ignoré.
2. Activer le réglage au BackOffice, sans redémarrage. Après distribution, caisse ouverte : une rangée « Français », « Nederlands », « Português » apparaît en bas de la visu client, la langue affichée marquée ; avec une seule langue proposée, aucune rangée. Caisse fermée : aucune rangée, et un choix forcé est ignoré.
3. LC-13-06-08 : magasin bilingue (seconde langue renseignée), scanner deux articles : mentions bilingues. Toucher « Português » : la visu client se redessine aussitôt en portugais seul (titre, total, reste à payer, mentions d’assiette, moyens de paiement, remerciement). Les libellés d’articles ne changent pas. La visu caisse reste dans la langue de l’opérateur.
4. Payer : l’écran de fin (« OBRIGADO! », « A devolver ») reste dans la langue choisie. NOUVELLE VENTE : la visu client revient d’elle-même à la langue du magasin, bilingue le cas échéant.
5. LC-13-06-13 : nouvelle vente, choisir « Nederlands », payer et imprimer le ticket (choix de fin de transaction, puis touche IMPRIMER sur une autre vente) : le ticket est en néerlandais seul. Là où le ticket de sortie est administré, il sort dans la même langue ; en mode formation, le ticket de formation aussi.
6. Réimprimer ce ticket après la vente : le duplicata suit la langue de la caisse (bilingue dans un magasin bilingue).
7. Désactiver le réglage au BackOffice : après distribution, la rangée disparaît.
8. Vérifier la lisibilité en 1280 × 800 et 800 × 600, les polices et le thème existants : rangée de touches sans défilement horizontal, aucune mention masquée.

Recette exécutée le 23/10/2026 sur imedge (18070) et impos (18080) réels, profil dev, bases H2 mémoire jetables, matériel simulé. BackOffice au navigateur : `language.customer-choice` activé, seconde langue `nl` ; clé présente sur la page des échelons. Visu client en 1280 × 800 et 800 × 600 : lait + baguette (3,72 €), mentions bilingues FR/NL, touches Français (marquée), Nederlands, Português, sans défilement horizontal. « Português » touché : les deux visus client passent en `pt` (« Ecrã do cliente », « Artigos elegíveis para vales de refeição », « TOTAL »), visu caisse restée en français. Espèces 5,00 € : « OBRIGADO! », « DINHEIRO 3,72 € », « A devolver : 1,28 € ». Vente terminée : visu client revenue en `fr`. Seconde vente, « Nederlands » : ticket imprimé relu sur l’imprimante simulée, « Bon », « TOTAAL INCL. BTW 3,72 E », « BETALING », « CONTANT », « TERUGGEGEVEN 1,28 E », « DANK VOOR UW BEZOEK », sans français ; réimpression : « DUPLICATA N°1 » bilingue NL/FR. Réglage désactivé : aucune touche, appel forcé `tag=nl` → 204 sans effet. Réglage réactivé, caisse verrouillée : aucune touche, appel forcé `tag=pt` sans effet. Ticket de sortie et ticket de formation : tests unitaires. Aucun essai sur dalle physique.

Validation : impos 7 039 tests Java + 300 JavaScript ; imedge 4 164 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 26 241 lignes et 13 318 branches ; imedge 20 144 lignes et 9 700 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %. Les attentes de taille du flux de la visu client intègrent les deux champs ajoutés ; la table des paramètres BackOffice intègre la nouvelle clé.

Cotation : LC-13-06-08 et LC-13-06-13 promues par CODE de non couvertes à intégralement couvertes. Limite déclarée : les messages poussés à l’afficheur par un événement restent dans la langue de la caisse. Seuls L711, O711, L716, O716 et les hauteurs de ces deux lignes changent dans le classeur. PDF : pages 1–2 actualisées, page 36 ajoutée, pages 3–35 préservées. P1 : 409/477 intégrales (85,7 %), 14 partielles (2,9 %), soit 423/477 intégrales ou partielles (88,7 %). Ligne de caisses : 530 intégrales, 45 partielles, 155 non couvertes ; couverture pondérée 75,7 %. Campagne : 116 promotions, dont 109 CODE et 7 RELECTURE.


## HD_43 — LC-08-06-01 / -04 / -05 : NIF portugais obligatoire, contrôlé et scanné

Gain : aucune vente portugaise ne se règle sans NIF, un NIF mal construit est refusé avant d’atteindre un document fiscal, et le caissier scanne le NIF au lieu de le taper.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé (scanner par `/api/pos/scan`), caisse rattachée au nœud (`pos.sync.store-url`). Dans PARAMÈTRES CAISSE > CLIENT EN CAISSE, trois réglages : `customer.tax-id-required` (LC-08-06-01) et `customer.tax-id-pt-check` (LC-08-06-04), désactivés par défaut, et `customer.tax-id-scan-pattern` (LC-08-06-05), par défaut `^(?:PT|NIF)(\d{9})$`. Les trois figurent dans les clés administrables par échelon. `customer.default-tax-id` vaut 999999990. Ouvrir une session caissier.

1. Réglages par défaut : une vente se règle sans NIF, comme avant ; aucun contrôle portugais.
2. LC-08-06-01 : activer le NIF obligatoire au BackOffice. Après distribution, scanner un article puis RÈGLEMENT : le masque NIF CLIENT s’ouvre, 999999990 proposé. VALIDER : le paiement s’ouvre. Nouvelle vente : vider le champ puis VALIDER : « NIF client obligatoire pour encaisser… », la vente n’entre pas en paiement. ANNULER revient à la vente.
3. LC-08-06-04 : activer le contrôle. Saisir 123456780 : « NIF portugais invalide : 9 chiffres, préfixe légal et clé de contrôle exacte. » ; 12345678 (8 chiffres) et 412345675 (préfixe 41) aussi refusés. 123456789, PT123456789, 500000000 (clé 0) et 999999990 acceptés. Un NIF étranger (ESB12345678) est accepté sans contrôle.
4. LC-08-06-05 : masque NIF CLIENT ouvert, scanner `NIF500000000` : le champ affiche 500000000 dans la seconde ; sur la vente, « NIF CLIENT : 500000000 ». Scanner `PT123456780` : refusé, le NIF précédent est conservé. Pendant le paiement, un scan de NIF est refusé (« TERMINEZ OU ANNULEZ LE TICKET D’ABORD »). Un code-barre article ou un code que l’expression ne lit pas suit son chemin habituel. Expression vidée au BackOffice : plus aucun code-barre NIF reconnu, et le conseil de scan disparaît du masque.
5. Payer et imprimer : le ticket porte « NIF 123456789 » ; avec 999999990, le libellé anonyme administré.
6. Magasin bilingue (seconde langue `nl`) : imprimer puis réimprimer un ticket ; sur le duplicata bilingue, la ligne de ventilation de TVA tient dans les 42 colonnes, sur deux lignes (« BTW / TVA 20,00% », puis « EXCL / HT … BTW / TVA … » aligné à droite). Ticket monolingue : ligne unique, inchangée.
7. Vérifier les libellés FR/NL/PT, les polices et le thème existants, et la lisibilité du masque en 800 × 600, 1280 × 800 et 1366 × 768, sans défilement horizontal.

Recette exécutée le 23/10/2026 sur imedge (18070) et impos (18080) réels, profil dev, bases H2 mémoire jetables, matériel simulé. BackOffice au navigateur : NIF obligatoire et contrôle portugais activés, expression par défaut lue, seconde langue `nl` ; trois clés sur la page des échelons. Caisse en 800 × 600 : lait au ticket, `/pay` renvoyé à `/sale-tax-id?next=pay`, 999999990 proposé, conseil de scan affiché ; champ vidé refusé (« NIF client obligatoire pour encaisser… ») ; 123456780 refusé (« NIF portugais invalide… ») ; 123456789 accepté, retour au paiement ; espèces 5,00 €, ticket imprimé avec « NIF 123456789 ». Duplicata bilingue : « Waarvan BTW / Dont TVA … » (42 colonnes), « BTW / TVA 20,00% » (18) et « EXCL / HT 2,30  BTW / TVA 0,46 » aligné à droite (42). Vente suivante : masque ouvert, `NIF500000000` scanné, champ passé à 500000000, notice « NIF CLIENT : 500000000 » sur la vente ; `PT123456780` scanné : refusé, 500000000 conservé ; ESB12345678 saisi : accepté ; paiement ouvert sans détour ; `NIF123456789` scanné pendant le paiement : refusé, ESB12345678 conservé. Masque en 1280 × 800 et 1366 × 768 sans défilement horizontal. Aucun scanner physique.

Validation : impos 7 057 tests Java + 302 JavaScript ; imedge 4 165 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 26 325 lignes et 13 396 branches ; imedge 20 154 lignes et 9 700 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %. La table des paramètres BackOffice intègre les trois nouvelles clés.

Cotation : LC-08-06-01, LC-08-06-04 et LC-08-06-05 promues par CODE de non couvertes à intégralement couvertes ; la correction de la ligne de TVA bilingue ne change aucune cotation (LC-13-06-11 déjà couverte). Seuls L524, O524, L527, O527, L528, O528 et les hauteurs de ces trois lignes changent dans le classeur. PDF : pages 1–2 actualisées, page 37 ajoutée, pages 3–36 préservées. P1 : 412/477 intégrales (86,4 %), 14 partielles (2,9 %), soit 426/477 intégrales ou partielles (89,3 %). Ligne de caisses : 533 intégrales, 45 partielles, 152 non couvertes ; couverture pondérée 76,1 %. Campagne : 119 promotions, dont 112 CODE et 7 RELECTURE.


## HD_43 — LC-03-02-09 / LC-14-03-01 : remise du client en compte, métrologie

Gain : le client en compte obtient son tarif négocié sans geste du caissier, et un contrôle métrologique lit en caisse les informations du certificat et du logiciel sans démonter la balance.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé, caisse rattachée au nœud (`pos.sync.store-url`). Au BackOffice : création de compte client autorisée (`customer.account-creation-blocked` désactivé), plage des numéros de compte `customer.account-pattern` renseignée (par exemple `^CC\d{8}$`), un compte CC00000001 avec une remise de 5 % sur sa fiche et un compte CC00000002 sans remise ; dans PARAMÈTRES CAISSE > BALANCE, `balance.metrology-body`, `balance.metrology-certificate` et `balance.metrology-crc` (LC-14-03-01), vides par défaut et présents sur la page des échelons. Attendre la distribution du référentiel client et des paramètres. Ouvrir une session caissier.

1. LC-03-02-09 : scanner deux articles (3,72 €), puis la carte CC00000001 : « CLIENT EN COMPTE DUPONT BTP : REMISE 5 % », ligne « REMISE TICKET (5 %) », total 3,53 € ; les montants de ligne sont ventilés ; la visu client affiche la même remise.
2. Ajouter, annuler ou changer la quantité d’un article : la remise est recalculée sur le nouveau panier.
3. Scanner de nouveau la carte : « REMISE TICKET DÉJÀ APPLIQUÉE : REMISE CLIENT NON APPLIQUÉE », la remise en place est conservée. Avec une remise ticket saisie à la main, même message, la remise saisie est conservée.
4. Scanner la carte CC00000002 : « CLIENT EN COMPTE MARTIN SARL : AUCUNE REMISE ». Un numéro dans la plage mais inconnu suit le traitement habituel d’un code inconnu ; sans plage administrée, aucune carte de compte n’est reconnue en vente.
5. Segment administré (`customer.discount-segment`) : seul un compte de ce segment obtient sa remise. Magasin qui épargne les articles déjà remisés : la remise se ventile sur les autres ; si tous le sont, « TOUTES LES LIGNES SONT DÉJÀ REMISÉES ».
6. Carte scannée avant le premier article : la remise couvre ensuite tout le panier. Numéro saisi au pavé de la vente puis OK, pour une plage numérique : même effet que le scan.
7. Pendant le paiement, un scan de carte de compte est refusé (« TERMINEZ OU ANNULEZ LE TICKET D’ABORD »). Payer et imprimer : le ticket porte la remise ticket ; dans un magasin bilingue, le remerciement trop long passe sur deux lignes et aucune ligne ne dépasse 42 colonnes.
8. LC-14-03-01 : caisse fermée, MAINTENANCE puis MÉTROLOGIE : organisme de certification, n° de certificat, CRC de la balance, logiciel d’encaissement et version du logiciel. Vider un paramètre au BackOffice : l’élément affiche « non renseigné ». RETOUR revient à la maintenance.
9. Vérifier les libellés FR/NL/PT, les polices et le thème existants, et la lisibilité en 800 × 600 et 1280 × 800, sans défilement horizontal.

Recette exécutée le 23/10/2026 sur imedge (18070) et impos (18080) réels, profil dev, bases H2 mémoire jetables, matériel simulé. BackOffice au navigateur : création de compte autorisée, comptes CC00000001 (DUPONT BTP, 5 %, plafond 1 000) et CC00000002 (MARTIN SARL, sans remise) créés, plage `^CC\d{8}$`, métrologie « LNE (organisme notifié 0071) », « LNE-37115 », « 8F3A », seconde langue `nl` ; trois clés de métrologie sur la page des échelons. Caisse en 800 × 600 : lait + baguette 3,72 € ; CC00000001 scannée : notice, ligne « REMISE TICKET (5 %) » -0,19 €, lignes 2,62 € et 0,91 €, total 3,53 €, visu client « KORTING BON (5 %) / REMISE TICKET (5 %) » ; second scan : remise conservée et signalée ; CC00000002 : « AUCUNE REMISE ». Espèces 5,00 €, ticket bilingue : « KORTING BON / REMISE TICKET -0,19 E », « DANK VOOR UW BEZOEK / » puis « MERCI DE VOTRE VISITE » sur deux lignes, largeur maximale 42. Nouvelle vente : carte scannée puis lait + baguette : total 3,53 €. Caisse verrouillée, maintenance puis MÉTROLOGIE en 800 × 600 et 1280 × 800 : cinq éléments affichés (logiciel impos, version 1.0-SNAPSHOT), sans défilement horizontal ; RETOUR vers la maintenance. Saisie au pavé et segment : tests unitaires (plage alphanumérique en recette). Aucune balance physique.

Validation : impos 7 073 tests Java + 305 JavaScript ; imedge 4 165 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 26 402 lignes et 13 440 branches ; imedge 20 154 lignes et 9 700 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %. La table des paramètres BackOffice intègre les trois clés de métrologie ; la garde des écrans adaptables couvre le nouvel écran.

Cotation : LC-03-02-09 et LC-14-03-01 promues par CODE de non couvertes à intégralement couvertes. Le commentaire de LC-03-02-09 annonçant l’absence du taux sur la fiche était obsolète : le champ existait et descendait en caisse ; seule son application manquait. Limite déclarée de LC-14-03-01 : le CRC affiché est celui du certificat, la balance ne le transmet pas. Seuls L188, O188, L728, O728 et les hauteurs de ces deux lignes changent dans le classeur. PDF : pages 1–2 actualisées, page 38 ajoutée, pages 3–37 préservées. P1 : 414/477 intégrales (86,8 %), 14 partielles (2,9 %), soit 428/477 intégrales ou partielles (89,7 %). Ligne de caisses : 535 intégrales, 45 partielles, 150 non couvertes ; couverture pondérée 76,4 %. Campagne : 121 promotions, dont 114 CODE et 7 RELECTURE.


## HD_43 — LC-07-04-07 / LC-07-04-08 : cashback par carte bancaire

Gain : le client retire des espèces en caisse en payant par carte, et le magasin écoule son fond de caisse sans aller à la banque.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé (TPE virtuel accepté par `/api/hardware/tpe/accept`, tiroir et imprimante simulés), caisse rattachée au nœud (`pos.sync.store-url`). Dans PARAMÈTRES CAISSE > MONETIQUE, deux réglages : `payment.cashback-enabled` (LC-07-04-07/08), désactivé par défaut, et `payment.cashback-max`, 60,00 € par défaut (0 : aucun plafond). Les deux figurent dans les clés administrables par échelon. Ouvrir une session caissier.

1. Réglages par défaut : aucune touche CASHBACK ; un montant carte supérieur au reste dû est ramené au reste dû, comme avant.
2. LC-07-04-07 : activer le cashback au BackOffice avec un plafond de 30,00 €. Après distribution, vente de 3,72 €, RÈGLEMENT puis CARTE BANCAIRE : l’aide « Au-delà du reste dû, la différence est rendue en espèces » s’affiche sous la saisie. Saisir 23,72 € : le TPE demande 23,72 €. Accepter : règlements « CARTE BANCAIRE 23,72 € » et « CASHBACK -20,00 € », reste 0, transaction terminée avec « RENDU CLIENT 20.00 € », tiroir ouvert, visu client « Cashback espèces -20,00 ».
3. Fermer le tiroir et imprimer : le ticket porte « CARTE BANCAIRE 23,72 E » puis « CASHBACK ESPECES -20,00 E », aucune ligne au-delà de 42 colonnes.
4. Plafond : vente de 2,76 €, carte 50,00 € : « CASHBACK REFUSÉ : PLAFOND 30,00 € », le TPE n’est pas sollicité. Un plafond à 0 n’en oppose aucun.
5. LC-07-04-08 : touche CASHBACK, saisir 20,00 au pavé puis VALIDER : le TPE demande 22,76 € ; accepté, 20,00 € rendus comme à l’étape 2. Un montant saisi d’avance sur le pavé puis CASHBACK part directement.
6. Refus du TPE : aucun règlement, ni carte ni espèces. Le débit combiné titres-restaurant (CB / TRD) ne porte jamais de cashback. Cashback désactivé : la touche disparaît, et un envoi direct de `/action/pay-card-cashback` est refusé (« CASHBACK NON PROPOSÉ DANS CE MAGASIN »).
7. Écran de vente : la touche affiche « FRUITS & LÉGUMES » (FR), « FRUIT & GROENTEN » (NL), « FRUTAS & LEGUMES » (PT), sans `&amp;`.
8. Vérifier les libellés FR/NL/PT, les polices et le thème existants, et la lisibilité de l’écran de règlement en 800 × 600, 1280 × 800 et 1366 × 768, sans défilement horizontal.

Recette exécutée le 23/10/2026 sur imedge (18070) et impos (18080) réels, profil dev, bases H2 mémoire jetables, matériel simulé. BackOffice au navigateur : cashback lu désactivé, plafond 60.00 ; activé, plafond 30.00 ; deux clés sur la page des échelons. Caisse en 800 × 600 : touche « FRUITS & LÉGUMES » (HTML `FRUITS &amp; LÉGUMES`, échappé une seule fois) ; vente de 3,72 €, aide affichée sous la saisie carte, 23,72 € demandés et acceptés, « CASHBACK -20,00 € » à l’historique, « RENDU CLIENT 20.00 € », tiroir OPEN, visu client « Cashback espèces » ; ticket « CASHBACK ESPECES -20,00 E », largeur 42. Vente de 2,76 € : 50,00 € refusés au plafond, TPE au repos ; CASHBACK, 20,00 tapés au pavé : 22,76 € demandés, CARTE BANCAIRE 22,76 € et Cashback espèces -20,00 €, tiroir ouvert, ticket conforme. Écran de règlement en 1280 × 800 et 1366 × 768 sans défilement horizontal. Le message « CASHBACK 20,00 E » part bien sur l’afficheur, que la caisse remplace aussitôt par le total, comme pour tout autre message de règlement. Aucun TPE physique.

Validation : impos 7 088 tests Java + 305 JavaScript ; imedge 4 166 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 26 450 lignes et 13 468 branches ; imedge 20 160 lignes et 9 700 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %. La table des paramètres BackOffice intègre les deux nouvelles clés.

Cotation : LC-07-04-07 et LC-07-04-08 promues par CODE de non couvertes à intégralement couvertes. Seuls L374, O374, L375, O375 et les hauteurs de ces deux lignes changent dans le classeur. PDF : pages 1–2 actualisées, page 39 ajoutée, pages 3–38 préservées. P1 : 416/477 intégrales (87,2 %), 14 partielles (2,9 %), soit 430/477 intégrales ou partielles (90,1 %). Ligne de caisses : 537 intégrales, 45 partielles, 148 non couvertes ; couverture pondérée 76,6 %. Campagne : 123 promotions, dont 116 CODE et 7 RELECTURE.


## HD_43 — LC-08-05-07 : duplicata du dernier ticket carte bancaire Verifone

Gain : le client qui a perdu son ticket carte reçoit à la caisse le duplicata du ticket même que le TPE a composé, sans manipulation du terminal.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé. Le TPE virtuel accepte une transaction par `POST /api/hardware/tpe/accept` ; le corps de la requête, s’il est présent (texte), est le ticket que renvoie le terminal : copie client, puis copie commerçant après le séparateur WCL `--/--`. Ouvrir une session caissier.

1. Vente réglée en carte ; le TPE accepte en renvoyant son ticket client et son ticket commerçant. IMPRIMER : le ticket de vente sort, suivi des deux tickets du terminal, à l’identique.
2. NOUVELLE VENTE. Menu TICKET, touche DERNIER : DUPLICATA CB : « DUPLICATA CB IMPRIMÉ » ; l’imprimante reçoit « *** DUPLICATA *** » puis le ticket client du terminal, caractère pour caractère, sans le ticket commerçant. Le duplicata est archivé dans les documents client (envoi par e-mail).
3. Ticket client conservé même s’il n’a pas été imprimé (impression conditionnelle, aucun ticket demandé) : la touche le réimprime.
4. Vente réglée en carte sans ticket renvoyé par le terminal : le duplicata reste le ticket construit par la caisse (LC-08-05-09), en-tête magasin, « *** DUPLICATA *** », montant, autorisation. Vente sans carte : « AUCUN PAIEMENT CARTE SUR CE TICKET ».
5. Plusieurs paiements carte sur le ticket : un duplicata par paiement, chacun avec son propre ticket du terminal quand il existe.
6. Vérifier les libellés, les polices et le thème existants, et la lisibilité de l’écran de vente en 800 × 600, 1280 × 800 et 1366 × 768, sans défilement horizontal.

Recette exécutée le 23/10/2026 sur imedge (18070) et impos (18080) réels, profil dev, bases H2 mémoire jetables, matériel simulé. Caisse : vente de 3,72 € réglée en carte, TPE simulé accepté avec un ticket client (« ************4242 », AID A0000000421010, « MONTANT = 3,72 EUR », « TICKET CLIENT ») et un ticket commerçant ; impression : ticket de vente, ticket client et ticket commerçant. Vente close ; en 800 × 600, menu TICKET, DERNIER : DUPLICATA CB : notice « ✔ DUPLICATA CB IMPRIMÉ », imprimante simulée : « *** DUPLICATA *** » puis le ticket client du terminal à l’identique, pas de ticket commerçant. Vente suivante de 2,76 € acceptée sans ticket du terminal : duplicata construit par la caisse (MONTANT 2,76 E, AUTORISATION). Écran de vente en 1280 × 800 et 1366 × 768 sans défilement horizontal. Limite : le transport Verifone attend toujours la spécification de son encadrement filaire ; la réponse du terminal est rejouée par le simulateur. Aucun TPE physique.

Validation : impos 7 093 tests Java + 305 JavaScript ; imedge 4 166 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 26 464 lignes et 13 480 branches ; imedge 20 160 lignes et 9 700 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %.

Cotation : LC-08-05-07 promue par CODE de non couverte à intégralement couverte. Seuls L516, O516 et la hauteur de cette ligne changent dans le classeur. PDF : pages 1–2 actualisées, page 40 ajoutée, pages 3–39 préservées. P1 : 417/477 intégrales (87,4 %), 14 partielles (2,9 %), soit 431/477 intégrales ou partielles (90,4 %). Ligne de caisses : 538 intégrales, 45 partielles, 147 non couvertes ; couverture pondérée 76,8 %. Campagne : 124 promotions, dont 117 CODE et 7 RELECTURE.


## HD_43 — LC-07-15-01 à -04 / LC-13-01-01 : paiement sur monnayeur en caisse

Gain : le caissier ne manipule plus les espèces, le rendu est exact, l’argent part au coffre sans prélèvement manuel et le fond du monnayeur est surveillé à chaque vente.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé. Le monnayeur simulé de la caisse expose le contrat du pont : `POST /api/hardware/cashchanger/deposit` (montant dû en centimes), `GET /api/hardware/cashchanger/deposit`, `POST …/deposit/fix`, `POST …/deposit/cancel`, `GET /api/hardware/cashchanger/levels` ; deux gestes de simulation : `POST /api/hardware/cashchanger/insert` (une pièce ou un billet, en centimes) et `POST /api/hardware/cashchanger/levels` (bloc `status=`, `full=`, `low=`, `high=`). Dans PARAMÈTRES CAISSE > PÉRIPHÉRIQUES, `hardware.cashchanger.enabled`, désactivé par défaut et présent sur la page des échelons. Au BackOffice, une période de coffre ouverte. Ouvrir une session caissier.

1. Réglage par défaut : aucune touche MONNAYEUR ; un envoi direct de `/action/pay-cashchanger` est refusé (« MONNAYEUR NON ACTIVÉ SUR CETTE CAISSE »).
2. LC-13-01-01 : activer le monnayeur ; niveaux simulés `low=200`, `full=NEARFULL`. Ouvrir la session : « MONNAYEUR : NIVEAU BAS 2,00 € - PRESQUE PLEIN » sur l’écran de vente.
3. LC-07-15-01/02 : vente de 3,72 €, RÈGLEMENT, MONNAYEUR : « PAIEMENT MONNAYEUR EN COURS », « À payer : 3,72 € », 0,00 €. Insérer 2 € : 2,00 € à l’écran, « Inséré : 2,00 € » sur la visu client. Avec l’arrondi des espèces, le montant demandé est le reste arrondi.
4. LC-07-15-01/03 : insérer 5 € : le monnayeur clôt le dépôt et rend 3,28 € ; règlement ESPECES 3,72 €, « RENDU CLIENT », tiroir fermé ; ticket « ESPECES 3,72 E / DONNE 7,00 E / RENDU MONNAIE 3,28 E ».
5. LC-07-15-04 : TERMINER : au BackOffice, coffre, « Remise au coffre — Prélèvement automatique monnayeur » de 3,72 €, à pointer ; aucune autorisation demandée.
6. LC-13-01-01 : niveaux `low=100,200`, `high=50`, `status=NEAREMPTY` : après la vente, « MONNAYEUR : NIVEAU BAS 1,00 € 2,00 € - NIVEAU HAUT 0,50 € - PRESQUE VIDE ». Monnayeur injoignable : « MONNAYEUR INJOIGNABLE ».
7. Vente suivante : MONNAYEUR, insérer 1 €, VALIDER L’INSÉRÉ : ESPECES 1,00 €, reste dû ; MONNAYEUR, insérer 0,50 €, ANNULER : « MONNAYEUR : MONTANT RENDU AU CLIENT », rien d’enregistré. Une règle bloquante du moyen Espèces refuse avant que la machine ne prenne quoi que ce soit. En formation, aucun monnayeur n’est sollicité.
8. Vérifier les libellés FR/NL/PT, les polices et le thème existants, et la lisibilité de l’écran de règlement et de la fenêtre monnayeur en 800 × 600, 1280 × 800 et 1366 × 768, sans défilement horizontal.

Recette exécutée le 23/10/2026 sur imedge (18070) et impos (18080) réels, profil dev, bases H2 mémoire jetables, monnayeur simulé. BackOffice : paramètre lu `false`, enregistré `true`, présent sur la page des échelons ; période « Octobre 2026 » ouverte. Ouverture de session sur une caisse fraîche : « MONNAYEUR : NIVEAU BAS 2,00 € - PRESQUE PLEIN ». Vente de 3,72 € en 800 × 600 : fenêtre monnayeur, « À payer : 3,72 € », 0,00 € puis 2,00 €, visu client « Reste à payer : 3,72 € — Inséré : 2,00 € » ; billet de 5 € : ESPECES 3,72 €, « RENDU CLIENT 3.28 € », tiroir CLOSED, ticket conforme ; après la vente, l’alerte de l’étape 6. BackOffice : mouvements et journal du coffre « Prélèvement automatique monnayeur 3.72 », à pointer. Vente de 2,76 € : 1,00 € validé, reste 1,76 € ; dépôt de 0,50 € annulé, rien d’enregistré. Fenêtre en 1280 × 800 et 1366 × 768 sans défilement horizontal. Aucun monnayeur physique : le pilote impos-hw (`jpos.CashChanger`, option `--cashchanger-name`) est testé sur un simulateur de contrôle JavaPOS.

Validation : impos 7 127 tests Java + 305 JavaScript ; imedge 4 166 Java + 11 JavaScript ; impos-hw 83 Java ; sans échec, erreur ou test ignoré. JaCoCo : impos 26 773 lignes et 13 576 branches ; imedge 20 160 lignes et 9 700 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %. La table des paramètres BackOffice intègre la nouvelle clé.

Cotation : LC-07-15-01, -02, -03, -04 et LC-13-01-01 promues par CODE de non couvertes à intégralement couvertes. Limites déclarées : aucun monnayeur physique éprouvé ; le standard JavaPOS ne signale que les niveaux globaux, les dénominations sont nommées quand le monnayeur les fournit au pont. Seuls L442 à L445, L668, leurs commentaires O et leurs hauteurs changent dans le classeur. PDF : pages 1–2 actualisées, page 41 ajoutée, pages 3–40 préservées. P1 : 422/477 intégrales (88,5 %), 14 partielles (2,9 %), soit 436/477 intégrales ou partielles (91,4 %). Ligne de caisses : 543 intégrales, 45 partielles, 142 non couvertes ; couverture pondérée 77,5 %. Campagne : 129 promotions, dont 122 CODE et 7 RELECTURE.


## HD_43 — LC-08-01-05, LC-08-01-06, LC-08-04-18, -19, -21, -22, -23, LC-05-07-03 : documents fiscaux portugais

Gain : la caisse portugaise émet elle-même les documents que l’administration fiscale exige — FS, FT, NC, Recibo —, numérotés, signés et chaînés, avec ATCUD et QR code fiscal ; la conversion FS → FT et le recibo se font en caisse, sans passage par le siège, et la carte achat sociale s’encaisse dans la même transaction.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé (TPE virtuel accepté par `/api/hardware/tpe/accept`, imprimante simulée). Dans PARAMÈTRES CAISSE > FISCAL, trois réglages : `fiscal.pt-documents` (désactivé par défaut), `fiscal.atcud-codes` (TYPE:CODE séparés par des points-virgules, administrable par caisse) et `fiscal.certificate-number` ; les deux nouvelles clés figurent sur la page des échelons. Clé de signature de la caisse : `pos.fiscal.pt.private-key` (PKCS#8 en Base64) ; absente en dev, une clé de développement est générée au démarrage. Au BackOffice, un article (Lait UHT 1L) porte l’attribut « carte achat sociale » ; la Baguette ne le porte pas. Ouvrir une session caissier.

1. Réglage par défaut : tickets inchangés, aucune touche CARTE SOCIALE ni FATURA, la touche FACTURE ouvre la facture A4 habituelle.
2. Activer `fiscal.pt-documents`, ATCUD `FS:AAJFJMVNTN;FT:AAJFJ3VNTN;NC:AAJFJ4VNTN;RC:AAJFJ5VNTN`, certificat `2468/AT`. LC-08-01-05 : vente Lait + Baguette, NIF 123456789 saisi, réglée en carte, IMPRIMER : sous l’identité du ticket « FATURA SIMPLIFICADA », « N° FS POS01/2026/00000001 » ; au pied « xxxx-Processado por programa certificado n.º 2468/AT », « ATCUD: AAJFJMVNTN-1 » et le QR fiscal `A:…*B:123456789*C:PT*D:FS*E:N*F:…*G:FS POS01/2026/00000001*H:AAJFJMVNTN-1*I1:PT*…*Q:xxxx*R:2468`. Sans NIF : « NIF CONSUMIDOR FINAL », `B:999999990`.
3. LC-08-04-18/19 : vente Lait, RÈGLEMENT, touche FATURA : écran DOCUMENTS FISCAUX ; VALIDER sans NIF : « LE NIF DU CLIENT EST OBLIGATOIRE ». Saisir NIF, nom et adresse au clavier tactile, VALIDER : retour au règlement, touche « ✓ FATURA ». Le ticket porte « FATURA », « N° FT POS01/2026/00000001 », le NIF, le nom et l’adresse, ATCUD AAJFJ3VNTN-1, et aucune FS. Depuis la vente, menu TICKET > FACTURE ouvre le même écran.
4. LC-08-01-06 : vente Lait + Baguette ; CARTE SOCIALE puis VALIDER sans montant : 2,76 € réglés (le montant éligible) ; une seconde fois : « CAS: AUCUN ARTICLE ELIGIBLE ». Carte bancaire pour 0,96 €. IMPRIMER : deux papiers coupés, du même ticket : FS …/00000002 (Lait, CARTE ACHAT SOCIALE 2,76) puis FS …/00000003 (Baguette, CARTE BANCAIRE 0,96), chacun avec sa TVA et ses mentions légales.
5. LC-08-04-21 : hors vente, TICKET > FACTURE, onglet FS VERS FATURA : date du jour et caisse proposées ; n° 9 : « DOCUMENT INTROUVABLE SUR CETTE CAISSE ». N° 2 et client : « DOCUMENTS EMIS : NC POS01/2026/00000001 - FT POS01/2026/00000002 » ; la NC (« REF. : FS …/00000002 », « MOTIF : ANNULATION, CONVERSION EN FATURA », aucun règlement) puis la FT du client sont imprimées. Une seconde conversion : « DOCUMENT DEJA ANNULE ».
6. LC-08-04-22 : onglet RECIBO, n° 1 : « DOCUMENTS EMIS : RC POS01/2026/00000001 », recibo imprimé (client, « REF. : FT …/00000001 », MONTANT RECU, règlement, QR `I1:0`). Une seconde demande : « RECIBO DEJA EMIS POUR CETTE FATURA ».
7. LC-05-07-03 : retour de la Baguette du ticket de l’étape 4 (aval superviseur) : le ticket de retour porte « NOTA DE CRÉDITO », « N° NC POS01/2026/00000002 », « REF. : FS POS01/2026/00000003 » (seul document portant l’article rendu), ATCUD AAJFJ4VNTN-2 et le QR.
8. LC-08-04-23 : chaque document (FS, FT, NC, RC) porte l’ATCUD de sa série et son rang, les 4 caractères de sa signature SHA-1/RSA chaînée au document précédent de la série, la mention de certification et le QR fiscal. En formation : « AUCUN DOCUMENT FISCAL EN MODE FORMATION ».
9. Vérifier les libellés FR/NL/PT, les polices et le thème existants, et la lisibilité de l’écran de règlement et de l’écran DOCUMENTS FISCAUX en 800 × 600, 1280 × 800 et 1366 × 768, sans défilement horizontal.

Recette exécutée le 23/10/2026 sur imedge (18070) et impos (18080) réels, profil dev, bases H2 mémoire jetables, matériel simulé. BackOffice au navigateur : `fiscal.pt-documents` lu `false` puis enregistré `true`, codes ATCUD et certificat `2468/AT` enregistrés, deux clés sur la page des échelons ; attribut carte achat sociale coché sur Lait UHT 1L. Caisse : étapes 2 à 7 jouées dans l’ordre, avec les numéros et messages ci-dessus, l’étape 3 par la touche FATURA du règlement ; touche FATURA, écran DOCUMENTS FISCAUX et saisie au clavier tactile en 800 × 600 ; écrans de règlement et DOCUMENTS FISCAUX en 1280 × 800 et 1366 × 768 sans défilement horizontal. Limites : signature par une clé de développement ; le NIF émetteur imprimé au QR (`A:`) est celui du magasin de la caisse, que la synchronisation ne met pas à jour depuis le BackOffice (12123456789 en dev). Aucune imprimante physique.

Validation : impos 7 195 tests Java + 312 JavaScript ; imedge 4 171 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 27 378 lignes et 13 948 branches ; imedge 20 166 lignes et 9 700 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %. La table des paramètres BackOffice intègre les deux nouvelles clés.

Cotation : LC-05-07-03, LC-08-01-05, LC-08-01-06, LC-08-04-18, -19, -21, -22 et -23 promues par CODE de non couvertes à intégralement couvertes. Seuls L306, L457, L458, L501, L502, L504, L505, L506, leurs commentaires O et leurs hauteurs changent dans le classeur. PDF : pages 1–2 actualisées, page 42 ajoutée, pages 3–41 préservées. P1 : 430/477 intégrales (90,1 %), 14 partielles (2,9 %), soit 444/477 intégrales ou partielles (93,1 %). Ligne de caisses : 551 intégrales, 45 partielles, 134 non couvertes ; couverture pondérée 78,6 %. Campagne : 137 promotions, dont 130 CODE et 7 RELECTURE.


## HD_43 — LC-06-02-01, -02, -04, -07, -08, -09, -10, -12, LC-08-04-15 : devis, commandes et bons de vente externes

Gain : les devis, commandes et bons de vente émis par l’outil de gestion commerciale se reprennent en caisse au scan de leur code-barre ; la caisse y prend les acomptes que le document impose ou permet, les ventile par taux de TVA, met le document à jour par API et imprime une facture d’acompte, puis solde le document dans la vente avec reprise automatique des acomptes ; plusieurs bons de vente se cumulent dans une même transaction.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé (TPE virtuel accepté par `/api/hardware/tpe/accept`, imprimante simulée). La caisse connaît le nœud magasin (`pos.recall.document-url`). Dans PARAMÈTRES CAISSE > DEVIS ET COMMANDES, trois réglages présents sur la page des échelons : `order.external-code-pattern` (vide par défaut), `order.recall-price-source` (DOCUMENT par défaut, ou CAISSE) et `order.deposit-invoice` (désactivé par défaut). L’outil externe dépose ses documents par `POST /api/orders` sur le nœud (jeton `X-Sync-Token` s’il est configuré) : `code`, `kind` (DEVIS, COMMANDE, BON_VENTE), `label`, `depositRule` (NONE, OPTIONAL, MANDATORY), `totalAmount`, `lines` (`ean`, `label`, `quantity`, `unitPrice`, `vatRate`, `deliveredQuantity`). Documents de la recette : DV-0001 devis à acompte obligatoire (lait 20 × 3,00 €, baguette 50 × 1,20 €, TVA 5,5 %) ; DV-0002 bon de vente (lait commandé 6 livré 4 à 1,15 €, baguette 2 à 1,10 €) ; DV-0003 bon de vente (baguette commandée 3 livrée 3 à 1,05 €) ; DV-0004 commande à acompte facultatif (lait 10 et baguette commandée 5 livrée 4 à 9,99 €). Ouvrir une session caissier.

1. Réglages par défaut : un code DV-0001 scanné n’est pas reconnu ; seuls les codes de commande créés en caisse le sont. Un dépôt portant une règle d’acompte inconnue est refusé (400 « Commande illisible »).
2. LC-06-02-01 : `order.external-code-pattern` = `DV-[0-9]{4}`, `order.deposit-invoice` activé. Scanner DV-0001 sur l’écran de vente : l’écran RAPPEL PAR CODE s’ouvre sur « DEVIS DV-0001 — DEVIS CUISINE MARTIN », colonnes Article / Cdé / Livré / P.U. / Total, « Total 120,00 € », « Déjà versé 0,00 € », « Reste dû 120,00 € », « ACOMPTE OBLIGATOIRE AVANT LE RÈGLEMENT », sans touche RÉGLER DANS LA VENTE ; un envoi direct de `/action/recall/document/settle` est refusé du même message.
3. LC-06-02-04/07, LC-08-04-15 : saisir 30,00 au pavé, ENREGISTRER L’ACOMPTE : « ACOMPTE ENREGISTRÉ », « Déjà versé 30,00 € », « Reste dû 90,00 € », touche RÉGLER DANS LA VENTE affichée. Imprimés : le justificatif « ACOMPTE », « MONTANT 30,00 E », « TVA ACOMPTE 5,50 % 1,56 E » et le code-barres, puis la « FACTURE D’ACOMPTE » « N° FACTURE POS01-FA2026-000001 » : raison sociale, adresse, SIRET, TVA intracom., commande DV-0001, articles, « TOTAL COMMANDE TTC 120,00 E », « ACOMPTE TTC 30,00 E », « TVA 5,50 % HT 28,44 TVA 1,56 », « RESTE A PAYER 90,00 E », mention d’exigibilité de la TVA.
4. LC-06-02-04 : 999,99 € : « ACOMPTE SUPÉRIEUR AU RESTE DÛ ». Second acompte de 20,00 € : « Déjà versé 50,00 € », « Reste dû 70,00 € » ; facture POS01-FA2026-000002 avec « DEJA VERSE 30,00 E » et « RESTE A PAYER 70,00 E ». Au nœud, `GET /api/orders/DV-0001` : acompte 50, TVA « 0.055:2.6 », statut PARTIALLY_PAID, reste 70.
5. LC-06-02-08 : RÉGLER DANS LA VENTE : articles chargés, total 120,00 €, reste à payer 70,00 € ; carte bancaire, IMPRIMER : ticket « TOTAL TTC 120,00 E », « TVA DÉJÀ PERÇUE SUR L’ACOMPTE 5,50 % 2,60 E », « REPRISE ACOMPTE 50,00 E », « CARTE BANCAIRE 70,00 E ». TERMINER : le nœud ne rappelle plus DV-0001 (404).
6. LC-06-02-09/10/12, LC-06-02-02 : nouvelle vente, scanner DV-0002 puis DV-0003 : trois lignes, « Commandé 6 · livré 4 » sous le lait (4 × 1,15 €), « Commandé 3 · livré 3 » sous la baguette de DV-0003 ; scanner de nouveau DV-0002 : « DOCUMENT DV-0002 DÉJÀ CHARGÉ DANS LA VENTE », rien d’ajouté. Total 9,95 €, carte, IMPRIMER : « QTE COMMANDEE 6 / LIVREE 4 » et « QTE COMMANDEE 3 / LIVREE 3 » sous leurs lignes. TERMINER : DV-0002 et DV-0003 soldés au nœud (404).
7. LC-06-02-08 : `order.recall-price-source` = CAISSE. Scanner DV-0004 depuis l’écran de vente : l’écran du document s’ouvre ; prix de la caisse (lait 2,76 €, baguette 0,96 €) au lieu des 9,99 € du document, « Total 31,44 € ». METTRE DE CÔTÉ revient à l’écran de rappel ; rappeler DV-0004 par sa saisie, RÉGLER DANS LA VENTE sans acompte : 10 × 2,76 € et 4 × 0,96 € (« Commandé 5 · livré 4 ») ; carte ; ticket conforme ; DV-0004 soldé au nœud.
8. LC-08-04-15 : création d’une commande en caisse (écran COMMANDE, acompte 2,00 €) : la facture d’acompte suit le justificatif dans la même séquence (POS01-FA2026-000003). En formation : « AUCUN ACOMPTE EN MODE FORMATION ».
9. Vérifier les libellés FR/NL/PT, les polices et le thème existants, et la lisibilité de l’écran du document et de l’écran de vente en 800 × 600, 1280 × 800 et 1366 × 768, sans défilement horizontal.

Recette exécutée le 24/10/2026 sur imedge (18070) et impos (18080) réels, profil dev, bases H2 mémoire jetables, matériel simulé. BackOffice au navigateur : trois réglages lus `''`, `DOCUMENT`, `false`, enregistrés `DV-[0-9]{4}`, `DOCUMENT` puis `CAISSE`, `true`, présents sur la page des échelons. Documents déposés par l’API du nœud ; règle `SOMETIMES` refusée (400). Étapes 2 à 8 jouées dans l’ordre avec les écrans, messages, montants et numéros ci-dessus, l’étape 1 et le refus en formation étant établis par les tests Java ; écran du document en 800 × 600, 1280 × 800 et 1366 × 768 sans défilement horizontal. Correctif nécessaire mis au jour par la recette : le taux de TVA des lignes de document au nœud était arrondi à deux décimales (5,5 % lu 6 %) ; il garde désormais quatre décimales. Limite : la saisie manuelle d’un code contenant un tiret n’est pas possible au clavier tactile alphabétique de l’écran RAPPEL (comportement antérieur) ; la recette l’a saisie par le formulaire. Aucune imprimante physique.

Validation : impos 7 243 tests Java + 312 JavaScript ; imedge 4 178 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 27 682 lignes et 14 128 branches ; imedge 20 216 lignes et 9 736 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %. La table des paramètres BackOffice intègre les trois nouvelles clés.

Cotation : LC-06-02-01, -02, -04, -07, -08, -09, -10, -12 et LC-08-04-15 promues par CODE de non couvertes à intégralement couvertes. Seuls L311, L312, L314, L317 à L320, L322, L498, leurs commentaires O et leurs hauteurs changent dans le classeur. PDF : pages 1–2 actualisées, page 43 ajoutée, pages 3–42 préservées. P1 : 439/477 intégrales (92,0 %), 14 partielles (2,9 %), soit 453/477 intégrales ou partielles (95,0 %). Ligne de caisses : 560 intégrales, 45 partielles, 125 non couvertes ; couverture pondérée 79,8 %. Campagne : 146 promotions, dont 139 CODE et 7 RELECTURE.

## HD_43 — LC-06-05-01 à -06, LC-13-06-09, -14, LC-08-06-11, LC-08-03-08, LC-08-02-02, LC-13-05-06 : selfscanning, langue du client fidèle, NIF, tickets techniques et formatés, mode école

Gain : les paniers construits sur les dispositifs de selfscanning se reprennent à n’importe quelle caisse par leur code, avec contrôle partiel tiré au sort ou demandé, contrôle total demandé, issu d’un écart ou provoqué, articles complémentaires et bons de réduction appliqués au paiement ; le visueur et le ticket passent dans la langue du client fidèle, y compris pour le ticket restitué par API ; les documents fiscaux portugais impriment toujours le code pays et le NIF, ou CONSUMIDOR FINAL ; les tickets techniques activés au BackOffice sont imprimés d’office ; tous les tickets formatés (client, point de vente, formation) sont mis à disposition des services tiers par le nœud ; les transactions du mode école remontent dans une table dédiée consultable au journal électronique, hors chiffre d’affaires. Correctifs : le clavier tactile de l’écran RAPPEL porte un tiret, le justificatif d’acompte nomme le document (DEVIS, BON DE VENTE…), et un schéma mis à jour (profil demo) reçoit la précision à quatre décimales du taux de TVA des lignes de document.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé (TPE virtuel accepté par `/api/hardware/tpe/accept`, imprimante simulée), bouchon IMFID local renvoyant la langue `nl-BE` du compte fidélité. La caisse connaît le nœud magasin (`pos.recall.document-url`). Au BackOffice, section SELFSCANNING, sur la page des échelons : `selfscan.code-pattern` (vide par défaut), `selfscan.partial-control-rate` (0 par défaut, borné à 0–100) et `selfscan.partial-control-items` (3 par défaut, au moins 1) ; section LANGUE : `language.fidelity-holder` (désactivé par défaut). Le dispositif dépose ses paniers par `POST /api/orders` sur le nœud avec `kind` SELF_SCAN, `control` (vide, NONE, PARTIAL ou TOTAL, toute autre valeur refusée en 400) et `vouchers` (`code`, `label`, `amount`). Paniers de la recette : SS00000002 (lait 2, baguette 2, huile d’olive 1, contrôle PARTIAL), SS00000003 (lait 3, baguette 1, sans contrôle demandé, bon de 0,50 €), SS00000004 (lait 2, baguette 1, contrôle NONE, bon « BON DE REDUCTION 1 EUR » de 1,00 €). Ouvrir une session caissier.

1. Réglages par défaut : un code SS00000004 scanné n’est pas reconnu. `selfscan.code-pattern` = `SS[0-9]{8}`.
2. LC-06-05-01, -02, -03 : scanner SS00000004 sur l’écran de vente : l’écran SELFSCANNING s’ouvre sur « AUCUN CONTRÔLE », les articles déclarés, le total et le bon « BON DE REDUCTION 1 EUR » à -1,00 €. INTÉGRER DANS LA VENTE : retour à la vente, articles chargés ; ajouter Café Grains 500g au scan. Payer : bon 1,00 € appliqué d’office, carte 6,60 € ; le ticket imprime le bon. TERMINER : le nœud ne rappelle plus SS00000004 (404).
3. LC-06-05-04 : saisir SS00000002 à l’écran RAPPEL au clavier tactile (tiret disponible) : « CONTRÔLE PARTIEL : RE-SCANNER 2 ARTICLE(S) — DEMANDÉ PAR L’APPAREIL ». Re-scanner le lait : « ARTICLE CONTRÔLÉ : LAIT UHT 1L (1/2) » ; INTÉGRER est refusé tant que le contrôle n’est pas achevé. Avec un taux de 100 %, un panier sans contrôle demandé est tiré au sort (« TIRÉ AU SORT »).
4. LC-06-05-05 : sur SS00000002, re-scanner un article non déclaré (Café) : « ÉCART SUR Café Grains 500g : CONTRÔLE TOTAL » ; re-scanner le reste : le tableau Déclaré / Contrôlé signale 2 écarts (BAGUETTE 2/1, Café 0/1). INTÉGRER : ticket POS01 construit sur le re-scan (LAIT 2, BAGUETTE 1, HUILE 6,90, Café 4,20, total 14,50 €).
5. LC-06-05-06 : rappeler SS00000003, CONTRÔLE TOTAL : « CONTRÔLE TOTAL PROVOQUÉ » ; tout re-scanner : 0 écart ; INTÉGRER, bon 0,50 € + carte 4,05 €, total 4,55 €. METTRE DE CÔTÉ laisse le panier rappelable.
6. LC-13-06-09, -14 : `language.fidelity-holder` activé ; nouvelle vente, identifier la carte fidélité du client néerlandophone : le visueur client passe en néerlandais (« TOTAAL ») ; ticket imprimé en néerlandais (« TOTAAL INCL. BTW », « BANKKAART », « DANK VOOR UW BEZOEK ») ; le ticket dématérialisé de cette vente s’affiche en néerlandais (`html lang="nl"`) ; la langue remonte au nœud avec le ticket.
7. LC-08-06-11 : `fiscal.pt-documents` activé : FS au consommateur final « NIF CONSUMIDOR FINAL », code QR `B:999999990*C:PT` ; FS au NIF 123456789 « NIF PT 123456789 » ; même règle sur FT, Recibo et Nota de Crédito.
8. LC-08-03-08, LC-08-02-02 : impression conditionnelle activée ; mouvement de caisse DÉPENSE 2,00 € TIMBRES : ticket imprimé d’office. Au nœud, `GET /api/printouts?audience=STORE` (jeton `X-Sync-Token`) rend le ticket du mouvement, source `TicketPrinterService.printMovementTicket`, contenu Base64URL décodé « MOUVEMENT DE CAISSE … » ; `audience=CUSTOMER` rend tickets de vente et tickets carte ; `after=<dernier id>` ne rend plus rien ; une audience inconnue est refusée (400).
9. LC-13-05-06 : en mode formation, vente de 3,72 € : BackOffice > Journal électronique, onglet « Transactions école » : date, caisse POS01, opérateur jdupont, 2 articles, 3,72 € ; le détail montre le ticket de formation ; la transaction est absente du journal transactionnel et du chiffre d’affaires.
10. Correctifs : BV-0005 saisi avec son tiret au clavier tactile de l’écran RAPPEL se charge ; un acompte de 5,00 € sur le devis DV-0006 imprime « DEVIS DV-0006 » sur le justificatif ; une base H2 fichier dont `customer_order_lines.vat_rate` est NUMERIC(6,2), démarrée en `update`, passe à NUMERIC(6,4).
11. Vérifier les libellés FR/NL/PT, les polices et le thème existants, et la lisibilité de l’écran SELFSCANNING et de l’écran de vente en 800 × 600, 1280 × 800 et 1366 × 768, sans défilement horizontal.

Recette exécutée le 24/10/2026 sur imedge (18070) et impos (18080) réels, profil dev, bases H2 mémoire jetables, matériel simulé, bouchon IMFID (8060). BackOffice au navigateur : section SELFSCANNING et `language.fidelity-holder` lus à leur défaut, enregistrés, présents sur la page des échelons ; contrôle `SOMETIMES` refusé (400). Étapes 2 à 10 jouées dans l’ordre avec les écrans, messages, montants et numéros ci-dessus ; étape 1 et tirage au sort établis par les tests Java ; écran SELFSCANNING en 800 × 600, 1280 × 800 et 1366 × 768 sans défilement horizontal. Correctifs mis au jour par la recette : un indicateur de panier à contrôler restait levé après intégration ou mise de côté et ramenait l’écran de vente vers SELFSCANNING ; il est désormais effacé ; le détail d’une transaction école au journal montrait le jeton du logo de l’imprimante, il en est retiré (test Java). Contrôle de schéma sur base H2 fichier démarrée en `update` : 1 alignement appliqué, colonne lue NUMERIC(6,4). Limite : le contrôle des tickets techniques s’est appuyé sur le mouvement de caisse. Aucune imprimante physique.

Validation : impos 7 308 tests Java + 319 JavaScript ; imedge 4 199 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 28 129 lignes et 14 372 branches ; imedge 20 418 lignes et 9 828 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %. La table des paramètres BackOffice intègre les quatre nouvelles clés.

Cotation : LC-06-05-01 à -06, LC-13-05-06, LC-13-06-09 et -14 promues par CODE de non couvertes à intégralement couvertes ; LC-08-02-02 et LC-08-06-11 promues par CODE de partiellement à intégralement couvertes ; LC-08-03-08 promue par RELECTURE de partiellement à intégralement couverte (activations BackOffice des tickets techniques apportées par les lots antérieurs, impression d’office prouvée par la recette). Seuls L340 à L345, L460, L478, L534, L703, L712, L717, leurs commentaires O et leurs hauteurs changent dans le classeur. PDF : pages 1–2 actualisées, page 44 ajoutée, pages 3–43 préservées. P1 : 451/477 intégrales (94,5 %), 11 partielles (2,3 %), soit 462/477 intégrales ou partielles (96,9 %). Ligne de caisses : 572 intégrales, 42 partielles, 116 non couvertes ; couverture pondérée 81,2 %. Campagne : 158 promotions, dont 150 CODE et 8 RELECTURE.

## HD_43 — LC-02-09-01 à -03, LC-05-03-01, LC-07-11-01, -03, LC-04-01-03, LC-05-07-02, LC-09-01-04 : hub de paiement Nexo, inaltérabilité des tickets en attente, détail des retours, programme externe

Gain : la caisse dialogue avec le hub de paiement externe en protocole Nexo Retailer pour activer, annuler, imprimer et reprendre les articles dématérialisés, et pour payer par carte cadeau après lecture du solde au hub ; chaque mise en attente, reprise et purge de ticket en attente laisse un enregistrement signé et chaîné, vérifié et consultable au nœud ; le ticket de retour détaille le décagnottage et les bons retirés, et un ticket mêlant vente et retour imprime le détail des retours à part ; une touche du menu AUTRE lance le programme externe administré au BackOffice. Correctifs mis au jour par la recette : le message de l’article dématérialisé de l’écran de retour est affiché au-dessus des lignes, et le détail des retours accompagne aussi le ticket imprimé à la demande.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé (TPE virtuel accepté par `/api/hardware/tpe/accept`, imprimante simulée), bouchon IMFID local (gains CAGNOTTE 5 % 0,62 € et BON 5 EUR 5,00 € sur un panier de plusieurs articles, 0,14 € de cagnotte sur un article). La caisse connaît le hub par `pos.nexo.hub-url` (absent : aucun hub, comportement antérieur) ; le hub simulé du profil dev (`/api/nexo`, `GET /api/nexo/cards/{code}` pour lire un compte) connaît les cartes cadeau 6299000000000017 (30,00 €, ILLICADO) et 6299000000000025 (0,00 €) et refuse l’activation des codes commençant par 9999. Au BackOffice, sur la page des échelons : `giftcard.payment-ticket` (section CARTE CADEAU, activé par défaut), `return.separate-detail` (section RETOUR CLIENT, désactivé par défaut), `external-program.label` (défaut « PROGRAMME EXTERNE »), `external-program.path` et `external-program.arguments` (vides par défaut, section PROGRAMME EXTERNE). Fiches articles : Set de Couteaux Chef (3300000000033, 30,00 €) en « E-CARTE CADEAU 30 », « Produit dématérialisé » coché, attribut personnalisé ACTIVATION_PROVIDER = ILLICADO ; Poêle Antiadhésive (3300000000031) en « E-CARTE CADEAU LIBRE », dématérialisé, « Prix à saisir en caisse », ACTIVATION_PROVIDER = TRANSACTIS. Script de recette `cash-management.sh` exécutable, qui note ses arguments. Ouvrir une session caissier.

1. Réglages par défaut : sans `external-program.path`, le menu AUTRE ne montre aucune touche de programme. Administrer `CASH MANAGEMENT`, le chemin du script et `--till POS01`.
2. LC-09-01-04 : menu AUTRE : touche CASH MANAGEMENT ; la toucher : « PROGRAMME LANCÉ : CASH MANAGEMENT », le script note « CASH MANAGEMENT --till POS01 ». Un chemin introuvable ou non exécutable est signalé (« PROGRAMME EXTERNE INTROUVABLE : … »).
3. LC-02-09-01 : scanner 3300000000033 : « SCANNEZ LE CODE D’ACTIVATION » ; scanner 99990001 : « ACTIVATION REFUSÉE (ACTIVATION REFUSEE PAR LE FOURNISSEUR) : ARTICLE ANNULÉ », ligne barrée. Rescanner l’article puis 9001000000001 : « ARTICLE ACTIVÉ - CODE 9001000000001 - SOLDE 30,00 € », la ligne affiche « CODE D’ACTIVATION : 9001000000001 » ; le hub montre la carte active à 30,00 €. Scanner 3300000000031 : invite PRIX À SAISIR, 15,00, puis 9001000000005 : activée à 15,00 € chez TRANSACTIS.
4. LC-02-09-02 : activer 9001000000002, ANNULER LIGNE sur cette ligne : « ACTIVATION ANNULÉE - CODE 9001000000002 », carte inactive au hub. Activer 9001000000003 puis abandonner le ticket (aval manager) : carte inactive au hub.
5. LC-02-09-03 : payer et terminer : après le ticket de caisse, TICKET D’ACTIVATION : ticket, article, CODE ACTIVATION 9001000000001, MONTANT 30,00 E, ANCIEN SOLDE 0,00 E, NOUVEAU SOLDE 30,00 E, FOURNISSEUR ILLICADO, CODE FOURNISSEUR S1, TRANSACTION HUB00000001.
6. LC-05-03-01 : consommer 10,00 € sur 9001000000001 hors caisse (requête Payment au hub). Écran de retour, ticket de la vente, + sur la ligne : « SCANNEZ LE CODE UNIQUE DE L’ARTICLE DÉMATÉRIALISÉ » ; saisir 9001000000099 : « CE CODE N’EST PAS CELUI DE L’ARTICLE VENDU » ; saisir 9001000000001 : « SOLDE RESTANT 20,00 € : 20,00 € REMBOURSÉS À LA DÉSACTIVATION », montant du retour 20,00 € ; motif, ESPÈCES, aval : TICKET DE RETOUR « E-CARTE CADEAU 30 1,00 x 20,00 », TOTAL REMBOURSE 20,00 E ; la carte est déchargée et inactive au hub.
7. LC-07-11-01 : vendre Casserole Inox (18,00 €), RÈGLEMENT, CARTE CADEAU, saisir 6299000000000017, VALIDER LA CARTE : panneau CARTE CADEAU « ****0017 · ILLICADO », SOLDE DISPONIBLE 30,00 €, MONTANT À DÉBITER proposé 18,00 ; saisir 12,40, PAYER : reste 5,60 €, solde du hub 17,60 €. Annuler ce règlement : solde du hub 30,00 €, reste 18,00 €. La carte 6299000000000025 : « CARTE CADEAU SANS SOLDE » ; 15,00 € demandés sur une carte à 12,00 € : « MONTANT SUPÉRIEUR AU SOLDE DE LA CARTE (12,00 €) » ; un montant supérieur au reste dû est ramené au reste dû.
8. LC-07-11-03 : avec `giftcard.payment-ticket` activé, le paiement imprime PAIEMENT CARTE CADEAU : CARTE ****0017, MONTANT 12,40 E, ANCIEN SOLDE 30,00 E, SOLDE RESTANT 17,60 E, FOURNISSEUR ILLICADO, CODE FOURNISSEUR P5, TRANSACTION HUB00000005. Paramètre désactivé : aucun ticket de paiement carte cadeau.
9. LC-04-01-03 : mettre en attente un ticket de deux articles, le reprendre, ajouter un article, le remettre en attente ; clôture Z avec aval : le ticket est purgé. BackOffice > Journal électronique, onglet « Tickets en attente » : chaîne POS01 « INTÈGRE (4 enregistrement(s)) », lignes MISE EN ATTENTE (2 articles, 3,72 €), REPRISE, MISE EN ATTENTE (3 articles, 4,32 €), PURGE À LA CLÔTURE (opérateur CLOTURE Z), chacune avec sa signature. Un enregistrement altéré ou un maillon faux rend la chaîne ROMPUE.
10. LC-05-07-02 : vente fidélisée (carte 2990000000019) de Lait et Casserole ; retour total : le TICKET DE RETOUR imprime, sous RECALCUL DES AVANTAGES, « DECAGNOTTAGE, BONS ET COUPONS RETIRES », « Cagnotte CAGNOTTE 5 % -0,62 E », « Bon d’achat BON 5 EUR DES -5,00 E », « TOTAL RETIRE -5,62 E ». `return.separate-detail` activé : vente de Casserole et retour de Lait sur le même ticket : le ticket ne porte que « VALEUR DES RETOURS -2,76 E », suivi du ticket DETAIL DES RETOURS (LAIT UHT 1L, -1 x 2,76 E, TOTAL RETOURS -2,76 E, recalcul des avantages).
11. Vérifier les libellés FR/NL/PT, les polices et le thème existants, et la lisibilité de l’écran de vente (code d’activation), du panneau de carte cadeau, de l’écran de retour et du menu AUTRE en 800 × 600, 1280 × 800 et 1366 × 768, sans défilement horizontal.

Recette exécutée le 24/10/2026 sur imedge (18070) et impos (18080) réels, profil dev, bases H2 mémoire jetables, matériel simulé, bouchon IMFID (8060), hub Nexo simulé de la caisse. BackOffice au navigateur : les cinq réglages lus à leur défaut, enregistrés, présents sur la page des échelons ; fiches articles enregistrées. Étapes 2 à 10 jouées avec les écrans, messages, montants et numéros ci-dessus ; étape 1, programme introuvable, maillon rompu et retour partiel fidélisé établis par les tests Java ; écrans de l’étape 11 sans défilement horizontal aux trois résolutions. Correctifs mis au jour par la recette : le message de l’article dématérialisé de l’écran de retour était rangé sous les touches de règlement, hors de l’écran en 800 × 600 et 1280 × 800 ; il est affiché au-dessus des lignes du ticket. Le détail des retours n’accompagnait que le ticket imprimé d’office ; il suit aussi le ticket imprimé à la demande ou choisi à la fin de la vente (tests Java). Limites : le moteur de valorisation simulé ne chiffre pas le panier conservé, d’où un retour partiel fidélisé sans retrait en recette, prudence établie par les tests Java ; aucune imprimante physique, aucun hub réel.

Validation : impos 7 385 tests Java + 319 JavaScript ; imedge 4 207 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 29 148 lignes et 14 826 branches ; imedge 20 535 lignes et 9 874 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %. La table des paramètres BackOffice intègre les cinq nouvelles clés.

Cotation : LC-02-09-01 à -03, LC-04-01-03, LC-05-03-01, LC-05-07-02, LC-07-11-01 et -03 promues par CODE de partiellement à intégralement couvertes ; LC-09-01-04 promue par CODE de non couverte à intégralement couverte. Seuls L108 à L110, L236, L290, L305, L427, L429, L538, leurs commentaires O et leurs hauteurs changent dans le classeur. PDF : pages 1–2 actualisées, page 45 ajoutée, pages 3–44 préservées. P1 : 460/477 intégrales (96,4 %), 3 partielles (0,6 %), soit 463/477 intégrales ou partielles (97,1 %). Ligne de caisses : 581 intégrales, 34 partielles, 115 non couvertes ; couverture pondérée 81,9 %. Campagne : 167 promotions, dont 159 CODE et 8 RELECTURE.

## HD_43 — BO-03-06-01, -08, -10, -30 à -38, -45, -48, -51, -54, -56, -57, -59, -62, -63, -64 : gestion des codes-barres

Gain : au Back Office, une plage de codes-barres de paiement se rattache au bon d’achat ou au titre-restaurant du référentiel des moyens de paiement ; une plage REGISTRY devient plage d’émission et la caisse compose de ses positions (TPV, n° de ticket, heure, dates, montant, séquence, magasin) le numéro des avoirs et cartes cadeau qu’elle imprime en code-barres ; une plage FONCTION ouvre une fonction caisse ; la carte superviseur se crée, s’imprime et vaut aval ; le nœud récupère par FTP les fichiers des balances ; les instruments émis et les codes de paiement lus remontent au nœud et se consultent au journal électronique. En caisse : symbologies administrées (2D, Code 128 alphanumérique d’article jusqu’à 38 caractères), clé de Luhn des cartes de fidélité, alerte de lecture hors ligne, alerte de bon inconnu, GS1 AI 17 forçable par un superviseur avec messages administrés, paniers qbusting. Correctifs mis au jour par la recette : la création au BackOffice d’un moyen de paiement, d’une plage de codes-barres ou d’une plage article échouait (ligne insérée avant d’être décrite) ; la page Comptes ne s’affichait plus (`page.items` au lieu de `page.rows`) ; une lecture des paramètres depuis un fil sans contexte de requête du nœud (flux de tickets) laissait le nœud publier les valeurs par défaut aux caisses jusqu’au prochain enregistrement ; le journal d’un titre papier porte le montant réellement réglé.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé, nœud imedge et deux caisses POS01 et POS02, serveur FTP local (utilisateur balance). Au BackOffice, page des échelons : `fidelity.card-luhn` (FIDÉLITÉ, désactivé), `gs1.expiry-block-days` (0), `gs1.expiry-message` et `gs1.short-date-message` (vides), `gs1.expiry-alert` accepte SUPERVISOR, `qbusting.code-pattern` (QBUSTING, vide), `scan.symbologies` (SCAN, vide), `scan.offline-alert` (NONE / INFO / BLOCK, défaut NONE), `voucher.unknown-alert` (INFO / BLOCK, défaut BLOCK), `balance.ftp-host`, `-port` (21), `-user`, `-password`, `-directory` (/), `-interval-seconds` (0) (BALANCE). Administrer : luhn activé, GS1 SUPERVISOR, 1 jour de refus, 5 jours de date courte, « PRODUIT PÉRIMÉ LE {date} », « DATE COURTE : {date} », `^QB\d{6}$`, `EAN13;EAN8;CODE128;GS1;QR`, INFO, INFO, FTP 127.0.0.1:2121 /drops toutes les 10 s. Moyens de paiement 900101 VOUCHER « Bon d’achat magasin » et 900102 TR « Titre restaurant ». Plages : CADEAU_COMPOSE (98, 24 chiffres, REGISTRY, émission GIFT_CARD ; TPV 3–4, ticket 5–10, date DDMMYY 11–16, montant 17–21, séquence 22–24) ; TR_PAPIER (77, 14 chiffres, montant encodé 11–14, moyen 900102) ; plages article FONCTION FN_ATTENTE (23, fonction tickets en attente) et FN_NUMERO (24, fonction lue dans la zone article). Carte superviseur d’Albert Einstein (PICKER) créée à l’écran Comptes.

1. BackOffice : rattacher une plage à CB : « Moyen de paiement inconnu pour une plage scannée. » ; déclarer émettrice une plage non REGISTRY : refus ; plage FONCTION nommant LOTTERY : « Fonction caisse inconnue : LOTTERY. » La carte superviseur imprimable porte le nom et le Code 128 du badge, relu « 22222222 » ; la colonne propose ensuite Imprimer et Retirer.
2. BO-03-06-54 : scanner 2990000001231 : « CARTE FIDÉLITÉ REFUSÉE : CLÉ DE CONTRÔLE INVALIDE » ; 2990000001230 : client identifié.
3. BO-03-06-01, -10 : `]d1ABC123` : « SYMBOLOGIE NON ACCEPTÉE : DATAMATRIX » ; `]E03300000000001` : POMMES GOLDEN ; article ART2024QR importé au nœud : `]Q1ART2024QR` et `]C0ART2024QR` l’enregistrent ; ART2024XX reste « CODE INCONNU ».
4. BO-03-06-64, -51 : GS1 `]C1010330000000000517<J-2>` : « PRODUIT PÉRIMÉ LE … » et AUTORISATION REQUISE ; aval de jdupont : « AUTORISATION REFUSÉE » ; aval d’aeinstein (porteur de carte) : article enregistré. GS1 à J+3 : « DATE COURTE : … », article enregistré.
5. BO-03-06-30 à -38, -62 : vendre la carte cadeau 3400025000001 en POS01 : numéro 980100000224102602500002 (98 | 01 | 000002 | 241026 | 02500 | 002) imprimé en clair et `[[BARCODE …]]` ; ajouter TIME HHMM puis HHMMSS en 25–28 / 25–30 : 9801000001241026025000011417 et 980100000224102602500002141838. Journal, onglet « Codes-barres imprimés » : POS01, transaction, numéro, 25,00 €, date d’impression, expiration, solde.
6. BO-03-06-48, -63 : en POS02, articles 3,84 €, RÈGLEMENT, scanner la carte émise par POS01 : réglée 3,84 € « Carte cadeau composée » ; le nœud porte le solde 21,16 € sous l’émetteur POS01. Scanner 989999999999999999999999 : « BON INCONNU DU SYSTÈME : AUCUN RÈGLEMENT » ; `voucher.unknown-alert` BLOCK : « BON INCONNU AU REGISTRE ». Journal, onglet « Codes-barres passés » : TPV, transaction, plage, montant, expiration, date d’utilisation, trouvé OUI / NON.
7. BO-03-06-08 : scanner 77123456780850 en règlement : « Titre restaurant » 2,16 € (assiette éligible), journalisé 2,16 €.
8. BO-03-06-59 : 2300000000000 ouvre l’écran des tickets en attente, 2400005000000 le rappel de document, 2400001000000 la métrologie ; 2400099000000 : « FONCTION CAISSE INCONNUE POUR CE CODE ».
9. BO-03-06-57 : déposer au nœud la commande QBUSTING QB000123 (2 lignes) ; la scanner : « QBUSTING QB000123 CHARGÉ » ; QB999999 : « CODE INCONNU ».
10. BO-03-06-45 : nœud suspendu, attendre l’échec du tirage : scanner un article : « CAISSE HORS LIGNE : CODE TRAITÉ SANS LE NOEUD MAGASIN », article enregistré ; en BLOCK : « CAISSE HORS LIGNE : CODE REFUSÉ », article non enregistré ; nœud revenu : lecture normale.
11. BO-03-06-56 : déposer dans /drops un fichier `reference;counter;ean;label;quantity;unit;price` (sachet 0000004242, 2 lignes) : lu et renommé `.done` ; scanner 2700000042427 : « TICKET COMPTOIR 0000004242 INTÉGRÉ (2 LIGNE(S)) ».
12. Vérifier les libellés FR/NL/PT, les polices et le thème existants, et la lisibilité de l’écran d’aval, de l’écran de règlement et de l’écran de vente en 800 × 600, 1280 × 800 et 1366 × 768, sans défilement horizontal.

Recette exécutée le 24/10/2026 sur imedge (18070), impos POS01 (18080) et POS02 (18081) réels, profil dev, bases H2 mémoire jetables, matériel simulé, bouchon IMFID (8060), serveur FTP pyftpdlib (2121). BackOffice au navigateur : les quatorze nouveaux réglages lus à leur défaut, enregistrés avec `gs1.expiry-alert` et `gs1.expiry-warn-days`, présents sur la page des échelons ; nouvelle plage créée à l’écran, listes « Moyen de paiement », « Plage d’émission » et « Fonction caisse » renseignées ; carte superviseur imprimée et décodée. Étapes 1 à 11 jouées avec les écrans, messages, montants et numéros ci-dessus ; sondage par code et programme externe par code établis par les tests Java. Écrans de caisse sans défilement horizontal aux trois résolutions ; les pages BackOffice débordent en 800 × 600 par l’en-tête commun, comme toutes les pages BackOffice existantes. Limites : le nœud n’interroge aucune base centrale des instruments ; acomptes et bons fidélité ne figurent pas au journal des codes imprimés ; aucune imprimante ni balance physique.

Validation : impos 7 446 tests Java + 319 JavaScript ; imedge 4 246 Java + 11 JavaScript, sans échec, erreur ou test Java ignoré. JaCoCo : impos 29 608 lignes et 15 134 branches ; imedge 20 913 lignes et 10 056 branches ; tous les compteurs manqués sont à zéro. JavaScript externe instrumenté à 100 %. La table des paramètres BackOffice intègre les quatorze nouvelles clés.

Cotation (feuille Back Office) : BO-03-06-01, -08, -10, -30 à -34, -37, -38, -45, -51, -54, -56, -59, -63 et -64 promues par CODE de partiellement à intégralement couvertes ; BO-03-06-57 promue par CODE de non couverte à intégralement couverte ; BO-03-06-48 et -62 restent partielles, commentaire actualisé. Seuls L236, L243, L245, L262 à L266, L269, L270, L277, L283, L286, L288, L289, L291, L295, L296, les commentaires O de ces lignes et de L280 et L294 et leurs hauteurs changent dans le classeur. PDF : pages 1–2 actualisées, page 46 ajoutée, pages 3–45 préservées. Back Office P1 : 407/704 intégrales (57,8 %), 223 partielles (31,7 %), soit 630/704 intégrales ou partielles (89,5 %) ; toutes priorités : 437 / 266 / 93, couverture pondérée 71,6 %. Ligne de caisses inchangée : P1 460/477. Campagne : 185 promotions, dont 177 CODE et 8 RELECTURE.

## HD_43 — BO-01-01-03, BO-01-02-01, -03, -06, -09, -10, BO-01-03-03, -04, BO-01-04-01, -02, -03, -05, -06, -08 : comptes, authentification, profils et accès

Gain : le contrôle d’accès par profil couvre tout le BackOffice : chacun des 212 gestes des écrans déclare sa fonctionnalité (46 au catalogue) et son option (visualisation, création, modification, suppression), un filtre commun interroge les profils de l’utilisateur sur les échelons de ses affectations et répond « Droit refusé » (403) sinon ; plus aucun écran ne repose sur un rôle fixe. Une option se soumet à validation, par l’utilisateur ou par un compte d’un profil valideur ; refus, validations et modifications s’écrivent au Journal d’administration (écran, filtre, extraction CSV). Le profil porte un niveau hiérarchique : un compte n’attribue que des profils de son niveau ou d’un niveau inférieur, à des comptes d’un niveau strictement inférieur. Un compte se rattache à des échelons (pays, enseigne, PDV, type de point de contact, point de contact) ; la création sans code génère badge, PIN et mot de passe provisoire sur une fiche imprimable ; l’action « Réinitialiser » régénère PIN et/ou mot de passe. Authentification OpenID Connect (SSO du groupement) à côté du mot de passe, réglée par compte et par échelon (`auth.bo-sign-in`). Le nœud central distribue aux PDV comptes, profils et affectations de leurs échelons ; le référentiel EMPLOYEES d’une caisse ne porte plus que les comptes qu’elle admet. Menu « Favoris » en tête de la navigation, menus, fonctionnalités et types de rapport masquables. Correctifs mis au jour par la recette : création d’un compte sans badge ou sans prénom (erreur 500), mot de passe distribué haché deux fois à l’application du référentiel, atterrissage sur le tableau de bord refusé à un compte à profil (il arrive désormais sur le premier écran de son menu).

Préconditions : construction dev, H2 mémoire jetable, matériel simulé ; nœud central imedge (18071, rôle central), nœud PDV 07039 (18070, `pos.central-url` vers le central, `pos.sso.enabled=true`), fournisseur OIDC simulé (18099, jetons RS256, JWKS), caisse impos POS01 (18080) rattachée au nœud PDV. Au BackOffice, page des échelons : `auth.bo-sign-in` (SESSION CAISSE, ANY / PASSWORD / SSO, défaut ANY).

1. BO-01-02-06 : écran Comptes, créer Paule Lefort sans badge, PIN ni mot de passe : fiche « Identifiants de Paule Lefort » avec badge en Code 128, PIN à 4 chiffres et mot de passe provisoire de 10 caractères ; créer sans prénom : « Le prénom est obligatoire. »
2. BO-01-03-03 : créer les profils Direction (niveau 5), Chef de caisse (niveau 3), Marketing (niveau 1, « Tout le menu » Articles, création des sélections soumise à validation par l’utilisateur, suppression des attributs soumise à validation par le profil Chef de caisse).
3. BO-01-03-04 : affecter Chef de caisse à Alice Martin sur PDV:07039, Marketing à Paule sur ENSEIGNE:ITM ; une affectation sur TPV:POS01 : « Échelon invalide : un pays, une enseigne, un point de vente ou « * ». »
4. Première connexion de Paule : redirigée vers `/admin/password`, changement accepté.
5. BO-01-04-01, -02, -06 : navigation de Paule limitée au menu Articles ; Articles, Sélections, Attributs : 200 ; Paramètres caisse et Comptes : 403 « Droit refusé » ; création de campagne : 403.
6. BO-01-04-03 : créer la sélection PROMO NOEL : fenêtre « Validation requise » ; mot de passe faux : « Validation refusée » ; bon mot de passe : « Sélection « PROMO NOEL » enregistrée. »
7. BO-01-04-05 : créer l’attribut BIO (sans validation) puis le supprimer : fenêtre de validation ; identifiants de Paule : refus (pas le profil valideur) ; identifiants du chef : « Attribut « BIO » supprimé. »
8. Écran Journal d’administration filtré sur paule : 8 lignes (2 refus d’écran, 1 refus de création, 2 validations refusées, 3 gestes effectués avec leur valideur) ; extraction CSV de 9 lignes (en-tête compris).
9. BO-01-03-03 : le chef affecte Direction à Paule : « Le profil « Direction » est d’un niveau supérieur au vôtre. » ; Marketing : accepté ; sur l’admin : « Vous n’administrez que les comptes d’un niveau inférieur au vôtre. » ; créer un compte de niveau 3 : refus.
10. BO-01-02-03 : le chef crée hote1 (TPV:POS01) et hote2 (TPV:POS02), fiches générées.
11. BO-01-02-10 : le chef réinitialise Paule : « Secrets réinitialisés : remettez-les au titulaire. », ancien mot de passe refusé, provisoire imposant le changement ; réinitialiser l’admin ou son propre compte : refus.
12. BO-01-04-08 : écran Mes favoris (admin) : Comptes et Journal d’administration en favoris, menu Financier et Codes postaux masqués, un type de rapport masqué : navigation « Favoris, Paramétrage, Articles, Administration, Supervision », 15 puis 14 rapports listés, le rapport masqué reste accessible (200).
13. BO-01-02-01 : page de connexion, « Se connecter avec le SSO du groupement » : Paule connectée par le fournisseur, Sélections 200, Paramètres caisse 403 ; identité inconnue : « Aucun compte actif n’admet la connexion SSO pour cette identité. »
14. BO-01-02-09 : Paule en « SSO seulement » : mot de passe refusé, SSO admis ; `auth.bo-sign-in` à PASSWORD : pas de bouton SSO, SSO refusé (« La connexion SSO n’est pas activée sur ce nœud. ») ; à SSO : l’admin refusé par mot de passe, admis par SSO ; retour à ANY.
15. BO-01-01-03 : au central, profil Marketing enseigne, comptes mkt.itm (ENSEIGNE:ITM) et pdv.autre (PDV:07040), affectation ; le central remet mkt.itm au PDV 07039 et pdv.autre au PDV 07040 seulement ; après le tirage du PDV 07039 : mkt.itm présent avec le badge « central », connecté avec le mot de passe du central et routé vers le premier écran de son menu, changement de mot de passe refusé au PDV ; pdv.autre absent.
16. BO-01-02-03 : référentiel EMPLOYEES de POS01 : hote1 présent, hote2 absent ; en caisse POS01, hote1 prend son poste par identifiant et par badge avec le PIN généré, hote2 refusé.
17. Vérifier les polices et le thème existants et la lisibilité des écrans Comptes, Profils, Affectations, Journal d’administration, Mes favoris, fenêtre de validation et page de connexion en 800 × 600, 1280 × 800 et 1366 × 768, sans défilement horizontal du contenu.

Recette exécutée le 24/10/2026 sur un nœud central imedge (18071), un nœud PDV 07039 (18070) et la caisse impos POS01 (18080), profil dev, bases H2 mémoire jetables, matériel simulé, fournisseur OIDC simulé (18099) ; scénario joué après le premier tirage des référentiels du PDV (l’échelon ENSEIGNE:ITM ne se résout qu’une fois le PDV connu du nœud). Étapes 1 à 16 jouées avec les messages, codes HTTP et listes ci-dessus ; fiches générées (badge Code 128, PIN, mot de passe provisoire) ; journal de 8 lignes et CSV de 9 lignes ; Paule et mkt.itm arrivent sur `/admin/articles`. Écrans Comptes, fiche d’identifiants, profil, Utilisateurs, Journal d’administration, Mes favoris, Droit refusé, fenêtre de validation et page de connexion sans défilement horizontal du contenu aux trois résolutions ; en 800 × 600 l’en-tête commun du BackOffice déborde, comme sur toutes les pages BackOffice existantes. Correctif de recette supplémentaire : la grille des droits du profil débordait de 31 px en 800 × 600 (colonne Validation resserrée sous 900 px). Limites : un point de contact (TPV:…) ne se choisit dans la liste des échelons d’un compte qu’une fois déclaré au référentiel des points de caisse ; la caisse n’impose le changement du PIN généré à la première prise de poste que si `auth.password-expiry-days` est administré (BO-01-02-06 reste partielle) ; aucun fournisseur d’identité réel.

Validation : imedge 4 329 tests Java + 11 JavaScript, sans échec, erreur ou test ignoré. JaCoCo : 21 947 lignes et 10 844 branches, tous les compteurs manqués à zéro. JavaScript externe instrumenté à 100 %. Un test d’architecture vérifie que chaque geste des écrans BackOffice déclare sa fonctionnalité ; la table des paramètres BackOffice intègre `auth.bo-sign-in`. Aucun fichier impos modifié.

Cotation (feuille Back Office) : BO-01-01-03, BO-01-02-03, -09, -10, BO-01-03-03, -04, BO-01-04-01, -02, -03, -05, -06 et -08 promues par CODE de partiellement à intégralement couvertes ; BO-01-02-01 promue par CODE de non couverte à intégralement couverte ; BO-01-02-06 reste partielle, commentaire actualisé. Seuls L4, L8, L9, L13, L14, L19 à L26, les commentaires O de ces lignes et de L11 et leurs hauteurs changent dans le classeur. PDF : pages 1–2 actualisées, page 47 ajoutée, pages 3–46 préservées. Back Office P1 : 417/704 intégrales (59,2 %), 214 partielles (30,4 %), soit 631/704 intégrales ou partielles (89,6 %) ; toutes priorités : 450 / 254 / 92, couverture pondérée 72,5 % ; bloc 01 : 20 / 3 / 2. Ligne de caisses inchangée : P1 460/477. Campagne : 198 promotions, dont 190 CODE et 8 RELECTURE.

## HD_43 — BO-01-01-04, BO-01-01-05, BO-01-02-07 : langues du Back Office, clé Dallas, échelons de caisse

Gain : le Back Office imedge est traduit en français, néerlandais et portugais. Le bean `i18n` lit les catalogues `messages/msg_fr|nl|pt.properties` (3 100 entrées par langue, le français faisant référence). Les 66 gabarits d'écran passent par `{inject:i18n.get(...)}`. Les textes calculés par le serveur (notices, menus, libellés d'énumérations, descriptions des paramètres) restent en français dans le code et sont traduits à l'affichage par `i18n.text(...)`.

La langue suit cet ordre : celle de l'opérateur (écran Comptes), puis la langue de référence de l'échelon du nœud (`language.system`, posée au pays, à l'enseigne ou au PDV), puis le français.

En dessous de 1 000 px, l'en-tête, les tableaux, les onglets du journal et la table de synchronisation se replient ; les paires de champs de période du journal passent à la ligne au lieu de déborder.

Clé Dallas (iButton) :
- elle s'attribue au compte à l'écran Comptes : seize chiffres hexadécimaux, unique ;
- elle descend aux caisses dans le référentiel EMPLOYEES, et aux PDV dans les comptes distribués par le central ;
- là où `auth.dallas-key` est activé, la clé lue par le lecteur de la caisse (`POST /api/pos/dallas`) ouvre la session sans code PIN.

Échelons d'un compte : la liste propose aussi les caisses vues sur la ligne, et un champ « Autres TPV » accepte des identifiants de caisses ni déclarées ni vues.

Report du lot 16 sur la caisse impos :
- colonnes `echelon_scopes`, `auth_mode` et `central_managed` de `Employee`, méthodes `allowsPasswordLogin`, `allowsSsoLogin` et `adoptBackOfficePasswordHash` ;
- refus du mot de passe pour un compte SSO seulement sur les surfaces machine ;
- pas de changement de mot de passe imposé à un compte SSO ou central.

Correctifs mis au jour par la recette : l'en-tête du Back Office débordait en 800 × 600 ; les tableaux Articles, Bons de réduction et Synchronisation, ainsi que les onglets du journal, débordaient en 800 × 600 ; les champs de période du journal se chevauchaient à toutes les résolutions.

Préconditions : construction dev, H2 mémoire jetable, matériel simulé ; nœud imedge PDV 07039 (18070) et caisse impos POS01 (18080) rattachée au nœud. Page des échelons :
- `language.system` (LANGUE, défaut fr) ;
- `auth.dallas-key` (SESSION CAISSE, désactivé).

1. BO-01-02-03 : écran Comptes, créer tpv7 sur PDV:07039 avec « Autres TPV » = ` POS07, pos08` : échelons « PDV 07039, TPV POS07, TPV POS08 » ; à la modification, TPV:POS07 et TPV:POS08 restent sélectionnés. Une fois POS01 connecté, la liste propose « TPV POS01 — vu sur la ligne ».
2. BO-01-02-07 : poser `auth.dallas-key` à activé. Créer scol (TPV:POS01) avec la clé `01:a2:b3:c4:d5:e6:f7:08`, enregistrée `01A2B3C4D5E6F708`.
   - La même clé sur un autre compte : « Cette clé Dallas est déjà attribuée à scol. »
   - La clé `12345` : « Clé Dallas invalide : seize chiffres hexadécimaux. »
3. BO-01-02-07 : le référentiel EMPLOYEES de POS01 porte la clé.
   - POS01 verrouillée, présenter la clé au lecteur (`POST /api/pos/dallas`) : la page de verrouillage ouvre la session sans PIN et affiche le contrôle du matériel.
   - Clé inconnue : `/lock?error=true`.
   - `DALLAS:01A2B3C4D5E6F708` saisi sans lecture : `/lock?error=true`.
   - `XYZ` présenté au lecteur : 400.
   - Paramètre désactivé, après le tirage : la clé lue est ignorée.
4. BO-01-01-04 : poser `language.system` à nl.
   - La page de connexion est en néerlandais (`lang="nl"`), les écrans de l'administrateur aussi : « Accounts », menus Instellingen, Artikelen, Administratie, Supervisie, Financieel.
   - Une notice calculée en français, « Cet identifiant est déjà pris. », s'affiche « Deze gebruikers-ID is al in gebruik. ».
   - Le compte lise, administré en portugais, voit ses écrans en portugais sur le même nœud (`lang="pt"`, « Diário eletrónico »).
5. BO-01-01-05 : parcourir les 48 écrans du menu, Mes favoris et Mot de passe en fr, nl et pt, à 800 × 600, 1280 × 800 et 1366 × 768 : pas de défilement horizontal, aucun champ ni bloc qui déborde de son conteneur.
6. Vérifier les polices et le thème existants. Les paramètres affichent leur libellé et leur description dans la langue de la page.

Recette exécutée le 24/10/2026 sur imedge (18070) et impos POS01 (18080), profil dev, bases H2 mémoire jetables, matériel simulé. Les étapes 1 à 6 ont été jouées avec les messages, codes et textes ci-dessus. Le balayage des 48 écrans dans les trois langues aux trois résolutions ne signale aucun débordement.

Limites :
- aucun lecteur iButton physique : le lecteur est simulé par l'appel au point d'entrée de la caisse ;
- les données (libellés d'articles, de touches, de gabarits d'impression) restent dans la langue où elles ont été saisies.

Validation : imedge 4 348 tests Java + 11 JavaScript ; impos 7 466 Java + 319 JavaScript, sans échec, erreur ni test ignoré. JaCoCo : imedge 22 130 lignes et 10 950 branches, impos 29 700 lignes et 15 212 branches, tous les compteurs manqués à zéro ; JavaScript externe instrumenté à 100 %. La table des paramètres BackOffice intègre auth.dallas-key ; un test vérifie que les trois catalogues ont les mêmes clés et les mêmes paramètres, et que chaque libellé appelé par les gabarits existe.

Cotation (feuille Back Office) : BO-01-01-04 est promue par CODE de partiellement à intégralement couverte ; BO-01-01-05 et BO-01-02-07 sont promues par CODE de non couvertes à intégralement couvertes.

Seuls L5, L6, L12, les commentaires O de ces lignes et leurs hauteurs changent dans le classeur. PDF `synthese-imedge-HD_43.pdf` : pages 1–2 réécrites sur imedge, page 48 ajoutée, pages 3–47 préservées.

Back Office P1 : 420/704 intégrales (59,7 %), 213 partielles (30,3 %), 71 non couvertes (10,1 %), soit 633/704 intégrales ou partielles (89,9 %). Toutes priorités : 453 / 253 / 90, couverture pondérée 72,8 %. Bloc 01 : 23 / 2 / 0.

Campagne : 201 promotions, dont 193 CODE et 8 RELECTURE.

## HD_43 — Lot 18 : BO-10 Règles et BO-08 Supervision

Gain : le Back Office imedge administre les règles de caisse du bloc BO-10 et la supervision et le déploiement du bloc BO-08.

Règles BO-10, toutes posées à `/admin/settings` (ou par échelon à `/admin/echelons`) et descendues aux caisses avec les autres paramètres :
- canaux externes (section CANAUX EXTERNES) : e-commerce `channel.ecommerce.*`, self-scanning `channel.selfscan.*`, gestion commerciale `channel.commercial.*`, Q-busting `channel.qbusting.*`, mobilité `channel.mobility.*`, bons de vente `channel.sales-slip.*`. Activation, mode de récupération, ajout d'articles, identifiant, source du prix, coupons, EAN générique des articles inconnus, canal transmis au moteur de promotion, vente interdite, impression automatique, acompte, paiement fictif. Le nœud reçoit les commandes e-commerce (`POST /api/ecommerce/orders`) et les enregistre comme tickets d'une caisse virtuelle rattachée à un îlot ; l'écran `/admin/ecommerce-orders` garde le compte rendu et la commande d'origine ;
- fidélité (section FIDÉLITÉ) : soldes des cagnottes, nom du porteur, moyen de paiement, compte et mot de passe masqué, délais, date de commande, promotions personnalisées déclenchées, regroupement par catégorie, sous-famille ;
- règles de caisse : tiroir en mode école, article inconnu, règle d'arrondi, décimales de facture, mot de passe à l'ouverture, longueur du code, mode de validation superviseur, code article sur ticket et écrans, ticket balance, message de vente interdite, regroupement des lignes, GS1 et alertes par AI, report des mises à jour de référentiel pendant une vente ;
- documents et fiscal : identifiants SAF-T FS et NC, certificat et export SAF-T PT (`/admin/saft`), éco-taxes, textes du chèque, logos monétique et sans contact, montant dû agrandi, tri et sous-totaux par famille, langues de l'afficheur client et du système, écran déconnecté, courriel du ticket, décimales et symbole des pesées, lignes annulées et lignes identiques sur le ticket.

Supervision BO-08, sur le central et sur chaque magasin :
- consoles d'intégration, d'usage des fonctions, d'activité, d'inventaire et de maintenance (`/admin/supervision/{integration,usage,activity,inventory,maintenance}`), avec graphiques, filtres par échelon et exports CSV/XLSX ;
- API de réception par domaine `GET /api/supervision/integrations?domain=VAT_RATES` ;
- déploiement par lots (`/admin/deployment`) : versions publiées, cibles, prérequis, période, pilotes, mise à jour au démarrage, reprise automatique, forçage, retour arrière ;
- conservation et réintégration automatique ou manuelle des dépôts du central, ordres de maintenance à distance tracés ;
- page mobile des alertes caisses (`/dashboard/mobile`).

Correctifs mis au jour par la recette :
- création d'un îlot, et d'un pays, d'une enseigne ou d'un PDV, en erreur 500 ;
- prix de la ligne marquée inconnue repris de l'article générique ;
- événements fidélité de clôture envoyés avec le compte de déploiement et un délai fixe ;
- tiroir resté fermé en mode école ;
- ventilation de TVA et totaux de facture divergents en arrondi administré ;
- message de vente interdite non saisissable sur la fiche article ;
- décimales et symbole absents du ticket comptoir ;
- apparence hors ligne peu visible ;
- appels superviseur des caisses en 404 au magasin ;
- réintégration planifiée en échec et réponses sans erreur classées partielles ;
- état de mise à jour non remis à zéro sur une nouvelle version cible ;
- textes restés en français sur les écrans nl et pt, débordements de tableaux à 800, 1280 et 1366 px.

Préconditions : construction dev, bases H2 mémoire jetables, matériel simulé. Nœuds :
- nœud imedge magasin PDV 07039 et sa caisse impos POS01, lancée avec `-Dpos.recall.document-url` vers le magasin et `-Dpos.fid.url` vers un bouchon imfid (comptes avec nom du porteur, `POST /api/accounts/{carte}/pots`, promotions de carte, événements, source `/gc/orders`, réponse en 3 s pour les cartes contenant 777) ;
- caisse POS02 sur le PDV 07039, pour les règles paires et impaires ;
- central imedge, pour BO-08 et BO-10-08-23 ;
- second magasin PDV 07040 (enseigne NET, adhérent ADH07040) et sa caisse POS01, déclarés au central par `/admin/echelons`, pour les cas multi-PDV.

Ports de la recette : passe RC1 magasin 19070, caisse 19080, imfid 19060 ; passe RC2 magasin 20070, caisse 20080, imfid 20060, central 20090 ; passe RC3 central 21090, magasin 07039 21070 avec POS01 21080 et POS02 21081, magasin 07040 21071 avec POS01 21082. Un paramètre enregistré atteint la caisse en 60 s au plus.

**Canaux e-commerce, self-scanning, gestion commerciale, Q-busting, mobilité, bons de vente**

1. BO-10-01-01, BO-10-01-03 : `channel.ecommerce.enabled` désactivé, `POST /api/ecommerce/orders` sur W0 : `"status":"DISABLED"`, « E-commerce non activé sur ce point de vente » ; le panier ECOMMERCE saisi en caisse : « E-COMMERCE : CANAL NON ACTIVÉ SUR CE MAGASIN ».
   - Activé, W1 : `{"status":"INTEGRATED","ticketNumber":"ECOM-W1","total":3.20,"unknownLines":1,…,"payments":[{"method":"PAYPAL","integratedAs":"CARD"}]}`.
   - Nouvel envoi de W1 : `"alreadyReceived":true`, rien n'est enregistré deux fois. `GET /api/ecommerce/orders/W1` rend le compte rendu, `GET /api/ecommerce/tickets/ECOM-W2` le ticket.
2. BO-10-01-06, BO-10-01-07, BO-10-01-09, BO-10-01-14 :
   - `unknown-item-ean=3300000000030`, W2 : `"outcome":"UNKNOWN","carriedOn":"3300000000030"`, ligne « INCONNU WEB [ART. INCONNU 9990001] » au prix 1,00 de la commande ; sans EAN générique, la ligne est intégrée et signalée UNKNOWN.
   - `price-source=POS` : Lait de W3 à 2,76 ; `TRANSACTION` : W4 à 1,10.
   - `default-tender=VOUCHER` : `"integratedAs":"VOUCHER"` ; `XYZ` : W7 REJECTED « Moyen de paiement par défaut inconnu de l'encaissement ».
   - `forbidden-items=ACCEPT` : W5 INTEGRATED, `"forbiddenLines":1` ; `REJECT` : W6 `"status":"REJECTED","message":"Article en vente interdite"`.
3. BO-10-01-08, BO-10-01-11 : créer l'îlot WEB (« Îlot créé. »), poser `island=WEB` : W20 intégrée, point d'encaissement ECOM rattaché à l'îlot WEB sur `/admin/checkout-points` ; îlot NOPE : « Îlot inconnu : NOPE ». `archive=true` : `/admin/ecommerce-orders` montre le compte rendu et la « Commande d'origine » ; `archive=false` : W11 avec le compte rendu seul.
4. BO-10-01-04, BO-10-01-05 : `return-ticket-types=Retour web`, rechercher ECOM-W1 à l'écran de retour et le rendre en « Retour magasin » : « TYPE DE RETOUR NON AUTORISÉ POUR UN TICKET E-COMMERCE » ; en « Retour web », le visa est demandé. `return-article-types=Retour partiel web`, rendre un Lait sur deux d'ECOM-W2 en « Retour web » : même refus ; en « Retour partiel web », le retour passe au visa.
5. BO-10-01-13, BO-10-01-22, BO-10-01-28, BO-10-01-31, BO-10-01-34 : poser le canal de promotion de chaque canal et rappeler un panier ; la requête de valorisation porte `"promotionChannel":"WEB"` (e-commerce, avec `"deliveryMode":"IN_STORE"`, self-scanning, gestion commerciale, Q-busting) ou `"promotionChannel":"OTHER"` (self-scanning, mobilité).
6. BO-10-01-15 à BO-10-01-18 :
   - `selfscan.enabled=false`, panier SE…-CMD… saisi : « SELFSCANNING : CANAL NON ACTIVÉ SUR CE MAGASIN » ; activé, le panier est chargé.
   - `retrieval-mode=SCAN`, code saisi : « CE DOCUMENT SE RÉCUPÈRE PAR LE SCAN DE SON CODE-BARRES » ; `KEYED`, code scanné : « CE DOCUMENT SE RÉCUPÈRE PAR LA SAISIE DE SON CODE À L'ÉCRAN RAPPEL ».
   - `add-items=AFTER`, article puis panier : « PANIER SELFSCANNING REFUSÉ : DES ARTICLES SONT DÉJÀ SAISIS » ; `BEFORE`, article après le panier : « AJOUT D'ARTICLE INTERDIT APRÈS LE PANIER SELFSCANNING » ; `NONE` : les deux refusés.
   - `identifier=CUSTOMER`, code du panier saisi : « PANIER SELFSCANNING : SAISIR LE N° CLIENT À L'ÉCRAN RAPPEL » ; numéro client 4242001 : panier chargé (Lait, Eau).
7. BO-10-01-19 à BO-10-01-21, BO-10-01-23 :
   - source du prix POS : Lait 2,76 € ; TRANSACTION : Lait 1,10 €.
   - `coupons=false` : « 1 COUPON(S) DU PANIER NON REPRIS » ; `true` : paiement « BON 1 EUR 1,00 ».
   - EAN générique 3300000000030 : ligne « INCONNU SLS [ART. INCONNU 9990002] », bandeau « 1 ARTICLE(S) INCONNU(S) MARQUÉ(S) » ; en prix POS, Lait 2,76 € et ligne inconnue 2,00 € (prix du panier).
   - `forbidden-items=REJECT` : « VENTE INTERDITE : Lot rappelé (retrait conso) (CODE 3660000099999) », rien de chargé.
8. BO-10-01-24 à BO-10-01-29 :
   - `commercial.forbidden-items=REJECT`, DEVIS avec l'article interdit : « VENTE INTERDITE : Lot rappelé (retrait conso) (CODE 3660000099999) ».
   - `retrieval-mode=KEYED`, COMMANDE scannée : « CE DOCUMENT SE RÉCUPÈRE PAR LA SAISIE… ». `transmission=PULL`, `pull-url` vers `/gc/orders` du bouchon, `pull-minutes=1` : `GET /api/orders/GC1-CMD900` sur le nœud rend la commande tirée.
   - Modèle BONGC et `auto-print-template=BONGC`, COMMANDE réglée par carte : l'imprimante simulée sort « BON GC POS01-00000016 / DOCUMENTS CO12-CMD13 / Lait x1 2,76 / TOTAL 2,76 » puis coupe.
   - `deposit-tender=CARD`, acompte 1,00 : acompte enregistré pour CO10-CMD11 sous CARD, `acompteAlreadyPaid 1.0000` au nœud.
   - `price-source=POS` : Lait réglé 2,76 € (1,10 sur le document).
9. BO-10-01-30, BO-10-01-32, BO-10-01-33 : `qbusting.retrieval-mode=SCAN`, code saisi : refus « …PAR LE SCAN DE SON CODE-BARRES » ; scanné : « QBUSTING QB…-CMD… CHARGÉ OK ». REJECT : « VENTE INTERDITE : Lot rappelé (retrait conso) (CODE 3660000099999) ». Prix POS : Lait 2,76 €.
10. BO-10-01-36, BO-10-01-37, BO-10-01-39 : `mobility.price-source=TRANSACTION` : Lait 1,10 €, Eau 0,50 € ; REJECT : même message de vente interdite. Transaction payée CARD 1,00 sur 1,60 €, `fictitious-tender=VOUCHER` : CARTE BANCAIRE 1,00 + BON D'ACHAT 0,60, reste 0,00, « MOBILITÉ : VOUCHER AJOUTÉ POUR 0,60 € ».
11. BO-10-01-40, BO-10-01-41 : `sales-slip.retrieval-mode=KEYED`, bon scanné : « CE DOCUMENT SE RÉCUPÈRE PAR LA SAISIE… » ; saisi : « BON DE VENTE BO…-CMD… CHARGÉ OK ». Prix POS : Lait 2,76 €.

**Fidélité**

12. BO-10-02-33 : `fidelity.pot-balances-enabled=true`, carte 2990000012344 : la caisse appelle `POST /api/accounts/2990000012344/pots {"earnAmount":5.62,"burnAmount":0}`.
    - `/fidelity` : « SOLDES DES CAGNOTTES : Cagnotte : 12,50 € Cagnotte carburant : 3,20 € ».
    - Ticket réimprimé après clôture : « SOLDES DES CAGNOTTES / Cagnotte 12,50 E / Cagnotte carburant 3,20 E » sous SOLDES CARTE.
13. BO-10-03-04, BO-10-03-06 : `show-holder-name=true`, scan de la carte 2990000000011 : « DURAND Anne · 2990000000011 · 12,50 € ». `payment-tender=VOUCHER`, paiement fidélité 1,00 et signature : « BON D'ACHAT 1,00 ».
14. BO-10-03-08, BO-10-03-10 : saisir `fidelity.password` ; le champ est masqué, « renseigné — vide = inchangé » ; un enregistrement à vide donne « 0 paramètre(s) enregistré(s) » et garde la valeur ; sur `/admin/echelons`, la valeur de `fidelity.password` est masquée. L'appel du compte et l'événement de clôture partent en `Basic posuser:S3cret!`. `read-timeout-ms=800`, imfid à 3 s : « SERVICE FIDÉLITÉ INDISPONIBLE » après 0,82 s ; `5000` : porteur affiché après 3,05 s.
15. BO-10-03-17, BO-10-03-22, BO-10-08-22 :
    - `order-date-enabled=true`, COMMANDE rappelée : `"orderDate":"2026-10-25T07:58:49"` dans la valorisation, la demande de gain et l'événement de clôture.
    - `jv-promotion-enabled=true`, promotion de carte sur 3300000000002 vendue : `"triggeredPromotions":[{"ean":"3300000000002",…,"quantity":1}]` dans l'événement de clôture.
    - `send-subfamily=true` : `"familyCode":"20200200","subFamilyCode":"202002000001"` dans la valorisation, `subFamilyCode` dans la demande de gain.
16. BO-10-03-20 : `fidelity.advantage-grouping=CATEGORY`, imfid rend PRODUITS FRAIS (rang 2) puis EPICERIE (rang 1) : le ticket imprime « EPICERIE / BON 5 EUR DES 10 EUR +5,00 E » puis « PRODUITS FRAIS / CAGNOTTE 5 % +0,62 E ».

**Règles de caisse**

17. BO-10-02-12 : vente en espèces en mode école. `drawer.open-in-training=false` : tiroir fermé, journal « Tiroir laissé fermé : mode école (BO-10-02-12) » ; `true` : tiroir ouvert. L'ouverture manuelle suit la même règle.
18. BO-10-02-14 : `scan.unknown-manual-entry=true`, scan de ZZZ999 : « ARTICLE INCONNU : ZZZ999 — SAISISSEZ LA FAMILLE OU LE CODE », puis SAISIE DIRECTE « Article inconnu, vendez-le par sa famille ou son code : ZZZ999 ».
19. BO-10-02-18, BO-10-02-19 : `calc.rounding-mode=DOWN`, PLU 4020 1,257 kg × 1,08 : 1,35 (1,36 en HALF_UP) ; ticket « TVA 20,00% HT 1,12 TVA 0,23 » ; facture : TVA 1,12/0,23, TOTAL HT 1,12, TOTAL TVA 0,23. `invoice.decimals-line-total-ht=3` : facture A4 « Total HT 0,900 € », facture 42 colonnes « Total HT 0,900 », ligne pesée « 1,133 ».
20. BO-10-02-22, BO-10-02-23 : `auth.password-required-on-open=false`, badge 12341234 sur `/lock` : la caisse s'ouvre seule ; `true` : elle attend le code. `auth.pin-length=6` : `/pin-change` à 4 chiffres « Le nouveau code doit comporter 6 chiffres » ; compte créé sur imedge : code généré « 120132 ».
21. BO-10-02-31 : `supervisor.validation-mode` :
    - CODE_PASSWORD : mcurie/1111 accepté ;
    - BARCODE : 11111111 tapé sans lecture « AUTORISATION REFUSÉE », badge lu accepté sans code ;
    - BARCODE_PASSWORD : badge lu + 1111 accepté ;
    - BARCODE_CODE_PASSWORD : badge + jdupont refusé, badge + mcurie/1111 accepté ;
    - DALLAS : clé 01-A2-B3-C4-D5-E6-F7-08 sur `/api/pos/dallas` acceptée ; consignes « Présentez le code-barres superviseur », « Présentez la clé Dallas superviseur ».
22. BO-10-02-34, BO-10-02-35 : `display.show-ean=true`, indicateur faux sur 3300000000001 et vrai sur 3300000000002 : EAN imprimés sous LAIT et BAGUETTE, aucun sous POMMES ; `false` : seul « 3300000000002 » ; DUPLICATA N°1 identique. Écran de vente : EAN 3300000000002 et 3300000000003 (actif), 3300000000002 seul (inactif) ; l'afficheur client ne porte l'EAN que sur LAIT.
23. BO-10-05-01 : `balance.ticket-mode=API_FTP` : `POST /api/sync/balance-ticket` 200, scan 2700000000427 : « TICKET COMPTOIR 0000000042 INTÉGRÉ (2 LIGNE(S)) » ; `FTP` : 403 « Récupération des tickets balance par l'API désactivée » ; `OFF` : 403 et le code 27… est lu « ARTICLE INCONNU ».
24. BO-10-07-20 : défaut « PRODUIT INTERDIT À LA VENTE » ; `sale.forbidden-message` : « RETRAIT RAPPEL : VOIR ACCUEIL » ; message « RAPPEL LOT 12 : NE PAS VENDRE » saisi sur la fiche de 3660000099999 : il l'emporte.
25. BO-10-08-25, BO-10-08-32 : vente ouverte, libellé de 3300000000003 modifié sur imedge : « Référentiel PRODUCTS différé : vente en cours », l'article scanné reste « BAGUETTE TRADITION » ; après clôture, « BAGUETTE MODIF » 4 s plus tard. `sale.group-lines=false` : même EAN deux fois, deux lignes x1 ; `true` : une ligne x3.
26. BO-10-09-01, BO-10-09-04 : `gs1.enabled=true` : (01)03300000000002 donne LAIT UHT 1L ; `false` : « ARTICLE INCONNU : (01)03300000000002 … ». `gs1.ai-alerts` : `10=SUPERVISOR:Lot {value} à contrôler` refuse « Lot LOT42 à contrôler » avec le panneau superviseur, visé la ligne est ajoutée ; `21=WARN` ajoute la ligne et « Numéro de série SN777 » ; `22=BLOCK` refuse « Variante V1 refusée ». Observation : un AI absent de la table du lecteur (240) est lu comme l'AI 24, donc une alerte `240=…` ne se déclenche pas.

**Documents et fiscal**

27. BO-10-06-01 à BO-10-06-03 : `/admin/saft` « FS POS01/2026/00000001 FS (1) » et « NC POS01/2026/00000001 NC (2) » ; `fiscal.saft-id-fs=5`, `fiscal.saft-id-nc=6` : « FS (5) », « NC (6) », export `<HeaderComment>FS=5;NC=6</HeaderComment>`. FS et NC impriment « Processado por programa certificado n.º 1234/AT » ; l'export `SAFT-PT_12123456789_2026-10-01_2026-10-25.xml` porte AuditFileVersion 1.04_01 et SoftwareCertificateNumber 1234.
28. BO-10-06-05, BO-10-06-06 : `ecotax.attributes=DEEE=Eco DEEE;DEA=Eco mobilier;PILES>NOPRINT;PNEUS;EMB`. En LINE, sous LAIT : « Eco DEEE 0,50 E / Eco mobilier 0,30 E / PNEUS 0,20 E / EMB 0,05 E », PILES non imprimée ; en TOTAL, sous la ventilation de TVA. `customer.invoice-export-ecotax=true` : CSV de `/admin/documents` avec TOTAL_ECO_TAX 1.1500, PILES compris.
29. BO-10-07-05, BO-10-07-13, BO-10-07-14 : `cheque.unit-text=Euro(s)`, `cheque.cent-text=Cent(s)`, recto de 2,50 : « deux Euro(s) et cinquante Cent(s) ». Images « Logo monétique » et « Logo sans contact » déposées, paiement carte : `[[IMAGE 384 109 …]]` au-dessus des tickets TPE client et commerçant. `ticket.due-amount-large=true` : « [[DOUBLE]]TOTAL TTC … 3,72 E » puis « [[/DOUBLE]] ».
30. BO-10-07-15, BO-10-08-30, BO-10-08-31 : `ticket.line-order=FAMILY` avec sous-totaux et gras : titres de famille, « SOUS-TOTAL [[BOLD]]8,28 E[[/BOLD]] ». `ticket.cancelled-lines-print=true` : « CONCOMBRE / ANNULE 3,60 E », hors des totaux. `ticket.identical-lines-detailed=true` : trois lignes « LAIT UHT 1L 2,76 » sur le ticket, x3 à l'écran.
31. BO-10-07-17, BO-10-07-18 : `language.customer-display=nl`, `language.customer-second=fr` : `/customer` en `lang="nl"`, « Welkom / Bienvenue », « TOTAL / TOTAAL », « Reste à payer / Nog te betalen ». `language.system=nl` puis `pt` : BackOffice (Instellingen, Parametrização) et caisse (KASSA, CAIXA) dans cette langue.
32. BO-10-07-19 : `display.offline-enabled=true`, délai 0, nœud magasin suspendu : après 31 à 32 s, colonne ticket hachurée, cadre orange, bandeau « *** MODE DÉCONNECTÉ — SERVEUR MAGASIN INJOIGNABLE *** » ; retour normal 1 s après la reprise du nœud.
33. BO-10-07-22, BO-10-07-23 : `ticket.email-offer=true` : `/action/finish` mène à `/ticket-email` (« Envoi du dernier ticket »). `ticket.email-editable=false`, carte avec adresse : « Adresse connue du programme de fidélité : Cette adresse ne peut pas être modifiée en caisse. anne.durand@example.test » ; sans adresse : « Saisissez celle qu'il vous donne ».
34. BO-10-08-28, BO-10-08-29 : `unit.weight-decimals=2`, `unit.weight-symbol=KG` : écran, afficheur et ticket « 1,26 KG x 1,08 E/KG », facture A4 « 1,26 KG », facture 42 colonnes « 1,26 KG x 1,08 (HT 0,90) », ticket comptoir « LAIT UHT 1L 2,00 KG » ; par défaut « 0,734 kg ».

**Supervision et déploiement** (central, magasins 07039 et 07040)

35. BO-08-01-17, BO-08-01-19 : `/admin/supervision/integration` du central : 80 intégrations, graphique OK/KO ; `?group=ENSEIGNE` « —, ITM, NET », `?pdv=07040` 27 lignes, `?status=KO` une ligne (VAT_RATES du central) ; exports `integration.csv` et `integration.xlsx`. `GET /api/supervision/integrations?domain=VAT_RATES` : 200, une entrée par nœud (`"pdv":"07039","kind":"REGISTER","node":"POS01","received":true`), le dépôt en échec du central en `"received":false`, détail ABANDONED.
36. BO-08-02-02 : `/admin/supervision/usage` : fonctions de caisse `/action/pay-card`, `/action/session/open`, `/action/unlock` et gestes du BackOffice (`deployment:addTargets` ×21, `remote_maintenance:issue`) ; `?enseigne=NET` ne montre que le PDV 07040, `?channel=CAISSE` trois fonctions sur deux nœuds ; exports CSV et XLSX.
37. BO-08-03-02, BO-08-03-03 : sur un lot, ajouter les cibles :
    - `UNIT 07040` : « 1 cible(s) ajoutée(s) » ; `ECHELON ENSEIGNE NET` : la seule caisse du 07040 ;
    - `ECHELON COUNTRY FR` règle impaire : « 07039 Caisse POS01 » et « 07040 Caisse POS01 » ; `UNIT 07039` règle paire : POS02 seule ;
    - `ADHERENT ADH07040` : magasin 07040 seul ; `VERSION 1.0-SNAPSHOT` : caisses des deux PDV ;
    - liste `07039;POS02 / 07040;POS01 / 99999;POS09` : « 1 cible(s) ajoutée(s), inconnu(s) : 99999;POS09 » ;
    - « Sélection enregistrée » (pilotes), rappel ; nom inconnu : « Sélection introuvable. ».
38. BO-08-03-01, BO-08-03-04 : la page du lot affiche « Prérequis respectés », « Version trop ancienne (minimum 1.5) », « Nœud jamais vu : version inconnue », « Hors ligne : mise à jour au prochain démarrage » (POS01 arrêtée), « Déjà à jour », et « N cible(s) prête(s) — M cible(s) à surveiller ». Période invalide : « Période illisible : attendu jj/mm/aaaa hh:mn, la fin après le début. » ; « Période enregistrée. » pour le lot MULTI du 02/12/2026 21:00 au 02/12/2026 23:45.
39. BO-08-03-05, BO-08-03-07 : lot CENTRALE (IMEDGE 3.0, ligne CENTRAL) : cible « Appliquée », « Application imedge 3.0 » dans l'inventaire du central. Lot SAISON (IMPOS 2.0, période passée) sur POS01 : en marche, WAITING_WINDOW ; après redémarrage, « Mise à jour 1.0-SNAPSHOT -> 2.0 : DOWNLOADING / VERIFYING / INSTALLING / APPLIED (Version 2.0 en service) ».
40. BO-08-03-09, BO-08-03-10, BO-08-03-06 : lot PILOTE-BO (IMEDGE 2.5) sur le seul 07040 : « Magasin 07040 2.5 (Appliquée) », 07039 reste « 1.0-SNAPSHOT (Au repos) ». Lot PILOTE-CAISSE (IMPOS 2.5), caisses paires du 07039 : « 07039 Caisse POS02 2.5 (Appliquée) », les autres caisses restent en 1.0-SNAPSHOT. `/admin/deployment` : graphique des versions « IMEDGE 3.0, IMPOS 1.0-SNAPSHOT, IMPOS 2.5, IMEDGE 2.5… », vues `?group=COUNTRY|ENSEIGNE|PDV`, `?enseigne=NET` 5 lignes, `?status=KO|OK`, exports `deploiement.csv` et `deploiement.xlsx`.
41. BO-08-04-01, BO-08-04-02, BO-08-04-14 :
    - paquet défectueux retenté à chaque rapport jusqu'à `deployment.max-attempts=3` (ROLLED_BACK à 07:39:58, 07:40:58, 07:41:58), puis ABANDONED ;
    - « Forcer » : « 1 cible(s) forcée(s). », la caisse retente aussitôt ;
    - ordre « Relance du tirage des référentiels » : « Tirage des référentiels relancé » sur 07039 et 07040 ;
    - IMPOS 2.6 en « paquet défectueux » : « 07040 Caisse POS01 » « Retour arrière — Autotest de la version 2.6 en échec : retour à la version 1.0-SNAPSHOT » ;
    - pendant une coupure du central, le magasin journalise « Rapport de nœud impossible » ; son rapport est reçu au redémarrage.
42. BO-08-04-03 à BO-08-04-08 (réintégration automatique au central) :
    - PRICES sur EAN inconnu en « Lignes rejetées », puis, après PRODUCTS, « (integration-retry) Réintégration du dépôt 10 (PRICES) » : « Intégré », 2 tentatives ;
    - PRODUCTS sans type rejoué chaque minute jusqu'à « Abandonné » ; PRODUCTS avec colonne ATTRIBUTES « Intégré » ;
    - NOMENCLATURE « Intégré » ;
    - CUSTOMERS à raison sociale vide et VAT_RATES `AB|5.5` en « Lignes rejetées », réintégrés jusqu'à « Abandonné » ; activité du central « Synchronisation — Intégrations en erreur : VAT_RATES » ;
    - OFFERS « Intégré », corps vide « Refusé » (400) ; au magasin, BUDGET_GROUPS en échec de connexion puis « Rejouer ».
43. BO-08-04-09 à BO-08-04-13 (réintégration manuelle) : « Réintégrer » sur PRICES : « Dépôt PRICES réintégré : APPLIED. » ; ordre « Relance des intégrations en erreur » au central : « Effectué — 4 dépôt(s) réintégré(s) », puis « 5 dépôt(s) réintégré(s) » avec CUSTOMERS ; « Relance du tirage des référentiels » au magasin 07040 et à la caisse POS01 ; « Relance de la livraison des flux moteur » : « Livraison des flux moteur relancée ».
44. BO-08-04-15, BO-08-04-16, BO-08-04-20 :
    - `/admin/supervision/activity` : central, deux magasins, trois caisses ; graphiques « —, 07039, 07040 » et « —, ITM, NET » ; filtres `?enseigne=NET`, `?pdv=07039`, `?country=FR&kind=STORE` ; exports.
    - Imprimante de POS02 sans papier (simulée) puis ordre « Contrôle des périphériques » : « Caisses en anomalie » (`?kind=REGISTER&status=KO`) ne liste que « 07039 Caisse POS02 KO Périphérique — Imprimante : En panne ».
    - `/admin/supervision/inventory` : matériel, logiciel, périphériques « Disponible » ou « En panne », graphique « Imprimante 2/1 », `?category=PERIPHERAL&status=KO`, export CSV.
45. BO-08-04-17 : `/admin/supervision/maintenance` :
    - « Relance du TPV » : En attente, Envoyé, puis Effectué « Réinitialisation terminal effectuée » ;
    - réinitialisation du scanner de 07040/POS01 et de l'imprimante ;
    - « Contrôle des périphériques » : `BALANCE=AVAILABLE, … IMPRIMANTE TICKETS=FAILED…` ;
    - réinitialisation matérielle pendant une vente : « En échec — Vente en cours : ordre refusé ».
46. BO-10-08-23 : magasin tirant du central, audit désactivé : `/admin/sync` « Traçage désactivé… » ; `referential.update-audit-enabled=true` : « Les 7 dernières intégrations… », lignes domaine / empreinte / 25/10 09:33:21 / APPLIED ; côté caisse, lignes DEFERRED pendant une vente.

**Alertes mobiles**

47. BO-10-08-02 : `dashboard.mobile-alerts-enabled` désactivé : `/dashboard/mobile` « L'affichage des alertes caisses sur mobile n'est pas activé au back-office. », `/dashboard/mobile/data` `enabled:false`. Activé, appel superviseur PRIX de POS01 : `{"terminal":"POS01","reason":"PRIX","status":"PENDING"}` ; autoriser : AUTHORISED ; refuser : REFUSED ; acquitter : l'alerte disparaît. En 390 × 844, pas de défilement horizontal.
48. Parcourir le menu du BackOffice (57 pages, favoris et mot de passe compris) sur le central et le magasin, en fr, nl et pt, à 800 × 600, 1280 × 800 et 1366 × 768 : pas de défilement horizontal, aucun bloc ni champ qui déborde ; écrans de caisse rappel, fidélité, visa et afficheur client dans les trois langues.

Recette exécutée le 25/10/2026 en trois passes (RC1 canaux et fidélité, RC2 règles de caisse, documents et fiscal, RC3 supervision, déploiement et alertes mobiles), profil dev, bases H2 mémoire jetables, matériel simulé, bouchon imfid. Les étapes 1 à 48 ont été jouées avec les messages, codes et valeurs ci-dessus. La passe finale du balayage des écrans du BackOffice ne signale aucun débordement sur les six parcours (deux nœuds, trois langues), et les 84 captures des écrans de supervision et de déploiement sont conformes.

Limites :
- BO-10-08-23 est partielle : l'audit des mises à jour est tenu par domaine, pas article par article ;
- BO-08-04-17 est partielle : maintenance par ordres à distance tracés, sans prise en main de l'écran à distance ;
- la mise à jour applicative est simulée : la séquence téléchargement, contrôle d'empreinte, installation, autotest et retour arrière est réelle, mais aucun binaire n'est remplacé ;
- aucun TPE réel : le logo sans contact, qui suit le mode de lecture rendu par le terminal, n'est prouvé que par les tests Java ;
- aucun lecteur iButton physique : la clé Dallas superviseur est présentée par l'appel au point d'entrée de la caisse ;
- la purge par ancienneté des commandes e-commerce archivées et la validité au seul jour J des bons de vente sont prouvées par les tests Java, faute de données anciennes en dev.

Validation : imedge 5 094 tests Java + 11 JavaScript ; impos 8 234 Java + 319 JavaScript, sans échec, erreur ni test ignoré. JaCoCo : imedge 26 274 lignes et 12 926 branches, impos 32 649 lignes et 16 861 branches, tous les compteurs manqués à zéro ; JavaScript externe instrumenté à 100 %. Les 302 classes copiées entre imedge et impos sont identiques au paquet près (seuls les scénarios Group*IT, homonymes sans être des copies, diffèrent).

Cotation (feuille Back Office) : les 110 lignes non intégrales des blocs 08 Supervision et 10 Règles sont recotées. 108 passent intégralement couvertes, dont 103 par CODE et 5 par RELECTURE (BO-10-02-22, BO-10-07-14, -18, -22, -23, déjà servies et prouvées en recette) ; BO-08-04-17 passe de non couverte à partiellement couverte ; BO-10-08-23 reste partielle, son commentaire actualisé. Back Office P1 : 516/704 intégrales (73,3 %), 157 partielles (22,3 %), 31 non couvertes (4,4 %), soit 673/704 intégrales ou partielles (95,6 %). Toutes priorités : 561 / 188 / 47, couverture pondérée 82,3 %. Bloc 08 : 45 / 1 / 0 ; bloc 10 : 170 / 1 / 0. Campagne : 310 promotions, dont 297 CODE et 13 RELECTURE.
## HD_43 — Lot 19 : BO-03 Paramétrage caisse

Gain : le Back Office imedge administre le paramétrage caisse du bloc BO-03 et les caisses impos l'appliquent : configuration par caisse, par îlot et groupée, matériel, menus et écrans de caisse, déploiement immédiat ou programmé (partiel ou complet) et paramètres datés, moyens de règlement, dépenses et mode dégradé monétique, remises et rabais, types de retour, modèles de ticket et documents imprimables, factures, messages fidélité et prestataires, groupes d'articles, codes-barres et GS1.

Écrans et domaines nouveaux du Back Office :
- `/admin/register-config` : configuration par caisse (échelon CHECKOUT_POINT), par îlot et groupée (îlot, type, liste de caisses, toutes, date facultative), données de formation ;
- `/admin/cash-menus` (domaine CASH_MENUS) : modèles de menu caisse ;
- `/admin/releases` : déploiement partiel et complet programmés, paramètres datés ;
- `/admin/return-types` (RETURN_TYPES), `/admin/prepaid-cards` (PREPAID_CARD_TYPES), `/admin/printable-documents` (PRINTABLE_DOCUMENTS) ;
- rapports `/admin/reports/deposit-returns` et `/admin/reports/returns-by-type` ;
- enrichis : `/admin/tenders`, `/admin/lists` (dépenses, remises et rabais), `/admin/coupons` (plages de codes-barres), `/admin/article-groups`, `/admin/touches`, `/admin/providers`, `/admin/templates`, `/admin/checkout-points`, `/admin/checkout-islands`, `/admin/day-end`, `/admin/echelons`, `/admin/journal/barcodes-issued`, détail de ticket du journal.

Correctifs mis au jour par la recette :
- flux Catalina jamais livré (boucle de livraison hors transaction) ;
- repères `%Champ%` de la fiche magasin imprimés en clair dans les modèles d'entête et de pied ;
- type et adresse du terminal monétique lus une seule fois au démarrage, donc jamais appliqués ; codes monétiques VIC lus hors du contexte de l'appelant ;
- réponse d'un terminal réel en erreur de contexte : paiement accepté perdu ;
- création d'une devise en erreur 500 ;
- ligne de retour en vente non remontée au magasin (rapports de déconsigne et de retours par type faux) ; bon de déconsigne 298… lu comme article pesé ;
- duplicata d'acompte sans le motif de l'original ;
- aperçu de facture « N° commande true » et « Échéance true » ; ligne d'attributs coupée sur le rouleau ; numéro de compte obligatoire sans effet et refus muet ;
- lignes promotion du moteur jamais imprimées (écart de revalorisation nul) ;
- zone fidélité absente d'un ticket imprimé à la demande avant clôture ;
- ticket d'acompte portant le numéro du client au lieu de sa raison sociale ;
- promotions de carte d'un client précédent reportées sur les paniers sans carte ;
- fichier de flux PRODUCTS, FAMILIES ou PRICES rejoué à chaque changement : désactivations d'articles et volumes de vente jamais reçus en caisse ;
- en-tête de colonne compté comme code inconnu à l'import d'une liste d'articles ;
- paramètre daté remplaçant aussitôt la valeur courante (F19 : valeur programmée tenue à côté de la valeur courante) ;
- vente non soldée clôturable par `/action/finish` ; erreur de paiement restée affichée après un geste réussi ; garantie chèque absente du journal du Back Office (FX) ;
- textes restés en français sur les écrans nl et pt (journal d'audit, aide ATCUD, touches dégradé et formation, écran de paiement, modal prix, parcours facture, natures des rapports), débordements de tableaux à 800, 1280 et 1366 px (prestataires, déploiements, listes, clients), pavés numériques coupés à 800 × 600.

Préconditions : construction dev, bases H2 mémoire jetables recréées à chaque lancement, matériel simulé (simulateur du module matériel, imprimante facturette, monnayeur, TPE). Nœuds :
- nœud imedge magasin PDV 07039, lancé avec `-Dpos.pdv.number=07039` ;
- caisse impos POS01 (îlot L1, ligne avant) et caisse C04 (îlot SLS, libre-service, exclu du flux Planexa) ;
- bouchon imfid (`shortMessage` de 3 lignes, 6 `commercialMessages` de 11 lignes), enregistreur HTTP (système de file unique, serveur Catalina), moteur de valorisation simulé, puits d'impression A4, partenaire de recherche externe, garantie chèque simulée (`cheque.guarantee-provider=SIMULATEUR`) ;
- central imedge (`pos.role=central`) et second magasin PDV 07040 avec sa caisse POS01, `pos.sync.central-url` vers le central, pour BO-03-06-48.

Ports de la recette : passe RK magasin 19070, POS01 19080, C04 19081, imfid 19060, enregistreur 19065 ; passe RL magasin 20070, POS01 20080, imfid 20060, passerelle TPE 20081 ; passe RM central 21090, magasin 07039 21070 et POS01 21080, magasin 07040 21071 et POS01 21082, bouchons 21060 ; passe F19 magasin 22070 (fuseau Australia/Adelaide, pour franchir minuit pendant la séance) et POS01 22080 ; passe FX magasin 23070 et caisse 23080. Une modification enregistrée atteint la caisse en 1,5 à 2,5 s.

**Configuration des caisses et configuration groupée**

1. BO-03-09-01 : désactiver POS01 sur `/admin/checkout-points`.
   - `/admin/reports/settlements` : « 0 ligne(s) … Aucune donnée sur la période demandée. » (actif : « CARD 3 … 2,40 € ») ; « Inclure les caisses désactivées » (`?inactive=true`) la remontre.
   - Déverrouillage de la caisse : 303 vers `/lock?error=deactivated`.
   - `/admin/day-end` : « Caisses ouvertes » liste POS01 et C04 actives, plus rien une fois désactivées ; l'alarme hors ligne et la clôture consolidée les ignorent.
2. BO-03-09-02, BO-03-09-03, BO-03-09-04 : `/admin/register-config`, niveau CHECKOUT_POINT, POS01, « Configuration enregistrée ».
   - entête HDR2 et pied FTR2 : « ENTETE DEUX TPV / Lille … PIED DEUX TPV » ; par défaut « ENTETE UN » ;
   - `ticket.vat-table` NONE : aucune ligne de TVA ; AMOUNT_ONLY : « Dont TVA 0,10 E » ; COMBINED : en plus « TVA 20,00% HT 0,50 TVA 0,10 » ; SEPARATE : coupon après la coupe « DÉTAIL TVA / Ticket: POS01-00000011 / TVA 20,00% HT 0,50 TVA 0,10 » ;
   - logos LOGO1 et LOGO2 importés en « Logo de ticket » ; `print.logo-code=LOGO2` : `[[IMAGE 384 121 ///wAAD///gAAH…]]`, LOGO1 : `[[IMAGE 384 86 ////…]]`.
3. BO-03-09-08 : la liste de l'hôtesse technique propose « 77770001 — Hélène Hotesse ».
   - Sur POS01 (Caisse) : « Une hôtesse technique ne s'affecte qu'à une caisse libre-service. » ; badge d'un caissier : « Le badge 77770002 n'est pas celui d'une hôtesse technique. ».
   - Enregistrée sur C04, elle vise une annulation de ticket sur C04 ; refusée sur POS01.
4. BO-03-09-09 : code-barres ticket désactivé : aucun code ; CODE128 : `[[BARCODE POS01-00000016]]` ; QR : `[[QR POS01-00000017]]` ; duplicata « DUPLICATA N°1 » avec `[[QR POS01-00000005]]`, rien une fois désactivé.
5. BO-03-09-10 : file unique non activée : touche absente, « La file unique n'est pas activée sur cette caisse. ». `queue.single-enabled` et `queue.single-url` posés : menu AUTRE « FILE UNIQUE : DISPONIBLE » ; appuis : « Caisse déclarée disponible pour le client suivant. » puis « Caisse déclarée non disponible. », l'enregistreur reçoit `{"terminal":"POS01","state":"AVAILABLE",…}` puis `UNAVAILABLE` ; adresse injoignable : « Système de file unique injoignable : l'état n'a pas été transmis. ».
6. BO-03-09-11, BO-03-09-14, BO-03-09-15, BO-03-09-16, BO-03-09-17 (matériel) :
   - tiroir désactivé, adresse 0x48F : touche `/drawer-open` masquée, `GET /api/pos/hardware-configuration` « drawer.enabled=false drawer.address=0x48F », `POST /api/pos/hardware-configuration/push` : PUSHED, bloc visible sur `/api/hardware/configuration` du simulateur ;
   - balance active, PLU 4020 à 1,250 kg : « 1,250 kg POMMES GOLDEN 1,250 kg x 1,08 €/kg 1,35 € » ; `hardware.scale.enabled=false` : « POIDS INVALIDE » ; balance DIGI 192.168.1.50:2001 : bloc « scale.protocol=DIGI scale.address=192.168.1.50 scale.port=2001 » ;
   - scanner de C04 désactivé : en-tête « Scanner : Désactivé », code ignoré ; réactivé, EAU MINÉRALE ajoutée ; pilote OPOS, périphérique DLS_Magellan_9800i, motifs Datalogic;Magellan : bloc poussé, la caisse laisse le scanner OPOS au module.
7. BO-03-09-18 : second scanner désactivé : `POST /api/pos/scan/customer` 204 ignoré ; activé : article refusé en 422 « Le scanner client ne lit que les cartes de fidélité et les coupons. », carte 2990000000011 en 200, écran « 2990000000011 · 12,50 € », le scanner principal ajoute les articles dans la même vente.
8. BO-03-09-19, BO-03-09-29, BO-03-09-30, BO-03-09-31 (monétique) :
   - `payment.terminal-type=VERIFONE`, adresse localhost:19067, sans redémarrage : client Verifone utilisé (« PAIEMENT REFUSE » injoignable, puis « Verifone exchange not implemented ») ; type vidé : TPE simulé, « CARTE BANCAIRE 0,60 » ;
   - `terminal.island-label=ILOT-L1` sur l'îlot L1 : export SETTINGS de POS01 et bloc « payment.island-label=ILOT-L1 », ajouté sous D7V à la requête Verifone ;
   - TR, ECOCHEQUE, GIFTPASS avec `{DAJ}01`, `{DBJ}01`, `{DCJ}01` : l'export TENDERS les porte, la requête est construite sur le fil de l'appelant ;
   - `terminal.pdv-label-enabled=true`, `terminal.pdv-label={D7P}PDV07039` : présents dans l'export SETTINGS de POS01.
9. BO-03-09-20 : prestataire CATALINA CATA vers l'enregistreur ; flux désactivé : 0 événement ; `catalina.enabled=true` sur POS01 : `POST /catalina/events` en 2 s avec `lines` (EAN 3300000000007, qté 2, 1.2) et `payments` (CARD 1.2) ; vente sur C04, non activée : 0.
10. BO-03-09-21, BO-03-09-22 : `hardware.printer.enabled=false` : rien d'imprimé ; true : ticket imprimé ; EPSON_TM, 19200, page 1252, largeur 58, retournement du chèque : bloc poussé et enregistré par le simulateur. `printer2` LOCAL A4 USB1 avec FACTURE:A4 : facture POS01-F000001 en HTML A4 sur `GET /api/hardware/printer2/content`, rien sur le rouleau ; format TICKET : « N° FACTURE POS01-F000002 » ; désactivée : rouleau.
11. BO-03-09-27 : `touch.external-apps=MONNAYEUR>…;MALFORME;CALC>…` : touches MONNAYEUR (`/external-app/0`) et CALC (`/external-app/1`) au menu AUTRE, entrée mal formée ignorée ; `/external-app/0` : 303 vers l'adresse ; `/external-app/7` : 404.
12. BO-03-10-06 : `export.planexa-enabled=true` : « 1 flux produit(s) pour le 2026-10-25. », `planexa-20261025.csv` « 2026-10-25;POS01;L1;12:00;23;23;15.00 … TOTAL » ; C04 (îlot SLS « Exclu du flux Planexa ») absente, présente une fois la case décochée.
13. BO-03-10-18, BO-03-10-19, BO-03-10-20 :
    - « Installer les données de formation » : « Données de formation installées : 5 élément(s) créé(s). », second clic « 0 élément(s) créé(s). » ; scan 3770000990010 : « ARTICLE TEST 1 1,00 € » ; formation/9999 ouvre « Caisse Formation » ;
    - fiche magasin renseignée : entête « BIENVENUE A Vaucresson - Intermarché Vaucresson », pied « TEL 01 47 00 00 00 TVA FR99123456789 SIRET 12345678900099 CPT FR76 3000 4000 0500 0012 3456 789 », facture avec la fiche entière ;
    - modèle HDR3 « %Nom Magasin% », « %Téléphone% %Ville% », « TVA : %TVA% » : « Intermarché Vaucresson / 01 47 00 00 00 Vaucresson / TVA : FR99123456789 » ; `%Inconnu%` conservé.
14. Configuration groupée (BO-03-10) : formulaire de groupe de `/admin/register-config` (clé, valeur, cible Îlot / Type / Liste / Toutes, date facultative) : « Appliquée à l'îlot … » ou « Configuration appliquée à 1 caisse(s). ».

**Menus de caisse**

15. BO-03-09-06, BO-03-10-16 : `/admin/cash-menus`, 101 modèles (SLS, LIGNE, M01 à M99) : « Menus caisse (101) ». SLS choisi sur le point POS01 : barre de menus [articles, autre], 3 colonnes, Remise, Remise globale et Quantité masquées ; modèle inconnu : « Menu caisse inconnu : NOPE ».
16. BO-03-10-09 : `menu.code=LIGNE` sur l'îlot L1 : POS01 en 4 colonnes, grandes touches ; SLS sur le point : le point l'emporte (articles et autre, 3 colonnes, ticket compact).
17. BO-03-10-17 : SLS : écran client `#cartLines{display:none}` (TOTAL_ONLY), ticket virtuel compact ; sans modèle, écran hôtesse 1920x1080 : 3 colonnes ; `discount.enabled=false` masque les touches de remise ; CHEQUE inactif absent de l'écran de paiement, présent une fois actif.

**Déploiement immédiat, programmé, partiel et complet, paramètres datés**

18. BO-03-12-01 : enregistrer un formulaire : `/api/referential/stamp` 1792926733151 → 1792926733152, la caisse applique en 1,5 s.
19. BO-03-12-02 : `period.pdv-actions` avec FOO inconnue et SAFE_COUNT en double : `/admin/day-end` « Clôtures consolidées … 2026-10-25 Clôturée 2 26 16.80 CLOSE_REGISTERS: 2 caisse(s) encore ouverte(s), fermeture ordonnée / SAFE_COUNT … / Z_REPORT … / BANK_DEPOSIT … » ; un jour fermé du calendrier ressort CLOSED_DAY.
20. BO-03-12-03 (partiel) : `/admin/releases`, « Programmer mes prochaines modifications » : « Vos prochaines modifications partiront le 25/10/2026 12:53. » et bandeau vert.
    - HDR3 modifié : tampon et empreinte DOCUMENT_TEMPLATES inchangés, la caisse garde l'ancien entête ; ligne « Modèles de document … Partiel /admin/templates/save Oui ».
    - « Avancer » : « Déploiement avancé… », la caisse imprime « ENTETE PROGRAMMEE » ; date atteinte : libéré seul (historique « Libéré », Par vide), « ENTETE A L HEURE ».
    - libellé de moyen de règlement modifié puis « Annuler » : « Déploiement annulé : le back-office reprend la version des caisses. », libellé revenu à « Titre restaurant » ; date passée : « La date d'effet doit être une date à venir. ».
21. BO-03-12-04 (complet) : « Programmer un déploiement complet » : « Déploiement complet programmé le 25/10/2026 13:17 : 25 domaine(s) retenu(s). » ; menu SLS passé à 2 colonnes et HDR3 modifiés depuis des formulaires non armés : retenus, la caisse garde 3 colonnes et l'ancien entête ; tout avancé : 2 colonnes et « ENTETE COMPLET ».
22. Paramètres datés (BO-03-12-03, BO-03-10, passe F19) : îlot L1 `terminal.island-label=ILOT-L1`, POS01 `hardware.drawer.address=0x48F`, échelon `hardware.printer.paper-width=80`, appliqués en 1 s. Programmer pour le 26/10/2026 :
    - POS01 drawer.address 0x500 et island-label LBL-POS01 : « Configuration enregistrée. » ; paper-width 58 sur `/admin/echelons` : « Paramètre d'échelon enregistré. » ; groupe LISTE POS01 `queue.single-url=http://q` : « Configuration appliquée à 1 caisse(s). » ;
    - l'export de POS01 garde 0x48F, ILOT-L1 (hérité), 80, sans queue.single-url ; empreinte SETTINGS inchangée (`9d9372dd…`) ; la caisse montre 0x48F, ILOT-L1 et 80 ;
    - indications « Valeur actuelle : 0x48F · Valeur programmée le 26/10/2026 : 0x500 » et « Valeur actuelle : héritée · Valeur programmée le 26/10/2026 : LBL-POS01 » ; `/admin/releases` 4 lignes avec les deux valeurs ; `/admin/echelons` « Valeurs programmées (4) » ;
    - annuler drawer.address : « Paramètre daté annulé. », 0x48F conservé ; avancer island-label : « Paramètre daté appliqué dès maintenant. », LBL-POS01 en caisse en 1 s ; reprogrammer 0x600 par le formulaire de groupe : la caisse reste à 0x48F ;
    - minuit d'Adelaïde : journal « promoteDue with today: 2026-10-26 » à 00:00:13, SETTINGS appliqués 2 s plus tard : drawer.address=0x600, paper-width=58, export queue.single-url=http://q ; plus aucune valeur programmée.

**Moyens de règlement, dépenses, mode dégradé monétique**

23. BO-03-02-01 : `/admin/tenders`, AMEX, nature CARD, « American Express » : « Moyen de règlement créé. », touche « American Express », ticket « American Express 4,92 E » ; nature VOUCHER : « Nature de règlement inconnue de la caisse : VOUCHER ».
24. BO-03-02-20 : CHEQUE « Apport / dépense autorisé » : proposé sur `/cash-movement` ; dépense de 10 € par chèque : `/withdrawal` « Chèques … -10,00 € », espèces inchangées ; bordereau « CHEQUE -10,00 E ».
25. BO-03-02-26, BO-03-02-29 : ACOMPTE « Duplicata en plus », acompte de 30 € sur CMD-4512 : « Acompte client » puis « Acompte client - DUPLICATA », MOTIF « Acompte commande CMD-4512 ». CHEQUE avec les îlots « CLS », POS01 sur l'îlot CLS : « REGLEMENT SOUMIS A CONTROLE SUR CET ILOT » ; liste vide : aucun appel.
26. BO-03-02-34, BO-03-02-36 : vente carte avec trames du terminal ; `payment.card-receipt-attached=true` : 2 coupes ; false : 3 coupes ; `payment.card-receipt-printed=false` : 1 coupe, aucune ligne CB. POS01 `hardware.cashchanger.enabled=true` et CASH « Connecté au monnayeur » : ESPÈCES poste `/action/pay-cashchanger`, panneau « MONNAYEUR EN COURS », 5 € insérés et TERMINER : CASH 2,76 €.
27. BO-03-02-37 : `/admin/prepaid-cards` GIFT (NEXO, ILLICADO, `6299:16`) ; carte 6299000000000017 : « ****0017 · ILLICADO SOLDE DISPONIBLE 30,00 € », 29,00 € sur `/voucher-balance` après 1 € payé.
    - Paiement décoché : « CARTE Carte cadeau Illicado NON ACCEPTEE EN PAIEMENT » ; consultation décochée : « CONSULTATION DU SOLDE NON AUTORISEE POUR LA CARTE … » ; activation décochée : ligne barrée, « ACTIVATION NON AUTORISEE POUR LA CARTE … ».
    - LOCAL : émetteur local seul, hub jamais appelé ; plage `62-99` refusée.
28. BO-03-02-39, BO-03-02-42 : CHF à 1,05 € avec rendu DEVISE, 25 CHF pour 23,52 € : « Rendu Client 2,60 CHF », ticket « DEVISE CHF 23,52 E / 22,40 CHF TAUX 1.050000 ». `change-order.roll-values=2:50.00:25;1:25.00:25` : « Rouleau 2€ × 25 (50.00€) » et « Rouleau 1€ × 25 (25.00€) » ; `2:40.00:25` ignoré.
29. BO-03-02-44 : AMEX `D16:43;D46:1010`, terminal en passerelle (`pos.tpe.mode=bridge`) : `{D16}43` : « American Express 2,76 E » ; `{D16}20{D46}1010` : « American Express » ; `{D16}20` : « CARTE BANCAIRE » ; saisie `D17:43` : « Correspondance monétique invalide : D17:43 ».
30. BO-03-02-45 à BO-03-02-47 : `/admin/lists`, liste de justification « Dépenses » servant EXPENSE.
    - `CONTROL:BLOKING` : « Option inconnue … CONTROL:BLOKING » ; TIMBRES 40 € en CHEQUE : bordereau « MOYEN CHEQUE » ;
    - RESTO désactivée : absente de la caisse au tirage suivant, envoi forcé refusé (`error=reason`) ; réactivée, elle revient ;
    - BLOCKING, 60 € pour 50 € : « MONTANT SUPERIEUR AU MAXIMUM DE LA DEPENSE » ; SUPERVISOR, 25 € pour 20 € : fenêtre responsable, visa mcurie ; WARNING, 35 € pour 30 € : « MOUVEMENT ENREGISTRE AU-DELA DU MAXIMUM DE LA DEPENSE ».
31. BO-03-02-48 à BO-03-02-50 : délai par défaut 15 min ; à 1 min, le mode dégradé forcé s'arrête seul après 60 s ; bascule `payment.degraded-mode=true` : carte acceptée d'office, 70 s plus tard la caisse attend le terminal. Visa d'activation : sans superviseur « AUTORISATION REFUSEE », mcurie « MODE DÉGRADÉ MONÉTIQUE ACTIVÉ (1 MIN) » ; visa de désactivation : mcurie « MODE DÉGRADÉ MONÉTIQUE DÉSACTIVÉ » ; chaque visa retiré, le geste passe sans superviseur.

**Remises et rabais**

32. BO-03-07-03, BO-03-07-06 : liste « Remises et rabais » ; touches ligne « -20% », « Rabais 1,50 », « Libre » au modal prix. « -20% » : « LAIT UHT 1L (Discount -20,00%) 2,21 € » ; « Rabais 1,50 » : « COCA-COLA 1.5L (Remise -1,50€) 0,66 € » ; « Remise ticket » (PERCENT,TICKET) : « REMISE TICKET (5 %) -0,44 € » ; PERCENT et AMOUNT ensemble refusés.
33. BO-03-07-04 : « Libre » `AMOUNT,MAX:30` : modal « Libre — plafond 30 » ; 40 : « PLAFOND DE LA TOUCHE Libre : 30 » ; 2 : après le visa du geste, « (Remise -2,00€) ».
34. BO-03-07-09 : « -20% » supprimée : touches ligne ['Rabais 1,50','Libre'] ; « Remise ticket » désactivée : plus de touche ticket ; touche forgée : « TOUCHE DE REMISE INCONNUE ».

**Retours**

35. BO-03-13-01 à BO-03-13-03 : `/admin/return-types`, TCK « Retour ticket » (mode ticket, actif) créé ; libellé manquant et plage inconnue refusés ; « Reprendre les types existants » : « 2 type(s) de retour repris de la liste et du paramètre. » ; `/return` : Contenant, Article défectueux, Retour ticket. TMP inutilisé : « Type de retour supprimé. » ; TCK utilisé : « Le type de retour TCK est rattaché à 1 retour(s) : il a été désactivé et non supprimé. » ; « Désactiver » retire la touche au tirage suivant, « Activer » la rend.
36. BO-03-13-04 : « Retour ticket » : bandeau « Scannez le ticket d'origine ou saisissez son N° et sa date » ; « 0001 » : « TYPE DE RETOUR TICKET : SCANNEZ LE TICKET D'ORIGINE OU SAISISSEZ SON NUMERO ET SA DATE » ; n° seul : « …SAISISSEZ LE NUMERO ET LA DATE DU TICKET D'ORIGINE » ; n° et 25/10/2026, n° complet ou scan du ticket : détail ouvert. Les autres types restent en retour en vente.
37. BO-03-13-11, BO-03-13-12 : `deposit.article-eans` et type DECON (déconsigne) ; vente, retour, bon 298… : `/admin/reports/deposit-returns` « Consigne 3300000000007 1,20 € / Retour déconsigne DECON 2,76 € / Reprise article consigné 0,60 € / Bon de déconsigne 2980000000508 5,08 € », TOTAL 1,20 € / 9,04 €. `/admin/reports/returns-by-type` : « Retour en vente · DECON · Déconsigne · LAIT UHT 1L · 2,76 € », « Retour en vente · TRI · Retour tri · POMMES GOLDEN · 3,50 € » et le remboursement TCK ; code de la plage RETTRI (type TRI) : « RETOUR Retour tri ENREGISTRE » sans superviseur, avec `return.without-origin-allowed=false`.

**Templates et documents imprimables**

38. BO-03-03-01 : `print.conditional-enabled=true`, `print.forced-documents=CARTE_PREPAYEE;DEMAT` : carte cadeau 6299000000000017 payée 2,76 : « PAIEMENT CARTE CADEAU … SOLDE RESTANT 27,24 E » ; article démat : « TICKET D'ACTIVATION … CODE ACTIVATION 7777000011112222 » ; clé retirée : rien ; FID forcé : « PAIEMENT FIDELITE / PAIEMENT TOTAL FID 1,00 E ».
39. BO-03-03-06, BO-03-03-09 : ticket en attente intégré « TICKET EN ATTENTE / POS01-00000025 / [[BARCODE POS01-00000025]] / Date : 25/10/2026 12:45 » ; modèle ATT1 (« Ticket en attente ») : « MODELE MAGASIN ATTENTE / … / IMPRIME LE 25/10/2026 12:46 / N TICKET POS01-00000027 ». Acompte 2,00 sur CO79-CMD79 : « ACOMPTE / COMMANDE CO79-CMD79 / CLIENT ACME SARL / MONTANT 2,00 E / TVA ACOMPTE C 5,50% … HT 0,18 TTC 0,19 / B 20,00% … / [[BARCODE …]] ».
40. BO-03-03-17, BO-03-03-23 : `ticket.line-order=FAMILY`, `ticket.family-level` 0 : « LAIT DEMI-ECREME », 1 : « FRAIS LS », 2 : « CREMERIE LS ». Avantage moteur « PROMO COCA -0,50 » : `ticket.promotion-placement=LINE` sous COCA-COLA, FOOTER au-dessus de TOTAL TTC, chacun l'emportant sur la place rendue par le moteur.
41. BO-03-04-06, BO-03-04-09, BO-03-04-10, BO-03-04-14 : `/admin/printable-documents`, FACT_PRO (FACTURE, A4), BL_SLIP (BON_LIVRAISON, FACTURETTE), UNUSED : « Document créé. » ; caisse « Quel document ? FACTURE PRO / BON DE LIVRAISON MICR / DOC INUTILISE ».
    - « Désactiver » BL_SLIP : « Document désactivé : il n'est plus proposé en caisse. » ; « Activer » : `/invoice/type/BL_SLIP` revient ;
    - supprimer FACT_PRO imprimé : « Document déjà imprimé en caisse : il est désactivé et non supprimé. » ; TMP : « Document supprimé. » ;
    - FACT_PRO en A4 : HTML vers l'imprimante réseau (3 pages), rouleau vide ; BL_SLIP : « Insérez la feuille 1 sur 5 », feuillets sur `/api/hardware/slip/content` ; UNUSED : rouleau.

**Factures**

42. BO-03-03-02, BO-03-03-04 : revue, « N° de commande » CMD-4711 : rouleau « N° commande CMD-4711 », A4 « N° commande : CMD-9 » ; sans échéance « Paiement dès réception de la facture », BETA « Échéance 30/11/2026 » ; titre « FACTURE PRO » ; `invoice.a4-lines-per-page=2` : « Page 1 / 3 … Page 3 / 3 » ; `invoice.slip-lines=12` : « Page 1 / 5… ». `ticket.vat-code=true` : « B 20,00 % 3,60 € 0,72 € 4,32 € », ticket « TVA B 20,00% HT 2,30 TVA 0,46 ».
43. BO-03-03-21 : `invoice.article-attributes=ORIGINE` : « Famille : LAIT DEMI-ECREME, ORIGINE : FRANCE » sur l'aperçu, le rouleau et l'A4 ; rien sur le ticket.
44. BO-03-04-16, BO-03-04-18 : `invoice.account-number-required=true`, client de passage : « Ce document exige un client portant un numéro de compte. » ; ACME (C100) : `/invoice/document/1`. `invoice.customer-validation=true` : « Vérifiez avant d'émettre … ACME SARL Compte C100 12 rue de la Paix 75002 Paris SIRET … NIF PT123456789 CHANGER » ; false : document ouvert directement.
45. BO-03-04-27, BO-03-05-08 : `fiscal.pt-documents=true` ; `fiscal.atcud-codes=FS:ABC` : « ATCUD: 0-1 » ; FS:AAJFJMVNTN : « ATCUD: AAJFJMVNTN-2 » au-dessus de `[[QR …H:AAJFJMVNTN-2…]]`, SAF-T `<ATCUD>AAJFJMVNTN-1</ATCUD>`. `ticket.footer-url=https://avis.intermarche.example/q` : ticket et facture rouleau finissent par `[[QR https://avis.intermarche.example/q]]`, l'A4 écrit l'adresse.

**Messages fidélité et prestataires**

46. BO-03-03-26, BO-03-03-27, BO-03-03-35 : carte fidélité, ticket imprimé par IMPRIMER TICKET.
    - « MSG2L LIGNE 1 BONJOUR / MSG2L LIGNE 2 MERCI » avant « CAGNOTTE DU JOUR +0,14 E », troisième ligne coupée ; `fidelity.short-message-printed=false` : absent ;
    - « COM1 L1…L10 … COM5 L10 » avant « Dont TVA » ; `fidelity.commercial-messages-max=2` : COM1 et COM2 ; 1 : COM1 ;
    - « SOLDES CARTE / SOLDE DISPONIBLE 12,50 E / TOTAL AVANTAGES JOUR 0,14 E / MONTANT UTILISE 1,00 E » et « SOLDES DES CAGNOTTES / CAGNOTTE 12,64 E / POUPANCA COMBUSTIVEL 3,20 E ».
47. BO-03-08-06, BO-03-08-07 : `/admin/providers`, HUBSIM « ARTICLE DEJA ACTIVE » → « Produit déjà activé » à la seconde activation ; HUBSIM SUCCESS : « ✔ Carte acceptée ». ILLICADO SUCCESS, texte ticket « Réglé {montant}, solde {solde} (carte {carte}) » : « Réglé 2,76 E, solde 21,72 E (carte / ****0017) » ; activation HUBSIM : « Réglé 7,20 E, solde 7,20 E (carte / ****2222) ».
48. BO-03-11-05, BO-03-11-06 : NEXO1 (Produits dématérialisés, NEXO Retailer, Au scan produit) et WYN1 (Wynid, À la fin du paiement) : « Fournisseur déclaré. » ; NEXO1 avec la sélection d'articles DEMAT et le modèle HDR1 listé.

**Groupes d'articles**

49. BO-03-01-03 : dupliquer GRPX (parent PARENTX, sous-groupe SUBX) en GRPY : « Groupe « GRPX » dupliqué en « GRPY » avec 1 sous-groupe(s). » ; lignes « GRPY … PARENTX 6 » et « GRPY-SUBX … GRPY 2 ».
50. BO-03-01-04, BO-03-01-05 : plage INT-000..INT-005 : 2 articles ; famille « 20 » (FRAIS LS) : Lait ; libellé « chips » : 1 ; « Ajouter au niveau 0 » : « 2 article(s) ajouté(s) au groupe « NIVEAU-0 ». » ; écran de vente : touches « Biscuits Chocolat 200g » et « Eau Minérale 1.5L », tuiles à la racine de SAISIE DIRECTE, aucune touche `/manual/cat/NIVEAU-0` ; « Eau Minérale 1.5L » vend « x1 EAU MINÉRALE 1.5L 0,60 € ».
51. BO-03-01-10, BO-03-01-13 : `/manual/cat/GRPX` : Baguette, Chips, Lait, Miel, Riz. Ventes, puis « Recalculer les volumes de vente » sur `/admin/touches` : « Volumes de vente recalculés : 5 article(s) vendu(s) sur la période. » ; `touch.article-sort-volume=true` : Lait, Riz, Miel, Baguette, Chips.
52. BO-03-01-16, BO-03-01-17 : import xlsx (en-tête EAN, 3 codes) : « 2 article(s) importé(s) dans le groupe « GRPX », 1 code(s) inconnu(s) », CSV accepté. « Désactiver » Lait : absent du groupe en caisse, scan « CODE INCONNU » ; réactivé, il revient avec sa description et ORIGINE.

**Codes-barres et GS1**

53. BO-03-06-41, BO-03-06-42 : plage EXTERNAL_LOOKUP, « Champ de la réponse » `article.ean` : `{"article":{"ean":"3300000000002"}}` → « x1 LAIT UHT 1L 2,76 € », réponse sans le champ → « REPONSE DU SERVICE EXTERNE ILLISIBLE ». Plage PROMOTION_ENGINE : « CODE 2812345678901 TRANSMIS AU MOTEUR DE PROMOTION », second scan « DEJA TRANSMIS », `scannedBarcodes: ['2812345678901']` ; `valuation.local-only=true` : « MOTEUR DE PROMOTION NON APPELE : CODE NON TRANSMIS ».
54. BO-03-06-43 : plage PROMOTION PROMO-BEURRE en AMOUNT, 2700220001500 : « PROMOTION PROMO-BEURRE DECLENCHEE SUR BEURRE DOUX 250G », puis « DEJA DECLENCHEE » ; `cardPromotions: {ean 3300000000009, AMOUNT, 1.5, IMMEDIATE_DISCOUNT}`, `scannedPromotions: ['PROMO-BEURRE']`.
55. BO-03-06-44, BO-03-06-58 : article ACTIVATION_REQUIRED : « SCANNEZ LE CODE D'ACTIVATION ! », ligne « CODE D'ACTIVATION : 7777000011112222 », activé sur le hub (« TRANSACTION HUB00000001 », HUBSIM). CUSTOMER_ORDER 2500047000000 : « COMMANDE 00047 CHARGÉ OK » (mode BOTH ; KEYED : « CE DOCUMENT SE RÉCUPÈRE PAR LA SAISIE… ») ; 2599999000000 : « COMMANDE 99999 INCONNUE DU NOEUD MAGASIN ».
56. BO-03-06-48 (central, 07039, 07040) : carte cadeau 296000000000001 émise au 07039, présente au central, scannée au paiement au 07040 : réglée 2,76, solde 22,24 € au central et au 07040 ; code inconnu en BLOCK : « BON INCONNU AU REGISTRE », en INFO : « BON INCONNU DU SYSTÈME : AUCUN RÈGLEMENT ! ».
57. BO-03-06-62 : `/admin/journal/barcodes-issued` : « POS01 POS01-00000040 Carte cadeau 296000000000001 25,00 € 25/10/2026 13:04:41 — 25,00 € » et « POS01 <id du mouvement> Acompte CO78-CMD78 2,00 € ».
58. GS1 : `gs1.ai-alerts=240=BLOCK:Réf {value}`, scan `]C10103300000000002240REF77` : refusé « Réf REF77 » ; sans l'alerte : « x1 LAIT UHT 1L ».

**Garantie chèque et clôture non soldée**

59. BO-03-02-24 : CHEQUE avec « Autorisation chèque » et « Saisie du n° de compte », `cheque.guarantee-provider=SIMULATEUR`.
    - Sans compte : « NUMERO DE COMPTE DU CHEQUE OBLIGATOIRE » ;
    - compte 123, 20 € : « CHEQUE GARANTI - AUTORISATION GAR000001 », sans superviseur, puis recto imprimé (« deux euros et zéro centimes … 2,00 EUR ») ;
    - compte 000… : « CHEQUE REFUSE PAR LA GARANTIE : CHEQUE EN OPPOSITION » ; 1600 € : « …MONTANT SUPERIEUR AU PLAFOND GARANTI » ;
    - compte 999… : « GARANTIE CHEQUE INDISPONIBLE - AUTORISATION SUPERVISEUR REQUISE » ; fournisseur vide : « AUTORISATION CHEQUE REQUISE », mcurie autorise ; MICR décoché : chèque enregistré sans lecteur.
60. Clôture non soldée et erreur de paiement (passe FX) : panier de 4,92 €.
    - `GET /action/finish` depuis l'écran de vente : 303 vers `/`, « VENTE NON SOLDEE - RESTE A PAYER 4,92 € », panier conservé ; `/pay` s'ouvre avec la zone de message vide ;
    - espèces 1,00 €, puis `/action/finish` : 303 vers `/pay`, « …RESTE A PAYER 3,92 € », paiement CASH 1,00 conservé ; même résultat en HEAD ;
    - chèque sans compte : « NUMERO DE COMPTE DU CHEQUE OBLIGATOIRE », effacé au geste suivant ; chèque de 3,92 € sur le compte 123 : seul « CHEQUE GARANTI - AUTORISATION GAR000001 », reste 0,00 ;
    - `/action/finish` : 303 vers `/` ; journal de la caisse : trois « Closing refused » et un seul TICKET_CLOSED, après paiement.
61. Journal du Back Office, ticket POS01-00000001 CLOSED : « CASH 1,00 / CHEQUE — Garantie chèque : GAR000001 (SIMULATEUR) 3,92 » ; en nl « Chequegarantie: », en pt « Garantia de cheque: ».
62. Parcourir le menu du Back Office (63 pages et sous-pages du lot 19) en fr, nl et pt, à 800 × 600, 1280 × 800 et 1366 × 768 : pas de défilement horizontal, aucun tableau ni champ qui déborde, aucun texte français sur les pages nl et pt hors données ; écrans de caisse vente, paiement, mouvements, modal prix, recherche de retour et parcours facture dans les trois langues.

Recette exécutée le 25/10/2026 en cinq passes (RK configuration des caisses, menus et déploiement ; RL moyens de règlement, dépenses, remises et retours ; RM modèles, documents, factures, fidélité, prestataires, groupes d'articles et codes-barres ; F19 paramètres datés, date franchie à minuit ; FX clôture non soldée, erreur de paiement et garantie chèque au journal), profil dev, bases H2 mémoire jetables, matériel simulé, bouchons imfid, valorisation, impression A4, file unique, Catalina et garantie chèque. Les étapes 1 à 62 ont été jouées avec les messages, codes et valeurs ci-dessus. Les balayages du menu du Back Office sont propres dans les trois langues après correctifs, et les 351 captures `bo19-*` et `pos19-*` des écrans du lot sont conformes.

Limites :
- onze lignes restent partielles :
  - BO-03-09-15, -17, -21, -22 : les pilotes DIGI, la couche OPOS Datalogic, l'application du type, de la vitesse, de la page de code et de la largeur d'imprimante, et le pilote de la seconde imprimante relèvent du module matériel, hors de ces dépôts ; la seconde imprimante ne se choisit pas par document en caisse ;
  - BO-03-09-19 : les protocoles Ingenico SIBS et Worldline ne sont pas implémentés, et l'échange Verifone non plus ;
  - BO-03-09-29, -30, -31 : la trame et l'échange filaire Verifone restent à écrire ; les codes et le label sont placés en fin de trame en attendant la spécification Verifone ;
  - BO-03-09-27 : le lancement d'un exécutable local est volontairement refusé, pour la sécurité ;
  - BO-03-11-05, -06 : fournisseurs, protocoles et phases d'appel sont administrés, mais les échanges NEXO, wynid, Prosodie et ATOS ne sont pas implémentés ;
- BO-03-06-43 : la promotion part au moteur au format documenté des promotions de carte ; imvaluation n'est pas dans ces dépôts, la recette l'a jouée avec un moteur simulé ;
- BO-03-03-26, -27 : les champs `shortMessage` et `commercialMessages` du contrat imfid sont prouvés avec un bouchon ; le service imfid doit encore les émettre ;
- déploiement programmé : un domaine retenu absorbe toute modification ultérieure jusqu'à la date, et l'annulation suit les règles d'application de chaque domaine ; un seul paramètre daté en attente par niveau et par clé ;
- BO-03-06-48 : après une utilisation dans un autre magasin, le magasin émetteur garde sa ligne au solde d'émission ; le central et le magasin d'utilisation portent le solde à jour ; transmission au mieux (2 s, sans reprise).

Validation : imedge 5 355 tests Java + 11 JavaScript ; impos 8 544 Java + 319 JavaScript, sans échec, erreur ni test ignoré. JaCoCo : imedge 29 013 lignes et 14 360 branches, impos 35 041 lignes et 18 442 branches, tous les compteurs manqués à zéro ; JavaScript externe instrumenté à 100 %. Les 313 classes copiées entre imedge et impos sont identiques au paquet près.

Cotation (feuille Back Office) : les 97 lignes non intégrales du bloc 03 Paramétrage sont recotées : 86 passent intégralement couvertes, dont 84 par CODE et 2 par RELECTURE (BO-03-06-44, BO-03-06-58) ; 11 restent partielles, leurs commentaires actualisés (BO-03-09-15, -17, -19, -21, -22, -27, -29, -30, -31, BO-03-11-05, -06). Back Office P1 : 591/704 intégrales (83,9 %), 92 partielles (13,1 %), 21 non couvertes (3,0 %), soit 683/704 intégrales ou partielles (97,0 %). Toutes priorités : 647 / 112 / 37, couverture pondérée 88,3 %. Bloc 03 : 286 / 11 / 0. Campagne : 396 promotions, dont 381 CODE et 15 RELECTURE.
## HD_43 — Lot 20 : BO-02 Référentiels, BO-07 e-Commerce, BO-09 Fin de journée

Gain : le Back Office imedge intègre et administre les référentiels du bloc BO-02 et les caisses impos les appliquent : codes de vente, code interne, modes de passage et unités de mesure, articles liés et complémentaires, nomenclature, recherche multicritère, sélections et droits par champ de la fiche, TVA par pays avec exonérations et mentions légales, ventes pour un tiers, étiquettes électroniques, tares et rappel de lots ; remontée des clients en compte et documents fiscaux portugais ; points de vente numérotés, importés, changeant d'enseigne avec héritage, regroupés par adhérent jusqu'au journal électronique central. Le bloc BO-07 intègre les ventes des systèmes tiers e-commerce et mobilité (canal, PDV d'origine, fidélité, livre de caisse, factures et SAF-T, NIF). Le bloc BO-09 complète la fin de journée : tickets en attente enregistrés et remis à zéro, fermeture forcée avec ticket technique, éditions imprimées, scripts avant et après, déclaration automatique des îlots libre-service, activation du lancement automatique et contrôle caisse / serveur.

Écrans et domaines nouveaux du Back Office :
- `/admin/measure-units` (domaine MEASURE_UNITS diffusé avant PRODUCTS), `/admin/article-field-rights`, `/admin/eeg-providers` ;
- `/admin/third-party-systems` ; rapports « Chiffre d'affaires par canal », « État caissière détaillé » (l'état existant devient « État caissière synthétique ») et « CA par point de vente et par adhérent » ;
- `/admin/documents/view` (détail d'un document) ; `/admin/adherent-journal` et son détail, sur le nœud central ;
- flux `POST /feeds/import/RECALL_LOTS` et `POST /feeds/import/PDVS` ; colonnes nouvelles des flux PRODUCTS (SALE_CODES, CHECKOUT_MODE, VAT_NUMBER, VAT_EXEMPT, unité…) et VAT_RATES (COUNTRY, LEGAL_MENTION, EXEMPT, EXEMPTION_CODE) ;
- enrichis : `/admin/articles` (recherche, fiche, action de masse, envoi EEG), `/admin/article-selections`, `/admin/vat-rates`, `/admin/lists` (contenants de tare), `/admin/images` (usage « Visuel de contenant de tare »), `/admin/customers`, `/admin/documents`, `/admin/echelons` (vue d'héritage, table des adhérents), consoles de supervision (filtre adhérent), `/admin/ecommerce-orders`, `/admin/day-end`, `/admin/checkout-islands`, `/admin/reports` (TVA par famille, ventes par famille).

Correctifs mis au jour par la recette et par les groupes :
- distribution du référentiel (groupe P) : la caisse n'appliquait que le dernier fichier de flux puis prenait l'empreinte du magasin, si bien que deux deltas entre deux tirages ou une modification du back-office antérieure au tirage ne l'atteignaient jamais ; les fichiers de flux portent désormais l'empreinte avant et après intégration, la caisse n'applique un fichier que depuis son état de base et retombe sinon sur les pages complètes ;
- modèle de TVA réconcilié (groupe PQ) : un seul drapeau d'exonération avec son code, une seule empreinte de régime, le régime nommé par l'article l'emporte sur celui du prix ; ventilation par taux, régime, exonération et vente pour un tiers, dans le même ordre sur le ticket, le coupon TVA et les totaux enregistrés ;
- nouveau tirage de la TVA en caisse par mise à jour et non par effacement (les prix perdaient leur lien de TVA) ; NC, FT et RC renvoyées au magasin après conversion ou reçu ;
- code interne vide stocké en chaîne vide (second article en violation d'unicité) ; repli ligne à ligne de l'import des prix sur un champ inexistant ; refus de quantité entière resté affiché après une saisie valide ;
- écran de fin de journée sans tickets en attente (les caisses n'envoient pas leurs paniers) : le nœud les lit désormais dans la chaîne des enregistrements de tickets en attente ;
- critère famille ignorant le schéma de nomenclature : un rayon ou une activité ne sélectionnait rien (recherche, sélections, rapports, exports) ;
- rapport TVA par famille groupé par taux seul : deux régimes à 0 % fusionnés, ligne exonérée classée sous le premier régime à 0 % ;
- FT et NC portugaises imprimant les exonérations en « 0,00% » et fusionnant la TVA pour tiers ;
- vue de document affichant le balisage brut d'impression (`[[IMAGE …]]`, `[[QR …]]`, `[[BARCODE …]]`) ;
- vue d'héritage listant des profils affectés à une autre enseigne ;
- ticket technique d'un poste verrouillé imprimant « Opérateur : — » ;
- textes restés en français sur les écrans nl et pt (critères de sélection en chaîne brute, natures de connecteur EEG, choix des options de rapport, issue des appels superviseur au tableau de bord, libellés de graphique de rapport, « désactivé » de la fin de journée, écran des fruits de la caisse) ; libellés du code article qui ne citaient que l'écran ; rapport des ventes par famille débordant à 800, 1280 et 1366 px.

Préconditions : construction dev (`-Dquarkus.profile=dev`, jeu de données et matériel simulé compris), bases H2 mémoire jetables recréées à chaque lancement, matériel simulé (balance, imprimante, scanner). Nœuds :
- nœud imedge magasin avec sa caisse impos POS01 ;
- pour les cas PDV et adhérent : central imedge (`pos.role=central`) et deux magasins 10001 et 10002 de l'adhérent AD1, `pos.sync.central-url` vers le central, chacun avec sa caisse POS01 ;
- bouchons : imfid, gestion commerciale (réponses 503, 503 puis 200), imprimante réseau en port brut, fournisseur d'étiquettes électroniques REST ;
- flux importés par `POST /feeds/import/{CODE}` (admin/admin), listes de la colonne ATTRIBUTES séparées par des virgules.

Ports de la recette : passe RA magasin 19070 (PDV 07039, FR/ITM une fois les échelons posés), POS01 19080, central 19090 (balayage seul), bouchon EEG 19061 ; passe RB central 20090, magasins 10001 sur 20070 et 10002 sur 20071, caisses 20080 et 20081, bouchons imfid 20060, gestion commerciale 20061 et imprimante réseau 20062.

**Articles : codes, modes de passage, unités, quantités**

1. BO-02-03-03, BO-02-03-04 : flux PRODUCTS `SALE_CODES=3770002000014;PPV7720`, code interne `INTPERC20` ; scans 3770002000014, PPV7720 puis INTPERC20 : une seule ligne « x3 PERCEUSE P20 3 x 99,00 € ».
2. BO-02-03-05 : TV en `CHECKOUT_MODE=PRICE_CALL` sans prix : « PRIX DE L'ARTICLE INTROUVABLE : VENTE IMPOSSIBLE » ; tarifée 399,00 puis `/action/price-mod/force_price` : « PRIX IMPOSÉ PAR LA FICHE ARTICLE ».
3. BO-02-03-06 : SALADE `MEAL_VOUCHER_ELIGIBLE` ; TR de 20 € sur un ticket de 160,64 € : accepté pour 6,00, ticket « TICKET RESTO 6,00 E » ; ticket de LAIT seul : « TR: AUCUN ARTICLE ELIGIBLE », journal « Paiement TR refusé ».
4. BO-02-03-09 : remise sur PERCEUSE (`DISCOUNT_FORBIDDEN`) : « REMISE INTERDITE SUR CET ARTICLE » ; remise ticket de 10 % sur TV 399, SALADE 6 et PERCEUSE 99 : « REMISE TICKET (10 %) -40,50 € », perceuse exclue ; `discountForbidden` dans la requête de valorisation.
5. BO-02-03-11, BO-02-03-18 : GraphQL `updateProduct(attributes: "RECALL=true…", saleCodes: ["SAL2026"])` répondue 200 ; fiche « Article en retrait / rappel » cochée et SAL2026 affiché ; au tirage suivant, scans de POMMES et de SAL2026 : « ARTICLE EN RETRAIT/RAPPEL ».
6. BO-02-03-17 : `/admin/measure-units`, « Charger les unités standard » : « 10 unité(s) standard créée(s) », compteur « 10 / 99 » ; CABLE importé en unité B : saisie « 1,50 € / mb », ligne « 2,50 mb x 1,50 €/mb », ticket « 2,50 mb x 1,50 E/mb ».
7. BO-02-03-21, BO-02-03-22, BO-02-03-23 :
   - FLEURS (`PRICE_TO_ENTER`) : « PRIX À SAISIR », 7,50 : « 1 pce x 7,50 €/pce » ;
   - PILES (`QUANTITY_TO_ENTER`) : 2,5 : « QUANTITÉ ENTIÈRE REQUISE », puis 3 : « 3 pce PILES P20 12,00 € », erreur effacée ;
   - CABLE (`DECIMAL_QUANTITY_TO_ENTER`) : 2,5 : « 2,50 m x 1,50 €/m 3,75 € ».
8. BO-02-03-24, BO-02-03-25 (partiels) : POMMES (WEIGHED) scannée par son EAN passe à la balance : « 1,234 kg x 2,50 €/kg 3,09 € » ; `GET /api/pos/deposit-control` : POMMES `weighed:true, expectedKg:1.234`, CHAISE `bulky:true, controlled:false, expectedKg:null`.
9. BO-02-03-39 : liste TARES (TARE_CONTAINER) sur `/admin/lists`, BQS 0,035 actif, SAC 0,010 inactif ; image BQS d'usage « Visuel de contenant de tare » ; écran `/fruits` de la caisse : « TARE : AUCUNE » et bouton BQS avec son visuel, SAC absent.
10. Distribution : deux fichiers PRODUCTS avant un même tirage : la caisse prend les pages et tous les articles sont présents ; delta sur une caisse à jour : « appliqué depuis le contenu » ; fichier de flux puis modification au back-office avant le tirage : fichier puis pages appliqués, libellé final « LAIT BO ».

**Articles liés et complémentaires, nomenclature, fiche**

11. BO-02-03-15 : BIERE `LINKED_ARTICLE_EAN` / `LINKED_ARTICLE_QUANTITY=6` : « x1 BIERE P20 1,20 € / x6 CONSIGNE P20 6 x 0,10 € 0,60 € ».
12. BO-02-03-16 : `COMPLEMENTARY_EANS` modifiés sur la fiche de LAIT, tirage suivant (différé tant qu'une vente est ouverte) ; scan de LAIT : « À PROPOSER : VIS P20, CHEVILLES P20 » (`article.complementary-suggestions`, `article.complementary-max` = 3).
13. BO-02-03-14, BO-02-03-28 : flux NOMENCLATURE et « Rattacher au nœud » : « Activité 20 FRAIS LS › Rayon 2020 CREMERIE LS › Famille 20200200 LAIT › Sous-famille 202002000001 LAIT DEMI-ECREME » ; fiche : « Code TVA de l'article », « TVA appliquée B — 20 % (régime du prix) », mode de passage, unité « B — Mètre bobine (mb) », fournisseur, prix EEG et attributs.

**Recherche, sélections, droits de champ**

14. BO-02-03-30 : `/admin/articles` : plu=4921 → POMMES ; internalCode, description, brand=VISCO (2), supplier=Outils / F01, prix 20–100, vat=9 → DON, attribut BULKY → CHAISE ; family=20 (activité) → articles LAIT.
15. BO-02-03-31 : `/admin/article-selections`, sélections Visserie, Gros prix et Frais (famille 20) pour 07039 ; « Copie de Gros prix » dupliquée et retrouvée (q=Copie) ; export CSV de « Gros prix » (6 lignes) ; ventes par famille restreintes à « Gros prix » 582,10 € ; rapport TVA restreint à « Frais » 2 lignes ; critères traduits (« Prijs van (€) : 20 · Prijs tot (€) : 500 »).
16. BO-02-03-12 : action de masse sur la sélection « Visserie » : « Code article à l'écran activé sur 2 article(s). » ; ligne caisse « VIS P20 3770002000021 », ticket « VIS P20 / 3770002000021 ».
17. BO-02-03-33 : `/admin/article-field-rights`, profils « Chef de rayon » (mcurie) et « Comptable », EAN, TVA et suppression réservés à Comptable :
    - pour mcurie, champs EAN et TVA désactivés, suppression absente ; EAN et TVA postés ignorés, libellé « Lait Marie » enregistré ; suppression : « Votre profil ne permet pas de supprimer un article. » ;
    - en admin : LAIT vendu « déjà vendu : désactivé et conservé », CHEVILLES jamais vendu « supprimé ».

**TVA par pays, exonérations, mentions légales, compte de tiers**

18. BO-02-02-01 : `/admin/vat-rates`, régimes 10 (0 %, exonéré, m07 enregistré M07, « Isento artigo 9.º do CIVA ») et 11 (0 %, exonéré, M16, « Isento artigo 14.º do RITI »), 14 second régime à 20 % ; exonération à 6 % : « Une exonération porte un taux de 0 %. » ; régime BE 13 servi au 07039 tant que le PDV n'a pas de pays, exclu une fois FR/ITM/07039 déclaré.
19. BO-02-02-01, BO-02-02-02 : `fiscal.pt-documents=true`, `ticket.vat-code=true`, `ticket.vat-table=COMBINED` ; FS POS01/2026/00000001 : lignes « CODE TVA : K », « CODE TVA : L », tableau « TVA K EXO HT 2,76 TVA 0,00 / TVA L EXO HT 0,96 TVA 0,00 », puis « Isento artigo 9.º do CIVA / Isento artigo 14.º do RITI » ; conversion : « NC POS01/2026/00000001 - FT POS01/2026/00000001 », les deux avec les deux mentions ; le RC de la FT imprime les deux mentions ; rapport TVA par famille : « 10 Isento 10 » et « 11 Isento 11 » distincts.
20. BO-02-03-26, BO-02-03-27 : régime 9 exonéré par VAT_RATES, DON en `VAT_NUMBER=9` ; fiche « Code TVA de l'article J — 0 % Exonere dons (EXO) » ; ticket « TVA J EXO HT 1,00 TVA 0,00 » ; flux de chiffre d'affaires « EXO;1.00 » ; rapport TVA par famille « 9 Exonere dons ».
21. BO-02-03-41, BO-02-03-42, BO-02-03-43 : TIMBRE en `THIRD_PARTY_SALE` :
    - ticket « TVA B 20,00% (cpte tiers) HT 1,25 TVA 0,25 » à côté de « TVA B 20,00% HT 51,78 TVA 10,36 » ; FT et NC portugaises avec les mêmes rubriques ;
    - rapport TVA par famille : le total 20 % exclut TIMBRE et HUILE, 71,06 € = 64,64 + 13,92 − 1,50 − 6,00 ;
    - flux régénérés par `/admin/sync/export` : tranca3 « COMPTE_TIERS;1.50;1.50;0.00 », « EXO;1.00 » ; tranfc « TVA_COMPTE_TIERS;-0.25 », « TOTAL_HORS_TVA_TIERS;629.89 ».

**Étiquettes électroniques**

22. BO-02-03-37, BO-02-03-38 (partiels) : `eeg.enabled=true`, fournisseurs HAN (simulé) et SES (REST, bouchon 19061) sur `/admin/eeg-providers` ;
    - fiche : « Prix affiché par les étiquettes électroniques: Hanshow 99.00 € Conforme / SES-imagotag 99.0 € Conforme », le bouchon reçoit `GET /prices/3770002000007` ;
    - envoi depuis la liste avec la sélection « Gros prix » : « Prix de 6 article(s) envoyés à 2 fournisseur(s) d'EEG sur 2 », le bouchon reçoit `POST /prices [{"ean":"3300000000033","price":30.00},…]`.

**Rappel de lots**

23. BO-02-03-44 : `POST /feeds/import/RECALL_LOTS` `3770002000113|L2401;L2402` : `{"updated":1}` ; GS1 `010377000200011310L2401` : « PRODUIT EN RAPPEL - LOT L2401 », refusé ; lot L9999 vendu ; scan de l'EAN : « PRODUIT EN RAPPEL - LOTS : L2401, L2402 ».

**Clients et remontée**

24. BO-02-04-13 : `channel.commercial.customer-uplink-url` vers le bouchon 20061 ; ville de C0002 changée en « Porto Corrigido » : fiche « En attente d'acquittement — 0 tentative(s) », puis « 1… / 2 tentative(s) (HTTP 503) », puis « Acquittée le 2026-10-25T18:13:21 » ; le bouchon journalise 503, 503, 200 `{"accountNumber":"C0002","source":"BACK_OFFICE",…}`.
25. BO-02-04-13 : client créé en caisse (`/invoice/customer/create`, Café Ribeira, PT503504564) : reçu par le bouchon `{"accountNumber":"POS01-CLI000001","origin":"REGISTER","source":"REGISTER",…}` ; les clients importés de la gestion commerciale ne remontent pas.

**Documents fiscaux PT**

26. BO-02-04-19 : `/admin/documents` liste RC, NC, FT, « FS · annulé », la FACTURE et la FS e-commerce ; chaque filtre de type ne rend que son type ; NIF 123456789 : FT, RC et FS ECOM2026/1-2 ; CSV `FT POS01/2026/00000001;FT;…;123456789;…;0-1;FS POS01/2026/00000002;0` ; `/admin/documents/view?number=FT POS01/2026/00000001` : ATCUD, document d'origine, QR code et lignes, sans balisage brut.

**Points de vente : numéro, enseigne, import, héritage, adhérents et journal par adhérent**

27. BO-02-05-03 (central) : `POST /feeds/import/PDVS` : 3 PDV créés, pays FR/PT et enseignes IF/IP, ligne 5 (123) rejetée ; rejeu : 0 créé, 0 mis à jour ; ligne de renumérotage : 1 mis à jour ; ancien numéro inconnu : « PDV à renommer introuvable » ; fichier rejoué après le passage de 10001 en IP à l'écran : 10001 reste sous IP.
28. BO-02-05-01 : « 1234 » : « Le numéro de PDV doit comporter exactement 5 chiffres. » ; 10004→10005 à l'écran puis 10005→10007 par import (PREVIOUS_NUMBER) ; recréer 10004 : « Le numéro 10004 a été porté par un autre point de vente… » ; renuméroter vers 10001 : « Ce numéro de PDV est déjà utilisé. » ; historique de 10007 « Numéro 10005 10007 Import / Numéro 10004 10005 Écran ».
29. BO-02-05-02 : 10001 passé de IF à IP : historique « Enseigne IF IP » ; hérités fiscal.pt-documents et language.system=pt (Enseigne IP), receipt.footer.legal (Pays PT), ticket.vat-code (Enseigne IP) ; magasin et back-office en pt ; caisse 20080 « TVA remplacé : 7 régime(s) » et « gabarits appliqués : 1 » ; modèle RECU_PT (COUNTRY:PT) imprimé « FATURA SIMPLIFICADA » ; profil « Caissier Portugal » affecté à ENSEIGNE:IP reçu par 10001, pas par 10002.
30. BO-02-05-04 : vue d'héritage de 10001 sur `/admin/echelons` du central : chaque paramètre avec son origine (Enseigne IP, Pays PT), TVA « 10 IVA normal (23 % PT) · 11 Isento (0 % PT) », modèle « RECU_PT (COUNTRY:PT) », comptes et profils « jdupont : Caissier Portugal » ; pour 10002/IF, plus de profil de l'enseigne IP.
31. BO-02-05-05 : ventes sur les deux caisses, panier annulé sur 20081 ; les magasins journalisent « Journal remonté au nœud central : … ticket(s) pour le PDV … » ; central `/admin/adherent-journal?adherent=AD1` : 11 lignes (caisse, e-commerce ECOM/MOBI, annulé) de 10001 et 10002.
    - filtres : AD2 0, pdv 1, CANCELLED 2, montant 5–7 7, pays PT 10, enseigne IF 1, caisse + numéro 2, date future 0 ;
    - « Ouvrir » : « Provenance AD1 / 10002 — Villeurbanne / Reçu au nœud central le … », lignes, paiements et ticket imprimé ; identifiant inconnu : « Aucun ticket remonté… » ;
    - rapport « CA par point de vente et par adhérent » : « AD1 10001 … 8 42,72 € / AD1 10002 … 2,76 € », option « * » : une ligne AD1 ; console `group=ADHERENT&adherent=AD1` : « AD1 : 4 / 0 » ; table des adhérents sur l'écran Échelons.
32. BO-02-05-01, BO-02-05-05 : après 10002→10008, journal par adhérent et rapport CA gardent les tickets sous « 10002 Villeurbanne (ancien n°, actuel 10008) » ; sur un magasin, `/admin/adherent-journal` indique que ce nœud n'est pas le nœud central.

**Systèmes tiers e-commerce et mobilité, canal, fidélité, factures et NIF**

33. BO-07-01-01 : `/admin/third-party-systems`, SITE_WEB (e-commerce, PDV 0101) et APP_MOB (mobilité) ; avec leurs jetons (`X-Integration-Token`) : INTEGRATED ECOM-W1001 et MOBI-M2001 ; jeton inconnu : 401 « Jeton de système tiers inconnu » ; mobilité sans système : DISABLED « Aucun système tiers de mobilité déclaré » ; `channel.integration.token-required=true` et jeton partagé seul : 401 « Jeton de système tiers obligatoire » ; système désactivé : DISABLED « Système tiers désactivé ».
34. BO-07-01-02 : `pdvNumber=99999` : REJECTED « Point de vente d'origine inconnu du nœud magasin » ; rapport « Chiffre d'affaires par canal » et écran des commandes : « E-commerce 5 31,00 € / Mobilité 1 6,20 € » ; au central, les tickets e-commerce comptent pour le PDV 10001.
35. BO-07-01-03 : commande avec la carte 4242424242 : le bouchon imfid reçoit `POST /api/events/ticket-closed {"ticketRef":"2026-ECOM-W1001","card":"4242424242",…,"promotionChannel":"WEB"}` ; rapport `fidelityQueued=true`, écran « Cagnotté ».
36. BO-07-01-04 : `tranfc-20261025.csv` : « ECOMMERCE/PAYPAL;31.00;5 » et « MOBILITY/PAYPAL;6.20;1 ».
37. BO-07-01-06 : SAF-T du jour : FS ECOM2026/1-3 et FS MOBI2026/1 en SourceBilling P ; documents de la plateforme WEB2026/42 et « FS WEB2026/43 » (format `TYPE SERIE/RANG`) en SourceBilling I.
38. BO-07-01-07, BO-07-01-08 : `customer.tax-id-pt-check=true` ; NIF 123456789 conservé ; « pt 123 456 789 » : PT123456789 ; 123456788 : REJECTED « NIF client invalide » ; NIF dans le rapport de commande, l'écran des commandes et CustomerTaxID du SAF-T ; sans NIF : customerTaxId 999999990, `customerTaxIdDefaulted=true`, `/api/ecommerce/tickets/ECOM-W1005` rend 999999990, SAF-T CustomerID « Consumidor final ».

**Fin de journée : tickets en attente, fermeture forcée, éditions, scripts, déclaration automatique, contrôle caisse / serveur**

39. BO-09-01-05, BO-09-01-06, BO-09-01-08, BO-09-03-03 (fin de période automatique, magasin 10002, heure de fin 17:50, ticket en attente, vente en cours) :
    - alerte « Fin de periode a 17:50 . Il reste 3 min … 1 ticket(s) en attente » ;
    - journal « Fermeture de fin de période demandée à la caisse POS01 (déclaration automatique de l'hôte) » ; la caisse en pleine vente attend, puis imprime après paiement « TICKET TECHNIQUE / FERMETURE FORCÉE DE CAISSE / Cause : fin de période », journal « Ticket technique de fermeture forcée imprimé — fin de période » ;
    - journal « Clôture avec ticket en attente POS01-00000002 … 0.96 » et « Fin de journée … 1 ticket(s) en attente enregistré(s) » ; carte « Tickets en attente enregistrés à la clôture » : sort « RAZ demandée », par « Fin de période automatique » (`period.parked-raz=true`) ; « Remise à zéro du ticket en attente POS01-00000002 demandée … par Fin de période automatique » ;
    - traitement « Fin de période du 2026-10-25 : [DAY, WEEK] ».
40. BO-09-03-03 : `period.auto-launch=false`, heure de fin passée : « Fin de période automatique désactivée : traitement du 2026-10-25 non lancé ».
41. BO-09-01-08 : ticket mis en attente, « RAZ de la caisse » sur `/admin/day-end` : « Remise à zéro demandée pour 1 ticket(s) en attente. » ; caisse « AUCUN TICKET EN ATTENTE » ; journal « 1 ticket(s) en attente remis à zéro sur ordre du back-office ».
42. BO-09-03-01 : `/admin/checkout-islands`, îlot CLS en déclaration automatique avec l'hôte 12341234, POS01 en SELF_CHECKOUT_NCR ; ordre CLOSE_SESSION_AUTO_DECLARE (« déclaration automatique de l'hôte ») ; Z « Especes comptees 102,76 = theorique, ECART 0,00 ».
43. BO-09-02-01, BO-09-02-03, BO-09-02-04 (lancement manuel) : `period.hooks-enabled=true`, `period.hook-before=avant.sh`, `period.hook-after=apres.sh` ;
    - « Fin de journée lancée : 1 caisse(s) ouverte(s) à fermer, 0 déconnectée(s), 1 ticket(s) en attente. Traitement de fin de période exécuté. » ; POS01-00000003 « Enregistré », journal « Clôture avec ticket en attente POS01-00000003… » ;
    - la caisse imprime le Z puis « TICKET TECHNIQUE », « Déclaration : automatique (théorique) », « En attente : 1 ticket(s) annulé(s) », « Opérateur : 12341234 / Nom : Jean Dupont », tous deux journalisés ;
    - avant.sh et apres.sh exécutés avec le code 0, sortie affichée sur l'écran, journal « Point d'entrée avant/après fin de période … exécuté ».
44. BO-09-01-10 : `period.reports-printer=localhost:20062` ; « État caissière synthétique », « État caissière détaillé » et « État des règlements » marqués « imprimé le … » ; l'imprimante simulée reçoit 3 travaux `%PDF-1.4` portant ces titres ; journal « État de fin de période cashiers imprimé sur localhost:20062 ».
45. BO-09-03-05 : `period.register-check=true` ; cas conforme : « Contrôle caisse / serveur du 2026-10-25 : caisse 1 ticket(s) 2.76, serveur 1 ticket(s) 2.76 — conforme, référentiel resynchronisé » ; magasin 2 relancé sans ses données (serveur 0 ticket) : la caisse journalise « écart, 1 vente(s) renvoyée(s) » et le magasin détient à nouveau POS01-00000001.

**Écrans**

46. Parcourir le menu du Back Office en fr, nl et pt, à 800 × 600, 1280 × 800 et 1366 × 768, sur le magasin (91 pages : menu complet, favoris, fiche, sélections, nouveau profil, tous les rapports) et sur le central : pas de défilement horizontal, aucun champ qui déborde, aucun texte français sur les pages nl et pt hors données ; écran des fruits et saisies de prix, d'unité et lignes de vente de la caisse dans les trois langues.

Recette exécutée le 25/10/2026 en deux passes (RA référentiel articles et TVA ; RB clients, documents, points de vente et adhérents, e-commerce et fin de journée), profil dev, bases H2 mémoire jetables, matériel simulé, bouchons imfid, gestion commerciale, imprimante réseau et étiquettes électroniques. Les étapes 1 à 46 ont été jouées avec les messages, codes et valeurs ci-dessus : 52 exigences conformes, 4 partielles. Les balayages du menu du Back Office sont propres dans les trois langues après correctifs, et les 261 captures `bo20-*` et `pos20-*` des écrans du lot sont conformes.

Limites :
- quatre lignes restent partielles :
  - BO-02-03-24, BO-02-03-25 : le contrôle à la dépose (poids attendu des lignes pesées, lignes encombrantes non contrôlées) n'est exposé que par l'API `GET /api/pos/deposit-control` ; aucune caisse libre-service ni balance de sécurité ne le consomme ;
  - BO-02-03-37, BO-02-03-38 : plusieurs fournisseurs, lecture du prix affiché en direct et envoi des prix passent par un contrat REST générique et un mur simulé ; les protocoles propres aux fournisseurs (SES, Hanshow) ne sont pas implémentés ;
- BO-07-01-03 : le panier d'une commande e-commerce part à imfid sans les offres du moteur de promotion ; imfid recalcule le cagnottage sur le seul panier ;
- BO-09-01-10 : les éditions automatiques exigent une imprimante réseau qui imprime le PDF reçu sur son port brut (9100).

Validation : imedge 5 699 tests Java + 11 JavaScript ; impos 8 634 Java + 319 JavaScript, sans échec, erreur ni test ignoré. JaCoCo : imedge 32 032 lignes et 16 314 branches, impos 35 638 lignes et 18 851 branches, tous les compteurs manqués à zéro ; JavaScript externe instrumenté à 100 %. Les 323 classes copiées entre imedge et impos sont identiques au paquet près.

Cotation (feuille Back Office) : les 56 lignes non intégrales des blocs 02 Référentiels, 07 e-Commerce et 09 Fin de journée sont recotées : 52 passent intégralement couvertes par CODE ; BO-02-03-24 et -25 restent partielles ; BO-02-03-37 et -38 passent de non couvertes à partiellement couvertes. Back Office P1 : 630/704 intégrales (89,5 %), 60 partielles (8,5 %), 14 non couvertes (2,0 %), soit 690/704 intégrales ou partielles (98,0 %). Toutes priorités : 699 / 75 / 22, couverture pondérée 92,5 %. Bloc 02 : 68 / 4 / 0 ; bloc 07 : 7 / 0 / 0 ; bloc 09 : 19 / 0 / 0. Campagne : 450 promotions, dont 435 CODE et 15 RELECTURE.

## HD_43 — Lot 21 : BO-04 Utilitaires, BO-05 Financier, BO-06 Rapports, BO-11 Flux sortants

Gain : le bloc BO-04 complète le journal électronique (quinze événements avec plage de montants, annulations de règlement, codes inconnus, tickets self-scanning contrôlés et vérifiés, serveur monétique secondaire, colonnes école et autonome réelles, duplicata de facture, TVA par taux), la supervision (aval par fonction de caisse, par profil et par îlot, cartes superviseur créées et imprimées au back-office, alerte globale traitée depuis n'importe quelle page) et le contrôle (barre d'indicateurs sur toutes les pages, écrans responsives, tableau de bord composé des données du système, surveillance en direct, alertes de caisse typées et bornées, justifications par fonction, fermeture à distance, traçabilité complète). Le bloc BO-05 apporte les archives fiscales art. 88, l'e-invoicing et l'e-reporting par plateforme de dématérialisation, le SAF-T mensuel et au fil de l'eau, l'export des transactions sur 12 ans, l'intégration des recettes hors caisse, la validation des mouvements, la remise au coffre, les salariés et les monnayeurs, et un théorique par moyen de paiement identique au Z de la caisse. Le bloc BO-06 livre les graphiques, les sondages, les listes de clients et de factures, les états de ventes, caissières, acomptes et avoirs, codes raison, TVA, détaxe et promotions, exportés dans la langue de la page. Le bloc BO-11 ouvre les flux sortants vers la gestion commerciale, Catalina, les panélistes, la vidéosurveillance, les bornes de prix, les monnayeurs, NEXO, les étiquettes électroniques, l'AT, l'e-commerce et la fidélité VT, GT et VT2. Couverture gagnée : 78 lignes passent de 56 partielles et 22 non couvertes à 75 intégrales et 3 partielles ; Back Office P1 de 630 à 685 intégrales sur 704 (89,5 % → 97,3 %), plus aucune P1 non couverte, couverture pondérée de 92,5 % à 98,6 %.

Écrans et domaines nouveaux du Back Office :
- journal électronique : critères événement, règlement annulé, codes inconnus, self-scanning (SELF_SCAN, SELF_SCAN_CONTROLLED, SELF_SCAN_VERIFIED), serveur secondaire (SECONDARY_SERVER), taux de TVA ; page duplicata `/admin/journal/document/{id}/duplicate` ;
- `/dashboard/control` et barre de contrôle sur toutes les pages, `/dashboard/live`, bandeau d'alerte global avec Autoriser / À traiter en caisse / Refuser / Acquitter, `POST /dashboard/force-close/{caisse}` ; `/admin/accounts` (création de compte) et `/admin/accounts/supervisor-card` ;
- écrans Archives fiscales, E-invoicing, E-reporting, SAF-T, Transactions fiscales et Flux de recettes ; API `POST /api/fiscal/einvoicing/status`, `POST /api/fiscal/ereporting/ack`, `GET /api/fiscal/saft/documents`, flux `POST /feeds/import/SAFE_RECEIPTS?mode=<flux>` ; enrichis : historique des journées (validation, correction, transfert), pochettes, salariés, monnayeurs ;
- `/admin/promotions`, `/admin/tax-refunds`, `/admin/messages` (sondages, import de codes postaux), `/admin/customers/edit` (facturation) ; rapports nouveaux : état caissière, profil des performances, commandes express / normales, synthèse hebdomadaire, acomptes / avoirs / bons, factures, clients, ventes par emplacement, ventes magasin, articles promotionnels, sondages, codes postaux, détaxe ; graphiques `?chart=BAR|LINE|PIE` ; extractions planifiées `/admin/reports/runs/{n}` ;
- fournisseurs Service commercial tirés (« Récupérer maintenant »), `GET /api/price-kiosk/{ean}`, `GET /api/sync/voucher-burn/{n}`, `POST /api/ecommerce/valuation` ; flux paigs1, vignett, colonne REMISES_AMONT de cafamil, clifact / clilfact / cliregl / clitva ; caisse : écrans `/vignettes` et `/cash-changer` ;
- paramètres nouveaux, entre autres : `journal.secondary-monetic-servers`, `auth.endorsement-profiles`, `control.alert-rules`, `justification.function-routes`, `control.indicators-bar`, `control.revenue-level`, `control.revenue-top`, `audit.watched-menus`, `supervision.global-alerts`, `supervision.live-event-types`, `fiscal.*` (archives, e-invoicing, e-reporting, `fiscal.saft-realtime`, échéance, rejeu), `safe.*` (pochettes, forçage, plafond d'acompte), `report.express-max-items`, `report.station-families`, `integration.pull-*`, `export.*`, `vignette.*`, `kiosk.*`, `eeg.auto-push-*`, `hardware.cashchanger.movements`, `nexo.*`, `channel.ecommerce.*`, `fidelity.vt-*`, `fidelity.gt-*`, `fidelity.vt2-enabled`.

Correctifs mis au jour par la recette et par les groupes :
- défauts de la recette du lot 20 (groupe W) : réponses JSON des importeurs invalides (désormais construites avec Jackson, `{"error":…}` compris) ; pied de page du central qui se disait nœud magasin ; journal par adhérent qui ne retrouvait pas un PDV par son ancien numéro ; 168 libellés français sans accents ; « CAISSE 04 | Opérateur : MARIE » en dur sur les en-têtes de la caisse ; prix EEG « 99.0 € » ; mutations GraphQL qui ne déplaçaient pas l'empreinte du référentiel, si bien que la caisse ne voyait la modification qu'au tirage suivant ;
- balayage des formats (groupe X) : montants, taux, quantités et dates bruts sur toutes les pages du back-office et de la caisse (« 2.7600 € », « 0.2000 », « x0.756 », « 2026-10-25T20:35 ») ; JSON construit à la main (recherche de nomenclature invalide au-delà de 50 résultats, ordres de caisse lus par expression régulière) ; codes bruts (CASH, FINISHED, « [DAY, WEEK] ») et français restant sur les pages nl et pt ;
- théorique par moyen de paiement (groupe CS) : la carte « Théorique par moyen de paiement » du magasin et le Z de la caisse décrivaient deux tiroirs différents (règlements et mouvements mélangés, apports, dépenses espèces, fond de caisse et remboursements espèces absents de la ligne Espèces) ; règlements (`totalsByMethod`) et théorique par moyen (`theoreticalByMethod`, Espèces = théorique du Z) sont séparés dans les deux copies de CashSessionService, et l'historique des journées, son CSV, les totaux figés et l'écran de prélèvement de la caisse lisent le théorique ; le X et le Z imprimés écrivent ESPECES / CARTE BANCAIRE au lieu de CASH / CARD ;
- recette RS (BO-04) : aide de l'écran des listes citant des codes que la caisse ne sert pas ; journal fonctionnel avec dates ISO, codes bruts (FORCE_PRICE, « PRICE_MODIFICATION par mcurie », SUPERVISOR, YESNO, BLOCK, « payment:CHEQUE ») et montants non formatés ; tare déduite non remontée au magasin (l'événement déduction ne trouvait rien) ; « &nbsp; » littéral sur le détail de retour et « click &amp; collect » échappé sur l'écran de rappel de la caisse ; `/admin/accounts` en HTTP 500 et rôles en code brut ;
- recette RT (BO-05, BO-11) : fichier refusé compté « intégré » au tirage ; refus de la borne de prix servis en application/octet-stream ; `burnedAt` à la microseconde ou à la minute ; date ISO dans les notices de régénération des flux ; instants de Tlogextr, trandet et facterp à la microseconde ou à la minute ; « 1 documents » ; UUID du mouvement dans le motif d'une correction ; chiffres et codes coupés en deux à 800 × 600 et tableau des transactions fiscales débordant de 13 px ; décade d'e-reporting extraite à la main puis transmise automatiquement avant sa fin ; recettes à montant ou date illisible perdues sans erreur (le 32/13/2026 crédité à la date du jour) ; écran des comptes en HTTP 500 ;
- recette RV (BO-06) : état des règlements en codes bruts et total compté deux fois ; état des remboursements avec moyen brut et identifiant de base au lieu du ticket ; codes raison « CANCEL_TICKET par mcurie », caissière vide et réponses de sondage listées ; drapeau école « O/N » en français sur nl et pt ; caissière sans nom faussant les moyennes ; sondages de début et de fin servis hors transaction puis retirés ; clients volatils créés en caisse remontés « Client en compte » ; réponses de sondage enregistrées comme appels superviseur (« DEMANDE EN COURS : SONDAGE ») ; boutons Oui/Non de la caisse et réponses de l'écran des messages non traduits ; rappel du moment, des jours et des heures ajouté à l'état des sondages ; camembert dessiné en ovale ; codes de famille hors nomenclature tronqués dans la barre de contrôle (« EAU_ ») ; « Type retour » en nl ;
- groupe S : page de poursuite du contrôle de caisse en HTTP 500 ; caisse affichant « accordée par Autorisé par » ; écran facture de la caisse en HTTP 500 sans ticket ; texte de caisse non échappé sur le tableau de supervision ; événements de prix, de ligne et d'annulation de règlement sans ticket ni caissière ; clés de règle en majuscules ; taux de TVA affiché « 0,2 % » et filtre 20 sans résultat ;
- groupe T : documents FS non remontés au nœud (ticket renvoyé après scellement) ; tirage ne reconnaissant pas un fichier inchangé ; instants à la minute ou à la microseconde et montants à 4 décimales sur le fil ; drapeau GS1 perdu entre le scan et l'export ; ligne ECOM dans le flux des vignettes et ventes e-commerce dans le journal Anaveo ; appel VT portant le libellé du bon au lieu du code du moyen ; paramètre d'écotaxe en double ; simulateur du hub NEXO sans Completion ;
- groupe U : FS et NC portugais jamais reçus par le nœud ; correction et transfert en HTTP 500 (identifiant de 39 caractères pour une colonne de 36) ; JSON brut affiché à l'écran ; colonne TVA des mouvements du coffre montrant le TTC ; personne morale affichée « - Nom » ; formulaire des flux de recettes acceptant des valeurs trop longues ; identifiant fiscal de l'acheteur absent du XML d'e-reporting ;
- groupe V : rapport de taux de carte fidélité en HTTP 500 ; ligne de moyenne comptée deux fois dans le total ; titres de colonnes coupés au milieu des mots ; date brute et échecs masqués des extractions planifiées ; question de transaction effaçant le sondage affiché ; ingestion d'un ticket sans statut de valorisation en échec ; reçu imprimé pour un panier de formation ; noms de superviseur vides ;
- réconciliation TU : un seul mécanisme de transmission SAF-T au fil de l'eau (SOAP RegisterInvoice pour l'AT, JSON pour les autres points d'accès, un seul rejeu) et un seul paramètre `fiscal.saft-realtime` ; un seul formateur de montants et un seul jeu de libellés de moyens de paiement ; une entité mise deux fois en file dans un même lot n'est expédiée qu'une fois ; « Recette » du journal du coffre traduit « Test » en nl et pt ;
- groupe Z : terminal simulé et pont matériel simulé sans serveur monétique (balise VWAY absente, routes de paiement du pont en 404 en dev) ; services de l'e-reporting classés en biens dans le TransactionsReport (désormais TLB1 / TPS1) ; décision instantanée du pont simulé déclenchant une mise à jour concurrente du ticket (le simulateur répond désormais « en cours » avant de décider) ;
- groupe R : à la caisse, la décision du terminal de paiement passé par le pont matériel était appliquée hors du verrou de la caisse, en concurrence avec l'écran de paiement : `StaleStateException`, fil `tpe-follow` arrêté et ticket clos sans règlement en base (2 tickets sur 21 au rejeu) ; désormais chaque décision (acceptation, refus, erreur, affichage) est appliquée sous le verrou de la caisse et une seule fois (jeton de transaction), pour le débit, le débit combiné, le débit et le crédit forcés du mode dégradé, le remboursement net et le remboursement de retour ; l'échec d'un ancien suivi n'efface plus la transaction suivante ; fausse erreur de démarrage « No employee in the database » sur les constructions dev ;
- réconciliation SV : drapeaux « saisi » et « scanné » unifiés et restaurés après redémarrage de la caisse ; même règle de comptage pour le duplicata du journal et celui des rapports ; libellés manquants de VIGNETTES_DECLARED et CASH_OPERATION_TIMED ; deux traductions nl de « Espèces » et « Secteurs » traduit « Taart » ; en-têtes du CSV du journal et colonne TVA en français sur nl et pt ; exports CSV, XLSX et PDF, duplicata et bordereau de détaxe dans la langue de la page.

Préconditions : construction dev (`-Dquarkus.profile=dev`, jeu de données et matériel simulé compris), bases H2 mémoire jetables recréées à chaque lancement (`drop-and-create`), matériel simulé (imprimante, scanner, terminal de paiement, monnayeur). Nœuds :
- nœud imedge magasin (PDV 07039) avec ses caisses impos POS01 et POS02 (îlot CLS pour POS01), ou C04 pour les flux et le financier ;
- pour les cas PDV et adhérent : central imedge (`pos.role=central`) et magasin 10002 avec `pos.sync.central-url` vers le central ;
- bouchons des receveurs externes : Catalina, TFREMA / DS (gestion commerciale), Anaveo, borne de prix, AT (`POST /at`, SOAP), plateforme de dématérialisation PDP (e-invoicing, e-reporting, SAF-T JSON), étiquettes électroniques EEG, e-commerce (`/web/events`), fidélité imfid (VT, GT, VT2), et un bouchon du moteur de valorisation pour la caisse ;
- connexion back-office admin/admin ; caisse jdupont / 1234, avals mcurie / 1111 (manager) et aeinstein (profil Chef de caisse).

Ports de la recette : passe RS magasin 19070 (PDV 07039 ; PDV 10002 avec `central-url` pour le journal par adhérent), POS01 19080, POS02 19081, central 19090, bouchons imfid 19060 et EEG 19061 ; passe RT magasin 20070, caisse C04 20080, bouchons imfid 20060 et receveurs de flux 20061 ; passe RV magasin 21070, POS01 21080, bouchon imfid 21060 ; rejeu CS magasin 38070, caisse C04 38080.

**Journal électronique : événements, annulations de règlement, articles inconnus, self-scanning, liste et export, duplicata, TVA**

1. BO-04-01-14 : `/admin/journal?event=…`, liste des quinze événements ; la plage de montants est masquée pour TRAINING et OFFLINE (`data-range`) :
   - CANCELLED → POS01-00000003 ; DEDUCTION → POS01-00000006 (tare 0,050 kg ; plage 1–2 → 1, ≥ 5 → 0) ; DISCOUNT → POS01-00000004 (0,4–0,6 → 1, ≥ 1 → 0) ; RETURN → POS01-00000005 (5,5–6,5 → 1) ; NEGATIVE → POS01-00000007 (−3…−2 → 1) ;
   - FORCED_PRICE → POS01-00000002 (1–2 → 1, ≥ 2 → 0) ; PRICE_ENTERED, CARRIED_OUT et VAT_EXEMPT → POS01-00000002 ; SCANNED → 11 tickets (2–3 → 7) ; NO_SALE → 2 lignes ; STORE_VOUCHER et VENDOR_COUPON → POS01-00000005 ;
   - TRAINING → une ligne POS02 « École O » ; OFFLINE → POS01-00000008 (magasin gelé 45 s).
2. BO-04-01-19 : règlement espèces de 1,00 annulé avec aval ; `flag=PAYMENT_CANCELLED&paymentCancelMethod=CASH&paymentCancelMin=0,5&paymentCancelMax=2` → POS01-00000004 ; moyen CARD → 0 ; minimum 3 → 0.
3. BO-04-01-34 : scan de 9999999999994 : « CODE INCONNU » ; `unknownCodeMin=9999999999990&unknownCodeMax=9999999999999` → POS01-00000004, plage 1–2 → 0 ; détail « Codes inconnus scannés 9999999999994 ».
4. BO-04-01-41, BO-04-01-42, BO-04-01-43 : paniers SS2101 et SS2103 poussés sur `/api/orders/` puis repris en caisse :
   - `flag=SELF_SCAN` → POS01-00000009 et POS01-00000010, colonne Autonome = O ;
   - SS2101 en contrôle total provoqué par l'hôtesse (`/action/selfscan/total`) : `flag=SELF_SCAN_CONTROLLED` → POS01-00000009, événement « Contrôle self-scanning (hôtesse) » SS2101 ;
   - SS2103 en contrôle partiel « demandé par l'appareil » : `flag=SELF_SCAN_VERIFIED` → POS01-00000010, événement « Vérification self-scanning (système) ».
5. BO-04-01-48 : `journal.secondary-monetic-servers=4` ; ticket POS09-00000001 poussé sur `/api/sync/ticket` avec un règlement CARD `moneticServer=4` : trouvé par `flag=SECONDARY_SERVER`.
   - recette Z de bout en bout : vente carte normale (`/api/hardware/tpe/accept`) C04-00000001 non trouvée par `flag=SECONDARY_SERVER` ; avec `payment.degraded-mode=true`, vente carte C04-00000002 trouvée ; par le pont matériel simulé (`payment.terminal-type=INGENICO_SIBS`), vente normale C04-00000003 non trouvée, débit dégradé forcé VWAY4 C04-00000004 trouvé.
6. BO-04-01-50 : la liste affiche N° TPV, transaction, caissière, date, heure, montant, nombre d'articles, École, Autonome et la TVA par taux, chaque ligne avec « Ouvrir » ; `/admin/journal/export` → `journal.csv` (UTF-8 avec BOM, « ; ») : `TPV;Transaction;Caissière;…;École;Autonome;TVA`, ligne `POS01;POS01-00000009;…;N;O;20 % HT 5,40 TVA 1,08` ; en nl `TPV;Transactie;Kassier;Datum;…;20 % excl. btw 3,20 btw 0,64`.
7. BO-04-01-53 : facture POS01-F000001 émise en POS01 ; le détail de POS01-00000005 renvoie à `/admin/journal/document/1/duplicate` : « FACTURE POS01-F000001 DUPLICATA — Impressions : 2 » ; événement « Duplicata imprimé » journalisé.
8. BO-04-01-55 : `vatRate=20` et `vatRate=0,2` → 14 tickets ; `vatRate=0` → POS01-00000002 ; détail « 0 % 3,00 0,00 3,00 / 20 % 6,83 1,37 8,20 » ; liste et CSV « 20 % HT … TVA … ».

**Supervision : règles par îlot, cartes superviseur, alertes et réponses du back-office**

9. BO-04-02-01 : à l'échelon îlot CLS, `gesture.function-endorsements=POST /action/pay-cash=REQUIRED:Chef de caisse` ; en POS01 (îlot CLS), paiement espèces retenu : mcurie → « AUTORISATION REFUSÉE », aeinstein (Chef de caisse) accepté et paiement rejoué (complet, ESPECES 2,76) ; POS02, sans îlot, paie sans aval.
10. BO-04-02-09, BO-04-02-11 : `/admin/accounts` répond 200 ; compte pmartin créé (« Paul Martin … Manager niveau 2 ») ; `POST /admin/accounts/supervisor-card id=5` émet le badge 82300628 ; `/admin/accounts/supervisor-card?id=5` : « CARTE SUPERVISEUR Paul Martin 82300628 », code-barres Code 128 en SVG et bouton d'impression.
11. BO-04-02-12 : ouverture du tiroir en POS01 : `/dashboard/alerts` et bandeau de chaque page du back-office « Ouverture du tiroir sans vente », avec Autoriser / À traiter en caisse / Refuser / Acquitter ; `GET /dashboard/call/10/authorise` 204 → caisse « granted: Le Manager », VALIDER, tiroir ouvert ; `refuse` 204 → aval effacé.

**Tableau de bord et contrôles : indicateurs, surveillance en direct, alertes, justifications, listes, fermeture à distance, traçabilité**

12. BO-04-03-01, BO-04-03-02 : barre de contrôle sur chaque page : « CA du jour 57,60 € · Clients encaissés 14 · Panier moyen (articles) 1,66 · Écart N-1 … · EPIC 21,48 € … » ; `/dashboard/control-data` rend CA, clients, panier, N-1 et CA par niveau de nomenclature ; à 375 × 667, journal, journal fonctionnel, tableau de bord, contrôle, direct, alertes mobiles, paramètres et comptes sans défilement horizontal en fr, nl et pt (scrollWidth 375), de même à 800, 1280 et 1366 px.
13. BO-04-03-03, BO-04-03-15 : `dashboard.indicators=event:CONTROL_ALERT;event:JUSTIFICATION_CHOSEN;event:PRICE_MODIFIED;event:LINE_CANCELLED;payment:cheque` → `/dashboard-data` rend ces cinq tuiles, dans cet ordre, avec leurs comptes.
14. BO-04-03-07 : `/dashboard/live-data` liste 50 opérations (TICKET_CLOSED, TICKET_CANCELLED, PRICE_MODIFIED, DRAWER_OPENED, DRAWER_FORCED_OPEN, REFUND_CREATED, PAYMENT_CANCELLED, ENDORSEMENT_*, CONTROL_ALERT, JUSTIFICATION_CHOSEN), libellées, par exemple « Fonction GET /drawer-open — réponse non ».
15. BO-04-03-08, BO-04-03-09 : règles de `control.alert-rules` :
    - `barcode:299*=BLOCK` : « ALERTE DE CAISSE : CODE-BARRES 299* », article non vendu ;
    - `function:GET /drawer-open=YESNO` : question, puis NON → « FONCTION ABANDONNÉE » ;
    - article YESNO `max=1` : question posée une fois, le second ORANGINA passe sans question (x2 ORANGINA) ;
    - article SUPERVISOR `profiles=Chef de caisse` : mcurie refusé, aeinstein accepté ;
    - paiement chèque BACKOFFICE avec notification : `/dashboard/alerts` « Moyen de paiement Chèque — Chèque à contrôler ».
16. BO-04-03-10, BO-04-03-12 : `/admin/lists`, J21 (DRAWER_OPEN) et J22 (`CANCEL_TICKET;PRICE_MOD;CANCEL_LINE`) ; en caisse, l'ouverture du tiroir propose « Erreur de saisie / Demande client / Ajout de monnaie », l'abandon du ticket et la modification de prix seulement « Erreur de saisie / Réclamation client », l'annulation de ligne la liste CANCEL_LINE ; journal « Justification » : CANCEL_TICKET « ERS - Erreur de saisie », PRICE_MOD « RCL - Réclamation client ».
17. BO-04-03-13 : `POST /dashboard/force-close/POS02` 204 ; POS02 imprime « TICKET TECHNIQUE FERMETURE FORCÉE DE CAISSE … Session : POS02-S00001 … Cause : back-office » ; journal « Session clôturée — POS02-S00001 - fermeture forcée sans comptage ».
18. BO-04-03-14, BO-04-03-04 : `audit.watched-menus=journal;settings` ; journal fonctionnel : « Accès à un menu », TPV « Back-office », « admin : Journal (/admin/journal) » et « admin : Paramètres caisse (/admin/settings) », connexions « Connexion admin » ; toutes les utilisations des contrôles y figurent avec leur ticket : Alerte de caisse (règle et réponse), Justification, Aval accordé / refusé, Décision du back-office (Autorisation, Refus, Fermeture forcée), Remise / forçage, Annulation article, Règlement annulé, libellées en fr, nl et pt.

**Financier : mise à disposition des transactions et archives, e-invoicing, e-reporting, SAF-T mensuel et temps réel, export des transactions**

19. BO-05-01-01 : écran Archives fiscales : « Archives produites : TaxAuditExtracts_07039_20261026.zip, TaxAuditReports_07039_20261026.zip » ; septembre archivé automatiquement ; l'archive Reports contient synthese.txt (« Chaînage des signatures : intègre ») et empreintes.txt (SHA-256).
20. BO-05-01-02 : `fiscal.einvoicing-enabled=true`, plateforme `fiscal.pdp-provider` déclarée ; factures A4 C04-F000002/4 de Boulangerie Martin (SIRET) avec la mention « Facture électronique transmise par plateforme… », Müller GmbH (DE) sans ; XML CII EN16931 bien formé ; statuts « 205 — Approuvée », puis « 212 — Encaissée » après un push ; `POST /api/fiscal/einvoicing/status` : 400 « Statut inconnu. », 401 sur mauvaise clé.
21. BO-05-01-03 : `fiscal.ereporting-enabled=true`, période DECADE ; XML avec les synthèses quotidiennes B2C et une InternationalInvoice `buyerVat` DE123456789 ; décades passées transmises automatiquement (« Acceptée (ACK-145) ») ; accusé poussé : « Rejetée Montant incohérent » ; la décade en cours attend un « Transmettre » manuel.
   - PaymentsReport (recette Z) : article 3300000000003 coché « Prestation de services (e-reporting des paiements) » (nl « Dienstverlening (e-reporting van betalingen) », pt « Prestação de serviços (e-reporting dos pagamentos) ») ; extraction du jour avant la vente : `<PaymentsReport/>` ; vente C04-00000005 (service 0,96 + lait 2,26 = 3,22) ; nouvelle extraction : `<DailyPayments date="2026-10-26" category="TPS1" scope="B2C">` taux 20, AmountIncludingTax 0.96, TaxAmount 0.16, TaxableAmount 0.80 ; TransactionsReport avec une synthèse TLB1 et une synthèse TPS1.
22. BO-05-01-04 : écran SAF-T : rappel de l'échéance du 25 ; « Fichier SAF-T extrait : SAFT-PT_07039_20261031.xml » (bien formé, contient FS C04/2026/00000002), puis « Fichier SAF-T déclaré transmis à l'administration. ».
23. BO-05-01-05 : `fiscal.saft-realtime=true`, AT (fournisseur Fiscal / SAFT) et SAFTPDP déclarés ; FS C04/2026/00000001 envoyé une fois à SAFTPDP (JSON, « Envoyé ACK-160 ») et une fois à l'AT (SOAP RegisterInvoice) ; écran « points d'accès : 2 — envoyés : 2 — en échec : 0 » ; `GET /api/fiscal/saft/documents` : 200 avec la clé, 401 sans.
24. BO-05-01-06 : écran Transactions fiscales : FT → 4 documents ; NIF FR44732829320 → 3 ; « Müller » → « 1 document » ; fragment de ticket, TTC 5–30 et HT 4–10 filtrés ; « abc » → alerte ; totaux par client affichés ; exports CSV, xlsx et PDF en 200.

**Financier : mouvements hors encaissement, coffre, salariés, monnayeurs, théorique par moyen de paiement**

25. BO-05-02-03 : écran Flux de recettes : STATION, GAZ_BOUTEILLE (deux taux, TVA 4,42) et GAZ_CITERNE intégrés ; renvoi du même fichier : « 0 créée(s), 1 mise(s) à jour » ; `POST /feeds/import/SAFE_RECEIPTS?mode=STATION` accepté ; montant ou date illisible : « 1 erreur(s) ».
26. BO-05-02-09, BO-05-02-10 : historique des journées :
    - « Mouvement validé. » ; nom de validateur de 21 caractères refusé ; « Correction enregistrée. » (motif « Correction du mouvement du 26/10/2026 00:12 : … ») ; « Transfert de règlement enregistré. » ; validation de la journée refusée tant que des mouvements ne sont pas validés ; « Chiffre d'affaires net de la journée validé. », puis « déjà validée » ;
    - pochette refusée avant la validation de la journée ; P-001 et P-002 acceptées ; P-003 : « compte déjà 2 pochette(s) ».
27. BO-05-02-15 : fiche « Nettoyage Pro SARL » (personne morale) créée ; « Plafond d'acompte dépassé : 100,00 € autorisés, 80,00 € déjà versés. » ; saisie de niveau 2 : « …niveau de forçage 2 : il se valide par un niveau supérieur. » ; tout valider : « 4 validé(s), 4 réservé(s) » ; le compte chefcoffre de niveau 3 valide : « Mouvement validé. ».
28. BO-05-02-16 : écran des monnayeurs : Chargement 50,00, Comptage (écart −12,50), Réalignement, puis « Vente C04-00000013 6,96 → 44,46 » ; aucune requête n'est faite à l'automate.
29. BO-05-02-09 (théorique par moyen de paiement, rejeu CS) : `cash.movement-tenders=CASH;CHEQUE` ; fond de caisse 100, vente espèces 5,52, apport 20, dépense espèces 5 (« Achat de timbres »), prélèvement 30, vente carte 0,96, dépense chèque 8 :
    - X de la caisse : « Règlements ESPÈCES 5,52 € CARTE BANCAIRE 0,96 € Fond de caisse 100,00 € Mouvements nets -15,00 € Espèces théoriques 90,52 € » ; écran de prélèvement : Espèces 90,52 €, Chèques -8,00 € ;
    - Z imprimé : « REGLEMENTS ESPECES 5,52 E / CARTE BANCAIRE 0,96 E », « Especes theorique 90,52 E », « Especes comptees 90,52 E », « ECART 0,00 E » ;
    - historique des journées du magasin : « Total théorique 83,48 € », Espèces 90,52, Carte bancaire 0,96, Chèque -8,00 ; session C04-S00001 comptée 90,52, théorique 90,52, écart 0,00.

**Rapports : graphiques, sondages, clients, factures**

30. BO-06-01-04 : `/admin/reports/{code}?chart=BAR|LINE|PIE` sur les 19 rapports d'agrégat ; productivité horaire `series=customers&series=sales` ; rapports de liste sans graphique ; données nulles : « aucune donnée à représenter » ; 33 rapports × 3 graphiques × fr/nl/pt en HTTP 200 ; camembert circulaire.
31. BO-06-03-01, BO-06-03-02 : `/admin/messages`, sondages avec numéro, question, moment, type de réponse, actif, dates, jours, heures et îlots « IL1;IL2 » :
    - en caisse, S7 (TRANSACTION_START) posé au premier article ; S3 (TRANSACTION_END, IL1;IL2) à la clôture avec OUI/NON, JA/NEE, SIM/NÃO ; S4 (IL2 seul), S5 (autre jour) et S6 (06:00–06:30) jamais posés ; SONDAGE-7 scanné pose S7, SONDAGE-4 rien sur IL1 ;
    - `surveys?pick=3&pick=7` : « Oui » → 1 client, 100,0 %, 8,76 €, 30,4 %, panier 8,76 €, 2,00 articles, 0,00 point ; sous-total par sondage ; rappels « Sondage n° 3 — … — réponses : Oui / Non — du — au — — actif » et « Sondage n° 7 — moment : … — jours : … — heures : 00:00 – 24:00 ».
32. BO-06-03-03 : import « 2 code(s) postal(aux) importé(s) » ; en caisse, 9999999 → « CODE POSTAL HORS LISTE », 1000001 enregistré 1000-001 ; rapport postal-codes : 1000-001 → 1 client, 8,76 € ; extraction xlsx planifiée en néerlandais (« Postcode-enquête … »).
33. BO-06-03-04 : `customers?option=VOLATILE` : POS01-CLI000001 « Client volatile » ; `/admin/customers/edit` : « Fiche enregistrée. » ; la liste montre une ligne d'adresse principale et une ligne de facturation (« 5 avenue de la Gare 59000 Lille », Facturation), facturation au pourcentage, profession Architecte ; noms D–E → 3 lignes, X–Z → aucune.
34. BO-06-03-05, BO-06-04-03 : rapport invoices : 07039, POS01-F000001, FACTURE, Paul / Durand, POS01-CLI000001, 9,36 / 7,80 / 1,56 €, POS01-00000001, 0102030405, p@durand.fr ; préambule « Rapport édité le … — point de vente 07039 » ; `option=BON_LIVRAISON` vide ; exports CSV, XLSX et PDF en 200 ; « Réimprimer (duplicata) » : « FACTURE N° POS01-F000001 — DUPLICATA N° 1 » (fr), « FACTUUR nr. … DUPLICAAT nr. 2 » (nl), « FATURA N.º … SEGUNDA VIA N.º 3 » (pt).

**Rapports : ventes par famille, emplacement, magasin et horaire**

35. BO-06-04-01 : family-sales en 21 colonnes, sous-total par rayon « Sous-total 2020 », filtre `family=2020`.
36. BO-06-04-02, BO-06-04-07 : `location-sales?option=15&ean=…` : « 23:30 – 23:45 », 3300000000002, LAIT UHT 1L, 202002000001, 2,76 €, 5,00, 11,52 €, puis TOTAL ; filtre de plage d'EAN ; `hourly-sales?option=15&hourFrom=23:00&hourTo=23:59` : tranches d'un quart d'heure ; `ean=3300000000002` à 60 min : 5 unités, 11,52 €.
37. BO-06-04-05 : store-sales : ventes nettes 27,84 €, brutes 31,08 €, TVA brute 4,80 €, prélèvements 20,00 €, 7 articles scannés et 2 saisis, puis détail par moyen de paiement (Carte bancaire / Espèces / Bon d'achat) ; `PREVIOUS_DAY` à zéro.
38. BO-06-04-06 : hourly-productivity, options 60/30/15 ; tranches « 00:30 – 00:45 » avec caisses ouvertes et moyennes ; ligne « Moyenne par tranche » ; graphique LINE ; depuis la page nl, « Planifier l'extraction » : « Extraction planifiée. », puis `/admin/reports/runs/3` est un PDF en néerlandais (« Verkoopstaat winkel »).

**Rapports : caissières, acomptes, avoirs et bons, codes raison**

39. BO-06-06-01 : un ticket mis en attente puis repris, un autre laissé, un panier de formation mis en attente ; `pending-tickets?option=ALL` : POS01-00000003 rappelé (ticket et caissière de rappel), POS01-00000004 en attente, FORMATION-1 mode école « Oui » (nl « Ja ») ; total 3 tickets, 9,72 € ; WAITING et RECALLED filtrent.
40. BO-06-06-02, BO-06-06-03, BO-06-06-08 :
    - cashier-performance : Jean Dupont et « Moyenne magasin », un verrouillage manuel (VERROUILLER puis badge 12341234 / 1234) ; cashier-orders avec `report.express-max-items=2` : commandes express et normales, rang d'efficacité ;
    - cashier-state SUMMARY et DETAIL (`cashier=12341234`) : non-ventes, clients, ventes, retours, remises, puis par moyen ventes, prélèvements 20,00 €, fond de caisse 100,00 €, apports, sorties ; récapitulatif TVA et durées ;
    - cashier-weekly : « 26/10/2026 – 01/11/2026 », Jean Dupont, « Carte bancaire 26,88 € ; Espèces 0,96 € ; Bon d'achat 0,96 € », sous-total et ligne de signature.
41. BO-06-06-06 : stored-values : avoir 297000000000001 émis par le remboursement POS01-R000001 et utilisé sur POS01-00000006 (0,96 €) ; acompte CMD4242 de 15,00 € ; options ISSUED et USED, îlots IL1 et IL2 filtrés.
42. BO-06-06-07 : reason-codes : « Aval de mcurie : Annulation du ticket », « Prélèvement coffre », « Aval de mcurie : Mode école », « Remboursement », « Motif de retour » ; en nl « Goedkeuring van mcurie: Annulering van het ticket » ; réponses de sondage absentes.

**Rapports : TVA, détaxe, promotions**

43. BO-06-07-01, BO-06-07-02 : vat-by-family aux niveaux 0 à 3 et en SYNTHESIS, sous-total par rayon ; `report.station-families=2020` : préambule « Chiffre d'affaires de la station exclu : 2020 » ; fidelity-vat SUMMARY, puis DETAIL sur POS01-00000001..00000002 (2 lignes et TOTAL), colonnes magasin et site e-commerce.
44. BO-06-07-03 : `/admin/tax-refunds` : « Bordereau BV20261026-POS01-00000001 prêt à imprimer. » ; ticket inconnu : « Ticket inconnu ou non clôturé. » ; bordereau PDF en fr, nl et pt (« 20 % : 9,36 / 1,56 € ») ; « Remboursement de la détaxe enregistré. », puis « Ce bordereau est déjà remboursé. » ; rapport tax-refunds « Remboursé ».
45. BO-06-08-01, BO-06-08-02 : `/admin/promotions` : « Promotion enregistrée. » ; ticket valorisé poussé au magasin (la caisse dev n'a pas de moteur de promotion) ; `promotion-cashiers?option=VALUE&cashier=12341234` : OFF1 « Lot de 3 laits », Jean Dupont, 1, 6,00 €, 2,28 €, 38,0 % ; PERCENT vide ; `promotion-articles?family=2020` : 3300000000002, LAIT UHT 1L, OFF1, 3,00, 6,00 €, 2,28 €, AMONT « Oui ».

**Flux sortants : émetteur, Catalina, CA par famille, facturation clients en compte, GS1, panel, vignettes**

46. BO-11-01-01 : fournisseur Service commercial (GC) ; « Récupérer maintenant » : `GET /gc/PRODUCTS` (Bearer key-GC) crée « Café pull RT21 » ; tirage suivant : « 0 intégré(s), 2 inchangé(s) » ; fichier refusé par l'import : « 0 intégré(s), 0 inchangé(s), 1 en échec ».
47. BO-11-01-07 (partiel) : vente close : `POST /catalina` (Bearer key-CAT) `{"storeId":"07039","laneId":"C04","transactionId":"C04-00000001","loyaltyCardNumber":"2990000000011","totalAmount":37.06,…}`.
48. BO-11-01-08 : `export.family-sales-upstream-offers=REMB`, offre REMB-LAIT (bouchon du moteur) ; `cafamil-20261026.csv` : `LAIT DEMI-ECREME;5.000;11.30;9.42;1.88;0.00;2.50;5`, la colonne REMISES_AMONT portant les 5 × 0,50 € de remises immédiates.
49. BO-11-01-12 : facture C04-F000001 d'un client en compte : `clifact` (enregistrement H), `clilfact` (3 enregistrements L), `cliregl` (`RC04-F000001 001CASH …3706`) et `clitva` (T, puis E d'écotaxe avec `customer.invoice-export-ecotax`).
50. BO-11-01-18 : `paigs1` : lignes SCAN et REGLEMENT pour 952600013436712345 avec GS1=O et BRULE=O, puis TOTAL ; second scan du bon : « BON DÉJÀ CONSOMMÉ EN CAISSE C04, TICKET C04-00000003 » ; `GET /api/sync/voucher-burn/{n}` : `burnedAt "2026-10-26T00:36:34"`.
51. BO-11-01-19 : `MS.07039.299.zip` contient Scannels.299, Scvenmag.299 et Tlogextr.299, instants à la seconde.
52. BO-11-01-20 : ticket de 37,06 € : « VIGNETTES 3 » (10 € la vignette) ; `/vignettes` : « Vignettes théoriques 3 », puis « Déclaration enregistrée … déjà déclarées 2 » ; saisie non numérique refusée ; `vignett-20261026.csv` : `C04;C04-S00001;jdupont;7;7;2;-5`.

**Flux sortants : temps réel, borne de prix, cash management, produits dématérialisés, EEG**

53. BO-11-02-01, BO-11-02-04 (partiels) : pour chaque vente close, le bouchon reçoit TFREMA `{"eventType":"VENTE","pdv":"07039",…}`, DS `DS07039 C04 C04-00000001 …226` et le journal Anaveo `C04|2026-10-26T00:36:06|ARTICLE|LAIT UHT 1L|1.00|2.26 … TOTAL|CLOSED|3|37.06`.
54. BO-11-02-06 : `GET /api/price-kiosk/3300000000002` (Bearer key-KIOSK) : `{"price":2.76,"offer":true}` ; formats XML et TEXT ; 401 « Borne de prix non déclarée », 404 « Article inconnu », 403 désactivée, en text/plain.
55. BO-11-03-03 : `hardware.cashchanger.movements=true` ; paiement de 37,06 € : « ESPECES 37,06 / DONNE 42,06 / RENDU 5,00 » ; écran `/cash-changer` : 882 → 902 € (apport) → 852 € (prélèvement) ; distribution de 0,03 € : « MONNAYEUR : MOUVEMENT NON RÉALISÉ, RIEN N'A ÉTÉ ENREGISTRÉ ».
56. BO-11-03-04 : `nexo.hub-url`, `nexo.poi-id`, `nexo.activation-moment=PAYMENT` ; au scan : « CODE D'ACTIVATION », puis « ACTIVATION À L'ENTRÉE EN PAIEMENT » ; carte activée sur le hub NEXO à l'entrée en paiement ; ticket d'activation « NOUVEAU SOLDE 20,00 E / TRANSACTION HUB00000002 ».
57. BO-11-03-05 : `eeg.auto-push-enabled=true` ; premier envoi : 40 prix sur `/eeg/prices` ; après le changement de prix de 3300000099991, seul `[{"ean":"3300000099991","price":4.90}]` est envoyé.

**Flux sortants : webservices SAF-T, e-commerce, fidélité VT, GT et VT2**

58. BO-11-04-02 : FS scellé et envoyé une fois par point d'accès : `POST /at` (text/xml, SOAP RegisterInvoice, Bearer key-AT) : « AT Envoyé 0 — AT-159 » ; après un échec de l'AT, le rejeu affiche « Envoyé (1) ».
   - WS-Security (recette Z) : sans identifiants, l'écran SAF-T indique « En-tête WS-Security de l'AT : inactif… » et FS C04/2026/00000001 part sans `<S:Header>` ; avec `fiscal.at-subuser=599999993/37`, un mot de passe et un certificat de développement, « …actif… » et FS 00000002 porte `<wsse:Security><wsse:UsernameToken>` (Username, Password, Nonce, Created) : le Nonce se déchiffre en clé de 16 octets, le mot de passe et l'horodatage UTC se restituent, accusé « 0 — AT-39 » ; certificat illisible : « …certificat de la clé publique illisible… », FS 00000003 en échec puis « Envoyé (5) » une fois le certificat rétabli.
59. BO-11-04-03 : `POST /api/ecommerce/valuation` relayé au moteur (200) ; commandes INTEGRATED, INTEGRATED et REJECTED, chaque statut posté sur `/web/events` (Bearer key-WEB).
60. BO-11-04-05, BO-11-04-06, BO-11-04-07 :
    - appel VT synchrone `/api/events/ticket-closed` (`X-Fidelity-Call: VT`) avec `payments [{"method":"CASH","amount":37.06}]` ;
    - VT répond 500 : `/api/gt` (`X-Fidelity-Call: GT`) appelé 3 fois, arrêté dès que le bouchon accepte (202) ;
    - `/api/earn` appelé après VT et avant l'impression ; ticket « CAGNOTTE PREVISIONNELLE 12,64 E ».

**Correctifs du lot 20, formats et écrans**

61. Correctifs W (rejeu RS) :
    - `POST /feeds/import/PRODUCTS` : `{"createdCount":1,"updatedCount":0,"errors":["Line 3 ignored (fewer cells than the header): TRONQ\"UE|x","Line 4 ignored (empty key 'EAN')"]}` lu par `json.loads` ; code inconnu `NO"PE` : 400 lisible ;
    - pied du central « Nœud central : référentiel et paramétrage des échelons… » (nl « Centraal knooppunt: … », pt « Nó central: … »), magasin « Nœud magasin : … » ;
    - central, 10002 renuméroté 10008 : `?pdv=10002` et `?pdv=10008` → « Résultats (1) AD1 · 10002 Villeurbanne (ancien n°, actuel 10008) POS01 POS01-00000001 … 28,80 », `?pdv=10009` → 0 ;
    - `/admin/day-end` « Fin de journée », « Le compte à rebours », « États édités en fin de période » ; `/admin/lists` « Listes administrées », « Créer une liste », « Libellé » ;
    - `/cash-count`, `/pin-change`, `/drawer-error`, `/supervisor` et `/fidelity` : « CAISSE POS01 | Opérateur : Jean Dupont » ;
    - article 3300000000002 : « SES-imagotag 99,00 € » en fr, nl et pt (bouchon 99.0) ;
    - `updateProduct` : « recordApplied PRODUCTS » à la caisse 0,8 s plus tard, scan « ORANGINA W7C » ; vente ouverte : « PRODUCTS différé : vente en cours ».
62. Balayage X : fiche article « Prix de vente courant 2,76 € TTC · 2,30 € HT · TVA 20,00 % » (nl « 2,76 € incl. btw · 2,30 € excl. btw · BTW 20,00 % », pt « 2,76 € c/ IVA · 2,30 € s/ IVA · IVA 20,00 % ») ; pager « Articles 1 à 38 sur 38 » ; aucune décimale brute ni date ISO sur 85 pages magasin et 77 pages central en fr, nl et pt, hors valeurs de saisie, versions et exemple de gabarit ; `/feeds/import/status` de la caisse, `/api/commands/POS01` du magasin et recherche de nomenclature lus par un vrai parseur JSON.
63. Écrans : menu du Back Office parcouru en fr, nl et pt à 800 × 600, 1280 × 800 et 1366 × 768 (magasin 85 pages, central 77 pages, plus favoris et mot de passe), les 33 rapports et leurs graphiques, les écrans de caisse vignettes, monnayeur, tickets en attente, sondages (oui/non, numérique, code postal) et contrôles (question, justification, refus) : pas de défilement horizontal, aucun champ qui déborde, aucune clé brute, aucun texte français sur les pages nl et pt hors données ; exports CSV, XLSX et PDF des rapports en nl et pt traduits.

Recette exécutée le 26/10/2026 en trois passes (RS journal électronique, supervision et contrôle avec le rejeu des correctifs W et X ; RT flux sortants et financier ; RV rapports) et un rejeu CS du théorique par moyen de paiement, profil dev, bases H2 mémoire jetables, matériel simulé, bouchons des receveurs externes. Les étapes 1 à 63 ont été jouées avec les messages, codes et valeurs ci-dessus : 75 exigences conformes, 3 partielles faute de spécification des receveurs (BO-11-01-07, BO-11-02-01, BO-11-02-04) ; BO-04-01-48, BO-05-01-03 et BO-11-04-02, partielles à la recette, sont complétées aux étapes 5, 21 et 58. Rejeu R du paiement carte par le pont matériel simulé répondant sans délai (`pos.mock.tpe.running-readings=0`), écran de paiement sollicité en parallèle : avant correctif, 2 `StaleStateException` et les tickets POS01-00000002 et POS01-00000017 clos sans règlement ; après correctif, 20 débits, 5 débits combinés, 5 débits dégradés forcés, 8 remboursements nets et 6 remboursements de retour, chacun avec exactement un règlement carte égal au total, aucune exception au journal. Les balayages du menu du Back Office sont propres dans les trois langues après correctifs, et les 779 captures `bo21-*` et `pos21-*` des écrans du lot sont conformes.

Limites :
- trois lignes restent partielles, faute des documents des receveurs :
  - BO-11-01-07 : chaque vente close part à Catalina (magasin, caisse, transaction, carte de fidélité, total, clé Bearer), mais les noms de champs suivent une lecture de l'interface Catalina Pos Web Service V1.21, dont le document n'est pas disponible ;
  - BO-11-02-01 : les ventes unitaires partent en temps réel en JSON TFREMA et en enregistrements DS à largeur fixe, mais les dispositions de ces deux éditeurs ne sont pas disponibles ;
  - BO-11-02-04 : DataTimeRealExport envoie à Anaveo un journal texte par vente, mais le protocole propre à Anaveo n'est pas spécifié.

Validation : imedge 6 116 tests Java + 11 JavaScript ; impos 8 810 Java + 328 JavaScript, sans échec, erreur ni test ignoré. JaCoCo : imedge 38 808 lignes et 19 877 branches, impos 37 052 lignes et 19 746 branches, tous les compteurs manqués à zéro ; JavaScript externe instrumenté à 100 %. Les 325 classes copiées entre imedge et impos sont identiques au paquet près.

Cotation (feuille Back Office) : les 78 lignes non intégrales des blocs 04 Utilitaires, 05 Financier, 06 Rapports et 11 Flux sortants sont recotées : 75 passent intégralement couvertes par CODE ; BO-11-01-07 passe de non couverte à partiellement couverte ; BO-11-02-01 et -02-04 restent partielles. Back Office P1 : 685/704 intégrales (97,3 %), 19 partielles (2,7 %), aucune non couverte, soit 704/704 intégrales ou partielles (100 %). Toutes priorités : 774 / 22 / 0, couverture pondérée 98,6 %. Bloc 04 : 71 / 0 / 0 ; bloc 05 : 23 / 0 / 0 ; bloc 06 : 31 / 0 / 0 ; bloc 11 : 31 / 3 / 0. Campagne : 525 promotions, dont 510 CODE et 15 RELECTURE.

## HD_43 — Recotation de la Ligne de caisses et défauts de caisse corrigés (26/10/2026)

Gain : les 149 lignes non intégrales de la feuille Ligne de caisses ont été confrontées au code actuel d'impos, sur preuve seulement. 32 passent intégralement couvertes (28 par RELECTURE, 4 par CODE après correction), 16 passent de non couvertes à partielles, LC-02-07-12 redescend à non couverte. Ligne de caisses : 581 / 34 / 115 → 613 / 38 / 79, couverture pondérée 81,9 % → 86,6 % ; P1 460 / 3 / 14 → 460 / 5 / 12.

Défauts corrigés (impos, un test chacun) : « Discount » en anglais sur la caisse française ; bandeau « SUPERVISEUR » non traduit ; quantité décimale armée acceptée sur un article à l'unité (scan, code saisi, saisie directe, touches, famille, GS1, plage partenaire, annulation par scan) ; invite « QUANTITÉ 1.5 » ; EAN rogné sur les touches à 6 colonnes ; textes français de la saisie directe en nl et pt ; titre « Matériel indisponible » permanent ; prix en vigueur absent de l'invite de prix du retour ; bon de réduction imprimé « BON D'ACHAT » ; libellé du moyen CAS absent ; jeton Resto Flash scanné refusé (« CODE INCONNU ») et pavé numérique seul ; base de TVA et total HT de facture à 3 décimales seulement complétés d'un zéro ; quantité du bon d'échange au format monétaire ; dépense espèces enregistrée dans le moyen de la dépense précédente ; total d'apport recalculé en flottant ; erreurs JavaScript « selectItem » et « PosKeyboards is not defined » sur six écrans de tiroir ; libellés français du bordereau de mouvement.

Préconditions : construction dev (`-Dquarkus.profile=dev`), bases H2 mémoire jetables, matériel simulé ; nœud magasin imedge et caisse impos ; paramètres administrés au Back Office du magasin et tirés par la caisse.

**Défauts de caisse**

1. Remise ligne 10 % sur BISCUITS : « BISCUITS CHOCOLAT 200G (Remise -10,00%) 2,16 € » à l'écran et sur la visu client ; nl « Korting -10,00% », pt « Desconto -10,00% ».
2. `gesture.endorsement-scope=PER_TICKET`, aval de mcurie : bandeau « SUPERVISEUR mcurie — FIN SUPERVISEUR », nl « SUPERVISOR mcurie — EINDE SUPERVISOR », pt « SUPERVISOR mcurie — FIM SUPERVISOR ».
3. QUANTITÉ 1,5 armée : invite « QUANTITÉ 1,5 — SAISISSEZ L'ARTICLE » ; scan de CHIPS 3300000000014 : « QUANTITÉ INVALIDE », total 0,00 ; même refus par code saisi et saisie directe ; QUANTITÉ 2 puis scan : « CHIPS CLASSIQUES 150G 2 x 1,80 € ».
4. LC-02-07-09 : `touch.show-ean=true`, 3 à 6 colonnes en 800 × 600, 1280 × 800 et 1366 × 768 : les 13 chiffres de l'EAN tiennent dans la touche, sur une ligne.
5. Saisie directe en nl : « Start », « < TERUG », « VORIGE », « PAGINA 1 / 2 », « VOLGENDE » ; en pt : « Início », « < VOLTAR », « ANTERIOR », « PÁGINA 1 / 2 », « SEGUINTE ».
6. Page matériel : tous périphériques présents → titre « Matériel disponible » ; visu client coupée → « Matériel indisponible ».
7. LC-05-02-15 : type de retour « Prix révisé » avec prix à confirmer, scan d'Huile d'Olive : « PRIX À SAISIR HUILE D'OLIVE 1L PRIX ACTUEL : 6,00 € » (nl « HUIDIGE PRIJS: 6,00 € », pt « PREÇO ATUAL: 6,00 € ») ; 4,50 confirmé, ligne de retour à 4,50.
8. Bon de réduction réglé : le ticket imprime son propre libellé (« Bon générique 1,00 E »).
9. CHIPS éligible carte achat sociale : écran de règlement « CARTE ACHAT SOCIALE » (nl « SOCIALE AANKOOPKAART », pt « CARTÃO DE COMPRAS SOCIAL »), ticket « CARTE ACHAT SOCIALE 1,80 E ».
10. LC-07-06-04 : zone RESTO FLASH ouverte, scan de « RF-QR-PAYLOAD-0001 » → règlement RESTOFLASH 2,76 €, reste 0,00, ticket « RESTO FLASH 2,76 E » ; pavé alphanumérique en capitales.
11. LC-08-04-26 : décimales de facture à 3, une ligne de 10,03 € à 20 % : total HT de ligne 8,358 €, base du tableau de TVA 8,358 €, TVA 1,67 €, « Sous-total HT 8,358 € ».
12. Bon d'échange : « 1,500 kg POMMES GOLDEN », « 1        LAIT UHT 1L », sans montant.
13. MOUVEMENT, dépense TENDER:CHEQUE puis dépense sans moyen : le moyen revient à Espèces ; un envoi portant encore CHEQUE sans choix manuel est enregistré en espèces ; un TR choisi à la main imprime « MOYEN TR ».
14. LC-12-04-02 : apport 50 € × 2 → 100,00 €, 20 € × 3 → 160,00 €, pièces 0,10 € × 3 → 160,30 €, total conservé entre onglets, aucune erreur de page ; bordereau « APPORT DE MONNAIE … Billet 20 € x2 40,00 E ».

**Recotation, preuves en dev des promotions**

15. Session : clé Dallas (`auth.dallas-key`) → ouverture sans PIN, clé tapée refusée ; reprise de pause par la clé de l'opérateur seul ; déverrouillage forcé par mcurie/1111 vers le changement de PIN ; aval PER_TICKET demandé à la première annulation, pas à la seconde, de nouveau après FIN SUPERVISEUR.
16. Vente : 3300000009999 inconnu → « ARTICLE INCONNU … SAISISSEZ LA FAMILLE OU LE CODE », famille EPICERIE à 2,35 → « ÉPICERIE & DIVERS 2,35 € », ticket « ARTICLE INCONNU / CODE LU 3300000009999 / Caisse : POS01 » ; prix forcé : « Prix initial: 6,00€ » sur la visu client et le ticket.
17. Second scanner client : carte lue entre deux scans opérateur rattachée ; article lu sur ce scanner refusé (422).
18. Self-scanning : panier EXT-0001 rappelé par le client 2990000000013 ; CAFÉ GRAINS ajouté, total 7,60 € ; « CONTRÔLE TOTAL : RE-SCANNER TOUS LES ARTICLES », écarts 2 → 0 ; EXT-0002 « AUCUN CONTRÔLE » → « CONTRÔLE TOTAL PROVOQUÉ » ; bon 1,00 € puis carte 6,60 €.
19. Resto Flash code 482913 : RESTOFLASH 0,96 €, reste 0,00 ; réimpression « 00000004 » → C04-00000004 seul, BON POUR ÉCHANGE imprimé ; NIF portugais contrôlé : 123456780 refusé, ESB12345678 et « DE 1234/56.789-0123X » acceptés.

Recette exécutée le 26/10/2026 par quatre groupes de recotation (blocs LC-01/02, LC-03 à 06, LC-07/08, LC-09 à 14) puis par le groupe de correction, profil dev, bases H2 mémoire jetables, matériel simulé, nœud magasin et caisse réels.

Limites : protocoles Verifone (c18, D5Y, c2g, chèque automatique), Ingenico SIBS et Worldline dans le module matériel, hors de ces dépôts ; API Restoflash signée ; NEXO pour les titres prépayés papier ; wynid, Prosodie, ATOS ; RFID ; gestion de file. LC-02-07-12 redescend : les tuiles de groupe n'affichent jamais le prix au kilo.

Validation : imedge 6 117 tests Java + 11 JavaScript ; impos 8 850 Java + 346 JavaScript, sans échec, erreur ni test ignoré. JaCoCo : imedge 38 811 lignes et 19 877 branches, impos 37 120 lignes et 19 808 branches, tous les compteurs manqués à zéro ; JavaScript externe instrumenté à 100 %. Les 325 classes copiées entre imedge et impos sont identiques au paquet près.
