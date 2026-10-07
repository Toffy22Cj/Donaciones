# Plan B-PROJ — Proyecciones con eventos reales (corrección)

**Estado:** **PROPUESTO** (2026-10-07). Necesita la aprobación de Carlos antes de cualquier código (regla 3.4). Redactado por el agente tras la revisión de la Enmienda 1 de ADR-029, que pidió tratar H-PROJ como corrección prioritaria (`fix/`).
**Origen:** hallazgo H-PROJ (`ADR-029-enmienda-1-campaignref.md` §6), ampliado aquí con un segundo defecto encontrado al verificarlo.
**Prioridad:** por delante de B5, B6 y B7. Afecta a lo que ya está en `develop` (B2), no solo a trabajo futuro.

---

## 1. Diagnóstico verificado (`develop` en `c5456a8`, 2026-10-07)

Rutas relativas a `core/src/main/java/com/traceability/core/`.

### Defecto S — origen de la secuencia (nuevo; anterior a la Fase 6)

| Hecho | Evidencia |
|---|---|
| El event store asigna a cada evento `expectedVersion + 1`, y todas las génesis pasan `expectedVersion = 0`: **el primer evento de un stream tiene `sequence = 1`** | `infrastructure/persistence/mongo/MongoEventStoreAdapter.java:55, 61`; `FundCommandService.java:80`; test `MongoEventStoreAdapterTest.java:144` (`assertEquals(1, …getSequence())`) |
| `DonationProjectionHandler` y `DonationAuditFactsHandler` toman `lastProcessed = -1` para un stream sin proyección y lanzan `SequenceGapException` si `sequence > lastProcessed + 1`: **esperan la génesis en 0** | `application/projection/DonationProjectionHandler.java:69, 74-76, 163-168`; `DonationAuditFactsHandler.java:77-84, 135-141` |
| Los documentos dicen 0: "El evento génesis siempre tiene sequence = 0" | `Promt-Contexto.md:644`; `golden-path.md:34`; `estado-fase5.md:50, 222`; `plan-ejecucion-agentes-fase5.md:74, 112, 162` |
| Todos los tests de proyección construyen los eventos a mano con génesis en 0 y `schemaVersion "1.0"` | `DonationProjectionIntegrationTest.java:124, 132`; `DonationAuditFactsIntegrationTest.java:101` |

**Consecuencia:** **ningún** stream escrito por los comandos reales se proyecta, sea cual sea la versión del payload. La génesis (secuencia 1) da `SequenceGapException`, pasa a reintento y, a las 4 horas, a cuarentena (ADR-042). Como la proyección nunca llega a existir, la cuarentena no pausa nada y `resumeProjection(fundId)` falla con "Projection not found". Todos los eventos siguientes del stream corren la misma suerte.

### Defecto V — payloads v2 (H-PROJ original)

| Manejador | Payloads v2 que no reconoce | Efecto | Evidencia |
|---|---|---|---|
| `DonationProjectionHandler` (fondos) | `FundRegisteredV2Payload`, `FundsClearedV2Payload` | Avanza la secuencia sin proyectar importe, moneda ni `campaignRef` | `DonationProjectionHandler.java:95-135` |
| `DonationProjectionHandler` (activos) y `resolveProjectionId` | `AssetRegisteredV2Payload` | `MissingDependencyException`: el activo nunca se proyecta ni entra en `asset_index` | `:174, 216, 225-255` |
| `DonationProjectionHandler.appendAssetHistory` | `AssetRegisteredV2Payload` | Añade a `asset_history` una transición con estado, ubicación y custodio a `null` | `:257-300` |
| `ProjectionEventSource.resolveProjectionId` | `AssetRegisteredV2Payload` | El reintento se guarda con `projectionId = null`: su cuarentena no se puede liberar | `infrastructure/projection/ProjectionEventSource.java:213-230` |
| `DonationAuditFactsHandler` | `AssetRegisteredV2Payload` (vía `resolveFundId`) | `MissingDependencyException` | `DonationAuditFactsHandler.java:121-123, 227-247` |
| `PendingAllocationProjectionHandler` | — (solo asignaciones, sin v2) | Funciona: no comprueba secuencia | `PendingAllocationProjectionHandler.java:41-73` |

Los consumidores de `crypto` y del anclaje solo leen `eventHash` y secuencias, no payloads: no les afecta (`MongoUnanchoredEventAdapter.java:25-110`).

### Otros hechos que condicionan la corrección

