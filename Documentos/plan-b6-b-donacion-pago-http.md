# Plan B6-b — HTTP de donación y pago: CV-11, pasarela simulada, `statusToken`, `trackingCode`, `donorRef` y `/account/donations`

**Estado:** **HECHO** (2026-10-07, `feat/b6-b-donacion-pago-http`; evidencia `evidencia-fase6/b6-b-donacion-pago-http-e72cc0e-2026-10-07.txt`, `estado-fase6.md` §0.14). Ejecutado bajo la autorización de trabajo autónomo de Carlos (2026-10-07), excepción temporal a las reglas 1 y 3.4. El plan es una `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` (DD-25); las decisiones concretas, DD-18 a DD-24.
**Normas que implementa (todas APROBADAS):** Enmienda 3 de ADR-037 (D1–D7, con E3-Q1 a E3-Q3; `trackingCode` de un año); ADR-048 (seudónimo aleatorio por cuenta, con la condición de §7); `propuesta-d-api.md` A2–A5, Q2–Q6; ADR-045 (orquestador de la Tx 2, sin cambios).
**Depende de:** B6-0 (#58), B6-a (convocatoria por HTTP) y B6-c (#61).
**Límites de la autorización que aplican aquí:** nada habilita dinero real ni un proveedor real (Enmienda 2 `:108` sigue en vigor); el proveedor simulado solo existe con `traceability.demo.simulated-payments=true` (falso por defecto); ningún secreto se escribe en código ni en configuración de `main` (el secreto de firma del webhook simulado llega por variable de entorno, sin valor por defecto).

---

## 1. Cambios

### 1.1 `convocatoria`

- **`DonationIntent`** gana `paymentProvider` (inmutable, fijado al crear una `GATEWAY`), `statusTokenHash` y `statusTokenExpiresAt` (D6), y la transición `PENDING → FAILED` (D4).
- **CV-11 (`createDonationIntent`):**
  - recibe el `donorRef` ya resuelto por `app` (ADR-048; el cliente nunca lo envía);
  - para `GATEWAY` llama a `PaymentProviderPort.createSession(intentId, amount, currency)` y guarda `paymentProvider` y `paymentSessionId` en la misma transacción (D3);
  - **política `allowSimulatedPayments`** (segunda barrera de E3-Q1): con la política desactivada, una sesión `SIMULATED` se rechaza con `SimulatedPaymentsNotAllowedException`;
  - genera el `statusToken` (256 bits de `SecureRandom`, base64url) y guarda solo su SHA-256 y su caducidad (24 h).
- **`confirmGatewayPayment(provider, sessionId, providerEventId, amount, currency)`** (D1) y **`failGatewayPayment(provider, sessionId, providerEventId)`** (D4), con la tabla de eventos fuera de orden de D4 y el registro `unacceptable_payment_events` con su contador.
- **Índice único parcial** `(paymentProvider, providerEventId)`.
- **Lecturas:** `findForStatusToken(intentId, token)` (comparación del hash en tiempo constante; ausente, inválido o caducado → vacío) y `findByDonorRef(donorRef)`, con índice sobre `donorRef`.

### 1.2 `identity` y `contracts` (ADR-048)

- `Account` gana `donorPseudonym` (UUID aleatorio, creado la primera vez).
- `DonorPseudonymPort.pseudonymFor(accountId)` en `contracts`, implementado en `identity` (obtener o crear, idempotente). No hay operación inversa ni listado.
- **ArchUnit:** fuera de `identity`, nada lee `donorPseudonym` de la cuenta; ningún DTO de `api` ni de `app.web` tiene un campo `donorRef` ni `donorPseudonym`.

### 1.3 `app`

- **`ConfirmGatewayPaymentUseCase`:** Tx 1 (`confirmGatewayPayment`) → si confirmó en esta llamada, Tx 2 con el **mismo** `FundsApplicationOrchestrator` (D1).
- **`SimulatedPaymentProvider`** (`PaymentProviderPort`), solo con `traceability.demo.simulated-payments=true`.
- **Rutas:**

| Ruta | Acceso | Respuesta |
|---|---|---|
| `POST /api/v1/public/campaigns/{publicCode}/donation-intents` (CV-11) | JWT opcional (ya en `PublicRoutes`), `Command-Id` | `201 {intentId, statusToken, paymentRedirectUrl?}` |
| `POST /api/v1/webhooks/payments` | pública (ya en `PublicRoutes`); firma HMAC del proveedor simulado; **la ruta solo existe con la propiedad activa** | `200` sin cuerpo; nunca el `trackingCode` |
| `GET /api/v1/public/donation-intents/{intentId}` | pública (se **añade** a `PublicRoutes`, D6), cabecera `Intent-Token` | `200 {status, trackingCode?}`; token ausente, inválido o caducado → el mismo 404 que una intención inexistente |
| `GET /api/v1/account/donations` | JWT obligatorio | `200 {items: [{intentId, campaignTitle?, amount, currency, status, trackingCode?}]}` |

- **`trackingCode`:** `TrackingCodeService.generate(fundId, fundsAppliedAt + P365D)`, solo con los fondos aplicados (E3-Q3: un año).

## 2. Tests (primero en rojo)

Los de la Enmienda 3 §5 y de ADR-048 §5 y §7, uno por fila:
1. Webhook simulado → `ConfirmGatewayPaymentUseCase` (Tx 1 → Tx 2) y `paymentProvider = SIMULATED`; el fondo existe y el ledger sube.
2. Perfil por defecto: la ruta del webhook da 404; crear con `SIMULATED` y `confirmGatewayPayment` con `SIMULATED` se rechazan (las dos barreras).
3. Cada fila de la tabla de D4, incluido el registro persistente y el contador en la confirmación tardía.
4. Dos webhooks iguales a la vez → una sola confirmación y una sola aplicación (`TransactionProbe` donde se afirme atomicidad).
5. `statusToken`: correcto → estado; ausente, inválido o caducado → 404 idéntico; nunca en logs; no aceptado en la URL.
6. `trackingCode`: ausente sin fondos aplicados; presente y estable después; válido para `GET /donations/tracking`; caduca al año.
7. ADR-048: con JWT, `acct:` + seudónimo estable por cuenta; sin JWT, `anon:` + UUID nuevo; un `donorRef` del cliente se ignora; `/account/donations` solo muestra las de la cuenta (sin JWT → 401); borrar el seudónimo vacía el historial; ninguna respuesta ni log contiene el seudónimo; ArchUnit.

**Mutaciones:** las de la Enmienda 3 §5 y ADR-048 §5.

## 3. Riesgos

- **`CampaignNotFoundException` tiene una sola traducción (403, B6-a).** CV-11 con un `publicCode` inexistente debe dar 404 público: el controlador de CV-11 consulta primero `ConvocatoriaReadPort` (como CV-07) y lanza `PublicCampaignNotFoundException`.
- El webhook simulado no es dinero real; la Enmienda 2 `:108` sigue impidiendo habilitar un proveedor real sin P1.
