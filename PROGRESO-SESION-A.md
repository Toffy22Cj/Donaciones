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
| A1 R-4 | **HECHO** (PR listo) | `feat/identity-r4-recuperacion-contrasena` `85dc190` (código en `eb7c6a6`) | #140 (depende de #137) | ci-local `eb7c6a6`: core 340, crypto 50, ai 47, api 160, identity 352, convocatoria 253, app 478, BUILD SUCCESS | fusionar #137 antes |
| A2 R-5 (D1, D2, D10) | **HECHO** (PR listo) | `feat/identity-r5-aceptaciones-perfil` `bfe126f` (código en `3002c6e`) | #142 | ci-local `3002c6e`: core 340, crypto 50, ai 47, api 156, identity 344, convocatoria 253, app 471, BUILD SUCCESS | — |
| A3 D3 | **HECHO** (PR listo) | `feat/identity-d3-aceptaciones-login` `a477610` (código en `cc5239e`) | #144 (depende de #137 y #142) | ci-local `d0b3d7c`: core 340, crypto 50, ai 47, api 162, identity 359, convocatoria 253, app 478, BUILD SUCCESS | fusionar #137 y #142 antes |
| A4 D6 sin D7 | **HECHO** (PR listo) | `feat/identity-d6-desactivacion` `ff9b9d7` (código en `8bc0b09`) | #146 | ci-local `98f366f`: core 340, crypto 50, ai 47, api 156, identity 339, convocatoria 253, app 470, BUILD SUCCESS | — |
| A5 CORS (DD-57) | en curso: rojo hecho; código `93a6adc` | `fix/cors-put-delete` `93a6adc` | #138 (borrador hasta la evidencia) | — | — |

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

## Siguiente paso exacto

A5 (CORS): verde app -am corriendo sobre `93a6adc`; luego mutaciones (`mutaciones/cors/`), ci-local, §0.44, nota de DD-57 ya hecha; reescribir el cuerpo de #138 con la evidencia (pie correcto) y pasarlo a listo. Después: pasada final y resumen.
