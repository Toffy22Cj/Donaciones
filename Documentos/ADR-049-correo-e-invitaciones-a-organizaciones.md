# ADR-049 — Envío de correo e invitaciones para incorporar miembros a una organización

## Status

**APROBADO como decisión delegada — 2026-10-07T22:30Z** (autorización de trabajo autónomo (3) de Carlos, §2: "Redacta y aplica como decisión delegada un ADR nuevo"). `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` **DD-60**.

Las decisiones marcadas **[Carlos]** son suyas y no son delegadas: dependencia, Mailpit, Gmail solo por entorno, token de un solo uso de 7 días guardado como hash, fragmento, email coincidente, roles invitables y deuda de la confirmación manual. Las marcadas **[DD-n]** las tomó el agente con el criterio fijado: (1) ADR y documentos aprobados; (2) recomendaciones escritas; (3) la opción más segura, reversible y simple; en seguridad y privacidad, la más restrictiva.

Dueño: Identidad. Cubre la dependencia nueva y el mecanismo nuevo (regla 3.5). Complementa ADR-026 y ADR-038 sin contradecirlos.

## Context

### Lo que ya está decidido y no se reabre

| Decisión | Fuente |
|---|---|
| Una cuenta pertenece como máximo a una organización; toda organización tiene exactamente un `REPRESENTATIVE`; nunca una membresía sin roles | ADR-026, invariantes 1, 3 y 4 |
| "`JoinOrganization` queda descartado: la incorporación ocurre mediante `AddEmployee`"; `AssignAdministrator` exige membresía previa | ADR-026 |
| El representante no se quita directamente; se transfiere (`TransferRepresentativeAndRemove`) | ADR-026, invariante 5 |
| Respuesta 403 uniforme sin oráculo de existencia | DD-01 (ratificada) |
| Credenciales (`trackingCode`, `statusToken`) nunca en la URL ni en logs | Enmienda 3 de ADR-037; hallazgo C2/H1 |
| Al menos un responsable activo por convocatoria; retirar al último → 409 | ADR-037 §2.5; DD-51 |
| Cada acción de `identity` deja una entrada en su registro de auditoría | ADR-026, ADR-038 |

### Lo que pide Carlos (autorización (3), §1)

- Una única dependencia nueva, `spring-boot-starter-mail`. Envío por SMTP estándar; Mailpit (Docker, `scripts/demo/`) en `dev`/demo; Gmail con contraseña de aplicación posible solo por variables de entorno.
- Invitación por correo: token aleatorio de un solo uso, caduca en 7 días, guardado solo como hash. El enlace lleva el token en el fragmento `/invitaciones#token=…`. Aceptar exige estar autenticado con una cuenta cuyo email coincida. Se invita como `ADMINISTRATOR` o `EMPLOYEE`, nunca `REPRESENTATIVE`.
- Seguridad (§4): ningún endpoint revela si un email tiene cuenta; el token nunca aparece en logs, respuestas ni URLs de la API; los correos no llevan datos personales de terceros más allá del nombre de la organización.

### Hechos verificados en el código (`develop` `37a6b74`)

- `Organization` tiene `addEmployee`, `assignAdministrator`, `removeAdministrator`, `removeEmployee`, `removeMemberFromOrganization` y `transferRepresentativeAndRemove`. No tiene "cambiar el rol de un miembro" como una sola operación, ni invitaciones.
- `Email` valida el formato pero **no normaliza mayúsculas**.
- Los servicios de `identity` usan `MongoTransactionRetryHelper` (transacción con reintento) y auditan dentro de la transacción.
- `identity` no conoce `convocatoria`: las asignaciones de responsables viven en `convocatoria` (`campaign_assignments`).

## Decision

### D1. Correo: `spring-boot-starter-mail`, solo en `app` [Carlos]

- Dependencia en `app/pom.xml`; ninguna otra.
- `identity` define el puerto `InvitationMailPort` (`identity.application.port.out`). `app` lo implementa con `JavaMailSender` (`SmtpInvitationMailAdapter`). `identity` no depende de Spring Mail.
- Configuración estándar `spring.mail.*`, que se lee de variables de entorno (`SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE`).
  - **Perfil `dev`:** `localhost:1025` sin autenticación (Mailpit).
  - **Configuración base:** sin host por defecto. Sin `SPRING_MAIL_HOST`, la aplicación no arranca (*fail-fast*, como los demás secretos). **[DD-61]**
