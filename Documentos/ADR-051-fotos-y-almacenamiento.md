# ADR-051 — Fotos y almacenamiento: logo de organización, portada de convocatoria y evidencia de entrega

## Status

**APROBADO — Carlos, 2026-10-18**, con la **Enmienda 1** (APROBADA — Carlos, 2026-10-18T18:45Z; §"Enmienda 1"). Redactado por el agente a petición de Carlos (M-08 y foto de entrega obligatoria, decisión 6 del 16 de octubre).

Aprobaciones de Carlos, explícitas en la conversación de trabajo:
- **2026-10-18T18:26Z:** F1 a F10.
- **2026-10-18T18:34Z (aprobación definitiva):** el diseño de detalle de F1 a F10, el esquema de `media_objects`, los límites numéricos, los riesgos residuales, el ajuste del orden del vínculo en F3, los conflictos C1 y C2 y las preguntas Q1 a Q3. El esquema y los límites, que estaban pendientes de revisar con Manuel, quedan cerrados por esta aprobación.

Aprobarlo **no** autoriza código: cada tarea necesita su plan aprobado (regla 3.4).

Dueño: compartido. `app` (almacenamiento y metadatos), `identity` (logo), `convocatoria` (portada), `core` (evidencia). Cubre, por la regla 3.5:
- una dependencia nueva (cliente S3);
- una integración externa nueva (almacenamiento compatible con S3);
- un puerto nuevo en `contracts` usado por varios módulos;
- un cambio de significado del campo `evidenceRef` de `ASSET_DELIVERED`.

## Context

### Lo que ya está decidido y no se reabre

| Decisión | Fuente |
|---|---|
| La imagen nunca va a MongoDB ni a la blockchain; en la base solo su referencia | Lista de tareas de Carlos, 2026-10-18 |
| Las credenciales nunca en el repositorio; *fail-fast* si faltan | ADR-047 D3; ADR-049 D1 |
| La foto de entrega es obligatoria | Decisión 6 de la reunión del 16 de octubre (sustituye lo dicho el 14 de octubre, cuando era opcional) |
| Despachar y recibir funcionan sin conexión con la cola del móvil | ADR-043 D6 |
| Los eventos son inmutables; los antiguos no se reescriben | Documento maestro |
| Perímetro de exposición del seguimiento público | ADR-021-D |
| Las operaciones de convocatoria son exclusivas del `ADMINISTRATOR`; el respaldo del `REPRESENTATIVE` no aplica | ADR-037 §5 |

### Hechos verificados en el código (`develop` `4bef7be`, 2026-10-18)

- No existe ningún campo de imagen, subida de archivos ni proveedor de almacenamiento.
- `ASSET_DELIVERED` lleva `evidenceRef` como texto libre (`AssetDeliveredPayload`). El dominio lo admite nulo (diseño de Fase 2); **el HTTP ya lo exige no vacío** (`PhysicalAssetController.deliver`). El semillero usa `"acta-1"`.
- Lo nuevo no es la obligatoriedad, sino el **significado**: pasa de texto libre a la huella de una foto que el backend guarda y verificó.
- El seguimiento público (`PublicLogisticsItemDTO`, `PublicTransitionDTO`) **no** expone `evidenceRef` hoy.
- Los valores antiguos de `evidenceRef` son texto libre y **pueden contener cualquier cosa**, incluidos datos personales.

## Decision

### F1. Almacenamiento compatible con S3, con adaptador local para desarrollo [Carlos]

- Producción: un proveedor con API compatible con S3 (por ejemplo, Cloudflare R2). Sirve igual si se despliega en un VPS o en Render.
- `dev` y `scripts/ci-local.sh`: adaptador local (directorio temporal) y uno en memoria para tests unitarios.
- Selección: `traceability.media.storage = s3 | local`. Fuera de `dev`, `local` no se admite y faltar cualquier valor de S3 impide arrancar (*fail-fast*).
- Configuración por entorno: `MEDIA_S3_ENDPOINT`, `MEDIA_S3_REGION` (en R2, `auto`), `MEDIA_S3_BUCKET`, `MEDIA_S3_ACCESS_KEY_ID`, `MEDIA_S3_SECRET_ACCESS_KEY`. Nunca en ficheros versionados ni en logs.
- Dependencia: `software.amazon.awssdk:s3`, **solo en `app`**, con la versión fijada en el `dependencyManagement` del `pom.xml` padre. Con el cliente HTTP ligero de la JDK (`url-connection-client`), excluyendo el cliente basado en Netty. El PR verifica que el BOM de Spring Boot no la gestiona.
- El *bucket* es **privado**: ningún objeto tiene URL pública del proveedor. Todo se sirve a través del backend (F6).
- El proveedor concreto y su costo se confirman con el despliegue, que sigue sin decidir.

