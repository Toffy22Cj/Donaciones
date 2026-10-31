# Plan — ADR-050 R-6: iniciar sesión con Google (D9, tests 27 a 29) (2026-10-30)

**Estado: PROPUESTO.** Espera un "plan aprobado" de Manuel que se pueda citar: un comentario de `buelvasmanuel` en el PR de este plan. Un solo mensaje aprueba el plan, sus nombres **[Plan]** y los puntos P-G-1 a P-G-14. Si Manuel responde a un punto, su respuesta sustituye a la recomendación. **No hay código antes de esa aprobación** (regla 3.4).

> **Nota (2026-10-31): APROBADO por Manuel**, con P-G-1 a P-G-14 como se recomiendan y tres ampliaciones de D9 (#164, comentario 6544630429; texto literal en `decisiones-delegadas-2026-10.md`, nota del 2026-10-31). "PROPUESTO" se conserva; esta nota lo supera.
> - **`POST /account/google/link`** lleva `{idToken, currentPassword}`. Una contraseña incorrecta o ausente → **400 `CurrentPasswordIncorrect`**, como la desactivación (`DeactivateAccountService.java:88-89`; `IdentityApiErrorMappings.java:79`). Se comprueba **antes** del `idToken`, para que un token robado sin la contraseña no revele nada de Google.
> - **`DELETE /account/google/link`** con `{currentPassword}` → `204`; auditoría `GOOGLE_UNLINKED` **[Plan]**; aviso por correo. Sin vínculo → `204` sin cambios, sin auditoría ni aviso. Contraseña incorrecta → el mismo 400.
> - **Avisos (E2-4 ampliado):** `GOOGLE_LINKED` ("Vinculaste tu cuenta de Google a PaxFide") y `GOOGLE_UNLINKED` ("Desvinculaste tu cuenta de Google de PaxFide") **[Plan]**, como los demás: sin token, IP ni dispositivo, con la hora de Bogotá, y un fallo de envío no cambia la respuesta.
>
> **Tests nuevos** (se añaden a §3):
>
> | # | Test | Rojo esperado |
> |---|---|---|
> | G-14 | Vincular con `currentPassword` incorrecta o ausente → 400 `CurrentPasswordIncorrect`, el mismo cuerpo que la desactivación, sin vincular y sin verificar el `idToken` (el JWKS local no recibe ninguna petición) | 204 |
> | G-15 | Desvincular con la contraseña correcta → 204, `googleSubject` borrado y auditoría `GOOGLE_UNLINKED`; después, `POST /auth/google` con ese `idToken` → 401 | 404 |
> | G-16 | Desvincular con la contraseña incorrecta → 400 y el vínculo sigue; sin JWT → 401; sin vínculo → 204 sin auditoría ni aviso | 404 |
> | G-17 | Vincular y desvincular envían un aviso cada uno, con su asunto, sin el `sub`, el `idToken` ni `#token=` | sin aviso |
> | G-18 | Tras desvincular, el mismo Google se puede vincular a **otra** cuenta (el índice único ya no lo bloquea) | 409 |
>
> **Mutaciones nuevas** (se añaden a §4):
>
> | # | Mutación | Debe matarla |
> |---|---|---|
> | 13 | Vincular sin comprobar `currentPassword` | G-14 |
> | 14 | Desvincular sin comprobar `currentPassword` | G-16 |
> | 15 | Desvincular no borra `googleSubject` | G-15, G-18 |
> | 16 | Vincular no avisa | G-17 |
> | 17 | Desvincular no audita | G-15 |

> **Nota (2026-10-31): implementado** en #166. La evidencia está en `estado-fase6.md` §0.80. Dos nombres **[Plan]** nuevos, que se dicen en el PR:
> - `LoginProblems` (en `api`): las respuestas de error comunes a los dos logins;
> - `LoginAcceptances` (en `identity`): la regla de D3, común a los dos logins.
>
> Las excepciones de conflicto toman el nombre de su título de P-G-4 (`GoogleAccountAlreadyLinkedException` y `AccountAlreadyLinkedToGoogleException`) en lugar de los nombres de §2.

**Qué entra** lo fija ADR-050 D9 (`ADR-050-cuenta-y-sesiones.md:172-184`) y sus tests 27 a 29 (`:284-287`). G-0 retoma R-6 y G-1 y G-2 fijan la configuración (`decisiones-delegadas-2026-10.md`, nota del 2026-10-30). Este plan fija el **cómo**.

**Base verificada:** la rama de #163 (Enmienda 2 de ADR-050, `31c2a56`), con `develop` `6375c40` integrado. **Depende de #163**, porque la Enmienda 2 cambia el login (E2-3) y `AuthenticateAccountPort`. Cada ruta:línea de este plan es de esa base.

---

## 0. Hechos verificados

| Hecho | Ruta:línea |
|---|---|
| D9: sin vinculación por correo; `POST /account/google/link` con JWT y `{idToken}`; `POST /auth/google` busca por `sub`; sin cuenta o con cuenta `INACTIVE` → el mismo 401 | `ADR-050-cuenta-y-sesiones.md:174-176` |
| D9: Nimbus, JWKS cacheado; `iss` `accounts.google.com` o `https://accounts.google.com`; `aud` de la lista; `exp`; `email_verified = true`; `GOOGLE_OAUTH_CLIENT_IDS` por entorno y sin secreto | `ADR-050-cuenta-y-sesiones.md:177-182` |
| D9: "Hoy los correos no se verifican" | `ADR-050-cuenta-y-sesiones.md:174`. La Enmienda 2 (#163) ya los verifica; ver P-G-1 |
| Módulos de R-6: `identity`, `api`, `app` (sin `contracts`) | `ADR-050-cuenta-y-sesiones.md:300` |
| Nimbus está en `api` (ADR-047 D1), con la versión fijada en el padre | `api/pom.xml:35-36`; `pom.xml:18` |
| `api` solo depende de `contracts`; `app` depende de `api` y de `identity` | `api/pom.xml:23`; `app/pom.xml:32`, `:37` |
| El login (en `api`) usa `AuthenticateAccountPort` (`contracts`) y calcula el `iat` en el controlador (E1-2) | `api/.../auth/LoginController.java:76`, `:105-106` |
| El orden del login con la Enmienda 2: contraseña → estado → correo verificado → aceptaciones | `identity/.../AuthenticateAccountPortImpl.java:75`, `:79`, `:82` |
| La escritura de las aceptaciones del login está condicionada al hash validado y a `ACTIVE` | `identity/.../port/out/AccountRepositoryPort.java:70` |
| Los controladores de `app.web` no pueden depender de infraestructura | `app/src/test/java/com/traceability/app/web/AppWebArchitectureTest.java:46` |
| El emisor de tokens admite un `iat` explícito | `contracts/.../TokenIssuerPort.java:23` |
| Rutas públicas de autenticación | `api/.../auth/PublicRoutes.java:31-38` |
| El 503 tiene un detalle fijo | `api/.../web/ApiExceptionHandler.java:62` |
| No hay límite de intentos en el login (DH-56, riesgo aceptado) | `ADR-050-cuenta-y-sesiones.md:240`; `ADR-047-jwt-libreria-y-secreto-de-firma.md:91` |
| Los avisos de la Enmienda 2 son tres: cambio, recuperación y desactivación | `ADR-050-enmienda-2-verificacion-correo-y-avisos.md:104-106` |
| `GOOGLE_OAUTH_CLIENT_IDS` nunca va con valores reales en el repositorio | `plan-ejecucion-cierre-manuel-2026-10-24.md:287` |
| No hay nada de Google en la configuración ni en el código de `develop` | `grep -rn -i google app/src/main/resources/*.yml` sale vacío |

## 1. Común

- **Un PR de código**, `feat/identity-adr050-r6-google`. Si #163 no está fusionado, sale de su rama y dice "depende de #163".
- **Sin cambios en `contracts`** (P-G-11). D9 no lo nombra y no hace falta: el login con Google no pasa por `AuthenticateAccountPort`.
- **Ciclo:**
  - rojo primero, con la salida literal;
  - verde por capas con el módulo completo (`identity`, `api` y `app`);
  - mutaciones con `scripts/mutaciones.py` en un clon limpio, con la mutación 0;
  - `free -g` ≥ 4 GB y `scripts/ci-local.sh` en un clon limpio del commit exacto, con la salida en `Documentos/evidencia-ci/`.
- **Los tests nunca llaman a Google:**
  - llaves RSA generadas en el test;
  - `idToken` firmados con Nimbus;
  - un `JWKSource` en memoria (`ImmutableJWKSet`) o un servidor JWKS local (`com.sun.net.httpserver`, de la JDK) para la caché y el tiempo de espera.
- **Ningún ID de cliente real** en el repositorio (G-1): los tests usan `client-web-test` y `client-android-test`.

## 2. Archivos por módulo

### `identity`

| Archivo | Cambio | D9 |
|---|---|---|
| `domain/model/Account.java` | `googleSubject` **[Plan]** (nulo si no hay vínculo). `linkGoogle(String subject)` **[Plan]**: si ya está vinculada a **otro** `sub` → `GoogleAlreadyLinkedToAccountException` **[Plan]**; el mismo `sub` → sin cambios | Vinculación |
| `documents/AccountDocument.java` y `mappers/AccountMapper.java` | `googleSubject`, con un índice único **parcial** `uq_google_subject` **[Plan]** (solo si existe) | "índice único" |
| `port/out/AccountRepositoryPort.java` y `MongoAccountRepositoryAdapter` | `Optional<Account> findByGoogleSubject(String)` **[Plan]** y `boolean linkGoogleIfUnlinked(AccountId, String subject)` **[Plan]** (`$set` condicionado a `ACTIVE` y sin `googleSubject`). Un `DuplicateKeyException` de `uq_google_subject` → `GoogleSubjectAlreadyLinkedException` **[Plan]** | Vinculación |
| `port/out/GoogleIdTokenVerifierPort.java` **[Plan]** | `VerifiedGoogleIdentity verify(String idToken)` **[Plan]**, con `{subject, email}`, ya comprobados firma, `iss`, `aud`, `exp` y `email_verified`. Lanza `GoogleIdTokenInvalidException` **[Plan]** o `GoogleSignInUnavailableException` **[Plan]** si no se pueden obtener las llaves (P-G-9) | Verificación |
| `application/service/GoogleSignInService.java` **[Plan]** | `authenticate(String idToken, AcceptedLegalVersions accepted)`: verificar → buscar por `sub` (**nunca por correo**) → `ACTIVE` → correo verificado (E2-3, P-G-1) → aceptaciones (D3, P-G-2) → `AuthenticatedAccount`. Sin cuenta o `INACTIVE` → `AuthenticationFailedException`, el mismo 401. `link(AuthorizationPrincipal, String idToken)`: vincula la cuenta del principal (P-G-4, P-G-5) | Las dos rutas |
| `domain/model/AuditAction.java` | `GOOGLE_LINKED` **[Plan]**, con el actor del principal y sin datos (ni el `sub` ni el correo) | P-G-5 |
| `domain/exception/` | Las excepciones **[Plan]** de arriba | — |

### `api`

| Archivo | Cambio | D9 |
|---|---|---|
| `auth/LoginIssuedAt.java` **[Plan]** | La regla de E1-2, `max(segundo actual truncado, credentialsNotBefore)`, sacada de `LoginController.java:105-108` a un método estático público, **sin cambiarla**. La usan los dos logins (P-G-3) | E1-2 |
| `auth/LoginController.java` | Usa `LoginIssuedAt`; el resto no cambia | E1-2 |
| `auth/PublicRoutes.java` y `PublicRoutesTest` | `POST /api/v1/auth/google`, pública. `POST /account/google/link` **no** es pública: necesita JWT | "Rutas públicas" |

### `app`

| Archivo | Cambio | D9 |
|---|---|---|
| `infrastructure/google/NimbusGoogleIdTokenVerifier.java` **[Plan]** | Implementa `GoogleIdTokenVerifierPort` con Nimbus (`DefaultJWTProcessor` y `JWKSourceBuilder`):<ul><li>JWKS de `traceability.google.jwks-uri` **[Plan]** (por defecto `https://www.googleapis.com/oauth2/v3/certs`), con caché, tiempo de espera y reintento (P-G-9);</li><li>`RS256`;</li><li>`iss` de los dos valores;</li><li>`aud` dentro de `traceability.google.client-ids` **[Plan]** (`${GOOGLE_OAUTH_CLIENT_IDS:}`, separados por comas);</li><li>`exp` con 60 s de tolerancia, como Nimbus por defecto (P-G-10);</li><li>`email_verified` booleano `true`.</li></ul>Nunca registra el `idToken`, el `sub` ni el correo | Verificación |
| `config/GoogleSignInConfig.java` **[Plan]** | Crea el verificador. Con la lista vacía, la aplicación **arranca** y todo `idToken` es inválido (P-G-8) | Configuración |
| `web/auth/GoogleSignInController.java` **[Plan]** | `POST /api/v1/auth/google {idToken, acceptedTermsVersion?, acceptedDataPolicyVersion?}` → `200 {token}`, con el `iat` de `LoginIssuedAt`. Los errores son los de `LoginController` y el 503 de P-G-9 | Login |
| `web/account/GoogleLinkController.java` **[Plan]** | `POST /api/v1/account/google/link {idToken}` con JWT → `204` (P-G-4) | Vinculación |
| `web/IdentityApiErrorMappings.java` | Los errores de la vinculación (P-G-4) y el 503 | — |
| `src/main/resources/application.yml` | `traceability.google.client-ids: ${GOOGLE_OAUTH_CLIENT_IDS:}` y `jwks-uri` con el valor público de Google. Sin ID reales ni secreto | G-1 |
| `infrastructure/mail/SmtpSecurityNoticeMailAdapter.java` | Solo si P-G-6 se aprueba: el aviso `GOOGLE_LINKED` | P-G-6 |

## 3. Tests, con su rojo esperado

| # | Test | Módulo | Rojo esperado |
|---|---|---|---|
| 27 | `NimbusGoogleIdTokenVerifierTest` **[Plan]**: firma de otra llave, `alg` `none` o `HS256`, `iss` ajeno, `aud` fuera de la lista, caducado, sin `email_verified` o con `false`, y `email_verified` como texto `"true"` → inválido. Uno bueno → `{sub, email}` | `app` | Compilación |
| 27 (HTTP) | `GoogleSignInHttpIntegrationTest` **[Plan]**: los mismos casos por `POST /auth/google` → **el mismo 401, byte a byte, que una contraseña errónea** | `app` | 404 (la ruta no existe) |
| 28 | El mismo: un `idToken` válido cuyo correo coincide con una cuenta **no vinculada** → 401, y la cuenta sigue sin `googleSubject` (negativa sobre la base) | `app` | 404, y después 200 si se buscara por correo |
| 29 | El mismo: vincular con sesión (`204`) y después `POST /auth/google` → `200 {token}` de esa cuenta, con el `iat` de E1-2 | `app` | 404 |
| G-1 | El mismo: una cuenta vinculada pero `INACTIVE` → el mismo 401 | `app` | — |
| G-2 | El mismo: una cuenta vinculada sin correo verificado → 403 `EmailNotVerified` (P-G-1) | `app` | 200 |
| G-3 | El mismo: sin las aceptaciones vigentes → 403 `AcceptanceRequired`; repetida con las versiones en el cuerpo → 200, y se guardan (P-G-2) | `app` | 200 |
| G-4 | El mismo: vincular un `sub` que ya tiene otra cuenta → 409 `GoogleAccountAlreadyLinked`; vincular otro `sub` a una cuenta ya vinculada → 409 `AccountAlreadyLinkedToGoogle`; repetir la misma vinculación → 204 sin auditoría nueva (P-G-4) | `app` | 404 |
| G-5 | El mismo: vincular sin JWT → 401; con un `idToken` inválido → 400 `GoogleIdTokenInvalid`; sin `idToken` → 400 | `app` | 404 |
| G-6 | El mismo: el JWKS local no responde (servidor que no contesta) → 503 `GoogleSignInUnavailable` en menos del tiempo de espera, y el login con contraseña sigue en 200 (P-G-9) | `app` | 404 |
| G-7 | El mismo: dos logins seguidos piden el JWKS **una sola vez** (contador del servidor local) | `app` | 2 peticiones |
| G-8 | El mismo, con `client-ids` vacío: la aplicación arranca y `POST /auth/google` → 401 (P-G-8) | `app` | Falla al arrancar |
| G-9 | `GoogleSignInServiceTest` **[Plan]** (unitario): nunca llama a `findByEmail` (negativa con un mock estricto) | `identity` | Compilación |
| G-10 | El `idToken`, el `sub` y el correo no aparecen en los logs (`CapturedOutput`) ni en las respuestas | `app` | — |
| G-11 | `PublicRoutesTest` y `PublicRoutesInventoryIntegrationTest`: `/auth/google` pública; `/account/google/link`, protegida | `api`, `app` | Falta la ruta |
| G-12 | `LoginControllerTest`: los tests de E1-2 (L-1 a L-4) siguen en verde con `LoginIssuedAt` | `api` | — |
| G-13 | `MongoIndexesAtStartupIntegrationTest`: el índice `uq_google_subject` es único y parcial | `app` | Falta el índice |
| 30 | `GoldenPathHttpIntegrationTest` en verde y `ci-local.sh` completo | todos | — |

## 4. Mutaciones

| # | Mutación | Debe matarla |
|---|---|---|
| 1 | Sin comprobar la firma | 27 |
| 2 | Aceptar cualquier `iss` | 27 |
| 3 | Aceptar cualquier `aud` | 27 |
| 4 | No exigir `email_verified` | 27 |
| 5 | Buscar por correo si no hay vínculo | 28, G-9 |
| 6 | No comprobar `INACTIVE` | G-1 |
| 7 | Sin la comprobación de E2-3 | G-2 |
| 8 | Sin las aceptaciones | G-3 |
| 9 | Emitir con el reloj sin la regla de E1-2 | 29 |
| 10 | Vincular aunque ya haya otro `sub` | G-4 |
| 11 | Sin caché del JWKS | G-7 |
| 12 | 401 en lugar de 503 cuando Google no responde | G-6 |

## 5. Puntos a decidir (P-G-n), con recomendación

| # | Punto | Recomendación |
|---|---|---|
| **P-G-1** | **Enmienda 2 y D9.** D9 dice "Hoy los correos no se verifican" (`ADR-050:174`); con #163 ya se verifican. ¿Qué pasa con `POST /auth/google` si la cuenta vinculada tiene el correo sin verificar? ¿Y si el correo de Google (`email_verified`) es otro? | **(1)** La misma regla que el login (E2-3): cuenta vinculada sin verificar → 403 `EmailNotVerified`, en el mismo orden (después del estado y antes de las aceptaciones). **(2)** Al **vincular** con sesión: si el correo de Google, con `email_verified = true`, es **el mismo** que el de la cuenta (normalizado), la cuenta queda verificada y se audita `EMAIL_VERIFIED` con `{via: "GOOGLE_LINK"}`, como la recuperación (P-E2V-8). Si es **otro**, se vincula igual (D9 vincula por `sub`), pero la cuenta sigue sin verificar. **(3)** "Nunca vincular por coincidencia de correo" se mantiene: el correo solo sirve para verificar, nunca para elegir la cuenta. **Se anota sin resolver:** la frase de `ADR-050:174` queda superada por la Enmienda 2; la razón de la regla (Google no elige la cuenta) sigue valiendo |
| **P-G-2** | **Aceptaciones (D3, #144).** ¿`POST /auth/google` responde 403 `AcceptanceRequired`? ¿Cómo repite el cliente? | **Sí**, igual que el login. El cuerpo gana, de forma aditiva, `acceptedTermsVersion?` y `acceptedDataPolicyVersion?`, como `LoginRequest` (`LoginController.java`), y el cliente repite con el **mismo** `idToken` si no ha caducado. La escritura reutiliza `recordLegalAcceptancesIfUnchanged` (`AccountRepositoryPort.java:70`) con el hash **leído** de la misma cuenta: protege igual contra un cambio de contraseña o una desactivación concurrentes. Sin método nuevo |
| **P-G-3** | **`iat` (Enmienda 1, E1-2).** | La misma regla y el mismo código: se mueve a `LoginIssuedAt` **[Plan]** en `api` sin cambiarla. Los tests L-1 a L-4 vigilan que no cambie. E1-2 dice que el cálculo está "en `LoginController`"; pasa a un ayudante del mismo paquete que usan los dos controladores, y **se anota** como desviación de forma, no de regla |
| **P-G-4** | **Vincular un `sub` ya vinculado a otra cuenta; vincular un segundo Google a una cuenta ya vinculada.** | **409 `GoogleAccountAlreadyLinked`** **[Plan]** y **409 `AccountAlreadyLinkedToGoogle`** **[Plan]**. El primero revela que esa cuenta de Google ya está en uso, pero quien lo pide tiene su `idToken`, así que no revela nada a un tercero. La misma vinculación repetida → `204` sin cambios. `idToken` inválido al vincular → **400 `GoogleIdTokenInvalid`** **[Plan]**, no 401: el 401 de esa ruta significa "sin sesión" |
| **P-G-5** | **¿Aviso por correo y auditoría al vincular? ¿Desvincular?** | **Auditoría:** sí, `GOOGLE_LINKED` **[Plan]**, sin datos. **Aviso:** ver P-G-6. **Desvincular: no está en D9, así que no se implementa;** se propone `DELETE /api/v1/account/google/link` como deuda registrada. Mientras no exista, la plataforma no tiene forma de quitar un vínculo |
| **P-G-6** | **¿El aviso de seguridad (Enmienda 2, E2-4) incluye vincular Google?** | E2-4 solo lista tres casos (`ADR-050-enmienda-2…:104-106`). Vincular cambia la forma de entrar, así que **recomiendo avisar** ("Vinculaste tu cuenta de Google a PaxFide"). Pero amplía E2-4: **lo decide Manuel**. Si es no, no se avisa |
| **P-G-7** | **Límite de intentos.** | No hay límite en ningún login (DH-56, riesgo aceptado en `ADR-050:240`). `/auth/google` tampoco lo tiene; se anota que el riesgo aplica igual. Fuerza bruta contra un `idToken` no tiene sentido (firma RSA) |
| **P-G-8** | **`GOOGLE_OAUTH_CLIENT_IDS` vacía.** | **La aplicación arranca**, `/auth/google` da el 401 uniforme y `/account/google/link` da 400 `GoogleIdTokenInvalid`. Un `INFO` al arrancar dice que Google está desactivado, sin valores |
| **P-G-9** | **JWKS: caché, tiempo de espera y fallo de Google.** | Con `JWKSourceBuilder` de Nimbus:<ul><li>caché de 5 min de vida y 15 s de refresco;</li><li>conexión y lectura de 2 s;</li><li>un reintento;</li><li>las llaves se piden la primera vez que hacen falta, no al arrancar.</li></ul>Si no se pueden obtener → **503 `GoogleSignInUnavailable`** **[Plan]**, no 401: el cliente sabe que puede entrar con contraseña. El login con contraseña no se toca (D9) |
| **P-G-10** | **Tolerancia de reloj en `exp`.** | La de Nimbus por defecto, 60 s. Sin `nbf` propio |
| **P-G-11** | **`contracts`.** | **No se toca.** El login con Google no pasa por `AuthenticateAccountPort`; lo hace `GoogleSignInService` en `identity`, y el controlador de `app` emite con `TokenIssuerPort` (`:23`), que ya existe |
| **P-G-12** | **Dónde vive la verificación.** | En `app` (`infrastructure/google`), detrás de un puerto de `identity`, porque `identity` no tiene Nimbus y los controladores de `app.web` no pueden depender de infraestructura (`AppWebArchitectureTest.java:46`) |
| **P-G-13** | **G-2: el `aud` del móvil.** | El backend acepta cualquier `aud` de la lista, sin distinguir el origen. Si el móvil manda un `aud` Android (sin `serverClientId`), también vale mientras ese ID esté en la lista. **Se anota que G-2 está sin verificar** en el código del móvil |
| **P-G-14** | **`estado-fase6.md`.** | §0.80 |

## 6. Aviso a la web y al móvil (contrato)

- **`POST /api/v1/auth/google`**, pública.
  - **Cuerpo:** `{idToken, acceptedTermsVersion?, acceptedDataPolicyVersion?}`.
  - **`200 {token}`:** el mismo token que el login.
  - **Errores:**
    - **401**, el mismo cuerpo que una contraseña errónea: `idToken` inválido o caducado, `aud` no configurado, cuenta no vinculada o `INACTIVE`, o Google desactivado;
    - **403 `EmailNotVerified`**;
    - **403 `AcceptanceRequired`** `{pendingTermsVersion?, pendingDataPolicyVersion?}`: repetir con el mismo `idToken` y las versiones;
    - **503 `GoogleSignInUnavailable`:** ofrecer la contraseña.
- **`POST /api/v1/account/google/link`**, con JWT.
  - **Cuerpo:** `{idToken}`.
  - **`204`.**
  - **Errores:**
    - **400 `GoogleIdTokenInvalid`** o campo vacío;
    - **401** sin sesión;
    - **409 `GoogleAccountAlreadyLinked`** (ese Google ya está en otra cuenta);
    - **409 `AccountAlreadyLinkedToGoogle`** (esta cuenta ya tiene otro);
    - **503 `GoogleSignInUnavailable`**.
  - Si el correo de Google coincide con el de la cuenta, la cuenta queda verificada (P-G-1).
- **No hay registro con Google** (Q2 (a) de ADR-050): primero se crea la cuenta con contraseña, se entra y se vincula.
- **El móvil** usa `google_sign_in` con `serverClientId` = ID web (G-2). **La web** usa Google Identity Services y manda el `credential` como `idToken`.
- **Despliegue:** `GOOGLE_OAUTH_CLIENT_IDS` con los ID de web, Android y Android demo, separados por comas. Nunca el secreto.
