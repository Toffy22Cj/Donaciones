# ADR-033 (número tentativo — confirmar contra el catálogo real antes de commitear) — Convocatoria, CampaignFundingLedger, CampaignAssignment, CampaignResponsibleState y DonationIntent

**Estado:** Aprobado — diseño conceptual y arquitectónico, con registro de riesgos cerrado (§7). Pendiente de implementación, de una única decisión de producto (D2, autoasignación) y de las verificaciones técnicas listadas en §7.
**Fecha:** Sesión de Fase 6, review formal de 12 puntos (Modo de Arquitectura).
**Complementa:** `convocatoria-resumen.md`, `fase-6-estructura-y-perimetro-convocatoria.md`, `contract-wiring-review.md`, `golden-path.md`, `api-contract-matrix.md`. Corrige §3.4 de `fase-6-estructura-y-perimetro-convocatoria.md` (ver §3 de este ADR).

---

## 1. Contexto

Fase 6 requiere que una `Organization` pueda crear y administrar campañas de recaudación (`Convocatoria`), que personas donen dinero con o sin cuenta, y que el sistema resuelva de forma segura la correlación entre el pago externo (webhook de un proveedor) y el registro financiero interno (`Fund`, Event Sourced, ya existente desde Fase 1-2).

`contract-wiring-review.md` identificó dos huecos que bloqueaban el Golden Path: P1/P2 (contratos de aplicación de Convocatoria e integración de lectura con Identity) y P3 (correlación de pago — el hueco más serio, porque `clearFundsGenesis` necesita `organizationRef/campaignRef/donorRef/currency/amount/commandId/actorRef` que un webhook externo no trae de forma nativa).

Este ADR resuelve P1, P2 y P3, y formaliza el perímetro completo de `Convocatoria` que `fase-6-estructura-y-perimetro-convocatoria.md` dejó pendiente de "review formal de 12 puntos" antes de pasar a ejecución.

## 2. Decisión

Se introducen cinco componentes, todos CRUD + audit log append-only (no Event Sourced — sus invariantes son de estado actual, mismo criterio ya usado para `identity` en Fase 4), viviendo en un módulo Maven nuevo `convocatoria`, con **`convocatoria → contracts` como única dependencia directa**:

```
Convocatoria              — metadata, configuración, visibilidad, status
CampaignFundingLedger     — clearedAmount agregado, protección de targetPolicy
CampaignAssignment        — asignación EMPLOYEE↔convocatoria (inserción por asignación)
CampaignResponsibleState  — contador de responsables activos (mecanismo de concurrencia)
DonationIntent            — correlación de pago, cierra P3
```

Ninguno importa tipos de `core` ni de `identity`. La coordinación cross-módulo (con `Fund`, con `identity`) vive exclusivamente en `app`, que sí depende de ambos.

### 2.1 `Convocatoria`

- Responsabilidad: identidad pública (`publicCode`, único), configuración de recaudación (`targetAmount`, `targetPolicy`), ventana temporal, visibilidad, `status`.
- `status: OPEN | CLOSED` es **campo persistido**, no derivado de fechas+meta — necesario porque `FLEXIBLE`/`STRICT` nunca cierran automáticamente al alcanzar la meta, solo `CLOSE_ON_TARGET` lo hace; una función pura de `(fechas, clearedAmount, targetAmount)` no podría representar sin ambigüedad "esta STRICT ya alcanzó su meta pero sigue abierta".
- No conoce `Fund`, `PhysicalAsset`, ni el mecanismo de pago — correlación siempre vía `campaignRef`/`organizationRef` opacos.
- `CLOSED` como terminal (sin `CLOSED→OPEN`) queda como suposición por ausencia de evidencia en contra, no como decisión confirmada — ver §7.

### 2.2 `CampaignFundingLedger`