### F2. Subida a través del backend [Carlos]

El backend recibe el archivo, lo valida, lo limpia (F4), calcula la huella y lo guarda. El cliente nunca escribe en el proveedor.

**Orden de escritura**, para que nunca exista un metadato que apunte a un archivo inexistente:
1. Validar y limpiar en memoria.
2. Guardar los bytes en el proveedor con una clave aleatoria (ULID), sin datos de la organización.
3. Insertar el metadato en MongoDB.

- Si falla el paso 3, queda un archivo huérfano en el proveedor. No causa daño; se registra con su clave.
- **Fallo ambiguo del proveedor** (tiempo agotado al guardar): guardar con la misma clave y los mismos bytes es idempotente, así que se reintenta **una vez**. Si vuelve a fallar → 503 `MediaStorageUnavailable` (`MediaStorageUnavailableException`) y no se inserta metadato.

### F3. `evidenceRef = sha256:<64 hex>` de los bytes limpios [Carlos]

- Los comandos nuevos de entrega exigen `evidenceRef` con el formato `^sha256:[0-9a-f]{64}$`.
- La validación del formato y del vínculo vive en el servicio de aplicación de `core` (`PhysicalAssetCommandService.deliverAsset`), no en el agregado: así el agregado sigue reconstruyendo los eventos antiguos con texto libre sin cambios.
- **Eventos antiguos:** se siguen leyendo igual. No se reescriben ni cambia la verificación de la cadena: el hash del evento se calcula sobre el texto que tenga, sea libre o `sha256:`.
- `ASSET_DELIVERED` **no** cambia de versión de esquema: el tipo del campo sigue siendo texto. Todo lector debe tratar como texto libre heredado cualquier `evidenceRef` sin prefijo `sha256:`.

**Una foto, un activo [Carlos]:**
- Una foto de evidencia solo puede vincularse a **un** `assetId`. Usarla en otro activo → 409 `EvidenceAlreadyBound` (`EvidenceAlreadyBoundException`).
- Reintentar la entrega del **mismo** `assetId` con la misma foto (mismo `Command-Id` o redundancia exacta) se permite sin error.

**Mecanismo del vínculo:**
- Puerto `DeliveryEvidencePort.bindToAsset(organizationId, evidenceRef, assetId)` en `contracts`; lo implementa `app`.
- Escritura condicional sobre el metadato: se vincula si `boundAssetId` es nulo **o ya es ese mismo `assetId`**. Si es otro → 409. Dos entregas simultáneas de activos distintos con la misma foto: gana una.
- Comprueba también que la foto exista, sea de evidencia, pertenezca a la misma organización que el activo y no esté borrada. Si no → 400 `EvidenceNotFound`, el mismo para todos esos casos.
- **Orden [Carlos, 2026-10-18T18:34Z]:** dentro de `PhysicalAssetCommandService.deliverAsset`, en cada intento del reintento de `transition()`:
  1. cargar el activo;
  2. `authorize(...)`;
  3. `PhysicalAsset.deliver(...)` valida la transición en memoria;
  4. **`DeliveryEvidencePort.bindToAsset(...)`**, solo si el agregado generó un evento (una redundancia exacta no genera evento y la foto ya está vinculada a ese activo);
  5. `appendAndOutbox(...)`.
- Así, un fallo de permisos o un estado del activo que no permite entregar **no vincula** la foto.
- El vínculo es una escritura aparte de la transacción de `appendAndOutbox`. Si el `append` pierde por concurrencia y, al reintentar, otra entrega ya ganó, la foto queda vinculada a este activo sin entrega propia (riesgo residual 2). Un reintento del mismo comando sobre el mismo activo vuelve a vincular sin error.
- El reintento genérico de `transition()` recibe el paso 4 como un gancho que solo usa la entrega; despachar y recibir no cambian.
- `DemoScenarioSeeder` y `GoldenPathHttpIntegrationTest` se actualizan en el PR que lo implemente.

