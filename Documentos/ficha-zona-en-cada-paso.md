# Ficha — La zona pública en cada paso del seguimiento

**Naturaleza:** ficha de contrato HTTP y de evento, en el formato de la ficha R-7. **No autoriza código:** el código sigue un plan aparte, que aprueba Manuel cuando la ficha esté congelada.
**Estado: PROPUESTA.** La congela Manuel, a quien Carlos delegó el tema (D-4).
**Fecha:** 2026-11-02 (UTC). **Base verificada:** `develop` `3726414`. Cada ruta:línea es de esa base.
**Origen:**
- **D-3** (Carlos, 2026-11-02): "Eso debe venir en los mismos datos".
- **D-4** (Manuel, 2026-11-02): siete respuestas.

Las dos están en `decisiones-delegadas-2026-10.md`, nota del 2026-11-02.

**Requiere enmienda o ADR:** cambia el esquema de eventos sellados, así que probablemente sí. Es la pregunta **P1** (§7).

---

## 1. Problema

- **La zona pública de cada paso** (por ejemplo, "Barranquilla, Atlántico") **sale de traducir el texto libre** que escribe el empleado con la colección `location_reference`: `core/src/main/java/com/traceability/core/application/service/LocationReferenceService.java:18-23`.
- **`location_reference` no tiene ruta ni pantalla para llenarse** (`estado-fase3.md:131`; `documento-maestro-proyecto.md:451`). En un servidor nuevo, todos los pasos salen "Sin zona pública".
- **El catálogo DIVIPOLA ya existe** (R-7): `app/src/main/resources/divipola/divipola.json` (`catalogVersion` `DIVIPOLA-2026-09`) y `GET /api/v1/public/locations`.

## 2. Hechos verificados

### 2.1 Lo que manda el empleado (`api/src/main/java/com/traceability/api/asset/PhysicalAssetDtos.java`)

Todas las rutas están bajo `/api/v1/physical-assets` (`PhysicalAssetController.java:46`).

| Paso | Ruta | Cuerpo | Ubicación (texto libre, obligatoria) |
|---|---|---|---|
| Registrar (Camino A) | `POST /register` (`PhysicalAssetController.java:60`) | `RegisterRequest` (`:11-12`) | `currentLocation` (`PhysicalAssetController.java:72`) |
| Registrar desde una donación (Camino B) | `POST /from-donation` (`:83`) | `RegisterFromDonationRequest` (`:15-16`) | `currentLocation` (`:95`) |
| Despachar | `POST /{assetRef}/dispatch` (`:122`) | `DispatchRequest(carrierRef)` (`:20`) | **ninguna** |
| Recibir | `POST /{assetRef}/receive` (`:131`) | `ReceiveRequest` (`:22`) | `facilityLocation` (`:136`) |
| Entregar | `POST /{assetRef}/deliver` (`:142`) | `DeliverRequest` (`:25`) | `locationRef` (`:150`) |

### 2.2 Dónde entra cada ubicación en el evento

Las rutas son relativas a `core/src/main/java/com/traceability/core/domain/physicalasset/`.

| Evento (`schemaVersion` que se escribe hoy) | Campo de ubicación | Dónde se emite |
|---|---|---|
| `ASSET_REGISTERED` 3.0 | `currentLocation` (`payloads/AssetRegisteredV3Payload.java:16`) | `PhysicalAsset.java:101` y `:153` (los dos caminos) |
| `ASSET_REGISTERED` 3.0, el **hijo de una división** | `currentLocation` = `childLocation` del padre (heredado) | `PhysicalAsset.java:178-182` |
| `ASSET_SPLIT` 3.0 | `childLocation` = `currentLocation` del padre | `PhysicalAsset.java:256-259` |
| `ASSET_DISPATCHED` 1.0 | `previousLocation` = `currentLocation` del estado (el empleado no la manda) | `PhysicalAsset.java:207-208`; al aplicarse, `currentLocation = null` (`:367`), aunque se conserva `lastKnownLocation` |
| `ASSET_RECEIVED` 1.0 | `facilityLocation` (`payloads/AssetReceivedPayload.java:10`) | `PhysicalAsset.java:220-221` |
| `ASSET_DELIVERED` 1.0 | `locationRef` (`payloads/AssetDeliveredPayload.java:13`) | `PhysicalAsset.java:307-308`. La entrega idempotente compara `locationRef` (`:291-298`) |

