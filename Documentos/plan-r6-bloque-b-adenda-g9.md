# Plan — Ficha R-6, Bloque B, adenda G-9: fecha del último cambio de cada lote (`lastEventAt?`)

~~**Estado: BORRADOR. Espera el "plan aprobado" de Manuel**~~ → **APROBADO — Manuel, 2026-10-25** (§9; nota del 2026-10-25). Texto original de esta línea:
**Estado: BORRADOR. Espera el "plan aprobado" de Manuel**, por la delegación de Carlos del 2026-10-24 (`decisiones-delegadas-2026-10.md`, nota del 2026-10-24). No se escribe código antes (regla 3.4).
**Fecha:** 2026-10-24 (UTC).
**Por qué es una adenda en su propio PR:** G-9 debía ir en el Bloque B, dentro del #113 (ficha R-6 Enmienda 1, E3; orden v2 de Carlos). El #113 se fusionó el 2026-10-24 antes de añadirla, así que G-9 va como adenda de `plan-r6-bloque-b.md` en su propio PR (indicación de Manuel, 2026-10-24). El plan B no se reescribe.
**Contrato:** `ficha-R6-enmienda-1.md` §E2. Decisión de Manuel sobre el "cómo" ([Manuel] de E2): `plan-ejecucion-cierre-manuel-2026-10-24.md` §0.5. Los puntos **[Plan]** se aprueban con la adenda.
**Base:** `develop` `8e37667`. Rama: `feat/r6-bloque-b-g9`, desde el `develop` del momento, con su hash.
**Dueño:** Manuel.

## 0. Bloqueo de estado (anotado, no se resuelve aquí)