### F4. El backend quita los metadatos con la JDK [Carlos]

- Con `javax.imageio` de la JDK, sin librerías nuevas:
  1. Identificar el formato por sus primeros bytes: JPEG `FF D8 FF`, PNG `89 50 4E 47 0D 0A 1A 0A`. Ni la extensión ni el `Content-Type` cuentan.
  2. Leer **solo la cabecera** y rechazar si las dimensiones superan el máximo **antes** de decodificar (protección contra imágenes que ocupan poco pero explotan en memoria).
  3. Decodificar y volver a codificar en el mismo formato **sin metadatos**: se descartan EXIF (incluido el GPS), XMP y comentarios. JPEG con calidad 0,9.
  4. Calcular el SHA-256 sobre los bytes resultantes.
- La subida devuelve el `evidenceRef`.
- **Límites [Carlos]:** como máximo 8000 px por lado y 40 megapíxeles.
- **Errores nombrados:** formato no admitido o imagen que la JDK no decodifica (por ejemplo, JPEG CMYK) → 415 `UnsupportedImageFormat`; tamaño o dimensiones excesivas → 413 `ImageTooLarge`.
- Consecuencia: volver a codificar descarta también el perfil de color y **la orientación del EXIF**. Por Q1 (a), la app endereza la imagen antes de subirla.
- Volver a codificar no es determinista entre versiones de la JDK. La huella vale para los bytes guardados, que son los que importan; la deduplicación de subidas iguales es solo de mejor esfuerzo.

### F5. Entregar exige conexión [Carlos]

- En este corte, el comando `DELIVER` no entra en la cola sin conexión del móvil. Primero se sube la foto, se recibe el `evidenceRef` y se envía la entrega, todo con conexión.
- Despachar y recibir conservan su comportamiento actual (ADR-043 D6).
- Una entrega enviada con conexión cuya respuesta no llega sigue siendo ambigua: se resuelve con "Verificar estado" y el mismo `Command-Id` (ADR-043 D6). Reintentarla es seguro por F3 (mismo activo).
- Lo formaliza la enmienda de ADR-043.

### F6. Quién ve cada foto [Carlos]

- Logo y portada: públicos.
- Evidencia de entrega: solo miembros de la organización del activo y el administrador de plataforma.
- El donante, en el seguimiento público, ve que hay evidencia registrada y su huella, **nunca la imagen**.

**Cómo se sirven** (todo a través del backend; el *bucket* es privado):

| Ruta | Acceso | Cabeceras |
|---|---|---|
| `GET /api/v1/public/campaigns/{publicCode}/organization-logo` | pública: el logo de la organización dueña de la convocatoria (Enmienda 1) | `Cache-Control: public, max-age=3600`, `ETag` con la huella |
| `GET /api/v1/organizations/{organizationId}/logo` | JWT: miembro de la organización o administrador de plataforma (Enmienda 1) | `Cache-Control: private, no-store` |
| `GET /api/v1/public/campaigns/{publicCode}/cover` | pública | ídem |
| `GET /api/v1/physical-assets/{assetRef}/evidence` | JWT: miembro de la organización del activo, o administrador de plataforma (`PlatformCommandType` nuevo `READ_DELIVERY_EVIDENCE`) | `Cache-Control: private, no-store` |

- Sin imagen → 404. Imagen borrada por F7 → 410 `EvidenceRemoved` en la ruta de evidencia.
- Las dos rutas públicas se añaden a `PublicRoutes` y al test de inventario en el mismo PR.
- Las vistas públicas de convocatoria (descubrimiento y CV-07) ganan `organizationLogoUrl?` (que apunta a `/api/v1/public/campaigns/{publicCode}/organization-logo`) y `coverImageUrl?`, ausentes si no hay imagen. **Ninguna ruta ni respuesta pública contiene `organizationId`** (Enmienda 1). La lectura autenticada de la organización (S-18) gana `logoUrl?`. Afecta a la ficha CV-07, al descubrimiento y a la ficha de la organización: se registran como enmiendas de contrato (ADR-041).

