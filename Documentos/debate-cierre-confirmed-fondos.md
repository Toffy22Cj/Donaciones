# Debate de cierre — CONFIRMED → aplicación de fondos (Convocatoria, Fase 6)

**Fecha:** 2026-10-02. **Rama:** `develop`, HEAD `673eda9`; `convocatoria/` sin versionar.
**Naturaleza:** informe temporal de análisis, solo lectura. No recomienda, no ordena opciones y no decide. Sirve para que se respondan las decisiones humanas de §4.
**Restricciones de entrada (no se discuten):**
- **F-1:** confirmar y aplicar fondos son actos separados. `PENDING → CONFIRMED → aplicación posterior`; `CONFIRMED` no implica fondos aplicados.
- **F-2:** `confirmDonationIntent()` no crea `Fund`, no aplica fondos ni incrementa el ledger; la génesis y la aplicación pertenecen al flujo posterior.

Taxonomía: **A** código · **B** test/ejecución · **C** git · **D** documental · **E** inferencia · **F** contradicción · **G** no determinable.

**Afirmaciones de contexto, verificadas de nuevo en esta sesión:**

| Afirmación | Estado | Evidencia |
|---|---|---|
| `convocatoria` no tiene outbox, eventos ni scheduler | Confirmado | A (grep de `outbox`, `@Scheduled`, `EventListener`, `publishEvent`) |
| El puerto de intenciones no consulta por estado | Confirmado: `insert`, `findById`, `existsByCampaignRef`, `confirmIfPending` | A (`DonationIntentRepositoryPort`) |
| `applyFundsForIntent()` parte de `PENDING` y confirma + aplica | Confirmado | A (`CampaignFundingLedgerService:70-80`); B (22 tests) |
| Una intención `CONFIRMED` no se puede aplicar | Confirmado | A; B (V7c, `applyingAnIndependentlyConfirmedIntentAppliesNothing`) |
| El ledger es un acumulado | Confirmado: `_id = campaignRef`, `clearedAmount`; operaciones `insert`, `find`, `delete`, `incrementUnconditionally`, `incrementWithinTarget` (ninguna resta) | A |
| ADR-037 exige atomicidad entre ledger, `Fund` y outbox | ADR-037 §2.2 ("en la misma transacción MongoDB que `Fund.FUNDS_CLEARED` y el mensaje de Outbox") y §2.3 | D |
| La frontera impide `convocatoria → core/app` | `ConvocatoriaArchitectureTest` | B |
| Existen precedentes de reintento y scheduling | `BlockchainAnchorProducer` (`app`, `@Scheduled`, recupera trabajo atascado con transiciones condicionales atómicas, "benign no-op" con varias instancias); `OutboxSagaCoordinator` y `ProjectionRetryScheduler` (`core`); ADR-042 (reintentos de proyección) | A, D |
| F-1 y F-2 hacen incompatible el modelo "confirmar + aplicar" | F-1: "actos separados" y "otro flujo" | Restricción de entrada frente a A |

**Hechos adicionales comprobados que condicionan el análisis:**
- **H-α:** una génesis repetida con el mismo `commandId` termina **sin error** (`FundCommandService:84-86` y `TransactionalEventPublisher.appendAndOutbox`: `tryClaim` → `return`). Con un `commandId` distinto sobre el mismo `fundId`, choca con el índice único `{streamId, sequence}` de `event_store` (`TraceabilityEventDocument:13`) y termina en error tras los reintentos de `CommandRetryTemplate`. (A; D: Enmienda §5.3 [ESTADO])
- **H-β:** en `core`, `authorize()` **no comprueba roles** para `SystemActor` ni `ExternalActor` ("bypass"); solo `HumanActor` pasa por `RoleAuthorizationPolicy` (`FundCommandService:51-63`). `CLEAR_FUNDS_AS_GENESIS` = `{ADMINISTRATOR}` (ADR-032:47). (A, D)
- **H-γ:** meta, política y moneda no se editan (`MonetaryTermsChangeNotSupportedException`: "no existe operación de cambio de meta/política"), y el ledger no tiene ninguna operación que reste. **Un rechazo STRICT o REJECT_EXCESS es permanente en este corte.** (A)
- **H-δ:** `confirmDonationIntent()` no tiene autorización: `confirmedBy` es texto libre (A). Ya existe `ConvocatoriaAuthorizationPolicy.requireAdministratorOf(actor, organización)` sobre `IdentityPrincipalPort` (A).
- **H-ε:** el plan dejó fuera de corte el registro de dinero no aceptable porque "sin mecanismo de resolución tendría un estado sin salida (regla 2.6)" (`implementation_plan.md:40`, D). Regla 2.6 de `reglas-equipo-y-agentes.md`: "Todo estado que un objeto puede alcanzar necesita una vía de salida explícita" (D).
- **H-ζ:** regla 3.5 de `reglas-equipo-y-agentes.md`: hace falta un ADR aprobado **antes** de introducir "un mecanismo de concurrencia, reintento o recuperación de fallos nuevo" o de cambiar "el contrato de un puerto ya usado por otro módulo" (D). `convocatoria-resumen.md:91`: las decisiones humanas no enmiendan un ADR (D).

