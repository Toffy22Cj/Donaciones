# Propuesta D-API — Decisiones abiertas antes de B6 y división de B6 en PR por flujo

**Estado:** **APROBADO — Carlos, 2026-10-07**: Q1–Q10 decididas, con precisiones en Q2, Q3, Q4, Q7 y Q9 (§5). Necesitan aprobación antes del merge de su PR: la enmienda 3 de ADR-037 (B6-b) y ADR-048 (B6-b). Cada PR de B6 necesita su plan. Cada PR de B6 necesitará después su plan aprobado (regla 3.4), y las piezas marcadas "ADR" necesitan su enmienda aprobada antes del merge (regla 3.5).
**Origen:** D-API de `plan-cierre-fase6-codigo.md` (`:27`); revisión de Carlos del 2026-10-07 ("B6 dividido en varios PR por flujo").
**Hechos:** verificados en `develop` tras #53, con cita. Rutas relativas a la raíz del repositorio.

---

## 1. Hechos que condicionan B6

| # | Hecho | Evidencia |
|---|---|---|
| H1 | **No existe confirmación por pasarela.** La única es la manual (`DonationIntentService.confirmDonationIntent`), que exige `ADMINISTRATOR` y **rechaza** `GATEWAY`/`PAYMENT_PROVIDER`. `ConfirmAndApplyDonationIntentUseCase` (Tx 1 → Tx 2 de ADR-045) envuelve esa confirmación manual | `convocatoria/.../DonationIntentService.java:122-145`, `:126-132`; `app/.../ConfirmAndApplyDonationIntentUseCase.java:26-39` |
| H2 | `ConfirmationSource` = `{PAYMENT_PROVIDER, ORGANIZATION}` y **se fija al crear** la intención según `paymentMethod`. ADR-037 lo cierra a esos dos valores. El requisito 3 del webhook simulado (`confirmationSource = SIMULATED`) choca con ambas cosas | `ConfirmationSource.java:7-8`; `DonationIntent.java:104-106`; ADR-037:312; plan `:137` |
| H3 | `paymentSessionId` y `providerEventId` existen pero `create` los deja nulos. `paymentSessionId` tiene un índice único parcial; `providerEventId`, **ninguno**. El repositorio no tiene `findByPaymentSessionId` ni transiciones a `FAILED`/`EXPIRED_UNKNOWN` | `DonationIntent.java:55,109`; `DonationIntentDocument.java:115,117`; `DonationIntentStatus.java:4-5` |
| H4 | **El `trackingCode` no se genera en producción.** `TrackingCodeService.generate(fundId, expiry)` solo lo llaman tests; depende de `expiry`, así que recalcularlo exige conocer ese `expiry` | `core/.../TrackingCodeService.java:6`; `HmacTrackingCodeService.java:34-43`; `FundsApplicationOrchestrator.java:129-132` |
| H5 | `donorRef` lo **aporta el cliente** en CV-11 y es opaco; nada lo deriva del JWT. `DonationReadPort` solo tiene `findByFundId` y el read model no tiene donante. La matriz dice "reutiliza patrón existente": es falso | `CreateDonationIntentCommand`; `DonationIntentService.java:96`; `DonationReadPort.java:6`; `propuesta-apis-fase6.md:309`; `documento-maestro-proyecto.md:450` ("requiere su propio ADR") |
| H6 | `ConvocatoriaReadPort` **no existe** y los campos de CV-07 no están definidos en ninguna fuente. Solo existe `resolvePublicCode → (campaignRef, organizationRef)` | ADR-037:283 (CD-06); `propuesta-apis-fase6.md:311`; `DonationIntentService.java:77-80` |
| H7 | `ficha-CV-01` está ABIERTA: Q-CV01-12 a 15, y `publicCode` de 50 bits frente a los ≥128 exigidos (deuda D-3) | `ficha-CV-01-crear-convocatoria.md:4,66,116-153`; `ConvocatoriaLifecycleService.java:48-49` |
| H8 | **Activos:** ningún registro devuelve el `assetId` (se genera dentro con `UUID.randomUUID()`) y un duplicado es un no-op silencioso. `dispatch`/`receive` solo existen en el dominio. El código usa `DELIVER_ASSET` y la matriz `DELIVER_PHYSICAL_ASSET`. `PhysicalAssetOperationalReadPort` no existe | `PhysicalAssetCommandService.java:133,160,243`; `PhysicalAsset.java:198,211`; `core/.../CommandType.java`; `api-contract-matrix.md:52-58,94` |
| H9 | `CampaignAuditFactsPort` **no tiene implementación** (B5, D-IA) | `contracts/.../CampaignAuditFactsPort.java`; ADR-040:93-99 |
| H10 | `api` depende solo de `core` y `contracts`. ADR-041 §2.8 obliga a que lo que cruce módulos pase por `app`. No hay `@ControllerAdvice` en ningún módulo. Nadie construye un `HumanActor` desde HTTP; el principal está en el atributo `authorizationPrincipal` de `JwtAuthFilter` | `api/pom.xml:18,23`; `JwtAuthFilter.java:39,109` |
| H11 | Los comandos de `convocatoria` reciben `actorAccountId` (String); los de `core`, un `ActorRef` | `CreateConvocatoriaCommand`; `PhysicalAssetCommandService` |

