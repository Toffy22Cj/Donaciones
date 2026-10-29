# ADR-050 — Enmienda 1: el login emite con `iat = max(segundo actual truncado, credentialsNotBefore)` (P-R3-3), y actor de la confirmación de la recuperación (P-R4-1, enmienda de ADR-038 E1)

## Status

**PROPUESTA — espera la aprobación de Carlos.** La redactó Manuel, con asistencia de Claude, el 2026-10-24. La decisión de fondo (P-R3-3, opción (a) con (c)) es de Manuel, por la delegación de Carlos del 2026-10-24 (`decisiones-delegadas-2026-10.md`, nota del 2026-10-24). Carlos aprueba o cambia la enmienda en su PR.

> **Nota (2026-10-25): APROBADA por Carlos, partes 1 y 2** (`decisiones-delegadas-2026-10.md`, nota del 2026-10-25, comunicado por Manuel; texto literal pendiente de registrar). "PROPUESTA" se conserva; esta nota lo supera. La parte 3 (P-R5-1, D3) no existe todavía y no queda cubierta.

> **Nota (2026-10-29): aprobación escrita de Carlos de las partes 1 y 2, y "el iat se queda en `LoginController` (E1-2), aceptado".** Carlos aceptó que `iat = max(segundo actual truncado, credentialsNotBefore)` se quede en `LoginController`, como dice E1-2, en lugar de moverlo a `identity` (`decisiones-delegadas-2026-10.md`, nota del 2026-10-29, C-1; comunicado por Manuel). **Texto literal: [texto cortado]**: llegó cortado en "identit" y está pendiente de que Manuel lo pegue completo. Esta nota sustituye a "texto literal pendiente de registrar" de la nota anterior, que se conserva.

> **Nota (2026-10-29, más tarde): texto literal de Carlos**, que sustituye a "[texto cortado]" de la nota anterior, que se conserva (`decisiones-delegadas-2026-10.md`, nota del 2026-10-29 "texto literal de C-1 y C-5"): "Apruebo la Enmienda 1 de ADR-050 (ADR-050-enmienda-1-iat-del-login.md), partes 1 y 2. Acepto que iat = max(segundo actual truncado, credentialsNotBefore) se calcule en LoginController, como dice E1-2, y no se mueva a identity"

> **Nota (2026-10-25): parte 1 implementada** en #137 (`fix/identity-p-r3-3-iat-del-login`), fusionado en `develop` como `37827c0`. La parte 2 está en #140 (nota en su sección). *El documento de cierre de Manuel (`cierre-backend-2026-10-25.md` §2) dice "parte 1 en #135", pero #135 (`536ffe6`) solo registró la aprobación de la parte 3; el código de la parte 1 entró con #137.* El estado de arriba se conserva.

**No autoriza código por sí sola.** El código va en su propio PR, con un plan corto aprobado por Manuel (regla 3.4), y solo después de que Carlos apruebe esta enmienda. **Ese PR se fusiona antes que el código de R-4.**

**Por qué es una enmienda.** La regla 3.5 la exige cuando se cambia el contrato de un puerto que ya usa otro módulo. `AuthenticateAccountPort` está en `contracts`: lo implementa `identity` y lo llama `api`.

