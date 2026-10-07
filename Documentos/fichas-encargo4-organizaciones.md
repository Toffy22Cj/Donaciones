# Fichas — tercera autorización de Carlos (organizaciones, usuarios, empleados y administradores)

**Origen:** "Autorización de trabajo autónomo (3) — Carlos, 2026-10-07", §3. Una ficha breve por bloque, en el orden de prioridad de Carlos. Las decisiones del agente llevan su número DD (`decisiones-delegadas-2026-10.md` §4).
**Reglas comunes:** las de `referencia-api-v1.md` §0. Identity no usa `Command-Id` (DH-34, DD-56). Toda negativa de permiso, otra organización o recurso inexistente en rutas protegidas da el mismo 403 (DD-01).

---

## 3.1 Crear organización (R9) y cola de verificación

**Cierra:** R9 (`hallazgos-front-fase2.md`) y Q-v2-2 (`propuesta-apis-fase6.md` §3.2). Q-v2-1 se respondió "(a) semilla" el 2026-10-06; la tercera autorización la reabre con la opción (b), y el flujo de producto queda fijado por Carlos: "cualquier usuario registrado; queda como `REPRESENTATIVE`".

| Endpoint | Auth | Entrada | Respuesta | Errores |
|---|---|---|---|---|
| `POST /organizations` | JWT (cualquier cuenta activa) | `{type: FOUNDATION\|COMPANY, name}` | `201 {organizationId, verificationStatus: "PENDING_VERIFICATION"}` | 400 `type` o `name` (vacío o > 200); 401; 409 `AccountAlreadyBelongsToOrganization` |
| `GET /platform/organizations` | JWT + autoridad de plataforma | `?status=PENDING_VERIFICATION\|NEEDS_MORE_INFORMATION` (sin él, las dos), `?cursor=` | `200 {items: [{organizationId, name?, type, verificationStatus, informationRequest?}], nextCursor?}`, 20 por página, `no-store` | 400 `status` o `cursor`; 401; 403 |

**Dominio:** sin reglas nuevas. `CreateOrganizationService` (ADR-026) ya exige que la cuenta no pertenezca a otra organización y crea al `REPRESENTATIVE`. La cola es una lectura nueva (`OrganizationVerificationQueueQuery`) con un comando de plataforma nuevo, `READ_VERIFICATION_QUEUE`, que exige `platformAuthority = ADMINISTRATOR` como los demás.

**Decisiones:**
- **DD-68:**
  - `name` es **obligatorio por HTTP**, aunque el dominio lo admite opcional (DD-04): la cola y CV-07 lo muestran. Se guarda sin espacios a los lados.
  - `type` se valida contra el enum en mayúsculas exactas.
  - La respuesta no incluye `Location`: no hay `GET /organizations/{id}` (ni lo pide la autorización).
- **DD-69:**
  - La cola incluye `PENDING_VERIFICATION` y `NEEDS_MORE_INFORMATION` (las dos esperan a la plataforma). Pedir `VERIFIED` o `REJECTED` → 400.
  - Orden por `organizationId` (ULID, orden de creación).
  - El cursor usa la clave del descubrimiento (DD-58, sin otro secreto) con un **propósito propio dentro del texto cifrado** (`verification-queue:` + ULID): un cursor de una lista no vale en la otra.
  - Sin miembros ni emails: la verificación documental va por fuera del sistema.
- `AccountAlreadyBelongsToOrganizationException` no tenía traducción (habría sido 500). Ahora da 409, también al aceptar una invitación (ADR-049 D4).

**Tests** (`OrganizationRegistrationAndQueueHttpIntegrationTest`, rojos primero):
- crear → `REPRESENTATIVE` y `PENDING_VERIFICATION`; `/me` lo refleja;
- repetir, o hacerlo un miembro de otra organización → 409;
- validaciones → 400 sin efecto; sin JWT → 401;
- 23 pendientes recorridas en ≥ 2 páginas sin duplicados, en orden y sin una verificada;
- filtro `NEEDS_MORE_INFORMATION` con su mensaje; estados no pendientes → 400;
- administrador de organización, representante y cuenta suelta → el mismo 403;
- cursor alterado, ajeno o del descubrimiento → 400, y el de la cola no vale en el descubrimiento.

