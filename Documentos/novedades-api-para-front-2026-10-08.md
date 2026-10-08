# Novedades de la API para los fronts — desde `8456ecc` (2026-10-08)

**Para quién:** equipos de `paxfide-web` (organización y plataforma) y `paxfide-mobile` (donante y operador de campo).
**Referencia completa:** `referencia-api-v1.md`. Aquí solo va lo nuevo o lo cambiado desde el cierre del encargo 4 (`8456ecc`), con lo que cada front necesita saber. Base: `http://localhost:8080/api/v1`.
**Datos para probar:** el perfil `demo-seed` crea todo el escenario (paso 4b de `runbook-demo-local.md`). Las cuentas de ejemplo y los códigos están en `demo-credenciales-locales.md`.

## Endpoints nuevos

| Endpoint | Front | Lo que hay que saber |
|---|---|---|
| `GET /organizations/{organizationId}/campaigns/{campaignRef}/prediction/history` | Web (gráfico avanzado, S-10) | Solo `ADMINISTRATOR`/`REPRESENTATIVE`. Devuelve `cuts` con t = 0.15, 0.25 y 0.50, cada uno con `cutAt`, `available` y, si tiene cifra, `probabilityReachTarget`, `estimatedFinalPctOfTarget` y `pctRaisedAtCut` (lo recaudado en ese momento según el Event Store). **Un corte sin cifra no es un error**: se pinta el motivo (`unavailableText`). `FUTURE_CUT` es lo normal antes de llegar al corte. Siempre `kind: "ESTIMATE"`: mostrarlo como estimación, nunca como hecho. `warnings` incluye que la tasa de fallos de los cortes pasados es 0 |
| `GET /donations/tracking/integrity` | Móvil y web pública (seguimiento del donante) | Con el `trackingCode` en `Authorization: Bearer`, como `/donations/tracking`. `batches[]` lleva `anchorStatus`, `merkleRoot`, `transactionHash`, `network`, `anchoredAt`, `confirmedBlockNumber`, `eventsOfThisDonation` y `verification.result` (`MATCH`/`MISMATCH`/`INCONCLUSIVE`) con `reason` y `reasonText`. `unanchoredEvents` = eventos aún sin lote. Sugerencia de UI: "Anclada y verificada" solo con todos los lotes en `MATCH` y `unanchoredEvents = 0`; `NOT_ANCHORED` = "en proceso de anclaje". El resultado se cachea 5 minutos |

## Cambios en endpoints existentes

| Endpoint | Front | Cambio |
|---|---|---|
| `GET /organizations/{organizationId}/campaigns/{campaignRef}/prediction` | Web | Fuera del rango del modelo (t < 0.15 o t > 0.50) ya **no hay cifra**: `available: false`, `unavailableReason: "OUTSIDE_TRAINED_RANGE"` y su texto. Al empezar una convocatoria esto es lo normal |
| `POST /auth/register`, `POST /auth/login`, invitaciones | Web y móvil | El email se guarda **sin espacios en los extremos y en minúsculas**: ` Ana@X.org` y `ana@x.org` son la misma cuenta (registrar la otra variante da 409). El front puede normalizar igual, pero no hace falta |
| `POST /campaigns/{campaignRef}/close` y vistas de responsables | Web | Al cerrar, las asignaciones pasan a **históricas** en la misma transacción (D-06). El empleado queda libre para otra convocatoria. `GET /me/campaigns` sigue mostrando la cerrada (con `status: "CLOSED"`) |
| `GET /donations/tracking/narrative` | Móvil y web pública | El texto de respaldo (sin LLM) ahora está **en español y sin identificadores** |
| Todas las rutas `/donations/tracking/**` | Móvil y web pública | Corrección de la documentación (H-TR-1): un `trackingCode` inválido, caducado o ausente da **401** (no 404, como decía la referencia), con el mismo `ProblemDetail` en todas las rutas |
| `GET /donations/tracking` | Móvil y web pública | Documentados campos que ya venían: `status` (`ACTIVA`/`EN_PROCESO`), `financialSnapshot.refundedAmount` y `logistics[].custodianCategory`. `logistics[].locationZone` puede llegar `null` |
| `GET /donations/tracking/assets/{assetRef}/history` | Móvil y web pública | `history[].locationZone` llega `null` en las transiciones sin ubicación (`DISPATCHED`, `SPLIT`, `CUSTODY_TRANSFERRED`…) |
| `GET /physical-assets/{assetRef}` y `GET /organizations/{organizationId}/physical-assets` | Móvil (operador) y web | `currentLocation` **falta** mientras el activo está `DISPATCHED` (en tránsito; solicitud S-05 del móvil) |
| `GET /public/campaigns` y `GET /public/campaigns/{publicCode}` | Web pública y móvil | `organizationName` puede faltar |
| `POST /public/campaigns/{publicCode}/donation-intents` | Web pública y móvil | `paymentRedirectUrl` solo llega con `GATEWAY`; `statusToken` puede faltar en un reenvío de una intención antigua |
| `GET /public/campaigns/{publicCode}/narrative` | Web pública | `content` y `source` llegan siempre, con `null` donde no aplica (`PENDING`, `UNAVAILABLE`) |
| `POST …/configuration-change-requests/{requestId}/reject` | Web | La respuesta incluye `configurationVersion: null` |

**Regla general nueva** (`referencia-api-v1.md` §0): `campo?` puede **faltar o llegar `null`**; el cliente debe tratar los dos casos igual. Un campo sin `?` siempre llega.

## Sin cambios de API (para que nadie los busque)

- Índices de MongoDB creados al arrancar (H-IDX-1): solo afecta a la robustez.
- Verificación de integridad más estricta (B4): ahora detecta un payload alterado y una cadena rota. Para el front solo añade el motivo `CANONICAL_FORM_UNKNOWN`.
- Recuperación de lotes de anclaje (`COLLECTING_FAILED`, `RETRY`/`RELEASE`): operación por JMX, sin ruta HTTP.
- CORS de la demo local: `http://localhost:3000` y `http://127.0.0.1:3000`.