---

## Decisión 1 — Barrera de idempotencia del paso CONFIRMED → aplicado

Supuesto común, impuesto por ADR-037 §2.2 y §2.3: la barrera se escribe **en la misma transacción MongoDB** del orquestador que el ledger, la génesis y el outbox.

| Criterio | B1 — estado público nuevo (p. ej. `FUNDED`) | B2 — campo interno en `DonationIntent` | B3a — colección nueva por intención (índice único `intentId`) | B3b — registro de intenciones dentro del documento del ledger | B4a — barrera en `core` (existencia del `Fund`) | B4b — barrera en `app` |
|---|---|---|---|---|---|---|
| Dónde vive | `donation_intents` (`convocatoria`) | `donation_intents` (`convocatoria`) | Colección nueva (`convocatoria`) | `campaign_funding_ledgers` (`convocatoria`) | `event_store` / `processed_commands` (`core`) | Estado propio de `app` |
| Qué modifica | `DonationIntentStatus`, documento, mapper, adaptador | `DonationIntent`, documento, mapper, adaptador | Documento, puerto y adaptador nuevos | `CampaignFundingLedgerDocument` y el filtro de incremento | Contrato de `clearFundsGenesis` | Persistencia en `app` |
| Escritura condicional atómica | Sí: `updateFirst({_id, status: CONFIRMED}, {status: X})`, mismo patrón que `confirmIfPending` (A) | Sí: `updateFirst({_id, status: CONFIRMED, marca ≠ true}, {marca: true})` | Sí: `insert` con índice único (patrón `CampaignAssignment`, ADR-037 §2.4; `processed_commands`) | Sí: un solo documento, `{campaignRef, aplicadas: {$ne: X}, capacidad}` → `$inc` + `$push` | Solo si la génesis repetida deja de ser silenciosa | G (no hay persistencia en `app`) |
| Carreras concurrentes | Sí: `WriteConflict` → gana una (mecanismo probado partiendo de `PENDING`, B por analogía) | Igual que B1 | Sí: clave duplicada o conflicto → falla la segunda | Sí: conflicto en el documento | Depende del cambio en `core` | G |
| Reintento tras commit | No-op: el estado ya no es `CONFIRMED` | No-op: la marca ya está puesta | La inserción duplicada **falla**; hay que traducirla a no-op | No-op por el filtro `$ne` | Con un `commandId` determinista, la génesis es silenciosa (H-α) y el ledger se duplica, salvo que se cambie `core` | G |
| Muere tras el ledger, antes del commit | Rollback completo | Rollback completo | Rollback completo | Rollback completo | Rollback completo | G |
| Muere tras crear el `Fund`, antes del commit | Rollback completo | Rollback completo | Rollback completo | Rollback completo | Rollback completo | G |
| Contratos que rompe | Lista de estados de ADR-037 §2.6; estados públicos (`api-contract-matrix.md:104`, ADR-041:45); Javadoc de `DonationIntentStatus` | Lista de campos de ADR-037 §2.6 y Enmienda §5.1; plan §16.2 ("ningún campo… fuera de este plan") | Componentes de ADR-037 §2 (añade una pieza); plan §4.2 | ADR-037 §2.2 (el ledger como escritura condicional agregada); plan §8 ("el ledger no se usa como read model para contar donaciones") | Idempotencia de `processed_commands`, común a todos los comandos de `core`; Enmienda §5.3 (`commandId` determinista) | B-1 y plan §11 (`app` es solo composición) |
| ¿Cambia ADR-037? | Sí (§2.6) | Sí (§2.6 y Enmienda §5.1) | Sí (§2) | Sí (§2.2) | Sí, y además un ADR de `core` (regla 3.5) | — |
| ¿Cambia la API pública? | Sí, un estado visible más (hoy no hay endpoint, pero cambia el contrato documentado) | No | No | No | No | No |
| Migración de datos | No: módulo no desplegado (Enmienda §9.2; sin versionar) | No | No | No | No | — |
| Complejidad nueva | Una transición y un estado público más | Combinaciones estado × marca; invariante "la marca solo con `CONFIRMED`"; el estado público no refleja la financiación | Una segunda fuente de verdad sobre "aplicado", separada de la intención | El documento crece sin límite (una entrada por intención; MongoDB tiene un máximo de 16 MB por documento) (E) | Cambia la semántica de idempotencia de todo `core` | Estado de dominio en `app` |
| Evidencia a favor | La Enmienda §5.3 ya sitúa la barrera en una transición de la intención | Precedente: `paymentMethod` y `confirmationSource` son "dos campos distintos, no variantes de un único `CONFIRMED`" (`convocatoria-resumen.md:163`) | Inserción con índice único, patrón ya usado en el módulo (ADR-037 §2.4; `processed_commands`) | Escritura en un solo documento, sin coordinar varios | El `fundId` ya identifica la intención en el `Fund` | — |
| Evidencia en contra | §6.19 DH-3: "No se inventan estados nuevos; si hiciera falta uno, se reporta como hueco documental antes de implementarlo"; tu decisión D-2 ("No vamos a inventar ahora un estado adicional como FUNDED") | Plan §16.2 | ADR-037 §2.5 trata los documentos de contención como mecanismo, "no una segunda fuente de verdad" | Crecimiento sin límite (E); cambia la naturaleza del ledger | H-α; Enmienda §5.3; regla 3.5 | `app` no persiste estado de dominio (D: plan §11, B-1) |

