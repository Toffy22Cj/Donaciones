```yaml
name: front-fase1
description: Diseño del frontend Flutter (paxfide-mobile) de PaxFide — CERRADO a nivel de diseño. Superficies, sesión, red, storage, SyncEngine/Outbox, AMBIGUOUS, restauración, árbol de rutas v1, guards, deep links, navegación por feature, 8 pantallas y estrategia de tests. Sin ADR asignado todavía. Abiertos: H1, H2, N1, N2, contratos de acciones, infraestructura de enlaces y creación de cuentas.
sources: [chat]
aliases: [Frontend, Flutter, paxfide-mobile, paxfide-web, SyncEngine, Outbox, AMBIGUOUS, NavigationRestoreState, ActionResolver, árbol de rutas, guards, deep links, PendingIntent, DeepLinkParser, OperatorScreen]
```

## Alcance de este documento

Diseño de frontend para PaxFide, trabajado en modo "Diseñemos X" (sin código todavía). Cubre exclusivamente `paxfide-mobile` (Flutter) en profundidad; `paxfide-web` (React, panel administrativo) queda deliberadamente diferido hasta ahora.

**Estado del documento:** Flutter v1 **cerrado a nivel de diseño**, con las excepciones registradas explícitamente como hallazgos, dependencias o decisiones pendientes en §16. Cerrado a nivel de diseño **no** significa implementable hoy: varios contratos consumidos siguen en estado CONCEPTUAL/DEFINIDO sin implementación backend (§16).

## 1. Estrategia de repos y superficies

- Tres repos separados (no monorepo): backend (Java/Maven, existente), `paxfide-mobile` (Flutter), `paxfide-web` (React/Next.js) — decidido por mismatch de CI/toolchain y riesgo de contract-drift, no por preferencia estilística.
- `api-contract-matrix.md` formalizado como fuente contractual compartida entre los tres repos. Regla de ownership: todo PR de backend que cambie el estado de un contrato actualiza la matriz en el mismo PR.
- Evolución futura hacia OpenAPI contemplada, pero no como reemplazo inmediato — la matriz codifica estados semánticos (CONTRATO CONCEPTUAL vs. CONTRATO DEFINIDO vs. YA EXISTE, etc.) que OpenAPI no expresa.
- Flutter enfocado en el usuario común (donante + operador de campo/QR); `paxfide-web` es la superficie administrativa (Organización/Platform Administrator) — confirmado por el usuario, no derivado. El alcance efectivo del donante en v1 está acotado en §12.
- Fuera deliberadamente de Flutter: `GET /organizations/{organizationId}/campaigns` (lectura administrativa, `ADMINISTRATOR` + `OrganizationBoundaryPolicy`) — pertenece a `paxfide-web`.

## 2. Arquitectura interna de Flutter

Feature-based, con dirección de dependencia estricta `presentation → domain → data → core` (nunca invertida):

```text
app/
core/
  network/
  storage/
  security/
  offline/
  errors/
  result/
features/
  auth/
  campaigns/
  donations/
  tracking/
  physical_assets/
shared/
```

`ActionResolver` vive en `features/physical_assets/domain/`, no en `core/` — es lógica específica del dominio de PhysicalAsset (lifecycle → acción disponible), no una utilidad transversal.

## 3. Contrato de sesión

> ⚠️ Afectado por hallazgo abierto **H2** (§16): comportamiento del Outbox ante cambio de cuenta autenticada.

Estados: `UNKNOWN → RESTORING → AUTHENTICATED | LOGGED_OUT`. Deliberadamente sin `REFRESHING`/`TOKEN_EXPIRED` — la estrategia de refresh sigue sin definir en Identity (confirmado en `identity-resumen.md` §7: "Estrategia de access/refresh token... relevante por el patrón offline de Flutter" queda listada como pendiente).

- JWT confirmado mínimo por fuente literal (`identity-resumen.md`): `sub = accountId, iat, exp, signature`. Nunca `organizationId`, `roles`, `platformAuthority`. `AuthorizationPrincipal` se resuelve backend-side en cada request — el cliente nunca lo cachea ni lo asume vigente.
- Logout local cerrado. Revocación remota de token, refresh y semántica general de `403` siguen pendientes en Identity, no inventados.

### Transiciones y salidas

| Estado | Salidas |
|---|---|
| `UNKNOWN` | → `RESTORING` |
| `RESTORING` | → `AUTHENTICATED` / `LOGGED_OUT` |
| `AUTHENTICATED` | → `LOGGED_OUT` por logout manual, **o por la regla provisional T-1** |
| `LOGGED_OUT` | → `AUTHENTICATED` por login |

### Regla provisional T-1 — `401` con JWT → `LOGGED_OUT` (aprobada)

Motivo: sin ella, `AUTHENTICATED` con JWT expirado no tenía salida (cada request devolvía `401`, el guard no reacciona a HTTP y `AuthResponseHandler` no tenía política) — violación de la regla 2.6 de `reglas-equipo-y-agentes.md`.

- Un `401` en una petición autenticada con **JWT** limpia el `TokenStore` y provoca `AUTHENTICATED → LOGGED_OUT`.
- El guard (§10) se encarga de las consecuencias de navegación.
- **No** decide refresh, revocación ni ninguna otra política de Identity.
- **No** toca el Outbox (eso es H2).
- **No** aplica al `401` de `/tracking`, que usa `trackingCode` (`CredentialMode = tracking`), no el JWT de sesión.
- Es **provisional**: se sustituye cuando Identity defina la estrategia de access/refresh token y la semántica de `401/403`.