## 2. Decisiones que pide esta propuesta

### A1. Dónde viven los controladores de B6 (H10)

**Recomendación:** los que cruzan módulos (convocatoria, identity, orquestación de fondos) van en **`app`**, en el paquete `com.traceability.app.web`. Los que solo usan `core` (activos, división, seguimiento) siguen en **`api`**.
- Es lo que exige ADR-041 §2.8, sin crear dependencias nuevas (`api` → `convocatoria`).
- El filtro JWT y `PublicRoutes` son globales, así que protegen también los controladores de `app`.
- Pieza compartida en `api`:
  - `CurrentActor`: resuelve el `HumanActor` y el `AuthorizationPrincipal` desde el atributo del filtro;
  - un `@ControllerAdvice` con `ProblemDetail` (DH-02) y 401/403 (DH-51);
  - la lectura de `Command-Id`.
- `app` reutiliza esa pieza.

### A2. Confirmación por pasarela y webhook simulado (H1, H2, H3) — **enmienda 3 de ADR-037**

1. **Caso de uso nuevo en `convocatoria`:** `confirmGatewayPayment(paymentSessionId, providerEventId, amount, currency)`.
   - Busca por `paymentSessionId` (ADR-037:306).
   - Si no existe → `PaymentCorrelationNotFoundException`.
   - Si `amount` o `currency` no coinciden → `PaymentEventMismatchException`.
   - Si no, confirma con la misma barrera `confirmIfPending`.
   - En `app`, `ConfirmGatewayPaymentUseCase` encadena Tx 1 → Tx 2 con el **mismo orquestador** de ADR-045 (requisito 1).
2. **El webhook simulado llama a ese mismo caso de uso**, sin atajos (requisito 1).
   - Va en un bean condicional al perfil `demo` (propiedad `traceability.demo.simulated-payments=true`), que no existe por defecto.
   - Un test comprueba que en el perfil por defecto la ruta da 404 (requisito 2).
   - Valida una firma HMAC con un secreto solo de demo.
3. **Distinguir una confirmación de demo (requisito 3) sin romper ADR-037:**
   - `confirmationSource` sigue siendo `PAYMENT_PROVIDER`;
   - se añade **`paymentProvider`** (texto, inmutable), fijado al crear la sesión de pago por el adaptador del proveedor: `SIMULATED` en la demo y el nombre real en producción.
   - **Recomendado frente a `confirmationSource = SIMULATED`:** la fuente de confirmación dice *quién* confirma; el proveedor dice *cuál*. Mezclarlos obligaría a cambiar la regla "se fija al crear".
   - Un test comprueba que una confirmación de demo queda con `paymentProvider = SIMULATED`.
