# Estado — Fase 6

**Última actualización:** a partir de la sesión de diseño y de la primera implementación real (Productor de MerkleBatch), ambas dentro de este mismo proyecto de planeación.
**Alcance de este documento:** refleja únicamente resultado de ejecución real verificado en esta sesión (output de Surefire, código inspeccionado). No infiere estado de fases anteriores por memoria — donde algo se toma de `estado-fase5.md`/`documento-maestro-proyecto.md`, se cita como tal, no se reconstruye.

---

## 1. Resumen de una línea

Las cinco capas de diseño conceptual de Fase 6 quedaron cerradas con review formal de 12 puntos, cada una con su ADR tentativo. De ellas, **solo Blockchain tiene implementación real iniciada y verificada** (Productor de `MerkleBatch`, Fase 1-3 del protocolo). Las demás siguen en estado de diseño aprobado, sin código propio de esta sesión.

*Nota de la consolidación documental del 2026-10-03 (solo Convocatoria):* para Convocatoria esta línea está desactualizada. Su primer corte está implementado (§2, fila 1, y §3bis). No se reescribe la frase porque resume a las cinco capas.

## 2. Estado por capa de diseño

| Capa | ADR | Estado de diseño | Estado de implementación |
|---|---|---|---|
| Convocatoria + Ledger + Assignment + DonationIntent | ADR-037 + Enmienda 1 (aprobada) | Cerrado para el primer corte (`implementation_plan.md` rev. 2.2); P1–P7 y R4 abiertos | **Primer corte implementado y verificado (2026-10-01), sin commit — ver §3bis** |
| Identidad (HumanActor, Platform Admin, verificación Organization, JWT) | ADR-038 | Approved — diseño conceptual; §7 cerrado (2026-09-30); enmiendas de implementación en ADR-038 §9; enmienda ADR-026 aplicada | **Implementado y verificado** en `feat/identity-adr-038` (último commit `2b2a68a`): tareas 1–8, sin JWT ni endpoints HTTP. Pendiente de merge a `develop` |
| Blockchain (Productor MerkleBatch, IntegrityVerificationPort) | ADR-039 (tentativo) | 12/12 cerrado | **Productor: Fase 1-3 implementada y verificada. `IntegrityVerificationPort`: sin empezar** |
| IA (ConvocatoriaAuditFacts) | ADR-040 (tentativo) | Cerrado parcialmente — 3 decisiones estructurales (A/B/C) y una contradicción de nomenclatura de puerto (C1) siguen abiertas | Sin código de esta sesión |
| APIs + Frontend | ADR-041 (tentativo) | 12/12 cerrado — mapeo endpoint↔hueco de dominio consolidado | Sin código de esta sesión |

*Nota de la consolidación (2026-10-03), fila Convocatoria:*
- **Situación documental actual:**
  - ADR-037 consolidado con la Enmienda 1 (APROBADA);
  - Enmienda 2 en **BORRADOR**, no integrada como norma;
  - ADR-043 (**Propuesto**) es un documento de otro bloque, citado solo como dependencia externa (`convocatoria-resumen.md` §6.21);
  - `implementation_plan.md` en revisión 3 (aprobación de la rev. 3 pendiente, DH-C-5).
- La fila cita la "rev. 2.2" porque se redactó antes de la rev. 3.
- Índice del bloque: `convocatoria-resumen.md` §0.

## 3. Blockchain — único componente con evidencia de código real

### 3.1 Verificado antes de tocar código (Fase de auditoría)

- `MerkleTree.build(List<String>)`: sin acoplamiento a orden de inserción real — descartado el riesgo que ADR-039 marcaba como pendiente de verificar. No requirió modificación.
- `MerkleBatch`: record inmutable, 15 campos reales (no 14 como decía `blockchain-resumen.md` — discrepancia documental, no bloqueante).
- `EventCanonicalMapper.toCanonicalMap()`: firma explícita sin `actorRef` ni `merkleBatchId` — confirmado por código que agregar `merkleBatchId` a `TraceabilityEventDocument` no afecta `eventHash`, siempre que esa firma no se amplíe.
- `AnchorStatus`: no tenía `COLLECTING`. Auditados los tres consumidores (`BlockchainAnchorScheduler`, `AnchorConfirmationPoller`, `BlockchainAdminOperationsService`) — ninguno usa `switch` exhaustivo, todos filtran por `findByStatus` de un valor específico. Agregar `COLLECTING` es seguro.
- `EventStorePort` (`append`, `loadStream(streamId)`) confirmado insuficiente para las operaciones del productor — motivó el diseño de un puerto nuevo.