## 4. `core/network` — contrato de `ApiClient`

- Transporte HTTP puro: no conoce tipos de dominio, no genera `commandId`, no decide autorización.
- `CredentialMode`: `none | jwt | tracking` — refleja los tres mecanismos de autenticación confirmados en `ADR-037` (JWT, tracking credential HMAC, firma de webhook), nunca intercambiables entre sí.
- Modelo de error en tres capas: transporte / HTTP-API / interpretación de dominio-feature.
- `AuthResponseHandler` es el punto de extensión del `401`. Su única política en v1 es la regla provisional T-1 (§3), limitada a `CredentialMode = jwt`.

## 5. `core/storage`

> ⚠️ Afectado por hallazgos abiertos **H1** (credencial bearer en estado de navegación persistido) y **H2** (ownership del Outbox por cuenta) — ver §16.

Tres stores independientes, sin TTL global (la frescura es responsabilidad de cada feature):

| Store | Propósito | Tecnología |
|---|---|---|
| `TokenStore` | credenciales, alta seguridad | `flutter_secure_storage` |
| `CacheStore` | reconstruible, cache ≠ fuente de verdad | sin tecnología obligada |
| `OutboxStore` | comandos pendientes + `commandId`, alta seguridad + integridad | pendiente de elegir |

Riesgo abierto anotado como requisito, no resuelto: el `OutboxStore` guarda datos operacionalmente sensibles (custodio, ubicación) con exposición ante pérdida de dispositivo comparable a credenciales.

## 6. `SyncEngine` + Outbox — máquina de estados consolidada

> ⚠️ Afectado por hallazgo abierto **H2** (§16): cambio de cuenta con comandos pendientes.

Estados: `PENDING`, `IN_FLIGHT`, `ACKNOWLEDGED`, `FAILED`, `AMBIGUOUS`. Todos tienen salida explícita (regla 2.6).

| Estado | Significado | Salidas |
|---|---|---|
| `PENDING` | comando encolado, no enviado | → `IN_FLIGHT` al enviarse |
| `IN_FLIGHT` | petición en curso | → `ACKNOWLEDGED` (aceptado) / `FAILED` (rechazo inequívoco) / `AMBIGUOUS` (timeout o fallo de transporte ambiguo). **Al arrancar la app, toda entrada persistida en `IN_FLIGHT` pasa a `AMBIGUOUS`** |
| `AMBIGUOUS` | no se sabe si el backend ejecutó el comando | → `ACKNOWLEDGED` vía "Verificar estado" (reconciliación) / reintento **manual** con el **mismo** `commandId` (→ `IN_FLIGHT`) |
| `FAILED` | el backend rechazó inequívocamente | → **Descartar** (con confirmación) / **Nueva operación** desde `AssetScreen` |
| `ACKNOWLEDGED` | confirmado | terminal; se retira de la lista de pendientes |

**`AMBIGUOUS` y reconciliación:**
- Nunca se reintenta automáticamente ni se resuelve en silencio.
- Reconciliación mediante el contrato ya confirmado `GET /physical-assets/{assetRef}`: si `lifecycleStatus` coincide con el estado esperado post-comando → `ACKNOWLEDGED`; si no, permanece `AMBIGUOUS` con reintento manual disponible usando el **mismo** `commandId` (nunca uno nuevo).
- Precisión epistémica: esto demuestra "el estado observado coincide con el esperado", **no** "este `commandId` causó ese estado".

**`FAILED`:**
- Reservado para rechazos inequívocos del backend; un timeout de red nunca entra ahí.
- **No se reintenta el mismo `commandId`**: el rechazo es determinista y volvería a fallar.
- **Descartar** elimina la entrada local del Outbox tras confirmación. No envía nada: el backend ya rechazó y no hay efecto que revertir.
- **Nueva operación** es una intención nueva: genera **otro `commandId`** y vuelve a pasar por el flujo normal de `ActionResolver`. No intenta revertir ni repetir el efecto del comando rechazado.

**`IN_FLIGHT` al arrancar (T-2):**

```text
App muere
   │
   ▼
IN_FLIGHT persistido
   │
   │ siguiente arranque
   ▼
AMBIGUOUS
   │
   ├── Verificar estado
   │       └── ACKNOWLEDGED / permanece AMBIGUOUS
   │
   └── reintento explícito
           └── mismo commandId
```

- No se conoce el resultado de la petición anterior: eso es exactamente `AMBIGUOUS`.
- `IN_FLIGHT` **nunca vuelve a `PENDING` automáticamente**: eso provocaría un reenvío automático que podría duplicar una operación ya aceptada por el backend (distinción fallo determinista vs. ambiguo, regla 2.6).

## 7. UX de `AMBIGUOUS`

- Lenguaje: "no pudimos confirmar" — nunca "falló" ni "fue realizada".
- Acción primaria tras timeout: "Verificar estado" (antes que "Reintentar"), para evitar que el operador reintente compulsivamente sobre una operación de la que no se sabe el resultado.
- Persiste entre reinicios de la app vía `OutboxStore` — no vuelve mágicamente al botón de acción original.
- No es un `lifecycleStatus` nuevo ni tiene ruta propia — vive dentro de `AssetScreen` y `PendingOperationsScreen`, como estado de sincronización del comando, separado del estado de dominio del asset.

