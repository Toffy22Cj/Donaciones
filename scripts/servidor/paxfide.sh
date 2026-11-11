#!/usr/bin/env bash
# Servidor de pruebas de PaxFide en un solo PC: MongoDB y MinIO en Docker, backend (jar), web (Next) y dos
# Cloudflare quick tunnels (API y web). Ningún valor real va en este script: todo sale de backend.env.
#
#   paxfide.sh arrancar      túneles nuevos, URLs a backend.env y a la web, recompila la web y arranca todo en tmux
#   paxfide.sh semilla       BORRA la base y carga el escenario de la demo (cuentas, convocatorias, trackingCode)
#   paxfide.sh compilar      git pull + compila backend y web
#   paxfide.sh estado        qué corre, URLs y comprobación de la API y la web
#   paxfide.sh logs [api|web|tunel-api|tunel-web]
#   paxfide.sh parar         detiene backend, web y túneles (MongoDB y MinIO siguen)
#
# Rutas por defecto (se pueden cambiar con variables de entorno):
#   PAXFIDE_HOME=~/PaxFide   (contiene Donaciones/, PaxFide/ y deploy/)
#   deploy/backend.env       variables del backend (chmod 600; nunca en git)
#   deploy/docker-compose.yml  MongoDB (replica set rs0) y MinIO
set -euo pipefail

HOME_DIR=${PAXFIDE_HOME:-$HOME/PaxFide}
BACK=$HOME_DIR/Donaciones
WEB=$HOME_DIR/PaxFide
DEP=$HOME_DIR/deploy
ENVF=$DEP/backend.env
WEBENV=$WEB/.env.production.local
JAR=$BACK/app/target/app-1.0.0-SNAPSHOT.jar
SESSION=paxfide
LOG_API=$DEP/backend.log
LOG_WEB=$DEP/web.log

die() { echo "ERROR: $*" >&2; exit 1; }
info() { printf '\n== %s\n' "$*"; }

need() { command -v "$1" >/dev/null 2>&1 || die "falta '$1' (instálalo con pacman)"; }

check_env() {
  [ -f "$ENVF" ] || die "no existe $ENVF"
  if command grep -q 'CAMBIAR' "$ENVF"; then die "quedan valores CAMBIAR en $ENVF"; fi
}

# valor de una variable de backend.env sin ejecutar el fichero
envval() { command grep -E "^$1=" "$ENVF" | tail -1 | cut -d= -f2- || true; }

setenv() { # setenv NOMBRE valor: reemplaza o añade la línea en backend.env
  if command grep -qE "^$1=" "$ENVF"; then sed -i "s|^$1=.*|$1=$2|" "$ENVF"; else printf '%s=%s\n' "$1" "$2" >> "$ENVF"; fi
}

wait_http() { # wait_http URL segundos
  local i
  for ((i = 0; i < $2; i++)); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$1" || true)" = 200 ] && return 0
    sleep 1
  done
  return 1
}

infra() {
  info "MongoDB y MinIO"
  (cd "$DEP" && docker compose --env-file "$ENVF" up -d mongo minio)
  local i
  for ((i = 0; i < 60; i++)); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$(cd "$DEP" && docker compose ps -q mongo)" 2>/dev/null)" = healthy ] && return 0
    sleep 2
  done
  die "MongoDB no llega a healthy: cd $DEP && docker compose logs mongo"
}

tunnels() {
  info "Túneles de Cloudflare (URLs nuevas)"
  pkill -f "cloudflared tunnel --url" || true
  nohup cloudflared tunnel --url http://localhost:8080 > "$DEP/tunel-api.log" 2>&1 &
  nohup cloudflared tunnel --url http://localhost:3000 > "$DEP/tunel-web.log" 2>&1 &
  API_URL=$(tunnel_url "$DEP/tunel-api.log")
  WEB_URL=$(tunnel_url "$DEP/tunel-web.log")
  setenv TRACEABILITY_WEB_BASE_URL "$WEB_URL"
  setenv TRACEABILITY_CORS_ALLOWED_ORIGINS "$WEB_URL,http://localhost:3000"
  local google
  google=$(envval GOOGLE_OAUTH_CLIENT_IDS | cut -d, -f1)   # el primero es el cliente web
  echo "NEXT_PUBLIC_API_BASE_URL=$API_URL/api/v1" > "$WEBENV"
  if [ -n "$google" ]; then echo "NEXT_PUBLIC_GOOGLE_CLIENT_ID=$google" >> "$WEBENV"; fi
  printf 'API_URL=%s\nWEB_URL=%s\n' "$API_URL" "$WEB_URL" > "$DEP/urls.txt"
}

