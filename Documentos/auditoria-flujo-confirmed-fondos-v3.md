# Auditoría forense del flujo CONFIRMED → aplicación de fondos (v3)

**Fecha:** 2026-10-02. **Rama:** `develop`, HEAD `673eda9`. `convocatoria/` sin versionar (0 commits).
**Naturaleza:** informe temporal, solo lectura. No modifica código, tests ni documentación canónica.
**Premisas (decisiones humanas, no se reabren):**
- **F-1:** confirmar y aplicar fondos son actos separados. `PENDING → CONFIRMED → aplicación posterior`; `CONFIRMED` no significa fondos aplicados.
- **F-2:** `confirmDonationIntent()` no crea `Fund`, no aplica fondos ni toca el ledger; la génesis y la aplicación corresponden a un flujo posterior.

Taxonomía: **A** código · **B** test/ejecución · **C** git · **D** documental · **E** inferencia · **F** contradicción · **G** no determinable.

**Estado del código auditado.** Incluye cambios de esta sesión aún sin commit: `@Transactional(SUPPORTS)` en `applyFundsForIntent` y los Javadocs que citan `convocatoria-resumen.md` §6.19.

**Tests existentes ejecutados en esta auditoría.** `mvn -o test -pl convocatoria` → `Tests run: 180, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. Por clases: `CampaignFundingLedgerIntegrationTest` 22, `ConfirmDonationIntentIntegrationTest` 8, escenario de negocio 5, `ConvocatoriaArchitectureTest` 2. No se escribió ningún test.

---

## 1. Flujo actual trazado

| Pieza | Comportamiento real | Evidencia |
|---|---|---|
| `DonationIntent` | Estados `PENDING`, `CONFIRMED`, `FAILED`, `EXPIRED_UNKNOWN`. No existe `FUNDED` ni ninguna marca de "fondos aplicados". El documento guarda `status` y los datos de confirmación (`confirmedBy`, `confirmedAt`, medio, referencia) | A (`DonationIntentStatus`, `DonationIntentDocument`) |
| `confirmDonationIntent()` | `findById` → valida N8 → `confirmIfPending` (escritura condicional `status = PENDING` y no vencida) → `true`/`false`; lanza `DonationIntentExpiredException` si está vencida. `@Transactional(SUPPORTS)`. No hay autorización (`confirmedBy` es texto libre), ni ledger, ni `Fund`, ni auditoría | A (`DonationIntentService:112-128`) |
| `applyFundsForIntent()` | Recibe un `ConfirmDonationIntentCommand` → `findById` → **llama a `confirmDonationIntent()`** → si devuelve `false`, devuelve `false` sin tocar el ledger → si no, `increment()` por política. Sin transacción activa, abre la suya con reintento; con transacción activa, se une a ella y la deja marcada para rollback ante cualquier excepción | A (`CampaignFundingLedgerService:70-80`) |
| `increment()` | `incrementWithinTarget` (STRICT, REJECT_EXCESS: filtro `clearedAmount ≤ target − amount`) o `incrementUnconditionally` (FLEXIBLE, ACCEPT_EXCESS); `matchedCount == 0` → `CampaignNotFoundException` o `CampaignFundingLimitExceededException` | A (`:83-99`, adaptador `:44-56`) |
| `CampaignFundingLedgerDocument` | `_id = campaignRef`, `clearedAmount` agregado; **ningún registro por intención** | A |
| Outbox, eventos, scheduler, saga en `convocatoria` | **No existen** | A (grep: `outbox`, `@Scheduled`, `EventListener`, `publishEvent`, `Saga`) |
| Consulta de intenciones por estado | **No existe**: el puerto solo ofrece `insert`, `findById`, `existsByCampaignRef` y `confirmIfPending` | A (`DonationIntentRepositoryPort`) |
| `Fund` / `CLEAR_FUNDS_AS_GENESIS` | Solo en `core` (`FundCommandService.clearFundsGenesis:83-97`). Una génesis repetida con el mismo `commandId` termina en silencio: `exists` → `return` (`:84-86`), y `appendAndOutbox`: `tryClaim` → `return`. Índice único `{streamId, sequence}` en `event_store` | A |
| Puertos en `contracts` | Ninguno de financiación ni de intenciones | A |
| Llamadores de producción | `confirmDonationIntent` ← solo `applyFundsForIntent`. `applyFundsForIntent` ← nadie. `clearFundsGenesis` ← nadie fuera de `core`. `app` no depende de `convocatoria` (`app/pom.xml`) y no tiene orquestador | A |

**El flujo `PENDING → CONFIRMED → applyFundsForIntent() → ledger → Fund` no existe.** Lo implementado es:
- `PENDING → applyFundsForIntent() → [CONFIRMED + ledger]`, en una transacción, sin `Fund`.
- `PENDING → confirmDonationIntent() → CONFIRMED`, que es un punto final dentro del módulo.

## 2. Preguntas Q1–Q3

### Q1 — ¿Puede existir legítimamente `CONFIRMED`, ledger 0 y sin `Fund` tras `confirmDonationIntent()`?

**Sí.** Lo permite §6.19 DH-1 y lo exigen F-1/F-2 (D). Ocurre en la práctica (B: `independentConfirmationNeitherRequiresNorAppliesFunds`; sonda V10: solo cambia `donation_intents`, ledger en 0).

| Subpregunta | Respuesta | Evidencia |
|---|---|---|
| ¿Cómo se reanuda? | **No se reanuda.** `applyFundsForIntent()` devuelve `false` sobre una intención `CONFIRMED` | A, B (`applyingAnIndependentlyConfirmedIntentAppliesNothing`) |
| ¿Quién detecta la intención? | **Nadie.** No hay consulta por estado, ni evento, ni outbox, ni proceso que la busque | A |
| ¿Qué operación posterior la consume? | **Ninguna existe** | A |
| ¿Qué garantiza que no se pierda? | **Nada** | A → hallazgo H-1 |

### Q2 — ¿Qué debe consumir la aplicación posterior?

- **Lo único que existe hoy** es la intención persistida con `status = CONFIRMED` (A). No se genera ningún evento al confirmar.
- **Documentación:** F-2 dice "un flujo posterior que consume una intención ya confirmada" (decisión humana, sin registrar). ADR-037 §2.6 paso 2 dice que el webhook busca la intención por `paymentSessionId` (D). `plan-api-fase6.md:92` (E9) dice que el orquestador consulta la intención (D).
- Ningún documento define otra representación, como un evento o un mensaje de outbox. **Qué la consume exactamente es NO DETERMINABLE.** El único candidato con respaldo es la intención `CONFIRMED`, localizada por `paymentSessionId` en la pasarela (D) y por un medio no definido en la confirmación manual (G).

### Q3 — ¿Qué debe hacer `applyFundsForIntent()`?

| Variante | Respaldo |
|---|---|
| **A.** Requiere `PENDING` → confirma → aplica | **Código** (A); `implementation_plan.md` §4.4 (línea 178), §8 (261); §6.19 DH-3 (501); `estado-fase6.md:71` (D); 22 tests (B) |
| **B.** Requiere `CONFIRMED` → aplica | Solo F-1/F-2 (sin registrar). Ningún documento ni código lo describe |
| **C.** Admite ambos estados | Nada |
| **D.** Otra operación distinta | Nada lo define; ADR-037 §2.3 lo sitúa en un "Application Service en `app`" sin nombrarlo (D) |

**Resultado:** el código y los documentos sostienen la Variante A; las decisiones F-1/F-2 apuntan a B o D. **F** (ver §9).

## 3. Idempotencia

**Identidad de la aplicación:** la intención (`intentId`), con `fundId` como su clave en el lado del `Fund`. El `commandId` de la génesis se deriva de la intención (Enmienda §5.3, D) y `fundId` es fijo por intención (ADR-037 §2.6bis; índice `uq_fund_id`, A).

| Mecanismo | ¿Protege `apply(X); apply(X)`? | Evidencia |
|---|---|---|
| `confirmIfPending` (escritura condicional sobre la intención, en la misma transacción que el incremento) | **Sí, solo partiendo de `PENDING`.** Probado en secuencia, en concurrencia (5 rondas con `CyclicBarrier`), tras reinicio de contexto y en la misma transacción externa | A, B (`sameIntentAppliedTwiceSequentiallyIncrementsOnce`, `sameIntentAppliedConcurrentlyIncrementsOnce`, `sameIntentAppliedAgainAfterARestartIsANoOp`, `retryAfterTheFirstApplicationFinishedWithAnotherReferenceIsANoOp`) |
| `commandId` + `convocatoria_processed_commands` | No: ni confirmar ni aplicar están en `CommandType` | A |
| Registro por intención en el ledger | No existe | A |
| Versionado del ledger | No existe; solo hay escritura condicional por capacidad | A |
| `commandId` de génesis + `processed_commands` de `core` | Protege el `Fund`, pero una génesis repetida termina **en silencio**, así que no evita volver a sumar al ledger | A; D (Enmienda §5.3 [ESTADO]) |
| `fundId` + índice único `{streamId, sequence}` | Impide un segundo `Fund` con un `commandId` distinto; no protege el ledger | A |

**Partiendo de `CONFIRMED` (modelo F-1) no existe ninguna barrera real.** Comprobar el estado antes de aplicar no sirve como garantía sin una escritura condicional en la misma transacción, porque deja ventana de carrera. → Hallazgo H-2.

## 4. Concurrencia

| Escenario | Qué ocurre | Evidencia |
|---|---|---|
| A: `apply(X)` ∥ B: `apply(X)`, partiendo de `PENDING`, sin transacción externa | Las dos transacciones escriben el documento de X. Una recibe `WriteConflict` (`TransientTransactionError`); el helper reintenta (máximo 3); el reintento ve `CONFIRMED`, devuelve `false` y no incrementa | B (test concurrente; `WriteConflict` en el log; sonda V3: 20/20) |
| Lo mismo dentro de transacciones externas | Sin reintento interno (Enmienda §6): el perdedor recibe la excepción transitoria y su transacción se revierte; ledger con un único incremento | B (sonda V7d: 10/10) |
| A: `apply(X)` ∥ B: `apply(Y)`, X + Y por encima del límite (STRICT) | Las dos escriben el documento del ledger. Conflicto → reintento → el filtro de capacidad se reevalúa → la segunda recibe `CampaignFundingLimitExceededException`, sin efecto parcial | B (`concurrentStrictApplicationsThatTogetherExceedTargetApplyOnlyOne`, 5 rondas: 1 aplicada, 1 rechazada, acumulado ≤ meta) |
| Aislamiento | Transacción multidocumento de MongoDB (replica set) | A (`MongoTransactionManager`), E (semántica de *snapshot* del motor, no comprobada aparte) |
| Partiendo de `CONFIRMED` (modelo F-1) | **NO DETERMINABLE**: no existe la operación | A |

## 5. Fallo después de confirmar (`confirmDonationIntent` → commit → el proceso muere)

| Aspecto | Resultado | Evidencia |
|---|---|---|
| Estado que queda | `CONFIRMED`, ledger sin cambios, sin `Fund` | A, B |
| Cómo se descubre | **No se descubre**: no hay consulta por estado, ni evento, ni outbox, ni scheduler | A |
| Cómo se reintenta | **No se puede**: `applyFundsForIntent()` devuelve `false` | A, B |
| Qué evita perder la intención | **Nada** | A → H-1 |
| Qué evita duplicar los fondos | No aplica: no se llega a aplicar nada | A |

No se inventa ningún mecanismo. Falta uno, y es el hallazgo H-1.

## 6. Fallo durante la aplicación

| Caso | Estados posibles | Evidencia |
|---|---|---|
| Partiendo de `CONFIRMED` | La aplicación no se intenta (`false`) | A, B |
| Partiendo de `PENDING`, ledger STRICT o REJECT_EXCESS rechaza | Excepción propagada, rollback, intención `PENDING` sin confirmación, ledger sin cambios | B (`strictRejectsWholeDonation…KeepsTheIntentPending`, `rejectExcessRejectsTheWhole200…`; V4) |
| FLEXIBLE | Sin límite: aplica siempre | B (`flexibleCanExceedTarget`) |
| Dentro de una transacción externa, propagando | Rollback; `PENDING` | B (`ledgerRejectionInsideAnExternalTransaction…`) |
| Dentro de una transacción externa, el llamador captura la excepción | El commit falla con `UnexpectedRollbackException`; `PENDING` | B (`externalCallerCatchingTheLedgerRejectionCannotCommitTheConfirmation`, que falla si se retira la corrección; V5) |
| ¿Puede quedar `CONFIRMED` sin fondos tras un fallo de aplicación? | **No** por este camino. Sí por la confirmación independiente (Q1) | A, B |
| ¿Se puede volver a intentar? | Sí, el estado lo permite: la intención sigue `PENDING` y el reintento vuelve a llegar al ledger. Con STRICT, la capacidad nunca se libera en este corte, así que un reintento con éxito de la misma intención no es alcanzable | B (`rejectedIntentStaysPendingAndAdmitsANewAttempt`), A |

## 7. Dónde entra `Fund`

| Fuente | Qué dice | Clase |
|---|---|---|
| ADR-037 §2.6 paso 3 | Webhook con correlación válida → `clearFundsGenesis(commandId, fundId…)` con el `fundId` de la intención | D (vigente) |
| ADR-037 §2.2, §2.3 | STRICT se verifica "en la misma transacción MongoDB que `Fund.FUNDS_CLEARED` y el mensaje de Outbox"; "una sola unidad transaccional, orquestada por un Application Service en `app`" | D (vigente) |
| Enmienda §5.2 N9 (línea 194) | "La confirmación manual se ejecuta como `CLEAR_FUNDS_AS_GENESIS`" → `CONFIRMED` (manual) = génesis | D (vigente) |
| Enmienda §5.3 (líneas 204–207) | La transición es la barrera "en la misma transacción" que el efecto; `commandId` de la génesis derivado de la intención | D (vigente) |
| `golden-path.md:30-33` | "webhook confirma pago → `clearFundsGenesis` + ledger (misma transacción)" | D (secundario) |
| `implementation_plan.md` §1.3, §9.3 | `Fund` fuera de corte; "No invoca `clearFundsGenesis` ni toca `Fund`" | D |
| F-2 | La génesis la hace un flujo posterior que consume la intención `CONFIRMED` | Decisión humana (sin registrar) |
| Código | `Fund` solo en `core`; ningún camino desde `convocatoria` ni desde `app` | A |

`CONFIRMED → CLEAR_FUNDS_AS_GENESIS → Fund` **no existe en el código**. En los documentos vigentes, la génesis ocurre **en el mismo acto o transacción que la confirmación** (§5.3, N9, §2.6, `golden-path`). F-2 la pone **después**. → **F** (§9).

## 8. Frontera con `app`

| Regla | Contenido | Clase |
|---|---|---|
| ADR-037 §2 | `convocatoria → contracts` es la única dependencia; "la coordinación cross-módulo (con `Fund`…) vive exclusivamente en `app`" | D |
| ADR-037 §2.3 | Ledger + `FUNDS_CLEARED` + outbox, orquestados por un Application Service en `app`; la transacción cruza módulos por infraestructura, no por código | D |
| ADR-037 §2.5 | Transacción interna del módulo solo cuando la operación "no cruza `core`" | D |
| `ConvocatoriaArchitectureTest` | `convocatoria` no depende de `core`, `identity`, `app`, `api`, `crypto` ni `ai` | B |
| `implementation_plan.md` §11 | `app → convocatoria` se añadirá con el orquestador; hoy no existe | D, A |
| `fase-6-estructura-y-perimetro-convocatoria.md:138` | "Coordinación `CampaignFundingLedger ↔ Fund`: orquestación externa (mismo patrón que `AssetRegisteredSagaPolicy`)", es decir, consistencia eventual por saga | D, **histórico**: lo sustituye ADR-037 §2.3 (transacción compartida) y la regla N11 da precedencia a ADR-037 sobre ese documento |
| Sagas existentes | `core/application/saga` (`AssetRegisteredSagaPolicy`, `OutboxSagaCoordinator`) solo para activos | A |

**Resultado:** la aplicación con `Fund` va **en `app`**, con `convocatoria` aportando la parte de la intención y del ledger dentro de la transacción del orquestador (D, B). No puede ir íntegramente en `convocatoria` (B). No hay saga de financiación documentada ni implementada; la alternativa de saga es histórica.

## 9. Contradicciones documentales

| # | Texto A | Texto B | Clase |
|---|---|---|---|
| **K-1** | Enmienda §5.3 (líneas 204–207) [DECISIÓN]: la transición `PENDING → CONFIRMED` es la barrera "antes de cualquier efecto financiero sobre el ledger, **en la misma transacción**" | `convocatoria-resumen.md` §6.19 DH-1 (línea 499: confirmación independiente) y DH-2 (línea 500: "La aplicación de fondos es una operación posterior y separada"), más F-1 | **F** |
| **K-2** | Enmienda §5.2 N9 (línea 194): "La confirmación manual se ejecuta como `CLEAR_FUNDS_AS_GENESIS`"; `convocatoria-resumen.md:167`: "Confirmar transferencia… **es** `CLEAR_FUNDS_AS_GENESIS`" | F-2 y §6.19 DH-1 | **F** (confirmación manual, flujo futuro) |
| **K-3** | `implementation_plan.md` §4.4 (178) y §8 (261), §6.19 DH-3 (501), `estado-fase6.md:71`: `applyFundsForIntent` = transición + ledger, partiendo de `PENDING` | F-1: actos separados; la aplicación consume una `CONFIRMED` | **F** |
| **K-4** | §6.19 (511–512), `estado-fase6.md:70`, plan rev. 2.5: "F-1"/"F-2" son nombres de **contradicciones** | Las decisiones humanas F-1/F-2 | **F** (de nombres) |
| K-5 | `convocatoria-resumen.md:91`: las decisiones humanas de §6 no enmiendan un ADR; ADR-037 y su Enmienda siguen vigentes hasta que se apruebe una enmienda | — | D: regla de precedencia (citada, no aplicada por el auditor) |

## 10. Tests: qué garantizan realmente

| Garantía | Test | Valoración |
|---|---|---|
| Confirmación independiente | `independentConfirmationNeitherRequiresNorAppliesFunds` | **Real**: estado persistido y aserciones negativas sobre los incrementos |
| `CONFIRMED` sin fondos | El anterior + `applyingAnIndependentlyConfirmedIntentAppliesNothing` | **Real**, pero el segundo afirma que la aplicación posterior **no** ocurre: protege el modelo de la Variante A, no el de F-1 |
| Aplicación posterior de una `CONFIRMED` | — | **No cubierto** (no existe) |
| Aplicación repetida | `sameIntentAppliedTwiceSequentially…`, `retryAfterTheFirstApplicationFinished…`, `…AfterARestart…` | **Real**, solo partiendo de `PENDING` |
| Aplicación concurrente | `sameIntentAppliedConcurrentlyIncrementsOnce`, `concurrentStrictApplicationsThatTogetherExceedTargetApplyOnlyOne` | **Real**: concurrencia forzada contra MongoDB real, solo partiendo de `PENDING` |
| Rechazo STRICT | `strictRejects…`, `rejectExcessRejectsTheWhole200…` | **Real** |
| Reintento | `ConvocatoriaTransactionRetryHelperIntegrationTest` (2); `rejectedIntentStaysPendingAndAdmitsANewAttempt` | Real para el helper; el segundo solo demuestra que el estado admite reintento (el reintento siempre se vuelve a rechazar) |
| Fallo transaccional | `joinsAnExternalTransactionAndRollsBackWithIt`, `missingLedgerIsCampaignNotFoundAndRollsBackTheConfirmation` | **Real** |
| Llamador externo que captura la excepción | `externalCallerCatchingTheLedgerRejectionCannotCommitTheConfirmation` | **Real**: falla si se retira la corrección (prueba de mutación de esta sesión) |
| Ídem, intención vencida | `externalCallerCatchingAnExpiredIntentFailureCannotCommit` | **Protege contra regresiones, pero no demuestra la corrección**: pasa también sin ella, porque `confirmDonationIntent` ya marca el rollback |

## 11. Matrices

| Pregunta | Código | Tests | Docs | Git | Evidencia A-G | Estado |
|---|---|---|---|---|---|---|
| Q1 `CONFIRMED` sin fondos | Posible; sin reanudación | Sí | DH-1, F-1 | `convocatoria` sin versionar | A, B, D | Legítimo, pero sin continuación (H-1) |
| Q2 Qué consume la aplicación | Nada | — | §2.6 paso 2, E9 (parcial) | — | G | No determinable |
| Q3 Semántica de `applyFundsForIntent` | Variante A | 22 tests | Plan §4.4/§8, DH-3 frente a F-1 | — | A, B, F | Contradicción (K-3) |
| Idempotencia partiendo de `PENDING` | `confirmIfPending` | Sí | §5.3 | — | A, B | Garantizada |
| Idempotencia partiendo de `CONFIRMED` | Ninguna | — | Ninguna | — | A, G | Hueco (H-2) |
| Concurrencia partiendo de `PENDING` | Conflicto + reintento + filtro | Sí | ADR-037 §2.2 | — | A, B | Garantizada |
| Proceso que muere tras confirmar | Intención perdida para el módulo | — | Nada | — | A | Hueco (H-1) |
| Rechazo en la aplicación | Rollback → `PENDING` | Sí | DH-3, DH-4 | — | A, B | Garantizado |
| Dónde entra `Fund` | Fuera de `convocatoria` | ArchUnit | §2.3, §5.3, N9 frente a F-2 | ADR-037 en `7a51ecb` | D, F | Contradicción (K-1, K-2) |
| Frontera con `app` | Sin orquestador | ArchUnit | ADR-037 §2, §2.3 | — | D, B | Definida |

| Pieza | Semántica actual | Semántica documentada | ¿Coinciden? | Acción necesaria |
|---|---|---|---|---|
| `CONFIRMED` | Transición registrada; no implica fondos | §6.19: no implica fondos (limitado al corte). §5.3 y N9: barrera / génesis en el mismo acto | Solo con §6.19 | Resolver K-1 y K-2 |
| `confirmDonationIntent()` | Solo confirma; sin autorización | DH-1 (independiente); N9 (= génesis, `ADMINISTRATOR`) | Con DH-1 sí; con N9 no | Resolver K-2 y la autorización |
| `applyFundsForIntent()` | Desde `PENDING`: confirma + ledger | Plan §4.4/§8, DH-3: igual. F-1: consume `CONFIRMED` | Con los documentos sí; con F-1 no | Resolver K-3 |
| Barrera de la aplicación posterior | No existe | No existe | — | Definirla (H-2) |
| Descubrimiento de intenciones `CONFIRMED` sin aplicar | No existe | No existe | — | Definirlo (H-1) |
| Ledger | Agregado; escritura condicional | ADR-037 §2.2 | Sí | — |
| `Fund` | Fuera de `convocatoria` | ADR-037 §2.3 (`app`) | Sí | — |
| Etiquetas F-1/F-2 | — | Contradicciones en §6.19; decisiones en la conversación | No | Renombrar o registrar (K-4) |

## 12. Hallazgos

- **H-1 (hueco):** una intención confirmada por separado no tiene ningún mecanismo que la descubra, la reanude o impida que se pierda (§5; A).
- **H-2 (hueco):** el paso `CONFIRMED → aplicado` no tiene barrera de idempotencia. La génesis repetida termina en silencio y el ledger no guarda registro por intención (§3; A, D).
- **K-1 a K-4:** contradicciones documentales (§9).

## 13. Contrato que debe quedar definido para que otro agente pueda implementarlo sin inventar

Son preguntas que el contrato debe responder. El auditor no les da respuesta:
1. **Entrada:** qué identifica la aplicación (intención por `intentId`, o por `paymentSessionId` en la pasarela) y qué precondición de estado exige.
2. **Barrera:** qué escritura condicional por intención, en la misma transacción que el ledger y la génesis, impide la doble aplicación (H-2), y si se representa como estado de `DonationIntent` (lo que tocaría ADR-037 §2.6 y ADR-041) o como otra marca.
3. **Composición:** qué operación de `convocatoria` participa en la transacción del orquestador, y si `applyFundsForIntent` se redefine o se sustituye.
4. **Descubrimiento:** cómo se encuentra y reanuda una intención `CONFIRMED` sin aplicar (H-1).
5. **Fallos:** el transitorio (reintento de la transacción completa, Enmienda §6, T1) frente al permanente (rechazo del ledger: salida de la intención y registro de P1).
6. **Autorización:** quién confirma y quién aplica (N8, N9, ADR-032).
7. **Normativa:** cómo quedan la Enmienda §5.3 y N9.

## 14. Dictamen

### CASO 3 — CONTRADICCIÓN DOCUMENTAL

Los documentos canónicos contienen dos modelos incompatibles:
- **Modelo vigente normativo:** la transición a `CONFIRMED` es la barrera de la aplicación y va **en la misma transacción** que el efecto financiero; la confirmación manual **es** la génesis. Lo sostienen la Enmienda §5.3, N9, ADR-037 §2.6, `golden-path`, el plan §4.4 y §8, §6.19 DH-3 y el código de `applyFundsForIntent`.
- **Modelo F-1/F-2:** la confirmación es independiente y la aplicación y la génesis son **posteriores y separadas**. Lo sostienen §6.19 DH-1 y DH-2 y las decisiones F-1/F-2.

No es el CASO 4 (bug): el código cumple el modelo documentado y no contradice ninguna semántica ya decidida para el paso de aplicación, porque esa semántica no está decidida. No es el CASO 2: hace falta resolver más de un texto.

**Texto y decisión que hay que resolver:**
1. **Enmienda §5.3** (líneas 204–207): si la barrera del efecto financiero deja de ser la transición `PENDING → CONFIRMED`, ¿cuál es la barrera del paso `CONFIRMED → aplicado`? (Resuelve K-1 y H-2.)
2. **Enmienda §5.2 N9** (línea 194) y `convocatoria-resumen.md:167`: ¿la confirmación manual deja de ejecutarse como `CLEAR_FUNDS_AS_GENESIS`? ¿Quién autoriza entonces la confirmación y quién la génesis? (Resuelve K-2.)
3. **`implementation_plan.md` §4.4 y §8, §6.19 DH-3:** ¿`applyFundsForIntent` pasa a consumir intenciones `CONFIRMED` o se sustituye? (Resuelve K-3; depende de 1.)
4. Cómo se descubren las intenciones `CONFIRMED` sin aplicar (H-1).
5. Registrar F-1 y F-2 en el lugar canónico y deshacer la colisión de nombres de §6.19 (K-4).

Según `convocatoria-resumen.md:91`, los puntos 1 y 2 requieren una enmienda de ADR-037, no solo una decisión registrada en el resumen. **No debe implementarse nada del paso `CONFIRMED → aplicación` hasta resolver los puntos 1 a 4.**
