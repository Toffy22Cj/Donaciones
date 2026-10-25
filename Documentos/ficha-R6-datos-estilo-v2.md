# Ficha R-6 — Datos que piden las pantallas del estilo v2

**Naturaleza:** ficha de contrato HTTP, en el formato de `ficha-R1-busqueda-descubrimiento-convocatorias.md`. **No es un ADR y no autoriza código** por sí sola: cada bloque se implementa con su propio plan aprobado (regla 3.4).
**Estado: CONGELADA** — decisiones D-R6-1 a D-R6-10 tomadas por Manuel (responsable de backend), por delegación de Carlos, el 2026-10-21 (§9). Visto bueno de Carlos en la revisión del PR que la incorpora.
**Fecha:** 2026-10-21 (UTC).
**Autor:** Carlos, con asistencia de Claude. **Implementa:** Manuel (backend). **Revisión y correcciones C-1 a C-7:** Manuel, con asistencia de Claude, 2026-10-21 (§10).
**Origen:** `paxfide-web/Documentos/prompts-agentes-estilo-v2.md` §3.4, huecos G-1 a G-8. Carlos decidió el 2026-10-21 que los nueve datos son **necesarios**. Mientras no existan, el front implementa las pantallas sin ellos y no los inventa.
**Base verificada:** `develop` en `4bef7be` (redacción) y en `fdccd99` (correcciones de §10). Cada plan vuelve a verificar las rutas de código contra el `develop` del momento.

---

## 1. Resumen

| # | Dato | Pantalla | Bloque | Tamaño |
|---|---|---|---|---|
| G-4b | `assetType` en la lista de lotes de la organización | Panel | A | Pequeño |
| G-5 | `endDate` en la lista de convocatorias de la organización | Panel | A | Pequeño |
| G-1 | Título de la convocatoria y nombre de la organización en el seguimiento | Línea de tiempo | A | Pequeño |
| G-2 | Fecha del pago en el seguimiento | Línea de tiempo | B | Mediano |
| G-6 | Recaudado por mes | Panel | B | Mediano |
| G-4 | Relación padre-hijo de los lotes en el seguimiento | Línea de tiempo | C | Mediano |
| G-3 | Estado de anclaje por paso en el historial | Línea de tiempo | C | Mediano |
| G-7 | Integridad de la organización | Panel | D | Grande |
| G-8 | Actividad reciente de la organización | Panel | D | Grande |

**Orden:** A, B, C, D. Un PR por bloque. Cada uno es independiente: el front añade cada dato cuando su bloque está en `develop`.

**Todos los cambios a respuestas existentes son aditivos** (campos opcionales nuevos). Ningún cliente actual se rompe: la regla de `referencia-api-v1.md` §0 ya dice que `campo?` puede faltar o llegar `null`.

## 2. Lo que no cambia

| Regla | Fuente |
|---|---|
| El seguimiento se autentica con `Authorization: Bearer <trackingCode>`; el 401 del filtro no cambia | `referencia-api-v1.md` §8 |
| El seguimiento nunca devuelve `fundId`, `donorRef`, `streamId`, `batchId`, ids internos de activos ni datos de otras donaciones | DD-76; ficha P4 |
| Las respuestas de la organización nunca llevan `donorRef` ni correos | DD-31, DD-54, DD-69 |
| 403 uniforme en rutas protegidas | DD-01 |
| La vista operacional del staff **sigue sin** genealogía de la división (`parentAssetRef`/`rootAssetRef`) | `api-contract-matrix.md` (fila `GET /physical-assets/{assetRef}`); `PhysicalAssetOperationalView` |
| Ningún dato personal en el anclaje ni en las respuestas públicas | Prompt maestro, privacidad |
| Ningún evento nuevo en el Event Store, ningún cambio de payload ni de versión de esquema | Esta ficha solo lee |

---

## 3. Bloque A — campos aditivos en respuestas existentes

### G-4b · `assetType` en la lista de lotes de la organización

