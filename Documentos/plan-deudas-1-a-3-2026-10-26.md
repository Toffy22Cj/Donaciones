# Plan — Deudas 1 a 3: fotos retiradas y la deduplicación, DT-B-1 (`recordedAt`) y P-E2-2 (G-8 descendente) (2026-10-26)

**Estado: PROPUESTO.** Espera un "plan aprobado" de Manuel que se pueda citar: un comentario de `buelvasmanuel` en el PR de este plan. Un solo mensaje aprueba las tres secciones, sus nombres **[Plan]** y la Enmienda 3 de la ficha R-6 (`ficha-R6-enmienda-3.md`). Si Manuel responde a un punto P-…, su respuesta sustituye a la recomendación.

**Qué entra lo decidió Manuel** el 2026-10-26, por delegación de Carlos (`decisiones-delegadas-2026-10.md`, nota del 2026-10-26, "comunicado por Manuel"), en D-1, D-2 y D-3. Este plan fija el **cómo**. Google (R-6 de ADR-050) no entra.

**Base verificada:** `develop` `269e047` (merge de #152), 2026-10-26. Cada ruta:línea de este plan se abrió sobre esa base.

---

## 0. Común a las tres deudas

- **Un PR por deuda, hacia `develop`:**
  - P1 `fix/media-evidencia-retirada` **[Plan]** (deuda 1), desde `develop` cuando se fusione este PR;
  - P2 `fix/event-store-recorded-at` **[Plan]** (DT-B-1), desde `develop`;
  - P3 `feat/r6-g8-descendente` **[Plan]** (P-E2-2). Toca el mismo orden que DT-B-1: si P2 no está fusionado, sale de su rama y el cuerpo dice "depende de #N".
- **Ciclo de cada PR:**
  - rojo primero (aserción o compilación), con la salida literal;
  - verde por capas con el módulo completo (`mvn test -pl <módulo> -am`);
  - mutaciones con `scripts/mutaciones.py` en un clon limpio, con la mutación 0 de línea base;
  - documentos (`referencia-api-v1.md` si cambia una respuesta o un error; `estado-fase6.md` en 0.66 a 0.69);
  - `free -g` y `scripts/ci-local.sh` en un clon limpio del commit exacto.
- **Cada PR** lleva su "Aviso a la web y al móvil". Si no cambia nada visible, lo dice.
- **Ningún test existente se desactiva ni se borra.** Si un test existente fija el comportamiento que la decisión cambia, el plan lo nombra. Se adapta en el mismo PR y se dice en el cuerpo.

---

## 1. Deuda 1 — Fotos retiradas y la deduplicación (D-1)

### 1.1 Hechos verificados

- **La deduplicación no mira `deletedAt`.**
  - `MediaUploadService.java:65-71`: para `DELIVERY_EVIDENCE`, si existe un metadato con la misma `(organizationId, purpose, sha256)`, devuelve ese metadato tal cual, sin `put` ni `insert`, aunque tenga `deletedAt`.
  - El controlador responde `201 {evidenceRef}` con su huella (`DeliveryEvidenceController.java:60-62`).
- **Guardarla como foto nueva tampoco funcionaría.**
  - El índice único parcial `uq_evidence_sha256` cubre `(organizationId, purpose, sha256)` de toda evidencia, también de las retiradas. Solo filtra por `purpose` (`MediaObjectDocument.java:24-25`).
  - Un `insert` nuevo chocaría con él. El camino de la carrera (`MediaUploadService.java:82-87`) recogería el metadato retirado, que es el mismo efecto.
- **Lo que pasa hoy después de la subida.**
  - La entrega ya rechaza esa evidencia: `bindEvidence` exige `deletedAt` nulo (`MongoMediaObjectStore.java:78`) y, si no, da `NOT_FOUND`, que es un 400 `EvidenceNotFound` (`MediaApiErrorMappings.java:52`).
  - Es decir: hoy la subida responde 201 y la entrega 400, así que el cliente recibe un `evidenceRef` que no sirve.
- **Quién pone `deletedAt` en una evidencia.**
  - Solo la retirada de la plataforma por F7 (`PlatformMediaRemovalController.java:127`, `markDeleted`).
  - El logo y la portada también lo reciben al reemplazarlos o quitarlos (F10): `OrganizationLogoService.java:84` y `:121`, y `CampaignCoverService.java:74` y `:104`, con `reason` `REPLACED` o `REMOVED` (`OrganizationLogoService.java:27-28`).
  - Una evidencia nunca se reemplaza (ADR-051 F10, `ADR-051-fotos-y-almacenamiento.md:193`).
- **El 410 que ya existe.**
  - `EvidenceRemovedException` (`EvidenceRemovedException.java:3-8`) está mapeada a `410` con el título por defecto (`MediaApiErrorMappings.java:48`). El título es el nombre de la clase sin "Exception", `EvidenceRemoved` (`ApiExceptionHandler.java:92` y `:115-119`).
  - Su detalle es fijo: "The resource was removed." (`ApiExceptionHandler.java:60-61`).
  - Hoy solo lo lanza la lectura (`DeliveryEvidenceReadController.java:92-93`; ADR-051 F6, `ADR-051-fotos-y-almacenamiento.md:126`).
- **El logo y la portada no deduplican.**
  - `MediaUploadService.java:65` solo deduplica la evidencia. El índice es parcial y deja repetir bytes en el logo y la portada (`MediaObjectDocument.java:18-20`). Lo fija `MediaUploadServiceTest.java:237` (`deduplication_isOnlyForEvidence_aRepeatedLogoIsStoredAgain`).
  - **No tienen este fallo:** nunca reutilizan un metadato retirado.
  - **Tienen otro parecido:** volver a subir los bytes de un logo o una portada retirados por F7 crea un objeto nuevo y vivo (F7 permite retirarlos, `ADR-051-fotos-y-almacenamiento.md:140`). Es F7 saltado por otro camino. Se trata en P-DB1-3.
- **Tests existentes que fijan el comportamiento de hoy.**
  - `MediaUploadServiceTest.java:225` (`sameEvidenceBytesTwice_returnsTheSameRef_withoutASecondPut`) y `:251` (la carrera) usan metadatos **vivos**.
  - Siguen valiendo y no se cambian.

### 1.2 Qué se hace

- `MediaUploadService.store`, para `DELIVERY_EVIDENCE`:
  - si el metadato existente tiene `deletedAt`, lanza `EvidenceRemovedException` **antes de** `storage.put`, sin bytes nuevos ni `insert`. Si está vivo, sigue como hoy;
  - lo mismo en el camino de la carrera (`:85-87`): si el ganador que se lee tiene `deletedAt`, la misma excepción (P-DB1-2).
- **Ningún nombre nuevo:** se reutiliza `EvidenceRemovedException` (P-DB1-1).
- Ningún puerto cambia: `MediaObject.deletedAt()` ya llega por `findByOrganizationIdAndPurposeAndSha256` (`MongoMediaObjectStore.java:89-92`).
- **Otra organización** que suba los mismos bytes no cambia: la clave incluye `organizationId`, así que es su propia evidencia.
- `referencia-api-v1.md`: nota fechada en la ruta de subida (`referencia-api-v1.md:228`), que gana el 410.

### 1.3 Tests (rojo esperado)

1. `MediaUploadServiceTest`, la evidencia existente tiene `deletedAt` → `EvidenceRemovedException`, sin `put` y sin `insert`. **Rojo:** aserción; hoy devuelve el `StoredMedia` retirado.
2. `MediaUploadServiceTest`, carrera: `insert` lanza `DuplicateEvidenceException` y el ganador tiene `deletedAt` → `EvidenceRemovedException`. **Rojo:** aserción.
3. HTTP, en `app`, con Mongo real y el almacenamiento en memoria:
   - se sube una evidencia, la plataforma la retira y se vuelven a subir los mismos bytes;
   - la respuesta es `410`, con el cuerpo exacto `{type, title: "EvidenceRemoved", status: 410, detail}`;
   - `media_objects` no gana documentos y el almacenamiento no gana bytes.
   - **Rojo:** "expected: 410 but was: 201".
4. Control, en el mismo test: otra foto de la misma organización sigue dando 201. La evidencia viva repetida sigue dando el mismo `evidenceRef`; ya lo cubre el test `:225`, que no cambia.
5. Los tests del logo y la portada (`:237` y los HTTP de T2 y T3) siguen en verde sin cambios.

### 1.4 Mutaciones (`app`)

| # | Mutación | La mata |
|---|---|---|
| 0 | línea base | — (VIVA) |
| 1 | no mirar `deletedAt` en el camino normal | tests 1 y 3 |
| 2 | no mirarlo en la carrera | test 2 |
| 3 | `put` antes de la comprobación (deja bytes huérfanos) | tests 1 y 3 (sin `put`, sin bytes nuevos) |
| 4 | lanzar también con un metadato vivo | test `:225` y control 4 |
| 5 | comprobarlo también para el logo | test `:237` |

### 1.5 Puntos para Manuel

| # | Pregunta | Recomendación |
|---|---|---|
| **P-DB1-1** | ¿Qué código y qué nombre tiene el rechazo? | **410 `EvidenceRemoved`, con la excepción que ya existe.** Encaja: la misma evidencia, identificada por su huella, fue retirada por F7, y ADR-051 ya le da ese código (`:126`). El cliente ya lo conoce desde T5, no hace falta un nombre nuevo y el detalle fijo sirve tal cual. La alternativa es un 409 con un título nuevo **[Plan, solo si se elige]** `EvidenceRemovedConflict`, más cercano a un POST que choca con el estado. Pero añade un segundo nombre para el mismo hecho. |
| **P-DB1-2** | ¿Se comprueba también en el camino de la carrera (`MediaUploadService.java:85-87`)? | **Sí.** Si no, un 201 con una evidencia retirada sigue siendo posible en una carrera con la retirada. |
| **P-DB1-3** | El logo y la portada retirados por F7 se pueden volver a subir con los mismos bytes como un objeto nuevo (§1.1). ¿Entra? | **No en este corte**, y se registra como deuda. Habría que distinguir F7 de F10: las dos ponen `deletedAt`, y `reason` es texto libre en F7 y una constante en F10. También haría falta una consulta nueva, porque `findByOrganizationIdAndPurposeAndSha256` devuelve un `Optional` y el logo y la portada pueden repetir huella (`MediaObjectDocument.java:19-20`). Además, F10 permite expresamente repetir bytes. Es una decisión de producto aparte. |

### 1.6 Aviso a la web y al móvil (anticipo)

- `POST /api/v1/organizations/{organizationId}/delivery-evidence` gana **410 `EvidenceRemoved`**: esos mismos bytes fueron retirados por la plataforma (F7).
- El móvil debe pedir otra foto, no reintentar.
- Hoy esa subida daba 201 y la entrega fallaba con 400. Ahora falla antes.

---

## 2. Deuda 2 — DT-B-1: `recordedAt` con precisión fija (D-2)

### 2.1 Hechos verificados

- **Dónde se escribe.**
  - `MongoEventStoreAdapter.java:65` toma `Instant.now()`, y `:96` guarda `recordedAt.toString()`.
  - `Instant.toString()` escribe 0, 3, 6 o 9 decimales según el valor (§0.53). El campo es un `String` (`TraceabilityEventDocument.java:42`).
- **`recordedAt` entra en el hash del evento.**
  - `MongoEventStoreAdapter.java:68-82` arma el mapa canónico con `recordedAt` y lo hashea.
  - `EventCanonicalMapper.java:53` lo pone como `recordedAt.toString()`.
  - ADR-039 lo dice: "`recordedAt` entra en el hash" (`ADR-039-enmienda-1-blockchain.md:78`).
  - **Consecuencia:** el texto de los eventos ya guardados no puede cambiar.
- **El verificador no lee el texto guardado tal cual.**
  - `RecomputingEventIntegrityChecker.java:105-107` hace `Instant.parse(e.recordedAt())` y vuelve a pasar por `toCanonicalMap`, es decir, por `Instant.toString()` (`EventCanonicalMapper.java:53`).
  - El corte de la forma heredada también lo parsea (`:93-98`).
  - **Consecuencia:** si el texto guardado cambia de formato sin cambiar el instante, el hash recalculado es el mismo, porque se rehace con `Instant.toString()` del mismo instante.
- **No entra en el lote Merkle ni en el ancla.**
  - Las hojas son los `eventHash`: `BlockchainAnchorProducer.java:132` y `:186`, con `getEventHashesByCoverage`.
  - Se ordenan por `streamId` y `sequence`, no por `recordedAt` (`MongoUnanchoredEventAdapter.java:106-108`).
  - El `recordedAt` solo llega al Merkle a través del `eventHash`, que no cambia (punto anterior).
- **Dónde se ordena por el texto.**
  - Los directorios de fondos y de activos: `MongoFundDirectoryAdapter.java:30`, `MongoAssetDirectoryAdapter.java:27` y los índices `TraceabilityEventDocument.java:20-26`.
  - Los eventos de G-8: `MongoActivityEventsAdapter.java:40-45`.
  - En memoria, G-8 compara también `closedAt.toString()` y `anchoredAt.toString()` (`OrganizationActivityQuery.java:161` y `:181`) con el mismo orden de texto (`:52-53`).
- **Nadie más escribe eventos.** El único `.recordedAt(` del código principal es `MongoEventStoreAdapter.java:96`.
- **Los tests ya tienen eventos "viejos".** `IntegrityRecomputationIntegrationTest.java:156` inserta eventos crudos con `Instant.toString()`, que son exactamente los datos ya guardados.
- **Contradicción en la cita (se anota, no se resuelve).**
  - D-2 cita ADR-025 para "el Event Store es de solo escritura". ADR-025 habla del Audit Log de `identity` (`ADR-025-persistencia-identidad.md:19-20`, "El Audit Log será **append-only**").
  - El Event Store inmutable está en `documento-maestro-proyecto.md:18` y `:499`.
  - La restricción vale igual: los eventos guardados no se reescriben.

### 2.2 Qué se hace

- **`core`: una clase nueva de formato del texto de listado.**
  - Es `RecordedAtText` **[Plan]** (`com.traceability.core.application.event`), con `static String of(Instant)` **[Plan]**.
  - Formato fijo `uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'`, siempre con 9 decimales, en UTC.
  - Es sin pérdida: `Instant.parse(of(t)).equals(t)`. Con años de 4 cifras, el orden de texto es el cronológico.
- **`MongoEventStoreAdapter.java:96`** guarda `RecordedAtText.of(recordedAt)`.
- **El hash no cambia.** El mapa canónico sigue con `recordedAt.toString()` (`EventCanonicalMapper.java:53`, sin tocar). El verificador parsea el texto, sea del formato que sea, y rehace el mismo `toString()`. La canonicalización, el hash, el Merkle y el ancla no cambian (ADR-051 F11 y ADR-039, intactos).
- **G-8:** las claves de `CAMPAIGN_CLOSED` y `BATCH_ANCHORED` se forman con `RecordedAtText.of` (`OrganizationActivityQuery.java:161` y `:181`). Así compiten en orden cronológico con los eventos nuevos.
  - La clave de los eventos sigue siendo el texto guardado (`:154`), igual que lo ordena Mongo.
  - Todas las comparaciones siguen siendo de texto. El orden total y el cursor siguen siendo coherentes con Mongo: nunca repiten ni saltan.
- **Los datos viejos se toleran, no se migran** (P-DT1-1):
  - los eventos ya guardados conservan su texto;
  - los directorios y G-8 los siguen ordenando por texto;
  - el riesgo que queda está acotado: solo dos eventos **viejos del mismo segundo**, o uno viejo y uno nuevo **en el segundo del despliegue**, pueden salir fuera de orden cronológico;
  - entre eventos nuevos, el orden es siempre cronológico.
- **Un cursor de G-8 emitido antes del despliegue** sigue valiendo: lleva texto, y el texto se sigue comparando como texto.
- Ningún puerto cambia. `ActivityEventsPort.ActivityEvent.recordedAt` sigue siendo `String` (`ActivityEventsPort.java:18`).

### 2.3 Tests (rojo esperado)

1. `RecordedAtTextTest` **[Plan]**. **Rojo:** compilación ("cannot find symbol RecordedAtText").
   - `…:05Z` da `…:05.000000000Z`; `.1Z` da `.100000000Z`; con 6 y 9 decimales, el texto exacto.
   - El resultado se vuelve a parsear al mismo instante.
   - Para dos instantes del mismo segundo, el orden del texto es el cronológico.
2. `core`, integración con Mongo real: `append` guarda `recordedAt` con `\.\d{9}Z$` y `Instant.parse` del texto da un instante válido. **Rojo:** aserción, porque hoy el texto puede no tener 9 decimales. Para que sea determinista, el test busca entre varios eventos uno con fracción no de 9 cifras, o comprueba el patrón en todos.
3. `core`, la integridad con datos mezclados:
   - eventos viejos crudos, insertados con `Instant.toString()` como en `IntegrityRecomputationIntegrationTest.java:156`, y eventos nuevos por `append`, en el mismo stream;
   - `RecomputingEventIntegrityChecker` no da hallazgos.
   - **Rojo:** ninguno esperado; es la prueba de que el hash no cambia. Se ejecuta antes y después del cambio.
4. `core`, el directorio de activos con dos génesis nuevas del mismo segundo, la primera con nanos 0: el orden es el cronológico. **Rojo:** aserción.
5. `app`, G-8: un cierre en el segundo exacto y un evento nuevo 100 ms después. El evento sale antes del cierre (descendente). **Rojo:** aserción, porque hoy `…:05Z` del cierre ordena detrás de `…:05.1…Z`.
6. Las medidas de los índices (`MongoIndexesAtStartupIntegrationTest`, `OrganizationActivityAdaptersTest`) y los tests 11a a 11f de G-8 siguen en verde sin cambios.

### 2.4 Mutaciones (`core` y `app`)

| # | Mutación | La mata |
|---|---|---|
| 0 | línea base | — (VIVA) |
| 1 | el adaptador sigue con `toString()` | test 2 |
| 2 | 6 decimales en lugar de 9 (pierde nanos) | test 1 (texto exacto y parse sin pérdida) |
| 3 | hashear el texto fijo en lugar de `toString()` | test 3 (`HASH_MISMATCH` en los eventos nuevos) |
| 4 | zona local en lugar de UTC | test 1 |
| 5 | G-8 con `closedAt.toString()` | test 5 |
| 6 | G-8 con `anchoredAt.toString()` | test 5, variante con lote |

### 2.5 Puntos para Manuel

| # | Pregunta | Recomendación |
|---|---|---|
| **P-DT1-1** | ¿Cómo conviven los datos viejos? (a) tolerarlos al leer, con texto fijo solo en los nuevos; (b) un campo nuevo ordenable **[Plan, solo si se elige]** `recordedAtOrder`; (c) normalizar el texto guardado | **(a).** (c) reescribe eventos guardados, y la restricción lo prohíbe. (b), sin rellenar los eventos viejos, que también sería reescribirlos, los deja sin campo: los directorios y G-8 los ordenarían primero o último, peor que hoy, y además pediría índices nuevos. (a) no toca nada guardado, mantiene el hash y deja el riesgo acotado a lo viejo del mismo segundo (§2.2). |
| **P-DT1-2** | ¿9 decimales, sin pérdida, o truncar el instante a microsegundos con 6? | **9.** Truncar cambia el instante que se hashea, aunque de forma coherente. 9 decimales no cambian ningún valor, y `Instant.parse` los lee. |
| **P-DT1-3** | ¿G-8 forma también con `RecordedAtText` las claves del cierre y del anclaje? | **Sí.** Si no, un cierre en el segundo exacto sigue ordenando mal contra los eventos nuevos (test 5). |
| **P-DT1-4** | Para probar el caso de nanos 0 en el adaptador, ¿se le inyecta un `Clock`? | **No.** El caso se prueba en `RecordedAtTextTest`, y en el adaptador basta el patrón. Así su constructor no cambia. |

### 2.6 Aviso a la web y al móvil (anticipo)

- **Nada visible cambia.** Ninguna respuesta lleva `recordedAt`.
- El orden de G-8 y de los listados de fondos y activos se vuelve cronológico dentro de un mismo segundo para los datos nuevos.

---

## 3. Deuda 3 — P-E2-2: G-8 muestra lo más reciente (D-3)

### 3.1 Hechos verificados

- **Los dos directorios devuelven los streams en orden ascendente de su génesis** (`MongoFundDirectoryAdapter.java:30` y `MongoAssetDirectoryAdapter.java:27`, `Sort.by("recordedAt", "streamId")`).
  - G-8 se queda con los 10 000 primeros (`OrganizationActivityQuery.java:131-135`), que son los más antiguos (`ficha-R6-enmienda-2.md:43-45`).
- **Quién usa los métodos de hoy**, que no cambian:
  - G-7: `OrganizationIntegrityQuery.java:64-65`;
  - `GET /funds` y el recaudado por mes: `FundOperationalQueryService.java:67` y `:78`;
  - `GET /physical-assets`: `PhysicalAssetOperationalQueryService.java:68`;
  - G-8: `OrganizationActivityQuery.java:131-132`.
- **Los puertos** (`FundDirectoryPort.java:9` y `AssetDirectoryPort.java:21`) tienen un solo método cada uno.
- **Los índices parciales** `{payload.organizationRef: 1, recordedAt: 1, streamId: 1}` (`TraceabilityEventDocument.java:20-26`) se pueden recorrer al revés para `{recordedAt: -1, streamId: -1}`, sin etapa de ordenación.
- **`truncated` se calcula con `DIRECTORY_LIMIT + 1`** (`OrganizationActivityQuery.java:131-133`).
- **La ficha R-6 está CONGELADA** (`ficha-R6-datos-estilo-v2.md:4`). G-8 está en `:141-154`, y la Enmienda 2 en `ficha-R6-enmienda-2.md`. Hace falta la Enmienda 3: `ficha-R6-enmienda-3.md`, en este PR.
- **Comparte el orden con DT-B-1:** el orden descendente usa el mismo `recordedAt` como texto. Con P2 fusionado, es cronológico para los datos nuevos.

### 3.2 Qué se hace

- **`core`, autorizado por D-3 (§3.5):** un método **nuevo** de lectura descendente en cada puerto:
  - `FundDirectoryPort.findLatestFundIdsByOrganization(String organizationRef, int limit)` **[Plan]**;
  - `AssetDirectoryPort.findLatestAssetIdsByOrganization(String organizationRef, int limit)` **[Plan]**;
  - los dos con el mismo filtro que el método de hoy y `Sort.by(desc("recordedAt"), desc("streamId"))`;
  - los métodos de hoy no cambian.
- **`app`:** solo G-8 (`OrganizationActivityQuery.java:131-132`) pasa a los métodos nuevos.
  - G-7 y los demás usos siguen con los de hoy.
  - `truncated` no cambia: verdadero si alguno de los dos devuelve más de 10 000.
- **Los ítems, su orden, el cursor y lo que nunca sale no cambian** (Enmienda 3).
  - Con 10 000 o menos, el conjunto de streams es el mismo, y los ítems y las páginas son idénticos.
- Ningún índice nuevo: los de hoy se recorren al revés (P-PE2-2).

### 3.3 Tests (rojo esperado)

1. `core`, adaptadores con Mongo real (organización con varias génesis): los métodos nuevos devuelven por `recordedAt` descendente y, en un empate, por `streamId` descendente, y respetan `limit`. **Rojo:** compilación ("cannot find symbol findLatestFundIdsByOrganization").
2. `core`, medición con el profiler, como P-R6D-6 en `OrganizationActivityAdaptersTest`: `IXSCAN` en `idx_fund_genesis_organization` y en `idx_asset_genesis_organization`, con dirección `backward` y sin etapa `SORT`.
3. `app`, G-8 con 10 001 activos: la actividad del activo **más nuevo** sale en la primera página y la del **más antiguo** no. `truncated: true`. **Rojo:** aserción, porque hoy sale la del más antiguo.
4. Lo mismo con 10 001 fondos.
5. G-7 sigue leyendo los más antiguos: con dobles, `OrganizationIntegrityQuery` llama al método de hoy y nunca al nuevo, con `never()`. **Rojo:** ninguno esperado; protege contra un cambio indebido.
6. Los tests de la Enmienda 2 (10 001 y 10 000 de borde) y 11a a 11f siguen en verde sin cambios.

### 3.4 Mutaciones (`core` y `app`)

| # | Mutación | La mata |
|---|---|---|
| 0 | línea base | — (VIVA) |
| 1 | el método nuevo ordena ascendente | test 1 |
| 2 | desempate por `streamId` ascendente | tests 1 y 2 (empate y `SORT`) |
| 3 | sin `limit` | test 1 |
| 4 | G-8 vuelve al método de hoy | tests 3 y 4 |
| 5 | G-7 pasa al método nuevo | test 5 |
| 6 | `truncated` con `>=` | tests de borde de la Enmienda 2 |

### 3.5 Puntos para Manuel

| # | Pregunta | Recomendación |
|---|---|---|
| **P-PE2-1** | "Lo más reciente" ¿son los streams **creados** más recientemente, por su génesis, o los que tuvieron actividad más reciente? | **Por génesis.** Es lo que los directorios saben ordenar con su índice. Un stream antiguo con actividad nueva, detrás de 10 000 más nuevos, no sale, y `truncated: true` lo avisa. Ordenar por la última actividad pide una agregación sobre todos los eventos de la organización, cuyo coste es el que R-R6D-2 acota. |
| **P-PE2-2** | Desempate: ¿`streamId` descendente, que recorre el índice al revés sin ordenar, o ascendente? | **Descendente:** usa el índice de hoy sin etapa `SORT`. Un desempate ascendente pediría un índice nuevo. |
| **P-PE2-3** | Nombres: ¿`findLatestFundIdsByOrganization` y `findLatestAssetIdsByOrganization`? | **Sí**, en paralelo con los de hoy. |

### 3.6 Aviso a la web y al móvil (anticipo)

- `GET /api/v1/organizations/{organizationId}/activity`: la forma no cambia.
- Con más de 10 000 fondos o activos (`truncated: true`), la actividad que sale es la de los **más recientes**, no la de los más antiguos.
- Con 10 000 o menos, nada cambia.

---

## 4. Documentos de este PR (P0)

- `decisiones-delegadas-2026-10.md`: nota del 2026-10-26 con D-1, D-2 y D-3, "comunicado por Manuel".
- Este plan.
- `ficha-R6-enmienda-3.md`: la Enmienda 3, para D-3.
- Notas "pagada en este corte (pendiente)" en `estado-fase6.md` §0.53 (DT-B-1) y §0.64 (la deduplicación), y en `ficha-R6-enmienda-2.md` (P-E2-2).
