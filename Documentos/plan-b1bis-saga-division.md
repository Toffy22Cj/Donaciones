# Plan B1-bis — Saga de la división: crear el hijo, compensar y barrera atómica

**Estado:** **HECHO** (`feat/b1bis-split-saga`, 2026-10-07): reactor completo en verde sobre `ff7bfbc` y mutaciones (`evidencia-fase6/b1bis-saga-division-ff7bfbc-2026-10-07.txt`). **APROBADO — Carlos, 2026-10-07** (Q1–Q4 con precisiones, §7). **Condición de merge cumplida:** `ADR-008-enmienda-1-recuperacion-coordinador-sagas.md` **APROBADA — Carlos, 2026-10-07**, con los añadidos D5 (resolución manual) y D7 (sin migración).
**Origen:** `propuesta-d-split.md` (**APROBADO — Carlos, 2026-10-07**, S1–S8 y precisiones P1–P4).
**Desbloquea:** criterios 15–18 del golden path (el 19 depende además de B5) y el endpoint de división de B6.
**Revisión:** cubierto por la excepción a la regla 3.2. La evidencia de tests sustituye al segundo revisor.

---

## 1. Hechos del código que condicionan el plan (`develop` tras #48)

Rutas relativas a `core/src/main/java/com/traceability/core/`.

| Hecho | Evidencia | Consecuencia |
|---|---|---|
| `appendAndOutbox` llama a `tryClaim(commandId)` **dentro de la misma transacción** que el append y el outbox. Si el reclamo ya existe, devuelve `false` sin escribir nada | `application/service/TransactionalEventPublisher.java:29-45` | Es la base de la barrera: el reclamo y su efecto se confirman juntos o no se confirma ninguno |
| `tryClaim` es un *upsert* sobre `processed_commands` (`_id = commandId`) que solo guarda `processedAt`. Un conflicto de escritura concurrente da `ConcurrencyConflictException` | `infrastructure/persistence/mongo/MongoProcessedCommandAdapter.java:30-40` | Hay que **guardar qué ganó** (`CHILD_CREATED` o `COMPENSATED`). El reintento de `CommandRetryTemplate` resuelve el conflicto: el perdedor, al reintentar, encuentra el reclamo hecho |
| **El coordinador revisa la ventana antes de ejecutar.** Pasada la ventana, llama a `compensate` y marca `QUARANTINED` **aunque `compensate` lance una excepción**; esa compensación no se reintenta nunca | `application/saga/OutboxSagaCoordinator.java:45-66` | **Hallazgo H1:** un fallo transitorio en la compensación (p. ej. un conflicto de versión del padre) deja la cantidad perdida para siempre: ni hijo ni reintegro. Ver Q1 |
| `fetchPendingMessages` filtra `PENDING` y `nextRetryAt <= now`, sin bloqueo; `update` sobrescribe sin comprobar la versión | `infrastructure/persistence/mongo/MongoOutboxPort.java:33-36` | Con más de una instancia, `execute` y `compensate` del mismo mensaje pueden correr a la vez. **La barrera lo hace inofensivo**; el bloqueo del coordinador queda fuera de alcance |
| En una sola instancia, `@Scheduled(fixedDelay)` usa un solo hilo: `execute` y `compensate` no se solapan | `OutboxSagaCoordinator.java:36` | La concurrencia real exige varias instancias o una ejecución manual. El test de concurrencia de la DoD las simula llamando a la política desde dos hilos |
| `compensateSplit` rechaza un padre `DELIVERED` (`AssetTerminalStateException`) y un `childAssetId` desconocido. Los dos son fallos permanentes | `domain/physicalasset/PhysicalAsset.java:234-249` | **Hallazgo H2:** si el padre se entrega antes de que la saga resuelva, compensar es imposible. Ver Q2 |
| `assetType`, `allocationId` y `sourceAllocationId` solo se asignan al aplicar el registro (v1, v2, v3). `currentLocation` y `custodianRef` cambian con el despacho, la recepción, la transferencia de custodia y la entrega | `PhysicalAsset.java:278-370` | Confirma la regla P4: del padre solo se leen esos tres; la ubicación y el custodio salen del `ASSET_SPLIT` |
| El agregado ya guarda el estado previo **por hijo** (`splitsBeforeCompensation`) y las compensaciones hechas (`compensatedSplits`) | `PhysicalAsset.java:47-48,343-360` | La proyección copia ese modelo (S6) |
| `splitPhysicalAsset` devuelve `void`, genera `childAssetId` con `UUID.randomUUID()` dentro del reintento y no escribe outbox | `application/command/PhysicalAssetCommandService.java:304-337` | S1 y S2 |
| `saga.quarantine.window` vale `PT24H` por defecto y no está configurada en ningún `application.yml` | `OutboxSagaCoordinator.java:29` | S5: el valor por defecto pasa a `PT4H` y afecta también a `ASSET_REGISTRATION_SAGA` (decidido en D-SPLIT Q2) |