4. **`paymentSessionId`:** lo asigna CV-11 para `GATEWAY`, a través de un puerto `PaymentProviderPort.createSession(...)`. En la demo lo implementa `SimulatedPaymentProvider`, que devuelve el `paymentSessionId` y una URL de *checkout* simulado.
5. **Índice de `providerEventId`:** único parcial sobre **`(paymentProvider, providerEventId)`**, porque dos proveedores pueden repetir el mismo id.
6. **Webhook duplicado:** el mismo `providerEventId` ya procesado → `200` sin efecto, que es el camino feliz de idempotencia (ADR-037:306).
7. **Webhook fuera de orden:**
   - pago **fallido** sobre `PENDING` → `FAILED` (transición nueva);
   - fallido sobre `CONFIRMED` → se ignora, con WARN;
   - **confirmado** sobre `FAILED` o `EXPIRED_UNKNOWN` → no cambia el estado, responde `200` al proveedor y emite un log ERROR. Es dinero recibido para una intención no pendiente, así que entra en el registro de dinero no aceptable (P1 de la Enmienda 2), que sigue fuera de la demo.
   - `EXPIRED_UNKNOWN` queda fuera de la demo: las intenciones `GATEWAY` no vencen.
8. **Recordatorio:** la Enmienda 2 (`:108`) impide habilitar dinero real sin P1. El webhook simulado no es dinero real.

### A3. Grafía del estado vencido

**Recomendación:** la del código, **`EXPIRED_UNKNOWN`**. Se corrigen ADR-037:300 y CD-13 en el PR de documentos.

### A4. Consulta del estado de la intención y entrega del `trackingCode` (Q-v2-7, H4) — **en la enmienda 3 de ADR-037**

1. **Credencial:** CV-11 devuelve, además de `intentId`, un **`statusToken`**.
   - Es aleatorio, de 256 bits, y se entrega una sola vez en la respuesta.
   - Se guarda **solo su hash** en la intención, con caducidad de 24 h.
   - Es una credencial de consulta, **no** el `trackingCode`, así que respeta `golden-path.md:154`.
2. **Ruta:** `GET /api/v1/public/donation-intents/{intentId}`, con cabecera `Intent-Token`. Se añade a `PublicRoutes`.
   - Devuelve el estado de la intención.
   - Cuando los fondos están aplicados, devuelve también el **`trackingCode`**, calculado al vuelo (ADR-021-C: la ruta no lo genera libremente, lo deriva para esa intención).
   - Un token ausente, inválido o caducado da 404, igual que una intención inexistente: no revela nada.
3. **Cómo recalcularlo sin guardarlo:** `expiry` determinista = `fundsAppliedAt` (ya guardado en la intención) + `traceability.tracking-code.ttl`. El mismo `fundId` y el mismo `expiry` dan el mismo código, sin campo nuevo en `Fund` ni en `FUNDS_CLEARED`.
4. **Pregunta Q4:** "secreto de un solo uso" (`propuesta-apis-fase6.md:198`). ¿Se invalida el token tras la primera lectura con `trackingCode`, o se puede reutilizar durante 24 h?
   - **Recomiendo reutilizable 24 h.** La página posterior al pago se recarga, y un solo uso deja sin código al donante cuya respuesta se pierde.
   - El riesgo queda acotado por la caducidad y porque el token solo da acceso a esa intención.

### A5. `accountId` ↔ `donorRef` y `GET /account/donations` (H5) — **ADR-048 nuevo**

1. **Origen de `donorRef` en CV-11** (ruta con JWT opcional, ya en `PublicRoutes`):
   - con JWT válido, `donorRef = "acct:" + accountId`;
   - sin JWT, `donorRef = "anon:" + UUID aleatorio` (el "opaco efímero" de `golden-path.md:50`).
   - **El cliente ya no lo envía.** Hoy lo hace, y eso permitiría suplantar a otro donante.