- Remitente: `traceability.mail.from` (`TRACEABILITY_MAIL_FROM`), sin valor por defecto fuera de `dev`.
- Base del enlace: `traceability.web.base-url` (`TRACEABILITY_WEB_BASE_URL`), sin valor por defecto fuera de `dev`. En `dev`, `http://localhost:5173`.
- Nunca se registran la contraseña SMTP, el token ni el cuerpo del correo. El adaptador registra solo el `invitationId` y el resultado.
- **Gmail:** `SPRING_MAIL_HOST=smtp.gmail.com`, puerto 587, STARTTLS y una contraseña de aplicación en `SPRING_MAIL_PASSWORD`, siempre en el entorno y nunca en un fichero versionado.

### D2. El agregado `OrganizationInvitation` [Carlos + DD-62]

| Campo | Contenido |
|---|---|
| `invitationId` | ULID |
| `organizationId` | la organización que invita |
| `email` | email del invitado, **normalizado en minúsculas** (DD-62) |
| `role` | `ADMINISTRATOR` o `EMPLOYEE` [Carlos] |
| `tokenHash` | SHA-256 del token, en hexadecimal; índice único. **El token nunca se guarda** [Carlos] |
| `status` | `PENDING` → `ACCEPTED` \| `REVOKED`; `EXPIRED` se deriva de `expiresAt` y no se escribe |
| `createdAt`, `expiresAt` (`createdAt` + 7 días [Carlos]), `invitedBy` (accountId), `acceptedBy`, `acceptedAt`, `revokedAt` | |
| `delivery` | `PENDING` \| `SENT` \| `FAILED`, resultado del envío (D5) |

- **Token:** 32 bytes de `SecureRandom` en Base64 URL sin relleno (256 bits). Por ser de alta entropía basta un SHA-256 sin sal: un hash no permite enumerarlo. Sin secretos nuevos. **[DD-62]**
- **Una sola invitación `PENDING` por (organización, email):** invitar de nuevo al mismo email revoca la anterior, crea otra con un token nuevo y envía otro correo. El enlace antiguo deja de valer. **[DD-62]**

### D3. El token en el fragmento: excepción consciente [Carlos]

El correo lleva `{TRACEABILITY_WEB_BASE_URL}/invitaciones#token=<token>`. El navegador **no envía el fragmento** al servidor ni lo incluye en `Referer`. La web lo lee, lo manda en el **cuerpo** de `POST /api/v1/invitations/accept` y debe borrarlo de la barra con `history.replaceState`.

Es una **excepción consciente a "ningún secreto en la URL"**, decidida por Carlos el 2026-10-07: el token está en la URL del correo y del navegador, aunque nunca en una URL de la API ni en los logs del servidor. Riesgos aceptados: el token queda en el historial del navegador si la web no lo borra, y en el buzón del invitado. Mitigaciones: un solo uso, 7 días, revocable y ligado al email (D4).

La API **nunca** acepta el token en la ruta ni en la query. Si llega ahí, se rechaza igual que un token inválido.

### D4. Aceptar [Carlos + DD-63]

`POST /api/v1/invitations/accept` con JWT y cuerpo `{token}`. En una transacción de `identity`:

1. Busca la invitación por `SHA-256(token)`.
2. Comprueba que:
   - esté `PENDING` y sin caducar;
   - el email de la cuenta autenticada, en minúsculas, sea igual al de la invitación [Carlos];
   - la cuenta esté `ACTIVE` y no pertenezca a ninguna organización (ADR-026, invariante 1).
3. `organization.addEmployee(account)` y `account.joinOrganization(org)`. Si el rol es `ADMINISTRATOR`, además `organization.assignAdministrator(account)`, así que la membresía queda `{EMPLOYEE, ADMINISTRATOR}`. Es la incorporación que manda ADR-026 ("mediante `AddEmployee`"), sin `JoinOrganization`.
4. Marca la invitación `ACCEPTED` con escritura condicional `status = PENDING`: dos aceptaciones concurrentes no pueden ganar las dos.
5. Audita `INVITATION_ACCEPTED`, `EMPLOYEE_ADDED` y, si toca, `ADMINISTRATOR_ASSIGNED`.