**Huella en el seguimiento público:**
- Para un activo entregado, el seguimiento expone `evidenceRegistered: true` y `evidenceRef` **solo si tiene el formato `sha256:`**.
- Un `evidenceRef` antiguo de texto libre **nunca** se expone: puede contener datos personales. Para ese caso, `evidenceRegistered: true` sin `evidenceRef`.
- Amplía el perímetro de ADR-021-D: C2 (a) aprueba esa enmienda, que se redacta antes del código.

### F7. Ni personas ni menores en las fotos [Carlos]

- Se fotografía el lote o el acta, nunca a personas. La app muestra el aviso de forma fija junto a la cámara (los datos de niños tienen protección reforzada, art. 7 de la Ley 1581 de 2012).
- El administrador de plataforma puede **borrar el archivo** de una foto que incumpla la regla. La huella queda en el evento de `core`, que es inmutable.
- `POST /api/v1/platform/organizations/{organizationId}/media/remove` con `{purpose, ref, reason}`, con un `PlatformCommandType` nuevo `REMOVE_MEDIA`. Borra los bytes del proveedor y deja en el metadato `deletedAt`, `deletedBy` y `reason`; el metadato **no** se borra, porque es el rastro de lo que se hizo.
- La misma operación sirve para retirar un logo o una portada inapropiados.

### F8. Formatos, tamaños y permisos de subida [Carlos]

- Solo JPEG y PNG, validados por contenido (F4).
- Máximo 5 MB para la evidencia y 2 MB para logo y portada. Límites de Spring: `spring.servlet.multipart.max-file-size=5MB` y `max-request-size=6MB`; el límite de 2 MB lo comprueba el controlador según el propósito.
- Logo: `REPRESENTATIVE` o `ADMINISTRATOR` de la organización.
- Portada: **solo `ADMINISTRATOR`** de la organización, para subir y para borrar (C1 (a); ADR-037 §5 intacto).
- Evidencia: los roles que `RoleAuthorizationPolicy` autoriza para entregar, de la organización del activo.

**Rutas de subida** (multipart, campo `file`):

| Ruta | Respuesta |
|---|---|
| `PUT /api/v1/organizations/{organizationId}/logo` | `200 {logoUrl}` |
| `DELETE /api/v1/organizations/{organizationId}/logo` | `204` |
| `PUT /api/v1/organizations/{organizationId}/campaigns/{campaignRef}/cover` | `200 {coverImageUrl}` |
| `DELETE /api/v1/organizations/{organizationId}/campaigns/{campaignRef}/cover` | `204` |
| `POST /api/v1/organizations/{organizationId}/delivery-evidence` | `201 {evidenceRef}` |

Sin permiso, otra organización o recurso inexistente → el mismo 403 (DD-01).

### F9. Puerto en `contracts`, adaptadores en `app` [Carlos]

- `contracts`: `MediaStoragePort`, solo con tipos de Java:
  - `void put(String objectKey, byte[] content, String contentType)`
  - `Optional<byte[]> get(String objectKey)`
  - `void delete(String objectKey)`
  - Fallos: `MediaStorageUnavailableException`.
- `app`: `S3MediaStorageAdapter`, `LocalMediaStorageAdapter` (`dev`) y uno en memoria para tests.
- Cada módulo guarda solo su referencia:
  - `identity`: la referencia del logo en `Organization`;
  - `convocatoria`: la de la portada en la convocatoria;
  - `core`: el `evidenceRef` en el evento.
- Los metadatos viven en una colección `media_objects`, propiedad del adaptador de `app`:

| Campo | Contenido |
|---|---|
| `objectKey` | ULID; clave en el proveedor; índice único |
| `sha256` | huella de los bytes guardados |
| `purpose` | `ORGANIZATION_LOGO` \| `CAMPAIGN_COVER` \| `DELIVERY_EVIDENCE` |
| `organizationId` | organización dueña |
| `ownerRef` | `campaignRef` para la portada; nulo en los demás |
| `contentType`, `sizeBytes`, `width`, `height` | |
| `uploadedBy`, `uploadedAt` | `accountId` e instante |
| `boundAssetId` | solo evidencia (F3) |
| `deletedAt`, `deletedBy`, `reason` | F7 y F10 |

- Índice único `(organizationId, purpose, sha256)` para la evidencia: subir dos veces los mismos bytes devuelve el mismo `evidenceRef` sin duplicar.