- **Endpoint:** `GET /api/v1/organizations/{organizationId}/physical-assets` (`api/.../asset/OrganizationAssetsController.java`).
- **Cambio:** cada ítem gana `assetType` (el mismo valor que ya devuelve el seguimiento en `logistics[].assetType`).
- **Dónde:** `PhysicalAssetOperationalView` (`core/.../port/out`) no lo tiene. Se añade el campo al registro y a su adaptador.
- **Afecta también a** `GET /physical-assets/{assetRef}`, que usa la misma vista. Aditivo en los dos.
- **Sigue fuera:** `parentAssetRef`/`rootAssetRef` (§2).

### G-5 · `endDate` en la lista de convocatorias de la organización

- **Endpoint:** `GET /api/v1/organizations/{organizationId}/campaigns` (`app/.../campaign/OrganizationCampaignsController.java`; consulta `convocatoria/.../query/OrganizationCampaignsQuery.java`).
- **Cambio:** cada ítem gana `startDate` y `endDate` (ISO-8601 UTC). `Convocatoria` ya los tiene y son inmutables.
- "Cierra pronto" lo calcula el front; el backend no añade un campo derivado.

### G-1 · Convocatoria y organización en el seguimiento

- **Endpoint:** `GET /api/v1/donations/tracking` (`api/.../controller/PublicDonationController.java`).
- **Cambio:** la respuesta gana `campaignTitle?` y `organizationName?`. Faltan si la donación no tiene convocatoria o no se encuentra (rama defensiva, como `campaignTitle?` en `GET /account/donations`).
- **La ciudad no entra:** `Convocatoria` no tiene ubicación. Añadirla sería un cambio de dominio; queda fuera de esta ficha.
- **Límite de módulos:** el controlador vive en `api`, que depende solo de `core` y `contracts` (`api/pom.xml`), no de `convocatoria`. Un puerto nuevo de solo lectura, `CampaignPublicSummaryPort` (`campaignRef → Optional<{title, organizationName}>`), **declarado en `contracts`** junto a `OrganizationPublicNamePort` (mismo patrón: `contracts` declara, `app` implementa) e implementado en `app`, que ya depende de `convocatoria` e `identity` (C-1). **[D-R6-1]**
- Los dos campos ya son públicos (CV-07 devuelve `organizationName` y `title` por `publicCode`), así que no amplían lo que un tercero puede saber.

---

## 4. Bloque B — datos con fecha

### G-2 · Fecha del pago

- **Endpoint:** `GET /api/v1/donations/tracking`.
- **Cambio:** `financialSnapshot` gana `firstClearedAt?` y `lastClearedAt?` (ISO-8601 UTC): el `occurredAt` del primer y del último `FUNDS_CLEARED` del fondo, por orden de `sequence` del stream (nunca por timestamp).
- **Por qué dos:** un fondo puede tener varias liquidaciones (`FundsClearedV2Payload` acumula con `inc` en `DonationProjectionHandler`). El front muestra la primera como "Pago confirmado" y, si son distintas, la última.
- **Usar `occurredAt`, no `recordedAt`:** es el momento del hecho, no del registro (prompt maestro, Event Sourcing). **[D-R6-2]**
- **Dónde (C-2):** se leen **del Event Store al consultar**: los `FUNDS_CLEARED` del stream del fondo (v1 y v2), con un puerto de lectura de `core`. **No** se añaden a `DonationProjectionDocument` en este corte. Motivo: `ProjectionRebuildService.rebuildAll()` existe pero hoy solo lo invoca un test; no hay un disparador operativo para reconstruir proyecciones en un entorno real, y crear uno sería un mecanismo nuevo de recuperación (regla 3.5). La consulta es por un solo `streamId`, cubierta por el índice `(streamId, sequence)`.

### G-6 · Recaudado por mes

