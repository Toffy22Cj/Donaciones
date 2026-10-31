# Plan — ADR-050 Enmienda 2: verificar el correo al crear la cuenta y avisos de seguridad (2026-10-29)

**Estado: PROPUESTO.** Espera un "plan aprobado" de Manuel que se pueda citar: un comentario de `buelvasmanuel` en el PR de este plan. Un solo mensaje aprueba el plan, sus nombres **[Plan]** y los puntos P-E2V-1 a P-E2V-12. Si Manuel responde a un punto, su respuesta sustituye a la recomendación. **No hay código antes de esa aprobación** (regla 3.4).

> **Nota (2026-10-29): APROBADO por Manuel**, por la delegación de Carlos (comentario de `buelvasmanuel` en #158, https://github.com/Toffy22Cj/Donaciones/pull/158#issuecomment-6504627552; registrado en `decisiones-delegadas-2026-10.md`). P-E2V-1 a P-E2V-12 se aprueban como se recomiendan. **P-E2V-3:** los administradores de plataforma existentes no se marcan como verificados; antes de desplegar hay que comprobar el SMTP de producción. "PROPUESTO" se conserva; esta nota lo supera.

**Qué entra** lo fija `ADR-050-enmienda-2-verificacion-correo-y-avisos.md` (E2-1 a E2-5). Carlos dejó la enmienda a cargo de Manuel, y Manuel cerró Q1 a Q5 el 2026-10-29: (a), (a), 24 h, sí, sin IP (`decisiones-delegadas-2026-10.md`, nota del 2026-10-29, C-3). Este plan fija el **cómo** (los puntos [Plan] de la enmienda).

**Base verificada:** `develop` `993c3e8`. Cada ruta:línea de este plan se abrió sobre esa base. **Depende de #157**, que trae la enmienda a `Documentos/`.

---

## 0. Común

- **Un PR de código**, `feat/identity-adr050-e2-verificacion-correo` **[Plan]**, hacia `develop`. Si #157 no está fusionado, sale de su rama y el cuerpo dice "depende de #157".
- **Ciclo:**
  - rojo primero (aserción o compilación), con la salida literal;
  - verde por capas con el módulo completo: `contracts`, `identity`, `api` y `app`;
  - mutaciones con `scripts/mutaciones.py` en un clon limpio, con la mutación 0 de línea base y el test que mata cada una;
  - `free -g` ≥ 4 GB y `scripts/ci-local.sh` en un clon limpio del commit exacto, con la salida en `Documentos/evidencia-ci/`.
- **Cambio de puerto autorizado:** `AuthenticateAccountPort` (`contracts`), por E2-3 y la regla 3.5. **No se cambia ningún otro puerto que use otro módulo.** Los puertos de salida nuevos o ampliados son internos de `identity`.
- **Aviso a la web y al móvil** en el PR, **antes de desplegar** (condición de Q2):
  - no se despliega hasta que las dos clientes manejen el 403 `EmailNotVerified`;
  - las cuentas reales existentes quedan bloqueadas hasta verificar el correo;
  - rutas nuevas: `POST /auth/email-verification` y `/auth/email-verification/confirm`;
  - `emailVerified` en `GET /account`;
  - la pantalla `/verificar-correo` de la web.

## 1. Archivos por módulo

### `contracts`

| Archivo | Cambio | Enmienda |
|---|---|---|
| `authentication/EmailNotVerifiedException.java` (nuevo; nombre fijado por la enmienda) | `RuntimeException` sin datos, junto a `AuthenticationFailedException`. Su javadoc dice que solo se lanza después de la contraseña y el estado | E2-3 |
| `authentication/AuthenticateAccountPort.java` | La firma no cambia. El javadoc gana `@throws EmailNotVerifiedException` y el orden: contraseña → estado → correo verificado → aceptaciones. Así **no** queda un camino de login que emita sin comprobarlo (P-E2V-1) | E2-3 |

### `identity`

| Archivo | Cambio | Enmienda |
|---|---|---|
| `domain/model/Account.java` | Campo `emailVerifiedAt` (`Instant`, nulo). `markEmailVerified(Instant now)` **[Plan]** devuelve si cambió: no hace nada si ya está verificada. `isEmailVerified()` **[Plan]**. Las sobrecargas de `reconstitute` reciben el campo. `createAccount` (`Account.java:70`) nace sin verificar | E2-1 |
| `infrastructure/persistence/mongo/documents/AccountDocument.java` y `mappers/AccountMapper.java` | `emailVerifiedAt`, en las dos direcciones. `save` reemplaza el documento entero (`MongoAccountRepositoryAdapter.java:27`): un mapeador que olvidara el campo borraría la verificación: lo cubre el test 3 (login después de otra escritura) | E2-1 |
| `application/port/out/AccountRepositoryPort.java` y `MongoAccountRepositoryAdapter.java` | `markEmailVerifiedIfUnverified(AccountId, Instant)` **[Plan]**: `$set` condicionado a que `emailVerifiedAt` no exista y a `status = ACTIVE`; devuelve si escribió. Lo usan la confirmación y el relleno de la semilla (P-E2V-4) | E2-2 |
| `domain/model/EmailVerificationToken.java` **[Plan]** y `EmailVerificationTokenStatus.java` **[Plan]** (`PENDING`, `USED`, `SUPERSEDED`) | Copia el patrón de `PasswordResetToken`, con `VALIDITY = 24 h` (Q3) y `COOLDOWN = 5 min`. Clase propia, sin generalizar la de D8 (P-E2V-6) | E2-2 |
| `application/port/out/EmailVerificationTokenRepositoryPort.java` **[Plan]**, `documents/EmailVerificationTokenDocument.java` **[Plan]** (colección `email_verification_tokens`, nombre fijado por la enmienda) y `MongoEmailVerificationTokenRepositoryAdapter.java` **[Plan]** | Los mismos métodos que `PasswordResetTokenRepositoryPort`: `insert`, `findByTokenHash`, `findPendingByAccount`, `supersedePendingFor` y `markUsedIfPending`. Índice único sobre `tokenHash` e índice único parcial `uq_pending_per_account` sobre `accountId` con `status = PENDING` | E2-2 |
| `application/port/out/EmailVerificationMailPort.java` **[Plan]** | `dispatch(EmailVerificationMail)`, con `record EmailVerificationMail(tokenId, to, token, expiresAt)` y un `toString` que solo enseña `tokenId`, como `PasswordResetMailPort` | E2-2 |
| `application/port/out/SecurityNoticeMailPort.java` **[Plan]** | `dispatch(SecurityNotice)`, con `record SecurityNotice(accountId, to, kind, occurredAt)` y `enum Kind {PASSWORD_CHANGED, PASSWORD_RESET, ACCOUNT_DEACTIVATED}` **[Plan]**. Su `toString` solo enseña `accountId` y `kind` | E2-4 |
| `application/service/EmailVerificationService.java` **[Plan]** | `request(String email)`: el reenvío, con la estructura de `RequestPasswordResetService`. Solo una cuenta `ACTIVE` sin verificar y fuera del enfriamiento recibe un token nuevo; el anterior pasa a `SUPERSEDED`; el correo sale después de confirmar. `confirm(String token)`: token por su hash, utilizable; cuenta `ACTIVE`; si ya está verificada, nada (P-E2V-7); `PENDING → USED` condicional; `markEmailVerifiedIfUnverified`; auditoría `EMAIL_VERIFIED`, todo en una transacción | E2-2 |
| `domain/exception/EmailVerificationTokenInvalidException.java` **[Plan]** | Una sola excepción para todos los casos de token inválido (desconocido, caducado, usado, sustituido, cuenta inactiva) | E2-2 |
| `domain/model/AuditAction.java` | `EMAIL_VERIFIED` (nombre fijado por la enmienda). Sus datos nunca llevan el correo | E2-2 |
| `application/service/AuthenticateAccountPortImpl.java` | Entre el estado (`:71`) y las aceptaciones (`:74`): si no está verificada, `EmailNotVerifiedException`. Antes de esta comprobación nada cambia, así que el coste del 401 sigue siendo el mismo | E2-3 |
| `application/service/CreateAccountService.java` | El registro público (`:53`) emite el primer token dentro de la transacción del alta y despacha el correo después. El alta de sistema (`:70`), que usan la semilla y los tests, nace **verificada** (P-E2V-2) | E2-1, E2-2 |
| `application/service/ChangeCredentialsService.java` | `changeOwnCredentials` (`:82`) envía el aviso `PASSWORD_CHANGED` después de confirmar. `resetCredentials` (`:108`) fija `emailVerifiedAt` en la misma transacción si faltaba, con auditoría `EMAIL_VERIFIED` `{via: "PASSWORD_RESET"}` (P-E2V-8), y después envía `PASSWORD_RESET`. `changeCredentials` (`:62`, sin contraseña actual, de administración) no avisa (P-E2V-9) | E2-2, E2-4 |
| `application/service/DeactivateAccountService.java` | `deactivateOwnAccount` (`:73`) envía `ACCOUNT_DEACTIVATED` después de confirmar, solo si la cuenta cambió. `deactivateAccount` (`:107`, por la plataforma) no avisa (P-E2V-9) | E2-4, Q4 |
| `application/service/BootstrapPlatformAuthorityService.java` | Antes de `grantPlatformAuthorityIfAbsent` (`:73`), y en la misma transacción, la cuenta destino queda verificada (P-E2V-3) | E2-1 |
| `application/service/AccountEmailQuery.java` | `account(String accountId)` **[Plan]** devuelve `{email, emailVerified}`. `emailOf` se conserva | E2-1 |

### `api`

| Archivo | Cambio | Enmienda |
|---|---|---|
| `auth/LoginController.java` | `catch (EmailNotVerifiedException)` → 403 `application/problem+json`, con `title` `EmailNotVerified`, `detail` fijo y sin token. Va entre el 401 (`:82`) y el 403 de aceptaciones (`:85`). Log en `debug` sin el correo | E2-3 |
| `auth/PublicRoutes.java` | `POST /api/v1/auth/email-verification` y `POST /api/v1/auth/email-verification/confirm`, públicas, junto a las de recuperación (`:34-35`) | E2-2 |

### `app`

| Archivo | Cambio | Enmienda |
|---|---|---|
| `web/account/EmailVerificationController.java` **[Plan]** | Las dos rutas, con la misma forma que `PasswordResetController`: solo el cuerpo; un campo vacío → 400 sin llamar al dominio; `202` sin cuerpo y `no-store`, o `204` | E2-2 |
| `web/IdentityApiErrorMappings.java` | `EmailVerificationTokenInvalidException` → 400 (`title` `EmailVerificationTokenInvalid`) | E2-2 |
| `web/account/AccountController.java` | `AccountResponse` gana `emailVerified` (`:20`). Es un cambio aditivo | E2-1 |
| `infrastructure/mail/SmtpEmailVerificationMailAdapter.java` **[Plan]** | Enlace `{base}/verificar-correo#token=…`. Las mismas validaciones de configuración y los mismos logs (solo `tokenId`) que `SmtpPasswordResetMailAdapter` | E2-2 |
| `infrastructure/mail/SmtpSecurityNoticeMailAdapter.java` **[Plan]** | Los tres asuntos de E2-4, con la hora de Bogotá (P-E2V-10) y `{base}/recuperar` sin token. Un fallo se registra con `accountId` y `kind`, nunca con el correo | E2-4 |
| `config/PasswordResetMailExecutorConfig.java` | Sin cambios. Los dos adaptadores nuevos usan el mismo ejecutor, `@Qualifier(EXECUTOR)` (E2-4: "el mismo ejecutor"; P-E2V-5) | E2-4 |
| `bootstrap/DemoSeedRunner.java` | Las cuentas nuevas de la semilla nacen verificadas por el alta de sistema. Las que ya existen se marcan con `markEmailVerifiedIfUnverified`, que es idempotente (P-E2V-4) | E2-1, Q2 |

**Sin cambios:** el filtro JWT, los claims, `core`, `convocatoria` y `crypto`. No entra ninguna dependencia nueva (`spring-boot-starter-mail` ya está).

## 2. Tests — Definición de hecho 1 a 14, con el rojo esperado

Los HTTP van en `EmailVerificationHttpIntegrationTest` **[Plan]** (`app`, Testcontainers), con el `CapturingMailSender` de `PasswordResetHttpIntegrationTest` (latch y fallo por destinatario) y un `Clock` de test. Los de servicio van en `EmailVerificationServicesIntegrationTest` **[Plan]** (`identity`).

| # | Test | Rojo esperado |
|---|---|---|
| 1 | Registro → `emailVerifiedAt` nulo y un correo con `#token=`. Con el envío bloqueado por el latch, el `201` llega antes | Compilación: `Account.getEmailVerifiedAt` no existe. Con stubs, la aserción "un correo capturado" falla con 0 |
| 2 | Cuenta sin verificar: contraseña correcta → 403 `EmailNotVerified` sin `token`; errónea → el 401 con el mismo cuerpo byte a byte que el de un correo inexistente | Compilación: `EmailNotVerifiedException` no existe. Después, `expected 403 but was 200` |
| 3 | Confirmar con un token válido → 204 y `emailVerifiedAt` fijado. Después de cambiar el perfil (otra escritura de la cuenta), el login → 200 | Ruta inexistente: `expected 204 but was 401` (sin JWT); con la ruta pública, 404 |
| 4 | Token desconocido, caducado (reloj +24 h +1 s), usado, sustituido o de una cuenta inactiva → el mismo 400, con cuerpos iguales | `expected 400 but was 404` hasta que existan la ruta y el mapeo |
| 5 | Dos confirmaciones simultáneas (`CyclicBarrier`) → una 204 y otra 400; una sola `EMAIL_VERIFIED` | Falla sin el `markUsedIfPending` condicional: dos 204 o dos auditorías |
| 6 | Reenvío para una cuenta sin verificar, una verificada, una inexistente y una inactiva → la misma respuesta (estado, cuerpo y cabeceras); solo la primera envía. Dentro de 5 min, nada; después, un token nuevo y el anterior `SUPERSEDED` | `expected 202 but was 404`; después, "un correo" con 0 |
| 7 | Confirmar una recuperación de una cuenta sin verificar → verificada; el login siguiente → 200 | `expected 200 but was 403` (el reset aún no verifica) |
| 8 | Semilla (`DemoSeedRunner`) y administrador inicial (`BootstrapPlatformAuthorityService`) → login 200 sin verificar | `expected 200 but was 403` en cuanto E2-3 está en el login |
| 9 | Una cuenta sin verificar y sin aceptaciones vigentes → 403 `EmailNotVerified`, no `AcceptanceRequired` | `title` es `AcceptanceRequired` si la comprobación va después de las aceptaciones |
| 10 | El token no aparece en logs (`CapturedOutput`), respuestas ni en la base (solo `tokenHash`); en la query o en la ruta no se acepta | Falla si el adaptador o el controlador lo registran; la ruta con `?token=` debe dar 400 |
| 11 | Cambio de contraseña, recuperación confirmada y desactivación propia → un aviso cada una, con el asunto de E2-4. Ninguno contiene la contraseña, un token ni `#token=` | "un aviso" con 0 |
| 12 | El SMTP falla al avisar → la operación sigue confirmada y la respuesta no cambia; el log lleva `accountId` y no el correo | Sin el `try/catch` del adaptador: respuesta 500 o log con el correo |
| 13 | Cambio rechazado (400 `CurrentPasswordIncorrect`, `PasswordTooShort`) → ningún aviso | Falla si el aviso sale antes de confirmar |
| 14 | `GoldenPathHttpIntegrationTest` en verde y `scripts/ci-local.sh` completo en un clon limpio, con el resumen literal de Surefire | — |

Además, unitarios (rojos por compilación):
- `EmailVerificationTokenTest` **[Plan]** (validez de 24 h, enfriamiento);
- `LoginControllerTest`, ampliado con el 403;
- `PublicRoutesTest`, con las dos rutas;
- `AuthenticateAccountPortImplTest`, con el orden;
- `PublicRoutesInventoryIntegrationTest`, con las rutas nuevas;
- `MongoIndexesAtStartupIntegrationTest`, con los índices de `email_verification_tokens`.

**Tests que cambian sin perder aserciones** (P-E2V-2): los que registran por HTTP y después inician sesión (`P2EndpointsHttpIntegrationTest`, `AuthenticationEndToEndIntegrationTest`, `OrganizationMembersAndInvitationsHttpIntegrationTest`, `AccountRegistrationAndProfileHttpIntegrationTest` y `AccountHttpIntegrationTest`). Verifican el correo con el token del correo capturado, por la ruta pública, antes del login. Ninguno se desactiva ni se borra.

## 3. Mutaciones (`scripts/mutaciones.py`, mutación 0 = línea base)

| # | Mutación | Debe matarla |
|---|---|---|
| 1 | Quitar la comprobación de E2-3 en `AuthenticateAccountPortImpl` | 2, 9 |
| 2 | Ponerla antes de la contraseña | 2 (el 401 deja de ser idéntico) |
| 3 | Ponerla después de las aceptaciones | 9 |
| 4 | `markUsedIfPending` sin la condición `PENDING` | 5, 4 (usado) |
| 5 | `supersedePendingFor` vacío | 6 (`SUPERSEDED`) |
| 6 | Enfriamiento a 0 | 6 |
| 7 | Validez de 7 días en lugar de 24 h | 4 (caducado) |
| 8 | `resetCredentials` sin verificar | 7 |
| 9 | El alta de sistema nace sin verificar | 8 |
| 10 | El bootstrap no verifica | 8 |
| 11 | El aviso se envía antes de la comprobación de la contraseña actual | 13 |
| 12 | El adaptador de avisos registra `to` | 12 |
| 13 | El mapeador olvida `emailVerifiedAt` al guardar | 3 |

## 4. Puntos a decidir (P-E2V-n), con recomendación

| # | Punto | Recomendación |
|---|---|---|
| P-E2V-1 | ¿Excepción o campo en `AuthenticatedAccount` (E2-3 [Plan])? | **Excepción** `EmailNotVerifiedException`. La firma no cambia y ningún llamador puede emitir sin pasar por ella, igual que `AcceptanceRequiredException` |
| P-E2V-2 | ¿El alta de sistema (`CreateAccountService.java:70`, semilla y tests) nace verificada? | **Sí.** "Las crea el sistema" (E2-1). Así los tests que crean cuentas con ella (unos 40 archivos) siguen igual. Los 5 tests HTTP que registran por la ruta pública verifican con el correo capturado |
| P-E2V-3 | Administrador de plataforma inicial: ¿cuándo queda verificado? | **En el bootstrap**, en la misma transacción. La cuenta ya existe (registrada por la ruta pública) cuando se nombra administrador. **Riesgo:** los administradores de plataforma que ya existen en producción no pasan por el bootstrap y, por Q2 (a), quedan sin verificar; recuperan el acceso con "Reenviar" o con la recuperación. ¿Se acepta, o se marcan al desplegar las cuentas con `platformAuthority`? |
| P-E2V-4 | Cuentas de la semilla que ya existen en una base desplegada | **`DemoSeedRunner` las marca** con la escritura condicional, que es idempotente. Sin esto, la demo ya desplegada quedaría bloqueada |
| P-E2V-5 | Ejecutor del correo | **El mismo** de la recuperación (E2-4). Comparte la cola de 100; un rechazo se registra y no cambia la respuesta |
| P-E2V-6 | ¿Generalizar `PasswordResetToken`? | **No.** Clase y colección propias, con la colección que fija la enmienda. Se copia el patrón probado y D8 no se toca |
| P-E2V-7 | Cuenta ya verificada con un token `PENDING` | **204 sin escribir nada:** ni `USED`, ni auditoría, como dice E2-2 |
| P-E2V-8 | Auditoría cuando la recuperación verifica | **`EMAIL_VERIFIED` con `{via: "PASSWORD_RESET"}`**, además del `CREDENTIALS_CHANGED` de siempre |
| P-E2V-9 | ¿Avisar en los caminos administrativos (`changeCredentials` sin contraseña actual; desactivación por la plataforma)? | **No.** E2-4 nombra D5, D8 y D6 (la propia cuenta), y el asunto "Desactivaste tu cuenta" solo vale para la propia |
| P-E2V-10 | Zona horaria del aviso | **America/Bogota**, como dice E2-4. El correo de recuperación (D8) sigue en UTC y no se toca |
| P-E2V-11 | Registro cuando el primer correo de verificación no sale (cola llena o SMTP caído) | **El `201` no cambia;** la persona usa "Reenviar" pasado el enfriamiento (riesgo aceptado de la enmienda). El log lleva solo `tokenId` |
| P-E2V-12 | `estado-fase6.md` | **§0.70** para este trabajo (serie 0.70 a 0.79). R-1 usa §0.71 |

## 5. Documentos que se actualizan con el PR de código

- `referencia-api-v1.md`:
  - las dos rutas nuevas;
  - el 403 `EmailNotVerified` del login;
  - `emailVerified` en `GET /account`;
  - el 400 `EmailVerificationTokenInvalid`.
- `ADR-050-enmienda-2-verificacion-correo-y-avisos.md`: una nota fechada "implementada en #N".
- `estado-fase6.md` §0.70.
- El aviso a la web y al móvil en el cuerpo del PR, con la condición de Q2.
