# Referencia de la API v1

**Estado:** referencia de lo **implementado** en `develop` (2026-10-07), generada a mano desde los controladores y sus tests; sin OpenAPI (no se añaden dependencias). Las decisiones aún no ratificadas se marcan con su DD (`decisiones-delegadas-2026-10.md`). Contratos de origen: `api-contract-matrix.md`, fichas y planes citados en cada fila.

## 0. Reglas comunes

| Regla | Contenido |
|---|---|
| Base | `/api/v1`. JSON UTF-8. Instantes ISO-8601 UTC con `Z`. Importes como **texto** de dígitos en **unidades mínimas** de la moneda según ISO 4217 (Q-CV01-3; COP tiene exponente 2: `"100000"` son 1 000,00 COP), salvo en el seguimiento (EF3: `long`, las mismas unidades) |
| Autenticación | `Authorization: Bearer <JWT>` (`POST /auth/login`). Las rutas no públicas exigen JWT (deny-by-default, `PublicRoutes`). El seguimiento usa el `trackingCode` en el mismo header, solo en `/donations/tracking/**` |
| `Command-Id` | Header con un UUID en los comandos de escritura de `convocatoria` y `core` (T-33 revisado). Ausente o no-UUID → 400. Reenvío con el mismo id → misma respuesta; el mismo id para otro comando → 409 `CommandIdReusedForDifferentCommand`. Identity no lo usa |
| CORS (S-03) | Orígenes exactos por `TRACEABILITY_CORS_ALLOWED_ORIGINS` (lista separada por comas; nunca `*`; sin valor, ninguno). Sin credenciales. Cabeceras: `Authorization`, `Command-Id`, `Intent-Token`, `Content-Type`. Expone `Location`. Métodos `GET`, `POST`, `OPTIONS` (DD-57) |
| Errores | `ProblemDetail` (RFC 7807) con título fijo y sin eco de la entrada ni del mensaje interno |
| 400 | Solo validaciones con nombre (campo inválido, email mal formado, …). `IllegalArgumentException` genérica → 500 (sección 0 de la segunda autorización) |
| 401 | Sin JWT válido, o cuenta `INACTIVE` |
| 403 | **Uniforme** (DD-01): sin permiso, otra organización y recurso inexistente en rutas protegidas dan el mismo cuerpo |
| 404 | Rutas públicas por código (`publicCode`, `trackingCode`, intención) inexistente; organización inexistente solo para la plataforma |
| 409 | Conflicto de estado o de invariante; el cliente no reintenta automáticamente |
| Paginación | `{items, nextCursor?}`; `nextCursor` omitido en la última página; cursor inválido → 400 (T-35). Donde se indica "una página", hay tope fijo y nunca `nextCursor` |

## 1. Autenticación y cuenta

| Método y ruta | Auth | Cuerpo | Respuestas | Errores |
|---|---|---|---|---|
| `POST /auth/login` | pública | `{email, password}` | `200 {token}` | 400 campos vacíos; **el mismo 401** para email inexistente, contraseña errónea o cuenta inactiva |
| `POST /auth/register` | pública, sin `Command-Id` (DD-56) | `{email, password}` | `201 {accountId, status}` | 400 vacíos, email mal formado o contraseña de menos de 12 caracteres (`PasswordTooShort`); 409 `DuplicateEmail` |
| `GET /me` | JWT | — | `200 {accountId, organizationId?, roles, platformAuthority?}`, `Cache-Control: no-store` (ficha N1) | 401 |
| `GET /account/donations` | JWT | — | `200 {items: [{intentId, campaignTitle, amount, currency, status, trackingCode?}]}`, una página de 100 (DD-21) | 401 |

## 2. Plataforma

| Método y ruta | Auth | Cuerpo | Respuestas | Errores |
|---|---|---|---|---|
| `POST /platform/organizations/{organizationId}/verify` | JWT + autoridad de plataforma | — | `200 {organizationId, verificationStatus: "VERIFIED"}` | 403; 404 inexistente; 409 si ya `VERIFIED`/`REJECTED` (DD-48) |
| `POST /platform/organizations/{organizationId}/reject` | ídem | — | `200 {…, verificationStatus: "REJECTED"}` | ídem |
| `POST /platform/organizations/{organizationId}/request-information` | ídem | `{message}` (1–2000) | `200 {…, verificationStatus: "NEEDS_MORE_INFORMATION"}` | 403 (antes de validar); 400 mensaje vacío o largo; 404; 409 |
| `GET /platform/organizations` (cola de verificación, §3.1) | ídem | `?status=PENDING_VERIFICATION\|NEEDS_MORE_INFORMATION` (sin él, las dos), `?cursor=` | `200 {items: [{organizationId, name?, type, verificationStatus, informationRequest?}], nextCursor?}`, 20 por página, por orden de creación, `no-store`; sin miembros ni emails (DD-69) | 400 `status` o cursor (el del descubrimiento no vale aquí); 403 |

