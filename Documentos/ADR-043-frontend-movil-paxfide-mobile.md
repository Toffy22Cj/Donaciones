# ADR-043 — Frontend móvil `paxfide-mobile`: sesión, Outbox con fallo ambiguo, restauración, navegación y deep links

> **Procedencia (2026-10-08, encargo de Carlos):** copia literal, sin cambios, de `ADR-043-frontend-movil-paxfide-mobile.md` del repositorio móvil `Toffy22Cj/PaxFide_Mv`, rama `develop`, commit `5fd86beff4abece18047f461ae3a6578a08b3a90` (SHA-256 del fichero: `24eae592fca5df88ec97e2522be2ccbe32cb2703fcacd7b644bf878b9e8c7e05`). La copia canónica es la del repositorio móvil; esta es de consulta para el backend. Sustituye a la copia anterior, que seguía en PROPUESTO.

*Nota de numeración (2026-10-07, decisión de Carlos):* el ADR del frontend web `paxfide-web` se renumera de ADR-042 a **ADR-046** (`ADR-046-frontend-web-paxfide-web.md`, repositorio `Toffy22Cj/PaxFide`) por colisión con `ADR-042-orquestacion-centralizada-reintentos-proyeccion.md`, que conserva su número. Las referencias de este documento se actualizaron.

**Status:** **APROBADO — Carlos, 2026-10-07T22:35Z (UTC)**, con los cambios de la §0 "Enmienda de aprobación" (encargo "alinear y completar `paxfide-mobile`", 2026-10-07). Antes: PROPUESTO (2026-09-30). Lo que en este documento contradiga la §0 queda sustituido por ella.
**Fecha:** 2026-09-30 (revisión 1)
**Número:** **043, asignado al aprobarse (2026-10-07)**; texto original: — era el siguiente libre tras ADR-042 (frontend web, hoy ADR-046) en la numeración vigente. No está asignado hasta aprobación humana explícita (condición 2 de §7).

**Documento de diseño fuente:** `front-fase1.md` (raíz), versión con estado "Flutter v1 cerrado a nivel de diseño". Este ADR **no reescribe el diseño**: formaliza las decisiones arquitectónicamente significativas y remite a sus secciones (§N = sección de `front-fase1.md`). Ante cualquier discrepancia entre este ADR y `front-fase1.md`, se detiene y se reporta; no se resuelve por interpretación.

**Numeración de ADR:** se usa la numeración de Fase 6 confirmada el 2026-09-28 (ADR-037 Convocatoria, ADR-038 Identidad, ADR-039 Blockchain, ADR-040 IA, ADR-041 APIs/Frontend, ADR-046 frontend web). `front-fase1.md` §4 cita "ADR-037" con la numeración anterior; corresponde al **ADR-041** vigente.

**Relacionados:** `front-fase1.md`, `ADR-046-frontend-web-paxfide-web.md`, `claude/front-fase2.md`, `hallazgos-front-fase2.md`, `api-contract-matrix.md`, `identity-resumen.md`, `golden-path.md`, `reglas-equipo-y-agentes.md`.

**Origen de la obligación:** ADR-046 §5 dejó explícitamente fuera "las decisiones pendientes de ADR de `front-fase1.md` (T-1 en móvil, máquina del Outbox con T-2, `PendingIntent`)", para "un ADR propio o una enmienda, a decidir por el equipo". El equipo eligió ADR propio.

---

## 0. Enmienda de aprobación — Carlos, 2026-10-07T22:35Z

Decisiones de Carlos (encargo del 2026-10-07, sección 1), aplicadas literalmente. Prevalecen sobre cualquier punto de §1–§7 que las contradiga.

