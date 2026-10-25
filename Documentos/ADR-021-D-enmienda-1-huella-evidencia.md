# ADR-021-D — Enmienda 1: la huella de la evidencia de entrega en el seguimiento público (ADR-051 F6, C2 (a))

## Status

**BORRADOR — espera la aprobación de Manuel** (decisión 5 de Manuel del 2026-10-25, por delegación de Carlos: "T5 entra; antes se redacta la enmienda de ADR-021-D (C2 (a)) y la aprueba Manuel"; `decisiones-delegadas-2026-10.md`, nota del 2026-10-25, "comunicado por Manuel").

- **Qué** se enmienda ya está decidido: C2 (a) de ADR-051, aprobada por Carlos el 2026-10-18 (`ADR-051-fotos-y-almacenamiento.md:205`), y su F6 (`:131-133`).
- Este borrador fija el **cómo**: los puntos **P-21D-1 a P-21D-4**. Con la aprobación de Manuel deja de ser borrador.
- No autoriza código. El código de la Tarea 5 va con su sección de `plan-cierre-backend-2026-10-25.md`, aprobada aparte.

**Fecha:** 2026-10-25 (UTC). **Base verificada:** `develop` `0f71bfc`.

## Context

### Dónde está ADR-021-D

- ADR-021-D no tiene archivo propio en el repositorio. Su texto es el resumen de `documento-maestro-proyecto.md:172`: qué se excluye, qué se enmascara y qué se expone tal cual o transformado en el seguimiento público.
- **No menciona la evidencia de entrega.** Hasta la Tarea 4, `evidenceRef` era texto libre, que puede contener datos personales (`ADR-051-fotos-y-almacenamiento.md:39`), y ningún DTO público lo llevaba (`:38`).
- Esta enmienda se escribe como archivo propio, como las de otros ADR, y se cita desde el documento maestro con una nota fechada.

### Lo que ya decidió ADR-051

