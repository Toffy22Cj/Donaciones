# PROGRESO — Sesión A (backend de Manuel, línea de identity)

Rama de memoria: `wip/progreso-sesion-a`. **No se fusiona ni se borra.** Se actualiza con commit y push después de
cada paso importante. La sesión B (bloques C y D de R-6, ADR-051 T4) usa sus propias secciones (estado-fase6 0.50–0.69);
la A usa 0.39–0.49.

## Estado al arrancar (2026-10-25)

- `develop` = `406f9b2`.
- #137 (P-R3-3, `fix/identity-p-r3-3-iat-del-login` `422a1cb`): abierto, listo, espera a que Manuel lo fusione.
- Ramas wip subidas sin probar (base `406f9b2`):
  - `wip/identity-r4-recuperacion` `a201788`
  - `wip/identity-r5-d1` `07a32e6`
  - `wip/identity-d6-desactivacion` `0ff5f67`

## Cola

| Ítem | Estado | Rama | PR | Evidencia | Bloqueado por |
|---|---|---|---|---|---|
| A1 R-4 | **HECHO** (PR listo) | `feat/identity-r4-recuperacion-contrasena` `84c1ad8` (código en `eb7c6a6`; develop `aa7a0d2` ya integrado) | #140 (#137 ya fusionado) | ci-local `eb7c6a6`: core 340, crypto 50, ai 47, api 160, identity 352, convocatoria 253, app 478, BUILD SUCCESS | — |
| A2 R-5 (D1, D2, D10) | **HECHO** (PR listo) | `feat/identity-r5-aceptaciones-perfil` `bfe126f` (código en `3002c6e`) | #142 | ci-local `3002c6e`: core 340, crypto 50, ai 47, api 156, identity 344, convocatoria 253, app 471, BUILD SUCCESS | — |
| A3 D3 | **HECHO** (PR listo) | `feat/identity-d3-aceptaciones-login` `a477610` (código en `cc5239e`) | #144 (depende de #137 y #142) | ci-local `d0b3d7c`: core 340, crypto 50, ai 47, api 162, identity 359, convocatoria 253, app 478, BUILD SUCCESS | fusionar #137 y #142 antes |
| A4 D6 sin D7 | **HECHO** (PR listo) | `feat/identity-d6-desactivacion` `ff9b9d7` (código en `8bc0b09`) | #146 | ci-local `98f366f`: core 340, crypto 50, ai 47, api 156, identity 339, convocatoria 253, app 470, BUILD SUCCESS | — |
| A5 CORS (DD-57) | **HECHO** (PR listo, ya no es borrador) | `fix/cors-put-delete` `517502c` (código en `93a6adc`) | #138 | ci-local `93a6adc`: core 340, crypto 50, ai 47, api 157, identity 332, convocatoria 253, app 464, BUILD SUCCESS | — |

## RELEVO A LA SESIÓN B (2026-10-25, Manuel: "La sesión B toma el relevo de todo el tren de fusiones, incluidos tus PR")

La sesión A **se detiene aquí**: no hace más push a ninguna rama (este archivo es la única excepción, a petición de Manuel). No hay tareas programadas (`list_triggers` vacío) ni procesos en marcha. Fusiona solo Manuel.

### Estado de cada PR de la sesión A (comprobado con git y gh el 2026-10-25)

