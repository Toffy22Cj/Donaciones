#!/usr/bin/env bash
# Servidor de pruebas de PaxFide en un solo PC: MongoDB y MinIO en Docker, backend (jar), web (Next) y dos
# Cloudflare quick tunnels (API y web). Ningún valor real va en este script: todo sale de backend.env.
#
#   paxfide.sh arrancar      túneles nuevos, URLs a backend.env y a la web, recompila la web y arranca todo en tmux
#   paxfide.sh semilla       BORRA la base y carga el escenario de la demo (cuentas, convocatorias, trackingCode)
#   paxfide.sh actualizar    trae los cambios del equipo (git pull de los 3 repos) y recompila y reinicia solo lo
#                            que cambió, sin tocar los túneles (las URLs siguen igual). --forzar recompila todo
#   paxfide.sh compilar      git pull + compila backend y web
#   paxfide.sh reparar-anclaje  tras "nonce too low" en el log: devuelve a la cola los lotes atascados sin enviar y
#                            reinicia el backend, que vuelve a tomar el nonce de la cartera en la red
#   paxfide.sh estado        qué corre, URLs, salud interna del backend (con MongoDB) y comprobación de la API y la web
#   paxfide.sh logs [api|web|tunel-api|tunel-web]
#   paxfide.sh parar         detiene backend, web y túneles (MongoDB y MinIO siguen)
#   paxfide.sh respaldo      copia cifrada de MongoDB (mongodump --oplog) y del bucket de MinIO, con manifiesto
#                            SHA-256, retención 7 diarias + 4 semanales y copia al destino externo (T-04)
#   paxfide.sh restaurar-prueba [carpeta]  restaura un respaldo (por defecto, el último) en un MongoDB aislado y
#                            desechable, arranca el backend contra él y verifica la integridad de todos los lotes
#   paxfide.sh instalar-temporizador  instala el temporizador de systemd (usuario) que ejecuta 'respaldo' a diario
#
# Respaldo (runbook-respaldo-restauracion.md), en backend.env o en el entorno:
#   PAXFIDE_BACKUP_AGE_RECIPIENT  clave pública de age (age1…) con la que se cifra; la privada NO vive en este PC
#   PAXFIDE_BACKUP_GPG_RECIPIENT  alternativa: destinatario de gpg (si no hay clave de age)
#   PAXFIDE_BACKUP_DEST           destino externo: ruta local montada (disco o carpeta sincronizada), usuario@host:ruta
#                                 (rsync por ssh) o rclone:remoto:ruta. 'solo-local' lo omite a sabiendas (avisa)
#   PAXFIDE_BACKUP_AGE_IDENTITY   solo para restaurar-prueba: fichero con la clave privada de age (p. ej. en un USB)
#   PAXFIDE_BACKUP_DIR            carpeta local de respaldos (por defecto deploy/respaldos)
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
  echo "Pendiente a mano cada vez que cambian las URLs:"
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
  backup_before_drop
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

# ───────────────────────────── T-04: respaldo y restauración de prueba ─────────────────────────────
# Plan de evolución del producto §2.1 (aprobado el 2026-11-14). MongoDB es el Event Store (única fuente de verdad) y
# MinIO guarda las fotos y evidencias cuyo sha256 está en los eventos: se copian los dos, cifrados, con manifiesto.

# cfg NOMBRE [defecto]: el entorno manda; si no, backend.env; si no, el valor por defecto
cfg() {
  local v="${!1:-}"
  [ -n "$v" ] || v=$(envval "$1")
  printf '%s' "${v:-${2:-}}"
}

backup_dir() { cfg PAXFIDE_BACKUP_DIR "$DEP/respaldos"; }

mongo_db_name() { envval SPRING_DATA_MONGODB_URI | sed -E 's|.*/([^/?]+)(\?.*)?$|\1|'; }

