# Auditoría adversarial pre-implementación — Cierre de Convocatoria (Fase 6)

**Fecha:** 2026-10-02. **Rama:** `develop`, HEAD `673eda9`. **Naturaleza:** informe temporal, solo lectura. No modifica código, tests ni documentación canónica.
**Decisiones auditadas:** D1–D7 (decisión humana del 2026-10-02), sobre las restricciones F-1 y F-2.

Taxonomía: **A** código · **B** test (reproducido ahora) · **C** git · **D** documental · **E** inferencia · **F** contradicción · **G** no determinable.

**Reproducido ahora (B):** `mvn -o test -pl convocatoria` → `Tests run: 180, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. Por clases: `IdempotentCommandExecutorIntegrationTest` 5, `CampaignFundingLedgerIntegrationTest` 22, `ConfirmDonationIntentIntegrationTest` 8, `ConvocatoriaAuthorizationPolicyTest` 8, `ConvocatoriaTransactionRetryHelperIntegrationTest` 2, escenario 5, arquitectura 2.
**Histórico documental, no reproducido aquí:** el reactor completo `229/50/19/34/71/180/23` (`estado-fase6.md`).

---

## 0. Hechos nuevos que condicionan el dictamen

| ID | Hecho | Evidencia |
|---|---|---|
| X-1 | **No existe ninguna implementación de producción de `OrganizationVerificationPort`**: solo el fake de test. `identity` no tiene el dato de verificación. El plan §11 dice que sin esa implementación "el contexto completo de `app` no arranca con los servicios de `convocatoria`" (`ApplicationContextLoadTest`) | A (grep de `implements OrganizationVerificationPort`; `identity` sin `verificationStatus`); D (plan §11, X1, ADR-038) |
| X-2 | **La génesis del `Fund` no escribe mensaje de outbox**: `appendAndOutbox(fundId, "Fund", 0, events, actorRef, List.of(), commandId)` (`FundCommandService:94`). En `core`, el outbox solo lo usan las sagas de `PhysicalAsset` | A |
| X-3 | **`convocatoria` ya tiene un registro con clave única por comando, dentro de la transacción**: `IdempotentCommandExecutor` + `MongoProcessedCommandAdapter` (reclamo con `findAndModify` + `upsert`; ante un duplicado devuelve el resultado guardado, N12; si la transacción se revierte, el reclamo también). Probado con concurrencia real | A; B (`concurrentSameCommandIdHasSingleEffectAndBothGetOriginalResult`, `duplicateReturnsOriginalResultWithoutRepeatingEffect`, `domainFailureLeavesNoClaimAndResendExecutesAgain`) |
| X-4 | `IdempotentCommandExecutor` **reintenta por dentro** (`executeWithRetry`). Dentro de la transacción del orquestador eso está prohibido (Enmienda §6). Es el mismo problema que T1 tiene en `core` (`CommandRetryTemplate`) | A; D (Enmienda §6; plan §8, T1) |
| X-5 | En `core`, `authorize()` **no comprueba roles** para `SystemActor` ni `ExternalActor`; solo `HumanActor` pasa por `OrganizationBoundaryPolicy` + `RoleAuthorizationPolicy` (`CLEAR_FUNDS_AS_GENESIS` → `ADMINISTRATOR`, `RoleAuthorizationPolicy:20`) | A |
| X-6 | `convocatoria` no puede depender de `app` (regla de arquitectura) ni tiene eventos: **el "disparo inmediato" no puede salir de `convocatoria`**; tiene que hacerlo quien llame a la confirmación, desde `app` | A, B (`ConvocatoriaArchitectureTest`) |
| X-7 | El origen de la intención ya está registrado: `paymentMethod` (`GATEWAY`, `BANK_TRANSFER`, `CASH`) y `confirmationSource` (`PAYMENT_PROVIDER`, `ORGANIZATION`) | A (`DonationIntent`, documento) |
| X-8 | `ConfirmDonationIntentCommand(intentId, confirmedBy, reference)`: `confirmedBy` es **texto libre**; no hay autorización. Ya existe `ConvocatoriaAuthorizationPolicy.requireAdministratorOf(accountId, org)`, que comprueba organización y rol con `IdentityPrincipalPort` | A |
| X-9 | `IdentityPrincipalPort` no filtra cuentas `INACTIVE` (X2) | D (plan §11, "verificado") |
| X-10 | Meta, política y moneda no se editan, y el ledger no resta: **un rechazo STRICT es permanente** | A |
| X-11 | El ledger solo puede borrarse en una edición directa que quita `MONETARY`, y esa edición exige que no haya intenciones (H1). Con intenciones existentes, no puede faltar el ledger | A (`ConvocatoriaLifecycleService:111-119`) |
| X-12 | Regla 3.5: hace falta un ADR aprobado antes de introducir "un mecanismo de concurrencia, reintento o recuperación de fallos nuevo". `convocatoria-resumen.md:91`: las decisiones humanas no enmiendan un ADR | D |
| X-13 | Enmienda §2.1 [DECISIÓN], alternativa descartada: "**Mecanismo de idempotencia propio de Convocatoria, distinto del de `core`**: crearía una segunda semántica de idempotencia en el sistema sin necesidad". §3.5: los comandos reutilizan el **patrón** `commandId` + registro de comandos procesados | D |

---

## 1. Intento de refutación por decisión

### D1 — Barrera: registro separado por intención con índice único

| Pregunta | Respuesta | Evidencia |
|---|---|---|
| ¿Puede participar en la misma transacción? | Sí: un `insert` o reclamo sobre un índice único, dentro de la transacción del orquestador (mismo `MongoTransactionManager` de `app`, ADR-037 §2.3) | A (patrón X-3); D |
| ¿Qué módulo lo posee? | `convocatoria`: es la dueña de la intención y del ledger; `core` no puede ver la intención y `app` no tiene estado de dominio | A, D |
| ¿Existe una abstracción equivalente? | **Sí:** el registro de comandos procesados de `convocatoria` (X-3), con clave determinista derivada de la intención (la Enmienda §5.3 ya exige un `commandId` derivado de la intención) | A, D |
| ¿El índice único impide la doble aplicación con concurrencia? | Sí: el perdedor recibe un conflicto de escritura o una clave duplicada y no llega al ledger, **siempre que el reclamo vaya antes del incremento** dentro de la transacción | B (X-3, por analogía); E (orden) |
| ¿Muere tras la barrera y antes del `Fund`? | Si va en la misma transacción: rollback; la barrera no queda escrita y la intención sigue `CONFIRMED`, recuperable | D (§2.3); B (`domainFailureLeavesNoClaim…`, por analogía) |
| ¿Se crea el `Fund` pero falla el ledger? | Rollback conjunto. Requiere T1 (X-4) | D, A |
| ¿Se incrementa el ledger y falla el outbox? | Hoy la génesis no escribe outbox (X-2); no hay fallo de outbox posible. Lo que el outbox de §2.3 debe contener es **G** | A, G |
| ¿Hay una transacción única capaz de garantizarlo? | El mecanismo existe (`MongoTransactionManager` en `app`, `appendAndOutbox` con `REQUIRED`), pero **no puede ejecutarse hoy**: `app` no puede componer `convocatoria` (X-1) y faltan los caminos sin reintento interno (X-4, T1) | A, D |
| Refutación | **Si D1 se implementa como una colección nueva con su propia semántica de idempotencia, choca con la Enmienda §2.1 (X-13) → F.** Si se implementa con el registro de comandos procesados existente (nuevo `CommandType` y clave derivada de la intención), es el mismo patrón y no hay contradicción | D, F |

**Sobrevive, con una condición:** la "barrera separada" debe ser el registro de comandos procesados de `convocatoria`, o una colección que la Enmienda 2 declare explícitamente como ese mismo patrón. Exige una variante sin reintento interno para usarla dentro de la transacción del orquestador (X-4).

### D2 — Disparo inmediato + scheduler de respaldo

| Pregunta | Respuesta | Evidencia |
|---|---|---|
| ¿Hay un scheduler reutilizable? | Ninguno reutilizable tal cual: los existentes son específicos de su dominio. **Sí hay un patrón reutilizable:** `BlockchainAnchorProducer` (`app`): `@Scheduled`, lotes con límite configurable, recuperación de trabajo atascado y transiciones condicionales ("benign no-op" si otra instancia ya lo hizo) | A, C (`7a51ecb`, `793d4b8`) |
| ¿Varias instancias? | Sí: la barrera de D1 decide; el perdedor no hace nada | A (patrón), B (X-3) |
| ¿Hay patrón de reclamo o bloqueo? | Escritura condicional atómica (`incrementRecoveryAttempts`, `transitionCollectingToPending`) y reclamo con `upsert` (X-3) | A |
| ¿Dónde vive? | En `app`: tiene que invocar `convocatoria` y `core`. `convocatoria` aporta la consulta | A (X-6, regla de arquitectura) |
| ¿Hace falta un ADR? | **Sí** (regla 3.5, X-12). ADR-042 regula reintentos de **proyecciones** en `core`: extenderlo mezclaría ámbitos; crear uno nuevo o extender ese es decisión humana | D |
| ¿Puede salir el disparo inmediato de `convocatoria`? | **No** (X-6). Debe hacerlo el caso de uso de `app` que llama a la confirmación (transacción 1) y luego a la aplicación (transacción 2) | A, B |
| Coste del descubrimiento | Con D1 (barrera fuera de la intención) y sin redefinir `CONFIRMED`, las intenciones aplicadas **siguen en `CONFIRMED` para siempre**. Encontrar las pendientes exige cruzar dos colecciones ("`CONFIRMED` sin registro"), y el conjunto que se recorre crece con el histórico | E |
| Refutación | No se refuta. Tiene dos consecuencias: X-6 obliga a que la confirmación pase por `app`, y el descubrimiento tiene un coste creciente | A, E |

**Sobrevive.**

### D3 — Estado terminal de rechazo de aplicación

| Pregunta | Respuesta | Evidencia |
|---|---|---|
| Estados actuales | `PENDING`, `CONFIRMED`, `FAILED`, `EXPIRED_UNKNOWN` (enum y ADR-037 §2.6) | A, D |
| ¿Sirve `FAILED`? | **No:** = "el sistema sabe que [el pago] falló" (ADR-041:75), y sus transiciones son del webhook (Javadoc de `DonationIntentStatus`, P3). Aquí el pago llegó | D |
| ¿Hay una taxonomía equivalente? | **No** en `DonationIntent`. Convenciones del dominio: participio en `UPPER_SNAKE` (`CONFIRMED`, `FAILED`, `CLOSED`, `REMOVED`, `CLEARED`, `REFUNDED`, y el compuesto `EXPIRED_UNKNOWN`). Vocabulario documental de **este mismo caso** (Enmienda §3.3): "rechazado por `STRICT`/`CLOSE_ON_TARGET + REJECT_EXCESS`" y "dinero no aceptable"; enum `OnTargetReached.REJECT_EXCESS` | A, D |
| ¿Nombre? | El vocabulario apunta a la raíz "rechaz-/REJECT". Un `REJECTED` sin más sería ambiguo frente al rechazo de un pago (que es `FAILED`); un compuesto que diga "aplicación de fondos rechazada" sigue la convención de `EXPIRED_UNKNOWN`. **No se puede determinar del repositorio: G** | E, G |
| ¿Qué es "permanente"? | Clasificación según el código: `CampaignFundingLimitExceededException` → permanente (X-10). `CloseOnTargetCloseNotSupportedException` → permanente dentro del corte (R4). `CampaignNotFoundException` → no debería ocurrir con intenciones existentes (X-11): anomalía, G. `InvalidFundingAmountException` → imposible para importes validados al crear. Conflicto transitorio (`TransientTransactionError`) → no es permanente | A, G |
| ¿Se puede reintentar? | Desde el estado terminal, no, por definición. Antes de escribirlo, el rechazo es determinista y se repetiría (X-10) | A, E |
| ¿Y el ledger? | Sin efecto: la transacción de aplicación se revierte. El estado terminal se escribe **en otra transacción**, porque la del rechazo queda marcada para rollback (`@Transactional(SUPPORTS)`, B) | A, B |
| ¿Y P1? | El dinero queda sin registro (ver D4) | D |
| ¿Algún documento lo impide? | Ninguno lo prohíbe. Exige enmendar ADR-037 §2.6, ADR-041 y `api-contract-matrix.md:104` (la lista de estados) | D |
| Riesgo de carrera | Escribir el estado terminal debe ser condicional a `CONFIRMED` y comprobar el registro de D1 dentro de una transacción; si no, podría marcarse como rechazada una intención que otra instancia acaba de aplicar | E |
| Refutación | No se refuta. Le falta el nombre (G) y la clasificación de las excepciones permanentes, que no está escrita (G) | — |

**Sobrevive, con dos cosas por precisar.**

### D4 — P1 fuera de corte

| Pregunta | Respuesta | Evidencia |
|---|---|---|
| ¿Qué flujos quedan incompletos? | Tras el estado terminal de D3, el dinero recibido queda sin registro y sin resolución | D (Enmienda §3.3, P1) |
| ¿Contradice algún requisito? | La Enmienda §3.3 [DECISIÓN] exige que ese dinero "queda en un registro de dinero no aceptable". El plan §1.3 (línea 40) y §14.1 ya lo dejaron fuera de corte por la regla 2.6. **No hay contradicción mientras no entre dinero real** (no hay webhook ni orquestador, A). **Sí la habría si se habilita el flujo con dinero real sin P1** | D, A |
| ¿Transición expresamente fuera de alcance? | El destino del dinero de una intención en el estado terminal de D3. La intención sí tiene salida | E |
| Dependencia futura | El registro de dinero no aceptable (N4: CRUD + audit log en `convocatoria`), escrito en la transacción que fije el estado terminal o junto a ella, con la resolución de P1 | D |

**Sobrevive como condición:** P1 bloquea habilitar el flujo con dinero real (webhook u orquestador en producción), no el código de `convocatoria`.

### D5 — Confirmación manual: solo `ADMINISTRATOR`; nunca para intenciones de pasarela

| Pregunta | Respuesta | Evidencia |
|---|---|---|
| ¿Hay una política reutilizable? | **Sí:** `ConvocatoriaAuthorizationPolicy.requireAdministratorOf` (X-8). `RoleAuthorizationPolicy` y `OrganizationBoundaryPolicy` son de `core` y `convocatoria` no puede importarlas (ADR-037 §4) | A, D |
| ¿Se puede usar directamente? | Sí, si `confirmedBy` pasa a ser el `accountId` del actor. **Es un cambio en el comando interno de `convocatoria`, no en `contracts`** | A |
| ¿`SystemActor` se salta la autorización? | Solo en `core` (X-5). La confirmación vive en `convocatoria`, que tiene su propia política: no le afecta | A |
| ¿Cómo distinguir manual de pasarela? | `paymentMethod == GATEWAY` / `confirmationSource == PAYMENT_PROVIDER`, datos que ya existen (X-7). No hace falta cambiar `contracts` | A |
| Consecuencia | **Con D5, en este corte ninguna intención `GATEWAY` puede llegar a `CONFIRMED`**: su camino es el webhook (P3), que no existe y que no tiene en `convocatoria` ninguna forma de representar al actor externo | A, D |
| Riesgos | Cuentas `INACTIVE` no filtradas (X-9, ADR-038): "`ADMINISTRATOR` autorizado" no se garantiza del todo hasta ADR-038. Los tests actuales confirman intenciones `GATEWAY` con `confirmedBy = "provider"`: habrá que reescribirlos (B) | D, B |
| ¿Contradicción? | N9 ("se ejecuta como `CLEAR_FUNDS_AS_GENESIS`") queda obsoleta por F-2; la exigencia de `ADMINISTRATOR` coincide con ella. Se resuelve en la Enmienda 2 (D7) | D |

**Sobrevive.** Le falta el camino de confirmación de pasarela (P3), fuera de corte.

### D6 — La aplicación la ejecuta el sistema

| Pregunta | Respuesta | Evidencia |
|---|---|---|
| Dónde vive | Composición en **`app`** (ADR-037 §2, §2.3). `convocatoria`: barrera + ledger (+ estado terminal). `core`: génesis (`clearFundsGenesis`) | D, B |
| El `Fund` | `core` | A |
| El ledger | `convocatoria` (incremento por política, escritura condicional) | A |
| El outbox | `core` (`appendAndOutbox`), **hoy sin mensaje para la génesis** (X-2) | A |
| ¿Hay una transacción que lo cubra todo? | El mecanismo sí (`MongoTransactionManager` en `app`; participación `REQUIRED`/`SUPPORTS`). **Su ejecución está bloqueada:** X-1 (`app` no puede componer `convocatoria`), X-4/T1 (reintentos internos) | A, D |
| Actor | `SystemActor`: `core` no comprueba roles (X-5), así que **la única autorización del movimiento de dinero es la de la confirmación (D5)**. Los eventos del `Fund` registran `SystemActor`; el humano consta en los datos de confirmación de la intención (N8) | A, E |
| Refutación | No se refuta en diseño. Se bloquea en ejecución por X-1 y X-4 | A |

**Sobrevive en diseño; bloqueada en capacidad.**

### D7 — Enmienda 2 + ADR de recuperación

| Pregunta | Respuesta | Evidencia |
|---|---|---|
| ¿Basta la Enmienda 2? | Para ADR-037, sí. **No basta para todo:** el estado nuevo toca ADR-041 (estados públicos) y la recuperación necesita su ADR (regla 3.5) | D |
| Qué debe cambiar | §5.3 (barrera); N9; §2.6 (estado terminal y paso 3); §2 / §9.2 (pieza nueva si la barrera no es el registro de comandos procesados); §3.3 / P1 (alcance); §5.2 (confirmación de pasarela solo por el proveedor) | D |
| ¿Hace falta un ADR adicional? | Sí, el de recuperación (X-12). Extender ADR-042 o crear uno nuevo es decisión humana | D |
| Otros | ADR-041 y `api-contract-matrix.md:104`. ADR-032 **no**, porque `ADMINISTRATOR` ya está autorizado | D |

**Sobrevive.**

---

## 2. Casos adversariales

| Caso | Esperado | Situación actual | Diseño D1–D7 | Evidencia | Problema |
|---|---|---|---|---|---|
| A. Camino feliz | Confirmación autorizada → `CONFIRMED` → disparo → barrera + ledger + `Fund` + outbox | La confirmación no está autorizada; `applyFundsForIntent` exige `PENDING`; no hay orquestador | `app`: caso de uso que confirma (transacción 1) y aplica (transacción 2: barrera `convocatoria` + ledger `convocatoria` + génesis `core` + outbox `core`) | A, D | X-1, X-4, X-2 |
| B. Muere tras `CONFIRMED` | Recuperación | Intención perdida (no hay descubrimiento) | El scheduler la encuentra ("`CONFIRMED` sin registro") | A, E | Coste de la consulta (D2) |
| C. Doble disparo | Una aplicación | Desde `PENDING`: una (B) | Barrera de clave única: una (B por analogía, X-3) | B | Orden: barrera antes del ledger |
| D. Reintento | Una aplicación | Desde `PENDING`: no-op (B) | El reclamo existente devuelve el resultado original (N12): no-op | B (X-3) | Variante sin reintento interno (X-4) |
| E. Fallo antes del ledger | Recuperable, sin falsa aplicación | Rollback (B) | Rollback; barrera no escrita; sigue `CONFIRMED` | B (analogía), D | — |
| F. Fallo después del ledger | Rollback | Rollback (B) | Rollback de toda la transacción; requiere T1 | B, D | X-4 |
| G. STRICT rechazado | Estado terminal (D3) | Excepción + rollback; la intención sigue `PENDING` (B) | Rollback; estado terminal en otra transacción condicionada | A, B, E | Nombre (G), clasificación de excepciones (G), P1 |
| H. Confirmación no autorizada | Rechazo | **Se acepta** (A) | `requireAdministratorOf` → `ActorRoleNotAllowedException` / `ActorNotInCampaignOrganizationException` | A, B (`ConvocatoriaAuthorizationPolicyTest`, 8) | `INACTIVE` (X-9) |
| I. Intención de pasarela confirmada a mano | Rechazo | **Se acepta** (A; los tests lo hacen) | Rechazo por `paymentMethod`/`confirmationSource` | A | Excepción nombrada por definir (regla 2.6) |
| J. Dos intenciones compiten por el límite | Una entra | Una aplicada, otra rechazada (B: `concurrentStrictApplications…`) | Igual: el filtro de capacidad sigue siendo una escritura condicional de un solo documento dentro de la transacción. La rechazada → caso G | B | — |

---

## 3. Matrices

### Matriz 1 — Decisiones humanas

| Decisión | Evidencia | Código compatible | Tests | Contradicción | Riesgo | Estado |
|---|---|---|---|---|---|---|
| D1 | X-3, X-13, ADR-037 §2.3 | Parcial: el registro existe; falta la variante sin reintento | X-3 (B) | **F si es una colección con semántica propia** (Enmienda §2.1) | Orden barrera→ledger | Sobrevive con condición |
| D2 | `BlockchainAnchorProducer`, regla 3.5, X-6 | No existe | — | — | Coste del descubrimiento; ADR | Sobrevive |
| D3 | Estados, ADR-041:75, X-10 | No existe | Rechazo desde `PENDING` (B) | Ninguna, si se enmienda §2.6/ADR-041 | Nombre y clasificación (G) | Sobrevive con dos precisiones |
| D4 | Enmienda §3.3; plan §1.3, §14.1 | — | — | Solo si se habilita dinero real | Dinero sin registro | Sobrevive como condición |
| D5 | X-7, X-8, N8, N9 | Política existente | `ConvocatoriaAuthorizationPolicyTest` (B) | N9 (se resuelve en D7) | `INACTIVE`; `GATEWAY` sin confirmación en el corte | Sobrevive |
| D6 | ADR-037 §2/§2.3, X-5 | El mecanismo sí; la ejecución no | — | — | Autorización solo en la confirmación | **Bloqueada por X-1 y X-4** |
| D7 | X-12 | — | — | — | — | Sobrevive |

### Matriz 2 — Flujo

| Paso | Módulo responsable | Código existente | Contrato | Transacción | Recuperación | Estado |
|---|---|---|---|---|---|---|
| Crear intención | `convocatoria` | Sí | Sí | Propia (idempotente) | — | Existe |
| Confirmar (`BANK_TRANSFER`, `ADMINISTRATOR`) | `convocatoria`, invocada desde `app` | Sí, sin autorización | Cambia el comando | Transacción 1 | — | Falta autorización y regla N8 |
| Confirmar (`GATEWAY`) | Webhook en `app` (P3) | No | No | — | — | Fuera de corte |
| Disparo inmediato | `app` | No | No | — | — | Bloqueado (X-1) |
| Descubrimiento | `app` (scheduler) + `convocatoria` (consulta) | No | No | Lectura | Ciclo | Bloqueado (X-1); ADR |
| Barrera | `convocatoria` (registro de comandos procesados) | Sí, con reintento interno | Nuevo `CommandType` | Transacción 2 (orquestador) | Por diseño | Falta variante sin reintento (X-4) |
| Ledger | `convocatoria` | Sí, acoplado a la confirmación | Cambia la operación | Transacción 2 | — | Requiere cambio |
| `Fund` | `core` | Sí | Sí | Transacción 2 | — | T1 |
| Outbox | `core` | Sí, sin mensaje (X-2) | **G** | Transacción 2 | — | G |
| Rechazo terminal | `convocatoria` | No | Estado nuevo | Transacción 3 (condicionada) | Scheduler | Falta nombre y estado |
| Dinero rechazado | `convocatoria` (N4) | No | P1 | — | — | Fuera de corte (D4) |

### Matriz 3 — Adversarial

Ver §2.

### Matriz 4 — Documentación

| Documento | Qué dice | Qué cambiaría D1–D7 | Contradicción | Acción necesaria |
|---|---|---|---|---|
| ADR-037 §2.6 | 4 estados; webhook → génesis | Estado terminal nuevo; el paso 3 son dos actos | Sí | Enmienda 2 |
| Enmienda §5.3 | Barrera = `PENDING → CONFIRMED` en la misma transacción | Barrera = registro por intención en la transacción de aplicación | Sí (F-1) | Enmienda 2 |
| Enmienda §5.2 N9 | Confirmación manual = `CLEAR_FUNDS_AS_GENESIS` | Confirmación = `ADMINISTRATOR`, sin génesis; la pasarela, solo el proveedor | Sí (F-2) | Enmienda 2 |
| Enmienda §2.1 y §3.5 | Sin un mecanismo de idempotencia distinto | La barrera debe usar el mismo patrón | Riesgo (D1) | Explicitarlo en la Enmienda 2 |
| Enmienda §3.3 / P1 | Registro de dinero no aceptable | P1 fuera de corte; no se habilita dinero real | No, si se declara | Enmienda 2 (alcance) |
| ADR-041:45, :75 | Estados públicos; `FAILED` = fallo de pago | Estado nuevo | Sí | Enmienda de ADR-041 |
| `api-contract-matrix.md:104` | Estados `PENDING`/`CONFIRMED`/`FAILED`/`EXPIRED-UNKNOWN` | Estado nuevo | Sí | Actualizar |
| `implementation_plan.md` §4.4, §7.2, §8, §9.2, §11, §13.3, §14.1 | Operación combinada; orquestador fuera de corte | Separación; barrera; recuperación | Sí | Actualizar (cita §6.17 y §6.18) |
| `convocatoria-resumen.md` §6.19 | DH-3 da por hecho el acto combinado; "F-1/F-2" = contradicciones | Registrar F-1/F-2 y D1–D7 | Sí (y colisión de nombres) | Actualizar |
| `estado-fase6.md` §3bis | Operación combinada; cita §6.18 | Estado nuevo | Sí | Actualizar tras la ejecución |
| `golden-path.md:30-33` | Webhook → génesis + ledger en un paso | Dos actos | Sí | Corregir |
| ADR-032 | `CLEAR_FUNDS_AS_GENESIS` → `ADMINISTRATOR` | Ninguno | No | No tocar |
| ADR-042 | Reintentos de proyección | Posible extensión | No | Decisión humana (D2) |

### Matriz 5 — Tests

| Test | Qué demuestra | Qué NO demuestra | Estado |
|---|---|---|---|
| `IdempotentCommandExecutorIntegrationTest` (5) | Reclamo de clave única en la transacción; concurrencia real (efecto único, resultado original); rollback del reclamo ante fallo de dominio | Uso dentro de una transacción externa sin reintento | Relevante para D1 (B) |
| `CampaignFundingLedgerIntegrationTest` (22) | Aplicación desde `PENDING`: idempotencia, concurrencia, reinicio, rechazo con rollback, transacción externa (`externalCaller…`, **validado por mutación**) | Aplicación desde `CONFIRMED`; `Fund`; outbox; recuperación. Prueba el modelo que F-1 descarta | Habrá que reescribirlos |
| `concurrentStrictApplicationsThatTogetherExceedTargetApplyOnlyOne` | Dos intenciones compitiendo por el límite (caso J) | — | Válido tras la separación (B) |
| `ConfirmDonationIntentIntegrationTest` (8) | Transición única, también concurrente; N8; vencimiento; confirmación independiente sin ledger | Autorización; rechazo de pasarela (los tests confirman `GATEWAY` con `"provider"`) | Habrá que reescribirlos (D5) |
| `ConvocatoriaAuthorizationPolicyTest` (8) | `requireAdministratorOf` y `requireRecipient` | Su uso en la confirmación | Reutilizable (B) |
| `externalCallerCatchingAnExpiredIntentFailureCannotCommit` | Regresión de DH-4 | La corrección (pasa sin ella) | Protección contra regresiones |
| Recuperación, estado terminal, outbox de financiación | — | Todo | **No existen** |

---

## 4. Dictamen

### BLOCKED — ARCHITECTURE GAP

Las decisiones D1–D7 **sobreviven** al intento de refutación como diseño:
- La única contradicción posible (D1 frente a la Enmienda §2.1) se evita implementando la barrera con el registro de comandos procesados.
- El resto se resuelve con la Enmienda 2 y el ADR de recuperación previstos en D7.

Pero el repositorio **no tiene capacidades necesarias** para ejecutar el flujo completo:

| # | Capacidad que falta | Evidencia | Qué bloquea |
|---|---|---|---|
| GAP-1 | **Implementación de producción de `OrganizationVerificationPort`** (ADR-038, X1). Sin ella, `app` no puede depender de `convocatoria` sin romper el arranque de su contexto | A (solo un fake), D (plan §11) | D2 y D6: orquestador, disparo inmediato, scheduler. Todo lo que vive en `app` |
| GAP-2 | **Caminos sin reintento interno** para usarse dentro de la transacción del orquestador: `clearFundsGenesis` (T1, `core`) y el reclamo idempotente (`IdempotentCommandExecutor`, `convocatoria`) | A, D (Enmienda §6, plan §8) | D1 y D6 dentro de la transacción única |
| GAP-3 | **Mensaje de outbox de la génesis**: ADR-037 §2.3 lo exige, pero hoy la génesis no escribe ninguno y su contenido no está definido | A, G | D6 ("ledger + `Fund` + outbox") |
| GAP-4 | **Camino de confirmación de pasarela** (webhook, P3) y forma de representar al actor externo en `convocatoria` | A, D | Con D5, ninguna intención `GATEWAY` llega a `CONFIRMED` en este corte |

**Lo que se puede implementar sin esperar a los gaps**, una vez aprobada la Enmienda 2 (regla 3.5, `convocatoria-resumen.md:91`). Todo está en `convocatoria` y es comprobable con los tests del módulo:
1. Autorización de la confirmación (`requireAdministratorOf`) y rechazo de intenciones de pasarela (D5).
2. Operación de aplicación desde `CONFIRMED` con barrera en el registro de comandos procesados y variante sin reintento interno (D1, mitad de GAP-2).
3. Estado terminal de rechazo y su escritura condicionada (D3), una vez decidido el nombre.
4. Consulta de intenciones `CONFIRMED` sin aplicar (pieza de `convocatoria` de D2).

**Precisiones humanas que siguen abiertas** (no bloquean el diseño; sí la redacción de la Enmienda 2):
1. **D1:** ¿la barrera es el registro de comandos procesados existente, con un `CommandType` nuevo y clave derivada de la intención, o una colección nueva? En el segundo caso, ¿se enmienda la alternativa descartada de la Enmienda §2.1?
2. **D3:** nombre del estado terminal (ver las convenciones del §1) y clasificación de las excepciones permanentes (`CampaignFundingLimitExceededException`, `CloseOnTargetCloseNotSupportedException`; tratamiento de `CampaignNotFoundException` como anomalía).
3. **D2:** ¿ADR nuevo de recuperación o extensión de ADR-042? ¿Se acepta el coste creciente de la consulta de descubrimiento?
4. **D2/D5:** ¿el caso de uso de confirmación manual vive en `app`, para poder hacer el disparo inmediato (X-6)?
5. **Alcance:** ¿GAP-1 (ADR-038) y GAP-2 (T1 en `core`) entran en el cierre de Convocatoria o se aceptan como dependencias que dejan el flujo `app` sin habilitar?
