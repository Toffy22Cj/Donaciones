# ADR-021-D — Enmienda 2: la copia censurada de la foto de entrega en el seguimiento público

## Status

**PROPUESTA — espera aprobación**, a la vez que la Enmienda 2 de ADR-051 (`ADR-051-enmienda-2-fotos-de-los-pasos-y-copia-para-el-donante.md`). Redactada el 2026-11-16 por Manuel, con asistencia de Claude, por su decisión del 2026-11-15: «el donante puede ver la foto de entrega, pero censurada y con aprobación». **[Falta: quién aprueba (Carlos o Manuel por delegación), fecha y medio.]**

**No autoriza código.** La Tarea 4 de la Enmienda 2 de ADR-051 lleva su plan.

**Enmienda a:** ADR-021-D (perímetro del seguimiento público; resumen en `documento-maestro-proyecto.md`) y su Enmienda 1 (`ADR-021-D-enmienda-1-huella-evidencia.md`).

## Context

- Hoy (Enmienda 1, E1 y E2): el paso `ASSET_DELIVERED` del historial público (`GET /donations/tracking/assets/{assetRef}/history`) lleva `evidenceRegistered` y, si es `sha256:`, `evidenceRef`. La imagen, cualquier URL de evidencia y los metadatos de `media_objects` **nunca** se exponen.
- E3 de la Enmienda 1: tras una retirada F7, el paso sigue igual y el seguimiento no dice nada de la moderación.
- La Enmienda 2 de ADR-051 crea la **copia censurada** de la foto de entrega (pixelada, otro objeto, otra huella, no anclada) y su **publicación**, que solo decide un `ADMINISTRATOR` de la organización.

## Decision

### E5. La copia publicada, en el paso de entrega

- El paso `ASSET_DELIVERED` del historial gana **`evidencePreviewUrl?`**, solo si se cumplen las tres condiciones:
  1. la copia censurada existe;
  2. está **publicada**;
  3. la foto original **no** está retirada por F7.

  En cualquier otro caso el campo **no aparece**. No hay un campo que diga «hay copia pero no está publicada».
- La URL apunta a `GET /api/v1/donations/tracking/assets/{assetRef}/evidence-preview`, que se autentica **como el resto del seguimiento**: el `trackingCode` en la cabecera `Authorization`, solo en `/donations/tracking/**`. Responde la copia (PNG) con `Cache-Control: private, no-store`. Sin copia publicada, con la original retirada o con un `trackingCode` que no cubre ese activo → **404**, el mismo en todos los casos.
- `PublicRoutes` ya cubre la ruta con `/api/v1/donations/tracking/**` (`api/.../auth/PublicRoutes.java:30`): no cambia. Se añade al test de inventario (`PublicRoutesInventoryIntegrationTest`).
- `logistics` de `GET /donations/tracking` **no cambia**.

### E6. Lo que sigue sin exponerse (amplía E2)

- La **foto original** de cualquier paso, por ninguna ruta pública.
- La copia **no publicada**, y cualquier señal de que existe.
- Las fotos de registrar, despachar y recibir y sus copias (no tienen copia en este corte; Enmienda 2 de ADR-051, Q-E2-4).
- La `objectKey`, `ownerRef`, `publishedBy`, `publishedAt`, la confirmación de quien publicó y cualquier otro metadato.
- La huella de la copia: el seguimiento sigue dando la huella de la **original** (`evidenceRef`), que es la anclada.

### E7. Retirada y despublicación

- Tras una retirada F7 o tras despublicar, el paso pierde `evidencePreviewUrl` y la ruta da 404. `evidenceRegistered` y `evidenceRef` siguen igual (Enmienda 1, E3).
- El seguimiento **no dice** si la copia se despublicó, se retiró o nunca se publicó.

### E8. Las huellas de los otros pasos — **pregunta Q-21D2-1**

La Enmienda 2 de ADR-051 sella la huella de las fotos opcionales de registrar, despachar y recibir. Hay que decidir si el seguimiento público las muestra:

| Opción | Efecto |
|---|---|
| (a) Sí, como en la entrega: `evidenceRegistered` y `evidenceRef` (solo `sha256:`) en esos pasos | Coherente con la entrega; la huella no revela nada sin la imagen (C2 (a) de ADR-051) |
| (b) No: solo la entrega | Menos cambios en el perímetro; las huellas quedan para la organización |

**Recomendación del agente: (a)**, por el mismo motivo que Carlos aprobó C2 (a). En los pasos sin foto, `evidenceRegistered: false`.

## Alternatives

| Alternativa | Por qué no |
|---|---|
| Incrustar la copia en la respuesta del historial (base64) | Respuestas grandes y la imagen viajaría en cada consulta; con una ruta aparte, el cliente la pide solo si la muestra |
| Ruta pública sin `trackingCode` (por `assetRef`) | El `assetRef` opaco aparece en el seguimiento, pero la foto solo debe verla quien sigue esa donación |
| Avisar al donante de que la foto «está en revisión» | Revela que existe una foto no publicada; E3 de la Enmienda 1 evita hablar de la moderación |

## Consequences

- El perímetro público gana una imagen **derivada y censurada**, siempre con decisión humana previa.
- Se añade una ruta al seguimiento; los clientes que ignoren `evidencePreviewUrl` siguen funcionando.
- `documento-maestro-proyecto.md`: nota fechada en ADR-021-D que remite a esta enmienda (Tarea D).

## Definición de hecho (tests, rojos primero)

1. Copia publicada → el paso lleva `evidencePreviewUrl` y la ruta devuelve la copia con el `trackingCode` correcto.
2. Sin publicar, despublicada o retirada por F7 → sin `evidencePreviewUrl`, y la ruta da el mismo 404 que con un `trackingCode` de otra donación (aserción negativa: el cuerpo es idéntico).
3. Ninguna respuesta pública contiene la original, su URL autenticada, la `objectKey` ni los datos de quien publicó.
4. `evidenceRef` sigue siendo la huella de la original, antes y después de publicar.
5. Si se aprueba Q-21D2-1 (a): los pasos de registrar, despachar y recibir con foto llevan su `evidenceRef`; sin foto, `evidenceRegistered: false`; un texto que no sea `sha256:` nunca aparece.