# URI para las herramientas dentro del contenedor de MongoDB: mismas credenciales que el backend (si las hay),
# pero contra 127.0.0.1:27017 del propio contenedor, sin base y con directConnection
mongo_local_uri() {
  local uri cred="" auth
  uri=$(envval SPRING_DATA_MONGODB_URI)
  if [[ "$uri" =~ ^mongodb://([^@/]+)@ ]]; then cred="${BASH_REMATCH[1]}@"; fi
  printf 'mongodb://%s127.0.0.1:27017/?directConnection=true' "$cred"
  if [ -n "$cred" ]; then
    auth=$(printf '%s' "$uri" | command grep -oE 'authSource=[^&]+' | cut -d= -f2 || true)
    printf '&authSource=%s' "${auth:-admin}"
  fi
}

encrypt_file() { # encrypt_file entrada salida
  local age_r gpg_r
  age_r=$(cfg PAXFIDE_BACKUP_AGE_RECIPIENT)
  gpg_r=$(cfg PAXFIDE_BACKUP_GPG_RECIPIENT)
  if [ -n "$age_r" ]; then
    age -r "$age_r" -o "$2" "$1"
  elif [ -n "$gpg_r" ]; then
    gpg --batch --yes --trust-model always -r "$gpg_r" -o "$2" --encrypt "$1"
  else
    return 1
  fi
}

decrypt_file() { # decrypt_file entrada salida
  case "$1" in
    *.age)
      local id
      id=$(cfg PAXFIDE_BACKUP_AGE_IDENTITY)
      [ -n "$id" ] && [ -f "$id" ] || die "falta PAXFIDE_BACKUP_AGE_IDENTITY (fichero con la clave privada de age)"
      age -d -i "$id" -o "$2" "$1" ;;
    *.gpg) gpg --batch --yes -o "$2" --decrypt "$1" ;;
    *) die "formato de cifrado desconocido: $1" ;;
  esac
}

