# Propuesta D-SPLIT — Saga del hijo de la división, compensación y respuesta de la API

**Estado:** **APROBADO — Carlos, 2026-10-07**, con las precisiones P1–P4 de §5 ya incorporadas al texto. Sin código. B1-bis necesita su plan aprobado (regla 3.4).
**Origen:** D-SPLIT de `plan-cierre-fase6-codigo.md` (B0). "`ASSET_SPLIT` entra en la demo — Carlos, 2026-10-07".
**Desbloquea:** B1-bis, y con él los criterios 15–18 del golden path; el 19 depende además de B5.

---

## 1. Lo que ya está decidido y no se reabre

| Decisión | Fuente |
|---|---|
| El padre sobrevive si le queda cantidad mayor que 0. Si llega a 0 pasa a `DEPLETED`, que es terminal. La división no cambia `lifecycleStatus` mientras quede cantidad | ADR-005 (catálogo, `documento-maestro-proyecto.md:146`) |
| La comunicación entre streams padre → hijo va por el `OutboxSagaCoordinator` genérico, con una `SagaPolicy` propia | ADR-007 (`:151`); `:288` y §7.2 |
| Toda saga tiene camino de compensación. El fallo permanente dispara `ASSET_SPLIT_COMPENSATED` en el padre, que puede "resucitar" un padre `DEPLETED` | ADR-008 (`:152`) |
| La compensación referencia `childAssetId`, y el agregado rechaza una segunda compensación | ADR-009 (`:153`) |
| El hijo hereda `organizationRef`, `donorRef` y `donationRef` | ADR-029 §3 |
| El hijo hereda `campaignRef` a través de `AssetSplitV3Payload`, y la saga **no lo recalcula** | ADR-029 Enmienda 1, D4 (APROBADA) |
| El hijo nace de forma **asíncrona** y el guion de la demo espera a que exista | `golden-path.md` 4B; plan de cierre `:144` |
| La compensación de la división se implementa y se prueba, pero no se demuestra | `golden-path.md` §3 |

## 2. Hechos verificados en el código (`develop` tras #45)

Rutas relativas a `core/src/main/java/com/traceability/core/`.

| Hecho | Evidencia |
|---|---|
| `splitPhysicalAsset(commandId, assetId, splitQuantity, actorRef)` devuelve `void`, y escribe `ASSET_SPLIT` 3.0 (más `ASSET_DEPLETED` si la cantidad llega a 0) **sin mensaje de outbox** (`null`). **El stream hijo nunca se crea** | `application/command/PhysicalAssetCommandService.java:304-337` |
| `childAssetId = UUID.randomUUID()` se genera **dentro** del reintento: cada intento recibe otro id. Un comando repetido no puede devolver el mismo hijo | `:324` |
| `AssetSplitV3Payload` lleva `childAssetId`, cantidades, `statusBeforeSplit`, ubicación y custodio del padre, `rootAssetRef`, `organizationRef`, `donorRef`, `donationRef` y `campaignRef`. **No lleva** `assetType`, `allocationId` ni `sourceAllocationId` | `domain/physicalasset/payloads/AssetSplitV3Payload.java` |
| No hay ninguna fábrica para el hijo: `register` fuerza `donationRef = null` (incompatible con un padre del Camino B) y `create` exige `donorRef`/`donationRef` (incompatible con un padre del Camino A) | `domain/physicalasset/PhysicalAsset.java:62-156` |
| `compensateSplit` existe, protege contra la doble compensación (`DuplicateCompensationException`) y "resucita" un padre `DEPLETED`. **Ningún caso de uso ni saga lo invoca**. No comprueba que la cantidad reintegrada sea la extraída | `:234-249` |
| **Coordinador:** las políticas se registran por `sagaType`. **No hay número máximo de reintentos**: un mensaje se da por fallido cuando vence la ventana de cuarentena, que en el código es `PT24H` (`saga.quarantine.window`); el documento maestro §7.2 dice **4 h**. Entonces se llama a `compensate` y el mensaje pasa a `QUARANTINED`. El *backoff* es `2^n × 15 s` | `application/saga/OutboxSagaCoordinator.java:29-90` |
| Convención de ADR-033: `commandId := messageId` en `execute` y `messageId + "-comp"` en la compensación | `ADR-033-…md:31-34`; `AssetRegisteredSagaPolicy.java:39,58` |
| **Proyección, divergencia con el dominio:** `ASSET_SPLIT_COMPENSATED` restaura `lifecycleStatus = statusBeforeSplit` **siempre** que este no sea nulo; el dominio solo lo hace si el padre estaba `DEPLETED`. Además, `statusBeforeSplit` es **un único campo** que sobrescribe cada división | `application/projection/DonationProjectionHandler.java:246-268` |
| Un hijo cuyo padre es del Camino B hereda `donationRef`, así que las proyecciones también lo **ignoran** (B-PROJ) | `AssetProjectionRouting.java:42-44` |
| No hay endpoint de división. La matriz dice "assets resultantes" | `api-contract-matrix.md:54` |
| **El dominio ya guarda el estado previo por hijo:** `splitsBeforeCompensation` (`Map<childAssetId, estado>`) y `compensatedSplits`, reconstruidos desde cada `ASSET_SPLIT`. Solo la proyección usa un único campo | `domain/physicalasset/PhysicalAsset.java:47-48,343-360` |
| **Reclamo atómico existente en `core`:** `ProcessedCommandRepositoryPort.tryClaim(id)` (upsert con `findAndModify` sobre `processed_commands`), llamado por `TransactionalEventPublisher.appendAndOutbox` **dentro de la misma transacción** que el append. Es el mismo patrón que el reclamo `APPLY_FUNDS` de `convocatoria` (ADR-045) | `infrastructure/persistence/mongo/MongoProcessedCommandAdapter.java:30-40`; `application/service/TransactionalEventPublisher.java:29-45` |


