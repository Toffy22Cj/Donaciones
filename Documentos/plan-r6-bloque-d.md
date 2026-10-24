# Plan — Ficha R-6, Bloque D: integridad de la organización (G-7) y actividad reciente (G-8)

**Estado: BORRADOR — espera "plan aprobado" (regla 3.4).** No autoriza código. **Tiene dos preguntas abiertas sobre la ficha (P-R6D-1 y P-R6D-2)** que, por estar CONGELADA, solo puede resolver Carlos.
**Fecha:** 2026-10-24. **Autor:** Manuel, con asistencia de Claude. **Base verificada:** `develop` `0b3dc3e`.
**Norma:** `Documentos/ficha-R6-datos-estilo-v2.md`, CONGELADA: §6 (Bloque D), §7 (tests 10 y 11), §8, §9 (D-R6-7, D-R6-8, D-R6-9, D-R6-10) y §10 (C-5, C-6 y C-7).
**Rama de implementación prevista:** `feat/r6-bloque-d`, **desde el `develop` posterior a la fusión de los Bloques B (#113) y C**: comparte archivos con los dos (§0.2). Es un PR solo para este bloque (D-R6-10).

---

## 0. Verificación de la ficha contra el código (`0b3dc3e`)

### 0.1 §6 de la ficha

| # | Afirmación de la ficha | Resultado | Evidencia |
|---|---|---|---|
| D-V1 | G-7: auth `ADMINISTRATOR` o `REPRESENTATIVE`, "como la predicción" (DD-43) | **CONFIRMADA** | La política de la predicción está en `app`: `CampaignPredictionUseCase.java:85-94` (`ADMINISTRATOR` o `REPRESENTATIVE` de la organización dueña; el resto, el mismo 403) |
| D-V2 | G-7: los streams de la organización son los de sus fondos y sus activos | **CONFIRMADA** | Fondos: `FundDirectoryPort.findFundIdsByOrganization` (`MongoFundDirectoryAdapter.java:26-29`, por la génesis). Activos: `AssetDirectoryPort.findAssetIdsByOrganization` (`MongoAssetDirectoryAdapter.java:23-30`), por la génesis `ASSET_REGISTERED` v2/v3; los v1 no llevan organización (`AssetDirectoryPort.java`, Javadoc) |
| D-V3 | G-7: `TrackingIntegrityService.MAX_EVENTS` es 10 000 (C-5) | **CONFIRMADA** | `app/.../integrity/TrackingIntegrityService.java:47`. Cuenta con `EventBatchMembershipPort.findMembership(streams, MAX_EVENTS)` (`:115`), que devuelve eventos, no recuentos (`MongoEventBatchMembershipAdapter.java:24-34`) |
| D-V4 | G-7: un lote mezcla eventos de varias organizaciones | **CONFIRMADA** | `MerkleBatch.coverage` es un mapa `streamId → SequenceRange` sin organización (`MerkleBatch.java:8-27`); `TrackingIntegrityService` ya cuenta solo los streams propios (`:115-119`) |
| D-V5 | G-7: módulo `app`, que compone `core` y `crypto` (C-7) | **CONFIRMADA** | `crypto` y `core` solo dependen de `contracts`; `app` los ve a los dos, como `TrackingIntegrityService` |
| D-V6 | G-8: `CAMPAIGN_CLOSED` desde la fecha de cierre que ya usa `CampaignTimelineQuery` (`closedAt`) | **CONFIRMADA por convocatoria, no por organización** | `CampaignTimelineQuery.timelineOf(campaignRef)` (`:28-35`) lee `ConvocatoriaAuditLogPort.findByCampaignRef` (`ConvocatoriaAuditLogPort.java:15`), el **único** método de lectura del puerto. `ConvocatoriaAuditEntry` no tiene organización (`ConvocatoriaAuditEntry.java:13-23`: `entryId`, `action`, `campaignRef`, `actorRef`, `targetRef`, `selfAssigned`, `commandId`, `occurredAt`, `details`). Ver **P-R6D-2** |
| D-V7 | G-8: `BATCH_ANCHORED` desde `MerkleBatch.anchoredAt`, "con cuántos eventos propios entraron" | **La fuente existe; el contrato no tiene dónde ponerlo** | `MerkleBatch.anchoredAt` existe (`MerkleBatch.java:20`). Pero la respuesta de la ficha (§6, G-8, línea 139) es `{type, occurredAt, subject: {kind: "ASSET"\|"CAMPAIGN"\|"FUND", ref}, quantity?, unitOfMeasure?, locationZone?}`: **ningún `kind` corresponde a un lote y ningún campo lleva el recuento** que pide la línea 142. Ver **P-R6D-1** |
| D-V8 | G-8: `locationZone` calculada igual que en el seguimiento | **CONFIRMADA** | `LocationReferenceService.resolveZone` (`core/.../service/LocationReferenceService.java:18`), la que usa `PublicDonationMapper` |
| D-V9 | G-8: cursor opaco y cifrado (DD-58) | **CONFIRMADA** | `DiscoveryCursorCodec` (`app/.../web/campaign/DiscoveryCursorCodec.java:22`), AES-256-GCM con IV aleatorio (DD-58); DD-69 ya reutilizó la clave con un **propósito propio** dentro del texto cifrado |
| D-V10 | G-8: hoy no existen eventos `CAMPAIGN_*` ni `BATCH_*` en el Event Store | **CONFIRMADA** | `EventPayloadRegistry` no registra ninguno; los tipos de convocatoria viven en el registro de auditoría de `convocatoria`, y los lotes en `merkle_batches` (`crypto`) |

### 0.2 Archivos que comparte con los Bloques B y C

- Con el **B (#113):**
  - `core/.../persistence/mongo/TraceabilityEventDocument.java` (B añadió `idx_fund_genesis_organization`; D propone el índice de activos, P-R6D-6);
  - `app/.../MongoIndexesAtStartupIntegrationTest.java` y `PublicRoutesInventoryIntegrationTest.java`;
  - G-8 lee los `FUNDS_CLEARED` y puede reutilizar `FundClearingEventsPort`, que es del B;
  - G-7 y G-8 usan `FundDirectoryPort`, acelerado por el índice del B;
  - `referencia-api-v1.md`, `api-contract-matrix.md` y `estado-fase6.md`.
- Con el **C:** la tabla de los cuatro valores de anclaje (G-3) y su `switch` exhaustivo. G-7 cuenta `underReviewEvents` con **la misma clasificación** y la reutiliza sin duplicarla (**P-R6D-5**).

**Por eso la implementación sale del `develop` posterior a la fusión del B y del C.**

## 1. Alcance

**Dentro**
- G-7 y G-8 (ficha §6).
- Los tests 10 y 11.
- `referencia-api-v1.md`, `api-contract-matrix.md` (filas de G-7 y G-8), `estado-fase6.md` y el aviso a la web.

**Fuera**
- `lastVerification` (C-6, D-R6-7).
- Crear eventos `CAMPAIGN_*` o `BATCH_*` (ficha §6).
- Los bloques B y C.
- **Lo que dependa de P-R6D-1 y P-R6D-2 hasta que Carlos responda.**

## 2. Decisiones del plan

### 2.1 G-7 · `GET /api/v1/organizations/{organizationId}/integrity`

- **Módulo:** `app` (D-V5). El controlador va en `app.web.organization` y la consulta en `app.application.integrity`, junto a `TrackingIntegrityService`.
- **Autorización:** `ADMINISTRATOR` o `REPRESENTATIVE` de la organización, con el mismo patrón que `CampaignPredictionUseCase.java:85-94`; el resto, el mismo 403. `no-store`.
- **Streams:** fondos (`FundDirectoryPort`) y activos (`AssetDirectoryPort`), con un límite alto por directorio (10 000, como G-6 en el Bloque B). Si se alcanza, `truncated: true` (C-5).
- **Recuentos por agregación**, no con `findMembership` y su tope:
  - un puerto de salida nuevo en `core` devuelve `{merkleBatchId → número de eventos propios}` con una agregación `$match {streamId $in} → $group por merkleBatchId` (medido: §6.1);
  - `null` cuenta como `unanchoredEvents`;
  - cada lote distinto se lee con `MerkleBatchRepositoryPort.findByBatchId` y se clasifica con la tabla del Bloque C (P-R6D-5).
- **Respuesta (ficha §6):** `200 {unanchoredEvents, inPendingBatches, underReviewEvents, lastAnchoredAt?, truncated, checkedAt}`.
  - `inPendingBatches` = eventos en lotes `IN_BATCH`.
  - `underReviewEvents` = eventos en lotes `UNDER_REVIEW`.
  - `lastAnchoredAt` = el mayor `anchoredAt` de los lotes `ANCHORED` con eventos propios.
  - Los eventos en lotes `RELEASED` cuentan como `unanchoredEvents`, como `PENDING` en G-3.
- **Nunca** `batchId`, `merkleRoot`, `transactionHash` ni datos de otras organizaciones: solo se cuentan los streams propios.

### 2.2 G-8 · `GET /api/v1/organizations/{organizationId}/activity?cursor=`

- **Módulo:** `app` (compone `core`, `convocatoria` y `crypto`, C-7).
- **Autorización:** `ADMINISTRATOR`, `REPRESENTATIVE` o `EMPLOYEE` de la organización (D-R6-8). `no-store`.
- **Fuentes y orden total (C-7):** `(instante de listado descendente, tipo de fuente, id estable)`.

| Fuente | Instante de listado | Id estable | Disponible |
|---|---|---|---|
| Eventos del Event Store de los streams de la organización: `ASSET_REGISTERED`, `ASSET_DISPATCHED`, `ASSET_RECEIVED`, `ASSET_DELIVERED`, `ASSET_SPLIT` y `FUNDS_CLEARED` (D-R6-9) | `recordedAt` (solo para listar) | `eventId` | **Sí** |
| `CAMPAIGN_CLOSED` | `closedAt` (`CampaignTimelineQuery`) | `campaignRef` | **Depende de P-R6D-2** |
| `BATCH_ANCHORED` | `anchoredAt` (`MerkleBatch`) | `batchId` (solo dentro del cursor cifrado) | **Depende de P-R6D-1** |

- **De dónde salen los lotes de `BATCH_ANCHORED`** (aclaración de Manuel, 2026-10-24):
  1. Se parte de la **misma agregación de G-7**: `$match {streamId $in <streams de la organización>}` y `$group` por `merkleBatchId`, que da cada lote con eventos propios y su recuento.
  2. Cada `merkleBatchId` distinto se lee con `MerkleBatchRepositoryPort.findByBatchId` (`MerkleBatchRepositoryPort.java:12`).
  3. Entran solo los lotes `ANCHORED`. El instante es su `anchoredAt` (`MerkleBatch.java:20`), y el recuento de eventos propios es el de la agregación.
  - **Nunca se consulta `MerkleBatch.coverage`:** no se buscan lotes por sus streams ni se leen los streams de otras organizaciones que el lote mezcla (D-V4).
  - El `batchId` solo va dentro del cursor cifrado, como id estable; nunca en la respuesta (G-7).
- **Página de 20.** Se piden 21 a cada fuente desde la clave del cursor, se mezclan por el orden total y se devuelven 20, con el cursor del ítem 20.
- **Cursor:** AES-256-GCM con la clave de DD-58 y un **propósito propio** dentro del texto cifrado, como DD-69. Guarda la clave completa `(instante, tipo de fuente, id estable)`; así un empate de instantes entre fuentes no repite ni salta ítems (test 11). Cursor inválido o de otro propósito → 400.
- **Ítems:** `{type, occurredAt, subject: {kind, ref}, quantity?, unitOfMeasure?, locationZone?}`.
  - **Activos:** `subject = {kind: "ASSET", ref: assetId}`. El personal ya ve el `assetId` en las vistas operacionales. `quantity` y `unitOfMeasure` salen del payload, y `locationZone` de `resolveZone` sobre la ubicación del payload.
  - **`FUNDS_CLEARED`:** `subject = {kind: "FUND", ref: fundId}`. La organización ya ve el `fundId` en `GET /funds`. **Sin importe ni donante** (ficha §6).
  - **`occurredAt`** es el del hecho; el orden usa `recordedAt` (ficha §6).
- **Nunca** el actor, `donorRef`, `beneficiaryRef`, custodios, ubicación exacta ni importes de una donación (aserción negativa, test 11).

### 2.3 Clases nuevas y por qué (regla 2.1)

| Clase o cambio | Módulo | Fuente |
|---|---|---|
| Consulta y controlador de G-7 (nombres en P-R6D-7) | `app` | Ficha §6 G-7; C-7 |
| Puerto de salida de recuento por lote, con su adaptador Mongo (nombre en P-R6D-7) | `core` | C-5: contar sin tope por agregación |
| Consulta y controlador de G-8 (nombres en P-R6D-7) | `app` | Ficha §6 G-8; C-7 |
| Puerto de salida de eventos de actividad por streams, con su adaptador Mongo (nombre en P-R6D-7) | `core` | §2.2: una consulta por página para la fuente del Event Store |
| Códec del cursor de actividad: reutiliza `DiscoveryCursorCodec` con propósito propio, o una clase hermana (P-R6D-7) | `app` | DD-58, DD-69 |
| Lo que resulte de P-R6D-2 (lectura de cierres por organización) | `convocatoria` | D-V6 |
| Índice parcial del directorio de activos (P-R6D-6) | `core` | §6.1 |

**No hay excepciones nuevas.** Cursor inválido → `InvalidRequestFieldException("cursor")`, como el descubrimiento.

## 3. Tests — rojos primero (ficha §7, tests 10 y 11)

| # | Test | Dónde |
|---|---|---|
| 10a | G-7 solo cuenta eventos propios aunque el lote mezcle organizaciones: un lote con eventos de A y de B da a A solo los suyos | `app` (Mongo real, lotes construidos a mano) |
| 10b | `underReviewEvents` cuenta los eventos en lotes `FAILED`, `COLLECTING_FAILED` y `ANCHOR_MISMATCH`; `inPendingBatches` los de `IN_BATCH`; los de `RELEASED` y los sin lote son `unanchoredEvents` | `app` |
| 10c | `lastAnchoredAt` es el mayor `anchoredAt` de los lotes `ANCHORED` propios | `app` |
| 10d | Con más fondos o activos que el límite, `truncated: true` (directorios espiados, como G-6 en el Bloque B) | `app` |
| 10e | Otra organización o un `EMPLOYEE` → el mismo 403; la respuesta no contiene `batchId`, `merkleRoot` ni `transactionHash` (aserción negativa) | `app` (HTTP) |
| 11a | G-8: de lo más reciente a lo más antiguo, páginas de 20, sin repeticiones ni saltos al recorrer todas las páginas | `app` |
| 11b | **Empate de instantes entre fuentes distintas** (mismo instante en un evento y en un cierre o un anclaje) que cae en el borde de página: ni se repite ni se salta | `app` (con las fuentes de P-R6D-1 y P-R6D-2 cuando estén resueltas; hasta entonces, empate entre dos eventos del Event Store) |
| 11c | Cursor inválido, manipulado o de otro propósito (por ejemplo, uno del descubrimiento) → 400 | `app` (HTTP) |
| 11d | Ningún ítem de otra organización | `app` |
| 11e | **Aserción negativa:** ningún campo de actor, donante, beneficiario, custodio, ubicación exacta ni importe individual | `app` (HTTP) |
| 11f | `ADMINISTRATOR`, `REPRESENTATIVE` y `EMPLOYEE` → 200; otra organización → 403 | `app` (HTTP) |
| — | Inventario de rutas: las dos rutas nuevas en `PROTECTED` | `app` |
| — | Común: `scripts/ci-local.sh` completo en verde, con el resumen literal de Surefire | — |

## 4. Mutaciones (`scripts/mutaciones.py`)

1. G-7: contar todos los eventos del lote, no solo los propios → muere 10a.
2. G-7: `ANCHOR_MISMATCH` fuera de `underReviewEvents` → muere 10b.
3. G-7: `RELEASED` como `inPendingBatches` → muere 10b.
4. G-7: sin `truncated` al alcanzar el límite → muere 10d.
5. G-7: dejar entrar a un `EMPLOYEE` → muere 10e.
6. G-8: cursor sin el tipo de fuente ni el id (solo el instante) → muere 11b.
7. G-8: orden ascendente → muere 11a.
8. G-8: no rechazar un cursor de otro propósito → muere 11c.
9. G-8: copiar el actor o el `donorRef` del evento al ítem → muere 11e.

## 5. Orden de trabajo (tras "plan aprobado", las respuestas de Carlos a P-R6D-1 y P-R6D-2, y la fusión de B y C)

1. Rama `feat/r6-bloque-d` desde ese `develop`, con el hash confirmado.
2. Tests rojos, con su salida literal.
3. Verde por capas, con el módulo completo en cada una:
   1. `core` (puertos de salida y adaptadores; índice si P-R6D-6);
   2. `convocatoria` (según P-R6D-2);
   3. `app` G-7;
   4. `app` G-8;
   5. HTTP e inventario.
4. Mutaciones, documentos y `ci-local.sh` en un clon limpio, revisando antes que haya al menos 4 GB libres. Después, el PR.

## 6. Riesgos y preguntas

### 6.1 Volumen (medido)

Medido el 2026-10-24 en Mongo **6.0.28** (`mongo:6.0`), con los índices reales de `event_store` más el del Bloque B.
- 259 000 eventos: 1000 organizaciones con 25 fondos y 50 activos cada una, más una organización con 500 fondos y 2000 activos (9000 eventos propios en 2500 streams).
- El 20 % de los eventos, sin lote.

| Consulta | Plan ganador | Claves | Documentos | Devueltos | ms |
|---|---|---|---|---|---|
| Directorio de activos (`MongoAssetDirectoryAdapter`), índices de hoy | `SORT ← FETCH ← IXSCAN(idx_event_type_campaign)` | **52 000** | **52 000** | 2000 | **176** |
| Directorio de activos, con índice parcial candidato `{payload.organizationRef, recordedAt, streamId}` y `{sequence: 1, aggregateType: "PhysicalAsset"}` | `FETCH ← IXSCAN` | 2000 | 2000 | 2000 | 15 |
| G-7: agregación por `merkleBatchId` de los 2500 streams | — | — | — | 17 grupos | 69 |
| G-8: una página de 21 eventos (`streamId $in`, `eventType $in`, orden `recordedAt` descendente), índices de hoy | `SORT ← FETCH ← IXSCAN(idx_stream_sequence)` | **9000** | **9000** | 21 | **85** |
| G-8: lo mismo, con un índice candidato `{streamId: 1, recordedAt: -1}` | el planificador **no lo elige** (con 2500 valores en `$in` no hace mezcla ordenada) | 9000 | 9000 | 21 | 123 |

- **R-R6D-1, el directorio de activos recorre todas las génesis de activos de la base.** Ya pasa hoy en `GET /physical-assets` de la organización. Un índice parcial, como el del Bloque B para fondos, lo resuelve (P-R6D-6).
- **R-R6D-2, cada página de G-8 ordena en memoria todos los eventos de la organización.** El coste crece con sus eventos (9000 → 85 ms) y se repite en cada página, porque el filtro del cursor no lo evita. Ningún índice simple lo arregla, porque los eventos no llevan la organización (solo la génesis). Con decenas de miles de eventos por organización haría falta una proyección de actividad, fuera de este corte (**P-R6D-4**).
- **R-R6D-3, G-7 lee un lote por cada `merkleBatchId` distinto** (17 en la medición). `MerkleBatchRepositoryPort` no tiene lectura en bloque (`findByBatchId`, `MerkleBatchRepositoryPort.java:12`); añadirla sería un cambio en `crypto` (P-R6D-7).
- **R-R6D-4, los activos v1 y los fondos 1.0 sin organización** quedan fuera de G-7 y G-8, como de los directorios (Bloque B, P-R6B-1).

### 6.2 Preguntas abiertas sobre la ficha (las resuelve Carlos; este plan no las resuelve)

| # | Dónde | Qué no cuadra | Opciones (sin recomendación: la ficha está CONGELADA) | Propuesta de Manuel, pendiente de Carlos (2026-10-24) |
|---|---|---|---|---|
| **P-R6D-1** | `ficha-R6-datos-estilo-v2.md` §6, G-8, **línea 142** (`BATCH_ANCHORED` "con cuántos eventos propios entraron") frente a la **línea 139** (respuesta `subject: {kind: "ASSET"\|"CAMPAIGN"\|"FUND", ref}, quantity?, unitOfMeasure?, locationZone?`) | `BATCH_ANCHORED` **no tiene `subject.kind`**, porque ningún valor corresponde a un lote, y **no hay campo para el recuento** de eventos propios. Además, G-7 (§6, mismo apartado) prohíbe exponer `batchId`, que sería el `ref` natural de un lote | (a) añadir `kind: "BATCH"` sin `ref` y un campo de recuento (por ejemplo, `eventCount?`); (b) `BATCH_ANCHORED` sin `subject` y con el recuento en otro campo; (c) quitar `BATCH_ANCHORED` de este corte | **(b)**: `BATCH_ANCHORED` **sin `subject`** y con **`eventCount`**, el número de eventos propios que entraron en el lote (de la agregación de §2.2). Sin `batchId` ni ningún otro identificador del lote. **Propuesta de Manuel, pendiente de Carlos** |
| **P-R6D-2** | `convocatoria/.../port/out/ConvocatoriaAuditLogPort.java:15` (solo `findByCampaignRef`) y `convocatoria/.../audit/ConvocatoriaAuditEntry.java:13-23` (sin organización); ficha §6, G-8, línea 142 (`CAMPAIGN_CLOSED` "desde la fecha de cierre que ya usa `CampaignTimelineQuery`") | El registro de auditoría de `convocatoria` **no se puede consultar por organización**. Para listar los cierres de una organización habría que enumerar sus convocatorias (`ConvocatoriaRepositoryPort.findByOrganizationRef`, `:29`) y leer el registro de cada una (`CampaignTimelineQuery.timelineOf`, `:28-35`): una lectura por convocatoria y por página | (a) una consulta nueva de `convocatoria` que enumere las convocatorias de la organización y lea sus cierres (N lecturas, sin cambiar puertos existentes); (b) un método nuevo en `ConvocatoriaAuditLogPort` por `campaignRef $in` (cambio de un puerto de `convocatoria`, regla 3.5); (c) quitar `CAMPAIGN_CLOSED` de este corte | **(a), limitada a las convocatorias cerradas de la organización.** Se listan las de la organización con `findByOrganizationRef` (`ConvocatoriaRepositoryPort.java:29`) y se queda con las de `status = CLOSED`, y solo de esas se lee el registro de auditoría con `CampaignTimelineQuery.timelineOf` (`:28-35`). **Propuesta de Manuel, pendiente de Carlos** (ver la nota de abajo) |

**Nota a la propuesta de P-R6D-2: con qué se sabe hoy que una convocatoria está cerrada** (comprobado en `0b3dc3e`, 2026-10-24)
- **El estado:** `ConvocatoriaStatus` tiene solo `OPEN` y `CLOSED` (`ConvocatoriaStatus.java:6-9`).
  - Pasa a `CLOSED` con `Convocatoria.close` (`Convocatoria.java:139-144`) y la escritura condicional `closeIfOpen` (`ConvocatoriaRepositoryPort.java:26`).
  - Se guarda en `ConvocatoriaDocument.status` (`ConvocatoriaDocument.java:29`).
- **La fecha del cierre no está en la convocatoria.** Sale de la entrada `CONVOCATORIA_CLOSED` del registro de auditoría, la primera por `occurredAt` (`CampaignTimelineQuery.java:30-31`).
- **`findByOrganizationRef(organizationRef, limit)` no filtra por estado** (`ConvocatoriaRepositoryPort.java:29`). El filtro `CLOSED` se aplica al leer, o con un método nuevo del adaptador. Si es un método nuevo del puerto, es un cambio de un puerto de `convocatoria`: lo decide Carlos (regla 3.5).
- **Coste:** una lectura del registro por convocatoria cerrada de la organización, no por cada convocatoria.

### 6.3 Preguntas para la aprobación del plan

| # | Pregunta | Recomendación |
|---|---|---|
| **P-R6D-3** | G-7: ¿contar por agregación sin tope (C-5) o con el tope de 10 000 eventos y `truncated`? | **Agregación**: 69 ms para 9000 eventos en 2500 streams (§6.1). `truncated` queda solo para el límite de los directorios (10 000 por tipo) |
| **P-R6D-4** | G-8: ¿se acepta el coste por página (orden en memoria de los eventos de la organización, R-R6D-2) en este corte? ¿Con qué umbral? | **Sí en este corte**, con el umbral registrado: si una organización pasa de unos 50 000 eventos, una proyección de actividad. Sin ventana de fechas, porque la ficha no la pide |
| **P-R6D-5** | La clasificación de los cuatro valores de anclaje: ¿una sola clase compartida por G-3 (Bloque C) y G-7? | **Sí**: la del Bloque C, en `app`, y G-7 la reutiliza. Un estado nuevo de `AnchorStatus` rompe la compilación en un solo sitio |
| **P-R6D-6** | ¿Índice parcial para el directorio de activos (R-R6D-1), en un commit propio, como P-R6B-7? | **Sí**, con `explain()` antes y después en el PR |
| **P-R6D-7** | Nombres y lectura de lotes. ¿Se añade `findByBatchIds` a `MerkleBatchRepositoryPort` (cambio en `crypto`), o un `findByBatchId` por lote? | **Un `findByBatchId` por lote** en este corte (17 en la medición), sin tocar `crypto`. Nombres propuestos: `OrganizationIntegrityQuery` y `OrganizationIntegrityController` (G-7); `OrganizationActivityQuery` y `OrganizationActivityController` (G-8); `EventBatchCountPort` y `MongoEventBatchCountAdapter`; `ActivityEventsPort` y `MongoActivityEventsAdapter`; el cursor, con `DiscoveryCursorCodec` y un propósito propio |

## 7. Documentos y aviso a la web (ficha §8)

- **`referencia-api-v1.md`:** filas nuevas para `GET /organizations/{organizationId}/integrity` y `GET /organizations/{organizationId}/activity`.
- **`api-contract-matrix.md`:** filas nuevas de G-7 y G-8.
- **`estado-fase6.md`:** entrada nueva con la evidencia.
- **Si Carlos cambia la ficha por P-R6D-1 o P-R6D-2,** ese cambio va en su propio PR, antes de este.
- **Aviso a la web y al móvil,** con el commit de `develop`. `paxfide-web/Documentos/prompts-agentes-estilo-v2.md` §3.4 está en otro repositorio.

## 8. Archivos previstos

- **`core`:**
  - `application/port/out/` (los dos puertos de salida);
  - `infrastructure/persistence/mongo/` (sus adaptadores y, según P-R6D-6, el índice en `TraceabilityEventDocument.java`, compartido con el Bloque B);
  - sus tests.
- **`convocatoria`:** según P-R6D-2.
- **`app`:**
  - `application/integrity/` (G-7) y `application/activity/` (G-8);
  - `web/organization/` (los dos controladores);
  - `PublicRoutesInventoryIntegrationTest` y `MongoIndexesAtStartupIntegrationTest`, compartidos con el Bloque B;
  - tests.
- **Documentos:** `referencia-api-v1.md`, `api-contract-matrix.md`, `estado-fase6.md` y `Documentos/evidencia-ci/`.
