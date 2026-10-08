# Runbook — demo local completa (golden path con anclaje en Ganache)

**Estado:** vigente (2026-10-07). Por decisión de Carlos, los criterios 10, 11, 12 y 18 del golden path se cierran con el anclaje en la **cadena local (Ganache)**. La testnet pública es opcional (`runbook-anclaje-testnet.md`). **Limitación aceptada:** un anclaje en Ganache es real dentro de la cadena local, pero no lo pueden verificar terceros.
**Para qué:** levantar desde cero, en una máquina limpia, todo lo que necesita la demo en vivo y recorrer el golden path por HTTP guardando la evidencia.
**Probado:** 2026-10-07T21:41Z, en una máquina limpia, siguiendo los pasos tal cual. Desde `down -v` hasta el recorrido completo con el anclaje: 42 s con el backend ya compilado (el primer `mvn install` tarda unos minutos). Evidencia de esa pasada: `evidencia-fase6/demo-local-2026-10-07T21-40-35Z/`.
**Valores:** todos los de este documento y de `scripts/demo/demo.env.example` son **de ejemplo y solo locales**. No son secretos reales y no sirven fuera de esta máquina. La clave de Ganache es la cuenta determinista #0, pública y sin valor.

---

## 0. Requisitos de la máquina

| Herramienta | Versión probada | Para qué |
|---|---|---|
| Docker con Compose v2 | Docker 29.8, Compose 5.6 | MongoDB en réplica, Ganache y Mailpit |
| Java | 21 (21.0.12) | backend |
| Maven | 3.9 (3.9.11) | compilar y arrancar el backend |
| Python | 3.10 o superior (3.13) | `scripts/demo/recorrido.py` (solo biblioteca estándar) |
| `curl`, `git` | cualquiera | despliegue del contrato |

Puertos libres: 27017 (MongoDB), 8545 (Ganache), 1025 y 8025 (Mailpit) y 8080 (backend).

## 1. Infraestructura: MongoDB en réplica, Ganache y Mailpit

```bash
# en la raíz del repositorio, rama develop
docker compose -f scripts/demo/docker-compose.yml up -d
docker compose -f scripts/demo/docker-compose.yml ps     # mongo debe quedar "healthy"
```

- MongoDB arranca como réplica `rs0` de un nodo: las transacciones del backend la exigen. El *healthcheck* la inicia la primera vez.
- Ganache arranca con `--deterministic` y chain id 1337: las mismas cuentas de prueba en cada arranque.
- Mailpit (ADR-049) recibe por SMTP en el puerto 1025 los correos de invitación del perfil `dev`; se leen en **http://localhost:8025**. Ningún correo sale de la máquina.

## 2. Variables de entorno

```bash
cp scripts/demo/demo.env.example scripts/demo/demo.env   # demo.env está en .gitignore
```

`CRYPTO_ANCHOR_SMART_CONTRACT` se rellena en el paso 3. El resto ya trae valores de ejemplo locales:
- perfil `dev`, URI de Mongo y Ganache;
- red `ganache-local`, chain 1337 y 0 confirmaciones (Ganache mina al instante);
- secretos de seguimiento y del webhook simulado (al menos 32 caracteres);
- clave del cursor del descubrimiento (`TRACEABILITY_DISCOVERY_CURSOR_KEY`, 32 bytes en Base64, distinta de los demás secretos; para otra máquina: `openssl rand -base64 32`);
- semilla de la demo y su contraseña (al menos 12 caracteres);
- CORS y URL base para un frontend local (`TRACEABILITY_WEB_BASE_URL`, la del enlace de las invitaciones);
- correo: el perfil `dev` ya usa Mailpit. Para probar con Gmail (contraseña de aplicación), las variables `SPRING_MAIL_*` van en la terminal y nunca en el fichero.

`SPRING_AI_OPENAI_API_KEY` es opcional. Sin ella, la narrativa individual sale con el texto de respaldo y la de convocatoria muestra "Narrativa no disponible", que es lo esperado y nunca texto sin validar. Con una clave real (la de Carlos), en su terminal y nunca en el fichero, el LLM es real y pasa el mismo grounding.

## 3. Desplegar el contrato de anclaje en Ganache

```bash
ADDR=$(scripts/demo/deploy-anchor-registry.sh) && echo "$ADDR"
sed -i "s|^CRYPTO_ANCHOR_SMART_CONTRACT=.*|CRYPTO_ANCHOR_SMART_CONTRACT=$ADDR|" scripts/demo/demo.env
```