## 3. Organización (panel)

| Método y ruta | Auth | Respuestas | Errores |
|---|---|---|---|
| `POST /organizations` (crear organización, R9, §3.1) | JWT, cualquier cuenta activa sin organización; cuerpo `{type: FOUNDATION\|COMPANY, name}` (1–200) | `201 {organizationId, verificationStatus: "PENDING_VERIFICATION"}`; quien la crea queda como `REPRESENTATIVE` (DD-68) | 400 `type` o `name`; 409 `AccountAlreadyBelongsToOrganization` |
| `GET /organizations/{organizationId}/campaigns` | `ADMINISTRATOR` de la organización | `200 {items: [{campaignRef, publicCode, title, status, visibility, currency?, targetAmount?, targetPolicy?, clearedAmount?, responsibles: [{accountId, actingRole}], assignedEmployeeCount}]}`, una página de 100 (DD-49) | 403 |
| `GET /organizations/{organizationId}/members` | `ADMINISTRATOR` o `REPRESENTATIVE` (DD-55) | `200 {items: [{accountId, roles, status}]}`, sin email | 403 |
| `GET /organizations/{organizationId}/funds` | `ADMINISTRATOR` o `EMPLOYEE` (DD-31) | `200 {items: [{fundId, campaignRef?, currency, clearedAmount, availableAmount, allocations: [{allocationId, amount, status}]}]}`, una página de 200; sin `donorRef` | 403 |
| `GET /organizations/{organizationId}/physical-assets` | `ADMINISTRATOR` o `EMPLOYEE` (DD-54) | `200 {items: [{assetRef, lifecycleStatus, currentCustodianRef, currentLocation, quantity, unitOfMeasure, campaignRef?}]}`, una página de 200; sin `donorRef` | 403 |
| `GET /organizations/{organizationId}/campaigns/{campaignRef}/prediction` | `ADMINISTRATOR` o `REPRESENTATIVE` (DD-43) | `200 {kind: "ESTIMATE", modelVersion, warning, available, probabilityReachTarget?, estimatedFinalPctOfTarget?, pctTimeElapsed?, warnings?, unavailableReason?, unavailableText?, asOf}`, `no-store`. Solo lectura. STRICT → `available: false`, `STRICT_POLICY_EXCLUDED`; moneda distinta de COP → `UNSUPPORTED_CURRENCY` (ADR-044 Enmienda 1, BORRADOR) | 403 |

## 4. Convocatoria (administración)

Todas con JWT + `ADMINISTRATOR` de la organización de la convocatoria y `Command-Id`.

| Método y ruta | Cuerpo | Respuestas | Errores |
|---|---|---|---|
| `POST /organizations/{organizationId}/campaigns` (CV-01) | `{title, description?, visibility, startDate, endDate, configuration: {acceptedDonationTypes, acceptedPaymentMethods?, currency?, targetAmount?, targetPolicy?, onTargetReached?}}` | `201 {campaignRef, publicCode}` | 400 campos (con nombre); 403; 409 `OrganizationNotVerified` |
| `POST /campaigns/{campaignRef}/employees` (CV-02) | `{employeeRef}` | `201 {assignmentId}` | 400; 403; 409 ya asignado, destinatario inválido, `CLOSED` |
| `POST /campaigns/{campaignRef}/administrators` (CV-03) | `{administratorRef}` | `201 {assignmentId}` | ídem |
| `POST /campaigns/{campaignRef}/responsibles/{responsibleRef}/remove` (DD-50) | opcional `{replacementRef, replacementActingRole}` | `200 {removedAssignmentId, replacementAssignmentId?}` | 400 reemplazo sin `replacementActingRole`; 403; 409 último responsable, no responsable (DD-51), convocatoria `CLOSED` |
| `POST /campaigns/{campaignRef}/close` | — | `200 {campaignRef, status: "CLOSED"}` | 403; 409 `CampaignAlreadyClosed` (`CLOSED` es terminal) |

## 5. Convocatoria y donación (público)