- **Endpoint nuevo:** `GET /api/v1/organizations/{organizationId}/funds/monthly?months=1..12` (por defecto 6). Auth: la misma política que `GET /organizations/{organizationId}/funds` (DD-31). `no-store`.
- **Respuesta:** `200 {timezone, items: [{month: "2026-10", currency, clearedAmount}]}`. Un ítem por mes y moneda, incluidos los meses con 0. Importes como texto en unidades mínimas (§0).
- **Fuente:** los `FUNDS_CLEARED` de los fondos de la organización, agrupados por el mes de `occurredAt`. **[D-R6-3]**
- **Versiones de esquema (C-3):** `FUNDS_CLEARED` 1.0 (`FundsClearedPayload`) **no** lleva `organizationRef`; la 2.0 sí. La pertenencia a la organización se resuelve por el `FUND_REGISTERED` del mismo stream, como ya hace `MongoFundDirectoryAdapter`, nunca solo por el payload. Test con eventos de las dos versiones.
- **Zona horaria del corte de mes:** `America/Bogota`, fija y devuelta en `timezone`. **[D-R6-4]**
- Recalculado en cada petición (sin proyección nueva) mientras el volumen lo permita; el plan registra el límite.
- *Nota (2026-10-23, plan del Bloque B, P-R6B-6; Manuel):*
  - **Tope de 10 000 fondos** por petición. Si la organización tiene más, la respuesta añade **`truncated: true`** al contrato de arriba (`200 {timezone, items, truncated?}`).
  - La pertenencia es por la **génesis** (`FUND_REGISTERED` o `FUNDS_CLEARED` 2.0), como `MongoFundDirectoryAdapter`. Los fondos 1.0 sin organización, anteriores a `0579f41`, quedan fuera (P-R6B-1).
  - Los meses en 0 salen de las monedas de los fondos de la organización (P-R6B-3).
  - El texto de arriba no se reescribe.
- **Módulo (C-7):** consulta en `core` (dueño del Event Store) expuesta por un puerto de lectura; la ruta HTTP donde viven hoy las de fondos. El plan lo fija contra el código.

---

## 5. Bloque C — datos que cambian una regla existente

### G-4 · Relación padre-hijo de los lotes en el seguimiento

- **Endpoint:** `GET /api/v1/donations/tracking`.
- **Cambio:** cada ítem de `logistics` gana `parentAssetRef?`: el `assetRef` **opaco** del lote del que salió (calculado con `AssetRefService.computeRef`, igual que `assetRef`). Falta en los lotes que no vienen de una división.
- **Ya está en la proyección:** `LogisticsProjection.parentAssetRef` existe; solo hay que pasarlo por `LogisticsReadItem` y el mapeador.
- **Garantía:** un `parentAssetRef` siempre apunta a otro ítem de la misma respuesta (la proyección resuelve los hijos por el índice del padre, `AssetProjectionRouting`). Si no está, se omite el campo; nunca se devuelve un ref de otra donación.
- **Qué regla cambia:** la genealogía se excluyó de las lecturas "para no relajar el perímetro de privacidad". Para el **donante**, todos los lotes del árbol pertenecen a su donación y ya los ve en `logistics`, así que solo se añade la relación entre cosas que ya ve. La vista del staff no cambia. Se registra como enmienda de contrato en `api-contract-matrix.md` (fila del seguimiento). **[D-R6-5]**
- `rootAssetRef` no se expone: se deduce siguiendo `parentAssetRef`.

### G-3 · Estado de anclaje por paso

- **Endpoint:** `GET /api/v1/donations/tracking/assets/{assetRef}/history` (`PublicAssetHistoryController`).
- **Cambio:** cada ítem de `history` gana `anchorStatus`, con **cuatro** valores (C-4) que cubren los diez estados de `AnchorStatus` (`crypto/.../domain/AnchorStatus.java`):