Las versiones se registran por `(eventType, schemaVersion)`, y la clase del payload decide cuál se escribe (`core/src/main/java/com/traceability/core/application/event/EventPayloadRegistry.java:23-41`). La 3.0 de `ASSET_REGISTERED` y `ASSET_SPLIT` es el precedente (ADR-029 Enmienda 1).

### 2.3 El hash y el anclaje

- **El hash incluye el payload completo y `schemaVersion`.** El mapa canónico se arma en `core/src/main/java/com/traceability/core/application/event/EventCanonicalMapper.java:46-58` y se resume con JCS en `core/src/main/java/com/traceability/core/infrastructure/persistence/mongo/MongoEventStoreAdapter.java:63-83`.
- **El anclaje usa ese `eventHash`** (lotes Merkle): `core/src/main/java/com/traceability/core/infrastructure/persistence/mongo/MongoStreamEventAnchorDataAdapter.java:29-32`.
- Por tanto, un campo nuevo en el payload queda **dentro del hash y del anclaje**.
- El hash se calcula solo al escribir. Los eventos viejos conservan el suyo (`ADR-029-enmienda-1-campaignref.md:35,51`).

### 2.4 Dónde se resuelve hoy la zona pública

Las tres llaman a `resolveZone(texto libre)`.

| Salida | Fuente del texto | Dónde |
|---|---|---|
| Historia pública de un activo: `GET /donations/tracking/assets/{assetRef}/history` → `locationZone` | La proyección `asset_history`, que copia el texto del evento (`core/src/main/java/com/traceability/core/application/projection/DonationProjectionHandler.java:313,320,336`). **`DISPATCHED` no lleva ubicación** (`:316-318`) | `api/src/main/java/com/traceability/api/application/mapper/AssetHistoryMapper.java:61` |
| Logística del donante: `GET /donations/tracking` → `logistics[].locationZone` | `currentLocation` de la proyección (`DonationProjectionHandler.java:243,253,283`) | `api/src/main/java/com/traceability/api/application/mapper/PublicDonationMapper.java:87` |
| Actividad de la organización: `GET /organizations/{id}/activity` → `locationZone` | El payload del evento, leído directamente | `app/src/main/java/com/traceability/app/application/activity/OrganizationActivityQuery.java:211-237` |

El contrato vigente dice que `locationZone` llega **`null` en `DISPATCHED`**, `SPLIT`, `CUSTODY_TRANSFERRED`, `SPLIT_COMPENSATED` y `DEPLETED` (`referencia-api-v1.md:319`). La zona se define como un valor derivado de `location_reference`, por consulta exacta (`documento-maestro-proyecto.md:515`; `plan-detallado-fase3.md:25-31`).

### 2.5 El catálogo

- Los nombres están en mayúsculas, tal como los publica el DANE. Por ejemplo: `08001` → "BARRANQUILLA", departamento `08` → "ATLÁNTICO".
- En Bogotá, el municipio y el departamento se llaman igual: `11001` → "BOGOTÁ, D.C." y `11` → "BOGOTÁ, D.C.".
- El puerto `LocationCatalogPort` vive en `identity` (`identity/src/main/java/identity/application/port/out/LocationCatalogPort.java`), y lo implementa `app` (`app/src/main/java/com/traceability/app/infrastructure/location/DivipolaLocationCatalog.java:24`). **`core` no lo ve.**

## 3. Propuesta

- **Campo nuevo en el cuerpo:** `municipalityCode` [Plan], el código DIVIPOLA de 5 dígitos. Va en `RegisterRequest`, `RegisterFromDonationRequest`, `ReceiveRequest` y `DeliverRequest`.
  - Es **obligatorio** (D-4.1). Si falta, o si el código no está en el catálogo → **400** (`InvalidRequestFieldException("municipalityCode")`, cuerpo `BadRequest`).
  - El departamento se deduce del catálogo.
