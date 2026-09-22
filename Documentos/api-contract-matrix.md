# API Contract Matrix v1 (no congelado)

**Estado:** Snapshot contractual de Fase 6, no un ADR. Conserva la HTTP Contract Matrix v1 derivada directamente de `golden-path.md`, con el estado de diseño/implementación de cada endpoint verificado contra el código en el momento de esta conversación.
**Para qué sirve:** referencia de trabajo para implementación de `api` y contrato de trabajo para el frontend — el frontend debe construirse contra este documento, no contra suposiciones extraídas de nombres de controllers.

---

## Leyenda de estado

- **DISEÑO CERRADO**: contrato conceptual completo, sin código todavía.
- **CONTRATO DEFINIDO / [bloqueador]**: contrato completo, implementación bloqueada por una precondición específica (ver `golden-path.md` §5).
- **CONTRATO CONCEPTUAL**: la forma general está clara pero faltan detalles (DTO exacto, proveedor externo) para congelarlo del todo.
- **YA EXISTE**: implementado desde una fase anterior, verificado.
- **PENDIENTE**: ni siquiera el contrato conceptual está resuelto.

---

## 1. Platform

| Endpoint | Auth | Domain op | Response | Estado |
|---|---|---|---|---|
| `POST /platform/organizations/{id}/verify` | JWT + `platformAuthority=ADMINISTRATOR` | `VERIFY_ORGANIZATION` | `{organizationId, verificationStatus}` | DISEÑO CERRADO |
| `POST /platform/organizations/{id}/reject` | ídem | `REJECT_ORGANIZATION` | ídem | DISEÑO CERRADO (fuera del Golden Path) |
| `POST /platform/organizations/{id}/request-information` | ídem | `REQUEST_ORGANIZATION_INFORMATION` + `message` | ídem | DISEÑO CERRADO (fuera del Golden Path) |
| `POST /platform/administrators` / `DELETE .../{accountId}` | JWT + `platformAuthority` | `GRANT_PLATFORM_AUTHORITY` / `REVOKE_PLATFORM_AUTHORITY` | `{accountId, platformAuthority}` | DISEÑO CERRADO (precondición de despliegue, no interacción en vivo del Golden Path) |

## 2. Convocatoria

| Endpoint | Auth | Domain op | Response | Estado |
|---|---|---|---|---|
| `POST /organizations/{id}/campaigns` | JWT + `ADMINISTRATOR` org | crear `Convocatoria` | `{campaignRef, publicCode, status, ...}` | DISEÑO CERRADO — módulo `convocatoria` sin código todavía |
| `POST /campaigns/{campaignRef}/employees` | JWT + `ADMINISTRATOR` org | asignar empleado | `{campaignRef, accountId, status}` | DISEÑO CERRADO |
| `GET /public/campaigns/{publicCode}` | pública | `ConvocatoriaReadPort.findPublicByCode` | `ConvocatoriaReadModel` (matriz ADR-021-D ya cerrada) | DISEÑO CERRADO |
| `GET /public/campaigns` (descubrimiento) | pública | `ConvocatoriaReadPort.listPublicOpen(cursor,limit)` | lista paginada | **PENDIENTE** — método mencionado, no diseñado en detalle |

## 3. Donación (anónima y autenticada — mismo contrato subyacente)

| Endpoint | Auth | Domain op | Response | Estado |
|---|---|---|---|---|
| `POST /public/campaigns/{publicCode}/donation-intents` | pública u opcional JWT | ninguna — solo prepara redirección a pasarela | `{paymentRedirectUrl / paymentSessionId}` | **PENDIENTE** — depende del proveedor de pago, no elegido |
| `POST /webhooks/payments` | firma del proveedor, sin JWT | `clearFundsGenesis` (`FundCommandService`, ya existe e implementado) + `CampaignFundingLedger` update, misma transacción | `200 OK` al proveedor. **El `trackingCode` NO forma parte de esta respuesta ni de ninguna respuesta al donante en este paso** — queda calculado y disponible internamente (ver fila siguiente) | CONTRATO CONCEPTUAL — DTO exacto depende del proveedor; dominio subyacente CERRADO E IMPLEMENTADO |
| *(canal de entrega del `trackingCode` al donante)* | — | — | — | **PENDIENTE** — momento de cálculo ya resuelto (inmediatamente tras `clearFundsGenesis`), mecanismo de entrega no. No es la respuesta del webhook. |
| `POST /auth/register` | pública | crear `Account` (`CreateAccountService`, existe desde Fase 4) | `{accountId, status}` | DOMINIO CERRADO — falta capa HTTP |
| *(verificación de email)* | — | — | — | **PENDIENTE** — flujo completo no diseñado |
| `POST /auth/login` | pública | `AuthenticateAccountPort` + `TokenIssuerPort` | `{token}` | CONTRATO CERRADO — puertos diseñados, implementación pendiente |
| `GET /account/donations` | JWT | `DonationReadPort` (existe, Fase 3), filtrado por `accountId` del `AuthorizationPrincipal` | lista de `DonationReadModel` | REUTILIZA PATRÓN EXISTENTE |

