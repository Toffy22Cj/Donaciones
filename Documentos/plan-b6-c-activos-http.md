# Plan B6-c — HTTP de activos y división (incluido D-ASSET)

**Estado:** **EN EJECUCIÓN bajo la autorización de trabajo autónomo de Carlos (2026-10-07)**, que exceptúa temporalmente las reglas 1 y 3.4. El plan entero es una `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` (DD-08); cada decisión concreta está en `decisiones-delegadas-2026-10.md` (DD-09 a DD-16).
**Origen:** `propuesta-d-api.md` (APROBADO): A8 (D-ASSET, id determinista, `DELIVER_ASSET`), A9 y Q9 (`Command-Id` en todos los comandos de la demo, reversión de T-33 por Carlos), §3 (B6-c); `api-contract-matrix.md` §4 y §4b; `plan-b1bis-saga-division.md` (división y `SplitResolutionReadPort`).
**Depende de:** B6-0 (fusionado en #58).
**Revisión:** cubierto por la excepción a la regla 3.2 (`estado-fase6.md` §0.5).

---

## 1. Hechos del código (`develop` en `1ad0a56`)

- `PhysicalAssetCommandService`:
  - `registerPhysicalAsset` y `registerPhysicalAssetFromDonation` generan el id con `UUID.randomUUID()` y devuelven `void`; un duplicado es un no-op mudo (H8).
  - `dispatch` y `receive` solo existen en el dominio (`PhysicalAsset.java:198,211`); no hay `CommandType` para ellos (D-ASSET).
  - `deliverAsset` existe, autoriza con `DELIVER_ASSET` (`EMPLOYEE`) y devuelve `void`.
  - `splitPhysicalAsset` devuelve el `childAssetId` determinista (B1-bis).
  - Los reclamos de `commandId` son globales a todos los comandos de `core`: un `Command-Id` reutilizado por otro comando hoy es un no-op silencioso.
  - Un activo inexistente se rehidrata vacío y la frontera de organización falla con `CrossOrganizationAccessException` (403), sin excepción propia.
- `SplitResolutionReadPort.findStatus(parent, child)` existe (B1-bis).
- Las proyecciones ignoran los activos del Camino B (`estado-fase6.md` §0.6), así que una lectura operacional sobre `logistics` no vería la mitad de los activos de la demo.
- Los controladores que solo usan `core` van en `api` (Q1 de D-API).

## 2. Cambios

### 2.1 `core`

- **`CommandType`:** `DISPATCH_PHYSICAL_ASSET` y `RECEIVE_PHYSICAL_ASSET`, con rol `EMPLOYEE` (matriz §4).
- **`dispatchAsset(commandId, assetId, carrierRef, actor)`** y **`receiveAsset(commandId, assetId, facilityLocation, receiverRef, actor)`**, con el mismo patrón que `deliverAsset`: reclamo, rehidratación, autorización (P9 → P7), transición del dominio y escritura atómica.
- **Id determinista del registro (Q9):** `AssetIds.of(organizationRef, commandId)` = `UUIDv5(NS_ASSET, organizationRef + ":" + commandId)`, con un espacio de nombres propio y distinto del de la división. Se calcula fuera del reintento. Los dos registros devuelven un `RegisteredAsset(assetId, donationRef, campaignRef)` leído del evento de génesis, así que un duplicado devuelve exactamente lo mismo.
- **Reenvío de un `Command-Id` de otro comando (DD-11):** los comandos de activo expuestos por HTTP guardan en su reclamo el resultado `TIPO:assetId` (`TransactionalEventPublisher` ya lo admite). Ante un reclamo existente:
  - mismo resultado → duplicado, misma respuesta;
  - otro resultado o reclamo sin resultado → `CommandIdReusedException` (nueva, `core`) → **409**.
  - No cambia ningún evento: el resultado vive en el registro de comandos.
- **Activo inexistente (DD-12):** `PhysicalAssetNotFoundException` (nueva), lanzada al cargar el activo antes de autorizar; se traduce al **mismo 403** que `CrossOrganizationAccessException`.
- **Lectura operacional (DD-13):** `PhysicalAssetOperationalReadPort.findForActor(assetId, actor)` → `PhysicalAssetOperationalView(assetRef, lifecycleStatus, currentCustodianRef, currentLocation, quantity, unitOfMeasure, campaignRef)`, rehidratando del event store. Exige `EMPLOYEE` y la frontera de organización (matriz §4b). Excluye `donorRef`, datos del `Fund` y la genealogía.
- **Estado de la división:** `findStatus` con la misma comprobación de rol y frontera sobre el padre.

### 2.2 `api` (paquete `com.traceability.api.asset`)

| Ruta | Respuesta | Notas |
|---|---|---|
| `POST /api/v1/physical-assets/register` | `201 {assetRef, status, campaignRef}` | Camino A. Cuerpo: `fundId`, `assetType`, `quantity`, `unitOfMeasure`, `custodianRef`, `currentLocation`, `allocationId`, `sourceAllocationId`. `organizationRef` del principal (DD-10) |
| `POST /api/v1/physical-assets/from-donation` | `201 {assetRef, status, donationRef, campaignRef}` | Camino B. Cuerpo: `assetType`, `quantity`, `unitOfMeasure`, `custodianRef`, `currentLocation`, `campaignRef` (opcional). `donorRef` lo genera el servidor (`anon:` + UUID; DD-09) |
| `POST /api/v1/physical-assets/{assetRef}/split` | `202` + `Location: /api/v1/physical-assets/{assetRef}/splits/{child}` + `{parentAssetRef, childAssetRef, status: "PENDING"}` | Cuerpo `{quantity}` |
| `GET /api/v1/physical-assets/{assetRef}/splits/{childAssetRef}` | `200 {status}` | Par sin división → 404 (dentro de la propia organización) |
| `POST /api/v1/physical-assets/{assetRef}/dispatch` | `200 {assetRef, status: "DISPATCHED"}` | Cuerpo `{carrierRef}` |
| `POST /api/v1/physical-assets/{assetRef}/receive` | `200 {assetRef, status: "RECEIVED"}` | Cuerpo `{facilityLocation, receiverRef}` |
| `POST /api/v1/physical-assets/{assetRef}/deliver` | `200 {assetRef, status: "DELIVERED"}` | Cuerpo `{finalCustodianRef, beneficiaryRef, locationRef, evidenceRef}`; `deliveredAt` del reloj del servidor (DD-15) |
| `GET /api/v1/physical-assets/{assetRef}` | `200` vista operacional | Cantidades como texto (T-34) |

- Todas con JWT obligatorio; los comandos con `@CommandId` y `@CurrentActor`. Nombres explícitos en cada `@PathVariable` (hallazgo de B6-0: el módulo compila sin `-parameters`).
- **Validación de la forma:** `quantity` como texto decimal positivo (`InvalidRequestFieldException`, nueva en `api.web`, → 400 sin eco); campos obligatorios ausentes → 400. Las invariantes del dominio siguen siendo 409.
- `CoreApiErrorMappings` añade `CommandIdReusedException` (409, `CommandIdReused`) y `PhysicalAssetNotFoundException` (el mismo 403). `PhysicalAssetNotAssociatedToOrganizationException` no puede salir por HTTP con el cambio de §2.1 (el activo sin organización es el inexistente).

### 2.3 `app`

- `PublicRoutesInventoryIntegrationTest.PROTECTED` gana las ocho rutas.

## 3. Tests (primero en rojo)

| # | Caso | Tipo |
|---|---|---|
| 1 | `dispatchAsset`/`receiveAsset`: transición y evento; idempotencia por `commandId`; rol `EMPLOYEE`; otra organización → `CrossOrganizationAccessException`; activo inexistente → `PhysicalAssetNotFoundException`; sin escritura en el rechazo | `core`, Testcontainers |
| 2 | Registro A y B: id = `AssetIds.of(org, cmd)`; un duplicado devuelve el mismo `RegisteredAsset` y no escribe nada; dos organizaciones con el mismo `commandId` → ids distintos | `core` |
| 3 | `Command-Id` de otro comando → `CommandIdReusedException`, sin escritura | `core` |
| 4 | Lectura operacional: campos exactos, sin `donorRef` ni genealogía; sin `EMPLOYEE` → 403; otra organización y inexistente → el mismo 403 | `core` |
| 5 | HTTP de punta a punta contra Tomcat real: registro B → división → `CHILD_CREATED` → dispatch/receive/deliver de padre e hijo → `DELIVERED` (criterios 7, 8, 15–17) | `app`, `RANDOM_PORT` |
| 6 | HTTP: cada ruta sin JWT → 401; sin `Command-Id` → 400 sin llamar al servicio; duplicado → mismo código y cuerpo byte a byte; reutilizado → 409 | `app` |
| 7 | HTTP: otra organización e inexistente → 403 idéntico byte a byte en todas las rutas con `{assetRef}` | `app` |
| 8 | HTTP: un `donorRef` enviado en el cuerpo del Camino B se ignora (el evento lleva `anon:`) | `app` |
| 9 | Inventario de rutas y ArchUnit de B6-0 en verde | existentes |

**Mutaciones:** quitar la autorización de `dispatchAsset`; id aleatorio en el registro; aceptar un `Command-Id` reutilizado; traducir `PhysicalAssetNotFoundException` a 404; incluir `donorRef` en la vista operacional; usar el `donorRef` del cliente; quitar el rol en la lectura.

## 4. Entrega

- Un PR, `feat/b6-c-activos-http`: `core` (servicio, ids, lectura), `api` (controlador y traducciones), `app` (inventario y tests).
- Commits: tests en rojo con esqueleto → `core` → `api` → documentos y evidencia (HECHO solo después de la evidencia).

## 5. Fuera de alcance y hallazgos

- **H-B6C-1 (DD-16):** el Camino A por HTTP necesita una asignación previa (`requestAllocation`, `ADMINISTRATOR`) que no tiene endpoint en ningún plan ni en la matriz. Sin ella, el registro A por HTTP solo funciona con una asignación creada por otra vía. Decisión de Carlos: endpoint nuevo o recorrido de la demo por el Camino B.
- `transferCustody` por HTTP, listados y QR.
