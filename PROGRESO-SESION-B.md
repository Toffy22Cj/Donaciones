# Progreso — Sesión B (trabajo pesado: R-6 C con E1, R-6 D, ADR-051 T4)

Rama de memoria. **No se fusiona ni se borra.** Base: `develop` `406f9b2` (2026-10-25).

## Estado de la cola

| Ítem | Estado | Rama | Commits | PR |
|---|---|---|---|---|
| B1 · R-6 Bloque C + E1 | **HECHO** | `feat/r6-bloque-c` (desde `develop` `406f9b2`) | código hasta `7e411f1`; docs `343c70b` | **#141** (abierto, revisor Toffy22Cj) |
| B2 · R-6 Bloque D | EN CURSO (mutaciones y CI) | `feat/r6-bloque-d` (desde `feat/r6-bloque-c`; merge de C `03d7c83`) | `70a8181`, `9ae6571` (índice), `cf3c18b`, `898977b`, docs `67a5f95` | depende de #141 y #139 |
| B3 · ADR-051 T4 | PENDIENTE | `feat/adr051-t4-evidencia` | — | — |

## Arranque (2026-10-25)

- `git fetch --all`; PR abiertos: #137 (sesión A, P-R3-3). #136 (G-9) fusionado en `develop` `406f9b2`.
- Docker: el demonio no corría; arrancado `dockerd` con `mirror.gcr.io` en `/etc/docker/daemon.json` (fuera del repo).
- Aprobaciones citables: `decisiones-delegadas-2026-10.md` nota 2026-10-25 (+ "todo aprobado", #134); `plan-r6-bloque-c.md:5`.

## B1 — lo que exige el plan (plan-r6-bloque-c.md v2, literal resumido)

- §2.3: puerto de contracts `anchorBySequence(String)` → `StepAnchor(status, eventHash, proof)`, `AnchorProof(merkleRoot, leafIndex, path, transactionHash, network, anchoredAt)` sin `leafCount`; puerto nuevo de core `findByStream` → `StreamEventAnchorData(sequence, eventHash, merkleBatchId)`; `MerkleTree.proof` + `MerkleTree.ProofStep`.
- §2.4: leafIndex desde coverage (criterio `IntegrityVerificationUseCase.java:69-73`), comparar `leafHashes[leafIndex]`; nunca buscar el hash; UNDER_REVIEW sin prueba si no coincide o incoherencia; lote antiguo → ANCHORED sin prueba; un lote, una vez.
- §2.5 api: `anchorStatus`, `eventHash`, `anchorProof?` (`PublicAnchorProofDTO`); paso sin evento → PENDING sin eventHash (P-C-4); negativa sin sequence/streamId/merkleBatchId/previousHash/leafHashes/leafCount.
- Tests 7a, 8, 9a-9d, C-E1..C-E11; mutaciones 1-13; medición §6; docs §7.

## Verificaciones en el código (`406f9b2`)

- E1-V1 `MerkleTree.java:38` regla del nodo impar: CONFIRMADA. E1-V2 `hashPair` privado: CONFIRMADA.
- E1-V3 `MongoUnanchoredEventAdapter.java:96-113` (orden `streamId, sequence`): CONFIRMADA. E1-V4 `IntegrityVerificationUseCase.java:69-73`: CONFIRMADA.
- E1-V9 `EventBatchMembershipPort.java:12` sin hash: CONFIRMADA → puerto nuevo.
- C-V1 `DonationProjectionDocument.java:78` `parentAssetRef` guarda el assetId: CONFIRMADA. C-V7 `AssetHistoryProjectionDocument.java:39` `sequence`: CONFIRMADA.
- `releaseClaim` (`MongoUnanchoredEventAdapter.java:117-131`) pone `merkleBatchId` a null.

## Hallazgos y preguntas (B1)

- **H-C-1 (nombres):** el plan nombra el método y el record del puerto de core pero no la interfaz. Elegido `StreamEventAnchorDataPort` (derivado del record [Plan]) y `MongoStreamEventAnchorDataAdapter`. Puerto de contracts y productor: `AssetAnchorStatusPort`, `AssetAnchorStatusProducer` (P-R6C-1). Paquete: raíz `com.traceability.contracts` (como `NarrativeReadPort`).
- **H-C-2 (caso no cubierto):** `BlockchainAnchorProducer.java:156` reclama los eventos y `:175` guarda el lote después: un `merkleBatchId` puede no tener lote. El plan no lo cubre. Implementado como `PENDING` sin prueba (criterio de P-R6C-3: nada comprobable). **Pregunta para Manuel.**

## Evidencia B1 (literal)

- crypto rojo: `MerkleTreeTest.java:[5,49] cannot find symbol` (MerkleTree.ProofStep), `[66,53]` (proof).
- crypto verde (`mvn install -pl crypto -am`): `Tests run: 57, Failures: 0, Errors: 0, Skipped: 0`.
- core rojo: `MongoStreamEventAnchorDataAdapterTest.java:[4,76] package ...StreamEventAnchorDataPort does not exist`.
- core verde (`mvn install -pl core`): `Tests run: 345, Failures: 0, Errors: 0, Skipped: 0`.
- medición: `findByStream: planSummary=IXSCAN { streamId: 1, sequence: 1 } keysExamined=6 docsExamined=6 hasSortStage=null`.
- app rojo: `AssetAnchorStatusProducerTest.java:[36,24] cannot find symbol`.
- app (clases nuevas): `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` (módulo completo pendiente).

## Evidencia B1 (continuación)

- api verde (`mvn -o install -pl api`): `Tests run: 162, Failures: 0, Errors: 0, Skipped: 0`.
- Reactor `mvn -o install -fae` sobre `7a802f0`: contracts No tests; core 345; crypto 57; ai 47; api 162; identity 332; convocatoria 253; app 478; todos `Failures: 0, Errors: 0, Skipped: 0` (falló después el repackage offline del plugin, no un test).
- HTTP rojo (api/src/main desactivado temporalmente): `expected: "ANCHORED" but was: "null"`; golden path `[exactamente un lote viene de una división] Expected size: 1 but was: 0`.
- Medición §6: 1000 hojas 49,47 ms / 5 hojas 27,00 ms / dif. 22,46 ms (otra: 47,52 / 22,39 / 25,13). event_store `IXSCAN { streamId: 1, sequence: 1 }` 5/5; merkle_batches `IXSCAN { batchId: 1 }` 1/1. < 50 ms → sin caché.
- Mutaciones (clon limpio `7a802f0`, línea base verde): 15/15 muertas, "Mutaciones vivas: 0" en crypto, api y app (specs en el scratchpad; detalle en estado-fase6 §0.50).
- Docs: referencia-api-v1 §8 (notas G-4 y G-3/E1), api-contract-matrix §5 (P-R6C-4), ficha §9 (E4), estado-fase6 §0.50 (sin commit todavía).

## Revisión adversaria B1 (antes del PR #141)

1. Afirmaciones sin comando: ninguna; líneas citadas comprobadas con sed/grep (MerkleTree 65-97, producer 50/65/69/70/86-99/102-109, IVU 69-73, BAP 156/175).
2. Nombres inventados: StreamEventAnchorDataPort y MongoStreamEventAnchorDataAdapter (H-C-1, declarado); clases de test.
3. Documentación borrada: `git diff origin/develop -- Documentos | grep '^-[^-]'` vacío.
4. Tests por razón equivocada: t8 pasa sin la función (negativa), la valida la mutación 1. Rojo del golden path era NPE → cambiado a aserción.
5. Mutación razonable viva: SÍ, la 14 (hojas vacías) → test añadido (`7e411f1`), muerta en clon limpio; CI repetida.
6. Secretos/rutas locales: no (claves de test ya existentes en otros tests; evidencia sin rutas).
7. Rama/commit: feat/r6-bloque-c, CI sobre 7e411f1.
- CI final: `ci-local-7e411f1-2026-10-25T07-39-57Z.txt` BUILD SUCCESS (app 479).
- Pie de PR: la herramienta lo cambia; editado a mano en #141 y #139.

## Evidencia B2 (literal)

- Rojo (implementación apartada): core `package ...ActivityEventsPort does not exist`; convocatoria `package ...OrganizationCampaignClosuresQuery does not exist`; app `package ...OrganizationActivityQuery does not exist`.
- Índice rojo: `Expecting actual: "IXSCAN { eventType: 1, payload.campaignRef: 1 }"` (200 claves/200 docs, sort); verde: `IXSCAN { payload.organizationRef: 1, recordedAt: 1, streamId: 1 }` 10/10.
- Fallos de test (no de código) corregidos: filtro del profiler (ruta anidada); cursor manipulado en el último carácter Base64.
- Verde: core 351; convocatoria 255; app 494 (con #141 integrado). Todos `Failures: 0, Errors: 0, Skipped: 0`.

## Paso exacto siguiente

- B2: mutaciones corriendo en `/home/user/mut-d` (clon de `67a5f95`; specs `mut-d-core.json`, `mut-d-app.json` en el scratchpad). Después: CI en clon limpio de `67a5f95`, completar MUT_D y CI_D en estado-fase6 §0.51, PR "depende de #141 y #139".
- B3: rama `feat/adr051-t4-evidencia` desde `develop`.
