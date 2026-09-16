#!/usr/bin/env bash
# imedge.sh — starts the STORE NODE.
#
# Usage:
#   ./sh/imedge.sh          start the node and wait for it
#   ./sh/imedge.sh test     start it (if down) then run impos' DemoStoreIT
#                           against it
#   ./sh/imedge.sh stop     stop the node started by this script
#   ./sh/imedge.sh status   is it answering?
#
# DemoStoreIT lives in IMPOS and not here, and that is not an oversight: it
# boots a REGISTER, plays a sale on that register's screen and then polls
# THIS node's dashboard. The application under test is the register; the
# node is the outside world it pushes to.
#
# ONE setting decides everything here: QUARKUS DEV MODE. The referential
# seed (DataInitializer) is annotated @IfBuildProfile(dev, test), a
# BUILD-time filter — a prod-packaged jar simply does not contain it, so
# the node would start with an empty database and refuse every ingestion
# with "409 Aucun magasin sur le noeud magasin". No runtime flag can bring
# it back; the node must run in dev, like the engine and imfid do.
#
# pos.role is NOT passed here: imedge sets pos.role=store in its own
# application.properties. This IS the node.
#
# The node needs its own port AND its own database: two instances on one
# H2 file lock each other, the classic trap. 8070 is the node's own port,
# declared in its application.properties; 8080 belongs to a register.
#
# Env overrides: STORE_PORT (8070), STORE_DB (./data/store-node),
# IMEDGE_DIR, IMPOS_DIR (default ../impos, only used by `test`),
# STORE_TOKEN (shared X-Sync-Token, only if your registers send one).
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "${IMEDGE_DIR:-}" ]; then
  if [ -f "$SCRIPT_DIR/pom.xml" ]; then IMEDGE_DIR="$SCRIPT_DIR"
  else IMEDGE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"; fi
fi
if [ ! -f "$IMEDGE_DIR/pom.xml" ]; then
  echo "✗ Racine imedge introuvable ($IMEDGE_DIR sans pom.xml) — exporter IMEDGE_DIR."; exit 1
fi
IMPOS_DIR="${IMPOS_DIR:-$IMEDGE_DIR/../impos}"

STORE_PORT="${STORE_PORT:-8070}"
STORE_DB="${STORE_DB:-./data/store-node}"
STORE_URL="http://localhost:$STORE_PORT"
RUN_DIR="$IMEDGE_DIR/.demo-stack"
PID_FILE="$RUN_DIR/imedge.pid"
LOG_FILE="$RUN_DIR/imedge.log"
mkdir -p "$RUN_DIR"

is_up() { curl -s -o /dev/null --max-time 1 "$STORE_URL/dashboard-data"; }

case "${1:-start}" in

  stop)
    if [ -f "$PID_FILE" ]; then
      pid=$(cat "$PID_FILE")
      kill "$pid" 2>/dev/null && echo "Nœud magasin arrêté (pid $pid)."
      rm -f "$PID_FILE"
    else
      echo "Aucun nœud magasin lancé par ce script."
      echo "S'il tourne quand même : lsof -ti :$STORE_PORT | xargs kill"
    fi
    exit 0
    ;;

  status)
    if is_up; then echo "✓ Nœud magasin actif — $STORE_URL/dashboard"
    else echo "✗ Nœud magasin muet sur :$STORE_PORT"; fi
    exit 0
    ;;

  start|test) ;;
  *) echo "Usage: $0 [start|test|stop|status]"; exit 1 ;;
esac

# ---------- start ----------
if is_up; then
  # A node this script did not start is a TRAP: it may be the prod-packaged
  # jar (no referential seed → "409 Aucun magasin"), and reusing it silently
  # costs a full test run to rediscover. Refuse rather than reuse what we
  # cannot vouch for.
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "· Nœud magasin déjà actif sur :$STORE_PORT (lancé par ce script) — réutilisé."
  else
    echo "✗ Un nœud NON GÉRÉ par ce script occupe :$STORE_PORT."
    echo "  Il tourne peut-être en prod (pas de seed → 409 « Aucun magasin »)."
    echo "  Le tuer puis relancer :"
    echo "      lsof -ti :$STORE_PORT | xargs kill"
    echo "      $0 ${1:-start}"
    exit 1
  fi
else
  echo "· Démarrage du nœud magasin en DEV (port $STORE_PORT, base $STORE_DB)…"
  TOKEN_ARG=""
  if [ -n "${STORE_TOKEN:-}" ]; then TOKEN_ARG="-Dpos.sync.token=$STORE_TOKEN"; fi
  ( cd "$IMEDGE_DIR" && nohup mvn -q quarkus:dev -Dquarkus.console.enabled=false \
      -Dquarkus.http.port="$STORE_PORT" \
      -Dquarkus.datasource.jdbc.url="jdbc:h2:file:$STORE_DB" \
      -Ddebug=false \
      $TOKEN_ARG \
      > "$LOG_FILE" 2>&1 & echo $! > "$PID_FILE" )

  printf "· Attente de :%s " "$STORE_PORT"
  waited=0
  while [ $waited -lt 120 ]; do
    if is_up; then echo "— prêt (${waited}s)."; break; fi
    sleep 2; waited=$((waited+2)); printf "."
  done
  if ! is_up; then
    echo; echo "✗ Muet après ${waited}s — voir $LOG_FILE"; exit 1
  fi
fi

# ---------- test ----------
if [ "${1:-start}" = "test" ]; then
  if [ ! -f "$IMPOS_DIR/pom.xml" ]; then
    echo "✗ Racine impos introuvable ($IMPOS_DIR sans pom.xml) — exporter IMPOS_DIR."
    echo "  DemoStoreIT est porté par la caisse : c'est elle qu'il démarre."
    exit 1
  fi
  echo "· DemoStoreIT (projet impos) contre le nœud magasin…"
  ( cd "$IMPOS_DIR" && mvn verify -DskipITs=false -DskipUTs=true -Dit.test='DemoStoreIT' ) \
    || { echo "✗ MODULE XIII ROUGE (le nœud reste up pour diagnostic)."; exit 1; }
  echo "✓ MODULE XIII VERT — la vente remonte au magasin."
fi

cat <<READY

════════════════════════════════════════════════════════
  NŒUD MAGASIN PRÊT (imedge)
    Adresse       $STORE_URL                ← l'adresse seule suffit
    Dashboard     $STORE_URL/dashboard      ← LE seul agrégateur
    Back-office   $STORE_URL/admin          (admin / admin)
    Flux          $STORE_URL/admin/sync
    Mode          dev (le seed du référentiel n'existe pas en prod)
    Superviseur   depuis la caisse : menu AUTRES → APPEL SUPERVISEUR
  Log: $LOG_FILE   —   Arrêt: ./sh/imedge.sh stop

  ⚠ La CAISSE (impos) doit savoir où pousser : elle doit tourner avec
      -Dpos.sync.store-url=$STORE_URL
    (sinon le dashboard restera à zéro — ce n'est pas un bug).
════════════════════════════════════════════════════════
READY
