#!/usr/bin/env bash
# CI local (Carlos, 2026-10-07): excepción permanente a la regla 3.2 de reglas-equipo-y-agentes.md mientras la cuenta
# de GitHub no tenga Actions. Ejecuta exactamente lo que exigen las reglas —`mvn clean test -fae` del reactor
# completo— y guarda la evidencia en Documentos/evidencia-ci/. Sale con un código distinto de 0 si algo falla.
#
# Uso: scripts/ci-local.sh        (desde cualquier carpeta del repositorio)
set -uo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "ci-local: no es un repositorio git" >&2; exit 2; }
cd "$ROOT"

# 1. Nada sin commit: la evidencia es de un commit concreto
if [ -n "$(git status --porcelain)" ]; then
    echo "ci-local: hay cambios sin commit; la evidencia tiene que corresponder a un commit. Abortado." >&2
    git status --short >&2
    exit 3
fi

# 2. Docker disponible (Testcontainers: MongoDB en réplica y Ganache)
if ! docker info >/dev/null 2>&1; then
    echo "ci-local: Docker no está disponible; los tests de integración lo necesitan. Abortado." >&2
    exit 4
fi

COMMIT=$(git rev-parse --short HEAD)
COMMIT_FULL=$(git rev-parse HEAD)
BRANCH=$(git rev-parse --abbrev-ref HEAD)
STAMP=$(date -u +%Y-%m-%dT%H-%M-%SZ)
OUT_DIR="Documentos/evidencia-ci"
OUT="$OUT_DIR/ci-local-$COMMIT-$STAMP.txt"
LOG=$(mktemp "${TMPDIR:-/tmp}/ci-local-XXXXXX.log")
mkdir -p "$OUT_DIR"

echo "ci-local: commit $COMMIT ($BRANCH); log completo en $LOG"

# 3. Exactamente lo que exigen las reglas
START=$(date -u +%Y-%m-%dT%H:%M:%SZ)
mvn -B clean test -fae >"$LOG" 2>&1
STATUS=$?
END=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# 4. Evidencia: cabecera, versiones, resumen literal de Surefire por módulo y sha256 del log
{
    echo "# CI local — scripts/ci-local.sh"
    echo "commit:   $COMMIT_FULL"
    echo "rama:     $BRANCH"
    echo "inicio:   $START"
    echo "fin:      $END"
    echo "comando:  mvn -B clean test -fae"
    echo "salida:   $STATUS"
    echo
    echo "## Versiones"
    java -version 2>&1 | grep -v "Picked up" | sed 's/^/java:   /'
    mvn -v 2>&1 | grep -v "Picked up" | head -1 | sed 's/^/maven:  /'
    docker version --format 'docker: cliente {{.Client.Version}}, servidor {{.Server.Version}}' 2>&1
    echo
    echo "## Resumen de Surefire por módulo (literal)"
    grep -E "Building .*SNAPSHOT|Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$|No tests to run|BUILD (SUCCESS|FAILURE)|Reactor Summary|SUCCESS \[|FAILURE \[|SKIPPED" "$LOG"
    echo
    echo "## Tests fallidos (si los hay)"
    grep -E "<<< (FAILURE|ERROR)!" "$LOG" | grep -v "Tests run" || echo "ninguno"
    echo
    echo "## sha256 del log completo"
    sha256sum "$LOG" | cut -d' ' -f1
} >"$OUT"

echo "ci-local: evidencia en $OUT"
grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$|BUILD" "$OUT"

# 5. Código de salida del reactor
if [ "$STATUS" -ne 0 ]; then
    echo "ci-local: FALLO (mvn salió con $STATUS)" >&2
    exit "$STATUS"
fi
exit 0