## 8. Contrato de restauración de navegación

> ⚠️ Afectado por hallazgo abierto **H1** (§16): `/tracking/:trackingCode` es ruta pública aprobada y restaurable según la taxonomía de abajo, pero su parámetro es una credencial bearer.

- `NavigationRestoreState{route, parámetros permitidos, schemaVersion}` — únicamente contexto de navegación, nunca verdad de dominio.
- Nunca persiste: JWT, refresh token, `AuthorizationPrincipal`, roles, `lifecycleStatus`, resultado de `ActionResolver`, estado del Outbox, `PendingIntent`, datos de Campaign/PhysicalAsset, datos financieros o de custodia.
- Taxonomía de rutas (independiente del listado concreto de rutas de §9 — define cómo se comporta una ruta **si existe y está aprobada**, no implica que una ruta concreta deba existir):

  ```text
  Ruta pública aprobada:
      puede restaurarse sin sesión.

  Ruta autenticada aprobada:
      solo puede restaurarse con sesión AUTHENTICATED.

  Ruta transitoria (formularios, diálogos, modales, escáner):
      nunca se restaura.
  ```

  Una ruta que no forma parte del árbol aprobado de §9 (incluidas las rutas diferidas fuera de v1, como `/campaigns`) no es restaurable bajo ninguna categoría — se trata como estado de navegación inválido.
- Restauración: "validar estructura de ruta" (formato, `schemaVersion` compatible), nunca "validar dominio" (asignación del asset, autorización, `lifecycleStatus`) — eso lo resuelven las capas posteriores (`AssetScreen` → `PhysicalAssetRepository`/backend/Outbox/`ActionResolver`).
- **Decisión B (cerrada)**: bajo `LOGGED_OUT`, se descarta cualquier ruta autenticada persistida → `/login` → destino estándar `/home` post-login. El router restaurando una ruta de recurso específico implicaría una validez de dominio que no puede garantizar. La Decisión B rige la **restauración de estado persistido**; los intentos frescos por deep link se rigen por §11.
- Una ruta pública aprobada sí puede restaurarse aunque la sesión esté `LOGGED_OUT`.
- El Outbox nunca influye en la decisión del router: un comando pendiente no hace que el router navegue automáticamente al recurso — se descubre a través de la UI normal (`/operator` → pendientes → recurso).
- Estado de navegación corrupto/incompatible (`schemaVersion` desconocida, ruta/parámetro inválido, ruta no aprobada) → se descarta → destino de fallback según sesión (`/home` o `/login`).
- v1 persiste solo una posición de restauración + parámetros mínimos, no el stack completo de navegación.

## 9. Árbol de rutas Flutter v1 — cerrado

Historial de modificaciones explícitas del árbol en esta fase:
- `/campaigns` diferido fuera de v1 (decisión abajo).
- `/donations` añadido (reapertura puntual por la decisión de §12; no reabre `/campaigns`, H1, H2 ni deep links).
- `/operator` pasa a tener pantalla propia, `OperatorScreen` (§13); no añade ruta: ocupa un nodo ya aprobado.

Fuente de los QR verificada directamente contra `api-contract-matrix.md` §4b:

| QR | Payload | Ruta | Acceso |
|---|---|---|---|
| Campaign | `publicCode` | `/c/{publicCode}` | Público |
| Tracking | `trackingCode` | `/tracking/{trackingCode}` | Público (ver H1 en §16) |
| Asset | `assetRef` | `/assets/{assetRef}` | Autenticado, entrada a acción de `EMPLOYEE` |

```text
PaxFide Mobile v1
│
├── PUBLIC
│   ├── /c/:publicCode          → CampaignPublicScreen
│   └── /tracking/:trackingCode → TrackingScreen (parámetro = credencial bearer — H1)
│
├── AUTH
│   └── /login                  → LoginScreen (transitoria, no restaurable)
│
└── AUTHENTICATED
    ├── /home                   → HomeScreen
    ├── /donations              → MyDonationsScreen (solo lista, sin detalle)
    └── /operator               → OperatorScreen
        ├── /operator/pending   → PendingOperationsScreen (fuente: OutboxStore local)
        └── /assets/:assetRef   → AssetScreen (lifecycleStatus + ActionResolver + estado Outbox, incl. AMBIGUOUS)

DIFERIDO — FUERA DE FLUTTER v1 (no pertenece al árbol aprobado)
└── /campaigns
```

- `MyDonationsScreen` es la pantalla de `/donations`, no una sub-ruta. No existe `/donations/:id`.
- Las acciones (`DISPATCH`, `RECEIVE`, `DELIVER`) no son rutas — son comandos sobre `/assets/:assetRef`; la acción disponible la deriva `ActionResolver` desde `lifecycleStatus` (`REGISTERED→Dispatch, DISPATCHED→Receive, RECEIVED→Deliver, DELIVERED→solo lectura`).
- `AMBIGUOUS` no tiene ruta propia — modifica la presentación de `AssetScreen`, no crea superficie de navegación.
- El escáner interno no es ruta: es superficie transitoria de `OperatorScreen`.
- Guards solo de sesión — nunca guards de autorización de negocio (`CanDeliverGuard`, etc.) como mecanismo de seguridad, porque el JWT no contiene roles y la autoridad real sigue siendo backend. Contrato completo en §10.
- **P7 sigue vigente**: el cliente nunca convierte disponibilidad de acción en autorización — la integración de `OrganizationBoundaryPolicy`/`RoleAuthorizationPolicy` en `PhysicalAssetCommandService` sigue pendiente en el backend (`golden-path.md`).