- **`DispatchRequest` no cambia** (D-4.2).
- **El texto libre** (`currentLocation`, `facilityLocation`, `locationRef`) **se sigue pidiendo y sigue siendo privado:** nunca se muestra en una salida pública. Solo lo ve la vista operativa del empleado.
- **Lo que se sella** (D-4.3 y D-4.5): versiones nuevas del payload, con el patrón de V2 y V3, que añaden `municipalityCode`, `zoneName` (el texto "Municipio, Departamento" de ese día) y `catalogVersion` (por ejemplo, `DIVIPOLA-2026-09`) [Plan]:
  - `ASSET_REGISTERED` **4.0** (`AssetRegisteredV4Payload` [Plan]);
  - `ASSET_RECEIVED` **2.0** (`AssetReceivedV2Payload` [Plan]);
  - `ASSET_DELIVERED` **2.0** (`AssetDeliveredV2Payload` [Plan]);
  - y, según P2 y P3, `ASSET_DISPATCHED` 2.0 y `ASSET_SPLIT` 4.0.
- **La zona pública de los eventos nuevos se lee de lo sellado** (`zoneName`), nunca del catálogo actual ni de `location_reference` (D-4.5). Las proyecciones copian el valor sellado.
- **Los eventos viejos** (1.0, 2.0 y 3.0) no cambian y siguen con `location_reference` como respaldo (D-4.4 y D-4.6).
- El campo `locationZone` de las tres salidas (§2.4) **no cambia de nombre**: solo cambia de dónde sale.

## 4. Reglas (decisiones de D-4; no son preguntas)

| # | Regla | D-4 |
|---|---|---|
| R1 | `municipalityCode` es obligatorio al registrar (los dos caminos), recibir y entregar. Si falta o no está en el catálogo → 400. Es un cambio que rompe: se acepta, porque no hay usuarios reales, y se avisa | 1 |
| R2 | Despachar no lleva municipio: el lugar es el del paso anterior | 2 |
| R3 | El municipio entra en el evento sellado (hash y anclaje) como versión nueva del payload. Nunca solo en la proyección | 3 |
| R4 | Los eventos que ya existen no se tocan ni se migran. Su zona sigue saliendo de `location_reference` | 4 |
| R5 | El evento guarda el código, el nombre de ese día ("Municipio, Departamento") y `catalogVersion`. La zona pública se lee de lo sellado | 5 |
| R6 | `location_reference` no se retira: queda solo como respaldo de los eventos viejos | 6 |
| R7 | La zona pública es el municipio en todos los pasos, también en la entrega (riesgo en §5) | 7 |

**Reglas de implementación que se derivan:**
- La entrega idempotente (`PhysicalAsset.java:291-298`) también compara `municipalityCode`. Repetir con otro municipio → el mismo error de "parámetros distintos".
- Cada versión nueva se declara en el registro y en cada manejador de proyección, como pide el test de contrato B-PROJ (`EventPayloadRegistry.java:54-60`).

## 5. Privacidad

- La zona pública es **solo el municipio y su departamento**. Nunca la dirección, el texto libre, el punto exacto, el beneficiario ni a quién se entregó (`plan-r6-bloque-d.md:96`).
- **Riesgo aceptado (D-4.7):** en un municipio muy pequeño, la zona de la entrega podría ayudar a identificar al beneficiario.
- **Alternativa anotada, sin implementar:** si Carlos lo pide, la entrega mostraría solo el departamento.

## 6. Contradicciones anotadas (no se resuelven aquí)

| # | Qué choca | Dónde |
|---|---|---|
| C-1 | Hasta ahora, una versión nueva de un evento sellado se fijó con una **enmienda de ADR**: "el número de versión y la lectura de las anteriores los fija esta enmienda" | `ADR-029-enmienda-1-campaignref.md:15`; regla 3.5 (`reglas-equipo-y-agentes.md:101-109`) → **P1** |
| C-2 | `locationZone` está definido como derivado **solo** de `location_reference`. Para los eventos nuevos sale de lo sellado | `documento-maestro-proyecto.md:515`; `plan-detallado-fase3.md:25-31` |
| C-3 | El contrato dice `locationZone` `null` en `DISPATCHED`, y D-4.7 dice "el municipio en todos los pasos" | `referencia-api-v1.md:319` → **P2** |

