# ADR-037 — Enmienda 3: confirmación por pasarela, webhook, proveedor simulado y consulta de la intención

**Estado:** **PROPUESTA** (2026-10-07). La decide Carlos. Debe estar **aprobada antes del merge de B6-b** (regla 3.5): añade un caso de uso, campos, un índice, una transición, una credencial y un registro persistente.
**Enmienda a:** ADR-037 (Convocatoria, ledger, asignación y `DonationIntent`), con sus enmiendas 1 y 2 (APROBADAS).
**Origen:** `propuesta-d-api.md` (APROBADO — Carlos, 2026-10-07), decisiones A2–A4, Q2, Q3 y Q4.

---

## 1. Contexto (verificado en el código, `develop` tras #55)

- **No existe la confirmación por pasarela.** `DonationIntentService.confirmDonationIntent` exige `ADMINISTRATOR` y **rechaza** `GATEWAY`/`PAYMENT_PROVIDER` (`:122-145`). Es la confirmación manual de la Enmienda 2 §3.1.
- `ConfirmationSource` ∈ `{PAYMENT_PROVIDER, ORGANIZATION}` y se fija **al crear** la intención según `paymentMethod` (`DonationIntent.java:104-106`; ADR-037:312).
- `paymentSessionId` y `providerEventId` existen, pero `create` los deja nulos. `providerEventId` no tiene índice. El repositorio no tiene `findByPaymentSessionId` ni transiciones a `FAILED`/`EXPIRED_UNKNOWN` (`DonationIntentStatus.java:4-5`).
- **El `trackingCode` no se genera en producción.** `HmacTrackingCodeService.generate(fundId, expiry)` depende de `expiry` y no guarda nada.
- Requisitos del webhook simulado (`plan-cierre-fase6-codigo.md:134-137`): usar el **mismo** caso de uso que el real; existir solo en `dev`/`demo` (404 en producción); distinguir una confirmación de demo de un pago real.
- Enmienda 2 `:108`: **no se habilita dinero real** hasta que exista P1 (registro de dinero no aceptable).

## 2. Decisión

### D1. Confirmación por pasarela

- **Caso de uso nuevo en `convocatoria`:** `confirmGatewayPayment(paymentProvider, paymentSessionId, providerEventId, amount, currency)`.
  1. Busca la intención por `paymentSessionId`. Si no existe → `PaymentCorrelationNotFoundException`.
  2. `amount` o `currency` distintos de los de la intención → `PaymentEventMismatchException`.
  3. `paymentProvider` distinto del de la intención → `PaymentEventMismatchException`.
  4. Confirma con la **misma barrera** `confirmIfPending` que la confirmación manual. `confirmationSource` sigue siendo `PAYMENT_PROVIDER`.
- **En `app`**, `ConfirmGatewayPaymentUseCase` encadena Tx 1 (confirmar) y Tx 2 (aplicar fondos) con el **mismo `FundsApplicationOrchestrator`** de ADR-045, igual que `ConfirmAndApplyDonationIntentUseCase`.
- La confirmación manual **sigue** rechazando `GATEWAY`: son dos caminos distintos que comparten la barrera de estado.

### D2. Proveedor de pago: `paymentProvider` y `SIMULATED` (Q2)

- **Campo nuevo en `DonationIntent`:** `paymentProvider` (texto, inmutable). Lo fija **al crear** una intención `GATEWAY` el adaptador del proveedor (D3). Para `BANK_TRANSFER` es nulo.
- `confirmationSource` no cambia de significado: dice **quién** confirma. `paymentProvider` dice **cuál** proveedor.
- **`SIMULATED`** es el valor del proveedor de la demo.
- **Se rechaza `SIMULATED` fuera de los perfiles `dev` y `demo` (condición de Carlos):**
  - al **crear**: el adaptador `SimulatedPaymentProvider` solo existe en esos perfiles, y además el dominio recibe una política `allowSimulatedPayments` (propiedad `traceability.demo.simulated-payments`, `false` por defecto) y rechaza una intención con `paymentProvider = SIMULATED` si está desactivada;
  - al **recibir el webhook**: el endpoint simulado solo existe en esos perfiles (404 en cualquier otro), y `confirmGatewayPayment` rechaza `SIMULATED` con la política desactivada aunque llegue por otra vía.
  - Tests en el perfil por defecto para los dos caminos.

### D3. Sesión de pago

- Puerto `PaymentProviderPort.createSession(intentId, amount, currency) → (paymentProvider, paymentSessionId, redirectUrl)`.
- CV-11 lo llama para `GATEWAY` y guarda `paymentProvider` y `paymentSessionId` en la intención, en la misma transacción que su creación.
- En la demo lo implementa `SimulatedPaymentProvider`: devuelve un `paymentSessionId` aleatorio y la URL de un *checkout* simulado.

### D4. Idempotencia y eventos fuera de orden (Q3)

- **Índice único parcial `(paymentProvider, providerEventId)`.** Dos proveedores pueden repetir un id; el mismo proveedor no.
- **Webhook duplicado:** el mismo `(paymentProvider, providerEventId)` ya procesado → `200` sin efecto (ADR-037:306: "camino feliz de idempotencia").
- **Fuera de orden:**

| Evento | Estado de la intención | Resultado |
|---|---|---|
| Pago fallido | `PENDING` | **`FAILED`** (transición nueva, terminal) |
| Pago fallido | `CONFIRMED` (o posterior) | Se ignora, con WARN |
| Pago confirmado | `PENDING` | `CONFIRMED`, y después la aplicación de fondos (ADR-045) |
| Pago confirmado | `CONFIRMED` (o posterior) | Duplicado: `200` sin efecto |
| **Pago confirmado** | **`FAILED` o `EXPIRED_UNKNOWN`** | **No cambia el estado. Escribe un registro persistente** en `unacceptable_payment_events` (proveedor, `providerEventId`, `intentId`, importe, moneda, motivo y fecha), **incrementa un contador** expuesto por JMX y emite un log ERROR. Responde `200` al proveedor (el evento se recibió) |