2. **`GET /api/v1/account/donations`**, con JWT obligatorio. Lista las intenciones con `donorRef = "acct:"+accountId`: estado, importe, convocatoria y, si los fondos están aplicados, el `trackingCode` (A4.3).
   - La consulta va a `convocatoria`, que es donde está `donorRef`, más `DonationReadPort.findByFundId` para el estado financiero.
   - Necesita un índice sobre `donorRef` en las intenciones.
3. **Conflicto que decide Carlos:** `propuesta-apis-fase6.md:320` dejó `/account/donations` "fuera de Flutter v1". El criterio 6 (`golden-path.md:209`) lo exige para la demo web. **Recomiendo** que entre en B6 para la web, y que móvil siga fuera de v1.
4. `documento-maestro-proyecto.md:450` exige un ADR propio para poblar `donorRef` desde `accountId`: **ADR-048**, corto.

### A6. CV-07: campos del detalle público (H6)

**Recomendación de campos:** `publicCode`, `title`, `description`, `status`, `startDate`, `endDate`, `acceptedDonationTypes`, `acceptedPaymentMethods`, `currency`, `targetAmount`, `targetPolicy` y `clearedAmount` (este último del ledger, solo si acepta `MONETARY`).
- **Excluidos:** `campaignRef`, `organizationRef`, `configurationVersion` e ids internos. El nombre de la organización se añade cuando haya un puerto de lectura de `identity`.
- `ConvocatoriaReadPort.findPublicByCode(publicCode)` en `convocatoria`, que compone `Convocatoria` y `CampaignFundingLedger` (sin un quinto read model, `propuesta-apis-fase6.md:59`).
- Una convocatoria `PRIVATE_LINK` sí se devuelve por su `publicCode`, que hace de secreto; por eso importa D-3, en A7.

### A7. CV-01: cerrar la ficha (H7)

Antes del PR B6-a hay que resolver Q-CV01-12 a 15 y **subir `publicCode` a ≥128 bits** (deuda D-3; la entropía es lo que protege CV-07 en `PRIVATE_LINK`). La ficha tiene la propuesta de formato; falta aprobarla.

### A8. D-ASSET y respuestas de activos (H8) — dentro de B6-b

- **Métodos nuevos:** `dispatchAsset` y `receiveAsset` en `PhysicalAssetCommandService`, con `CommandType` `DISPATCH_PHYSICAL_ASSET` y `RECEIVE_PHYSICAL_ASSET` (rol `EMPLOYEE`).
- **El registro devuelve el `assetId`, y de forma determinista**, como en la división: `UUIDv5(organizationRef + ":" + commandId)`, calculado fuera del reintento. Un duplicado con el mismo `Command-Id` devuelve **la misma** respuesta, no un no-op mudo.
- **Nombre:** se mantiene el `DELIVER_ASSET` del código y se corrige la matriz (`:58`).
- `GET /physical-assets/{assetRef}` (matriz `:94`) necesita `PhysicalAssetOperationalReadPort`, sobre la proyección `logistics` y `asset_index`. Va en B6-b.

### A9. `Command-Id` (T-33)

**Recomendación:** `Command-Id` (UUID) obligatorio en **todos** los comandos de escritura de la demo, también los de `core`, porque sus servicios ya reciben `commandId`. T-33 lo dejaba "pendiente" en `core`. Un duplicado devuelve el mismo código y el mismo cuerpo. Excepciones: login (no aplica) y el webhook (su idempotencia es `providerEventId`).

### A10. Narrativa de convocatoria (H9)

**Fuera de B6:** entra con B5 (D-IA, ADR-040 C2–C5 y C8). La ruta ya es pública en `PublicRoutes`.

## 3. B6 dividido en PR por flujo

Cada PR lleva su plan (regla 3.4), sus tests en rojo, el reactor y mutaciones, y los tests contra Tomcat real donde haya rutas públicas.

