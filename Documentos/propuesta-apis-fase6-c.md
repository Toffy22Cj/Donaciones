# Propuesta y Estado de APIs — Fase 6 (v2, corregida)

**Fecha:** 2026-10-06
**Sustituye a:** `propuesta-apis-fase6.md` (v1). La v1 se conserva solo como antecedente: contradecía la ficha N1 congelada, generalizaba T-33 fuera de su alcance y omitía CV-07.
**Naturaleza:** inventario y propuesta de trabajo. **No es normativo**, no es un ADR, no modifica ninguna fuente y **no autoriza código**. Nada de lo que aquí aparece como "candidato" o "pregunta" está decidido.
**Fuentes leídas para esta versión:** fichas de contratos API Fase 6 (2026-10-04), `ficha-N1-quien-soy.md` (2026-10-05), `api-contract-matrix.md`, `hallazgos-front-fase2.md` (N1, R9, C2/H1), diseño de Convocatoria (ADR-037 + Enmienda 1, decisiones D1–D4), diseño de Identidad (ADR-038).
**Limitación declarada:** revisado **solo contra documentos**. Ningún estado de implementación de esta tabla se ha verificado contra el repositorio en esta sesión; donde se cita una divergencia de código, proviene de las fichas (§1.4, TR-D1) y no de una inspección nueva.
**Actualización aditiva (2026-10-07):** §6 registra las respuestas humanas autorizadas el 2026-10-06 y §7 corrige, **sin reescribir §1**, la columna de implementación contra el repositorio real. Donde §1 y §7 difieran sobre implementación, prevalece §7. Este archivo es el que vive en `Documentos/`; la copia `propuesta-apis-fase6-v2.md` de la raíz del proyecto queda superada por esta.

---

## 0. Cómo leer este documento

Cada endpoint tiene **tres estados independientes**. La v1 los mezclaba; aquí nunca se funden.

| Columna | Valores |
|---|---|
| **Contrato** | `CONGELADO*` (ficha congelada, pendiente de incorporación normativa vía Enmienda 1 de ADR-041) · `ABIERTO-dominio` (forma HTTP fijada, falta una regla de dominio de otro bloque) · `MATRIZ` (solo forma en `api-contract-matrix.md`, sin ficha) · `CONCEPTUAL` · `PENDIENTE` · `SIN CONTRATO` |
| **Implementación** | `Fase 3 fusionada` · `adelanto sin commit` · `ninguna` · `no verificado` |
| **Bloqueo / divergencia** | Lo que impide avanzar o lo que el código hace distinto del contrato |

> **Regla:** ningún endpoint está en estado CERRADO. DH-01 (prefijo `/api/v1`), DH-02 (`ProblemDetail`) y DH-51 (401/403) siguen siendo [DHR] en borrador hasta aprobar la Enmienda 1 de ADR-041.

---

## 1. Inventario consolidado

> Columna "Implementación" corregida en §7 (2026-10-07).

### 1.1 Identidad y autenticación

| ID | Endpoint | Contrato | Implementación | Bloqueo / divergencia |
|---|---|---|---|---|
| ID-01 | `POST /api/v1/auth/login` | CONGELADO* | adelanto sin commit | **ID01-D1:** `INACTIVE` → 403 (contradice [N] ADR-038 §2.6 y ADR-041 §2.5: los tres fallos deben dar el mismo 401). **ID01-D2:** campos vacíos → 401 (la ficha exige 400). **ID01-D3:** puerto `issueToken` en vez de `issue`. **ID01-D4:** fallo de `issue()` sin tratamiento verificado |
| N1 | `GET /api/v1/me` | CONGELADO* (2026-10-05) | ninguna | Dependencia de merge: `feat/identity-adr-038` aún no está en `develop`. Ver §2 |
| ID-12 | `GET /api/v1/organizations/{organizationId}/members` | ABIERTO-dominio | adelanto sin commit (solo DTO) | Falta `OrganizationMembersReadPort` en Identity (+ `app`) |
| — | `POST /api/v1/auth/register` | MATRIZ (dominio cerrado: `CreateAccountService`, Fase 4) | no verificado | Sin ficha. Response en matriz: `{accountId, status}` |
| — | Verificación de email | PENDIENTE | ninguna | Flujo completo sin diseñar (Identity) |
| R9 | Registro de `Organization` + `REPRESENTATIVE` inicial | **SIN CONTRATO** | ninguna HTTP (dominio `CreateOrganization` existe desde Fase 4) | `hallazgos-front-fase2.md` R9. Hoy es precondición por semilla. Ver §3.1 |