## 7. Preguntas que D-4 no cubre (con opciones y recomendación)

| # | Pregunta | Opciones | Recomendación |
|---|---|---|---|
| **P1** | **¿Basta esta ficha o hace falta un ADR?** Cambia el esquema sellado de tres o cinco eventos, cambia las firmas de `PhysicalAssetCommandService` (las usa `api`, `PhysicalAssetController.java:48`) y necesita un puerto nuevo para que `core` valide el código (P4). El precedente es C-1, y la regla 3.5 pide un ADR antes de fusionar un cambio de contrato de un puerto usado por otro módulo | (a) Una **enmienda corta de ADR-029** ("Enmienda 2: zona DIVIPOLA en los eventos de `PhysicalAsset`"), redactada desde esta ficha y aprobada por Manuel. (b) Solo la ficha | **(a)**. Sigue el precedente de `campaignRef`, cumple la regla 3.5 y cuesta poco: la ficha ya tiene el contenido |
| **P2** | **¿Qué zona muestra `DISPATCHED`?** D-4.2 dice "el lugar es el del paso anterior", pero hoy sale `null` (C-3), y al aplicarse el despacho `currentLocation` queda en `null` (`PhysicalAsset.java:367`) | (a) Se mantiene `null`, sin cambiar el contrato. (b) Se muestra la zona del paso anterior, **sellada**: `ASSET_DISPATCHED` 2.0 [Plan] copia `municipalityCode`, `zoneName` y `catalogVersion` del estado, sin que el empleado mande nada. (c) Igual que (b), pero calculada al leer a partir del evento anterior | **(b)**. Cumple D-4.7 ("en todos los pasos") y D-4.5 ("se lee de lo sellado"), y el cuerpo de la ruta no cambia. La logística del donante también dejaría de quedar sin zona mientras el activo está en camino |
| **P3** | **La división:** ¿el hijo hereda la zona del padre? | (a) Sí: `ASSET_SPLIT` 4.0 y el `ASSET_REGISTERED` 4.0 del hijo copian el municipio sellado del padre, como se hereda `campaignRef` (ADR-029 Enmienda 1, D4). (b) El hijo queda sin zona | **(a)**. Un hijo sin zona volvería a "Sin zona pública", que es justo el problema |
| **P4** | **¿Dónde se valida el código?** `core` no ve el catálogo (§2.5) | (a) Un **puerto nuevo** en `contracts` [Plan] (por ejemplo `MunicipalityCatalogPort`, que devuelve `{zoneName, catalogVersion}` o "no existe"), implementado en `app` sobre `DivipolaLocationCatalog`. Así `core` valida el invariante para cualquier llamador. (b) `api` lo valida y le pasa a `core` el código, el nombre y la versión, ya resueltos | **(a)**. Es el criterio de ADR-029 §2.1 (`ADR-029-organization-physicalasset-donacion-especie.md:38-45`): el invariante se cumple en `core` para todos los llamadores. Sigue el precedente de `CampaignInKindEligibilityPort` (`ADR-029-enmienda-1-campaignref.md:67-68`). Con (b), `core` aceptaría cualquier nombre inventado |
| **P5** | **Formato de `zoneName`:** los nombres del catálogo están en mayúsculas, y en Bogotá el municipio y el departamento se llaman igual (§2.5) | (a) Se sella tal cual: "BARRANQUILLA, ATLÁNTICO"; en Bogotá, una sola vez, "BOGOTÁ, D.C.". La web decide cómo mostrarlo. (b) Se sella capitalizado ("Barranquilla, Atlántico"). (c) Se sella "BOGOTÁ, D.C., BOGOTÁ, D.C." | **(a)**: es el texto del catálogo de ese día, sin transformarlo (D-4.5). Repetir el nombre de Bogotá no informa nada. La regla "si los dos nombres son iguales, uno solo" queda escrita |
| **P6** | **¿La vista operativa del empleado** (`GET /physical-assets/{assetRef}`, `PhysicalAssetDtos.java:38-39`) también devuelve el municipio? | (a) Sí, `municipalityCode` y `zoneName`, de forma aditiva. (b) No | **(a)**, para que la pantalla de recibir o entregar muestre de dónde viene. Es aditivo |

