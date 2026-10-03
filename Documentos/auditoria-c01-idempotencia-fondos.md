# Auditoría forense C-01 — Doble aplicación de fondos sobre una misma `DonationIntent`

**Fecha:** 2026-10-02. **Rama:** `develop`, HEAD `673eda9`. Módulo `convocatoria` sin commit (working tree; `git log --all -- convocatoria` vacío).
**Naturaleza:** informe temporal de auditoría. No es documentación canónica.

---

## 1. Veredicto

**A — BUG CONFIRMADO**, con el alcance exacto de §2: confirmado en la operación pública de aplicación de fondos del módulo; hoy no existe ningún camino de producción que la invoque.

## 2. Resumen

1. La única operación que aplica fondos es `CampaignFundingLedgerService.applyFunds(campaignRef, amount)`. No recibe `intentId`, `fundId`, `commandId` ni `paymentSessionId`.
2. Ningún mecanismo (registro de comandos, índice único, estado terminal, marca de "procesado") liga una aplicación de fondos a una intención. La operación no es idempotente.
3. Reproducido contra MongoDB real: misma intención confirmada (250) aplicada dos veces → ledger 0 → 250 → 500, en FLEXIBLE y en STRICT.
4. También en concurrencia (2 aplicadas, 500) y tras un reinicio de contexto Spring (250 → 500): no hay protección ni en memoria ni en MongoDB.
5. La barrera que el diseño exige (`PENDING → CONFIRMED` antes del efecto financiero, misma transacción) existe como primitiva (`confirmIfPending`), pero `applyFunds` no la consulta.
6. Intento de refutación: simulando fuera del repositorio la composición documentada (confirmar y, solo si transiciona, aplicar, en una transacción), la doble aplicación no ocurre, ni en secuencia ni en concurrencia.
7. Esa composición (orquestador en `app`) está declarada fuera del primer corte y no existe. La garantía documentada no está implementada en ningún lugar del repositorio.
8. No es contradicción documental (C): el plan declara explícitamente que el acoplamiento pertenece al orquestador futuro.
9. Riesgo real hoy: nulo en ejecución (sin llamadores). Riesgo de diseño: cualquier llamador futuro que no reproduzca la composición documentada duplicará fondos sin ninguna defensa del módulo.

## 3. Flujo real

| Paso | Archivo | Clase/método | Acción | Persistencia |
|---|---|---|---|---|
| 1. Ledger | `application/service/ConvocatoriaLifecycleService.java:91` (crear) y `:128` (edición que añade MONETARY) | `CampaignFundingLedger.open` (`domain/model/CampaignFundingLedger.java:28`) | Crea ledger con `clearedAmount = 0` | `campaign_funding_ledgers`, `_id = campaignRef` |
| 2. Intención | `application/service/DonationIntentService.java:80` | `createDonationIntent` | Lee la convocatoria por `publicCode`; genera `intentId` y `fundId` (UUID); idempotente por `commandId` | `donation_intents` (`uq_fund_id`), `convocatoria_processed_commands`, audit log, en una transacción |
| 3. Confirmación | `DonationIntentService.java:112` → `MongoDonationIntentRepositoryAdapter.java:46` | `confirmDonationIntent` → `confirmIfPending` | `updateFirst({_id, status: PENDING, expiresAt > now}, {status: CONFIRMED, …})`; devuelve si se aplicó | `donation_intents`; `@Transactional(SUPPORTS)`; sin `commandId`, sin autorización |
| 4. Aplicación de fondos | `application/service/CampaignFundingLedgerService.java:36` | `applyFunds(campaignRef, amount)` | Lee el ledger; según `isCapacityLimited()` (`CampaignFundingLedger.java:41`) elige el incremento. **No lee la intención ni su estado.** | `@Transactional(SUPPORTS)` |
| 5. Persistencia | `MongoCampaignFundingLedgerRepositoryAdapter.java:45` / `:52` | `incrementUnconditionally` / `incrementWithinTarget` | `updateFirst({_id}, {$inc: clearedAmount})` o `updateFirst({_id, clearedAmount ≤ target − amount}, {$inc})` | `campaign_funding_ledgers` |
| — Enlace 3 → 4 | — | **No existe** | Ningún código de `main` llama a `applyFunds`; ningún otro módulo referencia `convocatoria` (grep global) | — |

## 4. Evidencia de idempotencia