### 1.2 Plataforma

| Endpoint | Contrato | Implementación | Bloqueo |
|---|---|---|---|
| `POST /api/v1/platform/organizations/{id}/verify` | MATRIZ | no verificado | VERIFY sobre `VERIFIED` o `REJECTED` sin comportamiento definido (ADR-038) |
| `POST /api/v1/platform/organizations/{id}/reject` | MATRIZ | no verificado | Ídem |
| `POST /api/v1/platform/organizations/{id}/request-information` | MATRIZ | no verificado | Ídem; forma de `message` no verificada |
| `POST /api/v1/platform/administrators` | MATRIZ | no verificado | GRANT sobre cuenta ya `ADMINISTRATOR`: ¿no-op o excepción? Mecanismo de `PlatformAuthorityState` no verificado |
| `DELETE /api/v1/platform/administrators/{accountId}` | MATRIZ | no verificado | REVOKE sobre cuenta sin autoridad; GRANT+REVOKE concurrentes. **(Omitido en v1)** |
| Cola de organizaciones por verificar | **SIN CONTRATO** | ninguna | Ver §3.2. **(Omitido en v1)** |

### 1.3 Convocatoria (módulo de Carlos)

| ID | Endpoint | Contrato | Implementación | Bloqueo |
|---|---|---|---|---|
| CV-01 | `POST /api/v1/organizations/{organizationId}/campaigns` | MATRIZ ("diseño cerrado" = forma HTTP, **no** Application Service verificado) | ninguna | **Siguiente ficha a redactar.** Requiere `Command-Id` (T-33) |
| CV-02 | `POST /api/v1/campaigns/{campaignRef}/employees` | ABIERTO-dominio | adelanto sin commit (solo DTO) | Cuenta no `EMPLOYEE` / `INACTIVE` sin excepción nombrada; asignación sobre `CLOSED` sin regla |
| CV-03 | `POST /api/v1/campaigns/{campaignRef}/administrators` | ABIERTO-dominio | ninguna | Administrador ya responsable activo sin excepción nombrada; designación sobre `CLOSED` |
| CV-07 | `GET /api/v1/public/campaigns/{publicCode}` | MATRIZ | ninguna | Paso obligatorio del Golden Path. Compone `Convocatoria` + `CampaignFundingLedger` (sin quinto read model). **(Omitido en v1)** |
| — | `GET /api/v1/organizations/{organizationId}/campaigns` | MATRIZ (§2b) | no verificado | `organizationId` lo obtiene el cliente de N1 |
| — | Descubrimiento `GET /api/v1/public/campaigns` | PENDIENTE | ninguna | Ver §3.3 |
| — | `RemoveResponsible` | **SIN CONTRATO HTTP** (comando aprobado en dominio) | ninguna | Ver §3.4 |
| — | Cerrar convocatoria | **SIN CONTRATO HTTP** | ninguna | Ver §3.4 |
| — | Solicitud/aprobación de cambios de configuración (D3) | **SIN CONTRATO HTTP** | ninguna | Ver §3.4 |

### 1.4 Donación y pago

