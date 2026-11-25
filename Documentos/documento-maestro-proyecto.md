# Documento Maestro — Motor de Trazabilidad Verificable de Donaciones

> **Consolidación del 2026-11-19 (Tarea D del encargo «cierre de fotos y documentación») — Actualizada el 2026-11-24 sobre `develop` en `86933a4c`.** Base: consolidación sobre `09e47a1`, actualizada el `2026-11-24` tras verificar el código fuente real en `86933a4c` (corrección de versiones exactas en `pom.xml`/`application.yml`, incorporación de ADR-032 Enm. 1 implementada en #194/#195, ADR-044 Enm. 4 PROPUESTA en #196, `DeviceCredential`, `CampaignAuditFactsProducer` en `app` y métrica vigente de **2 262 tests** en §9.2; detalle en la nota N-10). Cada tema aparece una sola vez: lo vigente queda en el cuerpo y lo superado pasa a las *Notas históricas* del final, con su fecha.

**Nombre comercial provisional** (no usado en código): el proyecto se ha llamado informalmente «PaxFide»; es explícitamente no vinculante y puede cambiar sin afectar nada del dominio, la arquitectura ni el código.
**Base package Java:** `com.traceability` (con `identity` en el paquete raíz `identity.*`, escaneados conjuntamente desde `TraceabilityApplication`).
**Fase actual:** Fase 6 implementada en `develop`; Fases 1 a 5 formalmente cerradas (detalle de la Fase 5 en `estado-fase5.md`; registro de los bloques de la Fase 6 en `estado-fase6.md`). Estado de `develop` y métrica de tests: §9.

---

## 1. Visión y objetivo del proyecto

Sistema de trazabilidad verificable para donaciones de organizaciones sociales. Registra y demuestra el ciclo de vida completo de una donación:

```
Ingreso financiero → Transmutación/adquisición de recursos → Movimiento logístico
→ Recepción → Entrega final
```

**Principio rector:** el Event Store es la única fuente de verdad. Los eventos son hechos históricos inmutables. El sistema **no** se modela como CRUD tradicional en el dominio de trazabilidad: el estado se reconstruye desde eventos o se mantiene en proyecciones derivadas y reconstruibles. (Los módulos `identity` y `convocatoria` son CRUD con registro de auditoría propio, por decisión explícita: ADR-025 y ADR-037.)

**Caso de uso demostrativo:** una fundación recibe una donación digital, la convierte en recursos físicos (kits, insumos), los rastrea hasta el beneficiario final y sella criptográficamente cada paso para que nadie —ni la propia organización— pueda alterar la historia sin que se detecte.

## 2. Arquitectura general

### 2.1 Estilo arquitectónico
- **Monolito modular** (no microservicios): un repositorio, un despliegue, fronteras de módulo estrictas que permitirían extraer servicios sin reescribir.
- **Event Sourcing** como persistencia del dominio de trazabilidad.
- **Domain-Driven Design pragmático:** los Aggregate Boundaries se deciden por invariantes y consistencia transaccional, nunca por conveniencia de nombres.
- **Arquitectura hexagonal (Ports & Adapters):** el dominio (`core.domain`) es Java puro, sin Spring ni MongoDB. La comunicación con el exterior pasa por puertos definidos en `contracts` o en `core.application.port.out`.
- **CQRS:** modelo de escritura (Aggregates + Event Store) separado del de lectura (proyecciones desnormalizadas y reconstruibles).

### 2.2 Regla de oro
*Correctness > Convenience. Explicitness > Magic. Domain Integrity > Framework Convenience. Long-term Maintainability > Short-term Speed.* Evitando también el sobrediseño: la solución más simple que satisfaga los requisitos presentes y los riesgos previsibles. No se introduce infraestructura (Kafka, Redis, Kubernetes, microservicios, Event Bus, Service Mesh) «porque podría necesitarse»: cada pieza exige una necesidad demostrable.

## 3. Stack tecnológico

| Componente | Tecnología |
|---|---|
| Lenguaje | Java 21 (backend) y Python ≥ 3.11 (`paxfide-predictor`, entrenamiento offline) |
| Framework | Spring Boot **3.4.4** (`pom.xml`, `spring-boot.version`). El parche 3.4.13 y la decisión de versión están en ADR-052, **PROPUESTO** y no fusionado (§5.X) |
| Build | Maven multi-módulo (8 módulos Java + padre) |
| Persistencia | MongoDB 7.0 como Replica Set `rs0` (Spring Data MongoDB) |
| IA | Spring AI 1.0.0-M6 (compatible con la API de OpenAI / Groq, modelo configurado en `application.yml`: `openai/gpt-oss-20b`) |
| Blockchain | Web3j **4.12.0** (`crypto/pom.xml`, `web3j-maven-plugin:4.9.4`; EVM / Sepolia / Ganache / Polygon Amoy) |
| Autenticación | Nimbus JOSE + JWT **10.10** (`pom.xml`; HS256 con rotación `kid` en `api`; verificación de Google OAuth RS256/JWKS en `app`); `spring-security-crypto` (BCrypt coste 12, sin `spring-boot-starter-security`) |
| Multimedia | AWS SDK for Java v2 S3 **2.55.10** (`pom.xml`, con `url-connection-client`, compatible con AWS S3 y MinIO) y sanitización/censura de imágenes con `javax.imageio` |
| Correo | `spring-boot-starter-mail` (SMTP `multipart/alternative` con plantillas propias; Mailpit en desarrollo) |
| Identificadores ordenables | `com.github.f4b6a3:ulid-creator:5.2.3` (`identity`, `app`) |
| Hashing | SHA-256 / HMAC-SHA256 |
| Canonicalización | JSON Canonicalization Scheme (JCS, RFC 8785), `io.github.erdtman:java-json-canonicalization:1.1` |
| Testing | JUnit 5, Testcontainers (MongoDB como Replica Set de 1 nodo, requerido para transacciones), ArchUnit (`1.3.0` en `core`/`crypto`/`ai`/`identity`/`convocatoria`; `1.2.1` en `api`/`app`), `maven-surefire-plugin:3.5.4` *(sin JaCoCo configurado en los POMs)* |
| Inyección | Solo por constructor; `@Autowired` en campos está prohibido |
| Utilidades | Lombok solo donde aporte valor real, nunca como dependencia arquitectónica |

Ninguna tecnología nueva se introduce sin justificar qué problema resuelve, por qué el stack actual no basta, su coste, su impacto arquitectónico y las alternativas consideradas.

## 4. Estructura de módulos Maven

```
raíz/ (pom.xml, packaging=pom)
├── contracts/    interfaces + DTOs puros; cero dependencias de infraestructura. Único módulo del que crypto y ai pueden depender.
├── core/         depende de contracts. Dominio (domain), orquestación (application), adaptadores técnicos (infrastructure).
├── crypto/       depende ÚNICAMENTE de contracts. Implementa HashPort y el anclaje. NUNCA depende de core.
├── ai/           depende ÚNICAMENTE de contracts. Implementa/consume AuditFactsPort y CampaignAuditFactsPort. NUNCA depende de core.
├── api/          presentación REST (ADR-020). Depende de core (solo core.application.port.out) y de contracts. NUNCA importa core.infrastructure.*.
├── convocatoria/ (Fase 6, ADR-037 y enmiendas) depende ÚNICAMENTE de contracts. Convocatoria, CampaignFundingLedger, CampaignAssignment y
│                 DonationIntent: CRUD + audit log propio, sin Event Sourcing. Sus dependencias hacia Identidad salen por puertos propios
│                 (OrganizationVerificationPort) o por contracts (IdentityPrincipalPort), compuestos en app.
├── identity/     (Fase 4, ADR-027) Aggregates Account y Organization sin Event Sourcing (CRUD + audit log append-only, ADR-025).
│                 Depende de spring-boot-starter, spring-data-mongodb, spring-security-crypto y, desde 8012f87, de contracts. NUNCA de core.
└── app/          módulo de ensamblaje y composición transversal. Depende de core, crypto, ai, api, identity y convocatoria. Provee la
                  configuración compartida (TraceabilityInfrastructureConfig, MongoIndexInitializer), el punto de entrada
                  (@SpringBootApplication), los casos de uso entre módulos (FundsApplicationOrchestrator, ConfirmAndApplyDonationIntentUseCase,
                  ConfirmGatewayPaymentUseCase, BlockchainAnchorProducer, IntegrityVerificationUseCase, TrackingIntegrityService,
                  AssetAnchorStatusProducer, MediaUploadService, CampaignPredictionUseCase, AccountImpactQuery, OrganizationActivityQuery,
                  OrganizationIntegrityQuery, OrganizationVerificationAdapter, PlatformAdminBootstrapRunner), adaptadores externos
                  (S3MediaStorageAdapter, SMTP, NimbusGoogleIdTokenVerifier, DivipolaLocationCatalog) y los controladores REST de producto
                  (app.web.*) que cruzan varios módulos. Cero lógica de dominio propia.
```

**Componente fuera de Maven:** el directorio `paxfide-predictor/` (Python, ADR-044) entrena offline el modelo y exporta un artefacto JSON determinista, `app/src/main/resources/predictor/campaign-predictor-baseline-0.3.0.json`, que `CampaignPredictorModel` (en `app`) evalúa en memoria sin Python en tiempo de ejecución. Las notas de la versión 0.2.0 siguen en `paxfide-predictor/RESULTADOS-baseline-0.2.0.md`.

**Reglas verificadas por ArchUnit** (rompen el build si se violan):
- cualquier clase bajo `core.domain` que importe Spring, MongoDB o BSON;
- `ai` o `crypto` que importen algo de `core.*`;
- cualquier clase bajo `api.*` que importe `core.infrastructure.*` (demostrada activa con una violación deliberada de prueba);
- cualquier clase bajo `identity.domain` que importe `org.springframework.*` o `com.mongodb.*`; cualquier clase bajo `identity.*` que importe `core.infrastructure.*`;
- cualquier clase de `convocatoria` que importe `core` o `identity` (`ConvocatoriaArchitectureTest`; `app` no está en su classpath).

### 4.1 Árbol interno de `core`
```
core/src/main/java/com/traceability/core/
├── domain/            Java puro: shared/ (AggregateRoot, EventStream, excepciones base), event/ (DomainEvent, payloads, GENESIS_HASH),
│                      fund/ (Aggregate Fund + payloads), physicalasset/ (Aggregate PhysicalAsset + payloads)
├── application/
│   ├── port/out/      EventStorePort, OutboxPort, UnanchoredEventRepositoryPort, DonationReadPort  (AuditFactsPort vive en contracts)
│   ├── event/         EventEnvelopeFactory, EventPayloadRegistry, EventCanonicalMapper
│   ├── saga/          OutboxSagaCoordinator, SagaPolicy<T>
│   ├── service/       TransactionalEventPublisher
│   └── projection/    DonationProjectionHandler, DonationAuditFactsHandler, ProjectionEventHandler, ProjectionRetryScheduler, AuditFactsPortImpl
└── infrastructure/
    ├── persistence/mongo/   MongoEventStoreAdapter, MongoOutboxPort, documentos @Document
    └── projection/          ProjectionEventSource (Change Streams), documentos de lectura
```

## 5. Catálogo de decisiones arquitectónicas (ADR)

**Cómo leerlo.** Una entrada por ADR, con su estado **real** y su procedencia (el documento y la nota fechada que lo dicen). «APROBADO — X, fecha» es lo que dice el documento; «implementado» es lo que consta en `estado-fase6.md` o en el código de `develop`. Los números **052 a 058** son los de la rama `claude/festive-newton-687sd0` (no fusionada); el **059** ya está en `develop`. **Siguiente número libre: ADR-060** (la enmienda de ADR-032 y la de ADR-044 de los planes del 2026-11-19 son enmiendas, no números nuevos) (regla: un número se reserva aquí antes de usarse en cualquier documento).

### I. Límites de dominio y consistencia
- **ADR-001 — Segregación de Agregados.** `Fund` y `PhysicalAsset` son Aggregate Roots transaccionalmente independientes; la asignación de donaciones a activos es consistencia eventual. `Donation` **no** es un Aggregate: es una proyección de lectura.
- **ADR-002 — Granularidad del activo físico.** `PhysicalAsset` = Unidad Logística Trazable, con `assetId` opaco; el mecanismo físico (QR/RFID/código de barras) es independiente de la identidad del Aggregate.
- **ADR-003 — Separación custodia/geografía.** `custodianRef` (responsabilidad) y `currentLocation` (nodo logístico) son ejes independientes. Se abandonó la «Transferencia» genérica.

### II. Modelos operativos y matemáticos
- **ADR-004 — Reembolsos.** `Fund` nunca muta `clearedAmount` destructivamente; `refundedAmount` es monótono creciente. Invariante: `refundedAmount + newRefund <= clearedAmount`. Si el reembolso excede `availableAmount`, se acepta y se levanta `causedDeficit = true` (la logística ya materializada no se revierte).
- **ADR-005 — Desconsolidación parcial y repetible (`ASSET_SPLIT`).** El padre sobrevive si `Q_after > 0`; si llega a 0 pasa a `DEPLETED` (terminal, reversible solo por `CompensateAssetSplit`).
- **ADR-006 — Telemetría vs. auditoría criptográfica.** El GPS continuo y las paradas no oficiales quedan fuera del Event Store y del pipeline criptográfico. No existe `IN_TRANSIT` ni `ASSET_TRANSIT_REPORTED`.
- **ADR-016 — Génesis dual de `Fund`.** `FUND_REGISTERED` (con promesa, `PLEDGED`) o `FUNDS_CLEARED` directo como primer evento. Prohibido insertar eventos sintéticos para uniformar.

### III. Resiliencia, sagas y compensaciones
- **ADR-007 — Sagas vía Outbox transaccional.** Comunicación entre streams mediante `OutboxSagaCoordinator` genérico con `SagaPolicy<T>` por caso de uso.
- **ADR-008 — Compensación de fugas de inventario y dinero.** Toda saga entre agregados tiene compensación (`ALLOCATION_REVERSED`, `ASSET_SPLIT_COMPENSATED`); la compensación de un split puede «resucitar» un activo desde `DEPLETED` con `statusBeforeSplit`. **Enmienda 1 (de ADR-007/008): APROBADA — Carlos, 2026-10-07** (`ADR-008-enmienda-1-recuperacion-coordinador-sagas.md`): fases de ejecución y resolución de 4 h cada una, fallo permanente frente a transitorio, estado `RESOLVED`, salida manual por JMX condicional y auditada, recuperación hacia delante de la división y `RESOLVED_MANUALLY`; sin migración de `QUARANTINED`. Implementada en B1-bis (2026-10-07).
- **ADR-009 — Idempotencia interna de la compensación.** Todo comando de compensación referencia la operación que revierte (`allocationId`, `childAssetId`); el agregado rechaza una segunda compensación.
- **ADR-012 — Asignación financiera en dos fases.** `ALLOCATION_REQUESTED` → `pendingAllocationAmount`; `ALLOCATION_CONFIRMED` → `allocatedAmount`; `ALLOCATION_REVERSED` → `availableAmount`. Nunca un evento único `FUNDS_ALLOCATED`.
- **ADR-013 — Taxonomía estricta de identificadores.** `commandId` (intención/reintento de red), `externalEventId` (hecho externo, p. ej. webhook), `allocationId`/`refundId`/`childAssetId` (operación de negocio), `eventId` (identidad histórica, entra en la cadena de hashes). Nunca intercambiables.
- **ADR-014 — Custodia/beneficiario y continuidad de ubicación.** En `ASSET_DELIVERED`, `beneficiaryRef` se sella solo en el payload y no sobrescribe `custodianRef`; en `ASSET_DISPATCHED`, `currentLocation` se copia a `lastKnownLocation`.

### IV. Modelo de lectura (CQRS) y orden de eventos
- **ADR-010 — Cuarentena de eventos fuera de orden.** Un stream sin procesar tiene `lastProcessedSequence = 0` (génesis = 1, enmienda D-SEQ, 2026-10-07). Si `incomingSequence > lastProcessedSequence + 1`: `RETRY_PENDING` con backoff exponencial; tras 4 h, `QUARANTINED` y se pausa esa proyección sin bloquear otros streams. Reanudación manual con `resumeProjection`.
- **ADR-011 — Índice de resolución (`asset_index`).** `assetId → projectionId` (= `fundId`); técnico, reconstruible, nunca fuente de verdad.
- **ADR-015 — Capa de lectura desacoplada.** `DonationProjection` (vista de usuario), `AssetHistoryProjection`, `asset_index` y `DonationAuditFacts` (único documento que `ai` puede leer, vía `AuditFactsPort`). El LLM nunca es fuente de verdad ni accede al Event Store.
- **ADR-017 — Framework de proyección genérico** (implícito, emergente de las Tareas 10/11). `ProjectionEventSource` y `ProjectionRetryScheduler` son genéricos; cada proyector implementa `ProjectionEventHandler` y mantiene su checkpoint por stream; los reintentos se enrutan por `handlerName`.
- **ADR-042 — Orquestación centralizada de reintentos de proyección (A7.2).** APROBADO e implementado en la Fase 5; refina ADR-010/017. Conserva el número (el ADR del frontend web que también usaba «042» pasó a ADR-046). **Enmienda 1 — reconstrucción por JMX:** el documento dice «**PROPUESTA** (2026-10-07)»; no autoriza código por sí sola. No está implementada: no existe `rebuildProjections` en el código de `develop` (comprobado el 2026-11-19) y `estado-fase6.md` la deja como precondición de B-PROJ PR 2, «no urgente sin entornos con datos reales»..

### V. Integración externa y operaciones
- **ADR-022 — Resolución manual de lotes atascados (JMX).** Los lotes `STUCK` del anclaje se resuelven solo a mano (`BlockchainAdminOperationsService.resolveStuckBatch(ABANDON|RESUBMIT)`); sin reintento automático (RBF) para evitar doble gasto.
- **ADR-018 y ADR-019** (el módulo `ai` bajo confianza cero hacia el LLM; el anclaje con Web3j) se citan en este documento y en otros, pero **no tienen fichero propio en `Documentos/`** (comprobado el 2026-11-19). El resumen de ADR-018 está en `Promt-maestro.md` y ADR-039 y su Enmienda 1 dicen que no reabren ADR-019/022. Siguen sin documento: si se quieren formalizar, es un ADR nuevo con número propio, no un fichero con esos números.

### VI. Exposición pública de lectura (Fase 3)
- **ADR-020 — Ubicación y dirección de dependencia de la capa API.** Módulo `api` separado de `app`; `app → api → core` (solo puertos); `api` también depende de `contracts` por `NarrativeReadPort`.
- **ADR-021-A/B/C — Autorización por tracking code.** Credencial bearer sin cuenta; stateless, HMAC-SHA-256 completo, dominio versionado `tracking:v1:` + `fundId`, comparación timing-safe, expiración embebida (365 días), lista de revocación dispersa (`revoked_tracking_codes`). La generación ocurre fuera de la Fase 3 (la Fase 3 solo valida).
- **ADR-021-D — Perímetro de exposición.** Qué se excluye (`sourceTransactionId`, `allocationId`, `requirementId`, `sourceAllocationId`, `parentAssetRef` interno, `rootAssetRef`, `statusBeforeSplit`, `AuditMetadata`, `donorRef`), qué se enmascara (`currentCustodian`, `vendorId`, `currentLocation`→zona) y qué se expone (`assetId`→`assetRef` por HMAC, cantidades, estado, montos).
  - *Enmienda 1 — huella de la evidencia de entrega* (`ADR-021-D-enmienda-1-huella-evidencia.md`): el encabezado dice «BORRADOR»; una nota del 2026-10-25 la da por **APROBADA por Manuel** (#147, por delegación de Carlos) e **implementada** (`estado-fase6.md` §0.64). Expone `evidenceRegistered` y, con formato `sha256:`, `evidenceRef`; aclara P-21D-4.
  - *Enmienda 2 — copia censurada en el seguimiento:* **APROBADA — Manuel, por delegación de Carlos, 2026-11-18** (`decisiones-delegadas-2026-10.md`, «Firmas por delegación»). **No implementada:** la Tarea 4 de fotos está **bloqueada hasta ADR-053 u otro límite de peticiones aprobado**.
- **ADR-023 — Sin Spring Security.** El mecanismo de la Fase 3 es un `OncePerRequestFilter` propio (`TrackingCodeAuthFilter`).
- **ADR-024 — Exposición asíncrona de narrativas.** `NarrativeReadPort` (en `contracts`), estados `AVAILABLE`/`PENDING`, generación lazy sin `.join()`. **Enmienda aprobada** (`ADR-024-enmienda-narrativa-endpoint.md`): la narrativa es un endpoint independiente (`GET /tracking/narrative`), nunca embebida en `PublicDonationTrackingDTO`.

### VII. Módulo de identidad y cuentas (Fase 4)
- **ADR-025 — Persistencia de identidad.** CRUD sobre MongoDB con audit log append-only (sin cadena criptográfica); operaciones entre agregados en transacción ACID con `TransactionTemplate` programático y reintento acotado solo ante `TransientTransactionError`.
- **ADR-026 — Modelo de dominio de identidad.** `Account` y `Organization` (`Membership.roles` nunca vacío); roles `REPRESENTATIVE` (exactamente uno), `ADMINISTRATOR` (0..N), `EMPLOYEE`; pertenencia única; transferencia de representación por un único comando atómico. **Enmienda — desactivación de un Platform Administrator** (`ADR-026-enmienda-desactivacion-platform-administrator.md`): origen en el cierre del hueco A de ADR-038, aprobada por el equipo el 2026-09-30.
- **ADR-027 — Módulo Maven independiente `identity`.** Bounded context distinto de `core`; no modifica `donorRef`, `custodianRef`, `beneficiaryRef` ni `assetRef`. BCrypt (factor 12) vía `spring-security-crypto`.

### VIII. Autorización y actores (Fase 5)
- **ADR-028 — `Organization ↔ Fund`.** Aprobada en la Fase 5.
- **ADR-029 — `Organization ↔ PhysicalAsset` y donación en especie.** Aprobada en la Fase 5, revisada (C3/C5). **Enmienda 1 (`campaignRef`):** APROBADA — Carlos, 2026-10-07; implementada el 2026-10-07 (D-CAMPAIGN); `ASSET_REGISTERED`/`ASSET_SPLIT` 3.0; registra el hallazgo H-PROJ. **Enmienda 2 (zona pública DIVIPOLA):** el encabezado dice «PROPUESTA»; **APROBADA por Manuel, dueño del backend** (comentario en #170, 2026-11-02) e **implementada** en `feat/core-zona-en-cada-paso` (`estado-fase6.md` §0.74). Sella el departamento y el municipio DANE (`SealedZone`) en cada paso. *(La copia del maestro decía «aprobada por Carlos»: lo dice el documento de otra manera; ver N-9.)*
- **ADR-030 — `actorRef`: ubicación y persistencia.** Aprobada en la Fase 5.
- **ADR-031 — Taxonomía de `ActorRef`.** Aprobada en la Fase 5.
- **ADR-032 — Autorización de comandos en `core`.** Puerto `IdentityPrincipalPort` (en `contracts`), guardas de pertenencia y de rol, matriz de autorización. Aprobada en la Fase 5. **Enmienda 1 (lectura del lote para `ADMINISTRATOR` y `REPRESENTATIVE` de la misma organización):** `ADR-032-enmienda-1-lectura-del-activo.md` (PR #194, `3d029fa0`) e **implementada en `develop`** (PR #195, `c5cb107d`): `PhysicalAssetOperationalQueryService.findOperationalView` admite `ADMINISTRATOR`, `REPRESENTATIVE` y `EMPLOYEE` de la misma organización; los comandos y `findSplitStatus` siguen exigiendo exclusivamente `EMPLOYEE`. *(Nota: el encabezado del fichero de la enmienda conserva «PROPUESTA» como texto previo a la fusión.)*
- **ADR-033 — Contrato y envelope de Asset Registration Saga.** Aprobado parcialmente.
- **ADR-034 — Read Model de Pending Allocation.** Aprobado; sustituye a `FundAllocationSagaPolicy` por resolución manual auditada.
- **ADR-035 — `HumanActor` como variante de `ActorRef`.** Aprobado.
- **ADR-036 — Reversión administrativa de asignación.** Aprobado.

### IX. Fase 6
- **ADR-037 — Convocatoria, `CampaignFundingLedger`, `CampaignAssignment`, `DonationIntent`.** Aprobado; implementado en `develop`. Enmiendas: **1** APROBADA (decisión humana del 2026-09-30, N1–N12); **2** (confirmación y aplicación de fondos) APROBADA — Carlos, 2026-10-07, con C1–C3 (aprobación en parte retroactiva de lo fusionado en el PR #29); **3** (pasarela y consulta de intención) APROBADA — Carlos, 2026-10-07 (el `trackingCode` dura 1 año); **4** (solicitud y aprobación de configuración) APROBADA como decisión delegada, 2026-10-07T23:59Z; **5** (fecha de creación y vista del historial) APROBADA — Carlos (comunicado por Manuel, 2026-10-21); **6** (listado de convocatorias para el representante) **PROPUESTA — espera aprobación** (redactada el 2026-11-15).
- **ADR-038 — Identidad: `HumanActor`, Platform Administrator, verificación de `Organization`, autenticación.** Aprobado; tareas 1–8 implementadas; JWT y autenticación HTTP implementados bajo ADR-047 y ADR-050.
- **ADR-039 — Productor de `MerkleBatch` e `IntegrityVerificationPort`.** Aprobado; implementado, incluida la recuperación de batches `COLLECTING`. **Enmienda 1 (verificación recalculada, `COLLECTING_FAILED`/`RELEASED`): APROBADA — Carlos, 2026-10-08** (encargo 6, P6); implementada (`RecomputingEventIntegrityChecker`, `IntegrityVerificationUseCase`, `batch_release_audit`).
- **ADR-040 — `ConvocatoriaAuditFacts` (IA).** C1 cerrado (`CampaignAuditFactsPort` separado en `contracts`) y C2–C5 cerrados e implementados (`CampaignAuditFactsProducer` en `app` —combinando `CampaignFundingFactsQuery` y `CampaignDeliveryFactsQuery`—, `CampaignNarrativeGenerator`, `CampaignGroundingValidator`; `GET /api/v1/public/campaigns/{publicCode}/narrative`). **C8** (narrativa de una convocatoria `CLOSED`): decidido como DD-38 (sí; las `PRIVATE_LINK` por su código, como CV-07), **RATIFICADA — Carlos, 2026-10-07**; implementado.
- **ADR-041 — APIs de producto y frontend.** Aprobado; implementado: todos los controladores `/api/v1` (autenticación, cuenta, organizaciones, campañas, donaciones, activos, multimedia, catálogo DIVIPOLA, predicción, administración), documentados en `referencia-api-v1.md`.
- **ADR-043 — Frontend móvil `paxfide-mobile`** (`ADR-043-frontend-movil-paxfide-mobile.md`, copia del repositorio `Toffy22Cj/PaxFide_Mv`). **APROBADO — Carlos, 2026-10-07T22:35Z.** Antes citado también como «ADR-043» para la recuperación de fondos (ver ADR-045). Las **Enmiendas 6** (gráficos de la organización: fondos y actividad) **y 7** (fotos en despachar y recibir) viven en el repositorio móvil y están **APROBADAS — Adonis, 2026-11-19**, igual que la revisión de `PaxFide_Mv#17` (`decisiones-delegadas-2026-10.md`, «Firmas del 2026-11-19»); la línea de estado se cambió en la rama de #17.
- **ADR-044 — Componente predictivo en Python.** **APROBADO e implementado** (módulo `paxfide-predictor/`, `CampaignPredictorModel`, `CampaignPredictionUseCase`, `CampaignPredictionHistoryUseCase`, endpoints públicos y de organización). **Modelo vigente: `baseline-0.3.0`** (regresión logística para la probabilidad de alcanzar la meta). Enmiendas: **1** (predicción en el backend) — el documento dice «BORRADOR» con decisiones delegadas DD-41 a DD-47 «pendientes de ratificar por Carlos»; la función está implementada; DD-41 a DD-47 están **RATIFICADAS — Carlos, 2026-10-07**; una nota del 2026-11-11 (Adonis) fija `baseline-0.3.0` y una del 2026-11-16 añade el perfil `demo-historico`, cuyo visto bueno sobre `core` es **APROBADO — Carlos, 2026-11-19**; **2** (traslado del componente a `Donaciones/paxfide-predictor/`) — no tiene fichero propio: la recoge una nota del 2026-10-08 dentro del documento de la Enm. 1; **3** (limitación de la curva de llegada) **APROBADA — Manuel, por delegación de Carlos, 2026-11-18**: el modelo reproduce la curva de llegada del generador sintético y subestima las convocatorias que recaudan de forma lineal; no cambia el modelo; **4** (mezcla de curvas de llegada y reentreno a `baseline-0.4.0`, `ADR-044-enmienda-4-mezcla-de-curvas-baseline-0-4-0.md`, PR #196, `86933a4c`) **PROPUESTA — espera aprobación de Manuel y Carlos** (sin código aún; conserva `baseline-0.3.0` seleccionable por propiedad `traceability.prediction.model-resource`). *(Dentro de `ADR-044-componente-predictivo-python.md` la sección «Enmienda 3» sigue diciendo «PROPUESTA — firma: [Falta: quién firma.]»: es texto anterior a la firma y está superado por la nota de 2026-11-18.)*
- **ADR-045 — Recuperación de la aplicación de fondos de `DonationIntent`.** APROBADO — Carlos, 2026-10-07; aprueba de forma retroactiva la barrera `APPLY_FUNDS`, `FUNDING_REJECTED` y la consulta de recuperables, fusionadas en el PR #29 sin cumplir la regla 3.5 (**incumplimiento registrado**). Implementado en `develop` (T1 en core #34; B2 en convocatoria y app #35/#36); scheduler de respaldo deshabilitado por defecto.
- **ADR-046 — Frontend web `paxfide-web`** (repositorio `Toffy22Cj/PaxFide`). APROBADO el 2026-09-28; se numeraba ADR-042 y se renumeró por decisión de Carlos el 2026-10-07. Los documentos cerrados que lo citan como «ADR-042» no se reescriben.
- **ADR-047 — JWT: librería, algoritmo y secreto de firma.** APROBADO — Carlos, 2026-10-07, con las precisiones P1–P4. Nimbus solo en `api`, HS256 con secreto ≥ 256 bits y *fail-fast*, `kid` con una clave anterior para rotar, `exp` de 8 h. Implementado.
- **ADR-048 — Origen del `donorRef`.** APROBADO — Carlos, 2026-10-07: seudónimo aleatorio por cuenta (`DonorPseudonymPort` en `contracts`, `DonorPseudonymService`, colección `donor_pseudonyms`). Implementado.
- **ADR-049 — Correo e invitaciones a organizaciones.** APROBADO como decisión delegada (2026-10-07T22:30Z, autorización (3) de Carlos; DD-60). `spring-boot-starter-mail` solo en `app`, token de un solo uso de 7 días guardado como hash. Implementado.
- **ADR-050 — Cuenta y sesiones.** APROBADO — Carlos, 2026-10-18: registro en dos pasos, aceptaciones legales, revocación con `credentialsNotBefore`, cambio y recuperación de contraseña, desactivación, Google. Enmiendas: **1** (`iat` del login) — encabezado «PROPUESTA»; **aprobada por Carlos** (partes 1 y 2, 2026-10-25; texto literal 2026-10-29); parte 1 implementada en #137, parte 2 con R-4 en #140; **2** (verificación de correo y avisos) — encabezado «PROPUESTA»; Q1–Q5 cerradas por Manuel (2026-10-29, por delegación de Carlos) y autoriza el cambio de `AuthenticateAccountPort`; implementada en #163 (`feat/identity-adr050-e2-verificacion-correo`, 2026-10-29; `estado-fase6.md` §0.70); **3** (registro con Google) **APROBADA — Carlos, 2026-11-12**.
- **ADR-051 — Fotos y almacenamiento.** APROBADO — Carlos, 2026-10-18, con la Enmienda 1 (18:45Z): almacenamiento compatible con S3, limpieza de metadatos con la JDK, `media_objects`, `evidenceRef = sha256:`. Tareas 1 a 5 implementadas. **Enmienda 2 (fotos de los pasos y copia para el donante): APROBADA — Manuel, por delegación de Carlos, 2026-11-18.** Estado de sus tareas: **1** (versiones de esquema `ASSET_REGISTERED` 5.0, `ASSET_DISPATCHED` 3.0, `ASSET_RECEIVED` 3.0 con `evidenceRef` opcional) fusionada (#187; revisada y **APROBADA — Carlos, 2026-11-19**); **2** (subida y lectura por paso) fusionada en #189; **3** (copia censurada y publicación) fusionada en #190; **4** (copia en el seguimiento público) **bloqueada** hasta ADR-053 u otro límite aprobado; **5** (web) fusionada en `PaxFide#72` (la lectura de la ficha para `ADMINISTRATOR` sin `EMPLOYEE` quedó resuelta en el backend por **ADR-032 Enm. 1**, PR #195); **6** (móvil) desbloqueada por la firma de la Enmienda 7 de ADR-043 (Adonis, 2026-11-19): empieza cuando `PaxFide_Mv#17` esté fusionado.

### X. Reservas de la rama `claude/festive-newton-687sd0` y ADR-059
La rama `claude/festive-newton-687sd0` **no está fusionada**; sus números quedan **reservados aquí** para no reutilizarlos:
- **ADR-052 — Versión de Spring Boot** (T-01; `ADR-052-version-spring-boot.md`). **PROPUESTO:** falta la decisión del aprobador entre las opciones B y C; el paso 1 (parche 3.4.13) no la necesita.
- **ADR-053 — Límite de intentos** (T-03; cierra DH-56; `ADR-053-limite-de-intentos.md`). **APROBADO y firmado — Carlos, 2026-11-19** (comunicado por Manuel; `decisiones-delegadas-2026-10.md`, «Firmas del 2026-11-19»). El documento sigue en la rama no fusionada: su línea de estado cambia cuando se traiga a `develop` (plan 3 de `plan-tanda-2026-11-19.md`). Es la dependencia de la Tarea 4 de fotos.
- **ADR-054 — Observabilidad mínima: salud y logs estructurados** (D-01; `ADR-054-observabilidad-minima.md`). **PROPUESTO**, falta la firma.
- **ADR-055 — Escaneo de dependencias y SBOM** (T-07; `ADR-055-escaneo-de-dependencias.md`). **PROPUESTO**, falta la firma.
- **ADR-056 — Autenticación de sistema y actor de integración** (F-08, ADR-A del plan). Solo reservado; **no hay documento**.
- **ADR-057 — Contrato público de integración** (F-08, ADR-B). Solo reservado; **no hay documento**.
- **ADR-058 — Webhooks salientes** (F-08, ADR-C). Solo reservado; **no hay documento**.
- **ADR-059 — `occurredAt` y `recordedAt`** (`ADR-059-occurredat-y-recordedat.md`, en `develop`). **APROBADA — Manuel, por delegación de Carlos, 2026-11-18.** **Incumplimiento registrado (regla 3.5):** el mecanismo que fecha hechos en el pasado (`EventTime` y el perfil `demo-historico`) se fusionó en #182 el **2026-11-16** sin ADR, con un plan aprobado por Manuel (`plan-paso2-datos-historicos-2026-11-16.md`); el ADR es documentación **posterior** al código y describe lo que hay. El número 059 no se reservó en este catálogo antes de usarse (regla de numeración incumplida); se regulariza aquí. Sus **dos preguntas** las respondió Carlos el 2026-11-19: (1) sí dio el visto bueno sobre `core` de #182; (2) **sí**, el event store comprobará `occurredAt ≤ recordedAt` al guardar, con una excepción con nombre propio y su test (tarea con plan propio; hasta que se fusione, el almacén no la hace).

## 6. Modelo de dominio

### 6.1 Aggregate `PhysicalAsset`
Estado interno (reconstruido por replay): `assetId`, `assetType`, `quantity` (`BigDecimal`), `unitOfMeasure` (inmutable), `lifecycleStatus`, `currentLocation` (nullable), `lastKnownLocation` (nunca null tras el registro), `custodianRef`, `parentAssetRef` (nullable), `rootAssetRef`, `allocationId` (nullable, propio), `sourceAllocationId` (nullable, heredado), `organizationRef` (ADR-029), `donorRef`/`donationRef` (Camino B, en especie), `campaignRef` (ADR-029 Enm. 1), `zone: SealedZone` (ADR-029 Enm. 2, DIVIPOLA), `finalBeneficiaryRef`, `finalEvidenceRef` (`sha256:`, ADR-051), `finalDeliveredAt`, `currentVersion`.

Versiones de esquema vigentes de las fotos por paso (ADR-051 Enm. 2, Tarea 1): `ASSET_REGISTERED` **5.0**, `ASSET_DISPATCHED` **3.0**, `ASSET_RECEIVED` **3.0**, con `evidenceRef` opcional (`null` sin foto); los lectores distinguen la versión con `instanceof` y los eventos viejos conservan su hash.

Máquina de estados (`IN_TRANSIT` fue eliminado, ADR-006):

| Estado | Comandos que acepta |
|---|---|
| `REGISTERED` | Dispatch, TransferCustody, Split |
| `DISPATCHED` | Receive, Deliver, TransferCustody |
| `RECEIVED` | Dispatch, Deliver, Split, TransferCustody |
| `DELIVERED` | — (terminal) |
| `DEPLETED` | — (terminal, reversible solo vía `CompensateAssetSplit`) |

| Comando | Evento(s) | Postcondición clave |
|---|---|---|
| `RegisterPhysicalAsset` | `ASSET_REGISTERED` | Génesis del stream |
| `DispatchPhysicalAsset` | `ASSET_DISPATCHED` | `lastKnownLocation` preserva el origen; `currentLocation = null` |
| `ReceivePhysicalAsset` | `ASSET_RECEIVED` | `currentLocation` y `lastKnownLocation` = ubicación confirmada |
| `TransferAssetCustody` | `ASSET_CUSTODY_TRANSFERRED` | Solo cambia `custodianRef`; rechaza si el nuevo es el actual |
| `SplitPhysicalAsset` | `ASSET_SPLIT` (+ `ASSET_DEPLETED` si Q llega a 0) | El padre sobrevive si Q > 0; el hijo nace por una saga aparte |
| `CompensateAssetSplit` | `ASSET_SPLIT_COMPENSATED` | Reintegra la cantidad; puede «resucitar» desde `DEPLETED` |
| `DeliverPhysicalAsset` | `ASSET_DELIVERED` | `beneficiaryRef` solo en el payload; nunca sobrescribe `custodianRef` |

Excepciones de dominio: `InvalidAssetTransitionException`, `InsufficientQuantityException`, `RedundantCustodyTransferException`, `InvalidSplitTargetException`, `DuplicateCompensationException`, `AssetTerminalStateException`.

### 6.2 Aggregate `Fund`
Estado interno: `fundId`, `organizationRef` (ADR-028), `campaignRef`, `donorRef`, `currency`, `pledgedAmount` (opcional), `clearedAmount` (histórico, inmutable), `pendingAllocationAmount`, `allocatedAmount`, `allocationAmounts` (mapa `allocationId → monto`), `refundedAmount`, `status`, `currentVersion`.

Ecuación fundamental: `availableAmount = clearedAmount - pendingAllocationAmount - allocatedAmount - refundedAmount`. Estados: `PLEDGED`, `CLEARED`, `FAILED`, `REFUNDED`/`FULLY_REFUNDED` (derivado, sin evento propio).

| Comando | Evento | Notas |
|---|---|---|
| `RegisterFund` | `FUND_REGISTERED` | Camino con promesa (ADR-016) |
| `ClearFunds` | `FUNDS_CLEARED` | Puede ser génesis directo |
| `AllocateFunds` (fase 1) | `ALLOCATION_REQUESTED` | Mueve a `pendingAllocationAmount` |
| — (fase 2, saga) | `ALLOCATION_CONFIRMED` | Mueve a `allocatedAmount` |
| — (compensación) | `ALLOCATION_REVERSED` | Devuelve a `availableAmount` |
| `RefundFunds` | `FUNDS_REFUNDED` | `refundedAmount += monto`; `causedDeficit` si excede lo disponible |

Excepciones: `InsufficientAvailableFundsException` (pedido > disponible), `ExceedsClearedFundsException` (reembolso + reembolsado > cleared), `DuplicateAllocationException` (allocationId ya existe), `InvalidFundTransitionException` (confirmar o revertir una asignación que no está pendiente).

### 6.3 Fechas de un evento (ADR-059)
`occurredAt` = cuándo ocurrió el hecho en el dominio (lo fija el agregado al emitir el evento con `EventTime.now()`); `recordedAt` = cuándo lo registró el almacén. Un evento rehidratado conserva su `occurredAt`. El perfil `demo-historico` (solo con `dev`) siembra convocatorias con fechas pasadas.

## 7. Capa de aplicación: sagas y command handlers

### 7.1 Command handlers
- `CommandRetryTemplate`: captura `ConcurrencyConflictException`, recarga el agregado y reevalúa las reglas de negocio con backoff exponencial.
- Servicios de dominio (`FundCommandService`, `PhysicalAssetCommandService`): invocan el agregado y delegan en `TransactionalEventPublisher`. Comandos de aplicación implementados: `registerFund`, `clearFundsGenesis`, `requestAllocation`, `confirmAllocation`, `reverseAllocation`, `registerPhysicalAsset`, `splitPhysicalAsset`, `deliverAsset`, además de despachar y recibir.
- `SagaPolicy` concretas: `AssetRegisteredSagaPolicy` y, desde B1-bis (2026-10-07), `SplitPhysicalAssetSagaPolicy` (crea el hijo bajo la barrera `SPLIT_RESOLUTION:{childAssetId}` y, al resolver, compensa o recupera hacia delante si el padre está `DELIVERED`). `FundAllocationSagaPolicy` fue descartada a favor de la resolución manual auditada (ADR-034).

### 7.2 Orquestación transaccional
`OutboxSagaCoordinator` (Java puro, sin Spring ni Mongo): fase de ejecución de 4 h desde `createdAt`, fase de resolución de 4 h desde `resolutionStartedAt`, `RESOLVED` tras compensar, `QUARANTINED` solo para lo que necesita a una persona (salida por JMX); backoff exponencial `2^retryCount × 15 s`; `compensate()` envuelto en su propio `try-catch` para no detener el lote; `SagaPolicy<T>` inyectada por caso de uso; `OutboxMessage` con `sourceAggregateId`.

**Escritura atómica (Transactional Outbox):** el evento de dominio y el mensaje de outbox se persisten en la misma transacción MongoDB (`TransactionalEventPublisher`, `@Transactional`, invocado desde fuera del propio bean para evitar la auto-invocación de Spring AOP).

## 8. Capa de infraestructura

### 8.1 Concurrencia optimista (innegociable)
1. El handler carga el agregado → `currentVersion = N` (fijo).
2. Evalúa las invariantes de negocio.
3. Ensambla el evento con `sequence = N + 1` (se decide aquí y no se recalcula).
4. `EventStorePort.append(streamId, expectedVersion = N, evento)`.
5. El adaptador consulta el `previousHash` del evento N exacto (no «el más reciente»).
6. El índice único `(streamId, sequence)` rechaza colisiones → `ConcurrencyConflictException` (nunca reintento automático en el adaptador). **Génesis** (enmienda D-SEQ, Carlos, 2026-10-07): un stream nuevo tiene N = 0; su primer evento es `sequence = 1` con `previousHash = GENESIS`.
7. El handler recarga y reevalúa el comando completo.

### 8.2 Pipeline criptográfico (`crypto`)
```
DomainEvent → EventCanonicalMapper (Map determinista)
            → HashPort.canonicalizeAndHash(eventData, previousHash)
            → JcsHashAdapter: inserta previousHash, canonicaliza JCS/RFC 8785, SHA-256; nunca incluye el propio eventHash
            → eventHash sellado en el documento persistido
```
`MerkleTree` agrupa los hashes (duplica la última hoja si el número es impar; orden estricto de inserción). **Anclaje a blockchain** (Web3j, ADR-019/ADR-022): implementado (Tarea 13). Ciclo de `MerkleBatch`: `COLLECTING → PENDING → SUBMITTING → SUBMITTED → {ANCHORED | ANCHOR_MISMATCH | STUCK | FAILED}`, más los estados `COLLECTING_FAILED` y `RELEASED` (ADR-039 Enm. 1).

**Fase 6 (ADR-039 y Enmienda 1):**
- `BlockchainAnchorProducer` (en `app`) reclama los eventos sin anclar del `event_store` (`UnanchoredEventRepositoryPort`; presupuesto repartido entre streams y contiguo por `sequence`), construye el árbol y pasa el batch de `COLLECTING` a `PENDING` con un *update* condicional; recupera los `COLLECTING` abandonados (`recoveryAttempts`, `crypto.anchor.collecting-recovery.*`). `MerkleBatch` guarda la cobertura multi-stream (`coverage`) y los `leafHashes`.
- `RecomputingEventIntegrityChecker` (core) + `IntegrityVerificationUseCase` (app) implementan `IntegrityVerificationPort`: recalculan el `eventHash` JCS + SHA-256 desde el payload tipado de cada evento, verifican la cadena `previousHash` desde `GENESIS_HASH` (`sequence = 1`), comprueban su posición en `MerkleBatch.leafHashes` y consultan la raíz en el contrato EVM. Los batches legacy sin `coverage`/`leafHashes` fallan explícitamente (`LegacyBatchCoverageUnavailableException`, `LegacyBatchLeafHashesUnavailableException`).
- Liberación auditada de eventos de un batch: `BlockchainAdminOperationsService.releaseBatchEvents` → `batch_release_audit`.
- Pruebas de inclusión por transición (`MerkleTree.proof`, `AssetAnchorStatusProducer`): el historial público de un activo (`GET /api/v1/donations/{trackingCode}/assets/{assetRef}/history`) incluye `anchorStatus`, `eventHash` y `anchorProof` de cada transición anclada.

### 8.3 Reconstrucción del payload tipado
`EventPayloadRegistry` mapea `eventType` (y `schemaVersion`) a la clase concreta del payload, para que `loadStream()` entregue instancias fuertemente tipadas al `switch` de `AggregateRoot.apply()`; es el punto de extensión del *upcasting*.

### 8.4 Proyecciones (CQRS)
```
event_store (Mongo)
   │  Change Stream (ProjectionEventSource, aislado de MongoEventStoreAdapter)
   ├── DonationProjectionHandler  → donation_views (vista de usuario)
   └── DonationAuditFactsHandler  → donation_audit_facts (hechos para la IA)
```
Reconstrucción: guardar `resumeToken` → *bulk* histórico → conmutar al stream desde el token, con guardia de idempotencia (`sequence <= lastProcessedSequence` → descartar). `DonationAuditFacts`: transiciones auditadas `DISPATCHED→RECEIVED` (72 h por defecto), `DISPATCHED→DELIVERED` (48 h, candidata a revisión por cubrir el trayecto completo) y `RECEIVED→DELIVERED` (48 h); los umbrales viven en `application.yml` y el usado se congela en el registro histórico; incluye `financialFlags` con `causedDeficit`.

## 9. Estado actual del proyecto

### 9.1 Tareas (Fases 2 a 4, cerradas)
| Tarea | Contenido | Estado |
|---|---|---|
| 0–1 | Scaffolding Maven multi-módulo; `AggregateRoot`, `EventStream`, contratos base | ✅ |
| 2 | `contracts`: `HashPort`, `AuditFactsPort`, DTOs | ✅ |
| 3–6 | Aggregates `PhysicalAsset` y `Fund` y sus payloads, con tabla de trazabilidad comando→ADR→test | ✅ |
| 7 / 7.1 | `OutboxSagaCoordinator` genérico (reabierto y corregido tras auditoría); command handlers y sagas activas | ✅ |
| 8 | `crypto`: `JcsHashAdapter`, `MerkleTree` | ✅ |
| 9 | Persistencia MongoDB, `EventStorePort`, Outbox transaccional, verificada con Testcontainers | ✅ |
| 10 / 10.1–10.5 | Proyecciones CQRS y cuarentena; correcciones (monto de origen, historial y logística, metadatos de `Fund`, `quantity` a `BigDecimal`, `ProjectionRetryDocument` por `.builder()`) | ✅ |
| 11–12 | `DonationAuditFacts` y `AuditFactsPort`; `ai`: `NarrativeGenerator` | ✅ |
| 13 | `crypto.infrastructure.web3j`: anclaje EVM | ✅ |
| 14 | `app`: ensamblaje (cierre de la Fase 2) | ✅ |
| Fase 3 (3.0–3.13) | Módulo `api`, `DonationReadPort`, secretos HMAC, `TrackingCodeService`, filtro de autorización, `asset_index`, `location_reference`, `assetRef`, mapper, tres endpoints públicos (`GET /tracking`, historial de activo, `GET /tracking/narrative`), `NarrativeReadPort` | ✅ |
| Fase 4 (4.0–4.10) | Módulo `identity`: value objects, `Account`, `Organization`/`Membership`, puertos, persistencia, BCrypt (costo 12), application services con reintento transaccional, suite end-to-end | ✅ |
| Fase 5 | Autorización de comandos, actores y relaciones `Organization`↔`Fund`/`PhysicalAsset` (ADR-028 a ADR-036, ADR-042) | ✅ (detalle en `estado-fase5.md`) |

### 9.2 Estado vigente de la Fase 6 y métrica de calidad
`develop` en **`86933a4c`** (2026-11-24). **Métrica de tests (CI local `mvn -B clean test -fae` sobre `12aa498`, último cambio de código fusionado en PR #195; extracto en `evidencia-ci/ci-local-12aa498-2026-11-19T19-35-10Z.txt`): 2 262 tests, 0 fallos, 0 errores, 0 omitidos**: core 448, crypto 62, ai 48, api 177, identity 448, convocatoria 272, app 807; `contracts` no tiene tests. `BUILD SUCCESS` en los 9 módulos del reactor. *(Corte previo sobre `09e47a1`: 2 255 tests, en `evidencia-fase6/surefire-develop-09e47a1-2026-11-19.txt`.)*

| Capa | Implementado en `develop` |
|---|---|
| Convocatoria e intenciones (ADR-037, ADR-045) | Módulo `convocatoria` completo; orquestador en dos transacciones (`FundsApplicationOrchestrator`, `ConfirmAndApplyDonationIntentUseCase`, `ConfirmGatewayPaymentUseCase`) y scheduler de reconciliación |
| Identidad, sesiones y gobernanza (ADR-038, 047, 048, 049, 050) | JWT HS256 con rotación `kid`, `JwtAuthFilter` *deny-by-default*, revocación por `credentialsNotBefore`, registro con verificación de correo, Google OAuth (login y registro), login biométrico por credencial de dispositivo (`DeviceCredential` / `DeviceToken`), recuperación y cambio de contraseña, aceptaciones legales, autodesactivación, invitaciones por correo, perfil institucional con validación de NIT (DIAN) y DIVIPOLA, verificación (`PENDING_VERIFICATION`/`NEEDS_MORE_INFORMATION`/`VERIFIED`/`REJECTED`), seudónimos de donante. (La desactivación administrativa, D7, sigue fuera de alcance.) |
| Criptografía, blockchain e integridad (ADR-039 + Enm. 1) | Productor de batches, verificación recalculada, `COLLECTING_FAILED`/`RELEASED`, `NonceCounterChainSync`, pruebas de inclusión por transición |
| Narrativas de IA y predictor (ADR-040, ADR-044) | Hechos (`CampaignAuditFactsProducer` en `app`) y narrativa de convocatoria con validación de *grounding*; `paxfide-predictor/` y evaluación nativa con **`baseline-0.3.0`** |
| Multimedia, territorio y APIs de producto (ADR-041, ADR-051, ADR-029 Enm. 2, ADR-032 Enm. 1) | Almacenamiento S3/MinIO, `ImageSanitizer`, verificación de evidencia de entrega, catálogo DIVIPOLA y zona sellada por paso, ficha del lote abierta a `ADMINISTRATOR`/`REPRESENTATIVE`/`EMPLOYEE` de la misma organización (PR #195), catálogo completo de `/api/v1` (54 controladores) |
| Fotos por paso (ADR-051 Enm. 2) | Tareas 1, 2 y 3 en `develop` (versiones de esquema, subida y lectura por paso, copia censurada y publicación solo `ADMINISTRATOR`); web (Tarea 5) fusionada; seguimiento público (Tarea 4) a la espera de que el límite de ADR-053 (firmado, aún en rama) esté en `develop`; móvil (Tarea 6) con su enmienda firmada, a la espera de `PaxFide_Mv#17` |
| Fechas de eventos (ADR-059) | `EventTime`, perfil `demo-historico` (#182) |

**Firmas pendientes (2026-11-24):** ADR-052, ADR-054 y ADR-055 (PROPUESTOS, en la rama `festive-newton`); ADR-037 Enm. 6 (PROPUESTA); ADR-042 Enm. 1 (PROPUESTA, sin código); ADR-044 Enm. 4 (`baseline-0.4.0`, PROPUESTA en PR #196); ADR-044 Enm. 1 sigue con encabezado «BORRADOR» aunque sus decisiones delegadas están ratificadas. **Fusionado tras el corte del 2026-11-19:** ADR-032 Enm. 1 (PR #194/#195) y borrador de ADR-044 Enm. 4 (PR #196).

**Próximos pasos** (planes en `plan-tanda-2026-11-19.md`): comprobación de `occurredAt ≤ recordedAt` (ADR-059); límite de intentos (ADR-053); copia censurada en el seguimiento (Tarea 4); móvil (fondos y actividad, y Tarea 6); aprobación y reentreno del predictor a `baseline-0.4.0` (ADR-044 Enm. 4). *(La ficha del lote para `ADMINISTRATOR` y `REPRESENTATIVE` ya quedó implementada en PR #195.)*

### 9.3 Deudas técnicas
- **`WebEnvironment` en tests.** `MongoTransactionManager` obliga a levantar más beans con Testcontainers si el *auto-discovery* choca. Resuelto en parte; vigilar si suben los tiempos.
- **Wrapper Web3j (`AnchorRegistry`).** Desde el 2026-11-06 `web3j-maven-plugin` genera en `generated-sources/web3j`, aunque lee los `.abi/.bin` de `src/test/resources/solidity/build`. Deuda menor.
- **Testcontainers (`docker.api.version`).** La negociación de versión falla en `app` y `core` pero no en `crypto`; el *workaround* fija `api.version=1.41` en `src/test/resources/docker-java.properties` (y copia `testcontainers.properties`) en ambos. Causa raíz sin aislar.
- **Snapshotting de eventos.** Pendiente para streams largos (candidato: `Fund` de campañas activas); no se implementa preventivamente: primero instrumentar la longitud del historial y evaluar solo si un stream real se acerca a ~500 eventos con latencia medible.
- **`PublicVendorCategory` sin uso** (Fase 3): el enum nunca entró en un DTO; pendiente de decisión de producto, no bloqueante.
- **Población de `location_reference`** (Fase 3): vacía o con datos mínimos; es trabajo de operación. Desde ADR-029 Enm. 2 solo respalda los eventos anteriores a la zona sellada.
- **`TrackingCodeService.revoke()`** (Fase 3): existe y está probado, pero sin endpoint ni JMX operativo (a diferencia de `resolveStuckBatch`); candidato a una mini-tarea.
- **Secretos HMAC en producción:** variables de entorno vía `@ConfigurationProperties`, sin vault; revisar si un despliegue real exige rotación más robusta.
- **Binarios `target/` trackeados** (Fase 4): resuelto en dos rondas (`chore/purge-tracked-build-artifacts` y `-round-2`). Lección incorporada a `reglas-equipo-y-agentes.md`: todo diagnóstico de higiene del repositorio se corre sobre el árbol completo.
- **Identidad ↔ `core`:** resuelto por ADR-048 (seudónimo aleatorio por cuenta); ver §5.IX.
- **Sin límite de peticiones** en la subida de fotos ni en el seguimiento (DH-56): lo cierra ADR-053 si se aprueba.

## 10. Diccionario de conceptos

- **ADR:** documento que congela una decisión arquitectónica con su contexto, alternativas y consecuencias. Los ADR-001 a ADR-059 (catálogo en §5; el 046 vive en `PaxFide`; los 052 a 058 están en una rama no fusionada) forman el contrato.
- **Aggregate / Aggregate Root:** entidad raíz que protege invariantes bajo un límite de consistencia transaccional (aquí `Fund` y `PhysicalAsset`); se reconstruye por replay.
- **allocationId / sourceAllocationId:** la operación de asignación financiera `Fund → PhysicalAsset`; el segundo es el heredado por los hijos de un split.
- **AuditFactsDTO / AuditFactsPort:** contrato (en `contracts`) por el que `ai` lee hechos deterministas de una donación sin conocer Mongo; lo implementa `AuditFactsPortImpl` (core) y lo consume `DonorReportGenerator` (`ai`).
- **CampaignAuditFactsDTO / CampaignAuditFactsPort:** contrato separado para los hechos de una convocatoria (ADR-040, C1); lo implementa `CampaignAuditFactsProducer` (`app`) y lo consume `CampaignNarrativeGenerator` (`ai`).
- **assetRef:** referencia pública opaca de un `PhysicalAsset`, por HMAC-SHA-256 completo (`asset-ref:v1:` + `assetId`); la resolución se hace recalculando el HMAC sobre candidatos acotados, nunca por índice persistido.
- **causedDeficit:** indicador derivado del dominio `Fund` cuando un reembolso excede lo disponible; un hecho, nunca una interpretación del LLM.
- **childAssetId:** el `PhysicalAsset` hijo de un `ASSET_SPLIT`.
- **commandId:** idempotencia de la intención de un comando (reintento de red, doble clic); distinto de `eventId`, `externalEventId` y los identificadores de operación.
- **Concurrencia optimista:** la versión esperada se fija al evaluar el negocio y la escritura se rechaza (no se recalcula) si ya no coincide.
- **Consistencia eventual:** `Fund` y `PhysicalAsset` pueden estar temporalmente desincronizados; se resuelve con sagas y compensación.
- **copia censurada (ADR-051 Enm. 2):** vista pixelada (PNG) de la foto de entrega, generada bajo demanda; la ven los miembros de la organización y la plataforma; la publica para el donante **solo** el `ADMINISTRATOR`, con confirmación explícita de que no se reconoce a ninguna persona, y la decisión queda registrada. No sirve para leer ni verificar a simple vista.
- **currentLocation / lastKnownLocation:** ubicación confirmada actual (nula solo en `DISPATCHED`) y último nodo confirmado (nunca nula tras el registro; ADR-014).
- **custodianRef vs. beneficiaryRef:** responsable operativo actual y receptor final; nunca se sobrescriben entre sí.
- **DonationReadPort / DonationReadModel:** puerto de lectura en `core.application.port.out` que expone a `api` solo lo que permite el perímetro (ADR-021-D); `confirmedAllocationAmount` se calcula en el adaptador.
- **DomainEvent / DomainEventPayload:** hecho histórico inmutable; el payload lleva solo datos de negocio y el envoltorio (id, secuencia, hashes) se ensambla fuera del dominio.
- **evidenceRef:** `sha256:` + 64 hexadecimales en minúscula; huella de la foto de un paso, subida antes del comando y usada por él.
- **Event Sourcing / Event Store:** el estado se deriva de la secuencia de eventos inmutables; el Event Store es la colección *append-only*, encadenada criptográficamente, única fuente de verdad.
- **EventPayloadRegistry:** mapea `eventType` y `schemaVersion` a la clase del payload.
- **expectedVersion:** versión del agregado al evaluar la lógica; precondición de escritura.
- **externalEventId:** identificador de un hecho de un sistema externo (webhook de pasarela) para deduplicar sus reintentos.
- **GENESIS_HASH:** `previousHash` del primer evento de cualquier stream.
- **Arquitectura hexagonal:** el dominio define puertos que la infraestructura implementa.
- **JCS (RFC 8785):** representación canónica de un JSON para que el hash sea reproducible.
- **lifecycleStatus:** estado actual de la máquina de estados de un agregado.
- **locationZone:** valor público derivado de `currentLocation` por consulta exacta a `location_reference`; `null` si no está registrada. Desde ADR-029 Enm. 2, la zona de cada paso nuevo sale del texto sellado en el evento (municipio DIVIPOLA) y la tabla solo respalda eventos anteriores.
- **Merkle Tree / Merkle Root:** agrupa hashes de eventos en una raíz, que es lo que se ancla en blockchain (nunca los eventos).
- **NarrativeReadPort:** puerto neutral en `contracts` (ADR-024); `getOrTriggerGeneration(fundId)` puede disparar generación asíncrona como efecto colateral y el nombre lo dice.
- **Monolito modular:** un despliegue con fronteras internas estrictas entre `contracts`, `core`, `crypto`, `ai`, `api`, `identity`, `convocatoria`, ensamblados en `app`.
- **Outbox transaccional / saga:** el evento y un mensaje pendiente se persisten en la misma transacción local; un proceso asíncrono lo reenvía con reintentos, backoff y compensación.
- **occurredAt / recordedAt:** ver §6.3.
- **payload:** contenido de negocio de un tipo de evento, sin metadatos de infraestructura.
- **PII Vault / referencia opaca:** los datos personales viven aparte y se referencian por identificadores opacos, nunca directamente en datos destinados a blockchain.
- **previousHash / eventHash:** hash del evento anterior y hash del actual canonicalizado junto con aquel; el propio `eventHash` nunca entra en el material hasheado.
- **PhysicalAsset:** ver §6.1.
- **ProjectionEventHandler:** interfaz común de los proyectores (`DonationProjectionHandler`, `DonationAuditFactsHandler`).
- **Proyección / Read Model:** vista de lectura derivada, optimizada para consulta y reconstruible desde el Event Store.
- **quantity / unitOfMeasure:** cantidad actual (mutable solo por split) y unidad (inmutable).
- **refundId:** idempotencia interna de un reembolso.
- **rootAssetRef / parentAssetRef:** genealogía de un split: ancestro original y padre directo (nulo si es raíz).
- **schemaVersion:** versión del payload de un tipo de evento, para evolucionar sin romper el histórico.
- **sequence:** entero monótono que ordena los eventos de un stream; `(streamId, sequence)` es único; el primero es 1 y cada siguiente es el anterior + 1, sin huecos (D-SEQ).
- **statusBeforeSplit:** estado del padre justo antes del split, sellado en `ASSET_SPLIT`, para resucitarlo si una compensación revierte un split que lo dejó en `DEPLETED`.
- **Stream:** secuencia completa y ordenada de eventos de un agregado.
- **TraceabilityEvent:** modelo conceptual del evento completo persistido.
- **Tracking Code:** credencial bearer sin estado que autoriza la consulta pública de una donación (ADR-021-A/B): `Base64URL(fundId|expiry).Base64URL(HMAC-SHA-256("tracking:v1:" + fundId|expiry))`; se genera fuera de la Fase 3.
- **Upcasting:** transformar un evento de un esquema antiguo al esperado por el código actual sin modificar el almacenado.

---

## Notas históricas (superado; se conserva con su fecha)

- **N-1 (2026-10-07) — Estado de la Fase 6 antes de la implementación.** Corte auditado sobre `develop` en `e269985`: 829 tests (core 229, crypto 50, ai 19, api 34, identity 261, convocatoria 194, app 42). Capas pendientes entonces: orquestador de fondos (ADR-045), JWT y endpoints de plataforma, migración de batches legacy, productor y consumidor de IA (bloqueados por C2–C5 de ADR-040), todos los endpoints de producto (ADR-041). Todo eso está hecho (§9.2).
- **N-2 (2026-11-03/06) — Cortes de la métrica.** `develop` en `11f8698` (2026-11-03): 2 058 tests (core 393, crypto 57, ai 47, api 177, identity 437, convocatoria 272, app 675; contracts 0). Documentación técnica integral en `technical_documentation.md` (2026-11-06; auditoría previa en `auditoria-fase6-codigo-vs-documentacion.md`, 2026-10-07, `develop` en `e269985`). La métrica vigente es la de §9.2 (2 255 tests; subió de 2 058 por las Tareas 1 a 3 de fotos, el perfil `demo-historico` y los cambios fusionados desde el 2026-11-03).
- **N-3 — Métricas de las fases cerradas.** Fase 2: 14 tareas más 6 correcciones, verde contra Testcontainers real. Fase 3: tres endpoints públicos verificados de punta a punta (filtro real + controlador real + puerto + mapper); reactor de 6 módulos. Fase 4: 66 tests de `identity` más el escenario end-to-end de la Tarea 4.10 con verificación exacta del audit log; reactor de 7 módulos; reintento transaccional verificado con concurrencia real forzada (no simulada).
- **N-4 (2026-10-07) — Verificación de integridad en su primer corte.** `IntegrityVerificationUseCase` recalculaba la raíz con el `eventHash` guardado, sin recalcularlo desde el payload ni verificar `previousHash` (hallazgo B-9). Resuelto por ADR-039 Enm. 1 (§8.2).
- **N-5 — Versiones anteriores del predictor.** El artefacto era `campaign-predictor-baseline-0.2.0.json`; el vigente es `baseline-0.3.0` (nota de Adonis del 2026-11-11 en ADR-044 Enm. 1). Resultados de 0.2.0 en `paxfide-predictor/RESULTADOS-baseline-0.2.0.md`.
- **N-6 — Estado de los ADR antes de implementarse.** ADR-044 figuraba «PROPUESTO» con número reservado y sin documento; ADR-038 «JWT pendiente»; ADR-040 «C2–C5 y C8 abiertos»; ADR-041 «sin código»; ADR-039 Enm. 1 «BORRADOR (2026-10-07), dirección aprobada por Carlos»; ADR-043 «PROPUESTO (2026-09-30)». Los estados vigentes están en §5.
- **N-7 — Frases de procedencia de la copia del maestro** («el wrapper Web3j vive en `generated-test-sources`», «Sin implementación ni consumidor todavía» de `CampaignAuditFactsPort`, «Sin mecanismo…» de revocación) quedaron resueltas o reformuladas en §9.3 y §10.
- **N-8 (2026-10-07) — «Próximo hito inmediato» de la Fase 6** (cadena del dinero D-P8 → T1/P8 → orquestador ADR-045 → API → golden path; cadena de narrativa de convocatoria con `campaignRef` en `PhysicalAsset`): ejecutado. La lista de candidatos «(a) donaciones en especie, (b) integración HTTP de identidad, (c) deuda menor» se reemplazó por los próximos pasos de §9.2.
- **N-9 (2026-11-19) — Correcciones hechas al consolidar la copia del maestro.** (1) El catálogo daba por libre ADR-052; hoy hay ADR-052 a ADR-055 (con documento) y reservas 056 a 058 en una rama no fusionada, y ADR-059 en `develop`: el siguiente libre es **ADR-060**. (2) La copia atribuía la aprobación de ADR-029 Enm. 2 a Carlos; el documento dice que la aprueba Manuel (#170, 2026-11-02). (3) La copia daba ADR-050 Enm. 1 y 2 por «aprobadas e implementadas» sin matizar; el estado de cada una consta en §5.IX. (4) La copia repetía en bloques consecutivos el texto antiguo y el nuevo de varias filas del stack, de los módulos (`app`), de ADR-029, 038, 039, 040, 041, 044, 045, 047 a 051, de la métrica de la Fase 6 y de la deuda del wrapper Web3j, y listaba la Fase 6 con dos versiones de la tabla de estado; aquí cada tema aparece una vez. (5) La copia decía «ADR-001 a ADR-046/051 forman el contrato»; ver §10. (6) El texto «Pendiente: Carlos» no aparece en esta copia como frase propia; los «pendiente de ratificar por Carlos» que siguen en los ADR firmados **no se han tocado** (regla: un ADR solo cambia con una nota fechada); su listado va en el PR para que Manuel decida.
- **N-10 (2026-11-24) — Auditoría integral contra `develop` `86933a4c` y sincronización con `technical_documentation.md`.** (1) Versiones del stack (§3) corregidas directamente contra los `pom.xml` y `application.yml`: `nimbus-jose-jwt` `10.10` (antes `9.37.3`), `software.amazon.awssdk` `2.55.10` (antes `2.29.51`), `web3j` `4.12.0` (antes `4.12.2`), modelo Groq `openai/gpt-oss-20b` (antes `llama-3.3-70b-versatile`), y eliminada la referencia inexistente a `JaCoCo 0.8.12` y `Awaitility 4.2.0`. (2) Corregido el implementador de `CampaignAuditFactsPort` a `CampaignAuditFactsProducer` en `app` (antes citaba `CampaignAuditFactsQueryService` en `core`). (3) Incorporados ADR-032 Enm. 1 (PR #194/#195, implementado), ADR-044 Enm. 4 (PR #196, PROPUESTA), `DeviceCredential`/`DeviceToken` y la métrica de 2 262 tests (`evidencia-ci/ci-local-12aa498-2026-11-19T19-35-10Z.txt`). (4) Actualizada integralmente `Documentos/technical_documentation.md` sobre `86933a4c`.
