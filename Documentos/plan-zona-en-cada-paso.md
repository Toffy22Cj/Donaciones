# Plan — La zona pública en cada paso del seguimiento (2026-11-02)

**Estado: PROPUESTO.** Espera un "plan aprobado" de Manuel que se pueda citar: un comentario de `buelvasmanuel` en el PR de este plan. **Antes, Manuel aprueba la enmienda** `ADR-029-enmienda-2-zona-publica.md`, que va en el mismo PR (ficha P1: "La apruebo yo antes del plan").

> **Nota (2026-11-02): APROBADO por Manuel** (comentario de `buelvasmanuel` en #170, 2026-11-02T22:10:26Z, https://github.com/Toffy22Cj/Donaciones/pull/170#issuecomment-6589733853), con E-1 en (a). "PROPUESTO" se conserva; esta nota lo supera. El texto literal está en `ADR-029-enmienda-2-zona-publica.md` y en `decisiones-delegadas-2026-10.md` (nota del 2026-11-02, más tarde).

**Norma:**
- `ficha-zona-en-cada-paso.md`, CONGELADA por Manuel (#169, comentario 6589479007), con D-3, D-4 (1 a 7) y P1 a P6;
- `ADR-029-enmienda-2-zona-publica.md` (D1 a D6 y E-1).

**Base verificada:** `develop` `3726414`, más la rama de #169. Cada ruta:línea es de esa base.

---

## 0. Hechos verificados

| # | Hecho | Dónde |
|---|---|---|
| H-1 | Los cuerpos que hoy llevan ubicación de texto libre: `RegisterRequest` y `RegisterFromDonationRequest` (`currentLocation`), `ReceiveRequest` (`facilityLocation`) y `DeliverRequest` (`locationRef`). `DispatchRequest` solo lleva `carrierRef` | `api/src/main/java/com/traceability/api/asset/PhysicalAssetDtos.java:11-12,15-16,20,22,25` |
| H-2 | El controlador exige ese texto con `RequestFields.required` y llama a `PhysicalAssetCommandService` | `api/src/main/java/com/traceability/api/asset/PhysicalAssetController.java:48,65-72,88-95,126,136,147-150` |
| H-3 | El servicio de comandos: `deliverAsset` (`:116`), `dispatchAsset` (`:128`), `receiveAsset` (`:135`), `registerPhysicalAsset` (`:210`) y `registerPhysicalAssetFromDonation` (`:328,347`). El precedente de validar contra otro módulo antes de persistir es `campaignInKindEligibility` (`:296-302`) | `core/src/main/java/com/traceability/core/application/command/PhysicalAssetCommandService.java` |
| H-4 | El agregado emite los eventos con ubicación en `:101`, `:153`, `:178-182` (el hijo), `:207-208` (el despacho), `:220-221`, `:256-259` (la división) y `:307-308`. Los aplica en `:314-417`. La entrega idempotente compara la ubicación en `:291-298` | `core/src/main/java/com/traceability/core/domain/physicalasset/PhysicalAsset.java` |
| H-5 | `SplitRecord` guarda `childLocation` para el hijo | `core/src/main/java/com/traceability/core/domain/physicalasset/SplitRecord.java:10-14` |
| H-6 | El registro de versiones | `core/src/main/java/com/traceability/core/application/event/EventPayloadRegistry.java:23-41` |
| H-7 | El hash: payload completo + `schemaVersion`. La verificación de integridad **recalcula** el hash con la clase de cada versión | `EventCanonicalMapper.java:46-58,66-69`; `application/integrity/RecomputingEventIntegrityChecker.java:75-82,101-107` |
| H-8 | **Los tres sitios que hoy leen `location_reference`:** (1) la historia pública, `AssetHistoryMapper.java:61`, con el texto que copia la proyección (`DonationProjectionHandler.java:313,320,336`); (2) la logística del donante, `PublicDonationMapper.java:87`, con `logistics[].currentLocation` (`DonationProjectionHandler.java:243,253,283`); (3) la actividad de la organización, `OrganizationActivityQuery.java:211-237`, que lee el payload directamente | `api/src/main/java/com/traceability/api/application/mapper/`; `core/src/main/java/com/traceability/core/application/projection/`; `app/src/main/java/com/traceability/app/application/activity/` |
| H-9 | La vista operativa se reconstruye del agregado (`asset.getCurrentLocation()`). La usan `GET /physical-assets/{assetRef}` y `GET /organizations/{id}/physical-assets` | `core/src/main/java/com/traceability/core/application/query/PhysicalAssetOperationalQueryService.java:51-53,69-76`; `core/src/main/java/com/traceability/core/application/port/out/PhysicalAssetOperationalView.java:13-15`; `api/src/main/java/com/traceability/api/asset/OrganizationAssetsController.java:40` |
| H-10 | Los manejadores de proyección declaran qué payloads tratan o ignoran (B-PROJ); un payload no declarado deja un log de error | `core/src/main/java/com/traceability/core/infrastructure/projection/ProjectionEventHandler.java:30-41`; `DonationProjectionHandler`, `DonationAuditFactsHandler` y `PendingAllocationProjectionHandler` |
| H-11 | El catálogo DIVIPOLA se carga al arrancar en `app`, con fail-fast. El de los tests tiene `05001` MEDELLÍN y `05002` ABEJORRAL (ANTIOQUIA), y `11001` BOGOTÁ, D.C. | `app/src/main/java/com/traceability/app/config/LocationCatalogConfig.java:18-22`; `app/src/test/resources/divipola/divipola.json` |
| H-12 | Las excepciones de `core` se mapean a HTTP en `api` | `api/src/main/java/com/traceability/api/web/CoreApiErrorMappings.java:55` |
| H-13 | El contrato vigente: `locationZone` es `null` en `DISPATCHED` | `Documentos/referencia-api-v1.md:319` |

## 1. Común

- **Un PR de código**, `feat/core-zona-en-cada-paso` **[Plan]**, que sale de `develop` cuando estén fusionados #169 y el PR de este plan.
- **Ciclo:**
  - rojo primero, con la salida literal;
  - verde por capas con el módulo completo (`core`, después `api` y después `app`);
  - mutaciones con `scripts/mutaciones.py` en un clon limpio, con la mutación 0;
  - `free -g` ≥ 4 GB y `scripts/ci-local.sh` en un clon limpio del commit exacto, con la salida en `Documentos/evidencia-ci/`.
- **Regla 3.5:** cubierta por la enmienda (§5): un puerto nuevo en `contracts` y el contrato de `PhysicalAssetCommandService`. No cambia **ningún otro** puerto existente.
- **Las clases de payload que ya existen no se tocan.**
- **Aviso a la web y al móvil** en el PR (§7).

## 2. Cambios por módulo

### `contracts`

| Archivo | Cambio |
|---|---|
| `contracts/.../location/MunicipalityCatalogPort.java` **[Plan]** | `Optional<MunicipalityEntry> find(String municipalityCode)`, de solo lectura (enmienda D3) |
| `contracts/.../location/MunicipalityEntry.java` **[Plan]** | `record MunicipalityEntry(municipalityCode, municipalityName, departmentCode, departmentName, catalogVersion)` |

### `app`

| Archivo | Cambio |
|---|---|
| `infrastructure/location/DivipolaMunicipalityCatalogAdapter.java` **[Plan]** | Implementa `MunicipalityCatalogPort` sobre el catálogo que ya se carga (`DivipolaLocationCatalog`, H-11). Un código que no existe → `Optional.empty()` |
| `config/LocationCatalogConfig.java` | Expone también el bean del puerto nuevo, con el mismo fail-fast |
| `application/activity/OrganizationActivityQuery.java` | Las clases nuevas en la cadena de `instanceof` (`:211-237`). La zona sale de `PublicZoneResolver` (ver `core`) |

### `core`

| Archivo | Cambio |
|---|---|
| `domain/physicalasset/payloads/AssetRegisteredV4Payload.java`, `AssetSplitV4Payload.java`, `AssetDispatchedV2Payload.java`, `AssetReceivedV2Payload.java` y `AssetDeliveredV2Payload.java` **[Plan]** | Las versiones de la enmienda D2: la anterior más `municipalityCode`, `zoneName` y `catalogVersion` |
| `application/event/EventPayloadRegistry.java` | `register(...)` de las cinco (4.0, 4.0, 2.0, 2.0, 2.0) |
| `domain/physicalasset/SealedZone.java` **[Plan]** | `record SealedZone(municipalityCode, zoneName, catalogVersion)` con `of(MunicipalityEntry)`, que compone `zoneName` (los nombres tal cual; si son iguales, uno solo) |
| `domain/physicalasset/PhysicalAsset.java` | <ul><li>campo `zone` (`SealedZone`, puede ser `null` en los activos anteriores);</li><li>`register`/`create`, `receive` y `deliver` reciben un `SealedZone` y emiten la versión nueva;</li><li>`dispatch` copia `zone` del estado en `AssetDispatchedV2Payload` (P2), y `split` lo copia en `AssetSplitV4Payload` (P3);</li><li>`registerSplitChild` lo hereda del `SplitRecord`;</li><li>`apply` trata las clases nuevas y mantiene las viejas;</li><li>la entrega idempotente compara también `municipalityCode` (`:291-298`);</li><li>con E-1 (a), un activo sin `zone` despacha y divide con los tres campos en `null`</li></ul> |
| `domain/physicalasset/SplitRecord.java` | Más el `SealedZone` del hijo (`null` para las divisiones 1.0 a 3.0) |
| `application/command/PhysicalAssetCommandService.java` | `registerPhysicalAsset`, `registerPhysicalAssetFromDonation`, `receiveAsset` y `deliverAsset` reciben `municipalityCode`. Orden: autorizar → `MunicipalityCatalogPort.find` → si no existe, `UnknownMunicipalityCodeException` **[Plan]**, sin persistir → `SealedZone.of` → agregado |
| `application/exception/UnknownMunicipalityCodeException.java` **[Plan]** | Excepción nombrada (regla 2.6) |
| `application/service/PublicZoneResolver.java` **[Plan]** | `resolve(String sealedZoneName, String rawLocation)`: lo sellado si existe; si no, `LocationReferenceService.resolveZone(rawLocation)` (enmienda D5). `LocationReferenceService` no cambia |
| `application/projection/DonationProjectionHandler.java` | Declara las cinco clases (B-PROJ). La historia guarda `zoneName` [Plan] en cada transición, también `DISPATCHED`; la logística guarda `currentZoneName` [Plan] al registrar, recibir y entregar (al despachar no cambia, como hoy `currentLocation`) |
| `DonationAuditFactsHandler.java` y `PendingAllocationProjectionHandler.java` | Declaran las clases nuevas como tratadas o ignoradas, igual que sus versiones anteriores |
| `application/port/out/PhysicalAssetOperationalView.java` y `PhysicalAssetOperationalQueryService.java` | Más `municipalityCode` y `zoneName`, del `zone` del agregado (P6) |
| `application/port/out/AssetTransitionReadModel.java`, el modelo de la logística y sus adaptadores Mongo | Más `zoneName` / `currentZoneName`, opcionales |

### `api`

| Archivo | Cambio |
|---|---|
| `asset/PhysicalAssetDtos.java` | `municipalityCode` [Plan] en `RegisterRequest`, `RegisterFromDonationRequest`, `ReceiveRequest` y `DeliverRequest`. `OperationalResponse` gana `municipalityCode` y `zoneName` (P6, aditivo) |
| `asset/PhysicalAssetController.java` | `RequestFields.required(b.municipalityCode(), "municipalityCode")` en las cuatro rutas, y formato `\d{5}`; si no → 400 `BadRequest` sin llamar a `core`. `dispatch` no cambia |
| `asset/OrganizationAssetsController.java` | Los ítems ganan `municipalityCode` y `zoneName` (aditivo, la misma vista, H-9) |
| `web/CoreApiErrorMappings.java` | `UnknownMunicipalityCodeException` → **400**, con el mismo cuerpo `BadRequest` que un campo inválido (ficha §3) |
| `application/mapper/AssetHistoryMapper.java` (`:61`) y `PublicDonationMapper.java` (`:87`) | `PublicZoneResolver.resolve(sealed, raw)` en lugar de `resolveZone(raw)` |

## 3. Compatibilidad con los eventos viejos

- **Reconstruir el agregado:** `apply` sigue tratando las versiones 1.0, 2.0 y 3.0, y su `zone` queda en `null`. La vista operativa devuelve entonces `municipalityCode` y `zoneName` en `null`.
- **Verificación de integridad:** los eventos viejos se deserializan con su clase, que no cambia (H-7), así que **dan el mismo hash** que antes. Lo comprueba el test 1.
- **Proyecciones:** los documentos existentes no se reconstruyen. Las transiciones sin `zoneName` usan el respaldo de `location_reference` (enmienda D5 y D6).
- **Ningún *backfill*** (enmienda D6).

## 4. Tests de la Definición de hecho (rojos primero)

| # | Test [Plan] | Módulo | Rojo esperado |
|---|---|---|---|
| 1 | **El hash de un evento viejo no cambia:** eventos `ASSET_REGISTERED` 3.0, `ASSET_DISPATCHED` 1.0, `ASSET_RECEIVED` 1.0 y `ASSET_DELIVERED` 1.0 guardados con el código actual dan el mismo `eventHash` con `RecomputingEventIntegrityChecker` después del cambio (aserción sobre el hash guardado) | `core` | Verde ya; es un test de guardia (negativa) |
| 2 | **Un evento nuevo lleva la zona dentro del hash:** el mapa canónico de `AssetReceivedV2Payload` incluye `municipalityCode`, `zoneName` y `catalogVersion`, y cambiar cualquiera de los tres cambia el `eventHash` | `core` | Compilación |
| 3 | `SealedZone.of`: `08001` → `"BARRANQUILLA, ATLÁNTICO"`; `11001` → `"BOGOTÁ, D.C."` (una sola vez); `catalogVersion` copiada | `core` | Compilación |
| 4 | Registrar (A y B), recibir y entregar con un código **inexistente** → `UnknownMunicipalityCodeException` y **ningún evento** en el *stream* (negativa) | `core` | Compilación |
| 5 | **Despacho con la zona del paso anterior:** registrar en `05001` → despachar → `AssetDispatchedV2Payload` con `05001` y su `zoneName`; el cuerpo de `dispatch` no cambia | `core` | Compilación |
| 6 | **Sublote con la zona heredada:** dividir un activo de `05002` → `AssetSplitV4Payload` y el `AssetRegisteredV4Payload` del hijo con `05002` | `core` | Compilación |
| 7 | La entrega idempotente con otro `municipalityCode` → "parámetros distintos" | `core` | Compilación |
| 8 | **Respaldo para eventos viejos:** una transición 3.0 o 1.0 con texto que está en `location_reference` → esa zona; sin entrada → `null`. Una transición nueva **ignora** `location_reference` aunque el texto esté en ella | `core`/`api` | Compilación |
| 9 | E-1 (a): un activo con eventos 3.0 que se despacha → `AssetDispatchedV2Payload` con los tres campos en `null`; la zona pública usa el respaldo | `core` | Compilación |
| 10 | HTTP: `register`, `from-donation`, `receive` y `deliver` **sin** `municipalityCode` → 400 `BadRequest`; con `99999` → 400 `BadRequest`; con `1234` → 400. En los tres casos **no se escribe ningún evento** | `app` (HTTP) | 200/201 (el campo se ignora) |
| 11 | HTTP: `GET /donations/tracking/assets/{assetRef}/history` con `location_reference` **vacía**: cada paso lleva `locationZone` = el `zoneName` sellado, también `DISPATCHED` y el hijo | `app` (HTTP) | `null` |
| 12 | **Lo sellado gana al catálogo:** se registra con el catálogo del test, se sustituye el bean del puerto por otro con otro nombre para `05001`, y la zona pública no cambia | `app` | Compilación |
| 13 | HTTP: `GET /donations/tracking` (`logistics[].locationZone`) y `GET /organizations/{id}/activity` muestran la zona sellada | `app` (HTTP) | `null` |
| 14 | HTTP: `GET /physical-assets/{assetRef}` y `GET /organizations/{id}/physical-assets` llevan `municipalityCode` y `zoneName` (P6) | `app` (HTTP) | Campos ausentes |
| 15 | **Negativa de privacidad:** ninguna salida pública (historia, logística, narrativa) lleva el texto libre, la dirección ni el beneficiario | `app` (HTTP) | Verde ya (guardia) |
| 16 | B-PROJ: las cinco clases nuevas están declaradas en cada manejador (`UndeclaredPayloadMonitorTest` y el test de contrato) | `core` | Rojo por clase no declarada |

**Tests que se adaptan, sin perder aserciones:** los que registran, reciben o entregan por HTTP o por el servicio añaden un `municipalityCode` del catálogo de prueba (`05001`):
- `GoldenPathHttpIntegrationTest`, `PhysicalAssetHttpIntegrationTest`, `DeliveryEvidence*HttpIntegrationTest` y `P2EndpointsHttpIntegrationTest`, en `app`;
- `AssetHttpCommandsIntegrationTest`, `MongoEventStoreAdapterTest`, `DonationProjectionIntegrationTest` y los demás llamadores del servicio (14 archivos de test), en `core`;
- `core` usa un `StubMunicipalityCatalogPort` [Plan] de soporte, como `StubCampaignInKindEligibilityPort`.

**Común:** `scripts/ci-local.sh` completo en un clon limpio, con el resumen literal de Surefire.

## 5. Mutaciones (`scripts/mutaciones.py`)

| # | Mutación | Debe matarla |
|---|---|---|
| 1 | `receive` escribe `AssetReceivedPayload` (1.0) en lugar de la 2.0 | 2, 11 |
| 2 | `zoneName` sin el municipio (solo el departamento) | 3, 11 |
| 3 | Bogotá repetido ("BOGOTÁ, D.C., BOGOTÁ, D.C.") | 3 |
| 4 | Un código inexistente se acepta con la zona en `null` | 4, 10 |
| 5 | La validación después de persistir | 4, 10 |
| 6 | El despacho sin copiar la zona (campos en `null`) | 5, 11 |
| 7 | El hijo sin heredar la zona | 6, 11 |
| 8 | La entrega idempotente no compara `municipalityCode` | 7 |
| 9 | `PublicZoneResolver` lee primero `location_reference` | 8, 11 |
| 10 | `PublicZoneResolver` lee el catálogo actual en lugar de lo sellado | 12 |
| 11 | `api` no exige `municipalityCode` | 10 |
| 12 | `AssetHistoryMapper` sigue con `resolveZone(raw)` | 11 |
| 13 | `PublicDonationMapper` sigue con `resolveZone(raw)` | 13 |
| 14 | La vista operativa sin `zoneName` | 14 |
| 15 | Un campo añadido a `AssetReceivedPayload` (1.0) en lugar de una clase nueva | 1 |

## 6. Documentos que se actualizan con el PR de código

- **`referencia-api-v1.md`**, con notas fechadas:
  - `municipalityCode` obligatorio en `register`, `from-donation`, `receive` y `deliver` (filas `:281-282,286-287`), con su 400;
  - `GET /physical-assets/{assetRef}` (`:288`) y la lista de la organización (`:173`) ganan `municipalityCode` y `zoneName`;
  - **la nota que supera lo de `:319` sobre el despacho:** "`locationZone` llega en `DISPATCHED` con la zona del paso anterior en los pasos registrados desde este cambio; en los anteriores puede seguir llegando `null`";
  - de dónde sale `locationZone` (lo sellado, con el respaldo).
- `documento-maestro-proyecto.md`: una nota en la definición de `locationZone` (`:515`).
- `ficha-zona-en-cada-paso.md` y la enmienda: una nota de "implementada".
- `estado-fase6.md`: una sección nueva.

## 7. Aviso a la web y al móvil

**Cambio que rompe** (D-4.1): sin `municipalityCode`, estas rutas responden 400.

| Ruta | Cuerpo |
|---|---|
| `POST /api/v1/physical-assets/register` | + **`municipalityCode`** (obligatorio, 5 dígitos) |
| `POST /api/v1/physical-assets/from-donation` | + **`municipalityCode`** |
| `POST /api/v1/physical-assets/{assetRef}/receive` | + **`municipalityCode`** |
| `POST /api/v1/physical-assets/{assetRef}/deliver` | + **`municipalityCode`** |
| `POST /api/v1/physical-assets/{assetRef}/dispatch` | Sin cambios: el sistema sella la zona del paso anterior |

- **Pantallas de registrar, recibir y entregar:** un **selector de departamento y municipio**, el mismo de R-7.
  - Se llena con `GET /api/v1/public/locations`, que tiene caché de 1 día.
  - Se manda el código de 5 dígitos del municipio.
- **El texto libre de la ubicación** se sigue pidiendo y **es privado**. El formulario puede decir "No se publica; el donante solo verá el municipio".
- **`locationZone`** en el seguimiento, la logística y la actividad llega como `"BARRANQUILLA, ATLÁNTICO"`, en mayúsculas tal cual el catálogo, y **el front lo formatea**. En los pasos anteriores al cambio puede seguir llegando `null`.
- **`GET /physical-assets/{assetRef}`** y la lista de activos de la organización ganan `municipalityCode` y `zoneName` (aditivo).
