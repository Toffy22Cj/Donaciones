# Plan 6 (ADR-044 Enm. 4) — respuestas literales del escenario histórico con baseline-0.4.0 (perfil demo-historico)

Capturado el 2026-11-20T05:34:52Z contra el backend local (`dev,demo-seed,demo-historico`, base vacía), commit `aae83a3`.
Cuenta: `ADMINISTRATOR` de la organización de la demo. Todos los datos son **sintéticos**. Los identificadores son de una base local desechable.

Rango de duración del entrenamiento (`featureRanges` de `baseline-0.4.0`): `[15.0, 90.0]` días. Las convocatorias con una duración fuera de ese rango se evalúan con la duración **recortada** (`clipToTrainedRange`); la respuesta no lo indica.

## Resumen

| Convocatoria | Estado | Moneda, política | Duración (días) | Transcurrido | Recaudado / meta | `/prediction` | Cortes de `/prediction/history` (0,15 · 0,25 · 0,50) |
|---|---|---|---|---|---|---|---|
| [Demo] Cerca del límite | OPEN | COP, FLEXIBLE | 300 | 47.0% | 80.0% | cifra: probabilidad 0.874, final estimado 1.5695 | cifra (recaudado 0.2586) · cifra (recaudado 0.4364) · `FUTURE_CUT` |
| [Demo] Después del rango | OPEN | COP, FLEXIBLE | 200 | 65.0% | 80.0% | `OUTSIDE_TRAINED_RANGE` | cifra (recaudado 0.1684) · cifra (recaudado 0.3158) · cifra (recaudado 0.6807) |
| [Demo] Cuarenta por ciento | OPEN | COP, FLEXIBLE | 200 | 40.0% | 45.0% | cifra: probabilidad 0.2068, final estimado 0.89 | cifra (recaudado 0.1577) · cifra (recaudado 0.2644) · `FUTURE_CUT` |
| [Demo] Un cuarto | OPEN | COP, FLEXIBLE | 200 | 25.0% | 45.0% | cifra: probabilidad 0.6463, final estimado 1.3727 | cifra (recaudado 0.2809) · cifra (recaudado 0.45) · `FUTURE_CUT` |
| [Demo] Política estricta | OPEN | COP, STRICT | 120 | 30.0% | 20.0% | `STRICT_POLICY_EXCLUDED` | `STRICT_POLICY_EXCLUDED`, sin cortes |
| [Demo] Meta alcanzada | OPEN | COP, FLEXIBLE | 120 | 30.0% | 105.0% | `TARGET_ALREADY_REACHED` | cifra (recaudado 0.6) · cifra (recaudado 0.9333) · `FUTURE_CUT` |
| [Demo] En dólares | OPEN | USD, FLEXIBLE | 120 | 30.0% | 25.0% | `UNSUPPORTED_CURRENCY` | `UNSUPPORTED_CURRENCY`, sin cortes |
| [Demo] Inicio del rango | OPEN | COP, FLEXIBLE | 200 | 17.0% | 12.0% | cifra: probabilidad 0.1637, final estimado 0.8257 | cifra (recaudado 0.0947) · `FUTURE_CUT` · `FUTURE_CUT` |
| [Demo] Cerrada antes de tiempo | CLOSED | COP, FLEXIBLE | 100 | 20.0% | 4.0% | `CAMPAIGN_ENDED` | `CAMPAIGN_ENDED` · `CAMPAIGN_ENDED` · `CAMPAIGN_ENDED` |
| [Demo] Antes del rango | OPEN | COP, FLEXIBLE | 120 | 7.0% | 3.0% | `OUTSIDE_TRAINED_RANGE` | `FUTURE_CUT` · `FUTURE_CUT` · `FUTURE_CUT` |
| Abrigo para el invierno | OPEN | COP, FLEXIBLE | 40 | 0.0% | 5.0% | `OUTSIDE_TRAINED_RANGE` | `FUTURE_CUT` · `FUTURE_CUT` · `FUTURE_CUT` |
| Kits de higiene (enlace privado) | OPEN | COP, FLEXIBLE | 45 | 0.0% | 6.7% | `OUTSIDE_TRAINED_RANGE` | `FUTURE_CUT` · `FUTURE_CUT` · `FUTURE_CUT` |
| Útiles escolares 2026 | CLOSED | COP, FLEXIBLE | 30 | 0.0% | 6.0% | `CAMPAIGN_ENDED` | `CAMPAIGN_ENDED` · `CAMPAIGN_ENDED` · `CAMPAIGN_ENDED` |