## 3.2 Conceder y revocar administradores de plataforma

**Cierra:** la fila de la matriz §1 (`GRANT_PLATFORM_AUTHORITY` / `REVOKE_PLATFORM_AUTHORITY`, "DISEÑO CERRADO"). El dominio ya existía (ADR-038 §2.3): contador `PlatformAuthorityState` con `decrementIfMoreThanOne` y escrituras condicionales. **Sin reglas nuevas.**

| Endpoint | Auth | Entrada | Respuesta | Errores |
|---|---|---|---|---|
| `GET /platform/administrators` | JWT + autoridad de plataforma | — | `200 {items: [{accountId, status}]}`, una página de 100, sin email, `no-store` | 401; 403 |
| `POST /platform/administrators` | ídem | `{accountId}` | `201 {accountId, platformAuthority: "ADMINISTRATOR"}` | 400 `accountId`; 403; 404 cuenta inexistente; 409 `PlatformAuthorityAlreadyGranted`, `PlatformAuthorityTargetInactive` |
| `POST /platform/administrators/{accountId}/revoke` | ídem | — | `200 {accountId}` | 403; 404; 409 `PlatformAuthorityNotHeld`, `LastPlatformAdministrator` |

**Reglas de Carlos:**
- Ante un estado que ya es el pedido, 409: conceder a quien ya la tiene o revocar a quien no la tiene.
- La plataforma nunca se queda sin administradores. Revocar al último, incluido uno mismo, da 409. Con dos revocaciones cruzadas simultáneas gana una sola, porque el contador se decrementa con una escritura condicional dentro de la transacción.

**Decisión DD-70:**
- Revocar va por `POST …/revoke` y no por el `DELETE` de la matriz: toda la API usa POST (retirar responsable, DD-50) y CORS solo permite `GET`/`POST` (DD-57).
- Una cuenta inexistente da **404**, porque solo la ve la plataforma, igual que la organización inexistente (DD-48).
- La lista no lleva email (DD-55).
- Se autoriza antes de validar el cuerpo: quien no es de la plataforma recibe 403, nunca 400.
- Las excepciones de dominio que no tenían traducción ahora dan 409: `PlatformAuthorityAlreadyGranted`, `PlatformAuthorityNotHeld` y `LastPlatformAdministrator`.

**Tests** (`PlatformAdministratorsHttpIntegrationTest`, rojo primero), un único ciclo porque el contador es global en la base:
- conceder → `/me` lo refleja y la lista lo incluye;
- repetir → 409; cuenta inexistente → 404; cuenta inactiva → 409; cuerpo vacío → 400;
- quien no es de la plataforma recibe el mismo 403, también con un cuerpo inválido, y no cambia nada;
- revocar → el revocado pierde el acceso en la petición siguiente; repetir → 409;
- el último no se revoca, ni a sí mismo;
- **8 rondas de revocación cruzada simultánea**: siempre gana exactamente una y queda un administrador.

## 3.3 Miembros de la organización e invitaciones por correo

**Diseño:** ADR-049 (DD-60 a DD-67). Dominio nuevo en `identity`:
- el agregado `OrganizationInvitation` y el token `InvitationToken`, de 256 bits y guardado como SHA-256;
- `Organization.changeMemberRole`;
- el puerto `CampaignResponsibilityPort` en `contracts`, implementado por `convocatoria` con `CampaignResponsibilityQuery`.

La incorporación reutiliza `AddEmployee` y `AssignAdministrator` (ADR-026). Correo por SMTP en `app` (`SmtpInvitationMailAdapter`), con Mailpit en `dev`.

