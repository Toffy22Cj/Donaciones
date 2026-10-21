# Plan — ADR-051, Tarea 1: puerto, adaptadores, limpieza con la JDK y `media_objects`

**Estado:** **APROBADO con cambios — 2026-10-20** (Manuel). Los cambios están en §9 y ya incorporados en el texto. El commit del paso 1 (ADR-051 + plan) espera la confirmación de cuál es la copia canónica de ADR-051 (§0).
**Rama:** `feat/app-adr051-media-storage`, creada desde `develop` = `origin/develop` = `4bef7be` (confirmado con `git log -1 origin/develop` antes de crearla; sin cambios respecto al hash indicado).
**Norma:** `ADR-051-fotos-y-almacenamiento.md`, APROBADO — Carlos, 2026-10-18, con la Enmienda 1. Entra en este mismo PR.
**Módulos:** `contracts`, `app` (ADR-051 §"Implementación", fila 1).

---

## 0. Procedencia del ADR (aviso)

En `~/Escritorio/` había **dos copias** de ADR-051:

| Fichero | Contenido |
|---|---|
| `ADR-051-fotos-y-almacenamiento.md` (26 kB) | APROBADO **con la Enmienda 1** (18:45Z): ruta pública del logo por `publicCode`, sin `organizationId` |
| `ADR-051-fotos-y-almacenamiento (1).md` (24 kB) | APROBADO **sin** la Enmienda 1 (versión anterior) |

`diff` entre las dos: solo la línea de estado, las dos filas del logo en F6, la viñeta de las vistas públicas en F6 y la sección "Enmienda 1". **Nada de eso toca la Tarea 1.** Se copió al repositorio la versión **con** la Enmienda 1, que es la más reciente y la que contiene a la otra. Si la canónica fuera la otra, hay que decirlo antes del commit.

---

## 1. Alcance

**Dentro** (ADR-051 F1, F2, F4, F8 límites, F9; "Implementación" fila 1):
- `MediaStoragePort` y `MediaStorageUnavailableException` en `contracts`.
- `S3MediaStorageAdapter`, `LocalMediaStorageAdapter` (`dev`) y el adaptador en memoria (tests) en `app`.
- Selección `traceability.media.storage` con *fail-fast*.
- Limpieza y huella con `javax.imageio`.
- Colección `media_objects` con sus índices y el orden de escritura de F2, en un servicio de `app` que reciben las tareas 2 a 4.

**Fuera:** logo (Tarea 2), portada (Tarea 3), evidencia, `DeliveryEvidencePort` y vínculo (Tareas 4 y 5), cualquier ruta HTTP, `PublicRoutes`, `PlatformCommandType`, traducción de excepciones a HTTP (`ApiErrorMappings`), F7 (retirada) y F10 (reemplazo).

**Qué no se puede probar sin otra tarea** (se dice, no se añade):
- Los criterios 3, 4, 5 y 15 hablan de **códigos HTTP** (415, 413, 503). Sin ruta, la Tarea 1 prueba la **excepción nombrada** que los produce. La traducción a 413/415/503 y su test por HTTP llegan con la primera ruta que la use (Tarea 2). Ver P-2.
- El 2 se formula a nivel de servicio, como pide el encargo: la huella devuelta es la de los bytes guardados. Que esa huella sea el `evidenceRef` con prefijo `sha256:` (F3) es de la Tarea 4.

---

## 2. Contradicciones y tensiones encontradas

Ninguna obliga a parar: en todos los casos el texto de ADR-051 se puede cumplir tal cual. Se listan para que se vean antes de aprobar.

| # | Dónde | Qué pasa | Cómo lo resuelve el plan |
|---|---|---|---|
| **T-1** | Encargo §3 ("Tamaño: 5 MB / 2 MB → 413" dentro de la limpieza) frente a ADR-051 **F8** ("límites de Spring: `max-file-size=5MB`…; **el límite de 2 MB lo comprueba el controlador** según el propósito") | F8 sitúa la comprobación de 2 MB en el controlador; el encargo la pone en la limpieza. F4 sí incluye el tamaño en sus errores ("tamaño o dimensiones excesivas → 413 `ImageTooLarge`"). | El servicio de la Tarea 1 comprueba el límite **por propósito** y lanza `ImageTooLargeException` (F4, errores nombrados; F8, valores). No sustituye al controlador ni a la propiedad de Spring de F8, que llegan con las rutas. **Pregunta P-1:** ¿el controlador de las tareas 2 a 4 repite la comprobación o confía en la del servicio? |
| **T-2** | Encargo §2 ("`LocalMediaStorageAdapter`, solo `dev`") frente a F1 ("`dev` y `scripts/ci-local.sh`: adaptador local") | Parecen distintos, pero no lo son: `ci-local.sh` ejecuta `mvn clean test` y el único test de `app` con perfil `dev` (`DemoSeedRunnerIntegrationTest`) arrancará con el adaptador local. El resto de tests de `app` no usa ningún perfil, así que no es `dev`. | El adaptador local solo existe con el perfil `dev` (F1, F9). Los tests sin perfil usan valores S3 ficticios que no se conectan a nada (§4.4) o el adaptador en memoria. |
| **T-3** | Regla 2.6 ("toda condición de fallo tiene su propia excepción nombrada") frente a ADR-047 D3 ("*fail-fast* … → `IllegalStateException`") | El patrón de *fail-fast* que pide seguir el encargo usa una excepción genérica. | Se sigue ADR-047 D3 y el código existente (`JwtSecurityProperties`, `SmtpInvitationMailAdapter`, `DiscoveryCursorConfig`): `IllegalStateException` con el nombre de la variable en el mensaje y nunca su valor. Es un fallo de arranque, no de una operación. |
| **T-4** | `documento-maestro-proyecto.md` §5: "Siguiente número libre: ADR-050" | ADR-050 y ADR-051 no están en el catálogo, y la regla dice que el número se reserva **antes** de usarse. ADR-051 cita además ADR-050 D10, que no está en el repositorio. | No afecta al código. **Pregunta P-6:** ¿añado ADR-051 al catálogo en este PR? |

---

## 3. Patrones que se siguen (ADR-047 D3, ADR-049 D1)

| Patrón | Precedente | Aplicación aquí |
|---|---|---|
| Dependencia nueva **solo en `app`** | ADR-049 D1 (`spring-boot-starter-mail` en `app/pom.xml`; el módulo de dominio define el puerto) | SDK de S3 solo en `app/pom.xml`. El puerto vive en `contracts` porque así lo fija F9 (lo usarán `identity`, `convocatoria` y `core`). |
| Versión a mano en el `dependencyManagement` del padre | ADR-047 D1 (`nimbus-jose-jwt.version`) | Propiedad `awssdk.version` y dos entradas: `s3` y `url-connection-client` (F1). |
| Secreto por entorno, **sin valor por defecto** en `application.yml`, *fail-fast* si falta, vacío o sin resolver (`${…`) | ADR-047 D3; `JwtSecurityProperties.isUnresolved`; ADR-049 D1 `host: ${SPRING_MAIL_HOST}` | `${MEDIA_S3_…}` sin `:` en `application.yml`; `IllegalStateException` al arrancar con el nombre de la variable (T-3). |
| Valor de prueba solo en `dev` o en el `application.yml` de tests, marcado como no productivo | ADR-047 D3; `app/src/test/resources/application.yml` (`test-only-jwt-…`, SMTP `localhost:1`) | Valores ficticios de S3 en el YAML de tests, que apuntan a `http://localhost:1`, como el SMTP de los tests. Nada de S3 en `application-dev.yml`. |
| Nunca en logs ni en mensajes | ADR-047 D7; ADR-049 D1 | §4.5 y criterio 19. |
| Test de *fail-fast* con `ApplicationContextRunner` y lectura literal del `application.yml` principal | `DiscoveryCursorKeyConfigTest` | Criterio 18 (§6). |

---

## 4. Decisiones del plan, con su fuente en ADR-051

### 4.1 Puerto en `contracts` (F9)

- Paquete **`com.traceability.contracts.media`**. F9 no fija el paquete; sigue la convención de `contracts` (un subpaquete por tema: `authentication`, `campaign`, `organization`…).
- `MediaStoragePort`, con los tres métodos literales de F9:
  - `void put(String objectKey, byte[] content, String contentType)`;
  - `Optional<byte[]> get(String objectKey)`;
  - `void delete(String objectKey)`.
- `MediaStorageUnavailableException extends RuntimeException` (F2, F9). Constructor `(String message)`, **sin causa encadenada**: así ningún registro de causas puede arrastrar un mensaje del proveedor (§4.5).
- Javadoc del contrato, que cumplen los tres adaptadores:
  - `put` es idempotente para la misma clave y los mismos bytes (F2);
  - `get` de una clave inexistente → `Optional.empty()`;
  - `delete` de una clave inexistente no falla.
