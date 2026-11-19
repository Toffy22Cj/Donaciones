# Paso 2 — respuestas literales del escenario histórico (perfil demo-historico)

Capturado el 2026-11-16T07:54:51Z contra el backend local (`dev,demo-seed,demo-historico`, base vacía), commit `d4485a6`.
Cuenta: `ADMINISTRATOR` de la organización de la demo. Todos los datos son **sintéticos**. Los identificadores son de una base local desechable.

Rango de duración del entrenamiento (`featureRanges` de `baseline-0.3.0`): `[15.0, 90.0]` días. Las convocatorias con una duración fuera de ese rango se evalúan con la duración **recortada** (`clipToTrainedRange`); la respuesta no lo indica.

## Resumen

| Convocatoria | Estado | Moneda, política | Duración (días) | Transcurrido | Recaudado / meta | `/prediction` | Cortes de `/prediction/history` (0,15 · 0,25 · 0,50) |
|---|---|---|---|---|---|---|---|
| [Demo] Cerca del límite | OPEN | COP, FLEXIBLE | 300 | 47.0% | 80.0% | cifra: probabilidad 0.9691, final estimado 1.4121 | cifra (recaudado 0.2586) · cifra (recaudado 0.4364) · `FUTURE_CUT` |
| [Demo] Después del rango | OPEN | COP, FLEXIBLE | 200 | 65.0% | 80.0% | `OUTSIDE_TRAINED_RANGE` | cifra (recaudado 0.1684) · cifra (recaudado 0.3158) · cifra (recaudado 0.6807) |
| [Demo] Cuarenta por ciento | OPEN | COP, FLEXIBLE | 200 | 40.0% | 45.0% | cifra: probabilidad 0.069, final estimado 0.8203 | cifra (recaudado 0.1577) · cifra (recaudado 0.2644) · `FUTURE_CUT` |
| [Demo] Un cuarto | OPEN | COP, FLEXIBLE | 200 | 25.0% | 45.0% | cifra: probabilidad 0.4327, final estimado 1.1827 | cifra (recaudado 0.2809) · cifra (recaudado 0.45) · `FUTURE_CUT` |
| [Demo] Meta alcanzada | OPEN | COP, FLEXIBLE | 120 | 30.0% | 105.0% | `TARGET_ALREADY_REACHED` | cifra (recaudado 0.6) · cifra (recaudado 0.9333) · `FUTURE_CUT` |
| [Demo] En dólares | OPEN | USD, FLEXIBLE | 120 | 30.0% | 25.0% | `UNSUPPORTED_CURRENCY` | `UNSUPPORTED_CURRENCY`, sin cortes |
| [Demo] Política estricta | OPEN | COP, STRICT | 120 | 30.0% | 20.0% | `STRICT_POLICY_EXCLUDED` | `STRICT_POLICY_EXCLUDED`, sin cortes |
| [Demo] Inicio del rango | OPEN | COP, FLEXIBLE | 200 | 17.0% | 12.0% | cifra: probabilidad 0.0045, final estimado 0.3618 | cifra (recaudado 0.0947) · `FUTURE_CUT` · `FUTURE_CUT` |
| [Demo] Cerrada antes de tiempo | CLOSED | COP, FLEXIBLE | 100 | 20.0% | 4.0% | `CAMPAIGN_ENDED` | `CAMPAIGN_ENDED` · `CAMPAIGN_ENDED` · `CAMPAIGN_ENDED` |
| [Demo] Antes del rango | OPEN | COP, FLEXIBLE | 120 | 7.0% | 3.0% | `OUTSIDE_TRAINED_RANGE` | `FUTURE_CUT` · `FUTURE_CUT` · `FUTURE_CUT` |
| Útiles escolares 2026 | CLOSED | COP, FLEXIBLE | 30 | 0.0% | 6.0% | `CAMPAIGN_ENDED` | `CAMPAIGN_ENDED` · `CAMPAIGN_ENDED` · `CAMPAIGN_ENDED` |
| Kits de higiene (enlace privado) | OPEN | COP, FLEXIBLE | 45 | 0.0% | 6.7% | `OUTSIDE_TRAINED_RANGE` | `FUTURE_CUT` · `FUTURE_CUT` · `FUTURE_CUT` |
| Abrigo para el invierno | OPEN | COP, FLEXIBLE | 40 | 0.0% | 5.0% | `OUTSIDE_TRAINED_RANGE` | `FUTURE_CUT` · `FUTURE_CUT` · `FUTURE_CUT` |

