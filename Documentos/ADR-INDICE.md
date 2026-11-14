# Índice de ADRs (generado)

Generado por `scripts/docs/indice-adr.py --escribir` desde el catálogo de `documento-maestro-proyecto.md` §5. No se edita a mano: la fuente es el catálogo.

| ADR | Ficheros en Documentos/ | Entrada del catálogo |
|---|---|---|
| ADR-001 | (en el catálogo) | **ADR-001 — Segregación de Agregados.** `Fund` y `PhysicalAsset` son Aggregate Roots transaccionalmente independientes. La asignación de donaciones a activos fí |
| ADR-002 | (en el catálogo) | **ADR-002 — Granularidad del Activo Físico.** `PhysicalAsset` = Unidad Logística Trazable, identificada por `assetId` opaco. No es obligatoriamente una unidad i |
| ADR-003 | (en el catálogo) | **ADR-003 — Separación Custodia/Geografía.** `custodianRef` (responsabilidad operativa) y `currentLocation` (nodo logístico) son ejes independientes. Ningún eve |
| ADR-004 | (en el catálogo) | **ADR-004 — Reembolsos: Sobregiro Controlado y Preservación del Histórico.** `Fund` nunca muta `clearedAmount` destructivamente. `refundedAmount` es magnitud pr |
| ADR-005 | (en el catálogo) | **ADR-005 — Desconsolidación Parcial y Repetible (ASSET_SPLIT).** El split extrae una cantidad Q del padre; el padre **sobrevive** si `Q_after > 0` (no es termi |
| ADR-006 | (en el catálogo) | **ADR-006 — Telemetría vs. Auditoría Criptográfica.** GPS continuo o paradas no oficiales quedan **excluidos** del Event Store y del pipeline criptográfico (JCS |
| ADR-007 | (en el catálogo) | **ADR-007 — Coordinación de Sagas vía Outbox Transaccional.** Comunicación cross-stream (Fund→PhysicalAsset, PhysicalAsset padre→hijo) vía `OutboxSagaCoordinato |
| ADR-008 | `ADR-008-enmienda-1-recuperacion-coordinador-sagas.md` | **ADR-008 — Compensación de Fugas de Inventario/Dinero.** *(Enmienda 1 de ADR-007/008 **APROBADA — Carlos, 2026-10-07**: `ADR-008-enmienda-1-recuperacion-coordi |
| ADR-009 | (en el catálogo) | **ADR-009 — Idempotencia Interna de Compensación.** Todo comando de compensación referencia el identificador único de la operación que revierte (`allocationId`, |
| ADR-010 | (en el catálogo) | **ADR-010 — Cuarentena de Eventos Fuera de Orden.** Un stream sin procesar tiene `lastProcessedSequence = 0` (génesis = 1, enmienda D-SEQ, 2026-10-07). Si `inco |
| ADR-011 | (en el catálogo) | **ADR-011 — Índice de Resolución (asset_index).** Índice técnico reconstruible (`assetId → projectionId`, donde `projectionId = fundId` estrictamente), NUNCA fu |
| ADR-012 | (en el catálogo) | **ADR-012 — Asignación Financiera en Dos Fases.** Cruzar `Fund`↔`PhysicalAsset` sin fingir atomicidad: `ALLOCATION_REQUESTED` mueve dinero a `pendingAllocationA |
| ADR-013 | (en el catálogo) | **ADR-013 — Taxonomía Estricta de Identificadores.** `commandId` (idempotencia de intención/reintento de red), `externalEventId` (idempotencia de hecho externo, |
| ADR-014 | (en el catálogo) | **ADR-014 — Separación Custodia/Beneficiario y Continuidad de Ubicación.** En `ASSET_DELIVERED`, `beneficiaryRef` se sella solo en el payload, nunca sobrescribe |
| ADR-015 | (en el catálogo) | **ADR-015 — Arquitectura Desacoplada de la Capa de Lectura.** Cuatro componentes con responsabilidades distintas: `DonationProjection` (vista de usuario), `Asse |
| ADR-016 | (en el catálogo) | **ADR-016 — Génesis Dual de Fund.** `Fund` admite dos caminos de creación: `FUND_REGISTERED` (con promesa previa, `PLEDGED`) o `FUNDS_CLEARED` directo como prim |
| ADR-017 | (en el catálogo) | **ADR-017 (implícito, emergente en Tarea 10/11) — Framework de Proyección Genérico.** `ProjectionEventSource` y `ProjectionRetryScheduler` son genéricos, no aco |
| ADR-018 | (en el catálogo) |  |
| ADR-020 | (en el catálogo) | **ADR-020 — Ubicación y Dirección de Dependencia de la Capa API.** Módulo Maven nuevo `api`, separado de `app` (que permanece como bootstrap puro). Dependencia  |
| ADR-021 | `ADR-021-D-enmienda-1-huella-evidencia.md` | **ADR-021-A — Mecanismo Primario de Autorización.** Tracking code (bearer credential) sin cuenta autenticada para el flujo público de consulta de donación. Cuen |
| ADR-022 | (en el catálogo) | **ADR-022 — Resolución Manual de Lotes Atascados (JMX).** La resolución de lotes en estado `STUCK` durante el anclaje a blockchain (Web3j) se realiza exclusivam |
| ADR-023 | (en el catálogo) | **ADR-023 — Sin Spring Security.** El mecanismo de autorización de Fase 3 (bearer token stateless con HMAC) no necesita sesiones, roles ni autenticación de usua |
| ADR-024 | `ADR-024-enmienda-narrativa-endpoint.md` | **ADR-024 — Exposición Asíncrona de Narrativas (Approved with amendment).** `NarrativeReadPort` (en `contracts`, único puerto que cruza hacia `ai`) con estados  |
| ADR-025 | `ADR-025-persistencia-identidad.md` | **ADR-025 — Persistencia de Identidad y Cuentas.** Sin Event Sourcing: CRUD convencional sobre MongoDB para `Account`/`Organization`, con Audit Log append-only  |
| ADR-026 | `ADR-026-enmienda-desactivacion-platform-administrator.md`<br>`ADR-026-modelo-dominio-identidad.md` | **ADR-026 — Modelo de Dominio de Identidad.** Dos Aggregate Roots: `Account` (email, passwordHash, status, organizationId nullable) y `Organization` (type, memb |
| ADR-027 | `ADR-027-modulo-identity.md` | **ADR-027 — Módulo Maven independiente `identity`.** Bounded context distinto de `core` (identidad vs. trazabilidad); mezclarlos contaminaría ciclo de vida y mo |
| ADR-028 | `ADR-028-relacion-organization-fund.md` | **ADR-028 — Relación `Organization ↔ Fund`.** Reconstrucción histórica, aprobada en Fase 5. |
| ADR-029 | `ADR-029-enmienda-1-campaignref.md`<br>`ADR-029-enmienda-2-zona-publica.md`<br>`ADR-029-organization-physicalasset-donacion-especie.md` | **ADR-029 — `Organization ↔ PhysicalAsset` y Donación en Especie.** Aprobada en Fase 5, revisada (C3/C5) para el enforcement vigente de `organizationRef`. `Phys |
| ADR-030 | `ADR-030-actorref-ubicacion-persistencia.md` | **ADR-030 — `actorRef`: Ubicación y Persistencia.** Aprobada en Fase 5. |
| ADR-031 | `ADR-031-taxonomia-actorref.md` | **ADR-031 — Taxonomía de `ActorRef`.** Aprobada en Fase 5. |
| ADR-032 | `ADR-032-autorizacion-comandos-core-matriz.md` | **ADR-032 — Autorización de Comandos en `core`.** Puerto `Identity ↔ Core` (`IdentityPrincipalPort` en `contracts`), guardas de pertenencia y rol, matriz de aut |
| ADR-033 | `ADR-033-contrato-asset-registration-saga.md` | **ADR-033 — Contrato y Envelope de Asset Registration Saga.** Aprobado parcialmente. |
| ADR-034 | `ADR-034-read-model-pending-allocation.md` | **ADR-034 — Read Model de Pending Allocation.** Aprobado; sustituye a `FundAllocationSagaPolicy` por resolución manual auditada. |
| ADR-035 | `ADR-035-human-actor-authorization.md` | **ADR-035 — `HumanActor` como variante de `ActorRef`.** Approved. |
| ADR-036 | `ADR-036-reversion-administrativa-de-asignacion.md` | **ADR-036 — Reversión Administrativa de Asignación.** Approved. |
| ADR-037 | `ADR-037-convocatoria-ledger-assignment-donationintent.md`<br>`ADR-037-enmienda-1-convocatoria.md`<br>`ADR-037-enmienda-2-convocatoria.md`<br>`ADR-037-enmienda-3-pasarela-y-consulta-de-intencion.md`<br>`ADR-037-enmienda-4-solicitud-y-aprobacion-de-configuracion.md`<br>`ADR-037-enmienda-5-fecha-de-creacion-y-vista-del-historial.md` | **ADR-037 — Convocatoria, CampaignFundingLedger, CampaignAssignment, DonationIntent.** Aprobado con Enmienda 1 (APROBADA). Enmienda 2 (confirmación y aplicación |
| ADR-038 | `ADR-038-identidad-platform-administrator-verificacion-organization.md` | **ADR-038 — Identidad: HumanActor, Platform Administrator, Verificación de Organization, Autenticación.** Approved; tareas 1–8 implementadas y fusionadas (§9 de |
| ADR-039 | `ADR-039-blockchain-merkle-producer-integrity-verification.md`<br>`ADR-039-enmienda-1-blockchain.md` | **ADR-039 — Productor de MerkleBatch e IntegrityVerificationPort.** Aprobado; implementado, incluida la recuperación de batches `COLLECTING`. |
| ADR-040 | `ADR-040-ia-convocatoria-audit-facts.md` | **ADR-040 — ConvocatoriaAuditFacts (IA).** Aprobado parcialmente; C1 cerrado (`CampaignAuditFactsPort` separado de `AuditFactsPort`), C2–C5 y C8 abiertos. |
| ADR-041 | `ADR-041-api-frontend-contratos-http.md` | **ADR-041 — APIs de producto y Frontend.** Aprobado (arquitectura de la capa); sin código. |
| ADR-042 | `ADR-042-enmienda-1-reconstruccion-jmx.md`<br>`ADR-042-orquestacion-centralizada-reintentos-proyeccion.md` | **ADR-042 — Orquestación Centralizada de Reintentos de Proyección (A7.2).** Aprobado e implementado en Fase 5; refina ADR-010/017. Conserva el número; el ADR de |
| ADR-043 | `ADR-043-frontend-movil-paxfide-mobile.md` | **ADR-043 — Frontend móvil `paxfide-mobile`.** PROPUESTO (2026-09-30). Hubo una colisión: los documentos de Convocatoria usaban también "ADR-043" para la recupe |
| ADR-044 | `ADR-044-componente-predictivo-python.md`<br>`ADR-044-enmienda-1-prediccion-en-backend.md` | **ADR-044 — Componente predictivo en Python.** PROPUESTO. **Número reservado**; el documento todavía no está en el repositorio. |
| ADR-045 | `ADR-045-recuperacion-aplicacion-fondos.md` | **ADR-045 — Recuperación de la aplicación de fondos de `DonationIntent`** (`ADR-045-recuperacion-aplicacion-fondos.md`). **APROBADO — Carlos, 2026-10-07**. Ante |
| ADR-046 | (en el catálogo) | **ADR-046 — Frontend web `paxfide-web`** (`ADR-046-frontend-web-paxfide-web.md`, **repositorio `Toffy22Cj/PaxFide`**). APROBADO el 2026-09-28. Antes se numeraba |
| ADR-047 | `ADR-047-jwt-libreria-y-secreto-de-firma.md` | **ADR-047 — JWT: librería, algoritmo y secreto de firma** (`ADR-047-jwt-libreria-y-secreto-de-firma.md`). **APROBADO — Carlos, 2026-10-07**, con las precisiones |
| ADR-048 | `ADR-048-donorref-desde-la-cuenta.md` | **ADR-048 — Origen del `donorRef`** (`ADR-048-donorref-desde-la-cuenta.md`). **APROBADO — Carlos, 2026-10-07**, con seudónimo aleatorio por cuenta. *Nota 2026-1 |
| ADR-049 | `ADR-049-correo-e-invitaciones-a-organizaciones.md` | **ADR-049 — Envío de correo e invitaciones para incorporar miembros a una organización** (`ADR-049-correo-e-invitaciones-a-organizaciones.md`). **Reservado el 2 |
| ADR-050 | `ADR-050-cuenta-y-sesiones.md`<br>`ADR-050-enmienda-1-iat-del-login.md`<br>`ADR-050-enmienda-2-verificacion-correo-y-avisos.md`<br>`ADR-050-enmienda-3-registro-con-google.md` | **ADR-050 — Cuenta y sesiones** (`ADR-050-cuenta-y-sesiones.md`). **APROBADO — Carlos, 2026-10-18.** Registro en dos pasos, aceptaciones legales, revocación con |
| ADR-051 | `ADR-051-fotos-y-almacenamiento.md` | **ADR-051 — Fotos y almacenamiento** (`ADR-051-fotos-y-almacenamiento.md`). **APROBADO — Carlos, 2026-10-18, con la Enmienda 1 (18:45Z).** Almacenamiento compat |
| ADR-052 | `ADR-052-version-spring-boot.md` | **ADR-052 — Versión de Spring Boot** (T-01). `ADR-052-version-spring-boot.md`. |
| ADR-053 | `ADR-053-limite-de-intentos.md` | **ADR-053 — Límite de intentos** (T-03; cierra DH-56). `ADR-053-limite-de-intentos.md`. |
| ADR-054 | `ADR-054-observabilidad-minima.md` | **ADR-054 — Observabilidad mínima: salud y logs estructurados** (D-01). `ADR-054-observabilidad-minima.md`. |
| ADR-055 | `ADR-055-escaneo-de-dependencias.md` | **ADR-055 — Escaneo de dependencias y SBOM** (T-07). `ADR-055-escaneo-de-dependencias.md`. |
| ADR-056 | (en el catálogo) | **ADR-056 — Autenticación de sistema y actor de integración** (F-08, ADR-A del plan). |
| ADR-057 | (en el catálogo) | **ADR-057 — Contrato público de integración** (F-08, ADR-B del plan). |
| ADR-058 | (en el catálogo) | **ADR-058 — Webhooks salientes** (F-08, ADR-C del plan). |

Siguiente número libre: ADR-059.
