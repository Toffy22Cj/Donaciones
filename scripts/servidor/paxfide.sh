#!/usr/bin/env bash
# Servidor de pruebas de PaxFide en un solo PC: MongoDB y MinIO en Docker, backend (jar) y web (Next), publicados en
# internet con Tailscale Funnel (URLs fijas) o, con PAXFIDE_EXPOSE=cloudflare en backend.env, con dos Cloudflare
# quick tunnels (URLs nuevas en cada arranque). Ningún valor real va en este script: todo sale de backend.env.
#
#   Tailscale Funnel:  web  https://<pc>.<red>.ts.net        (puerto 443  -> localhost:3000)
#                      API  https://<pc>.<red>.ts.net:8443   (puerto 8443 -> localhost:8080)
#
#   paxfide.sh arrancar      publica las URLs, las escribe en backend.env y en la web, arranca todo en tmux y
#                            recompila la web solo si su URL de la API cambió
#   paxfide.sh semilla       BORRA la base y carga el escenario de la demo (cuentas, convocatorias, trackingCode)
#   paxfide.sh actualizar    trae los cambios del equipo (git pull de los 3 repos) y recompila y reinicia solo lo
#                            que cambió, sin tocar los túneles (las URLs siguen igual). --forzar recompila todo
#   paxfide.sh compilar      git pull + compila backend y web
#   paxfide.sh reparar-anclaje  tras "nonce too low" en el log: devuelve a la cola los lotes atascados sin enviar y
#                            reinicia el backend, que vuelve a tomar el nonce de la cartera en la red
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
MOVIL=$HOME_DIR/PaxFide_Mv
DEP=$HOME_DIR/deploy
ENVF=$DEP/backend.env
WEBENV=$WEB/.env.production.local
JAR=$BACK/app/target/app-1.0.0-SNAPSHOT.jar
RUN_JAR=$DEP/backend-en-marcha.jar   # copia del jar: mvn puede recompilar sin romper el backend que corre
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

# docker compose siempre con backend.env (sin él, avisa de MINIO_ROOT_* vacías)
dc() { docker compose --project-directory "$DEP" -f "$DEP/docker-compose.yml" --env-file "$ENVF" "$@"; }

infra() {
  info "MongoDB y MinIO"
  dc up -d mongo minio
  local i
  for ((i = 0; i < 60; i++)); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$(dc ps -q mongo)" 2>/dev/null)" = healthy ] && { ensure_bucket; return 0; }
    sleep 2
  done
  die "MongoDB no llega a healthy: cd $DEP && docker compose logs mongo"
}

# El backend no crea el bucket de imágenes (MEDIA_S3_BUCKET): sin él, subir una foto da 503 MediaStorageUnavailable.
# Lo crea con el cliente mc que trae la imagen de MinIO; si ya existe, no hace nada.
ensure_bucket() {
  local bucket i
  bucket=$(envval MEDIA_S3_BUCKET)
  [ -n "$bucket" ] || die "falta MEDIA_S3_BUCKET en $ENVF"
  for ((i = 0; i < 30; i++)); do
    curl -s -o /dev/null http://127.0.0.1:9000/minio/health/live && break
    sleep 1
  done
  if dc exec -T -e MC_CONFIG_DIR=/tmp/.mc minio sh -c '
      MC=$(command -v mc || echo /opt/bitnami/minio-client/bin/mc)
      [ -x "$MC" ] || exit 3
      "$MC" alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null &&
      "$MC" mb --ignore-existing "local/$1" >/dev/null' sh "$bucket"; then
    echo "  bucket '$bucket' listo"
  else
    die "no pude crear el bucket '$bucket' en MinIO. Créalo a mano en http://127.0.0.1:9001 (usuario MINIO_ROOT_USER) y repite"
  fi
}

# Cómo se publica: tailscale (por defecto) o cloudflare
expose_mode() {
  local m
  m=$(envval PAXFIDE_EXPOSE)
  case "${m:-tailscale}" in
    tailscale|cloudflare) echo "${m:-tailscale}" ;;
    *) die "PAXFIDE_EXPOSE debe ser tailscale o cloudflare" ;;
  esac
}