## Respuestas literales

### [Demo] Cerca del límite

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","available":true,"probabilityReachTarget":0.9691,"estimatedFinalPctOfTarget":1.4121,"pctTimeElapsed":0.47,"warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:51.932020362Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-08-12T07:53:45Z","available":true,"probabilityReachTarget":0.0866,"estimatedFinalPctOfTarget":0.6635,"pctRaisedAtCut":0.2586},{"t":0.25,"cutAt":"2026-09-11T07:53:45Z","available":true,"probabilityReachTarget":0.3414,"estimatedFinalPctOfTarget":0.8973,"pctRaisedAtCut":0.4364},{"t":0.5,"cutAt":"2026-11-25T07:53:45Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-16T07:54:51.957230934Z"}
```

### [Demo] Después del rango

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"OUTSIDE_TRAINED_RANGE","unavailableText":"Fuera del rango del modelo: solo estima entre el 15 % y el 50 % del tiempo de la convocatoria","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:51.981996769Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-08-08T07:53:45Z","available":true,"probabilityReachTarget":0.0123,"estimatedFinalPctOfTarget":0.6943,"pctRaisedAtCut":0.1684},{"t":0.25,"cutAt":"2026-08-28T07:53:45Z","available":true,"probabilityReachTarget":0.0416,"estimatedFinalPctOfTarget":0.7957,"pctRaisedAtCut":0.3158},{"t":0.5,"cutAt":"2026-10-17T07:53:45Z","available":true,"probabilityReachTarget":0.6005,"estimatedFinalPctOfTarget":1.0825,"pctRaisedAtCut":0.6807}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-16T07:54:51.991676892Z"}
```

### [Demo] Cuarenta por ciento

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","available":true,"probabilityReachTarget":0.069,"estimatedFinalPctOfTarget":0.8203,"pctTimeElapsed":0.4,"warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:52.006240707Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-09-27T07:53:45Z","available":true,"probabilityReachTarget":0.0121,"estimatedFinalPctOfTarget":0.5266,"pctRaisedAtCut":0.1577},{"t":0.25,"cutAt":"2026-10-17T07:53:45Z","available":true,"probabilityReachTarget":0.0206,"estimatedFinalPctOfTarget":0.6549,"pctRaisedAtCut":0.2644},{"t":0.5,"cutAt":"2026-12-06T07:53:45Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-16T07:54:52.014718990Z"}
```

### [Demo] Un cuarto

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","available":true,"probabilityReachTarget":0.4327,"estimatedFinalPctOfTarget":1.1827,"pctTimeElapsed":0.25,"warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:52.033798758Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-10-27T07:53:45Z","available":true,"probabilityReachTarget":0.1529,"estimatedFinalPctOfTarget":0.7666,"pctRaisedAtCut":0.2809},{"t":0.25,"cutAt":"2026-11-16T07:53:45Z","available":true,"probabilityReachTarget":0.4423,"estimatedFinalPctOfTarget":1.148,"pctRaisedAtCut":0.45},{"t":0.5,"cutAt":"2027-01-05T07:53:45Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-16T07:54:52.041994224Z"}
```

