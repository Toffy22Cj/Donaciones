# Auditoría final de cierre — bloque Convocatoria (Fase 6)

**Fecha:** 2026-10-02. **Rama:** `develop`, HEAD `673eda9`; `convocatoria/` sin versionar.
**Naturaleza:** informe temporal de auditoría, solo lectura. No modifica código, tests ni documentación canónica.
**Decisiones de referencia (cerradas):** F-1, F-2, `FUNDING_REJECTED`, sin `APPLIED`, D1–D7, P9 (opción a), `APPLY_FUNDS`, retry autónomo con helper y sin retry interno dentro de una transacción externa.

Taxonomía: **A** código · **B** test ejecutado en esta auditoría · **C** git · **D** documental · **E** inferencia.

---

## 1. Tests ejecutados en esta auditoría

| Ejecución | Resultado |
|---|---|
| `mvn -o test -pl convocatoria` (suite completa) | `Tests run: 194, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS` |
| `IdempotentCommandExecutorIntegrationTest#concurrentSameCommandIdHasSingleEffectAndBothGetOriginalResult`, 10 ejecuciones (5 bajo carga de CPU en los 8 núcleos, 5 sin carga), 5 rondas cada una = 50 rondas | 10/10 en verde. Avisos de reintento: 50 en el intento 1, 7 en el intento 2, **0 en los intentos 3–5**: ningún llamador pasó del tercer intento de 6; no hubo agotamiento |
| `CampaignFundingLedgerIntegrationTest` — `insideAnExternalTransaction…`, `sameIntentAppliedConcurrently…`, `concurrentDuplicates…`, `concurrentStrict…`, `applicationAndRejectionRacing…`, `twoRecoveryWorkers…`, 3 ejecuciones | `Tests run: 6, Failures: 0, Errors: 0` × 3 |

Evidencia histórica de esta sesión, no repetida aquí: 40/40 ejecuciones del test de concurrencia tras el cambio de política (20 bajo carga); sonda de 4 llamadores × 300 rondas bajo carga sin fallos; pruebas de mutación de la barrera, el espacio de claves, la permanencia del rechazo, la autorización, P9, `CLOSE`, el resultado ante duplicados y la ruta sin retry interno, todas detectadas por sus tests.

Nota de método: un primer lote combinó métodos de dos clases con `+` y Surefire solo ejecutó el de `IdempotentCommandExecutorIntegrationTest`; los tests del ledger se repitieron aparte con el selector correcto.

## 2. Perímetro Convocatoria — invariantes y su protección