**B-G9-1.** `Documentos/ficha-R6-enmienda-1.md:3` dice "**Estado: PROPUESTA.** … Carlos da el visto bueno en el PR". El PR de esa copia (#117) lo fusionó Carlos el 2026-10-24 **sin Approve ni ninguna review** en GitHub (H-1). Este plan no decide si la fusión cuenta como visto bueno. **Pregunta para Carlos:** ¿la Enmienda 1 de la ficha R-6 queda aprobada? Mientras no conste, el código de G-9 (y el de E1, en el plan C) no empieza. El plan sí puede aprobarse.

## 1. Lo que pide la ficha (E2, literal en lo esencial)

- **Endpoint:** `GET /api/v1/organizations/{organizationId}/physical-assets`.
- **Cambio:** cada ítem gana `lastEventAt?` (ISO-8601 UTC): el `occurredAt` del último evento del stream del lote, **por orden de `sequence`**, nunca por timestamp.
- **Dónde:** se lee del Event Store al consultar. Se pide "sin una consulta por lote".
- **Si falta:** se omite el campo.
- **Tests:**
  - un lote con varios eventos tiene la fecha del de mayor `sequence`, aunque otro tenga un `occurredAt` mayor;
  - una página con varios lotes hace una sola consulta de agregación, y se cuenta.

**Decisión de Manuel (§0.5 del plan de ejecución):** una agregación por página con `$sort {streamId, sequence: -1}` y `$group` con el primero; **una sola consulta**, que el test cuenta, medida con `explain()`.

## 2. Hechos verificados en `develop` `19b4e0f` (2026-10-24; los archivos citados no cambian en `8e37667`)

- **El listado ya lee el stream entero de cada activo.** `PhysicalAssetOperationalQueryService.listForOrganization` (`core/.../application/query/PhysicalAssetOperationalQueryService.java:60-75`) toma hasta `MAX_ASSETS = 200` ids de `AssetDirectoryPort.findAssetIdsByOrganization` y, **por cada uno**, llama a `eventStore.loadStream(assetId)` para rehidratar el activo (`:68-70`).
- **El orden ya es por `sequence`.** `MongoEventStoreAdapter.loadStream` ordena por `sequence` ascendente (`MongoEventStoreAdapter.java:118-120`). `DomainEvent` lleva `occurredAt` como `Instant` (`DomainEvent.java:14`). En Mongo se guarda como texto (`TraceabilityEventDocument.java:36`; R-R6B-5 del plan B).
- **Índice:** `idx_stream_sequence` `{streamId: 1, sequence: 1}` único (`TraceabilityEventDocument.java:14`).
- **Respuesta:** el listado usa `PhysicalAssetDtos.OperationalResponse` (`PhysicalAssetDtos.java:38`), el mismo registro que `GET /api/v1/physical-assets/{assetRef}` (`PhysicalAssetController.java:157-159`). La ficha solo pide el campo en el listado.

## 3. Choque entre la decisión y el código (P-G9-1, para Manuel)

La decisión de §0.5 parte de que hay que evitar "una consulta por lote". En el código, el listado **ya hace una consulta por lote**, la de la rehidratación, y con ella ya tiene los eventos ordenados por `sequence`. El último de esa lista es exactamente el que pide la ficha.

| Opción | Consultas extra por página | Qué es |
|---|---|---|
| (a) La decisión de §0.5 | **+1** (la agregación `$sort` + `$group`) | Una agregación sobre los `streamId` de la página. Correcta, pero lee otra vez lo que ya se leyó |
| (b) Tomarlo de los eventos ya cargados | **0** | `lastEventAt = events.get(events.size() - 1).occurredAt()`, sobre la lista que `loadStream` ya devuelve ordenada por `sequence` |

**Recomendación: (b).** Cumple la ficha (último por `sequence`, sin consultas por lote **añadidas**) con cero consultas nuevas. El test que cuenta consultas cambia de "una agregación" a "ninguna consulta además de las de rehidratación". Lo hace contando las llamadas a `MongoTemplate`/`EventStorePort` antes y después del cambio con la misma página.

**Si Manuel mantiene (a):** el plan se aplica tal como dice §0.5, con el `explain()` en el PR, y se registra que la página pasa de N a N+1 consultas.

**Deuda existente, solo registrada (R-G9-1):** las 200 rehidrataciones por página (N+1) ya existían antes de G-9. G-9 no las empeora con (b). Quitarlas exigiría una proyección (fuera de este corte, C-2 de la ficha).

## 4. Cambios (con la opción (b); con (a), §4.1)

| Módulo | Archivo | Cambio |
|---|---|---|
| `core` | `PhysicalAssetOperationalView` | Gana `Instant lastEventAt` (nulo si el stream está vacío) |
| `core` | `PhysicalAssetOperationalQueryService.listForOrganization` | `lastEventAt` = `occurredAt` del último evento de `events`, la lista ya ordenada por `sequence`. Nulo si no hay eventos. `findOperationalView` (la vista individual) lo deja nulo **[Plan, P-G9-2]** |
| `api` | `OrganizationAssetsController` | Un registro de ítem propio del listado **[Plan, P-G9-3: `OrganizationAssetItem`, o añadir a `OperationalResponse` un campo que la vista individual nunca rellena]**, con `lastEventAt?` en ISO-8601 UTC y omitido si es nulo (`@JsonInclude(NON_NULL)`) |
| Documentos | `referencia-api-v1.md` (nota fechada de G-9), `ficha-R6-datos-estilo-v2.md` (nota en §9 que remite a la Enmienda 1, E4), `estado-fase6.md` | — |

### 4.1 Con la opción (a)

Además de lo anterior:
- un puerto de salida en `core` **[Plan: `AssetLastEventReadPort`]** y su adaptador Mongo, con la agregación de §0.5 (`$match {streamId: {$in: ids}}`, `$sort {streamId: 1, sequence: -1}`, `$group {_id: "$streamId", occurredAt: {$first: "$occurredAt"}}`);
- `explain()` en el PR, con 200 lotes y muchos eventos por lote, antes y después;
- el `occurredAt` en texto se convierte con `Instant.parse`, nunca se compara como texto (R-R6B-5).

## 5. Tests (rojos primero)

| # | Test | Módulo |
|---|---|---|
| G9-1 | Lote con varios eventos → `lastEventAt` es el `occurredAt` del de mayor `sequence`, **aunque otro evento tenga un `occurredAt` mayor** (negativa del orden por tiempo; los eventos se escriben a mano en el Event Store con `occurredAt` desordenados) | `core` (Mongo real) |
| G9-2 | Página con varios lotes → (b) **ninguna** consulta además de las de rehidratación / (a) **una sola** agregación (se cuentan con un espía del `MongoTemplate` o del puerto) | `core` |
| G9-3 | Lote sin eventos legibles → el campo **se omite** del JSON (no `null`, no `"hace 0 días"`) | `api`, `app` |
| G9-4 | Por HTTP: el campo llega en ISO-8601 UTC con `Z`; la vista individual `GET /physical-assets/{assetRef}` **no** lo trae (negativa) | `app` |

## 6. Mutaciones

| # | Mutación | La mata |
|---|---|---|
| 1 | Elegir el evento de mayor `occurredAt` en lugar del de mayor `sequence` | G9-1 |
| 2 | Tomar el primer evento en lugar del último | G9-1 |
| 3 | Serializar `null` en lugar de omitir | G9-3 |
| 4 | (b) Volver a leer el stream para la fecha / (a) Una consulta por lote | G9-2 |

## 7. Pasos y evidencia

1. Resolver B-G9-1 (Carlos) y P-G9-1 (Manuel).
2. Rama desde el `develop` del momento.
3. Rojo con la salida literal; verde por capas (`core`, `api`, `app`); mutaciones.
4. Con (a), el `explain()` en el PR.
5. Documentos de §4.
6. `free -g` con al menos 4 GB y `ci-local.sh` en un clon limpio; autoauditoría contra E2; PR con Carlos.

## 8. Puntos para la aprobación de Manuel

| # | Qué | Recomendación |
|---|---|---|
| **P-G9-1** | (a) la agregación de §0.5 o (b) los eventos ya cargados | (b) |
| **P-G9-2** | `lastEventAt` solo en el listado, no en la vista individual | Sí, es lo que pide E2 |
| **P-G9-3** | Un registro propio para el ítem del listado | Sí: la vista individual no cambia de forma |
| **B-G9-1** | El estado de la Enmienda 1 de la ficha | Pregunta para Carlos |

## 9. Aprobación — Manuel, 2026-10-25, por la delegación de Carlos

**Texto de Manuel en la sesión:** "Plan aprobado — G-9 (#124) con P-G9-1 (b), Manuel, 2026-10-25". La delegación es el mensaje de Carlos del 2026-10-24 por WhatsApp (`decisiones-delegadas-2026-10.md`, nota del 2026-10-24).

- **P-G9-1: opción (b).** `lastEventAt` sale de los eventos que `loadStream` ya devuelve ordenados por `sequence`, sin consultas nuevas. Sustituye, para G-9, a la agregación de §0.5 del plan de ejecución. G9-2 cuenta "ninguna consulta además de las de rehidratación", y no hay `explain()` (§4.1 no se aplica). R-G9-1 (el N+1 que ya existía) queda solo registrado.
- **P-G9-2 y P-G9-3:** el texto de Manuel no los nombra. Se aplican tal como los recomienda §8, que es lo que aprueba "plan aprobado": el campo solo en el listado y un registro propio para el ítem. Si Manuel quiere otra cosa, lo dice en el PR de código.
- **B-G9-1 sigue abierto:** `ficha-R6-enmienda-1.md:3` dice PROPUESTA y #117 se fusionó sin review. Esta aprobación es del plan y **no** marca como aprobada la Enmienda 1 de la ficha (regla 2). La fuente citable del "qué" de G-9 es el mensaje literal de Carlos del 2026-10-24 (`decisiones-delegadas-2026-10.md`, nota del 2026-10-24, punto 5: "G-9 (lastEventAt? en la lista de lotes, Enmienda 1 §E2)"). El PR de código lo cita y deja la pregunta a Carlos abierta.