### [Demo] Meta alcanzada

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"TARGET_ALREADY_REACHED","unavailableText":"La meta ya se alcanzó","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:52.053777120Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-10-29T07:53:45Z","available":true,"probabilityReachTarget":0.9908,"estimatedFinalPctOfTarget":1.7845,"pctRaisedAtCut":0.6},{"t":0.25,"cutAt":"2026-11-10T07:53:45Z","available":true,"probabilityReachTarget":0.9998,"estimatedFinalPctOfTarget":1.9916,"pctRaisedAtCut":0.9333},{"t":0.5,"cutAt":"2026-12-10T07:53:45Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-16T07:54:52.059436124Z"}
```

### [Demo] En dólares

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"UNSUPPORTED_CURRENCY","unavailableText":"El modelo solo se entrenó con convocatorias en COP","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:52.067591146Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":false,"unavailableReason":"UNSUPPORTED_CURRENCY","unavailableText":"El modelo solo se entrenó con convocatorias en COP","cuts":[],"warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:52.072765495Z"}
```

### [Demo] Política estricta

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"STRICT_POLICY_EXCLUDED","unavailableText":"La política STRICT rechaza el exceso sobre la meta; el modelo no se entrenó con ella","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:52.077887252Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":false,"unavailableReason":"STRICT_POLICY_EXCLUDED","unavailableText":"La política STRICT rechaza el exceso sobre la meta; el modelo no se entrenó con ella","cuts":[],"warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:52.083327577Z"}
```

### [Demo] Inicio del rango

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","available":true,"probabilityReachTarget":0.0045,"estimatedFinalPctOfTarget":0.3618,"pctTimeElapsed":0.17,"warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:52.089156428Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-11-12T07:53:45Z","available":true,"probabilityReachTarget":0.0038,"estimatedFinalPctOfTarget":0.36,"pctRaisedAtCut":0.0947},{"t":0.25,"cutAt":"2026-12-02T07:53:45Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"},{"t":0.5,"cutAt":"2027-01-21T07:53:45Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-16T07:54:52.095939153Z"}
```

### [Demo] Cerrada antes de tiempo

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:52.105347249Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-11-11T07:53:45Z","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó"},{"t":0.25,"cutAt":"2026-11-21T07:53:45Z","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó"},{"t":0.5,"cutAt":"2026-12-16T07:53:45Z","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-16T07:54:52.111584738Z"}
```

### [Demo] Antes del rango

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"OUTSIDE_TRAINED_RANGE","unavailableText":"Fuera del rango del modelo: solo estima entre el 15 % y el 50 % del tiempo de la convocatoria","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:52.119638990Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-11-25T22:17:45Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"},{"t":0.25,"cutAt":"2026-12-07T22:17:45Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"},{"t":0.5,"cutAt":"2027-01-06T22:17:45Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-16T07:54:52.127270235Z"}
```

### Útiles escolares 2026

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:52.136725283Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-11-20T19:53:43Z","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó"},{"t":0.25,"cutAt":"2026-11-23T19:53:43Z","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó"},{"t":0.5,"cutAt":"2026-12-01T07:53:43Z","available":false,"unavailableReason":"CAMPAIGN_ENDED","unavailableText":"La convocatoria ya terminó"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-16T07:54:52.142807406Z"}
```

### Kits de higiene (enlace privado)

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"OUTSIDE_TRAINED_RANGE","unavailableText":"Fuera del rango del modelo: solo estima entre el 15 % y el 50 % del tiempo de la convocatoria","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:52.151334936Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-11-23T01:53:43Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"},{"t":0.25,"cutAt":"2026-11-27T13:53:43Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"},{"t":0.5,"cutAt":"2026-12-08T19:53:43Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-16T07:54:52.155890522Z"}
```

### Abrigo para el invierno

`GET …/prediction` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","available":false,"unavailableReason":"OUTSIDE_TRAINED_RANGE","unavailableText":"Fuera del rango del modelo: solo estima entre el 15 % y el 50 % del tiempo de la convocatoria","warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:52.164486087Z"}
```
`GET …/prediction/history` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","basis":"EVENT_STORE","available":true,"cuts":[{"t":0.15,"cutAt":"2026-11-22T07:53:43Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"},{"t":0.25,"cutAt":"2026-11-26T07:53:43Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"},{"t":0.5,"cutAt":"2026-12-06T07:53:43Z","available":false,"unavailableReason":"FUTURE_CUT","unavailableText":"Este corte aún no ha llegado"}],"warnings":["modelo entrenado con datos sintéticos","En los cortes pasados la tasa de fallos es 0: los pagos fallidos no están en el Event Store"],"asOf":"2026-11-16T07:54:52.170731121Z"}
```