- Solo tipos de Java: `contracts/pom.xml` no cambia, y lo vigila ArchUnit (§6, ArchUnit).

### 4.2 Dependencia (F1)

- `pom.xml` padre: `<awssdk.version>` con la **última 2.x estable** el día de la implementación, como hizo ADR-047 D1 con Nimbus. ~~Elegida: 2.57.1, la `<release>` de `maven-metadata.xml`, publicada el 2026-10-20~~ (texto original, conservado). **Fijada: 2.55.10**, publicada en Maven Central el **2026-10-01** (cabecera `last-modified` del `.pom`). Se cambió el 2026-10-20 a petición de Manuel, para usar una versión con al menos una o dos semanas de antigüedad. Con 2.55.10 el `s3` sin exclusiones trae también `apache5-client` y `netty-nio-client` (`runtime`); con las exclusiones, el único cliente es `url-connection-client`.
- Dos entradas en `dependencyManagement`: `software.amazon.awssdk:s3` y `software.amazon.awssdk:url-connection-client`, ambas con `${awssdk.version}`.
- `app/pom.xml`:
  - `s3`, excluyendo `software.amazon.awssdk:netty-nio-client` (F1);
  - `url-connection-client`.
- **Verificación hecha hoy (2026-10-20):**
  - `spring-boot-dependencies-3.4.4.pom` tiene **0** apariciones de `awssdk`: no gestiona ni `s3` ni `url-connection-client`. Gestiona `io.netty`, pero eso deja de importar al excluir `netty-nio-client`.
  - `spring-ai-bom-1.0.0-M6.pom` tiene **0**.
  - El árbol de `app` en `4bef7be` (`mvn dependency:tree -pl app`) no contiene `awssdk` ni `netty`.
  - El PR repite estas comprobaciones con la versión elegida y pega la salida.
- **P-3 (decidido: sí):** `s3` también trae `apache-client` en tiempo de ejecución, y **se excluye**: el único cliente HTTP es el de la JDK (F1).
  - **Hallazgo al ejecutar (2026-10-20):** en 2.57.1 el cliente síncrono que `s3` trae por defecto ya no es `apache-client` sino **`apache5-client`** (alcance `runtime`). Para cumplir P-3 se excluye también: sin esa exclusión, el cliente Apache seguía en el *classpath*. Árbol de `app` tras las exclusiones: el único cliente HTTP es `url-connection-client`; ni `netty` ni `httpcomponents`.

### 4.3 Adaptadores (F1, F2, F9)

Paquete `com.traceability.app.infrastructure.media`, junto a `infrastructure.mail` (ADR-049) e `infrastructure.identity`.

**`S3MediaStorageAdapter`** (F1):
- Recibe un `S3Client` y el *bucket*. El cliente se construye en la configuración (§4.4):
  - `endpointOverride(MEDIA_S3_ENDPOINT)` y `region(MEDIA_S3_REGION)` (en R2, `auto`, F1);
  - `forcePathStyle(true)`, que admiten MinIO y R2;
  - `StaticCredentialsProvider`, **nunca** la cadena por defecto del SDK, para que no lea en silencio `AWS_*` ni `~/.aws`;
  - `httpClientBuilder(UrlConnectionHttpClient.builder())` explícito;
  - **reintentos del SDK desactivados** (`retryStrategy` sin reintentos). El único reintento es el de F2; si no, los intentos se multiplicarían;
  - `requestChecksumCalculation` y `responseChecksumValidation` en `WHEN_REQUIRED` (riesgo R-2).
- *Bucket* privado (F1, F6): el adaptador nunca fija ACL ni genera URL. El test de MinIO comprueba que un GET anónimo da 403.
- Métodos:
  - `put` → `PutObject` con `contentType`, y el reintento de F2 (abajo);
  - `get` → `GetObject`; `NoSuchKeyException` → `Optional.empty()`;
  - `delete` → `DeleteObject`, idempotente en S3;
  - en `get` y `delete`, cualquier otro fallo → `MediaStorageUnavailableException` **sin reintento**. F2 solo reintenta "al guardar".

**Reintento de `put` (F2, ya decidido): un reintento con la misma clave y los mismos bytes, solo ante un fallo ambiguo.** Clasificación con los tipos del SDK v2:

| Clase | Excepción del SDK | ¿Reintento? |
|---|---|---|
| Tiempo agotado | `ApiCallAttemptTimeoutException`, `ApiCallTimeoutException`; `SdkClientException` cuya causa es `SocketTimeoutException` | **Sí, uno** |
| E/S cortada con la petición en curso (resultado desconocido) | `SdkClientException` con causa `IOException` (conexión reiniciada, fin de flujo inesperado), salvo las de la fila "configuración / red" | **Sí, uno** |
| El proveedor no sabe si guardó | `S3Exception` con estado **408** o **5xx** (500, 502, 503 `SlowDown`, 504) | **Sí, uno** |
| Credenciales | `S3Exception` 403 (`InvalidAccessKeyId`, `SignatureDoesNotMatch`, `AccessDenied`, `RequestTimeTooSkewed`) | **No** (determinista) |
| *Bucket* o petición | `NoSuchBucketException` / 404; 400, 409, 411, 413 (`EntityTooLarge`) y cualquier otro 4xx | **No** (determinista) |
| Configuración / red | `SdkClientException` con causa `UnknownHostException`, `ConnectException` (rechazada) o `SSLHandshakeException`; o sin causa de E/S (endpoint inválido, credenciales que no cargan) | **No** (determinista: no llegó nada al proveedor) |

- Si el segundo intento falla, o el primero falla de forma determinista → `MediaStorageUnavailableException`. Es la única excepción que permite F9.
- La rama ambigua se registra en WARN y la determinista en ERROR (es configuración). Ver §4.5.
- Tiempos, como **valores de implementación** con propiedad y valor por defecto (no son secretos): conexión 3 s, *socket* 10 s y tiempo por intento 10 s. Peor caso de una subida: unos 20 s. **P-4 (decidido):** con esos valores por defecto.

**`LocalMediaStorageAdapter`** (F1: "`dev`: adaptador local (directorio temporal)"):
- Solo se crea con el perfil `dev` (§4.4).
- Directorio `traceability.media.local.directory`. En `application-dev.yml`: `${TRACEABILITY_MEDIA_LOCAL_DIRECTORY:${java.io.tmpdir}/paxfide-media}`. Es estable entre reinicios, para que la base de la demo no apunte a ficheros perdidos.
- `put` escribe en un temporal del mismo directorio y hace un `ATOMIC_MOVE` (idempotente). `get` y `delete` toleran que el fichero no exista.
- `IOException` → `MediaStorageUnavailableException`, sin reintento (fallo de disco local).
- El nombre del fichero es la clave. La clave se valida como ULID y la ruta resuelta debe quedar dentro del directorio, para evitar el recorrido de rutas. **P-5 (decidido):** una clave inválida lanza `IllegalArgumentException`.

**Adaptador en memoria** (F1, F9: "uno en memoria para tests"):
- `InMemoryMediaStorageAdapter`, en **`app/src/test`**: no es un bean de producción.
- Un `ConcurrentHashMap` con copias defensivas de los bytes.
- Permite forzar `MediaStorageUnavailableException` en el siguiente `put`, para los tests del servicio.

### 4.4 Selección y *fail-fast* (F1)

**Mecanismo de Spring:**
- `MediaStorageProperties`: un *record* `@ConfigurationProperties("traceability.media")` con `storage`, `s3.*` y `local.directory`. El `toString` se sobrescribe para no imprimir los valores de S3.
- `MediaStorageConfig` (`@Configuration`): un único `@Bean MediaStoragePort` que recibe las propiedades y el `Environment`, y decide en este orden:
  1. `storage` ausente o distinto de `s3`/`local` → `IllegalStateException`.
  2. `storage = local` y `!environment.acceptsProfiles(Profiles.of("dev"))` → `IllegalStateException`: "local solo se admite con el perfil dev (ADR-051 F1)".
  3. `storage = s3` y cualquiera de los cinco valores **ausente, vacío o sin resolver** (`${…`) → `IllegalStateException` con el **nombre** de la variable que falta (`MEDIA_S3_BUCKET`…), nunca su valor.
  4. Si todo es válido, construye el cliente y el adaptador **sin** llamar a la red. Una comprobación del *bucket* al arrancar haría que todo test de `app` necesitara MinIO; F1 solo exige que no falte ningún valor.
- **Por qué no `@Profile` ni `@ConditionalOnProperty`:** con una condición falsa, Spring no crea el bean y la aplicación falla después con un "No qualifying bean" genérico, o arranca si nadie lo inyecta todavía (hoy nadie lo hace). Un único `@Bean` que valida garantiza que no arranca y explica por qué.

**Ficheros versionados:**

