# ADR-029 — `Organization ↔ PhysicalAsset` y Donación en Especie

## Status
Reconstrucción histórica (Aprobada en Fase 5). Revisada en Fase 5 (C3/C5) para reflejar el mecanismo vigente de enforcement de `organizationRef` en el Camino A; la decisión de negocio no cambia.

## Contexto
En Fase 5, el modelo de trazabilidad física requería modelar dos realidades distintas:
1. Activos físicos derivados de asignaciones financieras de fondos (`Fund` → `PhysicalAsset`), denominado **Camino A**.
2. Activos físicos que ingresan directamente como donaciones materiales sin mediación de dinero líquido (Donación en Especie), denominado **Camino B** (Tarea 5.4).
Adicionalmente, se debía definir el comportamiento de herencia de procedencia en la división de lotes (`AssetSplit`).

## Problema
1. ¿Qué representa la referencia a la organización en `PhysicalAsset` y cómo se diferencia de los roles logísticos existentes (`custodianRef`, `beneficiaryRef`)?
2. ¿Cómo se hereda o ingresa la autoridad organizacional en el Camino A frente al Camino B?
3. ¿Debe existir un agregado independiente `InKindDonation` o los activos físicos nacen directamente?
4. ¿Cómo viaja la procedencia (`organizationRef`, `donorRef`, `donationRef`) en las operaciones de división (`AssetSplit`)?

## Decisión Histórica Reconstruida

### 1. `organizationRef` como Autoridad Operativa
`PhysicalAsset.organizationRef` representa la autoridad operativa y de gestión del activo. Es obligatorio e inmutable a partir de la génesis:
- Es independiente de `custodianRef` (responsabilidad y tenencia física temporal, ADR-003).
- Es independiente de `beneficiaryRef` (receptor final de la ayuda humanitaria, ADR-014).

### 2. Dos Caminos de Génesis con Esquema Uniforme
Se mantiene un único esquema de payload en `ASSET_REGISTERED:v2` (`AssetRegisteredV2Payload`):
- **Camino A (Asignación desde `Fund`):**
  - Génesis mediante `PhysicalAssetCommandService.registerPhysicalAsset(commandId, fundId, organizationRef, ...)`, invocado por el llamador que materializa la asignación (ver §2.1). `AssetRegisteredSagaPolicy` **no** crea el activo: es el consumidor posterior de `ASSET_REGISTRATION_SAGA` que confirma (o compensa) la asignación en el `Fund` (ADR-033).
  - Invariante: `PhysicalAsset.organizationRef == Fund.organizationRef` del `Fund` referenciado por `fundId`.
  - `donorRef = null`: el donante financiero del fondo **no** se hereda como donante del activo físico (distinción semántica deliberada entre donación monetaria y donación física).
  - `donationRef = null`.
- **Camino B (Donación en Especie directa):**
  - Entrada manual registrada por un operador (`PhysicalAssetCommandService.registerPhysicalAssetFromDonation`).
  - `organizationRef` y `donorRef` son datos de entrada directos y **obligatorios**.
  - `donationRef`: Identificador generado en la capa de aplicación para correlacionar los activos hermanos ingresados en un mismo acto de donación.
  - Se descartó crear un aggregate `InKindDonation`; la cardinalidad es 1:N con activos independientes y no existe invariante transaccional entre hermanos.

### 2.1 Enforcement vigente del invariante en el Camino A
El diseño original preveía que una saga disparada desde el `Fund` (`FundAllocationSagaPolicy`, NUEVA-4) creara el activo y le trasladara el `organizationRef` del `Fund`, de modo que el invariante se cumpliera por construcción. Esa automatización fue **descartada** por ADR-034 (Opción A: `PENDING_ALLOCATION` visible y de resolución manual) y nunca se implementó. En consecuencia, `organizationRef` llega como dato de entrada del llamador y el invariante se hace cumplir en la capa de aplicación:

- `registerPhysicalAsset` exige `fundId` (nulo o vacío → `InvalidFundReferenceException`).
- Carga el `Fund` desde el Event Store por `fundId`; si no existe → `InvalidFundReferenceException`.
- Compara la organización declarada con `Fund.organizationRef`; cualquier discrepancia (o `Fund` sin organización) se rechaza con `CrossOrganizationAccessException`.
- La validación ocurre **antes** de la autorización (ADR-032/ADR-035) y de cualquier persistencia: un rechazo no deja eventos de `PhysicalAsset`, no toca el stream del `Fund` y no emite `OutboxMessage`.
- Aplica a cualquier `ActorRef`, incluido `SystemActor`/`ExternalActor`: su bypass de autorización (P7/P9) no exime del invariante. La autorización de `HumanActor` solo comprueba pertenencia a la organización *declarada*, no que ésta sea la del `Fund`; por eso el invariante no puede delegarse en ella.

El `organizationRef` persistido en `ASSET_REGISTERED` es, por tanto, siempre el del `Fund`. Si en el futuro se reintroduce una saga que resuelva la organización desde el `Fund`, este enforcement debe mantenerse o sustituirse explícitamente mediante un nuevo ADR.

### 3. Herencia Completa en `AssetSplit` (V2)
En el evento `ASSET_SPLIT:v2` (`AssetSplitV2Payload`):
- El activo hijo hereda íntegramente del padre: `organizationRef`, `donorRef` y `donationRef`.
- No se utilizan campos auxiliares paralelos (a diferencia de `allocationId` y `sourceAllocationId`), porque estos identificadores representan procedencia histórica inmutable del lote físico, no operaciones puntuales.

### 4. Tratamiento de `PhysicalAsset` v1 (Legacy)
Los activos v1 preexistentes que carecen de `organizationRef`:
- Permiten reconstitución y consulta.
- Rechazan cualquier mutación con `PhysicalAssetNotAssociatedToOrganizationException`.

## Evidencia en Código y Repositorio
- `com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV2Payload.java`: Cita explícita `* Ref: ADR-029`.
- `com.traceability.core.domain.physicalasset.payloads.AssetSplitV2Payload.java`: Cita explícita `* Ref: ADR-005, ADR-008, ADR-029`.
- `com.traceability.core.domain.physicalasset.exceptions.PhysicalAssetNotAssociatedToOrganizationException.java`: Cita explícita `* Ref: ADR-029`.
- `com.traceability.core.domain.physicalasset.PhysicalAsset.java`: Implementación de herencia en `split(...)` y validación de `organizationRef`.
- `com.traceability.core.application.command.PhysicalAssetCommandService.java`: `registerPhysicalAsset` y `assertOrganizationMatchesFund` (enforcement del Camino A, §2.1); `registerPhysicalAssetFromDonation` (Camino B).
- Tests del Camino A:
  - `Phase5EndToEndIntegrationTest.testD1_caminoA_registerPhysicalAsset_inheritsFundOrganization_andEmitsSagaEnvelope` (organización del `Fund` en el activo y envelope `ASSET_REGISTRATION_SAGA`).
  - `PhysicalAssetCommandServiceIntegrationTest.registerPhysicalAsset_invalidFundId_doesNotPersist`, `registerPhysicalAsset_nonExistentFund_doesNotPersist`, `registerPhysicalAsset_organizationRefDifferentFromFund_rejectedEvenForSystemActor`.
  - `HumanActorAuthorizationIntegrationTest.physicalAssetCommandService_withHumanActor_fundOfOtherOrganization_rejectedWithoutEffects`.
- Commits: `014d101` (C3, enforcement contra el `Fund` y tests negativos), sobre el productor de `01a9f60`/`d30cd00`.
- Documentos de referencia: `estado-fase5.md` (§2, §5), `plan-ejecucion-agentes-fase5.md` (Bloque 3, Tareas 5.3, 5.4, 5.5), ADR-033 (contrato de la saga), ADR-034 (descarte de la saga de creación automática).

## Consecuencias
### Positivas
- Se unifica el tratamiento de activos físicos sin bifurcar los tipos de eventos en `EventPayloadRegistry`.
- Se preserva la procedencia inalterable de lotes divididos a lo largo de toda la cadena de custodia.
- Se resuelve el modelo de donaciones en especie sin agregar la complejidad de un agregado transaccional intermedio innecesario.

### Negativas / Restricciones
- La implementación del Application Service de donación en especie (Camino B) requirió diferirse hasta la resolución de identidad humana (`HumanActor`, ADR-035).