## Fondos por mes (`GET /organizations/{id}/funds/monthly?months=12`)

HTTP 200
```json
{"timezone":"America/Bogota","items":[{"month":"2025-12","currency":"COP","clearedAmount":"0"},{"month":"2025-12","currency":"USD","clearedAmount":"0"},{"month":"2026-01","currency":"COP","clearedAmount":"0"},{"month":"2026-01","currency":"USD","clearedAmount":"0"},{"month":"2026-02","currency":"COP","clearedAmount":"0"},{"month":"2026-02","currency":"USD","clearedAmount":"0"},{"month":"2026-03","currency":"COP","clearedAmount":"0"},{"month":"2026-03","currency":"USD","clearedAmount":"0"},{"month":"2026-04","currency":"COP","clearedAmount":"0"},{"month":"2026-04","currency":"USD","clearedAmount":"0"},{"month":"2026-05","currency":"COP","clearedAmount":"0"},{"month":"2026-05","currency":"USD","clearedAmount":"0"},{"month":"2026-06","currency":"COP","clearedAmount":"45252525"},{"month":"2026-06","currency":"USD","clearedAmount":"0"},{"month":"2026-07","currency":"COP","clearedAmount":"1007506647"},{"month":"2026-07","currency":"USD","clearedAmount":"0"},{"month":"2026-08","currency":"COP","clearedAmount":"1426730464"},{"month":"2026-08","currency":"USD","clearedAmount":"0"},{"month":"2026-09","currency":"COP","clearedAmount":"2073216754"},{"month":"2026-09","currency":"USD","clearedAmount":"0"},{"month":"2026-10","currency":"COP","clearedAmount":"3751196301"},{"month":"2026-10","currency":"USD","clearedAmount":"138889"},{"month":"2026-11","currency":"COP","clearedAmount":"1651097309"},{"month":"2026-11","currency":"USD","clearedAmount":"111111"}]}
```

## Lotes (`GET /organizations/{id}/physical-assets`)

| Lote | Convocatoria | Tipo | Cantidad | Estado | `lastEventAt` |
|---|---|---|---|---|---|
| `2fc78de3-e07e-51ee-8e6f-d699eba899e4` | Abrigo para el invierno | BLANKET | 6.0000 UNITS | DELIVERED | 2026-11-16T07:54:44.943241659Z |
| `8b6d3f9e-7a7e-5ec5-b4ea-c790b45fd56c` | Abrigo para el invierno | BLANKET | 4.0000 UNITS | DELIVERED | 2026-11-16T07:54:45.031270699Z |
| `6c1740ba-a93b-50f5-bf9b-48d6a9f102ee` | Abrigo para el invierno | FOOD_KIT | 20.0000 UNITS | DISPATCHED | 2026-11-16T07:54:45.075771735Z |
| `88d9d04a-0c9f-5176-a6b5-dc02e90589ec` | [Demo] Cuarenta por ciento | SCHOOL_KIT | 25.0000 UNITS | DELIVERED | 2026-10-19T07:53:45Z |
| `c859ae2e-d597-52b0-8e67-e6975dc433d8` | [Demo] Cuarenta por ciento | FOOD_KIT | 15.0000 UNITS | DISPATCHED | 2026-09-25T07:53:45Z |
| `5fd73be8-e86e-581b-bf74-a60e29b9a830` | [Demo] Cuarenta por ciento | HYGIENE_KIT | 20.0000 UNITS | RECEIVED | 2026-10-07T07:53:45Z |
| `41b782bc-a566-56a2-abaf-5e183945e5c7` | [Demo] Cuarenta por ciento | BLANKET | 10.0000 UNITS | REGISTERED | 2026-09-13T07:53:45Z |
| `6ad5107a-ab8b-542d-b6f8-fba4f7b9f5b5` | [Demo] Después del rango | SCHOOL_KIT | 25.0000 UNITS | DELIVERED | 2026-10-01T19:53:45Z |
| `40b2e42e-c1ca-5058-8d6b-61912d75298b` | [Demo] Después del rango | FOOD_KIT | 15.0000 UNITS | DISPATCHED | 2026-08-23T19:53:45Z |
| `99c0647b-8de5-5b82-b1c7-8e73bff03498` | [Demo] Después del rango | HYGIENE_KIT | 20.0000 UNITS | RECEIVED | 2026-09-12T07:53:45Z |
| `73c9b13a-406b-527c-ac88-9540733972fc` | [Demo] Después del rango | BLANKET | 10.0000 UNITS | REGISTERED | 2026-08-04T07:53:45Z |
| `66297136-f45d-5e62-bda8-1e6789099a26` | [Demo] Después del rango | BLANKET | 18.0000 UNITS | REGISTERED | 2026-11-16T07:54:48.060090825Z |
| `1e91db1c-ea4a-54fa-9c94-c5a2f5d0cdaf` | [Demo] Después del rango | BLANKET | 12.0000 UNITS | REGISTERED | 2026-11-16T07:54:48.074791388Z |

