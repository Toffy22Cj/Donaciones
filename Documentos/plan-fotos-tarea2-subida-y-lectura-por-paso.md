# Plan — Fotos, Tarea 2: subida y lectura de fotos por paso, y su retirada (2026-11-19)

**Estado: PROPUESTO.** Espera un «plan aprobado» de Manuel que se pueda citar (regla 3.4). **Sin código hasta entonces.**

**Norma:** ADR-051 Enmienda 2 (APROBADA — Manuel, por delegación de Carlos, 2026-11-18): E2-1 (subida), E2-6 (quién ve cada foto) y E2-7 (retirada). Encargo de Manuel del 2026-11-19 («cierre de fotos y documentación», Fase A): la retirada F7 de los tres propósitos nuevos va **aquí** y no en la Tarea 3, porque esta tarea habilita la subida y no puede existir una foto de paso que no se pueda retirar.

**Base verificada:** `develop` `98006d1` (2026-11-19; incluye la Tarea 1, #187). Cada ruta:línea es de esa base.

**Rama de código:** `feat/app-fotos-subida-por-paso`. Toca `app` y `api` (y la semilla en `app`). No toca `core` ni `contracts`: el puerto de vínculo y los propósitos ya están (#187). Si al implementar hiciera falta tocar `core` o `contracts`, es el punto de parada 3 del encargo.

## 0. Alcance

**Dentro:**
1. Subida: `POST /api/v1/organizations/{organizationId}/asset-evidence` con `step`.
2. Lectura por paso: `GET /api/v1/physical-assets/{assetRef}/evidence/{step}`, con `…/evidence/delivered` como alias de la ruta actual, y un listado de los pasos con foto (§2.3, Q-T2-1).
3. Retirada F7 de los tres propósitos nuevos, sustituyendo el 400 provisional de #187.
4. Índice único `(organizationId, purpose, sha256)` para los tres propósitos nuevos y su deduplicación.
5. La semilla `demo-historico` con fotos de paso de ejemplo (para las capturas de la Tarea 5).
6. Propuesta, con su análisis, sobre el hallazgo de `DonationAuditFactsHandler` (§4); **no se cambia hasta que Manuel elija**.

**Fuera:** la copia censurada y su publicación (Tarea 3), la web (Tarea 5), el seguimiento público (Tarea 4, bloqueada), el móvil (Tarea 6).

## 1. Hechos verificados

| # | Hecho | Dónde |
|---|---|---|
| H-1 | La subida de la entrega es `POST /api/v1/organizations/{organizationId}/delivery-evidence`, multipart `file`; autoriza antes de leer bytes con `OrganizationBoundaryPolicy` y `RoleAuthorizationPolicy` (`DELIVER_ASSET`); 413 si pasa de 5 MB | `app/.../web/asset/DeliveryEvidenceController.java:50-62` |
| H-2 | `MediaUploadService.store` deduplica **solo** si `purpose == DELIVERY_EVIDENCE`; con otro propósito no busca el existente | `app/.../application/media/MediaUploadService.java:66` |
| H-3 | La invariante de `store`: `ownerRef` es el `campaignRef` de una portada y `null` en los demás | `MediaUploadService.java:61` |
| H-4 | El índice único `uq_evidence_sha256` es `(organizationId, purpose, sha256)` **parcial** con `{'purpose': 'DELIVERY_EVIDENCE'}`; lo crea `MongoIndexInitializer` resolviendo las anotaciones de cada `@Document` | `app/.../infrastructure/media/MediaObjectDocument.java`; `app/.../config/MongoIndexInitializer.java:46-60` |
| H-5 | `MongoMediaObjectStore.insert` traduce el choque del índice a `DuplicateEvidenceException` **solo si el mensaje contiene el nombre `uq_evidence_sha256`**; con otro índice relanza `DuplicateKeyException` | `app/.../infrastructure/media/MongoMediaObjectStore.java:34-46` |
| H-6 | La lectura de la entrega: `GET /api/v1/physical-assets/{assetRef}/evidence` (miembros de la organización con cualquier rol o administrador de plataforma; 403 uniforme; 404 sin foto; 410 retirada). La huella sale de `PhysicalAsset.getFinalEvidenceRef()` | `app/.../web/asset/DeliveryEvidenceReadController.java:63-97` |
| H-7 | **El agregado no guarda la foto de los otros pasos** (solo `finalEvidenceRef`): para leer por paso hay que leerla del evento, no del estado | `core/.../physicalasset/PhysicalAsset.java` (`getFinalEvidenceRef`, `:658`) |
| H-8 | `PlatformMediaRemovalController` tiene un `switch` exhaustivo sobre `MediaPurpose`; en #187 los tres propósitos nuevos lanzan `InvalidRequestFieldException("purpose")` (400) | `app/.../web/platform/PlatformMediaRemovalController.java:85,96-97` |
| H-9 | La retirada de la entrega: busca `(organizationId, DELIVERY_EVIDENCE, sha256)`, borra bytes y marca el metadato (`markDeleted`); ya retirada → vacío (idempotente); inexistente → `MediaRemovalTargetNotFoundException` | `PlatformMediaRemovalController.java:112-123` |
| H-10 | `MediaPurpose` ya tiene `REGISTRATION_EVIDENCE`, `DISPATCH_EVIDENCE` y `RECEIPT_EVIDENCE` (5 MB) y el vínculo por paso (`DeliveryEvidenceBinding`, `MediaObjectStore.bindEvidence(..., purpose)`) | #187 |
| H-11 | `DonationAuditFactsHandler` calcula su conjunto «ignorado» como *el registro menos lo tratado*; por eso un payload nuevo que nadie trata queda «declarado» y se ignora sin log ni contador | `core/.../projection/DonationAuditFactsHandler.java:46,55,78-83`, `UndeclaredPayloadMonitor.java` |
| H-12 | No hay límite de peticiones en `develop` (ADR-051 riesgo 3, DH-56; ADR-053 sin fusionar). La subida por paso hereda la misma exposición que la de la entrega | `ADR-021-D Enm. 2`, sección «Límite de peticiones» |
| H-13 | La semilla `demo-historico` sube una foto de entrega con `uploads.store(MediaPurpose.DELIVERY_EVIDENCE, …)` | `app/.../bootstrap/historic/DemoHistoricSeeder.java:329` |

## 2. Diseño

### 2.1 Subida

`POST /api/v1/organizations/{organizationId}/asset-evidence`, multipart con `file` y `step` (`REGISTERED` | `DISPATCHED` | `RECEIVED`) → `201 {evidenceRef}` (`sha256:`), sin `Command-Id`, como la de la entrega.

- **Orden:** (1) `step` válido (400 `InvalidRequestFieldException("step")` si falta o no es uno de los tres; `DELIVERED` y cualquier otro → 400: la entrega tiene su ruta); (2) `boundary.assertBelongs` y rol del comando de ese paso con las políticas de `core` (`REGISTER_PHYSICAL_ASSET`, `DISPATCH_PHYSICAL_ASSET`, `RECEIVE_PHYSICAL_ASSET`; los tres exigen `EMPLOYEE`, `RoleAuthorizationPolicy`); el resto, el **mismo 403 uniforme** (DD-01), **antes de leer los bytes**; (3) tamaño (413); (4) `MediaUploadService.store(propósito del paso, …)`.
- `registrar` cubre los dos caminos (compra y donación en especie): las dos exigen `EMPLOYEE`. Se autoriza con `REGISTER_PHYSICAL_ASSET`.
- **Sin cambios de `core`:** el controlador usa las políticas ya existentes, igual que `DeliveryEvidenceController`.
- `MediaUploadService.store`: la rama de deduplicación (H-2) pasa de «solo `DELIVERY_EVIDENCE`» a «los cuatro propósitos de evidencia» (un método `isStepOrDeliveryEvidence(purpose)` en `MediaPurpose`). Si esa foto se retiró por F7, `410 EvidenceRemoved` (deuda 1), como hoy.

### 2.2 Índices y duplicados

- Un segundo índice único parcial en `MediaObjectDocument`, **sin tocar `uq_evidence_sha256`**: `uq_step_evidence_sha256`, `(organizationId, purpose, sha256)`, con el filtro de los tres propósitos nuevos. `MongoMediaObjectStore.insert` reconoce **los dos nombres** al traducir a `DuplicateEvidenceException` (H-5); con cualquier otro índice sigue relanzando.
- Qué se comprueba (test de duplicados por propósito): la misma imagen subida **dos veces al mismo propósito** no crea dos objetos y devuelve la misma `evidenceRef`; en **propósitos distintos** sí crea objetos separados (misma huella, dos filas), cada uno vinculable solo a su paso; una carrera de dos subidas idénticas simultáneas deja una sola fila.
- **Riesgo técnico a verificar con Testcontainers (`mongo:6.0`)** antes de fijar la forma del filtro: si un `partialFilterExpression` con `{purpose: {$in: [...]}}` se acepta; si no, tres índices parciales (uno por propósito). El test lo prueba contra la imagen real.

### 2.3 Lectura por paso

- `GET /api/v1/physical-assets/{assetRef}/evidence/{step}`, con `step` en minúsculas (`registered`, `dispatched`, `received`, `delivered`). `delivered` es un alias exacto de la ruta actual `…/evidence`, que no cambia.
- Misma autorización, mismas respuestas y mismos encabezados que `DeliveryEvidenceReadController` (miembros de la organización con cualquier rol o administrador de plataforma; el mismo 403 para quien no, y para un activo inexistente salvo la plataforma, que recibe 404; `Cache-Control: private, no-store`; sin foto → 404; foto retirada por F7 → 410 `EvidenceRemoved`). **Nunca se expone en una ruta pública** (E2-6).
- **La huella se lee del evento de ese paso** (H-7), no del estado del agregado: el controlador recorre `eventStore.loadStream` y toma el `evidenceRef` del último `ASSET_REGISTERED` 5.0, `ASSET_DISPATCHED` 3.0 o `ASSET_RECEIVED` 3.0 (los eventos de versiones anteriores no tienen foto y se tratan como «sin foto»). Se vuelve a validar el formato `^sha256:[0-9a-f]{64}$` antes de buscar el metadato.
- **Listado de pasos con foto** (Q-T2-1): `GET /api/v1/physical-assets/{assetRef}/evidence/steps` → `[{step, sequence, occurredAt, hasPhoto, removed}]`, con la misma autorización, para que la ficha del lote (Tarea 5) sepa qué pasos mostrar sin pedir una foto por paso a ciegas. **No** devuelve huellas ni claves.

### 2.4 Retirada F7 (sustituye el 400 de #187)

- En `PlatformMediaRemovalController`, los tres propósitos nuevos dejan de rechazarse: `case REGISTRATION_EVIDENCE, DISPATCH_EVIDENCE, RECEIPT_EVIDENCE -> evidenceKey(organizationId, purpose, request.ref())`. `evidenceKey` pasa a recibir el propósito (hoy está fijo en `DELIVERY_EVIDENCE`, H-9).
- Mismo comportamiento que la entrega: primero los bytes (503 si el proveedor falla, metadato sin marcar para reintentar), después `deletedAt`, `deletedBy` y `reason`; ya retirada → `204` sin nada; inexistente → 404 con el mismo cuerpo. **Solo el administrador de plataforma** (`REMOVE_MEDIA`, 403 uniforme al resto).
- La huella sigue en el evento (inmutable). Tras la retirada, la lectura de ese paso da 410 y el listado marca `removed: true`.
- Lo que **no** hace esta tarea: borrar la copia censurada de la entrega ni anular su publicación; eso es la Tarea 3 (la copia no existe todavía).
- **Nota fechada en ADR-051 Enm. 2** (parte de esta fase de planes): la tabla de Implementación pasa a: Tarea 2 = subida, lectura y **retirada F7 de los tres propósitos**; Tarea 3 = **solo** la copia censurada, su publicación y la retirada de la copia de entrega.

### 2.5 Semilla

`DemoHistoricSeeder` registra, despacha y recibe algunos lotes con la foto opcional del paso (una imagen de prueba sintética, como la de la entrega, H-13), con los comandos de #187. Las cifras y capturas de la Tarea 5 citan de ahí: commit, semilla `demo-historico` y endpoint.

## 3. Tests (rojos primero)

**A. Subida** (`StepEvidenceUploadHttpIntegrationTest`, Tomcat y Mongo reales)
1. Cada `step` válido → `201 {evidenceRef}` con la huella de los bytes limpios; el objeto queda con el propósito del paso y `boundAssetId` nulo.
2. `step` ausente, `DELIVERED` o desconocido → 400, sin leer bytes ni crear nada (aserción negativa sobre `media_objects` y el proveedor).
3. **403 uniforme antes de leer bytes**: otra organización, `REPRESENTATIVE`, `ADMINISTRATOR` sin `EMPLOYEE`, sin sesión (401): el proveedor en memoria no recibe ninguna escritura (`never()`).
4. 413 (más de 5 MB), 415 (ni JPEG ni PNG) y 503 (proveedor caído) por paso, sin fila de metadato en 413, 415 y 503.
5. Una foto de otro paso del mismo activo, o de la entrega, **no** es vinculable en el paso (400 `EvidenceNotFound`): se prueba por HTTP junto con la Tarea 1.

**B. Duplicados** (`MediaObjectPersistenceIntegrationTest` y HTTP)
6. La misma imagen dos veces al mismo propósito → una fila y la misma `evidenceRef`.
7. La misma imagen en dos propósitos distintos → dos filas independientes; vincular una no vincula la otra.
8. Carrera: N subidas simultáneas idénticas → una sola fila, todas `201` con la misma huella, sin `500` ni `409`.
9. El índice de la entrega no cambia (`uq_evidence_sha256` sigue siendo parcial solo de `DELIVERY_EVIDENCE`) y el nuevo existe con su nombre (`MongoIndexInitializer`).

**C. Lectura**
10. Cada paso lee su foto (200, bytes y tipo correctos, `private, no-store`); `delivered` y la ruta antigua dan **los mismos bytes**.
11. Sin foto → 404; versiones antiguas del evento → 404; huella inexistente en `media_objects` → 404.
12. 403 uniforme a otra organización y a quien no es miembro; plataforma sí (200) y 404 para activo inexistente.
13. Retirada por F7 → 410.
14. **Nunca pública:** ninguna ruta de `/api/v1/public/**` ni de `/donations/tracking/**` devuelve la foto (aserción negativa sobre el inventario de rutas, `PublicRoutesInventoryIntegrationTest`).
15. Listado de pasos: sin huellas ni claves en el cuerpo (aserción negativa); `removed` correcto tras F7.

**D. Retirada**
16. `purpose` de cada paso nuevo → `204`; bytes borrados (`storage.delete` una vez), metadato con `deletedAt`, `deletedBy`, `reason`; repetir → `204` sin segundo borrado (`never()`); 503 del proveedor → metadato sin marcar y reintentable.
17. Rechazada para quien no es plataforma (403 uniforme) y sin `reason` (400): no borra nada.
18. **Sustituye** el test `theNewStepPurposes_areRejectedByTheRemoval_untilTask3` de #187, que se elimina.

**E. Semilla**
19. `DemoHistoricSeederIntegrationTest`: los lotes con foto de paso tienen su `evidenceRef` en el evento y la foto vinculada; sin `demo-historico` nada de esto existe.

**Mutaciones** (ramas excluyentes): quitar la autorización del `step` antes de leer bytes (test 3 debe fallar); que `store` no deduplique para un propósito nuevo (test 6); que la retirada no marque el metadato tras borrar bytes, o que lo marque antes (test 16); que la lectura de `dispatched` devuelva la foto de `received` (test 10).

## 4. Hallazgo de `DonationAuditFactsHandler` — propuesta (no se cambia hasta que Manuel elija)

**Qué pasa hoy (H-11).** El manejador trata `ASSET_DISPATCHED` y `ASSET_RECEIVED` (y desde #187 las 3.0), y calcula `IGNORED = registro − tratados`. Por eso cualquier payload nuevo que nadie le enseñe queda «declarado como ignorado»: avanza la secuencia sin hechos de auditoría, **sin log y sin contador**. Con #187 esto habría ocultado un despacho 3.0 si yo no hubiera ampliado el manejador; `ProjectionPayloadContractTest` no lo detecta en este manejador (sí en `DonationProjectionHandler`, que tiene su lista explícita de ignorados).

**Opción (a): fallar con una excepción con nombre propio** (`UndeclaredAuditPayloadException`).
- *Cómo:* `IGNORED` pasa a ser una lista explícita; un payload fuera de `HANDLED ∪ IGNORED` lanza la excepción.
- *Qué pasa con la cuarentena (ADR-042, que refina ADR-010):* `ProjectionEventSource.isPermanentError` clasifica toda excepción que no sea hueco, dependencia faltante, acceso a datos o error de conexión como **permanente**: el evento queda `QUARANTINED` en el momento (`ProjectionEventSource.enqueueInitialRetry`). Pero **el manejador no avanza su secuencia por ese activo** (`assetLastProcessedSequences` solo se escribe al terminar), así que el siguiente evento del mismo activo llega con hueco y lanza `SequenceGapException` (reintentable): queda `PENDING` y el planificador lo reintenta hasta la ventana de 4 horas desde el primer intento; pasada, la cuarentena **pausa la proyección de donación del fondo** (`ProjectionRetryScheduler.quarantine`, que pone `status = PAUSED` en el `DonationProjectionDocument`), y desde ese momento todo evento de ese fondo se pone en cuarentena (regla A5). **Un hueco en una proyección secundaria de auditoría acaba pausando la proyección principal del fondo** (la que ven la organización y los donantes) hasta que alguien ejecute `resumeProjection` por JMX. Es exactamente la razón por la que `UndeclaredPayloadMonitor` documenta que el manejador «no lanza».
- *Ventaja:* falla en el primer evento y no se pierde en silencio. *Coste:* arriesga la proyección principal y exige operación manual.

**Opción (b): registrar un aviso estructurado y una métrica, sin fallar.**
- *Cómo:* `IGNORED` pasa a ser una lista explícita (no derivada del registro); el payload fuera de `HANDLED ∪ IGNORED` ya no «está declarado», así que `UndeclaredPayloadMonitor.checkDeclared` (que `DonationAuditFactsHandler` ya llama en las dos rutas, líneas 137 y 168) lo registra con `log.error` y suma al contador por manejador, que ya se expone por JMX en `ProjectionRetryScheduler#getUndeclaredPayloadCount`. Se enriquece el log con campos estructurados (`handler`, `eventType`, `schemaVersion`, `streamId`, `sequence`) sin el payload. **Y** `ProjectionPayloadContractTest` empieza a exigir la declaración también a este manejador, de modo que **un payload nuevo sin declarar rompe el CI** antes de llegar a producción: se convierte en el fallo temprano que (a) buscaba, sin tocar la operación.
- *Qué pasa con la cuarentena:* **nada**: no hay excepción, el evento no se encola, no hay hueco y la proyección principal no se pausa. El contador JMX sube y el log de error queda para la alerta.
- *Coste:* no «falla» en producción si alguien despliega un payload sin declarar saltándose el CI; queda el log y el contador, que ya son el mecanismo del proyecto (ADR-042).
- *«Métrica»:* el proyecto no tiene Micrometer ni Actuator (`grep` sobre los `pom.xml`: 0 coincidencias); la métrica es el contador JMX existente. Sin dependencias nuevas.

**Recomendación: (b).** Cumple el objetivo (que no se pierda en silencio: CI y log/contador) sin convertir un hueco de auditoría en una pausa de la proyección principal. **Test (b):** (1) `ProjectionPayloadContractTest` falla si se registra un payload de prueba que `DonationAuditFactsHandler` no declara; (2) un payload fuera de las dos listas incrementa el contador `byHandler()` y emite el log de error con los campos estructurados, y **no** lanza (aserción negativa), **no** crea documento de reintento (`never()` en el repositorio de retry) y **no** cambia el estado de la proyección. **Test (a), si Manuel la elige:** la excepción tiene nombre propio, queda `QUARANTINED`, y se documenta con un test que reproduce el hueco del siguiente evento del activo y la pausa tras la ventana (con el reloj controlado).

## 5. Preguntas para Manuel (con mi recomendación)

- **Q-T2-1 — Varias fotos del mismo paso.** Un lote puede despacharse y recibirse más de una vez (hay re-despacho), así que puede haber varias fotos de «despachar». (a) **Recomendada:** `…/evidence/{step}` devuelve la del **último** evento de ese paso, el listado `…/evidence/steps` da el `sequence` de cada uno, y un `?sequence=N` opcional devuelve la de un evento concreto. (b) Solo la última, sin `sequence` ni listado. (c) Una ruta por evento (`…/evidence/events/{sequence}`). **Recomiendo (a):** la ficha del lote necesita saber qué pasos tienen foto.
- **Q-T2-2 — Forma del `step`.** (a) **Recomendada:** campo multipart `step` con `REGISTERED|DISPATCHED|RECEIVED` en mayúsculas (como en E2-1) y `delivered|registered|…` en minúsculas en la ruta de lectura (como E2-6). (b) Unificar a minúsculas también en la subida. **Recomiendo (a)**: es lo que dicen E2-1 y E2-6; la diferencia mayúsculas/minúsculas ya está escrita.
- **Q-T2-3 — Forma del índice.** (a) **Recomendada:** un índice con filtro `{purpose: {$in: [3 propósitos]}}`, si `mongo:6.0` lo admite (se prueba con Testcontainers). (b) Tres índices parciales, uno por propósito. Si (a) no funciona, (b) sin volver a preguntar.
- **Q-T2-4 — Hallazgo del manejador de auditoría.** (a) fallar con excepción, (b) aviso y métrica (§4). **Recomiendo (b).** No lo implemento hasta que elijas.
- **Q-T2-5 — Límite de peticiones.** La subida por paso no tiene límite (como la de la entrega; DH-56). ¿Se acepta que la Tarea 2 no lo añada y siga como riesgo residual hasta ADR-053? **Recomiendo sí**: la ruta exige JWT y rol, y el coste por petición es el mismo que la de la entrega ya fusionada.

## 6. Riesgos

1. **Lectores y proyecciones:** ya cubiertos por #187; esta tarea no añade eventos.
2. **Fotos huérfanas:** una foto subida y nunca vinculada se acumula (Q-E2-6 (a), deuda de ADR-051 Q3); aumenta con tres propósitos nuevos. Sin limpieza programada, como decidiste.
3. **Filtro `$in` del índice parcial** (Q-T2-3): se verifica antes de fijar la forma.
4. **Exposición sin límite de peticiones** (Q-T2-5).
5. **Cambio de contrato del seguimiento:** ninguno; la foto de paso no sale por rutas públicas (test 14).

## 7. Definición de hecho

Tests 1 a 19 en verde, con el rojo previo visible y las mutaciones muertas; `scripts/ci-local.sh` completo con la salida de Surefire por módulo; nota fechada en ADR-051 Enm. 2 (tabla de Implementación y rutas); `referencia-api-v1.md` con las rutas nuevas; el test provisional de #187 sustituido por el de la retirada real; la semilla con una foto de paso de ejemplo y su procedencia. PR hacia `develop`; no se fusiona sin aprobación; **revisa Carlos solo si el PR toca `core` o `contracts`** (el plan no lo prevé).
