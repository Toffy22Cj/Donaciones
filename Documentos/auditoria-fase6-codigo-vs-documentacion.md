# Auditoría Fase 6 — código vs. documentación (Convocatoria, Identidad, Blockchain, IA)

**Fecha:** 2026-10-07. **Rama auditada:** `develop`, HEAD `e269985`.
**Método:** lectura del código real de `develop` (no de ramas ni de memoria) contrastada con `estado-fase6.md`, `documento-maestro-proyecto.md` y los documentos de cada capa. Cada hallazgo cita archivo de código y documento. La evidencia de tests es la ejecución de esta sesión (§9).
**Leyenda de severidad:** **A** = el documento afirma algo falso sobre el estado del código o del proceso; **M** = omisión o desfase que puede inducir a error al planificar; **B** = nombre o detalle cosmético.

---

## 1. Resumen

| Capa | Estado real en código (`develop`) | Desfase principal de la documentación |
|---|---|---|
| Convocatoria | Primer corte + cierre del flujo de fondos dentro del módulo, **commiteado y fusionado** (`81cf87c`, PR #29 `4374d55`). `app → convocatoria` añadido (`e269985`) con adaptador real de `OrganizationVerificationPort` | `estado-fase6.md` decía "sin commit" y "`app → convocatoria` no añadido". `ADR-037` sigue diciendo "Pendiente de implementación". Colisión de número ADR-043 (§2.1) |
| Identidad | ADR-038 tareas 1–8 **fusionadas en `develop`** (`aebedb2`). Sin JWT ni endpoints HTTP | `estado-fase6.md` y ADR-038 decían "pendiente de merge a `develop`" |
| Blockchain | Productor (Fases 1–3), `IntegrityVerificationPort`, **recuperación automática de `COLLECTING`**, `leafHashes` persistidos, partición por presupuesto, `verifyAllAnchored` en streaming | 3 de los 6 pendientes de `estado-fase6.md` §4 ya están implementados; `documento-maestro` §8.2 dice que el anclaje "aún no está implementado" |
| IA | Pipeline de donación individual operativo (`DonorReportGenerator`, `AuditFactsPortImpl`). `CampaignAuditFactsPort` solo como contrato, sin productor ni consumidor | La clase `NarrativeGenerator` que citan los documentos **no existe**: se llama `DonorReportGenerator` |

---

## 2. Hallazgos transversales

### 2.1 [A] Colisión de número: ADR-043

- Los documentos de Convocatoria (`convocatoria-resumen.md` §0/§6.21, `implementation_plan.md`, `ADR-037-enmienda-2`, `estado-fase6.md`, auditorías) citan **`ADR-043-recuperacion-aplicacion-fondos-convocatoria.md`** (recuperación automática de la aplicación de fondos, estado Propuesto).
- Ese archivo **no existe en el repositorio**. El único ADR-043 presente es `ADR-043-frontend-movil-paxfide-mobile.md` (frontend móvil, PROPUESTO 2026-09-30), que se declara "el siguiente libre tras ADR-042".
- Consecuencia: el bloqueante "aprobación humana de ADR-043" de Convocatoria apunta a un documento ausente, y el número está reclamado por dos decisiones distintas.
- **Decisión humana requerida:** renumerar uno de los dos (p. ej. recuperación de fondos → ADR-044) y añadir al repositorio el documento de recuperación, o confirmar que vive fuera del repositorio.

### 2.2 [M] Números de ADR "tentativos"

ADR-037, 039, 040 y 041 siguen titulados "número tentativo — confirmar contra el catálogo real antes de commitear", aunque ya están commiteados y el catálogo (§5 de `documento-maestro-proyecto.md`) no los incluía. Con §2.1, el catálogo es la única forma de evitar más colisiones; ahora queda registrado en el documento maestro.

### 2.3 [M] Catálogo de ADRs del documento maestro incompleto

`documento-maestro-proyecto.md` §5 terminaba en ADR-027. Faltaban ADR-028 a ADR-043 (Fases 5 y 6). Actualizado en esta sesión.

---

## 3. Convocatoria

| # | Documento | Afirmación | Código real | Sev. |
|---|---|---|---|---|
| C-1 | `estado-fase6.md` §2 y §3bis | Primer corte "sin commit"; HEAD `673eda92` | Commiteado en `81cf87c` y fusionado en PR #29 (`4374d55`) | A |
| C-2 | `estado-fase6.md` §3bis | "`app → convocatoria` **no** añadido (B-1); `app/**` sin cambios" | Añadido en `e269985` con `OrganizationVerificationAdapter`; el contexto completo de `app` arranca | A (ya con nota) |
| C-3 | `ADR-037` cabecera | "Pendiente de implementación" | Primer corte y flujo de fondos del módulo implementados (`convocatoria/`, 114 clases, 194 tests) | A |
| C-4 | `estado-fase6.md` §2 vs. §5 | §2: "`clearFundsGenesis` idempotencia verificada"; §5: "idempotencia… no verificada… máxima severidad" | Idempotencia por `commandId` sí probada (`ProcessedCommandIdempotencyIntegrationTest`: reenvío del mismo comando). **T1** sigue abierto: `FundCommandService.clearFundsGenesis` (`core/.../FundCommandService.java:83-97`) sigue envuelto en `retryTemplate.execute`. **P8** abierto: pasa `List.of()` como mensajes de outbox. Las dos frases no se contradicen si se precisa: verificada la idempotencia ante reenvío; pendientes T1/P8 para el orquestador | M |
| C-5 | `implementation_plan.md` §11, fila X2 | `IdentityPrincipalPort` "no filtra `INACTIVE`" | `IdentityPrincipalPortImpl.resolvePrincipal` lanza `InactiveAccountException` (commit `4872bd7`). Sigue cierto que `AccountNotFoundException` no está en `contracts` | M |
| C-6 | `convocatoria-resumen.md:421` | "`contracts` solo contiene `IdentityPrincipalPort`, `AuditFactsPort`, `NarrativeReadPort` y `HashPort`" | `contracts` contiene además `CampaignAuditFactsPort` y `CampaignAuditFactsDTO` | B (registro histórico) |
| C-7 | — (no documentado) | — | Al unir `convocatoria` y `core` en `app`, sus dos `MongoProcessedCommandAdapter` chocaban por nombre de bean; resuelto con nombre explícito `convocatoriaProcessedCommandAdapter` (`e269985`). Nueva variable de entorno obligatoria `CONVOCATORIA_BANK_TRANSFER_EXPIRATION` sin valor de producto decidido | M |

Verificado y conforme: `convocatoria → contracts` como única dependencia de proyecto (`convocatoria/pom.xml`); barrera `APPLY_FUNDS`, estado `FUNDING_REJECTED` y consulta `findConfirmedPendingApplication` presentes (`DonationIntentRepositoryPort`, `MongoDonationIntentRepositoryAdapter`, `ApplyFundsResult`). Orquestador, disparo inmediato y scheduler: **no existen** en `app` (conforme a lo documentado).

## 4. Identidad

| # | Documento | Afirmación | Código real | Sev. |
|---|---|---|---|---|
| I-1 | `estado-fase6.md` §2, §5; `ADR-038` cabecera y §9 | "Implementado en `feat/identity-adr-038`… pendiente de merge a `develop`" | Fusionado: `aebedb2` (Merge `origin/feat/identity-adr-038` into `develop`) | A |
| I-2 | `estado-fase6.md` §6 | Próximo paso: "(3) merge de Identidad a develop" | Ya hecho | A |
| I-3 | `ADR-038` §9.4 (deuda) | Revalidación del llamador duplicada en 5 servicios; `MongoTransactionRetryHelper` instancia `CommitRetryingMongoTransactionManager`; `getRetryCount()` público para tests; contenedor por clase | Las cuatro siguen presentes (`Grant/Revoke/Verify/Reject/RequestOrganizationInformationService`; `MongoTransactionRetryHelper.java:45`, `:130`; `BaseMongoIntegrationTest`) | — (conforme) |
| I-4 | `identity-resumen.md` §5, ADR-038 §2.7 | JWT, `TokenIssuerPort`, `AuthenticateAccountPort`, `LoginController` diseñados | Ninguno existe en código (`api` solo tiene los 3 controladores de tracking de Fase 3) | — (conforme: pendiente declarado) |
| I-5 | — | — | Nuevo consumidor de `identity` en `app`: `OrganizationVerificationAdapter` (lee `Organization.verificationStatus`) | M (documentado ahora) |

## 5. Blockchain

| # | Documento | Afirmación | Código real | Sev. |
|---|---|---|---|---|
| B-1 | `estado-fase6.md` §4 | Pendiente: "Mecanismo operativo que descubre y reintenta batches `COLLECTING` abandonados" | Implementado: `BlockchainAnchorProducer.recoverStaleCollectingBatches()` (Fases 2+3 sin reclamar de nuevo), `MerkleBatch.recoveryAttempts`, `MerkleBatchRepositoryPort.incrementRecoveryAttempts`, config `crypto.anchor.collecting-recovery.{timeout-seconds,max-per-cycle,warn-after-attempts}` (commit `793d4b8`) | A |
| B-2 | `estado-fase6.md` §4 | Pendiente: "Partición cuando un stream supera `maxEventsPerBatch` sin romper contigüidad" | `MongoUnanchoredEventAdapter.claimOrphansAndAssignBatch` reparte el presupuesto entre streams y reclama por `sequence ASC` con `limit(budget)`: un stream grande se parte en batches sucesivos contiguos. Test: `MongoUnanchoredEventAdapterTest` | A |
| B-3 | `estado-fase6.md` §4 | Pendiente: "Semántica de `matchedCount==0` en Fase 3" | Implementado como no-op benigno con log (`BlockchainAnchorProducer.java:180-185`) | A |
| B-4 | `estado-fase6.md` §4 | Pendiente: "Paginación/límite de `verifyAllAnchored()`" | Parcial: devuelve `Stream` respaldado por cursor (`streamByStatus`), sin cargar todo en memoria; no hay límite ni paginación para el llamador | M |
| B-5 | `estado-fase6.md` §3.1; `blockchain-resumen.md` §1 | `MerkleBatch` "15 campos" / "14 campos" | 16 componentes: se añadieron `leafHashes`, `maxFeePerGasOverride`, `recoveryAttempts` y `coverage` sustituye al rango escalar | B |
| B-6 | — (no documentado) | — | `leafHashes` persistidos en el batch y nueva `LegacyBatchLeafHashesUnavailableException` (además de `LegacyBatchCoverageUnavailableException`) | M |
| B-7 | `documento-maestro` §8.2 | "el anclaje real a blockchain (Web3j) es la Tarea 12, aún no implementada" | Implementado (ADR-019/022; `crypto/infrastructure/web3j/`); ya señalado en `blockchain-resumen.md` §1 sin corregir en el maestro | A |
| B-8 | `estado-fase6.md` §4 | Pendientes: migración de batches legacy; origen del `correlationId` en scheduler | Siguen abiertos (no hay migración; `app`/`crypto` no usan MDC ni `correlationId`) | — (conforme) |

## 6. IA

| # | Documento | Afirmación | Código real | Sev. |
|---|---|---|---|---|
| IA-1 | `ia-resumen.md`, `ADR-040`, `technical_documentation.md`, `documento-maestro` (tarea 12) | El pipeline se llama `NarrativeGenerator` | No existe ninguna clase `NarrativeGenerator` en el historial de git. El orquestador es `DonorReportGenerator` (`ai/.../application/service/DonorReportGenerator.java`); la lectura asíncrona la expone `AiNarrativeReadAdapter` (implementa `NarrativeReadPort`) | B |
| IA-2 | `documento-maestro` §4.1 | `AuditFactsPort` en `core.application.port.out`; `AuditFactsPortImpl` en `core.infrastructure.projection` | `AuditFactsPort` vive en `contracts`; `AuditFactsPortImpl` en `core.application.projection` | B |
| IA-3 | `ADR-040` §7-A | C1 cerrado; C2–C5 y C8 abiertos | Conforme: `CampaignAuditFactsPort`/`CampaignAuditFactsDTO` existen en `contracts` sin implementación ni consumidor | — (conforme) |
| IA-4 | `ADR-041`, `api-contract-matrix.md` | `GET /public/campaigns/{publicCode}/narrative` contrato definido, sin implementar | Conforme: no hay controlador | — |

## 7. Documento maestro — desfases corregidos en esta sesión

- Cabecera: Fase 6 en curso con estado real de cada capa.
- §4: faltaba el módulo `convocatoria`; `app` no listaba `convocatoria`; ruta del árbol de `core` (`core/src/main/java/core/` → `com/traceability/core`).
- §5: catálogo ampliado con ADR-028 a ADR-043 (incluida la colisión §2.1).
- §8.2: anclaje Web3j implementado; `MerkleBatch` con estado `COLLECTING` y productor.
- §9: estado de Fase 6 por capa y métrica de la ejecución de esta sesión.
- §10: diccionario — "16-17 ADRs" sustituido por el rango real.

## 8. Fuera de alcance de esta auditoría (recomendado)

Corregir en sus propios documentos (no editados aquí para no reescribir registros históricos): cabecera de `ADR-037` (C-3) y de `ADR-038` (I-1); fila X2 de `implementation_plan.md` (C-5); conteo de campos de `blockchain-resumen.md` (B-5); nombre `NarrativeGenerator` en `ia-resumen.md`/`ADR-040`/`technical_documentation.md` (IA-1); renumeración ADR-043 (§2.1, decisión humana).

## 9. Evidencia de tests (ejecución de esta sesión)

`mvn clean test -fae` del reactor completo (9 módulos) sobre `develop` `e269985`, Docker real (Testcontainers `mongo:6.0` replica set), 2026-10-07:

| Módulo | core | crypto | ai | api | identity | convocatoria | app | Total |
|---|---|---|---|---|---|---|---|---|
| Tests | 229 | 50 | 19 | 34 | 261 | 194 | 42 | **829** |

Todos con `Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS`. `app` pasa de 27 a 42 tests por el adaptador de `OrganizationVerificationPort` (`e269985`) y los tests de recuperación de `COLLECTING`; el resto coincide con las cifras ya registradas.