**BARRERAS VIABLES:** B1, B2 y B3a, todas en `convocatoria` y dentro de la transacción del orquestador.

**BARRERAS REFUTADAS:**
- **B3b:** crecimiento sin límite del documento y cambio de la naturaleza del ledger (E, D).
- **B4a con los contratos vigentes:** la génesis repetida es silenciosa (A) y la Enmienda §5.3 exige un `commandId` determinista. Solo sería viable cambiando el contrato de `core`, con un ADR propio.
- **B4b:** `app` no tiene ni debe tener estado de dominio propio (D).

**DECISIÓN HUMANA NECESARIA:** elegir entre B1, B2 y B3a. Ver DH-1.

---

## Decisión 2 — Descubrimiento y recuperación de intenciones `CONFIRMED` sin aplicar

| Criterio | R1 — sondeo programado | R2 — evento / outbox de `convocatoria` | R3 — disparador externo | R4 — consulta bajo demanda | R5 — disparo inmediato + sondeo de respaldo |
|---|---|---|---|---|---|
| Mecanismo | Un scheduler busca intenciones "`CONFIRMED` y sin aplicar" y lanza la transacción de aplicación | Al confirmar se escribe un mensaje de outbox en la misma transacción; un dispatcher lo consume | El webhook o el humano que confirmó vuelve a pedir la aplicación | Un caso de uso lista las pendientes y un humano las reintenta | El que confirma lanza la aplicación de inmediato; el sondeo recoge lo que quede atascado |
| Propietario / módulo | `app` (scheduler) + `convocatoria` (consulta) | `convocatoria` (outbox) + `app` (dispatcher) | Integración externa (P3) o un humano | `convocatoria` (consulta) + caso de uso de administración | `app` + `convocatoria` |
| Cómo descubre | Consulta por estado (**puerto nuevo**) | Mensajes pendientes del outbox (**infraestructura nueva**) | No descubre: depende del actor externo | Consulta explícita | Disparo, más consulta de respaldo |
| Cómo reintenta | En el siguiente ciclo | El dispatcher reintenta los mensajes | El proveedor reentrega (contrato desconocido, P3: **G**) o el humano repite | A mano | Ciclo de respaldo |
| Tras un reinicio | Las vuelve a encontrar | Las vuelve a encontrar | **Se pierden** si el actor no repite (G) | Se encuentran solo si alguien consulta | Las vuelve a encontrar |
| Dos workers a la vez | La barrera resuelve: uno aplica, el otro no-op. Precedente: `BlockchainAnchorProducer` ("benign no-op") (A) | Igual, por la barrera | Igual | Igual | Igual |
| Idempotencia | La barrera de la Decisión 1 | La barrera; el outbox no la sustituye | La barrera | La barrera | La barrera |
| Dependencia nueva | `app → convocatoria` (prevista, plan §11); scheduler en `app` | Outbox propio de `convocatoria` (no puede usar el de `core`: B, regla de arquitectura) y la confirmación pasa a necesitar una transacción. **El dispatcher también sondea**, así que R2 incluye la mecánica de R1 | Ninguna nueva | Puerto de consulta y autorización del caso de uso | Las de R1 |
| ADR necesario | Sí: "mecanismo de reintento o recuperación nuevo" (regla 3.5) | Sí, el mismo motivo | Probablemente no (E), pero no garantiza la recuperación | No como mecanismo automático (E) | Sí (regla 3.5) |
| 1000 pendientes | Lotes con límite configurable; precedente: `max-streams-per-batch` y `max-events-per-batch` de `BlockchainAnchorProducer` (A) | Lotes del dispatcher | No aplica | Paginación | Lotes |
| Evidencia | A (precedentes en `app` y `core`); D (ADR-042 regula reintentos de proyección, no este caso) | A (no existe; `OutboxSagaCoordinator` es de `core`) | G (P3 sin contrato) | E | A (`BlockchainAnchorProducer`: flujo normal + recuperación de lotes abandonados) |