tunnel_url() {
  local i u
  for ((i = 0; i < 40; i++)); do
    u=$(command grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$1" | head -1 || true)
    [ -n "$u" ] && { echo "$u"; return 0; }
    sleep 1
  done
  die "cloudflared no dio URL; mira $1"
}

stop_apps() {
  tmux kill-session -t "$SESSION" 2>/dev/null || true
  pkill -f "java -jar $JAR" 2>/dev/null || true
  pkill -f "next start" 2>/dev/null || true
  sleep 2
}

# start_backend [perfiles extra en SPRING_PROFILES_ACTIVE]
start_backend() {
  [ -f "$JAR" ] || die "no está el jar; ejecuta: $0 compilar"
  : > "$LOG_API"
  local cmd="cd '$BACK' && set -a && . '$ENVF' && set +a"
  [ -n "${1:-}" ] && cmd="$cmd && export SPRING_PROFILES_ACTIVE='$1' TRACEABILITY_BOOTSTRAP_PLATFORM_ADMIN_ENABLED=false"
  cmd="$cmd && java -jar '$JAR' 2>&1 | tee '$LOG_API'"
  if tmux has-session -t "$SESSION" 2>/dev/null; then
    tmux new-window -t "$SESSION" -n backend "bash -c \"$cmd; exec bash\""
  else
    tmux new-session -d -s "$SESSION" -n backend "bash -c \"$cmd; exec bash\""
  fi
}

wait_backend() {
  info "Esperando al backend (log: $LOG_API)"
  local i
  for ((i = 0; i < 180; i++)); do
    command grep -q 'Started TraceabilityApplication' "$LOG_API" 2>/dev/null && return 0
    if command grep -qE 'APPLICATION FAILED TO START|Application run failed' "$LOG_API" 2>/dev/null; then
      tail -40 "$LOG_API"; die "el backend no arrancó (arriba, las últimas líneas del log)"
    fi
    sleep 1
  done
  die "el backend no arrancó en 3 minutos; mira $LOG_API"
}

build_web() {
  info "Compilando la web contra $(cut -d= -f2- < "$WEBENV" | head -1)"
  (cd "$WEB" && pnpm install --frozen-lockfile && pnpm build) || die "falló el build de la web"
}

start_web() {
  : > "$LOG_WEB"
  tmux new-window -t "$SESSION" -n web "bash -c \"cd '$WEB' && pnpm start 2>&1 | tee '$LOG_WEB'; exec bash\""
  wait_http http://localhost:3000 90 || die "la web no responde en :3000; mira $LOG_WEB"
}

summary() {
  # shellcheck disable=SC1090
  . "$DEP/urls.txt"
  info "Listo"
  echo "API: $API_URL/api/v1   ($(curl -s -o /dev/null -w '%{http_code}' "$API_URL/api/v1/public/campaigns"))"
  echo "WEB: $WEB_URL          ($(curl -s -o /dev/null -w '%{http_code}' "$WEB_URL"))"
  echo
  echo "Pendiente a mano cada vez que cambian las URLs:"
  echo "  - Google Cloud Console > cliente Web > Orígenes de JavaScript autorizados: $WEB_URL"
  echo "  - PaxFide_Mv/config/movil.json:"
  printf '    {\n      "PAXFIDE_API_BASE_URL": "%s/api/v1",\n      "PAXFIDE_PUBLIC_BASE_URL": "%s"\n    }\n' "$API_URL" "$WEB_URL"
  echo
  echo "Ver procesos: tmux attach -t $SESSION   (salir sin detener: Ctrl+B y luego D)"
}

cmd_compilar() {
  need mvn; need pnpm
  info "Backend: git pull y mvn install"
  (cd "$BACK" && git pull --ff-only && mvn -q install -DskipTests)
  info "Web: git pull"
  (cd "$WEB" && git pull --ff-only)
  echo "La web se compila en 'arrancar' (necesita la URL del túnel de la API)."
}

cmd_arrancar() {
  need docker; need tmux; need cloudflared; need java; need pnpm
  check_env
  infra
  stop_apps
  tunnels
  start_backend
  wait_backend
  build_web
  start_web
  summary
}

cmd_semilla() {
  need docker; need tmux; need java
  check_env
  local db
  db=$(envval SPRING_DATA_MONGODB_URI | sed -E 's|.*/([^/?]+)(\?.*)?$|\1|')
  [ -n "$db" ] || die "no pude leer el nombre de la base en SPRING_DATA_MONGODB_URI"
  [ -f "$DEP/urls.txt" ] && command grep -q '^API_URL=' "$DEP/urls.txt" || die "primero ejecuta: $0 arrancar (hacen falta los túneles)"
  echo "Esto BORRA la base '$db' entera (cuentas, organizaciones, convocatorias, donaciones) y carga la demo."
  read -r -p "Escribe BORRAR para seguir: " ok
  [ "$ok" = BORRAR ] || die "cancelado"

  # La semilla necesita pagos simulados (webhook firmado) y su contraseña de ejemplo.
  [ -n "$(envval TRACEABILITY_DEMO_WEBHOOK_SECRET)" ] || setenv TRACEABILITY_DEMO_WEBHOOK_SECRET "$(openssl rand -hex 32)"
  [ -n "$(envval TRACEABILITY_DEMO_SIMULATED_PAYMENTS)" ] || setenv TRACEABILITY_DEMO_SIMULATED_PAYMENTS true
  [ -n "$(envval TRACEABILITY_DEMO_SEED_PASSWORD)" ] || setenv TRACEABILITY_DEMO_SEED_PASSWORD "$(openssl rand -hex 6)"
  setenv TRACEABILITY_DEMO_SEED_OUTPUT_FILE "$DEP/credenciales-pruebas.md"
  setenv TRACEABILITY_BOOTSTRAP_PLATFORM_ADMIN_ENABLED false

  infra
  stop_apps
  info "Vaciando la base '$db'"
  (cd "$DEP" && docker compose exec -T mongo mongosh --quiet --eval "db.getSiblingDB('$db').dropDatabase()")

  start_backend demo-seed
  info "Cargando la semilla (puede tardar un par de minutos)"
  local i
  for ((i = 0; i < 300; i++)); do
    command grep -q 'Semilla de la demo completa' "$LOG_API" 2>/dev/null && break
    if command grep -qE 'APPLICATION FAILED TO START|Application run failed|no está vacía' "$LOG_API" 2>/dev/null; then
      tail -40 "$LOG_API"; die "la semilla falló (arriba, el log)"
    fi
    sleep 1
  done
  command grep -q 'Semilla de la demo completa' "$LOG_API" || die "la semilla no terminó en 5 minutos; mira $LOG_API"

  info "Semilla cargada. Reinicio normal (sin el perfil demo-seed)"
  stop_apps
  start_backend
  wait_backend
  start_web
  summary
  echo
  echo "Cuentas, convocatorias y trackingCode de prueba: $DEP/credenciales-pruebas.md"
  echo "Contraseña común de esas cuentas: $(envval TRACEABILITY_DEMO_SEED_PASSWORD)"
}

cmd_estado() {
  info "Procesos"
  pgrep -af "cloudflared tunnel --url" || echo "(sin túneles)"
  pgrep -af "java -jar $JAR" || echo "(backend parado)"
  pgrep -af "next start" || echo "(web parada)"
  (cd "$DEP" && docker compose ps)
  if [ -f "$DEP/urls.txt" ]; then summary; fi
}

cmd_logs() {
  case "${1:-api}" in
    api) tail -f "$LOG_API" ;;
    web) tail -f "$LOG_WEB" ;;
    tunel-api) tail -f "$DEP/tunel-api.log" ;;
    tunel-web) tail -f "$DEP/tunel-web.log" ;;
    *) die "logs api|web|tunel-api|tunel-web" ;;
  esac
}

cmd_parar() {
  stop_apps
  pkill -f "cloudflared tunnel --url" || true
  echo "Backend, web y túneles detenidos. MongoDB y MinIO siguen (cd $DEP && docker compose stop para pararlos)."
}

case "${1:-}" in
  arrancar) cmd_arrancar ;;
  semilla) cmd_semilla ;;
  compilar) cmd_compilar ;;
  estado) cmd_estado ;;
  logs) cmd_logs "${2:-}" ;;
  parar) cmd_parar ;;
  *) sed -n '2,15p' "$0"; exit 1 ;;
esac