# Publica la web y la API y deja API_URL y WEB_URL; escribe backend.env, la web y urls.txt
expose() {
  if [ "$(expose_mode)" = cloudflare ]; then tunnels; else funnel; fi
  setenv TRACEABILITY_WEB_BASE_URL "$WEB_URL"
  setenv TRACEABILITY_CORS_ALLOWED_ORIGINS "$WEB_URL,http://localhost:3000"
  local google new
  google=$(envval GOOGLE_OAUTH_CLIENT_IDS | cut -d, -f1)   # el primero es el cliente web
  new="NEXT_PUBLIC_API_BASE_URL=$API_URL/api/v1"
  if [ -n "$google" ]; then new="$new"$'\n'"NEXT_PUBLIC_GOOGLE_CLIENT_ID=$google"; fi
  if [ "$(cat "$WEBENV" 2>/dev/null)" != "$new" ]; then
    printf '%s\n' "$new" > "$WEBENV"
    WEB_ENV_CHANGED=1
  fi
  printf 'API_URL=%s\nWEB_URL=%s\n' "$API_URL" "$WEB_URL" > "$DEP/urls.txt"
}

# Tailscale Funnel: URLs fijas del PC en la red de Tailscale, con HTTPS de Tailscale
funnel() {
  need tailscale
  info "Tailscale Funnel (URLs fijas)"
  pkill -f "cloudflared tunnel --url" 2>/dev/null || true
  local dns
  dns=$(tailscale status --json 2>/dev/null \
        | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))' 2>/dev/null || true)
  [ -n "$dns" ] || die "Tailscale no está conectado en este PC: sudo tailscale up (y repite)"
  if ! tailscale funnel --bg --https=443 http://127.0.0.1:3000 >/dev/null \
     || ! tailscale funnel --bg --https=8443 http://127.0.0.1:8080 >/dev/null; then
    echo "No pude activar Funnel. Revisa, una sola vez:" >&2
    echo "  1. sudo tailscale set --operator=\$USER   (para usar funnel sin sudo)" >&2
    echo "  2. En login.tailscale.com/admin: DNS > HTTPS Certificates activado, y Funnel permitido en la" >&2
    echo "     política (el comando 'tailscale funnel 3000' te da el enlace para activarlo)" >&2
    die "Funnel no está disponible"
  fi
  WEB_URL="https://$dns"
  API_URL="https://$dns:8443"
}

tunnels() {
  info "Túneles de Cloudflare (URLs nuevas)"
  pkill -f "cloudflared tunnel --url" || true
  nohup cloudflared tunnel --url http://localhost:8080 > "$DEP/tunel-api.log" 2>&1 &
  nohup cloudflared tunnel --url http://localhost:3000 > "$DEP/tunel-web.log" 2>&1 &
  API_URL=$(tunnel_url "$DEP/tunel-api.log")
  WEB_URL=$(tunnel_url "$DEP/tunel-web.log")
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
  stop_backend
  stop_web
}

stop_backend() {
  tmux kill-window -t "$SESSION:backend" 2>/dev/null || true
  pkill -f "java -jar $RUN_JAR" 2>/dev/null || true
  pkill -f "java -jar $JAR" 2>/dev/null || true
  sleep 2
}

stop_web() {
  tmux kill-window -t "$SESSION:web" 2>/dev/null || true
  pkill -f "next start" 2>/dev/null || true
  sleep 1
}

# start_backend [perfiles extra en SPRING_PROFILES_ACTIVE]
start_backend() {
  [ -f "$JAR" ] || die "no está el jar; ejecuta: $0 compilar"
  check_new_vars
  cp "$JAR" "$RUN_JAR"
  : > "$LOG_API"
  local cmd="cd '$BACK' && set -a && . '$ENVF' && set +a"
  [ -n "${1:-}" ] && cmd="$cmd && export SPRING_PROFILES_ACTIVE='$1' TRACEABILITY_BOOTSTRAP_PLATFORM_ADMIN_ENABLED=false"
  cmd="$cmd && java -jar '$RUN_JAR' 2>&1 | tee '$LOG_API'"
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
  if ! tmux has-session -t "$SESSION" 2>/dev/null; then tmux new-session -d -s "$SESSION" -n shell; fi
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
  if [ "$(expose_mode)" = tailscale ]; then
    echo "Estas URLs son fijas: configúralas una sola vez (no cambian al reiniciar):"
  else
    echo "Pendiente a mano cada vez que cambian las URLs:"
  fi
  echo "  - Google Cloud Console > cliente Web > Orígenes de JavaScript autorizados: $WEB_URL"
  echo "  - PaxFide_Mv/config/movil.json:"
  printf '    {\n      "PAXFIDE_API_BASE_URL": "%s/api/v1",\n      "PAXFIDE_PUBLIC_BASE_URL": "%s"\n    }\n' "$API_URL" "$WEB_URL"
  echo
  echo "Ver procesos: tmux attach -t $SESSION   (salir sin detener: Ctrl+B y luego D)"
}