## Respuestas literales

### [Demo] Cerca del límite

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","available":true,"probabilityReachTarget":0.874,"estimatedFinalPctOfTarget":1.5695,"pctTimeElapsed":0.47,"warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.485007887Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-08-16T05:32:46Z","available":true,"probabilityReachTarget":0.4136,"estimatedFinalPctOfTarget":1.1207,"pctRaisedAtCut":0.2586},{"t":0.25,"cutAt":"2026-09-15T05:32:46Z","available":true,"probabilityReachTarget":0.5691,"estimatedFinalPctOfTarget":1.3413,"pctRaisedAtCut":0.4364},{"t":0.5,"cutAt":"2026-11-29T05:32:46Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-20T05:34:52.513568431Z"}
```

### [Demo] Después del rango

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"OUTSIDE_TRAINED_RANGE","unavailableText":"Fuera del rango del modelo: solo estima entre el 15 % y el 50 % del tiempo de la convocatoria","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.551640702Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-08-12T05:32:46Z","available":true,"probabilityReachTarget":0.1968,"estimatedFinalPctOfTarget":0.707,"pctRaisedAtCut":0.1684},{"t":0.25,"cutAt":"2026-09-01T05:32:46Z","available":true,"probabilityReachTarget":0.2945,"estimatedFinalPctOfTarget":0.6897,"pctRaisedAtCut":0.3158},{"t":0.5,"cutAt":"2026-10-21T05:32:46Z","available":true,"probabilityReachTarget":0.4614,"estimatedFinalPctOfTarget":0.9455,"pctRaisedAtCut":0.6807}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-20T05:34:52.564842207Z"}
```

### [Demo] Cuarenta por ciento

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","available":true,"probabilityReachTarget":0.2068,"estimatedFinalPctOfTarget":0.89,"pctTimeElapsed":0.4,"warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.584654944Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-10-01T05:32:46Z","available":true,"probabilityReachTarget":0.2137,"estimatedFinalPctOfTarget":0.8473,"pctRaisedAtCut":0.1577},{"t":0.25,"cutAt":"2026-10-21T05:32:46Z","available":true,"probabilityReachTarget":0.1884,"estimatedFinalPctOfTarget":0.8333,"pctRaisedAtCut":0.2644},{"t":0.5,"cutAt":"2026-12-10T05:32:46Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-20T05:34:52.597264905Z"}
```

### [Demo] Un cuarto

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","available":true,"probabilityReachTarget":0.6463,"estimatedFinalPctOfTarget":1.3727,"pctTimeElapsed":0.25,"warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.616687767Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-10-31T05:32:46Z","available":true,"probabilityReachTarget":0.5,"estimatedFinalPctOfTarget":1.1247,"pctRaisedAtCut":0.2809},{"t":0.25,"cutAt":"2026-11-20T05:32:46Z","available":true,"probabilityReachTarget":0.624,"estimatedFinalPctOfTarget":1.3523,"pctRaisedAtCut":0.45},{"t":0.5,"cutAt":"2027-01-09T05:32:46Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-20T05:34:52.631999166Z"}
```

### [Demo] Política estricta

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"STRICT_POLICY_EXCLUDED","unavailableText":"La política STRICT rechaza el exceso sobre la meta; el modelo no se entrenó con ella","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.651019038Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":false,"unavailableReason":"STRICT_POLICY_EXCLUDED","unavailableText":"La política STRICT rechaza el exceso sobre la meta; el modelo no se entrenó con ella","cuts":[],"warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.662384380Z"}
```

### [Demo] Meta alcanzada

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"TARGET_ALREADY_REACHED","unavailableText":"La meta ya se alcanzó","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.673848952Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-11-02T05:32:46Z","available":true,"probabilityReachTarget":0.9768,"estimatedFinalPctOfTarget":2.0019,"pctRaisedAtCut":0.6},{"t":0.25,"cutAt":"2026-11-14T05:32:46Z","available":true,"probabilityReachTarget":0.9945,"estimatedFinalPctOfTarget":2.1634,"pctRaisedAtCut":0.9333},{"t":0.5,"cutAt":"2026-12-14T05:32:46Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-20T05:34:52.684728686Z"}
```

