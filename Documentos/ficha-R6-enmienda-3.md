# Ficha R-6 — Enmienda 3: con más de 10 000, la actividad reciente (G-8) muestra lo más reciente

**Estado: PROPUESTA.** **Qué** cambia lo decidió Manuel el 2026-10-26, por delegación de Carlos (D-3, `decisiones-delegadas-2026-10.md`, nota del 2026-10-26, "comunicado por Manuel"). Los puntos de detalle (P-PE2-1 a 3) se aprueban con el "plan aprobado" de `plan-deudas-1-a-3-2026-10-26.md` (§3).

> **Nota (2026-10-26): APROBADA por Manuel**, por delegación de Carlos (comentario de `buelvasmanuel` en #153, 2026-10-26T05:48:54Z, https://github.com/Toffy22Cj/Donaciones/pull/153#issuecomment-6409194078; registrado en `decisiones-delegadas-2026-10.md`). El estado de arriba se conserva; esta nota lo supera. P-PE2-1, P-PE2-2 y P-PE2-3, como se proponen. Se autorizan (§3.5) `findLatestFundIdsByOrganization` y `findLatestAssetIdsByOrganization`.

**Fecha:** 2026-10-26 (UTC).
**Base verificada:** `develop` en `269e047`.
**Cambia:** la respuesta de G-8 de la ficha R-6 (CONGELADA, `ficha-R6-datos-estilo-v2.md:4`), en `ficha-R6-datos-estilo-v2.md:141-154`, ya precisada por P-R6D-1 (b) (`:153-155`) y por la Enmienda 2 (`ficha-R6-enmienda-2.md`). El resto de la ficha no cambia.
**Origen:** P-E2-2 (`ficha-R6-enmienda-2.md:45` y `:52`), aceptada el 2026-10-25 como deuda y pagada ahora por D-3.

---

## 1. Por qué

- Los directorios devuelven los streams en orden **ascendente** de su génesis: `MongoFundDirectoryAdapter.java:30` y `MongoAssetDirectoryAdapter.java:27`.
- Con más de 10 000 fondos o activos, G-8 se queda con los 10 000 **más antiguos** (`OrganizationActivityQuery.java:131-135`; `ficha-R6-enmienda-2.md:43-45`).
- Una lista "de lo más reciente a lo más antiguo" (`ficha-R6-datos-estilo-v2.md:144`) no debería dejar fuera justamente lo más reciente.

## 2. Contrato

`GET /api/v1/organizations/{organizationId}/activity?cursor=`. **La forma de la respuesta no cambia** (Enmienda 2: `200 {items, nextCursor?, truncated}`).

| Caso | Antes | Con esta enmienda |
|---|---|---|
| 10 000 fondos y 10 000 activos o menos | todos los streams | **igual**: los mismos ítems, las mismas páginas, el mismo cursor |
| Más de 10 000 fondos **o** activos | la actividad de los 10 000 **más antiguos** de cada directorio, por su génesis; `truncated: true` | la de los 10 000 **más recientes** de cada directorio, por su génesis **[P-PE2-1]**; `truncated: true` |

- "Más recientes" se mide por la **génesis** del stream: el `recordedAt` de su secuencia 1, igual que el orden de hoy pero al revés.
  - Un stream antiguo con actividad nueva, que quede detrás de 10 000 más nuevos, no aparece. `truncated: true` lo avisa.
- `truncated`, el orden total `(instante descendente, tipo de fuente, id)`, el cursor y lo que nunca sale (`ficha-R6-datos-estilo-v2.md:148-149`) **no cambian**.
- **G-7** (`GET /organizations/{id}/integrity`) **no cambia**: sigue con los 10 000 más antiguos y su `truncated`.

## 3. Puertos (autorizados por D-3, reglas-equipo §3.5)

- Un método **nuevo** de lectura descendente en cada directorio de `core`:
  - `FundDirectoryPort.findLatestFundIdsByOrganization` **[Plan]**;
  - `AssetDirectoryPort.findLatestAssetIdsByOrganization` **[Plan]**.
- Llevan el mismo filtro de génesis y `limit`, con orden `recordedAt` descendente y, en un empate, `streamId` descendente **[P-PE2-2]**.
- Recorren al revés los índices de hoy (`TraceabilityEventDocument.java:20-26`), sin índice nuevo.
- **Los métodos existentes no cambian**, y G-7, `GET /funds`, el recaudado por mes y `GET /physical-assets` los siguen usando.

## 4. Relación con DT-B-1

- El orden usa `recordedAt` como texto. Con DT-B-1 pagada (`plan-deudas-1-a-3-2026-10-26.md` §2), es cronológico para los datos nuevos.
- Dos génesis viejas del mismo segundo pueden seguir desordenadas entre sí (P-DT1-1).

## 5. Tests (rojos primero)

1. Con 10 001 activos: la actividad del activo más nuevo sale en la primera página, y la del más antiguo no. `truncated: true`.
2. Lo mismo con 10 001 fondos.
3. Con 10 000 o menos: los tests de la Enmienda 2 y 11a a 11f siguen en verde, sin cambios.
4. Los adaptadores nuevos devuelven en orden descendente con desempate descendente y `limit`. El profiler muestra `IXSCAN` hacia atrás en los índices de génesis, sin `SORT`.
5. G-7 sigue llamando al método de hoy y nunca al nuevo.

## 6. Documentos que se actualizan con el PR del código

- `ficha-R6-datos-estilo-v2.md`: una nota fechada en G-8 que remite a esta enmienda.
- `referencia-api-v1.md`: una nota fechada en la ruta de G-8.
- `estado-fase6.md`: la sección del PR, en la serie 0.66 a 0.69.

> **Nota (2026-10-26): implementada** en `feat/r6-g8-descendente` (`estado-fase6.md` §0.68), tras la aprobación de Manuel (https://github.com/Toffy22Cj/Donaciones/pull/153#issuecomment-6409194078).