- **No existe `ASSET_REGISTERED` del hijo de una división.** `DonationProjectionHandler.java:192` lo da por supuesto (D-SPLIT/B1-bis).
- **Camino B sin proyección posible:** la `DonationProjection` se indexa por `fundId` y un activo en especie no tiene `Fund` ni `allocationId`. Si se extendiera sin más la rama v1 a v2, la consulta `allocations.allocationId = null` (`DonationProjectionHandler.java:236`) **casaría con cualquier proyección sin asignaciones** (semántica de `null` en MongoDB): un activo en especie acabaría en la donación de otra persona.
- **Reconstrucción existente pero incompleta.** `ProjectionRebuildService.rebuildAll()` (`application/projection/ProjectionRebuildService.java:40-73`):
  - solo la usan tests; no tiene operación JMX ni llamador en producción;
  - borra `donation_projections`, `asset_history` y `asset_index`, pero no `donation_audit_facts` ni `quarantined_projections`;
  - lee todo el event store ordenado por `occurredAt` global, no por stream y secuencia;
  - solo invoca a `DonationProjectionHandler`;
  - ante la primera excepción aborta y **deja parada** la fuente de eventos (`start()` no se alcanza).
- **Borrar las colecciones y el *checkpoint* no reproduce nada:** sin *resume token*, el *change stream* empieza en "ahora" (`ProjectionEventSource.java:116-118`).
- La cuarentena guarda el payload, pero no `aggregateType`, que `resumeProjection` infiere de las claves del payload (`ProjectionRetryScheduler.java:158-177`).
- **Ningún test** pasa un comando real por el *change stream* hasta una proyección (`core/src/test/.../application/command/`).
- `EventPayloadRegistry` no ofrece ninguna forma pública de enumerar sus entradas (`application/event/EventPayloadRegistry.java:20-26, 55-73`).

### Impacto en el golden path (`golden-path.md` §8)

El seguimiento público (`GET /api/v1/donations/tracking`, `DonationReadAdapter.java:21-24`) devuelve **404** para cualquier donación creada por los comandos reales, y el historial del activo también (`PublicAssetHistoryController.java:54, 67`). La narrativa individual no tiene hechos (`AuditFactsPortImpl.java:21-24`). Caen los criterios **3, 4, 6, 7, 8, 9, 13 y 14**, y los 15–19 de la división.

## 2. Decisión previa: el origen de la secuencia (D-SEQ)

| Opción | Qué implica |
|---|---|
| **(a) Recomendada — la génesis es la secuencia 1 (lo que hace el código).** Los manejadores toman "nada procesado" como 0, y se enmiendan los documentos que dicen 0 | No toca el event store, ni los hashes (que incluyen la secuencia) ni lo ya anclado. Cambio localizado en dos manejadores |
| (b) La génesis es la secuencia 0 (lo que dicen los documentos). Se cambia el event store | Rompe la coherencia de cualquier stream ya escrito y de sus hashes y anclajes. Solo sería posible sin datos reales en ningún entorno, y aun así cambia la semántica de `expectedVersion` para todos los llamadores |

Con (a) se corrige `golden-path.md:34` y se anota en `Promt-Contexto.md:644` (documento de contexto histórico) que la regla vigente es "génesis = 1, cada evento = anterior + 1, sin huecos".

## 3. Alcance de la corrección

Sin dependencias ni mecanismos nuevos: se corrige el comportamiento de piezas existentes (ADR-010, ADR-011, ADR-017, ADR-042). Si Carlos considera que la reconstrucción operativa (§3.3) es un mecanismo nuevo de recuperación, se registra como enmienda de ADR-042 antes de fusionar (regla 3.5) — ver Q4.

### 3.1 Tests primero (deben fallar antes de la corrección)

1. **Regresión de punta a punta (Testcontainers, *change stream* real):** comandos reales → proyecciones.
   - `clearFundsGenesis` → `donation_projections` con importe, moneda y `campaignRef`; `donation_audit_facts` creado.
   - `registerFund` + `requestAllocation` + `confirmAllocation` → asignación proyectada.
   - Camino A (`registerPhysicalAsset`) → `logistics`, `asset_index` y `asset_history` con estados reales; después `dispatch`/`receive`/`deliverAsset`. `PhysicalAssetCommandService` solo expone `deliverAsset` (D-ASSET: `dispatch`/`receive` no tienen método público), así que esos dos eventos se escriben en el test con el agregado y `TransactionalEventPublisher`, como hacen hoy los tests de `core`; cuando D-ASSET añada los métodos, el test pasará a usarlos.
   - Nada en `quarantined_projections` al final.
   - Lectura por `DonationReadPort` (la del seguimiento) con los campos esperados.
2. **Test de contrato de versiones:** por cada `(eventType, schemaVersion)` registrado en `EventPayloadRegistry`, cada manejador declara si lo **procesa** o lo **ignora de forma explícita**; un payload sin declarar hace fallar el test. Requiere:
   - en `EventPayloadRegistry`, un método público de solo lectura que enumere las entradas registradas;
   - en `ProjectionEventHandler` (interfaz interna de `core`), la declaración de payloads procesados e ignorados de cada manejador.
   Así la versión 3 de D-CAMPAIGN no puede repetir el fallo sin que el test lo detecte.
3. **Origen de la secuencia:** un stream que empieza en 1 se proyecta; un hueco real (1 → 3) sigue dando `SequenceGapException`.

### 3.2 Corrección (`fix/b-proj-proyecciones-v2`, PR 1)

