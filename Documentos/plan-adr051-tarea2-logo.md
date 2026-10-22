# Plan — ADR-051, Tarea 2: logo de la organización

**Estado: APROBADO — Manuel, 2026-10-21** (ver "Aprobación — Manuel, 2026-10-21", al final). El texto de §0 a §9 se conserva tal como se aprobó, salvo los nombres de §4.7 (P-T2-8), la fila T2-5 y el riesgo R-T2-5, que se añaden por la aprobación.
**Fecha:** 2026-10-21. **Autor:** Manuel, con asistencia de Claude. **Base verificada:** `develop` `fdccd99` (tras #107).
**Norma:**
- `Documentos/ADR-051-fotos-y-almacenamiento.md` (APROBADO — Carlos, 2026-10-18), con la Enmienda 1 (18:45Z). Aplican F2, F4, F6, F8, F9 y F10, y los criterios 3, 4, 5, 12, 12b, 12c, 14 (en lo que toca al logo), 15 y 16.
- Las decisiones P-1 y P-2 de `plan-adr051-tarea1-almacenamiento.md` §7.3 (Manuel, 2026-10-21).

**Rama de implementación prevista:** `feat/app-adr051-t2-logo`, desde el `develop` del momento.

---

## 0. Aviso de procedencia

- **Texto del criterio 12.** El ADR de `develop` aún tiene el texto antiguo. El texto alineado con la Enmienda 1 ("El logo público (`GET /api/v1/public/campaigns/{publicCode}/organization-logo`) y la portada se leen sin JWT…; la lectura autenticada del logo se prueba en 12c") y la fila "Fichas CV-07, descubrimiento y S-18" están en el PR #108, todavía sin fusionar. Este plan sigue la Enmienda 1, que ya está aprobada y en `develop`.
- **Decisiones P-1 y P-2.** Se registran en el §7.3 del plan de la Tarea 1 en el mismo PR #108. Este plan las cita tal como las dio Manuel:
  - **P-1:** el controlador comprueba el límite por propósito con `MediaPurpose.maxBytes()`, y el servicio mantiene su comprobación.
  - **P-2:** la traducción a 413, 415 y 503 entra en esta tarea, en el formato de error de ADR-041.

## 1. Alcance

**Dentro**
- Subir, reemplazar y quitar el logo: `PUT` y `DELETE /api/v1/organizations/{organizationId}/logo` (F8).
- Leerlo de dos formas (F6, Enmienda 1):
  - pública por `publicCode`: `GET /api/v1/public/campaigns/{publicCode}/organization-logo`;
  - autenticada: `GET /api/v1/organizations/{organizationId}/logo`.
- La referencia del logo en `Organization` de `identity` (F9).
- El borrado del archivo anterior después de confirmar el cambio (F10).
- La traducción de errores 413, 415 y 503 (P-2).
- `PublicRoutes` y su test de inventario (F6).
- Los campos de URL del logo en descubrimiento, CV-07 y S-18 (§2, "Enmiendas de contrato").

**Fuera**
- La portada (Tarea 3, Carlos).
- La evidencia de entrega (tareas 4 y 5).
- La retirada por el administrador de plataforma (F7, `REMOVE_MEDIA`, Tarea 5).
- La enmienda de ADR-021-D.

## 2. Enmiendas de contrato (ADR-041)

Las fichas CV-07 y R-1 no están en el repositorio. Por eso las enmiendas de contrato se registran aquí; la de S-18 va además como nota fechada al final de su ficha. **`referencia-api-v1.md` se actualiza en el PR de implementación**, no en este.

| Lectura | Campo nuevo | Valor | Reglas |
|---|---|---|---|
| Descubrimiento, `GET /api/v1/public/campaigns` (`fichas-p2-endpoints.md` P2.6; ficha R-1, fuera del repositorio) | `organizationLogoUrl?` en cada ítem | `/api/v1/public/campaigns/{publicCode}/organization-logo` | Falta si la organización no tiene logo. **Ningún `organizationId`** (Enmienda 1) |
| CV-07, `GET /api/v1/public/campaigns/{publicCode}` (ficha CV-07, fuera del repositorio; `PublicCampaignController.java:30-38`) | `organizationLogoUrl?` | igual | igual |
| S-18, `GET /api/v1/organizations/{organizationId}` (`ficha-S18-estado-verificacion-organizacion.md`, nota del 2026-10-21) | `logoUrl?` | `/api/v1/organizations/{organizationId}/logo` (ruta autenticada) | Falta si no hay logo. Los roles de S-18 no cambian (Q-S18-2) |

- **`coverImageUrl?`**, de descubrimiento y CV-07, lo añade la Tarea 3 (Carlos). Aquí queda solo como **pendiente**.
- Las URLs son relativas a la raíz de la API, como las demás rutas de `referencia-api-v1.md`. La respuesta no lleva host.

## 3. Hechos del código que condicionan el plan (`develop` `fdccd99`)

| Hecho | Evidencia |
|---|---|
| `Organization` es un documento de Mongo (`organizations`), sin eventos ni versión. `save` reemplaza el documento entero | `identity/.../documents/OrganizationDocument.java:9-21`; `MongoOrganizationRepositoryAdapter.java:20-21` |
| Las escrituras de `identity` van en transacción con reintento, y la regla de ArchUnit lo exige a todo `*Service` | `VerifyOrganizationService.java:67`; `identity/src/test/.../ArchitectureTest.java:40-42` |
| `Organization` ya tiene un campo opcional añadido de la misma forma, `name` (DD-04), con un `reconstitute` sobrecargado | `Organization.java:26-27`, `:63-74` |
| El permiso de logo de F8 (`REPRESENTATIVE` o `ADMINISTRATOR`) ya existe como política | `OrganizationMembersPolicy.requireManager` (`identity/.../authorization/OrganizationMembersPolicy.java:16-19`) |
| La lectura pública por código ya resuelve la organización de la convocatoria | `ConvocatoriaReadPort.findPublicByCode` (`convocatoria/.../query/ConvocatoriaReadPort.java:9`), que usa CV-07 en `PublicCampaignController.java:32` |
| Lista de rutas sin JWT | `api/.../auth/PublicRoutes.java:28-40`; tests `PublicRoutesTest` (api) y `PublicRoutesInventoryIntegrationTest` (app) |
| Traducción de errores: un bean `ApiErrorMappings` por módulo. **`DETAILS` no tiene textos para 413, 415 ni 503**: usarían `DEFAULT_DETAIL` | `api/.../web/ApiExceptionHandler.java:51-58`; `app/.../web/IdentityApiErrorMappings.java`, `ConvocatoriaApiErrorMappings.java` |
| No hay `spring.servlet.multipart.*` en la configuración. F8 fija 5 MB y 6 MB | `app/src/main/resources/application*.yml` (sin coincidencias) |
| Tarea 1: `MediaUploadService.store(purpose, organizationId, ownerRef, uploadedBy, content)` → `StoredMedia(objectKey, sha256, …)`. `MediaObjectStore` solo tiene `insert` y `findByOrganizationIdAndPurposeAndSha256` | `app/.../media/MediaUploadService.java:58`; `MediaObjectStore.java` |
| La lectura autenticada de S-18 la arma `OrganizationVerificationStatusQuery.StatusView` | `identity/.../OrganizationVerificationStatusQuery.java:22`; `app/.../OrganizationVerificationStatusController.java:32-38` |

## 4. Decisiones del plan, con su fuente en ADR-051

### 4.1 Referencia del logo en `identity` (F9)

- `Organization` gana la referencia del logo: la **`objectKey`** del objeto en el proveedor (ULID de F2). El campo es opcional; si falta, no hay logo. Nombre propuesto: `logoObjectKey` (**P-T2-1**).
- **Sí toca la persistencia de `identity`**, de forma aditiva:
  - un campo en `OrganizationDocument` y su paso por `OrganizationMapper`;
  - un `reconstitute` sobrecargado, como se hizo con `name`.
  - No hay índice, migración ni relleno: los documentos sin el campo se leen sin logo.
- **Métodos del dominio:** `replaceLogo(objectKey)` devuelve la referencia anterior, si la hay, y `removeLogo()` hace lo mismo. Ninguno toca miembros ni el estado de verificación.
- **Servicio de `identity`:** fija o quita la referencia dentro de `MongoTransactionRetryHelper.executeWithRetry`. Antes valida el permiso con `OrganizationMembersPolicy.requireManager` y devuelve la referencia anterior **leída dentro de la transacción**.
  - Si dos reemplazos llegan a la vez, el conflicto de escritura hace reintentar al segundo, que lee la referencia del primero y la devuelve como anterior. Así ninguna referencia queda apuntando a un archivo borrado.
- **Auditoría:** `AuditAction` no tiene una acción para el logo. Añadirla sería un valor nuevo que ADR-051 no nombra (**P-T2-5**).

### 4.2 Subida y reemplazo (F2, F4, F8, F10)

Orden en `app` para `PUT /api/v1/organizations/{organizationId}/logo` (multipart, campo `file`):
1. **Permiso antes de leer los bytes.** `REPRESENTATIVE` o `ADMINISTRATOR` de esa organización (F8). Si no, el 403 uniforme (DD-01). Ningún byte llega al proveedor sin permiso.
2. **Límite por propósito en el controlador (P-1).** Si `file.getSize() > MediaPurpose.ORGANIZATION_LOGO.maxBytes()` (2 MB), → 413 `ImageTooLarge`. El servicio de la Tarea 1 lo vuelve a comprobar.
3. `MediaUploadService.store(ORGANIZATION_LOGO, organizationId, null, accountId, bytes)`: limpia, guarda con una `objectKey` nueva e inserta el metadato (F2, Tarea 1).
4. `identity` fija la referencia nueva y devuelve la anterior (§4.1). **Este es el cambio confirmado.**
   - Si falla, la imagen nueva queda huérfana y se registra `media.orphan objectKey=…` (F2), y el error sale tal cual.
5. **F10, solo después de 4:**
   - si había una referencia anterior, se borran sus bytes del proveedor y su metadato se marca con `deletedAt` y `deletedBy` (F9; motivo en **P-T2-3**);
   - si el borrado falla, la respuesta **sigue siendo 200** y se registra `media.orphan objectKey=…` con la clave anterior (criterio 16).
6. Respuesta `200 {logoUrl}`, con `logoUrl = /api/v1/organizations/{organizationId}/logo` (F8).

`DELETE /api/v1/organizations/{organizationId}/logo` aplica el permiso del paso 1, después el paso 4 con `removeLogo` y después el paso 5. Responde `204`. Sin logo previo: **P-T2-4**.

### 4.3 Lecturas (F6, Enmienda 1)

| Ruta | Acceso | Cómo | Cabeceras |
|---|---|---|---|
| `GET /api/v1/public/campaigns/{publicCode}/organization-logo` | pública (`PublicRoutes`, `PUBLIC`) | `ConvocatoriaReadPort.findPublicByCode` → `organizationRef` → la referencia de `identity` → `MediaStoragePort.get` | `Cache-Control: public, max-age=3600`, `ETag` con la huella (`sha256` del metadato), `Content-Type` del metadato |
| `GET /api/v1/organizations/{organizationId}/logo` | JWT: miembro de esa organización, con cualquier rol, o administrador de plataforma (Enmienda 1). El resto, el 403 uniforme. Sin JWT → 401, que da el filtro | la referencia de `identity` → `get` | `Cache-Control: private, no-store` |

- Las dos dan 404 en estos casos: convocatoria no visible por su código (Enmienda 1), organización sin logo, metadato con `deletedAt` o bytes ausentes en el proveedor. Este último cubre el criterio 14 en lo que toca al logo: un logo retirado nunca se sirve. **No** hay 410: según F6, el 410 es solo de la evidencia.
- Las dos devuelven los bytes guardados (limpios) y nunca un `organizationId` en la ruta pública.
- **El administrador de plataforma en la lectura autenticada:** ADR-051 no le asigna un `PlatformCommandType` para el logo, aunque sí lo hace para la evidencia (`READ_DELIVERY_EVIDENCE`) (**P-T2-2**).

### 4.4 `PublicRoutes` y su test de inventario (F6)

- Se añade `new Route("GET", "/api/v1/public/campaigns/{publicCode}/organization-logo", Access.PUBLIC)`. La ruta antigua de F6, `/api/v1/public/organizations/{organizationId}/logo`, **no** se añade (Enmienda 1, "Consecuencias").
- `PublicRoutesTest` (api): la ruta nueva es pública; `GET /api/v1/organizations/{id}/logo`, el `PUT` y el `DELETE` no lo son.
- `PublicRoutesInventoryIntegrationTest` (app): las cuatro rutas nuevas quedan clasificadas, y el test falla si una ruta queda sin clasificar.

### 4.5 Traducción de errores (P-2; ADR-041 §2.5)

- **Bean nuevo en `app`:** `MediaApiErrorMappings implements ApiErrorMappings`, como `IdentityApiErrorMappings` y `ConvocatoriaApiErrorMappings`. Traduce:
  - `ImageTooLargeException` → 413 `ImageTooLarge` (F4);
  - `UnsupportedImageFormatException` → 415 `UnsupportedImageFormat` (F4);
  - `MediaStorageUnavailableException` → 503 `MediaStorageUnavailable` (F2).
- **El límite de Spring** (más de 5 MB por fichero o 6 MB por petición) también debe acabar en 413 `ImageTooLarge`. Si la excepción de Spring no trae ya su código, se traduce en el mismo bean. El test rojo lo decide.
- **Textos fijos de 413, 415 y 503** en `ApiExceptionHandler.DETAILS` (`api`). Hoy usarían el texto por defecto (**P-T2-6**).
- **Configuración:** `spring.servlet.multipart.max-file-size=5MB` y `max-request-size=6MB` (F8) en `application.yml`. Afecta a todas las rutas, pero hoy ninguna otra es multipart.

### 4.6 Campos de URL en las lecturas (§2)

- **Descubrimiento y CV-07** (`app`): `organizationLogoUrl?` solo si la organización tiene logo.
  - Supone **una lectura más de `identity` por convocatoria**: hasta 20 en el descubrimiento, más las dos que ya hace (nombre y verificación; hallazgo H-L4 de L-FRONTS).
  - Para no sumar una tercera lectura, se propone una sola lectura de `identity` que devuelva si hay logo (**P-T2-7**).
- **S-18** (`identity`/`app`): `StatusView` y `OrganizationStatus` ganan la información de si hay logo, y la respuesta lleva `logoUrl?`.

### 4.7 Clases nuevas y por qué (regla 2.1)

| Clase o cambio | Módulo | Fuente |
|---|---|---|
| Campo y métodos del logo en `Organization`, `OrganizationDocument` y `OrganizationMapper` | `identity` | F9: "`identity`: la referencia del logo en `Organization`" |
| `identity.application.service.OrganizationLogoReferenceService`: servicio de escritura de la referencia (`replace`, `remove`) | `identity` | F9 y F10: el cambio se confirma en `identity`; la regla de ArchUnit pide `MongoTransactionRetryHelper` |
| `identity.application.service.OrganizationLogoReferenceQuery`: lectura de la referencia, sin transacción, como `OrganizationPublicNameQuery`; también da, en una sola lectura, nombre, verificación y si hay logo (P-T2-7) | `identity` | F6: ambas lecturas necesitan la referencia |
| `com.traceability.app.application.media.OrganizationLogoService`: orquestador de subida, reemplazo, borrado y lectura del logo (pasos de §4.2 y §4.3) | `app` | F2, F8 y F10; la Tarea 2 es de `identity` y `app` (tabla "Implementación" de ADR-051) |
| `com.traceability.app.web.organization.OrganizationLogoController` (rutas `/organizations/{organizationId}/logo`) y `com.traceability.app.web.campaign.PublicOrganizationLogoController` (ruta pública) | `app` | F6 y F8 |
| Valor `READ_ORGANIZATION_LOGO` en `identity.application.authorization.PlatformCommandType` | `identity` | P-T2-2 (aprobado) |
| `MediaObjectStore` gana `findByObjectKey` y `markDeleted(objectKey, deletedBy, deletedAt, reason)`, con su adaptador Mongo | `app` | F9 (campos `deletedAt`, `deletedBy` y `reason` "F7 y F10") y F6 (`ETag` con la huella y `Content-Type`) |
| `com.traceability.app.web.MediaApiErrorMappings` | `app` | P-2; F2 y F4 nombran los errores |

No se crean excepciones nuevas: se usan las de la Tarea 1 y el 403 uniforme de cada política.

## 5. Tests — rojos primero, mapeados a los criterios

| # | Test | Criterio |
|---|---|---|
| T2-1 | `PUT` con un `.jpg` que no empieza por bytes de JPEG o PNG → 415 `UnsupportedImageFormat`; ningún metadato ni referencia | 3 |
| T2-2 | `PUT` con una imagen pequeña en bytes pero con dimensiones excesivas → 413, sin decodificarla | 4 |
| T2-3 | `PUT` de más de 2 MB → 413 desde el controlador, **sin llamar** a `MediaUploadService` (`never()`); más de 5 MB → 413 desde el límite de Spring | 5; P-1 |
| T2-4 | Sin permiso (`EMPLOYEE`, otra organización, inexistente) → el mismo 403; ningún `put` en el proveedor (aserción negativa) | F8; DD-01 |
| T2-5 | `GET /public/campaigns/{publicCode}/organization-logo` sin JWT → 200 con los bytes guardados, `Cache-Control: public, max-age=3600` y `ETag` igual a la huella, entre comillas (`"<sha256>"`; Aprobación) | 12, 12b |
| T2-6 | Ninguna respuesta pública (descubrimiento, CV-07, logo) contiene el `organizationId` (aserción negativa sobre el cuerpo y las cabeceras) | 12b |
| T2-7 | `GET /organizations/{id}/logo`: sin JWT → 401; con JWT de otra organización → el 403 uniforme; un `EMPLOYEE` de la organización → 200 con `private, no-store`; administrador de plataforma → 200 (según P-T2-2) | 12c |
| T2-8 | Código de una convocatoria inexistente, organización sin logo, metadato con `deletedAt` o bytes ausentes → 404, nunca 410 | 14 (logo); Enmienda 1 |
| T2-9 | Reemplazo: el archivo anterior se borra **después** de confirmar la referencia nueva (orden con `InOrder`); si falla el borrado → 200 y `media.orphan` con la clave anterior | 16; F10 |
| T2-10 | Si falla la escritura de `identity` → el archivo nuevo queda huérfano y registrado, y la referencia y el archivo anteriores no cambian | F2; F10 |
| T2-11 | Fallo del proveedor dos veces al subir → 503 `MediaStorageUnavailable`, ni metadato ni referencia | 15 |
| T2-12 | `DELETE` → 204; la referencia desaparece y después se borra el archivo; las lecturas dan 404 | F8; F10 |
| T2-13 | Dos `PUT` simultáneos → la referencia final apunta a uno de los dos archivos, y el otro queda borrado o registrado como huérfano, nunca referenciado | F10; §4.1 |
| T2-14 | `PublicRoutesTest` e inventario (§4.4) | F6 |
| T2-15 | Descubrimiento y CV-07: `organizationLogoUrl` presente con logo y ausente sin él. S-18: `logoUrl` igual | §2 |
| T2-16 | `identity`: un documento de organización sin el campo se lee sin logo; reemplazar devuelve la referencia anterior; quitar sin logo (P-T2-4) | F9 |
| T2-17 | Común: `scripts/ci-local.sh` completo en verde, con el resumen literal de Surefire | 20 |

## 6. Mutaciones (con `scripts/mutaciones.py`, como en la Tarea 1)

1. Borrar el archivo anterior **antes** de confirmar la referencia → muere T2-9.
2. Quitar el límite por propósito del controlador → muere T2-3 (`never()`).
3. Subir antes de comprobar el permiso → muere T2-4.
4. `Cache-Control: public` en la ruta autenticada → muere T2-7.
5. Leer la referencia anterior fuera de la transacción → muere T2-13.
6. Añadir la ruta autenticada a `PublicRoutes` → muere T2-7 y el inventario.
7. Servir un logo con `deletedAt` → muere T2-8.
8. Quitar la traducción de `UnsupportedImageFormatException` → muere T2-1 (daría 500).
9. Exponer `organizationId` en el `ETag` o en una cabecera → muere T2-6.

## 7. Orden de trabajo (tras "plan aprobado")

1. Tests rojos de `identity` (T2-16) y de `app` (T2-1 a T2-15), y salida roja literal.
2. Verde por capas: `identity` (dominio, persistencia y servicio) → `MediaObjectStore` → orquestador → controladores → `PublicRoutes` → traducción de errores → campos de URL.
3. Commit, mutaciones y `ci-local.sh` en un clon limpio, con la evidencia en `Documentos/evidencia-ci/`.
4. `referencia-api-v1.md` (rutas nuevas y los campos de §2), aviso a la web y al móvil, y `estado-fase6.md`.

## 8. Riesgos y preguntas

### 8.1 Riesgos

- **R-T2-1:** `save` de `Organization` reemplaza el documento entero. La transacción con reintento evita perder una escritura concurrente (cambio de miembros o verificación), y T2-13 lo cubre en parte.
- **R-T2-2:** una lectura más de `identity` por ítem del descubrimiento (§4.6), dentro de lo aceptado en H-L4 si se usa una sola lectura (P-T2-7).
- **R-T2-3:** el límite de *multipart* es global (F8). Hoy no hay otra ruta multipart, pero la evidencia (Tarea 4) lo comparte, y por eso es el de 5 MB.
- **R-T2-4:** el criterio 12 de `develop` todavía tiene el texto antiguo hasta que se fusione #108 (§0).
- **R-T2-5 (aceptado, Manuel, 2026-10-21):** tras un reemplazo, el logo público anterior puede seguir servido desde cachés hasta 1 h (`max-age=3600`).

### 8.2 Preguntas para la aprobación

| # | Pregunta | Recomendación |
|---|---|---|
| **P-T2-1** | Nombre y contenido del campo en `Organization` | `logoObjectKey`, la `objectKey` del proveedor (no la huella, que no es única fuera de la evidencia) |
| **P-T2-2** | Administrador de plataforma en `GET /organizations/{id}/logo`: ¿`PlatformCommandType` nuevo, o comprobar `platformAuthority` directamente? ADR-051 no nombra uno para el logo | Un `PlatformCommandType` nuevo (`READ_ORGANIZATION_LOGO`), como `READ_DELIVERY_EVIDENCE`, para que pase por `PlatformAuthorizationPolicy`. Es un valor nuevo: necesita aprobación |
| **P-T2-3** | `reason` del metadato cuando F10 borra un logo reemplazado o quitado. F9 lo lista "para F7 y F10" sin valores | `REPLACED` o `REMOVED`, con `deletedBy` = la cuenta que hizo el cambio |
| **P-T2-4** | `DELETE` sin logo previo | 204 (idempotente), sin tocar el proveedor |
| **P-T2-5** | ¿Auditar el cambio de logo en `identity` con una `AuditAction` nueva? | No en esta tarea: el metadato ya guarda `uploadedBy`/`uploadedAt` y `deletedBy`/`deletedAt`. Queda registrado |
| **P-T2-6** | Textos fijos de 413, 415 y 503 en `ApiExceptionHandler.DETAILS` (`api`) | Sí, en este PR: cambio de una línea por código, sin datos de la petición |
| **P-T2-7** | Descubrimiento: ¿una lectura de `identity` que dé nombre y logo a la vez? | Sí, una lectura de `identity` por ítem en lugar de dos; sin cambiar `OrganizationPublicNamePort` (está en `contracts`; regla 3.5) |
| **P-T2-8** | Nombres de las clases nuevas de §4.7 | Se fijan al aprobar el plan |
| **P-T2-9** | ¿Soporte de `If-None-Match` → 304 en la ruta pública? | No en este corte: solo `ETag` (lo que pide F6) |

## 9. Archivos previstos

**`identity`:** `domain/model/Organization.java`, `infrastructure/persistence/mongo/documents/OrganizationDocument.java`, `mappers/OrganizationMapper.java`, `application/service/OrganizationVerificationStatusQuery.java`, el servicio y la lectura nuevos (§4.7), y sus tests.
**`app`:**
- `application/media/` (orquestador, `MediaObjectStore` y `MongoMediaObjectStore`);
- `web/organization/` (controlador del logo y `OrganizationVerificationStatusController`);
- `web/campaign/` (ruta pública, `PublicCampaignController` y `PublicCampaignDiscoveryController`);
- `web/MediaApiErrorMappings.java`;
- `application.yml` (*multipart*);
- tests.

**`api`:** `auth/PublicRoutes.java`, `PublicRoutesTest`, `web/ApiExceptionHandler.java` (según P-T2-6).
**Documentos:** `referencia-api-v1.md`, `estado-fase6.md`, `Documentos/evidencia-ci/`.

## Aprobación — Manuel, 2026-10-21

**Plan aprobado** (revisión del PR #109), con estas respuestas a §8.2:

| # | Decisión |
|---|---|
| **P-T2-1** | `logoObjectKey` (la `objectKey` del proveedor) |
| **P-T2-2** | `PlatformCommandType` nuevo `READ_ORGANIZATION_LOGO`, por `PlatformAuthorizationPolicy` |
| **P-T2-3** | `reason` = `REPLACED` o `REMOVED`, con `deletedBy` = la cuenta que hizo el cambio |
| **P-T2-4** | `DELETE` sin logo previo → `204` idempotente, sin tocar el proveedor |
| **P-T2-5** | Sin auditoría nueva (ninguna `AuditAction` nueva) |
| **P-T2-6** | Sí: textos fijos de 413, 415 y 503 en `ApiExceptionHandler.DETAILS`, en este PR |
| **P-T2-7** | Una sola lectura de `identity` por organización (nombre, verificación y si hay logo), **sin cambiar puertos existentes** |
| **P-T2-8** | Nombres fijados en §4.7 antes de la fase roja: `OrganizationLogoReferenceService`, `OrganizationLogoReferenceQuery`, `OrganizationLogoService`, `OrganizationLogoController`, `PublicOrganizationLogoController`, `MediaApiErrorMappings` |
| **P-T2-9** | Sin `If-None-Match` → 304 en este corte |

**Además:**
- **T2-5** comprueba también que el `ETag` vaya entre comillas (`"<sha256>"`).
- **Riesgo nuevo R-T2-5 (aceptado):** tras un reemplazo, el logo público anterior puede seguir en cachés hasta 1 h (`Cache-Control: public, max-age=3600`).

**Orden:** la Tarea 2 se implementa después del Bloque A de R-6, en su propia rama, para no chocar con los mismos archivos.
