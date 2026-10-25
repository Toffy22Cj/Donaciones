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
| A5 CORS (DD-57) | HECHO (solo plan) | `fix/cors-put-delete` `f8f0d4e` | #138 (borrador) | — (sin código) | sí de Carlos (DD-57) y aprobación escrita de Manuel |

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

## Siguiente paso exacto

A1: cuando acabe `mvn test -pl app -am`, aplicar `defaultCandidate = false` y su test, volver a pasar app, y después
documentos (referencia-api, ya escrita sin commit; nota en el plan R-3 + R-2, escrita sin commit; estado-fase6 §0.40),
commit, push, mutaciones 1 a 12 en un clon limpio, ci-local y PR "depende de #137".
