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
| A1 R-4 | pendiente | — | — | — | (depende de #137: rama desde `fix/identity-p-r3-3-iat-del-login`) |
| A2 R-5 (D1, D2, D10) | pendiente | — | — | — | — |
| A3 D3 | pendiente | — | — | — | P-R3-3 y A2 |
| A4 D6 sin D7 | pendiente | — | — | — | — |
| A5 CORS (DD-57) | pendiente (PR borrador, sin código) | — | — | — | sí escrito de Manuel |

## Bitácora

- 2026-10-25: arranque de la sesión A; memoria creada desde `develop` `406f9b2`.

## Siguiente paso exacto

A1: crear `feat/identity-r4-recuperacion-contrasena` desde `origin/fix/identity-p-r3-3-iat-del-login` (`422a1cb`),
traer `wip/identity-r4-recuperacion`, compilar y enseñar el rojo literal apartando el código de producción.