- Responsabilidad: proteger `clearedAmount` contra `targetAmount`/`targetPolicy`. Un ledger por `campaignRef` (relación 1:1).
- **`FLEXIBLE`**: sin límite superior.
- **`STRICT`**: `clearedAmount + amount <= targetAmount`, verificado en la misma transacción MongoDB que `Fund.FUNDS_CLEARED` y el mensaje de Outbox.
- **`CLOSE_ON_TARGET`**: comparte la misma infraestructura transaccional; las tres opciones de excedente (cerrar / rechazar el excedente / aceptarlo) ya están documentadas desde antes de este ADR (`convocatoria-resumen.md`); lo que este ADR añade es el requisito de que la opción elegida se fije como configuración en el momento de creación de la convocatoria, no como decisión humana tomada en vivo al cruzar la meta (exigido por la atomicidad de §2.2) — el nombre exacto del campo/enum es implementación, no arquitectura.
- Mecanismo: escritura condicional atómica de un solo documento —

  ```
  updateOne(
    { campaignRef: X, clearedAmount: { $lte: targetAmount - amount } },
    { $inc: { clearedAmount: amount } }
  )
  ```

  **`status` NO forma parte del filtro** (decisión D1, ver §2.6bis) — el cierre de la convocatoria no invalida por sí solo una aplicación de fondos legítima, porque esa legitimidad ya quedó garantizada en el momento de crear el `DonationIntent` correspondiente (que sí exige `status=OPEN`, ver §2.6). El filtro solo protege la capacidad financiera (`STRICT`/`CLOSE_ON_TARGET`); `FLEXIBLE` no tiene condición de capacidad, así que ese `updateOne` se reduce a `{ campaignRef: X }`.
- `matchedCount == 0` tras una transacción que no reportó conflicto transitorio se clasifica determinísticamente en `CampaignNotFoundException` / `CampaignFundingLimitExceededException` (ya no existe `CampaignClosedException` como resultado de este paso — ver §2.6bis).
- Un `TransientTransactionError` (incluye *write conflicts* entre transacciones concurrentes sobre el mismo documento) **no es una categoría de error nueva** — es la misma categoría ya establecida en `convocatoria-resumen.md` ("retry acotado de `TransientTransactionError` para fallos transitorios de Mongo"), aplicada aquí sin modificación. Se descarta explícitamente `LedgerConcurrencyConflictException` como excepción separada: bajo este mecanismo no existe una tercera categoría entre "conflicto de dominio" y "conflicto transitorio de infraestructura".

### 2.3 Corrección de `STRICT` — sustituye la decisión de `fase-6-estructura-y-perimetro-convocatoria.md` §3.4

`fase-6-estructura-y-perimetro-convocatoria.md` §3.4 concluyó "STRICT con tolerancia documentada" (ventana de inconsistencia reconciliable, sin transacción compartida) basándose en que `core` no tiene `MongoTransactionManager`. Esa verificación era correcta pero incompleta: **no inspeccionó `app`**.

Verificado directamente por código en esta sesión:

```java
// app/.../TraceabilityInfrastructureConfig.java
@Bean
public MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
    return new MongoTransactionManager(databaseFactory);
}

// app/.../TraceabilityApplication.java
@SpringBootApplication(scanBasePackages = "com.traceability")
@EnableMongoRepositories(basePackages = "com.traceability")
```

`app` sí provee un `MongoTransactionManager` real, alcanzable desde cualquier módulo compuesto en ese mismo contexto de Spring. Esto confirma la nota de corrección ya registrada en `convocatoria-resumen.md` §5 e invalida §3.4 como decisión vigente.

**Decisión corregida**: `STRICT` (y `CLOSE_ON_TARGET`) se protegen con **transacción MongoDB real compartida** — `CampaignFundingLedger` + `Fund.FUNDS_CLEARED` (append en `core`) + mensaje de Outbox, en una sola unidad transaccional, orquestada por un Application Service en `app`. `convocatoria` sigue sin importar `core` — la transacción cruza módulos a nivel de infraestructura (mismo `MongoTransactionManager`, mismo cliente Mongo), nunca a nivel de código Java entre `convocatoria` y `core`.

`fase-6-estructura-y-perimetro-convocatoria.md` §3.4 debe marcarse como superado por este ADR en cualquier lectura futura.

### 2.4 `CampaignAssignment`

