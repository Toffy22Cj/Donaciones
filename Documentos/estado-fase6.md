# Estado — Fase 6

**Última actualización:** a partir de la sesión de diseño y de la primera implementación real (Productor de MerkleBatch), ambas dentro de este mismo proyecto de planeación.
**Alcance de este documento:** refleja únicamente resultado de ejecución real verificado en esta sesión (output de Surefire, código inspeccionado). No infiere estado de fases anteriores por memoria — donde algo se toma de `estado-fase5.md`/`documento-maestro-proyecto.md`, se cita como tal, no se reconstruye.

---

## 1. Resumen de una línea

Las cinco capas de diseño conceptual de Fase 6 quedaron cerradas con review formal de 12 puntos, cada una con su ADR tentativo. De ellas, **solo Blockchain tiene implementación real iniciada y verificada** (Productor de `MerkleBatch`, Fase 1-3 del protocolo). Las demás siguen en estado de diseño aprobado, sin código propio de esta sesión.

## 2. Estado por capa de diseño

| Capa | ADR | Estado de diseño | Estado de implementación |
|---|---|---|---|
| Convocatoria + Ledger + Assignment + DonationIntent | ADR-037 (tentativo) | 12/12 cerrado, D1 (pago tardío) resuelto, D2 (autoasignación) abierto | Sin código de esta sesión |
| Identidad (HumanActor, Platform Admin, verificación Organization, JWT) | ADR-038 | Approved — diseño conceptual; §7 cerrado (2026-09-30); enmiendas de implementación en ADR-038 §9; enmienda ADR-026 aplicada | **Implementado y verificado** en `feat/identity-adr-038` (último commit `2b2a68a`): tareas 1–8, sin JWT ni endpoints HTTP. Pendiente de merge a `develop` |
| Blockchain (Productor MerkleBatch, IntegrityVerificationPort) | ADR-039 (tentativo) | 12/12 cerrado | **Productor: Fase 1-3 implementada y verificada. `IntegrityVerificationPort`: sin empezar** |
| IA (ConvocatoriaAuditFacts) | ADR-040 (tentativo) | Cerrado parcialmente — 3 decisiones estructurales (A/B/C) y una contradicción de nomenclatura de puerto (C1) siguen abiertas | Sin código de esta sesión |
| APIs + Frontend | ADR-041 (tentativo) | 12/12 cerrado — mapeo endpoint↔hueco de dominio consolidado | Sin código de esta sesión |

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