| ID | Endpoint | Contrato | Implementación | Bloqueo |
|---|---|---|---|---|
| CV-11 | `POST /api/v1/public/campaigns/{publicCode}/donation-intents` | PENDIENTE | ninguna | Proveedor no elegido; **requiere `Command-Id`** (ADR-037 E1 §3.5); D4 (varios medios). Ver §3.5 |
| — | Consulta de estado del `DonationIntent` | PENDIENTE | ninguna | `EXPIRED-UNKNOWN` ≠ `FAILED`. **(Omitido en v1)** |
| — | Confirmación manual de pago (transferencia/efectivo, D4) | **SIN CONTRATO** | ninguna | Ver §3.5. **(Omitido en v1)** |
| — | `POST /api/v1/webhooks/payments` | CONCEPTUAL | dominio `clearFundsGenesis` implementado | **Mayor severidad:** idempotencia de `clearFundsGenesis` ante retry no demostrada. Transacción compartida con `CampaignFundingLedger`. `providerEventId` sin índice único (depende del proveedor) |
| — | Entrega del `trackingCode` al donante | PENDIENTE | ninguna | Ver §3.6 (pregunta, no propuesta) |
| — | `GET /api/v1/account/donations` | MATRIZ ("reutiliza patrón") | **no existe** (v1 lo daba por existente) | Sin ficha; relación `accountId` ↔ `donorRef` del `Fund` no verificada |

### 1.5 `PhysicalAsset`

| Endpoint | Contrato | Implementación | Bloqueo |
|---|---|---|---|
| `POST /api/v1/physical-assets/from-donation` | MATRIZ (definido) | parcial | `HumanAccount` + integración P7 |
| `POST /api/v1/physical-assets/register` | MATRIZ (definido) | parcial | P7. Respuesta ante duplicado: Core da "no-op sin resultado" (R11/F3 abierto) |
| `POST /api/v1/physical-assets/{assetRef}/split` | MATRIZ (definido) | parcial | P7; ídem R11/F3 |
| `POST /api/v1/physical-assets/{assetRef}/dispatch` | CONCEPTUAL | método no expuesto | Extensión de `CommandType` |
| `POST /api/v1/physical-assets/{assetRef}/receive` | CONCEPTUAL | método no expuesto | Ídem |
| `POST /api/v1/physical-assets/{assetRef}/deliver` | MATRIZ (definido) | `deliverAsset` existe sin autorización | P7 |
| `GET /api/v1/physical-assets/{assetRef}` | MATRIZ (§4b) | ninguna | **F5:** `PhysicalAssetOperationalReadPort` es hueco de implementación. Excluye siempre `donorRef`, financiero y genealogía |

### 1.6 Tracking y narrativas

| ID | Endpoint | Contrato | Implementación | Bloqueo / divergencia |
|---|---|---|---|---|
| TR-01 | `GET /api/v1/donations/tracking` | CONGELADO* | Fase 3 fusionada | **TR-D1:** 404 sin `ProblemDetail`. Q-TR-1 exige enmendar ADR-024 y consolidar matriz §5 (no ejecutado) |
| TR-02 | `GET /api/v1/donations/tracking/narrative` | CONGELADO* | Fase 3 fusionada | Ídem (cuerpo del 404 no verificado) |
| TR-03 | `GET /api/v1/donations/tracking/assets/{assetRef}/history` | CONGELADO* | Fase 3 fusionada | Ídem |
| — | `GET /api/v1/public/campaigns/{publicCode}/narrative` | MATRIZ (definido) | ninguna | Contradicción `AuditFactsPort` / `CampaignAuditFactsPort` (ADR-040). Expone **narrativa**, no audit facts crudos |
| C2/H1 | Ruta web `/tracking/:trackingCode` | — | — | Sigue abierta en frontend (el código viaja en la URL del navegador) |

### 1.7 Fuera de HTTP (decisión ya cerrada)

Productor de `MerkleBatch`, `BlockchainAnchorScheduler`, `AnchorConfirmationPoller`, `IntegrityVerificationPort`: procesos internos, sin endpoint en el Golden Path (matriz §6). No se proponen endpoints nuevos para ellos.

---

## 2. Corrección explícita de N1

La v1 proponía `GET /api/v1/auth/me` con `email`, `fullName` y `status`, y afirmaba que N1 no tenía ficha. **Eso contradice una decisión congelada.** Rige `ficha-N1-quien-soy.md`:

| Elemento | Contrato congelado |
|---|---|
| Ruta | `GET /api/v1/me` (Q-N1-1) |
| Cuerpo | Exactamente `accountId`, `organizationId`, `roles`, `platformAuthority`. **Sin `email`, sin `fullName`, sin `status`** (Q-N1-2) |
| Nulos | `organizationId` y `platformAuthority` se omiten si son nulos; `roles` siempre presente, `[]` sin organización (Q-N1-3) |
| Caché | `Cache-Control: no-store` (Q-N1-4) |
| Cuenta `INACTIVE` | 401 — por eso un campo `status` solo podría valer `ACTIVE` y no aporta nada |
| Naturaleza | Sirve para **representar** la interfaz; no sustituye la autorización de cada endpoint |

Este documento no reabre ninguna de esas respuestas.

---

## 3. Huecos reales: candidatos y preguntas cerradas

Nada de esta sección está decidido. Cada punto termina en una pregunta (`Q-v2-n`) para el responsable humano o en la dependencia del bloque dueño.

> Las respuestas a estas preguntas están en §6. Esta sección se conserva sin alterar como catálogo original de preguntas (instrucción humana del 2026-10-06).

### 3.1 Registro de `Organization` (R9) — dueño: Identity

El dominio existe (`CreateOrganization(type, initialRepresentativeAccountId)`, Fase 4). Falta el flujo de producto.

- **Q-v2-1.** ¿Entra en el alcance de Fase 6 o se mantiene como precondición por semilla para la demo?
  - (a) Precondición por semilla; se documenta como deuda (R9 sigue abierto).
  - (b) Endpoint HTTP en Fase 6. En ese caso Identity debe definir antes quién lo inicia, con qué datos y cómo nace la cuenta del `REPRESENTATIVE` inicial (registro de cuenta + creación de organización en una sola operación o en dos).

### 3.2 Cola de verificación de plataforma — dueño: Identity

Existe `verify` pero ninguna lectura permite al Platform Admin saber qué verificar.

- **Q-v2-2.** ¿Se necesita una lectura del tipo `GET /api/v1/platform/organizations?verificationStatus=PENDING_VERIFICATION` (cursor T-35, `PlatformAuthorizationPolicy`)?
  - (a) Sí, como ficha nueva de Identity.
  - (b) No para la demo: el admin conoce los `organizationId` por la semilla.

### 3.3 Descubrimiento público — dueño: Convocatoria

Necesidad confirmada en la ronda de cierre de riesgos; contrato HTTP pendiente.

**Requisito no negociable (no es pregunta):** el listado **nunca** incluye convocatorias Privada-por-enlace. Si las incluyera, rompería la promesa de esa visibilidad.

- **Q-v2-3.** ¿El listado admite filtros de cliente?
  - (a) Sin filtros: solo `?cursor=`; el backend devuelve únicamente `OPEN` + pública (lo que ya implica `listPublicOpen`). **Recomendada.**
  - (b) Con `?status=`. En contra: ninguna fuente lo respalda, y exponer `CLOSED` en el descubrimiento es una decisión de producto nueva.
- **Q-v2-4.** Campos del ítem resumido: subconjunto de `ConvocatoriaReadModel` (matriz ADR-021-D). Se fija en la ficha, no aquí.

### 3.4 Comandos de Convocatoria sin endpoint — dueño: Convocatoria (Carlos)

Aprobados en dominio, sin forma HTTP:

| Comando | Fuente de dominio | Qué falta |
|---|---|---|
| `RemoveResponsible` (con reemplazo obligatorio si es el último activo) | ADR-037 | Ficha HTTP; `Command-Id` obligatorio |
| Cerrar convocatoria | ADR-037 E1 §3.5 (lista "cerrar convocatoria" entre los comandos con `commandId`) | Ficha HTTP; definir si `CLOSED` es terminal (no confirmado documentalmente) |
| Solicitud y aprobación de cambio de configuración (D3, D2) | Decisiones del 30 sept; aprobador ≠ solicitante | Requiere antes la enmienda de ADR-037 con D3/D4; luego dos fichas (solicitar / aprobar) |

