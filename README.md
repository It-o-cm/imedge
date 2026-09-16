# imedge — le nœud magasin

`imedge` est le **nœud magasin** de la suite impos : un exécutable Quarkus par
magasin, qui

- **ingère** ce que les caisses poussent — sessions, tickets, remboursements,
  mouvements de caisse, tickets de balance, clients à compte, événements
  techniques (`POST /sync/**`) ;
- **détient et exporte** le référentiel du magasin vers ses caisses, par
  empreinte SHA-256 et par domaine (`GET /ref/export/**`) ;
- **intègre** ce que la gestion commerciale publie : articles, prix, familles,
  nomenclatures, magasins, employés, flux moteur (`POST /**/import`, GraphQL) ;
- **administre** le magasin : le back-office complet (`/admin/**`) et le
  journal électronique ;
- **supervise** la ligne : le tableau de bord (`/dashboard`) et l'écran des
  flux (`/admin/sync`).

Il ne vend rien. Pas de tiroir, pas de scanner, pas de TPE, pas d'écran de
vente : ces surfaces sont celles de la caisse, projet **impos**.

## Pourquoi le back-office est ici

Le référentiel appliqué sur une caisse est **remplacé** par le tirage suivant :
`RefApplyService` désactive ce que le nœud ne connaît pas. Un article, une
famille ou une nomenclature saisis côté caisse sont donc effacés dans la
période de tirage. Ce qui s'administre pour le magasin s'administre là où le
magasin exporte — ici.

Une **caisse isolée** garde son propre back-office dans impos : sans nœud, sa
portée est locale, et c'est la topologie qui décide (cf.
`docs/backoffice-cadrage.md` §, « administré sur le nœud magasin il s'applique
au magasin, édité sur une caisse isolée il s'applique localement »).

## Rapport avec impos

Les deux projets sont **autonomes** : imedge se compile, se teste et se déploie
sans impos, et réciproquement.

Le prix de cette autonomie est une **copie** : le modèle de domaine, les
payloads de synchronisation et le back-office existent des deux côtés, dans le
**même package** `com.intermarche.pos` et sous les mêmes noms de classes. C'est
délibéré — les deux nœuds partagent un schéma de base et un format de fil, donc
une modification de l'un se reporte sur l'autre par une simple copie de
fichier. Ne jamais « améliorer » une classe copiée d'un seul côté.

Ce qui n'existe QUE dans imedge : `imports/**`, `graphql/**`,
`service/sync/store/**`, `service/sync/EngineFeed*`,
`service/NomenclatureImportService`, `ui/dashboard/**`, `ui/supervision/**`,
`domain/barcode/BalanceTicket*` et `service/TicketRenderService`.

Ce qui n'existe QUE dans impos : la vente, les paiements, le matériel
(`ui/hardware/**`, scanner, TPE), la facture, le retour, la réimpression, les
écrans de caisse.

## Démarrer

L'adresse du nœud suffit : `http://localhost:8070` amène au back-office, sans
avoir à taper `/admin`.

```
./sh/imedge.sh          # démarre le nœud sur :8070 et attend qu'il réponde
./sh/imedge.sh status
./sh/imedge.sh stop
./sh/imedge.sh test     # démarre le nœud puis joue DemoStoreIT depuis impos
```

`DemoStoreIT` vit dans **impos** : c'est une caisse qu'il démarre, une vente
qu'il joue à l'écran de cette caisse, et le tableau de bord de ce nœud qu'il
interroge ensuite. L'application sous test est la caisse ; le nœud est
l'extérieur.

Le nœud tourne en **mode dev** : le seed du référentiel (`DataInitializer`) est
filtré à la compilation par `@IfBuildProfile(dev, test)`, donc un jar packagé
en prod ne le contient pas et le nœud démarrerait sur une base vide.

Une caisse pousse vers ce nœud quand elle tourne avec
`-Dpos.sync.store-url=http://localhost:8070`.

## Configuration

Tout est dans `src/main/resources/application.properties`. Les clés qui
comptent à la mise en service :

| clé | rôle |
|---|---|
| `pos.role=store` | fixé ici : c'est le nœud. Sans lui, 403 hors-rôle partout |
| `pos.pdv.number` | le PDV dont ce nœud résout les paramètres d'échelon |
| `pos.sync.token` | le jeton `X-Sync-Token` que les caisses présentent |
| `pos.sync.central-url` | tirage à deux niveaux central → magasin (route A) |
| `pos.valuation.url` | le moteur imvaluation attaché, pour la livraison des flux |

## Tests

```
mvn test                                        # la suite unitaire
mvn verify -DskipUTs=true -DskipITs=false       # SecurityExtensionLivenessIT
```

Conventions : voir `CLAUDE.md`.