# Variables obligatorias (${VAR} sin valor por defecto en application.yml) que faltan en backend.env. Si el equipo
# añade una nueva, el backend no arrancaría: se avisa antes y no se reinicia.
check_new_vars() {
  local v missing=""
  for v in $(command grep -oE '\$\{[A-Z_0-9]+\}' "$BACK/app/src/main/resources/application.yml" | tr -d '${}' | sort -u); do
    [ -n "$(envval "$v")" ] || missing="$missing $v"
  done
  [ -z "$missing" ] && return 0
  echo "Faltan en $ENVF variables obligatorias nuevas:$missing" >&2
  echo "Mira para qué son en Donaciones/Documentos/despliegue-backend-2026-10.md, añádelas y repite." >&2
  die "el backend no se reinicia sin ellas (el que corre sigue igual)"
}

# git pull de un repo; imprime "si" si cambió. Se niega si hay cambios locales sin guardar.
pull_repo() { # pull_repo dir nombre
  local dir=$1 name=$2 before after branch
  [ -d "$dir/.git" ] || { echo "  $name: no está en $dir (lo salto)" >&2; echo no; return 0; }
  if [ -n "$(git -C "$dir" status --porcelain --untracked-files=no)" ]; then
    git -C "$dir" status --short --untracked-files=no >&2
    die "$name tiene cambios locales sin guardar (arriba). Guárdalos (git stash) o descártalos y repite."
  fi
  branch=$(git -C "$dir" branch --show-current)
  before=$(git -C "$dir" rev-parse HEAD)
  git -C "$dir" pull --ff-only -q origin "$branch" >&2 || die "$name: git pull falló (¿la rama $branch divergió?)"
  after=$(git -C "$dir" rev-parse HEAD)
  if [ "$before" = "$after" ]; then
    echo "  $name ($branch): sin cambios" >&2; echo no
  else
    echo "  $name ($branch): ${before:0:7} -> ${after:0:7}" >&2
    git -C "$dir" log --oneline --no-decorate "$before..$after" | head -15 | sed 's/^/      /' >&2
    echo si
  fi
}