### [Demo] En dólares

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"UNSUPPORTED_CURRENCY","unavailableText":"El modelo solo se entrenó con convocatorias en COP","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.700963272Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":false,"unavailableReason":"UNSUPPORTED_CURRENCY","unavailableText":"El modelo solo se entrenó con convocatorias en COP","cuts":[],"warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.712584179Z"}
```

### [Demo] Inicio del rango

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","available":true,"probabilityReachTarget":0.1637,"estimatedFinalPctOfTarget":0.8257,"pctTimeElapsed":0.17,"warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.725546132Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-11-16T05:32:46Z","available":true,"probabilityReachTarget":0.1293,"estimatedFinalPctOfTarget":0.725,"pctRaisedAtCut":0.0947},{"t":0.25,"cutAt":"2026-12-06T05:32:46Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"},{"t":0.5,"cutAt":"2027-01-25T05:32:46Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-20T05:34:52.737368690Z"}
```

### [Demo] Cerrada antes de tiempo

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.751456274Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-11-15T05:32:46Z","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó"},{"t":0.25,"cutAt":"2026-11-25T05:32:46Z","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó"},{"t":0.5,"cutAt":"2026-12-20T05:32:46Z","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-20T05:34:52.761911918Z"}
```

### [Demo] Antes del rango

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"OUTSIDE_TRAINED_RANGE","unavailableText":"Fuera del rango del modelo: solo estima entre el 15 % y el 50 % del tiempo de la convocatoria","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.774845786Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-11-29T19:56:46Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"},{"t":0.25,"cutAt":"2026-12-11T19:56:46Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"},{"t":0.5,"cutAt":"2027-01-10T19:56:46Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-20T05:34:52.784498998Z"}
```

### Abrigo para el invierno

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"OUTSIDE_TRAINED_RANGE","unavailableText":"Fuera del rango del modelo: solo estima entre el 15 % y el 50 % del tiempo de la convocatoria","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.798613806Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-11-26T05:32:43Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"},{"t":0.25,"cutAt":"2026-11-30T05:32:43Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"},{"t":0.5,"cutAt":"2026-12-10T05:32:43Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-20T05:34:52.807787326Z"}
```

### Kits de higiene (enlace privado)

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"OUTSIDE_TRAINED_RANGE","unavailableText":"Fuera del rango del modelo: solo estima entre el 15 % y el 50 % del tiempo de la convocatoria","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.820661926Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-11-26T23:32:43Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"},{"t":0.25,"cutAt":"2026-12-01T11:32:43Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"},{"t":0.5,"cutAt":"2026-12-12T17:32:43Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-20T05:34:52.830116664Z"}
```

