# Plan — Fotos, Tarea 5: web (fotos de paso, copia censurada y portada) (2026-11-19)

**Estado: PROPUESTO.** Espera un «plan aprobado» de Manuel que se pueda citar (regla 3.4). **Sin código hasta entonces.**

**Norma:** ADR-051 Enmienda 2 (APROBADA — Manuel, por delegación de Carlos, 2026-11-18): E2-1 (fotos opcionales), E2-5 y E2-6 (publicación y quién ve qué) y E2-8 (comodidad del usuario). ADR-046 D8: **sin dependencias nuevas**. Encargo de Manuel del 2026-11-19.

**Repositorio:** `Toffy22Cj/PaxFide` (web), base `develop` `f6d350e` (2026-11-19; incluye #69 y #70). Rama de código: `feat/web-fotos-de-los-pasos`. **Empieza cuando la Tarea 3 del backend esté fusionada** (que a su vez espera a la Tarea 2); la copia en el seguimiento del donante (**Tarea 4**) queda **fuera**.

## 0. Alcance

**Dentro:**
1. Foto **opcional** al registrar (formulario de registro), despachar y recibir (ficha del lote), con vista previa, arrastrar y soltar, barra de progreso, mensajes de 413/415/503 y cambiar o quitar la foto antes de confirmar el paso.
2. En la **ficha del lote** (`/assets/[assetRef]`), una sección «Pasos y fotos» que muestra, por cada paso, su foto original a los miembros de la organización.
3. Para el `ADMINISTRATOR`: la copia censurada de la entrega, el control de publicación con la confirmación explícita y el historial de decisiones.
4. La **portada** como paso final opcional al crear la convocatoria y desde el listado `/panel/campaigns`, solo para el `ADMINISTRATOR`.
5. Corregir el aviso de `/panel/assets`.

**Fuera:** el seguimiento público (Tarea 4), el móvil (Tarea 6), y cualquier cambio de contrato del backend.

## 1. Hechos verificados (web, `develop` `f6d350e`)

| # | Hecho | Dónde |
|---|---|---|
| H-1 | Ya existe la subida de la evidencia de entrega con `XMLHttpRequest` y progreso, con errores con nombre (`TooLarge`, `UnsupportedFormat`, `Removed`, `StorageUnavailable`, `Forbidden`, `Unauthorized`, `Failed`) y su texto por error (U-3); 5 MB, JPEG o PNG | `src/lib/api/deliveryEvidence.ts` |
| H-2 | Ya existe el patrón de «subir la foto, después mandar el comando con su `evidenceRef`», con un reintento por ambigüedad que repite la **misma** petición sin volver a subir | `src/components/asset/DeliverModal.tsx` (cabecera del fichero) |
| H-3 | `ImageEditor` ya hace vista previa, progreso y «Quitar» con confirmación sin diálogo del navegador, **pero con el límite de 2 MB** del logo y la portada; **no hay arrastrar y soltar** en ningún componente (`grep onDrop|dragover|DataTransfer` sobre `src`: 0) | `src/components/media/ImageEditor.tsx` |
| H-4 | `PrivateImage` pide imágenes con JWT por `fetch`, las muestra con `URL.createObjectURL` y revoca al desmontar; con estados `loading`, `ok`, `not-found`, `removed`, `forbidden`, `error` | `src/components/media/PrivateImage.tsx` |
| H-5 | La ficha del lote (`/assets/[assetRef]`) muestra los 7 campos del modelo de lectura y las acciones; **no tiene lista de pasos**; la foto de la entrega se ve solo si el activo está entregado | `src/app/assets/[assetRef]/page.tsx:47-77` |
| H-6 | Despachar y recibir son `CommandModal` con campos declarados en `AssetActions`; la entrega usa su propio `DeliverModal` | `src/components/asset/AssetActions.tsx:44-62,99-116` |
| H-7 | La portada ya se edita en la configuración de la convocatoria con `CampaignCoverCard` (solo `ADMINISTRATOR`); **no** se ofrece al crearla ni en el listado | `src/components/media/CampaignCoverCard.tsx`; `src/components/campaign/CreateCampaignForm.tsx`; `src/screens/CampaignsScreen.tsx:434-441` |
| H-8 | El aviso a corregir: «Despachar y recibir se hacen escaneando el QR en la app móvil.» contradice los botones de despachar y recibir de la ficha del lote | `src/screens/OrgAssetsScreen.tsx:280` |
| H-9 | El servidor falso de los e2e es `scripts/fake-backend.js` (rutas de la evidencia de entrega en `:1442` y `:1958`); las capturas contra el backend simulado usan `playwright.capturas.config.ts` | repositorio web |

## 2. Contratos que consume (del backend; Tareas 2 y 3, aún sin fusionar)

Este plan **no cambia ningún contrato**: usa los de los planes de las Tareas 2 y 3. Si cambian al aprobarse esos planes, este se ajusta antes de empezar.

- Subida: `POST /api/v1/organizations/{organizationId}/asset-evidence` (multipart `file` y `step` = `REGISTERED|DISPATCHED|RECEIVED`) → `201 {evidenceRef}`.
- Comandos con `evidenceRef` opcional: `POST /physical-assets/register`, `/from-donation`, `/{assetRef}/dispatch`, `/{assetRef}/receive` (ya en `develop`, #187).
- Lectura: `GET /physical-assets/{assetRef}/evidence/{step}` (original) y `GET …/evidence/steps` (listado: `[{step, sequence, occurredAt, hasPhoto, removed}]`).
- Copia: `GET …/evidence/redacted`, `PUT` y `DELETE …/evidence/redacted/publication`. **Para mostrar el historial de decisiones al `ADMINISTRATOR`, la lectura necesita exponerlo**: ver Q-T5-1.

## 3. Diseño

### 3.1 Piezas compartidas (sin dependencias)

- `src/lib/api/stepEvidence.ts`: `uploadStepEvidence(organizationId, step, file, onProgress)` con `XMLHttpRequest`, **reutilizando** `checkEvidenceFile`, `EvidenceError` y `evidenceErrorText` de `deliveryEvidence.ts` (que no cambia). El texto de `Forbidden` («Tu cuenta no puede registrar entregas…») es el de la entrega: se añade un parámetro de paso para que diga lo que corresponde.
- `src/components/media/PhotoPicker.tsx` (nuevo): una zona para elegir o **arrastrar y soltar** una imagen (`onDragOver`, `onDrop` con `DataTransfer.files`, y el `<input type="file" accept="image/jpeg,image/png" capture>` para el móvil), **vista previa** con `URL.createObjectURL` revocada al cambiar y al desmontar, mensajes de `TooLarge` y `UnsupportedFormat` **antes de subir**, y «Cambiar» y «Quitar» **sin salir de la pantalla**. No sube: devuelve el `File` elegido; la subida la hace quien lo usa, en el momento de confirmar.
- `src/components/asset/StepPhotoField.tsx`: `PhotoPicker` + subida con barra de progreso + mensajes de 413, 415 y 503 (los de U-3) + el `evidenceRef` ya subido, para que un reintento por ambigüedad **no vuelva a subir** (patrón de H-2).

### 3.2 Fotos opcionales en registrar, despachar y recibir (E2-1, E2-8)

- El formulario de registro (`RegisterAssetForm`) y los modales de despachar y recibir ganan un campo «Foto (opcional)» con `StepPhotoField`. **Sin foto**, el comando se manda como hoy (sin `evidenceRef`; aserción negativa en los tests). **Con foto**: primero se sube (`asset-evidence`), después se manda el comando con `evidenceRef`.
- Si la subida falla, la persona **elige**: reintentar, o **seguir sin foto** (nunca se bloquea el paso, E2-3).
- Cambiar o quitar la foto solo es posible **antes de confirmar el paso**: una vez escrito el evento, su huella está sellada (E2-2) y la ficha no ofrece cambiarla. Se dice en pantalla.
- `CommandModal` recibe un hueco de campo adicional (hoy solo admite campos de texto y municipio, `AssetActions.tsx:44-62`); el cambio es aditivo.

### 3.3 La ficha del lote: «Pasos y fotos» (E2-6)

- Una sección nueva en `/assets/[assetRef]` que llama a `…/evidence/steps` y, por cada paso con foto, muestra la miniatura con `PrivateImage` (`…/evidence/{step}`) y un enlace para verla grande. Visible para los miembros de la organización con cualquier rol y el administrador de plataforma, **nunca** en una pantalla pública.
- Estados con nombre (reutilizando los de `PrivateImage`): cargando, sin foto, **retirada por la plataforma** («La plataforma retiró esta foto.», 410), sin permiso y error con reintento. Texto de «sin foto» sin culpar a nadie.
- Con varias fotos del mismo paso (re-despacho): se muestra cada una con su `sequence` y su fecha (Q-T2-1 (a) de la Tarea 2).

### 3.4 La copia censurada y la publicación, solo para el `ADMINISTRATOR`

- En la misma sección, para el `ADMINISTRATOR` de la organización y solo en el paso de la entrega: la **copia censurada** (`PrivateImage` sobre `…/evidence/redacted`) con un texto fijo que explica que es una vista pixelada, que **no sirve para leer ni verificar a simple vista**, y que es lo único que el donante vería **solo si él la publica**.
- **Control de publicación:** un interruptor «Publicar para el donante» que abre una confirmación con una casilla **explícita**, sin marcar de inicio: «Revisé la copia y no se reconoce a ninguna persona». «Publicar» queda deshabilitado hasta marcarla. `PUT` con `{confirmNoIdentifiablePerson: true}`; «Despublicar» con `DELETE`, con confirmación sin diálogo del navegador.
- **Historial de decisiones** (quién, cuándo, publicó o despublicó), solo lectura, en orden: la lista que solo crece (E2-5) (Q-T5-1).
- Para `EMPLOYEE`, `REPRESENTATIVE` y plataforma: la copia se ve (E2-6) pero **sin** el control de publicar ni la casilla; el texto dice quién puede publicar. Un `403` del backend nunca se muestra como éxito.

### 3.5 Portada (E2-8)

- Al crear la convocatoria: tras el `201`, un **paso final opcional** «Portada» con `CampaignCoverCard` (la misma pieza de la configuración, H-7), con «Saltar» y «Listo»; solo para el `ADMINISTRATOR` (C1 (a)). El comportamiento tras el éxito (enlace público y QR) no cambia.
- En `/panel/campaigns`: una acción «Portada» por convocatoria, solo para el `ADMINISTRATOR`, que abre la misma tarjeta. En el móvil, no (decisión del 2026-11-16).
- Arrastrar y soltar también en la portada y el logo: `ImageEditor` pasa a usar `PhotoPicker` (Q-T5-2).

### 3.6 El aviso de `/panel/assets` (H-8)

El texto «Despachar y recibir se hacen escaneando el QR en la app móvil.» pasa a algo cierto: «Despachar, recibir y entregar se hacen desde la ficha de cada lote o escaneando su QR en la app móvil.» (Q-T5-3). Test que lo ancla: el texto menciona la ficha del lote y la ficha ofrece esos botones.

## 4. Tests (rojos primero; vitest y Playwright)

**A. Lógica y piezas** (vitest)
1. `stepEvidence`: URL y campo `step` por paso; cabecera `Authorization` solo con sesión; **nunca** el JWT en la URL; mapeo 413/415/503/403/401/410 a `EvidenceError`.
2. `PhotoPicker`: elegir y arrastrar y soltar dan el mismo `File`; un archivo de otro tipo o de más de 5 MB se rechaza **antes** de subir; la vista previa se revoca al cambiar y al desmontar (`URL.revokeObjectURL`, aserción de llamada).
3. `StepPhotoField`: una subida correcta devuelve el `evidenceRef`; un reintento tras ambigüedad **no vuelve a subir** (`upload` llamado una vez); «Seguir sin foto» tras un fallo.

**B. Comandos** (vitest con cliente simulado)
4. **Sin foto no se sube nada** (`never()` sobre el cliente de subida) y el cuerpo del comando **no lleva `evidenceRef`**, en registrar, despachar y recibir.
5. Con foto: primero la subida, después el comando con su `evidenceRef`; si la subida falla, el comando **no se manda** hasta que la persona elige seguir sin foto.
6. Mensajes de 413, 415 y 503 con su texto (cada uno el suyo); 410 («retiró esta foto»).

**C. Ficha del lote**
7. La sección muestra, por paso, su foto; con un paso sin foto no muestra imagen ni error; con la retirada muestra el texto de retirada y **no** la imagen.
8. Un `EMPLOYEE` y un `REPRESENTATIVE` ven la copia pero **no** el control de publicar ni la casilla (aserción negativa: el botón y la casilla no están en el DOM).
9. El `ADMINISTRATOR`: «Publicar» deshabilitado hasta marcar la casilla; con la casilla, `PUT` con `{confirmNoIdentifiablePerson: true}` (aserción: el cuerpo exacto); «Despublicar» con `DELETE`; el historial muestra las entradas en orden.
10. Un `403` o un `409 RedactedCopyNotGenerated` del backend no se muestran como éxito.

**D. Portada y aviso**
11. Tras crear una convocatoria, el `ADMINISTRATOR` ve el paso de la portada con «Saltar»; otro rol **no** lo ve (aserción negativa); «Saltar» no hace ninguna petición de portada (`never()`).
12. `/panel/campaigns` ofrece «Portada» solo al `ADMINISTRATOR`.
13. El aviso de `/panel/assets` ya no dice que solo se hace en el móvil.

**E. e2e** (Playwright, servidor falso ampliado para que **imite** el contrato de las Tareas 2 y 3)
14. Registrar con foto, despachar sin foto y recibir con foto de punta a punta; la sección «Pasos y fotos» muestra solo las que hay.
15. `ADMINISTRATOR`: abre la copia, publica con la casilla, despublica y vuelve a publicar: el historial muestra tres entradas.
16. 390 px: sin scroll horizontal en la ficha con la sección de fotos (el mismo criterio de #70, medido con las animaciones detenidas si las hay).

**F. Capturas por rol con datos reales** (no son tests de CI; evidencia del PR)
- Contra **el backend real** con `dev,demo-seed,demo-historico` (no el servidor falso), con lotes que llevan fotos de paso de la semilla (Tarea 2, §2.5): una captura por rol (`ADMINISTRATOR`, `EMPLOYEE`, `REPRESENTATIVE`) y por estado (sin foto, con foto, retirada). **Cada captura dice de dónde sale:** endpoint, commit del backend y de la web, y la semilla `demo-historico` (regla de procedencia). Sin credenciales en ningún fichero ni log.

**Mutaciones:** mostrar el control de publicar a `REPRESENTATIVE` (test 8); mandar `evidenceRef: null` o vacío en lugar de omitirlo (test 4); subir sin esperar la elección tras un fallo (test 5); no revocar la URL de la vista previa (test 2); poner el JWT en la URL (test 1).

## 5. Preguntas para Manuel (con mi recomendación)

- **Q-T5-1 — Historial de decisiones en la web.** El contrato de las Tareas 2 y 3 no define cómo la web lee `publicationDecisions`. (a) **Recomendada:** que la Tarea 3 lo exponga **solo al `ADMINISTRATOR`** en una cabecera de la lectura o en una ruta `GET …/evidence/redacted/publication` que devuelva `{published, decisions[]}` sin bytes; sin `by` en claro para otros roles. Es un cambio del plan de la Tarea 3 (una ruta más), que **hay que aprobar antes** de que esa tarea empiece. (b) La web muestra solo «Publicada/No publicada» y no el historial. **Recomiendo (a)**, porque E2-5 dice que «queda registrado quién y cuándo» y esa promesa solo vale si alguien puede leerlo.
- **Q-T5-2 — Arrastrar y soltar en la portada y el logo.** (a) **Recomendada:** `ImageEditor` usa el mismo `PhotoPicker` (con su tope de 2 MB por propósito, parametrizado). (b) Solo las fotos de paso. **Recomiendo (a)**: E2-8 lo pide para la portada y el logo comparte el componente.
- **Q-T5-3 — Texto del aviso de `/panel/assets`.** El propuesto en §3.6. Cambia solo si lo apruebas.
- **Q-T5-4 — Foto en el formulario de registro.** (a) **Recomendada:** la foto se sube antes de confirmar y el `evidenceRef` va en el cuerpo (como la entrega). (b) Subirla después del registro y vincularla en otro paso: no existe en el contrato. Solo (a).
- **Q-T5-5 — Dónde vive «Pasos y fotos».** (a) **Recomendada:** una sección nueva en la ficha `/assets/[assetRef]`. (b) Una pantalla aparte. **Recomiendo (a)**, que es donde E2-6 la pone.
- **Q-T5-6 — Fotos del servidor falso.** El servidor falso tiene que imitar el contrato; si el real cambia, el falso se desactualiza. (a) **Recomendada:** las capturas y una pasada de e2e contra el **backend real** como comprobación final del PR (ya son parte de F). (b) Solo el falso. **Recomiendo (a).**

## 6. Riesgos

1. **Contrato aún no fusionado:** este plan depende de los de las Tareas 2 y 3; si cambian, se ajusta antes de empezar.
2. **Foto subida y no usada** (el comando falla tras subir): queda huérfana (Q-E2-6 (a)); el reintento reutiliza la misma `evidenceRef` y no sube otra.
3. **La copia censurada puede dar falsa seguridad.** Por eso el texto fijo y la casilla explícita (riesgo residual 1 de ADR-051 Enm. 2).
4. **Privacidad en las capturas:** solo con datos de la semilla `demo-historico` (sintéticos); sin credenciales ni correos reales en ficheros, commits ni logs.
5. **Cambio de `ImageEditor`** (Q-T5-2): toca el logo y la portada ya en producción; su suite de tests actual debe seguir en verde sin cambios.

## 7. Definición de hecho

Tests 1 a 16 en verde con el rojo previo visible y las mutaciones muertas; `scripts/ci-local.sh` del repositorio web **completo** (Vitest, comprobación de importaciones de servidor y Playwright) con la salida literal; capturas por rol con su procedencia (endpoint, commit y semilla) adjuntas al PR; sin dependencias nuevas (`package.json` y `pnpm-lock.yaml` sin cambios); el texto del aviso corregido. PR hacia `develop` del repositorio web; no se fusiona sin tu aprobación.