- Responsabilidad: registrar la asignación `EMPLOYEE`↔`campaignRef`, con estado y fechas. Componente persistente propio, **no** array embebido en `Convocatoria` — exigido estructuralmente por la invariante "un empleado solo puede estar asignado a una convocatoria activa a la vez", que requiere un índice cruzando convocatorias, imposible de expresar dentro de un documento `Convocatoria` individual indexado por `campaignRef`.
- Cada asignación es una **inserción** de un nuevo documento (no actualización de uno existente), protegida por **índice único parcial**:

  ```
  { employeeRef: 1 } UNIQUE WHERE status = "ACTIVE"
  ```

  MongoDB serializa esto a nivel de motor en el momento de la escritura — no hay ventana entre "leer si ya existe" y "escribir". `DuplicateKeyException → EmployeeAlreadyAssignedException`.

### 2.5 `CampaignResponsibleState` — mecanismo de concurrencia para "≥1 responsable"

**Semántica de la invariante, resuelta por contradicción lógica**: la cardinalidad "≥1 responsable activo" protege exclusivamente `EMPLOYEE` explícitamente asignados vía `CampaignAssignment`. El respaldo automático de `REPRESENTATIVE` (enmienda ADR-032) **no** satisface esta cardinalidad — solo habilita comandos acotados (`REGISTER_PHYSICAL_ASSET`, `SPLIT_PHYSICAL_ASSET`) cuando no hay `EMPLOYEE` activo. Justificación: `Organization` garantiza siempre exactamente un `REPRESENTATIVE`; si este contara para la cardinalidad de la convocatoria, la regla "retirar al último responsable sin reemplazo se rechaza" nunca podría activarse — sería una rama de código muerta. La única lectura que le da sentido real a esa regla es que el `REPRESENTATIVE` no cuenta.

- `CampaignResponsibleState { campaignRef, activeResponsibleCount }` — documento contador, **no** optimistic-locking con versión (una comparación de versión no impide que dos transacciones lean el mismo conteo y ambas decidan proceder antes de que cualquiera detecte el conflicto).
- Retiro sin reemplazo: `updateOne({ campaignRef: X, activeResponsibleCount: { $gt: 1 } }, { $inc: { activeResponsibleCount: -1 } })`. `matchedCount==0` → `LastResponsibleRemovalWithoutReplacementException`.
- Retiro con reemplazo simultáneo: una única transacción, `activeResponsibleCount` no cambia (mismo N, cambia el titular) — `RemoveResponsible(campaignRef, responsibleRef, replacementRef?)` es **un solo comando**, nunca dos comandos independientes (evitaría la ventana intermedia con count=0).
- Primera asignación: `upsert:true` en el mismo `updateOne` de incremento — inicialización e incremento son la misma operación atómica.
- Ambas escrituras (`CampaignAssignment` + `CampaignResponsibleState`) y el registro de audit log ocurren **en la misma transacción MongoDB**, interna al módulo `convocatoria` (no necesita el orquestador de `app`, a diferencia de `STRICT`, porque no cruza `core`).
- `CampaignResponsibleState` es un mecanismo de contención de escritura, no una segunda fuente de verdad — nunca se consulta como read model; cualquier consulta de "cuántos responsables tiene esta convocatoria" cuenta sobre `CampaignAssignment` directamente.

### 2.6 `DonationIntent` — cierre de P3

Correlación de pago: captura `organizationRef`, `campaignRef`, `donorRef`, `amount`, `currency` **antes** de que exista ningún pago, resolviendo `publicCode → campaignRef + organizationRef` vía `ConvocatoriaReadPort` (ya existente).

```
DonationIntent
├── intentId
├── fundId              ← generado una sola vez al crear el intent, inmutable;
│                          el webhook NUNCA genera un fundId nuevo
├── paymentSessionId     ← único
├── providerEventId      ← identidad del evento externo concreto, distinta de
│                          paymentSessionId (evita que un segundo evento con
│                          payload distinto se cuele como el mismo hecho)
├── organizationRef, campaignRef, donorRef, amount, currency
└── status: PENDING | CONFIRMED | FAILED | EXPIRED-UNKNOWN
```