## 4. `PhysicalAsset`

| Endpoint | Auth | Domain op | Response | Estado |
|---|---|---|---|---|
| `POST /physical-assets/from-donation` | JWT + `EMPLOYEE` | `REGISTER_PHYSICAL_ASSET_FROM_DONATION` | `{assetRef, status, donationRef, campaignRef}` | CONTRATO DEFINIDO / **BLOQUEADO** — `HumanAccount` (`golden-path.md` §5.1) + integración P7 (§5.2) |
| `POST /physical-assets/register` | JWT + `EMPLOYEE`/`REPRESENTATIVE` backup | `REGISTER_PHYSICAL_ASSET` | ídem | CONTRATO DEFINIDO / integración P7 pendiente |
| `POST /physical-assets/{assetRef}/split` | ídem | `SPLIT_PHYSICAL_ASSET` | assets resultantes | CONTRATO DEFINIDO / integración P7 pendiente |
| `POST /physical-assets/{assetRef}/dispatch` | ídem | `DISPATCH_PHYSICAL_ASSET` (extensión de `CommandType`, ver §5.2) | `{assetRef, status}` | CONTRATO CONCEPTUAL — método ni siquiera expuesto en `PhysicalAssetCommandService` hoy |
| `POST /physical-assets/{assetRef}/receive` | ídem | `RECEIVE_PHYSICAL_ASSET` (extensión) | ídem | CONTRATO CONCEPTUAL — mismo estado que dispatch |
| `POST /physical-assets/{assetRef}/deliver` | ídem | `DELIVER_PHYSICAL_ASSET` (extensión) — método `deliverAsset` ya existe, sin autorización | ídem | CONTRATO DEFINIDO / integración P7 pendiente |

## 5. Tracking y narrativas

| Endpoint | Auth | Estado |
|---|---|---|
| `GET /tracking/{trackingCode}` | tracking credential (bearer HMAC) | **YA EXISTE** (Fase 3) |
| `GET /tracking/{trackingCode}/narrative` | ídem | **YA EXISTE** (Fase 3) |
| `GET /public/campaigns/{publicCode}/narrative` | pública | CONTRATO DEFINIDO — `CampaignAuditFactsPort` diseñado, implementación pendiente |

## 6. Operaciones internas (sin HTTP, decisión ya cerrada)

`MerkleBatch` Producer, `BlockchainAnchorScheduler`, `AnchorConfirmationPoller`, `IntegrityVerificationPort.verifyBatch/verifyAllAnchored` — ninguno expone endpoint en el Golden Path. Son procesos internos (scheduler-driven) o de invocación administrativa futura fuera de este alcance.

## 2b. Convocatoria — lectura administrativa (nuevo, cerrado durante el Frontend Contract)

| Endpoint | Auth | Read Model | Estado |
|---|---|---|---|
| `GET /organizations/{organizationId}/campaigns` | JWT + `ADMINISTRATOR` org + `OrganizationBoundaryPolicy` | `ConvocatoriaAdminReadModel` — distinto del público: `campaignRef, publicCode, title, status, visibility, targetAmount, targetPolicy, clearedAmount, responsables{accountId, fullName}, assignedEmployeeCount`, paginado | CONTRATO DEFINIDO |

No se añade `GET /campaigns/{campaignRef}` (detalle individual) — se decide si hace falta cuando el frontend descubra si el listado es suficiente.