### 3.2 Decisiones de arquitectura tomadas durante la implementación (no en el ADR original)

- **`SequenceRange`** (record `fromSequence`/`toSequence`) vive en `contracts` — consumido por `core`, `app` y `crypto`, ninguno es dueño exclusivo.
- **`UnanchoredEventRepositoryPort`** vive en `core.application.port.out`, implementado por `MongoUnanchoredEventAdapter` en `core.infrastructure.persistence.mongo` — mismo patrón que `EventStorePort`/`MongoEventStoreAdapter` (el módulo dueño de la colección posee también el puerto y el adapter).
- **`BlockchainAnchorProducer` vive en `app`**, no en `crypto` — mismo precedente que el orquestador de `STRICT` en ADR-037: cuando una operación necesita atomicidad transaccional entre dos módulos hermanos, el orquestador vive en `app`, ninguno de los dos módulos de dominio importa al otro.
- **`MerkleBatchRepositoryPort.transitionCollectingToPending(batchId, merkleRoot): boolean`** — método nuevo, update condicional real (`WHERE batchId=X AND status=COLLECTING`), reemplaza el `save()` genérico original (que era lectura-luego-escritura, insuficiente para la Fase 3 del protocolo).
- **`coverage[]` persistido en `MerkleBatch`** — `sequenceRangeStart`/`sequenceRangeEnd` permanecen en `MerkleBatchDocument`, marcados `@Deprecated(forRemoval=false)`, sin eliminar (evita romper deserialización de batches históricos).
- **`LegacyBatchCoverageUnavailableException`** (excepción nombrada, `crypto.domain.exception`) — `toDomain()` falla explícitamente si `coverage` es nulo/vacío, en vez de sintetizar un `streamId` falso para batches legacy. Se descartó la síntesis silenciosa por riesgo real de falso `MISMATCH` en `IntegrityVerificationPort`.
  - **Alcance de esta excepción, explícito**: es una barrera total de lectura de batches legacy vía `toDomain()` — no exclusiva de la verificación de integridad. `findByBatchId()`, `findByStatus()`, y cualquier componente que dependa de esas lecturas (incluido el propio anchoring si encontrara un batch histórico en curso) queda afectado igual.
  - No urgente: confirmado que no existen batches históricos reales en el entorno de desarrollo actual.
  - La estrategia real de migración de legacy sigue sin diseñar — esto solo evita que, mientras no exista, el sistema mienta sobre integridad.

### 3.3 Evidencia de verificación (output real, no narrativa)

| Verificación | Resultado |
|---|---|
| Baseline inicial `mvn test -pl crypto` | 27 tests, 0 failures, 5 errors (todos por falta de Docker local — no regresión) |
| `COLLECTING` agregado al enum, re-test | 22 unitarios sin cambios, mismos 5 errores de entorno |
| `MultiCollectionTransactionIntegrationTest` (camino feliz + rollback, Testcontainers real) | `Tests run: 2, Failures: 0, Errors: 0` |
| `BlockchainAnchorProducerTest` (incluye aserción negativa: Fase 1 vacía → Fase 2/3 nunca se invocan) | Verificado con `verify(..., never())` sobre los tres puertos |
| `mvn test -pl crypto` tras refactor de `coverage[]` + `LegacyBatchCoverageUnavailableException` (con Docker, Testcontainers Mongo+Ganache reales) | `Tests run: 43, Failures: 0, Errors: 0` |