| Fichero | Contenido |
|---|---|
| `app/src/main/resources/application.yml` | `traceability.media.storage: s3`, y `traceability.media.s3.{endpoint, region, bucket, access-key-id, secret-access-key}: ${MEDIA_S3_ENDPOINT}` … `${MEDIA_S3_SECRET_ACCESS_KEY}`, **sin `:`**, como `jwt.signing-secret` |
| `app/src/main/resources/application-dev.yml` | `traceability.media.storage: ${TRACEABILITY_MEDIA_STORAGE:local}` y el directorio local. Ningún valor `MEDIA_S3_*` |
| `app/src/test/resources/application.yml` | `storage: s3` con valores **ficticios** (`endpoint: http://localhost:1`, `access-key-id: test-only-media-access-key-id`, `secret-access-key: test-only-media-secret-not-for-production`) y el mismo comentario que el SMTP y el JWT de tests (ADR-047 D3). Sin ellos, ningún `@SpringBootTest` de `app` arrancaría |

**Cómo se prueba:** criterio 18 (§6).

### 4.5 Nunca credenciales en logs (F1; criterio 19)

- Nada registra las propiedades de S3. Los mensajes de *fail-fast* nombran la variable, nunca el valor. `MediaStorageProperties.toString` las oculta.
- El adaptador registra solo `objectKey`, la clase de la excepción, el estado HTTP y `awsErrorDetails().errorCode()`; **nunca** `getMessage()` del SDK.
- `MediaStorageUnavailableException` lleva un mensaje fijo y ninguna causa (§4.1).
- **No se cambia el nivel de log del SDK.** El criterio 19 lo prueba con `software.amazon.awssdk` en DEBUG (R-3). Si así apareciera el `access-key-id` (va dentro de la cabecera `Authorization`), se fija `logging.level.software.amazon.awssdk: WARN` y se informa antes de seguir.

### 4.6 Limpieza con la JDK (F4) y límites (F4, F8)

Clase `ImageSanitizer` (`app.application.media`), sin dependencias nuevas, solo `javax.imageio`. Orden exacto:

1. **Tamaño** (F4 errores; valores de F8): si `content.length` supera el límite del propósito → `ImageTooLargeException`. Los límites son 5 MiB para `DELIVERY_EVIDENCE` y 2 MiB para `ORGANIZATION_LOGO` y `CAMPAIGN_COVER`, con la unidad binaria de `DataSize` de Spring (`5MB` = 5 × 1024 × 1024), la misma que F8 fija para el *multipart*. Viven en el enum `MediaPurpose` (T-1).
2. **Formato por bytes mágicos** (F4.1): `FF D8 FF` → JPEG; `89 50 4E 47 0D 0A 1A 0A` → PNG; cualquier otra cosa, o menos bytes → `UnsupportedImageFormatException`. El servicio **no recibe** nombre de fichero ni `Content-Type`, así que no pueden contar. El lector se elige con `ImageIO.getImageReadersByFormatName("jpeg"|"png")`, **nunca** por autodetección, para que un GIF o un BMP no entren por otro *plugin*.
3. **Dimensiones desde la cabecera, antes de decodificar** (F4.2):
   - `reader.setInput(MemoryCacheImageInputStream, seekForwardOnly=true, ignoreMetadata=true)` y después `getWidth(0)`/`getHeight(0)`, que leen solo SOF (JPEG) o IHDR (PNG);
   - si un lado pasa de 8000 o `ancho × alto` pasa de 40 000 000 → `ImageTooLargeException`;
   - los bordes (8000 y 40 MP exactos) se admiten.
4. **Decodificar** `reader.read(0)`. Un `IOException`/`IIOException` (aritmético, truncado, sin SOS) o un resultado nulo → `UnsupportedImageFormatException` (F4: "imagen que la JDK no decodifica").
   - **Aclaración del mecanismo de F4 (Manuel, 2026-10-20; no cambia el ADR):** la JDK 21.0.12 **sí decodifica** los JPEG CMYK y YCCK (`ColorSpace.TYPE_CMYK`), así que el ejemplo de F4 no se cumple solo por fallar al decodificar. Regla: si el `ColorSpace` de la imagen decodificada **no es `TYPE_RGB` ni `TYPE_GRAY`** → `UnsupportedImageFormatException`. Se decide **por espacio de color, no por número de componentes**: un PNG RGBA tiene cuatro componentes y se admite. Comprobado en esta JDK: JPEG CMYK, CMYK con Adobe (transform 0) y YCCK (transform 2) dan `TYPE_CMYK`; PNG RGBA y PNG con paleta dan `TYPE_RGB`; PNG gris y gris con alfa dan `TYPE_GRAY`.
5. **Volver a codificar en el mismo formato sin metadatos** (F4.3): `IIOImage(imagen, null, null)`, sin metadatos de entrada. JPEG con `MODE_EXPLICIT` y calidad `0.9f`; PNG con los parámetros por defecto. Se descartan EXIF (con el GPS), XMP y comentarios, y también el perfil de color y la orientación (consecuencia aceptada en F4 y Q1).
6. **SHA-256 de los bytes resultantes** (F4.4), en 64 hexadecimales en minúscula.

Notas:
- Los bytes de una foto (posibles datos personales) nunca pasan por ficheros temporales del disco:
  - la limpieza usa siempre flujos `MemoryCacheImageInputStream`/`MemoryCacheImageOutputStream`;
  - además, `ImageIO.setUseCache(false)` se llama **una sola vez**, en `MediaStorageConfig`, al crear el bean `ImageSanitizer`, con un comentario que dice que es un ajuste **global de la JVM**. No se llama en cada limpieza (cambio de la aprobación, §9).
- `SanitizedImage` (record): bytes limpios, `contentType` (`image/jpeg` o `image/png`, deducido del formato detectado), `width`, `height` y `sha256`.
- En `media_objects.sha256` se guarda el hexadecimal. El formato `sha256:<hex>` es el `evidenceRef` de F3 y lo arma la Tarea 4.

### 4.7 `media_objects` y orden de escritura (F2, F9)

**Documento** `MediaObjectDocument` (`@Document("media_objects")`, en `app.infrastructure.media`), con **los campos exactos de la tabla de F9**: `objectKey`, `sha256`, `purpose`, `organizationId`, `ownerRef`, `contentType`, `sizeBytes`, `width`, `height`, `uploadedBy`, `uploadedAt`, `boundAssetId`, `deletedAt`, `deletedBy` y `reason`. Más el `_id` que pone Mongo.

**Índices**, declarados con anotaciones. `MongoIndexInitializer` (H-IDX-1) los crea al arrancar sin cambios, porque recorre todas las clases `@Document`:
- `uq_object_key`: `@Indexed(unique = true)` en `objectKey` (F9).
- `uq_evidence_sha256`: `@CompoundIndex(def = "{'organizationId': 1, 'purpose': 1, 'sha256': 1}", unique = true, partialFilter = "{'purpose': 'DELIVERY_EVIDENCE'}")`.
  - **Por qué parcial:** F9 pide la unicidad "para la evidencia". Un único sin filtro impediría que una organización usara la misma imagen como logo y como portada, o que volviera a subir un logo idéntico tras reemplazarlo (F10). El filtro de igualdad sobre `purpose` lo admite MongoDB 6 y tiene precedente (`campaign_assignments.uq_active_employee_assignment`, `configuration_change_requests`).
  - Alternativas descartadas: una clave compuesta artificial o una comprobación solo en código, que no aguanta dos subidas simultáneas.

**Repositorio:** `MediaObjectRepository`, un `MongoRepository` de Spring Data con `insert` y `findByOrganizationIdAndPurposeAndSha256`. Mismo patrón que los documentos de `identity` con repositorio.

**Servicio** (`@Service`; recibe el `ImageSanitizer` como bean de `MediaStorageConfig`) `MediaUploadService.store(MediaPurpose purpose, String organizationId, String ownerRef, String uploadedBy, byte[] content)` → `StoredMedia(objectKey, sha256, contentType, sizeBytes, width, height)`. Orden de F2:

1. `ImageSanitizer` valida y limpia en memoria. Si falla, ni `put` ni `insert`.
2. **Solo para `DELIVERY_EVIDENCE`:** se busca `(organizationId, DELIVERY_EVIDENCE, sha256)`. Si existe, se devuelve su `StoredMedia` sin `put` ni `insert`. Es lo que pide F9: "subir dos veces los mismos bytes devuelve el mismo `evidenceRef` sin duplicar". Evita un huérfano en el caso habitual, y es de mejor esfuerzo, como dice F4 (re-codificar no es determinista entre JDK).
3. `put(objectKey = UlidCreator.getUlid().toString(), bytesLimpios, contentType)`. La clave es aleatoria y no lleva datos de la organización (F2.2). **P-7 (decidido):** `ulid-creator` se declara en `app/pom.xml` con la misma versión que `identity` (5.2.3).
4. `insert` del metadato con `uploadedAt = clock.instant()` (`Clock` inyectable) y `boundAssetId`, `deletedAt`, `deletedBy` y `reason` nulos.

