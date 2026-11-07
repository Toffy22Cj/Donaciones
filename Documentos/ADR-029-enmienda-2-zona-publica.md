# ADR-029 — Enmienda 2: la zona pública DIVIPOLA en los eventos de `PhysicalAsset`

**Estado:** **PROPUESTA.** La aprueba Manuel, dueño del backend (Carlos le delegó el tema el 2026-11-02), con un comentario en el PR. La implementación necesita además su plan aprobado (`plan-zona-en-cada-paso.md`, regla 3.4).

> **Nota (2026-11-02): implementada** en `feat/core-zona-en-cada-paso`, con el plan aprobado en #170. La evidencia está en `estado-fase6.md` §0.74.

> **Nota (2026-11-02): APROBADA por Manuel**, dueño del backend (comentario de `buelvasmanuel` en #170, 2026-11-02T22:10:26Z, https://github.com/Toffy22Cj/Donaciones/pull/170#issuecomment-6589733853). "PROPUESTA" se conserva; esta nota lo supera. **E-1 queda en (a).** Texto literal:
>
> Apruebo la enmienda `Documentos/ADR-029-enmienda-2-zona-publica.md` y el plan `Documentos/plan-zona-en-cada-paso.md` (cabeza `0054a55`), como dueño del backend.
>
> - **E-1:** si un activo registrado antes del cambio se despacha o se divide, el evento nuevo se escribe con los tres campos de zona vacíos y la zona pública sale de `location_reference`, como hoy. No se usa la versión vieja del evento ni se rechaza el despacho.
> - Las clases de payload que ya existen no se tocan: solo se añaden versiones nuevas (`RecomputingEventIntegrityChecker.java:101-107`).
>
> Autorizo el código según este plan, sobre `develop` (que ya incluye #169). Antes de fusionar #170, hay que integrar `develop` con un merge normal.
>
> — Manuel, 2026-11-02

**Origen:** la ficha `ficha-zona-en-cada-paso.md`, **CONGELADA por Manuel el 2026-11-02**:
- comentario en #169: https://github.com/Toffy22Cj/Donaciones/pull/169#issuecomment-6589479007;
- P1 dice: "enmienda corta de ADR-029, escrita a partir de esta ficha";
- las decisiones D-3 y D-4 están en `decisiones-delegadas-2026-10.md`, nota del 2026-11-02.

**Base verificada:** `develop` `3726414`. Cada ruta:línea es de esa base.

---

## 1. Qué está decidido antes de esta enmienda (vinculante, no se reabre)

| Decisión | Fuente |
|---|---|
| La zona pública viene en los datos de cada paso, no de llenar `location_reference` a mano (D-3) | `decisiones-delegadas-2026-10.md`, nota del 2026-11-02 |
| Registrar (por los dos caminos), recibir y entregar exigen el código de municipio DIVIPOLA. Sin él, o con un código que no está en el catálogo → 400 (D-4.1) | ídem; ficha R1 |
| Despachar no lleva municipio (D-4.2), pero sella la zona del paso anterior, copiada del estado del activo (P2) | ficha R2 y nota de congelación |
| El municipio entra en el **evento sellado** como versión nueva del payload, nunca solo en la proyección (D-4.3) | ficha R3 |
| Los eventos existentes no se tocan ni se migran: su zona sigue saliendo de `location_reference` (D-4.4 y D-4.6) | ficha R4 y R6 |
| Se sella el código, el texto de ese día ("MUNICIPIO, DEPARTAMENTO", tal cual del catálogo, con Bogotá una sola vez) y la versión del catálogo. La zona pública se lee de lo sellado (D-4.5, P5) | ficha R5 y nota de congelación |
| El sublote hereda la zona sellada, como hereda la convocatoria (P3) | nota de congelación |
| La validación va en `core`, con un puerto nuevo en `contracts` que implementa `app` (P4) | nota de congelación |
| Precedentes: una versión nueva de un evento sellado se fija con una enmienda de este ADR, y no hay *backfill* | `ADR-029-enmienda-1-campaignref.md:15,17` |

## 2. Hechos verificados en el código

Rutas relativas a `core/src/main/java/com/traceability/core/`, salvo que se indique otra.

| Hecho | Evidencia |
|---|---|
| El registro de payloads se indexa por `(eventType, schemaVersion)`, y la versión que se escribe la decide la clase. Hoy: `ASSET_REGISTERED` 1.0/2.0/3.0, `ASSET_SPLIT` 1.0/2.0/3.0, y 1.0 para `ASSET_DISPATCHED`, `ASSET_RECEIVED` y `ASSET_DELIVERED` | `application/event/EventPayloadRegistry.java:23-41` |
| El hash incluye el **payload completo** y `schemaVersion` | `application/event/EventCanonicalMapper.java:46-58`; `infrastructure/persistence/mongo/MongoEventStoreAdapter.java:63-83` |
| El anclaje (lotes Merkle) usa ese `eventHash` | `infrastructure/persistence/mongo/MongoStreamEventAnchorDataAdapter.java:29-32` |
| **La verificación de integridad recalcula el hash:** deserializa el payload guardado con la clase de su `(eventType, schemaVersion)` y lo vuelve a canonicalizar | `application/integrity/RecomputingEventIntegrityChecker.java:20-24,75-82,101-107`; `EventCanonicalMapper.java:66-69` |
| Las ubicaciones que se sellan hoy son texto libre: `currentLocation` (registrar, y el hijo de la división), `childLocation` (división), `previousLocation` (despacho, copiada del estado), `facilityLocation` (recibir) y `locationRef` (entregar) | `domain/physicalasset/PhysicalAsset.java:101,153,178-182,207-208,220-221,256-259,307-308` |
| Al aplicarse el despacho, `currentLocation = null`; `lastKnownLocation` se conserva | `PhysicalAsset.java:364-367` |
| El precedente de un puerto en `contracts` que `core` consulta antes de persistir: `CampaignInKindEligibilityPort` | `contracts/src/main/java/com/traceability/contracts/campaign/CampaignInKindEligibilityPort.java:9`; `application/command/PhysicalAssetCommandService.java:296-302` |
| `core` no ve el catálogo DIVIPOLA, que está detrás de un puerto de `identity` implementado en `app` | `identity/src/main/java/identity/application/port/out/LocationCatalogPort.java`; `app/src/main/java/com/traceability/app/infrastructure/location/DivipolaLocationCatalog.java:24` |

## 3. Decisión propuesta

### D1. Los campos que se sellan

Cada payload nuevo añade, al final y planos (como `campaignRef` en la 3.0), tres campos [Plan]:

| Campo [Plan] | Valor | Ejemplo |
|---|---|---|
| `municipalityCode` | Código DIVIPOLA de 5 dígitos | `08001` |
| `zoneName` | `"<MUNICIPIO>, <DEPARTAMENTO>"`, con los nombres **tal cual** del catálogo de ese día. Si los dos nombres son iguales, uno solo | `BARRANQUILLA, ATLÁNTICO`; `BOGOTÁ, D.C.` |
| `catalogVersion` | `catalogVersion` del catálogo con que se validó | `DIVIPOLA-2026-09` |

El texto libre que ya existe (`currentLocation`, `facilityLocation`, `locationRef`...) **se conserva** en el payload, como dato privado.

### D2. Versiones nuevas

| Evento | Versión nueva | Clase [Plan] | Contenido | Cuándo |
|---|---|---|---|---|
| `ASSET_REGISTERED` | **4.0** | `AssetRegisteredV4Payload` | 3.0 + D1 | Registrar por el Camino A y el B (zona del comando), y el hijo de una división (zona heredada, D4) |
| `ASSET_SPLIT` | **4.0** | `AssetSplitV4Payload` | 3.0 + D1 (la zona del padre, que hereda el hijo) | Dividir |
| `ASSET_DISPATCHED` | **2.0** | `AssetDispatchedV2Payload` | 1.0 + D1 (la zona del paso anterior, copiada del estado) | Despachar |
| `ASSET_RECEIVED` | **2.0** | `AssetReceivedV2Payload` | 1.0 + D1 (zona del comando) | Recibir |
| `ASSET_DELIVERED` | **2.0** | `AssetDeliveredV2Payload` | 1.0 + D1 (zona del comando) | Entregar |

- **Toda escritura nueva usa estas versiones.** Las anteriores solo se leen.
- **Las clases existentes no cambian ni un campo:** la verificación de integridad las deserializa para recalcular el hash (§2). Añadirles un campo cambiaría el mapa canónico de los eventos viejos y daría `HASH_MISMATCH`.
- `ASSET_CUSTODY_TRANSFERRED`, `ASSET_DEPLETED` y `ASSET_SPLIT_COMPENSATED` no tienen ubicación y **no cambian**.

### D3. Validación en `core` (P4)

- **Puerto nuevo de solo lectura en `contracts`** [Plan]: `MunicipalityCatalogPort`, con `Optional<MunicipalityEntry> find(String municipalityCode)`.
  - `MunicipalityEntry` [Plan] = `{municipalityCode, municipalityName, departmentCode, departmentName, catalogVersion}`.
  - Lo implementa `app` sobre el mismo catálogo DIVIPOLA que ya carga al arrancar.
- **`core` compone `zoneName`** (regla de D1) en un valor de dominio [Plan], para que la regla de Bogotá viva en `core` y tenga su propio test.
- **Orden, como con `campaignRef`:** autorizar → validar el código → persistir. Un código que no existe → `UnknownMunicipalityCodeException` [Plan] → **400**, **sin escribir ningún evento**. Si falta el código, `api` responde 400 sin llamar a `core`.
- Al despachar o dividir no se consulta el puerto: se copia la zona ya sellada en el estado.

### D4. El sublote hereda la zona (P3)

- `ASSET_SPLIT` 4.0 lleva la zona del padre.
- El `ASSET_REGISTERED` 4.0 del hijo la copia del `SplitRecord`, igual que `campaignRef` (ADR-029 Enmienda 1, D4).

### D5. Lectura de la zona pública

- **Regla única:** si el evento tiene `zoneName` sellado, se usa ese.
- **Si no lo tiene** (versiones anteriores, o E-1): se usa `location_reference` con el texto libre del mismo evento, como hasta hoy (`core/src/main/java/com/traceability/core/application/service/LocationReferenceService.java:18-23`). Sin entrada, sale `null`.
- **Nunca se lee el catálogo actual** para un evento ya escrito.
- **Se aplica en las tres salidas:** la historia pública, la logística del donante y la actividad de la organización. El detalle está en el plan.

### D6. Inmutabilidad, sin *backfill*

- Los eventos ya escritos no se tocan ni se migran, y sus hashes y su anclaje no cambian.
- Las proyecciones existentes no se reconstruyen: los pasos viejos siguen con el respaldo.

## 4. Efecto en el hash y el anclaje

- Los tres campos de D1 entran en el mapa canónico (`EventCanonicalMapper.java:46-58`) y, por tanto, en el `eventHash`, en la cadena `previousHash` y en el lote Merkle anclado. **La zona de cada paso nuevo queda sellada y anclada.**
- Los eventos 1.0, 2.0 y 3.0 se recalculan con su clase, que no cambia: **el mismo hash antes y después** (test del plan).
- No cambia la canonicalización (JCS), ni el algoritmo de hash, ni el Merkle, ni el contrato de anclaje (ADR-039).

## 5. Regla 3.5

Esta enmienda es el ADR que pide la regla 3.5 (`reglas-equipo-y-agentes.md:101-109`) para tres cosas:
- **un puerto nuevo en `contracts`** (`MunicipalityCatalogPort`) que usan dos módulos;
- **el cambio de contrato de `PhysicalAssetCommandService`**, que usa `api` (`api/src/main/java/com/traceability/api/asset/PhysicalAssetController.java:48`): `registerPhysicalAsset`, `registerPhysicalAssetFromDonation`, `receiveAsset` y `deliverAsset` reciben el código de municipio;
- **versiones nuevas de eventos sellados.**

No introduce dependencias nuevas, no cambia el límite de consistencia del agregado y no añade mecanismos de concurrencia ni de reintento.

## 6. Lo que esta enmienda no decide

- Mostrar solo el departamento en la entrega: es la alternativa de D-4.7, anotada y no implementada.
- Retirar `location_reference` (D-4.6: se queda como respaldo).
- Cómo presenta el front el texto en mayúsculas (P5).

## 7. Pregunta para Manuel (no la cubre la ficha)

| # | Pregunta | Opciones | Recomendación |
|---|---|---|---|
| **E-1** | Un activo registrado **antes** de este cambio no tiene zona sellada en su estado. Si después se **despacha** o se **divide**, ¿qué se sella? (Recibir y entregar sí traen código, porque es obligatorio) | (a) Se escribe la versión nueva con los tres campos de D1 en `null`, y la lectura aplica el respaldo de D5 con el texto libre del mismo evento. (b) Se escribe la versión vieja (1.0 o 3.0) para ese activo. (c) Se rechaza el despacho o la división hasta que el activo tenga zona | **(a)**. La regla de escritura es una sola ("toda escritura nueva usa la versión nueva") y la de lectura también (D5). (b) mezcla versiones según el estado, y (c) bloquea activos legítimos. Hoy no hay usuarios reales, así que el caso es de datos de prueba |

## 8. Consecuencias

**Positivas**
- La zona pública de cada paso nuevo queda **dentro del evento sellado y anclado**: se puede verificar y no depende de una tabla que nadie llena.
- No se toca el histórico ni sus hashes.

**Negativas / restricciones**
- Es un cambio que rompe para la web y el móvil: `municipalityCode` es obligatorio en cuatro rutas (D-4.1, aceptado porque no hay usuarios reales).
- Hay cinco clases de payload nuevas, y cada manejador de proyección debe declararlas (B-PROJ).
- Mientras haya eventos viejos, conviven dos fuentes de zona: la sellada y el respaldo.
