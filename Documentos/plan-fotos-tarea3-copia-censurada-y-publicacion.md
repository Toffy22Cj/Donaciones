# Plan — Fotos, Tarea 3: copia censurada, publicación y su retirada (2026-11-19)

**Estado: PROPUESTO.** Espera un «plan aprobado» de Manuel que se pueda citar (regla 3.4). **Sin código hasta entonces.**

**Norma:** ADR-051 Enmienda 2 (APROBADA — Manuel, por delegación de Carlos, 2026-11-18): E2-4 (copia censurada), E2-4b (generación bajo demanda y concurrencia), E2-5 (publicación) y E2-7 (retirada de la entrega y de su copia), con las correcciones de #184 (concurrencia, generación determinista, historial que solo crece y el test 8 bien redactado). Encargo de Manuel del 2026-11-19: la retirada F7 de los tres propósitos nuevos pasa a la Tarea 2; **esta tarea es solo la copia, su publicación y la retirada de la copia de la entrega**.

**Base verificada:** `develop` `98006d1` (2026-11-19), con la Tarea 1 (#187). La Tarea 3 **empieza cuando la Tarea 2 esté fusionada** (rama desde `develop` actualizado, sin apilar ramas).

**Rama de código:** `feat/app-fotos-copia-censurada`. Toca `app` (rutas, servicio, almacén y documento de `media_objects`). No toca `core`, `contracts` ni `api` en este plan; si hiciera falta (por ejemplo, Q-T3-7 (b)), es el punto de parada 3 del encargo y revisa Carlos.

## 0. Alcance

**Dentro:** el propósito `DELIVERY_EVIDENCE_REDACTED`; la copia pixelada determinista; su generación bajo demanda con concurrencia; las tres rutas (`GET …/evidence/redacted`, `PUT` y `DELETE …/publication`); el historial de publicación que solo crece; la retirada F7 de una foto de entrega que borra también la copia y anula la publicación en la misma operación.

**Fuera:** que el donante la vea (ruta pública y `evidencePreviewUrl`: Tarea 4, **bloqueada** hasta ADR-053 u otro límite de peticiones aprobado), la web (Tarea 5), las copias de las fotos de registrar, despachar y recibir (Q-E2-4 (a): no en este corte).

## 1. Hechos verificados

| # | Hecho | Dónde |
|---|---|---|
| H-1 | `MediaObjectDocument` es un `record` con `objectKey`, `sha256`, `purpose`, `organizationId`, `ownerRef`, `boundAssetId`, `deletedAt`, `deletedBy`, `reason`…; **no tiene** lista de decisiones | `app/.../infrastructure/media/MediaObjectDocument.java` |
| H-2 | El único índice único de contenido es `uq_evidence_sha256` `(organizationId, purpose, sha256)`, **parcial solo** de `DELIVERY_EVIDENCE`. **No cubre** `DELIVERY_EVIDENCE_REDACTED` | `MediaObjectDocument.java` (anotación `@CompoundIndex`) |
| H-3 | `MongoMediaObjectStore.insert` traduce el choque a `DuplicateEvidenceException` solo si el mensaje contiene `uq_evidence_sha256` | `MongoMediaObjectStore.java:34-46` |
| H-4 | `MediaUploadService.store` pasa **todo** por `ImageSanitizer.sanitize` (decodifica, limpia y vuelve a codificar), y exige `ownerRef` solo para la portada | `MediaUploadService.java:61-68`; `ImageSanitizer.java:64-` |
| H-5 | `MediaObjectStore` no tiene operaciones por `ownerRef` ni para añadir decisiones; `markDeleted` marca un `objectKey` | `app/.../application/media/MediaObjectStore.java` |
| H-6 | La retirada de la entrega busca `(organizationId, DELIVERY_EVIDENCE, sha256)`, borra bytes (`storage.delete`), y marca `markDeleted`; ya retirada → `204` sin nada | `PlatformMediaRemovalController.java:112-123` |
| H-7 | El rol del comando se comprueba con `RoleAuthorizationPolicy` de `core`, cuyo `switch` solo conoce comandos de fondos y de activos; **no hay** un comando de «publicar» | `core/.../authorization/RoleAuthorizationPolicy.java:20-22` |
| H-8 | `AuthorizationRole` tiene `ADMINISTRATOR`, `REPRESENTATIVE` y `EMPLOYEE`; `AuthorizationPrincipal.roles()` es un conjunto | `contracts/.../authorization/AuthorizationRole.java` |
| H-9 | La foto de entrega se lee con `private, no-store` y 410 si F7; la copia seguirá las mismas reglas | `DeliveryEvidenceReadController.java:63-97` |
| H-10 | El almacén de bytes: `storage.put(key, bytes, contentType)`, `get(key)`, `delete(key)`; `put` reintenta una vez y lanza `MediaStorageUnavailableException` (503) | `MediaUploadService.java:70`; `MediaStoragePort` |

## 2. Diseño

### 2.1 La copia censurada (E2-4), determinista

- Propósito nuevo `DELIVERY_EVIDENCE_REDACTED` (5 MB de tope solo por coherencia; la copia mide unos KB). Es un objeto aparte: su `objectKey`, su `sha256`, y `ownerRef` = la `objectKey` de la **original**. No se ancla (el `evidenceRef` del evento sigue siendo la huella de la original).
- Un componente puro, `EvidenceRedactor` (JDK, sin dependencias), recibe los bytes limpios de la original y devuelve el PNG:
  1. decodifica con `ImageIO`; una imagen con transparencia se compone **sobre blanco** (regla fija, para que no dependa del contenido);
  2. cuadrícula de **24 columnas** y `N = max(1, round(24 × corto / largo))` filas (o al revés si es vertical), cada bloque promedia el color de su rectángulo de la imagen original con **aritmética entera** (`suma / área`, redondeo fijo; el rectángulo del bloque `i` va de `floor(i·w/24)` a `floor((i+1)·w/24)`);
  3. cada bloque se amplía a **20 × 20 px** por vecino más próximo: la copia mide **480 px en el lado largo** y `20·N` en el corto;
  4. se codifica en **PNG con parámetros fijos** (un `ImageWriter` con nivel de compresión fijo, sin metadatos).
- **No pasa por `ImageSanitizer`** (H-4): volver a codificar con el limpiador cambiaría los bytes de la copia. Un servicio propio guarda la copia directamente (bytes y metadato), con el orden de F2 (bytes primero, metadato después).
- **Determinista** (DoD 7b): los mismos bytes de entrada dan los mismos bytes de salida y la misma huella, con la misma JDK. La unicidad de la copia **no depende de eso** (§2.2).

### 2.2 Generación bajo demanda y concurrencia (E2-4b)

`GET /api/v1/physical-assets/{assetRef}/evidence/redacted`: busca la copia por `(organizationId, DELIVERY_EVIDENCE_REDACTED, ownerRef)`; si no existe, la genera y la guarda. Dos peticiones simultáneas son un caso normal:

- Un **índice único parcial nuevo** `uq_redacted_owner` `(organizationId, purpose, ownerRef)` con filtro `{purpose: 'DELIVERY_EVIDENCE_REDACTED'}` (lo crea `MongoIndexInitializer` por la anotación, como los demás). **No se añade un índice por `sha256` para la copia:** ver Q-T3-1 (el texto de E2-4b lo da por existente y no lo está, H-2).
- `MongoMediaObjectStore.insert` reconoce `uq_redacted_owner` y lo traduce a una excepción **con nombre propio** (`DuplicateRedactedCopyException`), distinta de `DuplicateEvidenceException`.
- **El que pierde la carrera relee la copia por `ownerRef` y la devuelve** (`200`, los mismos bytes): nunca `500` ni `409`. Su archivo queda huérfano en el proveedor: se **intenta borrar** y, si no se puede, se registra con su clave (`media.orphan`, F2 y F10).
- Si al releer la copia está retirada por F7 entre medias → `410 EvidenceRemoved`.
- La original es la de la entrega del activo (`getFinalEvidenceRef`, como la lectura de la entrega); sin entrega con foto, o con un `evidenceRef` heredado de texto libre → el mismo `404` que la lectura de la entrega.

### 2.3 Rutas y permisos (E2-5, E2-6)

| Ruta | Quién | Respuesta |
|---|---|---|
| `GET /api/v1/physical-assets/{assetRef}/evidence/redacted` | miembros de la organización del activo con cualquier rol y administrador de plataforma | la copia (PNG), `Cache-Control: private, no-store`; la genera si no existe; 404 sin foto de entrega; 410 si F7 |
| `PUT …/evidence/redacted/publication` | **solo** `ADMINISTRATOR` de la organización del activo | `{confirmNoIdentifiablePerson: true}` → `204`; sin la confirmación → `400` |
| `DELETE …/evidence/redacted/publication` | solo `ADMINISTRATOR` de la organización | `204`, también si no estaba publicada |
| `GET …/evidence/redacted/publication` (Q-T3-8) | solo `ADMINISTRATOR` de la organización | `{published, decisions: [{action, at, by, confirmNoIdentifiablePerson}]}` en orden, **sin bytes**; `Cache-Control: private, no-store` |

- `EMPLOYEE`, `REPRESENTATIVE`, otra organización y el **administrador de plataforma** no publican ni despublican: el mismo `403` uniforme (DD-01). El de plataforma sí puede leer y retirar por F7.
- **Cómo se comprueba «ADMINISTRATOR de la organización»** (H-7, Q-T3-7): `OrganizationBoundaryPolicy.assertBelongs` + `principal.roles().contains(AuthorizationRole.ADMINISTRATOR)`, en `app`, **sin tocar `core`** (el `switch` de `RoleAuthorizationPolicy` no tiene un comando de «publicar»).
- Una foto retirada por F7 no se publica: `410 EvidenceRemoved`. Una copia que aún no se ha generado no se publica: `409 RedactedCopyNotGenerated` (Q-T3-3).

### 2.4 Historial de decisiones que solo crece (E2-5)

- `MediaObjectDocument` gana `publicationDecisions` (lista de `PublicationDecision{action, at, by, confirmNoIdentifiablePerson}`) y un campo derivado `published` (Q-T3-4). El constructor existente del `record` se conserva como sobrecarga con lista vacía: los 6 sitios que hoy lo llaman (`MongoMediaObjectStore` y dos clases de test, verificado con `grep -rn "new MediaObjectDocument("`) no cambian.
- **Una sola operación de escritura por decisión**, atómica por documento: `updateFirst({objectKey, published != true}, {$push: {publicationDecisions: …}, $set: {published: true}})` para `PUBLISH`, y el análogo con `published: true` → `false` para `UNPUBLISH`. Si no coincide ningún documento (ya estaba publicada, o ya despublicada), **no se añade nada** (`PUT` y `DELETE` idempotentes, `204`). El almacén **no ofrece** ninguna operación que reemplace o borre entradas (se comprueba en la interfaz con un test de reflexión sobre sus métodos).
- La publicación **vigente se deriva de la última entrada** (`PUBLISH` y ni la copia ni la original borradas por F7); `published` solo es su copia desnormalizada, escrita en la misma operación, y un test comprueba que coinciden siempre.

### 2.5 Retirada F7 de la entrega: la copia se borra en la misma operación (E2-7)

En `PlatformMediaRemovalController`, para `purpose = DELIVERY_EVIDENCE`, **tras** localizar la original y **antes** de tocar bytes:
1. si la copia existe y la publicación estaba vigente → `UNPUBLISH` con `by` = el administrador de plataforma (una escritura atómica: **primero anular**, así no hay instante en que la copia siga publicada sin original);
2. borrar los bytes de la copia (si existe) y los de la original (503 si el proveedor falla: metadatos sin marcar, se puede reintentar; la anulación del paso 1 ya quedó);
3. marcar `deletedAt`, `deletedBy`, `reason` en la original y en la copia.
- Si no existe copia, todo es como hoy. Repetir la retirada → `204` sin nada (idempotente, sin segunda entrada `UNPUBLISH`).
- Cuando la Tarea 4 exista, el seguimiento público dejará de ofrecer la copia **sin decir por qué** (ADR-021-D Enm. 1, E3): lo garantiza que «vigente» exige copia y original vivas, no esta tarea.

## 3. Tests (rojos primero)

**A. Redacción** (`EvidenceRedactorTest`, sin Spring)
1. **Test 8 (E2-4):** con `L` = lado largo de la copia (480), cada bloque de `L/24 = 20 × 20 px` **es de un solo color**; con una entrada de **rayas de 1 px** alternando blanco y negro, en la copia ningún bloque contiene más de un color y no queda **ninguna transición dentro de un bloque** (se recorre cada bloque, píxel a píxel).
2. Geometría: `N = round(24 × corto / largo)` (mínimo 1) y medidas `480 × 20N`, para apaisada, vertical, cuadrada y panorámica extrema.
3. **Test 7b (determinismo):** dos ejecuciones sobre los mismos bytes dan los mismos bytes y la misma huella (y los de un PNG con transparencia, compuesto sobre blanco).
4. Sin metadatos en el PNG (sin EXIF, ni texto, ni perfil) y formato PNG.
5. Mutación: usar `floor` en lugar de promedio, o 23 columnas, o un bloque de 19 px → el test 8 o el 2 fallan.

**B. Generación y concurrencia** (`RedactedEvidenceHttpIntegrationTest`, Mongo real)
6. La primera lectura genera y guarda; la segunda devuelve los mismos bytes **sin generar de nuevo** (`storage.put` una sola vez).
7. **Test 7c:** N lecturas simultáneas (barrera) sin copia previa → todas `200` con los mismos bytes; **una sola fila** de la copia en `media_objects` (aserciones negativas: ningún `500` ni `409`, ninguna segunda fila). Los archivos de las que perdieron se intentan borrar (`storage.delete`) y, si el borrado falla, se registra (`media.orphan`).
8. El mismo caso con **bytes distintos forzados** en cada generación (un redactor de prueba que añade ruido) sigue dejando **una sola** copia, por el índice `(organizationId, purpose, ownerRef)`.
9. Sin entrega, o con evidencia de texto libre → `404` (el mismo que la lectura de la entrega); retirada por F7 → `410`.
10. `403` uniforme: otra organización y no miembro; plataforma y miembros de cualquier rol → `200`.

**C. Publicación** (`RedactedPublicationHttpIntegrationTest`)
11. **Test 10:** `ADMINISTRATOR` + confirmación → `204`; sin la confirmación o con `false` → `400`; `EMPLOYEE`, `REPRESENTATIVE`, otra organización y **administrador de plataforma** → `403` uniforme (aserciones negativas: la lista `publicationDecisions` no cambia).
12. **Test 10b (historial):** publicar, despublicar y volver a publicar deja **tres entradas en orden** y la primera conserva su `at`, su `by` y su confirmación (se compara con la copia leída antes); `PUT` sobre una ya publicada y `DELETE` sobre una no publicada → `204` **sin añadir** entrada.
13. Carrera: dos `PUT` simultáneos → una sola entrada `PUBLISH` (la escritura condicional), ambas `204`.
14. La interfaz del almacén no ofrece operaciones que reemplacen o borren entradas (reflexión sobre sus métodos; cualquier método nuevo con esos verbos falla el test).
12b. (Q-T3-8) `GET …/publication` devuelve las decisiones en orden y sin bytes al `ADMINISTRATOR`; `EMPLOYEE`, `REPRESENTATIVE`, otra organización y plataforma → `403` uniforme (aserción negativa: el cuerpo no contiene ningún `accountId`).
15. `published` coincide siempre con la última entrada (tras cada transición y tras F7).
16. Copia no generada → `409 RedactedCopyNotGenerated`; foto retirada → `410`.

**D. Retirada** (`DeliveryEvidenceReadAndRemovalHttpIntegrationTest` ampliado)
17. **Test 11:** foto de entrega con copia **publicada** y retirada por F7: original y copia sin bytes (`storage.delete` dos veces), metadatos con `deletedAt`, `deletedBy` y `reason`, **una** entrada `UNPUBLISH` con el administrador de plataforma, publicación no vigente.
18. Sin copia: como hoy (una sola eliminación de bytes); copia **no publicada**: se borra sin entrada `UNPUBLISH`.
19. Idempotente: repetir → `204`, sin segundo borrado ni segunda entrada (`never()`).
20. Si el proveedor falla al borrar la copia (503), la publicación **ya quedó anulada** y el metadato sin marcar; reintentar completa el borrado.
21. **Ningún instante con copia publicada y original borrada:** el estado «vigente» es falso en cuanto la original o la copia tiene `deletedAt` (test de la derivación).

**Mutaciones** (ramas excluyentes): invertir el orden de F7 (borrar bytes antes de anular la publicación) → test 20/21; permitir que `REPRESENTATIVE` publique → test 11; reemplazar `$push` por `$set` de la lista → test 12; quitar el índice por `ownerRef` → test 8; devolver `409` cuando pierde la carrera → test 7.

## 4. Preguntas para Manuel (con mi recomendación)

- **Q-T3-1 — Índice de la copia (hallazgo).** E2-4b dice que `media_objects` «tiene el índice único `(organizationId, purpose, sha256)` (F9)» y que un choque en cualquiera de los dos índices es «ya existe». En el código ese índice es **parcial solo de `DELIVERY_EVIDENCE`** (H-2): no cubre la copia. (a) **Recomendada:** solo el índice por `ownerRef` para la copia; **no** añadir uno por `sha256`, porque dos originales distintas pueden dar copias idénticas (por ejemplo, dos fotos casi uniformes) y un índice por huella trataría la segunda como «ya existe» y la releería por un `ownerRef` que no es el suyo. Nota fechada en ADR-051 Enm. 2 que corrige E2-4b. (b) Añadir los dos índices como dice el texto. **Recomiendo (a).**
- **Q-T3-2 — Quién dispara la generación.** (a) **Recomendada:** cualquier lector autorizado (idempotente y determinista; la primera lectura genera). (b) Solo el `ADMINISTRATOR`, los demás reciben `404` hasta que exista. **Recomiendo (a):** menos estados y la copia es la misma.
- **Q-T3-3 — Publicar sin haber abierto la vista previa.** (a) **Recomendada:** `409 RedactedCopyNotGenerated`: la copia debe existir (es decir, alguien la generó al abrirla). (b) El `PUT` la genera y publica en un paso. **Recomiendo (a):** (b) permitiría publicar sin que nadie la haya visto. No se puede probar que el administrador la «vio»; esto es lo más cercano sin añadir estado.
- **Q-T3-4 — `published` desnormalizado.** (a) **Recomendada:** un booleano escrito en la **misma** operación atómica que la entrada, para que `PUT`/`DELETE` sean condicionales y la carrera no duplique entradas; la derivación sigue siendo la de la última entrada (test 15). (b) Sin campo: leer, decidir y escribir (carrera posible entre dos `PUT`). **Recomiendo (a).**
- **Q-T3-5 — Alfa.** Una imagen con transparencia se compone sobre blanco (§2.1). (a) **Recomendada.** (b) Sobre negro. Solo afecta a PNG con alfa.
- **Q-T3-6 — Tope de tamaño de la copia.** Sin tope propio: la copia mide ~20 KB. (a) **Recomendada:** sin tope especial. (b) 100 KB como invariante de test. **Recomiendo (a)**, con un test que lo mida y lo imprima.
- **Q-T3-8 — Cómo lee la web el historial de decisiones (viene del plan de la Tarea 5, Q-T5-1).** E2-5 dice que «queda registrado quién y cuándo» pero ninguna ruta lo devuelve. (a) **Recomendada:** una ruta de lectura más, `GET …/evidence/redacted/publication` → `{published, decisions[]}`, **solo para el `ADMINISTRATOR`** (el `by` es un `accountId`; a los demás roles no se les da). Cambia este plan, no el contrato aprobado de E2-5: añade una lectura. (b) No exponerlo: el historial queda solo en la base de datos. **Recomiendo (a).** Tests: 12b (lectura del historial en orden, `403` uniforme al resto, sin bytes).
- **Q-T3-7 — Dónde vive el permiso de publicar.** (a) **Recomendada:** en `app` (§2.3), sin tocar `core`. (b) Un `CommandType` nuevo en `core` (`PUBLISH_DELIVERY_EVIDENCE_COPY`) en `RoleAuthorizationPolicy`: unifica las políticas pero cambia `core`, revisa Carlos y el `switch` exhaustivo obliga a tocar todos los llamadores. **Recomiendo (a).**

## 5. Riesgos

1. **Reconocer personas en 24 bloques** (riesgo residual 2 de ADR-051 Enm. 2): sigue dependiendo de la aprobación humana y su registro; esta tarea no lo reduce.
2. **Dependencia de la JDK para la huella de la copia:** la huella solo se estabiliza con la misma JDK; la unicidad de la copia no depende de ella (índice por `ownerRef`).
3. **Archivos huérfanos del perdedor de la carrera** (E2-4b): se intentan borrar y, si no, se registran.
4. **El `record` de `media_objects` cambia** (un campo nuevo): se conserva el constructor anterior; los datos ya guardados no tienen la lista (`null` → vacía al leer).
5. **Sin consumidor público todavía** (Tarea 4 bloqueada): la publicación se guarda y se ve en la ficha del lote, pero el donante no la recibe.

## 6. Definición de hecho

Tests 1 a 21 en verde con el rojo previo visible y las mutaciones muertas; `scripts/ci-local.sh` completo con la salida de Surefire por módulo; nota fechada en ADR-051 Enm. 2 (Q-T3-1 y la tabla de Implementación); `referencia-api-v1.md` con las tres rutas; ninguna ruta pública nueva (aserción negativa sobre el inventario de rutas). PR hacia `develop`; no se fusiona sin aprobación.
