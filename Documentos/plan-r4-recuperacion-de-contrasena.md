# Plan R-4 — Recuperación de contraseña con envío diferido

**Estado: BORRADOR. Espera el "plan aprobado" de Manuel**, por la delegación de Carlos del 2026-10-24 (`decisiones-delegadas-2026-10.md`, nota del 2026-10-24). No se escribe código antes (regla 3.4). Carlos aprueba el PR de implementación con Approve en GitHub.
**Fecha:** 2026-10-24 (UTC).
**Contrato:** ADR-050 (APROBADO — Carlos, 2026-10-18), D8, Q5 (a) y los tests 11 a 18 de su definición de hecho. Este plan no decide nada que el ADR no fije, salvo los puntos marcados **[Plan]**, que se aprueban con el plan.
**Base:** `develop` `19b4e0f`. La rama sale del `develop` del momento de implementar, con su hash: `feat/identity-r4-recuperacion-contrasena`.
**Dueño:** Manuel.

> **APROBADO — Manuel, 2026-10-24, por la delegación de Carlos** (§11). Texto de Manuel: "Plan aprobado — R-4 (Manuel, 2026-10-24, por la delegación de Carlos), con estos cambios". El texto de §0 a §10 se conserva como se redactó; los cambios van en §11. **El código espera a tres cosas:** R-3 + R-2 fusionado, P-R3-3 fusionado y P-R4-1 aprobado por Carlos (en el PR #120).

## 0. Dependencias y bloqueos

| # | Dependencia | Estado al redactar | Efecto |
|---|---|---|---|
| **B-1** | **P-R3-3** (el login emite con `iat = max(segundo actual truncado, credentialsNotBefore)`): Enmienda 1 de ADR-050 (PR #120, PROPUESTA) y su PR de código, con plan corto aprobado por Manuel | Espera a Carlos | **El código de R-4 no se fusiona antes que el de P-R3-3** (decisión de Manuel, 2026-10-24). Sin P-R3-3, recuperar e iniciar sesión enseguida, que es el flujo normal de R-4, devuelve un token que el filtro rechaza. El test R4-19 lo comprueba y depende de P-R3-3 |
| **B-2** | R-3 + R-2 (`feat/identity-r3-r2-revocacion-contrasena`): `credentialsNotBefore`, `Account.changeCredentials(hash, now)` y `ChangeCredentialsService` | Rama publicada; el PR se abre tras `ci-local.sh` | R-4 lo reutiliza. La rama de R-4 sale de un `develop` que ya lo contenga |
| **B-3** | **Choque con ADR-038 E1** (P-R4-1, abajo): el actor de `CREDENTIALS_CHANGED` en la confirmación | Sin resolver; lo decide Carlos | El código de la confirmación depende de la decisión. La petición (pedir) no depende |

## 1. Alcance

Dentro (ADR-050 D8):
- `POST /api/v1/auth/password-reset` (pedir), pública: siempre `202`, enfriamiento de 5 minutos, token nuevo y el anterior `SUPERSEDED`, correo **después** de confirmar la transacción.
- `POST /api/v1/auth/password-reset/confirm` (confirmar), pública: token de un solo uso y 30 minutos. Guarda la contraseña, fija `credentialsNotBefore` (D4) y audita `CREDENTIALS_CHANGED` con `{via: "PASSWORD_RESET"}`. Responde `204`.
- Colección `password_reset_tokens` de `identity`, `PasswordResetMailPort` en `identity` y su implementación en `app` con `JavaMailSender` y un `ThreadPoolTaskExecutor` acotado (Q5 (a)).
- Las dos rutas en `PublicRoutes` y en el test de inventario.

Fuera: límite de intentos por IP (DH-56, riesgo aceptado 1 de ADR-050), reintentos del correo (Q5 (a)), R-5, D6, D7, Google y P-R3-3, que tiene su propio PR (B-1).

## 2. Hechos verificados en `develop` `19b4e0f` y en la rama de R-3 + R-2 (2026-10-24)

- **Patrón de ADR-049 D2 en el código:**
  - `identity/.../domain/model/InvitationToken.java`: 32 bytes de `SecureRandom`, Base64 URL sin relleno, `hashOf` (SHA-256 en hexadecimal) y `toString()` sin el token.
  - `OrganizationInvitationDocument.tokenHash` con `@Indexed(unique = true)` (`:23-24`).
  - `markAcceptedIfPending` es una escritura condicional `PENDING → ACCEPTED` (`OrganizationInvitationRepositoryPort`).
- **Envío de invitaciones hoy:** `InviteMemberService.invite` envía **después** de `executeWithRetry`, pero **en el mismo hilo de la petición**, y registra solo el id y el tipo del fallo. `SmtpInvitationMailAdapter` (`app/.../infrastructure/mail/`) exige `traceability.mail.from` y `traceability.web.base-url` (fail-fast, DD-61) y pone el token en el fragmento (`link`, `PATH = "/invitaciones"`).
- **Ejecutores:** `app/src/main` e `identity/src/main` no declaran ningún `TaskExecutor` ni usan `@EnableAsync`. R-4 añade el primero.
- **Índices:**
  - `auto-index-creation: true` (`application.yml:26`);
  - hay índices únicos parciales con `@CompoundIndex(partialFilter = …)` (`MediaObjectDocument.java:25`, `TraceabilityEventDocument.java:21`);
  - `MongoIndexInitializer` (`app/.../config/`) los asegura al arrancar.
- **Rutas públicas:** `PublicRoutes.ROUTES` está en **`api`** (`api/src/main/java/com/traceability/api/auth/PublicRoutes.java:28-43`). La tabla "Implementación" de ADR-050 pone R-4 en `identity` y `app`, pero D8 manda añadir las dos rutas a `PublicRoutes`, así que R-4 toca también `api` (solo esa lista y su test). No es un choque: lo dice D8; se anota.
- **Correo normalizado (DD-74):** `Email` recorta los extremos y pasa a minúsculas (`Email.java:18`, `:26`). El login trata un correo mal formado como inexistente (`AuthenticateAccountPortImpl.findByEmail`).
- **Auditoría:**
  - `AuditAction.CREDENTIALS_CHANGED` existe; no hay ninguna acción de "petición de recuperación", y el ADR no la pide;
  - `AuditLogEntry.record` acepta un `Map<String, Object>` de resumen;
  - **ADR-038 E1** (`ADR-038-identidad-platform-administrator-verificacion-organization.md:269-274`) y `identity/src/test/java/identity/architecture/ArchitectureTest.java:66-85`: `AccountAuditActor` solo lo construyen `CreateAccountService`, `AuditLogEntryMapper` y `AuthorizationAuditActorMapper`, y `SystemAuditActor` solo `BootstrapPlatformAuthorityService` y `AuditLogEntryMapper`. La única excepción al actor obligatorio es el autorregistro.
- **R-3 + R-2:**
  - `ChangeCredentialsService.changeCredentials(actor, accountId, nueva)` abre su propia transacción con `executeWithRetry`, y el helper rechaza las transacciones anidadas (`NestedIdentityTransactionException`);
  - `Account.changeCredentials(hash, now)` fija `credentialsNotBefore`.

## 3. Choque con un ADR (no se resuelve aquí)

**P-R4-1 — Actor de `CREDENTIALS_CHANGED` en la confirmación.** ADR-050 D8 manda auditar `CREDENTIALS_CHANGED` con `{via: "PASSWORD_RESET"}`. La confirmación es pública, sin JWT y sin principal. ADR-038 §2.2 exige que el servicio reciba el actor, y E1 solo exceptúa el autorregistro (`:269`). Ninguna de las clases autorizadas puede dar el actor aquí (`ArchitectureTest.java:66-85`).

| Opción | Qué es | Exige |
|---|---|---|
| (a) | `AccountAuditActor(<cuenta del token>)`, como el autorregistro: quien confirma demostró controlar el correo de la cuenta. `changeSummary = {via: "PASSWORD_RESET"}` | Añadir el servicio de la confirmación a la lista de E1 y a la regla de ArchUnit: enmienda de ADR-038 E1, aprobada por Carlos |
| (b) | `SystemAuditActor("password-reset")` | La misma enmienda, para la otra regla. Además pierde que fue el titular quien actuó |
| (c) | Que `app` construya el actor y lo pase | Esquiva la regla de ArchUnit, que solo mira `identity`, pero contradice el sentido de E1. **No se recomienda** |

**Recomendación: (a),** con una enmienda corta. Puede ser una nota en ADR-050 D8 que enmiende ADR-038 E1, o una Enmienda 2 de ADR-050. La confirmación no se implementa hasta que Carlos decida. Si Carlos lo prefiere, la petición puede ir antes en un PR propio.

## 4. Cambios por módulo

### `identity`

| Archivo | Cambio | Fuente |
|---|---|---|
| `domain/model/PasswordResetToken.java` (nuevo) **[Plan: nombre]** | Agregado `{id, tokenHash, accountId, status, createdAt, expiresAt, usedAt?}`, con `id` ULID interno para los logs. Hace tres cosas: `issue(accountId, secreto, now)` con `expiresAt = createdAt + 30 min`; `isUsableAt(now)` (`PENDING` y sin caducar); `isInCooldownAt(now)` (`PENDING` y `createdAt > now - 5 min`). `EXPIRED` se deriva, no se guarda | D8 |
| `domain/model/PasswordResetTokenStatus.java` (nuevo) | `PENDING`, `USED`, `SUPERSEDED` | D8 |
| Secreto del token **[Plan, P-R4-4]** | Reutilizar `InvitationToken.generate()` y `hashOf`, que son el patrón de ADR-049 D2 y no dependen de la invitación, en lugar de copiarlos | D8 ("patrón de ADR-049 D2") |
| `domain/exception/PasswordResetTokenInvalidException.java` (nuevo) | Desconocido, caducado, usado, reemplazado o cuenta `INACTIVE`: los cinco son la misma excepción | D8 |
| `application/port/out/PasswordResetTokenRepositoryPort.java` (nuevo) **[Plan: nombre]** | Cinco operaciones: `insert`; `findByTokenHash`; `findPendingByAccount`; `supersedePendingFor(accountId)`; `markUsedIfPending(id, usedAt)` (escritura condicional `status = PENDING`) | D8 |
| `application/port/out/PasswordResetMailPort.java` (nuevo) | `void dispatch(PasswordResetMail)`: **entrega** el correo para enviarlo después y vuelve enseguida; nunca lanza por un fallo de SMTP. `record PasswordResetMail(String tokenId, String to, String token, Instant expiresAt)`, con `toString()` sin token ni correo | D8, Q5 (a) |
| `application/service/RequestPasswordResetService.java` (nuevo) **[Plan: nombre]** | Ver §4.1 | D8 |
| `application/service/ChangeCredentialsService.java` | Método nuevo `resetCredentials(String token, String newPassword)` (ver §4.2). Se amplía el servicio, como pide D5 ("no se crea un servicio paralelo"), y se comparte `applyChange` con un resumen de auditoría como parámetro **[Plan]** | D8, D5 |
| `infrastructure/persistence/mongo/documents/PasswordResetTokenDocument.java` (nuevo), mapeador y adaptador | Colección `password_reset_tokens`. `tokenHash` único. **Índice único parcial `{accountId: 1}` con `{status: "PENDING"}`**, que garantiza "una sola `PENDING` por cuenta" aunque haya dos peticiones a la vez **[Plan]**. El índice va en `MongoIndexInitializer` | D8 ("Una sola `PENDING` por cuenta") |

#### 4.1 Pedir: `RequestPasswordResetService.request(String email)`

1. Correo vacío → lo filtra el controlador (400). Mal formado → igual que inexistente, como el login **[Plan]**.
2. Leer la cuenta por correo normalizado. Si no existe o está `INACTIVE`, no se hace nada más y no se escribe nada.
3. Si existe y está activa, en `executeWithRetry`:
   - `now = clock.instant()`;
   - si hay una `PENDING` en enfriamiento, no se hace nada;
   - si no, `supersedePendingFor(accountId)`, `InvitationToken.generate()` y `insert`;
   - el supplier **devuelve el secreto y el id del intento que confirmó** (Q5 (a)). El secreto se genera **dentro** del intento, así que un intento revertido nunca envía su token.
4. **Después** de que `executeWithRetry` vuelva, `mail.dispatch(...)`, una sola vez y sin reintentos.
5. **Dos peticiones simultáneas de la misma cuenta:** el índice parcial o el conflicto de escritura hacen que una gane. La otra relee y entra en enfriamiento. Un `DuplicateKeyException` del índice se trata como enfriamiento: nada que enviar y la misma respuesta **[Plan]**.
6. **Sin auditoría:** el ADR no la pide para la petición.

#### 4.2 Confirmar: `ChangeCredentialsService.resetCredentials(String token, String newPassword)`

1. Política de la nueva (`PlainPassword`) **antes** de leer nada, como en `changeCredentials`, para que una contraseña corta nunca consuma el token **[Plan, P-R4-2]**.
2. En **una** transacción con `executeWithRetry`, sin llamar a `changeCredentials` porque se anidaría:
   - `findByTokenHash(hashOf(token))`;
   - `isUsableAt(now)`;
   - la cuenta debe estar `ACTIVE`;
   - `markUsedIfPending`;
   - `account.changeCredentials(hash, now)`, que fija `credentialsNotBefore`;
   - `save`;
   - auditar `CREDENTIALS_CHANGED` con `{via: "PASSWORD_RESET"}` y el actor de P-R4-1.
3. Cualquier condición no cumplida → `PasswordResetTokenInvalidException`, y la transacción se revierte entera, sin consumir el token.
4. **Nota al plan R-3 + R-2:** su §3 decía que R-4 reutilizaría `changeCredentials(actor, accountId, nueva)`. No es posible sin anidar transacciones. Se reutilizan el dominio y `applyChange`, y el método se conserva.

### `api`

| Archivo | Cambio | Fuente |
|---|---|---|
| `auth/PublicRoutes.java` y su test | `POST /api/v1/auth/password-reset` y `POST /api/v1/auth/password-reset/confirm`, `PUBLIC` | D8 ("Rutas públicas") |

### `app`

| Archivo | Cambio | Fuente |
|---|---|---|
| `web/account/PasswordResetController.java` (nuevo) **[Plan: nombre]** | `POST /auth/password-reset {email}` → `202` sin cuerpo y con `Cache-Control: no-store` **[Plan, P-R4-3: el "mismo cuerpo" de D8 es un cuerpo vacío]**; correo vacío → 400 `InvalidRequestField`. `POST /auth/password-reset/confirm {token, newPassword}` → `204`; campo vacío → 400 sin llamar al dominio. Solo lee el cuerpo: un token en la query o en la ruta no se acepta. Nunca registra el token ni el correo. Sin `Command-Id` (DH-34) | D8 |
| `infrastructure/mail/SmtpPasswordResetMailAdapter.java` (nuevo) **[Plan: nombre]** | Implementa `PasswordResetMailPort`. `dispatch` entrega al ejecutor y vuelve. La tarea compone el texto plano (el enlace `{base}/recuperar#token=…`, la caducidad y "si no lo pediste, ignora este correo") y llama a `JavaMailSender`. Si la cola está llena (`TaskRejectedException`) o el envío falla, registra solo `tokenId` y el tipo de fallo. Usa la misma configuración que las invitaciones (`traceability.mail.from`, `traceability.web.base-url`, fail-fast) | D8, Q5 (a) |
| `config/PasswordResetMailExecutorConfig.java` (nuevo) **[Plan: nombre y valores, P-R4-5]** | `ThreadPoolTaskExecutor` propio y con nombre, que no sustituye al de Spring. 1 hilo base, 2 como máximo, cola de 100, rechazo con `AbortPolicy`. Al apagar espera hasta 10 s | Q5 (a) |
| `config/MongoIndexInitializer.java` | El índice único parcial de §4 | D8 |
| `web/IdentityApiErrorMappings.java` | `PasswordResetTokenInvalidException` → 400 `PasswordResetTokenInvalid` | D8 |

**Sin cambios:** `core`, `convocatoria`, `contracts` y el login (P-R3-3 va aparte, B-1). Ninguna dependencia nueva: `JavaMailSender` ya está (ADR-049 D1) y `ThreadPoolTaskExecutor` es de Spring.

## 5. Comportamiento ante fallos

| Situación | Resultado |
|---|---|
| Correo inexistente, mal formado o de cuenta `INACTIVE` | `202`, igual que con cuenta. Sin escritura y sin correo (riesgo aceptado 2: diferencia de milisegundos) |
| Segunda petición antes de 5 minutos | `202`, sin token nuevo y sin correo |
| SMTP caído, o cola llena | `202` igualmente; ya se respondió. Log con `tokenId` y el tipo de fallo. Sin reintentos: la persona lo pide de nuevo pasado el enfriamiento |
| La transacción de la petición falla de forma no transitoria | 500 y sin correo: el envío solo se entrega tras confirmar |
| Confirmar con un token inválido por cualquier motivo | 400 `PasswordResetTokenInvalid`, y nada cambia |
| Confirmar con contraseña corta | 400 `PasswordTooShort`; el token sigue `PENDING` |
| Dos confirmaciones simultáneas | Una `204`; la otra relee `USED` y recibe 400 |

## 6. Tests (rojos primero)

Numeración de la definición de hecho de ADR-050.

| Test | Módulo | Cubre |
|---|---|---|
| `PasswordResetTokenTest` (nuevo) | `identity` | `issue` (caduca a los 30 min), `isUsableAt` (borde de 30 min, `USED`, `SUPERSEDED`) e `isInCooldownAt` (borde de 5 min) |
| `PasswordResetServicesIntegrationTest` (nuevo, Testcontainers) **[Plan: nombre]** | `identity` | Petición: con cuenta crea un token y entrega un correo; sin cuenta o inactiva no escribe ni entrega (negativas); enfriamiento; `SUPERSEDED`; dos peticiones simultáneas dejan una sola `PENDING` y un solo correo; el correo se entrega **solo tras confirmar** (una transacción que falla no entrega nada) y con el secreto del intento que confirmó. Confirmación: 14 (los cinco motivos dan la misma excepción y nada cambia); 15 (`CyclicBarrier`); contraseña corta, que no consume el token; `credentialsNotBefore` fijado; auditoría `{via: "PASSWORD_RESET"}` sin contraseña ni token |
| `PasswordResetHttpIntegrationTest` (nuevo, Testcontainers, `JavaMailSender` de test y reloj controlado) | `app` | 11 (misma respuesta byte a byte: estado, cuerpo y cabeceras; solo el existente y activo envía); 12 (`JavaMailSender` de test **bloqueado** con un `CountDownLatch`: el `202` llega antes de que termine el envío); 13 (antes y después de 5 minutos); 14 y 15 por HTTP; 16 (confirmar revoca el JWT anterior: 401); 17 (el token no aparece en logs, respuestas ni base, donde solo está su hash; en query o ruta → 400 o 401, nunca se acepta); 18 (el enlace lleva el token tras `#`, con base `/recuperar`); campos vacíos → 400; SMTP que falla → `202` y log sin token ni correo |
| **R4-19** (en el mismo test de `app`) | `app` | **Depende de P-R3-3 (B-1):** confirmar y, en el mismo segundo (reloj fijo), iniciar sesión con la nueva → el token funciona |
| `PublicRoutesInventoryIntegrationTest`, `PublicRoutes` (test de `api`) | `api`, `app` | Las dos rutas, públicas |
| `ArchitectureTest` | `identity` | Según P-R4-1 |
| `GoldenPathHttpIntegrationTest` | `app` | Sigue en verde (30) |

## 7. Mutaciones (`scripts/mutaciones.py`)

| # | Mutación | Test que la mata |
|---|---|---|
| 1 | La petición responde distinto sin cuenta (por ejemplo, 404) | 11 |
| 2 | El correo se envía dentro de la transacción o de forma síncrona | 12 |
| 3 | Sin el enfriamiento | 13 |
| 4 | Sin el `SUPERSEDED` del token anterior | 13, y el de una sola `PENDING` |
| 5 | La confirmación no comprueba `expiresAt` | 14 (caducado) |
| 6 | `markUsedIfPending` sin la condición `status = PENDING` | 15 |
| 7 | La confirmación no fija `credentialsNotBefore` | 16 |
| 8 | Se guarda el token en claro en lugar del hash | 17 |
| 9 | El enlace lleva el token en la query en lugar del fragmento | 18 |
| 10 | La confirmación acepta una cuenta `INACTIVE` | 14 (inactiva) |
| 11 | El secreto se genera fuera del intento y se envía el de un intento revertido | Test de "solo el intento que confirmó" |

## 8. Pasos y evidencia

1. Esperar B-1 (Enmienda 1 aprobada y P-R3-3 fusionado o, como mínimo, por delante en el orden de fusión), B-2 (R-3 + R-2 en `develop`) y la decisión de P-R4-1.
2. Rama desde el `develop` del momento, con su hash.
3. Tests de §6 en rojo, con la salida literal. El rojo es una aserción o un fallo de compilación, nunca un test mal escrito.
4. Verde por capas, con el módulo completo en cada una: `identity`, `api`, `app`.
5. Mutaciones de §7.
6. Documentos:
   - `referencia-api-v1.md`: las dos rutas, el `202` uniforme, el enlace `/recuperar#token=` para la web, y que la confirmación no inicia sesión;
   - `estado-fase6.md`;
   - nota en el plan R-3 + R-2 sobre la reutilización (§4.2).
7. `free -g` con al menos 4 GB y `scripts/ci-local.sh` en un clon limpio. La evidencia va a `Documentos/evidencia-ci/`.
8. Autoauditoría contra ADR-050 D8, Q5 y los tests 11 a 18.
9. PR hacia `develop` con Carlos como revisor.

## 9. Puntos para la aprobación de Manuel

| # | Qué | Recomendación |
|---|---|---|
| **P-R4-1** | Actor de la auditoría en la confirmación (choque con ADR-038 E1, §3) | (a), con una enmienda que aprueba Carlos. Sin ella, la confirmación no se implementa |
| **P-R4-2** | Orden en la confirmación: política de la nueva antes del token | Sí: una contraseña corta nunca consume el token. Con token inválido **y** contraseña corta, gana `PasswordTooShort`, que no revela nada del token |
| **P-R4-3** | El "mismo cuerpo" del `202` | Cuerpo vacío, con `no-store` |
| **P-R4-4** | Reutilizar `InvitationToken` para el secreto | Sí, sin renombrarlo. La alternativa es extraer una clase común, que toca las invitaciones |
| **P-R4-5** | Ejecutor: 1 a 2 hilos, cola de 100, `AbortPolicy`, espera de 10 s al apagar | Aprobar |
| **P-R4-6** | Correo vacío → 400; correo mal formado → `202` como inexistente | Aprobar (igual que el login) |
| **P-R4-7** | Los nombres marcados **[Plan]** (clases, servicio y test) | Aprobar o renombrar |

## 10. Riesgos

- **Aceptados en ADR-050:** sin límite por IP (1) y la diferencia de milisegundos (2).
- **Nuevo, solo registrado:** con la cola llena o el SMTP caído, el correo se pierde hasta pasado el enfriamiento (5 minutos). Es lo que decide Q5 (a); se documenta para la web ("si no llega, vuelve a pedirlo en 5 minutos").
- **R-R3-1** (sin límite de intentos en `/account/password`) sigue como pregunta para Carlos. R-4 no lo empeora.

---

## 11. Aprobación — Manuel, 2026-10-24, por la delegación de Carlos

**Texto de Manuel en la sesión:** "Plan aprobado — R-4 (Manuel, 2026-10-24, por la delegación de Carlos), con estos cambios". La delegación es el mensaje de Carlos del 2026-10-24 por WhatsApp (`decisiones-delegadas-2026-10.md`, nota del 2026-10-24).

- **P-R4-1: opción (a).** La enmienda de ADR-038 E1 va en el **PR #120**, como segunda parte de la Enmienda 1 de ADR-050 (PROPUESTA; la aprueba Carlos), para que Carlos revise todo junto. Texto: "la confirmación de la recuperación audita con `AccountAuditActor` de la cuenta del token, como el autorregistro; se añade a la lista de E1 y a la regla de `ArchitectureTest`". Con la aprobación de Carlos, la clase autorizada es la que implementa la confirmación: `ChangeCredentialsService`, por §4.2. B-3 queda pendiente solo de esa aprobación.
- **P-R4-2 a P-R4-7: aprobados tal como están.**
- **Dos tests nuevos**, que se suman a §6 en `PasswordResetHttpIntegrationTest` (`app`):
  - **R4-20, cola llena:** un ejecutor de test que rechaza (`RejectedExecutionException`) → `202` idéntico al de los demás casos (estado, cuerpo y cabeceras), un log solo con `tokenId` y el tipo de fallo, sin correo y sin excepción hacia la petición.
  - **R4-21, `DuplicateKeyException`:** dos peticiones simultáneas de la misma cuenta que llegan al índice único parcial → las dos `202` con el mismo cuerpo, una sola `PENDING` y un solo correo.
- **Mutación nueva (12):** `dispatch` deja salir la excepción del ejecutor (`RejectedExecutionException` o `TaskRejectedException`) → muere R4-20.
- **Orden del código:** espera a R-3 + R-2 fusionado (B-2), a P-R3-3 fusionado (B-1) y a la aprobación de P-R4-1 por Carlos (B-3).

