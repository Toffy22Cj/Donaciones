# ADR-050 — Enmienda 2: verificar el correo al crear la cuenta y avisar por correo de los cambios de contraseña

## Status

**PROPUESTA — espera la aprobación de Carlos.**

- **Qué se construye** lo pidió Carlos el 2026-10-28: "añade eso del enlace al correo al crear o hacer modificaciones en la cuenta respecto a la contraseña".
- **Las preguntas Q1 a Q5** (§"Preguntas") tienen una recomendación. Al aprobar la enmienda, Carlos aprueba la recomendación o elige otra opción, por escrito en el PR.
- **El cómo de la implementación** (los puntos **[Plan]**) lo decide Manuel en su plan, por la delegación del 2026-10-24.

> **Nota (2026-10-29): Carlos dejó la enmienda a cargo de Manuel, y Manuel cierra Q1 a Q5** (`decisiones-delegadas-2026-10.md`, nota del 2026-10-29, C-3; comunicado por Manuel). La enmienda fija el **qué**. Las respuestas de Manuel coinciden con la recomendación de cada pregunta:
> - **Q1 (a):** sin correo verificado no se inicia sesión.
> - **Q2 (a):** las cuentas existentes quedan sin verificar; la semilla y el administrador de plataforma nacen verificados.
> - **Q3:** 24 h.
> - **Q4:** sí hay aviso al desactivar.
> - **Q5:** sin IP ni dispositivo.
>
> **Condición de Q2:** el PR de código avisa a la web y al móvil de que **no se despliega hasta que manejen el 403 `EmailNotVerified`**, porque las cuentas reales existentes quedarán bloqueadas hasta verificar el correo.
>
> Con esto se autoriza (regla 3.5) el cambio de `AuthenticateAccountPort` (`contracts`) que describe E2-3. **No autoriza código por sí sola:** el código espera el plan `plan-adr050-enmienda-2.md` aprobado por Manuel con un comentario citable. "PROPUESTA" se conserva; esta nota lo supera.

> **Nota (2026-10-29): contradicción anotada, sin resolver (P-E2V-13).** E2-2 pide auditar `EMAIL_VERIFIED`, pero ADR-038 §2.2 (`identity/src/test/java/identity/architecture/ArchitectureTest.java:67-88`) solo deja construir `AccountAuditActor` en `CreateAccountService`, `ChangeCredentialsService` (por la Enmienda 1 de ADR-050, parte 2), `AuthorizationAuditActorMapper` y `AuditLogEntryMapper`, y `SystemAuditActor` solo en `BootstrapPlatformAuthorityService` y `AuditLogEntryMapper`. Esta enmienda no autoriza a `EmailVerificationService`. Lo decide Manuel en #163.