| PR | Rama | Cabeza | Base integrada | Última CI (`ci-local.sh`, clon limpio) | ¿Código nuevo desde esa CI? |
|---|---|---|---|---|---|
| #140 R-4 | `feat/identity-r4-recuperacion-contrasena` | `84c1ad8` | develop `aa7a0d2` (#137 y #139) | `eb7c6a6`, verde: `Documentos/evidencia-ci/ci-local-eb7c6a6-2026-10-25T07-23-51Z.txt` | No: fuera de `Documentos`, el árbol es idéntico al de `eb7c6a6` |
| #142 R-5 (D1, D2, D10) | `feat/identity-r5-aceptaciones-perfil` | `bfe126f` | develop `406f9b2` (**sin** #137 ni #139) | `3002c6e`, verde: `ci-local-3002c6e-2026-10-25T08-19-21Z.txt` | No en la rama; sí en develop (#137, y lo que entre después) |
| #144 D3 | `feat/identity-d3-aceptaciones-login` | `a477610` | rama de #142 + `fix/identity-p-r3-3-iat-del-login` (merge `89a1e26`) | `d0b3d7c`, verde: `ci-local-d0b3d7c-2026-10-25T09-15-59Z.txt` | No |
| #146 D6 | `feat/identity-d6-desactivacion` | `ff9b9d7` | develop `406f9b2` | `98f366f`, verde: `ci-local-98f366f-2026-10-25T10-08-45Z.txt` | No en la rama; sí en develop |
| #138 CORS | `fix/cors-put-delete` | `517502c` | develop `406f9b2` | `93a6adc`, verde: `ci-local-93a6adc-2026-10-25T10-49-50Z.txt` | No en la rama. **Manuel: no se fusiona hasta que Carlos confirme; no hacer nada más con él** |

Dependencias: #140 ya no depende de nada (#137 fusionado). #144 depende de #142 (contiene sus commits). #142, #146 y #138 son independientes.

### Lo imprescindible: R-4 y D3 juntos