Nota de proceso: hubo un reporte intermedio de "`BUILD SUCCESS`" basado en `mvn clean install -DskipTests`, que fue señalado como violación de la regla de evidencia (compilar no es pasar tests) y corregido con la ejecución real antes de aceptar el cierre de esta pieza.

## 3bis. Convocatoria — primer corte (2026-10-01, rama `develop`, HEAD `673eda92`, sin commit)

Implementado según `implementation_plan.md` §15, Tareas 0–9. Módulo Maven `convocatoria` con `convocatoria → contracts` como única dependencia de proyecto (`mvn dependency:tree -pl convocatoria`); `app → convocatoria` **no** añadido (B-1); `app/**` sin cambios.

| Verificación | Resultado (output literal de Surefire) |
|---|---|
| Línea base antes de tocar código (`mvn test`, 7 módulos) | `BUILD SUCCESS`; core 229, crypto 50, ai 19, api 34, identity 71, app 23 — `Failures: 0, Errors: 0` |
| `mvn test -pl convocatoria` al cierre de la Tarea 9 | `Tests run: 168, Failures: 0, Errors: 0, Skipped: 0` |
| `mvn clean test` del reactor completo (8 módulos) | `Tests run: 229/50/19/34/71/168/23, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS` |

Concurrencia probada contra MongoDB real (Testcontainers, replica set) con `CyclicBarrier`: reintentos reales por `WriteConflict` (`TransientTransactionError`) observados en el log. Incidencia de entorno de test resuelta: recrear colecciones en cada test agotaba los descriptores de `mongod` (`Too many open files` → `WT_PANIC`, leído en el log del contenedor); ahora se crean una vez por JVM y se vacían entre tests.

Decisiones humanas tomadas durante la implementación (2026-10-01): G1 — quitar `MONETARY` en la edición directa borra el ledger en la misma transacción (N2); G2 — `RemoveResponsible` con reemplazo lleva `replacementActingRole`; G3 — una persona tiene como mucho una asignación activa por convocatoria.
*Nota de la consolidación (2026-10-03):* G1–G3 solo constan en este documento de estado. Su confirmación y su registro en `convocatoria-resumen.md` §6 son una decisión humana pendiente (`convocatoria-resumen.md` §0.4, DH-C-4). G3 no figura en ADR-037 §2.4 / Enmienda 1 §4.2.

**C-01 y cierre del flujo de fondos en `convocatoria` (2026-10-02, sin commit).** Especificación: ADR-037 Enmienda 2 (BORRADOR) y ADR-043 (Propuesto); decisiones en `convocatoria-resumen.md` §6.20; plan rev. 3.
- Confirmación (`confirmDonationIntent`): solo `ADMINISTRATOR` de la organización (`requireAdministratorOf`); rechaza las intenciones de pasarela; no toca ledger, registro de comandos ni `Fund`.
- Aplicación (`applyFundsForIntent(intentId)` → `ApplyFundsResult`): consume una intención `CONFIRMED`; barrera = reclamo del comando de sistema `APPLY_FUNDS` (clave conceptual `APPLY_FUNDS:{intentId}`, `_id = {commandType, commandId}`, espacio separado de los comandos de cliente; I1 intacto) antes del incremento del ledger; ante un duplicado devuelve el resultado original (`appliedNow = false`); se une a una transacción externa sin reintento interno y la marca para rollback.
- `FUNDING_REJECTED`: solo por `CampaignFundingLimitExceededException` verificado como permanente; `CLOSE_ON_TARGET + CLOSE` sigue `CONFIRMED` (R4); los fallos transitorios no lo producen.
- Consulta de intenciones `CONFIRMED` sin aplicar (pieza de `convocatoria` de ADR-043), que excluye las de convocatorias `CLOSE_ON_TARGET + CLOSE` mientras R4 no exista (P9, opción a).
- Pruebas de mutación de esta sesión: retirar la barrera, compartir el espacio de claves, marcar el rechazo sin verificar permanencia, quitar la autorización, quitar la exclusión P9, tratar `CLOSE` como permanente o devolver `appliedNow = true` en un duplicado hace fallar los tests correspondientes.

