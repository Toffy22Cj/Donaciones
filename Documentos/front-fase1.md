```yaml
name: front-fase1
description: Diseño del frontend Flutter (paxfide-mobile) para PaxFide — arquitectura de superficies, sesión, red, storage, SyncEngine/Outbox, UX de AMBIGUOUS, restauración de navegación y árbol de rutas v1. Review en curso, sin ADR asignado todavía.
sources: [chat]
aliases: [Frontend, Flutter, paxfide-mobile, paxfide-web, SyncEngine, Outbox, AMBIGUOUS, NavigationRestoreState, ActionResolver, árbol de rutas]
```

## Alcance de este documento

Diseño de frontend para PaxFide, trabajado en modo "Diseñemos X" (sin código todavía). Cubre exclusivamente `paxfide-mobile` (Flutter) en profundidad; `paxfide-web` (React, panel administrativo) queda deliberadamente diferido hasta cerrar Flutter.

## 1. Estrategia de repos y superficies

- Tres repos separados (no monorepo): backend (Java/Maven, existente), `paxfide-mobile` (Flutter), `paxfide-web` (React/Next.js) — decidido por mismatch de CI/toolchain y riesgo de contract-drift, no por preferencia estilística.
- `api-contract-matrix.md` formalizado como fuente contractual compartida entre los tres repos. Regla de ownership: todo PR de backend que cambie el estado de un contrato actualiza la matriz en el mismo PR.
- Evolución futura hacia OpenAPI contemplada, pero no como reemplazo inmediato — la matriz codifica estados semánticos (CONTRATO CONCEPTUAL vs. CONTRATO DEFINIDO vs. YA EXISTE, etc.) que OpenAPI no expresa.
- Flutter enfocado en el usuario común (donante + operador de campo/QR); `paxfide-web` es la superficie administrativa (Organización/Platform Administrator) — confirmado por el usuario, no derivado.
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

Estados: `UNKNOWN → RESTORING → AUTHENTICATED | LOGGED_OUT`. Deliberadamente sin `REFRESHING`/`TOKEN_EXPIRED` — la estrategia de refresh sigue sin definir en Identity (confirmado en `identity-resumen.md` §7: "Estrategia de access/refresh token... relevante por el patrón offline de Flutter" queda listada como pendiente).

- JWT confirmado mínimo por fuente literal (`identity-resumen.md`): `sub = accountId, iat, exp, signature`. Nunca `organizationId`, `roles`, `platformAuthority`. `AuthorizationPrincipal` se resuelve backend-side en cada request — el cliente nunca lo cachea ni lo asume vigente.
- Logout local cerrado; revocación remota de token, refresh y semántica de `401/403` explícitamente pendientes, no inventados.

## 4. `core/network` — contrato de `ApiClient`

- Transporte HTTP puro: no conoce tipos de dominio, no genera `commandId`, no decide autorización.
- `CredentialMode`: `none | jwt | tracking` — refleja los tres mecanismos de autenticación confirmados en `ADR-037` (JWT, tracking credential HMAC, firma de webhook), nunca intercambiables entre sí.
- Modelo de error en tres capas: transporte / HTTP-API / interpretación de dominio-feature.
- `401` es un punto de extensión no implementado (`AuthResponseHandler`), no una política ya decidida.

## 5. `core/storage`

Tres stores independientes, sin TTL global (la frescura es responsabilidad de cada feature):

| Store | Propósito | Tecnología |
|---|---|---|
| `TokenStore` | credenciales, alta seguridad | `flutter_secure_storage` |
| `CacheStore` | reconstruible, cache ≠ fuente de verdad | sin tecnología obligada |
| `OutboxStore` | comandos pendientes + `commandId`, alta seguridad + integridad | pendiente de elegir |

Riesgo abierto anotado como requisito, no resuelto: el `OutboxStore` guarda datos operacionalmente sensibles (custodio, ubicación) con exposición ante pérdida de dispositivo comparable a credenciales.

## 6. `SyncEngine` + Outbox

Estados: `PENDING → IN_FLIGHT → ACKNOWLEDGED | FAILED | AMBIGUOUS`.