## 3. Decisión

### S1. El comando escribe el mensaje de la saga en la misma transacción

`splitPhysicalAsset` añade un `OutboxMessage` con estos datos:

- `sagaType = "ASSET_SPLIT_SAGA"`;
- `sourceAggregateId = parentAssetId` y `correlationId = childAssetId`;
- *payload* `{"parentAssetId", "childAssetId"}`, sin copiar datos del evento. La política lee el resto del stream del padre, que es la fuente de verdad.

Va en el mismo `appendAndOutbox` que `ASSET_SPLIT`: el evento y el mensaje se escriben juntos o no se escribe ninguno (ADR-007).

### S2. `childAssetId` determinista con espacio de nombres (P1)

- `childAssetId = UUIDv5(NS_ASSET_SPLIT, parentAssetId + ":" + commandId)`, con `NS_ASSET_SPLIT` un UUID constante del dominio. Se calcula **antes** del bucle de reintento y va en el evento, en el mensaje y en la respuesta.
- **Por qué con el padre:** dos divisiones de padres distintos nunca colisionan aunque el cliente repita un `commandId`, y el id (un hash SHA-1) no revela el `commandId`.
- La repetición del mismo comando sobre el mismo padre devuelve el mismo `childAssetId` sin guardar nada más.
- Java solo trae UUID v3 (`nameUUIDFromBytes`, MD5); la v5 se implementa en el dominio (RFC 9562 §5.5, unas 15 líneas) con un test contra un vector conocido.

### S3. `SplitPhysicalAssetSagaPolicy.execute`: crea el hijo

1. Rehidrata el padre y busca su `ASSET_SPLIT` con ese `childAssetId` (v3; v2 y v1 para la herencia histórica).
2. Escribe la génesis del hijo con una **fábrica nueva de dominio**, `PhysicalAsset.registerSplitChild(...)`, porque ni `register` ni `create` sirven para el hijo (§2). El evento es `ASSET_REGISTERED` 3.0, **dentro del reclamo de S4**.
3. **Origen de cada atributo del hijo (P4, regla escrita):**

| Atributo | Origen | Motivo |
|---|---|---|
| `quantity = extractedQuantity`, `unitOfMeasure` | payload de `ASSET_SPLIT` | propio de la división |
| `currentLocation = childLocation`, `custodianRef = childCustodianRef` | **payload de `ASSET_SPLIT`** | **mutables**: el padre puede despacharse o cambiar de custodio entre la división y la ejecución de la saga |
| `organizationRef`, `donorRef`, `donationRef`, `campaignRef`, `rootAssetRef` | payload de `ASSET_SPLIT` | herencia (ADR-029 §3; Enmienda 1, D4) |
| `parentAssetRef = parentAssetId` | mensaje de la saga | — |
| `assetType` | **padre**, al ejecutar la saga | **inmutable** desde el registro |
| `allocationId = null`; `sourceAllocationId` = `allocationId` del padre o, si el padre ya es hijo, su `sourceAllocationId` | **padre**, al ejecutar la saga | **inmutables** desde el registro (ADR-033: la asignación financiera heredada) |

   **Regla:** del padre solo se leen atributos que ningún evento posterior al registro puede cambiar. Todo atributo mutable sale del payload de `ASSET_SPLIT`. Si un atributo leído del padre pasara a ser mutable, debe añadirse al payload en una versión nueva antes de cambiar su mutabilidad. B1-bis añade un test que lo fija (ver §4, test 11).