**Fallos:**
- **`put` falla** (`MediaStorageUnavailableException` tras el reintento del adaptador): se propaga y **no se inserta nada** (F2; criterio 15).
- **`insert` falla:** queda un huérfano (F2: "no causa daño; se registra con su clave").
  - **Dónde:** el *logger* de `MediaUploadService`, en WARN.
  - **Cómo:** una línea `clave=valor`, como los demás logs del proyecto: `media.orphan objectKey=<ULID> purpose=<…> cause=<clase de la excepción>`. Sin mensaje de la excepción, sin `organizationId`, sin `uploadedBy` y sin nada de S3.
  - Después se propaga la `DataAccessException` original, que es específica, y la Tarea 2 la traducirá a 500 con `correlationId` (B6-0).
- **Carrera de dos evidencias idénticas** (`DuplicateKeyException` sobre `uq_evidence_sha256`): se relee el metadato ganador y se devuelve su `StoredMedia`; el objeto recién guardado queda huérfano y se registra con la misma línea (F2). No se borra: F2 dice que se registra, no que se borre. **P-8 (decidido):** solo se registra el huérfano.

### 4.8 Clases nuevas que ADR-051 no nombra (regla 2.1)

| Clase | Módulo y paquete | Justificación en ADR-051 |
|---|---|---|
| `MediaPurpose` (enum) | `app.application.media` | F9, columna `purpose` (tres valores literales); F8, límite por propósito |
| `ImageSanitizer` | `app.application.media` | F4, pasos 1 a 4 |
| `SanitizedImage` (record) | `app.application.media` | F4: salida de la limpieza (bytes limpios + huella + dimensiones de F9) |
| `MediaUploadService` | `app.application.media` | F2, orden de escritura; F9, metadatos propiedad de `app` |
| `StoredMedia` (record) | `app.application.media` | F4: "la subida devuelve el `evidenceRef`"; lo que reciben las tareas 2 a 4 sin ver el documento de Mongo |
| `MediaObjectDocument` | `app.infrastructure.media` | F9, tabla `media_objects` |
| `MediaObjectRepository` | `app.infrastructure.media` | F9, tabla e índices |
| `MediaStorageProperties` | `app.infrastructure.media` | F1, selección y cinco variables |
| `MediaStorageConfig` | `app.infrastructure.media` | F1, *fail-fast* |
| `ImageTooLargeException`, `UnsupportedImageFormatException` | `app.application.media` | F4, "errores nombrados" (`ImageTooLarge`, `UnsupportedImageFormat`) |
| `MediaObjectStore` (puerto de salida) | `app.application.media` | **Iteración DT-2 (§11), aprobada por Manuel el 2026-10-21.** F9: los metadatos son propiedad del adaptador de `app`; el servicio los usa a través de un puerto, con el patrón `application.port.out` de los demás módulos. Solo lo que usa `MediaUploadService`: insertar y buscar por `(organizationId, purpose, sha256)` |
| `MediaObject` (record) | `app.application.media` | Iteración DT-2: el tipo de la capa de aplicación con el que trabaja el puerto, para que el servicio no conozca `MediaObjectDocument` (F9) |
| `DuplicateEvidenceException` | `app.application.media` | Iteración DT-2: la excepción nombrada (regla 2.6) con la que el adaptador traduce el choque de `uq_evidence_sha256` (F9, "sin duplicar") |
| `MongoMediaObjectStore` | `app.infrastructure.media` | Iteración DT-2: implementa `MediaObjectStore` con `MediaObjectRepository` (F9) |

Nombradas en ADR-051: `MediaStoragePort`, `MediaStorageUnavailableException`, `S3MediaStorageAdapter`, `LocalMediaStorageAdapter` y el adaptador en memoria (`InMemoryMediaStorageAdapter`, solo en tests).

---

## 5. Tests — rojos primero

Las imágenes de prueba se generan en el código con la JDK (`TestImages`, en `app/src/test`): sin binarios en el repositorio.
- EXIF con GPS, XMP y COM se insertan a mano tras el `SOI` de un JPEG generado.
- El JPEG CMYK se escribe como `Raster` de 4 bandas con el escritor JPEG de la JDK.
- Las "bombas" son un IHDR o un SOF con dimensiones enormes y datos de imagen corruptos.

| Crit. | Qué exige ADR-051 | Clase | Método(s) | Nivel |
|---|---|---|---|---|
| **1** | JPEG con GPS en el EXIF → guardado sin EXIF ni XMP, **leyendo los bytes guardados** | `MediaUploadServiceTest` | `jpegWithGpsExifXmpAndComment_storedBytesHaveNoApp1NorComment` (recorre los segmentos de lo que devuelve `InMemoryMediaStorageAdapter.get`: ningún `FFE1` ni `FFFE`); `pngWithExifAndTextChunks_storedBytesHaveNoMetadataChunks` (ni `eXIf`, `tEXt`, `iTXt` ni `zTXt`) | servicio + adaptador en memoria |
| **2** | La huella es la de los bytes guardados, no la de los subidos | `MediaUploadServiceTest` | `returnedSha256_isTheDigestOfTheStoredBytes_notOfTheUploadedOnes` (`sha256 == SHA-256(get(key))` y `!= SHA-256(entrada)`) | servicio |
| **3** | `.jpg` sin bytes de JPEG ni PNG → 415 | `ImageSanitizerTest` | `withoutJpegOrPngMagicBytes_isUnsupported` (parametrizado: GIF, BMP, texto, vacío, `FF D8` solo); `cmykJpeg_thatTheJdkCannotDecode_isUnsupported` | unidad |
| | | `MediaUploadServiceTest` | `unsupportedFormat_neverPutsNorInserts` (`never()` en los dos) | servicio |
| **4** | Pequeña en bytes y con dimensiones por encima del máximo → 413 **sin decodificar** | `ImageSanitizerTest` | `hugeDeclaredDimensions_areRejectedFromTheHeader_beforeDecoding` (**PNG** 8001 × 10 y 10 × 8001 con datos insuficientes: decodificar daría `Unsupported`, así que `ImageTooLarge` prueba que se rechazó antes; el control `control_thePatchedPngWithinLimits_cannotBeDecoded` lo demuestra); `jpegWithHugeDeclaredDimensions_isRejectedByItsHeader` (JPEG 8001 × 10, **sin** afirmar "sin decodificar", ver la nota de abajo); `over40Megapixels_withBothSidesUnder8000_isTooLarge` (7000 × 7000, cabecera); `dimensionLimits_boundaries` (8000 × 5000 se admite; 8001 × 1 y 8000 × 5001 no) | unidad |
| **5** | > 5 MB evidencia, > 2 MB logo o portada → 413 | `MediaUploadServiceTest` | `overThePurposeSizeLimit_isTooLarge_withoutPutNorInsert` (parametrizado: los tres propósitos con límite + 1 byte); `exactlyAtTheLimit_passesTheSizeCheck` (con bytes que no son imagen: da `Unsupported`, así que pasó el tamaño) | servicio |
| **15** | Fallo al guardar → un reintento; si vuelve a fallar → 503 y **ningún metadato** | `S3MediaStorageAdapterTest` (`S3Client` simulado) | `put_timeoutThenSuccess_retriesOnce_withTheSameKeyAndBytes` (captura las dos `PutObjectRequest` y los dos cuerpos); `put_twoAmbiguousFailures_throwsUnavailable_afterExactlyTwoAttempts` (parametrizado: tiempo agotado, `IOException`, 408 y 5xx); `put_deterministicFailure_isNotRetried` (403, `NoSuchBucket`, 400, `UnknownHost` y `ConnectException`: `times(1)`); `get_noSuchKey_isEmpty`; `get_andDelete_failures_areNotRetried` | unidad del adaptador |
| | | `MediaUploadServiceTest` | `storageUnavailable_propagates_andNoMetadataIsInserted` (`verify(repository, never()).insert(any())`) | servicio |
| | | `MediaObjectPersistenceIntegrationTest` | `storageUnavailable_leavesMediaObjectsEmpty` (`count == 0` en Mongo real) | integración |
| **17** | Adaptador S3 contra MinIO con Testcontainers (`GenericContainer`, sin módulo nuevo) | `S3MediaStorageAdapterMinioIntegrationTest` | `putGetDelete_roundTrip_keepsBytesAndContentType`; `get_missingKey_isEmpty`; `put_sameKeyTwice_isIdempotent`; `delete_missingKey_doesNotFail`; `bucketIsPrivate_anonymousGetIsForbidden`; `badCredentials_failAfterOneAttempt_withoutRetry` (cuenta intentos con un `ExecutionInterceptor`); `pausedProvider_timesOut_retriesOnce_thenUnavailable` (`docker pause` del contenedor) | integración (Docker) |
| **18** | Fuera de `dev`, sin S3 o con `local`, no arranca | `MediaStorageConfigTest` (`ApplicationContextRunner`) | `withoutDev_localStorage_doesNotStart`; `withoutDev_anyMissingS3Value_doesNotStart_namingTheVariable` (parametrizado: cinco variables × ausente, vacía y `${…}`); `unknownOrMissingStorage_doesNotStart`; `withDev_localStorage_starts_withTheLocalAdapter`; `completeS3_starts_withTheS3Adapter_withoutNetworkCalls`; `mainApplicationYml_hasNoDefaultForAnyMediaS3Variable` y `devYml_hasNoMediaS3Values` (lectura literal, como `DiscoveryCursorKeyConfigTest`) | contexto |
| **19** | Ninguna credencial del proveedor en logs | `S3MediaStorageAdapterMinioIntegrationTest` + `OutputCaptureExtension` | `noCredentialAppearsInLogs_onAnyProviderFailure` (credenciales malas, tiempo agotado y fallo de `get`, con `software.amazon.awssdk` en DEBUG; busca el *access key id* y el secreto) | integración |
| | | `MediaStorageConfigTest` | `failFastMessages_nameTheVariable_neverItsValue` (salida capturada y mensaje raíz) | contexto |

