# ADR-050 — Enmienda 1: el login emite con `iat = max(segundo actual truncado, credentialsNotBefore)` (P-R3-3)

## Status

**PROPUESTA — espera la aprobación de Carlos.** La redactó Manuel, con asistencia de Claude, el 2026-10-24. La decisión de fondo (P-R3-3, opción (a) con (c)) es de Manuel, por la delegación de Carlos del 2026-10-24 (`decisiones-delegadas-2026-10.md`, nota del 2026-10-24). Carlos aprueba o cambia la enmienda en su PR.

**No autoriza código por sí sola.** El código va en su propio PR, con un plan corto aprobado por Manuel (regla 3.4), y solo después de que Carlos apruebe esta enmienda. **Ese PR se fusiona antes que el código de R-4.**

**Por qué es una enmienda.** La regla 3.5 la exige cuando se cambia el contrato de un puerto que ya usa otro módulo. `AuthenticateAccountPort` está en `contracts`: lo implementa `identity` y lo llama `api`.

**Enmienda a:** ADR-050 (APROBADO — Carlos, 2026-10-18), D4.
**Origen:** plan R-3 + R-2 (`plan-r3-r2-revocacion-y-cambio-de-contrasena.md` §10.2) y la implementación de R-3 + R-2 (`feat/identity-r3-r2-revocacion-contrasena`), donde apareció el defecto.

## Context

### El defecto

D4 fija `credentialsNotBefore = max(segundo actual truncado, credentialsNotBefore actual) + 1 s` al cambiar o recuperar la contraseña y al desactivar. Por el `+ 1 s`, y por el `max` cuando hay varios cambios seguidos, el valor queda **uno o varios segundos en el futuro**.

El login emite hoy con `iat = ahora`. Un login en ese intervalo devuelve un token con `iat < credentialsNotBefore`, que el filtro rechaza (401) en la primera petición. La contraseña era correcta y el usuario no entiende por qué lo echan.

- Con `POST /account/password` (D5) no pasa: el token llega en la respuesta con `iat = credentialsNotBefore`.
- Con la recuperación (R-4, D8) es **el flujo normal**: recuperar la contraseña e iniciar sesión enseguida.
- Después de una desactivación y una reactivación, o de un cambio desde otro dispositivo, pasa solo si el login cae en ese segundo.

Lo reproduce `AccountPasswordHttpIntegrationTest.deactivateReactivate_andAPreviousTokenIs401`, con reloj fijo. En la rama de R-3 + R-2, ese test avanza el reloj un segundo antes de volver a iniciar sesión. `referencia-api-v1.md` lo registra como defecto conocido.

### Hechos verificados en el código (`develop` `19b4e0f` y la rama de R-3 + R-2, 2026-10-24)

- El token del login se emite en `api/src/main/java/com/traceability/api/auth/LoginController.java:62` (`tokenIssuerPort.issue(accountId)`).
- `api` solo recibe el `accountId`: `String authenticate(String email, String password)` (`contracts/src/main/java/com/traceability/contracts/authentication/AuthenticateAccountPort.java:13`, llamado en `LoginController.java:55`). `api` no depende de `identity`.
- La cuenta ya se carga una vez, en `identity/src/main/java/identity/application/service/AuthenticateAccountPortImpl.java:39` (`findByEmail`). Ahí se comprueban la contraseña (`:41`) y el estado (`:43`), y solo se devuelve el id (`:46`).
- Consumidores del puerto: `api` (`LoginController` y `LoginControllerTest`) e `identity` (`AuthenticateAccountPortImpl` y `AuthenticateAccountPortImplTest`). No hay otros.
- `TokenIssuerPort.issue(String accountId, Instant issuedAt)` ya existe tras R-3 + R-2 (ADR-050 D4).

## Decision

### E1-1. El puerto devuelve también `credentialsNotBefore`

`AuthenticateAccountPort.authenticate(String email, String password)` devuelve un registro `{accountId, credentialsNotBefore}` en lugar de `String`:
- `credentialsNotBefore` es nulo si la cuenta no lo tiene;
- se lee **del mismo documento que validó la contraseña**, en la misma lectura (`AuthenticateAccountPortImpl.java:39`).

**Cambia la firma; no se añade un método.** Así no queda un camino de login que ignore `credentialsNotBefore`. Los fallos no cambian: siguen siendo la misma `AuthenticationFailedException`, indistinguible y con el mismo coste (ID01-D1, ADR-047 Q5).

