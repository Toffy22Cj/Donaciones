# ADR-052 — Mapa del recorrido (M-25)

## Status

**APROBADO para implementar — encargo del equipo, 2026-11-02.** Origen: M-25 del documento «Funciones de la app móvil y pantallas del empleado» (16 de octubre): «Mapa del recorrido · Adonis (completo, con su ADR) · Backend + ubicación + mapas + ADR · Asignada, ADR primero», y la decisión 8 («Mapa sí, con ADR»). Sustituye al borrador `ADR-050-mapa-del-recorrido.md` del repositorio móvil (Propuesto, 2026-10-20), cuyo número chocaba con `ADR-050-cuenta-y-sesiones.md`. **Pendiente:** la firma de quien lo aprueba en nombre del equipo (regla 1 de `reglas-equipo-y-agentes.md`).

## Context

- El donante ve el recorrido de su aporte como una línea de tiempo (M-17): fecha, zona pública y categoría del custodio de cada paso. Quiere verlo también sobre un mapa.
- Los eventos de `PhysicalAsset` guardan **referencias** de lugar (`currentLocation`, `facilityLocation`, `locationRef`), sin coordenadas, y son inmutables.
- La única traducción pública de esas referencias es `location_reference` (Fase 3, tarea 3.6): `rawLocation → zoneName`, consulta exacta y sin heurística. Si la referencia no está, el seguimiento da `locationZone: null` («un dato ausente es preferible a uno inventado», `documento-maestro-proyecto.md`). La colección no tiene ruta HTTP: poblarla es trabajo de operación.
- La ubicación del empleado que despacha o entrega es un **dato personal** (Ley 1581 de 2012), y un punto de entrega exacto puede señalar dónde vive un beneficiario.
- Proveedor de mapas: Waze no sirve. Su Transport SDK es solo para empresas de transporte asociadas y no ofrece un mapa embebible para dibujar un recorrido propio; sus enlaces solo abren la app de Waze en un punto. OpenStreetMap es gratuito, sin clave, y `flutter_map` dibuja marcadores y líneas sobre él.

## Decision

### D1. Las coordenadas son de la zona pública, nunca del lugar exacto

`location_reference` gana dos campos opcionales, `latitude` y `longitude` (WGS84, grados decimales): **el punto representativo de la zona pública** (`zoneName`), por ejemplo el centro del municipio o del sector, nunca la dirección de la bodega o del punto de entrega. Todas las referencias de la misma zona llevan el mismo punto.

- Van los dos o ninguno; `latitude` en [-90, 90] y `longitude` en [-180, 180]. Una entrada con un solo campo o fuera de rango se trata como **sin coordenadas** (nunca un punto inventado).
- Los eventos no cambian: las coordenadas viven fuera de la cadena de custodia y se pueden corregir o borrar sin tocar nada anclado.
- Una entrada sin coordenadas sigue dando su `zoneName` como hoy.

### D2. Contrato: el punto de la zona en el seguimiento, sin rutas nuevas

En las dos lecturas que ya dan `locationZone`, con la misma credencial (`trackingCode`):

- `GET /donations/tracking`: cada ítem de `logistics` gana **`zonePoint?: {latitude, longitude}`**.
- `GET /donations/tracking/assets/{assetRef}/history`: cada ítem de `history` gana **`zonePoint?: {latitude, longitude}`**.

`zonePoint` sale **solo si** `locationZone` sale y su entrada tiene coordenadas válidas (D1). Si no, falta, como cualquier `campo?` (referencia §0). Ningún otro campo cambia. No hay ruta nueva ni lectura del catálogo por fuera del seguimiento.

### D3. Nunca la ubicación del teléfono

La app no pide permiso de ubicación ni lee el GPS. El mapa dibuja solo los `zonePoint` del recorrido. Si algún día se quisiera la ubicación real del empleado, haría falta otro ADR con consentimiento explícito, finalidad y borrado.

### D4. Población del catálogo

