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
| `GET /versions` | Rôle `store` | 200 : empreintes des **16 domaines MAGASIN** (`ENGINE_FEEDS, PRODUCTS, FAMILIES, TOUCH_GROUPS, VAT_RATES, PRICES, EMPLOYEES, COUPON_TYPES, ARTICLE_RANGES, ISLANDS, TENDERS, DOCUMENT_TEMPLATES, SETTINGS, CUSTOMERS, CURRENCIES, POSTAL_CODES`) |
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
| Tolérance null | `openingDate`/`closingDate` null | accepté (`parse` tolère null) |
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
| Ticket non exigé | `ticketNumber` null ou introuvable | `ticket=null`, `ticketNumber` conservé |
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

## D. Service du référentiel aux caisses [E] [R]

`imports/RefExportResource` + `service/sync/store/RefExportService`. Garde commune (`gate`) : rôle ni store ni central → **403** « Ce nœud n'a pas le rôle store ou central » ; jeton configuré non vide et discordant → **401** « Jeton invalide » ; jeton absent → ouvert.
**Domaines réels** : `RefPayloads.DOMAINS` = **16** (ENGINE_FEEDS, PRODUCTS, FAMILIES, TOUCH_GROUPS, VAT_RATES, PRICES, EMPLOYEES, COUPON_TYPES, ARTICLE_RANGES, ISLANDS, TENDERS, DOCUMENT_TEMPLATES, SETTINGS, CUSTOMERS, CURRENCIES, POSTAL_CODES) ; `RefPayloads.ECHELON_DOMAINS` = **4** (COUNTRIES, ENSEIGNES, PDVS, ECHELON_SETTINGS). Le `switch` de `getPage` couvre l'union exacte = **20** cas.

**D1 Empreintes par domaine** `GET /api/referential/versions` [E] [R]

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Garde rôle | rôle register | 403 « Ce nœud n'a pas le rôle store ou central » |
| Garde jeton | jeton discordant | 401 « Jeton invalide » |
| Liste store | rôle store | 200 map SHA-256, **16** domaines (`DOMAINS`) |
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

**K8 Événements techniques de tenue de poste (chantier B expert)** `JournalService` / `journal.html` [E]