4. `SystemActor`: la autorización humana fue la de la división; la saga no vuelve a autorizar, como `AssetRegisteredSagaPolicy`.
5. `assetType`, `allocationId` y `sourceAllocationId` no tienen *getter* público: se añaden de solo lectura. **No cambia el esquema de ningún evento.**

### S4. Barrera atómica entre crear el hijo y compensar (P2)

"Solo se compensa si el hijo no existe" **no basta** como comprobación previa: crear y compensar son escrituras en streams distintos (hijo y padre), así que `expectedVersion` no las serializa. Si la saga va lenta, la compensación vence a las 4 h y después la saga crea el hijo, la cantidad quedaría contada dos veces.

**Barrera:** un **único reclamo por división**, `SPLIT_RESOLUTION:{childAssetId}`, con el mismo mecanismo que `APPLY_FUNDS` (§2).
- Crear el hijo (`execute`) y compensar (`compensate`) pasan **ese mismo identificador** como `commandId` de `appendAndOutbox`. El reclamo se escribe en la **misma transacción** que su efecto: la génesis del hijo o `ASSET_SPLIT_COMPENSATED` en el padre.
- Solo una de las dos transacciones puede insertar el reclamo. La otra encuentra el reclamo ya hecho (`appendAndOutbox` devuelve `false`) o aborta por conflicto de escritura y, al reintentar, lo encuentra hecho. **Nunca ocurren las dos.**
- El reclamo guarda **qué ganó** (`CHILD_CREATED` o `COMPENSATED`). `tryClaim` hoy solo guarda `processedAt`: se añade `tryClaim(id, outcome)` y `findOutcome(id)` al puerto, sin cambiar los llamadores actuales.
- **Quien pierde:**
  - `execute` frente a `COMPENSATED`: termina sin error (no hay nada que crear) con un log WARN, y el mensaje queda `COMPLETED`.
  - `compensate` frente a `CHILD_CREATED`: no compensa, log WARN.
  - Si el ganador era él mismo (un reintento tras un fallo posterior al commit): no-op idempotente.
- **Desviación consciente de ADR-033:** el `commandId` de las dos ramas no es `messageId` ni `messageId-comp`, sino el reclamo común. Queda escrito en ADR-033 como precisión para las sagas con compensación competidora.
- `compensateSplit` comprueba además que la cantidad reintegrada **es la extraída** en esa división; hoy no lo comprueba.
- Se mantiene la protección de ADR-009 (`DuplicateCompensationException`) como segunda defensa.

### S5. Ventana de cuarentena: 4 h (P2)

Se fija `saga.quarantine.window` por defecto en **`PT4H`**, alineado con el documento maestro §7.2, ADR-010 y ADR-045. El valor de 24 h del código era la desviación. Afecta también a `ASSET_REGISTRATION_SAGA`. Con la barrera de S4, una saga lenta que pase de las 4 h ya no puede duplicar la cantidad.

### S6. Proyecciones: corregir las dos divergencias

- `ASSET_SPLIT_COMPENSATED` restaura `lifecycleStatus` **solo si el padre estaba `DEPLETED`**, igual que el dominio.
- El estado previo se guarda **por `childAssetId`** en la proyección (`splitsBeforeCompensation.{childAssetId}`), a partir del `statusBeforeSplit` que ya viaja en cada `ASSET_SPLIT`, igual que el mapa del agregado (§2). **Sin cambiar ninguna versión de evento.**
- `DonationAuditFactsHandler` sigue ignorando la división (no es una transición logística auditada); no cambia.

### S7. Respuesta de la API y consulta del hijo pendiente (P3)

- `POST /api/v1/physical-assets/{assetRef}/split` con `{commandId, quantity}` → **`202 Accepted`**, cabecera `Location` al recurso de estado y cuerpo `{parentAssetRef, childAssetRef, status: "PENDING"}`. Un reenvío con el mismo `commandId` devuelve la misma respuesta (S2).
- **Consulta mientras el hijo no existe:**
  - `GET /api/v1/physical-assets/{childAssetRef}` → **404**, igual que cualquier activo inexistente. No distingue "pendiente" de "no existe", así que no revela nada.
  - **Recurso de estado:** `GET /api/v1/physical-assets/{parentAssetRef}/splits/{childAssetRef}` → `200 {status}`, con `status` en `PENDING` (sin reclamo), `CHILD_CREATED` o `COMPENSATED` (el resultado del reclamo de S4). Se autoriza como la lectura del padre; un par padre/hijo que no corresponde a ninguna división da 404.
  - **El guion de la demo sondea el recurso de estado** hasta `CHILD_CREATED` (o se detiene en `COMPENSATED`) y solo entonces pide el hijo.