| PR | Flujo | Contenido | Depende de |
|---|---|---|---|
| **B6-0** | Base HTTP | `CurrentActor` (`HumanActor` y principal desde el JWT), `@ControllerAdvice` con `ProblemDetail` y 401/403, lectura de `Command-Id`, convención `app.web` frente a `api` | A1, A9 |
| **B6-a** | Convocatoria | CV-01, CV-02, CV-07 (`ConvocatoriaReadPort`), verificar organización (`VerifyOrganizationService`) | B6-0, A6, A7 |
| **B6-b** | Donación y pago | CV-11 (`donorRef` desde el JWT o anónimo, `statusToken`, sesión del proveedor), webhook simulado, `ConfirmGatewayPaymentUseCase`, consulta de estado con `trackingCode`, `GET /account/donations` | B6-0, B6-a, A2–A5, **enmienda 3 de ADR-037 y ADR-048 aprobados** |
| **B6-c** | Activos y división | Registro A y B (con id determinista), `dispatch`/`receive` (D-ASSET), `deliver`, `POST .../split` (`202` + `Location`), `GET .../splits/{child}`, `GET /physical-assets/{assetRef}` | B6-0, A8 |
| **B6-d** | Seguimiento y narrativa | Seguimiento de Fase 3 integrado con el `trackingCode` real de B6-b (de punta a punta: donación → `trackingCode` → seguimiento); narrativa de convocatoria cuando exista B5 | B6-b; B5 para la narrativa |

**Orden recomendado:** B6-0 → (B6-a ∥ B6-c) → B6-b → B6-d. B6-c no depende de convocatoria y puede ir en paralelo con B6-a.

## 4. Preguntas para Carlos

| # | Pregunta | Recomendación |
|---|---|---|
| Q1 | ¿Controladores de B6 que cruzan módulos en `app.web`, y los que solo usan `core`, en `api`? (A1) | Sí |
| Q2 | ¿`paymentProvider = SIMULATED` en lugar de `confirmationSource = SIMULATED`, con enmienda 3 de ADR-037? (A2.3) | Sí: no rompe la regla "se fija al crear" |
| Q3 | ¿Índice único `(paymentProvider, providerEventId)`, duplicado → 200 sin efecto, y las reglas de eventos fuera de orden de A2.7? | Sí |
| Q4 | ¿`statusToken` reutilizable 24 h o de un solo uso? (A4.4) | Reutilizable 24 h |
| Q5 | ¿`donorRef` derivado del JWT o anónimo (nunca del cliente), con ADR-048? (A5) | Sí |
| Q6 | ¿`GET /account/donations` entra en B6 para la web, aunque móvil siga fuera de v1? (A5.3) | Sí: el criterio 6 lo exige |
| Q7 | ¿Campos de CV-07 de A6? | Sí |
| Q8 | ¿`publicCode` ≥128 bits y Q-CV01-12 a 15 cerradas antes de B6-a? (A7) | Sí |
| Q9 | ¿Id de activo determinista en el registro y `Command-Id` en todos los comandos de la demo? (A8, A9) | Sí |
| Q10 | ¿B6 en cinco PR (B6-0, a, b, c, d) en el orden de §3? | Sí |
| — | Fecha interna de cierre | **21 de octubre, con revisión el 14 — CONFIRMADA por Carlos, 2026-10-07** |

## 5. Decisiones de Carlos (2026-10-07)