- F6 (`:131-133`): para un activo entregado, el seguimiento expone `evidenceRegistered: true`, y `evidenceRef` **solo** si tiene el formato `sha256:`. Un `evidenceRef` antiguo de texto libre **nunca** se expone; en ese caso, `evidenceRegistered: true` sin `evidenceRef`.
- F6: el donante **nunca** ve la imagen.
- F3 (Tarea 4, en `develop` desde #145): toda entrega nueva lleva `evidenceRef = sha256:<64 hex>` de los bytes limpios de una foto que el backend guardó.
- C2 (a): la huella no revela nada sin la imagen, y permite comprobar una foto si la organización la muestra.

### Hechos verificados (`develop` `0f71bfc`)

- Seguimiento público:
  - `PublicLogisticsItemDTO` (`api/.../dto/PublicLogisticsItemDTO.java:7-16`) es el ítem de `logistics` de `GET /donations/tracking`;
  - `PublicTransitionDTO` (`api/.../dto/PublicTransitionDTO.java:5-14`) es el paso de `GET /donations/tracking/assets/{assetRef}/history`;
  - ninguno de los dos lleva campos de evidencia.
- El historial público sale de la proyección `asset_history` (`MongoAssetHistoryReadAdapter.java:20-35`), que no guarda `evidenceRef`. La proyección `logistics` del fondo (`LogisticsReadItem.java:5-15`) tampoco.
- `evidenceRef` está en el payload de `ASSET_DELIVERED` (`AssetDeliveredPayload.java:14`), en el Event Store.

## Decision

### E1. Dónde se expone **[Manuel, P-21D-1]**

En el **paso `ASSET_DELIVERED` del historial** (`GET /donations/tracking/assets/{assetRef}/history`), que es el que corresponde al evento que lleva la huella. Cada paso de ese tipo gana:

| Campo | Cuándo llega | Contenido |
|---|---|---|
| `evidenceRegistered` | En todo paso `ASSET_DELIVERED` | `true` si el evento lleva un `evidenceRef` no vacío; `false` si es nulo o vacío **[P-21D-2]** |
| `evidenceRef?` | Solo si `evidenceRef` cumple `^sha256:[0-9a-f]{64}$` | El `evidenceRef` tal cual |

- Los demás pasos no llevan ninguno de los dos campos.
- `logistics` de `GET /donations/tracking` **no cambia**.

### E2. Qué nunca se expone (amplía la lista de exclusiones de ADR-021-D)

- Un `evidenceRef` que no cumpla el formato: texto libre heredado, con prefijo en mayúsculas, con espacios o con otra longitud. Se trata como texto que puede contener datos personales.
- La imagen, cualquier URL o ruta de la evidencia (incluida `GET /physical-assets/{assetRef}/evidence`), la `objectKey`, `boundAssetId`, `uploadedBy` y cualquier otro metadato de `media_objects`.

### E3. Tras una retirada por F7 **[Manuel, P-21D-3]**

- La huella está en el evento, que es inmutable (F7, `:138`). Tras la retirada, el paso **sigue** con `evidenceRegistered: true` y su `evidenceRef`.
- No se añade un campo de "retirada": el seguimiento público no dice nada de la moderación.

### E4. Hallazgo sobre el texto vigente de ADR-021-D **[Manuel, P-21D-4]**

- `documento-maestro-proyecto.md:172` lista `parentAssetRef` entre lo que **se excluye**.
- Desde la ficha R-6 (CONGELADA), G-4, implementado en #141, `logistics[]` de `GET /donations/tracking` lleva `parentAssetRef?`, que es el `assetRef` **opaco** (HMAC) del lote del que salió y solo si ese lote está en la misma `logistics` (`PublicLogisticsItemDTO.java:16`).
- Lo excluido en ADR-021-D es el identificador interno. Lo que expone G-4 es una referencia opaca con el mismo nombre. No parece una fuga, pero el texto de ADR-021-D no lo recoge.
- Esta enmienda **no lo resuelve**. Propone registrarlo con una nota fechada en el documento maestro.

## Alternatives

- **E1 en `logistics` en lugar del historial.** F6 dice "para un activo entregado", y `logistics` es por activo. Pero la proyección `logistics` no guarda `evidenceRef` y habría que ampliarla y reconstruirla. El paso del historial es el evento mismo.
- **Las dos.** Duplica el dato. Se puede añadir después sin romper nada.
- **Solo `evidenceRegistered`** (C2 (b)). Carlos eligió (a).

## Consequences

- El perímetro público gana un dato que no revela nada sin la imagen.
- Lo que el plan de T5 tiene que resolver: el historial sale de una proyección sin `evidenceRef`. La sección T5 del plan elige cómo leerlo, con los puertos que cambie marcados [Plan].
- Los clientes que ignoren los campos siguen funcionando: el cambio es aditivo.

## Definición de hecho (tests, rojos primero; los cubre el plan de T5)

1. Paso `ASSET_DELIVERED` con `evidenceRef = sha256:` válido → `evidenceRegistered: true` y el mismo `evidenceRef`.
2. Con texto libre heredado (`"acta-1"`) → `evidenceRegistered: true`, **sin** `evidenceRef`; el texto no aparece en ningún lugar del cuerpo de la respuesta.
3. Con `evidenceRef` nulo (eventos antiguos; el dominio lo admitía) → `evidenceRegistered: false`, sin `evidenceRef`.
4. Variantes casi válidas (`SHA256:…`, 63 o 65 hex, mayúsculas, espacios) → sin `evidenceRef`.
5. Los demás pasos no llevan ninguno de los dos campos; `logistics` no cambia.
6. Tras una retirada F7 → el paso sigue igual.
7. Ninguna respuesta pública contiene la `objectKey`, una URL de evidencia ni `boundAssetId`.

## Documentos que cambian al aprobarla

- `documento-maestro-proyecto.md`: nota fechada en ADR-021-D que remite a esta enmienda, y el hallazgo de E4 si Manuel lo acepta.
- `ADR-051-fotos-y-almacenamiento.md`: nota fechada en la tabla de documentos (`:255`) que da la enmienda por redactada y aprobada.
- `referencia-api-v1.md`: con el PR del código de T5.