**Enmienda a:** ADR-050 (APROBADO — Carlos, 2026-10-18), D4 (parte 1) y D8 (parte 2). La parte 2 enmienda además **ADR-038 E1** (§9.2).
**Dos partes, una sola revisión:** la parte 2 se añadió el 2026-10-24, por indicación de Manuel, para que Carlos revise todo junto (plan R-4, P-R4-1 (a), PR #121). Carlos puede aprobar una parte y no la otra.
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

---

# Parte 2 — Actor de `CREDENTIALS_CHANGED` en la confirmación de la recuperación (P-R4-1; enmienda de ADR-038 E1)

**Estado: PROPUESTA — espera la aprobación de Carlos.** La decisión de fondo (P-R4-1, opción (a)) es de Manuel, del 2026-10-24, por la delegación de Carlos, al aprobar el plan R-4 (`plan-r4-recuperacion-de-contrasena.md` §11, PR #121). Sin esta aprobación, la confirmación de R-4 no se implementa.

> **Nota (2026-10-25): APROBADA por Carlos** (la misma nota de `decisiones-delegadas-2026-10.md`). "PROPUESTA" se conserva; esta nota lo supera.

> **Nota (2026-10-25): implementada** con R-4 en #140 (`feat/identity-r4-recuperacion-contrasena`), fusionado en `develop` como `a5e56c8`. El estado de arriba se conserva.

## Context

- **ADR-050 D8** manda que la confirmación (`POST /api/v1/auth/password-reset/confirm`, pública y sin JWT) "audita `CREDENTIALS_CHANGED` con un dato `{via: "PASSWORD_RESET"}`". No dice quién es el actor.
- **ADR-038 §2.2:** todo servicio de `identity` que escribe en el Audit Log recibe el actor como parámetro; ninguno lo deduce del objetivo.
- **ADR-038 E1** (`ADR-038-identidad-platform-administrator-verificacion-organization.md:269-274`): la única excepción es el autorregistro (`CreateAccountService`, con `selfRegistration = true`). `AccountAuditActor` solo lo construyen `CreateAccountService`, `AuditLogEntryMapper` y `AuthorizationAuditActorMapper`; `SystemAuditActor`, solo `BootstrapPlatformAuthorityService` y `AuditLogEntryMapper`. Lo comprueban las reglas de `identity/src/test/java/identity/architecture/ArchitectureTest.java:66-85`.
- En la confirmación no hay principal autenticado, así que ninguna de las clases autorizadas puede dar el actor.

## Decision

**La confirmación de la recuperación audita con `AccountAuditActor` de la cuenta del token, como el autorregistro; se añade a la lista de E1 y a la regla de `ArchitectureTest`.**

- Quien confirma demostró controlar el correo de la cuenta, porque recibió el token en él. Es el titular quien actúa, igual que en el autorregistro.
- `changeSummary = {via: "PASSWORD_RESET"}` (D8), sin contraseñas ni token.
- La clase que se añade a la lista es la que implementa la confirmación según el plan R-4 §4.2: `ChangeCredentialsService`, en `resetCredentials`. Si el plan cambiara de clase, cambia aquí con nota.
- Las demás vías de `ChangeCredentialsService` siguen recibiendo el actor (`changeCredentials`) o tomándolo del principal (`changeOwnCredentials`, con `AuthorizationAuditActorMapper`).

## Alternatives

| Alternativa | Por qué se descarta |
|---|---|
| `SystemAuditActor("password-reset")` | Exige la misma enmienda (la otra regla) y pierde que actuó el titular |
| Que `app` construya el actor y lo pase | Esquiva la regla de ArchUnit, que solo analiza `identity`, pero contradice el sentido de E1 |
| Auditar sin actor | Rompe ADR-038 §2.2 (actor obligatorio, post-corte) |

## Consequences

- La lista de E1 pasa a tener cuatro clases que construyen `AccountAuditActor`. La regla de `ArchitectureTest` se actualiza en el mismo PR que el código de R-4, con su texto de `because`.
- Hay dos excepciones al actor recibido: el autorregistro y la confirmación de la recuperación. Ambas registran a la propia cuenta, con un dato que lo dice (`selfRegistration` y `via`).

## Definición de hecho (con el código de R-4)

1. La confirmación registra `CREDENTIALS_CHANGED` con `actor = {type: ACCOUNT, accountId: <cuenta del token>}` y `changeSummary = {via: "PASSWORD_RESET"}`, sin contraseña ni token (test de R-4).
2. `ArchitectureTest` sigue en verde con la lista ampliada, y su *bite test* falla si otra clase construye `AccountAuditActor`.


---

# Parte 3 (PROPUESTA) — El login exige las aceptaciones vigentes (D3, Q1 (a); P-R5-1 del plan R-5 + D1)

## Status

**PROPUESTA — espera el sí de Carlos a este texto concreto.** La redactó Claude a petición de Manuel el 2026-10-25.

> **Nota (2026-10-25): Aprobada — Carlos, 2026-10-25, comentario 'todo aprobado' en #134, que fusionó él.** El comentario es de `Toffy22Cj`, a las 01:54:37Z (https://github.com/Toffy22Cj/Donaciones/pull/134#issuecomment-6392174129); la fusión, a las 01:54:41Z. "PROPUESTA" y las dos viñetas de abajo se conservan; esta nota las supera. El código de D3 entra en la cola después de P-R3-3 y de D1, D2 y D10 (R-5, primera parte).
- **La aprobación de Carlos del 2026-10-25** (`decisiones-delegadas-2026-10.md`, nota del 2026-10-25) cubre las partes 1 y 2, **no esta**.
- **El código de D3 no empieza** hasta que Carlos apruebe este texto (regla 3.5). El resto de R-5 (D1, D2 y D10) no depende de esta parte (P-R5-2).

> **Nota (2026-10-25): implementada** en #144 (`feat/identity-d3-aceptaciones-login`), fusionado en `develop` como `69bc88d`. El estado de arriba se conserva.

**Enmienda a:** ADR-050 (APROBADO — Carlos, 2026-10-18), §"Preguntas", fila Q1, y D3.
**Origen:** `plan-r5-d1-aceptaciones-y-perfil.md`, §3 (P-R5-1) y §4.

## Context

### La contradicción con Q1

- **D3** pide que toda cuenta acepte una versión nueva en su siguiente inicio de sesión. Su mecanismo es **Q1 (a)**: "El login no emite token hasta aceptar: responde `403 AcceptanceRequired` con las versiones pendientes y el cliente repite el login con `acceptedTermsVersion`/`acceptedDataPolicyVersion` en el cuerpo".
- **Q1 (a) se justificó así** (ADR-050, §"Preguntas", fila Q1, columna de la recomendación): "**no toca el filtro ni el puerto**".
- **En el código, sí toca el puerto:**
  - el login está en `api` (`api/src/main/java/com/traceability/api/auth/LoginController.java`) y solo ve `AuthenticateAccountPort` (`contracts`), que hoy devuelve el `accountId` o, tras la parte 1, `AuthenticatedAccount`;
  - para decidir el 403 hay que conocer las versiones aceptadas por la cuenta, que están en `identity`;
  - para registrar la aceptación del segundo intento hay que escribir en `identity`;
  - las dos cosas cambian `AuthenticateAccountPort`, que usan `api` e `identity`, y eso activa la regla 3.5.
- **El filtro** sí queda como dice Q1: no cambia (ADR-047 D5 y ADR-050 D4).

### Hechos verificados (`develop` `03328e1`, 2026-10-25)

- `AuthenticateAccountPortImpl.authenticate` hace una sola lectura (`findByEmail`) y llama siempre a `matches`, contra un hash ficticio si la cuenta no existe. Después comprueba el estado, y los tres fallos lanzan la misma `AuthenticationFailedException` (ID01-D1; ADR-047 Q5). Hoy es **de solo lectura**.
- `AccountRepositoryPort.save(Account)` **sustituye el documento entero** (`MongoAccountRepositoryAdapter.java:26-27`) y no comprueba versión.
- La parte 1 (P-R3-3, aprobada) cambia la firma a `AuthenticatedAccount authenticate(String email, String password)`. Su código es el siguiente PR de la cola, antes que D3.

## Decision

### E3-1. Una sola firma del puerto, que recibe las versiones del cuerpo

`AuthenticateAccountPort` **sustituye** su método; no añade otro. Así no queda un camino de login que se salte las aceptaciones, igual que E1-1 hizo con `credentialsNotBefore`.

```java
AuthenticatedAccount authenticate(String email, String password, AcceptedLegalVersions accepted);
```

- `AcceptedLegalVersions(String termsVersion, String dataPolicyVersion)` **[Enmienda]**, en `contracts.authentication`. Los dos campos son anulables: un login sin versiones las manda nulas.
- El resultado sigue siendo `AuthenticatedAccount` (E1-1), con el `credentialsNotBefore` de la misma lectura que validó la contraseña.

### E3-2. Orden: primero la contraseña, después las aceptaciones

Dentro de `AuthenticateAccountPortImpl`, en este orden:
1. **Lo de hoy, sin cambios:** la lectura, `matches` siempre y el estado. Cualquier fallo da `AuthenticationFailedException`, **antes de mirar las aceptaciones**. **Una contraseña incorrecta sigue dando siempre el mismo 401, nunca 403**, para no revelar que la cuenta existe ni qué versiones aceptó.
2. Se comparan las versiones aceptadas por la cuenta con las vigentes (`LegalVersions`, P-R5-3).
3. Si están al día, se devuelve `AuthenticatedAccount`, como en la parte 1.
4. Si falta alguna:
   - si `accepted` trae **exactamente las vigentes** que faltan, se guardan con la fecha (D2) y se devuelve `AuthenticatedAccount`;
   - si no las trae, o trae otras, se lanza `AcceptanceRequiredException(pendingTermsVersion?, pendingDataPolicyVersion?)` **[Enmienda]**, en `contracts.authentication`, con solo las pendientes.

### E3-3. La escritura de las aceptaciones no pisa la cuenta

`authenticate` deja de ser de solo lectura en el caso 4. Para no pisar un cambio de contraseña o una desactivación concurrentes, que guardan la cuenta entera:
- las aceptaciones se escriben con una **actualización parcial** **[Enmienda]**: un método nuevo del puerto de salida de `identity` que hace `$set` solo de los cuatro campos de aceptación, y nunca con `save(Account)`;
- la escritura exige que el documento siga teniendo el `passwordHash` que se validó y el estado `ACTIVE`. Si no coincide, el login falla con la misma `AuthenticationFailedException` (401) y no se guarda nada;
- el `credentialsNotBefore` devuelto es el de la lectura que validó la contraseña. Aceptar no lo cambia.

### E3-4. El login en `api`

- `LoginRequest` gana `acceptedTermsVersion?` y `acceptedDataPolicyVersion?` y los pasa al puerto.
- **Con `AcceptanceRequiredException`, responde `403`** con `title = AcceptanceRequired`, un cuerpo `{pendingTermsVersion?, pendingDataPolicyVersion?}` (P-R5-5), **sin token** y nunca con el texto de los términos.
- El 401 y el 500 no cambian (ID01-D1, ID01-D4). El `iat` sigue la regla `max` de E1-2.

### E3-5. Cómo se combina con la parte 1

**Dos cambios seguidos y ordenados**, cada uno en su PR:
1. La parte 1 (P-R3-3, aprobada) cambia el retorno: `String` → `AuthenticatedAccount`. Va primero, y R-4 depende de ella.
2. Esta parte 3 cambia los parámetros: `(email, password)` → `(email, password, AcceptedLegalVersions)`. Va después de D1, D2 y D10, que crean `LegalVersions` y las aceptaciones de la cuenta.

- Ninguno de los dos añade un método. Cada uno sustituye el único que hay.
- **Por qué no un único cambio:** la parte 1 está aprobada y su código va antes en la cola, porque R-4 lo necesita. La parte 3 espera a Carlos y depende de D2. Juntarlas retrasaría R-4 sin ganar nada.
- Los consumidores son los mismos en los dos cambios: `LoginController` y `LoginControllerTest` en `api`, y `AuthenticateAccountPortImpl` y `AuthenticateAccountPortImplTest` en `identity`.

## Alternatives

| Opción | Qué es | Por qué no |
|---|---|---|
| **(a), la elegida** | El puerto recibe las versiones y lanza `AcceptanceRequiredException` (E3-1 a E3-5) | — |
| **(b)** | Mover el login de `api` a `app`, que ve `identity` directamente | Cambia ADR-038 §2.7 y la organización de `api` (el login, el emisor y el filtro viven juntos). Es más grande y aun así habría que enmendar el ADR |
| **(c)** | Dejar D3 fuera de R-5 y registrarlo como pendiente | Las cuentas existentes y las de semilla nunca aceptarían una versión nueva. Incumple D3 |
| (a') | (a) con `save(Account)` en vez de la actualización parcial | Un cambio de contraseña concurrente podría quedar deshecho y volver el hash anterior (E3-3) |
| (a'') | (a) con un método nuevo junto al actual | Dejaría un camino de login sin comprobar las aceptaciones |

## Consequences

- `contracts` gana `AcceptedLegalVersions` y `AcceptanceRequiredException`, y la firma del puerto cambia por segunda vez (E3-5).
- `identity`: la autenticación escribe en un caso (E3-3), con un método nuevo de actualización parcial en su puerto de salida.
- **Al desplegar D3, todas las cuentas** (también las existentes y las de la demo) reciben 403 en su siguiente login hasta aceptar. Es lo que pide D3. La web y el móvil deben tener lista la pantalla de aceptación **antes** del despliegue (riesgo del plan R-5).
- **Sin cambios:** el filtro y el verificador (Q1 (a) en lo demás). Los tokens ya emitidos siguen hasta su `exp` (riesgo aceptado 4 de ADR-050).
- `DemoScenarioSeeder`, `scripts/demo/recorrido.py` y los tests que inician sesión mandan las versiones vigentes.

## Definición de hecho (tests, rojos primero)

1. Contraseña correcta y versiones pendientes, sin versiones en el cuerpo → `403 AcceptanceRequired` con solo las pendientes y **sin token**.
2. Repetir el login con las versiones vigentes → se guardan, con la fecha, y se emite el token. El siguiente login sin versiones da 200.
3. Contraseña incorrecta con versiones pendientes → **el mismo 401 de siempre, byte a byte**, nunca 403. Lo mismo con una cuenta inexistente o `INACTIVE`.
4. Versiones en el cuerpo que no son las vigentes → 403 con las pendientes, y no se guarda nada.
5. Cuenta al día → el login no escribe nada (aserción negativa sobre la actualización parcial).
6. **Concurrencia (E3-3):** un cambio de contraseña entre la lectura y la escritura de las aceptaciones → el login da 401, la contraseña nueva se conserva y no se guarda ninguna aceptación.
7. El `iat` del token sigue la regla `max` de E1-2 (los tests de la parte 1 siguen en verde).
8. **Mutaciones:**
   - comprobar las aceptaciones antes que la contraseña → muere el test 3;
   - guardar con `save(Account)` → muere el test 6;
   - aceptar cualquier versión → muere el test 4;
   - no exigirlas → muere el test 1.
9. `GoldenPathHttpIntegrationTest` en verde y `scripts/ci-local.sh` completo en un clon limpio.
