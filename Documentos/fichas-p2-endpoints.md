# Fichas breves — P2 (segunda autorización, 2026-10-07)

**Estado:** IMPLEMENTADAS en `feat/p2-endpoints` con `P2EndpointsHttpIntegrationTest` (9 tests, rojo antes y verde después). Las decisiones que no venían de un documento aprobado son `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` (DD-48 a DD-56). Ninguna respuesta expone `donorRef`, seudónimos, email ni ids internos de otra organización. Errores: `ProblemDetail` fijo de `ApiExceptionHandler`; el 403 es uniforme (DD-01).

## P2.1 — `GET /api/v1/me` (ficha N1, congelada, sin cambios)
- **Auth:** JWT. Sin JWT válido o cuenta `INACTIVE` → 401 (filtro).
- **200:** exactamente `accountId`, `organizationId`, `roles`, `platformAuthority`; nulos omitidos salvo `roles` (`[]` sin organización); roles ordenados. `Cache-Control: no-store`.
- **Vive en `api`** (solo usa el principal).

## P2.2 — `POST /api/v1/auth/register`
- **Auth:** pública (ya en `PublicRoutes`). **Sin `Command-Id`** (Identity no lo usa, DH-34): un reintento recibe 409.
- **Cuerpo:** `{email, password}`. **201:** `{accountId, status}` (matriz §3).
- **Errores:** email o contraseña vacíos → 400; email mal formado → 400 (`InvalidEmailFormat`, sin eco del email); email ya registrado → 409 `DuplicateEmail`.
- **Contraseña de al menos 12 caracteres** (H-P2-1, Carlos, 2026-10-07): regla del dominio (`PlainPassword`), también al cambiarla; si no, 400 `PasswordTooShort`.

## P2.3 — `GET /api/v1/organizations/{organizationId}/campaigns`
- **Auth:** `ADMINISTRATOR` de la organización (matriz §2b, `ConvocatoriaAuthorizationPolicy`).
- **200:** `{items: [{campaignRef, publicCode, title, status, visibility, currency?, targetAmount?, targetPolicy?, clearedAmount?, responsibles: [{accountId, actingRole}], assignedEmployeeCount}]}`. Importes como texto. Una página con tope de 100, sin `nextCursor` (DD-49, como DD-21).
- **Sin `fullName`** de los responsables: Carlos lo quitó del contrato v1 (H-P2-2, 2026-10-07; matriz §2b enmendada). Se da `actingRole`.

## P2.4 — `POST /api/v1/campaigns/{campaignRef}/close`
- **Auth:** `ADMINISTRATOR`, `Command-Id`. **200:** `{campaignRef, status: "CLOSED"}`.
- **`CLOSED` es terminal** (decisión delegada por Carlos en la autorización): cerrar otra vez → 409 `CampaignAlreadyClosed`; reenvío con el mismo `Command-Id` → la misma respuesta. Tras cerrar, asignar responsables da 409 (DD-05).

## P2.5 — CV-03 y retirar responsable
- `POST /api/v1/campaigns/{campaignRef}/administrators`, cuerpo `{administratorRef}`, **201** `{assignmentId}`. Mismas reglas que CV-02: ya responsable → 409; destinatario que no es `ADMINISTRATOR` de la organización → 409 `InvalidResponsibleRecipient`; `CLOSED` → 409.
- `POST /api/v1/campaigns/{campaignRef}/responsibles/{responsibleRef}/remove` (DD-50: `POST .../remove` y no `DELETE`, porque lleva cuerpo opcional y `Command-Id`). Cuerpo opcional `{replacementRef, replacementActingRole}`. **200** `{removedAssignmentId, replacementAssignmentId?}`.
  - último responsable sin reemplazo → 409 `LastResponsibleRemovalWithoutReplacement`;
  - reemplazo sin `replacementActingRole` → 400;
  - quien no es responsable activo → 409 `ResponsibleAssignmentNotFound` (DD-51: 409, no 404; la convocatoria es de la organización del actor y ya se autorizó).
- Retirar en una convocatoria `CLOSED` → 409 `ResponsibleAssignmentOnClosedCampaign` (H-P2-4, Carlos, 2026-10-07).

## P2.6 — `GET /api/v1/public/campaigns`
- **Auth:** pública. **Solo `?cursor=`** (Q-v2-3).
- **200:** `{items: [{publicCode, title, organizationName, status, startDate, endDate, acceptedDonationTypes, currency?, targetAmount?, clearedAmount?}], nextCursor?}`; 20 por página; sin `nextCursor` en la última; cursor inválido → 400 (T-35).
- **Nunca `PRIVATE_LINK`** (filtro en la consulta). Solo `OPEN` (DD-52: una cerrada se sigue viendo por su código, CV-07, pero no se descubre). Orden por `publicCode`; el cursor es el último `publicCode` en Base64 URL, que ya es público (DD-53).
- Sin `campaignRef` ni `organizationRef`; sin descripción (está en CV-07).

## P2.7 — activos y miembros de la organización
- `GET /api/v1/organizations/{organizationId}/physical-assets`: `ADMINISTRATOR` o `EMPLOYEE` (DD-54, como los fondos, DD-31). **200** `{items: [{assetRef, lifecycleStatus, currentCustodianRef, currentLocation, quantity, unitOfMeasure, campaignRef?}]}`, los campos de la matriz §4b; sin `donorRef`, finanzas ni genealogía. Del event store (incluye el Camino B); tope 200. Los activos v1 no llevan organización y no aparecen (no se infiere).
- `GET /api/v1/organizations/{organizationId}/members` (ID-12): `ADMINISTRATOR` o `REPRESENTATIVE` (DD-55). **200** `{items: [{accountId, roles, status}]}`. **Sin email** (PII). Otra organización o inexistente → el mismo 403.

## P2.8 — rechazar y pedir información (plataforma)
- `POST /api/v1/platform/organizations/{id}/reject` → **200** `{organizationId, verificationStatus: "REJECTED"}`.
- `POST /api/v1/platform/organizations/{id}/request-information`, cuerpo `{message}` (1–2000 caracteres) → **200** `{organizationId, verificationStatus: "NEEDS_MORE_INFORMATION"}`; mensaje vacío o largo → 400, **después** de autorizar (un no autorizado recibe 403, nunca 400).
- Sobre una organización `VERIFIED` o `REJECTED`, `verify`, `reject` y `request-information` → **409** `InvalidVerificationTransition` (DD-48, por instrucción de Carlos; es la regla de dominio de ADR-038). Inexistente → 404 (solo la ve la plataforma). Sin `Command-Id` (DD-07).

## Hallazgos
- **H-P2-1:** ~~sin política de contraseña~~ — **cerrado**: al menos 12 caracteres.
- **H-P2-2:** ~~`fullName` en la matriz~~ — **cerrado**: fuera del contrato v1.
- **H-P2-3:** el 409 del registro revela que un email ya existe (enumeración). Lo fija la matriz (`{accountId, status}` y dominio con `DuplicateEmailException`); mitigarlo exige un flujo de verificación de email que está PENDIENTE.
- **H-P2-4:** ~~retirar en `CLOSED` sin regla~~ — **cerrado**: 409.