**Observaciones:**
- R3 y R4 **no garantizan** la recuperación tras la muerte del proceso; dependen de un actor externo.
- R2 no evita tener que sondear: necesita un dispatcher que también lo hace.
- **Interacción con la Decisión 3:** con R1 o R5, una intención con rechazo **permanente** (H-γ) se reintentaría en cada ciclo para siempre, salvo que la Decisión 3 le dé una salida.

**DECISIÓN HUMANA NECESARIA:** ver DH-2.

---

## Decisión 3 — Rechazo después de CONFIRMED

Hechos: en este corte, un rechazo STRICT o REJECT_EXCESS es permanente (H-γ). La Enmienda §3.3 dice que ese dinero "no entra al `Fund` ni al `CampaignFundingLedger`… y queda en un registro de dinero no aceptable", con mecanismo P1 pendiente. El plan dejó ese registro fuera de corte por la regla 2.6 (H-ε).

| Alternativa | Estados y transición | ¿Reversible? | ¿Reintento? | Dinero | P1 | Si el rechazo es permanente | ¿Sin salida? | Documentos a cambiar |
|---|---|---|---|---|---|---|---|---|
| a. Tratarlo como temporal y reintentar | `CONFIRMED` (sin cambio) | — | Sí, siempre | Sin destino | No interviene | Reintento infinito (H-γ) | **Sí, sin salida** (regla 2.6) → refutada para el caso permanente; válida solo para fallos transitorios (`WriteConflict`) | — |
| b. Estado terminal de rechazo (nombre por decidir) | `CONFIRMED → [rechazado]` | No | No | Al registro de dinero no aceptable (Enmienda §3.3) | **Lo necesita**: sin P1, el registro también queda sin salida (H-ε) | Terminal | No en la intención; sí en el registro mientras P1 siga abierto | ADR-037 §2.6; ADR-041 y `api-contract-matrix.md:104`; Enmienda §3.3 / P1 |
| c. Reutilizar `FAILED` | `CONFIRMED → FAILED` | No | No | Como en b | Como en b | Terminal | Como en b | **Contradice** la semántica documentada: `FAILED` = "el sistema sabe que [el pago] falló" (ADR-041:75), y sus transiciones son del webhook (Javadoc de `DonationIntentStatus`, P3). Aquí el pago llegó |
| d. Mantener `CONFIRMED` hasta que se pueda aplicar | `CONFIRMED` | — | Sí | Sin destino | No interviene | Nunca se aplica (H-γ) | **Sí, sin salida** → refutada para el caso permanente | — |
| e. Marca de "aplicación rechazada" en la intención + registro de P1 | `CONFIRMED` + marca | Según P1 | Según P1 | Al registro | Lo necesita | La salida es la resolución de P1 | Depende de P1 | ADR-037 §2.6 y Enmienda §5.1 (campo); Enmienda §3.3 |

**Conclusión verificable:** toda alternativa que dé salida a un rechazo permanente necesita, directa o indirectamente, el registro de dinero no aceptable, y por tanto **depende de P1** (H-ε). Las alternativas a y d quedan refutadas por la regla 2.6 en el caso permanente.

**DECISIÓN HUMANA NECESARIA:** ver DH-3 y DH-4.

---

## Decisión 4 — Autorización