### Precisión de ownership — `/operator/pending`

`/operator/pending` es una ruta de UI, **no un contrato backend ni una lectura de dominio nueva**. No implica ningún endpoint del tipo `GET /physical-assets/pending` ni nada que backend deba implementar.

```text
/operator/pending
    ↓
OutboxStore (fuente primaria, local)
    ↓
operaciones locales (PENDING / IN_FLIGHT / FAILED / AMBIGUOUS)
    ↓
assetRef
    ↓
GET /physical-assets/{assetRef} — solo cuando corresponda (reconciliación de §6)
```

### Decisión cerrada — `/campaigns` fuera de Flutter v1

```text
/campaigns
→ FUERA DE FLUTTER v1
→ no pertenece al árbol aprobado
→ no es restaurable
→ no recibe guard
→ no se implementa contra un endpoint pendiente
→ podrá reabrirse cuando GET /public/campaigns tenga contrato definido en la matriz
```

**Razón (contractual, no de preferencia de UX):** la matriz es la fuente contra la que se construye el frontend; `GET /public/campaigns` está **PENDIENTE** (ni contrato conceptual); las entradas públicas ya cerradas (QR y `/c/:publicCode`) cubren el acceso mientras tanto.

**Precisión de alcance:** no descarta la necesidad funcional de descubrimiento público, reconocida en `convocatoria-diseno.md`; solo fija su secuenciación.

**Condición de reapertura:** `GET /public/campaigns` en estado CONTRATO DEFINIDO. Al reabrirse, `/campaigns` entra por el proceso normal de revisión del árbol, no por reincorporación automática.

## 10. Guards y transición entre rutas — cerrado

### Responsabilidad

> ¿Puede mostrarse esta superficie con el estado de sesión actual?

No es un segundo sistema de autorización. No decide dominio, no lee Outbox, no decide qué se restaura (eso es §8).

### Entradas y salidas

- **Entradas (solo dos):** `SessionState` (§3) y categoría de ruta según §9: **Pública**, **Transitoria de auth** (`/login`), **Autenticada**, **No aprobada** (rutas desconocidas, parámetros estructuralmente inválidos y rutas diferidas como `/campaigns`).
- **Salidas:** `permitir | redirigir | esperar`.

### Tabla de decisión (referencia normativa)

| Sesión \ Ruta | Pública | `/login` | Autenticada | No aprobada |
|---|---|---|---|---|
| `UNKNOWN` / `RESTORING` | Permitir (no espera sesión) | Esperar | Esperar | Esperar → fallback |
| `AUTHENTICATED` | Permitir | → `/home` | Permitir | → `/home` |
| `LOGGED_OUT` | Permitir | Permitir | → `/login` (se descarta el destino restaurado — Decisión B; intento fresco por deep link: §11 R2) | → `/login` |

### Invariantes

1. **Idempotencia y ausencia de bucles.** Aplicar el redirect dos veces produce el mismo resultado. `/login` bajo `LOGGED_OUT` y `/home` bajo `AUTHENTICATED` son siempre destinos permitidos.
2. **Las rutas públicas no esperan sesión.**
3. **El guard solo conoce sesión + categoría de ruta.** Nunca consulta roles, `lifecycleStatus`, `ActionResolver`, Outbox ni códigos HTTP.
4. **Logout limpia la pila autenticada, sin decidir qué ocurre con el Outbox.** `AUTHENTICATED → LOGGED_OUT` (por logout o por T-1) elimina las rutas autenticadas de la pila; el destino del Outbox es H2.
5. **`403` no provoca redirección del guard.** Lo resuelve la pantalla como estado propio.
6. **El guard no reacciona a respuestas HTTP.** Reacciona solo a cambios de `SessionState`. El `401` con JWT se traduce a un cambio de sesión en `AuthResponseHandler` (T-1, §3); el guard solo ve la transición resultante.

### Superficie de arranque — UI de espera fuera del router

No se añade `/boot`. La UI de espera **no es una ruta**: no aparece en §9, no se restaura, no recibe guard, no puede ser destino de deep link y no modifica la taxonomía de §8.

### Orden de activación del router durante el arranque (G-1, opción a)

**El router está activo desde el arranque, incluso durante `UNKNOWN` / `RESTORING`.** La UI de espera se muestra únicamente para rutas cuya fila es "Esperar"; nunca bloquea el router completo.

```text
App arranca
    │
    ▼
SessionState = UNKNOWN / RESTORING
    │
    ▼
Router activo
    │
    ├── Ruta pública
    │      └── PERMITIR inmediatamente
    │
    ├── Ruta autenticada
    │      └── UI de espera fuera del router
    │
    ├── /login
    │      └── UI de espera si corresponde
    │
    └── Ruta no aprobada
           └── UI de espera → fallback al resolver sesión
```

Motivo: preserva la tabla y el invariante 2; un donante que abre un QR público no espera una sesión que esa superficie no necesita. La opción (b) (todo espera) fue descartada.

### Validación estructural de parámetros

Los documentos no definen formato de `publicCode` ni de `assetRef` para el cliente; no se inventa. En v1: **no vacío + longitud máxima defensiva**. Parámetro inválido → ruta **No aprobada**.

