# Ficha R-1 — Búsqueda, filtro y orden en el descubrimiento público de convocatorias

**Naturaleza:** ficha de contrato HTTP, en el formato de `ficha-S18-estado-verificacion-organizacion.md`. **No es un ADR y no autoriza código** por sí sola: el código sigue el plan `plan-r1-busqueda-descubrimiento.md`, que necesita su propia aprobación (regla 3.4).
**Estado: CONGELADA** — decisiones B1 a B7 de Carlos, 2026-10-18T18:45Z (§7).

> **Nota (2026-10-29), anotada sin resolver (regla: contradicción con una ficha CONGELADA).** Tras congelarse esta ficha, el descubrimiento ganó campos por cada ítem que la tabla del §2 (línea "Forma de cada ítem") no lista:
> - `coverImageUrl?` (ADR-051, Tarea 3): `app/src/main/java/com/traceability/app/web/campaign/PublicCampaignDiscoveryController.java:36` y `:68`;
> - también `organizationLogoUrl?` (ADR-051 F6, `:35` y `:67`) y `organizationVerified` (ficha L-FRONTS A1, M-07, `:33` y `:63`).
>
> El §3 ya excluye los campos de imagen de ADR-051 "que entran con las tareas de ese ADR". R-1 no cambia la forma de la respuesta, así que **no hay conflicto**. La tabla del §2 no se toca.

> **Nota (2026-10-29): implementada** en #159, sin cambios de contrato. Los tests 1 a 14 del §8 están en `SearchTextNormalizerTest`, `MongoConvocatoriaDiscoveryIntegrationTest`, `DiscoveryCursorCodecTest`, `PublicCampaignDiscoveryHttpIntegrationTest` y `MongoIndexesAtStartupIntegrationTest`. El test 15 (`ci-local.sh`) está en `estado-fase6.md` §0.71.
**Fecha:** 2026-10-18 (UTC).
**Autor:** Carlos (dueño del módulo `convocatoria`), con asistencia de Claude.
**Origen:** M-10 de la lista del móvil (16 de octubre) y tarea R-1 del backend de Carlos.
**Sustituye:** Q-v2-3 de `propuesta-apis-fase6.md` (opción (a), "sin filtros") y el apartado P2.6 de `fichas-p2-endpoints.md` en lo que contradiga esta ficha. Lo que esta ficha no toca de P2.6 sigue vigente.
**No requiere enmendar ADR-041:** ADR-041 no regula los filtros del descubrimiento; la decisión vivía en Q-v2-3 y P2.6.

---

## 1. Problema

`GET /api/v1/public/campaigns` solo acepta `?cursor=` y ordena por `publicCode`. El donante no puede buscar una convocatoria por su título, ni ver primero las que están por cerrar, ni separar las que aceptan dinero de las que aceptan donaciones en especie.

## 2. Lo que no cambia (P2.6 y decisiones vigentes)

| Regla | Fuente |
|---|---|
| Ruta pública, sin JWT | P2.6; `PublicRoutes` |
| **Nunca** convocatorias `PRIVATE_LINK`; el filtro está en la consulta | Requisito no negociable de `propuesta-apis-fase6.md` §3.3; P2.6 |
| Solo convocatorias `OPEN`; una cerrada se ve por su código (CV-07), no se descubre | DD-52 (B1 la mantiene) |
| 20 por página; `nextCursor` ausente en la última; cursor inválido → 400 | P2.6; T-35 |
| Cursor opaco cifrado y autenticado con AES-256-GCM (`DiscoveryCursorCodec`, `TRACEABILITY_DISCOVERY_CURSOR_KEY`) | DD-58 |
| Forma de cada ítem: `{publicCode, title, organizationName, status, startDate, endDate, acceptedDonationTypes, currency?, targetAmount?, clearedAmount?}`; sin `campaignRef` ni `organizationRef` | P2.6 |

## 3. Contrato

`GET /api/v1/public/campaigns?q=&donationType=&sort=&cursor=` — todos los parámetros opcionales. La respuesta no cambia de forma.