| Invariante | Código (A) | Test que lo protege (B) |
|---|---|---|
| Estados: `PENDING → CONFIRMED`, `CONFIRMED → FUNDING_REJECTED` (terminal); sin `APPLIED`; `FAILED` no se usa | `DonationIntentStatus`; `markFundingRejectedIfConfirmed` | `strictLimitRejectionEndsInFundingRejectedAndCannotBeAppliedAgain` |
| Confirmación solo registra la confirmación: sin ledger, sin registro de comandos, sin `Fund` (F-2) | `DonationIntentService.confirmDonationIntent` | `confirmationNeitherRequiresNorAppliesFunds` |
| Autorización: solo `ADMINISTRATOR` de la organización; `confirmedBy` = actor autorizado (D5) | `requireAdministratorOf` | `administratorConfirmationRecordsWhoWhenMethodAndReference`, `employeeAndRepresentativeCannotConfirm`, `administratorOfAnotherOrganizationCannotConfirm` |
| Intención de pasarela no confirmable a mano (D5) | `GatewayIntentManualConfirmationNotAllowedException` | `gatewayIntentCannotBeConfirmedManuallyEvenByAnAdministrator` |
| Aplicación solo desde `CONFIRMED` (F-1) | `DonationIntentNotConfirmedException` | `pendingIntentCannotBeApplied` |
| Barrera `APPLY_FUNDS` reclamada antes del ledger, misma transacción (D1) | `applyFundsForIntent` | `sameIntentAppliedTwiceSequentially…`, `…AfterARestartIsANoOp` |
| Idempotencia con resultado original ante duplicado | `ApplyFundsResult` | `duplicateApplicationReturnsTheOriginalResult`, `concurrentDuplicatesBothReceiveTheOriginalResult` |
| Concurrencia: un solo efecto | Reclamo atómico `findAndModify` + `upsert` | `sameIntentAppliedConcurrentlyIncrementsOnce`, `concurrentStrictApplicationsThatTogetherExceedTargetApplyOnlyOne`, `applicationAndRejectionRacingNeverLeaveAnAppliedRejectedIntent`, `twoRecoveryWorkersProcessingTheSameBatchApplyEachIntentOnce` |
| Aislamiento de claves cliente/sistema (`_id = {commandType, commandId}`); I1 entre comandos de cliente | `MongoProcessedCommandAdapter`; guardas en el ejecutor | `clientCommandIdEqualToTheIntentIdDoesNotCollideWithTheApplication`, `systemCommandTypeCannotEnterThroughTheClientPath`, `commandIdReusedConcurrentlyByTwoCommandTypesKeepsOnlyTheFirst` |
| Rollback: un fallo deja `CONFIRMED` sin barrera ni incremento; un llamador externo que captura no puede confirmar | `@Transactional(SUPPORTS)` | `joinsAnExternalTransactionAndRollsBackWithIt`, `ledgerRejectionInsideAnExternalTransaction…`, `externalCallerCatchingTheLedgerRejectionCannotCommit`, `applicationJoinsAnExternalTransactionAndRollsBackWithIt` |
| `CampaignFundingLimitExceededException` → `FUNDING_REJECTED`, verificado como permanente (D3) | `rejectFundingIfPermanentlyUnfundable` | `strictLimitRejectionEnds…`, `rejectionIsNotMarkedWhenTheIntentStillFitsOrIsAlreadyApplied` |
| `CloseOnTargetCloseNotSupportedException` no terminal y fuera de la cola (D3, P9) | `isPermanentlyUnfundable`; segundo `$lookup` | `closeOnTargetCloseDoesNotEndInFundingRejectedWhileR4IsPending`, `closeOnTargetCloseIntentsStayOutOfTheRecoveryQueueWithoutBeingRejected` |
| Fallo transitorio nunca termina en `FUNDING_REJECTED` | Verificación propia de la permanencia | `transientFailureNeverEndsInFundingRejected` |
| Anomalías de invariante se propagan, sin estado terminal | — | `missingLedgerIsAnAnomalyThatLeavesNothing`, `persistedIntentWithNonPositiveAmountIsAnAnomaly` |
| Consulta de recuperables (D2, parte de Convocatoria) | `findConfirmedPendingApplication` | `findsOnlyConfirmedIntentsWhoseApplicationIsPending`, `confirmedIntentLeftUnappliedIsRecoveredAfterARestart` |
| Retry autónomo con helper (6 intentos, espera exponencial con tope) | `ConvocatoriaTransactionRetryHelper`; clasificación de errores sin cambios | `transientFailuresAreRetriedUntilSuccessWithinTheLimit…`, `transientFailureBeyondTheLimitIsPropagatedAfterExactlyMaxAttempts`, `backoffGrowsExponentiallyWithCap…`, `domainExceptionIsNotRetried`, `realWriteConflictIsRetriedAndEffectAppliedOnce` |
| Sin retry interno dentro de una transacción externa | `inTransaction` | `insideAnExternalTransactionATransientFailureIsPropagatedWithoutInternalRetry` (validado por mutación) |
| Frontera de módulos | — | `ConvocatoriaArchitectureTest` |

**Resultado del perímetro técnico:** todos los invariantes decididos están implementados y protegidos por tests en verde. No se encontró ningún comportamiento del código que contradiga F-1, F-2, D1–D7, P9 ni las decisiones de nombre.

## 3. Frontera con otros módulos (no son fallos de Convocatoria)