| Pregunta | Evidencia | Resultado |
|---|---|---|
| A. ¿Quién puede confirmar manualmente? | Enmienda §5.2: `BANK_TRANSFER` → "humano de la organización"; N9: la confirmación manual se ejecuta como `CLEAR_FUNDS_AS_GENESIS` → `ADMINISTRATOR` (ADR-032:47); "Permitir que un EMPLOYEE confirme requeriría enmendar ADR-032" (D). Código: ninguna comprobación (A) | Con F-2, N9 deja de aplicarse literalmente a la confirmación. **DECISIÓN HUMANA NECESARIA** |
| A'. ¿Puede un humano confirmar una intención de pasarela? | N8: "La confirmación humana nunca sustituye al webhook en la pasarela" (D). El código no lo comprueba: `confirmationSource` no se contrasta con quién confirma (A) | Hueco de implementación con regla ya decidida (D frente a A) |
| B. ¿Quién dispara la aplicación posterior? | Depende de la Decisión 2: un scheduler sería `SystemActor`; el webhook, `ExternalActor` (ADR-037 §5; `contract-wiring-review.md:70`); un humano, `HumanActor`. **`core` no comprueba roles para `SystemActor` ni `ExternalActor`** (H-β) | Si es automático, la única autorización efectiva del dinero es la de la confirmación (E sobre A) |
| C. ¿Debe ser el mismo actor? | Ningún documento lo exige tras separar los actos | **DECISIÓN HUMANA NECESARIA** |
| D. ¿Puede ser un `ADMINISTRATOR`? | `ADMINISTRATOR` está autorizado a `CLEAR_FUNDS_AS_GENESIS` (ADR-032:47) y existe `requireAdministratorOf` en `convocatoria` (A) | Sí, con respaldo documental y de código, para cualquiera de los dos actos |
| E. ¿Hay precedente reutilizable? | `ConvocatoriaAuthorizationPolicy` + `IdentityPrincipalPort` (`contracts`) (A); `RoleAuthorizationPolicy` en `core` (A) | Sí, dentro de la frontera de `convocatoria` |
| F. ¿La confirmación es administrativa, consecuencia del webhook o las dos? | Enmienda §5.2: ambas, según el medio (`GATEWAY` → `PAYMENT_PROVIDER`; `BANK_TRANSFER` → `ORGANIZATION`) | Ambas, ya decidido por medio de pago (D) |

**Riesgo derivado (E sobre A):** con F-1 y F-2, una descubierta automática (R1, R5) y H-β, una confirmación sin autorización (H-δ) se convertiría en dinero aplicado sin que nadie autorice nada. **La autorización de la confirmación pasa de ser opcional a ser crítica** si la aplicación es automática.

---

## Ataques adversariales al diseño que sobrevive

Diseño atacado: el Modelo 2 de los debates anteriores, completado así:
- confirmación independiente;
- descubrimiento por R1 o R5;
- una transacción del orquestador con barrera (B1, B2 o B3a) + ledger + génesis + outbox.

Las columnas de "Código actual" describen lo que hay hoy.

| # | Ataque | Resultado esperado | Código actual | Diseño | Evidencia | Hueco |
|---|---|---|---|---|---|---|
| 1 | Dos workers, misma intención | Una aplicación | Desde `PENDING`: una (B). Desde `CONFIRMED`: ninguna | Barrera: gana una y la otra no hace nada o falla y se reintenta | B (analogía), E | — |
| 2 | Muere tras el ledger | Nada aplicado | Rollback (B) | Rollback de la transacción completa | D (§2.3), B (analogía) | Test del plan §13.3 pendiente |
| 3 | Muere tras crear el `Fund` | Nada aplicado | No hay `Fund` | Rollback, incluido el reclamo de `processed_commands` (dentro de la transacción, `tryClaim`) | A (`appendAndOutbox` `@Transactional`) | T1: `clearFundsGenesis` reintenta dentro y no debe dentro de la transacción del orquestador (plan §8) |
| 4 | A termina, B reintenta | No-op | Desde `PENDING`: `false` (B) | Barrera consumida → no-op | B (analogía) | B3a necesita traducir la clave duplicada a no-op |
| 5 | Reintento transaccional de MongoDB | Sin efectos dobles | Reintento propio sin transacción externa; sin reintento dentro de una externa (B) | Reintento de la transacción completa (Enmienda §6) | B, D | T1 |
| 6 | Reinicio tras `CONFIRMED` | Se reanuda | **Perdida** (A) | R1/R5: se reanuda. R3/R4: puede perderse | A, E | DH-2 |
| 7 | `CONFIRMED` durante días | Se termina aplicando o sale | Indefinido (A) | R1/R5 la recogen; si el rechazo es permanente, sin salida hasta DH-3 | A, E | DH-3 |
| 8 | STRICT se llena entre confirmar y aplicar | Rechazo con salida | La confirmación se acepta con STRICT lleno (V1); no se aplica | Rechazo permanente (H-γ) | A, B | DH-3, P1 |
| 9 | Límite alcanzado en dos aplicaciones concurrentes | Una entra, la otra rechazada | Una aplicada, la otra rechazada (B: `concurrentStrictApplications…`) | Igual, por el filtro de capacidad | B | La rechazada → DH-3 |
| 10 | Vuelve a entrar el mismo `commandId` | No-op | La aplicación no usa `commandId`; la génesis es silenciosa (A) | La barrera decide antes de la génesis | A | — |
| 11 | `commandId` distintos, misma intención | Una aplicación | No aplica | La barrera por intención impide el segundo efecto; sin barrera, la génesis fallaría por el índice de `event_store` y el ledger se revertiría con ella | A, E | Exige que la barrera vaya antes de la génesis en la transacción |
| 12 | Confirma un actor sin autorización | Rechazo | **Se acepta** (A) | Con aplicación automática y H-β, se aplica dinero sin autorización | A, E | **DH-5** |
| 13 | Aplica un actor distinto del que confirmó | Según la regla | No aplica | Sin regla | G | DH-6 |
| 14 | 1000 pendientes | Procesado acotado | No hay descubrimiento | Lotes (precedente `max-*-per-batch`) | A (precedente) | Parámetro de implementación |
| 15 | Un worker procesa una intención que otro acaba de marcar | No-op | No aplica | La escritura condicional falla para el segundo | B (analogía) | — |