**Una sola respuesta para todo rechazo [DD-63]:** token desconocido, caducado, revocado o ya usado, y email que no coincide, dan **el mismo 403** `InvitationNotAcceptable`. Si respondieran distinto, quien tuviera el token sabría si existe o para quién es. La cuenta que ya pertenece a una organización da 409 `AccountAlreadyBelongsToOrganization`, como en el resto de `identity`, pero solo después de validar el token y el email, para no ser un oráculo.

El principal se resuelve en cada petición (ADR-047 D5), así que la nueva membresía vale desde la petición siguiente sin volver a iniciar sesión.

### D5. Invitar [Carlos + DD-64]

`POST /api/v1/organizations/{organizationId}/invitations` con JWT y cuerpo `{email, role}`. Pueden `ADMINISTRATOR` o `REPRESENTATIVE` de esa organización [Carlos]. Al resto, el mismo 403 (DD-01).

- **Respuesta igual exista o no la cuenta [Carlos]:** `202 {invitationId, role, expiresAt}`. No se consulta si el email tiene cuenta, ni para invitar ni para enviar. Tampoco se mira si ya pertenece a otra organización: eso se comprueba al aceptar.
- `role = REPRESENTATIVE` o un rol desconocido → 400 [Carlos]. Email mal formado → 400 `InvalidEmailFormat`, que no depende de que exista una cuenta.
- **Orden: primero la transacción, después el correo [DD-64].** La invitación se guarda con `delivery = PENDING` y luego se envía. Un fallo SMTP deja `delivery = FAILED` y se registra con el `invitationId`, nunca con el token ni el email; la respuesta sigue siendo 202. El token solo existe en memoria entre la transacción y el envío. Para reintentar, se invita de nuevo. Un fallo SMTP no da información sobre cuentas, porque no depende de ellas.
- **Organización sin verificar:** se permite invitar [DD-64]. La verificación protege las convocatorias (CV-01 exige `VERIFIED`), no la composición del equipo.
- Audita `INVITATION_CREATED` (`{role}`, sin email).

**Correo [Carlos, §4]:** asunto "Invitación a {nombre de la organización} en PaxFide". El cuerpo, en texto plano, lleva:
- el nombre de la organización;
- el rol ofrecido;
- el enlace;
- la fecha de caducidad;
- una línea: "si no esperabas esta invitación, ignora este correo".

No lleva el nombre ni el email de quien invita ni ningún otro dato de terceros. Si la organización no tiene nombre (DD-04), se usa "una organización".

### D6. Listar y revocar invitaciones [DD-65]

- `GET /api/v1/organizations/{organizationId}/invitations`, con los mismos permisos que invitar. Devuelve las invitaciones `PENDING` sin caducar: `{items: [{invitationId, emailMasked, role, createdAt, expiresAt, delivery}]}`, una página de 100. **El email va enmascarado** (`m***@dominio.org`), igual que la lista de miembros no lleva email (DD-55): basta para reconocer a quién se invitó. Nunca devuelve el token ni el hash.
- `POST /api/v1/organizations/{organizationId}/invitations/{invitationId}/revoke`, con los mismos permisos. Devuelve `200 {invitationId, status: "REVOKED"}`. Si ya no está `PENDING` (aceptada, revocada o caducada) → 409 `InvitationNotPending`. Si es de otra organización o no existe → el mismo 403. Audita `INVITATION_REVOKED`.

### D7. Cambiar el rol de un miembro (operación nueva de dominio) [Carlos + DD-66]

`Organization.changeMemberRole(accountId, target)`, con `target ∈ {ADMINISTRATOR, EMPLOYEE}`:

| `target` | Efecto | Ya en ese estado → 409 `MemberAlreadyHasRole` |
|---|---|---|
| `ADMINISTRATOR` | añade `ADMINISTRATOR` y conserva el resto (`assignAdministrator`) | si ya tiene `ADMINISTRATOR` |
| `EMPLOYEE` | añade `EMPLOYEE` si falta y después quita `ADMINISTRATOR` | si tiene `EMPLOYEE` y no `ADMINISTRATOR` |

