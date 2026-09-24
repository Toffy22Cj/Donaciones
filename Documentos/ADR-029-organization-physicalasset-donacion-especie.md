# ADR-029 — `Organization ↔ PhysicalAsset` y Donación en Especie

## Status
Reconstrucción histórica (Aprobada en Fase 5)

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
  - Orquestado automáticamente mediante saga (`AssetRegisteredSagaPolicy`).
  - `organizationRef` se hereda obligatoriamente del `Fund` asignado.
  - `donorRef = null`: el donante financiero del fondo **no** se hereda como donante del activo físico (distinción semántica deliberada entre donación monetaria y donación física).
  - `donationRef = null`.
- **Camino B (Donación en Especie directa):**
  - Entrada manual registrada por un operador (`RegisterPhysicalAssetFromDonation`).
  - `organizationRef` y `donorRef` son datos de entrada directos y **obligatorios**.
  - `donationRef`: Identificador generado en la capa de aplicación para correlacionar los activos hermanos ingresados en un mismo acto de donación.
  - Se descartó crear un aggregate `InKindDonation`; la cardinalidad es 1:N con activos independientes y no existe invariante transaccional entre hermanos.

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
- Documentos de referencia: `estado-fase5.md` (§2, §5), `plan-ejecucion-agentes-fase5.md` (Bloque 3, Tareas 5.3, 5.4, 5.5).

## Consecuencias
### Positivas
- Se unifica el tratamiento de activos físicos sin bifurcar los tipos de eventos en `EventPayloadRegistry`.
- Se preserva la procedencia inalterable de lotes divididos a lo largo de toda la cadena de custodia.
- Se resuelve el modelo de donaciones en especie sin agregar la complejidad de un agregado transaccional intermedio innecesario.

### Negativas / Restricciones
- La implementación del Application Service de donación en especie (Camino B) requirió diferirse hasta la resolución de identidad humana (`HumanActor`, ADR-035).