### Útiles escolares 2026

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:52.843509493Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-11-24T17:32:43Z","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó"},{"t":0.25,"cutAt":"2026-11-27T17:32:43Z","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó"},{"t":0.5,"cutAt":"2026-12-05T05:32:43Z","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-20T05:34:52.853138357Z"}
```

## Fondos por mes (`GET /organizations/{id}/funds/monthly?months=12`)

HTTP 200
```json
{"timezone":"America/Bogota","items":[{"month":"2025-12","currency":"COP","clearedAmount":"0"},{"month":"2025-12","currency":"USD","clearedAmount":"0"},{"month":"2026-01","currency":"COP","clearedAmount":"0"},{"month":"2026-01","currency":"USD","clearedAmount":"0"},{"month":"2026-02","currency":"COP","clearedAmount":"0"},{"month":"2026-02","currency":"USD","clearedAmount":"0"},{"month":"2026-03","currency":"COP","clearedAmount":"0"},{"month":"2026-03","currency":"USD","clearedAmount":"0"},{"month":"2026-04","currency":"COP","clearedAmount":"0"},{"month":"2026-04","currency":"USD","clearedAmount":"0"},{"month":"2026-05","currency":"COP","clearedAmount":"0"},{"month":"2026-05","currency":"USD","clearedAmount":"0"},{"month":"2026-06","currency":"COP","clearedAmount":"0"},{"month":"2026-06","currency":"USD","clearedAmount":"0"},{"month":"2026-07","currency":"COP","clearedAmount":"994577354"},{"month":"2026-07","currency":"USD","clearedAmount":"0"},{"month":"2026-08","currency":"COP","clearedAmount":"1336225413"},{"month":"2026-08","currency":"USD","clearedAmount":"0"},{"month":"2026-09","currency":"COP","clearedAmount":"1945887580"},{"month":"2026-09","currency":"USD","clearedAmount":"0"},{"month":"2026-10","currency":"COP","clearedAmount":"3496567663"},{"month":"2026-10","currency":"USD","clearedAmount":"138889"},{"month":"2026-11","currency":"COP","clearedAmount":"2181741990"},{"month":"2026-11","currency":"USD","clearedAmount":"111111"}]}
```

## Lotes (`GET /organizations/{id}/physical-assets`)

| Lote | Convocatoria | Tipo | Cantidad | Estado | `lastEventAt` |
|---|---|---|---|---|---|
| `e75ece10-affd-54a7-bdb4-7894078297ff` | Abrigo para el invierno | BLANKET | 6.0000 UNITS | DELIVERED | 2026-11-20T05:33:45.943238953Z |
| `b3203984-fe8b-585e-a0b1-9714bc35a3b1` | Abrigo para el invierno | BLANKET | 4.0000 UNITS | DELIVERED | 2026-11-20T05:33:46.032535466Z |
| `ae0c62eb-56da-5ee9-86b0-ae2ad040e22a` | Abrigo para el invierno | FOOD_KIT | 20.0000 UNITS | DISPATCHED | 2026-11-20T05:33:46.077387873Z |
| `80ffe053-be44-5a8a-ab41-4def4bcd5bfc` | [Demo] Cuarenta por ciento | SCHOOL_KIT | 25.0000 UNITS | DELIVERED | 2026-10-23T05:32:46Z |
| `56343ee0-db25-5e27-b8a6-440fde82fe0f` | [Demo] Cuarenta por ciento | FOOD_KIT | 15.0000 UNITS | DISPATCHED | 2026-09-29T05:32:46Z |
| `19982272-6f30-51ef-84f3-27b45259da1a` | [Demo] Cuarenta por ciento | HYGIENE_KIT | 20.0000 UNITS | RECEIVED | 2026-10-11T05:32:46Z |
| `b0c6a0c5-8414-51a1-bde2-174497ae7a70` | [Demo] Cuarenta por ciento | BLANKET | 10.0000 UNITS | REGISTERED | 2026-09-17T05:32:46Z |
| `d0861741-c8ab-550c-b21e-6b3753e69ff2` | [Demo] Después del rango | SCHOOL_KIT | 25.0000 UNITS | DELIVERED | 2026-10-05T17:32:46Z |
| `4f2c93eb-04aa-5c2f-81ea-843b350b183f` | [Demo] Después del rango | FOOD_KIT | 15.0000 UNITS | DISPATCHED | 2026-08-27T17:32:46Z |
| `ca1c13d2-ca7a-5b29-8f62-c2075b05f571` | [Demo] Después del rango | HYGIENE_KIT | 20.0000 UNITS | RECEIVED | 2026-09-16T05:32:46Z |
| `197d03d4-60d7-55c2-94f1-e24d13e5954b` | [Demo] Después del rango | BLANKET | 10.0000 UNITS | REGISTERED | 2026-08-08T05:32:46Z |
| `8fb2908f-c9f4-5997-a016-ad866ab3b4d5` | [Demo] Después del rango | BLANKET | 18.0000 UNITS | REGISTERED | 2026-11-20T05:33:50.299300516Z |
| `3e575470-c517-560a-a012-f3a54e2986ab` | [Demo] Después del rango | BLANKET | 12.0000 UNITS | REGISTERED | 2026-11-20T05:33:50.622807156Z |

## Otros roles (sobre «[Demo] Un cuarto»)

`REPRESENTATIVE` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.4.0","warning":"modelo entrenado con datos sintéticos","available":true,"probabilityReachTarget":0.6463,"estimatedFinalPctOfTarget":1.3727,"pctTimeElapsed":0.25,"warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-20T05:34:53.321174163Z"}
```
`EMPLOYEE` → HTTP 403
```json
{"type":"about:blank","title":"Forbidden","status":403,"detail":"You are not allowed to perform this operation."}
```