| # | Cambio | Sustituye o actualiza |
|---|---|---|
| A1 | **H2 decidido (sustituye a D11).** Cada entrada del Outbox guarda el `accountId` que la creó. Una cuenta distinta nunca envía ni ve entradas ajenas. Tras un logout las entradas siguen guardadas y solo reaparecen si entra la misma cuenta. Descartarlas pide confirmación. | D11 (queda sin efecto: el `SyncEngine` se habilita); H2 en §5 (cerrado) |
| A2 | **D12.** Se autorizan `mobile_scanner` (escanear) y `qr_flutter` (generar). El `OutboxStore` va en `flutter_secure_storage` (ya aprobado): JSON cifrado con un campo de versión. Enrutado y estado con el SDK de Flutter, sin librerías nuevas. Ninguna otra dependencia sin preguntar a Carlos. | D12 (tabla de dependencias); D5 (tecnología del `OutboxStore`) |
| A3 | **Alcance.** Donante y operador de campo (D1). Única excepción: la pantalla de **predicción**, visible también para `ADMINISTRATOR`/`REPRESENTATIVE` (requisito académico). | D1 |
| A4 | **D10 actualizado.** Ya existen en el backend `dispatch`/`receive`/`deliver`, `GET /me`, `POST /auth/register`, CV-11 (donar), la consulta de la intención y `GET /account/donations`; dejan de estar bloqueados. | D10; N1 en §5 (cerrado por `GET /me`); fila "Contratos de dispatch/receive/deliver" de §5 |
| A5 | **Secciones §8 y §9 como registro histórico.** Este ADR no tiene secciones §8 ni §9 numeradas; la nota se aplicó a **D8 y D9** (árbol de rutas y deep links), que son las que el encargo contradice (seguimiento sin código en la URL). Ver `Documentos/decisiones-delegadas-mobile-2026-10.md`, DDM-01. | D8, D9 |

Decisiones que el encargo da por tomadas y que este ADR recoge para la trazabilidad:

- **Seguimiento sin código en la URL** (decisión ya tomada en la web): el QR de seguimiento abre la pantalla de seguimiento **sin** el código; el código se escribe a mano y viaja solo en la cabecera `Authorization`. Cierra H1 en móvil: no hay credencial en ninguna ruta ni en `NavigationRestoreState`.
- **El rol sale de `GET /api/v1/me`** y vive en memoria junto a la sesión; nunca se persiste ni se lee del JWT (refuerza D3).
- Las acciones del operador se **muestran** solo a `EMPLOYEE` según `/me`; el backend sigue autorizando cada petición (P7).

---

## 1. Context

`paxfide-mobile` es la superficie del usuario común: donante y operador de campo que escanea QR (§1). Las funciones administrativas de Organización y Platform Administrator pertenecen a `paxfide-web` (ADR-046).

Restricciones que condicionan la decisión:

- **Identidad (ADR-038):** JWT mínimo (`sub = accountId`, `iat`, `exp`, firma). Nunca `organizationId`, roles ni `platformAuthority`. `AuthorizationPrincipal` se resuelve en backend en cada request. **No existe estrategia de refresh** ni semántica general de `401/403` (`identity-resumen.md` §7).
- **ADR-041:** tres mecanismos de credencial no intercambiables (JWT, tracking credential HMAC, firma de webhook). `publicCode` y `trackingCode` son secretos tipo bearer (§2.7). La protección contra doble toque es UX, no idempotencia (§2.6).
- **Operación de campo:** conectividad intermitente en centros de acopio y transporte. Una petición puede ejecutarse en backend sin que el cliente reciba respuesta.
- **Event Store inmutable:** un comando duplicado o atribuido al actor equivocado produce un evento que no se borra, solo se compensa.
- **Regla 2.6:** todo estado necesita salida explícita; fallo determinista ≠ fallo ambiguo.
- **Estado contractual:** varios contratos consumidos están en CONCEPTUAL/DEFINIDO sin implementación backend (§16). Cerrado a nivel de diseño **no** significa implementable hoy.
- **Proyecto:** académico, equipo de 4 personas, plazo acotado. Sin infraestructura sin necesidad demostrada.

---

## 2. Decision

### D1 — Repos y superficies

(§1)

- Tres repos separados: backend, `paxfide-mobile` (Flutter), `paxfide-web` (Next.js). Motivo: mismatch de CI/toolchain y riesgo de contract-drift, no preferencia estilística.
- `api-contract-matrix.md` es la fuente contractual compartida. Todo PR de backend que cambie el estado de un contrato actualiza la matriz en el mismo PR.
- Flutter cubre donante + operador de campo. `GET /organizations/{organizationId}/campaigns` queda fuera de Flutter.

