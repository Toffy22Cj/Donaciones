# Plan — Ficha R-6, Bloque C: relación padre-hijo de los lotes en el seguimiento (G-4) y estado de anclaje por paso (G-3)

**Estado: BORRADOR — espera "plan aprobado" (regla 3.4).** No autoriza código.
**Fecha:** 2026-10-24. **Autor:** Manuel, con asistencia de Claude. **Base verificada:** `develop` `0b3dc3e`.
**Norma:** `Documentos/ficha-R6-datos-estilo-v2.md`, CONGELADA: §5 (Bloque C), §7 (tests 7, 8 y 9), §8, §9 (D-R6-5, D-R6-6, D-R6-10) y §10 (C-4).
**Rama de implementación prevista:** `feat/r6-bloque-c`, **desde el `develop` posterior a la fusión del Bloque B (#113)**: los dos tocan los mismos archivos del seguimiento (§0.1). Es un PR solo para este bloque (D-R6-10).

---

## 0. Verificación de la ficha contra el código (`0b3dc3e`)

| # | Afirmación de la ficha (§5) | Resultado | Evidencia |
|---|---|---|---|
| C-V1 | G-4: `LogisticsProjection.parentAssetRef` ya existe en la proyección | **CONFIRMADA** | `DonationProjectionDocument.java:78` (también `rootAssetRef`, `:79`). Guarda el **`assetId` del padre**, no un ref opaco. Lo escribe `DonationProjectionHandler.java:241-242` desde el registro |
| C-V2 | G-4: solo falta pasarlo por `LogisticsReadItem` y el mapeador | **CONFIRMADA** | `LogisticsReadItem` no tiene `parentAssetRef` (`assetId`, `lifecycleStatus`, `assetType`, `unitOfMeasure`, `quantity`, `currentLocation`, `currentCustodian`). Lo construye `DonationReadAdapter.java:33` y lo traduce `PublicDonationMapper.mapLogisticsItem` con `assetRefService.computeRef(item.assetId())` |
| C-V3 | G-4: un `parentAssetRef` siempre apunta a otro ítem de la misma respuesta, porque la proyección resuelve los hijos por el índice del padre | **CONFIRMADA, con un matiz** | `AssetProjectionRouting.resolveProjectionId` (`:104-131`): si el registro tiene padre, el hijo va a la **misma** proyección que el padre (`asset_index`, `:123-126`); si el padre no está indexado, el hijo no se proyecta (`projectionId = null`). **Matiz:** la garantía es de la proyección, no de cada lectura. El plan la vuelve a comprobar al mapear (§2.1) y omite el campo si el padre no está en `logistics` |
| C-V4 | G-4: la vista del staff no cambia | **CONFIRMADA** | `PhysicalAssetOperationalView` no tiene genealogía, y el Bloque A lo comprobó con una aserción negativa (`PhysicalAssetHttpIntegrationTest.theOperationalView_hasNoDonorNorGenealogy`) |
| C-V5 | G-3: el historial es `GET /api/v1/donations/tracking/assets/{assetRef}/history` (`PublicAssetHistoryController`) | **CONFIRMADA** | `PublicAssetHistoryController.java:27`, `:49`. Lee `AssetHistoryReadPort.getHistory(assetId)` |
| C-V6 | G-3: `AnchorStatus` tiene diez estados, y solo `RELEASED` libera los eventos (C-4) | **CONFIRMADA en `0b3dc3e`** | `crypto/.../domain/AnchorStatus.java:4-15`. Único llamador de `releaseClaim`: `CollectingFailedBatchService.java:67`. `FAILED` y `COLLECTING_FAILED` no liberan, así que **no hay motivo para detenerse** y la tabla de G-3 queda como está |
| C-V7 | G-3: la fuente es `EventBatchMembershipPort.findMembership` (`streamId`, `sequence`, `merkleBatchId`), la que ya usa `TrackingIntegrityService`, más el estado del lote | **CONFIRMADA, con dos condiciones que la ficha no dice** | (1) **El historial no lleva `sequence`.** `AssetTransitionReadModel` tiene `eventType`, `status`, `custodian`, `location` y `timestamp`, pero no `sequence`, aunque el documento de proyección sí lo guarda (`AssetHistoryProjectionDocument.java:39`). Sin él no se puede cruzar un paso con su evento. (2) **`api` no ve `crypto`.** `api/pom.xml` solo depende de `core` y `contracts`, y `core` solo de `contracts`; el estado del lote está en `crypto` (`MerkleBatchRepositoryPort.findByBatchId`, `MerkleBatch.status`). Igual que en G-1 (D-R6-1), hace falta un puerto que `app` implemente (**P-R6C-1**) |

**Ninguna contradicción** con la ficha ni con ADR vigentes. C-V3 y C-V7 son condiciones de implementación.

### 0.1 Archivos que comparte con el Bloque B

El Bloque B (#113) modifica estos archivos del seguimiento, y el C también los toca:
- `api/.../mapper/PublicDonationMapper.java` (B: `FundClearingDatesReadPort`; C: `parentAssetRef`);
- `api/.../mapper/PublicDonationMapperTest.java`, `PublicDonationControllerTest.java` y `PublicDonationControllerIntegrationTest.java`;
- `PublicAssetHistoryControllerIntegrationTest.java` y `PublicNarrativeControllerIntegrationTest.java` (B añadió un doble de su puerto; C añade el de G-3);
- `app/.../DonationPaymentHttpIntegrationTest.java`;
- `referencia-api-v1.md` §8, `api-contract-matrix.md` y `estado-fase6.md`.

**Por eso la implementación sale del `develop` posterior a la fusión del #113,** y no en paralelo.

## 1. Alcance

**Dentro**
- G-4 y G-3 (ficha §5).
- Los tests 7, 8 y 9.
- La enmienda de contrato en `api-contract-matrix.md`, `referencia-api-v1.md`, `estado-fase6.md` y el aviso a la web.

**Fuera**
- La vista del staff, que no cambia (D-R6-5).
- `rootAssetRef`, que no se expone.
- El hash del evento (D-R6-6).
- El resultado `MATCH`/`MISMATCH` por paso, que sigue en `…/integrity`.
- Los bloques B y D.

## 2. Decisiones del plan

### 2.1 G-4 · `parentAssetRef?` en `logistics`

- **`core`:** `LogisticsReadItem` gana `String parentAssetId` (interno) y `DonationReadAdapter` lo llena desde `LogisticsProjection.parentAssetRef`.
- **`api`:**
  - `PublicLogisticsItemDTO` gana `@JsonInclude(NON_NULL) String parentAssetRef`.
  - `PublicDonationMapper` lo calcula como `assetRefService.computeRef(parentAssetId)` **solo si ese `parentAssetId` es el `assetId` de otro ítem de la misma `logistics`**. Si no, el campo falta (C-V3).
  - Nunca se calcula el ref de un activo que no esté en la respuesta.
- **Nada más cambia:** ni `rootAssetRef`, ni la vista del staff, ni el orden de `logistics`.

### 2.2 G-3 · `anchorStatus` por paso

**Tabla de la ficha (§5, C-4), sin cambios:**

| `anchorStatus` | Estados del lote (`AnchorStatus`) |
|---|---|
| `ANCHORED` | `ANCHORED` |
| `IN_BATCH` | `COLLECTING`, `PENDING`, `SUBMITTING`, `SUBMITTED`, `STUCK` |
| `PENDING` | sin lote (`merkleBatchId` nulo), o lote `RELEASED` |
| `UNDER_REVIEW` | `FAILED`, `COLLECTING_FAILED`, `ANCHOR_MISMATCH` |

**Diseño:**
- **`core`:**
  - `AssetTransitionReadModel` gana `long sequence` (interno), que `MongoAssetHistoryReadAdapter` lee de `AssetTransition.sequence`. Es exponer en una vista de lectura un dato que la proyección ya tiene; ver **P-R6C-2**.
  - **`sequence` nunca sale por HTTP** (DD-76).
- **Puerto nuevo de solo lectura** (P-R6C-1). Lo declara `contracts` (o `core`), lo implementa `app` y lo usa `api`, con el patrón de `CampaignPublicSummaryPort` (D-R6-1):

  ```java
  /** Estado de anclaje, en los cuatro valores de la ficha R-6 (G-3), de cada evento de un stream. */
  Map<Long, String> anchorStatusBySequence(String streamId);
  ```

- **Implementación en `app`:**
  1. `EventBatchMembershipPort.findMembership(List.of(assetId), límite)`;
  2. para cada `merkleBatchId` distinto, `MerkleBatchRepositoryPort.findByBatchId` (un activo tiene pocos lotes; §6);
  3. un `switch` **exhaustivo** sobre `AnchorStatus`, sin rama `default`, que traduce a los cuatro valores.
- **`api`:**
  - `PublicTransitionDTO` gana `String anchorStatus`, siempre presente.
  - `AssetHistoryMapper` lo toma del mapa por la `sequence` del paso.
  - Un paso cuyo evento no aparezca en el mapa (por ejemplo, una proyección antigua con `sequence = 0`) es `PENDING` (**P-R6C-3**).
- **Sin `sequence`, `streamId`, `merkleBatchId` ni hashes** en la respuesta (aserción negativa).

### 2.3 Clases nuevas y por qué (regla 2.1)

| Clase o cambio | Módulo | Fuente |
|---|---|---|
| Puerto de estado de anclaje por evento (nombre en P-R6C-1) | `contracts` o `core` | §2.2; C-V7: `api` no ve `crypto` |
| Su implementación (nombre en P-R6C-1) | `app` | Compone `core` (`EventBatchMembershipPort`) y `crypto` (`MerkleBatchRepositoryPort`), como `TrackingIntegrityService` |
| Campos internos: `LogisticsReadItem.parentAssetId` y `AssetTransitionReadModel.sequence` | `core` | C-V2, C-V7 |
| Campos de respuesta: `PublicLogisticsItemDTO.parentAssetRef?` y `PublicTransitionDTO.anchorStatus` | `api` | Ficha §5 |

**No hay excepciones nuevas.**

## 3. Tests — rojos primero (ficha §7, tests 7, 8 y 9)

| # | Test | Dónde |
|---|---|---|
| 7a | División: el hijo trae `parentAssetRef` igual al `assetRef` del padre **de la misma respuesta**; un lote sin padre no trae el campo | `api` (mapeador) y `app` (HTTP, con una división real) |
| 8 | **Aserción negativa:** ningún `parentAssetRef` apunta fuera de `logistics`. Una donación cuyo activo es hijo de un activo de **otra** donación (proyección construida a mano en Mongo) no trae el campo | `api` (mapeador) y `core` (adaptador) |
| 9a | Historial: un paso por **cada uno de los diez** `AnchorStatus` (lotes construidos a mano en `merkle_batches`) da el valor de la tabla de §2.2 | `app` (implementación del puerto, Mongo real) |
| 9b | Paso sin lote → `PENDING`; paso en un lote `RELEASED` → `PENDING` | `app` |
| 9c | **Cobertura exhaustiva del `enum`:** el `switch` no tiene `default`, así que un estado nuevo no compila; además, un test recorre `AnchorStatus.values()` y comprueba que cada valor tiene fila | `app` |
| 9d | **Aserción negativa:** la respuesta del historial no contiene `sequence`, `streamId`, `merkleBatchId` ni hashes (`merkleRoot`, `eventHash`, `previousHash`) | `api` y `app` (HTTP) |
| — | Común: `scripts/ci-local.sh` completo en verde, con el resumen literal de Surefire | — |

## 4. Mutaciones (`scripts/mutaciones.py`)

1. G-4: `parentAssetRef` sin comprobar que el padre está en `logistics` → muere 8.
2. G-4: `parentAssetRef` igual al `assetId` crudo, sin `computeRef` → muere 7a.
3. G-3: `RELEASED` → `IN_BATCH` → muere 9b.
4. G-3: `FAILED` → `IN_BATCH` → muere 9a.
5. G-3: `ANCHOR_MISMATCH` → `ANCHORED` → muere 9a.
6. G-3: cruzar por la posición del paso en la lista en lugar de por `sequence` → muere 9a (paso con dos lotes distintos).
7. G-3: copiar `sequence` al DTO → muere 9d.

## 5. Orden de trabajo (tras "plan aprobado" y la fusión del #113)

1. Rama `feat/r6-bloque-c` desde el `develop` posterior al #113, con el hash confirmado.
2. Tests rojos, con su salida literal.
3. Verde por capas, con el módulo completo en cada una:
   1. `core` (`LogisticsReadItem`, adaptador; `AssetTransitionReadModel`, adaptador);
   2. el puerto en `contracts`;
   3. su implementación en `app`;
   4. `api` (DTO y mapeadores);
   5. `app` (HTTP).
4. Mutaciones, documentos y `ci-local.sh` en un clon limpio, revisando antes que haya al menos 4 GB libres. Después, el PR.

## 6. Riesgos y preguntas

### 6.1 Riesgos

- **R-R6C-1, coste de G-3.** Por consulta de historial: una consulta de pertenencia de **un** stream, por `idx_stream_sequence` (como la de G-2, 6 claves y 0 ms en la medición del Bloque B), más una lectura por lote distinto de ese activo. Un activo tiene un evento por transición (de 4 a 6 en el recorrido normal), así que hay como mucho tantos lotes como eventos. Es acotado y no se mide aparte.
- **R-R6C-2, coste de G-4.** Ninguna lectura nueva: el dato ya viene en la proyección que se lee hoy.
- **R-R6C-3, enmienda de una regla de privacidad (D-R6-5).** La genealogía se excluyó de las lecturas. Para el donante solo se añade la relación entre lotes que ya ve, y el test 8 lo protege. Se registra en `api-contract-matrix.md`.
- **R-R6C-4, proyecciones antiguas** sin `sequence` en el historial (valor 0): sus pasos salen `PENDING` (P-R6C-3).

### 6.2 Preguntas para la aprobación

| # | Pregunta | Recomendación |
|---|---|---|
| **P-R6C-1** | Puerto de G-3: ¿en `contracts` (como `CampaignPublicSummaryPort`) o en `core.application.port.out`? Y los nombres: puerto e implementación | **`contracts`**, como D-R6-1: el puerto no usa tipos de `core`. Nombres propuestos: `AssetAnchorStatusPort`, implementado en `app` por `AssetAnchorStatusProducer` |
| **P-R6C-2** | Añadir `sequence` a `AssetTransitionReadModel` (puerto de `core` que usa `api`): ¿hace falta enmienda? | **No**, por el criterio de P-R6A-2: es mostrar en una vista de lectura un dato que la proyección ya tiene y que no cambia. No sale por HTTP |
| **P-R6C-3** | Paso sin evento en el mapa (proyección antigua con `sequence = 0`, o sin pertenencia): ¿`PENDING`? | **Sí**: es "todavía no está en ningún lote" desde el punto de vista de lo que se puede comprobar. Nunca se inventa `ANCHORED` |
| **P-R6C-4** | Registro de la enmienda de G-4 en `api-contract-matrix.md`: la fila del seguimiento aún dice `GET /tracking/{trackingCode}` (§5, ruta antigua). ¿Nota fechada en esa fila o fila nueva con la ruta real `GET /donations/tracking`? | **Nota fechada en esa fila**, que cite la ruta real y D-R6-5, sin reescribirla |
| **P-R6C-5** | Límite de `findMembership` para el stream de un activo | El de `TrackingIntegrityService` (10 000), que en un solo activo nunca se alcanza |

## 7. Documentos y aviso a la web (ficha §8)

- **`referencia-api-v1.md`** §8, notas fechadas: `logistics[].parentAssetRef?` (G-4) y `history[].anchorStatus` con sus cuatro valores (G-3).
- **`api-contract-matrix.md`:** la enmienda de G-4 en la fila del seguimiento (P-R6C-4).
- **`estado-fase6.md`:** entrada nueva con la evidencia.
- **Aviso a la web y al móvil**, con el commit de `develop`: **`anchorStatus` tiene cuatro valores, no tres** (C-4). `paxfide-web/Documentos/prompts-agentes-estilo-v2.md` §3.4 está en otro repositorio.

## 8. Archivos previstos

- **`contracts`:** el puerto de G-3 (P-R6C-1).
- **`core`:**
  - `application/port/out/LogisticsReadItem.java`, `AssetTransitionReadModel.java`;
  - `infrastructure/projection/mongo/DonationReadAdapter.java`, `MongoAssetHistoryReadAdapter.java`;
  - sus tests.
- **`app`:** la implementación del puerto en `application/integrity/` (junto a `TrackingIntegrityService`), sus tests y las pruebas HTTP.
- **`api`:**
  - `application/dto/PublicLogisticsItemDTO.java`, `PublicTransitionDTO.java`;
  - `application/mapper/PublicDonationMapper.java`, `AssetHistoryMapper.java`;
  - sus tests y los dobles del puerto (compartidos con el Bloque B, §0.1).
- **Documentos:** `referencia-api-v1.md`, `api-contract-matrix.md`, `estado-fase6.md` y `Documentos/evidencia-ci/`.
