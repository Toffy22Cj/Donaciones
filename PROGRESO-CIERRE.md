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
- 12:28Z #156 fusionado. **Deudas 1 a 3 terminadas:** #153, #154, #155 y #156, todos fusionados.
- Quedan solo la deuda P-DB1-3 (logo y portada, aceptada) y la propuesta para el test con carrera de convocatoria (:551).

## Trabajo de Carlos (2026-10-29)

- Punto de partida: `develop` en 993c3e8, sin PR abiertos. Sesión web: `~/Documentos/backend-donaciones/` no está accesible.
- **P0:** PR borrador #157 (`chore/docs-trabajo-carlos-2026-10-29`, ccfeaef).
  - Hecho: la nota con C-1 a C-3 en `decisiones-delegadas-2026-10.md` y la nota en `ADR-050-enmienda-1-iat-del-login.md`. Solo añade texto.
  - Falta, porque no hay fuentes: la ficha y el plan R-1, `enmienda2-0001.patch` y el texto literal de C-1 ("[texto cortado]"). Se pidieron a Manuel en #157.
  - Hechos del plan R-1 verificados contra 993c3e8: `PublicCampaignDiscoveryQuery.java:44`, `ConvocatoriaRepositoryPort.java:42`, `MongoConvocatoriaRepositoryAdapter.java:21` y `:98`, `ConvocatoriaDocument.java:20-38`.
- **P1 (R-1):** bloqueado. Faltan el plan R-1 §2 y los tests 1 a 15 de la ficha §8.
- **P2 (Enmienda 2):** bloqueado. Falta el texto de la Enmienda 2 (E2-3 y su Definición de hecho, tests 1 a 14).
- **R-7:** no se empieza (C-4).

### Sesión web, segundo encargo (2026-10-29)
- Fuentes leídas de `wip/fuentes-carlos-2026-10-29` (`fuentes-carlos/`).
- **P0 HECHO:** #157 listo para revisión (ya no es borrador). Commits:
  - `6f7afa2`: Enmienda 2 con `git am`, autor Carlos;
  - `d1a4b75`: ficha y plan R-1, copia literal;
  - `546f505`: notas.
  `git diff develop -- Documentos | grep '^-[^-]'` → 0. C-1 sigue como "[texto cortado]".
- **P2 plan HECHO:** #158 (`chore/docs-plan-adr050-enmienda-2`, `4fbc326`), "depende de #157". ESPERA el comentario de aprobación de Manuel. Sin código de la Enmienda 2.
- **P1 R-1:** EN CURSO. Rama `feat/convocatoria-r1-busqueda-descubrimiento` desde #157.
- **R-1, código:** commit `7ef2625` en `feat/convocatoria-r1-busqueda-descubrimiento` (todavía sin push).
  - Rojo por compilación guardado.
  - Verde: `convocatoria` "Tests run: 272, Failures: 0, Errors: 0, Skipped: 0"; `app` dirigido, 41 en verde.
  - Pendiente: `app` completo, mutaciones (en curso en el clon `scratchpad/mut`), `ci-local` en un clon limpio y el PR.
  - Docker se arranca a mano en esta sesión (`dockerd`).
- **R-1 HECHO:** #159 listo para revisión ("depende de #157"). Commits:
  - `7ef2625`: código;
  - `7814d8d`: documentos;
  - `e0a457a`: evidencia.
  Mutaciones: 11 de 11 en `convocatoria` y 12 de 12 en `app`. `ci-local` en `7814d8d`: convocatoria 272 y app 622, BUILD SUCCESS.
- **Siguiente:** esperar el comentario de aprobación de Manuel en #158 para escribir el código de la Enmienda 2 (rama `feat/identity-adr050-e2-verificacion-correo` [Plan]). Cuando #157 se fusione, integrar `develop` en #158 y #159 con merge y repetir `ci-local` en #159.

### Tercer encargo (2026-10-29): R-1 al día, C-1 y R-7, código de la Enmienda 2
- **1. #159 HECHO:**
  - merge de `develop` `d313bc6` (`94de436`);
  - las dos notas en la ficha R-1 (`6dc17f5`);
  - `ci-local` en `6dc17f5`: convocatoria 272 y app 622, BUILD SUCCESS (`7e35792`).
  Listo para fusionar.
- **2. #161 HECHO** (`chore/docs-r7-y-c1`): C-1 y C-5 literales (`0232750`) y la ficha R-7 con `git am`, autor Carlos (`0481f9f`, `dd7a5a0`).
- **4. Plan R-7: #162 HECHO** (`chore/docs-plan-r7`, `ea0883c`, "depende de #161"). ESPERA la aprobación de Manuel; sin código.
- **3. Enmienda 2: EN CURSO** en `feat/identity-adr050-e2-verificacion-correo`, desde #158 (merge de `develop` `0fa64fd`).
  - Commits: `449fa26` (nota de la aprobación de Manuel) y `59d25c0` (tests en rojo por compilación).
  - Código escrito.
  - **BLOQUEO P-E2V-13:** ADR-038 §2.2 (`ArchitectureTest.java:67-88`) no deja a `EmailVerificationService` construir el actor de `EMAIL_VERIFIED`. No se toca el test; lo decide Manuel.