### D2 — Arquitectura interna feature-based

(§2)

- Dependencia estricta `presentation → domain → data → core`, nunca invertida.
- `ActionResolver` vive en `features/physical_assets/domain/`: es lógica de dominio de PhysicalAsset (lifecycle → acción disponible), no utilidad transversal.
- **`ActionResolver` no es autorización (P7).** Deriva qué acción *mostrar*; la autoridad es exclusivamente backend.

### D3 — Sesión y regla provisional T-1

(§3, §4)

- Estados `UNKNOWN → RESTORING → AUTHENTICATED | LOGGED_OUT`, todos con salida (tabla de §3). Sin `REFRESHING`/`TOKEN_EXPIRED` mientras Identity no defina refresh.
- El cliente nunca cachea `AuthorizationPrincipal` ni decodifica el JWT buscando roles.
- **T-1 (provisional):** un `401` en una petición con `CredentialMode = jwt` limpia el `TokenStore` y provoca `AUTHENTICATED → LOGGED_OUT`.
  - No decide refresh, revocación ni ninguna política de Identity.
  - No toca el Outbox (eso es H2, §5 de este ADR).
  - **No** aplica al `401` de tracking.
  - Se sustituye cuando Identity defina access/refresh y la semántica de `401/403`. La sustitución requiere enmienda a este ADR.

### D4 — `ApiClient` y `CredentialMode`

(§4)

- `ApiClient` es transporte HTTP puro: no conoce tipos de dominio, no genera `commandId`, no decide autorización.
- `CredentialMode = none | jwt | tracking`, reflejo de los mecanismos de ADR-041; nunca intercambiables.
- Error en tres capas: transporte / HTTP-API / interpretación de feature.
- `AuthResponseHandler` es el único punto de extensión del `401`; su única política en v1 es T-1.

### D5 — Almacenamiento local en tres stores

(§5)

| Store | Propósito | Tecnología |
|---|---|---|
| `TokenStore` | credenciales | `flutter_secure_storage` |
| `CacheStore` | reconstruible; cache ≠ fuente de verdad | no fijada |
| `OutboxStore` | comandos pendientes + `commandId`; alta seguridad + integridad | **pendiente** (D12) |

- Sin TTL global: la frescura es responsabilidad de cada feature.
- **Requisito registrado, no resuelto:** el `OutboxStore` guarda datos operacionalmente sensibles (custodio, ubicación) con exposición ante pérdida del dispositivo comparable a una credencial.

### D6 — `SyncEngine` + Outbox con estado `AMBIGUOUS`

(§6, §7) — **mecanismo de reintento y recuperación de fallos: motivo principal de este ADR (regla 3.5).**

| Estado | Significado | Salidas |
|---|---|---|
| `PENDING` | encolado, no enviado | → `IN_FLIGHT` |
| `IN_FLIGHT` | petición en curso | → `ACKNOWLEDGED` / `FAILED` (rechazo inequívoco) / `AMBIGUOUS` (timeout o fallo de transporte ambiguo) |
| `AMBIGUOUS` | no se sabe si el backend ejecutó | → `ACKNOWLEDGED` vía "Verificar estado" / reintento **manual** con el **mismo** `commandId` (→ `IN_FLIGHT`) |
| `FAILED` | rechazo inequívoco | → Descartar (con confirmación) / Nueva operación (con `commandId` **nuevo**) |
| `ACKNOWLEDGED` | confirmado | terminal; se retira de la lista |

Reglas:

- **T-2:** al arrancar la app, toda entrada persistida en `IN_FLIGHT` pasa a `AMBIGUOUS`. `IN_FLIGHT` **nunca** vuelve a `PENDING` automáticamente: sería un reenvío automático capaz de duplicar una operación ya aceptada.
- `AMBIGUOUS` **nunca** se reintenta automáticamente ni se resuelve en silencio.
- **Reconciliación** mediante `GET /physical-assets/{assetRef}`: si `lifecycleStatus` coincide con el esperado post-comando → `ACKNOWLEDGED`; si no, sigue `AMBIGUOUS`. **Límite epistémico:** demuestra "el estado observado coincide con el esperado", no "este `commandId` causó ese estado".
- `FAILED` no se reintenta con el mismo `commandId` (el rechazo es determinista). Descartar no envía nada.
- Un timeout nunca clasifica como `FAILED`.
- **UX (§7):** lenguaje "no pudimos confirmar"; acción primaria "Verificar estado" antes que "Reintentar"; persiste entre reinicios; no es un `lifecycleStatus` ni tiene ruta propia.

**Límite de aplicabilidad:** la reconciliación por `lifecycleStatus` solo es válida para comandos cuyo efecto se refleja en `lifecycleStatus` (`DISPATCH`/`RECEIVE`/`DELIVER`). No es válida para `split` ni `register` (el `ReadModel` operacional excluye genealogía, ADR-041 §2.2; ver `hallazgos-front-fase2.md` R11). Esos comandos no están en el alcance móvil v1 (N2).

### D7 — Restauración de navegación

(§8)

- `NavigationRestoreState{route, parámetros permitidos, schemaVersion}`: solo contexto de navegación, nunca verdad de dominio. Nunca persiste JWT, refresh, principal, roles, `lifecycleStatus`, resultado de `ActionResolver`, estado del Outbox, `PendingIntent` ni datos de Campaign/PhysicalAsset/financieros/custodia.
- Taxonomía: pública aprobada (restaurable sin sesión), autenticada aprobada (solo con `AUTHENTICATED`), transitoria (nunca). Ruta fuera del árbol aprobado → estado inválido.
- La restauración valida **estructura**, nunca dominio.
- **Decisión B:** bajo `LOGGED_OUT` se descarta cualquier ruta autenticada persistida → `/login` → `/home`. Rige solo para estado persistido, no para deep links frescos (D9).
- El Outbox nunca influye en el router.
- Estado corrupto o incompatible → se descarta → fallback según sesión.
- v1 persiste una sola posición, no el stack.

### D8 — Árbol de rutas v1 y guards

> **Registro histórico (2026-10-07).** Esta decisión se conserva como registro. Lo que contradiga el encargo de Carlos del 2026-10-07 (§0) queda sustituido; en particular, la ruta `/tracking/:trackingCode` se sustituye por `/tracking` sin parámetro (el código nunca va en una URL).

(§9, §10)

- Árbol aprobado de §9: públicas `/c/:publicCode`, `/tracking/:trackingCode`; auth `/login`; autenticadas `/home`, `/donations`, `/operator`, `/operator/pending`, `/assets/:assetRef`.
- `/campaigns` **fuera de v1** por razón contractual (`GET /public/campaigns` PENDIENTE). Reapertura solo con contrato DEFINIDO y por revisión normal del árbol.
- Acciones, estados de pantalla, formularios, secciones de contenido, `AMBIGUOUS` y escáner **no son rutas**.
- `/operator/pending` es una ruta de UI cuya fuente es el `OutboxStore` local; **no** implica ningún contrato backend.
- **Guards solo de sesión.** Entradas: `SessionState` + categoría de ruta. Salidas: `permitir | redirigir | esperar`. Tabla normativa de §10.
- Invariantes: idempotencia sin bucles; rutas públicas no esperan sesión; el guard no lee roles, Outbox, `lifecycleStatus` ni HTTP; el logout limpia la pila autenticada sin decidir sobre el Outbox; `403` es estado de pantalla, nunca redirect.
- **G-1 (a):** router activo desde el arranque; la UI de espera solo aparece para rutas cuya fila es "Esperar" y no es una ruta (no existe `/boot`).
- Validación estructural de parámetros en v1: no vacío + longitud máxima defensiva. No se inventa formato de `publicCode`/`assetRef`.

### D9 — Deep links y `PendingIntent`

