# Campagne « rapport de l'expert caisse » — le complément imedge

La caisse construit les gestes ; **toi, tu construis ce sans quoi ils ne valent
rien** : le paramètre qui les règle, le journal qui les prouve, le canal qui les
porte, et les documents que le magasin remet au client.

Ce prompt est le pendant de `impos/ia/expert-campaign.md`. Lis-le : il nomme,
étape par étape, les cinquante-trois exigences `EXP-nn` que la caisse sert. Ici
tu ne sers aucune d'elles directement — tu les rends utilisables.

---

## 1. La règle qui gouverne tout

**Un paramètre créé en caisse n'existe pas tant que le back-office ne
l'administre pas.** `PosSettingsService` porte des valeurs par défaut ; ce sont
des valeurs de secours, pas une administration. Un seuil qu'on ne peut pas
régler depuis le magasin est un seuil codé en dur avec une étape de plus.

De même : **un geste qui n'arrive pas au journal n'a pas eu lieu.** La moitié
des exigences de cette campagne existent pour qu'un tiers constate après coup ce
qui s'est passé au tiroir. Si l'événement part de la caisse et que
`JournalService` ne sait ni l'afficher ni le chercher, la moitié du travail est
perdue.

Tu travailles donc en miroir, et tu livres en même temps que la caisse.

## 2. Quatre chantiers

### A · Les paramètres à administrer

La campagne caisse crée une quinzaine d'entrées `Def`. Chacune a besoin d'un
écran dans `AdminSettingsResource`, d'une place dans la chaîne d'échelons
`COUNTRY → ENSEIGNE → PDV` (`EchelonSetting`, `AdminEchelonResource`), et d'une
descente par `ECHELON_DOMAINS` de `RefPayloads`. La caisse ne reçoit que des
valeurs résolues : elle ignore l'échelon qui a gagné.

Au niveau **point de vente**, parce que c'est là que le métier les fixe :

- **plafond de contenu du tiroir** — calé sur le montant garanti par
  l'assurance en cas de braquage ; déclenche l'alerte `PRÉLÈVEMENT` (`EXP-17`).
- **seuil d'écart de caisse** — usuellement 1 € ; au-delà, recomptage ou
  validation superviseur (`EXP-82`).
- **mode de fond de caisse** — fixe ou flottant. En flottant, le soir le
  caissier prélève les billets et laisse l'équivalent du fond initial, et ne
  compte réellement qu'en fin de semaine et le dernier jour du mois (`EXP-95`).

Au niveau **enseigne** le plus souvent, surchargeable en dessous :

- **la matrice d'autorisations** — un drapeau par fonction négative :
  annulation de ligne, annulation du dernier article, abandon de ticket, retour
  ticket, retour sans ticket, remboursement, remise, forçage de prix (`EXP-32`).
  Un écran qui les présente en tableau, pas quinze cases éparpillées.
- **la portée de l'aval superviseur** — à chaque opération, ou une fois pour
  tout le ticket jusqu'à `FIN SUPERVISEUR` (`EXP-33`).
- **les libellés d'accueil et d'attente** — `Bonjour : Connectez-vous`,
  `CLIENT SUIVANT` (`EXP-01`, `EXP-03`).

Et l'écran des **plages de codes-barres** (`BO-03-06-03/04/05`), qui est le plus
exigeant du lot. Pour chaque plage : les préfixes, la **position** et la
**longueur** du champ de valeur, sa nature — prix ou poids — et, pour un prix,
son **format monétaire : euro ou franc français**. Les deux coexistent dans le
parc d'étiquettes. Le taux de conversion du franc, `6,55957`, s'administre comme
un taux de devise et descend par la synchro : `AdminTenderResource` et le
domaine des devises montrent le chemin. Rien de figé dans le logiciel de caisse.

### B · Le journal électronique

La campagne caisse émet des événements nouveaux par
`TechnicalEventService.log(...)`. Le transport existe et est rejouable ; ce qui
manque de ton côté est le **sens** et la **recherche**.

Pour chaque type nouveau : une valeur dans `EventTypes`, son libellé lisible, et
un critère de recherche dans `JournalService` / `JournalResource`. Les gestes
qui comptent :

- verrouillage et déverrouillage, avec l'identité de l'opérateur concerné ;
- **déverrouillage forcé** par un superviseur, et **fin de journée forcée** —
  deux gestes distincts, deux traces distinctes (`EXP-14`, `EXP-96`) ;
- fermeture et réouverture de caisse sans clôture — la notion nouvelle
  (voir §3) ;
- ouverture et fermeture d'une **session superviseur**, avec l'identité de celui
  qui l'a ouverte et la façon dont elle s'est refermée : touche
  `FIN SUPERVISEUR` ou fin de ticket (`EXP-33`) ;
- franchissement du plafond du tiroir (`EXP-17`) ;
- **validation d'un écart de caisse** par un superviseur, avec son identité et
  le montant validé (`EXP-82`) ;
- refus d'annulation d'un article unique (`EXP-35`) — un refus se journalise
  aussi, c'est même souvent lui qui explique une plainte.

Les objets persistés nouveaux suivent l'autre moitié du chemin : une valeur dans
`SyncOutbox.EntityType` côté caisse, un DTO dans `SyncPayloads`, une route dans
`SyncEndpoints`, un *upsert* par `uid` dans `SyncIngestService`. Concernés : le
détail d'apport par coupure, le comptage des moyens papier, le bordereau de
règlement autonome, et la trace de validation d'écart.