### Concurrencia

El router reevalúa en cada emisión de `SessionState`. Un deep link a ruta no pública durante `RESTORING` queda en la UI de espera; uno a ruta pública se resuelve de inmediato.

### Relación con hallazgos abiertos

H1 afecta a restauración y logs, no a la decisión del guard. El invariante 4 cubre solo la pila de navegación, no el Outbox (H2). Ninguno se resuelve dentro de guards.

## 11. Deep links — cerrado (dependencia de infraestructura abierta)

### Definición

Un deep link es un **intento nuevo iniciado por el usuario desde fuera de la navegación de la app**: QR escaneado con la cámara del sistema, enlace abierto desde otra app, o QR escaneado con el escáner interno de `OperatorScreen`. Es distinto de la restauración de §8 (posición persistida). La Decisión B rige lo segundo, no lo primero.

| Payload | Ruta | Sesión requerida | Comportamiento |
|---|---|---|---|
| `publicCode` | `/c/:publicCode` | No | Se muestra inmediatamente. |
| `trackingCode` | `/tracking/:trackingCode` | No | Se muestra inmediatamente. El tratamiento posterior de la credencial es H1. |
| `assetRef` | `/assets/:assetRef` | Sí | Sin sesión → `PendingIntent` (R2). |

### Decisiones

- **R1 — Único `DeepLinkParser`.** Todo deep link, del sistema operativo o del escáner interno, pasa por un único parser que produce una ruta candidata y la entrega al guard. No existe vía paralela.
- **R2 — `PendingIntent` solo en memoria.** Con `LOGGED_OUT` y deep link a ruta autenticada: se registra `PendingIntent{route, params}` **nunca persistido**; se consume **una sola vez** tras login exitoso; un deep link nuevo lo **reemplaza**; si el login se cancela o fracasa, o la app muere, **desaparece**. No contradice la Decisión B. `AssetScreen` sigue validando contra backend.
- **R3 — Ningún deep link ejecuta comandos.** `DISPATCH`/`RECEIVE`/`DELIVER` requieren siempre acción explícita del operador. Un deep link no genera `commandId` ni entradas en el Outbox.
- **R4 — El intento fresco tiene precedencia** sobre `NavigationRestoreState` en un arranque en frío.
- **R5 — Validación estructural y host canónico.** Host desconocido, ruta desconocida o parámetro inválido → **No aprobada** → fallback. **El host canónico concreto está pendiente** y no se fija como valor ni en código ni en este documento.

### Deep link con formulario con cambios no enviados (opción ii: confirmar salida)

```text
Formulario con cambios no enviados
        │
        │ llega deep link
        ▼
   ¿hay cambios?
     │       │
    no       sí
     │       │
     ▼       ▼
navegar   confirmar salida
             │
        ┌────┴────┐
        ▼         ▼
     aceptar    cancelar
        │         │
        ▼         ▼
   deep link   permanece
```

- **Abandonar** → descarta el estado no enviado y **consume** el deep link.
- **Cancelar** → conserva el formulario y **no consume** el deep link.
- Protege solo estado de formulario en memoria; no toca el Outbox ni H2.

### Dependencia de infraestructura abierta

Pendiente de definir/verificar: dominio canónico; **Android App Links**; **iOS Universal Links**; configuración de dominio verificado; fallback web cuando la app no está instalada. Un esquema de URL no verificado puede ser reclamado por otra app; como `trackingCode` es credencial bearer, el mecanismo de entrega forma parte de la superficie que H1 debe considerar (no decide H1).

## 12. Alcance funcional del donante y navegación por feature

### Alcance del donante en Flutter v1 — secuenciación contractual (aprobada)

Aplicando el mismo criterio que a `/campaigns`:

| Superficie | Contrato (`api-contract-matrix.md`) | Decisión |
|---|---|---|
| Iniciar donación (`POST /public/campaigns/{publicCode}/donation-intents`) | PENDIENTE — proveedor de pago no elegido | **Fuera de v1** |
| Estado del pago (`PENDING/CONFIRMED/FAILED/EXPIRED-UNKNOWN`) | semántica cerrada, HTTP pendiente del proveedor | **Fuera de v1** |
| Registro (`POST /auth/register`) + verificación de email | dominio cerrado, falta capa HTTP; verificación de email PENDIENTE | **Fuera de v1** |
| Mis donaciones (`GET /account/donations`) | REUTILIZA PATRÓN EXISTENTE | **Dentro de v1** (`/donations`) |

Consecuencia registrada explícitamente: **el Golden Path del donante (sin/con cuenta) no es implementable en Flutter v1** con los contratos actuales. No es un fallo del frontend: es secuenciación contractual.

### `/donations` — superficie sin flujo productor en v1

- Ruta autenticada, restaurable según §8, **solo lista**, sin `/donations/:id`.
- Tocar una donación **no navega** a otra superficie en v1; no hay enlace a `/tracking` (la cuenta no posee el `trackingCode`: su canal de entrega está PENDIENTE y es una credencial distinta del JWT — regla 6 de la matriz).
- **No existe flujo productor en v1**: la única vía para ligar una donación a una cuenta es `donation-intents` con JWT opcional (fuera de v1), y la creación de cuentas no está diseñada (§16). En v1 la lista estará vacía en la práctica. No debe presentarse como "el donante ve sus donaciones" en demostraciones.
- No se inventan campos de `DonationReadModel` que la matriz no especifica.