> **Registro histórico (2026-10-07).** Esta decisión se conserva como registro. Lo que contradiga el encargo de Carlos del 2026-10-07 (§0) queda sustituido; en particular, la ruta `/tracking/:trackingCode` se sustituye por `/tracking` sin parámetro (el código nunca va en una URL).

(§11)

- **R1:** único `DeepLinkParser` para enlaces del sistema operativo y del escáner interno.
- **R2:** con `LOGGED_OUT` y destino autenticado se registra `PendingIntent{route, params}` **solo en memoria**; consumo único tras login; un deep link nuevo lo reemplaza; desaparece si el login se cancela o falla, o si la app muere.
- **R3:** ningún deep link ejecuta comandos, genera `commandId` ni crea entradas en el Outbox.
- **R4:** el intento fresco tiene precedencia sobre `NavigationRestoreState` en arranque en frío.
- **R5:** host desconocido, ruta desconocida o parámetro inválido → No aprobada → fallback. **El host canónico no se fija** en este ADR ni en código.
- Formulario con cambios no enviados + deep link entrante → confirmar salida (abandonar consume el enlace; cancelar lo descarta y conserva el formulario).

### D10 — Alcance funcional v1

> **Actualizada (2026-10-07, §0 A4):** donar, consulta de la intención, registro de cuenta, `/me` y los comandos `dispatch`/`receive`/`deliver` ya existen en el backend y dejan de estar fuera de v1 o bloqueados.

(§12, §13)

- Fuera de v1 por secuenciación contractual: iniciar donación, estado del pago, registro de cuenta. **El Golden Path del donante no es implementable en Flutter v1.**
- `/donations` dentro de v1, solo lista, **sin flujo productor**: en v1 estará vacía en la práctica y no debe presentarse como "el donante ve sus donaciones".
- `/home` con regla provisional (i) mientras N1 no tenga contrato: muestra "Mis donaciones" y "Operaciones"; el `403` de `/operator` es estado de pantalla.
- Sin botón "Donar" en v1.
- `401` de tracking: un único mensaje "código no válido o expirado"; el cliente nunca distingue causa; nunca modifica la sesión.
- Narrativa `PENDING`: actualización manual; **sin polling** (sería un mecanismo de reintento nuevo, regla 3.5).
- Formularios de comando de `AssetScreen` **bloqueados por contrato** hasta que existan contratos de `dispatch`/`receive`/`deliver`.

### D11 — Bloqueo de habilitación del `SyncEngine` por H2

> **Sustituida (2026-10-07):** H2 quedó decidido por Carlos (§0, A1). Este bloqueo ya no aplica.

**PROPUESTA NUEVA de este ADR. No está en `front-fase1.md`; requiere aprobación explícita (condición 3 de §7).**

- Motivo: H2 (ownership del Outbox ante cambio de cuenta) puede producir un evento inmutable atribuido al actor equivocado. `hallazgos-front-fase2.md` clasifica esa clase de efecto como severidad **Crítica**. T-1 agrava el escenario: un `401` puede provocar logout con entradas `AMBIGUOUS` pendientes.
- Propuesta: el `SyncEngine` **no se habilita para envío** en ningún build distribuible mientras H2 no esté decidido mediante enmienda a este ADR. Análogo a ADR-046 D6 ("R11 es un bloqueo duro de habilitación, no deuda").
- **Esta propuesta no elige ninguna opción de H2.** Solo fija que no se despliega sin decidirla.
- Alternativa a esta propuesta: tratar H2 como riesgo aceptado y documentado. No recomendada: el efecto es irreversible en el Event Store.

### D12 — Dependencias base

> **Actualizada (2026-10-07, §0 A2):** `mobile_scanner`, `qr_flutter` y `OutboxStore` sobre `flutter_secure_storage` (JSON cifrado con versión) quedan decididos. Enrutado y estado: SDK de Flutter. Cliente HTTP: `dart:io` del SDK (DDM-04).

Mínimas. Cualquier dependencia no listada como "Decidido" requiere enmienda a este ADR antes de usarse (regla 3.5).