cmd_actualizar() {
  need git; need mvn; need pnpm; need tmux
  check_env
  local force=0 back web movil
  [ "${1:-}" = --forzar ] && force=1
  info "Trayendo los cambios del equipo"
  back=$(pull_repo "$BACK" Donaciones)
  web=$(pull_repo "$WEB" PaxFide)
  movil=$(pull_repo "$MOVIL" PaxFide_Mv)
  [ "$force" = 1 ] && { back=si; web=si; }
  if [ "$back" = no ] && [ "$web" = no ]; then
    echo; echo "Backend y web ya estaban al día: no reinicio nada."
    [ "$movil" = si ] && echo "PaxFide_Mv cambió: recompila la app en tu equipo con Flutter (git pull allí)."
    return 0
  fi
  [ -f "$DEP/urls.txt" ] || die "no hay túneles; ejecuta: $0 arrancar"
  infra

  if [ "$back" = si ]; then
    info "Backend: compilando (el que corre sigue atendiendo mientras tanto)"
    (cd "$BACK" && mvn -q install -DskipTests) > "$DEP/backend-build.log" 2>&1 \
      || { tail -30 "$DEP/backend-build.log"; die "falló la compilación; el backend anterior sigue en marcha (log: $DEP/backend-build.log)"; }
    check_new_vars
    info "Backend: reiniciando"
    stop_backend
    start_backend
    wait_backend
  fi

  if [ "$web" = si ]; then
    info "Web: compilando (la web se para durante el build)"
    stop_web
    build_web
    start_web
  fi

  summary
  [ "$movil" = si ] && echo "PaxFide_Mv cambió: recompila la app en tu equipo con Flutter (git pull allí)."
  return 0
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
  need docker; need tmux; need java; need pnpm
  check_env
  [ "$(expose_mode)" = cloudflare ] && need cloudflared
  infra
  stop_apps
  WEB_ENV_CHANGED=0
  expose
  start_backend
  wait_backend
  if [ "$WEB_ENV_CHANGED" = 1 ] || [ ! -f "$WEB/.next/BUILD_ID" ] || [ "${1:-}" = --forzar ]; then
    build_web
  else
    info "Web: la URL de la API no cambió, uso el build que hay (--forzar para recompilar)"
  fi
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
  dc exec -T mongo mongosh --quiet --eval "db.getSiblingDB('$db').dropDatabase()"

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

# "nonce too low": el contador de nonce de la base quedó por detrás de la cartera (por ejemplo, tras vaciar la base).
# El backend lo sincroniza con la red al arrancar, pero un lote que ya tomó un nonce viejo se reintenta con él para
# siempre. Aquí esos lotes (SUBMITTING o STUCK sin transactionHash: no llegaron a la red) vuelven a PENDING sin nonce,
# se borra el contador y el backend, al reiniciar, lo vuelve a tomar de la red.
cmd_reparar_anclaje() {
  need docker; need tmux; need java
  check_env
  local db
  db=$(envval SPRING_DATA_MONGODB_URI | sed -E 's|.*/([^/?]+)(\?.*)?$|\1|')
  # sin la sincronización al arrancar, borrar el contador lo dejaría en 0 otra vez
  command grep -q NonceCounterChainSync "$JAR" \
    || die "el backend compilado no sincroniza el nonce con la red; primero: $0 actualizar (con develop al día)"
  infra
  stop_backend
  info "Liberando los lotes atascados de la base '$db'"
  dc exec -T mongo mongosh --quiet --eval "
    const d = db.getSiblingDB('$db');
    const r = d.merkle_batches.updateMany(
      { status: { \$in: ['SUBMITTING', 'STUCK'] }, transactionHash: null },
      { \$set: { status: 'PENDING' }, \$unset: { nonceUsed: '', submittedAt: '' } });
    const c = d.web3_nonce_counter.deleteMany({});
    print('lotes devueltos a la cola: ' + r.modifiedCount + ', contadores borrados: ' + c.deletedCount);"
  start_backend
  wait_backend
  command grep -m1 'web3.nonce' "$LOG_API" || true
  echo "Listo. Comprueba en unos minutos: $0 logs api (debe salir 'successfully submitted', sin 'nonce too low')."
}

cmd_estado() {
  info "Procesos"
  if [ "$(expose_mode)" = tailscale ]; then
    tailscale funnel status 2>/dev/null || echo "(Funnel inactivo)"
  else
    pgrep -af "cloudflared tunnel --url" || echo "(sin túneles)"
  fi
  pgrep -af "java -jar $RUN_JAR" || echo "(backend parado)"
  pgrep -af "next start" || echo "(web parada)"
  dc ps
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
  if [ "$(expose_mode)" = tailscale ] && command -v tailscale >/dev/null; then
    tailscale funnel reset >/dev/null 2>&1 || true
  fi
  echo "Backend, web y túneles detenidos. MongoDB y MinIO siguen (cd $DEP && docker compose stop para pararlos)."
}

case "${1:-}" in
  arrancar) cmd_arrancar "${2:-}" ;;
  semilla) cmd_semilla ;;
  actualizar) cmd_actualizar "${2:-}" ;;
  compilar) cmd_compilar ;;
  reparar-anclaje) cmd_reparar_anclaje ;;
  estado) cmd_estado ;;
  logs) cmd_logs "${2:-}" ;;
  parar) cmd_parar ;;
  *) sed -n '2,26p' "$0"; exit 1 ;;
esac