El script despliega `AnchorRegistry` (bytecode en `crypto/src/test/resources/solidity/build/`) desde la cuenta #1 de Ganache. La #0 queda para anclar con su nonce en 0, porque el contador de nonce del backend empieza en 0 (hallazgo H-P14-1). Si se reinicia Ganache, hay que repetir este paso y vaciar la base (paso 7).

## 4. Arrancar el backend (perfil `dev`)

```bash
mvn -q install -DskipTests                         # la primera vez y tras cada cambio de código
set -a; . scripts/demo/demo.env; set +a
mvn -q -pl app spring-boot:run                     # en otra terminal: queda escuchando en :8080
```

En el primer arranque, la **semilla** (`DemoSeedRunner`, solo con el perfil `dev` y `TRACEABILITY_DEMO_SEED_ENABLED=true`) crea:

| Cuenta | Email | Papel |
|---|---|---|
| Plataforma | `plataforma@demo.paxfide.local` | autoridad de plataforma |
| Representante | `representante@demo.paxfide.local` | `REPRESENTATIVE` de "Fundación Demo PaxFide" (`PENDING_VERIFICATION`) |
| Administrador | `administrador@demo.paxfide.local` | `ADMINISTRATOR` y `EMPLOYEE` |
| Empleado | `empleado@demo.paxfide.local` | `EMPLOYEE` |
| Donante | `donante@demo.paxfide.local` | donante con cuenta |

Todas entran con `TRACEABILITY_DEMO_SEED_PASSWORD`. En arranques siguientes la semilla no crea nada. Para comprobar que el backend responde: `curl -s localhost:8080/api/v1/public/campaigns` → `{"items":[]}`.

## 5. Recorrido del golden path, guardando la evidencia

```bash
set -a; . scripts/demo/demo.env; set +a
python3 scripts/demo/recorrido.py --salida demo-evidencia/$(date -u +%Y-%m-%dT%H-%M-%SZ)
```

El guion hace por HTTP los pasos 1–7 de `golden-path.md` y escribe cada petición y respuesta en `NN-paso.json`. Los JWT, `statusToken`, `trackingCode`, contraseñas y firmas aparecen como `***`, así que la carpeta se puede commitear. Pasos:
- verificar la organización;
- crear la convocatoria y asignar al empleado;
- donar sin cuenta y con cuenta, con el webhook simulado firmado;
- fondos, asignación y registro del activo (Camino A);
- división y espera del hijo;
- logística del padre y del hijo hasta `DELIVERED`;
- seguimiento con el `trackingCode` real;
- narrativas individual y de convocatoria.

Después espera a que **todos** los eventos del fondo, del padre y del hijo estén en batches `ANCHORED`: lo lee de MongoDB con `docker exec … mongosh`. Pide los recibos de cada transacción a Ganache y lo guarda en `anclaje-ganache.json`, con la raíz, el `txHash`, el bloque y el estado del recibo.

| Criterio de §8 | Dónde está la evidencia |
|---|---|
| 1–9, 13–17, 19 | los `NN-*.json` del recorrido |
| 10, 11, 18 | `anclaje-ganache.json`: batches `ANCHORED` que cubren los eventos del fondo, el padre y el hijo; recibos con `status: 0x1` |
| 12 (`verifyBatch` → `MATCH`) | sin ruta HTTP (matriz §6). Lo prueba el recorrido automático `GoldenPathHttpIntegrationTest` (Ganache por Testcontainers), cuya salida queda en cada `scripts/ci-local.sh`. En vivo: el poller solo marca `ANCHORED` si la raíz leída de la cadena coincide con la del batch |

Para conservar la evidencia de una sesión, se ejecuta con `--salida Documentos/evidencia-fase6/demo-local-<fecha UTC>` y se commitea.

**Qué se ve sin clave de LLM:** la narrativa individual sale `AVAILABLE` con `source: FALLBACK_TEMPLATE` (texto de respaldo) y la de convocatoria `UNAVAILABLE` con "Narrativa no disponible" y sus hechos (`clearedAmount`, unidades entregadas, receptores distintos).

**Repetir el recorrido sobre la misma base** funciona: crea otra convocatoria. Pero el empleado ya es responsable de la anterior, y un `EMPLOYEE` solo puede serlo de una convocatoria activa, así que la asignación da 409 y el guion avisa. Para una evidencia limpia, empezar de cero (paso 7).