| `anchorStatus` | Estados del lote | Qué significa para el donante |
|---|---|---|
| `ANCHORED` | `ANCHORED` | Anclado y confirmado |
| `IN_BATCH` | `COLLECTING`, `PENDING`, `SUBMITTING`, `SUBMITTED`, `STUCK` | En proceso de anclaje |
| `PENDING` | sin lote, o lote `RELEASED` (sus eventos vuelven a ser reclamables, Enmienda 1 de ADR-039 §2.3) | Todavía no está en ningún lote |
| `UNDER_REVIEW` | `FAILED`, `COLLECTING_FAILED`, `ANCHOR_MISMATCH` | El anclaje necesita revisión del operador |

- Un estado nuevo de `AnchorStatus` sin fila en esta tabla hace fallar un test (cobertura exhaustiva del `enum`): nunca cae en un valor por defecto. Si el plan encuentra que `FAILED` o `COLLECTING_FAILED` liberan los eventos igual que `RELEASED`, se detiene y lo reporta antes de cambiar la tabla.
- **Por qué el cuarto valor:** sin él, un lote `ANCHOR_MISMATCH` o `FAILED` se mostraría como "en proceso" para siempre, o se ocultaría un fallo de integridad. El detalle sigue siendo el de `GET …/integrity` (ficha P4).
- **Fuente:** `EventBatchMembershipPort.findMembership` (`streamId`, `sequence`, `merkleBatchId`), la que ya usa `TrackingIntegrityService`, más el estado del lote. Nada nuevo en `crypto`.
- **No se expone el hash del evento.** Un hash recortado no le sirve al donante para verificar nada; la verificación sigue siendo la de `GET …/integrity` (ficha P4). **[D-R6-6]**
- **Sin `sequence` ni `streamId`** en la respuesta (DD-76): el cruce se hace en el servidor.
- El resultado de la verificación (`MATCH`/`MISMATCH`) **no** se lleva a cada paso: sigue siendo por lote, en `…/integrity`.

---

## 6. Bloque D — endpoints nuevos

### G-7 · Integridad de la organización

- **Endpoint nuevo:** `GET /api/v1/organizations/{organizationId}/integrity`. Auth: `ADMINISTRATOR` o `REPRESENTATIVE` (como la predicción, DD-43). `no-store`.
- **Respuesta:** `200 {unanchoredEvents, inPendingBatches, underReviewEvents, lastAnchoredAt?, truncated, checkedAt}` (C-5, C-6).
- **Streams de la organización:** los de sus fondos y sus activos. Se cuentan con la misma clasificación de G-3 (`underReviewEvents` = eventos en lotes `UNDER_REVIEW`).
- **Tope (C-5):** `TrackingIntegrityService.MAX_EVENTS` es 10 000, pensado para una sola donación; una organización lo supera pronto. El plan elige entre contar con una agregación de MongoDB (sin tope) o mantener el tope y devolver `truncated: true` cuando se alcanza. Nunca un contador silenciosamente incompleto.
- **Nunca:** `batchId`, `merkleRoot`, `transactionHash` ni nada de otras organizaciones. Un lote mezcla eventos de varias organizaciones; solo se cuentan los propios.
- **Sin `lastVerification` en este corte** (C-6). **[D-R6-7]**
- **Módulo (C-7):** `app` (compone `core` y `crypto`, como `TrackingIntegrityService`). El plan lo fija contra el código.

### G-8 · Actividad reciente de la organización

- **Endpoint nuevo:** `GET /api/v1/organizations/{organizationId}/activity?cursor=`. Auth: `ADMINISTRATOR`, `REPRESENTATIVE` y `EMPLOYEE` de la organización. **[D-R6-8]** `no-store`.
- **Respuesta:** `200 {items: [{type, occurredAt, subject: {kind: "ASSET"|"CAMPAIGN"|"FUND", ref}, quantity?, unitOfMeasure?, locationZone?}], nextCursor?}`, 20 por página, de lo más reciente a lo más antiguo.
- **Tipos incluidos [D-R6-9]:**
  - Del Event Store, con su `eventType` real: `ASSET_REGISTERED`, `ASSET_DISPATCHED`, `ASSET_RECEIVED`, `ASSET_DELIVERED`, `ASSET_SPLIT` y `FUNDS_CLEARED` (sin importe por donación ni donante).
  - De fuera del Event Store, como tipos **de la respuesta** (no son eventos de dominio): `CAMPAIGN_CLOSED`, desde la fecha de cierre que ya usa `CampaignTimelineQuery` (`closedAt`), y `BATCH_ANCHORED`, desde `MerkleBatch.anchoredAt`, con cuántos eventos propios entraron. Hoy no existen eventos `CAMPAIGN_*` ni `BATCH_*` en el Event Store, y esta ficha no los crea.