Neuf valeurs ajoutées à `TechnicalEvent.EventType` (en fin d'enum, ordinal stable — à reporter dans impos côté caisse). La recherche, le filtre par case à cocher et l'affichage du journal étant génériques (`EventType.values()` alimente le filtre, `eventType in` la requête), chaque nouveau type est cherchable et filtrable sans autre code.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Filtre | case à cocher par type | les 9 nouveaux types apparaissent dans le filtre (générique `EventType.values()`) |
| Recherche | `eventType=DISCREPANCY_VALIDATED` | journal filtré sur la validation d'écart nominative (EXP-82) |
| Recherche | `eventType=REGISTER_UNLOCK_FORCED` / `SESSION_FORCE_CLOSED` | déverrouillage forcé (EXP-14) et fin de journée forcée (EXP-96), deux traces distinctes |
| Recherche | `TILL_CLOSED` / `TILL_REOPENED` | fermeture / réouverture de caisse sans clôture (§3), distinctes de LOCK et de SESSION_CLOSED |
| Recherche | `SUPERVISOR_SESSION_OPENED` / `_CLOSED` | ouverture / fermeture de session superviseur (EXP-33) |
| Recherche | `DRAWER_CEILING_REACHED` / `LAST_ITEM_CANCEL_REFUSED` | plafond tiroir franchi (EXP-17), refus d'annulation d'article unique (EXP-35) |
| Opérateur | recherche par badge sur ces types | l'identité concernée (superviseur) portée par `operatorBadgeId` |
| Reste à faire | objets persistés structurés (apport par coupure, comptage moyens papier, bordereau autonome) | nécessitent de nouvelles entités PARTAGÉES + production côté caisse + coordination d'ordinal `SyncOutbox.EntityType` — hors périmètre nœud seul, signalé |

**K9 Deux canaux back-office ↔ caisse (chantier C expert)** `AdminRegisterMessageResource`, `DashboardResource#receiveCall` [E] [R]

Canal DESCENDANT (EXP-20) : message du back-office vers une caisse, porté par le canal de commandes existant (`RegisterCommand.CommandType.DISPLAY_MESSAGE`, append STRING — à reporter dans impos). Canal MONTANT (EXP-19/84) : l'endpoint `/api/supervisor/call` accepte déjà un `reason` libre (message libre, EXP-19) ; la liste des demandes de service devient un référentiel administré (`supervisor.service-requests`, EXP-84).

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Descendant écran | `GET /admin/register-messages` ADMIN | 200 (caisses = points de caisse administrés) |
| Descendant envoi | caisse non choisie | 303 « Choisissez une caisse destinataire. » false |
| Descendant envoi | caisse inconnue | 303 « Caisse inconnue : <id> » false |
| Descendant envoi | message vide | 303 « Le message est obligatoire. » false |
| Descendant envoi | succès | `issue(terminal, DISPLAY_MESSAGE, opérateur, message)` ; 303 « Message déposé pour la caisse <id>. » true |
| Descendant règle | affichage caisse | écran caissier seul, jamais écran client, sans interrompre la vente (rendu côté caisse, signalé) |
| Montant libre | `POST /api/supervisor/call` reason libre | ajouté au registre (EXP-19, chemin existant) |
| Montant liste | `supervisor.service-requests` administré | `supervisorServiceRequests()` fournit les motifs à la caisse ; vide → saisie libre seule (EXP-84) |

---

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
| `GET /` | Page | 200 HTML ; aperçu de chaque gabarit administré vs sa vente de démo ; 11 types (`SALE_RECEIPT`…`LOYALTY_RECEIPT`) ; picker « Partir de » même type d'abord |
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

**M14 Paramètres de tenue de poste (chantier A expert)** `/admin/settings` + `/admin/echelons` [E]

Quinze `Def` ajoutés au catalogue `PosSettingsService`, rendus par l'écran de paramétrage générique (M1) et posables à tout échelon par l'écran des échelons (E5) — l'administration, la chaîne d'échelons et la descente `ECHELON_SETTINGS` sont génériques, un `Def` suffit. La caisse ne reçoit que la valeur résolue.

| Fonction | Cas | Attendu |
| --- | --- | --- |
| Tiroir | `drawer.content-ceiling` (€, PDV) | rendu section TIROIR ; `drawerContentCeiling()` lit le décimal ; 0 = pas de plafond (EXP-17) |
| Écart | `cash.discrepancy-threshold` (€, PDV) | rendu SESSION CAISSE ; défaut 1,00 € ; `cashDiscrepancyThreshold()` (EXP-82) |
| Fond | `cash.float-mode` (PDV) | `cashFloatMode()` normalise FIXED / FLOATING, inconnu → FIXED (EXP-95) |
| Matrice | 8 `auth.endorse-*` (BOOL, enseigne) | rendus GROUPÉS sous la section AUTORISATIONS ; `endorsementRequired(key)` ; défaut true (EXP-32) |
| Portée aval | `auth.endorsement-scope` (enseigne) | `endorsementScope()` normalise PER_OPERATION / PER_TICKET, inconnu → PER_OPERATION (EXP-33) |
| Libellés | `ui.login-greeting`, `customer.next-customer-message` | `loginGreeting()` / `nextCustomerMessage()` administrés (EXP-01, EXP-03) |
| Échelon | pose d'un `auth.endorse-*` au niveau ENSEIGNE, surchargé au PDV | résolution la plus spécifique gagne (E7) — « réglable par échelon » |

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

Fichiers lus (chemins absolus) : `/Users/henridarmet/Documents/Workspaces/imedge/src/main/java/com/intermarche/pos/ui/journal/JournalResource.java`, `.../ui/journal/JournalService.java`, `.../ui/dashboard/DashboardResource.java`, `.../ui/dashboard/DashboardService.java`, `.../ui/admin/AdminSettingsResource.java`, `.../ui/admin/AdminStoreResource.java`, `.../ui/admin/AdminTenderResource.java`, `.../ui/admin/AdminTouchResource.java`, `.../ui/admin/AdminCouponResource.java`, `.../ui/admin/AdminCampaignResource.java`, `.../ui/admin/AdminCustomerResource.java`, `.../service/campaign/CampaignService.java`, `.../service/campaign/CampaignFeedProjector.java`, `.../service/PosSettingsService.java`, `.../imports/SyncIngestResource.java`, `.../service/sync/store/SyncIngestService.java`, `.../imports/AccountCustomerCsvResource.java`, `.../domain/payment/AccountCustomer.java`.

Points de code notables (au-delà de S-C) : la fiche magasin ne lit jamais le code PDV du form ; `save` client ne lit ni `accountNumber` ni `origin` ; l'ingestion caisse ne pose jamais `origin` (défaut REGISTER en création, conservation en update) ; `CampaignFeedProjector` livre `BUDGET_GROUPS` avant `OFFERS` ; les appels superviseur du dashboard ne sont exposés que si `dashboardAlertsEnabled()`.

## P. GraphQL [E]

Endpoint unique `/graphql`. **Matrice d'accès (par opération)** : queries `@RolesAllowed("MANAGER")`, mutations `@RolesAllowed("ADMIN")` ; anonyme → 401 ; rôle insuffisant → 403 ; autorisé → 200. Gestion d'erreurs centralisée par `GraphQLTrait.execute` : `AlreadyExistsException` et `IllegalArgumentException`/autres → `GraphQLException` dans l'enveloppe `errors` (pas 500) ; `PersistenceException` → `GraphQLException` « Database error while performing <op>. Please check your data. » ; `NoSuchElementException` relancée telle quelle (message spécifique dans `errors`). GraphQL répond toujours 200 sauf refus d'accès.

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

## Couverture — état de départ

Ce catalogue énumère, par fonctionnalité, chaque fonction et chacun de ses cas
nominal et d'erreur (colonne *Attendu* = code HTTP + message exact / effet). La vue
condensée antérieure (151 scénarios) est remplacée par ces tables exhaustives.

État des tests écrits aujourd'hui : `src/test/java/com/intermarche/pos/**IT.java`
ne contient qu'une classe, `SecurityExtensionLivenessIT` (3 tests) — elle couvre trois
gardes de sécurité : import CSV anonyme refusé (B1/F), adresse par domaine retirée,
requête GraphQL anonyme refusée (P). La consolidation deux-nœuds (une caisse pousse,
le magasin consolide) est prouvée par `DemoStoreIT`, qui appartient à **impos**. Tout
le reste est à écrire.

## Convention d'implémentation

Une classe par groupe, `Group<X>IT` dans `com.intermarche.pos.e2e`, un `@Test` par
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