Sigue siendo trabajo de operación, sin ruta HTTP (tarea 3.6). `LocationReferenceService` gana `register(rawLocation, zoneName, latitude?, longitude?)`, que valida D1 y guarda o sustituye la entrada. Lo usan las semillas de la demo (perfil `dev`, como sus demás altas sin ruta, DD-77) para que la demo muestre zonas y mapa. Una ruta administrativa para el catálogo, si hace falta, va en otro ADR.

### D5. La app: `flutter_map` + `latlong2` sobre OpenStreetMap

- **Dependencias nuevas** (enmienda de ADR-043 A2/D12): `flutter_map` y `latlong2`. Nada más: sin geolocalización ni caché de teselas en disco.
- **Teselas:** la URL se configura al compilar (`PAXFIDE_MAP_TILE_URL`, como las demás de `AppConfig`); en desarrollo, la de OpenStreetMap. La atribución «© OpenStreetMap contributors» se ve siempre sobre el mapa y las peticiones llevan el identificador de la app como `User-Agent` (política de uso de las teselas de OSM). **Producción necesita un proveedor de teselas** (o uno propio): las teselas públicas de OSM no admiten ese tráfico. Sin URL configurada, el mapa no se muestra.
- **Qué dibuja**, en la sección «Recorrido» del seguimiento, bajo el árbol de lotes:
  - por cada lote, sus paradas en el orden del historial, con los pasos sin `zonePoint` omitidos y dos paradas seguidas en el mismo punto unidas en una;
  - una línea entre las paradas de cada lote y un marcador por parada; la última parada del lote, resaltada;
  - el encuadre ajustado a todas las paradas.
- **Qué no hace:** no sustituye a la línea de tiempo, que sigue siendo la fuente de la verdad. Si ningún paso tiene `zonePoint`, la sección del mapa no aparece. Sin conexión, las teselas no cargan; los marcadores y la línea se siguen viendo, con un aviso.
- El `trackingCode` no cambia de sitio: sigue solo en memoria (ADR-043 §0). Las peticiones de teselas no llevan nada del seguimiento.

## Alternatives

| Alternativa | Por qué no |
|---|---|
| Coordenadas exactas del lugar (bodega, punto de entrega) | Un punto de entrega puede señalar el domicilio de un beneficiario (Ley 1581); la zona basta para el relato |
| GPS del teléfono del empleado en cada evento | Dato personal; pide permiso, consentimiento, finalidad y borrado; y cambiaría eventos inmutables |
| Coordenadas dentro de los eventos | Inmutables y anclados: no se podrían corregir ni borrar |
| Geocodificar `zoneName` en el teléfono con un servicio externo | Poco fiable (nombres ambiguos) y envía las zonas del recorrido a un tercero |
| DIVIPOLA como fuente de coordenadas | El catálogo DIVIPOLA del backend (R-7) trae códigos y nombres, sin coordenadas |
| Waze | Sin mapa embebible para dibujar un recorrido propio |
| Google Maps (`google_maps_flutter`) | Necesita clave y cuenta de facturación, y otra dependencia de Google |

## Consequences

- **Backend:** dos campos opcionales en `location_reference`, `register` en `LocationReferenceService`, `zonePoint?` en dos DTO públicos, semillas de la demo con coordenadas. Sin migración: las entradas existentes quedan sin coordenadas.
- **Móvil:** dos dependencias; el mapa en el seguimiento; `PAXFIDE_MAP_TILE_URL` en la configuración.
- **Riesgos aceptados:**
  - Una zona muy pequeña podría acercarse a un lugar concreto: el catálogo debe usar zonas de nivel municipio o sector, nunca un punto de entrega (lo vigila quien lo puebla).
  - Las teselas de OSM solo sirven para desarrollo y demo.
- **Tests:**
  - backend: `zonePoint` sale con coordenadas válidas y falta sin ellas, con una sola o fuera de rango, en las dos lecturas; `register` valida D1;
  - móvil: el parser lee `zonePoint` y descarta valores inválidos; las paradas se ordenan, omiten los pasos sin punto y unen los repetidos; sin ningún punto no hay mapa; la atribución siempre se ve.
