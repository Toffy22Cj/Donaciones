# Plan — ADR-051, Tarea 4: evidencia de entrega (subida, vínculo y validación del formato)

**Estado: BORRADOR. Espera el "plan aprobado" de Manuel**, por la delegación de Carlos del 2026-10-24 (`decisiones-delegadas-2026-10.md`, nota del 2026-10-24). No se escribe código antes (regla 3.4). Carlos aprueba el PR de implementación con Approve en GitHub.

> **Nota (2026-10-25): APROBADO por Carlos** (`decisiones-delegadas-2026-10.md`, nota del 2026-10-25: "Carlos aprobó todo lo pendiente …", comunicado por Manuel; texto literal pendiente de registrar). El estado de arriba se conserva; esta nota lo supera. Los puntos P-T4-1 a P-T4-7 se aplican como los recomienda el plan. **B-2 sigue en pie:** el PR de código no se fusiona sin avisar antes a Adonis y a la web.
**Fecha:** 2026-10-25 (UTC). **Autor:** Manuel, con asistencia de Claude.
**Norma:** `Documentos/ADR-051-fotos-y-almacenamiento.md` (APROBADO — Carlos, 2026-10-18, con la Enmienda 1), tarea 4 de §"Implementación" (`:300`).
- Aplican F2, F3, F4, F5 (en la parte del backend) y F8.
- Criterios de la definición de hecho: 2, 3, 4 y 5 (para la evidencia), 6 a 11 y 20.

Este plan no decide nada que el ADR no fije, salvo los puntos marcados **[Plan]**, que se aprueban con el plan.
**Base verificada:** `develop` `8e37667` (2026-10-25). Rama de implementación: `feat/adr051-t4-evidencia` **[Plan]**, desde el `develop` del momento y con su hash.
**Dueño:** Manuel, con Adonis para el móvil (ADR-051, `:300`).
**Orden de Carlos** (traspaso de la sesión de Manuel del 2026-10-24): primero el Bloque D, después el PR de CORS en borrador, después **ADR-051 T4** y por último Google. Este plan se redacta ahora. Su código va en ese orden.

## 0. Dependencias y bloqueos