- **Q-v2-5.** ¿Estos tres entran en Fase 6 o quedan fuera del Golden Path de la demo?

### 3.5 Flujo de pago — dueño: Convocatoria + `app` (proveedor externo)

Hechos ya fijados que la v1 ignoraba:

- Crear `DonationIntent` requiere `Command-Id` (ADR-037 E1 §3.5).
- Precondición `status = OPEN` al crear; una intención creada válida conserva su validez tras el cierre (D1).
- D4: varios medios (pasarela, transferencia, efectivo), habilitados por la organización; la confirmación la hace **quien recibió** el dinero.
- `EXPIRED-UNKNOWN` no se colapsa en `FAILED` (sabemos que falló ≠ no sabemos qué pasó).

Endpoints implicados:

| Candidato | Estado |
|---|---|
| Crear intención (`POST …/donation-intents`) | Pendiente de proveedor; la respuesta depende del medio elegido |
| Consultar estado de la intención | Sin contrato. Necesario para la pantalla post-checkout |
| Confirmación manual de pago (transferencia/efectivo) | Sin contrato. Autorización: miembro de la organización que recibió el pago (rol exacto sin fijar) |
| Webhook | Conceptual; bloqueado por la idempotencia de `clearFundsGenesis` |

- **Q-v2-6.** Para la demo, ¿qué medio se implementa primero?
  - (a) Solo pasarela simulada (sandbox).
  - (b) Solo confirmación manual (evita depender de un proveedor y del webhook bloqueado).
  - (c) Ambos.

### 3.6 Entrega del `trackingCode` — dueño: Convocatoria + Core

**Restricciones fijas:**
- ADR-021-C: ningún endpoint público **genera** el código. Un endpoint que lo calcule al pedirlo violaría esa regla; como mucho podría devolver uno ya calculado tras `clearFundsGenesis`.
- ADR-041 §2.7: el código es un secreto bearer; no va en logs ni en URLs externas.
- La respuesta del webhook nunca lo contiene (matriz §3).

- **Q-v2-7.** Mecanismo de entrega:
  - (a) Lectura post-checkout vinculada a la intención (p. ej. dentro de la consulta de estado del `DonationIntent`), protegida por un secreto de un solo uso entregado al navegador del donante al crear la intención. Ventaja: no requiere email. Riesgo: hay que diseñar ese secreto y su caducidad.
  - (b) Email. En contra: depende de la verificación de email (pendiente) y obliga a guardar el email del donante anónimo (PII).
  - (c) Solo para donantes con cuenta, vía `GET /account/donations`. En contra: deja sin código al donante anónimo, y la cuenta es opcional.

### 3.7 Detalle de la organización para `PanelHome` — dueño: Identity

`ficha-N1` §5 lo excluye expresamente: si `PanelHome` necesita el nombre o el estado de verificación de la organización, es otra ficha.

- **Q-v2-8.** ¿`PanelHome` necesita nombre/estado de la organización en v1? (Sí → ficha nueva; No → se queda con N1.)

### 3.8 Predicción (ADR-044, PROPUESTO) — dueño: componente predictivo

Debe mostrarse en web y en Flutter, visible solo para `ADMINISTRATOR`/`REPRESENTATIVE`, y para la entrega del jueves 8 puede ser simulada.

- **Q-v2-9.** ¿Cómo llega la predicción al cliente?
  - (a) Simulada en el frontend para la entrega; sin endpoint.
  - (b) Endpoint de solo lectura que sirve un artefacto precalculado. Requiere ficha y depende de aprobar ADR-044.

---

## 4. Reglas transversales (alcance corregido)

