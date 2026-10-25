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
| A1 R-4 | en curso: rojo hecho; verde identity 351 y api 160; app corriendo | `feat/identity-r4-recuperacion-contrasena` (sobre `422a1cb`; cherry-pick de la wip → `9726be9`) | — | ver bitácora | depende de #137 |
| A2 R-5 (D1, D2, D10) | pendiente | — | — | — | — |
| A3 D3 | pendiente | — | — | — | P-R3-3 y A2 |
| A4 D6 sin D7 | pendiente | — | — | — | — |
| A5 CORS (DD-57) | plan APROBADO (Manuel, 2026-10-25); código pendiente, en la cola después de D6 | `fix/cors-put-delete` `2342b97` | #138 (borrador hasta tener la evidencia) | — | turno en la cola |

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
- A1: segunda ejecución completa de app sobre `eb7c6a6`, en curso.

## Siguiente paso exacto

A1: con app en verde sobre `eb7c6a6`: mutaciones (specs en esta rama: `mutaciones/r4/mut-identity.json` y `mutaciones/r4/mut-app.json`;
1 a 13) en un clon limpio con un repositorio Maven aislado (MAVEN_ARGS=-Dmaven.repo.local=…); estado-fase6 §0.40;
ci-local en un clon limpio; PR "depende de #137".