| # | Dependencia | Estado | Efecto |
|---|---|---|---|
| **B-1** | Tareas 1 y 2 de ADR-051 (`MediaStoragePort`, `ImageSanitizer`, `MediaUploadService`, `media_objects`, el logo) | En `develop` (#107 y #111, fusionados sin review; H-1) | T4 las reutiliza; no las cambia salvo lo indicado en §3 |
| **B-2** | **El móvil y la web envían hoy `evidenceRef` como texto libre**: el semillero usa `"acta-1"` (`DemoScenarioSeeder.java:220`) y el golden path, `"ev-1"` (`GoldenPathHttpIntegrationTest.java:281`). La enmienda de ADR-043 (F5: `DELIVER` con conexión, subir la foto antes) es de Adonis y no está redactada | Sin enmienda de ADR-043 | **En cuanto se fusione T4, cualquier entrega con texto libre recibirá 400.** El PR no se fusiona sin avisar antes a Adonis y a la web. Este plan no redacta la enmienda de ADR-043 |
| **B-3** | El orden de Carlos (Bloque D y CORS antes) | — | El código espera a su turno |
| **B-4** | T5 (lectura de la evidencia, retirada y huella en el seguimiento) necesita antes la enmienda de ADR-021-D (`ADR-051:255`) | Fuera de este plan | T4 no expone la evidencia en ninguna lectura (§1) |

## 1. Alcance

Dentro:
- **Subida** (F8): `POST /api/v1/organizations/{organizationId}/delivery-evidence`, multipart con el campo `file`, responde `201 {evidenceRef}` y `evidenceRef = "sha256:" + <64 hex>` de los bytes limpios (F3, F4). Los mismos bytes de la misma organización devuelven el mismo `evidenceRef` sin duplicar (F9; ya lo hace `MediaUploadService`).
- **Validación del formato** (F3): en `PhysicalAssetCommandService.deliverAsset` (`core`), `^sha256:[0-9a-f]{64}$`; si no cumple, 400. El agregado no cambia, así que los eventos antiguos se reconstruyen igual (criterio 11).
- **Vínculo** (F3): puerto `DeliveryEvidencePort.bindToAsset(organizationId, evidenceRef, assetId)` en `contracts`, implementado en `app`. Es una escritura condicional en `media_objects`:
  - 409 `EvidenceAlreadyBound` si la foto está vinculada a otro activo;
  - 400 `EvidenceNotFound`, el mismo en todos los casos, si no existe, no es evidencia, es de otra organización o está borrada.
- **El orden de F3** dentro de cada intento del reintento de `transition()`: cargar, `authorize`, `deliver` en memoria, `bindToAsset` (solo si se generó un evento) y `appendAndOutbox`.
- `DemoScenarioSeeder` y `GoldenPathHttpIntegrationTest` suben una foto real y entregan con su `evidenceRef` (`ADR-051:258`).
- La ruta nueva en el inventario de rutas, `referencia-api-v1.md` y `estado-fase6.md`.

Fuera:
- **La lectura de la evidencia**, la retirada por la plataforma (F7) y la huella en el seguimiento (F6, criterio 13): son T5.
- La portada (T3).
- La enmienda de ADR-043 (Adonis).
- La limpieza de evidencias nunca vinculadas (Q3 (a), deuda registrada).

## 2. Hechos verificados en `develop` `8e37667` (2026-10-25)

- **`core`, `PhysicalAssetCommandService`:**
  - `deliverAsset` (`:99-104`) delega en `transition(...)` (`:124-142`);
  - `transition` comprueba el reclamo del `commandId` (`:127-130`, un reenvío es un no-op) y, dentro de `retryTemplate.execute`, carga, autoriza (`:134`), aplica el cambio (`:135`) y llama a `appendAndOutbox` (`:136-137`);
  - despachar (`:107-110`) y recibir (`:113-117`) usan el mismo `transition`.
- **`core`, `PhysicalAsset.deliver`** (`:289-308`): una redundancia exacta, con el mismo `evidenceRef` y los mismos datos, no genera evento (`:296`).
- **`core`, `RoleAuthorizationPolicy`** (`:21-22`): `DELIVER_ASSET` exige `EMPLOYEE`.
- **`api`, `PhysicalAssetController.deliver`** (`:142-154`): exige `evidenceRef` no vacío (`RequestFields.required`) y no comprueba su formato.
- **`app`, media:**
  - `MediaUploadService.store(DELIVERY_EVIDENCE, …)` deduplica por `(organizationId, purpose, sha256)` (`:65-71`) y resuelve la carrera de dos subidas iguales (`:80-85`);
  - `ImageSanitizer` devuelve el SHA-256 en hexadecimal, **sin el prefijo `sha256:`** (`:164-166`);
  - `MediaPurpose.DELIVERY_EVIDENCE` permite 5 MB (`:12`), y `application.yml:9-14` ya fija `max-file-size: 5MB` y `max-request-size: 6MB` pensando en la Tarea 4.
- **`app`, `MediaObjectDocument`:**
  - ya tiene `boundAssetId` (`:39`) y el índice único **parcial** `uq_evidence_sha256` sobre `(organizationId, purpose, sha256)`, con `purpose = DELIVERY_EVIDENCE` (`:24-25`);
  - `MediaObjectStore` no tiene ninguna escritura condicional.
- **`app`, el patrón de la subida del logo (T2):** `OrganizationLogoController` (multipart `file`; `requireLogoManager` antes de leer los bytes) y `OrganizationLogoService`. Los errores de media se mapean en `MediaApiErrorMappings`.
- **Llamadores de la entrega con `evidenceRef` libre en los tests:**
  - `core`: `PhysicalAssetCommandServiceAuthorizationTest:96,116,134` (`"evid"`), `ProjectionChangeStreamE2ETest:189,223` (`"EVIDENCE-1"`), `ProcessedCommandIdempotencyIntegrationTest:349`, `CampaignDeliveryFactsQueryIntegrationTest:166`, `SplitSagaIntegrationTest:535` y `AssetHttpCommandsIntegrationTest:232`;
  - `app`: `PhysicalAssetHttpIntegrationTest:136,257`, `GoldenPathHttpIntegrationTest:281` y `DemoScenarioSeederIntegrationTest` (a través del semillero).

## 3. Cambios por módulo

### `contracts`

| Archivo | Cambio | Fuente |
|---|---|---|
| `media/DeliveryEvidencePort.java` (nuevo) | `void bindToAsset(String organizationId, String evidenceRef, String assetId)` | F3 (nombre del ADR) |
| `media/EvidenceAlreadyBoundException.java` y `media/EvidenceNotFoundException.java` (nuevos) | Las lanza el puerto; `core` las deja pasar y `app` las mapea a 409 y 400 | F3 (nombres del ADR); el paquete **[Plan]** |

El ADR ya cubre el puerto nuevo en `contracts` por la regla 3.5 (`ADR-051:13-17`, en concreto `:16`).

### `core`

| Archivo | Cambio | Fuente |
|---|---|---|
| `application/command/PhysicalAssetCommandService.java` | En `deliverAsset`: validar el formato `^sha256:[0-9a-f]{64}$` **antes** del reclamo y del reintento, lanzando `InvalidEvidenceRefException` **[Plan]** (400). Pasar a `transition` un gancho **[Plan]** que se llama tras `change.accept(asset)` y antes de `appendAndOutbox`, solo si `asset.getUncommittedEvents()` no está vacío. Para la entrega, el gancho es `deliveryEvidence.bindToAsset(asset.getOrganizationRef(), evidenceRef, assetId)`. Despachar y recibir pasan un gancho vacío | F3 (orden de los pasos 1 a 5) |
| `application/exception/InvalidEvidenceRefException.java` (nuevo) **[Plan]** | Formato inválido | F3; criterio 6 |
| Tests de `core` que entregan (§2) | `evidenceRef` con el formato `sha256:` y un doble de `DeliveryEvidencePort` en el contexto de test, como `TestIdentityPrincipalPort` | Consecuencia de F3 |

### `api`

| Archivo | Cambio | Fuente |
|---|---|---|
| `web/CoreApiErrorMappings.java` | `InvalidEvidenceRefException` → 400 | Criterio 6 |
| `asset/PhysicalAssetController.java` | Sin cambios. La validación vive en `core` (F3: "no en el agregado", en el servicio) | F3 |

### `app`

| Archivo | Cambio | Fuente |
|---|---|---|
| `application/media/MediaObjectStore.java` | Método nuevo **[Plan]** `BindResult bindEvidence(String organizationId, String sha256, String assetId)`. Escritura condicional en una sola operación: `{organizationId, purpose: DELIVERY_EVIDENCE, sha256, deletedAt: null, boundAssetId ∈ {null, assetId}}` → `$set boundAssetId`. Si no coincide nada, una lectura distingue `NOT_FOUND` de `BOUND_TO_OTHER`; la decisión ya la tomó la escritura condicional | F3 (escritura condicional; gana una) |
| `infrastructure/media/MongoMediaObjectStore.java` | Implementación con `updateFirst`, con el filtro dentro del índice parcial (`purpose` incluido) | F3; F9 |
| `application/media/DeliveryEvidenceBinding.java` (nuevo) **[Plan]** | Implementa `DeliveryEvidencePort`: quita el prefijo `sha256:`, llama a `bindEvidence` y traduce el resultado a las dos excepciones | F3 |
| `web/asset/DeliveryEvidenceController.java` (nuevo) **[Plan]** | `POST /api/v1/organizations/{organizationId}/delivery-evidence`, multipart `file` → `201 {evidenceRef}`. Autoriza **antes de leer los bytes**, como el logo: principal de esa organización con `EMPLOYEE`, con `OrganizationBoundaryPolicy` y `RoleAuthorizationPolicy.authorize(principal, DELIVER_ASSET)`. Cualquier otro caso da el mismo 403 (DD-01) | F8 |
| `web/MediaApiErrorMappings.java` | `EvidenceAlreadyBoundException` → 409, `EvidenceNotFoundException` → 400 | F3 |
| `bootstrap/DemoScenarioSeeder.java` | Antes de entregar, sube una imagen PNG generada en memoria por la ruta nueva y entrega con el `evidenceRef` devuelto | `ADR-051:258` |
| Tests: `GoldenPathHttpIntegrationTest`, `PhysicalAssetHttpIntegrationTest`, `PublicRoutesInventoryIntegrationTest` | Foto real y `evidenceRef` devuelto; la ruta nueva en el inventario (autenticada, no pública) | `ADR-051:258`; criterio 20 |

### Documentos

- `referencia-api-v1.md`: la ruta nueva y el cambio de la entrega (400 si el formato no es `sha256:`, 400 `EvidenceNotFound` y 409 `EvidenceAlreadyBound`).
- `estado-fase6.md`: sección nueva.
- Aviso a la web y al móvil (B-2).

## 4. Comportamiento ante fallos

| Caso | Resultado | Fuente |
|---|---|---|
| `evidenceRef` sin el formato `sha256:` | 400, sin reclamar el comando ni leer el activo **[Plan]** | F3; criterio 6 |
| Foto inexistente, de otra organización, que no es evidencia o borrada | El mismo 400 `EvidenceNotFound`; ni evento ni vínculo | F3; criterio 7 |
| Foto vinculada a otro activo | 409 `EvidenceAlreadyBound`; el activo no cambia | F3; criterio 8 |
| Sin permiso u otra organización | El 403 de siempre, **sin vincular** (`bindToAsset` no se llama) | F3 (orden); criterio 9b |
| Estado del activo que no permite entregar | El error del dominio de siempre, **sin vincular** | F3; criterio 9b |
| El mismo `Command-Id` reenviado | No-op en el reclamo (`:127-130`); el vínculo no se repite | F3; criterio 9 |
| `append` perdido por concurrencia | El reintento vuelve a vincular el mismo activo sin error. Si al final gana otra entrega, la foto queda vinculada sin entrega propia (riesgo residual 2, aceptado) | F3; `ADR-051:247` |
| El proveedor falla al subir | 503 `MediaStorageUnavailable` (T1, F2) | F2 |

## 5. Tests (rojos primero)

| # | Test | Criterio | Módulo | Rojo esperado |
|---|---|---|---|---|
| T4-1 | Subir un JPEG con GPS → `201 {evidenceRef}`; `evidenceRef` = `"sha256:"` + SHA-256 de los bytes **guardados** (no de los subidos) | 1 y 2 | `app` (HTTP) | 404 en la ruta (aserción) |
| T4-2 | `.jpg` que no empieza por los bytes de JPEG o PNG → 415 | 3 | `app` | 404 |
| T4-3 | Dimensiones por encima del máximo y pocos bytes → 413 sin decodificar | 4 | `app` (la parte del sanitizador ya está probada en T1) | 404 |
| T4-4 | Más de 5 MB → 413 | 5 | `app` | 404 |
| T4-5 | Sin JWT → 401; JWT de otra organización o sin `EMPLOYEE` → el 403 uniforme, sin leer los bytes | F8 | `app` | 404 |
| T4-6 | Entregar con `"acta-1"` → 400 y ningún evento | 6 | `core` y `app` | 200 (aserción) |
| T4-7 | Huella de otra organización, inexistente o borrada → el mismo 400 `EvidenceNotFound` (cuerpo idéntico) y ningún evento | 7 | `app` | Compilación |
| T4-8 | Una foto ya vinculada, en un segundo activo → 409 y ningún evento nuevo en el segundo activo | 8 | `app` | Compilación |
| T4-9 | La misma entrega con el mismo `Command-Id` → éxito, sin evento duplicado | 9 | `core` y `app` | Compilación |
| T4-9b | Entrega rechazada por permisos y por estado → `bindToAsset` nunca se llama (`never()`), `boundAssetId` sigue nulo y la foto sirve después en otro activo | 9b | `core` (doble) y `app` | Compilación |
| T4-10 | Dos entregas simultáneas de activos distintos con la misma foto → una gana y la otra recibe 409 | 10 | `app` (Mongo real) | Compilación |
| T4-11 | Un activo con un `ASSET_DELIVERED` antiguo de texto libre se reconstruye y su verificación no cambia | 11 | `core` | No es rojo: es la guarda |
| T4-12 | Despachar y recibir no llaman al gancho | F3 ("despachar y recibir no cambian") | `core` | Compilación |
| T4-13 | El golden path con foto real, en verde | 20 | `app` | — |

## 6. Mutaciones (`scripts/mutaciones.py`)

Antes, en un clon limpio: `mvn install` con sus tests de los módulos que no se mutan y la línea base en verde. Si no, una dependencia que falta cuenta como mutación muerta.

| # | Mutación | Debe morir en |
|---|---|---|
| 1 | Sin la validación del formato | T4-6 |
| 2 | El gancho se llama antes de `authorize` | T4-9b |
| 3 | El gancho se llama aunque no haya eventos (redundancia exacta) | T4-9 o un test de redundancia exacta **[Plan]** |
| 4 | `bindEvidence` sin la condición sobre `boundAssetId` | T4-8, T4-10 |
| 5 | `bindEvidence` sin `deletedAt: null` | T4-7 |
| 6 | `bindEvidence` sin `organizationId` en el filtro | T4-7 |
| 7 | La subida devuelve el hash sin el prefijo `sha256:` | T4-1 |
| 8 | La subida autoriza después de leer los bytes | T4-5 (con un doble que cuenta las lecturas) |
| 9 | Despachar pasa el gancho de la entrega | T4-12 |

## 7. Medición

`explain()` de la escritura condicional de `bindEvidence`: debe usar `uq_evidence_sha256` (IXSCAN), no `COLLSCAN`. Si el filtro no encaja en el índice parcial, se dice y se pregunta a Manuel antes de seguir.

## 8. Pasos

1. "Plan aprobado" de Manuel. Turno de T4 en el orden de Carlos (B-3).
2. Rama desde `develop`, con su hash.
3. Tests en rojo (§5), con su resumen de Surefire.
4. Código (§3).
5. Verde por capas, con el módulo completo en cada una: `contracts`, `core`, `api` y `app`, cada uno con su resumen literal.
6. Mutaciones (§6) y `explain()` (§7).
7. `free -g` con al menos 4 GB y `scripts/ci-local.sh` en un clon limpio. La evidencia va a `Documentos/evidencia-ci/`.
8. Autoauditoría contra F2, F3, F4, F5, F8 y los criterios 2 a 11 y 20: tabla "norma → lo hecho → coincide" en el PR.
9. PR hacia `develop` con Carlos de revisor; no se fusiona sin su Approve ni sin el aviso de B-2.

## 9. Puntos para el "plan aprobado"

| Punto | Recomendación |
|---|---|
| **P-T4-1** | La validación del formato, en `deliverAsset` y **antes** del reclamo del comando: un formato inválido no consume el `Command-Id` |
| **P-T4-2** | El gancho de `transition`, como `Consumer<PhysicalAsset>` opcional, que solo se llama si el agregado generó eventos |
| **P-T4-3** | Las excepciones del vínculo, en `contracts.media`, junto al puerto. `core` no las conoce más que para dejarlas pasar |
| **P-T4-4** | La subida autoriza con las mismas políticas de `core` (`OrganizationBoundaryPolicy` y `RoleAuthorizationPolicy` con `DELIVER_ASSET`) para que los roles no diverjan. F8 dice "de la organización del activo", pero la ruta de subida no tiene activo: se usa la organización de la ruta, y la entrega vuelve a comprobar que la foto es de la organización del activo (F3) |
| **P-T4-5** | `bindEvidence` en una escritura condicional y, solo si falla, una lectura para elegir entre 400 y 409 |
| **P-T4-6** | Los nombres marcados **[Plan]** |
| **P-T4-7** | El semillero sube una imagen PNG generada en memoria (sin ficheros nuevos en el repositorio) |

## 10. Riesgos

- **R-T4-1 (B-2):** al fusionar, los clientes que envían texto libre reciben 400. Mitigación: aviso previo a Adonis y a la web, y la enmienda de ADR-043.
- **R-T4-2:** riesgo residual 2 del ADR (una foto vinculada sin entrega propia), aceptado por Carlos.
- **R-T4-3:** las fotos subidas y nunca vinculadas se acumulan (Q3 (a), deuda registrada).
- **R-T4-4:** la deduplicación de la subida es de mejor esfuerzo (F4): la misma foto con otra versión de la JDK puede dar otra huella.