| Regla | Contenido | Alcance real |
|---|---|---|
| T-33 `Command-Id` | Header `Command-Id: <UUID>`; duplicado → mismo código y cuerpo; ausente/vacío/no-UUID → 400 | **Solo comandos de escritura de Convocatoria** (crear convocatoria, asignar, designar, retirar, cerrar, configurar, crear `DonationIntent`). Identity **sin** `commandId` (DH-34). Core: pendiente (no-op sin resultado). Login: no aplica. **(La v1 lo extendía a "todos los comandos": incorrecto)** |
| Sin deduplicación HTTP transversal | Prohibido `Idempotency-Key` genérico | Toda la API (ADR-041 §2.6) |
| T-36 transporte | `Authorization: Bearer`; JWT y `trackingCode` comparten header pero se separan **por ruta** (`/api/v1/donations/tracking/**`) | Nunca intercambiables |
| 401 / 403 | 401 = no autenticado, token inválido o cuenta `INACTIVE`; 403 = autenticado sin permiso. Sin ocultar recursos con 404 fuera de tracking | DH-51 |
| 401 uniforme | Mismo código **y cuerpo** en los tres fallos de login; y en todas las causas de 401 de tracking | ID-01; TR-01 a TR-03 |
| T-34 JSON | Instantes ISO-8601 UTC con `Z`; importes como String; `NON_NULL` | **Excepto tracking**, que conserva EF3 (montos `long`, nulos EF3); solo el `timestamp` de TR-03 adopta ISO con `Z` |
| Escala decimal | Abierta | Se fija en cada endpoint con importes |
| T-35 paginación | `?cursor=` opaco; sin `limit`; `{items, nextCursor}`; `nextCursor` omitido en la última página; cursor inválido → 400 | Toda lectura paginada |
| Errores | `ProblemDetail` (RFC 7807) | Toda la API |
| 409 | Conflicto de invariante; **el frontend no reintenta automáticamente** | Toda la API |
| Abiertos sin bloquear forma | `correlationId` (DH-04), CORS, OpenAPI, rate limiting (DH-56), 422/429/503 | — |

---

## 5. Orden de trabajo propuesto

1. **CV-01** (crear convocatoria) — ficha. Es tu módulo y abre el Golden Path de Convocatoria.
2. **CV-07** (detalle público) — ficha.
3. Respuestas a **Q-v2-6** y **Q-v2-7**: deciden si CV-11 y la entrega del `trackingCode` pueden avanzar sin proveedor.
4. Descubrimiento (**Q-v2-3**), con la exclusión de Privada-por-enlace ya fija.
5. Dependencias de otros bloques, que no son tuyas pero conviene pedir: `OrganizationMembersReadPort` (ID-12), excepciones nombradas de CV-02/CV-03, idempotencia de `clearFundsGenesis`, R9 y cola de verificación (Q-v2-1, Q-v2-2).

**Acciones derivadas que requieren autorización expresa (no ejecutadas):**
- Enmienda de ADR-024 y consolidación aditiva de la matriz §5 (Q-TR-1).
- Consolidación aditiva de la matriz con N1 (criterio de cierre 1 de la ficha).
- Corrección de las divergencias ID01-D1…D4 y TR-D1 en código (cada una con test de regresión que reproduzca la divergencia antes de corregirla).

---

## 6. Registro de respuestas humanas

**Alcance de este registro (declarado por Carlos, 2026-10-06):** estas respuestas son las únicas autorizadas para registrarse aquí. **No aprueban ninguna ficha ni autorizan código.** Este documento sigue siendo inventario/propuesta hasta que cada decisión pase por su mecanismo normativo (ficha, enmienda o ADR).