| Endpoint | Auth | Entrada | Respuesta | Errores |
|---|---|---|---|---|
| `POST /organizations/{id}/invitations` | `ADMINISTRATOR` o `REPRESENTATIVE` | `{email, role: ADMINISTRATOR\|EMPLOYEE}` | `202 {invitationId, role, expiresAt}`, **igual exista o no la cuenta** | 400 `InvalidMemberRole`, `InvalidEmailFormat`; 403 (antes de validar) |
| `GET /organizations/{id}/invitations` | ídem | — | `200 {items: [{invitationId, emailMasked, role, createdAt, expiresAt, delivery}]}`, pendientes sin caducar, `no-store` | 403 |
| `POST /organizations/{id}/invitations/{invitationId}/revoke` | ídem | — | `200 {invitationId, status: "REVOKED"}` | 403 (otra organización o inexistente); 409 `InvitationNotPending` |
| `POST /invitations/accept` | JWT de la cuenta invitada | `{token}` en el **cuerpo**; ninguna query | `200 {organizationId, roles}` | 401; **el mismo 403 `InvitationNotAcceptable`** para token desconocido, caducado, revocado, usado, email distinto o token en la URL; 409 `AccountAlreadyBelongsToOrganization` (tras validar token y email) |
| `POST /organizations/{id}/members/{accountId}/role` | `ADMINISTRATOR` o `REPRESENTATIVE` | `{role: ADMINISTRATOR\|EMPLOYEE}` | `200 {accountId, roles}` | 400; 403 (no miembro, otra organización, roles del representante cambiados por otro); 409 `MemberAlreadyHasRole`, `ActiveCampaignResponsible` |
| `POST /organizations/{id}/members/{accountId}/remove` | ídem | — | `200 {accountId, removed: true}` | 403; 409 `RepresentativeTransferRequired`, `ActiveCampaignResponsible` |

**Decisión DD-71:**
- Invitar responde **202**: el correo se envía después y puede fallar sin que cambie la respuesta (DD-64).
- Aceptar responde con la organización y los roles, para que la web no tenga que pedir `/me`.
- Un `?…` en la URL de aceptar se rechaza siempre con el 403 uniforme: nunca se acepta un token por la URL.
- Cambiar el rol y quitar van por `POST`, como el resto de la API (DD-50, DD-70).
- Los rechazos del dominio que no tenían traducción ahora dan 409: `RepresentativeTransferRequired`.

**Tests:**
- `OrganizationMembersAndInvitationsHttpIntegrationTest` (18), sobre la definición de hecho de ADR-049:
  - hash de un token de 256 bits; enlace con fragmento; misma respuesta con y sin cuenta;
  - roles y 403 antes de validar; fallo SMTP → `FAILED`;
  - lista enmascarada; reinvitar revoca el enlace anterior; revocar y repetir → 409;
  - aceptar en minúsculas y efectivo en la petición siguiente; rechazos con el mismo 403; caducidad con reloj controlado;
  - cuenta con organización → 409; 4 aceptaciones simultáneas → 1 gana;
  - el token nunca en logs; cambio de rol restrictivo; roles del representante; representante nunca se quita;
  - responsable activo: no se degrada ni se quita hasta reemplazarlo o cerrar la convocatoria.
- `InvitationMailpitIntegrationTest`: SMTP real con Mailpit por Testcontainers.
- `SmtpInvitationMailAdapterTest`: *fail-fast* sin SMTP, remitente o URL base.
- `OrganizationInvitationTest`: dominio.

## 3.4 Empleado: mis convocatorias asignadas

| Endpoint | Auth | Respuesta | Errores |
|---|---|---|---|
| `GET /me/campaigns` | JWT, cualquier cuenta | `200 {items: [{campaignRef, publicCode, title, status, actingRole, assignedAt}]}`, una página de 100, `no-store` | 401 |

**Comportamiento:**
- Solo lectura (`AssignedCampaignsQuery` en `convocatoria`).
- Devuelve las asignaciones **activas** de quien llama en convocatorias de **su organización actual**, tomada del principal y nunca de la petición. Sin organización, la lista sale vacía. No acepta parámetros: nadie ve las asignaciones de otro.

**Decisión DD-72:**
- Se incluyen también las convocatorias `CLOSED`, con su estado, para que el empleado vea su historial; la web puede filtrarlas.
- Una asignación retirada (DD-50) desaparece de la lista.