**Tests de soporte (no son criterios, pero el plan los necesita):**

| Qué | Clase | Método(s) |
|---|---|---|
| Orden de F2 | `MediaUploadServiceTest` | `writeOrder_isCleanThenPutThenInsert` (`InOrder`); `insertFailure_logsTheOrphanKey_withoutCredentialsNorOrganization` |
| Deduplicación de evidencia (F9) | `MediaUploadServiceTest` | `sameEvidenceBytesTwice_returnsTheSameRef_withoutASecondPut`; `concurrentDuplicateEvidence_returnsTheWinner_andLogsTheOrphan` |
| Índices (F9, H-IDX-1) | `MediaObjectPersistenceIntegrationTest` | `objectKey_isUnique`; `sameEvidenceBytes_inTheSameOrganization_collide`; `sameBytes_asLogoAndAsCover_orAsEvidenceOfAnotherOrganization_doNotCollide` |
| | `MongoIndexesAtStartupIntegrationTest` (se modifica) | añade `unique("media_objects", "uq_object_key", …)` y `uniquePartial("media_objects", "uq_evidence_sha256", …, {purpose: DELIVERY_EVIDENCE})` |
| Adaptador local | `LocalMediaStorageAdapterTest` | ida y vuelta, idempotencia, borrado de un fichero inexistente; clave no ULID o con `../` rechazada |
| ArchUnit (encargo §5) | `MediaArchitectureTest` (`app`, importa `com.traceability` e `identity`) | `awsSdk_onlyInAppInfrastructureMedia`; `contracts_dependsOnlyOnJava` (todo `com.traceability.contracts..` depende solo de `java..` y de sí mismo; comprobado hoy: ningún `import` fuera de eso); `nettyAndApacheHttpClients_areNotOnTheClasspath` (`Class.forName` falla para `NettyNioAsyncHttpClient`, `ApacheHttpClient` y `Apache5HttpClient`) |

**Criterio 4, caso JPEG (Manuel, 2026-10-20):** se pidió que el JPEG de prueba terminara justo después del SOF, para que decodificarlo fallara. **No se puede:** con el fichero cortado tras el SOF, la JDK no lee ni la cabecera (`IIOException: Invalid JPEG file structure: missing SOS marker`), así que ya no es el caso de "dimensiones excesivas". Cortado justo tras el SOS, la JDK lo decodifica con tolerancia (rellena lo que falta) y no da error. Por eso el caso JPEG **solo prueba que el límite se lee de su cabecera**, y la prueba de "sin decodificar" queda en el PNG. La mutación 3 (comprobar las dimensiones después de decodificar) tiene que morir por el caso PNG; se confirma en la fase de mutaciones.

**MinIO:**
- Imagen con versión fijada, sin `latest`. Propuesta: `minio/minio:RELEASE.2024-10-13T13-34-11Z`, que se confirma con `docker pull` antes del primer rojo (hoy no está en la caché local). Ver R-4.
  - **Hallazgo al ejecutar (2026-10-20), R-4 materializado:** `minio/minio` ya no existe en Docker Hub (la API devuelve 404 y `docker pull` da "repository does not exist"), y `quay.io/minio/minio` responde 401 sin autenticación. Se usa **`bitnamilegacy/minio:2025.7.23-debian-12-r5`**: es MinIO (`minio version DEVELOPMENT.2025-07-23T15-54-02Z`), empaquetado por Bitnami en su catálogo heredado, que ya no se actualiza. Arranca con su propio binario (`entrypoint minio`, `server /tmp/data`), sin los scripts de Bitnami. Sigue siendo "MinIO con Testcontainers, `GenericContainer`, sin módulo nuevo" (definición de hecho 17), pero la procedencia de la imagen cambia: **pregunta N-2**.
  - **N-2 aceptada (Manuel, 2026-10-20), fijada por digest y no por etiqueta:** `bitnamilegacy/minio@sha256:6dabb4a2088c9a79908de3bc05f4586c23ad2182c8908e7e3acbf61c1467fb20`. Procedencia: Docker Hub, organización `bitnamilegacy` (catálogo heredado de Bitnami, sin actualizaciones). Corresponde a la etiqueta `2025.7.23-debian-12-r5` y contiene `minio version DEVELOPMENT.2025-07-23T15-54-02Z (commit-id=7ced9663e6a791fef9dc6be798ff24cda9c730ac)`. Digest leído con `docker image inspect --format '{{json .RepoDigests}}'` el 2026-10-20.
- El *bucket* lo crea el test con el propio `S3Client`.
- Mismo patrón que `InvitationMailpitIntegrationTest`: `@Testcontainers`, `GenericContainer` y `Wait.forHttp("/minio/health/ready")`.
- Sin `@SpringBootTest`: el adaptador se construye con `MediaStorageConfig` sobre un `ApplicationContextRunner`, para que el test sea rápido y no necesite Mongo.

**Fase roja:** primero los tests, con las clases vacías (o lanzando `UnsupportedOperationException`) para que compilen. Se pega la salida roja de `mvn test -pl app` con el motivo de cada fallo. La fase roja se guarda con `git stash` o en un commit propio, nunca con `tar` (encargo 3, punto 7).

**Mutaciones** (`scripts/mutaciones.py`; cada una debe morir):

| # | Mutación | Test que la mata |
|---|---|---|
| 1 | Quitar el reintento de `put` | 15 |
| 2 | Reintentar también los 403 | 15 |
| 3 | Comprobar las dimensiones después de decodificar | 4 |
| 4 | Hashear la entrada en vez de la salida | 2 |
| 5 | Copiar los metadatos de entrada al escribir | 1 |
| 6 | `insert` antes de `put` | orden |
| 7 | Quitar `partialFilter` | índices |
| 8 | Admitir `local` sin `dev` | 18 |
| 9 | Registrar `getMessage()` del SDK | 19 |
| 10 | Elegir el lector por autodetección | 3, con BMP |

**Cierre (no ahora):**
- `mvn test -pl contracts,app` y el reactor con `scripts/ci-local.sh` completo en un clon limpio. Se pega el resumen literal de Surefire.
- Criterio 20: el golden path sigue en verde dentro de ese reactor.

---

## 6. Orden de trabajo (tras "plan aprobado")

1. Commit solo de documentación: ADR-051 y este plan. **Bloqueado** hasta que se confirme la copia canónica de ADR-051 (§0).
2. `contracts`: puerto y excepción.
3. Tests rojos de `app` (todo §5) con esqueletos que compilan, más las dependencias de §4.2 en `pom.xml` y `app/pom.xml` (antes eran el paso 4). **Corrección de orden:** los tests del adaptador S3 (simulado y MinIO) no compilan sin el SDK en el *classpath*, así que las dependencias entran con la fase roja. Árbol de dependencias pegado: sin `netty-nio-client` ni `apache-client`. Salida roja literal de `mvn test -pl app`.
4. *(Integrado en el paso 3.)*
5. Verde por capas: `ImageSanitizer` → adaptadores → `MediaStorageConfig` y los YAML → `MediaUploadService` y `media_objects`. Incluye `.env.example` y `runbook-demo-local.md` (§8).
6. Mutaciones de §5.
7. `ci-local.sh` en un clon limpio. Evidencia en `Documentos/evidencia-ci/`.
8. Nueva sección en `estado-fase6.md` con lo hecho, lo que queda para la Tarea 2 y la evidencia. **Esa sección y la descripción del PR dicen literalmente** (cambio de la aprobación, §9):
   > Los criterios 3, 4, 5 y 15 de ADR-051 quedan cubiertos a nivel de excepción (`UnsupportedImageFormatException`, `ImageTooLargeException`, `MediaStorageUnavailableException`). Las respuestas 413, 415 y 503 por HTTP quedan para la Tarea 2.