### C · Les deux canaux

**Descendant.** `RegisterCommandService.issue / pending / acknowledge` existe et
porte déjà la fermeture forcée. C'est lui qui portera le **message du
back-office vers la caisse** (`EXP-20`) : un type de commande de plus, un écran
de saisie côté magasin, et la règle que l'expert pose — le message s'affiche sur
**l'écran caissier seul**, jamais sur l'écran client, et **ne perturbe pas
l'encaissement**. N'invente pas un second canal.

**Montant.** `DashboardResource` reçoit déjà `/api/supervisor/call` avec ses
quatre motifs figés. Deux exigences l'élargissent : le **message libre** de la
caisse vers le back-office (`EXP-19`), et la **demande de service** choisie dans
une liste **administrée** au lieu de quatre boutons en dur (`EXP-84`). Même
endpoint, même écran de supervision, une liste de motifs qui devient un
référentiel.

### D · Les documents du magasin

Ce sont les exigences de l'onglet G, et elles sont toutes bâties sur le même
geste : **le back-office remet un code au client, la caisse le lit, les articles
apparaissent.**

- **Devis et commandes** (`EXP-90`) : saisie au back-office, code remis au
  client, paiement total ou acompte en caisse.
- **Bon de vente** (`EXP-91`) : même mécanique, paiement de l'ensemble.
- **Click and collect** (`EXP-92`) : le panier vient d'un outil de mobilité, la
  caisse le rappelle et le fait payer.
- **Acompte** (`EXP-85`) : il existe côté caisse comme mouvement
  `CUSTOMER_DEPOSIT` sans rattachement. C'est toi qui portes la commande à
  laquelle il se rattache, donc la reprise.

Écris **un seul** mécanisme de rappel par code, et fais-en dériver les quatre.
Trois implémentations parallèles du même geste seraient trois fois la même
maintenance.

**Le coffre** (`EXP-87`) est déjà chez toi : `AdminSafeInventoryResource`,
`AdminSafeMovementResource`, `AdminSafeJournalResource`,
`AdminSafePeriodResource`. Ce qui manque n'est pas le coffre mais **le
raccord** : les prélèvements et les apports que la caisse émet doivent
l'alimenter au lieu de rester des mouvements de caisse sans destination.

**La reprise d'un ticket en attente sur une autre caisse** (`EXP-46`)
t'appartient aussi : hors ligne la caisse ne reprend que ses propres tickets, et
c'est la règle dégradée assumée. Le mode connecté — le nœud qui sait où dort un
ticket garé — est à construire ici.

## 3. Le vocabulaire — et il change le modèle

L'expert corrige le jargon, et la correction vaut pour tes écrans autant que
pour la caisse. **Trois** notions, pas deux :

- **Verrouiller / déverrouiller** — une pause courte, le ticket survit.
- **Ouvrir / fermer sa caisse** — la caissière retire son tiroir pour déjeuner
  ou l'emporter sur une autre caisse. **Rouvrir l'après-midi est la continuité
  du matin** : même session, même fond.
- **Clôturer** — compter, prélever tous les moyens, RAZ de fin de poste.

Tes écrans de supervision, ton journal et tes rapports emploient ces mots-là, et
ne présentent plus une fermeture comme une clôture. Une **session de caisse**
peut donc contenir plusieurs fermetures et réouvertures, et un seul comptage
final : ce que tu agrèges et ce que tu affiches doit en tenir compte.

## 4. Portée

- Tu construis ce que décrivent les quatre chantiers, et rien d'autre.
- Tu ne modifies pas `impos`. Si la caisse doit changer pour que ton écran
  fonctionne, tu le signales en une ligne — c'est l'autre campagne qui le fera.
- Un refactoring rencontré en chemin se signale ; il ne se fait pas.
- Git : un commit par chantier, sur la branche courante. Jamais de branche
  nouvelle, jamais de push.
- Package : `com.intermarche.edge.service` pour les services généraux ; tout ce
  qui sert un écran va dans `ui.*`. Jamais de package à classe unique.

## 5. Tests

- Unitaires : JUnit 5 + Mockito nu, selon `CLAUDE.md`. Jamais `@QuarkusTest`,
  jamais H2 pour un test de classe.
- **Chaque jambe de chaque expression booléenne composée a son cas de test**,
  nullité comprise.
- Les scénarios e2e se déclarent dans `e2e-scenarios.md`, dans le groupe qui
  leur correspond, **dans le fichier existant**.
- `mvn verify` vert à la fin de chaque chantier. Les deux classes rouges
  préexistantes ne te concernent pas.
- Attention au port de test : `imedge` ne fixe pas `quarkus.http.test-port` et
  hérite donc du 8081 d'`impos`. Deux suites lancées ensemble se percutent.

## 6. Ce que tu rends

Par chantier : les exigences servies, les écrans créés, les domaines de synchro
touchés, le verdict de `mvn verify`.

À la fin : la liste des paramètres administrables ajoutés avec leur échelon, la
liste des types d'événements journalisables ajoutés, et — en une ligne chacune —
les questions restées ouvertes et les changements que la caisse doit faire pour
que l'ensemble tienne.