> **Nota (2026-10-29, más tarde): P-E2V-13 decidido por Manuel, opción (a)** (#163, comentario 6506647217; `decisiones-delegadas-2026-10.md`). El actor de `EMAIL_VERIFIED` es la cuenta. `EmailVerificationService` se añade a la regla de ADR-038 §2.2 de `ArchitectureTest.java:67-88`, como `ChangeCredentialsService` en la Enmienda 1 parte 2. La nota anterior se conserva.

**No autoriza código por sí sola** (regla 3.4). **Es una enmienda** porque introduce un mecanismo nuevo (tokens de verificación y avisos) y cambia el contrato del login, que usa `api` a través de `AuthenticateAccountPort` (regla 3.5).

**Enmienda a:** ADR-050 (APROBADO — Carlos, 2026-10-18): D1, D4, D5, D6 y D8. Usa el mismo patrón de token que ADR-049 D2 y D3 y que D8.
**Fecha:** 2026-10-28 (UTC). **Base verificada:** `develop` `993c3e8`.

## Context

### El hueco

- **Hoy cualquiera puede registrar una cuenta con un correo ajeno.** `POST /auth/register` crea la cuenta `ACTIVE` sin comprobar que el correo sea de quien se registra.
- **Lo que eso provoca:**
  - aceptaciones legales (D2, Ley 1581) a nombre de una persona que no las dio;
  - correos de recuperación (D8) que le llegan al dueño real del correo sobre una cuenta que nunca creó;
  - un correo que ya no se puede registrar, porque da `409 DuplicateEmail`.
- **El dueño del correo tampoco se entera** cuando cambia o se recupera la contraseña de su cuenta. Si alguien la cambia con un token robado (riesgo R-R3-1), nadie lo sabe.

### Hechos verificados (`develop` `993c3e8`)

- Hoy hay dos correos, los dos en texto plano y por SMTP:
  - la invitación (`SmtpInvitationMailAdapter`, ADR-049);
  - la recuperación (`SmtpPasswordResetMailAdapter`, D8).
- La recuperación ya tiene el patrón que esta enmienda reutiliza:
  - un token de 256 bits guardado como SHA-256;
  - el enlace con el token en el fragmento;
  - el envío diferido tras confirmar la transacción, con el ejecutor acotado de Q5 (a);
  - un enfriamiento de 5 minutos.
- El login ya tiene una respuesta 403 que solo llega después de comprobar la contraseña: `AcceptanceRequired` (D3). Esta enmienda sigue ese mismo modelo.
- Las cuentas de la semilla se crean en `DemoScenarioSeeder`. El administrador de plataforma inicial, en su arranque.

## Decision

### E2-1. La cuenta guarda si su correo está verificado

- `Account` gana `emailVerifiedAt` (`Instant`, nulo mientras no se verifica).
- **Cuentas nuevas:** nacen con `emailVerifiedAt = null`.
- **Cuentas de la semilla y administrador de plataforma inicial:** nacen verificadas, porque las crea el sistema.
- **Cuentas que ya existen al desplegar:** según Q2.
- `GET /me` no cambia. `GET /account` gana `emailVerified` (booleano), para que la web y el móvil lo muestren.

### E2-2. Verificación del correo

**Envío.** Al registrarse se envía el correo de verificación, de forma diferida y después de confirmar la cuenta (el mismo mecanismo que D8). El registro sigue respondiendo `201`, como hoy.

**El token**, con el patrón de D8:
- 32 bytes de `SecureRandom`, en Base64 URL sin relleno; en la base, solo su SHA-256, con índice único;
- colección `email_verification_tokens`: `{tokenHash, accountId, status: PENDING | USED | SUPERSEDED, createdAt, expiresAt, usedAt?}`;
- una sola `PENDING` por cuenta;
- caduca a las **24 horas** (Q3).

**El enlace:** `{TRACEABILITY_WEB_BASE_URL}/verificar-correo#token=<token>`, con el token en el fragmento (ADR-049 D3).

**Confirmar** — `POST /api/v1/auth/email-verification/confirm`, pública, con `{token}`:
- token `PENDING`, sin caducar y de una cuenta `ACTIVE` → lo marca `USED` con escritura condicional (de dos confirmaciones simultáneas gana una), fija `emailVerifiedAt` y audita `EMAIL_VERIFIED` sin el correo. Responde `204`. **No inicia sesión.**
- desconocido, caducado, usado, sustituido o de una cuenta `INACTIVE` → **el mismo 400** `EmailVerificationTokenInvalid`.
- una cuenta ya verificada con un token todavía `PENDING` → `204`, sin cambios ni auditoría nueva.

**Reenviar** — `POST /api/v1/auth/email-verification`, pública, con `{email}`:
- responde **siempre el mismo `202`**, exista o no la cuenta y esté o no verificada (no revela nada);
- solo una cuenta `ACTIVE` sin verificar, fuera del enfriamiento de **5 minutos**, recibe un token nuevo, y el anterior pasa a `SUPERSEDED`.

**Recuperar la contraseña también verifica.** Si alguien confirma una recuperación (D8), demostró que controla el correo: si la cuenta no estaba verificada, se fija `emailVerifiedAt` en la misma transacción. Así una persona sin verificar que olvidó la contraseña no se queda atrapada.

### E2-3. El login exige el correo verificado (Q1 (a))

- Si la contraseña es correcta y la cuenta no tiene `emailVerifiedAt`, el login responde **`403`** (`application/problem+json`, `title` `EmailNotVerified`), **sin token**.
- **El orden es:** contraseña → estado → correo verificado → aceptaciones (D3). Así una cuenta sin verificar nunca ve el 403 de aceptaciones.
- **El 401 no cambia:** con una contraseña errónea, el 401 de siempre, sin revelar si el correo está verificado.
- **Contrato del puerto:** `AuthenticateAccountPort` (en `contracts`) lanza `EmailNotVerifiedException`, nueva y en `contracts` junto a `AuthenticationFailedException`. Solo se lanza **después** de comprobar la contraseña.
  - `LoginController` la traduce al 403.
  - **[Plan]** Si la forma correcta resulta ser un campo en `AuthenticatedAccount` en lugar de una excepción, el plan lo justifica, siempre que no quede ningún camino que emita un token sin comprobarlo.
- Los tokens ya emitidos siguen valiendo hasta `exp`: el filtro no cambia. Las cuentas que ya existen dependen de Q2.

### E2-4. Avisos de seguridad por correo

Se envía un aviso, **sin enlace con token**, en cada uno de estos casos:

| Cuándo | Asunto | Contenido |
|---|---|---|
| Cambio de contraseña (D5, `POST /account/password`) | "Tu contraseña de PaxFide cambió" | La fecha y hora (Bogotá). "Si no fuiste tú, recupera tu contraseña aquí: `{web}/recuperar`". Se cerraron las demás sesiones |
| Recuperación confirmada (D8) | "Restableciste tu contraseña de PaxFide" | Lo mismo |
| Desactivación (D6), según Q4 | "Desactivaste tu cuenta de PaxFide" | La fecha y hora, y que para volver a activarla hay que escribir a la plataforma |

- **Mismo mecanismo:** diferido tras confirmar la transacción, con el mismo ejecutor y sin reintentos (Q5 (a) de ADR-050).
- **Un fallo de envío no deshace ni cambia la operación,** que ya está confirmada. Se registra con el `accountId` y el tipo de aviso, **nunca** con el correo.
- **El aviso nunca lleva:** contraseñas, tokens, la IP ni el dispositivo (Q5).
- **Sin enfriamiento:** cada cambio confirmado manda su aviso.

### E2-5. Lo que no cambia

- Las invitaciones (ADR-049). Aceptar una ya exige un correo coincidente y una sesión, que con E2-3 implica un correo verificado.
- Los claims del token (`sub`, `iat`, `exp`), D4, la Enmienda 1 y el filtro JWT.
- Ninguna dependencia nueva: `spring-boot-starter-mail` ya está.
- Nada entra en los eventos de `core`, en el hash ni en la blockchain (D11).

## Preguntas — las cierra Carlos al aprobar

| # | Pregunta | Opciones | Recomendación |
|---|---|---|---|
| Q1 | ¿Cuándo se exige el correo verificado? | (a) No se inicia sesión sin él. (b) Se entra, pero no se puede crear organización ni aceptar invitaciones | **(a)** (la recomendada el 2026-10-28): es la más simple y la más restrictiva, con menos reglas y menos casos que probar |
| Q2 | Cuentas que ya existen al desplegar | (a) Quedan **sin verificar** y deben verificar en su siguiente login (con "Reenviar"). (b) Se marcan verificadas | **(a)**, salvo la semilla y la plataforma, que nacen verificadas. Lo creado antes nunca se verificó y marcarlo "verificado" sería falso |
| Q3 | Validez del enlace | 24 h / 7 días (como la invitación) | **24 h**: se reenvía sin coste |
| Q4 | ¿Aviso al desactivar la cuenta? | Sí / No | **Sí**: quien desactiva con una contraseña robada también deja al dueño fuera |
| Q5 | Contenido del aviso | Con IP o dispositivo / sin | **Sin**: son datos personales nuevos que habría que tratar (Ley 1581) y no hacen falta para que la persona actúe |

## Alternatives

| Alternativa | Por qué se descarta |
|---|---|
| Confirmar el cambio de contraseña con un enlace por correo antes de aplicarlo | Si un atacante tiene la sesión y la contraseña actual, ya puede desactivar la cuenta. Un aviso con el camino de recuperación cubre el caso con menos fricción |
| Verificar con un código de 6 dígitos en lugar de un enlace | Necesita limitar los intentos (no existe, DH-56) para no ser adivinable. El enlace con 256 bits no lo necesita |
| No verificar y solo avisar | No resuelve el registro con un correo ajeno ni las aceptaciones a nombre de otra persona |

## Consequences

**Positivas:**
- las aceptaciones legales y la recuperación quedan ligadas a quien controla el correo;
- el dueño se entera de cualquier cambio de su contraseña;
- todo reutiliza piezas ya probadas.

**Negativas:**
- un paso más para registrarse;
- un 403 nuevo que la web y el móvil deben tratar;
- dos rutas públicas nuevas;
- una colección nueva;
- con Q2 (a), las cuentas reales anteriores deben verificar antes de volver a entrar.

**Riesgos aceptados (propuestos):**
- sin límite de intentos por IP en las rutas públicas (DH-56, como D8);
- si el correo de verificación no llega, la persona espera el enfriamiento y lo vuelve a pedir;
- un aviso que no sale no se reintenta.

**Web y móvil:**
- la pantalla "Revisa tu correo" tras registrarse, con "Reenviar";
- el 403 `EmailNotVerified` en el login, con "Reenviar";
- la ruta `/verificar-correo` (superficie nueva);
- en la web, la tarea P-6 de `paxfide-web/Documentos/plan-fase7-puesta-al-dia.md`, cuando esto esté en `develop`.

## Definición de hecho (tests, rojos primero)

**Verificación**

1. Registro → cuenta con `emailVerifiedAt` nulo, y un correo con el enlace `#token=` (captura de correos). La respuesta llega antes de que termine el envío.
2. Login de una cuenta sin verificar con la contraseña correcta → 403 `EmailNotVerified`, sin token. Con la contraseña errónea → el 401 de siempre, el mismo cuerpo byte a byte (negativa: no revela la verificación).
3. Confirmar con un token válido → 204, `emailVerifiedAt` fijado, y el login siguiente → 200.
4. Confirmar con un token desconocido, caducado (reloj controlado), usado, sustituido o de una cuenta inactiva → el mismo 400.
5. Dos confirmaciones simultáneas del mismo token (`CyclicBarrier`) → una 204 y la otra 400; una sola auditoría.
6. Reenviar: correo existente sin verificar, existente verificado, inexistente e inactivo → la misma respuesta (estado, cuerpo y cabeceras). Solo el primero envía. Dentro de los 5 minutos no se envía nada; después, un token nuevo y el anterior `SUPERSEDED`.
7. Confirmar una recuperación (D8) de una cuenta sin verificar → queda verificada.
8. La semilla y el administrador de plataforma inicial → login 200 sin verificar nada.
9. Orden: una cuenta sin verificar y sin aceptaciones vigentes → 403 `EmailNotVerified`, no `AcceptanceRequired`.
10. El token no aparece en logs, respuestas ni en la base (solo su hash), y no se acepta en la query ni en la ruta.

**Avisos**

11. Cambiar la contraseña, confirmar una recuperación y desactivar la cuenta (según Q4) → un aviso cada uno, con el asunto de E2-4. Ninguno contiene la contraseña, un token ni `#token=` (negativa).
12. Un fallo del SMTP al avisar → la operación sigue confirmada y la respuesta no cambia. El log lleva el `accountId` y no el correo.
13. Un cambio rechazado (400 `CurrentPasswordIncorrect`, `PasswordTooShort`) → ningún aviso (negativa).

**Común**

14. `GoldenPathHttpIntegrationTest` en verde y `scripts/ci-local.sh` completo en un clon limpio, con el resumen literal de Surefire.