| Pregunta | Respuesta | Fecha | Efecto |
|---|---|---|---|
| Q-v2-1 | **(a) Semilla** | 2026-10-06 | R9 queda como deuda de producto. No se incorpora endpoint de registro de organización en Fase 6 |
| Q-v2-2 | **(b) Sin cola de verificación** | 2026-10-06 | No se añade el listado de organizaciones pendientes para la demo |
| Q-v2-3 | **(a) Sin filtros de cliente**; solo `?cursor=` | 2026-10-06 | Se fija únicamente la ausencia de filtros. **No** se congelan aquí los literales del enum de visibilidad (`PUBLIC` / `PRIVATE_BY_LINK` u otros): corresponden a la ficha. Sigue fijo el requisito de no listar nunca convocatorias Privada-por-enlace (§3.3) |
| Q-v2-4 | — | — | No era una decisión: los campos del ítem se fijan en la ficha de descubrimiento |
| Q-v2-5 | **Sí, entran en Fase 6, con precondiciones** | 2026-10-06 | "Entra en Fase 6" ≠ "listo para contrato". (1) **Cerrar convocatoria:** antes de cerrar su ficha debe resolverse si `CLOSED` es terminal. (2) **Solicitud/aprobación D3:** antes de redactar sus fichas debe enmendarse ADR-037, en el orden ya establecido: verificar `campaignRef` / comprobación contextual y cerrar los huecos de D4 |
| Q-v2-6 | **(a′) Webhook simulado** (opción añadida tras revisión; no estaba en la lista original de §3.5) | 2026-10-06 | No depende de que D4 sea normativa, no crea un endpoint de confirmación manual y conserva el camino de la matriz `webhook → clearFundsGenesis → CampaignFundingLedger`. **Sigue bloqueado** por la idempotencia de `clearFundsGenesis` ante retry; esta respuesta no autoriza saltarse ese bloqueo. La confirmación manual (D4) queda para después de que D4 sea normativa |
| Q-v2-7 | **Dirección (a), explícitamente NO congelada** | 2026-10-06 | Solo dirección de diseño, no contrato HTTP. Se resolverá junto con la consulta de estado del `DonationIntent`. Restricción mantenida: ningún endpoint público genera el `trackingCode` (ADR-021-C) |
| Q-v2-8 | **No: `PanelHome` no necesita datos de la organización en v1** | 2026-10-06 | No se amplía N1 ni se crea ficha de detalle de organización por este motivo |
| Q-v2-9 | **Mock server externo** (opción añadida tras revisión; no estaba en la lista original de §3.8) | 2026-10-06 | La predicción se sirve como JSON desde un mock server **fuera de `com.traceability`**, consumido por Flutter (requisito académico), sin contrato backend de predicción. La UI la identifica como **estimación simulada**, separada de los hechos verificables |

**Siguiente pieza de trabajo:** ficha CV-01. No espera a resolver el resto de los endpoints.

---

## 7. Actualización aditiva — estado real del repositorio (2026-10-07)

**Fuentes:** repositorio backend, rama `develop`, HEAD `0d4f428` (clon de solo lectura del 2026-10-07; **no se ejecutaron tests en esta sesión**); `Documentos/estado-fase6.md` (2026-10-07, `develop` en `e269985`, 829 tests en verde según su §0.2, ejecución de esa sesión, no de esta); `Documentos/auditoria-fase6-codigo-vs-documentacion.md` (2026-10-07). §1 se conserva tal como se redactó; esta sección corrige su columna de implementación.

### 7.1 Correcciones de implementación