- **D-SEQ (a):** "nada procesado" = 0 en `DonationProjectionHandler` y `DonationAuditFactsHandler`, y la condición de "documento nuevo" deja de depender de que la secuencia valga 0 (`DonationProjectionHandler.java:138`; `DonationAuditFactsHandler.java:110`).
- **v2:** `FundRegisteredV2Payload`, `FundsClearedV2Payload` y `AssetRegisteredV2Payload` en los dos manejadores, en `appendAssetHistory`, en los dos `resolveProjectionId` (manejador y `ProjectionEventSource`) y en `resolveFundId` de la auditoría.
- **Camino B — ignorado de forma explícita, sin cuarentena:** un `ASSET_REGISTERED` sin `allocationId` ni `parentAssetRef` (y los eventos posteriores de ese activo) se ignora con un log y una métrica, en vez de ir a reintento y cuarentena. No se pierde nada: la proyección es reconstruible y, cuando se decida dónde se proyecta la donación en especie (Q3 de la Enmienda 1 de ADR-029, con C5 de ADR-040), una reconstrucción la incorporará. Se elimina también el riesgo de la consulta `allocationId = null`.
- **Sin cambios** en el event store, los payloads, los hashes ni el anclaje.

### 3.3 Recuperación de lo acumulado (PR 2)

Los eventos escritos hasta hoy están en `quarantined_projections` o pendientes de reintento, y sus proyecciones no existen. Como las proyecciones son reconstruibles (documento maestro, principio rector), la recuperación es una **reconstrucción**, no una liberación evento a evento (`resumeProjection` no puede liberar proyecciones que nunca existieron).

`ProjectionRebuildService` se corrige así:
- reconstruye **todos** los manejadores con estado (`DonationProjectionHandler`, `DonationAuditFactsHandler`, `PendingAllocationProjectionHandler`);
- vacía también `donation_audit_facts`, `pending_allocations` y `quarantined_projections` (estos últimos quedan superados por la reconstrucción);
- recorre el event store en **dos pasadas**, primero los streams `Fund` y después los `PhysicalAsset`, cada una ordenada por `(streamId, sequence)`, porque un activo necesita la asignación de su `Fund` ya proyectada;
- un evento que falle se registra y la reconstrucción continúa, con un resumen al final;
- la fuente de eventos se reinicia siempre (`finally`), con el *resume token* tomado antes de empezar, de modo que lo escrito durante la reconstrucción se procesa después (idempotencia por secuencia);
- se expone como operación JMX `rebuildProjections()`, con el mismo patrón que `resumeProjection` (ADR-042) y `BlockchainAdminOperationsService` (ADR-022).

**Tests:**
- reconstrucción desde un event store con streams v1 y v2, génesis en 1 y una cuarentena previa;
- un evento que falla no aborta la reconstrucción ni deja parada la fuente;
- eventos escritos durante la reconstrucción se proyectan después.

**Procedimiento operativo** (documentado en el PR): parar el tráfico de escritura si es posible, ejecutar `rebuildProjections()` por JMX y comprobar que `quarantined_projections` queda vacío y que el seguimiento de una donación conocida devuelve importes.

### 3.4 Fuera de alcance

- La versión 3 de los payloads: entra con la implementación de D-CAMPAIGN, y el test de contrato de §3.1 obliga a cubrirla.
- Dónde se proyecta un activo del Camino B (Q3 de la Enmienda 1 de ADR-029).
- El `ASSET_REGISTERED` del hijo de una división (B1-bis).
- Guardar `aggregateType` en la cuarentena y hacer configurable la ventana de 4 horas (deuda de ADR-042; se registra, no se toca).

## 4. Definición de hecho

- Los tests de §3.1 fallan antes de la corrección (salida de Surefire en el PR) y pasan después.
- `mvn clean test` del reactor completo, con la salida literal de Surefire (regla 2.3).
- Mutaciones comprobadas: volver a "nada procesado = -1" y quitar el soporte de `FundsClearedV2Payload` hacen fallar los tests.
- `golden-path.md:34` corregido según D-SEQ.
- Aprobación de cada PR por una persona distinta del autor (regla 3.2).

## 5. Preguntas para Carlos

| # | Pregunta | Recomendación |
|---|---|---|
| Q1 | D-SEQ: ¿la génesis es la secuencia 1 (código) o 0 (documentos)? | (a) 1, sin tocar el event store |
| Q2 | ¿Hay algún entorno (demo, *staging*) con datos reales escritos por los comandos? | Determina si el PR 2 es urgente o puede ir después del PR 1 |
| Q3 | Camino B: ¿ignorarlo de forma explícita hasta decidir su proyección (§3.2)? | Sí |
| Q4 | ¿La reconstrucción operativa por JMX (§3.3) es corrección de lo existente o un mecanismo nuevo que requiere enmendar ADR-042? | Corrección de lo existente; si Carlos opina lo contrario, la enmienda va antes de fusionar el PR 2 |
| Q5 | ¿Dos PR (corrección y recuperación) o uno? | Dos: el PR 1 desbloquea el resto y es pequeño |
