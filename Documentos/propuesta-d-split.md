# Propuesta D-SPLIT — Saga del hijo de la división, compensación y respuesta de la API

**Estado:** **PROPUESTO** (2026-10-07). Lo decide Carlos (dueño de `core`). Sin código. B1-bis necesitará después su plan aprobado (regla 3.4).
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

## 3. Propuesta

### S1. El comando escribe el mensaje de la saga en la misma transacción

`splitPhysicalAsset` añade un `OutboxMessage` con estos datos:

- `sagaType = "ASSET_SPLIT_SAGA"`;
- `sourceAggregateId = parentAssetId` y `correlationId = childAssetId`;
- *payload* `{"parentAssetId", "childAssetId"}`, sin copiar datos del evento. La política lee el resto del stream del padre, que es la fuente de verdad.

Va en el mismo `appendAndOutbox` que `ASSET_SPLIT`: el evento y el mensaje se escriben juntos o no se escribe ninguno (ADR-007).

### S2. `childAssetId` determinista a partir del `commandId`

- `childAssetId = UUID.nameUUIDFromBytes("ASSET_SPLIT:" + commandId)`. Ese id va en el evento, en el mensaje y en la respuesta.
- **Motivo:** hoy un comando repetido no puede devolver el mismo hijo, porque el id es aleatorio y no se guarda. Con el id determinista, la repetición del mismo `commandId` devuelve el mismo `childAssetId` sin almacenar nada más, y el reintento interno ya no cambia el id.

### S3. `SplitPhysicalAssetSagaPolicy.execute`: crea el hijo de forma idempotente

1. Rehidrata el padre y busca su `AssetSplitV3Payload` con ese `childAssetId`. Lee también la v2 y la v1 para la herencia histórica.
2. Si el stream del hijo **ya existe** (su génesis está en el event store), termina con éxito sin escribir nada. Así un reintento no duplica el hijo.
3. Si no existe, escribe la génesis del hijo con una **fábrica nueva de dominio**, `PhysicalAsset.registerSplitChild(...)`, porque ni `register` ni `create` sirven para el hijo (§2). El evento es `ASSET_REGISTERED` 3.0 con:
   - `assetId = childAssetId` y `parentAssetRef = parentAssetId`;
   - `rootAssetRef` del payload de la división;
   - `quantity = extractedQuantity` y `unitOfMeasure`;
   - `currentLocation = childLocation` y `custodianRef = childCustodianRef`;
   - `organizationRef`, `donorRef`, `donationRef` y `campaignRef` **heredados del payload** (ADR-029 §3; Enmienda 1, D4);
   - `assetType` del padre;
   - `allocationId = null` y `sourceAllocationId` = el `allocationId` del padre o, si el padre ya es hijo, su `sourceAllocationId` (ADR-033: "la asignación financiera heredada por un activo hijo").
4. Usa `commandId = messageId` (ADR-033) y `SystemActor`. La autorización humana fue la de la división; la saga no vuelve a autorizar, como `AssetRegisteredSagaPolicy`.
5. `expectedVersion = 0`: si dos ejecuciones compiten, el índice único `(streamId, sequence)` deja escribir a una sola. La otra ve el hijo existente en el siguiente intento.

**Detalle:** `assetType` y `allocationId` no están en el payload de la división ni tienen *getter* público en el agregado. Se añade un *getter* de solo lectura o se toman de la génesis del padre. **No se cambia el esquema del evento** (ver Q4).

### S4. Compensación: solo si el hijo no existe

- El coordinador llama a `compensate` cuando vence la ventana (S5). La política:
  - **comprueba primero que el hijo no exista**; si existe, no compensa y deja traza;
  - solo si no existe, invoca `compensateSplit(childAssetId, extractedQuantity)` en el padre, con `commandId = messageId + "-comp"` (ADR-033) y `SystemActor`.
- **Motivo:** si el hijo se creó y se compensara igualmente, la cantidad existiría dos veces, en el hijo y devuelta al padre.
- Para ese caso extremo (el hijo se crea después de vencer la ventana), el `DuplicateCompensationException` del agregado (ADR-009) protege de una segunda compensación, pero no del caso "hijo y compensación a la vez". Por eso la comprobación previa es obligatoria.
- Se añade a `compensateSplit` la comprobación de que la cantidad reintegrada **es la extraída** en esa división; hoy no lo comprueba.