| Fila(s) de §1 | §1 decía | Estado verificado | Evidencia |
|---|---|---|---|
| CV-01, CV-02, CV-03, retirar responsable, cerrar convocatoria, edición directa de configuración | ninguna / adelanto sin commit (solo DTO) | **Dominio, casos de uso y persistencia implementados** en el módulo `convocatoria`. **Sin capa HTTP**: `api` solo tiene los tres controllers de Fase 3 | `ConvocatoriaLifecycleService` (crear, editar, cerrar), `ResponsibleAssignmentService` (asignar, designar, retirar); PR #29 (`81cf87c`); `estado-fase6.md` §0, §2 |
| CV-11 (crear intención) y confirmación manual de `BANK_TRANSFER` | ninguna | Casos de uso implementados (`DonationIntentService`). La confirmación manual es solo para `ADMINISTRATOR` y nunca para pasarela (Enmienda 2 §3.1, **BORRADOR**). Sin contrato HTTP. La confirmación manual no es desplegable sin el índice de referencia de pago (`estado-fase6.md` §3bis) | `estado-fase6.md` §3bis. No cambia Q-v2-6 (webhook simulado para la demo) |
| Composición `app → convocatoria` | Se trataba como inexistente | **Existe** desde `e269985`, con `OrganizationVerificationAdapter` real (lee `Organization.verificationStatus` de `identity`) | `app/.../OrganizationVerificationAdapter.java`; `estado-fase6.md` §3bis |
| N1 | Dependencia de merge: `feat/identity-adr-038` no está en `develop` | **Dependencia resuelta**: ADR-038 fusionado (`aebedb2`); `AuthorizationPrincipal` en `develop` incluye `platformAuthority`. N1 sigue **sin implementación HTTP** | `contracts/.../AuthorizationPrincipal.java`; `estado-fase6.md` §0 |
| ID-01 | adelanto sin commit | **No existe en `develop`**: no hay `LoginController`, `JwtAuthFilter` ni `TokenIssuerPort`. Las divergencias ID01-D1…D4 describen un adelanto fuera de `develop` | Búsqueda en `api/` y en todo el repositorio; `estado-fase6.md` §0 ("JWT/autenticación HTTP" abierto) |
| Webhook | Idempotencia de `clearFundsGenesis` ante retry no demostrada | **Parcial**: idempotencia ante reenvío verificada (`ProcessedCommandIdempotencyIntegrationTest`); barrera `APPLY_FUNDS` implementada en `convocatoria` (Enmienda 2, BORRADOR). Siguen abiertos: T1 y P8 en `core`, orquestador, disparo y scheduler en `app`, P3 | `estado-fase6.md` §2, §3bis, §5 |
| CV-07 y descubrimiento | ninguna | `ConvocatoriaReadPort` **no existe** (CD-06). Existe la resolución interna `publicCode → campaignRef + organizationRef` (`DonationIntentService.resolvePublicCode`) | `convocatoria-resumen.md` §0.5 (CD-06); `handoff-convocatoria.md` |

### 7.2 Hechos documentales nuevos que afectan a contratos futuros (no son decisiones)

- **ADR-037 Enmienda 2 (BORRADOR, DH-C-1):** separa confirmar de aplicar fondos y añade el estado terminal `FUNDING_REJECTED`. Afecta a la futura ficha de consulta de estado del `DonationIntent` y a Q-v2-7.
- **Grafía `EXPIRED_UNKNOWN` (código) frente a `EXPIRED-UNKNOWN` (documentos)** (DH-C-8): afecta a cualquier contrato que exponga el estado de la intención.
- **Colisión de número ADR-043** (`estado-fase6.md` §0.1).
- **Colisión de número ADR-042** *(corregido el 2026-10-07; una versión anterior de esta línea decía, por error, que `ADR-042-frontend-web-paxfide-web.md` no existía)*. Ese ADR **existe** en el repositorio `Toffy22Cj/PaxFide` (rama `develop`, `Documentos/`), en estado APROBADO desde el 2026-09-28, y contiene la tabla de renumeración de Fase 6 (Convocatoria 037, Identidad 038, Blockchain 039, IA 040, APIs/Frontend 041, Frontend web 042). En el backend, el número 042 lo ocupa `ADR-042-orquestacion-centralizada-reintentos-proyeccion.md` (Fase 5, Aprobado). Resultado: **dos ADR distintos con el mismo número en dos repositorios**, igual que la colisión ADR-043. Renumerar uno de los dos es decisión humana pendiente. La renumeración 037–041 sí tiene fuente; falta registrarla en el backend (DH-C-3). Esta propuesta cita ADR-037 (Convocatoria) y ADR-041 (API), que existen con esos números en `Documentos/`.
- **Cuerpo HTTP de crear convocatoria "NO DEFINIDO"** y discrepancia de respuesta matriz/código (`handoff-convocatoria.md`, `auditoria-documental-convocatoria.md`): se trata en la ficha CV-01.

### 7.3 Lo que esta sección no hace

No cambia ninguna respuesta de §6; no modifica §3 ni §5 (instrucción humana del 2026-10-06); no aprueba fichas ni autoriza código.