**Precondición de creación (ya fijada en el diseño original de esta pieza, no es una decisión nueva de esta ronda)**: un `DonationIntent` solo puede crearse contra una `Convocatoria` con `status=OPEN` (y `Organization.verificationStatus=VERIFIED`). Esta precondición es la que hace posible D1 (§2.6bis): si toda intención existente fue necesariamente creada mientras la convocatoria estaba abierta, el estado `CLOSED` en el momento del webhook no puede indicar un intento fraudulento colándose después del cierre — solo puede tratarse de una confirmación tardía de algo ya legítimo.

Flujo:
1. `POST /public/campaigns/{publicCode}/donation-intents` crea el `DonationIntent` (con `fundId` ya fijado) antes de contactar al proveedor de pago — rechazado determinísticamente si `Convocatoria.status != OPEN`.
2. El webhook, autenticado por firma del proveedor (nunca JWT), busca por `paymentSessionId`. Si no existe → `PaymentCorrelationNotFoundException` (rechazo + auditoría, no se inventa el contexto). Si `providerEventId`/`amount`/`currency` no coinciden con lo esperado → `PaymentEventMismatchException`. Si el `providerEventId` ya fue procesado → **no es un error**, es el camino feliz de idempotencia (no-op).
3. Si la correlación es válida: invoca `clearFundsGenesis(commandId, fundId, ...)` usando siempre el `fundId` de `DonationIntent`, nunca uno generado en el adaptador — sujeto únicamente a las reglas de `CampaignFundingLedger` (§2.2), sin volver a comprobar `status` (D1, ver §2.6bis).

### 2.6bis D1 — El cierre no invalida retroactivamente una intención ya aceptada

**Decisión**: una `DonationIntent` creada válidamente mientras la convocatoria estaba `OPEN` conserva su validez aunque la convocatoria pase después a `CLOSED`. Un webhook legítimo asociado a esa intención puede completar la operación tras el cierre. `CampaignFundingLedger` no usa `status=OPEN` como condición de aceptación del webhook (§2.2) — la operación sigue sujeta, sin excepción, a la capacidad y `targetPolicy` vigentes, de forma atómica.

Esto **no es "aceptar pagos después del cierre"** — es una consecuencia más estrecha y más defendible: el cierre no puede revocar retrospectivamente una intención que el sistema ya había aceptado como válida. Un `DonationIntent` que nunca pudo crearse (porque la convocatoria ya estaba `CLOSED` en ese momento) nunca entra en este escenario.

Alternativa descartada explícitamente (Camino B): mantener `status=OPEN` en el filtro del ledger y tratar el pago tardío legítimo como un caso de reconciliación manual/batch separado. Se descarta porque reintroduce exactamente la clase de "ventana de inconsistencia reconciliable aceptada a propósito" que este mismo ADR elimina en §2.3 para `STRICT` — sería inconsistente aplicar un estándar distinto al mismo tipo de problema dentro del mismo documento.

**Por qué `fundId` estable es la pieza crítica**: verificado en `FundCommandService`/`MongoEventStoreAdapter` (código real revisado en esta sesión) que el `streamId` (`fundId`) del génesis es un parámetro externo, no algo que el dominio derive — si el adaptador del webhook generara un `fundId` nuevo en cada invocación, dos entregas concurrentes del mismo pago crearían dos streams distintos, dos `Fund` reales, sin que el índice único `(streamId, sequence)` lo detectara nunca (streams diferentes no colisionan). Con `fundId` fijado en `DonationIntent`, una colisión concurrente sobre el mismo `fundId` sí choca contra ese índice → `DuplicateKeyException → ConcurrencyConflictException` (falla ruidosa, no duplicado silencioso).

## 3. Consecuencias