## 2. La barrera atómica, paso a paso (S4, P2)

**Clave del reclamo:** `SPLIT_RESOLUTION:{childAssetId}`. Es la misma para crear el hijo y para compensar, y se usa como `commandId` de `appendAndOutbox` en las dos ramas.

| Rama | Transacción única (`appendAndOutbox`) | Si encuentra el reclamo ya hecho |
|---|---|---|
| `execute` (crear el hijo) | `tryClaim(clave, CHILD_CREATED)` + génesis `ASSET_REGISTERED` 3.0 del hijo (stream nuevo, `expectedVersion = 0`) | Lee el resultado: `CHILD_CREATED` → éxito idempotente (mensaje `COMPLETED`); `COMPENSATED` → éxito sin efecto con WARN (mensaje `COMPLETED`) |
| `compensate` | `tryClaim(clave, COMPENSATED)` + `ASSET_SPLIT_COMPENSATED` en el stream del padre (`expectedVersion` = versión leída) | `COMPENSATED` → idempotente; `CHILD_CREATED` → no compensa, WARN |

**Por qué basta con esto:**
- El reclamo es un documento con `_id` único. Dos transacciones que intentan crearlo a la vez no pueden confirmarse las dos: una gana y la otra aborta por conflicto de escritura (`ConcurrencyConflictException`).
- `CommandRetryTemplate` reintenta la que aborta. En el reintento, `tryClaim` devuelve `false` y la rama lee el resultado guardado.
- Como el efecto va en la misma transacción que el reclamo, **nunca hay hijo y compensación a la vez**, ni efecto sin reclamo.
- `expectedVersion` no protege aquí, porque cada rama escribe en un stream distinto (hijo y padre). Por eso hace falta el reclamo común.

**Cambio en el puerto (sin romper a los llamadores actuales):**
- `ProcessedCommandRepositoryPort`: se añaden `boolean tryClaim(String commandId, String outcome)` y `Optional<String> findOutcome(String commandId)`. El `tryClaim(String)` actual no cambia.
- `TransactionalEventPublisher.appendAndOutbox`: se añade una sobrecarga con `claimOutcome`. La firma actual no cambia.

**Desviación consciente de ADR-033**, ya aprobada en D-SPLIT: en esta saga el `commandId` no es `messageId` ni `messageId-comp`, sino la clave común. Se añade una línea a ADR-033 como precisión para sagas con compensación competidora.

## 3. Cambios

### 3.1 Dominio (`domain/physicalasset`)
- **`SplitChildIds.of(parentAssetId, commandId)`**: UUID v5 (RFC 9562 §5.5, SHA-1) con un espacio de nombres constante `NS_ASSET_SPLIT` (P1). Java solo trae la v3, así que la v5 se implementa aquí (unas 15 líneas), con un test contra un vector conocido.
- **`PhysicalAsset.registerSplitChild(...)`**: fábrica nueva. Emite `ASSET_REGISTERED` 3.0 con:
  - la cantidad y la unidad, la ubicación y el custodio, y las referencias heredadas (`rootAssetRef`, `organizationRef`, `donorRef`, `donationRef`, `campaignRef`), todo del payload de `ASSET_SPLIT`;
  - `parentAssetRef`;
  - `assetType` y `sourceAllocationId` del padre;
  - `allocationId = null`.

  No exige que `donorRef`/`donationRef` sean nulos o no nulos: el hijo hereda lo que tenga el padre, de cualquier camino.
- **Getters de solo lectura:** `getAssetType()`, `getAllocationId()`, `getSourceAllocationId()`, y un `findSplit(childAssetId)` que devuelve el `ASSET_SPLIT` que generó ese hijo, leyendo la v1, la v2 y la v3.
- **`compensateSplit`:** la cantidad reintegrada debe ser **igual** a la extraída en esa división; si no, `InvalidCompensationQuantityException`.