### `/home` — regla provisional (i) mientras N1 no tenga contrato

```text
HOME (provisional)
├── Mis donaciones → /donations
└── Operaciones → /operator
                    │
                    └── 403 → estado de pantalla
                              "tu cuenta no tiene acceso operativo"
                              (sin redirect)
```

- Provisional: no decide cómo funcionará `/home` cuando exista el contrato de N1.
- El `403` es estado de pantalla (invariante 5), nunca redirect. El frontend no es autoridad sobre roles.
- Opción (ii) descartada: ocultaría `/operator/pending` detrás de un escaneo previo, una restricción de UX derivada de una carencia contractual.

### Reglas de navegación por feature (aprobadas)

1. **Estados de pantalla no son rutas.** Carga, error, vacío, `403`, `404`, `AMBIGUOUS`, `401` de tracking.
2. **Formularios de comando son transitorios sobre `AssetScreen`** (hoja/diálogo), nunca rutas, nunca restaurables; con deep link entrante aplica la confirmación de §11.
3. **Secciones de contenido no son rutas.** Narrativa de tracking dentro de `TrackingScreen`; narrativa de campaña dentro de `CampaignPublicScreen`.
4. **Back según origen y pila disponible.** Ruta pública con pila vacía bajo `LOGGED_OUT` → salir de la app (comportamiento del sistema, no una ruta; no existe `/home` público). Ruta autenticada con pila vacía → `/home`. `AssetScreen` abierta desde `/operator/pending` → vuelve a pendientes.

## 13. Pantallas (8)

| Pantalla | Ruta | Datos (estado del contrato) | Estados | Acciones / salidas |
|---|---|---|---|---|
| `LoginScreen` | `/login` | `POST /auth/login` (contrato cerrado, implementación pendiente) | formulario; enviando; credenciales inválidas; error de red | Login sin efectos duplicables: reintento tras error de red es seguro. Sin enlace "crear cuenta". Salida: `/home` o `PendingIntent` |
| `HomeScreen` | `/home` | ninguno | — | Regla provisional (i), §12 |
| `MyDonationsScreen` | `/donations` | `GET /account/donations` (reutiliza patrón) | carga; lista; vacío (esperado en v1); error | Solo lista, sin detalle |
| `CampaignPublicScreen` | `/c/:publicCode` | `GET /public/campaigns/{publicCode}` (diseño cerrado); narrativa `GET .../narrative` (contrato definido, implementación pendiente) | carga; contenido; código inexistente; error; narrativa no disponible | **Sin botón "Donar" en v1** |
| `TrackingScreen` | `/tracking/:trackingCode` | tracking + narrativa (YA EXISTE, Fase 3) | carga; contenido; **`401` único "código no válido o expirado"**; error; narrativa `AVAILABLE`/`PENDING` | Narrativa `PENDING` → "Actualizar" manual |
| `OperatorScreen` | `/operator` | ninguno propio | normal; `403` "tu cuenta no tiene acceso operativo" | Entrada al escáner (transitorio); acceso a `/operator/pending` |
| `AssetScreen` | `/assets/:assetRef` | `GET /physical-assets/{assetRef}` (contrato definido) | carga; contenido; `403`; `404`; error; estado de Outbox incl. `AMBIGUOUS` | Acción según `ActionResolver`; **formularios de comando bloqueados por contrato**; "Nueva operación" tras `FAILED` |
| `PendingOperationsScreen` | `/operator/pending` | `OutboxStore` + reconciliación puntual `GET /physical-assets/{assetRef}` | una fila por entrada con su estado | `AMBIGUOUS`: Verificar estado / reintento mismo `commandId`; `FAILED`: Descartar (confirmación) |

Precisiones:
- **`401` de tracking:** el backend responde el mismo `401` para código inválido, expirado o revocado (Tarea 3.4, Fase 3). El frontend nunca intenta distinguir la causa: filtraría lo que el contrato oculta deliberadamente. Este `401` nunca modifica la sesión.
- **Sin botón "Donar":** no debe existir un control que prometa una capacidad sin contrato implementable en v1.
- **Formularios de comando:** `dispatch`/`receive` están en CONTRATO CONCEPTUAL sin DTO; `deliver` en CONTRATO DEFINIDO sin cuerpo explícito en la matriz. No se especifican campos hasta que exista contrato.
- **Narrativa `PENDING`:** actualización manual. Sin polling en v1 (sería un mecanismo de reintento nuevo que exigiría ADR, regla 3.5).

## 14. Estrategia de tests (aprobada)

### Auditoría de salidas (regla 2.6)

| Máquina | Estado | Salidas | Completa |
|---|---|---|---|
| Sesión | `UNKNOWN` | → `RESTORING` | ✅ |
| | `RESTORING` | → `AUTHENTICATED` / `LOGGED_OUT` | ✅ |
| | `AUTHENTICATED` | → `LOGGED_OUT` (logout o T-1) | ✅ |
| | `LOGGED_OUT` | → `AUTHENTICATED` (login) | ✅ |
| Outbox | `PENDING` | → `IN_FLIGHT` | ✅ |
| | `IN_FLIGHT` | → `ACKNOWLEDGED` / `FAILED` / `AMBIGUOUS`; al arrancar → `AMBIGUOUS` (T-2) | ✅ |
| | `AMBIGUOUS` | → `ACKNOWLEDGED` / reintento mismo `commandId` | ✅ |
| | `FAILED` | → Descartar / Nueva operación | ✅ |
| | `ACKNOWLEDGED` | terminal, retirada de la lista | ✅ |
| `PendingIntent` | retenido | → consumido / descartado | ✅ |
| Diálogo formulario + deep link | abierto | → abandonar / cancelar | ✅ |
| Narrativa | `PENDING` | → Actualizar | ✅ |
| Restauración | estado corrupto | → fallback | ✅ |

