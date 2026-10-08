# Ficha — P4 del encargo 6: verificación de integridad visible por la API

**Origen:** "Autorización de trabajo autónomo (4)", Carlos, 2026-10-08, P4.
**Hasta ahora:** `verifyBatch` (`IntegrityVerificationUseCase`) no tenía ruta HTTP.
**Reglas comunes:** las de `referencia-api-v1.md` §0 y §8 (seguimiento con `trackingCode`).

| Endpoint | Auth | Respuesta | Errores |
|---|---|---|---|
| `GET /api/v1/donations/tracking/integrity` | `Authorization: Bearer <trackingCode>`, como TR-01 | `200 {batches: [...], unanchoredEvents, checkedAt}`, `no-store` | El mismo 404 de todo el seguimiento si el código no vale (TR-D1) |

Cada elemento de `batches` es un lote que contiene al menos un evento de la donación:

```
{anchorStatus, merkleRoot?, transactionHash?, network?, anchoredAt?, confirmedBlockNumber?, eventsOfThisDonation,
 verification: {result: MATCH|MISMATCH|INCONCLUSIVE, reason?, reasonText?, affectsThisDonation?}}
```

## Qué es "de la donación"

- Los eventos del stream del fondo (`fundId` del `trackingCode`).
- Los de los activos que la proyección de la donación lista en `logistics`. Es la misma fuente que ya usa el seguimiento, así que la lista incluye los hijos de una división.
- Un lote entra si tiene algún evento de esos streams (campo `merkleBatchId` del evento).
- `unanchoredEvents` cuenta los eventos de la donación que todavía no están en ningún lote.

## Qué nunca sale (DD-76)

- Ni el `batchId` ni el `streamId`, la cobertura, la secuencia o el hash de los eventos de **otras** donaciones. Tampoco `affectedSequences` ni la raíz recalculada.
- Tampoco ningún dato personal, el `donorRef` ni el `fundId`.
- `eventsOfThisDonation` y `affectsThisDonation` solo miran los streams propios.

## Resultados y motivos

| `anchorStatus` del lote | `result` | `reason` |
|---|---|---|
| `ANCHORED` y la raíz recalculada coincide | `MATCH` | — |
| `ANCHORED`, la raíz no coincide y hay el mismo número de hojas | `MISMATCH` | `ROOT_MISMATCH`; `affectsThisDonation` dice si alguna hoja alterada es de esta donación |
| `ANCHORED` y cambió el número de eventos cubiertos | `MISMATCH` | `LEAF_COUNT_CHANGED`; `affectsThisDonation` se omite (no se puede atribuir) |
| `ANCHORED` sin `leafHashes` (lote antiguo) | `INCONCLUSIVE` | `LEGACY_BATCH` |
| Cualquier otro estado (`COLLECTING`, `PENDING`, `SUBMITTING`, `SUBMITTED`, `STUCK`, `FAILED`, `ANCHOR_MISMATCH`) | `INCONCLUSIVE` | `NOT_ANCHORED` |

`verifyBatch` compara la raíz recalculada desde el event store con la raíz guardada del lote, que es la que se anclará o ya se ancló. No consulta la cadena: la comprobación en cadena es la del anclaje (ADR-039).

## Coste: caché del resultado (DD-76)

- La verificación lee los hashes de todo el lote y recalcula el árbol. Por eso el resultado de cada lote `ANCHORED` se guarda en caché **5 minutos**, con un máximo de 1000 lotes; si se supera, la caché se vacía.
- Durante ese tiempo, un cambio en la base no se ve en esta ruta. Un lote no anclado no se verifica: se responde `NOT_ANCHORED` sin leer eventos.

## Tests

- **Unitarios:** mapeo de estados y motivos; `affectsThisDonation` solo con streams propios; nada de otros streams en el resultado; caché (una sola verificación en 5 minutos y otra después); recuento de no anclados.
- **HTTP con Mongo real:**
  - dos donaciones por el webhook simulado y un lote real del productor (`BlockchainAnchorProducer.produceBatch`), marcado `ANCHORED` como lo haría el anclaje;
  - el resultado es `MATCH`;
  - si se altera un evento de la **otra** donación: `MISMATCH` con `affectsThisDonation: false`, y la respuesta no lleva nada de la otra donación;
  - un código inválido da el mismo 404 que el seguimiento.