- `REPRESENTATIVE` nunca se toca aquí: se cambia con la transferencia de ADR-026. El 409 ante un estado que ya es el pedido es la opción más restrictiva, igual que en las autoridades de plataforma (autorización (3), §3.2). A nivel de dominio, `assignAdministrator` sigue siendo idempotente (ADR-026, invariante 10): el 409 lo decide el servicio de aplicación a partir del `false` que devuelve.
- **Los roles del representante solo los cambia él mismo [DD-66].** Un `ADMINISTRATOR` no puede degradar ni ascender al `REPRESENTATIVE` (403). Es la opción restrictiva: quien responde legalmente por la organización no pierde roles por decisión de un subordinado.
- **Degradar a un responsable activo [Carlos]:** pasar a `EMPLOYEE` a quien es responsable activo con `actingRole = ADMINISTRATOR` de una convocatoria no cerrada → 409 `ActiveCampaignResponsible`. Primero hay que reemplazarlo (DD-50).
- Endpoint: `POST /api/v1/organizations/{organizationId}/members/{accountId}/role` con `{role}`. Devuelve `200 {accountId, roles}`. Audita `ADMINISTRATOR_ASSIGNED` o `ADMINISTRATOR_REMOVED` (y `EMPLOYEE_ADDED` si se añade).

### D8. Quitar un miembro [Carlos]

`POST /api/v1/organizations/{organizationId}/members/{accountId}/remove` devuelve `200 {accountId, removed: true}`. Usa `removeMemberFromOrganization` de ADR-026:
- representante → 409 `RepresentativeTransferRequired`, así que la organización nunca se queda sin `REPRESENTATIVE`;
- responsable activo de cualquier convocatoria no cerrada, con cualquier `actingRole` → 409 `ActiveCampaignResponsible`;
- no miembro o de otra organización → el mismo 403.

### D9. Responsable activo: puerto de solo lectura en `contracts` [DD-67]

`contracts/campaign/CampaignResponsibilityPort`: `Set<String> activeActingRoles(String organizationId, String accountId)`. Devuelve los `actingRole` de las asignaciones `ACTIVE` del miembro en convocatorias de esa organización que no estén `CLOSED`. Lo implementa `convocatoria` y lo consulta `identity`, igual que `CampaignInKindEligibilityPort` (D-CAMPAIGN). Sin implementación, la aplicación no arranca.

**Riesgo residual aceptado [DD-67]:** la comprobación lee `convocatoria` fuera de la transacción de `identity`. Una asignación CV-02/CV-03 concurrente con la retirada del mismo miembro podría colarse. CV-02/CV-03 ya validan la membresía del destinatario al asignar (`requireRecipient`), así que la ventana es la de dos peticiones simultáneas de administradores de la misma organización. El efecto sería una asignación activa de alguien que ya no es miembro, detectable y corregible con DD-50. No se añade un candado distribuido.

### D10. Sin cambios en eventos ni en la cadena

Nada de esto toca payloads de eventos de `core`, versiones de esquema, canonicalización, hash, Merkle ni anclaje. Las invitaciones viven en la colección `organization_invitations` de `identity`, y su rastro está en el registro de auditoría de `identity`.

## Alternatives

| Alternativa | Por qué no |
|---|---|
| Token en la query (`?token=`) | Llega a los logs de acceso, a proxies y a `Referer`. Carlos eligió el fragmento |
| Token firmado (JWT) en vez de aleatorio con hash | No se puede revocar sin guardar estado, y añade otra clave. El aleatorio con hash es más simple y revocable |
| Guardar el token cifrado | Bastaría el hash; guardarlo permitiría reenviarlo, pero también robarlo de la base |
| `JoinOrganization` con el rol de la invitación | ADR-026 lo descarta; se reutilizan `AddEmployee` y `AssignAdministrator` |
| Enviar el correo dentro de la transacción | Un SMTP lento alarga la transacción de Mongo y un reintento enviaría dos correos |
| Bandeja de salida (outbox) con reintentos de envío | Más maquinaria de la que hace falta: reinvitar cubre el reintento. Se puede añadir sin migración |
| Responder 404 o 409 distintos según el motivo del rechazo al aceptar | Sería un oráculo sobre el token y su destinatario (DD-01) |
| GreenMail para los tests | Sería otra dependencia nueva. Se usa un `JavaMailSender` de test que captura los mensajes, y Mailpit por Testcontainers, que Carlos ya autorizó |

