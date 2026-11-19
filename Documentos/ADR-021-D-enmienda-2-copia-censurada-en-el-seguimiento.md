# ADR-021-D — Enmienda 2: la copia censurada de la foto de entrega en el seguimiento público

## Status

**APROBADA — Manuel, por delegación de Carlos, 2026-11-18** (acordado en conversación con Carlos; registro en `decisiones-delegadas-2026-10.md`, sección «Firmas por delegación (2026-11-18)»), a la vez que la Enmienda 2 de ADR-051 (`ADR-051-enmienda-2-fotos-de-los-pasos-y-copia-para-el-donante.md`). Redactada el 2026-11-16 por Manuel, con asistencia de Claude, por su decisión del 2026-11-15: «el donante puede ver la foto de entrega, pero censurada y con aprobación».

Firma Carlos (cambia el perímetro público y el contrato de eventos de `core`). **Manuel dio su conformidad con las decisiones el 2026-11-18** y cerró la pregunta Q-21D2-1 con (a).

*Historial del estado.* PROPUESTA el 2026-11-16 (#183). **Revisada el 2026-11-18** por observaciones de Manuel, sin cambiar ninguna decisión: la forma de pintar la imagen sin pasar el `trackingCode` por la URL (E5b y E6), el límite de peticiones de la ruta (verificado, abajo) y, al leer `TrackingCodeAuthFilter`, la corrección de las respuestas de error de E5 (un código inválido es `401`, no `404`).

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
- La URL apunta a `GET /api/v1/donations/tracking/assets/{assetRef}/evidence-preview`, que se autentica **como el resto del seguimiento**: el `trackingCode` en la cabecera `Authorization: Bearer`, solo en `/donations/tracking/**` (`TrackingCodeAuthFilter.java:23-24` y `:37-38`). Responde la copia (PNG) con `Cache-Control: private, no-store`.
- **Respuestas de error**, copiando el comportamiento ya vigente del historial de activos:
  - `trackingCode` ausente o inválido → `401` (`TrackingCodeAuthFilter.java:48` y `:56`);
  - `trackingCode` válido pero que **no cubre** ese `assetRef` (no se resuelve entre los lotes del fondo, o no pertenece a él) → el **mismo `401`** uniforme, para no permitir enumerar activos (`PublicAssetHistoryController.java:63`, `:73`, `:79` y `:88`);
  - `trackingCode` que sí cubre el activo, pero **sin copia publicada, con la original retirada por F7 o sin foto** → `404` (`TrackedResourceNotFoundException`), **con el mismo cuerpo en los tres casos**: el seguimiento no dice cuál de ellos es (E7).
- `PublicRoutes` ya cubre la ruta con `/api/v1/donations/tracking/**` (`api/.../auth/PublicRoutes.java:30`): no cambia. Se añade al test de inventario (`PublicRoutesInventoryIntegrationTest`).
- `logistics` de `GET /donations/tracking` **no cambia**.

### E5b. Cómo se pinta la imagen (la cabecera no cabe en un `<img src>`)

Un `<img src>` no puede mandar la cabecera `Authorization`. Por eso:

- **Web:** la pide con `fetch(evidencePreviewUrl, { headers: { Authorization: 'Bearer <trackingCode>' } })`, convierte la respuesta en un `Blob` y la muestra con `URL.createObjectURL`. **Revoca** esa URL con `URL.revokeObjectURL` al desmontar el componente y al cambiar de paso. La web ya manda el `trackingCode` solo en esa cabecera (`PaxFide/src/lib/api/tracking.ts:9-13`, «nunca en la ruta ni en la query»); la imagen usa la misma función.
- **Móvil:** manda la cabecera `Authorization` con la imagen de red (`Image.network(url, headers: {...})` o el cliente HTTP de la app), con la misma cabecera que ya usa su cliente de seguimiento (`PaxFide_Mv/lib/features/tracking/data/tracking_api.dart:177-184`).

### E6. Lo que sigue sin exponerse (amplía E2)

- **El `trackingCode` nunca viaja en la URL de la imagen: ni en la ruta, ni en la consulta (`?code=…`), ni en el fragmento, ni dentro de `evidencePreviewUrl`.** Tampoco se acepta así «por comodidad» (por ejemplo, para poder usar `<img src>` directamente). Es la regla de ADR-043 §0 («seguimiento sin código en la URL») y de la web, aplicada a esta ruta. Si llega un `trackingCode` en la consulta, **se ignora** y no sustituye a la cabecera (la petición sin cabecera sigue siendo `401`).

- La **foto original** de cualquier paso, por ninguna ruta pública.
- La copia **no publicada**, y cualquier señal de que existe.
- Las fotos de registrar, despachar y recibir y sus copias (no tienen copia en este corte; Enmienda 2 de ADR-051, Q-E2-4).
- La `objectKey`, `ownerRef`, `publishedBy`, `publishedAt`, la confirmación de quien publicó y cualquier otro metadato.
- La huella de la copia: el seguimiento sigue dando la huella de la **original** (`evidenceRef`), que es la anclada.

### E7. Retirada y despublicación

- Tras una retirada F7 o tras despublicar, el paso pierde `evidencePreviewUrl` y la ruta da 404. `evidenceRegistered` y `evidenceRef` siguen igual (Enmienda 1, E3).
- El seguimiento **no dice** si la copia se despublicó, se retiró o nunca se publicó.

### E8. Las huellas de los otros pasos — **Q-21D2-1: decidida (a)**

La Enmienda 2 de ADR-051 sella la huella de las fotos opcionales de registrar, despachar y recibir. **Decisión de Manuel, 2026-11-18: (a)** — el seguimiento público las muestra como la de la entrega: en esos pasos, `evidenceRegistered` y `evidenceRef` (solo si es `sha256:`). En los pasos sin foto, `evidenceRegistered: false` y sin `evidenceRef`. Es el mismo motivo por el que Carlos aprobó C2 (a) de ADR-051: la huella no revela nada sin la imagen.

Se descartó (b), mostrar solo la entrega.

## Límite de peticiones de la ruta nueva — verificado el 2026-11-18 sobre `develop` `bcaa5dc`

**La ruta `…/evidence-preview` no queda hoy bajo ningún límite de peticiones**, y tampoco el resto de `/donations/tracking/**`. Evidencia:

- En `develop` ningún fichero de `*/src/main` contiene `429`, `TOO_MANY_REQUESTS` ni `Retry-After` (búsqueda sobre todos los módulos: 0 ficheros).
- Los únicos filtros de la cadena de seguridad son `JwtAuthFilter` (`api/.../auth/jwt/JwtAuthFilter.java:37`) y `TrackingCodeAuthFilter` (`api/.../infrastructure/security/TrackingCodeAuthFilter.java:20`); ninguno cuenta peticiones.
- `PublicRoutes.java:30` solo declara `/api/v1/donations/tracking/**` como pública (`Access.PUBLIC`); no limita nada.
- ADR-051, riesgo residual 3 (`ADR-051-fotos-y-almacenamiento.md:248`): «Las rutas públicas de imágenes no tienen límite de peticiones (sigue abierto DH-56)».

**Lo que cambiaría con ADR-053**, que sigue **PROPUESTO y sin fusionar** en la rama `claude/festive-newton-687sd0`: su grupo `TRACKING` cubre `route("*", "/api/v1/donations/tracking/**")` (`api/.../web/ratelimit/RateLimitGroup.java:23`, en esa rama), con 120 peticiones por minuto y por IP. Esa ruta ya incluye la nueva, así que, si ADR-053 se fusiona, la cubre **sin cambios** de esta enmienda.

**Consecuencia y riesgo residual:** hasta que ADR-053 entre, la ruta hereda la exposición de DH-56: quien pruebe `trackingCode` por fuerza bruta puede hacerlo también contra ella. No empeora el caso: sin un `trackingCode` válido que cubra el activo, la respuesta es el mismo `401` de las demás rutas del seguimiento, y la imagen que se sirve es una copia pixelada de unos 480 px.

**Dependencia (decisión de Manuel, 2026-11-18; Carlos la confirma al firmar).** El `trackingCode` es un HMAC completo y no se puede adivinar, así que el riesgo no es la fuerza bruta sino el **coste**: quien tenga un código válido puede pedir la imagen sin parar y cada petición lee del almacenamiento. **La Tarea 4 de la Enmienda 2 de ADR-051 (esta ruta) no se fusiona antes de ADR-053 o de otro límite de peticiones aprobado.** Las tareas 1 a 3 de esa enmienda no dependen de esto.

## Alternatives

| Alternativa | Por qué no |
|---|---|
| Incrustar la copia en la respuesta del historial (base64) | Respuestas grandes y la imagen viajaría en cada consulta; con una ruta aparte, el cliente la pide solo si la muestra |
| Ruta pública sin `trackingCode` (por `assetRef`) | El `assetRef` opaco aparece en el seguimiento, pero la foto solo debe verla quien sigue esa donación |
| Pasar el `trackingCode` en la URL (ruta o consulta) para poder usar `<img src>` | Deja la credencial en el historial del navegador, en los registros de acceso y en cabeceras `Referer`; ADR-043 §0 y la web ya lo prohíben (E6) |
| Avisar al donante de que la foto «está en revisión» | Revela que existe una foto no publicada; E3 de la Enmienda 1 evita hablar de la moderación |

## Consequences

- El perímetro público gana una imagen **derivada y censurada**, siempre con decisión humana previa.
- Se añade una ruta al seguimiento; los clientes que ignoren `evidencePreviewUrl` siguen funcionando.
- `documento-maestro-proyecto.md`: nota fechada en ADR-021-D que remite a esta enmienda (Tarea D).

## Definición de hecho (tests, rojos primero)

1. Copia publicada → el paso lleva `evidencePreviewUrl` y la ruta devuelve la copia con el `trackingCode` correcto.
2. Sin publicar, despublicada o retirada por F7 → sin `evidencePreviewUrl`, y la ruta da el mismo `404` en los tres casos y en el de una foto inexistente (aserción negativa: los cuerpos son idénticos). Con un `trackingCode` que no cubre el activo → el mismo `401` que da el historial de activos (aserción negativa: nunca `404`).
3. Ninguna respuesta pública contiene la original, su URL autenticada, la `objectKey` ni los datos de quien publicó.
4. `evidenceRef` sigue siendo la huella de la original, antes y después de publicar.
5. Q-21D2-1 (a): los pasos de registrar, despachar y recibir con foto llevan su `evidenceRef`; sin foto, `evidenceRegistered: false`; un texto que no sea `sha256:` nunca aparece.
6. **`trackingCode` en la consulta** (`?code=…`, `?trackingCode=…`) sin cabecera → `401`; con cabecera válida y además en la consulta, la consulta se ignora (aserción negativa: ninguna respuesta ni registro contiene el código).
7. La web pide la imagen con `fetch` y la cabecera, la muestra con `URL.createObjectURL` y la revoca al desmontar (test de la pantalla: `revokeObjectURL` se llama; el `src` de la imagen no contiene el código).