# Copia al destino externo de diarios/ y semanales/ (la retención se refleja: lo que se borró aquí se borra allí)
copy_external() { # copy_external carpeta-local
  local dest
  dest=$(cfg PAXFIDE_BACKUP_DEST)
  case "$dest" in
    "") echo "falta PAXFIDE_BACKUP_DEST (destino externo); para omitirlo a sabiendas: PAXFIDE_BACKUP_DEST=solo-local" >&2
        return 1 ;;
    solo-local) echo "AVISO: PAXFIDE_BACKUP_DEST=solo-local: el respaldo NO salió de este equipo" >&2; return 0 ;;
    rclone:*) need rclone; rclone sync "$1" "${dest#rclone:}" --include '/diarios/**' --include '/semanales/**' ;;
    /*) mkdir -p "$dest" || return 1
        local sub
        for sub in diarios semanales; do
          mkdir -p "$dest/$sub"
          # borrar en el destino lo que la retención borró aquí, y copiar lo nuevo
          (cd "$dest/$sub" && for d in respaldo-*; do [ -e "$d" ] && [ ! -e "$1/$sub/$d" ] && rm -rf -- "$d"; done; true)
          cp -a "$1/$sub/." "$dest/$sub/" || return 1
        done
        # comprobar en el destino lo que se acaba de copiar
        (cd "$dest/diarios/$(basename "$LAST_BACKUP")" && sha256sum --quiet -c manifiesto.sha256) ;;
    *:*) need rsync; rsync -a --delete --include '/diarios/***' --include '/semanales/***' --exclude '*' "$1/" "$dest/" ;;
    *) echo "PAXFIDE_BACKUP_DEST no reconocido: $dest" >&2; return 1 ;;
  esac
}

prune() { # prune carpeta cuántos: deja los N respaldos más recientes
  local n=0 d
  for d in $(ls -1d "$1"/respaldo-* 2>/dev/null | sort -r); do
    n=$((n + 1))
    [ "$n" -le "$2" ] || rm -rf -- "$d"
  done
}

# Respaldo completo. Devuelve 0 solo si el respaldo local está cifrado, verificado y copiado al destino externo
# (o PAXFIDE_BACKUP_DEST=solo-local). Deja la carpeta en LAST_BACKUP.
do_backup() {
  need docker; need sha256sum
  local bk stamp work db bucket events objects f uri
  if [ -z "$(cfg PAXFIDE_BACKUP_AGE_RECIPIENT)" ] && [ -z "$(cfg PAXFIDE_BACKUP_GPG_RECIPIENT)" ]; then
    echo "falta PAXFIDE_BACKUP_AGE_RECIPIENT (o PAXFIDE_BACKUP_GPG_RECIPIENT): sin cifrado no hay respaldo" >&2
    return 1
  fi
  [ -n "$(cfg PAXFIDE_BACKUP_AGE_RECIPIENT)" ] && need age
  db=$(mongo_db_name); bucket=$(envval MEDIA_S3_BUCKET)
  [ -n "$db" ] && [ -n "$bucket" ] || { echo "faltan SPRING_DATA_MONGODB_URI o MEDIA_S3_BUCKET" >&2; return 1; }
  [ -n "$(dc ps -q mongo 2>/dev/null)" ] && [ -n "$(dc ps -q minio 2>/dev/null)" ] \
    || { echo "MongoDB o MinIO no están en marcha" >&2; return 1; }

  bk=$(backup_dir); stamp=$(date -u +%Y-%m-%dT%H-%M-%SZ)
  mkdir -p "$bk/diarios" "$bk/semanales" "$bk/registros" && chmod 700 "$bk"
  work="$bk/.en-curso-$stamp"
  rm -rf "$work"; mkdir -m 700 "$work"
  uri=$(mongo_local_uri)

  info "Respaldo $stamp: MongoDB (mongodump --oplog, base completa)"
  dc exec -T mongo mongodump --quiet --uri "$uri" --archive --gzip --oplog > "$work/mongo.archive.gz" \
    || { echo "mongodump falló" >&2; rm -rf "$work"; return 1; }
  events=$(dc exec -T mongo mongosh --quiet "$uri" --eval "db.getSiblingDB('$db').event_store.countDocuments()" | tr -d '\r') \
    || { echo "no pude contar los eventos" >&2; rm -rf "$work"; return 1; }

  info "Respaldo $stamp: MinIO (bucket '$bucket')"
  dc exec -T -e MC_CONFIG_DIR=/tmp/.mc minio sh -c '
      MC=$(command -v mc || echo /opt/bitnami/minio-client/bin/mc)
      [ -x "$MC" ] || exit 3
      rm -rf /tmp/pf-respaldo && mkdir -p /tmp/pf-respaldo &&
      "$MC" alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null &&
      "$MC" mirror --quiet "local/$1" /tmp/pf-respaldo >/dev/null &&
      tar -C /tmp/pf-respaldo -cf - .; rc=$?; rm -rf /tmp/pf-respaldo; exit $rc' sh "$bucket" > "$work/minio.tar" \
    || { echo "la copia de MinIO falló" >&2; rm -rf "$work"; return 1; }
  objects=$(tar -tf "$work/minio.tar" | command grep -cv '/$' || true)

  info "Respaldo $stamp: manifiesto y cifrado"
  {
    echo "fecha=$stamp"
    echo "base=$db"
    echo "eventos=$events"
    echo "bucket=$bucket"
    echo "objetos=$objects"
    for f in mongo.archive.gz minio.tar; do
      echo "contenido.$f.bytes=$(stat -c %s "$work/$f")"
      echo "contenido.$f.sha256=$(sha256sum "$work/$f" | cut -d' ' -f1)"
    done
  } > "$work/manifiesto.txt"
  local ext=age
  [ -n "$(cfg PAXFIDE_BACKUP_AGE_RECIPIENT)" ] || ext=gpg
  for f in mongo.archive.gz minio.tar; do
    encrypt_file "$work/$f" "$work/$f.$ext" || { echo "el cifrado falló" >&2; rm -rf "$work"; return 1; }
    shred -u "$work/$f" 2>/dev/null || rm -f "$work/$f"
    echo "cifrado.$f.$ext.bytes=$(stat -c %s "$work/$f.$ext")" >> "$work/manifiesto.txt"
  done
  (cd "$work" && sha256sum "mongo.archive.gz.$ext" "minio.tar.$ext" manifiesto.txt > manifiesto.sha256)
  (cd "$work" && sha256sum --quiet -c manifiesto.sha256) || { echo "el manifiesto no cuadra" >&2; rm -rf "$work"; return 1; }

  mv "$work" "$bk/diarios/respaldo-$stamp"
  LAST_BACKUP="$bk/diarios/respaldo-$stamp"
  # semanal: el primero de cada semana ISO
  local week
  week=$(date -u +%G-W%V)
  if [ ! -e "$bk/semanales/.semana-$week" ]; then
    cp -a "$LAST_BACKUP" "$bk/semanales/" && : > "$bk/semanales/.semana-$week"
  fi
  prune "$bk/diarios" 7
  prune "$bk/semanales" 4
  { ls -1 "$bk/semanales"/.semana-* 2>/dev/null || true; } | sort -r | tail -n +9 | xargs -r rm -f

  info "Respaldo $stamp: copia al destino externo"
  copy_external "$bk" || { echo "la copia externa falló; el respaldo local está en $LAST_BACKUP" >&2; return 1; }
  date -u +%Y-%m-%dT%H:%M:%SZ > "$bk/ultimo-respaldo-ok"
  echo "Respaldo listo: $LAST_BACKUP ($events eventos, $objects objetos)"
}

cmd_respaldo() {
  check_env
  do_backup || die "el respaldo NO se completó (arriba, el motivo)"
}

# Antes de cualquier dropDatabase: sin un respaldo completo y verificado, no se borra nada
backup_before_drop() {
  info "Respaldo obligatorio antes de vaciar la base"
  do_backup || die "el respaldo previo falló: la base NO se ha borrado"
}

# Destruye el backend, el MongoDB y los ficheros descifrados de la restauración de prueba. Nunca falla.
cleanup_restore() {
  set +e
  if [ -n "${RESTORE_API:-}" ]; then kill "$RESTORE_API" 2>/dev/null; wait "$RESTORE_API" 2>/dev/null; fi
  [ -n "${RESTORE_NAME:-}" ] && docker rm -f "$RESTORE_NAME" >/dev/null 2>&1
  if [ -n "${RESTORE_WORK:-}" ] && [ -d "$RESTORE_WORK" ]; then
    find "$RESTORE_WORK" -type f -exec shred -u {} + 2>/dev/null; rm -rf "$RESTORE_WORK"
  fi
  RESTORE_API=""; RESTORE_NAME=""; RESTORE_WORK=""
  return 0
}

cmd_restaurar_prueba() {
  need docker; need java; need sha256sum
  check_env
  local bk src port db events want ok=1 image report
  # globales a propósito: la limpieza corre en el trap EXIT, cuando las variables locales ya no existen
  RESTORE_WORK=""; RESTORE_NAME=""; RESTORE_API=""
  bk=$(backup_dir)
  src=${1:-$(ls -1d "$bk"/diarios/respaldo-* 2>/dev/null | sort | tail -1)}
  [ -n "$src" ] && [ -d "$src" ] || die "no hay respaldo que restaurar (en $bk/diarios)"
  [ -f "$JAR" ] || die "no está el jar; ejecuta: $0 compilar"
  info "Restauración de prueba de $src"

  (cd "$src" && sha256sum --quiet -c manifiesto.sha256) || die "respaldo corrupto: el SHA-256 no coincide con el manifiesto"
  RESTORE_WORK=$(mktemp -d "${TMPDIR:-/tmp}/paxfide-restauracion-XXXXXX"); chmod 700 "$RESTORE_WORK"
  RESTORE_NAME="paxfide-restauracion-$$"
  trap cleanup_restore EXIT
  local work=$RESTORE_WORK name=$RESTORE_NAME

  local f enc
  for f in mongo.archive.gz minio.tar; do
    enc=$(ls "$src/$f".* 2>/dev/null | command grep -E '\.(age|gpg)$' | head -1)
    [ -n "$enc" ] || die "falta $f cifrado en $src"
    decrypt_file "$enc" "$work/$f" || die "no pude descifrar $enc"
    [ "$(sha256sum "$work/$f" | cut -d' ' -f1)" = "$(command grep "^contenido.$f.sha256=" "$src/manifiesto.txt" | cut -d= -f2)" ] \
      || die "respaldo corrupto: $f descifrado no coincide con el manifiesto"
  done
  want=$(command grep '^objetos=' "$src/manifiesto.txt" | cut -d= -f2)
  [ "$(tar -tf "$work/minio.tar" | command grep -cv '/$' || true)" = "$want" ] || die "la copia de MinIO no tiene los $want objetos del manifiesto"

  info "MongoDB aislado y desechable"
  image=$(docker inspect -f '{{.Config.Image}}' "$(dc ps -q mongo 2>/dev/null)" 2>/dev/null || true)
  docker run -d --name "$name" -p 127.0.0.1::27017 "${image:-mongo:6.0}" --replSet rs0 --bind_ip_all >/dev/null
  port=$(docker port "$name" 27017/tcp | head -1 | sed 's/.*://')
  local i
  for ((i = 0; i < 60; i++)); do
    docker exec "$name" mongosh --quiet --eval \
      "try { rs.status().ok } catch (e) { rs.initiate({_id: 'rs0', members: [{_id: 0, host: 'localhost:27017'}]}).ok }" 2>/dev/null \
      | command grep -q 1 && docker exec "$name" mongosh --quiet --eval 'db.hello().isWritablePrimary' 2>/dev/null | command grep -q true && break
    sleep 1
  done
  info "Restaurando (mongorestore --oplogReplay)"
  docker exec -i "$name" mongorestore --quiet --archive --gzip --oplogReplay < "$work/mongo.archive.gz" || die "mongorestore falló"

  db=$(command grep '^base=' "$src/manifiesto.txt" | cut -d= -f2)
  events=$(docker exec "$name" mongosh --quiet --eval "db.getSiblingDB('$db').event_store.countDocuments()" | tr -d '\r')
  want=$(command grep '^eventos=' "$src/manifiesto.txt" | cut -d= -f2)
  echo "eventos restaurados: $events (manifiesto: $want)"
  [ "$events" = "$want" ] || ok=0

  info "Backend contra la copia (puerto interno, sin red de anclaje ni correo)"
  report="$work/informe.txt"
  (
    set -a; . "$ENVF"; set +a
    # nada de lo que haga este backend puede salir: cadena, correo, IA y S3 apuntan a un puerto cerrado
    export SPRING_DATA_MONGODB_URI="mongodb://127.0.0.1:$port/$db?directConnection=true"
    export SERVER_ADDRESS=127.0.0.1 SERVER_PORT=0
    export WEB3_PRIVATE_KEY="0x$(openssl rand -hex 32)" WEB3_NODE_URL=http://127.0.0.1:9
    export SPRING_MAIL_HOST=127.0.0.1 SPRING_MAIL_PORT=9 SPRING_AI_OPENAI_API_KEY=dummy
    export SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:9 MEDIA_S3_ENDPOINT=http://127.0.0.1:9
    export TRACEABILITY_BOOTSTRAP_PLATFORM_ADMIN_ENABLED=false TRACEABILITY_DEMO_SEED_ENABLED=false
    export TRACEABILITY_RESTORE_CHECK_ENABLED=true TRACEABILITY_RESTORE_CHECK_OUTPUT_FILE="$report"
    export SPRING_PROFILES_ACTIVE="$(printf '%s' "${SPRING_PROFILES_ACTIVE:-}" | sed 's/demo-seed//g; s/,,*/,/g; s/^,//; s/,$//')"
    exec java -jar "$JAR"
  ) > "$work/backend.log" 2>&1 &
  RESTORE_API=$!
  for ((i = 0; i < 300; i++)); do
    [ -f "$report" ] && break
    kill -0 "$RESTORE_API" 2>/dev/null || { tail -40 "$work/backend.log"; die "el backend de prueba no arrancó"; }
    sleep 1
  done
  [ -f "$report" ] || { tail -40 "$work/backend.log"; die "la verificación no terminó en 5 minutos"; }
  cat "$report"
  command grep -q '^resultado=OK$' "$report" || ok=0

  mkdir -p "$bk/registros"
  local log_file
  log_file="$bk/registros/restauracion-$(date -u +%Y-%m-%dT%H-%M-%SZ).txt"
  { echo "respaldo=$src"; echo "eventos_restaurados=$events"; cat "$src/manifiesto.txt" "$report";
    echo "conclusion=$([ "$ok" = 1 ] && echo CORRECTA || echo FALLIDA)"; } > "$log_file"
  echo "Registro: $log_file"
  cleanup_restore; trap - EXIT
  [ "$ok" = 1 ] || die "la restauración de prueba FALLÓ (arriba, el informe)"
  echo "Restauración de prueba CORRECTA (MongoDB de prueba y ficheros descifrados destruidos)."
}