### F10. Reemplazar y borrar [Carlos]

- Al reemplazar o quitar un logo o una portada, el archivo anterior se borra **después** de confirmar el cambio. Si el borrado falla, queda huérfano y se registra con su clave.
- La evidencia de entrega no se borra, salvo por F7 o por una solicitud formal de supresión (ADR-050 D10).

### F11. Sin cambios en la cadena

- La forma del payload de `ASSET_DELIVERED` no cambia. La canonicalización, el hash, Merkle y el anclaje tampoco.
- La huella de la foto queda anclada **dentro** del evento (por el `evidenceRef`), sin que la imagen salga del almacenamiento privado.

## Conflictos — resueltos por Carlos el 2026-10-18

| # | Conflicto | Opciones | Recomendación del agente | Decisión |
|---|---|---|---|---|
| **C1** | F8 permite que el `REPRESENTATIVE` suba la portada de una convocatoria. ADR-037 §5 hace exclusivas del `ADMINISTRATOR` todas las operaciones de convocatoria y dice que "el respaldo del `REPRESENTATIVE` no aplica" | (a) Portada solo `ADMINISTRATOR`, alineado con ADR-037. (b) Mantener al `REPRESENTATIVE` como excepción explícita, con una enmienda de ADR-037 §5 limitada a la portada | **(a)**: no abre una excepción en la regla de permisos de convocatoria por una imagen | **(a)**: subir y borrar la portada es exclusivo del `ADMINISTRATOR` |
| **C2** | F6 expone la huella en el seguimiento público. Hoy los DTO públicos no la llevan, y ADR-021-D fija ese perímetro | (a) Enmendar ADR-021-D con `evidenceRegistered` y `evidenceRef` solo en formato `sha256:` (F6). (b) Exponer solo `evidenceRegistered` | **(a)**: la huella no revela nada sin la imagen, y permite comprobar una foto si la organización la muestra. Requiere la enmienda antes del código | **(a)**: se enmienda ADR-021-D |

## Preguntas — cerradas por Carlos el 2026-10-18

| # | Pregunta | Opciones | Recomendación del agente | Decisión |
|---|---|---|---|---|
| **Q1** | Orientación: al quitar el EXIF se pierde la orientación y las fotos verticales del teléfono pueden quedar giradas | (a) La app endereza la imagen antes de subirla (requisito para la enmienda de ADR-043). (b) El backend lee la orientación del EXIF y gira la imagen antes de quitarlo, con la JDK y más código | **(a)**: menos código en el backend y en la app es una operación habitual | **(a)** |
| **Q2** | Límites de dimensiones (8000 px por lado, 40 megapíxeles) y de tamaño | Valores de esta propuesta u otros | Confirmar con Manuel | **8000 px por lado, 40 megapíxeles, 5 MB evidencia, 2 MB logo y portada** |
| **Q3** | Fotos de evidencia subidas y nunca vinculadas | (a) Deuda registrada: no se limpian en este corte. (b) Tarea programada que las borra pasados N días | **(a)**: una tarea programada es un mecanismo nuevo sin necesidad demostrada en la demo | **(a)**: deuda registrada |

## Alternatives

| Alternativa | Por qué no |
|---|---|
| Guardar las imágenes en MongoDB (GridFS) | Descartado por Carlos: la imagen nunca va a MongoDB |
| Disco del servidor en producción | Solo funciona en VPS; en Render no persiste entre despliegues |
| Cloudinary | Transforma las imágenes, lo que rompe la huella si no se guarda el original |
| Subida directa al proveedor con URL firmada | El backend no ve los bytes: no puede quitar el GPS ni calcular la huella de lo guardado |
| URLs públicas del *bucket* | Habría que separar *buckets* por visibilidad; un error de configuración expondría las evidencias |
| Identificador opaco en `evidenceRef` en lugar de la huella | La foto no quedaría anclada en la cadena |
| La app quita los metadatos | Depende de que cada cliente lo haga bien; el backend no podría garantizarlo |
| Subir versión de esquema de `ASSET_DELIVERED` | El tipo del campo no cambia; se resuelve con la validación del comando |
| Entrega sin conexión con la foto en el teléfono | Fotos de beneficiarios en un dispositivo que se puede perder, y un paso nuevo en una cola bloqueada por H2 (ADR-043 D11) |
| Librería de imágenes de terceros | La JDK basta para JPEG y PNG |