| Verificación (esta sesión) | Resultado (output literal de Surefire) |
|---|---|
| `mvn -o test -pl convocatoria` | `Tests run: 190, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS` |

*Nota de la consolidación (2026-10-03):*
- Auditorías posteriores (`auditoria-documental-convocatoria.md`, `auditoria-cierre-final-convocatoria.md`) informan 194 tests en verde.
- En la primera pasada de la consolidación esa cifra no se registró, porque no se habían ejecutado tests y la regla 2.4 exige una ejecución de la sesión que actualiza.
- Quedó confirmada por la ejecución real de la verificación siguiente.
- La especificación citada arriba (Enmienda 2 y ADR-043) sigue en BORRADOR / Propuesto.

**Verificación del 2026-10-03** (ejecución real en la sesión de consolidación documental; sin cambios de código ni de tests: sumas de comprobación de `convocatoria/src` idénticas antes y después):

| Verificación | Resultado (output literal de Surefire) |
|---|---|
| `mvn -o test -pl convocatoria` | `Tests run: 194, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS` |

Esta es la cifra vigente del módulo. Las de 168 y 190 se conservan arriba como registro de ejecuciones anteriores. No se ejecutó el reactor completo.

**Bloqueado fuera de `convocatoria`:** orquestador de la aplicación (ledger + génesis + outbox en una transacción), disparo inmediato y scheduler de ADR-043 (`app`): `app` no puede componer `convocatoria` sin una implementación de producción de `OrganizationVerificationPort` (ADR-038), porque `ConvocatoriaLifecycleService` y `DonationIntentService` la requieren y `app` escanea `com.traceability`. También T1 y P8 en `core`.

**No cubierto por este corte (sigue abierto):** orquestador de la aplicación de fondos y su test de reintento de transacción completa (Enmienda 2 §3.2, ADR-043, §13.3 del plan); T1 en `core`; adaptador real de `OrganizationVerificationPort` (ADR-038); webhook (P3); efectivo (P5); P1, P2, P4, P6, P7; R3 (solicitud de cambio); R4 (`CLOSE_ON_TARGET` + `CLOSE`); índice de referencia de pago, sin el cual la confirmación manual de `BANK_TRANSFER` no es desplegable.

## 4. Pendiente — Blockchain

- `IntegrityVerificationPort` (`verifyBatch`, `verifyAllAnchored`) — diseño cerrado en ADR-039, sin código todavía.
- Partición cuando un stream supera `maxEventsPerBatch` sin romper contigüidad.
- Semántica de `matchedCount==0` en Fase 3 cuando dos workers compiten por el mismo `COLLECTING`.
- Mecanismo operativo que descubre y reintenta batches `COLLECTING` abandonados.
- Paginación/límite de `verifyAllAnchored()`.
- Estrategia real de migración de `MerkleBatch` históricos (la excepción de §3.2 es una barrera de seguridad, no una migración).
- Origen del `correlationId` para ejecuciones de scheduler/background.
- Deuda técnica registrada aparte (no bloqueante): el test de integración multi-colección levanta todo el contexto de `app`, incluida configuración de IA (`spring.ai.openai.api-key` simulada) — candidato a acotar con un slice de test más estrecho (`@DataMongoTest` o equivalente).

## 5. Pendiente — resto de Fase 6

Ver §7 de cada ADR para el detalle completo. Resumen de las piezas de mayor severidad, sin repetir lo ya extenso en cada documento:

- **Convocatoria**: idempotencia real de `clearFundsGenesis` ante retry no verificada (bloquea el camino completo del webhook de pago) — máxima severidad de todo Fase 6.
  *Nota de la consolidación (2026-10-03):* la aplicación de fondos ya está protegida dentro de `convocatoria` por la barrera `APPLY_FUNDS` (§3bis; Enmienda 2 §3.3, BORRADOR). Del lado de `core` siguen pendientes:
  - T1 (camino de `clearFundsGenesis` sin reintento interno);
  - P8 (mensaje de outbox de la génesis);
  - la idempotencia de `clearFundsGenesis` en ejecución.

  Ver ADR-037 §7.1 y la Enmienda 2 §6.
