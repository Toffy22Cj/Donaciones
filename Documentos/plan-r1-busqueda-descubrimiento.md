# Plan R-1 — Búsqueda, filtro y orden en el descubrimiento público

**Estado: PROPUESTO — espera la aprobación de Carlos (regla 3.4). No se escribe código antes.**

> **Nota (2026-10-29): APROBADO por Carlos.** Texto literal de Carlos: "plan-r1-busqueda-descubrimiento.md: PROPUESTO, nunca aprobado. Pásalo aprobado". Además: "Todo está aprobado" (`decisiones-delegadas-2026-10.md`, nota del 2026-10-29, C-2; comunicado por Manuel). "PROPUESTO" se conserva; esta nota lo supera.
>
> **Revisado contra `develop` `993c3e8` (el plan se escribió sobre `4bef7be`): los hechos siguen valiendo.**
> - `findPublicOpenAfter` solo lo llama `PublicCampaignDiscoveryQuery` (`convocatoria/src/main/java/com/traceability/convocatoria/application/query/PublicCampaignDiscoveryQuery.java:44`). Se declara en `ConvocatoriaRepositoryPort.java:42` y se implementa en `MongoConvocatoriaRepositoryAdapter.java:98`.
> - `MongoConvocatoriaRepositoryAdapter` es la única implementación de `ConvocatoriaRepositoryPort` (`MongoConvocatoriaRepositoryAdapter.java:21`). No hay dobles de test que la implementen.
> - Los campos de `ConvocatoriaDocument` que usa el plan existen: `visibility`, `status`, `startDate`, `endDate`, `publicCode`, `title` y `acceptedDonationTypes` (`ConvocatoriaDocument.java:22-30`). `titleSearch` no existe todavía, como espera el plan. Desde `4bef7be` se añadió `coverObjectKey` (`:38`, ADR-051 T3).
> - Los archivos existentes del §2 y del §3 están donde dice el plan: `ConvocatoriaMapper.java`, `DiscoveryCursorCodec.java`, `PublicCampaignDiscoveryController.java`, `AbstractConvocatoriaMongoIntegrationTest.java`, `DiscoveryCursorCodecTest.java`, `MongoIndexesAtStartupIntegrationTest.java` y `P2EndpointsHttpIntegrationTest.java`. `DiscoveryCursorCodec` cifra hoy en US-ASCII (`DiscoveryCursorCodec.java:82` y `:100`). Los archivos nuevos no existen todavía.
> - `title` sigue sin poder editarse (`Convocatoria.java:42`, `final`). Las escrituras posteriores a la creación son `$set` de campos concretos (`MongoConvocatoriaRepositoryAdapter.java:47-80`), así que no borran `titleSearch`.
>
> **El §6 (evidencia pegada por un humano) queda superado.** La evidencia sigue el ciclo del encargo del 2026-10-29: rojo primero con la salida literal, verde por capas con el módulo completo y el resumen literal de Surefire, mutaciones con `scripts/mutaciones.py` y `scripts/ci-local.sh` en un clon limpio, con la salida en `Documentos/evidencia-ci/`. El §6 se conserva.

> **Nota (2026-10-29): implementado** en #159 (`feat/convocatoria-r1-busqueda-descubrimiento`), con los archivos del §2 y los tests del §3. La evidencia está en `estado-fase6.md` §0.71.
**Fecha:** 2026-10-18 (UTC).
**Contrato:** `ficha-R1-busqueda-descubrimiento-convocatorias.md` (CONGELADA). Este plan no decide nada que la ficha no fije; si al implementar aparece un caso no cubierto, se detiene y se pregunta (regla 2.1).
**Base:** `develop` en `4bef7be`. **Rama:** `feat/convocatoria-r1-busqueda-descubrimiento`.
**Dueño:** Carlos (módulo `convocatoria`); el controlador vive en `app`.

## 1. Alcance

Dentro: los parámetros `q`, `donationType` y `sort`, el cursor versión 2, el campo `titleSearch` con su relleno al arrancar y los dos índices.

Fuera: filtros por estado u organización (B1, B2), campos de imagen de ADR-051, cambios en CV-07, cualquier otra ruta.

## 2. Cambios por módulo

Cada clase se justifica contra la ficha R-1 (y, por herencia, ADR-037 y ADR-041). No se crean clases fuera de esta lista sin preguntar.

### `convocatoria`

