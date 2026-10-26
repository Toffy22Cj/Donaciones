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
| P5 T5 | BLOQUEADO (rama subida, sin PR) | `feat/adr051-t5-evidencia-lectura` `2fcbaa5` (sale de T3) | — | rojo hecho; verde: app 577, 1 fallo (medición del Bloque C) | H-T5-1: dos consultas al event store por historial; decisión de Manuel (a)/(b)/(c); además depende de #150 |

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

- **T5 bloqueado (H-T5-1):** `TrackingLineageAndAnchorHttpIntegrationTest.java:246` exige una consulta al event store por historial (plan C §6); el mapper de T5 añade una segunda. Opciones en §0.64 de la rama. Recomendada (a): `StepAnchor` (contracts) gana `evidenceRef` — cambio de puerto, necesita aprobación.
- Siguiente: respuestas de Manuel para M-21 (campaignRef) y T5 (H-T5-1). Al fusionarse cualquier PR: integrar develop en los demás y repetir CI.

## 2026-10-26: M-21 (a) y T5 (a)

- #148, #149 y #150 fusionados; `develop` en `ab19717`. Decisiones de Manuel: #147, comentario 6406618994 (M-21 (a), T5 (a), autorización 3.5 de los dos puertos).
- **T5: #151.** Notas de decisión en `a0cf7f5`, antes del código; merge de develop `a94e9c9`; código `136c4ca`. App 592; mutaciones 13 de 13 muertas; ci-local exit 0 (`ci-local-136c4ca-…`).
- **M-21:** rama `feat/account-impact` desde `ab19717`. `DonationView.campaignRef`; `GET /api/v1/account/impact`. App 589; mutaciones 9 de 9 muertas; ci-local en curso.
- Orden de fusión: #151 (lleva las notas de decisión) → M-21. Si develop se mueve, integrar develop y repetir la CI.

## Deudas 1 a 3 (2026-10-26)

- Base: develop `269e047` (#147 a #152 fusionados). Google (R-6 de ADR-050) no entra.
- **P0: #153** (`chore/docs-deudas-1-a-3`, `0926c77`), solo documentos: nota de D-1, D-2 y D-3, `plan-deudas-1-a-3-2026-10-26.md`, `ficha-R6-enmienda-3.md` y las notas "pendiente" en §0.53, §0.64 y la Enmienda 2. **DETENIDO** hasta el comentario de aprobación de buelvasmanuel en #153.
- Hechos clave:
  - D-1: dedup en `MediaUploadService.java:65-71`; el índice parcial cubre también las retiradas; el 410 `EvidenceRemoved` ya existe.
  - D-2: `recordedAt` entra en el hash, pero el verificador parsea y rehace `toString()`, así que guardar 9 decimales no cambia el hash. No entra en el Merkle.
  - D-3: métodos nuevos `findLatest…`, solo para G-8.
- Contradicción anotada: ADR-025 es el Audit Log de identity, no el Event Store.
- Siguiente: tras la aprobación, P1 → P2 → P3 (P3 sale de P2 si P2 no está fusionado).
- 05:48Z: #153 APROBADO por buelvasmanuel (comentario 6409194078) y fusionado. develop en 9295634.
  - P-DB1-3: el logo y la portada quedan como deuda registrada.
  - Todo lo demás, como se propuso.
- **P1** `fix/media-evidencia-retirada` (worktree /home/user/p1): registro de la aprobación `c5f6f40`; tests `b3559b8` (rojo "expected: 410 but was: 201"); código `80dce43`. Docs §0.66 sin commit (marcadores VERDE_P1, MUT_P1, CI_P1).
- **P2** `fix/event-store-recorded-at` (/home/user/p2): tests `664669d`; código `045cd47`; test de protección del hash `7210254`; docs `§0.67`.
  - Desvío: mockStatic(Instant) en los tests, porque en JDK 21 `now()` ya da 9 cifras.
- **P3** `feat/r6-g8-descendente` (/home/user/p3), desde P2: tests `b22bcc9`; código `a60e0a2`; docs `9e1d9a9` §0.68.
  - Desvío: los dobles de los tests e2 pasan a los métodos nuevos.
- Specs de mutaciones en /home/user/tools/mut-p1, mut-p2 y mut-p3. Van de una en una, porque `m2-mut` es compartido.
- 07:40Z: **PR abiertos.** Orden de fusión: #154 → #155 → #156. Probé a fusionarlos en ese orden sobre develop 9295634 y no hay conflictos.
  - **#154** (P1, deuda 1): app 602; 5 de 5 mutaciones muertas; CI de `732c2c1` con salida 0.
  - **#155** (P2, DT-B-1): core 377 y app 601; 7 de 7 mutaciones muertas; CI de `006bac5` con salida 0.
  - **#156** (P3, P-E2-2, depende de #155): core 379 y app 604; 9 de 9 mutaciones muertas; CI de `9bffd83` con salida 0.
- Deuda nueva registrada: P-DB1-3 (logo y portada), en §0.64.
- 11:35Z #154 fusionado; 12:03Z #155 fusionado (develop 76eb4f0).
  - #156 integra develop (982cdfe): CI salida 0, app 607. El merge paralelo de otro agente (10fa18e) tenía el mismo árbol y lo integré en 01a83b8, sin forzar.
  - Hallazgo: CampaignFundingLedgerIntegrationTest.java:551 tiene una carrera (DonationIntentNotConfirmedException no aceptada). Falló una vez en la CI de b536e02. Arreglo propuesto, sin aplicar.
- Queda #156, listo para fusionar.