- **Identidad**: ADR-038 implementado en `feat/identity-adr-038` (detalle en §7 de este documento y en ADR-038 §9). Pendiente: merge a `develop`, emisión de JWT/autenticación HTTP (§2.7), endpoints de plataforma y la deuda técnica de ADR-038 §9.4.
- **IA**: contradicción sin resolver entre `AuditFactsPort` (`ia-resumen.md`) y `CampaignAuditFactsPort` (`api-contract-matrix.md`) — requiere verificación de código antes de considerar el contrato cerrado.
- **APIs/Frontend**: ningún hueco propio de severidad alta — hereda los de arriba.
- **Dataset + narrativa de demo**: sin empezar, deliberadamente al final — depende de que el Golden Path funcione de extremo a extremo, lo cual hoy no ocurre (bloqueado por `HumanActor`+P7, entre otros; el modelo de identidad de ADR-038 ya está implementado, falta su exposición HTTP).

## 6. Próximo paso sugerido

De los pendientes de mayor severidad, ninguno depende de otro para empezar. Orden por impacto en el Golden Path: (1) verificar idempotencia de `clearFundsGenesis`, (2) resolver la contradicción de puerto en IA, (3) continuar con `IntegrityVerificationPort` en Blockchain, (4) decisiones funcionales de Identidad.

## 7. Identidad — implementación de ADR-038 (2026-10-01 → 2026-10-04)

Commits, enmiendas, evidencia y deuda técnica: **ADR-038 §9**. Aquí solo el estado y los incidentes de proceso.

### 7.1 Evidencia (output real, ejecutado por el humano)

| Verificación | Resultado |
|---|---|
| `mvn clean test` (reactor completo) sobre `2b2a68a` | `BUILD SUCCESS` — core 229, crypto 50, ai 19, api 34, identity 261, app 27 |
| `BootstrapPlatformAuthorityConcurrencyIntegrationTest`, 10 ejecuciones | 10/10 `Tests run: 2, Failures: 0, Errors: 0` |
| `OrganizationVerificationConcurrencyIntegrationTest`, 5 ejecuciones | 5/5 `Tests run: 7, Failures: 0, Errors: 0` |
| Pruebas de mutación (6B, 7, 8A, 8B) | Cada mutación puso en rojo exactamente los tests previstos (ADR-038 §9.3) |

### 7.2 Incidentes de proceso

- **Tarea 4 (`558a915`): salidas fabricadas.** El reporte del agente presentó como literales salidas de Maven y de git que no lo eran (un ULID con fecha de 2025, líneas de ArchUnit inexistentes, conteos que no sumaban el total, un autor falso). Su apartado de desviaciones decía "ninguna" mientras el diff borraba un comentario y cambiaba sangrías; se corrigió en `6b8a463`. **Regla derivada:** la evidencia de tests la ejecuta y la pega el humano, nunca el agente.
- **Tareas 6A y 8A: tests que no compilaban.** En dos ocasiones, tests rojos del agente usaron una variable local `org`, que tapa los paquetes `org.springframework` y `org.bson`. El compilador del editor dejó en `target/` clases que fallaban al ejecutarse. **Regla derivada:** no usar `org` como nombre de variable y verificar siempre con `mvn clean`.
- **Tarea 7 (`9a68e17`): regresión introducida por el revisor.** El revisor declaró "código muerto" una espera del test de concurrencia del bootstrap a partir del delta de reintentos, sin comprobar qué transacción reintentaba. Sin esa espera, el test falló 7 de 10 veces. La instrumentación mostró que en MongoDB 6.0 el conflicto aparece al confirmar (ADR-038 §9.2, E3), y se corrigió en `0e2440a`. **Regla derivada:** en tests de concurrencia, se instrumenta antes de declarar código muerto, y las aserciones identifican qué hilo falló.
- **Tarea 6B: contexto de Spring con contenedor detenido.** Dos clases con la misma configuración compartían el contexto cacheado de Spring, que apuntaba a un contenedor ya detenido ("Conexión rehusada"). Se corrigió con `@DirtiesContext`.