### S5. Ventana de cuarentena: alinear el código y el documento

El código usa **24 h** por defecto y el documento maestro §7.2 dice **4 h**. Para la división, 4 h basta (como ADR-010 y ADR-045): si el hijo no se ha podido crear en 4 h, es un fallo permanente. **Decisión de Carlos (Q2):** fijar `saga.quarantine.window` en 4 h para todas las sagas (también afecta a `ASSET_REGISTRATION_SAGA`), o corregir el documento a 24 h.

### S6. Proyecciones: corregir las dos divergencias

- `ASSET_SPLIT_COMPENSATED` debe restaurar `lifecycleStatus` **solo si el padre estaba `DEPLETED`**, igual que el dominio.
- `statusBeforeSplit` debe guardarse **por `childAssetId`** en la proyección, no en un único campo, porque un padre puede dividirse varias veces antes de que alguna se compense.
- `DonationAuditFactsHandler` sigue ignorando la división (no es una transición logística auditada); no cambia.

### S7. Respuesta de la API (para la ficha de B6)

- `POST /api/v1/physical-assets/{assetRef}/split` con `{commandId, quantity}` → **`202 Accepted`** y `{parentAssetRef, childAssetRef, status: "PENDING"}`.
  - `childAssetRef` es el id determinista de S2, así que un reenvío con el mismo `commandId` devuelve la misma respuesta.
  - El cliente consulta `GET` del activo hijo hasta que existe: es la espera del guion de la demo.
- Esto **sustituye** "assets resultantes" de `api-contract-matrix.md:54`: el hijo no puede devolverse en la misma llamada porque nace de forma asíncrona.

### S8. Camino B

Un hijo de un padre en especie hereda `donationRef`, así que las proyecciones lo ignoran, como a su padre. **La demo divide un activo del Camino A** (paso 4 del golden path), que sí se proyecta. Cuando se decida la proyección del Camino B (Q3 de la Enmienda 1 de ADR-029), sus hijos entrarán con ella.

## 4. Tests mínimos para B1-bis (propuesta de definición de hecho)

1. La división escribe `ASSET_SPLIT` y el mensaje de la saga en la misma transacción; si una de las dos escrituras falla, no queda ninguna.
2. El mismo `commandId` devuelve el mismo `childAssetId` y no crea una segunda división.
3. La saga crea el hijo con la cantidad extraída, `parentAssetRef`, `rootAssetRef`, `sourceAllocationId` y las cuatro referencias heredadas, `campaignRef` incluido (criterio 15).
4. La cantidad del padre se reduce en lo extraído (criterio 16).
5. Si la saga se reejecuta tras crear el hijo, termina con éxito sin hijo duplicado; con dos ejecuciones concurrentes nace un solo hijo.
6. Fallo permanente sin hijo → `compensateSplit` con la cantidad extraída; la segunda compensación se rechaza (ADR-009); un padre `DEPLETED` vuelve a su estado anterior.
7. Fallo permanente **con** el hijo ya creado → **no** compensa.
8. `compensateSplit` con una cantidad distinta de la extraída → rechazo.
9. De punta a punta con el *change stream*: el hijo aparece en `logistics` de la misma donación, con `parentAssetRef` y `campaignRef`, y padre e hijo llegan a `DELIVERED` por separado (criterio 17).
10. Proyección: una compensación no "resucita" un padre que no estaba `DEPLETED`; dos divisiones seguidas y una compensación restauran el estado correcto.

## 5. Preguntas para Carlos

| # | Pregunta | Recomendación |
|---|---|---|
| Q1 | ¿`childAssetId` determinista a partir del `commandId` (S2)? | Sí: hace idempotente la respuesta sin guardar nada |
| Q2 | Ventana de cuarentena de las sagas: ¿4 h (documento) o 24 h (código)? (S5) | 4 h, alineada con ADR-010 y ADR-045 |
| Q3 | ¿Respuesta `202` con `childAssetRef` pendiente (S7)? | Sí; se corrige la matriz de contratos |
| Q4 | ¿`assetType` y `allocationId` del hijo leídos del padre, sin cambiar el esquema de `ASSET_SPLIT` (S3)? | Sí: la 3.0 acaba de entrar y no hace falta otra versión |
| Q5 | ¿Corregir en B1-bis las dos divergencias de proyección (S6)? | Sí: sin ellas, la compensación deja estados incorrectos en el seguimiento |