| Dependencia | Módulo | Qué bloquea | Evidencia |
|---|---|---|---|
| Modelo de verificación de `Organization` y adaptador de producción de `OrganizationVerificationPort` (ADR-038) | `identity`/`app` | `app → convocatoria`; por tanto, orquestador, disparo inmediato y scheduler | A: solo existe el fake de test; `app` escanea `com.traceability` y dos servicios de Convocatoria requieren el puerto |
| Orquestador transaccional (barrera + ledger + génesis + outbox) | `app` | Aplicación efectiva con `Fund` y su rollback conjunto | A: no existe; D: Enmienda 2 §3.2 |
| Disparo inmediato y scheduler (ADR-043) | `app` | Recuperación automática real | A: no existe |
| T1: `clearFundsGenesis` sin reintento interno | `core` | Génesis dentro de la transacción del orquestador | A: `CommandRetryTemplate`; D: plan §8 |
| Espacio de claves de la génesis | `core` | Atomicidad "ledger ⇔ `Fund`" ante una ocupación previa (`tryClaim` sin tipo, retorno silencioso) | A |
| P8: mensaje de outbox de la génesis | `core` | ADR-037 §2.3 | A: `List.of()` |
| Contrato HTTP de la confirmación manual; webhook (P3) | `api`/`app` | Exposición de la confirmación; confirmación de pasarela | A: 0 endpoints de escritura |
| P1: registro de dinero no aceptable | producto / `convocatoria` (N4) | Habilitar dinero real con rechazos | D: Enmienda 2 §4 (fuera de corte) |
| R4 | `convocatoria` (futuro) | Intenciones `CLOSE_ON_TARGET + CLOSE` | D |

## 4. Documentación frente al código

| Discrepancia | Documento | Clasificación |
|---|---|---|
| **La Enmienda 2 está en BORRADOR**: su §9 exige aprobación humana explícita del texto. Por `convocatoria-resumen.md:91`, las decisiones humanas no son normativas hasta que la enmienda se aprueba; el código implementa una semántica cuya base normativa sigue siendo un borrador | `ADR-037-enmienda-2-convocatoria.md` | **Bloqueante para el cierre de Convocatoria** (decisión humana pendiente; no requiere implementación) |
| **ADR-043 está Propuesto**: la regla 3.5 exige un ADR aprobado antes de fusionar código de un mecanismo de recuperación nuevo; la consulta de recuperables de Convocatoria forma parte de ese mecanismo | `ADR-043-…` | **Bloqueante para el cierre de Convocatoria** en lo que afecta a la consulta (decisión humana pendiente) |
| `estado-fase6.md` registra `Tests run: 190` y no recoge el cambio de la política de reintento ni el test de la ruta externa (hoy 194) | `estado-fase6.md:79` | Pendiente documental no bloqueante |
| ADR-041 no menciona `FUNDING_REJECTED` ni que `CONFIRMED` no implica fondos (`api-contract-matrix.md:104` sí). ADR-041 no enumera los estados, así que no se contradice | `ADR-041-…` | Pendiente documental no bloqueante (capa `api`) |
| La política de reintento de Convocatoria (6 intentos, espera exponencial) diverge de `identity` y `core` (3 intentos, 10–50 ms). El plan §4.4 pide reutilizar el *patrón* (programático, acotado, sin reintentar dominio), que se cumple; la divergencia numérica no está documentada | `implementation_plan.md` §4.4, §16; comentario del helper | Pendiente documental no bloqueante |
| Javadocs que remiten a "reportado en `implementation_plan.md` §16", sección que no contiene ese resumen (hallazgo previo I-6) | código (comentarios) | Pendiente documental no bloqueante |
| P10: trazabilidad de `FUNDING_REJECTED` (fecha, motivo, auditoría), declarada en la Enmienda 2 §7 como "enmienda posterior" | Enmienda 2 §7 | Pendiente explícitamente diferido; no bloqueante mientras se mantenga diferido |
| `golden-path.md:91` usa `REJECTED`, pero para la verificación de `Organization` (ADR-038), no para `DonationIntent` | `golden-path.md` | Sin discrepancia |
| Nombres obsoletos (`APPLY_DONATION_INTENT`, `REJECTED` para la intención, `APPLIED`), P9 pendiente o `CLOSE` terminal | Todos los canónicos | Sin coincidencias |