| Dependencia | Tipo | Uso | Estado |
|---|---|---|---|
| Flutter SDK / Dart | Runtime | Framework (D1) | Decidido. **Versión no fijada** en `front-fase1.md` |
| `flutter_secure_storage` | Runtime | `TokenStore` (D5) | Decidido |
| Almacenamiento del `OutboxStore` | Runtime | D5/D6 | **Pendiente.** Debe cumplir alta seguridad + integridad |
| Enrutador, gestión de estado, cliente HTTP, herramientas de test de integración | Runtime / desarrollo | D2, D4, D8, §14 | **No decididos.** `front-fase1.md` no los fija; este ADR no los elige |

---

## 3. Alternatives

Todas proceden de las decisiones descartadas en `front-fase1.md`.

| Alternativa | Por qué se descarta |
|---|---|
| **Monorepo** (backend + móvil + web) | Mismatch de CI/toolchain; favorece contract-drift implícito (§1). |
| **OpenAPI como fuente contractual inmediata** | No expresa los estados semánticos de la matriz (CONCEPTUAL / DEFINIDO / YA EXISTE). Contemplado como evolución, no reemplazo (§1). |
| **Estados `REFRESHING`/`TOKEN_EXPIRED`** | Modelarían una estrategia de refresh que Identity no ha definido (§3). |
| **`IN_FLIGHT → PENDING` al arrancar** | Reenvío automático de una operación de resultado desconocido; riesgo de duplicación (§6). |
| **Reintento automático de `AMBIGUOUS`** | Mismo riesgo; además oculta al operador que el resultado es desconocido (§6, §7). |
| **Clasificar timeout como `FAILED`** | Confunde fallo ambiguo con determinista (regla 2.6). |
| **Restaurar la ruta autenticada tras login** | El router afirmaría una validez de dominio que no puede garantizar (§8, Decisión B). |
| **Persistir `PendingIntent`** | Estado de intención sobreviviendo a sesiones; contradice §8 (§11 R2). |
| **Ruta `/boot`** | Añade una superficie que no es navegación; la espera se resuelve fuera del router (§10). |
| **G-1 (b): todo espera sesión** | Viola el invariante "las rutas públicas no esperan sesión" (§10). |
| **Guards de autorización de negocio** (`CanDeliverGuard`) | El JWT no lleva roles; la autoridad es backend (§9, P7). |
| **`/campaigns` contra endpoint PENDIENTE** | Construir contra un contrato inexistente (§9). |
| **`/home` opción (ii)** | Esconde `/operator/pending` tras un escaneo por una carencia contractual (§12). |
| **Polling de narrativa** | Mecanismo de reintento nuevo sin necesidad demostrada (§13). |
| **`ActionResolver` en `core/`** | Es lógica de dominio de PhysicalAsset, no transversal (§2). |

---

## 4. Consequences

### Positivas

- Los fallos ambiguos tienen salida explícita y nunca provocan reenvío automático (regla 2.6).
- Una sola fuente de autorización (backend), coherente con `paxfide-web` (ADR-046).
- Ningún deep link puede producir efectos en el Event Store (R3).
- El router no depende de dominio ni de Outbox: sus decisiones son verificables con una tabla de 4 × 4.

### Negativas y deuda aceptada

- **T-1 es provisional:** cualquier `401` con JWT expulsa al usuario; sin refresh habrá re-logins en campo.
- **La reconciliación de `AMBIGUOUS` no prueba causalidad** (D6).
- **Decisión B:** tras re-login el usuario vuelve a `/home`, no al recurso.
- **Ninguna acción operativa es ejecutable hoy:** formularios de comando bloqueados por contrato (D10) y, si se aprueba D11, envío bloqueado por H2.
- **`/donations` sin flujo productor** y Golden Path del donante no implementable en v1.

### Divergencia deliberada con `paxfide-web` (ADR-046)

| Tema | Móvil (este ADR) | Web (ADR-046) | Motivo |
|---|---|---|---|
| Comandos | Outbox persistente | Sin Outbox | Requisito offline solo en móvil |
| Estado de navegación | `NavigationRestoreState` | La URL | Plataforma |
| Retorno post-login | `PendingIntent` (solo deep link) | Destino en memoria para toda ruta (G-W2) | Plataforma |
| `/tracking/:trackingCode` | En v1, con H1 abierto | Deshabilitada (404) hasta H1 | En web la URL se expone a historial, logs del servidor, `Referer` y analítica; en móvil esos canales no aplican y H1 se limita a restauración y logs locales |