Con D3 (#144), **una cuenta sin las aceptaciones vigentes recibe `403 AcceptanceRequired` al iniciar sesión** si el cuerpo no trae `acceptedTermsVersion` y `acceptedDataPolicyVersion`. Los tests crean cuentas con `createAccount(email, password)`, que no acepta nada. Por eso, **cuando R-4 (#140) y D3 (#144) coincidan en una rama** (el merge de develop en #144 después de fusionar #140, o al revés), hay que cambiar en `app/src/test/java/com/traceability/app/web/account/PasswordResetHttpIntegrationTest.java` los dos helpers que inician sesión (líneas 193 y 200 en `84c1ad8`):

- `login(email, password)` y `loginStatus(email, password)`: añadir al cuerpo `,"acceptedTermsVersion":"v1-2026-10","acceptedDataPolicyVersion":"v1-2026-10"`, como se hizo en #144 con `AccountPasswordHttpIntegrationTest` (comentario "ADR-050 D3: las cuentas del test no aceptaron nada al crearse; el login acepta las vigentes").
- Los usa: `confirmingAnUnknownExpiredUsed…` (348), `confirming_revokesThePreviousJwt_andTheNewPasswordWorks` (379, 388, 389), `r4_19_confirmAndLogInInTheSameSecond_theTokenWorks` (402) y `theToken_neverAppears…` (429). Con las versiones en el cuerpo, los 401 esperados siguen siendo 401 (la contraseña va antes que las aceptaciones) y los 200 vuelven a ser 200.
- Repasar también **cualquier login nuevo** que hayan traído los PR de la B: `git grep -n "/api/v1/auth/login" -- app/src/test` tras el merge. Ya mandan las versiones en #144: `AuthenticationEndToEndIntegrationTest`, `PathEvasionRealServerIntegrationTest`, `AccountPasswordHttpIntegrationTest`, `DemoSeedRunnerIntegrationTest` y `P2EndpointsHttpIntegrationTest`. `DemoScenarioSeeder` y `scripts/demo/recorrido.py` también.
- Después: `ci-local.sh` completo en un clon limpio. App debe dar 478 (D3) + 14 (R-4) + lo que traiga develop.

### Conflictos que se esperan al integrar develop (todos de "añadir", conservar los dos lados)

- `app/src/test/java/com/traceability/app/auth/PublicRoutesInventoryIntegrationTest.java`: R-4 añade las dos rutas públicas de recuperación; R-5, las dos del perfil (protegidas); D6, `POST /api/v1/account/deactivate` (protegida).
- `app/src/main/java/com/traceability/app/web/IdentityApiErrorMappings.java`: R-4 añade `PasswordResetTokenInvalidException` → 400; D6 añade `PlatformAdministratorDeactivationException` → 409 al final de la lista (cuidado con la coma y el `);` final).
- `identity/src/test/java/identity/application/service/AccountApplicationServiceIntegrationTest.java`, en `@Import`: R-4 añade `MongoPasswordResetTokenRepositoryAdapter`; D6 añade `StubCampaignResponsibilityPort`.
- `Documentos/referencia-api-v1.md`: las notas de R-4, R-5, D3 y D6 van todas justo después de la viñeta "Riesgo registrado (R-R3-1…)" de §1; conservar todas. La de CORS va en §0.
- `Documentos/estado-fase6.md`: secciones de la A, de la 0.40 a la 0.44 (0.40 R-4, 0.41 R-5, 0.42 D3, 0.43 D6, 0.44 CORS), encima de la 0.39. Orden descendente; la B usa de la 0.50 a la 0.69.
- `api/src/test/java/com/traceability/api/auth/PublicRoutesTest.java` (R-4) y `AuthenticateAccountPortImplTest` / `LoginControllerTest` (D3 sobre P-R3-3): ya integrados en sus ramas.

### Notas y aprobaciones ya registradas

- #144: comentario con la aprobación de Manuel del nombre `recordLegalAcceptancesIfUnchanged` y de guardar solo la versión pendiente.
- #142: comentario con la aprobación de una sola clase de test HTTP y de la mutación 4 sobre `GET /account`.
- Plan R-4: nota fechada (`84c1ad8`): la mutación 6 la mata el test del adaptador, no el 15.
- Plan CORS y DD-57: notas fechadas de la aprobación de Manuel (`2342b97`), con la salvedad de que `decisiones-delegadas-2026-10.md:179` no nombra CORS.

### Pendiente tras las fusiones (no hecho por la A)

- Notas "implementada en #N" en la Enmienda 1 de ADR-050 (partes 1, 2 y 3) y en los planes, cuando se fusionen #137 (ya está), #140 y #144.
- Preguntas para Carlos: D7 (`plan-d6-desactivacion-propia.md:42`), R-R3-1 (`:138`) y la confirmación de CORS (#138).
- Bloqueados: D7, Google, T3 y T5.
- Las ramas `wip/identity-r4-recuperacion`, `wip/identity-r5-d1`, `wip/identity-d6-desactivacion` y esta `wip/progreso-sesion-a` **no se borran**.

### Cómo se hizo la evidencia (por si la B la repite)

- Docker con `/etc/docker/daemon.json` `{"registry-mirrors":["https://mirror.gcr.io"]}` (Docker Hub da 429); nunca en un commit.
- Clones limpios con `git clone --branch <rama>` y repositorio Maven aislado: `MAVEN_ARGS=-Dmaven.repo.local=<copia de ~/.m2 sin com/traceability>`. `free -g` ≥ 4 antes de cada `ci-local.sh`.
- Mutaciones: `scripts/mutaciones.py` con las especificaciones de `mutaciones/<ítem>/` de esta rama, tras `mvn install` (con tests) de las dependencias y la línea base en verde.
- Nota a mano al final de cada evidencia: el espejo de Docker, el repositorio aislado y el `free -g`.

## Bitácora

- 2026-10-25: arranque de la sesión A; memoria creada desde `develop` `406f9b2`.

- #137: pie del PR corregido a "🤖 Generated with [Claude Code](https://claude.com/claude-code)".
- A1 rojo (worktree `/home/user/a1-rojo`, producción de R-4 apartada):
  - identity: BUILD FAILURE en testCompile, "cannot find symbol" de PasswordResetToken, PasswordResetTokenStatus, PasswordResetMailPort, PasswordResetMail, PasswordResetTokenRepositoryPort, PasswordResetTokenDocument, MongoPasswordResetTokenRepositoryAdapter, PasswordResetTokenInvalidException y RequestPasswordResetService.
  - api: "Tests run: 3, Failures: 1" — PublicRoutesTest.theListIsExactlyTheOneApprovedInThePlan (faltan las dos rutas).
  - app (tests de identity y api apartados): compilación, "cannot find symbol: class PasswordResetMailExecutorConfig"; con PasswordResetHttpIntegrationTest apartado: "Tests run: 2, Failures: 1" — PublicRoutesInventoryIntegrationTest, "could not find the following element(s): [/api/v1/auth/password-reset, /api/v1/auth/password-reset/confirm]".
- A1 verde: identity "Tests run: 351, Failures: 0, Errors: 0, Skipped: 0"; api "Tests run: 160, Failures: 0, Errors: 0, Skipped: 0".
- A1 hallazgo de la revisión: el bean `passwordResetMailExecutor` (ThreadPoolTaskExecutor) desactiva el `applicationTaskExecutor` de Boot 3.4.4 (`@ConditionalOnMissingBean(Executor.class)`), contra el plan R-4 §4 app ("no sustituye al de Spring"). Arreglo: `@Bean(defaultCandidate = false)` (OnBeanCondition 3.4.4 usa isDefaultCandidate) y un test que lo compruebe.
- A5: #138 en borrador con el plan `Documentos/plan-cors-put-delete.md`.

- CORS: Manuel aprobó el plan (#138) el 2026-10-25; notas fechadas en el plan y sobre DD-57 (`2342b97`). La nota de Carlos citada (decisiones-delegadas:179) no nombra CORS expresamente: registrado. Código: ciclo completo después de D6; el PR pasa a listo solo con la evidencia.
- R-4: `3071605` (defaultCandidate + documentos); R-5: rama `feat/identity-r5-aceptaciones-perfil` `42e6e37` (cherry-pick + tests 22 y 23 ampliados, sin Maven); D6: `feat/identity-d6-desactivacion` `8bc0b09` (sin Maven).

- A1: primera ejecución de `mvn test -pl app -am` sobre `9726be9`: core 340, crypto 50, ai 47, api 160, identity 351 y convocatoria 253 en verde; app "Tests run: 477, Failures: 1" — `twoSimultaneousConfirmations_oneIs204_andTheOther400`: "Expecting actual: [500, 204] to contain exactly in any order: [204, 400]" (IdentityConcurrentModificationException, WriteConflict agotado). Arreglo `eb7c6a6`: BCrypt fuera de la transacción de la confirmación, más el test `markUsedIfPending_onlyMovesAPendingToken_once`.
- A1: rojo del test del ejecutor (sin defaultCandidate): `theOwnMailExecutor_doesNotReplaceSpringsApplicationTaskExecutor` — "Expecting value to be true but was false" (Tests run: 14, Failures: 1); en esa ejecución el test de concurrencia pasó.
- A1: segunda ejecución `mvn test -pl app -am` sobre `eb7c6a6`: core 340, crypto 50, ai 47, api 160, identity 352, convocatoria 253, app 478, todos "Failures: 0, Errors: 0, Skipped: 0", BUILD SUCCESS.
- A1: mutaciones en el clon limpio `/home/user/mut-r4` (`eb7c6a6`, repo Maven aislado; install con tests de .,contracts,core,crypto,ai,api,convocatoria; base identity 18 y app 14 en verde). identity: 9 de 9 muertas. app: 11 de 13 muertas; VIVAS en app la 6 (la mata en identity `markUsedIfPending_onlyMovesAPendingToken_once`; el WriteConflict de Mongo resuelve la carrera, así que el test 15 no la mata en ninguna capa) y la 11 (la mata en identity `afterATransientRetry_…`, que es donde la pone el plan). Resultado literal: `mutaciones/r4/resultado-eb7c6a6.txt`.
- A1: ci-local en el clon limpio `/home/user/ci-r4` (`eb7c6a6`), en curso; free -g: 15 disponibles.

- A1: ci-local en el clon limpio `eb7c6a6`: BUILD SUCCESS (evidencia `Documentos/evidencia-ci/ci-local-eb7c6a6-2026-10-25T07-23-51Z.txt`, con nota a mano). Commit de evidencia + estado-fase6 §0.40: `85dc190`. PR #140 abierto, pie corregido. Cuando #137 se fusione: merge de develop en la rama de #140 y repetir ci-local.

- A2 rojo (producción de R-5 apartada, sobre develop): identity BUILD FAILURE en testCompile, "cannot find symbol" de AccountProfileService, InvalidProfileFieldException, LegalAcceptance, LegalVersionNotCurrentException, LegalVersions y OrganizationTermsAcceptance; app (tests de identity apartados) "cannot find symbol" de LegalVersions y LegalVersionsConfig.
- A2 verde sobre `42e6e37` (`mvn test -pl … -am`): identity "Tests run: 344", app "Tests run: 471" (api 156, igual que develop), todos "Failures: 0, Errors: 0, Skipped: 0".
- A2: `52aa433` nota en referencia-api. Mutaciones 1 a 7 (`mutaciones/r5/`); la 4 adaptada a GET /account (MeController está en api y no ve el nombre).

- A2: mutaciones identity 5/5 (52aa433) y app 7/7 (3002c6e) muertas; ci-local 3002c6e verde (`ci-local-3002c6e-2026-10-25T08-19-21Z.txt`); §0.41; PR #142, pie corregido.
- A3: D3 escrito (contracts AcceptedLegalVersions/AcceptanceRequiredException; puerto con 3 parámetros; AccountRepositoryPort.recordLegalAcceptancesIfUnchanged [nombre propuesto]; LoginController 403; tests LoginAcceptanceIntegrationTest y LoginAcceptanceHttpIntegrationTest; logins de los tests con versiones; recorrido.py).

- A3: rojo (api/identity compilación; app "Tests run: 5, Failures: 3", expected 403 but was 200); verde identity 359, api 162, app 478; mutaciones identity 6/6, api 1/1, app 4/7 (vivas 2, 5, 6: concurrencia, mueren en identity test 6); ci-local d0b3d7c verde; §0.42; PR #144 con pie corregido.

- A4 rojo: identity "cannot find symbol: method deactivateOwnAccount"; app (identity apartado) "Tests run: 8, Failures: 7" (expected 409/400/204 but was 404). Verde (98f366f): identity 339, app 470.
- A5 rojo (758a8a8, solo tests): api `CorsConfigTest` "Expecting actual: [GET, POST, OPTIONS] to contain exactly ... [GET, POST, PUT, DELETE, OPTIONS]"; app `CorsHttpIntegrationTest` "Tests run: 7, Failures: 1", preflight PUT "expected: 200 but was: 403". Código en 93a6adc.

- A4: mutaciones identity 5/5, app 6/7 (las 6 del plan muertas; la 7 extra viva en app, muerta en identity); ci-local 98f366f verde; §0.43; PR #146 con pie corregido.

- A5: mutaciones api 4/4 y app 4/4; ci-local 93a6adc verde; §0.44; #138 reescrito (título, cuerpo, pie) y pasado a listo.
- Pasada final (2026-10-25): las cabezas remotas y los cinco archivos de evidencia (salida 0, BUILD SUCCESS) comprobados con git; develop sigue en `406f9b2`.

- 2026-10-25, modo tren (Manuel): fusionados #137 y #139 (develop `aa7a0d2`). Respuestas de Manuel: D3 nombre y "solo la pendiente" aprobados (nota en #144); R-5 una clase y mutación 4 en /account aprobadas (nota en #142); R-4 nota fechada en el plan (`84c1ad8`); CORS #138 no se toca hasta que Carlos confirme.
- #140: merge de develop `aa7a0d2` (`4531319`, sin conflictos) + nota del plan (`84c1ad8`). Fuera de Documentos, el árbol es idéntico a `eb7c6a6` (CI ya en verde con #137 dentro): no se repite la CI. Avisado "#140 listo".

## Siguiente paso exacto

Ninguno para la sesión A: **detenida** (Manuel, 2026-10-25). Ver "RELEVO A LA SESIÓN B" arriba.