| # | Decisión |
|---|---|
| **Q2** | **Sí:** `paymentProvider = SIMULATED`, y `confirmationSource` sigue indicando quién confirma. **Condición:** la aplicación **rechaza `SIMULATED` fuera de los perfiles `dev` y `demo`**, tanto al crear la intención (el adaptador simulado no existe fuera de ellos, y el dominio rechaza ese proveedor si el perfil no lo permite) como al recibir un webhook. Test en el perfil por defecto para los dos caminos. Necesita la enmienda 3 de ADR-037 |
| **Q4** | **Reutilizable durante 24 h.** Mitigaciones obligatorias: solo se guarda el hash; caduca a las 24 h; **nunca aparece en logs ni en URLs** (va en la cabecera `Intent-Token`, no en la ruta ni en la consulta); el `trackingCode` solo se entrega cuando la intención está confirmada y con los fondos aplicados. Tests de logs, como en B3 |
| **Q6** | **Sí:** `GET /account/donations` entra en B6, porque lo exige el criterio 6 |
| **ADR-048** | Debe **evaluar un seudónimo derivado**, `donorRef = "acct:" + HMAC(clave propia, accountId)`, frente al id de la cuenta en claro. Motivo: los eventos son inmutables, y el id en claro no podría suprimirse si alguien ejerce el derecho de supresión (Habeas Data). Si se elige el id en claro, queda escrito como **riesgo aceptado con el nombre de Carlos** |
| **`donorRef` del cliente** | Confirmado como **fallo de seguridad**: hoy no se puede explotar porque CV-11 no tiene HTTP, pero B6 lo expondría. Se corrige en B6-b: el `donorRef` se deriva del JWT o es anónimo, y el cliente nunca lo envía |
| **A1** | De acuerdo: los controladores que cruzan módulos van en `app.web` |
| **§3** | De acuerdo: B6 en cinco PR, en ese orden |
| **CV-01** | Hay que cerrar la ficha, con un `publicCode` de ≥128 bits, **antes de B6-a**. **Hecho (2026-10-07):** ficha CONGELADA, con 130 bits, cuerpo anidado, 400, visibilidad obligatoria y 5000 caracteres |
| **Q1** | **Sí:** los controladores que solo usan `core` se quedan en `api` |
| **Q3** | **Sí**, con una precisión: una **confirmación sobre una intención `FAILED` o `EXPIRED_UNKNOWN`** (dinero real recibido para una intención no pendiente) no puede quedarse solo en un log. Deja: (1) un **registro persistente** (`unacceptable_payment_events`: proveedor, id del evento, intención, importe, moneda, motivo y fecha); (2) un **contador** expuesto por JMX; (3) un **vínculo explícito con P1** como vía de resolución pendiente (regla 2.6). El resto, igual: duplicado → 200 sin efecto; fallo sobre `PENDING` → `FAILED`; fallo sobre `CONFIRMED` → se ignora con WARN |
| **Q5** | **Sí:** el `donorRef` se deriva del JWT o es anónimo, y el cliente nunca lo envía; la forma exacta, en ADR-048 |
| **Q7** | **Sí**, y **B6-a añade un puerto mínimo de `identity`** que devuelve solo el **nombre de la organización**, para el detalle público. **Cerrada remitiendo a la ficha CV-07** (opción (b), Carlos, 2026-10-07): se reabre Q-CV07-1 **solo para añadir `acceptedPaymentMethods`** (sin ellos la web no puede ofrecer cómo pagar). Siguen excluidos `publicCode` (el cliente ya lo tiene) y `targetPolicy` (configuración interna). Esto **sustituye** la lista de A6, que incluía esos dos. La ficha CV-07 aún no está en el repositorio (ver la nota de sincronización) |
| **Deudas D-7 a D-9 de CV-01** | **Entran en B6-a** (Carlos, 2026-10-07): si no, la ficha prometería rechazos que el código no hace |
| **Q9** | **Sí:** id de activo determinista **con espacio de nombres**, `UUIDv5(NS_ASSET, organizationRef + ":" + commandId)`, como el hijo de la división; y `Command-Id` en todos los comandos de la demo. **Reversión explícita de T-33** (`propuesta-apis-fase6.md:222`, que lo limitaba a Convocatoria y dejaba Core "pendiente"): **Carlos, 2026-10-07**. Motivo: la demo expone por HTTP los comandos de `core`, y los reintentos móviles y de red son requisito del proyecto desde el principio; `core` ya tenía idempotencia por `commandId`, y lo nuevo es exigir la cabecera y devolver la misma respuesta |
| **Q10** | **Sí** (ya decidido arriba) |