### 3.2 Aplicación (`application/command`, `application/saga`)
- **`splitPhysicalAsset`:**
  - devuelve `String childAssetId`;
  - calcula el id **fuera** del reintento con `SplitChildIds.of`;
  - escribe en la misma transacción `ASSET_SPLIT` y el `OutboxMessage` `ASSET_SPLIT_SAGA` (`sourceAggregateId = parentAssetId`, `correlationId = childAssetId`, payload `{"parentAssetId","childAssetId"}`, `messageId` = UUID nuevo).
  - Si el `commandId` ya está procesado, devuelve **el mismo** `childAssetId`: es determinista, no hace falta guardarlo.
- **`SplitPhysicalAssetSagaPolicy`** (`getSagaType() = "ASSET_SPLIT_SAGA"`), con dos métodos de servicio nuevos en `PhysicalAssetCommandService`, ambos dentro de `CommandRetryTemplate`:
  - `createSplitChild(parentAssetId, childAssetId)`: rehidrata el padre, busca su `ASSET_SPLIT` y aplica la barrera (§2). El actor es `SystemActor("SplitPhysicalAssetSagaPolicy")`, sin nueva autorización.
  - `compensateSplitChild(parentAssetId, childAssetId)`: igual, con la cantidad extraída de ese `ASSET_SPLIT`.
- **`SplitResolutionReadPort`** (nuevo, solo lectura) para el recurso de estado de S7, que expone B6. `findStatus(parentAssetId, childAssetId)` devuelve un **estado nombrado** (`SplitResolutionStatus`, Q3), o vacío si el padre no tiene ese `ASSET_SPLIT`:
  - `PENDING`: sin reclamo y el mensaje de la saga no está en cuarentena;
  - `CHILD_CREATED`;
  - `COMPENSATED`;
  - `UNRESOLVED`: sin reclamo y el mensaje está `QUARANTINED`;
  - **`RESOLVED_MANUALLY`**: reclamo con ese resultado, tomado por `markResolvedManually` (enmienda D5).
- **Resolución de la división** (`SplitPhysicalAssetSagaPolicy.compensate`, enmienda D4, Q2): en este orden, bajo la barrera:
  1. si hay reclamo, termina con éxito según su resultado;
  2. compensar;
  3. si el padre está `DELIVERED`, **crear el hijo igualmente** (recuperación hacia delante);
  4. solo si crear el hijo también es imposible, `PermanentSagaFailureException`.
- **Coordinador** (Q1, enmienda D1–D5):
  - dos fases, ejecución y resolución, con 4 h cada una;
  - `PermanentSagaFailureException` frente a transitorio;
  - estado `RESOLVED`;
  - `QUARANTINED` solo para lo que necesita a una persona, con ERROR y contador;
  - el documento de outbox gana `resolutionStartedAt`, `lastFailureReason` y `manualNote`.
- **Reloj inyectable en el coordinador** (`Clock`, por defecto el del sistema): hoy usa `Instant.now()`, y los tests 16 y 18 necesitan fijar el instante a ambos lados de las 4 h.
- **MBean `SagaOutboxAdministration`** (enmienda D5): `quarantinedCount`, `listQuarantined`, `retryResolution(messageId, operator, note)` y `markResolvedManually(messageId, operator, note)`.
  - Las dos operaciones son condicionales (solo actúan sobre `QUARANTINED`) y exigen `operator` y `note`.
  - Escriben `saga_manual_actions` en la misma transacción que el cambio de estado.
  - `SagaPolicy.onManualResolution` es un método por defecto. La división lo sobrescribe para tomar el reclamo con `RESOLVED_MANUALLY`.
- **Ventana:** el valor por defecto de `saga.quarantine.window` pasa a `PT4H` (S5).

### 3.3 Proyecciones (S6)
- `DonationProjectionHandler`, al procesar `ASSET_SPLIT_COMPENSATED`: restaura `lifecycleStatus` **solo si el padre está `DEPLETED`**, con el estado previo guardado para **ese** `childAssetId`.
- La proyección guarda `splitsBeforeCompensation.{childAssetId}`, tomado de cada `ASSET_SPLIT`, en lugar del campo único `statusBeforeSplit`. Ninguna versión de evento cambia.
- El registro del hijo ya se proyecta por B-PROJ (se resuelve a través del `asset_index` del padre). Hay que comprobarlo con el hijo **real** creado por la saga, no con uno construido a mano.