### Niveles

1. **Unitarios de lógica pura:** tabla de guards (4 estados × 4 categorías) + idempotencia; `ActionResolver`; `DeepLinkParser` (cada payload, host no canónico, ruta desconocida, parámetro inválido); validación de `NavigationRestoreState` (`schemaVersion` desconocida, ruta no aprobada, `/campaigns`); máquina del Outbox (todas las transiciones de la auditoría, incl. T-2); `PendingIntent` (consumo único, reemplazo, descarte); `AuthResponseHandler` (T-1 con JWT; sin efecto con tracking).
2. **Estados de pantalla con `ApiClient` falso:** las 8 pantallas en cada uno de sus estados (carga, contenido, vacío, error, `403`, `404`, `AMBIGUOUS`, `401` de tracking, narrativa `PENDING`).
3. **Flujos completos con backend falso:**
   - arranque en frío con ruta pública → se muestra sin UI de espera (G-1);
   - deep link a `/assets` sin sesión → login → llega al asset (R2);
   - timeout en comando → `AMBIGUOUS` → Verificar estado → `ACKNOWLEDGED`;
   - app muere con `IN_FLIGHT` → arranque → `AMBIGUOUS` (T-2);
   - `FAILED` → Descartar;
   - `401` con JWT → `LOGGED_OUT` → `/login` (T-1);
   - logout → back no vuelve a `/assets` (invariante 4);
   - formulario con cambios + deep link → cancelar conserva el formulario;
   - arranque en frío con deep link + `NavigationRestoreState` → gana el deep link (R4).

### Aserciones negativas obligatorias (regla 2.5)

- una ruta pública nunca espera ni se redirige;
- el guard nunca lee Outbox ni roles;
- un deep link nunca crea entradas en Outbox, nunca genera `commandId`, nunca invoca `DISPATCH`/`RECEIVE`/`DELIVER`;
- `AMBIGUOUS` nunca se reintenta automáticamente;
- `IN_FLIGHT` nunca vuelve a `PENDING` al arrancar;
- `FAILED` nunca se reintenta con el mismo `commandId`;
- `PendingIntent` nunca se persiste (ni en `NavigationRestoreState` ni en ningún store);
- `/campaigns` nunca se permite en ningún estado de sesión;
- el `401` de tracking nunca modifica la sesión.

### Fuera del cierre de Flutter

Pruebas E2E contra backend real. **No forman parte de la evidencia necesaria para cerrar el diseño de Flutter v1** mientras los contratos consumidos sigan en CONCEPTUAL/DEFINIDO sin implementación: probar contra un backend incompleto produciría falsos positivos. No quedan descartadas del proyecto; serán otra capa de verificación cuando los contratos estén implementados.

## 15. Resumen de decisiones cerradas en esta fase

- Árbol v1 con `/donations` añadido y `/campaigns` diferido.
- Guards: tabla, 6 invariantes, UI de espera fuera del router, router activo durante el arranque (G-1 a).
- Deep links: R1–R5, formulario + deep link con confirmación.
- Donación, estado de pago y registro fuera de v1; `/donations` dentro, sin flujo productor.
- `/home` provisional (i).
- `OperatorScreen` en `/operator`, con escáner transitorio.
- Outbox: salida de `FAILED` (Descartar / Nueva operación con `commandId` nuevo) e `IN_FLIGHT → AMBIGUOUS` al arrancar (§6 consolidado).
- T-1: `401` con JWT → `LOGGED_OUT` (provisional).
- Narrativa `PENDING` con actualización manual; sin botón "Donar"; `401` único de tracking.
- Estrategia de tests en tres niveles + aserciones negativas; E2E fuera del cierre.

## 16. Hallazgos, dependencias y decisiones pendientes

Ninguna de las opciones listadas en esta sección está elegida.

### H1 — `trackingCode` en restauración de navegación

**Conflicto:** §8 permite restaurar sin sesión una ruta pública aprobada, y `/tracking/:trackingCode` lo es; pero `trackingCode` es una **credencial bearer HMAC** (ADR-021-A/B, validez embebida de 365 días por defecto). Persistirla en `NavigationRestoreState` la conserva en un almacenamiento no diseñado como `TokenStore`, contra el espíritu de §8. Superficie adicional: logs del router, reportes de fallos, analítica y el mecanismo de entrega del enlace (§11).

**Pregunta:** ¿puede una ruta cuyo parámetro es una credencial bearer formar parte de `NavigationRestoreState`?

**Opciones:** prohibir la restauración de `/tracking/:trackingCode`; permitirla almacenando otra referencia segura (tocaría §5); cambiar el mecanismo de navegación/tracking.

**Verificación pendiente relacionada:** `api-contract-matrix.md` §5 lista `GET /tracking/{trackingCode}` (código en la ruta), mientras la Tarea 3.4 de `plan-ejecucion-agentes-fase3.md` implementó el filtro sobre `/api/v1/donations/tracking/**` con el token en `Authorization: Bearer`. Verificar contra el repositorio cuál es el contrato vigente.