---

## Matriz A — Decisiones

| Decisión | Restricción | Código actual | ADR | Docs | Opciones viables | Decisión humana |
|---|---|---|---|---|---|---|
| Barrera | ADR-037 §2.2/§2.3 (una transacción); H-α | Solo `PENDING → CONFIRMED` | §5.3 (barrera = transición desde `PENDING`) | §6.19 DH-3 (sin estados nuevos sin reportarlo antes) | B1, B2, B3a | DH-1 |
| Descubrimiento | Regla 3.5 (ADR para la recuperación) | Ninguno | — | Precedentes en `app`/`core` | R1, R2, R5 (R3, R4 sin garantía) | DH-2 |
| Rechazo tras `CONFIRMED` | Regla 2.6; H-γ; Enmienda §3.3 | No aplica | §3.3, P1 | Plan:40 | b, e (a y d refutadas; c contradice ADR-041) | DH-3, DH-4 |
| Autorización de la confirmación | N8, N9; H-β | Ninguna | ADR-032:47 | §5.2 | `ADMINISTRATOR` vía `requireAdministratorOf`, u otra | DH-5 |
| Autorización de la aplicación | H-β | — | ADR-037 §5 | — | Sistema, externo o humano | DH-6 |
| Operación de `convocatoria` | F-1 | Modelo 3 (confirmar + aplicar) | §5.3 | Plan §4.4/§8 | Cambiarla para consumir `CONFIRMED` | (derivada de F-1, no requiere DH) |
| Normativa | `convocatoria-resumen.md:91` | — | §5.3, N9 vigentes | — | Enmienda 2 | DH-7 |

## Matriz B — Flujo

| Paso | Módulo | Operación | Transacción | Barrera | Retry | Estado |
|---|---|---|---|---|---|---|
| PENDING | `convocatoria` | `createDonationIntent` | Propia (idempotente por `commandId`) | `processed_commands` | Helper | Existe (A, B) |
| → CONFIRMED | `convocatoria` | `confirmDonationIntent` | Escritura de un documento (o la de quien llame) | `confirmIfPending` | — | Existe (A, B); **sin autorización** |
| [descubrimiento] | `app` + `convocatoria` (R1/R5) o `convocatoria` (R2) | Consulta por estado / outbox | Lectura | — | Ciclo | **No existe** (A) — DH-2 |
| [aplicación] | `app` (orquestador) | Composición | **Una transacción MongoDB** | B1, B2 o B3a (`convocatoria`) | Transacción completa (Enmienda §6, T1) | **No existe** — DH-1 |
| LEDGER | `convocatoria` | Incremento por política | La del orquestador | Filtro de capacidad | Con la transacción | Existe (A), hoy acoplado a la confirmación |
| FUND | `core` | `clearFundsGenesis` (`commandId` derivado) | La del orquestador | `processed_commands` + índice de `event_store` | **Prohibido dentro** (T1) | Existe (A); T1 pendiente |
| OUTBOX | `core` | `appendAndOutbox` | La del orquestador (`REQUIRED`) | — | Con la transacción | Existe (A) |
| [estado final] | `convocatoria` | Barrera consumida, o salida de rechazo | La del orquestador / la de P1 | — | — | Sin definir — DH-1, DH-3 |

## Matriz C — Contradicciones

