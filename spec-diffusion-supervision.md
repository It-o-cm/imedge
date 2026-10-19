# Spécification — Diffusion & supervision des flux (delta sur la console imedge)

**Projet** : imedge (le nœud magasin) · **Chantier** : bloc RFP « Diffusion &
supervision » (GB-01-01-01/02/19/20/30 ; GB-01-01-03 documentaire) · **Statut** :
validé en séance le 26/09/2026 — re-ciblé d'impos vers imedge après constat :
la console EXISTE déjà au nœud (`admin-sync.html`, « Supervision des flux »).
**Moitié caisse** : un panneau de santé minimal reste chez impos
(`spec-sante-caisse.md` dans le repo impos).

## 1. Constat — l'écran existe, il manque le pilotage fin

`admin-sync.html` montre déjà, par flux : versions stockée/acquittée, dernière
erreur, plus le tirage référentiel, l'outbox magasin et la session locale ; deux
actions globales existent (`/admin/sync/pull`, `/admin/sync/deliver`). Le delta
RFP tient en quatre manques : rafraîchissement automatique, livraison **ciblée**
par flux, **planification**, et rejeu **ciblé** d'une ligne en erreur. Rien de la
mécanique de synchronisation ne change — l'écran l'appelle.

## 2. Le delta

1. **Rafraîchissement automatique** (le « temps réel » de GB-01-01-20/30) :
   patron `valuation-refresh` d'imvaluation — remplacement des corps de tableaux
   seuls (flux moteur, tirage, outbox), toutes les 2 s, suspendu quand l'onglet
   est masqué, actions et scroll préservés.
2. **Livrer maintenant, par flux** (GB-01-01-01) : un bouton par ligne du tableau
   « Flux moteur » — variante ciblée par code à exposer dans le service de
   livraison (même transaction, même journal que la livraison globale), garde
   `data-confirm`. La ligne en erreur utilise le même bouton : c'est le rejeu
   ciblé (GB-01-01-19/30) — l'idempotence aval (imports par checksum,
   consommation par ticket) rend le rejeu sûr par construction ; le documenter
   dans la Javadoc de l'action.
3. **Planifier une livraison** (GB-01-01-02) : entité `ScheduledDelivery`
   {`feedCode` nullable = tous, `scheduledAt`, `createdBy`, état
   PENDING/DONE/FAILED (message)} — exécutée par la boucle de livraison
   existante à son réveil (précision à la minute, assumée : c'est de la
   diffusion d'offres, pas du trading). Section « Planifications » sur l'écran :
   création (flux + date/heure), liste, annulation tant que PENDING.
4. **Note documentaire** (GB-01-01-03, diffusion immédiate) : c'est le
   comportement nominal existant (la boucle livre au fil de l'eau) — la réponse
   RFP cite l'écran, rien à développer.

RBAC : mêmes règles que l'écran actuel (le profil admin du nœud pilote ; les
autres voient sans boutons ; anonyme refusé).

## 3. Ce que le chantier ne fait PAS

- Aucune modification de la mécanique (tirage, catalogue, versions, checksums,
  outbox) : seule la variante « livraison ciblée par code » s'ajoute au service.
- Pas de panneau de consommation ici tant que la dérivation ticket→déclaration
  tourne côté caisse (pile démo) : le panneau suit le composant, voir la moitié
  caisse.
- Pas d'exports financiers (chantier à part).

## 4. Cas normatifs (tests)

1. Rafraîchissement : les corps de tableaux se mettent à jour sans rechargement,
   suspendus onglet masqué (test JS, patron des tests `valuation-refresh`).
2. « Livrer maintenant » ciblé : seul le flux visé part, version acquittée mise
   à jour, journal écrit ; les autres flux inchangés.
3. Ligne en erreur (moteur coupé) → marquage erreur ; moteur revenu + bouton →
   ligne à jour, pas de double application (checksum).
4. Planification échue → livrée au réveil, DONE ; planification annulée →
   jamais exécutée ; échec de livraison planifiée → FAILED + message, visible.
5. Accès : profil non pilote = aucune action rendue, anonyme = refusé.

**Règles dures habituelles** : code et Javadoc en anglais, Javadoc sur toute
méthode sans exception, portée stricte (l'écran, la variante ciblée,
`ScheduledDelivery` — rien d'autre), suite complète (Java + JS) verte.