- **Nunca:** el actor (quién hizo la acción), `donorRef`, `beneficiaryRef`, custodios, ubicaciones exactas (solo `locationZone`, calculada igual que en el seguimiento, `PublicDonationMapper`) ni importes de una donación individual.
- **Orden y cursor (C-7):** se mezclan tres fuentes. El orden total es `(instante de listado descendente, tipo de fuente, id estable)`, donde el instante es `recordedAt` para los eventos (solo para listar; no es un orden de dominio), `closedAt` para el cierre y `anchoredAt` para el anclaje. El cursor opaco y cifrado (DD-58) guarda esa clave completa, de modo que un empate de instantes entre fuentes no repite ni salta ítems.
- El texto ("Lote L-0142 recibido en Riohacha") lo arma el front con `labels.ts`; el backend no devuelve frases.
- **Módulo (C-7):** `app` (compone `core`, `convocatoria` y `crypto`). El plan fija los puertos contra el código.

> **Nota (2026-10-25): respuestas de Carlos a las dos preguntas del plan D sobre esta sección** (`plan-r6-bloque-d.md` §6.2, P-R6D-1 y P-R6D-2). El texto de G-8 de arriba se conserva; esta nota lo precisa. Aprobación: `plan-r6-bloque-d.md:5` ("Respuestas a las dos preguntas abiertas (Manuel, 2026-10-25): P-R6D-1 (b) y P-R6D-2 (a)"), dentro de la aprobación de Carlos de todo lo pendiente (`decisiones-delegadas-2026-10.md`, notas del 2026-10-25; confirmación escrita "todo aprobado" en #134). El plan D (§7) pide registrarlo aquí, en su propio PR y antes del de código.
> - **P-R6D-1 (b):** un ítem `BATCH_ANCHORED` **no lleva `subject`** y lleva **`eventCount`**: el número de eventos propios de la organización que entraron en ese lote. Sin `batchId` ni ningún otro identificador del lote (como G-7). Para los demás tipos, `subject` sigue como dice la respuesta de arriba. La forma de la respuesta queda: `{type, occurredAt, subject?: {kind: "ASSET"|"CAMPAIGN"|"FUND", ref}, eventCount?, quantity?, unitOfMeasure?, locationZone?}`.
> - **P-R6D-2 (a), limitada a las convocatorias cerradas:** `CAMPAIGN_CLOSED` sale solo de las convocatorias de la organización con estado `CLOSED`, y su fecha es la del cierre que ya usa `CampaignTimelineQuery` (`closedAt`). No se cambia ningún puerto de `convocatoria`.

---

## 7. Tests previstos (rojos primero)

**Bloque A**
1. Lista de lotes con `assetType`; sigue sin `parentAssetRef`/`rootAssetRef` ni `donorRef` (aserción negativa).
2. Lista de convocatorias con `startDate` y `endDate` iguales a los del dominio.
3. Seguimiento con `campaignTitle` y `organizationName`; sin convocatoria, los dos faltan; la respuesta sigue sin `fundId` ni `donorRef`.
4. Test de arquitectura: `api` sigue sin depender de `convocatoria` ni de `identity`.

**Bloque B**
5. Una liquidación: `firstClearedAt = lastClearedAt = occurredAt` del evento. Dos: primera y última por `sequence`, aunque sus `occurredAt` estén desordenados. Fondo sin `FUNDS_CLEARED`: los dos faltan.
6. Recaudado por mes: meses vacíos en 0; corte de mes en `America/Bogota` (un `FUNDS_CLEARED` a las 23:30 del 31 en Bogotá cae en ese mes aunque en UTC sea el día 1); eventos 1.0 y 2.0 sumados en la organización correcta; otra organización → 403; `months` fuera de 1..12 → 400 con el campo.

**Bloque C**
7. División: el hijo trae `parentAssetRef` igual al `assetRef` del padre de la misma respuesta; un lote sin padre no trae el campo.
8. Ningún `parentAssetRef` apunta fuera de `logistics` (aserción negativa sobre una donación con un activo dividido de otra donación en la base).
9. Historial: una prueba por cada uno de los diez estados de `AnchorStatus`, más el caso sin lote, con el valor de la tabla de G-3; test de cobertura exhaustiva del `enum`. La respuesta no contiene `sequence`, `streamId`, `merkleBatchId` ni hashes.

**Bloque D**
10. Integridad de la organización: solo cuenta eventos propios aunque el lote mezcle organizaciones; `underReviewEvents` cuenta los lotes `UNDER_REVIEW`; si se mantiene un tope, al alcanzarlo `truncated: true`; otra organización → 403; sin `batchId` ni `merkleRoot`.
11. Actividad: orden, paginación sin repeticiones ni saltos **incluido un empate de instantes entre fuentes distintas**, cursor inválido → 400; ningún ítem de otra organización; ningún campo de actor, donante, beneficiario ni importe individual (aserción negativa).

**Común:** `scripts/ci-local.sh` en verde sobre el último commit de cada PR, con el resumen literal de Surefire.

## 8. Documentos que se actualizan con cada PR

- `referencia-api-v1.md`: los campos y endpoints nuevos.
- `api-contract-matrix.md`: la fila del seguimiento (G-4) y las filas nuevas (G-6, G-7, G-8).
- `paxfide-web/Documentos/prompts-agentes-estilo-v2.md` §3.4: el hueco cerrado pasa de "mientras no exista" a "disponible desde <commit>". **Cambio para la web:** `anchorStatus` tiene cuatro valores, no tres (C-4).
- Aviso a la web y al móvil con cada bloque.

## 9. Decisiones — Manuel, 2026-10-21, por delegación de Carlos

| # | Pregunta | Decisión |
|---|---|---|
| D-R6-1 | G-1: puerto nuevo de solo lectura | **Sí**, `CampaignPublicSummaryPort` declarado en `contracts` e implementado en `app` (C-1). Puerto nuevo, no cambia uno existente |
| D-R6-2 | G-2: fecha del pago | **`occurredAt` de `FUNDS_CLEARED`** (primero y último por `sequence`), **leído del Event Store al consultar**, sin cambiar la proyección (C-2) |
| D-R6-3 | G-6: fuente del recaudado por mes | **Los `FUNDS_CLEARED` del Event Store**, versiones 1.0 y 2.0, con la pertenencia por `FUND_REGISTERED` (C-3) |
| D-R6-4 | G-6: zona horaria del corte de mes | **`America/Bogota`**, devuelta en la respuesta |
| D-R6-5 | G-4: `parentAssetRef` opaco solo en el seguimiento del donante | **Sí.** La vista del staff no cambia |
| D-R6-6 | G-3: estado de anclaje por paso sin hash del evento | **Sí, con cuatro valores** (C-4) |
| D-R6-7 | G-7: verificación del último lote | **(a) No en este corte**; contadores con `underReviewEvents` y `truncated` (C-5, C-6) |
| D-R6-8 | G-8: roles | **`ADMINISTRATOR`, `REPRESENTATIVE` y `EMPLOYEE`** de la organización |
| D-R6-9 | G-8: tipos | **Los de §6**, con el orden total y el cursor de C-7 |
| D-R6-10 | Orden de los bloques | **A, B, C, D, un PR por bloque** |

> **Nota (2026-10-25, sesión de Manuel, con asistencia de Claude): D-R6-6 y la Enmienda 1.** La tabla de arriba se conserva. `ficha-R6-enmienda-1.md` (E1) **reabre D-R6-6**: cada paso de G-3 llevaría `eventHash` y, con el lote `ANCHORED`, `anchorProof` (decisión de Carlos del 2026-10-24). E2 añade G-9 (`lastEventAt?` por lote). La enmienda está en `develop` (#117), pero su estado sigue siendo **PROPUESTA** (`ficha-R6-enmienda-1.md:3`) y #117 se fusionó sin review. **Hasta que Carlos la confirme, rige D-R6-6 tal como está** (también `[D-R6-6]` en §5) y no se escribe código de E1 ni de G-9. Planes: C, versión 2 (#126), y adenda G-9 del plan B (#124), los dos en BORRADOR.

> **Nota (2026-10-25, más tarde; ficha R-6, Enmienda 1, E4): la Enmienda 1 está APROBADA** por Carlos (`decisiones-delegadas-2026-10.md`, notas del 2026-10-25; confirmación escrita "todo aprobado" en #134), y el plan C, versión 2, también. La nota anterior se conserva; esta la supera en lo que dice de "hasta que Carlos la confirme". **D-R6-6 queda reabierto por E1:** cada paso del historial lleva `eventHash` y, con el lote `ANCHORED` y sus hojas, `anchorProof` sin `leafCount` (`ficha-R6-enmienda-1.md` §E1; `plan-r6-bloque-c.md`). El `[D-R6-6]` de §5 ("No se expone el hash del evento") se lee con esta enmienda. Implementación: rama `feat/r6-bloque-c` (`estado-fase6.md` §0.50).

## 10. Correcciones sobre el borrador (Manuel, 2026-10-21)

| # | Dónde | Qué decía el borrador | Por qué se corrige |
|---|---|---|---|
| **C-1** | G-1 | "Mismo patrón que `DonationIntentReadPort` en `AccountDonationsController`"; puerto "en `core` o `contracts`" | `DonationIntentReadPort` vive en `convocatoria` y lo usa un controlador de `app`, no de `api`. El precedente correcto es `OrganizationPublicNamePort`: declarado en `contracts`, implementado en `app` |
| **C-2** | G-2 | Guardar las fechas en la proyección y reconstruirla "con el mecanismo que ya existe" | `ProjectionRebuildService` solo lo invoca un test (`DonationProjectionIntegrationTest`); no hay disparador operativo. Leer del Event Store evita la reconstrucción y un mecanismo nuevo |
| **C-3** | G-6 | Agrupar los `FUNDS_CLEARED` "de los fondos de la organización" | La versión 1.0 del payload no lleva `organizationRef`; sin resolver la pertenencia por `FUND_REGISTERED` se perderían esas liquidaciones |
| **C-4** | G-3 | Tres valores (`ANCHORED`, `IN_BATCH`, `PENDING`) | `AnchorStatus` tiene diez estados. `FAILED`, `COLLECTING_FAILED` y `ANCHOR_MISMATCH` no cabían en ninguno sin mentir al donante (regla 2.6: todo estado con su salida explícita) |
| **C-5** | G-7 | Contar "con el mismo tope que P4 (`MAX_EVENTS`)" | El tope es 10 000 eventos, pensado para una donación; en una organización daría un contador incompleto sin avisar |
| **C-6** | G-7 | `lastVerification?` en la respuesta y D-R6-7 abierta | Se decidió (a): el campo sale del contrato de este corte. Se añade `underReviewEvents` |
| **C-7** | G-6, G-7, G-8 | Sin módulo dueño; G-8 ordenaba por `recordedAt` sin decir cómo mezclar tres fuentes | Cada endpoint nuevo declara su módulo. Un cursor sobre tres fuentes necesita un orden total con desempate, o repite y salta ítems en los empates |