- Esto **sustituye** "assets resultantes" de `api-contract-matrix.md:54`, que se corrige en B1-bis. El recurso de estado se añade a la matriz como contrato nuevo de B6.

### S8. Camino B

Un hijo de un padre en especie hereda `donationRef`, así que las proyecciones lo ignoran, como a su padre. **La demo divide un activo del Camino A** (paso 4 del golden path), que sí se proyecta. Cuando se decida la proyección del Camino B (Q3 de la Enmienda 1 de ADR-029), sus hijos entrarán con ella.

## 4. Definición de hecho de B1-bis

1. La división escribe `ASSET_SPLIT` y el mensaje de la saga en la misma transacción; si una de las dos escrituras falla, no queda ninguna.
2. El mismo `commandId` sobre el mismo padre devuelve el mismo `childAssetId` y no crea una segunda división; el mismo `commandId` sobre **otro** padre da otro `childAssetId`; la v5 coincide con un vector conocido.
3. La saga crea el hijo con la cantidad extraída, `parentAssetRef`, `rootAssetRef`, `sourceAllocationId` y las cuatro referencias heredadas, `campaignRef` incluido (criterio 15).
4. La cantidad del padre se reduce en lo extraído (criterio 16).
5. Si la saga se reejecuta tras crear el hijo, termina con éxito sin hijo duplicado; con dos ejecuciones concurrentes nace un solo hijo.
6. Fallo permanente sin hijo → `compensateSplit` con la cantidad extraída; la segunda compensación se rechaza (ADR-009); un padre `DEPLETED` vuelve a su estado anterior.
7. **Concurrencia (P2):** `execute` y `compensate` de la misma división lanzados a la vez (barrera con `CountDownLatch`, N repeticiones) → en cada repetición, **exactamente uno** de los dos efectos; nunca el hijo y la compensación juntos, y el reclamo guarda el ganador.
8. `compensate` después de `CHILD_CREATED` no compensa; `execute` después de `COMPENSATED` no crea el hijo y deja el mensaje `COMPLETED`.
9. `compensateSplit` con una cantidad distinta de la extraída → rechazo.
10. De punta a punta con el *change stream*: el hijo aparece en `logistics` de la misma donación, con `parentAssetRef` y `campaignRef`, y padre e hijo llegan a `DELIVERED` por separado (criterio 17).
11. **Origen de los atributos (P4):** si el padre se despacha a otra ubicación entre la división y la saga, el hijo nace con la ubicación y el custodio **del evento de división**, no los actuales del padre.
12. Proyección: una compensación no "resucita" un padre que no estaba `DEPLETED`; dos divisiones seguidas y una compensación restauran el estado correcto.
13. La ventana de cuarentena por defecto es `PT4H`.
14. Recurso de estado (cuando entre en B6): `PENDING` → `CHILD_CREATED`; un par padre/hijo inexistente da 404.

Mutaciones mínimas: quitar el reclamo común (el test 7 debe fallar), derivar el id solo del `commandId`, tomar la ubicación del padre actual y restaurar el estado de la proyección sin comprobar `DEPLETED`.

## 5. Decisiones de Carlos (2026-10-07)

| # | Pregunta | Decisión |
|---|---|---|
| Q1 | ¿`childAssetId` determinista? | **Sí, con espacio de nombres (P1):** UUID v5 de `parentAssetId` + `commandId` (S2) |
| Q2 | Ventana de cuarentena | **4 h**, y además **barrera atómica (P2)** entre crear el hijo y compensar, con test de concurrencia (S4, S5) |
| Q3 | ¿`202` con `childAssetRef` pendiente? | **Sí**, definiendo la consulta del hijo pendiente (P3): 404 en el activo, recurso de estado para el guion (S7) |
| Q4 | ¿Atributos del hijo leídos del padre? | **Sí, solo los inmutables (P4)**; ubicación y custodio salen del evento de división. Regla escrita (S3) |
| Q5 | ¿Corregir las divergencias de proyección? | **Sí**, con un mapa por hijo construido desde los `ASSET_SPLIT`, sin cambiar versiones de evento (S6) |