## Otros roles (sobre «[Demo] Un cuarto»)

`REPRESENTATIVE` → HTTP 200
```json
{"kind":"ESTIMATE","modelVersion":"baseline-0.3.0","warning":"modelo entrenado con datos sintéticos","available":true,"probabilityReachTarget":0.4327,"estimatedFinalPctOfTarget":1.1827,"pctTimeElapsed":0.25,"warnings":["modelo entrenado con datos sintéticos"],"asOf":"2026-11-16T07:54:52.541576152Z"}
```
`EMPLOYEE` → HTTP 403
```json
{"type":"about:blank","title":"Forbidden","status":403,"detail":"You are not allowed to perform this operation."}
```

## Notas para leer la tabla

- **Fechas de los lotes.** `lastEventAt` es el `occurredAt` del último evento del lote. En los lotes «[Demo]» es del pasado, salvo en los dos últimos de «Después del rango»: el lote dividido (su último evento es la división, pedida al sembrar) y su hijo, que crea una saga en otro hilo. Los dos llevan la **fecha real**. Los tres lotes de «Abrigo para el invierno» son de la semilla `demo-seed`, sin cambios.
- **Meses de fondos.** `month` sale del `occurredAt` de los `FUNDS_CLEARED` (zona `America/Bogota`): seis meses con fondos en COP y dos en USD.
- **Duración recortada.** Las convocatorias «[Demo]» duran de 100 a 300 días y el modelo se entrenó con 15 a 90: su variable `durationDays` se evalúa recortada a 90. Es la única variable de estos casos que sale del rango de entrenamiento (`featureRanges`).
- **Por qué hay estimaciones tan bajas con buen ritmo.** «Cuarenta por ciento» lleva el 45 % de la meta al 40 % del tiempo y el modelo le da un 6,9 % de probabilidad. No es un fallo de cálculo: las medias de entrenamiento del propio JSON del modelo (`scalerMean`) son `pctRaised` 0,489 con `pctTimeElapsed` 0,28 y `paceRatio` 2,06. Las convocatorias sintéticas con las que se entrenó recaudan casi el doble del ritmo lineal en el primer tercio, así que una convocatoria que va al ritmo lineal queda, para el modelo, muy por detrás de las que alcanzan la meta. «Un cuarto» (ritmo 1,8) da 43 % y «Cerca del límite» (ritmo 1,7, con más donaciones) da 97 %. El modelo no se ha tocado (`baseline-0.3.0`).