Que el QR impreso contenga la credencial ya está decidido (§4b de la matriz); H1 trata solo lo que hace el cliente después.

### H2 — Ownership del Outbox ante cambio de cuenta

**Conflicto:** no existe frontera por `accountId` que impida procesar bajo una identidad nueva comandos creados bajo la anterior. Si `SyncEngine` envía el comando de la cuenta A con el JWT de la cuenta B, el backend registraría un evento inmutable con el actor equivocado — choca con el modelo de auditoría de Fase 6 (`HumanAccount`: `accountId + organizationId + roles efectivos`). Aplica también a entradas `AMBIGUOUS` en el logout, incluido el logout provocado por T-1.

**Opciones:** vaciar/invalidar el Outbox al cambiar de cuenta; particionarlo por `accountId`; bloquear el procesamiento hasta reautenticar la cuenta propietaria; una combinación. Cualquiera debe cumplir la regla 2.6 (estados nuevos con salida explícita). Candidata a constar en el futuro ADR del frontend.

### N1 — Contrato de lectura del principal / capacidades (dependencia de backend)

El JWT no lleva roles y no existe contrato de "quién soy / capacidades" en la matriz. Sin él, `/home` no puede decidir qué entradas mostrar; mientras tanto rige la regla provisional (i) de §12. Cuando exista, **no sustituirá la autorización backend**: solo permitirá representar correctamente la UI. No se inventa endpoint ni DTO.

### N2 — Ownership de `register`, `split` y `from-donation`

La matriz los define como `JWT + EMPLOYEE` (`REPRESENTATIVE` como respaldo en `register`/`split`), con integración P7 pendiente; `golden-path.md` sitúa `REGISTER_PHYSICAL_ASSET_FROM_DONATION` en el flujo del empleado. Eso sugiere, pero no decide, que pertenecen a la superficie operativa. **Pregunta:** ¿forman parte de `paxfide-mobile` o de `paxfide-web`? No se añaden rutas ni formularios para ellas hasta decidirlo.

### Contratos de acciones de `PhysicalAsset`

`dispatch`/`receive`: CONTRATO CONCEPTUAL (métodos no expuestos hoy en `PhysicalAssetCommandService`). `deliver`: CONTRATO DEFINIDO, autorización P7 pendiente. Los formularios de comando de `AssetScreen` quedan bloqueados hasta que existan los contratos.

### Infraestructura de enlaces

Dominio canónico, Android App Links, iOS Universal Links, dominio verificado y fallback web sin app instalada (§11).

### Creación de cuentas (dependencia externa al bloque Flutter)

No existe vía implementable de creación de cuentas: el registro de donantes está fuera de v1 (falta capa HTTP y verificación de email) y el flujo de registro/invitación de empleados no está definido (`identity-resumen.md`). Efecto:
- afecta al origen de todas las cuentas autenticadas;
- no cambia el contrato del login;
- no invalida `/donations`;
- explica por qué `/donations` está estructuralmente disponible pero sin flujo productor en v1.

## 17. Estado final de Flutter v1

| Bloque | Estado |
|---|---|
| Repos/superficies | ✅ Cerrado |
| Arquitectura Flutter | ✅ Cerrado |
| Sesión | ✅ Cerrado — T-1 provisional; ⚠️ H2 |
| Network | ✅ Cerrado |
| Storage | ⚠️ H1 / H2 |
| SyncEngine/Outbox | ✅ Cerrado — máquina consolidada (§6); ⚠️ H2 |
| UX `AMBIGUOUS` | ✅ Cerrado |
| Restauración navegación | ⚠️ H1 |
| Árbol de rutas | ✅ Cerrado (`/donations` añadido; `/campaigns` diferido) |
| Guards | ✅ Cerrado |
| Deep links | ✅ Cerrado; ⚠️ infraestructura de enlaces |
| Navegación por feature | ✅ Cerrado; `/home` provisional por N1 |
| Pantallas (8) | ✅ Cerrado; formularios de comando bloqueados por contrato |
| Tests de flujo y estados | ✅ Estrategia cerrada; E2E fuera del cierre |

```text
PENDIENTES REGISTRADOS
├── H1 trackingCode en restauración ........ ⚠️ decisión pendiente
├── H2 Outbox / accountId .................. ⚠️ decisión pendiente
├── N1 contrato quién-soy .................. ⚠️ dependencia de backend
├── N2 register/split/from-donation ........ ⚠️ ownership mobile vs web
├── Contratos dispatch/receive/deliver ..... ⚠️ dependencia de backend
├── Infraestructura de enlaces ............. ⚠️ dependencia de infraestructura
└── Creación de cuentas .................... ⚠️ dependencia externa
```

Orden seguido en Flutter:

```text
Restauración de sesión
  → Restauración de navegación
  → Árbol de rutas
  → Guards y transición entre rutas
  → Deep links
  → Navegación específica por feature
  → Pantallas completas
  → Tests de flujo y estados
  → CIERRE DE DISEÑO FLUTTER v1
```

Siguiente, cuando se decida: diseño de `paxfide-web` (panel administrativo React) — portal público de tracking vs. panel autenticado — todavía no iniciado. Las decisiones de este documento que merecen ADR (T-1, máquina del Outbox con T-2, R2 `PendingIntent`) se formalizarán en el ADR del frontend cuando se asigne.
