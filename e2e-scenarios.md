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