No se encontró ninguna **contradicción real** entre el código y la documentación vigente.

## 5. Criterio de cierre

| Condición | Estado | Evidencia | Pertenece a |
|---|---|---|---|
| Estados y transiciones (`FUNDING_REJECTED`, sin `APPLIED`) | ✅ | A, B | Convocatoria |
| Confirmación sin efectos financieros (F-2) | ✅ | A, B | Convocatoria |
| Autorización `ADMINISTRATOR` y rechazo de pasarela (D5) | ✅ | A, B | Convocatoria |
| Aplicación solo desde `CONFIRMED` (F-1) | ✅ | A, B | Convocatoria |
| Barrera `APPLY_FUNDS` antes del ledger, misma transacción (D1) | ✅ | A, B, mutación | Convocatoria |
| Idempotencia y resultado original ante duplicados | ✅ | B, mutación | Convocatoria |
| Concurrencia: un solo efecto | ✅ | B (repetido), sonda | Convocatoria |
| Aislamiento de claves cliente/sistema; I1 intacto | ✅ | B, mutación | Convocatoria |
| Rollback y llamador externo que captura | ✅ | B | Convocatoria |
| `FUNDING_REJECTED` solo por rechazo permanente verificado (D3) | ✅ | B, mutación | Convocatoria |
| `CLOSE` no terminal y fuera de la cola (D3, P9) | ✅ | B, mutación | Convocatoria |
| Consulta de recuperables | ✅ | B | Convocatoria |
| Retry autónomo estable; sin agotamiento bajo carga | ✅ | B (10 + 40 ejecuciones), sonda | Convocatoria |
| Sin retry interno dentro de una transacción externa | ✅ | B, mutación | Convocatoria |
| Suite completa en verde | ✅ 194/0/0 | B | Convocatoria |
| **Aprobación humana de la Enmienda 2 de ADR-037** | ❌ BORRADOR | D | Convocatoria (decisión humana) |
| **Aprobación humana de ADR-043** (por la consulta de recuperables) | ❌ Propuesto | D | Convocatoria (decisión humana) |
| `estado-fase6.md`, plan §4.4/§16 y Javadocs §16 al día | ⚠️ | D | Documentación no bloqueante |
| Enmienda de ADR-041 (estado público) | ⚠️ | D | Documentación (`api`), no bloqueante |
| P10 trazabilidad de `FUNDING_REJECTED` | ⏸ Diferido | D | Convocatoria (diferido explícitamente) |
| `convocatoria/` versionado en rama `feat/` | ⏸ Sin commit (no autorizado) | C | Proceso |
| Orquestador, disparo, scheduler, T1, espacio de claves de génesis, P8, ADR-038, P3, P1, R4 | ❌ | A, D | Dependencias externas |

## 6. Dictamen

### NO CERRADO

El perímetro técnico de Convocatoria está completo: todos sus invariantes están implementados, protegidos por tests en verde y validados por mutación. No queda ninguna condición del bloque que requiera **implementación**. Los únicos bloqueos reales son dos actos de **decisión humana** dentro del propio perímetro:

1. **Aprobar el texto de la Enmienda 2 de ADR-037** (§9). Sin ella, la semántica implementada carece de base normativa según `convocatoria-resumen.md:91`.
2. **Aprobar ADR-043**, en lo que afecta a la consulta de intenciones recuperables de Convocatoria (regla 3.5 de `reglas-equipo-y-agentes.md`).

En cuanto ambos estén aprobados, el bloque queda **CERRADO** en su perímetro. Lo restante es documentación no bloqueante (§4) y las dependencias externas del §3, que bloquean el Golden Path pero no pertenecen a Convocatoria.