---

## 7. Riesgos y preguntas

### 7.1 Ya decidido en ADR-051 (no se reabre)

- Un reintento solo al guardar, misma clave y mismos bytes; si vuelve a fallar, 503 y ningún metadato (F2).
- El huérfano por fallo del `insert` no se limpia; se registra con su clave (F2). Las evidencias nunca vinculadas son deuda registrada (Q3 a).
- Se pierden el perfil de color y la orientación; la app endereza antes de subir (F4, Q1 a).
- La deduplicación es de mejor esfuerzo porque re-codificar no es determinista entre JDK (F4).
- Límites 8000 px, 40 MP, 5 MB y 2 MB (Q2).
- *Bucket* privado, todo a través del backend (F1, F6).

### 7.2 Riesgos técnicos que encuentra este plan

| # | Riesgo | Mitigación en el plan |
|---|---|---|
| R-1 | Memoria: decodificar 40 MP ocupa unos 160 MB por imagen en ARGB. Varias subidas a la vez pueden agotar el *heap*. ADR-051 no prevé un límite de concurrencia. | Ninguna en la Tarea 1 (sería un mecanismo nuevo, regla 3.5). Se registra para Carlos. |
| R-2 | Las versiones del SDK desde 2.30 envían por defecto sumas CRC32 con cuerpo `aws-chunked`; algunos proveedores compatibles con S3 las rechazaban. | `WHEN_REQUIRED` en la petición y en la respuesta. El test 17 lo ejerce contra MinIO; con R2 hay que confirmarlo al desplegar (F1: proveedor sin decidir). |
| R-3 | En DEBUG, el SDK puede registrar cabeceras, y `Authorization` contiene el *access key id* (no el secreto). | El criterio 19 se prueba en DEBUG. Si falla, se fija `software.amazon.awssdk: WARN` y se informa. |
| R-4 | Desde finales de 2025, MinIO dejó de publicar imágenes nuevas de la edición comunitaria. Las etiquetas antiguas siguen descargándose, pero pueden desaparecer. | Etiqueta fija verificada con `docker pull`. Si deja de existir, el test 17 se rompe de forma visible (no se salta). |
| R-5 | `ImageIO` usa los *plugins* del *classpath*: uno nuevo (p. ej. TwelveMonkeys) cambiaría qué se decodifica. | El lector se elige por nombre de formato. Hoy no hay *plugins* extra; no se añade un test que lo vigile (sería sobreingeniería). |
| R-6 | El único de la evidencia incluye los metadatos borrados por F7: volver a subir una foto retirada devolvería el metadato retirado. | No ocurre en la Tarea 1 (F7 es de la Tarea 5). Ver P-9. |

**Deuda registrada tras el verde (Manuel, 2026-10-20), sin cambiar código:**

| # | Deuda | Efecto | Precedentes |
|---|---|---|---|
| **DT-1** | `S3MediaStorageAdapter.isAmbiguous` recorre las causas de afuera hacia adentro y se queda con la primera que reconoce. Una `IOException` que envuelve una `ConnectException` se clasifica como ambigua (se ve primero la `IOException`), así que se reintenta una vez, aunque una conexión rechazada es determinista. | No hace daño: `put` con la misma clave y los mismos bytes es idempotente (F2), y el coste es un intento más antes del 503. Corregirlo exigiría buscar primero las causas deterministas en toda la cadena. | — |
| **DT-2 — RESUELTA (2026-10-21, `05cdfc1`, §11)** | `MediaUploadService` (`app.application.media`) dependía de `MediaObjectRepository` y `MediaObjectDocument` (`app.infrastructure.media`): la capa de aplicación conoce la persistencia. | Acopla el servicio a Spring Data y al documento de Mongo. Ninguna regla de ArchUnit lo prohíbe hoy; `AppWebArchitectureTest` solo protege `app.web`. | **No hay precedentes en `app`.** Fuera de la tarea, ningún archivo de `app.application` importa `app.infrastructure`, `..persistence..`, `org.springframework.data` ni documentos; el único import de Mongo es `com.mongodb.MongoException` en `FundsApplicationOrchestrator`, para leer la etiqueta `TransientTransactionError`, que es del *driver* y no de la persistencia propia. Antes de esta tarea, `app` no tenía ningún `@Document` ni repositorio propio: solo `MongoIndexInitializer` (config) y `DemoScenarioSeeder` (bootstrap) usan `MongoTemplate`. En los demás módulos (`core`, `identity`, `convocatoria`) la aplicación usa puertos de `application.port.out` implementados en `infrastructure`. La corrección natural sería un puerto de salida en `app.application.media`, implementado en `app.infrastructure.media`. |

### 7.2bis Hallazgos de la fase roja (2026-10-20)

| # | Hallazgo | Qué hace el plan | Pregunta |
|---|---|---|---|
| **N-1** (resuelta: rechazo por espacio de color, ver §4.6) | ADR-051 F4 pone "JPEG CMYK" como ejemplo de "imagen que la JDK no decodifica". **En la JDK 21.0.12 de este equipo no es así:** `ImageIO` lo decodifica (`SimpleCMYKColorSpace`) y el escritor lo vuelve a codificar como JPEG de cuatro componentes, que los navegadores muestran con los colores mal. Si se aplica solo la regla literal ("lo que la JDK no decodifica"), un JPEG CMYK se guardaría. | El test rojo `ImageSanitizerTest.cmykJpeg_isUnsupported` sigue la intención del ADR: CMYK → `UnsupportedImageFormatException`. Para ponerlo en verde hace falta una regla explícita: **rechazar la imagen decodificada si su espacio de color no es RGB ni gris** (en la práctica, cuatro componentes). | ¿Se aplica esa regla (rechazo explícito, coherente con el ejemplo de F4) o se admite CMYK porque la JDK lo decodifica? Hasta la respuesta, ese test queda en rojo. |
| **N-2** (resuelta: aceptada, fijada por digest, ver §5) | `minio/minio` desapareció de Docker Hub (R-4). | MinIO de `bitnamilegacy`, con versión fijada (§5). | ¿Se acepta esa imagen para el criterio 17, o prefieres otra fuente (p. ej. construir la imagen desde el código de MinIO, o un registro interno)? |

### 7.3 Preguntas

**Decididas en la aprobación (Manuel, 2026-10-20):** P-3 sí (excluir `apache-client`); P-4 sí, con los valores por defecto propuestos; P-5 `IllegalArgumentException`; P-7 declarar `ulid-creator` 5.2.3 en `app/pom.xml`; P-8 solo registrar el huérfano. T-3 aceptado.

**Siguen abiertas para Carlos y no bloquean la Tarea 1:** P-1, P-2, P-6 y P-9.

Texto original de las preguntas, conservado:

| # | Pregunta | Recomendación |
|---|---|---|
| **P-1** | T-1: ¿el controlador de las tareas 2 a 4 repite el límite de 2 MB de F8, o basta con el del servicio? | El del servicio, con la misma constante de `MediaPurpose`; el controlador solo deja el límite de Spring de 5/6 MB de F8 |
| **P-2** | ¿La traducción de `ImageTooLargeException` → 413, `UnsupportedImageFormatException` → 415 y `MediaStorageUnavailableException` → 503 (un bean `ApiErrorMappings` en `app`) entra en la Tarea 1 o en la 2? | En la Tarea 2, con la primera ruta, para probarla por HTTP |
| **P-3** | ¿Se excluye también `apache-client` (§4.2)? | Sí: un solo cliente HTTP, el de la JDK, como dice F1 |
| **P-4** | Tiempos del cliente S3 (conexión 3 s, *socket* 10 s, intento 10 s): ¿valores de implementación? | Sí, configurables y con esos valores por defecto |
| **P-5** | Una clave que no es ULID en un adaptador es un error de programación (las claves solo las genera el servicio). ¿`IllegalArgumentException` (que ya da 500, §0.16) o una excepción nombrada nueva que ADR-051 no define? Lo mismo para `ownerRef` presente fuera de la portada, o ausente en ella. | `IllegalArgumentException`: no es una condición de fallo de la operación ni puede venir del cliente |
| **P-6** | ¿Añado ADR-051 al catálogo de `documento-maestro-proyecto.md` §5 en este PR (T-4)? ¿Y ADR-050, que ADR-051 cita (D10) y no está en el repositorio? | ADR-051 sí, con una línea; ADR-050 cuando lo suba su dueño |
| **P-7** | `ulid-creator` llega a `app` solo de forma transitiva, a través de `identity` (5.2.3, versión fijada en `identity/pom.xml`). ¿Declararlo en `app/pom.xml`? Sería una dependencia directa nueva de `app`, aunque no del proyecto. | Declararlo con la misma versión y dejar el traslado al `dependencyManagement` del padre para otro PR |
| **P-8** | En la carrera de dos evidencias idénticas, ¿se borra el objeto perdedor (`delete`) o solo se registra como huérfano? | Solo registrarlo, como dice F2 para el fallo del `insert` |
| **P-9** | R-6: con una evidencia retirada (F7), ¿volver a subir la misma foto debe dar error o un metadato nuevo? Afecta al índice parcial. | Decidirlo en la Tarea 5; la Tarea 1 no tiene borrados |