- Positivas: elimina el riesgo de duplicación financiera silenciosa por streams distintos ante entregas concurrentes del webhook; cierra P1/P2/P3 del wiring review; formaliza `RemoveResponsible`, contrato que ningún documento previo definía pese a que la regla de negocio lo exigía; corrige una decisión de `STRICT` basada en evidencia incompleta.
- Negativas / deuda aceptada: dos transacciones MongoDB independientes conviviendo (`STRICT` vía orquestador de `app`, `CampaignAssignment`+`ResponsibleState` interna a `convocatoria`) sin evidencia de comportamiento conjunto bajo carga — riesgo registrado, no resuelto (§7).

## 4. Alternativas descartadas

- **Metadata del proveedor de pago para derivar `organizationRef`**: descartada — el webhook nunca debe inventar contexto de dominio (regla ya establecida en `contract-wiring-review.md`).
- **Crear `Fund` directamente desde el webhook, sin `DonationIntent`**: descartada — mezclaría infraestructura externa, correlación e identidad de dominio en el adaptador.
- **`Donation` como Aggregate Event-Sourced propio**: descartada — `Fund` ya representa correctamente el lifecycle financiero; el proyecto evita convertir "Donation" en un Aggregate artificial desde las primeras rondas de diseño de agregados.
- **`CampaignResponsibleState` con optimistic locking (campo `version`)**: descartada — no impide que dos transacciones lean el mismo conteo antes de que cualquiera detecte conflicto; sustituida por update condicional atómico.
- **`CampaignAssignment` embebido como array dentro de `Convocatoria`**: descartada — la invariante de unicidad cruza convocatorias, no puede protegerse con un índice sobre un documento indexado por `campaignRef`.
- **Reutilizar `RoleAuthorizationPolicy`/`OrganizationBoundaryPolicy` de `core` directamente en `convocatoria`**: descartada — violaría la frontera `convocatoria → contracts` únicamente; se introduce `ConvocatoriaAuthorizationPolicy` propia, mismo patrón que `PlatformAuthorizationPolicy` en `identity`.
- **`LedgerConcurrencyConflictException` como categoría de error separada**: descartada tras analizar el mecanismo real — un *write conflict* sobre la escritura condicional cae en la categoría ya existente `TransientTransactionError`, sin necesitar nombre nuevo.

## 5. Autorización

- `CreateConvocatoria`: `ADMINISTRATOR` de la `Organization` (`VERIFIED`).
- `AssignEmployee`/`RemoveResponsible`: exclusivamente `ADMINISTRATOR`, sin autoasignación (excepto la pregunta abierta en §7).
- Respaldo de `REPRESENTATIVE` (ADR-032) **no aplica** a ninguno de los tres comandos anteriores — exclusivo de `PhysicalAsset`.
- `DonationIntent` (creación): pública u opcional JWT, sin `RoleAuthorizationPolicy`.
- Webhook de pago: firma del proveedor, nunca JWT — actor externo, nunca usuario autenticado.

## 6. Observabilidad

`correlationId` (tracing), `commandId` (comando+idempotencia), `intentId` (proceso de donación), `providerEventId` (evento externo), `eventId` (evento persistido), `streamId`/`fundId` (agregado) — seis identificadores con alcance distinto, ninguno sustituye a otro. `core.domain.event.ActorRef` es exclusivo de `app`↔`core`; `convocatoria` usa su propia representación de actor derivada de `AuthorizationPrincipal`, sin importar el tipo de `core`. El audit log de `convocatoria` participa en la misma transacción que la escritura de dominio que registra.

## 7. Estado final de riesgos y decisiones de producto (tras ronda de cierre)

**D1 (pago tardío tras `CLOSED`) queda resuelto — ver §2.6bis.** Solo quedan dos decisiones de producto genuinamente irreductibles por inferencia documental, más un conjunto de verificaciones técnicas que no bloquean seguir planeando:

| # | Tema | Estado | Bloquea implementación de |
|---|---|---|---|
| D1 | Pago legítimo confirmado después de `CLOSED` | ✅ **Resuelto — §2.6bis** | — |
| D2 | ¿Autoasignación prohibida también para un `ADMINISTRATOR` que a la vez es `EMPLOYEE`? | 🔴 **Decisión de producto pendiente** | `AssignEmployee` (caso límite) |
| — | `onTargetReachedBehavior` — semántica ya documentada desde antes de este ADR (cerrar/rechazar excedente/aceptarlo); falta fijar nombre de campo/enum | 🟢 Semántica resuelta, detalle de implementación pendiente | Nada — es especificación, no decisión |
| — | Idempotencia real de `clearFundsGenesis` ante retry — comportamiento de `CommandRetryTemplate` al agotar reintentos, no verificado | 🔴 **Verificación técnica pendiente** | El camino completo del webhook |
| — | Comportamiento conjunto de la transacción de `STRICT` (orquestada en `app`) y la de `CampaignAssignment`+`CampaignResponsibleState` (interna a `convocatoria`) bajo carga simultánea | 🟡 Benchmark/prueba de carga pendiente | Nada de forma inmediata; riesgo de escala |
| — | Desincronización `CampaignAssignment.ACTIVE` vs. `Account.INACTIVE` (Identity) | 🟢 Riesgo aceptado — `REPRESENTATIVE` cubre la operación de respaldo, sin sincronización automática | Nada |
| — | `providerEventId` sin índice único global — depende del contrato exacto del proveedor de pago, no elegido | 🟡 Pendiente de infraestructura externa | Materialización final del índice |
| — | Descubrimiento público de convocatorias (`GET /campaigns` o equivalente) | 🟢 **Necesidad de negocio confirmada** (`convocatoria-resumen.md` + `contract-wiring-review.md`, que trata explícitamente "necesidad confirmada ≠ contrato HTTP cerrado" como la jerarquía correcta, no como contradicción) — el contrato HTTP exacto, paginación, filtros y límites siguen pendientes | El endpoint concreto, no la decisión de si existe |
| — | ¿`Convocatoria.CLOSED` es terminal (sin `CLOSED→OPEN`)? | 🟡 No confirmado documentalmente — tratado como "reapertura fuera del contrato actual", no como invariante irreversible confirmada | Reapertura, si se necesitara |
| — | Ciclo de vida exacto de `CampaignAssignment.REMOVED` | 🟢 **Resuelto como decisión derivada del diseño ya congelado**: `REMOVED` es histórico, no se reactiva; una nueva asignación siempre crea un nuevo documento (consistente con "inserción por asignación", §2.4) | Nada |
| — | Límites operacionales de transacciones Mongo multi-documento (duración, tasa de contención) | 🟡 Benchmark/carga pendiente antes de producción | Nada de forma inmediata |
| — | Firmas exactas de `CreateConvocatoria`/`AssignEmployee`/`RemoveResponsible` | 🟡 Verificación contra código, no decisión arquitectónica | Nada de forma inmediata |

**Autocorrección registrada en esta ronda**: el ítem de descubrimiento público se presentó en una versión anterior de este ADR como una contradicción documental sin resolver entre `convocatoria-resumen.md` y `fase-6-estructura-y-perimetro-convocatoria.md`. Esa lectura ignoraba que `contract-wiring-review.md` —ya presente en el contexto de esta sesión— resuelve exactamente esa tensión de forma explícita, distinguiendo "necesidad de negocio confirmada" de "contrato HTTP cerrado" como la jerarquía correcta del proyecto, no como una inconsistencia. Corregido arriba.

Solo D2 queda como decisión de producto pendiente sin resolver; el resto son verificaciones técnicas o detalles de implementación que no requieren congelar nada nuevo antes de continuar con las capas siguientes de Fase 6 (Identidad, Blockchain, IA).

## 8. Trazabilidad de verificación

Este ADR se apoya en código real inspeccionado durante la sesión, no solo en documentación conceptual: `FundCommandService.java`, `MongoEventStoreAdapter.java`, `TraceabilityInfrastructureConfig.java`, `TraceabilityApplication.java` — todos citados en línea en las secciones correspondientes. Las firmas exactas de `CreateConvocatoria`/`AssignEmployee`/`RemoveResponsible` no fueron verificadas contra código (no existe todavía) — son especificaciones propuestas derivadas del review, y deben tratarse como tales hasta la implementación.