## Consequences

**Positivas**
- La foto de entrega queda anclada en la cadena por su huella sin salir del almacenamiento privado.
- Las coordenadas GPS del teléfono nunca llegan a guardarse.
- Una foto no se puede reciclar en otro activo.
- Producción y desarrollo usan la misma interfaz; el proveedor se puede cambiar sin tocar el dominio.

**Negativas**
- Una dependencia nueva (cliente S3) y cinco valores de configuración.
- Todo el tráfico de imágenes pasa por el backend.
- Entregar exige conexión en este corte.
- El perfil de color y la orientación del EXIF se pierden al limpiar (Q1).
- Puede haber archivos huérfanos en el proveedor (F2, F10) y fotos de evidencia nunca vinculadas: **deuda registrada** (Q3 (a)), sin tarea de limpieza en este corte.

**Riesgos residuales — aceptados por Carlos, 2026-10-18**
1. El aviso de no fotografiar personas depende de quien toma la foto; el borrado de F7 es posterior.
2. Si el `append` de una entrega pierde por concurrencia frente a otra entrega del mismo activo con otra foto, la primera foto queda vinculada a ese activo sin entrega propia e inutilizable para otros activos (F3). Los rechazos por permisos o por estado no la vinculan.
3. Las rutas públicas de imágenes no tienen límite de peticiones (sigue abierto DH-56).

## Documentos que cambian por este ADR

| Documento | Cambio | Responsable |
|---|---|---|
| Enmienda de ADR-043 (móvil) | `DELIVER` con conexión; subir la foto antes de entregar; dependencia de cámara; aviso de no fotografiar personas; enderezar la orientación antes de subir (Q1 (a)) | Adonis |
| Enmienda de ADR-021-D | Huella en el seguimiento público (C2 (a), aprobada; se redacta antes del código de la tarea 5) | Carlos |
| Fichas CV-07, descubrimiento y S-18 (ADR-041) | `organizationLogoUrl?` y `coverImageUrl?` en descubrimiento y CV-07; `logoUrl?` en la lectura autenticada de la organización (S-18) | Carlos y Manuel |
| `referencia-api-v1.md` | Rutas de subida, lectura y retirada | Quien implemente cada una |
| `DemoScenarioSeeder`, `GoldenPathHttpIntegrationTest` | `evidenceRef` con formato `sha256:` de una foto real | Quien implemente F3 |

## Definición de hecho (tests, rojos primero)

**Limpieza y huella (F4)**
1. Un JPEG con GPS en el EXIF se guarda sin ningún metadato EXIF ni XMP (se comprueba leyendo los bytes guardados).
2. El `evidenceRef` devuelto es el SHA-256 de los bytes guardados, no de los subidos.
3. Un archivo con extensión `.jpg` que no empieza por los bytes de JPEG o PNG → 415.
4. Una imagen pequeña en bytes pero con dimensiones por encima del máximo → 413, **sin** decodificarla (se comprueba con una imagen preparada para ello).
5. Más de 5 MB de evidencia o 2 MB de logo o portada → 413.

**Vínculo (F3)**
6. Entregar con un `evidenceRef` que no tiene el formato `sha256:` → 400.
7. Entregar con la huella de una foto de otra organización, inexistente o borrada → el mismo 400 `EvidenceNotFound`.
8. Usar en un segundo activo una foto ya vinculada → 409 y el segundo activo no cambia (aserción negativa: ningún evento nuevo).
9. Reintentar la entrega del mismo activo con la misma foto y el mismo `Command-Id` → éxito, sin evento duplicado.
9b. Una entrega rechazada por permisos (otra organización, rol sin permiso) o por estado del activo (ya entregado con otros datos, o en un estado que no permite entregar) **no vincula** la foto: `boundAssetId` sigue nulo y `bindToAsset` no se invoca (aserción negativa con `never()`), y la misma foto se puede usar después en otro activo.
10. Dos entregas simultáneas de activos distintos con la misma foto → una gana y la otra recibe 409.
11. Los activos con eventos antiguos de `evidenceRef` en texto libre se siguen reconstruyendo y la verificación de su cadena no cambia.