## 5b. Invitar a un miembro (ADR-049)

Con el backend en marcha y la sesión del administrador de la semilla:

```bash
TOKEN=$(curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"administrador@demo.paxfide.local\",\"password\":\"$TRACEABILITY_DEMO_SEED_PASSWORD\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
ORG=$(curl -s localhost:8080/api/v1/me -H "Authorization: Bearer $TOKEN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["organizationId"])')
curl -s localhost:8080/api/v1/organizations/$ORG/invitations -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"email":"nueva@demo.paxfide.local","role":"EMPLOYEE"}'
```

- La respuesta es `202 {invitationId, role, expiresAt}`, exista o no una cuenta con ese email.
- El correo aparece en **http://localhost:8025** con el enlace `http://localhost:5173/invitaciones#token=…`. El token va en el fragmento, que el navegador no envía al servidor.
- La web toma el token del fragmento, lo borra de la barra y llama a `POST /api/v1/invitations/accept` con `{token}` en el cuerpo y la sesión de la cuenta invitada, que debe tener ese mismo email.

**Probado en vivo el 2026-10-08T00:10Z**, con los pasos tal cual, desde `down -v`:
- la invitación responde `202`;
- el correo llega a Mailpit con el asunto "Invitación a Fundación Demo PaxFide en PaxFide" y el enlace `http://localhost:5173/invitaciones#token=…`;
- la cuenta `nueva@demo.paxfide.local` se registra, acepta con el token en el cuerpo (`200 {organizationId, roles: ["EMPLOYEE"]}`) y su `/me` ya trae la organización;
- repetir la aceptación da `403`;
- el token no aparece ni una vez en el log del backend.

## 6. Recorrido automático (sin backend en marcha)

```bash
mvn -pl app -am test -Dtest=GoldenPathHttpIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Levanta su propio MongoDB y su propia Ganache con Testcontainers, recorre los 19 criterios (incluido `verifyBatch` → `MATCH`) y escribe `app/target/golden-path-anclaje-evidencia.txt`. Es la evidencia de cierre de B7 junto con `scripts/ci-local.sh`.

## 7. Parar y empezar de cero

```bash
# backend: Ctrl+C en su terminal
docker compose -f scripts/demo/docker-compose.yml down -v   # -v borra los datos de Mongo y la cadena
```

## 8. Problemas frecuentes

| Síntoma | Causa | Qué hacer |
|---|---|---|
| El backend no arranca: `TRACKING_CODE_SECRET`, `TRACEABILITY_DEMO_WEBHOOK_SECRET` o la contraseña de la semilla | Variables no cargadas | `set -a; . scripts/demo/demo.env; set +a` en la misma terminal |
| El backend no arranca: `TRACEABILITY_DISCOVERY_CURSOR_KEY` | Falta la clave del cursor del descubrimiento, no tiene 32 bytes en Base64 o coincide con otro secreto (DD-53: sin valor por defecto) | Cargar `demo.env`; para otra máquina, `openssl rand -base64 32`. El mensaje de error nunca muestra la clave |
| El backend no arranca: `SPRING_MAIL_HOST`, `TRACEABILITY_MAIL_FROM` o `TRACEABILITY_WEB_BASE_URL` | Sin perfil `dev` no hay valores por defecto (DD-61) | Arrancar con `SPRING_PROFILES_ACTIVE=dev` (`demo.env`) o dar las tres variables |
| No llega el correo de invitación | Mailpit parado | `docker compose -f scripts/demo/docker-compose.yml up -d mailpit`; la lista de invitaciones lo muestra como `delivery: FAILED` |
| `nonce too low` en el log del backend | Ganache reiniciada o contrato desplegado con la cuenta #0 | `down -v`, y repetir los pasos 1, 3 y 4 |
| El recorrido espera mucho al anclaje | Intervalos por defecto | Comprobar `TRACEABILITY_ANCHOR_PRODUCER_INTERVAL_MS`, `CRYPTO_ANCHOR_SUBMIT_DELAY` y `CRYPTO_ANCHOR_POLL_DELAY` en `demo.env` |
| `verificar-organizacion: 409` | La organización ya se verificó en otra ejecución | Es correcto; el guion lo acepta |
| Mongo no queda `healthy` | Puerto 27017 ocupado | Parar el Mongo local o cambiar el puerto en el compose y en la URI |
