# Ficha R-6 — Enmienda 2: `truncated` en la actividad reciente de la organización (G-8)

**Estado: PROPUESTA.** **Qué** cambia lo decidió Manuel el 2026-10-25, por delegación de Carlos (decisión 3, `decisiones-delegadas-2026-10.md`, nota del 2026-10-25, "comunicado por Manuel"). Los puntos marcados **[Manuel]** son de detalle y se aprueban con el "plan aprobado" de `plan-cierre-backend-2026-10-25.md` (§G-8).

> **Nota (2026-10-25): APROBADA por Manuel**, por delegación de Carlos (comentario de `buelvasmanuel` en #147, 2026-10-25T22:35:54Z, https://github.com/Toffy22Cj/Donaciones/pull/147#issuecomment-6404356890; registrado en `decisiones-delegadas-2026-10.md`). El estado de arriba se conserva; esta nota lo supera. P-E2-1: siempre. P-E2-2: aceptado en este corte, como deuda; no se cambian los puertos de los directorios.

> **Nota (2026-10-25): implementada** en `feat/r6-g8-truncated` (`fef7e6c`; `estado-fase6.md` §0.62). La aprobación de Manuel (#147, comentario 6404356890) se registra en #148.
**Fecha:** 2026-10-25 (UTC).
**Base verificada:** `develop` en `0f71bfc`.
**Cambia:** la respuesta de G-8 de la ficha R-6 (CONGELADA), `ficha-R6-datos-estilo-v2.md:144`, ya precisada por la nota de P-R6D-1 (b) (`:154`). El resto de la ficha no cambia.
**Nombre:** el documento de cierre de Manuel la llama "enmienda E2". `ficha-R6-enmienda-1.md:77` ya usa "E2" para G-9, así que esta va en un archivo propio como **Enmienda 2**.
**Origen:** pregunta del Bloque D (`estado-fase6.md` §0.51, nota del 2026-10-25; comentario en #143): G-8 tenía un límite sin `truncated`.

---

## 1. Por qué

- G-8 lee como mucho 10 000 fondos y 10 000 activos de la organización (`OrganizationActivityQuery.java:61` y `:126-127`), igual que G-7 (`OrganizationIntegrityQuery.java:37`).
- G-7 avisa con `truncated` cuando se pasa (`OrganizationIntegrityQuery.java:64-66`; ficha `:134`), y también el recaudado por mes de G-6 (`truncated?`, ficha `:89`).
- G-8 no avisaba: una organización con más de 10 000 fondos o activos recibía una actividad incompleta **sin saberlo**. Es el "contador silenciosamente incompleto" que C-5 prohíbe para G-7 (ficha `:136`).

## 2. Contrato (aditivo)

`GET /api/v1/organizations/{organizationId}/activity?cursor=`. La respuesta gana `truncated`:

`200 {items: [...], nextCursor?, truncated}`

| Campo | Cuándo llega | Contenido |
|---|---|---|
| `truncated` | Siempre, en todas las páginas **[Manuel, P-E2-1]** | `true` si la organización tiene más de 10 000 fondos **o** más de 10 000 activos; si no, `false` |

- Los ítems, su orden, el cursor y lo que nunca sale (ficha `:148-149`) **no cambian**.
- Un cliente que ignore el campo sigue funcionando.

## 3. Criterio (el de G-7)

- Se leen `DIRECTORY_LIMIT + 1` identificadores de cada directorio. `truncated` es verdadero si alguno de los dos devuelve más de `DIRECTORY_LIMIT`, y solo se usan los primeros `DIRECTORY_LIMIT` de cada uno. Es lo que hace `OrganizationIntegrityQuery.java:64-68`.
- Se calcula en cada página, porque cada página vuelve a leer los directorios. Con los directorios sin cambios, todas las páginas dan el mismo valor.
- **Sin puertos nuevos ni cambiados:** `FundDirectoryPort` y `AssetDirectoryPort` ya reciben el límite.

## 4. Consecuencia que hay que saber (verificada en el código)

- Los dos directorios devuelven los streams **en orden ascendente de su génesis** (`MongoFundDirectoryAdapter.java:30` y `MongoAssetDirectoryAdapter.java:27`, `Sort.by("recordedAt", "streamId")`).
- Por eso, con más de 10 000, G-8 se queda con los 10 000 fondos y activos **más antiguos**. La actividad de los más **nuevos** no sale, aunque G-8 se lee de lo más reciente a lo más antiguo.
- `truncated: true` lo avisa, pero no lo arregla. Leer los más recientes exigiría un orden descendente en los directorios, es decir, **cambiar puertos que ya usa G-7** (regla 3.5). Esta enmienda no lo hace (**P-E2-2**).
- **Nota (2026-10-26): P-E2-2, pagada en este corte (pendiente).** D-3 de Manuel (`decisiones-delegadas-2026-10.md`, nota del 2026-10-26) manda que G-8 muestre lo más reciente con más de 10 000, con un método nuevo de lectura descendente en los directorios (autorización §3.5). Los métodos de hoy y G-7 no cambian. El contrato está en `ficha-R6-enmienda-3.md`, y el plan en `plan-deudas-1-a-3-2026-10-26.md` §3; los dos esperan la aprobación de Manuel. El texto de arriba se conserva.
- **Nota (2026-10-26): P-E2-2, pagada** en `feat/r6-g8-descendente` (Enmienda 3; `estado-fase6.md` §0.68).

## 5. Puntos para Manuel

| # | Pregunta | Recomendación |
|---|---|---|
| **P-E2-1** | ¿`truncated` siempre, o solo cuando es `true` (`truncated?`, como G-6)? | **Siempre**, como G-7, el criterio que pide la decisión 3 |
| **P-E2-2** | ¿Se acepta que, con más de 10 000, G-8 muestre la actividad de los streams más antiguos (§4)? | **Sí en este corte**, con `truncated: true`. Ninguna organización de la demo se acerca a 10 000. Leer los más recientes sería un cambio de puerto con su propio plan |

## 6. Tests (rojos primero)

1. Una organización con 10 001 activos → `truncated: true` en la primera página y en la siguiente.
2. Una con 10 001 fondos → `truncated: true`.
3. Una con exactamente 10 000 de cada uno → `truncated: false` (borde).
4. Una organización pequeña → `truncated: false`; los ítems y el cursor no cambian (los tests 11a a 11f del Bloque D siguen en verde).
5. Por HTTP: el campo está en las tres formas de la respuesta (con `nextCursor`, sin él y con `items` vacío).

## 7. Documentos que se actualizan con el PR del código

- `ficha-R6-datos-estilo-v2.md`: nota fechada en G-8 que remite a esta enmienda (ya en el PR de documentos).
- `referencia-api-v1.md`: nota fechada en la ruta de G-8.
- `estado-fase6.md`: sección del PR, en la serie 0.60 a 0.69.