---

## 8. Archivos

**Se crean:**

| Archivo | Para qué |
|---|---|
| `Documentos/ADR-051-fotos-y-almacenamiento.md` | La norma (ya copiada, sin commit) |
| `Documentos/plan-adr051-tarea1-almacenamiento.md` | Este plan |
| `contracts/src/main/java/com/traceability/contracts/media/MediaStoragePort.java` | F9 |
| `contracts/src/main/java/com/traceability/contracts/media/MediaStorageUnavailableException.java` | F2, F9 |
| `app/src/main/java/com/traceability/app/application/media/MediaPurpose.java` | F8, F9 |
| `app/src/main/java/com/traceability/app/application/media/ImageSanitizer.java` | F4 |
| `app/src/main/java/com/traceability/app/application/media/SanitizedImage.java` | F4 |
| `app/src/main/java/com/traceability/app/application/media/ImageTooLargeException.java` | F4 |
| `app/src/main/java/com/traceability/app/application/media/UnsupportedImageFormatException.java` | F4 |
| `app/src/main/java/com/traceability/app/application/media/MediaUploadService.java` | F2, F9 |
| `app/src/main/java/com/traceability/app/application/media/StoredMedia.java` | F4, F9 |
| `app/src/main/java/com/traceability/app/infrastructure/media/S3MediaStorageAdapter.java` | F1, F2 |
| `app/src/main/java/com/traceability/app/infrastructure/media/LocalMediaStorageAdapter.java` | F1 |
| `app/src/main/java/com/traceability/app/infrastructure/media/MediaStorageProperties.java` | F1 |
| `app/src/main/java/com/traceability/app/infrastructure/media/MediaStorageConfig.java` | F1 |
| `app/src/main/java/com/traceability/app/infrastructure/media/MediaObjectDocument.java` | F9 |
| `app/src/main/java/com/traceability/app/infrastructure/media/MediaObjectRepository.java` | F9 |
| `app/src/main/java/com/traceability/app/application/media/MediaObjectStore.java` | F9, iteración DT-2 |
| `app/src/main/java/com/traceability/app/application/media/MediaObject.java` | F9, iteración DT-2 |
| `app/src/main/java/com/traceability/app/application/media/DuplicateEvidenceException.java` | F9, iteración DT-2 |
| `app/src/main/java/com/traceability/app/infrastructure/media/MongoMediaObjectStore.java` | F9, iteración DT-2 |
| `app/src/test/java/com/traceability/app/application/archfixture/ApplicationUsingInfrastructure.java`, `ApplicationUsingSpringData.java` | Violaciones deliberadas de la regla de ArchUnit de DT-2 |
| `app/src/test/java/com/traceability/app/archfixture/AwsSdkOutsideTheMediaAdapter.java` | Violación deliberada de la regla del SDK |
| `app/src/test/java/com/traceability/app/infrastructure/media/InMemoryMediaStorageAdapter.java` | F1, F9 |
| `app/src/test/java/com/traceability/app/application/media/TestImages.java` | Imágenes de prueba generadas |
| `app/src/test/java/com/traceability/app/application/media/ImageSanitizerTest.java` | Criterios 3 y 4 |
| `app/src/test/java/com/traceability/app/application/media/MediaUploadServiceTest.java` | Criterios 1, 2, 3, 5 y 15; orden; deduplicación |
| `app/src/test/java/com/traceability/app/infrastructure/media/S3MediaStorageAdapterTest.java` | Criterio 15 (reintento) |
| `app/src/test/java/com/traceability/app/infrastructure/media/S3MediaStorageAdapterMinioIntegrationTest.java` | Criterios 17 y 19 |
| `app/src/test/java/com/traceability/app/infrastructure/media/LocalMediaStorageAdapterTest.java` | F1 |
| `app/src/test/java/com/traceability/app/infrastructure/media/MediaStorageConfigTest.java` | Criterios 18 y 19 |
| `app/src/test/java/com/traceability/app/infrastructure/media/MediaObjectPersistenceIntegrationTest.java` | F9, criterio 15 en Mongo real |
| `app/src/test/java/com/traceability/app/infrastructure/media/MediaArchitectureTest.java` | ArchUnit |
| `Documentos/evidencia-ci/ci-local-<commit>-<fecha>.txt` | Al final, la genera `ci-local.sh` |

**Se modifican:**

| Archivo | Cambio |
|---|---|
| `pom.xml` | `awssdk.version`, y `s3` y `url-connection-client` en `dependencyManagement` |
| `app/pom.xml` | `s3` (sin `netty-nio-client` ni `apache-client`, P-3) y `url-connection-client`; `ulid-creator` 5.2.3 (P-7) |
| `.env.example` | Las cinco `MEDIA_S3_*` **vacías**, con un comentario (ADR-051 F1: sin ellas, la aplicación no arranca fuera de `dev`; `MEDIA_S3_REGION=auto` en R2; nunca en ficheros versionados) |
| `Documentos/runbook-demo-local.md` | Fila en "El backend no arranca", con la misma forma que la de DD-61: `MEDIA_S3_*` o `local` sin perfil `dev` → arrancar con `SPRING_PROFILES_ACTIVE=dev` (`demo.env`) o dar las cinco variables |
| `app/src/main/resources/application.yml` | `traceability.media.*` con `${MEDIA_S3_…}` sin valor por defecto |
| `app/src/main/resources/application-dev.yml` | `storage: local` y el directorio |
| `app/src/test/resources/application.yml` | S3 ficticio de tests (`localhost:1`) |
| `app/src/test/java/com/traceability/app/config/MongoIndexesAtStartupIntegrationTest.java` | Los dos índices de `media_objects` |
| `Documentos/estado-fase6.md` | Nueva sección al cerrar (paso 8) |
| `Documentos/documento-maestro-proyecto.md` | Solo si P-6 = sí: ADR-051 en el catálogo |

**No se tocan:** `contracts/pom.xml`; `core`, `crypto`, `ai`, `api`, `identity` y `convocatoria`; `PublicRoutes`; `PlatformCommandType`; `referencia-api-v1.md` (no hay rutas nuevas).

---

## 9. Aprobación — Manuel, 2026-10-20

"Plan aprobado con estos cambios" (texto del mensaje, resumido punto por punto):

1. Añadir a "Se modifican" `.env.example` (las cinco `MEDIA_S3_*` vacías con comentario, ADR-051 F1) y `Documentos/runbook-demo-local.md` (fila en "El backend no arranca", igual a la de DD-61). → §8.
2. `ImageIO.setUseCache(false)` una sola vez en la configuración, con un comentario de que es global de la JVM; no en cada limpieza. → §4.6.
3. La sección de `estado-fase6.md` y la descripción del PR dicen literalmente que los criterios 3, 4, 5 y 15 quedan cubiertos a nivel de excepción, y que el 413/415/503 por HTTP queda para la Tarea 2. → §6, paso 8.
4. Decisiones: P-3 sí; P-4 sí, con esos valores; P-5 `IllegalArgumentException`; P-7 declarar `ulid-creator` en `app/pom.xml` con la misma versión; P-8 solo registrar el huérfano; T-3 aceptado. P-1, P-2, P-6 y P-9 quedan abiertas para Carlos y no bloquean la Tarea 1. → §4.2, §4.3, §4.7, §7.3.
5. No hacer el commit del paso 1 hasta confirmar cuál es la copia canónica de ADR-051. Avanzar con los pasos 2 y 3 sin commit y pegar la salida roja literal de `mvn test -pl app`. → §6.

**Ajustes del agente al ejecutar, registrados aquí:** se excluye también `apache5-client`, el cliente Apache por defecto en 2.57.1 (§4.2, P-3); las dependencias de §4.2 pasan del paso 4 al paso 3, porque sin el SDK los tests rojos del adaptador S3 no compilan (§6). La versión fijada es la 2.55.10 (§4.2; antes 2.57.1, cambiada el 2026-10-20 para usar una versión con al menos una o dos semanas).

## 10. Ejecución (2026-10-20, sin commit)