| Parámetro | Valores | Regla |
|---|---|---|
| `q` | texto | Se recorta. Tras recortar, de **2 a 100 puntos de código**; fuera de rango → 400 `InvalidRequestField` (`q`). Busca como **subcadena** en el título, sin distinguir mayúsculas ni tildes (§4). Si se envía, se valida aunque quede vacío tras recortar (`?q=` o solo espacios → 400). El cliente omite el parámetro cuando no busca |
| `donationType` | `MONETARY` \| `IN_KIND` | La convocatoria debe aceptar ese tipo (`acceptedDonationTypes` lo contiene). Otro valor → 400 `InvalidRequestField` (`donationType`) |
| `sort` | `ending_soon` \| `recent` | `ending_soon`: `endDate` ascendente, desempate `publicCode` ascendente. `recent`: `startDate` descendente, desempate `publicCode` ascendente. **Sin `sort`: `publicCode` ascendente, como hoy.** Otro valor → 400 `InvalidRequestField` (`sort`) |
| `cursor` | opaco | §5 |

**Orden de validación:** `q`, `donationType`, `sort` y por último `cursor`. Cada error nombra un solo campo, el primero que falla.

> **Nota (2026-10-29), anotada sin resolver:** el cuerpo de la respuesta 400 **no** nombra el campo. Es el `BadRequest` fijo de `InvalidRequestFieldException` (`api/src/main/java/com/traceability/api/web/InvalidRequestFieldException.java:7-10`), igual que en todas las rutas, mientras esta tabla dice "400 `InvalidRequestField` (`q`)". Manuel lo acepta (#159, https://github.com/Toffy22Cj/Donaciones/pull/159#issuecomment-6504628005): R-1 no cambia ese cuerpo solo para sí. El campo que nombra cada error se comprueba llamando al controlador (`PublicCampaignDiscoveryHttpIntegrationTest`, `rejects`). El texto de arriba se conserva.

**Excluido a propósito:** filtro por estado (B1), por organización (B2), búsqueda en la descripción o en el nombre de la organización, y los campos de imagen de ADR-051 (`coverImageUrl?`, `organizationLogoUrl?`), que entran con las tareas de ese ADR.

## 4. Búsqueda sin mayúsculas ni tildes

- El documento de persistencia `ConvocatoriaDocument` gana un campo `titleSearch`. **No** es un campo del dominio: lo calcula el mapeador de infraestructura.
- Normalización, la misma para el título y para `q`:
  1. `Normalizer.normalize(texto, Normalizer.Form.NFD)`;
  2. quitar las marcas diacríticas combinantes (`\p{M}`);
  3. `toLowerCase(Locale.ROOT)`.
  Así "Educación", "EDUCACION" y "educación" quedan como `educacion`.
- La búsqueda es una expresión regular de subcadena sobre `titleSearch`, construida con `Pattern.quote(qNormalizado)`: ningún carácter de `q` actúa como operador.
- `title` no se puede editar en el dominio (`Convocatoria.title` es inmutable), así que `titleSearch` solo se escribe al crear.
- **Convocatorias existentes:** al arrancar, un proceso rellena `titleSearch` en las que no lo tienen, con una escritura condicional (solo si el campo sigue sin existir). Es idempotente: con todas rellenas, no hace nada.

## 5. Cursor versión 2

- Contenido cifrado (AES-256-GCM, la misma clave): `{v: 2, q, donationType, sort, lastSortValue, lastPublicCode}`, con `q` ya normalizado y `lastSortValue` como instante ISO-8601 (`endDate` o `startDate`), o nulo con el orden por defecto.
- Al recibir un cursor v2, **filtros y orden de la petición deben coincidir** con los que lleva dentro (comparando `q` normalizado). Si no coinciden → 400 `InvalidRequestField` (`cursor`).
- **Cursores del formato anterior** (solo el `publicCode`): se aceptan **únicamente** sin `q`, sin `donationType` y sin `sort`. Con cualquiera de ellos → 400 (`cursor`).
- La página siguiente continúa estrictamente después de la última devuelta:
  - orden por defecto: `publicCode > last`;
  - `ending_soon`: `endDate > lastEnd` o (`endDate == lastEnd` y `publicCode > last`);
  - `recent`: `startDate < lastStart` o (`startDate == lastStart` y `publicCode > last`).
- Una convocatoria que se cierra o se crea mientras alguien pagina puede aparecer o faltar en las páginas siguientes; nunca se repite una ya devuelta con el mismo orden. Se acepta (consistencia de lectura, sin instantánea).

## 6. Índices

Declarados con `@CompoundIndex` en `ConvocatoriaDocument`, para que `MongoIndexInitializer` los cree al arrancar y `MongoIndexesAtStartupIntegrationTest` los vigile:
- `idx_discovery_ending_soon`: `{visibility: 1, status: 1, endDate: 1, publicCode: 1}`;
- `idx_discovery_recent`: `{visibility: 1, status: 1, startDate: -1, publicCode: 1}`.

El orden por defecto sigue usando `uq_public_code`. La búsqueda por subcadena no usa índice (recorre las convocatorias públicas abiertas que pasan los demás filtros); es aceptable para el volumen del proyecto y se registra como límite conocido.

> **Nota (2026-10-29): otro límite conocido**, aceptado por Manuel (#159, https://github.com/Toffy22Cj/Donaciones/pull/159#issuecomment-6504628005). Un `q` formado solo por marcas diacríticas combinantes (por ejemplo, dos tildes sueltas) cumple los 2 puntos de código, porque la longitud se mide tras recortar y antes de normalizar (§3). Al normalizar queda vacío y coincide con todas las convocatorias. Solo devuelve convocatorias públicas abiertas, igual que sin `q`.

## 7. Decisiones — Carlos, 2026-10-18T18:45Z

| # | Decisión |
|---|---|
| B1 | (a) Sin filtro de estado; DD-52 intacta |
| B2 | (a) Sin filtro por organización en este corte |
| B3 | (a) `titleSearch` normalizado en persistencia, relleno al arrancar, subcadena con `Pattern.quote` |
| B4 | (a) `ending_soon` y `recent`; sin `sort`, `publicCode` ascendente |
| B5 | (a) Cursor v2 cifrado con filtros y orden; cursores antiguos solo sin filtros y con el orden por defecto |
| B6 | `q` de 2 a 100 puntos de código tras recortar; `sort` desconocido → 400 con el campo nombrado |
| B7 | (b) `donationType=MONETARY\|IN_KIND`; valor inválido → 400 con el campo nombrado |

## 8. Tests previstos (rojos primero)

**Unitarios**
1. Normalización: "Educación Rural", "EDUCACION rural" y "educación rural" producen lo mismo; caracteres de expresión regular en `q` (`.*`, `(`, `[`, `\E`) se buscan literalmente.
2. Cursor v2: ida y vuelta con todos los campos; alterado o de otra clave → vacío; un cursor v1 sigue decodificándose.

**Integración HTTP con Testcontainers**
3. `q` encuentra por subcadena sin distinguir mayúsculas ni tildes, y no devuelve las que no coinciden.
4. `q` de 1 carácter, de 101, vacío o de solo espacios → 400 `q`; `q` con espacios alrededor de un texto válido se recorta y busca.
5. `donationType=IN_KIND` solo devuelve convocatorias que aceptan especie; `donationType=OTRO` → 400 `donationType`.
6. `sort=ending_soon` y `sort=recent` devuelven el orden fijado, con desempate por `publicCode` cuando dos fechas coinciden; `sort=OTRO` → 400 `sort`.
7. Paginación con filtros y orden: recorrer todas las páginas devuelve cada convocatoria que cumple exactamente una vez (sin saltos ni repeticiones), con más de 20 resultados y fechas repetidas.
8. Cursor emitido con unos filtros usado con otros, o con otro orden → 400 `cursor`.
9. Cursor v1 sin filtros → continúa; cursor v1 con `q`, `donationType` o `sort` → 400 `cursor`.
10. **Nunca** aparecen convocatorias `PRIVATE_LINK` ni `CLOSED`, con ningún filtro ni orden (aserción negativa).
11. Búsqueda sin resultados → `200 {items: []}` sin `nextCursor`.
12. Sin parámetros, la respuesta es idéntica a la de antes del cambio (orden por `publicCode`).

**Persistencia**
13. Una convocatoria nueva se guarda con `titleSearch`; el proceso de arranque rellena las que no lo tienen y no toca las que ya lo tienen (ejecutarlo dos veces no cambia nada).
14. Los dos índices nuevos existen tras arrancar (`MongoIndexesAtStartupIntegrationTest`).

**Común**
15. `scripts/ci-local.sh` en verde sobre el último commit del PR, con el resumen literal de Surefire pegado en el PR.

## 9. Documentos que se actualizan con el PR

- `referencia-api-v1.md`: los parámetros nuevos de `GET /public/campaigns`.
- `fichas-p2-endpoints.md` P2.6: nota de que esta ficha la sustituye en filtros, orden y cursor.
- `propuesta-apis-fase6.md` Q-v2-3: nota de que la decisión cambió con esta ficha.
- Aviso a la web y al móvil (M-10): los parámetros nuevos y que la respuesta no cambia de forma.
