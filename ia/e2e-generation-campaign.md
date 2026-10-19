# Campagne — écrire les tests e2e d'imedge depuis le catalogue

## La source

`e2e-scenarios.md` à la racine du projet : 1890 lignes, **121 fonctionnalités** en
groupes **A → R**, chacune en table `Fonction | Cas | Attendu`. La colonne *Attendu*
porte le code HTTP, le message EXACT ou l'effet en base. C'est la spécification de
cette campagne — on n'invente rien, on n'interprète rien.

Ses deux dernières sections font foi et ne sont pas à rediscuter : la **convention
d'implémentation** (une classe `Group<X>IT` par groupe dans `com.intermarche.e2e`,
un `@Test` par fonction/cas, l'identifiant de la fonctionnalité dans le nom de méthode
ET dans sa javadoc) et la **priorité d'exécution** en sept étapes.

## L'existant, à ne pas redoubler

`src/test/java/com/intermarche/edge/security/SecurityExtensionLivenessIT.java` — 3 tests
qui couvrent déjà A8, P6 et le refus d'import anonyme. Ne pas les réécrire, ne pas les
déplacer. Toute autre classe e2e est à créer.

Harnais : `@QuarkusTest` + RestAssured, comme cette classe. **Pas de Playwright, aucun
scénario navigateur** (`CLAUDE.md`). Failsafe est configuré, `<skipITs>true</skipITs>`
par défaut.

## Les lots

Dans l'ordre de priorité du catalogue. **Un lot = une livraison + un rapport ; on ne
démarre pas le suivant avant que le précédent soit vert.**

1. **F** — la porte d'import et ses onze délégations (F1 → F13). Le plus gros gisement
   et le plus fragile.
2. **C** puis **D** — ingestion des remontées de caisse, service du référentiel.
3. **J** — les onze rapports ; la concordance CSV/XLSX/PDF est le test le moins cher.
4. **A** et **B** — droits, rôles, garde `pos.role`, jeton `pos.sync.token`.
5. **H, I, K, L, M, N, O, P, Q** — le reste des surfaces `[E]`.
6. **G** et **E** — supervision et brique centrale. Demandent la pile de démo complète.
7. **R** — robustesse et reprise, en dernier.

**Ne traiter dans les lots 1 à 5 que les scénarios `[E]`** (nœud seul, base H2, aucune
caisse). Un scénario `[R]` (caisse), `[C]` (central) ou `[V]` (moteur) n'est ni écrit
ni simulé : il est listé dans le rapport du lot, pour les lots 6 et 7.

## Règles de construction — elles viennent d'échecs réels, pas de style

**Un profil de test par classe.** Chaque `Group<X>IT` déclare sa propre classe de
profil imbriquée, même vide :

```java
public static class Profile implements QuarkusTestProfile { }
```

Quarkus compare la CLASSE de profil : deux profils distincts, même s'ils produisent la
même configuration, forcent le redémarrage de l'application entre deux classes, donc
base recréée et état neuf. Sans ça, les 121 groupes partagent une seule application et
une seule base : un rouge peut alors venir de la classe d'avant, et plus aucun rapport
n'est une mesure. Le coût est un boot par classe, et il est acceptable.

**Un `@AfterEach` qui remet la classe à zéro** — lignes créées, session d'authentification,
réglages modifiés. Une méthode qui échoue au milieu ne doit pas empoisonner les suivantes :
un test qui meurt entre « j'active » et « je désactive » laisse la bascule allumée.

**L'attendu du catalogue est l'attendu du test, au caractère près.** Les messages sont
cités entre guillemets français dans la colonne *Attendu* : ils s'asserent en entier,
pas par `contains` sur un fragment. Un code HTTP s'asserte tel qu'écrit.

**Chaque assertion porte un message qui nomme l'état observé**, pas seulement
« attendu X, obtenu Y » : le code de retour, le corps ou le champ qui tranche. Un test
qui échoue doit se diagnostiquer seul — sans ça, chaque rouge coûte une demi-heure de
lecture de code.

**Authentification** : compte ADMIN d'amorçage, via `/j_security_check` (form auth,
cookie `quarkus-credential`), mot de passe fourni par la configuration du profil de
test — jamais en dur dans une classe, jamais un secret réel.

**Asynchrone** : ne jamais asserter sur un rendu d'écran pour prouver un effet de bord.
On attend l'état serveur (la base, la réponse d'un GET de contrôle), avec une échéance
bornée et un message d'échec qui nomme l'état atteint.

## Interdits

- **Ne pas modifier `src/main`.** Si un scénario révèle un écart entre le code et le
  catalogue : le test est écrit conforme au CATALOGUE, il est laissé rouge, et l'écart
  est décrit dans le rapport (fonction, attendu du catalogue, comportement observé,
  fichier:ligne). On ne corrige pas le code, et **on n'aligne jamais l'attendu sur le
  code** : une spécification qui se trompe sur l'existant est précisément ce qui fait
  passer une régression pour une évolution.
- Ne pas désactiver, `@Disabled`, ni affaiblir un test pour le faire passer.
- Ne pas toucher à `SecurityExtensionLivenessIT`.
- Pas de git : ni commit, ni branche, ni stash.

## Règles dures habituelles

Code et commentaires en anglais. **Javadoc sur toutes les méthodes sans exception**,
tests compris, avec l'identifiant de la fonctionnalité et le cas couvert. Portée
stricte : rien en dehors du lot ; une observation hors périmètre tient en une ligne du
rapport.

## Exécution

Une classe à la fois, en tâche de fond — une classe dépasse largement le délai d'un
shell :

```
cd ~/Documents/Workspaces/imedge
nohup mvn verify -DskipUTs=true -DskipITs=false -Dit.test='GroupFIT' > /tmp/imedge-F.log 2>&1 &
```

Puis lire `target/failsafe-reports/com.intermarche.e2e.GroupFIT.txt`. Jamais deux
runs en parallèle sur le dépôt : ils se disputent le port 8081 et s'annulent.

## Rapport par lot

Le groupe traité, le nombre de cas du catalogue et le nombre de `@Test` écrits, le
résultat du run, la liste des scénarios `[R]`/`[C]`/`[V]` mis de côté, et — séparément,
c'est le livrable qui compte le plus — les écarts entre le code et le catalogue, un par
ligne, avec leur preuve.