- `AMBIGUOUS` = fallo de transporte ambiguo (timeout) — no se sabe si el backend ejecutó el comando. Nunca se reintenta automáticamente ni se resuelve en silencio.
- **Reconciliación** (corrección aplicada tras detectar que `AMBIGUOUS` no tenía salida explícita, violación directa de la regla 2.6 de `reglas-equipo-y-agentes.md`): usa el contrato ya confirmado `GET /physical-assets/{assetRef}` → si `lifecycleStatus` coincide con el estado esperado post-comando, transiciona a `ACKNOWLEDGED`; si no, permanece `AMBIGUOUS` con reintento manual disponible usando el **mismo** `commandId` (nunca uno nuevo).
- Precisión epistémica explícita: esto demuestra "el estado observado coincide con el esperado", **no** "este `commandId` causó ese estado" — no se afirma causalidad no probada.
- `FAILED` queda reservado para rechazos inequívocos del backend; un timeout de red nunca entra ahí.

## 7. UX de `AMBIGUOUS`

- Lenguaje: "no pudimos confirmar" — nunca "falló" ni "fue realizada".
- Acción primaria tras timeout: "Verificar estado" (antes que "Reintentar"), para evitar que el operador reintente compulsivamente sobre una operación de la que no se sabe el resultado.
- Persiste entre reinicios de la app vía `OutboxStore` — no vuelve mágicamente al botón de acción original.
- No es un `lifecycleStatus` nuevo ni tiene ruta propia — vive dentro de `AssetScreen`, como estado de sincronización del comando, separado del estado de dominio del asset.

## 8. Contrato de restauración de navegación

- `NavigationRestoreState{route, parámetros permitidos, schemaVersion}` — únicamente contexto de navegación, nunca verdad de dominio.
- Nunca persiste: JWT, refresh token, `AuthorizationPrincipal`, roles, `lifecycleStatus`, resultado de `ActionResolver`, estado del Outbox, datos de Campaign/PhysicalAsset, datos financieros o de custodia.
- Taxonomía de rutas (independiente del listado concreto de rutas de §9 — la taxonomía define cómo se comporta una ruta **si existe y está aprobada**, no implica que una ruta concreta deba existir):

  ```text
  Ruta pública aprobada:
      puede restaurarse sin sesión.

  Ruta autenticada aprobada:
      solo puede restaurarse con sesión AUTHENTICATED.

  Ruta transitoria (formularios, diálogos, modales):
      nunca se restaura.
  ```

  Una ruta que no forma parte del árbol aprobado de §9 (incluida cualquier ruta en estado PENDIENTE DE DECISIÓN) no es restaurable bajo ninguna categoría — se trata como estado de navegación inválido (ver regla de estado corrupto/incompatible abajo).
- Restauración: "validar estructura de ruta" (formato, `schemaVersion` compatible), nunca "validar dominio" (asignación del asset, autorización, `lifecycleStatus`) — eso lo resuelven las capas posteriores (`AssetScreen` → `PhysicalAssetRepository`/backend/Outbox/`ActionResolver`).
- **Decisión B (cerrada)**: bajo `LOGGED_OUT`, se descarta cualquier ruta autenticada persistida → `/login` → destino estándar `/home` post-login. No hay "volver a donde estaba" — el router restaurando una ruta de recurso específico implicaría una validez de dominio que no puede garantizar.
- Una ruta pública aprobada sí puede restaurarse aunque la sesión esté `LOGGED_OUT` — "no tengo sesión" y "no puedo restaurar una ruta pública" son cosas distintas.
- El Outbox nunca influye en la decisión del router: un comando pendiente no hace que el router navegue automáticamente al recurso — se descubre a través de la UI normal (`/operator` → pendientes → recurso).
- Estado de navegación corrupto/incompatible (`schemaVersion` desconocida, ruta/parámetro inválido, ruta no aprobada) → se descarta → destino de fallback según sesión (`/home` o `/login`) — satisface la misma regla de "todo estado necesita salida explícita".
- v1 persiste solo una posición de restauración + parámetros mínimos, no el stack completo de navegación.

## 9. Árbol de rutas Flutter v1 — abierto únicamente por `/campaigns`

El árbol **no está cerrado todavía**: todas las rutas listadas abajo están aprobadas, pero `/campaigns` sigue PENDIENTE DE DECISIÓN (ver subsección "Abierto" al final de esta sección). Hasta resolverlo, §9 no se considera congelado.

Fuente verificada directamente contra `api-contract-matrix.md` (sección "4b. PhysicalAsset — QR y lectura operacional"): los tres QR apuntan a rutas UX ya cerradas —

| QR | Payload | Ruta | Acceso |
|---|---|---|---|
| Campaign | `publicCode` | `/c/{publicCode}` | Público |
| Tracking | `trackingCode` | `/tracking/{trackingCode}` | Público |
| Asset | `assetRef` | `/assets/{assetRef}` | Autenticado, entrada a acción de `EMPLOYEE` |