### E1-2. El login emite con `max`

`LoginController` emite con `tokenIssuerPort.issue(accountId, iat)`, donde `iat = max(segundo actual truncado, credentialsNotBefore)`. Si `credentialsNotBefore` es nulo, `iat` es el segundo actual, como hoy. `exp` sigue siendo `iat + ttl` (D5).

### Por qué es seguro

- **D4 ya acepta un `iat` futuro:** "Un token puede salir con `iat` unos segundos en el futuro tras varios cambios seguidos; el filtro no valida `iat` futuro (ADR-047 D5), así que no afecta". D5 usa la misma técnica (`iat = credentialsNotBefore`).
- **No revive ningún token anterior.** El login demuestra que se conoce la contraseña **vigente**, y el `credentialsNotBefore` usado es el del mismo documento que la validó. Un token emitido antes del cambio sigue con su `iat` antiguo y sigue rechazado.
- `exp` puede quedar unos segundos más tarde que con `iat = ahora`. Es la misma diferencia que D5 ya admite.

## Alternatives

| Alternativa | Por qué se descarta |
|---|---|
| **(b) Un puerto nuevo de solo lectura** que `LoginController` llama después de `authenticate` para leer `credentialsNotBefore` (dos lecturas) | **Por seguridad.** Si la contraseña cambia entre las dos lecturas, un login hecho con la contraseña **anterior** recibiría un token con el `credentialsNotBefore` nuevo, válido después del cambio. Eso se salta la revocación de D4. Con una sola lectura (E1-1), el valor corresponde siempre a la contraseña que se validó |
| **Añadir un método** al puerto y conservar `String authenticate(...)` | Dejaría un camino de login que emite con la hora actual e ignora `credentialsNotBefore`, y el defecto volvería en cuanto alguien lo usara |
| **No hacer nada** (documentarlo y que el cliente reintente al segundo) | Con R-4, el flujo normal (recuperar e iniciar sesión enseguida) fallaría |
| **Quitar el `+ 1 s` de D4** | Reabre lo que D4 cierra: un token viejo emitido en el mismo segundo que el cambio seguiría valiendo (ADR-050 test 2) |

## Consequences

- Cambia un contrato de `contracts` y sus dos consumidores, en un PR: `api` (`LoginController`, `LoginControllerTest`) e `identity` (`AuthenticateAccountPortImpl`, `AuthenticateAccountPortImplTest`). `core` y `convocatoria` no lo usan.
- El token del login puede salir con `iat` unos segundos en el futuro justo después de una revocación. Los claims siguen siendo `sub`, `iat` y `exp` (ADR-047 Q4).
- **Otros logins:** el login con Google (ADR-050, `POST /api/v1/auth/google`, R-6) emite tokens y debe aplicar la misma regla. Su plan lo cubre; esta enmienda no lo implementa.
- **R-5 (D3, Q1 (a))** también cambia el login (`403 AcceptanceRequired`). Esta enmienda no decide cómo llegan al login las versiones aceptadas. Si R-5 necesita datos de la cuenta en el login, su plan lo dirá, y cualquier cambio del puerto pasa otra vez por la regla 3.5.
- Se quita el defecto conocido de `referencia-api-v1.md` (nota fechada) y el avance de un segundo de `deactivateReactivate_andAPreviousTokenIs401`.

## Definición de hecho (tests, rojos primero)

1. Con reloj fijo: cambiar la contraseña y, en el mismo segundo, iniciar sesión con la nueva → el token funciona.
2. Dos cambios seguidos y un login inmediato → el token funciona.
3. Negativa: el token anterior al cambio sigue recibiendo 401.
4. `deactivateReactivate_andAPreviousTokenIs401` sin el avance de un segundo: el login del mismo segundo funciona.
5. Cuenta sin `credentialsNotBefore` → el login emite con el segundo actual, como hoy.
6. Los fallos del login siguen siendo el mismo 401, indistinguibles (tests existentes de `LoginControllerTest` y `AuthenticateAccountPortImplTest`).
7. **Mutación:** el login emite con el reloj e ignora `credentialsNotBefore` → mueren los tests 1, 2 y 4.
8. `GoldenPathHttpIntegrationTest` en verde y `scripts/ci-local.sh` completo en un clon limpio.