## 4b. `PhysicalAsset` — QR y lectura operacional (nuevo, cerrado durante el Frontend Contract)

**Semántica de QR** (los tres tipos, payload = URL canónica, no solo el identificador):

| QR | Payload | Destino (ruta UX) | Acceso |
|---|---|---|---|
| Campaign | `publicCode` | `/c/{publicCode}` | Público |
| Tracking | `trackingCode` | `/tracking/{trackingCode}` | Público |
| Asset | `assetRef` | `/assets/{assetRef}` | Autenticado — entrada a acción de `EMPLOYEE`, no lectura pública |

`/assets/{assetRef}` es ruta UX cerrada; el contrato HTTP subyacente que resuelve qué acción mostrar es el siguiente. Renderización de la imagen PNG/SVG queda como implementación pendiente (frontend o endpoint de presentación puro, sin lógica de dominio) — no bloquea nada.

| Endpoint | Auth | Read Model | Estado |
|---|---|---|---|
| `GET /physical-assets/{assetRef}` | JWT + `EMPLOYEE` + `OrganizationBoundaryPolicy` | `PhysicalAssetOperationalReadModel` — `assetRef, lifecycleStatus, currentCustodianRef, currentLocation, quantity, unitOfMeasure, campaignRef`. Excluye explícitamente `donorRef`, datos financieros de `Fund`, genealogía de split (`parentAssetRef`/`rootAssetRef`) — el perímetro de privacidad no se relaja por estar autenticado, solo por necesidad operativa concreta | CONTRATO DEFINIDO |

`lifecycleStatus → acción disponible` (derivado, no es un campo nuevo): `REGISTERED→Dispatch`, `DISPATCHED→Receive`, `RECEIVED→Deliver`, `DELIVERED→solo lectura`.

---

## Frontend Contract / UX Flow — Golden Path: CERRADO

Mapeo completo de pantallas, rutas UX, estados y dependencias contractuales para las seis capas del recorrido (Platform Admin, Organización, Visitante público, Donante con cuenta, Empleado, Tracking/narrativa). Ningún elemento pendiente listado abajo justifica reabrir el mapa de pantallas — son contratos o implementaciones dentro de pantallas ya identificadas, no pantallas faltantes:

- Descubrimiento público de convocatorias (`GET /public/campaigns`)
- Proveedor y contrato HTTP final del flujo de pago (`donation-intent`, consulta de estado `PENDING/CONFIRMED/FAILED/EXPIRED-UNKNOWN`)
- Verificación de email
- Implementación del módulo `convocatoria`
- Integración P7 en `PhysicalAssetCommandService`
- Implementación de `dispatch`/`receive` (no expuestos hoy) y autorización de `deliver`
- Narrativa de convocatoria (`CampaignAuditFactsPort`)
- Generación de imágenes QR (PNG/SVG)

---

1. `api` es dueño de HTTP; controllers sin lógica de negocio.
2. Escrituras delegan en Application Services/Command Services existentes o nuevos.
3. Lecturas usan `ReadPort`/`ReadModel`; `api` nunca recibe documentos Mongo crudos.
4. JWT mínimo (`sub`, `iat`, `exp`, firma) — sin roles/organización/autoridad de plataforma.
5. `AuthorizationPrincipal` se resuelve desde Identity en cada request autenticada (efecto inmediato de cambios de rol/estado).
6. Tracking credential (HMAC) es distinto de JWT — no se mezclan mecanismos.
7. Webhooks son actores externos, nunca usuarios autenticados vía JWT.
8. Ningún endpoint nuevo introduce una decisión de dominio no aprobada en `convocatoria-resumen.md`, `identity-resumen.md`, `blockchain-resumen.md` o `ia-resumen.md`.

## Nota de procedencia y vigencia

Este documento refleja el estado conceptual y los hallazgos observados durante la revisión de Fase 6 en esta conversación. La implementación real (especialmente Fase 5, en curso por el equipo en paralelo) puede avanzar simultáneamente — **antes de considerar cualquier endpoint operativo, verificar contra el repositorio actual**, no asumir que los estados aquí anotados siguen vigentes. Mismo criterio que ya rige `golden-path.md`.