```text
PaxFide Mobile
├── PUBLIC
│   ├── /c/:publicCode          → CampaignPublicScreen
│   └── /tracking/:trackingCode → TrackingScreen
├── AUTH
│   └── /login                  → LoginScreen (transitoria, no restaurable)
└── AUTHENTICATED
    ├── /home                   → HomeScreen
    └── /operator
        ├── /operator/pending   → PendingOperationsScreen (fuente: OutboxStore local — ver precisión de ownership abajo)
        └── /assets/:assetRef   → AssetScreen (lifecycleStatus + ActionResolver + Outbox state, incl. AMBIGUOUS)

PENDIENTE DE DECISIÓN (fuera del árbol aprobado)
└── /campaigns
```

- Las acciones (`DISPATCH`, `RECEIVE`, `DELIVER`) no son rutas — son comandos sobre `/assets/:assetRef`; la acción disponible la deriva `ActionResolver` desde `lifecycleStatus` (`REGISTERED→Dispatch, DISPATCHED→Receive, RECEIVED→Deliver, DELIVERED→solo lectura`), nunca una URL propia.
- `AMBIGUOUS` no tiene ruta propia (no `/ambiguous/:assetRef` ni `/assets/:assetRef/ambiguous`) — modifica la presentación de `AssetScreen`, no crea superficie de navegación.
- Guards solo de sesión (`PublicRoute`, `AuthenticatedRoute`, `TransientAuthRoute`) — nunca guards de autorización de negocio (`CanDeliverGuard`, etc.) como mecanismo de seguridad, porque el JWT no contiene roles y la autoridad real sigue siendo backend.
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

- La lista de pendientes se construye exclusivamente desde el `OutboxStore` del dispositivo.
- La única lectura remota que puede combinarse es `GET /physical-assets/{assetRef}` (CONTRATO DEFINIDO en `api-contract-matrix.md` §4b), y solo para reconciliación por asset, nunca como listado.

### Abierto — sin resolver todavía (detectado en review, no decidido por conveniencia editorial)

```text
/campaigns
Estado: PENDIENTE DE DECISIÓN
```

`/campaigns` (listado de descubrimiento público) apareció como "necesidad funcional confirmada" en una versión intermedia del árbol, y desapareció del árbol final sin que nadie lo declarara explícitamente. La decisión depende del contrato funcional, no de la conveniencia del router:

1. Si el flujo público de Flutter es exclusivamente mediante `publicCode` compartido (QR/código), nunca por navegación/descubrimiento → `/campaigns` no entra en Flutter v1 — a declarar como decisión explícita.
2. Si Flutter tendrá descubrimiento público de campañas (la necesidad de negocio ya está confirmada en `convocatoria-diseno.md`) → `/campaigns` entra como ruta pública.

Hecho verificado relevante para la decisión (no la resuelve): `api-contract-matrix.md` §2 marca `GET /public/campaigns` (descubrimiento) como **PENDIENTE** — ni siquiera tiene contrato conceptual resuelto.

Mientras siga PENDIENTE DE DECISIÓN, `/campaigns` no forma parte del árbol aprobado y, por la taxonomía de §8, no es restaurable.

## 10. Estado de Fase 1 y orden de trabajo pendiente (Flutter)

| Bloque | Estado |
|---|---|
| Repos/superficies | ✅ Cerrado |
| Arquitectura Flutter | ✅ Cerrado |
| Sesión | ✅ Cerrado |
| Network | ✅ Cerrado |
| Storage | ✅ Cerrado |
| SyncEngine/Outbox | ✅ Cerrado |
| UX `AMBIGUOUS` | ✅ Cerrado |
| Restauración navegación | ✅ Cerrado |
| Árbol de rutas | ⚠️ Pendiente únicamente `/campaigns` |
| Guards | ⏳ Siguiente |
| Deep links | ⏳ Después de guards |

```text
Restauración de sesión (cerrado)
  → Restauración de navegación (cerrado)
  → Árbol de rutas (abierto únicamente por /campaigns — PENDIENTE DE DECISIÓN)
  → Guards y transición entre rutas (siguiente)
  → Deep links (publicCode / trackingCode)
  → Navegación específica por feature
  → Pantallas completas
  → Tests de flujo y estados
```

Después de Flutter: diseño de `paxfide-web` (panel administrativo React) — portal público de tracking vs. panel autenticado — deliberadamente no iniciado todavía.