**Tests** (`MyAssignedCampaignsHttpIntegrationTest`, rojo primero):
- el empleado ve solo la suya, con su papel y el estado;
- el administrador responsable ve las dos suyas;
- un miembro sin asignaciones, el que crea las convocatorias y una cuenta suelta ven la lista vacía; sin JWT → 401;
- retirado, desaparece; cerrada, sigue con `CLOSED`;
- quien cambia de organización no ve las asignaciones de la anterior;
- `?accountId=` de otro no cambia nada.

## 3.5 Editar la configuración con solicitud y aprobación (D3)

**Diseño:** `ADR-037-enmienda-4-solicitud-y-aprobacion-de-configuracion.md`, aprobada como decisión delegada (DD-73). Sigue la §3.2 de la Enmienda 1 (D2/D3):
- edición directa solo antes de la primera donación;
- después, una solicitud que aprueba otro `ADMINISTRATOR` o el `REPRESENTATIVE`, nunca el solicitante;
- sin aprobador válido, el cambio queda bloqueado; PaxFide nunca aprueba.

| Endpoint | Auth | Entrada | Respuesta | Errores |
|---|---|---|---|---|
| `POST /campaigns/{ref}/configuration` | `ADMINISTRATOR`, `Command-Id` | `{expectedConfigurationVersion, configuration}` (la de CV-01) | `200 {campaignRef, configurationVersion}` | 400; 403; 409 `CampaignAlreadyHasDonations`, `ConfigurationVersionConflict`, `ConfigurationChangeOnClosedCampaign`, `MonetaryTermsChangeNotSupported` |
| `POST /campaigns/{ref}/configuration-change-requests` | `ADMINISTRATOR`, `Command-Id` | ídem | `201 {requestId, status: "PENDING", baseConfigurationVersion}` | 400; 403; 409 ya hay una pendiente, versión, cerrada, `MonetaryTermsChangeNotSupported`, `MonetaryRemovalNotAllowed` |
| `GET /campaigns/{ref}/configuration-change-requests` | `ADMINISTRATOR` o `REPRESENTATIVE` | — | `200 {items: [{requestId, status, baseConfigurationVersion, proposedConfiguration, requestedBy, requestedAt, decidedBy?, decidedAt?, resultingConfigurationVersion?}]}`, 50, `no-store` | 403 |
| `POST …/{requestId}/approve` | otro `ADMINISTRATOR` o el `REPRESENTATIVE`, `Command-Id` | — | `200 {requestId, status: "APPROVED", configurationVersion}` | 403 (`SelfApprovalNotAllowed` si es el solicitante); 409 no pendiente, versión avanzada, cerrada, `MonetaryRemovalNotAllowed` |
| `POST …/{requestId}/reject` | ídem, o el propio solicitante (retirarla) | — | `200 {requestId, status: "REJECTED"}` | 403; 409 no pendiente |

**Reglas nuevas** (DD-73, enmienda D2 y D4):
- una sola solicitud pendiente por convocatoria (índice único parcial, creado al arrancar);
- la propuesta se valida al pedirla;
- con intenciones de donación, nunca se quita `MONETARY`, porque se perdería el ledger.

**Tests** (`CampaignConfigurationChangeHttpIntegrationTest`, 5, rojos primero):
- edición directa y luego 409 con donaciones;
- solicitud, segunda pendiente 409, autoaprobación 403;
- aprobación por otro administrador y por el representante, visible en CV-07;
- la intención anterior conserva su versión;
- quitar `MONETARY` o cambiar la meta → 409;
- configuración avanzada → la aprobación falla y la solicitud sigue pendiente; el solicitante la retira;
- convocatoria cerrada → 409;
- el mismo 403 para quien no debe.

**Hallazgo H-IDX-1** (para revisar aparte): el `application.yml` de test de `app` sustituye al principal y no activa `auto-index-creation`. Los índices que el código declara solo con anotaciones sobre documentos sin repositorio de Spring Data no existen en los tests de `app`; por ejemplo, el índice único parcial de `campaign_assignments`. Este bloque crea el suyo de forma explícita.