| ID | Fuente A | Fuente B | Contradicción | ¿La resuelven F-1/F-2? | Acción |
|---|---|---|---|---|---|
| K-1 | Enmienda §5.3 (barrera `PENDING → CONFIRMED` en la misma transacción que el efecto) | F-1 | La barrera se gasta antes del efecto | F-1 la **crea**, no la resuelve | Enmienda 2 (DH-7) |
| K-2 | Enmienda N9 (confirmación manual = `CLEAR_FUNDS_AS_GENESIS`); `convocatoria-resumen.md:167` | F-2 | Confirmar ≠ génesis | F-2 deja obsoleta esa parte de N9; la autorización queda sin regla | Enmienda 2 + DH-5 |
| K-3 | Plan §4.4/§8; §6.19 DH-3; `estado-fase6.md:71`; código | F-1 | La operación oficial confirma y aplica | F-1 obliga a cambiar código ya probado | Cambio de código tras la enmienda |
| K-4 | ADR-037 §2.6 paso 3; `golden-path.md:30-33` | F-1/F-2 | El webhook confirma y genera en un paso | Parcialmente: el webhook puede confirmar y lanzar la aplicación como dos actos | Actualizar textos |
| K-5 | §6.19 y `estado-fase6.md:70`: "F-1"/"F-2" = contradicciones | Decisiones F-1/F-2 | Colisión de nombres | No | Registro canónico |
| K-6 | `FAILED` = fallo de pago (ADR-041:75) | Alternativa c de la Decisión 3 | Significado distinto | No | Evitar o enmendar |
| K-7 | N8 ("la confirmación humana nunca sustituye al webhook en la pasarela") | Código: `confirmDonationIntent` admite cualquier `confirmedBy` | Regla decidida que el código no aplica | No | DH-5 / código |

---

## 1. Lo que ya está cerrado

- `confirmDonationIntent()` cumple F-2: solo modifica el documento de la intención (A; B: V10).
- La idempotencia y la concurrencia de las escrituras condicionales en MongoDB, en este módulo, están probadas como mecanismo, partiendo de `PENDING` (B).
- Un llamador externo que captura la excepción no puede confirmar la transacción (B, validado por mutación).
- `Fund` se gestiona en `core` y su composición con el ledger en `app`; `convocatoria` no puede hacerlo (A, B, D).
- El ledger, el `Fund` y el outbox deben ir en una transacción (D: ADR-037 §2.2, §2.3).
- Un rechazo STRICT es permanente en este corte (A).
- `core` no comprueba roles para actores de sistema ni externos (A).

## 2. Lo que está refutado

- **El modelo "una operación confirma y aplica"** (Modelo 3, que es el código actual): por F-1.
- **Ledger y `Fund` en momentos distintos** (Modelo 1): por ADR-037 §2.2 y §2.3.
- **Evento u outbox como alternativa en sí misma** (Modelo 4): no hay infraestructura, y en la práctica es una forma de descubrimiento.
- **Barreras B3b, B4a (con los contratos vigentes) y B4b** (Decisión 1).
- **Rechazo permanente que se queda en `CONFIRMED`** (alternativas a y d): por la regla 2.6.
- **Reutilizar `FAILED` para el rechazo del ledger** (alternativa c): contradice ADR-041:75 y la propiedad del webhook (P3).
- **Descubrimiento solo con R3 o R4:** no garantiza la recuperación tras la muerte del proceso.

## 3. Lo que sobrevive

- Flujo: confirmación independiente → descubrimiento → **una** transacción del orquestador de `app` con barrera por intención en `convocatoria` + ledger + génesis (`commandId` derivado) + outbox.
- Barreras compatibles: **B1, B2, B3a**.
- Descubrimiento compatible: **R1, R2, R5**. R3 y R4 solo como complemento.
- Rechazo permanente compatible: **b o e**, ambas dependientes de P1.
- Autorización: **`ADMINISTRATOR`** respaldado para ambos actos; el resto, sin determinar.

## 4. Decisiones humanas necesarias

1. **DH-1:** ¿la barrera del paso `CONFIRMED → aplicado` es **(B1)** un estado público nuevo, **(B2)** un campo interno en `DonationIntent` o **(B3a)** un registro separado por intención con índice único?
2. **DH-2:** ¿cómo se descubre una intención `CONFIRMED` sin aplicar: **(R1)** sondeo programado en `app`, **(R2)** outbox propio de `convocatoria` con dispatcher, o **(R5)** disparo inmediato con sondeo de respaldo?
3. **DH-3:** si el ledger rechaza de forma permanente una intención `CONFIRMED`, ¿pasa **(b)** a un estado terminal de rechazo o **(e)** se queda en `CONFIRMED` con una marca de "aplicación rechazada"? En los dos casos el dinero va al registro de dinero no aceptable.
4. **DH-4:** como cualquier salida de un rechazo permanente depende del registro de dinero no aceptable, y ese registro está fuera de corte por la regla 2.6 mientras P1 siga abierto, ¿**(i)** se decide ahora P1 (al menos el estado de salida de ese registro), o **(ii)** se acepta que el cierre de Convocatoria deja explícitamente fuera de alcance el rechazo permanente de una intención `CONFIRMED`?
5. **DH-5:** ¿quién puede confirmar manualmente una intención (`BANK_TRANSFER`): **(i)** solo `ADMINISTRATOR` de la organización, mediante `requireAdministratorOf`, u **(ii)** otro rol, lo que exigiría enmendar ADR-032? ¿Debe bloquearse en código que un humano confirme una intención de pasarela (N8)?
6. **DH-6:** ¿quién dispara la aplicación posterior: **(i)** el sistema (`SystemActor`, sin comprobación de roles en `core`), **(ii)** el mismo actor que confirmó, o **(iii)** un `ADMINISTRATOR` como acto separado?
7. **DH-7:** ¿se aprueba redactar una **Enmienda 2 de ADR-037** que sustituya la barrera de §5.3, la parte de N9 sobre `CLEAR_FUNDS_AS_GENESIS` y, según DH-1 y DH-3, la lista de estados de §2.6, junto con el ADR de recuperación que exige la regla 3.5 para DH-2?