| Archivo | Cambio | Ficha |
|---|---|---|
| `application/query/SearchTextNormalizer.java` (nuevo) | Clase final con un método estático `normalize(String)`: NFD, quitar `\p{M}`, `toLowerCase(Locale.ROOT)`. Sin dependencias de Spring | §4 |
| `application/query/PublicCampaignDiscoveryQuery.java` | `page(String)` pasa a `page(Criteria)`. Registros anidados nuevos, como los que ya tiene: `Criteria(String textQuery, DonationType donationType, Sort sort, Position after)`, `Position(Instant lastSortValue, String lastPublicCode)`, `enum Sort {DEFAULT, ENDING_SOON, RECENT}`. `Page` devuelve `nextPosition` (o nulo en la última página) en lugar de `lastPublicCode`. `textQuery` llega ya normalizado | §3, §5 |
| `application/port/out/ConvocatoriaRepositoryPort.java` | `findPublicOpenAfter(String, int)` se sustituye por `findPublicOpenPage(Criteria, int limit)`. Verificado el 2026-10-18 en `4bef7be`: el único llamador es `PublicCampaignDiscoveryQuery` y la única implementación es `MongoConvocatoriaRepositoryAdapter` (no hay dobles de test que la implementen) | §3, §5 |
| `infrastructure/persistence/mongo/document/ConvocatoriaDocument.java` | Campo `titleSearch`. `@CompoundIndexes` con `idx_discovery_ending_soon` e `idx_discovery_recent` | §4, §6 |
| `infrastructure/persistence/mongo/mapper/ConvocatoriaMapper.java` | `toDocument` escribe `titleSearch = SearchTextNormalizer.normalize(title)`. `toDomain` lo ignora: no es dominio | §4 |
| `infrastructure/persistence/mongo/adapter/MongoConvocatoriaRepositoryAdapter.java` | `findPublicOpenPage`: siempre `visibility = PUBLIC` y `status = OPEN`; `titleSearch` con `Pattern.compile(Pattern.quote(q))` si hay `q`; `acceptedDonationTypes` contiene el tipo si hay `donationType`; condición de continuación y orden según `Sort` (§5 de la ficha) | §3, §5 |
| `infrastructure/persistence/mongo/ConvocatoriaTitleSearchBackfill.java` (nuevo) | `InitializingBean`: recorre las convocatorias sin `titleSearch` y lo escribe con una actualización condicional (`_id` y `titleSearch` inexistente). Se ejecuta al arrancar, antes de servir peticiones. Registra solo el número de convocatorias rellenadas | §4 |

### `app`

| Archivo | Cambio | Ficha |
|---|---|---|
| `web/campaign/PublicCampaignDiscoveryController.java` | Lee `q`, `donationType`, `sort` y `cursor`; valida en el orden de la ficha (§3) con `InvalidRequestFieldException`; normaliza `q`; decodifica el cursor y comprueba que filtros y orden coinciden; construye `Criteria`; codifica `nextCursor` v2 | §3, §5 |
| `web/campaign/DiscoveryCursorCodec.java` | El texto cifrado pasa de US-ASCII a UTF-8, para poder llevar `q` con tildes. Los cursores ya emitidos siguen descifrando igual (ASCII es subconjunto de UTF-8). Sin cambio de clave ni de formato binario | §5 |
| `web/campaign/DiscoveryCursorPayload.java` (nuevo) | Registro `{v, q, donationType, sort, lastSortValue, lastPublicCode}` y su conversión a JSON con el `ObjectMapper` de la aplicación. Distingue un cursor v1 (texto con formato de `publicCode`) de uno v2 (JSON con `v = 2`) | §5 |

**Sin cambios:** `PublicRoutes` (la ruta ya es pública), `contracts`, `identity`, `core`. Ninguna dependencia nueva.

## 3. Tests (rojos primero)

| Test | Módulo | Cubre (ficha §8) |
|---|---|---|
| `SearchTextNormalizerTest` (nuevo) | `convocatoria` | 1 |
| `MongoConvocatoriaDiscoveryIntegrationTest` (nuevo, extiende `AbstractConvocatoriaMongoIntegrationTest`) | `convocatoria` | 3, 5, 6, 7, 10, 11 a nivel de repositorio; 13 (relleno idempotente) |
| `DiscoveryCursorCodecTest` (ampliado) | `app` | 2 (UTF-8, v1 sigue valiendo) |
| `PublicCampaignDiscoveryHttpIntegrationTest` (nuevo, Testcontainers) | `app` | 3 a 12 por HTTP, incluida la aserción negativa de `PRIVATE_LINK` y `CLOSED` con todos los filtros y órdenes |
| `MongoIndexesAtStartupIntegrationTest` (ampliado) | `app` | 14: los dos índices con sus claves exactas |
| `P2EndpointsHttpIntegrationTest` (sin cambios esperados) | `app` | 12: sin parámetros, el comportamiento de antes |

El test 7 crea más de 20 convocatorias con fechas repetidas y recorre todas las páginas en los tres órdenes, comprobando que cada convocatoria esperada aparece exactamente una vez.

## 4. Pasos

1. Rama desde `develop`. Línea base: `mvn -B clean test -fae` del reactor, con el resumen de Surefire.
2. Escribir los tests de §3 y comprobar que fallan por la razón esperada (no por compilación de otra cosa).
3. `convocatoria`: normalizador, criterios, puerto, adaptador, mapeador, índices y relleno. `mvn test -pl convocatoria` completo.
4. `app`: codec, carga del cursor y controlador. `mvn test -pl app` completo.
5. `scripts/ci-local.sh` sobre el último commit.
6. Actualizar los documentos de §9 de la ficha en el mismo PR.
7. PR hacia `develop` con el resumen literal de Surefire y una aprobación humana (regla 3.2).

## 5. Riesgos

- **Búsqueda sin índice:** recorre las convocatorias públicas abiertas que pasan los demás filtros. Aceptado para el volumen del proyecto (ficha §6).
- **Ventana del relleno:** si una convocatoria se crea mientras corre el relleno, ya llega con `titleSearch` (lo escribe el mapeador); el relleno solo escribe donde el campo no existe, así que no la pisa.
- **Cursor con `q` largo:** como mucho 100 puntos de código normalizados; el cursor sigue siendo corto.

## 6. Entorno de ejecución

En la sesión de agente del 2026-10-18 el proxy rechaza `repo.maven.apache.org` y `repo.spring.io`, así que Maven no resuelve dependencias y no se pueden ejecutar los tests desde ahí. Docker sí funciona. Por la regla derivada del incidente de la Tarea 4 (`estado-fase6.md` §7.2), la evidencia de tests la ejecuta y la pega el humano en cualquier caso.