**Visibilidad (F6, F7)**
12. El logo público (GET /api/v1/public/campaigns/{publicCode}/organization-logo) y la portada se leen sin JWT; la evidencia exige JWT de la organización del activo o administrador de plataforma, y cualquier otro recibe el 403 uniforme. La lectura autenticada del logo se prueba en 12c.
13. El seguimiento público nunca contiene la imagen ni una URL de la evidencia; muestra `evidenceRegistered: true` y `evidenceRef` solo si tiene formato `sha256:`; un `evidenceRef` antiguo de texto libre nunca aparece.
14. Tras la retirada por el administrador de plataforma, la evidencia da 410, los bytes ya no están en el proveedor y el metadato conserva motivo, autor y fecha.

**Almacenamiento (F1, F2, F10)**
15. Fallo del proveedor al guardar → un reintento; si vuelve a fallar → 503 y ningún metadato insertado.
16. Reemplazar un logo borra el archivo anterior solo después de confirmar el cambio; si el borrado falla, la respuesta sigue siendo correcta y queda registrado.
17. El adaptador S3 pasa un test de integración contra MinIO con Testcontainers (`GenericContainer`, sin módulo nuevo).
18. Fuera de `dev`, sin configuración de S3 o con `local`, la aplicación no arranca.
19. Ninguna credencial del proveedor aparece en logs.

**Común**
20. Los 19 criterios del golden path siguen en verde, con `scripts/ci-local.sh` completo.

## Implementación (tras la aprobación, una tarea y un plan por PR)

| Orden | Tarea | Módulos | Responsable sugerido |
|---|---|---|---|
| 1 | Puerto, adaptadores, limpieza con la JDK y `media_objects` | `contracts`, `app` | Manuel |
| 2 | Logo de la organización | `identity`, `app` | Manuel |
| 3 | Portada de la convocatoria (solo `ADMINISTRATOR`) | `convocatoria`, `app` | Carlos |
| 4 | Evidencia de entrega: subida, vínculo y validación del formato | `core`, `app`, `api` | Manuel con Adonis |
| 5 | Lectura de evidencia, retirada por plataforma y huella en el seguimiento (tras redactar la enmienda de ADR-021-D) | `app`, `api` | Manuel |

## Enmienda 1 — Ruta pública del logo sin `organizationId`

**APROBADA — Carlos, 2026-10-18T18:45Z.**

**Contexto.** F6 publicaba el logo en `GET /api/v1/public/organizations/{organizationId}/logo`. Eso exponía el `organizationId` en una ruta pública, y ninguna respuesta pública lo expone hoy, a propósito: el descubrimiento (P2.6) no lleva `campaignRef` ni `organizationRef`, y CV-07 solo da `organizationName`. Además, un cliente anónimo no tenía de dónde obtener ese identificador. Lo detectó el agente al preparar la ficha de búsqueda (R-1).

**Decisión.**
- La ruta pública pasa a ser `GET /api/v1/public/campaigns/{publicCode}/organization-logo`: devuelve el logo de la organización dueña de esa convocatoria, con las mismas reglas de visibilidad de la convocatoria (404 si la convocatoria no es visible por su código o si la organización no tiene logo).
- Las vistas públicas de convocatoria exponen `organizationLogoUrl?`, que apunta a esa ruta.
- `GET /api/v1/organizations/{organizationId}/logo` queda **autenticada** con JWT, para miembros de la organización y administradores de plataforma (el mismo 403 uniforme para el resto, DD-01).
- Las rutas de subida y borrado del logo (F8) no cambian.

**Consecuencias.** `PublicRoutes` registra la ruta nueva y no la anterior. Ningún identificador de organización aparece en rutas ni respuestas públicas.

**Tests añadidos a la definición de hecho.**
- 12b. `GET /api/v1/public/campaigns/{publicCode}/organization-logo` devuelve el logo sin JWT; ninguna respuesta pública (descubrimiento, CV-07, logo) contiene el `organizationId`.
- 12c. `GET /api/v1/organizations/{organizationId}/logo` sin JWT → 401; con JWT de otra organización → el 403 uniforme.

**Nota editorial (2026-10-21, Manuel):** el criterio 12 y la tabla "Documentos que cambian por este ADR" se alinean con esta enmienda. No cambia ninguna decisión. Visto bueno de Carlos en la revisión del PR que la incorpora.