### 3.4 Documentos del mismo PR
- `api-contract-matrix.md:54`: sustituir "assets resultantes" por `202` + `{parentAssetRef, childAssetRef, status}` + `Location`, y añadir el recurso de estado como contrato de B6.
- `documento-maestro-proyecto.md`: en la tabla de comandos y en `:288`, la política deja de estar pendiente; §7.2 queda con la ventana de 4 h ya alineada con el código.
- ADR-033: la precisión de la clave común.
- `estado-fase6.md` y `plan-cierre-fase6-codigo.md`: B1-bis hecho, **después** de la evidencia (regla 2.4).

## 4. Tests (definición de hecho)

**Primero los que deben fallar**, con la salida literal de Surefire en el PR.

Los 14 tests de `propuesta-d-split.md` §4, con estas concreciones:

| # | Caso | Tipo |
|---|---|---|
| 1 | `ASSET_SPLIT` y el mensaje de la saga en la misma transacción; si el outbox falla (con `@MockitoSpyBean`), no queda ninguno de los dos | integración, Mongo |
| 2 | El mismo `commandId` sobre el mismo padre da el mismo hijo; sobre otro padre, otro hijo; la v5 coincide con un vector conocido | unitario + integración |
| 3–4 | El hijo nace con la cantidad, la herencia completa (incluido `campaignRef`) y `sourceAllocationId`; el padre se reduce (criterios 15 y 16) | integración |
| 5 | Una nueva ejecución tras crear el hijo no lo duplica; dos `execute` concurrentes crean un solo hijo | integración, dos hilos |
| 6 | Compensar sin hijo reintegra la cantidad extraída; un padre `DEPLETED` vuelve a su estado anterior | integración |
| **7** | **Concurrencia de la barrera:** `execute` y `compensate` de la misma división lanzados a la vez desde dos hilos con un `CountDownLatch`, **20 repeticiones**. En cada una, exactamente uno de los dos efectos (o hijo, o `ASSET_SPLIT_COMPENSATED`), nunca los dos ni ninguno, y el reclamo guarda el ganador | integración, Mongo replica set |
| 8 | `compensate` tras `CHILD_CREATED` no compensa; `execute` tras `COMPENSATED` no crea el hijo y el mensaje queda `COMPLETED` | integración |
| 9 | `compensateSplit` con una cantidad distinta de la extraída se rechaza | unitario |
| 10 | De punta a punta con el *change stream*: el hijo aparece en `logistics` con `parentAssetRef` y `campaignRef`, y padre e hijo llegan a `DELIVERED` por separado (criterio 17) | integración |
| 11 | Si el padre se despacha a otra ubicación entre la división y la saga, el hijo nace con la ubicación y el custodio del `ASSET_SPLIT` (P4) | integración |
| 12 | Proyección: una compensación no "resucita" un padre que no estaba `DEPLETED`; dos divisiones y una compensación restauran el estado correcto | integración |
| 13 | Ventana por defecto `PT4H` | unitario |
| 14 | `SplitResolutionReadPort`: `PENDING` → `CHILD_CREATED`; un par padre/hijo inexistente da vacío | integración |

**Además, por las decisiones de Q1 y Q2 (enmienda §5):**

| # | Caso | Tipo |
|---|---|---|
| 15 | Coordinador: fallo transitorio en resolución → `PENDING`; en el ciclo siguiente se resuelve → `RESOLVED` | unitario |
| 16 | Coordinador: pasada la ventana de resolución (4 h) → `QUARANTINED` con ERROR y contador | unitario |
| 17 | Coordinador: fallo permanente en ejecución → resolución inmediata, sin esperar 4 h; fallo permanente en resolución → `QUARANTINED` | unitario |
| **18** | **Efecto de las 4 h sobre la saga de registro existente**, con el valor por defecto (contexto de Spring sin la propiedad): a las 3 h 59 min reintenta `confirmAllocation`; a las 4 h 1 min llama a `reverseAllocation`; un `reverseAllocation` que falla una vez se reintenta y el mensaje termina `RESOLVED` (antes quedaba `QUARANTINED` sin compensar) | integración |
| 19 | JMX: `retryResolution` vuelve a resolver con ventana nueva; `markResolvedManually` pasa a `RESOLVED` con nota; las dos rechazan un mensaje que no está `QUARANTINED`; `quarantinedCount` y `listQuarantined` reflejan el estado | integración |
| **20** | **Recuperación hacia delante (Q2):** padre `DELIVERED` y ejecución vencida → la resolución **crea el hijo** (reclamo `CHILD_CREATED`), mensaje `RESOLVED`, estado `CHILD_CREATED` | integración |
| 21 | Padre `DELIVERED` y creación del hijo también imposible → `QUARANTINED`, estado `UNRESOLVED`; tras `retryResolution`, si la causa desapareció, se crea el hijo | integración |
| 22 | **Resolución manual de una división:** `markResolvedManually` → estado `RESOLVED_MANUALLY`, mensaje `RESOLVED`, registro en `saga_manual_actions` con operador, nota y fecha; después, `createSplitChild` y `compensateSplitChild` no tienen efecto; sin operador o sin nota, o sobre un mensaje no `QUARANTINED`, o con el reclamo ya tomado → rechazo sin ningún cambio | integración |