## Consequences

- **Positivas:** una organización incorpora a su equipo sin semillas ni scripts. El token no toca la base ni los logs del servidor. Ninguna respuesta distingue si un email tiene cuenta.
- **Negativas:**
  - Un SMTP caído no reintenta solo; la lista muestra `delivery: FAILED` y hay que reinvitar.
  - La comprobación del responsable activo tiene la ventana de D9.
  - Sin `SPRING_MAIL_HOST`, la aplicación no arranca fuera de `dev`: hay una variable más que configurar.
- **Deuda registrada:**
  - **Confirmación manual de pagos** (transferencia y efectivo): **FUERA** por decisión de Carlos (2026-10-07). Motivos: (1) P1, el diseño de la confirmación independiente no está cerrado (quién confirma y con qué autoridad, R9 de la auditoría F1–F2); (2) no hay referencia de pago que ligue un ingreso real con una intención; (3) riesgo de abuso, porque una confirmación manual sin control dual crea dinero en el sistema. Registrada en `estado-fase6.md`.
  - **Normalización de `Email`:** las invitaciones comparan en minúsculas, pero `Email` (y la unicidad de cuentas) distingue mayúsculas. No se cambia aquí, porque es otra decisión con datos existentes. Se registra como hallazgo.
    - **Cerrado (encargo 5, punto 4, Carlos, 2026-10-08):** `Email` normaliza a minúsculas al construirse; registro, login e invitaciones usan el mismo valor (DD-74, evidencia `evidencia-fase6/emails-minusculas-2026-10-08.txt`).

## Definición de hecho (tests, rojos primero)

1. El token generado tiene 256 bits y la base solo guarda su SHA-256: ningún documento contiene el token.
2. El enlace del correo es `{base}/invitaciones#token=<token>`, con el token después de `#` y nunca en la ruta ni en la query.
3. Invitar a un email con cuenta y a uno sin cuenta da exactamente la misma respuesta (estado, claves del cuerpo y cabeceras salvo `invitationId`/`expiresAt`).
4. `REPRESENTATIVE` o un rol desconocido → 400; un `EMPLOYEE`, otra organización o una organización inexistente → el mismo 403.
5. Aceptar con la cuenta del email (sin distinguir mayúsculas) incorpora como `EMPLOYEE` o como `{EMPLOYEE, ADMINISTRATOR}`. El principal siguiente ya trae la organización y los roles.
6. Aceptar con otra cuenta, con un token caducado (reloj controlado), revocado, ya usado o desconocido → el mismo 403. La invitación sigue `PENDING` en el caso del email distinto.
7. Dos aceptaciones concurrentes del mismo token: una gana y la otra recibe 403.
8. Reinvitar al mismo email deja `REVOKED` la anterior; su token ya no vale.
9. Revocar una no pendiente → 409; una de otra organización → 403.
10. La lista devuelve el email enmascarado y nunca el token ni el hash.
11. Ningún log (captura de salida) ni ninguna respuesta contiene el token. El token en la query o en la ruta de `accept` no se acepta.
12. El correo enviado (Mailpit real por Testcontainers) contiene el nombre de la organización, el rol, el enlace con fragmento y la caducidad, y no contiene el email ni el id de quien invita.
13. Fallo SMTP: respuesta 202, `delivery: FAILED` y un log sin token ni email.
14. Cambiar rol: `ADMINISTRATOR` sobre quien ya lo es → 409; `EMPLOYEE` sobre quien ya lo es → 409; degradar a un responsable activo como `ADMINISTRATOR` → 409; un `ADMINISTRATOR` que cambia los roles del representante → 403.
15. Quitar: representante → 409; responsable activo → 409; tras retirarlo con DD-50, se puede quitar. Después, su principal no tiene organización.
16. Sin `SPRING_MAIL_HOST` (configuración base), la aplicación no arranca.
17. Los 19 criterios del golden path siguen en verde (`GoldenPathHttpIntegrationTest`).