### Verificación (Definition of Done)

La estrategia de `front-fase1.md` §14: auditoría de salidas, tres niveles (lógica pura, estados de pantalla con `ApiClient` falso, flujos con backend falso) y las **aserciones negativas obligatorias** de §14. Evidencia: output literal del runner de tests, con el mismo estándar que Surefire en backend.

Las pruebas E2E contra backend real **no** forman parte del cierre mientras los contratos consumidos sigan sin implementación: producirían falsos positivos.

---

## 5. Pendientes registrados — NO decididos por este ADR

Ninguna opción de esta sección está elegida. Cada una se resuelve por enmienda a este ADR o por el ADR del dueño indicado.

| ID | Tema | Dueño | Efecto mientras siga abierto |
|---|---|---|---|
| **H1** | ¿Puede `/tracking/:trackingCode` (credencial bearer) entrar en `NavigationRestoreState`? + verificar contrato vigente (path vs. `Authorization: Bearer`) | Frontend + verificación backend | Afecta D5 y D7 |
| **H2** | Ownership del Outbox ante cambio de cuenta (incluido logout por T-1) | Frontend | Afecta D3, D5, D6; ver D11 |
| **N1** | Contrato "quién soy / capacidades" | Identity | `/home` provisional (D10) |
| **N2** | `register`, `split`, `from-donation`: ¿móvil o web? | Equipo | Sin rutas ni formularios en móvil |
| — | Contratos de `dispatch`/`receive` (CONCEPTUAL) y `deliver` (DEFINIDO, P7 pendiente) | PhysicalAsset | Formularios bloqueados (D10) |
| — | Infraestructura de enlaces: dominio canónico, Android App Links, iOS Universal Links, dominio verificado, fallback web | Infraestructura | D9 R5 sin host; `trackingCode` expuesto a reclamación de esquema no verificado |
| — | Creación de cuentas (donantes y empleados) | Identity | Sin origen implementable de cuentas autenticadas |
| — | Tecnología del `OutboxStore` y dependencias no decididas (D12) | Frontend | Enmienda antes de usarlas |

---

## 6. Fuera de este ADR

- `paxfide-web`: ADR-046.
- Estrategia de access/refresh token y semántica de `401/403`: Identity (ADR-038). Al definirse, T-1 se sustituye por enmienda.
- Identidad visual de la app móvil.
- Distribución (tiendas, firma de la app, entornos).

---

## 7. Condiciones de aprobación

| # | Condición | Estado |
|---|---|---|
| 1 | Fidelidad de D1–D10 y D12 respecto de `front-fase1.md` | PENDIENTE |
| 2 | Número del ADR (043 propuesto) | PENDIENTE |
| 3 | **D11** como decisión nueva: bloqueo de habilitación del `SyncEngine` hasta decidir H2 (aprobar, rechazar o sustituir por riesgo aceptado) | PENDIENTE |
| 4 | Aceptar que D12 deja dependencias sin decidir y que cada una exige enmienda antes de usarse | PENDIENTE |
| 5 | Fuente documental única: existen dos copias de `front-fase1.md` en la raíz (2026-09-27 01:17 y 01:19 UTC); por la convención del proyecto rige la más reciente. Confirmar que es la versión "cerrada a nivel de diseño" y retirar o marcar la otra como histórica | PENDIENTE |

**Resolución (2026-10-07):** Carlos aprueba el ADR con la §0. La condición 3 se resuelve sustituyendo D11 por la decisión de H2 (A1); la 4, autorizando las dependencias de A2; la 5, por la convención "rige la copia más reciente" (ver DDM-02).

Cuando las cinco estén cumplidas, el estado pasa a **APROBADO** sin nueva revisión técnica. Hasta entonces, `front-fase1.md` §17 conserva sus pendientes y este ADR no los da por resueltos.
