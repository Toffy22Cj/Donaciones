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
| Campos opcionales | En las respuestas, `campo?` **puede faltar o llegar `null`** según el endpoint; el cliente trata los dos casos igual. Un campo sin `?` siempre llega y no es `null` (revisión de nulabilidad del 2026-10-08 contra el código) |
| Paginación | `{items, nextCursor?}`; `nextCursor` omitido en la última página; cursor inválido → 400 (T-35). Donde se indica "una página", hay tope fijo y nunca `nextCursor` |

*Nota (2026-10-25, DD-57 ampliada; plan CORS, #138):* CORS admite además **`PUT` y `DELETE`** (métodos: `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS`; sin `PATCH`), para que la web de otro origen pueda subir y quitar el logo (`PUT`/`DELETE /organizations/{organizationId}/logo`). El resto de la fila de CORS no cambia. La fila de arriba no se reescribe.

## 1. Autenticación y cuenta

| Método y ruta | Auth | Cuerpo | Respuestas | Errores |
|---|---|---|---|---|
| `POST /auth/login` | pública | `{email, password}`; el email no distingue mayúsculas ni cuenta los espacios de los extremos (DD-74) | `200 {token}` | 400 campos vacíos; **el mismo 401** para email inexistente, contraseña errónea o cuenta inactiva |
| `POST /auth/register` | pública, sin `Command-Id` (DD-56) | `{email, password}`; el email se guarda sin espacios en los extremos y en minúsculas, así que ` Ana@x.org` y `ana@x.org` son la misma cuenta (409) (DD-74) | `201 {accountId, status}` | 400 vacíos, email mal formado o contraseña de menos de 12 caracteres (`PasswordTooShort`); 409 `DuplicateEmail` |
| `GET /me` | JWT | — | `200 {accountId, organizationId?, roles, platformAuthority?}`, `Cache-Control: no-store` (ficha N1) | 401 |
| `GET /account/donations` | JWT | — | `200 {items: [{intentId, campaignTitle?, amount, currency, status, trackingCode?}]}` (`campaignTitle` solo falta si la convocatoria no se encuentra; rama defensiva), una página de 100 (DD-21) | 401 |
| `GET /account` (ficha L-FRONTS, A3; M-26) | JWT | — | `200 {email}`, `Cache-Control: no-store`: el correo de la propia cuenta (la del JWT; sin parámetro de ruta), ya normalizado (DD-74). Con o sin organización. Nunca en un log. `GET /me` sigue sin email (ficha N1) | 401 |
| `GET /me/campaigns` (§3.4) | JWT | — | `200 {items: [{campaignRef, publicCode, title, status, actingRole, assignedAt}]}`: asignaciones activas de quien llama en su organización actual y, de las convocatorias `CLOSED`, sus asignaciones históricas (DD-72, D-06); una página de 100, `no-store` | 401 |
| `POST /invitations/accept` (§3.3, ADR-049 D4) | JWT de la cuenta invitada | `{token}` en el cuerpo (la web lo lee del fragmento `#token=` y lo borra de la barra); **nunca** en la URL | `200 {organizationId, roles}`; efectivo en la petición siguiente | 401; **el mismo 403 `InvitationNotAcceptable`** (desconocido, caducado, revocado, usado, otro email o token en la URL); 409 `AccountAlreadyBelongsToOrganization` |

*Nota (2026-10-22, ficha L-FRONTS A2; Enmienda 5 de ADR-037; M-15/M-16):* cada ítem de `GET /account/donations` lleva además:
- **`createdAt?`**: ISO-8601 UTC; falta en las intenciones anteriores a la enmienda;
- **`confirmedAt?`**: solo en `CONFIRMED` y `FUNDING_REJECTED`;
- **`organizationName?`**: falta si la organización no tiene nombre, DD-04;
- **`paymentMethod`**: `GATEWAY`, `BANK_TRANSFER` o `CASH`; siempre;
- **`publicCode?`**: **solo** si la convocatoria es `PUBLIC` al leer; una `PRIVATE_LINK` nunca lo trae (ADR-041 §2.7).

*Nota (2026-10-25, M-21 "Mi impacto"; plan de cierre §M-21, aprobado en #147):* ruta nueva **`GET /account/impact`**, con JWT de cualquier cuenta y `Cache-Control: private, no-store`. Responde `200 {donationsCount, totals: [{currency, amount}], campaignsSupported, deliveredUnits: [{unitOfMeasure, quantity}], truncated}`:
- `donationsCount`: las donaciones `CONFIRMED` de la cuenta (DD-46).
- `totals`: su suma **por moneda**, en unidades mínimas y como texto; nunca se suman monedas distintas.
- `campaignsSupported`: las convocatorias distintas de esas donaciones.
- `deliveredUnits`: las cantidades de los activos `DELIVERED` de los fondos de esas donaciones, sumadas **por unidad de medida** y como texto. Cada activo cuenta una vez. **Se presentan como "unidades entregadas en las convocatorias que apoyaste"**, no como unidades atribuidas al donante (P-M21-4).
- `truncated`: `true` si la cuenta tiene más de 100 donaciones, el tope de DD-21. Solo cuentan las 100 más recientes.

Una cuenta que nunca donó recibe ceros y listas vacías, y su seudónimo no se crea. Nunca salen el seudónimo, el `donorRef`, el `fundId`, el `assetId` ni el `campaignRef`. Errores: 401.

Orden: **`createdAt` descendente**, con las intenciones sin `createdAt` al final (enmienda de DD-21). La página sigue siendo de 100, sin `nextCursor`, y trae las 100 más recientes. Ninguna respuesta lleva `organizationRef` ni `donorRef`. Las filas de arriba no se reescriben.

*Nota (2026-10-24, ADR-050 D4 y D5; plan R-3 + R-2, `feat/identity-r3-r2-revocacion-contrasena`):* ruta nueva y revocación de sesiones. Las filas de arriba y la regla 401 de §0 no se reescriben; esta nota las amplía.

| Método y ruta | Auth | Cuerpo | Respuestas | Errores |
|---|---|---|---|---|
| `POST /account/password` | JWT, sin `Command-Id` (Identity no lo usa) | `{currentPassword, newPassword}` | `200 {token}`, `Cache-Control: no-store`. El token sale con `iat = credentialsNotBefore` y `exp = iat + 8 h`: **la sesión actual sigue con el token nuevo** y todas las demás quedan revocadas. El cliente sustituye el token guardado | 400 campo vacío (sin llamar al dominio); **400 `CurrentPasswordIncorrect`**, nunca 401 (ADR-043 T-1); 400 `PasswordTooShort` (menos de 12). En los tres no cambia nada. Si la actual es incorrecta **y** la nueva es corta, gana `CurrentPasswordIncorrect` (orden de ADR-050 D5). 401 sin JWT válido |

- **Revocación (ADR-050 D4):** cambiar la contraseña y desactivar la cuenta fijan `credentialsNotBefore = max(segundo actual truncado, valor anterior) + 1 s`. Un token con `iat` anterior recibe **el mismo 401** que cualquier otro rechazo. Reactivar no deshace la revocación: la persona inicia sesión de nuevo. Las cuentas que nunca cambiaron nada no tienen `credentialsNotBefore` y sus tokens valen hasta `exp`.
- **Token sin `iat`:** 401 (el emisor siempre lo escribe).
- **500 tras confirmar (ADR-038 #7):** si el cambio se confirma y falla la emisión del token, la respuesta es 500. **La contraseña sí cambió** y todas las sesiones quedaron revocadas, también la actual: la web y el móvil deben llevar a iniciar sesión con la contraseña nueva.
- **Defecto conocido (P-R3-3, decisión de Manuel del 2026-10-24 por la delegación de Carlos):** con el `+ 1 s` y el `max` de D4, `credentialsNotBefore` puede quedar uno o varios segundos en el futuro, y un inicio de sesión en ese intervalo recibe un token que el filtro rechaza (401). Con `POST /account/password` no pasa, porque el token llega en la respuesta. Afecta a quien inicie sesión justo después de cambiar la contraseña, de una desactivación o, con R-4, de una recuperación, donde sería el flujo normal. **Se corrige antes de R-4**, en un PR propio, tras la Enmienda 1 de ADR-050 D4 (el login emitirá con `iat = max(segundo actual truncado, credentialsNotBefore)`). Hasta entonces, el cliente que reciba ese 401 puede repetir el inicio de sesión un segundo después.
  - *Nota (2026-10-25, plan P-R3-3; ADR-050 Enmienda 1, E1-1 y E1-2):* **corregido.** `POST /api/v1/auth/login` emite ahora con `iat = max(segundo actual truncado, credentialsNotBefore)`, y un inicio de sesión justo después de cambiar la contraseña, de una desactivación o de una recuperación recibe un token que vale. Ya no hace falta repetirlo un segundo después. El párrafo de arriba se conserva.
- **Riesgo registrado (R-R3-1, pregunta para Carlos):** la ruta no limita los intentos; quien tenga un token robado puede probar contraseñas actuales.

*Nota (2026-10-25, ADR-050 D6 y Q3 (a); plan D6; `feat/identity-d6-desactivacion`):* desactivar la propia cuenta. Ruta nueva; las filas de arriba no se reescriben. D7 (reactivación por la plataforma) no está.

| Método y ruta | Auth | Cuerpo | Respuestas | Errores |
|---|---|---|---|---|
| `POST /account/deactivate` | JWT; solo la cuenta del JWT; sin `Command-Id` | `{currentPassword}` | `204` sin cuerpo. La cuenta pasa a `INACTIVE` y **todos sus tokens quedan revocados**, también el de esta petición (D4): la siguiente petición con cualquiera de ellos da 401. No se borra nada y **la membresía de la organización no cambia** (D6); la organización la retira con la operación de siempre | 400 `currentPassword` vacío (sin llamar al dominio); **400 `CurrentPasswordIncorrect`**, nunca 401; **409 `PlatformAdministratorDeactivation`** (administrador de plataforma); **409 `RepresentativeTransferRequired`** (representante de su organización: antes tiene que transferir el rol); **409 `ActiveCampaignResponsible`** (responsable activo de una convocatoria no cerrada). En todos esos casos la cuenta sigue `ACTIVE` y nada cambia. Orden de las comprobaciones: contraseña, administrador, representante, responsable. 401 sin JWT válido |

- **Sin vuelta atrás por la API:** la reactivación (D7) está pendiente de Carlos. Una cuenta desactivada por error solo se recupera fuera de la API.
- **Riesgo registrado (R-R3-1, como en `/account/password`):** la ruta no limita los intentos de contraseña.

*Nota (2026-10-25, ADR-050 D1, D2 y D10; plan R-5 + D1, primera parte; `feat/identity-r5-aceptaciones-perfil`):* aceptaciones legales, perfil y minimización. Las filas de arriba y la de `POST /organizations` (§3) no se reescriben; esta nota las amplía.

| Método y ruta | Auth | Cuerpo | Respuestas | Errores |
|---|---|---|---|---|
| `POST /auth/register` (ampliado) | pública | `{email, password, acceptedTermsVersion, acceptedDataPolicyVersion}` | `201 {accountId, status}`, como antes. La cuenta guarda cada versión con su fecha | Además de los de antes: **400** (`title` `BadRequest`) si falta alguna de las dos versiones o no es la vigente, **sin cuenta creada**. El campo va en el log, nunca el valor del correo |
| `POST /organizations` (ampliado) | JWT, como antes | `{type, name, acceptedOrganizationTermsVersion}` | `201`, como antes. La organización guarda la versión, la cuenta que la aceptó y la fecha | Además: **400** si falta la versión o no es la vigente, **sin organización** |
| `GET /account/profile` | JWT; solo la cuenta del JWT | — | `200 {email, givenName?, familyName?}`, `Cache-Control: no-store` | 401 |
| `PUT /account/profile` | JWT; solo la cuenta del JWT; sin `Command-Id` | `{givenName?, familyName?}`; se recortan los espacios; `null` o ausente **borra** el campo | `200` con el mismo cuerpo que el `GET`, `no-store` | **400** si un campo queda vacío tras recortar o pasa de **100 puntos de código** (un emoji cuenta uno); nada cambia. 401 |

- **Versiones vigentes:** hoy son `v1-2026-10` las tres (términos, autorización de datos y términos de organización). Fuera de `dev`, el servidor no arranca sin `TRACEABILITY_LEGAL_TERMS_VERSION`, `TRACEABILITY_LEGAL_DATA_POLICY_VERSION` y `TRACEABILITY_LEGAL_ORGANIZATION_TERMS_VERSION`. Los textos los publica la web (`paxfide-web/Documentos/legal/`); el backend solo guarda versiones.
- **El nombre es privado:** no aparece en `GET /me`, `GET /account`, la lista de miembros, `GET /organizations/{id}`, `GET /me/campaigns`, las rutas públicas ni los logs. La auditoría `PROFILE_UPDATED` guarda solo qué campos cambiaron (`{fields: […]}`), nunca los valores.
- **D10:** la auditoría `ACCOUNT_CREATED` ya no guarda el correo (`{selfRegistration: true}`). Las entradas antiguas no se reescriben.
- **El login no cambia en esta parte:** exigir las aceptaciones vigentes en el login (D3, `403 AcceptanceRequired`) va en su propio PR.

*Nota (2026-10-25, ADR-050 D3 y Q1 (a); Enmienda 1, parte 3; `feat/identity-d3-aceptaciones-login`):* el login exige las aceptaciones vigentes. La fila de `POST /auth/login` de arriba y la viñeta de esta nota anterior no se reescriben; esta nota las amplía.

| Método y ruta | Auth | Cuerpo | Respuestas | Errores |
|---|---|---|---|---|
| `POST /auth/login` (ampliado) | pública | `{email, password, acceptedTermsVersion?, acceptedDataPolicyVersion?}` | `200 {token}`, como antes, si la cuenta está al día o si el cuerpo trae **exactamente** las versiones vigentes que le faltan; en ese caso se guardan con la fecha | **`403`** (`application/problem+json`, `title` `AcceptanceRequired`) con `{pendingTermsVersion?, pendingDataPolicyVersion?}` (solo las pendientes) y **sin token**, si la contraseña es correcta pero falta alguna versión y el cuerpo no la trae o trae otra; no se guarda nada. **El 401 no cambia**: con una contraseña incorrecta, un correo inexistente o una cuenta inactiva, el mismo 401 de siempre, **nunca 403** |

- **Flujo para la web y el móvil:** si el login da 403 `AcceptanceRequired`, mostrar los textos de las versiones pendientes y repetir el login con `acceptedTermsVersion` y `acceptedDataPolicyVersion` en el cuerpo. El 403 llega solo después de comprobar la contraseña.
- **Al desplegar, todas las cuentas** sin las versiones vigentes (las anteriores a R-5 y las de la semilla) reciben 403 en su siguiente login hasta aceptar. Las que se registraron con las versiones vigentes no notan nada.
- **Los tokens ya emitidos** siguen valiendo hasta su `exp` (riesgo aceptado 4 de ADR-050). El filtro no cambia.

*Nota (2026-10-25, ADR-050 D8 y Q5 (a); plan R-4, `feat/identity-r4-recuperacion-contrasena`):* recuperación de la contraseña. Dos rutas nuevas, **públicas**, sin `Command-Id` (DH-34). Las filas de arriba no se reescriben.

| Método y ruta | Auth | Cuerpo | Respuestas | Errores |
|---|---|---|---|---|
| `POST /auth/password-reset` | pública | `{email}` | **Siempre el mismo `202` sin cuerpo**, con `Cache-Control: no-store`: exista o no la cuenta, esté inactiva, el correo esté mal formado o se repita la petición antes de 5 minutos. Solo una cuenta existente y `ACTIVE`, fuera del enfriamiento, recibe un correo | 400 `email` vacío |
| `POST /auth/password-reset/confirm` | pública | `{token, newPassword}` | `204`, `no-store`. **No inicia sesión:** la web lleva a iniciar sesión con la contraseña nueva | 400 campo vacío (sin llamar al dominio); **el mismo 400 `PasswordResetTokenInvalid`** para un token desconocido, caducado, usado, sustituido o de una cuenta inactiva; 400 `PasswordTooShort` (menos de 12), que **no consume el token** |

- **El enlace del correo** es `{TRACEABILITY_WEB_BASE_URL}/recuperar#token=…`: el token va en el **fragmento**, que el navegador no envía. La web lo lee, lo borra de la barra y lo manda en el cuerpo de `confirm`. Un token en la query o en la ruta nunca se acepta.
- **Validez:** 30 minutos y un solo uso. Pedir otro deja sustituido (`SUPERSEDED`) el anterior.
- **Enfriamiento de 5 minutos:** una segunda petición antes no envía nada. Si el correo no llega (servidor de correo caído o cola llena), la web debe decir "si no llega, vuelve a pedirlo en 5 minutos" (Q5 (a): sin reintentos).
- **Confirmar revoca todas las sesiones** (`credentialsNotBefore`, D4). Con P-R3-3, iniciar sesión justo después con la contraseña nueva funciona.
- **Riesgo aceptado en ADR-050 (1):** ninguna de las dos rutas limita los intentos por IP (DH-56).

## 2. Plataforma

| Método y ruta | Auth | Cuerpo | Respuestas | Errores |
|---|---|---|---|---|
| `POST /platform/organizations/{organizationId}/verify` | JWT + autoridad de plataforma | — | `200 {organizationId, verificationStatus: "VERIFIED"}` | 403; 404 inexistente; 409 si ya `VERIFIED`/`REJECTED` (DD-48) |
| `POST /platform/organizations/{organizationId}/reject` | ídem | — | `200 {…, verificationStatus: "REJECTED"}` | ídem |
| `POST /platform/organizations/{organizationId}/request-information` | ídem | `{message}` (1–2000) | `200 {…, verificationStatus: "NEEDS_MORE_INFORMATION"}` | 403 (antes de validar); 400 mensaje vacío o largo; 404; 409 |
| `GET /platform/organizations` (cola de verificación, §3.1) | ídem | `?status=PENDING_VERIFICATION\|NEEDS_MORE_INFORMATION` (sin él, las dos), `?cursor=` | `200 {items: [{organizationId, name?, type, verificationStatus, informationRequest?}], nextCursor?}`, 20 por página, por orden de creación, `no-store`; sin miembros ni emails (DD-69) | 400 `status` o cursor (el del descubrimiento no vale aquí); 403 |
| `GET /platform/administrators` (§3.2) | ídem | — | `200 {items: [{accountId, status}]}`, una página de 100, sin email, `no-store` | 403 |
| `POST /platform/administrators` (§3.2) | ídem | `{accountId}` | `201 {accountId, platformAuthority: "ADMINISTRATOR"}` | 400; 403 (antes de validar); 404 cuenta inexistente; 409 `PlatformAuthorityAlreadyGranted`, `PlatformAuthorityTargetInactive` |
| `POST /platform/administrators/{accountId}/revoke` (§3.2, DD-70) | ídem | — | `200 {accountId}` | 403; 404; 409 `PlatformAuthorityNotHeld`, `LastPlatformAdministrator` (nunca sin administradores) |
| `POST /platform/organizations/{organizationId}/media/remove` (ADR-051 F7; Tarea 5; nota del 2026-10-25) | ídem | `{purpose: DELIVERY_EVIDENCE\|ORGANIZATION_LOGO\|CAMPAIGN_COVER, ref?, reason}`: `ref` es el `evidenceRef` (`sha256:`) para la evidencia, el `campaignRef` para la portada y nada para el logo; `reason` de 1 a 500 caracteres | `204`: borra los bytes y deja en el metadato `deletedAt`, `deletedBy` y `reason`; para el logo y la portada, quita también la referencia. Ya retirada, o sin logo ni portada → `204` igual | 401; 403 (antes de validar el cuerpo); 400 `purpose`, `ref` o `reason`; 404 evidencia inexistente en esa organización; 503 si el proveedor falla. El motivo nunca va al log ni a una respuesta |

| `POST /platform/accounts/{accountId}/reactivate` (ADR-050 D7; nota del 2026-10-25) | ídem | — | `200 {accountId, status: "ACTIVE"}`, `no-store`; ya activa → `200` igual, sin auditoría | 401 sin JWT; 403 uniforme (antes de leer la cuenta); 404 `NotFound` cuenta inexistente (solo la ve la plataforma). Los tokens anteriores a la desactivación siguen dando 401: la persona vuelve a iniciar sesión |

## 3. Organización (panel)

| Método y ruta | Auth | Respuestas | Errores |
|---|---|---|---|
| `POST /organizations` (crear organización, R9, §3.1) | JWT, cualquier cuenta activa sin organización; cuerpo `{type: FOUNDATION\|COMPANY, name}` (1–200) | `201 {organizationId, verificationStatus: "PENDING_VERIFICATION"}`; quien la crea queda como `REPRESENTATIVE` (DD-68) | 400 `type` o `name`; 409 `AccountAlreadyBelongsToOrganization` |
| `GET /organizations/{organizationId}` (estado de verificación de la propia organización; ficha S-18) | `ADMINISTRATOR` o `REPRESENTATIVE` de la organización (Q-S18-2); un administrador de plataforma sin membresía, no (Q-S18-4) | `200 {organizationId, name?, type, verificationStatus, informationRequest?}`, `no-store`. `verificationStatus`: `PENDING_VERIFICATION`, `NEEDS_MORE_INFORMATION`, `VERIFIED` o `REJECTED`; `informationRequest` (el texto de la plataforma, tal cual) solo con `NEEDS_MORE_INFORMATION` (Q-S18-3). Sin miembros, correos ni datos de cuentas | 403 (también `EMPLOYEE`, otra organización o inexistente; el mismo, DD-01) |
| `GET /organizations/{organizationId}/campaigns` | `ADMINISTRATOR` de la organización | `200 {items: [{campaignRef, publicCode, title, status, visibility, currency?, targetAmount?, targetPolicy?, clearedAmount?, responsibles: [{accountId, actingRole}], assignedEmployeeCount}]}`, una página de 100 (DD-49) | 403 |
| `GET /organizations/{organizationId}/members` | `ADMINISTRATOR` o `REPRESENTATIVE` (DD-55) | `200 {items: [{accountId, roles, status?}]}`, sin email (`status` solo falta si no se encuentra la cuenta; rama defensiva) | 403 |
| `POST /organizations/{organizationId}/invitations` (§3.3, ADR-049) | `ADMINISTRATOR` o `REPRESENTATIVE`; cuerpo `{email, role: ADMINISTRATOR\|EMPLOYEE}` | `202 {invitationId, role, expiresAt}`, **la misma respuesta exista o no la cuenta**; el correo lleva `{web}/invitaciones#token=…` (token en el fragmento) | 400 `InvalidMemberRole`, `InvalidEmailFormat`; 403 (antes de validar) |
| `GET /organizations/{organizationId}/invitations` | ídem | `200 {items: [{invitationId, emailMasked, role, createdAt, expiresAt, delivery: SENT\|FAILED\|PENDING}]}`, pendientes sin caducar, una página de 100, `no-store` (DD-65) | 403 |
| `POST /organizations/{organizationId}/invitations/{invitationId}/revoke` | ídem | `200 {invitationId, status: "REVOKED"}` | 403; 409 `InvitationNotPending` |
| `POST /organizations/{organizationId}/members/{accountId}/role` | ídem; cuerpo `{role: ADMINISTRATOR\|EMPLOYEE}` | `200 {accountId, roles}`; los roles del `REPRESENTATIVE` solo los cambia él (DD-66) | 400; 403; 409 `MemberAlreadyHasRole`, `ActiveCampaignResponsible` |
| `POST /organizations/{organizationId}/members/{accountId}/remove` | ídem | `200 {accountId, removed: true}` | 403; 409 `RepresentativeTransferRequired`, `ActiveCampaignResponsible` |
| `GET /organizations/{organizationId}/funds` | `ADMINISTRATOR` o `EMPLOYEE` (DD-31) | `200 {items: [{fundId, campaignRef?, currency, clearedAmount, availableAmount, allocations: [{allocationId, amount, status}]}]}`, una página de 200; sin `donorRef` | 403 |
| `GET /organizations/{organizationId}/funds/monthly?months=1..12` (ficha R-6, Bloque B, G-6) | `ADMINISTRATOR` o `EMPLOYEE`, como `/funds` (DD-31) | `200 {timezone: "America/Bogota", items: [{month: "AAAA-MM", currency, clearedAmount}], truncated?}`, `no-store`. `months` es opcional: 6 por defecto, de 1 a 12. Una fila por mes y moneda de los fondos de la organización, del mes más antiguo al actual, con los meses vacíos en `"0"`. Sin fondos, `items: []`. Suma los `FUNDS_CLEARED` 1.0 y 2.0 de los fondos cuya **génesis** lleva la organización, por el mes de `occurredAt` en Bogotá. Los fondos sin organización (anteriores al 2026-09-16) quedan fuera, como en `/funds` (P-R6B-1). Sin `fundId` ni datos del donante | 400 `months`; 403 (también otra organización o inexistente, DD-01) |
| `GET /organizations/{organizationId}/physical-assets` | `ADMINISTRATOR` o `EMPLOYEE` (DD-54) | `200 {items: [{assetRef, lifecycleStatus, currentCustodianRef, currentLocation?, quantity, unitOfMeasure, campaignRef?}]}`, una página de 200 (`currentLocation` falta mientras el activo está `DISPATCHED`, en tránsito; solicitud S-05 del móvil); sin `donorRef` | 403 |
| `GET /organizations/{organizationId}/campaigns/{campaignRef}/prediction` | `ADMINISTRATOR` o `REPRESENTATIVE` (DD-43) | `200 {kind: "ESTIMATE", modelVersion, warning, available, probabilityReachTarget?, estimatedFinalPctOfTarget?, pctTimeElapsed?, warnings?, unavailableReason?, unavailableText?, asOf}`, `no-store`. Solo lectura. STRICT → `available: false`, `STRICT_POLICY_EXCLUDED`; moneda distinta de COP → `UNSUPPORTED_CURRENCY` (ADR-044 Enmienda 1, BORRADOR); fuera del rango de entrenamiento (t < 0,15 o t > 0,50) → `OUTSIDE_TRAINED_RANGE`, sin cifras (Carlos, 2026-10-08) | 403 |
| `GET /organizations/{organizationId}/campaigns/{campaignRef}/prediction/history` (encargo 6, P3, S-10) | `ADMINISTRATOR` o `REPRESENTATIVE` (como la predicción) | `200 {kind: "ESTIMATE", modelVersion, warning, basis: "EVENT_STORE", available, unavailableReason?, unavailableText?, cuts: [{t: 0.15\|0.25\|0.50, cutAt, available, unavailableReason?, unavailableText?, probabilityReachTarget?, estimatedFinalPctOfTarget?, pctRaisedAtCut?}], warnings, asOf}`, `no-store`. Solo lectura. Lo recaudado en cada corte se reconstruye con los `FUNDS_CLEARED` del Event Store anteriores al corte, **nunca con valores actuales**. Sin cifra: `FUTURE_CUT`, `CAMPAIGN_ENDED` (cerrada antes del corte), `CONFIGURATION_CHANGED_AFTER_CUT` y `TARGET_ALREADY_REACHED` (este da `pctRaisedAtCut`). STRICT, otra moneda o sin meta → `available: false` y `cuts: []`. En los cortes pasados la tasa de fallos es 0 y `warnings` lo dice (DD-75) | 403 |
| `PUT /organizations/{organizationId}/logo` (ADR-051 F8; Tarea 2) | `REPRESENTATIVE` o `ADMINISTRATOR` de la organización; `multipart/form-data`, campo `file` (JPEG o PNG por contenido, como mucho 2 MB) | `200 {logoUrl}` (`/api/v1/organizations/{organizationId}/logo`). La imagen se guarda limpia, sin metadatos (F4); el logo anterior se borra después de confirmar el cambio (F10) | 403 (antes de mirar el tamaño; también otra organización o inexistente, DD-01); 413 `ImageTooLarge` (más de 2 MB, más de 5 MB por archivo o 6 MB por petición, o dimensiones excesivas); 415 `UnsupportedImageFormat`; 503 `MediaStorageUnavailable` |
| `DELETE /organizations/{organizationId}/logo` (ADR-051 F8; Tarea 2) | ídem | `204`, también sin logo previo (P-T2-4) | 403 |
| `GET /organizations/{organizationId}/logo` (ADR-051 F6, Enmienda 1; Tarea 2) | JWT: miembro de la organización, con cualquier rol, o administrador de plataforma | `200` con los bytes guardados, `Content-Type` de la imagen, `Cache-Control: no-store, private` | 401 sin JWT; 403 (el mismo, DD-01); 404 `NotFound` sin logo (nunca 410) |

*Nota (2026-10-21, ficha R-6, Bloque A; G-4b y G-5):* `GET /organizations/{organizationId}/physical-assets` lleva además **`assetType`** en cada ítem (el registrado; sigue sin `parentAssetRef`, `rootAssetRef` ni `donorRef`). `GET /organizations/{organizationId}/campaigns` lleva además **`startDate`** y **`endDate`** en cada ítem (ISO-8601 UTC, siempre presentes; las del dominio, como en CV-07). Las filas de arriba no se reescriben.

*Nota (2026-10-25, ficha R-6, Enmienda 1, E2; G-9, adenda G-9 del plan B):* cada ítem de `GET /organizations/{organizationId}/physical-assets` lleva además **`lastEventAt?`** (ISO-8601 UTC): el `occurredAt` del último evento del lote **por `sequence`**, nunca por la fecha más reciente. Se omite si no hay fecha. `GET /physical-assets/{assetRef}` no lo lleva. Las filas de arriba no se reescriben.

*Nota (2026-10-21, ADR-051 Tarea 2; ficha S-18, nota del 2026-10-21):* `GET /organizations/{organizationId}` lleva además **`logoUrl?`** (`/api/v1/organizations/{organizationId}/logo`, la ruta autenticada), solo si la organización tiene logo. Los roles de S-18 no cambian. Las filas de arriba no se reescriben.

*Nota (2026-10-23, ficha R-6, Bloque B; G-6; P-R6B-6):* el recaudado por mes lee como mucho **10 000 fondos** de la organización. Si tiene más, la respuesta lleva **`truncated: true`** y el resultado no está completo. Con 10 000 o menos, `truncated` no aparece.

*Nota (2026-10-25, ficha R-6, Bloque D; G-7 y G-8; plan D con P-R6D-1 (b) y P-R6D-2 (a)): dos rutas nuevas.* Las filas de arriba no se reescriben.

| Método y ruta | Quién | Respuesta | Errores |
|---|---|---|---|
| `GET /organizations/{organizationId}/integrity` (G-7) | `ADMINISTRATOR` o `REPRESENTATIVE` de la organización (como la predicción, DD-43) | `200 {unanchoredEvents, inPendingBatches, underReviewEvents, lastAnchoredAt?, truncated, checkedAt}`, `no-store`. Cuenta los eventos de los fondos y los activos de la organización (por su génesis) según el estado de su lote, con la tabla de G-3: `inPendingBatches` = en lotes `COLLECTING`, `PENDING`, `SUBMITTING`, `SUBMITTED` o `STUCK`; `underReviewEvents` = en lotes `FAILED`, `COLLECTING_FAILED` o `ANCHOR_MISMATCH`; `unanchoredEvents` = sin lote o en un lote `RELEASED`. `lastAnchoredAt` es el mayor `anchoredAt` de los lotes `ANCHORED` con eventos propios. Aunque un lote mezcle organizaciones, solo cuenta los eventos propios. Lee como mucho 10 000 fondos y 10 000 activos; si hay más, `truncated: true`. Sin `lastVerification` (D-R6-7). Nunca `batchId`, `merkleRoot`, `transactionHash` ni nada de otras organizaciones | 401 sin JWT; 403 (el mismo para un `EMPLOYEE`, otra organización o una inexistente, DD-01) |
| `GET /organizations/{organizationId}/activity?cursor=` (G-8) | `ADMINISTRATOR`, `REPRESENTATIVE` o `EMPLOYEE` de la organización (D-R6-8) | `200 {items: [{type, occurredAt, subject?: {kind: "ASSET"\|"CAMPAIGN"\|"FUND", ref}, eventCount?, quantity?, unitOfMeasure?, locationZone?}], nextCursor?}`, 20 por página, de lo más reciente a lo más antiguo, `no-store`. `type`: `ASSET_REGISTERED`, `ASSET_DISPATCHED`, `ASSET_RECEIVED`, `ASSET_DELIVERED`, `ASSET_SPLIT` y `FUNDS_CLEARED` (los eventos, con `ref` = `assetId` o `fundId`); `CAMPAIGN_CLOSED` (las convocatorias `CLOSED`, con `ref` = `campaignRef` y la fecha de cierre); `BATCH_ANCHORED` (**sin `subject`** y con **`eventCount`**, los eventos propios que entraron en el lote; P-R6D-1 (b)). `quantity` y `unitOfMeasure`, en el registro y en la división; `locationZone`, en el registro, la recepción y la entrega, calculada como en el seguimiento. `occurredAt` es el del hecho; el orden usa el instante de listado (`recordedAt` de los eventos, la fecha de cierre o `anchoredAt`) y, en un empate, eventos, después cierres y después lotes. Un `FUNDS_CLEARED` no lleva importe. Nunca el actor, `donorRef`, `beneficiaryRef`, custodios, la ubicación exacta ni importes. `nextCursor` es opaco y solo vale para la misma lista de la misma organización | 400 `cursor` (inválido, alterado, de otro propósito o de otra organización); 401 sin JWT; 403 (el mismo, DD-01) |

*Nota (2026-10-25, Enmienda 2 de la ficha R-6; plan de cierre §G-8, aprobado en #147):* la respuesta de G-8 gana **`truncated`**, que llega siempre, en todas las páginas: `200 {items, nextCursor?, truncated}`. Es `true` si la organización tiene más de 10 000 fondos o más de 10 000 activos; en ese caso solo se leen los primeros 10 000 de cada directorio, que son los **más antiguos** (deuda P-E2-2). La fila de arriba se conserva.

## 4. Convocatoria (administración)

Todas con JWT + `ADMINISTRATOR` de la organización de la convocatoria y `Command-Id`.

| Método y ruta | Cuerpo | Respuestas | Errores |
|---|---|---|---|
| `POST /organizations/{organizationId}/campaigns` (CV-01) | `{title, description?, visibility, startDate, endDate, configuration: {acceptedDonationTypes, acceptedPaymentMethods?, currency?, targetAmount?, targetPolicy?, onTargetReached?}}` | `201 {campaignRef, publicCode}` | 400 campos (con nombre); 403; 409 `OrganizationNotVerified` |
| `POST /campaigns/{campaignRef}/employees` (CV-02) | `{employeeRef}` | `201 {assignmentId}` | 400; 403; 409 ya asignado, destinatario inválido, `CLOSED` |
| `POST /campaigns/{campaignRef}/administrators` (CV-03) | `{administratorRef}` | `201 {assignmentId}` | ídem |
| `POST /campaigns/{campaignRef}/responsibles/{responsibleRef}/remove` (DD-50) | opcional `{replacementRef, replacementActingRole}` | `200 {removedAssignmentId, replacementAssignmentId?}` | 400 reemplazo sin `replacementActingRole`; 403; 409 último responsable, no responsable (DD-51), convocatoria `CLOSED` |
| `POST /campaigns/{campaignRef}/close` | — | `200 {campaignRef, status: "CLOSED"}` | 403; 409 `CampaignAlreadyClosed` (`CLOSED` es terminal) |
| `POST /campaigns/{campaignRef}/configuration` (§3.5, edición directa) | `{expectedConfigurationVersion, configuration}` | `200 {campaignRef, configurationVersion}`; solo sin donaciones | 400; 403; 409 `CampaignAlreadyHasDonations`, `ConfigurationVersionConflict`, `ConfigurationChangeOnClosedCampaign`, `MonetaryTermsChangeNotSupported` |
| `POST /campaigns/{campaignRef}/configuration-change-requests` (§3.5) | ídem | `201 {requestId, status: "PENDING", baseConfigurationVersion}` | 400; 403; 409 `ConfigurationChangeRequestAlreadyPending`, versión, cerrada, `MonetaryTermsChangeNotSupported`, `MonetaryRemovalNotAllowed` |
| `GET /campaigns/{campaignRef}/configuration-change-requests` | — (`ADMINISTRATOR` o `REPRESENTATIVE`) | `200 {items: [{requestId, status, baseConfigurationVersion, proposedConfiguration, requestedBy, requestedAt, decidedBy?, decidedAt?, resultingConfigurationVersion?}]}`, 50, `no-store` | 403 |
| `POST /campaigns/{campaignRef}/configuration-change-requests/{requestId}/approve` | — (otro `ADMINISTRATOR` o el `REPRESENTATIVE`) | `200 {requestId, status: "APPROVED", configurationVersion}` | 403 (`SelfApprovalNotAllowed`); 409 `ConfigurationChangeRequestNotPending`, `ConfigurationVersionConflict`, cerrada, `MonetaryRemovalNotAllowed` |
| `POST /campaigns/{campaignRef}/configuration-change-requests/{requestId}/reject` | — (los mismos o el solicitante) | `200 {requestId, status: "REJECTED", configurationVersion: null}` | 403; 409 `ConfigurationChangeRequestNotPending` |

*Nota (2026-10-25, ADR-051 Tarea 3; plan de cierre §T3, aprobado en #147):* la portada de la convocatoria. Estas dos rutas **no** llevan `Command-Id` (como el logo). La tabla de arriba se conserva.

| Método y ruta | Quién | Respuestas | Errores |
|---|---|---|---|
| `PUT /organizations/{organizationId}/campaigns/{campaignRef}/cover` (F8) | `ADMINISTRATOR` de la organización de la convocatoria, nunca el `REPRESENTATIVE` (C1 (a)); `multipart/form-data`, campo `file`, JPEG o PNG de hasta 2 MB | `200 {coverImageUrl}` (`/api/v1/public/campaigns/{publicCode}/cover`); reemplaza la anterior y la borra después (F10). También en una convocatoria `CLOSED` (P-T3-3) | 401; 403 uniforme, antes de leer los bytes (otro rol, otra organización, `campaignRef` ajeno o inexistente); 413 `ImageTooLarge`; 415 `UnsupportedImageFormat`; 503 `MediaStorageUnavailable` |
| `DELETE /organizations/{organizationId}/campaigns/{campaignRef}/cover` (F8) | ídem | `204`, también sin portada | 401; 403 |

## 5. Convocatoria y donación (público)

| Método y ruta | Auth | Cuerpo | Respuestas | Errores |
|---|---|---|---|---|
| `GET /public/campaigns` | pública | `?cursor=` | `200 {items: [{publicCode, title, organizationName?, status, startDate, endDate, acceptedDonationTypes, currency?, targetAmount?, clearedAmount?}], nextCursor?}`; 20 por página; cursor opaco (DD-58); solo `PUBLIC` y `OPEN`, **nunca `PRIVATE_LINK`** (DD-52) | 400 cursor |
| `GET /public/campaigns/{publicCode}` (CV-07) | pública | — | `200 {organizationName?, title, description?, status, startDate, endDate, acceptedDonationTypes, acceptedPaymentMethods?, currency?, targetAmount?, clearedAmount?}` | 404 |
| `GET /public/campaigns/{publicCode}/narrative` | pública | — | `200 {status: "AVAILABLE", content, source: "LLM_GENERATED", facts}`; `202 {status: "PENDING", content: null, source: null, facts}`; `200 {status: "UNAVAILABLE", content: "Narrativa no disponible", source: null, facts}` (`content` y `source` siempre llegan, con `null` donde no aplica). `facts = {status, currency?, targetAmount?, clearedAmount?, unitsDelivered, distinctRecipients}` (B5, DD-39) | 404 |
| `POST /public/campaigns/{publicCode}/donation-intents` (CV-11) | JWT **opcional**, `Command-Id` | `{amount, currency, paymentMethod}` | `201 {intentId, statusToken?, paymentRedirectUrl?}` (`paymentRedirectUrl` solo con `GATEWAY`; `statusToken` solo falta en un reenvío de una intención anterior a la Enmienda 3, sin hash de token); un reenvío emite un `statusToken` nuevo y anula el anterior (DD-18 sustituida) | 400; 404; 409 reglas de la convocatoria |
| `GET /public/donation-intents/{intentId}` | header `Intent-Token` | — | `200 {status, trackingCode?}` (el código, solo con fondos aplicados) | 404 (token inválido o caducado, igual que inexistente) |
| `POST /webhooks/payments` | firma `X-Simulated-Signature`; **solo** con `traceability.demo.simulated-payments=true` (perfil `dev`) | `{type: "payment.confirmed"\|"payment.failed", paymentSessionId, providerEventId, amount, currency}` | `200` sin cuerpo | 401 firma; 400 campos; 404 sesión; 409 evento incoherente |
| `GET /public/campaigns/{publicCode}/organization-logo` (ADR-051 F6, Enmienda 1; Tarea 2) | pública | — | `200` con los bytes guardados del logo de la organización dueña de la convocatoria, `Content-Type` de la imagen, `Cache-Control: public, max-age=3600`, `ETag: "<sha256>"` (sin `If-None-Match`, P-T2-9). Nunca el `organizationId` | 404 `NotFound` (convocatoria no visible por su código, organización sin logo, logo borrado o sin bytes; siempre el mismo, nunca 410) |
| `GET /public/campaigns/{publicCode}/cover` (ADR-051 F6; Tarea 3; nota del 2026-10-25) | pública | — | `200` con los bytes guardados de la portada, `Cache-Control: public, max-age=3600`, `ETag` con la huella y el `Content-Type` del metadato | 404 `NotFound`, el mismo si el código no es visible, si no hay portada, si se retiró o si faltan los bytes |

*Nota (2026-10-18, ficha L-FRONTS, A1; M-07; Q-A1-3):* `GET /public/campaigns` (cada ítem) y `GET /public/campaigns/{publicCode}` llevan además **`organizationVerified`** (booleano, **siempre presente**), calculado al leer con el estado actual de la organización (`OrganizationVerificationPort.isVerified`): `true` solo si está `VERIFIED`; una organización inexistente da `false`. Las filas de arriba no se reescriben.

*Nota (2026-10-21, ADR-051 Tarea 2; enmiendas de contrato del plan de la Tarea 2 §2):* `GET /public/campaigns` (cada ítem) y `GET /public/campaigns/{publicCode}` llevan además **`organizationLogoUrl?`** (`/api/v1/public/campaigns/{publicCode}/organization-logo`), solo si la organización tiene logo. Ninguna respuesta pública lleva el `organizationId` (Enmienda 1). Nombre, verificación y logo salen de una sola lectura de la organización (P-T2-7). Tras reemplazar un logo, el anterior puede seguir en cachés hasta 1 h (R-T2-5, aceptado). `coverImageUrl?` llega con la Tarea 3. Las filas de arriba no se reescriben.

*Nota (2026-10-25, ADR-051 Tarea 3):* `GET /public/campaigns` (cada ítem) y `GET /public/campaigns/{publicCode}` llevan además **`coverImageUrl?`** (`/api/v1/public/campaigns/{publicCode}/cover`), solo si la convocatoria tiene portada. Ninguna respuesta pública lleva el `campaignRef`, el `organizationId` ni la `objectKey`.

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
| `GET /physical-assets/{assetRef}` | — | `200 {assetRef, lifecycleStatus, currentCustodianRef, currentLocation?, quantity, unitOfMeasure, campaignRef?}`; `currentLocation` falta en `DISPATCHED` (S-05) | 403 (también inexistente) |

*Nota (2026-10-21, ficha R-6, Bloque A; G-4b):* `GET /physical-assets/{assetRef}` lleva además **`assetType`** (el registrado). Las filas de arriba no se reescriben.

*Nota (2026-10-25, ADR-051, Tarea 4; F2, F3, F4, F5 y F8): evidencia de entrega.* Las filas de arriba no se reescriben.
- **Ruta nueva:** `POST /organizations/{organizationId}/delivery-evidence`, `multipart/form-data` con el campo `file` (JPEG o PNG por contenido, como mucho 5 MB). Quién: los roles que pueden entregar (`EMPLOYEE`) de esa organización; el resto, el mismo 403 (DD-01), comprobado antes de leer el archivo. Respuesta: `201 {evidenceRef}`, con `evidenceRef = "sha256:" + SHA-256 de los bytes guardados` (la imagen se guarda limpia, sin EXIF, GPS, XMP ni comentarios; F4). Los mismos bytes de la misma organización devuelven el mismo `evidenceRef` sin duplicar (F9). Errores: 401 sin JWT; 403; 413 `ImageTooLarge`; 415 `UnsupportedImageFormat`; 503 `MediaStorageUnavailable`. **No lleva `Command-Id`.**
- **Cambio en `POST /physical-assets/{assetRef}/deliver`:** `evidenceRef` tiene que ser `sha256:<64 hexadecimales en minúscula>`, el devuelto por la subida. Si no, **400 `InvalidEvidenceRef`**, sin consumir el `Command-Id` ni mirar el activo. La foto tiene que ser de la organización del activo, de evidencia y no borrada; si no, **400 `EvidenceNotFound`**, el mismo cuerpo en todos esos casos. Una foto solo sirve para **un** activo: usarla en otro da **409 `EvidenceAlreadyBound`** y el activo no cambia. Reintentar la entrega del mismo activo con la misma foto (el mismo `Command-Id`) es un éxito sin evento duplicado. Una entrega rechazada por permisos o por el estado del activo no vincula la foto.
- **Orden para el cliente (F5):** primero subir la foto, con conexión, y después entregar con su `evidenceRef`. Despachar y recibir no cambian.
- **Eventos antiguos:** los `ASSET_DELIVERED` con `evidenceRef` en texto libre se siguen leyendo igual; no se reescriben ni cambia la verificación de su cadena.

*Nota (2026-10-25, ADR-051, Tarea 5; F6 y F7): leer la evidencia.* `GET /physical-assets/{assetRef}/evidence`, con JWT. La leen los miembros de la organización del activo, con cualquier rol, y el administrador de plataforma. Responde `200` con los bytes guardados, `Cache-Control: private, no-store` y el `Content-Type` del metadato. Errores: 401; el 403 uniforme para otra organización, para una cuenta sin organización o para un activo inexistente (DD-12); 404 `NotFound`, el mismo si no hay entrega, si el `evidenceRef` es texto libre heredado o si faltan el metadato o los bytes; **410 `EvidenceRemoved`** si la plataforma la retiró. Las filas de arriba no se reescriben.

## 7. Fondos (Camino A)

| Método y ruta | Auth | Cuerpo | Respuestas | Errores |
|---|---|---|---|---|
| `POST /funds/{fundId}/allocations` | `ADMINISTRATOR`, `Command-Id` | `{amount}` | `201 {allocationId, status: "REQUESTED"}` (id determinista, DD-29) | 400; 403 (también fondo inexistente, DD-30); 409 importe mayor que el disponible, `Command-Id` reutilizado |
| `POST /funds/{fundId}/allocations/{allocationId}/confirm` | ídem | — | `200 {allocationId, status: "CONFIRMED"}` (DD-32) | 403; 409 |

## 8. Seguimiento del donante

Header `Authorization: Bearer <trackingCode>`. Si el código falta, no vale o caducó, **todas** las rutas de esta sección dan **401** con el mismo `ProblemDetail` (`title: "Unauthorized"`, `detail: "Invalid or missing tracking code"`; solo cambia `instance`, que es la ruta pedida).

*Corrección (2026-10-08, hallazgo H-TR-1):* esta sección decía "el mismo 404 (TR-D1)", pero el filtro del `trackingCode` responde 401 desde la Fase 3. El test de P4 lo comprobó. Se documenta el comportamiento real; cambiarlo a 404 sería decisión de Carlos.

| Método y ruta | Respuestas |
|---|---|
| `GET /donations/tracking` | `200 {status: "ACTIVA"\|"EN_PROCESO", financialSnapshot: {currency, originalAmount, clearedAmount, pendingAllocationAmount, confirmedAllocationAmount, refundedAmount}, campaignRef?, logistics: [{assetRef, lifecycleStatus, assetType, unitOfMeasure, quantity, locationZone?, custodianCategory}]}` (EF3); `locationZone` llega `null` si la ubicación no tiene zona pública o el activo no tiene ubicación |
| `GET /donations/tracking/narrative` | `200 {status: "PENDING"\|"AVAILABLE", content?, source?}` |
| `GET /donations/tracking/assets/{assetRef}/history` | `200 {history: [{eventType, timestamp, locationZone?, custodianCategory, status}]}`; `locationZone` llega `null` en las transiciones sin ubicación (`DISPATCHED`, `SPLIT`, `CUSTODY_TRANSFERRED`, `SPLIT_COMPENSATED`, `DEPLETED`) o sin zona pública |
| `GET /donations/tracking/integrity` (encargo 6, P4; ficha `ficha-p4-integridad-en-seguimiento.md`) | `200 {batches: [{anchorStatus, merkleRoot?, transactionHash?, network?, anchoredAt?, confirmedBlockNumber?, eventsOfThisDonation, verification: {result: "MATCH"\|"MISMATCH"\|"INCONCLUSIVE", reason?, reasonText?, affectsThisDonation?}}], unanchoredEvents, checkedAt}`, `no-store`. Un lote entra si tiene eventos del fondo o de sus activos. `reason`: `NOT_ANCHORED`, `LEGACY_BATCH`, `ROOT_MISMATCH` (con `affectsThisDonation`), `LEAF_COUNT_CHANGED`, `CANONICAL_FORM_UNKNOWN` o `VERIFICATION_INCONCLUSIVE`. Desde B4 (Enmienda 1 de ADR-039), `MISMATCH` también detecta un payload alterado o una cadena `previousHash` rota. Nunca el `batchId`, la cobertura ni nada de otra donación. Resultado en caché 5 min por lote (DD-76) |

*Nota (2026-10-21, ficha R-6, Bloque A; G-1; D-R6-1):* `GET /donations/tracking` lleva además **`campaignTitle?`** (el título de la convocatoria) y **`organizationName?`** (el nombre público de su organización). Faltan los dos si la donación no tiene `campaignRef` o la convocatoria no se encuentra; `organizationName` falta también si la organización no tiene nombre (DD-04). Sigue sin `fundId` ni `donorRef` (DD-76). Para una convocatoria `PRIVATE_LINK`, el título se muestra a quien tiene el `trackingCode` (P-R6A-4, aceptado). Las filas de arriba no se reescriben.

*Nota (2026-10-23, ficha R-6, Bloque B; G-2; D-R6-2):* en `GET /donations/tracking`, **`financialSnapshot`** lleva además **`firstClearedAt?`** y **`lastClearedAt?`** (ISO-8601 UTC): el `occurredAt` del primer y del último `FUNDS_CLEARED` (1.0 o 2.0) del fondo, por orden de `sequence` del stream, nunca por fecha. Se leen del Event Store al consultar (C-2). Con una sola liquidación son iguales, y sin liquidaciones faltan los dos. Las filas de arriba no se reescriben.

*Nota (2026-10-25, ficha R-6, Bloque C; G-4; D-R6-5):* en `GET /donations/tracking`, cada ítem de **`logistics`** lleva además **`parentAssetRef?`**: el `assetRef` opaco del lote del que salió por una división, calculado igual que `assetRef`. **Siempre apunta a otro ítem de la misma `logistics`**; si el padre no está en la respuesta, el campo falta (nunca se devuelve el ref de un lote de otra donación). Falta también en los lotes que no vienen de una división. `rootAssetRef` no se expone: se deduce siguiendo `parentAssetRef`. Las filas de arriba no se reescriben.

*Nota (2026-10-25, ficha R-6, Bloque C; G-3 y Enmienda 1, E1; D-R6-6 reabierto):* en `GET /donations/tracking/assets/{assetRef}/history`, cada ítem de **`history`** lleva además:
- **`anchorStatus`**, siempre, con **cuatro** valores (C-4): `ANCHORED` (lote `ANCHORED`); `IN_BATCH` (lote `COLLECTING`, `PENDING`, `SUBMITTING`, `SUBMITTED` o `STUCK`); `PENDING` (sin lote, o lote `RELEASED`); `UNDER_REVIEW` (lote `FAILED`, `COLLECTING_FAILED` o `ANCHOR_MISMATCH`, o un evento cuyo hash ya no es la hoja anclada; ver abajo).
- **`eventHash?`**: el `eventHash` del evento en el Event Store, 64 caracteres hexadecimales en minúscula. Falta solo si el paso no tiene evento asociado (proyecciones antiguas; P-C-4), y entonces el paso es `PENDING`.
- **`anchorProof?`**, solo si el lote está `ANCHORED` y tiene sus `leafHashes` guardados: `{merkleRoot, leafIndex, path: [{side: "LEFT"|"RIGHT", hash}], transactionHash, network, anchoredAt}` (`anchoredAt` en ISO-8601 UTC). **Sin `leafCount`** (plan de ejecución §0.4). `leafIndex` es la posición dentro del lote, no la secuencia del stream.
- Un lote antiguo sin `leafHashes` da `ANCHORED` sin `anchorProof`: la prueba no se reconstruye con las hojas actuales. Si el `eventHash` actual no es igual a la hoja `leafIndex` del lote, el paso es `UNDER_REVIEW` y no lleva prueba: nunca se devuelve una prueba que no corresponda al hecho mostrado.
- **Algoritmo de verificación (E1.3):** las hojas son los `eventHash` del lote, por `streamId` y después por `sequence`; el nodo padre es `SHA-256(UTF-8(izquierdo_hex + derecho_hex))` en hexadecimal minúscula (se concatenan los textos hexadecimales, no los bytes); el último nodo de un nivel impar se combina consigo mismo, y su paso es `{side: "RIGHT", hash: <el mismo nodo>}`. Para verificar: `nodo = eventHash`; por cada paso, `LEFT → sha256_hex(hash + nodo)` y `RIGHT → sha256_hex(nodo + hash)`; la prueba vale si al final `nodo == merkleRoot`.
- **Vectores fijos** para el test del cliente: `crypto/src/test/resources/merkle/proof-vectors.json` (`{description, leaves, index, path, root}`; las hojas son SHA-256 de `"leaf-0"`, `"leaf-1"`…; incluye 5 hojas con índice 4 y con índice 2).
- **Límites (E1.4):** el árbol no separa por dominio las hojas de los nodos internos y repite el último nodo de un nivel impar, así que en teoría admite segundas preimágenes de árbol; no se cambia porque invalidaría las raíces ya ancladas (haría falta un ADR). La prueba demuestra que el hash del paso está en la raíz anclada en esa transacción; **no** demuestra que el hash corresponda al contenido del evento, que no se expone: eso lo comprueba `GET …/integrity`, en el servidor.
- Sigue sin `sequence`, `streamId`, `merkleBatchId`, `previousHash` ni `leafHashes` (DD-76). Las filas de arriba no se reescriben.

*Nota (2026-10-25, enmienda 1 de ADR-021-D; ADR-051 F6, C2 (a), Tarea 5):* en `GET /donations/tracking/assets/{assetRef}/history`, el paso de la entrega (`status: "DELIVERED"`) gana **`evidenceRegistered`**: `true` si el evento lleva un `evidenceRef` no vacío y `false` si no lo lleva. También gana **`evidenceRef?`**, solo si es una huella `sha256:<64 hexadecimales en minúscula>`. Un `evidenceRef` antiguo de texto libre **nunca** sale, porque puede llevar datos personales. Los demás pasos no llevan ninguno de los dos campos, y `logistics` no cambia. Tras una retirada (F7), el paso sigue igual: la huella está en el evento, que es inmutable. Nunca sale la imagen, una URL de la evidencia ni la `objectKey`.

## 9. Sin HTTP

`MerkleBatch`, `BlockchainAnchorScheduler`, `AnchorConfirmationPoller` e `IntegrityVerificationPort.verifyBatch` son procesos internos (matriz §6). El anclaje en testnet y su evidencia: `runbook-anclaje-testnet.md`.