| Mecanismo | Ubicación | Qué protege | Atómico | Evidencia |
|---|---|---|---|---|
| `commandId` + `convocatoria_processed_commands` | `IdempotentCommandExecutor`, `MongoProcessedCommandAdapter` | Los 7 comandos de `CommandType` (creación de intención incluida). **No `applyFunds` ni `confirmDonationIntent`.** | Sí (misma transacción) | A: `CommandType` no tiene tipo para aplicar fondos |
| `confirmIfPending` (estado terminal `CONFIRMED`) | `MongoDonationIntentRepositoryAdapter.java:46` | Que la transición ocurra una vez | Sí (escritura condicional de un documento) | B: segunda confirmación → `false` (§6) |
| Índice `uq_fund_id` | `DonationIntentDocument.java:20` | Que dos intenciones no compartan `fundId` | Sí (motor) | A. No interviene en el ledger |
| Índice `uq_payment_session_id` (parcial) | `DonationIntentDocument.java:30` | Unicidad de sesión de pasarela | Sí | A. `paymentSessionId` siempre null en este corte |
| Filtro `clearedAmount ≤ target − amount` | `MongoCampaignFundingLedgerRepositoryAdapter.java:52` | Capacidad (STRICT / REJECT_EXCESS), no unicidad | Sí | B: no impide la doble aplicación mientras haya capacidad (§6) |
| Marca "fondos aplicados" por intención | — | — | — | **No existe** (A: grep de `applyFunds`, `processed`, `idempot` en `main`) |
| `clearFundsGenesis` / `processed_commands` de `core` | `core/.../FundCommandService.java:83` | Idempotencia del `Fund`, no del ledger | — | A: `core` no referencia `convocatoria` |

Respuestas a la Fase 2, para `applyFunds`:

1. Dónde se comprueba "ya procesado": en ningún sitio.
2. Cuándo se registra: nunca.
3. Atomicidad comprobación + registro: no aplica.
4. Dos llamadas concurrentes: ambas aplican (§6).
5. Primera llamada parcial: es un único `updateFirst`; no hay estado parcial, pero tampoco registro.
6. Mismo comando repetido: la operación no tiene `commandId`; cada llamada aplica.
7. `commandId` distinto, misma intención: idéntico al punto 6.

## 5. Escenarios

| Caso | Resultado observado | Evidencia |
|---|---|---|
| Mismo intent + mismo `commandId` | `applyFunds` no acepta `commandId`; la llamada idéntica repetida suma dos veces (0 → 250 → 500) | A (firma `applyFunds(String, long)`) + B (§6, secuencial) |
| Mismo intent + `commandId` diferente | Igual: suma dos veces. Según el diseño (Enmienda §5.3), el `commandId` del efecto financiero lo deriva el orquestador de la intención y la barrera es `PENDING → CONFIRMED`; nada de eso existe en el camino de `applyFunds` | A + D |
| Concurrencia | Dos `applyFunds` simultáneos (`CyclicBarrier`): ambos aplican, `cleared = 500` | B |
| Reinicio | Segundo contexto Spring independiente contra la misma MongoDB: `250 → 500`. No existe protección persistida ni en memoria | B |
| Caminos duplicados | Hay una sola entrada al ledger (`applyFunds`) y cero llamadores de producción. Es un bean público, invocable desde cualquier componente del contexto | A (grep) |
| Refutación: composición documentada simulada | Secuencial: 1.ª `true` (250), 2.ª `false` (250). Concurrente: una `true`, la otra `DataIntegrityViolationException` (conflicto de escritura sin reintento en la simulación); `cleared = 250` | B (simulación temporal, no código del repositorio) |

## 6. Reproducción

- **Comando:** `java -Ddocker.api.version=1.41 -cp <scratchpad/probe/out>:<test-classes>:<classes>:<contracts/target/classes>:<classpath test de Maven> ProbeRunner com.traceability.convocatoria.probe.C01FundsIdempotencyProbeTest`. Compilado con `javac` contra las clases ya compiladas del módulo.
- **Infraestructura:** MongoDB 6.0 en replica set vía Testcontainers. Contexto `ConvocatoriaTestApplication` con los fakes de test del módulo (`FakeIdentityPrincipalPort`, `FakeOrganizationVerificationPort`). Servicios, adaptadores e índices son los de producción del módulo.
- **Estado inicial:** convocatoria MONETARY (meta 1.000, `BANK_TRANSFER`), ledger `clearedAmount = 0`; intención de 250 creada y confirmada (`status = CONFIRMED`).

| Política | Antes | Tras 1.ª aplicación | Tras 2.ª aplicación | 2.ª confirmación |
|---|---|---|---|---|
| FLEXIBLE | 0 | 250 | **500** | `false` |
| STRICT | 0 | 250 | **500** | `false` |

- **Concurrente:** `applied=2 cleared=500`.
- **Reinicio:** `beforeRestart=250`, `afterApplyInNewContext=500`.
- **Resultado final:** el ledger crece en cada aplicación de la misma intención. La barrera de confirmación funciona (segunda confirmación `false`), pero `applyFunds` no depende de ella.

## 7. Contraste documental