cmd_instalar_temporizador() {
  need systemctl
  local units="$HOME/.config/systemd/user" src
  src=$(dirname "$(readlink -f "$0")")/systemd
  mkdir -p "$units"
  sed "s|@PAXFIDE_SH@|$(readlink -f "$0")|; s|@PAXFIDE_HOME@|$HOME_DIR|" "$src/paxfide-respaldo.service" > "$units/paxfide-respaldo.service"
  cp "$src/paxfide-respaldo.timer" "$units/"
  systemctl --user daemon-reload
  systemctl --user enable --now paxfide-respaldo.timer
  systemctl --user list-timers paxfide-respaldo.timer
  echo "Para que corra sin sesión abierta: sudo loginctl enable-linger $USER"
}

cmd_estado() {
  info "Procesos"
  pgrep -af "cloudflared tunnel --url" || echo "(sin túneles)"
  pgrep -af "java -jar $RUN_JAR" || echo "(backend parado)"
  pgrep -af "next start" || echo "(web parada)"
  dc ps
  # ADR-054 D1: salud interna (MongoDB incluido) en el puerto de gestión, que solo escucha en 127.0.0.1
  local mp
  mp=$(envval MANAGEMENT_SERVER_PORT); mp=${mp:-8081}
  info "Salud interna (127.0.0.1:$mp)"
  curl -s --max-time 5 "http://127.0.0.1:$mp/actuator/health" || echo "(sin respuesta: el backend está parado o arrancando)"
  echo
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
  actualizar) cmd_actualizar "${2:-}" ;;
  compilar) cmd_compilar ;;
  reparar-anclaje) cmd_reparar_anclaje ;;
  estado) cmd_estado ;;
  logs) cmd_logs "${2:-}" ;;
  parar) cmd_parar ;;
  respaldo) cmd_respaldo ;;
  restaurar-prueba) cmd_restaurar_prueba "${2:-}" ;;
  instalar-temporizador) cmd_instalar_temporizador ;;
  *) sed -n '2,32p' "$0"; exit 1 ;;
esac
