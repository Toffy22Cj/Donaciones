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
| P1 D7 | HECHO (PR abierto) | `feat/identity-d7-reactivacion` `2174928` | #148 | ci-local 36a224c BUILD SUCCESS (app 566); 8/8 mutaciones | — |
| P2 G-8 truncated | HECHO (PR abierto) | `feat/r6-g8-truncated` `bc969db` | #149 | ci-local fef7e6c BUILD SUCCESS (app 564); 5/5 mutaciones | — |
| P3 M-21 | BLOQUEADO | — | — | — | `DonationIntentReadPort.DonationView` sin campaignRef (`convocatoria/.../DonationIntentReadPort.java:28-30`); cambiarlo es cambiar un puerto que usa `app` (regla 3.5), no autorizado |
| P4 T3 portada | HECHO (PR abierto) | `feat/adr051-t3-portada` `77e7136` | #150 | ci-local 3ec967a BUILD SUCCESS (app 568); 8/8 mutaciones | — |
| P5 T5 | EN CURSO | `feat/adr051-t5-evidencia-lectura` `a7ec3c9` (sale de T3) | — | rojo hecho; verde en curso | depende de #150 |

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

## Código (2026-10-25/26)

- Plan aprobado por Manuel: #147, comentario 6404356890; registrado en el primer PR de código (#148, `e15c764`).
- D7: #148. G-8 y T3, en curso.
- **M-21 bloqueado (regla 3 del prompt):** "convocatorias apoyadas" necesita el `campaignRef` de cada intención, y `DonationView` no lo lleva. Alternativas para Manuel: (a) añadir `campaignRef` a `DonationView` (cambio aditivo de un puerto de `convocatoria` que usa `app`); (b) sacarlo del fondo (`DonationReadModel.campaignRef`), pero solo existe para las donaciones con fondos aplicados, así que una `CONFIRMED` sin fondos aún no contaría.
- Herramientas en /home/user/tools: ci.sh, mut.sh (con "mutación 0" de línea base), evidencia.py, ambos.py.

- G-8: #149. T3: #150. T5: en curso, sale de `feat/adr051-t3-portada` porque retirar una portada necesita `ConvocatoriaCoverReferenceService` (depende de #150).
- Las cinco ramas añaden secciones junto a §0.60 en estado-fase6: cuando se fusione una, las demás integran develop (merge normal, conservar los dos lados) y repiten la CI.