- **3. Enmienda 2: #163 en BORRADOR**, BLOQUEADO por P-E2V-13 (el actor de `EMAIL_VERIFIED` frente a ADR-038 §2.2, `ArchitectureTest.java:67-88`).
  - Commits: `7ea5370` (código), `aea7f43` (tests adaptados y semilla) y `fd7af15` (documentos).
  - `identity`: 411 tests, con una sola falla, la de esa regla. `app`: en verde en dirigido.
  - Con la decisión de Manuel: aplicar la opción, hacer las mutaciones y `ci-local`, y pasar el PR a listo.
  - El contenedor se reinició una vez (el error 137 fue por correr dos builds a la vez). Docker se arranca a mano.

## Google R-6 (2026-10-30)
- **Al empezar:**
  - #158, #159 y #161 ya fusionados (`develop` `6375c40`);
  - Manuel decidió P-E2V-13, opción (a), en #163, y aprobó el plan R-7 en #162 (P-R7-1: el JSON de DIVIPOLA está en `wip/fuentes-carlos-2026-10-29:fuentes-carlos/divipola/divipola.json`).
- **#163:**
  - merge de `develop` (`6375c40`), con conflictos solo en notas, resueltos conservando los dos lados;
  - P-E2V-13 (a) aplicada en `31c2a56`;
  - `ci-local` en curso.
- **P0 HECHO:** #164 (`chore/docs-plan-adr050-r6-google`, `7c38e45`, "depende de #163"), con las notas de G-0 a G-2 y el plan con P-G-1 a 14. ESPERA la aprobación de Manuel; sin código de Google.
- **Pendiente:** el código de R-7 (aprobado en #162) no está en este encargo; queda para el siguiente.
- **#163 HECHO, listo para fusionar:**
  - mutaciones 13 de 13 (la 4 necesitó el test `test5_markUsedIfPending_isConditional_…`, `dfc4768`);
  - `ci-local` en `dfc4768`: identity 412 y app 630, BUILD SUCCESS (`b632154`).
- **#164:** sin comentario de aprobación todavía. P1 (código de Google) detenido.

## Código R-7 y Google (2026-10-31)
- `develop` en `b0654aa` (#162, #163 y #164 fusionados).
- **R-7** (`feat/identity-r7-perfil-organizacion`, con push), PR #165 listo (no borrador):
  - commits: `4b43bb7` (aprobación), `931fce1` (rojo), `3df49ca` (código), `19c611a` (arreglo del test 8), `2d3862e` (documentos), `3e2a6a1` (equals del perfil vacío) y `86b9fbe` (evidencia de ci-local);
  - mutaciones: 12 de 12 muertas;
  - ci-local en `3e2a6a1`: BUILD SUCCESS (app 640, identity 428).
- **Google** (`feat/identity-adr050-r6-google`, con push), PR #166 listo (no borrador), cabeza `098e8b0`:
  - commits: `ece5f03`, `6faec6c`, `5bb0925`, `9787e06`, `91e376f` y `098e8b0` (evidencia de ci-local);
  - mutaciones: 17 de 17 muertas (con la 1b);
  - ci-local en `91e376f`: BUILD SUCCESS (api 175, identity 413, app 653).
- Estado al cierre (2026-10-31): `develop` sigue en `b0654aa` y no hay que hacer P3. Los dos PR están abiertos y son fusionables; el orden es #165 y después #166. El segundo necesita un merge de develop (conflicto de notas en estado-fase6) y repetir ci-local.

## Cierre R-7 y Google (2026-10-31)
- P0: el pie de #166 está corregido (termina en la línea 🤖), y el cuerpo incluye las respuestas de Manuel. `gh pr edit` no sirve porque GraphQL da 403, y el proxy REST reescribe el pie; se usó la herramienta MCP `update_pull_request`.
- P1, hecho:
  - R-7: nota en `decisiones-delegadas` en la rama de #165 (`1ed060e`). Solo toca Documentos, así que no hace falta repetir ci-local;
  - Google: notas en `decisiones-delegadas` y en el plan, en la rama de #166 (`c569d84`).
- P2, hecho: #167 (`chore/docs-despliegue-backend-2026-10`, `4477e10`), `Documentos/despliegue-backend-2026-10.md`.
- P3: esperando a que se fusione #165; se comprueba cada 10 min, como mucho 3 h (empezó a las 18:50Z; límite: 21:50Z).