**Mutaciones** (cada una debe hacer fallar algún test):
- quitar el reclamo común (cada rama con su propio `commandId`): debe fallar el test 7;
- derivar el id solo del `commandId`;
- generar el id dentro del reintento;
- tomar la ubicación del padre actual;
- restaurar el estado en la proyección sin comprobar `DEPLETED`;
- aceptar en `compensateSplit` una cantidad distinta de la extraída;
- no guardar el resultado en el reclamo;
- que `compensate` ignore `CHILD_CREATED`;
- volver a marcar `QUARANTINED` tras una compensación fallida (H1): deben fallar los tests 15 y 18;
- quitar la ventana de resolución: debe fallar el 16;
- tratar `PermanentSagaFailureException` como transitorio: debe fallar el 17;
- quitar la recuperación hacia delante: debe fallar el 20;
- dejar el valor por defecto de la ventana en `PT24H`: deben fallar el 13 y el 18;
- que `markResolvedManually` no tome el reclamo de la división: debe fallar el 22;
- que la resolución manual no sea condicional al estado `QUARANTINED`: debe fallar el 22.

**Verificación:** `mvn clean install -fae` del reactor completo, con la salida literal en el PR y el archivo de evidencia en `evidencia-fase6/`.

## 5. Forma de entrega
- **Antes:** el PR de documentación con la enmienda de ADR-007/008 (este mismo). El PR de código **no se fusiona** hasta que la enmienda esté aprobada.
- **Un PR, `feat/b1bis-split-saga`**, que solo toca `core`, más documentos.
- **Commits:** tests que fallan con esqueleto → dominio → aplicación y barrera → proyección → coordinador y MBean → documentos → evidencia y paso a HECHO.

## 6. Fuera de alcance
- El endpoint HTTP de división y el del recurso de estado (B6).
- El bloqueo del coordinador entre instancias: la barrera garantiza la corrección aunque falte.
- La proyección de los hijos del Camino B (Q3 de la Enmienda 1 de ADR-029).
- La demostración de la compensación (golden path §3: se prueba, no se demuestra).

## 7. Decisiones de Carlos (2026-10-07)

| # | Pregunta | Decisión |
|---|---|---|
| Q1 | H1: la compensación fallida no se reintenta | **Sí**, distinguiendo permanente de transitorio, con **ventana máxima** de reintento (4 h, como ADR-042), **salida manual por JMX** de la cuarentena (regla 2.6) y **enmienda de ADR-007/008 aprobada antes del merge**, en su propio PR de documentación (`ADR-008-enmienda-1-recuperacion-coordinador-sagas.md`) |
| Q2 | H2: el padre se entrega antes de resolver | **Recuperar hacia delante:** la resolución sigue intentando crear el hijo aunque el padre esté `DELIVERED`, porque la cantidad ya se extrajo. `UNRESOLVED` solo si además eso falla de forma permanente, con salida manual. **Alternativa descartada** y registrada en la enmienda: impedir entregar el padre con divisiones pendientes (exigiría un evento de resolución en el padre) |
| Q3 | Recurso de estado | **Sí:** el puerto en `core` ahora y el endpoint en B6, con estados nombrados `PENDING`, `CHILD_CREATED`, `COMPENSATED` y `UNRESOLVED` |
| Q4 | Un PR | **Sí, un PR de `core`**; la enmienda va antes, en su PR de documentación |
| — | Comprobación pedida | Efecto de las 4 h sobre la saga de registro existente: enmienda D6 y test 18 |