| Método y ruta | Auth | Cuerpo | Respuestas | Errores |
|---|---|---|---|---|
| `GET /public/campaigns` | pública | `?cursor=` | `200 {items: [{publicCode, title, organizationName, status, startDate, endDate, acceptedDonationTypes, currency?, targetAmount?, clearedAmount?}], nextCursor?}`; 20 por página; cursor opaco (DD-58); solo `PUBLIC` y `OPEN`, **nunca `PRIVATE_LINK`** (DD-52) | 400 cursor |
| `GET /public/campaigns/{publicCode}` (CV-07) | pública | — | `200 {organizationName, title, description?, status, startDate, endDate, acceptedDonationTypes, acceptedPaymentMethods?, currency?, targetAmount?, clearedAmount?}` | 404 |
| `GET /public/campaigns/{publicCode}/narrative` | pública | — | `200 {status: "AVAILABLE", content, source: "LLM_GENERATED", facts}`; `202 {status: "PENDING", facts}`; `200 {status: "UNAVAILABLE", content: "Narrativa no disponible", facts}`. `facts = {status, currency?, targetAmount?, clearedAmount?, unitsDelivered, distinctRecipients}` (B5, DD-39) | 404 |
| `POST /public/campaigns/{publicCode}/donation-intents` (CV-11) | JWT **opcional**, `Command-Id` | `{amount, currency, paymentMethod}` | `201 {intentId, statusToken, paymentRedirectUrl}`; un reenvío emite un `statusToken` nuevo y anula el anterior (DD-18 sustituida) | 400; 404; 409 reglas de la convocatoria |
| `GET /public/donation-intents/{intentId}` | header `Intent-Token` | — | `200 {status, trackingCode?}` (el código, solo con fondos aplicados) | 404 (token inválido o caducado, igual que inexistente) |
| `POST /webhooks/payments` | firma `X-Simulated-Signature`; **solo** con `traceability.demo.simulated-payments=true` (perfil `dev`) | `{type: "payment.confirmed"\|"payment.failed", paymentSessionId, providerEventId, amount, currency}` | `200` sin cuerpo | 401 firma; 400 campos; 404 sesión; 409 evento incoherente |

## 6. Activos físicos

Todas con JWT; `EMPLOYEE` de la organización salvo indicación; las escrituras con `Command-Id`.

| Método y ruta | Cuerpo | Respuestas | Errores |
|---|---|---|---|
| `POST /physical-assets/from-donation` (Camino B) | `{assetType, quantity, unitOfMeasure, custodianRef, currentLocation, campaignRef?}` | `201 {assetRef, status, donationRef, campaignRef?}` | 400; 403; 409 convocatoria no elegible |
| `POST /physical-assets/register` (Camino A) | `{fundId, assetType, quantity, unitOfMeasure, custodianRef, currentLocation, allocationId, sourceAllocationId?}` | `201 {assetRef, status, campaignRef?}` | 400; 403; 409 |
| `POST /physical-assets/{assetRef}/split` | `{quantity}` | `202 {parentAssetRef, childAssetRef, status: "PENDING"}` + `Location` | 400; 403; 409 |
| `GET /physical-assets/{parentAssetRef}/splits/{childAssetRef}` | — | `200 {status}` | 403; 404 |
| `POST /physical-assets/{assetRef}/dispatch` | `{carrierRef}` | `200 {assetRef, status: "DISPATCHED"}` | 400; 403; 409 |
| `POST /physical-assets/{assetRef}/receive` | `{facilityLocation, receiverRef}` | `200 {assetRef, status: "RECEIVED"}` | ídem |
| `POST /physical-assets/{assetRef}/deliver` | `{finalCustodianRef, beneficiaryRef, locationRef, evidenceRef}` | `200 {assetRef, status: "DELIVERED"}` | ídem |
| `GET /physical-assets/{assetRef}` | — | `200 {assetRef, lifecycleStatus, currentCustodianRef, currentLocation, quantity, unitOfMeasure, campaignRef?}` | 403 (también inexistente) |

## 7. Fondos (Camino A)

| Método y ruta | Auth | Cuerpo | Respuestas | Errores |
|---|---|---|---|---|
| `POST /funds/{fundId}/allocations` | `ADMINISTRATOR`, `Command-Id` | `{amount}` | `201 {allocationId, status: "REQUESTED"}` (id determinista, DD-29) | 400; 403 (también fondo inexistente, DD-30); 409 importe mayor que el disponible, `Command-Id` reutilizado |
| `POST /funds/{fundId}/allocations/{allocationId}/confirm` | ídem | — | `200 {allocationId, status: "CONFIRMED"}` (DD-32) | 403; 409 |

## 8. Seguimiento del donante

Header `Authorization: Bearer <trackingCode>`; cualquier fallo de código → el mismo 404 con `ProblemDetail` fijo (TR-D1).

| Método y ruta | Respuestas |
|---|---|
| `GET /donations/tracking` | `200 {financialSnapshot: {currency, originalAmount, clearedAmount, pendingAllocationAmount, confirmedAllocationAmount}, campaignRef?, logistics: [{assetRef, lifecycleStatus, assetType, unitOfMeasure, quantity, locationZone}]}` (EF3) |
| `GET /donations/tracking/narrative` | `200 {status: "PENDING"\|"AVAILABLE", content?, source?}` |
| `GET /donations/tracking/assets/{assetRef}/history` | `200 {history: [{eventType, timestamp, locationZone, custodianCategory, status}]}` |

## 9. Sin HTTP

`MerkleBatch`, `BlockchainAnchorScheduler`, `AnchorConfirmationPoller` e `IntegrityVerificationPort.verifyBatch` son procesos internos (matriz §6). El anclaje en testnet y su evidencia: `runbook-anclaje-testnet.md`.
