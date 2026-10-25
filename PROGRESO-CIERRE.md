# PROGRESO — Cierre del backend de Manuel (2026-10-25)

Rama de memoria: `wip/progreso-cierre-backend`. **No se fusiona ni se borra.** Fuente: `cierre-backend-2026-10-25.md` (subido por Manuel), §2 (8 decisiones) y §4 (prompt).
Secciones de estado-fase6: SOLO 0.60 a 0.69.

## Estado al arrancar

- `develop` = `0f71bfc` (merge de #145). Ningún PR abierto (último #146).
- Worktrees: memoria `/home/user/cierre-prog`; documentos `/home/user/cierre-docs` (`chore/docs-cierre-backend`).

## Cola

| Ítem | Estado | Rama | PR | Evidencia | Bloqueado por |
|---|---|---|---|---|---|
| P0 documentos + plan | HECHO (PR abierto) | `chore/docs-cierre-backend` `17186e0` | #147 | solo documentos; diff sin líneas borradas | "plan aprobado" de Manuel |
| M-22 verificación | HECHO con hallazgo | (en #147, §0.60) | #147 | 11 campos de `DonationDtos.java:30-32` | lista de campos del comprobante (H-CB-5) |
| P1 D7 | PENDIENTE | `feat/identity-d7-reactivacion` | — | — | plan aprobado |
| P2 G-8 truncated | PENDIENTE | `feat/r6-g8-truncated` | — | — | plan aprobado |
| P3 M-21 | PENDIENTE | `feat/account-impact` | — | — | plan aprobado |
| P4 T3 portada | PENDIENTE | `feat/adr051-t3-portada` | — | — | plan aprobado |
| P5 T5 | PENDIENTE | `feat/adr051-t5-evidencia-lectura` | — | — | enmienda ADR-021-D aprobada |

## Bitácora

- 2026-10-25: arranque desde `develop` `0f71bfc`.

## P0 — documentos (2026-10-25)

- Escrito en `chore/docs-cierre-backend`:
  - las 8 decisiones literales en decisiones-delegadas;
  - las 3 notas "implementada" de la Enmienda 1 de ADR-050 (#137, #140, #144);
  - las notas de Google y D7 en ADR-050 y la de DT-B-1 en §0.53;
  - las notas de T3 y T5 en ADR-051;
  - `ficha-R6-enmienda-2.md` y su nota en la ficha;
  - `ADR-021-D-enmienda-1-huella-evidencia.md` (BORRADOR);
  - `plan-cierre-backend-2026-10-25.md`;
  - §0.60 con M-22.
- Hallazgos H-CB-1 a H-CB-6 (en el plan).

### Revisión adversaria antes del commit de P0

1. **Afirmaciones sin comprobar:**
   - cada ruta:línea del plan y de las enmiendas se volvió a abrir; se corrigieron 6 citas desplazadas (ficha :135→:136; ADR-051 :204→:205 y :37→:38; F6 :121→:123; :116-124→:124/:126; F8 :147);
   - `PublicCampaignView` solo la usa CV-07, así que se corrigió P-T3-2;
   - parte 2 en #140: comprobado con `ChangeCredentialsService.java:131`.
2. **Nombres inventados:** todos los nuevos van marcados [Plan], y los que da el ADR (`REACTIVATE_ACCOUNT`, `READ_DELIVERY_EVIDENCE`, `REMOVE_MEDIA`) están citados.
3. **Líneas de documentación borradas:** `git diff | grep '^-[^-]'` vacío.
4. **Tests que pasan por la razón equivocada:** no hay código. En el plan, cada test negativo lleva `never()` o la comprobación de lo que no cambia.
5. **Mutaciones que sobrevivirían:** cada mutación del plan tiene un test que la mata; G-8 (4) pide un test propio, que es el `never()` sobre el stream 10 001.
6. **Secretos o rutas locales:** ninguno. Las rutas de PaxFide se citan por repositorio y archivo, sin rutas de la máquina.
7. **Rama y commit:** `chore/docs-cierre-backend` desde `origin/develop` `0f71bfc`, sin `paxfide-predictor-demo/` ni configuración local.

- #147 abierto, con el pie corregido. **DETENIDO en el código** hasta un "plan aprobado" de Manuel, citable. T5 espera además la aprobación de la enmienda de ADR-021-D.