## 5. Cambios que serán necesarios después (no implementados)

- **`convocatoria`:**
  - `applyFundsForIntent` pasa a consumir `CONFIRMED`, con la barrera de DH-1 y sin parámetro de confirmación.
  - Barrera en `DonationIntent` o un registro nuevo.
  - Consulta por estado (R1/R5) u outbox (R2).
  - Autorización en `confirmDonationIntent` (DH-5) y comprobación de N8.
  - Salida del rechazo permanente (DH-3).
- **`core`:**
  - T1: camino sin reintento interno de `clearFundsGenesis` para el orquestador (plan §8).
  - Sin cambios de idempotencia si no se elige B4a.
- **`app`:**
  - Dependencia `app → convocatoria`.
  - Orquestador transaccional.
  - Scheduler o dispatcher (DH-2).
  - Actor de la aplicación (DH-6).
- **`contracts`:** nada si `app` usa directamente los servicios de `convocatoria` y `core` (E); un puerto nuevo solo si la decisión lo exige.
- **ADR / documentación:**
  - Enmienda 2 de ADR-037 (§5.3, N9, §2.6).
  - ADR de recuperación (regla 3.5).
  - ADR-041 y `api-contract-matrix.md` si hay estado público.
  - ADR-032 si cambia quién confirma.
  - `convocatoria-resumen.md`: registro de F-1, F-2 y las DH; corrección de §6.19 y la colisión de nombres.
  - `implementation_plan.md`: §4.4, §7.2, §8, §9, §11, §13.
  - `estado-fase6.md` y `golden-path.md:30-33`.
- **Tests:**
  - Reescribir los 22 de `CampaignFundingLedgerIntegrationTest` y el escenario, que hoy prueban el Modelo 3.
  - Barrera desde `CONFIRMED`: secuencial, concurrente, tras reinicio y con `commandId` distintos.
  - Descubrimiento tras reinicio y con dos workers.
  - Rechazo permanente con salida.
  - Autorización de la confirmación.
  - Test de cierre del orquestador del plan §13.3: rollback conjunto y reintento de la transacción completa.

## 6. Criterio de cierre de Convocatoria (condiciones verificables)

1. **Contrato:** DH-1 a DH-7 respondidas y registradas en `convocatoria-resumen.md`; Enmienda 2 y ADR de recuperación aprobados (regla 3.5; `convocatoria-resumen.md:91`).
2. **Código:**
   - Ninguna operación de `convocatoria` confirma y aplica a la vez (A).
   - Barrera escrita de forma condicional en la transacción del orquestador (A).
   - La confirmación está autorizada (A).
3. **Tests** (salida literal de Surefire con 0 fallos en `mvn test -pl convocatoria` y en el reactor completo), cubriendo:
   - aplicación desde `CONFIRMED`, sola y repetida;
   - concurrencia real (`CyclicBarrier`, MongoDB en replica set);
   - reinicio del contexto;
   - `commandId` distintos;
   - rechazo permanente con salida;
   - autorización.
4. **Recuperación:** un test demuestra que una intención `CONFIRMED` sobrevive a un reinicio y se termina aplicando exactamente una vez (B).
5. **Orquestador:** el test del plan §13.3 está en verde, y T1 resuelto en `core` (B).
6. **Documentación:** ninguna fila de la Matriz C queda sin acción; los documentos de §5 están actualizados.
7. **Auditoría final:** una auditoría adversarial independiente no reproduce ninguna de las violaciones de la tabla de ataques (B).
8. **Git:** `convocatoria/` versionado y los cambios en una rama `feat/` con PR (regla 3.1), no en el árbol sin versionar.

## 7. Dictamen

**REQUIERE DECISIONES HUMANAS.**

El repositorio permite refutar el modelo actual y las alternativas de §2. Lo que queda (§3) es técnicamente compatible con las restricciones. Pero siete decisiones (§4) no se pueden deducir de la evidencia. Dos de ellas (DH-4, DH-7) dependen además de P1 y de enmiendas que, por las reglas del propio proyecto, deben aprobarse antes de escribir código.