| Paso | Estado | Evidencia |
|---|---|---|
| 1. Commit de ADR-051 y el plan | **Bloqueado**: falta confirmar la copia canónica de ADR-051 (§0). El código va aparte en `035d30a` (rama local, sin push), por instrucción de Manuel | — |
| 2. Puerto en `contracts` | Hecho | `com.traceability.contracts.media` |
| 3. Rojos (con dependencias) | Hecho | `mvn test -pl app`: `Tests run: 399, Failures: 96, Errors: 30` — solo las clases nuevas o modificadas; las 261 existentes en verde. Después, los rojos de N-1 (`cmykAndYcckJpegs_areUnsupported` ×3, `rgbaGrayAlphaAndPalettePngs_areAdmitted` ×3) y el caso JPEG separado del criterio 4 |
| 5. Verde por capas | Hecho | Clases de la tarea: `Tests run: 144, Failures: 0, Errors: 0`. Módulo completo: `mvn test -pl app` → `Tests run: 405, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (261 existentes + 144 de la tarea), golden path incluido |
| 6. Mutaciones | Hecho sobre `035d30a`: **10 de 10 muertas**, restauración verificada contra HEAD. La 3 (dimensiones después de decodificar) muere **por los casos PNG** (`hugeDeclaredDimensions_areRejectedFromTheHeader_beforeDecoding`, `over40Megapixels_withBothSidesUnder8000_isTooLarge`); el caso JPEG (`jpegWithHugeDeclaredDimensions_isRejectedByItsHeader`) no la mata, como se esperaba (§5) | `scripts/mutaciones.py` con el JSON de §5; detalle en la tabla de abajo |
| 7. `ci-local.sh` en clon limpio | Hecho sobre `035d30a` (clon nuevo de la rama, sin otra carga en la máquina; 2026-10-21T04:41:04Z → 04:54:58Z): core 326, crypto 50, ai 47, api 137, identity 289, convocatoria 241, app 405, todos con `Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`; sha256 del log `037747431b3a35f237b6e1cc94f19054619dd37165af32c363455259db700602` | `Documentos/evidencia-ci/ci-local-035d30a-2026-10-21T04-41-04Z.txt` (copiada al repositorio, sin commit) |
| 8. Sección de `estado-fase6.md` | Pendiente, al cerrar | — |

Comprobado en el log del verde: contra MinIO real, una firma mala da `status=403 errorCode=SignatureDoesNotMatch` con `attempt=1 retry=false`, y el proveedor congelado da `ApiCallAttemptTimeoutException` con `attempt=1 retry=true` y después `attempt=2 retry=false`. Con el SDK en DEBUG (249 líneas de `software.amazon.awssdk`), ninguna credencial aparece en el log: **R-3 no se materializa** y no hace falta subir el nivel de log del SDK.

**Mutaciones (2026-10-20, sobre `035d30a`):**

| # | Mutación | Resultado | Tests que la matan |
|---|---|---|---|
| 1 | Sin reintento de `put` | Muerta | `put_timeoutThenSuccess_retriesOnce_withTheSameKeyAndBytes`; `put_twoAmbiguousFailures_throwsUnavailable_afterExactlyTwoAttempts` [1–10]; `failedAttempts_areLogged_…` |
| 2 | Reintentar también los 4xx | Muerta | `put_deterministicFailure_isNotRetried` [1–9] (los 4xx) |
| 3 | Dimensiones después de decodificar | Muerta | `hugeDeclaredDimensions_areRejectedFromTheHeader_beforeDecoding` (PNG); `over40Megapixels_withBothSidesUnder8000_isTooLarge` (PNG) |
| 4 | Huella de la entrada | Muerta | `returnedSha256_isTheDigestOfTheStoredBytes_notOfTheUploadedOnes`; `sha256_isTheDigestOfTheOutputBytes_inLowercaseHex` |
| 5 | Guardar la entrada con sus metadatos | Muerta | `jpegWithGpsExifXmpAndComment_…`; `pngWithExifAndTextChunks_…`; `jpeg_isReencodedWithQuality09`; `returnedSha256_…` |
| 6 | Insertar antes de guardar | Muerta | `writeOrder_isCleanThenPutThenInsert_withTheExactMetadata`; `storageUnavailable_propagates_andNoMetadataIsInserted`; `storageUnavailable_leavesMediaObjectsEmpty`; `insertFailure_logsTheOrphanKey_…`; `concurrentDuplicateEvidence_…` |
| 7 | Único sin `partialFilter` | Muerta | `sameBytes_asLogoAndCover_orAsEvidenceOfAnotherOrganization_doNotCollide`; `theCriticalUniqueAndPartialIndexes_existRightAfterStartup` |
| 8 | `local` sin `dev` | Muerta | `withoutDev_localStorage_doesNotStart` |
| 9 | Registrar el mensaje del SDK | Muerta | `failedAttempts_areLogged_withKeyAttemptClassStatusAndCode_butNeverTheProviderMessage` |
| 10 | Lector por autodetección | Muerta | `aBmpThatImageIoCouldRead_isUnsupported_becauseTheReaderIsChosenByMagicBytes`; `withoutJpegOrPngMagicBytes_isUnsupported` [1] (GIF); `unsupportedFormat_neverPutsNorInserts` |


## 11. Iteración DT-2: puerto de salida `MediaObjectStore` (aprobada por Manuel, 2026-10-21)

**Cambio:** se corrige DT-2 ahora, no como deuda. `MediaUploadService` usa el puerto `MediaObjectStore` (`app.application.media`), con el tipo de aplicación `MediaObject`; `MongoMediaObjectStore` (`app.infrastructure.media`) lo implementa con `MediaObjectRepository` y traduce el `DuplicateKeyException` de `uq_evidence_sha256` a `DuplicateEvidenceException`. Cualquier otro fallo del insert deja el huérfano registrado con su clave y se propaga (F2). Justificación: ADR-051 F9 (metadatos propiedad del adaptador de `app`) y el patrón `application.port.out` de los demás módulos. Clases nuevas en §4.8; archivos en §8.

**Regla nueva de ArchUnit** (`MediaArchitectureTest`): `com.traceability.app.application..` no depende de `com.traceability.app.infrastructure..` ni de `org.springframework.data..`. Se demuestra activa con dos violaciones deliberadas (`ApplicationUsingInfrastructure`, `ApplicationUsingSpringData`).

| Paso | Resultado |
|---|---|
| Rojo (clases afectadas, sobre el esqueleto) | `Tests run: 38, Failures: 11, Errors: 12` en `MediaUploadServiceTest`, `MediaObjectPersistenceIntegrationTest` y `MediaArchitectureTest`, todos por `UnsupportedOperationException` del esqueleto o la aserción correspondiente. La regla nueva ya estaba verde en esa fase, porque el esqueleto del servicio ya no importaba la infraestructura. Por eso su rojo real se comprobó **contra el código de `035d30a`** (clon limpio con el test nuevo copiado y después restaurado): `Tests run: 10, Failures: 1`, con `appApplication_doesNotDependOnAppInfrastructureNorSpringData` señalando el constructor y el campo `repository` (`MediaObjectRepository`) de `MediaUploadService` |
| Verde (clases afectadas) | `Tests run: 38, Failures: 0, Errors: 0, Skipped: 0` |
| Commit | `05cdfc1` en la rama local, sin push |
| `mvn test -pl app` | `Tests run: 411, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (405 anteriores + 4 del adaptador + 2 de ArchUnit) |
| Mutación 6 (insertar antes de guardar), reescrita para el código nuevo | **Muerta**: `writeOrder_isCleanThenPutThenInsert_withTheExactMetadata`, `storageUnavailable_propagates_andNoMetadataIsInserted`, `storageUnavailable_leavesMediaObjectsEmpty`, `insertFailure_logsTheOrphanKey_…`, `concurrentDuplicateEvidence_…` |
| Mutación 7 (único sin `partialFilter`) | **Muerta**: `sameBytes_asLogoAndCover_orAsEvidenceOfAnotherOrganization_doNotCollide`, `theCriticalUniqueAndPartialIndexes_existRightAfterStartup`. Restauración verificada contra HEAD |
| `ci-local.sh`, clon limpio de `05cdfc1` (2026-10-21T05:14:42Z → 05:28:42Z) | core 326, crypto 50, ai 47, api 137, identity 289, convocatoria 241, app 411, todos con `Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`; sha256 del log `c976601247894bf237c8b1731a7c2e18eee0df866f3549d5cc0539fa1b96cc8f`. Evidencia: `Documentos/evidencia-ci/ci-local-05cdfc1-2026-10-21T05-14-42Z.txt` (sin commit) |

**Pendiente, a la espera de la confirmación de la copia canónica de ADR-051:** commit de ADR-051, del plan y de las dos evidencias de `ci-local.sh`; sección de `estado-fase6.md`; push y PR.