## 8. Tests de la Definición de hecho (con las recomendaciones; rojos primero)

**Unitarios**

1. Cada payload nuevo se registra con su versión (`EventPayloadRegistry`) y se escribe con ella. Las 1.0, 2.0 y 3.0 se siguen leyendo.
2. El agregado aplica las versiones nuevas y las viejas. La entrega idempotente con otro `municipalityCode` → "parámetros distintos".
3. `zoneName`: "MUNICIPIO, DEPARTAMENTO"; en Bogotá, una sola vez (P5).

**Integración HTTP (Testcontainers)**

4. Registrar (los dos caminos), recibir y entregar: sin `municipalityCode` → 400; con un código que no está en el catálogo (por ejemplo, `99999`) → 400. En los dos casos **no se escribe ningún evento** (negativa).
5. Con un código válido → el evento guardado lleva `municipalityCode`, `zoneName` y `catalogVersion`, y su `eventHash` cambia si se altera cualquiera de ellos (están dentro del hash).
6. `GET /donations/tracking/assets/{assetRef}/history`: cada paso nuevo lleva `locationZone` = el `zoneName` sellado, también `DISPATCHED` (P2) y el hijo de una división (P3), **con `location_reference` vacía**.
7. **Lo sellado gana al catálogo:** si se cambia el catálogo del test después de registrar, la zona pública no cambia (D-4.5).
8. **Eventos viejos:** un evento 3.0 o 1.0 insertado con texto libre resuelve su zona por `location_reference` (respaldo) y, sin entrada, sale `null`.
9. **Negativa de privacidad:** ninguna salida pública (historia, logística, narrativa) lleva el texto libre (`currentLocation`, `facilityLocation`, `locationRef`), una dirección ni el beneficiario.
10. La logística del donante (`GET /donations/tracking`) y la actividad de la organización muestran el `zoneName` sellado.
11. `GoldenPathHttpIntegrationTest` actualizado con `municipalityCode`, y en verde.

**Común**

12. `scripts/ci-local.sh` completo en un clon limpio, con el resumen literal de Surefire.

## 9. Documentos que se actualizan con el PR de código

- `referencia-api-v1.md`: notas fechadas con el campo nuevo, el 400 y el origen de `locationZone`.
- `documento-maestro-proyecto.md`: nota en la definición de `locationZone` (C-2).
- La enmienda de ADR-029 (si P1 queda en (a)).
- `estado-fase6.md`.

## 10. Aviso a la web y al móvil

**Cambio que rompe** (D-4.1): sin `municipalityCode`, estas rutas responden 400.

| Ruta | Qué cambia en el cuerpo |
|---|---|
| `POST /api/v1/physical-assets/register` | + `municipalityCode` (obligatorio) |
| `POST /api/v1/physical-assets/from-donation` | + `municipalityCode` (obligatorio) |
| `POST /api/v1/physical-assets/{assetRef}/receive` | + `municipalityCode` (obligatorio) |
| `POST /api/v1/physical-assets/{assetRef}/deliver` | + `municipalityCode` (obligatorio) |
| `POST /api/v1/physical-assets/{assetRef}/dispatch` | Nada |

- **Pantallas de registrar, recibir y entregar** (web y móvil): un **selector de departamento y municipio**, el mismo de R-7.
  - Se llena con `GET /api/v1/public/locations`, que tiene caché de 1 día.
  - Se manda el código de 5 dígitos del municipio.
- El texto libre de la ubicación (`currentLocation`, `facilityLocation`, `locationRef`) se sigue pidiendo, como dato **privado** de la organización. El formulario puede decir "No se publica; el donante solo verá el municipio".
- **Seguimiento público:** `locationZone` llega con el municipio de cada paso (también en camino, con P2). En los pasos registrados antes de este cambio, puede seguir llegando `null`.
