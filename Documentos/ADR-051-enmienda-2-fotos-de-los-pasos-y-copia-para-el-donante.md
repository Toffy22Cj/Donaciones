# ADR-051 — Enmienda 2: fotos opcionales en registrar, despachar y recibir, y copia censurada de la foto de entrega para el donante

## Status

**PROPUESTA — firma: Pendiente: Carlos.** Redactada el 2026-11-16 por Manuel, con asistencia de Claude, a partir de sus decisiones del 2026-11-15 (encargo, Tarea C, punto 4) y del 2026-11-16 (punto 11: «pixelado fuerte de toda la imagen con la JDK + aprobación del `ADMINISTRATOR`; la detección de caras queda para un ADR posterior»).

La firma es de **Carlos**: la enmienda cambia el contrato de eventos de `core` y ADR-051 lo aprobó él. **Manuel dio su conformidad con las decisiones el 2026-11-18** (respuesta «paso 3 cerrado, enmiendas de fotos y pendientes») y cerró las preguntas Q-E2-1 a Q-E2-6 con la opción recomendada (a). Hasta que Carlos lo confirme, el campo de firma queda en «Pendiente: Carlos».

*Historial del estado.* PROPUESTA el 2026-11-16 (#183). **Revisada el 2026-11-18** por cinco observaciones de Manuel, sin cambiar ninguna decisión: (1) generación bajo demanda con concurrencia (E2-4b); (2) historial de publicación que solo crece (E2-5); (3) el test 8 de redacción, que decía lo contrario de lo que quería (E2-4 y definición de hecho); (4) la forma de pintar la imagen sin pasar el `trackingCode` por la URL (en la Enmienda 2 de ADR-021-D); (5) el límite de peticiones de la ruta pública (en la Enmienda 2 de ADR-021-D).

**No autoriza código.** Cada tarea de §Implementación necesita su plan aprobado (regla 3.4). Va con la **Enmienda 2 de ADR-021-D** (perímetro público), que se aprueba a la vez.

**Por qué es una enmienda** (regla 3.5): cambia el contrato de tres eventos de `core` (versión de esquema nueva), añade propósitos y rutas a `media_objects`, amplía quién ve qué (F6) y amplía la retirada (F7).

**Enmienda a:** ADR-051 (F3, F5, F6, F7, F8, F9 y F10), con su Enmienda 1.

## Context

### Lo decidido por Manuel que esta enmienda diseña

| # | Decisión | Fecha |
|---|---|---|
| M1 | Fotos **opcionales** al registrar, despachar y recibir. La de entrega sigue siendo **obligatoria** | 2026-11-15 |
| M2 | Despachar y recibir **siguen funcionando sin conexión** (ADR-043 D6): sin señal, el paso se registra sin foto | 2026-11-15 |
| M3 | La organización (y el administrador de plataforma) ve la foto original, como hoy | 2026-11-15 |
| M4 | El backend genera una **copia derivada censurada**: objeto aparte en `media_objects`, con su propia huella. El `evidenceRef` anclado sigue siendo la huella de la original y no cambia | 2026-11-15 |
| M5 | El donante ve **solo la copia**, y **solo** si un `ADMINISTRATOR` de la organización la marca como publicable. Por defecto, no es pública | 2026-11-15 |
| M6 | La retirada de F7 también borra la copia | 2026-11-15 |
| M7 | Censura: **pixelado fuerte de toda la imagen con la JDK**, sin dependencias; detección de caras en un ADR posterior. La aprobación humana no se elimina: la censura automática sola no cumple el art. 7 de la Ley 1581 para menores | 2026-11-16 |

### Hechos verificados (`develop` `5c7dc94`, 2026-11-16)

- `MediaPurpose` tiene `ORGANIZATION_LOGO` y `CAMPAIGN_COVER` (2 MB) y `DELIVERY_EVIDENCE` (5 MB) (`app/.../media/MediaPurpose.java`).
- Versiones que se escriben hoy: `ASSET_REGISTERED` **4.0**, `ASSET_DISPATCHED` **2.0**, `ASSET_RECEIVED` **2.0**, `ASSET_DELIVERED` **2.0** (`EventPayloadRegistry.java:30-46`, ADR-029 Enmienda 2). Ninguno de los tres primeros lleva `evidenceRef`.
- El hash del evento incluye el **payload completo** y `schemaVersion`, y la verificación de integridad deserializa el payload guardado con la clase de su `(eventType, schemaVersion)` y lo vuelve a canonicalizar (ADR-029 Enmienda 2 §1). `EventCanonicalMapper.toCanonicalMap` convierte el payload con Jackson **sin excluir los nulos**.
- El gancho de F3 (vincular la foto antes del `append`) existe solo para la entrega; despachar y recibir pasan `NO_HOOK` (`PhysicalAssetCommandService`).
- La web registra lotes (`RegisterAssetForm`) y, en la ficha del lote, ofrece despachar, recibir y entregar. El móvil despacha, recibe y entrega con la cola sin conexión (ADR-043 D6, Enmienda 4); registrar sigue siendo de la web (Enmienda 2 de ADR-043 §1).
- El seguimiento público expone, en el paso `ASSET_DELIVERED`, `evidenceRegistered` y `evidenceRef` si es `sha256:` (ADR-021-D Enmienda 1). La imagen, nunca.

## Decision

### E2-1. Fotos opcionales en registrar, despachar y recibir

- Tres propósitos nuevos en `media_objects`: `REGISTRATION_EVIDENCE`, `DISPATCH_EVIDENCE` y `RECEIPT_EVIDENCE`. Mismas reglas que `DELIVERY_EVIDENCE`: JPEG o PNG por contenido, limpieza y huella con la JDK (F4), **5 MB**, aviso fijo de no fotografiar personas (F7), la app endereza la imagen (Q1 (a)).
- **Subida:** `POST /api/v1/organizations/{organizationId}/asset-evidence`, multipart con `file` y `step` (`REGISTERED` | `DISPATCHED` | `RECEIVED`) → `201 {evidenceRef}` (`sha256:`). Permiso: los roles que `RoleAuthorizationPolicy` autoriza para el comando de ese paso, de esa organización; el resto, el 403 uniforme (DD-01). La ruta de la entrega (`…/delivery-evidence`) no cambia.
- **Comandos:** `register`, `from-donation`, `dispatch` y `receive` aceptan `evidenceRef?` opcional. Si llega, tiene que tener el formato `^sha256:[0-9a-f]{64}$` (400 si no) y se vincula como en F3: **una foto, un activo y un paso**; la misma foto en otro activo o en otro paso → 409 `EvidenceAlreadyBound`; inexistente, de otra organización, de otro propósito o borrada → 400 `EvidenceNotFound`. Sin `evidenceRef`, el paso se registra como hoy.
- **Orden:** el mismo de F3 (cargar, autorizar, validar en el agregado, vincular, `append`), con el gancho que hoy solo usa la entrega. Un rechazo por permisos o por estado **no vincula** la foto.

### E2-2. Versión de esquema (análisis pedido por Manuel)

**Hace falta subir la versión de los tres eventos.** Añadir `evidenceRef` a las clases actuales (`AssetRegisteredV4Payload`, `AssetDispatchedV2Payload`, `AssetReceivedV2Payload`) cambiaría el hash de los eventos ya guardados: al verificarlos, Jackson los deserializaría con el campo nuevo a `null` y el mapa canónico ganaría `"evidenceRef": null`, que no estaba cuando se calculó su hash. La verificación daría `HASH_MISMATCH` en eventos íntegros.

| Evento | Versión nueva | Clase | Contenido |
|---|---|---|---|
| `ASSET_REGISTERED` | **5.0** | `AssetRegisteredV5Payload` | 4.0 + `evidenceRef` (`null` sin foto) |
| `ASSET_DISPATCHED` | **3.0** | `AssetDispatchedV3Payload` | 2.0 + `evidenceRef` |
| `ASSET_RECEIVED` | **3.0** | `AssetReceivedV3Payload` | 2.0 + `evidenceRef` |
| `ASSET_DELIVERED` | 2.0, **sin cambio** | — | ya lleva `evidenceRef` (F3) |

- Toda escritura nueva usa la versión nueva, con o sin foto, como hizo ADR-029 Enmienda 2. Las anteriores solo se leen y **no cambian**: el mismo hash antes y después (test).
- La huella de la foto de cada paso queda **sellada y anclada** dentro del evento, como la de la entrega (F11). La imagen no sale del almacenamiento privado.
- El hijo de una división (`ASSET_SPLIT` y el registro del hijo por la saga) nace **sin** foto; no se pide en ese flujo.

### E2-3. Sin conexión (ADR-043 D6; M2)

- Despachar y recibir **siguen entrando en la cola** del móvil. **La cola nunca guarda fotos** de estos pasos: un paso en cola va sin `evidenceRef`. Motivo: es lo que F5 ya descartó para la entrega (fotos en un dispositivo que se puede perder).
- Con conexión, la app sube la foto primero y manda el comando con su `evidenceRef`. Si la subida falla (sin red, 503), la app ofrece seguir **sin foto**; nunca bloquea el paso.
- Si la respuesta del comando no llega (fallo ambiguo), se reintenta con el mismo `Command-Id` y el mismo `evidenceRef`: el vínculo admite el mismo activo y paso (F3).
- La entrega no cambia: exige conexión y foto (F5).

### E2-4. Copia censurada de la foto de entrega (M4, M7)

- **Solo de la foto de entrega.** Las fotos de los otros pasos no tienen copia para el donante en este corte (Q-E2-4).
- **Propósito nuevo:** `DELIVERY_EVIDENCE_REDACTED`. Es un objeto aparte en `media_objects`: su propia `objectKey`, su propia huella (`sha256`) y `ownerRef` = la `objectKey` de la original. El `evidenceRef` del evento (la huella de la original) **no cambia** y la copia **no** se ancla: es un derivado, no un hecho.
- **Cómo se censura (JDK, sin dependencias):**
  1. se parte de los bytes limpios guardados (F4);
  2. se reduce la imagen a una cuadrícula de **24 bloques en su lado más largo** (Q-E2-1) y `N = round(24 × lado corto / lado largo)` bloques en el corto (mínimo 1), promediando el color de cada bloque;
  3. cada bloque se amplía a **20 × 20 px** con vecino más próximo: la copia mide **480 px en el lado largo y `20 × N` en el corto** (la relación de aspecto se redondea al bloque);
  4. se guarda en PNG, con parámetros fijos y sin metadatos.

  **Determinista:** los mismos bytes de entrada dan los mismos bytes de salida y, por tanto, la misma huella (misma versión de la JDK). La unicidad de la copia **no depende de eso** (E2-4b).

  El resultado conserva la composición y los colores, y no deja reconocer caras, texto ni matrículas. **Tampoco deja leer un acta.** Es deliberado: sirve para mostrar que hay una foto de la entrega, no su contenido.
- **Cuándo se genera** (Q-E2-2): **bajo demanda**, la primera vez que un `ADMINISTRATOR` abre la vista previa para publicarla, y se guarda. Así no se generan copias que nadie va a publicar, y vale también para las fotos de entrega anteriores a esta enmienda. Si la generación falla, el `ADMINISTRATOR` ve el error y puede reintentar; la entrega no se ve afectada.
- **Regla fija:** la censura no se configura desde la interfaz: el nivel de pixelado es del código y de esta enmienda.

### E2-4b. Generación bajo demanda y concurrencia

`GET …/evidence/redacted` busca la copia por `ownerRef` (la `objectKey` de la original) y, si no existe, la genera y la guarda con el orden de F2 (bytes en el proveedor, después el metadato). Dos `ADMINISTRATOR` pueden abrirla a la vez, así que el choque se trata como un caso normal:

- **Garantía de una sola copia.** `media_objects` tiene el índice único `(organizationId, purpose, sha256)` (F9) y esta enmienda añade el índice único parcial **`(organizationId, purpose, ownerRef)` solo para `DELIVERY_EVIDENCE_REDACTED`**: una original, como mucho una copia, aunque la JDK diera bytes distintos. Se declara en el documento de `media_objects` como los demás, y lo crea `MongoIndexInitializer` (`app/.../config/MongoIndexInitializer.java:56-57`, que ejecuta `ensureIndex` sobre cada índice declarado).
- **Un choque de clave duplicada en cualquiera de los dos índices significa «ya existe».** El que pierde la carrera relee la copia existente por `ownerRef` y **la devuelve** (`200`, los mismos bytes). **Nunca** es un `500` ni un `409`.
- **El archivo del perdedor** ya está en el proveedor con su propia clave: queda huérfano (F2). Se intenta borrar y, si no se puede, se registra con su clave, como en F10.
- Si al releer la copia ya no está porque la retiró F7 entre medias → `410 EvidenceRemoved`, como con la original.

### E2-5. Publicación para el donante: aprobación humana (M5)

- Por defecto, **no publicada**. Publicar o retirar la publicación es exclusivo del **`ADMINISTRATOR` de la organización del activo**. Ni el `EMPLOYEE`, ni el `REPRESENTATIVE`, ni el administrador de plataforma publican (Q-E2-3); el de plataforma sí puede retirar la foto por F7.
- **Rutas** (JWT):

  | Ruta | Respuesta |
  |---|---|
  | `GET /api/v1/physical-assets/{assetRef}/evidence/redacted` | La copia (la genera si no existe). Para el `ADMINISTRATOR` y los miembros de la organización, y el administrador de plataforma. `Cache-Control: private, no-store` |
  | `PUT /api/v1/physical-assets/{assetRef}/evidence/redacted/publication` | `{confirmNoIdentifiablePerson: true}` → `204`. Sin la confirmación, 400 |
  | `DELETE /api/v1/physical-assets/{assetRef}/evidence/redacted/publication` | `204`, también si no estaba publicada |

- **La confirmación es explícita:** quien publica marca «Revisé la copia y no se reconoce a ninguna persona». No hay publicación automática ni por lotes.
- **Historial de decisiones que solo crece.** El metadato de la copia lleva una lista `publicationDecisions`; cada entrada es `{action, at, by, confirmNoIdentifiablePerson}`:
  - `action` es `PUBLISH` o `UNPUBLISH`;
  - `at` es el instante y `by`, el `accountId` de quien decidió;
  - `confirmNoIdentifiablePerson` es `true` en `PUBLISH` y `null` en `UNPUBLISH`.

  La lista **solo se escribe añadiendo** (una operación de añadir al final; el adaptador no ofrece ninguna que reemplace o borre entradas). **La publicación vigente se deriva de la última entrada**: es vigente si es `PUBLISH` y ni la copia ni la original están borradas por F7. Así, publicar, despublicar y volver a publicar deja tres entradas y la primera no cambia: «queda registrado quién y cuándo» es verdad de cada decisión, no solo de la última.
- `PUT` sobre una copia ya publicada y `DELETE` sobre una no publicada responden `204` **sin añadir** entrada (son idempotentes y no ensucian el historial).
- La retirada por F7 de la foto de entrega, si la publicación estaba vigente, **añade** una entrada `UNPUBLISH` cuyo `by` es el administrador de plataforma (E2-7).
- Una foto retirada por F7 no se puede publicar (410 `EvidenceRemoved`).

### E2-6. Quién ve cada foto (amplía F6)

| Foto | Organización del activo (cualquier rol) | Administrador de plataforma | Donante (seguimiento público) |
|---|---|---|---|
| Original de entrega | Sí (`GET …/evidence`, como hoy) | Sí | **Nunca** |
| Originales de registrar, despachar y recibir | Sí (`GET …/evidence/{step}`, con `step` en minúsculas) | Sí | **Nunca** |
| Copia censurada de la entrega | Sí | Sí | **Solo si está publicada** (ADR-021-D Enmienda 2) |

- La ruta actual `GET /physical-assets/{assetRef}/evidence` sigue siendo la de la entrega; `…/evidence/delivered` es un alias.
- Sin foto → 404. Foto retirada por F7 → 410 `EvidenceRemoved` en las rutas autenticadas.
- La ficha del lote en la web muestra, en el detalle de cada paso, su foto original cuando quien mira es miembro de la organización. Al `ADMINISTRATOR` le muestra además la copia censurada y el control de publicación.

### E2-7. Retirada (amplía F7; M6)

- `POST /platform/organizations/{organizationId}/media/remove` con el `purpose` de cualquier foto de paso (los cuatro de evidencia) borra sus bytes como hoy.
- **Si es la de entrega, borra también la copia censurada** (bytes y publicación) en la misma operación: los bytes se borran y, si la publicación estaba vigente, se añade la entrada `UNPUBLISH` del historial (E2-5). El metadato de las dos se conserva con `deletedAt`, `deletedBy` y `reason`.
- La huella sigue en el evento, que es inmutable. El seguimiento público deja de ofrecer la copia **sin decir por qué** (ADR-021-D Enmienda 1, E3).
- La retirada de una foto de entrega publicada se hace en un solo paso; no hay un instante en que la copia siga publicada sin original.

### E2-8. Comodidad del usuario (decisiones de Manuel; sin cambio de contrato)

Van en los PR de la web y del móvil con su plan; no necesitan más enmienda que esta:

- vista previa antes de subir;
- arrastrar y soltar en la web; cámara directa en el móvil;
- barra de progreso de la subida (en la web con `XMLHttpRequest`, sin dependencias);
- mensajes claros para 413, 415 y 503;
- cambiar o quitar la foto sin salir de la pantalla;
- la **portada** como paso final opcional al crear la convocatoria (tras el `201`, la ruta de F8 ya existe) y desde el listado `/panel/campaigns`, solo para el `ADMINISTRATOR` (C1 (a)). En el móvil, no (decisión de Manuel del 2026-11-16);
- la corrección del aviso de `/panel/assets` («Despachar y recibir se hacen escaneando el QR en la app móvil»), que contradice los botones de la ficha del lote.

## Preguntas — cerradas por Manuel el 2026-11-18 (todas, con la opción recomendada)

| # | Pregunta | Opciones | Decisión (Manuel, 2026-11-18) |
|---|---|---|---|
| **Q-E2-1** | Nivel de pixelado | (a) 24 bloques en el lado largo. (b) 16: más fuerte, apenas se ve la escena. (c) 32: más detalle; una cara de frente y cerca podría intuirse | **(a)**. La aprobación humana sigue siendo obligatoria en cualquier caso |
| **Q-E2-2** | Cuándo se genera la copia | (a) Bajo demanda, al abrir la vista previa para publicar. (b) Al subir cada foto de entrega | **(a)**: cubre las fotos anteriores y no genera copias que nadie publica. Con la concurrencia de E2-4b |
| **Q-E2-3** | Quién publica | (a) Solo `ADMINISTRATOR` de la organización. (b) También el `REPRESENTATIVE` | **(a)** |
| **Q-E2-4** | ¿Copia para el donante de las fotos de registrar, despachar y recibir? | (a) No en este corte. (b) Sí, con el mismo mecanismo | **(a)** |
| **Q-E2-5** | ¿Se pide una foto al registrar desde el móvil? | (a) No: registrar sigue siendo de la web (Enmienda 2 de ADR-043). (b) Sí, con una enmienda de ADR-043 | **(a)** |
| **Q-E2-6** | Fotos de paso subidas y nunca vinculadas | (a) Misma deuda que Q3 de ADR-051. (b) Limpieza programada | **(a)** |

## Alternatives

| Alternativa | Por qué no |
|---|---|
| Añadir `evidenceRef` a las clases actuales de los tres eventos | Cambia el hash recalculado de los eventos ya guardados (E2-2) |
| Un solo propósito `STEP_EVIDENCE` con el paso en otro campo | El índice único `(organizationId, purpose, sha256)` y las reglas por paso son más simples con un propósito por paso |
| Guardar las fotos de despachar y recibir en la cola sin conexión | Es lo que F5 descartó para la entrega: fotos en un dispositivo que se puede perder |
| Detección de caras (OpenCV u otra) | Dependencia nueva y falla con menores de perfil o de lejos; ADR posterior (M7) |
| Desenfoque gaussiano en lugar de pixelado | Con la JDK (`ConvolveOp`) hace falta un núcleo grande y es más lento; un desenfoque suave puede revertirse en parte; el pixelado por bloques no |
| Publicación automática tras la censura | La censura automática sola no cumple el art. 7 de la Ley 1581 para menores (M7) |
| Anclar la huella de la copia | La copia es un derivado regenerable; lo anclado es el hecho: la original |

## Consequences

**Positivas**
- Cada paso puede llevar su foto, sellada y anclada por su huella, sin bloquear el trabajo sin conexión.
- El donante puede ver que hubo una entrega con foto, sin que se reconozca a nadie, y solo con la decisión de una persona responsable.

**Negativas**
- Tres versiones de esquema nuevas: todo lector de esos eventos tiene que conocerlas (proyecciones, seguimiento, actividad, integridad).
- La copia pixelada no sirve para leer un acta ni para verificar a simple vista qué se entregó.
- Más rutas y más metadatos en `media_objects`.

**Riesgos residuales**
1. Un `ADMINISTRATOR` puede publicar una copia sin revisarla bien. La confirmación explícita y su registro (quién y cuándo) lo hacen trazable, no imposible.
2. Con 24 bloques, una persona muy cerca de la cámara puede quedar como una forma reconocible por su silueta o su ropa. Por eso la aprobación humana es obligatoria.
3. Las fotos de paso nunca vinculadas se acumulan (Q-E2-6 (a)).
4. Quien pierde la carrera de generación (E2-4b) deja un archivo huérfano en el proveedor; se intenta borrar y, si no, queda registrado (F2, F10).

## Documentos que cambian por esta enmienda

| Documento | Cambio |
|---|---|
| ADR-021-D, Enmienda 2 | La copia censurada publicada en el seguimiento público; qué nunca se expone |
| ADR-043 (móvil), enmienda nueva | Cámara opcional en despachar y recibir; la cola sin fotos (E2-3) |
| `referencia-api-v1.md` | Rutas de E2-1, E2-5 y E2-6; campos `evidenceRef?` de los comandos |
| ADR-029 Enmienda 2 | Nota fechada: las versiones 4.0/2.0/2.0 dejan de ser las que se escriben |
| `DemoScenarioSeeder`, `DemoHistoricSeeder`, golden path | Las versiones nuevas; una foto de paso de ejemplo |

## Definición de hecho (tests, rojos primero; por tarea)

**Esquema e integridad**
1. Los eventos `ASSET_REGISTERED` 1.0 a 4.0, `ASSET_DISPATCHED` y `ASSET_RECEIVED` 1.0 y 2.0 ya guardados verifican con el **mismo hash** antes y después.
2. Toda escritura nueva usa 5.0 / 3.0 / 3.0, con o sin foto; su hash incluye `evidenceRef`.

**Fotos de paso**
3. Registrar, despachar y recibir sin `evidenceRef` funcionan como hoy (aserción negativa: no se llama a `bindToAsset`).
4. Con `evidenceRef` válido, el evento lo lleva y la foto queda vinculada a ese activo y paso; en otro activo o paso → 409; de otro propósito u organización → 400.
5. Un rechazo por permisos o estado no vincula la foto (`never()`).
6. Móvil: despachar y recibir sin conexión entran en la cola **sin** foto y se envían al volver la red; la cola nunca contiene bytes de imagen.

**Copia censurada**
7. La copia es otro objeto con otra huella; el `evidenceRef` del evento no cambia.
7b. **La generación es determinista:** dos generaciones a partir de los mismos bytes dan los mismos bytes y la misma huella (misma JDK).
7c. **Concurrencia:** dos `GET …/evidence/redacted` simultáneos de la misma foto, sin copia previa, responden los dos `200` con los mismos bytes, y en `media_objects` queda **una sola** copia para esa original (aserciones negativas: ningún `500` ni `409`, ninguna segunda fila). El mismo caso con bytes distintos forzados en cada generación sigue dejando una sola copia por el índice `(organizationId, purpose, ownerRef)`.
8. **Redacción:** con `L` el lado largo de la copia (480 px), cada bloque de lado `L/24` (20 × 20 px) **es de un solo color**, y un patrón más fino que ese bloque **no aparece** en la copia. Se comprueba con una imagen de entrada de rayas de 1 px alternando blanco y negro: en la copia ningún bloque contiene más de un color y no queda ninguna transición dentro de un bloque.
9. Sin publicar, el donante no la recibe por ninguna ruta (aserción negativa sobre todas las rutas públicas).
10. Publicar exige `ADMINISTRATOR` de la organización y la confirmación; `EMPLOYEE`, `REPRESENTATIVE`, otra organización y administrador de plataforma → 403.
10b. **Historial:** publicar, despublicar y volver a publicar deja tres entradas en `publicationDecisions`, en orden, y la primera conserva su `at`, su `by` y su confirmación (se compara con la copia leída antes). `PUT` sobre una ya publicada y `DELETE` sobre una no publicada no añaden entrada. El adaptador no expone ninguna operación que reemplace o borre entradas (se comprueba en su interfaz).
11. Publicada y después retirada por F7: original y copia sin bytes, publicación anulada con una entrada `UNPUBLISH` del administrador de plataforma, el donante ya no la recibe y el paso público no menciona la moderación.
12. Los originales de los pasos nunca aparecen en una respuesta pública.

**Común**
13. Golden path y `scripts/ci-local.sh` completos, en verde.

## Implementación (tras la aprobación; una tarea y un plan por PR)

| Orden | Tarea | Módulos |
|---|---|---|
| 1 | Versiones de esquema nuevas y `evidenceRef?` opcional en los tres comandos, con el gancho de vínculo | `core`, `api` |
| 2 | Subida de fotos de paso y lectura por paso | `app`, `api` |
| 3 | Copia censurada, publicación y retirada ampliada | `app` |
| 4 | Seguimiento público con la copia (ADR-021-D Enmienda 2) | `app`, `api` |
| 5 | Web: fotos de paso, ficha del lote, publicación, portada en el alta y E2-8 | web |
| 6 | Móvil: cámara opcional en despachar y recibir, y la copia en el seguimiento (con su enmienda de ADR-043) | móvil |
