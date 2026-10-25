# Plan — Ficha R-6, Bloque C (versión 2): parentAssetRef (G-4), anchorStatus por paso (G-3) y hash con prueba de Merkle (E1)

**Estado: BORRADOR, versión 2. Espera el "plan aprobado" de Manuel**, por la delegación de Carlos del 2026-10-24 (`decisiones-delegadas-2026-10.md`, nota del 2026-10-24). No autoriza código (regla 3.4).
**Fecha:** 2026-10-24 (UTC). **Autor:** Manuel, con asistencia de Claude.
**Base verificada:** `develop` `8e37667` (posterior a la fusión del #113, el Bloque B).
**Norma:**
- `ficha-R6-datos-estilo-v2.md`, CONGELADA: §5 (Bloque C), §7 (tests 7, 8 y 9), §8, §9 (D-R6-5, D-R6-6, D-R6-10) y §10 (C-4);
- `ficha-R6-enmienda-1.md` **§E1**, que reabre D-R6-6 (estado PROPUESTA; ver B-C-1);
- las decisiones de Manuel sobre los puntos [Manuel] de E1: `plan-ejecucion-cierre-manuel-2026-10-24.md` §0.4.

**Qué cambia respecto a la versión 1:**
- G-3 deja de dar solo `anchorStatus`: cada paso lleva también `eventHash` y, si su lote está `ANCHORED`, `anchorProof` (E1).
- El puerto de P-R6C-1 crece para llevar esos datos.
- G-4, la tabla de los cuatro estados (C-4) y P-R6C-2 a P-R6C-5 no cambian.
- La versión 1 se conserva entera en el **Anexo A**. Sus verificaciones C-V1 a C-V7 siguen valiendo en `8e37667`: los archivos citados no han cambiado, salvo los del seguimiento que tocó el Bloque B (§0.1 de la versión 1).

**Rama de implementación:** `feat/r6-bloque-c`, desde el `develop` del momento, con su hash. Un PR para este bloque (D-R6-10).

---

## 0. Bloqueo de estado (anotado, no se resuelve aquí)

**B-C-1.** `ficha-R6-enmienda-1.md:3` dice "**Estado: PROPUESTA.** … Carlos da el visto bueno en el PR". El PR que la trajo (#117) lo fusionó Carlos el 2026-10-24 (21:07:55Z) **sin Approve ni ninguna review** en GitHub (H-1). Es la misma pregunta que B-G9-1 en #124: ¿la Enmienda 1 queda aprobada?

Mientras no conste:
- la parte de E1 (§2.3 a §2.5) no se implementa;
- G-4 y G-3 sin E1 sí pueden implementarse, porque vienen de la ficha CONGELADA.

**Pregunta para Manuel (P-C-0):** si B-C-1 tarda, ¿se implementan G-4 y G-3 primero y E1 en un segundo PR? Eso rompe "un PR por bloque" (D-R6-10), así que también necesita el sí de Carlos. **Recomendación:** esperar y hacer un solo PR.

## 1. Verificación de E1 contra el código (`develop` `8e37667`)

| # | Afirmación de E1 o de §0.4 | Resultado | Evidencia |
|---|---|---|---|
| E1-V1 | **Regla del nodo impar:** el último nodo de un nivel impar se combina consigo mismo | **CONFIRMADA** | `crypto/src/main/java/com/traceability/crypto/domain/MerkleTree.java:38`: `String right = (i + 1 < currentLevel.size()) ? currentLevel.get(i + 1) : left;` (comentario en `:23` y `:37`) |
| E1-V2 | Nodo padre = `SHA-256(UTF-8(izquierdo_hex + derecho_hex))` en hexadecimal minúscula | **CONFIRMADA** | `MerkleTree.java:52-61` (`hashPair`: `left + right`, `getBytes(UTF_8)`) y `:63-73` (`bytesToHex`, minúscula y con ceros a la izquierda). `hashPair` es `private static`, así que `proof` tiene que vivir en la misma clase para reutilizarlo |
| E1-V3 | Hojas en el orden de `getEventHashesByCoverage`: `streamId` y después `sequence` | **CONFIRMADA** | `MongoUnanchoredEventAdapter.java:96-113`: consulta por los rangos de `coverage`, `Sort.by(ASC, "streamId", "sequence")` (`:108`). La usa `BlockchainAnchorProducer` para construir el árbol (`:132` y `:186`) y guarda esas hojas con `transitionCollectingToPending(batchId, merkleRoot, leafHashes)` |
| E1-V4 | `leafIndex` se calcula desde `coverage` con ese orden | **Ya existe el cálculo**, no es nuevo | `IntegrityVerificationUseCase.java:69-73` construye `expectedIdentities`: `coverage` ordenado por clave (`Map.Entry.comparingByKey()`) y, en cada stream, `rangeClosed(fromSequence, toSequence)`. La posición de `(streamId, sequence)` en esa lista es su `leafIndex`. El plan reutiliza **ese mismo criterio**, no inventa otro |
| E1-V5 | Lote antiguo sin `leafHashes` | **CONFIRMADA** | `IntegrityVerificationUseCase.java:61-65` lanza `LegacyBatchLeafHashesUnavailableException` si `leafHashes` es nulo o vacío (`crypto/.../domain/exception/`) |
| E1-V6 | Datos de `anchorProof` en el lote | **CONFIRMADA** | `MerkleBatch` (`crypto/.../domain/MerkleBatch.java:8-24`): `merkleRoot`, `leafHashes`, `network`, `transactionHash`, `anchoredAt`, `coverage` y `status` |
| E1-V7 | `eventHash`: 64 caracteres hexadecimales en minúscula | **CONFIRMADA** | `JcsHashAdapter.java:42-59` (SHA-256, "lowercase hex"); el Event Store lo guarda en `TraceabilityEventDocument.java:46` (`MongoEventStoreAdapter.java:82`, `:101`) |
| E1-V8 | Tamaño máximo de un lote | **1000 hojas** por defecto | `BlockchainAnchorProducer.java:42`: `traceability.anchor.producer.max-events-per-batch:1000`. Es el caso que se mide (§6) |
| E1-V9 | Hay un puerto que dé el `eventHash` de cada evento de un stream | **NO.** `EventBatchMembershipPort.EventMembership` es `(streamId, sequence, merkleBatchId)`, sin hash (`core/.../port/out/EventBatchMembershipPort.java:12`) | Añadirle el hash sería **cambiar un puerto existente**, y eso es la regla 3.5. **No se toca:** se usa un puerto nuevo de solo lectura (§2.3, P-C-2) |
| E1-V10 | `app` ya usa `MerkleTree` directamente | **CONFIRMADA** | `IntegrityVerificationUseCase.java:90` (`MerkleTree.build(currentLeaves)`) y `BlockchainAnchorProducer.java` (Fase 2). Ver P-C-1 |
| E1-V11 | Hoy el DTO no tiene `anchorStatus` | **CONFIRMADA** | `PublicTransitionDTO` (`api/.../dto/PublicTransitionDTO.java`): `eventType`, `timestamp`, `locationZone`, `custodianCategory`, `status`. G-3 no está implementado |

**Ninguna contradicción** con E1 ni con ADR vigentes. E1-V9 obliga a un puerto nuevo, que es lo que §0.4 permite.

## 2. Diseño

### 2.1 G-4 · `parentAssetRef?` en `logistics`

Sin cambios respecto a la versión 1 (Anexo A, §2.1).

### 2.2 G-3 · `anchorStatus` por paso

La tabla de los cuatro valores (C-4) **no cambia**. La versión 1, §2.2, sigue valiendo: el enum `TrackingAnchorStatus` de `contracts`, el `switch` exhaustivo sin `default`, `PENDING` sin lote o con lote `RELEASED`, y P-R6C-3. Con una sola excepción, que añade E1:

- **Un evento alterado pasa a `UNDER_REVIEW` aunque su lote esté `ANCHORED`** (E1.2). La regla está en §2.4, paso 4.

### 2.3 Puertos (P-R6C-1 ampliado y uno nuevo en `core`)

**(1) El puerto de P-R6C-1, en `contracts`, con su resultado ampliado.** Los nombres van marcados **[Plan]**:

```java
/** Estado de anclaje, hash y, si el lote está ANCHORED, prueba de Merkle de cada evento de un stream (ficha R-6, G-3 y Enmienda 1, E1). */
Map<Long, StepAnchor> anchorBySequence(String streamId);

record StepAnchor(TrackingAnchorStatus status, String eventHash, AnchorProof proof /* nulo si no hay prueba */) {}
record AnchorProof(String merkleRoot, int leafIndex, List<ProofStep> path,
                   String transactionHash, String network, Instant anchoredAt) {}
record ProofStep(Side side, String hash) {}
enum Side { LEFT, RIGHT }
```

- `AnchorProof` es el `anchorProof` de E1.2, **sin `leafCount`** (§0.4).
- La versión 1 proponía `Map<Long, TrackingAnchorStatus> anchorStatusBySequence(String)`. El puerto aún no existe en `develop`, así que cambiar su forma **no** toca un puerto existente.

**(2) Un puerto nuevo de solo lectura en `core`**, porque ninguno da el hash (E1-V9). Nombres **[Plan]**:

```java
/** Hash y lote de cada evento de un stream, en orden de sequence. Solo lectura. */
List<StreamEventAnchorData> findByStream(String streamId);
record StreamEventAnchorData(long sequence, String eventHash, String merkleBatchId) {}
```

- Su adaptador Mongo hace **una** consulta: `{streamId}`, ordenada por `sequence`, proyectando `sequence`, `eventHash` y `merkleBatchId`. La cubre `idx_stream_sequence`.
- Sustituye a `findMembership` **solo en G-3**. `EventBatchMembershipPort` no cambia, y `TrackingIntegrityService` lo sigue usando.

**(3) `MerkleTree.proof`, en `crypto`** (§0.4):

```java
public static List<ProofStep> proof(List<String> leafHashes, int index)
public record ProofStep(Side side, String hash) {}   // anidado en MerkleTree [Plan]
```

- Es una función pura que reutiliza `hashPair` (privado, E1-V2), con la misma regla del nodo impar que `build` (`:38`).
- El último nodo de un nivel impar da `{RIGHT, el mismo nodo}`.
- Lanza `IllegalArgumentException` si las hojas están vacías o el índice está fuera de rango, como `build` (`:26-28`).
- **P-C-1:** §0.4 dice que `MerkleTree.proof` se expone "con un puerto nuevo de solo lectura". `app` ya llama a `MerkleTree` directamente (E1-V10), y el puerto de solo lectura que ve `api` es el de (1). **Recomendación:** que el puerto nuevo sea el de (1), y no añadir otro en `crypto` que solo envuelva una función estática. Si Manuel prefiere un puerto en `crypto`, sería `crypto/application/port/in/MerkleProofPort` **[Plan]**, implementado por un servicio de `crypto`.

### 2.4 Implementación del puerto (1), en `app` (`application/integrity/`, junto a `TrackingIntegrityService`)

Para un `streamId`:

1. `findByStream(streamId)` (puerto (2)): una consulta.
2. Para cada `merkleBatchId` distinto, `MerkleBatchRepositoryPort.findByBatchId`. Un activo tiene pocos eventos (de 4 a 6) y, por tanto, pocos lotes.
3. **`anchorStatus`:** el `switch` exhaustivo de C-4 (versión 1, §2.2).
4. **Si el lote está `ANCHORED` y tiene `leafHashes`:**
   1. **`leafIndex` se calcula desde `coverage`**, con el criterio de `IntegrityVerificationUseCase.java:69-73`: se suman los tamaños `toSequence − fromSequence + 1` de los streams de `coverage` cuya clave es menor que `streamId` (por `String.compareTo`, como `comparingByKey`), y después se suma `sequence − fromSequence` del propio stream.
      - Si `streamId` no está en `coverage`, o su `sequence` cae fuera del rango, o el índice no es menor que `leafHashes.size()`: `UNDER_REVIEW`, sin `anchorProof`. Es una incoherencia entre el evento y el lote, y nunca se devuelve una prueba en ese caso.
   2. **Se compara `leafHashes[leafIndex]` con el `eventHash` actual. Nunca se busca el hash en la lista (§0.4).**
      - Si son distintos: `UNDER_REVIEW`, sin `anchorProof` (E1.2).
      - Si son iguales: `anchorProof = {merkleRoot, leafIndex, MerkleTree.proof(leafHashes, leafIndex), transactionHash, network, anchoredAt}`.
   3. **Un lote, una vez:** si varios pasos del activo caen en el mismo lote, sus pruebas se calculan con la misma lista de hojas leída en el paso 2. No se repite la lectura **[Plan]**.
5. **Lote `ANCHORED` sin `leafHashes`** (antiguo, E1-V5): `ANCHORED`, con `eventHash` y sin `anchorProof`. No se reconstruye nada con las hojas actuales (E1.2).
6. **Cualquier otro estado:** `eventHash` y sin `anchorProof`.

**P-C-3:** en un lote `IN_BATCH` con `leafHashes` (de `PENDING` a `SUBMITTED`), ¿también se compara el hash, y un evento alterado da `UNDER_REVIEW`? E1.2 lo dice para "el paso" sin restringirlo, pero la prueba solo existe con `ANCHORED`. **Recomendación:** comparar solo con `ANCHORED`. En los demás estados no hay prueba que proteger, y `UNDER_REVIEW` ya tiene sus estados en C-4.

### 2.5 `api`

- `PublicTransitionDTO` gana:
  - `String anchorStatus` (G-3), siempre presente;
  - `String eventHash` (E1), siempre presente;
  - `@JsonInclude(NON_NULL) PublicAnchorProofDTO anchorProof` **[Plan: nombre]**, con `merkleRoot`, `leafIndex`, `path: [{side, hash}]`, `transactionHash`, `network` y `anchoredAt` (ISO-8601 UTC).
- `AssetHistoryMapper` cruza cada paso con el mapa **por su `sequence`** (versión 1, §2.2). Si un paso no está en el mapa:
  - `anchorStatus = PENDING` (P-R6C-3);
  - sin `anchorProof`;
  - **sin `eventHash`, porque no hay evento del que leerlo.** E1.2 dice "Siempre", así que se pregunta (**P-C-4**). **Recomendación:** omitirlo antes que inventarlo (regla 2.2). Pasa solo con las proyecciones antiguas de R-R6C-4.
- **Aserción negativa:** ni `sequence`, ni `streamId`, ni `merkleBatchId`, ni `previousHash`, ni `leafCount`, ni `leafHashes` (DD-76, §0.4).

### 2.6 Vectores fijos (E1.5)

- Archivo de recursos `crypto/src/test/resources/merkle/proof-vectors.json` **[Plan: ruta]**: lista de `{leaves, index, path, root}`.
- Hojas = SHA-256 en hexadecimal de textos fijos (`"leaf-0"`, `"leaf-1"`…), para que la web pueda regenerarlas.
- Casos:
  - 1 hoja (camino vacío);
  - 2 hojas;
  - 3 hojas (impar en el primer nivel);
  - **5 hojas, con el índice 4** (el último, que se combina consigo mismo en dos niveles);
  - 5 hojas con el índice 2.
- El test comprueba que `MerkleTree.build` da esa raíz y `MerkleTree.proof` da ese camino.
- Además, un verificador independiente en el test, el pseudocódigo de E1.3, recalcula la raíz desde el camino.
- La web copia el archivo para su test de F-C2. El aviso cita el commit (§7).

### 2.7 Clases nuevas y por qué (regla 2.1)

| Clase o cambio | Módulo | Fuente |
|---|---|---|
| Puerto de P-R6C-1 con `StepAnchor`, `AnchorProof`, `ProofStep` y `Side` | `contracts` | Ficha §5 (G-3), E1.2; C-V7 (`api` no ve `crypto`) |
| `TrackingAnchorStatus` (cuatro valores) | `contracts` | C-4; corrección de Manuel del 2026-10-24 |
| Puerto nuevo `findByStream` con `StreamEventAnchorData` | `core` | E1-V9; regla 3.5 (no tocar `EventBatchMembershipPort`) |
| Su adaptador Mongo | `core` | Idem |
| `MerkleTree.proof` y `MerkleTree.ProofStep` | `crypto` | E1.3; §0.4 |
| Implementación del puerto de P-R6C-1 | `app` | Compone `core` y `crypto`, como `TrackingIntegrityService` |
| `PublicAnchorProofDTO` y campos de `PublicTransitionDTO` | `api` | E1.2 |
| `LogisticsReadItem.parentAssetId`, `AssetTransitionReadModel.sequence` | `core` | Versión 1 (C-V2, C-V7) |

No hay excepciones nuevas.

## 3. Tests (rojos primero)

Los tests 7a, 8, 9a, 9b y 9c de la versión 1 (Anexo A, §3) siguen. El 9d se amplía. Se añaden:

| # | Test | Dónde |
|---|---|---|
| C-E1 | `MerkleTreeTest`: para listas de 1 a 33 hojas, la prueba de **cada** índice reconstruye `getRoot()` con el verificador de E1.3 | `crypto` |
| C-E2 | Cambiar un carácter de un paso, de la hoja o el `side` de un paso invalida la prueba (negativa) | `crypto` |
| C-E3 | Vectores fijos de §2.6, entre ellos el de 5 hojas | `crypto` |
| C-E4 | `proof` con hojas vacías o con un índice fuera de rango → `IllegalArgumentException` | `crypto` |
| C-E5 | Integración: lote `ANCHORED` con varios streams y varios eventos por stream (el activo **no** es el primer stream de `coverage`) → `eventHash` igual al del Event Store, `leafIndex` igual a la posición por `coverage`, y la prueba reconstruye `merkleRoot` | `app` (Mongo real) |
| C-E6 | Lote `PENDING` → `IN_BATCH`, con `eventHash` y **sin** `anchorProof` | `app` |
| C-E7 | Lote `ANCHORED` sin `leafHashes` → `ANCHORED`, con `eventHash` y **sin** `anchorProof` | `app` |
| C-E8 | **Evento alterado** (se cambia su `eventHash` en `event_store` a otro valor) → `UNDER_REVIEW`, sin `anchorProof` | `app` |
| **C-E9** | **Evento alterado con el hash de otra hoja del mismo lote:** su `eventHash` se cambia por el de **otro** evento del lote → `UNDER_REVIEW`, sin `anchorProof`. Es el test que distingue calcular el índice de buscar el hash: si se busca, se encuentra la otra hoja y sale una prueba válida para un hecho que no es el mostrado | `app` |
| C-E10 | Paso sin evento en el mapa → `PENDING`, sin `eventHash` ni `anchorProof` (P-C-4) | `api` |
| 9d (ampliado) | La respuesta no contiene `sequence`, `streamId`, `merkleBatchId`, `previousHash`, `leafHashes` ni `leafCount` | `api` y `app` (HTTP) |
| C-E11 | Por HTTP, con un lote `ANCHORED` real del recorrido: el JSON de `anchorProof` verifica con el pseudocódigo de E1.3, escrito en el test sin usar `MerkleTree` | `app` |

Cada caso de integración lleva las aserciones negativas de las otras ramas (E1.5).

## 4. Mutaciones (`scripts/mutaciones.py`)

Las 1 a 7 de la versión 1 (Anexo A, §4) siguen. La 7 se amplía a "copiar `sequence` o `leafCount` al DTO". Se añaden:

| # | Mutación | La mata |
|---|---|---|
| 8 | **Buscar el hash en `leafHashes` (`indexOf(eventHash)`) en lugar de calcular el índice desde `coverage`** (§0.4) | C-E9 |
| 9 | No comparar `leafHashes[leafIndex]` con el `eventHash` actual | C-E8 |
| 10 | El nodo impar se combina con un hash vacío en lugar de consigo mismo, en `proof` | C-E1, C-E3 (5 hojas) |
| 11 | Invertir `LEFT` y `RIGHT` | C-E1, C-E11 |
| 12 | Calcular el índice sin sumar los streams anteriores de `coverage` | C-E5 |
| 13 | Dar `anchorProof` con un lote antiguo, reconstruyendo con las hojas actuales | C-E7 |

## 5. Orden de trabajo (tras "plan aprobado" y B-C-1)

1. Rama `feat/r6-bloque-c` desde el `develop` del momento, con su hash.
2. Tests rojos, con su salida literal.
3. Verde por capas, con el módulo completo en cada una:
   1. `crypto` (`MerkleTree.proof` y los vectores);
   2. `core` (G-4, `sequence` del historial y el puerto nuevo);
   3. `contracts` (el puerto de P-R6C-1);
   4. `app` (su implementación);
   5. `api`;
   6. `app` (HTTP).
4. Medición (§6), mutaciones, documentos (§7).
5. `free -g` con al menos 4 GB y `ci-local.sh` en un clon limpio.
6. Autoauditoría contra la ficha §5, E1 y §0.4. PR con Carlos.

## 6. Medición (§0.4)

**Con `explain()`:**
- la consulta del puerto nuevo (`findByStream`). Se espera `IXSCAN` sobre `idx_stream_sequence`, con unas pocas claves examinadas;
- `findByBatchId`, por el índice único de `batchId` (`MerkleBatchDocument.java:21-22`).

**Con el tiempo:** el historial HTTP de un activo cuyo lote `ANCHORED` tiene **1000 hojas** (el máximo, E1-V8), contra el mismo historial con un lote de 5 hojas. Mediana de 20 llamadas tras 5 de calentamiento.

**Estimación sin medir:**
- un lote de 1000 hojas pesa unos 66 kB, por `leafHashes`;
- construir una prueba son unos 1000 SHA-256.

Si el tiempo con 1000 hojas supera en más de 50 ms al de 5 hojas, se anota y se pregunta a Manuel antes de seguir. Se podría guardar en caché la lista de hojas por lote `ANCHORED`, porque ya no cambia (como `MAX_CACHED_BATCHES` en `TrackingIntegrityService.java:48`). No se añade esa caché sin medir.

## 7. Documentos y aviso a la web

- **`referencia-api-v1.md`** §8, notas fechadas:
  - G-4 (`parentAssetRef?`);
  - G-3 (`anchorStatus`, cuatro valores);
  - E1 (`eventHash`, `anchorProof` sin `leafCount`), con el algoritmo de E1.3 y el límite de E1.4: sin separación de dominio entre hojas y nodos, y el nodo impar duplicado.
- **`api-contract-matrix.md`:** P-R6C-4.
- **`ficha-R6-datos-estilo-v2.md` §9:** nota junto a D-R6-6 que remite a la Enmienda 1 (E4).
- **`estado-fase6.md`**, con la evidencia y la medición.
- **Aviso a la web y al móvil (F-C2)**, con el commit de `develop`:
  - `anchorStatus` tiene cuatro valores;
  - `eventHash` y `anchorProof`, con su algoritmo;
  - los vectores de §2.6;
  - qué prueba y qué no (E1.4).

## 8. Riesgos

- **R-R6C-1 a R-R6C-4:** los de la versión 1 (Anexo A, §6.1).
- **R-C-5, orden de los `streamId`.**
  - El índice usa el orden de `String.compareTo`, el mismo que `comparingByKey` (`IntegrityVerificationUseCase.java:70`) y `Collections.sort` en la reclamación (`MongoUnanchoredEventAdapter.java:36`).
  - Las hojas siguen el orden de Mongo (`:108`).
  - Coinciden para texto ASCII, como los identificadores actuales.
  - Si un día no coincidieran, la verificación existente ya fallaría igual. El efecto aquí sería `UNDER_REVIEW` sin prueba, nunca una prueba falsa, gracias al paso 4.2.
- **R-C-6, rangos contiguos.**
  - El cálculo supone que cada rango de `coverage` está completo en el lote. Es la misma suposición que `getEventHashesByCoverage` (consulta por rango, sin filtrar por `merkleBatchId`) y que `IntegrityVerificationUseCase.java:71-75`.
  - Si no se cumpliera, el paso 4.2 da `UNDER_REVIEW`, nunca una prueba falsa.
- **R-C-7, segundas preimágenes (E1.4).** No se cambia el algoritmo, porque invalidaría las raíces ancladas. Se documenta.
- **R-C-8, el hash prueba inclusión, no contenido (E1.4).** Un evento con el payload alterado y el `eventHash` intacto da una prueba válida. Lo detecta `…/integrity`, en el servidor, no esta prueba. La web lo dice (F-C2).

## 9. Puntos para la aprobación de Manuel

| # | Qué | Recomendación |
|---|---|---|
| **P-C-0** | Si B-C-1 tarda, ¿G-4 y G-3 primero y E1 después? | No: un solo PR, y se espera |
| **P-C-1** | El "puerto nuevo de solo lectura" de §0.4: el de P-R6C-1 ampliado, o además uno en `crypto` | El de P-R6C-1, sin un segundo puerto |
| **P-C-2** | Puerto nuevo en `core` para el hash (E1-V9), sin tocar `EventBatchMembershipPort` | Sí |
| **P-C-3** | Comparar el hash solo con `ANCHORED` | Sí |
| **P-C-4** | Paso sin evento → sin `eventHash` (E1.2 dice "Siempre") | Omitirlo, no inventarlo |
| **P-C-5** | Nombres y rutas marcados **[Plan]** | Aprobar o renombrar |
| **P-R6C-1 a P-R6C-5** | Los de la versión 1 | Siguen como estaban, con P-R6C-1 ampliado por §2.3 |
| **B-C-1** | Estado de la Enmienda 1 de la ficha | Pregunta para Carlos (igual que B-G9-1, #124) |

---

## Anexo A — Versión 1 (2026-10-24), íntegra

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
  Map<Long, TrackingAnchorStatus> anchorStatusBySequence(String streamId);
  ```

  - **`TrackingAnchorStatus`**, nombre propuesto en P-R6C-1: un `enum` de `contracts` con los cuatro valores de la tabla, `ANCHORED`, `IN_BATCH`, `PENDING` y `UNDER_REVIEW`. Así la respuesta no puede llevar un quinto valor sin cambiar el contrato.
  - `PublicTransitionDTO.anchorStatus` lo serializa por su nombre.
  - *Corrección de Manuel, 2026-10-24:* el borrador devolvía `Map<Long, String>`.

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
| `enum` de los cuatro valores, `TrackingAnchorStatus` (nombre en P-R6C-1) | `contracts` | Ficha §5, tabla de G-3 (C-4); corrección de Manuel del 2026-10-24 |
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
| 9c | **Cobertura exhaustiva de los dos `enum`:** el `switch` no tiene `default`, así que un estado nuevo no compila. Además, un test recorre `AnchorStatus.values()` y comprueba que cada valor tiene fila, y otro recorre `TrackingAnchorStatus.values()` y comprueba que cada uno de los cuatro sale de al menos un `AnchorStatus` (ninguno sobra) | `app` |
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
| **P-R6C-1** | Puerto de G-3: ¿en `contracts` (como `CampaignPublicSummaryPort`) o en `core.application.port.out`? Y los nombres: puerto, `enum` e implementación | **`contracts`**, como D-R6-1: el puerto no usa tipos de `core`. Devuelve un **`enum` de cuatro valores declarado en `contracts`**, no un `String` (corrección de Manuel, 2026-10-24). Nombres propuestos: `AssetAnchorStatusPort` y `TrackingAnchorStatus`, implementado en `app` por `AssetAnchorStatusProducer` |
| **P-R6C-2** | Añadir `sequence` a `AssetTransitionReadModel` (puerto de `core` que usa `api`): ¿hace falta enmienda? | **No**, por el criterio de P-R6A-2: es mostrar en una vista de lectura un dato que la proyección ya tiene y que no cambia. No sale por HTTP |
| **P-R6C-3** | Paso sin evento en el mapa (proyección antigua con `sequence = 0`, o sin pertenencia): ¿`PENDING`? | **Sí**: es "todavía no está en ningún lote" desde el punto de vista de lo que se puede comprobar. Nunca se inventa `ANCHORED` |
| **P-R6C-4** | Registro de la enmienda de G-4 en `api-contract-matrix.md`: la fila del seguimiento aún dice `GET /tracking/{trackingCode}` (§5, ruta antigua). ¿Nota fechada en esa fila o fila nueva con la ruta real `GET /donations/tracking`? | **Nota fechada en esa fila**, que cite la ruta real y D-R6-5, sin reescribirla |
| **P-R6C-5** | Límite de `findMembership` para el stream de un activo | El de `TrackingIntegrityService` (10 000), que en un solo activo nunca se alcanza |

**Nota a P-R6C-3 (Manuel, 2026-10-24): opción (b), sin cambio de modelo.**
- Manuel pidió distinguir "sin `sequence`" de "`sequence` 0", porque creía que una proyección antigua leída como 0 chocaría con la `sequence` 0 del génesis y tomaría el estado de su lote. **La premisa era errónea: el génesis tiene `sequence` 1.**
  - `MongoEventStoreAdapter.java:45` y `:63`: el primer `append` parte de `expectedVersion == 0` y da `newSequence = currentSequence + 1`.
  - `EventStreamGenesisReadPort.java:17`: `FIRST_SEQUENCE = 1`.
  - `EventBatchMembershipPort.java:12`: la `sequence` de la pertenencia es la del Event Store.
- Por tanto, ningún evento tiene `sequence` 0, y un paso leído como 0 no encuentra nada en el mapa y sale `PENDING`, como ya decía esta fila.
- Además, `AssetTransition.sequence` (`AssetHistoryProjectionDocument.java:39`) existe desde el primer commit (`4ab3a34`) y el handler lo escribe siempre (`DonationProjectionHandler.java:307`).
- `AssetTransitionReadModel.sequence` sigue siendo `long`.

## 7. Documentos y aviso a la web (ficha §8)

- **`referencia-api-v1.md`** §8, notas fechadas: `logistics[].parentAssetRef?` (G-4) y `history[].anchorStatus` con sus cuatro valores (G-3).
- **`api-contract-matrix.md`:** la enmienda de G-4 en la fila del seguimiento (P-R6C-4).
- **`estado-fase6.md`:** entrada nueva con la evidencia.
- **Aviso a la web y al móvil**, con el commit de `develop`: **`anchorStatus` tiene cuatro valores, no tres** (C-4). `paxfide-web/Documentos/prompts-agentes-estilo-v2.md` §3.4 está en otro repositorio.

## 8. Archivos previstos

- **`contracts`:** el puerto de G-3 y su `enum` de cuatro valores (P-R6C-1).
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