- **El registro persistente (precisión de Carlos):** un log puede rotarse y perderse. El registro es la única constancia de dinero real recibido para una intención no pendiente. **Queda atado a P1** como vía de resolución pendiente (devolución o aceptación). Hasta que exista P1, se ve y se cuenta, pero no se resuelve (regla 2.6: el estado tiene salida declarada en P1). Mismo motivo que la Enmienda 2 §4 para `FUNDING_REJECTED`.
- `EXPIRED_UNKNOWN` queda fuera de la demo: las intenciones `GATEWAY` no vencen.

### D5. Grafía

Se adopta la del código, **`EXPIRED_UNKNOWN`**, y se corrigen ADR-037:300 y CD-13.

### D6. Consulta del estado de la intención y entrega del `trackingCode` (Q-v2-7; Q4)

- **Credencial `statusToken`:**
  - CV-11 la devuelve una sola vez en su respuesta. Es aleatoria, de 256 bits;
  - en la intención solo se guarda su **hash** (SHA-256) y su caducidad (**24 h**);
  - es **reutilizable** durante esas 24 h (Q4), porque el cliente consulta varias veces mientras espera a que el pago pase de pendiente a confirmado;
  - **nunca aparece en logs ni en URLs**: viaja en la cabecera `Intent-Token`, no en la ruta ni en la consulta. Test de logs, como en B3.
- **Ruta:** `GET /api/v1/public/donation-intents/{intentId}`, añadida a `PublicRoutes`.
  - Devuelve el estado de la intención.
  - Un token ausente, inválido o caducado da **404**, igual que una intención inexistente.
- **`trackingCode`:** solo se incluye cuando la intención está **confirmada y con los fondos aplicados** (`fundsAppliedAt` presente).
  - Se **deriva** al vuelo para ese `fundId`: no se genera libremente (ADR-021-C) ni se guarda.
  - `expiry` determinista = `fundsAppliedAt` + `traceability.tracking-code.ttl`, de modo que el mismo `fundId` y el mismo `expiry` dan siempre el mismo código, sin campo nuevo en `Fund` ni en `FUNDS_CLEARED` (`golden-path.md:154`).

### D7. `donorRef` en CV-11

Lo fija ADR-048: se deriva del JWT o es anónimo, y **el cliente nunca lo envía** (hoy sí lo envía, y eso es un fallo de seguridad que B6 expondría).

## 3. Alternativas

| Alternativa | Por qué no |
|---|---|
| `confirmationSource = SIMULATED` | Mezcla quién confirma con cuál proveedor, y rompería la regla "se fija al crear" (ADR-037:312) |
| Confirmación tardía solo en un log | Un log puede rotarse; es dinero real recibido sin constancia persistente (precisión de Carlos a Q3) |
| `statusToken` de un solo uso | El cliente no podría consultar varias veces mientras espera la confirmación (Q4) |
| Guardar el `trackingCode` | No hace falta: el `expiry` determinista permite recalcularlo, sin un campo de dominio que solo sirva para guardarlo (`golden-path.md:154`) |
| `trackingCode` en la respuesta del webhook | Prohibido (`propuesta-apis-fase6.md:195`): la respuesta va al proveedor, no al donante |

## 4. Consecuencias

- **`DonationIntent`** gana `paymentProvider`, `statusTokenHash` y `statusTokenExpiresAt`, y la transición `PENDING → FAILED`.
- **Índices:** único parcial `(paymentProvider, providerEventId)` e índice sobre `paymentSessionId` (ya existe).
- **Colección nueva:** `unacceptable_payment_events`, con su contador JMX.
- **Puerto nuevo:** `PaymentProviderPort`, con un adaptador simulado solo en `dev`/`demo`.
- **Sigue en vigor:** no se habilita dinero real hasta P1 (Enmienda 2 `:108`).

## 5. Verificación (en B6-b)

- Webhook simulado: llama a `ConfirmGatewayPaymentUseCase` (Tx 1 → Tx 2) y deja `paymentProvider = SIMULATED`.
- En el perfil por defecto: ruta simulada → 404; crear con `SIMULATED` → rechazo; `confirmGatewayPayment` con `SIMULATED` → rechazo.
- Cada fila de la tabla de D4, con el registro persistente y el contador en la confirmación tardía.
- Concurrencia: dos webhooks iguales a la vez → una sola confirmación y una sola aplicación.
- `statusToken`: correcto → estado; ausente, inválido o caducado → 404 idéntico; nunca en logs (captura) ni aceptado en la URL.
- `trackingCode`: ausente mientras no haya fondos aplicados; presente y estable después; válido para `GET /donations/tracking`.
- Mutaciones: aceptar `SIMULATED` con la política desactivada; quitar el registro persistente; aceptar el token por URL; entregar el `trackingCode` antes de aplicar fondos.

## 6. Preguntas para Carlos

| # | Pregunta | Recomendación |
|---|---|---|
| E3-Q1 | ¿`paymentProvider` en la intención, con la política `allowSimulatedPayments` en el dominio además del perfil? (D2) | Sí: dos barreras independientes |
| E3-Q2 | ¿`unacceptable_payment_events` como registro persistente atado a P1, con contador JMX? (D4) | Sí |
| E3-Q3 | ¿`traceability.tracking-code.ttl` de **90 días** por defecto? (D6) | 90 días: el donante sigue su donación durante la entrega; el valor es de producto |