| Documento | Regla declarada | Código actual | Resultado |
|---|---|---|---|
| ADR-037 Enmienda 1 §5.3 [DECISIÓN] | `PENDING → CONFIRMED` es la barrera antes de cualquier efecto financiero sobre el ledger, en la misma transacción | La barrera existe (`confirmIfPending`); el acoplamiento barrera → ledger no existe | Garantía no implementada (E). No es F: §5.3 no asigna el acoplamiento al módulo |
| `implementation_plan.md` §7.2 | "El orquestador (fuera de corte) solo toca el ledger si la transición se aplicó en esa misma transacción" | Sin orquestador; `applyFunds` sin llamadores | Consistente con el alcance declarado (D) |
| `implementation_plan.md` §8 | Aplicación de fondos "invocada en el futuro solo por el orquestador de `app`" | Bean público, sin restricción de llamador | Restricción solo documental (D/E) |
| `implementation_plan.md` §1.3 | Orquestador STRICT y efecto sobre `Fund` fuera de corte | Ausentes | Consistente (A) |
| ADR-037 §2.6 paso 3 / §2.6bis | El webhook invoca `clearFundsGenesis` con el `fundId` de la intención; ledger sujeto solo a capacidad | Sin webhook (P3) | No implementado, declarado |
| `golden-path.md:33`, `api-contract-matrix.md:41` | `CampaignFundingLedger` actualizado en la misma transacción que `clearFundsGenesis` | No existe esa transacción | No implementado; api-contract-matrix lo presenta como contrato sin advertirlo (D) |
| `estado-fase6.md` §3bis | Orquestador de STRICT y su test, "no cubierto por este corte" | Ausente | Consistente |

## 8. Evidencia

| Conclusión | Clase |
|---|---|
| `applyFunds` no recibe ninguna identidad de intención ni de comando | A |
| No existe registro de procesamiento para la aplicación de fondos | A |
| Doble aplicación secuencial suma dos veces (FLEXIBLE y STRICT) | B |
| Doble aplicación concurrente suma dos veces | B |
| La falta de protección persiste tras reiniciar el contexto | B |
| La segunda confirmación devuelve `false` (barrera funcional) | B |
| La composición documentada, simulada, impide la doble aplicación | B (simulación temporal) |
| El fallo de la variante concurrente simulada es un conflicto de escritura de Mongo traducido por Spring | E (se observó `DataIntegrityViolationException`; la causa concreta no se registró en esta ejecución) |
| Ningún camino de producción invoca `applyFunds` | A |
| El código de `convocatoria` no está en Git | C |
| El diseño asigna la garantía a un orquestador inexistente | D |
| La doble aplicación no contradice una decisión cerrada que el módulo deba cumplir por sí mismo | E |
| Comportamiento de un futuro orquestador | G |

## 9. ¿Qué tendría que cambiar?

No se implementa nada. Solo se describe la garantía ausente.

- **Garantía que falta:** que una misma `DonationIntent` produzca como máximo un efecto sobre `CampaignFundingLedger`.
- **Capa:** la unidad transaccional que ejecuta el efecto financiero, es decir, el orquestador de `app` que el diseño ya prevé. Alternativamente, el propio módulo, si se decide que el ledger no debe confiar en sus llamadores; esa elección es humana.
- **Condición a proteger:** el incremento del ledger ocurre solo si, en la misma transacción, la intención pasa de `PENDING` a `CONFIRMED` (o equivalente persistido y único por intención). Una repetición, concurrente o tras reinicio, no produce un segundo incremento.
- **Pruebas que deberían existir:**
  - misma intención aplicada dos veces en secuencia → un incremento;
  - dos aplicaciones concurrentes reales (`CyclicBarrier`, Testcontainers replica set) → un incremento, sin errores de usuario;
  - repetición tras reinicio de contexto → un incremento;
  - fallo forzado tras la transición → rollback conjunto de transición y ledger;
  - reintento por `TransientTransactionError` envolviendo la transacción completa (Enmienda §6, plan §13.3).

## 10. Archivos modificados

- **Código de producción:** 0
- **Documentación canónica:** 0
- **ADRs:** 0
- **Informe forense:** 1 — `Documentos/auditoria-c01-idempotencia-fondos.md` (este archivo)
- **Artefactos temporales (fuera del repositorio, en el scratchpad de la sesión):**
  - `scratchpad/probe/src/com/traceability/convocatoria/probe/C01FundsIdempotencyProbeTest.java`
  - `scratchpad/probe/out/com/traceability/convocatoria/probe/C01FundsIdempotencyProbeTest.class`
  - `scratchpad/c01.log` (salida de la ejecución)
  - Reutilizados de la auditoría anterior: `scratchpad/probe/src/ProbeRunner.java` (y su `.class`), `scratchpad/cp.txt`, `scratchpad/fullcp.txt`
- **Efectos colaterales:** contenedor MongoDB de Testcontainers efímero durante la ejecución. `target/` no se modificó en esta verificación (no se ejecutó Maven).
